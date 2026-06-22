# MirrorCast — Android-to-macOS Wireless Screen Mirroring

**Source:** synthesized from `MirrorCast_PRD_v1.0.docx` with platform stack decisions (Kotlin + Jetpack Compose on Android, Swift on macOS) and testing seams confirmed with the user.

## Problem Statement

I want to watch my Android phone's screen on my Mac — a live football match, a streaming app, a game, a tutorial recording — but every existing way of doing it has a catch. Chromecast needs a dongle and targets TVs, not Macs. ApowerMirror wants a subscription and watermarks the free tier. QuickTime only works with a USB cable and only with iPhones. scrcpy is powerful but it's a CLI tool, and I'm not opening a terminal to watch a match.

There is no polished, free, native macOS app that receives an Android screen cast over plain Wi-Fi with one-tap simplicity. So I end up either watching on the small screen, fiddling with cables, or paying for tools that get in the way.

## Solution

Two native apps that find each other on the same Wi-Fi network and mirror the Android screen to a macOS window with zero configuration and no account.

The Mac runs a native Swift receiver (SwiftUI + AppKit hybrid) that advertises itself over mDNS/Bonjour, accepts an RTSP/RTP stream from the Android device, decodes H.264 via VideoToolbox, renders via `AVSampleBufferDisplayLayer`, and plays synced AAC audio. The Android device runs a native Kotlin + Jetpack Compose sender that discovers the Mac, captures the screen via MediaProjection, encodes H.264 via MediaCodec, packetizes RTP over UDP, and streams audio alongside. Pairing is a one-time 4-digit PIN; subsequent connections are one tap. Latency target is under 200 ms end-to-end, 1080p/30fps default.

## User Stories

### Discovery & pairing

1. As a Sports Fan, I want both apps to find each other automatically on my home Wi-Fi, so that I don't have to type IP addresses.
2. As a Sports Fan, I want the Android app to list my Mac by name (e.g. "Rohit's MacBook Pro") within 3 seconds of opening it, so that I can start casting immediately.
3. As a Sports Fan, I want to tap my Mac's name in the Android device list and have mirroring start, so that the whole flow is one tap.
4. As a first-time user, I want a 4-digit PIN to appear on my Mac and be prompted to confirm it on my Android phone, so that nobody else on the shared Wi-Fi can cast to my Mac without consent.
5. As a returning user, I want my previously-paired Mac to appear at the top of the Android device list with a "Last connected" tag, so that reconnecting is instant.
6. As a returning user, I want a paired reconnect to skip the PIN entirely, so that I can resume casting in under 3 seconds.
7. As a Developer on a segmented network (hotel Wi-Fi, enterprise VLAN), I want to fall back to entering the Mac's IP address manually, so that mDNS discovery failure doesn't block me.
8. As a user on a Mac that isn't showing up in the Android list, I want the Android app to show a helpful "no receivers found, check you're on the same Wi-Fi" state, so that I know what to fix.
9. As a user running the Mac app for the first time, I want a macOS local-network privacy prompt to explain why MirrorCast needs local network access, so that I can grant it knowingly.
10. As a Sports Fan whose Mac is in a different room, I want to confirm the device name before approving, so that I'm casting to the right Mac on a network with several.

### Casting — video

11. As a Sports Fan watching a live match, I want the mirrored video to appear on my Mac in under 200 ms, so that the action stays in sync with what's on my phone.
12. As a Sports Fan, I want smooth 30 fps video at 1080p by default, so that the match looks crisp on my Mac display.
13. As a user on a congested Wi-Fi network, I want to drop the stream to 720p or 480p manually, so that playback stays smooth when bandwidth is poor.
14. As a user on flaky Wi-Fi, I want the stream to adapt its bitrate automatically (200 Kbps–8 Mbps), so that I don't have to babysit quality settings.
15. As a user on a network with noticeable packet loss, I want the receiver to absorb jitter via a buffer, so that playback stays smooth up to ~20% UDP loss.
16. As a Content Creator, I want the receiver to decode H.264 on Apple Silicon hardware, so that my Mac's CPU stays under 15 % during a 1080p/30fps cast.
17. As a Developer, I want the stream to skip B-frames, so that decode latency stays low.
18. As a QA Engineer, I want the stream to recover from malformed RTSP/RTP packets without crashing the Mac app, so that a flaky sender doesn't take down my receiver.
19. As a user whose Wi-Fi drops briefly, I want both apps to auto-reconnect within 5 seconds when the network comes back, so that I don't have to restart anything.
20. As a user whose Android phone locks the screen, I want the Mac window to show a clear "Screen locked" placeholder instead of freezing, so that I understand what happened.
21. As a user whose Android phone background-kills the sender app, I want the Mac to detect the lost session and return to an idle state within seconds, so that I'm not staring at a stale frame.

