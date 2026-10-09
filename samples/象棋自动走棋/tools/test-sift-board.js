// @engine quickjs
"use strict";

/** K40 图片回归：调用项目实际定位、校准、棋盘服务，不触发点击或算棋。 */
const baseDir = files.path(__dirname + "/..");
const fixtureDir = "/sdcard/Download/ai-sift-chess";
const fixture = JSON.parse(files.read(fixtureDir + "/fixture-pipeline.json"));
const locator = require("../src/vision/locate-board.js");
const adapter = require("../src/runtime/aijs-adapter.js");
const factory = require("../src/vision/pipeline-factory.js");
const readerModule = require("../src/vision/board-reader.js");
const serviceModule = require("../src/services/board-service.js");
const frameGate = require("../src/vision/frame-gate.js");
const recognition = require("../src/automation/driver/recognition.js");
locator.configureSift({ baseDir: baseDir });
const source = factory.createGlyphSource({ adapter: adapter, baseDir: baseDir,
    confirmGeometry: locator.confirmGeometry, log: console.log });
const reader = readerModule.create(source);
let image = null, failure = null;
const samples = factory.createSamples(adapter), rows = [], checks = [], repeats = [];
const service = serviceModule.createBoardService({
    ports: { now: adapter.nowMono, capture: function () { return { image: image }; } },
    locateBoard: locator, glyph: source, calibrate: reader.calibrateGrid,
    checkGeometry: locator.checkGeometry,
    vision: { gate: frameGate.create({}), samples: samples }
});
function grade(result, item) {
    if (item.negative) return { correct: !result.ok, negative: true };
    // 真值只在识别完后评分，不作为识别器或 referenceBoard 的输入。
    const expected = [];
    item.fen.split("/").forEach(function (row) {
        row.split("").forEach(function (p) {
            if (p >= "1" && p <= "9") for (let n = 0; n < Number(p); n++) expected.push(null);
            else expected.push(p);
        });
    });
    const chars = result.chars || [], wrong = [];
    let total = 0, correct = 0;
    for (let i = 0; i < 90; i++) {
        const want = expected[i];
        if (want === "?") continue;
        total++;
        if ((chars[i] || null) === (want || null)) correct++;
        else wrong.push({ row: Math.floor(i / 9), col: i % 9, expected: want, actual: chars[i] || null });
    }
    return { correct: correct, total: total, wrong: wrong };
}
function save(complete) {
    const path = baseDir + "/test-output/sift-board-project.json";
    files.ensureDir(path);
    files.write(path, JSON.stringify({ complete: complete, failure: failure, rows: rows,
        checks: checks, repeats: repeats, time: Date.now() }, null, 2));
}
try {
    fixture.frames.forEach(function (item, index) {
        image = images.read(fixtureDir + "/" + item.file);
        try {
            if (["skin-b.png", "skin-c.png", "skin2-02.png", "skin2-03.png", "skin2-04.png", "skin2-05.png", "skin2-06.png"].indexOf(item.file) >= 0) {
                const geometry = locator.checkGeometry(image, service.grid());
                checks.push({ name: item.file + " keeps geometry after piece/highlight changes", pass: geometry.ok,
                    rechecked: geometry.siftRechecked === true, ms: geometry.ms, reason: geometry.reason });
            }
            if (item.group === "transformed" || item.negative) {
                const stale = service.recognizeSmart({});
                checks.push({ name: item.file + " rejects previous grid", pass: !stale.ok &&
                    (item.negative ? stale.geometryUncertain === true : stale.geometryChanged === true) });
            }
            if (item.file === "shifted.png") {
                const shifted = image, previous = images.read(fixtureDir + "/skin2-06.png");
                let calls = 0;
                try {
                    // 上面的变位失败已清空稳定帧基线；先恢复旧盘面并预热，再测试第二帧变位。
                    image = previous;
                    let warm = service.recognizeSmart({});
                    if (warm.unstable) warm = service.recognizeSmart({});
                    if (!warm.ok) throw new Error("多帧变位测试缺少有效第一帧：" + warm.reason);
                    const state = { cfg: { observeFrames: 2, observeGapMs: 0 },
                        opts: { recognize: function () {
                            image = calls++ === 0 ? previous : shifted;
                            return service.recognizeSmart({});
                        } }, clock: function () {}, stats: { frameConflicts: 0, mergedFrames: 0 } };
                    const merged = recognition.create(state, { note: function () {} }).recognizeMerged({});
                    checks.push({ name: "driver rejects old first frame after board moves in second frame",
                        pass: calls === 2 && !merged.ok && merged.geometryChanged === true,
                        calls: calls, reason: merged.reason, geometryChanged: merged.geometryChanged });
                } finally { image = shifted; previous.recycle(); }
            }
            const start = Date.now(), located = locator.locateBoardInImage(image, {});
            if (!located.ok) {
                rows.push({ file: item.file, ok: false, reason: located.reason, ms: Date.now() - start, score: grade(located, item) });
                if (!item.negative) throw new Error("棋盘定位失败：" + item.file);
                return;
            }
            const calibrated = reader.confirmSiftGrid(image, located, {});
            service.setGrid(calibrated && calibrated.ok ? calibrated.grid : located.grid);
            const observationStart = Date.now();
            let result = service.recognizeSmart({});
            if (result.unstable) result = service.recognizeSmart({});
            rows.push({ file: item.file, ok: result.ok, reason: result.reason || null,
                method: located.method, geometryCacheHit: located.geometryCacheHit,
                locateMs: located.timing.locateMs, calibrated: calibrated && calibrated.method,
                calibrateMs: calibrated && calibrated.ms, observeMs: Date.now() - observationStart,
                ms: Date.now() - start, unknown: result.unknownCells, layout: result.layout,
                glyphStats: result.glyphStats, score: grade(result, item) });
            if (item.file === "skin2-04.png") {
                const wrong = rows[rows.length - 1].score.wrong || [];
                checks.push({ name: "animation preserves uncertainty without inventing pieces",
                    pass: !result.ok || (result.unknownCells > 0 && wrong.every(function (cell) { return cell.actual === "?"; })) });
            }
            if (index === 0) for (let n = 0; n < 3; n++) {
                const t = Date.now(), repeated = service.recognizeSmart({});
                repeats.push({ ms: Date.now() - t, ok: repeated.ok, score: grade(repeated, item) });
            }
            console.log("CHESS_PROJECT " + item.file + " " + JSON.stringify(rows[rows.length - 1]));
        } finally { image.recycle(); image = null; save(false); }
    });
    if (checks.some(function (c) { return !c.pass; })) throw new Error("旧几何拦截失败");
} catch (error) { failure = String(error) + "\n" + String(error.stack || ""); console.error(failure); }
finally { source.release(); locator.releaseSift(); save(true); }
console.log("CHESS_PROJECT_DONE failure=" + failure);
if (failure) throw new Error(failure);
