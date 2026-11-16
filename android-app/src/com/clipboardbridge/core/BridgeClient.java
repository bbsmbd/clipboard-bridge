package com.clipboardbridge.core;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * One TCP connection to the PC app. Static helpers for probe / pair, instance for a live session.
 * Pure Java: tested against the Rust PC app on a desktop JVM.
 */
public class BridgeClient {

    public interface Binder {
        /** Bind the socket to the right network (WiFi) before connect. May do nothing. */
        void bind(Socket s, String ip);
    }

    public interface Listener {
        void onClip(String text);
        void onImage(byte[] data, String mime);
        void onUnpaired();
    }

    /** Thrown when the PC answered but refused (wrong PIN, unknown phone, locked...). */
    public static class Refused extends IOException {
        public final String reason;
        public final int secs;
        public Refused(String reason, int secs) {
            super("refused: " + reason);
            this.reason = reason;
            this.secs = secs;
        }
    }

    public static class Hi {
        public String ip, pcId, name, salt, nonce;
        public boolean known;
        public int locked;
    }

    public static class Paired {
        public String ip, pcId, name;
        public byte[] key;
    }

    private static final Binder NO_BIND = new Binder() {
        public void bind(Socket s, String ip) {}
    };

    private static Socket open(String ip, int connectMs, int readMs, Binder b) throws IOException {
        Socket s = new Socket();
        try {
            (b == null ? NO_BIND : b).bind(s, ip);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.connect(new InetSocketAddress(ip, Proto.TCP_PORT), connectMs);
            s.setSoTimeout(readMs);
            return s;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    private static JSONObject hello(String mode, String phoneId, String phoneName) {
        JSONObject o = Proto.obj("t", "hello", "mode", mode, "id", phoneId, "name", phoneName);
        try { o.put("v", Proto.VERSION); } catch (Exception ignored) {}
        return o;
    }

    private static Hi readHi(DataInputStream in, String ip) throws IOException {
        Proto.Frame f = Proto.readFrame(in);
        JSONObject o = f.kind == Proto.KIND_PLAIN ? Proto.parse(f.payload) : null;
        if (o == null || !"hi".equals(o.optString("t"))) throw new IOException("not a Clipboard Bridge V1 PC");
        Hi h = new Hi();
        h.ip = ip;
        h.pcId = o.optString("pc");
        h.name = o.optString("name", "PC");
        h.salt = o.optString("salt");
        h.nonce = o.optString("nonce");
        h.known = o.optBoolean("known");
        h.locked = o.optInt("locked");
        return h;
    }

    /** Reachability test: returns the PC identity. */
    public static Hi probe(String ip, int connectMs, String phoneId, String phoneName, Binder b) throws IOException {
        Socket s = open(ip, connectMs, 4000, b);
        try {
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            Proto.sendPlain(out, hello("probe", phoneId, phoneName));
            return readHi(in, ip);
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    /** Pair with PIN. Throws Refused("pin"|"locked") or IOException (network). */
    public static Paired pair(String ip, String pin, String phoneId, String phoneName, Binder b) throws IOException {
        Socket s = open(ip, 4000, 15000, b);
        try {
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            Proto.sendPlain(out, hello("pair", phoneId, phoneName));
            Hi hi = readHi(in, ip);
            if (hi.locked > 0) throw new Refused("locked", hi.locked);
            byte[] salt = Proto.b64d(hi.salt);
            if (salt == null) throw new IOException("bad salt");
            byte[] pk = Proto.pinKey(pin, salt);
            Proto.sendEnc(out, pk, Proto.obj("t", "pair", "nonce", hi.nonce, "name", phoneName));
            Proto.Frame f = Proto.readFrame(in);
            if (f.kind == Proto.KIND_PLAIN) {
                JSONObject o = Proto.parse(f.payload);
                String reason = o == null ? "pin" : o.optString("reason", "pin");
                throw new Refused(reason, o == null ? 0 : o.optInt("secs"));
            }
            JSONObject ok = Proto.decryptJson(pk, f.payload);
            if (ok == null || !"pair_ok".equals(ok.optString("t"))) throw new Refused("pin", 0);
            Paired p = new Paired();
            p.ip = ip;
            p.pcId = ok.optString("pc", hi.pcId);
            p.name = ok.optString("name", hi.name);
            p.key = Proto.b64d(ok.optString("key"));
            if (p.key == null || p.key.length != 32) throw new IOException("bad key");
            return p;
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    // ------------------- live session -------------------
    private final String ip;
    private final byte[] key;
    private final String phoneId, phoneName;
    private final Binder binder;
    private Socket sock;
    private OutputStream out;
    private DataInputStream in;
    private volatile boolean closed;
    public String pcName = "PC";
    public String pcId = "";

    public BridgeClient(String ip, byte[] key, String phoneId, String phoneName, Binder binder) {
        this.ip = ip;
        this.key = key;
        this.phoneId = phoneId;
        this.phoneName = phoneName;
        this.binder = binder;
    }

    public String ip() { return ip; }

    /** Connect + authenticate with the trusted key. Throws Refused("unknown"|"key") if the PC forgot us. */
    public void connect(int connectMs) throws IOException {
        sock = open(ip, connectMs, 15000, binder);
        out = new BufferedOutputStream(sock.getOutputStream());
        in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
        synchronized (this) {
            Proto.sendPlain(out, hello("auth", phoneId, phoneName));
        }
        Hi hi = readHi(in, ip);
        pcId = hi.pcId;
        pcName = hi.name;
        synchronized (this) {
            Proto.sendEnc(out, key, Proto.obj("t", "auth", "nonce", hi.nonce));
        }
        Proto.Frame f = Proto.readFrame(in);
        if (f.kind == Proto.KIND_PLAIN) {
            JSONObject o = Proto.parse(f.payload);
            close();
            throw new Refused(o == null ? "key" : o.optString("reason", "key"), 0);
        }
        JSONObject ok = Proto.decryptJson(key, f.payload);
        if (ok == null || !"auth_ok".equals(ok.optString("t"))) {
            close();
            throw new Refused("key", 0);
        }
        sock.setSoTimeout(60000);
    }

    /** Blocking read loop until the connection drops. */
    public void run(Listener l) {
        try {
            while (!closed) {
                Proto.Frame f = Proto.readFrame(in);
                if (f.kind == Proto.KIND_FILE) {
                    Object[] fd = Proto.decryptFile(key, f.payload);
                    if (fd == null) continue;
                    JSONObject h = (JSONObject) fd[0];
                    byte[] data = (byte[]) fd[1];
                    send(Proto.obj("t", "ack", "id", h.optString("id")));
                    if ("img".equals(h.optString("t")) && data.length > 0 && data.length <= Proto.MAX_IMAGE_BYTES) {
                        l.onImage(data, h.optString("mime", "image/png"));
                    }
                    continue;
                }
                if (f.kind != Proto.KIND_ENC) continue;
                JSONObject o = Proto.decryptJson(key, f.payload);
                if (o == null) continue;
                String t = o.optString("t");
                if ("clip".equals(t)) {
                    send(Proto.obj("t", "ack", "id", o.optString("id")));
                    String text = o.optString("text");
                    if (text.length() > 0) l.onClip(text);
                } else if ("unpaired".equals(t)) {
                    l.onUnpaired();
                    break;
                } else if ("ping".equals(t)) {
                    send(Proto.obj("t", "pong"));
                }
            }
        } catch (IOException ignored) {
        } finally {
            close();
        }
    }

    public synchronized void send(JSONObject o) throws IOException {
        if (closed || out == null) throw new IOException("closed");
        Proto.sendEnc(out, key, o);
    }

    public void sendClip(String text) throws IOException {
        send(Proto.obj("t", "clip", "id", Proto.randomId(), "text", text));
    }

    public synchronized void sendImage(byte[] data, String mime) throws IOException {
        if (closed || out == null) throw new IOException("closed");
        JSONObject h = Proto.obj("t", "img", "id", Proto.randomId(), "mime", mime);
        try { h.put("size", data.length); } catch (Exception ignored) {}
        Proto.sendFile(out, key, h, data);
    }

    public void ping() throws IOException {
        send(Proto.obj("t", "ping"));
    }

    public boolean isOpen() { return !closed && sock != null; }

    public void close() {
        closed = true;
        try { if (sock != null) sock.close(); } catch (IOException ignored) {}
    }
}
