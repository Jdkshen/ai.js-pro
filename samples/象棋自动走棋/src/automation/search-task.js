// @engine quickjs
"use strict";
// Run each search in the same engine; require and native engine caches survive.
function analyze(args, suppliedEngine) {
    var dir = args.dir;
    // 走 engine-router：worker 里必须跟主线程同一个后端（否则面板选 Pikafish、实际还是 JS 在算）。
    // worker 是独立引擎实例，模块状态不共享 —— 后端与配置只能从 args 带进来。
    var engine = suppliedEngine || require(dir + "/src/engine/engine-router.js");
    if (args.engine != null) {
        engine.setBackend(String(args.engine));
    }
    if (args.pikafish != null) {
        engine.configurePikafish(args.pikafish);
    }
    var options = {};
    if (args.movetime != null) {
        options.movetime = args.movetime;
    }
    if (args.depth != null) {
        options.depth = args.depth;
    }
    if (args.timeoutMs != null) {
        options.timeoutMs = args.timeoutMs;
    }
    // ★ 显式 MultiPV（「预判对面」用）：>0 时按它要路数，结果里的 candidates 会带回 top-K。
    if (args.multiPv != null) {
        options.multiPv = args.multiPv;
    }
    // 传进来的是 90 格数组时才有用（FEN 里已经带了走棋方）；不传就让引擎自己判
    if (args.side != null) {
        options.side = args.side;
    }
    // 规划期的排除表（游戏拒绝过的着法 + 疑似循环/长捉的着法）：以前这条路上漏传了，
    // 结果 worker 模式下黑名单完全不生效（自检 2026-09-21 补）。
    if (args.excludeIccs != null) {
        options.excludeIccs = args.excludeIccs;
    }
    if (args.positionCommand != null) {
        options.positionCommand = args.positionCommand;
        options.historyStatus = args.historyStatus;
    }
    // ★ 开局库步数闸门（见 engine-router 的库前检查）：连「本步用不用库」一起带进来 ——
    //   不传的话 worker 路径会无视面板设的步数上限，永远优先出库。
    if (args.bookOwnMoves != null) {
        options.bookOwnMoves = args.bookOwnMoves;
    }
    // ★ 开局库候选随机（2026-09-25 用户）：「总是同一条线」→ false 才回退到主着法
    if (args.bookRandom != null) {
        options.bookRandom = args.bookRandom;
    }
    if (args.ownMovesMade != null) {
        options.ownMovesMade = args.ownMovesMade;
    }
    // ★ 竞争策略透传（2026-09-27 独立复核）：这一层以前把这几项整段丢了 ——
    //   · openingBook:false 到不了引擎 ⇒ 预思考会命中开局库秒回，Hash 根本没热身；
    //   · criticalReview/previousScore ⇒ worker 路的关键步加时复核永远是关的（断链）；
    //   · openingLearning ⇒ worker 路不查战绩。
    if (args.openingBook != null) {
        options.openingBook = args.openingBook;
    }
    if (args.criticalReview != null) {
        options.criticalReview = args.criticalReview;
    }
    if (args.maxTotalMs != null) options.maxTotalMs = args.maxTotalMs;
    if (args.previousScore != null) {
        options.previousScore = args.previousScore;
    }
    if (args.openingLearning != null) {
        options.openingLearning = args.openingLearning;
    }
    var result = engine.analyze(args.position, options);
    return {
        ok: !!result.ok,
        engine: result.engine || engine.lastEngine() || null,
        historyUsed: result.historyUsed === true,
        historyStatus: result.historyStatus || "fen",
        iccs: result.iccs || null,
        // ★ 轻量预判用（2026-09-25）：主变（PV）必须透传 —— pvIccs[0] = 我方这手，
        //   pvIccs[1] = 引擎预判的对方应手。以前在这里被裁掉，驱动侧根本看不到预判。
        pvIccs: Array.isArray(result.pvIccs) ? result.pvIccs : null,
        // ★「预判对面」用：多路候选（move/scoreCp/mate/depth）也要透传（以前只回 iccs）。
        candidates: Array.isArray(result.candidates) ? result.candidates : null,
        score: result.score == null ? null : result.score,
        depth: result.depth == null ? null : result.depth,
        nodes: result.nodes == null ? null : result.nodes,
        ms: result.ms == null ? null : result.ms,
        mateIn: result.mateIn == null ? null : result.mateIn,
        // ★ 开局库：这一手是第几个候选（1-based）/ 池大小（日志用）
        bookVariant: result.bookVariant == null ? null : result.bookVariant,
        bookPool: result.bookPool == null ? null : result.bookPool,
        // ★ 竞争策略标记（2026-09-27）：关键步复核结果 / 排除放宽 / 开局学习命中
        review: result.review == null ? null : result.review,
        excludeRelaxed: result.excludeRelaxed === true,
        bookLearned: result.bookLearned === true,
        reason: result.reason || ""
    };
}

module.exports = {analyze: analyze};
