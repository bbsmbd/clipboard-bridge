package com.clipboardbridge;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;
import java.util.Locale;

/**
 * Android 10+ blocks clipboard reading in the background. This service only watches for a
 * "Copy" action (Copy button click, "Copied" toast, system clipboard popup) and then opens the
 * invisible CaptureActivity, which is allowed to read the clipboard and sends it to the PC.
 * It does not read screen content.
 */
public class ClipAccessibilityService extends AccessibilityService {
    static volatile boolean running;
    static volatile ClipAccessibilityService self;
    private static volatile long lastLaunch;

    /** Opens the invisible capture window (allowed from background while accessibility is on). */
    static void launchCapture(Context fallback) {
        long now = System.currentTimeMillis();
        if (now - lastLaunch < 700) return;
        if (now - BridgeService.lastRemoteAt < 2500) return; // our own write from the PC
        lastLaunch = now;
        Context c = self != null ? self : fallback;
        try {
            Intent i = new Intent(c, CaptureActivity.class)
                    .putExtra(CaptureActivity.EXTRA_AUTO, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            c.startActivity(i);
        } catch (Exception e) {
            AppLog.add("capture launch failed: " + e.getClass().getSimpleName());
        }
    }
    private final Handler h = new Handler(Looper.getMainLooper());
    private long last;
    private long connectedAt;
    private static final boolean DIAG = false;
    private long lastNotifAt;
    private long lastUserTouchAt;
    private String lastDiag = "";
    static volatile String lastClick = "";

    @Override
    protected void onServiceConnected() {
        running = true;
        self = this;
        connectedAt = System.currentTimeMillis();
        AppLog.add("Accessibility ON");
        Prefs p = new Prefs(this);
        if (p.enabled() && p.paired()) BridgeService.start(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || Build.VERSION.SDK_INT < 29) return; // Android 6-9: the service listener works directly
        CharSequence pkgCs = e.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        if (pkg.equals(getPackageName())) return;
        String text = joined(e.getText(), e.getContentDescription());
        boolean copy = false;
        int type = e.getEventType();
        diag(type, pkg, text, e.getClassName());
        String why = null;
        long nowT = System.currentTimeMillis();
        if (type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED || type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            lastUserTouchAt = nowT;
        }
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && e.getParcelableData() instanceof Notification) {
            lastNotifAt = nowT;
        }
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            copy = isCopyWord(text);
            if (copy) why = "copy button";
            if (!copy && text.length() > 0 && text.length() <= 40 && type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                lastClick = pkg + ": " + text;
            }
        } else if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            // app toasts like "Copied to clipboard". System toasts ("... pasted from your clipboard")
            // are ignored, otherwise reading the clipboard would trigger itself again and again.
            copy = !(e.getParcelableData() instanceof Notification) && !"android".equals(pkg)
                    && !"com.android.systemui".equals(pkg) && mentionsCopied(text);
            if (copy) why = "toast";
            // Android 12+: after every copy, Google "App services" reads the clipboard and the system
            // shows "<app> pasted from your clipboard". Any such toast except our own = clipboard changed.
            if (!copy && !(e.getParcelableData() instanceof Notification)
                    && ("android".equals(pkg) || "com.android.systemui".equals(pkg))
                    && (text.contains("pasted from your clipboard") || text.contains("pasted from clipboard")
                        || text.contains("ক্লিপবোর্ড থেকে পেস্ট"))
                    && !text.contains("clipboard bridge")) {
                copy = true;
                why = "paste toast";
            }
        } else if (type == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
            copy = mentionsCopied(text);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cls = e.getClassName();
            String c = cls == null ? "" : cls.toString().toLowerCase(Locale.US);
            boolean sys = pkg.equals("com.android.systemui") || pkg.startsWith("com.oplus") || pkg.startsWith("com.coloros")
                    || pkg.startsWith("com.oneplus") || pkg.startsWith("com.miui") || pkg.startsWith("com.samsung");
            copy = sys && (c.contains("clipboard") || mentionsCopied(text)
                    || (text.contains("clipboard") && !text.contains("paste")));
            if (copy) why = "clipboard popup";
            // Android 13+ (OnePlus/ColorOS, Pixel...): after Copy/Cut the system shows its clipboard
            // popup as an untitled SystemUI FrameLayout window. Notifications/shade carry text, so
            // an EMPTY SystemUI FrameLayout not caused by a notification = copy happened.
            if (!copy && "com.android.systemui".equals(pkg) && text.length() == 0
                    && (c.endsWith("framelayout") || c.equals("android.view.view") || c.endsWith(".view"))
                    && nowT - lastNotifAt > 3000) {
                copy = true;
                why = "system popup";
            }
        }
        if (!copy) return;
        long now = System.currentTimeMillis();
        if (now - last < 1200) return;
        // text that just arrived from the PC also makes Android show its clipboard popup: skip it
        if (now - BridgeService.lastRemoteAt < 2500) return;
        AppLog.add("Copy detected [" + why + "]");
        last = now;
        Prefs p = new Prefs(this);
        if (!p.enabled() || !p.paired()) return;
        final long launchedAt = System.currentTimeMillis();
        h.postDelayed(new Runnable() {
            public void run() { launchCapture(ClipAccessibilityService.this); }
        }, 60);
        // backup: if the capture window did not open within 700 ms, use the overlay method
        h.postDelayed(new Runnable() {
            public void run() {
                if (CaptureActivity.lastOpenedAt < launchedAt) captureViaOverlay(false);
            }
        }, 760);
    }

    /** First 20 minutes after Accessibility is turned on: log candidate events to find the phone's Copy signal. */
    private void diag(int type, String pkg, String text, CharSequence cls) {
        if (!DIAG || System.currentTimeMillis() - connectedAt > 20 * 60 * 1000L) return;
        String t;
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) t = "CLICK";
        else if (type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) t = "LONG";
        else if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) t = "TOAST";
        else if (type == AccessibilityEvent.TYPE_ANNOUNCEMENT) t = "SAY";
        else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) t = "WIN";
        else return;
        String c = cls == null ? "" : cls.toString();
        int dot = c.lastIndexOf('.');
        if (dot >= 0) c = c.substring(dot + 1);
        String p = pkg.length() > 22 ? pkg.substring(pkg.length() - 22) : pkg;
        String tx = text.length() > 30 ? text.substring(0, 30) : text;
        String line = "EV " + t + " " + p + " '" + tx + "' " + c;
        if (line.equals(lastDiag)) return;
        lastDiag = line;
        AppLog.add(line);
    }

    /**
     * Android 10+ lets only the focused window's app read the clipboard. Android 14+ also blocks
     * background apps from opening activities, so we add a 1x1 focusable ACCESSIBILITY overlay
     * (allowed for accessibility services, no extra permission), read the clipboard when it gets
     * focus, and remove it immediately. Falls back to the invisible activity.
     */
    private void captureViaOverlay(final boolean fallbackToActivity) {
        final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        final boolean[] done = new boolean[1];
        final View v = new View(this) {
            @Override
            public void onWindowFocusChanged(boolean hasFocus) {
                super.onWindowFocusChanged(hasFocus);
                if (!hasFocus || done[0]) return;
                done[0] = true;
                ClipContent cc = null;
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cc = ClipContent.from(getContext(), cm.getPrimaryClip());
                } catch (Exception ignored) {
                }
                if (cc == null) AppLog.add("Capture(overlay): clipboard unreadable/empty");
                removeSafely(wm, this);
                if (cc != null) cc.send(getContext(), false);
            }
        };
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.alpha = 0f;
        try {
            wm.addView(v, lp);
        } catch (Exception e) {
            AppLog.add("overlay failed (" + e.getClass().getSimpleName() + ")");
            if (fallbackToActivity) launchCapture(this);
            return;
        }
        h.postDelayed(new Runnable() {
            public void run() {
                if (done[0]) return;
                done[0] = true;
                removeSafely(wm, v);
                AppLog.add("Capture(overlay): no focus");
                if (fallbackToActivity) launchCapture(ClipAccessibilityService.this);
            }
        }, 1200);
    }

    private static void removeSafely(WindowManager wm, View v) {
        try { wm.removeView(v); } catch (Exception ignored) {}
    }

    private static String joined(List<CharSequence> l, CharSequence desc) {
        StringBuilder sb = new StringBuilder();
        if (l != null) for (CharSequence c : l) if (c != null) sb.append(c).append(' ');
        if (desc != null) sb.append(desc);
        return sb.toString().trim().toLowerCase(Locale.ROOT);
    }

    static boolean isCopyWord(String t) {
        if (t.length() == 0 || t.length() > 40) return false;
        if (t.contains("copyright")) return false;
        return t.equals("copy") || t.startsWith("copy ") || t.endsWith(" copy") || t.contains("copy to clipboard")
                || t.contains("copy link") || t.contains("copy text") || t.contains("কপি") || t.contains("অনুলিপি")
                || t.equals("copiar") || t.equals("copier") || t.equals("kopieren") || t.equals("копировать")
                || t.equals("نسخ") || t.equals("कॉपी करें") || t.equals("कॉपी") || t.equals("salin");
    }

    static boolean mentionsCopied(String t) {
        if (t.contains("paste") || t.contains("পেস্ট") || t.length() > 80) return false;
        return t.contains("copied") || t.contains("text cut") || t.contains("cut to clipboard") || t.equals("cut")
                || t.contains("copy") || t.contains("কাট") || t.contains("কপি করা") || t.contains("কপি হয়েছে")
                || t.contains("कॉपी किया") || t.contains("disalin") || t.contains("copiado") || t.contains("copié");
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        running = false;
        self = null;
        super.onDestroy();
    }
}
