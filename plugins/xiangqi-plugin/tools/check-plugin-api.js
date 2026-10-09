/**
 * 离线自检：不装真机也能验证插件的 JS 半边（入口契约、引擎解析、与工程库的接缝）。
 *
 *   node plugins/xiangqi-plugin/tools/check-plugin-api.js
 *
 * 做法：用「假插件对象 + 假 SDK」调用 assets/scripts/index.js，SDK 的返回口径按 Java 侧来
 * （字符串 JSON，Rhino 就是这样；QuickJS 会自己解码成对象，两条路都要能走）。
 */
"use strict";

const assert = require("assert");
const fs = require("fs");
const path = require("path");

const PLUGIN_ROOT = path.resolve(__dirname, "..");
const SCRIPTS_DIR = path.join(PLUGIN_ROOT, "src", "main", "assets", "scripts");
const PROJECT_DIR = path.resolve(PLUGIN_ROOT, "..", "..", "samples", "象棋自动走棋");

const START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
const SEARCH_RAW = [
    "info depth 12 seldepth 18 multipv 1 score cp 24 nodes 1000 time 100 pv h2e2 h7e7",
    "info depth 12 seldepth 18 multipv 2 score mate 3 nodes 900 time 100 pv b2e2 h7e7",
    "bestmove h2e2"
].join("\n");

const results = [];
function check(name, body) {    try {
        body();
        results.push(true);
        console.log("  ok   " + name);
    } catch (error) {
        results.push(false);
        console.log("  FAIL " + name + "\n       " + (error && error.message ? error.message : error));
    }
}

/** 插件脚本在真机上用的是宿主 `files` API；离线自检里补一份同形状的实现。 */
globalThis.files = {
    exists: (p) => fs.existsSync(p),
    isFile: (p) => fs.existsSync(p) && fs.statSync(p).isFile(),
    isDir: (p) => fs.existsSync(p) && fs.statSync(p).isDirectory(),
    listDir: (p) => fs.readdirSync(p),
    ensureDir: (p) => { fs.mkdirSync(p, { recursive: true }); return true; },
    copy: (from, to) => { fs.copyFileSync(from, to); return true; },
    dirname: (p) => path.dirname(p),
    join: (...parts) => path.join(...parts),
    cwd: () => process.cwd()
};

/** 假 SDK：模拟 XiangqiPlugin（Java）的方法名与返回口径。 */
function fakeSdk(state) {
    return {
        capabilities: function () {
            return JSON.stringify({
                versionName: "1.0.0", versionCode: 1, assetsScriptDir: "scripts",
                engineAvailable: state.available, engineLoadError: state.loadError || "",
                nnue: "/data/user/0/com.jdkshen.xiangqi.plugin/files/pikafish.nnue",
                hostRuntime: "com.example.HostRuntime", hostScope: "com.example.HostScope"
            });
        },
        engineAvailable: function () { return state.available; },
        engineLoadError: function () { return state.loadError || ""; },
        engineRunning: function () { return state.running; },
        engineStart: function (evalFile, threads, hashMb, contempt) {
            state.start = { evalFile: evalFile, threads: threads, hashMb: hashMb, contempt: contempt };
            if (!state.available) {
                return JSON.stringify({ ok: false, error: "插件的原生引擎不可用（" + state.loadError + "）" });
            }
            state.running = true;
            return JSON.stringify({ ok: true, running: true, threads: threads, hashMb: hashMb,
                contempt: contempt, evalFile: evalFile, options: ["option name Contempt type spin"] });
        },
        engineAnalyze: function (input, movetimeMs, timeoutMs, multiPv, depth, withHistory) {
            state.search = { input: input, movetimeMs: movetimeMs, timeoutMs: timeoutMs, multiPv: multiPv,
                depth: depth, withHistory: withHistory };
            if (!state.available) {
                return "error: 插件的原生引擎不可用（" + state.loadError + "）";
            }
            return state.raw;
        },
        engineOptions: function () { return JSON.stringify(["option name Contempt type spin"]); },
        engineConfiguredThreads: function () { return 2; },
        engineStop: function () { state.running = false; },
        engineQuit: function () { state.running = false; },
        resolveNnue: function () { return "/data/user/0/com.jdkshen.xiangqi.plugin/files/pikafish.nnue"; },
        nnuePath: function () { return "/data/user/0/com.jdkshen.xiangqi.plugin/files/pikafish.nnue"; },
        scriptDir: function () { return PROJECT_DIR; }
    };
}

