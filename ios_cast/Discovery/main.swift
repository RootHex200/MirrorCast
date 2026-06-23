// MirrorCast discovery test client.
//
// Browses for `_mirrorcast._tcp.` services on the local network for a bounded time,
// resolves each one's name + host + port, prints what it found, and exits.
//
// Exit codes:
//   0  — at least one receiver was resolved within the timeout.
//   1  — no receivers found after searching for the full timeout.
//   2  — bad arguments.
//
// Usage:
//   discovery <seconds>
//
// Run alongside a Mac that's running the receiver (GUI app or harness); the client
// should find it by name within 3 seconds on a healthy LAN.

import Foundation

setvbuf(stdout, nil, _IOLBF, 0)
setvbuf(stderr, nil, _IOLBF, 0)

let args = CommandLine.arguments
let seconds: Int
if args.count >= 2, let parsed = Int(args[1]), parsed > 0 {
    seconds = parsed
} else {
    seconds = 5
}

let timeout = TimeInterval(seconds)

final class DiscoveryClient: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private let browser = NetServiceBrowser()
    private var services: [NetService] = []
    private var resolved: [(name: String, host: String, port: Int32)] = []
    private let lock = NSLock()

    var results: [(name: String, host: String, port: Int32)] {
        lock.lock(); defer { lock.unlock() }
        return resolved
    }

    func start() {
        browser.delegate = self
        browser.searchForServices(ofType: "_mirrorcast._tcp.", inDomain: "")
    }

    func stop() {
        browser.stop()
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        service.delegate = self
        service.resolve(withTimeout: 3.0)
        lock.lock()
        services.append(service)
        lock.unlock()
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        let hit: (name: String, host: String, port: Int32) = (
            name: sender.name,
            host: sender.hostName ?? "?",
            port: Int32(sender.port)
        )
        lock.lock()
        resolved.append(hit)
        lock.unlock()
        let elapsed = Date().timeIntervalSince(searchStart)
        print("[discovery] resolved in \(String(format: "%.2f", elapsed))s: \(hit.name) @ \(hit.host):\(hit.port)")
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        print("[discovery] failed to resolve \(sender.name): \(errorDict)")
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        print("[discovery] search failed: \(errorDict)")
    }
}

let client = DiscoveryClient()
let searchStart = Date()
client.start()
print("[discovery] browsing for _mirrorcast._tcp. for \(seconds)s…")

let deadline = Date().addingTimeInterval(timeout)
while Date() < deadline {
    RunLoop.current.run(until: Date().addingTimeInterval(0.25))
}

client.stop()

let results = client.results
if results.isEmpty {
    print("[discovery] NO receivers found after \(seconds)s")
    exit(1)
}

print("[discovery] found \(results.count) receiver(s):")
for r in results {
    print("  - \(r.name) @ \(r.host):\(r.port)")
}
exit(0)
