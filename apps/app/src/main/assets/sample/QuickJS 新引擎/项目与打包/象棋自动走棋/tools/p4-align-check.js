// @engine quickjs
/**
 * 几何自动对齐（autoAlign）离线验收：同一张图，关/开对齐各跑一次，比较未知格与几何报告。
 * 用文件图，不碰屏幕截图（K40 上 MediaProjection 会话坏了就再也出不了帧）。
 */
const board = require("./recognize-board.js");
const locateBoard = require("./locate-board.js");

const IMAGE = "/sdcard/yolo-bench/board1.png";

const located = locateBoard.locateBoardInImage(IMAGE, {});
if (!located.ok) {
    console.log("ALIGN locate-fail " + located.reason);
} else {
    const grid = located.grid;
    console.log("ALIGN grid origin=(" + Math.round(grid.originX) + "," + Math.round(grid.originY) +
        ") step=(" + Math.round(grid.stepX) + "," + Math.round(grid.stepY) + ")");

    function runWith(autoAlign) {
        const reused = {
            ok: true,
            method: "offline",
            grid: grid,
            imageObject: located.imageObject,
            frameObject: located.imageObject
        };
        return board.run(located.imageObject, reused, { orientation: "RED_BOTTOM", autoAlign: autoAlign }, null);
    }

    const off = runWith(false);
    console.log("ALIGN off ok=" + off.ok + " unknown=" + off.unknownCells +
        " detections=" + (off.stats ? off.stats.detected : "?"));
    console.log("ALIGN off layout " + off.layout);
    console.log("ALIGN off sideUnknown=" + (off.stats ? off.stats.sideUnknown : "?"));

    const on = runWith(true);
    console.log("ALIGN on ok=" + on.ok + " unknown=" + on.unknownCells +
        " detections=" + (on.stats ? on.stats.detected : "?"));
    console.log("ALIGN on layout " + on.layout);
    console.log("ALIGN on report " + JSON.stringify(on.stats ? on.stats.autoAlign : null));
    console.log("ALIGN done");
}
