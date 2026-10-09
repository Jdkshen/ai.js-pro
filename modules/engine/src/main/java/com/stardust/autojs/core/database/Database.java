package com.stardust.autojs.core.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteTransactionListener;

import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Wrapper;

import java.util.ArrayList;
import java.util.Map;
import android.database.DefaultDatabaseErrorHandler;
import com.stardust.autojs.runtime.ScriptRuntime;

public class Database implements AutoCloseable {


    public interface Callbacks {
        default void onConfigure(Database database) {}
        default void onCreate(Database database) {}
        default void onUpgrade(Database database, int oldVersion, int newVersion) {}
        default void onOpen(Database database) {}
        default void onCorruption(SQLiteDatabase database) {
            new DefaultDatabaseErrorHandler().onCorruption(database);
        }
    }

    private final DatabasesOpenHelper mHelper;
    private final ScriptRuntime mRuntime;
    private final Callbacks mCallbacks;
    private final boolean mReadOnly;
    private SQLiteDatabase mWritableDatabase;
    private SQLiteDatabase mReadableDatabase;
    private boolean mClosed;
    private boolean mRegistered;

    public Database(Context context, String name) {
        this(context, name, 1);
    }

    public Database(Context context, String name, int version) {
        this(context, name, Math.max(version, 1), false, null, null);
    }

    public Database(Context context, String name, int version, boolean readOnly,
                    Callbacks callbacks, ScriptRuntime runtime) {
        if (version < 1) throw new IllegalArgumentException("Database version must be >= 1");
        mRuntime = runtime;
        mCallbacks = callbacks == null ? new Callbacks() {} : callbacks;
        mReadOnly = readOnly;
        mHelper = new DatabasesOpenHelper(context, name, null, version,
                callbacks == null ? null : mCallbacks::onCorruption);
    }

    /** Opens immediately so initialization and migration failures reach sqlite.open(). */
    public synchronized Database open() {
        writable();
        if (mRuntime != null && !mRegistered) {
            mRuntime.addCleanable(this);
            mRegistered = true;
        }
        return this;
    }

    public SQLiteDatabase getSQLiteDatabase() {
        return writable();
    }

    private void checkOpen() {
        if (mClosed) throw new IllegalStateException("Database is closed");
    }

    private SQLiteDatabase writable() {
        checkOpen();
        if (mWritableDatabase == null || !mWritableDatabase.isOpen()) {
            mWritableDatabase = mReadOnly ? mHelper.getReadableDatabase() : mHelper.getWritableDatabase();
        }
        return mWritableDatabase;
    }

    private SQLiteDatabase readable() {
        checkOpen();
        if (mReadableDatabase == null || !mReadableDatabase.isOpen()) {
            mReadableDatabase = mHelper.getReadableDatabase();
        }
        return mReadableDatabase;
    }

    public void executeSql(String sql) {
        writable().execSQL(sql);
    }

    public void executeSql(String sql, Object[] bindArgs) {
        writable().execSQL(sql, bindArgs);
    }

    /** Auto.js compatible alias for {@link #executeSql(String)}.
     *  Query statements (SELECT/PRAGMA/EXPLAIN/WITH) are routed to select(). */
    public Object exec(String sql) {
        String trimmed = sql == null ? "" : sql.trim().toLowerCase();
        if (trimmed.startsWith("select") || trimmed.startsWith("pragma")
                || trimmed.startsWith("explain") || trimmed.startsWith("with")) {
            return select(sql);
        }
        executeSql(sql);
        return null;
    }

    public Object exec(String sql, Object[] bindArgs) {
        String trimmed = sql == null ? "" : sql.trim().toLowerCase();
        if (trimmed.startsWith("select") || trimmed.startsWith("pragma")
                || trimmed.startsWith("explain") || trimmed.startsWith("with")) {
            return select(sql, bindArgs);
        }
        executeSql(sql, bindArgs);
        return null;
    }

    public long insert(String table, Map<String, Object> values) {
        return insert(table, null, values);
    }

    public long insert(String table, String nullColumnHack, Map<String, Object> values) {
        return writable().insert(table, nullColumnHack, toContentValues(values));
    }

    /** Legacy result metadata extension; Pro insert() itself returns the row id. */
    public DatabaseResultSet insertResult(String table, Map<String, Object> values) {
        long rowId = insert(table, values);
        return new DatabaseResultSet(rowId, rowId != -1 ? 1 : 0, new DatabaseResultSet.RowList(new ArrayList<>()));
    }

