import Foundation

/// Exponential-backoff reconnect strategy. Returns the delay to wait before the
/// next attempt. Caps at [maxDelay] (default 5s, matching the PRD's 5s reconnect
/// target). Reset to zero on a successful reconnect.
struct ReconnectStrategy {

    /// Cap: PRD §9.6 says "within 5 seconds when the network returns."
    static let defaultMaxDelay: TimeInterval = 5.0

    private let base: TimeInterval
    private let maxDelay: TimeInterval
    private let multiplier: Double
    private(set) var attempt: Int = 0

    init(base: TimeInterval = 0.2, maxDelay: TimeInterval = ReconnectStrategy.defaultMaxDelay,
         multiplier: Double = 2.0) {
        self.base = base
        self.maxDelay = maxDelay
        self.multiplier = multiplier
    }

    /// Next delay. Mutates attempt count.
    mutating func nextDelay() -> TimeInterval {
        let raw = base * pow(multiplier, Double(attempt))
        attempt += 1
        return min(raw, maxDelay)
    }

    /// Reset on success.
    mutating func reset() {
        attempt = 0
    }

    /// Schedule [work] after the next delay, on the main queue.
    mutating func scheduleNext(_ work: @escaping () -> Void) {
        let d = nextDelay()
        DispatchQueue.main.asyncAfter(deadline: .now() + d, execute: work)
    }
}
