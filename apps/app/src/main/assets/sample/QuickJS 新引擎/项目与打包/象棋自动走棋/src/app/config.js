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
    /** 识别绘制档位：off / grid / cells / full。 */
    drawMode: "off",
    /** 几何自动微调（真机实测会把同一子并成一格，默认关）。 */
    autoAlign: false,
    /** 启动后是否把面板收成小窗（默认 false：日志要看得见）。 */
    collapse: false,
    /** 启动后是否自动开始自动走棋。 */
    start: false,
    /** 首个观察成功后是否自动声明"现在轮到我"。 */
    declareTurn: false,
    /** 自动停止（ms，0 = 不停）。 */
    stopAfterMs: 0
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
        // 默认 false（不收起）：收起会把日志区一起藏掉，只有无人值守跑才需要
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
