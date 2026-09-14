// @engine quickjs
/**
 * autoAlign 造重子的取证探针（**设备端**）。
 *
 * 背景（K40 + 腾讯天天象棋实测）：
 *   同一个开局局面，`autoAlign: true` 时驱动看到的局面非法
 *   （`红R 3 个 / 红N 3 个 / 红C 4 个 / 红P 9 个`，状态机 RECOVERING 拒绝出手），
 *   把 autoAlign 关掉就是合法的标准开局。几何只挪了 3px，**不可能**改变「框→哪一格」的归属，
 *   所以怀疑问题出在 alignGrid 的挑选目标本身。
 *
 * 这个脚本把「一帧的全部检测框 + 几何」落盘成 JSON，PC 上就能离线重放 alignGrid 的决策，
 * 不用每次上手机。输出：`/sdcard/xiangqi-p4/align-debug.json`
 *
 * 跑法（K40 / 天天象棋前台停在棋局页）：
 *   am start -a android.intent.action.VIEW -d file:///sdcard/xiangqi-p4/app/p4-align-debug.js \
 *     -t text/plain -n com.jdkshen.aijspro/.external.open.RunIntentActivity
 *   logcat -d | grep -i 'ALIGNDBG'
 */
const adapter = require("../src/runtime/aijs-adapter.js");
const locateBoard = require("./locate-board.js");
const recognizeBoard = require("./recognize-board.js");
const recognizer = require("../src/vision/recognizer.js");
const assignModule = require("../src/vision/cell-assignment.js");
const cellsContract = require("../src/vision/cells.js");

const FRAME = "/sdcard/xiangqi-p4/align-debug-frame.png";
const OUT = "/sdcard/xiangqi-p4/align-debug.json";
const LOG_TAG = "ALIGNDBG";

function say(text) {
    console.log(LOG_TAG + " " + text);
}

function grab(path) {
    try {
        const caps = adapter.capabilities();
        if (caps.captureShell) {
            const result = shell("screencap -p " + path, { shizuku: true, timeout: 12000 });
            if (result && result.code === 0 && files.exists(path)) {
                const image = images.read(path);
                if (image) {
                    return { image: image, channel: "shell(" + caps.captureShellBackend + ")" };
                }
            }
        }
    } catch (error) {
        say("shell 截图失败：" + error);
    }
    try {
        if (!adapter.requestCapture("portrait")) {
            return null;
        }
        const frame = adapter.capture("full", 2000);
        return frame ? { image: frame, channel: "screen" } : null;
    } catch (error) {
        say("应用截屏失败：" + error);
        return null;
    }
}

/** 把 90 格落成「行 0 在上」的布局串（和识别结果同一口径）。 */
function layoutOf(chars) {
    const text = cellsContract.toTextArray(chars);
    const rows = [];
    for (let row = 0; row < 10; row++) {
        let line = "";
        let run = 0;
        for (let col = 0; col < 9; col++) {
            const ch = text[row * 9 + col];
            if (ch === ".") {
                run += 1;
                continue;
            }
            if (run > 0) {
                line += String(run);
                run = 0;
            }
            line += ch;
        }
        if (run > 0) {
            line += String(run);
        }
        rows.push(line);
    }
    return rows.join("/");
}

/** 逐格快照，用来 diff「对齐前 / 对齐后」。 */
function snapshot(cells) {
    const out = [];
    for (let i = 0; i < 90; i++) {
        const cell = cells[i];
        out.push({
            index: i,
            state: cell.state,
            piece: cell.piece,
            score: cell.score == null ? null : Number(cell.score.toFixed(3)),
            residual: Number((cell.residual || 0).toFixed(4))
        });
    }
    return out;
}

function diffCells(before, after) {
    const changes = [];
    for (let i = 0; i < 90; i++) {
        const a = before[i];
        const b = after[i];
        if (a.state === b.state && a.piece === b.piece) {
            continue;
        }
        changes.push({
            cell: "(" + (i % 9) + "," + Math.floor(i / 9) + ")",
            index: i,
            from: a.state + (a.piece ? ":" + a.piece : ""),
            to: b.state + (b.piece ? ":" + b.piece : ""),
            score: b.score,
            residualBefore: a.residual,
            residualAfter: b.residual
        });
    }
    return changes;
}

