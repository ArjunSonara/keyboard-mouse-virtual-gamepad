# Virtual Pad (v2) — Pure Keyboard & Mouse Virtual Gamepad

Turn your Android phone into an ultra-low-latency, zero-conflict virtual gamepad for PC games.

Unlike conventional virtual controllers that emulate an Xbox/ViGEmBus controller (which often causes severe camera stutter and UI flickering in games like *Red Dead Redemption*, *GTA*, and other titles that fight between controller and mouse input), **Virtual Pad v2 emulates pure, low-level physical keyboard keys and relative mouse aim**. The game sees only a single, consistent keyboard and mouse device.

---

## ⚡ Features

- **True Keyboard & Mouse Input**: Every button translates into real low-level keyboard key holds via the **Interception driver**. The swipe zone translates directly into fluid relative mouse movement.
- **1-Click Windows Executable (`VirtualPad.exe`)**: No command prompt required! Double-click `VirtualPad.exe` to automatically handle ADB reverse USB routing, display Wi-Fi pairing QR code, and manage connections with zero console flickering.
- **Customizable D-Pad (4-Way Directional Rebinding)**: Rebind each D-Pad direction (`Up`, `Down`, `Left`, `Right`) individually to any keyboard key or mouse click. Directional labels update dynamically on-screen.
- **Key Shape Customization**: Toggle any button's shape between **Circle**, **Square**, and **Pill / Rounded Rect**.
- **Game Profiles & Presets**: Save and quickly switch between distinct layouts (e.g. *Default*, *RDR2*, *FPS Shooter*, *Racing*).
- **HUD Opacity Slider**: Adjust button transparency from 100% solid down to 20% ghost to clearly see the game beneath your controls.
- **Gyroscope Motion Aiming**: Tilt your phone for subpixel precision camera aim with built-in deadzone and drift filtering.
- **Customizable HUD & Sizing**: Drag & reposition any control, scale from 0.5x to 2.2x, add up to 16 extra custom buttons, and bind **Left/Right/Middle Mouse Clicks** directly inside the phone UI.
- **Ultra-Low Latency USB Mode**: Physical USB cable transport using `adb reverse tcp:6001 tcp:6001` with `TCP_NODELAY` and length-prefixed stream framing. Transmission latency is **< 1 millisecond**.
- **Wi-Fi Mode with QR Pairing**: Instant wireless pairing via QR code popup using high-speed UDP with redundancy.

---

## 🎮 Default Key Mapping

| Phone Touch Control | PC Output | Default Game Action |
|---|---|---|
| **Left Joystick** | `W`, `A`, `S`, `D` | Movement (supports diagonal holds) |
| **Right Touch Area** | Relative Mouse Movement | Camera Aim / Look |
| **LB** | `Tab` | Weapon Wheel / Inventory |
| **RB** | `Q` | Take Cover |
| **LT** | `Mouse Right` *(Right Click)* | Aim Weapon |
| **RT** | `Mouse Left` *(Left Click)* | Shoot / Attack |
| **Y** | `E` | Mount / Interact |
| **X** | `Space` | Jump |
| **B** | `R` | Reload / Melee |
| **A** | `LShift` | Sprint *(Tap or hold to run)* |
| **LSB** | `LCtrl` | Crouch |
| **RSB** | `C` | Look Behind / Eagle Eye |
| **D-Pad Up** | `H` | Whistle *(Customizable to any key)* |
| **D-Pad Down** | `T` | Camera Perspective *(Customizable to any key)* |
| **D-Pad Left** | `X` | Dual Wield / Quick Action *(Customizable to any key)* |
| **D-Pad Right** | `X` | Action / Call *(Customizable to any key)* |
| **Small Icon (Left)** | `Esc` | Pause Menu |
| **Hamburger Icon (Right)** | `B` | Satchel / Journal |

*(All keys and D-pad directions can be rebound inside the phone app via the ⚙️ Gear → Customize HUD).*

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

2. **Start the PC Server**:
   - **Option A (Recommended)**: Double-click [**`VirtualPad.exe`**](VirtualPad.exe) in the root directory.
   - **Option B (Python CLI)**:
     ```powershell
     pip install -r files/virtualpad_v2/pc_server/requirements.txt
     python files/virtualpad_v2/pc_server/launcher.py
     ```

---

### 2. Android App Setup

- Install the ready-to-use APK directly on your phone:
  - **APK File**: [`files/virtualpad_v2/VirtualPad.apk`](files/virtualpad_v2/VirtualPad.apk)
  - Or install via ADB:
    ```powershell
    adb install -r files/virtualpad_v2/VirtualPad.apk
    ```

---

### 3. Connect & Play

- **Mode A: USB Cable (Recommended for zero latency)**:
  1. Plug in phone via USB with **USB Debugging** enabled.
  2. Launch `VirtualPad.exe` (it automatically sets up port forwarding).
  3. Open the app on your phone, tap **⚙️ Gear** → **Connection Setup** → tap **Connect (USB)**.
- **Mode B: Wi-Fi (Wireless)**:
  1. Ensure your PC and phone are on the same Wi-Fi network.
  2. Open the app on your phone, tap **⚙️ Gear** → **Connection Setup** → **Scan QR** (scan the QR on your PC screen).

---

## 🛠️ Architecture

```
[ Android Device ]
  ControllerView (Touch Gestures, D-Pad, Shapes & Gyro Aiming)
       │
       ▼
  NetworkClient (Background Worker Thread + Socket)
       │
  ══════════════════════════════════════════════
       │  Transport: USB (TCP) or Wi-Fi (UDP)
       │  Packet: 11-byte Little-Endian binary
  ══════════════════════════════════════════════
       ▼
[ PC Server (VirtualPad.exe / launcher.py) ]
  UDP/TCP Socket Listeners + Auto ADB Reverse
       │
       ├──► Keyboard Key Holds (Interception Filter Driver)
       └──► Relative Aim Movement (user32.mouse_event / Interception Mouse)
```
