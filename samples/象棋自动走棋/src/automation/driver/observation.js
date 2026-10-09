// @engine quickjs
"use strict";

/** 完整观测与局面采纳：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const machineModule = require("../state-machine.js");
const verifier = require("../move-verifier.js");
const safety = require("../observation-safety.js");
const pieceCapAudit = require("../piece-cap-audit.js");
const legal = require("../../chess/legal-moves.js");

function create(driverState, driverActions) {
    const royalGuard = safety.createGuard();
    /**
     * 盘面 → 紧凑文本（FEN 风格：空位用数字压缩，未知格写 '?'）。
     * ⚠️ 不能用「只拼字符」的写法：那样丢掉了空格子，**看不出列号**，诊断等于白打
     * （真机踩过：想把「我方落子+对方应手」的判错盘面读出来，结果两行都没列号，只能重跑）。
     */
    function boardText(chars) {
        if (!chars) {
            return "-";
        }
        const rows = [];
        for (let r = 0; r < 10; r++) {
            let line = "";
            let empty = 0;
            for (let c = 0; c < 9; c++) {
                const ch = chars[r * 9 + c];
                if (ch) {
                    if (empty > 0) {
                        line += empty;
                        empty = 0;
                    }
                    line += ch;
                } else {
                    empty += 1;
                }
            }
            if (empty > 0) {
                line += empty;
            }
            rows.push(line);
        }
        return rows.join("/");
    }

    /** 截一帧并识别。复用已校准几何（700ms 的定位不必每轮都做）。 */
    function observeOnce(options) {
        const started = driverState.nowMono();
        const request = {
            grid: options && options.relocate ? null : driverState.grid,
            orientation: driverState.cfg.orientation,
            confidence: driverState.cfg.confidence,
            timeoutMs: 400,
            // 只读的上一张可信盘面：选中高亮不能把原地棋子变成另一兵种。
            referenceBoard:
                driverState.lastReliable && driverState.lastReliable.board
                    ? driverState.lastReliable.board.slice()
                    : null
        };
        // A capture can invalidate the old destination fact later in this call.
        // Legal reply inference must retain the complete board before that repair.
        const factBaseline=request.referenceBoard && driverState.lastReliable
            ? {board:request.referenceBoard,side:driverState.lastReliable.side} : null;
        const independentRead = options && options.independentRead === true;
        if (independentRead) {
            request.independentRead = true;
            request.referenceBoard = null;
            request.freshRead = true;
            request.frames = 2;
        }
        if (driverState.cfg.regionRatio) {
            request.regionRatio = driverState.cfg.regionRatio;
        }
        // ★ 观察帧数按状态分级（2026-09-21 提速·方案第 4 条）：等待对方（WAIT_TURN）只用 1 帧 ——
        //   那时我们只是「盯画面有没有变」，新解释本来就要跨观测佐证（轮次待佐证），1 帧足够；
        //   出手前/确认/恢复仍走 observeFrames（2 帧），别动关键判断的帧数。
        if (!independentRead && driverState.cfg.observeFramesByState === true && !(options && options.forVerify)) {
            let stateNow = null;
            try {
                stateNow = driverState.machine.state ? driverState.machine.state() : null;
            } catch (error) {
                stateNow = null;
            }
            if (stateNow === machineModule.STATE.WAIT_TURN) {
                request.frames = 1;
            }
        }
        if (driverState.forceFreshReadOnce) {
            // 杀棋复核：这一次观察跳过差分缓存、全盘新鲜重读（面板侧看 options.freshRead）
            request.freshRead = true;
        }
        const stateNow = driverState.machine.state();
        if (!independentRead && !(options && options.forVerify) &&
            ["WAIT_STABLE", "WAIT_TURN", "RECOVERING"].indexOf(stateNow) >= 0) {
            request.asyncObservation = true;
            request.stamp = Object.assign({state:stateNow}, driverState.machine.versions());
        }
        let result;
        try {
            result = driverActions.recognizeMerged(request);
        } catch (error) {
            driverState.stats.observations += 1;
            driverState.stats.errors += 1;
            driverState.stats.lastError = String(error && error.message ? error.message : error);
            driverState.forceFreshReadOnce = false;
            return { ok: false, reason: "识别异常：" + driverState.stats.lastError };
        }
        // Polling is not an observation. Keep the current facts and failure counters intact.
        if (result && result.pending) return result;
        const royalRecheck = safety.recheckRoyal(result, request, driverActions.recognizeMerged, function () {
            driverState.clock(420);
        });
        if (royalRecheck.attempted) {
            driverActions.note("将帅复核前原始盘面：" + boardText(result.rawChars || result.chars));
            driverState.stats.royalRechecks = (driverState.stats.royalRechecks || 0) + 1;
            if (royalRecheck.recovered) {
                driverState.stats.royalRecheckHits = (driverState.stats.royalRecheckHits || 0) + 1;
                driverActions.note("将帅新帧复核通过：两张独立读数已看清，未使用旧盘面补子");
            } else driverActions.note("将帅新帧复核未通过：" + royalRecheck.reason + "｜原始盘面 " +
                boardText(royalRecheck.result && (royalRecheck.result.rawChars || royalRecheck.result.chars)));
            result = royalRecheck.result;
        }
        driverState.stats.observations += 1;
        driverState.coveredFilledIndexes = [];
        driverState.evidenceIndexes = [];
        driverState.forceFreshReadOnce = false; // 只对这一次观察生效（recognizeMerged 里两帧合并都会带 freshRead）
        driverState.stats.lastObserveMs = result && result.asyncMs != null
            ? result.asyncMs : driverState.nowMono() - started;
        driverState.lastResult = result;
        const danger = royalGuard.check(result, driverState.machine.versions().sessionId, driverState.stats.observations);
        driverState.royalDanger = danger;
        if (danger) {
            driverState.stats.unrecognized += 1;
            driverActions.note("将帅安全拦截：" + danger.reason + "｜新帧 " + danger.streak + "/2");
            if (danger.confirmed) driverActions.pump(driverState.machine.dispatch({type:"PAUSE",
                reason:"连续新帧将帅缺失或重复，疑似对局结束或识别异常，已停自动走棋"}));
            return {ok:false, royalUnsafe:true, stopRequired:danger.confirmed, reason:danger.reason};
        }
        if (!result || !result.ok) {
            driverState.stats.unrecognized += 1;
            if (result && result.geometryChanged) {
                driverState.geometryStaleUntil = driverState.nowMono() + driverState.GEOMETRY_SUSPECT_MS;
            }
            return { ok: false, geometryChanged: !!(result && result.geometryChanged),
                reason: result && result.reason ? result.reason : "识别失败" };
        }
        // Independent audit reads must not repair the baseline, reuse destination facts,
        // or write fresh observations into history before the caller has compared them.
        if (independentRead) {
            const chars = result.chars && result.chars.slice();
            const gridChanged = result.grid && driverState.grid &&
                ["originX", "originY", "stepX", "stepY", "version"].some(function (key) {
                    return result.grid[key] !== driverState.grid[key];
                });
            return {
                ok: !!chars && chars.length === 90 && !gridChanged,
                reason: gridChanged ? "核对期间棋盘坐标发生变化" : "独立全盘重读",
                chars: chars,
                rawChars: result.rawChars ? result.rawChars.slice() : chars,
                unknownCells: chars ? driverActions.countUnknownChars(chars) : 90,
                conflicts: result.stats && result.stats.conflicts || 0,
                coveredFills: 0,
                layout: chars ? boardText(chars) : null,
                result: result
            };
        }
        // 识别时几何被微调过（按检测框对齐）→ 记下来，让后面的点击用同一套几何，
        // 否则会出现「按 A 几何认棋盘、按 B 几何点坐标」的错位。
        if (
            result.grid &&
            driverState.grid &&
            (result.grid.originX !== driverState.grid.originX ||
                result.grid.originY !== driverState.grid.originY ||
                result.grid.stepX !== driverState.grid.stepX ||
                result.grid.stepY !== driverState.grid.stepY)
        ) {
            const dx = Math.round(result.grid.originX - driverState.grid.originX);
            const dy = Math.round(result.grid.originY - driverState.grid.originY);
            const stepDriftX = Math.abs(result.grid.stepX / driverState.grid.stepX - 1);
            const stepDriftY = Math.abs(result.grid.stepY / driverState.grid.stepY - 1);
            // 检测框误配曾把原点一次挪 (-171,-192)px，后续盘面整行错位。
            // 同一次观察只接受小幅微调；大位移必须走重新定位及其锚定验证。
            if (
                Math.abs(dx) > Math.max(12, driverState.grid.stepX * 0.35) ||
                Math.abs(dy) > Math.max(12, driverState.grid.stepY * 0.35) ||
                stepDriftX > 0.05 ||
                stepDriftY > 0.05
            ) {
                driverState.stats.unrecognized += 1;
                driverState.geometryStaleUntil = driverState.nowMono() + driverState.GEOMETRY_SUSPECT_MS; // ★ 几何变了的强证据（见变量注释）
                driverActions.note(
                    "检测框几何微调越界 (" +
                        dx +
                        "," +
                        dy +
                        ")，格距变化 " +
                        Math.round(Math.max(stepDriftX, stepDriftY) * 100) +
                        "% → 拒收本帧，等重新定位（已标记几何可疑：本轮恢复/沿用都不许再信旧几何）"
                );
                return { ok: false, reason: "检测框几何微调越界", grid: driverState.grid };
            }
            driverState.grid = result.grid;
            driverActions.note(
                "几何按检测框微调：原点偏移 (" +
                    dx +
                    "," +
                    dy +
                    ")（未知格 " +
                    (result.stats && result.stats.autoAlign ? result.stats.autoAlign.unknown : "?") +
                    "）"
            );
        }
        // 点棋用「实测框中心」：连它所属的几何版本一起记下（版本不符就退回交点）
        driverState.lastCenters = result.centers || null;
        driverState.lastCentersVersion =
            result.grid && result.grid.version != null
                ? result.grid.version
                : driverState.grid && driverState.grid.version != null
                  ? driverState.grid.version
                  : null;
        // 「游戏画花了的格子」→ 按已知事实补齐。
        // 真机现象：游戏给刚落的子画高亮环，那一格会被模型认错（实测：带环的炮读成马），
        // 棋规层再把它降级成 '?'。**我方落子和对方应手的子都会被画环**，所以这是
        // 一张表（最多两格）。不补的话未知格≠0，轮次推断直接放弃 → 卡死在 RECOVERING。
        // 每一格一旦能自己认出来（或被吃/换子）就自动从表里去掉。
        // ⚠️ **先留一份模型读到的原始观测**（§12）：后面的补丁/补脑/纠色会就地改 `result.chars`，
        // 不留原始读数就分不清「模型看到了什么」与「我们按规则改成了什么」。
        // 诊断与审核只看 rawChars，行动仍看修正后的盘面（两者都必须能查到）。
        const rawChars = result.rawChars ? result.rawChars.slice() : (result.chars ? result.chars.slice() : null);
        let patchedNow = null;
        let unknownCells = result.unknownCells == null ? 0 : result.unknownCells;
        /** 「已知事实说有子，模型却整格读成空」的可疑格 → 交给单格重拍（见 reprobeSuspects） */
        const suspects = [];
        const facts = driverState.knownFacts.list();
        // 应手同兵种吃子先作严格单步检查。这里只保留前基准，仍交给轮次层跨帧确认；
        // 若先删除前盘面的被吃子，会把一次正常吃子变成 resync 并丢掉整段棋谱。
        let replyCapture = null;
        if (facts.length && !(options && options.forVerify) && factBaseline && factBaseline.board &&
            factBaseline.side === (driverState.machine.mySide() === "w" ? "b" : "w") &&
            rawChars && rawChars.length === 90 && factBaseline.board.indexOf("?") < 0 &&
            facts.some(function (patch) {
                const raw = patch && rawChars[patch.index];
                return patch && patch.char && raw && raw !== patch.char &&
                    raw.toLowerCase() === patch.char.toLowerCase();
            })) {
            const matched = legal.matchObserved(factBaseline.board, factBaseline.side, rawChars);
            if (matched.status === "UNIQUE" && matched.move) {
                const move = matched.move;
                const index = move.toRow * 9 + move.toCol;
                const applied = legal.applyMove(factBaseline.board, move);
                if (factBaseline.board[index] && applied && applied.board &&
                    rawChars[index] === applied.board[index]) {
                    replyCapture = { index: index, char: applied.board[index], iccs: move.iccs };
                }
            }
        }
        if (facts.length > 0 && result.chars) {
            const keep = [];
            for (let i = 0; i < facts.length; i++) {
                const patch = facts[i];
                if (patch == null || patch.index == null) {
                    continue;
                }
                const got = result.chars[patch.index];
                // ⚠️ 这里**不看周期**（2026-09-14 真机事故，见 doVerify 里的说明）：
                // 以前是「上一手的记录一律作废」，结果是「对方刚落子的那格」在我方一确认就失效
                // → 那格读成空时没人补 → 每帧都解释不了 → 白等 54 秒。
                // 现在改成按**棋子是否离开**作废（dropPatchesAt）：能自己认出来/变成别的同色子
                // 就留着（装饰会反复发作），真被吃成异色子则丢掉（不能把吃子藏起来）。
                // 确认时丢掉与票据后盘面冲突的旧记录，让本帧观测参与验证。
                // 包括起点变空和落点被吃换色。K40 h0h9 吃车时，落点旧黑车记录把
                // 新红车当成基准错误，删掉落子前黑车并断开棋谱；正常吃子不能修订前盘面。
                // 这里只删除补齐提示，不把票据期望直接写入观测，也不改 lastReliable。
                if (options && options.expect) {
                    const want = options.expect[patch.index];
                    if (want === null || want === "" || want === undefined || want !== patch.char) {
                        continue;
                    }
                }
                // ★ 空网格记录（途经格，见 addEmptyPatch）：读到「空」= 与已知事实**一致**，绝不能
                //   像下面那样翻成 '?'。翻了就等于凭空造未知格：K40 实测每帧差 1~3 格
                //   （(2,4)(2,5) 两个途经格被翻成 '?'）→ `sideFor` 放弃 → 「轮次未知」空转 60 秒。
                if (patch.char == null) {
                    if (got === "?" || got === "" || got === null || got === undefined) {
                        if (got === "?") {
                            result.chars[patch.index] = null;
                            unknownCells = Math.max(0, unknownCells - 1);
                        }
                        keep.push(patch);
                    }
                    // 读到子 ⇒ 与「这格必为空」冲突（可能对方应手正落在这里）→ 丢掉记录，让观测说话
                    continue;
                }
                if (got === "?" || got === "" || got === null || got === undefined) {
                    // ① 读不出来 / 整格漏检。
                    // ★ 2026-09-19（K40 真机）：区分「模型看到了东西但认不出」与「整格没检测到」——
                    //   `?`（看到了但认不出）在实战里几乎一定是**游戏给刚落/刚动的子画的装饰**
                    //   （高亮环/命中标记），我们的子还在那一格 ⇒ 直接按已知事实补回去。
                    //   不补的后果（实测）：每帧都留 1 个未知格 → `sideFor` 直接放弃 →
                    //   永远「轮次未知，继续观察」→ 整局停住（停在 WAIT_TURN 干等 120 秒）。
                    //   而「读到空」（`""`/null）**不能**直接补：那个子可能真的从这个高亮格走开了
                    //   （2026-09-14 的事故：把空掉的格补回旧子 → 整帧看起来没变）→ 仍交给
                    //   fillKnownFacts 用唯一解释判断。
                    if (got === "?") {
                        result.chars[patch.index] = patch.char;
                        unknownCells = Math.max(0, unknownCells - 1);
                        driverState.stats.ownMovePatched += 1;
                        driverState.stats.decorFilled = (driverState.stats.decorFilled || 0) + 1;
                        driverActions.resetStuckStreak(patch);
                        keep.push(patch);
                        continue;
                    }
                    if (got !== "?") {
                        // ★ 本周期刚登记的已知事实（对方应手 / 我方落点 / 刚落子的格子）：
                        //   那一格里的子**在我方下一次确认之前不可能再动**（要吃它也得等我方落子），
                        //   所以「读到整格空」只可能是模型漏检，而不是「子走了」。
                        //   真机实测（K40，2026-09-19）：黑炮落在 (4,2)，模型一会儿读 c、一会儿整格读空，
                        //   就是**降阈值重拍（0.12）也读空**（不是阈值问题）→ 每帧差 1 个未知格 →
                        //   `sideFor` 数不齐子力 → 「轮次未知」空转 60 秒。
                        //   ⚠️ 界限用「我方出手序号」（ply），**不能**用确认周期：同一着法会重复确认好几次，
                        //   周期一涨记录立刻沦为「旧记录」（实测：补齐只生效了一帧）。
                        //   ⚠️ 我方一出手（ply 变），记录就不能再当事实 —— 那时子的去留真的可能变
                        //   （见 ④ 支的说明），仍然只敢补 '?'。
                        if (patch.ply != null && patch.ply === driverState.moveEpoch) {
                            // ★ 2026-09-25 加固（人工走棋回放抓到的死锁，真机同类）：还得看**轮到谁** ——
                            //   这一格刚登记过（ply 没变），但如果事实那枚子所属的一方**正好该走棋**，
                            //   「整格读空」就可能是他刚刚走开了（用户人工走棋时就是这样：我方刚落过子的
                            //   格子，下一手我方又把它走掉 —— 人不会等我们点击，ply 也就不变）。
                            //   补回去 ⇒ 观测里同一枚子两份（起点 + 新落点）⇒ 幻影逻辑把新落点清掉
                            //   ⇒ 着法永远推不出来 → 每帧死循环（回放日志：`清掉幻影（重复子）(0,9)R→空`）。
                            //   所以这种情形**不许补**：保持观测（空读），交给 fillKnownFacts 用
                            //   「上一张可靠盘面 + 唯一合法着法」解释 —— 那正是这条路的正确入口。
                            const factSide = String(patch.char) === String(patch.char).toUpperCase() ? "w" : "b";
                            const factCanMoveNow =
                                !driverState.lastReliable ||
                                !driverState.lastReliable.side ||
                                driverState.lastReliable.side === factSide;
                            if (!factCanMoveNow) {
                                result.chars[patch.index] = patch.char;
                                unknownCells = Math.max(0, unknownCells - 1);
                                driverState.stats.freshEmptyFilled += 1;
                                keep.push(patch);
                                continue;
                            }
                        }
                        // ★ 先拿「找色 + OCR」证据（用户提议：棋子定位用找色扫描）——
                        //   这一步能一句话说清「那格到底有没有子」，不再靠催游戏和猜：
                        //   · 找色说没墨迹 ⇒ 真的空了 ⇒ 事实过期，按观测算；
                        //   · 找色说有子（兵种也对得上）⇒ 就是模型漏检 ⇒ 直接补回去。
                        const evidence = driverActions.cellEvidence(patch.index);
                        if (evidence && evidence.hasPiece === false) {
                            driverState.stats.ocrRejected += 1;
                            driverActions.note(
                                "已知事实作废（找色证据是空格）：(" +
                                    (patch.index % 9) +
                                    "," +
                                    Math.floor(patch.index / 9) +
                                    ") 期望 " +
                                    patch.char +
                                    "，找色两边的墨迹密度都低于门槛 → 按观测算"
                            );
                            driverActions.resyncReliableAt(patch);
                            driverState.factInvalidated = true;
                            continue; // 不 keep ⇒ 记录消失，格子保持观测到的空
                        }
                        if (
                            evidence &&
                            evidence.hasPiece === true &&
                            patch.char &&
                            evidence.cls &&
                            String(evidence.cls).toLowerCase() === String(patch.char).toLowerCase()
                        ) {
                            driverState.stats.ocrConfirmed += 1;
                            result.chars[patch.index] = evidence.char || patch.char;
                            unknownCells = Math.max(0, unknownCells - 1);
                            driverActions.resetStuckStreak(patch);
                            keep.push(patch);
                            continue;
                        }
                        // ★ 单格 OCR 检查（2026-09-19，用户提的）：先拿证据，再决定补还是丢。
                        //   读到汉字 ⇒ 那格确实有子（顺便知道兵种与颜色）
                        //   读不到汉字 ⇒ 那格是空的 ⇒ 这条已知事实是错的，丢掉并同步改正可靠基准
                        if (typeof driverState.opts.ocrCell === "function") {
                            const looked = driverActions.ocrCheckPatch(patch);
                            if (looked.state === "match") {
                                result.chars[patch.index] = looked.char || patch.char;
                                unknownCells = Math.max(0, unknownCells - 1);
                                driverState.stats.ocrConfirmed += 1;
                                driverActions.resetStuckStreak(patch);
                                keep.push(patch);
                                continue;
                            }
                            if (looked.state === "empty" || looked.state === "other") {
                                driverState.stats.ocrRejected += 1;
                                const why =
                                    looked.state === "empty"
                                        ? "OCR 连续 " + (looked.seen || 2) + " 次都读不到字，那格是空的"
                                        : "OCR 读到 " + looked.glyph + "（与已知事实 " + patch.char + " 不符）";
                                driverActions.note(
                                    "已知事实作废：" +
                                        why +
                                        " → (" +
                                        (patch.index % 9) +
                                        "," +
                                        Math.floor(patch.index / 9) +
                                        ") 按观测算"
                                );
                                driverActions.resyncReliableAt(patch);
                                driverState.factInvalidated = true;
                                continue; // 不 keep ⇒ 记录消失，格子保持观测到的空
                            }
                        }
                        // ★ 先「催游戏」，催不动再「承认观测」（2026-09-19，K40 实拍定位后加的）：
                        //   真机现象不是模型伪影，而是**走子动画卡在中间**：黑炮 1,2→4,2 卡住，
                        //   棋子是隐形的、(1,2) 空了、(4,2) 只剩一个**闪烁的**落点光环 ⇒
                        //   那两格永远读不出子（一会儿 '?'、一会儿空）⇒ 每帧差一格未知 ⇒ 轮次未知空转。
                        //   天天象棋是**人机对战**（电脑应手是瞬间的），动画最多 1 秒 ——
                        //   一连 3 帧（≈10 秒）还读不出来就已经不正常了。
                        //   ⚠️ 只做**安全动作**（重新带前台 / 最近任务切出再切回），绝不点棋盘：
                        //   万一那格真的只是模型漏检，点下去就是走错棋。
                        //   ⚠️ 没有 `opts.nudge`（自检/离线）时**不做任何降级** —— 也无法区分
                        //   「画面卡住」与「模型漏检」（自检里那两条回归用例就是后者），保持原行为。
                        patch.emptyStreak = (patch.emptyStreak || 0) + 1;
                        if (
                            patch.emptyStreak >= driverState.cfg.stuckEmptyStreak &&
                            typeof driverState.opts.nudge === "function"
                        ) {
                            const at = driverState.nowMono();
                            const last = driverState.stuckNudgeAt[patch.index] || 0;
                            if (at - last >= driverState.cfg.stuckNudgeCooldownMs) {
                                driverState.stuckNudgeAt[patch.index] = at;
                                patch.nudges = (patch.nudges || 0) + 1;
                                driverState.stats.stuckNudges += 1;
                                driverActions.note(
                                    "疑似画面卡住：已知事实 (" +
                                        (patch.index % 9) +
                                        "," +
                                        Math.floor(patch.index / 9) +
                                        ") 已连续 " +
                                        patch.emptyStreak +
                                        " 帧读成空 —— 催游戏一下（第 " +
                                        patch.nudges +
                                        " 次）"
                                );
                                try {
                                    driverState.opts.nudge({
                                        index: patch.index,
                                        char: patch.char,
                                        streak: patch.emptyStreak,
                                        attempt: patch.nudges
                                    });
                                } catch (error) {
                                    driverState.stats.lastError = String(
                                        error && error.message ? error.message : error
                                    );
                                }
                                if (patch.nudges > driverState.cfg.stuckNudgeLimit) {
                                    /**
                                     * ★ 2026-09-20：丢记录之前先拿**全盘 OCR**复核一次。
                                     *
                                     * 真机现场（v5 局，⑷,7 那枚炮）：模型整格读成空、降阈值重拍也读空，
                                     * 但全盘 OCR 明明认出「炮」（日志：`全盘 OCR 改正 (4,7)空→C(OCR 炮)`）。
                                     * 旧行为到这里就把记录丢掉、还把可靠基准一起改正 ⇒ 盘面上真真
                                     * 丢了一枚子 ⇒ `sideFor` 数不齐 ⇒「轮次推不出来」→ 看门狗 →
                                     * 恢复空转（v5 实测 21 次）。既然 OCR 给了证据，就该**保留**这条事实，
                                     * 只把它当成「模型漏检」。
                                     */
                                    const ocrSaid = driverActions.ocrCellEvidence(patch.index, patch.char);
                                    if (
                                        ocrSaid &&
                                        patch.char &&
                                        String(ocrSaid).toLowerCase() === String(patch.char).toLowerCase()
                                    ) {
                                        driverState.stats.stuckFactsKeptByOcr =
                                            (driverState.stats.stuckFactsKeptByOcr || 0) + 1;
                                        driverActions.note(
                                            "催游戏 " +
                                                patch.nudges +
                                                " 次读不出来，但全盘 OCR 确认 (" +
                                                (patch.index % 9) +
                                                "," +
                                                Math.floor(patch.index / 9) +
                                                ") 就是 " +
                                                patch.char +
                                                " → **保留**这条已知事实（模型漏检，不是空格）"
                                        );
                                        driverActions.resetStuckStreak(patch);
                                        patch.nudges = 0;
                                        result.chars[patch.index] = patch.char;
                                        unknownCells = Math.max(0, unknownCells - 1);
                                        driverState.evidenceIndexes.push(patch.index); // OCR 确认过的格：体检不回滚
                                        keep.push(patch);
                                        continue;
                                    }
                                    // 催了这么多次仍然读不出来 ⇒ 这条记录无法用画面证实，
                                    // 就按**观测**算（那一格当作空），让轮次推断能继续。
                                    driverState.stats.stuckFactsDropped += 1;
                                    driverActions.note(
                                        "催游戏 " +
                                            patch.nudges +
                                            " 次仍然读不出来 → 放弃这条已知事实，" +
                                            "按观测算：(" +
                                            (patch.index % 9) +
                                            "," +
                                            Math.floor(patch.index / 9) +
                                            ") 不再补 " +
                                            patch.char
                                    );
                                    // ⚠️ 光丢补齐表不够：**可靠基准**是唯一权威，它带着这条无法证实的记录的话，
                                    //   下一帧就会变成「基准里有子、观测里没子，没有任何合法着法解释得了」
                                    //   → 永远 RECOVERING（真机实测：未知格已归零，但停在
                                    //   「既没有合法单步也没有一步+应手能解释」）。
                                    driverActions.resyncReliableAt(patch);
                                    driverState.factInvalidated = true;
                                    continue; // 不 keep ⇒ 记录消失，格子保持观测到的空
                                }
                            }
                        }
                        result.chars[patch.index] = "?";
                        unknownCells += 1;
                        // 已知事实（票据 + 棋规）说这格有子，模型却整格读成空 ⇒ 可疑。
                        // 不能直接补（2026-09-14：子真走了会把局面改成假的），但值得**降阈值重拍一次**。
                        suspects.push(patch);
                    }
                    keep.push(patch);
                    continue;
                }
                if (got === patch.char) {
                    // ② 自己能认出来了 → 保留（同一手内下一帧可能又被盖住）
                    driverActions.resetStuckStreak(patch);
                    keep.push(patch);
                    continue;
                }
                // ③ 读成了**别的子**：分两种，都要按已知事实补齐，但判据不同 ——
                //    · **同字异色**（C↔c、P↔p…）= 同一个兵种被读成红黑反了。这台机器上实测会**反复抖**
                //      （2026-09-14 r1.log：`(1,7)?→C` 这类行反复出现）。⚠️ 以前这种情况走 ④ 丢掉记录，
                //      于是**一帧抖动就足够把记录丢掉**，之后同色读错的帧再没人补 →
                //      「轮次推不出来（UNKNOWN）」→ 看门狗 → 整轮停在那儿。
                //      离线回放（diag-patch-life2.js）已复现：一帧 红黑读反 就把 (2,3) 的记录丢掉。
                //    · **同色不同字**（C↔P）= 兵种被游戏装饰读错（真机实测：我方炮吃卒落在卒位被读成红兵）。
                //    异色**且**不同字（C↔n）才是「真的被吃/走开了」→ 必须让它变，
                //    否则会把吃子藏起来，后面算棋就按错盘面走。
                const sameGlyph =
                    got &&
                    patch.char &&
                    got.length === 1 &&
                    patch.char.length === 1 &&
                    got.toLowerCase() === patch.char.toLowerCase();
                const sameColor =
                    got && patch.char && (got === got.toUpperCase()) === (patch.char === patch.char.toUpperCase());
                if (got && got !== "" && (sameGlyph || sameColor)) {
                    if (sameGlyph && !sameColor) {
                        /**
                         * ★ 同字异色是最危险的一种：可能是「模型红黑读反」，也可能是
                         * **对方同兵种反吃**（真机 2026-09-20 卡死两分钟的真因）。
                         * 拿**像素证据**来定（用户提议的「找色判阵营」）：
                         *   · 证据说阵营与已知事实**相反** ⇒ 事实过期（真被吃了）→ 作废、按观测算；
                         *   · 证据支持事实 / 拿不到证据 ⇒ 照旧按事实补齐。
                         */
                        const liveSide = safety.freshSide(result, patch.index);
                        const hint = liveSide ? {side:liveSide} : driverActions.cellSideEvidence(patch);
                        const wantSide = patch.char === String(patch.char).toUpperCase() ? "RED" : "BLACK";
                        if (hint && hint.side !== wantSide) {
                            driverState.stats.sameGlyphFalsified = (driverState.stats.sameGlyphFalsified || 0) + 1;
                            driverActions.note(
                                "已知事实作废（找色证据）：(" +
                                    (patch.index % 9) +
                                    "," +
                                    Math.floor(patch.index / 9) +
                                    ") 期望 " +
                                    patch.char +
                                    "（" +
                                    (wantSide === "RED" ? "红" : "黑") +
                                    "），像素说是" +
                                    (hint.side === "RED" ? "红" : "黑") +
                                    (hint.cls ? "（OCR 读到 " + hint.cls + "）" : "") +
                                    " → 按观测算（多半是对方同兵种反吃）"
                            );
                            if (replyCapture && replyCapture.index === patch.index && replyCapture.char === got) {
                                driverState.stats.replyCaptureFactsExpired = (driverState.stats.replyCaptureFactsExpired || 0) + 1;
                                driverActions.note("应手吃子候选 " + replyCapture.iccs +
                                    "：撤销旧落点事实，保留前盘面棋谱，等待跨帧确认");
                            } else {
                                driverActions.resyncReliableAt(patch);
                                driverState.factInvalidated = true;
                            }
                            continue; // 不 keep ⇒ 记录消失，保持观测到的那个字
                        }
                    }
                    result.chars[patch.index] = patch.char;
                    patchedNow = patch;
                    driverState.stats.ownMovePatched += 1;
                    driverActions.resetStuckStreak(patch);
                    if (sameGlyph && !sameColor) {
                        driverState.stats.sameGlyphPatched += 1; // 计数：真出现「同兵种吃子被掩盖」时能在日志里看见
                    }
                    keep.push(patch);
                    continue;
                }
                // ★★ 异色 **且** 异字（我方炮 C 被读成黑卒 p 这类）：默认就是「真被吃了 / 走开了」（④）。
                //    真机上还有另一种**每局都出现**的常态（now23 铁证，K40）：**落点被游戏画花**
                //    ⇒ 模型把我方的子读成敌方的**另一个兵种** ⇒ 走 ④ 丢掉记录 ⇒ 之后每帧都读成 p
                //    也没人管 ⇒ 差分「变化 0 格」永远不动 ⇒ 每 12 秒白烧一轮 `轮次推不出来 → 恢复 → 重建基准`。
                //    分这个歧义用**第三票：像素（找色）** —— 同帧 OCR 也说那格是 C，只有模型/融合读错：
                //      · 证据站**已知事实**这边 ⇒ 这一帧读错，保住事实（`pixelKeptFacts` 计数会出现在日志）；
                //      · 证据站观测这边 / 拿不到证据 ⇒ 照旧 ④ 丢掉（对方同兵种/异兵种反吃时正是这种）。
                if (got && got !== "" && !sameGlyph && !sameColor) {
                    const liveSide = safety.freshSide(result, patch.index);
                        const hint = liveSide ? {side:liveSide} : driverActions.cellSideEvidence(patch);
                    const factSide = patch.char === String(patch.char).toUpperCase() ? "RED" : "BLACK";
                    if (hint && hint.side === factSide) {
                        result.chars[patch.index] = patch.char;
                        driverState.stats.pixelKeptFacts = (driverState.stats.pixelKeptFacts || 0) + 1;
                        driverActions.resetStuckStreak(patch);
                        driverActions.note(
                            "按像素证据保住已知事实：(" +
                                (patch.index % 9) +
                                "," +
                                Math.floor(patch.index / 9) +
                                ") 读成 " +
                                got +
                                "，但找色说是" +
                                (factSide === "RED" ? "红" : "黑") +
                                "（期望 " +
                                patch.char +
                                "）→ 这一帧读错"
                        );
                        keep.push(patch);
                        continue;
                    }
                }
                // ④ 变空 / 换成异色且不同字 → 真的被吃或走开了，不再保留
            }
            driverState.knownFacts.replace(keep);
        }
        // 还有未知格 → 试试用「已知事实」补齐（唯一解释；见 fillKnownFacts 的说明）
        if (unknownCells > 0 && result.chars) {
            const filled = driverActions.fillKnownFacts(result.chars,factBaseline);
            // 只有「补完真的没未知格」才算成功：宁可老老实实报未知格，也不要假装补齐（见 fillKnownFacts 的注释）
            if (filled && filled.board && filled.board.indexOf("?") < 0) {
                // ★ F1「与上一张可靠盘面一致」= 这些格是**按基准填回**的 —— 除了模型/找色读不出来，
                //   最典型的原因就是被游戏「上一步」标记压住（真机 K40 2026-09-21 死区：对手的起点/落
                //   点都被压 ⇒ 填回原样 ⇒ 差分看起来「没动」）。登记给 sideFor 的「被盖住的应手」用。
                if (String(filled.how).indexOf("与上一张可靠盘面一致") === 0) {
                    for (let i = 0; i < 90; i++) {
                        if (result.chars[i] === "?" && filled.board[i] !== "?") {
                            driverState.coveredFilledIndexes.push(i);
                        }
                    }
                }
                result.chars = filled.board;
                unknownCells = 0;
                driverState.stats.knownFactFills += 1;
                if (driverState.lastFillText !== filled.how) {
                    driverState.lastFillText = filled.how;
                    driverActions.note("未知格按已知事实补齐：" + filled.how);
                }
            }
        }
        // ★ 还有未知格 → 直接拿「OCR + 找色」证据补（见 evidenceCharFor）。
        //   这是「定位一次 + 找色 + OCR」那条路在现链路里的落地：不看模型检测框，
        //   也不吃红黑读反；判不出来就留着未知（后面还有重拍/恢复兵底）。
        if (unknownCells > 0 && result.chars && typeof driverState.opts.ocrCell === "function") {
            let byEvidence = 0;
            let byReliable = 0;
            const detail = [];
            const detail2 = [];
            /** 被「兵种上限」驳回的兜底填充（见下方硬校验；日志用） */
            const rejectedFill = [];
            /** 驳回多帧后改判「空」的格子（日志用） */
            const capEmptied = [];
            // 轮次已经被未知格挡住 ⇒ 这一格的值不能无限等：把兜底门槛降到 2 帧（见 unknownSideStreak）
            const stallFill = driverState.unknownSideStreak >= 1;
            const fillNeeded = stallFill
                ? Math.min(driverState.cfg.unknownFillFromReliableFrames, 2)
                : driverState.cfg.unknownFillFromReliableFrames;
            for (let i = 0; i < 90; i++) {
                if (result.chars[i] !== "?") {
                    driverState.unknownStreak[i] = 0;
                    driverState.capRejectedStreak[i] = 0; // 读出来了 ⇒ 驳回计数重来
                    if (driverState.stallFilledUntil[i]) {
                        driverState.stallFilledUntil[i] = 0; // 这一格读出来了 ⇒ 放下粘住，采用读数
                    }
                    continue;
                }
                driverState.unknownStreak[i] = (driverState.unknownStreak[i] || 0) + 1;
                const ch = driverActions.evidenceCharFor(i);
                if (ch) {
                    result.chars[i] = ch;
                    unknownCells = Math.max(0, unknownCells - 1);
                    byEvidence += 1;
                    driverState.evidenceIndexes.push(i); // 证据格：体检不回滚
                    driverState.unknownStreak[i] = 0;
                    if (detail.length < 6) {
                        detail.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + ch);
                    }
                    continue;
                }
                // ★ 顽固未知（OCR+找色也没证据）→ 按上一张可靠盘面兜（未知 = 没变）。
                //   不兜的话就是「一格看不清 ⇒ 整局不动」（真机 v8 实测：FEN 生不出来、每轮抛异常）。
                if (
                    (driverState.unknownStreak[i] >= fillNeeded ||
                        (driverState.stallFilledUntil[i] || 0) > driverState.nowMono()) &&
                    driverState.lastReliable &&
                    driverState.lastReliable.board
                ) {
                    const fallback = driverState.lastReliable.board[i];
                    // ★ 硬校验：兜的值不许把兵种顶超上限（棋子不会分身）——真机 2026-09-21 now60：
                    //   (7,2) 起点格被「上一步星光标记」压住 20s+ 读不出 ⇒ 兜成旧值 c ⇒ 黑炮变 3 枚
                    //   （不可能盘面）⇒ 幻影去清真落点 (4,2)c、又把它收编 ⇒ 轮次永远停在「轮到对方」。
                    //   填出「不可能」比留着「未知」更坏：未知有看门狗/重锚兜底，错误盘面把推理带沟里。
                    if (
                        typeof fallback === "string" &&
                        fallback !== "?" &&
                        driverActions.countGlyphChars(result.chars, fallback) >= driverActions.glyphPieceCap(fallback)
                    ) {
                        /**
                         * ★★ 驳回之后不能永远堵着（2026-09-21 10:33 K40 死锁，用户报「又卡住找不到格子」）：
                         *   真机现场 (5,7)：我方炮 f2i2 走后，起点格被游戏「上一步」标记压住读不出来；
                         *   兜底想按旧基准填 C，但盘面里红炮已经两枚（另一枚就是它自己走到 (8,7) 那里）
                         *   ⇒ 永远驳回 ⇒ 那一格永远 '?' ⇒ 轮次永远未知 ⇒ 12 分钟没出手。
                         *   判据其实在手里：**基准值放不下 = 基准那一格已被证伪**（棋子不会分身），
                         *   那枚子只能已经离开 ⇒ 连续 `capRejectEmptyFrames` 帧都被驳回就按空处理。
                         *   单帧误读不动手；下一帧真读出字会立刻纠正（与其它兜底同一条路）。
                         */
                        driverState.capRejectedStreak[i] = (driverState.capRejectedStreak[i] || 0) + 1;
                        if (driverState.capRejectedStreak[i] >= (driverState.cfg.capRejectEmptyFrames || 3)) {
                            result.chars[i] = null;
                            unknownCells = Math.max(0, unknownCells - 1);
                            driverState.unknownStreak[i] = 0;
                            driverState.stallFilledUntil[i] = driverState.nowMono() + driverState.cfg.stallFillHoldMs;
                            driverState.coveredFilledIndexes.push(i);
                            // 注意：**不计入 unknownFilledFromReliable**（那个账是「按基准填回」的）——
                            // 这里是按「基准被证伪」推出来的空格，单独计数。
                            driverState.stats.unknownFillEmptied = (driverState.stats.unknownFillEmptied || 0) + 1;
                            if (capEmptied.length < 4) {
                                capEmptied.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")");
                            }
                        } else if (rejectedFill.length < 4) {
                            rejectedFill.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")=" + fallback);
                        }
                        continue;
                    }
                    driverState.capRejectedStreak[i] = 0;
                    result.chars[i] = fallback === undefined ? null : fallback;
                    unknownCells = Math.max(0, unknownCells - 1);
                    byReliable += 1;
                    driverState.unknownStreak[i] = 0;
                    driverState.stallFilledUntil[i] = driverState.nowMono() + driverState.cfg.stallFillHoldMs;
                    driverState.coveredFilledIndexes.push(i);
                    if (detail2.length < 6) {
                        detail2.push(
                            "(" +
                                (i % 9) +
                                "," +
                                Math.floor(i / 9) +
                                ")=" +
                                (fallback === undefined || fallback === null ? "空" : fallback)
                        );
                    }
                }
            }
            if (byEvidence > 0) {
                driverState.stats.evidenceFilled = (driverState.stats.evidenceFilled || 0) + byEvidence;
                driverActions.note("未知格按 OCR+找色证据补齐 " + byEvidence + " 格：" + detail.join(" "));
            }
            if (byReliable > 0) {
                driverState.stats.unknownFilledFromReliable =
                    (driverState.stats.unknownFilledFromReliable || 0) + byReliable;
                driverActions.note(
                    "顽固未知 " +
                        byReliable +
                        " 格（OCR+找色也没证据）→ 按上一张可靠盘面填（未知=没变）：" +
                        detail2.join(" ") +
                        "（下一帧读出来会立刻纠正）" +
                        (stallFill ? "｜轮次已被未知格挡住，兜底门槛提前到 2 帧" : "")
                );
            }
            if (rejectedFill.length > 0) {
                driverState.stats.unknownFillRejected =
                    (driverState.stats.unknownFillRejected || 0) + rejectedFill.length;
                driverActions.note(
                    "兜底被兵种上限驳回 " +
                        rejectedFill.length +
                        " 格（填了就是不可能盘面，留 '?' 等重读）：" +
                        rejectedFill.join(" ")
                );
            }
            if (capEmptied.length > 0) {
                driverActions.note(
                    "兜底连续 " +
                        (driverState.cfg.capRejectEmptyFrames || 3) +
                        " 帧被兵种上限驳回 ⇒ 判定那枚子已离开（基准过时），按空处理：" +
                        capEmptied.join(" ") +
                        "（下一帧读出字会立刻纠正）"
                );
            }
        }
        // ★ 全盘 OCR 改正「同色不同兵种」的误读（见 applyOcrBoardFixes）。
        //   放在补齐表之后、`sideFor` 之前：让轮次推断拿到的是**改对后**的盘面。
        if (result.chars && typeof driverState.opts.ocrBoard === "function") {
            const fixed = driverActions.applyOcrBoardFixes(result.chars, result);
            if (fixed > 0) {
                // Historical repairs cannot reverse decisive colors from this captured frame.
        for (let i = 0; rawChars && result.chars && i < 90; i++) {
            const side = safety.freshSide(result, i), raw = rawChars[i], got = result.chars[i];
            if (side && raw && raw !== "?" && got && got !== "?" &&
                (raw === raw.toUpperCase()) === (side === "RED") &&
                (got === got.toUpperCase()) !== (side === "RED")) {
                result.chars[i] = raw;
                driverActions.note("当前像素阵营优先：格 " + i + " " + got + "→" + raw);
            }
        }
        unknownCells = driverActions.countUnknownChars(result.chars);
                patchedNow = patchedNow || true;
            }
        }
        // ★ 单格重拍（见 reprobeSuspects）：唯一解释补不上，但可疑格是「已知事实说有子」的，
        //   值得换一帧降阈值再看一眼 —— 修的是「模型整格漏检」而不是「补已知事实」。
        if (unknownCells > 0 && suspects.length > 0 && result.chars) {
            if (driverActions.reprobeSuspects(suspects, result) > 0) {
                let stillUnknown = 0;
                for (let i = 0; i < 90; i++) {
                    if (result.chars[i] === "?") {
                        stillUnknown += 1;
                    }
                }
                unknownCells = stillUnknown;
            }
        }
        // 颜色翻转纠正：游戏给刚落的子/被威胁的子加装饰（高亮环、命中标记），实测会让模型
        // 把**同一个兵种的颜色判反**（例：落点上的红炮被读成红兵/黑卒）。
        //
        // ⚠️ 只改「观测字符与期望字符是同一个字母的不同大小写」的格子，而且**只在确认阶段**
        // 用（options.expect = 票据的「预期落子后局面」）：
        //   · 为什么不看上一张可靠盘面？因为「我的红兵吃掉对方的黑卒」也会表现为同一格
        //     P↔p，拿旧盘面去纠就会**把真实的吃子改掉**；
        //   · 用票据的预期盘面就没有这个问题：纠正之后整盘必须与预期**完全一致**，
        //     否则验证器照样判 UNCHANGED/UNEXPECTED —— 风险由验证器兜住，不是无条件采信。
        if (options && options.expect && result.chars) {
            const expectBoard = options.expect;
            let flips = 0;
            for (let i = 0; i < 90; i++) {
                const got = result.chars[i];
                const want = expectBoard[i];
                if (typeof got !== "string" || typeof want !== "string") {
                    continue;
                }
                if (got !== want && got.toLowerCase() === want.toLowerCase() && /^[A-Za-z]$/.test(got) &&
                    !safety.freshSide(result, i)) {
                    result.chars[i] = want;
                    flips += 1;
                }
            }
            if (flips > 0) {
                driverState.stats.colorFlips += flips;
            }
            // ★ 按「棋子怎么走」对账（2026-09-19）：用**棋规**判断哪些格子必定是读错了，直接改正。
            //   跟「容差 N 格」那套的区别：这里的每一条都是**一步之内不可能发生的事**，
            //   所以是「按规则推出真相」，不是「容忍差异」。
            //   ① 颜色不可能变：同字异色（P↔p）只能是模型红黑读反 ⇒ 按期望改回（上面那段已做）
            //   ② **落点上一定是我方走过去的那个子**（棋规唯一）：观测在落点读到同色但别的兵种，
            //      （例：我方炮吃卒落在兵位，被游戏装饰读成红兵）⇒ 按期望改回
            //   ③ **直线移动的途经格**内容由棋规唯一决定（炮吃子 ⇒ 中间恰有一个炮架；否则全空）：
            //      期望为空的途经格观测到任何东西 ⇒ 按期望置空（游戏会把走子轨迹画在中途）
            //   其余格子一律不动 —— 那些才是「真的变了」，交给验证器判。
            if (options.ticket && verifier.pathIndexesBetween) {
                let dest = -1;
                let path = null;
                try {
                    dest = verifier.destinationIndex(options.ticket);
                } catch (error) {
                    dest = -1;
                }
                try {
                    path = verifier.pathIndexesBetween(options.ticket);
                } catch (error) {
                    path = null;
                }
                let byRule = 0;
                const notes = [];
                for (let i = 0; i < 90; i++) {
                    const got = result.chars[i];
                    const want = expectBoard[i];
                    if (got === want) {
                        continue;
                    }
                    const sameColor =
                        typeof got === "string" &&
                        typeof want === "string" &&
                        got &&
                        want &&
                        (got === got.toUpperCase()) === (want === want.toUpperCase());
                    // ② 落点：棋规决定那里必是我方走过去的那个子
                    if (i === dest && want && typeof got === "string" && got && sameColor) {
                        result.chars[i] = want;
                        byRule += 1;
                        notes.push("落点(" + (i % 9) + "," + Math.floor(i / 9) + ")" + got + "→" + want);
                        continue;
                    }
                    // ③ 途经格：期望为空而观测有东西（轨迹/装饰）
                    if (path && path[i] != null && (want === null || want === "") && got) {
                        result.chars[i] = null;
                        byRule += 1;
                        notes.push("途经(" + (i % 9) + "," + Math.floor(i / 9) + ")" + got + "→空");
                    }
                }
                if (byRule > 0) {
                    driverState.stats.ruleFixes = (driverState.stats.ruleFixes || 0) + byRule;
                    driverActions.note(
                        "按棋规改正 " + byRule + " 格（" + notes.join("；") + "）——「棋子怎么走」决定的，不是容差"
                    );
                }
                // ④ 重复子（幻影）：见 clearPhantoms。确认阶段的参照 = 票据的预期局面（最可信）
                driverActions.clearPhantoms(result.chars, expectBoard, "与票据的预期局面比，多出来的那份");
            }
        }
        // ★ 重复子（幻影）在这里再清一遍，参照 = **上一张可靠基准**：
        //   确认阶段已经用票据的预期盘面对过账，可**推断路径**（轮次）同样会被幻影咬到
        //   （真机 18:21：`诊断观测 rrbakab1r` vs `诊断基准 r1bakab1r` ⇒ 轮次推不出来 → 恢复）。
        //   不变量是硬的：一局里任何一方的子数**只会减少或不变**（吃子少一枚，象棋没有升变）。
        if (driverState.lastReliable && driverState.lastReliable.board) {
            driverActions.clearPhantoms(
                result.chars,
                driverState.lastReliable.board,
                "与上一张可靠基准比，多出来的那份"
            );
        }
        // lastBoard 要在**补齐/纠色之后**记：它会被「现在轮到我」拿去当基准，
        // 存的必须是「目前最可信的那张盘」（带 '?' 的原始观测会让基准不干净）。
        driverState.lastBoard = result.chars ? result.chars.slice() : null;
        driverState.lastRawChars = rawChars;
        // 原始读数与修正后盘面不一致时记一笔（限频）：审核、复现都靠它
        if (rawChars && result.chars && rawChars.join("") !== result.chars.join("")) {
            let diffCount = 0;
            const changed = [];
            for (let i = 0; i < 90; i++) {
                if (rawChars[i] !== result.chars[i]) {
                    diffCount += 1;
                    if (changed.length < 6) {
                        changed.push(
                            "(" +
                                (i % 9) +
                                "," +
                                Math.floor(i / 9) +
                                ")" +
                                (rawChars[i] || "空") +
                                "→" +
                                (result.chars[i] || "空")
                        );
                    }
                }
            }
            driverState.stats.rawCorrected += 1;
            const text =
                "原始读数被修正 " + diffCount + " 格：" + changed.join(" ") + (diffCount > changed.length ? " …" : "");
            if (driverState.lastRawCorrectedText !== text) {
                driverState.lastRawCorrectedText = text;
                driverActions.note(text);
            }
        } else {
            driverState.lastRawCorrectedText = "";
        }
        // ★★ 兵种上限体检（2026-09-25 真机 16:06 那局「黑 c ×3」事故；实现见 piece-cap-audit.js）：
        //   对方炮 g8→g4 吃我方炮；「起点被上一步标记盖住」+「落点被画花」两条补齐路径各自把同一个子
        //   写了一处 ⇒ 黑炮 3 枚（不可能盘面）⇒ 引擎侧拒算（"黑c 有 3 个"）——但那是**算到一半才炸**：
        //   算棋失败 → RECOVERING → 基准重建（着法历史从「完整」掉成「部分」，长将/长捉判罚全废）。
        //   棋子不会分身：超限的兵种里，**相对原始读数被改写过的**那些格必是补齐写出来的假子 ⇒
        //   回滚成原始读数；若与原始读数一致（纯幻影）则强制全盘重读。宁可多等一帧，不把不可能盘面写进基准。
        if (result.chars) {
            const rolled = pieceCapAudit.auditPieceCaps(result.chars, rawChars, {
                keepIndexes: driverState.evidenceIndexes
            });
            if (rolled.length > 0) {
                driverState.lastBoard = result.chars.slice(); // 体检后的盘面才是「目前最可信」
                for (let r = 0; r < rolled.length; r++) {
                    const one = rolled[r];
                    driverState.stats.pieceCapRollbacks = (driverState.stats.pieceCapRollbacks || 0) + 1;
                    driverActions.note(
                        "⚠ 兵种上限体检：" +
                            one.char +
                            " 超上限 " +
                            one.cap +
                            (one.reverted > 0
                                ? "（补齐把同一个子写了多份）⇒ 回滚 " +
                                  one.reverted +
                                  " 格到原始读数：" +
                                  one.cells.join(" ")
                                : "，且全部与原始读数一致（视觉幻影）") +
                            (one.stillOver ? "｜仍超限 → 强制全盘重读" : "")
                    );
                    if (one.stillOver) {
                        driverState.forceFreshReadOnce = true;
                    }
                }
            }
        }
        // ★ 一致性收口（2026-09-21 16:0x 真机事故）：前面各种补齐路径各自维护 unknownCells 计数，
        //   任何一条路径少写一格，就会留下「计数说 0、盘面里还留着 '?'」的帧 ⇒ 下游 buildFen 抛
        //   「存在未知格，不能生成 FEN」⇒ 每轮被当异常跳过（面板上只看到轮次未知空转）。
        //   以**盘面本身**为准归一化：未知格 = chars 里 '?' 的个数（唯一事实来源）。
        // Historical repairs cannot reverse decisive colors from this captured frame.
        for (let i = 0; rawChars && result.chars && i < 90; i++) {
            const side = safety.freshSide(result, i), raw = rawChars[i], got = result.chars[i];
            if (side && raw && raw !== "?" && got && got !== "?" &&
                (raw === raw.toUpperCase()) === (side === "RED") &&
                (got === got.toUpperCase()) !== (side === "RED")) {
                result.chars[i] = raw;
                driverActions.note("当前像素阵营优先：格 " + i + " " + got + "→" + raw);
            }
        }
        unknownCells = driverActions.countUnknownChars(result.chars);
        // 缺将帅已在原始观测入口拦截；不得向空格凭棋规补帅。
        if (unknownCells < 0) {
            unknownCells = 0;
        }
        return {
            ok: true,
            chars: result.chars,
            rawChars: rawChars,
            unknownCells: unknownCells,
            patched: patchedNow,
            coveredFills: driverState.coveredFilledIndexes.length,
            conflicts: result.stats && result.stats.conflicts ? result.stats.conflicts : 0,
            // 占用图 + 变化格（2026-09-21 对方应手快路用）：面板 diff 链路每帧都带；全盘/旧链路为 undefined
            occupancy: result.occupancy,
            changed: result.changed,
            layout: result.layout,
            result: result
        };
    }

    return { boardText: boardText, observeOnce: observeOnce };
}

module.exports = { create: create };
