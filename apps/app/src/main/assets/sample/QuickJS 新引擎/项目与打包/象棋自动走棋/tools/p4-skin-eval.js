// @engine quickjs
/**
 * 盘面级评测（**设备端**，跑在真机 / PC-QuickJS 上）：
 * 对 `/sdcard/xiangqi-p4/skin/` 里的每一帧跑「定位 → 识别」，和人工核对过的真值比逐格准确率。
 *
 * 为什么要有这个：mAP 对「整盘读没读对」不敏感（历史上微调后 mAP 只差 0.006，盘面级掉了 4.4 个点）。
 * 这套皮肤（腾讯天天象棋 + K40）就是我们的交付域，必须有自己的盘面级基线。
 *
 * 文件：
 *   /sdcard/xiangqi-p4/skin-truth.json    { "skin-c.png": "rnbakab1r/9/..." }   ← 人工核对过的真值
 *   /sdcard/xiangqi-p4/skin-eval.json     { "calibrate": false, "confidence": 0.25 }（可选）
 *   /sdcard/xiangqi-p4/skin/<文件名>.png
 */
const board = require("./recognize-board.js");
const locateBoard = require("./locate-board.js");
const fen = require("../src/chess/fen.js");

const SKIN_DIR = "/sdcard/xiangqi-p4/skin/";
const TRUTH_FILE = "/sdcard/xiangqi-p4/skin-truth.json";
const OPTIONS_FILE = "/sdcard/xiangqi-p4/skin-eval.json";

function readJson(path, fallback) {
    try {
        if (!files || typeof files.read !== "function") {
            return fallback;
        }
        const text = String(files.read(path) || "").trim();
        return text ? JSON.parse(text) : fallback;
    } catch (error) {
        console.log("SKINE 读不了 " + path + "：" + String(error && error.message ? error.message : error).slice(0, 80));
        return fallback;
    }
}

/** 把布局串（含数字与 ?）展开成 90 个字符的数组，行序 = row 0 在上。 */
function expand(layout) {
    const cells = [];
    const rows = String(layout || "").split("/");
    for (let r = 0; r < rows.length; r++) {
        const row = rows[r];
        for (let i = 0; i < row.length; i++) {
            const ch = row.charAt(i);
            if (ch >= "1" && ch <= "9") {
                const count = Number(ch);
                for (let k = 0; k < count; k++) {
                    cells.push(".");
                }
            } else {
                cells.push(ch);
            }
        }
    }
    return cells;
}

function cellName(index) {
    return "(" + (index % 9) + "," + Math.floor(index / 9) + ")";
}

