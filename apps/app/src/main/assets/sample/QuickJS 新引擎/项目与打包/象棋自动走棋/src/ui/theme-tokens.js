// @engine quickjs
/**
 * 主题令牌（§14：同一主题样式只在 tokens 定义一次）。
 *
 * 真机事故背景：面板/画板/日志区各写各的字号与颜色，改一处忘一处
 * （「挡住游戏按钮」「字太小看不清」两次返修都是这么来的）。
 * 这里只放数值与色值，**不**碰任何宿主接口。
 *
 * 尺寸单位遵循宿主 XML：
 *   · w/h/坐标 = 设备像素；textSize = sp；行高 ≈ 1.35 × 字号。
 */
"use strict";

/** 紧凑面板的几何（真机实测：不挡对手头像、也不挡底部按钮排）。 */
const PANEL = {
    width: 1020,
    height: 372,
    marginX: 30,
    /** 默认停在棋盘上沿之上（K40 ≈ 0.11 × 屏高）；定位成功后由 snapPanelAboveBoard 再贴一次。 */
    defaultTopRatio: 0.11,
    /** 按钮行的行高与间距（px）。 */
    rowHeight: 42,
    rowGap: 6,
    /** 状态行 / 步骤区 / 日志区的高度（px）。 */
    statusHeight: 34,
    stepHeight: 80,
    logHeight: 76
};

/** 字号（sp）。 */
const FONT = {
    title: 11,
    button: 11,
    status: 9,
    step: 9,
    log: 8
};

/** 颜色（ARGB 字符串，宿主 XML 直接吃）。 */
const COLOR = {
    bg: "#F2182434",
    bgSoft: "#E6101820",
    border: "#6633CCFF",
    title: "#FFE6F2FF",
    text: "#FFCFE3FF",
    textDim: "#FF93A9C4",
    ok: "#FF6BE08A",
    warn: "#FFF2C14E",
    bad: "#FFFF7A7A",
    accent: "#FF63B3FF"
};

/** 日志区最多显示几行（紧凑版式）。 */
const LOG_LINES_COMPACT = 2;
const LOG_TRUNCATE = 42;
const LOG_TRUNCATE_TAIL = 38;

module.exports = {
    PANEL: PANEL,
    FONT: FONT,
    COLOR: COLOR,
    LOG_LINES_COMPACT: LOG_LINES_COMPACT,
    LOG_TRUNCATE: LOG_TRUNCATE,
    LOG_TRUNCATE_TAIL: LOG_TRUNCATE_TAIL
};
