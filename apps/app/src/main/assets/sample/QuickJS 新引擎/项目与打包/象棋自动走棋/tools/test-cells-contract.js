// @engine quickjs
/**
 * 格子三态契约 + 几何搜索的行为测试（PC 上 `node tools/test-cells-contract.js` 直接跑，不需要手机）。
 *
 * 覆盖执行单 §3.1 / §3.2 的验收用例：
 *   · 开局：32 PIECE / 58 EMPTY / 0 UNKNOWN，总数 90
 *   · 合法残局：2 / 4 / 8 枚棋子（其余 EMPTY），计数、FEN、定位接受规则一致
 *   · 含 1 个 UNKNOWN：三态数量准确，UNKNOWN 不算空也不算棋子
 *   · 非法字符 / 长度不足 / 全空棋盘 / 缺将 / 布局解析失败：有明确拒绝原因
 *   · 空格的 null 与 '.' 两种写法计数一致（这正是 scoreGrid 的事故点）
 *   · 几何搜索用缓存框（不跑模型）能从错位种子找回真实网格，并拒绝退化网格
 */
"use strict";

// 既能在 PC 上 `node tools/test-cells-contract.js`，也能在手机上被悬浮窗 require ——
// 所以不用 node 专用的 `path`，只走相对 require（QuickJS 也支持）。
const cells = require("../src/vision/cells.js");
const geometry = require("../src/vision/geometry.js");
const gridSearch = require("../src/vision/grid-search.js");
const fen = require("../src/chess/fen.js");

let pass = 0;
const failures = [];

function check(name, fn) {
    try {
        fn();
        pass += 1;
        console.log("  ok   " + name);
    } catch (error) {
        failures.push({ name: name, message: String(error && error.message ? error.message : error) });
        console.log("  FAIL " + name + " → " + (error && error.message ? error.message : error));
    }
}

function assert(condition, message) {
    if (!condition) {
        throw new Error(message || "断言失败");
    }
}

function eq(actual, expected, message) {
    if (actual !== expected) {
        throw new Error((message || "不等于") + "：实际 " + JSON.stringify(actual) + "，期望 " + JSON.stringify(expected));
    }
}

/** FEN 布局段 → 90 格 chars（内部契约：空格 = null）。 */
function charsFromLayout(layout) {
    const out = [];
    const rows = String(layout).split("/");
    for (let i = 0; i < rows.length; i++) {
        const row = rows[i];
        for (let k = 0; k < row.length; k++) {
            const ch = row[k];
            if (ch >= "1" && ch <= "9") {
                for (let n = 0; n < Number(ch); n++) {
                    out.push(cells.EMPTY);
                }
            } else {
                out.push(ch);
            }
        }
    }
    return out;
}

const OPENING = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";
// 合法残局（子力少但结构合法）：帅车 vs 将士；每串都校验过 9 列
const ENDGAME4 = "3ka4/9/9/9/9/9/9/9/9/3KR4";
const ENDGAME8 = "2kabn3/9/9/9/9/9/9/9/9/2KRCP3";
const ENDGAME2 = "4k4/9/9/9/9/9/9/9/9/4K4";
const TRUTH_GRID = geometry.createGrid({ originX: 93, originY: 648, stepX: 115, stepY: 114, orientation: "RED_BOTTOM", version: 7 });
const SEEDS = [
    { originX: 81, originY: 666, stepX: 115, stepY: 114, orientation: "RED_BOTTOM", version: 7 },
    { originX: 72, originY: 660, stepX: 114.8, stepY: 114.3, orientation: "RED_BOTTOM", version: 7 }
];

/** 由「棋盘 + 网格」造检测框（中心点 = 交点，模拟模型输出）。 */
function detectionsOf(layout, grid) {
    const chars = charsFromLayout(layout);
    const list = [];
    for (let i = 0; i < 90; i++) {
        const ch = chars[i];
        if (!cells.isPieceChar(ch)) {
            continue;
        }
        const point = geometry.pointAt(grid, i % 9, Math.floor(i / 9));
        list.push({ label: String(ch).toUpperCase(), score: 0.9, x: point.x, y: point.y });
    }
    return list;
}

console.log("== 格子三态契约 ==");

