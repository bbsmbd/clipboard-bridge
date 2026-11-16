package com.clipboardbridge.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Clipboard Bridge V1 wire protocol (must match pc-app/src/proto.rs).
 * Frame: [u32 BE length][kind 'P'|'E'][payload]; E payload = iv(12) || AES-256-GCM ciphertext+tag.
 * Pure Java (no android.*) so it can be tested on a desktop JVM.
 */
public final class Proto {
    public static final int UDP_PORT = 54545;
    public static final int TCP_PORT = 54546;
    public static final int VERSION = 1;
    public static final int MAX_FRAME = 32 * 1024 * 1024;
    public static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    public static final int MAX_TEXT_CHARS = 1000000;
    public static final int PBKDF2_ITERS = 50000;
    public static final byte KIND_PLAIN = 'P';
    public static final byte KIND_ENC = 'E';
    /** 'F' = AES-GCM([u32 BE header len][header JSON][raw bytes]) — images */
    public static final byte KIND_FILE = 'F';
    public static final Charset UTF8 = Charset.forName("UTF-8");

    private static final SecureRandom RNG = new SecureRandom();

    private Proto() {}

    public static final class Frame {
        public final byte kind;
        public final byte[] payload;
        Frame(byte kind, byte[] payload) { this.kind = kind; this.payload = payload; }
    }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static String randomId() {
        byte[] b = random(8);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /** PBKDF2-HMAC-SHA256 (implemented by hand: the JCE name is missing below Android 8). */
    public static byte[] pinKey(String pin, byte[] salt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pin.getBytes(UTF8), "HmacSHA256"));
            mac.update(salt);
            mac.update(new byte[]{0, 0, 0, 1});
            byte[] u = mac.doFinal();
            byte[] out = u.clone();
            for (int i = 1; i < PBKDF2_ITERS; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < out.length; j++) out[j] ^= u[j];
            }
            return out; // 32 bytes
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] encrypt(byte[] key, byte[] plain) {
        try {
            byte[] iv = random(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    /** @return plaintext or null when the key is wrong / data damaged. */
    public static byte[] decrypt(byte[] key, byte[] data) {
        if (data == null || data.length < 28) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, data, 0, 12));
            return c.doFinal(data, 12, data.length - 12);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    public static void writeFrame(OutputStream out, byte kind, byte[] payload) throws IOException {
        int len = payload.length + 1;
        if (len > MAX_FRAME) throw new IOException("frame too large");
        ByteArrayOutputStream b = new ByteArrayOutputStream(len + 4);
        b.write((len >>> 24) & 0xff);
        b.write((len >>> 16) & 0xff);
        b.write((len >>> 8) & 0xff);
        b.write(len & 0xff);
        b.write(kind);
        b.write(payload, 0, payload.length);
        out.write(b.toByteArray());
        out.flush();
    }

    public static Frame readFrame(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len <= 0 || len > MAX_FRAME) throw new IOException("bad frame length " + len);
        byte[] buf = new byte[len];
        in.readFully(buf);
        byte[] p = new byte[len - 1];
        System.arraycopy(buf, 1, p, 0, len - 1);
        return new Frame(buf[0], p);
    }

    public static void sendPlain(OutputStream out, JSONObject o) throws IOException {
        writeFrame(out, KIND_PLAIN, o.toString().getBytes(UTF8));
    }

    public static void sendEnc(OutputStream out, byte[] key, JSONObject o) throws IOException {
        writeFrame(out, KIND_ENC, encrypt(key, o.toString().getBytes(UTF8)));
    }

    public static void sendFile(OutputStream out, byte[] key, JSONObject header, byte[] data) throws IOException {
        byte[] h = header.toString().getBytes(UTF8);
        byte[] plain = new byte[4 + h.length + data.length];
        plain[0] = (byte) (h.length >>> 24);
        plain[1] = (byte) (h.length >>> 16);
        plain[2] = (byte) (h.length >>> 8);
        plain[3] = (byte) h.length;
        System.arraycopy(h, 0, plain, 4, h.length);
        System.arraycopy(data, 0, plain, 4 + h.length, data.length);
        writeFrame(out, KIND_FILE, encrypt(key, plain));
    }

    /** Decrypted 'F' frame: [0] = header JSONObject, [1] = byte[] data; null if invalid. */
    public static Object[] decryptFile(byte[] key, byte[] payload) {
        byte[] p = decrypt(key, payload);
        if (p == null || p.length < 4) return null;
        int hl = ((p[0] & 0xff) << 24) | ((p[1] & 0xff) << 16) | ((p[2] & 0xff) << 8) | (p[3] & 0xff);
        if (hl < 0 || 4 + hl > p.length) return null;
        byte[] hb = new byte[hl];
        System.arraycopy(p, 4, hb, 0, hl);
        JSONObject h = parse(hb);
        if (h == null) return null;
        byte[] data = new byte[p.length - 4 - hl];
        System.arraycopy(p, 4 + hl, data, 0, data.length);
        return new Object[]{h, data};
    }

    public static String shaHex(byte[] b) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            return "";
        }
    }

    public static JSONObject parse(byte[] b) {
        if (b == null) return null;
        try {
            return new JSONObject(new String(b, UTF8));
        } catch (JSONException e) {
            return null;
        }
    }

    public static JSONObject decryptJson(byte[] key, byte[] data) {
        return parse(decrypt(key, data));
    }

    public static JSONObject obj(String... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
        } catch (JSONException ignored) {
        }
        return o;
    }

    // ---- Base64 (java.util.Base64 needs Android 8, android.util.Base64 is not on the JVM) ----
    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String b64e(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 0xff;
            int b1 = i + 1 < d.length ? d[i + 1] & 0xff : 0;
            int b2 = i + 2 < d.length ? d[i + 2] & 0xff : 0;
            sb.append(B64[b0 >> 2]);
            sb.append(B64[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < d.length ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < d.length ? B64[b2 & 63] : '=');
        }
        return sb.toString();
    }

    public static byte[] b64d(String s) {
        if (s == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+') v = 62;
            else if (c == '/') v = 63;
            else if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue;
            else return null;
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }

    public static String preview(String t) {
        String one = t.replace('\n', ' ').replace('\r', ' ');
        return one.length() > 40 ? one.substring(0, 40) + "..." : one;
    }
}
