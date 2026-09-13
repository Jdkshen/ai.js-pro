// @engine quickjs
/**
 * 几何偏移扫描（**离线版**：跑在一张已保存的截图文件上，完全不碰屏幕截图）。
 *
 * 为什么用离线：K40/MIUI 上 MediaProjection 会话一旦坏掉就再也出不了帧
 * （`Timed out waiting for the first screen capture frame`，连续 31s 全失败），
 * 而识别算法本身只依赖一帧图像 —— 用文件就能把「6 个固定未知格」这件事查清楚。
 *
 * 图片：/sdcard/yolo-bench/board1.png（PC 端 adb exec-out screencap 存下来的整屏图）
 */
const board = require("./recognize-board.js");
const locateBoard = require("./locate-board.js");

const IMAGE = "/sdcard/yolo-bench/board1.png";
const OFFSETS = [-20, -14, -7, 0, 7, 14, 20];

const located = locateBoard.locateBoardInImage(IMAGE, {});
if (!located.ok) {
    console.log("SHIFTF locate-fail " + located.reason);
} else {
    const base = located.grid;
    console.log("SHIFTF base origin=(" + Math.round(base.originX) + "," + Math.round(base.originY) +
        ") step=(" + Math.round(base.stepX) + "," + Math.round(base.stepY) + ")" +
        " residual=" + (base.quality ? base.quality.maxResidual : "?") +
        " columns=" + JSON.stringify(located.columns) + " rows=" + JSON.stringify(located.rows));

    function recognizeWith(grid) {
        const reused = {
            ok: true,
            method: "offline",
            reason: "离线：文件图 + 给定几何",
            grid: grid,
            imageObject: located.imageObject,
            frameObject: located.imageObject
        };
        return board.run(located.imageObject, reused, { orientation: "RED_BOTTOM" }, null);
    }

    const baseline = recognizeWith(base);
    console.log("SHIFTF 0,0 unknown=" + (baseline.ok ? baseline.unknownCells : -1) +
        " detections=" + (baseline.stats ? baseline.stats.detected : "?"));
    if (baseline.ok) {
        console.log("SHIFTF 0,0 layout " + baseline.layout);
    }

    let best = null;
    for (let i = 0; i < OFFSETS.length; i++) {
        for (let j = 0; j < OFFSETS.length; j++) {
            const dx = OFFSETS[i];
            const dy = OFFSETS[j];
            if (dx === 0 && dy === 0) { continue; }
            const shift = Object.assign({}, base, {
                originX: base.originX + dx,
                originY: base.originY + dy,
                version: (base.version || 1) + 1
            });
            const result = recognizeWith(shift);
            const unknown = result.ok ? result.unknownCells : -1;
            console.log("SHIFTF " + dx + "," + dy + " ok=" + result.ok + " unknown=" + unknown +
                (result.ok ? (" detections=" + (result.stats ? result.stats.detected : "?")) : ""));
            if (result.ok && (best == null || unknown < best.unknown)) {
                best = { dx: dx, dy: dy, unknown: unknown, layout: result.layout };
            }
        }
    }
    if (best) {
        console.log("SHIFTF best dx=" + best.dx + " dy=" + best.dy + " unknown=" + best.unknown);
        console.log("SHIFTF best layout " + best.layout);
    }
    console.log("SHIFTF done");
}
