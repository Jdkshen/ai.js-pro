// @engine quickjs
/**
 * 引擎路由：**只用 Pikafish**（原生 NNUE）。
 *
 * ⚠️ 2026-09-24（用户「把 js 引擎算法删除了 不要了」）：内置 JS 搜索（search.js/eval.js）、
 *    本地 UCI 引擎（local-engine.js）与算棋入口 engine.js.analyze() **已整体删除**，
 *    这里不再有任何 JS 回退 —— Pikafish 不可用就**明确失败**（驱动按「算不出来」处理，
 *    不再拿弱几百 Elo 的引擎兜底；那正是 09-24 那局把胜势走成和棋的直接原因）。
 *
 * 为什么还留这一层：面板/驱动/worker 都从这里取「算棋」和「谁在算」；
 * 「谁在算这步棋」只在一处决定，其它地方（日志/状态栏）都从这里读。
 *
 * 两种后端（setBackend）：
 *   "auto"（默认）：能用 Pikafish 就用；不可用则失败并记原因；
 *   "pikafish"   ：同上（保留键名兼容旧配置；两种模式现在行为一致）。
 */
"use strict";

const positionUtils = require("./engine.js");   // 只有局面工具（解析/校验/描述），不含搜索

let backendKey = "auto";
let pikafishModule = null;
let pikafishLoadError = "";
let lastFallbackReason = "";
let lastUsed = "pikafish";

/** 懒加载 Pikafish 适配器（宿主没有 $pikafish 时也不该让 require 直接炸）。 */
function pikafish() {
    if (pikafishModule == null && pikafishLoadError === "") {
        try {
            pikafishModule = require("./pikafish-engine.js");
        } catch (error) {
            pikafishLoadError = String(error && error.message ? error.message : error);
        }
    }
    return pikafishModule;
}

/** 设置后端：'auto' | 'pikafish'（'js'/其它未知键一律按 auto —— JS 引擎已移除）。 */
function setBackend(key) {
    const normalized = String(key == null ? "auto" : key).toLowerCase();
    backendKey = (normalized === "pikafish" || normalized === "native" || normalized === "pf") ? "pikafish" : "auto";
    return backendKey;
}

function backend() {
    return backendKey;
}

/** 透传 Pikafish 配置（evalFile/threads/hashMb；改完下次 analyze 生效）。 */
function configurePikafish(options) {
    const module = pikafish();
    if (module) {
        module.configure(options);
    }
}

/** 提前把原生引擎拉起来（面板开工时调，省掉第一步棋的 ~0.7s 权重加载）。 */
function warmUpPikafish() {
    const module = pikafish();
    if (!module || !module.available()) {
        return { ok: false, reason: pikafishUnavailableReason() };
    }
    return module.ensureStarted();
}

/** 停掉原生引擎（不切换后端；下次 analyze 自动重启）。 */
function stopPikafish() {
    const module = pikafish();
    if (module) {
        module.stop();
    }
}

function pikafishUnavailableReason() {
    if (pikafishLoadError) {
        return "Pikafish 适配器加载失败：" + pikafishLoadError;
    }
    const module = pikafish();
    if (!module) {
        return "Pikafish 适配器不可用";
    }
    if (!module.available()) {
        return "宿主没有 $pikafish（原生库未加载；目前只随包 arm64-v8a）";
    }
    return "";
}

/**
 * 当前解析到的引擎描述（日志/状态栏用）。JS 引擎已移除：不可用时明确写明不可用。
 */
function describe() {
    const module = pikafish();
    const usable = module && module.available();
    if (!usable) {
        const reason = pikafishUnavailableReason();
        return "Pikafish（不可用：" + reason + "；内置 JS 引擎已移除，不再回退）";
    }
    const config = module.configText();
    return backendKey === "pikafish" ? (config + "〔强制〕") : (config + "〔自动〕");
}

/** 最近一次失败原因（面板/日志排障用）。 */
function lastFallback() {
    return lastFallbackReason;
}

/**
 * 算一步棋——与 pikafish-engine 同契约。
 * ⚠️ JS 引擎已移除：Pikafish 不可用/失败就**原样返回失败**，绝不降级；
 * 上层（驱动）把 ok:false 当作「这步算不出来」处理（重试/等待/记日志）。
 * @param position FEN / {board, side} / 90 长数组
 * @param options  其余同 pikafish-engine（movetime/excludeIccs/positionCommand…）
 */
function analyze(position, options) {
    const opts = options || {};
    const module = pikafish();
    if (!module || !module.available()) {
        lastFallbackReason = pikafishUnavailableReason();
        lastUsed = "none";
        return {
            ok: false,
            stage: "native",
            reason: "Pikafish 不可用（内置 JS 引擎已移除，不再回退）：" + lastFallbackReason,
            side: opts.side
        };
    }
    const result = module.analyze(position, opts);
    lastUsed = "pikafish";
    return result;
}

/** 单次调用/全局设置的统一解析：'pikafish' 认强制；其余（含旧的 'js'）一律跟随全局。 */
function resolveWanted(requested) {
    if (requested == null) {
        return backendKey;
    }
    const normalized = String(requested).toLowerCase();
    if (normalized === "pikafish" || normalized === "native" || normalized === "pf") {
        return "pikafish";
    }
    return backendKey;
}

/** 这步棋实际用了哪个引擎（analyze 之后读；日志用）。 */
function lastEngine() {
    return lastUsed;
}

module.exports = {
    // 与 engine.js（局面工具）对齐的常用面（面板/自检别的地方也用得到）
    analyze: analyze,
    resolvePosition: positionUtils.resolvePosition,
    inspect: positionUtils.inspect,
    describe: positionUtils.describe,
    applyBest: positionUtils.applyBest,
    // 路由自己的面
    setBackend: setBackend,
    backend: backend,
    configurePikafish: configurePikafish,
    warmUpPikafish: warmUpPikafish,
    stopPikafish: stopPikafish,
    pikafishAvailable: function () {
        const module = pikafish();
        return !!(module && module.available());
    },
    pikafishUnavailableReason: pikafishUnavailableReason,
    engineDescription: describe,
    lastEngine: lastEngine,
    lastFallback: lastFallback
};
