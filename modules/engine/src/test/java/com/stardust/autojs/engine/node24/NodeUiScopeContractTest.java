package com.stardust.autojs.engine.node24;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * task-254：Node UI 的 JS 层契约（bugs 2.1/2.4/2.5/2.9）。
 *
 * <p>契约在子 Node 进程里对着 production {@code assets/node24/ui.js} 跑
 * （{@code node24-ui-scope-contract-harness.js}）。假宿主实现 production 的宿主串协议
 * （inflate/findViewById/viewCommand/bindClick/pollEvents）与新增视图协议动作
 * （addView/removeView/getParent/setOnTextChangedListener/setOnItemSelectedListener），
 * 因此不需要真机或 Android 框架即可校验：
 *
 * <ul>
 *   <li>2.4 {@code ui.inflate(数字资源 id / ui.R.layout.x / 名字)}：数字解析成布局，不再出现
 *       「找不到布局：2113929219」；无法解析时区分「只支持 XML/资源 id」与「布局不存在」；</li>
 *   <li>2.5 inflate 返回值参与真实视图树：{@code addView(child)} 后 {@code getParent()/
 *       getChildAt()} 是宿主真实 View；旧宿主缺协议时明确报错而不是静默；</li>
 *   <li>2.9 {@code setOnClickListener/setOnCheckedChangeListener/setOnItemSelectedListener}
 *       经宿主事件回路真正回调；旧宿主缺事件通道时轮询 {@code isChecked()} 兜底；</li>
 *   <li>2.1 {@code on('text_change')}：真实 TextWatcher + 旧宿主 {@code getText()} 轮询兜底。</li>
 * </ul>
 *
 * <p>已知边界（真机 device 契约覆盖，不在本 JVM 契约内）：假宿主不实现 prelude 的 Java 成员反射，
 * check/item_selected 两参数接口的完整回调序列只能在真机验证。
 *
 * <p>没有 Node 可执行文件时跳过（与 {@link NodeUiWidgetAliasReceiverTest} 同一策略）；
 * 有 Node 但契约失败是硬失败。
 */
public class NodeUiScopeContractTest {

    @Test
    public void inflateIdsTreeTreeAndViewEventsHold() throws Exception {
        File ui = find("src/main/assets/node24/ui.js", "modules/engine/src/main/assets/node24/ui.js");
        File harness = find("src/test/java/com/stardust/autojs/engine/node24/node24-ui-scope-contract-harness.js",
                "modules/engine/src/test/java/com/stardust/autojs/engine/node24/node24-ui-scope-contract-harness.js");
        assertNotNull("找不到 production ui.js（测试工作目录=" + new File(".").getAbsolutePath() + "）", ui);
        assertNotNull("找不到 node24-ui-scope-contract-harness.js", harness);

        File node = findNode();
        Assume.assumeTrue("未找到 node 可执行文件；设置 NODE_BINARY 后运行此契约测试", node != null);

        Path log = Files.createTempFile("node24-ui-scope-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(node.getPath(), harness.getAbsolutePath(), ui.getAbsolutePath());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            Process process = builder.start();
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            int exit = finished ? process.exitValue() : -1;
            assertEquals("Node UI scope contract failed (exit=" + exit + "):\n" + output, 0, exit);
            assertTrue("missing OK marker:\n" + output,
                    output.contains("OK node24 UI scope contract"));
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static File find(String... relativeCandidates) {
        File base = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int depth = 0; depth < 6 && base != null; depth++, base = base.getParentFile()) {
            for (String relative : relativeCandidates) {
                File candidate = new File(base, relative);
                if (candidate.isFile()) {
                    return candidate.getAbsoluteFile();
                }
            }
        }
        for (String relative : relativeCandidates) {
            File candidate = new File(relative);
            if (candidate.isFile()) {
                return candidate.getAbsoluteFile();
            }
        }
        return null;
    }

    private static File findNode() {
        String configured = System.getenv("NODE_BINARY");
        if (configured != null && !configured.trim().isEmpty()) {
            File explicit = new File(configured);
            return explicit.isFile() ? explicit.getAbsoluteFile() : new File(configured);
        }
        try {
            Process probe = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            boolean done = probe.waitFor(10, TimeUnit.SECONDS);
            if (!done) {
                probe.destroyForcibly();
                return null;
            }
            return probe.exitValue() == 0 ? new File("node") : null;
        } catch (Exception unavailable) {
            return null;
        }
    }
}
