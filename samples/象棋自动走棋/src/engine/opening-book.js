// @engine quickjs
"use strict";

// This opt-in trial book is prepared and vetted on a PC. No network access.
// The JSON stays on local storage rather than being bundled with the APK.
const fen = require("../chess/fen.js");
const rules = require("../chess/rules.js");
const legal = require("../chess/legal-moves.js");
// ★ 开局学习（2026-09-27）：战绩挑选规则只有一份（services/opening-results.choose）
const openingResults = require("../services/opening-results.js");

const TRIAL_PATH = "/sdcard/xiangqi-p4/opening-book-vetted.json";
let loaded = null;
let tried = false;

function parseIndex(text) {
    try {
        const data = JSON.parse(String(text));
        return data && data.schema === 1 && data.vet && data.positions &&
            typeof data.positions === "object" ? data : null;
    } catch (error) {
        return null;
    }
}

function loadTrial() {
    if (tried) return loaded;
    tried = true;
    try {
        if (typeof files !== "undefined" && files.exists(TRIAL_PATH)) {
            loaded = parseIndex(files.read(TRIAL_PATH));
        }
    } catch (error) {
        loaded = null;
    }
    return loaded;
}

/**
 * 从库里条目里挑一条着法线（2026-09-25 用户：「开局库好像每次都一样，搞随机的吧」）。
 *
 * 抽签池 = 条目的 candidates，只收：
 *   · 形状合法（iccs）且 **scoreType === "cp"**、分数有限（与老口径一致：没搜索证据的条目不自动走）；
 *   · 不在 excludes 里（被游戏拒过 / 循环禁用 / 求胜模式排掉的线不进池）；
 *   · 分数 ≥ 池里最高分 − scoreSlack（默认 30cp）—— **随机也不许挑道明显差的线**。
 * deterministic = true 时退回老行为（总用主着法；主着法不在池里则用池里第一条）。
 *
 * @param entry 库条目 { move, games, candidates: [{ move, scoreType, score, depth }] }
 * @param options { rand?（默认 Math.random；自检注入 0~0.999 可复现）,
 *                  scoreSlack?(默认 30), deterministic?, excludes? }
 * @returns { iccs, score（该走棋方视角，未翻倍）, depth, games, variant, pool } 或 null
 */
function pickLine(entry, options) {
    const opts = options || {};
    const rand = typeof opts.rand === "function" ? opts.rand : Math.random;
    const slack = opts.scoreSlack == null ? 30 : Math.max(0, Number(opts.scoreSlack) || 0);
    const excludes = opts.excludes || [];
    const master = String((entry && entry.move) || "");
    if (!/^[a-i][0-9][a-i][0-9]$/.test(master)) {
        return null;
    }
    const list = Array.isArray(entry && entry.candidates) ? entry.candidates : [];
    let pool = [];
    for (let i = 0; i < list.length; i++) {
        const item = list[i];
        if (!item || !/^[a-i][0-9][a-i][0-9]$/.test(String(item.move || ""))) {
            continue;
        }
        if (item.scoreType !== "cp" || excludes.indexOf(item.move) >= 0) {
            continue;
        }
        const score = Number(item.score);
        if (!isFinite(score)) {
            continue;
        }
        pool.push({ iccs: item.move, score: score, depth: item.depth });
    }
    if (pool.length > 1) {
        let best = pool[0].score;
        for (let i = 1; i < pool.length; i++) {
            if (pool[i].score > best) {
                best = pool[i].score;
            }
        }
        pool = pool.filter(function (item) { return item.score >= best - slack; });
    }
    if (!pool.length) {
        return null;
    }
    const mk = function (item, index) {
        return {
            iccs: item.iccs,
            score: item.score,
            depth: item.depth,
            games: Number(entry.games) || 0,
            variant: index + 1,
            pool: pool.length
        };
    };
    if (opts.deterministic === true) {
        for (let i = 0; i < pool.length; i++) {
            if (pool[i].iccs === master) {
                return mk(pool[i], i);
            }
        }
        return mk(pool[0], 0);
    }
    // ★ 开局学习（2026-09-27，openingLearning）：results = 该局面人工确认过的战绩
    //   （services/opening-results.aggregate 的形态：{iccs: {games, points}}）。
    //   判据见 opening-results.choose：每个候选至少 5 局、平滑得分差 ≥10%。
    //   数据不足/存储不可用 ⇒ 原样走随机（绝不因「学习」走出没证据的线）。
    if (opts.deterministic !== true && opts.results && pool.length > 1) {
        let learned = null;
        try {
            learned = openingResults.choose(pool, opts.results);
        } catch (error) {
            learned = null;   // 战绩数据坏了/存储不可用：当没有学习数据
        }
        if (learned) {
            for (let i = 0; i < pool.length; i++) {
                if (pool[i].iccs === learned) {
                    const out = mk(pool[i], i);
                    out.learned = true;
                    return out;
                }
            }
        }
    }
    let r = Number(rand());
    if (!isFinite(r) || r < 0) {
        r = 0;
    }
    if (r >= 1) {
        r = 0.999999;
    }
    const index = Math.floor(r * pool.length);
    return mk(pool[index], index);
}

