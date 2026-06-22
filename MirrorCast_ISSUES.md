# MirrorCast — Tracer-Bullet Issues

**Source:** `MirrorCast_PRD.md`. Each issue below is a thin vertical slice that cuts end-to-end through the Swift receiver, the Kotlin/Compose sender, and the RTSP/RTP protocol layer. Publish in order — later issues reference earlier identifiers.

Issues 1–14 below. HITL issues: 9, 13, 14. All others AFK.

---

## Issue 1 — End-to-end skeleton: FFmpeg sender → Swift receiver renders a single H.264 frame

**Type:** AFK · **Blocked by:** none · **Stories:** 11

### What to build

The thinnest possible pipeline. Mac app listens on a hardcoded TCP port (7236), accepts an RTSP URL from an FFmpeg CLI running on the same machine, decodes the H.264 stream via `VideoToolbox` (`VTDecompressionSession`), and renders at least one frame to a native macOS window via `AVSampleBufferDisplayLayer`. No audio, no mDNS, no Android — just "I can receive an H.264 stream and put pixels on screen."

Drive the receiver from `ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=30 -c:v libx264 -f rtsp rtsp://127.0.0.1:7236/live` as the test sender.

Establishes the Swift receiver codebase, the `Network.framework` listener, and the headless test harness seam (the FFmpeg-driven harness called out in the PRD's Testing Decisions).

### Acceptance criteria

- [ ] Swift macOS app opens a window on launch and listens on TCP 7236.
- [ ] FFmpeg CLI sender streams H.264 testsrc to the receiver; rendered frames are visible in the window.
- [ ] `VTDecompressionSession` is used (hardware-accelerated decode path, not software libavcodec).
- [ ] A frame counter the pipeline exposes increments while streaming; a headless test asserts this without a window.
- [ ] Closing the sender or sending RTSP TEARDOWN stops the stream without crashing the Mac app.

### Blocked by

None — can start immediately.

---

## Issue 2 — mDNS discovery: test client finds Mac by name

**Type:** AFK · **Blocked by:** 1 · **Stories:** 1, 2, 8

### What to build

Mac advertises itself as `_mirrorcast._tcp.` via `NetService` on launch, publishing its hostname and port. A tiny test client (Kotlin scratch app using `NsdManager`, or a second Swift CLI using `NetServiceBrowser`) discovers the Mac by name within 3 seconds of starting discovery. No casting yet — discovery is the entire deliverable.

The test client should also exercise the "no receivers found" path by browsing on an isolated network and confirming the empty state is distinguishable from "still searching."

### Acceptance criteria

- [ ] Mac registers a `_mirrorcast._tcp.` service on launch; service is removed on quit.
- [ ] A separate NsdManager-based client resolves the Mac's name and port within 3 seconds on the same LAN.
- [ ] If no receiver is advertising, the client can distinguish "searching" from "no receivers found" after a bounded timeout.
- [ ] Service name is human-readable (e.g. "Rohit's MacBook Pro — MirrorCast"), not just a UUID.

### Blocked by

- Issue 1 (need a running Mac app to advertise).

---

## Issue 3 — Android Kotlin + Compose app shell with fake capture engine

**Type:** AFK · **Blocked by:** 2 · **Stories:** 40

### What to build

Kotlin + Jetpack Compose Android app that boots a UI, shows a big Cast button, and lists discovered receivers (from issue 2's mDNS seam) via a `ReceiverDiscovery` interface backed by `NsdManager`. Defines the `ScreenCaptureEngine` interface with a `FakeScreenCaptureEngine` implementation that emits synthetic H.264 NALs on a clock. Tapping a receiver enters a "connecting" state; the cast surface is wired but the real screen isn't captured yet — that's issue 4.

This is the seam all Android-side tests drive against: Compose UI + fake engine + fake discovery, no real `MediaProjection`.

### Acceptance criteria

- [ ] Compose UI launches with a prominent Cast button and a receiver list populated by `ReceiverDiscovery`.
- [ ] `ScreenCaptureEngine` and `ReceiverDiscovery` are interfaces with fake implementations suitable for Robolectric/Espresso.
- [ ] Tapping a receiver transitions UI state `idle → connecting → streaming` using the fake engine.
- [ ] Receiver list populates within 3 seconds when the fake discovery emits.
- [ ] "Last connected" tag is rendered for any receiver flagged as paired by the discovery interface (records themselves land in issue 9).

### Blocked by

- Issue 2 (need mDNS seam to populate the list).

---

## Issue 4 — Real screen capture on Android: MediaProjection → MediaCodec H.264 → RTP

**Type:** AFK · **Blocked by:** 3 · **Stories:** 11, 12, 41, 42

### What to build

Replace `FakeScreenCaptureEngine` with a real implementation: `MediaProjection` permission flow starts a `VirtualDisplay` whose surface feeds a hardware `MediaCodec` H.264 encoder. Encoder output (`MediaCodec.Callback` dequeuing NALs) feeds a custom RTP packetizer over Java NIO UDP to the Mac's port. Foreground service of type `mediaProjection` owns the persistent "casting" notification and keeps the session alive in background.

This is the first true end-to-end cast: the actual Android screen appears on the Mac receiver from issue 1. Default 1080p/30fps.

### Acceptance criteria

- [ ] Android app requests MediaProjection permission with a clear explanation dialog.
- [ ] Approved permission starts a `VirtualDisplay` → hardware `MediaCodec` H.264 encoder → RTP packetizer over UDP.
- [ ] Foreground service with `mediaProjection` type shows a persistent notification; service survives screen lock and app backgrounding.
- [ ] Android screen is visible live on the Mac receiver from issue 1 at 1080p/30fps.
- [ ] "Stop casting" from the app or the notification tears down the encoder and releases MediaProjection.

### Blocked by

- Issue 3 (need the UI + ScreenCaptureEngine seam).

---

## Issue 5 — AAC audio path with A/V sync

**Type:** AFK · **Blocked by:** 4 · **Stories:** 22, 23, 26

### What to build

Add a second RTP stream for audio: `AudioRecord` (system audio where the OEM exposes it, else MIC) → AAC-LC `MediaCodec` (48 kHz stereo, ~128 Kbps) → RTP packetizer over UDP on a separate negotiated port. RTCP for both tracks enables clock sync. Mac decodes AAC via `AVAudioEngine` or `AVSampleBufferAudioRenderer` and plays back clocked against RTCP sender reports.

A/V sync target: within 40 ms of each other end-to-end.

### Acceptance criteria

- [ ] AAC-LC audio stream is sent alongside the video stream on its own RTP port with RTCP.
- [ ] Mac decodes AAC and plays audio synced to video within 40 ms.
- [ ] Audio continues smoothly during normal network jitter.
- [ ] Stopping video (issue 6's teardown) also stops audio cleanly.

### Blocked by

- Issue 4 (need the sender pipeline to add audio alongside).

---

## Issue 6 — RTSP state machine + TEARDOWN lifecycle

**Type:** AFK · **Blocked by:** 5 · **Stories:** 49, 50, 52

### What to build

Formalize the RTSP state machine on both sides: `DESCRIBE → SETUP → PLAY → TEARDOWN`, plus the session-id and capability negotiation. Replace the hardcoded session handling from issue 1 with a real state machine driven by RTSP messages. Both apps detect peer loss (RTCP timeout, `NWConnection` state change, socket exception) and transition cleanly. Either side can initiate TEARDOWN.

```
idle → handshake (DESCRIBE/SETUP/PLAY) → streaming → teardown → idle
                                       ↗              ↘
                                 reconnecting ←── (loss)
```

This is the spine the rest of the receiver hangs off of.

### Acceptance criteria

- [ ] RTSP state machine implemented on both Mac and Android; all four verbs handled correctly.
- [ ] Either side initiating TEARDOWN leaves both apps in a clean idle state.
- [ ] Android force-killing the sender is detected on the Mac within seconds and returns the Mac to idle.
- [ ] Mac force-quitting the receiver is detected on Android and returns Android to the device list.
- [ ] State transitions are observable to tests via a counter/flow the pipeline exports.

### Blocked by

- Issue 5 (need both tracks flowing before formalizing the session).

---

## Issue 7 — macOS windowing UX: aspect-locked resize, fullscreen, multi-display, persistence, Dark Mode

**Type:** AFK · **Blocked by:** 1 · **Stories:** 28, 29, 30, 35, 36, 37

### What to build

All window behaviour on the Mac receiver. Aspect-locked resizing preserves the captured aspect (typically 16:9). Fullscreen via double-click or Cmd+F, exit with Esc or Cmd+F. Multi-display support: window movable between displays, fullscreen onto the chosen display. Window size and position persisted in `UserDefaults` across launches. Dark Mode follows `NSApp.effectiveAppearance` automatically.

### Acceptance criteria

- [ ] Resizing the window preserves aspect ratio; user cannot stretch the picture.
- [ ] Double-click or Cmd+F enters fullscreen; Esc or Cmd+F exits.
- [ ] Window can be dragged between displays and fullscreened onto any attached display.
- [ ] Window size and position are restored on next launch from `UserDefaults`.
- [ ] Window chrome matches system Light/Dark Mode without restart.

### Blocked by

- Issue 1 (need a rendering window to apply behaviours to).

---

## Issue 8 — Menu bar icon + HUD overlay

**Type:** AFK · **Blocked by:** 6 · **Stories:** 32, 33, 34

### What to build

Menu bar item with Connect / Disconnect / Settings / Quit actions — the receiver can run without a visible dock window. Cmd+I toggles a HUD overlay on the mirror window showing device name, signal quality, fps, and latency, reading counters the pipeline already exports. Clean Quit tears down any active session and stops mDNS advertising.

### Acceptance criteria

- [ ] Menu bar icon present on launch with Connect / Disconnect / Settings / Quit.
- [ ] Quit from the menu bar tears down active sessions and removes the mDNS service.
- [ ] Cmd+I toggles a HUD overlay with device name, fps, latency, signal quality.
- [ ] HUD values update live from counters the session pipeline already exposes.
- [ ] Receiver can run with the main window closed, driven entirely from the menu bar.

### Blocked by

- Issue 6 (need clean session lifecycle for Connect/Disconnect).

---

## Issue 9 — PIN pairing with persisted device records

**Type:** HITL · **Blocked by:** 6 · **Stories:** 4, 5, 6, 10, 56, 58

### What to build

First-time pairing flow: when an Android device connects that isn't in the Mac's pairing store, the Mac displays a 4-digit PIN; Android prompts the user to confirm; on match both sides persist a pairing record keyed by device ID (Mac UUID + Android `Settings.Secure.ANDROID_ID`). Returning peers skip the PIN. Android surfaces a "Last connected" tag for paired receivers at the top of the device list. Mac settings exposes an unpair action that deletes the record and rejects future connections.

Marked HITL because the pairing UX, PIN policy (retry limits, expiry), and the device-ID choice deserve a design review before locking in.

### Acceptance criteria

- [ ] First-time connection shows a 4-digit PIN on the Mac and prompts confirmation on Android.
- [ ] On PIN match, both sides persist a pairing record; subsequent connections skip the PIN.
- [ ] Android shows a "Last connected" tag for paired receivers at the top of the device list.
- [ ] Mac settings exposes unpair; unpaired devices are rejected on next connect attempt.
- [ ] PIN retry and expiry policy is documented and implemented (policy to be reviewed — HITL).

### Blocked by

- Issue 6 (need the session lifecycle to gate on pairing state).

---

## Issue 10 — Reconnect, adaptive bitrate, TCP fallback

**Type:** AFK · **Blocked by:** 6, 9 · **Stories:** 13, 14, 15, 19, 20, 21, 51

### What to build

Reliability slice. RTCP-timeout-based auto-reconnect: on loss, both apps enter a "Reconnecting…" state and attempt re-`PLAY` within 5 seconds when the network returns. Adaptive bitrate observes RTCP receiver reports and packet loss, steps encoder bitrate between 200 Kbps and 8 Mbps; user-selected resolution caps (1080p/720p/480p from the Android UI) override the ceiling. TCP-interleaved RTP fallback when UDP is blocked (enterprise/hotel). "Screen locked" placeholder on the Mac when Android screen locks.

### Acceptance criteria

- [ ] Brief Wi-Fi drop triggers auto-reconnect within 5 seconds on both sides.
- [ ] Mac shows a "Reconnecting…" overlay during the gap and a "Screen locked" placeholder when Android locks.
- [ ] Adaptive bitrate responds to injected RTCP loss by reducing encoder bitrate within the 200 Kbps–8 Mbps band.
- [ ] User can change resolution (1080p/720p/480p) mid-session from the Android app.
- [ ] On a UDP-blocked network, RTSP interleaved RTP over TCP keeps the session working.

### Blocked by

- Issue 6 (state machine), Issue 9 (pairing must survive reconnect).

---

## Issue 11 — Error recovery: malformed packets, crash safety, audio mute, volume keys

**Type:** AFK · **Blocked by:** 10 · **Stories:** 18, 24, 25, 46

### What to build

Hardened slice. Receiver must not crash on malformed RTSP/RTP — log and recover. Audio mute on Mac independent of video stream. Mac volume keys drive cast audio level. Android surfaces clear errors on PIN rejection, session drop, and peer loss so the user can retry instead of guessing.

### Acceptance criteria

- [ ] Injecting malformed RTP/RTSP from a golden PCAP does not crash the Mac receiver; the stream recovers and an error is logged.
- [ ] Mute toggle on the Mac silences cast audio without interrupting video.
- [ ] Mac volume keys adjust the cast audio output level.
- [ ] Android shows actionable error messages on PIN rejection, session drop, and peer loss.

### Blocked by

- Issue 10 (need the reliability substrate before layering error handling).

---

## Issue 12 — Cross-platform RTSP/RTP conformance suite (golden PCAPs)

**Type:** AFK · **Blocked by:** 5 · **Stories:** 11, 12, 22, 26 (verification)

### What to build

The third testing seam from the PRD. Check in a folder of golden PCAPs covering: full `DESCRIBE/SETUP/PLAY/TEARDOWN` exchange, RTP H.264 streams with known frame boundaries, RTCP sender/receiver reports, and an AAC audio session. Build a runner that exercises both the Swift receiver and the Kotlin sender's RTP packetizer against these golden inputs and asserts on decoded output. This is the seam that guarantees an Android build from this repo can be received by a Mac build from this repo.

Golden PCAPs are checked in; new ones are added when a new behaviour (TCP fallback, HEVC later) lands.

### Acceptance criteria

- [ ] Golden PCAPs checked in for handshake, video, audio, RTCP, teardown.
- [ ] Runner exercises the Swift receiver against golden PCAPs and asserts on frame counts and decode success.
- [ ] Runner exercises the Kotlin RTP packetizer against golden inputs and asserts on packet structure.
- [ ] CI (or the local test runner) fails if either side diverges from golden output.
- [ ] Document how to add a new golden PCAP when a new behaviour lands.

### Blocked by

- Issue 5 (need both tracks' protocols stable before capturing goldens).

---

## Issue 13 — Privacy prompts, entitlements, and no-telemetry audit

**Type:** HITL · **Blocked by:** 9 · **Stories:** 9, 54, 55, 57

### What to build

macOS local-network permission prompt with clear copy explaining why MirrorCast needs LAN access. Entitlement audit: request only what's required (local network; microphone only if/when audio input is added). No-analytics audit: confirm no telemetry is transmitted without explicit opt-in. HITL because the App Sandbox vs. direct-download decision (still open in the PRD) affects local-network entitlements and mDNS behaviour, and needs a human call.

### Acceptance criteria

- [ ] First launch shows the macOS local-network permission prompt with explanatory copy.
- [ ] Entitlements requested are the minimal set required for v1.0 functionality.
- [ ] Audit confirms no analytics or telemetry transmitted without explicit opt-in.
- [ ] App Sandbox vs. direct-download decision is made and documented (HITL — affects entitlements).

### Blocked by

- Issue 9 (pairing flow must be in place before privacy review).

---

## Issue 14 — Packaging, distribution, and manual-verify checklists

**Type:** HITL · **Blocked by:** 11, 13 · **Stories:** 47, 59, 60

### What to build

Ship slice. Mac app notarization + installer artifact. Android APK build under 10 MB. Per-device-class manual-verify checklists (Pixel, Samsung, Xiaomi, OnePlus) covering MediaCodec OEM quirks, MediaProjection behaviour, and foreground-service lifecycle. HITL because both distribution decisions are still open in the PRD: Mac App Store vs. direct download, Android Play Store vs. direct APK sideload.

### Acceptance criteria

- [ ] Mac app is notarized and ships as an installer (direct download; App Store path decided — HITL).
- [ ] Android APK is under 10 MB and installs cleanly on a clean Android 10+ device.
- [ ] Manual-verify checklists exist for Pixel, Samsung, Xiaomi, OnePlus covering capture, encode, audio, reconnect.
- [ ] Distribution decisions (App Store vs. direct download, Play Store vs. APK sideload) are made and documented.

### Blocked by

- Issue 11 (need error-handling hardening before shipping).
- Issue 13 (need entitlement and privacy decisions settled before packaging).
