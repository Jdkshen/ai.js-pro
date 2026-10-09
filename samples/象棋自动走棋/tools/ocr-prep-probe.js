// @engine quickjs
/**
 * 「给 OCR 之前先处理一下图（颜色/对比度）」真机探针。
 *
 * 起因（用户提问 2026-09-20）：`帅` 那一格真机 44ms 也读不出（黑 `将` 0.74 能读出），
 * 怀疑是**红墨迹 vs 木纹背景**在灰度下对比度不足 ⇒ 问「先做颜色处理再喂 OCR 会不会更好」。
 *
 * 这个探针把「格子的裁剪图」用 N 种预处理各跑一遍 OCR，同一帧、同一格、同一会话，
 * 唯一变量就是预处理 ⇒ 谁认得出、分数多少、多花多少时间，一目了然。
 *
 * 引擎侧关键事实（看 `modules/engine/src/main/cpp/quickjs_jni.cpp` 得到，别再猜）：
 *   · `session.detect(image, {max, region})` 的 `image` 可以是**任意帧**（clip/resize/grayscale/threshold 的产物），
 *     原生侧对 CV_8UC1/8UC3/8UC4 都做转换（`nativeOcrDetect`）⇒ 预处理图可以直接喂；
 *   · 尺寸会被 det 阶段按 `targetSize`（默认 480）归一化 ⇒「放大」本身大概率是空操作（要测的是**对比度**）；
 *   · `images.threshold` 是原生实现，**内部先转灰度**再阈值 ⇒ 不需要自己先 grayscale；
 *   · `images.inRange/adaptiveThreshold/blur` 走 Java OpenCV 互操作（`toMat`→`Imgproc`→`matToImage`），
 *     `toMat` 出来的 Mat 是 **BGRA**，而 `inRange` 的 Scalar 按 **RGB** 组装 ⇒ 通道顺序可疑，这里只做「灰度范围」用法。
 *
 * 用法：
 *   adb push 到手机 → am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity --es path <本文件>
 * 日志前缀 `[ocrprep]`，报告 + 裁剪图落 `/sdcard/xiangqi-p4/ocr-prep/<时间戳>/`。
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            // ⚠️ PC（Node）上是反斜杠、真机（QuickJS）是正斜杠 ⇒ 先统一再剥掉 /tools
            return String(__dirname).replace(/\\/g, "/").replace(/\/tools$/, "");
        }
    } catch (ignore) {
        // 没有 __dirname 就按当前目录算
    }
    return ".";
}());

function load(relative) {
    const path = BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative);
    return require(path);
}

const adapter = load("src/runtime/aijs-adapter.js");
const locateBoard = load("src/vision/locate-board.js");
const recognizeBoard = load("experiments/vision/recognize-board.js");
const detector = load("src/vision/piece-detector.js");
const cropper = load("src/vision/piece-cropper.js");
const pieceOcr = load("src/vision/piece-ocr.js");

const OUT_ROOT = "/sdcard/xiangqi-p4/ocr-prep";
/** 与 `board-scan.scan` 一致：取框心 0.70（别的都不变，唯一变量是预处理） */
const CROP_RATIO = 0.70;
/** 每个变体在同一格上跑几次（看稳定性 + 取第二次的耗时，避开首次抖动） */
const REPEATS = 2;
/** 认字门槛（与 `board-scan.DEFAULTS.minScore` 一致） */
const MIN_SCORE = 0.6;

/**
 * 预处理流水线：`steps` 按顺序执行，null = 现状（整帧 + region，不处理）。
 * 每一步都产出一个新帧，跑完统一回收（`NativeFrame` 要显式 recycle，不回收会堆在原生帧表里）。
 */
const VARIANTS = [
    { key: "raw", steps: null, note: "现状：整帧+region（彩色）" },
    { key: "clip", steps: [], note: "只裁剪，不处理" },
    { key: "gray", steps: ["gray"], note: "灰度" },
    { key: "gray2x", steps: ["up2", "gray"], note: "2×LANCZOS4 → 灰度" },
    { key: "otsu", steps: ["otsu"], note: "Otsu 二值（黑字白底）" },
    { key: "otsu2x", steps: ["up2", "otsu"], note: "2× → Otsu" },
    { key: "otsu2xi", steps: ["up2", "otsuInv"], note: "2× → Otsu 反色（白字黑底）" },
    { key: "otsu3x", steps: ["up3", "otsu"], note: "3× → Otsu" },
    { key: "gaussOtsu2x", steps: ["up2", "gauss3", "otsu"], note: "2× → 高斯3 → Otsu" },
    { key: "rangeDark", steps: ["rangeDark", "invert"], note: "暗色分割 → 反色（检验 inRange 可用性）" }
];

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[ocrprep] " + text);
    }
}

