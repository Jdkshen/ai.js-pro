package org.autojs.source.engine.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Single-process owner for Node storage shared by the three runtime pool slots. */
public final class Node24StorageProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String name, Bundle args) {
        if (args == null) throw new IllegalArgumentException("storage arguments missing");
        SharedPreferences prefs = getContext().getSharedPreferences(
                "node24.storage." + (name == null ? "default" : name), Context.MODE_PRIVATE);
        String key = args.getString("key", "");
        Bundle result = new Bundle();
        switch (method) {
            case "get":
                result.putString("value", prefs.getString(key, null));
                return result;
            case "put":
                requireCommit(prefs.edit().putString(key, args.getString("value", "")).commit());
                break;
            case "remove": requireCommit(prefs.edit().remove(key).commit()); break;
            case "clear": requireCommit(prefs.edit().clear().commit()); break;
            default: throw new IllegalArgumentException("Unsupported storage action: " + method);
        }
        result.putBoolean("ok", true);
        return result;
    }

    private static void requireCommit(boolean success) {
        if (!success) throw new IllegalStateException("Node storage write failed");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
