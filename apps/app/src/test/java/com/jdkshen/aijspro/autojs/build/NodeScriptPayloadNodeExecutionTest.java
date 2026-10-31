package com.jdkshen.aijspro.autojs.build;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/**
 * 用真实的 Node 执行等级 2 的入口载荷，验证「源码不可读但行为完全一致」。
 *
 * <p>这是本任务里最接近真机的一步：{@code apps/inrt} 里的 Node 24 会拿到的就是这里生成的
 * 文本，而 JVM 单测能证明的只有「文本可逆」；只有真的跑一遍才能证明
 * {@code require('./x')} 相对路径、{@code var} 提升、{@code "ui";} 序言都还在。
 *
 * <p>找不到 {@code node} 可执行文件时用 {@link Assume} 跳过，不把「本机没装 Node」
 * 变成测试失败。真正跑起来但输出不对，就是硬失败。
 */
public class NodeScriptPayloadNodeExecutionTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void runsWrappedPayloadWithRelativeRequireAndKeepsVarHoisting() throws Exception {
        Assume.assumeTrue("本机没有 node，跳过真实执行验证", nodeAvailable());

        String source = ""
                + "// @engine node24\n"
                + "\"ui\";\n"
                // 相对 require 必须由解包后的脚本自己解析；require 来自 CJS 模块作用域，
                // eval 保持在其作用域里，所以这条在加载器下必须照样成立。
                + "var dep = require('./dep');\n"
                + "var answer = hoisted() + dep.value;\n"
                + "function hoisted() { return 40; }\n"
                + "console.log('answer=' + answer);\n"
                + "console.log('mode=' + (typeof __filename === 'string' ? 'file' : 'none'));\n";
        String wrapped = NodeScriptPayload.wrap(source, "main.js");

        File dir = tmp.newFolder("project");
        Files.write(new File(dir, "dep.js").toPath(),
                "exports.value = 2;\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE);
        File main = new File(dir, "runner.js");
        // 运行时实际读到的就是这份文本：写文件再让 node 执行。
        Files.write(main.toPath(), wrapped.getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE);

        String output = runNode(dir, main.getAbsolutePath());
        assertTrue("载荷必须能真实执行，输出=\n" + output, output.contains("answer=42"));
        assertTrue("必须仍然是文件模块（__filename 可用），输出=\n" + output,
                output.contains("mode=file"));
    }

    @Test public void wrappedPayloadStillFailsOnSyntaxErrorLikeTheOriginalSource() throws Exception {
        Assume.assumeTrue("本机没有 node，跳过真实执行验证", nodeAvailable());
        String source = "function ( { broken;\n";
        String wrapped = NodeScriptPayload.wrap(source, "main.js");
        File dir = tmp.newFolder("broken");
        File main = new File(dir, "runner.js");
        Files.write(main.toPath(), wrapped.getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE);
        String output = runNodeExpectingFailure(dir, main.getAbsolutePath());
        assertTrue("语法错误应当照旧暴露给用户，输出=\n" + output,
                output.contains("SyntaxError") || output.contains("Unexpected"));
    }

    // ------------------------------------------------------------------ 内部

    private boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder(nodeCommand(), "--version")
                    .redirectErrorStream(true).start();
            boolean done = process.waitFor(20, TimeUnit.SECONDS);
            process.destroy();
            return done && process.exitValue() == 0;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static String nodeCommand() {
        return isWindows() ? "node.exe" : "node";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private String runNode(File workingDir, String scriptPath) throws Exception {
        return run(workingDir, Arrays.asList(nodeCommand(), scriptPath));
    }

    private String runNodeExpectingFailure(File workingDir, String scriptPath) throws Exception {
        // 语法错误时 node 以非 0 退出，但 stdout/stderr 都会进同一份 output，断言看内容即可。
        return run(workingDir, Arrays.asList(nodeCommand(), scriptPath));
    }

    private String run(File workingDir, List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(workingDir);
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        // 不让真实执行验证受本机 NODE_OPTIONS/代理影响。
        environment.remove("NODE_OPTIONS");
        environment.put("NODE_NO_WARNINGS", "1");
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("node 执行超时");
        }
        return output.toString();
    }
}
