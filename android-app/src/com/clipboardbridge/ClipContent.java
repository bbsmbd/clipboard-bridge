package com.clipboardbridge;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.net.Uri;

import com.clipboardbridge.core.Proto;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** What is on the clipboard: text or an image. */
final class ClipContent {
    String text;
    Uri imageUri;
    String mime;

    /** Must be called while the app may read the clipboard (focused window / foreground). */
    static ClipContent from(Context c, ClipData d) {
        if (d == null || d.getItemCount() == 0) return null;
        ClipData.Item item = d.getItemAt(0);
        ClipDescription desc = d.getDescription();
        ClipContent cc = new ClipContent();
        if (item.getText() == null && item.getUri() != null) {
            String m = null;
            if (desc != null) {
                for (int i = 0; i < desc.getMimeTypeCount(); i++) {
                    if (desc.getMimeType(i).startsWith("image/")) { m = desc.getMimeType(i); break; }
                }
            }
            if (m == null) {
                try { m = c.getContentResolver().getType(item.getUri()); } catch (Exception ignored) {}
            }
            if (m != null && m.startsWith("image/")) {
                cc.imageUri = item.getUri();
                cc.mime = m;
                return cc;
            }
        }
        CharSequence t = item.coerceToText(c);
        if (t == null || t.toString().trim().length() == 0) return null;
        cc.text = t.toString();
        return cc;
    }

    /** Reads image bytes (max 20 MB); null if too large or unreadable. Call off the main thread when possible. */
    static byte[] readImage(Context c, Uri uri) {
        InputStream in = null;
        try {
            in = c.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                if (o.size() > Proto.MAX_IMAGE_BYTES) return null;
            }
            return o.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
    }

    /** Text goes straight to the service; an image is read on a worker thread, then sent. */
    void send(final Context c, final boolean force) {
        if (text != null) {
            BridgeService.sendText(c, text, force);
            return;
        }
        final Context app = c.getApplicationContext();
        final Uri u = imageUri;
        final String m = mime;
        new Thread(new Runnable() {
            public void run() {
                byte[] b = readImage(app, u);
                if (b == null) {
                    AppLog.add("image too large or unreadable");
                    return;
                }
                BridgeService.sendImage(app, b, m, force);
            }
        }).start();
    }
}
