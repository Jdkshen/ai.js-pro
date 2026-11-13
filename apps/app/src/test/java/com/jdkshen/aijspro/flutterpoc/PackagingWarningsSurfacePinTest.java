package com.jdkshen.aijspro.flutterpoc;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** task-110：打包警告从「只有 logcat」搬到结构化返回的桥侧钉子。 */
public class PackagingWarningsSurfacePinTest {

    private static String read(String relative) throws Exception {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("apps/app/" + relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void statusJsonAlwaysCarriesWarnings() throws Exception {
        String source = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocPackaging.java");
        assertTrue("状态 JSON 必须带 warnings（空数组也要带）",
                source.contains("value.put(\"warnings\", new JSONArray(warnings));"));
        assertTrue("Job 必须收集 warnings", source.contains("final List<String> warnings = new ArrayList<>();"));
        assertTrue("warning 上限要有（避免刷屏）", source.contains("warnings.size() < 50"));
    }

    @Test
    public void warningsComeFromTheBuilderReportReadOnly() throws Exception {
        String source = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocPackaging.java");
        assertTrue("必须从打包器的结构化报告里取（不另造真值）", source.contains("builder.getPackagingReport()"));
        assertTrue("字段名是 W2 定的 packagingWarnings", source.contains("optJSONArray(\"packagingWarnings\")"));
        assertTrue("读取失败要可见（不静默）", source.contains("打包提示读取失败："));
    }

    @Test
    public void apkBuilderStillPublishesWarningsInTheReport() throws Exception {
        String source = read("src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java");
        // 2026-11-13 更新：打包器改用常量键（PackagingReportFields.PACKAGING_WARNINGS）而不是字面量，
        // 避免「写端改名、读端还按字符串找」的漂移。断言跟着钉常量用法。
        assertTrue("报告里的 packagingWarnings 由打包器写入（这里只读消费）",
                source.contains("report(PackagingReportFields.PACKAGING_WARNINGS"));
        assertTrue("常量本身要等于 packagingWarnings（读端 Dart 按这个键取）",
                read("src/main/java/com/jdkshen/aijspro/autojs/build/PackagingReportFields.kt")
                        .contains("PACKAGING_WARNINGS"));
    }
}
