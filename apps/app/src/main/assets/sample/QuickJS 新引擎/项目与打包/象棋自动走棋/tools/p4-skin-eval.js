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
const cellsContract = require("../src/vision/cells.js");

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

/**
 * 逐帧指标（执行单 §6.1）。
 * 口径要点：
 *   · `787/810` 这种是**严格含阵营格子准确率**，不是「整盘完全正确率」；
 *   · 真值未知格（'?'）不进分母，但要报「真值覆盖率」；
 *   · 空格要单列（否则一堆空格会把棋子错误冲淡）：占用格召回、错空、空位假阳性；
 *   · 修复（降级/翻色）带来的**正确与错误变化**都要记（不能只报「未知格变少了」）。
 */
function frameMetrics(expectText, gotChars, result) {
    const truth = expectText.map(function (ch) { return cellsContract.fromText(ch); });
    const got = gotChars.map(function (ch) { return cellsContract.fromText(ch); });
    const truthCounts = cellsContract.count(truth);
    const gotCounts = cellsContract.count(got);
    const skipped = truthCounts.unknown;
    const scoredCells = 90 - skipped;
    let strict = 0;
    let loose = 0;
    let colourOnly = 0;
    let truthPieces = 0;
    let occupancyHit = 0;
    let missEmpty = 0;
    let truthEmpty = 0;
    let emptyRight = 0;
    let emptyFalsePositive = 0;
    const confusion = {};
    const wrong = [];
    for (let index = 0; index < 90; index++) {
        const want = truth[index] == null ? "." : truth[index];
        const gotCh = got.length > index ? (got[index] == null ? "." : got[index]) : "?";
        if (want === "?") {
            continue;
        }
        const wantPiece = cellsContract.isPieceChar(want);
        const gotPiece = cellsContract.isPieceChar(gotCh);
        if (wantPiece) {
            truthPieces += 1;
            if (gotPiece) {
                occupancyHit += 1;
            } else {
                missEmpty += 1;
            }
        } else {
            truthEmpty += 1;
            if (gotPiece) {
                emptyFalsePositive += 1;
            } else if (gotCh === ".") {
                emptyRight += 1;
            }
        }
        const isStrict = String(want) === String(gotCh);
        const isLoose = isStrict || (wantPiece && gotPiece &&
            String(want).toLowerCase() === String(gotCh).toLowerCase());
        if (isStrict) {
            strict += 1;
        }
        if (isLoose) {
            loose += 1;
        }
        if (isLoose && !isStrict && wantPiece && gotPiece) {
            colourOnly += 1;
        }
        const key = want + "→" + gotCh;
        confusion[key] = (confusion[key] || 0) + 1;
        if (!isStrict) {
            wrong.push(cellName(index) + " " + want + "→" + gotCh);
        }
    }

    const repaired = (result && result.repaired) || null;
    const demoted = repaired && repaired.demoted ? repaired.demoted : [];
    const flipped = repaired && repaired.flipped ? repaired.flipped : [];
    let repairFixed = 0;
    let repairBroke = 0;
    for (let i = 0; i < demoted.length; i++) {
        const item = demoted[i];
        const before = item.char == null ? item.piece : item.char;
        const wasRight = String(truth[item.index] == null ? "." : truth[item.index]) === String(before == null ? "." : before);
        if (wasRight) {
            repairBroke += 1;
        } else {
            repairFixed += 1;
        }
    }
    for (let i = 0; i < flipped.length; i++) {
        const item = flipped[i];
        const indices = item.index != null ? [item.index] : (item.indices || item.cells || []);
        for (let k = 0; k < indices.length; k++) {
            const index = indices[k];
            const want = String(truth[index] == null ? "." : truth[index]);
            const wasRight = item.from != null && want === String(item.from);
            const nowRight = item.to != null && want === String(item.to);
            if (nowRight && !wasRight) {
                repairFixed += 1;
            } else if (wasRight && !nowRight) {
                repairBroke += 1;
            }
        }
    }

    const stats = (result && result.stats) || {};
    const predUnknown = inRange(result && result.unknownCells) ? result.unknownCells : 90;
    const predUnknownBefore = inRange(stats.unknownBeforeRepair) ? stats.unknownBeforeRepair : null;
    const truthComplete = skipped === 0;
    const fullBoardCorrect = truthComplete && strict === 90;
    const json = {
        name: null,
        cells: 90,
        truthKnown: 90 - skipped,
        truthUnknown: skipped,
        coverage: (90 - skipped) / 90,
        scored: scoredCells,
        strict: strict,
        loose: loose,
        colourOnly: colourOnly,
        predUnknown: predUnknown,
        predUnknownBeforeRepair: predUnknownBefore,
        predPieces: gotCounts.pieces,
        predEmpty: gotCounts.empty,
        truthPieces: truthPieces,
        occupancyRecall: truthPieces ? occupancyHit / truthPieces : 1,
        occupancyHit: occupancyHit,
        missEmpty: missEmpty,
        truthEmpty: truthEmpty,
        emptyFalsePositive: emptyFalsePositive,
        emptyRight: emptyRight,
        repair: { demoted: demoted.length, flipped: flipped.length, fixed: repairFixed, broke: repairBroke },
        truthComplete: truthComplete,
        fullBoardCorrect: fullBoardCorrect
    };
    return {
        strict: strict,
        loose: loose,
        scored: scoredCells,
        skipped: skipped,
        colourOnly: colourOnly,
        predUnknown: predUnknown,
        predUnknownBeforeRepair: predUnknownBefore,
        truthPieces: truthPieces,
        occupancyHit: occupancyHit,
        missEmpty: missEmpty,
        truthEmpty: truthEmpty,
        emptyRight: emptyRight,
        emptyFalsePositive: emptyFalsePositive,
        repairDemoted: demoted.length,
        repairFlipped: flipped.length,
        repairFixed: repairFixed,
        repairBroke: repairBroke,
        truthComplete: truthComplete,
        fullBoardCorrect: fullBoardCorrect,
        confusion: confusion,
        wrong: wrong,
        json: json
    };
}

