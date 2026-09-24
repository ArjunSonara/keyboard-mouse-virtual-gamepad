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
import atexit
import subprocess

if sys.platform == "win32":
    try:
        ctypes.windll.winmm.timeBeginPeriod(1)
        atexit.register(ctypes.windll.winmm.timeEndPeriod, 1)
        print("[System] Windows high-resolution multimedia timer initialized (1.0ms resolution).")
    except Exception as e:
        print(f"[System] Warning: could not set 1ms timer resolution: {e}")

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

    # Clean exclusive routing:
    # 1. Forward raw stroke through Interception driver kernel filter if available
    if _interception_ctx and _interception_ctx.mouse:
        try:
            stroke = interception.MouseStroke(
                interception.MouseFlag.MOUSE_MOVE_RELATIVE, 0, 0, int(dx), int(dy)
            )
            _interception_ctx.send(_interception_ctx.mouse, stroke)
            return
        except Exception:
            pass

    # 2. Fallback to Windows user32 mouse_event only if Interception mouse device is unavailable
    try:
        ctypes.windll.user32.mouse_event(0x0001, int(dx), int(dy), 0, 0)
    except Exception:
        pass

_held_ctx = {}
_key_sources = {}

KEY_ALIASES = {
    "numpad0": "num0", "numpad1": "num1", "numpad2": "num2",
    "numpad3": "num3", "numpad4": "num4", "numpad5": "num5",
    "numpad6": "num6", "numpad7": "num7", "numpad8": "num8",
    "numpad9": "num9", "num_0": "num0", "num_1": "num1",
    "num_2": "num2", "num_3": "num3", "num_4": "num4",
    "num_5": "num5", "num_6": "num6", "num_7": "num7",
    "num_8": "num8", "num_9": "num9",
}

def _actually_set_key(key: str, should_hold: bool):
    key = KEY_ALIASES.get(key.lower(), key.lower())
    is_held = key in _held_ctx
    mouse_btn = MOUSE_BUTTON_MAP.get(key.lower())

    if mouse_btn:
        print(f"[Input] Mouse '{mouse_btn}' {'DOWN' if should_hold else 'UP'}", flush=True)
        down_flag, up_flag = MOUSE_EVENT_FLAGS[mouse_btn]
        if should_hold and not is_held:
            # 1. Prefer exclusive Interception kernel injection
            sent_interception = False
            if _interception_ctx and _interception_ctx.mouse:
                try:
                    btn_state = interception.inputs._get_button_states(mouse_btn, down=True)
                    stroke = interception.MouseStroke(
                        interception.MouseFlag.MOUSE_MOVE_RELATIVE,
                        btn_state,
                        0, 0, 0
                    )
                    _interception_ctx.send(_interception_ctx.mouse, stroke)
                    sent_interception = True
                except Exception:
                    pass
            # 2. Fallback to user32 mouse_event if Interception unavailable
            if not sent_interception:
                try:
                    ctypes.windll.user32.mouse_event(down_flag, 0, 0, 0, 0)
                except Exception:
                    pass
            _held_ctx[key] = True
        elif not should_hold and is_held:
            # 1. Prefer exclusive Interception kernel injection
            sent_interception = False
            if _interception_ctx and _interception_ctx.mouse:
                try:
                    btn_state = interception.inputs._get_button_states(mouse_btn, down=False)
                    stroke = interception.MouseStroke(
                        interception.MouseFlag.MOUSE_MOVE_RELATIVE,
                        btn_state,
                        0, 0, 0
                    )
                    _interception_ctx.send(_interception_ctx.mouse, stroke)
                    sent_interception = True
                except Exception:
                    pass
            # 2. Fallback to user32 mouse_event if Interception unavailable
            if not sent_interception:
                try:
                    ctypes.windll.user32.mouse_event(up_flag, 0, 0, 0, 0)
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
        sock.listen(5)
    except Exception:
        return
    while True:
        try:
            conn, _ = sock.accept()
            try:
                conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                try:
                    conn.ioctl(socket.SIO_KEEPALIVE_VALS, (1, 5000, 1000))
                except Exception:
                    pass
                connection_status = "Connected (USB Cable)"
                print("[USB/TCP] phone connected over USB", flush=True)
                buf = bytearray()
                while True:
                    chunk = conn.recv(1024)
                    if not chunk:
                        break
                    buf.extend(chunk)
                    # Frame format: 2-byte little endian unsigned short (length) + payload
                    # Sliding pointer parser: zero array reallocations during 1000Hz ultra-polling
                    offset = 0
                    buf_len = len(buf)
                    while buf_len - offset >= 2:
                        packet_len = struct.unpack_from("<H", buf, offset)[0]
                        if packet_len > 4096 or packet_len == 0:
                            # Framing desynchronization guard: advance 1 byte
                            offset += 1
                            continue
                        if buf_len - offset < 2 + packet_len:
                            break
                        payload = bytes(buf[offset + 2 : offset + 2 + packet_len])
                        offset += 2 + packet_len
                        try:
                            dispatch_packet(payload)
                        except Exception as ex:
                            print(f"[Input Error] {ex}", flush=True)
                    if offset > 0:
                        if offset >= buf_len:
                            buf.clear()
                        else:
                            del buf[:offset]
            finally:
                try:
                    conn.close()
                except Exception:
                    pass
            connection_status = "Phone disconnected"
            print("[USB/TCP] phone disconnected", flush=True)
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

