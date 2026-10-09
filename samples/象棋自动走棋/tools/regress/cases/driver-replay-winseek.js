// @engine quickjs
/** 驱动实走回放：求胜只在本次候选分差足够小时换招，重复着法不进入硬排除表。 */
"use strict";

module.exports = {
    name: "驱动回放：求胜模式（均势时避开走回旧局面的着法）",
    tags: ["driver", "replay", "win-seek"],
    run: function (t) {
        if (typeof global === "undefined" || typeof process === "undefined") {
            t.skip("仅 PC：识别与引擎都在夹具里替换（真机请直接看日志里的「求胜模式：这个局面评分…」一行）");
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

        // ---- 循环夹具：红车 a0↔a1、黑车 a9↔a8 来回摆（真机日志里那种「谁也赢不了」的盘面） ----
        const startBoard = fen.parseFen(fen.START_FEN).board;
        const repeatIccs = "a0a1";                     // 我方想走的重复着法
        const afterOur1 = legal.applyMove(startBoard, { iccs: repeatIccs }).board;
        const afterReply1 = legal.applyMove(afterOur1, { iccs: "a9a8" }).board;
        const afterOur2 = legal.applyMove(afterReply1, { iccs: "a1a0" }).board;
        const afterReply2 = legal.applyMove(afterOur2, { iccs: "a8a9" }).board;   // 回头 = 开局盘面
        // 摆完一轮再走 repeatIccs：局面与第一轮记过的**完全一样**（这就是被排除的那一手）
        const afterOur3Repeat = legal.applyMove(afterReply2, { iccs: repeatIccs }).board;
        // 排除生效时引擎端口会挑的第一手 = 合法着法表里第一个不是 a0a1 的（夹具端口的兜底口径）
        const fallbackIccs = (function () {
            const list = legal.allLegalMoves(startBoard, "w");
            for (let i = 0; i < list.length; i++) {
                if (list[i].iccs !== repeatIccs) {
                    return list[i].iccs;
                }
            }
            return null;
        })();
        const afterOur3Fallback = legal.applyMove(afterReply2, { iccs: fallbackIccs }).board;

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

        /**
         * 跑一个场景（同一套脚本化帧），返回账本 / 日志 / 引擎端口收到的选项。
         * @param sc { label, winSeek, score, expectRepeat }
         */
        const runScenario = function (sc) {
            const computes = [];
            const notes = [];
            const stage = { name: "pre" };
            const frameBoard = function () {
                switch (stage.name) {
                    case "own1": return afterOur1;
                    case "reply1": return afterReply1;
                    case "own2": return afterOur2;
                    case "reply2": return afterReply2;
                    case "own3": return sc.expectRepeat ? afterOur3Repeat : afterOur3Fallback;
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
                // 与面板同签名：analyze(position, options)；excludeIccs 就是驱动排除逻辑的出口
                analyze: function (position, options) {
                    const board = (position && position.board) ? position.board : startBoard;
                    const side = (position && position.side) ? position.side : "w";
                    const excludes = (options && options.excludeIccs) ? options.excludeIccs : [];
                    const list = legal.allLegalMoves(board, side);
                    const prefs = ["a1a0", "a0a1"];         // 脚本想走的循环着法，合法才用
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
                    computes.push({
                        boardKey: fingerprint(board), side: side,
                        excludes: excludes.slice(), picked: pick, multiPv: options.multiPv
                    });
                    return {
                        ok: true, iccs: pick, move: pick,
                        score: sc.score, depth: 12, pvIccs: [pick], ms: 30,
                        candidates: [{move:pick,scoreCp:sc.score,depth:12},
                            {move:fallbackIccs,scoreCp:sc.altScore == null ? sc.score-5 : sc.altScore,depth:12}]
                    };
                },
                tap: function (x, y, tapOptions) {
                    if (tapOptions && tapOptions.kind === "to") {
                        if (stage.name === "pre") {
                            stage.name = "own1";
                        } else if (stage.name === "reply1") {
                            stage.name = "own2";
                        } else if (stage.name === "reply2") {
                            stage.name = "own3";
                        }
                    }
                    return { ok: true, method: "replay-tap" };
                },
                screen: function () { return { width: 1080, height: 2400 }; },
                nowMono: function () { return Date.now(); },
                config: {
                    movetimeMs: 20, observeFrames: 1,
                    // 场景显式配置评分窗口，保留旧下限 opt-out 并覆盖新的近均势窗口。
                    winSeek: sc.winSeek === true, winSeekScoreMin: sc.minScore == null ? 0 : sc.minScore, winSeekScoreMax: 25,
                    winSeekMinSeen: sc.minSeen == null ? 2 : sc.minSeen,
                    bookOwnMoves: 0   // 本用例不走开局库（引擎端口已换夹具）
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
            t.ok(ticksUntil(function () { return driver.ownMoveCount() >= 1 && match(afterOur1); }, 20),
                sc.label + "：第 1 手（车出来 a0a1）没走成 — 末尾日志：" + tail());
            stage.name = "reply1";
            t.ok(ticksUntil(function () { return match(afterReply1); }, 12),
                sc.label + "：黑车 a9a8 的应手应当被采纳？— 末尾日志：" + tail());
            t.ok(ticksUntil(function () { return driver.ownMoveCount() >= 2; }, 20),
                sc.label + "：第 2 手（车缩回 a1a0）没走成 — 末尾日志：" + tail());
            stage.name = "reply2";
            t.ok(ticksUntil(function () { return match(afterReply2); }, 12),
                sc.label + "：黑车 a8a9 的应手应当被采纳？— 末尾日志：" + tail());
            // 第 3 手：正是「要不要再走一遍 a0a1」这个决策点
            t.ok(ticksUntil(function () { return driver.ownMoveCount() >= 3; }, 30),
                sc.label + "：第 3 手没走成 — 末尾日志：" + tail());
            return { notes: notes, computes: computes, chain: String(driver.tracker().positionCommand || "") };
        };

        const scenarios = [
            { label: "求胜关", winSeek: false, score: 0, minSeen: 2, expectRepeat: true },
            { label: "求胜开+均势（评分 0）+minSeen 1", winSeek: true, score: 0, minSeen: 1, expectRepeat: false },
            { label: "求胜开+均势+默认 minSeen 2（只是第 2 次重复）", winSeek: true, score: 0, minSeen: 2, expectRepeat: true },
            { label: "求胜开+落后（评分 -20）", winSeek: true, score: -20, minSeen: 1, expectRepeat: true },
            { label: "近均势（评分 -9，下限 -25）", winSeek: true, score: -9, minScore: -25, minSeen: 1, expectRepeat: false },
            { label: "求胜开+均势但替代亏200", winSeek: true, score: 0, altScore: -200, minSeen: 1, expectRepeat: true },
            { label: "求胜开+大优（评分 500）", winSeek: true, score: 500, minSeen: 1, expectRepeat: true }
        ];
        for (let si = 0; si < scenarios.length; si++) {
            const sc = scenarios[si];
            const out = runScenario(sc);
            const repeatCount = countOf(out.chain, repeatIccs);
            // ⚠ 只看「求胜模式」日志行：驱动自己的走子/确认日志里本来就满屏 a0a1
            const winSeekNotes = out.notes.filter(function (line) {
                return String(line).indexOf("求胜模式") >= 0;
            }).join("｜");
            const bannedBy = function () {
                for (let i = 0; i < out.computes.length; i++) {
                    if (out.computes[i].excludes.indexOf(repeatIccs) >= 0) {
                        return out.computes[i];
                    }
                }
                return null;
            }();
            if (sc.expectRepeat) {
                t.equal(repeatCount, 2,
                    sc.label + "：重复着法应当照走两次（账本：" + out.chain + "）");
                t.ok(bannedBy == null,
                    sc.label + "：不该把 a0a1 交给引擎排除（实收：" +
                    (bannedBy ? bannedBy.excludes.join("/") : "") + "）");
                // ⚠ 不能断言「完全不打求胜日志」：这一带局面里还有「车对车」的等价兑子（a1a8），
                //    求胜模式会为它单独出手 —— 这里只要求「重复着法没进排除表」。
                t.ok(!out.notes.some(function (line) { return line.indexOf("→ 候选") >= 0; }),
                    sc.label + "：没有合适候选时应保留原着法");
            } else {
                t.equal(repeatCount, 1,
                    sc.label + "：回来那一手不该再走 a0a1（账本：" + out.chain + "）");
                t.ok(out.chain.indexOf(fallbackIccs) >= 0,
                    sc.label + "：应当改走次优着法 " + fallbackIccs + "（账本：" + out.chain + "）");
                t.equal(bannedBy, null, sc.label + "：重复候选不能进入硬排除表");
                t.ok(out.computes.some(function (call) { return call.multiPv > 1; }),
                    sc.label + "：通过多路评分比较替代");
                // ⚠ 日志有 15s 限频（「避开兑子」和「重复排除」共用窗口，谁先打不定）⇒ 不拿具体文案当机制判据，
                //    机制由上面的 bannedBy/excludes 断言把关；这里只要求确实出现过求胜模式日志。
                t.ok(winSeekNotes.indexOf("求胜模式") >= 0,
                    sc.label + "：应当出现过求胜模式日志（实际：" + winSeekNotes.slice(-240) + "）");
            }
        }
        if (fallbackIccs == null) {
            t.ok(false, "夹具不对：开局盘面除 a0a1 外竟没有别的合法着法");
        }
    }
};
