// @engine quickjs
"use strict";

/**
 * 字形分类器（重构方案 §3.5）：**只认字形，不认红黑**。
 *
 * 为什么要拆开（方案 §3.6「颜色必须独立于OCR」+ §13）：
 *   · 天天象棋里**黑方的炮同样写成「炮」**（K40 实测：黑炮 (7,2) 被 PP-OCR 读成「炮」）；
 *   · 老版这里把字形直接映射成带大小写的 FEN 字符（炮→C、砲→c），于是「黑炮」被判成红炮，
 *     再和颜色模块打架 ⇒ 2026-09-19 真机 (4,6) 那格同时出现「红炮 / 黑卒」两套说法，
 *     轮次推断彻底解释不通。
 *   · 方案要求：字形只输出 7 类（R N B A K C P），红黑交给颜色模块（piece-color/piece-side）。
 *
 * 出口是**类别**；要变成 FEN 字符时用 `applySide(cls, side)` 显式加颜色。
 * （OCR 会话与阈值在 `ocr-runtime.js`，本文件保持纯函数、离线可测。）
 */

/** 七类逻辑类别（方案 §3.5 的表，含常见异体字） */
const CLASS_OF_GLYPH = {
    "车": "R", "俥": "R", "車": "R",
    "马": "N", "傌": "N", "馬": "N",
    "相": "B", "象": "B",
    "仕": "A", "士": "A",
    "帅": "K", "帥": "K", "将": "K", "將": "K",
    "炮": "C", "砲": "C",
    "兵": "P", "卒": "P",
    // —— 真机实测的**系统性误读**（K40 / PP-OCRv6，2026-09-19）——
    // 「兵」被读成「宾」：4 格命中（(0,6)/(2,6)/(6,6)/(8,6)，分 0.74~0.84），字形上只差一个宝盖头。
    // 这类映射只回答「兵种」，红黑依旧由颜色模块决定（方案 §3.6），所以不会因此把黑卒判成红兵。
    "宾": "P",
    // 「士」被读成拉丁字母 H：K40 实测（2026-09-19）PP-OCR 给 "H" 0.85 分 —— 士的十字加底座
    // 跟 H 确实像。
    // ⚠️ 2026-09-20 用户口径：**YOLO 只负责定位（哪儿有子），兵种只能由 OCR 字形说了算**。
    //    所以这类误读要在**字形表**里收，而不是拿检测框的类别字母兜底（那是让只该管定位的
    //    模型去定兵种，判错还会把 OCR 的正确结果顶掉）。
    "H": "A", "h": "A",
    // 「帥」被读成「師」（真机 2026-09-20，K40，红帅在 (4,7)，游戏画的是**繁体「帥」**）：
    //    彩色原图读出垃圾「印市」0.49，**灰度**后读出「師」0.70 —— 两者只差一撇。
    //    「師」在象棋里根本不是一个棋子字（字形集合只有 帥/將/車/馬/炮/相/仕/兵），
    //    所以把它收成 K 是安全的：误读成「師」的东西实际上只能是「帥」。
    "師": "K"
};

/**
 * 检测模型的**类别字母**（YOLO 输出的是合并红黑的 7 类）。
 * ⚠️ 2026-09-20 起：**不参与兵种判定**（用户口径：YOLO 只用来定位，给 OCR 认）。
 * 保留它只为了诊断 —— 想验证「框与字形打架」时可以拿日志里的字对比。
 */
const CLASS_OF_LETTER = { R: "R", N: "N", B: "B", A: "A", K: "K", C: "C", P: "P" };

/** 检测框 label → { glyph?, cls }；认得就返回（glyph 为 null，它不是字形）。仅用于诊断/日志。 */
function classOfLabel(label) {
    const s = String(label == null ? "" : label).trim().toUpperCase();
    const cls = CLASS_OF_LETTER[s];
    return cls ? { glyph: null, cls: cls, source: "yolo-label" } : null;
}

const CLASS_NAME = { R: "车", N: "马", B: "相", A: "仕", K: "帅", C: "炮", P: "兵" };