### Casting — audio

22. As a Sports Fan, I want the match audio to play through my Mac's speakers in sync with the video, so that the commentary matches the picture.
23. As a Sports Fan, I want stereo AAC audio at ~128 Kbps, so that the audio quality matches what I'd get from the phone.
24. As a user in a meeting, I want to mute the cast audio on the Mac without stopping the video stream, so that I can keep watching silently.
25. As a user, I want my Mac's volume keys to control the cast audio level, so that the controls work the way I expect.
26. As a user, I want audio and video to stay within 40 ms of each other, so that lip-sync drift is never noticeable.
27. As a Content Creator capturing gameplay, I want to route the cast audio into OBS or QuickTime alongside the video, so that my recording has synced sound.

### macOS receiver UI

28. As a Sports Fan, I want the mirrored feed in a resizable native macOS window that locks aspect ratio, so that the picture isn't stretched.
29. As a Sports Fan, I want to double-click the window or press Cmd+F to enter fullscreen, so that I can fill the screen for the match.
30. As a Sports Fan in fullscreen, I want to exit with Esc or Cmd+F, so that I'm not trapped.
31. As a QA Engineer demoing during a meeting, I want an always-on-top mode, so that the mirror floats above my slides and IDE.
32. As a Developer, I want a menu bar icon with Connect / Disconnect / Settings / Quit, so that the receiver can run without a visible dock window.
33. As a user, I want to quit the Mac app cleanly from the menu bar icon, so that it stops advertising itself on the network.
34. As a Developer, I want a HUD overlay (Cmd+I) showing device name, signal quality, fps and latency, so that I can debug a poor connection.
35. As a user, I want the Mac app to follow system Light/Dark Mode, so that the window chrome matches the rest of my desktop.
36. As a user with multiple displays, I want to move the mirror window between displays and fullscreen onto the one I choose, so that I can cast to my external monitor.
37. As a Sports Fan, I want the window to remember its size and position across launches, so that I don't resize every time.
38. As a user, I want the Mac app to launch in under 2 seconds, so that opening it doesn't delay the match.
39. As a user, I want the Mac app to stay under 200 MB RSS during an active session, so that it doesn't squeeze out my other apps.

### Android sender UI

40. As a Sports Fan, I want a single big "Cast" button as soon as I open the Android app, so that the happy path is impossible to miss.
41. As a first-time user, I want the Android app to prompt me for the MediaProjection screen-capture permission with a clear explanation, so that I understand what I'm granting.
42. As a user, I want the Android app to show a persistent foreground notification while casting, so that Android doesn't kill the session and I can tap to stop.
43. As a user, I want a "Stop casting" action in the Android app and in the notification, so that I can end the session from either place.
44. As a user, I want the Android app to display the Mac's name and current quality while casting, so that I know where I'm sending and at what bitrate.
45. As a user, I want to change resolution (1080p / 720p / 480p) mid-session from the Android app, so that I can react to network conditions.
46. As a user, I want to see a clear error in the Android app if the Mac rejects the PIN or drops the session, so that I can retry instead of guessing.
47. As a Sports Fan, I want the Android APK to stay under 10 MB, so that the download is quick even on mobile data.
48. As a user with the phone in my pocket while casting, I want the screen to turn off without stopping the cast, so that battery isn't wasted (where MediaProjection permits).

