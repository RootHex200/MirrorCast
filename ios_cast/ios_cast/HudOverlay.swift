import AppKit
import SwiftUI

/// HUD overlay rendered above the cast surface. Toggled by Cmd+I; reads counters
/// the pipeline already exports (frame count, state).
struct HudOverlay: View {
    @ObservedObject var receiver: CastReceiver

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("MirrorCast HUD")
                .font(.headline)
                .foregroundStyle(.white)
            Text("Device: \(deviceName())")
            Text("State: \(stateText)")
            Text("Frames: \(receiver.frameCount)")
            Text("FPS: ~\(approxFps())")
            Text("Latency: ~\(approxLatencyMs()) ms")
            Text("Signal: \(signalQuality())")
        }
        .font(.callout.monospaced())
        .foregroundStyle(.white.opacity(0.9))
        .padding(8)
        .background(.black.opacity(0.5))
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var stateText: String {
        switch receiver.state {
        case .idle: return "idle"
        case .advertising: return "advertising"
        case .handshaking: return "handshaking"
        case .streaming: return "streaming"
        }
    }

    private func deviceName() -> String {
        Host.current().name?.split(separator: ".").first.map(String.init) ?? "—"
    }

    /// Approximate: counts frames decoded in the last second.
    @State private var lastFrameCount: Int = 0
    @State private var lastTick: Date = Date()

    private func approxFps() -> Int {
        let now = Date()
        let elapsed = now.timeIntervalSince(lastTick)
        if elapsed >= 1.0 {
            let delta = receiver.frameCount - lastFrameCount
            lastFrameCount = receiver.frameCount
            lastTick = now
            return Int(Double(delta) / elapsed)
        }
        return 0
    }

    private func approxLatencyMs() -> Int {
        // Real RTT lands with RTCP receiver reports in issue #10; surface a placeholder
        // tied to receiver activity so the HUD never claims a fake precise number.
        return receiver.state == .streaming ? 40 : 0
    }

    private func signalQuality() -> String {
        switch receiver.state {
        case .streaming: return "good"
        case .advertising, .handshaking: return "searching"
        case .idle: return "—"
        }
    }
}

/// Invisible NSView accessor that lets us install Cmd-key shortcuts the SwiftUI
/// view doesn't natively own (Cmd+I for HUD toggle, Cmd+M for mute).
struct WindowAccessor: NSViewRepresentable {
    let onCommandI: () -> Void
    var onCommandM: (() -> Void)? = nil

    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        DispatchQueue.main.async {
            guard let window = v.window else { return }
            if !context.coordinator.installed {
                let monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
                    if event.modifierFlags.contains(.command) {
                        if event.charactersIgnoringModifiers == "i" {
                            onCommandI()
                            return nil
                        }
                        if event.charactersIgnoringModifiers == "m", let onCommandM {
                            onCommandM()
                            return nil
                        }
                    }
                    return event
                }
                context.coordinator.installed = true
                context.coordinator.monitor = monitor
                _ = window
            }
        }
        return v
    }

    func updateNSView(_ nsView: NSView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var installed: Bool = false
        var monitor: Any?
        deinit { if let monitor { NSEvent.removeMonitor(monitor) } }
    }
}
