---
date: 2026-06-24
topic: cast-stutter-perf
---

# Cast stutter when casting video content

## Summary

Reduce cast stutter on the Android sender so that casting video content (YouTube, internet video) no longer freezes and catches up in jumps. Keep 1080p / 30 fps / 8 Mbps / 2 s keyframe / VBR unchanged. Batch per-access-unit RTP writes into a single flush, and decouple the send path from the encoder drain callback.

## Problem Frame

When casting YouTube or other video content, the Mac mirror freezes briefly and then jumps forward to catch up — the freeze + catch-up signature of a stall-then-drain pattern. The user confirmed the test path is Android emulator on the same Mac (loopback, via `10.0.2.2`), so Wi-Fi loss is not in the path and the freeze has to be CPU / IO / render bound.

Two specific code paths in the current pipeline produce that signature on loopback. `SocketRtspTransport.writeRaw` calls `out?.write` followed by `out?.flush` on every RTP packet (`app/src/main/java/com/example/android_cast/cast/RtspClient.kt:197-200`); at 1080p / 30 fps with multi-slice output this is roughly 100+ syscalls per second, each one able to stall the worker that is also producing the next frame. `RtpPacketizer.sendAccessUnit` (`app/src/main/java/com/example/android_cast/cast/RtpPacketizer.kt:35-41`) iterates each NAL and emits one RTP packet per NAL via the Sender callback, so the per-packet flush cost is paid once per NAL rather than once per picture. When the send path stalls, the encoder output buffer backs up; when it clears, a burst flushes and the picture jumps.

The user's stated preference is to keep quality (1080p) and accept no degradation, so the fix must attack the IO / scheduling overhead rather than reduce bitrate or resolution.

## Key Decisions

- **Keep 1080p / 30 fps / 8 Mbps / I-frame=2 / VBR.** The user explicitly prefers quality over smoothness-via-downgrade. The fix cannot lower resolution, bitrate, frame rate, or change the codec profile.
- **Approach A only for this workstream.** Batch writes per access unit and decouple send from the encoder drain path. Approaches B (Mac-side jitter buffer / display queue depth) and C (wire the existing `AdaptiveBitrate` to RTCP receiver reports) are deferred to separate workstreams.
- **No protocol change.** The wire format stays RTSP-over-TCP with interleaved RTP. Marker bits, FU-A fragmentation, and the existing `RtpPacketizer` API all stay intact; only the call pattern around `Sender.send` and `transport.writeRaw` changes.

## Requirements

**Android sender pipeline**

- R1. All RTP frames for one access unit are accumulated and written to the socket in a single flush per access unit, rather than one flush per RTP packet.
- R2. The send path runs decoupled from the encoder drain callback, so encoder output is not blocked by socket write latency.
- R3. Encoder configuration (resolution, frame rate, bitrate, keyframe interval, bitrate mode) stays unchanged from the current defaults.
- R4. The bounded queue between encoder and sender must not grow unbounded under sustained backpressure; behavior under backpressure is to drop the oldest pending access unit rather than block encode.

**Behavior preservation**

- R5. The RTP wire format, marker-bit placement, FU-A fragmentation, and RTSP interleaving remain byte-compatible with the existing Mac receiver — no receiver-side change is required for this workstream.

## Success Criteria

- Casting a YouTube video from the Android emulator to the Mac for at least 60 seconds shows no visible freeze-then-jump stalls more than once per minute.
- The HUD fps readout (`ios_cast/ios_cast/HudOverlay.swift:47-57`) stays at or above 25 fps during sustained video playback cast.
- `droppedFrames` on the Mac receiver (`ios_cast/ios_cast/CastReceiver.swift`) grows noticeably slower than before during the same 60-second cast.

## Scope Boundaries

**Deferred for later (separate workstreams)**

- Mac-side jitter buffer and display-layer queue depth (Approach B) — follow-on if Approach A does not fully clear the stutter.
- Wiring `AdaptiveBitrate` to parsed RTCP receiver reports (Approach C) — belongs in a real-Wi-Fi workstream where loss and RTT signals actually fire.
- Mac window sizing / fullscreen / maximize-to-fill — separate workstream; not in scope here.

**Outside this workstream's identity**

- Lowering the default resolution or bitrate ceiling.
- Switching transport (UDP RTP, or TCP fallback negotiation) — the existing transport path stays.

## Dependencies / Assumptions

- Assumption: the freeze + catch-up on loopback is caused primarily by per-packet flush overhead and encoder→send coupling, not by VideoToolbox decode on the Mac. This is unverified by profiling; if Approach A lands and the stutter persists, the next suspect is the display-layer drop at `ios_cast/ios_cast/CastReceiver.swift:358-366` (Approach B territory).
- Assumption: the encoder drain loop running on `Dispatchers.Default` (a coroutine pool) means the per-packet flush stalls a pool worker, not a single dedicated thread. The fix is the same — batch + decouple — but the failure mechanism is "pool worker blocked by syscalls" rather than "single thread shared between encode and send."

## Outstanding Questions

**Resolve Before Planning**

- None. Scope is bounded; planning can proceed.

**Deferred to Planning**

- The exact shape of the bounded queue (channel vs. blocking queue vs. dedicated sender thread) and its capacity. The requirement is bounded + drop-oldest; the data structure is an implementation choice.
- Whether the per-AU batching buffer is a single `ByteArray` built up in the Sender lambda, or a list of frames joined at flush time. Either satisfies R1.

## Sources / Research

- Grounding dossier: `/tmp/compound-engineering/ce-brainstorm/mirrorcast-perf/grounding.md` — verbatim quotes with `file:line` pointers across the cast pipeline, encoder config, RTP packetization, Mac receive path, and window setup.
- Claim verification run during synthesis confirmed R1–R4's preconditions: per-packet flush at `RtspClient.kt:197-200`, per-NAL send loop at `RtpPacketizer.kt:35-41`, static encoder defaults at `MediaProjectionScreenCaptureEngine.kt:36-39` and `:146-154`, and the unwired `AdaptiveBitrate` class. One claim refuted: the encoder drain loop runs on `Dispatchers.Default`, not on the `cast-engine` thread — the fix still applies, the mechanism is "stop blocking a pool worker with per-packet syscalls."
