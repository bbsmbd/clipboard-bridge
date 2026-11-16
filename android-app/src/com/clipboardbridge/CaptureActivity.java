package com.clipboardbridge;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Invisible activity: Android 10+ lets only the focused app read the clipboard, so this window
 * takes focus for a moment, reads the copied text, hands it to BridgeService and closes.
 */
public class CaptureActivity extends Activity {
    static final String EXTRA_AUTO = "auto";
    static volatile long lastOpenedAt;
    private boolean done;
    private boolean auto;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        overridePendingTransition(0, 0);
        auto = getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO, false);
        lastOpenedAt = System.currentTimeMillis();
        h.postDelayed(new Runnable() {
            public void run() { if (!done) { done = true; AppLog.add("Capture: no window focus"); close(); } }
        }, 2500);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        lastOpenedAt = System.currentTimeMillis();
        auto = intent != null && intent.getBooleanExtra(EXTRA_AUTO, false);
        done = false;
        if (hasWindowFocus()) capture();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) capture();
    }

    private void capture() {
        if (done) return;
        done = true;
        ClipContent cc = null;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cc = ClipContent.from(this, cm.getPrimaryClip());
        } catch (Exception ignored) {
        }
        if (cc == null) AppLog.add("Capture: clipboard unreadable/empty");
        Prefs p = new Prefs(this);
        if (!p.paired()) {
            Toast.makeText(this, R.string.toast_not_paired, Toast.LENGTH_SHORT).show();
        } else if (cc == null) {
            if (!auto) Toast.makeText(this, R.string.toast_empty, Toast.LENGTH_SHORT).show();
        } else {
            cc.send(this, !auto);
            if (!auto) {
                Toast.makeText(this, BridgeService.state == BridgeService.ST_CONNECTED
                        ? R.string.toast_sent : R.string.toast_pending, Toast.LENGTH_SHORT).show();
            }
        }
        close();
    }

    private void close() {
        finish();
        overridePendingTransition(0, 0);
    }
}
