// @engine quickjs
"use strict";

/** controls：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    /**
     * 固定标题只显示「象棋助手」。运行状态另在进度区显示；不再因状态词长度、
     * 手机宽度或系统字体缩放，把标题末字挤到旁边的模式按钮下。
     */
    function panelTitleText() {
        // 状态已经在进度区和收起条显示。标题保持固定，系统大字体/小屏幕也不会截字。
        return "象棋助手";
    }

    function compactStateText() {
        if (panelState.starting) return "准备中";
        if (panelState.startError) return "待重试";
        let state = panelState.starting ? "启动中" : panelState.running ? "运行中" : "未运行";
        try {
            const key = panelState.driver && panelState.driver.machine ? panelState.driver.machine.state() : null;
            if (key && panelState.COMPACT_STATE_LABEL[key]) {
                state = panelState.COMPACT_STATE_LABEL[key];
            }
        } catch (error) {
            // 状态机还没建立时使用 running 的兜底文案
        }
        return state;
    }

    /** 点击仍绑在 text 上；外层 card 才能真正画出圆角，text/vertical 的 radius 属性会被宿主忽略。 */
    function fitText(maxSp) {
        return ' autoSizeMinTextSize="' + panelActions.panelPx(22) + 'px" autoSizeMaxTextSize="' + maxSp + 'sp"';
    }

    function button(id, label, width, bg, fg, height) {
        const row = height || panelState.PANEL.rowHeight;
        const w = panelActions.panelPx(width);
        const fill = bg || panelState.C.btnNeutral;
        const color =
            fg ||
            (fill === panelState.C.btnAccent
                ? panelState.C.btnAccentFg
                : fill === panelState.C.btnPrimary || fill === panelState.C.btnDanger
                  ? "#FFFFFF"
                  : panelState.C.btnNeutralFg);
        const emphasis =
            fill === panelState.C.btnPrimary || fill === panelState.C.btnDanger || fill === panelState.C.btnAccent
                ? ' textStyle="bold"'
                : "";
        return (
            '    <card w="' +
            w +
            'px" h="' +
            row +
            'px" cardCornerRadius="' +
            panelState.PANEL.radiusButton +
            'px" cardElevation="0dp" cardBackgroundColor="' +
            fill +
            '" clipToOutline="true">' +
            '<text id="' +
            id +
            '" text="' +
            label +
            '" w="' +
            w +
            'px" h="' +
            row +
            'px" textColor="' +
            color +
            '" textSize="' +
            panelState.themeTokens.FONT.button +
            '" gravity="center" includeFontPadding="false" singleLine="true"' +
            fitText(panelState.themeTokens.FONT.button) + emphasis +
            "/></card>"
        );
    }

    function gap(width) {
        return '    <frame w="' + panelActions.panelPx(width || 8) + 'px" h="1px"/>';
    }

    function rowGap() {
        return '  <frame h="' + panelState.PANEL.rowGap + 'px"/>';
    }

    /** 行首的分组标签（对局 / 诊断） */
    function rowLabel(text) {
        return (
            '    <text text="' +
            text +
            '" w="' +
            panelActions.panelPx(70) +
            'px" h="' +
            panelState.PANEL.rowHeight +
            'px" textColor="' +
            panelState.C.status +
            '" textSize="' +
            panelState.themeTokens.FONT.button +
            '" textStyle="bold" gravity="center" includeFontPadding="false" singleLine="true"' + fitText(panelState.themeTokens.FONT.button) + '/>'
        );
    }

    /** 参数值显示（原滑条改 +/− 后的「值段」）：白卡片 + 居中字。 */
    function slider(id, width) {
        return (
            '    <card w="' +
            width +
            'px" h="' +
            panelState.PANEL.rowHeight +
            'px" cardCornerRadius="' +
            panelState.PANEL.radiusCard +
            'px" cardElevation="0dp" cardBackgroundColor="' +
            panelState.C.card +
            '" clipToOutline="true">' +
            '<text id="' +
            id +
            '" text="" w="' +
            width +
            'px" h="' +
            panelState.PANEL.rowHeight +
            'px" textColor="' +
            panelState.C.step +
            '" textSize="' +
            panelState.themeTokens.FONT.status +
            '" gravity="center" includeFontPadding="false" singleLine="true" padding="4px"' + fitText(panelState.themeTokens.FONT.status) + '/></card>'
        );
    }

    /**
     * 参数区几何：一个「− 值 ＋」控件占的宽 = 原滑条宽（paramRow 排版不变）。
     * 参数行：搜索模式 / 算棋时间或层数 / 线程 / 哈希。
     */
    function sliderWidth() {
        // 间隙数 = 左右 padding 2 + 行内 gap 4（模式/时间/线程/哈希/避和 五段控件之间 4 个缝）
        return Math.floor(
            (panelState.PANEL.width -
                panelActions.panelPx(8) * 6 -
                panelActions.panelPx(panelState.SEARCH_MODE_BTN_W) -
                panelActions.panelPx(panelState.HASH_BTN_W) -
                panelActions.panelPx(panelState.CONTEMPT_BTN_W)) /
                2
        );
    }

    /** 「− 值 ＋」里值段的宽度（= 原滑条宽 − 两个小按钮 − 两条 4px 缝）。 */
    function stepValueWidth() {
        return sliderWidth() - 2 * (panelActions.panelPx(panelState.STEP_BTN_W) + panelActions.panelPx(4));
    }

    /**
     * 「− 值 ＋」控件（2026-09-25 用户口径：算棋时间/线程改为 +/- 控制，点一下 ±0.1s／±1，按住连加）。
     * −/＋ 用带底色 <text> 冒充按钮（这个引擎里 `<button>` 收不到触摸，见 button 的说明），
     * 值段沿用 slider()（白卡片 + 居中字）。三段合计宽 = 原滑条宽，paramRow 排布不用改。
     */
    function stepControl(prefix) {
        return (
            button(prefix + "Minus", "−", panelState.STEP_BTN_W, panelState.C.btnNeutral) +
            "\n" +
            gap(4) +
            "\n" +
            slider(prefix + "Value", stepValueWidth()) +
            "\n" +
            gap(4) +
            "\n" +
            button(prefix + "Plus", "+", panelState.STEP_BTN_W, panelState.C.btnNeutral)
        );
    }

    function openingStepControl() {
        return (
            button("openingMinus", "−", panelState.OPENING_BTN_W, panelState.C.btnNeutral) +
            "\n" +
            gap(4) +
            "\n" +
            slider("openingValue", panelActions.panelPx(panelState.OPENING_VALUE_W)) +
            "\n" +
            gap(4) +
            "\n" +
            button("openingPlus", "+", panelState.OPENING_BTN_W, panelState.C.btnNeutral)
        );
    }

    return {
        panelTitleText: panelTitleText,
        compactStateText: compactStateText,
        button: button,
        fitText: fitText,
        gap: gap,
        rowGap: rowGap,
        rowLabel: rowLabel,
        slider: slider,
        sliderWidth: sliderWidth,
        stepValueWidth: stepValueWidth,
        stepControl: stepControl,
        openingStepControl: openingStepControl
    };
}

module.exports = { create: create };