function loadPlugin(state, scriptDirOverride) {
    const sdk = fakeSdk(state);
    const dir = scriptDirOverride || PROJECT_DIR;
    const plugin = {
        getMainScriptPath: function () { return path.join(dir, "index.js"); },
        getAssetsScriptDir: function () { return "scripts"; },
        getVersion: function () { return "1.0.0"; },
        unwrap: function () { return sdk; }
    };
    return require(path.join(SCRIPTS_DIR, "index.js"))(plugin);
}

console.log("xiangqi plugin API check");

// ---------------------------------------------------------------- 入口形状
const good = loadPlugin({ available: true, running: false, raw: SEARCH_RAW, loadError: "" });
check("入口返回识别 + 算棋能力包", function () {
    assert.strictEqual(typeof good.engine.analyze, "function");
    assert.strictEqual(typeof good.engine.analyzePosition, "function");
    assert.strictEqual(typeof good.board.read, "function");
    assert.strictEqual(typeof good.board.readFile, "function");
    assert.strictEqual(typeof good.board.capture, "function");
    assert.strictEqual(typeof good.load, "function");
    assert.strictEqual(typeof good.usePluginEngine, "function");
});
check("版本与路径来自插件 SDK", function () {
    assert.strictEqual(good.version, "1.0.0");
    assert.strictEqual(good.paths.scriptDir, PROJECT_DIR);
    assert.strictEqual(good.paths.glyphTemplates, PROJECT_DIR + "/assets/glyph-tpl");
});
check("能力清单可读（宿主 runtime/scope 有记录）", function () {
    assert.strictEqual(good.capabilities.engineAvailable, true);
    assert.strictEqual(good.capabilities.hostRuntime, "com.example.HostRuntime");
    assert.strictEqual(good.capabilities.nnue.indexOf("pikafish.nnue") > 0, true);
});

// ---------------------------------------------------------------- 引擎（与 $pikafish 同形状）
check("available()/isRunning() 直通 SDK", function () {
    assert.strictEqual(good.engine.available(), true);
    assert.strictEqual(good.engine.isRunning(), false);
    good.engine.start({ threads: 4, hashMb: 256, contempt: 0 });
    assert.strictEqual(good.engine.isRunning(), true);
});
check("start() 只把基本类型传给 Java（越界值夹到引擎接受的区间）", function () {
    assert.strictEqual(good.engine.DEFAULTS.threads, 2);
    const state = { available: true, running: false, raw: "", loadError: "" };
    const api = loadPlugin(state);
    api.engine.start({ threads: 4, hashMb: 256, contempt: -2000 });
    assert.deepStrictEqual(state.start, { evalFile: null, threads: 4, hashMb: 256, contempt: -200 });
    api.engine.quit();
    assert.strictEqual(state.running, false);
});
check("analyze() 解析 info/bestmove（MultiPV 候选、mate、depth）", function () {
    const result = good.engine.analyze(START_FEN, 1200);
    assert.strictEqual(result.ok, true);
    assert.strictEqual(result.move, "h2e2");
    assert.strictEqual(result.candidates.length, 2);
    assert.strictEqual(result.candidates[0].scoreCp, 24);
    assert.strictEqual(result.candidates[1].mate, 3);
    assert.strictEqual(result.candidates[1].move, "b2e2");
    assert.strictEqual(result.depth, 12);
    assert.strictEqual(result.pv, "h2e2 h7e7");
});
check("analyze() 透传 movetime/multiPv/depth 且不带历史", function () {
    const state = { available: true, running: true, raw: SEARCH_RAW, loadError: "" };
    const api = loadPlugin(state);
    api.engine.analyze(START_FEN, 900, 30000, 5, 12);
    assert.deepStrictEqual(state.search, { input: START_FEN, movetimeMs: 900, timeoutMs: 30000,
        multiPv: 5, depth: 12, withHistory: false });
});
check("analyzePosition() 走棋谱历史分支", function () {
    const state = { available: true, running: true, raw: SEARCH_RAW, loadError: "" };
    const api = loadPlugin(state);
    api.engine.analyzePosition("position fen " + START_FEN + " moves h2e2 h7e7", 1200);
    assert.strictEqual(state.search.withHistory, true);
    assert.strictEqual(state.search.input.indexOf("moves h2e2 h7e7") > 0, true);
});
check("只有 bestmove 也当成一个候选（单步杀）", function () {
    const api = loadPlugin({ available: true, running: true, raw: "bestmove h2e2", loadError: "" });
    const result = api.engine.analyze(START_FEN, 500);
    assert.strictEqual(result.ok, true);
    assert.strictEqual(result.move, "h2e2");
    assert.strictEqual(result.candidates.length, 1);
});
check("原生报错转成 {ok:false, error}", function () {
    const api = loadPlugin({ available: true, running: true, raw: "error: 无效 FEN", loadError: "" });
    const result = api.engine.analyze("bad", 500);
    assert.strictEqual(result.ok, false);
    assert.strictEqual(result.error, "无效 FEN");
});
check("引擎缺失时 start/analyze 都给出明确原因", function () {
    const api = loadPlugin({ available: false, running: false, raw: "",
        loadError: "UnsatisfiedLinkError: libxiangqi_engine.so" });
    const started = api.engine.start();
    assert.strictEqual(started.ok, false);
    assert.strictEqual(started.error.indexOf("UnsatisfiedLinkError") > 0, true);
    const result = api.engine.analyze(START_FEN, 500);
    assert.strictEqual(result.ok, false);
    assert.strictEqual(result.error.indexOf("libxiangqi_engine.so") > 0, true);
});

