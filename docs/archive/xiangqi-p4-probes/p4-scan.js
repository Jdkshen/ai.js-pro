// @engine quickjs
/**
 * P4 前的识别调参：在**真实棋局**上试几组识别参数，打印未知格数。
 *
 * 用法：把棋盘摆在前台，然后 adb 启动本脚本（无 ui，纯日志）：
 *   adb shell am start -a android.intent.action.VIEW -d file:///sdcard/xiangqi-p4/tools/p4-scan.js \
 *     -t text/plain -n com.jdkshen.aijspro/.external.open.RunIntentActivity
 *   adb logcat -d -s GlobalConsole:V
 */
const board = require("./recognize-board.js");
const locateBoard = require("./locate-board.js");
const adapter = require("../src/runtime/aijs-adapter.js");

if (!adapter.requestCapture("portrait")) {
    console.log("SCAN capture-denied");
} else {
    const located = locateBoard.locateBoardOnScreen({});
    console.log("SCAN locate ok=" + located.ok + (located.ok ? "" : (" reason=" + located.reason)));
    if (located.ok) {
        const grid = located.grid;
        console.log("SCAN grid origin=(" + Math.round(grid.originX) + "," + Math.round(grid.originY) +
            ") step=(" + Math.round(grid.stepX) + "," + Math.round(grid.stepY) + ")" +
            " quality=" + JSON.stringify(grid.quality));
        const configs = [
            ["默认", {}],
            ["conf0.15", { confidence: 0.15 }],
            ["conf0.35", { confidence: 0.35 }],
            ["margin1.5", { cropMargin: 1.5 }],
            ["margin2.0", { cropMargin: 2.0 }],
            ["margin1.5+conf0.15", { cropMargin: 1.5, confidence: 0.15 }],
            ["fq0.5", { frameQuality: 0.5 }],
            ["fq0.2", { frameQuality: 0.2 }]
        ];
        for (let i = 0; i < configs.length; i++) {
            const options = configs[i][1];
            options.grid = grid;
            options.orientation = "RED_BOTTOM";
            let result;
            try {
                result = board.recognizeScreen(options);
            } catch (error) {
                console.log("SCAN " + configs[i][0] + " 异常：" + error);
                continue;
            }
            console.log("SCAN " + configs[i][0] +
                " ok=" + result.ok +
                " unknown=" + (result.ok ? result.unknownCells : "-") +
                " detections=" + (result.stats ? result.stats.detected : "?") +
                " -> " + (result.ok ? result.layout : ("失败：" + result.reason)));
        }
    }
}
