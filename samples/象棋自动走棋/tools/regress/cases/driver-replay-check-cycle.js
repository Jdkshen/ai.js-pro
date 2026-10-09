// @engine quickjs
/** 连续将军回放：已确认棋谱送到 Pikafish，由引擎判断规则责任，驱动不按计划强制禁手。 */
"use strict";

module.exports = {
    name: "驱动回放：我方长将的循环（车来回将军）——完整棋谱传给引擎判罚",
    tags: ["driver", "replay", "loop-ban", "check"],
    run: function (t) {
        const fen = t.load("src/chess/fen.js");
        const legal = t.load("src/chess/legal-moves.js");
        const geometry = t.load("src/vision/geometry.js");
        const raw = t.load("src/automation/auto-driver.js");
        const driverModule = Object.assign({}, raw, {
            createDriver: function (options) {
                const opts = Object.assign({}, options || {});
                opts.config = Object.assign({ computeMode: "inline" }, opts.config || {});
                return raw.createDriver(opts);
            }
        });

        // ---- 局面：我方车在 e0；对方将 d9（车走 d 线就将军 d9，回 e 线就将军 e9） ----
        const s0 = fen.parseFen("3k5/9/9/9/9/9/9/9/9/4RK3 w - - 0 1").board;
        const step = function (board, iccs) {
            const applied = legal.applyMove(board, { iccs: iccs });
            return (applied && applied.board) ? applied.board : null;
        };
        const s1 = step(s0, "e0d0");   // 我方将军（d 线顶到 d9）
        const s2 = step(s1, "d9e9");   // 对方将躲到 e9
        const s3 = step(s2, "d0e0");   // 我方回 e 线继续将军
        const s4 = step(s3, "e9d9");   // 对方将躲回 d9（= 回到起点）

        const key = function (board) {
            let out = "";
            for (let i = 0; i < board.length; i++) {
                out += (board[i] == null ? "." : String(board[i]));
            }
            return out;
        };

        t.ok(!!(s1 && s2 && s3 && s4), "四步（e0d0/d9e9/d0e0/e9d9）都应当能应用（盘面或着法搭错了）");
        if (!(s1 && s2 && s3 && s4)) {
            return;
        }
        t.equal(key(s4), key(s0), "四步之后应当回到原局面（这就是循环）");
        t.ok(legal.isLegalMove(s0, 4, 9, 3, 9, "w").legal, "车 e0d0 应当合法");
        t.ok(legal.isLegalMove(s1, 3, 0, 4, 0, "b").legal, "将 d9e9 应当合法（躲将）");
        t.ok(legal.isLegalMove(s2, 3, 9, 4, 9, "w").legal, "车 d0e0 应当合法");
        t.ok(legal.isLegalMove(s3, 4, 0, 3, 0, "b").legal, "将 e9d9 应当合法（躲将）");

        const frames = [s0, s1, s2, s3];
        const stage = { idx: 0 };
        const state = function () { return frames[stage.idx % 4]; };

        const grid = geometry.createGrid({
            originX: 96, originY: 782, stepX: 136, stepY: 135,
            orientation: geometry.ORIENTATION_RED_BOTTOM, version: 11,
            quality: { sampleCount: 20, inlierCount: 20, maxResidual: 0.05, meanResidual: 0.02 }
        });

        const notes = [];
        const analyzeCalls = [];
        const ourMoveFor = {};
        ourMoveFor[key(s0)] = "e0d0";
        ourMoveFor[key(s2)] = "d0e0";

        const driver = driverModule.createDriver({
            allowClick: true,
            mySide: "w",
            log: function (text) { notes.push(String(text)); },
            sleep: function () { },
            recognize: function () {
                return {
                    ok: true, chars: state().slice(), unknownCells: 0, conflicts: 0,
                    layout: null, centers: null, grid: grid
                };
            },
            locate: function () { return { ok: true, grid: grid }; },
            analyze: function (position, options) {
                const board = position && position.board ? position.board : null;
                const iccs = board ? ourMoveFor[key(board)] : null;
                analyzeCalls.push({
                    iccs: iccs,
                    options: options,
                    board: board ? board.slice() : null
                });
                let move = null;
                if (iccs) {
                    const list = legal.allLegalMoves(board, position.side);
                    for (let i = 0; i < list.length; i++) {
                        if (list[i].iccs === iccs) {
                            move = list[i];
                            break;
                        }
                    }
                }
                return {
                    ok: !!move, iccs: iccs, move: move, score: 0, depth: 1, ms: 1,
                    reason: move ? "" : "场景只覆盖 e0d0 / d0e0 两个局面"
                };
            },
            tap: function (x, y, tapOptions) {
                if (tapOptions && tapOptions.kind === "to" && stage.idx % 2 === 0) {
                    stage.idx += 1;
                }
                return { ok: true, method: "replay-tap" };
            },
            screen: function () { return { width: 1080, height: 2400 }; },
            nowMono: function () { return Date.now(); },
            config: { movetimeMs: 20, observeFrames: 1 }
        });

        driver.start();
        driver.tick();
        driver.tick();
        const declared = driver.assertTurn();
        t.ok(declared && declared.ok, "声明轮到我方应当成功（" + (declared && declared.reason) + "）");

        const targetOwnMoves = 5;
        let confirmedOwn = 0;
        let guard = 0;
        while (confirmedOwn < targetOwnMoves && guard < 800) {
            guard += 1;
            driver.tick();
            const reliable = driver.lastReliable();
            if (!reliable) {
                continue;
            }
            if (stage.idx % 2 === 1 && key(reliable.board) === key(state())) {
                confirmedOwn += 1;
                t.log("我方第 " + confirmedOwn + " 手已确认（" +
                    ourMoveFor[key(frames[(stage.idx - 1) % 4])] + "）");
                if (confirmedOwn < targetOwnMoves) {
                    stage.idx += 1;
                }
            }
        }
        t.equal(confirmedOwn, targetOwnMoves, "五次将军都应当确认成功");

        // ---- 核心断言：长将的循环**照旧要禁**（与安静往返那条用例相反） ----
        let firstBanned = null;
        let banCalls = 0;
        for (let i = 0; i < analyzeCalls.length; i++) {
            const list = analyzeCalls[i].options && analyzeCalls[i].options.excludeIccs;
            if (list && list.length) {
                banCalls += 1;
                if (!firstBanned) {
                    firstBanned = list.join("/");
                }
            }
        }
        t.equal(banCalls, 0, "不能根据计划重复覆盖引擎的规则判断");
        const command = analyzeCalls[analyzeCalls.length-1].options.positionCommand;
        t.ok(command && command.indexOf("e0d0 d9e9 d0e0 e9d9 e0d0 d9e9 d0e0 e9d9") >= 0,
            "完整连续将军循环必须进入引擎命令：" + command);
        if (firstBanned) {
            t.log("（引擎收到的排除表：" + firstBanned + "）");
        }
        const stats = driver.stats();
        for (let i = 0; i < notes.length && i < 120; i++) {
            if (notes[i].indexOf("循环") >= 0) {
                t.log("[note] " + notes[i]);
            }
        }
        t.equal(stats.loopBanHits, 0, "由引擎规则判断，不注入启发式禁手");
        t.equal(stats.loopBanSkippedQuiet, 0, "这里没有安静往返：不该走「安静着法放行」这条路");
        t.equal(stats.loopBanSkippedCheck, 0, "这里我方从未被将军：不该走「被将军放行」这条路");
        t.equal(stats.trackerRejects, 0, "账本不应拒收");
        t.equal(stats.trackerMismatch, 0, "影子与可靠基准不应失步");
        t.equal(stats.trackerErrors, 0, "账本不应抛异常");
    }
};