function ensureDir(dir) {
    try {
        if (typeof files !== "undefined" && files.ensureDir) {
            files.ensureDir(dir + "/");
            return true;
        }
    } catch (ignore) {
        // 忽略
    }
    return false;
}

function writeText(path, text) {
    try {
        if (typeof files !== "undefined" && files.write) {
            files.write(path, String(text));
            return true;
        }
    } catch (ignore) {
        // 忽略
    }
    return false;
}

/** 与 `ink-lookup-probe.js` 同一套：找色 + 采样 + 检测（检测只为拿框/标签） */
function hostFor(grid) {
    return {
        countColor: function (image, color, options) {
            return adapter.countColor(image, color, options);
        },
        rgbAt: function (image, x, y) {
            return adapter.rgbAt(image, x, y);
        },
        detect: function (image, options) {
            const recognized = recognizeBoard.recognizeScreen({
                grid: grid ? Object.assign({}, grid, { cols: 9, rows: 10 }) : null,
                orientation: "RED_BOTTOM",
                confidence: (options && options.confidence) || 0.22,
                autoAlign: false,
                frame: image
            });
            return detector.normalizeBoxes((recognized && recognized.native && recognized.native.list) || []);
        }
    };
}

/**
 * 按 `variant.steps` 造图；返回 `{ input, options, made, fail }`。
 * 调用方**必须**把 `made` 里的帧全部 recycle。
 */
