package com.jdkshen.aijspro.pluginclient;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.annotation.Nullable;

/**
 * Lets the Auto.js Pro VS Code extension discover the debug server over USB.
 *
 * <p>The extension runs
 * {@code adb shell content query --uri content://org.autojs.autojspro.debug.provider/debug-server}
 * and parses {@code state=(\d+)(?:, type=([^,]+), port=(\d+))?} from a single row;
 * it only proceeds when {@code state == 2}, then forwards a local port to
 * {@code tcp:<port>} (9317 for the legacy type) and connects.
 *
 * <p>The authority must literally be {@code org.autojs.autojspro.debug.provider}
 * because that string is hard-coded in the extension.
 */
public class DebugServerProvider extends ContentProvider {

    public static final String AUTHORITY = "org.autojs.autojspro.debug.provider";

    private static final String[] COLUMNS = {"state", "type", "port"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        boolean running = DevPluginServer.getInstance().isRunning();
        cursor.addRow(new Object[]{
                running ? "2" : "0",
                "legacy",
                String.valueOf(DevPluginServer.PORT)
        });
        return cursor;
    }

    @Nullable
    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
