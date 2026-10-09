// @engine quickjs
"use strict";

// 主页面与浮窗共用参数。沿用旧存储键，已有配置无须重新填写。
const budget = require("../automation/search-budget.js");
const DEFAULTS = { mode: "watch", mySide: "w", tap: "auto", thinkMs: 5000,
    adaptiveSearch: true,
    thinkStepMs: 100, searchMode: "time", searchDepth: 12, depthTimeoutMs: 3000,
    threads: 2, hashMb: 128, contempt: 40, bookOwnMoves: 6, bookRandom: true, winSeek: false, criticalReview: true, prethink: false, openingLearning: true, replyGear: "std" };
const OPTIONS = { mode: ["watch", "advice", "auto"], mySide: ["w", "b"],
    tap: ["auto", "accessibility", "shizuku"], thinkStepMs: [100, 500, 1000],
    searchMode: ["time", "depth"], depthTimeoutMs: [3000, 6000, 10000], hashMb: [64, 128, 256, 512],
    replyGear: ["off", "quick", "std", "deep"] };
const RANGES = { thinkMs: [500, 20000], searchDepth: [6, 24], threads: [1, 8],
    contempt: [-200, 200], bookOwnMoves: [0, 12] };
/**
 * ★ 2026-10-06：对面应手预判档位（面板「策略战绩」菜单可调）。
 * 用途 = 画面画「敌1…敌N」蓝箭头、识别多解裁决、命中统计；「标准」= 原口径 900ms × 5 路。
 * 「关」= 不算对面（省一份算力，箭头退回主变第 2 手单路）。
 */
const REPLY_GEARS = {
    off: { predictReply: false, movetimeMs: 900, multiPv: 5 },
    quick: { predictReply: true, movetimeMs: 500, multiPv: 3 },
    std: { predictReply: true, movetimeMs: 900, multiPv: 5 },
    deep: { predictReply: true, movetimeMs: 1500, multiPv: 5 }
};
const REPLY_LABELS = { off: "关", quick: "快 500ms×3", std: "标准 900ms×5", deep: "深 1500ms×5" };
function replyOptions(gear) { return REPLY_GEARS[gear] || REPLY_GEARS.std; }
function replyLabel(gear) { return REPLY_LABELS[gear] || REPLY_LABELS.std; }
function store() {
    try { return storages.create("xiangqi-panel"); } catch (error) { return null; }
}
function normalize(raw) {
    const out = {};
    Object.keys(DEFAULTS).forEach(function (key) {
        let v = raw && raw[key];
        if (v == null || typeof v === "function") v = DEFAULTS[key];
        if (OPTIONS[key]) v = OPTIONS[key].indexOf(v) >= 0 ? v : DEFAULTS[key];
        else if (RANGES[key]) {
            v = Number(v);
            v = isFinite(v) ? Math.max(RANGES[key][0], Math.min(RANGES[key][1], Math.round(v))) : DEFAULTS[key];
        } else v = typeof v === "boolean" ? v : DEFAULTS[key];
        out[key] = v;
    });
    return out;
}
function read(storage) {
    const s = storage === undefined ? store() : storage;
    const raw = {};
    Object.keys(DEFAULTS).forEach(function (k) {
        try { raw[k] = s ? s.get(k, DEFAULTS[k]) : DEFAULTS[k]; } catch (error) { raw[k] = DEFAULTS[k]; }
    });
    const value = normalize(raw);
    if (s) {
        try {
            const v1 = s.get("competitiveSearchV1", false) === true;
            const v2 = s.get("competitiveSearchV2", false) === true;
            if (budget.shouldMigrateSavedSearch(value.searchMode, value.searchDepth, value.depthTimeoutMs, v1)) {
                value.searchMode = "time";
                value.thinkMs = Math.max(value.thinkMs, budget.COMPETITIVE_TIME_MS);
                s.put("searchMode", value.searchMode); s.put("thinkMs", value.thinkMs);
                s.put("competitiveSearchV1", true); s.put("competitiveSearchV2", true);
            } else if (v1 && !v2) {
                if (budget.shouldRepairLegacyTime(value.searchMode, value.thinkMs, v1, v2)) {
                    value.thinkMs = budget.COMPETITIVE_TIME_MS; s.put("thinkMs", value.thinkMs);
                }
                s.put("competitiveSearchV2", true);
            }
            if (!v1) { s.put("competitiveSearchV1", true); s.put("competitiveSearchV2", true); }
        } catch (error) { /* 只读存储仍可使用当前值。 */ }
    }
    return value;
}
function write(patch, storage) {
    const s = storage === undefined ? store() : storage;
    const value = normalize(Object.assign({}, read(s), patch));
    if (!s) return { ok: false, value: value, reason: "设置存储不可用" };
    try {
        Object.keys(patch).forEach(function (k) { if (k in DEFAULTS) s.put(k, value[k]); });
        // 用户主动选择的旧组合不应再次触发历史迁移。
        s.put("competitiveSearchV1", true); s.put("competitiveSearchV2", true);
        return { ok: true, value: value };
    } catch (error) { return { ok: false, value: value, reason: String(error) }; }
}
function engineOptions(v) { return { threads: v.threads, hashMb: v.hashMb, contempt: v.contempt }; }
function driverConfig(v) {
    const reply = replyOptions(v && v.replyGear);
    return { recordGames:true, movetimeMs: v.thinkMs, adaptiveSearch: v.adaptiveSearch, searchDepth: v.searchMode === "depth" ? v.searchDepth : 0,
        depthTimeoutMs: v.depthTimeoutMs, bookOwnMoves: v.bookOwnMoves, bookRandom: v.bookRandom,
        winSeek: v.winSeek, criticalReview:v.criticalReview, prethink:v.prethink, openingLearning:v.openingLearning, pikafish: engineOptions(v),
        predictReply: reply.predictReply, predictReplyMovetimeMs: reply.movetimeMs, predictReplyMultiPv: reply.multiPv };
}
function applyDriver(driver, v) {
    if (!driver) return;
    const reply = replyOptions(v && v.replyGear);
    const values = { setMovetime: v.thinkMs, setSearchDepth: v.searchMode === "depth" ? v.searchDepth : 0,
        setDepthTimeout: v.depthTimeoutMs, setBookOwnMoves: v.bookOwnMoves,
        setBookRandom: v.bookRandom, setWinSeek: v.winSeek,
        setPredictReply: {predictReply:reply.predictReply, movetimeMs:reply.movetimeMs, multiPv:reply.multiPv},
        setStrategy: {adaptiveSearch:v.adaptiveSearch, criticalReview:v.criticalReview, prethink:v.prethink, openingLearning:v.openingLearning}, setPikafish: engineOptions(v) };
    Object.keys(values).forEach(function (k) { if (typeof driver[k] === "function") driver[k](values[k]); });
}
function changed(a, b) { return Object.keys(DEFAULTS).filter(function (k) { return !a || a[k] !== b[k]; }); }
module.exports = { DEFAULTS: DEFAULTS, OPTIONS: OPTIONS, RANGES: RANGES, store: store,
    normalize: normalize, read: read, write: write, changed: changed,
    replyOptions: replyOptions, replyLabel: replyLabel,
    engineOptions: engineOptions, driverConfig: driverConfig, applyDriver: applyDriver };