check("开局：32 PIECE / 58 EMPTY / 0 UNKNOWN，总数 90", function () {
    const chars = charsFromLayout(OPENING);
    const counts = cells.count(chars);
    eq(counts.total, 90, "总数");
    eq(counts.pieces, 32, "棋子数");
    eq(counts.empty, 58, "空格数");
    eq(counts.unknown, 0, "未知格数");
    assert(counts.ok, "开局应判定为 ok：" + counts.reason);
    eq(fen.layoutOf(chars), OPENING, "FEN 往返");
});

check("空格两种写法（null / '.'）计数一致（scoreGrid 事故点）", function () {
    const withNull = charsFromLayout(OPENING);
    const withDots = cells.toTextArray(withNull);
    const a = cells.count(withNull);
    const b = cells.count(withDots);
    eq(b.pieces, a.pieces, "点号写法的棋子数");
    eq(b.empty, a.empty, "点号写法的空格数");
    eq(b.pieces, 32, "点号写法不能把空格算成棋子");
    assert(withDots.indexOf(".") >= 0, "文本层应有 '.'");
});

check("非法字符 / 长度不足 / 全空 / 缺将 都有明确拒绝原因", function () {
    const bad = charsFromLayout(OPENING);
    bad[0] = "X";
    const badCounts = cells.count(bad);
    assert(!badCounts.ok && badCounts.invalid.length === 1, "非法字符应被列出：" + badCounts.reason);

    const short = charsFromLayout(OPENING).slice(0, 89);
    const shortCounts = cells.count(short);
    assert(!shortCounts.ok && /长度不是 90/.test(shortCounts.reason), "长度不足应报长度：" + shortCounts.reason);

    const emptyBoard = new Array(90).fill(cells.EMPTY);
    const emptyCounts = cells.count(emptyBoard);
    eq(emptyCounts.pieces, 0, "全空棋子数");
    eq(emptyCounts.empty, 90, "全空空格数");
    assert(emptyCounts.ok, "全空本身是合法数组（是否接受由几何搜索的门槛决定）");

    const noKing = charsFromLayout("9/9/9/9/9/9/9/9/9/4K4");
    const kingAudit = gridSearch.typeAudit(noKing);
    eq(kingAudit.kings, 1, "缺将时将帅计数");

    const parsed = fen.parseFen("这不是 FEN");
    assert(!parsed.ok, "坏 FEN 必须解析失败");
});

check("残局 2 / 4 / 8 子：计数与 FEN 一致", function () {
    const cases = [[ENDGAME2, 2], [ENDGAME4, 4], [ENDGAME8, 8]];
    for (let i = 0; i < cases.length; i++) {
        const layout = cases[i][0];
        const want = cases[i][1];
        const chars = charsFromLayout(layout);
        const counts = cells.count(chars);
        eq(counts.pieces, want, want + " 子局的棋子数");
        eq(counts.empty, 90 - want, want + " 子局的空格数");
        eq(counts.unknown, 0, want + " 子局的未知格数");
        eq(fen.layoutOf(chars), layout, want + " 子局的 FEN 往返");
    }
});

check("统一执行闸门：合法 2 / 4 / 8 子均接受", function () {
    const layouts = [ENDGAME2, ENDGAME4, ENDGAME8];
    for (let i = 0; i < layouts.length; i++) {
        const chars = charsFromLayout(layouts[i]);
        const n = cells.count(chars).pieces;
        const audited = gridSearch.auditRecognition(chars, { detectionCount: n, placed: n, outOfBoard: 0 }, {});
        assert(audited.ok, n + " 子残局应通过：" + audited.reason);
    }
});

check("统一执行闸门：缺将、双缺与 UNKNOWN 过多均拒绝", function () {
    const oneKing = charsFromLayout("9/9/9/9/9/9/9/9/9/4K4");
    const noKing = new Array(90).fill(cells.EMPTY);
    noKing[0] = "R";
    noKing[89] = "r";
    const unknown = charsFromLayout(ENDGAME2);
    for (let i = 10; i < 15; i++) unknown[i] = cells.UNKNOWN;
    const a = gridSearch.auditRecognition(oneKing, { detectionCount: 1, placed: 1 }, {});
    const b = gridSearch.auditRecognition(noKing, { detectionCount: 2, placed: 2 }, {});
    const c = gridSearch.auditRecognition(unknown, { detectionCount: 7, placed: 2 }, {});
    assert(!a.ok && /将帅/.test(a.reason), "缺将应拒绝：" + a.reason);
    assert(!b.ok && /将帅/.test(b.reason), "双缺应拒绝：" + b.reason);
    assert(!c.ok && /未知格过多/.test(c.reason), "UNKNOWN 过多应拒绝：" + c.reason);
});

