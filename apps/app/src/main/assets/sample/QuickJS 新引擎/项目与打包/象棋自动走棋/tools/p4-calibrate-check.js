// @engine quickjs
/**
 * 会话级几何校准离线验收：同一张图，先看基准几何的未知格，再跑 calibrateGrid，
 * 打印它选中的偏移与校准后的未知格数。
 */
const board = require("./recognize-board.js");
const locateBoard = require("./locate-board.js");

const IMAGE = "/sdcard/yolo-bench/board1.png";

const located = locateBoard.locateBoardInImage(IMAGE, {});
if (!located.ok) {
    console.log("CAL locate-fail " + located.reason);
} else {
    const base = located.grid;
    console.log("CAL base origin=(" + Math.round(base.originX) + "," + Math.round(base.originY) + ")");

    const plain = board.run(located.imageObject, located, { orientation: "RED_BOTTOM", autoAlign: false }, null);
    console.log("CAL 校准前 unknown=" + plain.unknownCells + " layout " + plain.layout);

    const started = Date.now();
    const calibrated = board.calibrateGrid(located.imageObject, located, {
        orientation: "RED_BOTTOM",
        autoAlign: false,
        search: { maxShift: 18, step: 7 },
        maxCandidates: 16
    });
    console.log("CAL 用时 " + (Date.now() - started) + "ms tried=" + calibrated.tried +
        " dx=" + calibrated.dx + " dy=" + calibrated.dy + " unknown=" + calibrated.unknown +
        " sideUnknown=" + calibrated.sideUnknown);
    console.log("CAL " + calibrated.reason);
    if (calibrated.layout) {
        console.log("CAL 校准后 layout " + calibrated.layout);
    }
    console.log("CAL done");
}