/** 七类在任何一侧的数量上限（方案 §3.9 规则校验要用） */
const CLASS_LIMIT = { R: 2, N: 2, B: 2, A: 2, K: 1, C: 2, P: 5 };

const GLYPHS = Object.keys(CLASS_OF_GLYPH).join("");
const config = require("./ocr-config.js");

/** 旧的「字形→带颜色 FEN 字符」表（**已弃用**，只为兼容探针/旧调用保留） */
const ALIAS = { "帅": "K", "将": "k", "仕": "A", "士": "a", "相": "B", "象": "b", "車": "R", "车": "r", "马": "N", "馬": "n", "炮": "C", "砲": "c", "兵": "P", "卒": "p" };

/** 文本 → { glyph, cls }（**不含红黑**）；读到认不得的字返回 null，绝不猜 */
function classOf(text) {
    const s = String(text == null ? "" : text).trim();
    for (let i = 0; i < s.length; i++) {
        const ch = s.charAt(i);
        const cls = CLASS_OF_GLYPH[ch];
        if (cls) {
            return { glyph: ch, cls: cls };
        }
    }
    return null;
}

/**
 * 类别 + 阵营 → FEN 字符（红大写、黑小写）。
 * 这是**唯一**给字形加颜色的地方：颜色必须来自颜色模块/已知事实，不能来自字形。
 *
 * @param side "RED"|"BLACK"|"w"|"b"（大小写不敏感）
 */
function applySide(cls, side) {
    if (!cls) {
        return null;
    }
    const ch = String(cls).toUpperCase();
    if (!CLASS_NAME[ch]) {
        return null;
    }
    const key = String(side == null ? "" : side).toLowerCase();
    const red = key === "red" || key === "w" || key === "r";
    return red ? ch : ch.toLowerCase();
}

/**
 * 兼容旧调用：`{ glyph, cls, char }`。
 * ⚠️ 旧调用（面板）曾用「字形自带红黑」的映射，那是错的（黑炮也写「炮」）；
 *    现在 char 必须由调用方给 side，缺省按红方（只为了不崩，不应当依赖它）。
 */
function normalize(text, side) {
    const hit = classOf(text);
    if (!hit) {
        return null;
    }
    return { glyph: hit.glyph, cls: hit.cls, char: applySide(hit.cls, side || "RED") };
}

/**
 * 兼容旧探针的工具：宿主给了 `recognizeCell` 时用它认一格。
 * ⚠️ 真机路径（PP-OCR）给的是 `detect(image, {max, region})`，没有 `recognizeCell` ——
 *    那条路请用 `ocr-runtime.js`（2026-09-19 踩过：用本函数会永远返回「OCR 未提供」）。
 */
function confirmCell(api, image, box, expected, threshold) {
    if (!api || typeof api.recognizeCell !== "function") return { ok: false, reason: "OCR 未提供（PP-OCR 请用 ocr-runtime）" };
    let raw;
    try { raw = api.recognizeCell(image, box); } catch (e) { return { ok: false, reason: String(e && e.message || e) }; }
    const hit = normalize(raw && (raw.text || raw.label || raw.glyph || raw));
    const score = raw && raw.score == null ? 0 : Number(raw.score);
    if (!hit || score < (threshold == null ? 0.78 : threshold)) return { ok: false, reason: "OCR 置信度不足", score: score };
    return { ok: true, glyph: hit.glyph, char: hit.char, score: score, matches: !expected || expected === hit.char };
}

module.exports = {
    GLYPHS: GLYPHS,
    CONFIG: config,
    // 新接口（方案 §3.5）：字形 → 七类（无红黑）
    classOf: classOf,
    classOfLabel: classOfLabel,
    applySide: applySide,
    CLASS_OF_GLYPH: CLASS_OF_GLYPH,
    CLASS_OF_LETTER: CLASS_OF_LETTER,
    CLASS_NAME: CLASS_NAME,
    CLASS_LIMIT: CLASS_LIMIT,
    ALIAS: ALIAS,
    // 旧接口（兼容）
    normalize: normalize,
    confirmCell: confirmCell
};
