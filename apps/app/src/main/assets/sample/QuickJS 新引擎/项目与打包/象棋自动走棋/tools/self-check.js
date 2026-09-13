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

// ---------------------------------------------------------------- 收尾

const summary = "SELF_CHECK " + (failures.length === 0 ? "OK" : "FAILED") +
    " passed=" + passed + " failed=" + failures.length;
console.log(summary);
failures.forEach(function (item, index) {
    console.log("  " + (index + 1) + ") " + item);
});

module.exports = { passed: passed, failures: failures, summary: summary };
