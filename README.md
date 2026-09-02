# Android Video Wall

**Zero-latency multi-device Android Video Wall**

One Master device streams its screen (or media) via WebRTC. Multiple Client devices join as grid tiles, each automatically cropping the correct viewport, applying matrix transforms, and rendering with frame-accurate sync + dynamic spatial audio.

## Features

- **Master / Client roles** in a single APK
- **Ultra-simple Grid Numbering UI** – enter index `1`, `2`, `3`… + columns/rows; crop + transform calculated automatically
- **WebRTC** low-latency video + audio streaming (screen capture via MediaProjection)
- **Local WebSocket signaling server** running on the Master (no external server required)
- **Clock synchronization** over the signaling channel (offset + RTT) for frame-perfect playback
- **Dynamic Spatial Audio** – stereo pan based on physical grid position relative to the action
- **Cropped rendering** using WebRTC `TextureBuffer.cropAndScale` / transform matrices
- **GitHub Actions** CI that builds a signed debug APK on every push

## Architecture

```
Master                          Clients (1..N)
┌─────────────────┐              ┌─────────────────┐
│ MediaProjection   │              │ Enter grid index  │
│ + WebRTC Offer    │ ── WebRTC ──│ Receive track     │
│ WebSocket Server  │   Stream     │ Crop + Transform  │
│ Time Sync Beacon  │              │ Spatial Audio     │
└─────────────────┘              └─────────────────┘
```

## Requirements

- Android 8.0+ (API 26+)
- Same Wi-Fi / LAN (for WebSocket + WebRTC)
- Master needs `FOREGROUND_SERVICE` + MediaProjection permission

## Quick Start

1. Build & install the APK on all devices (or use the CI artifact).
2. On the **Master**:
   - Open app → Choose **Master**
   - Grant screen-capture permission
   - Note the IP address shown (or use mDNS hostname)
3. On each **Client**:
   - Choose **Client**
   - Enter Master IP + port (default `8080`)
   - Enter your grid number (1 = top-left, row-major) and total columns / rows
   - Connect
4. Clients automatically receive the stream, crop their tile, and play with spatial audio.

## Grid Numbering

Index is **1-based, row-major**:

```
1  2  3
4  5  6
7  8  9
```

The client computes:

```kotlin
col = (index - 1) % columns
row = (index - 1) / columns
cropX = col / columns.toFloat()
cropY = row / rows.toFloat()
cropW = 1f / columns
cropH = 1f / rows
```

These normalized values drive `TextureBuffer.cropAndScale` (or an equivalent matrix) so each device shows only its portion of the full frame.

## Spatial Audio

Horizontal position in the grid maps to stereo pan (`-1.0` … `+1.0`).  
The Master can also send per-frame or periodic “action X” hints over the data channel; clients further bias the pan toward the active region.

## Time Sync

Master periodically broadcasts `{type:"time", t: masterMonoNs, wall: …}` over WebSocket.  
Clients measure RTT and maintain a `clockOffsetNs`.  All rendering / audio decisions use the synchronized clock.

## Building Locally

```bash
./gradlew :app:assembleDebug
```

APK appears at `app/build/outputs/apk/debug/app-debug.apk`.

## CI

Every push to `main` (and PRs) triggers `.github/workflows/build.yml` which:

- Sets up JDK 17
- Caches Gradle
- Builds the debug APK
- Uploads the artifact

## Project Structure

```
app/
  src/main/java/com/videowall/
    VideoWallApp.kt
    MainActivity.kt
    ui/                 # Compose screens
    webrtc/             # PeerConnection, SignalingServer/Client, Capturer
    sync/               # Clock offset management
    render/             # GridCalculator, CroppedVideoSink
    audio/              # SpatialAudioController
  src/main/res/
.github/workflows/build.yml
```

## License

MIT
