// @engine quickjs
/**
 * 多帧观测 → 稳定局面（P2 的接线层）。
 *
 * 为什么要它：单帧识别一定会错几个子（模型 val mAP50 只有 0.53，马/相 尤其弱），
 * 而连续几帧里「错的那一两个子」每次都不一样 —— 按格投票就能把它们洗掉，
 * 真正会动的只是刚走走的那个子。任务书也要求：融合后仍要交给棋规层整体校验。
 *
 * 本模块只管「把识别结果喂进 temporal-fusion、再把融合结果翻译成 FEN 字符/布局」：
 * 识别本身怎么跑（截图/读图、定位、模型）由调用方负责，便于离线用桩测。
 */
"use strict";

const assignment = require("./cell-assignment.js");
const fen = require("../chess/fen.js");
const fusionModule = require("./temporal-fusion.js");
const defaults = require("../util/defaults.js");

const DEFAULTS = {
    /** 至少要几帧才开始下结论（与 temporal-fusion 的 minFrames 对齐） */
    minFrames: 3,
    /** 窗口时间跨度（毫秒）—— 帧数由时间窗口封顶，不另设条数上限 */
    maxSpanMs: 1500,
    /** 同一格至少几票 */
    minAgree: 2,
    /** 投票占比下限 */
    minShare: 0.6
};

/**
 * @param options.now 取单调毫秒的函数（测试可注入）
 * @param options.modelVersion 模型标识（变了就作废历史）
 * @param options.sessionId    会话标识
 */
function createSequence(options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    const fusion = fusionModule.createFusion({
        minFrames: opts.minFrames,
        minAgree: opts.minAgree,
        minShare: opts.minShare,
        maxSpanMs: opts.maxSpanMs,
        now: opts.now
    });
    let seen = 0;
    let lastReason = "尚未采集";
    let lastVersion = "";

    /**
     * 喂一帧识别结果。
     * @param result recognizer.recognize 的返回值
     * @param info   {geometryVersion, modelVersion, sessionId, capturedAt, quality}
     */
    function observe(result, info) {
        const meta = info || {};
        if (!result || !result.ok || !result.assigned) {
            lastReason = "这一帧识别失败：" + ((result && result.reason) || "未知原因");
            return null;
        }
        const geometryVersion = meta.geometryVersion == null
            ? (result.grid && result.grid.version) : meta.geometryVersion;
        const modelVersion = meta.modelVersion == null ? (opts.modelVersion || "unknown") : meta.modelVersion;
        const sessionId = meta.sessionId == null ? (opts.sessionId || "default") : meta.sessionId;
        lastVersion = [geometryVersion, modelVersion, sessionId].join("|");
        seen++;
        fusion.add({
            cells: result.assigned,
            geometryVersion: geometryVersion,
            modelVersion: modelVersion,
            sessionId: sessionId,
            capturedAt: meta.capturedAt,
            quality: meta.quality == null ? (result.frameQuality == null ? 1 : result.frameQuality) : meta.quality
        });
        return snapshot();
    }

    /** 当前融合结果（不喂新帧也能看）。 */
    function snapshot() {
        const fused = fusion.snapshot();
        const chars = fused.cells ? assignment.cellsToChars(fused.cells) : null;
        let layout = null;
        if (chars) {
            try {
                layout = fen.layoutOf(chars, { allowUnknown: true });
            } catch (error) {
                layout = null;
            }
        }
        lastReason = fused.reason;
        return {
            stable: fused.stable,
            reason: fused.reason,
            frames: fused.frames,
            spanMs: fused.spanMs,
            stats: fused.stats,
            cells: fused.cells,
            chars: chars,
            layout: layout,
            version: lastVersion,
            seen: seen
        };
    }

    function reset(reason) {
        fusion.reset(reason);
        lastReason = reason || "已重置";
    }

    return {
        observe: observe,
        snapshot: snapshot,
        reset: reset,
        lastReason: function () { return lastReason; },
        raw: fusion
    };
}

/** 给人看的棋盘图（与 recognizer.boardText 同样的版式，但吃的是 chars）。 */
function boardText(chars) {
    if (!chars) {
        return "";
    }
    const lines = [];
    for (let row = 0; row < 10; row++) {
        const cells = [];
        for (let col = 0; col < 9; col++) {
            const ch = chars[row * 9 + col];
            cells.push(ch == null ? " . " : " " + ch + " ");
        }
        lines.push(String(row).padStart(2, " ") + " " + cells.join(""));
    }
    return lines.join("\n");
}

module.exports = {
    DEFAULTS: DEFAULTS,
    createSequence: createSequence,
    boardText: boardText
};
