// @engine quickjs
/**
 * 「那一格读不出来 ⇒ 换几把钥匙重读」真机探针（用户 2026-09-20 的 ②）。
 *
 * 背景：走子动画期间（棋子被画暗 ~0.4s）那格读不出字/判不出色 ⇒ 观察降级成未知格 ⇒
 * 驱动「布局没生成」白等。要治的是**读得出来**，不是把等待缩短（三次 A/B 都证明“等”比“猜”好）。
 *
 * 做法：
 *   1. 自己用 Shizuku 走一手兵（c7→c6，前面是空位 ⇒ 一定合法、一定不损子）；
 *   2. **紧接着**连拍若干帧（走的正是驱动那条截图路径 ⇒ 采样到的“动画帧”与驱动看到的是同一类）；
 *   3. 对**落点格**跑多种预处理（裁剪/灰度/放大/放大+灰度/Otsu/放大+Otsu/收窄字区 0.5 …），
 *      逐帧打印「读到了什么字 + 分数 + 耗时」；
 *   4. 汇总：哪把钥匙、在第几帧（≈动画内/动画后）读出来了。
 *
 * 用法：adb push 到手机 → am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity --es path <本文件>
 * 日志前缀 `[reread]`。
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return String(__dirname).replace(/\\/g, "/").replace(/\/tools$/, "");
        }
    } catch (ignore) {
        // 忽略
    }
    return ".";
}());

function load(relative) {
    const path = BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative);
    return require(path);
}

const adapter = load("src/runtime/aijs-adapter.js");
const cropper = load("src/vision/piece-cropper.js");
const pieceOcr = load("src/vision/piece-ocr.js");

/** 本机已验证几何（面板 20:07 日志：起点 (81,697)、格距 (115,114)，棋子落在交点上） */
const OX = 81;
const OY = 697;
const SX = 115;
const SY = 114;
/** 走哪一手：红兵 c7 → c6（正前方空位 ⇒ 合法、不损子） */
const FROM = { col: 0, row: 6 };
const TO = { col: 0, row: 5 };
const FRAMES = 8;
const GAP_MS = 120;
const MIN_SCORE = 0.6;

const VARIANTS = [
    { key: "clip", steps: [], ratio: 0.70 },
    { key: "gray", steps: ["gray"], ratio: 0.70 },
    { key: "up2", steps: ["up2"], ratio: 0.70 },
    { key: "up2gray", steps: ["up2", "gray"], ratio: 0.70 },
    { key: "otsu", steps: ["otsu"], ratio: 0.70 },
    { key: "up2otsu", steps: ["up2", "otsu"], ratio: 0.70 },
    { key: "clip05", steps: [], ratio: 0.50 },
    { key: "gray05", steps: ["gray"], ratio: 0.50 }
];

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[reread] " + text);
    }
}

/** 忙等（QuickJS 里没有可靠的 sleep，探针只需要粗粒度节拍） */
function pause(ms) {
    const started = Date.now();
    while (Date.now() - started < ms) {
        // 空转
    }
}

function recycleAll(frames) {
    for (let i = 0; i < frames.length; i++) {
        try {
            frames[i].recycle();
        } catch (ignore) {
            // 忽略
        }
    }
}

function centreOf(col, row) {
    return { x: OX + col * SX, y: OY + row * SY };
}

/** 落点格的裁剪框：与 `piece-cropper` 的“框心 region”同一套算法，只是中心用交点。 */
function regionFor(col, row, ratio) {
    const size = Math.round(Math.min(SX, SY) * ratio);
    const c = centreOf(col, row);
    return [Math.round(c.x - size / 2), Math.round(c.y - size / 2), size, size];
}

/** 按 steps 造图（调用方负责 recycle） */
function build(image, region, steps) {
    const made = [];
    let input = null;
    try {
        input = images.clip(image, region[0], region[1], region[2], region[3]);
    } catch (error) {
        return { input: image, options: { max: 1, region: region }, made: made, fail: "clip：" + error };
    }
    made.push(input);
    for (let i = 0; i < steps.length; i++) {
        const step = steps[i];
        let next = null;
        try {
            if (step === "gray") {
                next = images.grayscale(input);
            } else if (step === "up2") {
                next = images.resize(input, [
                    Math.max(1, Math.round(input.pixelWidth * 2)),
                    Math.max(1, Math.round(input.pixelHeight * 2))
                ], "LANCZOS4");
            } else if (step === "otsu") {
                next = images.threshold(input, 0, 255, 8);      // OTSU
            }
        } catch (error) {
            return { input: input, options: { max: 1 }, made: made, fail: step + "：" + error };
        }
        if (!next) {
            return { input: input, options: { max: 1 }, made: made, fail: step + " 不支持" };
        }
        made.push(next);
        input = next;
    }
    return { input: input, options: { max: 1 }, made: made, fail: null };
}

