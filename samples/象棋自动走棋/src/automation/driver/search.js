// @engine quickjs
"use strict";

/** 引擎搜索与预判结果：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const fenModule = require("../../chess/fen.js");
const legal = require("../../chess/legal-moves.js");
const winSeekModule = require("../win-seek.js");
const searchBudget = require("../search-budget.js");
const prethinkModule = require("../prethink.js");
const lineMaterial = require("../line-material.js");

function preferenceMultiPv(repeats, swaps, board, side, cfg) {
    const count = (repeats ? repeats.length : 0) + (swaps ? swaps.length : 0);
    return count || lineMaterial.shouldCompare(board, side, cfg.winSeek === true && cfg.winSeekKeepMaterial !== false) ? 5 : 0;
}

function create(driverState, driverActions) {
    // Repeated positions may be stable because old facts keep restoring a bad board.
    // Verify each such board once per session without those facts before searching again.
    let auditedRepeats = Object.create(null);
    let auditSession = null;
    function auditRepeatedBoard(effect) {
        if (driverState.cfg.repeatBoardAudit !== true || !effect.board ||
            driverState.winSeekSeen.count(effect.board, effect.side) < 3) return true;
        const session = driverState.machine.versions().sessionId;
        if (auditSession !== session) { auditSession = session; auditedRepeats = Object.create(null); }
        const key = winSeekModule.keyOf(effect.board, effect.side);
        if (auditedRepeats[key]) return true;
        const observed = driverActions.observeOnce({ independentRead: true });
        const diffs = observed && observed.ok && observed.chars
            ? legal.diffBoards(effect.board, observed.chars) : [];
        if (!observed || !observed.ok || !observed.chars || observed.chars.length !== 90 || observed.unknownCells !== 0 ||
            observed.conflicts > 0 || diffs.length) {
            const detail = diffs.length ? diffs.slice(0, 6).map(function (cell) {
                return "(" + cell.col + "," + cell.row + ")" +
                    (cell.before || "空") + "→" + (cell.after || "空");
            }).join(" ") : (observed && observed.reason || "重读存在未知格或冲突");
            const reason = "重复盘面核对未通过：" + detail + "；已暂停，请核对棋盘后重新识别";
            driverActions.note(reason);
            driverActions.pump(driverState.machine.dispatch({ type: "PAUSE", reason: reason }));
            return false;
        }
        auditedRepeats[key] = true;
        driverActions.note("重复盘面核对通过：两张全盘新帧与算棋盘面一致（未使用旧棋子补齐），保留已确认棋谱");
        return true;
    }

    function compute(effect) {
        if (!auditRepeatedBoard(effect)) return null;
        driverState.prethink.poll();
        if (driverState.prethink.busy()) {
            driverState.prethinkSeed = null;
            driverState.prethink.cancel();
            driverState.deferredCompute = {effect:effect, at:driverState.nowMono()};
            return null;
        }
        const actual = driverActions.verifiedHistoryFor(effect);
        if (driverState.prethink.take(prethinkModule.identity(effect.board, effect.side,
            actual.positionCommand, driverState.machine.versions().sessionId, driverState.cfg.pikafish))) {
            driverActions.note("手机预思考命中：实际盘面和棋谱一致，沿用引擎缓存继续正式搜索");
            driverState.stats.prethinkHits = (driverState.stats.prethinkHits || 0) + 1;
        }
        driverState.prethinkSeed = null;
        // 算棋放到独立引擎线程（worker）：主线程不再被 ~1.2s 的搜索占住，
        // 悬浮窗触摸/心跳照常。结果由 pollCompute() 在后续 tick 里非阻塞收回来。
        /**
         * ★★ 盘面自检（2026-09-21 真机：v5r 漏读一枚黑士 → 「假双将」空转 7 分钟）：
         *   真实对局里「轮到走棋的一方 0 个合法着法」= 被将死/困毙 —— 对局**早就结算了**。
         *   还坐在这局里算出 0 着，只可能是盘面读错（现场：d7 黑士漏检 ⇒ 黑炮的「隔一子打帅」
         *   线没被挡住 ⇒ 假双将 ⇒ pikafish `bestmove (none)` 每 12s 空转，面板「算棋失败」刷屏）。
         *   处置：不出手、不喂引擎 —— 强制下一帧全盘新鲜重读 + 按观测量重建基准；
         *   连续多次仍 0 着 ⇒ 提示「盘面持续矛盾：可能对局已结束或识别异常」，让它显式可见。
         */
        let legalCount = -1;
        try {
            legalCount = effect && effect.board ? legal.allLegalMoves(effect.board, effect.side).length : -1;
        } catch (error) {
            legalCount = -1;
        }
        if (legalCount === 0) {
            driverState.noMoveStreak += 1;
            driverState.stats.impossibleNoMoves = (driverState.stats.impossibleNoMoves || 0) + 1;
            driverState.forceFreshReadOnce = true;
            driverState.needFreshBaseline = true;
            driverActions.note(
                "盘面自检不通过：轮到" +
                    (effect.side === "w" ? "红" : "黑") +
                    "方却 0 合法着法" +
                    "（真对局早该结束了）⇒ 判为识别错误：不出手，强制全盘新鲜重读核对（第 " +
                    driverState.noMoveStreak +
                    " 次）"
            );
            if (driverState.noMoveStreak === 3) {
                driverActions.note(
                    "⚠ 盘面自检连续 3 次不通过：对局可能已结束（被将死/困毙，看游戏结算），" +
                        "或识别持续异常（可用面板「绘制」+ 截图人工核对棋盘）"
                );
            }
            return driverActions.pump(
                driverState.machine.dispatch({
                    type: "JOB_RESULT",
                    jobId: effect.jobId,
                    ok: false,
                    reason: "盘面自检不通过：轮到的一方 0 合法着法（识别错误，强制重读）"
                })
            );
        }
        driverState.noMoveStreak = 0;
        driverState.lastComputeObs = driverState.stats.observations;
        // 本局面要排除的着法（游戏实际拒绝过的）：worker 与同步两条路都要带上
        const excludes = driverActions.excludedIccsFor(effect.board, effect.side);
        const verifiedHistory = driverActions.verifiedHistoryFor(effect);
        const budget = searchBudget.plan(effect.board, effect.movetimeMs, driverState.cfg.searchDepth,
            { adaptiveSearch: driverState.cfg.adaptiveSearch, side: effect.side });
        if (budget.adaptive) driverActions.note("自动预算：" + budget.phase + "，首搜 " + budget.movetimeMs +
            "ms，含关键步复核上限 " + budget.maxTotalMs + "ms");
        if (driverActions.useWorkerSearch()) {
            dropPredict(null); // worker 单任务：算棋起跑前先让预判让位（正常情况它早跑完了）
            const started = driverState.nowMono();
            // ★ 「避开兑子」：算兑子候选（**不硬排除**；有的话算棋开多路，给 finishCompute 挑替代）
            const workerSwapList = driverActions.winSeekSwapListFor(effect.board, effect.side);
            const workerRepeatList = driverActions.winSeekRepeatListFor(effect.board, effect.side);
            if (
                driverState.workerSearch.start(effect.fen || effect.board, {
                    side: effect.side,
                    movetime: budget.movetimeMs,
                    depth: driverState.cfg.searchDepth > 0 ? driverState.cfg.searchDepth : null,
                    timeoutMs: driverState.cfg.searchDepth > 0 ? driverState.cfg.depthTimeoutMs : null,
                    excludeIccs: excludes,
                    positionCommand: verifiedHistory.positionCommand,
                    historyStatus: verifiedHistory.historyStatus,
                    engine: driverState.cfg.engineKey,
                    pikafish: driverState.cfg.pikafish,
                    criticalReview: driverState.cfg.criticalReview,
                    maxTotalMs: budget.maxTotalMs,
                    previousScore: driverState.lastThinkScore,
                    openingLearning: driverState.cfg.openingLearning,
                    // ★ 开局库步数闸门（见 cfg.bookOwnMoves）：本步用不用库 + 本局我方已走几手
                    bookOwnMoves: driverState.cfg.bookOwnMoves,
                    ownMovesMade: driverState.ownMoveCount,
                    // ★ 开局库候选随机（见 cfg.bookRandom）：false 才总用主着法
                    bookRandom: driverState.cfg.bookRandom,
                    // ★ 「避开兑子」（不硬排除）：有兑子候选时开多路，首选是兑子就在候选里挑替代
                    multiPv: preferenceMultiPv(workerRepeatList, workerSwapList, effect.board, effect.side, driverState.cfg)
                })
            ) {
                driverState.pendingCompute = {
                    jobId: effect.jobId,
                    effect: effect,
                    startedAtMono: started,
                    // ★ 「避开兑子」：这次算棋的兑子候选表（finishCompute 挑替代用）
                    swapList: workerSwapList,
                    repeatList: workerRepeatList,
                    excludes: excludes,
                    // 本次是否**打算**带着法历史算（日志要把「宿主缺接口」与「本来就没历史」分开报）
                    historyRequested: !!verifiedHistory.positionCommand
                };
                driverState.stats.thinkOffThread += 1;
                driverActions.note(
                    "算棋（worker 线程）：轮到 " +
                        (effect.side === "w" ? "红" : "黑") +
                        (driverState.cfg.searchDepth > 0
                            ? "，目标 " +
                              driverState.cfg.searchDepth +
                              " 层（最长 " +
                              driverState.cfg.depthTimeoutMs +
                              "ms）"
                            : "，预算 " +
                              budget.movetimeMs +
                              "ms" +
                              (budget.phase === "normal" ? "" : "（残局加时，剩 " + budget.pieces + " 子）"))
                );
                return null; // 不阻塞：JOB_RESULT 稍后由 pollCompute 喂回去
            }
        }
        return computeInline(effect, excludes, verifiedHistory, budget);
    }

    /** 兜底 / 对照路径：在本线程同步算（会挡住触控，就是被替换掉的那条老路）。 */
    function computeInline(effect, excludes, verifiedHistory, budget) {
        driverState.stats.thinkInline += 1;
        const historyInput = verifiedHistory || driverActions.verifiedHistoryFor(effect);
        const searchPlan = budget || searchBudget.plan(effect.board, effect.movetimeMs, driverState.cfg.searchDepth,
            { adaptiveSearch: driverState.cfg.adaptiveSearch, side: effect.side });
        // ★ 「避开兑子」：算兑子候选（**不硬排除**；有的话开多路给 finishCompute 挑替代）
        const swapList = driverActions.winSeekSwapListFor(effect.board, effect.side);
        const repeatList = driverActions.winSeekRepeatListFor(effect.board, effect.side);
        let result;
        try {
            result = driverState.opts.analyze(
                { board: effect.board, side: effect.side },
                {
                    movetime: searchPlan.movetimeMs,
                    depth: driverState.cfg.searchDepth > 0 ? driverState.cfg.searchDepth : null,
                    timeoutMs: driverState.cfg.searchDepth > 0 ? driverState.cfg.depthTimeoutMs : null,
                    // 本局面内已经被游戏拒绝过的着法（点下去局面没变）不再选
                    excludeIccs: excludes || null,
                    // ★ 「避开兑子」：有兑子候选时开多路（首选是兑子也能在候选里挑替代）
                    multiPv: preferenceMultiPv(repeatList, swapList, effect.board, effect.side, driverState.cfg),
                    positionCommand: historyInput.positionCommand,
                    historyStatus: historyInput.historyStatus,
                    engine: driverState.cfg.engineKey,
                    pikafish: driverState.cfg.pikafish,
                    criticalReview: driverState.cfg.criticalReview,
                    maxTotalMs: searchPlan.maxTotalMs,
                    previousScore: driverState.lastThinkScore,
                    openingLearning: driverState.cfg.openingLearning,
                    // ★ 开局库步数闸门（见 cfg.bookOwnMoves）：本步用不用库 + 本局我方已走几手
                    bookOwnMoves: driverState.cfg.bookOwnMoves,
                    ownMovesMade: driverState.ownMoveCount,
                    // ★ 开局库候选随机（见 cfg.bookRandom）：false 才总用主着法
                    bookRandom: driverState.cfg.bookRandom
                }
            );
        } catch (error) {
            result = { ok: false, reason: String(error && error.message ? error.message : error) };
        }
        return finishCompute(effect, {
            ok: !!result.ok,
            reason: result.reason || "",
            iccs: result.iccs,
            move: result.move,
            score: result.score,
            depth: result.depth,
            ms: result.ms,
            engine: result.engine,
            review:result.review, excludeRelaxed:result.excludeRelaxed, bookLearned:result.bookLearned,
            pvIccs: result.pvIccs,
            historyUsed: result.historyUsed === true,
            historyStatus: result.historyStatus || "fen",
            historyRequested: !!historyInput.positionCommand,
            // ★ 开局库：这一手是第几个候选（1-based）/ 池大小（日志用）
            bookVariant: result.bookVariant == null ? null : result.bookVariant,
            bookPool: result.bookPool == null ? null : result.bookPool,
            // ★ 「避开兑子」：多路候选 + 兑子表（finishCompute 挑替代用）
            candidates: result.candidates,
            swapList: swapList, repeatList: repeatList, excludes: excludes
        });
    }

    /**
     * 算棋日志里的「历史」口径（2026-09-25：用户问「赢不了是不是算力不够」后细化）：
     *   · 用了历史 → 「历史 完整」/「历史 部分」（**只有完整才判得了长将/长捉**）；
     *   · 想用但宿主没有 `analyzePosition` → 「历史 不可用（宿主缺 analyzePosition）」；
     *   · 本来就没有可用历史（未确认局面 / 中途接管）→ 「历史 无（未确认局面）」。
     * 以前三种都写成「当前FEN」，日志里分不出是哪一种。
     */
    function historyTextOf(result) {
        if (result && result.historyStatus === "book") {
            return "本步查本地开局库，棋谱继续记录";
        }
        if (result && result.historyUsed) {
            return "历史 " + (String(result.historyStatus) === "complete" ? "完整" : "部分");
        }
        if (result && result.historyRequested) {
            return "历史 不可用（宿主缺 analyzePosition）";
        }
        return "历史 无（未确认局面）";
    }

    /**
     * 轻量预判的「记账」：对方一手被**采纳**时调用（观测推断 / 占用快路 / 确认帧 三条路）。
     * 命中与偏差都打日志 + 计数，然后清掉预判（只用一次）。
     * @param iccs    被采纳的对方着法（ICCS）
     * @param via     采纳来源（写进日志）
     * @param preFen  可选：这一手发生前的局面 FEN（与预判基点不符时静默丢弃，防陈旧误记）
     */
    function noteOpponentMove(iccs, via, preFen) {
        // 对面已经走完了：还在跑的预判任务立即让位（结果已无意义）
        dropPredict(null);
        if (!driverState.predictedReplies || !iccs) {
            return;
        }
        if (preFen && preFen !== driverState.predictedReplies.key) {
            driverState.predictedReplies = null;
            return;
        }
        const list = driverState.predictedReplies.list;
        const hit = list.indexOf(String(iccs)) >= 0;
        driverState.stats.predictChecked = (driverState.stats.predictChecked || 0) + 1;
        if (hit) {
            driverState.stats.predictHits = (driverState.stats.predictHits || 0) + 1;
        } else {
            driverState.stats.predictMisses = (driverState.stats.predictMisses || 0) + 1;
        }
        const rank = list.indexOf(String(iccs)) + 1;
        driverActions.note(
            "预判" +
                (hit ? "命中" : "偏差") +
                "（" +
                via +
                "）：" +
                (hit
                    ? String(iccs) + "（第 " + rank + "/" + list.length + " 路）"
                    : "预判 " + list.join("/") + "，实际 " + String(iccs)) +
                "｜预判 命中 " +
                driverState.stats.predictHits +
                "/" +
                driverState.stats.predictChecked
        );
        driverState.predictedReplies = null;
    }

    /** 取消/丢弃预判任务（worker 单任务：起算棋/对面已走都必须先让它让位）。 */
    function dropPredict(reason) {
        if (!driverState.pendingPredict) {
            return;
        }
        driverState.pendingPredict = null;
        try {
            if (driverState.workerSearch.isRunning()) {
                driverState.workerSearch.cancel();
            }
        } catch (error) {
            // worker 可能正好在收尾：无所谓
        }
        driverState.stats.predictDrops = (driverState.stats.predictDrops || 0) + 1;
    }

    /**
     * 起一次「对面视角」的预判（我方算完时调用；见 DEFAULTS.predictReply 的说明）。
     * 2026-10-05（用户报「只出来一路」）：与预思考不再互斥 —— 预判先占空闲槽（面板要画 top-K、
     * 对面真走完后还要核对），预思考改为等预判收完再上（见 pollPrethink 的 pendingPredict 闸门）。
     */
    function startPredict(expectedAfterFen) {
        if (driverState.cfg.predictReply === false || !expectedAfterFen || !driverActions.useWorkerSearch()) {
            return;
        }
        dropPredict(null); // 上一个没退干净就先让位（正常情况下上一轮早收了）
        const started = driverState.workerSearch.start(expectedAfterFen, {
            movetime: driverState.cfg.predictReplyMovetimeMs,
            multiPv: driverState.cfg.predictReplyMultiPv,
            engine: driverState.cfg.engineKey,
            pikafish: driverState.cfg.pikafish
        });
        if (!started) {
            return; // worker 起不来：跳过（预判只是增值信息，不阻塞任何流程）
        }
        driverState.pendingPredict = { key: expectedAfterFen, startedAtMono: driverState.nowMono() };
        driverState.stats.predictJobs = (driverState.stats.predictJobs || 0) + 1;
    }

    /** 非阻塞收预判结果（每轮 tick 调一次；只认「现在仍在等对面、且局面没变」的结果）。 */
    function pollPredict() {
        if (!driverState.pendingPredict) {
            return;
        }
        if (!driverState.workerSearch.isRunning()) {
            driverState.pendingPredict = null; // 任务被别处取消（暂停/换局）：标记清掉即可
            return;
        }
        const state = driverState.workerSearch.poll();
        if (state.status === "running") {
            return;
        }
        const job = driverState.pendingPredict;
        driverState.pendingPredict = null;
        if (state.status !== "done" || !state.result || state.result.ok !== true) {
            if (state.status === "error" || state.status === "timeout") {
                driverState.stats.predictErrors = (driverState.stats.predictErrors || 0) + 1;
            }
            return;
        }
        // 只有「还是等对面、且局面还是那一张」才采纳；对面已经走过了/局面变了 ⇒ 这算结果作废
        const stillWaiting =
            driverState.lastReliable &&
            driverState.lastReliable.side !== driverState.machine.mySide() &&
            fenModule.buildFen(driverState.lastReliable.board, driverState.lastReliable.side) === job.key;
        if (!stillWaiting) {
            driverState.stats.predictStale = (driverState.stats.predictStale || 0) + 1;
            return;
        }
        const list = [];
        const candidates = Array.isArray(state.result.candidates) ? state.result.candidates : null;
        if (candidates) {
            for (let i = 0; i < candidates.length && list.length < driverState.cfg.predictReplyMultiPv; i++) {
                const move = candidates[i] && candidates[i].move;
                if (move && list.indexOf(String(move)) < 0) {
                    list.push(String(move));
                }
            }
        }
        if (list.length === 0 && state.result.iccs) {
            list.push(String(state.result.iccs));
        }
        if (list.length === 0) {
            return;
        }
        // 对面视角搜索还带回它自己的主变（对面那一手 → 我方应手 → 对面下一手…）：
        // 有就把「预判线」也升级成这一份（更贴对面视角）；没有就沿用我方主变里存的那条线。
        const prev =
            driverState.predictedReplies && driverState.predictedReplies.key === job.key
                ? driverState.predictedReplies
                : null;
        const enemyLine = [];
        const ownLine = [];
        const pv = Array.isArray(state.result.pvIccs) ? state.result.pvIccs : null;
        if (pv) {
            for (let pk = 0; pk < pv.length; pk++) {
                const step = pv[pk] == null ? "" : String(pv[pk]);
                if (!step) {
                    continue;
                }
                if (pk % 2 === 0) {
                    if (enemyLine.length < 4) {
                        enemyLine.push(step);
                    }
                } else if (ownLine.length < 3) {
                    ownLine.push(step);
                }
            }
        }
        driverState.predictedReplies = {
            key: job.key,
            list: list,
            enemy: enemyLine.length ? enemyLine : prev ? prev.enemy : [],
            own: ownLine.length ? ownLine : prev ? prev.own : []
        };
        driverState.stats.predictAdopted = (driverState.stats.predictAdopted || 0) + 1;
        const top = candidates && candidates[0];
        driverActions.note(
            "预判（对面视角 " +
                Math.round(state.elapsedMs || 0) +
                "ms，深度 " +
                (state.result.depth == null ? "?" : state.result.depth) +
                "）：" +
                list.join(" / ") +
                (top && top.scoreCp != null ? "（头名分 " + Math.round(top.scoreCp) + "）" : "")
        );
    }

    /**
     * 把一个算棋结果变成 JOB_RESULT 喂回状态机（worker 与同步两条路共用）。
     * @param effect 当时那个 COMPUTE 效果（里面有 board/side，用来算预期后局面）
     * @param result { ok, reason, iccs, move, score, depth, ms }
     */
    function finishCompute(effect, result) {
        // Reject before changing scores, prediction seeds or logs; the state-machine gate alone
        // is too late for these side effects when an old worker completes after pause/new game.
        if (typeof driverState.machine.currentJob === "function") {
            const active = driverState.machine.currentJob();
            if (!active || active.jobId !== effect.jobId || driverState.machine.state() !== "THINKING") {
                if (driverState.pendingCompute && driverState.pendingCompute.jobId === effect.jobId) driverState.pendingCompute = null;
                return null;
            }
        }
        if (result.review) driverActions.note("关键步复核：" + result.review.reason + "，追加 " + result.review.extraMs +
            "ms（" + (result.review.accepted ? "采用复核结果" : "保留较深的首轮结果") + "）");
        // 避重与避兑共享一个分差预算，不能先后换两次而累计亏分。
        try {
            const keepMaterial = result.engine === "pikafish" && driverState.cfg.winSeek === true &&
                driverState.cfg.winSeekKeepMaterial !== false;
            const forecast = result.ok && lineMaterial.forecastRepeat(effect.board,effect.side,
                result.candidates,result.iccs,keepMaterial);
            const avoid = (result.repeatList || []).concat(result.swapList || [],forecast ? forecast.avoid : []);
            if (result.ok && result.engine === "pikafish" && (avoid.length ||
                lineMaterial.shouldCompare(effect.board, effect.side, keepMaterial))) {
                const candidates = Array.isArray(result.candidates) ? result.candidates : [];
                const count = legal.allLegalMoves(effect.board,effect.side).length;
                driverActions.note("求胜候选核对：请求 5 路，返回 " + candidates.length +
                    " 路，当前合法着法 " + count + " 手" + (count === 1 ? "（唯一应对，保留引擎首选）" : ""));
            }
            let alt = result.ok && winSeekModule.preferAlternative(result.candidates, avoid, {
                bestMove: result.iccs, side: effect.side, excludes: result.excludes,
                slack: driverState.cfg.winSeekSwapSlack,
                minScore: driverState.cfg.winSeekScoreMin, maxScore: driverState.cfg.winSeekScoreMax,
                onePlyRepeat: result.repeatList
            });
            const materialAlt = result.ok && lineMaterial.preferCandidate(effect.board, effect.side, result.candidates, {
                enabled: keepMaterial, bestMove: result.iccs, excludes: (result.excludes || []).concat(avoid),
                slack: driverState.cfg.winSeekSwapSlack,
                minScore: driverState.cfg.winSeekScoreMin, maxScore: driverState.cfg.winSeekScoreMax
            });
            // Both preferences compare to the original engine head; never add
            // one allowed score loss to another allowed score loss.
            if (materialAlt && (!alt || materialAlt.gap <= alt.gap)) alt = materialAlt;
            if (alt && alt.iccs !== result.iccs && moveFromIccs(effect, alt.iccs)) {
                const candidate = result.candidates[alt.index];
                driverState.stats.winSeekSwapSkips = (driverState.stats.winSeekSwapSkips || 0) + 1;
                driverActions.note("求胜模式：引擎首选 " + result.iccs + " → 候选 " + alt.iccs +
                    "（本次评分差 " + Math.round(alt.gap) + "cp；差得多就保留首选）");
                if (alt.material) {
                    driverState.stats.winSeekMaterialKeeps = (driverState.stats.winSeekMaterialKeeps || 0) + 1;
                    driverActions.note("求胜保子：比较前 " + alt.plies + " 半回合合法主变，我方兵卒 " +
                        alt.headMaterial.pawns + "→" + alt.material.pawns + "，车马炮 " +
                        alt.headMaterial.majors + "→" + alt.material.majors + "（预测，不写入确认棋谱）");
                } else if (forecast) {
                    driverState.stats.winSeekForecastSkips = (driverState.stats.winSeekForecastSkips || 0) + 1;
                    driverActions.note("求胜提前避重：原主变前 " + forecast.plies +
                        " 半回合内已有同盘同轮次重复，替代主变在已检查范围内未重复；同深度近分比较");
                }
                result.iccs = alt.iccs;
                result.move = null;
                result.score = candidate.scoreCp;
                result.depth = candidate.depth == null ? result.depth : candidate.depth;
                // 换着法后评分、主变和预思考种子必须跟随候选，不能沿用原着法的预测。
                result.pvIccs = typeof candidate.pv === "string" && candidate.pv.split(/\s+/)[0] === alt.iccs
                    ? candidate.pv.split(/\s+/) : [alt.iccs];
            } else if (result.ok && ((result.repeatList && result.repeatList.indexOf(result.iccs) >= 0) || forecast)) {
                const lines = Array.isArray(result.candidates) ? result.candidates.slice(0, 5).map(function (item) {
                    return item.move + " " + (item.scoreCp == null ? "?" : Math.round(item.scoreCp)) +
                        "cp/d" + (item.depth == null ? "?" : item.depth);
                }).join(" / ") : "无";
                const score = typeof result.score === "number" ? result.score * (effect.side === "b" ? -1 : 1) : null;
                const outside = score != null && (score < driverState.cfg.winSeekScoreMin || score > driverState.cfg.winSeekScoreMax);
                driverActions.note("求胜保护：" + (forecast ? "主变预测重复 " : "重复着法 ") + result.iccs +
                    (outside ? " 当前行棋方评分 " + Math.round(score) + "cp 超出求胜窗口 [" +
                        driverState.cfg.winSeekScoreMin + "," + driverState.cfg.winSeekScoreMax + "]" :
                        " 没有评分接近的可靠替代") + "，保留引擎首选｜候选 " + lines);
            }
        } catch (error) {
            // 偏好失败保留引擎原结果。
        }
        const outcome = { jobId: effect.jobId, ok: !!result.ok, reason: result.reason || "" };
        if (result.ok && typeof result.score === "number" && isFinite(result.score)) {
            // 求胜模式的「均势」闸门（见 cfg.winSeek / winSeekRepeatListFor）：记下这一步的评分
            driverState.lastThinkScore = result.score;
        }
        if (result.ok) {
            driverState.lastComputeFailKey = null;
            const checkedMove = moveFromIccs(effect, result.iccs);
            if (!checkedMove) {
                outcome.ok = false;
                outcome.reason = "引擎返回非法或不在当前局面的着法：" + String(result.iccs);
                driverActions.note("算棋结果已拒绝：" + outcome.reason);
                return driverActions.pump(driverState.machine.dispatch(Object.assign({ type: "JOB_RESULT" }, outcome)));
            }
            result.move = checkedMove;
            outcome.iccs = result.iccs;
            outcome.move = result.move;
            outcome.score = result.score;
            outcome.depth = result.depth;
            // Display metadata travels with the same validated job as the move.
            // Renderers never infer a search result from free-form log lines.
            outcome.source = result.engine || "pikafish";
            outcome.engineMs = result.ms;
            outcome.pvIccs = Array.isArray(result.pvIccs) ? result.pvIccs.slice(0, 16) : [];
            // 预期后局面：拿引擎给的着法算，不手写 FEN
            const applied = legal.applyMove(effect.board, result.move);
            outcome.expectedAfterFen = fenModule.buildFen(applied.board, effect.side === "b" ? "w" : "b");
            // ★ 预判（2026-09-25）：引擎主变（PV）的第 2 着就是它眼里的「对方最可能应手」——
            //   顺手记下：①日志显示；②验证/推断遇到多解时当平局裁决。每次算棋覆盖，绝不当唯一依据。
            //   ★ 多步预判线（2026-09-25 晚，用户要求）：主变整条线都留档 ——
            //     enemy = 对面第 1、2、3…手（PV 奇数位），own = 我方后续着法（偶数位）；
            //     实时裁决只吃 enemy[0]（本回合的分辨），更深的几手留给日志与将来的对账；
            //     对面视角的 top-K 搜索（pollPredict）完成后会把 list 升级成带排名的一整排候选。
            const pvIccs = Array.isArray(result.pvIccs) ? result.pvIccs : null;
            const enemyLine = [];
            const ownLine = [];
            if (pvIccs) {
                for (let pk = 1; pk < pvIccs.length; pk++) {
                    const step = pvIccs[pk] == null ? "" : String(pvIccs[pk]);
                    if (!step) {
                        continue;
                    }
                    if (pk % 2 === 1) {
                        if (enemyLine.length < 4) {
                            enemyLine.push(step);
                        }
                    } else if (ownLine.length < 3) {
                        ownLine.push(step);
                    }
                }
            }
            const predictedReply = enemyLine.length ? enemyLine[0] : null;
            driverState.predictedReplies = predictedReply
                ? { key: outcome.expectedAfterFen, list: [predictedReply], enemy: enemyLine, own: ownLine }
                : null;
            // ★ 升级层：「我的算完，再算对面」—— 在等对面应手的空档里按对面视角再浅算一手（top-K）
            if (result.engine === "pikafish" && pvIccs && pvIccs[0] === result.iccs && predictedReply) {
                driverState.prethinkSeed = {fen:outcome.expectedAfterFen, reply:predictedReply, attempted:false};
            }
            startPredict(outcome.expectedAfterFen);
            driverActions.note(
                "算棋：轮到 " +
                    (effect.side === "w" ? "红" : "黑") +
                    "，" +
                    result.iccs +
                    "（深度 " +
                    result.depth +
                    "，" +
                    result.ms +
                    "ms，评分 " +
                    Math.round(result.score) +
                    "｜后端 " +
                    (result.engine || "未知") +
                    (result.bookVariant ? "（候选 " + result.bookVariant + "/" + result.bookPool + " 随机）" : "") +
                    "｜" +
                    historyTextOf(result) +
                    (result.bookLearned ? "｜已参考本对手战绩" : "") +
                    (result.excludeRelaxed ? "｜排除放宽" : "") +
                    (predictedReply
                        ? "｜预计对方 " + predictedReply + (enemyLine[1] ? "（其后 " + enemyLine[1] + "）" : "")
                        : "") +
                    "）"
            );
            /**
             * ★★ 杀棋佐证闸门 v2（2026-09-21 下午；v1 见 16.19）：
             *   v1 只要求「算棋之后出现过一张 0 未知格、盘面一致的观测」——实测挡住了大部分
             *   假杀，但**挡不住「两帧同错」**：被盖格兜底把错误盘面稳定下来 ⇒ 两帧一致 ⇒ 放行
             *   （真机 09:50 的 d6g6：深度 1 / 4ms / 29999，点出去并不是杀）。
             *   v2：**只有“干净”的复核帧**（0 未知格 + **没有按基准填回的格** + 盘面一致）
             *   才直接放行；否则一律按住（pendingMate），由 tick 里走
             *   「干净帧放行 / 含被盖格 → 强制全盘新鲜重读复核」这两条路。
             */
            if (driverActions.isMateClaim(result.score)) {
                const cleanSame =
                    driverState.lastObsSummary &&
                    driverState.lastObsSummary.obs > driverState.lastComputeObs &&
                    driverState.lastObsSummary.unknown === 0 &&
                    driverState.lastObsSummary.covered === 0 &&
                    driverState.lastObsSummary.key === driverActions.positionKeyOfBoard(effect.board);
                if (!cleanSame) {
                    driverState.stats.mateHolds += 1;
                    driverState.pendingMate = {
                        jobId: effect.jobId,
                        board: effect.board ? effect.board.slice() : null,
                        boardKey: driverActions.positionKeyOfBoard(effect.board),
                        side: effect.side,
                        iccs: result.iccs,
                        obsAtStart: driverState.lastComputeObs,
                        freshRequested: false,
                        freshDeadlineObs: 0,
                        outcome: outcome
                    };
                    driverActions.note(
                        "杀棋待佐证：" +
                            result.iccs +
                            "（评分 " +
                            Math.round(result.score) +
                            "，深度 " +
                            result.depth +
                            "）先按住 —— 等一张干净的第二帧" +
                            "（无未知格、也没有按基准填回的格）再出手"
                    );
                    driverState.stats.thinkMs = driverState.pendingCompute
                        ? driverState.nowMono() - driverState.pendingCompute.startedAtMono
                        : 0;
                    driverState.pendingCompute = null;
                    return null;
                }
                driverActions.note("杀棋已有干净复核帧（无未知格、无按基准填回）→ 放行 " + result.iccs);
            }
            driverActions.recordPlanDecision(effect.board, effect.side, result.iccs);
        } else {
            // ★ 2026-09-21 真机（`算棋失败：bestmove (none)` 每 12s 空转，游戏僵住）：日志里看不到
            //   引擎拿到的局面，无法区分「真被将死 / 识别盘面不对 / 引擎输入路径坏了」三种原因。
            //   这里把「引擎拿到的盘面（FEN）」与「棋规层数出来的合法着法数」一起写出来：
            //   两者都 0 ⇒ 盘面按棋规就是终局；引擎说 0 而棋规有子可走 ⇒ 引擎输入/盘面数据有问题。
            //   同一张盘面只详细打一次（恢复循环里每 12s 会重复踩到它）。
            let failFen = "";
            try {
                failFen = effect && effect.board ? fenModule.buildFen(effect.board, effect.side) : "";
            } catch (error) {
                failFen = "";
            }
            const failKey = outcome.reason + "|" + failFen;
            if (failKey !== driverState.lastComputeFailKey) {
                driverState.lastComputeFailKey = failKey;
                let legalCount = -1;
                try {
                    legalCount = effect && effect.board ? legal.allLegalMoves(effect.board, effect.side).length : -1;
                } catch (error) {
                    legalCount = -1;
                }
                driverActions.note(
                    "算棋失败：" +
                        outcome.reason +
                        (failFen ? "｜盘面（" + (effect.side === "b" ? "黑先" : "红先") + "）：" + failFen : "") +
                        (legalCount >= 0 ? "｜棋规层合法着法 " + legalCount + " 种" : "")
                );
            } else {
                driverActions.note("算棋失败（同一张盘面）：" + outcome.reason);
            }
        }
        driverState.stats.thinkMs = driverState.pendingCompute
            ? driverState.nowMono() - driverState.pendingCompute.startedAtMono
            : 0;
        driverState.pendingCompute = null;
        return driverActions.pump(driverState.machine.dispatch(Object.assign({ type: "JOB_RESULT" }, outcome)));
    }

    /** worker 里只回传 ICCS：在这里从合法着法表里把着法对象找回来（着法生成比搜索便宜得多）。 */
    function moveFromIccs(effect, iccs) {
        // ICCS 必须是 a0..i9 到 a0..i9 的合法坐标；引擎异常时曾返回 a0b0，
        // 这不是一个真实着法，禁止进入点击确认循环。
        if (typeof iccs !== "string" || !/^[a-i][0-9][a-i][0-9]$/.test(iccs)) {
            driverActions.note("拒绝非法着法：" + String(iccs));
            return null;
        }
        const list = legal.allLegalMoves(effect.board, effect.side);
        for (let i = 0; i < list.length; i++) {
            if (list[i].iccs === iccs) {
                return list[i];
            }
        }
        return null;
    }

    /**
     * 非阻塞收算棋结果（每轮 tick 开头调一次）。
     * @returns null                                  没有在算
     *          { pending: true,  elapsedMs }         还在算（本轮不用再截图/观察）
     *          { pending: false, elapsedMs, status } 已收尾（结果或失败已喂回状态机）
     */
    function pollCompute() {
        if (driverState.deferredCompute) {
            const deferred = driverState.deferredCompute;
            if (driverState.prethink.busy()) {
                if (driverState.nowMono() - deferred.at < 12000) return {pending:true, elapsedMs:driverState.nowMono()-deferred.at};
                driverState.deferredCompute = null;
                finishCompute(deferred.effect, {ok:false, reason:"预思考退出超时，暂不启动新搜索"});
                return {pending:false,status:"timeout"};
            }
            driverState.deferredCompute = null;
            compute(deferred.effect);
            return {pending:!!driverState.pendingCompute,elapsedMs:0,status:"done"};
        }
        if (!driverState.pendingCompute || !driverState.workerSearch.isRunning()) {
            return null;
        }
        const state = driverState.workerSearch.poll();
        if (state.status === "running") {
            return { pending: true, elapsedMs: state.elapsedMs };
        }
        const job = driverState.pendingCompute;
        // 收尾期间状态机可能已经离开 THINKING（暂停/新会话/局面变了）：交给 finishCompute 的路由，
        // 由状态机自己判定是否接受（onJobResult 里有 S02/S03 两道门）。
        if (state.status === "done") {
            const result = state.result;
            let move = null;
            if (result.ok && result.iccs) {
                move = moveFromIccs(job.effect, result.iccs);
            }
            if (!result.ok) {
                finishCompute(job.effect, { ok: false, reason: result.reason || "worker 算棋失败" });
            } else if (!move) {
                finishCompute(job.effect, {
                    ok: false,
                    reason: "worker 给的着法 " + result.iccs + " 不在合法着法表里"
                });
            } else {
                finishCompute(job.effect, {
                    ok: true,
                    iccs: result.iccs,
                    move: move,
                    score: result.score,
                    depth: result.depth,
                    ms: result.ms,
                    engine: result.engine,
            review:result.review, excludeRelaxed:result.excludeRelaxed, bookLearned:result.bookLearned,
                    // ★（2026-09-25 修）：这里以前把 pvIccs 裁掉了 —— worker 路的主变到 finishCompute
                    //   就断了（「预计对方」/预判基点全都拿不到）。必须一并透传。
                    pvIccs: result.pvIccs,
                    historyUsed: result.historyUsed === true,
                    historyStatus: result.historyStatus || "fen",
                    historyRequested: job.historyRequested === true,
                    // ★ 开局库：候选序号/池大小（与同步路一致，日志要能看到随机结果）
                    bookVariant: result.bookVariant == null ? null : result.bookVariant,
                    bookPool: result.bookPool == null ? null : result.bookPool,
                    // ★ 「避开兑子」：多路候选 + 兑子表（finishCompute 挑替代用）
                    candidates: result.candidates,
                    repeatList: job.repeatList, excludes: job.excludes,
                    swapList: job.swapList == null ? null : job.swapList
                });
            }
            return { pending: false, elapsedMs: state.elapsedMs, status: "done" };
        }
        const reason =
            state.status === "timeout"
                ? "worker 算棋超时（超过 " + state.elapsedMs + "ms）｜原始：" + (state.error || "") +
                    "｜worker 状态：" + JSON.stringify(driverState.workerSearch.stats())
                : "worker 算棋异常：" + (state.error || "");
        finishCompute(job.effect, { ok: false, reason: reason });
        return { pending: false, elapsedMs: state.elapsedMs, status: state.status };
    }

    function cancelPrethink() {
        driverState.prethinkSeed = null;
        driverState.deferredCompute = null;
        driverState.prethink.cancel();
    }
    function pollPrethink() {
        driverState.prethink.poll();
        const seed = driverState.prethinkSeed;
        // 预判在跑就先不用预思考（单 worker 单任务）——等预判收完的下一轮再从这里排队上。
        if (!seed || seed.attempted || driverState.pendingPredict || driverState.cfg.prethink !== true || !driverState.running || driverState.cfg.searchDepth > 0 ||
            driverState.machine.state() !== "WAIT_TURN" || typeof driverState.machine.geometryTrusted !== "function" ||
            !driverState.machine.geometryTrusted()) return;
        const snap = driverState.positionTracker.snapshot();
        if (!seed || seed.attempted || snap.fen !== seed.fen) return;
        const p = prethinkModule.plan(snap, seed.reply, driverState.machine.mySide(),
            driverState.machine.versions().sessionId, driverState.cfg.pikafish,
            searchBudget.prethinkMs(driverState.cfg.movetimeMs, driverState.cfg.adaptiveSearch));
        seed.attempted = true;
        if (p && driverState.prethink.start(p, driverState.cfg.pikafish, driverState.cfg.engineKey, snap.historyStatus)) {
            driverState.stats.prethinkJobs = (driverState.stats.prethinkJobs || 0) + 1;
            driverActions.note("手机预思考：假设对方 " + p.reply + "，提前算我方应手 " + p.budget + "ms");
        }
    }
    return {
        pollPrethink:pollPrethink, cancelPrethink:cancelPrethink,
        compute: compute,
        computeInline: computeInline,
        historyTextOf: historyTextOf,
        noteOpponentMove: noteOpponentMove,
        dropPredict: dropPredict,
        startPredict: startPredict,
        pollPredict: pollPredict,
        finishCompute: finishCompute,
        moveFromIccs: moveFromIccs,
        pollCompute: pollCompute
    };
}

module.exports = { create: create };
