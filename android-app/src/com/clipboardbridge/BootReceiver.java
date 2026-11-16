package com.clipboardbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the connection after reboot / app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Prefs p = new Prefs(c);
        if (p.enabled() && p.paired()) BridgeService.start(c);
    }
}
