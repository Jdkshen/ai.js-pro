// @engine quickjs
/**
 * 证据融合（重构方案 §3.8「多帧融合」+ §3.7「棋子结果融合」）。
 *
 * 与旧 `temporal-fusion.js` 的区别：那边是「按格子投票」，这边是**按证据融合** ——
 * 每枚棋子带三份证据（YOLO 框 / OCR 字形 / 颜色），分别投票，最后按方案给的综合分数定夺：
 *
 *   finalScore = detectionScore×0.30 + ocrScore×0.45 + colorScore×0.25      （方案 §3.7）
 *
 * 方案 §3.8 三条硬规则：
 *   · 最近 3 帧：两帧结果相同即可确认；三帧三种结果 ⇒ 未知；
 *   · 棋子移动只允许「起点消失、终点出现」或合法吃子变化；
 *   · **颜色单独投票**，不能因为字形一致就覆盖阵营；
 *   · 点击后的动画帧不参与投票（由调用方 `excludeSince`/稳定帧闸门保证）。
 *
 * 出口：每格 `{ char, cls, side, finalScore, evidence, state }`，拿不准就是 `?` —— 绝不猜。
 */
"use strict";

const DEFAULTS = {
    /** 综合分数门槛（方案 §3.7：≥0.78 才接受） */
    minFinalScore: 0.78,
    /** 三份证据的权重（方案 §3.7） */
    weightDetect: 0.30,
    weightOcr: 0.45,
    weightColor: 0.25,
    /** 保留几帧（方案 §3.8：最近 3 帧） */
    frames: 3
};

const WEIGHT_IDENTITY = { 0: 255, 1: 10 };

/**
 * 综合分数（方案 §3.7 的公式）。
 * @param evidence { detectionScore, ocrScore, colorScore }
 */
function finalScore(evidence, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const e = evidence || {};
    const d = Number(e.detectionScore) || 0;
    const o = Number(e.ocrScore) || 0;
    const c = Number(e.colorScore) || 0;
    return d * opts.weightDetect + o * opts.weightOcr + c * opts.weightColor;
}

/** 单枚棋子的入口数据（调用方给：检测分数、OCR 类别/分数、颜色/分数）
 *
 * ⚠️ `options` 必须透传：之前这里用固定 `DEFAULTS`，导致 `createFuser({minFinalScore, weightDetect,…})`
 *    里的四个参数**全部不生效**（2026-09-20 评审指出）。
 */
function pieceOf(entry, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const e = entry || {};
    const cls = e.cls ? String(e.cls).toUpperCase() : null;
    const side = e.side ? String(e.side).toUpperCase() : null;
    const score = finalScore({
        detectionScore: e.detectionScore,
        ocrScore: e.ocrScore,
        colorScore: e.colorScore
    }, opts);
    const ok = !!(cls && side && side !== "UNKNOWN" && score >= opts.minFinalScore);
    return {
        index: e.index,
        cls: cls,
        side: side,
        char: ok ? (side === "RED" ? cls : cls.toLowerCase()) : "?",
        finalScore: score,
        evidence: {
            yolo: e.detectionScore != null,
            ocrText: e.text || null,
            color: side
        },
        state: ok ? "ok" : (cls ? "low" : "unknown")
    };
}

/**
 * 三帧证据融合器。
 * 每帧调 `push(frame)`，frame = [{ index, cls, side, detectionScore, ocrScore, colorScore }]。
 * 返回：{ chars, cells, unknown, confirmed, conflicts }
 */
function createFuser(options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const history = [];

    function push(frame) {
        const items = frame || [];
        // 每格每帧只保留一条（同一格重复检测取分数最高的）
        const byIndex = {};
        for (let i = 0; i < items.length; i++) {
            const one = items[i];
            if (!one || one.index == null) {
                continue;
            }
            const built = pieceOf(one, opts);
            const prev = byIndex[built.index];
            if (!prev || built.finalScore > prev.finalScore) {
                byIndex[built.index] = built;
            }
        }
        history.push(byIndex);
        while (history.length > opts.frames) {
            history.shift();
        }
        return fuse();
    }

    function fuse() {
        const cells = [];
        const conflicts = [];
        // 收集出现过的格子
        const indexes = {};
        for (let f = 0; f < history.length; f++) {
            for (const key in history[f]) {
                indexes[key] = true;
            }
        }
        const chars = new Array(90);
        for (let i = 0; i < 90; i++) {
            chars[i] = null;
        }
        let confirmed = 0, unknown = 0;
        for (const key in indexes) {
            const index = Number(key);
            // 每帧这一格的结论（没有该格 = 那一帧主认为「空」）
            const seen = history.map(function (frame) { return frame[index] || null; });
            const last = seen[seen.length - 1];
            if (!last || last.state !== "ok") {
                chars[index] = "?";
                unknown += 1;
                cells.push({ index: index, char: "?", state: "unknown", votes: seen.length });
                continue;
            }
            // 两帧一致 ⇒ 确认（方案 §3.8）
            let agree = 0;
            for (let f = 0; f < seen.length; f++) {
                const one = seen[f];
                if (one && one.char === last.char) {
                    agree += 1;
                }
            }
            // 三帧三种结论 ⇒ 未知
            const distinct = {};
            for (let f = 0; f < seen.length; f++) {
                if (seen[f] && seen[f].state === "ok") {
                    distinct[seen[f].char] = true;
                }
            }
            const distinctCount = Object.keys(distinct).length;
            if (distinctCount >= 3 && opts.frames >= 3) {
                chars[index] = "?";
                unknown += 1;
                conflicts.push({ index: index, reason: "三帧三种结论", chars: Object.keys(distinct) });
                cells.push({ index: index, char: "?", state: "conflict", votes: seen.length });
                continue;
            }
            if (agree >= 2) {
                chars[index] = last.char;
                confirmed += 1;
                cells.push(Object.assign({}, last, { votes: agree }));
                continue;
            }
            // 只有一票：保留但标为未确认（调用方按自己策略决定要不要等下一帧）
            chars[index] = "?";
            unknown += 1;
            cells.push({ index: index, char: "?", state: "pending", votes: agree, candidate: last.char });
        }
        return {
            chars: chars,
            cells: cells,
            unknown: unknown,
            confirmed: confirmed,
            conflicts: conflicts,
            frames: history.length
        };
    }

    function reset() {
        history.length = 0;
    }

    return { push: push, fuse: fuse, reset: reset, stats: function () { return { frames: history.length }; } };
}

module.exports = {
    createFuser: createFuser,
    finalScore: finalScore,
    pieceOf: pieceOf,
    DEFAULTS: DEFAULTS
};
