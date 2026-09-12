// @engine quickjs
/**
 * 时序融合：多帧观测 → 稳定局面（或明确的「还不能下结论」原因）。
 *
 * 规则（与任务书一致）：
 * - 窗口按**时间跨度**上限保留，不按数组长度；
 * - 空格状态也参与投票；
 * - 几何 / 模型 / 会话版本变化、连续丢失棋盘、长时间暂停 → 清空历史；
 * - 逐格融合后仍要交给棋规层整体校验，不允许拼出不可能的整盘。
 */
"use strict";

const assignment = require("./cell-assignment.js");

const DEFAULTS = {
    /** 至少看过几帧才开始判断 */
    minFrames: 3,
    /** 同一格至少几票才算数 */
    minAgree: 2,
    /** 投票占比下限（反对票多就继续观察） */
    minShare: 0.6,
    /** 窗口最大时间跨度（毫秒） */
    maxSpanMs: 1500
};

/**
 * @param options.now 取当前单调毫秒的函数（便于测试注入）
 */
function createFusion(options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const now = opts.now || function () { return Date.now(); };
    let frames = [];
    let versions = null;
    let reason = "尚未采集";

    function versionKey(frame) {
        return [frame.geometryVersion, frame.modelVersion, frame.sessionId].join("|");
    }

    function reset(nextReason) {
        frames = [];
        reason = nextReason || "历史已清空";
    }

    /** frame: {cells, stats?, geometryVersion, modelVersion, sessionId, quality?, capturedAt?} */
    function add(frame) {
        if (!frame || !frame.cells || frame.cells.length !== 90) {
            reset("帧数据不完整");
            return;
        }
        const key = versionKey(frame);
        if (versions !== null && key !== versions) {
            reset("几何/模型/会话已变化，旧观测作废");
        } else {
            versions = key;
        }
        const at = frame.capturedAt == null ? now() : frame.capturedAt;
        frames.push({ at: at, cells: frame.cells, quality: frame.quality == null ? 1 : frame.quality });
        const oldestAllowed = at - opts.maxSpanMs;
        frames = frames.filter(function (item) { return item.at >= oldestAllowed; });
    }

    /** 融合出稳定局面，或给出「还不能下结论」的原因。 */
    function snapshot() {
        if (frames.length < opts.minFrames) {
            reason = "观测帧不足（" + frames.length + "/" + opts.minFrames + "）";
            return { stable: false, cells: null, reason: reason, frames: frames.length };
        }
        const cells = new Array(90);
        let pieceCells = 0;
        let emptyCells = 0;
        let unknownCells = 0;
        for (let i = 0; i < 90; i++) {
            const votes = {};
            let unknownVotes = 0;
            let total = 0;
            for (let f = 0; f < frames.length; f++) {
                const cell = frames[f].cells[i];
                if (!cell) {
                    continue;
                }
                total++;
                if (cell.state === assignment.STATE_PIECE && cell.piece) {
                    votes[cell.piece] = (votes[cell.piece] || 0) + 1;
                } else if (cell.state === assignment.STATE_UNKNOWN) {
                    unknownVotes++;
                } else {
                    votes["\u0000empty"] = (votes["\u0000empty"] || 0) + 1;
                }
            }
            let bestLabel = null;
            let bestCount = 0;
            for (const label in votes) {
                if (votes[label] > bestCount) {
                    bestCount = votes[label];
                    bestLabel = label;
                }
            }
            const share = total > 0 ? bestCount / total : 0;
            if (unknownVotes > bestCount) {
                cells[i] = { state: assignment.STATE_UNKNOWN, piece: null, score: share, votes: bestCount };
                unknownCells++;
                continue;
            }
            if (bestLabel !== null && bestCount >= opts.minAgree && share >= opts.minShare) {
                if (bestLabel === "\u0000empty") {
                    cells[i] = { state: assignment.STATE_EMPTY, piece: null, score: share, votes: bestCount };
                    emptyCells++;
                } else {
                    cells[i] = { state: assignment.STATE_PIECE, piece: bestLabel, score: share, votes: bestCount };
                    pieceCells++;
                }
                continue;
            }
            cells[i] = { state: assignment.STATE_UNKNOWN, piece: null, score: share, votes: bestCount };
            unknownCells++;
        }
        const stable = unknownCells === 0;
        reason = stable ? "稳定" : ("存在 " + unknownCells + " 个未知格");
        return {
            stable: stable,
            cells: cells,
            reason: reason,
            frames: frames.length,
            stats: { pieces: pieceCells, empty: emptyCells, unknown: unknownCells },
            spanMs: frames.length > 1 ? frames[frames.length - 1].at - frames[0].at : 0
        };
    }

    return {
        options: opts,
        add: add,
        snapshot: snapshot,
        reset: reset,
        frameCount: function () { return frames.length; }
    };
}

module.exports = { createFusion: createFusion, DEFAULTS: DEFAULTS };
