// @engine quickjs
const board = require("../experiments/vision/recognize-board.js");
const out = board.recognizeScreen({
    confidence: 0.25,
    ocrConfirm: true,
    timeoutMs: 1800,
    orientation: "RED_BOTTOM"
});
console.log("BOARD_PROBE " + JSON.stringify({
    ok: out.ok,
    stage: out.stage || null,
    reason: out.reason || "",
    detections: out.detectionCount || 0,
    unknown: out.unknownCells == null ? null : out.unknownCells,
    chars: out.chars || null,
    ocr: out.ocr || null,
    ocrRuntime: out.ocrRuntime || null,
    timing: out.timing || null
}));
