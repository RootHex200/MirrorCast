#!/bin/bash
# Builds the headless receiver harness by compiling the receiver Swift sources
# together with Harness/main.swift into a single executable.
set -euo pipefail

cd "$(dirname "$0")/.."

SRCDIR="ios_cast"
OUT="build-harness"
mkdir -p "$OUT"

SOURCES=(
  "$SRCDIR/RTSPServer.swift"
  "$SRCDIR/RTPPacket.swift"
  "$SRCDIR/H264Decoder.swift"
  "$SRCDIR/ServiceAdvertiser.swift"
  "$SRCDIR/RTSPStateMachine.swift"
  "$SRCDIR/ReconnectStrategy.swift"
  "$SRCDIR/AACReceiver.swift"
  "$SRCDIR/PairingStore.swift"
  "$SRCDIR/CastReceiver.swift"
  "Harness/main.swift"
)

echo "Compiling harness…"
swiftc \
  -O \
  -D DEBUG_CAST \
  -target arm64-apple-macos26.4 \
  -sdk "$(xcrun --show-sdk-path)" \
  -module-name MirrorCastHarness \
  "${SOURCES[@]}" \
  -o "$OUT/mirrorcast-harness"

echo "Built: $OUT/mirrorcast-harness"
