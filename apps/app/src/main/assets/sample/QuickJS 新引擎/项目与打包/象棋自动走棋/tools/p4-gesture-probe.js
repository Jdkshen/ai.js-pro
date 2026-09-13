// @engine quickjs
/**
 * 手势风格探针：在游戏界面上同一个交点分别用 click / press / swipe / longClick 点一次，
 * 每次前后各采样棋盘交点中心像素，打印「变化点数」——>0 说明这个手势风格对方有反应。
 *
 * 背景：`input tap`（adb shell）能让腾讯天天象棋落子，但无障碍 `click()/press()` 返回 true
 * 却没有任何反应；这个探针用来判断是不是「手势形状」的问题（还是游戏/系统直接过滤无障碍事件）。
 */
const adapter = require("../src/runtime/aijs-adapter.js");
const locateBoard = require("./locate-board.js");
const geometry = require("../src/vision/geometry.js");

const SAMPLE = { fromCol: 1, fromRow: 7 };   // 红炮

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
        if (a[i][0] !== b[i][0] || a[i][1] !== b[i][1] || a[i][2] !== b[i][2]) {
            n++;
        }
    }
    return n;
}

function grab(grid) {
    const frame = adapter.capture("full", 400);
    if (!frame) {
        return null;
    }
    const points = sampleBoard(frame, grid);
    frame.recycle();
    return points;
}

if (!adapter.requestCapture("portrait")) {
    console.log("STYLE 0 capture-denied");
} else {
    const located = locateBoard.locateBoardOnScreen({});
    if (!located.ok) {
        console.log("STYLE 1 locate-fail " + located.reason);
    } else {
        const grid = located.grid;
        const point = geometry.pointAt(grid, SAMPLE.fromCol, SAMPLE.fromRow);
        const x = Math.round(point.x);
        const y = Math.round(point.y);
        console.log("STYLE 1 grid v" + grid.version + " 目标=(" + x + "," + y + ")" +
            " a11y=" + (auto.service ? "ready" : "null"));

        const baseline = grab(grid);
        console.log("STYLE 2 基线采样 " + (baseline ? "ok" : "fail"));

        function stage(name, action) {
            const before = grab(grid);
            const returned = action();
            sleep(1200);
            const after = grab(grid);
            const changed = (before && after) ? diffCount(before, after) : -1;
            console.log("STYLE " + name + " -> 返回 " + returned + "，画面变化点数 " + changed);
            return changed;
        }

        stage("click", function () { return click(x, y); });
        stage("press300", function () { return press(x, y, 300); });
        stage("swipe2px", function () { return swipe(x, y, x + 2, y + 2, 80); });
        stage("longClick", function () { return longClick(x, y); });
        console.log("STYLE done");
    }
}
