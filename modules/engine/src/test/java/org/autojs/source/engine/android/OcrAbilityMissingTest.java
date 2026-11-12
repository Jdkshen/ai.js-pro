package org.autojs.source.engine.android;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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
        // Lead 2026-11-12：主程序把 OCR 剥离成插件后，这句话的**首选解法改成「装插件」**
        // （旧文案让人「关掉开关重新打包」，在新默认下已不成立）。三份 JS 副本必须逐字一致。
        String prefix = "OCR 能力未随包：libppocr.so / libmlkit* 与识别模型不在包内（optimization.removeOcr=true，";
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

    /** task-107 反例：真实错误（参数/路径/用法）即使字面带上 libppocr/libmlkit 也**不得**被判成「未随包」。 */
    @Test
    public void realErrorsWithLibraryNamesAreNotMisclassified() {
        assertFalse(Node24HostBridge.isOcrAbilityMissing(
                new IllegalArgumentException("backend 不支持：libppocr")));
        assertFalse(Node24HostBridge.isOcrAbilityMissing(
                new IllegalStateException("图片读取失败：libppocr.so 不存在")));
        assertFalse(Node24HostBridge.isOcrAbilityMissing(
                new RuntimeException("dlopen 配置错误")));
        assertFalse(Node24HostBridge.isOcrAbilityMissing(
                new IllegalArgumentException("ocr 不支持的操作：libmlkit")));
        assertFalse(Node24HostBridge.isOcrAbilityMissing(
                new IllegalStateException("参数非法：backend=ppocr libppocr 路径=/x")));
    }

    /** 真缺失形状仍要命中：加载失败文案（含库名）或缺失型异常类型。 */
    @Test
    public void libraryLoadShapesAreStillClassified() {
        assertTrue(Node24HostBridge.isOcrAbilityMissing(new RuntimeException(
                "dlopen failed: library \"libppocr.so\" not found")));
        assertTrue(Node24HostBridge.isOcrAbilityMissing(
                new RuntimeException("java.lang.UnsatisfiedLinkError: 无法加载 libmlkit_google_ocr_pipeline.so")));
        assertTrue(Node24HostBridge.isOcrAbilityMissing(new NoClassDefFoundError("x")));
    }

    /** task-107：JS 侧三层防线——Node24 先排除参数类错误、QuickJS 只在加载形状上加点名句、Rhino 用能力探针不用文案。 */
    @Test
    public void scriptSidesGuardAgainstRewritingRealErrors() throws Exception {
        // Node24：先排除参数/用法类错误，再统一（不误伤真实错误）。
        String node = read("src/main/assets/node24/modules/ocr.js");
        assertTrue("Node24 先排除参数/用法类错误", node.contains("不支持|参数|backend|非法|必须是"));
        assertTrue("Node24 只在点名句或加载形状上统一", node.contains("未随包"));
        // QuickJS：形状判据（库名 + 加载形状），不是任意文案匹配。
        String quickjs = read("src/main/assets/modules/qjs-plugins.js");
        assertTrue("QuickJS 有共享形状判据（task-157 改名）", quickjs.contains("function ocrLooksLikeAbilityMissing(value)"));
        assertTrue("QuickJS 只在形状命中时加点名句", quickjs.contains("ocrLooksLikeAbilityMissing(ppocrError) ?"));
        // Rhino：断言**本意**——早退必须是「能力探针」，**不得**靠异常文案判错；字面形式可随实现演进。
        String rhino = read("src/main/assets/modules/__ocr__.js");
        assertTrue("Rhino 必须用能力探针（ppocrIsAvailable 或 nativeLibPresent）",
                rhino.contains("ppocrIsAvailable(") || rhino.contains("nativeLibPresent("));
        assertTrue("Rhino 不得靠异常文案判错",
                !(rhino.contains("catch") && rhino.contains("indexOf(") && rhino.contains("未随包")));
    }

    /** task-157 (P2-2 方案A)：共享块必须逐字相同（按标记切片），且常量行三处一致。 */
    @Test
    public void ocrAvailabilitySharedBlockIsIdentical() throws Exception {
        String rhino = read("src/main/assets/modules/__ocr__.js");
        String quickjs = read("src/main/assets/modules/qjs-plugins.js");
        String node = read("src/main/assets/node24/modules/ocr.js");
        String qBlock = sharedBlock(quickjs);
        String nBlock = sharedBlock(node);
        assertTrue("qjs 必须带共享块", qBlock.length() > 0);
        assertTrue("node24 必须带共享块", nBlock.length() > 0);
        assertEquals("两份带形状判据的共享块必须逐字相同", qBlock, nBlock);
        String qConst = constantLine(qBlock);
        assertEquals("常量行与 node24 不一致", qConst, constantLine(nBlock));
        assertEquals("常量行与 Rhino 不一致", qConst, constantLine(sharedBlock(rhino)));
    }

    /** task-157：防新增副本 —— 全仓含共享判据定义的文件数必须恰好 2（多一份即红）。 */
    @Test
    public void onlyTwoFilesDefineTheSharedPredicate() throws Exception {
        final String needle = "function ocrLooksLikeAbilityMissing";
        final int[] files = { 0 };
        final java.util.List<String> hits = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(
                java.nio.file.Paths.get("src/main/assets"))) {
            walk.filter(java.nio.file.Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".js"))
                    .filter(path -> {
                        try {
                            return java.nio.file.Files.size(path) < 4L * 1024L * 1024L;
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .forEach(path -> {
                        try {
                            String content = new String(java.nio.file.Files.readAllBytes(path),
                                    java.nio.charset.StandardCharsets.UTF_8);
                            if (content.contains(needle)) {
                                files[0]++;
                                hits.add(path.toString());
                            }
                        } catch (Exception ignored) {
                        }
                    });
        }        // task-157 覆盖边界（W2 复审建议）：本钉只扫 "src/main/assets" 下的 *.js（且跳过 >4MB 的文件）
        // ⇒ 若将来有人在**别的目录**新增第四份副本，这条断言**不会红**（属于已知边界，不是缺陷）。
        assertEquals("共享判据必须恰好被 2 个文件定义（扫描范围：src/main/assets/**/*.js，>4MB 跳过），多了就是新副本："
                + hits, 2, files[0]);
    }

    private static String sharedBlock(String source) {
        int start = source.indexOf("// >>> OCR-AVAILABILITY-BLOCK");
        int end = source.indexOf("// <<< OCR-AVAILABILITY-BLOCK");
        if (start < 0 || end < 0) {
            return "";
        }
        return source.substring(start, end + "// <<< OCR-AVAILABILITY-BLOCK".length());
    }

    private static String constantLine(String block) {
        for (String line : block.split("\n")) {
            if (line.contains("var OCR_NOT_PACKAGED =")) {
                return line.trim();
            }
        }
        return "";
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relativePath)), StandardCharsets.UTF_8);
    }
}