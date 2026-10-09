// @engine quickjs
/**
 * 盘面级判决（「全局拟合 + 守恒」）—— 2026-09-25，把**棋盘定位**那套思路搬到识别判决上。
 *
 * 定位为什么准：它不逐条线独立判生死，而是把 95 条线的证据放进**一个全局拟合**里
 * （origin/step 全搜 + trimmed 求和，允许最弱 1~2 条线缺席）。字形识别恰恰相反 ——
 * 逐格独立判决，于是：
 *   · 一格「字形认得、只有红黑分不出」→ 只能留 `?`；状态机要求「未知格 0」⇒ **整盘卡住**；
 *   · 一格读数「违反棋规/数量上限」→ 也照单全收（字形链此前**没有** consistency.repair，
 *     旧检测链有 —— 这是搬过来之前的一个真缺口）。
 *
 * 这里补两层**全局**判决（只用约束算，不碰像素）：
 *   ① 修复：违规格按棋规降级 / 改阵营（复用 `src/chess/consistency.js` 的成熟逻辑）
 *   ② 守恒补全：`?` 格在「子力上限 + 棋规」下**唯一解**时补上；多解一律保持 `?`
 *
 * 原则：**宁缺勿错** —— 只在硬约束把答案逼到唯一时才落子；
 * 「空」永远合法，所以补全只对「识别已经确认『这里有子』、只是分不清是哪一色」的格生效
 * （gate="side"，字形 車/馬/炮 的三个字）。其余 `?` 一律原样保留。
 *
 * 纯 JS，不碰平台接口（离线自检可直接测）。
 */
"use strict";

const fen = require("../chess/fen.js");
const consistency = require("../chess/consistency.js");

const STATE_PIECE = "PIECE";
const STATE_EMPTY = "EMPTY";
const STATE_UNKNOWN = "UNKNOWN";

/** 红黑同形、只差颜色的三个字 → [红, 黑]。其余字形自带阵营，不存在「分不出色」的歧义。 */
const SIDED_PAIR = { "車": ["R", "r"], "馬": ["N", "n"], "炮": ["C", "c"] };

const DEFAULTS = {
    /** 读数格的默认可信度（识别已过门限的格子；只有同分比较用得到） */
    nominalScore: 0.9,
    /** gate="side" 的格：字形已确认（这些格子本来就匹配到了模板并过了两道门） */
    fillSide: true,
    /**
     * gate="match" 的格（连字形都不敢定）默认**不补**：
     * 实测空盘/木纹的假最高分能到 0.32~0.39，被动画盖住的格 0.19~0.36 —— 都不足以证明"这里有子"，
     * 而"空"又永远合法 ⇒ 补全无从谈起。真要用需显式给这个下限（默认 null = 关）。
     */
    fillMatchFloor: null,
    /** 一帧最多补几格（保守：补全是有代价的推断，不搞批量） */
    maxFill: 3,
    /** 是否启用 ① 违规修复（降级/改阵营） */
    repair: true
};

/** 90 格字符 → consistency 认的 cells（纯函数）。 */
function cellsOf(chars, options) {
    const opts = options || {};
    const scores = opts.scores || {};
    const out = new Array(chars.length);
    for (let i = 0; i < chars.length; i++) {
        const ch = chars[i];
        if (ch === "?") {
            out[i] = { state: STATE_UNKNOWN, piece: null, score: null };
        } else if (!ch || ch === ".") {
            out[i] = { state: STATE_EMPTY, piece: null, score: null };
        } else {
            out[i] = {
                state: STATE_PIECE, piece: ch,
                score: scores[i] == null ? (opts.nominalScore == null ? DEFAULTS.nominalScore : opts.nominalScore) : scores[i]
            };
        }
    }
    return out;
}

/** 硬违规（九宫/过河/兵卒后退）条数 —— 与 consistency 口径一致。 */
function hardCount(validation) {
    const list = (validation && validation.violations) || [];
    let n = 0;
    for (let i = 0; i < list.length; i++) {
        const kind = list[i].kind;
        if (kind === "palace" || kind === "river" || kind === "pawn-back") {
            n += 1;
        }
    }
    return n;
}

/** 子力数量类错误（"红炮有 3 个，超过上限 2"）。 */
function countErrors(validation) {
    const list = (validation && validation.errors) || [];
    let n = 0;
    for (let i = 0; i < list.length; i++) {
        if (/超过上限|数量异常|超过标准上限/.test(String(list[i]))) {
            n += 1;
        }
    }
    return n;
}

function boardOfChars(chars) {
    const board = new Array(chars.length);
    for (let i = 0; i < chars.length; i++) {
        const ch = chars[i];
        board[i] = (!ch || ch === "." || ch === "?") ? null : ch;
    }
    return board;
}