// ---------------------------------------------------------------- 工程库接缝
check("棋规可用（开局 44 步合法着法）", function () {
    const parsed = good.rules.fen.parseFen(START_FEN);
    assert.strictEqual(parsed.ok, true);
    assert.strictEqual(good.rules.legal.allLegalMoves(parsed.board, parsed.sideToMove).length, 44);
});
check("usePluginEngine() 把工程引擎切到插件自带的引擎", function () {
    const pikafish = good.usePluginEngine();
    assert.strictEqual(typeof pikafish.setDriver, "function");
    assert.strictEqual(pikafish.available(), true);   // 通过注入的驱动（假 SDK）判定
    // 工程里的 analyze 返回的是走法对象（move 是 {fromCol,fromRow,toCol,toRow}，ICCS 在 iccs 上）
    const result = pikafish.analyze(START_FEN, { movetime: 800 });
    assert.strictEqual(result.ok, true);
    assert.strictEqual(result.iccs, "h2e2");
    assert.strictEqual(result.engine, "pikafish");
});
check("load() 能取工程里的任意模块", function () {
    const uci = good.load("src/engine/uci.js");
    assert.strictEqual(uci.moveToUci({ fromCol: 7, fromRow: 7, toCol: 4, toRow: 7 }), "h2e2");
});
check("识别门面可装配（adapter 可访问）", function () {
    const adapter = good.board.adapter();
    assert.strictEqual(typeof adapter.capabilities, "function");
    assert.strictEqual(typeof adapter.missingRequired, "function");
});
check("installProject() 把工程装到目标目录", function () {
    assert.strictEqual(typeof good.installProject, "function");
    const target = path.join(PLUGIN_ROOT, "build", "installed-project-check");
    fs.rmSync(target, { recursive: true, force: true });
    const result = good.installProject(target);
    assert.strictEqual(result.ok, true, result.error || "");
    assert.strictEqual(result.overwrite === undefined, true);
    for (const relative of ["project.json", "main.js", "src/engine/pikafish-engine.js",
                            "assets/glyph-tpl/tpl_將.png", "templates/tpl-270_797_573x692.png",
                            "tools/run-regress.js"]) {
        assert.ok(fs.existsSync(path.join(target, relative)), relative + " 未安装");
    }
    // 再装一次不应报错：默认只补缺
    const second = good.installProject(target);
    assert.strictEqual(second.ok, true);
    assert.strictEqual(second.files, 0);
    assert.ok(second.skipped > 0);
    fs.rmSync(target, { recursive: true, force: true });
});
check("工程库加载不了时只降级那部分（引擎仍可用，原因可读）", function () {
    const api = loadPlugin({ available: true, running: false, raw: SEARCH_RAW, loadError: "" },
        path.join(PLUGIN_ROOT, "build", "missing-project-dir"));
    assert.strictEqual(api.rules, null);
    assert.strictEqual(api.rulesError.indexOf("工程库加载失败") >= 0, true);
    assert.strictEqual(api.engine.available(), true);
    assert.strictEqual(api.engine.analyze(START_FEN, 500).ok, true);
    assert.throws(function () { api.usePluginEngine(); }, /工程算棋链路不可用/);
});

const failed = results.filter(function (ok) { return !ok; }).length;
console.log("\n" + (results.length - failed) + "/" + results.length + " checks passed");
process.exit(failed === 0 ? 0 : 1);
