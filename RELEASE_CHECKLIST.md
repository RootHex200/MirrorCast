# Packaging, Distribution & Manual-Verify Checklist (issue #14)

HITL checklist. Run through it before tagging any public release.

## Mac receiver

### Build

- [ ] `xcodebuild -project ios_cast.xcodeproj -scheme ios_cast -configuration Release archive -archivePath build/MirrorCast.xcarchive`
- [ ] Notarize: `xcrun notarytool submit MirrorCast.dmg --apple-id ... --team-id ... --wait`
- [ ] Staple: `xcrun stapler staple MirrorCast.dmg`
- [ ] DMG built with `create-dmg` or `hdiutil` — background image, drag-to-Applications.

### Manual-verify (per release)

PRD §9 testing decisions — receiver concerns are manual, not automated.

- [ ] Launch: window opens in < 2 s.
- [ ] mDNS: Android (or `Discovery/main.swift`) resolves Mac within 3 s.
- [ ] FFmpeg smoke: `ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=30 -c:v libx264 -pix_fmt yuv420p -bf 0 -g 30 -f rtsp -rtsp_transport tcp rtsp://127.0.0.1:7236/live` renders frames.
- [ ] Headless smoke: `./build-harness/mirrorcast-harness 127.0.0.1 10` driven by FFmpeg returns exit 0.
- [ ] Fullscreen: Cmd+F toggles, Esc exits, double-click enters.
- [ ] HUD: Cmd+I shows overlay with non-zero fps while streaming.
- [ ] Aspect lock: resize preserves 16:9.
- [ ] Persistence: close + relaunch restores size & position.
- [ ] Dark mode: chrome follows system.
- [ ] Menu bar: Connect/Disconnect/Settings/Quit; Quit removes mDNS service.
- [ ] Mute: Cmd+M silences audio without stopping video.
- [ ] TEARDOWN: kill sender, Mac returns to idle without crashing.

### Distribution channels

- [ ] Direct download from website (recommended for v1.0).
- [ ] App Store (parallel): flip sandbox to true in `MirrorCast.entitlements`, add multicast entitlement.

## Android sender

### Build

- [ ] `./gradlew :app:assembleRelease` (or `bundleRelease` for Play).
- [ ] Sign with release keystore (`signingConfig` set in `build.gradle.kts`).
- [ ] APK size < 10 MB (PRD story 47).
- [ ] ProGuard/R8 mappings uploaded with symbols if minify enabled.

### Manual-verify (per release)

- [ ] Install on a clean device.
- [ ] First launch: MediaProjection permission prompt with explanatory dialog.
- [ ] Cast button: tap, list populates within 3 s on a healthy LAN.
- [ ] PIN pairing: first-time shows PIN on Mac, confirm on Android, persists.
- [ ] Returning: paired receiver shows "Last connected", skips PIN.
- [ ] Resolution change: 1080p → 720p → 480p mid-session reconfigures encoder.
- [ ] Adaptive bitrate: simulate packet loss via `tc` or congested network; fps dips, bitrate drops.
- [ ] Foreground notification: persists through screen lock + background.
- [ ] Stop from notification AND from app: both work.
- [ ] Force-kill Mac: Android returns to device list within seconds.

### OEM quirks (manual per device class)

- [ ] Pixel (stock).
- [ ] Samsung (OneUI MediaCodec variance).
- [ ] Xiaomi (MIUI battery whitelist).
- [ ] OnePlus (OxygenOS notification priority).

### Distribution channels (decision deferred per PRD §10)

- [ ] Play Store (signed AAB).
- [ ] Direct APK sideload from website.

## Pre-release cross-checks

- [ ] No new code paths reference external network hosts outside the LAN.
- [ ] `grep -rE "MetricKit|Telemetry|Analytics|crashlytics|amplitude|mixpanel" ios_cast/ app/` returns nothing.
- [ ] Privacy audit (`ios_cast/PRIVACY_AUDIT.md`) re-reviewed.
- [ ] Golden PCAP conformance suite passes on both platforms.
- [ ] Receiver state machine and reconnect path re-verified.

## Version policy

- Semver: `v<major>.<minor>.<patch>`.
- Pre-release: `vX.Y.Z-rc.N`.
- Tag the commit, push tag, attach binaries to the GitHub release.
