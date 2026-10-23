package org.autojs.source.engine.android;

import com.android.dx.command.dexer.Main;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** The fast compiler must accept actual callback bytecode, including wide values and interfaces. */
public class Node24GeneratedClassDexTest {
    public interface Events {
        boolean accept(Object value, double scale);
    }

    public abstract static class Parent {
        protected Parent(String name, long version) {}
        public abstract long compute(int number, long offset);
        protected String label(Object value) { return String.valueOf(value); }
    }

    @Test public void dxCompilesGeneratedCallbacksAndSuperHelpers() throws Exception {
        File directory = Files.createTempDirectory("node24-generated-dex-").toFile();
        File input = new File(directory, "GeneratedCallback.class"), output = new File(directory, "callback-dex.jar");
        String name = "node24.probe.GeneratedCallback";
        byte[] bytes = Node24ClassGenerator.generate(name, Parent.class, new Class<?>[]{Events.class},
                new LinkedHashSet<>(Arrays.asList("compute", "label", "accept")));
        try {
            Files.write(input.toPath(), bytes);
            Main.Arguments args = new Main.Arguments();
            args.fileNames = new String[]{input.getAbsolutePath()};
            args.strictNameCheck = false;
            args.outName = output.getAbsolutePath();
            args.jarOutput = true;
            assertEquals(0, Main.run(args));
            try (ZipFile zip = new ZipFile(output)) {
                ZipEntry dex = zip.getEntry("classes.dex");
                assertNotNull(dex);
                try (java.io.InputStream stream = zip.getInputStream(dex)) {
                    assertArrayEquals(new byte[]{'d','e','x','\n'}, stream.readNBytes(4));
                }
            }
        } finally {
            Files.deleteIfExists(input.toPath()); Files.deleteIfExists(output.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
