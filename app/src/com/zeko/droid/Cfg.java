package com.zeko.droid;

import android.content.Context;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Cấu hình: mặc định build-time + override runtime từ assets/zk.conf.
 * → panel Builder chỉ cần thay file assets rồi ký lại, không cần compile dex.
 */
public final class Cfg {
    // giá trị mặc định (không còn bị build.ps1 ghi đè)
    public static final String HOST = "127.0.0.1";
    public static final int    PORT = 7878;
    public static final String PSK  = "";
    public static final boolean AUTO_ACCEPT_PROJECTION = true;

    private static volatile String rtHost, rtPsk;
    private static volatile int rtPort;
    private static volatile int rtAuto = -1;
    private static volatile boolean loaded;

    public static String host() { return rtHost != null ? rtHost : HOST; }
    public static int port() { return rtPort > 0 ? rtPort : PORT; }
    public static String psk() { return rtPsk != null ? rtPsk : PSK; }
    public static boolean auto() { return rtAuto < 0 ? AUTO_ACCEPT_PROJECTION : rtAuto == 1; }

    public static synchronized void load(Context ctx) {
        if (loaded) return;
        loaded = true;
        try (InputStream is = ctx.getAssets().open("zk.conf")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim().toLowerCase();
                String v = line.substring(i + 1).trim();
                if (k.equals("host") && !v.isEmpty()) rtHost = v;
                else if (k.equals("port")) {
                    try { rtPort = Integer.parseInt(v); } catch (Throwable ignored) { }
                } else if (k.equals("psk")) rtPsk = v;
                else if (k.equals("auto")) rtAuto = v.equals("1") ? 1 : 0;
            }
        } catch (Throwable ignored) {
            // không có assets/zk.conf → dùng giá trị mặc định
        }
    }
}
