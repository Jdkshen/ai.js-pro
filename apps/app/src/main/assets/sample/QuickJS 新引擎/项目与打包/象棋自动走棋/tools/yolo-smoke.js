// @engine quickjs
/**
 * YOLO 主识别冒烟（真机一次性）。
 *
 * 跑法（**必须先 force-stop**：RunIntentActivity 单实例，应用在跑时 am start 不会执行新脚本）：
 *   adb -s cccc62c7 shell am force-stop com.jdkshen.aijspro
 *   adb -s cccc62c7 shell am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity \
 *     --es path "/sdcard/xiangqi-p4/proj/tools/yolo-smoke.js"
 * 结果：logcat 搜 `YOLOSMOKE`，或直接 pull /sdcard/xiangqi-p4/yolo-smoke.txt
 *
 * 它做三件事：
 *   ① 引擎加载：yolo/ 下的 dex + .so + 模型（DexClassLoader/TflRunner）
 *   ② 直接读数一帧（不过服务层）
 *   ③ 过服务层多帧投票 `recognizeYolo()`（3 帧 / 130ms）
 * 手机停在真实棋局上时 FEN 才有可读性；其它画面只能证明「链子是活的」。
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return __dirname.replace(/[\/\\]tools$/, "");
        }
    } catch (ignore) { /* 默认当前目录 */ }
    return ".";
}());

function load(relative) {
    return require(BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative));
}

const OUT = "/sdcard/xiangqi-p4/yolo-smoke.txt";
const lines = [];

function say() {
    const parts = [];
    for (let i = 0; i < arguments.length; i++) {
        parts.push(String(arguments[i]));
    }
    const text = "YOLOSMOKE: " + parts.join(" ");
    lines.push(text);
    log(text);
}

function flush() {
    try {
        files.write(OUT, lines.join("\n") + "\n");
    } catch (error) {
        log("写结果文件失败：" + error);
    }
}

try {
    const yoloModule = load("src/vision/yolo-recognizer.js");
    const boardServiceModule = load("src/services/board-service.js");
    const adapter = load("src/runtime/aijs-adapter.js");
    say("start base=" + BASE_DIR);

    // 抓帧：走面板同款通道（适配器负责系统截屏 ↔ shell 切换；裸 images.captureScreen()
    // 在一次性脚本模式下会报 __aiNativeCaptureFrame 失败——真机踩过）。
    let captureNote = "";
    try {
        const granted = adapter.requestCapture("portrait");
        captureNote = "requestCapture=" + granted + " backend=" + adapter.captureBackend();
    } catch (error) {
        captureNote = "requestCapture 异常：" + error;
    }
    say("capture " + captureNote);
    function grab() {
        try {
            const frame = adapter.capture("full", 2000);
            return frame ? { image: frame, path: null, channel: adapter.captureChannel() } : null;
        } catch (error) {
            say("capture 异常：" + error);
            return null;
        }
    }

    const source = yoloModule.create({
        baseDir: BASE_DIR,
        log: function (text) { say("[src]", text); }
    });
    say("assetRoot=" + source.assetRoot);

    const server = source.ensureServer();
    say("engine=" + (server ? "ok" : "FAILED"));
    if (server) {
        // ② 直接读一帧（不过服务层）
        const grabbed = grab();
        say("grab=" + (grabbed ? ("ok channel=" + grabbed.channel) : "null"));
        if (grabbed) {
            const one = source.read(grabbed.image, 0.45, 0.45);
            if (one.ok) {
                say("read1 ok n=" + one.n + " flipped=" + one.flipped + " ms=" + one.ms);
                say("read1 fen=" + one.fen);
                say("read1 box=" + JSON.stringify(one.box));
            } else {
                say("read1 fail reason=" + one.reason);
            }
        }
        // ③ 服务层多帧投票
        const service = boardServiceModule.createBoardService({
            ports: {
                now: function () { return Date.now(); },
                capture: function () { return grab(); },
                sleep: function (ms) { sleep(ms); }
            },
            yolo: source,
            yoloOptions: { votes: 3, gapMs: 130, confidence: 0.45, iou: 0.45, minPieces: 3 }
        });
        const started = Date.now();
        const smart = service.recognizeYolo({});
        if (smart && smart.ok) {
            const st = smart.yoloStats || {};
            say("vote ok method=" + smart.method + " frames=" + st.frames + "/" + st.votes +
                " votedCells=" + st.votedCells + " unknownByVote=" + st.unknownByVote +
                " held=" + st.held + " unknown=" + smart.unknownCells +
                " avgMs=" + st.avgMs + " totalMs=" + (Date.now() - started));
            say("layout=" + smart.layout);
            say("validation=" + ((smart.validation && smart.validation.ok) ? "ok" : ("FAIL " + ((smart.validation && smart.validation.reason) || "?"))));
        } else {
            say("vote fail reason=" + ((smart && smart.reason) || "?") +
                " totalMs=" + (Date.now() - started));
        }
    }
    say("done");
} catch (error) {
    say("EXCEPTION " + (error && error.stack ? error.stack : error));
    say("done (error)");
}
flush();