def silent_popen(*args, **kwargs):
    for k, v in _get_subprocess_kwargs().items():
        kwargs.setdefault(k, v)
    kwargs.setdefault("stdin", subprocess.DEVNULL)
    return subprocess.Popen(*args, **kwargs)

usb_status_str = "Checking USB..."
_last_reversed_devices = set()
_cached_adb_bin = None

def get_adb_bin():
    global _cached_adb_bin
    if _cached_adb_bin and os.path.exists(_cached_adb_bin):
        return _cached_adb_bin

    # Check PATH first via shutil.which (instant, no subprocess needed)
    adb_which = shutil.which("adb")
    if adb_which:
        _cached_adb_bin = adb_which
        return _cached_adb_bin

    exe_dir = os.path.dirname(os.path.abspath(sys.executable))
    candidates = [
        os.path.join(exe_dir, "adb.exe"),
        os.path.join(exe_dir, "platform-tools", "adb.exe"),
        r"C:\Users\arjun\Downloads\platform-tools-latest-windows\platform-tools\adb.exe",
        r"C:\Users\arjun\AppData\Local\Android\Sdk\platform-tools\adb.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
    ]
    for c in candidates:
        if os.path.isfile(c):
            _cached_adb_bin = c
            return _cached_adb_bin
        try:
            r = silent_run([c, "version"], capture_output=True, text=True, timeout=5)
            if r.returncode == 0:
                _cached_adb_bin = c
                return _cached_adb_bin
        except Exception:
            continue
    return None

REVERSE_PORTS = [6001, 8080, 8081, 8082]
pcmirror_process = None
pcmirror_user_wanted = True
_last_pcmirror_auto_restart = 0
job_object_handle = None

def init_job_object():
    global job_object_handle
    try:
        from ctypes import wintypes
        kernel32 = ctypes.windll.kernel32
        job = kernel32.CreateJobObjectW(None, None)
        if job:
            JOBOBJECT_EXTENDED_LIMIT_INFORMATION = 9
            class JOBOBJECT_BASIC_LIMIT_INFORMATION(ctypes.Structure):
                _fields_ = [
                    ("PerProcessUserTimeLimit", wintypes.LARGE_INTEGER),
                    ("PerJobUserTimeLimit", wintypes.LARGE_INTEGER),
                    ("LimitFlags", wintypes.DWORD),
                    ("MinimumWorkingSetSize", ctypes.c_size_t),
                    ("MaximumWorkingSetSize", ctypes.c_size_t),
                    ("ActiveProcessLimit", wintypes.DWORD),
                    ("Affinity", ctypes.c_size_t),
                    ("PriorityClass", wintypes.DWORD),
                    ("SchedulingClass", wintypes.DWORD),
                ]
            class IO_COUNTERS(ctypes.Structure):
                _fields_ = [
                    ("ReadOperationCount", ctypes.c_ulonglong),
                    ("WriteOperationCount", ctypes.c_ulonglong),
                    ("OtherOperationCount", ctypes.c_ulonglong),
                    ("ReadTransferCount", ctypes.c_ulonglong),
                    ("WriteTransferCount", ctypes.c_ulonglong),
                    ("OtherTransferCount", ctypes.c_ulonglong),
                ]
            class JOBOBJECT_EXTENDED_LIMIT_INFORMATION_STRUCT(ctypes.Structure):
                _fields_ = [
                    ("BasicLimitInformation", JOBOBJECT_BASIC_LIMIT_INFORMATION),
                    ("IoCounters", IO_COUNTERS),
                    ("ProcessMemoryLimit", ctypes.c_size_t),
                    ("JobMemoryLimit", ctypes.c_size_t),
                    ("PeakProcessMemoryLimit", ctypes.c_size_t),
                    ("PeakJobMemoryLimit", ctypes.c_size_t),
                ]
            info = JOBOBJECT_EXTENDED_LIMIT_INFORMATION_STRUCT()
            JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            kernel32.SetInformationJobObject(job, JOBOBJECT_EXTENDED_LIMIT_INFORMATION, ctypes.byref(info), ctypes.sizeof(info))
            job_object_handle = job
    except Exception as e:
        print(f"[WARN] Could not initialize Windows Job Object: {e}")

