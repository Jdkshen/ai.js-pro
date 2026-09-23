// @engine quickjs
/**
 * 截图就绪性探针：从「脚本刚启动 + 马上切到游戏」这个最恶劣的时刻起，
 * 反复尝试截图，打印第几次/多少毫秒才成功。
 *
 * 背景：真机上多次出现
 *   `Timed out waiting for the first screen capture frame`
 * 连续 3 次（600/1500/3000ms 都超），整轮直接抛异常。
 * 自动走棋必须知道「要等多久才该开始截图」，否则每局开头都会白失败几轮。
 */
const adapter = require("../src/runtime/aijs-adapter.js");

if (!adapter.requestCapture("portrait")) {
    console.log("CAPW capture-denied");
} else {
    const t0 = Date.now();
    let ok = 0;
    let fail = 0;
    for (let i = 0; i < 15; i++) {
        try {
            const frame = adapter.capture("full", 1500, { attempts: 1 });
            if (frame) { frame.recycle(); }
            ok += 1;
            console.log("CAPW 第" + (i + 1) + "次成功 用时" + (Date.now() - t0) + "ms");
        } catch (error) {
            fail += 1;
            console.log("CAPW 第" + (i + 1) + "次失败 用时" + (Date.now() - t0) + "ms：" +
                String(error && error.message ? error.message : error).slice(0, 70));
        }
        sleep(800);
    }
    console.log("CAPW done ok=" + ok + " fail=" + fail + " 总用时=" + (Date.now() - t0) + "ms");
}
