// @engine quickjs
/**
 * 真实棋局帧采集器 v2：连续走合法着法 → 取帧，供「真实帧微调」用。
 *
 * v1 的坑：定位/校准时游戏还没在前台 → 校准失败 → **颜色判反** → 算出来的"合法着法"是错的，
 * 游戏全拒（只偶然成功几步）。v2 因此：
 *   1. 先用 dumpsys 确认游戏在前台（不能用 currentPackage()：MIUI 游戏加速的悬浮侧栏会顶掉它）
 *   2. 校准后用「未知格 ≤ 2 + 局面通过棋规校验」当门禁，不达标就重试（最多 3 次）
 *   3. 走完一步等**局面稳定**（连续两次识别一致）而不是死等固定时间
 *   4. 每帧立刻落盘 + 追加 draft.json（崩了也不丢）
 */
const adapter = require("../src/runtime/aijs-adapter.js");
const locateBoard = require("./locate-board.js");
const recognize = require("./recognize-board.js");
const geometry = require("../src/vision/geometry.js");
const legal = require("../src/chess/legal-moves.js");
const fen = require("../src/chess/fen.js");

const GAME = "com.tencent.qqgame.xq";
const OUT_DIR = "/sdcard/xiangqi-p4/realframes/";
const WANT = 60;
const WAIT_MS = 6000;

function log(text) {
    console.log("RF " + text);
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
    const result = shell("input tap " + Math.round(x) + " " + Math.round(y), { shizuku: true, timeout: 5000 });
    return result && result.code === 0;
}

function recognizeFile(grid, path) {
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

/** 局面是否「像真的」：未知格少、没有棋规违规（颜色判反时会一堆违规）。 */
function boardLooksSane(result) {
    if (!result || !result.ok || result.unknownCells > 2) {
        return false;
    }
    const parsed = fen.parseFen(result.layout + " w");
    if (!parsed.ok) {
        return false;
    }
    const check = fen.validateBoard(parsed.board);
    return check.errors.length === 0;
}

log("等待游戏到前台（最多 20s）");
if (!waitForGame(20000)) {
    log("游戏不在前台（当前 " + topActivity().slice(0, 80) + "），放弃");
} else {
    log("游戏已在前台，开始定位+校准");
    let grid = null;
    let start = null;
    for (let attempt = 1; attempt <= 3 && !start; attempt++) {
        const located = locateBoard.locateBoardOnScreen({});
        if (!located.ok) {
            log("第 " + attempt + " 次定位失败：" + located.reason);
            continue;
        }
        const calibrated = recognize.calibrateGrid(located.frameObject || located.imageObject, located, {
            orientation: "RED_BOTTOM", confidence: 0.25, autoAlign: false,
            search: { maxShift: 18, step: 3 }, maxCandidates: 25
        });
        if (!calibrated || !calibrated.grid) {
            log("第 " + attempt + " 次校准没结果");
            continue;
        }
        grid = calibrated.grid;
        const probe = recognizeFile(grid, "/sdcard/yolo-bench/_rf-probe.png");
        log("第 " + attempt + " 次：偏移(" + calibrated.dx + "," + calibrated.dy + ") 未知格" +
            (calibrated.unknown) + " → 试拍 " + (probe && probe.ok ? probe.layout : "失败"));
        if (probe && boardLooksSane(probe)) {
            start = probe;
        }
    }
    if (!start) {
        log("3 次都没拿到可用局面，放弃");
    } else {
        log("起始局面可用：" + start.layout);
        let board = start.chars.slice();
        let mySide = null;
        const accepted = { files: [], layouts: {} };
        let index = 0;
        let attempts = 0;
        let misses = 0;

        function waitStable(path, tries) {
            let last = null;
            for (let i = 0; i < tries; i++) {
                const result = recognizeFile(grid, path);
                if (result && result.ok && last && result.layout === last) {
                    return result;
                }
                last = result && result.ok ? result.layout : null;
                sleep(1200);
            }
            return null;
        }

        while (index < WANT && attempts < WANT * 5 && misses < 20) {
            attempts += 1;
            const side = mySide || (attempts % 2 === 0 ? "w" : "b");
            const moves = legal.allLegalMoves(board, side);
            if (!moves.length) {
                log("第 " + attempts + " 次：" + side + " 无合法着法（识别可能有问题），换边");
                mySide = null;
                misses += 1;
                continue;
            }
            const move = moves[Math.floor(Math.random() * moves.length)];
            const file = OUT_DIR + "real-" + ("0" + (index + 1)).slice(-2) + ".png";
            const from = geometry.pointAt(grid, move.fromCol, move.fromRow);
            const to = geometry.pointAt(grid, move.toCol, move.toRow);
            tapPoint(from.x, from.y);
            sleep(300);
            tapPoint(to.x, to.y);
            const after = waitStable("/sdcard/yolo-bench/_rf-after.png", 6);
            if (!after) {
                log("第 " + attempts + " 次 " + side + " " + move.iccs + "：局面读不出来");
                misses += 1;
                continue;
            }
            const diff = legal.diffBoards(board, after.chars);
            if (diff.length === 0) {
                log("第 " + attempts + " 次 " + side + " " + move.iccs + "：没被接受");
                mySide = mySide === null ? (side === "w" ? "b" : "w") : null;
                misses += 1;
                continue;
            }
            if (mySide === null) {
                mySide = side;
                log("判定我方=" + mySide);
            }
            misses = 0;
            index += 1;
            // 把稳定后的那一帧正式落盘（screencap 到目标文件）
            screencap(file);
            const name = file.split("/").pop();
            accepted.files.push(name);
            accepted.layouts[name] = after.layout;
            log(index + "/" + WANT + " " + side + " " + move.iccs + " 变化 " + diff.length + " 格 未知格 " +
                after.unknownCells + "：" + after.layout);
            try {
                if (files && typeof files.write === "function") {
                    files.write(OUT_DIR + "draft.json", JSON.stringify(accepted, null, 2));
                }
            } catch (error) { /* 忽略 */ }
            board = after.chars.slice();
        }
        log("结束：收下 " + index + " 帧，尝试 " + attempts + " 次");
    }
}
log("RF done");
