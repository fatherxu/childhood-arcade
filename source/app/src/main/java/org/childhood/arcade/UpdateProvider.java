package org.childhood.arcade;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only access to the single verified update APK for the system installer. */
public final class UpdateProvider extends ContentProvider {
    static final String AUTHORITY = "org.childhood.arcade.updates";
    static final String KEY_PATH = "apk_path";

    @Override public boolean onCreate() { return true; }

    private File apk() {
        Context context = getContext();
        if (context == null) return null;
        String path = context.getSharedPreferences(AppUpdates.PREFS, 0).getString(KEY_PATH, "");
        if (path == null || path.length() == 0) return null;
        File file = new File(path);
        if (!file.isFile() || !file.getName().endsWith(".apk")) return null;
        try {
            String root = new File(context.getCacheDir(), "update").getCanonicalPath() + File.separator;
            if (!file.getCanonicalPath().startsWith(root)) return null;
        } catch (IOException e) {
            return null;
        }
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/update.apk".equals(uri.getPath())) throw new FileNotFoundException("Unknown update path");
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only endpoint");
        File file = apk();
        if (file == null) throw new FileNotFoundException("No verified update");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new SecurityException();
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException(); }

    @Override public int delete(Uri uri, String selection, String[] args) { throw new SecurityException(); }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new SecurityException();
    }
}
