// @engine quickjs
/**
 * 配置层（§14：同一配置只在 config 定义一次；默认值、校验、迁移、读 autorun 都在这儿）。
 *
 * 之前这一坨逻辑长在 `悬浮窗运行与测试.js` 的 main() 里：读文件、解析 JSON、逐项校验、
 * 顺手把值写进一堆模块级变量 —— 既没法单测，也没法在第二个入口（main.js）复用，
 * 而且"非法值静默退回默认"这种坑（拼错 clickMethod 整个对照实验作废）就藏在那儿。
 *
 * 这里只做：**读 → 解析 → 校验 → 归一化**，返回一个不可变的结果对象；
 * 应用（写回各模块的变量）由 bootstrap/controller 负责。
 */
"use strict";

const labels = require("../ui/labels.js");

const DEFAULTS = {
    /** 目标游戏包名（前台守卫要用它判断"焦点在不在游戏上"）。 */
    gamePackage: "com.tencent.qqgame.xq",
    /** 运行模式：watch / advice / auto（见 ui/labels 的 MODES）。 */
    mode: "watch",
    /** 截图通道偏好：shell / app / auto。 */
    captureBackend: "shell",
    /** 点击通道：auto / shizuku / accessibility。 */
    clickMethod: "auto",
    /** 按压时长（ms）：有些游戏会丢弃过短的注入点击（K40 + 天天象棋需 ≥300ms）。 */
    clickPressMs: 350,
    /** 藏窗后到出手的等待（ms）。 */
    clickHideSettleMs: 120,
    /** 算棋跑在哪里：worker / inline。 */
    computeMode: "worker",
    /** 正常观察：worker / inline；窗口截图可用时启用后台视觉，落子确认仍同步。 */
    visionMode: "worker",
    /** 识别绘制档位：off / grid / cells / full。 */
    drawMode: "moves",
    /** 几何自动微调（真机实测会把同一子并成一格，默认关）。 */
    autoAlign: false,
    /** 默认收成 ImGui 操作窗；点「更多」显示完整参数和日志。 */
    collapse: true,
    /** 启动后是否自动开始自动走棋。 */
    start: false,
    /** 首个观察成功后是否自动声明"现在轮到我"。 */
    declareTurn: false,
    /** 自动停止（ms，0 = 不停）。 */
    stopAfterMs: 0,
    /**
     * 自动重载（分钟，0 = 关；默认 60）：到点把面板脚本换到新引擎里跑。
     * 为什么要它（2026-09-26）：quickjs 引擎偶发内部崩溃（JS_DefineProperty 空指针，
     * 09-24/25/26 三次同点墓碑），JS 层 try/catch 拦不住 —— 定期换新引擎把「崩在长跑中」摊薄。
     * 面板设置经 storages 保留；「上次在跑」会在重载/崩溃后自动继续。
     */
    autoReloadMin: 60
    /**
     * （2026-09-27 移除）远程算棋（LAN 助攻）：整条链已下线，引擎回归本机算力。
     * 旧 autorun 里的 remoteEngine 会被 parseAutorun 提示一次并忽略（见那里的说明）。
     */
};

/** 校验结果里的一条问题。 */
function issue(level, key, message, value) {
    return { level: level, key: key, message: message, value: value };
}

/**
 * 解析并校验一段 autorun 文本。
 *
 * @param text autorun 文件原文（可为空）
 * @returns { ok, config, issues, raw }
 *          ok=false 表示"读/解析失败"（调用方应继续用默认配置，但要打日志）；
 *          issues 里 level="error" 的条目表示**必须终止启动**（例如通道名拼错）。
 */