function main() {
    const grabbed = grab(FRAME);
    if (!grabbed) {
        say("拿不到帧（两条通道都失败）");
        return;
    }
    const size = adapter.sizeOf(grabbed.image);
    say("抓帧：" + grabbed.channel + " " + size.width + "×" + size.height);

    const located = locateBoard.locateBoardInImage(FRAME, {
        orientation: "RED_BOTTOM", confidence: 0.25, autoAlign: false,
        search: { maxShift: 18, step: 3 }, maxCandidates: 25
    });
    if (!located.ok) {
        say("定位失败：" + located.reason);
        return;
    }
    // 会话级校准（一局一次，和面板/主流程同一条路径）
    const calibrated = recognizeBoard.calibrateGrid(located.imageObject, located, {
        orientation: "RED_BOTTOM", confidence: 0.25, autoAlign: false,
        search: { maxShift: 18, step: 3 }, maxCandidates: 25,
        // 把评估过的全部候选落盘，便于离线看「为什么挑了这个偏移」
        dumpPath: "/sdcard/xiangqi-p4/calib-dump.json"
    });
    const grid = calibrated && calibrated.grid ? calibrated.grid : located.grid;
    say("几何 v" + grid.version + " 起点 (" + grid.originX + "," + grid.originY + ") 格距 (" +
        grid.stepX + "," + grid.stepY + ")｜校准：" + (calibrated && calibrated.reason ? calibrated.reason : "无"));

    // 一次检测，两套几何策略共用同一批框（这样 A/B 只差 autoAlign 一个变量）：
    // run() 会把原生检测结果（含 list）挂在 result.native 上，正是我们要的取证材料。
    const image = located.imageObject;
    let detections = [];
    let native = null;
    let baseResult = null;
    const result = recognizeBoard.run(image, located, {
        orientation: "RED_BOTTOM", confidence: 0.25, autoAlign: false
    }, null);
    if (!result.ok) {
        say("识别失败：" + result.reason);
        return;
    }
    baseResult = result;
    native = result.native || { list: [] };
    // ⚠️ adapter.detect 返回的是**未归一化**的宿主原始项（坐标在 bounds 里，且是 Java 包装数值
    // ——直接用 item.x.toFixed 会报“toFixed of undefined”）。复用库自己的归一化，保证与运行时同口径。
    detections = recognizer.normalize(native.list, null);
    say("检测框 " + detections.length + " 个（推理 " + native.inferenceMs + "ms）｜基础 FEN " + result.layout);

    const assignOptions = {
        minScore: recognizer.DEFAULTS.minScore,
        maxResidual: recognizer.DEFAULTS.maxResidual,
        conflictGap: recognizer.DEFAULTS.conflictGap,
        frameQuality: 1
    };
    const alignSearch = { maxShift: 18, step: 6 };
    const dump = {
        capturedAt: Date.now(),
        channel: grabbed.channel,
        size: [size.width, size.height],
        grid: {
            originX: number(grid.originX), originY: number(grid.originY),
            stepX: number(grid.stepX), stepY: number(grid.stepY),
            orientation: grid.orientation, version: grid.version
        },
        calibrate: {
            ok: calibrated.ok, reason: calibrated.reason || null, version: grid.version
        },
        assignOptions: assignOptions,
        alignSearch: alignSearch,
        detections: detections.map(function (item) {
            return {
                label: item.label,
                score: number(item.score),
                x: number(item.x), y: number(item.y),
                width: item.width == null ? null : number(item.width),
                height: item.height == null ? null : number(item.height)
            };
        }),
        recognizeOptions: { confidence: 0.25, useRegion: true },
        recognizeLayout: baseResult.layout
    };
    files.write(OUT, JSON.stringify(dump));

    const base = assignModule.assign(detections, grid, assignOptions);
    const aligned = recognizer.alignGrid(detections, grid, assignOptions, alignSearch);
    dump.alignReport = aligned.report;
    dump.baseLayout = layoutOf(assignModule.cellsToChars(base.cells));
    dump.alignedLayout = layoutOf(assignModule.cellsToChars(aligned.assigned.cells));
    dump.changes = diffCells(snapshot(base.cells), snapshot(aligned.assigned.cells));
    files.write(OUT, JSON.stringify(dump));
    say("基础几何：" + dump.baseLayout + "（未知 " + base.stats.unknown + " 冲突 " + base.stats.conflicts + "）");
    say("对齐后：" + dump.alignedLayout + "（偏移 " + aligned.report.dx + "," + aligned.report.dy +
        " 未知 " + aligned.report.unknown + " 冲突 " + aligned.report.conflicts +
        " 代价 " + aligned.report.costBefore + "→" + aligned.report.costAfter +
        " 增益 " + aligned.report.gain + "，applied=" + aligned.report.applied +
        " 子数变化 " + aligned.report.pieceDelta + "）");
    say("状态变化 " + dump.changes.length + " 格：" + dump.changes.map(function (item) {
        return item.cell + " " + item.from + "→" + item.to + "(残差 " + item.residualBefore + "→" + item.residualAfter + ")";
    }).join("；"));
    say("已写 " + OUT);

    // 阵营（红黑）取证：颜色是先按「格距 84%」的框采样的，边角格会把棋盘外框的红木纹采进去。
    // 这里把每一子的 redShare/darkShare 打出来，用于判断「颜色判错」是不是采样框的问题。
    colorDiag(image, grid);
    const alignedGrid = recognizer.alignGrid(detections, grid, assignOptions, alignSearch).grid;
    if (alignedGrid.version !== grid.version) {
        colorDiag(image, alignedGrid);
    }
}

function colorDiag(image, grid) {
    const result = recognizeBoard.run(image, {
        ok: true, method: "align-debug", grid: grid, imageObject: image, frameObject: image
    }, { orientation: "RED_BOTTOM", confidence: 0.25, autoAlign: false }, null);
    if (!result.ok) {
        say("阵营取证失败：" + result.reason);
        return;
    }
    say("阵营取证 v" + grid.version + "｜FEN " + result.layout +
        "｜红 " + result.stats.sideRed + " 黑 " + result.stats.sideBlack + " 未知 " + result.stats.sideUnknown);
    const cells = result.sides.map(function (item) {
        return item.col + "," + item.row + "=" + item.piece + ":" +
            Number(item.redShare).toFixed(3) + "/" + Number(item.darkShare).toFixed(3);
    });
    say("  采样（col,row=子:红占比/暗占比）：" + cells.join(" "));
    if (result.stats.sideReasons) {
        say("  判不出的原因：" + JSON.stringify(result.stats.sideReasons));
    }
}

/** 统一把宿主包装的数值转成 JS number（Java 包装数值没有 toFixed）。 */
function number(value) {
    const n = Number(value);
    return isFinite(n) ? Math.round(n * 10000) / 10000 : null;
}

try {
    main();
} catch (error) {
    say("异常：" + String(error && error.message ? error.message : error) +
        "｜" + String(error && error.stack ? error.stack : "无栈"));
}
