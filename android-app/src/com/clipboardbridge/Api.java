package com.clipboardbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.service.quicksettings.TileService;

/**
 * Every call to an API above Android 6 (API 23) lives here, behind an SDK check.
 * The build compiles all other classes against API 23 to prove they never touch newer APIs.
 */
final class Api {
    private Api() {}

    static final String CHANNEL = "bridge";

    static void createChannel(Context c, String name) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    static Notification.Builder builder(Context c) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(c, CHANNEL);
        return new Notification.Builder(c);
    }

    static void startForeground(Service s, int id, Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            s.startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            s.startForeground(id, n);
        }
    }

    static void startService(Context c, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            // Android 12+: not allowed from background right now; the service retries via boot/app start
            AppLog.add("service start blocked: " + e.getClass().getSimpleName());
        }
    }

    static boolean needsNotifPermission(Context c) {
        return Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static void stopForeground(Service s) {
        if (Build.VERSION.SDK_INT >= 24) s.stopForeground(Service.STOP_FOREGROUND_REMOVE);
        else s.stopForeground(true);
    }

    /** Android 10+: also keep a copy in the Gallery ("Pictures/Clipboard Bridge"). No permission needed. */
    static boolean saveToGallery(Context c, byte[] data, String mime) {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "clipboard_" + System.currentTimeMillis() + "." + ImageProvider.ext(mime));
            v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime);
            v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Clipboard Bridge");
            v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
            android.content.ContentResolver r = c.getContentResolver();
            android.net.Uri u = r.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return false;
            java.io.OutputStream o = r.openOutputStream(u);
            try { o.write(data); } finally { o.close(); }
            v.clear();
            v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
            r.update(u, v, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    static void tileLaunch(TileService t, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(t, 7, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            t.startActivityAndCollapse(pi);
        } else {
            t.startActivityAndCollapse(i);
        }
    }
}