### Disconnect & lifecycle

49. As a user, I want to stop the cast from the Mac menu bar, so that the Mac side sends RTSP TEARDOWN and the Android side returns to idle.
50. As a user, I want to stop the cast from the Android app, so that the Mac returns to its idle "waiting for a device" state.
51. As a user whose Wi-Fi went away and came back, I want the Mac to show a "Reconnecting…" overlay during the gap, so that I know the session is recovering rather than dead.
52. As a user who force-quits the Mac app, I want the Android app to detect the lost peer and return to the device list, so that I can pick another receiver.
53. As a user, I want the Mac app to remember paired Android devices across launches, so that reconnection is one tap after a reboot.

### Privacy & security

54. As a user, I want all stream data to stay on my local network, so that my screen contents are never relayed through a cloud server.
55. As a user, I want no analytics or telemetry collected without my explicit opt-in, so that I'm not leaking usage data.
56. As a user on shared Wi-Fi, I want the PIN pairing to prevent random people from casting to my Mac, so that the receiver is safe in a café.
57. As a user, I want the Mac app to request only the permissions it needs (local network), so that the entitlement surface is minimal.
58. As a user, I want to unpair a previously-paired device from the Mac app's settings, so that a lost or sold phone can't reconnect.

### Distribution

59. As a user, I want to download the Mac app from a website (with App Store as a parallel channel), so that I'm not forced into the App Store.
60. As a user, I want to install the Android app, so that I can start casting (distribution channel — Play Store vs. direct APK — to be confirmed).

## Implementation Decisions

### Platform stacks (locked)

- **macOS receiver:** Swift 5.9+, SwiftUI + AppKit hybrid. Video decode via `VideoToolbox` (`VTDecompressionSession`), render via `AVSampleBufferDisplayLayer` for the zero-copy hardware path. Networking via `Network.framework` (`NWListener`, `NWConnection`). mDNS advertisement via `NetService`. RTSP handling via a custom Swift state machine (preferred for latency control) with FFmpeg `libavformat` RTSP parsing as a fallback path.
- **Android sender:** Kotlin, Coroutines + Flow, Jetpack Compose UI. Screen capture via `MediaProjection` (requires user permission). H.264 encode via `MediaCodec` configured for hardware encoding, fed by the `MediaProjection` `VirtualDisplay` surface. RTP packetization over Java NIO UDP sockets. Discovery via Android NSD (`NsdManager`).

### Protocol stack

- Discovery: mDNS/Bonjour, UDP 5353 multicast (224.0.0.251).
- Signalling: RTSP over TCP 7236 (session setup, capability negotiation, TEARDOWN).
- Video transport: RTP over UDP, ports 7236–7238 negotiated per session, H.264 (AVC, no B-frames, Baseline/High profile).
- Audio transport: RTP over UDP, ports 7238–7240 negotiated, AAC-LC 48 kHz stereo at ~128 Kbps.
- RTCP: video RTCP = video_port + 1, audio RTCP = audio_port + 1, used for A/V sync and sender/receiver reports.
- TCP fallback: RTSP interleaved RTP when UDP is blocked (enterprise/hotel networks).

### Receiver session pipeline (macOS)

A single session pipeline runs the receiver end-to-end. The state machine is the spine of the app and is the seam the headless harness drives:

```
idle → advertising → handshake (RTSP DESCRIBE/SETUP/PLAY)
     → streaming(video+audio) → [paused] → teardown → idle
                ↘ reconnecting ↗
```

- mDNS advertise on launch.
- RTSP state machine handles `DESCRIBE` → `SETUP` → `PLAY` → `TEARDOWN`.
- RTP ingress demuxes video and audio tracks by SSRC/PT.
- Video pipeline: RTP depacketize H.264 NALs → `VTDecompressionSession` → `AVSampleBufferDisplayLayer`.
- Audio pipeline: RTP depacketize AAC → `AVAudioEngine` or `AVSampleBufferAudioRenderer` for output, clocked against RTCP for sync.
- HUD (Cmd+I) reads counters the pipeline already exposes (frame count, fps, RTT, jitter).
- Reconnect logic observes RTCP timeouts and `NWConnection` state; on loss, transitions to `reconnecting` and attempts re-`PLAY` within 5 s.

