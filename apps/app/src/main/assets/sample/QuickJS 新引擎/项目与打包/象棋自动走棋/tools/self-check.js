// @engine quickjs
/**
 * 离线自检：只跑纯 JS 模块（FEN / 几何 / 格点分配 / 时序融合）的断言。
 *
 * 不截图、不加载模型、不点击。可以在手机上直接跑，用来确认「换了引擎/换了打包方式
 * 之后棋规与几何逻辑还是对的」——这是任务书里「纯坐标、FEN、棋规和状态转换可做离线测试」
 * 那一条的落地。
 */
"use strict";

const fen = require("../src/chess/fen.js");
const geometry = require("../src/vision/geometry.js");
const assignment = require("../src/vision/cell-assignment.js");
const fusionModule = require("../src/vision/temporal-fusion.js");

let passed = 0;
const failures = [];

function check(name, body) {
    try {
        const result = body();
        if (result === true || result === undefined) {
            passed++;
            return;
        }
        failures.push(name + " → " + result);
    } catch (error) {
        failures.push(name + " → 异常：" + (error && error.message ? error.message : error));
    }
}

function eq(actual, expected, label) {
    if (actual !== expected) {
        return (label || "值") + " 期望 " + expected + "，实际 " + actual;
    }
    return true;
}

function near(actual, expected, tolerance, label) {
    if (!isFinite(actual) || Math.abs(actual - expected) > tolerance) {
        return (label || "值") + " 期望 " + expected + "±" + tolerance + "，实际 " + actual;
    }
    return true;
}

// ---------------------------------------------------------------- FEN

check("初始局面能解析", function () {
    const parsed = fen.parseFen(fen.START_FEN);
    if (!parsed.ok) return parsed.errors.join("；");
    return eq(fen.pieceCount(parsed.board), 32, "子数");
});

check("解析后再生成等于原布局", function () {
    const parsed = fen.parseFen(fen.START_FEN);
    const rebuilt = fen.buildFen(parsed.board, "w");
    return eq(rebuilt.split(" ")[0], fen.START_FEN.split(" ")[0], "布局");
});

check("少子残局不被拒绝（原包 MIN_PIECES:9 的问题）", function () {
    const parsed = fen.parseFen("3k5/9/9/9/9/9/9/9/4A4/4K4 w - - 0 1");
    if (!parsed.ok) return "解析失败：" + parsed.errors.join("；");
    const validation = fen.validateBoard(parsed.board);
    return eq(validation.errors.length, 0, "校验错误数");
});

check("行数不对的 FEN 被拒绝", function () {
    const parsed = fen.parseFen("9/9/9/9/9/9/9/9/9 w - - 0 1");
    return parsed.ok === false || "竟然通过了";
});

check("超宽行被拒绝", function () {
    const parsed = fen.parseFen("rnbakabnr9/9/9/9/9/9/9/9/9/9 w - - 0 1");
    return parsed.ok === false || "竟然通过了";
});

check("非法字符被拒绝", function () {
    const parsed = fen.parseFen("rnbakabnX/9/9/9/9/9/9/9/9/RNBAKABNR w - - 0 1");
    return parsed.ok === false || "竟然通过了";
});

check("缺将帅会被报出来（不静默补子）", function () {
    const parsed = fen.parseFen("9/9/9/9/9/9/9/9/9/9 w - - 0 1");
    if (!parsed.ok) return "解析失败（也算通过）";
    const validation = fen.validateBoard(parsed.board);
    return validation.errors.length > 0 || "没有报错";
});

check("未知格默认不能生成 FEN", function () {
    const cells = new Array(90);
    cells[0] = "?";
    try {
        fen.buildFen(cells, "w");
        return "竟然成功了";
    } catch (error) {
        return true;
    }
});

check("轮次未知时不塞 side 字段", function () {
    const cells = new Array(90);
    try {
        fen.buildFen(cells, "unknown");
        return "竟然成功了";
    } catch (error) {
        return true;
    }
});

check("象过河会被判为错误", function () {
    const cells = new Array(90);
    cells[fen.index(2, 4)] = "B";
    cells[fen.index(4, 9)] = "K";
    cells[fen.index(4, 0)] = "k";
    const validation = fen.validateBoard(cells);
    return validation.errors.length > 0 || "没有报错";
});

// ---------------------------------------------------------------- 几何

const grid = geometry.createGrid({ originX: 100, originY: 200, stepX: 40, stepY: 41 });
const grid180 = geometry.createGrid({
    originX: 100, originY: 200, stepX: 40, stepY: 41, orientation: geometry.ORIENTATION_RED_TOP
});

check("规范坐标 → 屏幕坐标", function () {
    const point = geometry.pointAt(grid, 2, 3);
    return eq(point.x, 180, "x") === true && eq(point.y, 323, "y") === true;
});

