import com.clipboardbridge.core.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** End-to-end test: real Rust PC app (headless build) <-> Java phone core. */
public class E2E {
    static Process pc;
    static final BlockingQueue<String> pcOut = new LinkedBlockingQueue<String>();
    static PrintWriter pcIn;
    static int pass = 0;
    static final BlockingQueue<Object[]> imgs = new LinkedBlockingQueue<Object[]>();
    static void onImg(byte[] d, String m) { imgs.add(new Object[]{d, m}); }

    static byte[] makePng(int w, int h, long seed) throws Exception {
        java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.util.Random r = new java.util.Random(seed);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) im.setRGB(x, y, 0xFF000000 | r.nextInt(0xFFFFFF));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(im, "png", o);
        return o.toByteArray();
    }

    static void check(boolean c, String what) {
        if (!c) {
            System.out.println("FAIL: " + what);
            pc.destroy();
            System.exit(1);
        }
        pass++;
        System.out.println("ok   " + what);
    }

    static String waitFor(String prefix, int ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            String l = pcOut.poll(200, TimeUnit.MILLISECONDS);
            if (l != null && l.startsWith(prefix)) return l;
        }
        return null;
    }

    public static void main(String[] a) throws Exception {
        String exe = a[0];
        File home = new File(a[1]);
        ProcessBuilder pb = new ProcessBuilder(exe);
        pb.environment().put("CB_HOME", home.getAbsolutePath());
        pb.redirectErrorStream(true);
        pc = pb.start();
        pcIn = new PrintWriter(new OutputStreamWriter(pc.getOutputStream(), "UTF-8"), true);
        final BufferedReader r = new BufferedReader(new InputStreamReader(pc.getInputStream(), "UTF-8"));
        new Thread(new Runnable() { public void run() {
            try { String l; while ((l = r.readLine()) != null) { pcOut.add(l); if (System.getenv("VERBOSE") != null) System.out.println("   PC> " + l); } } catch (IOException e) {}
        }}).start();
        String pinLine = waitFor("PIN ", 5000);
        check(pinLine != null, "PC app started");
        String pin = pinLine.substring(4).trim();
        check(waitFor("LOG TCP 54546 ready", 5000) != null, "PC TCP server listening");

        String phoneId = Proto.randomId();
        String phone = "টেস্ট ফোন";

        BridgeClient.Hi hi = BridgeClient.probe("127.0.0.1", 2000, phoneId, phone, null);
        check(hi.pcId.length() > 0 && hi.salt.length() > 0, "probe gets PC identity: " + hi.name);
        check(!hi.known, "new phone is not known yet");

        String wrong = pin.equals("111111") ? "222222" : "111111";
        try {
            BridgeClient.pair("127.0.0.1", wrong, phoneId, phone, null);
            check(false, "wrong PIN must fail");
        } catch (BridgeClient.Refused e) {
            check("pin".equals(e.reason), "wrong PIN refused with reason=pin");
        }

        BridgeClient.Paired p = BridgeClient.pair("127.0.0.1", pin, phoneId, phone, null);
        check(p.key.length == 32 && p.pcId.equals(hi.pcId), "correct PIN pairs, got 32-byte key");
        check(waitFor("PAIRED", 3000) != null, "PC reports paired phone");

        // session
        final BlockingQueue<String> phoneClips = new LinkedBlockingQueue<String>();
        final BridgeClient c = new BridgeClient("127.0.0.1", p.key, phoneId, phone, null);
        c.connect(2000);
        new Thread(new Runnable() { public void run() {
            c.run(new BridgeClient.Listener() {
                public void onClip(String t) { phoneClips.add(t); }
                public void onImage(byte[] d, String m) { onImg(d, m); }
                public void onUnpaired() { phoneClips.add("<<UNPAIRED>>"); }
            });
        }}).start();
        check(waitFor("SESSIONS", 3000) != null, "authenticated session established");

        String t1 = "হ্যালো পিসি! line2\nশেষ 🙂";
        c.sendClip(t1);
        String got = waitFor("CLIP ", 3000);
        check(got != null && got.substring(5).replace("\\n", "\n").equals(t1), "phone -> PC Bengali+emoji text exact");

        pcIn.println("send PC theke phone e \\nদ্বিতীয় লাইন");
        String g2 = phoneClips.poll(3, TimeUnit.SECONDS);
        check("PC theke phone e \nদ্বিতীয় লাইন".equals(g2), "PC -> phone text exact");

        StringBuilder big = new StringBuilder();
        while (big.length() < 400000) big.append("বাংলা লম্বা লেখা 0123456789 ");
        pcIn.println("send " + big);
        String g3 = phoneClips.poll(5, TimeUnit.SECONDS);
        check(big.toString().equals(g3), "PC -> phone 400k-char text exact");
        c.sendClip(big.toString());
        String g4 = waitFor("CLIP ", 5000);
        check(g4 != null && g4.substring(5).equals(big.toString()), "phone -> PC 400k-char text exact");

        // ---- images ----
        byte[] small = makePng(320, 200, 1);
        c.sendImage(small, "image/png");
        String gi = waitFor("IMG ", 5000);
        check(gi != null && gi.equals("IMG image/png " + small.length + " " + Proto.shaHex(small)), "phone -> PC image exact (" + small.length / 1024 + " KB)");
        byte[] bigImg = makePng(1600, 1200, 2);
        c.sendImage(bigImg, "image/png");
        String gb = waitFor("IMG ", 10000);
        check(gb != null && gb.equals("IMG image/png " + bigImg.length + " " + Proto.shaHex(bigImg)), "phone -> PC large image exact (" + bigImg.length / 1024 + " KB)");
        File pf = File.createTempFile("cbimg", ".png");
        FileOutputStream fo = new FileOutputStream(pf); fo.write(bigImg); fo.close();
        pcIn.println("sendimg " + pf.getAbsolutePath());
        Object[] got2 = imgs.poll(10, TimeUnit.SECONDS);
        check(got2 != null && Proto.shaHex((byte[]) got2[0]).equals(Proto.shaHex(bigImg)) && "image/png".equals(got2[1]), "PC -> phone large image exact");
        c.sendClip("text after image");
        String ta = waitFor("CLIP ", 3000);
        check("CLIP text after image".equals(ta), "text still works after images");

        c.ping();
        Thread.sleep(300);
        check(c.isOpen(), "ping keeps session open");

        // reconnect replaces old session
        final BridgeClient c2 = new BridgeClient("127.0.0.1", p.key, phoneId, phone, null);
        c2.connect(2000);
        new Thread(new Runnable() { public void run() {
            c2.run(new BridgeClient.Listener() {
                public void onClip(String t) { phoneClips.add("c2:" + t); }
                public void onImage(byte[] d, String m) { onImg(d, m); }
                public void onUnpaired() {}
            });
        }}).start();
        Thread.sleep(500);
        pcOut.clear();
        pcIn.println("count");
        String cnt = waitFor("COUNT", 2000);
        check("COUNT 1".equals(cnt), "reconnect replaces old session (count=1)");
        pcIn.println("send after-reconnect");
        String g5 = null;
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) { String x = phoneClips.poll(300, TimeUnit.MILLISECONDS); if (x != null && x.startsWith("c2:")) { g5 = x; break; } }
        check("c2:after-reconnect".equals(g5), "new session receives clips");

        // wrong key -> refused
        byte[] bad = Proto.random(32);
        try {
            new BridgeClient("127.0.0.1", bad, phoneId, phone, null).connect(2000);
            check(false, "bad key must fail");
        } catch (BridgeClient.Refused e) {
            check("key".equals(e.reason), "wrong trusted key refused (reason=key)");
        }
        try {
            new BridgeClient("127.0.0.1", p.key, "otherphone", phone, null).connect(2000);
            check(false, "unknown phone must fail");
        } catch (BridgeClient.Refused e) {
            check("unknown".equals(e.reason), "unknown phone refused (reason=unknown)");
        }

        // UDP query/response (direct to 127.0.0.1)
        DatagramSocket ds = new DatagramSocket();
        ds.setSoTimeout(3000);
        byte[] q = "CBV1?".getBytes("UTF-8");
        ds.send(new DatagramPacket(q, q.length, InetAddress.getByName("127.0.0.1"), Proto.UDP_PORT));
        DatagramPacket dp = new DatagramPacket(new byte[512], 512);
        ds.receive(dp);
        String beacon = new String(dp.getData(), 0, dp.getLength(), "UTF-8");
        check(beacon.startsWith("CBV1|" + hi.pcId + "|54546|"), "UDP query answered: " + beacon);
        ds.close();

        // probe over the real LAN interface
        List<Discovery.LocalNet> nets = Discovery.localNets();
        if (!nets.isEmpty()) {
            String ip = nets.get(0).addr.getHostAddress();
            BridgeClient.Hi h2 = BridgeClient.probe(ip, 2000, phoneId, phone, null);
            check(h2.pcId.equals(hi.pcId), "probe via LAN address " + ip + " (" + nets.get(0).ifName + "/" + nets.get(0).prefix + ")");
        }

        // lockout after 5 wrong PINs
        String id3 = Proto.randomId();
        int refusedPin = 0;
        String lastReason = "";
        for (int i = 0; i < 6; i++) {
            try { BridgeClient.pair("127.0.0.1", wrong, id3, "x", null); }
            catch (BridgeClient.Refused e) { lastReason = e.reason; if ("pin".equals(e.reason)) refusedPin++; }
        }
        check(refusedPin == 5 && "locked".equals(lastReason), "brute-force lockout after 5 wrong PINs");

        // persistence: restart PC app, same PIN and phone still trusted
        c2.close();
        pcIn.println("quit");
        pc.waitFor(5, TimeUnit.SECONDS);
        pc.destroy();
        Thread.sleep(500);
        pcOut.clear();
        pc = pb.start();
        pcIn = new PrintWriter(new OutputStreamWriter(pc.getOutputStream(), "UTF-8"), true);
        final BufferedReader r2 = new BufferedReader(new InputStreamReader(pc.getInputStream(), "UTF-8"));
        new Thread(new Runnable() { public void run() {
            try { String l; while ((l = r2.readLine()) != null) pcOut.add(l); } catch (IOException e) {}
        }}).start();
        String pin2 = waitFor("PIN ", 5000);
        check(pin2 != null && pin2.substring(4).trim().equals(pin), "PIN stays the same after restart");
        waitFor("LOG TCP", 5000);
        BridgeClient c3 = new BridgeClient("127.0.0.1", p.key, phoneId, phone, null);
        c3.connect(2000);
        check(c3.isOpen(), "paired phone reconnects after PC restart without PIN");
        c3.close();
        pcIn.println("quit");
        pc.destroy();
        System.out.println("ALL " + pass + " CHECKS PASSED");
        System.exit(0);
    }
}
