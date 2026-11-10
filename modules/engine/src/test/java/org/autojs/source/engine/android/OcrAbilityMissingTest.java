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
}