// @engine quickjs
/**
 * 点击链路验证（真机，**会真的点棋盘**）：定位 → 预检局面 == 标准开局 → 用 click-executor
 * 点一步「炮二平五」→ 再识别一次看落子确认怎么说。
 *
 * 为什么单独一个探针：自动模式要求「零未知格」才出手，而当前模型在腾讯天天象棋皮肤上恒定有
 * 6 个未知格 → ③ 模式会正确地停住，点击链路就没法被走到。这个探针跳过那条闸门，
 * 只验证「格点 → 屏幕点 → 按压 → 游戏真的动了」这一段。
 *
 * 安全前提：局面必须与标准开局一致（未知格跳过比较），否则**不点**。
 */
const adapter = require("../src/runtime/aijs-adapter.js");
const locateBoard = require("./locate-board.js");
const recognizeBoard = require("./recognize-board.js");
const executorModule = require("../src/automation/click-executor.js");
const ticketModule = require("../src/automation/move-ticket.js");
const verifier = require("../src/automation/move-verifier.js");
const fenModule = require("../src/chess/fen.js");
const legal = require("../src/chess/legal-moves.js");
const geometry = require("../src/vision/geometry.js");

/** 炮二平五（标准开局里红方的一个合法着法） */
const MOVE = { fromCol: 1, fromRow: 7, toCol: 4, toRow: 7 };
const MOVE_ICCS = "b2e2";

function describeClicks(outcome) {
    const parts = [];
    for (let i = 0; i < outcome.clicks.length; i++) {
        const item = outcome.clicks[i];
        parts.push(item.kind + "(" + item.x + "," + item.y + ")=" + (item.ok ? "ok" : ("fail:" + item.error)) +
            "/" + item.method + " " + item.elapsedMs + "ms");
    }
    return parts.join(" | ");
}

/**
 * 只比「站位」不比颜色：未知格跳过、大小写忽略。
 * 为什么要忽略颜色：腾讯天天象棋皮肤上后排角上的黑车被判成了红车（阵营采样受棋盘装饰干扰），
 * 但**站位**是对的 —— 安全阀要卡的是「这还是不是一个没动过的开局」，不是「颜色判得准不准」。
 */
function samePlacement(expected, observed) {
    if (!expected || !observed || observed.length !== expected.length) {
        return false;
    }
    for (let i = 0; i < expected.length; i++) {
        const got = observed[i];
        if (got === "?" || got === undefined || got === null) {
            continue;
        }
        const want = expected[i];
        const a = got ? String(got).toLowerCase() : "";
        const b = want ? String(want).toLowerCase() : "";
        if (a !== b) {
            return false;
        }
    }
    return true;
}

if (!adapter.requestCapture("portrait")) {
    console.log("CLICK_PROBE 0 capture-denied");
} else {
    console.log("CLICK_PROBE 1 a11y=" + (auto.service ? "ready" : "null") +
        " tap=" + (adapter.capabilities().tap ? "有" : "无") +
        " pkg=" + currentPackage());

    const located = locateBoard.locateBoardOnScreen({});
    if (!located.ok) {
        console.log("CLICK_PROBE 2 locate-fail " + located.reason);
    } else {
        const grid = located.grid;
        console.log("CLICK_PROBE 2 grid=(" + Math.round(grid.originX) + "," + Math.round(grid.originY) +
            ") step=(" + Math.round(grid.stepX) + "," + Math.round(grid.stepY) + ") v" + grid.version);

        const seen = recognizeBoard.recognizeScreen({ grid: grid, orientation: "RED_BOTTOM" });
        const startBoard = fenModule.parseFen(fenModule.START_FEN).board;
        const observed = seen && seen.ok ? seen.chars : null;
        const looksLikeOpening = !!observed && legal.boardMatches(startBoard, observed);
        const samePlace = !!observed && samePlacement(startBoard, observed);
        console.log("CLICK_PROBE 3 识别 ok=" + (seen && seen.ok) + " 未知格=" +
            (seen && seen.ok ? seen.unknownCells : "-") + " 布局=" + (seen && seen.ok ? seen.layout : seen.reason) +
            "｜与开局完全一致=" + looksLikeOpening + "｜站位与开局一致（忽略颜色/未知格）=" + samePlace);

        if (!samePlace) {
            console.log("CLICK_PROBE 4 局面不是标准开局 → 不点击（安全阀）");
        } else {
            const ticket = ticketModule.create({
                sessionId: 1,
                basePositionVersion: 1,
                baseFen: fenModule.START_FEN,
                baseSide: "w",
                modelVersion: "probe",
                geometryVersion: grid.version,
                moveICCS: MOVE_ICCS,
                move: MOVE,
                expectedAfterFen: fenModule.buildFen(legal.applyMove(startBoard, MOVE).board, "b"),
                source: "click-probe"
            });
            const executor = executorModule.createExecutor({
                tap: adapter.createTap({ pressMs: 60 }).tap,
                nowMono: adapter.nowMono,
                waitMs: adapter.waitMs
            });
            const outcome = executor.execute(ticket, { grid: grid, screen: adapter.screenSize() });
            console.log("CLICK_PROBE 4 执行：" + outcome.status + " 点击 " + outcome.clickCount +
                " 次｜" + describeClicks(outcome) + "｜" + outcome.reason);

            // 等游戏把动画走完，再看一次局面 + 让 verifier 判断
            sleep(1200);
            const after = recognizeBoard.recognizeScreen({ grid: grid, orientation: "RED_BOTTOM" });
            if (after && after.ok) {
                const verdict = verifier.verify({
                    ticket: ticket,
                    observed: after.chars,
                    unknownCells: after.unknownCells
                });
                console.log("CLICK_PROBE 5 落子确认：" + verdict.result + "｜" + verdict.reason);
                console.log("CLICK_PROBE 5 局面=" + after.layout + "（未知格 " + after.unknownCells + "）");
            } else {
                console.log("CLICK_PROBE 5 复核识别失败：" + (after ? after.reason : "无结果"));
            }
        }
    }
}
