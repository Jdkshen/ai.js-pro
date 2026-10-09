package com.stardust.autojs.core.database;

import android.database.Cursor;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

public class DatabaseCursorTest {
    private static class Data {
        int position = -1;
        boolean closed;
        final String[] columns = {"id", "score", "name", "blob"};
        final Object[][] rows = {
                {4294967297L, 1.234567890123d, "one", new byte[]{0, (byte) 255}},
                {4294967298L, 9.75d, "two", new byte[]{3}}
        };

        Cursor cursor() {
            return (Cursor) Proxy.newProxyInstance(Cursor.class.getClassLoader(), new Class<?>[]{Cursor.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "moveToNext": position++; return position < rows.length;
                            case "moveToFirst": position = 0; return rows.length > 0;
                            case "getColumnCount": return columns.length;
                            case "getColumnName": return columns[(int) args[0]];
                            case "getColumnNames": return columns;
                            case "getColumnIndexOrThrow":
                                for (int i = 0; i < columns.length; i++) if (columns[i].equals(args[0])) return i;
                                throw new IllegalArgumentException(String.valueOf(args[0]));
                            case "getType":
                                Object value = rows[position][(int) args[0]];
                                return value instanceof Long ? Cursor.FIELD_TYPE_INTEGER : value instanceof Double
                                        ? Cursor.FIELD_TYPE_FLOAT : value instanceof byte[] ? Cursor.FIELD_TYPE_BLOB : Cursor.FIELD_TYPE_STRING;
                            case "getLong": return (Long) rows[position][(int) args[0]];
                            case "getDouble": return (Double) rows[position][(int) args[0]];
                            case "getString": return (String) rows[position][(int) args[0]];
                            case "getBlob": return (byte[]) rows[position][(int) args[0]];
                            case "close": closed = true; return null;
                            case "isClosed": return closed;
                            default: throw new AssertionError("Unexpected Cursor call: " + method.getName());
                        }
                    });
        }
    }

    @Test public void allReturnsNativeArrayAndConsumesOnlyRemainingRows() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Data data = new Data();
            DatabaseCursor cursor = new DatabaseCursor(data.cursor(), null, scope);
            assertTrue(cursor.moveToNext());
            assertEquals(4294967297L, cursor.getByColumn("id"));
            assertEquals(1.234567890123d, (Double) cursor.getByColumn("score"), 0d);
            ScriptableObject.putProperty(scope, "rows", cursor.all(false));
            assertEquals(Boolean.TRUE, cx.evaluateString(scope,
                    "Array.isArray(rows) && rows.length === 1 && rows[0].id === 4294967298 && rows.map(function(r){return r.name;})[0] === 'two'",
                    "cursor-test", 1, null));
            assertFalse(data.closed);
            Scriptable remaining = cursor.all();
            assertEquals(0d, Context.toNumber(remaining.get("length", remaining)), 0d);
            assertTrue(data.closed);
        } finally { Context.exit(); }
    }

    @Test public void nextAndSingleConsumePositionAndSingleCloses() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Data data = new Data();
            DatabaseCursor cursor = new DatabaseCursor(data.cursor(), null, scope);
            assertEquals("one", cursor.next().get("name", scope));
            assertEquals("two", cursor.single().get("name", scope));
            assertTrue(data.closed);
        } finally { Context.exit(); }
    }

    @Test public void typedAccessPreservesBlobAndRejectsUnknownColumns() {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Data data = new Data();
            DatabaseCursor cursor = new DatabaseCursor(data.cursor(), null, scope);
            assertTrue(cursor.moveToNext());
            assertArrayEquals(new byte[]{0, (byte) 255}, (byte[]) cursor.getByColumn("blob"));
            try { cursor.getByColumn("missing"); fail("Unknown column must throw"); }
            catch (IllegalArgumentException expected) { assertEquals("missing", expected.getMessage()); }
            cursor.close();
        } finally { Context.exit(); }
    }
}