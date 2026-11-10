package com.jdkshen.aijspro.flutterpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * task-100 的**回归钉**：真机上「大小构成」行不显示的根因是 **PocBridge 的路由白名单没登记 apkBreakdown**
 * （Dart 调用被当未知方法拒掉、异常被静默吞掉）。这个测试把三件事钉住：
 * ① 路由必须登记在 PocIntegration 那一组里；② 桥失败必须留 logcat；③ 桶的展示顺序 = 验收串的顺序。
 */
public class ApkBreakdownRoutingPinTest {

    private static String read(String relative) throws Exception {
        Path path = Paths.get(relative);
        if (!Files.exists(path)) path = Paths.get("apps/app/" + relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void pocBridgeRoutesApkBreakdownToPocIntegration() throws Exception {
        String source = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/PocBridge.java");
        int caseAt = source.indexOf("case \"apkBreakdown\":");
        assertTrue("PocBridge 必须登记 apkBreakdown（否则被当未知方法拒掉）", caseAt > 0);
        int groupStart = source.indexOf("case \"openBuildPage\":");
        int dispatch = source.indexOf("return PocIntegration.execute(activity, method, args);");
        assertTrue("openBuildPage 组必须存在", groupStart > 0 && dispatch > groupStart);
        assertTrue("apkBreakdown 必须落在 PocIntegration 这一组里（组内、派发之前）",
                caseAt > groupStart && caseAt < dispatch);
    }

    @Test
    public void bridgeLogsFailuresInsteadOfSwallowingThem() throws Exception {
        String source = read("src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/ApkBreakdownBridge.java");
        assertTrue("失败必须留一条 logcat", source.contains("apkBreakdown 失败: "));
        assertTrue("必须真的写日志（不是只拼字符串）", source.contains("Log.w(TAG,"));
    }

    @Test
    public void bucketOrderMatchesTheAcceptedDisplayString() {
        assertEquals("验收串顺序：引擎与模板 · OpenCV · 内建 OCR · 你的工程",
                Arrays.asList(ApkSizeBreakdown.BUCKET_ENGINE, ApkSizeBreakdown.BUCKET_OPENCV,
                        ApkSizeBreakdown.BUCKET_OCR, ApkSizeBreakdown.BUCKET_PROJECT),
                Arrays.asList(ApkSizeBreakdown.BUCKET_ORDER));
    }
}