check("屏幕坐标 → 最近交点（含残差）", function () {
    const hit = geometry.nearest(grid, 181, 322);
    if (hit.col !== 2 || hit.row !== 3) return "落到了 (" + hit.col + "," + hit.row + ")";
    return near(hit.residual, Math.sqrt(2), 0.001, "残差");
});

check("棋盘外的点不会被误判为可靠交点", function () {
    const hit = geometry.nearest(grid, 100 + 40 * 12, 200);
    return hit.inside === false || "判成了内部点";
});

check("红方在上时规范坐标翻转", function () {
    const point = geometry.pointAt(grid180, 0, 0);
    // 规范 (0,0) 在红方在上时位于屏幕右下角
    return eq(point.x, 100 + 8 * 40, "x") === true && eq(point.y, 200 + 9 * 41, "y") === true;
});

check("180° 下往返映射一致", function () {
    const point = geometry.pointAt(grid180, 6, 8);
    const hit = geometry.nearest(grid180, point.x, point.y);
    return eq(hit.col, 6, "col") === true && eq(hit.row, 8, "row") === true;
});

check("稳健拟合能还原原点与格距", function () {
    const truth = geometry.createGrid({ originX: 137, originY: 421, stepX: 44.5, stepY: 45.2 });
    const samples = [];
    const corners = [[0, 0], [8, 0], [0, 9], [8, 9], [4, 4], [2, 7], [6, 2]];
    corners.forEach(function (pair) {
        const point = geometry.pointAt(truth, pair[0], pair[1]);
        samples.push({ col: pair[0], row: pair[1], x: point.x + 0.6, y: point.y - 0.4 });
    });
    // 一个明显错误中心（例如把相邻格的框算进来了）
    samples.push({ col: 3, row: 5, x: geometry.pointAt(truth, 3, 5).x + 31, y: geometry.pointAt(truth, 3, 5).y + 27 });
    const fitted = geometry.fit(samples, { version: 2 });
    if (!fitted) return "拟合失败";
    const checks = [
        near(fitted.originX, 137, 2, "originX"),
        near(fitted.originY, 421, 2, "originY"),
        near(fitted.stepX, 44.5, 0.5, "stepX"),
        near(fitted.stepY, 45.2, 0.5, "stepY")
    ];
    const bad = checks.filter(function (item) { return item !== true; });
    if (bad.length > 0) return bad.join("；");
    return fitted.quality.inlierCount < fitted.quality.sampleCount || "离群点没有被剔除";
});

check("样本不足时不给出几何", function () {
    return geometry.fit([{ col: 0, row: 0, x: 1, y: 1 }]) === null || "竟然拟合出来了";
});

check("几何可信度门槛", function () {
    const truth = geometry.createGrid({
        originX: 10, originY: 20, stepX: 40, stepY: 40,
        quality: { sampleCount: 5, inlierCount: 5, maxResidual: 0.1, meanResidual: 0.05 }
    });
    if (!geometry.isTrustworthy(truth)) return "残差很小却判为不可信";
    const poor = geometry.createGrid({
        originX: 10, originY: 20, stepX: 40, stepY: 40,
        quality: { sampleCount: 5, inlierCount: 5, maxResidual: 0.9, meanResidual: 0.5 }
    });
    return geometry.isTrustworthy(poor) === false || "残差很大却判为可信";
});

// ---------------------------------------------------------------- 格点分配

function detection(label, col, row, score) {
    const point = geometry.pointAt(grid, col, row);
    return { label: label, score: score == null ? 0.9 : score, x: point.x, y: point.y };
}

check("交点上的框落到正确格", function () {
    const result = assignment.assign([detection("R", 0, 9), detection("k", 4, 0)], grid);
    const first = result.cells[fen.index(0, 9)];
    const second = result.cells[fen.index(4, 0)];
    if (first.state !== assignment.STATE_PIECE || first.piece !== "R") return "左车没有落到 (0,9)";
    if (second.state !== assignment.STATE_PIECE || second.piece !== "k") return "黑将没有落到 (4,0)";
    return eq(result.stats.unknown, 0, "未知格数");
});

check("同类重复框合并、不产生冲突", function () {
    const result = assignment.assign([detection("P", 2, 6, 0.8), detection("P", 2, 6, 0.95)], grid);
    const cell = result.cells[fen.index(2, 6)];
    if (cell.state !== assignment.STATE_PIECE) return "被判成 " + cell.state;
    if (cell.conflict) return "同类重复框被误判成冲突";
    return near(cell.score, 0.95, 0.001, "取最高分");
});

check("两类分数接近时标记冲突并判未知", function () {
    const result = assignment.assign([detection("R", 4, 4, 0.8), detection("P", 4, 4, 0.75)], grid);
    const cell = result.cells[fen.index(4, 4)];
    if (cell.state !== assignment.STATE_UNKNOWN) return "没有判未知，而是 " + cell.state;
    return cell.conflict === true || "没有标记冲突";
});

