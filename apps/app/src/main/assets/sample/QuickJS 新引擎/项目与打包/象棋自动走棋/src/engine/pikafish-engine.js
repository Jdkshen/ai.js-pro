// @engine quickjs
/**
 * Pikafish 适配器：把宿主 `$pikafish`（native NNUE 引擎）包成与 `engine.js` **同一契约**的
 * `analyze(position, options)`，让驱动/面板/worker 可以无感切换。
 *
 * 契约对齐（见 engine.js 的 @returns）：
 *   { ok, side, move, iccs, captured, score, depth, nodes, ms, mateIn, pvIccs, fen, engine }
 *
 * 语义换算（Pikafish 是 UCI 引擎，与内置 JS 引擎的约定不同）：
 *   · UCI 的 score 是**走棋方视角**，这里换算成**红方视角**（正 = 红优），与 engine.js 一致；
 *   · `score mate N`（N 步杀，走棋方视角）→ score 置 ±29900（越过驱动的杀棋门 29800）、
 *     mateIn = ±N；没有 mate 时 mateIn = null、score = cp。
 *
 * 已知限制（v1.1）：
 *   · movetime 用尽才返回（引擎没有"提前命中就收"的通道），单步耗时 ≈ options.movetime；
 *   · excludeIccs（循环/长捉排除表）靠 MultiPV 选路：请求前 N 路候选（N = 排除数 + 1，最多 5），
 *     取第一路不在排除表里的。全部被挡时返回失败，由 engine-router 回退 JS 引擎兜底。
 */
"use strict";

const fenModule = require("../chess/fen.js");
const rules = require("../chess/rules.js");
const legal = require("../chess/legal-moves.js");
const engineModule = require("./engine.js");

/** 权重默认位置（按序找；null = 交给宿主默认目录 filesDir/externalFilesDir）。 */
const DEFAULT_EVAL_CANDIDATES = [
    "/sdcard/xiangqi-p4/pikafish.nnue",
    null
];

const DEFAULTS = {
    threads: 2,
    hashMb: 128,
    movetime: 1200,
    timeoutSlackMs: 15000   // analyze 的兜底超时 = movetime + slack
};

let started = false;
let lastStartError = "";
let activeConfig = { evalFile: null, threads: DEFAULTS.threads, hashMb: DEFAULTS.hashMb };

/** 宿主有没有挂上 $pikafish（原生库没打进包/旧版本宿主 → false）。 */
function available() {
    return typeof $pikafish !== "undefined" && $pikafish != null &&
        typeof $pikafish.analyze === "function" && $pikafish.available() === true;
}

/** 最近一次启动失败的原因（面板/日志用）。 */
function lastError() {
    return lastStartError;
}

/** 面板/配置注入：evalFile / threads / hashMb。改配置会强制重启引擎（下次 analyze 时生效）。 */
function configure(options) {
    const opts = options || {};
    if (opts.evalFile !== undefined) {
        activeConfig.evalFile = opts.evalFile == null ? null : String(opts.evalFile);
    }
    if (opts.threads !== undefined) {
        const threads = Math.round(Number(opts.threads));
        if (isFinite(threads) && threads >= 1) {
            activeConfig.threads = threads;
        }
    }
    if (opts.hashMb !== undefined) {
        const hashMb = Math.round(Number(opts.hashMb));
        if (isFinite(hashMb) && hashMb >= 16) {
            activeConfig.hashMb = hashMb;
        }
    }
    if (opts.restart === true) {
        stop();
    }
}

/** 按候选表找权重路径（files.exists 能判 /sdcard；找不到就返回 null）。 */
function pickEvalFile() {
    if (activeConfig.evalFile) {
        return activeConfig.evalFile;
    }
    for (let i = 0; i < DEFAULT_EVAL_CANDIDATES.length; i++) {
        const candidate = DEFAULT_EVAL_CANDIDATES[i];
        if (candidate == null) {
            return null;   // 交给宿主默认目录
        }
        try {
            if (files.exists(candidate)) {
                return candidate;
            }
        } catch (error) { /* 换下一个 */ }
    }
    return null;
}

/**
 * 启动引擎（幂等）。可以提前调用做「预热」（避免第一步棋多等 ~0.7s 的权重加载）。
 * @returns { ok, reason? }
 */
function ensureStarted() {
    if (started && $pikafish.isRunning()) {
        return { ok: true };
    }
    if (!available()) {
        lastStartError = "$pikafish 不可用（原生库没加载；目前只随包 arm64-v8a）";
        return { ok: false, reason: lastStartError };
    }
    const result = $pikafish.start({
        evalFile: pickEvalFile(),
        threads: activeConfig.threads,
        hashMb: activeConfig.hashMb
    });
    if (result && result.ok) {
        started = true;
        lastStartError = "";
        return { ok: true };
    }
    started = false;
    lastStartError = (result && result.error) ? String(result.error) : "未知错误";
    return { ok: false, reason: lastStartError };
}

/** 退出引擎（面板「停止」时调用；下次 analyze 会重新拉起）。 */
function stop() {
    if (started && available()) {
        try {
            $pikafish.quit();
        } catch (error) { /* 引擎侧已经退出 */ }
    }
    started = false;
}

/** "h2" → { col: 7, row: 7 }（ICCS 的 rank 0 在底线，本项目 row 0 在顶部）。 */
function squareToColRow(square) {
    return {
        col: square.charCodeAt(0) - 97,
        row: 9 - Number(square.charAt(1))
    };
}

