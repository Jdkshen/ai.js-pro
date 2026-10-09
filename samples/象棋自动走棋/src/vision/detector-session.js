// @engine quickjs
"use strict";
// 几何证据用的 detect-only 会话：不输出棋盘、不做棋子分类结论。
const adapter = require("../runtime/aijs-adapter.js");
const detectionStage = require("./detection-stage.js");
const MODEL_FILE = "assets/models/chessai_160x320_v5r.onnx";
const LABELS_FILE = "assets/models/chessai_160x320_v5r.txt";
const INPUT_WIDTH = 160;
const INPUT_HEIGHT = 320;
/** 喂矩形模型时希望的区域宽高比（见 recognizer.DEFAULTS.regionAspect 的说明） */
const REGION_ASPECT = INPUT_WIDTH / INPUT_HEIGHT;
/**
 * 检测区域怎么取："full" = 整屏（实测 97.0%，训练侧也是这套「全屏→矩形」口径）；
 * "board" = 只裁棋盘外框（实测 96.4%，比整屏差）。
 * 方形档回滚时把它设成 "board"。
 */
const REGION_MODE = "full";
const INPUT_SIZE = INPUT_WIDTH; // 方形档回滚时用（同时把 REGION_ASPECT 设为 null）
const THREADS = 4;

function modelPaths() {
    return {
        model: adapter.resolveProjectFile(MODEL_FILE),
        labels: adapter.resolveProjectFile(LABELS_FILE)
    };
}

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
    // 矩形（非方形）模型：必须显式给 inputWidth/inputHeight，否则引擎按方形 letterbox，比例就错了
    // 反面防呆（真机踩过）：传了方形的 inputSize 却又没给 inputWidth/Height 时，
    // 如果沿用默认的 160×320，方形模型会直接报
    //   OpenCV reshape2_layer: Assertion failed outTotal == inpTotal
    // —— 回滚到方形档时只改 MODEL_FILE/LABELS_FILE 是*不够*的。这里让 inputSize 自己把两个维度拉平。
    let inputWidth = opts.inputWidth == null ? INPUT_WIDTH : opts.inputWidth;
    let inputHeight = opts.inputHeight == null ? INPUT_HEIGHT : opts.inputHeight;
    if (
        opts.inputWidth == null &&
        opts.inputHeight == null &&
        opts.inputSize != null &&
        opts.inputSize !== INPUT_HEIGHT
    ) {
        inputWidth = opts.inputSize;
        inputHeight = opts.inputSize;
    }
    if (inputWidth && inputHeight && inputWidth !== inputHeight) {
        request.inputWidth = inputWidth;
        request.inputHeight = inputHeight;
    }
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
            failures.push(
                candidate.label + "（" + candidate.model + "）：" + (error && error.message ? error.message : error)
            );
        }
    }
    throw new Error("模型加载失败，已试过：" + failures.join("；"));
}

function createDetectionSession(options) {
    const base = Object.assign({}, options || {});
    const loaded = base.detector
        ? { detector: base.detector, request: base.detectorRequest || null, source: "调用方注入", failures: [] }
        : loadDetector(base);
    const detector = loaded.detector;
    const ownsDetector = !base.detector;
    let closed = false;
    let calls = 0;

    function detect(image, grid, options) {
        if (closed) {
            return { ok: false, stage: "detect", reason: "检测会话已关闭", raw: [], detections: [] };
        }
        const opts = Object.assign({}, base, options || {});
        let native = { list: [], preprocessMs: null, inferenceMs: null, totalMs: null };
        const result = detectionStage.detect({
            image: image,
            grid: grid,
            // AI.js Pro 的 region 检测会把坐标回移到全图；这是矩形模型的生产路径。
            useRegion: opts.useRegion !== false,
            detect: function (img, detectOptions) {
                native = adapter.detect(detector, img, detectOptions);
                return native.list;
            },
            crop: opts.crop,
            cropMargin: opts.cropMargin,
            regionAspect: opts.regionAspect === undefined ? REGION_ASPECT : opts.regionAspect,
            regionMode: opts.regionMode === undefined ? REGION_MODE : opts.regionMode,
            confidence: opts.confidence,
            nms: opts.nms
        });
        calls += 1;
        result.native = native;
        result.model = loaded.request;
        result.modelSource = loaded.source;
        result.modelFailures = loaded.failures;
        return result;
    }

    function close() {
        if (closed) {
            return;
        }
        closed = true;
        if (ownsDetector && detector && typeof detector.close === "function") {
            try {
                detector.close();
            } catch (ignore) {
                // 释放失败不改变检测结论；退出钩子仍继续清理其他资源。
            }
        }
    }

    return {
        detect: detect,
        close: close,
        detector: detector,
        model: loaded.request,
        modelSource: loaded.source,
        modelFailures: loaded.failures,
        calls: function () {
            return calls;
        },
        closed: function () {
            return closed;
        }
    };
}

function detectOnly(image, grid, options) {
    const opts = options || {};
    if (opts.session && typeof opts.session.detect === "function") {
        return opts.session.detect(image, grid, opts);
    }
    let session = null;
    try {
        session = createDetectionSession(opts);
        return session.detect(image, grid, opts);
    } catch (error) {
        return {
            ok: false,
            stage: "load",
            reason: "加载模型失败：" + (error && error.message ? error.message : error),
            raw: [],
            detections: []
        };
    } finally {
        if (session) {
            session.close();
        }
    }
}
module.exports = {
    MODEL_FILE: MODEL_FILE,
    LABELS_FILE: LABELS_FILE,
    modelPaths: modelPaths,
    loadDetector: loadDetector,
    createDetectionSession: createDetectionSession,
    detectOnly: detectOnly
};
