"""
Virtual Pad PC Server (v2) - all keyboard+mouse, dual transport, QR pairing
============================================================================
No virtual Xbox controller here at all - every button becomes a real
keyboard key hold, and the swipe zone becomes real relative mouse movement.
Both go through the Interception driver, so the whole input stream looks
like one physical keyboard+mouse to any game - nothing left to conflict.

TRANSPORT (both run at once, pick whichever in the phone app):
  - WiFi:  raw UDP on PORT, paired via a QR code shown in a popup window
  - USB:   TCP on PORT via `adb reverse tcp:PORT tcp:PORT`, phone app
           connects to 127.0.0.1:PORT - no WiFi involved at all

SETUP (one-time on the PC):
  1. Install the Interception driver (NOT just the pip package):
       https://github.com/oblitum/Interception/releases
       -> extract, open cmd AS ADMINISTRATOR in that folder, run:
          install-interception.exe /install
       -> REBOOT your PC. Required, the driver won't load until you do.
  2. pip install -r requirements.txt
  3. Run: python server.py
     - A QR code window pops up (WiFi pairing) and the console prints the
       same info as text.
     - For USB: plug in the phone, enable USB debugging, then run:
          adb reverse tcp:6001 tcp:6001
       and pick "USB" mode in the phone app - no IP/QR needed for this mode.

KEYMAP: edit BUTTON_KEYMAP below to change what any button sends.
"""

import socket
import struct
import threading
import time
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

try:
    import interception
except ImportError:
    raise SystemExit(
        "Missing dependency. Run: pip install interception-python\n"
        "Also install the Interception DRIVER first (separate from the pip "
        "package) and reboot: https://github.com/oblitum/Interception/releases\n"
        "  -> extract it, run 'install-interception.exe /install' as Admin, reboot."
    )

try:
    import qrcode
    from PIL import ImageTk
    import tkinter as tk
    QR_AVAILABLE = True
except ImportError:
    QR_AVAILABLE = False

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
PORT = 6001
STICK_DEADZONE = 0.3  # fraction of full stick travel (0.0-1.0) to trigger WASD

# Bit order MUST match the Android app's ControllerState.toBytes() exactly.
BUTTON_ORDER = [
    "lb", "rb", "lt", "rt",
    "y", "x", "b", "a",
    "lsb", "rsb",
    "dpad_up", "dpad_down", "dpad_left", "dpad_right",
    "small_icon", "hamburger_icon",
]

# Edit these to change what any button sends. Two buttons may share the
# same key (e.g. dpad_left / dpad_right both send "x") - that's handled
# correctly below via reference counting, so the key won't release early
# if only one of two sources holding it lets go.
BUTTON_KEYMAP = {
    "lb": "tab",
    "rb": "q",
    "lt": "[",
    "rt": ";",
    "y": "e",
    "x": "space",
    "b": "r",
    "a": "shift",
    "lsb": "ctrl",
    "rsb": "c",
    "dpad_up": "h",
    "dpad_down": "t",
    "dpad_left": "x",
    "dpad_right": "x",
    "small_icon": "esc",
    "hamburger_icon": "b",
}

# Packet: <B H b b h h>  (little-endian)
#   B  = version/magic byte (0xAA), lets us ignore garbage packets
#   H  = 16-bit button bitmask, bit order = BUTTON_ORDER above
#   b  = stick X, signed byte, -127..127 representing -1.0..1.0
#   b  = stick Y, signed byte, -127..127 representing -1.0..1.0
#   h  = mouse dx, signed 16-bit
#   h  = mouse dy, signed 16-bit
PACKET_FORMAT = "<BHbbhh"
PACKET_SIZE = struct.calcsize(PACKET_FORMAT)
PACKET_MAGIC = 0xAA

# ---------------------------------------------------------------------------
# Interception init
# ---------------------------------------------------------------------------
try:
    interception.auto_capture_devices(keyboard=True, mouse=True)
