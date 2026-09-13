# Virtual Pad (v2) — Pure Keyboard & Mouse Virtual Gamepad

Turn your Android phone into an ultra-low-latency, zero-conflict virtual gamepad for PC games.

Unlike conventional virtual controllers that emulate an Xbox/ViGEmBus controller (which often causes severe camera stutter and UI flickering in games like *Red Dead Redemption*, *GTA*, and other titles that fight between controller and mouse input), **Virtual Pad v2 emulates pure, low-level physical keyboard keys and relative mouse aim**. The game sees only a single, consistent keyboard and mouse device.

---

## ⚡ Features

- **True Keyboard & Mouse Input**: Every button translates into real low-level keyboard key holds via the **Interception driver**. The swipe zone translates directly into fluid relative mouse movement.
- **Customizable HUD & Key Binding**: Drag & reposition any button, D-pad, or joystick on your screen. Scale controls (0.5x to 2.2x), add up to 16 extra custom buttons, and assign any keyboard key or **Left/Right/Middle Mouse Clicks** directly inside the phone UI.
- **Ultra-Low Latency USB Mode**: Transmits input over a physical USB cable using `adb reverse tcp:6001 tcp:6001` with `TCP_NODELAY` and length-prefixed stream framing. Transmission latency is **< 1 millisecond**.
- **Wi-Fi Mode with QR Pairing**: Instant wireless pairing via QR code popup (or manual IP entry) using high-speed UDP with redundancy.
- **Subpixel Mouse Look**: Smooth camera control with fractional accumulation and zero dropped micro-movements.
- **Compact 11-Byte Binary Protocol**: Event-driven packet sending with 32-bit bitmask and instant layout synchronization.

---

## 🎮 Default Key Mapping

| Phone Touch Control | PC Output | Default Game Action |
|---|---|---|
| **Left Joystick** | `W`, `A`, `S`, `D` | Movement (supports diagonal holds) |
| **Right Touch Area** | Relative Mouse Movement | Camera Aim / Look |
| **LB** | `Tab` | Weapon Wheel / Inventory |
| **RB** | `Q` | Take Cover |
| **LT** | `[` | Aim Weapon *(Rebind in-game to Right Mouse Click)* |
| **RT** | `;` | Shoot / Attack *(Rebind in-game to Left Mouse Click)* |
| **Y** | `E` | Mount / Interact |
| **X** | `Space` | Jump |
| **B** | `R` | Reload / Melee |
| **A** | `LShift` | Sprint *(Tap or hold to run)* |
| **LSB** | `LCtrl` | Crouch |
| **RSB** | `C` | Look Behind / Eagle Eye |
| **D-Pad Up** | `H` | Whistle |
| **D-Pad Down** | `T` | Camera Perspective / Map |
| **D-Pad Left / Right** | `X` | Dual Wield / Quick Action |
| **Small Icon (Left)** | `Esc` | Pause Menu |
| **Hamburger Icon (Right)** | `B` | Satchel / Journal |

*(You can easily customize any keybinding by editing `BUTTON_KEYMAP` in `server.py`).*

---

## 🚀 Quick Setup Guide

### 1. PC Setup

1. **Install the Interception Driver** *(One-time install)*:
   - Download the latest driver release from [Interception Releases](https://github.com/oblitum/Interception/releases).
   - Extract the zip file.
   - Open Command Prompt **as Administrator** in the `command line installer` folder and run:
     ```cmd
     install-interception.exe /install
     ```
   - **Reboot your PC**.

2. **Install Python Requirements**:
   ```powershell
   pip install -r files/virtualpad_v2/pc_server/requirements.txt
   ```

3. **Start the PC Server**:
   ```powershell
   python "files/virtualpad_v2/pc_server/server.py"
   ```

---

### 2. Android App Setup

1. Open `files/virtualpad_v2/android_app` in Android Studio and run it on your phone, or install the compiled debug APK directly:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "files/virtualpad_v2/android_app/app/build/outputs/apk/debug/app-debug.apk"
   ```
2. Open the app and tap **SETUP** (top-right corner).

---

### 3. Connect & Play

#### Mode A: USB Cable (Recommended for lowest latency)
1. Enable **USB Debugging** on your phone (Settings → Developer Options).
2. Connect your phone via USB cable and allow USB debugging on the prompt.
3. Run this command on your PC:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" reverse tcp:6001 tcp:6001
   ```
4. In the mobile app, tap **SETUP** → select **USB Mode**.

#### Mode B: Wi-Fi (Wireless)
1. Ensure your PC and phone are connected to the same Wi-Fi network.
2. Tap **SETUP** on the mobile app → tap **Scan QR** → scan the QR code displayed on your PC screen.

---

## 🛠️ Architecture

```
[ Android Device ]
  ControllerView (Touch Gestures & Virtual HUD)
       │
       ▼
  NetworkClient (Background Worker Thread + Socket)
       │
  ═══════════════════════════════════════
       │  Transport: USB (TCP) or Wi-Fi (UDP)
       │  Packet: 9-byte Little-Endian binary
  ═══════════════════════════════════════
       ▼
[ PC Server (server.py) ]
  UDP/TCP Socket Listeners
       │
       ├──► Keyboard Key Holds (Interception Filter Driver)
       └──► Relative Aim Movement (user32.mouse_event / Interception Mouse)
```
