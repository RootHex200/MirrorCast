import AppKit
import SwiftUI

/// Owns the cast window: aspect-locked resize, Cmd+F fullscreen, frame persistence.
///
/// The window content is SwiftUI (`CastWindowContent`); this controller hosts it
/// inside an `NSPanel`-style window so the SwiftUI lifecycle isn't fighting us
/// for window-level behaviours the SwiftUI `WindowGroup` doesn't expose.
final class CastWindowController: NSWindowController, NSWindowDelegate {

    static let aspectRatio: CGSize = CGSize(width: 16, height: 9)
    private let defaultsKey = "cast.window.frame"

    private var monitor: Any?

    convenience init(rootView: some View) {
        let window = CastPanel(
            contentRect: Self.restoredFrame(),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = "MirrorCast"
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.isMovableByWindowBackground = true
        window.aspectRatio = Self.aspectRatio
        window.minSize = CGSize(width: 480, height: 270)
        window.delegate = CastWindowDelegateBridge.shared
        window.contentView = NSHostingView(rootView: rootView)

        self.init(window: window)
        CastWindowDelegateBridge.shared.controller = self
    }

    override func showWindow(_ sender: Any?) {
        super.showWindow(sender)
        installKeyboardMonitor()
    }

    private func installKeyboardMonitor() {
        guard monitor == nil else { return }
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self else { return event }
            // Cmd+F toggles fullscreen
            if event.modifierFlags.contains(.command), event.charactersIgnoringModifiers == "f" {
                self.toggleFullscreen()
                return nil
            }
            // Esc exits fullscreen
            if event.keyCode == 53, self.window?.styleMask.contains(.fullScreen) == true {
                self.toggleFullscreen()
                return nil
            }
            return event
        }
    }

    func toggleFullscreen() {
        guard let window else { return }
        window.toggleFullScreen(nil)
    }

    /// Persisted frame from `UserDefaults`; falls back to a 1280x720 centered rect.
    static func restoredFrame() -> NSRect {
        if let raw = UserDefaults.standard.string(forKey: "cast.window.frame"),
           let parts = parse(raw) {
            return parts
        }
        let screen = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1280, height: 720)
        let w: CGFloat = 1280
        let h: CGFloat = 720
        return NSRect(x: screen.midX - w/2, y: screen.midY - h/2, width: w, height: h)
    }

    static func parse(_ raw: String) -> NSRect? {
        let parts = raw.split(separator: ",").compactMap { Double($0) }
        guard parts.count == 4 else { return nil }
        return NSRect(x: parts[0], y: parts[1], width: parts[2], height: parts[3])
    }

    func persistFrame() {
        guard let frame = window?.frame else { return }
        let raw = "\(frame.origin.x),\(frame.origin.y),\(frame.size.width),\(frame.size.height)"
        UserDefaults.standard.set(raw, forKey: defaultsKey)
    }

    deinit {
        if let monitor { NSEvent.removeMonitor(monitor) }
    }
}

/// Floating-style panel; `aspectRatio` is enforced here so the user cannot stretch
/// the picture out of proportion.
final class CastPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }

    /// Double-click anywhere in the title bar / content toggles fullscreen.
    override func mouseDown(with event: NSEvent) {
        if event.clickCount == 2 {
            toggleFullScreen(nil)
            return
        }
        super.mouseDown(with: event)
    }
}

/// Single-shot bridge so the `NSWindowDelegate` callbacks route back into the
/// window controller. NSWindowController can't be its own delegate cleanly across
/// SwiftUI lifetime cycles.
final class CastWindowDelegateBridge: NSObject, NSWindowDelegate {
    static let shared = CastWindowDelegateBridge()
    weak var controller: CastWindowController?

    func windowDidResize(_ notification: Notification) {
        // aspectRatio on NSWindow handles live constraint; nothing extra here.
    }

    func windowDidMove(_ notification: Notification) {
        controller?.persistFrame()
    }

    func windowDidEndLiveResize(_ notification: Notification) {
        controller?.persistFrame()
    }

    func windowWillClose(_ notification: Notification) {
        controller?.persistFrame()
    }
}
