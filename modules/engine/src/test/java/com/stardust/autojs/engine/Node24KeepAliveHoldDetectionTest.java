package com.stardust.autojs.engine;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * bugs 6.1 回归：keepRunning 的保活判定不能只看入口文件。
 *
 * <p>入口源码扫描保留为启动期预判，工程源码扫描补上 require 的模块；
 * node_modules 等第三方目录必须跳过，扫描失败不能影响脚本启动。</p>
 */
public class Node24KeepAliveHoldDetectionTest {

    @Test
    public void entryMarkerIsStillDetected() {
        assertTrue(Node24JavaScriptEngine.containsKeepAliveRequest("$autojs.keepRunning();"));
        assertTrue(Node24JavaScriptEngine.containsKeepAliveRequest("require('ui')"));
        assertTrue(Node24JavaScriptEngine.containsKeepAliveRequest("\"ui nodejs\";"));
        assertFalse(Node24JavaScriptEngine.containsKeepAliveRequest("console.log('plain')"));
        assertFalse(Node24JavaScriptEngine.containsKeepAliveRequest(null));
        assertFalse(Node24JavaScriptEngine.containsKeepAliveRequest(""));
    }

    @Test
    public void requiredModuleInsideProjectRequestingKeepAliveIsDetected() throws Exception {
        Path project = Files.createTempDirectory("node24-keepalive-scan-");
        try {
            Files.createDirectories(project.resolve("lib"));
            Files.write(project.resolve("project.json"),
                    "{\"name\":\"probe\",\"type\":\"node\"}".getBytes(StandardCharsets.UTF_8));
            Path entry = project.resolve("main.js");
            Files.write(entry, "require('./lib/module')().start();".getBytes(StandardCharsets.UTF_8));
            Files.write(project.resolve("lib").resolve("module.js"),
                    "module.exports = function () { return { start: function () { $autojs.keepRunning(); } }; };"
                            .getBytes(StandardCharsets.UTF_8));
            // 入口源码本身看不到 keepRunning：必须靠工程源码扫描命中。
            assertFalse(Node24JavaScriptEngine.containsKeepAliveRequest(
                    new String(Files.readAllBytes(entry), StandardCharsets.UTF_8)));
            assertTrue("require 的模块里 keepRunning 必须被判定为保活",
                    Node24JavaScriptEngine.shouldHoldAlive(
                            new String(Files.readAllBytes(entry), StandardCharsets.UTF_8),
                            entry.toString()));
        } finally {
            deleteTree(project);
        }
    }

    @Test
    public void ordinaryProjectStaysOrdinary() throws Exception {
        Path project = Files.createTempDirectory("node24-keepalive-scan-plain-");
        try {
            Files.write(project.resolve("project.json"),
                    "{\"name\":\"probe\",\"type\":\"node\"}".getBytes(StandardCharsets.UTF_8));
            Path entry = project.resolve("main.js");
            Files.write(entry, "console.log('one shot');".getBytes(StandardCharsets.UTF_8));
            Files.write(project.resolve("worker.js"),
                    "console.log('no keepalive here');".getBytes(StandardCharsets.UTF_8));
            assertFalse("没有任何 keepRunning 的工程必须保持普通脚本语义",
                    Node24JavaScriptEngine.shouldHoldAlive(
                            "console.log('one shot');", entry.toString()));
        } finally {
            deleteTree(project);
        }
    }

    @Test
    public void nodeModulesAndBuildOutputsAreIgnored() throws Exception {
        Path project = Files.createTempDirectory("node24-keepalive-scan-vendor-");
        try {
            Files.createDirectories(project.resolve("node_modules").resolve("dep"));
            Files.createDirectories(project.resolve("build"));
            Files.write(project.resolve("main.js"), "start();".getBytes(StandardCharsets.UTF_8));
            Files.write(project.resolve("node_modules").resolve("dep").resolve("index.js"),
                    "$autojs.keepRunning();".getBytes(StandardCharsets.UTF_8));
            Files.write(project.resolve("build").resolve("bundle.js"),
                    "$autojs.keepRunning();".getBytes(StandardCharsets.UTF_8));
            assertFalse("第三方/产物目录里的 keepRunning 不能改变保活判定",
                    Node24JavaScriptEngine.shouldHoldAlive("start();",
                            project.resolve("main.js").toString()));
        } finally {
            deleteTree(project);
        }
    }

    @Test
    public void missingFilenameIsSafe() {
        assertFalse(Node24JavaScriptEngine.shouldHoldAlive("start();", null));
        assertFalse(Node24JavaScriptEngine.shouldHoldAlive("start();", ""));
        assertFalse(Node24JavaScriptEngine.shouldHoldAlive(null, "/definitely/missing/dir/main.js"));
    }

    private static void deleteTree(Path root) {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