### Sender session pipeline (Android)

Behind a `ScreenCaptureEngine` interface so the UI and transport are testable in isolation:

- `MediaProjection` permission result starts a `VirtualDisplay` whose surface feeds a hardware `MediaCodec` H.264 encoder.
- Encoder output (`MediaCodec.Callback` dequeuing NALs) feeds a custom RTP packetizer over Java NIO UDP.
- Audio captured via `AudioRecord` (MIC or system audio where the OEM exposes it) → AAC `MediaCodec` → RTP packetizer.
- `NsdManager` registers and browses `_mirrorcast._tcp.` on launch; discovered receivers populate a Compose state flow within 3 s.
- Foreground service of type `mediaProjection` keeps the cast alive in the background and owns the persistent notification.
- Adaptive bitrate: observe RTCP receiver reports and packet loss → step encoder bitrate between 200 Kbps and 8 Mbps; user-selected resolution caps (1080p/720p/480p) override the ceiling.

### Pairing

- First-time: Mac displays a 4-digit PIN; Android prompts the user to confirm; on match, both sides persist a pairing record keyed by device ID (Mac UUID + Android `Settings.Secure.ANDROID_ID`).
- Returning: if a pairing record matches the connecting peer, skip the PIN.
- Unpair: available in Mac settings; deletes the local record and rejects future connections from that device.

### Compat matrix

- macOS 13 Ventura / 14 Sonoma / 15 Sequoia, Apple Silicon and Intel.
- Android 10 (API 29)+.
- 2.4 GHz and 5 GHz Wi-Fi, WPA2/WPA3. No internet required.

### UX specifics

- Window is aspect-locked; resize preserves 16:9 (or the captured aspect).
- Fullscreen via double-click or Cmd+F; Esc or Cmd+F to exit.
- Always-on-top via an `NSPanel`-style floating window.
- Menu bar item: Connect / Disconnect / Settings / Quit.
- HUD overlay (Cmd+I) shows device name, signal quality, fps, latency.
- Window size/position persisted in `UserDefaults`.
- Dark Mode follows `NSApp.effectiveAppearance`.

### Open technical decisions

- Mac App Sandbox vs. direct download (affects local-network entitlements and mDNS behaviour) — leaning direct download for v1.0 to avoid sandbox friction, App Store in parallel if entitlements permit.
- Android distribution: Play Store vs. direct APK sideload — to be decided.
- HEVC (H.265) deferred to v1.1.
- Wi-Fi Direct (P2P) Miracast mode and cloud relay for cross-subnet scenarios deferred — out of scope for v1.0.

## Testing Decisions

### What makes a good test here

Tests assert on **external behaviour** — observable outputs of a real pipeline — not on internal module shape. Internals (the RTP packetizer, the RTSP state machine, the jitter buffer, the decode loop) are free to refactor as long as the observable contract holds. We do not mock the network or the codecs inside these seams; we drive the real code from the seam's edge.

### Seam 1 — macOS receiver (one headless harness)

The receiver session pipeline is driven from a CLI harness that accepts an RTSP URL and streams from a bundled FFmpeg test source (testsrc, sample MP4, golden PCAP replay). The harness exercises the real mDNS advertise, RTSP handshake, RTP ingest, VideoToolbox decode, and audio decode paths headlessly — no window required for most assertions.

Assertions: a frame counter the pipeline already exports increments at the expected rate; HUD metrics (fps, latency, jitter) land within expected ranges; RTCP-driven reconnect fires within 5 s of a forced socket drop; malformed RTP injected from a golden PCAP does not crash the process; TEARDOWN returns the receiver to `idle`.

Window/UX concerns (fullscreen, always-on-top, menu bar, Cmd+F, Cmd+I, Dark Mode, multi-display, window persistence) are a manual-verify checklist run against each release candidate, not automation.