/** Return a legally checked, engine-vetted book move or null. */
function probe(index, board, side, excludes, options) {
    if (!index || !index.positions || !board || board.length !== 90 ||
        (side !== "w" && side !== "b")) return null;
    const key = fen.layoutOf(board) + " " + side;
    const entry = index.positions[key];
    if (!entry) return null;
    // ★ 2026-09-27（独立复核同名用例）：条目的**主着法必须在这局面上合法** ——
    //   库文件被改坏 / 生成版本错配时整条作废（否则 pickLine 会凭 candidates 继续出子，
    //   而这条目本身已经不可信了）。见 tools/test-opening-book.js 的 e0e9 用例。
    const master = String(entry.move || "");
    if (!/^[a-i][0-9][a-i][0-9]$/.test(master)) return null;
    const masterFrom = rules.iccsSquare(master, 0);
    const masterTo = rules.iccsSquare(master, 1);
    if (!legal.isLegalMove(board, masterFrom % 9, Math.floor(masterFrom / 9),
        masterTo % 9, Math.floor(masterTo / 9), side).legal) return null;
    // ★ 2026-09-25 用户：「开局库好像每次都一样，搞随机的吧」⇒ 默认从候选里抽签（见 pickLine）
    // ★ 开局学习（2026-09-27）：战绩数据由调用方按局面键取（resultsFor(key, side)），取不到就退回随机
    let results = null;
    try {
        results = (options && typeof options.resultsFor === "function")
            ? options.resultsFor(key, side)
            : (options && options.results);
    } catch (error) {
        results = null;
    }
    const pick = pickLine(entry, {
        rand: options && options.rand,
        scoreSlack: options && options.scoreSlack,
        deterministic: !!(options && options.deterministic),
        excludes: excludes || [],
        results: results
    });
    if (!pick) return null;
    const from = rules.iccsSquare(pick.iccs, 0);
    const to = rules.iccsSquare(pick.iccs, 1);
    const fc = from % 9, fr = Math.floor(from / 9);
    const tc = to % 9, tr = Math.floor(to / 9);
    if (!legal.isLegalMove(board, fc, fr, tc, tr, side).legal) return null;
    const score = pick.score * (side === "b" ? -1 : 1);
    if (!isFinite(score)) return null;
    return {
        iccs: pick.iccs,
        move: { fromCol: fc, fromRow: fr, toCol: tc, toRow: tr, iccs: pick.iccs },
        score: score,
        depth: pick.depth == null ? 0 : Number(pick.depth),
        games: pick.games,
        captured: board[rules.idx(tc, tr)] || null,
        variant: pick.variant,
        pool: pick.pool,
        // ★ 这一手是「战绩学习」挑出来的（日志/排障用；见 pickLine 的 learning 分支）
        learned: pick.learned === true
    };
}

/**
 * 开局库步数闸门（2026-09-25 用户口径：「开局用开局库走到第 N 手，之后引擎接手算棋」）。
 *
 * @param bookOwnMoves 允许用库的**我方已走着数**上限：
 *                    null/undefined = 不设限（老行为：只要书上有就用）；
 *                    0 = 不用库（全程引擎实时搜索）；N>0 = 只在前 N 手内用库。
 * @param ownMovesMade 本局我方已确认着数（驱动给；数不出来传 null）
 * @returns true = 这一步还能用库
 */
function withinLimit(bookOwnMoves, ownMovesMade) {
    if (bookOwnMoves == null || bookOwnMoves === "") {
        return true;
    }
    const max = Number(bookOwnMoves);
    if (!isFinite(max)) {
        return true;
    }
    if (max <= 0) {
        return false;   // 0 = 不用库
    }
    const made = Number(ownMovesMade);
    if (!isFinite(made)) {
        return true;    // 数不出来时别把库挡掉（宁可照旧用）
    }
    return made < max;
}

/**
 * 测试/离线回放用：直接塞一份索引（不碰设备文件）。传 null 就恢复「走文件」的状态。
 * ⚠️ 只建议自检/工具用，正式链路一律走 loadTrial()。
 * 对象与 JSON 文本都收（对象直接按 schema 校验，不走 JSON.parse —— 否则 String(obj) 必失败）。
 */
function setTrialIndex(index) {
    if (index && typeof index === "object") {
        loaded = (index.schema === 1 && index.vet && index.positions &&
            typeof index.positions === "object") ? index : null;
    } else {
        loaded = parseIndex(index);
    }
    tried = true;
    return !!loaded;
}

module.exports = {
    TRIAL_PATH: TRIAL_PATH,
    parseIndex: parseIndex,
    loadTrial: loadTrial,
    probe: probe,
    pickLine: pickLine,
    withinLimit: withinLimit,
    setTrialIndex: setTrialIndex
};
