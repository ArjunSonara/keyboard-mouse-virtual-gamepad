"""
Virtual Pad Standalone Desktop Launcher
========================================
Runs the zero-latency Keyboard + Mouse Virtual Gamepad Server.
Automatically handles ADB reverse port forwarding for USB mode,
displays the Wi-Fi pairing QR code, and requires zero cmd usage.
"""

import os
import sys
import time
import socket
import struct
import threading
import ctypes
import subprocess
import json

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)

try:
    import interception
except ImportError:
    import tkinter as tk
    from tkinter import messagebox
    root = tk.Tk()
    root.withdraw()
    messagebox.showerror(
        "Interception Driver Missing",
        "Could not load interception driver.\nPlease install install-interception.exe /install as Administrator and reboot your PC."
    )
    sys.exit(1)

try:
    import qrcode
    from PIL import Image, ImageTk
    QR_AVAILABLE = True
except ImportError:
    QR_AVAILABLE = False

PORT = 6001
STICK_DEADZONE = 0.3

BUTTON_ORDER = [
    "lb", "rb", "lt", "rt",
    "y", "x", "b", "a",
    "lsb", "rsb",
    "dpad_up", "dpad_down", "dpad_left", "dpad_right",
    "small_icon", "hamburger_icon",
] + [f"custom_{i}" for i in range(16)]