except Exception as e:
    raise SystemExit(
        "Could not initialize the Interception driver. Make sure you:\n"
        "  1) Installed the driver itself (not just the pip package) from\n"
        "     https://github.com/oblitum/Interception/releases\n"
        "  2) Ran install-interception.exe /install as Administrator\n"
        "  3) Rebooted your PC after installing it\n"
        f"Underlying error: {e}"
    )


def move_mouse_relative(dx: int, dy: int):
    if dx == 0 and dy == 0:
        return
    interception.move_relative(dx, dy)


# ---------------------------------------------------------------------------
# Key hold management, with reference counting for shared keys
# (e.g. dpad_left and dpad_right both drive the "x" key)
# ---------------------------------------------------------------------------
_held_ctx = {}          # key_name -> active hold_key() context manager, or absent
_key_sources = {}       # key_name -> set of source names currently requesting it


def _actually_set_key(key: str, should_hold: bool):
    is_held = key in _held_ctx
    if should_hold and not is_held:
        ctx = interception.hold_key(key)
        ctx.__enter__()
        _held_ctx[key] = ctx
    elif not should_hold and is_held:
        _held_ctx[key].__exit__(None, None, None)
        del _held_ctx[key]


def request_key(key: str, source: str, pressed: bool):
    sources = _key_sources.setdefault(key, set())
    if pressed:
        sources.add(source)
    else:
        sources.discard(source)
    _actually_set_key(key, len(sources) > 0)


def release_everything():
    for key in list(_held_ctx.keys()):
        _actually_set_key(key, False)
    _key_sources.clear()


# ---------------------------------------------------------------------------
# State application
# ---------------------------------------------------------------------------
_last_buttons = 0
_last_stick_x = 0.0
_last_stick_y = 0.0
_state_lock = threading.Lock()


def apply_packet(buttons: int, stick_x: float, stick_y: float, mouse_dx: int, mouse_dy: int):
    global _last_buttons, _last_stick_x, _last_stick_y

    with _state_lock:
        changed_bits = buttons ^ _last_buttons
        if changed_bits:
            for i, name in enumerate(BUTTON_ORDER):
                bit = 1 << i
                if changed_bits & bit:
                    pressed = bool(buttons & bit)
                    key = BUTTON_KEYMAP.get(name)
                    if key:
                        request_key(key, source=name, pressed=pressed)
            _last_buttons = buttons

        if stick_x != _last_stick_x or stick_y != _last_stick_y:
            request_key("w", "stick", stick_y < -STICK_DEADZONE)
            request_key("s", "stick", stick_y > STICK_DEADZONE)
            request_key("a", "stick", stick_x < -STICK_DEADZONE)
            request_key("d", "stick", stick_x > STICK_DEADZONE)
            _last_stick_x = stick_x
            _last_stick_y = stick_y

    if mouse_dx or mouse_dy:
        move_mouse_relative(mouse_dx, mouse_dy)


def handle_raw_packet(data: bytes):
    if len(data) != PACKET_SIZE:
        return
    magic, buttons, sx, sy, dx, dy = struct.unpack(PACKET_FORMAT, data)
    if magic != PACKET_MAGIC:
        return
    stick_x = sx / 127.0
    stick_y = sy / 127.0
    apply_packet(buttons, stick_x, stick_y, dx, dy)


# ---------------------------------------------------------------------------
# UDP (WiFi) listener
# ---------------------------------------------------------------------------
def udp_listener():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", PORT))
    print(f"[WiFi/UDP] listening on port {PORT}")
    last_log = 0
    while True:
        try:
            data, addr = sock.recvfrom(64)
            handle_raw_packet(data)
            if time.time() - last_log > 3:
                print(f"[WiFi/UDP] receiving from {addr[0]}")
                last_log = time.time()
        except Exception:
            continue


# ---------------------------------------------------------------------------
# TCP (USB, via adb reverse) listener
# ---------------------------------------------------------------------------
def tcp_listener():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("127.0.0.1", PORT))
    sock.listen(1)
    print(f"[USB/TCP] listening on 127.0.0.1:{PORT} (use: adb reverse tcp:{PORT} tcp:{PORT})")
    while True:
        try:
            conn, _ = sock.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            print("[USB/TCP] phone connected over USB")
            buf = b""
            while True:
                chunk = conn.recv(256)
                if not chunk:
                    break
                buf += chunk
                while len(buf) >= PACKET_SIZE:
                    handle_raw_packet(buf[:PACKET_SIZE])
                    buf = buf[PACKET_SIZE:]
        except Exception:
            continue


