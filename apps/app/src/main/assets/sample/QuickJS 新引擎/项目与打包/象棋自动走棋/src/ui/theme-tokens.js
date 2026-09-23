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

/** 面板几何（真机实测：不挡棋盘、也不挡底部按钮排）。 */
const PANEL = {
    width: 1020,
    /** 424 = 三行按钮(42×3) + 状态(34) + 进度卡(80) + 日志区(128) + padding/余量。 */
    /**
     * ⚠️ 2026-09-20 用户口径（「悬浮窗面板在上面，日志在下面」；又提「字太小/面板好看点/轮次没显示完整」）：
     *   控制面板（三行按钮 + 状态 + 进度）在上方；日志单独一窗在下方。
     *   高度 = 3×rowHeight(48) + statusHeight(42) + stepHeight(144) + padding/余量 ≈ 364。
     */
    height: 364,
    marginX: 30,
    /** 默认停在棋盘上沿之上（K40 ≈ 0.11 × 屏高）；定位成功后由 snapPanelAboveBoard 再贴一次。 */
    defaultTopRatio: 0.11,
    /**
     * 面板「安全带」的上下比例（2026-09-20 用户口径：**不许挡棋盘、不许挡底部菜单**）：
     *   · safeTopRatio：顶边不低于 7% 屏高 —— 避开状态栏 + 系统「指针位置」调试浮层（K40: 168）
     *   · menuTopRatio：底边不超 93% 屏高 —— 游戏底部「菜单/显示分析/悔棋/提示」带的上沿
     *     （K40 实测：菜单带顶 ~2235、图标 2281 起。0.92 那版把空带留窄了，日志窗被顶成贴着棋盘）
     */
    safeTopRatio: 0.07,
    menuTopRatio: 0.93,
    /** 面板底边与底部菜单之间留的缝（px） */
    menuGap: 10,
    /** 按钮行的行高与间距（px）。 */
    rowHeight: 48,
    rowGap: 6,
    /**
     * 状态行 / 进度区 / 日志区的高度（px）。
     * ⚠️ 2026-09-20：字号提上去后必须按**行高**给高度，否则字会被上下切一半
     *   （用户截图里状态行那个「未」字被削掉就是它；10sp 行高 ≈37px、11sp ≈40px）。
     */
    statusHeight: 42,
    /** 进度/轮次区：3 行（11sp 行高 ≈40px；3×40 + padding 16 + 余量 = 144） */
    stepHeight: 144,
    /** 日志窗高度：装得下 10 行（11sp 行高 ~40px；10×40 + padding 20 = 420）。底边贴菜单上沿。 */
    logHeight: 420,
    /** 圆角（px）：这个引擎支持 radius，圆一点看起来不那么像调试窗口 */
    radiusPanel: 14,
    radiusButton: 9,
    radiusCard: 10
};

/** 字号（sp）。 */
const FONT = {
    title: 12,
    button: 11,
    status: 10,
    step: 11,
    log: 11
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

/**
 * 日志窗显示几行。
 * ⚠️ 2026-09-20 用户要求「面板不要默认隐藏日志」：原来是 2（而且面板一启动还会自动收成小窗）
 *    ⇒ 等于看不到日志；改成 4 行 + **默认不收起**。
 * ⚠️ 2026-09-20 晚：日志移到**屏幕下方单独一窗**后，这块地方本来就不挡东西 ⇒ 提到 8 行。
 * ⚠️ 11sp 实测行高 ~40px，日志窗 420 − 上下 padding 20 = 400 ⇒ 装得下 10 行；
 *    显示 9 行（留 1 行余量给折行条目，否则最后一条会被窗口底边切）。
 */
const LOG_LINES_COMPACT = 9;
/**
 * 日志每条**一行放几个字**（超出就**折行**，不截断）。
 * ⚠️ 2026-09-20 用户两次强调「显示完整」：以前用截断（42/38→后来 32 字）+「…」，
 *    长句（路径、「等对方应手：把游戏带回前台（之前 触摸焦点 …）」）永远看不全。
 *    现在改为**折行**：一条日志最多占 LOG_WRAP_LINES 行，超过才用「…」收尾。
 *    11sp 在 1000px 宽里约 33 字/行。
 */
const LOG_WRAP = 33;
const LOG_WRAP_LINES = 3;

module.exports = {
    PANEL: PANEL,
    FONT: FONT,
    COLOR: COLOR,
    LOG_LINES_COMPACT: LOG_LINES_COMPACT,
    LOG_WRAP: LOG_WRAP,
    LOG_WRAP_LINES: LOG_WRAP_LINES
};
