// MirrorCast headless receiver harness.
//
// Usage:
//   Harness [--port N] <seconds>
//
// Listens for an RTSP-over-TCP H.264 stream on the receiver port (default 7236),
// runs the real CastReceiver pipeline without a window for N seconds, then prints
// the decoded frame count and exits.

import Foundation
import Network

setvbuf(stdout, nil, _IOLBF, 0)
setvbuf(stderr, nil, _IOLBF, 0)

var port: Int = 7236
var seconds: Int = 10

var i = 1
let args = CommandLine.arguments
while i < args.count {
    if args[i] == "--port" && i + 1 < args.count {
        port = Int(args[i + 1]) ?? port
        i += 2
    } else if let s = Int(args[i]) {
        seconds = s
        i += 1
    } else {
        i += 1
    }
}

CastReceiver.port = NWEndpoint.Port(rawValue: UInt16(port)) ?? .any

let receiver = CastReceiver()
receiver.start()

print("[harness] listening on TCP \(CastReceiver.port.rawValue) for \(seconds)s…")

let deadline = Date().addingTimeInterval(TimeInterval(seconds))
while Date() < deadline {
    RunLoop.current.run(until: Date().addingTimeInterval(0.25))
}

let frameCount = receiver.frameCount
print("[harness] frames decoded: \(frameCount)")
receiver.stop()
exit(frameCount > 0 ? 0 : 1)
