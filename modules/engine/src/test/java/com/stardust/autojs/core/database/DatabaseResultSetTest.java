package com.stardust.autojs.core.database;

import android.database.Cursor;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DatabaseResultSetTest {

    @Test
    public void emptyQueryClosesCursor() {
        AtomicBoolean closed = new AtomicBoolean();
        Cursor cursor = (Cursor) Proxy.newProxyInstance(
                Cursor.class.getClassLoader(), new Class<?>[]{Cursor.class},
                (proxy, method, args) -> {
                    if ("moveToFirst".equals(method.getName())) {
                        return false;
                    }
                    if ("close".equals(method.getName())) {
                        closed.set(true);
                        return null;
                    }
                    throw new AssertionError("Unexpected Cursor call: " + method.getName());
                });

        assertNull(DatabaseResultSet.fromCursor(cursor));
        assertTrue("An empty query must release its Cursor", closed.get());
    }
}
