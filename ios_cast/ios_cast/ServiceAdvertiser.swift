import Foundation

/// Advertises the receiver over mDNS/Bonjour as `_mirrorcast._tcp.` so the Android
/// (or test) client can discover it by name on the same LAN.
final class ServiceAdvertiser: NSObject {
    static let serviceType = "_mirrorcast._tcp."
    static let port: Int32 = 7236

    private var netService: NetService?

    /// Starts advertising. Re-callable; subsequent calls are no-ops while running.
    func start() {
        guard netService == nil else { return }
        let name = Self.makeServiceName()
        let service = NetService(domain: "", type: Self.serviceType, name: name, port: Self.port)
        service.delegate = self
        service.publish()
        netService = service
        print("[advertiser] publishing as '\(name)' on port \(Self.port)")
    }

    /// Stops advertising and removes the service from the network.
    func stop() {
        netService?.stop()
        netService = nil
    }

    /// Human-readable service name, e.g. "roothex's MacBook Pro — MirrorCast".
    /// Falls back to the hostname if `Host.current()` is unavailable.
    static func makeServiceName() -> String {
        let hostName = Host.current().name ?? ProcessInfo.processInfo.hostName
        let trimmed = hostName.split(separator: ".").first.map(String.init) ?? hostName
        return "\(trimmed) — MirrorCast"
    }
}

extension ServiceAdvertiser: NetServiceDelegate {
    func netServiceDidPublish(_ sender: NetService) {
        print("[advertiser] published: \(sender.name)")
    }

    func netService(_ sender: NetService, didNotPublish errorDict: [String : NSNumber]) {
        print("[advertiser] didNotPublish: \(errorDict)")
    }
}
