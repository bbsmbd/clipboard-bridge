package com.clipboardbridge;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Api.createChannel(this, LocaleHelper.wrap(this).getString(R.string.channel_name));
        Prefs p = new Prefs(this);
        if (p.enabled() && p.paired()) BridgeService.start(this);
    }
}
