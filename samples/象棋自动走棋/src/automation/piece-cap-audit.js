// @engine quickjs
/**
 * 兵种上限体检（纯函数）。
 *
 * 修的是 2026-09-25 真机 16:06 那局的「黑炮 ×3」事故：
 *   对方炮 g8→g4 吃我方炮；「起点被游戏上一步标记盖住」+「落点被画花」两条补齐路径各自把
 *   同一个子写了一处 ⇒ 盘面里黑炮 3 枚（不可能盘面）⇒ 引擎侧拒算（"黑c 有 3 个"）——
 *   但那是**算到一半才炸** ⇒ 算棋失败 → RECOVERING → 基准重建（着法历史从「完整」掉成
 *   「部分」，长将/长捉判罚全废）。在采纳盘面之前收口，就不会走到那一步。
 *
 * 规则：上限是**物理上限**（象棋无升变，一局里每种子的数量只会减少不会增加）⇒ 超限时，
 * 「相对原始读数被改写过的」那些格**必是补齐写出来的假子** ⇒ 回滚成原始读数是安全且正确的；
 * 若全部与原始读数一致（纯视觉幻影）则标记 stillOver（调用方拿去强制全盘重读）。
 *
 * 就地修改 `chars`；返回报告数组 [{ char, cap, reverted, stillOver, cells }]。
 * 纯 JS，不碰平台接口（自检直接测）。
 */
"use strict";

/** 各兵种单侧上限（与 fen.js 的 MAX_PER_TYPE、consistency.js 同口径）。 */
const MAX_PER_TYPE = { R: 2, N: 2, B: 2, A: 2, K: 1, C: 2, P: 5 };

const LETTERS = "PNRCBAKpnrcbak";

function countOf(chars, ch) {
    let n = 0;
    for (let i = 0; i < 90; i++) {
        if (chars[i] === ch) {
            n += 1;
        }
    }
    return n;
}

/**
 * @param chars     待体检的盘面（90 格，就地修改）
 * @param rawChars  原始读数（90 格；没有就传 null —— 那样只能报告、不能回滚）
 * @param options   { maxPerType?, keepIndexes?（不许回滚的格：本帧被全盘 OCR/找色/单格重拍
 *                   这类**证据**写过的格；不保护的话会把正确的纠正也顶回去，自检 3010 用例抓到过） }
 * @returns [{ char, cap, reverted（回滚格数）, stillOver（回滚后仍超限）, cells（前 4 格描述） }]
 */
function auditPieceCaps(chars, rawChars, options) {
    const opts = options || {};
    const maxPerType = opts.maxPerType || MAX_PER_TYPE;
    const keep = opts.keepIndexes || null;
    const rolled = [];
    if (!chars || chars.length !== 90) {
        return rolled;
    }
    for (let li = 0; li < LETTERS.length; li++) {
        const ch = LETTERS.charAt(li);
        const cap = maxPerType[ch.toUpperCase()];
        if (!cap || countOf(chars, ch) <= cap) {
            continue;
        }
        let reverted = 0;
        const cells = [];
        for (let i = 0; i < 90; i++) {
            if (chars[i] !== ch) {
                continue;
            }
            if (keep && keep.indexOf(i) >= 0) {
                continue;   // 证据格：这一格是「读出来的」，不是补齐猜的
            }
            const raw = rawChars ? rawChars[i] : null;
            if (raw !== ch) {
                // 这一格是「补齐/改写」写出来的 —— 回滚到原始读数（可能是 '?'、别的子、或空）
                chars[i] = raw === undefined ? null : raw;
                reverted += 1;
                if (cells.length < 4) {
                    cells.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + (raw || "空"));
                }
            }
        }
        rolled.push({
            char: ch,
            cap: cap,
            reverted: reverted,
            stillOver: countOf(chars, ch) > cap,
            cells: cells
        });
    }
    return rolled;
}

module.exports = { auditPieceCaps: auditPieceCaps, MAX_PER_TYPE: MAX_PER_TYPE };
