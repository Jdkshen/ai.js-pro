// @engine quickjs
/**
 * 抓帧耗时拆解探针（2026-09-20，用户问「速度怎么这么慢」）。
 *
 * 已知：真机一次观察里 `抓帧（含藏窗）≈ 930ms`，而且一次观察默认抓 2 帧 ⇒ ≈1.9s，
 * 占一轮观察的 2/3。但 930ms 里到底是哪一段慢没量过：
 *   ① `shell screencap -p`（设备端 PNG 编码 + 写盘）
 *   ② `images.read`（PNG 解码 + 建帧）
 *   ③ 还是 /sdcard 的 FUSE 写盘拖的
 *
 * ⚠️ 块注释里别写「星号 + 斜杠」连在一起（写路径时很容易顺手写成两个星号再接斜杠）——
 *    那个序列本身就是注释结束符，会把后半行当代码解析（2026-09-20 本文件就踩了，
 *    报 `Unexpected identifier` / `SyntaxError: expecting ';'`）。
 *
 * 做法：把两者拆开各测 N 次，另外测三种对照：
 *   · raw：`screencap <file>`（不加 -p，输出原始 RGBA，约 10MB）—— 去掉 PNG 编码；
 *   · 换目录：写到 `/data/local/tmp`（不走 /sdcard 的 FUSE）—— 看写盘是不是瓶颈；
 *   · 投屏通道：`captureScreen()`（MediaProjection，若授权可用）—— 看有没有更快的通道。
 *
 * 日志前缀 `[capbench]`。注意本探针**不改任何配置**，只测量。
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return String(__dirname).replace(/\\/g, "/").replace(/\/tools$/, "");
        }
    } catch (ignore) {
        // 忽略
    }
    return ".";
}());

function load(relative) {
    return require(BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative));
}

const adapter = load("src/runtime/aijs-adapter.js");

const REPEATS = 3;

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[capbench] " + text);
    }
}

function median(list) {
    const sorted = list.slice().sort(function (a, b) { return a - b; });
    return sorted[Math.floor(sorted.length / 2)];
}

/** 跑 N 次取中位数；单次失败就记 null */
function bench(label, fn) {
    const times = [];
    let detail = null;
    for (let i = 0; i < REPEATS; i++) {
        const started = Date.now();
        try {
            const out = fn();
            const ms = Date.now() - started;
            times.push(ms);
            if (out && out.detail) {
                detail = out.detail;
            }
        } catch (error) {
            log(label + " 第 " + (i + 1) + " 次失败：" + error);
            times.push(-1);
        }
    }
    const ok = times.filter(function (one) { return one >= 0; });
    log(label + "：" + (ok.length ? (median(ok) + "ms（" + times.join("/") + "）") : "全失败") +
        (detail ? "｜" + detail : ""));
    return ok.length ? median(ok) : null;
}

function shellBackend() {
    const caps = adapter.capabilities();
    return caps.shizuku ? "shizuku" : (caps.shell ? "root" : null);
}

/** 跑一条 shell 命令（与适配器同一条通道） */
function runShell(command, backend) {
    const options = { timeout: 20000 };
    options[backend] = true;
    if (typeof shell === "undefined") {
        throw new Error("宿主没有 shell");
    }
    const result = shell(command, options);
    if (!result || result.code !== 0) {
        throw new Error("exit " + (result ? result.code : "?") + " " +
            String((result && result.error) || "").trim().slice(0, 80));
    }
    return result;
}

function fileSize(path) {
    try {
        const result = runShell("ls -l " + path, backend);
        const text = String((result && result.result) || "").trim();
        return text.split(/\s+/).slice(4).join(" ") || "?";
    } catch (error) {
        return "?";
    }
}

let backend = null;