def assign_process_to_job(proc):
    if job_object_handle and proc and hasattr(proc, "_handle"):
        try:
            kernel32 = ctypes.windll.kernel32
            kernel32.AssignProcessToJobObject(job_object_handle, proc._handle)
        except Exception as e:
            print(f"[WARN] Could not assign process to Job Object: {e}")

def get_pcmirror_bin():
    # Check PyInstaller bundle dir, exe dir, script dir, or workspace root
    base_dirs = [
        getattr(sys, "_MEIPASS", ""),
        os.path.dirname(os.path.abspath(sys.executable)),
        os.path.dirname(os.path.abspath(__file__)),
        os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")),
        os.getcwd(),
    ]
    for b in base_dirs:
        if b:
            p = os.path.join(b, "PCMirror.exe")
            if os.path.isfile(p):
                return p
    return shutil.which("PCMirror.exe")

def start_pcmirror_daemon():
    global pcmirror_process, pcmirror_user_wanted
    pcmirror_user_wanted = True
    # Preflight sweep: cleanly kill any zombie PCMirror.exe from previous dirty crashes
    try:
        silent_run(["taskkill", "/F", "/IM", "PCMirror.exe"], capture_output=True, timeout=3)
        time.sleep(0.3)
    except Exception:
        pass

    pcmirror_bin = get_pcmirror_bin()
    if not pcmirror_bin:
        print("[WARN] PCMirror.exe not found. Screen mirroring will require manual launch.")
        return

    try:
        # Args: <port> <width> <height> <fps> <bitrate> [--no-adb-watcher]
        # 8080 0 0 120 80000000 (0 0 auto-detects native screen resolution)
        cmd = [pcmirror_bin, "8080", "0", "0", "120", "80000000", "--no-adb-watcher"]
        pcmirror_process = silent_popen(cmd, cwd=os.path.dirname(pcmirror_bin))
        assign_process_to_job(pcmirror_process)
        print(f"[INFO] PCMirror native engine started (PID: {pcmirror_process.pid})")
    except Exception as e:
        print(f"[ERROR] Failed to start PCMirror: {e}")

def stop_pcmirror_daemon():
    global pcmirror_process, pcmirror_user_wanted
    pcmirror_user_wanted = False
    if pcmirror_process:
        try:
            pcmirror_process.terminate()
            pcmirror_process.wait(timeout=1)
        except Exception:
            pass
        pcmirror_process = None
    try:
        silent_run(["taskkill", "/F", "/IM", "PCMirror.exe"], capture_output=True, timeout=2)
    except Exception:
        pass
    print("[INFO] PCMirror native engine stopped.")

def toggle_pcmirror_daemon():
    global pcmirror_process, pcmirror_user_wanted
    is_running = False
    if pcmirror_process and pcmirror_process.poll() is None:
        is_running = True
    else:
        try:
            r = silent_run(["tasklist", "/FI", "IMAGENAME eq PCMirror.exe"], capture_output=True, text=True, timeout=2)
            if "PCMirror.exe" in r.stdout:
                is_running = True
        except Exception:
            pass

    if is_running:
        stop_pcmirror_daemon()
        return False
    else:
        start_pcmirror_daemon()
        return True

