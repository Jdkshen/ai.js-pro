package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.stardust.autojs.core.database.Database;
import com.stardust.autojs.runtime.ScriptRuntime;

import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/** Pro-compatible SQLite entry point; integer version overloads remain as extensions. */
public class Sqlite {
    private final Context mContext;
    private final Files mFiles;
    private final ScriptRuntime mRuntime;

    public Sqlite(Context context, Files files) {
        this(context, files, null);
    }

    public Sqlite(Context context, Files files, ScriptRuntime runtime) {
        mContext = context.getApplicationContext();
        mFiles = files;
        mRuntime = runtime;
    }

    public Database open(String name) { return open(name, 1, false); }

    public Database open(String name, int version) {
        return open(name, version == 0 ? 1 : version, false);
    }

    /** Host bridge entry point, without a Rhino options object. */
    public Database open(String name, int version, boolean readOnly) {
        return new Database(mContext, resolveName(name), version, readOnly, null, mRuntime).open();
    }

    public Database open(String name, Object options) { return open(name, options, null); }

    public Database open(String name, Object options, Object callbacks) {
        Scriptable opts = options instanceof Scriptable ? (Scriptable) options : null;
        Object requestedVersion = property(opts, "version");
        int version = org.mozilla.javascript.Context.toBoolean(requestedVersion)
                ? (int) org.mozilla.javascript.Context.toNumber(requestedVersion) : 1;
        boolean readOnly = org.mozilla.javascript.Context.toBoolean(property(opts, "readOnly"));
        Database.Callbacks adapted = callbacks instanceof Database.Callbacks
                ? (Database.Callbacks) callbacks : adaptCallbacks(callbacks);
        return new Database(mContext, resolveName(name), version, readOnly, adapted, mRuntime).open();
    }

    private static Object property(Scriptable object, String name) {
        if (object == null) return Undefined.instance;
        Object value = ScriptableObject.getProperty(object, name);
        return value == Scriptable.NOT_FOUND ? Undefined.instance : value;
    }

    private static Database.Callbacks adaptCallbacks(Object callbacks) {
        if (!(callbacks instanceof Scriptable)) return null;
        Scriptable object = (Scriptable) callbacks;
        return new Database.Callbacks() {
            private boolean call(String name, Object... args) {
                Object callback = property(object, name);
                if (!(callback instanceof Function)) return false;
                org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.getCurrentContext();
                if (cx == null) throw new IllegalStateException("No Rhino context for database callback");
                Function function = (Function) callback;
                Scriptable scope = function.getParentScope();
                if (scope == null) scope = ScriptableObject.getTopLevelScope(object);
                Object[] wrapped = new Object[args.length];
                for (int i = 0; i < args.length; i++) wrapped[i] = org.mozilla.javascript.Context.javaToJS(args[i], scope);
                function.call(cx, scope, object, wrapped);
                return true;
            }

            @Override public void onConfigure(Database db) { call("onConfigure", db); }
            @Override public void onCreate(Database db) { call("onCreate", db); }
            @Override public void onUpgrade(Database db, int oldVersion, int newVersion) { call("onUpgrade", db, oldVersion, newVersion); }
            @Override public void onOpen(Database db) { call("onOpen", db); }
            @Override public void onCorruption(SQLiteDatabase db) {
                if (!call("onCorruption", db)) Database.Callbacks.super.onCorruption(db);
            }
        };
    }

    private String resolveName(String name) {
        return mFiles == null ? name : mFiles.path(name);
    }
}