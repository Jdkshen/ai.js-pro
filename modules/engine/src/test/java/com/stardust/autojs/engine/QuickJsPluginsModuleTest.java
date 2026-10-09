package com.stardust.autojs.engine;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

/**
 * QuickJS 侧的 `$plugins`（modules/qjs-plugins.js）在 JVM 里跑一遍：
 * 真正的 QuickJS 要整机才能验，但「取插件对象 → require 入口 index.js → 用插件对象调用它」这段
 * 胶水逻辑是纯 JS，用 Rhino + 假宿主就能钉住（手法同 PluginsMlkitCompatibilityTest）。
 *
 * <p>假宿主按 QuickJS bootstrap 的真实样子摆：`globalThis` 上挂 `Java` / `engines` / `require` / `$ocr`。
 */
public class QuickJsPluginsModuleTest {

    @Test
    public void loadsPluginEntryWithOfficialPluginObjectShape() throws Exception {
        assertEquals("1.2.3:scripts:/cache/plugin-scripts/com.example.plugin/index.js",
                evaluate("var api = $plugins.load('com.example.plugin');"
                        + "api.version + ':' + api.assetsScriptDir + ':' + api.mainScriptPath;"));
    }

    @Test
    public void entryScriptReceivesTheUnwrappedSdkInstance() throws Exception {
        assertEquals("sdk", evaluate("String($plugins.load('com.example.plugin').instance.tag);"));
    }

    @Test
    public void resolvesTheHostBridgeForTheRunningEngine() throws Exception {
        assertEquals("com.example.plugin@7",
                evaluate("$plugins.load('com.example.plugin'); String(loaded[0]);"));
    }

    @Test
    public void missingPluginFailsLoudly() throws Exception {
        assertEquals("true", evaluate("var failed = false;"
                + "try { $plugins.load('not.installed'); }"
                + "catch (e) { failed = String(e).indexOf('not.installed') >= 0; }"
                + "String(failed);"));
    }

    @Test
    public void absentOfficialMlkitPluginFallsBackToOfflineOcr() throws Exception {
        assertEquals("mlkit:9", evaluate("var MLKit = $plugins.load('org.autojs.autojspro.plugin.mlkit.ocr');"
                + "new MLKit().detect({ id: 9 }).text;"));
    }

    @Test
    public void registersItselfUnderBothModuleNames() throws Exception {
        assertEquals("function,function,function", evaluate(
                "[typeof $plugins.load, typeof plugins.load, typeof __aiModernModules.plugins.load].join(',');"));
    }

    private static String evaluate(String script) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("src/main/assets/modules/qjs-plugins.js")),
                StandardCharsets.UTF_8);
        Context cx = Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            cx.evaluateString(scope, setup(), "setup", 1, null);
            cx.evaluateString(scope, source, "qjs-plugins", 1, null);
            return Context.toString(cx.evaluateString(scope, script, "test", 1, null));
        } finally {
            Context.exit();
        }
    }

    /** 假宿主：Java 互操作 / engines / require / $ocr，外加记录调用顺序的 loaded。 */
    private static String setup() {
        return String.join("\n",
                "var loaded = [];",
                "var sdkInstance = { tag: 'sdk', version: '1.2.3' };",
                "var engines = { myEngine: function () { return { id: 7 }; } };",
                "var __aiModernModules = {};",
                "function require(path) {",
                "  loaded.push(String(path));",
                "  return function (plugin) {",
                "    return {",
                "      version: plugin.unwrap().version,",
                "      assetsScriptDir: plugin.getAssetsScriptDir(),",
                "      mainScriptPath: plugin.getMainScriptPath(),",
                "      instance: plugin.unwrap()",
                "    };",
                "  };",
                "}",
                "function fakePluginHandle(packageName) {",
                "  return {",
                "    getMainScriptPath: function () {",
                "      return '/cache/plugin-scripts/' + packageName + '/index.js';",
                "    },",
                "    getAssetsScriptDir: function () { return 'scripts'; },",
                "    unwrap: function () { return sdkInstance; }",
                "  };",
                "}",
                "function fakeHost(id) {",
                "  return {",
                "    loadPlugin: function (packageName) {",
                "      if (packageName !== 'com.example.plugin') {",
                "        throw new Error('PluginLoadException: NameNotFoundException: ' + packageName);",
                "      }",
                "      loaded.push(packageName + '@' + id);",
                "      return fakePluginHandle(packageName);",
                "    }",
                "  };",
                "}",
                "function fakeExtensions() { return { forEngine: fakeHost }; }",
                "var Java = {",
                "  type: function (name) {",
                "    if (name !== 'com.stardust.autojs.engine.QuickJsApiExtensions') {",
                "      throw new Error('unexpected host class: ' + name);",
                "    }",
                "    return fakeExtensions();",
                "  }",
                "};",
                "function fakeOcrSession(options) {",
                "  return {",
                "    detect: function (image) { return { text: options.backend + ':' + image.id }; },",
                "    release: function () {},",
                "    isReleased: function () { return false; }",
                "  };",
                "}",
                "var $ocr = { create: fakeOcrSession };");
    }
}