_adb_fail_count = 0

def auto_adb_reverse():
    global usb_status_str, _last_reversed_devices, _adb_fail_count
    adb_bin = get_adb_bin()

    if not adb_bin:
        usb_status_str = "ADB not found (Use Wi-Fi or install Android platform-tools)"
        return

    try:
        r = silent_run([adb_bin, "devices"], capture_output=True, text=True, timeout=8)
        lines = r.stdout.splitlines() if r.returncode == 0 else []
        all_devs = [line.split("\t")[0].strip() for line in lines if "\tdevice" in line]

        # Prioritize physical USB devices over emulators (e.g. emulator-5554)
        physical_devs = [d for d in all_devs if not d.startswith("emulator-")]
        devs = physical_devs if physical_devs else all_devs

        unauthorized = any("\tunauthorized" in line for line in lines)
        offline = any("\toffline" in line for line in lines)

        if offline and not devs:
            silent_run([adb_bin, "reconnect", "offline"], capture_output=True, timeout=4)
            time.sleep(0.5)
            r = silent_run([adb_bin, "devices"], capture_output=True, text=True, timeout=8)
            lines = r.stdout.splitlines() if r.returncode == 0 else []
            all_devs = [line.split("\t")[0].strip() for line in lines if "\tdevice" in line]
            physical_devs = [d for d in all_devs if not d.startswith("emulator-")]
            devs = physical_devs if physical_devs else all_devs

        if unauthorized and not devs:
            usb_status_str = "⚠️ Phone unauthorized: Tap 'Allow USB debugging' on phone"
            _last_reversed_devices.clear()
            _adb_fail_count = 0
            return

        if devs:
            _adb_fail_count = 0
            target_dev = devs[0]  # Target primary physical device

            # Query existing reverse list specifically for this device
            rev_list = silent_run([adb_bin, "-s", target_dev, "reverse", "--list"], capture_output=True, text=True, timeout=5)
            rev_output = rev_list.stdout if rev_list.returncode == 0 else ""

            # Identify which ports (if any) are actually missing
            missing_ports = [p for p in REVERSE_PORTS if f"tcp:{p}" not in rev_output]

            # If all ports are already routed and device hasn't changed, DO NOT re-run adb reverse!
            # (Re-running adb reverse resets active TCP tunnels and severs live connections!)
            if not missing_ports and set(devs) == _last_reversed_devices:
                usb_status_str = f"🟢 USB Ready: All 4 ports routed ({target_dev})"
                return

            # Route ONLY missing ports or ports for a newly attached device
            all_ok = True
            ports_to_route = missing_ports if (set(devs) == _last_reversed_devices and missing_ports) else REVERSE_PORTS

            for p in ports_to_route:
                rev = silent_run([adb_bin, "-s", target_dev, "reverse", f"tcp:{p}", f"tcp:{p}"], capture_output=True, text=True, timeout=5)
                if rev.returncode != 0:
                    all_ok = False

            if all_ok or not missing_ports:
                _last_reversed_devices = set(devs)
                usb_status_str = f"🟢 USB Ready: All 4 ports routed ({target_dev})"
            else:
                usb_status_str = "⚠️ ADB reverse partial/failed on some ports"
            return

        # Debounce disconnects: require 3 consecutive failed queries before clearing
        _adb_fail_count += 1
        if _adb_fail_count >= 3:
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

    init_job_object()
    start_pcmirror_daemon()

    local_ip = get_local_ip()

    # Start network workers
    threading.Thread(target=udp_listener, daemon=True).start()
    threading.Thread(target=tcp_listener, daemon=True).start()
    threading.Thread(target=_usb_watcher_loop, daemon=True).start()

    # Modern Dark GUI Window
    root = tk.Tk()
    root.title("VirtualPad Console Server (Hybrid)")
    root.geometry("460x650")
    root.configure(bg="#0D1117")
    root.resizable(False, False)

    title_label = tk.Label(
        root, text="VIRTUALPAD CONSOLE SERVER", font=("Segoe UI", 15, "bold"),
        fg="#58A6FF", bg="#0D1117"
    )
    title_label.pack(pady=(16, 4))

    subtitle_label = tk.Label(
        root, text="Zero-Latency Screen Mirroring & Gamepad Server", font=("Segoe UI", 9),
        fg="#8B949E", bg="#0D1117"
    )
    subtitle_label.pack(pady=(0, 10))

    # QR Code Frame
    qr_frame = tk.Frame(root, bg="#161B22", padx=10, pady=10, highlightbackground="#30363D", highlightthickness=1)
    qr_frame.pack(padx=20, pady=6)

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
    status_label.pack(pady=(6, 2))

    services_label = tk.Label(
        root, text="🎮 Input: :6001 | 🖥️ Video: :8080 | 🔊 Audio: :8082", font=("Consolas", 9),
        fg="#79C0FF", bg="#0D1117"
    )
    services_label.pack(pady=(2, 4))

    usb_label = tk.Label(
        root, text=usb_status_str, font=("Segoe UI", 9),
        fg="#3FB950", bg="#0D1117"
    )
    usb_label.pack(pady=(2, 4))

    conn_label = tk.Label(
        root, text=f"Status: {connection_status}", font=("Segoe UI", 10, "italic"),
        fg="#E6EDF3", bg="#0D1117"
    )
    conn_label.pack(pady=(4, 8))

    # Screen Mirroring Control Card
    mirror_card = tk.Frame(root, bg="#161B22", padx=12, pady=8, highlightbackground="#30363D", highlightthickness=1)
    mirror_card.pack(fill="x", padx=20, pady=(0, 10))

    mirror_status_var = tk.StringVar(value="🖥️ Screen Mirror: ACTIVE")
    mirror_status_label = tk.Label(
        mirror_card, textvariable=mirror_status_var, font=("Segoe UI", 9, "bold"),
        fg="#3FB950", bg="#161B22"
    )
    mirror_status_label.pack(side="left", padx=4)

    def on_toggle_mirror():
        active = toggle_pcmirror_daemon()
        if active:
            mirror_status_var.set("🖥️ Screen Mirror: ACTIVE")
            mirror_status_label.config(fg="#3FB950")
            btn_toggle_mirror.config(text="Turn OFF", bg="#21262D", fg="#F85149")
        else:
            mirror_status_var.set("⚡ Pure Gamepad: OFF (0% CPU/GPU)")
            mirror_status_label.config(fg="#E3B341")
            btn_toggle_mirror.config(text="Turn ON", bg="#238636", fg="#FFFFFF")

    btn_toggle_mirror = tk.Button(
        mirror_card, text="Turn OFF", font=("Segoe UI", 8, "bold"),
        bg="#21262D", fg="#F85149", relief="flat", padx=10, pady=2,
        command=on_toggle_mirror, cursor="hand2"
    )
    btn_toggle_mirror.pack(side="right", padx=4)

    def update_gui_loop():
        global _last_pcmirror_auto_restart
        conn_label.config(text=f"Status: {connection_status}")
        usb_label.config(text=usb_status_str)
        is_alive = pcmirror_process is not None and pcmirror_process.poll() is None
        if is_alive:
            mirror_status_var.set("🖥️ Screen Mirror: ACTIVE")
            mirror_status_label.config(fg="#3FB950")
            btn_toggle_mirror.config(text="Turn OFF", bg="#21262D", fg="#F85149")
        else:
            if pcmirror_user_wanted:
                mirror_status_var.set("🔄 Mirror Engine Recovering...")
                mirror_status_label.config(fg="#58A6FF")
                btn_toggle_mirror.config(text="Turn OFF", bg="#21262D", fg="#F85149")
                now = time.time()
                if now - _last_pcmirror_auto_restart >= 2.5:
                    _last_pcmirror_auto_restart = now
                    print("[WATCHDOG] PCMirror engine exited or crashed. Auto-recovering...")
                    start_pcmirror_daemon()
            else:
                mirror_status_var.set("⚡ Pure Gamepad: OFF (0% CPU/GPU)")
                mirror_status_label.config(fg="#E3B341")
                btn_toggle_mirror.config(text="Turn ON", bg="#238636", fg="#FFFFFF")
        root.after(1000, update_gui_loop)

    root.after(1000, update_gui_loop)

    def on_exit():
        release_everything()
        stop_pcmirror_daemon()
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_exit)

    root.mainloop()

if __name__ == "__main__":
    main()
