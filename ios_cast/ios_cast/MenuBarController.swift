import AppKit

/// Owns the menu bar status item: Connect / Disconnect / Settings / Quit.
/// Clean Quit tears down the active session (CastReceiver.stop) and removes the
/// mDNS service (also in stop), so the receiver can disappear from the network
/// before NSApp terminates.
final class MenuBarController: NSObject {
    private var statusItem: NSStatusItem?

    /// Called by Quit; tears down session, then exits.
    var onQuit: (() -> Void)?

    func install() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.image = NSImage(systemSymbolName: "rectangle.on.rectangle.angled", accessibilityDescription: "MirrorCast")
        item.button?.image?.isTemplate = true

        let menu = NSMenu()
        menu.addItem(withTitle: "Connect", action: #selector(connectTapped), keyEquivalent: "c").target = self
        menu.addItem(withTitle: "Disconnect", action: #selector(disconnectTapped), keyEquivalent: "d").target = self
        menu.addItem(NSMenuItem.separator())
        menu.addItem(withTitle: "Settings…", action: #selector(settingsTapped), keyEquivalent: ",").target = self
        menu.addItem(NSMenuItem.separator())
        menu.addItem(withTitle: "Quit MirrorCast", action: #selector(quitTapped), keyEquivalent: "q").target = self

        item.menu = menu
        statusItem = item
    }

    @objc private func connectTapped() {
        NotificationCenter.default.post(name: .castUserConnect, object: nil)
    }

    @objc private func disconnectTapped() {
        NotificationCenter.default.post(name: .castUserDisconnect, object: nil)
    }

    @objc private func settingsTapped() {
        // Settings UI lands in a later issue; surface the main window for now.
        NSApplication.shared.activate(ignoringOtherApps: true)
    }

    @objc private func quitTapped() {
        onQuit?()
        NSApplication.shared.terminate(nil)
    }
}

extension Notification.Name {
    static let castUserConnect = Notification.Name("cast.userConnect")
    static let castUserDisconnect = Notification.Name("cast.userDisconnect")
}
