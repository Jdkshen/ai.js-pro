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
const pieceSide = require("../src/vision/piece-side.js");
const recognizer = require("../src/vision/recognizer.js");
const defaultsUtil = require("../src/util/defaults.js");
const gridSearch = require("../src/vision/grid-search.js");
const boardLocator = require("../src/vision/board-locator.js");
const latticeDetector = require("../src/vision/lattice-detector.js");
const glyphRecognizer = require("../src/vision/glyph-recognizer.js");
const circleGridCheck = require("../src/vision/circle-grid-check.js");
const locateBoardEntry = require("./locate-board.js");

let passed = 0;
const failures = [];

function check(name, body) {
    // 定位「哪个用例挂住」用：SELF_CHECK_TRACE=1 时逐条打印（默认安静）。
    // ⚠️ 手机端（QuickJS 宿主）没有 process —— 必须先探测再读，否则整份自检在真机上起不来
    //（2026-09-23 真机冒烟实锤：TypeError: cannot read property 'env' of undefined）。
    if (typeof process !== "undefined" && process && process.env && process.env.SELF_CHECK_TRACE) {
        process.stdout.write("[check] " + name + "\n");
    }
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

check("河沿象位（红相 row 5 = c4/g4）不是「过河」（2026-09-21 真机卡死 14 分钟的真凶）", function () {
    // 标准着法「飞相 e2→c4」后，红相落在河沿 (2,5)/(6,5) —— 合法象位。
    // 旧校验器把象位行写成 {7,8,9}（漏了 5、还误收 8）⇒ 河沿的相每帧被判「过河」⇒
    // 驱动的 inspect 永远不过 ⇒ 算棋全失败 ⇒ 无限 RECOVERING。这条断言在修前必失败。
    const cells = new Array(90);
    cells[fen.index(2, 5)] = "B";
    cells[fen.index(4, 9)] = "K";
    cells[fen.index(4, 0)] = "k";
    const validation = fen.validateBoard(cells);
    if (validation.errors.length > 0) {
        return "河沿象位被误判：" + validation.errors.join("；");
    }
    // 对称：黑象河沿 (2,4)
    const blackCells = new Array(90);
    blackCells[fen.index(2, 4)] = "b";
    blackCells[fen.index(4, 9)] = "K";
    blackCells[fen.index(4, 0)] = "k";
    const blackValidation = fen.validateBoard(blackCells);
    return blackValidation.errors.length === 0 ||
        ("黑象河沿被误判：" + blackValidation.errors.join("；"));
});

check("非象位行（自家半边但不是象位）仍要告警（红线不能放宽）", function () {
    const cells = new Array(90);
    cells[fen.index(2, 6)] = "B";     // row 6：自家半边、非象位
    cells[fen.index(4, 9)] = "K";
    cells[fen.index(4, 0)] = "k";
    const validation = fen.validateBoard(cells);
    const warned = validation.warnings.some(function (text) {
        return text.indexOf("非象位行") >= 0;
    });
    return warned || ("没有对 row 6 的相告警：" + JSON.stringify(validation.warnings));
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

/**
 * 2026-09-14 真机事故：③ 自动走棋卡在「等落子」，日志 48 次
 * `[driver] 确认：UNRELIABLE：有 1 处关键冲突`。
 * 根因：模型把同一格的同一个字在红黑之间抖（C/c），两候选分差 < conflictGap →
 * 视觉层算「关键冲突」→ 验证器一票否决整帧，而抖动每帧都发生 → 永远等不到干净帧。
 * 现在同字异色不再计入关键冲突（棋子是什么、在哪已确定，缺的颜色交给下游纠色/棋规还原），
 * 但仍然判未知格并且留明细，异字冲突照旧一票否决。
 */
check("同字异色冲突不算关键冲突", function () {
    const result = assignment.assign([detection("C", 1, 7, 0.80), detection("c", 1, 7, 0.78)], grid);
    const cell = result.cells[fen.index(1, 7)];
    if (cell.state !== assignment.STATE_UNKNOWN) return "同字异色没判未知，而是 " + cell.state;
    if (result.stats.conflicts !== 0) return "同字异色被算成 " + result.stats.conflicts + " 处关键冲突";
    if (result.stats.sameGlyphConflicts !== 1) return "同字异色没计数：" + result.stats.sameGlyphConflicts;
    const detail = result.stats.conflictCells[0];
    if (!detail || detail.name !== "(1,7)" || detail.sameGlyph !== true) {
        return "冲突格明细缺失/不对：" + JSON.stringify(detail);
    }
    return true;
});

check("异字冲突仍然按关键冲突否决", function () {
    const result = assignment.assign([detection("C", 1, 7, 0.80), detection("N", 1, 7, 0.78)], grid);
    if (result.stats.conflicts !== 1) return "异字冲突计数 = " + result.stats.conflicts;
    if (result.stats.sameGlyphConflicts !== 0) return "异字被记成了同字异色";
    const detail = result.stats.conflictCells[0];
    if (!detail || detail.sameGlyph !== false) return "异字明细标错：" + JSON.stringify(detail);
    if (detail.best !== "C" || detail.second !== "N") return "明细候选不对：" + JSON.stringify(detail);
    return true;
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

// ---- 低分框的两种正确处理（2026-09-14 真机事故：黑将被漏成空格）----
// 人审绘制（src/vision/overlay-draw.js）把原始检测框画出来才发现的：
// 同一个黑将检出两个框（0.42 / 0.26），而 minScore=0.45 把两个都丢掉 → 那格变**空格** →
// 盘面凭空少一个将，而未知格/冲突/数量这些闸门都拦不住「空格」→ 驱动永远卡住。

check("低分框（同标签 ≥2 个）算一致证据 → 当棋子，不能当空格", function () {
    const result = assignment.assign([
        detection("k", 4, 0, 0.42),
        detection("k", 4, 0, 0.26)
    ], grid);
    const cell = result.cells[fen.index(4, 0)];
    if (cell.state !== assignment.STATE_PIECE) {
        return "重复检出的低分框应该判成棋子（那格是黑将），实际 " + cell.state;
    }
    return cell.piece === "k" ? true : ("读成了 " + cell.piece);
});

check("低分框（孤立一个）→ 未知格，也不许当空格", function () {
    const result = assignment.assign([detection("k", 4, 0, 0.3)], grid);
    const cell = result.cells[fen.index(4, 0)];
    if (cell.state !== assignment.STATE_UNKNOWN) {
        return "孤立低分框应判未知格（“有东西但没把握”），实际 " + cell.state;
    }
    return cell.piece === null ? true : "未知格不该带兵种";
});

check("低于 weakScore 的框仍然丢弃（别把噪声当证据）", function () {
    const result = assignment.assign([detection("k", 4, 0, 0.1)], grid);
    return result.cells[fen.index(4, 0)].state === assignment.STATE_EMPTY || "0.1 的框不该入格";
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

// ---------------------------------------------------------------- 走法规则（R01–R04）

const rules = require("../src/chess/rules.js");
const legal = require("../src/chess/legal-moves.js");
const transition = require("../src/chess/transition.js");
const locator = require("../src/vision/board-locator.js");

function boardOf(pieces) {
    const board = new Array(90);
    for (let i = 0; i < 90; i++) {
        board[i] = null;
    }
    pieces.forEach(function (item) {
        board[fen.index(item[1], item[2])] = item[0];
    });
    return board;
}

function moveSet(board, col, row) {
    return rules.pseudoMoves(board, col, row).map(function (move) {
        return move.toCol + "," + move.toRow;
    }).sort();
}

check("R01 蹩马腿：马腿被堵就走不了那个方向", function () {
    const open = boardOf([["N", 1, 2], ["K", 4, 9], ["k", 4, 0]]);
    if (moveSet(open, 1, 2).indexOf("2,4") < 0) return "没有堵腿时也走不到 (2,4)";
    const blocked = boardOf([["N", 1, 2], ["P", 1, 3], ["K", 4, 9], ["k", 4, 0]]);
    return moveSet(blocked, 1, 2).indexOf("2,4") < 0 || "马腿被堵仍然能走到 (2,4)";
});

check("R01b 塞象眼：象眼被堵就走不了那个方向", function () {
    const open = boardOf([["B", 2, 9], ["K", 4, 9], ["k", 4, 0]]);
    if (moveSet(open, 2, 9).indexOf("4,7") < 0) return "没有塞眼时也走不到 (4,7)";
    const blocked = boardOf([["B", 2, 9], ["P", 3, 8], ["K", 4, 9], ["k", 4, 0]]);
    return moveSet(blocked, 2, 9).indexOf("4,7") < 0 || "象眼被堵仍然能走到 (4,7)";
});

check("R03 象不过河、士与将不出九宫", function () {
    // 红相在 (2,5)：田字目标 (0,3)/(4,3) 都已过河，必须被拦掉
    const board = boardOf([["B", 2, 5], ["A", 4, 8], ["K", 4, 9], ["k", 3, 0]]);
    const elephant = moveSet(board, 2, 5);
    if (elephant.indexOf("0,3") >= 0 || elephant.indexOf("4,3") >= 0) return "红相走过了河";
    if (elephant.indexOf("0,7") < 0 || elephant.indexOf("4,7") < 0) return "红相走不到 (0,7)/(4,7)：" + elephant.join(" ");
    const advisor = moveSet(board, 4, 8);
    if (advisor.indexOf("3,7") < 0) return "仕走不到 (3,7)";
    const king = moveSet(board, 4, 9);
    return king.indexOf("4,8") < 0 || "帅走不到 (4,8)";
});

check("R03b 兵：未过河只能向前，过河后可横走、不能后退", function () {
    const board = boardOf([["P", 2, 6], ["K", 4, 9], ["k", 4, 0]]);
    const before = moveSet(board, 2, 6);
    if (before.length !== 1 || before[0] !== "2,5") return "未过河的红兵走法应为 [(2,5)]，实际 " + before.join(" ");
    const crossed = boardOf([["P", 2, 4], ["K", 4, 9], ["k", 4, 0]]);
    const after = moveSet(crossed, 2, 4);
    if (after.indexOf("2,3") < 0 || after.indexOf("1,4") < 0 || after.indexOf("3,4") < 0) {
        return "过河红兵应能前进一步并横走，实际 " + after.join(" ");
    }
    return after.indexOf("2,5") < 0 || "红兵竟然可以后退";
});

check("R02 炮：平移无阻挡；吃子必须恰好一个炮架", function () {
    const board = boardOf([
        ["C", 4, 9], ["P", 4, 6], ["p", 4, 3], ["K", 3, 9], ["k", 3, 0]
    ]);
    const moves = moveSet(board, 4, 9);
    if (moves.indexOf("4,8") < 0 || moves.indexOf("4,7") < 0) return "炮不能平移到炮架之前的空位：" + moves.join(" ");
    if (moves.indexOf("4,5") >= 0) return "炮越过了炮架却没吃子";
    if (moves.indexOf("4,3") < 0) return "炮翻过唯一炮架后应能吃 (4,3)";
    const twoScreens = boardOf([
        ["C", 4, 9], ["P", 4, 7], ["P", 4, 6], ["p", 4, 3], ["K", 3, 9], ["k", 3, 0]
    ]);
    return moveSet(twoScreens, 4, 9).indexOf("4,3") < 0 || "两个炮架竟然还能吃 (4,3)";
});

check("R04 走完不能自将：垫住的子不能走开", function () {
    const board = boardOf([
        ["K", 4, 9], ["R", 4, 5], ["r", 4, 0], ["k", 3, 0]
    ]);
    const offFile = legal.isLegalMove(board, 4, 5, 0, 5);
    if (offFile.legal) return "把垫子挪离本列竟然合法";
    if (offFile.reason.indexOf("被将") < 0) return "没有说明原因是被将，而是：" + offFile.reason;
    const alongFile = legal.isLegalMove(board, 4, 5, 4, 4);
    return alongFile.legal || "沿着本列挪动仍然挡着，应该合法，却被判定：" + alongFile.reason;
});

check("R04b 将帅照面：走开中间的子不合法", function () {
    const board = boardOf([["K", 4, 9], ["N", 4, 5], ["k", 4, 0]]);
    if (!rules.kingsFacing(boardOf([["K", 4, 9], ["k", 4, 0]]))) return "空列上将帅没有判为照面";
    const result = legal.isLegalMove(board, 4, 5, 3, 7);
    return result.legal === false || "走成照面竟然合法";
});

check("R05 唯一合法后继能确定着法", function () {
    // (4,5) 的兵负责挡住将帅同列，避免整盘因「照面」而没有合法着法
    const before = boardOf([["K", 4, 9], ["R", 0, 9], ["P", 4, 5], ["k", 4, 0], ["a", 3, 0]]);
    const after = legal.applyMove(before, rules.pseudoMoves(before, 0, 9).filter(function (move) {
        return move.toCol === 0 && move.toRow === 8;
    })[0]).board;
    const result = transition.infer(before, "w", after);
    if (result.status !== transition.STATUS.MOVE) return "没有判成唯一着法，而是 " + result.status;
    return result.moves[0].iccs === "a0a1" || "着法记错：" + result.moves[0].iccs;
});

check("R05b 局面没变要能被识别出来", function () {
    const board = boardOf([["K", 4, 9], ["R", 0, 9], ["P", 4, 5], ["k", 4, 0]]);
    const result = transition.infer(board, "w", board);
    return result.status === transition.STATUS.UNCHANGED || "判成了 " + result.status;
});

check("R05c 解释不了的变化返回 UNKNOWN（不补造历史）", function () {
    const before = boardOf([["K", 4, 9], ["R", 0, 9], ["P", 4, 5], ["k", 4, 0]]);
    const observed = boardOf([["K", 4, 9], ["R", 8, 8], ["P", 4, 5], ["k", 4, 0], ["C", 1, 1]]);
    const result = transition.infer(before, "w", observed, { allowPair: false });
    return result.status === transition.STATUS.UNKNOWN || "判成了 " + result.status;
});

check("R05d 未知格不参与比对", function () {
    const before = boardOf([["K", 4, 9], ["R", 0, 9], ["P", 4, 5], ["k", 4, 0]]);
    const after = legal.applyMove(before, rules.pseudoMoves(before, 0, 9).filter(function (move) {
        return move.toCol === 0 && move.toRow === 8;
    })[0]).board.slice();
    after[fen.index(0, 9)] = "?"; // 起点看不清
    const result = transition.infer(before, "w", after);
    return result.status === transition.STATUS.MOVE || "起点未知时没能判出着法，而是 " + result.status;
});

// ---------------------------------------------------------------- 棋盘定位

check("格线检测：能从亮度投影里找出线", function () {
    const samples = [];
    const lineAt = [100, 160, 220, 280, 340];
    for (let pos = 60; pos <= 380; pos += 1) {
        let value = 0.9;
        lineAt.forEach(function (line) {
            if (Math.abs(pos - line) <= 1) {
                value = 0.15;
            }
        });
        samples.push({ pos: pos, value: value });
    }
    const result = locator.detectLines(locator.profileFromSamples(samples), {});
    if (result.lines.length !== lineAt.length) return "找到 " + result.lines.length + " 条线（应为 " + lineAt.length + "）";
    const first = result.lines[0];
    if (Math.abs(first - 100) > 1.5) return "第一条线位置偏了：" + first;
    return result.uniformity > 0.95 || "间距一致性太低：" + result.uniformity;
});

check("棋盘定位：合成棋盘上能还原原点与格距", function () {
    const step = 60;
    const originX = 120;
    const originY = 300;
    const verticalLines = [];
    for (let col = 0; col < 9; col++) {
        verticalLines.push(originX + col * step);
    }
    const horizontalLines = [];
    for (let row = 0; row < 10; row++) {
        horizontalLines.push(originY + row * step);
    }
    // 合成一张「棋盘图」：木色背景 + 深色格线 + 若干棋子造成的暗块
    const pieces = [[0, 0], [4, 0], [8, 0], [1, 2], [7, 7], [4, 9]];
    const sampler = function (x, y) {
        let value = 0.9;
        for (let i = 0; i < verticalLines.length; i++) {
            if (Math.abs(x - verticalLines[i]) <= 1.5) value = 0.2;
        }
        for (let i = 0; i < horizontalLines.length; i++) {
            if (Math.abs(y - horizontalLines[i]) <= 1.5) value = Math.min(value, 0.2);
        }
        for (let i = 0; i < pieces.length; i++) {
            const px = originX + pieces[i][0] * step;
            const py = originY + pieces[i][1] * step;
            if (Math.abs(x - px) <= 20 && Math.abs(y - py) <= 20) value = Math.min(value, 0.35);
        }
        return value;
    };
    const result = locator.locateByLines(sampler, {
        region: { x: 100, y: 280, width: 520, height: 580 },
        sampleStep: 1
    });
    if (!result.ok) return result.reason;
    const checks = [
        near(result.grid.originX, originX, 2, "originX"),
        near(result.grid.originY, originY, 2, "originY"),
        near(result.grid.stepX, step, 1, "stepX"),
        near(result.grid.stepY, step, 1, "stepY")
    ];
    const bad = checks.filter(function (item) { return item !== true; });
    return bad.length === 0 ? true : bad.join("；");
});

check("棋盘定位：没有格线时明确失败，不硬猜", function () {
    const flat = function () { return 0.8; };
    const result = locator.locateByLines(flat, { region: { x: 0, y: 0, width: 400, height: 400 }, sampleStep: 2 });
    return result.ok === false || "纯色画面上竟然定位成功了";
});

// 真实照片上，最上一行与最下一行的横线正好被棋子压住（残局照片就是这个样子）：
// 格点阵必须把缺的线补回来，而且序号不能错 —— 错一格就是整盘错一行。
check("支撑度定位：被棋子压住两行线时补回格点且序号正确", function () {
    const step = 60;
    const originX = 120;
    const originY = 300;
    const cols = 9;
    const rows = 10;
    const vLines = [];
    const hLines = [];
    for (let col = 0; col < cols; col++) vLines.push(originX + col * step);
    for (let row = 0; row < rows; row++) hLines.push(originY + row * step);
    const sampler = function (x, y) {
        let value = 0.9;
        for (let i = 0; i < vLines.length; i++) {
            if (Math.abs(x - vLines[i]) <= 1.2) value = 0.2;
        }
        for (let i = 0; i < hLines.length; i++) {
            if (Math.abs(y - hLines[i]) <= 1.2) value = Math.min(value, 0.2);
        }
        // 首行 / 末行整行摆满棋子：比格线还暗，于是这两条线的支撑度直接归零
        for (let col = 0; col < cols; col++) {
            const px = originX + col * step;
            const onFirst = Math.abs(y - hLines[0]) <= 22;
            const onLast = Math.abs(y - hLines[rows - 1]) <= 22;
            if (Math.abs(x - px) <= 22 && (onFirst || onLast)) value = Math.min(value, 0.1);
        }
        return value;
    };
    const result = locator.locateBySupport(sampler, {
        region: { x: 100, y: 280, width: 520, height: 580 },
        step: 1
    });
    if (!result.ok) return result.reason;
    const bad = [
        near(result.grid.originX, originX, 3, "originX"),
        near(result.grid.originY, originY, 3, "originY"),
        near(result.grid.stepX, step, 1, "stepX"),
        near(result.grid.stepY, step, 1, "stepY")
    ].filter(function (item) { return item !== true; });
    if (bad.length > 0) {
        return bad.join("；") + "（横线命中 " + result.horizontal.matched + "/" + rows +
            "，起点序号 " + result.horizontal.startIndex + "）";
    }
    if (result.horizontal.quality.reconstructed < 1) {
        return "这次并没有真的「缺线」（补回 " + result.horizontal.quality.reconstructed + " 条），断言失去意义";
    }
    return true;
});

check("手动校准：四个角点能建立几何并给出质量", function () {
    const grid = geometry.createGrid({ originX: 100, originY: 200, stepX: 50, stepY: 50 });
    const corners = [[0, 0], [8, 0], [0, 9], [8, 9]].map(function (pair) {
        const point = geometry.pointAt(grid, pair[0], pair[1]);
        return { col: pair[0], row: pair[1], x: point.x + 0.5, y: point.y - 0.5 };
    });
    const result = locator.locateByCorners(corners, { minSamples: 4 });
    if (!result.ok) return result.reason;
    return near(result.grid.originX, 100, 2, "originX") === true &&
        near(result.grid.stepY, 50, 0.5, "stepY") === true;
});

// ---------------------------------------------------------------- 阵营判定与识别链路

// 真机踩过两次：`Object.assign({}, DEFAULTS, options)` 在 options 里**有这个键但值是 undefined**
// 时会把默认值覆盖成 undefined（frameQuality 丢 → 空格全变未知；sampleStep 丢 → 采样步进变 NaN）。
check("默认值合并：显式传 undefined 不覆盖默认（真机踩过）", function () {
    const merged = defaultsUtil.mergeDefaults({ a: 1, b: 2, c: 3 }, { a: 5, b: undefined });
    if (merged.a !== 5) {
        return "显式传的值没生效：a=" + merged.a;
    }
    if (merged.b !== 2) {
        return "undefined 把默认值覆盖掉了：b=" + merged.b;
    }
    if (merged.c !== 3) {
        return "没传的字段应保留默认：c=" + merged.c;
    }
    const empty = defaultsUtil.mergeDefaults({ x: 7 }, null);
    if (empty.x !== 7) {
        return "options 为 null 时应全部用默认值";
    }
    return true;
});

// 象棋盘上红方是红字、黑方是黑字，而模型只有 7 个兵种类别（没有颜色），
// 所以阵营要靠在棋子范围内数「红笔画 / 黑笔画」补回来。
check("阵营判定：红笔画占优 → 红", function () {
    const result = pieceSide.classify([[210, 60, 55], [198, 72, 66], [190, 170, 140], [205, 58, 50]]);
    return result.side === pieceSide.SIDE_RED ||
        ("判成了 " + result.side + "（红 " + result.redShare.toFixed(3) + " / 暗 " + result.darkShare.toFixed(3) + "）");
});

check("阵营判定：黑笔画占优 → 黑", function () {
    const result = pieceSide.classify([[45, 45, 45], [60, 58, 55], [190, 170, 140], [30, 30, 30]]);
    return result.side === pieceSide.SIDE_BLACK ||
        ("判成了 " + result.side + "（红 " + result.redShare.toFixed(3) + " / 暗 " + result.darkShare.toFixed(3) + "）");
});

check("阵营判定：只有木色（没有笔画）→ 未知，不猜", function () {
    const result = pieceSide.classify([[190, 170, 140], [185, 165, 138], [200, 180, 150]]);
    return result.side === pieceSide.SIDE_UNKNOWN || ("竟然判成了 " + result.side);
});

check("阵营判定：套上阵营得到 FEN 字符", function () {
    if (pieceSide.applySide("R", pieceSide.SIDE_RED) !== "R") {
        return "红方应保留大写";
    }
    if (pieceSide.applySide("R", pieceSide.SIDE_BLACK) !== "r") {
        return "黑方应转小写";
    }
    if (pieceSide.applySide("K", pieceSide.SIDE_UNKNOWN) !== "?") {
        return "判不出阵营时应返回 '?'，不能猜";
    }
    return true;
});

check("棋盘外框：罩得住四条边且按格距外扩", function () {
    const grid = geometry.createGrid({ originX: 100, originY: 200, stepX: 50, stepY: 50 });
    const box = geometry.boundsOf(grid, 0.75);
    const a = geometry.pointAt(grid, 0, 0);
    const b = geometry.pointAt(grid, 8, 9);
    if (!(box.x < a.x && box.y < a.y)) {
        return "左上角没被罩住：框 " + JSON.stringify(box);
    }
    if (!(box.x + box.width > b.x && box.y + box.height > b.y)) {
        return "右下角没被罩住";
    }
    if (Math.abs(box.x - (a.x - 37.5)) > 1) {
        return "外扩量不对，box.x=" + box.x + "（期望 " + (a.x - 37.5) + "）";
    }
    return true;
});

check("FEN 布局：未知格用 ? 表示（不允许时要报错）", function () {
    const cells = new Array(90).fill(null);
    cells[0] = "R";
    cells[1] = "?";
    const layout = fen.layoutOf(cells, { allowUnknown: true });
    if (layout.indexOf("?") < 0) {
        return "未知格没有用 ? 表示：" + layout;
    }
    let threw = false;
    try {
        fen.layoutOf(cells, {});
    } catch (error) {
        threw = true;
    }
    return threw || "不允许未知格时应当报错";
});

// 端到端（全程用桩，不加载模型、不截图）：
// 按标准开局摆一盘「真值棋」，把它换成模型会给出的东西（7 个兵种字母、没有颜色）当检测框，
// 再用「真值颜色」当取像素的结果，看整条链路能不能还原出开局的 FEN 布局。
check("识别链路：桩检测器 + 桩取色 → 还原开局 FEN 布局", function () {
    const grid = geometry.createGrid({ originX: 100, originY: 200, stepX: 50, stepY: 50 });
    const truth = fen.parseFen(fen.START_FEN).board;
    const box = geometry.boundsOf(grid, 0.75);
    const detections = [];
    for (let index = 0; index < 90; index++) {
        const ch = truth[index];
        if (!ch) {
            continue;
        }
        const point = geometry.pointAt(grid, index % 9, Math.floor(index / 9));
        // 检测框给的是**裁剪图**坐标：这里减掉裁剪偏移，顺带把「坐标要加回去」这件事锁死
        detections.push({
            label: ch.toUpperCase(),
            score: 0.9,
            x: point.x + 1.5 - box.x,
            y: point.y - 1.0 - box.y
        });
    }
    // 一枚低分假框：必须被门槛挡掉，不能变成棋子
    detections.push({ label: "P", score: 0.2, x: 25, y: 25 });

    const result = recognizer.recognize({
        image: { stub: true },
        grid: grid,
        // 显式传 undefined：必须仍然是默认值（否则低分框不会被挡、空格会全部变未知）
        minScore: undefined,
        frameQuality: undefined,
        sideSampleStep: undefined,
        detect: function () { return detections; },
        crop: function (image, cropBox) { return { stub: true, box: cropBox }; },
        rgbAt: function (image, x, y) {
            const hit = geometry.nearest(grid, x, y);
            if (!hit.inside) {
                return [190, 170, 140];
            }
            const ch = truth[hit.row * 9 + hit.col];
            if (!ch) {
                return [190, 170, 140]; // 木色：没有笔画
            }
            return ch === ch.toUpperCase() ? [205, 60, 55] : [45, 45, 45];
        }
    });
    if (!result.ok) {
        return result.reason;
    }
    const expected = fen.START_FEN.split(" ")[0];
    if (result.layout !== expected) {
        return "布局不一致：\n" + result.layout + "\n应为：\n" + expected;
    }
    if (result.stats.placed !== 32) {
        return "落到格上的棋子应为 32，实际 " + result.stats.placed;
    }
    if (result.unknownCells !== 0) {
        return "不应该有未知格，实际 " + result.unknownCells + "（" + result.layout + "）";
    }
    if (result.stats.sideUnknown !== 0) {
        return "不应该有判不出阵营的棋子，实际 " + result.stats.sideUnknown;
    }
    return true;
});

// 同一条链路，但走宿主自带的「区域检测」：`detect(image, {region})` 会把检测框坐标
// **回移**到全图（Java 侧 detectRgba 里 packed[i] += regionX）。这条断言就是把这个契约锁死：
// 桩检测器按「区域坐标 + 区域原点」返回，最后的布局必须与裁剪模式一致 —— 两处都加一次偏移就会错。
check("识别链路：区域检测模式（坐标已回移）→ 同样还原开局", function () {
    const grid = geometry.createGrid({ originX: 100, originY: 200, stepX: 50, stepY: 50 });
    const truth = fen.parseFen(fen.START_FEN).board;
    const seenRegions = [];
    const result = recognizer.recognize({
        image: { stub: true },
        grid: grid,
        useRegion: true,
        detect: function (image, detectOptions) {
            seenRegions.push(detectOptions.region);
            const region = detectOptions.region || [0, 0, 0, 0];
            const list = [];
            for (let index = 0; index < 90; index++) {
                const ch = truth[index];
                if (!ch) {
                    continue;
                }
                const point = geometry.pointAt(grid, index % 9, Math.floor(index / 9));
                list.push({
                    label: ch.toUpperCase(),
                    score: 0.9,
                    // 宿主行为：区域内算完把坐标**移回全图**，所以这里直接给全图坐标
                    x: point.x,
                    y: point.y
                });
            }
            return list;
        },
        rgbAt: function (image, x, y) {
            const hit = geometry.nearest(grid, x, y);
            if (!hit.inside) {
                return [190, 170, 140];
            }
            const ch = truth[hit.row * 9 + hit.col];
            return !ch ? [190, 170, 140] : (ch === ch.toUpperCase() ? [205, 60, 55] : [45, 45, 45]);
        }
    });
    if (seenRegions.length !== 1) {
        return "应当只调用一次检测，实际 " + seenRegions.length + " 次";
    }
    const region = seenRegions[0];
    if (!region || region[2] < 400 || region[3] < 400) {
        return "传给检测的区域明显不对：" + JSON.stringify(region);
    }
    if (!result.ok) {
        return result.reason;
    }
    const expected = fen.START_FEN.split(" ")[0];
    if (result.layout !== expected) {
        return "布局不一致：\n" + result.layout + "\n应为：\n" + expected;
    }
    return true;
});

// 这条是拿真机识别结果去跑 validateBoard 时才发现的：两个告警条件方向写反了 ——
// 合法局面（兵在偶数列、相在 7/9 行）每一条都报，反而真正的异常不报。
check("局面校验：兵卒列与相象行的告警方向正确", function () {
    const start = fen.parseFen(fen.START_FEN).board;
    const clean = fen.validateBoard(start);
    if (clean.errors.length > 0) {
        return "标准开局不该有错误：" + clean.errors.join("；");
    }
    const noisy = clean.warnings.filter(function (w) {
        return w.indexOf("兵位列") >= 0 || w.indexOf("非象位行") >= 0;
    });
    if (noisy.length > 0) {
        return "合法局面不该报这类告警：" + noisy.join("；");
    }
    // 反向：把红兵放到奇数列、红相放到第 8 行（非象位行）→ 必须报
    const bad = start.slice();
    bad[fen.index(0, 6)] = null;
    bad[fen.index(1, 6)] = "P";
    bad[fen.index(2, 9)] = null;
    bad[fen.index(2, 8)] = "B";
    const flagged = fen.validateBoard(bad).warnings;
    if (flagged.filter(function (w) { return w.indexOf("兵位列") >= 0; }).length === 0) {
        return "红兵在奇数列应当报「不在兵位列」";
    }
    if (flagged.filter(function (w) { return w.indexOf("非象位行") >= 0; }).length === 0) {
        return "红相在第 8 行应当报「非象位行」";
    }
    return true;
});

// 真机踩到：某批图竖线 9/9 全中、步长也对，却因为「给定范围装不下整块棋盘」被拒。
// 9 条线全中时序号本来就是确定的（就是 0..8），不该再拿范围去卡它。
check("支撑度定位：9 条线全中时，范围装不下也要给结果", function () {
    const step = 60;
    const originX = 120;
    const originY = 300;
    const vLines = [];
    const hLines = [];
    for (let col = 0; col < 9; col++) vLines.push(originX + col * step);
    for (let row = 0; row < 10; row++) hLines.push(originY + row * step);
    // 棋子只放在中间几行：四边的线都露出来，两轴都能全中（本轮要测的不是缺线补回）
    const pieces = [[4, 4], [3, 5], [5, 5], [2, 6], [6, 6]];
    const sampler = function (x, y) {
        let value = 0.9;
        for (let i = 0; i < vLines.length; i++) {
            if (Math.abs(x - vLines[i]) <= 1.2) value = 0.2;
        }
        for (let i = 0; i < hLines.length; i++) {
            if (Math.abs(y - hLines[i]) <= 1.2) value = Math.min(value, 0.2);
        }
        for (let i = 0; i < pieces.length; i++) {
            const px = originX + pieces[i][0] * step;
            const py = originY + pieces[i][1] * step;
            if (Math.abs(x - px) <= 20 && Math.abs(y - py) <= 20) value = Math.min(value, 0.1);
        }
        return value;
    };
    // 故意给一个「扫描范围够大（能扫到全部 9/10 条线）但起点离棋盘很远」的范围：
    // 旧逻辑要求「第 0 条线离范围起点不超过 1.2 个格距」，这种范围会被误拒。
    const result = locator.locateBySupport(sampler, {
        region: { x: 0, y: 0, width: 700, height: 900 },
        step: 1
    });
    if (!result.ok) {
        return "全中却被拒：" + result.reason;
    }
    return near(result.grid.originX, originX, 3, "originX") === true &&
        near(result.grid.stepX, step, 1, "stepX") === true;
});

// ---------------------------------------------------------------- 多帧融合（P2）

const sequenceModule = require("../src/vision/sequence.js");

/** 拼一帧识别结果里那种 cells（只填要点：有子的格、未知格，其余算空）。 */
function stubCells(placements, unknownIndexes) {
    const cells = new Array(90);
    for (let i = 0; i < 90; i++) {
        cells[i] = { state: "EMPTY", piece: null, score: 0, candidates: [], conflict: false, residual: 0 };
    }
    Object.keys(placements || {}).forEach(function (key) {
        cells[Number(key)] = {
            state: "PIECE", piece: placements[key], score: 0.9,
            candidates: [], conflict: false, residual: 0
        };
    });
    (unknownIndexes || []).forEach(function (index) {
        cells[index] = { state: "UNKNOWN", piece: null, score: 0, candidates: [], conflict: false, residual: 0 };
    });
    return cells;
}

// 单帧识别一定会错几个子，而错的那几个每帧都不一样 —— 按格投票就能洗掉。
// 这是 P2 存在的全部理由，所以必须有断言守着。
check("多帧融合：3 帧里有 1 帧认错子 → 投票洗掉", function () {
    const truth = { 0: "r", 9: "n", 40: "K", 80: "P" };
    const wrong = Object.assign({}, truth, { 40: "A" }); // 帅被认成仕
    const sequence = sequenceModule.createSequence({ minFrames: 3, sessionId: "t" });
    sequence.observe({ ok: true, assigned: stubCells(truth, []) }, { geometryVersion: 1, modelVersion: "m" });
    sequence.observe({ ok: true, assigned: stubCells(wrong, []) }, { geometryVersion: 1, modelVersion: "m" });
    const fused = sequence.observe({ ok: true, assigned: stubCells(truth, []) }, { geometryVersion: 1, modelVersion: "m" });
    if (!fused.stable) {
        return "三帧一致（两帧正确）应当稳定，实际：" + fused.reason;
    }
    const expected = fen.layoutOf(
        stubChars(truth), { allowUnknown: true });
    if (fused.layout !== expected) {
        return "融合结果不对：\n" + fused.layout + "\n应为：\n" + expected;
    }
    if (fused.chars[40] !== "K") {
        return "第 40 格应当是 K（被两帧纠正），实际 " + fused.chars[40];
    }
    return true;
});

check("多帧融合：帧数不够时明确说「还不够」，不给结论", function () {
    const sequence = sequenceModule.createSequence({ minFrames: 3, sessionId: "t" });
    sequence.observe({ ok: true, assigned: stubCells({ 0: "r" }, []) }, { geometryVersion: 1, modelVersion: "m" });
    const fused = sequence.observe({ ok: true, assigned: stubCells({ 0: "r" }, []) }, { geometryVersion: 1, modelVersion: "m" });
    if (fused.stable) {
        return "只看了 2 帧就给了稳定结论";
    }
    return fused.reason.indexOf("帧不足") >= 0 || ("原因没说清：" + fused.reason);
});

check("多帧融合：只有一帧看到的子 → 判未知（宁可不判）", function () {
    const sequence = sequenceModule.createSequence({ minFrames: 3, minAgree: 2, sessionId: "t" });
    sequence.observe({ ok: true, assigned: stubCells({ 0: "r", 50: "P" }, []) }, { geometryVersion: 1, modelVersion: "m" });
    sequence.observe({ ok: true, assigned: stubCells({ 0: "r" }, [50]) }, { geometryVersion: 1, modelVersion: "m" });
    const fused = sequence.observe({ ok: true, assigned: stubCells({ 0: "r" }, [50]) }, { geometryVersion: 1, modelVersion: "m" });
    if (fused.chars[50] !== "?") {
        return "只出现一票的格子应当是未知（?），实际 " + fused.chars[50];
    }
    if (fused.stable) {
        return "有未知格时不该判稳定";
    }
    return true;
});

check("多帧融合：几何版本变化 → 旧观测作废，重新数帧", function () {
    const sequence = sequenceModule.createSequence({ minFrames: 3, sessionId: "t" });
    sequence.observe({ ok: true, assigned: stubCells({ 0: "r" }, []) }, { geometryVersion: 1, modelVersion: "m" });
    sequence.observe({ ok: true, assigned: stubCells({ 0: "r" }, []) }, { geometryVersion: 1, modelVersion: "m" });
    const fused = sequence.observe({ ok: true, assigned: stubCells({ 0: "r" }, []) }, { geometryVersion: 2, modelVersion: "m" });
    if (fused.frames !== 1) {
        return "几何变了以后帧数应重新从 1 起，实际 " + fused.frames;
    }
    return fused.stable === false || "几何变了却仍然判稳定";
});

/** 把 {index: char} 变成 90 长的 chars 数组（给 layoutOf 用）。 */
function stubChars(placements) {
    const chars = new Array(90);
    for (let i = 0; i < 90; i++) {
        chars[i] = null;
    }
    Object.keys(placements || {}).forEach(function (key) {
        chars[Number(key)] = placements[key];
    });
    return chars;
}

// ---------------------------------------------------------------- 一致性修复

const consistency = require("../src/chess/consistency.js");

check("棋规修复：将/帅缺失 ⇒ 把本方九宫空格标成未知（不再静默少一个将）", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const cells = [];
    for (let i = 0; i < 90; i++) {
        cells.push({
            state: board[i] ? "PIECE" : "EMPTY",
            piece: board[i] || null,
            score: board[i] ? 0.9 : 0,
            candidates: [],
            conflict: false,
            residual: 0
        });
    }
    // 人为抹掉黑将（模拟真机漏检：那格读成「空格」而不是未知格）
    const kingIndex = fen.index(4, 0);
    cells[kingIndex] = { state: "EMPTY", piece: null, score: 0, candidates: [], conflict: false, residual: 0 };
    const result = consistency.repair(cells);
    if (!result.missingRequired || result.missingRequired.indexOf("k") < 0) {
        return "没有报出「黑将缺失」（missingRequired=" + JSON.stringify(result.missingRequired) + "）";
    }
    if (cells[kingIndex].state !== "UNKNOWN") {
        return "黑将原位（九宫内的空格）应该被标成未知格，实际 " + cells[kingIndex].state;
    }
    return true;
});

// 这一盘的识别结果是**真机跑出来的**（K40，dev-560，chess-shot-01.png），
// 32 格看起来挺好，但棋规一校验就露馅：红仕 3 个（上限 2）、黑卒退到起始线之后。
// 修复应当把这两类"可归因到格子"的错误降级成未知，而不是整盘作废。
const DEVICE_BOARD = [
    // [index, fenChar, score]
    [0, "r", 0.92], [1, "n", 0.93], [2, "b", 0.94], [3, "a", 0.84], [4, "k", 0.88],
    [5, "a", 0.64], [6, "b", 0.94], [8, "r", 0.84],
    [19, "c", 0.91], [24, "c", 0.51], [26, "p", 0.59],
    [27, "p", 0.92], [29, "p", 0.92], [31, "p", 0.93], [33, "p", 0.91], [35, "p", 0.90],
    [54, "P", 0.90], [56, "P", 0.93], [58, "P", 0.92], [60, "P", 0.89], [62, "P", 0.90],
    [68, "C", 0.86], [70, "C", 0.92],
    [81, "R", 0.78], [82, "B", 0.85], [83, "A", 0.80], [84, "A", 0.89], [85, "K", 0.84],
    [86, "A", 0.87], [87, "B", 0.77], [88, "N", 0.90], [89, "R", 0.85]
];

function deviceCells() {
    const cells = new Array(90);
    for (let i = 0; i < 90; i++) {
        cells[i] = { state: "EMPTY", piece: null, score: 0, candidates: [], conflict: false, residual: 0 };
    }
    DEVICE_BOARD.forEach(function (item) {
        cells[item[0]] = {
            state: "PIECE", piece: item[1], score: item[2],
            candidates: [{ label: item[1], score: item[2], count: 1, residual: 0.05 }],
            conflict: false, residual: 0.05
        };
    });
    return cells;
}

check("一致性修复：真机那盘能被棋规反查出问题（先确认它确实有问题）", function () {
    const before = fen.validateBoard(consistency.boardOf(deviceCells()));
    if (before.errors.length === 0) {
        return "这盘本该有结构性错误（红仕 3 个 / 黑卒退到起始线之后），现在一个都没有，断言失去意义";
    }
    return before.errors.length >= 2 || ("错误数偏少：" + before.errors.join("；"));
});

check("一致性修复：违规格降级为未知后，结构性错误清空", function () {
    const cells = deviceCells();
    const result = consistency.repair(cells);
    if (!result.changed) {
        return "应当修复出至少一处降级";
    }
    if (result.after.length !== 0) {
        return "修复后仍有错误：" + result.after.join("；") + "（降级了 " +
            result.demoted.map(function (d) { return d.char + "@" + d.row + "," + d.col; }).join(" ") + "）";
    }
    if (result.demoted.length > 4) {
        return "降级太多（" + result.demoted.length + " 格），会把真棋子也扔掉";
    }
    const chars = consistency.charsOf(cells);
    const unknown = chars.filter(function (ch) { return ch === "?"; }).length;
    if (unknown !== result.demoted.length) {
        return "未知格数（" + unknown + "）与降级次数（" + result.demoted.length + "）不一致";
    }
    return true;
});

check("一致性修复：合法的标准开局不该被降级", function () {
    const start = fen.parseFen(fen.START_FEN).board;
    const cells = new Array(90);
    for (let i = 0; i < 90; i++) {
        cells[i] = start[i]
            ? { state: "PIECE", piece: start[i], score: 0.9, candidates: [], conflict: false, residual: 0.05 }
            : { state: "EMPTY", piece: null, score: 0, candidates: [], conflict: false, residual: 0 };
    }
    const result = consistency.repair(cells);
    return result.demoted.length === 0 || ("合法开局被降级了：" + JSON.stringify(result.demoted));
});

// ---------------------------------------------------------------- 结果文本

// recognize-board 依赖宿主适配层，但适配层在离线环境下会退化成空对象，所以这里能 require
let recognizeBoard = null;
try {
    recognizeBoard = require("./recognize-board.js");
} catch (error) {
    recognizeBoard = null;
}

check("结果文本：模型要报出文件名，不能是 undefined", function () {
    if (!recognizeBoard) {
        return "require ./recognize-board.js 失败，跳过";
    }
    const stub = {
        ok: true,
        stats: {
            placed: 32, unknownCells: 2, unknown: 2, detected: 34,
            sideRed: 16, sideBlack: 16, sideUnknown: 0, sideReasons: {}
        },
        located: { reason: "桩定位", timing: { locateMs: 1 } },
        timing: { cropMs: 0, detectMs: 1, sideMs: 1 },
        native: { inferenceMs: 0.5 },
        elapsedMs: 10,
        detectionCount: 34,
        unknownCells: 2,
        // ⚠️ 这里就是曾经写成 result.model.model 的地方（loaded.request 里只有 modelPath）
        model: { modelPath: "C:\\x\\assets\\models\\chessai_160x320_v5r.onnx", inputSize: 160, inputWidth: 160, inputHeight: 320 },
        modelSource: "脚本目录",
        layout: "9/9",
        chars: new Array(90).fill("?")
    };
    let text;
    try {
        text = recognizeBoard.describeResult(stub);
    } catch (error) {
        return "describeResult 抛错：" + (error && error.message ? error.message : error);
    }
    if (text.indexOf("undefined") >= 0) {
        return "结果文本里出现了 undefined：" + text.split("\n").slice(0, 3).join(" | ");
    }
    if (text.indexOf("chessai_160x320_v5r.onnx") < 0) {
        return "没有报出模型文件名：" + text.split("\n").slice(0, 3).join(" | ");
    }
    return true;
});

// ---------------------------------------------------------------- 算棋（Pikafish）

const engineModule = require("../src/engine/engine.js");   // 局面工具（inspect/resolvePosition/describe…）
const uciModule = require("../src/engine/uci.js");

// ⚠️ 2026-09-24：内置 JS 搜索/eval/本地 UCI 引擎已整体删除（用户「js 引擎相关的东西都删除」）。
// 原来的评估/搜索/自我对弈断言随之删除；算棋只剩 Pikafish 一条路，下面的断言都不需要真引擎：
// 适配器的选路是纯函数，真机契约由 tools/regress + 设备上的契约测试负责。
const pikafishEngine = require("../src/engine/pikafish-engine.js");

check("算棋·Pikafish 适配：排除表在候选里跳过（MultiPV 选路）", function () {
    const candidates = [
        { move: "b2e2", scoreCp: 10 },
        { move: "h2e2", scoreCp: 8 },
        { move: "b0c2", scoreCp: 5 }
    ];
    const picked = pikafishEngine.pickCandidate(candidates, ["b2e2"], "b2e2");
    if (!picked || picked.move !== "h2e2") {
        return "应当跳过 b2e2 选 h2e2，实际 " + JSON.stringify(picked);
    }
    const plain = pikafishEngine.pickCandidate(candidates, null, "b2e2");
    return (plain && plain.move === "b2e2") ||
        ("没有排除表就该取头名，实际 " + JSON.stringify(plain));
});

check("算棋·Pikafish 适配：pickCandidate 是纯选路（全被排除返回 null；放宽在 analyze 做）", function () {
    const candidates = [{ move: "b2e2" }, { move: "h2e2" }];
    const picked = pikafishEngine.pickCandidate(candidates, ["b2e2", "h2e2"], "b2e2");
    if (picked !== null) {
        return "全被排除却还给了 " + JSON.stringify(picked);
    }
    // 引擎没回候选（极快收敛只给 bestmove）时也要能按兜底着法判断
    const fallback = pikafishEngine.pickCandidate(null, ["b2e2"], "b2e2");
    return fallback === null ||
        ("空候选 + 被排除的兜底着法应当为 null，实际 " + JSON.stringify(fallback));
});

check("算棋·Pikafish 适配：全被排除不再交给 JS —— 加宽扫掠 + 放宽（和棋那局的真机修法）", function () {
    const plan = pikafishEngine.multiPvPlan;
    if (typeof plan !== "function") {
        return "适配器没有导出 multiPvPlan";
    }
    if (plan(3, 1) !== 4 || plan(8, 1) !== 5) {
        return "第一遍计划不对（3→" + plan(3, 1) + "，8→" + plan(8, 1) + "）";
    }
    if (plan(8, 2) !== 9 || plan(20, 2) !== 12) {
        return "第二遍收宽计划不对（8→" + plan(8, 2) + "，20→" + plan(20, 2) + "）";
    }
    const board = fen.parseFen(fen.START_FEN).board;
    const moves = legal.allLegalMoves(board, "w").map(function (m) { return m.iccs; });
    const banned = moves.slice(0, 5);
    const wanted = moves[5];
    const previous = global.$pikafish;
    const calls = [];
    let wideExtra = [wanted];   // 第二遍（加宽后）附带的表外候选；测「放宽」时置空
    const fake = {
        available: function () { return true; },
        isRunning: function () { return true; },
        start: function () { return { ok: true }; },
        quit: function () { },
        analyze: function (value, movetime, timeoutMs, multiPv) {
            calls.push(multiPv);
            const list = (multiPv || 1) > 5 ? banned.concat(wideExtra) : banned.slice();
            return {
                ok: true,
                move: list[0],
                candidates: list.map(function (m, i) { return { move: m, scoreCp: 12 - i, depth: 12 }; })
            };
        }
    };
    global.$pikafish = fake;
    try {
        const swept = pikafishEngine.analyze({ board: board, side: "w" },
            { excludeIccs: banned, movetime: 10 });
        if (!swept.ok || swept.iccs !== wanted || swept.excludeRelaxed) {
            return "加宽扫掠没有挑出表外着法：" + JSON.stringify({ r: swept, calls: calls });
        }
        if (calls.length < 2 || !(calls[1] > 5)) {
            return "第二遍没有加宽 MultiPV：" + JSON.stringify(calls);
        }
        // 两遍都全被排除 → 放宽（ok 且带标记），不再失败交给路由回退
        wideExtra = [];
        calls.length = 0;
        const relaxed = pikafishEngine.analyze({ board: board, side: "w" },
            { excludeIccs: banned, movetime: 10 });
        if (!relaxed.ok || relaxed.excludeRelaxed !== true) {
            return "全被排除时应当放宽而不是失败：" + JSON.stringify(relaxed);
        }
        if (banned.indexOf(relaxed.iccs) < 0) {
            return "放宽后应当取引擎头名（表内着法），实际 " + relaxed.iccs;
        }
        return true;
    } finally {
        pikafishEngine.stop();
        if (previous === undefined) { delete global.$pikafish; }
        else { global.$pikafish = previous; }
    }
});

check("算棋·Pikafish：已确认棋谱走新 API，旧宿主安全退回当前 FEN", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const move = legal.allLegalMoves(board, "w")[0].iccs;
    const command = "position fen " + fen.START_FEN;
    const calls = [];
    const previous = global.$pikafish;
    const fake = {
        available: function () { return true; },
        isRunning: function () { return true; },
        start: function () { return { ok: true }; },
        quit: function () { },
        analyze: function (value) {
            calls.push({ method: "fen", value: value });
            return { ok: true, move: move, candidates: [{ move: move, scoreCp: 0, depth: 1 }] };
        },
        analyzePosition: function (value) {
            calls.push({ method: "history", value: value });
            return { ok: true, move: move, candidates: [{ move: move, scoreCp: 0, depth: 1 }] };
        }
    };
    global.$pikafish = fake;
    try {
        const withHistory = pikafishEngine.analyze({ board: board, side: "w" },
            { positionCommand: command, historyStatus: "complete", movetime: 10 });
        if (!withHistory.ok || !withHistory.historyUsed || calls[0].method !== "history" ||
            calls[0].value !== command) {
            return "新宿主未收到棋谱命令：" + JSON.stringify({ result: withHistory, calls: calls });
        }
        delete fake.analyzePosition;
        const legacy = pikafishEngine.analyze({ board: board, side: "w" },
            { positionCommand: command, historyStatus: "complete", movetime: 10 });
        if (!legacy.ok || legacy.historyUsed || calls[1].method !== "fen" ||
            calls[1].value !== fen.START_FEN) {
            return "旧宿主未安全退回当前 FEN：" + JSON.stringify({ result: legacy, calls: calls });
        }
        return true;
    } finally {
        pikafishEngine.stop();
        if (previous === undefined) { delete global.$pikafish; }
        else { global.$pikafish = previous; }
    }
});

check("引擎门面：飞相到河沿（e2→c4）后局面仍可算棋（旧校验器让每步算棋都失败）", function () {
    // 与「河沿象位」同源：驱动卡死那 14 分钟里，inspect() 就是拿 validateBoard 的 errors 说话的。
    const board = fen.parseFen(fen.START_FEN).board;
    const one = legal.applyMove(board, { fromCol: 6, fromRow: 9, toCol: 4, toRow: 7 });   // 相 g0→e2
    const two = legal.applyMove(one.board, { fromCol: 4, fromRow: 7, toCol: 2, toRow: 5 }); // 相 e2→c4
    const problems = engineModule.inspect(two.board);
    return problems.length === 0 || ("河沿相被 inspect 拒了：" + problems.join("；"));
});

check("算棋·门面：有未知格就拒绝算棋（不猜）", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    board[40] = "?";
    const result = pikafishEngine.analyze({ board: board, side: "w" }, { movetime: 200 });
    if (result.ok) {
        return "有未知格却算出了 " + result.iccs;
    }
    return result.reason.indexOf("未知格") >= 0 || ("原因没提未知格：" + result.reason);
});

check("算棋·门面：将/帅数量不对要拦下", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    board[85] = null; // 85 = col4,row9 正是红帅那一格（81 是车，别写错）
    const result = pikafishEngine.analyze({ board: board, side: "w" }, { movetime: 200 });
    if (result.ok) {
        return "帅数量不对却算出了 " + result.iccs;
    }
    return result.reason.indexOf("帅") >= 0 || ("原因不对：" + result.reason);
});

check("算棋·UCI：着法记法与 rules.moveToIccs 一致", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const moves = legal.allLegalMoves(board, "w");
    for (let i = 0; i < moves.length; i++) {
        if (uciModule.moveToUci(moves[i]) !== rules.moveToIccs(moves[i])) {
            return "不一致：" + uciModule.moveToUci(moves[i]) + " vs " + rules.moveToIccs(moves[i]);
        }
    }
    return moves.length > 0 || "开局没有着法？";
});

check("算棋·描述：结果文本不出现 undefined（吃 Pikafish 适配器的字段集）", function () {
    // 不依赖真引擎：合成一份适配器形状的结果过 describe（面板/日志就是拿它渲染的）
    const text = engineModule.describe({
        ok: true, side: "w", iccs: "b2e2", captured: null, score: 12,
        depth: 18, nodes: 12345, ms: 1000, mateIn: null, pvIccs: ["b2e2", "b7e7"]
    });
    if (text.indexOf("undefined") >= 0) {
        return "描述里有 undefined：" + text;
    }
    return text.indexOf("b2e2") >= 0 || ("描述里没有着法：" + text);
});

check("算棋·描述：失败结果也要说清阶段与原因", function () {
    const text = engineModule.describe({ ok: false, stage: "native", reason: "Pikafish 不可用" });
    return (text.indexOf("Pikafish 不可用") >= 0 && text.indexOf("undefined") < 0) ||
        ("失败描述不对：" + text);
});

// ---------------------------------------------------------------- 截图黑帧

const adapter = require("../src/runtime/aijs-adapter.js");

check("截图·黑帧判定：整幅纯黑要认出来", function () {
    const frame = { pixelWidth: 1000, pixelHeight: 2000 };
    const result = adapter.looksBlank(frame, { read: function () { return 0; } });
    return result === true || ("纯黑帧没被判为黑：" + result);
});

check("截图·黑帧判定：只要有一处不黑就不算黑", function () {
    const frame = { pixelWidth: 1000, pixelHeight: 2000 };
    let calls = 0;
    // 第 5 个点（正中间）给 #00796B 的绿通道 121，其余全 0
    const result = adapter.looksBlank(frame, {
        read: function () {
            calls++;
            return calls === 5 ? 121 : 0;
        }
    });
    return result === false || "只有一个点不黑却被判为黑帧";
});

check("截图·黑帧判定：深色主题（非纯黑）不能误判", function () {
    const frame = { pixelWidth: 1000, pixelHeight: 2000 };
    const result = adapter.looksBlank(frame, { read: function () { return 11; } });
    return result === false || "整屏 #0B0B0B 被误判为黑帧";
});

check("截图·黑帧判定：拿不到像素时说“不是黑”，不乱报", function () {
    const frame = { pixelWidth: 1000, pixelHeight: 2000 };
    return adapter.looksBlank(frame) === false || "离线环境不该判为黑帧";
});

// ---------------------------------------------------------------- P4 自动执行
// 状态机 / 动作票据 / 点击执行器 / 落子确认全是纯逻辑，所以任务书 17.2–17.3 里
// 「禁止出手」和「落子确认」的用例可以直接搬进离线自检：先是这些断言兜住，
// 真机上能不能点才有意义（对手是真人，点错一步就下不了了）。

const ticketModule = require("../src/automation/move-ticket.js");
const executorModule = require("../src/automation/click-executor.js");
const verifier = require("../src/automation/move-verifier.js");
const machineModule = require("../src/automation/state-machine.js");

const START_FEN = fen.START_FEN;
const START_BOARD = fen.parseFen(START_FEN).board;
const OWN_MOVE = legal.legalMoves(START_BOARD, 1, 7, "w")[0];      // 红方炮的一个合法着法
const AFTER_BOARD = legal.applyMove(START_BOARD, OWN_MOVE).board;
const AFTER_FEN = fen.buildFen(AFTER_BOARD, "b");
const REPLY_MOVE = legal.allLegalMoves(AFTER_BOARD, "b")[0];       // 对方的一个合法应手
const AFTER_REPLY_BOARD = legal.applyMove(AFTER_BOARD, REPLY_MOVE).board;

const TEST_GRID = geometry.createGrid({
    originX: 96,
    originY: 782,
    stepX: 136,
    stepY: 135,
    orientation: geometry.ORIENTATION_RED_BOTTOM,
    version: 7,
    quality: { sampleCount: 20, inlierCount: 20, maxResidual: 0.05, meanResidual: 0.02 }
});

function sampleTicket(overrides) {
    const input = {
        sessionId: 1,
        basePositionVersion: 3,
        baseFen: START_FEN,
        baseSide: "w",
        modelVersion: "self-check-model",
        geometryVersion: 7,
        moveICCS: OWN_MOVE.iccs,
        move: OWN_MOVE,
        expectedAfterFen: AFTER_FEN,
        source: "self-check",
        score: 15,
        depth: 3
    };
    if (overrides) {
        for (const key in overrides) {
            if (Object.prototype.hasOwnProperty.call(overrides, key)) {
                input[key] = overrides[key];
            }
        }
    }
    return ticketModule.create(input);
}

function sampleContext(overrides) {
    const ctx = {
        running: true,
        paused: false,
        sessionId: 1,
        positionVersion: 3,
        boardFen: START_FEN,
        side: "w",
        modelVersion: "self-check-model",
        geometryVersion: 7
    };
    if (overrides) {
        for (const key in overrides) {
            if (Object.prototype.hasOwnProperty.call(overrides, key)) {
                ctx[key] = overrides[key];
            }
        }
    }
    return ctx;
}

/** 自检用的小驱动：假点击 + 状态机，方便断言「点了几下、发了哪些动作」。 */
function harness(options) {
    const opts = options || {};
    const clock = { value: 1000000 };
    const nowMono = function () { return clock.value; };
    const clicked = [];
    const fakeTap = function (x, y, tapOptions) {
        clicked.push({ x: x, y: y, kind: tapOptions && tapOptions.kind });
        return { ok: true, method: "self-check-tap" };
    };
    const disabledTap = executorModule.createDisabledTap("自检：只识别/建议模式");
    const executor = executorModule.createExecutor({
        tap: opts.allowClick === false ? disabledTap.tap : fakeTap,
        nowMono: nowMono,
        waitMs: function () { },
        config: { pressMs: 40, settleMs: 0, pointTolerance: 0.35 }
    });
    const machine = machineModule.createMachine({
        mySide: "w",
        allowClick: opts.allowClick !== false,
        unknownTurnWatchdogMs: 5000,
        verifyTimeoutMs: 2000,
        retryLimit: opts.retryLimit == null ? 1 : opts.retryLimit,
        ticketTtlMs: 15000,
        movetimeMs: 200
    }, { nowMono: nowMono });
    const log = [];

    function dispatch(event) {
        const outcome = machine.dispatch(event);
        log.push({
            event: typeof event === "string" ? event : event.type,
            state: outcome.state,
            effects: outcome.effects.map(function (item) { return item.type; })
        });
        return outcome;
    }

    function snapshotEvent(board, side, extra) {
        const ext = extra || {};
        return {
            type: "SNAPSHOT",
            snapshot: {
                board: board.slice(),
                side: side,
                fen: ext.fen || fen.buildFen(board, side === "b" ? "b" : "w"),
                unknownCells: ext.unknownCells == null ? 0 : ext.unknownCells,
                stable: ext.stable !== false,
                geometryTrusted: ext.geometryTrusted !== false,
                conflicts: ext.conflicts || 0,
                // 识别自对齐会把驱动侧的几何版本 +1，快照要能带上它（见「几何版本同步」用例）
                geometryVersion: ext.geometryVersion == null ? undefined : ext.geometryVersion
            }
        };
    }

    function effectsOf(type) {
        let count = 0;
        for (let i = 0; i < log.length; i++) {
            for (let j = 0; j < log[i].effects.length; j++) {
                if (log[i].effects[j] === type) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 把状态机吐出的 EXECUTE_TICKET 真的走一遍点击（真机上是 auto-driver 在做）。
     *
     * ⚠️ 必须**照抄**驱动的顺序：驱动在拿到 EXECUTE_TICKET 之后、真正点之前还会再调一次
     * `machine.canClick(ticket)`（防「排队期间局面/轮次变了」）。夹具以前省了这一步，
     * 结果漏掉了一个真机 bug：状态机在放行时就把 actionId 记进 executedTickets，
     * 驱动这一核对立刻被自己的标记挡住（"该票据已经执行过"）→ ③ 永远点不出去。
     */
    function executeCurrentTicket() {
        const ticket = machine.currentTicket();
        const gate = machine.canClick(ticket);
        if (!gate.ok) {
            dispatch({
                type: "CLICK_RESULT", ticket: ticket, status: "REJECTED",
                clickCount: 0, reason: gate.reason
            });
            return { ticket: ticket, outcome: { status: "REJECTED", clickCount: 0, reason: gate.reason } };
        }
        const outcome = executor.execute(ticket, {
            grid: TEST_GRID,
            cancel: function () { return opts.cancelled === true; }
        });
        dispatch({ type: "CLICK_RESULT", ticket: ticket, status: outcome.status, clickCount: outcome.clickCount, reason: outcome.reason });
        return { ticket: ticket, outcome: outcome };
    }

    return {
        machine: machine,
        clock: clock,
        executor: executor,
        disabledTap: disabledTap,
        clicked: clicked,
        log: log,
        dispatch: dispatch,
        snapshotEvent: snapshotEvent,
        effectsOf: effectsOf,
        executeCurrentTicket: executeCurrentTicket
    };
}

/** 走完「开局 → 就绪 → 几何 → 轮到我方」，停在 THINKING（算棋进行中）。 */
function advanceToThinking(h) {
    h.dispatch({ type: "START" });
    h.dispatch({ type: "READY", modelVersion: "self-check-model" });
    h.dispatch({ type: "GEOMETRY", geometryVersion: 7, trusted: true });
    const first = h.dispatch(h.snapshotEvent(START_BOARD, "w"));
    const compute = first.effects.filter(function (item) { return item.type === "COMPUTE"; })[0];
    if (!compute) {
        return { ok: false, reason: "第一帧没有开始算棋（状态 " + first.state + "）" };
    }
    return { ok: true, jobId: compute.jobId };
}

/** 走完「开局 → 就绪 → 几何 → 轮到我方 → 算出着法」，停在 PRECHECK。 */
function advanceToPrecheck(h) {
    const prep = advanceToThinking(h);
    if (!prep.ok) {
        return prep;
    }
    h.dispatch({
        type: "JOB_RESULT",
        jobId: prep.jobId,
        ok: true,
        iccs: OWN_MOVE.iccs,
        move: OWN_MOVE,
        expectedAfterFen: AFTER_FEN,
        score: 15,
        depth: 3
    });
    return prep;
}

// ---- 几何版本同步（真机 2026-09-21：「面板在点、游戏没反应」的根因）

check("几何版本随观察帧同步：自对齐把版本 +1 后，新票据必须拿新版本", function () {
    // 真机事故：识别自对齐/钉棋子让驱动侧的 grid.version 从 1 跳到 2（驱动收编了 result.grid），
    // 但状态机的 versions.geometryVersion 从来不更新 ⇒ 之后新造的票据全挂 v1
    // ⇒ 点击器核对 grid.version(2) !== ticket.geometryVersion(1) 直接 REJECTED
    // ⇒ 日志「票据几何版本 v1 / 当前 v2」、面板一直「在点」但游戏毫无反应，直到尝试预算耗尽暂停。
    // 修法：观察帧（SNAPSHOT）带 geometryVersion，状态机只同步版本号、不动状态。
    const h = harness({});
    const prep = advanceToThinking(h);
    if (!prep.ok) {
        return prep.reason;
    }
    h.dispatch({
        type: "JOB_RESULT", jobId: prep.jobId, ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE,
        expectedAfterFen: AFTER_FEN, score: 15, depth: 3
    });
    const oldTicket = h.machine.currentTicket();
    if (!oldTicket || oldTicket.geometryVersion !== 7) {
        return "第一张票据的几何版本应为 7，实际 " + (oldTicket && oldTicket.geometryVersion);
    }
    // 识别自对齐：几何升到 v8；同一帧里局面也变了（对方应手了）⇒ 状态机重开一个任务
    const next = h.dispatch(h.snapshotEvent(AFTER_REPLY_BOARD, "w", { geometryVersion: 8 }));
    const compute = next.effects.filter(function (item) { return item.type === "COMPUTE"; })[0];
    if (!compute) {
        return "局面变化后没有重开任务（状态 " + next.state + "，效果 " +
            next.effects.map(function (item) { return item.type; }).join("/") + "）";
    }
    h.dispatch({
        type: "JOB_RESULT", jobId: compute.jobId, ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE,
        expectedAfterFen: AFTER_FEN, score: 15, depth: 3
    });
    const ticket = h.machine.currentTicket();
    if (!ticket) {
        return "没有生成新票据";
    }
    return ticket.geometryVersion === 8 ||
        ("新票据仍拿着旧几何版本 " + ticket.geometryVersion + "（点击器会 REJECTED：面板在点、游戏没反应）");
});

// ---- 票据：出手前核对

check("P4·票据：缺少关键字段要明确报错", function () {
    try {
        ticketModule.create({ baseFen: START_FEN, baseSide: "w" });
        return "缺字段却没报错";
    } catch (error) {
        return String(error.message).indexOf("票据缺少字段") === 0 ? true : ("报错信息不清楚：" + error.message);
    }
});

check("P4·票据：同一张票只能执行一次（S04）", function () {
    const ticket = sampleTicket();
    const first = ticketModule.canExecute(ticket, sampleContext());
    if (!first.ok) {
        return "第一次就该放行：" + first.reason;
    }
    ticketModule.start(ticket);
    const second = ticketModule.canExecute(ticket, sampleContext());
    if (second.ok) {
        return "已经开始的票据还能再执行";
    }
    return second.reason.indexOf("只能执行一次") >= 0 || second.reason.indexOf("STARTED") >= 0
        ? true : ("拒绝原因没说清： " + second.reason);
});

check("P4·票据：过期不能执行", function () {
    const ticket = sampleTicket();
    const result = ticketModule.canExecute(ticket, sampleContext(), ticket.expiresAtMono + 1);
    return result.ok ? "过期票据仍被放行" : true;
});

check("P4·票据：会话/模型/几何/局面/轮次任一变化都拒绝（S02）", function () {
    const variants = [
        { sessionId: 2 },
        { modelVersion: "另一个模型" },
        { geometryVersion: 8 },
        { positionVersion: 4 },
        { boardFen: AFTER_FEN },
        { side: "b" }
    ];
    for (let i = 0; i < variants.length; i++) {
        const result = ticketModule.canExecute(sampleTicket(), sampleContext(variants[i]));
        if (result.ok) {
            return "第 " + (i + 1) + " 种变化（" + JSON.stringify(variants[i]) + "）没有被拦下";
        }
    }
    return true;
});

check("P4·票据：暂停时不执行（S03）", function () {
    const result = ticketModule.canExecute(sampleTicket(), sampleContext({ paused: true }));
    return result.ok ? "暂停时仍被放行" : true;
});

// ---- 点击执行器

check("P4·点击：起点→终点两次，且按压时长可配", function () {
    const clicked = [];
    const executor = executorModule.createExecutor({
        tap: function (x, y, tapOptions) {
            clicked.push({ x: x, y: y, pressMs: tapOptions.pressMs });
            return { ok: true, method: "fake" };
        },
        nowMono: function () { return 1000; },
        waitMs: function () { },
        config: { pressMs: 55, settleMs: 0 }
    });
    const outcome = executor.execute(sampleTicket(), { grid: TEST_GRID });
    if (outcome.status !== "SENT") {
        return "没有发出点击：" + outcome.status + " " + outcome.reason;
    }
    if (clicked.length !== 2) {
        return "点击次数应为 2，实际 " + clicked.length;
    }
    if (clicked[0].pressMs !== 55) {
        return "按压时长没按配置走：" + clicked[0].pressMs;
    }
    const from = geometry.pointAt(TEST_GRID, OWN_MOVE.fromCol, OWN_MOVE.fromRow);
    const to = geometry.pointAt(TEST_GRID, OWN_MOVE.toCol, OWN_MOVE.toRow);
    if (clicked[0].x !== Math.round(from.x) || clicked[0].y !== Math.round(from.y)) {
        return "起点坐标不对：" + clicked[0].x + "," + clicked[0].y;
    }
    return clicked[1].x === Math.round(to.x) && clicked[1].y === Math.round(to.y)
        ? true : ("终点坐标不对：" + clicked[1].x + "," + clicked[1].y);
});

check("P4·点击：起点之后被取消 → 不发终点点击（A04）", function () {
    let cancelNow = false;
    const clicked = [];
    const executor = executorModule.createExecutor({
        tap: function (x, y, tapOptions) {
            clicked.push(tapOptions.kind);
            cancelNow = true;   // 起点点完立刻暂停
            return { ok: true, method: "fake" };
        },
        nowMono: function () { return 1000; },
        waitMs: function () { },
        config: { pressMs: 40, settleMs: 0 }
    });
    const outcome = executor.execute(sampleTicket(), {
        grid: TEST_GRID,
        cancel: function () { return cancelNow; }
    });
    if (outcome.status !== "CANCELLED") {
        return "取消后状态应为 CANCELLED，实际 " + outcome.status;
    }
    if (!outcome.cancelledBetween) {
        return "没有记录「起点已点、终点前取消」";
    }
    return outcome.clickCount === 1 && clicked.length === 1
        ? true : ("取消后仍点了 " + clicked.length + " 次：" + clicked.join(","));
});

check("P4·点击：点击前就被取消 → 一次都不点", function () {
    const executor = executorModule.createExecutor({
        tap: function () { return { ok: true }; },
        nowMono: function () { return 1000; },
        waitMs: function () { },
        config: { pressMs: 40, settleMs: 0 }
    });
    const outcome = executor.execute(sampleTicket(), {
        grid: TEST_GRID,
        cancel: function () { return true; }
    });
    return outcome.status === "CANCELLED" && outcome.clickCount === 0
        ? true : ("状态 " + outcome.status + "，点了 " + outcome.clickCount + " 次");
});

check("P4·点击：几何版本不一致 / 落点出界 → 拒绝且不点", function () {
    const executor = executorModule.createExecutor({
        tap: function () { return { ok: true }; },
        nowMono: function () { return 1000; },
        waitMs: function () { },
        config: { pressMs: 40, settleMs: 0 }
    });
    const otherVersion = geometry.createGrid({
        originX: 96, originY: 782, stepX: 136, stepY: 135,
        orientation: geometry.ORIENTATION_RED_BOTTOM, version: 8
    });
    const stale = executor.execute(sampleTicket(), { grid: otherVersion });
    if (stale.status !== "REJECTED" || stale.clickCount !== 0) {
        return "几何版本不一致时没有拒绝：" + stale.status + " / " + stale.clickCount;
    }
    // 原点大到棋子全在屏幕外：nearest() 判不了，得靠屏幕边界兜底
    const outside = geometry.createGrid({
        originX: -5000, originY: -5000, stepX: 136, stepY: 135,
        orientation: geometry.ORIENTATION_RED_BOTTOM, version: 7
    });
    const off = executor.execute(sampleTicket(), {
        grid: outside,
        screen: { width: 1080, height: 2400 }
    });
    return off.status === "REJECTED" && off.clickCount === 0
        ? true : ("出界坐标没有被拒绝：" + off.status + " / " + off.clickCount);
});

check("P4·点击：只识别/建议模式用禁用桩，调用即计数（A05 的基础）", function () {
    const disabled = executorModule.createDisabledTap("只识别模式");
    const result = disabled.tap(100, 200);
    if (result.ok !== false) {
        return "禁用桩不该返回成功";
    }
    if (disabled.calls() !== 1) {
        return "禁用桩没有计数：" + disabled.calls();
    }
    return disabled.describe().indexOf("1 次") >= 0 ? true : ("描述不对：" + disabled.describe());
});

// ---- 落子确认

check("R05g 对方应手还在动画中（子同时画在起点与落点）→ UNRELIABLE 等下一帧，不作废票据", function () {
    // 真机现场（2026-09-20 K40 新局）：确认观测 = 期望 + 「应手那枚子还画在起点」，
    // 盘面看起来多一枚同型子、没有任何格少子（FEN 只差 1 格）⇒ 旧逻辑判 UNEXPECTED →
    // 作废票据 + 恢复 → 之后 30s「轮次推不出来」空转。
    const ticket = sampleTicket();
    const expected = AFTER_BOARD.slice();
    const reply = legal.allLegalMoves(expected, "b")[0];
    const text = String(reply.iccs);
    const from = (9 - Number(text.charAt(1))) * 9 + "abcdefghi".indexOf(text.charAt(0));
    const to = (9 - Number(text.charAt(3))) * 9 + "abcdefghi".indexOf(text.charAt(2));
    const after = legal.applyMove(expected, reply).board;
    if (!after[to]) {
        return "夹具不对：应手落点没有子";
    }
    const observed = after.slice();
    observed[from] = after[to];          // 起点那枚子还没清（动画中）
    const verdict = verifier.verify({ ticket: ticket, observed: observed, unknownCells: 0 });
    if (verdict.result !== "UNRELIABLE") {
        return "应当判 UNRELIABLE（等下一帧），实际 " + verdict.result + "：" + verdict.reason;
    }
    if (String(verdict.reason).indexOf("动画") < 0) {
        return "原因里要写明是应手动画（真机上分不出它和「看不清」就只能猜）：" + verdict.reason;
    }
    // 反例 1：多出来的那枚子**不在**任何合法应手的起点上（凭空多子）→ 必须仍然 UNEXPECTED
    const bogus = after.slice();
    let elsewhere = -1;
    for (let i = 89; i >= 0; i--) {
        if (!after[i] && i !== from) {
            elsewhere = i;
            break;
        }
    }
    bogus[elsewhere] = after[to];
    const bad = verifier.verify({ ticket: ticket, observed: bogus, unknownCells: 0 });
    if (bad.result !== "UNEXPECTED") {
        return "凭空多子必须照旧判 UNEXPECTED（动画容差不能放宽成万能），实际 " + bad.result + "：" + bad.reason;
    }
    // 反例 2：起点画的是**另一枚子**（不是应手那枚）→ 也不算动画
    const wrongPiece = after.slice();
    wrongPiece[from] = after[to] === after[to].toUpperCase() ? "p" : "P";
    const bad2 = verifier.verify({ ticket: ticket, observed: wrongPiece, unknownCells: 0 });
    if (bad2.result === "UNRELIABLE" && String(bad2.reason).indexOf("动画") >= 0) {
        return "起点画的不是应手那枚子时不许当成动画帧";
    }
    return true;
});

check("P4·确认：等于预期落子后局面 → CONFIRMED（A01）", function () {
    const verdict = verifier.verify({ ticket: sampleTicket(), observed: AFTER_BOARD.slice() });
    return verdict.result === "CONFIRMED" ? true : (verdict.result + "：" + verdict.reason);
});

check("P4·确认：还是走之前的样子 → UNCHANGED，不算成功（A02）", function () {
    const verdict = verifier.verify({ ticket: sampleTicket(), observed: START_BOARD.slice() });
    if (verdict.result !== "UNCHANGED") {
        return verdict.result + "：" + verdict.reason;
    }
    return verifier.isSuccess(verdict.result) === false ? true : "UNCHANGED 被当成了成功";
});

check("R05e 落点格被装饰读错兵种时，「一手+应手」仍要能认出来", function () {
    // 真机现场（2026-09-14，K40 + 天天象棋离线局）：我方 炮(c2)吃卒落在 (2,3)、
    // 对方紧接着 炮(c4)吃我兵落在 (4,6)，这两个「刚落的子」都被模型读成了兵
    // （颜色对、兵种错）→ 严格两步匹配 0 解 → 轮次推不出来 → 永久 RECOVERING（白等 90s）。
    const base = AFTER_REPLY_BOARD.slice();
    const own = legal.allLegalMoves(base, "w")[0];
    const mid = legal.applyMove(base, own).board;
    const reply = legal.allLegalMoves(mid, "b")[0];
    const end = legal.applyMove(mid, reply).board;
    const observed = end.slice();
    [own, reply].forEach(function (move) {
        const text = String(move.iccs);
        const dest = (9 - Number(text.charAt(3))) * 9 + "abcdefghi".indexOf(text.charAt(2));
        const truth = end[dest];
        observed[dest] = truth === truth.toUpperCase() ? "P" : "p";   // 同色、兵种读错
    });
    const result = transition.infer(base, "w", observed, { allowPair: true });
    if (result.status !== transition.STATUS.MOVE_PAIR) {
        return "应认成两步解释，实际 " + result.status + "：" + result.reason;
    }
    // 容差解释**必须**带标记：驱动靠它追加「连续两帧一致」的门槛（§12）
    if (result.tolerant !== true) {
        return "容差解释没有被标 tolerant，驱动就无从追加跨帧证据";
    }
    if (!result.board || !legal.boardMatches(result.board, end)) {
        return "没能给出「按着法改正过」的盘面";
    }
    // 反向保护：起点没让开（子其实没走）时**不许**采信
    const bad = observed.slice();
    const srcText = String(own.iccs);
    bad[(9 - Number(srcText.charAt(1))) * 9 + "abcdefghi".indexOf(srcText.charAt(0))] = base[
        (9 - Number(srcText.charAt(1))) * 9 + "abcdefghi".indexOf(srcText.charAt(0))];
    const rejected = transition.infer(base, "w", bad, { allowPair: true });
    return rejected.status === transition.STATUS.MOVE_PAIR
        ? "起点还留着子（没走）却被认成两步解释" : true;
});

check("R05f 只差「同字母换大小写」的装饰噪声 → 判没走子（真机盘面）", function () {
    // 两手的起点都还在原位，只有 (2,3)/(4,6) 两格的兵卒被读出反色。
    const before = fen.parseFen("rnbakabnr/9/1c2c4/p1p1p1p1p/9/9/P1P1P1P1P/2C4C1/9/RNBAKABNR").board;
    const observed = fen.parseFen("rnbakabnr/9/1c2c4/p1P1p1p1p/9/9/P1P1p1P1P/2C4C1/9/RNBAKABNR").board;
    const result = transition.infer(before, "w", observed, { allowPair: true });
    if (result.status !== transition.STATUS.UNCHANGED) {
        return "应判没走子，实际 " + result.status + "：" + result.reason;
    }
    if (!result.board || !legal.boardMatches(result.board, before)) {
        return "没把盘面纠正回基准";
    }
    // 反向保护：真的走子（起点变空）绝不能被当噪声吞掉
    const moved = before.slice();
    moved[7 * 9 + 2] = "";      // 我方炮离开 (2,7)
    moved[3 * 9 + 2] = "P";     // 目的格被读成红兵（真机就是这样）
    const real = transition.infer(before, "w", moved, { allowPair: true });
    return real.status !== transition.STATUS.UNCHANGED ? true : "真走子被误判成没走";
});

check("R05g 应手落点被读错兵种 + 另一格只是红黑读反 → 单步仍要认出来（真机盘面）", function () {
    // 真机现场（2026-09-14，K40 + 天天象棋离线局，b2c2 刚 CONFIRMED 之后）：
    // 对方应手是「车九进一」(0,0)→(0,1)，那一格带高亮环被模型读成炮 `c`；
    // 同时 (7,2) 的黑炮被读成红炮 `C`（同一兵种、只是红黑读反）。
    // 旧逻辑：差异格数 1 → 3，容差（≤1 且必须落在落点）不成立 →
    // 「轮次推不出来（UNKNOWN）」→ 看门狗 → RECOVERING 一整轮再也回不来。
    const base = fen.parseFen("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/2C4C1/9/RNBAKABNR").board;
    const observed = fen.parseFen("1nbakabnr/c8/1c5C1/p1p1p1p1p/9/9/P1P1P1P1P/2C4C1/9/RNBAKABNR").board;
    // 前提：严格推断（含两步）解释不了 —— 这正是真机日志里的结论
    const strict = transition.infer(base, "b", observed, { allowPair: true });
    if (strict.status !== transition.STATUS.UNKNOWN) {
        return "夹具前提不成立：严格推断竟给出了 " + strict.status;
    }
    const tolerant = transition.inferSingleWithDecorationTolerance(base, "b", observed);
    if (!tolerant) {
        return "单步容差没能认出这一步（真机上就是卡在这里）";
    }
    if (String(tolerant.iccs) !== "a9a8") {
        return "认成了 " + tolerant.iccs + "，应为 a9a8（车九进一）";
    }
    if (tolerant.nextSide !== "w") {
        return "下一手应为轮到我方(w)，实际 " + tolerant.nextSide;
    }
    const expected = legal.applyMove(base, tolerant.move).board;
    if (!legal.boardMatches(tolerant.board, expected)) {
        return "给出的盘面不是「基准 + 这一步」";
    }
    if (tolerant.board[2 * 9 + 7] !== "c") {
        return "(7,2) 没按基准纠回黑炮：" + tolerant.board[2 * 9 + 7];
    }
    if (tolerant.colorNoise !== 1) {
        return "同字异色噪声计数 = " + tolerant.colorNoise;
    }
    // 反向保护一：只差颜色读反（一步没走）→ 不许认成走子
    const noiseOnly = base.slice();
    noiseOnly[2 * 9 + 7] = "C";
    if (transition.inferSingleWithDecorationTolerance(base, "b", noiseOnly)) {
        return "只差颜色读反（其实没走子）却被认成走了一步";
    }
    // 反向保护二：起点还留着子（其实没走）→ 不许认
    const stuck = observed.slice();
    stuck[0] = "r";
    if (transition.inferSingleWithDecorationTolerance(base, "b", stuck)) {
        return "起点还留着子却被认成走子";
    }
    // 反向保护三：异字母的差异不能被当噪声吞掉（吃子/换子必须让它变）
    const swapped = observed.slice();
    swapped[2 * 9 + 7] = "P";
    if (transition.inferSingleWithDecorationTolerance(base, "b", swapped)) {
        return "异字母差异被当成噪声吞掉了";
    }
    return true;
});

/**
 * ⚠️ 这里有意**没有**「整帧只少一个子 → 判没走子」的断言（2026-09-14 试过又拿掉了）：
 * 真机那帧 = 「起点少一个子 + 另一格同字母异色」，它跟「真吃子、落点被读成同字母异色」
 * 在单帧上**结构完全等价**（R05f 的反向保护就是后者）→ 单帧无法区分，硬判会把吃子藏起来。
 * 正确办法是用驱动已有的「已知事实补齐」表：推断出对方那一手时把它的**落点也登记进去**
 * （`auto-driver.registerMoveDestinations`），下一帧那一格就不再读成空，自然回到 UNCHANGED。
 */

check("R05h 起点残影（模型在起点重读旧子）→ 开 allowSourceGhost 后能认出这一步（真机盘面）", function () {
    // 真机现场（2026-09-20，K40 + 天天象棋，日志 17:42:31）：对方卒 g6g5 之后，
    // 观测在起点 (6,3) 仍读得到卒（同时 (6,4) 也有），而我方刚落的炮 (2,3) 被读成黑炮 c。
    // 旧逻辑：差异落在**起点**上 → 任何合法着法都解释不了 → 轮次推不出来 → 看门狗 → 恢复。
    const base = fen.parseFen("rnbakab1r/9/1c4nc1/p1C1p1p1p/9/9/P1P1P1P1P/7C1/9/RNBAKABNR").board;
    const observed = fen.parseFen("rnbakab1r/9/1c4nc1/p1c1p1p1p/6p2/9/P1P1P1P1P/7C1/9/RNBAKABNR").board;
    // 前提：严格推断解释不了（真机日志里就是这一行“轮次推不出来”）
    if (transition.infer(base, "b", observed, { allowPair: true }).status !== transition.STATUS.UNKNOWN) {
        return "夹具前提不成立：严格推断竟然能解释";
    }
    // 默认（不开开关）必须**仍然严格**：单帧分不出残影与「其实没走、落点是幻觉」
    if (transition.inferSingleWithDecorationTolerance(base, "b", observed)) {
        return "默认不允许起点残影，这里却认了";
    }
    const tolerant = transition.inferSingleWithDecorationTolerance(base, "b", observed,
        { allowSourceGhost: true });
    if (!tolerant) {
        return "开了 allowSourceGhost 仍认不出这一步";
    }
    if (String(tolerant.iccs) !== "g6g5") {
        return "认成了 " + tolerant.iccs + "，应为 g6g5";
    }
    if (tolerant.nextSide !== "w") {
        return "下一手应为轮到我方(w)，实际 " + tolerant.nextSide;
    }
    // 给出的盘面 = 基准 + 这一步（残影/颜色噪声都按着法纠回，不是拿观测当真相）
    const expected = legal.applyMove(base, tolerant.move).board;
    if (!legal.boardMatches(tolerant.board, expected)) {
        return "给出的盘面不是「基准 + 这一步」";
    }
    if (tolerant.board[3 * 9 + 2] !== "C") {
        return "(2,3) 没按基准纠回红炮：" + tolerant.board[3 * 9 + 2];
    }
    if (tolerant.board[3 * 9 + 6] !== null && tolerant.board[3 * 9 + 6] !== "") {
        return "(6,3) 残影没清掉：" + tolerant.board[3 * 9 + 6];
    }
    // 反向保护一：开开关也不许把「起点换成了别的字」当残影
    const swapped = observed.slice();
    swapped[3 * 9 + 6] = "P";
    if (transition.inferSingleWithDecorationTolerance(base, "b", swapped, { allowSourceGhost: true })) {
        return "起点被换成异色异字却被当成残影";
    }
    // 反向保护二：「起点还留着子 + 落点读成空」= 这手很可能根本没发生 → 开开关也不许认
    const notMoved = observed.slice();
    notMoved[4 * 9 + 6] = null;
    if (transition.inferSingleWithDecorationTolerance(base, "b", notMoved, { allowSourceGhost: true })) {
        return "起点留子 + 落点读空被误认成走了一手";
    }
    return true;
});

check("P4·确认：出现一个合法对方应手 → OPPONENT_REPLY（A03）", function () {
    const verdict = verifier.verify({ ticket: sampleTicket(), observed: AFTER_REPLY_BOARD.slice() });
    if (verdict.result !== "OPPONENT_REPLY") {
        return verdict.result + "：" + verdict.reason;
    }
    return verdict.plies.length === 2 ? true : ("应手确认应该记两步，实际 " + verdict.plies.length + " 步");
});

check("P4·确认：只有别处那格颜色读反（棋其实没走成）→ UNCHANGED，不许一直等", function () {
    // 真机现场（2026-09-14，K40 + 离线局）：b2c2 走成后，第二手 c2c6 点击**没走成**
    // （炮还稳稳留在起点），而落点的黑卒被读成红兵（p→P）→ 与基准只差这一格。
    // 旧逻辑把它当「动画帧」一直等 → 65 次 × 2.5s 白等一整轮。正确结论是 UNCHANGED。
    const baseBoard = AFTER_REPLY_BOARD.slice();
    let move = null;
    for (let i = 0; i < 90 && !move; i++) {
        const ch = baseBoard[i];
        if (!ch || ch !== ch.toUpperCase()) {
            continue;   // 只从我方（红）子出发找着法
        }
        const list = legal.legalMoves(baseBoard, i % 9, Math.floor(i / 9), "w");
        if (list.length > 0) {
            move = list[0];
        }
    }
    if (!move) {
        return "夹具前提不成立：红方没有合法着法";
    }
    const after = legal.applyMove(baseBoard, move).board;
    const ticket = sampleTicket({
        baseFen: fen.buildFen(baseBoard, "w"),
        baseSide: "w",
        moveICCS: move.iccs,
        move: move,
        expectedAfterFen: fen.buildFen(after, "b")
    });
    // 观测 = 基准 + 把某个**非起点格**的颜色读反（模拟装饰误判），棋子一步没动
    const observed = baseBoard.slice();
    const sourceIndex = move.fromRow * 9 + move.fromCol;
    let flipped = -1;
    for (let i = 0; i < 90; i++) {
        const ch = baseBoard[i];
        if (!ch || i === sourceIndex || ch !== ch.toUpperCase()) {
            continue;
        }
        observed[i] = ch.toLowerCase();
        flipped = i;
        break;
    }
    if (flipped < 0) {
        return "夹具前提不成立：找不到可翻色的红子";
    }
    const verdict = verifier.verify({
        ticket: ticket, observed: observed, unknownCells: 0,
        stable: true, geometryTrusted: true, conflicts: 0
    });
    if (verdict.result !== "UNCHANGED") {
        return "颜色读反应判 UNCHANGED，实际 " + verdict.result + "：" + verdict.reason;
    }
    // 反向保护：真的走成了（起点空、落点是我方子）必须判 CONFIRMED，别被这条规则吃掉
    const landed = verifier.verify({
        ticket: ticket, observed: after.slice(), unknownCells: 0,
        stable: true, geometryTrusted: true, conflicts: 0
    });
    return landed.result === "CONFIRMED" ? true : ("真落子被误判成 " + landed.result);
});

check("P4·确认：未知格外围格按「唯一解释」判 —— 只有一种解释能对上 ⇒ 确认", function () {
    // ⚠️ 2026-09-20 改判据：旧断言是「任何未知格都 UNRELIABLE」，太保守 ——
    //   真机上木纹压字/水印/装饰造成**任何一格 '?'** 都会让确认超时（实测每次白烧 ~15s 恢复）。
    //   新判据：把看不清的格剔出比对，看「我方这手（对方未应）」与「我方这手 + 某个合法应手」
    //   是不是只有一个能对上所有看得清的格。
    //   本用例：未知格是黑車/黑馬的**原位** —— 若它们动过，落点在观测里看得见且仍是空的 ⇒
    //   那些应手全被排除 ⇒ 只剩「我方这手、对方未应」一个解释 ⇒ 可以确认。
    const observed = AFTER_BOARD.slice();
    observed[0] = "?";
    observed[1] = "?";
    const verdict = verifier.verify({ ticket: sampleTicket(), observed: observed, unknownCells: 2 });
    if (verdict.result !== verifier.RESULT.CONFIRMED) {
        return "应当确认（唯一解释），实际 " + verdict.result + "：" + verdict.reason;
    }
    if (verdict.board[0] !== AFTER_BOARD[0] || verdict.board[1] !== AFTER_BOARD[1]) {
        return "看不清的格要按票据/棋规补齐：" + verdict.board[0] + "/" + verdict.board[1];
    }
    if (verdict.unknownCells !== 2) {
        return "未知格账目要留着（体检用）：" + verdict.unknownCells;
    }
    return true;
});

check("P4·确认：对方那手的两格都看不清 ⇒ 两种解释都能对上 ⇒ 仍然 UNRELIABLE（绝不能当成没落子）", function () {
    const reply = legal.legalMoves(AFTER_BOARD, 1, 0, "b")[0];      // 黑馬 (col 1,row 0) 的一个合法应手
    const text = String(reply.iccs);
    const start = (9 - Number(text.charAt(1))) * 9 + "abcdefghi".indexOf(text.charAt(0));
    const dest = (9 - Number(text.charAt(3))) * 9 + "abcdefghi".indexOf(text.charAt(2));
    const observed = AFTER_BOARD.slice();
    observed[start] = "?";
    observed[dest] = "?";
    const verdict = verifier.verify({ ticket: sampleTicket(), observed: observed, unknownCells: 2 });
    return verdict.result === verifier.RESULT.UNRELIABLE
        ? true
        : ("应当 UNRELIABLE（两种解释都能对上），实际 " + verdict.result + "：" + verdict.reason);
});

check("P4·确认：别的可靠局面 → UNEXPECTED 并带上差异格", function () {
    const observed = START_BOARD.slice();
    observed[0] = null;                                     // 凭空少一个黑车
    const verdict = verifier.verify({ ticket: sampleTicket(), observed: observed });
    if (verdict.result !== "UNEXPECTED") {
        return verdict.result + "：" + verdict.reason;
    }
    return verdict.reason.indexOf("←") < 0 && verdict.reason.indexOf("→") >= 0
        ? true : ("差异描述不完整：" + verdict.reason);
});

check("P4·确认：几何不可信 / 观测不稳定 → UNRELIABLE", function () {
    const a = verifier.verify({ ticket: sampleTicket(), observed: AFTER_BOARD.slice(), geometryTrusted: false });
    const b = verifier.verify({ ticket: sampleTicket(), observed: AFTER_BOARD.slice(), stable: false });
    const c = verifier.verify({ ticket: sampleTicket(), observed: AFTER_BOARD.slice(), conflicts: 1 });
    return (a.result === "UNRELIABLE" && b.result === "UNRELIABLE" && c.result === "UNRELIABLE")
        ? true : ("三种不可靠情形没有都识别：" + a.result + "/" + b.result + "/" + c.result);
});

// ---- 状态机：禁止出手与正常一轮

check("P4·状态机：轮次未知超过看门狗 → 不点击（S01）", function () {
    const h = harness();
    h.dispatch({ type: "START" });
    h.dispatch({ type: "READY", modelVersion: "self-check-model" });
    h.dispatch({ type: "GEOMETRY", geometryVersion: 7, trusted: true });
    h.clock.value += 1000;
    h.dispatch({ type: "SNAPSHOT", snapshot: { board: START_BOARD.slice(), side: null, fen: START_FEN, unknownCells: 0, stable: true } });
    if (h.effectsOf("COMPUTE") !== 0) {
        return "轮次未知就开始算棋了";
    }
    h.clock.value += 6000;   // 超过 5s 看门狗
    h.dispatch({ type: "TICK", nowMono: h.clock.value });
    if (h.machine.counters().watchdogTrips !== 1) {
        return "看门狗没有记一次触发：" + h.machine.counters().watchdogTrips;
    }
    if (h.effectsOf("EXECUTE_TICKET") !== 0 || h.clicked.length !== 0) {
        return "轮次未知超时后仍然出了手";
    }
    return h.machine.state() === "RECOVERING" ? true : ("超时后状态不对：" + h.machine.state());
});

check("P4·状态机：思考中局面变了，旧结果不生成票据（S02）", function () {
    const h = harness();
    const prep = advanceToThinking(h);
    if (!prep.ok) {
        return prep.reason;
    }
    if (h.machine.state() !== "THINKING") {
        return "应该停在 THINKING，实际 " + h.machine.state();
    }
    // 思考中棋盘变了（对方先落子），机器应取消旧任务并开始新任务
    const changed = h.dispatch(h.snapshotEvent(AFTER_BOARD, "w"));
    if (h.effectsOf("CANCEL_JOB") !== 1) {
        return "局面变了却没有取消旧任务";
    }
    if (h.machine.counters().jobsCancelled !== 1) {
        return "取消计数不对：" + h.machine.counters().jobsCancelled;
    }
    if (changed.effects.filter(function (e) { return e.type === "COMPUTE"; }).length !== 1) {
        return "没有为新局面重新开始算棋";
    }
    // 旧任务的着法结果迟到：必须被丢掉，不能生成票据、更不能出手
    const late = h.dispatch({
        type: "JOB_RESULT", jobId: prep.jobId, ok: true,
        iccs: OWN_MOVE.iccs, move: OWN_MOVE, expectedAfterFen: AFTER_FEN
    });
    if (late.effects.length !== 0) {
        return "过期结果产生了动作：" + late.effects.map(function (e) { return e.type; }).join(",");
    }
    if (h.machine.counters().ticketsCreated !== 0) {
        return "过期结果生成了票据";
    }
    if (h.machine.state() !== "THINKING") {
        return "状态被过期结果带偏：" + h.machine.state();
    }
    return true;
});

check("P4·状态机：拿到票据后局面又变了 → 旧票据作废且不出手", function () {
    const h = harness();
    const prep = advanceToPrecheck(h);
    if (!prep.ok) {
        return prep.reason;
    }
    const staleTicket = h.machine.currentTicket();
    if (!staleTicket) {
        return "PRECHECK 阶段应该有票据";
    }
    h.dispatch(h.snapshotEvent(AFTER_BOARD, "w"));
    if (staleTicket.executionState !== "CANCELLED") {
        return "局面变了旧票据居然还是 " + staleTicket.executionState;
    }
    return (h.effectsOf("EXECUTE_TICKET") === 0 && h.clicked.length === 0)
        ? true : "旧票据仍然出了手";
});

check("P4·状态机：暂停后结果才回来 → 不生成票据、不点击（S03）", function () {
    const h = harness();
    h.dispatch({ type: "START" });
    h.dispatch({ type: "READY", modelVersion: "self-check-model" });
    h.dispatch({ type: "GEOMETRY", geometryVersion: 7, trusted: true });
    const first = h.dispatch(h.snapshotEvent(START_BOARD, "w"));
    const jobId = first.effects.filter(function (e) { return e.type === "COMPUTE"; })[0].jobId;
    h.dispatch({ type: "PAUSE" });
    if (h.machine.state() !== "PAUSED") {
        return "暂停后状态不对：" + h.machine.state();
    }
    h.dispatch({
        type: "JOB_RESULT", jobId: jobId, ok: true,
        iccs: OWN_MOVE.iccs, move: OWN_MOVE, expectedAfterFen: AFTER_FEN
    });
    if (h.machine.counters().ticketsCreated !== 0) {
        return "暂停后仍然生成了票据";
    }
    return (h.effectsOf("EXECUTE_TICKET") === 0 && h.clicked.length === 0)
        ? true : "暂停后出了手";
});

check("P4·状态机：正常一轮 → 算棋、核对、点击、确认各一次", function () {
    const h = harness();
    const prep = advanceToPrecheck(h);
    if (!prep.ok) {
        return prep.reason;
    }
    if (h.machine.state() !== "PRECHECK") {
        return "拿到着法后状态应为 PRECHECK，实际 " + h.machine.state();
    }
    const pre = h.dispatch(h.snapshotEvent(START_BOARD, "w"));
    if (h.effectsOf("EXECUTE_TICKET") !== 1) {
        return "出手前核对没有通过：" + pre.reason;
    }
    const executed = h.executeCurrentTicket();
    if (executed.outcome.status !== "SENT") {
        return "点击没发出：" + executed.outcome.status + " " + executed.outcome.reason;
    }
    if (h.clicked.length !== 2) {
        return "点击次数不是 2：" + h.clicked.length;
    }
    if (h.machine.state() !== "VERIFYING") {
        return "点击后应在确认状态，实际 " + h.machine.state();
    }
    const verdict = verifier.verify({ ticket: executed.ticket, observed: AFTER_BOARD.slice() });
    h.dispatch({ type: "VERIFY_RESULT", ticket: executed.ticket, verdict: verdict });
    const counters = h.machine.counters();
    if (counters.confirms !== 1) {
        return "确认计数应为 1，实际 " + counters.confirms;
    }
    if (counters.clicks !== 2) {
        return "点击计数应为 2，实际 " + counters.clicks;
    }
    // 确认之后**不该**再发 RECOVER：那会让驱动重新定位+校准（真机实测一次 ~8 秒，
    // CONFIRMED + OPPONENT_REPLY 各一次 = 一手棋白烧 ~16 秒）。只等下一帧观测即可。
    if (h.machine.state() !== "WAIT_STABLE") {
        return "确认后应等下一帧观测（WAIT_STABLE），实际 " + h.machine.state();
    }
    if (h.effectsOf("RECOVER") !== 0) {
        return "确认后不该重新定位（RECOVER 动作数 " + h.effectsOf("RECOVER") + "）";
    }
    return executed.ticket.executionState === "CONFIRMED"
        ? true : ("票据状态不是 CONFIRMED：" + executed.ticket.executionState);
});

check("P4·状态机：只识别/建议模式任何输入都不产生点击（A05）", function () {
    const h = harness({ allowClick: false });
    const prep = advanceToPrecheck(h);
    if (!prep.ok) {
        return prep.reason;
    }
    // 建议模式拿到着法后应停在 **SUGGESTED 稳态**（以前停在 PRECHECK，下一次观测就被
    // 「出手前核对不通过：当前模式禁止点击」打成 RECOVERING → 真机 ② 模式一直空转）
    if (h.machine.state() !== "SUGGESTED") {
        return "拿到着法后应停在 SUGGESTED，实际 " + h.machine.state();
    }
    if (h.effectsOf("SUGGEST") !== 1) {
        return "没把建议交给驱动（SUGGEST 动作数 " + h.effectsOf("SUGGEST") + "）";
    }
    const pre = h.dispatch(h.snapshotEvent(START_BOARD, "w"));
    if (h.effectsOf("EXECUTE_TICKET") !== 0) {
        return "只识别模式下仍然产生了点击动作";
    }
    if (h.clicked.length !== 0 || h.disabledTap.calls() !== 0) {
        return "只识别模式下的点击调用数不为 0：" + h.clicked.length + "/" + h.disabledTap.calls();
    }
    // 同一局面重复观测：必须安静（不点击、不重算、更不能掉进恢复）
    if (h.machine.state() !== "SUGGESTED") {
        return "同局面重复观测后状态变成了 " + h.machine.state() + "（应保持 SUGGESTED）";
    }
    if (h.effectsOf("COMPUTE") !== 1) {
        return "同局面重复观测重复触发了算棋（COMPUTE 次数 " + h.effectsOf("COMPUTE") + "）";
    }
    if (h.effectsOf("RECOVER") !== 0) {
        return "建议模式不该进恢复流程（RECOVER 动作数 " + h.effectsOf("RECOVER") + "）";
    }
    return pre.reason.indexOf("建议") >= 0 ? true : ("拒绝/保持理由没说清：" + pre.reason);
});

check("P4·状态机：建议模式轮次未知不跑看门狗、不进恢复（② 空转 bug 回归）", function () {
    const h = harness({ allowClick: false });
    h.dispatch({ type: "START" });
    h.dispatch({ type: "READY", modelVersion: "self-check-model" });
    h.dispatch({ type: "GEOMETRY", geometryVersion: 7, trusted: true });
    h.clock.value += 1000;
    h.dispatch({ type: "SNAPSHOT", snapshot: { board: START_BOARD.slice(), side: null, fen: START_FEN, unknownCells: 0, stable: true } });
    if (h.machine.state() !== "WAIT_TURN") {
        return "轮次未知应停在 WAIT_TURN，实际 " + h.machine.state();
    }
    h.clock.value += 60000;   // 远超 5s 看门狗
    h.dispatch({ type: "TICK", nowMono: h.clock.value });
    if (h.machine.counters().watchdogTrips !== 0) {
        return "建议模式不该触发轮次看门狗（次数 " + h.machine.counters().watchdogTrips + "）";
    }
    if (h.machine.state() !== "WAIT_TURN") {
        return "建议模式轮次未知超时后不该变状态，实际 " + h.machine.state();
    }
    return h.effectsOf("RECOVER") === 0 ? true : "建议模式轮次未知却发起了恢复";
});

check("P4·状态机：确认没落子 → 最多重试一次，且第二次用的是新票据", function () {
    const h = harness();
    const prep = advanceToPrecheck(h);
    if (!prep.ok) {
        return prep.reason;
    }
    h.dispatch(h.snapshotEvent(START_BOARD, "w"));
    const first = h.executeCurrentTicket();
    const unchanged = verifier.verify({ ticket: first.ticket, observed: START_BOARD.slice() });
    h.dispatch({ type: "VERIFY_RESULT", ticket: first.ticket, verdict: unchanged });
    if (h.machine.counters().retries !== 1) {
        return "第一次没落子后没有准备重试：" + h.machine.counters().retries;
    }
    if (h.machine.state() !== "PRECHECK") {
        return "重试前应回到 PRECHECK，实际 " + h.machine.state();
    }
    h.dispatch(h.snapshotEvent(START_BOARD, "w"));
    if (h.effectsOf("EXECUTE_TICKET") !== 2) {
        return "重试没有发出第二次点击：" + h.effectsOf("EXECUTE_TICKET");
    }
    const second = h.executeCurrentTicket();
    if (second.ticket.actionId === first.ticket.actionId) {
        return "重试复用了同一张票据（违反「同一票据只执行一次」）";
    }
    const unchanged2 = verifier.verify({ ticket: second.ticket, observed: START_BOARD.slice() });
    h.dispatch({ type: "VERIFY_RESULT", ticket: second.ticket, verdict: unchanged2 });
    if (h.machine.counters().retries !== 1) {
        return "重试计数被加了多次：" + h.machine.counters().retries;
    }
    return h.effectsOf("EXECUTE_TICKET") === 2
        ? true : ("重试用尽了还在点：" + h.effectsOf("EXECUTE_TICKET"));
});

check("P4·状态机：确认超时 → 只恢复，不重复盲点", function () {
    const h = harness();
    const prep = advanceToPrecheck(h);
    if (!prep.ok) {
        return prep.reason;
    }
    h.dispatch(h.snapshotEvent(START_BOARD, "w"));
    h.executeCurrentTicket();
    const before = h.effectsOf("EXECUTE_TICKET");
    h.clock.value += 3000;   // 超过 verifyTimeoutMs=2000
    h.dispatch({ type: "TICK", nowMono: h.clock.value });
    if (h.machine.state() !== "RECOVERING") {
        return "确认超时后应进入恢复，实际 " + h.machine.state();
    }
    return h.effectsOf("EXECUTE_TICKET") === before
        ? true : "确认超时后又点了一次";
});

check("P4·状态机：确认期间「轮次未知」的观测不许把状态拽出 VERIFYING（真机 07:43 UNRELIABLE 死循环根因）", function () {
    // 真机 2026-09-21 07:42-07:45（K40）：点击 g0g3 后确认连刷「装饰容差下 12~13 种应手解释」，
    // 而每条观测的轮次都推不出来（基准被幻影/重建搅乱）⇒ 旧顺序里「轮次未知看门狗」在
    // 「点击/确认中」早退之前动手：4s 一跳 RECOVERING → 恢复 → 回来还是确认同一张票据，
    // 2.5s 的「确认超时 → CANCEL_TICKET」永远等不到（TICK 到时状态总不是 VERIFYING）
    // ⇒ 同一手卡死两分半，最后还把 activeOwnMove 清掉。
    // 修：点击/确认进行中时，观测只喂确认流程 —— 先于任何看门狗/几何/冲突判断返回。
    const h = harness({});
    const prep = advanceToPrecheck(h);
    if (!prep.ok) {
        return prep.reason;
    }
    h.dispatch(h.snapshotEvent(START_BOARD, "w"));
    h.executeCurrentTicket();
    if (h.machine.state() !== "VERIFYING") {
        return "夹具前提不成立：点击后没进 VERIFYING（" + h.machine.state() + "）";
    }
    // 一条「轮次未知」的观测：状态必须还是 VERIFYING（修前会被拽成 WAIT_TURN）
    h.clock.value += 300;
    h.dispatch({ type: "SNAPSHOT", snapshot: { board: START_BOARD.slice(), side: null, fen: START_FEN, unknownCells: 0, stable: true } });
    if (h.machine.state() !== "VERIFYING") {
        return "轮次未知的观测把状态拽走了：" + h.machine.state() + "（" + h.machine.reason() + "）";
    }
    // 时间推过确认超时（不足看门狗）：应走「确认超时 → RECOVER + CANCEL_TICKET」，不许被看门狗抢先
    const watchBefore = h.machine.counters().watchdogTrips;
    h.clock.value += 3000;
    h.dispatch({ type: "TICK", nowMono: h.clock.value });
    if (h.machine.counters().watchdogTrips !== watchBefore) {
        return "确认期间触发了轮次看门狗（次数 " + h.machine.counters().watchdogTrips + "）";
    }
    if (h.machine.state() !== "RECOVERING") {
        return "确认超时后应进 RECOVERING，实际 " + h.machine.state();
    }
    return h.effectsOf("CANCEL_TICKET") >= 1 ? true : "确认超时没有发 CANCEL_TICKET";
});

check("P4·状态机：未知格 / 几何不可信 → 不出手也不算棋", function () {
    const h = harness();
    h.dispatch({ type: "START" });
    h.dispatch({ type: "READY", modelVersion: "self-check-model" });
    h.dispatch({ type: "GEOMETRY", geometryVersion: 7, trusted: true });
    const unknown = h.dispatch(h.snapshotEvent(START_BOARD, "w", { unknownCells: 3 }));
    if (h.effectsOf("COMPUTE") !== 0) {
        return "有未知格仍然开始算棋：" + unknown.state;
    }
    const untrusted = h.dispatch(h.snapshotEvent(START_BOARD, "w", { geometryTrusted: false }));
    if (h.effectsOf("COMPUTE") !== 0 || h.machine.state() !== "RECOVERING") {
        return "几何不可信时状态不对：" + untrusted.state;
    }
    return (h.effectsOf("EXECUTE_TICKET") === 0 && h.clicked.length === 0)
        ? true : "不可靠观测下仍然出了手";
});

check("P4·状态机：接管新会话会清基准，旧票据作废", function () {
    const h = harness();
    const prep = advanceToPrecheck(h);
    if (!prep.ok) {
        return prep.reason;
    }
    const ticket = h.machine.currentTicket();
    h.machine.setSession(2);
    const gate = h.machine.canClick(ticket);
    if (gate.ok) {
        return "换会话后旧票据仍可执行";
    }
    return gate.reason.indexOf("会话") >= 0 ? true : ("拒绝原因没说清：" + gate.reason);
});

// ---------------------------------------------------- 几何自动微调（autoAlign）

/** 造一个 9×10 的点阵几何；格距 115px，和 K40 实测同量级。 */
function makeGrid(originX, originY, version) {
    return geometry.createGrid({
        originX: originX, originY: originY, stepX: 115, stepY: 115,
        orientation: "RED_BOTTOM", version: version == null ? 1 : version
    });
}

/** 在给定点阵上，把每个交点摆一个检测框（centreAt 可整体平移，模拟检测框自身的偏置）。 */
function boxesOnLattice(lattice, centreOffsetX, centreOffsetY) {
    const list = [];
    for (let row = 0; row < 10; row++) {
        for (let col = 0; col < 9; col++) {
            const point = geometry.pointAt(lattice, col, row);
            list.push({
                label: "P", score: 0.9,
                x: point.x + (centreOffsetX || 0),
                y: point.y + (centreOffsetY || 0)
            });
        }
    }
    return list;
}

check("autoAlign：几何已对准时不挪（幂等）", function () {
    const lattice = makeGrid(80, 660);
    const report = recognizer.alignGrid(boxesOnLattice(lattice, 0, 0), lattice, {}, {}).report;
    if (report.moved || report.applied) {
        return "对着准的几何还挪了：" + report.dx + "," + report.dy + "（增益 " + report.gain + "）";
    }
    return eq(report.dx, 0, "dx");
});

check("autoAlign：同样的几何连跑两次，第二次一定不动（旧目标不幂等，会每帧 ±3 抖）", function () {
    const first = recognizer.alignGrid(boxesOnLattice(makeGrid(80, 660), 4, 0), makeGrid(80, 660), {}, {});
    if (!first.report.applied) {
        return "4px 偏置应当被纠正，实际 applied=false（增益 " + first.report.gain + "）";
    }
    const second = recognizer.alignGrid(boxesOnLattice(makeGrid(80, 660), 4, 0), first.grid, {}, {});
    if (second.report.moved || second.report.applied) {
        return "第二次又挪了：" + second.report.dx + "," + second.report.dy;
    }
    return eq(second.report.versionAfter, first.report.versionAfter, "版本");
});

check("autoAlign：检测框整体偏置 18px 时**拒绝**挪几何（挪了颜色采样就废）", function () {
    // K40 取证：框拟合想把几何挪 (12,6)，挪完黑子被判成红子（红 28 黑 2）。颜色采样以交点为心，
    // 所以超过 maxApply 的位移必须拒绝，保留颜色校准过的几何。
    const lattice = makeGrid(80, 660);
    const report = recognizer.alignGrid(boxesOnLattice(lattice, 12, 6), lattice, {}, {}).report;
    if (report.applied) {
        return "超上限的位移不该应用：" + report.dx + "," + report.dy;
    }
    if (!report.refused) {
        return "拒绝了但没说原因（refused 为空）";
    }
    return eq(report.versionAfter, report.versionBefore, "版本");
});

check("autoAlign：位移在 [minApply,maxApply] 内 → 改几何并把版本 +1（票据必须作废）", function () {
    const lattice = makeGrid(80, 660);
    const report = recognizer.alignGrid(boxesOnLattice(lattice, 4, 2), lattice, {}, {}).report;
    if (!report.moved) {
        return "6px 位移应当纠正，实际 moved=false（增益 " + report.gain + "）";
    }
    if (!report.applied) {
        return "位移 6px 在 [minApply,maxApply] 内却没应用（refused=" + report.refused + "）";
    }
    return eq(report.versionAfter, report.versionBefore + 1, "版本");
});

check("autoAlign：位移小于 minApply 时只改本帧读数、不动执行几何（票据不作废）", function () {
    const lattice = makeGrid(80, 660);
    const report = recognizer.alignGrid(boxesOnLattice(lattice, 2, 0), lattice, {}, { minApply: 6 }).report;
    if (report.applied) {
        return "位移没到 minApply 却改了执行几何：" + report.dx + "," + report.dy;
    }
    return eq(report.versionAfter, report.versionBefore, "版本");
});

check("autoAlign：定位整体错 40px（框全出界）→ 不硬凑，报告 suspect 交给重新定位", function () {
    const trueLattice = makeGrid(80, 660);
    const wrongGrid = makeGrid(40, 620);   // 错 40px
    const report = recognizer.alignGrid(boxesOnLattice(trueLattice, 0, 0), wrongGrid, {}, {}).report;
    if (report.applied) {
        return "40px 错位不该靠微调解决（应当 RECOVER 重新定位）";
    }
    return report.suspect ? true : "40px 错位却没有报告 suspect";
});

// ---------------------------------------------------------------- 算棋挪到 worker（独立引擎线程）

// ---------------------------------------------------------------- 差分追踪（用户 2026-09-20 提的算法）
// 「先看哪一格动了，再问那个兵种能不能这么走」——与「枚举全部合法着法 × 整盘比对」互为补充：
// 追踪便宜（两个格子 + 一段路程）、且**不看落点格读成了什么**（高亮环把落点读花也认得出）。
check("追踪：落点格被游戏画花（读成敌色）也认得出这一手 —— 真机 now24 的 C2→C6", function () {
    const before = START_BOARD.slice();
    const after = legal.applyMove(before, OWN_MOVE).board;
    const dest = transition.squareIndex(OWN_MOVE.iccs, 1);
    if (dest < 0) {
        return "夹具前提不成立：OWN_MOVE.iccs = " + OWN_MOVE.iccs;
    }
    after[dest] = "p";                       // 高亮环：我方炮被读成黑卒
    const got = transition.inferTracked(before, "w", after,
        { allowPair: true, allowSourceGhost: true });
    if (!got || got.status !== "MOVE") {
        return "没追出来（" + (got ? got.status : "null") + "）";
    }
    if (String(got.moves[0].iccs) !== String(OWN_MOVE.iccs)) {
        return "追出来的着法不对：" + got.moves[0].iccs + "，期望 " + OWN_MOVE.iccs;
    }
    if (got.nextSide !== "b") {
        return "下一手该是黑方，实际 " + got.nextSide;
    }
    const applied = legal.applyMove(before, OWN_MOVE).board;
    for (let i = 0; i < 90; i++) {
        if ((got.board[i] || null) !== (applied[i] || null)) {
            return "追踪给的盘面不是「基准 + 这一步」（第 " + i + " 格）";
        }
    }
    return true;
});

check("追踪：兵种规则当真 —— 车路被挡 / 蹩马腿 / 炮吃子没架子，一律不给结论", function () {
    const cases = [];
    // ① 车 a0→a7：中间 (0,3) 有黑卒 ⇒ 车路被挡
    let before = START_BOARD.slice();
    let after = before.slice();
    after[rules.idx(0, 0)] = null;
    after[rules.idx(0, 7)] = "R";
    cases.push({ name: "车路被挡", board: before, observed: after });
    // ② 马 (1,0)→(3,1)：马腿 (2,0) 是象 ⇒ 蹩马腿
    before = START_BOARD.slice();
    after = before.slice();
    after[rules.idx(1, 0)] = null;
    after[rules.idx(3, 1)] = "N";
    cases.push({ name: "蹩马腿", board: before, observed: after });
    // ③ 炮 (1,7)→(1,9) 吃马：中间一个架子都没有 ⇒ 炮不能吃子
    before = START_BOARD.slice();
    after = before.slice();
    after[rules.idx(1, 7)] = null;
    after[rules.idx(1, 9)] = "C";
    cases.push({ name: "炮吃子没架子", board: before, observed: after });
    for (let i = 0; i < cases.length; i++) {
        const got = transition.inferTracked(cases[i].board, "w", cases[i].observed,
            { allowPair: true, allowSourceGhost: true });
        if (got) {
            return "「" + cases[i].name + "」不该给结论，却给出 " + got.status + " " + got.moves[0].iccs;
        }
    }
    return true;
});

check("追踪：差分里有看不清的格子就不下结论", function () {
    const before = START_BOARD.slice();
    const after = legal.applyMove(before, OWN_MOVE).board;
    after[transition.squareIndex(OWN_MOVE.iccs, 1)] = "p";   // 落点画花（追踪本来能认）
    after[rules.idx(4, 5)] = "?";                            // 另一格看不清 ⇒ 不猜
    const got = transition.inferTracked(before, "w", after,
        { allowPair: true, allowSourceGhost: true });
    if (got) {
        return "有未知格还给结论：" + got.status;
    }
    return true;
});

check("追踪：起点空了 + 落点只读得出 '?'（光环）也能追出来（用户口径：按原来的位置扫）", function () {
    // 用户 2026-09-20 口径：「按照对方的原来位置扫描，就可以退出来了 —— 那个棋不在那个位置」。
    // 真机就是这么卡的：起点已空（子确实走了），落点被高亮环盖住只剩 '?'，
    // 旧逻辑要求「落点必须是个子」⇒ 推不出来 ⇒ 恢复。现在只枚举**那一枚子自己的**合法走法。
    const before = START_BOARD.slice();
    const after = legal.applyMove(before, OWN_MOVE).board;
    const src = transition.squareIndex(OWN_MOVE.iccs, 0);
    const dest = transition.squareIndex(OWN_MOVE.iccs, 1);
    after[dest] = "?";                       // 光环：落点看不到子
    const got = transition.inferTracked(before, "w", after,
        { allowPair: true, allowSourceGhost: true });
    if (!got || got.status !== "MOVE") {
        return "没追出来（" + (got ? got.status : "null") + "）";
    }
    if (String(got.moves[0].iccs) !== String(OWN_MOVE.iccs)) {
        return "追出来的着法不对：" + got.moves[0].iccs;
    }
    if (got.board[dest] !== before[src]) {
        return "落点没按着法改正（应为 " + before[src] + "，实际 " + got.board[dest] + "）";
    }
    if (String(got.reason).indexOf("?") >= 0) {
        return "reason 里混进了「这一帧读成了什么」（会让跨帧佐证永远凑不齐）：" + got.reason;
    }
    return true;
});

check("追踪：起点空了但落点还是空的（子还在飞）⇒ 不许当走完了", function () {
    const before = START_BOARD.slice();
    const after = before.slice();
    after[transition.squareIndex(OWN_MOVE.iccs, 0)] = null;   // 只有起点空了
    const got = transition.inferTracked(before, "w", after,
        { allowPair: true, allowSourceGhost: true });
    if (got) {
        return "子还在飞就被当成走完了：" + got.status + " " + got.moves[0].iccs;
    }
    return true;
});

check("追踪：两步（我方一步 + 对方应手）也追得出，且两个落点都被画花", function () {
    const before = START_BOARD.slice();
    const ours = OWN_MOVE;
    const mid = legal.applyMove(before, ours).board;
    const replies = legal.allLegalMoves(mid, "b").filter(function (move) {
        // 挑一个「不吃子」的应手：落点在中间局面里是空的（吃子会把两处差分并到一格，夹具就没意义了）
        return !mid[rules.idx(move.toCol, move.toRow)];
    });
    if (!replies.length) {
        return "夹具前提不成立：对方没有不吃子的着法";
    }
    const reply = replies[0];
    const after = legal.applyMove(mid, reply).board;
    after[transition.squareIndex(ours.iccs, 1)] = "p";       // 我方落点画花
    after[transition.squareIndex(reply.iccs, 1)] = "c";      // 对方落点也画花
    const got = transition.inferTracked(before, "w", after,
        { allowPair: true, allowSourceGhost: true });
    if (!got || got.status !== "MOVE_PAIR") {
        return "没追出两步（" + (got ? got.status : "null") + "）";
    }
    if (String(got.moves[0].iccs) !== String(ours.iccs) ||
        String(got.moves[1].iccs) !== String(reply.iccs)) {
        return "两步追错了：" + got.moves[0].iccs + " + " + got.moves[1].iccs +
            "，期望 " + ours.iccs + " + " + reply.iccs;
    }
    return true;
});

const searchWorker = require("../src/automation/search-worker.js");
/**
 * 驱动场景用例的宿主确定性（2026-09-23 真机实修）。
 *
 * 这些用例假定「算棋在本线程同步完成」：Node 宿主没有 threads，驱动自然走 inline；
 * 而手机上宿主**有** threads ⇒ 驱动默认 worker，算棋异步完成，用例就停在 THINKING
 * （K40 实测 27 例失败，清单见 .artifacts/k40-now/a1-selfcheck-detail.log）。
 * 与其逐个用例改（≈40 处），这里套一层壳：测试里**缺省**把 computeMode 强制成 "inline"，
 * 显式传了 computeMode 的调用不受影响（worker 的定向用例在 searchWorker 层测）。
 */
const driverModule = (function () {
    const raw = require("../src/automation/auto-driver.js");
    return Object.assign({}, raw, {
        createDriver: function (options) {
            const opts = Object.assign({}, options || {});
            opts.config = Object.assign({ computeMode: "inline" }, opts.config || {});
            return raw.createDriver(opts);
        }
    });
})();
const overlayDraw = require("../src/vision/overlay-draw.js");

check("worker 算棋：宿主没有 threads 时必须报不可用（Node 侧就是这种情况）", function () {
    if (typeof threads !== "undefined") {
        return true;   // 设备端：有 threads，跳过这条
    }
    return searchWorker.available() === false ? true : "Node 下 available() 应为 false";
});

check("worker 算棋：回传载荷「对象」与「JSON 串」都要认（引擎会提前反序列化成对象）", function () {
    const asObject = searchWorker.decode({ ok: true, iccs: "b2e2", ms: 1285 });
    if (!asObject || asObject.iccs !== "b2e2") {
        return "对象形态没认出来（真机就是这种情况：引擎已把 JSON 串 parse 成对象）";
    }
    const asText = searchWorker.decode('{"ok":true,"iccs":"b2e2"}');
    if (!asText || asText.iccs !== "b2e2") {
        return "JSON 串形态没认出来";
    }
    if (searchWorker.decode("worker 挂了") !== null || searchWorker.decode(null) !== null) {
        return "非 JSON 载荷应当返回 null（宁可报错也不要拿错数据）";
    }
    return true;
});

check("驱动：没有 threads 时自动退回本线程同步算棋（computeMode=inline）", function () {
    const driver = driverModule.createDriver({
        allowClick: false,
        recognize: function () {
            return { ok: true, chars: START_BOARD.slice(), unknownCells: 0, conflicts: 0, layout: START_FEN };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 }; },
        config: { movetimeMs: 20 }
    });
    if (driver.computeMode() !== "inline") {
        return "没有 threads 时应当是 inline，实际 " + driver.computeMode();
    }
    return typeof driver.isThinking === "function" && typeof driver.pollCompute === "function"
        ? true
        : "驱动没有暴露 isThinking/pollCompute";
});

check("驱动：pollCompute 空闲时返回 null，不制造假任务", function () {
    const driver = driverModule.createDriver({
        allowClick: false,
        recognize: function () {
            return { ok: true, chars: START_BOARD.slice(), unknownCells: 0, conflicts: 0, layout: START_FEN };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 }; },
        config: { movetimeMs: 20 }
    });
    if (driver.pollCompute() !== null) {
        return "空闲时 pollCompute 应当返回 null";
    }
    return driver.isThinking() === false ? true : "空闲时 isThinking 应当为 false";
});

check("驱动：基准带未知格时不许假装补齐（真机 ② 空转 bug 回归）", function () {
    const board = START_BOARD.slice();
    board[0] = "?";   // 人为制造「被高亮环画花」的一格
    const driver = driverModule.createDriver({
        allowClick: false,
        recognize: function () {
            return { ok: true, chars: board.slice(), unknownCells: 1, conflicts: 0, layout: null };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 }; },
        config: { movetimeMs: 20 }
    });
    driver.start();
    driver.tick();   // 建立识别基准
    // 1) 带未知格的局面不能当基准（否则后面会把 '?' 当事实抄回去）
    const asserted = driver.assertTurn();
    if (asserted.ok) {
        return "带未知格的局面不该允许声明轮次";
    }
    // 2) 再观察一轮：不许报「已补齐」、更不许把异常抛出（旧行为：补成 unknownCells=0 → buildFen 抛
    //    「存在未知格，不能生成 FEN」→ 每轮都被当异常跳过，面板上只看到「轮次未知」）
    const before = driver.stats().knownFactFills;
    driver.tick();
    const after = driver.stats();
    if (after.knownFactFills !== before) {
        return "没有干净基准时报补齐成功（knownFactFills " + before + " → " + after.knownFactFills + "）";
    }
    if (after.errors !== 0) {
        return "不该抛异常（errors=" + after.errors + "：" + after.lastError + "）";
    }
    return driver.machine.state() === "ERROR" ? "状态机被推进 ERROR" : true;
});

check("驱动·全盘 OCR：说的正是账本上已有的值 ⇒ 不必等 3 帧共识，当帧就登记成已知事实（真机 18:15）", function () {
    // 真机现场（2026-09-20）：我方炮那格被高亮环读成黑卒 `p`，而全盘 OCR 每 6s 才跑一次
    // ⇒「翻色要 3 帧」≈ 18s 才落地，可轮次看门狗 4s 就先触发 ⇒ 每帧「轮次推不出来」→ 恢复。
    // 判据：OCR 说的与**上一张可靠基准**完全一致 ⇒ 那是把帧纠正回来，不是给账本加新事实。
    const idx = 0;                             // (0,0)：开局那枚黑車（'r'）
    const truth = START_BOARD[idx];
    const observed = START_BOARD.slice();
    let ocrOn = true;
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function (t) { noteText.push(String(t)); },
        sleep: function () { },
        recognize: function () {
            return {
                ok: true, chars: observed.slice(), unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrBoard: function () {
            const glyphs = new Array(90);
            const sides = new Array(90);
            const scores = new Array(90);
            if (ocrOn) {
                glyphs[idx] = "車";
                sides[idx] = "BLACK";
                scores[idx] = 0.95;
            }
            return {
                ok: true,
                chars: ocrOn ? START_BOARD.slice() : observed.slice(),
                glyphs: glyphs, sides: sides, scores: scores, hits: ocrOn ? 1 : 0
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 0 }
    });
    driver.start();
    driver.tick();                             // 第 1 帧：干净开局 ⇒ 基准 = 开局
    // 这一帧模型把黑車读成红車（错），而 OCR 说「还是黑車」= 账本上已有的值 ⇒ 不用等共识
    observed[idx] = truth.toUpperCase();
    driver.tick();
    const registered = noteText.some(function (t) { return t.indexOf("按已知事实补齐") >= 0; });
    if (!registered) {
        return "与账本一致的 OCR 结论不必等共识，应当帧就登记 — 日志 " +
            noteText.filter(function (t) { return t.indexOf("全盘 OCR") >= 0; }).slice(-2).join(" / ");
    }
    // 之后 OCR 不可用的帧也不许把那格读回错的颜色（已知事实在兜）
    ocrOn = false;
    driver.tick();
    driver.tick();
    const seen = driver.lastBoard();
    if (!seen || seen[idx] !== truth) {
        return "已知事实没兜住那一格：应为 " + truth + "，实际 " + (seen ? seen[idx] : "无观测");
    }
    return true;
});

check("驱动·全盘 OCR 改正：兵种取字形、颜色取像素，且**翻色要连续 3 帧一致**（真机 (8,9) + 2026-09-20 闸门）", function () {
    // 真机事故：对手的黑炮吃了我的底車，模型把那一格读成 R（車，旧内容）；
    // 全盘 OCR 认出「炮」—— 旧代码纠兵种时**颜色取模型**，于是写成 C（红炮）⇒ 盘面「红炮 3 个」
    // ⇒ 规则门否决整帧、棋卡死。用户口径（2026-09-20）：OCR 只管兵种，红黑一律从像素来。
    // ★ 2026-09-20 再加闸门：单帧 OCR 直接写基准曾把账本改漂（重复黑炮/丢帅）⇒
    //   「翻颜色」必须连续 3 帧读到同一个字、且像素（找色）给出决定性结论。
    const idx = 89;                    // (8,9)
    const observed = START_BOARD.slice();
    observed[idx] = "R";               // 模型读成红車（错）
    const ocrChars = START_BOARD.slice();
    ocrChars[idx] = "c";               // board-scan：字形「炮」+ 像素采样=黑
    const noteText = [];
    const sides = new Array(90);
    sides[idx] = "BLACK";
    const scores = new Array(90);
    scores[idx] = 0.9;
    // 证据先不给（assertTurn 那一帧不能已被改过，否则基准一开始就是 c，测不出「基准要共识」）
    const fixture = { on: false };
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function (text) { noteText.push(String(text)); },
        sleep: function () { },
        recognize: function () {
            let unknown = 0;
            for (let i = 0; i < 90; i++) {
                if (observed[i] === "?") { unknown += 1; }
            }
            return {
                ok: true, chars: observed.slice(), unknownCells: unknown, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrBoard: function () {
            const chars = ocrChars.slice();
            const glyphs = new Array(90);
            glyphs[idx] = "炮";
            if (!fixture.on) {
                chars[idx] = "R";          // 还没给证据：OCR 也按模型读数返回
            }
            return {
                ok: true, chars: chars, glyphs: glyphs,
                sides: sides.slice(), scores: scores.slice(), hits: 1
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        // 闸门按「一次询问 = 一帧」计数；自检里把限频关掉，每次 tick 都是一帧
        config: { movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 0 }
    });
    driver.start();
    driver.tick();
    driver.assertTurn();   // 基准 = 当前观测（此时那一格还是模型读的 R）
    if (driver.lastReliable().board[idx] !== "R") {
        return "声明基准时那一格应当还是 R，实际 " + driver.lastReliable().board[idx];
    }
    fixture.on = true;
    driver.tick();         // 第 1 帧
    // ★ 本帧观测要**立即**用上（否则同帧的验证会看到旧兵种 → UNEXPECTED）
    const seen1 = driver.lastBoard();
    if (!seen1 || seen1[idx] !== "c") {
        return "本帧观测应当立即按证据改成 c（验证靠它），实际 " +
            (seen1 ? seen1[idx] : "无观测") + " — 日志 " +
            noteText.filter(function (t) { return t.indexOf("全盘 OCR") >= 0; }).slice(-2).join(" / ");
    }
    if (driver.lastReliable().board[idx] !== "R") {
        return "基准还轮不到第 1 帧就改（账本漂移就是这么来的）：" + driver.lastReliable().board[idx];
    }
    driver.tick();         // 第 2 帧
    if (driver.lastReliable().board[idx] !== "R") {
        return "翻色要连续 3 帧，第 2 帧仍不该写基准，实际 " + driver.lastReliable().board[idx];
    }
    driver.tick();         // 第 3 帧 ⇒ 采纳
    const reliable = driver.lastReliable();
    if (!reliable || !reliable.board) {
        return "没有可靠基准";
    }
    if (reliable.board[idx] !== "c") {
        return "(8,9) 应当是黑炮 c（兵种取字形、颜色取像素），实际 " + reliable.board[idx] +
            "（若为 C 就是又把颜色取成了模型那一套）— 日志 " +
            noteText.filter(function (t) { return t.indexOf("全盘 OCR") >= 0; }).slice(-2).join(" / ");
    }
    return true;
});

check("驱动·全盘 OCR 硬闸：已知事实已经定死颜色的格子，像素说反色也不许翻（真机 17:49 事故）", function () {
    // 真机现场（2026-09-20，K40）：我方 h2c2 的落点带高亮环 ⇒ 像素把红炮判成黑 ⇒
    // 三帧共识把「黑炮」写进基准 ⇒ 之后每帧都对不上、白烧一次恢复。
    // 棋子一局里**不会改颜色**：已知事实（票据 + 棋规推出来的）说这格是我方炮，它就是红炮。
    const text = String(OWN_MOVE.iccs);
    const dest = (9 - Number(text.charAt(3))) * 9 + "abcdefghi".indexOf(text.charAt(2));
    let observed = START_BOARD.slice();        // 先给一张干净开局，让驱动建立轮次基准
    let truth = null;
    let flipOn = false;                        // 真机时序：落点**先**登记成已知事实，之后像素才开始撒谎
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function (text2) { noteText.push(String(text2)); },
        sleep: function () { },
        recognize: function () {
            return {
                ok: true, chars: observed.slice(), unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrBoard: function () {
            const chars = observed.slice();
            if (flipOn && truth) {
                chars[dest] = truth === truth.toUpperCase() ? truth.toLowerCase() : truth.toUpperCase();
            }
            const glyphs = new Array(90);
            const sides = new Array(90);
            const scores = new Array(90);
            if (flipOn && truth) {
                glyphs[dest] = "炮";
                sides[dest] = truth === truth.toUpperCase() ? "BLACK" : "RED";   // 像素也站同一边（判反了）
                scores[dest] = 0.9;
            }
            return { ok: true, chars: chars, glyphs: glyphs, sides: sides, scores: scores, hits: 1 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 0 }
    });
    driver.start();
    driver.tick();                             // 开局 ⇒ 基准 = 开局、轮到红方
    // 我方走一步：跨帧佐证要连续两帧推出同一个解释，第 2 帧会把**落点登记成已知事实**
    truth = AFTER_BOARD[dest];
    observed = AFTER_BOARD.slice();
    driver.tick();
    driver.tick();
    const registered = noteText.some(function (t) { return t.indexOf("会被画环") >= 0; });
    // 现在才让「像素 + 全盘 OCR」把那格说成反色（真机就是高亮环骗了像素）
    flipOn = true;
    for (let i = 0; i < 4; i++) {
        driver.tick();
    }
    if (!registered) {
        return "夹具前提不成立：落点没被登记成已知事实 — 日志 " +
            noteText.filter(function (t) { return t.indexOf("已知事实") >= 0; }).slice(-2).join(" / ");
    }
    if (!(driver.stats().ocrFlipBlocked >= 1)) {
        return "颜色翻转没有被已知事实驳回（ocrFlipBlocked=" + driver.stats().ocrFlipBlocked + "）— 日志 " +
            noteText.filter(function (t) { return t.indexOf("全盘 OCR") >= 0; }).slice(-2).join(" / ");
    }
    const reliable = driver.lastReliable();
    if (!reliable || reliable.board[dest] !== truth) {
        return "已知事实被像素翻了颜色（应为 " + truth + "，实际 " +
            (reliable ? reliable.board[dest] : "无基准") + "）";
    }
    return true;
});

check("驱动：全盘 OCR 想改「我方刚落点」时整轮不许抛异常（真机 now22：from is not initialized 死循环）", function () {
    // 真机铁证（now22）：`activeOwnMove.destIndex` 早退块里用了 `from`/`want`，而这两个 const
    // 声明在它**下面** ⇒ 一命中就抛 TDZ（QuickJS 文案恰好是 `from is not initialized`）⇒
    // `applyOcrBoardFixes` 整体抛出 ⇒ 那一轮作废 ⇒ 落点格的误读留到下一帧 ⇒ 又「轮次推不出来」
    // ⇒ 恢复 ⇒ 再撞。异常被上层 catch 成「本轮异常（已跳过，下一轮重试）」——日志里像良性提示，
    // 实际是死循环的开关。这条用例只守一件事：那条闸门必须**静默驳回**，不许抛。
    const text = String(OWN_MOVE.iccs);
    const dest = (9 - Number(text.charAt(3))) * 9 + "abcdefghi".indexOf(text.charAt(2));
    const stage = { clicked: false, broken: false };
    const noteText = [];
    const board = function () {
        if (!stage.clicked) {
            return START_BOARD.slice();
        }
        const cells = AFTER_BOARD.slice();
        if (stage.broken) {
            cells[79] = "?";                    // 别的格子出未知格 ⇒ 逼确认/恢复路径把 ocrBoard 叫起来
        }
        return cells;
    };
    const driver = driverModule.createDriver({
        allowClick: true,
        log: function (t) { noteText.push(String(t)); },
        sleep: function () { },
        recognize: function () {
            const cells = board();
            let unknown = 0;
            for (let i = 0; i < 90; i++) {
                if (cells[i] === "?") {
                    unknown += 1;
                }
            }
            return {
                ok: true, chars: cells, unknownCells: unknown, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrBoard: function () {
            const chars = board();
            const glyphs = new Array(90);
            const sides = new Array(90);
            const scores = new Array(90);
            if (stage.clicked) {
                // 高亮环：「像素 + 全盘 OCR」都把我方刚落下的那格说成黑卒（真机现场）
                chars[dest] = "p";
                glyphs[dest] = "卒";
                sides[dest] = "BLACK";
                scores[dest] = 0.9;
            }
            return { ok: true, chars: chars, glyphs: glyphs, sides: sides, scores: scores, hits: 1 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 };
        },
        tap: function (x, y, tapOptions) {
            if (tapOptions && tapOptions.kind === "to") {
                stage.clicked = true;           // 落点那次 tap：从此我方刚落点 = 点击窗口内
            }
            return { ok: true, method: "self-check-tap" };
        },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 0 }
    });
    driver.start();
    for (let i = 0; i < 6 && !stage.clicked; i++) {
        driver.tick();                          // 走到「我方出手 → SENT」
    }
    if (!stage.clicked) {
        return "夹具前提不成立：点击没发生（状态 " + driver.machine.state() + "）";
    }
    stage.broken = true;                        // 别的格子出未知格 ⇒ 逼出恢复/OCR 路径
    for (let i = 0; i < 8; i++) {
        driver.tick();
    }
    const crashed = noteText.filter(function (t) { return t.indexOf("本轮异常") >= 0; });
    if (crashed.length) {
        return "改「我方刚落点」时整轮抛异常（" + crashed[0] + "）—— 那一刻会作废整轮、误读留到下一帧 ⇒ 死循环";
    }
    if (!(driver.stats().ocrFlipBlocked >= 1)) {
        return "没走到「我方刚落点」这条闸门（ocrFlipBlocked=" + driver.stats().ocrFlipBlocked +
            "；状态 " + driver.machine.state() + "）— 日志 " + noteText.slice(-5).join(" / ");
    }
    return true;
});

check("驱动：往空位补子必须有墨迹证据（真机 now29 坏轮：(4,7)空→C 与 (7,7) 打架）", function () {
    // 真机现场：OCR 在**空格** (4,7) 上读出「炮」，找色也说红 ⇒ 补进观测与账本 ⇒
    // 与 (7,7) 的真炮来回打架 ⇒ 一轮 15 次「轮次推不出来」。修法：往空位补子必须有**正面墨迹证据**
    // （找色两边的密度都低于门槛 ⇒ 驳回）。
    const empty = rules.idx(4, 7);            // 开局 (4,7) 是空的（红炮家在 (1,7)/(7,7)）
    let observed = START_BOARD.slice();
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function (t) { noteText.push(String(t)); },
        sleep: function () { },
        recognize: function () {
            return {
                ok: true, chars: observed.slice(), unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrBoard: function () {
            // 谎报：把空格 (4,7) 读成红炮（连续两帧一致 ⇒ 会过「共识」闸门）
            const chars = observed.slice();
            const glyphs = new Array(90);
            const sides = new Array(90);
            const scores = new Array(90);
            chars[empty] = "C";
            glyphs[empty] = "炮";
            sides[empty] = "RED";
            scores[empty] = 0.95;
            return { ok: true, chars: chars, glyphs: glyphs, sides: sides, scores: scores, hits: 1 };
        },
        ocrCell: function () {
            // 找色：那一格两边都没有墨迹（密度 0）⇒ 空格
            return { ok: true, side: null, cls: null, char: null, score: 0,
                ink: { area: 1000, red: 0, black: 0 } };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 0, ocrCooldownMs: 0 }
    });
    driver.start();
    for (let i = 0; i < 4; i++) {
        driver.tick();
    }
    if (!((driver.stats().ocrEmptyBlocked || 0) >= 1)) {
        return "空格补子没被墨迹证据驳回（ocrEmptyBlocked=" + (driver.stats().ocrEmptyBlocked || 0) +
            "）— 日志 " + noteText.filter(function (t) { return t.indexOf("全盘 OCR") >= 0; })
                .slice(-2).join(" / ");
    }
    const seen = driver.lastBoard();
    if (seen && seen[empty]) {
        return "空格还是被补上了子：" + seen[empty];
    }
    return true;
});

check("驱动：观察撞上走子动画（两帧不一致）⇒ 补一帧躲开，且仍是严格合并", function () {
    // 真机 now30：同一格一帧读成子、一帧读成暗影 ⇒ 严格合并降级成未知 ⇒ 整轮「布局没生成」白等。
    // ⚠️ 这条能力**默认关**（两轮真机 A/B：补帧每轮多 ~1.4s ⇒ 出招 8→4，得不偿失），
    //   所以用例里显式打开 —— 将来收紧触发条件再开时，这条用例能保证它仍然“只在变好时才采用”。
    const board = START_BOARD.slice();
    const dest = rules.idx(4, 5);
    let calls = 0;
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function (t) { noteText.push(String(t)); },
        sleep: function () { },
        recognize: function () {
            calls += 1;
            const cells = board.slice();
            cells[dest] = calls === 1 ? "p" : "P";   // 第一帧撞上动画（读成敌色），之后干净
            return {
                ok: true, chars: cells, unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 2, observeRetryGapMs: 0, observeRetryOnUnknown: true }
    });
    driver.start();
    for (let i = 0; i < 3; i++) {
        driver.tick();
    }
    if (!((driver.stats().observeRetries || 0) >= 1)) {
        return "没触发「补一帧」（observeRetries=" + (driver.stats().observeRetries || 0) + "）";
    }
    if (!((driver.stats().observeRetryHits || 0) >= 1)) {
        return "补帧没救回来（observeRetryHits=" + (driver.stats().observeRetryHits || 0) +
            "）— 日志 " + noteText.slice(-3).join(" / ");
    }
    const seen = driver.lastBoard();
    if (!seen || seen[dest] !== "P") {
        return "补帧后那格应是 P，实际 " + (seen ? seen[dest] : "无");
    }
    return true;
});

check("驱动·异色异字的落点误读：像素（找色）站已知事实这边时不许丢记录（真机 now23 死循环）", function () {
    // 真机铁证（now23）：我方炮落在 (2,3) 之后**被游戏画花**，模型把红炮读成**黑卒 p**
    // （异色 + 异字）⇒ 旧逻辑走 ④「真的被吃/走开了」把已知事实记录**丢掉** ⇒
    // 之后每帧都读成 p 也没人补 ⇒ 差分「变化 0 格」永远不动 ⇒ 每 12 秒一轮
    // `轮次推不出来 → 恢复 → 重建基准`（日志里 `(col 2,row 3) C→p` 反复出现）。
    // 歧义判据（本轮新增）：拿**第三票** —— 找色/像素说那格的墨迹是哪一边。
    const text = String(OWN_MOVE.iccs);
    const dest = (9 - Number(text.charAt(3))) * 9 + "abcdefghi".indexOf(text.charAt(2));
    let observed = START_BOARD.slice();
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function (t) { noteText.push(String(t)); },
        sleep: function () { },
        recognize: function () {
            return {
                ok: true, chars: observed.slice(), unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrBoard: function () {
            return {
                ok: true, chars: observed.slice(),
                glyphs: new Array(90), sides: new Array(90), scores: new Array(90), hits: 0
            };
        },
        ocrCell: function () {
            // 像素（找色）说那格是**红**的，兵种也读成「炮」——与已知事实一致
            return { ok: true, side: "RED", cls: "炮", char: "C", score: 0.9 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 0, ocrCooldownMs: 0 }
    });
    driver.start();
    driver.tick();                             // 开局 ⇒ 基准
    observed = AFTER_BOARD.slice();            // 我方这一手成立 ⇒ 落点登记成已知事实
    driver.tick();
    driver.tick();
    if (!noteText.some(function (t) { return t.indexOf("会被画环") >= 0; })) {
        return "夹具前提不成立：落点没被登记成已知事实 — 日志 " + noteText.slice(-3).join(" / ");
    }
    observed[dest] = "p";                      // 高亮环：红炮被读成黑卒（异色 + 异字）
    for (let i = 0; i < 3; i++) {
        driver.tick();
    }
    if (!(driver.stats().pixelKeptFacts >= 1)) {
        return "异色异字的落点误读没被像素证据挡下（pixelKeptFacts=" +
            (driver.stats().pixelKeptFacts || 0) + "）— 日志 " +
            noteText.filter(function (t) { return t.indexOf("已知事实") >= 0; }).slice(-3).join(" / ");
    }
    const seen = driver.lastBoard();
    if (!seen || seen[dest] !== "C") {
        return "观测/基准里那格没被保住（应为 C，实际 " + (seen ? seen[dest] : "无") + "）";
    }
    if (driver.stats().unknownTurn !== 0) {
        return "保住事实之后仍出现「轮次推不出来」" + driver.stats().unknownTurn + " 次";
    }
    return true;
});

check("驱动·恢复后重建基准：这一帧把子读错兵种，而全盘 OCR 说那格与上一张基准一致 ⇒ 保住基准（真机 17:59）", function () {
    // 真机现场（2026-09-20）：我方炮刚落下的那一格带高亮环，模型把它读成黑卒 `p` ⇒
    // 「恢复 → 按观测量重建基准」照单全收 ⇒ 基准被写坏 ⇒ 之后每帧都「解释不了 4 格差异」⇒ 又恢复。
    // 判据：全盘 OCR 说这格的字与**上一张基准完全一致**（兵种 + 颜色）⇒ 这格没动过，是帧读错了。
    const idx = 7 * 9 + 1;                     // (col 1, row 7)：开局那门红炮（b2）
    let observed = START_BOARD.slice();
    let clock = 0;
    if (START_BOARD[idx] !== "C") {
        return "夹具写错了：开局那格应当是红炮，实际 " + START_BOARD[idx];
    }
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function () { },
        sleep: function () { },
        recognize: function () {
            return {
                ok: true, chars: observed.slice(), unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrBoard: function () {
            // ⚠️ 冷却故意设得极大：让 applyOcrBoardFixes 那条路用不上，
            //    只剩「重建基准时的证据闸」能救 —— 否则这条用例测不出新逻辑（真机也是冷却没到）。
            const glyphs = new Array(90);
            const sides = new Array(90);
            const scores = new Array(90);
            for (let i = 0; i < 90; i++) {
                if (START_BOARD[i]) {
                    glyphs[i] = "炮";
                    sides[i] = START_BOARD[i] === START_BOARD[i].toUpperCase() ? "RED" : "BLACK";
                    scores[i] = 0.95;
                }
            }
            return { ok: true, chars: START_BOARD.slice(), glyphs: glyphs, sides: sides, scores: scores, hits: 30 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { clock += 1000; return clock; },
        config: {
            movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 999999,
            unknownTurnWatchdogMs: 4000, recoverRetryMs: 1000
        }
    });
    driver.start();
    driver.tick();                             // 基准 = 开局
    // 这一帧：(1,7) 的红炮读没了、(2,7) 冒出个黑卒 ⇒ 没有任何合法着法能解释 ⇒ 必然进恢复
    observed = START_BOARD.slice();
    observed[idx] = null;
    observed[2 * 9 + 7] = "p";
    const rebuildsBefore = driver.stats().baselineRebuilds;
    // 一直喂坏帧直到**重建基准刚发生**（重建后同一 tick 的观测就是我们要断言的东西；
    // 再多跑几 tick 会把 lastBoard 覆盖回原始坏帧，断言就没意义了）。
    for (let i = 0; i < 12 && driver.stats().baselineRebuilds === rebuildsBefore; i++) {
        driver.tick();
    }
    const rel = driver.lastReliable();
    if (!(driver.stats().baselineRebuilds >= 1)) {
        return "夹具前提不成立：重建基准没被触发（baselineRebuilds=" +
            driver.stats().baselineRebuilds + "）";
    }
    if (!rel || !rel.board) {
        return "没有可靠基准";
    }
    if (rel.board[idx] !== "C") {
        return "重建基准时该保住 (1,7) 的红炮（全盘 OCR 说它没动），实际 " + rel.board[idx];
    }
    // ★ 只保住基准还不够（真机 now21 死循环 22 次）：「保住」改的是 rebuilt，
    //   而**同一 tick 紧接着的 `sideFor` 用的是原始观测** —— 那一格还是误读 ⇒ 又「没有合法
    //   着法能解释」⇒ 又恢复 ⇒ 又重建 ⇒ 循环。改正必须写回观测（`lastBoard`）。
    const seenAfterRebuild = driver.lastBoard();
    if (seenAfterRebuild && seenAfterRebuild[idx] !== "C") {
        return "重建基准的改正没写回观测：lastBoard[1,7]=" + seenAfterRebuild[idx] +
            "（真机表现为相邻两帧都「轮次推不出来」的死循环）";
    }
    return true;
});

/**
 * 硬不变量的**两种处置**（2026-09-20 真机 now38 第一次跑就暴露了：白否 6 帧 = ~60s 恢复）：
 *   · 轮次是**兜底猜的** + 只有一方被将 ⇒ 矛盾恰好能定轮次（被将方必须应着）⇒ 改轮次继续用；
 *   · 两边同时被将（将帅照面）⇒ 怎么看都错 ⇒ 仍然否掉。
 * 造夹具的要点：观测必须**解释不了**（否则轮次能按着法推出来，走不到兜底那条路）。
 */
check("驱动·重建基准：兜底轮次与盘面冲突（只有一方被将）⇒ 按「被将方必须应着」改轮次继续用", function () {
    // 真机现场（now38）：兜底还说「黑方刚走完」，盘面却是红炮 (4,4) 隔着黑卒 (4,3) 在将黑将 (4,0)
    // ⇒ 真相是**红方刚走完、轮到黑方**，兜底过时了。老逻辑白否这张盘 ⇒ 恢复空转一分钟。
    const gun = 4 * 9 + 4;                     // (col 4, row 4)
    let observed = START_BOARD.slice();
    observed[2 * 9 + 1] = null;                // 红炮离开 (1,2)
    observed[gun] = "C";                       // 红炮出现在 (4,4)：不是直线走法 ⇒ 推不出轮次
    const noteText = [];
    let clock = 0;
    const driver = driverModule.createDriver({
        allowClick: true,                     // ⚠️ 建议模式（allowClick:false）不恢复、走不到重建基准
        tap: function () { return { ok: true, clicks: [] }; },
        log: function (t) { noteText.push(String(t)); },
        sleep: function () { },
        recognize: function () {
            return {
                ok: true, chars: observed.slice(), unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrBoard: function () {
            return {
                ok: true, chars: observed.slice(),
                glyphs: new Array(90), sides: new Array(90), scores: new Array(90), hits: 0
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { clock += 1000; return clock; },
        config: {
            movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 999999,
            unknownTurnWatchdogMs: 4000, recoverRetryMs: 1000
        }
    });
    driver.start();
    driver.tick();                             // 基准 = 标准开局，轮次红
    for (let i = 0; i < 20 && !(driver.stats().sideFromCheck >= 1); i++) {
        driver.tick();
    }
    if (!(driver.stats().sideFromCheck >= 1)) {
        return "兜底轮次与盘面冲突时没有改轮次（sideFromCheck=" +
            (driver.stats().sideFromCheck || 0) + "，impossibleInCheck=" +
            (driver.stats().impossibleInCheck || 0) + "）— 日志 " +
            noteText.filter(function (t) { return t.indexOf("被将") >= 0; }).slice(-2).join(" / ");
    }
    const rel = driver.lastReliable();
    if (!rel || rel.side !== "b") {
        return "改轮次后该轮到黑方，实际 " + (rel ? rel.side : "没有基准");
    }
    if (!rel.board || rel.board[gun] !== "C") {
        return "改轮次后这张盘该被采纳（(4,4) 是红炮），实际 " + (rel.board ? rel.board[gun] : "无");
    }
    return true;
});

check("驱动·重建基准：将帅照面（两边同时被将）⇒ 仍然否掉，绝不拿来算棋", function () {
    const observed = START_BOARD.slice();
    observed[3 * 9 + 4] = null;                // 黑卒 (4,3) 没了
    observed[6 * 9 + 4] = null;                // 红兵 (4,6) 也没了 ⇒ 中路只剩将帅
    let clock = 0;
    const driver = driverModule.createDriver({
        allowClick: true,                     // 同上：建议模式不会进恢复流程
        tap: function () { return { ok: true, clicks: [] }; },
        log: function () { },
        sleep: function () { },
        recognize: function () {
            return {
                ok: true, chars: observed.slice(), unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrBoard: function () {
            return {
                ok: true, chars: observed.slice(),
                glyphs: new Array(90), sides: new Array(90), scores: new Array(90), hits: 0
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { clock += 1000; return clock; },
        config: {
            movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 999999,
            unknownTurnWatchdogMs: 4000, recoverRetryMs: 1000
        }
    });
    driver.start();
    driver.tick();
    for (let i = 0; i < 20; i++) {
        driver.tick();
    }
    if (!(driver.stats().impossibleInCheck >= 1)) {
        return "将帅照面的盘没被不变量拦下（impossibleInCheck=" +
            (driver.stats().impossibleInCheck || 0) + "）";
    }
    if ((driver.stats().sideFromCheck || 0) !== 0) {
        return "两边同时被将还改了轮次（sideFromCheck=" + driver.stats().sideFromCheck + "）";
    }
    const rel = driver.lastReliable();
    if (rel && rel.board && rel.board[3 * 9 + 4] === null) {
        return "将帅照面的盘被采纳成了基准（(4,3) 已成空）";
    }
    return true;
});

check("驱动·未知格挡住轮次：兜底门槛提前到 2 帧（别干等 4s 看门狗 → 再烧 5s 恢复）（真机 now39 占 25% 时间）", function () {
    // now39 实测：8 段恢复全是 `轮次未知超时（看门狗）`，触发链条是
    //   1 个格子读不出来 ⇒ sideFor 直接 return null ⇒ 看门狗 4s ⇒ 重新定位/观察 ~5s。
    // 而「顽固未知 → 按上一张可靠盘面兜（未知=没变）」要 **4 帧**（≈4.8s）—— 看门狗总是先动手。
    // 收紧点：**只在那格确实已经把轮次挡住**（上一帧 side 也是 null）时，门槛降到 2 帧；
    //   正常节奏一律仍按 4 帧（now33 的教训：无条件提前填值更差）。
    // 夹具要点：得让「已知事实补齐（F1/F2）」都补不出来，才走得到这条兜底 ——
    //   · F1 要求「除未知格外全都与基准一致」⇒ 让**红兵真的走一步**（(4,6)→(4,5)）把它打掉；
    //   · F2 要求「未知格正好是那唯一着法改动的格子」⇒ 未知格放在不相干的 (7,2) 上把它打掉。
    const idx = 2 * 9 + 7;                     // (7,2) 那门黑炮：这一帧读不出来
    let phase = 0;                             // 0 = 正常帧；≥1 = 那一格读成 '?'，且红兵已走 (4,6)→(4,5)
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function () { },
        sleep: function () { },
        recognize: function () {
            const chars = START_BOARD.slice();
            if (phase >= 1) {
                chars[idx] = "?";
                chars[6 * 9 + 4] = null;       // 红兵 (4,6) 走掉
                chars[5 * 9 + 4] = "P";        // 落到 (4,5)
            }
            let unknown = 0;
            for (let i = 0; i < 90; i++) {
                if (chars[i] === "?") {
                    unknown += 1;
                }
            }
            return {
                ok: true, chars: chars, unknownCells: unknown, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrCell: function () { return null; },   // 找色 + OCR 也没证据 ⇒ 只能走「按可靠盘面兜」
        ocrBoard: function () { return { ok: false, reason: "自检：本用例只测兜底门槛" }; },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 999999, ocrCooldownMs: 0 }
    });
    driver.start();
    driver.tick();                             // 基准
    driver.assertTurn();
    phase = 1;
    driver.tick();                             // 第 1 帧未知：还没「被挡住」（上一帧轮次是好的）⇒ 不提前兜
    if ((driver.stats().unknownFilledFromReliable || 0) !== 0) {
        return "第 1 帧就提前兜了（正常节奏不该动，now33 的教训）";
    }
    phase = 2;
    driver.tick();                             // 第 2 帧：轮次已被挡住 ⇒ 门槛降到 2 帧 ⇒ 兜底生效
    if (!((driver.stats().unknownFilledFromReliable || 0) >= 1)) {
        return "第 2 帧没有兜底（unknownFilledFromReliable=" +
            (driver.stats().unknownFilledFromReliable || 0) + "）";
    }
    phase = 3;
    const third = driver.tick();               // 第 3 帧：粘住 ⇒ 那一帧就是完整的；跨帧佐证也满了
    if (!third || third.side !== "b") {
        return "第 3 帧应当推出「轮到黑方」（红兵已走），实际 " + (third ? third.side : "无结果") +
            " — 日志 unknownFilledFromReliable=" + (driver.stats().unknownFilledFromReliable || 0);
    }
    const seen = driver.lastBoard();
    if (seen && seen.indexOf("?") >= 0) {
        return "兜底之后仍有未知格（" + seen.join("") + "）";
    }
    return true;
});

check("驱动·兜底填充不许顶超兵种上限（真机 now60：黑炮被兜成 3 枚 ⇒ 幻影误清真落点 ⇒ 轮次死锁）", function () {
    // 真机 2026-09-21 now60：对方炮 (7,2)→(4,2) 平中；起点格 (7,2) 被游戏「上一步星光标记」的
    // 粉白辉光压住 20s+ 读不出（面板侧已改为按**墨迹密度**判空，见 inkLooksEmpty），
    // 而驱动这边把它兜成旧值 c ⇒ **黑炮 3 枚**（不可能盘面）⇒ 幻影去清真落点 (4,2)c、
    //（那格有墨迹）又被收编 ⇒ 轮次永远停在「轮到对方」，整局不动。
    // 夹具：观测 = 开局 + 一枚「谁也解释不了」的 c 落在 (4,4)（无合法单步能到） + (7,2) 读不出。
    // 此时 c 已经 2 枚（(1,2)+(4,4)），兜底再填 (7,2)=c 就是第 3 枚 ⇒ 必须被硬校验驳回。
    const logs = [];
    let phase = 0;
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function (line) { logs.push(String(line)); },
        sleep: function () { },
        recognize: function () {
            const chars = START_BOARD.slice();
            if (phase >= 1) {
                chars[4 * 9 + 4] = "c";        // 多出来的一枚黑炮（无合法单步解释得了）
                chars[2 * 9 + 7] = "?";        // 起点格 (7,2) 读不出来
            }
            let unknown = 0;
            for (let i = 0; i < 90; i++) {
                if (chars[i] === "?") {
                    unknown += 1;
                }
            }
            return {
                ok: true, chars: chars, unknownCells: unknown, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrCell: function () { return null; },   // OCR+找色也没证据 ⇒ 只能走「按可靠盘面兜」
        ocrBoard: function () { return { ok: false, reason: "自检：本用例只测上限驳回" }; },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 999999, ocrCooldownMs: 0 }
    });
    driver.start();
    driver.tick();                             // 基准（干净开局：(7,2)=c、(4,4)=空）
    driver.assertTurn();
    phase = 1;
    for (let i = 0; i < 6; i++) {
        driver.tick();
    }
    const seen = driver.lastBoard() || [];
    let cCount = 0;
    for (let i = 0; i < seen.length; i++) {
        if (seen[i] === "c") {
            cCount += 1;
        }
    }
    if (cCount > 2) {
        return "黑炮被兜成 " + cCount + " 枚（不可能盘面）：" + seen.join("");
    }
    if ((driver.stats().unknownFillRejected || 0) < 1) {
        return "没有观察到「兵种上限驳回」（unknownFillRejected=" +
            (driver.stats().unknownFillRejected || 0) + "）——夹具前提失效？";
    }
    if ((driver.stats().unknownFilledFromReliable || 0) !== 0) {
        return "兜底居然填了（会把黑炮变 3 枚）：unknownFilledFromReliable=" +
            driver.stats().unknownFilledFromReliable;
    }
    let hit = false;
    for (let i = 0; i < logs.length && !hit; i++) {
        hit = logs[i].indexOf("兵种上限驳回") >= 0;
    }
    if (!hit) {
        return "日志里没有「兵种上限驳回」行";
    }
    return true;
});

check("状态机·等对方长时间没动 ⇒ 自动发 DECLARE_TURN 翻轮次（真机 now62：对方已走完被收编成基准修正 ⇒ 163s 死等）", function () {
    // 真机 2026-09-21 凌晨：b0c2 确认 → 轮到对方 → 对方那步的新落子被幻影逻辑收编进基准 ⇒
    // 差分永远「变化 0 格」⇒ 空闲警告从 20s 刷到 163s+ 直到循环到点（外行看像在等对手想棋）。
    // 修法：等过 opponentIdleDeclareMs 就发 DECLARE_TURN（驱动重立基准+翻轮次，与点「轮到我」同路）。
    const h = harness({});
    h.dispatch({ type: "START" });
    h.dispatch({ type: "READY", modelVersion: "self-check-idle-declare" });
    h.dispatch({ type: "GEOMETRY", geometryVersion: 4, trusted: true });
    h.dispatch(h.snapshotEvent(START_BOARD, "b"));      // 轮到对方
    h.dispatch(h.snapshotEvent(START_BOARD, "b"));      // 画面没变 → 开始记空闲
    const idleDefaults = machineModule.DEFAULTS;
    h.clock.value += idleDefaults.opponentIdleWarnMs + 800;   // 只过「警告」门槛（别到判反门槛）
    h.dispatch(h.snapshotEvent(START_BOARD, "b"));
    if (String(h.machine.reason()).indexOf("判反") < 0) {
        return "过了警告门槛还没出现「多半是轮次判反」提示：" + h.machine.reason();
    }
    const declaresBefore = h.machine.counters().idleTurnDeclares || 0;
    h.clock.value += (idleDefaults.opponentIdleDeclareMs - idleDefaults.opponentIdleWarnMs) + 2000;   // 过判反门槛
    const out = h.dispatch(h.snapshotEvent(START_BOARD, "b"));
    const types = out.effects.map(function (e) { return e.type; });
    if (types.indexOf("DECLARE_TURN") < 0) {
        return "超时没发 DECLARE_TURN（effects=" + types.join(",") + "，状态 " +
            h.machine.state() + "｜" + h.machine.reason() + "）";
    }
    if ((h.machine.counters().idleTurnDeclares || 0) !== declaresBefore + 1) {
        return "idleTurnDeclares 计数没涨（" + declaresBefore + " → " +
            (h.machine.counters().idleTurnDeclares || 0) + "）";
    }
    return true;
});

check("状态机·暂停是「粘」的：暂停后新的观测不许把状态偷偷拉回 WAIT_TURN / THINKING（真机 now37 炮吃将循环 20 次）", function () {
    // 真机铁证（now37，21:38–21:39）：暂停之后面板的循环仍在 tick、观察照常，
    // 而 onSnapshot 原来**没有 PAUSED 闸门** —— 一条「轮次未知」的观测把状态 setState(WAIT_TURN)
    // 拽出暂停，接着「轮到我方」的观测又推进 THINKING ⇒ 同一着法 e6e9 被重算/重试 20 次。
    const h = harness({});
    h.dispatch({ type: "START" });
    h.dispatch({ type: "READY", modelVersion: "self-check-sticky" });
    h.dispatch({ type: "GEOMETRY", geometryVersion: 7, trusted: true });
    h.dispatch(h.snapshotEvent(START_BOARD, "w"));            // 轮到我方 → THINKING
    h.dispatch({ type: "PAUSE", reason: "同一着法 e6e9 已试 4 次仍未确认落子" });
    if (h.machine.state() !== "PAUSED") {
        return "夹具前提不成立：没进 PAUSED（现在 " + h.machine.state() + "）";
    }
    const computesBefore = h.effectsOf("COMPUTE");
    const states = [];
    for (let i = 0; i < 3; i++) {
        h.dispatch(h.snapshotEvent(START_BOARD, null));       // 轮次未知：真机就是这一条把暂停拽掉的
        states.push(h.machine.state());
        h.dispatch(h.snapshotEvent(START_BOARD, "w"));        // 轮到我方
        states.push(h.machine.state());
    }
    if (h.machine.state() !== "PAUSED") {
        return "暂停被观测解除了（" + states.join("→") + "）—— 真机上表现为同一着法被反复重试";
    }
    if (h.effectsOf("COMPUTE") !== computesBefore) {
        return "暂停后还在发起算棋（COMPUTE " + computesBefore + "→" + h.effectsOf("COMPUTE") + " 次）";
    }
    // 显式 RESUME 仍然要能出来（闸门不能把恢复路一起堵死）
    h.dispatch({ type: "RESUME" });
    if (h.machine.state() !== "WAIT_STABLE") {
        return "RESUME 被闸门挡住了（现在 " + h.machine.state() + "）";
    }
    return true;
});

check("驱动·同字异色抖动：观测只差一格颜色 ⇒ 按基准纠正并算「没变化」，不许判成「轮次推不出来」（真机 now39）", function () {
    // 真机现场（now39，21:54:54 → 21:55:36，同一格 (4,6)，游戏给那枚棋画了高亮环）：
    //   模型在 c/C/? 之间跳、全盘 OCR 的像素颜色也跟着跳 ⇒ 差分每帧都看到「变了」。
    //   这一轮实测：8 段「轮次未知超时 → 恢复」×~5s = 占整轮 25% 的时间。
    // 期望（既有机制，本用例把它钉住）：**同兵种、只差颜色**的格 ⇒ 按基准纠正、判「局面没变化」，
    //   绝不当成「推不出来」—— 否则每 4s 看门狗一次、每局白烧几十秒。
    //   ⚠️ 别为这个另加「按住旧颜色」的机制：既有这条（1 格颜色读反 ⇒ 按基准改）已经覆盖；
    //      另加一层还会把真实换色（同兵种吃子）按住，反而改坏账本（本用例写完后专门撤销过一版）。
    const idx = 2 * 9 + 7;                     // (7,2) 那门黑炮：翻转它不会让 clearPhantoms 误清
    const seq = ["c", "C", "c", "C", "c", "C"];
    let phase = 0;
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function (t) { noteText.push(String(t)); },
        sleep: function () { },
        recognize: function () {
            const chars = START_BOARD.slice();
            chars[idx] = seq[Math.min(phase, seq.length - 1)];
            return {
                ok: true, chars: chars, unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrBoard: function () { return { ok: false, reason: "自检：本用例只测同字异色纠正" }; },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 0 }
    });
    driver.start();
    driver.tick();                             // 第 1 帧：c ⇒ 基准
    driver.assertTurn();
    const rel0 = driver.lastReliable();
    if (!rel0 || !rel0.board || rel0.board[idx] !== "c") {
        return "夹具前提不成立：基准那一格应当是 c，实际 " + (rel0 && rel0.board ? rel0.board[idx] : "无基准");
    }
    const unknownBefore = driver.stats().unknownTurn || 0;
    for (let i = 1; i < seq.length; i++) {
        phase = i;
        driver.tick();
        const seen = driver.lastBoard();
        if (!seen || seen[idx] !== "c") {
            return "第 " + i + " 帧（模型读 " + seq[i] + "）应当按基准纠正成 c，实际 " +
                (seen ? seen[idx] : "无观测") + " — 日志 " +
                noteText.filter(function (t) { return t.indexOf("按基准纠正") >= 0; }).slice(-1).join(" / ");
        }
    }
    if ((driver.stats().unknownTurn || 0) !== unknownBefore) {
        return "同字异色的抖动被误判成「轮次推不出来」（unknownTurn " + unknownBefore + "→" +
            driver.stats().unknownTurn + "）";
    }
    return true;
});

check("驱动·全盘 OCR 补正闸门：像素（找色）没给结论、或与像素矛盾时，永不写基准", function () {
    const idx = 89;
    const observed = START_BOARD.slice();
    observed[idx] = "R";                       // 模型说红車
    const ocrChars = START_BOARD.slice();
    ocrChars[idx] = "c";                       // 全盘 OCR 想改成黑炮（= 翻色）
    const gate = { side: "UNKNOWN", score: 0.9 };
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function () { },
        sleep: function () { },
        recognize: function () {
            return {
                ok: true, chars: observed.slice(), unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrBoard: function () {
            const glyphs = new Array(90);
            const sides = new Array(90);
            const scores = new Array(90);
            glyphs[idx] = "炮";
            sides[idx] = gate.side;
            scores[idx] = gate.score;
            return { ok: true, chars: ocrChars.slice(), glyphs: glyphs, sides: sides, scores: scores, hits: 1 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 0 }
    });
    driver.start();
    driver.tick();
    driver.assertTurn();
    for (let i = 0; i < 5; i++) {
        driver.tick();
    }
    if (driver.lastReliable().board[idx] !== "R") {
        return "找色没给结论（UNKNOWN）时不许翻色，实际 " + driver.lastReliable().board[idx];
    }
    if (!(driver.stats().ocrBoardBlocked >= 1)) {
        return "被驳回的格子要计数（体检用），实际 blocked=" + driver.stats().ocrBoardBlocked;
    }
    // 与像素结论**直接矛盾**（像素说红、补正想写成黑）也一样驳回
    gate.side = "RED";
    for (let i = 0; i < 5; i++) {
        driver.tick();
    }
    if (driver.lastReliable().board[idx] !== "R") {
        return "与找色结论矛盾时不许写（像素说红、却要补成黑炮），实际 " + driver.lastReliable().board[idx];
    }
    return true;
});

check("驱动·全盘 OCR 补正闸门：`?` 与低分字形永不写进盘面与基准，合法高分要连续 2 帧", function () {
    const idx = 89;
    const observed = START_BOARD.slice();
    observed[idx] = null;                      // 模型漏检：那格看起来是空的
    const fixture = { ch: "?", score: 0.9 };
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function () { },
        sleep: function () { },
        recognize: function () {
            return {
                ok: true, chars: observed.slice(), unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrBoard: function () {
            const chars = new Array(90);
            const glyphs = new Array(90);
            const sides = new Array(90);
            const scores = new Array(90);
            chars[idx] = fixture.ch;
            glyphs[idx] = "炮";
            sides[idx] = "BLACK";
            scores[idx] = fixture.score;
            return { ok: true, chars: chars, glyphs: glyphs, sides: sides, scores: scores, hits: 1 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 0 }
    });
    driver.start();
    driver.tick();
    driver.assertTurn();
    // ① OCR 回的是 `?` ⇒ 永远不写（以前会被当成“有字”写进基准）
    for (let i = 0; i < 4; i++) {
        driver.tick();
    }
    if (driver.lastReliable().board[idx] !== null) {
        return "`?` 不许写进盘面/基准，实际 " + driver.lastReliable().board[idx];
    }
    // ② 合法字但分数 0.2 ⇒ 仍然不写
    fixture.ch = "c";
    fixture.score = 0.2;
    for (let i = 0; i < 4; i++) {
        driver.tick();
    }
    if (driver.lastReliable().board[idx] !== null) {
        return "低于 ocrPatchMinScore 的字形不许采纳，实际 " + driver.lastReliable().board[idx];
    }
    // ③ 分数够了：连续 2 帧才写
    fixture.score = 0.9;
    driver.tick();
    if (driver.lastReliable().board[idx] !== null) {
        return "第 1 帧不该写基准，实际 " + driver.lastReliable().board[idx];
    }
    driver.tick();
    if (driver.lastReliable().board[idx] !== "c") {
        return "连续 2 帧一致后应当补成 c，实际 " + driver.lastReliable().board[idx];
    }
    return true;
});

check("几何体检：整行偏一格的「假几何」必须被认出来（真机 origin.y 590 vs 真实 690）", function () {
    const sanity = require("../src/vision/grid-sanity.js");
    // 真机当时的行分布：黑方底线跑到第 1 行（8 枚）、第 0 行空的；红方底线掉到网格外
    const shifted = sanity.rowsOf([
        { row: 1 }, { row: 1 }, { row: 1 }, { row: 1 }, { row: 1 }, { row: 1 }, { row: 1 }, { row: 1 },
        { row: 3 }, { row: 3 }, { row: 3 }, { row: 3 }, { row: 3 },
        { row: 4 }, { row: 4 }, { row: 4 }, { row: 4 },
        { row: 6 }, { row: 6 }, { row: 6 }, { row: 6 },
        { row: 7 }, { row: 7 }
    ]);
    const verdict = sanity.suspect(shifted);
    if (verdict.ok) {
        return "整行偏的网格必须判为可疑（行分布 " + shifted.join(",") + "）";
    }
    // 正常开局：两极端行各 9 枚 ⇒ 放行
    const normal = sanity.rowsOf([
        { row: 0 }, { row: 0 }, { row: 0 }, { row: 0 }, { row: 0 }, { row: 0 }, { row: 0 }, { row: 0 }, { row: 0 },
        { row: 2 }, { row: 2 }, { row: 3 }, { row: 3 }, { row: 3 }, { row: 3 }, { row: 3 },
        { row: 6 }, { row: 6 }, { row: 6 }, { row: 6 }, { row: 6 }, { row: 7 }, { row: 7 },
        { row: 9 }, { row: 9 }, { row: 9 }, { row: 9 }, { row: 9 }, { row: 9 }, { row: 9 }, { row: 9 }, { row: 9 }
    ]);
    if (!sanity.suspect(normal).ok) {
        return "正常开局不该被判可疑（行分布 " + normal.join(",") + "）";
    }
    // 下边也偏（第 9 行空、第 8 行挤满）同样要认出来
    const shiftedDown = sanity.rowsOf([
        { row: 1 }, { row: 1 }, { row: 1 }, { row: 1 }, { row: 1 }, { row: 1 },
        { row: 8 }, { row: 8 }, { row: 8 }, { row: 8 }, { row: 8 }, { row: 8 }, { row: 8 }
    ]);
    if (sanity.suspect(shiftedDown).ok) {
        return "底边偏（第 9 行空、第 8 行 7 个）也该判可疑";
    }
    // ★ now49 真机（2026-09-20 深夜）：中残局里底线只剩 5~7 枚 ⇒ 整行偏的签名变成
    //   「顶行 0 + 第 1 行 5 + **底行也只剩 1**」（真实的底线整排被挤到网格外）；
    //   旧规则 ≥6 的门槛刚好漏掉（0,5,1,2,3,1,1,4,1,1 被放行 ⇒ 校准收下偏一行的网格）。
    const midGameShift = sanity.rowsOf([
        { row: 1 }, { row: 1 }, { row: 1 }, { row: 1 }, { row: 1 },
        { row: 2 },
        { row: 3 }, { row: 3 },
        { row: 4 }, { row: 4 }, { row: 4 },
        { row: 5 },
        { row: 6 },
        { row: 7 }, { row: 7 }, { row: 7 }, { row: 7 },
        { row: 8 },
        { row: 9 }
    ]);
    const midVerdict = sanity.suspect(midGameShift);
    if (midVerdict.ok || midVerdict.rows.join(",") !== "0,5,1,2,3,1,1,4,1,1") {
        return "中残局整行偏（0,5,1,2,3,1,1,4,1,1）也要判可疑";
    }
    // 反向保护：真实的「顶行真空了」不许误判 —— 红方底线（第 9 行）完好就是最好的反证
    const topEmptyLegit = sanity.rowsOf([
        { row: 1 }, { row: 1 }, { row: 1 }, { row: 1 }, { row: 1 },
        { row: 2 },
        { row: 3 }, { row: 3 },
        { row: 4 }, { row: 4 },
        { row: 6 }, { row: 6 }, { row: 6 },
        { row: 8 }, { row: 8 },
        { row: 9 }, { row: 9 }, { row: 9 }, { row: 9 }, { row: 9 }, { row: 9 }
    ]);
    if (!sanity.suspect(topEmptyLegit).ok) {
        return "顶行真空、但底线 6 枚完好（合法局面）不该判可疑";
    }
    return true;
});

check("几何体检：平移候选按「±整行、±半行」排序（now47：锚错一行时先平移救回，不再直接回退缓存框）", function () {
    const sanity = require("../src/vision/grid-sanity.js");
    const list = sanity.shiftCandidates({ stepX: 115, stepY: 114 });
    if (!list || list.length !== 4) {
        return "应当给 4 个候选，实际 " + (list ? list.length : "无");
    }
    const flat = list.map(function (p) { return p.join(":"); }).join(" ");
    if (flat !== "0:114 0:-114 0:57 0:-57") {
        return "候选次序/数值不对：" + flat;
    }
    const odd = sanity.shiftCandidates({ stepY: 115 });    // 115/2=57.5 ⇒ 四舍五入 58
    if (odd[2][1] !== 58 || odd[3][1] !== -58) {
        return "半行应当四舍五入：" + JSON.stringify(odd);
    }
    return true;
});

/**
 * 真机回归（2026-09-20 K40，用户现场）：`b2c2`（炮 (1,7)→(2,7)）发出后，确认那几帧里
 * **起点与落点同时是 `?`**（棋子飞在空中/被高亮环盖住），而盘面其余格子与**落子前**完全一致。
 *
 * ❌ 旧行为：「已知事实补齐」的 F1（与上一张可靠盘面一致）照**落子前**的基准把两格都补成 C
 *    ⇒ 基准变成 `…1CC4C1…`（两枚炮都在）⇒ 之后每一帧都「少一个子且解释不了」
 *    ⇒ 轮次永远推不出来 ⇒ 永久 RECOVERING（用户看到的“卡住”）。
 *
 * 修法：本手在飞时（`activeOwnMove`），F1 比对/回填用的是**已走一手的基准** ——
 * 起点补空、落点补走过去的那个子。
 */
check("驱动：我方这手在飞时，起点格不许被补回棋子（幻影炮回归 2026-09-20）", function () {
    const fromIndex = transition.squareIndex(OWN_MOVE.iccs, 0);
    const destIndex = transition.squareIndex(OWN_MOVE.iccs, 1);
    if (fromIndex < 0 || destIndex < 0) {
        return "夹具着法取不到起/落点：" + OWN_MOVE.iccs;
    }
    const afterOwn = legal.applyMove(START_BOARD, OWN_MOVE).board;
    const stage = { clicked: false };
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: true,
        log: function (text) { noteText.push(String(text)); },
        sleep: function () { },
        recognize: function () {
            // 出手前：干净开局；出手后：起点+落点两个未知格，其余与落子前一致（真机那一帧）
            const cells = START_BOARD.slice();
            if (stage.clicked) {
                cells[fromIndex] = "?";
                cells[destIndex] = "?";
            }
            let unknown = 0;
            for (let i = 0; i < 90; i++) {
                if (cells[i] === "?") { unknown += 1; }
            }
            return {
                ok: true, chars: cells, unknownCells: unknown, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 };
        },
        tap: function (x, y, tapOptions) {
            // 一次出手 = 两次 tap；落点那次（kind="to"）才算真的出手了
            if (tapOptions && tapOptions.kind === "to") {
                stage.clicked = true;
            }
            return true;
        },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1 }
    });
    driver.start();
    driver.tick();
    driver.assertTurn();
    for (let i = 0; i < 8 && !stage.clicked; i++) {
        driver.tick();
    }
    if (!stage.clicked) {
        return "夹具没走到出手：状态 " + driver.machine.state() +
            "（" + driver.machine.reason() + "），日志 " + noteText.slice(-3).join(" / ");
    }
    for (let i = 0; i < 6; i++) {
        driver.tick();
    }
    const reliable = driver.lastReliable();
    if (!reliable || !reliable.board) {
        return "没有可靠基准";
    }
    if (reliable.board[fromIndex] !== null) {
        return "起点格被补回了一枚棋子（幻影 " + reliable.board[fromIndex] + "，应为空）—— 基准 " +
            reliable.board.join("") + "；日志 " + noteText.filter(function (t) { return t.indexOf("补齐") >= 0; }).slice(-2).join(" / ");
    }
    if (reliable.board[destIndex] !== afterOwn[destIndex]) {
        return "落点格不对：" + reliable.board[destIndex] + "，应为 " + afterOwn[destIndex];
    }
    return true;
});

/**
 * 真机回归（2026-09-20 深夜，now14 18:20:53）：模型把 (0,0) 那枚黑車的框偏到了 (1,0) ⇒
 * 观测里同一枚車占了**两格**（`rrbakab1r/…` vs 期望 `r1bakab1r/…`）⇒ 确认连刷 5 次
 * 「还有 N 个未知格（且看得清的格子对不上这一手）」→ 恢复（白烧 ~20s）。
 *
 * 判据：**棋子不会分身** —— 观测里某枚子的个数多于期望里的个数，多出来的那些格就是幻影，
 * 按期望置空。⚠️ 只在「多出的份数 == 候选格数」时清（多一格都不清，宁可交给恢复）；
 * 真走子不会触发（子走了起点会空 ⇒ 个数不变；吃子则是变少）。
 */
check("驱动：重复子（同一枚子在观测里占两格）要按期望清掉，别把确认卡死（真机 18:20 回归）", function () {
    const afterOwn = legal.applyMove(START_BOARD, OWN_MOVE).board;
    const ghost = 1 * 9 + 1;                 // 开局第 1 行是空的：幻影黑車就放这儿
    const unknownCell = 1 * 9 + 3;           // 另一个空格读成 '?'（看不清，不是棋子）
    const real = 0;                          // (0,0) 是真的黑車
    const stage = { clicked: false };
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: true,
        log: function (text) { noteText.push(String(text)); },
        sleep: function () { },
        recognize: function () {
            const cells = (stage.clicked ? afterOwn : START_BOARD).slice();
            if (stage.clicked) {
                cells[ghost] = cells[real];   // 框偏了：同一枚黑車出现在两格
                cells[unknownCell] = "?";    // 另一格看不清
            }
            let unknown = 0;
            for (let i = 0; i < 90; i++) {
                if (cells[i] === "?") {
                    unknown += 1;
                }
            }
            return {
                ok: true, chars: cells, unknownCells: unknown, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 };
        },
        tap: function (x, y, tapOptions) {
            if (tapOptions && tapOptions.kind === "to") {
                stage.clicked = true;
            }
            return true;
        },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1 }
    });
    driver.start();
    driver.tick();
    driver.assertTurn();
    for (let i = 0; i < 8 && !stage.clicked; i++) {
        driver.tick();
    }
    if (!stage.clicked) {
        return "夹具没走到出手：状态 " + driver.machine.state() +
            "（" + driver.machine.reason() + "），日志 " + noteText.slice(-3).join(" / ");
    }
    for (let i = 0; i < 6; i++) {
        driver.tick();
    }
    if (!(driver.stats().phantomCleared >= 1)) {
        return "幻影没被清掉（phantomCleared=" + driver.stats().phantomCleared + "）— 日志 " +
            noteText.filter(function (t) { return t.indexOf("幻影") >= 0 || t.indexOf("确认") >= 0; })
                .slice(-3).join(" / ");
    }
    if (!(driver.stats().confirms >= 1)) {
        return "确认还是没成功（confirms=" + driver.stats().confirms + "）— 日志 " +
            noteText.filter(function (t) { return t.indexOf("确认") >= 0; }).slice(-3).join(" / ");
    }
    const seen = driver.lastBoard();
    if (seen && seen[ghost] !== null) {
        return "幻影格没清干净：" + seen[ghost];
    }
    // ⚠️ '?' 是「看不清」，不是棋子，也绝不能被幻影规则清成空
    if (seen && seen[unknownCell] === null) {
        return "'?' 被当成幻影清掉了（看不清 ≠ 没子）";
    }
    return true;
});

/**
 * 真机回归（2026-09-20 深夜，now24）：`清掉幻影（重复子）1 格（(4,2)c→空）` 在同一局里**连刷 8 次**
 * ——那格根本不是分身，是**我方子被高亮环读成了敌方兵种**（与 (2,3)/(8,5) 同一类误读，
 * 只是那两格有已知事实记录、这格没有）。清掉等于让我方那枚子凭空消失，下一帧又来一出。
 * 判据（本轮新增）：清之前问一句**像素（找色）阵营** —— 与观测相反就**不清也不猜**（改成我方子
 * 只会立刻又凑出我方那份的重复计数，只是换一出戏），交给恢复流程；没有证据才照旧清。
 */
check("驱动：幻影候选其实是我方子被读成敌色时，不许清掉（真机 now24 回归）", function () {
    const afterOwn = legal.applyMove(START_BOARD, OWN_MOVE).board;
    const ghost = 1 * 9 + 1;                 // 参照里这里是空的；观测却多出一枚「黑卒」
    const stage = { clicked: false };
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: true,
        log: function (text) { noteText.push(String(text)); },
        sleep: function () { },
        recognize: function () {
            const cells = (stage.clicked ? afterOwn : START_BOARD).slice();
            if (stage.clicked) {
                cells[ghost] = "p";          // 高亮环骗了模型：那格被读成黑卒（异色同字）
            }
            let unknown = 0;
            for (let i = 0; i < 90; i++) {
                if (cells[i] === "?") {
                    unknown += 1;
                }
            }
            return {
                ok: true, chars: cells, unknownCells: unknown, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrCell: function () {
            // 像素（找色）：那格的墨迹是**红**的 —— 与观测读到的黑卒相反
            return { ok: true, side: "RED", cls: "卒", char: "P", score: 0.9 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 };
        },
        tap: function (x, y, tapOptions) {
            if (tapOptions && tapOptions.kind === "to") {
                stage.clicked = true;
            }
            return true;
        },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrCooldownMs: 0 }
    });
    driver.start();
    driver.tick();
    driver.assertTurn();
    for (let i = 0; i < 8 && !stage.clicked; i++) {
        driver.tick();
    }
    if (!stage.clicked) {
        return "夹具没走到出手：状态 " + driver.machine.state() +
            "（" + driver.machine.reason() + "），日志 " + noteText.slice(-3).join(" / ");
    }
    for (let i = 0; i < 4; i++) {
        driver.tick();
    }
    if (!(driver.stats().phantomHeldBack >= 1)) {
        return "幻影候选没被像素证据拦下（phantomHeldBack=" +
            (driver.stats().phantomHeldBack || 0) + "）— 日志 " +
            noteText.filter(function (t) { return t.indexOf("幻影") >= 0; }).slice(-2).join(" / ");
    }
    const seen = driver.lastBoard();
    if (seen && seen[ghost] === null) {
        return "那格还是被清空了（应保持观测到的 p，交给恢复流程）";
    }
    return true;
});

/**
 * 真机回归（2026-09-24 12:48-12:57，用户「自己被将军了 还在走其他的」）：
 * 对方黑车走到底线 (3,9)、**贴着我方帅 (4,9) 将军**；驱动把它当成「重复子」连着清了 7 分钟
 * （`清掉幻影（重复子）1 格（(3,9)r→空）` 每帧一次）—— 盘面上永远没有将军 ⇒ 引擎一直走别的
 * （真实棋局里全都不合法，游戏全部拒收）⇒ 面板卡在「轮次推不出来/RECOVERING」。
 *
 * 判据（本轮新增，**不依赖证据**）：清一枚子不许改变我方帅的被将状态（含将帅照面）——
 * 拿掉前后不一样就不清，留给恢复/收编流程。收编那条路要求「那格真有墨迹」的证据，
 * 对黑车细笔画 + 单格证据限频的场合给不出来，所以必须有一条不看证据的底线。
 */
check("驱动：幻影候选正在将军我方帅 ⇒ 不许清（真机 2026-09-24「被将军还在走其他的」回归）", function () {
    const BASE = fen.parseFen("3k5/4a4/9/9/9/9/9/9/4A4/4K4").board;   // 黑将(3,0) 黑士(4,1) 我仕(4,8) 我帅(4,9)
    const own = legal.legalMoves(BASE, 4, 8, "w").filter(function (m) {
        return m.toCol === 5 && m.toRow === 9;
    })[0] || legal.legalMoves(BASE, 4, 8, "w")[0];
    const afterOwn = legal.applyMove(BASE, own).board;
    const stage = { clicked: false };
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: true,
        log: function (text) { noteText.push(String(text)); },
        sleep: function () { },
        recognize: function () {
            const cells = (stage.clicked ? afterOwn : BASE).slice();
            if (stage.clicked) {
                cells[9 * 9 + 3] = "r";   // (col3,row9)：黑车贴脸将军我方帅(4,9)
            }
            return {
                ok: true, chars: cells, unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: own.iccs, move: own, score: 1, depth: 1, ms: 1 };
        },
        tap: function (x, y, tapOptions) {
            if (tapOptions && tapOptions.kind === "to") {
                stage.clicked = true;
            }
            return true;
        },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrCooldownMs: 0 }
    });
    driver.start();
    driver.tick();
    driver.assertTurn();
    for (let i = 0; i < 8 && !stage.clicked; i++) {
        driver.tick();
    }
    if (!stage.clicked) {
        return "夹具没走到出手：状态 " + driver.machine.state() +
            "（" + driver.machine.reason() + "），日志 " + noteText.slice(-3).join(" / ");
    }
    for (let i = 0; i < 4; i++) {
        driver.tick();
    }
    const seen = driver.lastBoard();
    if (!seen || seen[9 * 9 + 3] === null) {
        return "将军的黑车被清掉了（应保留 r，实际 " +
            (seen ? JSON.stringify(seen[9 * 9 + 3]) : "无盘面") + "）— 日志 " +
            noteText.filter(function (t) { return t.indexOf("幻影") >= 0; }).slice(-2).join(" / ");
    }
    if (!(driver.stats().phantomHeldByCheck >= 1)) {
        return "没走「被将军不删」这条护栏（phantomHeldByCheck=" +
            (driver.stats().phantomHeldByCheck || 0) + "，phantomCleared=" +
            driver.stats().phantomCleared + "）— 日志 " +
            noteText.filter(function (t) { return t.indexOf("幻影") >= 0; }).slice(-2).join(" / ");
    }
    return true;
});

/**
 * 真机回归（2026-09-20 深夜，now20 卡了 19 次「轮次推不出来」）：
 * 我方炮 (2,3) 被模型误读成黑卒 ⇒ 卒的总数 +1；而对方那枚卒**从 (6,3) 走到了 (6,4)**。
 * 幻影规则把「多出来的那份」算在了 (6,4) 那枚**真卒**头上、把它清成空
 * ⇒ 盘面少了对方一枚卒 ⇒ 任何合法着法都解释不了 ⇒ 恢复 → 重建基准 → 再撞，**死循环**。
 *
 * 收紧：只有当参照里这枚子的**每一份都还在原位**（keep == ref）时，多出来的那份才可能是分身；
 * 有子移动过（keep < ref）就整类不动手。
 */
check("驱动：幻影规则不许把「移动过的子」当分身清掉（真机 now20 死循环回归）", function () {
    const movedFrom = 3 * 9 + 6;              // (col 6,row 3)：参照里那枚黑卒
    const movedTo = 4 * 9 + 6;                // (col 6,row 4)：它走到了这里
    const misread = 7 * 9 + 1;                // (col 1,row 7)：开局那门红炮，被误读成卒（把卒数顶成 +1）
    const observed = START_BOARD.slice();
    observed[movedFrom] = null;
    observed[movedTo] = "p";
    observed[misread] = "p";
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: false,
        log: function (text) { noteText.push(String(text)); },
        sleep: function () { },
        recognize: function () {
            return {
                ok: true, chars: observed.slice(), unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: null }; },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrBoardCooldownMs: 999999 }
    });
    driver.start();
    for (let i = 0; i < 5; i++) {
        driver.tick();
    }
    const seen = driver.lastBoard();
    if (seen && seen[movedTo] !== "p") {
        return "走过来的那枚卒被当成幻影清掉了（应为 p，实际 " + seen[movedTo] + "）— 日志 " +
            noteText.filter(function (t) { return t.indexOf("幻影") >= 0; }).slice(-2).join(" / ");
    }
    if (noteText.some(function (t) { return t.indexOf("清掉幻影") >= 0; })) {
        return "这一帧不该触发任何幻影清理 — 日志 " +
            noteText.filter(function (t) { return t.indexOf("幻影") >= 0; }).slice(-1).join(" / ");
    }
    return true;
});

/**
 * 真机回归（2026-09-20 深夜，now48）：`清掉幻影（重复子）1 格（(5,3)r→空）` 每帧一次、连刷 20+ 次 ——
 * 那格的黑車**是真的**（对方走过来了，但那一手没被推断采纳；基准里这枚子的另一份还在原位
 * ⇒ 计数恒定“多一份”）。每帧把真子擦掉 = 观测永远与真棋盘对不上，而且这枚子永远进不了基准
 *（它的走子永远推不出来）。
 *
 * 修法：同一格连着 `phantomAdoptStreak` 帧都「多出来」且**那格真有墨迹** ⇒ 收编进基准（不再清）。
 * 反向保护：证据说那格没墨迹（模型框漂移的假子）⇒ 照旧清，绝不收编（下一条用例守着）。
 */
check("驱动：幻影僵持 3 帧且那格真有墨迹 ⇒ 收编进基准而不是反复清掉（真机 now48 回归）", function () {
    const afterOwn = legal.applyMove(START_BOARD, OWN_MOVE).board;
    const ghost = 3 * 9 + 5;                  // (col 5,row 3)：开局是空位，观测里多出一枚黑車
    const stage = { clicked: false, ghostOn: false };
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: true,
        log: function (text) { noteText.push(String(text)); },
        sleep: function () { },
        recognize: function () {
            const cells = (stage.clicked ? afterOwn : START_BOARD).slice();
            if (stage.ghostOn) {
                cells[ghost] = "r";           // 对方那枚車真的走过来了（基准漏收）
            }
            return {
                ok: true, chars: cells, unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrCell: function () {
            // 那格真有墨迹（黑色）—— 不是模型框漂移的假子
            return {
                ok: true, side: "BLACK", cls: "車", char: "r", score: 0.9,
                ink: { red: 0, black: 600, area: 10000 }
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 };
        },
        tap: function (x, y, tapOptions) {
            if (tapOptions && tapOptions.kind === "to") {
                stage.clicked = true;
            }
            return true;
        },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrCooldownMs: 0 }
    });
    driver.start();
    driver.tick();
    driver.assertTurn();
    for (let i = 0; i < 8 && !stage.clicked; i++) {
        driver.tick();
    }
    if (!stage.clicked) {
        return "夹具没走到出手：状态 " + driver.machine.state() +
            "（" + driver.machine.reason() + "），日志 " + noteText.slice(-3).join(" / ");
    }
    for (let i = 0; i < 6; i++) {
        driver.tick();                        // 让确认走完、基准落定
    }
    stage.ghostOn = true;
    for (let i = 0; i < 6; i++) {
        driver.tick();
    }
    if (!(driver.stats().phantomAdopted >= 1)) {
        return "僵持到点没收编（phantomAdopted=" + (driver.stats().phantomAdopted || 0) +
            "，phantomCleared=" + driver.stats().phantomCleared + "）— 日志 " +
            noteText.filter(function (t) { return t.indexOf("幻影") >= 0; }).slice(-3).join(" / ");
    }
    const rel = driver.lastReliable();
    if (!rel || rel.board[ghost] !== "r") {
        return "收编后基准里还是没有那枚車：" + (rel ? String(rel.board[ghost]) : "没有基准");
    }
    const seen = driver.lastBoard();
    if (!seen || seen[ghost] !== "r") {
        return "收编之后那格还在被清（观测=" + (seen ? String(seen[ghost]) : "无") + "）";
    }
    return true;
});

check("驱动：幻影僵持但证据说那格**没墨迹** ⇒ 是框漂移的假子，照旧清、绝不收编（安全阀）", function () {
    const afterOwn = legal.applyMove(START_BOARD, OWN_MOVE).board;
    const ghost = 3 * 9 + 5;
    const stage = { clicked: false, ghostOn: false };
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: true,
        log: function (text) { noteText.push(String(text)); },
        sleep: function () { },
        recognize: function () {
            const cells = (stage.clicked ? afterOwn : START_BOARD).slice();
            if (stage.ghostOn) {
                cells[ghost] = "r";
            }
            return {
                ok: true, chars: cells, unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrCell: function () {
            // 找色说那格**没有墨迹**（密度全 0）⇒ 观测里的“車”是模型框漂移的假子
            return {
                ok: false, noText: true, score: 0,
                ink: { red: 0, black: 0, area: 10000 }
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 };
        },
        tap: function (x, y, tapOptions) {
            if (tapOptions && tapOptions.kind === "to") {
                stage.clicked = true;
            }
            return true;
        },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrCooldownMs: 0 }
    });
    driver.start();
    driver.tick();
    driver.assertTurn();
    for (let i = 0; i < 8 && !stage.clicked; i++) {
        driver.tick();
    }
    if (!stage.clicked) {
        return "夹具没走到出手：状态 " + driver.machine.state() +
            "（" + driver.machine.reason() + "），日志 " + noteText.slice(-3).join(" / ");
    }
    for (let i = 0; i < 6; i++) {
        driver.tick();
    }
    stage.ghostOn = true;
    for (let i = 0; i < 6; i++) {
        driver.tick();
    }
    if ((driver.stats().phantomAdopted || 0) !== 0) {
        return "假子被收编了（phantomAdopted=" + driver.stats().phantomAdopted +
            "）—— 证据说那格没墨迹";
    }
    if (!(driver.stats().phantomCleared >= 2)) {
        return "假子应当照旧被清（phantomCleared=" + driver.stats().phantomCleared + "）— 日志 " +
            noteText.filter(function (t) { return t.indexOf("幻影") >= 0; }).slice(-3).join(" / ");
    }
    const seen = driver.lastBoard();
    if (seen && seen[ghost] != null) {
        return "假子还留在观测里（" + seen[ghost] + "）";
    }
    return true;
});

/**
 * 真机回归（2026-09-24，K40）：**一次点击的确认被完整跑 2~3 次**（每次多烧一整轮全盘观测，~0.6s）。
 *
 * 根因在驱动的 `pump`：`handleEffect` 的返回值会被**调用方的 pump 再次入队**，而嵌套动作
 * （executeTicket / doVerify / compute…）自己也在调 pump ⇒ 同一组 effects（CLICK_RESULT 的
 * [VERIFY]）被内外两层各执行一遍。修法：pump 返回前把 effects 清空（每个 outcome 只执行一次）。
 * 09-24 早上用「同票据复用结论」盖过一次症状（只对 ROI 路径生效），去掉 OCR 后立刻复发。
 */
check("驱动：一次点击只确认一次（pump 嵌套不得重复执行 effects —— 真机 2~3 次确认回归）", function () {
    const afterOwn = legal.applyMove(START_BOARD, OWN_MOVE).board;
    const stage = { clicked: false };
    const noteText = [];
    const driver = driverModule.createDriver({
        allowClick: true,
        log: function (text) { noteText.push(String(text)); },
        sleep: function () { },
        recognize: function () {
            const cells = (stage.clicked ? afterOwn : START_BOARD).slice();
            return {
                ok: true, chars: cells, unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 };
        },
        tap: function (x, y, tapOptions) {
            if (tapOptions && tapOptions.kind === "to") {
                stage.clicked = true;
            }
            return true;
        },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrCooldownMs: 0 }
    });
    driver.start();
    driver.tick();
    driver.assertTurn();
    for (let i = 0; i < 8 && !stage.clicked; i++) {
        driver.tick();
    }
    if (!stage.clicked) {
        return "夹具没走到出手：状态 " + driver.machine.state() +
            "（" + driver.machine.reason() + "）｜日志 " + noteText.slice(-3).join(" / ");
    }
    for (let i = 0; i < 6; i++) {
        driver.tick();
    }
    const clicks = noteText.filter(function (t) { return t.indexOf("点击：") >= 0; });
    if (clicks.length !== 1) {
        return "应当只有一次点击，实际 " + clicks.length + " 次：" + clicks.join(" / ");
    }
    const confirms = noteText.filter(function (t) { return t.indexOf("确认：CONFIRMED") >= 0; });
    if (confirms.length !== 1) {
        return "一次点击应当只确认一次，实际 " + confirms.length + " 次：" + confirms.join(" / ");
    }
    return true;
});

/**
 * 真机回归（2026-09-20，K40 实拍 + 截图放大逐格核对）：**同兵种反吃被两处「纠色」抹掉证据**，
 * 整局钉死在 `UNRELIABLE：装饰容差下有 11 种应手解释，不唯一` 两分钟以上。
 *
 * 现场：
 *   ① 我方炮 i4i7 吃掉对方第二枚炮（中间隔一个卒当炮架）→ 落点 i7 登记进已知事实（C）；
 *   ② 对方**同一横线上的另一枚炮 b7×i7 反吃**：观测在 i7 读到的是 `c`（黑炮，**读得没错**），
 *      但补齐表第 ③ 支按「同字异色 = 红黑读反」写回 C，期望纠色块又按 `toLowerCase()` 相等翻成 C
 *      ⇒ 观测与期望只剩 b7 变空 ⇒ 看起来像「炮从 b7 走了、去哪儿没看见」
 *      ⇒ 装饰容差枚举出的 11 种解释永不唯一（那 11 种正是 b7 那门炮的全部合法落点）。
 *
 * 现在：同字异色连续 `cfg.colorDisputeStreak` 帧不变 → 按**原始读数**复议，拿到成功结论
 * （CONFIRMED / OPPONENT_REPLY）才采信，并把那一格的已知事实改写成原始读数
 * （不改写的话下一帧又被翻回去，僵持原样复发）。
 */
function recaptureFixture(mode) {
    const BASE_FEN = "1rb1kab2/4a4/nc4n1c/p3p3p/6p2/8C/P1P1P1P1P/4C4/7r1/RNBAKABNR w - - 0 1";
    const parsed = fen.parseFen(BASE_FEN);
    if (!parsed || !parsed.ok) {
        return "夹具 FEN 解析失败：" + (parsed && parsed.reason);
    }
    const baseBoard = parsed.board;
    const ownMove = legal.allLegalMoves(baseBoard, "w").filter(function (m) { return m.iccs === "i4i7"; })[0];
    if (!ownMove) {
        return "夹具里 i4i7（我方炮吃对方炮）不是合法着法";
    }
    const afterOwn = legal.applyMove(baseBoard, ownMove).board;
    const replyMove = legal.allLegalMoves(afterOwn, "b").filter(function (m) { return m.iccs === "b7i7"; })[0];
    if (!replyMove) {
        return "夹具里 b7i7（对方另一枚炮反吃）不是合法应手";
    }
    const afterReply = legal.applyMove(afterOwn, replyMove).board;
    const b7 = 2 * 9 + 1;      // (1,2)：对方另一枚炮
    const i7 = 2 * 9 + 8;      // (8,2)：我方刚落子那一格
    if (afterReply[b7] !== null || afterReply[i7] !== "c") {
        return "夹具结果不对：b7=" + afterReply[b7] + " i7=" + afterReply[i7];
    }
    const stage = { clicked: false, frames: 0 };
    const noteText = [];
    /**
     * 自检专用「刹车」：拿到复议结论之后就把这个 tick 打断。
     *
     * 为什么需要：自检里帧是**瞬时**的（没有采集/解板的等待），状态机会在一个 tick 内
     * 反复「观察 → 确认」，直到把帧放完；真机上每帧 ~1.5s 且局面会继续变，不会出现这种事。
     * 驱动对动作异常有 try/catch（会记一条「动作 … 失败」），所以这里抛异常是安全的。
     */
    let brakeArmed = false;
    const driver = driverModule.createDriver({
        allowClick: true,
        log: function (text) {
            const line = String(text);
            noteText.push(line);
            if (!brakeArmed && line.indexOf("可靠基准已刷新") >= 0 &&
                line.indexOf("OPPONENT_REPLY") >= 0 && line.indexOf("轮到我方") >= 0) {
                // ⚠️ 必须等到**这一行**（应手已佐证、基准已推进）再刹：
                //   刹早了会把「采纳复议」那个动作本身打断，计数不涨、断言假失败。
                brakeArmed = true;
            }
            if (brakeArmed) {
                throw new Error("__SELFCHECK_BRAKE__");
            }
        },
        sleep: function () { },
        recognize: function () {
            // ⚠️ 帧上限：自检里帧是**瞬时**的，状态机会在一个 tick 内反复「观察→确认」；
            //   真机上每帧 ~1.5s、局面会继续变，所以不存在这个问题。放完就报错，逼它停下来。
            stage.frames += 1;
            if (stage.frames > 30) {
                return { ok: false, reason: "自检：脚本帧已放完" };
            }
            // 真值帧：对方已经反吃回来了。模型**读对了**（i7 = c）——
            // 出错的是驱动内部那两处「纠色」，这正是回归点。
            const cells = (stage.clicked ? afterReply : baseBoard).slice();
            if (mode === "misread" && stage.clicked) {
                // 真机第二局现场：反吃过来的那枚**黑炮被直接读成我方颜色**（原始读数 == 期望）
                // ⇒ 「同字异色」分歧根本形不成，只能靠「对方吃在我方落点」的假设解释。
                cells[i7] = "C";
            }
            return {
                ok: true, chars: cells, unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            // ⚠️ 出手之后不再提供着法：否则状态机会拿旧着法继续规划（自检里会空转）。
            //   这一条用例只关心「反吃那帧能不能被认下来」。
            if (stage.clicked) {
                return { ok: true, iccs: null };
            }
            return { ok: true, iccs: ownMove.iccs, move: ownMove, score: 0, depth: 1, ms: 1 };
        },
        tap: function (x, y, tapOptions) {
            if (tapOptions && tapOptions.kind === "to") {
                stage.clicked = true;
            }
            return { ok: true, method: "self-check-tap" };
        },
        screen: function () { return { width: 1500, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1 }
    });
    driver.start();
    /** 带刹车的 tick：刹车只用于自检（帧瞬时 ⇒ 状态机会空转），真机上不存在 */
    const tick = function () {
        try {
            driver.tick();
        } catch (error) {
            if (String(error && error.message).indexOf("__SELFCHECK_BRAKE__") >= 0) {
                return false;
            }
            throw error;
        }
        return true;
    };
    tick();
    driver.assertTurn();
    for (let i = 0; i < 10 && !stage.clicked; i++) {
        if (!tick()) {
            break;
        }
    }
    if (!stage.clicked) {
        return "夹具没走到出手：状态 " + driver.machine.state() +
            "（" + driver.machine.reason() + "），日志 " + noteText.slice(-3).join(" / ");
    }
    for (let i = 0; i < 14; i++) {
        if (!tick()) {
            break;
        }
    }
    const stats = driver.stats();
    if (stats.colorDisputeAdopted < 1) {
        return "「同字异色」复议没有生效（adopted=" + stats.colorDisputeAdopted +
            "，确认 " + stats.confirms + "，状态 " + driver.machine.state() + "：" + driver.machine.reason() +
            "）—— 日志 " + noteText.filter(function (t) {
                return t.indexOf("同字异色") >= 0 || t.indexOf("不唯一") >= 0;
            }).slice(-2).join(" / ");
    }
    const reliable = driver.lastReliable();
    if (!reliable || !reliable.board) {
        return "没有可靠基准";
    }
    if (reliable.board[b7] !== null) {
        return "b7 应当是空的（那枚炮已经反吃过去），实际 " + reliable.board[b7];
    }
    if (reliable.board[i7] !== "c") {
        return "i7 应当是黑炮 c（对方反吃），实际 " + reliable.board[i7] +
            "（若为 C 就是又被「同字异色」纠回了我方子）—— 基准 " + reliable.board.join("");
    }
    return true;
}

check("驱动：同兵种反吃 · 目的地读成敌方色（同字异色僵持）要能按原始读数复议认下来（真机回归 2026-09-20）", function () {
    return recaptureFixture("colordispute");
});

check("驱动：同兵种反吃 · 目的地被读成我方色（盘面僵持）也要能认下来（真机第二局现场）", function () {
    return recaptureFixture("misread");
});

/**
 * 真机回归（auto35.log，2026-09-14）：确认阶段连刷 **54 次**
 * `UNRELIABLE：装饰容差下有 N 种应手解释，不唯一`，一手棋再也走不下去。
 *
 * 时序（日志逐行核对过）：
 *   ① 我方第一手点击 → 落点被高亮环读成 '?' → CONFIRMED（落点登记进已知事实表）
 *   ② 对方应手**正常可见**那一帧 → 轮次推断认出它 → 它的落点也登记进表（下标 22 = (4,2)）
 *   ③ 我方第二手点击 → 落点被读成 '?' → CONFIRMED
 *      ❌ 旧代码在这一步把**整张表**按周期作废（`verifyCycle + 1`），连同 ② 那条一起丢掉
 *   ④ 之后每一帧里 ② 那个子**整格漏检（读成空）** → 盘面永远差一格 → 没有任何合法着法
 *      能解释 → 核对给不出结论 / 轮次推不出来
 *
 * 现在：作废**按棋子是否真的离开**判定（`addPatch` 的 cycle 不再是唯一依据），
 * 我方确认不再顺手丢掉别人那格 → ④ 被补齐 → 轮次推断照常成功。
 */
check("驱动：我方确认不许作废「对方刚落点」的补齐记录（真机 54 次卡死回归）", function () {
    const replyDest = transition.squareIndex(REPLY_MOVE.iccs, 1);
    const own2 = legal.allLegalMoves(AFTER_REPLY_BOARD, "w")[0];
    const own2Dest = transition.squareIndex(own2.iccs, 1);
    const afterOwn2 = legal.applyMove(AFTER_REPLY_BOARD, own2).board;
    if (replyDest == null || own2Dest == null) {
        return "夹具着法取不到落点：" + JSON.stringify({ reply: REPLY_MOVE.iccs, own2: own2 && own2.iccs });
    }

    const stage = { at: 0, clicks: 0, analyzes: 0 };
    const noteText = [];
    const board = function () {
        if (stage.at === 0) {
            return START_BOARD.slice();
        }
        if (stage.at === 1) {
            const one = AFTER_BOARD.slice();
            one[transition.squareIndex(OWN_MOVE.iccs, 1)] = "?";
            return one;
        }
        if (stage.at === 2) {
            return AFTER_REPLY_BOARD.slice();
        }
        if (stage.at === 3) {
            const three = afterOwn2.slice();
            three[own2Dest] = "?";
            return three;
        }
        // ★ 回归帧：我方第二手已成立，但**对方那个子整格漏检**
        const four = afterOwn2.slice();
        four[replyDest] = null;
        return four;
    };
    const countUnknown = function (cells) {
        let unknown = 0;
        for (let i = 0; i < 90; i++) {
            if (cells[i] === "?") {
                unknown += 1;
            }
        }
        return unknown;
    };

    const driver = driverModule.createDriver({
        allowClick: true,
        log: function (text) { noteText.push(text); },
        sleep: function () { },
        recognize: function () {
            const cells = board();
            return {
                ok: true, chars: cells, unknownCells: countUnknown(cells), conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            stage.analyzes += 1;
            const move = stage.analyzes === 1 ? OWN_MOVE : own2;
            return { ok: true, iccs: move.iccs, move: move, score: 0, depth: 1, ms: 1 };
        },
        tap: function (x, y, tapOptions) {
            // ⚠️ 一次出手 = 两次 tap（起手 + 落点），只在**落点那次**推进脚本阶段
            if (tapOptions && tapOptions.kind === "to") {
                stage.clicks += 1;
                stage.at = stage.clicks === 1 ? 1 : 3;
            }
            return { ok: true, method: "self-check-tap" };
        },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, attemptLimitPerMove: 3, attemptLimitPerPosition: 5 }
    });
    driver.start();

    // 跑到「我方两手都确认成功」为止（阶段推进由 tap 回调负责）
    for (let i = 0; i < 24 && stage.at < 4; i++) {
        driver.tick();
        if (stage.at === 1 && driver.stats().confirms >= 1) {
            stage.at = 2;
        }
        if (stage.at === 3 && driver.stats().confirms >= 2) {
            stage.at = 4;
        }
    }
    if (stage.at !== 4) {
        return "没能走到回归帧（阶段 " + stage.at + "，确认 " + driver.stats().confirms +
            "，状态 " + driver.machine.state() + "：" + driver.machine.reason() + "）";
    }

    // 回归帧：必须被「已知事实补齐」补回去 → 轮次推理照常给出「轮到对方」
    let lastResult = null;
    for (let i = 0; i < 4; i++) {
        lastResult = driver.tick();
    }
    const stats = driver.stats();
    if (stats.unknownTurn !== 0) {
        return "轮次推不出来 " + stats.unknownTurn + " 次（补齐记录被作废了）：" +
            noteText.filter(function (text) { return text.indexOf("轮次推不出来") >= 0; }).slice(-1)[0];
    }
    if (!lastResult || lastResult.side !== "b") {
        return "回归帧里轮次应当是 b（轮到对方），实际 " + (lastResult && lastResult.side);
    }
    if (driver.machine.state() !== "WAIT_TURN") {
        return "应当稳定在 WAIT_TURN（等对方落子），实际 " + driver.machine.state() +
            "：" + driver.machine.reason();
    }
    return true;
});

/**
 * 真机回归（r1.log，2026-09-14 K40）：我方第二手确认成功之后，每一帧都「轮次推不出来（UNKNOWN）」。
 *
 * 现场三格伪影（日志原始读数）：
 *   · 我方刚落子那格（带高亮环）被读成 **P**（同色不同兵种）—— 这一格记录在表里
 *   · 对方刚落子那格（带高亮环）**整格漏检**（读成空）—— 这一格记录也在表里
 *   · 另有一格只是红黑读反（P → p）
 *
 * 根因（离线回放 diag-patch-life2.js 复现）：补齐表第 ④ 支写着「换成异色 → 丢掉记录」，
 * 而**这台模型会把同一格在红黑之间抖**（实测 `C` ↔ `c` 反复出现）。于是：
 *   一帧「同字异色」就足够把我方落点那格的记录丢掉 → 后面同色读错的帧再没人补
 *   → 盘面永远差一格 → 没有任何合法着法能解释 → 看门狗 → 整轮停住。
 *
 * 现在：**同字异色不再作废记录**（按记录补齐并留计数），异色且不同字才算「真的被吃走」。
 */
check("驱动：同字异色抖动不许丢掉「已知事实」记录（真机每帧推不出回归）", function () {
    const replyDest = transition.squareIndex(REPLY_MOVE.iccs, 1);
    const own2 = legal.allLegalMoves(AFTER_REPLY_BOARD, "w")[0];
    const own2Dest = transition.squareIndex(own2.iccs, 1);
    const afterOwn2 = legal.applyMove(AFTER_REPLY_BOARD, own2).board;
    if (replyDest == null || own2Dest == null) {
        return "夹具着法取不到落点";
    }

    const stage = { at: 0, clicks: 0, analyzes: 0, frames: 0 };
    const noteText = [];
    const countUnknown = function (cells) {
        let unknown = 0;
        for (let i = 0; i < 90; i++) {
            if (cells[i] === "?") {
                unknown += 1;
            }
        }
        return unknown;
    };
    const board = function () {
        if (stage.at === 0) {
            return START_BOARD.slice();
        }
        if (stage.at === 1) {
            const one = AFTER_BOARD.slice();
            one[transition.squareIndex(OWN_MOVE.iccs, 1)] = "?";
            return one;
        }
        if (stage.at === 2) {
            return AFTER_REPLY_BOARD.slice();
        }
        if (stage.at === 3) {
            const three = afterOwn2.slice();
            three[own2Dest] = "?";
            return three;
        }
        // 阶段 4：交替喂「同字异色抖动帧」与「真机那一帧」
        stage.frames += 1;
        const frame = afterOwn2.slice();
        frame[replyDest] = null;                                  // 对方那格整格漏检
        frame[own2Dest] = stage.frames % 2 === 1
            ? (afterOwn2[own2Dest] === "C" ? "c" : afterOwn2[own2Dest].toLowerCase())   // 抖动：同字异色
            : "P";                                                // 真机那一帧：同色不同兵种
        return frame;
    };

    const driver = driverModule.createDriver({
        allowClick: true,
        log: function (text) { noteText.push(text); },
        sleep: function () { },
        recognize: function () {
            const cells = board();
            return {
                ok: true, chars: cells, unknownCells: countUnknown(cells), conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            stage.analyzes += 1;
            const move = stage.analyzes === 1 ? OWN_MOVE : own2;
            return { ok: true, iccs: move.iccs, move: move, score: 0, depth: 1, ms: 1 };
        },
        tap: function (x, y, tapOptions) {
            if (tapOptions && tapOptions.kind === "to") {
                stage.clicks += 1;
                stage.at = stage.clicks === 1 ? 1 : 3;
            }
            return { ok: true, method: "self-check-tap" };
        },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, attemptLimitPerMove: 3, attemptLimitPerPosition: 5 }
    });
    driver.start();
    for (let i = 0; i < 24 && stage.at < 4; i++) {
        driver.tick();
        if (stage.at === 1 && driver.stats().confirms >= 1) {
            stage.at = 2;
        }
        if (stage.at === 3 && driver.stats().confirms >= 2) {
            stage.at = 4;
        }
    }
    if (stage.at !== 4) {
        return "没能走到回归阶段（阶段 " + stage.at + "，确认 " + driver.stats().confirms +
            "，状态 " + driver.machine.state() + "：" + driver.machine.reason() + "）";
    }

    let lastResult = null;
    for (let i = 0; i < 6; i++) {
        lastResult = driver.tick();
    }
    const stats = driver.stats();
    if (stats.unknownTurn !== 0) {
        return "轮次推不出来 " + stats.unknownTurn + " 次（抖动把补齐记录丢掉了）：" +
            noteText.filter(function (text) { return text.indexOf("轮次推不出来") >= 0; }).slice(-1)[0];
    }
    if (!lastResult || lastResult.side !== "b") {
        return "回归帧里轮次应当是 b（轮到对方），实际 " + (lastResult && lastResult.side);
    }
    if (driver.machine.state() !== "WAIT_TURN") {
        return "应当稳定在 WAIT_TURN（等对方落子），实际 " + driver.machine.state();
    }
    return true;
});

check("驱动：快速恢复也必须按干净观测重建基准（rebuildMaxUnknown 不能是 undefined）", function () {
    const recoveredBoard = START_BOARD.slice();
    // 造一张与开局不一致、但没有未知格的干净观测；恢复后应直接成为新基准。
    recoveredBoard[0] = null;
    recoveredBoard[9] = "r";
    let observations = 0;
    let snapshots = 0;
    const notes = [];
    const fakeMachine = {
        dispatch: function (event) {
            const effects = [];
            if (event.type === "READY") {
                effects.push({ type: "CALIBRATE" });
            } else if (event.type === "SNAPSHOT" && snapshots++ === 0) {
                // 第一次观测后立刻模拟进入 RECOVERING。由于刚校准过，这次必走 25 秒内的快速恢复分支。
                effects.push({ type: "RECOVER", reason: "测试快速恢复" });
            }
            return { effects: effects, reason: event.type };
        },
        state: function () { return "WAIT_TURN"; },
        reason: function () { return "fixture"; },
        mySide: function () { return "w"; },
        setMySide: function () { },
        canClick: function () { return { ok: false, reason: "fixture" }; },
        counters: function () { return {}; },
        versions: function () { return { sessionId: 1 }; },
        setSession: function () { }
    };
    const driver = driverModule.createDriver({
        allowClick: false,
        machine: fakeMachine,
        nowMono: function () { return 1000; },
        sleep: function () { },
        recognize: function () {
            const board = observations++ === 0 ? START_BOARD : recoveredBoard;
            return { ok: true, chars: board.slice(), unknownCells: 0, conflicts: 0 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: false, reason: "本用例不算棋" }; },
        log: function (text) { notes.push(String(text)); },
        config: { observeFrames: 1 }
    });
    driver.start();
    driver.tick();     // 建开局基准，并触发快速 RECOVER
    driver.tick();     // 应在 sideFor 前按 recoveredBoard 重建
    const stats = driver.stats();
    if (stats.baselineRebuilds !== 1) {
        return "快速恢复后应重建 1 次基准，实际 " + stats.baselineRebuilds +
            "（rebuildMaxUnknown 是否漏了默认值，或标志在快速恢复早退后才设置？）";
    }
    const reliable = driver.lastReliable();
    if (!reliable || reliable.board[0] !== null || reliable.board[9] !== "r") {
        return "恢复后的可靠基准不是当前干净观测：" + JSON.stringify(reliable && [reliable.board[0], reliable.board[9]]);
    }
    if (!notes.some(function (text) { return text.indexOf("按观测量建基准") >= 0; })) {
        return "没有记录按观测重建日志";
    }
    return true;
});

check("驱动：重建基准的那一帧不许出手（真机 2026-09-21 07:59 假杀输棋根因）", function () {
    const recoveredBoard = START_BOARD.slice();
    recoveredBoard[0] = null;
    recoveredBoard[9] = "r";
    let observations = 0;
    let snapshots = 0;
    const notes = [];
    const snapshotSides = [];
    const fakeMachine = {
        dispatch: function (event) {
            const effects = [];
            if (event.type === "READY") {
                effects.push({ type: "CALIBRATE" });
            } else if (event.type === "SNAPSHOT") {
                snapshotSides.push(event.snapshot ? event.snapshot.side : undefined);
                if (snapshots++ === 0) {
                    effects.push({ type: "RECOVER", reason: "测试快速恢复" });
                }
            }
            return { effects: effects, reason: event.type };
        },
        state: function () { return "WAIT_TURN"; },
        reason: function () { return "fixture"; },
        mySide: function () { return "w"; },
        setMySide: function () { },
        canClick: function () { return { ok: false, reason: "fixture" }; },
        counters: function () { return {}; },
        versions: function () { return { sessionId: 1 }; },
        setSession: function () { }
    };
    const driver = driverModule.createDriver({
        allowClick: false,
        machine: fakeMachine,
        nowMono: function () { return 1000; },
        sleep: function () { },
        recognize: function () {
            const board = observations++ === 0 ? START_BOARD : recoveredBoard;
            return { ok: true, chars: board.slice(), unknownCells: 0, conflicts: 0 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: false, reason: "本用例不算棋" }; },
        log: function (text) { notes.push(String(text)); },
        config: { observeFrames: 1 }
    });
    driver.start();
    driver.tick();     // 建开局基准，并在快照后触发快速 RECOVER
    const held = driver.tick();     // 重建基准的那一帧：重建只用了这一帧，禁止出手
    if (!held || held.reason !== "重建基准后等下一帧佐证") {
        return "重建帧应当返回「重建基准后等下一帧佐证」，实际 " + JSON.stringify(held);
    }
    if (held.side != null) {
        return "重建帧的轮次必须是未知（side=null，等下一帧推），实际 " + held.side;
    }
    const lastSide = snapshotSides[snapshotSides.length - 1];
    if (lastSide != null) {
        return "重建帧喂给状态机的快照轮次必须是 null（不出手），实际 " + lastSide;
    }
    if (driver.stats().rebuildHolds !== 1) {
        return "rebuildHolds 应当是 1，实际 " + driver.stats().rebuildHolds;
    }
    if (!notes.some(function (text) { return text.indexOf("等下一帧佐证") >= 0; })) {
        return "没有记录「等下一帧佐证」日志";
    }
    const next = driver.tick();     // 下一帧：盘面佐证后正常推轮次
    if (next.side !== "w") {
        return "重建后的下一帧应能推出轮次（w），实际 " + next.side;
    }
    return true;
});

check("驱动：杀棋必须第二帧佐证才放行（真机 2026-09-21 07:59 假杀输棋）", function () {
    const notes = [];
    let taps = 0;
    const driver = driverModule.createDriver({
        allowClick: true,
        nowMono: function () { return Date.now(); },
        sleep: function () { },
        recognize: function () {
            return { ok: true, chars: START_BOARD.slice(), unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 29999, depth: 1, ms: 2 };
        },
        tap: function () { taps += 1; return { ok: true, method: "self-check-tap" }; },
        log: function (text) { notes.push(String(text)); },
        config: { movetimeMs: 20, observeFrames: 1 }
    });
    driver.start();
    // 第一轮：校准 + 观测 → 算出「杀」→ 必须被按住（不发 JOB_RESULT ⇒ 无票据、无点击）
    for (let i = 0; i < 3 && driver.stats().mateHolds === 0; i++) {
        driver.tick();
    }
    if (driver.stats().mateHolds !== 1) {
        return "杀棋应当被按住 1 次，实际 " + driver.stats().mateHolds +
            "（状态 " + driver.machine.state() + "：" + driver.machine.reason() + "）";
    }
    if (driver.machine.state() !== "THINKING") {
        return "按住期间状态机应当停在 THINKING（等佐证），实际 " + driver.machine.state();
    }
    if (driver.machine.currentTicket()) {
        return "按住期间不该生成票据（更不该点击）";
    }
    if (taps !== 0) {
        return "按住期间点了 " + taps + " 次（杀棋不许在第二帧佐证前点击）";
    }
    if (!notes.some(function (text) { return text.indexOf("杀棋待佐证") >= 0; })) {
        return "没有记录「杀棋待佐证」日志";
    }
    // 第二轮观测：读回同一张盘面 → 佐证通过 → 放行（JOB_RESULT ⇒ PRECHECK + 票据）
    for (let i = 0; i < 4 && driver.stats().mateHoldPassed === 0; i++) {
        driver.tick();
    }
    const stats = driver.stats();
    if (stats.mateHoldPassed !== 1) {
        return "第二帧一致时应当放行，实际 passed=" + stats.mateHoldPassed +
            "，vetoed=" + stats.mateHoldVetoed + "（状态 " + driver.machine.state() + "）";
    }
    if (!driver.machine.currentTicket()) {
        return "放行后应当生成票据（等出手前核对）";
    }
    if (!notes.some(function (text) { return text.indexOf("杀棋佐证通过") >= 0; })) {
        return "没有记录「杀棋佐证通过」日志";
    }
    return true;
});

check("驱动：杀棋第二帧盘面不一致 ⇒ 作废重算（不许拿读错的盘面出杀）", function () {
    const notes = [];
    let taps = 0;
    const movedBoard = legal.applyMove(START_BOARD, OWN_MOVE).board;
    let observations = 0;
    const driver = driverModule.createDriver({
        allowClick: true,
        nowMono: function () { return Date.now(); },
        sleep: function () { },
        recognize: function () {
            const board = observations++ === 0 ? START_BOARD.slice() : movedBoard.slice();
            return { ok: true, chars: board, unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 29999, depth: 1, ms: 2 };
        },
        tap: function () { taps += 1; return { ok: true, method: "self-check-tap" }; },
        log: function (text) { notes.push(String(text)); },
        config: { movetimeMs: 20, observeFrames: 1 }
    });
    driver.start();
    for (let i = 0; i < 3 && driver.stats().mateHolds === 0; i++) {
        driver.tick();
    }
    if (driver.stats().mateHolds !== 1) {
        return "杀棋应当先被按住，实际 " + driver.stats().mateHolds;
    }
    for (let i = 0; i < 4 && driver.stats().mateHoldVetoed === 0; i++) {
        driver.tick();
    }
    const stats = driver.stats();
    if (stats.mateHoldVetoed !== 1) {
        return "第二帧盘面不一致应当作废，实际 vetoed=" + stats.mateHoldVetoed +
            "（状态 " + driver.machine.state() + "）";
    }
    if (driver.machine.state() !== "RECOVERING") {
        return "作废后应当进 RECOVERING 重读，实际 " + driver.machine.state();
    }
    if (driver.machine.currentTicket() || taps !== 0) {
        return "作废的那手不许生成票据/点击（票据 " + (driver.machine.currentTicket() ? "有" : "无") +
            "，点击 " + taps + " 次）";
    }
    if (!notes.some(function (text) { return text.indexOf("杀棋佐证未通过") >= 0; })) {
        return "没有记录「杀棋佐证未通过」日志";
    }
    return true;
});

check("驱动：杀棋佐证帧含「按基准填回」的格 ⇒ 不许直接放行，先强制全盘新鲜重读复核（K40 09:50 假杀 d6g6）", function () {
    const notes = [];
    let sawFreshRead = false;
    let phase = "start";          // start → dirty（第一次观察之后全是「带一个被盖格」的帧）
    const dirtyCell = 4 * 9 + 4;  // (4,4)：开局是空格；读成 '?' 后会被「按基准填回」补成空
    const driver = driverModule.createDriver({
        allowClick: true,
        nowMono: function () { return Date.now(); },
        sleep: function () { },
        recognize: function (options) {
            if (options && options.freshRead) { sawFreshRead = true; }
            const board = (phase === "start") ? START_BOARD.slice() : (function () {
                const copy = START_BOARD.slice();
                copy[dirtyCell] = "?";
                return copy;
            })();
            let unknown = 0;
            for (let i = 0; i < board.length; i += 1) { if (board[i] === "?") { unknown += 1; } }
            return { ok: true, chars: board, unknownCells: unknown, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 29999, depth: 1, ms: 2 };
        },
        tap: function () { return { ok: true, method: "self-check-tap" }; },
        log: function (text) { notes.push(String(text)); },
        config: { movetimeMs: 20, observeFrames: 1 }
    });
    driver.start();
    for (let i = 0; i < 10 && driver.stats().mateHoldPassed === 0; i += 1) {
        driver.tick();
        phase = "dirty";   // 干净的开局帧只留第一拍
    }
    const stats = driver.stats();
    if (stats.mateHolds !== 1) {
        return "杀棋应当被按住 1 次，实际 " + stats.mateHolds +
            "（状态 " + driver.machine.state() + "：" + driver.machine.reason() + "）";
    }
    if (stats.mateHoldVetoed !== 0) {
        return "盘面自始至终一致，不该作废，实际 vetoed=" + stats.mateHoldVetoed;
    }
    if (stats.mateHoldPassed !== 1) {
        return "全盘复核一致后应当放行，实际 passed=" + stats.mateHoldPassed +
            "（状态 " + driver.machine.state() + "）";
    }
    if (!notes.some(function (text) { return text.indexOf("强制全盘新鲜重读复核") >= 0; })) {
        return "被盖格帧被当成干净佐证了（没有「强制全盘新鲜重读复核」日志）：" +
            notes.filter(function (t) { return t.indexOf("杀棋") >= 0; }).join(" / ");
    }
    if (!sawFreshRead) {
        return "没有把 freshRead 透传给识别层（面板侧不会跳过差分缓存）";
    }
    if (!notes.some(function (text) { return text.indexOf("杀棋全盘复核通过") >= 0; })) {
        return "没有记录「杀棋全盘复核通过」日志";
    }
    if (!driver.machine.currentTicket()) {
        return "复核通过后应当生成票据（等出手前核对）";
    }
    return true;
});

check("驱动：全盘新鲜重读的盘面与算棋时不一致 ⇒ 杀棋作废（重读才是真相）", function () {
    const notes = [];
    let taps = 0;
    let phase = "start";            // start → dirty → moved（看到「强制全盘新鲜重读」后，下一帧就换成别的盘）
    const dirtyCell = 4 * 9 + 4;    // 阶段 dirty：开局空格 (4,4)，F1 按基准填回（=> 盘面不变）
    const movedDest = 6 * 9 + 1;    // 阶段 moved：OWN_MOVE（b2b3）的落点 (1,6)；它属于「这手改动过的格」，F2 能补
    const movedBoard = legal.applyMove(START_BOARD, OWN_MOVE).board;
    const withDirty = function (board, cell) {
        const copy = board.slice();
        copy[cell] = "?";
        return copy;
    };
    const driver = driverModule.createDriver({
        allowClick: true,
        nowMono: function () { return Date.now(); },
        sleep: function () { },
        recognize: function () {
            let board;
            if (phase === "start") {
                board = START_BOARD.slice();
            } else if (phase === "dirty") {
                board = withDirty(START_BOARD, dirtyCell);
            } else {
                board = withDirty(movedBoard, movedDest);
            }
            let unknown = 0;
            for (let i = 0; i < board.length; i += 1) { if (board[i] === "?") { unknown += 1; } }
            return { ok: true, chars: board, unknownCells: unknown, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 29999, depth: 1, ms: 2 };
        },
        tap: function () { taps += 1; return { ok: true, method: "self-check-tap" }; },
        log: function (text) { notes.push(String(text)); },
        config: { movetimeMs: 20, observeFrames: 1 }
    });
    driver.start();
    for (let i = 0; i < 12 && driver.stats().mateHoldVetoed === 0; i += 1) {
        if (phase === "dirty" && notes.some(function (t) {
            return t.indexOf("强制全盘新鲜重读复核") >= 0;
        })) {
            phase = "moved";   // 重读请求已发出 ⇒ 下一帧就是「新鲜重读」该看到的盘面
        }
        driver.tick();
        if (phase === "start") {
            phase = "dirty";
        }
    }
    const stats = driver.stats();
    if (stats.mateHolds !== 1) {
        return "杀棋应当被按住 1 次，实际 " + stats.mateHolds;
    }
    if (stats.mateHoldVetoed !== 1) {
        return "重读盘面不一致应当作废，实际 vetoed=" + stats.mateHoldVetoed +
            "（状态 " + driver.machine.state() + "）｜notes=" + notes.slice(-12).join(" / ");
    }
    if (stats.mateHoldPassed !== 0) {
        return "绝不该放行（两帧从来没真正独立一致过）";
    }
    if (driver.machine.currentTicket() || taps !== 0) {
        return "作废的那手不许生成票据/点击（票据 " + (driver.machine.currentTicket() ? "有" : "无") +
            "，点击 " + taps + " 次）";
    }
    if (!notes.some(function (t) { return t.indexOf("强制全盘新鲜重读复核") >= 0; })) {
        return "没有走到「强制全盘新鲜重读」——测试没覆盖目标路径：" + notes.slice(-6).join(" / ");
    }
    if (!notes.some(function (t) {
        return t.indexOf("杀棋全盘复核未通过") >= 0 || t.indexOf("杀棋佐证未通过") >= 0;
    })) {
        return "没有记录「复核未通过」日志：" + notes.slice(-6).join(" / ");
    }
    return true;
});

check("驱动：兜底值被兵种上限连续驳回 ⇒ 判定那枚子已离开、按空处理（真机 10:33 (5,7) 死锁）", function () {
    // 现场复刻：我方炮 b2g2 走后，「上一步」标记压住起点 (1,7) ⇒ 读不出来；
    // 基准里那格仍是 'C'，可盘面里红炮已经两枚（另一枚就在它走去的 (6,7)）
    // ⇒ 兜底永远被兵种上限驳回 ⇒ 旧行为：那一格永远 '?'、轮次永远未知（真机卡 12 分钟）。
    let own = null;
    const list = legal.legalMoves(START_BOARD, 1, 7, "w");
    for (let i = 0; i < list.length; i += 1) {
        if (list[i].toCol === 6 && list[i].toRow === 7) {
            own = list[i];
            break;
        }
    }
    if (!own) {
        return "夹具失败：开局红炮 (1,7) 找不到走到 (6,7) 的走法";
    }
    const origin = 7 * 9 + 1;   // (1,7)：被「上一步」标记压住的起点
    const dest = 7 * 9 + 6;     // (6,7)：炮走过去的那一格
    const aside = 7 * 9 + 4;    // (4,7)：另一格读不出来（真机同帧还有一格）—— 它能把 F1/F2 堵住，
                                // 否则「唯一合法着法解释得通」（F2）会先自己补齐、轮不到兜底环节
    const moved = legal.applyMove(START_BOARD, own).board;
    let phase = "start";
    const notes = [];
    const fakeMachine = {
        dispatch: function (event) {
            const effects = [];
            if (event.type === "READY") {
                effects.push({ type: "CALIBRATE" });
            }
            return { effects: effects, reason: event.type };
        },
        state: function () { return "WAIT_TURN"; },
        reason: function () { return "fixture"; },
        mySide: function () { return "w"; },
        setMySide: function () { },
        canClick: function () { return { ok: false, reason: "fixture" }; },
        counters: function () { return {}; },
        versions: function () { return { sessionId: 1 }; },
        setSession: function () { }
    };
    const driver = driverModule.createDriver({
        allowClick: false,
        machine: fakeMachine,
        nowMono: function () { return 1000; },
        sleep: function () { },
        recognize: function () {
            let board;
            if (phase === "start") {
                board = START_BOARD.slice();
            } else {
                board = moved.slice();
                board[origin] = "?";   // 起点被「上一步」标记压住：读不出来
                board[aside] = "?";    // 另外一格也读不出来（它不属于这手改动的格）
            }
            let unknown = 0;
            for (let i = 0; i < board.length; i += 1) { if (board[i] === "?") { unknown += 1; } }
            return { ok: true, chars: board, unknownCells: unknown, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        ocrCell: function () { return null; },   // OCR+找色也没证据 ⇒ 只能走「按可靠盘面兜」
        analyze: function () { return { ok: false, reason: "本用例不算棋" }; },
        log: function (text) { notes.push(String(text)); },
        config: { observeFrames: 1 }
    });
    driver.start();
    for (let i = 0; i < 10; i += 1) {
        driver.tick();
        phase = "moved";
    }
    const stats = driver.stats();
    if (!(stats.unknownFillEmptied >= 1)) {
        return "驳回多帧后应当按空处理，实际 unknownFillEmptied=" + (stats.unknownFillEmptied || 0) +
            "｜notes=" + notes.slice(-6).join(" / ");
    }
    if (!notes.some(function (t) { return t.indexOf("按空处理") >= 0; })) {
        return "没有记录「按空处理」日志：" + notes.slice(-6).join(" / ");
    }
    if (!notes.some(function (t) { return t.indexOf("兜底被兵种上限驳回") >= 0; })) {
        return "夹具没走到『兵种上限驳回』那一环（测试没覆盖目标路径）";
    }
    const rel = driver.lastReliable();
    if (!rel || rel.side !== "b") {
        return "按空处理后轮次应当顺着炮的着法推进到黑方，实际 " +
            (rel ? rel.side : "无基准") + "｜notes=" + notes.slice(-6).join(" / ");
    }
    if (rel.board[dest] !== "C" || rel.board[origin] != null) {
        return "推进后的盘面不对（落点=" + String(rel.board[dest]) +
            "，起点=" + String(rel.board[origin]) + "）";
    }
    return true;
});

/** 夹具：假的「规划机器」——每次快照都发一个 COMPUTE（局面 = 观测到的盘面，轮次固定 w）。 */
function makeLoopFixtureMachine(stateName) {
    let jobs = 0;
    return {
        dispatch: function (event) {
            const effects = [];
            if (event.type === "READY") {
                effects.push({ type: "CALIBRATE" });
            } else if (event.type === "SNAPSHOT") {
                jobs += 1;
                effects.push({
                    type: "COMPUTE",
                    jobId: "loop-fixture-" + jobs,
                    board: event.snapshot.board,
                    side: "w",
                    fen: event.snapshot.fen,
                    movetimeMs: 5
                });
            }
            return { effects: effects, reason: event.type };
        },
        state: function () { return stateName || "THINKING"; },
        reason: function () { return "fixture"; },
        mySide: function () { return "w"; },
        setMySide: function () { },
        canClick: function () { return { ok: false, reason: "fixture" }; },
        counters: function () { return {}; },
        versions: function () { return { sessionId: 1 }; },
        setSession: function () { }
    };
}

check("驱动：取消重算的同一手不算「又摆了一次」（循环历史去重，真机 09:08 误触禁手）", function () {
    const excludesSeen = [];
    const notes = [];
    const driver = driverModule.createDriver({
        allowClick: false,
        machine: makeLoopFixtureMachine(),
        nowMono: function () { return Date.now(); },
        sleep: function () { },
        recognize: function () {
            return { ok: true, chars: START_BOARD.slice(), unknownCells: 0, conflicts: 0 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function (position, options) {
            excludesSeen.push(options && options.excludeIccs ? options.excludeIccs.slice() : null);
            return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 };
        },
        log: function (text) { notes.push(String(text)); },
        config: { movetimeMs: 5, observeFrames: 1 }
    });
    driver.start();
    for (let i = 0; i < 4; i++) {
        driver.tick();
    }
    if (excludesSeen.length < 4) {
        return "应当算了 4 次棋，实际 " + excludesSeen.length + " 次";
    }
    for (let i = 0; i < excludesSeen.length; i++) {
        if (excludesSeen[i]) {
            return "同一局面连记同手（取消重算场景）不该触发禁手，第 " + (i + 1) +
                " 次却禁了 " + JSON.stringify(excludesSeen[i]);
        }
    }
    if (driver.stats().loopBanHits !== 0) {
        return "loopBanHits 应当是 0，实际 " + driver.stats().loopBanHits;
    }
    if (notes.some(function (text) { return text.indexOf("疑似循环") >= 0; })) {
        return "去重场景不该出现「疑似循环」日志";
    }
    return true;
});

check("驱动：两招来回（A,B,A,B）断环——禁掉上一轮那一手（真机 2026-09-21 f1↔f3 摆车）", function () {
    const boardX = START_BOARD.slice();
    const boardY = legal.applyMove(START_BOARD, OWN_MOVE).board;
    const moveA = OWN_MOVE;
    let moveB = null;
    const candidates = legal.allLegalMoves(boardY, "w");
    for (let i = 0; i < candidates.length; i++) {
        if (candidates[i].iccs !== moveA.iccs) {
            moveB = candidates[i];
            break;
        }
    }
    let frame = 0;
    const excludesSeen = [];
    const driver = driverModule.createDriver({
        allowClick: false,
        machine: makeLoopFixtureMachine(),
        nowMono: function () { return Date.now(); },
        sleep: function () { },
        recognize: function () {
            // 按「帧号」而不是「调用次数」切板面：同一帧里驱动可能识别多次（校准/佐证），
            // 那几次必须看到同一盘面，否则夹具的奇偶会错位（真机不会一帧内换盘）
            const board = (frame % 2 === 1) ? boardX : boardY;
            return { ok: true, chars: board.slice(), unknownCells: 0, conflicts: 0 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function (position, options) {
            excludesSeen.push(options && options.excludeIccs ? options.excludeIccs.slice() : null);
            const atX = position.board.join("") === boardX.join("");
            return { ok: true, iccs: atX ? moveA.iccs : moveB.iccs,
                move: atX ? moveA : moveB, score: 0, depth: 1, ms: 1 };
        },
        log: function () { },
        config: { movetimeMs: 5, observeFrames: 1 }
    });
    driver.start();
    for (let i = 0; i < 4; i++) {
        frame = i + 1;
        driver.tick();
    }
    if (excludesSeen.length < 4) {
        return "应当算了 4 次棋，实际 " + excludesSeen.length + " 次";
    }
    if (excludesSeen[2]) {
        return "第 3 手（A,B,A 里的 A）还不该禁手：" + JSON.stringify(excludesSeen[2]);
    }
    const fourth = excludesSeen[3] || [];
    if (fourth.indexOf(moveB.iccs) < 0) {
        return "第 4 手应当禁掉来回的那一手 " + moveB.iccs + "，实际 " + JSON.stringify(excludesSeen[3]);
    }
    if (driver.stats().loopBanHits !== 1) {
        return "loopBanHits 应当是 1，实际 " + driver.stats().loopBanHits;
    }
    return true;
});

check("驱动：同一局面同一手摆到第 3 次必禁（①号规则；黑方 a9↔a8 摆车，双方都合法）", function () {
    // 循环：黑方在 (0,0)↔(1,0) 来回摆车（每一步都是合法着法，轮次推断能解释），
    // 我们每次都在同一局面决定走同一手 → 第 3 次进这个局面时该手要被禁（长捉要判负，得换一手）。
    const boardX = START_BOARD.slice();
    const moveA = OWN_MOVE;
    let shuttle = null;
    const blacks = legal.allLegalMoves(boardX, "b");
    for (let i = 0; i < blacks.length; i++) {
        const m = blacks[i];
        if (m.fromCol === 0 && m.fromRow === 0 && m.toCol === 0 && m.toRow === 1) {
            shuttle = m;
            break;
        }
    }
    if (!shuttle) {
        return "夹具前提不成立：黑方 (0,0)→(1,0) 应当是合法着法";
    }
    const boardY = legal.applyMove(boardX, shuttle).board;
    let moveB = null;
    const redAtY = legal.allLegalMoves(boardY, "w");
    for (let i = 0; i < redAtY.length; i++) {
        if (redAtY[i].iccs !== moveA.iccs) {
            moveB = redAtY[i];
            break;
        }
    }
    let frame = 0;
    const excludesSeen = [];
    const notes = [];
    const driver = driverModule.createDriver({
        allowClick: false,
        machine: makeLoopFixtureMachine(),
        nowMono: function () { return Date.now(); },
        sleep: function () { },
        recognize: function () {
            const board = (frame % 2 === 1) ? boardX : boardY;
            return { ok: true, chars: board.slice(), unknownCells: 0, conflicts: 0 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function (position, options) {
            excludesSeen.push(options && options.excludeIccs ? options.excludeIccs.slice() : null);
            const atX = position.board.join("") === boardX.join("");
            return { ok: true, iccs: atX ? moveA.iccs : moveB.iccs,
                move: atX ? moveA : moveB, score: 0, depth: 1, ms: 1 };
        },
        log: function (text) { notes.push(String(text)); },
        config: { movetimeMs: 5, observeFrames: 1 }
    });
    driver.start();
    for (let i = 0; i < 5; i++) {
        frame = i + 1;
        driver.tick();
    }
    if (excludesSeen.length < 5) {
        return "应当算了 5 次棋，实际 " + excludesSeen.length + " 次";
    }
    if (excludesSeen[0] || excludesSeen[1] || excludesSeen[2]) {
        return "前三次决策还不该禁手：" + JSON.stringify(excludesSeen);
    }
    const fourth = excludesSeen[3] || [];
    if (fourth.indexOf(moveB.iccs) < 0) {
        return "第 4 手应当禁掉来回的那一手 " + moveB.iccs + "，实际 " + JSON.stringify(excludesSeen[3]);
    }
    const fifth = excludesSeen[4] || [];
    if (fifth.indexOf(moveA.iccs) < 0) {
        return "第 5 手（同一局面第 3 次）应当禁掉 " + moveA.iccs + "，实际 " + JSON.stringify(excludesSeen[4]);
    }
    if (driver.stats().loopBanHits !== 2) {
        return "loopBanHits 应当是 2，实际 " + driver.stats().loopBanHits;
    }
    if (!notes.some(function (text) { return text.indexOf("疑似循环") >= 0; })) {
        return "禁手时应当留下「疑似循环」日志";
    }
    return true;
});

const loopHistoryModule = require("../src/automation/loop-history.js");

check("循环历史：同一世代里取消重算只算一次（真机 09:08 误触禁手）", function () {
    const history = loopHistoryModule.createLoopHistory();
    history.record("K", "b2c2", 0);
    history.record("K", "b2c2", 0);   // CANCEL 后重算：同一个回合世代，是同一手
    if (history.size() !== 1) {
        return "同世代同局面同手应当只记一次，实际记了 " + history.size() + " 次";
    }
    if (history.bansFor("K")) {
        return "还不到 3 次，不该禁手：" + JSON.stringify(history.bansFor("K"));
    }
    return true;
});

check("循环历史：跨世代照记，第 3 次进同一局面禁手（长将必须禁得掉）", function () {
    const history = loopHistoryModule.createLoopHistory();
    history.record("K", "d6e6", 0);
    if (history.bansFor("K")) {
        return "第一次不该禁手";
    }
    history.record("K", "d6e6", 1);   // 确认落定一手后对方应回来：新世代，是真摆回来
    if (history.size() !== 2) {
        return "跨世代同局面同手必须照记（否则长将永远禁不掉），实际记了 " + history.size() + " 次";
    }
    const bans = history.bansFor("K");   // 第 3 次进这个局面前查询
    if (!bans || bans.indexOf("d6e6") < 0) {
        return "第 3 次应当禁掉 d6e6，实际 " + JSON.stringify(bans);
    }
    return true;
});

check("循环历史：两招来回 A,B,A → 禁掉 B 局面上一轮走的手（断环）", function () {
    const history = loopHistoryModule.createLoopHistory();
    history.record("X", "m1", 0);
    history.record("Y", "m2", 1);
    history.record("X", "m1", 2);
    const bans = history.bansFor("Y");
    if (!bans || bans.indexOf("m2") < 0) {
        return "该禁 Y 局面上的 m2，实际 " + JSON.stringify(bans);
    }
    return true;
});

check("循环历史：clear 后什么都不禁（换局/换边）", function () {
    const history = loopHistoryModule.createLoopHistory();
    history.record("K", "b2c2", 0);
    history.record("K", "b2c2", 1);
    history.clear();
    if (history.size() !== 0 || history.bansFor("K")) {
        return "clear 之后应当是干净状态，实际 size=" + history.size() + " bans=" + JSON.stringify(history.bansFor("K"));
    }
    return true;
});

check("驱动：worker 起不来（宿主没有 threads）时仍能出着法（退回同步，不卡死）", function () {
    const driver = driverModule.createDriver({
        allowClick: false,
        recognize: function () {
            return { ok: true, chars: START_BOARD.slice(), unknownCells: 0, conflicts: 0, layout: START_FEN };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 }; },
        config: { movetimeMs: 20 }
    });
    driver.start();
    let state = null;
    // 夹具是 allowClick:false → 出着法后停在 SUGGESTED（建议模式稳态）；点击模式才是 PRECHECK。
    // 这条用例只看「有没有算出着法、有没有走同步算棋」，两个稳态都算通过。
    for (let i = 0; i < 4 && state !== "PRECHECK" && state !== "SUGGESTED"; i++) {
        driver.tick();
        state = driver.machine.state();
    }
    const stats = driver.stats();
    return state === "PRECHECK" || state === "SUGGESTED"
        ? (stats.thinkInline > 0 ? true : "出了着法但没走同步算棋？")
        : ("没能进到 PRECHECK/SUGGESTED，停在 " + state + "（" + driver.machine.reason() + "）");
});

// ---------------------------------------------------------------- 诊断日志环 + 点击通道 + 尝试预算

const logRingModule = require("../src/diagnostics/log-ring.js");
const adapterModule = require("../src/runtime/aijs-adapter.js");

check("ICCS 换算：a..i + 行号 9..0 → 90 格下标（驱动/验证器/推理共用一份）", function () {
    const rulesModule = require("../src/chess/rules.js");
    if (rulesModule.iccsSquare("b2c2", 0) !== (9 - 2) * 9 + 1) {
        return "起点换算错：b2 应当 = 64，实际 " + rulesModule.iccsSquare("b2c2", 0);
    }
    if (rulesModule.iccsSquare("b2c2", 1) !== (9 - 2) * 9 + 2) {
        return "落点换算错：c2 应当 = 65，实际 " + rulesModule.iccsSquare("b2c2", 1);
    }
    if (rulesModule.iccsSquare("a9i0", 0) !== 0 || rulesModule.iccsSquare("a9i0", 1) !== 89) {
        return "边界换算错：a9 应 = 0、i0 应 = 89";
    }
    if (rulesModule.iccsSquare("b2", 0) !== null || rulesModule.iccsSquare("", 1) !== null) {
        return "非法 ICCS 应当返回 null";
    }
    return true;
});

check("长跑：日志环超过上限要原地裁剪，不许抛「常量重新赋值」", function () {
    // 真机回归：`const history = []; history = history.slice(-200)` 在超过 300 条时抛
    // "Assignment to constant variable"，而一轮真机跑正好是 200~320 条。
    const ring = logRingModule.createLogRing(300, 200);
    for (let i = 1; i <= 500; i++) {
        ring.push({ text: "第 " + i + " 条" });
    }
    if (ring.size() > 300) {
        return "日志环溢出了上限（300），实际 " + ring.size() + " 条";
    }
    if (ring.size() < 200) {
        return "日志环应当保留 200~300 条，实际只剩 " + ring.size() + " 条";
    }
    const all = ring.all();
    if (all[all.length - 1].text !== "第 500 条") {
        return "最后一条应是「第 500 条」，实际 " + all[all.length - 1].text;
    }
    // 裁剪只丢最旧的，不丢新产生的
    if (all[all.length - 2].text !== "第 499 条") {
        return "倒数第二条应是「第 499 条」，实际 " + all[all.length - 2].text;
    }
    return true;
});

check("点击通道：非法通道名必须报错，不许静默退回 auto", function () {
    let threw = false;
    try {
        adapterModule.createTap({ method: "accssibility" });   // 故意拼错
    } catch (error) {
        threw = /未知的点击通道/.test(String(error && error.message ? error.message : error));
    }
    return threw ? true : "拼错的通道名没有被拒绝（对照实验会被静默污染）";
});

check("点击通道：日志里的手势名必须是真实手势（原地 swipe ≠ tap）", function () {
    // pressMs ≥ 120 → 实际执行 `input swipe x y x y ms`（原地按住），名称必须如实反映，
    // 否则通道对照实验会被「shizuku-input-tap」这个名字误导。
    const longPress = adapterModule.createTap({ method: "shizuku", pressMs: 350 });
    const pressed = longPress.tap(100, 200);
    if (pressed.method !== "shizuku-input-swipe") {
        return "350ms 按压应记为 shizuku-input-swipe，实际 " + pressed.method;
    }
    if (pressed.channel !== "shizuku" || pressed.requested !== "shizuku") {
        return "请求/实际通道没记全：" + JSON.stringify({ c: pressed.channel, r: pressed.requested });
    }
    if (typeof pressed.startedAtMono !== "number" || typeof pressed.finishedAtMono !== "number") {
        return "缺少起止时刻（无法判断两次手势是否互相取消）";
    }
    const shortPress = adapterModule.createTap({ method: "shizuku", pressMs: 60 });
    const tapped = shortPress.tap(100, 200);
    return tapped.method === "shizuku-input-tap"
        ? true : ("60ms 应是 shizuku-input-tap，实际 " + tapped.method);
});

check("驱动：同一局面同一手重试超限 → 暂停，不再无限盲点（尝试预算）", function () {
    let clock = 1000;
    const taps = [];
    const driver = driverModule.createDriver({
        allowClick: true,
        tap: function (x, y) {
            taps.push({ x: x, y: y });
            return { ok: true, method: "self-check-tap", x: x, y: y };
        },
        // 永远返回同一个开局：模拟「点击确实发出去了，但棋子不落」的真机现场
        recognize: function () {
            return { ok: true, chars: START_BOARD.slice(), unknownCells: 0, conflicts: 0, layout: START_FEN };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 }; },
        nowMono: function () { return clock; },
        log: function () { },
        config: { movetimeMs: 20, attemptLimitPerMove: 2, attemptLimitPerPosition: 3 }
    });
    driver.start();
    for (let i = 0; i < 80; i++) {
        clock += 1500;
        driver.tick();
        if (driver.machine.state() === "PAUSED") {
            break;
        }
    }
    if (driver.machine.state() !== "PAUSED") {
        return "重试超限后应暂停，实际停在 " + driver.machine.state() +
            "（" + driver.machine.reason() + "）";
    }
    if (String(driver.machine.reason()).indexOf("同一") < 0) {
        return "暂停原因没说明是重试超限：" + driver.machine.reason();
    }
    const attempts = Math.floor(taps.length / 2);   // 起点+终点 = 一次出手
    if (attempts > 3) {
        return "暂停前出手 " + attempts + " 次（上限 3），预算没生效";
    }
    if (attempts < 2) {
        return "还没到预算就停了（只出手 " + attempts + " 次）";
    }
    const before = taps.length;
    for (let i = 0; i < 5; i++) {
        clock += 1500;
        driver.tick();
    }
    return taps.length === before ? true : ("暂停后仍在点击（" + before + " → " + taps.length + "）");
});

// ---------------------------------------------------------------- §14 框架契约

const labelsModule = require("../src/ui/labels.js");
const themeTokens = require("../src/ui/theme-tokens.js");
const configModule = require("../src/app/config.js");
const storeModule = require("../src/app/session-store.js");
const controllerModule = require("../src/app/controller.js");
const recorderModule = require("../src/diagnostics/recorder.js");
const portsModule = require("../src/runtime/ports.js");
const windowManagerModule = require("../src/runtime/window-manager.js");
const gameSessionModule = require("../src/services/game-session.js");
const bootstrapModule = require("../src/app/bootstrap.js");
const busModule = require("../src/app/panel-bus.js");
const pipelineFactoryModule = require("../src/vision/pipeline-factory.js");
const moveLedgerModule = require("../src/chess/move-ledger.js");
const positionTrackerModule = require("../src/services/position-tracker.js");
const attemptBudgetModule = require("../src/automation/attempt-budget.js");
const crossFrameGateModule = require("../src/automation/cross-frame-gate.js");
const frameConsensusModule = require("../src/automation/frame-consensus.js");
const knownFactsModule = require("../src/automation/known-facts.js");

check("框架·文案：未知状态要原样返回，不能悄悄变成「未知」把新状态藏起来", function () {
    if (labelsModule.stateText("CLICKING") !== "点击中") {
        return "已知状态翻译错：" + labelsModule.stateText("CLICKING");
    }
    if (labelsModule.stateText("BRAND_NEW_STATE") !== "BRAND_NEW_STATE") {
        return "未知状态被吞掉了：" + labelsModule.stateText("BRAND_NEW_STATE");
    }
    if (labelsModule.MODES.length !== 3 || !labelsModule.MODE_LABEL.auto) {
        return "模式表不完整";
    }
    // 2026-09-20：面板默认不能把日志藏起来（原来的默认是「2 行 + 启动即收起小窗」）
    if (themeTokens.LOG_LINES_COMPACT < 4) {
        return "日志默认只显示 " + themeTokens.LOG_LINES_COMPACT + " 行（要求默认别隐藏日志）";
    }
    // 2026-09-20 晚：日志**拆成屏幕下方单独一窗**（用户口径「面板在上面，日志在下面」）⇒
    // 这两个高度不再需要互相装得下，改成各自够用：
    //   · 控制面板：三行按钮 + 状态行 + 进度卡（126 + 34 + 80 = 240）
    //   · 日志窗：够 8 行（9sp 行高实测 ~37px；8×37 + padding 12 = 308）
    const controlsNeed = themeTokens.PANEL.rowHeight * 3 + themeTokens.PANEL.statusHeight + themeTokens.PANEL.stepHeight;
    if (themeTokens.PANEL.height < controlsNeed) {
        return "控制面板高度装不下自己的内容：" + themeTokens.PANEL.height + " < " + controlsNeed;
    }
    if (themeTokens.PANEL.logHeight < themeTokens.LOG_LINES_COMPACT * 30) {
        return "日志窗高度装不下 " + themeTokens.LOG_LINES_COMPACT + " 行：" + themeTokens.PANEL.logHeight;
    }
    if (!(themeTokens.PANEL.safeTopRatio > 0 && themeTokens.PANEL.safeTopRatio < themeTokens.PANEL.menuTopRatio &&
        themeTokens.PANEL.menuTopRatio < 1)) {
        return "面板安全带比例不对：safeTopRatio=" + themeTokens.PANEL.safeTopRatio +
            " menuTopRatio=" + themeTokens.PANEL.menuTopRatio;
    }
    return themeTokens.PANEL.height > 0 ? true : "面板令牌缺失";
});

check("悬浮窗：720p/1080p/1280p 的控件与窗口均在屏宽内", function () {
    const screens = [{ width: 720, height: 1600 }, { width: 1080, height: 2400 },
        { width: 1280, height: 2772 }];
    for (let i = 0; i < screens.length; i++) {
        const screen = screens[i];
        const layout = themeTokens.layoutFor(screen);
        const rowWidth = Math.round((74 + 150 + 140 + 110 + 110 + 160 + 130) * layout.scale) +
            Math.round(16 * layout.scale);
        if (layout.PANEL.width > screen.width || layout.compactWidth > screen.width ||
                rowWidth > layout.PANEL.width) {
            return screen.width + "px 布局溢出：" + JSON.stringify(layout);
        }
        if (layout.PANEL.height <= 0 || layout.PANEL.logHeight <= 0) {
            return screen.width + "px 布局高度无效";
        }
    }
    return true;
});

check("框架·配置：合法 autorun 要被归一化（含默认值补齐）", function () {
    const parsed = configModule.parseAutorun(JSON.stringify({
        mode: "auto", clickMethod: "accessibility", clickPressMs: 350,
        clickHideSettleMs: 600, stopAfterMs: 250000, start: true, collapse: true
    }));
    if (!parsed.ok) return "合法配置被判非法：" + JSON.stringify(parsed.issues);
    const merged = configModule.withDefaults(parsed.config);
    if (merged.mode !== "auto" || merged.clickMethod !== "accessibility") {
        return "归一化结果不对：" + JSON.stringify(merged);
    }
    if (merged.clickHideSettleMs !== 600) {
        return "clickHideSettleMs 没被采纳：" + merged.clickHideSettleMs;
    }
    if (merged.collapse !== true) {
        return "collapse 没被采纳：" + merged.collapse;
    }
    if (configModule.withDefaults({}).collapse !== false) {
        return "collapse 默认值应为 false（默认不收起，日志要看得见）";
    }
    return merged.gamePackage ? true : "默认值没补上（gamePackage）";
});

check("框架·配置：非法通道名必须报错、且原样透传（不许静默退回 auto）", function () {
    const parsed = configModule.parseAutorun('{"clickMethod":"accssibility"}');
    if (!configModule.hasBlockingIssue(parsed.issues)) {
        return "拼错的通道名没有被判为致命";
    }
    if (parsed.config.clickMethod !== "accssibility") {
        return "非法值被改写成 " + parsed.config.clickMethod + "（应该原样透传，让 createTap 报错）";
    }
    const bad = configModule.parseAutorun("{ 这不是 JSON }");
    if (bad.ok !== false || !configModule.hasBlockingIssue(bad.issues)) {
        return "坏 JSON 没有被判为致命";
    }
    const empty = configModule.parseAutorun("   ");
    return (empty.ok === true && empty.empty === true) ? true : "空 autorun 应该 ok+empty";
});

check("框架·窗口：点击租约期间抓帧不许把窗口恢复出来（今天的血案）", function () {
    const visibleLog = [];
    const fakeWin = {
        x: 30, y: 264,
        setVisibility: function (visible) { visibleLog.push(visible); return true; },
        setPosition: function (x, y) { this.x = x; this.y = y; return true; },
        getPosition: function () { return { x: this.x, y: this.y }; }
    };
    const wm = windowManagerModule.createWindowManager({
        win: fakeWin, sleep: function () { }, panel: { height: 372 }, clickHideSettleMs: 0
    });
    // 普通抓帧：藏 → 跑 → 恢复
    wm.withHidden(function () { visibleLog.push("frame"); });
    if (visibleLog[0] !== false || visibleLog[visibleLog.length - 1] !== true) {
        return "普通抓帧没有「藏→跑→恢复」：" + JSON.stringify(visibleLog);
    }
    // 租约期间抓帧：藏 → 跑 → **不许恢复**
    visibleLog.length = 0;
    wm.acquireClickLease(0);
    wm.withHidden(function () { visibleLog.push("frame"); });
    if (visibleLog.indexOf(true) >= 0) {
        return "租约期间抓帧把窗口恢复了（游戏会丢掉「已选中」）：" + JSON.stringify(visibleLog);
    }
    if (!wm.isLeaseHeld()) {
        return "租约状态没被记住";
    }
    wm.releaseClickLease();
    if (wm.isHidden()) {
        return "释放租约后窗口应当恢复可见";
    }
    // 用户拖过 → 不再自动贴板
    const first = wm.snapAboveBoard({ y: 900 }, 2400);
    if (!first.moved) {
        return "首次贴板应当移动：" + first.reason;
    }
    wm.markUserMoved();
    const after = wm.snapAboveBoard({ y: 1500 }, 2400);
    return after.moved ? "用户拖过之后不该再抢位置" : true;
});

check("框架·控制器：命令要校验参数与运行态，别让按钮直接改全局", function () {
    const calls = [];
    const controller = controllerModule.createController({
        handlers: {
            START: function (arg) { calls.push(["START", arg.mode]); return "ok"; },
            SET_CHANNEL: function (arg) { calls.push(["SET_CHANNEL", arg.channel]); return "ok"; },
            STOP: function () { calls.push(["STOP"]); return "ok"; }
        },
        isRunning: function () { return false; }
    });
    if (!controller.run("SET_CHANNEL", { channel: "shizuku" }).ok) {
        return "合法通道被拒（应当交给绑定的处理函数）";
    }
    if (calls.length !== 1 || calls[0][1] !== "shizuku") {
        return "合法命令没有传到处理函数：" + JSON.stringify(calls);
    }
    const bogus = controller.run("SET_CHANNEL", { channel: "shizukuu" });
    if (bogus.ok || String(bogus.reason).indexOf("未知点击通道") < 0) {
        return "非法通道没被拒：" + JSON.stringify(bogus);
    }
    if (controller.run("NOT_A_COMMAND").ok) {
        return "未知命令没被拒";
    }
    if (!controller.run("START", { mode: "auto" }).ok) {
        return "合法 START 被拒";
    }
    const running = controllerModule.createController({
        handlers: { START: function () { return "ok"; } },
        isRunning: function () { return true; }
    });
    if (running.run("START", { mode: "auto" }).ok) {
        return "已经在跑时不该允许再 START（guard 失效）";
    }
    const thrower = controllerModule.createController({
        handlers: { STOP: function () { throw new Error("内部炸了"); } },
        isRunning: function () { return false; }
    });
    const result = thrower.run("STOP");
    return (result.ok === false && String(result.error).indexOf("内部炸了") >= 0)
        ? true : "处理函数抛错时应当返回 ok:false + error";
});

check("框架·状态：只有真变化才通知订阅者", function () {
    const store = storeModule.createSessionStore({ mode: "watch" });
    let notified = 0;
    store.subscribe(function () { notified += 1; });
    store.patch({ mode: "auto" }, "test");
    const after = notified;
    store.patch({ mode: "auto" }, "same");   // 同值不该再通知
    if (notified !== after) {
        return "同值 patch 也通知了（界面会被无意义重画）";
    }
    return (after === 1 && store.get("mode") === "auto") ? true : "状态没生效";
});

check("框架·记录器：限频只放一条，环长度有界", function () {
    const lines = [];
    let clock = 0;
    const recorder = recorderModule.createRecorder({
        sink: function (text) { lines.push(text); },
        now: function () { return clock; },
        limit: 10, keep: 5
    });
    recorder.logThrottled("k", 1000, "第一次");
    clock += 100;
    recorder.logThrottled("k", 1000, "第二次");
    if (lines.length !== 1) {
        return "限频没生效：打了 " + lines.length + " 条";
    }
    clock += 2000;
    recorder.logThrottled("k", 1000, "第三次");
    if (lines.length !== 2) {
        return "过了限频窗口应当再打一条，实际 " + lines.length;
    }
    for (let i = 0; i < 30; i++) {
        recorder.log("填 " + i);
    }
    return (lines.length === 32 && recorder.size() <= 10)
        ? true : ("环长度不对：lines=" + lines.length + " size=" + recorder.size());
});

check("框架·端口：缺能力要能列出来，不能跑到一半才崩", function () {
    const bare = portsModule.createPorts({}, {});
    const missing = bare.missing();
    if (missing.indexOf("capture") < 0 || missing.indexOf("rgbAt") < 0) {
        return "空适配层应当报出 capture/rgbAt 缺失，实际 " + JSON.stringify(missing);
    }
    const fake = portsModule.createPorts({
        capture: function () { return null; },
        screenSize: function () { return { width: 1080, height: 2400 }; },
        rgbAt: function () { return [0, 0, 0]; },
        createTap: function () { return null; },
        focusedWindowPackage: function () { return "com.x"; }
    }, {});
    if (fake.missing().length !== 0) {
        return "能力齐备时不该报缺：" + JSON.stringify(fake.missing());
    }
    return fake.describe().indexOf("必需能力齐备") >= 0 ? true : "摘要文案不对：" + fake.describe();
});

check("框架·会话：改了「创建时确定」的参数必须重建驱动（否则界面说 A、实际走 B）", function () {
    const built = [];
    const fakeDriver = {
        start: function () { return "WAIT_STABLE"; },
        stop: function () { },
        computeMode: function () { return "inline"; }
    };
    const session = gameSessionModule.createGameSession({
        driverModule: {
            createDriver: function (deps) {
                built.push(deps);
                return Object.assign({}, fakeDriver);
            }
        },
        ports: portsModule.createPorts({}, {}),
        config: { clickMethod: "shizuku", clickPressMs: 350 },
        note: function () { }
    });
    session.start();
    if (built.length !== 1) {
        return "首次启动应当建一次驱动";
    }
    const same = session.applyConfig({ drawMode: "cells" }, "绘制档位");
    if (same.rebuilt) {
        return "只改绘制档位不该重建驱动";
    }
    const changed = session.applyConfig({ clickMethod: "accessibility" }, "换通道");
    if (!changed.rebuilt) {
        return "改了点击通道却没作废驱动（通道在 createTap 时就固定了）";
    }
    session.stop("测试结束");
    return session.running() === false ? true : "stop 之后仍是运行中";
});

check("框架·会话：extras 携带的依赖要进驱动（静态 + 每次构建 + driverConfig）", function () {
    const built = [];
    const session = gameSessionModule.createGameSession({
        driverModule: {
            createDriver: function (deps) {
                built.push(deps);
                return { start: function () { return "S"; }, stop: function () { }, isRunning: function () { return false; } };
            }
        },
        ports: portsModule.createPorts({}, {}),
        extras: { isTrustworthy: function () { return true; } },
        note: function () { }
    });
    const tap = function () { return "tap"; };
    session.build({ allowClick: true, tap: tap, extras: { mySide: "b" }, driverConfig: { tickMs: 1 } });
    const deps = built[0];
    if (!deps) { return "会话没有建驱动"; }
    if (typeof deps.isTrustworthy !== "function") { return "静态 extras 没进驱动"; }
    if (deps.mySide !== "b") { return "per-build extras 没进驱动"; }
    if (typeof deps.tap !== "function") { return "tap 没进驱动"; }
    if (!deps.config || deps.config.tickMs !== 1) { return "driverConfig 没进驱动 config"; }
    session.stop("测试结束");
    return true;
});

check("框架·会话：驱动被 pause 后再次 start 应当视为继续（不能报「已经在跑」）", function () {
    let driverRunning = false;
    const driver = {
        start: function () { driverRunning = true; return "READY"; },
        stop: function () { driverRunning = false; },
        pause: function () { driverRunning = false; },
        isRunning: function () { return driverRunning; },
        computeMode: function () { return "inline"; }
    };
    const session = gameSessionModule.createGameSession({
        driverModule: { createDriver: function () { return driver; } },
        ports: portsModule.createPorts({}, {}),
        note: function () { }
    });
    const first = session.start();
    if (first.ok !== true) { return "首次启动失败：" + first.reason; }
    const again = session.start();
    if (again.ok !== false) { return "驱动在跑时应当拒绝重复启动"; }
    driver.pause();
    const resume = session.start();
    return resume.ok === true ? true : ("暂停后 start 应当视为继续，实际：" + resume.reason);
});

check("框架·总线：对方状态行带「几何版本/引擎」——两边共用同一渲染口径", function () {
    const store = {};
    const fakeFiles = {
        getSdcardPath: function () { return "/sdcard"; },
        join: function (a, b) { return a + "/" + b; },
        ensureDir: function () { return true; },
        read: function (p) { return store[p] || ""; },
        write: function (p, t) { store[p] = String(t); return true; }
    };
    const floatyBus = busModule.createPanelBus({ owner: "floaty", files: fakeFiles, now: function () { return 1000; } });
    floatyBus.publish({ running: true, mode: "auto", side: "b", gridVersion: 7, backend: "pikafish", drawMode: "cells" });
    const mainBus = busModule.createPanelBus({ owner: "main", files: fakeFiles, now: function () { return 1000; } });
    const text = mainBus.describeOther();
    if (text.indexOf("正在跑") < 0) { return "没读到对方运行态：" + text; }
    if (text.indexOf("几何 v7") < 0) { return "状态行缺几何版本：" + text; }
    if (text.indexOf("Pikafish") < 0) { return "状态行缺引擎后端：" + text; }
    return true;
});

check("视觉工厂：没有 OCR 会话时不建流水线（不抛错，服务层会退回旧路径）", function () {
    const logs = [];
    const ctl = pipelineFactoryModule.create({
        adapter: {
            clip: function () { }, rgbAt: function () { }, countColor: function () { },
            prepareRegion: function () { }, releaseFrame: function () { }
        },
        recognizeBoard: { createDetectionSession: function () { return { detect: function () { return { ok: true, raw: [] }; } }; } },
        getOcrSession: function () { return null; },
        log: function (text) { logs.push(text); },
        pipelineLog: function () { }
    });
    const pipeline = ctl.ensure();
    if (pipeline !== null) {
        return "没有 OCR 会话时不应建流水线";
    }
    if (!logs.some(function (text) { return text.indexOf("没有 OCR 会话") >= 0; })) {
        return "应记录不可用原因：" + logs.join("｜");
    }
    return true;
});

check("视觉工厂：稳定帧采样必须返回数字数组（frame-gate 的输入契约）", function () {
    const adapter = { rgbAt: function () { return [10, 20, 30]; } };
    const samples = pipelineFactoryModule.createSamples(adapter);
    const out = samples("img", { originX: 0, originY: 0, stepX: 50, stepY: 50 });
    if (!out || !out.length) {
        return "采样不应为空";
    }
    if (typeof out[0] !== "number") {
        return "采样必须是数字数组，实际 " + typeof out[0];
    }
    if (samples(null, { originX: 0, originY: 0, stepX: 1, stepY: 1 }) !== null) {
        return "没有图时应当返回 null";
    }
    return true;
});

check("框架·装配：autorun 致命错误时 blocking=true；dispose 按 lockOwner 令牌还锁；app.bus = 注入实例", function () {
    const released = [];
    const myBus = bootstrapModule.createBus({ owner: "self-check" });   // 无 files → 空总线
    const app = bootstrapModule.createApp({
        owner: "self-check",
        lockOwner: "自检锁令牌",
        bus: myBus,
        adapter: {
            capture: function () { return null; },
            screenSize: function () { return { width: 1080, height: 2400 }; },
            rgbAt: function () { return [0, 0, 0]; }
        },
        host: {
            readFile: function () { return '{"clickMethod":"bogus"}'; },
            writeFile: function () { },
            sleep: function () { },
            now: function () { return 0; },
            win: { setVisibility: function () { return true; }, setPosition: function () { return true; } }
        },
        modules: {
            driverModule: { createDriver: function () { return { start: function () { return "X"; }, stop: function () { } }; } },
            locateBoard: {}, recognizeBoard: {}, geometry: {},
            loopLock: { acquire: function () { return true; }, release: function (token) { released.push(token); return true; }, isHeld: function () { return false; }, lockPath: function () { return "/tmp/lock"; } }
        },
        paths: { autorun: "/tmp/autorun.json" }
    });
    if (!app.blocking) {
        return "非法 clickMethod 应当让 blocking=true";
    }
    if (app.config.clickMethod !== "bogus") {
        return "非法值不该被改写：" + app.config.clickMethod;
    }
    if (app.bus !== myBus) {
        return "app.bus 应当是注入的同一实例";
    }
    app.dispose("测试");
    if (released.length !== 1) {
        return "dispose 没有释放循环锁";
    }
    return released[0] === "自检锁令牌" ? true : ("dispose 的锁令牌不对（旧 owner-channel 就是这里静默失败）：" + released[0]);
});

check("框架·装配：createBus 单创建口 —— 有 files 走 panel-bus，缺 files 是空总线（不抛错）", function () {
    const store = {};
    const fakeFiles = {
        getSdcardPath: function () { return "/sdcard"; },
        join: function (a, b) { return a + "/" + b; },
        ensureDir: function () { return true; },
        read: function (p) { return store[p] || ""; },
        write: function (p, t) { store[p] = String(t); return true; }
    };
    const realBus = bootstrapModule.createBus({ owner: "main", files: fakeFiles, now: function () { return 1000; } });
    if (realBus.publish({ running: true, mode: "auto" }) !== true) {
        return "有 files 时应当能发布状态";
    }
    if (!store["/sdcard/.aijspro-floaty/bus-state-main.json"]) {
        return "发布后应当写入 bus-state-main.json";
    }
    const nullBus = bootstrapModule.createBus({ owner: "floaty" });
    if (nullBus.publish({}) !== false) {
        return "缺 files 时应当是空总线（publish=false）";
    }
    if (nullBus.poll() !== null) {
        return "空总线 poll 应当返回 null";
    }
    if (nullBus.readOther().fresh !== false) {
        return "空总线 readOther 应当是不在线口径";
    }
    if (String(nullBus.describeOther()).indexOf("没有状态") < 0) {
        return "空总线描述应当说没有状态：" + nullBus.describeOther();
    }
    return true;
});

check("棋谱账本：只收已验证着法，positionCommand 从基准 FEN + moves 拼出", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const moves = legal.allLegalMoves(board, "w");
    if (!moves.length) { return "标准开局没有合法着法？"; }
    const first = moves[0];
    const applied = legal.applyMove(board, { iccs: first.iccs });
    const ledger = moveLedgerModule.createMoveLedger({});
    ledger.begin(fen.START_FEN, ledger.HISTORY.COMPLETE);
    const r = ledger.confirm({
        iccs: first.iccs, fromBoard: board, side: "w", afterBoard: applied.board, source: "our-click"
    });
    if (!r.ok) { return "合法着法应能入账：" + r.reason; }
    const cmd = ledger.positionCommand();
    if (cmd !== "position fen " + fen.START_FEN + " moves " + first.iccs) {
        return "命令不符：" + cmd;
    }
    return ledger.state().ply === 1 ? true : "ply 应为 1";
});

check("棋谱账本：棋规不合法的着法拒收（不入历史）", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const ledger = moveLedgerModule.createMoveLedger({});
    ledger.begin(fen.START_FEN, ledger.HISTORY.COMPLETE);
    const r = ledger.confirm({ iccs: "a0b1", fromBoard: board, side: "w", source: "our-click" });
    if (r.ok !== false) { return "非法着法不应入账"; }
    return ledger.state().ply === 0 ? true : "被拒着法不应写进历史";
});

check("棋谱账本：落子后盘面与验证值不一致拒收（容差格除外）", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const first = legal.allLegalMoves(board, "w")[0];
    const applied = legal.applyMove(board, { iccs: first.iccs }).board;
    const col = "abcdefghi".indexOf(first.iccs.charAt(2));
    const dest = (9 - Number(first.iccs.charAt(3))) * 9 + col;
    const tampered = applied.slice();
    tampered[dest] = null;
    const ledger = moveLedgerModule.createMoveLedger({});
    ledger.begin(fen.START_FEN, ledger.HISTORY.COMPLETE);
    const bad = ledger.confirm({
        iccs: first.iccs, fromBoard: board, side: "w", afterBoard: tampered, source: "our-click"
    });
    if (bad.ok !== false || !(bad.diffIndexes || []).length) {
        return "篡改盘面应被拒收并给出差异格";
    }
    const good = ledger.confirm({
        iccs: first.iccs, fromBoard: board, side: "w", afterBoard: tampered,
        source: "our-click", toleranceIndexes: bad.diffIndexes
    });
    return good.ok === true ? true : ("容差格内应放行：" + good.reason);
});

check("棋谱账本：中途接管 = partial 段（段基准 + 已验证着法，仍带不完整标记）", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const first = legal.allLegalMoves(board, "w")[0];
    const applied = legal.applyMove(board, { iccs: first.iccs }).board;
    const ledger = moveLedgerModule.createMoveLedger({});
    ledger.setPartial(fen.START_FEN, "测试接管");
    const r = ledger.confirm({
        iccs: first.iccs, fromBoard: board, side: "w", afterBoard: applied.board, source: "our-click"
    });
    if (!r.ok) { return "partial 段也应能收已验证着法：" + r.reason; }
    if (ledger.state().historyStatus !== "partial") { return "状态应为 partial"; }
    const cmd = ledger.positionCommand();
    return cmd && cmd.indexOf(" moves " + first.iccs) > 0 ? true : ("partial 段命令错：" + cmd);
});

check("事实跟踪器：开局开段 / 着法推进 / 原地改正降级 / 作废清账（版本单调）", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const first = legal.allLegalMoves(board, "w")[0];
    const applied = legal.applyMove(board, { iccs: first.iccs }).board;
    const tracker = positionTrackerModule.createPositionTracker({});
    const a = tracker.adopt({ board: board, side: "w", reason: "test" });
    if (a.historyStatus !== "complete") { return "开局应开完整段：" + a.historyStatus; }
    const v0 = tracker.snapshot().version;
    const c = tracker.confirmMove({
        iccs: first.iccs, fromBoard: board, side: "w", afterBoard: applied, source: "our-click"
    });
    if (!c.ok) { return "着法应入账：" + c.reason; }
    const snap = tracker.snapshot();
    if (snap.version <= v0) { return "版本应递增"; }
    if (!snap.position || snap.position.side !== "b") {
        return "推进后应轮到黑方：" + JSON.stringify(snap.position && snap.position.side);
    }
    if (String(snap.positionCommand).indexOf(" moves " + first.iccs) < 0) { return "命令应带着法：" + snap.positionCommand; }
    const f = tracker.fix({ board: board.slice(), side: "w", reason: "resync-test" });
    if (!f.ok || tracker.snapshot().historyStatus !== "partial") { return "fix 应降级为 partial"; }
    if (tracker.snapshot().ledger.ply !== 0) { return "fix 应从纠正后的局面重开段"; }
    if (tracker.snapshot().positionCommand !== "position fen " + tracker.snapshot().fen) {
        return "fix 后历史命令应以纠正后的局面为基准";
    }
    tracker.invalidate("new-session-test", { resetLedger: true });
    const end = tracker.snapshot();
    return (!end.position && end.historyStatus === "unknown") ? true : ("作废后应空白：" + end.historyStatus);
});

check("事实跟踪器：标准布局但黑方先行不能伪装成完整开局历史", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const tracker = positionTrackerModule.createPositionTracker({});
    const adopted = tracker.adopt({ board: board, side: "b", reason: "black-to-move" });
    const snap = tracker.snapshot();
    const expected = "position fen " + fen.buildFen(board, "b");
    return adopted.ok && snap.historyStatus === "partial" && snap.positionCommand === expected
        ? true : ("错误地采用红先开局：" + JSON.stringify(snap));
});

check("事实跟踪器：容差确认后棋谱与当前盘面不同，必须重开部分历史段", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const first = legal.allLegalMoves(board, "w")[0];
    const applied = legal.applyMove(board, { iccs: first.iccs }).board;
    const dest = (9 - Number(first.iccs.charAt(3))) * 9 +
        "abcdefghi".indexOf(first.iccs.charAt(2));
    const corrected = applied.slice();
    corrected[dest] = null;
    const tracker = positionTrackerModule.createPositionTracker({});
    tracker.adopt({ board: board, side: "w", reason: "test" });
    const confirmed = tracker.confirmMove({
        iccs: first.iccs, fromBoard: board, side: "w",
        afterBoard: corrected, toleranceIndexes: [dest]
    });
    const snap = tracker.snapshot();
    const expected = "position fen " + fen.buildFen(corrected, "b");
    return confirmed.ok && snap.historyStatus === "partial" &&
        snap.ledger.ply === 0 && snap.positionCommand === expected
        ? true : ("容差后的棋谱仍与当前盘面冲突：" + JSON.stringify(snap));
});

check("事实跟踪器：与当前局面不连续的着法拒收（失败不推进版本）", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const first = legal.allLegalMoves(board, "w")[0];
    const applied = legal.applyMove(board, { iccs: first.iccs }).board;
    const tracker = positionTrackerModule.createPositionTracker({});
    tracker.adopt({ board: board, side: "w", reason: "test" });
    const version = tracker.snapshot().version;
    const r = tracker.confirmMove({
        iccs: first.iccs, fromBoard: applied, side: "w", afterBoard: applied, source: "our-click"
    });
    if (r.ok !== false) { return "不连续/非法的着法不应入账"; }
    if (tracker.snapshot().version !== version) { return "拒收不应推进版本"; }
    return tracker.stats().rejects === 1 ? true : ("拒收计数应为 1，实际 " + tracker.stats().rejects);
});

check("驱动·账本影子：tracker() 访问器存在；newSession 后账本空白", function () {
    const driver = driverModule.createDriver({
        allowClick: false,
        recognize: function () {
            return { ok: true, chars: START_BOARD.slice(), unknownCells: 0, conflicts: 0, layout: START_FEN };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: true, iccs: OWN_MOVE.iccs, move: OWN_MOVE, score: 0, depth: 1, ms: 1 }; },
        config: { movetimeMs: 20 }
    });
    if (typeof driver.tracker !== "function") { return "驱动缺少 tracker() 访问器"; }
    const before = driver.tracker();
    if (!before || before.position !== null || before.historyStatus !== "unknown") {
        return "初始账本应当空白：" + JSON.stringify(before);
    }
    driver.newSession();
    const after = driver.tracker();
    return (after && after.position === null && after.historyStatus === "unknown")
        ? true : ("newSession 后账本应空白：" + JSON.stringify(after));
});

check("尝试预算：同着法/同局面超限拦截，清零后从头计数", function () {
    const budget = attemptBudgetModule.createAttemptBudget({ limitPerMove: 2, limitPerPosition: 10 });
    const ticket = { sessionId: 1, baseFen: "FEN", baseSide: "w", moveICCS: "a0a1" };
    const first = budget.take(ticket);
    const second = budget.take(ticket);
    const third = budget.take(ticket);
    if (first.over || second.over) { return "限额内不应拦截"; }
    if (!third.over || String(third.reason).indexOf("a0a1") < 0) {
        return "超限应拦截并带着法：" + third.reason;
    }
    budget.clear("测试");
    const again = budget.take(ticket);
    if (again.over || again.moveCount !== 1) { return "清零后应从头计数：" + again.moveCount; }
    const posBudget = attemptBudgetModule.createAttemptBudget({ limitPerMove: 10, limitPerPosition: 2 });
    posBudget.take({ sessionId: 1, baseFen: "FEN", baseSide: "w", moveICCS: "a0a1" });
    posBudget.take({ sessionId: 1, baseFen: "FEN", baseSide: "w", moveICCS: "b0b1" });
    const posThird = posBudget.take({ sessionId: 1, baseFen: "FEN", baseSide: "w", moveICCS: "c0c1" });
    return (posThird.over && String(posThird.reason).indexOf("局面") >= 0)
        ? true : ("局面限额应生效：" + posThird.reason);
});

check("跨帧佐证门：同一解释要连续两帧；应手同理；换键/ reset 重新计数", function () {
    let obs = 1;
    const gate = crossFrameGateModule.createCrossFrameGate({ sample: function () { return obs; } });
    const first = gate.supportedByTwoFrames(["board"], "唯一合法单步 a0a1");
    if (first.ok || !first.wait) { return "第一帧应当暂缓"; }
    obs = 2;
    const second = gate.supportedByTwoFrames(["board"], "唯一合法单步 a0a1");
    if (!second.ok) { return "第二帧（新观测序号）应当通过"; }
    const other = gate.supportedByTwoFrames(["board"], "唯一合法单步 b0b1");
    if (other.ok) { return "换了键应当重新计数"; }
    const r1 = gate.replyCorroborated("a0a1>b0b1>-");
    const r2 = gate.replyCorroborated("a0a1>b0b1>-");
    if (r1.ok || !r2.ok) { return "应手也要两次佐证"; }
    if (gate.replyCorroborated(null).ok !== false) { return "空键不应通过"; }
    gate.reset("测试");
    return gate.supportedByTwoFrames(["board"], "唯一合法单步 a0a1").ok ? "reset 后应重新计数" : true;
});

check("连续帧共识账本：同帧重复不刷数 / 跨帧同票+1 / 换字矛盾票重置 / drop 清格", function () {
    const cons = frameConsensusModule.createFrameConsensus({});
    cons.beginFrame(100);
    const a1 = cons.vote(5, "C", 2);
    const a2 = cons.vote(5, "C", 2);
    if (a1.ok || a1.frames !== 1) { return "首票应 1 帧、未过闸"; }
    if (a2.frames !== 1 || a2.ok) { return "同一帧重复问不应刷计数：" + JSON.stringify(a2); }
    cons.beginFrame(101);
    const b = cons.vote(5, "C", 2);
    if (!b.ok || b.frames !== 2) { return "跨帧同票应过闸：" + JSON.stringify(b); }
    cons.beginFrame(102);
    const c = cons.vote(5, "c", 2);
    if (!c.unstable || c.frames !== 1) { return "换字票应 unstable 且重置为 1：" + JSON.stringify(c); }
    const c2 = cons.vote(5, "c", 2);
    if (!c2.unstable || c2.frames !== 1) { return "不稳的票再重复也不该算稳：" + JSON.stringify(c2); }
    cons.drop(5);
    const d = cons.vote(5, "C", 2);
    if (d.frames !== 1) { return "drop 后应从 1 重新计数：" + JSON.stringify(d); }
    cons.clear();
    return cons.snapshot().gate["5"] === undefined ? true : "clear 应清空共识";
});

check("已知事实表：register 去重替换保持位置 / drop 计数与回调 / applyTo 只补 '?' / charAt", function () {
    const facts = knownFactsModule.createKnownFacts();
    if (facts.upsert({ index: 3, char: "C", cycle: 1, ply: 0 }) !== false) { return "新记录应返回 false（追加）"; }
    if (facts.upsert({ index: 5, char: "c", cycle: 1, ply: 0 }) !== false) { return "第二条也应追加"; }
    if (facts.upsert({ index: 3, char: "R", cycle: 2, ply: 1 }) !== true) { return "同 index 应返回 true（替换）"; }
    if (facts.count() !== 2) { return "替换不应增加条数：" + facts.count(); }
    const listed = facts.list();
    if (listed[0].index !== 3 || listed[0].char !== "R") { return "替换应保持位置且用新值"; }
    const chars = ["?", "x", "?", "?", "?", "?"];
    const applied = facts.applyTo(chars);
    if (applied[3] !== "R" || applied[5] !== "c") { return "applyTo 应把 '?' 补成记录值"; }
    if (applied[1] !== "x") { return "已读出的字不应被覆盖"; }
    if (chars[3] !== "?") { return "applyTo 不应就地改原数组"; }
    if (facts.charAt(3) !== "R" || facts.charAt(0) !== null) { return "charAt 口径错"; }
    facts.upsert({ index: 7, char: null, cycle: 2, ply: 1 });
    if (facts.charAt(7) !== null) { return "空网格记录不算「有字」"; }
    const seen = [];
    const dropped = facts.drop([3], function (patch) { seen.push(patch.index); });
    if (dropped !== 1 || seen[0] !== 3) { return "drop 计数/回调错：" + dropped + "/" + seen.join(","); }
    if (facts.count() !== 2 || facts.charAt(3) !== null) { return "drop 后应移除该格"; }
    facts.clear();
    return facts.count() === 0 ? true : "clear 应清空";
});

check("棋谱账本/跟踪器：join 碰撞的盘面必须能区分；同一手的重复确认幂等不重复记账", function () {
    // 2026-09-23 回放用例抓到的回归：`a0a1`（车只上一格）前后两盘 join("") 是**同一串**
    //（空行被折叠）⇒ 连续性/镜像检查全部误判通过 → 同一手记了两遍。
    const board = fen.parseFen(fen.START_FEN).board;
    const move = legal.allLegalMoves(board, "w")[0];
    const after = legal.applyMove(board, { iccs: move.iccs }).board;
    if (board.join("") !== after.join("")) {
        return "夹具前提变了：" + move.iccs + " 前后 join 不再相同（换别的着法或更新本断言）";
    }
    if (moveLedgerModule.cellsKey(board) === moveLedgerModule.cellsKey(after)) {
        return "cellsKey 必须能区分这两盘（null 安全）";
    }
    const tracker = positionTrackerModule.createPositionTracker({});
    tracker.adopt({ board: board, side: "w", reason: "test" });
    const first = tracker.confirmMove({
        iccs: move.iccs, fromBoard: board, side: "w", afterBoard: after, source: "our-click"
    });
    if (!first.ok || first.duplicate) { return "首手应正常入账：" + JSON.stringify(first); }
    const again = tracker.confirmMove({
        iccs: move.iccs, fromBoard: board, side: "w", afterBoard: after, source: "our-click"
    });
    if (!again.ok || again.duplicate !== true) {
        return "同一手的重复确认应幂等放行（duplicate）：" + JSON.stringify(again);
    }
    const snap = tracker.snapshot();
    if (snap.ledger.ply !== 1) { return "重复确认不应重复记账：" + snap.ledger.ply; }
    if (tracker.stats().duplicates !== 1 || tracker.stats().rejects !== 0) {
        return "重复/拒收计数不对：" + JSON.stringify(tracker.stats());
    }
    return true;
});

// ---------------------------------------------------------------- 识别绘制（人审用）

check("识别绘制：网格线正好落在交点/路上（19 条线，位置与几何一致）", function () {
    const items = overlayDraw.gridBoxes(TEST_GRID, { lineThickness: 2, linePadding: 20 });
    if (items.length !== 19) {
        return "应有 10 横 + 9 竖 = 19 条线，实际 " + items.length;
    }
    const horizontal = items.filter(function (item) { return (item.y2 - item.y1) <= 2.1; });
    if (horizontal.length !== 10) {
        return "横线应有 10 条，实际 " + horizontal.length;
    }
    // 第 0 条横线的中心 y 必须等于 originY（人审就是看这条线有没有穿过顶排棋子圆心）
    const first = horizontal[0];
    const centerY = (first.y1 + first.y2) / 2;
    if (Math.abs(centerY - TEST_GRID.originY) > 0.01) {
        return "第 0 条横线没落在 originY 上：" + centerY + " vs " + TEST_GRID.originY;
    }
    // 第 8 条竖线的中心 x = originX + 8*stepX
    const vertical = items.filter(function (item) { return (item.x2 - item.x1) <= 2.1; });
    if (vertical.length !== 9) {
        return "竖线应有 9 条，实际 " + vertical.length;
    }
    const lastX = (vertical[8].x1 + vertical[8].x2) / 2;
    if (Math.abs(lastX - (TEST_GRID.originX + 8 * TEST_GRID.stepX)) > 0.01) {
        return "第 8 条竖线位置不对：" + lastX;
    }
    return true;
});

check("识别绘制：每格标签画在格心、状态编进文字（? / X!）", function () {
    const start = fen.parseFen(fen.START_FEN).board.slice();
    const at = function (items, row, col) {
        const center = overlayDraw.centerOf(TEST_GRID, row, col);
        return items.filter(function (item) {
            return Math.abs((item.x1 + item.x2) / 2 - center.x) < 0.01 &&
                Math.abs((item.y1 + item.y2) / 2 - center.y) < 0.01;
        })[0];
    };
    // ① 只画观测：未知格标签就是 '?'
    const unknownBoard = start.slice();
    unknownBoard[0] = "?";
    const plain = overlayDraw.cellBoxes(TEST_GRID, unknownBoard, { cellBoxSize: 16 });
    const unknownCell = at(plain, 0, 0);
    if (!unknownCell) {
        return "(0,0) 那格没有画出来";
    }
    if (unknownCell.label !== "?") {
        return "未知格的标签应该有且只有 ?，实际 " + unknownCell.label;
    }
    // ② 给了期望盘面：与期望不同的格子要标 X!（人审最想看的就是这一格）
    const wrongBoard = start.slice();
    wrongBoard[1] = "R";                 // 期望是黑马 n
    const withExpect = overlayDraw.cellBoxes(TEST_GRID, wrongBoard, {
        cellBoxSize: 16, expectChars: start
    });
    const mismatch = withExpect.filter(function (item) { return item.label.indexOf("X!") === 0; });
    if (mismatch.length !== 1) {
        return "期望 vs 观测只有 1 处不同，应恰好标 1 个 X!，实际 " + mismatch.length;
    }
    if (mismatch[0].label.indexOf("R") < 0 || mismatch[0].label.indexOf("n") < 0) {
        return "X! 标签要带上「读到什么→期望什么」，实际 " + mismatch[0].label;
    }
    return true;
});

check("识别绘制：模式决定画什么（grid 只网格 / full 加检测框）", function () {
    const chars = fen.parseFen(fen.START_FEN).board.slice();
    const result = {
        chars: chars,
        detectionCount: 2,
        detections: [{ x: 200, y: 300, width: 90, height: 90, label: "N", score: 0.91 },
            { x: 400, y: 500, width: 90, height: 90, label: "R", score: 0.77 }]
    };
    const gridOnly = overlayDraw.build({ grid: TEST_GRID, result: result, mode: "grid" });
    if (gridOnly.items.length !== 19) {
        return "grid 模式应只有 19 条线，实际 " + gridOnly.items.length;
    }
    const cells = overlayDraw.build({ grid: TEST_GRID, result: result, mode: "cells" });
    if (cells.items.length <= gridOnly.items.length) {
        return "cells 模式应比 grid 多出格子标签";
    }
    const full = overlayDraw.build({ grid: TEST_GRID, result: result, mode: "full" });
    if (full.items.length <= cells.items.length) {
        return "full 模式应再带上原始检测框与空格标记";
    }
    const withPlan = overlayDraw.build({
        grid: TEST_GRID, result: result, mode: "cells",
        points: [{ x: 196, y: 1495, label: "▶b2" }, { x: 311, y: 1495, label: "◀c2" }]
    });
    const arrow = withPlan.items.filter(function (item) { return item.label.indexOf("▶") === 0; });
    if (arrow.length !== 1 || Math.abs((arrow[0].x1 + arrow[0].x2) / 2 - 196) > 0.01) {
        return "点击落点的框没画对";
    }
    if (full.text.indexOf("绘制 full") !== 0) {
        return "HUD 文字没带模式：" + full.text;
    }
    return true;
});

check("截图通道：setCaptureBackend 会归一转值，且没有 storages 时不抛异常（离线自检就是这个环境）", function () {
    const back = adapter.setCaptureBackend;
    if (typeof back !== "function") {
        return "适配层没有 setCaptureBackend";
    }
    const before = adapter.captureBackend();
    const shell = back("shell", { persist: false });
    if (shell !== "shell") {
        return "设 shell 却返回 " + shell;
    }
    const bogus = back("不存在的通道", { persist: false });
    if (bogus !== "auto") {
        return "非法值应当归一到 auto，实际 " + bogus;
    }
    back(before, { persist: false });   // 恢复，别影响后面的用例
    return true;
});

check("截图通道：captureChannel() 默认是「还没截图」（界面靠它区分「偏好」和「实际用上」）", function () {
    if (typeof adapter.captureChannel !== "function") {
        return "适配层没有 captureChannel";
    }
    const used = adapter.captureChannel();
    return used === null || used === undefined || typeof used === "string"
        ? true
        : ("返回值类型不对：" + typeof used);
});

// ---------------------------------------------------------------- 落点格被游戏画花（真机：小米8）

check("落点补齐：未知格恰好是我方落点、其它 89 格全对 → 允许确认（兵种来自票据，不是猜）", function () {
    const ticket = sampleTicket();
    const dest = verifier.destinationIndex(ticket);
    if (dest == null) {
        return "destinationIndex 没算出来（ICCS=" + ticket.moveICCS + "）";
    }
    const observed = AFTER_BOARD.slice();
    observed[dest] = "?";                     // 游戏给刚落的子加高亮 → 那格认不出来
    const verdict = verifier.verify({
        ticket: ticket,
        observed: observed,
        unknownCells: 1,
        stable: true,
        geometryTrusted: true,
        conflicts: 0
    });
    if (verdict.result !== verifier.RESULT.CONFIRMED) {
        return "应当确认，实际 " + verdict.result + "：" + verdict.reason;
    }
    if (!verdict.patched || verdict.patched.index !== dest) {
        return "没有报告补齐的格子：" + JSON.stringify(verdict.patched);
    }
    if (verdict.board[dest] !== AFTER_BOARD[dest]) {
        return "补齐后的局面不对：落点是 " + verdict.board[dest];
    }
    return true;
});

check("落点补齐：未知格在别处（对方某枚子的原位）⇒ 唯一解释仍成立 ⇒ 允许确认", function () {
    // ⚠️ 2026-09-20 改：旧断言要求「未知格不在落点上 ⇒ 一律 UNRELIABLE」（真机每次白烧 ~15s 恢复）。
    //   新判据见上一条：只要**只有一种解释**对得上所有看得清的格，就采信。
    const ticket = sampleTicket();
    const dest = verifier.destinationIndex(ticket);
    const observed = AFTER_BOARD.slice();
    const other = dest === 0 ? 1 : 0;
    observed[other] = "?";
    const verdict = verifier.verify({
        ticket: ticket,
        observed: observed,
        unknownCells: 1,
        stable: true,
        geometryTrusted: true,
        conflicts: 0
    });
    if (verdict.result !== verifier.RESULT.CONFIRMED) {
        return "应当确认（唯一解释），实际 " + verdict.result + "：" + verdict.reason;
    }
    return verdict.board[other] === AFTER_BOARD[other]
        ? true
        : ("看不清的格要按票据/棋规补齐，实际 " + verdict.board[other]);
});

check("落点补齐：落点未知但局面根本不是这一手（起点没清空）→ 不许确认", function () {
    const ticket = sampleTicket();
    const dest = verifier.destinationIndex(ticket);
    const observed = START_BOARD.slice();     // 还停在走之前
    observed[dest] = "?";
    const verdict = verifier.verify({
        ticket: ticket,
        observed: observed,
        unknownCells: 1,
        stable: true,
        geometryTrusted: true,
        conflicts: 0
    });
    return verifier.isSuccess(verdict.result) === false
        ? true
        : ("这种情况不该算走成了：" + verdict.result);
});

// ---------------------------------------------------------------- 视觉架构重构（方案：YOLO/OCR/颜色/规则 四权分离）
// 2026-09-19：这三块是方案 §3.1/§3.5/§3.9 的落地，用例对应真机踩过的坑。

const pieceOcrModule = require("../src/vision/piece-ocr.js");
const ocrRuntimeModule = require("../src/vision/ocr-runtime.js");
const frameGateModule = require("../src/vision/frame-gate.js");
const boardValidator = require("../src/vision/board-validator.js");
const pieceColorModule = require("../src/vision/piece-color.js");
const pieceCropper = require("../src/vision/piece-cropper.js");
const pieceDetector = require("../src/vision/piece-detector.js");
const gridTracker = require("../src/vision/grid-tracker.js");
const evidenceFusion = require("../src/vision/evidence-fusion.js");
const visionPipelineModule = require("../src/vision/vision-pipeline.js");
const fakeVisionHost = require("./fake-vision-host.js");

check("视觉流水线（假宿主三帧）：要真出盘面 —— 框分要进融合、融合器要跨帧累积（2026-09-19 两次组装事故）", function () {
    const host = fakeVisionHost.createFakeHost();
    const pipeline = visionPipelineModule.create({
        detect: host.detect,
        clip: host.clip,
        rgbAt: host.rgbAt,
        ocrSession: host.ocrSession,
        log: function () { }
    });
    let result = null;
    for (let i = 0; i < 3; i++) {
        result = pipeline.recognize({}, host.grid, { side: "w" });
    }
    if (!result) {
        return "流水线没有返回结果";
    }
    // ⚠️ 事故 1：cropAll 不带分数 ⇒ detectionScore 恒为 null ⇒ 综合分封顶 0.70
    const zeroScore = (result.evidence || []).filter(function (one) { return !(one.detectionScore > 0); });
    if (zeroScore.length) {
        return "有 " + zeroScore.length + " 枚棋子的 detectionScore 不是正数（框分没接进证据）";
    }
    // ⚠️ 事故 2：每帧新建融合器 ⇒ 永远只有一票 ⇒ 确认数恒为 0
    if (!(result.fusion && result.fusion.confirmed >= 3)) {
        return "三帧之后应当至少确认 3 格，实际 " + JSON.stringify(result.fusion) +
            "（融合器没跨帧累积？）";
    }
    if (result.chars[0] !== "K") {
        return "(0,0) 应当是红帅 K，实际 " + result.chars[0];
    }
    if (result.chars[1] !== "k") {
        return "(1,0) 应当是黑将 k，实际 " + result.chars[1];
    }
    // 事故 3：(2,0) 的 OCR 读成 “H”（真机就是 0.85 读成 H）。
    // ⚠️ 2026-09-20 用户口径：**YOLO 只负责定位（哪儿有子），兵种只认 OCR 字形** ——
    //    所以“士被读成 H”要在**字形表**里收（piece-ocr 的系统性误读表），
    //    不再拿检测框的类别字母兜底。
    if (result.chars[2] !== "a") {
        return "(2,0) 应当是黑士 a（字形表把 “H” 归到士），实际 " + result.chars[2];
    }
    const yoloCls = (result.evidence || []).filter(function (one) {
        return one.clsSource === "yolo" || one.clsSource === "ocr+yolo";
    });
    if (yoloCls.length !== 0) {
        return "YOLO 的类别不许参与兵种判定，实际有 " + yoloCls.length + " 格带 yolo 来源";
    }
    return true;
});

check("视觉流水线：YOLO 只负责定位 —— 框说炮/字形说车就得认车，OCR 认不出也不拿框顶", function () {
    const host = fakeVisionHost.createFakeHost({
        pieces: [
            // 框说 C（炮），字形说「车」 ⇒ 必须认「车」
            { col: 0, row: 0, label: "C", ocr: { text: "车", score: 0.92 }, side: "red" },
            // 字形认不出（返回一个不在表里的字，分再高也没用）⇒ 那格保持未知，**不许**用框的类别顶
            { col: 2, row: 0, label: "R", ocr: { text: "霾", score: 0.99 }, side: "red" }
        ]
    });
    const pipeline = visionPipelineModule.create({
        detect: host.detect,
        clip: host.clip,
        rgbAt: host.rgbAt,
        ocrSession: host.ocrSession,
        log: function () { }
    });
    let result = null;
    for (let i = 0; i < 3; i++) {
        result = pipeline.recognize({}, host.grid, { side: "w" });
    }
    const boxed = (result.evidence || []).filter(function (one) { return one.col === 0 && one.row === 0; })[0];
    if (!boxed) {
        return "没拿到 (0,0) 的证据";
    }
    if (boxed.cls !== "R") {
        return "兵种必须来自 OCR 字形（车 ⇒ R），实际 " + boxed.cls + "（来源 " + boxed.clsSource + "）";
    }
    if (boxed.clsSource !== "ocr") {
        return "类别来源应当只有 ocr，实际 " + boxed.clsSource;
    }
    if (result.rawChars[0] !== "R") {
        return "盘面 (0,0) 应当是红车 R，实际 " + result.rawChars[0];
    }
    const unreadable = (result.evidence || []).filter(function (one) { return one.col === 2 && one.row === 0; })[0];
    if (unreadable && unreadable.cls) {
        return "OCR 认不出时不许拿框的类别顶上，实际填了 " + unreadable.cls + "（来源 " + unreadable.clsSource + "）";
    }
    return true;
});

check("视觉流水线：OCR 先灰度 —— 灰度主读救回环格；灰度读不出的格再用彩色兜底（2026-09-21）", function () {
    // 2026-09-21 用户口径「先灰度」：主读走灰度（clip+gray），彩色只在兜底里出现（方向与老顺序相反）。
    // 探针实测两边互有胜负（灰度会把馬读成「志」、兵读成「民」）⇒ 兜底保留：
    // 灰度读不出 / 分过低被丢时，用彩色（整帧+region）重读一次。
    const host = fakeVisionHost.createFakeHost({
        pieces: [
            // 环格：彩色读不出（不在字形表里），灰度是正经的「马」（黑方马）
            { col: 0, row: 0, label: "N", ocr: { text: "霾", score: 0.99 }, ocrGray: { text: "马", score: 0.88 }, side: "black" },
            // 对照格：彩色就读得出 ⇒ 不该再花一次灰度
            { col: 2, row: 0, label: "R", ocr: { text: "车", score: 0.92 }, side: "red" },
            // ⚠️ 真机事故（2026-09-20）：灰度读出**像个字**但只有 0.24 分 —— 当初不加最低分就采纳，
            //    观测层多了一枚幽灵「卒」，后半局钉死在「装饰容差下有 N 种应手解释，不唯一」。
            //    这种读数必须丢掉（那格保持未知），而不是写一个错兵种进去。
            { col: 4, row: 0, label: "P", ocr: { text: "霾", score: 0.99 }, ocrGray: { text: "卒", score: 0.24 }, side: "black" }
        ]
    });
    const pipeline = visionPipelineModule.create({
        detect: host.detect,
        clip: host.clip,
        rgbAt: host.rgbAt,
        countColor: host.countColor,
        prepareRegion: host.prepareRegion,
        releaseFrame: host.releaseFrame,
        ocrSession: host.ocrSession,
        log: function () { }
    });
    let result = null;
    for (let i = 0; i < 3; i++) {
        result = pipeline.recognize({}, host.grid, { side: "w" });
    }
    const ring = (result.evidence || []).filter(function (one) { return one.col === 0 && one.row === 0; })[0];
    if (!ring) {
        return "没拿到 (0,0) 的证据";
    }
    if (ring.cls !== "N") {
        // 证据层的 cls 是**兵种**（大写，不带颜色），颜色由 side 另外记
        return "灰度重试之后 (0,0) 应当是马 N，实际 " + ring.cls + "（ocrState=" + ring.ocrState + "）";
    }
    if (result.chars[0] !== "n") {
        return "盘面 (0,0) 应当是黑马 n（颜色由 side 上上去），实际 " + result.chars[0];
    }
    const stats = pipeline.stats();
    if (stats.grayPrimary < 3) {
        return "三格都该先走灰度主读，实际 " + stats.grayPrimary + " 格";
    }
    if (stats.colorRetries !== 1 || stats.colorRetryHits !== 0) {
        // 只有 (4,0)（灰度 0.24 被丢）才轮到彩色兜底；而且彩色也读不出「霾」⇒ 0 救回
        return "只该对一格格做彩色兜底（(4,0)），实际 retries=" + stats.colorRetries + " hits=" + stats.colorRetryHits;
    }
    if (stats.grayLow !== 1) {
        return "低分灰度读数应当计 1 次被丢，实际 " + stats.grayLow;
    }
    const lowCell = (result.evidence || []).filter(function (one) { return one.col === 4 && one.row === 0; })[0];
    if (lowCell && lowCell.cls) {
        return "灰度只有 0.24 分时宁可未知，不许写兵种，实际填了 " + lowCell.cls;
    }
    if (result.chars[4] !== "?") {
        return "盘面 (4,0) 应当是 `?`（低分读数丢弃），实际 " + result.chars[4];
    }
    if (host.stats().leaked !== 0) {
        // 原生帧不会 GC：灰度重试裁出来的每一帧都必须 releaseFrame
        return "灰度重试裁出来的临时帧必须还回去，实际泄漏 " + host.stats().leaked + " 帧";
    }
    // 老宿主（没有 prepareRegion）：不许崩，也不许把格子填成别的 —— 保持未知走老路
    const oldHost = fakeVisionHost.createFakeHost({
        pieces: [{ col: 0, row: 0, label: "N", ocr: { text: "霾", score: 0.99 }, ocrGray: { text: "马", score: 0.88 }, side: "black" }]
    });
    const oldPipeline = visionPipelineModule.create({
        detect: oldHost.detect,
        clip: oldHost.clip,
        rgbAt: oldHost.rgbAt,
        countColor: oldHost.countColor,
        ocrSession: oldHost.ocrSession,
        log: function () { }
    });
    let oldResult = null;
    for (let i = 0; i < 3; i++) {
        oldResult = oldPipeline.recognize({}, oldHost.grid, { side: "w" });
    }
    if (oldResult.chars[0] !== "?") {
        return "宿主没有 prepareRegion 时应当保持未知（走老路），实际 " + oldResult.chars[0];
    }
    if (oldPipeline.stats().grayRetries !== 0 || oldPipeline.stats().grayPrimary !== 0) {
        return "宿主没有 prepareRegion 时不该走灰度路，实际 grayPrimary=" + oldPipeline.stats().grayPrimary +
            " grayRetries=" + oldPipeline.stats().grayRetries;
    }
    return true;
});

check("裁剪以检测框为准：框偏一点就跟它走，框跑太远退回格中心（方案 §3.2）", function () {
    const grid = { originX: 100, originY: 100, stepX: 120, stepY: 120, orientation: "RED_BOTTOM" };
    const near = pieceCropper.boxForItem(grid, {
        index: 0, col: 0, row: 0, residual: 0.1,
        box: { centerX: 118, centerY: 104, width: 112, height: 110 }
    });
    if (!near || !near.fromDetector) {
        return "残差 0.1 格时应当采用检测框，实际 " + JSON.stringify(near);
    }
    if (Math.abs(near.centerX - 118) > 1 || Math.abs(near.centerY - 104) > 1) {
        return "裁块中心应当跟着检测框，实际 (" + near.centerX + "," + near.centerY + ")";
    }
    if (!(near.width <= Math.round(120 * 0.95 * 0.82) + 1)) {
        return "裁块边长应当被夹到 maxDetectorSize×cropRatio 之内，实际 " + near.width;
    }
    const far = pieceCropper.boxForItem(grid, {
        index: 0, col: 0, row: 0, residual: 0.6,
        box: { centerX: 260, centerY: 104, width: 112, height: 110 }
    });
    if (far.fromDetector) {
        return "残差 0.6 格（明显跑掉）应当退回格中心";
    }
    if (Math.abs(far.centerX - 100) > 1) {
        return "退回后中心应当是交点 (100,100)，实际 " + far.centerX;
    }
    return true;
});

check("视觉流水线：逐格变化缓存 —— 没变的格不该再 OCR，但结论要一样（方案 §7 的预算）", function () {
    const host = fakeVisionHost.createFakeHost();
    const pipeline = visionPipelineModule.create({
        detect: host.detect,
        clip: host.clip,
        rgbAt: host.rgbAt,
        ocrSession: host.ocrSession,
        log: function () { }
    });
    let first = null;
    let second = null;
    for (let i = 0; i < 3; i++) {
        first = pipeline.recognize({}, host.grid, { side: "w" });
    }
    for (let i = 0; i < 1; i++) {
        second = pipeline.recognize({}, host.grid, { side: "w" });
    }
    const cached = (second.stats && second.stats.cellsCached) || 0;
    if (cached < 3) {
        return "第 4 帧应当有 3 格命中缓存（像素指纹不变），实际 " + cached;
    }
    if (second.chars[0] !== first.chars[0] || second.chars[1] !== first.chars[1]) {
        return "命中缓存后结论应当不变，实际 " + JSON.stringify([first.chars[0], second.chars[0]]);
    }
    // 几何一变，缓存必须作废。
    // ⚠️ 偏移量别用 5 的倍数：假宿主的像素是按 (dx+dy)%5 造的，偏移 5 会得到**完全相同**的
    //    采样指纹，测出来像是在缓存没失效（这是测试数据退化，不是缓存坏了）。
    const before = (second.stats && second.stats.cellsCached) || 0;
    const moved = Object.assign({}, host.grid, { originX: host.grid.originX + 3, version: 2 });
    const third = pipeline.recognize({}, moved, { side: "w" });
    const delta = ((third.stats && third.stats.cellsCached) || 0) - before;
    if (delta !== 0) {
        return "几何变了之后缓存应当作废（这一帧命中增量应为 0），实际增量 " + delta;
    }
    return true;
});

check("视觉流水线：帅/将字形分不够时，要靠「两路一致 + 九宫约束」补全（不是猜）", function () {
    // 造一个「帅那格只有 0.5 分、检测标签也没给出 K」的盘（真机就是这样：
    // 九宫斜线压着字形，PP-OCR 只给 0.51 分）。label 置空是为了避开「两路一致加权」那条捷径。
    const host = fakeVisionHost.createFakeHost({
        pieces: [
            { col: 4, row: 9, label: null, ocr: { text: "帅", score: 0.51 }, side: "red" },
            { col: 3, row: 9, label: "A", ocr: { text: "仕", score: 0.95 }, side: "red" },
            { col: 4, row: 0, label: "K", ocr: { text: "将", score: 0.95 }, side: "black" }
        ]
    });
    const pipeline = visionPipelineModule.create({
        detect: host.detect,
        clip: host.clip,
        rgbAt: host.rgbAt,
        ocrSession: host.ocrSession,
        log: function () { }
    });
    let result = null;
    for (let i = 0; i < 3; i++) {
        result = pipeline.recognize({}, host.grid, { side: "w" });
    }
    // (4,9) 的索引 = 9*9 + 4 = 85
    // 字段契约（2026-09-20 评审）：`chars` = **补全后**的盘面；`rawChars` = 融合层原样输出。
    if (result.rawChars[85] !== "?") {
        return "融合层原始输出第 85 格（4,9）应该还是 `?`（字形分只有 0.51，不够 0.78），实际 " + result.rawChars[85];
    }
    if (result.chars[85] !== "K") {
        return "规则层应当把 (4,9) 补全为红帅 K，实际 " + result.chars[85];
    }
    if (result.board[85] !== "K" || result.chars === result.rawChars) {
        return "board 与 chars 必须一致（且不等于 rawChars），实际 board=" + result.board[85];
    }
    if (!(result.stats && result.stats.inferred >= 1)) {
        return "补全次数应当计入 stats.inferred，实际 " + JSON.stringify(result.stats && result.stats.inferred);
    }
    if (!result.validation || !result.validation.ok) {
        return "补齐帅之后规则校验应当通过，实际 " +
            JSON.stringify(result.validation && result.validation.reason);
    }
    return true;
});

check("字形分类：真机系统性误读要认（「兵」被读成「宾」、「士」被读成 H）", function () {
    const hit = pieceOcrModule.classOf("宾");
    if (!hit || hit.cls !== "P") {
        return "「宾」应当归到 P 类（兵/卒只是兵种），实际 " + JSON.stringify(hit);
    }
    // ⚠️ 2026-09-20 用户口径：YOLO 只负责定位，**兵种只认 OCR 字形** ⇒ 「H」这类系统性误读
    //    必须在字形表里认下来（K40 实测 PP-OCR 把「士」读成 H、给 0.85 分）。
    const h = pieceOcrModule.classOf("H");
    if (!h || h.cls !== "A") {
        return "「H」（真机把士读成 H）应当归到 A 类（士），实际 " + JSON.stringify(h);
    }
    if (pieceOcrModule.classOf("霾")) {
        return "表里没有的字不许硬猜（霾 不该有类别）";
    }
    const label = pieceOcrModule.classOfLabel("K");
    if (!label || label.cls !== "K") {
        return "检测类别字母 K 应当映射到 K 类（仅诊断用），实际 " + JSON.stringify(label);
    }
    return true;
});

check("字形分类不带红黑：黑炮也写「炮」，颜色必须由颜色模块给", function () {
    const hit = pieceOcrModule.classOf("炮");
    if (!hit || hit.cls !== "C") {
        return "「炮」应当归到 C 类，实际 " + JSON.stringify(hit);
    }
    if (hit.char != null) {
        return "字形分类不该带颜色（char），实际 " + JSON.stringify(hit);
    }
    if (pieceOcrModule.applySide("C", "BLACK") !== "c" || pieceOcrModule.applySide("C", "RED") !== "C") {
        return "applySide 没把颜色拼对：" + pieceOcrModule.applySide("C", "BLACK") + "/" +
            pieceOcrModule.applySide("C", "RED");
    }
    return true;
});

check("字形分类器认不得的字一律 null（绝不猜）", function () {
    ["", " ", "木", "楚河", "abc", null].forEach(function (text) {
        const hit = pieceOcrModule.classOf(text);
        if (hit) {
            throw new Error("「" + text + "」被错误地认成了 " + hit.cls);
        }
    });
    return true;
});

check("字形分类覆盖七类异体字（俥傌帥將砲…）", function () {
    const table = { "车": "R", "俥": "R", "車": "R", "马": "N", "傌": "N", "馬": "N", "相": "B", "象": "B",
        "仕": "A", "士": "A", "帅": "K", "帥": "K", "将": "K", "將": "K", "炮": "C", "砲": "C",
        "兵": "P", "卒": "P" };
    for (const glyph in table) {
        const hit = pieceOcrModule.classOf(glyph);
        if (!hit || hit.cls !== table[glyph]) {
            return "「" + glyph + "」应为 " + table[glyph] + "，实际 " + JSON.stringify(hit);
        }
    }
    return true;
});

check("OCR 运行时按方案分级：≥0.70 采纳 / 0.55~0.70 低置信 / <0.55 未知", function () {
    let creates = 0;
    const runtime = ocrRuntimeModule.create({
        createSession: function () {
            creates += 1;
            return {
                detect: function (image, options) {
                    return image && image.blocks ? image.blocks : [];
                }
            };
        }
    });
    const cases = [
        [{ text: "炮", score: 0.99 }, "ok"],
        [{ text: "炮", score: 0.65 }, "low"],
        [{ text: "炮", score: 0.40 }, "unknown"]
    ];
    for (let i = 0; i < cases.length; i++) {
        const got = runtime.recognizeGlyph({ blocks: [cases[i][0]] }, [0, 0, 10, 10]);
        if (got.state !== cases[i][1]) {
            return "score=" + cases[i][0].score + " 应当 " + cases[i][1] + "，实际 " + got.state;
        }
    }
    if (creates !== 1) {
        return "OCR 会话应当复用（只创建 1 次），实际 " + creates + " 次";
    }
    return true;
});

check("OCR 没读到字是「未知」而不是「空格」（真机踩过：飞行中的棋子被判成空）", function () {
    const runtime = ocrRuntimeModule.create({
        createSession: function () {
            return { detect: function () { return []; } };
        }
    });
    const got = runtime.recognizeGlyph({}, [0, 0, 10, 10]);
    if (got.state !== "unknown") {
        return "空结果应当是 unknown，实际 " + got.state;
    }
    if (got.state === "ok" || got.cls != null) {
        return "空结果不许给出类别";
    }
    return true;
});

check("稳定帧：画面还在动时必须拒绝（方案 §3.1）", function () {
    let clock = 100000;
    const gate = frameGateModule.create({ now: function () { return clock; } });
    const a = [10, 20, 30, 40];
    // 两帧一样 ⇒ 稳定
    let verdict = gate.check(a, [10, 20, 30, 40], "board");
    if (!verdict.stable) {
        return "两帧应当稳定，实际 " + verdict.reason;
    }
    // 差 20 ⇒ 不稳定（阈值 6）
    verdict = gate.check(a, [30, 40, 50, 60], "board");
    if (verdict.stable) {
        return "两帧差 20 应当判不稳定（阈值 " + gate.config.maxMeanDiff + "）";
    }
    // 未知格太多 ⇒ 这一帧也不要
    const unknownVerdict = gate.acceptResult(9);
    if (unknownVerdict.ok) {
        return "未知格 9 个（> " + gate.config.maxUnknownCells + "）应当丢弃";
    }
    if (!gate.exhausted !== (gate.state.retries >= gate.config.maxRetries)) {
        return "重试次数的 exhausted 判定不对";
    }
    return true;
});

check("稳定帧：切前台后的第一帧只预热，不进识别（方案 §3.1）", function () {
    let clock = 5000;
    const gate = frameGateModule.create({ now: function () { return clock; } });
    gate.onFocusChange(clock);
    // 等够时间后，第一帧仍然只预热
    clock += 1000;
    const first = gate.check([1, 2, 3], [1, 2, 3], "board");
    if (!first.warmup || first.stable) {
        return "切前台后第一帧应当只预热，实际 " + JSON.stringify(first);
    }
    // 第二帧才真正比较
    const second = gate.check([1, 2, 3], [1, 2, 3], "board");
    if (!second.stable) {
        return "预热之后的稳定帧应当被采纳，实际 " + second.reason;
    }
    return true;
});

check("规则安全门：标准开局可信，三个炮/缺帅一律否决（方案 §3.9）", function () {
    const start = fen.parseFen("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w").board;
    const ok = boardValidator.validate(start, { side: "w" });
    if (!ok.ok) {
        return "标准开局应当可信，实际 " + ok.reason;
    }
    // 三个黑炮（读错的典型）：应当否决
    const three = start.slice();
    const emptyCells = [];
    for (let i = 0; i < 90; i++) {
        if (!three[i]) {
            emptyCells.push(i);
        }
    }
    three[emptyCells[0]] = "c";
    three[emptyCells[1]] = "c";
    const bad = boardValidator.validate(three, { side: "w" });
    if (bad.ok) {
        return "三个炮的局面应当被否决";
    }
    // 缺帅：应当否决
    const noKing = start.slice();
    for (let i = 0; i < 90; i++) {
        if (noKing[i] === "K") {
            noKing[i] = null;
        }
    }
    if (boardValidator.validate(noKing, { side: "w" }).ok) {
        return "缺红帅的局面应当被否决";
    }
    return true;
});

check("规则安全门：着法必须在合法着法表里，a0b0 不许当兜底", function () {
    const start = fen.parseFen("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w").board;
    const fallback = boardValidator.isLegalMove(start, "w", "a0b0");
    if (fallback.ok) {
        return "a0b0 是兜底着法，必须拒绝";
    }
    const legalMoves = legal.allLegalMoves(start, "w");
    if (!legalMoves.length) {
        return "标准开局应当有合法着法";
    }
    const one = boardValidator.isLegalMove(start, "w", legalMoves[0].iccs);
    if (!one.ok) {
        return "开局第一手 " + legalMoves[0].iccs + " 应当是合法的，实际 " + one.reason;
    }
    return true;
});

check("稳定帧闸门：采样必须是**数字数组**（传 [r,g,b] 会静默变成 NaN —— 2026-09-20 基准工具踩过）", function () {
    const gate = frameGateModule.create({});
    const base = [100, 120, 140, 160, 180];
    const near = [101, 121, 141, 159, 181];
    const okVerdict = gate.check(base, near, "t");
    if (!okVerdict.stable) {
        return "差 1～2 的两帧应当算稳定，实际 " + okVerdict.reason;
    }
    // 反例：传三元组 ⇒ Number([1,2,3]) = NaN ⇒ 闸门应当明确报「无法比较」，而不是当成稳定
    const triples = [[1, 2, 3], [1, 2, 3]];
    const bad = frameGateModule.create({}).check(triples, triples, "t");
    if (bad.stable) {
        return "三元组采样不该被判成稳定（NaN 陷阱），实际 " + JSON.stringify(bad);
    }
    if (!/无法比较/.test(String(bad.reason))) {
        return "三元组采样应当报「无法比较」，实际 " + bad.reason;
    }
    return true;
});

check("识别服务 board-service：识别链只有字形（通过=glyph；否决/超限/帧不稳=这一帧不观察；无字形=明确失败）", function () {
    const boardService = require("../src/services/board-service.js");
    const grid = { originX: 100, originY: 100, stepX: 120, stepY: 120, orientation: "RED_BOTTOM", version: 1 };
    const charsOf = function () {
        const out = new Array(90);
        for (let i = 0; i < 90; i++) {
            out[i] = null;
        }
        out[0] = "r";
        return out;
    };
    let legacyRuns = 0;
    let glyphRuns = 0;
    let glyphResult = null;
    let gateStable = true;
    const build = function (calibrate) {
        return boardService.createBoardService({
            ports: { now: function () { return Date.now(); }, capture: function () { return { image: {} }; } },
            locateBoard: { locateBoardInImage: function () { return { ok: true, grid: grid }; } },
            recognizeBoard: {
                run: function () {
                    legacyRuns += 1;
                    return { ok: true, chars: charsOf(), layout: "", grid: grid, unknownCells: 0 };
                }
            },
            calibrate: calibrate || null,
            glyph: {
                recognize: function () {
                    glyphRuns += 1;
                    return glyphResult;
                }
            },
            vision: {
                gate: {
                    check: function () { return { stable: gateStable, reason: "画面还在动" }; },
                    acceptResult: function (unknown) {
                        return unknown > 6 ? { ok: false, reason: "未知格 " + unknown + " 个（> 6）" } : { ok: true };
                    }
                },
                samples: function () { return [1, 2, 3]; },
                countUnknown: function () { return 0; },
                log: function () { }
            }
        });
    };
    // ① 字形通过 ⇒ method=glyph；且绝不跑旧路径
    const chars = charsOf();
    chars[0] = "n";
    glyphResult = { ok: true, chars: chars, rawChars: chars.slice(), unknownCells: 0, layout: "x", validation: { ok: true }, stats: { ms: 123 } };
    const good = build();
    const r1 = good.recognizeSmart({});
    if (!r1 || r1.method !== "glyph" || r1.chars[0] !== "n") {
        return "字形通过时应返回 method=glyph，实际 " + JSON.stringify(r1 && { m: r1.method, c0: r1.chars && r1.chars[0] });
    }
    if (legacyRuns !== 0) {
        return "识别链只有字形，不该跑旧路径，实际跑了 " + legacyRuns + " 次";
    }
    if (r1.unknownCells !== 0 || !r1.validation) {
        return "字形结果要带 unknownCells/validation（驱动按它们做门）";
    }
    // ② 规则门否决 ⇒ {ok:false}（**不**回退旧路径：读不过 = 这一帧不观察）
    glyphResult = { ok: true, chars: charsOf(), unknownCells: 0, validation: { ok: false, reason: "红帅数量 0（应为 1）" }, stats: {} };
    const bad = build();
    const r2 = bad.recognizeSmart({});
    if (!r2 || r2.ok !== false || !/字形识别不通过/.test(String(r2.reason)) || !/红帅数量/.test(String(r2.reason))) {
        return "字形被否决时应返回 ok:false 且带原因，实际 " + JSON.stringify(r2 && { ok: r2.ok, r: r2.reason });
    }
    if (legacyRuns !== 0) {
        return "字形不通过也不许回退旧路径（「其他的都不要了」），实际跑了 " + legacyRuns + " 次";
    }
    // ③ 帧不稳 ⇒ 不观察（连字形都不跑）
    glyphResult = { ok: true, chars: charsOf(), unknownCells: 0, validation: { ok: true }, stats: {} };
    const shaky = build();
    gateStable = false;
    const beforeShaky = glyphRuns;
    const r3 = shaky.recognizeSmart({});
    gateStable = true;
    if (!r3 || r3.ok !== false || r3.unstable !== true) {
        return "帧不稳定时应当返回 {ok:false, unstable:true}，实际 " + JSON.stringify(r3);
    }
    if (glyphRuns !== beforeShaky || legacyRuns !== 0) {
        return "帧不稳定时不该跑字形/旧路径，实际 字形+" + (glyphRuns - beforeShaky) + " 旧路径 " + legacyRuns;
    }
    // ④ 未知格超限（gate.acceptResult 驳回）⇒ 也不观察、不回退
    glyphResult = { ok: true, chars: charsOf(), unknownCells: 9, validation: { ok: true }, stats: {} };
    const manyUnknown = build();
    const r4 = manyUnknown.recognizeSmart({});
    if (!r4 || r4.ok !== false || !/未知格/.test(String(r4.reason))) {
        return "字形未知格超限时应返回 ok:false 且标明未知格，实际 " + JSON.stringify(r4 && { ok: r4.ok, r: r4.reason });
    }
    // ⑤ 没有字形注入 ⇒ 明确失败（不再偷偷退回旧路径）
    const none = boardService.createBoardService({
        ports: { now: function () { return Date.now(); }, capture: function () { return { image: {} }; } },
        locateBoard: { locateBoardInImage: function () { return { ok: true, grid: grid }; } },
        recognizeBoard: {
            run: function () {
                legacyRuns += 1;
                return { ok: true, chars: charsOf(), layout: "", grid: grid, unknownCells: 0 };
            }
        }
    });
    const r5 = none.recognizeSmart({});
    if (!r5 || r5.ok !== false || !/字形识别不可用/.test(String(r5.reason)) || legacyRuns !== 0) {
        return "没有字形注入时应明确失败且不跑旧路径，实际 " + JSON.stringify(r5 && { ok: r5.ok, r: r5.reason }) + " legacy=" + legacyRuns;
    }
    // ⑥ 注入校准 ⇒ locate() 用校准后的几何（主界面/悬浮窗共用同一份校准参数）
    const calib = build(function (image, located, options) {
        return {
            ok: true, grid: Object.assign({}, located.grid, { version: 7 }),
            dx: -11, dy: -4, unknown: 1, tried: 16, layout: ""
        };
    });
    const located = calib.locate({});
    if (!located.ok || located.grid.version !== 7 || calib.grid().version !== 7) {
        return "注入校准后 locate() 必须用校准后的几何，实际 " +
            JSON.stringify({ ok: located && located.ok, v: located && located.grid && located.grid.version });
    }
    return true;
});

check("识别服务 board-service：字形单路（mode 已废弃；否决/超限=不观察；无字形/enabled:false=明确失败）", function () {
    const boardService = require("../src/services/board-service.js");
    const grid = { originX: 100, originY: 100, stepX: 120, stepY: 120, orientation: "RED_BOTTOM", version: 1 };
    const charsOf = function () {
        const out = new Array(90);
        for (let i = 0; i < 90; i++) {
            out[i] = null;
        }
        out[0] = "r";
        return out;
    };
    let glyphRuns = 0;
    let pipelineRuns = 0;
    let legacyRuns = 0;
    const build = function (glyphResult) {
        return boardService.createBoardService({
            ports: { now: function () { return Date.now(); }, capture: function () { return { image: {} }; } },
            locateBoard: { locateBoardInImage: function () { return { ok: true, grid: grid }; } },
            recognizeBoard: {
                run: function () {
                    legacyRuns += 1;
                    return { ok: true, chars: charsOf(), layout: "", grid: grid, unknownCells: 0 };
                }
            },
            glyph: {
                recognize: function () {
                    glyphRuns += 1;
                    return glyphResult;
                }
            }
        });
    };
    // 为了同时注入 vision，单独组装（createBoardService 的参数在创建时就固定）
    const buildFull = function (glyphResult) {
        return boardService.createBoardService({
            ports: { now: function () { return Date.now(); }, capture: function () { return { image: {} }; } },
            locateBoard: { locateBoardInImage: function () { return { ok: true, grid: grid }; } },
            recognizeBoard: {
                run: function () {
                    legacyRuns += 1;
                    return { ok: true, chars: charsOf(), layout: "", grid: grid, unknownCells: 0 };
                }
            },
            glyph: {
                recognize: function () {
                    glyphRuns += 1;
                    return glyphResult;
                }
            },
            vision: {
                pipeline: {
                    recognize: function () {
                        pipelineRuns += 1;
                        return { ok: true, chars: charsOf(), rawChars: charsOf(), validation: { ok: true }, timing: null };
                    }
                },
                gate: {
                    acceptResult: function (unknown) {
                        return unknown > 6 ? { ok: false, reason: "未知格 " + unknown + " 个（> 6）" } : { ok: true };
                    }
                },
                countUnknown: function () { return 0; },
                log: function () { }
            }
        });
    };
    // ① 字形通过 ⇒ method="glyph"，且**不跑**流水线/旧路径；unknownCells 要透给驱动
    const chars = charsOf();
    chars[0] = "n";
    const good = buildFull({ ok: true, chars: chars, rawChars: chars.slice(), unknownCells: 0, layout: "x", validation: { ok: true }, stats: { ms: 123 } });
    const r1 = good.recognizeSmart({});
    if (!r1 || r1.method !== "glyph" || r1.chars[0] !== "n") {
        return "字形通过时应返回 method=glyph，实际 " + JSON.stringify(r1 && { m: r1.method, c0: r1.chars && r1.chars[0] });
    }
    if (pipelineRuns !== 0 || legacyRuns !== 0) {
        return "字形通过时不该跑流水线/旧路径，实际 流水线 " + pipelineRuns + " 旧路径 " + legacyRuns;
    }
    if (r1.unknownCells !== 0 || !r1.validation) {
        return "字形结果要带 unknownCells/validation（驱动按它们做门）";
    }
    // ② 字形被规则门否决 ⇒ {ok:false}（不观察；**不**回退流水线/旧路径）
    const reject = buildFull({ ok: true, chars: charsOf(), unknownCells: 0, validation: { ok: false, reason: "红帅数量 0（应为 1）" }, stats: {} });
    const r2 = reject.recognizeSmart({});
    if (!r2 || r2.ok !== false || !/字形识别不通过/.test(String(r2.reason))) {
        return "字形被否决时应返回 ok:false，实际 " + JSON.stringify(r2 && { ok: r2.ok, r: r2.reason });
    }
    if (glyphRuns !== 2 || pipelineRuns !== 0 || legacyRuns !== 0) {
        return "字形被否决时只该跑字形（共 2 次），实际 字形 " + glyphRuns + "/流水线 " + pipelineRuns + "/旧路径 " + legacyRuns;
    }
    // ③ 字形未知格超限（gate.acceptResult 驳回）⇒ 也不观察
    const manyUnknown = buildFull({ ok: true, chars: charsOf(), unknownCells: 9, validation: { ok: true }, stats: {} });
    const r3 = manyUnknown.recognizeSmart({});
    if (!r3 || r3.ok !== false || pipelineRuns !== 0) {
        return "字形未知格超限时应返回 ok:false 且不跑流水线，实际 " + JSON.stringify(r3 && { ok: r3.ok, r: r3.reason }) + " pipeline=" + pipelineRuns;
    }
    // ④ mode 参数已废弃：mode="glyph" 与默认同一行为（不通过=失败）
    const strict = buildFull({ ok: true, chars: charsOf(), unknownCells: 0, validation: { ok: false, reason: "黑将数量 0（应为 1）" }, stats: {} });
    const r4 = strict.recognizeSmart({ mode: "glyph" });
    if (!r4 || r4.ok !== false || !/字形识别不通过/.test(String(r4.reason))) {
        return "mode=glyph 与默认行为一致（不通过=失败），实际 " + JSON.stringify(r4 && { ok: r4.ok, r: r4.reason });
    }
    // ⑤ mode="pipeline" 同样被忽略：仍然只走字形
    const skip = buildFull({ ok: true, chars: charsOf(), unknownCells: 0, validation: { ok: true }, stats: {} });
    const before = glyphRuns;
    const r5 = skip.recognizeSmart({ mode: "pipeline" });
    if (!r5 || r5.method !== "glyph" || glyphRuns !== before + 1 || pipelineRuns !== 0) {
        return "mode 已废弃：pipeline 也应只走字形，实际 " + JSON.stringify(r5 && r5.method) + " glyphRuns+" + (glyphRuns - before) + " pipeline=" + pipelineRuns;
    }
    // ⑥ 没有 vision 注入（字形专属服务）⇒ 照样走字形
    const only = build({ ok: true, chars: charsOf(), unknownCells: 0, validation: { ok: true }, stats: {} });
    const r6 = only.recognizeSmart({});
    if (!r6 || r6.method !== "glyph") {
        return "只有字形注入时也应当走字形，实际 " + JSON.stringify(r6 && r6.method);
    }
    // ⑦ glyph.enabled=false ⇒ 明确不可用（旧回退语义已删除）
    const off = boardService.createBoardService({
        ports: { now: function () { return Date.now(); }, capture: function () { return { image: {} }; } },
        locateBoard: { locateBoardInImage: function () { return { ok: true, grid: grid }; } },
        recognizeBoard: {
            run: function () {
                legacyRuns += 1;
                return { ok: true, chars: charsOf(), layout: "", grid: grid, unknownCells: 0 };
            }
        },
        glyph: {
            enabled: false,
            recognize: function () { return { ok: true, chars: charsOf(), validation: { ok: true }, stats: {} }; }
        }
    });
    const r7 = off.recognizeSmart({});
    if (!r7 || r7.ok !== false || !/字形识别不可用/.test(String(r7.reason)) || legacyRuns !== 0) {
        return "glyph.enabled=false 时应明确失败且不跑旧路径，实际 " + JSON.stringify(r7 && { ok: r7.ok, r: r7.reason });
    }
    return true;
});

check("board-scan：颜色采样跟着 YOLO 定位框走（框心偏 50px 也能采到棋子）", function () {
    const boardScanMod = require("../src/vision/board-scan.js");
    const grid = { originX: 100, originY: 100, stepX: 120, stepY: 120, orientation: "RED_BOTTOM", version: 1 };
    const IMAGE = {};
    // 棋子画在 (150,100)（比交点偏 +50px，真机就是这种偏差）；其余是中性绿底
    const rgbAt = function (image, x, y) {
        if (Math.abs(x - 150) <= 12 && Math.abs(y - 100) <= 12) {
            return [176, 44, 40];
        }
        // ⚠️ 不能用纯灰当“无棋子”底色：piece-color 的「低饱和」那一条会把灰判成黑（
        //    blackFromSat = (0.45-s)/0.45，s=0 直接给 0.4 分）；中性绿两边都不沾 ⇒ UNKNOWN。
        return [120, 170, 120];
    };
    const session = { detect: function () { return [{ text: "车", score: 0.92 }]; } };
    const host = { session: session, rgbAt: rgbAt };
    // ① 不给框：按格心采样 ⇒ 采到的全是中性绿底 ⇒ 颜色未定，那格保持 null（不猜颜色）
    const plain = boardScanMod.scan(host, IMAGE, grid, {});
    if (!plain.ok) {
        return "扫描失败：" + plain.reason;
    }
    if (plain.chars[0] !== null) {
        return "不跟框时应当采不到颜色（绿底既不是红也不是黑），实际 " + plain.chars[0];
    }
    // ② 给 YOLO 框（框心 (150,100)）：颜色要跟着框采 ⇒ 红车 R
    const boxes = { 0: { box: { centerX: 150, centerY: 100, width: 80, height: 80 }, residual: 0.25 } };
    const withBox = boardScanMod.scan(host, IMAGE, grid, { boxes: boxes });
    if (!withBox.ok) {
        return "带框扫描失败：" + withBox.reason;
    }
    if (withBox.chars[0] !== "R") {
        return "颜色应当按 YOLO 框心采样（(150,100) 那格是红子），实际 " + withBox.chars[0] +
            "（side=" + withBox.sides[0] + "）";
    }
    return true;
});

check("红黑（找色版）：框内数红/黑墨迹 → 密度分开才算，空位与平局都留 UNKNOWN", function () {
    const ink = require("../src/vision/ink-color.js");
    const area = 68 * 68;                      // 真机一个棋子中心区的量级
    // 真机实测：红字格红密度 ≈0.09~0.16、黑字格黑密度 ≈0.13、空格两边都 ≈0
    const red = ink.judgeCounts({ red: Math.round(area * 0.09), black: 0, area: area, source: "findColor" });
    if (red.side !== "RED") {
        return "红墨迹占 9% 时应当判 RED，实际 " + red.side + "（" + red.reason + "）";
    }
    if (!(red.confidence >= 0.35)) {
        return "颜色证据分应当饱和（≥0.35）才能过融合门槛，实际 " + red.confidence;
    }
    const black = ink.judgeCounts({ red: 20, black: Math.round(area * 0.13), area: area, source: "findColor" });
    if (black.side !== "BLACK") {
        return "黑墨迹占 13%、红墨迹极少时应当判 BLACK，实际 " + black.side + "（" + black.reason + "）";
    }
    const empty = ink.judgeCounts({ red: 3, black: 1, area: area, source: "findColor" });
    if (empty.side !== "UNKNOWN") {
        return "空格（两边几乎没有墨迹）必须留 UNKNOWN，绝不猜颜色，实际 " + empty.side;
    }
    const tie = ink.judgeCounts({ red: 200, black: 200, area: area, source: "findColor" });
    if (tie.side !== "UNKNOWN") {
        return "两边都好、互相压不倒时必须 UNKNOWN，实际 " + tie.side;
    }
    const half = ink.judgeCounts({ red: Math.round(area * 0.09), black: Math.round(area * 0.07), area: area, source: "findColor" });
    if (half.side !== "UNKNOWN") {
        return "红只比黑多 1.3 倍（不够 dominance=1.5）时应当 UNKNOWN，实际 " + half.side;
    }
    return true;
});

check("红黑（找色版）：board-scan 用 YOLO 框的位置调找色；没有找色能力才回退采样", function () {
    const boardScanMod = require("../src/vision/board-scan.js");
    const grid = { originX: 100, originY: 100, stepX: 120, stepY: 120, orientation: "RED_BOTTOM" };
    const AREA = 68 * 68;
    const calls = [];
    // 假宿主：把「哪个格子是什么颜色」记在闭包里，countColor 按传入的 region 反推格子
    const host = {
        session: { detect: function () { return [{ text: "车", score: 0.9 }]; } },
        countColor: function (image, color, options) {
            const region = options.region;
            const col = Math.round((region.x + region.width / 2 - grid.originX) / grid.stepX);
            const row = Math.round((region.y + region.height / 2 - grid.originY) / grid.stepY);
            calls.push({ col: col, row: row, color: color, x: region.x, width: region.width });
            const isRedRef = String(color).toUpperCase() === "#83412D";
            if (col === 0 && row === 0) {
                return isRedRef ? Math.round(AREA * 0.09) : 0;      // (0,0) 红子
            }
            if (col === 1 && row === 0) {
                return isRedRef ? 0 : Math.round(AREA * 0.13);      // (1,0) 黑子
            }
            return 0;                                              // 其它格：空位
        },
        rgbAt: function () { return null; }                        // 只有采样能力的宿主才用它
    };
    // 给 (0,0) 一个**偏 50px** 的 YOLO 框：找色区必须跟着框心走（用户口径 2026-09-20）
    const boxes = { 0: { box: { centerX: 150, centerY: 100, width: 80, height: 80 }, residual: 0.25 } };
    const result = boardScanMod.scan(host, {}, grid, { boxes: boxes });
    if (!result.ok) {
        return "扫描应当成功，实际 " + result.reason;
    }
    if (result.chars[0] !== "R") {
        return "(0,0) 红子应当是 R，实际 " + JSON.stringify(result.chars[0]);
    }
    if (result.chars[1] !== "r") {
        return "(1,0) 黑子应当是小写 r，实际 " + JSON.stringify(result.chars[1]);
    }
    if (result.chars[2] !== null || result.sides[2] !== "UNKNOWN") {
        return "空格必须留 null/UNKNOWN，实际 " + JSON.stringify({ ch: result.chars[2], side: result.sides[2] });
    }
    if (result.inkSkipped !== 88) {
        // (0,0) 有框、(1,0) 有墨迹 ⇒ 都要扫；剩下 88 格没框也没墨迹 ⇒ 被闸门跳过（性能，不丢证据）
        return "没框又没墨迹的格子应当被跳过（88 格），实际 inkSkipped=" + result.inkSkipped;
    }
    if (result.hits !== 2) {
        return "只有 2 格有颜色证据，实际 hits=" + result.hits;
    }
    const redCall = calls.filter(function (one) { return one.color === "#83412D" && one.col === 0 && one.row === 0; })[0];
    if (!redCall) {
        return "(0,0) 应当调过找色（红参考色），实际一条都没有";
    }
    // 框心在 150 ⇒ 中心区左边缘 = 150 - width/2（不是格心的 100 - width/2）
    if (Math.abs((redCall.x + redCall.width / 2) - 150) > 6) {
        return "找色区必须跟着 YOLO 框心（150）走，实际中心 " + (redCall.x + redCall.width / 2);
    }
    // 两个参考色都要问一遍（红黑是对称的）
    const colours = {};
    for (let i = 0; i < calls.length; i++) {
        colours[calls[i].color] = true;
    }
    if (!colours["#83412D"] || !colours["#282830"]) {
        return "红、黑两个参考色都该找一遍，实际 " + JSON.stringify(Object.keys(colours));
    }
    return true;
});

check("分段计时（方案 §7）：mark 累计正确、summary 会封口、format 一定带「合计」", function () {
    const timing = require("../src/runtime/timing.js");
    // 假时钟：确定性断言，不依赖真实耗时
    let now = 1000;
    const clock = timing.create({ now: function () { return now; } });
    now += 120;
    clock.mark("抓帧");
    now += 30;
    clock.mark("稳定帧");
    now += 1500;
    const summary = clock.summary("流水线");
    if (summary.total !== 1650) {
        return "合计应当是 120+30+1500=1650，实际 " + summary.total;
    }
    const names = summary.sections.map(function (one) { return one.name; }).join(",");
    if (names !== "抓帧,稳定帧,流水线") {
        return "分段名字不对：" + names;
    }
    // 封口：summary() 之后不该再涨（否则日志里「边打印边涨」）
    now += 500;
    clock.mark("多余的");
    if (summary.sections.length !== 3 || summary.total !== 1650) {
        return "summary() 之后不该再涨，实际 " + JSON.stringify(summary);
    }
    const line = timing.format(summary, "耗时分期");
    if (line.indexOf("合计 1650ms") < 0 || line.indexOf("抓帧 120ms") < 0 || line.indexOf("耗时分期：") !== 0) {
        return "格式不对：" + line;
    }
    if (timing.format(null).indexOf("没有分段数据") < 0) {
        return "空快照也要给一句人话，实际 " + timing.format(null);
    }
    // tail=null：不补最后一段（调用方自己已经 mark 过了）
    let now2 = 0;
    const clock2 = timing.create({ now: function () { return now2; } });
    now2 += 7;
    clock2.mark("a");
    const s2 = clock2.summary(null);
    if (s2.sections.length !== 1 || s2.total !== 7) {
        return "tail=null 时不该补段，实际 " + JSON.stringify(s2);
    }
    return true;
});

check("OCR 预处理（先灰度）：灰度主读直接救回红帅（真机：红帅格灰度「\u5E2B」0.70；彩色那遍「印市」0.49 根本不用跑）", function () {
    const boardScanMod = require("../src/vision/board-scan.js");
    const grid = { originX: 100, originY: 100, stepX: 120, stepY: 120, orientation: "RED_BOTTOM" };
    const AREA = 68 * 68;
    const prepared = [];
    const host = {
        // 彩色：垃圾文本（真机「印市」）；灰度：读成「師」（U+5E2B，已在字形表里收成 K）
        session: {
            detect: function (image) {
                return image && image.__gray
                    ? [{ text: "\u5E2B", score: 0.70 }]
                    : [{ text: "\u5370\u5E02", score: 0.49 }];
            }
        },
        countColor: function (image, color, options) {
            const region = options.region;
            const col = Math.round((region.x + region.width / 2 - grid.originX) / grid.stepX);
            const row = Math.round((region.y + region.height / 2 - grid.originY) / grid.stepY);
            if (col !== 0 || row !== 0) {
                return 0;                                  // 其它格：空位（没墨迹）
            }
            return String(color).toUpperCase() === "#83412D" ? Math.round(AREA * 0.09) : 0;
        },
        rgbAt: function () { return null; },
        prepareRegion: function (image, region, mode) {
            const frame = { __gray: mode === "gray", mode: mode, region: region, released: false };
            prepared.push(frame);
            return frame;
        },
        releaseFrame: function (frame) { frame.released = true; return true; }
    };
    const result = boardScanMod.scan(host, {}, grid, { only: [0, 1] });
    if (!result.ok) {
        return "扫描应当成功，实际 " + result.reason;
    }
    if (result.chars[0] !== "K") {
        return "灰度直读后 (0,0) 应当是红帅 K，实际 " + JSON.stringify(result.chars[0]) +
            "（灰度那遍必须接上找色结论给出红黑）";
    }
    if (result.scores[0] !== 0.70) {
        return "分数应当用灰度那遍的 0.70，实际 " + result.scores[0];
    }
    const grays = prepared.filter(function (one) { return one.mode === "gray"; });
    const clips = prepared.filter(function (one) { return one.mode === "clip"; });
    if (grays.length !== 1) {
        return "只有 (0,0) 有墨迹值得读，(1,0) 空位必须跳过，实际灰度读 " + grays.length + " 次";
    }
    if (clips.length !== 0) {
        // 灰度主读就够 ⇒ 一次彩色兜底都不该花（老顺序里这两行的角色相反）
        return "灰度主读就够，不该再裁彩色帧，实际 clip " + clips.length + " 次";
    }
    if (result.inkSkipped !== 1) {
        return "(1,0) 空位应当被闸门跳过 1 格，实际 " + result.inkSkipped;
    }
    for (let i = 0; i < prepared.length; i++) {
        if (!prepared[i].released) {
            return "临时帧必须即时释放（原生帧不会 GC，不还就堆在帧表里）：第 " + i + " 个没还";
        }
    }
    if (prepared[0].mode !== "gray" || !Array.isArray(prepared[0].region) || prepared[0].region.length !== 4) {
        return "主读要传 mode + [x,y,w,h]，实际 " + JSON.stringify({ mode: prepared[0].mode, region: prepared[0].region });
    }
    if (result.grayPrimary !== 1 || result.grayChars !== 1 || result.grayRetries !== 0 || result.colorRetries !== 0) {
        return "计数不对：" + JSON.stringify({ grayPrimary: result.grayPrimary, chars: result.grayChars, grayRetries: result.grayRetries, colorRetries: result.colorRetries });
    }
    return true;
});

check("性能：复用「刚为同一帧读过的逐格证据」的格子不再重读（真机：一次询问 1155ms → 预期 ~300ms）", function () {
    const boardScanMod = require("../src/vision/board-scan.js");
    const grid = { originX: 100, originY: 100, stepX: 120, stepY: 120, orientation: "RED_BOTTOM" };
    const AREA = 68 * 68;
    // 流水线刚读过：(0,0) 黑炮 0.92 分（够确定 ⇒ 可复用）；(2,0) 也读了但只有 0.40 分（不够 ⇒ 不复用）；
    // (3,0) 没有字形（不复用）
    const evidence = [
        { index: 0, cls: "c", side: "BLACK", ocrScore: 0.92, text: "炮" },
        { index: 2, cls: "p", side: "RED", ocrScore: 0.40, text: "兵" },
        { index: 3, cls: null, side: "RED", ocrScore: 0 }
    ];
    const reuse = boardScanMod.reuseFromEvidence(evidence);
    if (reuse.reused !== 1) {
        return "只有 (0,0) 够确定（有字形 + 分数 ≥0.6 + 颜色已定），实际复用 " + reuse.reused + " 格";
    }
    if (reuse.chars[0] !== "c") {
        return "(0,0) 应当复用成小写 c（黑炮），实际 " + JSON.stringify(reuse.chars[0]);
    }
    if (reuse.scores[0] !== 0.92) {
        return "分数要一起带出来（驱动写基准前还有一道分闸），实际 " + reuse.scores[0];
    }
    const detectCalls = [];
    const host = {
        session: {
            detect: function () {
                detectCalls.push(1);
                return [{ text: "车", score: 0.9 }];
            }
        },
        countColor: function (image, color, options) {
            const region = options.region;
            const col = Math.round((region.x + region.width / 2 - grid.originX) / grid.stepX);
            const row = Math.round((region.y + region.height / 2 - grid.originY) / grid.stepY);
            // 只有 (5,0) 有墨迹（黑）；(2,0)/(3,0) 及其它格都没墨迹
            if (col === 5 && row === 0) {
                return String(color).toUpperCase() === "#83412D" ? 0 : Math.round(AREA * 0.13);
            }
            return 0;
        },
        rgbAt: function () { return null; },
        prepareRegion: function (image, region, mode) {
            return { clip: mode === "clip", region: region, recycle: function () { } };
        },
        releaseFrame: function () { return true; }
    };
    const result = boardScanMod.scan(host, {}, grid, { reuse: reuse });
    if (!result.ok) {
        return "扫描应当成功，实际 " + result.reason;
    }
    if (result.reused !== 1) {
        return "复用计数应当是 1，实际 " + result.reused;
    }
    if (result.chars[0] !== "c" || result.glyphs[0] !== "炮" || result.sides[0] !== "BLACK") {
        return "复用格的结论必须原样带出，实际 " + JSON.stringify({
            ch: result.chars[0], glyph: result.glyphs[0], side: result.sides[0]
        });
    }
    if (detectCalls.length !== 1) {
        return "只有 (5,0)（没框但有墨迹）该真读一次 OCR，实际 " + detectCalls.length + " 次";
    }
    if (result.chars[5] !== "r") {
        return "(5,0) 黑车应当是 r，实际 " + JSON.stringify(result.chars[5]);
    }
    if (result.hits !== 2) {
        return "hit 应当把复用格与扫到的格都算上（2 格有字），实际 " + result.hits;
    }
    return true;
});

check("性能：没框的格子先用找色问「有没有墨迹」，没墨迹就不花 OCR（真机：全盘 2895ms → 1015ms）", function () {
    const boardScanMod = require("../src/vision/board-scan.js");
    const grid = { originX: 100, originY: 100, stepX: 120, stepY: 120, orientation: "RED_BOTTOM" };
    const AREA = 68 * 68;
    const detectCalls = [];
    const host = {
        session: {
            detect: function (image, options) {
                detectCalls.push(options && options.region ? "region" : "clip");
                return [{ text: "车", score: 0.9 }];
            }
        },
        countColor: function (image, color, options) {
            const region = options.region;
            const col = Math.round((region.x + region.width / 2 - grid.originX) / grid.stepX);
            const row = Math.round((region.y + region.height / 2 - grid.originY) / grid.stepY);
            // (0,0) 有墨迹、但**没有 YOLO 框**（模拟「模型漏检、OCR 能看到」的那类格）；其它格没墨迹
            if (col === 0 && row === 0) {
                return String(color).toUpperCase() === "#83412D" ? Math.round(AREA * 0.09) : 0;
            }
            return 0;
        },
        rgbAt: function () { return null; },
        prepareRegion: function (image, region, mode) {
            return { clip: mode === "clip", region: region, recycle: function () { } };
        },
        releaseFrame: function () { return true; }
    };
    const result = boardScanMod.scan(host, {}, grid, {});
    if (!result.ok) {
        return "扫描应当成功，实际 " + result.reason;
    }
    // ★ 最关键的一条：没框但有墨迹的格子**必须照样 OCR**（漏检证据不能丢）
    if (result.chars[0] !== "R") {
        return "没框但有墨迹的格子必须照样 OCR（漏检证据），实际 " + JSON.stringify(result.chars[0]);
    }
    if (result.hits !== 1) {
        return "只有 1 格有证据，实际 hits=" + result.hits;
    }
    if (result.inkSkipped !== 89) {
        return "89 格没墨迹应当被跳过，实际 " + result.inkSkipped;
    }
    if (detectCalls.length !== 1) {
        return "OCR 只该跑 1 次（只有那一格有墨迹），实际 " + detectCalls.length + " 次";
    }
    // 没有找色能力的宿主（Rhino / 离线夹具）：闸门自动关掉，回到「逐格都试一次」的老行为
    detectCalls.length = 0;
    const old = boardScanMod.scan({
        session: host.session, countColor: null, rgbAt: host.rgbAt,
        prepareRegion: host.prepareRegion, releaseFrame: host.releaseFrame
    }, {}, grid, {});
    if (old.inkSkipped !== 0 || detectCalls.length !== 90) {
        return "没有找色能力时不许跳格（老行为），实际 inkSkipped=" + old.inkSkipped +
            "／OCR " + detectCalls.length + " 次";
    }
    return true;
});

check("OCR 预处理：主读=彩色 + retryGray=false ⇒ 完全不碰灰度（老顺序的开关语义不变）；没有 prepareRegion 也一样", function () {
    const boardScanMod = require("../src/vision/board-scan.js");
    const grid = { originX: 100, originY: 100, stepX: 120, stepY: 120, orientation: "RED_BOTTOM" };
    const modes = [];
    function makeHost(withPrepare) {
        const host = {
            session: { detect: function (image) { return image && image.__gray ? [{ text: "車", score: 0.9 }] : []; } },
            countColor: function (image, color) {
                return String(color).toUpperCase() === "#83412D" ? 400 : 0;
            },
            rgbAt: function () { return null; }
        };
        if (withPrepare) {
            host.prepareRegion = function (image, region, mode) {
                modes.push(mode);
                return { __gray: mode === "gray", recycle: function () { return true; } };
            };
            host.releaseFrame = function () { return true; };
        }
        return host;
    }
    const off = boardScanMod.scan(makeHost(true), {}, grid, { only: [0], ocrPrimary: "color", retryGray: false });
    if (off.chars[0] != null || off.grayRetries !== 0) {
        return "老顺序 + retryGray=false 时不许碰灰度，实际 " + JSON.stringify({ ch: off.chars[0], retries: off.grayRetries });
    }
    const noCap = boardScanMod.scan(makeHost(false), {}, grid, { only: [0] });
    if (noCap.chars[0] != null || noCap.grayRetries !== 0) {
        return "宿主没有 prepareRegion 时不许重试，实际 " + JSON.stringify({ ch: noCap.chars[0], retries: noCap.grayRetries });
    }
    const grayModes = modes.filter(function (one) { return one === "gray"; });
    if (grayModes.length !== 0) {
        return "两种情况下都不该请灰度图，实际 " + grayModes.length + " 次";
    }
    return true;
});

check("OCR 预处理（先灰度）：灰度读不出时用**彩色**兜底救回（新顺序的兜底方向）", function () {
    const boardScanMod = require("../src/vision/board-scan.js");
    const grid = { originX: 100, originY: 100, stepX: 120, stepY: 120, orientation: "RED_BOTTOM" };
    const modes = [];
    const host = {
        // 灰度：读不出（[]）；彩色（clip）：正常读「车」—— 探针里灰度把馬读成「志」那种情况就靠它兜
        session: { detect: function (image) { return image && image.__gray ? [] : [{ text: "车", score: 0.9 }]; } },
        countColor: function (image, color) {
            return String(color).toUpperCase() === "#83412D" ? 400 : 0;
        },
        rgbAt: function () { return null; },
        prepareRegion: function (image, region, mode) {
            modes.push(mode);
            return { __gray: mode === "gray", recycle: function () { return true; } };
        },
        releaseFrame: function () { return true; }
    };
    const result = boardScanMod.scan(host, {}, grid, { only: [0] });
    if (result.chars[0] !== "R") {
        return "灰度读不出时应当用彩色兜底救回 R，实际 " + JSON.stringify(result.chars[0]);
    }
    if (result.grayPrimary !== 1 || result.colorRetries !== 1 || result.colorHits !== 1 || result.colorChars !== 1) {
        return "计数不对：" + JSON.stringify({
            grayPrimary: result.grayPrimary, retries: result.colorRetries, hits: result.colorHits, chars: result.colorChars
        });
    }
    if (modes.join(",") !== "gray,clip") {
        return "顺序必须是「先灰度、再彩色兜底」，实际 " + modes.join(",");
    }
    return true;
});

check("OCR 预处理：主读**先裁剪再送**（灰度/彩色都裁；越界时自动回退「整帧+region」）", function () {
    const boardScanMod = require("../src/vision/board-scan.js");
    const grid = { originX: 100, originY: 100, stepX: 120, stepY: 120, orientation: "RED_BOTTOM" };
    const calls = [];
    const released = [];
    let clipCount = 0;
    const host = {
        session: {
            detect: function (image, options) {
                calls.push({ full: image.__full === true, region: !!(options && options.region) });
                return [{ text: "车", score: 0.9 }];
            }
        },
        countColor: function (image, color) {
            // 两格都给「红子」的找色证据（否则颜色未定 ⇒ chars 留 null，跟裁剪无关）
            return String(color).toUpperCase() === "#83412D" ? 400 : 0;
        },
        rgbAt: function () { return null; },
        prepareRegion: function (image, region, mode) {
            clipCount += 1;
            if (clipCount === 1) {
                return null;                      // 第一格：模拟「区域越界」⇒ 必须回退老路径
            }
            return { __clipped: true };
        },
        releaseFrame: function (frame) { released.push(frame); return true; }
    };
    const result = boardScanMod.scan(host, { __full: true }, grid, { only: [0, 1], retryGray: false });
    if (!result.ok) {
        return "扫描应当成功，实际 " + result.reason;
    }
    if (result.chars[0] !== "R" || result.chars[1] !== "R") {
        return "两格都该读出字来，实际 " + JSON.stringify([result.chars[0], result.chars[1]]);
    }
    if (calls.length !== 2) {
        return "每格只该问一次 OCR，实际 " + calls.length + " 次";
    }
    if (!calls[0].full || !calls[0].region) {
        return "越界回退时 detect 必须收到「整帧 + region」，实际 " + JSON.stringify(calls[0]);
    }
    if (calls[1].full || calls[1].region) {
        return "裁剪成功时 detect 必须收到裁剪帧、且**不带** region，实际 " + JSON.stringify(calls[1]);
    }
    if (released.length !== 1) {
        return "裁剪帧用完必须释放（越界那次没有帧），实际 " + released.length + " 次";
    }
    return true;
});

check("字形表：真机误读「師（帥差一撇）」「帅」「帥」都算帅（K）；「飾/师」不在表里", function () {
    const pieceOcr = require("../src/vision/piece-ocr.js");
    // ⚠️ 用码点写，别靠手打形近字：師 U+5E2B 是 PP-OCR 实际吐出来的字（真机 (4,7) 灰度过 0.70），
    //    飾 U+98FE / 师 U+5E08 是**另一个字**，不该被认成帅（错认会把兵种判错）。
    const yes = ["\u5E05", "\u5E25", "\u5E2B"];   // 帅 / 帥 / 師
    for (let i = 0; i < yes.length; i++) {
        const hit = pieceOcr.classOf(yes[i]);
        if (!hit || hit.cls !== "K") {
            return yes[i] + "(U+" + yes[i].charCodeAt(0).toString(16).toUpperCase() + ") 应当归到 K，实际 " + JSON.stringify(hit);
        }
    }
    const no = ["\u98FE", "\u5E08"];              // 飾 / 师
    for (let i = 0; i < no.length; i++) {
        if (pieceOcr.classOf(no[i])) {
            return no[i] + "(U+" + no[i].charCodeAt(0).toString(16).toUpperCase() + ") 不是棋子字，不该被认出来";
        }
    }
    return true;
});

check("未知格账目：空位不算未知（评审 2026-09-20 的第一条）", function () {    const boardScan = require("../src/vision/board-scan.js");
    // 30 枚棋子 + 60 个空位（null）+ 0 个 `?` ⇒ 未知必须是 0
    const chars = new Array(90);
    for (let i = 0; i < 90; i++) {
        chars[i] = null;
    }
    const pieces = "rnbakabnrcpCpppPPcPPCRNBAKABNR";
    for (let i = 0; i < pieces.length && i < 90; i++) {
        chars[i] = pieces.charAt(i);
    }
    if (boardScan.countUnknownCells(chars) !== 0) {
        return "60 个空位不该算未知，实际 " + boardScan.countUnknownCells(chars) +
            "（写 `!ch || ch === \"?\"` 就会得到 60，再被稳定帧闸门把每帧都丢掉）";
    }
    chars[3] = "?";
    if (boardScan.countUnknownCells(chars) !== 1) {
        return "一个 `?` 应当只算 1 个未知，实际 " + boardScan.countUnknownCells(chars);
    }
    return true;
});

check("校验器：未知格只统计一次（评审 2026-09-20 的第二条）", function () {
    const validator = require("../src/vision/board-validator.js");
    const board = new Array(90);
    for (let i = 0; i < 90; i++) {
        board[i] = null;
    }
    board[0] = "r";
    board[1] = "n";
    board[2] = "b";
    board[3] = "a";
    board[4] = "k";
    board[5] = "a";
    board[6] = "b";
    board[7] = "n";
    board[8] = "r";
    board[81] = "R";
    board[82] = "N";
    board[83] = "B";
    board[84] = "A";
    board[85] = "K";
    board[86] = "A";
    board[87] = "B";
    board[88] = "N";
    board[89] = "R";
    board[40] = "?";
    const out = validator.validate(board, { allowUnknown: true, side: "b" });
    if (!out.stats || out.stats.unknown !== 1) {
        return "1 个 `?` 必须只统计 1 次，实际 " + (out.stats ? out.stats.unknown : "无 stats") +
            "（写成 red.unknown + black.unknown 就是 2）";
    }
    return true;
});

check("证据融合：自定义阈值/权重必须真的生效（评审 2026-09-20 的第三条）", function () {
    const fusion = require("../src/vision/evidence-fusion.js");
    const entry = { index: 0, cls: "R", side: "RED", detectionScore: 0.9, ocrScore: 0.8, colorScore: 1.0 };
    // ⚠️ 不要传 `frames: 1`：那样历史只剩 1 帧，永远只有一票、永远确认不了（自己先踩了一次）。
    const loose = fusion.createFuser();
    const strict = fusion.createFuser({ minFinalScore: 2 });
    // 融合器要**两票一致**才确认（方案 §3.8），所以同一帧数据推两次再比
    loose.push([entry]);
    strict.push([entry]);
    const a = loose.push([entry]);
    const b = strict.push([entry]);
    if (!(a.confirmed === 1)) {
        return "默认阈值下该格应当确认，实际 " + JSON.stringify(a);
    }
    if (!(b.confirmed === 0 && b.unknown === 1)) {
        return "minFinalScore=2 时该格不该确认（说明配置没被透传），实际 " + JSON.stringify(b);
    }
    return true;
});

check("检测框：先排序再截断 —— 45 个乱序框必须留下分数最高的（评审 2026-09-20 的第四条）", function () {
    const detector = require("../src/vision/piece-detector.js");
    const raw = [];
    for (let i = 0; i < 45; i++) {
        raw.push({ centerX: 100 + i, centerY: 100, width: 50, height: 50, score: i / 100, label: "R" });
    }
    const host = { detect: function () { return raw; } };
    const out = detector.detect(host, {}, { maxDetections: 40 });
    if (!out.ok || out.boxes.length !== 40) {
        return "应当留下 40 个框，实际 " + JSON.stringify({ ok: out.ok, n: out.boxes.length });
    }
    if (Math.abs(out.boxes[0].score - 0.44) > 1e-6) {
        return "留下的应当是分数最高的那批（0.44 开头），实际首个 " + out.boxes[0].score;
    }
    return true;
});

check("流水线：整盘 30 子 60 空位时，未知格必须是 0（不是把那 60 个空位算进去）", function () {
    const host = fakeVisionHost.createFakeHost(fakeVisionHost.openingPieces ? {} : {});
    const pipeline = visionPipelineModule.create({
        detect: host.detect,
        clip: host.clip,
        rgbAt: host.rgbAt,
        ocrSession: host.ocrSession,
        log: function () { }
    });
    let result = null;
    for (let i = 0; i < 3; i++) {
        result = pipeline.recognize({}, host.grid, { side: "w" });
    }
    const unknown = result.chars.filter(function (ch) { return ch === "?"; }).length;
    if (unknown !== 0) {
        return "3 格全确认后不应有未知格，实际 " + unknown;
    }
    const empties = result.chars.filter(function (ch) { return ch === null; }).length;
    if (empties !== 87) {
        return "其余 87 格应当保持空位（null），实际 " + empties;
    }
    return true;
});

check("流水线：帅/将**连检测框都没有**时，用 board-scan（字形+找色）在九宫里抢救，而不是整帧退回旧路径", function () {
    // 真机 2026-09-20 现场：红帅那一格检测框没检出 ⇒ 融合层里**连证据都没有**（不是 '?'）
    // ⇒ inferKings（只吃 `?` 格子）无从下手 ⇒ 规则门报「红帅数量 0」⇒ 面板丢弃整帧、退回旧路径。
    const host = fakeVisionHost.createFakeHost({
        pieces: [
            { col: 4, row: 9, label: "K", ocr: { text: "帅", score: 0.51 }, side: "red" },
            { col: 4, row: 0, label: "K", ocr: { text: "将", score: 0.92 }, side: "black" },
            { col: 0, row: 0, label: "R", ocr: { text: "车", score: 0.92 }, side: "black" }
        ],
        hiddenFromDetect: ["4,9"]        // 帅：检测没检出（模型对帅/将召回差）
    });
    const pipeline = visionPipelineModule.create({
        detect: host.detect,
        clip: host.clip,
        rgbAt: host.rgbAt,
        countColor: host.countColor,
        ocrSession: host.ocrSession,
        log: function () { }
    });
    const kingIndex = 4 + 9 * 9;         // (4,9) = 85
    let result = null;
    for (let i = 0; i < 3; i++) {
        result = pipeline.recognize({}, host.grid, { side: "w" });
    }
    if (result.chars[kingIndex] !== "K") {
        return "(4,9) 应当被抢救成 K，实际 " + JSON.stringify(result.chars[kingIndex]) +
            "（不抢救的话这一格是空的，规则门会报「红帅数量 0」）";
    }
    if (!result.stats.kingRescued) {
        return "统计里应当记 kingRescued，实际 " + result.stats.kingRescued;
    }
    if (!result.validation || !result.validation.ok) {
        return "抢救之后规则门应当通过，实际 " +
            (result.validation ? result.validation.reason : "没有 validation");
    }
    // 反例：九宫里也读不到帅（OCR 没字）⇒ 不许猜，仍交给调用方自己的兜底
    const blind = fakeVisionHost.createFakeHost({
        pieces: [
            { col: 4, row: 9, label: "K", ocr: null, side: "red" },
            { col: 4, row: 0, label: "K", ocr: { text: "将", score: 0.92 }, side: "black" }
        ],
        hiddenFromDetect: ["4,9"]
    });
    const pipeline2 = visionPipelineModule.create({
        detect: blind.detect,
        clip: blind.clip,
        rgbAt: blind.rgbAt,
        countColor: blind.countColor,
        ocrSession: blind.ocrSession,
        log: function () { }
    });
    let result2 = null;
    for (let i = 0; i < 3; i++) {
        result2 = pipeline2.recognize({}, blind.grid, { side: "w" });
    }
    if (result2.chars[kingIndex] === "K") {
        return "读不到帅就不该抢救（不许猜），实际补了一个 K";
    }
    // 第二路：帅被**检出**（label=K）但字形一个字也读不出 —— 用「检测类别当候选 + 九宫 + 找色颜色」补。
    // 同宫里故意放一个红仕（label=A）：它不该被当成帅（兵种不取框的类别，标签只用来挑候选）。
    const labelOnly = fakeVisionHost.createFakeHost({
        pieces: [
            { col: 4, row: 8, label: "K", ocr: null, side: "red" },
            { col: 3, row: 8, label: "A", ocr: null, side: "red" },
            { col: 4, row: 0, label: "K", ocr: { text: "将", score: 0.92 }, side: "black" }
        ]
    });
    const pipeline3 = visionPipelineModule.create({
        detect: labelOnly.detect,
        clip: labelOnly.clip,
        rgbAt: labelOnly.rgbAt,
        countColor: labelOnly.countColor,
        ocrSession: labelOnly.ocrSession,
        log: function () { }
    });
    const kingAt48 = 4 + 8 * 9;
    let result3 = null;
    for (let i = 0; i < 3; i++) {
        result3 = pipeline3.recognize({}, labelOnly.grid, { side: "w" });
    }
    if (result3.chars[kingAt48] !== "K") {
        return "(4,8) 应当用「检测类别候选 + 九宫 + 找色」抢救成 K，实际 " +
            JSON.stringify(result3.chars[kingAt48]);
    }
    if (!(result3.stats.kingRescuedByYolo >= 1)) {
        return "应当记 kingRescuedByYolo（YOLO 定位优先），实际 " + result3.stats.kingRescuedByYolo;
    }
    if (result3.chars[4 + 8 * 9 - 1] === "K") {
        return "宫里的红仕（label=A）不该被当成帅";
    }
    if (!result3.validation || !result3.validation.ok) {
        return "第二路抢救后规则门应当通过，实际 " +
            (result3.validation ? result3.validation.reason : "没有 validation");
    }
    return true;
});

check("全盘扫描：字形只给兵种、红黑必须来自像素 —— 差一色都不许硬塞（2026-09-20 镜像事故）", function () {
    const boardScanModule = require("../src/vision/board-scan.js");
    const grid = { originX: 100, originY: 100, stepX: 120, stepY: 120, orientation: "RED_BOTTOM" };
    const wood = [196, 168, 138];
    const host = {
        // 每一格 OCR 都读成「車」—— 只测红黑归属，不测字形
        session: { detect: function () { return [{ text: "車", score: 0.9 }]; } },
        rgbAt: function (image, x, y) {
            // 最右一列故意不给笔画 ⇒ 颜色证据不足，应当留 null（不猜）
            if (x > grid.originX + 7 * grid.stepX) {
                return wood;
            }
            if ((x + y) % 5 !== 0) {
                return wood;
            }
            const row = Math.round((y - grid.originY) / grid.stepY);
            return row <= 4 ? [58, 56, 54] : [176, 44, 40];   // 上半黑笔画、下半红笔画
        }
    };
    const result = boardScanModule.scan(host, {}, grid, {});
    if (!result.ok) {
        return "扫描应当成功，实际 " + result.reason;
    }
    // ⚠️ 这就是事故点：以前面板返回的是 `hit.cls`（全大写），驱动会把它当红方用
    if (result.chars[0] !== "r") {
        return "上半（黑方）那一格应当是**小写** r，实际 " + JSON.stringify(result.chars[0]) +
            "（回到全大写就说明红黑又被丢了）";
    }
    // index 81 = (col 0, row 9)：红方底线，避开最右列那组「故意不给笔画」的格子
    if (result.chars[81] !== "R") {
        return "下半（红方）那一格应当是大写 R，实际 " + JSON.stringify(result.chars[81]);
    }
    if (result.chars[8] !== null) {
        return "颜色证据不足的格子必须留 null（不猜），实际 " + JSON.stringify(result.chars[8]);
    }
    if (!(result.colourUnknown >= 9)) {
        return "最右一列（9 格）都应记为颜色未定，实际 " + result.colourUnknown;
    }
    return true;
});

check("颜色分类器：红笔画多 ⇒ RED、暗笔画多 ⇒ BLACK、样本太少/得分太近 ⇒ UNKNOWN（方案 §3.6）", function () {
    const red = [];
    for (let i = 0; i < 60; i++) {
        red.push([200, 40, 40]);   // 红字
    }
    const black = [];
    for (let i = 0; i < 60; i++) {
        black.push([40, 40, 40]);  // 黑字
    }
    const junk = [];
    for (let i = 0; i < 10; i++) {
        junk.push([180, 160, 140]);   // 木纹
    }
    const redVerdict = pieceColorModule.classifySamples(red);
    const blackVerdict = pieceColorModule.classifySamples(black);
    if (redVerdict.side !== "RED") {
        return "红字应当判 RED，实际 " + redVerdict.side + "（" + redVerdict.reason + "）";
    }
    if (blackVerdict.side !== "BLACK") {
        return "黑字应当判 BLACK，实际 " + blackVerdict.side + "（" + blackVerdict.reason + "）";
    }
    if (pieceColorModule.classifySamples(junk).side !== "UNKNOWN") {
        return "采样太少（<40）应当判 UNKNOWN";
    }
    return true;
});

check("颜色分类器：真机采样（木纹底 + 笔画混合）必须真的数出红/暗像素（2026-09-19 键名不匹配事故）", function () {
    // 纯色理想样本测不出「像素判定恒为 false」这类 Bug：黑字会靠「低饱和」侥幸过关。
    // 这里按真机分布构造：大半是木纹底，一小部分是笔画。
    const wood = [196, 168, 138];
    const mixedDark = [];
    for (let i = 0; i < 100; i++) {
        mixedDark.push(i % 5 === 0 ? [58, 56, 54] : wood);   // 20% 黑笔画
    }
    const mixedRed = [];
    for (let i = 0; i < 100; i++) {
        mixedRed.push(i % 5 === 0 ? [176, 44, 40] : wood);   // 20% 红笔画
    }
    const darkFeatures = pieceColorModule.features(mixedDark);
    const redFeatures = pieceColorModule.features(mixedRed);
    if (!(darkFeatures.darkRatio > 0.1)) {
        return "混合样本的 darkRatio 应当 >0.1（真的数到了暗像素），实际 " + darkFeatures.darkRatio +
            " —— 像素判定很可能又拿到了错的阈值键名";
    }
    if (!(redFeatures.redRatio > 0.1)) {
        return "混合样本的 redRatio 应当 >0.1（真的数到了红像素），实际 " + redFeatures.redRatio;
    }
    const darkVerdict = pieceColorModule.classifySamples(mixedDark);
    if (darkVerdict.side !== "BLACK") {
        return "木纹底 + 黑笔画应当判 BLACK，实际 " + darkVerdict.side + "（" + darkVerdict.reason + "）";
    }
    const redVerdict = pieceColorModule.classifySamples(mixedRed);
    if (redVerdict.side !== "RED") {
        return "木纹底 + 红笔画应当判 RED，实际 " + redVerdict.side + "（" + redVerdict.reason + "）";
    }
    return true;
});

check("颜色投票：单帧翻面保留上一帧并记冲突，不直接换阵营（方案 §3.6）", function () {
    const voter = pieceColorModule.createVoter();
    const red = [];
    for (let i = 0; i < 60; i++) {
        red.push([200, 40, 40]);
    }
    const black = [];
    for (let i = 0; i < 60; i++) {
        black.push([40, 40, 40]);
    }
    const first = voter.vote(10, red);
    if (first.side !== "RED") {
        return "第一帧应当是 RED，实际 " + first.side;
    }
    const flipped = voter.vote(10, black);
    if (flipped.side !== "RED" || !flipped.kept) {
        return "单帧翻面应当保留上一帧（RED）并标记 kept，实际 " + JSON.stringify(flipped);
    }
    if (voter.stats().conflicts !== 1) {
        return "应当记 1 次冲突，实际 " + voter.stats().conflicts;
    }
    // 看不清的一帧不能覆盖结论
    const unclear = voter.vote(10, [[180, 160, 140]]);
    if (unclear.side !== "UNKNOWN") {
        return "采样不足那一帧应当返回 UNKNOWN，实际 " + unclear.side;
    }
    return true;
});

check("颜色投票：连续读反 3 帧必须采纳新颜色（一次误读不能把整格颜色钉死，真机 2026-09-20）", function () {
    const red = [];
    for (let i = 0; i < 60; i++) {
        red.push([200, 40, 40]);
    }
    const black = [];
    for (let i = 0; i < 60; i++) {
        black.push([40, 40, 40]);
    }
    const voter = pieceColorModule.createVoter();
    if (voter.vote(10, red).side !== "RED") {
        return "第一帧应当是 RED";
    }
    const one = voter.vote(10, black);
    const two = voter.vote(10, black);
    if (one.side !== "RED" || two.side !== "RED") {
        return "前两帧读反应当保留上一帧（RED），实际 " + one.side + "/" + two.side;
    }
    const three = voter.vote(10, black);
    if (three.side !== "BLACK" || !three.swapped) {
        return "连续 3 帧读反就该采纳新颜色（BLACK）并标记 swapped，实际 " + JSON.stringify(three.side);
    }
    if (voter.stats().flips !== 1) {
        return "应当记 1 次「翻面被采纳」，实际 " + voter.stats().flips;
    }
    if (voter.vote(10, black).side !== "BLACK") {
        return "采纳之后应当稳定在 BLACK";
    }
    // 找色通道（ink-color）用的是同一个投票器，同一套翻转规则也得生效
    const ink = require("../src/vision/ink-color.js");
    const area = 68 * 68;
    const inkVoter = ink.createVoter();
    const redCounts = { red: Math.round(area * 0.09), black: 0, area: area, source: "findColor" };
    const blackCounts = { red: 0, black: Math.round(area * 0.13), area: area, source: "findColor" };
    if (inkVoter.vote(0, redCounts).side !== "RED") {
        return "找色通道第一帧应当是 RED";
    }
    inkVoter.vote(0, blackCounts);
    inkVoter.vote(0, blackCounts);
    if (inkVoter.vote(0, blackCounts).side !== "BLACK") {
        return "找色通道连续读反也必须能翻过来（否则黑子在路上永远被当成红子）";
    }
    return true;
});

check("状态机：声明「轮到我」必须能把状态从 RECOVERING 推出来（真机 2026-09-20 整轮空转）", function () {
    const machineModule = require("../src/automation/state-machine.js");
    const clock = { value: 0 };
    const machine = machineModule.createMachine({
        mySide: "w",
        allowClick: true,
        unknownTurnWatchdogMs: 1000,
        verifyTimeoutMs: 2000,
        movetimeMs: 100
    }, { nowMono: function () { return clock.value; } });
    machine.dispatch({ type: "START" });
    machine.dispatch({ type: "READY", modelVersion: "self-check" });
    machine.dispatch({ type: "GEOMETRY", geometryVersion: 1, trusted: true });
    const board = new Array(90);
    for (let i = 0; i < 90; i++) {
        board[i] = null;
    }
    board[0] = "r";
    const snapshot = function (side) {
        return {
            type: "SNAPSHOT",
            snapshot: {
                board: board.slice(), side: side, geometryTrusted: true,
                unknownCells: 0, conflicts: 0, stable: true
            }
        };
    };
    // 轮次未知 + 等过看门狗 ⇒ RECOVERING（超时不算「轮到我」）
    machine.dispatch(snapshot(null));
    clock.value = 5000;
    const tripped = machine.dispatch(snapshot(null));
    if (tripped.state !== "RECOVERING") {
        return "轮次未知超过看门狗后应当是 RECOVERING，实际 " + tripped.state;
    }
    // 用户声明「现在轮到我」：状态机必须能被推动（原来只发 RECOVER，声明进不来）
    const declared = machine.dispatch({ type: "TURN_DECLARED" });
    if (declared.state !== "WAIT_STABLE") {
        return "声明轮到我之后应当回到 WAIT_STABLE 重新观察，实际 " + declared.state +
            "（这就是真机「只恢复/等待」空转的原因）";
    }
    // 下一帧：轮到我方 ⇒ 应该真的开始算棋
    const thinking = machine.dispatch(snapshot("w"));
    if (thinking.state !== "THINKING") {
        return "基准确立的下一帧应当进入 THINKING，实际 " + thinking.state;
    }
    return true;
});

check("裁剪：只裁有棋子的交点，尺寸 = 格距×0.82，中心留 70%（方案 §3.4）", function () {
    const grid = { originX: 100, originY: 200, stepX: 100, stepY: 100, orientation: "RED_BOTTOM", version: 1 };
    const box = pieceCropper.cropBoxFor(grid, 0, 0);
    if (box.width !== 82 || box.height !== 82) {
        return "裁块应当 82×82，实际 " + box.width + "×" + box.height;
    }
    if (box.centerX !== 100 || box.centerY !== 200) {
        return "(0,0) 中心应当是 (100,200)，实际 (" + box.centerX + "," + box.centerY + ")";
    }
    const centre = pieceCropper.centreRegion(box, 0.7);
    if (centre.width > box.width || Math.abs(centre.x + centre.width / 2 - box.centerX) > 1) {
        return "中心区域不对：" + JSON.stringify(centre);
    }
    // 只裁传入的交点（给 2 个就只裁 2 个）
    const host = { clip: function () { return null; }, rgbAt: function () { return [1, 2, 3]; } };
    const crops = pieceCropper.cropAll(host, {}, grid, [{ index: 0 }, { index: 1 }]);
    if (crops.length !== 2) {
        return "应当只裁 2 块，实际 " + crops.length;
    }
    return true;
});

check("检测框→交点：一框一格、超过 0.38 格距拒绝、冲突取更近的（方案 §3.3）", function () {
    const grid = { originX: 0, originY: 0, stepX: 100, stepY: 100, orientation: "RED_BOTTOM", version: 1 };
    const boxes = [
        { x: -10, y: -10, width: 20, height: 20, centerX: 0, centerY: 0, score: 0.9 },        // 正中 (0,0)
        { x: 90, y: -10, width: 20, height: 20, centerX: 100, centerY: 0, score: 0.5 },       // 正中 (1,0) 但分低
        { x: 195, y: -10, width: 20, height: 20, centerX: 205, centerY: 0, score: 0.8 },      // (2,0) 偏 0.05 格
        { x: 900, y: 900, width: 20, height: 20, centerX: 910, centerY: 910, score: 0.9 }     // 远离任何交点
    ];
    const mapped = pieceDetector.mapToCells(boxes, grid);
    if (mapped.cells.length !== 3) {
        return "应当映射出 3 格，实际 " + mapped.cells.length + "（拒绝 " + mapped.rejected.length + "）";
    }
    if (mapped.rejected.length !== 1) {
        return "应当拒绝 1 个框，实际 " + mapped.rejected.length;
    }
    const zero = mapped.cells.filter(function (one) { return one.index === 0; });
    if (zero.length !== 1) {
        return "(0,0) 应当只收一个框，实际 " + zero.length;
    }
    // 同一格两个框：取更近的那个
    const conflict = pieceDetector.mapToCells([
        { centerX: 0, centerY: 0, width: 10, height: 10, score: 0.4 },
        { centerX: 6, centerY: 0, width: 10, height: 10, score: 0.9 }
    ], grid);
    if (conflict.cells.length !== 1 || Math.abs(conflict.cells[0].residual) > 0.01) {
        return "同格冲突应当保留更近的框，实际 " + JSON.stringify(conflict.cells);
    }
    return true;
});

check("几何跟踪：残差分级（≤0.22 保持 / >0.22 微调 / >0.35 重定位）且换几何必须递增版本（方案 §3.3）", function () {
    const tracker = gridTracker.create({ originX: 0, originY: 0, stepX: 100, stepY: 100, orientation: "RED_BOTTOM", version: 3 });
    if (tracker.review([{ residual: 0.1 }, { residual: 0.05 }]).action !== "keep") {
        return "残差 0.075 应当保持";
    }
    if (tracker.review([{ residual: 0.3 }]).action !== "micro") {
        return "残差 0.3 应当微调";
    }
    if (tracker.review([{ residual: 0.5 }]).action !== "relocate") {
        return "残差 0.5 应当重定位";
    }
    const next = tracker.update({ originX: 1, originY: 1, stepX: 100, stepY: 100, orientation: "RED_BOTTOM" }, "微调");
    if (next.version !== 4) {
        return "换几何必须 version+1（旧票据要靠它失效），实际 " + next.version;
    }
    return true;
});

check("证据融合：两帧一致才确认、三帧三种结果判未知、综合分数 ≥0.78（方案 §3.7/§3.8）", function () {
    const score = evidenceFusion.finalScore({ detectionScore: 0.9, ocrScore: 0.9, colorScore: 0.9 });
    if (Math.abs(score - 0.9) > 0.001) {
        return "三份证据都是 0.9 时综合分应当 0.9，实际 " + score;
    }
    const fuser = evidenceFusion.createFuser();
    const piece = { index: 40, cls: "C", side: "RED", detectionScore: 0.9, ocrScore: 0.9, colorScore: 0.9 };
    let out = fuser.push([piece]);
    if (out.chars[40] !== "?") {
        return "只一帧时不应确认（拿不准就 ?），实际 " + out.chars[40];
    }
    out = fuser.push([piece]);
    if (out.chars[40] !== "C") {
        return "两帧一致应当确认为 C，实际 " + out.chars[40];
    }
    // 三帧三种结论 ⇒ 未知
    const fuser2 = evidenceFusion.createFuser();
    fuser2.push([{ index: 40, cls: "C", side: "RED", detectionScore: 0.9, ocrScore: 0.9, colorScore: 0.9 }]);
    fuser2.push([{ index: 40, cls: "P", side: "RED", detectionScore: 0.9, ocrScore: 0.9, colorScore: 0.9 }]);
    const three = fuser2.push([{ index: 40, cls: "N", side: "RED", detectionScore: 0.9, ocrScore: 0.9, colorScore: 0.9 }]);
    if (three.chars[40] !== "?") {
        return "三帧三种结论应当判未知，实际 " + three.chars[40];
    }
    // 分数不够 ⇒ 不确认
    const low = evidenceFusion.pieceOf({ index: 0, cls: "C", side: "RED", detectionScore: 0.5, ocrScore: 0.5, colorScore: 0.5 });
    if (low.char !== "?") {
        return "综合分 0.5（<0.78）应当输出 ?，实际 " + low.char;
    }
    return true;
});

/**
 * 真机回归（2026-09-21，K40 败局复盘）：**对手的应手起点/落点被游戏「上一步」标记盖住** ⇒
 * 观测层「顽固未知 → 按旧基准填」把那几格填回原样 ⇒ 差分看起来「没动」⇒ 轮次永远停在
 * 「轮到对方」，每帧空转，直到被对手 8 步绝杀（现场日志 09:20:41→09:24:15 全是
 * 「顽固未知 1 格=(1,4)」+「状态 WAIT_TURN｜轮到对方（b）」，而对方其实早已走出 b7b5）。
 *
 * 现在：凡本帧有「按旧基准填」的格子，就先试「被盖住的应手」——枚举走棋方的全部合法着法，
 * 只有一招能把非被盖格全部对上（还要连续两帧佐证）才采信。
 */
check("过渡：『被盖住的应手』——起点/落点都读不出来时，唯一合法着法能认出来、两解则不认", function () {
    // ① 唯一解：标准开局轮黑方，取他的一步不吃子走法；观测 = 走完后把**起点和落点都填回原值**
    //    （真机死区盘面：读不出来 → 按旧基准填 → 看上去那枚子根本没动）
    let reply = null;
    const list = legal.allLegalMoves(START_BOARD, "b");
    for (let i = 0; i < list.length && !reply; i += 1) {
        const d0 = transition.squareIndex(list[i].iccs, 1);
        if (d0 >= 0 && START_BOARD[d0] == null) {
            reply = list[i];
        }
    }
    if (!reply) {
        return "夹具失败：开局黑方找不到不吃子走法";
    }
    const src = transition.squareIndex(reply.iccs, 0);
    const dest = transition.squareIndex(reply.iccs, 1);
    const moved = legal.applyMove(START_BOARD, reply).board;
    const observed = moved.slice();
    observed[src] = START_BOARD[src];       // 起点被盖住 → 填回旧值：看上去没走
    observed[dest] = START_BOARD[dest];     // 落点被盖住 → 填回旧值：看上去还是空的
    const hit = transition.inferSingleThroughCovered(START_BOARD, "b", observed, [src, dest]);
    if (!hit || hit.iccs !== reply.iccs) {
        return "被盖住的唯一应手没认出来：期望 " + reply.iccs + "，实际 " + (hit ? hit.iccs : "null");
    }
    if (hit.board[dest] !== moved[dest] || hit.board[src] != null) {
        return "认出来的盘面不对（起点应空、落点应有子）";
    }
    if (hit.nextSide !== "w") {
        return "认手之后应当轮到红方，实际 " + hit.nextSide;
    }
    // ② 两解 ⇒ 绝不认：孤车有两个「被盖住」的落点，两种走法都说得通
    const amb = fen.parseFen("r4k3/9/9/9/9/9/9/9/9/3K5 b - - 0 1").board;
    const ambHit = transition.inferSingleThroughCovered(amb, "b", amb.slice(), [0, 1, 2]);
    if (ambHit !== null) {
        return "两解时不许认，实际认了 " + ambHit.iccs;
    }
    // ③ 没有「被盖住」的格 ⇒ 不归这条路管（普通差分/枚举的活）
    if (transition.inferSingleThroughCovered(START_BOARD, "b", moved, []) !== null) {
        return "没有盖住的格时不应出手";
    }
    return true;
});

check("驱动：对手应手被「上一步」标记盖住（起点/落点读不出、填回原样）也要能推出来（K40 死区回归）", function () {
    const afterOwn = legal.applyMove(START_BOARD, OWN_MOVE).board;
    let reply = null;
    const list = legal.allLegalMoves(afterOwn, "b");
    for (let i = 0; i < list.length && !reply; i += 1) {
        const d0 = transition.squareIndex(list[i].iccs, 1);
        if (d0 >= 0 && afterOwn[d0] == null) {
            reply = list[i];
        }
    }
    if (!reply) {
        return "夹具失败：应手找不到不吃子走法";
    }
    const afterReply = legal.applyMove(afterOwn, reply).board;
    const src = transition.squareIndex(reply.iccs, 0);
    const dest = transition.squareIndex(reply.iccs, 1);
    let observations = 0;
    const notes = [];
    const fakeMachine = {
        dispatch: function (event) {
            const effects = [];
            if (event.type === "READY") {
                effects.push({ type: "CALIBRATE" });
            }
            return { effects: effects, reason: event.type };
        },
        state: function () { return "WAIT_TURN"; },
        reason: function () { return "fixture"; },
        mySide: function () { return "w"; },
        setMySide: function () { },
        canClick: function () { return { ok: false, reason: "fixture" }; },
        counters: function () { return {}; },
        versions: function () { return { sessionId: 1 }; },
        setSession: function () { }
    };
    const driver = driverModule.createDriver({
        allowClick: false,
        machine: fakeMachine,
        nowMono: function () { return 1000; },
        sleep: function () { },
        recognize: function () {
            observations += 1;
            let board;
            if (observations === 1) {
                board = START_BOARD.slice();           // 第 1 帧：开局基准（轮红）
            } else if (observations <= 3) {
                board = afterOwn.slice();              // 第 2~3 帧：我方走了一手（跨帧佐证后采信，轮黑）
            } else {
                board = afterReply.slice();            // 第 4 帧起：对方应手已走，但**起点+落点都被盖住**
                board[src] = "?";
                board[dest] = "?";
            }
            let unknown = 0;
            for (let i = 0; i < board.length; i += 1) {
                if (board[i] === "?") {
                    unknown += 1;
                }
            }
            return { ok: true, chars: board, unknownCells: unknown, conflicts: 0 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { return { ok: false, reason: "本用例不算棋" }; },
        log: function (text) { notes.push(String(text)); },
        config: { observeFrames: 1 }
    });
    driver.start();
    for (let i = 0; i < 12; i += 1) {
        driver.tick();
    }
    if (!notes.some(function (text) {
        return text.indexOf("顽固未知") >= 0 || text.indexOf("与上一张可靠盘面一致") >= 0;
    })) {
        return "夹具没走到『按基准填回未知格』（测试没覆盖目标路径）：" + notes.slice(-16).join(" / ") +
            "｜stats=" + JSON.stringify(driver.stats());
    }
    const rel = driver.lastReliable();
    if (!rel || rel.side !== "w") {
        return "被盖住的应手没推出来（仍停在 " + (rel ? rel.side : "无基准") + "）：" +
            notes.slice(-5).join(" / ");
    }
    if (rel.board[dest] !== afterReply[dest]) {
        return "推出来的着法没落进基准盘（" + dest + "=" + String(rel.board[dest]) + "）";
    }
    if (!notes.some(function (text) { return text.indexOf("盖住的应手 " + reply.iccs) >= 0; })) {
        return "没有『盖住的应手』日志：" + notes.slice(-5).join(" / ");
    }
    return true;
});

check("驱动：对手应手『占用唯一解』快路 —— 起点空 + 落点有子、字形全读不出来也能定手（免 OCR）", function () {
    const afterOwn = legal.applyMove(START_BOARD, OWN_MOVE).board;
    // 找一手对方的「不吃子」应手（落点本来是空格，最干净）
    let reply = null;
    const list = legal.allLegalMoves(afterOwn, "b");
    for (let i = 0; i < list.length && !reply; i += 1) {
        const d0 = transition.squareIndex(list[i].iccs, 1);
        if (d0 >= 0 && afterOwn[d0] == null) {
            reply = list[i];
        }
    }
    if (!reply) {
        return "夹具失败：应手找不到不吃子走法";
    }
    const afterReply = legal.applyMove(afterOwn, reply).board;
    const src = transition.squareIndex(reply.iccs, 0);
    const dest = transition.squareIndex(reply.iccs, 1);
    /** 由盘面造占用图（红大写→"R"，黑小写→"B"；空格→"."） */
    function occupancyOf(board) {
        const occ = new Array(90);
        for (let i = 0; i < 90; i += 1) {
            const ch = board[i];
            occ[i] = ch == null ? "." : (ch === ch.toUpperCase() ? "R" : "B");
        }
        return occ;
    }
    function buildCase(replyFastPath) {
        let observations = 0;
        const localNotes = [];
        const fakeMachine = {
            dispatch: function (event) {
                const effects = [];
                if (event.type === "READY") {
                    effects.push({ type: "CALIBRATE" });
                }
                return { effects: effects, reason: event.type };
            },
            state: function () { return "WAIT_TURN"; },
            reason: function () { return "fixture"; },
            mySide: function () { return "w"; },
            setMySide: function () { },
            canClick: function () { return { ok: false, reason: "fixture" }; },
            counters: function () { return {}; },
            versions: function () { return { sessionId: 1 }; },
            setSession: function () { },
            currentTicket: function () { return null; }
        };
        const driver = driverModule.createDriver({
            allowClick: false,
            machine: fakeMachine,
            nowMono: function () { return 1000; },
            sleep: function () { },
            recognize: function () {
                observations += 1;
                if (observations === 1) {
                    return { ok: true, chars: START_BOARD.slice(), unknownCells: 0, conflicts: 0 };
                }
                if (observations <= 3) {
                    // 我方走了一手（跨帧佐证后采信，轮黑）——与『盖住的应手』用例同一套夹具
                    return { ok: true, chars: afterOwn.slice(), unknownCells: 0, conflicts: 0 };
                }
                // 应手已落地：**字形全读不出来**（'?'），但占用图/变化格是硬的 —— 快路只认这两样
                const board = afterReply.slice();
                board[src] = "?";
                board[dest] = "?";
                return {
                    ok: true, chars: board, unknownCells: 2, conflicts: 0,
                    occupancy: occupancyOf(afterReply), changed: [src, dest]
                };
            },
            locate: function () { return { ok: true, grid: TEST_GRID }; },
            analyze: function () { return { ok: false, reason: "本用例不算棋" }; },
            log: function (text) { localNotes.push(String(text)); },
            config: { observeFrames: 1, replyFastPath: replyFastPath }
        });
        driver.start();
        for (let i = 0; i < 12; i += 1) {
            driver.tick();
        }
        return { driver: driver, notes: localNotes };
    }
    // ① 开（默认）：占用唯一解 ⇒ 免 OCR 定手，基准轮次翻回我方、盘面含应手
    const on = buildCase(true);
    const relOn = on.driver.lastReliable();
    if (!relOn || relOn.side !== "w") {
        return "快路没定手（基准仍停在 " + (relOn ? relOn.side : "无") + "）：" + on.notes.slice(-8).join(" / ");
    }
    if (relOn.board[dest] !== afterReply[dest] || relOn.board[src] != null) {
        return "定手后的盘面不对（起点应空、落点应是 " + String(afterReply[dest]) + "）";
    }
    if (!on.notes.some(function (text) { return text.indexOf("占用唯一解 " + reply.iccs) >= 0; })) {
        return "没有『占用唯一解』日志：" + on.notes.slice(-5).join(" / ");
    }
    // ② 关（A/B 对照）：同样的帧不许走快路（旧路径在字形 '?'、无补齐记录时推不出来）
    const off = buildCase(false);
    if (off.notes.some(function (text) { return text.indexOf("占用唯一解 ") >= 0; })) {
        return "关掉 replyFastPath 之后仍然走了快路";
    }
    if (off.notes.some(function (text) { return text.indexOf("对手应手=占用唯一解") >= 0; })) {
        return "关掉 replyFastPath 之后仍有采纳日志";
    }
    return true;
});

/**
 * 真机回归（2026-09-24，中途卡顿最后一层）：对方应手的**起点被「找色作废」置空、落点被「幻影清除」擦掉**，
 * 修订后的基准 = 观测 ⇒ 差分追踪看到「没变化」⇒ 轮次永远停在「轮到对方」，只能等 15s 判反兜底。
 *
 * 真实事故：我方确认后，对方同一枚马先走一步（马 → (2,2)），再走一步（(2,2) → (0,1)）。
 * 第二手落地的帧里「已知事实作废（找色证据是空格）：(2,2)n」把起点从基准删掉，
 * 下一帧起「清掉幻影（重复子）(0,1)n→空」又把落点从观测擦掉 —— 这一手被两步修订抹平。
 *
 * 修法（对账修订）：修订器登记「修订前的基准」；sideFor 发现「修订发生在等对方时、
 * 且观测里走棋方少了一枚子」⇒ 借回旧基准重算轮次（修订+观测的累计差 = 那一手）⇒ 两帧佐证后翻轮次。
 */
check("驱动：对方应手被『找色作废+幻影清除』抹平，也要按『对账修订』借回旧基准认出来（真机 02:58 回归）", function () {
    const afterOwn1 = legal.applyMove(START_BOARD, OWN_MOVE).board;
    let reply1 = null;
    let reply2 = null;
    let OWN2 = null;
    let afterOwn2 = null;
    let afterReply2 = null;
    let dest1 = -1;
    let dest2 = -1;
    const blacks1 = legal.allLegalMoves(afterOwn1, "b");
    for (let i = 0; i < blacks1.length && !reply1; i += 1) {
        const f1 = transition.squareIndex(blacks1[i].iccs, 0);
        const d1 = transition.squareIndex(blacks1[i].iccs, 1);
        if (f1 < 0 || d1 < 0 || afterOwn1[d1] != null) {
            continue;   // 只要不吃子的应手
        }
        const aR1 = legal.applyMove(afterOwn1, blacks1[i]).board;
        const reds2 = legal.allLegalMoves(aR1, "w");
        for (let j = 0; j < reds2.length && !OWN2; j += 1) {
            const f2 = transition.squareIndex(reds2[j].iccs, 0);
            const d2 = transition.squareIndex(reds2[j].iccs, 1);
            if (f2 < 0 || d2 < 0 || aR1[d2] != null || f2 === d1 || d2 === d1) {
                continue;   // 我方第二步也不吃子、不碰那枚马
            }
            const aO2 = legal.applyMove(aR1, reds2[j]).board;
            const blacks2 = legal.allLegalMoves(aO2, "b");
            for (let k = 0; k < blacks2.length; k += 1) {
                if (transition.squareIndex(blacks2[k].iccs, 0) !== d1) {
                    continue;   // 要的是**同一枚子**再走一步
                }
                const d3 = transition.squareIndex(blacks2[k].iccs, 1);
                if (d3 < 0 || aO2[d3] != null) {
                    continue;   // 还是不吃子（落点在观测里呈「多出来」的形态）
                }
                reply1 = blacks1[i];
                OWN2 = reds2[j];
                reply2 = blacks2[k];
                afterOwn2 = aO2;
                dest1 = d1;
                dest2 = d3;
                afterReply2 = legal.applyMove(aO2, reply2).board;
                break;
            }
            if (reply2) {
                break;
            }
        }
    }
    if (!reply1 || !reply2) {
        return "夹具失败：找不到「同一枚黑子走两步」的着法组合";
    }
    const afterReply1 = legal.applyMove(afterOwn1, reply1).board;
    const own1Dest = transition.squareIndex(OWN_MOVE.iccs, 1);
    const own2Dest = transition.squareIndex(OWN2.iccs, 1);
    const charAt = {};
    charAt[dest1] = afterReply1[dest1];       // 那枚黑子（走到 (2,2) 之后）
    charAt[dest2] = afterReply2[dest2];       // 同一枚黑子（再走到 (0,1)）
    charAt[own1Dest] = afterOwn1[own1Dest];
    charAt[own2Dest] = afterOwn2[own2Dest];
    const stage = { board: START_BOARD.slice(), move: OWN_MOVE, incident: false, tapCount: 0, onToTap: null };
    const notes = [];
    const driver = driverModule.createDriver({
        allowClick: true,
        log: function (text) { notes.push(String(text)); },
        sleep: function () { },
        recognize: function () {
            return {
                ok: true, chars: stage.board.slice(), unknownCells: 0, conflicts: 0,
                layout: null, centers: null, grid: TEST_GRID
            };
        },
        ocrCell: function (query) {
            const idx = query && query.index;
            const ch = charAt[idx];
            if (!ch) {
                return null;   // 其他格：没有证据（不猜）
            }
            const lower = String(ch).toLowerCase();
            const side = (String(ch) === String(ch).toUpperCase() && String(ch) !== lower) ? "RED" : "BLACK";
            if (idx === dest1 && stage.incident) {
                // 那枚子已经走了：这格是空的（找色两边都没有墨迹）—— 正是这一帧触发「找色作废」
                return { ok: true, side: null, cls: null, ink: { red: 0, black: 0, area: 10000 } };
            }
            return {
                ok: true, side: side, cls: ch, char: ch,
                ink: side === "RED" ? { red: 600, black: 0, area: 10000 }
                    : { red: 0, black: 600, area: 10000 }
            };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () {
            return { ok: true, iccs: stage.move.iccs, move: stage.move, score: 0, depth: 1, ms: 1 };
        },
        tap: function (x, y, tapOptions) {
            if (tapOptions && tapOptions.kind === "to") {
                stage.tapCount += 1;
                // ⚠️ 必须在**点击回调内**就把盘面切到「走成了」——确认在同一个 tick 里就跑，
                //    切晚了会把真走成的局面判成 UNCHANGED（真机同样如此），进而恢复/重建把夹具带偏。
                if (typeof stage.onToTap === "function") {
                    const once = stage.onToTap;
                    stage.onToTap = null;
                    once();
                }
            }
            return true;
        },
        screen: function () { return { width: 1080, height: 2400 }; },
        nowMono: function () { return Date.now(); },
        config: { movetimeMs: 20, observeFrames: 1, ocrCooldownMs: 0 }
    });
    const same = function (a, b) {
        for (let i = 0; i < 90; i += 1) {
            if (!a || a[i] !== b[i]) {
                return false;
            }
        }
        return true;
    };
    const waitFor = function (pred, maxTicks) {
        for (let i = 0; i < maxTicks; i += 1) {
            driver.tick();
            if (pred()) {
                return true;
            }
        }
        return false;
    };
    driver.start();
    driver.tick();
    driver.assertTurn();
    // ① 我方第一手（点击）：等点出去 + 观测认下来
    stage.tapCount = 0;
    stage.onToTap = function () { stage.board = afterOwn1.slice(); };
    if (!waitFor(function () { return stage.tapCount >= 1; }, 12)) {
        return "夹具没走到我方第一手点击：状态 " + driver.machine.state() +
            "（" + driver.machine.reason() + "）｜日志 " + notes.slice(-6).join(" / ");
    }
    if (!waitFor(function () {
        const rel = driver.lastReliable();
        return rel && rel.side === "b" && same(rel.board, afterOwn1);
    }, 10)) {
        return "我方第一手没落定：日志 " + notes.slice(-8).join(" / ");
    }
    for (let i = 0; i < 3; i += 1) {
        driver.tick();   // 让确认结算走完
    }
    // ② 对方应手①（正常差分，应被认出来；同时登记「起点格」的已知事实，其 ply=本手）
    stage.move = OWN2;                     // 轮次翻回我方后，机器会立刻算并点第二手
    stage.board = afterReply1.slice();
    if (!waitFor(function () {
        const rel = driver.lastReliable();
        return rel && rel.side === "w" && same(rel.board, afterReply1);
    }, 10)) {
        return "对方应手①没被认出来：状态 " + driver.machine.state() +
            "｜日志 " + notes.slice(-8).join(" / ");
    }
    // ③ 我方第二手（点击）：出手后 moveEpoch +1 ⇒ 应手①登记的记录沦为「旧记录」（走证据路径）
    stage.tapCount = 0;
    stage.onToTap = function () { stage.board = afterOwn2.slice(); };
    if (!waitFor(function () { return stage.tapCount >= 1; }, 14)) {
        return "夹具没走到我方第二手点击：状态 " + driver.machine.state() +
            "（" + driver.machine.reason() + "）｜日志 " + notes.slice(-8).join(" / ");
    }
    if (!waitFor(function () {
        const rel = driver.lastReliable();
        return rel && rel.side === "b" && same(rel.board, afterOwn2);
    }, 12)) {
        return "我方第二手没落定：日志 " + notes.slice(-8).join(" / ");
    }
    for (let i = 0; i < 3; i += 1) {
        driver.tick();
    }
    // ④ 事故帧：对方同一枚马再走一格 —— 起点读空（找色作废）+ 落点在旧基准里没有（幻影清除）
    stage.incident = true;
    stage.board = afterReply2.slice();
    const flipped = waitFor(function () {
        const rel = driver.lastReliable();
        return rel && rel.side === "w";
    }, 16);
    const rel = driver.lastReliable();
    if (!flipped) {
        return "对账修订没接住：应手被修订抹平后轮次没翻回来（停在 " +
            (rel ? rel.side : "无基准") + "）｜日志 " + notes.slice(-10).join(" / ");
    }
    if (!same(rel.board, afterReply2)) {
        return "轮次翻回来了但盘面不对（dest1=" + String(rel.board[dest1]) +
            "，dest2=" + String(rel.board[dest2]) + "，期望 dest2=" + String(afterReply2[dest2]) + "）";
    }
    if (!notes.some(function (text) { return text.indexOf("可靠基准已按观测改正") >= 0; })) {
        return "夹具没走到『找色作废』（没覆盖目标路径）：" + notes.slice(-8).join(" / ");
    }
    if (!notes.some(function (text) { return text.indexOf("对账修订") >= 0; })) {
        return "没有『对账修订』借旧基准的日志：" + notes.slice(-8).join(" / ");
    }
    if (notes.some(function (text) { return text.indexOf("对账修订超时") >= 0; })) {
        return "对账修订超时了（应手没在时限内认出来）：" + notes.slice(-8).join(" / ");
    }
    return true;
});

check("applyMove：只带 iccs 的着法也必须真的走子（2026-09-21 卡确认真因回归）", function () {
    const src = transition.squareIndex(OWN_MOVE.iccs, 0);
    const dest = transition.squareIndex(OWN_MOVE.iccs, 1);
    const viaIccs = legal.applyMove(START_BOARD, { iccs: OWN_MOVE.iccs });
    const viaCoords = legal.applyMove(START_BOARD, OWN_MOVE);
    if (!viaIccs.board) {
        return "只带 iccs 时 applyMove 返回了空盘面（应从 iccs 解析出四元坐标）";
    }
    if (viaIccs.board[src] != null) {
        return "起点没被清空（着法没真的应用）";
    }
    if (viaIccs.board[dest] == null || viaIccs.board[dest] !== viaCoords.board[dest]) {
        return "iccs 版落点与四元坐标版不一致";
    }
    if (!legal.boardMatches(viaIccs.board, viaCoords.board)) {
        return "iccs 版盘面与四元坐标版整体不一致";
    }
    const bogus = legal.applyMove(AFTER_BOARD, { iccs: OWN_MOVE.iccs });
    if (bogus.board !== null) {
        return "起点已空时仍返回了盘面（会把落点那枚子抹掉）";
    }
    return true;
});

check("验证器：按当前基准重建票据盘面（期望必须带上这一手；走不通不重建）", function () {
    const ticket = sampleTicket({});
    // ① 当前基准 = 走之前：重建出的期望必须 = 「基准 + 这一手」，绝不能与 base 相同
    const rebuilt = verifier.rebuildTicketBoards(ticket, START_BOARD);
    if (!rebuilt) {
        return "重建返回 null（夹具失败？）";
    }
    if (rebuilt.expectedAfterFen === rebuilt.baseFen) {
        return "重建出的期望 == base：着法没被应用（2026-09-21 真机卡确认的原始病根）";
    }
    const rebuiltAfter = fen.parseFen(rebuilt.expectedAfterFen);
    if (!rebuiltAfter.ok || !legal.boardMatches(rebuiltAfter.board, AFTER_BOARD)) {
        return "重建出的期望盘面与「base + 这一手」不一致";
    }
    // ② 基准已经把这一手消化掉（起点已空）⇒ 必须拒绝重建（否则会把落点抹掉）
    if (verifier.rebuildTicketBoards(ticket, AFTER_BOARD) !== null) {
        return "起点已空时仍重建了盘面";
    }
    // ③ 端到端：重建后的票据 + 「已走成」的观测 ⇒ CONFIRMED（修复前这里是无限 UNRELIABLE）
    const verdict = verifier.verify({
        ticket: {
            moveICCS: ticket.moveICCS,
            baseSide: ticket.baseSide,
            baseFen: rebuilt.baseFen,
            expectedAfterFen: rebuilt.expectedAfterFen
        },
        observed: AFTER_BOARD.slice(),
        stable: true,
        geometryTrusted: true,
        conflicts: 0
    });
    if (verdict.result !== verifier.RESULT.CONFIRMED) {
        return "重建后的票据 + 已落子的观测没有 CONFIRMED：" + verifier.describe(verdict);
    }
    return true;
});

check("驱动：盘面自检 —— 「轮到的一方 0 合法着法」不许喂引擎（假双将空转回归）", function () {
    // 真机 2026-09-21 现场盘面：d7 黑士被漏读 ⇒ 黑炮「隔一子打帅」不被挡 ⇒ 假双将
    const mateFen = "3ck4/3Ra4/4b4/2N6/4N4/9/9/9/9/2rK5 w - - 0 1";
    const mate = fen.parseFen(mateFen);
    if (!mate.ok || legal.allLegalMoves(mate.board, "w").length !== 0) {
        return "夹具失败：这不是一个红方 0 着法的局面";
    }
    let analyzeCalls = 0;
    let jobResults = 0;
    const notes = [];
    const fakeMachine = {
        dispatch: function (event) {
            if (event.type === "READY") {
                return { effects: [{ type: "COMPUTE", jobId: "job-mate",
                    board: mate.board.slice(), side: "w", movetimeMs: 100 }], reason: "fixture" };
            }
            if (event.type === "JOB_RESULT") {
                jobResults += 1;
            }
            return { effects: [], reason: event.type };
        },
        state: function () { return "THINKING"; },
        reason: function () { return "fixture"; },
        mySide: function () { return "w"; },
        setMySide: function () { },
        canClick: function () { return { ok: false, reason: "fixture" }; },
        counters: function () { return {}; },
        versions: function () { return { sessionId: 1 }; },
        setSession: function () { },
        currentTicket: function () { return null; }
    };
    const driver = driverModule.createDriver({
        allowClick: false,
        machine: fakeMachine,
        nowMono: function () { return 1000; },
        sleep: function () { },
        recognize: function () {
            return { ok: true, chars: mate.board.slice(), unknownCells: 0, conflicts: 0 };
        },
        locate: function () { return { ok: true, grid: TEST_GRID }; },
        analyze: function () { analyzeCalls += 1; return { ok: false, reason: "不该被调用" }; },
        log: function (text) { notes.push(String(text)); },
        config: { observeFrames: 1 }
    });
    driver.start();
    driver.tick();
    if (analyzeCalls !== 0) {
        return "盘面自检没拦住，引擎还是被调用了";
    }
    if (jobResults !== 1) {
        return "JOB_RESULT 没有回给状态机（jobResults=" + jobResults + "）";
    }
    if (!notes.some(function (text) { return text.indexOf("盘面自检不通过") >= 0; })) {
        return "没有『盘面自检不通过』日志：" + notes.slice(-6).join(" / ");
    }
    return true;
});

check("ink-color.looksEmpty：找色压线的黑士（5.8%<6%）靠暗墨迹不判空；空格/星光照旧判空", function () {
    const inkColor = require("../src/vision/ink-color.js");
    const area = 104 * 104;
    // 真机实测（K40，d7 黑士，偏心 28px 的裁剪中心）：黑≈5.83%（<6% 线）、暗≈8.3%
    const d7 = { red: 6, black: Math.round(area * 0.0583), dark: Math.round(area * 0.083), area: area };
    if (inkColor.looksEmpty(d7)) {
        return "压线的黑士被误判为空（旧口径的真身）";
    }
    if (!inkColor.looksEmpty({ red: 0, black: 0, dark: 0, area: area })) {
        return "空格没被判空";
    }
    // 星光标记（历史帧 4.2% 红、暗 0）⇒ 仍判空（不许把旧的骗色问题放回来）
    if (!inkColor.looksEmpty({ red: Math.round(area * 0.042), black: 0, dark: 0, area: area })) {
        return "星光标记格没被判空（会重新引入旧病）";
    }
    if (inkColor.looksEmpty({ red: 0, black: Math.round(area * 0.10), dark: 0, area: area })) {
        return "正常黑子被误判为空";
    }
    if (inkColor.looksEmpty({ red: Math.round(area * 0.14), black: 0, dark: 0, area: area })) {
        return "红子被误判为空";
    }
    return true;
});

check("board-scan：空框格偏心读成「土」时，1.15×宽窗重读救回「士」（d7 漏读回归）", function () {
    const boardScanMod = require("../src/vision/board-scan.js");
    const grid = { originX: 100, originY: 100, stepX: 115, stepY: 114, cols: 9, rows: 10, orientation: "RED_BOTTOM", version: 1 };
    const d7 = 3 + 2 * 9;
    const session = {
        detect: function (img, options) {
            const region = options && options.region;
            if (!region) { return []; }
            const w = Number(region[2]);
            const col = Math.round((Number(region[0]) + w / 2 - grid.originX) / grid.stepX);
            const row = Math.round((Number(region[1]) + Number(region[3]) / 2 - grid.originY) / grid.stepY);
            if (col !== 3 || row !== 2) { return []; }
            // 窄窗：模型读出的是「土」（无效字）；宽窗：完整地看到「士」
            return w >= 120 ? [{ text: "士", score: 0.9 }] : [{ text: "土", score: 0.8 }];
        }
    };
    const host = {
        session: session,
        countColor: function (image, color, options) {
            const region = options && options.region;
            if (!region) { return 0; }
            const x = region.x != null ? region.x : region[0];
            const y = region.y != null ? region.y : region[1];
            const w = region.width != null ? region.width : region[2];
            const h = region.height != null ? region.height : region[3];
            const col = Math.round((x + w / 2 - grid.originX) / grid.stepX);
            const row = Math.round((y + h / 2 - grid.originY) / grid.stepY);
            if (col !== 3 || row !== 2) { return 0; }
            const area = Math.max(1, Math.round(w) * Math.round(h));
            const ref = String(color).toUpperCase();
            if (ref === "#282830") { return Math.round(area * 0.058); }   // 找色黑：压 6% 线
            if (ref === "#303030") { return Math.round(area * 0.083); }   // 暗墨迹：稳过
            return 0;
        }
    };
    const out = boardScanMod.scan(host, {}, grid, {});
    if (!out || out.wideRetries < 1) {
        return "没走宽窗重读（wideRetries=" + (out && out.wideRetries) + "）";
    }
    if (out.wideHits < 1) {
        return "宽窗重读没救回字形（wideHits=" + out.wideHits + "）";
    }
    if (out.chars[d7] !== "a") {
        return "(3,2) 应读出黑士 a，实际 " + JSON.stringify(out.chars[d7]) +
            "｜glyphs=" + JSON.stringify(out.glyphs[d7]) + "｜scores=" + JSON.stringify(out.scores[d7]);
    }
    return true;
});

// ---------------------------------------------------------------- 几何搜索（多证据融合最小版）

check("几何搜索：未知格/覆盖率定位阶段只罚不拒（方案 §10 最小版）", function () {
    const trueGrid = geometry.createGrid({ originX: 100, originY: 100, stepX: 100, stepY: 100, orientation: "RED_BOTTOM" });
    const dets = [
        { label: "R", score: 0.9, x: 100, y: 100 },
        { label: "N", score: 0.9, x: 300, y: 100 },
        { label: "K", score: 0.9, x: 500, y: 100 },
        { label: "P", score: 0.9, x: -500, y: -500 },   // 棋盘外（压低 coverage）
        { label: "P", score: 0.9, x: -600, y: -600 },
        { label: "P", score: 0.9, x: -700, y: -700 }
    ];
    for (let i = 0; i < 5; i++) {
        // 只拿到弱证据（0.3 < minScore 0.45）⇒ 5 个未知格（旧硬门槛 >4 会一票否决）
        dets.push({ label: "C", score: 0.3, x: 100 + i * 100, y: 300 });
    }
    const soft = gridSearch.searchByDetections(dets, {
        seeds: [trueGrid], radius: 0, step: 1, stepScales: [1]
    });
    if (!soft.ok) {
        return "软门槛下应当接受（旧行为会拒绝），实际：" + soft.reason;
    }
    const strict = gridSearch.searchByDetections(dets, {
        seeds: [trueGrid], radius: 0, step: 1, stepScales: [1],
        requireUnknown: true, requireCoverage: true
    });
    if (strict.ok) {
        return "显式 requireUnknown/requireCoverage 时应当拒绝";
    }
    const joined = (strict.best && strict.best.reasons ? strict.best.reasons.join("；") : "");
    if (joined.indexOf("未知格") < 0 || joined.indexOf("比例过低") < 0) {
        return "拒绝理由应同时含「未知格过多」与「比例过低」，实际：" + joined;
    }
    return true;
});

check("几何搜索：种子格距偏 3% 时步距缩放（±2%/±4%）能救回", function () {
    const seed = { originX: 120, originY: 200, stepX: 112.5, stepY: 112.5, orientation: "RED_BOTTOM" };
    const spots = [[0, 0, "K"], [1, 0, "R"], [8, 9, "k"], [4, 5, "N"], [2, 7, "P"]];
    const dets = spots.map(function (s) {
        return { label: s[2], score: 0.9, x: 120 + s[0] * 116, y: 200 + s[1] * 116 };
    });
    const found = gridSearch.searchByDetections(dets, { seeds: [seed], radius: 3, step: 3 });
    if (!found.ok) {
        return "带步距缩放搜索应当能找到可行几何：" + found.reason;
    }
    if (Math.abs(found.grid.stepX - 116) > 116 * 0.025) {
        return "格距应被纠到 116±2.5%，实际 " + found.grid.stepX;
    }
    const pinned = gridSearch.searchByDetections(dets, { seeds: [seed], radius: 3, step: 3, stepScales: [1] });
    if (!pinned.ok) {
        return "只搜平移时也应当有可行几何（对照组）";
    }
    if (Math.abs(pinned.grid.stepX - 116) <= Math.abs(found.grid.stepX - 116)) {
        return "对照组（不搜步距）不应与搜索组一样准：pinned=" + pinned.grid.stepX + " found=" + found.grid.stepX;
    }
    return true;
});

check("几何搜索：默认种子按屏幕推导（不再写死 K40 坐标）", function () {
    const k40 = gridSearch.deriveSeeds({ width: 1080, height: 2400 });
    if (!k40 || !k40.length) {
        return "1080×2400 应当能推导出种子";
    }
    if (Math.abs(k40[0].stepX - 115.6) > 4) {
        return "K40 格距应 ≈115.6（实测 115.9），实际 " + k40[0].stepX;
    }
    const ys = k40.map(function (s) { return s.originY; }).join(",");
    if (ys.indexOf("666") < 0) {
        return "中间档 y 应当是 K40 实测的 666（0.2775×2400），实际 " + ys;
    }
    const big = gridSearch.deriveSeeds({ width: 1280, height: 2772 });
    if (!big || !big.length) {
        return "1280×2772 应当能推导出种子";
    }
    const ratio = big[0].stepX / k40[0].stepX;
    if (Math.abs(ratio - 1280 / 1080) > 0.03) {
        return "格距应随屏幕短边等比缩放（期望 " + (1280 / 1080).toFixed(3) + "），实际 " + ratio.toFixed(3);
    }
    if (gridSearch.deriveSeeds(null) !== null) {
        return "无效尺寸应当返回 null（调用方退回兜底常量）";
    }
    return true;
});

check("定位候选评分：条数/残差/补线/警告合成分数（选优取代「首个即胜」）", function () {
    const mk = function (vq, hq, warning) {
        return {
            ok: true, warning: warning || "",
            vertical: { matched: vq.m, quality: { matched: vq.m, maxResidual: vq.r, reconstructed: vq.c }, lineScore: vq.s },
            horizontal: { matched: hq.m, quality: { matched: hq.m, maxResidual: hq.r, reconstructed: hq.c }, lineScore: hq.s }
        };
    };
    const good = mk({ m: 9, r: 0.02, c: 0, s: 0.8 }, { m: 10, r: 0.03, c: 0, s: 0.8 }, "");
    const poor = mk({ m: 9, r: 0.2, c: 1, s: 0.4 }, { m: 9, r: 0.25, c: 1, s: 0.4 }, "棋盘范围与格点阵对不齐");
    const goodScore = boardLocator.scoreLocateResult(good);
    const poorScore = boardLocator.scoreLocateResult(poor);
    if (!(goodScore > poorScore)) {
        return "好候选分数应更高：good=" + goodScore + " poor=" + poorScore;
    }
    if (boardLocator.isAcceptableLocate(poor)) {
        return "带警告/补线的候选不应算「足够好」";
    }
    if (!boardLocator.isAcceptableLocate(good)) {
        return "无警告、9/9+10/10、残差小的候选应当算「足够好」";
    }
    if (isFinite(boardLocator.scoreLocateResult({ ok: false }))) {
        return "失败的定位结果不应参与比较（-Infinity）";
    }
    return true;
});

check("几何搜索：探测裁剪网格覆盖所有种子（3 档 y 不被裁）", function () {
    const seeds = gridSearch.deriveSeeds({ width: 1080, height: 2400 });
    const probe = gridSearch.probeGridFor(seeds);
    if (!probe) {
        return "应当生成探测网格";
    }
    const first = seeds[0];
    const last = seeds[seeds.length - 1];
    const needTop = first.originY;
    const needBottom = last.originY + 9 * last.stepY;
    const probeBottom = probe.originY + 9 * probe.stepY;
    if (!(probe.originY <= needTop) || !(probeBottom >= needBottom)) {
        return "探测网格 y 范围 [" + probe.originY + "," + probeBottom +
            "] 没盖住种子区 [" + needTop + "," + needBottom + "]";
    }
    const needLeft = first.originX;
    const needRight = first.originX + 8 * first.stepX;
    const probeRight = probe.originX + 8 * probe.stepX;
    if (!(probe.originX <= needLeft) || !(probeRight >= needRight)) {
        return "探测网格 x 范围没盖住种子区";
    }
    return true;
});

// ---------------------------------------------------------------- 格线探测器（多证据融合 P1）

/** 合成一条「线响应」：centers = [{at, h}] 处放三角形峰（离线测梳状拟合用）。 */
function mkLatticeResponse(from, to, step, centers, shape) {
    const n = Math.floor((to - from) / step) + 1;
    const values = new Array(n);
    const base = shape && shape.base != null ? shape.base : 0.02;
    const half = ((shape && shape.width) || 8) / 2;
    for (let i = 0; i < n; i++) {
        values[i] = base;
        const x = from + i * step;
        for (let c = 0; c < centers.length; c++) {
            const d = Math.abs(x - centers[c].at);
            if (d <= half) {
                values[i] += (centers[c].h == null ? 1 : centers[c].h) * (1 - d / (half + 1));
            }
        }
    }
    return { axis: "x", from: from, to: to, step: step, values: values };
}

check("格线探测器：梳状拟合恢复 9 条竖线（±3px），遮挡 2 条也能扛（trimmed）", function () {
    const truth = [82, 197, 312, 427, 542, 657, 772, 887, 1002];
    const centers = truth.map(function (at) { return { at: at, h: 1 }; });
    const resp = mkLatticeResponse(0, 1080, 4, centers, { base: 0.03, width: 8 });
    const fit = latticeDetector.combFit(resp, 9, { stepMin: 75, stepMax: 151 });
    if (!fit.best) {
        return "没出候选";
    }
    if (Math.abs(fit.best.origin - 82) > 3) {
        return "origin 应 ≈82±3，实际 " + fit.best.origin;
    }
    if (Math.abs(fit.best.step - 115) > 2) {
        return "step 应 ≈115±2，实际 " + fit.best.step;
    }
    // 遮挡第 2、6 条（被棋子盖住）后仍要恢复
    const covered = centers.map(function (c, i) {
        return (i === 2 || i === 6) ? { at: c.at, h: 0 } : c;
    });
    const resp2 = mkLatticeResponse(0, 1080, 4, covered, { base: 0.03, width: 8 });
    const fit2 = latticeDetector.combFit(resp2, 9, { stepMin: 75, stepMax: 151 });
    if (!fit2.best) {
        return "遮挡后没出候选";
    }
    if (Math.abs(fit2.best.origin - 82) > 3 || Math.abs(fit2.best.step - 115) > 2) {
        return "遮挡 2 条后应仍恢复：origin=" + fit2.best.origin + " step=" + fit2.best.step;
    }
    return true;
});

check("格线探测器：半格诱饵不许胜出（真梳 115 vs 半格梳 57.5）", function () {
    const truth = [82, 197, 312, 427, 542, 657, 772, 887, 1002];
    const centers = truth.map(function (at) { return { at: at, h: 1 }; });
    for (let k = 0; k < truth.length - 1; k++) {
        centers.push({ at: (truth[k] + truth[k + 1]) / 2, h: 0.45 });
    }
    // stepMin 放宽到 40，半格距 57.5 也在搜索范围内——诱饵真的会被评到
    const resp = mkLatticeResponse(0, 1080, 4, centers, { base: 0.03, width: 8 });
    const fit = latticeDetector.combFit(resp, 9, { stepMin: 40, stepMax: 151 });
    if (!fit.best) {
        return "没出候选";
    }
    if (Math.abs(fit.best.step - 115) > 3) {
        return "应选真格距 115（半格诱饵 57.5 不许胜），实际 " + fit.best.step;
    }
    return true;
});

check("格线探测器：外侧两线被整行棋子压住时锚点不许偏、证据仍 10/10（2026-09-24 真机回放）", function () {
    // 真机四张截图的共性：起始局面首末两行整行摆满棋子，外线只剩 ~22% 支撑度（0.3 vs 1.3）
    const truth = [695, 809.3, 923.6, 1037.9, 1152.2, 1266.5, 1380.8, 1495.1, 1609.4, 1723.7];
    const centers = truth.map(function (at, i) {
        return { at: at, h: (i === 0 || i === truth.length - 1) ? 0.3 : 1.3 };
    });
    const resp = mkLatticeResponse(0, 2400, 4, centers, { base: 0.02, width: 10 });
    const fit = latticeDetector.combFit(resp, 10, { stepMin: 100, stepMax: 130 });
    if (!fit.best) {
        return "没出候选";
    }
    // 修复前：锚点 -1 的平移梳把第一条线放到棋盘外的空槽（0.0x），trimmed 求和与真梳完全同分，
    // 靠扫描顺序选错 → 582；现在「存在的线」加成让真梳胜出。
    if (Math.abs(fit.best.origin - 695) > 3) {
        return "锚点应 ≈695（不许被 ±1 格距平移梳抢走），实际 " + fit.best.origin.toFixed(1);
    }
    // 入口门槛（matched）与平移加成共用同一个「存在的线」判据（lineEvidence.present）
    const present = latticeDetector.lineEvidence(fit.best.perLine, {}).present;
    if (present !== 10) {
        return "外线被压住仍应算存在（10/10），实际 " + present;
    }
    return true;
});

check("格线探测器：真缺线时证据掉下门槛（横线 9/10 不许凑）", function () {
    const truth = [695, 809.3, 923.6, 1037.9, 1152.2, 1266.5, 1380.8, 1495.1, 1609.4, 1723.7];
    const centers = truth.map(function (at, i) {
        const missing = (i === 3 || i === 6);   // 两条内部线真的没有（如被弹窗盖死）
        return { at: at, h: missing ? 0 : ((i === 0 || i === 9) ? 0.3 : 1.3) };
    });
    const resp = mkLatticeResponse(0, 2400, 4, centers, { base: 0.02, width: 10 });
    const fit = latticeDetector.combFit(resp, 10, { stepMin: 100, stepMax: 130 });
    if (!fit.best) {
        return "没出候选";
    }
    const present = latticeDetector.lineEvidence(fit.best.perLine, {}).present;
    if (present > 8) {
        return "两条线真缺失时证据应 ≤8（门槛 9/10），实际 " + present;
    }
    return true;
});

check("格线探测器：lineEvidence 口径（22% 支持度算存在、空槽与纯噪声不算）", function () {
    const perLine = [0.55, 2.5, 2.2, 1.8, 2.4, 1.9, 2.6, 2.1, 2.3, 0.6];
    if (latticeDetector.lineEvidence(perLine, {}).present !== 10) {
        return "0.55/2.5=22% 的外线应算存在";
    }
    if (latticeDetector.lineEvidence([0.55, 2.5, 2.2, 1.8, 2.4, 1.9, 2.6, 2.1, 2.3, 0.02], {}).present !== 9) {
        return "空槽（0.02）不许算存在";
    }
    if (latticeDetector.lineEvidence([0, 0.65, 2.7, 2.2, 1.4, 0], {}).present !== 4) {
        return "0.65 算存在、0.0 不算（应 4 条）";
    }
    // 纯噪声图（2026-09-24「回到应用」文件列表截图实测：全线 0.01~0.02）：相对门槛会降到 0.004，
    // 全部「存在」→ 必须靠绝对下限（minLineSupport=0.15）全部拦下
    const noise = [0.01, 0.02, 0.01, 0.02, 0.01, 0.02, 0.01, 0.02, 0.02, 0.02];
    if (latticeDetector.lineEvidence(noise, {}).present !== 0) {
        return "纯噪声（0.01~0.02）不许算存在，实际 " + latticeDetector.lineEvidence(noise, {}).present;
    }
    // 强线（≥最强线×0.3）只算 3 条（2.7/2.2/1.4），0.65 不算
    if (latticeDetector.lineEvidence([0, 0.65, 2.7, 2.2, 1.4, 0], {}).strong !== 3) {
        return "强线应为 3 条，实际 " + latticeDetector.lineEvidence([0, 0.65, 2.7, 2.2, 1.4, 0], {}).strong;
    }
    return true;
});

check("格线探测器：假宿主端到端恢复棋盘（竖 115 / 横 114）", function () {
    const vLines = [82, 197, 312, 427, 542, 657, 772, 887, 1002];
    const hLines = [695, 809, 923, 1037, 1151, 1265, 1379, 1493, 1607, 1721];
    const fakeImage = { width: 1080, height: 2400 };
    const fakeHost = {
        sizeOf: function () { return { width: 1080, height: 2400 }; },
        countColor: function (image, color, options) {
            const region = options.region;
            const vertical = region.width <= 3;   // 竖条带 vs 横条带
            const lines = vertical ? vLines : hLines;
            const start = vertical ? region.x : region.y;
            const end = vertical ? region.x + region.width : region.y + region.height;
            let hits = 0;
            for (let i = 0; i < lines.length; i++) {
                // 线画 3px 宽（[c-1, c+2)）：避开「2px 线 + 4px 采样」的采样混叠
                const overlap = Math.min(end, lines[i] + 2) - Math.max(start, lines[i] - 1);
                if (overlap > 0) {
                    hits += overlap * (vertical ? region.height : region.width);
                }
            }
            return Math.round(hits);
        }
    };
    const out = latticeDetector.detect(fakeHost, fakeImage, {});
    if (!out.ok) {
        return "假宿主下应当成功：" + out.reason;
    }
    if (Math.abs(out.grid.originX - 82) > 4) {
        return "originX 应 ≈82±4，实际 " + out.grid.originX;
    }
    if (Math.abs(out.grid.stepX - 115) > 2.5) {
        return "stepX 应 ≈115±2.5，实际 " + out.grid.stepX;
    }
    if (Math.abs(out.grid.originY - 695) > 4) {
        return "originY 应 ≈695±4，实际 " + out.grid.originY;
    }
    if (Math.abs(out.grid.stepY - 114) > 2.5) {
        return "stepY 应 ≈114±2.5，实际 " + out.grid.stepY;
    }
    if (!(out.calls > 500)) {
        return "应当发起多次找色调用，实际 " + out.calls;
    }
    if (!(out.evidence.vertical.matched >= 7)) {
        return "竖线 matched 应 ≥7，实际 " + out.evidence.vertical.matched;
    }
    return true;
});

// ---------------------------------------------------------------- 字形模板识别（电脑 py 移植）

/** 字形识别的假宿主：按「90 格画像表」回答三道门计数、模板匹配（region/裁剪窗/半尺寸）与阵营取样。
 *  cells["r,c"] = { name, sim, simCoarse?, side?, dark?, inner?, bright?, dy? }（不在表里 = 空格）。
 *  dy = 该格字形相对基准中心的纵向漂移（模拟 lattice 网格的逐行漂移）。 */
function makeGlyphFake(options) {
    const opts = options || {};
    const grid = opts.grid || { originX: 100, originY: 100, stepX: 100, stepY: 100 };
    const cells = opts.cells || {};
    const anchorDy = opts.anchorDy == null ? glyphRecognizer.DEFAULTS.ANCHOR_DY : opts.anchorDy;
    const TW = glyphRecognizer.DEFAULTS.TW;
    const width = opts.width || 1200;
    const height = opts.height || 1400;
    const calls = { matchRegions: [], clips: [], counts: [], resizes: [], pixels: 0, halfMatches: 0 };
    let clock = 0;
    const cellAt = function (r, c) {
        return (r >= 0 && r < 10 && c >= 0 && c < 9) ? (cells[r + "," + c] || null) : null;
    };
    const cellOfCell = function (x, y) {      // 格中心（x, y）→ 画像
        return cellAt(Math.round((y - grid.originY) / grid.stepY), Math.round((x - grid.originX) / grid.stepX));
    };
    const cellOfWindow = function (cx, cy) {  // 采样点（x, y≈字形中心）→ 画像
        return cellAt(Math.round((cy + 5 - grid.originY) / grid.stepY), Math.round((cx - grid.originX) / grid.stepX));
    };
    /** 某格的「真实字形中心」（格点 + ANCHOR + 可选漂移） */
    const centerOf = function (key) {
        const parts = key.split(",");
        const r = Number(parts[0]);
        const c = Number(parts[1]);
        const cell = cells[key] || {};
        return {
            x: Math.round(grid.originX + c * grid.stepX),
            y: Math.round(grid.originY + r * grid.stepY + anchorDy + (cell.dy || 0))
        };
    };
    /** 全尺寸窗匹配语义：模板中心可落在窗口中心 ±(半宽 − TW) 内 */
    const matchInWindow = function (wx, wy, ww, wh, template, threshold) {
        let best = null;
        for (const key in cells) {
            const cell = cells[key];
            if (!cell || cell.name !== (template && template.name)) {
                continue;
            }
            const center = centerOf(key);
            if (Math.abs(center.x - (wx + ww / 2)) > (ww / 2 - TW) ||
                Math.abs(center.y - (wy + wh / 2)) > (wh / 2 - TW)) {
                continue;
            }
            const sim = cell.sim == null ? 0.9 : cell.sim;
            if (sim < threshold) {
                continue;
            }
            if (!best || sim > best.sim) {
                best = { sim: sim, x: center.x, y: center.y };
            }
        }
        return best;
    };
    return {
        calls: calls,
        sizeOf: function () { return { width: width, height: height }; },
        grayscale: function (image) { return { gray: image }; },
        clip: function (image, x, y, w, h) {
            calls.clips.push([x, y, w, h]);
            return { kind: "win", x: x, y: y, width: w, height: h, cell: cellOfCell(x + w / 2, y + h / 2) };
        },
        threshold: function (win, t, max) { return { kind: t === 140 ? "dark" : "bright", cell: win.cell, width: win.width }; },
        countColor: function (bin, color, options) {
            const region = options.region;
            calls.counts.push({ kind: bin.kind, x: region.x, y: region.y, w: region.width, h: region.height });
            const cell = bin.cell;
            if (!cell) {
                return 0;
            }
            if (bin.kind === "dark" && color === "#000000") {
                if (region.width === 40 && region.height === 40) {
                    return Math.round((cell.inner == null ? 0.5 : cell.inner) * 1600);
                }
                return Math.round((cell.dark == null ? 0.3 : cell.dark) * bin.width * bin.width);
            }
            if (bin.kind === "bright" && color === "#000000") {
                // 亮面门：数「≤200 的暗像素」再把亮面算回来
                return Math.round((1 - (cell.bright == null ? 0.5 : cell.bright)) * bin.width * bin.width);
            }
            return 0;
        },
        matchTemplate: function (image, template, options) {
            const threshold = options && options.threshold != null ? options.threshold : 0.7;
            if (image && image.kind === "half") {
                // 半尺寸粗搜：窗口/中心都按半坐标（粗模板边长 = TW/2）
                calls.halfMatches += 1;
                const region = options.region;
                const halfSide = TW / 2;
                const cx = region[0] + region[2] / 2;
                const cy = region[1] + region[3] / 2;
                let best = null;
                for (const key in cells) {
                    const cell = cells[key];
                    if (!cell || cell.name !== (template && template.name)) {
                        continue;
                    }
                    const center = centerOf(key);
                    const hx = center.x / 2;
                    const hy = center.y / 2;
                    if (Math.abs(hx - cx) > (region[2] / 2 - halfSide) ||
                        Math.abs(hy - cy) > (region[3] / 2 - halfSide)) {
                        continue;
                    }
                    const sim = cell.simCoarse == null ? (cell.sim == null ? 0.9 : cell.sim) : cell.simCoarse;
                    if (sim < threshold) {
                        continue;
                    }
                    if (!best || sim > best.sim) {
                        best = { sim: sim, x: hx, y: hy };
                    }
                }
                if (!best) {
                    return { matches: [] };
                }
                return { matches: [{ similarity: best.sim, point: { x: best.x - halfSide, y: best.y - halfSide } }] };
            }
            let win = null;
            if (image && image.kind === "win") {
                win = [image.x, image.y, image.width, image.height];
            } else if (options && options.region) {
                win = options.region;
                calls.matchRegions.push(options.region);
            } else {
                win = [0, 0, width, height];
            }
            const hit = matchInWindow(win[0], win[1], win[2], win[3], template, threshold);
            if (!hit) {
                return { matches: [] };
            }
            return { matches: [{ similarity: hit.sim, point: { x: hit.x - win[0] - TW, y: hit.y - win[1] - TW } }] };
        },
        pixel: function (image, x, y) {
            calls.pixels += 1;
            const cell = cellOfWindow(x, y);
            if (cell && cell.side === "r") {
                return { r: 176, g: 64, b: 48 };
            }
            return { r: 56, g: 58, b: 58 };
        },
        red: function (p) { return p.r; },
        green: function (p) { return p.g; },
        blue: function (p) { return p.b; },
        resize: function (image, size) {
            calls.resizes.push(size);
            // 整帧缩一半 → 半尺寸帧（金字塔粗搜用）；模板缩一半 → 保留 name（粗搜要按名匹配）
            if (size[0] === Math.round(width / 2) && size[1] === Math.round(height / 2)) {
                return { kind: "half", of: image };
            }
            return { resized: size, of: image, name: image && image.name };
        },
        nowMono: function () { clock += 5; return clock; }
    };
}

function glyphMockTemplates() {
    // ⚠️ recognize 把 templates[i].image 传给 host.matchTemplate —— 名字要挂在图像对象上
    return glyphRecognizer.TPL_NAMES.map(function (name) { return { name: name, image: { name: name, tpl: name } }; });
}

check("字形识别：端到端（假宿主）——三字判色、低分记未知、空格跳过", function () {
    const fake = makeGlyphFake({
        cells: {
            "0,0": { name: "車", sim: 0.95, side: "r" },
            "0,4": { name: "將", sim: 0.92 },
            "4,4": { name: "炮", sim: 0.88, side: "b" },
            "9,0": { name: "兵", sim: 0.93 },
            "9,4": { name: "帥", sim: 0.90 },
            "2,2": { name: "相", sim: 0.65 }
        }
    });
    const out = glyphRecognizer.recognize(fake, { color: true },
        { originX: 100, originY: 100, stepX: 100, stepY: 100 }, { templates: glyphMockTemplates() });
    if (out.error) {
        return "识别异常：" + out.error;
    }
    if (out.chars[0] !== "R") {
        return "r0c0 車+红 应 → R，实际 " + out.chars[0];
    }
    if (out.chars[4] !== "k") {
        return "r0c4 將 应 → k，实际 " + out.chars[4];
    }
    if (out.chars[40] !== "c") {
        return "r4c4 炮+黑 应 → c，实际 " + out.chars[40];
    }
    if (out.chars[81] !== "P") {
        return "r9c0 兵 应 → P，实际 " + out.chars[81];
    }
    if (out.chars[85] !== "K") {
        return "r9c4 帥 应 → K，实际 " + out.chars[85];
    }
    if (out.chars[20] !== "?") {
        return "r2c2 模板分 0.65 < 0.70 应 → ?，实际 " + out.chars[20];
    }
    if (out.chars[1] !== ".") {
        return "空格应 → .，实际 " + out.chars[1];
    }
    if (out.inked !== 6 || out.unknown !== 1) {
        return "触子/未知应为 6/1，实际 " + out.inked + "/" + out.unknown;
    }
    return true;
});

check("字形识别：窄窗裁剪（横 ±6/纵 ±6）与内窗位置（[14,9,40,40]）", function () {
    const fake = makeGlyphFake({ cells: { "3,3": { name: "卒", sim: 0.9 } } });
    const out = glyphRecognizer.recognize(fake, { color: true },
        { originX: 100, originY: 100, stepX: 100, stepY: 100 }, { templates: glyphMockTemplates() });
    if (out.error) {
        return "识别异常：" + out.error;
    }
    // 格 (3,3)：x=400、y=400、cy=395 → 窄窗 [400-34-6, 395-34-6, 80, 80]（落帧内时走裁剪窗）
    const narrow = fake.calls.clips.filter(function (c) { return c[2] === 80 && c[3] === 80; })[0];
    if (!narrow || narrow[0] !== 360 || narrow[1] !== 355) {
        return "窄窗裁剪应为 [360,355,80,80]，实际 " + JSON.stringify(narrow);
    }
    const hasFull = fake.calls.counts.some(function (c) {
        return c.kind === "dark" && c.x === 0 && c.y === 0 && c.w === 68 && c.h === 68;
    });
    const hasInner = fake.calls.counts.some(function (c) {
        return c.kind === "dark" && c.x === 14 && c.y === 9 && c.w === 40 && c.h === 40;
    });
    const hasBright = fake.calls.counts.some(function (c) {
        return c.kind === "bright" && c.w === 68 && c.h === 68;
    });
    if (!hasFull || !hasInner || !hasBright) {
        return "应分别数「整窗 68×68 暗」「内窗 [14,9,40,40] 暗」「亮面（数暗像素算回来）」";
    }
    if (out.chars[30] !== "p") {
        return "r3c3 卒应 → p，实际 " + out.chars[30];
    }
    if (out.retries !== 0) {
        return "0.9 分不该触发复核，实际 " + out.retries;
    }
    return true;
});

check("字形识别：宽窗复核（漂移超出窄窗时靠 ±20 兜住）", function () {
    const fake = makeGlyphFake({ cells: { "0,3": { name: "卒", sim: 0.93, dy: 12 } } });
    const out = glyphRecognizer.recognize(fake, { color: true },
        { originX: 100, originY: 100, stepX: 100, stepY: 100 }, { templates: glyphMockTemplates() });
    if (out.error) {
        return "识别异常：" + out.error;
    }
    if (out.chars[3] !== "p") {
        return "r0c3（漂移 12px）应靠宽窗找到 → p，实际 " + out.chars[3];
    }
    if (out.retries !== 1) {
        return "应复核 1 次（窄窗 12>6 出窗），实际 " + out.retries;
    }
    const wide = fake.calls.clips.some(function (c) { return c[3] === 108; });
    if (!wide) {
        return "复核应使用宽窗（高 108）";
    }
    return true;
});

check("字形识别：行自适应——上一行实测 dy 传给下一行窄窗", function () {
    const fake = makeGlyphFake({
        cells: {
            "4,0": { name: "兵", sim: 0.9, dy: 12 },
            "4,2": { name: "兵", sim: 0.9, dy: 12 },
            "5,1": { name: "兵", sim: 0.9, dy: 12 }
        }
    });
    const out = glyphRecognizer.recognize(fake, { color: true },
        { originX: 100, originY: 100, stepX: 100, stepY: 100 }, { templates: glyphMockTemplates() });
    if (out.error) {
        return "识别异常：" + out.error;
    }
    if (out.chars[36] !== "P" || out.chars[38] !== "P" || out.chars[46] !== "P") {
        return "三格都应读出 P，实际 " + out.chars[36] + out.chars[38] + out.chars[46];
    }
    if (out.retries !== 1) {
        return "只应复核 1 次（第一个格子；之后行内/下一行都用窄窗），实际 " + out.retries;
    }
    // r5c1：y=600、cy=595、dy=12 → 窄窗中心 607 → 裁剪 [160,567,80,80]
    const hit = fake.calls.clips.some(function (c) { return c[0] === 160 && c[1] === 567 && c[2] === 80; });
    if (!hit) {
        return "r5c1 应使用「上一行 dy=+12」修正后的窄窗 [160,567,80,80]";
    }
    return true;
});

check("字形识别：金字塔——半尺寸粗搜提名 + 全尺寸精修；粗搜没提名则回退全搜", function () {
    // A) 粗搜提名成功：精修分 0.95 ≥ SAFE，直接采纳（无回退）
    const fakeA = makeGlyphFake({ cells: { "3,3": { name: "卒", sim: 0.95, simCoarse: 0.8 } } });
    const tplsA = glyphMockTemplates();
    const prepA = glyphRecognizer.prepareFor(fakeA, tplsA, 1);
    const outA = glyphRecognizer.recognize(fakeA, { color: true },
        { originX: 100, originY: 100, stepX: 100, stepY: 100 },
        { templates: prepA.templates, TW: prepA.TW, ANCHOR_DY: prepA.ANCHOR_DY, coarseTemplates: prepA.coarse });
    if (outA.error) {
        return "识别异常：" + outA.error;
    }
    if (outA.chars[30] !== "p") {
        return "r3c3 应 → p，实际 " + outA.chars[30];
    }
    if (outA.retries !== 0) {
        return "精修分够高不应回退，实际复核 " + outA.retries;
    }
    if (fakeA.calls.halfMatches !== 11) {
        return "粗搜应跑 11 个模板（半尺寸），实际 " + fakeA.calls.halfMatches;
    }
    if (prepA.coarse.length !== 11 || prepA.coarse[0].side !== 34) {
        return "粗模板应为 11 个、边长 34（TW）";
    }

    // B) 粗搜全部低于门限 → 无提名 → 回退「窄+宽」全搜仍能读出
    const fakeB = makeGlyphFake({ cells: { "3,3": { name: "卒", sim: 0.95, simCoarse: 0.1 } } });
    const tplsB = glyphMockTemplates();
    const prepB = glyphRecognizer.prepareFor(fakeB, tplsB, 1);
    const outB = glyphRecognizer.recognize(fakeB, { color: true },
        { originX: 100, originY: 100, stepX: 100, stepY: 100 },
        { templates: prepB.templates, TW: prepB.TW, ANCHOR_DY: prepB.ANCHOR_DY, coarseTemplates: prepB.coarse });
    if (outB.error) {
        return "识别异常：" + outB.error;
    }
    if (outB.chars[30] !== "p") {
        return "回退全搜后应 → p，实际 " + outB.chars[30];
    }
    if (outB.retries !== 1) {
        return "应复核 1 次（金字塔回退），实际 " + outB.retries;
    }
    return true;
});

check("字形识别：normalizeBoard（'.'→null）与未知格计数", function () {
    const chars = new Array(90).fill(".");
    chars[0] = "R";
    chars[10] = "?";
    const board = glyphRecognizer.normalizeBoard(chars);
    if (board[0] !== "R" || board[1] !== null || board[10] !== "?") {
        return "normalizeBoard 不符：" + JSON.stringify([board[0], board[1], board[10]]);
    }
    if (glyphRecognizer.countUnknown(board) !== 1) {
        return "未知格应为 1，实际 " + glyphRecognizer.countUnknown(board);
    }
    return true;
});

check("选中放大：占位未变时保留棋子身份，原始读数仍可追查", function () {
    const before = new Array(90).fill(null);
    const seen = before.slice();
    before[83] = "B";
    seen[83] = "A";
    const held = glyphRecognizer.preserveIdentityOnSameOccupancy(seen, before);
    if (held.held !== 1 || held.board[83] !== "B" || seen[83] !== "A") {
        return "同位误读应保留红相，原始数组不得改写";
    }
    const moved = seen.slice();
    moved[83] = null;
    moved[74] = "B";
    const accepted = glyphRecognizer.preserveIdentityOnSameOccupancy(moved, before);
    return accepted.held === 0 && accepted.board[74] === "B" || "真实移子不应被旧身份保护挡住";
});

check("圆心几何校验：正确交点通过、半格偏移拒绝、少子不误拒", function () {
    const grid = { originX: 83, originY: 704, stepX: 115, stepY: 114 };
    const circles = [[0, 0], [4, 0], [8, 0], [1, 2], [4, 5], [2, 9]].map(function (xy) {
        return { x: grid.originX + xy[0] * grid.stepX,
            y: grid.originY + xy[1] * grid.stepY, radius: 40 };
    });
    const aligned = circleGridCheck.evaluate(circles, grid);
    const shifted = circleGridCheck.evaluate(circles, Object.assign({}, grid,
        { originX: grid.originX + grid.stepX * 0.5 }));
    const sparse = circleGridCheck.evaluate(circles.slice(0, 2), grid);
    return aligned.ok && aligned.matched === 6 && shifted.conclusive && !shifted.ok &&
        !sparse.conclusive || "圆心证据判定不符";
});

check("字形识别：prepareFor 缓存（同缩放比不重复缩放；粗模板随行给出）", function () {
    const fake = makeGlyphFake({});
    const tpls = glyphMockTemplates();
    const p1 = glyphRecognizer.prepareFor(fake, tpls, 1.185);
    if (p1.TW !== 40 || p1.cached !== false || p1.coarse.length !== 11 || p1.coarse[0].side !== 40) {
        return "首次准备应为 TW=40 / 非缓存 / 11 粗模板(side 40)，实际 " +
            JSON.stringify([p1.TW, p1.cached, p1.coarse.length, p1.coarse[0].side]);
    }
    if (fake.calls.resizes.length !== 22) {
        return "应缩放 22 次（11 模板 + 11 粗模板），实际 " + fake.calls.resizes.length;
    }
    const p2 = glyphRecognizer.prepareFor(fake, tpls, 1.185);
    if (p2.cached !== true || fake.calls.resizes.length !== 22) {
        return "同缩放比应命中缓存且不再缩放";
    }
    const p3 = glyphRecognizer.prepareFor(fake, tpls, 1);
    if (p3.TW !== 34 || p3.cached !== false || fake.calls.resizes.length !== 33) {
        return "换缩放比应重算（scale≈1 只补 11 个粗模板），实际 resizes=" + fake.calls.resizes.length;
    }
    return true;
});

check("字形识别：跨分辨率 prepareTemplates（scale 1.185 → TW 40 / 模板 80×80）", function () {
    const fake = makeGlyphFake({});
    const tpls = glyphMockTemplates();
    const scaled = glyphRecognizer.prepareTemplates(fake, tpls, 1.185);
    if (scaled.TW !== 40) {
        return "TW 应 40，实际 " + scaled.TW;
    }
    if (scaled.ANCHOR_DY !== -6) {
        return "ANCHOR_DY 应 -6，实际 " + scaled.ANCHOR_DY;
    }
    if (fake.calls.resizes.length !== tpls.length) {
        return "应逐模板 resize（" + tpls.length + " 次），实际 " + fake.calls.resizes.length + " 次";
    }
    if (fake.calls.resizes[0][0] !== 80 || fake.calls.resizes[0][1] !== 80) {
        return "应缩放到 80×80，实际 " + JSON.stringify(fake.calls.resizes[0]);
    }
    const same = glyphRecognizer.prepareTemplates(fake, tpls, 0.995);
    if (same.TW !== 34 || fake.calls.resizes.length !== tpls.length) {
        return "scale≈1 不应 resize（TW 应 34）";
    }
    return true;
});

check("字形识别：charsToFen 布局", function () {
    const chars = new Array(90).fill(".");
    chars[0] = "R";
    const fen = glyphRecognizer.charsToFen(chars);
    if (fen !== "R8/9/9/9/9/9/9/9/9/9") {
        return "FEN 不符：" + fen;
    }
    return true;
});

check("定位融合：lattice 结果映射成旧结果形状（面板兼容）", function () {
    const grid = { originX: 82, originY: 695, stepX: 115.9, stepY: 114.1, orientation: "RED_BOTTOM" };
    const latticeOut = {
        ok: true, grid: grid, score: 3.51, squareness: 0.0157, calls: 640,
        evidence: {
            vertical: { origin: 82, step: 115.9, matched: 9, score: 1.8 },
            horizontal: { origin: 695, step: 114.1, matched: 10, score: 1.7 }
        },
        timing: { responseMs: 300, fitMs: 250, totalMs: 561 }
    };
    const mapped = locateBoardEntry.mapLatticeResult(latticeOut, {
        size: { width: 1080, height: 2400 },
        captureMs: 42, totalMs: 620, frameObject: { id: "frame" }
    });
    if (mapped.method !== "lattice") return "method 应为 lattice，实际 " + mapped.method;
    if (mapped.grid !== grid) return "grid 应原样透传";
    if (mapped.columns !== 9 || mapped.rows !== 10) return "columns/rows 应取证据条数";
    if (mapped.earlyAccept !== true || mapped.attempts !== 1) return "应标记 attempts=1/earlyAccept";
    if (!mapped.frameObject || mapped.frameObject.id !== "frame") return "frameObject 应透传";
    return near(mapped.region.x, 82 - 115.9 * 0.5, 1, "region.x") &&
        near(mapped.region.y, 695 - 114.1 * 0.5, 1, "region.y") &&
        near(mapped.region.width, 9 * 115.9, 1, "region.width") &&
        near(mapped.region.height, 10 * 114.1, 1, "region.height") &&
        near(mapped.timing.locateMs, 561, 1, "locateMs") &&
        eq(mapped.lattice.calls, 640, "找色次数");
});

check("定位融合：regionFromGrid 与棋盘外沿对齐（±0.5 格）", function () {
    const region = locateBoardEntry.regionFromGrid(
        { originX: 82.4, originY: 695.2, stepX: 115.9, stepY: 114.1 }, 1080, 2400);
    return eq(region.x, Math.round(82.4 - 115.9 * 0.5), "x") &&
        eq(region.y, Math.round(695.2 - 114.1 * 0.5), "y") &&
        eq(region.width, Math.round(9 * 115.9), "width") &&
        eq(region.height, Math.round(10 * 114.1), "height");
});

// ── YOLO 主识别（多帧投票，2026-09-24）──────────────────────────────
// 投票是纯逻辑：给 mock 的 yolo 源按脚本喂帧，看服务层怎么合。真机上的 TflRunner
// 归真机冒烟管（tools/self-check.js 不碰设备）。
const boardServiceModule = require("../src/services/board-service.js");
const yoloRecognizer = require("../src/vision/yolo-recognizer.js");

// 开局 FEN（row0=屏幕顶行）→ 90 格；造帧用。
function initChars() {
    return yoloRecognizer.create({ baseDir: ".", log: function () { } })
        .fenToChars("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR");
}

/** 服务层 mock：脚本化「每帧读什么」。capture 返回的帧只当作句柄用。 */
function yoloService(frames, options) {
    let call = 0;
    return boardServiceModule.createBoardService({
        ports: {
            now: function () { return Date.now(); },
            capture: function () { return { image: { id: call } }; },
            sleep: function () { }
        },
        yolo: {
            ensureServer: function () { return true; },
            read: function () {
                const v = frames[Math.min(call, frames.length - 1)];
                call++;
                return v;
            }
        },
        yoloOptions: Object.assign({ votes: 3, gapMs: 0 }, options || {})
    });
}

function yoloFrame(chars, n) {
    return { ok: true, chars: chars.slice(), n: n == null ? 32 : n, box: {}, geo: {}, ms: 170 };
}

check("YOLO 投票：3 帧一致 → method=yolo、全盘通过、逐格与帧一致", function () {
    const base = initChars();
    const service = yoloService([yoloFrame(base), yoloFrame(base), yoloFrame(base)]);
    const out = service.recognizeYolo();
    if (!out.ok) return "应通过，实际 " + out.reason;
    if (out.method !== "yolo") return "method 应为 yolo，实际 " + out.method;
    if (out.yoloStats.frames !== 3) return "frames 应为 3，实际 " + out.yoloStats.frames;
    if (out.unknownCells !== 0) return "未知格应为 0，实际 " + out.unknownCells;
    for (let c = 0; c < 90; c++) {
        if (out.chars[c] !== base[c]) return "第 " + c + " 格 期望 " + base[c] + "，实际 " + out.chars[c];
    }
    return true;
});

check("YOLO 投票：逐格 3 帧各不同 → 该格记 '?'（有票不过阈值）", function () {
    const a = initChars(), b = initChars(), c = initChars();
    a[7 * 9 + 1] = "C"; b[7 * 9 + 1] = "N"; c[7 * 9 + 1] = "P";
    const out = yoloService([yoloFrame(a), yoloFrame(b), yoloFrame(c)]).recognizeYolo();
    if (!out.ok) return "应通过（未知格在闸门内），实际 " + out.reason;
    if (out.chars[7 * 9 + 1] !== "?") return "该格应为 '?'，实际 " + out.chars[7 * 9 + 1];
    if (out.yoloStats.unknownByVote !== 1) return "unknownByVote 应为 1，实际 " + out.yoloStats.unknownByVote;
    return eq(out.unknownCells, 1, "未知格");
});

check("YOLO 投票：单帧误报一枚棋、另两帧读空 → 判空（空也计票）", function () {
    const a = initChars(), b = initChars(), c = initChars();
    a[4 * 9 + 4] = "p";   // 中宫本来空：只第一帧看到一枚黑卒
    const out = yoloService([yoloFrame(a), yoloFrame(b), yoloFrame(c)]).recognizeYolo();
    if (!out.ok) return "应通过，实际 " + out.reason;
    if (out.chars[4 * 9 + 4] !== null && out.chars[4 * 9 + 4] !== undefined) {
        return "该格应为空，实际 " + out.chars[4 * 9 + 4];
    }
    return eq(out.unknownCells, 0, "未知格");
});

check("YOLO 投票：低于 minPieces 的帧被丢弃，frames 反映有效帧数", function () {
    const base = initChars();
    const service = yoloService([
        yoloFrame(base, 32),
        { ok: true, chars: base.slice(), n: 2, box: {}, geo: {}, ms: 1 },
        { ok: true, chars: base.slice(), n: 0, box: {}, geo: {}, ms: 1 }
    ]);
    const out = service.recognizeYolo();
    if (!out.ok) return "应通过，实际 " + out.reason;
    return eq(out.yoloStats.frames, 1, "有效帧数");
});

check("YOLO 投票：全部帧读不出 → ok:false（不往驱动里塞废帧）", function () {
    const service = yoloService([
        { ok: false, reason: "没有棋盘" },
        { ok: false, reason: "没有棋盘" },
        { ok: false, reason: "没有棋盘" }
    ]);
    const out = service.recognizeYolo();
    if (out.ok !== false) return "应失败";
    return true;
});

check("YOLO 投票：同占位身份保持（参照盘说话，held 计数上升）", function () {
    const base = initChars();
    const a = initChars(), b = initChars(), c = initChars();
    // 7 行 1 列本来是红炮 C：三帧读出 C/N/P 各一 → 投票为 '?'，但占位仍是非空 → 保持参照盘身份
    a[7 * 9 + 1] = "C"; b[7 * 9 + 1] = "N"; c[7 * 9 + 1] = "P";
    const service = yoloService([yoloFrame(a), yoloFrame(b), yoloFrame(c)]);
    const out = service.recognizeYolo({ referenceBoard: base });
    if (!out.ok) return "应通过，实际 " + out.reason;
    if (out.chars[7 * 9 + 1] !== "C") return "身份应保持为 C，实际 " + out.chars[7 * 9 + 1];
    if (out.yoloStats.held < 1) return "held 应 ≥1，实际 " + out.yoloStats.held;
    return eq(out.unknownCells, 0, "未知格");
});

check("YOLO 模块：fenToChars 行序（row0=屏幕顶行）与索引映射", function () {
    const chars = initChars();
    if (chars[0] !== "r") return "row0col0 应为黑车 r，实际 " + chars[0];
    if (chars[9 * 1 + 0] !== null) return "row1col0 应为空";
    if (chars[81] !== "R") return "row9col0 应为红车 R，实际 " + chars[81];
    return eq(chars[89], "R", "row9col8");
});

// ---------------------------------------------------------------- 收尾

const summary = "SELF_CHECK " + (failures.length === 0 ? "OK" : "FAILED") +
    " passed=" + passed + " failed=" + failures.length;
console.log(summary);
failures.forEach(function (item, index) {
    console.log("  " + (index + 1) + ") " + item);
});

module.exports = { passed: passed, failures: failures, summary: summary };
