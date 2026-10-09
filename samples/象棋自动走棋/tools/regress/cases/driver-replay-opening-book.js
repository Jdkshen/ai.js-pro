// @engine quickjs
/**
 * 驱动回放：**开局库步数闸门**（2026-09-25 用户：「开局用开局库走到第 N 手，之后引擎接手算棋」）。
 *
 * 干什么：一局从标准开局起，`bookOwnMoves: 2`，开局库（夹具）里**前两手和后一手都有条目**：
 *   · 我方第 1、2 手 → 命中库（原生引擎一次都不许被调）；
 *   · 我方第 3 手 → 库里**明明有条目**，但已超出 N 手 → 必须落到引擎（这就是「引擎接手」）。
 *
 * 这条链路真跑的是 engine-router → opening-book：
 *   · 库索引用 `opening-book.setTrialIndex` 注入（不走 /sdcard 文件）；
 *   · 原生引擎换成 `$pikafish` 桩（只在 PC/Node 上装得上 ⇒ 真机直接 skip）。
 * 顺带把「驱动 → options（bookOwnMoves/ownMovesMade）→ 路由 → 库前检查」整条透传都验了
 * （worker 路径的同名参数透传见 search-worker.js）。
 */
"use strict";

module.exports = {
    name: "驱动回放：开局库步数（前 2 手出库，第 3 手交回引擎）",
    tags: ["driver", "replay", "book"],
    run: function (t) {
        if (typeof global === "undefined" || typeof process === "undefined") {
            t.skip("仅 PC：开局库与原生引擎都在夹具里替换（真机请直接跑面板看日志里的「后端 开局库」）");
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

        // ---- 一局五手：我方 3 手 + 对方 2 应手（全部由棋规纯函数推出来） ----
        const startBoard = fen.parseFen(fen.START_FEN).board;
        const our1 = { iccs: "h2e2" };                            // 炮二平五（常用开局着法）
        const afterOur1 = legal.applyMove(startBoard, our1).board;
        const reply1 = legal.allLegalMoves(afterOur1, "b")[0];
        const afterReply1 = legal.applyMove(afterOur1, reply1).board;
        const our2 = legal.allLegalMoves(afterReply1, "w")[0];    // 库里给的第 2 手
        const afterOur2 = legal.applyMove(afterReply1, our2).board;
        const reply2 = legal.allLegalMoves(afterOur2, "b")[0];
        const afterReply2 = legal.applyMove(afterOur2, reply2).board;
        const our3 = legal.allLegalMoves(afterReply2, "w")[0];    // 引擎桩给的第 3 手
        const afterOur3 = legal.applyMove(afterReply2, our3).board;
        // 库里也给「第 3 手」塞一条（着法与引擎桩不同）：闸门要是失效，它就会被采用 ⇒ 断言抓得到
        const bookTrap = legal.allLegalMoves(afterReply2, "w")[1] || our3;

        const entry = function (iccs) {
            return {
                move: iccs, games: 100,
                candidates: [{ move: iccs, scoreType: "cp", score: 20, depth: 14 }]
            };
        };
        const bookKey = function (board, side) { return fen.layoutOf(board) + " " + side; };
        const fixture = { schema: 1, vet: { engine: "test" }, positions: {} };
        fixture.positions[bookKey(startBoard, "w")] = entry(our1.iccs);
        fixture.positions[bookKey(afterReply1, "w")] = entry(our2.iccs);
        fixture.positions[bookKey(afterReply2, "w")] = entry(bookTrap.iccs);   // 超出 N 手 → 不该被读

        const bookModule = t.load("src/engine/opening-book.js");
        bookModule.setTrialIndex(fixture);

        let nativeCalls = 0;
        global.$pikafish = {
            available: function () { return true; },
            isRunning: function () { return true; },
            start: function () { return { ok: true }; },
            // ⚠️ 宿主契约是**位置参数**：analyze(fen, movetime, timeoutMs, multiPv, depth)
            //    （选项里的 bookOwnMoves 不会到这里 —— 那层断言在 routerSeen 上做）
            analyze: function (fenText) {
                nativeCalls += 1;
                let board = startBoard;
                let side = "w";
                try {
                    const parsed = fen.parseFen(String(fenText));
                    if (parsed && parsed.ok && parsed.board) {
                        board = parsed.board;
                        side = parsed.side || (String(fenText).indexOf(" b ") > 0 ? "b" : "w");
                    }
                } catch (error) {
                    // 用开局盘兜底（夹具自证够用）
                }
                const move = legal.allLegalMoves(board, side)[0];
                return {
                    ok: true, move: move.iccs,
                    candidates: [{ move: move.iccs, scoreCp: 10, depth: 12, pv: move.iccs, timeMs: 50 }]
                };
            }
        };
        const engineModule = t.load("src/engine/engine-router.js");
        // ⚠️ 选项要在**路由层**抓：原生适配器会自造一份给宿主的选项对象，
        //    在 $pikafish 桩里看不到驱动传上来的 bookOwnMoves/ownMovesMade。
        const routerSeen = [];
        const analyzePort = function (position, options) {
            routerSeen.push({
                bookOwnMoves: options && options.bookOwnMoves,
                ownMovesMade: options && options.ownMovesMade
            });
            return engineModule.analyze(position, options);
        };

        const grid = geometry.createGrid({
            originX: 96, originY: 782, stepX: 136, stepY: 135,
            orientation: geometry.ORIENTATION_RED_BOTTOM, version: 7,
            quality: { sampleCount: 20, inlierCount: 20, maxResidual: 0.05, meanResidual: 0.02 }
        });

        const stage = { name: "pre" };
        const notes = [];
        const frameBoard = function () {
            switch (stage.name) {
                case "own1": return afterOur1;
                case "reply1": return afterReply1;
                case "own2": return afterOur2;
                case "reply2": return afterReply2;
                case "own3": return afterOur3;
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
            // 与面板一致：options 原样交给 engine-router（库前检查在路由里）
            analyze: analyzePort,
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
            // This fixture checks book routing, not the wall clock budget. A 20ms
            // search becomes a 100ms total cap and may expire before a cold stub
            // call under concurrent builds. Keep unrelated background jobs off.
            config: { movetimeMs: 1000, adaptiveSearch:false, criticalReview:false,
                prethink:false, predictReply:false, observeFrames: 1, bookOwnMoves: 2 }
        });

        const match = function (board) {
            const reliable = driver.lastReliable();
            return !!reliable && reliable.board.join("") === board.join("");
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

        try {
            driver.start();

            // ① 第 1 手：库里给 h2e2 ⇒ 秒出（引擎不许被调）
            // ⚠️ 条件必须带「账本已确认手数」：驱动发票据时会把可靠基准**乐观**推到落子后局面，
            //    只比盘面会在点击之前就误判"走完了"（它会去改 stage，把整局带沟里）。
            t.ok(ticksUntil(function () { return driver.ownMoveCount() >= 1 && match(afterOur1); }, 20),
                "第 1 手没走成（库里 h2e2）— 末尾日志：" + notes.slice(-4).join(" ｜ "));
            t.equal(nativeCalls, 0, "第 1 手命中库，不该调引擎");

            // ② 对方应手 → 轮到我方第 2 手：库里给 our2 ⇒ 仍然秒出
            stage.name = "reply1";
            t.ok(ticksUntil(function () { return match(afterReply1); }, 12), "对方第 1 应手应当被采纳");
            t.ok(ticksUntil(function () { return driver.ownMoveCount() >= 2; }, 20),
                "第 2 手没走成（库里给 " + our2.iccs + "）— 末尾日志：" + notes.slice(-4).join(" ｜ "));
            t.equal(nativeCalls, 0, "第 2 手仍在 N 手内，不该调引擎");

            // ③ 对方再应一手 → 第 3 手已超出 N(=2) ⇒ 库里明明有条目也不许用，交给引擎
            stage.name = "reply2";
            t.ok(ticksUntil(function () { return match(afterReply2); }, 12), "对方第 2 应手应当被采纳");
            t.ok(ticksUntil(function () { return driver.ownMoveCount() >= 3; }, 30),
                "第 3 手没走成（应当由引擎桩给 " + our3.iccs + "）— 末尾日志：" + notes.slice(-4).join(" ｜ "));
            t.equal(nativeCalls, 1, "超出 N 手之后必须落到引擎（恰一次）｜实际 " + nativeCalls +
                "｜路由层选项：" + JSON.stringify(routerSeen));
        } finally {
            delete global.$pikafish;
        }

        // 账本是「真的走成了哪几手」的唯一权威（不含恢复/重建路径的伪造匹配）
        const chain = String(driver.tracker().positionCommand || "");
        t.ok(chain.indexOf(our1.iccs) >= 0, "第 1 手应当是开局库给的 " + our1.iccs + "（账本：" + chain + "）");
        t.ok(chain.indexOf(our2.iccs) >= 0, "第 2 手应当是开局库给的 " + our2.iccs + "（账本：" + chain + "）");
        t.ok(chain.indexOf(bookTrap.iccs) < 0,
            "库里第 3 手那条（" + bookTrap.iccs + "）超过 N 手不该被采用（账本：" + chain + "）");
        t.ok(chain.indexOf(our3.iccs) >= 0,
            "第 3 手应当是引擎桩的着法 " + our3.iccs + "（账本：" + chain + "）");
        if (routerSeen.length) {
            t.equal(routerSeen[0].bookOwnMoves, 2, "引擎收到的选项里应带上面板设的库步数");
            t.equal(routerSeen[0].ownMovesMade, 0, "第一手时「已走 0 手」");
        }
        t.ok(notes.join("｜").indexOf("开局库(PC-Pikafish)") >= 0,
            "前两手应当来自开局库（日志里要能看到后端名）");
    }
};
