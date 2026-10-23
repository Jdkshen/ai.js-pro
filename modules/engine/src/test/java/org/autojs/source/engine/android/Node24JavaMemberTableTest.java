package org.autojs.source.engine.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class Node24JavaMemberTableTest {
    private static final class Isolated extends ClassLoader {
        Class<?> define(byte[] bytes) { return defineClass("generated.SameName", bytes, 0, bytes.length); }
    }
    @Test public void identicalClassNamesInDifferentLoadersHaveDifferentIds() {
        byte[] bytes = Node24ClassGenerator.generate("generated.SameName", Object.class, null,
                java.util.Collections.singleton("toString"));
        Class<?> a = new Isolated().define(bytes), b = new Isolated().define(bytes);
        assertEquals(a.getName(), b.getName());
        Node24JavaMemberTable table = new Node24JavaMemberTable();
        assertNotEquals(table.typeId(a), table.typeId(b));
        assertNotEquals(table.bind(a, "toString", false), table.bind(b, "toString", false));
    }
    @Test public void idsShareClassMetadataButKeepStaticAndClassIdentitySeparate() {
        Node24JavaMemberTable table = new Node24JavaMemberTable();
        int a = table.bind(String.class, "valueOf", true);
        assertEquals(a, table.bind(String.class, "valueOf", true));
        assertNotEquals(a, table.bind(String.class, "valueOf", false));
        assertNotEquals(a, table.bind(Integer.class, "valueOf", true));
        assertSame(String.class, table.get(a).type);
        assertTrue(table.get(a).isStatic);
        assertNull(table.get(0));
        assertEquals(table.typeId(String.class), table.typeId(String.class));
        assertNotEquals(table.typeId(String.class), table.typeId(Integer.class));
    }
    @Test public void capacityFallbackPreservesPreviouslyIssuedIds() {
        Node24JavaMemberTable table = new Node24JavaMemberTable();
        int first = table.bind(String.class, "first", false);
        for (int i = 1; i < Node24JavaMemberTable.MAX_ENTRIES; i++)
            assertTrue(table.bind(String.class, "m" + i, false) > 0);
        assertEquals(0, table.bind(String.class, "overflow", false));
        assertEquals(first, table.bind(String.class, "first", false));
        assertNotNull(table.get(first));
        table.close();
        assertNull(table.get(first));
        assertEquals(0, table.bind(String.class, "after-close", false));
        assertEquals(0, table.typeId(String.class));
    }
}
