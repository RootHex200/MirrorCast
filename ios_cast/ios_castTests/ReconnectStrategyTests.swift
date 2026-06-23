import Testing
@testable import ios_cast

@Suite struct ReconnectStrategyTests {

    @Test func exponentialGrowth() {
        var s = ReconnectStrategy(base: 0.2, maxDelay: 5.0)
        #expect(s.nextDelay() == 0.2)
        #expect(s.nextDelay() == 0.4)
        #expect(s.nextDelay() == 0.8)
        #expect(s.nextDelay() == 1.6)
        #expect(s.nextDelay() == 3.2)
        #expect(s.nextDelay() == 5.0)
        #expect(s.nextDelay() == 5.0)
    }

    @Test func reset() {
        var s = ReconnectStrategy()
        _ = s.nextDelay()
        _ = s.nextDelay()
        s.reset()
        #expect(s.attempt == 0)
        #expect(s.nextDelay() == 0.2)
    }
}
