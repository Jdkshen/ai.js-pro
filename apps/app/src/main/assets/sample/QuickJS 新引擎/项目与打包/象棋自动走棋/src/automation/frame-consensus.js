// @engine quickjs
/**
 * 连续帧共识账本（「同一格连续 N 帧说同一个字」的闸门）—— 从 auto-driver 抽出的纯簿记模块。
 *
 * 语义（原实现直搬，行为不变）：
 *   · `beginFrame(frameId)` 开一轮询问：`frameId != null`（面板给了 `at`，命中缓存时它不变）
 *     用它当帧标识；没给（自检/夹具）用内部序号 +1 当一帧；
 *   · `vote(index, ch, needed)` 投一票：
 *     - 同一帧重复问**不会**把「连续帧数」刷上去（frameKey 相同）；
 *     - 与上一票同字 → 跨帧则 +1；与上一票**矛盾**（同一帧里同格给出不同字）→ unstable，
 *       帧数重置为 1；不 stable 的票不能算「连续」；
 *     - 返回 `{ ok: frames >= needed, frames, needed, unstable }`。
 *   · `drop(index)` / `clear()`：那一格的子走了（或基准换代）时作废对应共识。
 */
"use strict";

/**
 * @param options { seq? }（seq 起始序号，测试可注入）
 */
function createFrameConsensus(options) {
    const opts = options || {};
    let seq = Number(opts.seq || 0) || 0;

    const gate = {};       // index -> { char, frames, frameKey, unstable }
    let frameKey = null;   // 本轮询问的帧标识

    /** 开一轮询问；返回帧标识（`frameId` 给了就用它，否则内部序号 +1）。 */
    function beginFrame(frameId) {
        frameKey = frameId != null ? ("t" + frameId) : ("q" + (++seq));
        return frameKey;
    }

    /**
     * 投一票：同格同字跨帧 +1；同帧重复不刷数；矛盾票标记 unstable 并重置为 1。
     * @returns { ok, frames, needed, unstable }
     */
    function vote(index, ch, needed) {
        const prev = gate[index];
        let frames = 1;
        let unstable = false;
        if (prev && prev.char === ch) {
            frames = prev.frameKey === frameKey ? prev.frames : prev.frames + 1;
            // 连续两帧同票才算「稳」；单票重现不算
            unstable = !!prev.unstable && frames < 2;
        } else if (prev && prev.frameKey !== frameKey) {
            // 这一票与上一票**矛盾**（同格同一帧里给出不同字）⇒ 这格的字形/颜色证据自己不稳
            unstable = true;
        }
        gate[index] = { char: ch, frames: frames, frameKey: frameKey, unstable: unstable };
        return { ok: frames >= needed, frames: frames, needed: needed, unstable: unstable };
    }

    /** 那一格的子走了 ⇒ 共识一并作废（否则上一段共识会在下一枚子到达时被直接拿来用）。 */
    function drop(index) {
        delete gate[index];
    }

    /** 基准换代（换边/新会话/重建/停止…）⇒ 全部作废。 */
    function clear() {
        const keys = Object.keys(gate);
        for (let i = 0; i < keys.length; i++) {
            delete gate[keys[i]];
        }
    }

    return {
        beginFrame: beginFrame,
        vote: vote,
        drop: drop,
        clear: clear,
        snapshot: function () {
            return {
                frameKey: frameKey,
                gate: JSON.parse(JSON.stringify(gate))
            };
        }
    };
}

module.exports = { createFrameConsensus: createFrameConsensus };
