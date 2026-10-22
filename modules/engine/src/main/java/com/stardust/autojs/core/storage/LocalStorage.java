package com.stardust.autojs.core.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

/**
 * Created by Stardust on 2017/12/3.
 */

public class LocalStorage {

    private static final String NAME_PREFIX = "autojs.localstorage.";
    private final SharedPreferences mSharedPreferences;
    private final Context mContext;
    private final String mName;
    private final Uri mProvider;

    public LocalStorage(Context context, String name) {
        mContext = context;
        mName = name;
        mSharedPreferences = context.getSharedPreferences(NAME_PREFIX + name, Context.MODE_PRIVATE);
        String authority = context.getPackageName() + ".node24.storage";
        mProvider = context.getPackageManager().resolveContentProvider(authority, 0) == null
                ? null : Uri.parse("content://" + authority);
    }

    private Bundle remote(String action, String key, String type, Object value) {
        Bundle args = new Bundle();
        args.putString("namespace", "legacy");
        args.putString("key", key);
        args.putString("type", type);
        String field = "get".equals(action) ? "default" : "value";
        if (value instanceof Long) args.putLong(field, (Long) value);
        else if (value instanceof Boolean) args.putBoolean(field, (Boolean) value);
        else args.putString(field, (String) value);
        Bundle result = mContext.getContentResolver().call(mProvider, action, mName, args);
        if (result == null) throw new IllegalStateException("Shared storage provider unavailable");
        return result;
    }

    public LocalStorage put(String key, String value) {
        if (mProvider != null) { remote("put", key, "string", value); return this; }
        mSharedPreferences.edit()
                .putString(key, value)
                .apply();
        return this;
    }

    public LocalStorage put(String key, long value) {
        if (mProvider != null) { remote("put", key, "number", value); return this; }
        mSharedPreferences.edit()
                .putLong(key, value)
                .apply();
        return this;
    }

    public LocalStorage put(String key, boolean value) {
        if (mProvider != null) { remote("put", key, "boolean", value); return this; }
        mSharedPreferences.edit()
                .putBoolean(key, value)
                .apply();
        return this;
    }

    public long getNumber(String key, long defaultValue) {
        if (mProvider != null) return remote("get", key, "number", defaultValue).getLong("value");
        return mSharedPreferences.getLong(key, defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        if (mProvider != null) return remote("get", key, "boolean", defaultValue).getBoolean("value");
        return mSharedPreferences.getBoolean(key, defaultValue);
    }

    public String getString(String key, String defaultValue) {
        if (mProvider != null) return remote("get", key, "string", defaultValue).getString("value");
        return mSharedPreferences.getString(key, defaultValue);
    }

    public long getNumber(String key) {
        return getNumber(key, 0);
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public void remove(String key) {
        if (mProvider != null) { remote("remove", key, "string", null); return; }
        mSharedPreferences.edit().remove(key).apply();
    }

    public boolean contains(String key) {
        if (mProvider != null) return remote("contains", key, "string", null).getBoolean("value");
        return mSharedPreferences.contains(key);
    }

    public void clear() {
        if (mProvider != null) { remote("clear", "", "string", null); return; }
        mSharedPreferences.edit().clear().apply();
    }
}
