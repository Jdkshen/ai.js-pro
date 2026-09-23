// @engine quickjs
/**
 * 驱动回放（续）：**失败路径** —— 尝试预算超限 → 暂停并作废基准 → 恢复后按观测重建（账本转 partial）。
 *
 * 脚本：点击"永远成功"但盘面永远不动（= 游戏拒绝/识别冻结）⇒ 同一手试到预算上限：
 *   · 驱动应暂停（budgetPauses ≥ 1）且**作废可靠基准**（tracker 位置为空）；
 *   · 作废不伪改账本段（此前没有已验证着法，历史仍标 complete、ply=0）；
 *   · 恢复（resume）后按观测重建基准 ⇒ 账本降级 partial（这一段不再声称完整）、事件不追溯。
 * 钥匙：这是 A3 验收里「失败不推进版本」的驱动级证据。
 */
"use strict";

module.exports = {
    name: "驱动回放：预算超限→暂停清基准→恢复重建（账本转 partial，失败不推进）",
    tags: ["driver", "replay", "recovery"],
    run: function (t) {
        const fen = t.load("src/chess/fen.js");
        const legal = t.load("src/chess/legal-moves.js");
        const geometry = t.load("src/vision/geometry.js");
        const ledgerModule = t.load("src/chess/move-ledger.js");
        const raw = t.load("src/automation/auto-driver.js");
        const driverModule = Object.assign({}, raw, {
            createDriver: function (options) {
                const opts = Object.assign({}, options || {});
                opts.config = Object.assign({ computeMode: "inline" }, opts.config || {});
                return raw.createDriver(opts);
            }
        });

        const startBoard = fen.parseFen(fen.START_FEN).board;
        const our1 = legal.allLegalMoves(startBoard, "w")[0];
        const afterOwn1 = legal.applyMove(startBoard, our1).board;
        const notes = [];
        let boardNow = startBoard;
        const grid = geometry.createGrid({
            originX: 96, originY: 782, stepX: 136, stepY: 135,
            orientation: geometry.ORIENTATION_RED_BOTTOM, version: 7,
            quality: { sampleCount: 20, inlierCount: 20, maxResidual: 0.05, meanResidual: 0.02 }
        });

        const driver = driverModule.createDriver({
            allowClick: true,
            log: function (text) { notes.push(String(text)); },
            sleep: function () { },
            recognize: function () {
                // 盘面默认永远不动（= 游戏拒绝）；需要时就换 boardNow
                return {
                    ok: true, chars: boardNow.slice(), unknownCells: 0, conflicts: 0,
                    layout: null, centers: null, grid: grid
                };
            },
            locate: function () { return { ok: true, grid: grid }; },
            analyze: function () {
                return { ok: true, iccs: our1.iccs, move: our1, score: 0, depth: 1, ms: 1 };
            },
            tap: function () { return { ok: true, method: "replay-tap" }; },
            screen: function () { return { width: 1080, height: 2400 }; },
            nowMono: function () { return Date.now(); },
            config: { movetimeMs: 20, observeFrames: 1 }
        });

        driver.start();
        for (let i = 0; i < 120; i++) {
            driver.tick();
            if (driver.machine.state() === "PAUSED") {
                break;
            }
        }
        t.ok(driver.stats().budgetPauses >= 1,
            "同一手试到上限应暂停（budgetPauses≥1；实际 " + driver.stats().budgetPauses +
            "，状态 " + driver.machine.state() + "）");
        t.equal(driver.machine.state(), "PAUSED", "暂停状态应为 PAUSED");
        t.equal(driver.tracker().position, null, "暂停时应作废可靠基准（tracker 位置为空）");
        const paused = driver.tracker();
        t.equal(paused.historyStatus, "complete", "作废不伪改账本段（还没重建：历史仍为 complete）");
        t.equal(paused.ledger.ply, 0, "失败不应把没验证的东西写进账本（ply=0）");

        // 恢复：画面换成「另一张干净但非开局的盘面」⇒ 按观测重建 ⇒ 账本降级 partial
        boardNow = afterOwn1;
        driver.resume();
        let rebuilt = false;
        for (let i = 0; i < 40 && !rebuilt; i++) {
            driver.tick();
            rebuilt = driver.tracker().historyStatus === "partial";
        }
        t.ok(rebuilt, "恢复后应按观测重建基准（非开局 ⇒ 账本转 partial）");
        const snap = driver.tracker();
        t.ok(snap.position && ledgerModule.cellsKey(snap.position.board) === ledgerModule.cellsKey(afterOwn1),
            "重建后的基准应等于这一帧的观测（cellsKey 比较）");
        t.equal(snap.ledger.ply, 0, "重建后账本从新段起算（先前没有已验证着法）");
        const stats = driver.stats();
        t.equal(stats.trackerMismatch, 0, "影子与可靠基准不应失步");
        t.equal(stats.trackerErrors, 0, "账本不应抛异常");
        if (t.failures.length) {
            t.log("NOTES|" + notes.slice(-40).join(" ｜ "));
        }
    }
};
