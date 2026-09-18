# VirtualPad (v2) — Pure Keyboard & Mouse Virtual Gamepad + Desktop Mirror

VirtualPad v2 is an ultra-low latency input virtualization and real-time screen/audio mirroring platform designed for PC gaming on Android devices.

## 🚀 Key Differences in v2
- **Zero-Conflict Pure KBM**: Completely removed Xbox 360 / ViGEmBus controller emulation. Every button maps to a genuine physical keyboard key hold or mouse click via the Windows kernel **Interception driver**. Eliminates camera stutter, UI flickering, and controller lockups in games like *Red Dead Redemption 2*, *GTA V*, and emulators.
- **Native 120 FPS Desktop Mirror**: Integrates native C++ DirectX Desktop Duplication (DXGI 1.2) + Media Foundation Hardware H.264 Encoder (MFT). Supports up to 200 Mbps bitrates with sub-frame 10–16 ms host latency.
- **WASAPI Audio Loopback**: Dedicated Port 8082 stream capturing Windows system audio and playing it via low-latency Android AudioTrack with zero audio/video desync.
- **Dual On-Screen HUD Controls**:
  - **⚙️ STREAM (Top-Left)**: Stream settings (Resolution, 30–120 FPS, 10–200 Mbps Bitrate), plus **Viewport Adjust Mode** (pinch-to-zoom & pan with 🔒 Viewport Lock).
  - **⚙️ PAD (Top-Right)**: 36 game presets, customizable button shapes/sizes, macro studio, turbo rapid fire, haptics, and live touch calibration.
- **Self-Healing USB Input Daemon**: Port 6001 background auto-reconnect worker that instantly recovers from cable unplugs, server restarts, or app backgrounding.
- **1-Click All-in-One Launcher (`VirtualPad.exe`)**: A single standalone executable that runs the input server, embeds the mirror engine, and handles all ADB reverse forwarding automatically.

## 🔌 Port Mapping

| Port | Protocol | Purpose | Framing |
|---|---|---|---|
| **6001** | TCP / UDP | Virtual Gamepad & Mouse Aim | 2-byte length prefix + 11-byte Little-Endian binary |
| **8080** | TCP | Hardware H.264 Video Stream | Raw NALUs (SPS/PPS/IDR/P-frames) |
| **8081** | TCP | Stream Control Backchannel | JSON commands (bitrate, fps, keyframe request) |
| **8082** | TCP | WASAPI Loopback Audio | 48kHz Stereo 16-bit PCM |

## 🎮 Default Controls

| Phone Button | PC Key Output | Default In-Game Action |
|---|---|---|
| Left Stick | `W`, `A`, `S`, `D` | Movement (8-way, diagonal holds) |
| Right Screen Half | Mouse dx / dy | Camera Aim / Look |
| LB | `Tab` | Weapon Wheel / Inventory |
| RB | `Q` | Take Cover |
| LT | `Mouse Right` *(RMB)* | Aim Down Sights |
| RT | `Mouse Left` *(LMB)* | Attack / Shoot |
| Y | `E` | Interact / Mount |
| X | `Space` | Jump |
| B | `R` | Reload / Melee |
| A | `Left Shift` | Sprint *(Tap or hold)* |
| LSB | `Left Ctrl` | Crouch |
| RSB | `C` | Look Behind / Eagle Eye |
| D-Pad | `H` / `T` / `X` | Whistle / Camera / Actions |
| Small Icon | `Esc` | Pause Menu |
| Hamburger Icon | `B` | Map / Satchel |

## 📦 Directory Structure
- `android_app/`: Android Studio project (Kotlin, MediaCodec decoder, AudioTrack PCM, custom canvas `ControllerView`).
- `pc_server/`: Desktop server source (`launcher.py`, `server.py`, `PCMirror.exe`).
- `VirtualPad.apk`: Pre-built ready-to-install Android APK.
- `VirtualPad.exe`: Pre-built all-in-one Windows standalone launcher.
