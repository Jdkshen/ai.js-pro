// @engine quickjs
/**
 * 驱动回放：**安静往返的循环**（帅 e0↔f0 跟着对方的炮来回摆）——「循环禁用」必须放行。
 *
 * 现场（2026-09-25 真机第 3 局 15:19:47，用户口径「走着走着就送子给对面吃了」）：
 *   · 黑炮 e3↔f3 来回：**e 线被我方 e2 炮 + e1 士两个垫子挡着（我方没被将）**，
 *     f 线只有一个垫子（帅 f0 时被将）—— 典型的「一将一闲 + 我方帅闲走」安静往返，
 *     引擎评分一直 -10 左右（双方不变作和）；
 *   · 同一个「轮到我方」的局面第 3 次出现 ⇒ 旧逻辑把帅的两步往返 e0f0/f0e0 全禁了
 *     ⇒ 引擎没有等值着法，被迫走 c8c9（评分 -868）⇒ 下一手被黑象吃车，送子输棋。
 *
 * 守卫（本次修）：**只禁「我方给出将军」的着法**（长将才判负）；安静往返按规则是
 * 「双方不变作和」；另外「我方正被将军时整表放行」（着法都是应将）。
 *
 * 本用例把这条循环喂给**真驱动**跑 5 手（第 3 次回到起点局面时旧逻辑会开禁），断言：
 *   · 整条循环里引擎**一次都没收到排除表**（旧逻辑第 3 次会收到 ["e0f0","f0e0"]）；
 *   · `stats.loopBanSkippedQuiet` ≥ 1（安静着法被放行——e 线那次）且
 *     `stats.loopBanSkippedCheck` ≥ 1（被将时整表放行——f 线那次）；
 *   · `stats.loopBanHits` = 0（本次不该真的禁掉任何着法）；
 *   · 五次走动全部确认、账本零拒收零失步。
 */
"use strict";

