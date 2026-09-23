// @engine quickjs
/**
 * 跨帧佐证门（「同一个解释 / 同一个应手必须连续两次成立」才放行）—— 从 auto-driver 抽出的纯门控模块。
 *
 * 两个子门（原实现直搬，行为不变）：
 *   1. `supportedByTwoFrames(baseBoard, reason)`：轮次推断的解释门。
 *      键 = 基准盘面 + 解释文本；**采样单位必须是「观测次数」**（`sample()` 回调），
 *      不能靠时间间隔 —— 自检是瞬时循环（sleep 是空函数），拿墙钟当条件会永远等不到第二帧。
 *      返回 `{ ok }` 或 `{ ok:false, wait:true }`（由调用方决定怎么计数/打日志）。
 *   2. `replyCorroborated(key)`：对方应手门。单独一帧枚举出来的应手可能是模型重复框
 *      伪影造的**假应手**，写进可靠盘面就会把整局钉死；要求连续两次验证都枚举出同一个。
 *      `key == null` 直接 `{ ok:false }`（非 wait）。
 *
 * 为什么要这两道门（2026-09-19，K40 假事实事故）：模型会给出重复检测框的伪影（同一枚黑炮
 * 被读成两个位置）→ 单帧就能推出一个**不存在的着法**；一旦被采信写进可靠盘面与票据期望值，
 * 验证阶段就永远「有多解、不唯一」，重定位也治不好。连续两帧都推出同一个解释，伪影过不了关。
 * 副作用：真实着法晚一帧（约一个 tick）被采信 —— 期间基准不变，下一帧会重推同一个差异。
 */
"use strict";

/**
 * @param options { keyOf?(board, side), sample?() -> 观测序号, }
 */
function createCrossFrameGate(options) {
    const opts = options || {};
    const keyOf = typeof opts.keyOf === "function" ? opts.keyOf
        : function (board, side) { return (board ? board.join("") : "") + "|" + (side || ""); };
    const sample = typeof opts.sample === "function" ? opts.sample : function () { return -1; };

    /** 解释门：键 + 连续帧数 + 上次采样序号 */
    let key = null;
    let samples = 0;
    let obs = -1;
    /** 应手门：键 + 连续次数 */
    let replyKey = null;
    let replyCount = 0;

    /**
     * 解释必须连续两次观测成立。
     * @returns { ok, wait? }
     */
    function supportedByTwoFrames(baseBoard, reason) {
        const nextKey = keyOf(baseBoard, "x") + "|" + (reason || "");
        const current = sample();
        if (key !== nextKey) {
            key = nextKey;
            samples = 1;
            obs = current;
        } else if (current !== obs) {
            samples += 1;
            obs = current;
        }
        if (samples < 2) {
            return { ok: false, wait: true };
        }
        return { ok: true };
    }

    /**
     * 应手也要连续两次佐证；空键直接否（不算 wait）。
     * @returns { ok, wait? }
     */
    function replyCorroborated(nextReplyKey) {
        if (nextReplyKey == null) {
            return { ok: false };
        }
        if (replyKey === nextReplyKey) {
            replyCount += 1;
        } else {
            replyKey = nextReplyKey;
            replyCount = 1;
        }
        if (replyCount < 2) {
            return { ok: false, wait: true };
        }
        return { ok: true };
    }

    /** 清空两个门的计数（基准重建等场合）。 */
    function reset(why) {
        key = null;
        samples = 0;
        obs = -1;
        replyKey = null;
        replyCount = 0;
    }

    return {
        supportedByTwoFrames: supportedByTwoFrames,
        replyCorroborated: replyCorroborated,
        reset: reset,
        snapshot: function () {
            return {
                key: key, samples: samples, obs: obs,
                replyKey: replyKey, replyCount: replyCount
            };
        }
    };
}

module.exports = { createCrossFrameGate: createCrossFrameGate };
