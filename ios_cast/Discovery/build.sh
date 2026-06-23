#!/bin/bash
# Builds the MirrorCast discovery test client.
set -euo pipefail

cd "$(dirname "$0")/.."

OUT="build-discovery"
mkdir -p "$OUT"

echo "Compiling discovery client…"
swiftc \
  -O \
  -target arm64-apple-macos26.4 \
  -sdk "$(xcrun --show-sdk-path)" \
  -module-name MirrorCastDiscovery \
  "Discovery/main.swift" \
  -o "$OUT/mirrorcast-discovery"

echo "Built: $OUT/mirrorcast-discovery"