check("统一执行闸门：非法字符、低覆盖率与框出界均拒绝", function () {
    const chars = charsFromLayout(ENDGAME2);
    chars[20] = "X";
    const invalid = gridSearch.auditRecognition(chars, { detectionCount: 3, placed: 2 }, {});
    assert(!invalid.ok && /非法字符/.test(invalid.reason), "非法字符应拒绝：" + invalid.reason);
    const valid = charsFromLayout(ENDGAME4);
    const low = gridSearch.auditRecognition(valid, { detectionCount: 10, placed: 4 }, {});
    assert(!low.ok && /比例过低/.test(low.reason), "低覆盖率应拒绝：" + low.reason);
    const outside = gridSearch.auditRecognition(valid, { detectionCount: 4, placed: 4, outOfBoard: 1 }, {});
    assert(!outside.ok && /越出棋盘/.test(outside.reason), "出界框应拒绝：" + outside.reason);

    const over = new Array(90).fill(cells.EMPTY);
    over[0] = "K";
    over[89] = "k";
    for (let i = 1; i <= 11; i++) over[i] = "P";
    const excessive = gridSearch.auditRecognition(over, { detectionCount: 13, placed: 13 }, {});
    assert(!excessive.ok && /兵种超限/.test(excessive.reason), "兵种超限应拒绝：" + excessive.reason);
});

check("含 1 个 UNKNOWN：三态数量准确（不算空、不算子）", function () {
    const chars = charsFromLayout(OPENING);
    assert(cells.isPieceChar(chars[0]), "夹具前提：0 号格原本是棋子");
    chars[0] = cells.UNKNOWN;
    const counts = cells.count(chars);
    eq(counts.pieces, 31, "棋子数");
    eq(counts.empty, 58, "空格数");
    eq(counts.unknown, 1, "未知格数");
    eq(counts.pieces + counts.empty + counts.unknown, 90, "三态之和");
    const text = cells.toTextArray(chars);
    eq(text[0], "?", "文本层未知格");
});

check("cells.countCells 与 count 在字符层一致", function () {
    const chars = charsFromLayout(OPENING);
    const assigned = gridSearch.scoreCandidate(detectionsOf(OPENING, TRUTH_GRID), TRUTH_GRID, {}).cells;
    const fromCells = cells.countCells(assigned);
    const fromChars = cells.count(chars);
    eq(fromCells.pieces, fromChars.pieces, "棋子数");
    eq(fromCells.empty, fromChars.empty, "空格数");
});

console.log("== 几何搜索（缓存框，不跑模型）==");

check("开局：从错位种子找回真实网格", function () {
    const detections = detectionsOf(OPENING, TRUTH_GRID);
    const found = gridSearch.searchByDetections(detections, { seeds: SEEDS, radius: 30, step: 6, orientation: "RED_BOTTOM" });
    assert(found.ok, "应接受：" + (found.reason || ""));
    eq(found.grid.originX, TRUTH_GRID.originX, "originX");
    eq(found.grid.originY, TRUTH_GRID.originY, "originY");
    eq(found.grid.version, 7, "几何版本应原样带出（不能被静默改掉）");
    eq(found.best.pieces, 32, "子力数");
    eq(found.best.unknown, 0, "未知格");
    assert(found.best.coverage > 0.99, "覆盖度应接近 1");
});

check("残局 4 子 / 8 子：不被通用门槛拒绝（旧实现要求 ≥24 子）", function () {
    const layouts = [ENDGAME4, ENDGAME8];
    for (let i = 0; i < layouts.length; i++) {
        const detections = detectionsOf(layouts[i], TRUTH_GRID);
        const found = gridSearch.searchByDetections(detections, { seeds: SEEDS, radius: 30, step: 6, orientation: "RED_BOTTOM" });
        assert(found.ok, layouts[i] + " 应接受：" + (found.reason || ""));
        assert(found.best.pieces < 24, "这就是旧门槛会拒掉的局面（" + found.best.pieces + " 子）");
    }
});