const truth = readJson(TRUTH_FILE, null);
const options = readJson(OPTIONS_FILE, {});
if (!truth) {
    console.log("SKINE 没有真值文件 " + TRUTH_FILE);
} else {
    const names = Object.keys(truth);
    const calibrate = options.calibrate === true;
    console.log("SKINE 开始：帧 " + names.length + " 张｜校准=" + calibrate +
        "｜confidence=" + (options.confidence == null ? 0.25 : options.confidence) +
        "｜模型=" + (options.model || "内置（assets/models/chessai_160.onnx）") +
        (options.inputWidth ? ("｜输入=" + options.inputWidth + "x" + options.inputHeight) : ""));

    let scored = 0;
    let hit = 0;
    let looseHit = 0;
    let total = 0;
    let skippedSum = 0;
    let unknownSum = 0;
    let colourOnlySum = 0;
    let perfect = 0;

    for (let i = 0; i < names.length; i++) {
        const name = names[i];
        const path = SKIN_DIR + name;
        const started = Date.now();
        const located = locateBoard.locateBoardInImage(path, {});
        if (!located.ok) {
            console.log("SKINE " + name + " 定位失败：" + located.reason);
            continue;
        }
        const base = located.grid;
        let used = base;
        let calibration = null;
        if (calibrate) {
            const calibrated = board.calibrateGrid(located.imageObject, located, {
                orientation: "RED_BOTTOM",
                confidence: options.confidence == null ? 0.25 : options.confidence,
                autoAlign: false,
                model: options.model,
                labels: options.labels,
                inputWidth: options.inputWidth,
                inputHeight: options.inputHeight,
                search: options.search || { maxShift: 18, step: 3 },
                maxCandidates: options.maxCandidates == null ? 25 : options.maxCandidates
            });
            if (calibrated.ok && calibrated.grid) {
                used = calibrated.grid;
                calibration = calibrated;
            }
        }
        const reused = {
            ok: true,
            method: calibrate ? "calibrate" : "locate",
            grid: used,
            imageObject: located.imageObject,
            frameObject: located.imageObject
        };
        const result = board.run(located.imageObject, reused, {
            orientation: "RED_BOTTOM",
            confidence: options.confidence == null ? 0.25 : options.confidence,
            autoAlign: false,
            model: options.model,
            labels: options.labels,
            inputWidth: options.inputWidth,
            inputHeight: options.inputHeight
        }, null);

        const expect = expand(truth[name]);
        const got = result && result.chars ? result.chars.map(function (ch) { return ch == null ? "." : ch; }) : [];
        let right = 0;        // 严格：要一模一样（阵营也要对）
        let rightLoose = 0;   // 只算「兵种 + 位置」，不看阵营
        let skipped = 0;      // 真值里标 '?' 的格子：交叉验证没定论，**不参与计分**
        const wrong = [];
        const colourOnly = [];
        for (let index = 0; index < 90; index++) {
            const want = expect[index] == null ? "." : expect[index];
            if (want === "?") {
                skipped += 1;
                continue;
            }
            const gotCh = got.length > index ? got[index] : "?";
            const strict = String(want) === String(gotCh);
            const loose = String(want).toLowerCase() === String(gotCh).toLowerCase();
            // "?" / "." 在宽松口径下也要算错（没认出来不是"位置对"）
            const meaningful = loose && want !== "." && gotCh !== "." && gotCh !== "?";
            if (strict) {
                right += 1;
            }
            if (meaningful || (want === gotCh)) {
                rightLoose += 1;
            }
            if (!strict) {
                wrong.push(cellName(index) + " " + want + "→" + gotCh);
                if (loose && want !== "." && gotCh !== "." && gotCh !== "?") {
                    colourOnly.push(cellName(index) + " " + want + "→" + gotCh);
                }
            }
        }
        console.log("SKINE " + name + " 布局 " + (result && result.layout ? result.layout : "（无）"));
        scored += 1;
        hit += right;
        looseHit += rightLoose;
        total += (90 - skipped);
        skippedSum += skipped;
        colourOnlySum += colourOnly.length;
        unknownSum += result && result.ok ? result.unknownCells : 90;
        if (right === 90) {
            perfect += 1;
        }
        // 诊断：错格到底是「根本没检测框」还是「有框但被判成冲突/未知」
        const details = [];
        if (result && result.assigned) {
            for (let index = 0; index < 90; index++) {
                const want = expect[index] == null ? "." : expect[index];
                const gotCh = got.length > index ? got[index] : "?";
                if (String(want) === String(gotCh)) {
                    continue;
                }
                const cell = result.assigned[index];
                const candidates = (cell.candidates || []).map(function (candidate) {
                    return candidate.label + "@" + (Math.round(candidate.score * 100) / 100);
                });
                details.push(cellName(index) + " " + want + "→" + gotCh +
                    " state=" + cell.state + " 残差=" + (Math.round((cell.residual || 0) * 100) / 100) +
                    " 候选[" + candidates.join(",") + "]");
            }
        }
        console.log("SKINE " + name +
            " 严格 " + right + "/" + (90 - skipped) + "｜不看颜色 " + rightLoose + "/" + (90 - skipped) +
            "（颜色错 " + colourOnly.length + "，跳过 " + skipped + "）" +
            " 未知=" + (result && result.ok ? result.unknownCells : "-") +
            "（修复前 " + (result && result.stats ? result.stats.unknownBeforeRepair : "-") + "）" +
            " 框=" + (result && result.stats ? result.stats.detected : "-") +
            " 冲突=" + (result && result.stats ? result.stats.conflicts : "-") +
            " 用时=" + (Date.now() - started) + "ms" +
            (calibration ? (" 校准偏移=(" + calibration.dx + "," + calibration.dy + ") tried=" + calibration.tried) : "") +
            (colourOnly.length ? ("\nSKINE 只是颜色错：" + colourOnly.join(" ")) : "") +
            (result && result.repaired && result.repaired.demoted && result.repaired.demoted.length
                ? ("\nSKINE 降级 " + result.repaired.demoted.length + " 格：" +
                   result.repaired.demoted.map(function (item) {
                       return cellName(item.index);
                   }).join(" "))
                : "") +
            (result && result.repaired && result.repaired.before && result.repaired.before.length
                ? ("\nSKINE 降级前违规：" + result.repaired.before.join("；"))
                : "") +
            (details.length ? ("\nSKINE 错格：" + details.slice(0, 12).join("\nSKINE 错格：")) : ""));
    }

    if (scored) {
        console.log("SKINE === 盘面级（严格，含阵营，跳过交叉验证未定论的格）" + hit + "/" + total + " = " +
            (100 * hit / total).toFixed(1) + "%｜只看兵种+位置 " + looseHit + "/" + total + " = " +
            (100 * looseHit / total).toFixed(1) + "%｜纯颜色错 " + colourOnlySum + " 格｜平均未知格 " +
            (unknownSum / scored).toFixed(1) + "｜共跳过 " + skippedSum + " 格");
    } else {
        console.log("SKINE 没有可评分的帧");
    }
}
console.log("SKINE done");
