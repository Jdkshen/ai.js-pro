package com.stardust.autojs.core.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;

public class Transaction {

    private final SQLiteDatabase mDatabase;
    private final Database mOwner;

    public Database getDatabase() {
        return mOwner;
    }

    public Transaction(Database database) {
        mOwner = database;
        mDatabase = database.getSQLiteDatabase();
    }

    public void executeSql(String sqlStatement, String[] arguments, StatementCallback callback, StatementErrorCallback errorCallback){
        Cursor cursor = mDatabase.rawQuery(sqlStatement,  arguments);
        callback.handleEvent(this, DatabaseResultSet.fromCursor(cursor));
    }

    public Object exec(String sqlStatement) {
        return exec(sqlStatement, null);
    }

    /** 与 Database.exec 一致的 select 路由（Pro 示例的事务体内用 tx.exec）。 */
    public Object exec(String sqlStatement, Object[] arguments) {
        String trimmed = sqlStatement == null ? "" : sqlStatement.trim().toLowerCase();
        if (trimmed.startsWith("select") || trimmed.startsWith("pragma")
                || trimmed.startsWith("explain") || trimmed.startsWith("with")) {
            Cursor cursor = mDatabase.rawQuery(sqlStatement, toStringArray(arguments));
            DatabaseResultSet result = DatabaseResultSet.fromCursor(cursor);
            return result != null ? result
                    : new DatabaseResultSet(-1, 0, new DatabaseResultSet.RowList(new ArrayList<>()));
        }
        if (arguments == null) {
            mDatabase.execSQL(sqlStatement);
        } else {
            mDatabase.execSQL(sqlStatement, arguments);
        }
        return null;
    }

    private static String[] toStringArray(Object[] args) {
        if (args == null) return null;
        String[] result = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            result[i] = args[i] == null ? null : String.valueOf(args[i]);
        }
        return result;
    }

    public void succeed() {
        mDatabase.setTransactionSuccessful();
    }

    public void end() {
        mDatabase.endTransaction();
    }
}
