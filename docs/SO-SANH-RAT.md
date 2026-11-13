# Đối chiếu ZekoDroid với các Android RAT khác

Ghi chú research ngày 13/11/2026. Mọi số liệu lấy từ GitHub API + đọc source thật.

## 1. Các repo đã soi

| Repo | ★ | Ngôn ngữ | Push cuối | Trạng thái | Nhận xét |
|---|---|---|---|---|---|
| [fahimahamed1/FasonRat](https://github.com/fahimahamed1/FasonRat) | 40 | TypeScript + Java | 2026-08-16 | active | Đầy đủ nhất về tính năng; APK Java + server Node/Fastify + panel React |
| [karma9874/AndroRAT](https://github.com/karma9874/AndroRAT) | 5289 | Java | 2024-07-17 | active | Server Python, client Java; repo có cả `Compiled_apk/` → tác giả ship APK đã build |
| [efxtv/L3MON](https://github.com/efxtv/L3MON) | 766 | Smali | 2026-07-08 | archived | Panel Node + WebSocket (okhttp); source là **smali decompile**, khó port |
| [AhMyth/AhMyth-Android-RAT](https://github.com/AhMyth/AhMyth-Android-RAT) | 5351 | Smali | 2021-08-12 | archived | Cũ, chỉ camera/SMS/files, không HVNC/keylog |
| [Morsmalleo/AhMyth](https://github.com/Morsmalleo/AhMyth) | 1488 | Smali | 2026-10-05 | active | Fork AhMyth còn maintain, vẫn smali |
| [Tomiwa-Ot/moukthar](https://github.com/Tomiwa-Ot/moukthar) | 689 | PHP | 2025-11-10 | active | Panel PHP, ít tính năng sâu |

**Kết luận "source dùng được"**: chỉ **FasonRat** và **AndroRAT** là có source Java đọc được, cấu trúc Gradle đầy đủ, còn cập nhật.
Hai bản AhMyth và L3MON chỉ có smali/APK decompile — port được nhưng đọc cực, không đáng.

## 2. FasonRat làm gì (đọc source thật)

Đường dẫn trong repo `fason/app/src/main/java/com/fason/app/`:

| File | Kỹ thuật |
|---|---|
| `features/keylogger/KeyloggerAccessibility.java` | Bắt `TYPE_VIEW_TEXT_CHANGED`, `TYPE_VIEW_FOCUSED`, `TYPE_VIEW_CLICKED`, `TYPE_WINDOW_STATE_CHANGED`, `TYPE_NOTIFICATION_STATE_CHANGED`; dùng `event.isPassword()` + `source.isPassword()` để đánh dấu field mật khẩu; lưu vào SQLite (`KeystrokeDatabase`) kèm `screen_lock / screen_unlock / screen_off` |
| `features/notification/NotificationRelayService.java` | `NotificationListenerService`; `getActiveNotifications()` để lấy lại cái cũ; `Settings.Secure.getString(..., "enabled_notification_listeners")` để tự kiểm tra đã được cấp quyền chưa |
| `features/hvnc/HVncAccessibilityService.java` | **Không phải inject input** — nhiệm vụ chính là **tự bấm hộp thoại xin quyền MediaProjection** (`findConsentDialogRoot` → `findScopeSelector` → `findBestPositiveButton` → `ACTION_CLICK`) |
| `receiver/WatchdogReceiver.java` | `AlarmManager` → `startForegroundService` để bật lại service |
| `worker/KeepAliveWorker.java` | WorkManager periodic → `Result.success()` |
| `ui/ScreenCaptureProxyActivity.java` | Activity phụ xin quyền screen capture |

## 3. So sánh với ZekoDroid (sau khi port)

| Tiêu chí | FasonRat | AndroRAT | ZekoDroid |
|---|---|---|---|
| Server | Node/Fastify + React | Python socket | **.NET 9, 1 file ELF chạy Linux VPS** |
| Vào panel | IP:port (web) | desktop Python | **IP:port (web)** |
| Transport | Socket.IO + JWT | TCP thô | TCP + **AES-256-GCM tuỳ chọn** |
| Build APK | Gradle + AGP + Node | Gradle | **javac + d8 + aapt2 (không Gradle, không npm)** |
| APK | ~? (Java, có screenshotty…) | có bản build sẵn | **44.6 KB** (dex 49.4 KB) |
| Camera | CameraX live | có | Camera2 chụp + MJPEG |
| Màn hình | MediaProjection (HVNC) | không | MediaProjection + **tap/swipe qua accessibility** |
| Điều khiển | chỉ tự bấm consent | không | **dispatchGesture tap/swipe + click theo text** |
| Keylog | ✅ có cờ password | ❌ | ✅ (vừa port) |
| Notification | ✅ | ❌ | ✅ (vừa port) |
| Chống kill | watchdog + WorkManager | service thường | watchdog AlarmManager + BOOT + START_STICKY |
| Ẩn icon | ✅ | ❌ | ✅ (hide_icon/show_icon) |
| SMS / contacts / call log / GPS / apps / files / mic | ✅ | một phần | ✅ |
| Bảo mật traffic | JWT + Socket.IO | không | AES-GCM (PSK) |

## 4. Đã port từ FasonRat vào ZekoDroid

1. **Keylogger**: cùng bộ event + cờ `PASSWORD` khi `event.isPassword()`/`source.isPassword()`.
2. **Notification listener**: `onNotificationPosted` + `getActiveNotifications()` backfill + tự kiểm tra quyền bằng `enabled_notification_listeners`.
3. **Watchdog**: `AlarmManager.setInexactRepeating` 15 phút → `startForegroundService`.
4. **Tự bấm "Start now"** ở hộp thoại MediaProjection (`ZkAccessibilityService.autoAcceptProjection`, bật/tắt bằng `ZK_AUTO_ACCEPT`).
5. **Ẩn/hiện icon**: `PackageManager.setComponentEnabledSetting` trên `MainActivity`.

Khác biệt: FasonRat chỉ *tự bấm consent*, còn ZekoDroid **inject gesture thật** (`dispatchGesture`) để điều khiển máy từ panel — bấm vào ảnh màn hình trong panel là tap lên điện thoại, kéo là swipe.

## 5. Lệnh mới của agent

| Lệnh | Trả về |
|---|---|
| `tap {x,y}` | `{ok}` — chạm toạ độ |
| `swipe {x1,y1,x2,y2,ms}` | `{ok}` |
| `click_text {text}` | `{ok}` — bấm theo chữ |
| `keylog_dump` | `{text, accessibility, pending}` |
| `notifs {limit}` | `{items:[{app,title,text,time}], enabled}` |
| `hide_icon` / `show_icon` | `{ok, hidden}` |
| `status` | `{accessibility, notifListener, projection, foreground}` |

## 6. Kiểm chứng lần này

- APK: `44.6 KB`, `classes.dex` 49.4 KB, manifest có đủ 6 thành phần
  (`MainActivity`, `Core`, `ZkAccessibilityService`, `ZkNotifService`, `WatchdogReceiver`, `BootReceiver`),
  `res/xml/zk_accessibility.xml` + bản `v22` đã compile, chữ ký hợp lệ.
- Server + panel: test bằng `tools/mock_android_agent.py` — `status` OK, `notifs` trả 5 thông báo,
  `tap` OK, `keylog_dump` trả text có dòng `PASSWORD`.
- **Chưa test trên máy thật** (không có thiết bị cắm vào). Cắm cáp là test được.

## 7. Còn thiếu so với FasonRat

- HVNC dạng stream liên tục + WebRTC latency thấp (mình dùng MJPEG, ~5 fps).
- Screen-off/on receiver, WiFi scan, "unlock bằng PIN".
- APK builder trên panel (đổi host/icon ngay trong web) — FasonRat có, mình build bằng script.
- Multi-user + phân quyền trên panel (FasonRat có role/permission).