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
    /** 常用控制两行 + 参数一行 + 状态 + 进度；诊断工具在主界面。 */
    height: 380,
    marginX: 30,
    /**
     * 默认停在棋盘上沿之上（K40 ≈ 0.10 × 屏高）；定位成功后由 snapPanelAboveBoard 再贴一次。
     * ⚠️ 2026-09-25 用户报「悬浮窗挡住几何来定位盘」：0.11 那档（264）在 K40 上底边正好压到
     *   棋盘含子上沿（~635）⇒ 每次抓帧都被判定「压住」而藏面板、定位帧也被迫含藏窗。改 0.10（240）。
     */
    defaultTopRatio: 0.10,
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
    /**
     * 面板底边与「棋盘含子上沿」之间留的缝（px）。
     * ⚠️ 旧口径只留 10px（写在 snapPanelAboveBoard 里），棋盘/网格位置一抖就压上棋盘上沿
     *   （用户投诉「挡住几何定位盘」）；而「压住 ⇒ 每帧抓帧藏面板」既慢，又把面板白块带进定位帧。
     *   这里给够 24px 硬缝。
     */
    boardClearGap: 24,
    /** 按钮行的行高与间距（px）。 */
    rowHeight: 56,
    rowGap: 6,
    /**
     * 状态行 / 进度区 / 日志区的高度（px）。
     * ⚠️ 2026-09-20：字号提上去后必须按**行高**给高度，否则字会被上下切一半
     *   （用户截图里状态行那个「未」字被削掉就是它；10sp 行高 ≈37px、11sp ≈40px）。
     */
    // 10sp 状态字在部分 MIUI 字体上有额外上下字面距，留 4px 余量避免“未运行”等字被削边。
    statusHeight: 46,
    /** 进度/轮次区：3 行（11sp 行高 ≈40px；3×40 + padding 16 + 余量 = 144） */
    stepHeight: 136,
    /** 日志窗高度：标题 + 6 行短事件，减少对棋盘下方空间的占用。 */
    logHeight: 380,
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
    bg: "#F7F8FA", bgSoft: "#FFFFFF", border: "#E6EAF0",
    title: "#20252E", text: "#303744", textDim: "#667080",
    ok: "#24865C", warn: "#B97819", bad: "#C24B52", accent: "#3486FF"
};

/** 以 1080px 宽为基准缩放窗口和所有子控件，避免 720px 溢出和高分屏留白。 */
function layoutFor(screen) {
    const width = screen && Number(screen.width) > 0 ? Number(screen.width) : 1080;
    const height = screen && Number(screen.height) > 0 ? Number(screen.height) : 2400;
    const nominal = Math.max(0.55, Math.min(1.2, width / 1080));
    const scale = Math.min(nominal, Math.max(0.3, (width - 16) / PANEL.width));
    function px(value) { return Math.max(1, Math.round(value * scale)); }
    const panel = Object.assign({}, PANEL, {
        width: px(PANEL.width), height: px(PANEL.height), marginX: Math.max(8, px(PANEL.marginX)),
        menuGap: px(PANEL.menuGap), boardClearGap: px(PANEL.boardClearGap),
        rowHeight: px(PANEL.rowHeight), rowGap: px(PANEL.rowGap),
        statusHeight: px(PANEL.statusHeight), stepHeight: px(PANEL.stepHeight),
        logHeight: Math.min(px(PANEL.logHeight), Math.round(height * 0.22)),
        logHeaderHeight: px(64),
        radiusPanel: px(22), radiusButton: px(12), radiusCard: px(16)
    });
    return {
        scale: scale, PANEL: panel, compactWidth: px(690), compactHeight: px(64),
        logWrap: Math.max(15, Math.round(LOG_WRAP * scale))
    };
}

/**
 * 日志窗显示几行。
 * ⚠️ 2026-09-20 用户要求「面板不要默认隐藏日志」：原来是 2（而且面板一启动还会自动收成小窗）
 *    ⇒ 等于看不到日志；改成 4 行 + **默认不收起**。
 * ⚠️ 2026-09-20 晚：日志移到**屏幕下方单独一窗**后，这块地方本来就不挡东西 ⇒ 提到 8 行。
 * ⚠️ 11sp 实测行高 ~40px，日志窗 420 − 上下 padding 20 = 400 ⇒ 装得下 10 行；
 *    显示 9 行（留 1 行余量给折行条目，否则最后一条会被窗口底边切）。
 */
const LOG_LINES_COMPACT = 6;
/**
 * 日志每条**一行放几个字**（超出就**折行**，不截断）。
 * ⚠️ 2026-09-20 用户两次强调「显示完整」：以前用截断（42/38→后来 32 字）+「…」，
 *    长句（路径、「等对方应手：把游戏带回前台（之前 触摸焦点 …）」）永远看不全。
 *    现在改为**折行**：一条日志最多占 LOG_WRAP_LINES 行，超过才用「…」收尾。
 *    11sp 在 1000px 宽里约 33 字/行。
 * ⚠️ 2026-09-24 用户第三次强调「（下面日志窗的字）没显示完整」：3 行上限是 09-20「日志挤在
 *    上方面板 6 行小窗」时代定的；日志早已挪到屏幕下方 9 行的独立窗口，3 行装不下「本轮账」
 *    这种长行（真机截图里 尾行被「…」截掉）。上限提到 **9 = 窗口行数**：最新那条能占满整窗
 *    完整显示（旧的被顶掉），超过 9 行才收编。
 */
const LOG_WRAP = 44;
const LOG_WRAP_LINES = 2;

module.exports = {
    PANEL: PANEL,
    FONT: FONT,
    COLOR: COLOR,
    layoutFor: layoutFor,
    LOG_LINES_COMPACT: LOG_LINES_COMPACT,
    LOG_WRAP: LOG_WRAP,
    LOG_WRAP_LINES: LOG_WRAP_LINES
};
