// @engine quickjs
/**
 * 真机入口：棋盘定位 → 识别 → FEN。
 *
 * 只截图 / 读图，**不点击棋盘**（自动落子还没接）。
 * 用法：
 *   const board = require("./tools/recognize-board.js");
 *   const out = board.recognizeImage("/sdcard/Download/chess-shot-01.png");
 *   board.describeResult(out)   // 给人看的多行文本
 *
 * 模型放在工程的 assets/models/ 下（chessai_160.onnx + chessai_160.txt 标签）。
 * 标签就是 7 个兵种字母 R N B A K C P（与 fen.js 同一套字符）。
 * ⚠️ 这个模型**没有红黑**，阵营靠格心颜色采样补（见 src/vision/piece-side.js）。
 */
"use strict";

const adapter = require("../src/runtime/aijs-adapter.js");
const locator = require("../src/vision/board-locator.js");
const recognizer = require("../src/vision/recognizer.js");
const sequenceModule = require("../src/vision/sequence.js");
const locateBoard = require("./locate-board.js");

const MODEL_FILE = "assets/models/chessai_160.onnx";
const LABELS_FILE = "assets/models/chessai_160.txt";
const INPUT_SIZE = 160;
const THREADS = 4;

/** 模型/标签的路径（磁盘优先，否则 asset://）。给界面显示用，方便看出跑的是哪份。 */
function modelPaths() {
    return {
        model: adapter.resolveProjectFile(MODEL_FILE),
        labels: adapter.resolveProjectFile(LABELS_FILE)
    };
}

/**
 * 候选路径：工程有两种跑法，`__dirname` 的含义也可能随入口而变，
 * 所以不猜一种，而是按顺序都试一遍，谁能加载就用谁（并把用的是哪个报出来）。
 */
function candidatePaths() {
    const scriptDir = adapter.scriptDir();
    const list = [];
    list.push({
        label: "脚本目录解析",
        model: adapter.resolveProjectFile(MODEL_FILE),
        labels: adapter.resolveProjectFile(LABELS_FILE)
    });
    const dir = String(scriptDir || "").replace(/\/+$/, "");
    if (dir && list[0].model.indexOf("asset://") !== 0) {
        list.push({
            label: "脚本目录 + asset://",
            model: "asset://" + dir + "/" + MODEL_FILE,
            labels: "asset://" + dir + "/" + LABELS_FILE
        });
    }
    // 打包成独立 APK 时，运行端会把整棵工程拷到磁盘（filesDir/project），路径按 cwd 解析
    list.push({
        label: "工作目录",
        model: adapter.projectPath(MODEL_FILE),
        labels: adapter.projectPath(LABELS_FILE)
    });
    return list;
}

function loadDetector(options) {
    const opts = options || {};
    const request = {
        backend: "dnn",
        inputSize: opts.inputSize == null ? INPUT_SIZE : opts.inputSize,
        threads: opts.threads == null ? THREADS : opts.threads
    };
    const candidates = opts.model
        ? [{ label: "调用方指定", model: opts.model, labels: opts.labels }]
        : candidatePaths();
    const failures = [];
    for (let i = 0; i < candidates.length; i++) {
        const candidate = candidates[i];
        try {
            // ⚠️ 适配层的键名是 modelPath（不是 model），传错会得到“yolo.load opencv 需要 model 路径”
            const full = Object.assign({}, request, {
                modelPath: candidate.model,
                labels: candidate.labels
            });
            const detector = adapter.loadDetector(full);
            return { detector: detector, request: full, source: candidate.label, failures: failures };
        } catch (error) {
            failures.push(candidate.label + "（" + candidate.model + "）：" +
                (error && error.message ? error.message : error));
        }
    }
    throw new Error("模型加载失败，已试过：" + failures.join("；"));
}

/**
 * 识别一张已保存的图片：读图 → 定位棋盘 → 识别。
 * @param path      图片路径
 * @param options.region/regionRatio  棋盘大致范围（紧贴棋盘外沿，误差 ≤ 半个格距）
 */
function recognizeImage(path, options) {
    const opts = options || {};
    const located = locateBoard.locateBoardInImage(path, opts);
    if (!located.ok) {
        return { ok: false, stage: "locate", reason: "定位失败：" + located.reason };
    }
    return run(located.imageObject, located, opts, null);
}

/** 识别当前屏幕：截图 → 定位棋盘 → 识别。 */
function recognizeScreen(options) {
    const opts = options || {};
    // 复用已校准的几何：自动走棋每一步都要看棋盘，重复定位一次要 700ms 左右，
    // 而棋盘在对局中不会动 —— 只有 RECOVERING（几何可疑）时才重新定位。
    if (opts.grid) {
        let reuseFrame = opts.frame;
        if (!reuseFrame) {
            if (!adapter.requestCapture("portrait")) {
                return { ok: false, stage: "capture", reason: "用户拒绝了截图授权" };
            }
            reuseFrame = adapter.capture("full", opts.timeoutMs == null ? 300 : opts.timeoutMs);
            if (!reuseFrame) {
                return { ok: false, stage: "capture", reason: "截图失败：没有拿到帧" };
            }
        }
        const reused = {
            ok: true,
            method: "reuse",
            reason: "复用已校准的几何（v" + opts.grid.version + "）",
            grid: opts.grid,
            frameObject: reuseFrame,
            imageObject: reuseFrame
        };
        return run(reuseFrame, reused, opts, reuseFrame);
    }
    const located = locateBoard.locateBoardOnScreen(opts);
    if (!located.ok) {
        return { ok: false, stage: "locate", reason: "定位失败：" + located.reason, timing: located.timing };
    }
    return run(opts.frame || located.frameObject, located, opts, located.frame || null);
}

