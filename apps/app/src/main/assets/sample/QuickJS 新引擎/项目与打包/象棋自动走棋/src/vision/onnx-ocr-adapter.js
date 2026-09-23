// @engine quickjs
/**
 * ⚠️ **不是公开入口（2026-09-20 明确）**：本文件是早期「ONNX 识别后端」的实验产物，
 * 当前**没有任何生产调用**（只有 `ocr-runtime.js` 的注释提到它，用来说明「不要用 recognizeCell」）。
 *
 * 业务层请一律走 `ocr-runtime.js` —— 它封的是真机契约 `$ocr.create({backend:'ppocr'}).detect(image,{max,region})`。
 * 将来若要接 ONNX 分类模型（方案 §9 阶段 C），也应当作为 `ocr-runtime.js` 的**内部后端**接入，
 * 而不是让调用方直接 require 本模块。
 */
/**
 * AI.js Pro 本地 ONNX OCR 适配层。
 *
 * 当前引擎公开的是 yolo.load/detect（OpenCV DNN）。因此 OCR 模型必须导出为
 * 可检测单字框的 ONNX（每个棋子块返回一个最高分标签）；普通 softmax 分类
 * 输出不能直接塞进 yolo.load，需等引擎提供通用 onnx.infer API 后再启用。
 */
const config = require("./ocr-config.js").CONFIG;

function create(adapter, options) {
    const opts = options || {};
    const modelPath = opts.modelPath || adapter.resolveProjectFile("assets/models/xiangqi_ocr_192.onnx");
    const labelsPath = opts.labelsPath || adapter.resolveProjectFile("assets/models/xiangqi_ocr_192.txt");
    const api = { available: false, reason: "" };
    // 优先使用 AI.js Pro 示例中的本地 PP-OCR 接口（无需额外 ONNX 文件）。
    // 示例脚本的公开契约：$ocr.create({backend:'ppocr'}).detect(image)。
    const nativeOcr = (typeof $ocr !== "undefined") ? $ocr : null;
    if (nativeOcr && typeof nativeOcr.create === "function") {
        try {
            api.session = nativeOcr.create({ backend: "ppocr" });
            api.available = true;
            api.backend = "ppocr";
            api.recognizeCell = function (image, box) {
                let crop = null;
                try {
                    crop = adapter.clip(image, box);
                    const blocks = api.session.detect(crop, { max: 1 }) || [];
                    const b = blocks[0];
                    return b ? { text: b.text || b.label || "", score: Number(b.score == null ? b.confidence : b.score) || 0 } : { text: "", score: 0 };
                } finally { try { if (crop && crop.recycle) crop.recycle(); } catch (ignore) {} }
            };
            api.close = function () { try { if (api.session && api.session.release) api.session.release(); } catch (ignore) {} };
            return api;
        } catch (error) { api.reason = "PP-OCR 初始化失败：" + (error && error.message ? error.message : error); }
    }
    if (!adapter || typeof adapter.loadDetector !== "function") {
        api.reason = "AI.js Pro 未提供 yolo.load/dnn";
        return api;
    }
    try {
        api.detector = adapter.loadDetector({
            modelPath: modelPath,
            labels: labelsPath,
            inputWidth: config.inputWidth,
            inputHeight: config.inputHeight,
            threads: opts.threads == null ? 2 : opts.threads
        });
        api.available = true;
        api.modelPath = modelPath;
        api.labelsPath = labelsPath;
    } catch (error) {
        api.reason = "OCR 模型未接入或加载失败：" + (error && error.message ? error.message : error);
        return api;
    }
    api.recognizeCell = function (image, box) {
        if (!api.available || !api.detector) return { text: "", score: 0 };
        let crop = null;
        try {
            crop = adapter.clip(image, box);
            const out = adapter.detect(api.detector, crop, { confidence: config.scoreThreshold, nms: 0.45 });
            let best = null;
            for (let i = 0; i < out.list.length; i++) {
                const item = out.list[i];
                const score = Number(item.score == null ? item.confidence : item.score) || 0;
                if (!best || score > best.score) best = { text: item.label || item.className || item.name || "", score: score };
            }
            return best || { text: "", score: 0 };
        } finally {
            try { if (crop && crop.recycle) crop.recycle(); } catch (ignore) {}
        }
    };
    api.close = function () {
        try { if (api.detector && api.detector.close) api.detector.close(); } catch (ignore) {}
        api.detector = null;
    };
    return api;
}

module.exports = { CONFIG: config, create: create };
