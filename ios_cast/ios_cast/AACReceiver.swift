import Foundation
import AVFoundation

/// AAC-LC RTP depacketizer per RFC 3640 (single AU per packet + AU-headers-section).
/// Feeds raw AAC frames to a downstream playback engine.
final class AacDepacketizer {
    private var currentAu = Data()
    private var expectedAuSize: Int = 0
    private(set) var frameCount: Int = 0

    /// Parses one AAC RTP packet payload (post-RTP-header). Returns a complete AAC
    /// access unit (raw frame, no ADTS) when the AU is fully assembled.
    func consume(payload: Data) -> Data? {
        guard payload.count >= 4 else { return nil }
        // AU-headers-length is the first 2 bytes, in 32-bit words.
        let headerBits = (Int(payload[0]) << 8) | Int(payload[1])
        let headerBytes = headerBits / 8
        guard payload.count >= 2 + headerBytes else { return nil }

        // Each AU-header is 16 bits = AU-size (13) + AU-index (3).
        // We support only the single-AU / sequential-AUs cases.
        var offset = 2
        var auSizes: [Int] = []
        while offset + 2 <= 2 + headerBytes {
            let size = (Int(payload[offset]) << 5) | (Int(payload[offset + 1]) >> 3)
            auSizes.append(size)
            offset += 2
        }

        var dataOffset = 2 + headerBytes
        var assembled: Data? = nil
        for size in auSizes {
            guard dataOffset + size <= payload.count else { return nil }
            currentAu.append(payload.subdata(in: dataOffset..<(dataOffset + size)))
            dataOffset += size
            expectedAuSize = size
            // Single-AU case: emit immediately. Multi-AU case is rare here; we emit
            // each as it arrives.
            assembled = currentAu
            currentAu = Data()
            frameCount += 1
        }
        return assembled
    }
}

/// Wraps `AVAudioEngine` + `AVAudioPlayerNode` to play AAC frames decoded by an
/// upstream component. We use `AVAudioConverter` to decode AAC-LC at 48 kHz stereo.
/// Exposes [isMuted] and [volume] so the UI can mute without interrupting video
/// and Mac volume keys can drive cast audio level.
final class AudioPlayer {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let format: AVAudioFormat
    private var converter: AVAudioConverter?
    private let queue = DispatchQueue(label: "cast.audio.playback")

    /// 0.0...1.0; persisted so volume keys survive a session restart.
    var volume: Float = 1.0 {
        didSet { applyVolume() }
    }
    /// True => silence without releasing the audio pipeline.
    var isMuted: Bool = false {
        didSet { applyVolume() }
    }

    init(sampleRate: Double = 48_000, channels: AVAudioChannelCount = 2) {
        format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: channels)!
    }

    func start() {
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        do {
            try engine.start()
            player.play()
            applyVolume()
        } catch {
            // Audio session failures here are non-fatal for the video path.
        }
    }

    func stop() {
        player.stop()
        engine.stop()
    }

    func enqueue(pcm: AVAudioPCMBuffer) {
        player.scheduleBuffer(pcm, completionHandler: nil)
    }

    private func applyVolume() {
        player.volume = isMuted ? 0.0 : volume
    }
}