check("中心离交点太远的框判未知", function () {
    const point = geometry.pointAt(grid, 1, 1);
    const result = assignment.assign([{ label: "N", score: 0.9, x: point.x + 30, y: point.y + 28 }], grid);
    const cell = result.cells[fen.index(1, 1)];
    if (cell.state === assignment.STATE_UNKNOWN) return true;
    // 也可能被算到相邻格上；那里同样必须是未知（不能当成可靠棋子）
    const moved = geometry.nearest(grid, point.x + 30, point.y + 28);
    const other = result.cells[fen.index(moved.col, moved.row)];
    return other.state === assignment.STATE_UNKNOWN || "错位的框被当成了可靠棋子";
});

check("图像质量差时空格降级为未知", function () {
    const good = assignment.assign([], grid, { frameQuality: 1 });
    const poor = assignment.assign([], grid, { frameQuality: 0.3 });
    if (good.cells[0].state !== assignment.STATE_EMPTY) return "好帧没有给出空格";
    return poor.cells[0].state === assignment.STATE_UNKNOWN || "差帧仍然声称知道空格";
});

check("低分框被丢弃", function () {
    const result = assignment.assign([detection("C", 1, 7, 0.2)], grid, { minScore: 0.45 });
    return result.cells[fen.index(1, 7)].state === assignment.STATE_EMPTY || "低分框仍然入格";
});

// ---------------------------------------------------------------- 时序融合

function frameFrom(cells, extras) {
    return Object.assign({
        cells: cells,
        geometryVersion: 1,
        modelVersion: "test",
        sessionId: "s1",
        quality: 1
    }, extras || {});
}

check("三帧一致才判稳定", function () {
    const one = assignment.assign([detection("R", 0, 9)], grid);
    const fusion = fusionModule.createFusion({ now: function () { return 0; } });
    fusion.add(frameFrom(one.cells, { capturedAt: 0 }));
    const early = fusion.snapshot();
    if (early.stable) return "只有一帧就宣布稳定";
    fusion.add(frameFrom(one.cells, { capturedAt: 100 }));
    fusion.add(frameFrom(one.cells, { capturedAt: 200 }));
    const settled = fusion.snapshot();
    if (!settled.stable) return "三帧一致却仍不稳定：" + settled.reason;
    return settled.cells[fen.index(0, 9)].piece === "R" || "融合后棋子丢了";
});

check("一帧冲突不会污染稳定局面", function () {
    const clean = assignment.assign([detection("R", 0, 9)], grid);
    const dirty = assignment.assign([detection("R", 0, 9, 0.8), detection("P", 0, 9, 0.78)], grid);
    const fusion = fusionModule.createFusion({ now: function () { return 0; } });
    fusion.add(frameFrom(clean.cells, { capturedAt: 0 }));
    fusion.add(frameFrom(dirty.cells, { capturedAt: 100 }));
    fusion.add(frameFrom(clean.cells, { capturedAt: 200 }));
    const settled = fusion.snapshot();
    return settled.cells[fen.index(0, 9)].piece === "R" || "冲突帧盖掉了多数票";
});

check("几何/模型/会话变化会清空历史", function () {
    const cells = assignment.assign([detection("R", 0, 9)], grid).cells;
    const fusion = fusionModule.createFusion({ now: function () { return 0; } });
    fusion.add(frameFrom(cells, { capturedAt: 0 }));
    fusion.add(frameFrom(cells, { capturedAt: 100 }));
    fusion.add(frameFrom(cells, { capturedAt: 200 }));
    fusion.reset("测试重置");
    const after = fusion.snapshot();
    if (after.stable) return "重置后仍然稳定";
    fusion.add(frameFrom(cells, { capturedAt: 300, geometryVersion: 2 }));
    return fusion.snapshot().stable === false || "换了几何版本仍然沿用旧观测";
});

check("超出时间跨度的旧帧会被丢掉", function () {
    const cells = assignment.assign([detection("R", 0, 9)], grid).cells;
    const fusion = fusionModule.createFusion({ minFrames: 3, maxSpanMs: 500, now: function () { return 0; } });
    fusion.add(frameFrom(cells, { capturedAt: 0 }));
    fusion.add(frameFrom(cells, { capturedAt: 100 }));
    fusion.add(frameFrom(cells, { capturedAt: 900 }));
    fusion.add(frameFrom(cells, { capturedAt: 1000 }));
    fusion.add(frameFrom(cells, { capturedAt: 1100 }));
    return fusion.frameCount() <= 3 || "旧帧没有被时间窗口裁掉，实际 " + fusion.frameCount();
});

// ---------------------------------------------------------------- 收尾

const summary = "SELF_CHECK " + (failures.length === 0 ? "OK" : "FAILED") +
    " passed=" + passed + " failed=" + failures.length;
console.log(summary);
failures.forEach(function (item, index) {
    console.log("  " + (index + 1) + ") " + item);
});

module.exports = { passed: passed, failures: failures, summary: summary };
