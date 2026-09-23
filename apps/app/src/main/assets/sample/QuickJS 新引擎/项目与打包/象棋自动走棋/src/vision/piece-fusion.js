// @engine quickjs
/**
 * ⚠️ **已弃用（2026-09-20）**：保留仅为兼容旧调用，新代码请用 `evidence-fusion.js`。
 *
 * 为什么不直接用：两者解决的不是同一件事 ——
 *   · 本模块（旧）：拿「OCR 二次确认表」去改**已有盘面**，冲突时把该格打成 `?`；
 *   · `evidence-fusion.js`（新，方案 §3.7/§3.8）：把「检测 / 字形 / 颜色」三份证据按权重合成，
 *     再过三帧投票（两帧一致才确认）。
 * 新流水线只走后者；本模块当前**没有任何生产调用**（只有文档和旧测试提到它）。
 * 真机现状：`finalScore = 检测×0.30 + 字形×0.45 + 颜色×0.25`，≥0.78 才接受。
 */
"use strict";
const ocr = require("./piece-ocr.js");

/**
 * @deprecated 用 `evidence-fusion.createFuser()`。保留这个函数只是为了不弄坏旧脚本。
 * OCR 只做二次确认：冲突时变 UNKNOWN，绝不替换成未经确认的 OCR 猜测。
 */
function fuse(result, confirmations, options) {
    const out = Object.assign({}, result || {});
    out.chars = result && result.chars ? result.chars.slice() : [];
    const conflicts = [], accepted = [];
    const threshold = options && options.threshold != null ? Number(options.threshold) : 0.78;
    const list = confirmations || [];
    for (let i = 0; i < list.length; i++) {
        const item = list[i];
        if (!item || item.index == null || !item.char) continue;
        const index = Number(item.index), detected = out.chars[index];
        if (item.score != null && Number(item.score) < threshold) continue;
        if (detected && detected !== "?" && detected !== item.char) {
            out.chars[index] = "?";
            conflicts.push({ index: index, detected: detected, ocr: item.char });
        } else if (detected === "?" || detected == null || detected === "") {
            out.chars[index] = item.char;
            accepted.push(index);
        }
    }
    let unknown = 0;
    for (let j = 0; j < out.chars.length; j++) if (out.chars[j] === "?") unknown++;
    out.unknownCells = unknown;
    out.ocr = { enabled: list.length > 0, accepted: accepted.length, conflicts: conflicts, safe: conflicts.length === 0 };
    return out;
}
module.exports = { fuse: fuse };
