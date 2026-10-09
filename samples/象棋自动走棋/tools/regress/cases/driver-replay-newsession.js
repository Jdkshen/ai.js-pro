// @engine quickjs
/**
 * 驱动回放（续）：**换局** —— newSession 清账后，重开一段从标准开局重新记账。
 *
 * 脚本：正常走完我方一手（点击后帧切到落子后，确认 CONFIRMED）⇒ newSession() ⇒
 *   · 账本作废（位置为空、历史 unknown、positionCommand 为 null）；
 *   · 随后帧回到标准开局 ⇒ 重新开段（complete、ply=0、positionCommand = position fen 开局）。
 * 钥匙：A3 验收里「换局能正确追加/清账」的驱动级证据 + 账本绝不跨局带历史。
 */
"use strict";

module.exports = {
    name: "驱动回放：换局（newSession）清账；重开从标准开局起新段（不跨局带历史）",
    tags: ["driver", "replay", "session"],
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
        const grid = geometry.createGrid({
            originX: 96, originY: 782, stepX: 136, stepY: 135,
            orientation: geometry.ORIENTATION_RED_BOTTOM, version: 7,
            quality: { sampleCount: 20, inlierCount: 20, maxResidual: 0.05, meanResidual: 0.02 }
        });

        const stage = { clicked: false };
        const driver = driverModule.createDriver({
            allowClick: true,
            log: function (text) { notes.push(String(text)); },
            sleep: function () { },
            recognize: function () {
                return {
                    ok: true, chars: (stage.clicked ? afterOwn1 : startBoard).slice(),
                    unknownCells: 0, conflicts: 0, layout: null, centers: null, grid: grid
                };
            },
            locate: function () { return { ok: true, grid: grid }; },
            analyze: function () {
                return { ok: true, iccs: our1.iccs, move: our1, score: 0, depth: 1, ms: 1 };
            },
            tap: function (x, y, tapOptions) {
                if (tapOptions && tapOptions.kind === "to") {
                    stage.clicked = true;   // 落点 tap 后：画面切到「我方落子之后」
                }
                return { ok: true, method: "replay-tap" };
            },
            screen: function () { return { width: 1080, height: 2400 }; },
            nowMono: function () { return Date.now(); },
            config: { movetimeMs: 20, observeFrames: 1 }
        });

        driver.start();
        let confirmed = false;
        for (let i = 0; i < 40 && !confirmed; i++) {
            driver.tick();
            const reliable = driver.lastReliable();
            // ⚠️ 必须 cellsKey 比较：a0a1 前后两盘 join("") 相同（本用例连断言都踩过这个坑）
            confirmed = !!reliable && ledgerModule.cellsKey(reliable.board) === ledgerModule.cellsKey(afterOwn1);
        }
        t.ok(confirmed, "应先正常确认我方一手（阶段 clicked=" + stage.clicked + "，状态 " + driver.machine.state() + "）");
        const before = driver.tracker();
        t.equal(before.ledger.ply, 1, "换局前账本应有 1 手");
        t.ok(String(before.positionCommand).indexOf(" moves " + our1.iccs) > 0, "换局前 positionCommand 应带着法");

        // 换局：会话 +1，基准/账本/几何全部作废
        driver.newSession();
        const after = driver.tracker();
        t.equal(after.position, null, "换局后位置应清空");
        t.equal(after.historyStatus, "unknown", "换局后账本应作废（unknown）");
        t.equal(after.positionCommand, null, "换局后不得输出任何历史命令");

        // 重开：帧回到标准开局 ⇒ 重新开段
        stage.clicked = false;
        let restarted = false;
        for (let i = 0; i < 20 && !restarted; i++) {
            driver.tick();
            restarted = driver.tracker().historyStatus === "complete" && driver.tracker().ledger.ply === 0;
        }
        t.ok(restarted, "重开后应从标准开局起新段（complete、ply=0）");
        const fresh = driver.tracker();
        t.equal(fresh.positionCommand, "position fen " + fen.START_FEN, "新段命令 = 标准开局、且不带任何旧着法");
        t.equal(driver.stats().trackerMismatch, 0, "全程不应失步");
        t.equal(driver.stats().trackerErrors, 0, "账本不应抛异常");
        if (t.failures.length) {
            t.log("NOTES|" + notes.slice(-40).join(" ｜ "));
        }
    }
};
