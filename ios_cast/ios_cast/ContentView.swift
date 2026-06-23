import AVFoundation
import Network
import SwiftUI

struct ContentView: View {
    @StateObject private var receiver = CastReceiver()
    @State private var displayLayer = AVSampleBufferDisplayLayer()
    @State private var hudVisible: Bool = false
    @State private var isMuted: Bool = false

    var body: some View {
        ZStack {
            Color.black
            VideoDisplayView(displayLayer: displayLayer)
                .aspectRatio(16.0/9.0, contentMode: .fit)
            overlay
            if hudVisible {
                HudOverlay(receiver: receiver)
                    .allowsHitTesting(false)
            }
        }
        .frame(minWidth: 640, minHeight: 360)
        .onAppear {
            receiver.displayLayer = displayLayer
            receiver.start()
        }
        .onDisappear {
            receiver.stop()
        }
        .onReceive(NotificationCenter.default.publisher(for: NSScreen.colorSpaceDidChangeNotification)) { _ in }
        .background(
            // Hidden key-capture view so Cmd+I toggles HUD even when the video layer
            // owns the surface.
            HStack {}.background(
                WindowAccessor(
                    onCommandI: { hudVisible.toggle() },
                    onCommandM: { isMuted.toggle() }
                )
            )
        )
    }

    @ViewBuilder
    private var overlay: some View {
        VStack {
            if receiver.state != .streaming {
                waitingLabel
            } else {
                statsLabel
            }
            Spacer()
        }
        .padding()
    }

    private var waitingLabel: some View {
        VStack(spacing: 8) {
            Text("MirrorCast")
                .font(.largeTitle)
                .foregroundStyle(.white)
            Text("Listening on TCP \(CastReceiver.port.rawValue) — state: \(stateText)")
                .foregroundStyle(.secondary)
            if let err = receiver.lastError {
                Text(err)
                    .foregroundStyle(.red)
                    .font(.callout)
            }
        }
    }

    private var statsLabel: some View {
        HStack {
            Text("Frames: \(receiver.frameCount)")
            Text("State: \(stateText)")
        }
        .font(.callout)
        .foregroundStyle(.green)
    }

    private var stateText: String {
        switch receiver.state {
        case .idle: return "idle"
        case .advertising: return "advertising"
        case .handshaking: return "handshaking"
        case .streaming: return "streaming"
        }
    }
}

#Preview {
    ContentView()
}