BUTTON_KEYMAP = {
    "lb": "tab",
    "rb": "q",
    "lt": "mouse_right",
    "rt": "mouse_left",
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
for i in range(16):
    BUTTON_KEYMAP.setdefault(f"custom_{i}", "f")

PACKET_FORMAT = "<BIbbhh"
PACKET_SIZE = struct.calcsize(PACKET_FORMAT)  # 11 bytes
PACKET_MAGIC = 0xAA
PACKET_MAGIC_CONFIG = 0xAC
PACKET_MAGIC_WHEEL = 0xAD
PACKET_MAGIC_MACRO_KEY = 0xAE

MOUSE_BUTTON_MAP = {
    "mouse_left": "left",
    "lmb": "left",
    "left_click": "left",
    "click_left": "left",
    "mouse_right": "right",
    "rmb": "right",
    "right_click": "right",
    "click_right": "right",
    "mouse_middle": "middle",
    "mmb": "middle",
    "middle_click": "middle",
    "click_middle": "middle",
}

MOUSE_EVENT_FLAGS = {
    "left": (0x0002, 0x0004),
    "right": (0x0008, 0x0010),
    "middle": (0x0020, 0x0040),
}

_interception_ctx = None
_mouse_devices = []

try:
    interception.auto_capture_devices(keyboard=True, mouse=True)
    _interception_ctx = interception.inputs._g_context
    for dev in range(10, 20):
        if _interception_ctx.is_mouse(dev):
            hwid = _interception_ctx.devices[dev].get_HWID()
            if hwid:
                _mouse_devices.append(dev)
    for dev in _mouse_devices:
        hwid = _interception_ctx.devices[dev].get_HWID() or ""
        if "VID_" in hwid and "{00001124" not in hwid:
            _interception_ctx.mouse = dev
            break
        elif any(k in hwid.upper() for k in ["ELAN", "SYNAPTICS"]):
            _interception_ctx.mouse = dev
            break
except Exception:
    pass

_mouse_move_count = 0

def move_mouse_relative(dx: int, dy: int):
    global _mouse_move_count
    if dx == 0 and dy == 0:
        return

    _mouse_move_count += 1
    try:
        ctypes.windll.user32.mouse_event(0x0001, int(dx), int(dy), 0, 0)
    except Exception:
        pass

    if _interception_ctx and _interception_ctx.mouse:
        try:
            stroke = interception.MouseStroke(
                interception.MouseFlag.MOUSE_MOVE_RELATIVE, 0, 0, int(dx), int(dy)
            )
            _interception_ctx.send(_interception_ctx.mouse, stroke)
        except Exception:
            pass

_held_ctx = {}
_key_sources = {}

def _actually_set_key(key: str, should_hold: bool):
    is_held = key in _held_ctx
    mouse_btn = MOUSE_BUTTON_MAP.get(key.lower())

    if mouse_btn:
        print(f"[Input] Mouse '{mouse_btn}' {'DOWN' if should_hold else 'UP'}", flush=True)
        down_flag, up_flag = MOUSE_EVENT_FLAGS[mouse_btn]
        if should_hold and not is_held:
            try:
                ctypes.windll.user32.mouse_event(down_flag, 0, 0, 0, 0)
            except Exception:
                pass
            try:
                if _interception_ctx and _interception_ctx.mouse:
                    btn_state = interception.inputs._get_button_states(mouse_btn, down=True)
                    stroke = interception.MouseStroke(
                        interception.MouseFlag.MOUSE_MOVE_RELATIVE,
                        btn_state,
                        0, 0, 0
                    )
                    _interception_ctx.send(_interception_ctx.mouse, stroke)
                _held_ctx[key] = True
            except Exception:
                _held_ctx[key] = True
        elif not should_hold and is_held:
            try:
                ctypes.windll.user32.mouse_event(up_flag, 0, 0, 0, 0)
            except Exception:
                pass
            try:
                if _interception_ctx and _interception_ctx.mouse:
                    btn_state = interception.inputs._get_button_states(mouse_btn, down=False)
                    stroke = interception.MouseStroke(
                        interception.MouseFlag.MOUSE_MOVE_RELATIVE,
                        btn_state,
                        0, 0, 0
                    )
                    _interception_ctx.send(_interception_ctx.mouse, stroke)
            except Exception:
                pass
            _held_ctx.pop(key, None)
    else:
        if should_hold and not is_held:
            try:
                ctx = interception.hold_key(key)
                ctx.__enter__()
                _held_ctx[key] = ctx
            except Exception:
                pass
        elif not should_hold and is_held:
            try:
                _held_ctx[key].__exit__(None, None, None)
            except Exception:
                pass
            finally:
                _held_ctx.pop(key, None)

_pulse_generations = {}


def _pulse_key(key: str):
    """
    Pulses a key or mouse button: releases it for ~35ms, then presses it back down
    so that games and emulators register a fresh click/keystroke even if another button
    (like Right Fire / swipe-aim) is already holding the same key.
    Uses generation counters to avoid race conditions when rapidly tapping.
    """
    gen = _pulse_generations.get(key, 0) + 1
    _pulse_generations[key] = gen

    def _do_pulse(my_gen):
        # 1. Send clean release so the game / emulator registers the UP transition
        with _state_lock:
            _actually_set_key(key, False)

        # 35ms sleep guarantees at least 2 complete frame ticks at 60Hz (16.6ms/frame)
        time.sleep(0.035)

        with _state_lock:
            # Only re-engage hold if this pulse is still the newest one and sources still want this key held
            if _pulse_generations.get(key) == my_gen:
                if len(_key_sources.get(key, set())) > 0:
                    _actually_set_key(key, True)

    threading.Thread(target=_do_pulse, args=(gen,), daemon=True).start()


def request_key(key: str, source: str, pressed: bool):
    sources = _key_sources.setdefault(key, set())
    if pressed:
        if source in sources:
            # This source is already holding the key (e.g. stick moving while already tilted).
            # Maintain the hold continuously without spamming pulses!
            return
        was_already_held = len(sources) > 0
        sources.add(source)
        if was_already_held:
            # Multi-Source Trigger: A DIFFERENT button was pressed while another button is already holding this key!
            # (e.g. Left Fire tapped while Right Fire is holding mouse_left to aim/swipe).
            # Pulse the key/mouse button so Windows and the game register a fresh click/down event.
            _pulse_key(key)
        else:
            _actually_set_key(key, True)
    else:
        if source not in sources:
            # This source wasn't holding the key anyway.
            return
        sources.discard(source)
        # When a source is released:
        # If other sources are still holding this key (e.g. right thumb still on screen),
        # keep the hold active without causing an unwanted second pulse/release glitch!
        _actually_set_key(key, len(sources) > 0)

def release_everything():
    for key in list(_held_ctx.keys()):
        _actually_set_key(key, False)
    _key_sources.clear()

_last_buttons = 0
_last_stick_x = 0.0
_last_stick_y = 0.0
_state_lock = threading.Lock()

def handle_config_packet(payload: bytes):
    try:
        json_str = payload[1:].decode("utf-8")
        new_map = json.loads(json_str)
        with _state_lock:
            for btn_id, key_name in new_map.items():
                BUTTON_KEYMAP[btn_id] = str(key_name).lower()
        print(f"[Config] Updated {len(new_map)} keys: {new_map}")
    except Exception:
        pass

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
            request_key("shift", "stick_sprint", stick_y < -0.82)
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

def handle_wheel_packet(data: bytes):
    if len(data) < 3:
        return
    wheel_delta = struct.unpack("<h", data[1:3])[0]
    if wheel_delta != 0:
        print(f"[Input] Wheel delta: {wheel_delta}", flush=True)
        try:
            ctypes.windll.user32.mouse_event(0x0800, 0, 0, int(wheel_delta), 0)
        except Exception:
            pass
        if _interception_ctx and _interception_ctx.mouse:
            try:
                button_data = 120 if wheel_delta > 0 else 65416
                stroke = interception.MouseStroke(
                    interception.MouseFlag.MOUSE_MOVE_RELATIVE,
                    interception.MouseButtonFlag.MOUSE_WHEEL,
                    button_data,
                    0,
                    0
                )
                _interception_ctx.send(_interception_ctx.mouse, stroke)
            except Exception:
                pass

def handle_macro_key_packet(data: bytes):
    if len(data) < 3:
        return
    pressed = bool(data[1])
    try:
        key = data[2:].decode("utf-8").strip().lower()
        if key:
            print(f"[Input] Macro key: '{key}' {'DOWN' if pressed else 'UP'}", flush=True)
            request_key(key, "macro", pressed)
    except Exception:
        pass

def dispatch_packet(data: bytes):
    if not data:
        return
    magic = data[0]
    if magic == PACKET_MAGIC:
        handle_raw_packet(data)
    elif magic == PACKET_MAGIC_CONFIG:
        handle_config_packet(data)
    elif magic == PACKET_MAGIC_WHEEL:
        handle_wheel_packet(data)
    elif magic == PACKET_MAGIC_MACRO_KEY:
        handle_macro_key_packet(data)

connection_status = "Waiting for phone..."

def udp_listener():
    global connection_status
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.bind(("0.0.0.0", PORT))
    except Exception:
        return
    while True:
        try:
            data, addr = sock.recvfrom(2048)
            dispatch_packet(data)
            connection_status = f"Connected (Wi-Fi: {addr[0]})"
        except Exception:
            continue

def tcp_listener():
    global connection_status
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("127.0.0.1", PORT))
        sock.listen(1)
    except Exception:
        return
    while True:
        try:
            conn, _ = sock.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            connection_status = "Connected (USB Cable)"
            buf = bytearray()
            while True:
                chunk = conn.recv(512)
                if not chunk:
                    break
                buf.extend(chunk)
                while len(buf) >= 2:
                    packet_len = struct.unpack("<H", buf[:2])[0]
                    if len(buf) < 2 + packet_len:
                        break
                    payload = bytes(buf[2 : 2 + packet_len])
                    del buf[: 2 + packet_len]
                    dispatch_packet(payload)
            connection_status = "Phone disconnected"
            release_everything()
        except Exception:
            connection_status = "Waiting for phone..."
            release_everything()
            continue

