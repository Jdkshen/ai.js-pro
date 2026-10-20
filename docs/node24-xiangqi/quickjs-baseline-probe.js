// @engine quickjs
// R5.3 QuickJS 识别基线探针：对已保存真实帧跑「读图→棋盘定位→棋子识别→FEN 布局」。
// 运行：adb shell am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity --es path <本文件>
// 输出：/sdcard/Download/three-engine-bench/r5-quickjs-baseline.json + 控制台 R5_BASELINE_DONE
"use strict";
var OUT = "/sdcard/Download/three-engine-bench/r5-quickjs-baseline.json";
var DEVICE = [(typeof device !== "undefined" && device.width) || 0, (typeof device !== "undefined" && device.height) || 0];
var INPUTS = [
    { id: "f01", label: "real-frame-f01", path: "/sdcard/Download/three-engine-bench/r4-frames/f01.png" },
    { id: "skin-a", label: "skin-a", path: "/sdcard/Download/three-engine-bench/skin-a.png" }
];

function engineName() {
    try { return String(engines.myEngine()); } catch (e) { }
    try { return String(engines); } catch (e) { }
    return "unknown";
}

var board = require("./experiments/vision/recognize-board.js");
var recognizer = require("./experiments/vision/recognizer.js");

var result = {
    probe: "r5-quickjs-baseline",
    purpose: "QuickJS chess recognition baseline (offline saved frames) for Node24 porting",
    engine: engineName(),
    dirname: String(typeof __dirname !== "undefined" ? __dirname : ""),
    device: DEVICE,
    startedAt: new Date().toISOString(),
    inputs: []
};

for (var i = 0; i < INPUTS.length; i++) {
    var item = INPUTS[i];
    var rec = { id: item.id, label: item.label, path: item.path, exists: files.exists(item.path) };
    if (!rec.exists) {
        rec.ok = false;
        rec.reason = "file missing";
        result.inputs.push(rec);
        continue;
    }
    var t0 = Date.now();
    try {
        var out = board.recognizeImage(item.path, {});
        rec.wallMs = Date.now() - t0;
        rec.ok = !!out.ok;
        rec.stage = out.stage || null;
        rec.reason = out.reason || "";
        rec.layout = out.layout || null;
        rec.unknownCells = out.unknownCells;
        rec.detectionCount = out.detectionCount;
        rec.stats = out.stats || null;
        rec.timing = out.timing || null;
        rec.model = out.model || null;
        rec.modelSource = out.modelSource || null;
        rec.modelFailures = out.modelFailures || null;
        rec.native = (function () {
            try {
                if (!out.native) return null;
                return {
                    preprocessMs: out.native.preprocessMs, inferenceMs: out.native.inferenceMs,
                    totalMs: out.native.totalMs, count: out.native.list ? out.native.list.length : null
                };
            } catch (e) { return null; }
        })();
        rec.located = out.located ? {
            ok: out.located.ok, method: out.located.method, reason: out.located.reason,
            columns: out.located.columns, rows: out.located.rows,
            region: out.located.region || null,
            grid: out.located.grid ? {
                originX: out.located.grid.originX, originY: out.located.grid.originY,
                stepX: out.located.grid.stepX, stepY: out.located.grid.stepY,
                orientation: out.located.grid.orientation, version: out.located.grid.version
            } : null,
            timing: out.located.timing || null
        } : null;
        if (out.ok) {
            var g = out.grid;
            rec.grid = {
                originX: g.originX, originY: g.originY, stepX: g.stepX, stepY: g.stepY,
                orientation: g.orientation, version: g.version
            };
            rec.boardBbox = {
                x: Math.round(g.originX - g.stepX / 2),
                y: Math.round(g.originY - g.stepY / 2),
                width: Math.round(8 * g.stepX + g.stepX),
                height: Math.round(9 * g.stepY + g.stepY)
            };
            rec.box = out.box || null;
            rec.chars = out.chars || null;
            rec.placements = out.placements || null;
            rec.sides = out.sides || null;
            rec.repaired = out.repaired || null;
            rec.boardText = recognizer.boardText(out);
            rec.summary = board.describeResult(out);
        }
    } catch (e) {
        rec.wallMs = Date.now() - t0;
        rec.ok = false;
        rec.error = String(e && e.stack || e);
    }
    result.inputs.push(rec);
}

result.finishedAt = new Date().toISOString();
files.ensureDir("/sdcard/Download/three-engine-bench/");
files.write(OUT, JSON.stringify(result, null, 2));
console.log("R5_BASELINE_DONE " + JSON.stringify(result.inputs.map(function (r) {
    return {
        id: r.id, ok: r.ok, unknown: r.unknownCells, layout: r.layout, wallMs: r.wallMs,
        error: r.error ? String(r.error).slice(0, 160) : null
    };
})));
