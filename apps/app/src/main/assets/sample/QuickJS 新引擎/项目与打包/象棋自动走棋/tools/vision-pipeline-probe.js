// @engine quickjs
/**
 * 新视觉流水线实机探针（重构方案 §8 的 `tools/vision-pipeline-probe.js`）。
 *
 * 一帧跑完整条流水线并打出可核对的证据（对应方案 §12 交付物 7/8/9）：
 *   截图 → 稳定帧 → YOLO 框 → 交点映射 → 裁剪 → OCR 字形 → 红黑颜色 → 证据融合 → 规则校验
 *
 * 输出：
 *   · logcat（前缀 `[vpipeline]`）：逐枚棋子的证据表 + 分段耗时；
 *   · `.artifacts/vision-probe/<时间戳>/frame.png`：原始帧；
 *   · `.artifacts/vision-probe/<时间戳>/report.json`：全部原始数据（性能/准确率报告由它生成）；
 *   · 校验不通过时把这一帧存进 `.artifacts/failures/`（方案 §12 交付物 8）。
 *
 * 用法：
 *   adb push 到手机 → am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity --es path <本文件>
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return __dirname.replace(/\/tools$/, "");
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
const locateBoard = load("tools/locate-board.js");
const recognizeBoard = load("tools/recognize-board.js");
const visionPipeline = load("src/vision/vision-pipeline.js");
const frameGate = load("src/vision/frame-gate.js");
const gridTracker = load("src/vision/grid-tracker.js");
const pieceOcr = load("src/vision/piece-ocr.js");
const boardValidator = load("src/vision/board-validator.js");

/** 输出目录（.artifacts 下的临时区；手机上落在工程同级） */
const OUT_ROOT = "/sdcard/xiangqi-p4/vision-probe";
const FAIL_ROOT = "/sdcard/xiangqi-p4/vision-failures";

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[vpipeline] " + text);
    }
}

