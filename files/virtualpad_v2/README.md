# Virtual Pad v2 — pure keyboard+mouse, dual transport, QR pairing

## What changed from v1
- **No virtual Xbox controller anymore.** Removed vgamepad/ViGEmBus entirely.
  Every button is a real keyboard key hold; the swipe zone is real relative
  mouse movement. Both go through the Interception driver, so the game sees
  one consistent keyboard+mouse device — this is what fixes the RDR1
  stutter for good, since there's no controller axis left to conflict with
  anything.
- **Binary network protocol** instead of JSON — a fixed 9-byte packet per
  update instead of a text blob, for lower latency.
- **Event-driven sending** — a packet fires the instant you touch, move, or
  release anything, instead of waiting for a fixed tick. A lightweight
  ~30ms heartbeat resends button/stick state only, as a safety net against
  dropped WiFi packets (never replays mouse deltas).
- **Dual transport** — WiFi (UDP) or USB cable (TCP via `adb reverse`),
  switchable in the app.
- **QR pairing** for WiFi — no more typing an IP by hand.

## PC setup
1. Install the Interception driver (this is the ONLY driver needed now —
   no ViGEmBus required):
   https://github.com/oblitum/Interception/releases
   - Extract it, open Command Prompt **as Administrator** in that folder
   - Run: `install-interception.exe /install`
   - **Reboot your PC** — required, the driver won't load until you do
2. `pip install -r pc_server/requirements.txt`
3. Run: `python pc_server/server.py`
   - A QR code pops up in a small window — that's for WiFi pairing
   - The console also prints the IP:port and the USB command to run

## Phone setup
1. Open `android_app/` in Android Studio, let Gradle sync, build/run onto
   your phone (or Build → Build APK, then sideload it).
2. On first launch, tap the **SETUP** button (top-right).
3. Choose a mode:
   - **WiFi**: tap "Scan QR instead" and point the camera at the PC's QR
     window, or type the IP manually. Same WiFi network required.
   - **USB**: plug the phone in via cable with USB debugging enabled, then
     on the PC run:
     ```
     adb reverse tcp:6001 tcp:6001
     ```
     then pick USB in the app. No IP needed — it tunnels over the cable.

## Keymap (edit in `pc_server/server.py` → `BUTTON_KEYMAP`)

| HUD element | Key sent | Note |
|---|---|---|
| Left stick | W / A / S / D | 8-directional, diagonals hold two keys at once |
| LB | Tab | |
| RB | Q | |
| LT | `[` | rebind in-game to Right Mouse Click |
| RT | `;` | rebind in-game to Left Mouse Click |
| Y | E | |
| X | Space | |
| B | R | |
| A | L Shift | manual sprint — tap and hold to sprint |
| LSB | L Ctrl | |
| RSB | C | |
| D-pad Up | H | |
| D-pad Down | T | |
| D-pad Left / Right | X (both) | intentionally shared — not distinguished |
| Small icon (rectangles) | Esc | pause menu |
| Hamburger icon | B | |
| Swipe zone (right side) | Mouse movement | camera/aim look |

All buttons are **hold-based**: the key stays down exactly as long as your
finger is on the button, and releases the instant you lift it — same as a
real keyboard.

In RDR1 (or any game), go to Settings → Controls → Keyboard & Mouse and
rebind each action to the key shown above, matching what that action used
to be on a controller.

## Tuning
- **Mouse sensitivity**: swipe distance is currently sent 1:1 in pixels; if
  it feels too slow/fast, multiply `dx`/`dy` in `ControllerView.kt`'s
  `handlePointerMove` "look" branch before accumulating, or scale it
  server-side in `handle_raw_packet()` in `server.py`.
- **Stick deadzone**: `STICK_DEADZONE` in `server.py` (default 0.3) — how
  far you need to push before WASD triggers.
- **Button/zone positions**: `onSizeChanged()` in `ControllerView.kt`,
  as fractions of screen width/height.

## Notes
- USB mode requires the `adb reverse` command to be re-run each time you
  reconnect the phone (it doesn't persist across unplugs).
- WiFi mode has no encryption/auth — fine for a private home network only.
- Closing the QR popup window on the PC just hides it — the server keeps
  running. Stop the whole thing with Ctrl+C in the terminal.
- I couldn't compile/test the actual APK or run the Python server in this
  sandbox (no Android SDK, no Windows, no network here) — the logic is
  complete and should build with minimal fixes, but budget a little time
  for first-run tweaks once you build it in Android Studio and run the
  server on your PC.
