// @engine quickjs
/**
 * 定位器探针：一次跑多组 region，看哪组能让 locateBoardInImage 成功。
 * 之前它报「格点阵与给定棋盘范围对不齐（命中 7/9 条）；请把范围调到紧贴棋盘」，
 * 说明默认范围不对，这里显式给几组候选。
 */
const locate = require("./locate-board.js");

const SHOT = "/sdcard/xiangqi-p4/_now.png";
const CASES = [
    { tag: "默认", opts: {} },
    { tag: "宽A", opts: { region: { x: 40, y: 600, width: 1010, height: 1120 } } },
    { tag: "宽B", opts: { region: { x: 20, y: 560, width: 1050, height: 1220 } } },
    { tag: "宽C", opts: { region: { x: 60, y: 620, width: 970, height: 1090 } } },
    { tag: "宽D", opts: { region: { x: 50, y: 630, width: 990, height: 1080 } } },
    { tag: "宽E", opts: { region: { x: 45, y: 610, width: 1000, height: 1110 } } }
];

for (let i = 0; i < CASES.length; i++) {
    const item = CASES[i];
    let res = null;
    try {
        res = locate.locateBoardInImage(SHOT, item.opts);
    } catch (error) {
        console.log("LP " + item.tag + " 抛异常 " + String(error && error.message ? error.message : error));
        continue;
    }
    if (res && res.ok) {
        console.log("LP " + item.tag + " OK 几何(" + res.grid.originX.toFixed(2) + "," + res.grid.originY.toFixed(2) +
            "/" + res.grid.stepX.toFixed(3) + "," + res.grid.stepY.toFixed(3) + ") 竖" +
            (res.columns ? res.columns + "/9" : "?") + " 横" + (res.rows ? res.rows + "/10" : "?"));
    } else {
        console.log("LP " + item.tag + " 失败：" + (res && res.reason ? res.reason : "无"));
    }
}
console.log("LP done");