function main() {
    log("开始｜shizuku=" + adapter.capabilities().shizuku + "｜shell=" + adapter.capabilities().shell);
    backend = shellBackend();
    if (!backend) {
        log("没有 shell 通道（需要 Shizuku/root），后面的 shell 项都跑不了");
    }

    // —— ① 现状路径：screencap -p 到 /sdcard，再 images.read ——
    const PNG_SDCARD = "/sdcard/aijspro-bench.png";
    const PNG_TMP = "/data/local/tmp/aijspro-bench.png";
    const RAW_SDCARD = "/sdcard/aijspro-bench.raw";
    if (backend) {
        bench("shell: screencap -p /sdcard（现状：PNG 编码+写 FUSE）", function () {
            runShell("screencap -p " + PNG_SDCARD, backend);
            return { detail: "文件 " + fileSize(PNG_SDCARD) };
        });
        bench("images.read(/sdcard PNG)（解码+建帧）", function () {
            const frame = images.read(PNG_SDCARD);
            if (!frame) {
                throw new Error("读不回来");
            }
            const info = frame.width + "x" + frame.height;
            try {
                frame.recycle();
            } catch (ignore) {
                // 忽略
            }
            return { detail: info };
        });
        // —— ② raw：去掉 PNG 编码 ——
        bench("shell: screencap /sdcard（raw RGBA，无 PNG 编码）", function () {
            runShell("screencap " + RAW_SDCARD, backend);
            return { detail: "文件 " + fileSize(RAW_SDCARD) };
        });
        // —— ③ 换目录：不走 /sdcard 的 FUSE ——
        bench("shell: screencap -p /data/local/tmp（不走 FUSE）", function () {
            runShell("screencap -p " + PNG_TMP, backend);
            return { detail: "文件 " + fileSize(PNG_TMP) };
        });
        bench("images.read(/data/local/tmp PNG)（应用能不能读它）", function () {
            const frame = images.read(PNG_TMP);
            if (!frame) {
                throw new Error("读不回来");
            }
            const info = frame.width + "x" + frame.height;
            try {
                frame.recycle();
            } catch (ignore) {
                // 忽略
            }
            return { detail: info };
        });
    }

    // —— ④ raw 快通道的正确性：与 PNG 逐点对照（通道顺序/头偏移错了这里立刻红）——
    if (backend && typeof images.readRaw === "function") {
        try {
            runShell("screencap -p " + PNG_SDCARD, backend);
            runShell("screencap " + RAW_SDCARD, backend);
            const pngFrame = images.read(PNG_SDCARD);
            const size = adapter.screenSize();
            const rawFrame = images.readRaw(RAW_SDCARD, size.width, size.height);
            const points = [[500, 400], [100, 100], [900, 2000], [540, 1200], [200, 1500], [950, 700]];
            let same = 0;
            const lines = [];
            for (let i = 0; i < points.length; i++) {
                const x = points[i][0];
                const y = points[i][1];
                const a = pngFrame.pixel(x, y) & 0xFFFFFF;
                const b = rawFrame.pixel(x, y) & 0xFFFFFF;
                const hit = a === b;
                same += hit ? 1 : 0;
                lines.push("(" + x + "," + y + ")" + (hit ? "=" : "!=") + b.toString(16));
            }
            log("raw vs PNG 像素对照：" + same + "/" + points.length + " 一致｜" + lines.join(" "));
            log(same === points.length
                ? "=> 通道顺序与头偏移都对（RGBA + 16 字节头）"
                : "=> ⚠️ 有像素不一致（画面在动的话属正常；全不一致就要查通道顺序）");
            pngFrame.recycle();
            rawFrame.recycle();
        } catch (error) {
            log("raw vs PNG 对照失败：" + error);
        }
    } else {
        log("引擎没有 images.readRaw（旧 .so），raw 快通道不可用");
    }

    // —— ⑤ 投屏通道（MediaProjection）——
    try {
        if (adapter.requestCapture("portrait")) {
            bench("captureScreen()（MediaProjection，full）", function () {
                const frame = adapter.capture("full", 1500, { backend: "screen" });
                if (!frame) {
                    throw new Error("没帧");
                }
                const info = frame.width + "x" + frame.height + " 通道=" + adapter.describeFrame(frame);
                return { detail: info };
            });
        } else {
            log("投屏通道：授权失败（跳过）");
        }
    } catch (error) {
        log("投屏通道不可用：" + error);
    }

    // —— ⑥ 组合：一次完整抓帧（现在默认走 raw 快通道）——
    bench("合计：adapter.capture('full')（自动选通道）", function () {
        const frame = adapter.capture("full", 1500);
        if (!frame) {
            throw new Error("没帧");
        }
        const info = "通道=" + adapter.describeFrame(frame) + "｜raw=" + JSON.stringify(adapter.shellCaptureInfo());
        return { detail: info };
    });

    log("结束");
}

main();
