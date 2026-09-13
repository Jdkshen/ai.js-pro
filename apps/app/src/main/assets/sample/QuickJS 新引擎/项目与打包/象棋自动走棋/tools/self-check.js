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
        model: { modelPath: "C:\\x\\assets\\models\\chessai_160.onnx", inputSize: 160 },
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
    if (text.indexOf("chessai_160.onnx") < 0) {
        return "没有报出模型文件名：" + text.split("\n").slice(0, 3).join(" | ");
    }
    return true;
});

// ---------------------------------------------------------------- 算棋（本地引擎）

const evalModule = require("../src/engine/eval.js");
const searchModule = require("../src/engine/search.js");
const engineModule = require("../src/engine/engine.js");
const uciModule = require("../src/engine/uci.js");
const localEngine = require("../src/engine/local-engine.js");

// 一步杀：黑将 (4,0) 无子；红兵 (4,2) 保住 (4,1)，红兵 (3,1)/(5,1) 封住 (3,0)/(5,0)，红车 (0,1) 一击
const MATE_IN_1 = "4k4/R2P1P3/4P4/9/9/9/9/9/9/4K4";
// 白吃一个车：红车 (0,4) 沿第 4 行吃无保护的黑车 (4,4)
const WIN_ROOK = "3k5/9/9/9/R3r4/9/9/9/9/4K4";

check("算棋·评估：开局红黑对称", function () {
    const value = evalModule.evaluate(fen.parseFen(fen.START_FEN).board);
    return Math.abs(value) < 40 || ("开局分数偏离 0 太多：" + value);
});

check("算棋·评估：红多一个车至少领先 800", function () {
    const value = evalModule.evaluate(fen.parseFen("3k5/9/9/9/9/9/9/9/9/3RK4").board);
    return value > 800 || ("分数 " + value);
});

check("算棋·搜索：一步杀能被找到", function () {
    const result = searchModule.search(fen.parseFen(MATE_IN_1).board, "w",
        { movetime: 1500, depth: 3 });
    if (!result.ok) {
        return "搜索失败：" + result.reason;
    }
    return result.score > searchModule.MATE - 200 ||
        ("没看到杀棋：score=" + result.score + " best=" + result.iccs + " depth=" + result.depth);
});

check("算棋·搜索：白吃一个车能被找到", function () {
    const result = searchModule.search(fen.parseFen(WIN_ROOK).board, "w",
        { movetime: 1500, depth: 3 });
    if (!result.ok) {
        return "搜索失败：" + result.reason;
    }
    return result.captured === "r" || ("没吃车：走 " + result.iccs + "，score=" + result.score);
});

check("算棋·搜索：无着可走（将死/困毙）要明确报出来", function () {
    const result = searchModule.search(fen.parseFen(MATE_IN_1).board, "b", { movetime: 300 });
    if (result.ok) {
        return "黑方无着可走却给出 " + result.iccs;
    }
    return result.reason && result.reason.length > 0 || "没给原因";
});

check("算棋·搜索：给出的着法经棋规复核仍合法", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const result = searchModule.search(board, "w", { movetime: 1200, depth: 3 });
    if (!result.ok) {
        return "搜索失败：" + result.reason;
    }
    const verify = legal.isLegalMove(board, result.move.fromCol, result.move.fromRow,
        result.move.toCol, result.move.toRow, "w");
    return verify.legal || ("不合法：" + result.iccs + "（" + verify.reason + "）");
});

check("算棋·门面：有未知格就拒绝算棋（不猜）", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    board[40] = "?";
    const result = engineModule.analyze({ board: board, side: "w" }, { movetime: 200 });
    if (result.ok) {
        return "有未知格却算出了 " + result.iccs;
    }
    return result.reason.indexOf("未知格") >= 0 || ("原因没提未知格：" + result.reason);
});

check("算棋·门面：将/帅数量不对要拦下", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    board[85] = null; // 85 = col4,row9 正是红帅那一格（81 是车，别写错）
    const result = engineModule.analyze({ board: board, side: "w" }, { movetime: 200 });
    if (result.ok) {
        return "帅数量不对却算出了 " + result.iccs;
    }
    return result.reason.indexOf("帅") >= 0 || ("原因不对：" + result.reason);
});

check("算棋·UCI：着法记法与 rules.moveToIccs 一致", function () {
    const board = fen.parseFen(fen.START_FEN).board;
    const moves = searchModule.generateMoves(board, true, false);
    for (let i = 0; i < moves.length; i++) {
        if (uciModule.moveToUci(moves[i]) !== rules.moveToIccs(moves[i])) {
            return "不一致：" + uciModule.moveToUci(moves[i]) + " vs " + rules.moveToIccs(moves[i]);
        }
    }
    return moves.length > 0 || "开局没有着法？";
});

