// @engine quickjs
/**
 * 几何偏移扫描：验证「6 个固定未知格」到底是不是定位精度导致的。
 *
 * 现象：这块皮肤上每一帧都有**同样 6 格**识别不出来（多帧投票没用）。
 * 怀疑：board-locator 给出的 originX/originY 与真实格线中心有 ~10px 系统性偏差，
 *       边角格的裁剪框偏出去，模型就看不懂了。
 *
 * 做法：把定位结果的原点平移若干像素，逐组识别，打印未知格数 —— 若某个偏移下
 *       未知格变成 0，就说明问题在几何而不在模型/阈值。
 *
 * 用法：棋盘在前台，然后 adb 启动本脚本，读 GlobalConsole 里的 SHIFT 行。
 */
const board = require("./recognize-board.js");
const locateBoard = require("./locate-board.js");
const adapter = require("../src/runtime/aijs-adapter.js");

const OFFSETS = [-15, -7, 0, 7, 15];

// ⚠️ 截图会话的时机很关键（K40/MIUI 实测）：
//   在我们自己的界面还在前台时就 requestCapture，之后切到游戏，第一帧永远等不到
//   （Timed out waiting for the first screen capture frame，连续 31s 都失败）。
//   改成本脚本先 sleep、等 PC 端把游戏切前台之后再 requestCapture，就能出帧。
console.log("SHIFT 等待 9s（PC 端把游戏切前台）");
sleep(9000);

let granted = false;
for (let attempt = 0; attempt < 3 && !granted; attempt++) {
    granted = adapter.requestCapture("portrait") !== false;
    if (!granted) {
        console.log("SHIFT requestCapture 第" + (attempt + 1) + "次被拒");
        sleep(1500);
    }
}

if (!granted) {
    console.log("SHIFT capture-denied");
} else {
    const located = locateBoard.locateBoardOnScreen({});
    if (!located.ok) {
        console.log("SHIFT locate-fail " + located.reason);
    } else {
        const base = located.grid;
        console.log("SHIFT base origin=(" + Math.round(base.originX) + "," + Math.round(base.originY) +
            ") step=(" + Math.round(base.stepX) + "," + Math.round(base.stepY) +
            ") residual=" + (base.quality ? base.quality.maxResidual : "?"));

        let best = null;
        for (let i = 0; i < OFFSETS.length; i++) {
            for (let j = 0; j < OFFSETS.length; j++) {
                const dx = OFFSETS[i];
                const dy = OFFSETS[j];
                const shift = Object.assign({}, base, {
                    originX: base.originX + dx,
                    originY: base.originY + dy,
                    version: (base.version || 1) + 1
                });
                let result;
                try {
                    result = board.recognizeScreen({
                        grid: shift,
                        orientation: "RED_BOTTOM",
                        confidence: 0.25,
                        timeoutMs: 800
                    });
                } catch (error) {
                    console.log("SHIFT " + dx + "," + dy + " 异常：" +
                        String(error && error.message ? error.message : error).slice(0, 90));
                    continue;
                }
                const unknown = result.ok ? result.unknownCells : -1;
                console.log("SHIFT " + dx + "," + dy + " ok=" + result.ok +
                    " unknown=" + unknown +
                    (result.ok ? (" detections=" + (result.stats ? result.stats.detected : "?")) : ""));
                if (result.ok && (best == null || unknown < best.unknown)) {
                    best = { dx: dx, dy: dy, unknown: unknown, layout: result.layout, stats: result.stats };
                }
            }
        }
        if (best) {
            console.log("SHIFT best dx=" + best.dx + " dy=" + best.dy + " unknown=" + best.unknown);
            console.log("SHIFT best layout " + best.layout);
            if (best.stats) {
                console.log("SHIFT best stats " + JSON.stringify(best.stats));
            }
        }
        console.log("SHIFT done");
    }
}
