import Foundation
import Network

enum RTSPMethod: String {
    case options = "OPTIONS"
    case describe = "DESCRIBE"
    case setup = "SETUP"
    case play = "PLAY"
    case teardown = "TEARDOWN"
    case announce = "ANNOUNCE"
    case record = "RECORD"
    case getParameter = "GET_PARAMETER"
}

struct RTSPRequest {
    let method: RTSPMethod
    let uri: String
    let version: String
    var headers: [String: String]
    var body: Data

    var cseq: String? { headers["CSeq"] }
}

enum RTSPParser {
    /// Parses one RTSP request from `buffer` starting at `startOffset`.
    /// Returns the request and bytes consumed, or nil if more data is needed.
    static func parseRequest(buffer: Data, startOffset: Int) -> (RTSPRequest, Int)? {
        guard let headerEnd = findHeaderEnd(buffer: buffer, startOffset: startOffset) else {
            return nil
        }

        let headerBytes = buffer.subdata(in: startOffset..<headerEnd)
        guard let headerString = String(data: headerBytes, encoding: .utf8) else {
            return nil
        }

        var lines = headerString.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else { return nil }
        lines.removeFirst()

        let requestParts = requestLine.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
        guard requestParts.count == 3,
              let method = RTSPMethod(rawValue: requestParts[0]) else {
            return nil
        }

        var headers: [String: String] = [:]
        for line in lines where !line.isEmpty {
            guard let colon = line.range(of: ":") else { continue }
            let key = String(line[..<colon.lowerBound]).trimmingCharacters(in: .whitespaces)
            let value = String(line[colon.upperBound...]).trimmingCharacters(in: .whitespaces)
            headers[key] = value
        }

        var consumed = (headerEnd + 4) - startOffset
        var body = Data()
        if let lenStr = headers["Content-Length"], let len = Int(lenStr) {
            let bodyStart = headerEnd + 4
            let bodyEnd = bodyStart + len
            if bodyEnd > buffer.count { return nil }
            body = buffer.subdata(in: bodyStart..<bodyEnd)
            consumed = bodyEnd - startOffset
        }

        return (
            RTSPRequest(method: method, uri: requestParts[1], version: requestParts[2], headers: headers, body: body),
            consumed
        )
    }

    /// Index of the first byte of the `\r\n\r\n` terminator, or nil if not yet present.
    private static func findHeaderEnd(buffer: Data, startOffset: Int) -> Int? {
        let count = buffer.count
        var i = startOffset
        while i + 4 <= count {
            if buffer[i] == 0x0D && buffer[i + 1] == 0x0A && buffer[i + 2] == 0x0D && buffer[i + 3] == 0x0A {
                return i
            }
            i += 1
        }
        return nil
    }
}

/// Parsed RTSP-interleaved RTP/RTCP frame: `$\r\n` is a 4-byte header `$<channel><len-hi><len-lo>`.
struct InterleavedFrame {
    let channel: UInt8
    let payload: Data
}

enum InterleavedFrameReader {
    /// Parses one interleaved frame from `buffer` at `offset`.
    static func parse(buffer: Data, offset: Int) -> (InterleavedFrame, Int)? {
        guard offset + 4 <= buffer.count else { return nil }
        guard buffer[offset] == 0x24 else { return nil }  // '$'
        let channel = buffer[offset + 1]
        let length = (Int(buffer[offset + 2]) << 8) | Int(buffer[offset + 3])
        let frameEnd = offset + 4 + length
        guard frameEnd <= buffer.count else { return nil }
        let payload = buffer.subdata(in: (offset + 4)..<frameEnd)
        return (InterleavedFrame(channel: channel, payload: payload), frameEnd - offset)
    }
}
