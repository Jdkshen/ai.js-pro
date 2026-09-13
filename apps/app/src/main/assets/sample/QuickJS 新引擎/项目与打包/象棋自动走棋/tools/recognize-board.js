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
    lines.push("模型：" + result.model.model + "（路径来源：" + result.modelSource + "）");
    lines.push("检测：" + result.detectionCount + " 个框 → 落到格上 " + stats.placed +
        "，未知格 " + stats.unknownCells + "，淘汰(低分/出界) " +
        (stats.detected - stats.placed - stats.unknown));
    lines.push("阵营：判出 " + result.sides.length + " 个，其中判不出 " + stats.sideUnknown + " 个");
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
        lines.push("⚠ 有 " + result.stats.sideUnknown + " 个棋子判不出红黑（棋子颜色太暗/太淡），这些格显示为 '?'");
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
    describeResult: describeResult
};
