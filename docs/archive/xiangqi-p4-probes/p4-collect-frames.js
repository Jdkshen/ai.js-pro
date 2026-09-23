// @engine quickjs
/**
 * 采集「真实棋局帧」用于扩充皮肤验证集 / 微调数据。
 *
 * 与之前随机点的区别：这里用**我们自己的棋规**枚举合法着法，再用 Shizuku 注入点击，
 * 所以每一步都真的会被游戏接受 → 能连续取得**不同局面**（吃子、将军、过河……）。
 *
 * 每步流程：随机会法 → 两次 tap → 等对方应手 → `screencap` 存 PNG → 用已校准几何识别
 * → 把「文件名 + 识别布局 + 未知格」追加到 draft.json（PC 端据此做人工核对）
 *
 * 用法：PC 先把游戏切前台（脚本会等 9 秒），然后
 *   adb shell am start -a android.intent.action.VIEW -d file:///sdcard/xiangqi-p4/tools/p4-collect-frames.js \
 *     -t text/plain -n com.jdkshen.aijspro/.external.open.RunIntentActivity
 */
const adapter = require("../src/runtime/aijs-adapter.js");
const locateBoard = require("./locate-board.js");
const recognize = require("./recognize-board.js");
const geometry = require("../src/vision/geometry.js");
const legal = require("../src/chess/legal-moves.js");

const OUT_DIR = "/sdcard/xiangqi-p4/skin2/";
const WANT = 14;
const WAIT_MS = 9000;

function log(text) {
    console.log("COLLECT " + text);
}

function shellScreencap(path) {
    const result = shell("screencap -p " + path, { shizuku: true, timeout: 12000 });
    return result && result.code === 0;
}

function tapAt(grid, col, row) {
    const point = geometry.pointAt(grid, col, row);
    const x = Math.round(point.x);
    const y = Math.round(point.y);
    const result = shell("input tap " + x + " " + y, { shizuku: true, timeout: 5000 });
    return result && result.code === 0;
}

function observe(grid, path) {
    if (!shellScreencap(path)) {
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
    const result = recognize.run(image, reused, {
        orientation: "RED_BOTTOM",
        confidence: 0.25,
        autoAlign: false
    }, null);
    try { image.recycle(); } catch (error) { /* 忽略 */ }
    return result;
}

log("等待 " + WAIT_MS + "ms（PC 端把游戏切前台）");
sleep(WAIT_MS);

const located = locateBoard.locateBoardOnScreen({});
if (!located.ok) {
    log("定位失败：" + located.reason);
} else {
    // 会话级几何校准（这一步决定了颜色判断对不对）
    const calibrated = recognize.calibrateGrid(located.imageObject, located, {
        orientation: "RED_BOTTOM", confidence: 0.25, autoAlign: false,
        search: { maxShift: 18, step: 3 }, maxCandidates: 25
    });
    const grid = (calibrated && calibrated.ok && calibrated.grid) ? calibrated.grid : located.grid;
    log("几何校准=" + (calibrated && calibrated.ok
        ? ("偏移(" + calibrated.dx + "," + calibrated.dy + ") 未知格" + calibrated.unknown)
        : "失败，用原始定位"));

    const drafts = {};
    const accepted = { files: [], layouts: {} };
    let first = observe(grid, "/sdcard/yolo-bench/_collect-first.png");
    if (!first || !first.ok || first.unknownCells > 2) {
        log("首帧识别不可用（" + (first ? ("未知格 " + first.unknownCells) : "截图/识别失败") + "），放弃");
    } else {
        log("首帧：" + first.layout + "（未知格 " + first.unknownCells + "）");
        let board = first.chars.slice();
        // 不知道轮到谁：两边各试一次，谁能让局面真的变，谁就是我方
        let mySide = null;
        let index = 0;
        let attempts = 0;
        const limit = WANT * 4;

        function tryMove(side) {
            const moves = legal.allLegalMoves(board, side);
            if (!moves.length) {
                return null;
            }
            return moves[Math.floor(Math.random() * moves.length)];
        }

        while (index < WANT && attempts < limit) {
            attempts += 1;
            const side = mySide || (attempts % 2 === 0 ? "w" : "b");
            const move = tryMove(side);
            if (!move) {
                log("第 " + attempts + " 次：" + side + " 没有合法着法（可能识别错了），换边");
                mySide = null;
                continue;
            }
            const file = OUT_DIR + "skin2-" + ("0" + (index + 1)).slice(-2) + ".png";
            tapAt(grid, move.fromCol, move.fromRow);
            sleep(300);
            tapAt(grid, move.toCol, move.toRow);
            sleep(2600);                       // 等对方应手
            const after = observe(grid, file);
            if (!after || !after.ok) {
                log("第 " + attempts + " 次 " + side + " " + move.iccs + "：识别失败");
                continue;
            }
            const diff = legal.diffBoards(board, after.chars);
            if (diff.length === 0) {
                log("第 " + attempts + " 次 " + side + " " + move.iccs + "：局面没变（游戏没接受）");
                if (mySide === null) {
                    mySide = (side === "w") ? "b" : "w";
                } else {
                    mySide = null;             // 连续失败就重新判断
                }
                continue;
            }
            if (mySide === null) {
                mySide = side;
                log("判定我方=" + mySide);
            }
            index += 1;
            accepted.files.push(file.split("/").pop());
            accepted.layouts[file.split("/").pop()] = after.layout;
            log(index + "/" + WANT + " " + side + " " + move.iccs + " → 变化 " + diff.length + " 格，" +
                "未知格 " + after.unknownCells + "：" + after.layout);
            board = after.chars.slice();
        }

        try {
            if (files && typeof files.write === "function") {
                files.write(OUT_DIR + "draft.json", JSON.stringify(accepted, null, 2));
                log("草稿已写 " + OUT_DIR + "draft.json");
            }
        } catch (error) {
            log("写草稿失败：" + String(error && error.message ? error.message : error));
        }
        log("完成：收下 " + index + " 帧，尝试 " + attempts + " 次");
    }
}
log("COLLECT done");
