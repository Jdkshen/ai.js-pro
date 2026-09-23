// @engine quickjs
/**
 * 稳定帧闸门（重构方案 §3.1「截图与稳定帧模块」）。
 *
 * 为什么必须有它（2026-09-19 K40 实拍事故）：黑方那手棋的**棋子还在飞行中**时，
 * 那一格在画面上是隐形的 ⇒ OCR / YOLO 都读不到 ⇒ 上层把「读不到」当成「空格」，
 * 把一条**真实**的已知事实丢了、还把可靠基准改错，整局随后再也解释不通。
 * 这类帧（切前台、动画中间、半截帧、悬浮窗遮挡）本来就不该进识别。
 *
 * 判定（方案给的建议值，都做成可调）：
 *   · 前台切换后先等 350~500ms，且**切回后的第一帧只预热**，不进状态机；
 *   · 连续采两帧，间隔 100~160ms，在棋盘区域算平均像素差 ≤ 6/255 才算稳定；
 *   · 最多重试 3 次；
 *   · 识别出来的未知格 > 6 ⇒ 直接丢弃该帧并重拍。
 *
 * 本模块**只做判定**，不碰截图/OCR：调用方给两帧的「棋盘区域像素采样」即可，离线可测。
 */
"use strict";

const DEFAULTS = {
    /** 切前台后等待（ms） */
    focusSettleMs: 400,
    /** 两帧之间的间隔（ms） */
    frameGapMs: 130,
    /** 棋盘区域平均像素差阈值（0~255） */
    maxMeanDiff: 6,
    /** 每轮最多重拍几次 */
    maxRetries: 3,
    /** 识别出的未知格超过这个数就丢弃该帧（方案 §3.1：>6） */
    maxUnknownCells: 6
};

/** 平均绝对差（两串采样值） */
function meanAbsDiff(a, b) {
    if (!a || !b || a.length === 0 || a.length !== b.length) {
        return null;
    }
    let sum = 0;
    for (let i = 0; i < a.length; i++) {
        sum += Math.abs(Number(a[i]) - Number(b[i]));
    }
    return sum / a.length;
}

/**
 * 建一个闸门实例：记住「刚切过前台」与重试次数这类跨帧状态。
 *
 * @param host { now?, sampleRegion?(frame, region) → [0..255 采样], log? }
 *   sampleRegion 由调用方实现（真机=按区域取灰度；自检=直接给数组），本模块只比数值。
 */
function create(host, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const h = host || {};
    const now = h.now || function () { return Date.now(); };
    const log = h.log || function () { };
    const state = {
        /** 刚切回前台的时间（0 = 没切过） */
        focusAt: 0,
        /** 本次识别已重试次数 */
        retries: 0,
        lastReason: "",
        /** 统计（面板/日志要看） */
        accepted: 0,
        rejected: 0,
        warmups: 0
    };

    /** 切前台/换几何后调用：下一帧只预热，不进识别 */
    function onFocusChange(when) {
        state.focusAt = when == null ? now() : when;
        state.retries = 0;
        log("稳定帧：刚切过前台，等 " + opts.focusSettleMs + "ms，且第一帧只预热");
    }

    /**
     * 判定两帧是否构成「稳定帧」。
     *
     * @param first  前一帧（可空：首次采集时传 null，会要求再采一帧）
     * @param second 后一帧
     * @param region 棋盘区域标识（仅用于日志/去重，可为 null）
     * @return { stable, reason, meanDiff, warmup, retry }
     */
    function check(first, second, region) {
        const at = now();
        // ① 刚切过前台：等够时间，且第一帧只预热
        if (state.focusAt > 0) {
            const waited = at - state.focusAt;
            if (waited < opts.focusSettleMs) {
                state.lastReason = "刚切前台，还差 " + Math.round(opts.focusSettleMs - waited) + "ms";
                return { stable: false, reason: state.lastReason, warmup: true, retry: state.retries };
            }
            state.warmups += 1;
            state.focusAt = 0;
            state.lastReason = "切前台后第一帧只预热，不送识别";
            return { stable: false, reason: state.lastReason, warmup: true, retry: state.retries };
        }
        // ② 需要两帧才能比
        if (!first || !second) {
            state.lastReason = "还缺一帧（稳定帧要两帧比像素差）";
            return { stable: false, reason: state.lastReason, warmup: true, retry: state.retries };
        }
        // ③ 平均像素差
        const diff = meanAbsDiff(first, second);
        // ⚠️ 必须同时挡 NaN：`diff == null` 对 NaN 是 false，而 `NaN > maxMeanDiff` 也是 false
        //    ⇒ 采样格式不对（例如传了 [r,g,b] 三元组）时会被判成**稳定**，
        //    真机表现就是日志里的「稳定（平均差 NaN）」（2026-09-20 基准工具踩到）。
        if (diff == null || !isFinite(diff)) {
            state.lastReason = "两帧采样无法比较（长度/取值不对）";
            state.rejected += 1;
            return { stable: false, reason: state.lastReason, meanDiff: null, retry: state.retries };
        }
        if (diff > opts.maxMeanDiff) {
            state.rejected += 1;
            state.retries += 1;
            state.lastReason = "画面还在动（平均差 " + diff.toFixed(1) + " > " + opts.maxMeanDiff + "）";
            return { stable: false, reason: state.lastReason, meanDiff: diff, retry: state.retries };
        }
        state.accepted += 1;
        state.lastReason = "";
        return { stable: true, reason: "", meanDiff: diff, retry: state.retries };
    }

    /** 识别完了：未知格太多也当这帧没成（方案 §3.1「>6 未知格触发重拍」） */
    function acceptResult(unknownCells) {
        const unknown = Number(unknownCells) || 0;
        if (unknown > opts.maxUnknownCells) {
            state.rejected += 1;
            state.retries += 1;
            state.lastReason = "未知格 " + unknown + " 个（> " + opts.maxUnknownCells + "），丢弃这一帧";
            return { ok: false, reason: state.lastReason, unknown: unknown };
        }
        return { ok: true, reason: "", unknown: unknown };
    }

    function exhausted() {
        return state.retries >= opts.maxRetries;
    }

    function reset() {
        state.retries = 0;
    }

    function describe() {
        return "稳定帧：采纳 " + state.accepted + " / 丢弃 " + state.rejected +
            " / 预热 " + state.warmups + (state.lastReason ? ("｜" + state.lastReason) : "");
    }

    return {
        config: opts,
        state: state,
        onFocusChange: onFocusChange,
        check: check,
        acceptResult: acceptResult,
        exhausted: exhausted,
        reset: reset,
        describe: describe
    };
}

module.exports = {
    create: create,
    meanAbsDiff: meanAbsDiff,
    DEFAULTS: DEFAULTS
};
