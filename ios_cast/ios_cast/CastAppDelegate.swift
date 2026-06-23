import AppKit
import SwiftUI

/// Owns the singleton cast window across app lifetime, replacing SwiftUI's default
/// `WindowGroup` so we can apply aspect-lock + persistence + fullscreen keyboard
/// commands that the SwiftUI scene API doesn't expose.
final class CastAppDelegate: NSObject, NSApplicationDelegate {

    private var windowController: CastWindowController?
    private var menuBarController: MenuBarController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Adopt the system appearance so window chrome follows Light/Dark automatically.
        NSApp.appearance = nil  // nil -> follow system

        // Menu bar first so the app is usable even with no visible window.
        let menuBar = MenuBarController()
        menuBar.onQuit = { [weak self] in
            self?.windowController?.close()
        }
        menuBar.install()
        menuBarController = menuBar

        let contentView = ContentView()
        let controller = CastWindowController(rootView: contentView)
        controller.showWindow(self)
        windowController = controller
    }

    func applicationWillTerminate(_ notification: Notification) {
        windowController?.persistFrame()
    }

    /// Closing the dock window does NOT quit the app — the menu bar still owns it.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    @objc func toggleFullscreen(_ sender: Any?) {
        windowController?.toggleFullscreen()
    }
}