function inRange(value) {
    return typeof value === "number" && isFinite(value);
}

function mergeConfusion(target, source) {
    const keys = Object.keys(source || {});
    for (let i = 0; i < keys.length; i++) {
        target[keys[i]] = (target[keys[i]] || 0) + source[keys[i]];
    }
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
        "｜模型=" + (options.model || "内置（assets/models/chessai_160x320_v5r.onnx）") +
        (options.inputWidth ? ("｜输入=" + options.inputWidth + "x" + options.inputHeight) : ""));

    let scored = 0;
    let hit = 0;
    let looseHit = 0;
    let total = 0;
    let skippedSum = 0;
    let unknownSum = 0;
    let unknownBeforeSum = 0;
    let colourOnlySum = 0;
    let perfect = 0;
    // 执行单 §6.1 要求的分母/覆盖率/占用格口径（不只看「格子准确率」）
    const agg = {
        frames: names.length,
        framesRecognized: 0,
        framesRejected: 0,
        rejectedReasons: [],
        framesTruthComplete: 0,
        framesFullBoardCorrect: 0,
        truthKnown: 0,
        scoredCells: 0,
        truthPieces: 0,
        occupancyHit: 0,
        missEmpty: 0,
        truthEmpty: 0,
        emptyRight: 0,
        emptyFalsePositive: 0,
        repairDemoted: 0,
        repairFlipped: 0,
        repairFixed: 0,
        repairBroke: 0,
        confusion: {},
        geometryMethods: {}
    };
    let gridCache = null;

    for (let i = 0; i < names.length; i++) {
        const name = names[i];
        const path = SKIN_DIR + name;
        const started = Date.now();
        // 几何：优先用 pickGrid（缓存框搜索：一次检测 + 纯坐标评分，不设 24 子门槛）
        // 线定位器在这套皮肤下经常只命中 7~8 条竖线就直接失败，只当兜底。
        // 会话内复用上一帧的几何（棋盘在对局中不会动），只做小幅校准（执行单 §4.2）。
        let located = null;
        let calibration = null;
        let used = null;
        let pickedImage = null;
        let pickReport = null;
        try {
            pickedImage = images.read(path);
        } catch (error) {
            pickedImage = null;
        }
        const geomMode = options.geometry || "auto";
        // 几何优先级（K40 实测，9 帧/787 可计分格）：
        //   locator + calibrateGrid → 严格 782/787 = 99.4%，纯颜色错 0
        //   pickGrid（缓存框）优先    → 严格 753/787 = 95.7%，纯颜色错 20
        // 差别就来自 calibrateGrid：它的代价函数含「修复动作数」，正好压住颜色采样点偏移。
        // 所以**质量优先：先定位器，失败再用缓存框搜索兜底**（后者比旧的每候选识别快 ~120 倍）。
        if (geomMode === "locator" || geomMode === "auto") {
            located = locateBoard.locateBoardInImage(path, {});
            if (!located.ok && geomMode === "auto") {
                console.log("SKINE " + name + " 定位器失败（" + located.reason + "），改用 pickGrid 缓存框搜索");
                located = null;
            }
        }
        if (!located && geomMode !== "locator" && pickedImage) {
            let picked = null;
            try {
                picked = board.pickGrid(pickedImage, {
                    orientation: "RED_BOTTOM",
                    confidence: options.confidence == null ? 0.25 : options.confidence,
                    model: options.model,
                    labels: options.labels,
                    inputWidth: options.inputWidth,
                    inputHeight: options.inputHeight,
                    regionMode: options.regionMode,
                    regionAspect: options.regionAspect,
                    radius: options.pickRadius == null ? 18 : options.pickRadius,
                    step: options.pickStep == null ? 4 : options.pickStep,
                    probeCropMargin: 0.5,
                    mode: options.pickMode === "detect" ? "detect" : "cached",
                    seeds: options.seeds || (gridCache ? [gridCache] : null),
                    // 会话先验：上一帧可信局面的子力数（允许吃子）；跨会话不复用
                    expectPieces: options.expectPieces === false ? null : (agg.lastPieces == null ? null : agg.lastPieces)
                });
            } catch (error) {
                picked = { ok: false, reason: "pickGrid 抛异常：" + String(error && error.message ? error.message : error) };
            }
            if (picked && picked.ok) {
                located = {
                    ok: true, method: "pick-grid-cached", grid: picked.grid,
                    imageObject: pickedImage, frameObject: pickedImage
                };
                used = picked.grid;
                gridCache = picked.grid;
                // ⚠️ calibration 保持 null：pickGrid 只负责给出**好种子**，
                //    真正决定颜色质量的是 calibrateGrid（K40 实测：跳过它 → 纯颜色错 0→77 格）。
                pickReport = picked.report || null;
                if (picked.result && picked.result.stats && picked.result.stats.pieceCells != null) {
                    agg.lastPieces = picked.result.stats.pieceCells;
                }
                console.log("SKINE " + name + " 几何 pickGrid tried=" + picked.tried +
                    " 偏移=(" + picked.report.tried + " 候选) 检测" + (picked.report.detectMs == null ? "-" : picked.report.detectMs) +
                    "ms 搜索" + picked.report.searchMs + "ms 末次识别" + picked.report.finalMs + "ms");
            } else {
                console.log("SKINE " + name + " pickGrid 失败（" + (picked && picked.reason ? picked.reason : "无") + "），改用定位器");
                try { pickedImage.recycle(); } catch (error) { /* 忽略 */ }
            }
        }
        if (!located && geomMode !== "cached") {
            located = locateBoard.locateBoardInImage(path, {});
        }
        if (!located) {
            agg.framesRejected += 1;
            agg.rejectedReasons.push(name + "：" + (geomMode === "cached" ? "pickGrid 没找到几何（已禁止回退定位器）" : located.reason));
            console.log("SKINE " + name + " 定位失败（拒绝）：" + (geomMode === "cached" ? "pickGrid 没找到几何" : located.reason));
            continue;
        }
        const base = located.grid;
        if (used == null) {
            used = base;
        }
        if (calibrate && calibration == null) {
            const calibrated = board.calibrateGrid(located.imageObject, located, {
                orientation: "RED_BOTTOM",
                confidence: options.confidence == null ? 0.25 : options.confidence,
                autoAlign: false,
                model: options.model,
                labels: options.labels,
                inputWidth: options.inputWidth,
                inputHeight: options.inputHeight,
                regionMode: options.regionMode,
                regionAspect: options.regionAspect,
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
            inputHeight: options.inputHeight,
            regionMode: options.regionMode,
            regionAspect: options.regionAspect
        }, null);

        const expect = expand(truth[name]);
        const got = result && result.chars ? result.chars.map(function (ch) { return ch == null ? "." : ch; }) : [];
        const metrics = frameMetrics(expect, result && result.chars ? result.chars : [], result);
        const geomMethod = (located && located.method) || "unknown";
        agg.geometryMethods[geomMethod] = (agg.geometryMethods[geomMethod] || 0) + 1;
        agg.framesRecognized += 1;
        agg.truthKnown += metrics.json.truthKnown;
        agg.scoredCells += metrics.scored;
        agg.truthPieces += metrics.truthPieces;
        agg.occupancyHit += metrics.occupancyHit;
        agg.missEmpty += metrics.missEmpty;
        agg.truthEmpty += metrics.truthEmpty;
        agg.emptyRight += metrics.emptyRight;
        agg.emptyFalsePositive += metrics.emptyFalsePositive;
        agg.repairDemoted += metrics.repairDemoted;
        agg.repairFlipped += metrics.repairFlipped;
        agg.repairFixed += metrics.repairFixed;
        agg.repairBroke += metrics.repairBroke;
        mergeConfusion(agg.confusion, metrics.confusion);
        if (metrics.truthComplete) { agg.framesTruthComplete += 1; }
        if (metrics.fullBoardCorrect) { agg.framesFullBoardCorrect += 1; }
        metrics.json.name = name;
        metrics.json.geometry = {
            method: geomMethod,
            version: used && used.version != null ? used.version : null,
            dx: calibration ? calibration.dx : null,
            dy: calibration ? calibration.dy : null
        };
        metrics.json.timingMs = Date.now() - started;
        metrics.json.pickReport = pickReport;
        metrics.json.recognizeTiming = result && result.timing ? result.timing : null;
        metrics.json.native = result && result.native ? {
            preprocessMs: result.native.preprocessMs,
            inferenceMs: result.native.inferenceMs,
            totalMs: result.native.totalMs
        } : null;
        metrics.json.elapsedMs = result && result.elapsedMs != null ? result.elapsedMs : null;
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
        console.log("SKINE " + name + " 口径 真值覆盖 " + metrics.json.truthKnown + "/90（未知 " + metrics.skipped +
            "）｜可计分 " + metrics.scored + "｜严格 " + metrics.strict + "｜占用格召回 " + metrics.occupancyHit + "/" +
            metrics.truthPieces + "｜错空 " + metrics.missEmpty + "｜空位假阳性 " + metrics.emptyFalsePositive +
            "｜预测UNKNOWN " + metrics.predUnknown + "（修复前 " + metrics.predUnknownBeforeRepair + "）" +
            "｜修复 降级" + metrics.repairDemoted + " 翻色" + metrics.repairFlipped + "（修好 " + metrics.repairFixed +
            " / 修坏 " + metrics.repairBroke + "）｜整盘全对=" + (metrics.fullBoardCorrect ? "是" : "否"));
        console.log("SKINE_JSON " + JSON.stringify(metrics.json));
    }

    if (scored) {
        console.log("SKINE === 盘面级（严格，含阵营，跳过交叉验证未定论的格）" + hit + "/" + total + " = " +
            (100 * hit / total).toFixed(1) + "%｜只看兵种+位置 " + looseHit + "/" + total + " = " +
            (100 * looseHit / total).toFixed(1) + "%｜纯颜色错 " + colourOnlySum + " 格｜平均未知格 " +
            (unknownSum / scored).toFixed(1) + "｜共跳过 " + skippedSum + " 格");
        console.log("SKINE === 口径（执行单 §6.1）帧 " + agg.frames + "（识别成功 " + agg.framesRecognized +
            "，拒绝 " + agg.framesRejected + "）｜真值完整帧 " + agg.framesTruthComplete +
            "｜整盘布局全对帧 " + agg.framesFullBoardCorrect + "/" + agg.framesTruthComplete +
            "｜真值覆盖 " + agg.truthKnown + "/" + (90 * agg.frames) +
            "｜格准确率（严格/仅兵种位置）" + hit + "/" + total + " 与 " + looseHit + "/" + total +
            "｜占用格召回 " + agg.occupancyHit + "/" + agg.truthPieces +
            "｜错空 " + agg.missEmpty + "｜空位假阳性 " + agg.emptyFalsePositive + "（空位正确 " + agg.emptyRight + "/" + agg.truthEmpty + "）" +
            "｜预测UNKNOWN 合计 " + unknownSum + "（修复前 " + unknownBeforeSum + "）" +
            "｜修复 降级" + agg.repairDemoted + " 翻色" + agg.repairFlipped + "（修好 " + agg.repairFixed + " / 修坏 " + agg.repairBroke + "）" +
            "｜定位方式 " + JSON.stringify(agg.geometryMethods));
        console.log("SKINE_JSON_SUMMARY " + JSON.stringify({
            frames: agg.frames,
            framesRecognized: agg.framesRecognized,
            framesRejected: agg.framesRejected,
            framesTruthComplete: agg.framesTruthComplete,
            framesFullBoardCorrect: agg.framesFullBoardCorrect,
            truthKnown: agg.truthKnown,
            truthTotal: 90 * agg.frames,
            scoredCells: agg.scoredCells,
            strictHit: hit,
            strictRate: total ? hit / total : null,
            looseHit: looseHit,
            looseRate: total ? looseHit / total : null,
            colourOnly: colourOnlySum,
            truthPieces: agg.truthPieces,
            occupancyHit: agg.occupancyHit,
            occupancyRecall: agg.truthPieces ? agg.occupancyHit / agg.truthPieces : null,
            missEmpty: agg.missEmpty,
            truthEmpty: agg.truthEmpty,
            emptyRight: agg.emptyRight,
            emptyFalsePositive: agg.emptyFalsePositive,
            predUnknown: unknownSum,
            predUnknownBeforeRepair: unknownBeforeSum,
            repair: { demoted: agg.repairDemoted, flipped: agg.repairFlipped, fixed: agg.repairFixed, broke: agg.repairBroke },
            geometryMethods: agg.geometryMethods,
            confusion: agg.confusion
        }));
    } else {
        console.log("SKINE 没有可评分的帧");
    }
    if (agg.framesRejected) {
        console.log("SKINE 被拒帧（不进成功率分母）" + agg.framesRejected + "：" + agg.rejectedReasons.join("；"));
    }
}
console.log("SKINE done");
