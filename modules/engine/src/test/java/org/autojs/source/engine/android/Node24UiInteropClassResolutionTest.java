package org.autojs.source.engine.android;

import android.view.View;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;

/** P2-1/P2-2: nested R classes and host-classloader Class.forName behavior. */
public class Node24UiInteropClassResolutionTest {

    public static class ResourceOwner {
        public static final int VISIBLE = 1;

        public static class color {
            public static final int system_accent1_500 = 0x11223344;
        }

        public static class attr {
            public static final int state_checked = 0x010100a0;
        }
    }

    @Test
    public void lowercaseNestedResourceClassesResolveAndExposeStaticFields() throws Exception {
        Class<?> color = Node24JavaArguments.nestedClass(ResourceOwner.class, "color");
        assertSame(ResourceOwner.color.class, color);
        assertEquals(0x11223344, color.getField("system_accent1_500").getInt(null));

        Class<?> attr = Node24JavaArguments.nestedClass(ResourceOwner.class, "attr");
        assertSame(ResourceOwner.attr.class, attr);
        assertEquals(0x010100a0, attr.getField("state_checked").getInt(null));

        assertNull(Node24JavaArguments.nestedClass(ResourceOwner.class, "missing"));
        assertNull(Node24JavaArguments.nestedClass(ResourceOwner.class, "color$nested"));
    }

    @Test
    public void androidNestedInterfacesResolveThroughTheSamePath() {
        assertSame(View.OnClickListener.class,
                Node24JavaArguments.nestedClass(View.class, "OnClickListener"));
    }

    @Test
    public void hostClassLoaderPreservesClassIdentityAndInitialization() throws Exception {
        String className = getClass().getName() + "$LoaderProbe";
        String property = "node24.ui.loader.initialized";
        ClassLoader parent = getClass().getClassLoader();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = parent.getResourceAsStream(className.replace('.', '/') + ".class")) {
            assertNotNull(input);
            byte[] buffer = new byte[4096]; int size;
            while ((size = input.read(buffer)) != -1) bytes.write(buffer, 0, size);
        }
        byte[] definition = bytes.toByteArray();
        ClassLoader loader = new ClassLoader(parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(className)) return super.loadClass(name, resolve);
                Class<?> found = findLoadedClass(name);
                if (found == null) found = defineClass(name, definition, 0, definition.length);
                if (resolve) resolveClass(found);
                return found;
            }
        };
        try {
            System.clearProperty(property);
            assertNull(System.getProperty(property));
            Class<?> lazy = Node24JavaArguments.loadClass(className, loader, false);
            assertSame(loader, lazy.getClassLoader());
            assertNotSame(Class.forName(className, false, parent), lazy);
            assertNull(System.getProperty(property));
            assertSame(lazy, Node24JavaArguments.initializeClass(lazy));
            assertEquals("initialized", System.getProperty(property));
        } finally {
            System.clearProperty(property);
        }
    }
    public static class LoaderProbe {
        static { System.setProperty("node24.ui.loader.initialized", "initialized"); }
    }
}
