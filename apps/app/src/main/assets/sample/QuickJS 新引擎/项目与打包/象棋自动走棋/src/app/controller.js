// @engine quickjs
/**
 * 应用控制器（§14：所有按钮通过控制器命令进入业务；校验参数；统一生命周期）。
 *
 * 现状：面板的按钮回调里直接改全局变量、直接调 stopLoop()/buildDriver()/refreshOverlay()，
 * 于是"点了停止但点击还在跑""改了通道但 driver 还是旧的"这类问题只能靠人盯日志。
 * 这里把入口收敛成命令：**校验 → 交给注入的处理函数 → 记录事件**。
 *
 * 命令集合（§14.3 的第一批，参数必须校验）：
 *   START {mode} / PAUSE {reason} / RESUME / STOP / RELOCATE / RECOGNIZE / AUDIT
 *   DECLARE_TURN {side} / NEW_SESSION / SET_MODE {mode} / SET_CHANNEL {channel}
 *   SET_PRESS {ms} / SET_DRAW {drawMode} / PROBE {tapProbe} / SELFTEST / CONTRACT
 */
"use strict";

const labels = require("../ui/labels.js");

/** 每个命令的参数校验 + 允许的运行态。 */
const COMMANDS = {
    START: { args: ["mode"], guard: "notRunning" },
    PAUSE: { args: ["reason"], guard: "running" },
    RESUME: { args: [], guard: "running" },
    STOP: { args: [], guard: "any" },
    RELOCATE: { args: [], guard: "any" },
    RECOGNIZE: { args: [], guard: "any" },
    AUDIT: { args: [], guard: "any" },
    DECLARE_TURN: { args: ["side"], guard: "any" },
    NEW_SESSION: { args: [], guard: "any" },
    SET_MODE: { args: ["mode"], guard: "notRunning" },
    SET_CHANNEL: { args: ["channel"], guard: "any" },
    SET_PRESS: { args: ["ms"], guard: "any" },
    SET_DRAW: { args: ["drawMode"], guard: "any" },
    PROBE: { args: ["tapProbe"], guard: "notRunning" },
    SELFTEST: { args: [], guard: "any" },
    CONTRACT: { args: [], guard: "any" }
};

function createController(options) {
    const opts = options || {};
    const handlers = opts.handlers || {};
    const store = opts.store || null;
    const recorder = opts.recorder || null;
    const isRunning = typeof opts.isRunning === "function" ? opts.isRunning : function () { return false; };
    const history = [];
    const counters = {};

    function reject(name, reason, arg) {
        counters[name + ":rejected"] = (counters[name + ":rejected"] || 0) + 1;
        history.push({ at: Date.now(), name: name, ok: false, reason: reason, arg: arg == null ? null : arg });
        if (recorder) {
            recorder.log("命令 " + name + " 被拒：" + reason);
        }
        return { ok: false, reason: reason, name: name };
    }

    /** 参数校验（返回 null 表示通过）。 */
    function validate(name, arg) {
        const spec = COMMANDS[name];
        if (!spec) {
            return "未知命令：" + name;
        }
        if (spec.guard === "running" && !isRunning()) {
            return "当前没有在跑，命令 " + name + " 无效";
        }
        if (spec.guard === "notRunning" && isRunning()) {
            return "正在跑，先停止再执行 " + name;
        }
        if (name === "START") {
            const mode = arg && arg.mode != null ? String(arg.mode) : null;
            if (mode != null && labels.MODES.indexOf(mode) < 0) {
                return "未知模式：" + mode;
            }
        }
        if (name === "SET_MODE") {
            const mode = arg && arg.mode != null ? String(arg.mode) : null;
            if (mode == null || labels.MODES.indexOf(mode) < 0) {
                return "未知模式：" + mode;
            }
        }
        if (name === "SET_CHANNEL") {
            const channel = arg && arg.channel != null ? String(arg.channel) : null;
            if (channel == null || labels.CHANNELS.indexOf(channel) < 0) {
                return "未知点击通道：" + channel;
            }
        }
        if (name === "SET_DRAW") {
            const draw = arg && arg.drawMode != null ? String(arg.drawMode) : null;
            if (draw == null || labels.DRAW_ORDER.indexOf(draw) < 0) {
                return "未知绘制档位：" + draw;
            }
        }
        if (name === "SET_PRESS") {
            const ms = arg && arg.ms != null ? Number(arg.ms) : NaN;
            if (!isFinite(ms) || ms <= 0 || ms > 2000) {
                return "按压时长非法（1..2000ms）：" + (arg && arg.ms);
            }
        }
        if (name === "DECLARE_TURN") {
            const side = arg && arg.side != null ? String(arg.side) : null;
            if (side !== "w" && side !== "b") {
                return "轮次只能是 w / b，收到：" + side;
            }
        }
        if (name === "PROBE" && (!arg || !arg.tapProbe)) {
            return "探针命令缺少 tapProbe 参数";
        }
        return null;
    }

    function run(name, arg) {
        const key = String(name || "").toUpperCase();
        const problem = validate(key, arg);
        if (problem) {
            return reject(key, problem, arg);
        }
        const handler = handlers[key];
        if (typeof handler !== "function") {
            return reject(key, "命令没有绑定处理函数（框架接入不完全）", arg);
        }
        counters[key] = (counters[key] || 0) + 1;
        const started = Date.now();
        let result = null;
        let error = null;
        try {
            result = handler(arg == null ? {} : arg);
        } catch (exception) {
            error = String(exception && exception.message ? exception.message : exception);
        }
        history.push({
            at: started, name: key, ok: error == null, error: error,
            ms: Date.now() - started, arg: arg == null ? null : arg
        });
        if (history.length > 100) {
            history.shift();
        }
        if (error != null && recorder) {
            recorder.log("命令 " + key + " 执行出错：" + error);
        }
        return { ok: error == null, name: key, result: result, error: error };
    }

    return {
        run: run,
        commands: function () { return Object.keys(COMMANDS); },
        counters: function () { return Object.assign({}, counters); },
        history: function () { return history.slice(); },
        last: function () { return history.length ? history[history.length - 1] : null; }
    };
}

module.exports = { COMMANDS: COMMANDS, createController: createController };