    public DatabaseResultSet select(String sql) {
        return select(sql, null);
    }

    public DatabaseResultSet select(String sql, Object[] selectionArgs) {
        Cursor cursor = readable().rawQuery(sql, toStringArray(selectionArgs));
        DatabaseResultSet result = DatabaseResultSet.fromCursor(cursor);
        return result != null ? result
                : new DatabaseResultSet(-1, 0, new DatabaseResultSet.RowList(new ArrayList<>()));
    }

    public int update(String table, Map<String, Object> values, String whereClause, Object[] whereArgs) {
        return writable().update(table, toContentValues(values), whereClause, toStringArray(whereArgs));
    }

    public DatabaseResultSet updateResult(String table, Map<String, Object> values, String whereClause, Object[] whereArgs) {
        int affected = update(table, values, whereClause, whereArgs);
        return new DatabaseResultSet(-1, affected, new DatabaseResultSet.RowList(new ArrayList<>()));
    }

    public int delete(String table, String whereClause, Object[] whereArgs) {
        return writable().delete(table, whereClause, toStringArray(whereArgs));
    }

    public DatabaseResultSet deleteResult(String table, String whereClause, Object[] whereArgs) {
        int affected = delete(table, whereClause, whereArgs);
        return new DatabaseResultSet(-1, affected, new DatabaseResultSet.RowList(new ArrayList<>()));
    }

    @Override
    public synchronized void close() {
        if (mClosed) return;
        mHelper.close();
        mClosed = true;
        if (mRuntime != null && mRegistered) {
            mRuntime.removeCleanable(this);
            mRegistered = false;
        }
    }

    public boolean isOpen() {
        return !mClosed && mWritableDatabase != null && mWritableDatabase.isOpen();
    }

    public boolean isReadOnly() { return writable().isReadOnly(); }
    public boolean inTransaction() { return writable().inTransaction(); }
    public void beginTransaction() { writable().beginTransaction(); }
    public void beginTransactionNonExclusive() { writable().beginTransactionNonExclusive(); }
    public void endTransaction() { writable().endTransaction(); }
    public void setTransactionSuccessful() { writable().setTransactionSuccessful(); }
    public void setVersion(int version) { writable().setVersion(version); }
    public void setPageSize(long bytes) { writable().setPageSize(bytes); }
    public long setMaximumSize(long bytes) { return writable().setMaximumSize(bytes); }

    /** Pro 示例使用的别名：{@link #executeSql(String)}。 */
    public void execSQL(String sql) {
        executeSql(sql);
    }

    public void execSQL(String sql, Object[] bindArgs) {
        executeSql(sql, bindArgs);
    }

    /** Pro rawQuery returns a live cursor; select() retains materialized result metadata. */
    public DatabaseCursor rawQuery(String sql, Object[] args) {
        return new DatabaseCursor(readable().rawQuery(sql, toStringArray(args)), mRuntime);
    }

    public DatabaseCursor rawQuery(String sql) {
        return rawQuery(sql, null);
    }

    public int getVersion() {
        return writable().getVersion();
    }

    public long getPageSize() {
        return writable().getPageSize();
    }

    public long getMaximumSize() {
        return writable().getMaximumSize();
    }

    public String getPath() {
        return writable().getPath();
    }

    /**
     * Pro 示例形态：{@code db.transaction(function(){...}).on("begin"/"commit"/...)}。
     * 事务同步执行，事件对象在注册时“补放”已发生的事件（见 TransactionEvents）。
     */
    public TransactionEvents transaction(final Function callback) {
        final TransactionEvents events = new TransactionEvents();
        final org.mozilla.javascript.Context cx = org.mozilla.javascript.Context.getCurrentContext();
        final Scriptable scope = callback.getParentScope();
        final SQLiteDatabase database = writable();
        final Transaction transaction = new Transaction(this);
        // 不能在 onBegin 里执行事务体（见 transactionInternal 注释）——listener 只负责事件。
        database.beginTransactionWithListener(new SQLiteTransactionListener() {
            @Override
            public void onBegin() {
            }

            @Override
            public void onCommit() {
                events.occur("commit", transaction);
            }

            @Override
            public void onRollback() {
                events.occur("rollback", transaction);
            }
        });

        events.occur("begin", transaction);
        try {
            Scriptable callbackScope = scope != null ? scope : callback;
            callback.call(cx, callbackScope, callbackScope,
                    new Object[]{org.mozilla.javascript.Context.javaToJS(transaction, callbackScope)});
            transaction.succeed();
        } catch (Exception e) {
            events.occur("error", e);
        } finally {
            transaction.end();
        }
        events.occur("end", transaction);
        return events;
    }