function ensureDir(dir) {
    try {
        if (typeof files !== "undefined" && files.ensureDir) {
            files.ensureDir(dir + "/");
            return true;
        }
    } catch (ignore) {
        // 没 files 能力就只打日志
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

function main() {
    const startedAt = Date.now();
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const outDir = OUT_ROOT + "/" + stamp;
    ensureDir(outDir);
    log("开始：" + stamp);

    if (!adapter.requestCapture("portrait")) {
        log("截图授权失败");
        return;
    }
    // ① 稳定帧（方案 §3.1）：采两帧比像素差
    //     ⚠️ 这里还没有几何，宿主只用到 rgbAt —— 别把还没声明的 grid 传进来（TDZ 会直接抛）
    const gate = frameGate.create({ log: log });
    const frameA = adapter.capture("full", 1500);
    adapter.waitMs(130);
    const frameB = adapter.capture("full", 1500);
    const stability = gate.check(sampleRegion(host(null), frameA), sampleRegion(host(null), frameB), "full-screen");
    log("稳定帧：" + (stability.stable ? "稳定" : "不稳定") +
        "（平均差 " + (stability.meanDiff == null ? "-" : stability.meanDiff.toFixed(2)) + "）" +
        (stability.stable ? "" : " ⇒ " + stability.reason));
    if (!frameB) {
        log("截图失败");
        return;
    }
    const image = frameB;

    // ② 定位棋盘
    const locateStart = Date.now();
    const located = locateBoard.locateBoardOnScreen({ orientation: "RED_BOTTOM" });
    const locateMs = Date.now() - locateStart;
    if (!located || !located.ok) {
        log("定位失败：" + ((located && located.reason) || "无结果"));
        return;
    }
    const grid = Object.assign({}, located.grid, { version: 1, orientation: "RED_BOTTOM" });
    log("定位成功：" + locateMs + "ms｜原点 (" + Math.round(grid.originX) + "," + Math.round(grid.originY) +
        ") 格距 (" + grid.stepX.toFixed(1) + "," + grid.stepY.toFixed(1) + ")");

    // ③ OCR 会话（真机契约：$ocr.create + detect(image,{max,region})）
    let session = null;
    try {
        session = (typeof $ocr !== "undefined" && $ocr.create) ? $ocr.create({ backend: "ppocr" }) : null;
    } catch (error) {
        log("OCR 会话建立失败：" + error);
    }
    log("OCR 会话：" + (session ? "PP-OCRv6" : "不可用"));

    // ④ 跑流水线
    //    ⚠️ 必须连喂多帧：方案 §3.8 要求「最近 3 帧里两帧结果一致才确认」，
    //       只喂一帧时**按设计**全都只能是 `?`（2026-09-19 误以为是融合坏了）。
    const hostApi = host(grid);
    const pipeline = visionPipeline.create(Object.assign({}, hostApi, { ocrSession: session, log: log }));
    const rounds = 3;
    const pipelineTimes = [];
    let result = null;
    for (let round = 0; round < rounds; round++) {
        if (round > 0) {
            adapter.waitMs(180);
        }
        const frame = round === rounds - 1 ? image : adapter.capture("full", 1500);
        const startedRound = Date.now();
        result = pipeline.recognize(frame || image, grid, { side: "w" });
        pipelineTimes.push(Date.now() - startedRound);
        const fusionStats = result.fusion || {};
        log("第 " + (round + 1) + " 帧：确认 " + (fusionStats.confirmed == null ? "-" : fusionStats.confirmed) +
            " / 未定 " + (fusionStats.unknown == null ? "-" : fusionStats.unknown) +
            "｜" + pipelineTimes[pipelineTimes.length - 1] + "ms");
    }
    const pipelineMs = pipelineTimes[pipelineTimes.length - 1];
    const avgMs = Math.round(pipelineTimes.reduce(function (a, b) { return a + b; }, 0) / pipelineTimes.length);

    log("流水线：" + (result.ok ? "完成" : ("失败 " + result.reason)) + "｜末帧 " + pipelineMs +
        "ms｜平均 " + avgMs + "ms（" + rounds + " 帧）");
    log(pipeline.describe());
    log("检测框 " + (result.detection ? result.detection.boxes : "?") + " 个 ⇒ 映射 " +
        (result.detection ? result.detection.mapped : "?") + " 格（拒绝 " +
        (result.detection && result.detection.rejected ? result.detection.rejected.length : 0) + "）");

    // ⑤ 逐枚证据表
    const rows = [];
    const evidence = result.evidence || [];
    for (let i = 0; i < evidence.length; i++) {
        const one = evidence[i];
        const at = "(" + one.col + "," + one.row + ")";
        const line = at + " 类别 " + (one.cls || "?") + " 颜色 " + (one.side || "?") +
            " 框分 " + (one.detectionScore == null ? "-" : one.detectionScore.toFixed(2)) +
            " OCR " + (one.ocrScore == null ? "-" : one.ocrScore.toFixed(2)) +
            (one.text ? "（" + one.text + "）" : "") +
            " 颜色分 " + (one.colorScore == null ? "-" : one.colorScore.toFixed(2));
        rows.push(line);
    }
    log("逐枚证据（" + rows.length + " 枚）：");
    for (let i = 0; i < rows.length && i < 32; i++) {
        log("  " + rows[i]);
    }

    // ⑥ 融合结果与规则校验
    //    ⚠️ 要打**规则补全之后**的盘面：`chars` 是融合层的原始输出，帅/将那条会还是 `?`，
    //    容易让人误以为盘面没补齐（2026-09-19 自己就差点被这行误导）。
    const fused = (result.board && result.board.length === 90) ? result.board : (result.chars || []);
    let fusedChars = "";
    for (let i = 0; i < 90; i++) {
        fusedChars += (fused[i] || ".");
    }
    log("融合结果（90 格，. = 空/未确认）：" + fusedChars);
    if (result.validation) {
        log("规则校验：" + (result.validation.ok ? "通过" : ("否决 —— " + result.validation.reason)));
        if (result.validation.stats) {
            const s = result.validation.stats;
            log("  子力：红 " + s.red.total + " / 黑 " + s.black.total + " / 未知 " + s.unknown);
        }
    }

    // ⑦ 存证：截图 + 报告 JSON；校验不过再存一份失败样本
    const framePath = outDir + "/frame.png";
    let frameSaved = false;
    try {
        if (image && adapter.sizeOf) {
            const size = adapter.sizeOf(image);
            if (typeof images !== "undefined" && images.save && size) {
                images.save(image, framePath);
                frameSaved = true;
            }
        }
    } catch (error) {
        frameSaved = false;
    }
    const report = {
        stamp: stamp,
        elapsedMs: Date.now() - startedAt,
        stability: stability,
        locateMs: locateMs,
        grid: { originX: grid.originX, originY: grid.originY, stepX: grid.stepX, stepY: grid.stepY, version: grid.version },
        pipelineMs: pipelineMs,
        ok: !!result.ok,
        reason: result.reason || "",
        detection: result.detection || null,
        fusion: result.fusion || null,
        validation: result.validation ? {
            ok: result.validation.ok, reason: result.validation.reason,
            problems: result.validation.problems, stats: result.validation.stats
        } : null,
        evidence: evidence.map(function (one) {
            return {
                index: one.index, col: one.col, row: one.row, cls: one.cls, side: one.side,
                detectionScore: one.detectionScore, ocrScore: one.ocrScore, colorScore: one.colorScore,
                text: one.text, ocrState: one.ocrState, colorReason: one.colorReason
            };
        }),
        fused: fusedChars,
        stats: pipeline.stats(),
        frameSaved: frameSaved,
        framePath: frameSaved ? framePath : null
    };
    const reportPath = outDir + "/report.json";
    const saved = writeText(reportPath, JSON.stringify(report, null, 2));
    log("报告：" + (saved ? reportPath : "（写盘失败）"));
    log("截图：" + (frameSaved ? framePath : "（没存下来）"));

    if (result.validation && !result.validation.ok) {
        ensureDir(FAIL_ROOT);
        const failPath = FAIL_ROOT + "/" + stamp + ".json";
        writeText(failPath, JSON.stringify(report, null, 2));
        log("失败样本已存：" + failPath);
    }

    try {
        if (session && session.release) {
            session.release();
        }
    } catch (ignore) {
        // 忽略
    }
    log("结束（总耗时 " + (Date.now() - startedAt) + "ms）");
}

/**
 * 宿主能力：把 adapter 包成流水线要的三件事（clip / rgbAt / detect）。
 *
 * ⚠️ 检测这一层**复用 recognize-board 的检测调用**：模型是 160×320 的矩形检测器，
 *    必须按宽高比送区域（`regionAspect`/`regionMode` 那套）；自己直接 `adapter.detect(整屏)`
 *    会在原生侧撞 `reshape2_layer` 断言（2026-09-19 真机实撞）。
 *    它返回的 `result.native.list` 就是这一帧的原始 YOLO 框，正好是流水线要的输入。
 */
function host(grid) {
    let cachedFrame = null;
    let cachedBoxes = null;
    return {
        clip: function (image, box) {
            return adapter.clip(image, box);
        },
        rgbAt: function (image, x, y) {
            return adapter.rgbAt(image, x, y);
        },
        // 红黑判色走引擎找色（与面板一致；没有这个能力时流水线自己退回采样）
        countColor: function (image, color, options) {
            return adapter.countColor(image, color, options);
        },
        detect: function (image, options) {
            if (image === cachedFrame && cachedBoxes) {
                return cachedBoxes;
            }
            let boxes = [];
            try {
                const recognized = recognizeBoard.recognizeScreen({
                    grid: grid ? Object.assign({}, grid, { cols: 9, rows: 10 }) : null,
                    orientation: "RED_BOTTOM",
                    confidence: (options && options.confidence) || 0.22,
                    autoAlign: false,
                    frame: image
                });
                const raw = (recognized && recognized.native && recognized.native.list) || [];
                // 原生形态是 { bounds:{centerX,centerY,width,height}, label, score } —— 转成方案 §3.2 的统一结构
                boxes = [];
                for (let i = 0; i < raw.length; i++) {
                    const one = raw[i] || {};
                    const b = one.bounds || {};
                    const cx = b.centerX != null ? b.centerX : one.centerX;
                    const cy = b.centerY != null ? b.centerY : one.centerY;
                    const w = b.width != null ? b.width : one.width;
                    const h = b.height != null ? b.height : one.height;
                    if (!isFinite(cx) || !isFinite(cy) || !isFinite(w) || !isFinite(h)) {
                        continue;
                    }
                    boxes.push({
                        centerX: cx, centerY: cy, width: w, height: h,
                        score: one.score, label: one.label, source: "yolo"
                    });
                }
                // ⚠️ 失败也要留痕：0 个框到底是「检测真的没检出」还是「这一步就失败了」，
                //    不看这一行只能猜（2026-09-19 为此多跑了一轮真机）。
                log("检测调用：" + (recognized ? (recognized.ok ? "ok" : ("失败 " + recognized.reason)) : "没有返回值") +
                    "｜原始框 " + raw.length + " 个 ⇒ 可用 " + boxes.length + " 个" +
                    (recognized && recognized.stage ? ("｜stage=" + recognized.stage) : ""));
            } catch (error) {
                log("检测异常：" + (error && error.message ? error.message : error));
                boxes = [];
            }
            cachedFrame = image;
            cachedBoxes = boxes;
            return boxes;
        }
    };
}

/** 稳定帧采样：整屏按 24×24 网格取灰度（真机上也就几百个点） */
function sampleRegion(h, image) {
    if (!image) {
        return null;
    }
    let size = null;
    try {
        size = adapter.sizeOf(image);
    } catch (error) {
        size = null;
    }
    if (!size) {
        return null;
    }
    const cols = 24, rows = 24;
    const out = [];
    for (let r = 0; r < rows; r++) {
        for (let c = 0; c < cols; c++) {
            const x = Math.round((c + 0.5) * size.width / cols);
            const y = Math.round((r + 0.5) * size.height / rows);
            let px = null;
            try {
                px = h.rgbAt(image, x, y);
            } catch (error) {
                px = null;
            }
            out.push(px ? (0.299 * px[0] + 0.587 * px[1] + 0.114 * px[2]) : 0);
        }
    }
    return out;
}

try {
    main();
} catch (error) {
    log("异常：" + (error && error.stack ? error.stack : error));
}
