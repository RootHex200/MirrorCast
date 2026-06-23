import AppKit
import AVFoundation
import SwiftUI

/// SwiftUI bridge for `AVSampleBufferDisplayLayer`.
struct VideoDisplayView: NSViewRepresentable {
    let displayLayer: AVSampleBufferDisplayLayer

    func makeNSView(context: Context) -> HostingNSView {
        let view = HostingNSView()
        view.installLayer(displayLayer)
        return view
    }

    func updateNSView(_ nsView: HostingNSView, context: Context) {
        nsView.installLayer(displayLayer)
    }
}

final class HostingNSView: NSView {
    private var installedLayer: AVSampleBufferDisplayLayer?

    func installLayer(_ layer: AVSampleBufferDisplayLayer) {
        if installedLayer === layer { return }
        installedLayer?.removeFromSuperlayer()
        installedLayer = layer
        layer.frame = bounds
        layer.videoGravity = .resizeAspect
        if hostLayer == nil {
            hostLayer = CALayer()
            wantsLayer = true
            self.layer = hostLayer
        }
        hostLayer?.addSublayer(layer)
    }

    private var hostLayer: CALayer?

    override func layout() {
        super.layout()
        installedLayer?.frame = bounds
    }
}