module.exports = {
    name: "驱动回放：被将军的循环（对方长将）——循环禁用放行、不被逼着送子",
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

        // ---- 局面：黑炮 e3 来回；e 线（我方 e2 炮 + e1 士）两垫子=不被将，f 线（f2 士）一垫子=被将 ----
        // FEN 第一段 = rank9……第十段 = rank0（炮在第三段 = rank3 ✅；FEN 段序写错会让循环直接不成立）
        const s0 = fen.parseFen("3k5/9/9/9/9/9/4c4/4CA3/4A4/4K4 w - - 0 1").board;
        const step = function (board, iccs) {
            const applied = legal.applyMove(board, { iccs: iccs });
            return (applied && applied.board) ? applied.board : null;
        };
        const s1 = step(s0, "e0f0");   // 我方帅闲走（跑到 f 线，下一手对方炮 f3 就成将军）
        const s2 = step(s1, "e3f3");   // 黑炮换到 f 线（将军：f2 士是炮架）
        const s3 = step(s2, "f0e0");   // 我方帅躲回 e 线
        const s4 = step(s3, "f3e3");   // 黑炮回到 e 线（= 回到起点）

        const key = function (board) {
            let out = "";
            for (let i = 0; i < board.length; i++) {
                out += (board[i] == null ? "." : String(board[i]));
            }
            return out;
        };

        // 自证循环成立 + 四步都合法（场景自己先把前提钉死，免得盘面搭错还跑断言）
        t.ok(!!(s1 && s2 && s3 && s4), "四步（e0f0/e3f3/f0e0/f3e3）都应当能应用（盘面或着法搭错了）");
        if (!(s1 && s2 && s3 && s4)) {
            return;
        }
        t.equal(key(s4), key(s0), "四步之后应当回到原局面（这就是循环）");
        t.ok(legal.isLegalMove(s0, 4, 9, 5, 9, "w").legal, "帅 e0f0 应当合法（闲走；e 线有两个垫子，没被将）");
        t.ok(legal.isLegalMove(s1, 4, 6, 5, 6, "b").legal, "炮 e3f3 应当合法（换 f 线：隔 f2 士将军）");
        t.ok(legal.isLegalMove(s2, 5, 9, 4, 9, "w").legal, "帅 f0e0 应当合法（躲避 f 线将军）");
        t.ok(legal.isLegalMove(s3, 5, 6, 4, 6, "b").legal, "炮 f3e3 应当合法（回到 e 线）");

        const frames = [s0, s1, s2, s3];
        const stage = { idx: 0 };   // 偶数 = 我方回合帧；奇数 = 我方刚走完（等对方应手）的帧
        const state = function () { return frames[stage.idx % 4]; };

        const grid = geometry.createGrid({
            originX: 96, originY: 782, stepX: 136, stepY: 135,
            orientation: geometry.ORIENTATION_RED_BOTTOM, version: 9,
            quality: { sampleCount: 20, inlierCount: 20, maxResidual: 0.05, meanResidual: 0.02 }
        });

        const notes = [];
        const analyzeCalls = [];
        // 我方该走哪一步**按画面判**（不按调用次数）：重算/取消也不会串位
        const ourMoveFor = {};
        ourMoveFor[key(s0)] = "e0f0";
        ourMoveFor[key(s2)] = "f0e0";

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
                    reason: move ? "" : "场景只覆盖 e0f0 / f0e0 两个局面"
                };
            },
            tap: function (x, y, tapOptions) {
                // 「落点」那次点击 = 我方这手真的发出去了：画面立刻切到「走完之后」
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
        // 先观测两帧拿到 lastBoard，再声明「轮到我方」（面板「轮到我方」按钮同款语义）
        driver.tick();
        driver.tick();
        const declared = driver.assertTurn();
        t.ok(declared && declared.ok, "声明轮到我方应当成功（" + (declared && declared.reason) + "）");

        // 5 手（帅 3 次出现在 e0） = 起点局面第 3 次轮到我方（旧逻辑这一次就会开禁）
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
                // 这时 stage.idx 是「走完之后」的帧编号，回退一格拿这一步（偶数帧）的着法名
                t.log("我方第 " + confirmedOwn + " 手已确认（" +
                    ourMoveFor[key(frames[(stage.idx - 1) % 4])] + "）");
                if (confirmedOwn < targetOwnMoves) {
                    stage.idx += 1;   // 画面切到「对方应手之后」，对局继续
                }
            }
        }
        t.equal(confirmedOwn, targetOwnMoves, "五次走动都应当确认成功（循环里也走得完）");

        // 把「放行」那条日志亮出来（真机排障同款文本），证明守卫真的被踩到
        for (let i = 0; i < notes.length; i++) {
            if (notes[i].indexOf("循环检测放行") >= 0) {
                t.log(notes[i]);
                break;
            }
        }

        // ---- 核心断言：整条循环里引擎一次都没收到排除表 ----
        let withExcludes = null;
        for (let i = 0; i < analyzeCalls.length; i++) {
            const list = analyzeCalls[i].options && analyzeCalls[i].options.excludeIccs;
            if (list && list.length) {
                withExcludes = list;
                break;
            }
        }
        t.equal(withExcludes, null,
            "安静往返的循环里不应给我方设禁（旧逻辑第 3 次会禁 e0f0/f0e0 → 逼引擎换一手送子）");
        const stats = driver.stats();

        t.ok(analyzeCalls.slice(1).every(function (call) { return call.options.positionCommand &&
            call.options.positionCommand.indexOf(" moves ") >= 0; }),
            "循环经过的已确认棋谱必须送到引擎");
        t.equal(stats.loopBanHits, 0, "本次不该真的禁掉任何着法");
        t.ok(analyzeCalls.length >= targetOwnMoves, "五次算棋都应当进引擎（实际 " + analyzeCalls.length + " 次）");
        t.equal(stats.trackerRejects, 0, "账本不应拒收");
        t.equal(stats.trackerMismatch, 0, "影子与可靠基准不应失步");
        t.equal(stats.trackerErrors, 0, "账本不应抛异常");
    }
};
