// @engine quickjs
/**
 * 驱动回放（任务书 §6「录制回放」的脚本化版）——**逐手喂真驱动**，输出逐手轨迹并断言账本。
 *
 * 干什么：把一段「我方 2 手 + 对方 1 应手」的对局按帧脚本回放给真正的 auto-driver：
 *   起点帧 → 出手（分析→票据→两次点击）→ 落子帧 → 对方应手帧（唯一解释，要跨帧佐证）
 *   → 第二手 → 再确认。每一手都从 `driver.tracker()` 取「已确认局面 + positionCommand」，
 *   最后断言：账本逐手追加、零拒收、零失步（影子与可靠基准一致）、零异常。
 *
 * 为什么重要：这是 A1/A2/A3 的**无截屏验收**——真机截屏授权缺失时，仍能证明
 * 「观测 → 已确认局面 → 搜索输入（positionCommand）→ 票据 → 验证结果」这条链完整。
 *
 * 与 self-check 同口径：强制 `computeMode: inline`（设备上有 worker 时场景会挂在 THINKING，
 * node 上又天然没有 worker —— 两边都要确定性地走同步算棋）。
 */
"use strict";

module.exports = {
    name: "驱动回放：脚本对局（我方 2 手 + 对方 1 应手）——账本逐手追加、零拒收零失步",
    tags: ["driver", "replay", "ledger"],
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

        // ---- 回放脚本：一局三个半回合（全部由棋规纯函数推出来，不手编盘面） ----
        const startBoard = fen.parseFen(fen.START_FEN).board;
        const our1 = legal.allLegalMoves(startBoard, "w")[0];
        const afterOwn1 = legal.applyMove(startBoard, our1).board;
        const reply1 = legal.allLegalMoves(afterOwn1, "b")[0];
        const afterReply1 = legal.applyMove(afterOwn1, reply1).board;
        const our2 = legal.allLegalMoves(afterReply1, "w")[0];
        const afterOwn2 = legal.applyMove(afterReply1, our2).board;

        const grid = geometry.createGrid({
            originX: 96, originY: 782, stepX: 136, stepY: 135,
            orientation: geometry.ORIENTATION_RED_BOTTOM, version: 7,
            quality: { sampleCount: 20, inlierCount: 20, maxResidual: 0.05, meanResidual: 0.02 }
        });

        const stage = { name: "pre" };
        const notes = [];
        let planIndex = 0;
        const planned = [our1, our2];
        const frameBoard = function () {
            if (stage.name === "pre") { return startBoard; }
            if (stage.name === "own1") { return afterOwn1; }
            if (stage.name === "reply1") { return afterReply1; }
            return afterOwn2;
        };

        const driver = driverModule.createDriver({
            allowClick: true,
            log: function (text) { notes.push(String(text)); },
            sleep: function () { },
            recognize: function () {
                return {
                    ok: true, chars: frameBoard().slice(), unknownCells: 0, conflicts: 0,
                    layout: null, centers: null, grid: grid
                };
            },
            locate: function () { return { ok: true, grid: grid }; },
            analyze: function () {
                const move = planned[Math.min(planIndex, planned.length - 1)];
                return { ok: true, iccs: move.iccs, move: move, score: 0, depth: 1, ms: 1 };
            },
            tap: function (x, y, tapOptions) {
                // 落点那次 tap 才算真的出手：出手后画面立刻换到「落子之后」的帧
                if (tapOptions && tapOptions.kind === "to") {
                    if (stage.name === "pre") {
                        stage.name = "own1";
                    } else if (stage.name === "reply1") {
                        stage.name = "own2";
                    }
                }
                return { ok: true, method: "replay-tap" };
            },
            screen: function () { return { width: 1080, height: 2400 }; },
            nowMono: function () { return Date.now(); },
            config: { movetimeMs: 20, observeFrames: 1 }
        });

        const match = function (board) {
            const reliable = driver.lastReliable();
            return !!reliable && reliable.board.join("") === board.join("");
        };
        const trace = [];
        let replyLogged = false;
        let done = false;
        driver.start();
        for (let i = 0; i < 80 && !done; i++) {
            driver.tick();
            if (stage.name === "own1" && match(afterOwn1)) {
                stage.name = "reply1";
                planIndex = 1;
                trace.push("我方 " + our1.iccs + " 已确认｜" + driver.tracker().positionCommand);
            } else if (stage.name === "reply1" && match(afterReply1)) {
                if (!replyLogged) {
                    replyLogged = true;
                    trace.push("对方 " + reply1.iccs + " 已采纳｜" + driver.tracker().positionCommand);
                }
            } else if (stage.name === "own2" && match(afterOwn2)) {
                trace.push("我方 " + our2.iccs + " 已确认｜" + driver.tracker().positionCommand);
                done = true;
            }
        }
        for (let i = 0; i < trace.length; i++) {
            t.log(trace[i]);
        }
        if (!done) {
            t.fail("回放没走完（阶段 " + stage.name + "，状态 " + driver.machine.state() +
                "／" + driver.machine.reason() + "）— 末尾日志：" + notes.slice(-4).join(" ｜ "));
            return;
        }

        const snap = driver.tracker();
        t.equal(snap.positionCommand,
            "position fen " + fen.START_FEN + " moves " + our1.iccs + " " + reply1.iccs + " " + our2.iccs,
            "positionCommand 应按验证顺序逐手追加（搜索输入 = 已验证历史）");
        t.equal(snap.ledger.ply, 3, "账本应有 3 手（我方×2 + 对方应手×1）");
        const stats = driver.stats();
        t.equal(stats.trackerRejects, 0, "账本不应拒收（拒收=影子口径与驱动不一致）");
        t.equal(stats.trackerMismatch, 0, "影子与可靠基准不应失步");
        t.equal(stats.trackerErrors, 0, "账本不应抛异常");
    }
};
