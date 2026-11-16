package com.clipboardbridge;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.clipboardbridge.core.BridgeClient;
import com.clipboardbridge.core.Discovery;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Find the PC (automatic search + manual IP) and pair with its 6-digit PIN. */
public class PairActivity extends Activity {
    private Prefs prefs;
    private TextView tvMyIp, tvSearch, tvResult;
    private LinearLayout list;
    private EditText etIp, etPin;
    private Button btnSearch, btnPair, btnTest;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    private volatile boolean searching;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_pair);
        prefs = new Prefs(this);
        tvMyIp = (TextView) findViewById(R.id.tvMyIp);
        tvSearch = (TextView) findViewById(R.id.tvSearch);
        tvResult = (TextView) findViewById(R.id.tvResult);
        list = (LinearLayout) findViewById(R.id.listFound);
        etIp = (EditText) findViewById(R.id.etIp);
        etPin = (EditText) findViewById(R.id.etPin);
        btnSearch = (Button) findViewById(R.id.btnSearch);
        btnPair = (Button) findViewById(R.id.btnPairNow);
        btnTest = (Button) findViewById(R.id.btnTest);

        String ips = Net.myIps();
        tvMyIp.setText(ips.length() > 0 ? getString(R.string.my_ip, ips) : getString(R.string.my_ip_none));
        if (prefs.pcIp().length() > 0) etIp.setText(prefs.pcIp());

        btnSearch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { search(); }
        });
        btnTest.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { test(); }
        });
        btnPair.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pair(); }
        });
        search();
    }

    private void search() {
        if (searching) return;
        searching = true;
        btnSearch.setEnabled(false);
        tvSearch.setText(R.string.search_running);
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                android.net.wifi.WifiManager.MulticastLock ml = Net.multicastLock(app);
                if (ml != null) try { ml.acquire(); } catch (Exception ignored) {}
                List<Discovery.Found> f;
                try {
                    f = Discovery.udp(3000, null, Net.udpBinder(app));
                } finally {
                    if (ml != null) try { ml.release(); } catch (Exception ignored) {}
                }
                if (f.isEmpty()) {
                    h.post(new Runnable() { public void run() { tvSearch.setText(R.string.search_sweep); } });
                    f = Discovery.sweep(null, prefs.phoneId(), prefs.phoneName(), Net.tcpBinder(app));
                }
                final List<Discovery.Found> res = new ArrayList<Discovery.Found>(f);
                h.post(new Runnable() { public void run() { showFound(res); } });
            }
        }).start();
    }

    private void showFound(List<Discovery.Found> res) {
        searching = false;
        btnSearch.setEnabled(true);
        list.removeAllViews();
        if (res.isEmpty()) {
            tvSearch.setText(R.string.search_none);
            return;
        }
        tvSearch.setText(getString(R.string.search_found, res.size()));
        for (final Discovery.Found f : res) {
            Button bt = new Button(this);
            bt.setAllCaps(false);
            bt.setText(getString(R.string.found_item, f.name, f.ip));
            bt.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    etIp.setText(f.ip);
                    etPin.requestFocus();
                    showKeyboard(etPin);
                }
            });
            list.addView(bt);
        }
        if (res.size() == 1) {
            etIp.setText(res.get(0).ip);
            etPin.requestFocus();
        }
    }

    private void showKeyboard(View v) {
        try {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(v, 0);
        } catch (Exception ignored) {
        }
    }

    private String ip() {
        return etIp.getText().toString().trim();
    }

    private void setBusy(boolean b) {
        busy = b;
        btnPair.setEnabled(!b);
        btnTest.setEnabled(!b);
    }

    private void test() {
        final String ip = ip();
        if (ip.length() == 0) {
            tvResult.setText(R.string.err_no_ip);
            return;
        }
        setBusy(true);
        tvResult.setText(R.string.testing);
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    BridgeClient.Hi hi = BridgeClient.probe(ip, 4000, prefs.phoneId(), prefs.phoneName(), Net.tcpBinder(app));
                    msg = getString(R.string.test_ok, hi.name);
                } catch (Exception e) {
                    msg = errorText(e);
                }
                final String m = msg;
                h.post(new Runnable() { public void run() { setBusy(false); tvResult.setText(m); } });
            }
        }).start();
    }

    private void pair() {
        final String ip = ip();
        final String pin = etPin.getText().toString().replace(" ", "").trim();
        if (ip.length() == 0) {
            tvResult.setText(R.string.err_no_ip);
            return;
        }
        if (pin.length() != 6) {
            tvResult.setText(R.string.err_pin_len);
            return;
        }
        setBusy(true);
        tvResult.setText(R.string.pairing);
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                String msg;
                boolean ok = false;
                try {
                    BridgeClient.Paired p = BridgeClient.pair(ip, pin, prefs.phoneId(), prefs.phoneName(), Net.tcpBinder(app));
                    prefs.savePair(p.pcId, p.name, p.ip, p.key);
                    if (prefs.key() == null) throw new IOException("key store failed");
                    msg = getString(R.string.pair_ok, p.name);
                    ok = true;
                } catch (BridgeClient.Refused r) {
                    msg = "locked".equals(r.reason) ? getString(R.string.err_locked, r.secs) : getString(R.string.err_pin);
                } catch (Exception e) {
                    msg = errorText(e);
                }
                final String m = msg;
                final boolean success = ok;
                h.post(new Runnable() {
                    public void run() {
                        setBusy(false);
                        tvResult.setText(m);
                        if (success) {
                            BridgeService.reconnect(PairActivity.this);
                            Toast.makeText(PairActivity.this, m, Toast.LENGTH_LONG).show();
                            finish();
                        }
                    }
                });
            }
        }).start();
    }

    private String errorText(Exception e) {
        if (e instanceof BridgeClient.Refused) return getString(R.string.err_pin);
        if (e instanceof ConnectException) {
            String m = String.valueOf(e.getMessage()).toLowerCase();
            if (m.contains("unreach") || m.contains("no route")) return getString(R.string.err_unreach);
            return getString(R.string.err_refused);
        }
        if (e instanceof NoRouteToHostException) return getString(R.string.err_unreach);
        if (e instanceof SocketTimeoutException) return getString(R.string.err_timeout);
        if (e instanceof UnknownHostException || e instanceof IllegalArgumentException) return getString(R.string.err_bad_ip);
        String m = String.valueOf(e.getMessage());
        if (m.toLowerCase().contains("unreach")) return getString(R.string.err_unreach);
        if (m.contains("not a Clipboard Bridge")) return getString(R.string.err_old_pc);
        return getString(R.string.err_net, e.getClass().getSimpleName());
    }
}
