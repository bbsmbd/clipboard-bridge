import com.clipboardbridge.core.*;

import java.io.*;
import java.util.concurrent.*;

/** Phone core <-> real Windows EXE (running under Wine): pairing + clipboard both directions. */
public class WinTest {
    static String sh(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        p.waitFor(10, TimeUnit.SECONDS);
        return new String(b.toByteArray(), "UTF-8");
    }

    static final BlockingQueue<Object[]> imgs = new LinkedBlockingQueue<Object[]>();
    static void onImg(byte[] d, String m) { imgs.add(new Object[]{d, m}); }

    static void ok(boolean c, String m) {
        System.out.println((c ? "ok   " : "FAIL ") + m);
        if (!c) System.exit(1);
    }

    public static void main(String[] a) throws Exception {
        String pin = a[0];
        String id = Proto.randomId();
        BridgeClient.Hi hi = BridgeClient.probe("127.0.0.1", 3000, id, "Wine Phone", null);
        ok(true, "probe Windows EXE: pc=" + hi.name);
        try {
            BridgeClient.pair("127.0.0.1", "000000".equals(pin) ? "111111" : "000000", id, "Wine Phone", null);
            ok(false, "wrong pin accepted");
        } catch (BridgeClient.Refused r) {
            ok("pin".equals(r.reason), "wrong PIN refused by EXE");
        }
        BridgeClient.Paired p = BridgeClient.pair("127.0.0.1", pin, id, "Wine Phone", null);
        ok(p.key.length == 32, "paired with Windows EXE");
        final BlockingQueue<String> got = new LinkedBlockingQueue<String>();
        final BridgeClient c = new BridgeClient("127.0.0.1", p.key, id, "Wine Phone", null);
        c.connect(3000);
        new Thread(new Runnable() { public void run() {
            c.run(new BridgeClient.Listener() {
                public void onClip(String t) { got.add(t); }
                public void onImage(byte[] d, String m) { onImg(d, m); }
                public void onUnpaired() {}
            });
        }}).start();
        ok(true, "session authenticated");

        String t1 = "ফোন থেকে পিসিতে ✔ line1\nline2";
        c.sendClip(t1);
        Thread.sleep(2500);
        String clip = sh("xclip", "-selection", "clipboard", "-o").replace("\r\n", "\n");
        ok(clip.equals(t1), "phone -> Windows clipboard exact (read back via X11): " + clip.replace("\n", "\\n"));

        // PC copy: put text on the clipboard from outside; EXE must push it to the phone
        String t2 = "পিসি থেকে ফোনে " + System.currentTimeMillis();
        Process xp = new ProcessBuilder("xclip", "-selection", "clipboard", "-i").start();
        xp.getOutputStream().write(t2.getBytes("UTF-8"));
        xp.getOutputStream().close();
        String r = got.poll(10, TimeUnit.SECONDS);
        ok(t2.equals(r), "Windows clipboard change -> phone: " + r);
        // ---- images through the real Windows clipboard ----
        java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(120, 80, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 80; y++) for (int x = 0; x < 120; x++) im.setRGB(x, y, (x * 2) << 16 | (y * 3) << 8 | 77);
        ByteArrayOutputStream po = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(im, "png", po);
        c.sendImage(po.toByteArray(), "image/png");
        Thread.sleep(3000);
        Process xo = new ProcessBuilder("xclip", "-selection", "clipboard", "-t", "image/png", "-o").start();
        java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(xo.getInputStream());
        ok(back != null && back.getWidth() == 120 && back.getHeight() == 80 && (back.getRGB(50, 40) & 0xFFFFFF) == (im.getRGB(50, 40) & 0xFFFFFF),
                "phone image -> Windows clipboard (pixels match)");
        java.awt.image.BufferedImage im2 = new java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 48; y++) for (int x = 0; x < 64; x++) im2.setRGB(x, y, (x * 4) << 8 | (y * 5) | 0x400000);
        ByteArrayOutputStream p2 = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(im2, "png", p2);
        Process xi = new ProcessBuilder("xclip", "-selection", "clipboard", "-t", "image/png", "-i").start();
        xi.getOutputStream().write(p2.toByteArray());
        xi.getOutputStream().close();
        Object[] gi = imgs.poll(10, TimeUnit.SECONDS);
        java.awt.image.BufferedImage rb = gi == null ? null : javax.imageio.ImageIO.read(new ByteArrayInputStream((byte[]) gi[0]));
        ok(rb != null && rb.getWidth() == 64 && rb.getHeight() == 48 && (rb.getRGB(30, 20) & 0xFFFFFF) == (im2.getRGB(30, 20) & 0xFFFFFF),
                "Windows clipboard image -> phone (pixels match)");
        c.close();
        System.out.println("WINDOWS EXE TEST PASSED");
        System.exit(0);
    }
}