function parseAutorun(text) {
    const issues = [];
    const trimmed = String(text == null ? "" : text).trim();
    if (!trimmed) {
        return { ok: true, config: {}, issues: issues, raw: null, empty: true };
    }
    let raw = null;
    try {
        raw = JSON.parse(trimmed);
    } catch (error) {
        issues.push(issue("error", "json", "autorun 不是合法 JSON：" + error, trimmed));
        return { ok: false, config: {}, issues: issues, raw: null };
    }
    if (!raw || typeof raw !== "object") {
        issues.push(issue("error", "json", "autorun 顶层必须是对象", trimmed));
        return { ok: false, config: {}, issues: issues, raw: null };
    }

    const config = {};

    if (raw.mode != null) {
        const mode = String(raw.mode);
        if (labels.MODES.indexOf(mode) < 0) {
            issues.push(issue("error", "mode", "未知模式：" + mode +
                "（只支持 " + labels.MODES.join(" / ") + "）", mode));
        } else {
            config.mode = mode;
        }
    }

    if (raw.gamePackage != null) {
        const pkg = String(raw.gamePackage).trim();
        if (!pkg) {
            issues.push(issue("warn", "gamePackage", "gamePackage 为空，沿用默认", pkg));
        } else {
            config.gamePackage = pkg;
        }
    }

    if (raw.clickMethod != null) {
        // ⚠️ 非法值**不静默退回 auto**：拼错一个字母就整轮对照实验作废（真机踩过）
        const method = String(raw.clickMethod);
        if (labels.CHANNELS.indexOf(method) < 0) {
            issues.push(issue("error", "clickMethod", "非法点击通道：" + method +
                "（只支持 " + labels.CHANNELS.join(" / ") + "）", method));
            config.clickMethod = method;   // 原样透传，让 createTap 明确报错
        } else {
            config.clickMethod = method;
        }
    }

    if (raw.clickPressMs != null) {
        const ms = Math.round(Number(raw.clickPressMs));
        if (!isFinite(ms) || ms <= 0) {
            issues.push(issue("warn", "clickPressMs", "按压时长非法，沿用默认", raw.clickPressMs));
        } else {
            config.clickPressMs = Math.min(2000, Math.max(1, ms));
        }
    }

    if (raw.clickHideSettleMs != null) {
        const ms = Math.round(Number(raw.clickHideSettleMs));
        if (!isFinite(ms) || ms < 0) {
            issues.push(issue("warn", "clickHideSettleMs", "藏窗等待非法，沿用默认", raw.clickHideSettleMs));
        } else {
            config.clickHideSettleMs = Math.min(3000, ms);
        }
    }

    if (raw.captureBackend != null) {
        const backend = String(raw.captureBackend);
        if (labels.CAPTURE_LABEL[backend] == null) {
            issues.push(issue("warn", "captureBackend", "未知截图通道：" + backend + "（沿用默认）", backend));
        } else {
            config.captureBackend = backend;
        }
    }

    if (raw.computeMode != null) {
        config.computeMode = String(raw.computeMode) === "inline" ? "inline" : "worker";
    }
    if (raw.visionMode != null) {
        const mode = String(raw.visionMode);
        if (mode !== "worker" && mode !== "inline")
            issues.push(issue("error", "visionMode", "视觉模式仅支持 worker / inline", mode));
        else config.visionMode = mode;
    }

    if (raw.drawMode != null) {
        const draw = String(raw.drawMode);
        if (labels.DRAW_ORDER.indexOf(draw) < 0) {
            issues.push(issue("warn", "drawMode", "未知绘制档位：" + draw + "（沿用默认）", draw));
        } else {
            config.drawMode = draw;
        }
    }

    if (raw.autoAlign != null) {
        config.autoAlign = !!raw.autoAlign;
    }

    if (raw.collapse != null) {
        // 显式 false 可直接显示完整参数与日志。
        config.collapse = !!raw.collapse;
    }

    if (raw.start != null) {
        config.start = !!raw.start;
    }

    if (raw.declareTurn != null) {
        config.declareTurn = !!raw.declareTurn;
    }

    if (raw.stopAfterMs != null) {
        const ms = Math.round(Number(raw.stopAfterMs));
        if (!isFinite(ms) || ms < 0) {
            issues.push(issue("warn", "stopAfterMs", "自动停止时间非法，按不停处理", raw.stopAfterMs));
            config.stopAfterMs = 0;
        } else {
            config.stopAfterMs = ms;
        }
    }

    if (raw.autoReloadMin != null) {
        // 自动重载（分钟，0 = 关）：非法值不致命，按默认处理并记一条提示
        const min = Math.round(Number(raw.autoReloadMin));
        if (!isFinite(min) || min < 0) {
            issues.push(issue("warn", "autoReloadMin", "自动重载周期非法（分钟，0=关），沿用默认", raw.autoReloadMin));
        } else {
            config.autoReloadMin = Math.min(24 * 60, min);
        }
    }

    if (raw.remoteEngine != null) {
        // 远程算棋（LAN 助攻）已于 2026-09-27 整条移除：旧配置只提示、不生效，也不拦启动。
        // 明确报一条 warn 是因为「手里还留着旧 autorun 的用户」很容易以为电脑端还在帮忙算棋。
        issues.push(issue("warn", "remoteEngine", "远程算棋已移除，此配置被忽略（引擎回归本机算力）", raw.remoteEngine));
    }

    return { ok: true, config: config, issues: issues, raw: raw };
}

/** 用默认值补齐（不改原对象）。 */
function withDefaults(overrides) {
    const out = {};
    const keys = Object.keys(DEFAULTS);
    for (let i = 0; i < keys.length; i++) {
        out[keys[i]] = DEFAULTS[keys[i]];
    }
    const given = overrides || {};
    const givenKeys = Object.keys(given);
    for (let i = 0; i < givenKeys.length; i++) {
        if (givenKeys[i] === "remoteEngine") {
            continue;   // 远程算棋已移除：任何入口都不许把它带回来（2026-09-27）
        }
        if (given[givenKeys[i]] !== undefined) {
            out[givenKeys[i]] = given[givenKeys[i]];
        }
    }
    return out;
}

/** issues 里有没有必须终止的（level="error"）。 */
function hasBlockingIssue(issues) {
    const list = issues || [];
    for (let i = 0; i < list.length; i++) {
        if (list[i].level === "error") {
            return true;
        }
    }
    return false;
}

/**
 * 读 autorun 文件（唯一碰文件系统的地方，便于测试时替换）。
 *
 * @param readFile (path) -> string|null
 * @param path autorun 路径
 * @returns { ok, config, issues, raw, empty, path }
 */
function loadAutorun(readFile, path) {
    let text = null;
    try {
        text = typeof readFile === "function" ? readFile(path) : null;
    } catch (error) {
        return {
            ok: false, config: {}, raw: null, path: path,
            issues: [issue("error", "read", "读 autorun 失败：" + error, path)]
        };
    }
    const parsed = parseAutorun(text);
    parsed.path = path;
    parsed.text = String(text == null ? "" : text).trim();
    return parsed;
}

module.exports = {
    DEFAULTS: DEFAULTS,
    parseAutorun: parseAutorun,
    withDefaults: withDefaults,
    hasBlockingIssue: hasBlockingIssue,
    loadAutorun: loadAutorun
};
