// @engine quickjs
/**
 * d7 黑士「读不出来」真机定案探针（2026-09-21）。
 *
 * 素材：把发生事故的那张实帧推到 `/sdcard/xiangqi-p4/probe/scr-now.png`（1080×2400）。
 * 目标（三个问题一次问清）：
 *   ① 该格在**真机找色**下的红/黑/暗墨迹各是多少（对比 6% / 1% 两处门槛）；
 *   ② PP-OCR 对该格在**各种预处理**（原样/灰度/灰度+OTSU/放大2×/放大+OTSU/固定阈值）下读得出「士」吗；
 *   ③ 裁剪中心偏 28px（当时校准网格的偏移量）时还读不读得出。
 * 用法：adb push 本文件 + scr-now.png → am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity
 *       --es path /sdcard/xiangqi-p4/proj/tools/probe-d7-cell.js
 * 日志前缀 [d7probe]。
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return __dirname.replace(/\/tools$/, "");
        }
    } catch (ignore) { /* 默认当前目录 */ }
    return ".";
}());

function load(relative) {
    const path = BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative);
    return require(path);
}

const adapter = load("src/runtime/aijs-adapter.js");
const pieceOcr = load("src/vision/piece-ocr.js");

const PIC = "/sdcard/xiangqi-p4/probe/scr-now.png";
const MIN_SCORE = 0.6;

/** 本帧实测：棋子中心（本地 640 模型框心）＋当时校准网格点（偏 28px） */
const CELLS = [
    { name: "d7士(实测心)", cx: 427, cy: 927 },
    { name: "e8士(对照有框)", cx: 542, cy: 809 },
    { name: "c6馬(对照有框)", cx: 312, cy: 1042 },
    { name: "空d6", cx: 427, cy: 1042 },
    { name: "空e6", cx: 542, cy: 1042 },
    { name: "空d5", cx: 427, cy: 1151 }
];

/** OCR 预处理变体：[裁剪比例, 步骤...]（步骤与 decor-reread-probe 同一套 API） */
const VARIANTS = [
    { key: "原样    ", size: 0.72, steps: [] },
    { key: "灰度    ", size: 0.72, steps: ["gray"] },
    { key: "灰度OTSU", size: 0.72, steps: ["gray", "otsu"] },
    { key: "原样OTSU", size: 0.72, steps: ["otsu"] },
    { key: "2×放大  ", size: 0.72, steps: ["up2"] },
    { key: "2×+OTSU ", size: 0.72, steps: ["up2", "otsu"] },
    { key: "灰度阈130", size: 0.72, steps: ["gray", "th130"] },
    { key: "灰度阈160", size: 0.72, steps: ["gray", "th160"] },
    { key: "大框90% ", size: 0.90, steps: ["gray"] }
];

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[d7probe] " + text);
    }
}

function recycleAll(list) {
    for (let i = 0; i < list.length; i++) {
        try {
            if (list[i] && list[i].recycle) {
                list[i].recycle();
            }
        } catch (ignore) { /* 忽略 */ }
    }
}

function build(image, regionArr, steps) {
    const made = [];
    let input = null;
    try {
        input = images.clip(image, regionArr[0], regionArr[1], regionArr[2], regionArr[3]);
    } catch (error) {
        return { input: image, region: regionArr, made: made, fail: "clip：" + error };
    }
    made.push(input);
    for (let i = 0; i < steps.length; i++) {
        const step = steps[i];
        let next = null;
        try {
            if (step === "gray") {
                next = images.grayscale(input);
            } else if (step === "up2") {
                next = images.resize(input,
                    [Math.max(1, Math.round(input.pixelWidth * 2)),
                     Math.max(1, Math.round(input.pixelHeight * 2))], "LANCZOS4");
            } else if (step === "otsu") {
                next = images.threshold(input, 0, 255, 8);
            } else if (step === "th130") {
                next = images.threshold(input, 130, 255, 0);
            } else if (step === "th160") {
                next = images.threshold(input, 160, 255, 0);
            }
        } catch (error) {
            return { input: input, region: null, made: made, fail: step + "：" + error };
        }
        if (!next) {
            return { input: input, region: null, made: made, fail: step + " 不支持" };
        }
        made.push(next);
        input = next;
    }
    return { input: input, region: null, made: made, fail: null };
}

function inkCount(image, cx, cy, size, ref, tol) {
    const region = [Math.round(cx - size / 2), Math.round(cy - size / 2), size, size];
    const n = adapter.countColor(image, ref, { region: region, threshold: tol });
    return n == null ? -1 : Number(n);
}

