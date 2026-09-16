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
 * 模型放在工程的 assets/models/ 下（v5r 矩形档 chessai_160x320_v5r.onnx + chessai_160x320_v5r.txt 标签）。
 * 标签就是 7 个兵种字母 R N B A K C P（与 fen.js 同一套字符）。
 * ⚠️ 这个模型**没有红黑**，阵营靠格心颜色采样补（见 src/vision/piece-side.js）。
 */
"use strict";

const adapter = require("../src/runtime/aijs-adapter.js");
const locator = require("../src/vision/board-locator.js");
const recognizer = require("../src/vision/recognizer.js");
const sequenceModule = require("../src/vision/sequence.js");
const locateBoard = require("./locate-board.js");
const geometry = require("../src/vision/geometry.js");
const fen = require("../src/chess/fen.js");
const cells = require("../src/vision/cells.js");
const gridSearch = require("../src/vision/grid-search.js");

/**
 * 内置模型（v5r，2026-09-13 接入）：
 *   · 默认用**矩形档** `chessai_160x320_v5r.onnx`（输入 1×3×320×160，即宽 160 高 320），
 *     并配 **整屏入检**（`REGION_MODE = "full"`）：K40 冻结帧实测（9 帧 /787 格，人工真值）
 *     整屏→矩形 763/787 = 97.0% ＞ 裁棋盘→矩形 759/787 = 96.4%，
 *     而且这正好是训练侧的评测口径（电脑端 xq36：全屏→矩形 0.969）。
 *   · 方形档 `chessai_160_v5r.onnx`（1×3×160×160）作为回滚/对照（同套帧 771/787 = 98.0%，
 *     ⚠️ 已不再随包（省 APK 体积），要从 `.artifacts/model-sync/` 拷回 assets/models/ 才能用）
 *     纯颜色错 0）：改下面**五个**常量 —— MODEL_FILE / LABELS_FILE / INPUT_WIDTH=160 /
 *     INPUT_HEIGHT=160 / REGION_ASPECT=null / REGION_MODE="board"。
 *     ⚠️ 只改模型名不改 INPUT_HEIGHT 会直接报 OpenCV reshape2 断言（方形模型吃 160×320）。
 *   · 更老的 `chessai_160.onnx` 不再随包（同样在 `.artifacts/model-sync/`），
 *     供历史评测脚本对照（同套帧 782/787 = 99.4%，
 *     但这 9 帧很可能就是它当初的基线集，有「见过」的嫌疑，不能当泛化结论）。
 */
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
const INPUT_SIZE = INPUT_WIDTH;   // 方形档回滚时用（同时把 REGION_ASPECT 设为 null）
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
    // 矩形（非方形）模型：必须显式给 inputWidth/inputHeight，否则引擎按方形 letterbox，比例就错了
    // 反面防呆（真机踩过）：传了方形的 inputSize 却又没给 inputWidth/Height 时，
    // 如果沿用默认的 160×320，方形模型会直接报
    //   OpenCV reshape2_layer: Assertion failed outTotal == inpTotal
    // —— 回滚到方形档时只改 MODEL_FILE/LABELS_FILE 是*不够*的。这里让 inputSize 自己把两个维度拉平。
    let inputWidth = opts.inputWidth == null ? INPUT_WIDTH : opts.inputWidth;
    let inputHeight = opts.inputHeight == null ? INPUT_HEIGHT : opts.inputHeight;
    if (opts.inputWidth == null && opts.inputHeight == null &&
        opts.inputSize != null && opts.inputSize !== INPUT_HEIGHT) {
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
            // ⚠️ 真机踩过（小米8 + Android 15）：这里原来写死 300ms。投屏通道慢时会
            // 拿到「半更新」的帧 —— 症状是**左列一片丢子**（a9-c9 读成空，而同一帧
            // 用文件识别是全的）。给足 1200ms，宁可多等一会儿也不要半截帧。
            reuseFrame = adapter.capture("full", opts.timeoutMs == null ? 1200 : opts.timeoutMs);
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
        // 允许调用方注入已加载的检测器：几何校准要连着跑十几次识别，每次都重新 load 一遍模型太贵。
        loaded = opts.detector
            ? { detector: opts.detector, request: opts.detectorRequest || null, source: "调用方注入", failures: [] }
            : loadDetector(opts);
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
        // 矩形模型要竖屏窗口（默认 160/320）；方形档回滚时传 regionAspect: null
        regionAspect: opts.regionAspect === undefined ? REGION_ASPECT : opts.regionAspect,
        // "full" = 整屏入检（与训练侧的「全屏→矩形」评测口径一致）；"board" 只裁棋盘
        regionMode: opts.regionMode === undefined ? REGION_MODE : opts.regionMode,
        confidence: opts.confidence,
        minScore: opts.minScore,
        frameQuality: opts.frameQuality,
        sideSampleStep: opts.sideSampleStep,
        sideToMove: opts.sideToMove,
        // 真机上定位有系统性偏差（K40+天天象棋实测 (-7,-14)，未知格 6→1），
        // 所以默认开着「按检测框微调几何」；要对照原行为传 autoAlign: false
        autoAlign: opts.autoAlign !== false,
        alignSearch: opts.alignSearch
    });
    if (!opts.detector && detector && typeof detector.close === "function") {
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
    // 矩形档要报 宽×高，不能只报 inputSize（否则显示成 undefined）
    const inputDesc = (request.inputWidth && request.inputHeight)
        ? (request.inputWidth + "×" + request.inputHeight)
        : String(request.inputSize);
    lines.push("模型：" + modelFile + "（" + result.modelSource + "，输入 " + inputDesc + "）");
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

/**
 * **会话级几何校准**：定位给出的原点常有系统性偏差 —— K40 + 腾讯天天象棋实测：
 * 把原点从 (81,666) 挪到 (74,652)（dx=-7, dy=-14），未知格从 **6 降到 1**；
 * 而「按检测框残差最小」的另一种微调（+3,+6）未知格仍是 6。
 * 所以校准的评分标准必须是**最终未知格数**，不是几何残差。
 *
 * 代价：每个候选要跑一次完整识别（推理为主，~0.4s），所以这是**一局一次**的动作
 * （自动走棋在一轮识别里不会调它）。模型只加载一次，候选之间复用。
 *
 * @param image    一帧图（或图片对象）
 * @param located  locateBoard 的结果（用它的 grid 做基准、坐标做搜索中心）
 * @param options.search {maxShift, step}；options.maxCandidates 上限；其余参数透传给识别
 * @returns { ok, grid, dx, dy, unknown, tried, layout, reason }
 */
function calibrateGrid(image, located, options) {
    const opts = options || {};
    // ⚠️ 真机踩过：locateBoardOnScreen() 给的是 `frameObject`，**没有** `imageObject`
    //    （locateBoardInImage 才有）→ 只读 imageObject 会让校准**静默失败**，
    //    而失败后仍然返回、上层继续用未校准的几何（颜色判错、未知格一堆）。
    //    所以这里两个字段都认，并且允许调用方直接传图。
    const frame = image || (located && (located.imageObject || located.frameObject)) || null;
    if (!frame || !located || !located.ok || !located.grid) {
        return { ok: false, reason: "缺少图像（imageObject/frameObject 都没有）或基准几何" };
    }
    const search = opts.search || { maxShift: 18, step: 7 };
    const maxShift = search.maxShift == null ? 18 : search.maxShift;
    const step = search.step == null ? 7 : search.step;
    const maxCandidates = opts.maxCandidates == null ? 25 : opts.maxCandidates;
    /** 粗扫（覆盖半格级错锚）的候选上限：会话级一次，宽容一些没代价 */
    const coarseCandidates = opts.coarseCandidates == null ? 81 : opts.coarseCandidates;

    let loaded;
    try {
        loaded = loadDetector(opts);
    } catch (error) {
        return { ok: false, reason: "加载模型失败：" + (error && error.message ? error.message : error) };
    }
    const base = located.grid;
    const runOpts = Object.assign({}, opts, { detector: loaded.detector });
    delete runOpts.search;

    let tried = 0;
    /**
     * 丢一个子的代价。单位与「未知格」同量级：一个格子被认错，无论是「读不出来」还是
     * 「读成了空格」，对盘面都是同一个错。
     *
     * ⚠️ 三项权重的相对大小是**真机事故换来的**，别随手调：
     *   · 「修复动作」曾经是 ×1000 → 2026-09-14 K40 实测：正确几何 (0,0) 读出「子 31/未知 1」
     *     但要 1 次棋规修复 → 代价 1001；而一个偏掉 50px（≈0.44 格）的畸形几何虽然把整盘
     *     推成「子 9 / 未知 23」（一盘垃圾），却因为「不需要修复」而代价只有 287 → 胜出、
     *     整个面板就卡在 RECOVERING。
     *   · 而修复降级的格子**本来就已经计进未知格**（repair 会把非法子改成 '?'），所以
     *     修复只需很低的额外权重（区分「模型自己读不出」与「读出矛盾子要去纠正」）。
     *   · 「丢子」必须比未知格重：把棋子读成空格时 unknown 不涨（那格成了 EMPTY），
     *     只能靠这一项把它拉回来。
     */
    const PIECE_LOSS_WEIGHT = 20;
    /** 一次棋规修复的额外代价（降级后的格子已计入 unknown，这里只区分来源）。 */
    const REPAIR_WEIGHT = 6;
    const results = [];

    /** 候选代价：越小越好。pickBest 与诊断落盘共用，避免两处各写一份公式而不同步。 */
    function costOf(item, maxPieces) {
        return item.fixes * REPAIR_WEIGHT + item.unknown +
            (maxPieces - item.pieces) * PIECE_LOSS_WEIGHT;
    }

    /** 从 layout（FEN 风格字符串）数棋子个数：字母是棋子，数字是空格。 */
    function countPieces(layout) {
        if (!layout) {
            return 0;
        }
        let count = 0;
        for (let i = 0; i < layout.length; i++) {
            const ch = layout.charAt(i);
            if ((ch >= "a" && ch <= "z") || (ch >= "A" && ch <= "Z")) {
                count += 1;
            }
        }
        return count;
    }

    /**
     * 评估一个偏移；失败会**重试一次**。
     * ⚠️ 冷启动（模型/图刚打开）时第一发可能直接失败，而失败在旧的实现里等价于
     * 「最差候选」（unknown=999）—— 结果就是基准偏移 (0,0) 被淘汰、一个便宜但错的偏移胜出。
     */
    function evaluate(dx, dy) {
        const candidate = Object.assign({}, located, {
            grid: Object.assign({}, base, {
                originX: base.originX + dx,
                originY: base.originY + dy
            })
        });
        let result = null;
        for (let attempt = 0; attempt < 2 && result == null; attempt++) {
            try {
                const current = run(frame, candidate, runOpts, null);
                if (current && current.ok) {
                    result = current;
                }
            } catch (error) {
                result = null;
            }
        }
        if (result == null) {
            return null;
        }
        tried += 1;
        const repairedInfo = result.repaired || {};
        const fixes = (repairedInfo.demoted ? repairedInfo.demoted.length : 0) +
            (repairedInfo.flipped ? repairedInfo.flipped.length : 0);
        return {
            ok: result.ok,
            dx: dx,
            dy: dy,
            unknown: result.unknownCells,
            fixes: fixes,
            pieces: countPieces(result.layout),
            cost: 0,
            layout: result.layout,
            grid: candidate.grid,
            sideUnknown: result.stats ? result.stats.sideUnknown : null
        };
    }

    /** 扫一批候选偏移（dx/dy = center + 偏移）；不做「代价 0 就提前收工」：
     *  降级/翻色修复能让多个偏移都凑出代价 0，提前退出会把没对齐的那个留下。 */
    function scanOffsets(centerX, centerY, halfRange, stepPx, cap) {
        const offsets = [];
        for (let dx = -halfRange; dx <= halfRange; dx += stepPx) {
            for (let dy = -halfRange; dy <= halfRange; dy += stepPx) {
                offsets.push([dx, dy]);
            }
        }
        // 先试偏移小的：真机偏差就在十几像素，大的只作为兜底
        offsets.sort(function (a, b) {
            return (Math.abs(a[0]) + Math.abs(a[1])) - (Math.abs(b[0]) + Math.abs(b[1]));
        });
        for (let i = 0; i < offsets.length && i < cap; i++) {
            const item = evaluate(centerX + offsets[i][0], centerY + offsets[i][1]);
            if (item) {
                results.push(item);
            }
        }
    }

    /**
     * 在已评估的候选里挑最优。
     *
     * 代价 = 修复动作 × REPAIR_WEIGHT + 未知格 + **丢子 × PIECE_LOSS_WEIGHT**
     * （权重为什么是这几个数，见 costOf 上面的事故记录）
     */
    function pickBest(list) {
        let maxPieces = 0;
        for (let i = 0; i < list.length; i++) {
            if (list[i].pieces > maxPieces) {
                maxPieces = list[i].pieces;
            }
        }
        let best = null;
        for (let i = 0; i < list.length; i++) {
            const item = list[i];
            const cost = costOf(item, maxPieces);
            if (best == null || cost < best.cost ||
                (cost === best.cost &&
                    (Math.abs(item.dx) + Math.abs(item.dy)) < (Math.abs(best.dx) + Math.abs(best.dy)))) {
                best = Object.assign({}, item, { cost: cost, maxPieces: maxPieces });
            }
        }
        return best;
    }

    // (1) 基准偏移：先用单点扫确认它真被评过（内含重试）
    scanOffsets(0, 0, 0, step, 1);

    // (2) 粗扫：范围覆盖「半格」级错锚 —— 线定位偶尔把第一条线锚错半格（K40 实测偏差 49px），
    //     而调用方习惯传 maxShift=18，那段参数根本够不到真值。步长大 → 候选量可控。
    //     ⚠️ 这一步只有在代价函数看得见「丢子」之后才是安全的（见 pickBest）。
    const cellStep = Math.max(base.stepX || 0, base.stepY || 0);
    const coarseRange = Math.max(maxShift, Math.round(cellStep * 0.75));
    const coarseStep = Math.max(6, Math.round(coarseRange / 3));
    scanOffsets(0, 0, coarseRange, coarseStep, coarseCandidates);

    // (3) 精修：以当前最优为原点，用调用方给的 (maxShift, step) 再扫一遍
    const interim = pickBest(results);
    scanOffsets(interim ? interim.dx : 0, interim ? interim.dy : 0,
        maxShift, step, maxCandidates);

    const best = pickBest(results);

    // 诊断落盘（opts.dumpPath）：把**评估过的全部候选**连同代价写出来。
    // 「校准挑了一个把整盘推向未知的偏移」这种事故，不看候选全量就只能猜。
    if (opts.dumpPath) {
        try {
            let maxPieces = 0;
            for (let i = 0; i < results.length; i++) {
                if (results[i].pieces > maxPieces) {
                    maxPieces = results[i].pieces;
                }
            }
            const rows = results.map(function (item) {
                return {
                    dx: item.dx, dy: item.dy, unknown: item.unknown, pieces: item.pieces,
                    fixes: item.fixes, sideUnknown: item.sideUnknown,
                    cost: costOf(item, maxPieces),
                    layout: item.layout
                };
            }).sort(function (a, b) {
                return a.cost - b.cost || (Math.abs(a.dx) + Math.abs(a.dy)) - (Math.abs(b.dx) + Math.abs(b.dy));
            });
            files.write(opts.dumpPath, JSON.stringify({
                base: { originX: base.originX, originY: base.originY, stepX: base.stepX, stepY: base.stepY },
                evaluated: results.length,
                chosen: best ? { dx: best.dx, dy: best.dy, unknown: best.unknown, pieces: best.pieces } : null,
                candidates: rows
            }));
        } catch (error) {
            // 诊断落盘失败不影响主流程
        }
    }

    if (loaded.detector && typeof loaded.detector.close === "function") {
        try {
            loaded.detector.close();
        } catch (error) {
            // 忽略
        }
    }
    if (!best) {
        return { ok: false, reason: "所有候选都识别失败" };
    }
    best.tried = tried;
    best.base = { originX: base.originX, originY: base.originY, stepX: base.stepX, stepY: base.stepY };
    best.reason = (best.unknown === 0
        ? "已校准（无未知格"
        : ("校准后仍有 " + best.unknown + " 个未知格")) +
        (best.fixes ? ("；修复动作 " + best.fixes + " 次") : "") +
        "；子 " + best.pieces + "/" + best.maxPieces + "）";
    return best;
}

/** 给一个候选网格打分（**完整识别**路径：每个候选都跑一次模型）。
 *
 * 只在 `pickGrid({mode:"detect"})` 对照时用；默认路径是 grid-search.js 的缓存框评分（快得多）。
 * ⚠️ 两条铁律（都踩过）：
 *   1. 计数必须走 `cells.js` —— 这里原来写「不是 '.' 也不是 '?' 就算棋子」，
 *      而空格在字符层是 `null`，于是**空格全被算成棋子**（读出「子 90」这种不可能的数）；
 *   2. 解析失败**不能**算 0 违规（否则瞎网格会被选成最优）。 */
function scoreGrid(image, grid, options) {
    const opts = options || {};
    const runOpts = Object.assign({}, opts);
    delete runOpts.search;
    const holder = { ok: true, method: "probe", grid: grid, imageObject: image, frameObject: image };
    let result = null;
    try {
        result = run(image, holder, runOpts, null);
    } catch (error) {
        return null;
    }
    if (!result || !result.ok) {
        return null;
    }
    const counts = cells.count(result.chars || []);
    let violations = 0;
    const parsed = fen.parseFen(result.layout + " w");
    if (!parsed.ok) {
        violations = 9;
    } else {
        violations = fen.validateBoard(parsed.board).errors.length;
    }
    const expect = opts.expectPieces;
    let cost = violations * 1000 + (result.unknownCells || 0) * 10 + counts.invalid.length * 1000 +
        Math.max(0, counts.pieces - 32) * 100;
    if (typeof expect === "number" && isFinite(expect)) {
        cost += Math.abs(counts.pieces - expect) * 20;
    }
    return {
        grid: grid,
        cost: cost,
        pieces: counts.pieces,
        empty: counts.empty,
        unknown: result.unknownCells || 0,
        violations: violations,
        counts: counts,
        result: result
    };
}

/**
 * 取棋盘（几何）：找棋盘所在的那组网格。
 *
 * 默认走**缓存框**路径（执行单 §4.2）：先把整块棋盘区域检测一次，之后每个候选用纯坐标运算评分 ——
 * 旧的「每候选跑一次 run()」一共 171 次完整识别（含 171 次模型加载/关闭），19.8 秒/帧且分不清耗时去哪了。
 *
 * 不设「至少 24 枚」这种通用门槛（合法残局可能只有 2~8 枚）：接受规则见 grid-search.js，
 * 需要「将帅都在、未知格少、检测框落在棋盘内、兵种不超限」；`expectPieces` 只作为**软先验**（允许吃子）。
 */
function pickGrid(image, options) {
    const opts = options || {};
    const seeds = opts.seeds || [
        { originX: 81, originY: 666, stepX: 115, stepY: 114, orientation: "RED_BOTTOM" },
        { originX: 72, originY: 660, stepX: 114.8, stepY: 114.3, orientation: "RED_BOTTOM" }
    ];
    const orientation = opts.orientation || "RED_BOTTOM";
    const mode = opts.mode === "detect" ? "detect" : "cached";
    const report = { mode: mode, tried: 0, detectCount: 0, detectMs: null, searchMs: null, finalMs: null, totalMs: null };
    const startedAt = adapter.nowMono();
    if (!image) {
        return { ok: false, reason: "没有图像", tried: 0, report: report };
    }

    // 1) 检测器只加载一次；调用方注入的归调用方，我们不关
    let detector = opts.detector || null;
    let loaded = null;
    let owner = false;
    if (!detector) {
        try {
            loaded = loadDetector(opts);
            detector = loaded.detector;
            owner = true;
        } catch (error) {
            return { ok: false, reason: "加载模型失败：" + (error && error.message ? error.message : error), tried: 0, report: report };
        }
    } else {
        loaded = { request: opts.detectorRequest || null, source: "调用方注入", failures: [] };
    }

    const runOpts = Object.assign({}, opts, { detector: detector, detectorRequest: loaded ? loaded.request : null });
    delete runOpts.search;
    delete runOpts.seeds;
    delete runOpts.radius;
    delete runOpts.step;
    delete runOpts.fineRadius;
    delete runOpts.fineStep;
    delete runOpts.minPieces;
    delete runOpts.maxUnknown;
    delete runOpts.requireKings;
    delete runOpts.minCoverage;
    delete runOpts.probeCropMargin;

    try {
        if (mode === "detect") {
            // 对照路径：每候选完整识别（保留下来做性能对比）
            let best = null;
            for (let s = 0; s < seeds.length; s++) {
                const seed = seeds[s];
                const radius = opts.radius == null ? 30 : opts.radius;
                const step = opts.step == null ? 6 : opts.step;
                for (let dx = -radius; dx <= radius; dx += step) {
                    for (let dy = -radius; dy <= radius; dy += step) {
                        const grid = geometry.createGrid({
                            originX: seed.originX + dx, originY: seed.originY + dy,
                            stepX: seed.stepX, stepY: seed.stepY, orientation: seed.orientation || orientation
                        });
                        const scored = scoreGrid(image, grid, runOpts);
                        report.tried += 1;
                        if (scored && (!best || scored.cost < best.cost)) {
                            best = scored;
                        }
                        if (best && best.cost === 0) {
                            break;
                        }
                    }
                    if (best && best.cost === 0) {
                        break;
                    }
                }
            }
            report.totalMs = adapter.nowMono() - startedAt;
            if (!best) {
                return { ok: false, reason: "所有候选都识别失败（detect 模式）", tried: report.tried, report: report };
            }
            return {
                ok: !!best.result.ok,
                reason: best.result.ok ? "" : best.result.reason,
                grid: best.grid,
                cost: best.cost,
                tried: report.tried,
                pieces: best.pieces,
                empty: best.empty,
                unknown: best.unknown,
                counts: best.counts,
                violations: best.violations,
                result: best.result,
                imageObject: image,
                frameObject: image,
                method: "pick-grid-detect",
                report: report
            };
        }

        // 2) 检测一次：裁剪范围比棋盘多留一圈，保证 ±radius 内的候选棋盘都在检测范围内
        const probeGrid = geometry.createGrid({
            originX: seeds[0].originX, originY: seeds[0].originY,
            stepX: seeds[0].stepX, stepY: seeds[0].stepY,
            orientation: seeds[0].orientation || orientation
        });
        const probeMargin = opts.probeCropMargin == null ? 0.45 : opts.probeCropMargin;
        const probeOpts = Object.assign({}, runOpts, { cropMargin: probeMargin, autoAlign: false });
        const probe = run(image, { ok: true, method: "pick-probe", grid: probeGrid, imageObject: image, frameObject: image }, probeOpts, null);
        report.detectMs = probe && probe.timing ? probe.timing.detectMs : null;
        report.detectCount = probe && probe.detections ? probe.detections.length : 0;
        if (!probe || !probe.ok || !probe.detections || !probe.detections.length) {
            report.totalMs = adapter.nowMono() - startedAt;
            return {
                ok: false,
                reason: "首次检测没拿到检测框（棋盘不在画面里？）" + (probe && probe.reason ? "：" + probe.reason : ""),
                tried: 0,
                report: report,
                probe: probe
            };
        }

        const searchStart = adapter.nowMono();
        const found = gridSearch.searchByDetections(probe.detections, {
            seeds: seeds,
            radius: opts.radius,
            step: opts.step,
            fineRadius: opts.fineRadius,
            fineStep: opts.fineStep,
            orientation: orientation,
            expectPieces: opts.expectPieces,
            expectWeight: opts.expectWeight,
            minPieces: opts.minPieces,
            maxUnknown: opts.maxUnknown,
            requireKings: opts.requireKings,
            requireTypeLimits: opts.requireTypeLimits,
            minCoverage: opts.minCoverage,
            assign: {
                minScore: opts.minScore,
                conflictGap: opts.conflictGap,
                maxResidual: opts.maxResidual,
                frameQuality: opts.frameQuality
            }
        });
        report.searchMs = adapter.nowMono() - searchStart;
        report.tried = found.tried;
        if (!found.ok) {
            report.totalMs = adapter.nowMono() - startedAt;
            return {
                ok: false,
                reason: found.reason,
                tried: found.tried,
                candidates: found.candidates,
                rejectionSummary: found.rejectionSummary,
                best: found.best,
                report: report,
                probe: probe
            };
        }

        // 3) 用胜出的几何做一次完整识别（颜色/一致性修复/布局以它为准）
        const finalStart = adapter.nowMono();
        const finalResult = run(image, { ok: true, method: "pick-grid", grid: found.grid, imageObject: image, frameObject: image }, runOpts, null);
        report.finalMs = adapter.nowMono() - finalStart;
        const counts = cells.count(finalResult && finalResult.chars ? finalResult.chars : []);
        let violations = 0;
        if (finalResult && finalResult.layout) {
            const parsed = fen.parseFen(finalResult.layout + " w");
            violations = parsed.ok ? fen.validateBoard(parsed.board).errors.length : 9;
        } else {
            violations = 9;
        }
        report.totalMs = adapter.nowMono() - startedAt;
        return {
            ok: !!(finalResult && finalResult.ok),
            reason: finalResult && finalResult.ok ? "" : ("最终识别失败：" + (finalResult && finalResult.reason ? finalResult.reason : "无结果")),
            grid: found.grid,
            cost: found.cost,
            tried: found.tried,
            pieces: counts.pieces,
            empty: counts.empty,
            unknown: finalResult ? finalResult.unknownCells : null,
            counts: counts,
            violations: violations,
            residual: found.best ? found.best.residual : null,
            coverage: found.best ? found.best.coverage : null,
            result: finalResult,
            imageObject: image,
            frameObject: image,
            method: "pick-grid-cached",
            report: report
        };
    } finally {
        // 所有权：只有我们自己加载的才关；错误/早退/取消都不会漏
        if (owner && detector && typeof detector.close === "function") {
            try {
                detector.close();
            } catch (error) {
                // 关不掉的检测器不影响结果
            }
        }
    }
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
    describeSequence: describeSequence,
    calibrateGrid: calibrateGrid,
    pickGrid: pickGrid,
    scoreGrid: scoreGrid,
    // 调参用：直接把「一帧图 + 自定义几何」交给识别管线，不重新截图、不重新定位
    // （离线排查「未知格是不是几何偏出来的」就靠它）
    run: run
};
