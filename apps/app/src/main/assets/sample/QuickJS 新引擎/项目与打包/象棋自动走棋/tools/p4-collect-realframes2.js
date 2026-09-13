// @engine quickjs
/**
 * 真实棋局帧采集器 v3：只要「局面变了且像真的」就收，**不要求游戏接受点击**。
 *
 * v2 慢在哪（3 分钟只收 9 帧）：
 *   1. waitStable() 每帧要重复识别 2~6 次（每次要先 screencap）
 *   2. 每帧落盘还要再 screencap 一次（同一个画面抓两遍）
 *   3. 只收「游戏真的接受了这一步」的帧 → 但玩家对手的应手/动画也会让局面变化，白等
 * v3 改成：点两下 → 等 ~900ms 动画结束 → **直接抓到目标路径**（一次截图）→ 识别 →
 *   「像真的（未知格 ≤ 2 + 棋规无违规）+ 布局与上一帧不同」就留下，否则丢弃。
 *   连续 12 次没有新布局就按一次返回键（关掉悔棋/认输之类的弹窗）继续。
 *
 * v3.3：**不用 locateBoardInImage 了**（同一帧它时好时坏：要么「格点阵与给定棋盘范围对不齐
 *   命中 7/9 条」，要么直接成功；换个 UI 状态就挂）。改为在已知基准几何上做大范围自校准：
 *   原点 ±60px、步长 6px 粗搜（同一个代价函数 fixes*1000 + unknown），够用且只花 ~30s。
 *   真机实测：MIUI 那次 MediaProjection 会话死掉之后，定位里的 capture 会**无限等**
 *   （脚本卡在「开始定位+校准」4 分钟以上、无异常、进程还活着）。
 *   截图一律走 Shizuku 的 `screencap -p`，基准几何直接用本机已知值（origin 81,666 / step 115,114），
 *   校准只在这附近搜小范围。
 */
const locateBoard = require("./locate-board.js");
const recognize = require("./recognize-board.js");
const geometry = require("../src/vision/geometry.js");
const legal = require("../src/chess/legal-moves.js");
const fen = require("../src/chess/fen.js");

const GAME = "com.tencent.qqgame.xq";
const OUT_DIR = "/sdcard/xiangqi-p4/realframes2/";
const WANT = 70;

function log(text) {
    console.log("RF2 " + text);
}

function topActivity() {
    try {
        const result = shell("dumpsys activity activities | grep topResumedActivity", { shizuku: true, timeout: 6000 });
        return String(result && result.result ? result.result : "").replace(/\s+/g, " ");
    } catch (error) {
        return "";
    }
}

function waitForGame(limitMs) {
    const started = Date.now();
    while (Date.now() - started < limitMs) {
        if (topActivity().indexOf(GAME) >= 0) {
            return true;
        }
        sleep(800);
    }
    return false;
}

function screencap(path) {
    const result = shell("screencap -p " + path, { shizuku: true, timeout: 12000 });
    return result && result.code === 0;
}

function tapPoint(x, y) {
    try {
        shell("input tap " + Math.round(x) + " " + Math.round(y), { shizuku: true, timeout: 5000 });
    } catch (error) { /* 忽略 */ }
}

/** 抓一帧到 path 并识别（一次截图）。 */
function captureAndRecognize(grid, path) {
    if (!screencap(path)) {
        return null;
    }
    let image = null;
    try {
        image = images.read(path);
    } catch (error) {
        return null;
    }
    if (!image) {
        return null;
    }
    const reused = { ok: true, method: "reuse", grid: grid, imageObject: image, frameObject: image };
    const result = recognize.run(image, reused, { orientation: "RED_BOTTOM", confidence: 0.25, autoAlign: false }, null);
    try { image.recycle(); } catch (error) { /* 忽略 */ }
    return result;
}

function boardLooksSane(result) {
    if (!result || !result.ok || result.unknownCells > 4) {
        return false;
    }
    const parsed = fen.parseFen(result.layout + " w");
    if (!parsed.ok) {
        return false;
    }
    return fen.validateBoard(parsed.board).errors.length === 0;
}

/**
 * 取几何：**直接用库里的 pickGrid**（缓存框搜索 + 三态计数 + 残差入代价 + 不设 24 子门槛）。
 * 这里原来自己抄了一份 scoreGrid：用「不是 '.' 不是 '?'」数棋子（空格被算成棋子）、
 * 还要求至少 24 子（合法残局直接废掉）—— 现在全部由库里负责，采集器只提供会话软先验。
 */
let priorPieces = null;

function pickGrid(image, opts) {
    const options = opts || {};
    return recognize.pickGrid(image, {
        orientation: "RED_BOTTOM",
        confidence: 0.25,
        radius: options.radius == null ? 18 : options.radius,
        step: options.step == null ? 4 : options.step,
        probeCropMargin: 0.5,
        // 上一可信局面的子力数只作软先验（允许吃子）；重校准/换局时必须传 null
        expectPieces: options.fresh ? null : priorPieces
    });
}