    public void transaction(TransactionCallback callback, TransactionErrorCallback errorCallback, DatabaseVoidCallback successCallback) {
        transactionInternal(writable(), callback, errorCallback, successCallback);
    }

    public void readTransaction(TransactionCallback callback, TransactionErrorCallback errorCallback, DatabaseVoidCallback successCallback) {
        transactionInternal(readable(), callback, errorCallback, successCallback);
    }

    public void changeVersion(int oldVersion, int newVersion, TransactionCallback callback, TransactionErrorCallback errorCallback, DatabaseVoidCallback successCallback) {
        SQLiteDatabase database = writable();
        transactionInternal(database, transaction -> {
            if (database.getVersion() == oldVersion) {
                callback.handleEvent(transaction);
                // 版本号与表结构一起提交；迁移失败时保留旧版本，允许重试。
                database.setVersion(newVersion);
            }
        }, errorCallback, successCallback);
    }

    private void transactionInternal(SQLiteDatabase database, TransactionCallback callback, TransactionErrorCallback errorCallback, DatabaseVoidCallback successCallback) {
        Transaction transaction = new Transaction(this);
        // 注意：不能在 listener.onBegin() 里执行事务体——AOSP 的 SQLiteSession 直到
        // beginTransactionWithListener() 返回后才登记事务栈，onBegin 里调 setTransactionSuccessful/
        // endTransaction 会抛 "there is no current transaction"（K40 / Android 11+ 实测）。
        database.beginTransactionWithListener(new SQLiteTransactionListener() {
            @Override
            public void onBegin() {
            }

            @Override
            public void onCommit() {
                successCallback.handleEvent();
            }

            @Override
            public void onRollback() {
            }
        });

        try {
            callback.handleEvent(transaction);
            transaction.succeed();
        } catch (Exception e) {
            errorCallback.handleEvent(new android.database.SQLException(
                    e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            transaction.end();
        }
    }

    private static ContentValues toContentValues(Map<String, Object> values) {
        ContentValues cv = new ContentValues();
        if (values == null) return cv;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof Wrapper) v = ((Wrapper) v).unwrap();
            if (v == null) {
                cv.putNull(entry.getKey());
            } else if (v instanceof Number) {
                Number number = (Number) v;
                double decimal = number.doubleValue();
                // 整数值仍按 long 写入（保持原有行为），带小数的值按 REAL 写入，
                // 否则 JS/JSON 的 1.5 会被 longValue() 截断成 1。
                if (Double.isFinite(decimal) && decimal == Math.rint(decimal)) {
                    cv.put(entry.getKey(), number.longValue());
                } else {
                    cv.put(entry.getKey(), decimal);
                }
            } else if (v instanceof Boolean) {
                cv.put(entry.getKey(), (Boolean) v);
            } else if (v instanceof byte[]) {
                cv.put(entry.getKey(), (byte[]) v);
            } else {
                cv.put(entry.getKey(), String.valueOf(v));
            }
        }
        return cv;
    }

    private static String[] toStringArray(Object[] args) {
        if (args == null) return null;
        String[] result = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            result[i] = args[i] == null ? null : String.valueOf(args[i]);
        }
        return result;
    }


    private class DatabasesOpenHelper extends SQLiteOpenHelper {

        public DatabasesOpenHelper(Context context, String name, SQLiteDatabase.CursorFactory factory, int version) {
            super(context, name, factory, version);
        }

        public DatabasesOpenHelper(Context context, String name, SQLiteDatabase.CursorFactory factory, int version, DatabaseErrorHandler errorHandler) {
            super(context, name, factory, version, errorHandler);
        }

        private void bind(SQLiteDatabase db) {
            mWritableDatabase = db;
            mReadableDatabase = db;
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            bind(db);
            mCallbacks.onConfigure(Database.this);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            bind(db);
            mCallbacks.onCreate(Database.this);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            bind(db);
            mCallbacks.onUpgrade(Database.this, oldVersion, newVersion);
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            bind(db);
            mCallbacks.onOpen(Database.this);
        }
    }

}
