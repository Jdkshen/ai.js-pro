// @engine quickjs
/**
 * 校准（calibrateGrid）的真机分场景验收。
 *
 * A 回归：基准几何本来就对时，校准**不许**把它改差（K40 实测过 dx=1,dy=1 把黑方 5 个卒认成 3 个）。
 * B 复现：把基准人为往下挪半格（50px），模拟线定位把第一条线锚错半格的那种帧，
 *         看校准能不能把偏移找回来（期望 dy ≈ -50、子数回到最多）。
 *
 * 跑法（K40）：
 *   adb push tools/recognize-board.js tools/p4-calibrate-shift-probe.js  →  /sdcard/xiangqi-k40/proj/tools/
 *   adb shell "am start -a android.intent.action.VIEW \
 *     -d file:///sdcard/xiangqi-k40/proj/tools/p4-calibrate-shift-probe.js -t text/plain \
 *     -n com.jdkshen.aijspro/.external.open.RunIntentActivity"
 */
const board = require("./recognize-board.js");
const locateBoard = require("./locate-board.js");

const IMAGE = "/sdcard/yolo-bench/board1.png";

const located = locateBoard.locateBoardInImage(IMAGE, {});
if (!located.ok) {
    console.log("SHIFT locate-fail " + located.reason);
} else {
    const base = located.grid;
    console.log("SHIFT base origin=(" + Math.round(base.originX) + "," + Math.round(base.originY) +
        ") step=(" + Math.round(base.stepX) + "," + Math.round(base.stepY) + ")");

    function check(tag, dx, dy) {
        const shifted = Object.assign({}, located, {
            grid: Object.assign({}, base, {
                originX: base.originX + dx,
                originY: base.originY + dy
            })
        });
        const started = Date.now();
        const r = board.calibrateGrid(located.imageObject, shifted, {
            orientation: "RED_BOTTOM",
            autoAlign: false,
            search: { maxShift: 18, step: 7 },
            maxCandidates: 16
        });
        console.log("SHIFT " + tag + " 人为偏移=(" + dx + "," + dy + ") 用时=" + (Date.now() - started) +
            "ms tried=" + r.tried + " 选中=(" + r.dx + "," + r.dy + ") unknown=" + r.unknown +
            " 子=" + r.pieces + "/" + r.maxPieces);
        console.log("SHIFT " + tag + " layout " + r.layout);
    }

    check("A基准", 0, 0);
    check("B偏半格", 0, 50);
    console.log("SHIFT done");
}
