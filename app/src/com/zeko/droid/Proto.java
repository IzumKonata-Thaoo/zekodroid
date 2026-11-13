package com.zeko.droid;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Khung truyền giống server: [4 byte độ dài LE][payload], payload JSON hoặc AES-256-GCM. */
public final class Proto {

    private final byte[] key;   // null = không mã hoá

    public Proto(String psk) {
        byte[] k = null;
        if (psk != null && !psk.trim().isEmpty()) {
            try {
                k = MessageDigest.getInstance("SHA-256").digest(psk.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
        this.key = k;
    }

    public boolean encrypted() { return key != null; }

    public void write(OutputStream os, JSONObject o) throws Exception {
        byte[] json = o.toString().getBytes(StandardCharsets.UTF_8);
        byte[] payload;
        if (key == null) {
            payload = json;
        } else {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(json);
            payload = new byte[12 + 16 + ct.length];
            System.arraycopy(nonce, 0, payload, 0, 12);
            // tag nằm ở cuối ct của JCE → tách ra đúng vị trí server mong đợi
            System.arraycopy(ct, ct.length - 16, payload, 12, 16);
            System.arraycopy(ct, 0, payload, 28, ct.length - 16);
        }
        byte[] head = new byte[4];
        ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length);
        synchronized (os) {
            os.write(head);
            os.write(payload);
            os.flush();
        }
    }

    public JSONObject read(InputStream is) throws IOException {
        byte[] head = new byte[4];
        if (!readExact(is, head, 4)) return null;
        int len = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len <= 0 || len > 64 * 1024 * 1024) return null;
        byte[] payload = new byte[len];
        if (!readExact(is, payload, len)) return null;

        byte[] json;
        if (key == null) {
            json = payload;
        } else {
            try {
                if (payload.length < 29) return null;
                byte[] nonce = new byte[12];
                byte[] tag = new byte[16];
                byte[] ct = new byte[payload.length - 28];
                System.arraycopy(payload, 0, nonce, 0, 12);
                System.arraycopy(payload, 12, tag, 0, 16);
                System.arraycopy(payload, 28, ct, 0, ct.length);

                byte[] combined = new byte[ct.length + 16];
                System.arraycopy(ct, 0, combined, 0, ct.length);
                System.arraycopy(tag, 0, combined, ct.length, 16);

                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
                json = c.doFinal(combined);
            } catch (Exception e) {
                return null;
            }
        }
        try {
            return new JSONObject(new String(json, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean readExact(InputStream is, byte[] buf, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = is.read(buf, off, n - off);
            if (r <= 0) return false;
            off += r;
        }
        return true;
    }

    public static String b64(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    public static byte[] unb64(String s) {
        return android.util.Base64.decode(s, android.util.Base64.NO_WRAP);
    }
}
