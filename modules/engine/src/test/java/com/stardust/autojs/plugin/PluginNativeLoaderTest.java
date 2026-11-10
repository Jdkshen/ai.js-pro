package com.stardust.autojs.plugin;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** task-141: JVM-testable parts of PluginNativeLoader (zip extraction / abi selection / named failures). */
public class PluginNativeLoaderTest {

    private static File fakeApk(File dir) throws Exception {
        File apk = new File(dir, "plugin.apk");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            put(zip, "lib/arm64-v8a/libppocr.so", "PPOCR");
            put(zip, "lib/arm64-v8a/libc++_shared.so", "CXX");
            put(zip, "lib/armeabi-v7a/libppocr.so", "V7A");
            put(zip, "lib/arm64-v8a/README.txt", "no");
            put(zip, "assets/ocr/models/x.bin", "model");
            put(zip, "lib/arm64-v8a/nested/libx.so", "nested");
        }
        return apk;
    }

    private static void put(ZipOutputStream zip, String name, String body) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body.getBytes("UTF-8"));
        zip.closeEntry();
    }

    /** Only lib/<abi>/*.so at top level is extracted (no .txt, no nested, no other abi). */
    @Test
    public void extractsOnlyTopLevelLibsOfTheRequestedAbi() throws Exception {
        File dir = Files.createTempDirectory("pluginlibs").toFile();
        File apk = fakeApk(dir);
        File target = new File(dir, "out");
        int copied = PluginNativeLoader.extractLibsFromApk(apk, target, "arm64-v8a");
        assertEquals(2, copied);
        assertTrue(new File(target, "libppocr.so").isFile());
        assertTrue(new File(target, "libc++_shared.so").isFile());
        assertFalse(new File(target, "README.txt").exists());
        assertFalse("nested dirs are skipped", new File(target, "nested").exists());
        assertEquals("PPOCR", new String(Files.readAllBytes(new File(target, "libppocr.so").toPath()), "UTF-8"));
        assertEquals("x86 abi has no libs in this fake apk", 0,
                PluginNativeLoader.extractLibsFromApk(apk, new File(dir, "out7"), "x86"));
    }

    private static File apkMissing(File dir) {
        return new File(dir, "missing.apk");
    }

    /** Missing apk / empty result => 0 (caller logs and returns null), never throws. */
    @Test
    public void missingApkYieldsZero() throws Exception {
        File dir = Files.createTempDirectory("pluginlibs2").toFile();
        assertEquals(0, PluginNativeLoader.extractLibsFromApk(apkMissing(dir), new File(dir, "out"), "arm64-v8a"));
        assertEquals(0, PluginNativeLoader.extractLibsFromApk(null, new File(dir, "out"), "arm64-v8a"));
    }

    /** Named failure text: library + absolute path + reason + fallback hint. */
    @Test
    public void libLoadExceptionNamesLibraryPathAndReason() {
        PluginNativeLoader.LibLoadException error =
                new PluginNativeLoader.LibLoadException("OCR 插件库加载失败：libppocr.so（路径=/x/libppocr.so）：文件不存在");
        assertTrue(error.getMessage().contains("libppocr.so"));
        assertTrue(error.getMessage().contains("/x/libppocr.so"));
        assertTrue(error.getMessage().contains("文件不存在"));
        assertTrue(error instanceof IllegalStateException);
    }

    /** Source pin: load() uses absolute-path System.load, deps-first API and never caches failure. */
    @Test
    public void sourcePinsForAbsolutePathLoad() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("src/main/java/com/stardust/autojs/plugin/PluginNativeLoader.java")), "UTF-8");
        assertTrue("must use System.load(absolute path)", source.contains("System.load(lib.getAbsolutePath())"));
        assertFalse("must not use loadLibrary", source.contains("System.loadLibrary("));
        assertTrue("staging falls back to apk zip", source.contains("extractLibsFromApk("));
        assertTrue("READY marker written", source.contains("READY"));
    }

    /** task-146 (W2 design): models extraction is pure logic - synthetic plugin APK zip. */
    @Test
    public void extractPluginModelsCopiesFiveFilesByteIdentical() throws Exception {
        File dir = Files.createTempDirectory("pluginmodels").toFile();
        File apk = modelsApk(dir, 5);
        File target = new File(dir, "models");
        assertEquals(5, PluginNativeLoader.extractModelsFromApk(apk, target));
        for (int i = 0; i < 5; i++) {
            File file = new File(target, "model" + i + ".bin");
            assertTrue("model" + i + " must exist", file.isFile());
            assertEquals("body-" + i, new String(Files.readAllBytes(file.toPath()), "UTF-8"));
        }
        // idempotent: second run rewrites the same bytes and still reports 5, without throwing
        assertEquals(5, PluginNativeLoader.extractModelsFromApk(apk, target));
        assertEquals("body-0", new String(Files.readAllBytes(new File(target, "model0.bin").toPath()), "UTF-8"));
    }

    /** Missing apk / null => 0 (never throws); a partial set copies what is there. */
    @Test
    public void extractPluginModelsHandlesMissingAndPartial() throws Exception {
        File dir = Files.createTempDirectory("pluginmodels2").toFile();
        assertEquals(0, PluginNativeLoader.extractModelsFromApk(new File(dir, "nope.apk"), new File(dir, "m1")));
        assertEquals(0, PluginNativeLoader.extractModelsFromApk(null, new File(dir, "m2")));
        File apk = modelsApk(dir, 4);
        assertEquals("4 of 5 present: copies 4 (completeness is PPOcr.prepareModels job)", 4,
                PluginNativeLoader.extractModelsFromApk(apk, new File(dir, "m3")));
    }

    /** source pin: prefix, ../ guard, public models getter, staging hook. */
    @Test
    public void modelsExtractionPins() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("src/main/java/com/stardust/autojs/plugin/PluginNativeLoader.java")), "UTF-8");
        assertTrue("prefix", source.contains("assets/ocr/models/"));
        assertTrue("../ guard", source.contains("relative.contains(\"..\")"));
        assertTrue("public models dir getter", source.contains("public static File modelsDirOrNull(Context host, String pluginPackage, int versionCode)"));
        assertTrue("staging stages models", source.contains("extractModelsFromApk(pluginApk(host, pluginPackage), new File(dir, \"models\"))"));
    }

    private static File modelsApk(File dir, int count) throws Exception {
        File apk = new File(dir, "models-" + count + ".apk");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            for (int i = 0; i < count; i++) {
                zip.putNextEntry(new ZipEntry("assets/ocr/models/model" + i + ".bin"));
                zip.write(("body-" + i).getBytes("UTF-8"));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("assets/ocr/models/../../evil.so"));
            zip.write("evil".getBytes("UTF-8"));
            zip.closeEntry();
        }
        return apk;
    }
}