function main() {
    let pic = null;
    try {
        pic = images.read(PIC);
    } catch (error) {
        log("读图失败：" + error);
        return;
    }
    if (!pic) {
        log("读图失败：null");
        return;
    }
    log("图已载入 " + pic.pixelWidth + "x" + pic.pixelHeight);

    // ① 各格墨迹计数：找色（红/黑，容差 24）＋ 暗色候选（3 组参考色/容差）
    const DARKS = [["#303030", 40], ["#404040", 48], ["#282830", 56]];
    for (let i = 0; i < CELLS.length; i++) {
        const c = CELLS[i];
        for (let s = 0; s < 2; s++) {
            const size = s === 0 ? 104 : 83;   // 0.9 / 0.72 档
            const area = size * size;
            const red = inkCount(pic, c.cx, c.cy, size, "#83412D", 24);
            const black = inkCount(pic, c.cx, c.cy, size, "#282830", 24);
            let darkText = "";
            for (let d = 0; d < DARKS.length; d++) {
                const dark = inkCount(pic, c.cx, c.cy, size, DARKS[d][0], DARKS[d][1]);
                darkText += "｜暗" + DARKS[d][0].slice(1) + "/" + DARKS[d][1] + "=" + dark +
                    "(" + (100 * dark / area).toFixed(2) + "%)";
            }
            log(c.name + " size=" + size + "：红=" + red + "(" + (100 * red / area).toFixed(2) + "%)" +
                " 黑=" + black + "(" + (100 * black / area).toFixed(2) + "%)" + darkText);
        }
    }

    // ② OCR 变体（对 d7 与 e8 对照各跑一套）
    if (typeof $ocr === "undefined" || typeof $ocr.create !== "function") {
        log("没有 $ocr，OCR 部分跳过");
        recycleAll([pic]);
        return;
    }
    const session = $ocr.create({ backend: "ppocr" });
    for (let ci = 0; ci < 2; ci++) {
        const c = CELLS[ci];
        for (let v = 0; v < VARIANTS.length; v++) {
            const variant = VARIANTS[v];
            const size = Math.round(115 * variant.size);
            const region = [Math.round(c.cx - size / 2), Math.round(c.cy - size / 2), size, size];
            const built = build(pic, region, variant.steps);
            if (built.fail) {
                log(c.name + "｜" + variant.key + "：✗" + built.fail);
                recycleAll(built.made);
                continue;
            }
            let block = null;
            let ms = 0;
            try {
                const started = Date.now();
                const blocks = session.detect(built.input, { max: 1 }) || [];
                ms = Date.now() - started;
                block = blocks[0] || null;
            } catch (error) {
                log(c.name + "｜" + variant.key + "：detect 异常 " + error);
                recycleAll(built.made);
                continue;
            }
            recycleAll(built.made);
            if (!block) {
                log(c.name + "｜" + variant.key + "：空（无字） " + ms + "ms");
            } else {
                const text = String(block.text || block.label || "");
                const score = Number(block.score == null ? block.confidence : block.score) || 0;
                const cls = pieceOcr.classOf ? pieceOcr.classOf(text) : null;
                log(c.name + "｜" + variant.key + "：text=" + (text || "(空)") + " score=" + score.toFixed(2) +
                    " 兵种=" + (cls || "-") + (cls && score >= MIN_SCORE ? " ✓" : " ✗") + " " + ms + "ms");
            }
        }
    }

    // ③ 偏心 +28px（当时校准网格的真实偏移）：尺寸 × 预处理 扫描 —— 找「偏心也能读对」的钥匙
    const OFF = { cx: 455, cy: 926 };
    const OFF_VARIANTS = [
        { key: "0.72原样  ", size: 0.72, steps: [] },
        { key: "0.72灰度  ", size: 0.72, steps: ["gray"] },
        { key: "0.90灰度  ", size: 0.90, steps: ["gray"] },
        { key: "1.10灰度  ", size: 1.10, steps: ["gray"] },
        { key: "1.20灰度  ", size: 1.20, steps: ["gray"] },
        { key: "0.72×2灰度", size: 0.72, steps: ["gray", "up2"] },
        { key: "1.20×2灰度", size: 1.20, steps: ["gray", "up2"] }
    ];
    for (let v = 0; v < OFF_VARIANTS.length; v++) {
        const variant = OFF_VARIANTS[v];
        const size = Math.round(115 * variant.size);
        const region = [Math.round(OFF.cx - size / 2), Math.round(OFF.cy - size / 2), size, size];
        const built = build(pic, region, variant.steps);
        if (built.fail) {
            log("偏心+28｜" + variant.key + "：✗" + built.fail);
            recycleAll(built.made);
            continue;
        }
        let block = null;
        let ms = 0;
        try {
            const started = Date.now();
            const blocks = session.detect(built.input, { max: 1 }) || [];
            ms = Date.now() - started;
            block = blocks[0] || null;
        } catch (error) { /* 忽略 */ }
        recycleAll(built.made);
        if (!block) {
            log("偏心+28｜" + variant.key + "：空（无字） " + ms + "ms");
        } else {
            const text = String(block.text || block.label || "");
            const score = Number(block.score == null ? block.confidence : block.score) || 0;
            const cls = pieceOcr.classOf ? pieceOcr.classOf(text) : null;
            log("偏心+28｜" + variant.key + "：text=" + (text || "(空)") + " score=" + score.toFixed(2) +
                " 兵种=" + (cls || "-") + " " + ms + "ms");
        }
    }

    // ③b 偏心中心（网格点）下的墨迹计数 —— 差分扫描判空用的就是网格点
    for (let s = 0; s < 2; s++) {
        const size = s === 0 ? 104 : 83;
        const area = size * size;
        const red = inkCount(pic, OFF.cx, OFF.cy, size, "#83412D", 24);
        const black = inkCount(pic, OFF.cx, OFF.cy, size, "#282830", 24);
        const dark = inkCount(pic, OFF.cx, OFF.cy, size, "#303030", 40);
        log("偏心+28 墨迹 size=" + size + "：红=" + red + "(" + (100 * red / area).toFixed(2) + "%)" +
            " 黑=" + black + "(" + (100 * black / area).toFixed(2) + "%)" +
            " 暗=" + dark + "(" + (100 * dark / area).toFixed(2) + "%)");
    }

    recycleAll([pic]);
    log("结束");
}

main();
