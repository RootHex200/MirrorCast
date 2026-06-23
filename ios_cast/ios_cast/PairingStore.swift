import Foundation

/// Pairing record persisted on both Mac and Android.
/// Identifies the peer by its device ID (Android: Settings.Secure.ANDROID_ID;
/// Mac: `IOPlatformUUID`). Records are persisted so returning peers skip the PIN.
struct PairingRecord: Codable, Equatable {
    let peerDeviceID: String
    let peerDisplayName: String
    let ourDeviceID: String
    let createdAt: Date
    let lastConnectedAt: Date

    init(peerDeviceID: String, peerDisplayName: String, ourDeviceID: String,
         createdAt: Date = .init(), lastConnectedAt: Date = .init()) {
        self.peerDeviceID = peerDeviceID
        self.peerDisplayName = peerDisplayName
        self.ourDeviceID = ourDeviceID
        self.createdAt = createdAt
        self.lastConnectedAt = lastConnectedAt
    }
}

/// Persists pairing records on disk (Application Support on macOS, app private
/// storage on Android). The store is the source of truth: "is this peer paired?"
/// and "remove this peer" both route through it.
final class PairingStore {

    private let storageURL: URL
    private var records: [String: PairingRecord] = [:]
    private let queue = DispatchQueue(label: "cast.pairing")

    init(storageURL: URL) {
        self.storageURL = storageURL
        load()
    }

    /// Convenience for macOS: stores under `Application Support/MirrorCast/pairing.json`.
    static func defaultForMac() -> PairingStore {
        let fm = FileManager.default
        let dir = (try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask,
                               appropriateFor: nil, create: true)) ?? fm.temporaryDirectory
            .appendingPathComponent("MirrorCast", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return PairingStore(storageURL: dir.appendingPathComponent("pairing.json"))
    }

    func isPaired(peerDeviceID: String) -> Bool {
        queue.sync { records[peerDeviceID] != nil }
    }

    func upsert(_ record: PairingRecord) {
        queue.sync {
            records[record.peerDeviceID] = record
            saveLocked()
        }
    }

    func remove(peerDeviceID: String) {
        queue.sync {
            records.removeValue(forKey: peerDeviceID)
            saveLocked()
        }
    }

    func touchLastConnected(peerDeviceID: String, at date: Date = .init()) {
        queue.sync {
            guard var rec = records[peerDeviceID] else { return }
            rec = PairingRecord(
                peerDeviceID: rec.peerDeviceID,
                peerDisplayName: rec.peerDisplayName,
                ourDeviceID: rec.ourDeviceID,
                createdAt: rec.createdAt,
                lastConnectedAt: date
            )
            records[peerDeviceID] = rec
            saveLocked()
        }
    }

    func all() -> [PairingRecord] {
        queue.sync { Array(records.values) }
    }

    // MARK: - persistence

    private func load() {
        queue.sync {
            guard let data = try? Data(contentsOf: storageURL),
                  let decoded = try? JSONDecoder().decode([String: PairingRecord].self, from: data) else {
                return
            }
            records = decoded
        }
    }

    private func saveLocked() {
        guard let data = try? JSONEncoder().encode(records) else { return }
        try? data.write(to: storageURL, options: [.atomic])
    }
}

/// Generates 4-digit PINs for first-time pairing and validates them. PIN policy
/// (retry limit, expiry) lives here — see HITL review notes in the issue.
final class PairingChallenge {
    static let retryLimit = 3
    static let expirySeconds: TimeInterval = 60

    struct Pending: Equatable {
        let pin: String
        let issuedAt: Date
        var attempts: Int
    }

    private var pending: [String: Pending] = [:]  // peerDeviceID -> pending challenge
    private let queue = DispatchQueue(label: "cast.pairing.challenge")

    /// Issue a fresh 4-digit PIN for [peerDeviceID].
    func issue(peerDeviceID: String) -> String {
        let pin = String(format: "%04d", Int.random(in: 0...9999))
        queue.sync {
            pending[peerDeviceID] = Pending(pin: pin, issuedAt: Date(), attempts: 0)
        }
        return pin
    }

    /// Returns true if the supplied PIN matches the active challenge and the
    /// challenge hasn't expired or been retried past [retryLimit].
    /// Errors:
    ///   - .noChallenge: no PIN was ever issued for this peer.
    ///   - .expired: the PIN's TTL elapsed.
    ///   - .retryLimit: too many wrong attempts; the peer must re-request a PIN.
    ///   - .wrongPin: pin didn't match; attempts incremented.
    enum VerifyError: Error, Equatable {
        case noChallenge, expired, retryLimit, wrongPin
    }

    func verify(peerDeviceID: String, pin: String) -> Result<String, VerifyError> {
        var result: Result<String, VerifyError> = .failure(.noChallenge)
        queue.sync {
            guard var p = pending[peerDeviceID] else {
                result = .failure(.noChallenge)
                return
            }
            if Date().timeIntervalSince(p.issuedAt) > PairingChallenge.expirySeconds {
                pending.removeValue(forKey: peerDeviceID)
                result = .failure(.expired)
                return
            }
            if p.attempts >= PairingChallenge.retryLimit {
                pending.removeValue(forKey: peerDeviceID)
                result = .failure(.retryLimit)
                return
            }
            if p.pin == pin {
                pending.removeValue(forKey: peerDeviceID)
                result = .success(pin)
                return
            }
            p.attempts += 1
            pending[peerDeviceID] = p
            result = .failure(.wrongPin)
        }
        return result
    }
}
