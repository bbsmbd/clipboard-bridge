package com.clipboardbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.clipboardbridge.core.Proto;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Settings. The PC trust key is wrapped with an Android Keystore key (hardware-backed where available). */
public final class Prefs {
    private static final String KS_ALIAS = "cbv1_wrap";
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("cbv1", Context.MODE_PRIVATE);
        if (sp.getString("phoneId", "").length() == 0) {
            sp.edit().putString("phoneId", Proto.randomId()).apply();
        }
    }

    public String phoneId() { return sp.getString("phoneId", ""); }

    public String phoneName() {
        String m = Build.MODEL == null ? "Android" : Build.MODEL;
        String b = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (b.length() > 0 && !m.toLowerCase().startsWith(b.toLowerCase())) m = b.substring(0, 1).toUpperCase() + b.substring(1) + " " + m;
        return m.length() > 40 ? m.substring(0, 40) : m;
    }

    public String lang() { return sp.getString("lang", "en"); }
    public void setLang(String l) { sp.edit().putString("lang", l).apply(); }

    public boolean enabled() { return sp.getBoolean("enabled", true); }
    public void setEnabled(boolean e) { sp.edit().putBoolean("enabled", e).apply(); }

    public boolean paired() { return pcId().length() > 0 && key() != null; }
    public String pcId() { return sp.getString("pcId", ""); }
    public String pcName() { return sp.getString("pcName", "PC"); }
    public String pcIp() { return sp.getString("pcIp", ""); }
    public void setPcIp(String ip) { sp.edit().putString("pcIp", ip).apply(); }

    public void savePair(String pcId, String pcName, String ip, byte[] key) {
        sp.edit().putString("pcId", pcId).putString("pcName", pcName).putString("pcIp", ip)
                .putString("key", wrap(key)).putBoolean("enabled", true).apply();
    }

    public void clearPair() {
        sp.edit().remove("pcId").remove("pcName").remove("pcIp").remove("key").apply();
    }

    public byte[] key() {
        String s = sp.getString("key", "");
        if (s.length() == 0) return null;
        byte[] k = unwrap(s);
        return k != null && k.length == 32 ? k : null;
    }

    // ---- Keystore wrapping ----
    private static SecretKey wrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KS_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KS_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static String wrap(byte[] key) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, wrapKey());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(key);
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return "K:" + Proto.b64e(all);
        } catch (Exception e) {
            return "P:" + Proto.b64e(key); // devices with a broken keystore
        }
    }

    private static byte[] unwrap(String s) {
        try {
            if (s.startsWith("P:")) return Proto.b64d(s.substring(2));
            if (!s.startsWith("K:")) return null;
            byte[] all = Proto.b64d(s.substring(2));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(128, all, 0, 12));
            return c.doFinal(all, 12, all.length - 12);
        } catch (Exception e) {
            return null;
        }
    }
}
