// @engine quickjs
/**
 * 驱动回放：**求胜模式之「等价兑子」**（2026-09-25 用户：「为什么前面没有给他挖好坑，到后面又和老」）。
 *
 * 棋理背景：引擎每一步都假设对手不犯错 ⇒ 它自己不避兑子（「均势 0 分 + 子力换光 = 0 分」，它眼里没损失）。
 * 但对人来说子力换光 = 和棋，而对手深度有限 —— 留着子力的复杂局面才是它可能算错的地方。
 * 所以求胜模式在**不落后**时把「等价兑子」也排掉。
 *
 * 场景（车兑车：红车 b0、黑车 b4 + a8；⚠ iccs rank 0 = 红方底线）：
 *   ① 第 1 手走无害的 b0a0 —— 这一手之前还没算过棋 ⇒ 闸门（评分未知）不放行，不该排除任何东西（基线）；
 *      算完这一手评分落账（夹具给 0）；黑方应手把车摆进 a4；
 *   ② 第 2 手：红车 a0 吃 a4 是**等价兑子**（黑车 a8 立刻吃回同一格）⇒ 必须被排进 excludeIccs，
 *      实际走的是兜底着法（不是 a0a4）；
 *   ③ 对照组（winSeek 关）：同一套帧，第 2 手照走 a0a4 —— 证明「不开关就不动」。
 *
 * 排除表出口 = 引擎端口的 options.excludeIccs（面板同签名 analyze(position, options)）。
 */
"use strict";