function readOnce(session, image, region, variant) {
    const built = build(image, region, variant.steps);
    if (built.fail) {
        recycleAll(built.made);
        return { fail: built.fail };
    }
    let block = null;
    let ms = 0;
    try {
        const started = Date.now();
        const blocks = session.detect(built.input, built.options) || [];
        ms = Date.now() - started;
        block = blocks[0] || null;
    } catch (error) {
        recycleAll(built.made);
        return { fail: "detect：" + error };
    }
    recycleAll(built.made);
    if (!block) {
        return { text: "", score: 0, legal: false, ms: ms };
    }
    const score = Number(block.score == null ? block.confidence : block.score) || 0;
    const text = String(block.text || block.label || "");
    const hit = pieceOcr.classOf(text);
    return { text: text, score: score, legal: !!(hit && score >= MIN_SCORE), ms: ms };
}

function short(one) {
    if (!one) {
        return "-";
    }
    if (one.fail) {
        return "✗" + String(one.fail).slice(0, 28);
    }
    if (!one.text) {
        return "空";
    }
    return one.text + (one.legal ? "" : "?") + one.score.toFixed(2);
}

function main() {
    log("开始（几何 origin(" + OX + "," + OY + ") step(" + SX + "," + SY + ")）");
    if (typeof $ocr === "undefined" || typeof $ocr.create !== "function") {
        log("宿主没有 $ocr");
        return;
    }
    if (!adapter.requestCapture("portrait")) {
        log("截图授权失败");
        return;
    }
    let tap = null;
    try {
        tap = adapter.createTap({ pressMs: 120 }).tap;
    } catch (error) {
        log("建立点击失败：" + error);
        return;
    }
    const a = centreOf(FROM.col, FROM.row);
    const b = centreOf(TO.col, TO.row);
    log("点击 c7(" + a.x + "," + a.y + ") → c6(" + b.x + "," + b.y + ")");
    try {
        tap(a.x, a.y);
    } catch (error) {
        log("第一次点击失败：" + error);
    }
    pause(320);
    try {
        tap(b.x, b.y);
    } catch (error) {
        log("第二次点击失败：" + error);
    }
    const session = $ocr.create({ backend: "ppocr" });
    if (!session) {
        log("OCR 会话建立失败");
        return;
    }
    const shots = [];
    for (let i = 0; i < FRAMES; i++) {
        const shotAt = Date.now();
        let image = null;
        try {
            image = adapter.capture("full", 1200);
        } catch (error) {
            image = null;
        }
        if (image) {
            shots.push({ i: i, ms: shotAt, image: image });
        }
        pause(GAP_MS);
    }
    log("连拍 " + shots.length + " 帧（间隔 " + GAP_MS + "ms + 截图耗时）");
    let hits = {};
    for (let s = 0; s < shots.length; s++) {
        const shot = shots[s];
        const after = shot.ms - (shots[0] ? shots[0].ms : shot.ms);
        const parts = [];
        for (let v = 0; v < VARIANTS.length; v++) {
            const variant = VARIANTS[v];
            const region = regionFor(TO.col, TO.row, variant.ratio);
            const one = readOnce(session, shot.image, region, variant);
            parts.push(variant.key + "=" + short(one));
            if (one && one.legal) {
                hits[variant.key] = (hits[variant.key] || 0) + 1;
            }
        }
        log("帧" + (s + 1) + " (+" + after + "ms) " + parts.join("  "));
    }
    const keys = Object.keys(hits);
    log("=== 汇总：能读出「" + (hits && keys.length ? keys.join("、") : "（无）") + "」 ===");
    for (let v = 0; v < VARIANTS.length; v++) {
        log("  " + VARIANTS[v].key + " 命中 " + (hits[VARIANTS[v].key] || 0) + " 帧");
    }
    for (let s = 0; s < shots.length; s++) {
        try {
            shots[s].image.recycle();
        } catch (ignore) {
            // 忽略
        }
    }
    log("结束");
}

main();
