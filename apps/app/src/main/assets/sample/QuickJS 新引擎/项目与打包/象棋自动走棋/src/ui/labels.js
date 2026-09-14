// @engine quickjs
/**
 * 界面文案（§14：同一文案只在 labels 定义一次）。
 *
 * 为什么单独一份：同一句话以前在页面、悬浮窗、日志、报告里各写一遍，改一处漏三处
 * （真机排查时最容易吃亏的就是"日志说 A、界面说 B"）。
 * 这里只放**展示字符串**，不放逻辑、不引用任何宿主能力。
 */
"use strict";

const MODES = ["watch", "advice", "auto"];

const MODE_LABEL = {
    watch: "① 只识别",
    advice: "② 着法建议",
    auto: "③ 自动走棋"
};

/** 状态机状态 → 中文。未知状态原样返回（免得掩盖新状态）。 */
const STATE_LABEL = {
    STOPPED: "已停止",
    INITIALIZING: "初始化",
    CALIBRATING: "定位中",
    WAIT_STABLE: "等稳定",
    WAIT_TURN: "等轮次",
    THINKING: "思考中",
    SUGGESTED: "已给建议",
    PRECHECK: "出手前核对",
    CLICKING: "点击中",
    VERIFYING: "确认中",
    RECOVERING: "恢复中",
    PAUSED: "已暂停",
    ERROR: "出错"
};

/** 识别绘制档位（顺序即按钮循环顺序）。 */
const DRAW_ORDER = ["off", "grid", "cells", "full"];
const DRAW_LABEL = {
    off: "关",
    grid: "网格",
    cells: "格子",
    full: "全量"
};

/** 点击通道（顺序即按钮循环顺序）；与 adapter.createTap 的合法值一致。 */
const CHANNELS = ["auto", "shizuku", "accessibility"];
const CHANNEL_LABEL = {
    auto: "自动",
    shizuku: "Shizuku",
    accessibility: "无障碍"
};

/** 截图通道。 */
const CAPTURE_LABEL = {
    shell: "shell",
    app: "应用截屏",
    auto: "自动"
};

/** 面板顶部那一行状态文案的固定片段（避免各处拼写不一致）。 */
const TEXT = {
    focusGameOk: "前台 游戏✓",
    focusGameBad: "前台 ✗（点击会被自己吃掉）",
    lockHeld: "持有循环锁",
    lockFree: "未持锁",
    unknownCells: function (count) { return "未知格 " + count; },
    turnMine: "轮到我方",
    turnTheirs: "轮到对方",
    turnUnknown: "轮次未知"
};

module.exports = {
    MODES: MODES,
    MODE_LABEL: MODE_LABEL,
    STATE_LABEL: STATE_LABEL,
    DRAW_ORDER: DRAW_ORDER,
    DRAW_LABEL: DRAW_LABEL,
    CHANNELS: CHANNELS,
    CHANNEL_LABEL: CHANNEL_LABEL,
    CAPTURE_LABEL: CAPTURE_LABEL,
    TEXT: TEXT,
    /** 状态中文名（未知状态原样返回，方便一眼看出有新状态）。 */
    stateText: function (state) {
        return STATE_LABEL[state] || String(state == null ? "?" : state);
    }
};
