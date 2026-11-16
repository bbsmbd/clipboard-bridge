package com.clipboardbridge;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Small in-memory log shown on the main screen (never written to disk). */
public final class AppLog {
    private static final List<String> LINES = new ArrayList<String>();
    private static final SimpleDateFormat F = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private AppLog() {}

    public static synchronized void add(String m) {
        LINES.add(F.format(new Date()) + "  " + m);
        while (LINES.size() > 120) LINES.remove(0);
    }

    public static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (int i = LINES.size() - 1; i >= 0; i--) sb.append(LINES.get(i)).append('\n');
        return sb.toString();
    }
}