check("算棋·UCI：本地引擎能应答 uci/isready/position/go", function () {
    const instance = localEngine.create();
    const uciLines = instance.send("uci");
    if (!uciLines.some(function (line) { return line === "uciok"; })) {
        return "没有 uciok：" + JSON.stringify(uciLines);
    }
    if (instance.send("isready")[0] !== "readyok") {
        return "isready 没回 readyok";
    }
    if (instance.send("position startpos moves b2e2 b7e7").length !== 0) {
        return "position 不该有输出";
    }
    const parsed = fen.parseFen(instance.currentFen());
    if (!parsed.ok || parsed.sideToMove !== "w") {
        return "走完两子后 FEN/轮次不对：" + instance.currentFen();
    }
    const lines = instance.send("go depth 2");
    const best = lines[lines.length - 1];
    return /^bestmove [a-i][0-9][a-i][0-9]$/.test(best) || ("bestmove 行不对：" + best);
});

check("算棋·集成：自我对弈 6 步，每步都用棋规复核", function () {
    let board = fen.parseFen(fen.START_FEN).board;
    let side = "w";
    for (let ply = 0; ply < 6; ply++) {
        const result = engineModule.analyze({ board: board, side: side },
            { movetime: 400, depth: 2 });
        if (!result.ok) {
            return "第 " + (ply + 1) + " 步算不出来：" + result.reason;
        }
        const verify = legal.isLegalMove(board, result.move.fromCol, result.move.fromRow,
            result.move.toCol, result.move.toRow, side);
        if (!verify.legal) {
            return "第 " + (ply + 1) + " 步不合法：" + result.iccs + "（" + verify.reason + "）";
        }
        board = legal.applyMove(board, result.move).board;
        side = side === "w" ? "b" : "w";
    }
    return true;
});

check("算棋·门面：结果文本不出现 undefined", function () {
    const result = engineModule.analyze(fen.START_FEN, { movetime: 400, depth: 2 });
    const text = engineModule.describe(result);
    return text.indexOf("undefined") < 0 || ("描述里有 undefined：" + text);
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
                conflicts: ext.conflicts || 0
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

    /** 把状态机吐出的 EXECUTE_TICKET 真的走一遍点击（真机上是 main.js 的驱动在做）。 */
    function executeCurrentTicket() {
        const ticket = machine.currentTicket();
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

check("P4·确认：出现一个合法对方应手 → OPPONENT_REPLY（A03）", function () {
    const verdict = verifier.verify({ ticket: sampleTicket(), observed: AFTER_REPLY_BOARD.slice() });
    if (verdict.result !== "OPPONENT_REPLY") {
        return verdict.result + "：" + verdict.reason;
    }
    return verdict.plies.length === 2 ? true : ("应手确认应该记两步，实际 " + verdict.plies.length + " 步");
});

check("P4·确认：有未知格是「看不清」，绝不能当成没落子", function () {
    const observed = AFTER_BOARD.slice();
    observed[0] = "?";
    observed[1] = "?";
    const verdict = verifier.verify({ ticket: sampleTicket(), observed: observed, unknownCells: 2 });
    return verdict.result === "UNRELIABLE" ? true : (verdict.result + "：" + verdict.reason);
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
    if (h.machine.state() !== "RECOVERING") {
        return "确认后应进入恢复，实际 " + h.machine.state();
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
    const pre = h.dispatch(h.snapshotEvent(START_BOARD, "w"));
    if (h.effectsOf("EXECUTE_TICKET") !== 0) {
        return "只识别模式下仍然产生了点击动作";
    }
    if (h.clicked.length !== 0 || h.disabledTap.calls() !== 0) {
        return "只识别模式下的点击调用数不为 0：" + h.clicked.length + "/" + h.disabledTap.calls();
    }
    return pre.reason.indexOf("禁止点击") >= 0 ? true : ("拒绝原因没说清：" + pre.reason);
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

// ---------------------------------------------------------------- 收尾

const summary = "SELF_CHECK " + (failures.length === 0 ? "OK" : "FAILED") +
    " passed=" + passed + " failed=" + failures.length;
console.log(summary);
failures.forEach(function (item, index) {
    console.log("  " + (index + 1) + ") " + item);
});

module.exports = { passed: passed, failures: failures, summary: summary };