/**
 * 从候选路由中挑一手：优先第一路不在排除表里的（纯函数，离线自检直接测）。
 * @param candidates 引擎回的候选数组（[{move, scoreCp, mate, depth, pv}...]，可能为空）
 * @param excludes   排除表（ICCS 数组；空/null 时直接取头名）
 * @param fallbackMove candidates 为空时的兜底着法（引擎的 bestmove）
 * @returns 选中的候选对象；全部被挡时返回 null
 */
function pickCandidate(candidates, excludes, fallbackMove) {
    const list = (candidates && candidates.length) ? candidates : [{ move: fallbackMove }];
    if (!excludes || !excludes.length) {
        return list[0];
    }
    for (let i = 0; i < list.length; i++) {
        const move = list[i] && list[i].move;
        if (move && excludes.indexOf(move) < 0) {
            return list[i];
        }
    }
    return null;
}

/**
 * 算一步棋（阻塞，耗时 ≈ options.movetime）。
 * @param position FEN / {board, side} / 90 长数组（同 engine.js）
 * @param options.movetime / options.side / options.timeoutMs
 * @returns 同 engine.js 的 analyze 返回；失败给 { ok:false, stage, reason }
 */
function analyze(position, options) {
    const opts = options || {};
    const resolved = engineModule.resolvePosition(position, opts);
    const problems = engineModule.inspect(resolved.board);
    if (problems.length > 0) {
        return { ok: false, stage: "position", reason: problems.join("；"), side: resolved.side };
    }
    const boot = ensureStarted();
    if (!boot.ok) {
        return { ok: false, stage: "native", reason: boot.reason, side: resolved.side };
    }
    const fen = fenModule.buildFen(resolved.board, resolved.side);
    const movetime = opts.movetime == null ? DEFAULTS.movetime : opts.movetime;
    const timeoutMs = opts.timeoutMs == null ? movetime + DEFAULTS.timeoutSlackMs : opts.timeoutMs;
    // 排除表（游戏拒绝过的着法/循环排除）：用 MultiPV 多要几路，再挑第一路不在表里的。
    const excludes = (opts.excludeIccs && opts.excludeIccs.length) ? opts.excludeIccs : null;
    const multiPv = excludes ? Math.min(excludes.length + 1, 5) : 1;
    let response;
    try {
        response = $pikafish.analyze(fen, movetime, timeoutMs, multiPv);
    } catch (error) {
        return {
            ok: false, stage: "native", side: resolved.side,
            reason: "引擎调用抛错：" + String(error && error.message ? error.message : error)
        };
    }
    if (!response || response.ok !== true) {
        return {
            ok: false, stage: "native", side: resolved.side,
            reason: (response && response.error) ? response.error : "引擎没有返回 bestmove"
        };
    }
    const chosen = pickCandidate(response.candidates, excludes, response.move);
    if (!chosen) {
        return {
            ok: false, stage: "exclude", side: resolved.side,
            reason: "Pikafish 的 " + (response.candidates ? response.candidates.length : 0) +
                " 路候选全在排除表里（" + excludes.join("/") + "）"
        };
    }
    const iccs = String(chosen.move || "");
    if (!/^[a-i][0-9][a-i][0-9]$/.test(iccs)) {
        return { ok: false, stage: "native", side: resolved.side, reason: "引擎给了非法着法：" + iccs };
    }
    const from = squareToColRow(iccs.substring(0, 2));
    const to = squareToColRow(iccs.substring(2, 4));
    // 棋规复核：即使引擎出错（或 FEN 与识别局面对不上），也不允许点出非法着法
    const check = legal.isLegalMove(resolved.board, from.col, from.row, to.col, to.row, resolved.side);
    if (!check.legal) {
        return {
            ok: false, stage: "move-check", side: resolved.side,
            reason: "引擎着法通不过棋规校验：" + check.reason + "（" + iccs + "）"
        };
    }
    const flip = resolved.side === "b" ? -1 : 1;   // 走棋方视角 → 红方视角
    const scoreCp = chosen.scoreCp == null ? null : Number(chosen.scoreCp) * flip;
    const mate = chosen.mate == null ? null : Number(chosen.mate) * flip;
    const move = { fromCol: from.col, fromRow: from.row, toCol: to.col, toRow: to.row, iccs: iccs };
    const pvSource = (typeof chosen.pv === "string") ? chosen.pv : "";
    const pvIccs = pvSource.length > 0
        ? pvSource.split(/\s+/).filter(function (token) { return token.length >= 4; })
        : [iccs];
    return {
        ok: true,
        side: resolved.side,
        move: move,
        iccs: iccs,
        captured: resolved.board[rules.idx(to.col, to.row)] || null,
        // 杀棋分要越过驱动的杀棋门（MATE_CLAIM_MIN_SCORE=29800），与引擎的 MATE-1 语义对齐
        score: mate != null ? (mate > 0 ? 29900 : -29900) : (scoreCp == null ? 0 : scoreCp),
        depth: chosen.depth == null ? null : Number(chosen.depth),
        nodes: chosen.nodes == null ? null : Number(chosen.nodes),
        ms: chosen.timeMs == null ? null : Number(chosen.timeMs),
        mateIn: mate,
        pvIccs: pvIccs,
        fen: fen,
        engine: "pikafish"
    };
}

module.exports = {
    available: available,
    lastError: lastError,
    configure: configure,
    ensureStarted: ensureStarted,
    stop: stop,
    analyze: analyze,
    /** 纯函数：从候选路由挑一手（离线自检用）。 */
    pickCandidate: pickCandidate,
    /** 一行配置描述（面板/日志用）。 */
    configText: function () {
        return "Pikafish（线程 " + activeConfig.threads + "，Hash " + activeConfig.hashMb + "MB" +
            (activeConfig.evalFile ? "" : "，权重自动查找") + "）";
    },
    DEFAULTS: DEFAULTS
};