check("残局 2 子（帅 vs 将）也能定位", function () {
    const detections = detectionsOf(ENDGAME2, TRUTH_GRID);
    const found = gridSearch.searchByDetections(detections, { seeds: SEEDS, radius: 30, step: 6, orientation: "RED_BOTTOM" });
    assert(found.ok, "2 子局应接受：" + (found.reason || ""));
    eq(found.best.pieces, 2, "子力数");
    eq(found.best.kings, 2, "将帅数");
});

check("退化网格被拒：没有任何检测框", function () {
    const found = gridSearch.searchByDetections([], { seeds: SEEDS });
    assert(!found.ok, "没有检测框不能算成功");
    assert(/没有检测框/.test(found.reason), "原因应说明缺检测框：" + found.reason);
});

check("退化网格被拒：棋盘完全在搜索范围之外（覆盖度为 0）", function () {
    const far = geometry.createGrid({ originX: 900, originY: 2100, stepX: 115, stepY: 114, orientation: "RED_BOTTOM" });
    const detections = detectionsOf(OPENING, far);
    const found = gridSearch.searchByDetections(detections, { seeds: SEEDS, radius: 30, step: 6, orientation: "RED_BOTTOM" });
    assert(!found.ok, "棋盘不在候选范围内时不能宣称成功");
    assert(found.reason.length > 0, "必须有拒绝原因");
});

check("退化网格被拒：空格对着空格（全空棋盘 + 一堆假框）", function () {
    // 检测框全落在棋盘外 300px 处：覆盖度为 0
    const detections = [];
    for (let i = 0; i < 30; i++) {
        detections.push({ label: "P", score: 0.9, x: 500 + i * 3, y: 200, width: 60, height: 60 });
    }
    const found = gridSearch.searchByDetections(detections, { seeds: SEEDS, radius: 30, step: 6, orientation: "RED_BOTTOM" });
    assert(!found.ok, "框全在棋盘外不能算成功");
    // 而且不能把这种候选选成「最优」（否则代价比较也是坏的）
    const scored = gridSearch.scoreCandidate(detections, found.grid, {});
    assert(scored.pieces === 0 || scored.coverage < 0.5, "退化候选应表现为无棋盘证据（pieces=" + scored.pieces + " coverage=" + scored.coverage + "）");
});

check("兵种超限被拒（33 个兵）", function () {
    const chars = new Array(90).fill(cells.EMPTY);
    chars[0] = "K";
    chars[89] = "k";
    for (let i = 0; i < 33; i++) {
        chars[10 + i] = "P";
    }
    const audit = gridSearch.typeAudit(chars);
    assert(audit.overLimit.length > 0, "33 个兵必须被判定超限");
    const scored = gridSearch.scoreCandidate([], geometry.createGrid({ originX: 0, originY: 0, stepX: 1, stepY: 1 }), {});
    assert(scored.cost >= 0, "评分可用");
});

check("兵种超限默认只当软惩罚（硬拒会把好网格一起废掉）", function () {
    // 造一个「士多检成 5 个」的框集：模型把 3 个士认成 5 个是识别问题，不是几何问题
    const chars = new Array(90).fill(cells.EMPTY);
    chars[0] = "K";
    chars[89] = "k";
    for (let i = 0; i < 5; i++) {
        chars[10 + i] = "A";
    }
    const audit = gridSearch.typeAudit(chars);
    assert(audit.overLimit.length > 0, "5 个士必须被判定超限");

    const detections = [];
    for (let i = 0; i < 90; i++) {
        const ch = chars[i];
        if (!cells.isPieceChar(ch)) {
            continue;
        }
        const point = geometry.pointAt(TRUTH_GRID, i % 9, Math.floor(i / 9));
        detections.push({ label: String(ch).toUpperCase(), score: 0.9, x: point.x, y: point.y });
    }
    const soft = gridSearch.searchByDetections(detections, { seeds: SEEDS, radius: 30, step: 6 });
    assert(soft.ok, "默认（软惩罚）应接受：" + (soft.reason || ""));
    eq(soft.grid.originX, TRUTH_GRID.originX, "软惩罚下也应选中真实网格 originX");
    eq(soft.best.pieces, 7, "子力数");
    const hard = gridSearch.searchByDetections(detections, { seeds: SEEDS, radius: 30, step: 6, requireTypeLimits: true });
    assert(!hard.ok, "requireTypeLimits=true 时应拒绝");
    assert(/兵种超限/.test(hard.reason), "拒绝原因应说明超限：" + hard.reason);
});

