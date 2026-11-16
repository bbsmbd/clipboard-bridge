package com.clipboardbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Serves images received from the PC so they can sit on the clipboard (content:// URI).
 * Read-only, not exported; Android grants the pasting app temporary read access.
 */
public class ImageProvider extends ContentProvider {
    static final String AUTHORITY = "com.clipboardbridge.images";

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "clipimg");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String ext(String mime) {
        if ("image/jpeg".equals(mime)) return "jpg";
        if ("image/webp".equals(mime)) return "webp";
        if ("image/gif".equals(mime)) return "gif";
        return "png";
    }

    /** Saves the image and returns its content:// URI. Keeps only the newest few files. */
    static Uri store(Context c, byte[] data, String mime) throws IOException {
        File d = dir(c);
        File[] old = d.listFiles();
        if (old != null && old.length > 4) {
            java.util.Arrays.sort(old, new java.util.Comparator<File>() {
                public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
            });
            for (int i = 0; i < old.length - 4; i++) old[i].delete();
        }
        String name = "clipboard_" + System.currentTimeMillis() + "." + ext(mime);
        File f = new File(d, name);
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(data); } finally { o.close(); }
        return Uri.parse("content://" + AUTHORITY + "/" + name);
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
        File f = new File(dir(getContext()), name);
        if (!f.exists()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public String getType(Uri uri) {
        String n = String.valueOf(uri.getLastPathSegment()).toLowerCase();
        if (n.endsWith(".jpg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            File f = fileFor(uri);
            c.addRow(new Object[]{f.getName(), f.length()});
        } catch (FileNotFoundException ignored) {
        }
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String sel, String[] args) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String sel, String[] args) { return 0; }
}
