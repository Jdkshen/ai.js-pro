/**
 * 插件里那份「象棋自动走棋」工程代码。
 *
 * 这些文件不是复制粘贴出来的第二份：构建时由 Gradle 从仓库根的
 * `samples/象棋自动走棋`（工程已从主 APK 移出）整棵拷到插件的 `assets/scripts/`
 * （见 plugins/xiangqi-plugin/build.gradle），
 * 所以工程改了插件就跟着改，不会出现两套识别算法。
 *
 * 本文件只做三件事：定位脚本目录、加载工程模块、把「识别」封装成能直接调的形状。
 */
"use strict";

/** 取路径的父目录（兼容 / 与 \：真机永远是 POSIX，但离线自检可能在 Windows 上跑）。 */
function dirName(path) {
    var text = String(path == null ? "" : path);
    var index = Math.max(text.lastIndexOf("/"), text.lastIndexOf("\\"));
    return index > 0 ? text.substring(0, index) : text;
}

/**
 * 插件脚本目录 = 宿主 `cache/plugin-scripts/<包名>/`（工程代码与模板都在它下面）。
 * 优先用宿主给的入口脚本路径；拿不到就退回 SDK 按宿主约定算出来的目录。
 */
function resolveScriptDir(plugin, sdk) {
    var mainScriptPath = null;
    try {
        if (plugin && typeof plugin.getMainScriptPath === "function") {
            mainScriptPath = plugin.getMainScriptPath();
        } else if (plugin && plugin.mainScriptPath != null) {
            mainScriptPath = plugin.mainScriptPath;
        }
    } catch (ignore) {
        // 换下一个来源
    }
    if (mainScriptPath) {
        return dirName(String(mainScriptPath));
    }
    var fromSdk = null;
    try {
        if (sdk && typeof sdk.scriptDir === "function") {
            fromSdk = sdk.scriptDir();
        }
    } catch (ignore) {
        fromSdk = null;
    }
    if (fromSdk) {
        return String(fromSdk);
    }
    throw new Error("插件拿不到脚本目录：宿主既没提供入口脚本路径，也没有 scriptDir()");
}

function loadModule(scriptDir, relative) {
    return require(String(scriptDir).replace(/\/+$/, "") + "/" + relative);
}

/** 一次 require 一批工程模块（键名 = 对外暴露的名字）。 */
function loadModules(scriptDir, map) {
    var result = {};
    for (var key in map) {
        if (Object.prototype.hasOwnProperty.call(map, key)) {
            result[key] = loadModule(scriptDir, map[key]);
        }
    }
    return result;
}

function describe(error) {
    return error && error.message ? String(error.message) : String(error);
}

/**
 * 把插件里的工程代码装进目标目录（一行行复制，保留目录结构）。
 *
 * <p>为什么有它：象棋工程不再随宿主 APK 打包，用户也就不会在「示例」页看到它。
 * 这里让插件把它还原成一个普通工程（src/assets/templates + main.js + project.json），
 * 编辑器里打开、按「运行」跑起来，和内置时代一样。
 *
 * @param targetDir 目标目录（不存在会建）
 * @param options.overwrite 覆盖已存在的文件（默认 false：只补缺的）
 * @returns {ok, files, skipped, targetDir} 或 {ok:false, error}
 */
function installProject(scriptDir, targetDir, options) {
    var opts = options || {};
    var target = String(targetDir == null ? "" : targetDir).replace(/[\\/]+$/, "");
    if (!target) {
        return { ok: false, error: "installProject 需要目标目录" };
    }
    var paths = [];
    try {
        collect(String(scriptDir).replace(/[\\/]+$/, ""), "", paths);
    } catch (error) {
        return { ok: false, error: "读插件工程失败：" + describe(error) };
    }
    var copied = 0;
    var skipped = 0;
    var failures = [];
    for (var i = 0; i < paths.length; i++) {
        var relative = paths[i];
        var targetPath = target + "/" + relative;
        try {
            if (!opts.overwrite && files.exists(targetPath)) {
                skipped += 1;
                continue;
            }
            files.ensureDir(files.dirname(targetPath));
            files.copy(files.join(scriptDir, relative), targetPath);
            copied += 1;
        } catch (error) {
            failures.push(relative + ": " + describe(error));
        }
    }
    var result = { ok: failures.length === 0, files: copied, skipped: skipped, targetDir: target };
    if (failures.length) {
        result.error = "有 " + failures.length + " 个文件没装成功（前几个）：" + failures.slice(0, 3).join("；");
    }
    return result;
}

/** 递归收集脚本目录下的相对文件路径（宿主 files API；两边引擎都有）。 */
function collect(root, prefix, out) {
    var entries = files.listDir(prefix ? root + "/" + prefix : root);
    for (var i = 0; i < entries.length; i++) {
        var name = String(entries[i]);
        if (name === "." || name === ".." || name.charAt(0) === ".") {
            continue;   // 与插件打包时的排除规则一致（点开头的都不带）
        }
        var relative = prefix ? prefix + "/" + name : name;
        if (files.isDir(root + "/" + relative)) {
            collect(root, relative, out);
        } else {
            out.push(relative);
        }
    }
}

