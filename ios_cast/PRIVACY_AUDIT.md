# Privacy & Entitlement Audit (issue #13)

HITL summary of what the Mac and Android apps request and why. Required reading
before tagging a public release.

## Mac receiver (`ios_cast/`)

| Permission / entitlement | Justification | Required for v1.0? |
|---|---|---|
| Local Network (`NSLocalNetworkUsageDescription`) | mDNS advertise + RTSP listener on the LAN | Yes |
| Bonjour service `_mirrorcast._tcp` | Advertised so the Android app can discover by name | Yes |
| Network server | RTSP listener on TCP 7236 | Yes |
| Network client | Outbound mDNS responses; future reconnect | Yes |
| Microphone (`NSMicrophoneUsageDescription`) | Only if AAC routing uses MIC | No (system audio path doesn't need it) |
| App Sandbox | Open PRD §10 — leaning direct download for v1.0 | HITL call |

### No-telemetry audit

Verified: no analytics SDKs in `Package.resolved` / SPM deps; no `URLSession`
hits to non-LAN hosts; no `MetricKit` / `TelemetryProvider` integration. All
socket traffic is `NWListener` on localhost or LAN. Network failures are logged
to `os_log` only; nothing is uploaded.

To re-verify before release:

```sh
grep -rE "MetricKit|Telemetry|Analytics|crashlytics|amplitude|mixpanel" ios_cast/ app/
# expect: no matches in source
```

## Android sender (`app/`)

| Permission | Justification | Required for v1.0? |
|---|---|---|
| `INTERNET` | RTP/RTSP sockets | Yes |
| `ACCESS_NETWORK_STATE` | Detect Wi-Fi drop for reconnect | Yes |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Persistent cast notification + surviving screen lock | Yes |
| `POST_NOTIFICATIONS` | Cast-in-progress notification on Android 13+ | Yes |
| `CHANGE_WIFI_MULTICAST_STATE` | NSD mDNS browsing | Yes |
| `RECORD_AUDIO` | Only if MIC-based audio capture is enabled at runtime | No (guarded) |

No `<uses-permission>` for analytics, advertising ID, location, contacts, or
storage. `MediaProjection` is requested at runtime via the system permission
dialog (issue #4), not statically.

## Open HITL decision: App Sandbox vs. direct download

Captured in PRD §10. **Direct download is the recommendation for v1.0** because
mDNS advertising inside the sandbox needs `com.apple.developer.networking.multicast`
which adds entitlement friction without a clear user benefit. App Store path
becomes a parallel channel in a later release; the entitlements file is already
structured for that flip.
