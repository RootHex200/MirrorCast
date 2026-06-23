# Headless receiver harness (issue #1)

Drives the real `CastReceiver` pipeline headlessly for FFmpeg-driven end-to-end
verification, as called out in the PRD's "Seam 1 — macOS receiver" section.

## Build

```sh
cd ios_cast
./Harness/build.sh
```

Produces `ios_cast/build-harness/mirrorcast-harness`.

## Run

The harness listens on TCP 7236 and exits with the decoded frame count.

```sh
# Terminal A
./ios_cast/build-harness/mirrorcast-harness 127.0.0.1 10

# Terminal B (install ffmpeg via `brew install ffmpeg` if missing)
ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=30 \
  -c:v libx264 -profile:v baseline -bf 0 \
  -f rtsp -rtsp_transport tcp rtsp://127.0.0.1:7236/live
```

Harness prints `frames decoded: N`. Exit code 0 means at least one frame was
decoded; 1 means zero frames — the pipeline failed to decode the stream.

## GUI app

Open `ios_cast.xcodeproj` in Xcode, build & run the `ios_cast` scheme. The
window opens on launch, listens on TCP 7236, renders the FFmpeg stream, and
shows a frame counter overlay.
