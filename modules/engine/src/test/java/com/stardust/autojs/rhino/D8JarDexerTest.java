package com.stardust.autojs.rhino;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 回归测试：D8 必须能把 Java 17（class major 61）的 jar dex 化。
 *
 * <p>旧实现 dx 只支持 Java 8（major 52），遇到这种 jar 会直接失败。
 * Android 单元测试的 classpath 没有 {@code javax.tools}，所以这里直接内嵌一个
 * 用 {@code javac --release 17} 编译出的最小 class（273 字节）作为 fixture。
 * 重新生成方法：编译后 {@code [Convert]::ToBase64String([IO.File]::ReadAllBytes(path))}。
 */
public class D8JarDexerTest {

    private static final String JAVA_17_CLASS_BASE64 =
            "yv66vgAAAD0AEAoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWCAAIAQAFaGVsbG8HAAoBAAxKYXZhMTdTYW1wbGUBAARDb2RlAQAPTGluZU51bWJlclRhYmxlAQAUKClMamF2YS9sYW5nL1N0cmluZzsBAApTb3VyY2VGaWxlAQARSmF2YTE3U2FtcGxlLmphdmEAIQAJAAIAAAAAAAIAAQAFAAYAAQALAAAAHQABAAEAAAAFKrcAAbEAAAABAAwAAAAGAAEAAAABAAkACAANAAEACwAAABsAAQAAAAAAAxIHsAAAAAEADAAAAAYAAQAAAAIAAQAOAAAAAgAP";

    @Test
    public void dexesJava17ClassFile() throws Exception {
        File tempDir = createTempDir();
        try {
            byte[] classBytes = Base64.getDecoder().decode(JAVA_17_CLASS_BASE64);
            int major = ((classBytes[6] & 0xff) << 8) | (classBytes[7] & 0xff);
            assertEquals("fixture must be Java 17 class", 61, major);

            File jar = new File(tempDir, "java17.jar");
            writeJar(jar, "Java17Sample.class", classBytes);

            File outDir = new File(tempDir, "dex");
            assertTrue(outDir.mkdirs());
            D8JarDexer.dex(jar, outDir);

            File dex = new File(outDir, "classes.dex");
            assertTrue("D8 should produce classes.dex", dex.isFile());
            assertTrue(dex.length() > 0);
            byte[] magic = new byte[4];
            try (InputStream in = new FileInputStream(dex)) {
                assertEquals(4, in.read(magic));
            }
            assertArrayEquals("dex magic", new byte[]{'d', 'e', 'x', '\n'}, magic);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static File createTempDir() throws IOException {
        File file = File.createTempFile("d8-jar-dexer-test", "");
        if (!file.delete() || !file.mkdirs()) {
            throw new IOException("cannot create temp dir: " + file);
        }
        return file;
    }

    private static void writeJar(File jar, String entryName, byte[] classBytes) throws IOException {
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.putNextEntry(new JarEntry(entryName));
            out.write(classBytes);
            out.closeEntry();
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