def _get_subprocess_kwargs():
    kwargs = {}
    if sys.platform == "win32":
        kwargs["creationflags"] = 0x08000000  # CREATE_NO_WINDOW
        si = subprocess.STARTUPINFO()
        si.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        si.wShowWindow = 0  # SW_HIDE
        kwargs["startupinfo"] = si
    return kwargs

def silent_run(*args, **kwargs):
    for k, v in _get_subprocess_kwargs().items():
        kwargs.setdefault(k, v)
    kwargs.setdefault("stdin", subprocess.DEVNULL)
    return subprocess.run(*args, **kwargs)

def silent_check_output(*args, **kwargs):
    for k, v in _get_subprocess_kwargs().items():
        kwargs.setdefault(k, v)
    kwargs.setdefault("stdin", subprocess.DEVNULL)
    return subprocess.check_output(*args, **kwargs)

usb_status_str = "Checking USB..."
_last_reversed_devices = set()
_cached_adb_bin = None

def get_adb_bin():
    global _cached_adb_bin
    if _cached_adb_bin:
        return _cached_adb_bin
    candidates = [
        "adb",
        r"C:\Users\arjun\AppData\Local\Android\Sdk\platform-tools\adb.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
    ]
    for c in candidates:
        try:
            r = silent_run([c, "version"], capture_output=True, text=True, timeout=2)
            if r.returncode == 0:
                _cached_adb_bin = c
                return _cached_adb_bin
        except Exception:
            continue
    return None

