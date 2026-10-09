/**
 * 象棋引擎插件入口（Auto.js 插件约定的 `assets/scripts/index.js`）。
 *
 * 宿主加载流程（本仓库见 modules/engine/.../core/plugin/Plugin.java 与 assets/docs/plugins.html）：
 *   1. 按 AndroidManifest 里 `org.autojs.plugin.sdk.registry` 指向的类，反射调它的
 *      `loadDefault(宿主Context, 插件Context, runtime, scope)`；
 *   2. 把插件 `assets/scripts/` 拷到宿主 `cache/plugin-scripts/<包名>/`，`require` 本文件；
 *   3. 用第 1 步的对象调用下面导出的函数，**返回值就是 `plugins.load(包名)` 的结果**。
 *
 * 于是脚本侧得到一份「识别 + 算棋」能力包：
 *
 *   var xq = $plugins.load("com.jdkshen.xiangqi.plugin");
 *   xq.engine.available();                        // 插件自带引擎（不依赖宿主有没有 $pikafish）
 *   xq.engine.start({ threads: 2, hashMb: 128 });
 *   xq.engine.analyze("rnbakabnr/... w - - 0 1", 1000);   // {ok, move, candidates, …}
 *   var image = xq.board.capture("full", 2000);   // 宿主截图能力
 *   var reading = xq.board.read(image);           // 盘面读数（插件内置模板）
 *   xq.engine.quit(); xq.board.releaseFrame(image);
 */
"use strict";

var engineModule = require("./engine.js");
var library = require("./library.js");

/** Rhino 给字符串、QuickJS 给对象：两边都归一成对象。 */
function decodeJson(value) {
    if (typeof value === "string") {
        try {
            return JSON.parse(value);
        } catch (error) {
            return null;
        }
    }
    return value;
}

function describeError(error) {
    return error && error.message ? String(error.message) : String(error);
}

module.exports = function (plugin) {
    var sdk = (plugin && typeof plugin.unwrap === "function") ? plugin.unwrap() : plugin;
    if (!sdk) {
        throw new Error("插件对象为空：宿主没有把插件 SDK 实例传进来");
    }
    var scriptDir = library.resolveScriptDir(plugin, sdk);
    var engine = engineModule.create(sdk);
    var capabilities = decodeJson(typeof sdk.capabilities === "function" ? sdk.capabilities() : null) || {};

    // 工程库（棋规/识别）是 QuickJS 工程代码；Rhino 的 const 语义过不去其中一部分文件。
    // 引擎那半是自带的原生实现，与宿主引擎无关——所以这里只降级缺失的部分，不把整个插件拖崩。
    var rules = null;
    var rulesError = "";
    try {
        rules = library.rules(scriptDir);
    } catch (error) {
        rulesError = "工程库加载失败：" + describeError(error);
    }

    return {
        /** 插件 APK 版本（宿主能取到 PackageInfo 时才有值）。 */
        version: capabilities.versionName == null ? "" : String(capabilities.versionName),
        /** 插件 + 宿主能力清单：{versionName, versionCode, engineAvailable, engineLoadError, nnue, hostRuntime, hostScope}。 */
        capabilities: capabilities,
        /** 路径：脚本目录（工程代码与模板都在下面）、字形模板、模板目录。 */
        paths: {
            scriptDir: scriptDir,
            glyphTemplates: scriptDir + "/assets/glyph-tpl",
            templates: scriptDir + "/templates"
        },
        /** 自带原生 Pikafish：与宿主 `$pikafish` 同形状，可直接当 pikafish-engine.js 的 driver。 */
        engine: engine,
        /** 棋规 / FEN / 记谱（工程 src/chess、src/engine/uci.js，纯 JS）。加载失败时是 null。 */
        rules: rules,
        /** `rules` 为空时的原因（Rhino 宿主上通常就是「工程库是 QuickJS 代码」）。 */
        rulesError: rulesError,
        /** 识别：locate / read / readFile / capture（懒加载，第一次调用时才装配视觉链路）。 */
        board: library.board(scriptDir),
        /** 逃生口：按插件脚本目录加载工程里的任意模块，例如 load("src/engine/engine-router.js")。 */
        load: function (relative) {
            return library.load(scriptDir, relative);
        },
        /**
         * 把插件里的整套工程装到目标目录（默认装到脚本目录下，与内置时代的位置一致）。
         * @param targetDir 省略 = <宿主脚本目录>/象棋自动走棋
         * @param options.overwrite 覆盖已有文件（默认只补缺）
         */
        installProject: function (targetDir, options) {
            var target = targetDir;
            if (target == null) {
                var base = ".";
                try {
                    base = files.cwd();
                } catch (ignore) {
                    base = "/sdcard/脚本";
                }
                target = String(base).replace(/[\\/]+$/, "") + "/象棋自动走棋";
            }
            return library.installProject(scriptDir, target, options);
        },
        /**
         * 让工程自己的算棋链路（engine-router/pikafish-engine）改用插件自带的引擎。
         * @returns pikafish 适配器模块（已注入驱动），可直接 setDriver(null) 回退到宿主 $pikafish
         */
        usePluginEngine: function () {
            var pikafish;
            try {
                pikafish = library.load(scriptDir, "src/engine/pikafish-engine.js");
            } catch (error) {
                throw new Error("工程算棋链路不可用（" + describeError(error) + "）" +
                    (rulesError ? "；" + rulesError : ""));
            }
            if (typeof pikafish.setDriver === "function") {
                pikafish.setDriver(engine);
            }
            return pikafish;
        }
    };
};
