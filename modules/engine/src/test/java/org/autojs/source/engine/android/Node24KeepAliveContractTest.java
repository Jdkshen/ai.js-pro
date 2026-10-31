package org.autojs.source.engine.android;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * bugs 6.1/6.2/6.3 回归：keepRunning 运行时标记、引用计数保活、宿主定时器兜底。
 *
 * <ul>
 *   <li>6.1 keepRunning 在 require 的模块里调用同样保活（宿主按运行时标记，而不是入口源码扫描）；</li>
 *   <li>6.2 保活是引用计数：ui.js 的 cleanupUiLifecycle() → $autojs.stopRunning() 只释放它自己那次，
 *       用户脚本显式申请的 keepRunning 继续有效，脚本不会在界面销毁时自退；</li>
 *   <li>6.3 纯 Java 回调上下文里的 setTimeout 走宿主定时器兜底，一定执行。</li>
 * </ul>
 *
 * <p>JS 半边在子 Node 进程里跑真实 production prelude（{@code node24-keepalive-contract.js}）；
 * 没有 Node 可执行文件时跳过，有 Node 而契约失败是硬失败。</p>
 */
public class Node24KeepAliveContractTest {

    @Test
    public void productionPreludeKeepsRunningAtRuntime() throws Exception {
        File prelude = find("src/main/assets/node24/prelude.js",
                "modules/engine/src/main/assets/node24/prelude.js");
        File harness = find("src/test/js/node24-keepalive-contract.js",
                "modules/engine/src/test/js/node24-keepalive-contract.js");
        assertNotNull("找不到 production prelude.js（测试工作目录="
                + new File(".").getAbsolutePath() + "）", prelude);
        assertNotNull("找不到 node24-keepalive-contract.js", harness);

        File node = findNode();
        Assume.assumeTrue("未找到 node 可执行文件；设置 NODE_BINARY 后运行此契约测试", node != null);

        Path work = Files.createTempDirectory("node24-keepalive-contract-");
        Path log = Files.createTempFile("node24-keepalive-contract-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    node.getPath(), harness.getAbsolutePath(), prelude.getAbsolutePath(), work.toString());
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
            assertEquals("Node keepalive contract failed (exit=" + exit + "):\n" + output, 0, exit);
            assertTrue("missing OK marker:\n" + output,
                    output.contains("OK node24 keepalive contract"));
        } finally {
            deleteTree(work);
            Files.deleteIfExists(log);
        }
    }

    @Test
    public void keepAliveRequestKeepsRuntimeSemanticsAndLegacyCompatibility() throws Exception {
        // 新 prelude：running + holds。
        JSONObject running = new JSONObject().put("running", true).put("holds", 2);
        assertTrue(Node24HostBridge.keepAliveRequestRunning(running));
        assertEquals(2, Node24HostBridge.keepAliveRequestHolds(running));

        JSONObject released = new JSONObject().put("running", false).put("holds", 0);
        assertFalse(Node24HostBridge.keepAliveRequestRunning(released));
        assertEquals(0, Node24HostBridge.keepAliveRequestHolds(released));

        // 旧 prelude：只有 running，缺省按 1 个引用归一，不能降级成未保活。
        JSONObject legacyRunning = new JSONObject().put("running", true);
        assertTrue(Node24HostBridge.keepAliveRequestRunning(legacyRunning));
        assertEquals(1, Node24HostBridge.keepAliveRequestHolds(legacyRunning));

        // running 缺省 true（旧桥只发 kind 的情形）。
        JSONObject empty = new JSONObject();
        assertTrue(Node24HostBridge.keepAliveRequestRunning(empty));
        assertEquals(1, Node24HostBridge.keepAliveRequestHolds(empty));

        // 明确停止必须释放，不能被当成「默认保活」。
        JSONObject stopped = new JSONObject().put("running", false);
        assertFalse(Node24HostBridge.keepAliveRequestRunning(stopped));
        assertEquals(0, Node24HostBridge.keepAliveRequestHolds(stopped));

        assertFalse(Node24HostBridge.keepAliveRequestRunning(null));
        assertEquals(0, Node24HostBridge.keepAliveRequestHolds(null));
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
            if (explicit.isFile()) {
                return explicit.getAbsoluteFile();
            }
            return new File(configured);
        }
        try {
            Process probe = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true).start();
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

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理不能掩盖契约结果。
                }
            });
        } catch (IOException ignored) {
        }
    }
}