def auto_adb_reverse():
    global usb_status_str, _last_reversed_devices
    adb_bin = get_adb_bin()

    if not adb_bin:
        usb_status_str = "ADB not found (Use Wi-Fi or install Android SDK platform-tools)"
        return

    try:
        r = silent_run([adb_bin, "devices"], capture_output=True, text=True, timeout=2)
        devs = set([line.split("\t")[0].strip() for line in r.stdout.splitlines() if "\tdevice" in line])
        if devs:
            if devs != _last_reversed_devices:
                rev = silent_run([adb_bin, "reverse", f"tcp:{PORT}", f"tcp:{PORT}"], capture_output=True, text=True, timeout=2)
                if rev.returncode == 0:
                    _last_reversed_devices = devs
            usb_status_str = f"🟢 USB Ready: adb reverse active ({len(devs)} device connected)"
            return
        _last_reversed_devices.clear()
        usb_status_str = "⚪ USB: Plug in phone with USB Debugging enabled"
    except Exception as e:
        usb_status_str = f"USB Note: {e}"

def _usb_watcher_loop():
    while True:
        try:
            auto_adb_reverse()
            time.sleep(5)
        except Exception:
            time.sleep(5)

def get_local_ip():
    adapters = {}
    try:
        out = silent_check_output("ipconfig", text=True, errors="ignore")
        current_name = ""
        for line in out.splitlines():
            line = line.rstrip()
            if line and not line.startswith(" ") and not line.startswith("\t"):
                current_name = line.rstrip(":")
            elif "IPv4" in line:
                import re
                m = re.search(r":\s*([0-9.]+)", line)
                if m and current_name:
                    ip = m.group(1).strip()
                    if not ip.startswith("169.254."):
                        adapters[current_name] = ip
    except Exception:
        pass

    best_ip = None
    import re
    for name, ip in adapters.items():
        if re.search(r"wi-?fi", name, re.IGNORECASE) and not any(x in name.lower() for x in ["direct", "virtual"]):
            best_ip = ip
            break
    if not best_ip:
        for name, ip in adapters.items():
            if "ethernet" in name.lower() and not any(x in name.lower() for x in ["virtual", "vethernet", "vpn", "warp"]):
                best_ip = ip
                break
    if not best_ip:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            best_ip = s.getsockname()[0]
            s.close()
        except Exception:
            best_ip = "127.0.0.1"

    return best_ip

def main():
    import tkinter as tk

    local_ip = get_local_ip()

    # Start network workers
    threading.Thread(target=udp_listener, daemon=True).start()
    threading.Thread(target=tcp_listener, daemon=True).start()
    threading.Thread(target=_usb_watcher_loop, daemon=True).start()

    # Modern Dark GUI Window
    root = tk.Tk()
    root.title("Virtual Pad - Server (v2)")
    root.geometry("440x560")
    root.configure(bg="#0D1117")
    root.resizable(False, False)

    title_label = tk.Label(
        root, text="VIRTUAL PAD SERVER", font=("Segoe UI", 16, "bold"),
        fg="#58A6FF", bg="#0D1117"
    )
    title_label.pack(pady=(16, 4))

    subtitle_label = tk.Label(
        root, text="Zero-Latency Virtual Gamepad for PC", font=("Segoe UI", 10),
        fg="#8B949E", bg="#0D1117"
    )
    subtitle_label.pack(pady=(0, 12))

    # QR Code Frame
    qr_frame = tk.Frame(root, bg="#161B22", padx=12, pady=12, highlightbackground="#30363D", highlightthickness=1)
    qr_frame.pack(padx=20, pady=8)

    if QR_AVAILABLE:
        qr_img = qrcode.make(f"{local_ip}:{PORT}", box_size=5, border=2)
        tk_img = ImageTk.PhotoImage(qr_img)
        img_label = tk.Label(qr_frame, image=tk_img, bg="#161B22")
        img_label.image = tk_img
        img_label.pack()

    # Status Info
    status_label = tk.Label(
        root, text=f"Wi-Fi Pairing: {local_ip}:{PORT}", font=("Consolas", 11, "bold"),
        fg="#58A6FF", bg="#0D1117"
    )
    status_label.pack(pady=(8, 2))

    usb_label = tk.Label(
        root, text=usb_status_str, font=("Segoe UI", 9),
        fg="#3FB950", bg="#0D1117"
    )
    usb_label.pack(pady=(2, 4))

    conn_label = tk.Label(
        root, text=f"Status: {connection_status}", font=("Segoe UI", 10, "italic"),
        fg="#E6EDF3", bg="#0D1117"
    )
    conn_label.pack(pady=(4, 12))

    def update_gui_loop():
        conn_label.config(text=f"Status: {connection_status}")
        usb_label.config(text=usb_status_str)
        root.after(1000, update_gui_loop)

    root.after(1000, update_gui_loop)

    def on_exit():
        release_everything()
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_exit)

    root.mainloop()

if __name__ == "__main__":
    main()
