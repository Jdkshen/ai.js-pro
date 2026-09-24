// @engine quickjs
/**
 * 引擎路由：在**内置 JS 引擎**（engine.js）与 **Pikafish 原生引擎**（pikafish-engine.js）之间选后端。
 *
 * 为什么要有这一层：
 *   · 面板/驱动/worker 原来直接 require engine.js；换成 Pikafish 时如果逐个改调用点，
 *     很容易出现「worker 里用的还是 JS 引擎」这种半生效状态（这个项目已经因为
 *     worker 漏传 excludeIccs 踩过一次）；
 *   · 「谁在算这步棋」应该只在一处决定，其他地方（日志/状态栏）都从这里读。
 *
 * 三种后端（setBackend）：
 *   "auto"（默认）：能用 Pikafish 就用（已装权重、原生库在），起步失败自动回退 JS 并记原因；
 *   "pikafish"   ：只用 Pikafish（失败直接报错，方便对照/排障）；
 *   "js"         ：只用内置 JS 引擎（老路径，离线/无原生库时也是它）。
 *
 * 回退边界：请求带非空 `excludeIccs`（游戏拒绝过的着法/循环排除表）时，适配器用 MultiPV 选路
 * （v1.2：先 5 路、全被挡加宽到 12 路再扫、仍全被挡就放宽排除表取头名并带 excludeRelaxed 标记 ——
 * **不再因为排除表而回退**）；只有引擎启动失败、FEN 读不出来等真失败，auto 模式才回退 JS 并记原因。
 */
"use strict";

const jsEngine = require("./engine.js");

let backendKey = "auto";
let pikafishModule = null;
let pikafishLoadError = "";
let lastFallbackReason = "";
let lastUsed = "js";

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

/** 设置后端：'auto' | 'pikafish' | 'js'（不认识的键按 auto 处理）。 */
function setBackend(key) {
    const normalized = String(key == null ? "auto" : key).toLowerCase();
    backendKey = (normalized === "pikafish" || normalized === "native" || normalized === "pf") ? "pikafish"
        : (normalized === "js" || normalized === "javascript") ? "js"
            : "auto";
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
 * 当前实际会用的引擎名（用于日志/状态栏；「auto + 可用」= pikafish）。
 * 注意：excludeIccs 的回退是**单次**的，这里报的是常态选择。
 */
function describe() {
    if (backendKey === "js") {
        return "JS 引擎（内置搜索）";
    }
    const module = pikafish();
    const usable = module && module.available();
    if (!usable) {
        const reason = pikafishUnavailableReason();
        return backendKey === "pikafish"
            ? ("Pikafish（不可用：" + reason + "）")
            : ("JS 引擎（Pikafish 不可用：" + reason + "）");
    }
    const config = module.configText();
    return backendKey === "pikafish" ? (config + "〔强制〕") : (config + "〔自动〕");
}

/** 最近一次「因排除表回退到 JS」的原因（面板排障用）。 */
function lastFallback() {
    return lastFallbackReason;
}

/**
 * 算一步棋——与 engine.js 同契约（上层无感切换）。
 * @param position FEN / {board, side} / 90 长数组
 * @param options  engine: 'auto'|'pikafish'|'js'（单次覆盖）；其余同 engine.js
 */
function analyze(position, options) {
    const opts = options || {};
    const wanted = resolveWanted(opts.engine);
    if (wanted !== "js") {
        const module = pikafish();
        if (module && module.available()) {
            const result = module.analyze(position, opts);
            if (result.ok) {
                lastUsed = "pikafish";
                return result;
            }
            if (wanted === "pikafish") {
                lastUsed = "pikafish";
                return result;   // 强制模式：不算就直说失败，别偷偷降级
            }
            lastFallbackReason = "Pikafish 失败（" + result.reason + "）→ 本步回退 JS 引擎";
            lastUsed = "js";
            return Object.assign({ engine: "js", historyUsed: false, historyStatus: "fen" },
                jsEngine.analyze(position, opts));
        }
        if (wanted === "pikafish") {
            lastUsed = "pikafish";
            return {
                ok: false, stage: "native",
                reason: pikafishUnavailableReason(),
                side: opts.side
            };
        }
        lastFallbackReason = pikafishUnavailableReason();
    }
    lastUsed = "js";
    return Object.assign({ engine: "js", historyUsed: false, historyStatus: "fen" },
        jsEngine.analyze(position, opts));
}

/** 单次调用/全局设置的统一解析：null → 全局设置，'auto' → 跟随全局（不认识的键也算 auto）。 */
function resolveWanted(requested) {
    if (requested == null) {
        return backendKey;
    }
    const normalized = String(requested).toLowerCase();
    if (normalized === "pikafish" || normalized === "native" || normalized === "pf") {
        return "pikafish";
    }
    if (normalized === "js" || normalized === "javascript") {
        return "js";
    }
    return backendKey;
}

/** 这步棋实际用了哪个引擎（analyze 之后读；日志用）。 */
function lastEngine() {
    return lastUsed;
}

module.exports = {
    // 与 engine.js 对齐的常用面（面板/自检别的地方也用得到）
    analyze: analyze,
    resolvePosition: jsEngine.resolvePosition,
    inspect: jsEngine.inspect,
    describe: jsEngine.describe,
    applyBest: jsEngine.applyBest,
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