/**
 * 识别门面：装配工程自己的「定位 → 字形识别」链路，用插件内置模板 + 宿主截图/取色能力。
 * 懒加载：`plugins.load()` 不碰视觉代码，第一次调用 read/locate 时才 require。
 */
function createBoard(scriptDir) {
    var factory = loadModule(scriptDir, "src/vision/pipeline-factory.js");
    var readerModule = loadModule(scriptDir, "src/vision/board-reader.js");
    var locator = loadModule(scriptDir, "src/vision/locate-board.js");
    var adapter = loadModule(scriptDir, "src/runtime/aijs-adapter.js");
    var log = function (text) {
        try {
            if (typeof console !== "undefined" && console && typeof console.log === "function") {
                console.log("[xiangqi-plugin] " + text);
            }
        } catch (ignore) {
            // 日志失败不影响识别
        }
    };
    // SIFT 定位用工程 templates/ 下的模板（相对脚本目录），与真机运行同一口径。
    try {
        if (locator && typeof locator.configureSift === "function") {
            locator.configureSift({ baseDir: scriptDir });
        }
    } catch (ignore) {
        // 没有模板也能走其它定位通道
    }
    var glyph = factory.createGlyphSource({ adapter: adapter, baseDir: scriptDir, log: log });
    var reader = readerModule.create(glyph);
    var board = {
        /** 平台适配层：宿主接口（截图/取色/OCR/无障碍）全部从这里走，缺能力会明确报名字。 */
        adapter: adapter,
        locator: locator,
        /** 只想定位棋盘：{ok, grid, method} */
        locate: function (image, options) {
            return locator.locateBoardInImage(image, options || {});
        },
        /** 已经拿到图片对象 → 盘面读数。 */
        read: function (image, options) {
            var opts = options || {};
            var located = opts.located || locator.locateBoardInImage(image, opts);
            return reader.run(image, located, opts);
        },
        /** 从图片文件读盘面（离线回放/截图自检用）。 */
        readFile: function (path, options) {
            return reader.recognizeImage(String(path), options || {});
        },
        /** 截一帧（宿主能力）：mode = "full" | 宿主支持的区域名，返回的帧要自己 releaseFrame。 */
        capture: function (mode, timeoutMs, options) {
            if (typeof adapter.capture !== "function") {
                throw new Error("宿主没有截图能力（adapter.capture 缺失）");
            }
            return adapter.capture(mode || "full", timeoutMs || 2000, options || {});
        },
        releaseFrame: function (image) {
            return adapter.releaseFrame(image);
        },
        describe: function (result) {
            return reader.describeResult(result);
        },
        /** 释放字形模板/会话（插件卸载或不再识别时调）。 */
        release: function () {
            try {
                if (glyph && typeof glyph.release === "function") {
                    glyph.release();
                }
            } finally {
                if (locator && typeof locator.releaseSift === "function") {
                    locator.releaseSift();
                }
            }
        }
    };
    return board;
}

/** 懒加载包装：第一次调用任意方法时才真的 require 视觉链路。 */
function lazyBoard(scriptDir) {    var instance = null;
    var loadError = null;
    function get() {
        if (instance) {
            return instance;
        }
        if (loadError) {
            throw loadError;
        }
        try {
            instance = createBoard(scriptDir);
        } catch (error) {
            loadError = new Error("识别链路加载失败：" + describe(error));
            throw loadError;
        }
        return instance;
    }
    return {
        locate: function (image, options) { return get().locate(image, options); },
        read: function (image, options) { return get().read(image, options); },
        readFile: function (path, options) { return get().readFile(path, options); },
        capture: function (mode, timeoutMs, options) { return get().capture(mode, timeoutMs, options); },
        releaseFrame: function (image) { return get().releaseFrame(image); },
        describe: function (result) { return get().describe(result); },
        release: function () { if (instance) { instance.release(); } },
        /** 平台适配层对象（宿主能力探测：adapter.capabilities() / adapter.missingRequired()）。 */
        adapter: function () { return get().adapter; },
        /** 定位器（locateBoardOnScreen 等）。 */
        locator: function () { return get().locator; }
    };
}

module.exports = {
    resolveScriptDir: resolveScriptDir,
    load: loadModule,
    rules: function (scriptDir) {
        return loadModules(scriptDir, {
            rules: "src/chess/rules.js",
            fen: "src/chess/fen.js",
            legal: "src/chess/legal-moves.js",
            uci: "src/engine/uci.js"
        });
    },
    board: lazyBoard,
    /** 把插件里的整套工程装到目标目录（宿主 files API 实现，两个引擎都能用）。 */
    installProject: installProject
};
