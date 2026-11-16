package com.clipboardbridge;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** In-app language switch (Bengali / English), independent of the phone's system language. */
final class LocaleHelper {
    private LocaleHelper() {}

    static Context wrap(Context base) {
        String l = new Prefs(base).lang();
        Locale loc = "en".equals(l) ? Locale.ENGLISH : new Locale("bn");
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(loc);
        return base.createConfigurationContext(cfg);
    }
}
