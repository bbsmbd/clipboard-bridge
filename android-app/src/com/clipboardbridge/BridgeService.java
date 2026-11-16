package com.clipboardbridge;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.clipboardbridge.core.BridgeClient;
import com.clipboardbridge.core.Discovery;
import com.clipboardbridge.core.Proto;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that keeps one encrypted TCP connection to the paired PC,
 * finds the PC again when its IP changes, and moves clipboard text both ways.
 */
public class BridgeService extends Service {
    public static final String ACTION_SEND = "com.clipboardbridge.action.SEND";
    public static final String ACTION_RECONNECT = "com.clipboardbridge.action.RECONNECT";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_FORCE = "force";

    public static final int ST_OFF = 0, ST_NOPAIR = 1, ST_NOWIFI = 2, ST_SEARCH = 3, ST_CONNECTING = 4,
            ST_CONNECTED = 5, ST_FORGOT = 6;

    private static final int NOTIF_ID = 54546;

    static volatile BridgeService instance;
    public static volatile int state = ST_OFF;
    public static volatile String connectedName = "";
    public static volatile String connectedIp = "";
    public static volatile long lastRemoteAt;

    private volatile BridgeClient client;
    private volatile boolean running;
    private final Object signal = new Object();
    private Handler main;
    private ExecutorService sendExec;
    private Prefs prefs;
    private ClipboardManager cm;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private ConnectivityManager.NetworkCallback netCb;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock mcLock;
    private volatile String lastRemote;
    private volatile String lastSent;
    private volatile String pending;
    private volatile long pendingAt;
    private int lastNotifState = -1;

    // ---------------- static entry points ----------------
    public static void start(Context c) {
        Api.startService(c, new Intent(c, BridgeService.class));
    }

    public static void reconnect(Context c) {
        BridgeService s = instance;
        if (s != null) s.dropAndRetry();
        else start(c);
    }

    /** Send text to the PC (from capture activity, share menu, accessibility...). */
    public static void sendText(Context c, String text, boolean force) {
        if (text == null || text.length() == 0) return;
        BridgeService s = instance;
        if (s != null) {
            s.queueSend(text, force);
        } else {
            Intent i = new Intent(c, BridgeService.class).setAction(ACTION_SEND)
                    .putExtra(EXTRA_TEXT, text).putExtra(EXTRA_FORCE, force);
            Api.startService(c, i);
        }
    }