function buildInput(image, region, variant) {
    const made = [];
    if (!variant.steps) {
        return { input: image, options: { max: 1, region: region }, made: made, fail: null };
    }
    let input = null;
    try {
        input = images.clip(image, region[0], region[1], region[2], region[3]);
    } catch (error) {
        return { input: image, options: { max: 1, region: region }, made: made,
            fail: "clip(" + region.join(",") + ")：" + error };
    }
    made.push(input);    for (let i = 0; i < variant.steps.length; i++) {
        const step = variant.steps[i];
        let next = null;
        try {
            if (step === "gray") {
                next = images.grayscale(input);
            } else if (step === "up2" || step === "up3") {
                const factor = step === "up2" ? 2 : 3;
                next = images.resize(input, [
                    Math.max(1, Math.round(input.pixelWidth * factor)),
                    Math.max(1, Math.round(input.pixelHeight * factor))
                ], "LANCZOS4");
            } else if (step === "otsu") {
                next = images.threshold(input, 0, 255, 8);          // THRESH_BINARY | THRESH_OTSU
            } else if (step === "otsuInv") {
                next = images.threshold(input, 0, 255, 9);          // THRESH_BINARY_INV | THRESH_OTSU
            } else if (step === "invert") {
                next = images.threshold(input, 128, 255, 1);        // THRESH_BINARY_INV
            } else if (step === "gauss3") {
                next = images.blur(input, 3);
            } else if (step === "rangeDark") {
                next = images.inRange(input, "#000000", "#787878"); // 暗色（墨迹）→ 255
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

/** 单变体单格跑一次 */
function runOnce(session, image, region, variant) {
    const built = buildInput(image, region, variant);
    if (built.fail) {
        recycleAll(built.made);
        return { fail: built.fail, ms: 0, prepMs: 0 };
    }
    const prepMs = Date.now();
    const buildMs = prepMs - 0;   // 占位：下面的 ms 只统计 detect，prep 单独看
    let block = null;
    let detectMs = 0;
    try {
        const started = Date.now();
        const blocks = session.detect(built.input, built.options) || [];
        detectMs = Date.now() - started;
        block = blocks[0] || null;
    } catch (error) {
        recycleAll(built.made);
        return { fail: "detect：" + error, ms: 0, prepMs: 0 };
    }
    recycleAll(built.made);
    void buildMs;
    if (!block) {
        return { text: "", score: 0, legal: false, ms: detectMs };
    }
    const score = Number(block.score == null ? block.confidence : block.score) || 0;
    const text = String(block.text || block.label || "");
    const hit = pieceOcr.classOf(text);
    return {
        text: text,
        score: score,
        legal: !!(hit && score >= MIN_SCORE),
        cls: hit ? hit.cls : null,
        ms: detectMs
    };
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

function short(one) {
    if (!one) {
        return "-";
    }
    if (one.fail) {
        return "✗(" + String(one.fail).slice(0, 60) + ")";
    }
    if (!one.text) {
        return "空";
    }
    return one.text + (one.legal ? "" : "?") + one.score.toFixed(2);
}

function main() {
    const startedAt = Date.now();
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const outDir = OUT_ROOT + "/" + stamp;
    ensureDir(outDir);
    log("开始 " + stamp);

    if (typeof $ocr === "undefined" || typeof $ocr.create !== "function") {
        log("宿主没有 $ocr");
        return;
    }
    if (!adapter.requestCapture("portrait")) {
        log("截图授权失败");
        return;
    }
    const image = adapter.capture("full", 1500);
    if (!image) {
        log("截图失败");
        return;
    }
    const located = locateBoard.locateBoardOnScreen({ orientation: "RED_BOTTOM" });
    if (!located || !located.ok) {
        log("定位失败：" + ((located && located.reason) || "无结果"));
        return;
    }
    const grid = Object.assign({}, located.grid, { version: 1, orientation: "RED_BOTTOM" });
    log("几何：原点 (" + Math.round(grid.originX) + "," + Math.round(grid.originY) + ") 格距 (" +
        grid.stepX.toFixed(1) + "," + grid.stepY.toFixed(1) + ")");

    const host = hostFor(grid);
    const boxes = host.detect(image, { confidence: 0.22 });
    const mapped = detector.mapToCells(boxes, grid);
    const cells = (mapped && mapped.cells) || [];
    const byIndex = {};
    for (let i = 0; i < cells.length; i++) {
        const one = cells[i];
        const index = one.index != null ? one.index : (one.row * 9 + one.col);
        if (index == null || byIndex[index]) {
            continue;
        }
        byIndex[index] = {
            index: index, col: one.col, row: one.row,
            box: one.box, residual: one.residual,
            label: one.box ? one.box.label : null
        };
    }
    log("检测框 " + boxes.length + " ⇒ 映射 " + Object.keys(byIndex).length + " 格");

    // 目标格：九宫 18 格（帅/将就在这儿）+ 若干带框的格子（有子才有字可认）
    const targets = [];
    const seen = {};
    for (let row = 0; row < 10; row++) {
        for (let col = 3; col <= 5; col++) {
            if ((row >= 7 && row <= 9) || row <= 2) {
                const idx = row * 9 + col;
                seen[idx] = true;
                targets.push(idx);
            }
        }
    }
    let extra = 0;
    for (let i = 0; i < 90 && extra < 12; i++) {
        if (seen[i] || !byIndex[i]) {
            continue;
        }
        seen[i] = true;
        targets.push(i);
        extra += 1;
    }
    log("目标格 " + targets.length + "（九宫 18 + 带框 " + extra + "）｜变体 " + VARIANTS.length +
        " × 重复 " + REPEATS);

    const session = $ocr.create({ backend: "ppocr" });
    if (!session) {
        log("OCR 会话建立失败");
        return;
    }
    log("OCR 会话已建立（PP-OCRv6，随 APK 打包）");

    // 目标格的裁剪框：有检测框就用框心（与 board-scan 一致），否则用格心
    const plan = [];
    for (let k = 0; k < targets.length; k++) {
        const index = targets[k];
        const cell = byIndex[index];
        const col = index % 9, row = Math.floor(index / 9);
        const box = cell
            ? cropper.boxForItem(grid, { index: index, col: col, row: row, box: cell.box, residual: cell.residual }, {})
            : cropper.cropBoxFor(grid, col, row, {});
        if (!box) {
            continue;
        }
        const regionBox = cropper.centreRegion(box, CROP_RATIO);
        if (!regionBox) {
            continue;
        }
        plan.push({
            index: index, col: col, row: row,
            // ⚠️ `$ocr.detect` 的 `region` **只认数组**，`{x,y,width,height}` 会报
            //    “region must be [x, y, width, height]”（2026-09-20 探针踩到）
            region: [regionBox.x, regionBox.y, regionBox.width, regionBox.height],
            label: cell ? cell.label : null,
            boxed: !!cell, glyph: null
        });
    }

    // 预热（模型首次调用要加载/分配，别把第一次的成绩算进对比里）
    const warm = runOnce(session, image, plan[0].region, VARIANTS[0]);
    log("预热：" + short(warm));

    const rows = [];
    for (let p = 0; p < plan.length; p++) {
        const one = plan[p];
        const runs = {};
        const parts = [];
        for (let v = 0; v < VARIANTS.length; v++) {
            const variant = VARIANTS[v];
            let last = null;
            try {
                for (let r = 0; r < REPEATS; r++) {
                    last = runOnce(session, image, one.region, variant);
                }
            } catch (error) {
                last = { fail: "runOnce：" + error, ms: 0 };
            }
            runs[variant.key] = last;
            parts.push(variant.key + "=" + short(last) + "/" + (last ? last.ms : 0) + "ms");
        }
        // 这一格「有字可认」的判据：至少一个变体认出了合法棋子字
        let anyLegal = false;
        for (let v = 0; v < VARIANTS.length; v++) {
            if (runs[VARIANTS[v].key] && runs[VARIANTS[v].key].legal) {
                anyLegal = true;
                break;
            }
        }
        rows.push({
            index: one.index, col: one.col, row: one.row,
            boxed: one.boxed, label: one.label, region: one.region, anyLegal: anyLegal, runs: runs
        });
        log("(" + one.col + "," + one.row + ")" + (one.boxed ? "" : "·空") +
            (one.label == null ? "" : "[" + one.label + "]") +
            " " + one.region[0] + "," + one.region[1] + " " + one.region[2] + "x" + one.region[3] +
            " " + parts.join(" | "));
        // 九宫格存裁剪图（人工核对用）：原图 + 每个变体的最终图
        if (typeof images !== "undefined" && images.save && (one.row <= 2 || one.row >= 7)) {
            for (let v = 0; v < VARIANTS.length; v++) {
                const variant = VARIANTS[v];
                const built = buildInput(image, one.region, variant);
                if (!built.fail) {
                    try {
                        images.save(built.input, outDir + "/" + one.col + "-" + one.row + "-" + variant.key + ".png");
                    } catch (ignore) {
                        // 存不下来就算了
                    }
                }
                recycleAll(built.made);
            }
        }
    }

    // 汇总：只在「至少一个变体认出来了」的格子上比（空格子大家都认不出，没意义）
    const summary = [];
    for (let v = 0; v < VARIANTS.length; v++) {
        const variant = VARIANTS[v];
        let legalCount = 0;
        let scoreSum = 0;
        let scoreN = 0;
        let msSum = 0;
        let msN = 0;
        let fail = null;
        for (let i = 0; i < rows.length; i++) {
            if (!rows[i].anyLegal) {
                continue;
            }
            const one = rows[i].runs[variant.key];
            if (!one || one.fail) {
                if (one && one.fail) {
                    fail = one.fail;
                }
                continue;
            }
            if (one.legal) {
                legalCount += 1;
            }
            if (one.text) {
                scoreSum += one.score;
                scoreN += 1;
            }
            if (one.ms) {
                msSum += one.ms;
                msN += 1;
            }
        }
        const scored = rows.filter(function (row) { return row.anyLegal; }).length;
        summary.push({
            key: variant.key, note: variant.note,
            scoredCells: scored, legalCount: legalCount,
            avgScore: scoreN ? scoreSum / scoreN : 0,
            avgMs: msN ? msSum / msN : 0,
            fail: fail
        });
        log("汇总 " + variant.key + "（" + variant.note + "）：识字 " + legalCount + "/" + scored +
            "｜平均分 " + (scoreN ? (scoreSum / scoreN).toFixed(3) : "-") +
            "｜平均 " + (msN ? Math.round(msSum / msN) : 0) + "ms" +
            (fail ? "｜✗ " + fail : ""));
    }

    writeText(outDir + "/report.json", JSON.stringify({
        stamp: stamp, elapsedMs: Date.now() - startedAt,
        grid: { originX: grid.originX, originY: grid.originY, stepX: grid.stepX, stepY: grid.stepY },
        cropRatio: CROP_RATIO, repeats: REPEATS, minScore: MIN_SCORE,
        variants: VARIANTS.map(function (v) { return { key: v.key, note: v.note }; }),
        summary: summary, cells: rows
    }, null, 1));
    try {
        images.save(image, outDir + "/frame.png");
    } catch (ignore) {
        // 忽略
    }
    log("报告：" + outDir + "/report.json");
    log("结束（总耗时 " + (Date.now() - startedAt) + "ms）");
}

main();
