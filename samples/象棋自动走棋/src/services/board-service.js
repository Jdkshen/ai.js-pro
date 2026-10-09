// @engine quickjs
/**
 * 棋盘服务（§14：把 locate / recognize / audit 这些"用一次就散"的工具调用收成业务入口）。
 *
 * 现在面板、驱动、探针、离线工具各写一遍 `locateAndCalibrate()` / `recognizeScreen()` 调用序列，
 * 抓帧藏窗、几何版本校验、未知格统计这些"每次都该做"的事经常漏做一步。
 */
"use strict";

const timing = require("../runtime/timing.js");
const safety = require("../automation/observation-safety.js");

function createBoardService(options) {
    const opts = options || {};
    const ports = opts.ports;
    const locateBoard = opts.locateBoard;
    const geometry = opts.geometry || null;
    const recorder = opts.recorder || null;
    /**
     * 几何精校准注入（可选）：`calibrate(image, located, options)` ⇒ `{ok, grid, dx, dy, unknown, tried, layout}`。
     * 入口不给就只做定位（面板自己管校准）。
     */
    const calibrate = typeof opts.calibrate === "function" ? opts.calibrate : null;
    /**
     * 稳定帧能力注入（可选）；不包含第二套分类器：
     *   vision = {
     *     gate,                       // frame-gate 实例（可空；可空=跳过稳定帧判定）
     *     samples(image, grid),       // 棋盘区域采样（**数字数组**，frame-gate 的输入）
     *     log(text)                   // 限频日志（可空）
     *   }
     */
    const vision = opts.vision || null;
    /**
     * 唯一棋子分类源；缺少时明确返回失败：
     *   glyph = { recognize(image, grid, options) → {ok, chars, unknownCells, validation, layout, stats}, enabled? }
     * 规则校验失败或未知格超限则丢弃当前观察，等待下一帧。
     */
    const glyph = opts.glyph || null;

    let grid = null;
    let lastResult = null;
    let lastFrame = null;
    /** 抓帧前后的窗口处理由调用方注入（面板=窗口管理器；离线工具=空实现）。 */
    const hideWindow = typeof opts.hideWindow === "function" ? opts.hideWindow : function (fn) { return fn(); };
    const note = typeof opts.note === "function" ? opts.note : function () { };
    /**
     * 最近一次 `recognizeSmart()` 的分段计时（面板/探针打印用）。
     * 形如 `{ smart: {sections,total}, pipeline: {sections,total}|null, legacy: bool }`。
     * 为什么放在服务层：面板的 recognize 依赖被驱动的「两帧合并」包着（一次观察可能调两次），
     * 只有服务层知道「每一次识别」自己的分段。
     */
    let lastTimingDetail = null;
    function rememberTiming(clock, pipelineResult) {
        lastTimingDetail = {
            smart: clock.summary("收尾"),
            pipeline: (pipelineResult && pipelineResult.timing) ? pipelineResult.timing : null,
            legacy: !!(pipelineResult && pipelineResult.method === "legacy")
        };
    }
    /** 稳定帧的上一次采样（识别服务内部状态） */
    let lastSamples = null;
    let lastGateNoteAt = 0;
    let lastFallbackNoteAt = 0; // 字形校验失败日志的限频时间（保留兼容字段名）

    function setGrid(next) {
        grid = next || null;
        return grid;
    }

    /**
     * 丢掉稳定帧基线。切前台/重建画面之后调用：下一帧当作首帧（预热），
     * 不会拿切窗前的旧采样去配新画面而误判「不稳定」（2026-09-20 面板侧行为收到了服务层）。
     */
    function resetFrameBaseline() {
        lastSamples = null;
    }

    /** 抓一帧（自动处理藏窗）。 */
    function grab() {
        return hideWindow(function () {
            const frame = ports.capture({ backend: opts.captureBackend });
            if (frame) {
                lastFrame = frame;
            }
            return frame;
        });
    }

    /** 定位 + 校准；成功返回 grid。 */
    function locate(options) {
        const started = ports.now();
        const grabbed = grab();
        if (!grabbed) {
            return { ok: false, reason: "拿不到帧", ms: ports.now() - started };
        }
        let located = null;
        try {
            located = locateBoard.locateBoardInImage
                ? locateBoard.locateBoardInImage(grabbed.image, options || {})
                : locateBoard.locate(grabbed.image, options || {});
        } catch (error) {
            return { ok: false, reason: "定位异常：" + error, ms: ports.now() - started };
        }
        if (!located || !located.ok) {
            return {
                ok: false, reason: located && located.reason ? located.reason : "定位没有结果",
                ms: ports.now() - started
            };
        }
        grid = located.grid;
        // 几何精校准（可选注入）：按「最终未知格数」打分挑偏移 —— 入口各自写的版本里，
        // 主界面和悬浮窗用的参数不同，收进服务层后两家一份（2026-09-20 入口收口）。
        if (calibrate) {
            let calibrated = null;
            try {
                calibrated = calibrate(grabbed.image, located, options || {});
            } catch (error) {
                note("几何校准异常（沿用定位几何）：" + (error && error.message ? error.message : error));
            }
            if (calibrated && calibrated.ok && calibrated.grid) {
                grid = calibrated.grid;
                note("几何校准：偏移(" + calibrated.dx + "," + calibrated.dy + ") 未知格 " +
                    calibrated.unknown + " 试了 " + calibrated.tried + " 个候选" +
                    (calibrated.layout ? (" 局面 " + calibrated.layout) : ""));
            } else {
                note("几何校准没有结果（沿用定位几何）：" + ((calibrated && calibrated.reason) || "未说明"));
            }
        }
        const result = { ok: true, grid: grid, ms: ports.now() - started, located: located };
        note("棋盘已定位：起点 (" + Math.round(grid.originX) + "," + Math.round(grid.originY) +
            ") 格距 (" + Math.round(grid.stepX) + "," + Math.round(grid.stepY) + ") 版本 v" + grid.version);
        return result;
    }


    /**
     * 完整识别（**唯一识别链 = 字形识别**，2026-09-24 用户口径「其他的都不要了」）：
     * 稳定帧 → 字形识别 → 规则门 + 未知格闸门。不通过 = 这一帧不观察（不回退流水线/旧路径；
     * 驱动对丢帧免疫，下一帧继续）。
     *
     * 返回：
     *   · `{ok:false, unstable:true, reason}` ⇒ 画面在动，这一帧不观察（方案 §3.1）；
     *   · `{ok:false, reason}` ⇒ 字形读不过（规则门/未知格超限/不可用），同样按「不观察」处理；
     *   · `{ok:true, method:"glyph", chars, rawChars, unknownCells, grid, frame, ...}`。
     */
    function recognizeSmart(options) {
        const cb = options || {};
        const clock = timing.create();
        lastTimingDetail = null;
        const glyphReady = !!(glyph && glyph.enabled !== false && typeof glyph.recognize === "function");
        if (!glyphReady) {
            return { ok: false, reason: "字形识别不可用：没有注入 glyph（模板缺失/已关闭）" };
        }
        if (!grid) {
            const located = locate({});
            if (!located.ok) {
                clock.mark("定位");
                rememberTiming(clock, null);
                return { ok: false, reason: "定位失败：" + located.reason };
            }
        }
        const grabbed = grab();
        clock.mark("抓帧（含藏窗）");
        if (!grabbed || !grabbed.image) {
            rememberTiming(clock, null);
            return { ok: false, reason: "识别失败：拿不到帧" };
        }
        if (typeof opts.checkGeometry === "function") {
            let checked;
            try { checked = opts.checkGeometry(grabbed.image, grid); }
            catch (error) { checked = { ok: false, geometryUncertain: true, reason: "几何复查异常：" + error }; }
            clock.mark("棋盘几何复查");
            if (checked && !checked.ok) {
                resetFrameBaseline();
                rememberTiming(clock, null);
                return { ok: false, geometryChanged: checked.geometryChanged === true,
                    geometryUncertain: checked.geometryChanged !== true, unstable: checked.geometryChanged !== true,
                    reason: checked.reason, frame: grabbed };
            }
        }
        // ① 稳定帧闸门（方案 §3.1）：动画中间帧上读出来的东西会把已知事实弄脏，宁可不观察
        const gate = (vision && vision.gate) || null;
        if (gate && typeof gate.check === "function") {
            const samples = typeof vision.samples === "function" ? vision.samples(grabbed.image, grid) : null;
            const verdict = gate.check(lastSamples, samples, "board");
            lastSamples = samples;
            if (!verdict.stable) {
                clock.mark("稳定帧");
                rememberTiming(clock, null);
                if (ports.now() - lastGateNoteAt > 4000) {
                    lastGateNoteAt = ports.now();
                    if (vision.log) {
                        vision.log("稳定帧：这一帧不送识别（" + verdict.reason + "）");
                    }
                }
                return { ok: false, unstable: true, reason: "帧不稳定：" + verdict.reason };
            }
        }
        clock.mark("稳定帧");
        // ② 字形识别（唯一识别链）：规则门 + 未知格闸门都过才算数；不过 = 这一帧不观察
        let g = null;
        try {
            g = glyph.recognize(grabbed.image, grid, {
                side: cb.side,
                referenceBoard: cb.referenceBoard,
                // ★ 2026-10-06（识别直通配套）：杀棋复核等「必须真读一眼」的观察（驱动把
                //   request.freshRead 挂在 options 上，见 observation.js）透传到识别层 ——
                //   reuse-gate 见到 fresh 就整帧重读；普通观察才允许「指纹没变的格沿用」。
                fresh: cb.freshRead === true || cb.fresh === true
            });
        } catch (error) {
            g = { ok: false, reason: "字形识别异常：" + (error && error.message ? error.message : error) };
        }
        const gUnknown = (g && g.ok && typeof g.unknownCells === "number")
            ? g.unknownCells
            : ((g && g.ok && g.chars && vision && typeof vision.countUnknown === "function")
                ? vision.countUnknown(g.chars) : null);
        const gAccepted = (g && g.ok && gate && typeof gate.acceptResult === "function" && gUnknown != null)
            ? gate.acceptResult(gUnknown)
            : { ok: true };
        clock.mark("字形识别");
        const royal = g && safety.inspect(g.rawChars || g.chars);
        if (royal && royal.unsafe) {
            rememberTiming(clock, null);
            return {ok:false, royalUnsafe:true, royal:royal, reason:royal.reason,
                rawChars:g.rawChars || g.chars.slice(), frame:grabbed};
        }
        if (g && g.ok && g.validation && g.validation.ok && gAccepted.ok) {
            lastTimingDetail = {
                smart: clock.summary("收尾"),
                pipeline: null,
                legacy: false,
                glyph: g.stats || null
            };
            return {
                ok: true,
                method: "glyph",
                grid: grid,
                chars: g.chars,
                rawChars: g.rawChars || g.chars.slice(),
                unknownCells: gUnknown == null ? 0 : gUnknown,
                layout: g.layout || null,
                validation: g.validation,
                glyphStats: g.stats || null,
                imageObject: grabbed.image,
                frameObject: grabbed.image,
                frame: grabbed
            };
        }
        const why = (g && g.ok)
            ? ((g.validation && g.validation.reason) || gAccepted.reason || "未说明")
            : ((g && g.reason) || "没有结果");
        rememberTiming(clock, null);
        if (vision && vision.log && ports.now() - lastFallbackNoteAt > 5000) {
            lastFallbackNoteAt = ports.now();
            vision.log("字形识别不通过（这一帧不观察）：" + why);
        }
        // ★ 2026-09-24（和棋那局）：失败帧也把图带上 —— 对局结算页恰恰会让字形识别整体失败
        //   （双方帅/将读不出来），而面板的「结算页探针」（probeGameOver）要拿这一帧去 OCR
        //   底部按钮带。原来失败不带 frame、探针又只挂在 ok 分支上 ⇒ 结算页永远探不到 ⇒
        //   对局结束后循环一直空转（浮窗一直显示「等稳定」）。
        return { ok: false, reason: "字形识别不通过：" + why, frame: grabbed };
    }

    /** 旧路径（原 `recognize`）：兼容既有调用方，行为不变。 */


    return {
        locate: locate,
        recognize: recognizeSmart,
        recognizeSmart: recognizeSmart,
        grab: grab,
        grid: function () { return grid; },
        setGrid: setGrid,
        resetFrameBaseline: resetFrameBaseline,
        lastResult: function () { return lastResult; },
        /** 最近一次识别的时间分段（方案 §7 性能分期；没跑过就是 null） */
        lastTiming: function () { return lastTimingDetail; },
        lastFrame: function () { return lastFrame; },
        clear: function () {
            grid = null;
            lastResult = null;
            lastFrame = null;
            lastSamples = null;
        },
        describe: function () {
            if (!grid) {
                return "棋盘：未定位";
            }
            return "棋盘：v" + grid.version + " 起点 (" + Math.round(grid.originX) + "," +
                Math.round(grid.originY) + ") 格距 (" + Math.round(grid.stepX) + "," +
                Math.round(grid.stepY) + ")";
        }
    };
}

module.exports = { createBoardService: createBoardService };