# ---------------------------------------------------------------------------
# Local IP detection + QR pairing popup
# ---------------------------------------------------------------------------
def get_local_ip():
    """
    Finds the best local IPv4 address (prioritizing physical Wi-Fi or Ethernet
    over VPN / WARP / virtual adapters).
    """
    adapters = {}
    try:
        import subprocess
        import re
        out = subprocess.check_output("ipconfig", text=True, errors="ignore")
        current_name = ""
        for line in out.splitlines():
            line = line.rstrip()
            if line and not line.startswith(" ") and not line.startswith("\t"):
                current_name = line.rstrip(":")
            elif "IPv4" in line:
                m = re.search(r":\s*([0-9.]+)", line)
                if m and current_name:
                    ip = m.group(1).strip()
                    if not ip.startswith("169.254."):
                        adapters[current_name] = ip
    except Exception:
        pass

    best_ip = None
    # 1. Look for Wi-Fi specifically
    for name, ip in adapters.items():
        if re.search(r"wi-?fi", name, re.IGNORECASE) and not any(x in name.lower() for x in ["direct", "virtual"]):
            best_ip = ip
            break
    # 2. Look for regular Ethernet
    if not best_ip:
        for name, ip in adapters.items():
            if "ethernet" in name.lower() and not any(x in name.lower() for x in ["virtual", "vethernet", "vpn", "warp"]):
                best_ip = ip
                break
    # 3. Look for any Wireless adapter (e.g. hotspot)
    if not best_ip:
        for name, ip in adapters.items():
            if "wireless" in name.lower() and not any(x in name.lower() for x in ["warp", "virtual"]):
                best_ip = ip
                break
    # 4. Fallback to socket probe
    if not best_ip:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            best_ip = s.getsockname()[0]
            s.close()
        except Exception:
            best_ip = "127.0.0.1"

    return best_ip, adapters


def show_qr_popup(ip: str, port: int):
    if not QR_AVAILABLE:
        print("(qrcode/Pillow not installed - skipping QR popup, use manual IP entry)")
        return

    payload = f"{ip}:{port}"
    img = qrcode.make(payload, box_size=8, border=2)

    root = tk.Tk()
    root.title("Virtual Pad - scan to connect")
    root.attributes("-topmost", True)

    tkimg = ImageTk.PhotoImage(img)
    label_img = tk.Label(root, image=tkimg)
    label_img.pack(padx=16, pady=(16, 4))

    label_text = tk.Label(root, text=f"WiFi pairing: {payload}", font=("Consolas", 12))
    label_text.pack(pady=(0, 16))

    def on_close():
        root.withdraw()  # hide, don't kill the server

    root.protocol("WM_DELETE_WINDOW", on_close)

    def _pump():
        # lets Ctrl+C in the console actually interrupt tkinter's mainloop
        root.after(200, _pump)

    root.after(200, _pump)
    root.mainloop()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    ip, adapters = get_local_ip()
    print("=" * 60)
    print("Virtual Pad server starting")
    print(f"  WiFi pairing info : {ip}:{PORT}")
    for name, aip in adapters.items():
        if aip != ip and not any(x in name.lower() for x in ["warp", "virtual"]):
            print(f"  Alternative IP ({name}) : {aip}:{PORT}")
    print(f"  USB (adb reverse) : adb reverse tcp:{PORT} tcp:{PORT}")
    print("=" * 60)

    threading.Thread(target=udp_listener, daemon=True).start()
    threading.Thread(target=tcp_listener, daemon=True).start()

    try:
        if QR_AVAILABLE:
            try:
                show_qr_popup(ip, PORT)
            except Exception as e:
                print(f"(Could not open QR popup: {e})")

        print("\nServer is running! Press Ctrl+C to stop.")
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        release_everything()
        print("\nShutting down, all keys released.")


if __name__ == "__main__":
    main()
