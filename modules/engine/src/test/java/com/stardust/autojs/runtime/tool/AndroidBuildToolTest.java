package com.stardust.autojs.runtime.tool;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.Assert.*;

public class AndroidBuildToolTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void smaliCliProducesDex() throws Exception {
        File source = temp.newFile("Probe.smali");
        Files.write(source.toPath(), (".class public Lmcp/Probe;\n.super Ljava/lang/Object;\n"
                + ".method public static value()I\n.registers 1\nconst/16 v0, 42\nreturn v0\n.end method\n")
                .getBytes(StandardCharsets.UTF_8));
        File dex = new File(temp.getRoot(), "smali.dex");
        AndroidBuildTool.main(new String[]{"smali", "assemble", source.getPath(), "-o", dex.getPath()});
        assertDex(dex);
    }

    @Test public void d8CliProducesDexFromJava17Jar() throws Exception {
        // Same precompiled Java17Sample fixture as D8JarDexerTest; no desktop Java compiler API in APK.
        byte[] bytes = Base64.getDecoder().decode("yv66vgAAAD0AEAoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWCAAIAQAFaGVsbG8HAAoBAAxKYXZhMTdTYW1wbGUBAARDb2RlAQAPTGluZU51bWJlclRhYmxlAQAUKClMamF2YS9sYW5nL1N0cmluZzsBAApTb3VyY2VGaWxlAQARSmF2YTE3U2FtcGxlLmphdmEAIQAJAAIAAAAAAAIAAQAFAAYAAQALAAAAHQABAAEAAAAFKrcAAbEAAAABAAwAAAAGAAEAAAABAAkACAANAAEACwAAABsAAQAAAAAAAxIHsAAAAAEADAAAAAYAAQAAAAIAAQAOAAAAAgAP");
        File jar = temp.newFile("probe.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            output.putNextEntry(new JarEntry("Java17Sample.class"));
            output.write(bytes);
            output.closeEntry();
        }
        File out = temp.newFolder("dex");
        AndroidBuildTool.main(new String[]{"d8", "--min-api", "26", "--output", out.getPath(), jar.getPath()});
        assertDex(new File(out, "classes.dex"));
    }

    private static void assertDex(File dex) throws Exception {
        assertTrue(dex.isFile());
        byte[] bytes = Files.readAllBytes(dex.toPath());
        assertTrue(bytes.length > 8);
        assertArrayEquals(new byte[]{'d','e','x','\n'}, java.util.Arrays.copyOf(bytes, 4));
    }
}
