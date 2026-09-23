// @engine quickjs
/**
 * OCR 运行时（对应重构方案 §3.5）：**只负责「把一格图变成字形」**，不碰红黑。
 *
 * 分工（方案 §13 最终原则）：
 *   · 位置 → YOLO
 *   · 字形 → 本模块（PP-OCR）
 *   · 红黑 → piece-color.js / piece-side.js
 *   · 合法性 → board-validator.js
 *
 * 真实契约（2026-09-19 K40 实测，别再猜）：
 *   `$ocr.create({backend:"ppocr"})` → `session.detect(image, {max, region:[x,y,w,h]})`
 *   → `[{ text, score, bounds }]`，`text` 就是棋子上的**汉字**（炮/车/卒…）。
 *   ⚠️ 不是 `recognizeCell`（那是 onnx-ocr-adapter 的假想接口，会话上没有 ⇒ 永远「OCR 未提供」）。
 *
 * 阈值（方案 §3.5）：
 *   · score ≥ 0.70 ⇒ 采纳
 *   · 0.55 ≤ score < 0.70 ⇒ 采纳但标记低置信
 *   · score < 0.55 ⇒ 未知（输出 UNKNOWN，绝不猜）
 *
 * 会话必须复用（方案 §7 性能预算）：一次 `create` 不便宜，每帧重建会把整盘拖到秒级。
 */
"use strict";

const pieceOcr = require("./piece-ocr.js");

/** 采纳阈值 / 未知阈值（方案 §3.5） */
const SCORE_ACCEPT = 0.70;
const SCORE_UNKNOWN = 0.55;

/**
 * 建一个 OCR 运行时。
 *
 * @param host { createSession(options), log? }
 *        createSession 返回一个对象：{ detect(image, {max, region}), release? }
 *        —— 真机由面板包 `$ocr.create({backend:"ppocr"})`；自检传假实现即可，纯离线可测。
 * @param options { backend, maxBlocks, log }
 */
function create(host, options) {
    const opts = options || {};
    const log = (host && host.log) || opts.log || function () { };
    const api = {
        available: false,
        reason: "",
        created: 0,
        detects: 0,
        session: null
    };

    function ensureSession() {
        if (api.session) {
            return api.session;
        }
        if (!host || typeof host.createSession !== "function") {
            api.reason = "宿主没有提供 createSession（没有 OCR 能力）";
            return null;
        }
        try {
            api.session = host.createSession({ backend: opts.backend || "ppocr" });
            api.created += 1;
            api.available = !!(api.session && typeof api.session.detect === "function");
            api.reason = api.available ? "" : "会话没有 detect()";
        } catch (error) {
            api.session = null;
            api.available = false;
            api.reason = "OCR 会话建立失败：" + (error && error.message ? error.message : error);
        }
        if (api.available) {
            log("OCR 会话就绪（第 " + api.created + " 次创建，之后复用）");
        }
        return api.session;
    }

    /**
     * 认**一格**：返回字形与七类逻辑类别（**不含红黑**）。
     *
     * @return { state, glyph, cls, score, text, reason }
     *   state = "ok"      识别成功（score ≥ 0.70）
     *         | "low"     识别成功但置信偏低（0.55~0.70，调用方自己决定用不用）
     *         | "unknown" 没读到字或分不出字形（**那格到底是空还是没有，由调用方结合多帧判断**）
     *         | "error"   OCR 不可用/异常
     */
    function recognizeGlyph(image, region) {
        const session = ensureSession();
        if (!session) {
            return { state: "error", reason: api.reason || "OCR 不可用" };
        }
        if (!image || !region) {
            return { state: "error", reason: "缺图或缺区域" };
        }
        api.detects += 1;
        let blocks = null;
        try {
            blocks = session.detect(image, {
                max: opts.maxBlocks == null ? 1 : opts.maxBlocks,
                region: region
            }) || [];
        } catch (error) {
            return { state: "error", reason: "detect 异常：" + (error && error.message ? error.message : error) };
        }
        const one = blocks[0];
        if (!one) {
            // 没检测到文本行：**这一帧**看不到字。⚠️ 不能直接当成「空格」——
            // 棋子可能正在飞行（画面上隐形）、或被悬浮窗/水印挡住（2026-09-19 真机踩过：
            // 黑炮还在飞行中被判成空，把真实事实丢了）。空格的判定交给多帧/稳定帧那一层。
            return { state: "unknown", reason: "这一帧没读到字" };
        }
        const text = String(one.text || one.label || "");
        const score = Number(one.score == null ? one.confidence : one.score) || 0;
        const hit = pieceOcr.classOf(text);
        if (!hit) {
            return { state: "unknown", reason: "读到的不是棋子的字", text: text, score: score };
        }
        if (score < SCORE_UNKNOWN) {
            return { state: "unknown", reason: "置信度低于未知阈值", text: text, score: score, glyph: hit.glyph, cls: hit.cls };
        }
        return {
            state: score >= SCORE_ACCEPT ? "ok" : "low",
            glyph: hit.glyph,
            cls: hit.cls,
            score: score,
            text: text
        };
    }

    function release() {
        try {
            if (api.session && api.session.release) {
                api.session.release();
            }
        } catch (ignore) {
            // 释放失败不影响结论
        }
        api.session = null;
        api.available = false;
    }

    api.recognizeGlyph = recognizeGlyph;
    api.release = release;
    return api;
}

module.exports = {
    create: create,
    SCORE_ACCEPT: SCORE_ACCEPT,
    SCORE_UNKNOWN: SCORE_UNKNOWN
};