function validateChars(chars) {
    const validation = fen.validateBoard(boardOfChars(chars));
    return {
        errors: (validation.errors || []).length,
        hard: hardCount(validation),
        count: countErrors(validation),
        validation: validation
    };
}

/**
 * 守恒补全：对一个 `?` 格试它的候选字形（红/黑两色），
 * 只有「恰好一个候选不让盘面变坏（错误数不增加、硬违规为 0）」时才采用。
 * @return { char, why } | null
 */
function resolveCell(chars, index, candidates, base) {
    const kept = [];
    for (let i = 0; i < candidates.length; i++) {
        const ch = candidates[i];
        const probe = chars.slice();
        probe[index] = ch;
        const after = validateChars(probe);
        if (after.hard > 0) {
            continue;                      // 违反硬棋规（九宫/过河/兵种位置）
        }
        if (after.errors > base.errors) {
            continue;                      // 引入了新的错误（最常见：子力超上限）
        }
        kept.push({ char: ch, count: after.count });
    }
    if (kept.length === 1) {
        return { char: kept[0].char, why: "唯一解" };
    }
    if (kept.length === 0) {
        return { char: null, why: "两个候选都违规" };
    }
    // 多解：若其中一个能**减少**数量错误而另一个不会，优先它（这是"翻色修正"的方向）
    const better = kept.filter(function (item) { return item.count < base.count; });
    if (better.length === 1) {
        return { char: better[0].char, why: "唯一能消掉数量错误" };
    }
    return { char: null, why: "多解（" + kept.length + " 个候选都合法）" };
}

/**
 * @param chars 90 格（字形识别的产物；`.` 空 / `?` 未知 / 字母棋子）
 * @param options {
 *   scores?,  // { 格号: 匹配分 }（来自 glyph-recognizer 的 collect，只有违规降级挑"最弱格"用得到）
 *   notes?,   // glyph-recognizer 的 notes（`?` 的原因与候选字形）
 *   repair?, fillSide?, fillMatchFloor?, maxFill?
 * }
 * @return { chars, filled[], demoted[], flipped[], stats }
 */
function resolve(chars, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const work = chars.slice();
    const filled = [];
    const demoted = [];
    const flipped = [];

    // ① 违规修复（降级 / 改阵营）—— 复用旧检测链那套成熟逻辑
    if (opts.repair !== false && consistency && typeof consistency.repair === "function") {
        const cells = cellsOf(work, opts);
        const repaired = consistency.repair(cells, {});
        demoted.push.apply(demoted, repaired.demoted || []);
        flipped.push.apply(flipped, repaired.flipped || []);
        if ((repaired.demoted && repaired.demoted.length) || (repaired.flipped && repaired.flipped.length)) {
            const after = consistency.charsOf(cells);
            for (let i = 0; i < work.length; i++) {
                work[i] = after[i];
            }
        }
    }

    // ② 守恒补全（只对「有子但分不出色」的格）
    const notes = opts.notes || [];
    const base = validateChars(work);
    for (let n = 0; n < notes.length && filled.length < opts.maxFill; n++) {
        const note = notes[n];
        if (!note || note.kind !== "unknown") {
            continue;
        }
        const index = note.idx;
        if (work[index] !== "?") {
            continue;
        }
        let candidates = null;
        if (note.gate === "side" && opts.fillSide !== false) {
            candidates = SIDED_PAIR[note.name] || null;
        } else if (note.gate === "match" && opts.fillMatchFloor != null && note.sim != null &&
            note.sim >= opts.fillMatchFloor) {
            candidates = SIDED_PAIR[note.name] || null;
        }
        if (!candidates) {
            continue;
        }
        const got = resolveCell(work, index, candidates, base);
        if (got && got.char) {
            work[index] = got.char;
            filled.push({
                index: index, col: index % 9, row: Math.floor(index / 9),
                char: got.char, from: note.name, was: candidates.join("/"),
                sim: note.sim == null ? null : note.sim, why: got.why
            });
        }
    }

    return {
        chars: work,
        filled: filled,
        demoted: demoted,
        flipped: flipped,
        stats: {
            filled: filled.length,
            demoted: demoted.length,
            flipped: flipped.length,
            unknownBefore: countUnknown(chars),
            unknownAfter: countUnknown(work)
        }
    };
}

function countUnknown(chars) {
    let n = 0;
    for (let i = 0; i < chars.length; i++) {
        if (chars[i] === "?") {
            n += 1;
        }
    }
    return n;
}

module.exports = {
    DEFAULTS: DEFAULTS,
    SIDED_PAIR: SIDED_PAIR,
    cellsOf: cellsOf,
    resolveCell: resolveCell,
    resolve: resolve,
    countUnknown: countUnknown
};
