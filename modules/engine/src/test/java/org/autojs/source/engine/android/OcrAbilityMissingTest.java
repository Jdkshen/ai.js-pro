package org.autojs.source.engine.android;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * task-99：① node24 资源缺失时诊断降级（源码钉：两个 resolver 都 runCatching+getOrNull；absent 只在两者都 null 时出现；
 * 有资源时字段为 present ⇒ 逐字不变）；② OCR 库不在包内时给**点名**错误（用真异常对象测，纯 JVM，不依赖 APK）。
 */
public class OcrAbilityMissingTest {

    @Test
    public void classifiesMissingAbility() {
        assertTrue(Node24HostBridge.isOcrAbilityMissing(
                new UnsatisfiedLinkError("dlopen failed: library \"libppocr.so\" not found")));
        assertTrue(Node24HostBridge.isOcrAbilityMissing(new NoClassDefFoundError("com/x/PPOcr")));
        assertTrue(Node24HostBridge.isOcrAbilityMissing(
                new RuntimeException("wrapper", new UnsatisfiedLinkError("libmlkit_google_ocr_pipeline.so"))));
    }

    @Test
    public void doesNotMisclassifyOtherFailures() {
        assertFalse(Node24HostBridge.isOcrAbilityMissing(new IllegalArgumentException("backend 不支持：foo")));
        assertFalse(Node24HostBridge.isOcrAbilityMissing(new IllegalStateException("图片读取失败：/tmp/x.png")));
    }

    @Test
    public void diagnosticsDegradesOnlyWhenBothResolversAreNull() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "../../apps/app/src/main/java/com/jdkshen/aijspro/mcp/McpEngineDiagnostics.kt")),
                StandardCharsets.UTF_8);
        assertTrue("两个 resolver 必须 runCatching+getOrNull（缺失不抛）",
                source.contains("private fun resolveRuntimeDirectory(context: Context): File? = runCatching {")
                        && source.contains("private fun resolveToolsDirectory(context: Context): File? = runCatching {"));
        assertTrue("absent 只在两者都 null 时",
                source.contains("input.runtimeDirectoryPath == null && input.toolsDirectoryPath == null"));
        assertTrue("有一份资源即 present", source.contains("\"absent(资源未随包)\" else \"present\""));
    }

    /**
     * task-105：**同一句点名文案**必须在三引擎三处逐字出现（Rhino __ocr__.js / QuickJS qjs-plugins.js /
     * Node24 node24-modules/ocr.js），且与 Java 常量 OcrAvailability.NOT_PACKAGED_MESSAGE 同源。
     */
    @Test
    public void allThreeEnginesShareTheSameNamedMessage() throws Exception {
        String java = read("src/main/java/com/stardust/autojs/runtime/api/OcrAvailability.java");
        assertTrue("Java 常量存在", java.contains("NOT_PACKAGED_MESSAGE"));
        assertTrue("Java 判据存在", java.contains("public static boolean isAbilityMissing(Throwable failure)"));
        assertTrue("handleOcr 用共享常量",
                read("src/main/java/org/autojs/source/engine/android/Node24HostBridge.java")
                        .contains("com.stardust.autojs.runtime.api.OcrAvailability.NOT_PACKAGED_MESSAGE"));
        String prefix = "OCR 能力未随包（optimization.removeOcr=true）：libppocr.so / libmlkit* 不在包内；";
        for (String path : new String[] { "src/main/assets/modules/__ocr__.js",
                "src/main/assets/modules/qjs-plugins.js", "src/main/assets/node24/modules/ocr.js" }) {
            String source = read(path);
            assertTrue(path + " 必须带同一句点名文案", source.contains(prefix));
            assertTrue(path + " 用共享常量名", source.contains("OCR_NOT_PACKAGED"));
            assertTrue(path + " 不得把 UnsatisfiedLinkError 直接抛给脚本",
                    !source.contains("throw new UnsatisfiedLinkError"));
        }
    }

    /** 默认路径逐字不变：非缺失型失败仍走原有文案（未被点名错误替换）。 */
    @Test
    public void normalFailurePathStaysUntouched() throws Exception {
        String source = read("src/main/java/org/autojs/source/engine/android/Node24HostBridge.java");
        assertTrue("其它失败仍走 ocrFailureMessage", source.contains("ocrFailureMessage(failure)"));
        assertTrue("判据只在缺失时命中", source.contains("OcrAvailability.isAbilityMissing(failure)"));
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relativePath)), StandardCharsets.UTF_8);
    }
}