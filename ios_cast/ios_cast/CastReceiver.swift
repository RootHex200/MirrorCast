import Foundation
import Network
import AVFoundation
import AVKit
import CoreMedia
import AppKit
import Combine

/// Drives the receiver pipeline: RTSP/TCP listener → interleaved RTP frames →
/// H.264 depacketizer → VideoToolbox decoder → AVSampleBufferDisplayLayer.
///
/// Exposes `frameCount` so a headless harness can assert the pipeline is decoding
/// without needing a window.
final class CastReceiver: ObservableObject {

    @Published private(set) var state: ReceiverState = .idle
    @Published private(set) var frameCount: Int = 0
    @Published private(set) var droppedFrames: Int = 0
    @Published private(set) var lastError: String?

    /// Set by the UI layer; decoded sample buffers are enqueued on this layer when present.
    var displayLayer: AVSampleBufferDisplayLayer? {
        didSet { attachDisplayLayer() }
    }

    static var port: NWEndpoint.Port = 7236

    private var listener: NWListener?
    private var advertiser: ServiceAdvertiser?
    private var activeConnection: NWConnection?
    let stateMachine = RTSPStateMachine()
    private var reconnect = ReconnectStrategy()
    /// Identities of connections we cancelled ourselves; their async `.cancelled`
    /// callbacks must NOT trigger peer-loss on the new active connection.
    private var intentionallyCancelled: Set<ObjectIdentifier> = []
    private let decodeQueue = DispatchQueue(label: "cast.decode")
    private let depacketizer = H264Depacketizer()
    private lazy var decoder: H264Decoder = {
        let d = H264Decoder(queue: decodeQueue)
        d.onSampleBuffer = { [weak self] sb in
            DispatchQueue.main.async {
                self?.handleDecodedSampleBuffer(sb)
            }
        }
        return d
    }()

    // RTSP session state
    private var sessionID: String = ""
    private var configuredVideoChannel: Int? = nil
    private var videoChannelToTransport: [Int: RTSPTransportKind] = [:]

    // Inbound buffer (RTSP requests + interleaved frames share one byte stream).
    private var rxBuffer = Data()

