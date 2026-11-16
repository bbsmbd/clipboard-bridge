package com.clipboardbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private Prefs prefs;
    private TextView tvStatus, tvPc, tvMyIp, tvLog, tvAccessHint;
    private Button btnSend, btnPair, btnToggle, btnAccess, btnBattery, btnLang, btnForget;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            refresh();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvPc = (TextView) findViewById(R.id.tvPc);
        tvMyIp = (TextView) findViewById(R.id.tvMyIp);
        tvLog = (TextView) findViewById(R.id.tvLog);
        tvAccessHint = (TextView) findViewById(R.id.tvAccessHint);
        btnSend = (Button) findViewById(R.id.btnSend);
        btnPair = (Button) findViewById(R.id.btnPair);
        btnToggle = (Button) findViewById(R.id.btnToggle);
        btnAccess = (Button) findViewById(R.id.btnAccess);
        btnBattery = (Button) findViewById(R.id.btnBattery);
        btnLang = (Button) findViewById(R.id.btnLang);
        btnForget = (Button) findViewById(R.id.btnForget);

        btnSend.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendClipboardNow(); }
        });
        btnPair.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, PairActivity.class)); }
        });
        btnToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean en = !prefs.enabled();
                prefs.setEnabled(en);
                if (en) BridgeService.start(MainActivity.this);
                else BridgeService.stop(MainActivity.this);
                refresh();
            }
        });
        btnAccess.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        });
        btnBattery.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askBattery(); }
        });
        btnLang.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.setLang("en".equals(prefs.lang()) ? "bn" : "en");
                recreate();
            }
        });
        btnForget.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.clearPair();
                BridgeService.reconnect(MainActivity.this);
                refresh();
            }
        });

        if (Api.needsNotifPermission(this)) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        if (prefs.enabled() && prefs.paired()) BridgeService.start(this);
        if (!prefs.paired() && b == null) {
            startActivity(new Intent(this, PairActivity.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.enabled() && prefs.paired() && BridgeService.instance == null) BridgeService.start(this);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private void askBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void sendClipboardNow() {
        if (!prefs.paired()) {
            Toast.makeText(this, R.string.toast_not_paired, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipContent cc = null;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cc = ClipContent.from(this, cm.getPrimaryClip());
        } catch (Exception ignored) {
        }
        if (cc == null) {
            Toast.makeText(this, R.string.toast_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        cc.send(this, true);
        Toast.makeText(this, BridgeService.state == BridgeService.ST_CONNECTED
                ? R.string.toast_sent : R.string.toast_pending, Toast.LENGTH_SHORT).show();
    }

    static boolean accessibilityOn(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String me = new ComponentName(c, ClipAccessibilityService.class).flattenToString();
            String meShort = new ComponentName(c, ClipAccessibilityService.class).flattenToShortString();
            for (String part : s.split(":")) {
                if (part.equalsIgnoreCase(me) || part.equalsIgnoreCase(meShort)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void refresh() {
        int st = prefs.enabled() ? BridgeService.state : BridgeService.ST_OFF;
        if (prefs.enabled() && !prefs.paired()) st = BridgeService.ST_NOPAIR;
        tvStatus.setText(BridgeService.stateText(this, st));
        int color = st == BridgeService.ST_CONNECTED ? 0xFF1B8C4B
                : (st == BridgeService.ST_OFF ? 0xFF8A8A8A : 0xFFC23B3B);
        tvStatus.setTextColor(color);
        if (prefs.paired()) {
            String ip = BridgeService.state == BridgeService.ST_CONNECTED ? BridgeService.connectedIp : prefs.pcIp();
            tvPc.setText(getString(R.string.paired_pc, prefs.pcName(), ip));
            btnPair.setText(R.string.btn_pair_change);
            btnForget.setVisibility(View.VISIBLE);
        } else {
            tvPc.setText(R.string.no_pc);
            btnPair.setText(R.string.btn_pair);
            btnForget.setVisibility(View.GONE);
        }
        String ips = Net.myIps();
        tvMyIp.setText(ips.length() > 0 ? getString(R.string.my_ip, ips) : getString(R.string.my_ip_none));
        btnToggle.setText(prefs.enabled() ? R.string.btn_off : R.string.btn_on);

        boolean needAccess = Build.VERSION.SDK_INT >= 29;
        boolean accOn = accessibilityOn(this);
        btnAccess.setVisibility(needAccess ? View.VISIBLE : View.GONE);
        tvAccessHint.setVisibility(needAccess && !accOn ? View.VISIBLE : View.GONE);
        btnAccess.setText(accOn ? R.string.btn_access_on : R.string.btn_access);

        boolean batteryOk = true;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            batteryOk = pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception ignored) {
        }
        btnBattery.setText(batteryOk ? R.string.btn_battery_ok : R.string.btn_battery);
        btnLang.setText("en".equals(prefs.lang()) ? "বাংলা" : "English");
        tvLog.setText(AppLog.text());
    }
}
