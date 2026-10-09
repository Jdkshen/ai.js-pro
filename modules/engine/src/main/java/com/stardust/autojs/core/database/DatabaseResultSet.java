package com.stardust.autojs.core.database;

import android.database.Cursor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class DatabaseResultSet {


    public static class RowList {

        public final int length;
        private final ArrayList<Map<String, Object>> mData;


        public RowList(ArrayList<Map<String, Object>> data) {
            mData = data;
            length = mData.size();
        }

        public Object item(int i) {
            return mData.get(i);
        }

        @Override
        public String toString() {
            return String.valueOf(mData);
        }
    }

    public final long insertId;
    public final long rowsAffected;
    public final RowList rows;

    public static DatabaseResultSet fromCursor(Cursor cursor) {
        try {
            ArrayList<Map<String, Object>> rows = new ArrayList<>();
            if (!cursor.moveToFirst()) {
                return null;
            }
            int columnCount = cursor.getColumnCount();
            long insertId = cursor.getLong(0);
            rows.add(readRowAsMap(cursor, columnCount));
            while (cursor.moveToNext()) {
                rows.add(readRowAsMap(cursor, columnCount));
            }
            return new DatabaseResultSet(insertId, new RowList(rows));
        } finally {
            cursor.close();
        }
    }

    private static Map<String, Object> readRowAsMap(Cursor cursor, int columnCount) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < columnCount; i++) {
            map.put(cursor.getColumnName(i), CursorHelper.getValue(cursor, i));
        }
        return map;
    }

    public DatabaseResultSet(long insertId, RowList rowList) {
        this(insertId, rowList.length, rowList);
    }

    public DatabaseResultSet(long insertId, long rowsAffected, RowList rowList) {
        this.insertId = insertId;
        this.rowsAffected = rowsAffected;
        this.rows = rowList;
    }

    private int mCursor = -1;

    /** 全部行（RowList：length/item(i)）。 */
    public RowList all() {
        return rows;
    }

    /** 第一行（空结果为 null）。 */
    public Object single() {
        return rows.length > 0 ? rows.item(0) : null;
    }

    /** 游标移动到下一行。 */
    public boolean moveToNext() {
        if (mCursor + 1 < rows.length) {
            mCursor++;
            return true;
        }
        return false;
    }

    /** 当前游标行（未移动或越界为 null）。 */
    public Object pick() {
        return mCursor >= 0 && mCursor < rows.length ? rows.item(mCursor) : null;
    }

    /** 结果一次性读出、无底层资源；保留以对齐 Pro 示例里的 cursor.close()。 */
    public void close() {
    }

}
