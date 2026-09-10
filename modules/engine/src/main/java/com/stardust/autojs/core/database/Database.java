package com.stardust.autojs.core.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteTransactionListener;

import java.util.ArrayList;
import java.util.Map;

public class Database {


    private final DatabasesOpenHelper mHelper;
    private SQLiteDatabase mWritableDatabase;
    private SQLiteDatabase mReadableDatabase;

    public Database(Context context, String name) {
        this(context, name, 1);
    }

    public Database(Context context, String name, int version) {
        mHelper = new DatabasesOpenHelper(context, name, null, Math.max(version, 1));
    }

    private SQLiteDatabase writable() {
        if (mWritableDatabase == null || !mWritableDatabase.isOpen()) {
            mWritableDatabase = mHelper.getWritableDatabase();
        }
        return mWritableDatabase;
    }

    private SQLiteDatabase readable() {
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

    public DatabaseResultSet insert(String table, Map<String, Object> values) {
        long rowId = writable().insert(table, null, toContentValues(values));
        return new DatabaseResultSet(rowId, rowId > 0 ? 1 : 0, new DatabaseResultSet.RowList(new ArrayList<>()));
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

    public DatabaseResultSet update(String table, Map<String, Object> values, String whereClause, Object[] whereArgs) {
        int affected = writable().update(table, toContentValues(values), whereClause, toStringArray(whereArgs));
        return new DatabaseResultSet(-1, affected, new DatabaseResultSet.RowList(new ArrayList<>()));
    }

    public DatabaseResultSet delete(String table, String whereClause, Object[] whereArgs) {
        int affected = writable().delete(table, whereClause, toStringArray(whereArgs));
        return new DatabaseResultSet(-1, affected, new DatabaseResultSet.RowList(new ArrayList<>()));
    }

    public void close() {
        if (mWritableDatabase != null) mWritableDatabase.close();
        if (mReadableDatabase != null && mReadableDatabase != mWritableDatabase) mReadableDatabase.close();
        mHelper.close();
    }

    public void transaction(TransactionCallback callback, TransactionErrorCallback errorCallback, DatabaseVoidCallback successCallback) {
        transactionInternal(writable(), callback, errorCallback, successCallback);
    }

    public void readTransaction(TransactionCallback callback, TransactionErrorCallback errorCallback, DatabaseVoidCallback successCallback) {
        transactionInternal(readable(), callback, errorCallback, successCallback);
    }

    public void changeVersion(int oldVersion, int newVersion, TransactionCallback callback, TransactionErrorCallback errorCallback, DatabaseVoidCallback successCallback) {
        transactionInternal(writable(), new TransactionCallback() {
            @Override
            public void handleEvent(Transaction transaction) {
                if (transaction.getDatabase().getVersion() == oldVersion) {
                    transaction.getDatabase().setVersion(newVersion);
                    callback.handleEvent(transaction);
                }
            }
        }, errorCallback, successCallback);
    }

    private void transactionInternal(SQLiteDatabase database, TransactionCallback callback, TransactionErrorCallback errorCallback, DatabaseVoidCallback successCallback) {
        database.beginTransactionWithListener(new SQLiteTransactionListener() {
            @Override
            public void onBegin() {
                Transaction transaction = new Transaction(database);
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

            @Override
            public void onCommit() {
                successCallback.handleEvent();
            }

            @Override
            public void onRollback() {
            }
        });
    }

    private static ContentValues toContentValues(Map<String, Object> values) {
        ContentValues cv = new ContentValues();
        if (values == null) return cv;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object v = entry.getValue();
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


    private static class DatabasesOpenHelper extends SQLiteOpenHelper {

        public DatabasesOpenHelper(Context context, String name, SQLiteDatabase.CursorFactory factory, int version) {
            super(context, name, factory, version);
        }

        public DatabasesOpenHelper(Context context, String name, SQLiteDatabase.CursorFactory factory, int version, DatabaseErrorHandler errorHandler) {
            super(context, name, factory, version, errorHandler);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Auto.js sqlite module keeps an empty schema by default; callers use executeSql.
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }

    }

}
