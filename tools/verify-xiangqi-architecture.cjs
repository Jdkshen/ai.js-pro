// PC-only architecture and composition checks. No dependencies outside Node.
"use strict";
const fs = require("fs");
const path = require("path");
const vm = require("vm");
const assert = require("assert");
const root = path.resolve(__dirname, "../samples/象棋自动走棋");
const read = file => fs.readFileSync(path.join(root, file), "utf8");
function walk(dir) {
    return fs.readdirSync(path.join(root, dir), { withFileTypes: true }).flatMap(entry =>
        entry.isDirectory() ? walk(dir + "/" + entry.name) : [dir + "/" + entry.name]);
}
const production = ["main.js", "悬浮窗运行与测试.js", ...walk("src")].filter(f => f.endsWith(".js"));
for (const file of production) {
    const source = read(file);
    new vm.Script(source, { filename: file });
    assert(source.startsWith("// @engine quickjs"), "Missing QuickJS marker: " + file);
    const code = source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
    for (const match of code.matchAll(/\b(require|load)\(["']([^"']+)["']\)/g)) {
        const target = match[1] === "load" ? match[2] : path.posix.normalize(path.posix.join(path.posix.dirname(file), match[2]));
        if (match[1] === "require" && !match[2].startsWith(".")) continue;
        assert(!/^(tools|experiments)\//.test(target), file + " depends on " + target);
        assert(fs.existsSync(path.join(root, target)), file + " missing dependency: " + target);
    }
}
function checkActions(dirs, stateName, actionsName) {
    const modules = dirs.flatMap(walk).filter(f => f.endsWith(".js"));
    const actions = {};
    for (const file of modules) {
        const provided = require(path.join(root, file)).create({}, actions);
        for (const name of Object.keys(provided)) {
            assert(!actions[name], "Duplicate action " + name);
            actions[name] = provided[name];
        }
    }
    const all = modules.map(read).join("\n") + read(stateName === "driverState" ? "src/automation/auto-driver.js" : "src/app/panel-app.js");
    for (const match of all.matchAll(new RegExp(actionsName + "\\.([A-Za-z_$][\\w$]*)", "g"))) {
        assert.strictEqual(typeof actions[match[1]], "function", "Unwired action " + match[1]);
    }
    return Object.keys(actions).length;
}
const driverActions = checkActions(["src/automation/driver"], "driverState", "driverActions");
const panelActions = checkActions(["src/app/panel", "src/ui/panel"], "panelState", "panelActions");
assert(read("悬浮窗运行与测试.js").split("\n").length < 40, "Panel entry grew into a monolith");
assert(read("src/automation/auto-driver.js").split("\n").length < 1800, "Driver entry grew into a monolith");
assert(read("src/app/panel-app.js").split("\n").length < 1800, "Panel composition grew into a monolith");

const factory = require(path.join(root, "src/vision/pipeline-factory.js"));
assert.strictEqual(factory.create, undefined, "Legacy pipeline factory leaked into production");
const service = require(path.join(root, "src/services/board-service.js")).createBoardService({});
assert.strictEqual(service.recognize, service.recognizeSmart, "Manual/automatic recognition differ");
assert.strictEqual(service.recognizeLegacy, undefined, "Legacy API is still exposed");
const readerModule = require(path.join(root, "src/vision/board-reader.js"));
const expected = { ok: true, unknownCells: 0, layout: "4k4/9/9/9/9/4P4/9/9/9/4K4", validation: { ok: true } };
let glyphCalls = 0;
const reader = readerModule.create({ recognize() { glyphCalls++; return expected; } });
const grid = { originX: 50, originY: 80, stepX: 40, stepY: 40, version: 1 };
assert.strictEqual(reader.run({}, { ok: true, grid }, {}), expected);
// Calibration must not load a classification model. No Android/YOLO host exists here.
const calibrated = reader.calibrateGrid({}, { ok: true, grid }, { maxCandidates: 1, coarseCandidates: 1 });
assert(calibrated.ok && glyphCalls > 1, "Calibration did not use the glyph source");
const driver = require(path.join(root, "src/automation/auto-driver.js"));
const deps = { recognize: () => null, locate: () => ({ ok: false }), analyze: () => null, allowClick: false };
const a = driver.createDriver(deps), b = driver.createDriver(deps);
a.setGrid(grid);
assert.notDeepStrictEqual(a.grid(), b.grid(), "Driver instances share grid state");
a.start();
assert.strictEqual(b.isRunning(), false, "Driver instances share running state");
a.stop();
const loaded = Object.keys(require.cache).filter(f => f.startsWith(root) && /[\\/]experiments[\\/]/.test(f));
assert.deepStrictEqual(loaded, [], "Production module load pulled experimental classifiers");
console.log(`ARCHITECTURE OK production=${production.length} driverActions=${driverActions} panelActions=${panelActions}`);