### Seam 2 — Android sender (one Compose + fake-engine seam)

`ScreenCaptureEngine` and `ReceiverDiscovery` are interfaces behind the Compose UI. Unit tests (Robolectric) and instrumented tests (Espresso on a device/emulator) drive the UI against a fake engine that emits synthetic H.264 NALs and a fake `NsdManager` that publishes discovered/lost events on a controlled clock.

Assertions: device list populates within 3 s when the fake browser emits; "Last connected" tag is rendered for the paired device; tapping a receiver transitions the cast state machine `idle → connecting → streaming → paused → teardown` correctly; PIN mismatch surfaces the right error; foreground notification appears on streaming entry; resolution change fires the expected encoder reconfiguration call on the fake engine; adaptive bitrate reacts to injected RTCP loss reports.

Real `MediaProjection` + `MediaCodec` are exercised by a single on-device instrumented smoke test that actually casts to a local FFmpeg receiver, plus a manual-verify checklist per device class (Pixel, Samsung, Xiaomi, OnePlus) for OEM `MediaCodec` quirks.

### Seam 3 — cross-platform RTSP/RTP conformance suite

A shared folder of golden PCAPs (DESCRIBE/SETUP/PLAY exchanges, RTP H.264 streams with known frame boundaries, RTCP sender/receiver reports, TEARDOWN) and the expected decoded outputs. Both the Swift receiver and the Kotlin sender's packetizer are exercised against these golden inputs. This is the seam that guarantees an Android build from this repo can be received by a Mac build from this repo without integration surprises.

Golden PCAPs are checked in; new ones are added when a new behaviour (TCP fallback, HEVC later) lands.

### Prior art

As a green-field repo there is no existing test prior art. The three seams above are the starting baseline — future tests should prefer extending these seams over introducing new ones.

## Out of Scope

- iOS sender support (separate product).
- Windows or Linux receiver (Mac only for v1.0).
- Remote control / touch injection from Mac back to Android.
- Cloud relay or NAT traversal for cross-network or cross-subnet casting.
- Wi-Fi Direct (P2P) Miracast mode.
- DRM-protected content playback (Netflix, Disney+, etc.) — OS-level restriction on `MediaProjection`.
- On-Mac screen recording / clip capture of the mirrored stream — planned for v1.1.
- HEVC (H.265) encode/decode — v1.1.
- HE-AAC or Opus audio codecs — AAC-LC only for v1.0.
- Multi-device casting (one Android → many Macs, or many Androids → one Mac).
- In-app analytics dashboards or telemetry (no analytics without opt-in).
- Localization beyond English for the first cut.

## Further Notes

- **Success metrics** (from the source PRD, carried forward as release criteria): connection success rate ≥ 95%; latency P50 < 150 ms; latency P95 < 250 fps; sustained ≥ 30 fps at 1080p; discovery under 3 s; first-time pairing under 30 s; 7-day retention > 40%; fatal crash rate < 0.1%.
- **Primary personas** carried forward: (A) Sports Fan watching live matches / streaming apps on a bigger screen; (B) Mobile Developer / QA Engineer using it for demos and bug capture; (C) Content Creator using it as an OBS / QuickTime capture source without watermarks.
- **Phase plan** (from source PRD §9): Phase 1 Foundation (RTSP receiver, manual IP, H.264 decode, basic window) → Phase 2 Discovery (mDNS, Android device list, one-tap) → Phase 3 Audio & Polish (AAC, A/V sync, fullscreen, Dark Mode) → Phase 4 Reliability (PIN, auto-reconnect, adaptive bitrate, error recovery) → Phase 5 Beta (50 users, crash + latency profiling) → v1.0 Launch.
- **Known risks** (from source PRD §10): UDP blocked by network policy → TCP fallback; Android OEM MediaCodec fragmentation → per-device quirks; DRM black screen → documented limitation; macOS sandbox restrictions on mDNS → entitlement tuning; H.264 decode latency → B-frame-free profile + tuned jitter buffer; App Store rejection for screen-capture semantics → direct download first, App Store parallel.