    func start() {
        #if DEBUG_CAST
        print("[cast] start() called")
        #endif
        guard listener == nil else { return }
        do {
            let params = NWParameters.tcp
            let listener = try NWListener(using: params, on: CastReceiver.port)
            listener.newConnectionHandler = { [weak self] conn in
                self?.handleNewConnection(conn)
            }
            listener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    self?.setState(.advertising)
                case .failed(let err):
                    self?.lastError = "Listener failed: \(err)"
                    self?.setState(.idle)
                default:
                    break
                }
            }
            listener.start(queue: .global(qos: .userInitiated))
            self.listener = listener
            if advertiser == nil {
                let adv = ServiceAdvertiser()
                adv.start()
                advertiser = adv
            }
        } catch {
            lastError = "Failed to bind: \(error)"
            setState(.idle)
        }
    }

    func stop() {
        teardownConnection()
        advertiser?.stop()
        advertiser = nil
        listener?.cancel()
        listener = nil
        setState(.idle)
    }

    // MARK: - Connection handling

    private func handleNewConnection(_ conn: NWConnection) {
        print("[cast] new connection accepted: \(conn)")
        // Always accept the newest connection. If an old one is lingering (Android
        // tapped Cast again after a stale session), cancel it first. Track its
        // identity so its async .cancelled callback doesn't tear down the new one.
        if let old = activeConnection {
            print("[cast] replacing stale active connection")
            intentionallyCancelled.insert(ObjectIdentifier(old))
            old.cancel()
            activeConnection = nil
        }
        activeConnection = conn
        rxBuffer.removeAll()
        sessionID = ""
        configuredVideoChannel = nil
        videoChannelToTransport.removeAll()
        conn.stateUpdateHandler = { [weak self, weak conn] state in
            guard let self, let conn else { return }
            print("[cast] conn state: \(String(describing: state))")
            switch state {
            case .ready:
                try? self.stateMachine.apply(.connect)
                self.setState(.handshaking)
                self.receiveLoop()
            case .failed(let err):
                if self.intentionallyCancelled.remove(ObjectIdentifier(conn)) == nil {
                    self.lastError = "Connection failed: \(err)"
                    self.handlePeerLoss()
                }
            case .cancelled:
                if self.intentionallyCancelled.remove(ObjectIdentifier(conn)) == nil {
                    self.handlePeerLoss()
                }
            default:
                break
            }
        }
        conn.start(queue: .global(qos: .userInitiated))
    }

    private func receiveLoop() {
        guard let conn = activeConnection else { return }
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                print("[cast] rx \(data.count)B")
                self.consumeInbound(data: data)
            }
            if let error {
                print("[cast] rx error: \(error)")
                self.lastError = "Receive error: \(error)"
                self.teardownConnection()
                return
            }
            if isComplete {
                print("[cast] rx EOF")
                self.teardownConnection()
                return
            }
            self.receiveLoop()
        }
    }

    // MARK: - Inbound byte stream

    private func consumeInbound(data: Data) {
        rxBuffer.append(data)
        #if DEBUG_CAST
        print("[cast] rx \(data.count)B, buffer now \(rxBuffer.count)B")
        #endif
        var consumedTotal = 0
        #if DEBUG_CAST
        print("[cast] consume entry: consumedTotal=\(consumedTotal) rxBuffer.count=\(rxBuffer.count)")
        #endif
        while consumedTotal < rxBuffer.count {
            let offset = consumedTotal
            #if DEBUG_CAST
            print("[cast] consume loop at \(offset)/\(rxBuffer.count) byte0=0x\(String(rxBuffer[offset], radix: 16))")
            #endif
            // Try interleaved RTP/RTCP first: '$' = 0x24
            if rxBuffer[offset] == 0x24 {
                if let (frame, consumed) = InterleavedFrameReader.parse(buffer: rxBuffer, offset: offset) {
                    handleInterleavedFrame(frame)
                    consumedTotal += consumed
                    continue
                } else {
                    // Need more bytes for this frame.
                    return
                }
            } else {
                // RTSP request line.
                if let (req, consumed) = RTSPParser.parseRequest(buffer: rxBuffer, startOffset: offset) {
                    handleRTSPRequest(req)
                    consumedTotal += consumed
                    continue
                } else {
                    #if DEBUG_CAST
                    let preview = String(data: rxBuffer.subdata(in: offset..<min(rxBuffer.count, offset + 200)), encoding: .utf8) ?? "<binary>"
                    print("[cast] parse stalled at offset \(offset)/\(rxBuffer.count): \(preview)")
                    #endif
                    return
                }
            }
        }
        if consumedTotal > 0 {
            if consumedTotal >= rxBuffer.count {
                rxBuffer = Data()
            } else {
                rxBuffer = rxBuffer.subdata(in: consumedTotal..<rxBuffer.count)
            }
        }
    }

    // MARK: - RTSP handling

    private func handleRTSPRequest(_ req: RTSPRequest) {
        print("[cast] <- \(req.method) \(req.uri) CSeq=\(req.cseq ?? "?") body=\(req.body.count)B")
        for (k, v) in req.headers { print("[cast]    \(k): \(v)") }
        if !req.body.isEmpty, let s = String(data: req.body, encoding: .utf8) { print("[cast]    body: \(s)") }
        let cseq = req.cseq ?? "0"
        switch req.method {
        case .options:
            sendResponse(method: "OPTIONS", cseq: cseq, headers: [
                "Public": "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, ANNOUNCE, RECORD, GET_PARAMETER"
            ])
        case .describe:
            let sdp = Self.sdpDescription()
            let headers: [String: String] = [
                "Content-Type": "application/sdp",
                "Content-Length": "\(sdp.count)"
            ]
            if sessionID.isEmpty { sessionID = UUID().uuidString }
            sendResponse(method: "DESCRIBE", cseq: cseq, headers: headers, body: sdp)
        case .announce:
            if sessionID.isEmpty { sessionID = UUID().uuidString }
            extractSpropParameterSets(from: req.body)
            sendResponse(method: "ANNOUNCE", cseq: cseq, headers: [:])
        case .setup:
            if sessionID.isEmpty { sessionID = UUID().uuidString }
            // Accept interleaved RTP on the channel the client asked for, or default to 0.
            var channel = 0
            if let transport = req.headers["Transport"] {
                channel = extractInterleavedChannel(transport: transport) ?? 0
            }
            configuredVideoChannel = channel
            videoChannelToTransport[channel] = .interleaved
            let transportHeader = "RTP/AVP/TCP;unicast;interleaved=\(channel)-\(channel + 1)"
            sendResponse(method: "SETUP", cseq: cseq, headers: [
                "Session": sessionID,
                "Transport": transportHeader
            ])
        case .play:
            try? stateMachine.apply(.play)
            setState(.streaming)
            sendResponse(method: "PLAY", cseq: cseq, headers: [
                "Session": sessionID,
                "Range": "npt=0.000-"
            ])
        case .teardown:
            try? stateMachine.apply(.teardown)
            try? stateMachine.apply(.reset)
            sendResponse(method: "TEARDOWN", cseq: cseq, headers: ["Session": sessionID])
            teardownConnection()
        case .getParameter:
            sendResponse(method: "GET_PARAMETER", cseq: cseq, headers: ["Session": sessionID])
        case .record:
            // RECORD from an ANNOUNCE/SETUP/RECORD sender is equivalent to PLAY for
            // state-machine purposes: the stream is now live.
            try? stateMachine.apply(.play)
            setState(.streaming)
            sendResponse(method: "RECORD", cseq: cseq, headers: ["Session": sessionID])
        }
    }

    private func extractInterleavedChannel(transport: String) -> Int? {
        // Forms: "interleaved=0-1", "interleaved=0"
        guard let range = transport.range(of: "interleaved=") else { return nil }
        let rest = transport[range.upperBound...]
        var digits = ""
        for ch in rest {
            if ch.isNumber { digits.append(ch) } else { break }
        }
        return Int(digits)
    }

    private func sendResponse(method: String, cseq: String, headers: [String: String], body: Data = Data()) {
        guard let conn = activeConnection else { return }
        var response = "RTSP/1.0 200 OK\r\n"
        response += "CSeq: \(cseq)\r\n"
        if !sessionID.isEmpty && !headers.keys.contains("Session") {
            response += "Session: \(sessionID)\r\n"
        }
        for (k, v) in headers {
            response += "\(k): \(v)\r\n"
        }
        response += "\r\n"
        var bytes = Data(response.utf8)
        if !body.isEmpty { bytes.append(body) }
        #if DEBUG_CAST
        print("[cast] -> \(method) response (\(bytes.count)B)")
        #endif
        conn.send(content: bytes, completion: .contentProcessed { _ in })
    }

    // MARK: - RTP / depacketizer / decoder

    /// Parses `sprop-parameter-sets` from an ANNOUNCE's SDP and primes the depacketizer/decoder
    /// with the SPS/PPS NAL units so IDR-less streams can still decode.
    private func extractSpropParameterSets(from sdp: Data) {
        guard let sdpString = String(data: sdp, encoding: .utf8) else {
            #if DEBUG_CAST
            print("[cast] sprop: SDP not utf8")
            #endif
            return
        }
        #if DEBUG_CAST
        print("[cast] sprop: scanning SDP (\(sdp.count)B)")
        #endif
        for line in sdpString.components(separatedBy: "\r\n").flatMap({ $0.components(separatedBy: "\n") }) {
            guard line.hasPrefix("a=fmtp:") else { continue }
            guard let range = line.range(of: "sprop-parameter-sets=") else { continue }
            let after = line[range.upperBound...]
            let value = after.prefix(while: { $0 != ";" && $0 != " " && $0 != "\r" })
            let b64s = value.split(separator: ",")
            var sps: Data? = nil
            var pps: Data? = nil
            for (i, b64) in b64s.enumerated() {
                guard let nal = Data(base64Encoded: String(b64)) else { continue }
                if i == 0 { sps = nal }
                if i == 1 { pps = nal }
                #if DEBUG_CAST
                let t = nal.first.map { String($0 & 0x1F) } ?? "?"
                print("[cast] sprop nal #\(i) type=\(t) \(nal.count)B")
                #endif
            }
            if let sps, let pps {
                decoder.prime(sps: sps, pps: pps)
            }
        }
    }

    private func handleInterleavedFrame(_ frame: InterleavedFrame) {
        print("[cast] interleaved frame ch=\(frame.channel) \(frame.payload.count)B")
        guard let videoChannel = configuredVideoChannel else {
            print("[cast] no configuredVideoChannel, dropping frame")
            return
        }
        guard frame.channel == videoChannel else {
            print("[cast] frame on ch=\(frame.channel), expected \(videoChannel) — RTCP, ignoring")
            return
        }
        guard let packet = RTPPacket(frame.payload) else {
            print("[cast] RTP parse failed")
            RTPMalformedLog.note("RTP parse failed (\(frame.payload.count)B)")
            return
        }
        if let accessUnit = depacketizer.consume(packet: packet) {
            // Flow control: if the display layer is saturated, drop the AU instead
            // of queueing more decode work. Keeps the TCP receive loop responsive.
            if let layer = displayLayer, !layer.isReadyForMoreMediaData {
                droppedFrames += 1
                return
            }
            decoder.decode(accessUnit: accessUnit)
        }
    }

    private func handleDecodedSampleBuffer(_ sb: CMSampleBuffer) {
        frameCount += 1
        if let layer = displayLayer, layer.isReadyForMoreMediaData {
            layer.enqueue(sb)
        } else {
            droppedFrames += 1
        }
    }

    private func attachDisplayLayer() {
        // No-op: the layer is enqueued against on-demand in handleDecodedSampleBuffer.
    }

    private func teardownConnection() {
        if let conn = activeConnection {
            intentionallyCancelled.insert(ObjectIdentifier(conn))
            conn.cancel()
        }
        activeConnection = nil
        sessionID = ""
        configuredVideoChannel = nil
        try? stateMachine.apply(.reset)
        if state == .streaming || state == .handshaking {
            setState(.idle)
        }
    }

    /// Called on connection failure or peer-induced TEARDOWN. We retry the
    /// listener-backed handshake using exponential backoff capped at 5s.
    private func handlePeerLoss() {
        try? stateMachine.apply(.peerLost)
        if let conn = activeConnection {
            intentionallyCancelled.insert(ObjectIdentifier(conn))
            conn.cancel()
        }
        activeConnection = nil
        configuredVideoChannel = nil
        setState(.idle)  // surface "Reconnecting…" state via RTSPStateMachine observers
        // The listener is still up; the sender will reconnect when its network
        // returns. We don't actively retry outbound; the PRD reconnect target is
        // driven by the sender side.
        reconnect.scheduleNext { [weak self] in
            guard let self else { return }
            try? self.stateMachine.apply(.reconnectFailed)
        }
    }

    private func setState(_ newState: ReceiverState) {
        DispatchQueue.main.async { [weak self] in
            self?.state = newState
        }
    }

    // MARK: - SDP

    private static func sdpDescription() -> Data {
        let sdp = """
        v=0\r\n
        o=- 0 0 IN IP4 127.0.0.1\r\n
        s=MirrorCast\r\n
        t=0 0\r\n
        m=video 0 RTP/AVP/TCP 96\r\n
        a=control:streamid=0\r\n
        a=rtpmap:96 H264/90000\r\n
        a=fmtp:96 packetization-mode=1;profile-level-id=42E01F\r\n
        a=framerate:30\r\n
        """
        return Data(sdp.utf8)
    }
}

enum ReceiverState: Equatable {
    case idle
    case advertising
    case handshaking
    case streaming
}

enum RTSPTransportKind {
    case interleaved
    case udp
}
