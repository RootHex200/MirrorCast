import Foundation

/// Formal RTSP receiver-side state machine. The spine the rest of the receiver hangs off.
///
/// States:
///   idle          -> no peer, no session.
///   handshake     -> OPTIONS/DESCRIBE/SETUP/PLAY exchange in flight.
///   streaming     -> PLAY complete; RTP ingest + decode running.
///   reconnecting  -> peer lost; backing off before re-PLAY.
///   teardown      -> TEARDOWN in flight.
///
/// All transitions are funneled through [apply] so the rest of the app can assert
/// invariants and observe.
final class RTSPStateMachine {

    enum State: Equatable {
        case idle
        case handshake
        case streaming
        case reconnecting
        case teardown
    }

    private(set) var state: State = .idle {
        didSet {
            listeners.values.forEach { $0(state) }
        }
    }

    private var listeners: [UUID: (State) -> Void] = [:]
    private let lock = NSLock()

    enum Event {
        case connect
        case play
        case playFailed
        case peerLost
        case teardown
        case reconnectOk
        case reconnectFailed
        case reset
    }

    /// Apply [event] and return the new state. Invalid transitions throw, exposing
    /// bugs at the boundary instead of silently swallowing them.
    @discardableResult
    func apply(_ event: Event) throws -> State {
        lock.lock(); defer { lock.unlock() }
        let next: State
        switch (state, event) {
        // idle
        case (.idle, .connect): next = .handshake
        case (.idle, .reset): next = .idle
        // handshake
        case (.handshake, .play): next = .streaming
        case (.handshake, .playFailed): next = .idle
        case (.handshake, .teardown): next = .teardown
        // streaming
        case (.streaming, .peerLost): next = .reconnecting
        case (.streaming, .teardown): next = .teardown
        // reconnecting
        case (.reconnecting, .reconnectOk): next = .streaming
        case (.reconnecting, .reconnectFailed): next = .idle
        case (.reconnecting, .teardown): next = .teardown
        // teardown
        case (.teardown, .reset): next = .idle
        default:
            throw NSError(domain: "RTSPStateMachine", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "invalid \(event) in \(state)"])
        }
        state = next
        return next
    }

    @discardableResult
    func observe(_ handler: @escaping (State) -> Void) -> UUID {
        let token = UUID()
        lock.lock()
        listeners[token] = handler
        lock.unlock()
        handler(state)
        return token
    }

    func cancel(_ token: UUID) {
        lock.lock()
        listeners.removeValue(forKey: token)
        lock.unlock()
    }
}