function run(image, located, opts, frame) {
    const started = adapter.nowMono();
    let loaded;
    try {
        loaded = loadDetector(opts);
    } catch (error) {
        return {
            ok: false,
            stage: "load",
            reason: "加载模型失败：" + (error && error.message ? error.message : error),
            model: modelPaths(),
            located: located
        };
    }    const detector = loaded.detector;
    let native = { list: [], preprocessMs: null, inferenceMs: null, totalMs: null };
    const result = recognizer.recognize({
        image: image,
        grid: located.grid,
        // 宿主自带区域检测：传 region 就只算那块，而且返回的坐标已经移回全图
        useRegion: true,
        detect: function (img, detectOptions) {
            native = adapter.detect(detector, img, detectOptions);
            return native.list;
        },
        rgbAt: function (img, x, y) {
            return adapter.rgbAt(img, x, y);
        },
        cropMargin: opts.cropMargin,
        confidence: opts.confidence,
        minScore: opts.minScore,
        frameQuality: opts.frameQuality,
        sideSampleStep: opts.sideSampleStep,
        sideToMove: opts.sideToMove
    });
    if (detector && typeof detector.close === "function") {
        try {
            detector.close();
        } catch (error) {
            // 关不掉不影响本次结果
        }
    }
    result.located = located;
    result.model = loaded.request;
    result.modelSource = loaded.source;
    result.modelFailures = loaded.failures;
    result.native = native;
    result.frame = frame;
    result.elapsedMs = adapter.nowMono() - started;
    return result;
}

/**
 * 多帧识别（图片序列）：同一局面的几张图依次识别，按格投票出稳定局面。
 *
 * 单帧一定会错几个子（模型本身只有 mAP50 0.53），而错的那几个每帧都不一样 ——
 * 投票就能洗掉；真正会变的只有刚走的那一步。
 * @param paths 图片路径数组（同一局面、不同时刻的截图；只给一张时 = 只测链路接通）
 */
function recognizeImageSequence(paths, options) {
    const opts = options || {};
    const list = [];
    for (let i = 0; i < (paths ? paths.length : 0); i++) {
        const item = String(paths[i] || "").trim();
        if (item) {
            list.push(item);
        }
    }
    if (list.length === 0) {
        return { ok: false, stage: "input", reason: "没有给图片路径" };
    }
    const sequence = sequenceModule.createSequence({
        modelVersion: MODEL_FILE,
        sessionId: "image",
        minFrames: opts.minFrames,
        minAgree: opts.minAgree,
        minShare: opts.minShare,
        maxSpanMs: 60000
    });
    const perFrame = [];
    for (let i = 0; i < list.length; i++) {
        const result = recognizeImage(list[i], opts);
        perFrame.push({
            path: list[i],
            ok: !!result.ok,
            reason: result.reason || "",
            layout: result.layout || null,
            detectionCount: result.detectionCount || 0,
            unknownCells: result.unknownCells == null ? null : result.unknownCells,
            timing: result.timing || null,
            elapsedMs: result.elapsedMs || 0
        });
        sequence.observe(result, { geometryVersion: result.located ? result.located.grid.version : 0 });
    }
    const fused = sequence.snapshot();
    return {
        ok: fused.layout != null,
        frames: list.length,
        perFrame: perFrame,
        fusion: fused,
        reason: fused.reason
    };
}

/** 多帧识别（连拍屏幕）：连续截 N 帧识别后投票。 */
function recognizeScreenSequence(options) {
    const opts = options || {};
    const count = opts.frames == null ? 3 : opts.frames;
    const sequence = sequenceModule.createSequence({
        modelVersion: MODEL_FILE,
        sessionId: "screen",
        minFrames: opts.minFrames,
        minAgree: opts.minAgree,
        minShare: opts.minShare,
        maxSpanMs: opts.maxSpanMs == null ? 8000 : opts.maxSpanMs
    });
    const perFrame = [];
    let first = null;
    for (let i = 0; i < count; i++) {
        // 第一帧定位；后续帧复用同一个格点（棋盘不会动，省下每次 700ms）
        const result = first == null
            ? recognizeScreen(opts)
            : recognizeScreen(Object.assign({}, opts, { frame: adapter.capture("full", opts.timeoutMs), region: first.located.region }));
        if (first == null && result.ok) {
            first = result;
        }
        perFrame.push({
            ok: !!result.ok,
            reason: result.reason || "",
            layout: result.layout || null,
            detectionCount: result.detectionCount || 0,
            unknownCells: result.unknownCells == null ? null : result.unknownCells,
            elapsedMs: result.elapsedMs || 0
        });
        sequence.observe(result, {
            geometryVersion: result.located ? result.located.grid.version : 0,
            capturedAt: adapter.nowMono()
        });
    }
    const fused = sequence.snapshot();
    return { ok: fused.layout != null, frames: count, perFrame: perFrame, fusion: fused, reason: fused.reason };
}

