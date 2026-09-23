// @engine quickjs
/**
 * 端到端「走一步」探针 v2（前台对照版）。
 *
 * 关键教训：
 * 1) 之前用 `currentPackage()` 判断「游戏在不在前台」是错的 —— MIUI 游戏加速（包名
 *    com.miui.securitycenter）的悬浮侧栏挂在游戏上面，无障碍报出的前台包名一直是 securitycenter，
 *    哪怕 dumpsys 里 topResumedActivity 就是游戏。
 * 2) 判断前台必须用 dumpsys（走 Shizuku shell），不能用 a11y 的 currentPackage()。
 *
 * 通道由 /sdcard/xiangqi-p4/who.txt 决定：shizuku（默认）| a11y
 */
const adapter = require("../src/runtime/aijs-adapter.js");
const locateBoard = require("./locate-board.js");
const geometry = require("../src/vision/geometry.js");

const GAME = "com.tencent.qqgame.xq";
const DEFAULT_MOVE = { fromCol: 1, fromRow: 7, toCol: 4, toRow: 7 };   // 红炮二平五
const WAIT_MS = 9000;

/** 走法从 /sdcard/xiangqi-p4/move.txt 读：fromCol,fromRow,toCol,toRow */
function readMove() {
    try {
        if (files && typeof files.read === "function") {
            const text = String(files.read("/sdcard/xiangqi-p4/move.txt") || "").trim();
            const nums = text.split(/[^0-9]+/).filter(function (s) { return s !== ""; }).map(Number);
            if (nums.length >= 4) {
                return { fromCol: nums[0], fromRow: nums[1], toCol: nums[2], toRow: nums[3] };
            }
        }
    } catch (error) { /* 用默认走法 */ }
    return DEFAULT_MOVE;
}

function readChannel() {
    try {
        if (files && typeof files.read === "function") {
            const text = String(files.read("/sdcard/xiangqi-p4/who.txt") || "").trim();
            if (text) { return text.split("\n")[0].trim(); }
        }
    } catch (error) { /* 文件不存在就用默认 */ }
    return "shizuku";
}

/** 真·前台 Activity（dumpsys）：不受 MIUI 悬浮窗干扰 */
function topActivity() {
    try {
        const result = shell("dumpsys activity activities | grep topResumedActivity", { shizuku: true, timeout: 6000 });
        const text = String(result.result || "").replace(/\s+/g, " ");
        const at = text.indexOf("topResumedActivity=");
        return at >= 0 ? text.slice(at + 18, at + 120).trim() : text.slice(0, 120);
    } catch (error) {
        return "?";
    }
}

function waitForGame(limitMs) {
    const started = Date.now();
    while (Date.now() - started < limitMs) {
        const front = topActivity();
        if (front.indexOf(GAME) >= 0) { return front; }
        sleep(600);
    }
    return "";
}

function sampleBoard(frame, grid) {
    const points = [];
    for (let col = 0; col < 9; col++) {
        for (let row = 0; row < 10; row++) {
            const p = geometry.pointAt(grid, col, row);
            const pixel = images.pixel(frame, Math.round(p.x), Math.round(p.y));
            points.push([colors.red(pixel), colors.green(pixel), colors.blue(pixel)]);
        }
    }
    return points;
}

function diffCount(a, b) {
    let n = 0;
    for (let i = 0; i < a.length; i++) {
        if (a[i][0] !== b[i][0] || a[i][1] !== b[i][1] || a[i][2] !== b[i][2]) { n++; }
    }
    return n;
}

function grab(grid) {
    const frame = adapter.capture("full", 500);
    if (!frame) { return null; }
    const points = sampleBoard(frame, grid);
    frame.recycle();
    return points;
}

const channel = readChannel();
const MOVE = readMove();
console.log("MOVE 0 通道=" + channel + "｜走法=" + MOVE.fromCol + "," + MOVE.fromRow + "->" +
    MOVE.toCol + "," + MOVE.toRow + "｜等 " + WAIT_MS + "ms（PC 端把游戏切前台）｜前台=" + topActivity());
sleep(WAIT_MS);

const front = waitForGame(25000);
if (!front) {
    console.log("MOVE 1 游戏没到前台（当前=" + topActivity() + "），放弃");
} else if (!adapter.requestCapture("portrait")) {
    console.log("MOVE 1 capture-denied");
} else {
    console.log("MOVE 1 前台=" + front);
    const located = locateBoard.locateBoardOnScreen({});
    if (!located.ok) {
        console.log("MOVE 1 locate-fail " + located.reason);
    } else {
        const grid = located.grid;
        const from = geometry.pointAt(grid, MOVE.fromCol, MOVE.fromRow);
        const to = geometry.pointAt(grid, MOVE.toCol, MOVE.toRow);
        console.log("MOVE 1 grid v" + grid.version +
            " 起=(" + Math.round(from.x) + "," + Math.round(from.y) + ")" +
            " 终=(" + Math.round(to.x) + "," + Math.round(to.y) + ")");

        function inject(px, py) {
            const x = Math.round(px);
            const y = Math.round(py);
            if (channel === "a11y") {
                return "a11y press=" + press(x, y, 120);
            }
            const result = shell("input tap " + x + " " + y, { shizuku: true, timeout: 5000 });
            return "shizuku code=" + result.code +
                (result.code === 0 ? "" : " err=" + String(result.error).slice(0, 60));
        }

        const before = grab(grid);
        console.log("MOVE 2 第一下：" + inject(from.x, from.y) + "｜前台=" + topActivity());
        sleep(1400);
        const mid = grab(grid);
        console.log("MOVE 3 第二下：" + inject(to.x, to.y) + "｜前台=" + topActivity());
        sleep(1600);
        const after = grab(grid);
        console.log("MOVE 4 选中变化=" + ((before && mid) ? diffCount(before, mid) : -1) +
            "｜落子变化=" + ((mid && after) ? diffCount(mid, after) : -1) +
            "｜总变化=" + ((before && after) ? diffCount(before, after) : -1) +
            "｜前台=" + topActivity());
        console.log("MOVE done");
    }
}
