package org.autojs.source.engine.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Single-process owner for storage shared by script and Node pool processes. */
public final class Node24StorageProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String name, Bundle args) {
        if (args == null) throw new IllegalArgumentException("storage arguments missing");
        boolean legacy = "legacy".equals(args.getString("namespace"));
        SharedPreferences prefs = getContext().getSharedPreferences(
                (legacy ? "autojs.localstorage." : "node24.storage.")
                        + (name == null ? "default" : name), Context.MODE_PRIVATE);
        String key = args.getString("key", "");
        Bundle result = new Bundle();
        String type = args.getString("type", "string");
        switch (method) {
            case "get":
                if ("number".equals(type)) result.putLong("value", prefs.getLong(key, args.getLong("default")));
                else if ("boolean".equals(type)) result.putBoolean("value", prefs.getBoolean(key, args.getBoolean("default")));
                else result.putString("value", prefs.getString(key, args.getString("default")));
                return result;
            case "put":
                SharedPreferences.Editor editor = prefs.edit();
                if ("number".equals(type)) editor.putLong(key, args.getLong("value"));
                else if ("boolean".equals(type)) editor.putBoolean(key, args.getBoolean("value"));
                else editor.putString(key, args.getString("value"));
                requireCommit(editor.commit());
                break;
            case "contains": result.putBoolean("value", prefs.contains(key)); return result;
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