/** 多帧结果的说明文本。 */
function describeSequence(result) {
    const lines = [];
    if (!result || !result.frames) {
        return "没有结果";
    }
    lines.push("共 " + result.frames + " 帧：");
    for (let i = 0; i < result.perFrame.length; i++) {
        const frame = result.perFrame[i];
        lines.push("  第 " + (i + 1) + " 帧：" + (frame.ok
            ? ("检测 " + frame.detectionCount + " 个框，未知格 " + frame.unknownCells + "，" + frame.elapsedMs + "ms")
            : ("失败：" + frame.reason)));
    }
    const fusion = result.fusion;
    lines.push("");
    lines.push("融合：" + (fusion.stable ? "稳定" : "还不稳定") + "（" + fusion.reason + "，窗口 " + fusion.spanMs + "ms）");
    if (fusion.stats) {
        lines.push("融合后：棋子 " + fusion.stats.pieces + " / 空 " + fusion.stats.empty + " / 未知 " + fusion.stats.unknown);
    }
    if (fusion.layout) {
        lines.push("");
        lines.push("局面（大写=红、小写=黑、?=未知）：");
        lines.push(sequenceModule.boardText(fusion.chars));
        lines.push("");
        lines.push("FEN 布局：" + fusion.layout);
    }
    return lines.join("\n");
}

/** 给人看的多行文本。 */
function describeResult(result) {
    const lines = [];
    if (!result || !result.ok) {
        const stage = result && result.stage ? "（阶段：" + result.stage + "）" : "";
        lines.push("识别失败" + stage + "：" + ((result && result.reason) || "未知原因"));
        if (result && result.located && result.located.ok) {
            lines.push("棋盘定位是成功的：" + result.located.reason);
        }
        return lines.join("\n");
    }
    const stats = result.stats;
    lines.push("棋盘：" + result.located.reason);
    // ⚠️ loaded.request 里存的是 **modelPath**（键名与 yolo.load 一致），没有 model 字段 —— 以前写成
    // result.model.model 就会打出「模型：undefined」
    const request = result.model || {};
    const modelFile = request.modelPath ? String(request.modelPath).split(/[\\/]/).pop() : "(未知)";
    lines.push("模型：" + modelFile + "（" + result.modelSource + "，输入 " + request.inputSize + "）");
    lines.push("检测：" + result.detectionCount + " 个框 → 落到格上 " + stats.placed +
        "，未知格 " + stats.unknownCells + "，淘汰(低分/出界) " +
        (stats.detected - stats.placed - stats.unknown));
    lines.push("阵营：判出 " + result.stats.sideRed + " 红 / " + result.stats.sideBlack + " 黑" +
        (result.stats.sideUnknown > 0 ? "，判不出 " + result.stats.sideUnknown + " 个" : ""));
    const timings = [];
    if (result.located.timing) {
        timings.push("定位 " + result.located.timing.locateMs + "ms");
    }
    timings.push("裁剪 " + result.timing.cropMs + "ms");
    timings.push("检测 " + result.timing.detectMs + "ms");
    if (result.native && result.native.inferenceMs != null) {
        timings.push("（推理 " + result.native.inferenceMs.toFixed(1) + "ms）");
    }
    timings.push("判色 " + result.timing.sideMs + "ms");
    lines.push("耗时：" + timings.join(" / ") + "，合计 " + result.elapsedMs + "ms");
    lines.push("");
    lines.push("局面（大写=红、小写=黑、?=未知）：");
    lines.push(recognizer.boardText(result));
    lines.push("");
    lines.push("FEN 布局：" + result.layout);
    if (result.unknownCells > 0) {
        lines.push("⚠ 有 " + result.unknownCells + " 个未知格（'?'），所以这里只给布局、不给完整 FEN");
    }
    if (result.stats.sideUnknown > 0) {
        const reasons = [];
        for (const key in result.stats.sideReasons) {
            if (Object.prototype.hasOwnProperty.call(result.stats.sideReasons, key)) {
                reasons.push(key + "（" + result.stats.sideReasons[key] + " 个）");
            }
        }
        lines.push("⚠ 有 " + result.stats.sideUnknown + " 个棋子判不出红黑： " + reasons.join("；"));
    }
    return lines.join("\n");
}

module.exports = {
    MODEL_FILE: MODEL_FILE,
    LABELS_FILE: LABELS_FILE,
    modelPaths: modelPaths,
    loadDetector: loadDetector,
    recognizeImage: recognizeImage,
    recognizeScreen: recognizeScreen,
    recognizeImageSequence: recognizeImageSequence,
    recognizeScreenSequence: recognizeScreenSequence,
    describeResult: describeResult,
    describeSequence: describeSequence
};