module.exports = {
    name: "驱动回放：求胜模式之等价兑子（留住子力，不主动简化）",
    tags: ["driver", "replay", "win-seek", "swap"],
    run: function (t) {
        if (typeof global === "undefined" || typeof process === "undefined") {
            t.skip("仅 PC：识别与引擎都在夹具里替换（真机请直接看日志里的「等价兑子 N」）");
            return;
        }
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

        // 起始（第 1 手前）：黑将 e9、黑车 b4 + a8；红车 b0、红帅 d0（将帅不同列，局面合法）
        const startBoard = fen.parseFen("4k4/r8/9/9/9/1r7/9/9/9/1R1K5 w - - 0 1").board;
        const our1 = "b0a0";                                                     // 无害：红车横进 a 列
        const reply1 = "b4a4";                                                   // 黑车横进 a4（红车 a0 可吃）
        const swapIccs = "a0a4";                                                 // 等价兑子（黑车 a8 立刻吃回）
        const afterOur1 = legal.applyMove(startBoard, { iccs: our1 }).board;
        const afterReply1 = legal.applyMove(afterOur1, { iccs: reply1 }).board;
        const afterOur2Swap = legal.applyMove(afterReply1, { iccs: swapIccs }).board;
        const fallbackIccs = (function () {
            const list = legal.allLegalMoves(afterReply1, "w");
            for (let i = 0; i < list.length; i++) {
                if (list[i].iccs !== swapIccs) {
                    return list[i].iccs;
                }
            }
            return null;
        })();
        const afterOur2Fallback = legal.applyMove(afterReply1, { iccs: fallbackIccs }).board;

        const grid = geometry.createGrid({
            originX: 96, originY: 782, stepX: 136, stepY: 135,
            orientation: geometry.ORIENTATION_RED_BOTTOM, version: 7,
            quality: { sampleCount: 20, inlierCount: 20, maxResidual: 0.05, meanResidual: 0.02 }
        });

        const fingerprint = function (board) {
            let out = "";
            for (let i = 0; i < board.length; i++) {
                out += (board[i] == null ? "." : String(board[i]));
            }
            return out;
        };
        const countOf = function (text, needle) {
            return String(text).split(needle).length - 1;
        };
        const startKey = fingerprint(startBoard);
        const reply1Key = fingerprint(afterReply1);

        /**
         * 跑一个场景：同一套脚本化帧，只换 winSeek 开关。
         * @param sc { label, winSeek, expectSwap }
         */
        const runScenario = function (sc) {
            const computes = [];
            const notes = [];
            const stage = { name: "pre" };
            const frameBoard = function () {
                switch (stage.name) {
                    case "own1": return afterOur1;
                    case "reply1": return afterReply1;
                    case "own2": return sc.expectSwap ? afterOur2Swap : afterOur2Fallback;
                    default: return startBoard;
                }
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
                // 与面板同签名：analyze(position, options)；excludeIccs / multiPv 就是驱动策略的出口
                analyze: function (position, options) {
                    const board = (position && position.board) ? position.board : startBoard;
                    const side = (position && position.side) ? position.side : "w";
                    const excludes = (options && options.excludeIccs) ? options.excludeIccs : [];
                    const multiPv = (options && options.multiPv) ? Number(options.multiPv) : 0;
                    const boardKey = fingerprint(board);
                    // 脚本想走的着法：第 1 手摆车，第 2 手吃（兑子）
                    const prefs = boardKey === startKey ? [our1] : (boardKey === reply1Key ? [swapIccs] : []);
                    const list = legal.allLegalMoves(board, side);
                    let pick = null;
                    for (let i = 0; i < prefs.length && pick == null; i++) {
                        for (let j = 0; j < list.length; j++) {
                            if (list[j].iccs === prefs[i] && excludes.indexOf(prefs[i]) < 0) {
                                pick = prefs[i];
                            }
                        }
                    }
                    for (let j = 0; j < list.length && pick == null; j++) {
                        if (excludes.indexOf(list[j].iccs) < 0) {
                            pick = list[j].iccs;
                        }
                    }
                    // ★ 多路候选（模拟 MultiPV）：第一路 = 首选（可能是兑子），第二路 = 第一个非兑子替代
                    let second = null;
                    for (let j = 0; j < list.length; j++) {
                        if (list[j].iccs !== pick && excludes.indexOf(list[j].iccs) < 0) {
                            second = list[j].iccs;
                            break;
                        }
                    }
                    computes.push({
                        boardKey: boardKey, side: side, multiPv: multiPv,
                        excludes: excludes.slice(), picked: pick,
                        swapExcluded: excludes.indexOf(swapIccs) >= 0
                    });
                    return {
                        ok: true, iccs: pick, move: pick,
                        score: 0, depth: 12, pvIccs: [pick], ms: 30,
                        candidates: multiPv > 0
                            ? [{ move: pick, scoreCp: 0 }, { move: second, scoreCp: -5 }]
                            : null
                    };
                },
                tap: function (x, y, tapOptions) {
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
                config: {
                    movetimeMs: 20, observeFrames: 1,
                    winSeek: sc.winSeek === true,
                    winSeekScoreMin: 0, winSeekScoreMax: 25, winSeekMinSeen: 2,
                    bookOwnMoves: 0
                }
            });

            const tail = function () { return notes.slice(-4).join(" ｜ "); };
            const match = function (board) {
                const reliable = driver.lastReliable();
                return !!reliable && fingerprint(reliable.board) === fingerprint(board);
            };
            const ticksUntil = function (condition, limit) {
                for (let i = 0; i < (limit || 12); i++) {
                    if (condition()) {
                        return true;
                    }
                    driver.tick();
                }
                return condition();
            };

            driver.start();
            driver.tick();
            driver.tick();
            // ⚠ 非标准开局夹具：驱动自己认不出轮到谁，要先「现在轮到我」声明一次（同 check-cycle 用例）
            const declared = driver.assertTurn();
            t.ok(declared && declared.ok,
                sc.label + "：声明轮到我方应当成功（" + (declared && declared.reason) + "）");
            // ① 第 1 手：还没算过棋 ⇒ 闸门不放行，不该排除任何东西
            t.ok(ticksUntil(function () { return driver.ownMoveCount() >= 1 && match(afterOur1); }, 20),
                sc.label + "：第 1 手（b0a0）没走成 — 末尾日志：" + tail());
            t.equal(computes.length >= 1 ? computes[0].excludes.length : -1, 0,
                sc.label + "：第 1 手之前没有评分 ⇒ 不该排任何着法（实际：" +
                (computes.length ? JSON.stringify(computes[0].excludes) : "没算过") + "）");
            // ② 对方应手：黑车 b4→a4（帧差唯一解释）
            stage.name = "reply1";
            t.ok(ticksUntil(function () { return match(afterReply1); }, 12),
                sc.label + "：黑车 b4a4 的应手应当被采纳 — 末尾日志：" + tail());
            // ③ 第 2 手：等价兑子该不该被排掉
            t.ok(ticksUntil(function () { return driver.ownMoveCount() >= 2; }, 30),
                sc.label + "：第 2 手没走成 — 末尾日志：" + tail());
            return { notes: notes, computes: computes, chain: String(driver.tracker().positionCommand || "") };
        };

        const scenarios = [
            { label: "求胜关", winSeek: false, expectSwap: true },
            { label: "求胜开（不落后+评分 0）", winSeek: true, expectSwap: false }
        ];
        for (let si = 0; si < scenarios.length; si++) {
            const sc = scenarios[si];
            const out = runScenario(sc);
            const winSeekNotes = out.notes.filter(function (line) {
                return String(line).indexOf("求胜模式") >= 0;
            }).join("｜");
            const swapExcludedAt = (function () {
                for (let i = 0; i < out.computes.length; i++) {
                    if (out.computes[i].swapExcluded) {
                        return out.computes[i];
                    }
                }
                return null;
            }());
            const multiSee = (function () {
                for (let i = 0; i < out.computes.length; i++) {
                    if (out.computes[i].multiPv >= 2) {
                        return out.computes[i];
                    }
                }
                return null;
            }());
            if (sc.expectSwap) {
                t.equal(countOf(out.chain, swapIccs), 1,
                    sc.label + "：第 2 手应当照走兑子 a0a4（账本：" + out.chain + "）");
                t.ok(swapExcludedAt == null,
                    sc.label + "：不该把兑子交给引擎排除（实收：" +
                    (swapExcludedAt ? swapExcludedAt.excludes.join("/") : "") + "）");
                t.ok(multiSee == null,
                    sc.label + "：没开求胜时不该开多路（实际 multiPv=" + (multiSee ? multiSee.multiPv : 0) + "）");
            } else {
                t.equal(countOf(out.chain, swapIccs), 0,
                    sc.label + "：第 2 手不该再走兑子 a0a4（账本：" + out.chain + "）");
                t.ok(out.chain.indexOf(fallbackIccs) >= 0,
                    sc.label + "：应当改走非兑子候选 " + fallbackIccs + "（账本：" + out.chain + "）");
                t.ok(swapExcludedAt == null,
                    sc.label + "：兑子**不许硬排除**（实收：" +
                    (swapExcludedAt ? swapExcludedAt.excludes.join("/") : "") + "）");
                t.ok(multiSee != null && multiSee.multiPv >= 2,
                    sc.label + "：有兑子候选时应当开多路（实际 " + (multiSee ? multiSee.multiPv : "没开") + "）");
                t.ok(winSeekNotes.indexOf("→ 候选") >= 0,
                    sc.label + "：日志要说清「首选是兑子 → 改走替代」（实际：" + winSeekNotes.slice(-240) + "）");
            }
        }
        if (fallbackIccs == null) {
            t.ok(false, "夹具不对：兑子局面下除 a0a4 外竟没有别的合法着法");
        }
    }
};
