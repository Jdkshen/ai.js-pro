package com.stardust.autojs.engine.node24;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * task-246 R02：Widget 多层别名共享同一个根 View 时，内层/外层各自的属性接收者。
 *
 * <p>契约在子 Node 进程里对着 production {@code assets/node24/ui.js} 跑（
 * {@code node24-widget-alias-receiver-harness.js}）；宿主 inflater 的调用顺序（
 * {@link NodeUiWidgetInflater} 的 ArrayDeque frames 与 rootViewCreated 顺序）由 harness 忠实模拟，
 * 因此这里不需要真机或 Android 框架即可校验属性路由：
 *
 * <ul>
 *   <li>三层别名共享一个根：{@code Widget.of}/extras 归属最外层；</li>
 *   <li>同名/不同名属性各自命中声明它的那一层，未声明时落到原生 View；</li>
 *   <li>事件回调内通过 {@code widget.view.attr()} 读写仍命中自己的接收者；</li>
 *   <li>release（停止）后阶段回调失效但接收者不串台；重新 inflate 到新根后旧接收者不受影响。</li>
 * </ul>
 *
 * <p>没有 Node 可执行文件时跳过（与 Node24SensorsCwdTest 同一策略）；有 Node 但契约失败是硬失败。
 */
public class NodeUiWidgetAliasReceiverTest {

    @Test
    public void sharedRootAliasesKeepPerWidgetAttributeReceivers() throws Exception {
        File ui = find("src/main/assets/node24/ui.js", "modules/engine/src/main/assets/node24/ui.js");
        File harness = find("src/test/java/com/stardust/autojs/engine/node24/node24-widget-alias-receiver-harness.js",
                "modules/engine/src/test/java/com/stardust/autojs/engine/node24/node24-widget-alias-receiver-harness.js");
        assertNotNull("找不到 production ui.js（测试工作目录=" + new File(".").getAbsolutePath() + "）", ui);
        assertNotNull("找不到 node24-widget-alias-receiver-harness.js", harness);

        File node = findNode();
        Assume.assumeTrue("未找到 node 可执行文件；设置 NODE_BINARY 后运行此契约测试", node != null);

        Path log = Files.createTempFile("node24-widget-alias-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(node.getPath(), harness.getAbsolutePath(), ui.getAbsolutePath());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            Process process = builder.start();
            boolean finished = process.waitFor(90, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            int exit = finished ? process.exitValue() : -1;
            assertEquals("Node Widget alias receiver contract failed (exit=" + exit + "):\n" + output, 0, exit);
            assertTrue("missing OK marker:\n" + output,
                    output.contains("OK node24 Widget alias receiver contract"));
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

    /** 保留给后续 R02 扩展用例（临时目录清理）。 */
    @SuppressWarnings("unused")
    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 清理失败不能掩盖契约结果。
                }
            });
        } catch (Exception ignored) {
        }
    }
}
