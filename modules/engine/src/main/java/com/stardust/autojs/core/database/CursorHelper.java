package com.stardust.autojs.core.database;

import android.database.Cursor;

public class CursorHelper {


    public static Object getValue(Cursor cursor, int column) {
        switch (cursor.getType(column)) {
            case Cursor.FIELD_TYPE_STRING:
                return cursor.getString(column);
            case Cursor.FIELD_TYPE_FLOAT:
                return cursor.getDouble(column);
            case Cursor.FIELD_TYPE_INTEGER:
                return cursor.getLong(column);
            case Cursor.FIELD_TYPE_BLOB:
                return cursor.getBlob(column);
            default:
                return null;
        }
    }
}