    /** Send an image (PNG/JPEG/...) to the PC. */
    public static void sendImage(Context c, byte[] data, String mime, boolean force) {
        BridgeService s = instance;
        if (s != null) s.queueImage(data, mime, force);
        else AppLog.add(LocaleHelper.wrap(c).getString(R.string.log_pending_img));
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, BridgeService.class));
        state = ST_OFF;
    }

    // ---------------- lifecycle ----------------
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        main = new Handler(Looper.getMainLooper());
        prefs = new Prefs(this);
        sendExec = Executors.newSingleThreadExecutor();
        wifiLock = Net.wifiLock(this);
        mcLock = Net.multicastLock(this);
        Api.startForeground(this, NOTIF_ID, buildNotification());
        registerClipListener();
        registerNetCallback();
        running = true;
        new Thread(new Runnable() { public void run() { workerLoop(); } }, "cb-worker").start();
        new Thread(new Runnable() { public void run() { pingLoop(); } }, "cb-ping").start();
        new Thread(new Runnable() { public void run() { logWatchLoop(); } }, "cb-logwatch").start();
        AppLog.add("service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Api.startForeground(this, NOTIF_ID, buildNotification());
        if (intent != null && ACTION_SEND.equals(intent.getAction())) {
            queueSend(intent.getStringExtra(EXTRA_TEXT), intent.getBooleanExtra(EXTRA_FORCE, false));
        } else if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
            dropAndRetry();
        } else {
            wake();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        wake();
        try { if (logProc != null) logProc.destroy(); } catch (Exception ignored) {}
        BridgeClient c = client;
        if (c != null) c.close();
        try { if (clipListener != null) cm.removePrimaryClipChangedListener(clipListener); } catch (Exception ignored) {}
        try {
            if (netCb != null) ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb);
        } catch (Exception ignored) {}
        releaseLocks();
        sendExec.shutdownNow();
        instance = null;
        state = ST_OFF;
        Api.stopForeground(this);
        AppLog.add("service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void exec(Runnable r) {
        try { sendExec.execute(r); } catch (Exception ignored) {} // executor already shut down
    }

    // ---------------- worker ----------------
    private void wake() {
        synchronized (signal) { signal.notifyAll(); }
    }

    private void sleepFor(long ms) {
        synchronized (signal) {
            try { signal.wait(ms); } catch (InterruptedException ignored) {}
        }
    }

    private void dropAndRetry() {
        BridgeClient c = client;
        if (c != null) c.close();
        wake();
    }

    private void setState(int s) {
        state = s;
        if (s != lastNotifState) {
            lastNotifState = s;
            main.post(new Runnable() { public void run() { updateNotification(); } });
        }
    }

    private void workerLoop() {
        int[] fails = new int[1];
        while (running) {
            try {
                if (!workerStep(fails)) return;
            } catch (Throwable t) {
                AppLog.add("error: " + t.getClass().getSimpleName());
                sleepFor(5000);
            }
        }
    }

    /** One connect attempt + session. @return false when the service should stop. */
    private boolean workerStep(int[] failsBox) {
        int fails = failsBox[0];
        {
            if (!prefs.enabled()) {
                setState(ST_OFF);
                main.post(new Runnable() { public void run() { stopSelf(); } });
                return false;
            }
            if (!prefs.paired()) {
                setState(ST_NOPAIR);
                sleepFor(20000);
                return true;
            }
            if (!Net.hasLan(this)) {
                setState(ST_NOWIFI);
                sleepFor(15000);
                return true;
            }
            if (state != ST_FORGOT) setState(ST_CONNECTING);
            BridgeClient c = null;
            String ip = prefs.pcIp();
            int[] refused = new int[1];
            if (ip.length() > 0) c = tryConnect(ip, refused);
            if (c == null && refused[0] == 0 && running) {
                setState(ST_SEARCH);
                // full-subnet sweep only on the first tries, then every 4th try (battery)
                c = findAndConnect(refused, fails < 3 || fails % 4 == 0);
            }
            if (refused[0] != 0) {
                setState(ST_FORGOT);
                sleepFor(60000);
                return true;
            }
            if (c == null) {
                failsBox[0] = fails + 1;
                if (state != ST_FORGOT) setState(ST_SEARCH);
                sleepFor(Math.min(4000L * (fails + 1), 30000L));
                return true;
            }
            failsBox[0] = 0;
            if (!c.ip().equals(prefs.pcIp())) prefs.setPcIp(c.ip());
            client = c;
            connectedName = c.pcName;
            connectedIp = c.ip();
            acquireWifiLock();
            setState(ST_CONNECTED);
            AppLog.add(str(R.string.log_connected) + " " + c.pcName + " (" + c.ip() + ")");
            flushPending();
            c.run(new BridgeClient.Listener() {
                public void onClip(final String text) { onClipFromPc(text); }
                public void onImage(byte[] data, String mime) { onImageFromPc(data, mime); }
                public void onUnpaired() {
                    prefs.clearPair();
                    AppLog.add(str(R.string.log_unpaired));
                }
            });
            client = null;
            releaseLocks();
            if (running) {
                setState(ST_CONNECTING);
                AppLog.add(str(R.string.log_disconnected));
                sleepFor(1500);
            }
        }
        return true;
    }

    private BridgeClient tryConnect(String ip, int[] refused) {
        byte[] key = prefs.key();
        if (key == null) return null;
        BridgeClient c = new BridgeClient(ip, key, prefs.phoneId(), prefs.phoneName(), Net.tcpBinder(this));
        try {
            c.connect(2500);
            if (prefs.pcId().length() > 0 && c.pcId.length() > 0 && !prefs.pcId().equals(c.pcId)) {
                c.close();
                return null; // a different PC now has this IP
            }
            return c;
        } catch (BridgeClient.Refused r) {
            if ("unknown".equals(r.reason) || "key".equals(r.reason)) {
                refused[0] = 1;
                AppLog.add(str(R.string.log_forgot));
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private BridgeClient findAndConnect(int[] refused, boolean sweep) {
        String want = prefs.pcId();
        if (mcLock != null) try { mcLock.acquire(); } catch (Exception ignored) {}
        List<Discovery.Found> f;
        try {
            f = Discovery.udp(3000, want, Net.udpBinder(this));
        } finally {
            if (mcLock != null) try { mcLock.release(); } catch (Exception ignored) {}
        }
        for (Discovery.Found x : f) {
            if (want.equals(x.pcId)) {
                BridgeClient c = tryConnect(x.ip, refused);
                if (c != null || refused[0] != 0) return c;
            }
        }
        if (!running || !sweep) return null;
        f = Discovery.sweep(want, prefs.phoneId(), prefs.phoneName(), Net.tcpBinder(this));
        for (Discovery.Found x : f) {
            if (want.equals(x.pcId)) {
                BridgeClient c = tryConnect(x.ip, refused);
                if (c != null || refused[0] != 0) return c;
            }
        }
        return null;
    }

    private volatile Process logProc;

    static boolean canReadLogs(Context c) {
        return c.checkCallingOrSelfPermission("android.permission.READ_LOGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Android 10+: every clipboard change makes the system log
     * "Denying clipboard access to com.clipboardbridge" (our listener is refused in the background).
     * Watching for that line detects a copy in ANY app; the capture window then reads it.
     */
    private void logWatchLoop() {
        if (android.os.Build.VERSION.SDK_INT < 29) return; // 6-9: the listener reads directly
        boolean announced = false;
        while (running) {
            if (!canReadLogs(this)) {
                sleepFor(60000);
                continue;
            }
            if (!announced) {
                AppLog.add("Auto-copy watcher ON");
                announced = true;
            }
            java.io.BufferedReader r = null;
            try {
                logProc = Runtime.getRuntime().exec(new String[]{"logcat", "-T", "1", "-v", "brief", "ClipboardService:E", "*:S"});
                r = new java.io.BufferedReader(new java.io.InputStreamReader(logProc.getInputStream()));
                String me = getPackageName();
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.contains("Denying clipboard access to " + me)) {
                        if (prefs.enabled() && prefs.paired()) ClipAccessibilityService.launchCapture(this);
                    }
                }
            } catch (Exception e) {
                AppLog.add("log watcher: " + e.getClass().getSimpleName());
            } finally {
                try { if (r != null) r.close(); } catch (Exception ignored) {}
                try { if (logProc != null) logProc.destroy(); } catch (Exception ignored) {}
            }
            if (running) sleepFor(3000);
        }
    }

    private void pingLoop() {
        while (running) {
            try { Thread.sleep(20000); } catch (InterruptedException ignored) {}
            final BridgeClient c = client;
            if (c != null) {
                exec(new Runnable() {
                    public void run() {
                        try { c.ping(); } catch (IOException e) { c.close(); }
                    }
                });
            }
        }
    }

    private void acquireWifiLock() {
        try { if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire(); } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
    }

    // ---------------- clipboard ----------------
    private void onClipFromPc(final String text) {
        main.post(new Runnable() {
            public void run() {
                if (!prefs.enabled()) return;
                lastRemote = text;
                lastRemoteAt = System.currentTimeMillis();
                try {
                    cm.setPrimaryClip(ClipData.newPlainText("Clipboard Bridge", text));
                    AppLog.add(str(R.string.log_from_pc) + " " + Proto.preview(text));
                } catch (Exception e) {
                    AppLog.add("clipboard error: " + e.getClass().getSimpleName());
                }
            }
        });
    }

    private volatile String lastRemoteImg;
    private volatile String lastSentImg;

    private void onImageFromPc(final byte[] data, final String mime) {
        final String h = Proto.shaHex(data);
        main.post(new Runnable() {
            public void run() {
                if (!prefs.enabled()) return;
                try {
                    android.net.Uri u = ImageProvider.store(BridgeService.this, data, mime);
                    lastRemoteImg = h;
                    lastRemoteAt = System.currentTimeMillis();
                    ClipData cd = new ClipData("Clipboard Bridge image", new String[]{mime}, new ClipData.Item(u));
                    cm.setPrimaryClip(cd);
                    boolean saved = Api.saveToGallery(BridgeService.this, data, mime);
                    AppLog.add(str(saved ? R.string.log_img_from_pc_saved : R.string.log_img_from_pc) + " " + kb(data.length));
                } catch (Exception e) {
                    AppLog.add("image error: " + e.getClass().getSimpleName());
                }
            }
        });
    }

    static String kb(int n) {
        return n >= 1048576 ? String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0) : ((n + 1023) / 1024) + " KB";
    }

    void queueImage(final byte[] data, final String mime, final boolean force) {
        if (data == null || data.length == 0) return;
        final String h = Proto.shaHex(data);
        if (!force && (h.equals(lastRemoteImg) || h.equals(lastSentImg))) return;
        if (data.length > Proto.MAX_IMAGE_BYTES) {
            AppLog.add(str(R.string.log_img_too_big));
            return;
        }
        exec(new Runnable() {
            public void run() {
                BridgeClient c = client;
                if (c == null) {
                    AppLog.add(str(R.string.log_pending_img));
                    wake();
                    return;
                }
                try {
                    c.sendImage(data, mime == null ? "image/png" : mime);
                    lastSentImg = h;
                    lastRemoteImg = null;
                    AppLog.add(str(R.string.log_img_sent) + " " + kb(data.length));
                } catch (IOException e) {
                    c.close();
                }
            }
        });
    }

    private void registerClipListener() {
        cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipListener = new ClipboardManager.OnPrimaryClipChangedListener() {
            public void onPrimaryClipChanged() {
                // Works in the background on Android 6-9. On 10+ only while our app is in front;
                // there the accessibility service + capture activity do the job.
                try {
                    ClipContent cc = ClipContent.from(BridgeService.this, cm.getPrimaryClip());
                    if (cc != null) cc.send(BridgeService.this, false);
                } catch (Exception ignored) {
                }
            }
        };
        try { cm.addPrimaryClipChangedListener(clipListener); } catch (Exception ignored) {}
    }

    void queueSend(final String text, final boolean force) {
        if (text == null || text.length() == 0) return;
        if (!force && (text.equals(lastRemote) || text.equals(lastSent))) {
            // same text as last time: nothing to do
            return;
        }
        if (text.length() > Proto.MAX_TEXT_CHARS) {
            AppLog.add(str(R.string.log_too_big));
            return;
        }
        exec(new Runnable() {
            public void run() {
                BridgeClient c = client;
                if (c == null) {
                    pending = text;
                    pendingAt = System.currentTimeMillis();
                    AppLog.add(str(R.string.log_pending));
                    wake();
                    return;
                }
                try {
                    c.sendClip(text);
                    lastSent = text;
                    lastRemote = null;
                    AppLog.add(str(R.string.log_sent) + " " + Proto.preview(text));
                } catch (IOException e) {
                    pending = text;
                    pendingAt = System.currentTimeMillis();
                    c.close();
                }
            }
        });
    }

    private void flushPending() {
        String p = pending;
        if (p != null && System.currentTimeMillis() - pendingAt < 120000) {
            pending = null;
            queueSend(p, true);
        } else {
            pending = null;
        }
    }

    private void registerNetCallback() {
        try {
            ConnectivityManager cmgr = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkRequest req = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { wake(); }
                @Override public void onLost(Network network) {
                    BridgeClient c = client;
                    if (c != null) c.close();
                    wake();
                }
            };
            cmgr.registerNetworkCallback(req, netCb);
        } catch (Exception ignored) {
        }
    }

    // ---------------- notification ----------------
    private String str(int id) {
        return LocaleHelper.wrap(this).getString(id);
    }

    static String stateText(Context lc, int st) {
        switch (st) {
            case ST_CONNECTED: return lc.getString(R.string.st_connected, connectedName);
            case ST_NOPAIR: return lc.getString(R.string.st_nopair);
            case ST_NOWIFI: return lc.getString(R.string.st_nowifi);
            case ST_SEARCH: return lc.getString(R.string.st_search);
            case ST_CONNECTING: return lc.getString(R.string.st_connecting);
            case ST_FORGOT: return lc.getString(R.string.st_forgot);
            default: return lc.getString(R.string.st_off);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Context lc = LocaleHelper.wrap(this);
        Notification.Builder b = Api.builder(this);
        b.setSmallIcon(R.drawable.ic_notif);
        b.setContentTitle(lc.getString(R.string.app_name));
        b.setContentText(stateText(lc, state));
        b.setOngoing(true);
        b.setShowWhen(false);
        b.setPriority(Notification.PRIORITY_LOW);
        b.setContentIntent(PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        Intent cap = new Intent(this, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        b.addAction(R.drawable.ic_notif, lc.getString(R.string.notif_send),
                PendingIntent.getActivity(this, 2, cap, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) {
        }
    }
}
