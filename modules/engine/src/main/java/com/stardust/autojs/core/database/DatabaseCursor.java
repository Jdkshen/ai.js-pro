package com.stardust.autojs.core.database;

import android.content.ContentResolver;
import android.database.CharArrayBuffer;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;

import com.stardust.autojs.runtime.ScriptRuntime;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.ArrayList;
import java.util.List;

/** A live Android cursor with the Pro script conveniences and consumption semantics. */
public final class DatabaseCursor implements Cursor, AutoCloseable {
    private final Cursor mCursor;
    private final ScriptRuntime mRuntime;
    private Scriptable mScope;

    public DatabaseCursor(Cursor cursor, ScriptRuntime runtime) {
        this(cursor, runtime, runtime == null ? null : runtime.getTopLevelScope());
    }

    DatabaseCursor(Cursor cursor, ScriptRuntime runtime, Scriptable scope) {
        mCursor = cursor;
        mRuntime = runtime;
        mScope = scope;
        if (runtime != null) runtime.addCleanable(this);
    }

    private Context context() {
        Context cx = Context.getCurrentContext();
        if (cx == null) throw new IllegalStateException("No Rhino context for database cursor");
        if (mScope == null) mScope = cx.initStandardObjects();
        return cx;
    }

    public Object get(int index) {
        return CursorHelper.getValue(mCursor, index);
    }

    public Object getByColumn(String column) {
        return get(mCursor.getColumnIndexOrThrow(column));
    }

    public Scriptable pick() {
        Context cx = context();
        Scriptable row = cx.newObject(mScope);
        for (int i = 0; i < mCursor.getColumnCount(); i++) {
            ScriptableObject.putProperty(row, mCursor.getColumnName(i),
                    Context.javaToJS(get(i), mScope));
        }
        return row;
    }

    public Scriptable next() {
        return mCursor.moveToNext() ? pick() : null;
    }

    public Scriptable all() { return all(true); }

    public Scriptable all(boolean close) {
        Context cx = context();
        try {
            ArrayList<Object> rows = new ArrayList<>();
            while (mCursor.moveToNext()) rows.add(pick());
            return cx.newArray(mScope, rows.toArray());
        } finally {
            if (close) close();
        }
    }

    public Scriptable single() {
        try {
            return next();
        } finally {
            close();
        }
    }

    @Override public void close() {
        try {
            mCursor.close();
        } finally {
            if (mRuntime != null) mRuntime.removeCleanable(this);
        }
    }

    @Override public int getCount() { return mCursor.getCount(); }
    @Override public int getPosition() { return mCursor.getPosition(); }
    @Override public boolean move(int offset) { return mCursor.move(offset); }
    @Override public boolean moveToPosition(int position) { return mCursor.moveToPosition(position); }
    @Override public boolean moveToFirst() { return mCursor.moveToFirst(); }
    @Override public boolean moveToLast() { return mCursor.moveToLast(); }
    @Override public boolean moveToNext() { return mCursor.moveToNext(); }
    @Override public boolean moveToPrevious() { return mCursor.moveToPrevious(); }
    @Override public boolean isFirst() { return mCursor.isFirst(); }
    @Override public boolean isLast() { return mCursor.isLast(); }
    @Override public boolean isBeforeFirst() { return mCursor.isBeforeFirst(); }
    @Override public boolean isAfterLast() { return mCursor.isAfterLast(); }
    @Override public int getColumnIndex(String name) { return mCursor.getColumnIndex(name); }
    @Override public int getColumnIndexOrThrow(String name) { return mCursor.getColumnIndexOrThrow(name); }
    @Override public String getColumnName(int index) { return mCursor.getColumnName(index); }
    @Override public String[] getColumnNames() { return mCursor.getColumnNames(); }
    @Override public int getColumnCount() { return mCursor.getColumnCount(); }
    @Override public byte[] getBlob(int index) { return mCursor.getBlob(index); }
    @Override public String getString(int index) { return mCursor.getString(index); }
    @Override public void copyStringToBuffer(int index, CharArrayBuffer buffer) { mCursor.copyStringToBuffer(index, buffer); }
    @Override public short getShort(int index) { return mCursor.getShort(index); }
    @Override public int getInt(int index) { return mCursor.getInt(index); }
    @Override public long getLong(int index) { return mCursor.getLong(index); }
    @Override public float getFloat(int index) { return mCursor.getFloat(index); }
    @Override public double getDouble(int index) { return mCursor.getDouble(index); }
    @Override public int getType(int index) { return mCursor.getType(index); }
    @Override public boolean isNull(int index) { return mCursor.isNull(index); }
    @Override public void deactivate() { mCursor.deactivate(); }
    @Override public boolean requery() { return mCursor.requery(); }
    @Override public boolean isClosed() { return mCursor.isClosed(); }
    @Override public void registerContentObserver(ContentObserver observer) { mCursor.registerContentObserver(observer); }
    @Override public void unregisterContentObserver(ContentObserver observer) { mCursor.unregisterContentObserver(observer); }
    @Override public void registerDataSetObserver(DataSetObserver observer) { mCursor.registerDataSetObserver(observer); }
    @Override public void unregisterDataSetObserver(DataSetObserver observer) { mCursor.unregisterDataSetObserver(observer); }
    @Override public void setNotificationUri(ContentResolver resolver, Uri uri) { mCursor.setNotificationUri(resolver, uri); }
    @Override public void setNotificationUris(ContentResolver resolver, List<Uri> uris) { mCursor.setNotificationUris(resolver, uris); }
    @Override public Uri getNotificationUri() { return mCursor.getNotificationUri(); }
    @Override public List<Uri> getNotificationUris() { return mCursor.getNotificationUris(); }
    @Override public boolean getWantsAllOnMoveCalls() { return mCursor.getWantsAllOnMoveCalls(); }
    @Override public void setExtras(Bundle extras) { mCursor.setExtras(extras); }
    @Override public Bundle getExtras() { return mCursor.getExtras(); }
    @Override public Bundle respond(Bundle extras) { return mCursor.respond(extras); }
}