check("缺一个将帅默认只当软惩罚（模型漏检将帅是识别问题，不是几何问题）", function () {
    // 真机 2026-09-20：检测模型对帅/将召回差（面板反复报「红帅数量 0」）。
    // 硬拒会让**每个候选**都被否掉 ⇒ `pickGrid 也失败：最优候选不合用：将帅数量异常（1）` ⇒ 定位做不出来。
    const chars = new Array(90).fill(cells.EMPTY);
    chars[0] = "K";                 // 只给红帅（黑将漏检）
    for (let i = 10; i < 20; i++) {
        chars[i] = "P";
    }
    const detections = [];
    for (let i = 0; i < 90; i++) {
        if (!cells.isPieceChar(chars[i])) {
            continue;
        }
        const point = geometry.pointAt(TRUTH_GRID, i % 9, Math.floor(i / 9));
        detections.push({ label: String(chars[i]).toUpperCase(), score: 0.9, x: point.x, y: point.y });
    }
    const soft = gridSearch.searchByDetections(detections, { seeds: SEEDS, radius: 30, step: 6 });
    assert(soft.ok, "默认（软惩罚）应接受缺将帅的候选：" + (soft.reason || ""));
    eq(soft.grid.originX, TRUTH_GRID.originX, "软惩罚下也应选中真实网格 originX");
    const hard = gridSearch.searchByDetections(detections, { seeds: SEEDS, radius: 30, step: 6, requireKings: true });
    assert(!hard.ok && /将帅/.test(hard.reason || ""), "requireKings=true 时应拒绝：" + (hard.reason || ""));
});

check("上一局面的子力数先验：允许吃子（3 2 → 30 仍接受）", function () {
    // 从开局里拿掉两个红兵 → 正好 30 子（不手数，直接由字符数组生成 FEN）
    const chars = charsFromLayout(OPENING);
    let removed = 0;
    for (let i = 0; i < 90 && removed < 2; i++) {
        if (chars[i] === "P") {
            chars[i] = cells.EMPTY;
            removed += 1;
        }
    }
    eq(removed, 2, "应拿掉两个红兵");
    const layout = fen.layoutOf(chars);
    const detections = detectionsOf(layout, TRUTH_GRID);
    eq(detections.length, 30, "夹具应有 30 个框");
    const found = gridSearch.searchByDetections(detections, {
        seeds: SEEDS, radius: 30, step: 6, orientation: "RED_BOTTOM", expectPieces: 32
    });
    assert(found.ok, "有软先验时吃子局面应接受：" + (found.reason || ""));
    eq(found.best.pieces, 30, "子力数");
    const scored = gridSearch.scoreCandidate(detections, found.grid, { expectPieces: 32, expectWeight: 20 });
    assert(scored.cost < 200, "软先验的罚分不应主导（cost=" + scored.cost + "）");
});

console.log("");
console.log("== 汇总：ok " + pass + " / FAIL " + failures.length + " ==");
for (let i = 0; i < failures.length; i++) {
    console.log("FAIL " + failures[i].name + " → " + failures[i].message);
}
const summary = "CONTRACT " + (failures.length === 0 ? "PASS" : "FAIL") +
    " pass=" + pass + " fail=" + failures.length +
    (failures.length ? ("｜首个失败：" + failures[0].name + " → " + failures[0].message) : "");
console.log(summary);
// 兼容 PC（node）与手机（QuickJS）：不依赖 process.exit
if (typeof process !== "undefined" && process && typeof process.exit === "function") {
    process.exit(failures.length === 0 ? 0 : 1);
}
module.exports = { pass: pass, failures: failures, summary: summary };