log("等待游戏到前台（最多 20s）");
if (!waitForGame(20000)) {
    log("游戏不在前台（当前 " + topActivity().slice(0, 80) + "），放弃");
} else {
    log("游戏已在前台，开始定位+校准（shell 截图 + 基准几何大范围自校准）");
    const BASE_GRID = geometry.createGrid({
        originX: 81, originY: 666, stepX: 115, stepY: 114, orientation: "RED_BOTTOM"
    });
    let grid = null;
    let start = null;
    for (let attempt = 1; attempt <= 3 && !grid; attempt++) {
        const shotPath = "/sdcard/yolo-bench/_rf2-base.png";
        if (!screencap(shotPath)) {
            log("第 " + attempt + " 次：shell 截图失败");
            sleep(1500);
            continue;
        }
        let image = null;
        try {
            image = images.read(shotPath);
        } catch (error) {
            image = null;
        }
        if (!image) {
            log("第 " + attempt + " 次：读图失败");
            continue;
        }
        const located = { ok: true, method: "base-grid", grid: BASE_GRID, frameObject: image, imageObject: image };
        const picked = pickGrid(image, { fresh: true });
        if (!picked.ok) {
            log("第 " + attempt + " 次校准失败：" + (picked.reason || "无") + " tried=" + picked.tried);
            try { image.recycle(); } catch (error) { /* 忽略 */ }
            continue;
        }
        grid = picked.grid;
        log("第 " + attempt + " 次：几何(" + grid.originX.toFixed(1) + "," + grid.originY.toFixed(1) + "/" +
            grid.stepX.toFixed(1) + "," + grid.stepY.toFixed(1) + ") 子 " + picked.pieces +
            " 未知 " + picked.unknown + " tried=" + picked.tried);
        const probe = picked.result;
        if (probe && boardLooksSane(probe)) {
            start = probe;
        }
        try { image.recycle(); } catch (error) { /* 忽略 */ }
        void located;
    }
    if (!start) {
        log("校准没拿到可用局面，放弃");
    } else {
        log("起始局面：" + start.layout);
        let board = start.chars.slice();
        let mySide = "w";
        const accepted = { files: [], layouts: {} };
        let index = 0;
        let attempts = 0;
        let stalled = 0;
        let lastLayout = start.layout;

        /** 连续拿不到新局面的自救：返回键 → 重拉游戏 + 点中间 → 重新校准网格。
         *  真机踩过：随机走子把棋局走死（结算页）之后，光按返回键会彻底离开棋盘，
         *  之后全部截图都识别不出 → 连续失败 21 次就放弃、只收到 13 帧。 */
        function recover(count) {
            if (count > 0 && count % 3 === 0) {
                log("恢复：按返回键（连续 " + count + " 次没进展）");
                shell("input keyevent 4", { shizuku: true, timeout: 4000 });
            }
            if (count > 0 && count % 6 === 0) {
                log("恢复：重新拉起游戏 + 点屏幕中间两下");
                shell("monkey -p " + GAME + " -c android.intent.category.LAUNCHER 1", { shizuku: true, timeout: 8000 });
                sleep(2500);
                tapPoint(540, 1200);
                sleep(700);
                tapPoint(540, 1200);
                sleep(1500);
            }
            if (count > 0 && count % 9 === 0) {
                const shot = "/sdcard/yolo-bench/_rf2-recal.png";
                if (screencap(shot)) {
                    let img = null;
                    try { img = images.read(shot); } catch (error) { img = null; }
                    if (img) {
                            const picked = pickGrid(img, { fresh: true });
                        try { img.recycle(); } catch (error) { /* 忽略 */ }
                        if (picked && picked.ok) {
                            grid = picked.grid;
                            log("恢复：重新校准网格(" + grid.originX.toFixed(1) + "," + grid.originY.toFixed(1) + ")");
                        } else {
                            log("恢复：重新校准没成功");
                        }
                    }
                }
            }
        }

        while (index < WANT && attempts < WANT * 12) {
            attempts += 1;
            const moves = legal.allLegalMoves(board, mySide);
            if (!moves.length) {
                mySide = mySide === "w" ? "b" : "w";
                stalled += 1;
                recover(stalled);
                continue;
            }
            const move = moves[Math.floor(Math.random() * moves.length)];
            const from = geometry.pointAt(grid, move.fromCol, move.fromRow);
            const to = geometry.pointAt(grid, move.toCol, move.toRow);
            const file = OUT_DIR + "real-" + ("0" + (index + 1)).slice(-2) + ".png";
            tapPoint(from.x, from.y);
            sleep(250);
            tapPoint(to.x, to.y);
            sleep(900);
            const after = captureAndRecognize(grid, file);
            if (!boardLooksSane(after)) {
                stalled += 1;
                recover(stalled);
                mySide = mySide === "w" ? "b" : "w";
                continue;
            }
            if (after.layout === lastLayout) {
                shell("rm -f " + file, { shizuku: true, timeout: 4000 });
                stalled += 1;
                recover(stalled);
                mySide = mySide === "w" ? "b" : "w";
                continue;
            }
            stalled = 0;
            index += 1;
            lastLayout = after.layout;
            board = after.chars.slice();
            // 下一帧可以拿这个子力数当软先验（允许吃子；重校准/换局时传 fresh 丢掉）
            priorPieces = after.stats && after.stats.pieceCells != null ? after.stats.pieceCells : null;
            const name = file.split("/").pop();
            accepted.files.push(name);
            accepted.layouts[name] = after.layout;
            log(index + "/" + WANT + " " + mySide + " " + move.iccs + " 未知格 " + after.unknownCells + "：" + after.layout);
            try {
                if (files && typeof files.write === "function") {
                    files.write(OUT_DIR + "draft.json", JSON.stringify(accepted, null, 2));
                }
            } catch (error) { /* 忽略 */ }
        }
        log("结束：收下 " + index + " 帧，尝试 " + attempts + " 次");
    }
}
log("RF2 done");
