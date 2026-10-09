// @engine quickjs
"use strict";
const imguiCompact = require("../imgui-compact-window.js");

/** window：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    const textCache = {};
    function setPanelText(key, view, value) {
        if (!view) return false;
        const text = String(value);
        const old = textCache[key];
        if (old && old.view === view && old.text === text) return false;
        view.setText(text);
        textCache[key] = { view: view, text: text };
        return true;
    }

    function activePanelWindow() {
        return panelState.collapsed && panelState.compactWin ? panelState.compactWin : panelState.win;
    }

    function setWindowVisible(window, visible) {
        // Android 显隐值是 0/8；布尔值会被 QuickJS 的 Number() 转成 0/1，含义正好颠倒。
        // 首选只改根 View，保留窗口挂载和游戏焦点。
        if (typeof window.setContentVisibility === "function") {
            window.setContentVisibility(visible ? 0 : 8);
            return true;
        }
        return window.setVisibility(visible ? 0 : 8) !== false;
    }

    function hidePanel(reason, options) {
        panelState.hideDepth += 1;
        if (!options || options.includeLog !== false) hideLogWindow(reason);
        if (panelState.panelPosBeforeHide) {
            return true; // 已经藏着了（嵌套调用）
        }
        const panel = activePanelWindow();
        if (!panel) {
            return false;
        }
        let x = 30;
        let y = 0;
        try {
            const cx = panel.getX(true);
            const cy = panel.getY(true);
            if (isFinite(cx)) {
                x = cx;
            }
            if (isFinite(cy)) {
                y = cy;
            }
        } catch (error) {
            // 读不到就用默认位置，反正恢复时还会再读一次
        }
        // ROM 可把请求的屏幕外坐标夹回可见区；先真正隐藏 View，避免截图仍带窗口。
        try {
            if (setWindowVisible(panel, false)) {
                panelState.hideViaVisibility = true;
                panelState.panelPosBeforeHide = { x:x, y:y };
                panelState.panelMoveCounts.hide += 1;
                logHideOnce(reason, x, y);
                return true;
            }
        } catch (error) { /* 旧宿主继续用移出屏幕兜底。 */ }
        const offY = -(panelState.PANEL_H + 120); // 完全在屏幕上方之外
        try {
            if (typeof panel.setPosition === "function") {
                panel.setPosition(x, offY);
                let landed = null;
                try {
                    landed = panel.getY(true);
                } catch (error) {
                    landed = null;
                }
                if (!isFinite(landed) || landed < 0) {
                    panelState.panelPosBeforeHide = { x: x, y: y };
                    panelState.panelMoveCounts.hide += 1;
                    logHideOnce(reason, x, y);
                    return true;
                }
                panel.setPosition(x, y); // 挪不动（被夹住）→ 复位后走兜底
            }
        } catch (error) {
            // 落到 setVisibility 兜底
        }
        try {
            panelState.hideViaVisibility = setWindowVisible(panel, false);
        } catch (error) {
            panelState.hideViaVisibility = false;
        }
        if (panelState.hideViaVisibility) {
            panelState.panelPosBeforeHide = { x: x, y: y };
            panelState.panelMoveCounts.hide += 1;
        }
        return panelState.hideViaVisibility;
    }

    function showPanel(options) {
        panelState.hideDepth = Math.max(0, panelState.hideDepth - 1);
        if (!options || options.includeLog !== false) showLogWindow();
        if (panelState.hideDepth > 0 || !panelState.panelPosBeforeHide) {
            return true; // 还有一层没放完
        }
        const home = panelState.panelPosBeforeHide;
        panelState.panelPosBeforeHide = null;
        const panel = activePanelWindow();
        if (!panel) {
            return false;
        }
        if (panelState.hideViaVisibility) {
            panelState.hideViaVisibility = false;
            try {
                return setWindowVisible(panel, true);
            } catch (error) {
                panelActions.logLine("恢复面板失败（这个 ROM 的 setVisibility 恢复不可靠）：" + error);
                return false;
            }
        }
        try {
            panel.setPosition(home.x, home.y);
            panelState.panelY = home.y; // 是我们摆的位置，不算「用户拖过」
            panelState.panelMoveCounts.show += 1;
            return true;
        } catch (error) {
            panelActions.logLine("恢复面板位置失败：" + error);
            return false;
        }
    }

    function setActivePanelVisibility(visible, reason, options) {
        return visible ? showPanel(options) : hidePanel(reason, options);
    }

    /** 一次截图只借用自己隐藏的窗口；点击租约持有的隐藏状态不能重复计数或提前恢复。 */
    function beginCaptureVisibility(options) {
        const opts = options || {};
        let panelHidden = false, logHidden = false, released = false;
        const lease = { hidden: false, clearedDrawing: false, release: function () {
            if (released) return;
            released = true;
            try {
                if (panelHidden) showPanel({ includeLog: false });
            } finally {
                if (logHidden) showLogWindow();
            }
        } };
        if (typeof panelActions.prepareOverlayForCapture === "function") {
            lease.clearedDrawing = panelActions.prepareOverlayForCapture();
        }
        if (opts.hide === false || panelState.clickLeaseActive || panelState.adapter.captureBackend() === "window") return lease;
        const targets = panelActions.captureHideTargets(opts.includeFooter, opts.hide === true);
        try {
            if (targets.panel && !panelState.panelPosBeforeHide) {
                const depth = panelState.hideDepth;
                panelHidden = hidePanel(opts.reason || "抓帧", { includeLog: false }) !== false;
                if (!panelHidden) panelState.hideDepth = depth;
            }
            if (targets.log && !panelState.logPosBeforeHide) {
                logHidden = hideLogWindow(opts.reason || "抓帧") !== false;
            }
            lease.hidden = panelHidden || logHidden;
            return lease;
        } catch (error) {
            lease.release();
            throw error;
        }
    }

    /**
     * 日志窗的藏/显：**往上挪**会正好落在棋盘上，所以它要**往下挪出屏幕**。
     * 拿不到位置或挪不动（被 ROM 夹回屏幕内）时退回 setVisibility（与面板同一套兜底）。
     */
    function hideLogWindow(reason) {
        if (!panelState.logWin || panelState.logPosBeforeHide || panelState.collapsed || panelState.logWindowEnabled === false) {
            return true; // 没建/已经藏了
        }
        let x = panelState.PANEL.marginX;
        let y = 0;
        try {
            const cx = panelState.logWin.getX(true);
            const cy = panelState.logWin.getY(true);
            if (isFinite(cx)) {
                x = cx;
            }
            if (isFinite(cy)) {
                y = cy;
            }
        } catch (error) {
            // 读不到就用默认
        }
        let screenH = 2400;
        try {
            const size = panelState.adapter.screenSize();
            if (size && size.height > 0) {
                screenH = size.height;
            }
        } catch (error) {
            // 用兜底值
        }
        try {
            if (setWindowVisible(panelState.logWin, false)) {
                panelState.logHideViaVisibility = true;
                panelState.logPosBeforeHide = { x:x, y:y };
                return true;
            }
        } catch (error) { /* 旧宿主继续用移出屏幕兜底。 */ }
        try {
            if (typeof panelState.logWin.setPosition === "function") {
                panelState.logWin.setPosition(x, screenH + 120);
                let landed = null;
                try {
                    landed = panelState.logWin.getY(true);
                } catch (error) {
                    landed = null;
                }
                if (isFinite(landed) && landed > screenH) {
                    panelState.logPosBeforeHide = { x: x, y: y };
                    panelState.logHideViaVisibility = false;
                    return true;
                }
                panelState.logWin.setPosition(x, y); // 挪不动 → 复位后走兜底
            }
        } catch (error) {
            // 落到 setVisibility 兜底
        }
        try {
            panelState.logHideViaVisibility = setWindowVisible(panelState.logWin, false);
        } catch (error) {
            panelState.logHideViaVisibility = false;
        }
        if (panelState.logHideViaVisibility) {
            panelState.logPosBeforeHide = { x: x, y: y };
        }
        return panelState.logHideViaVisibility;
    }

    function showLogWindow() {
        if (!panelState.logWin || !panelState.logPosBeforeHide || panelState.hideDepth > 0) {
            return true;
        }
        const home = panelState.logPosBeforeHide;
        panelState.logPosBeforeHide = null;
        if (panelState.logHideViaVisibility) {
            panelState.logHideViaVisibility = false;
            try {
                setWindowVisible(panelState.logWin, true);
            } catch (error) {
                panelActions.logLine("恢复日志窗失败（这个 ROM 的 setVisibility 恢复不可靠）：" + error);
            }
            return true;
        }
        try {
            panelState.logWin.setPosition(home.x, home.y);
            panelState.logPanelY = home.y;
        } catch (error) {
            panelActions.logLine("恢复日志窗位置失败：" + error);
        }
        return true;
    }

    function logHideOnce(reason, x, y) {
        const now = Date.now();
        if (panelState.panelMoveCounts.hide > 1 && now - panelState.lastHideLogAt < 10000) {
            return;
        }
        panelState.lastHideLogAt = now;
        let pr = null;
        let br = null;
        try {
            pr = panelActions.panelRect();
        } catch (error) {
            pr = null;
        }
        try {
            br = panelActions.boardRect();
        } catch (error) {
            br = null;
        }
        panelActions.logLine(
            "藏面板（第 " +
                panelState.panelMoveCounts.hide +
                " 次，原因 " +
                (reason || "未注明") +
                "）：面板(" +
                Math.round(x) +
                "," +
                Math.round(y) +
                " " +
                (pr ? pr.width + "×" + pr.height : "?") +
                ")" +
                "｜棋盘" +
                (br ? "(" + Math.round(br.x) + "," + Math.round(br.y) + " " + br.width + "×" + br.height + ")" : "未知")
        );
    }

    function render() {
        if (!panelState.win) {
            return;
        }
        try {
            setPanelText("title", panelState.win.title, panelActions.panelTitleText());
            setPanelText("status", panelState.win.status, panelActions.statusLine());
            setPanelText("step", panelState.win.step, panelActions.stepLine());
            if (panelState.logWin && panelState.logWin.logArea) {
                const scroll = panelState.logWin.logScroll;
                let reading = false;
                try {
                    reading = !!textCache.log && scroll && typeof scroll.attr === "function" &&
                        String(scroll.attr("canScrollDown")) === "true";
                } catch (ignore) { /* old hosts retain manual scrolling */ }
                if (panelState.logFollowRequested) reading = false;
                const changed = !reading && setPanelText("log", panelState.logWin.logArea, panelActions.visibleLog().join("\n"));
                setPanelText("logTitle", panelState.logWin.logTitle, panelActions.logTitle());
                setPanelText("logMeta", panelState.logWin.logMeta, reading ? "回到最新 ↓" : panelActions.logMeta());
                // Freeze the visible events while reading older lines. Heartbeats never move the viewport.
                if (changed || panelState.logFollowRequested) {
                    try {
                        if (scroll && typeof scroll.attr === "function") scroll.attr("scrollToEnd", true);
                    } catch (ignore) { /* native scrolling is still available */ }
                }
                panelState.logFollowRequested = false;
            }
            // 运行期换色（增强：状态绿/黄/红）。不同引擎版本暴露的方法不一样，失败就算了。
            try {
                if (panelState.win.step && typeof panelState.win.step.setTextColor === "function") {
                    const color = panelActions.stepColor();
                    if (panelState.lastStepColor !== color) {
                        panelState.win.step.setTextColor(color);
                        panelState.lastStepColor = color;
                    }
                }
            } catch (error) {
                // 不支持就维持 XML 里的静态色
            }
            setPanelText("mode", panelState.win.modeBtn, 
                panelState.UI_MODE_LABEL[panelState.modeKey] || panelState.MODE_LABEL[panelState.modeKey]
            );
            // 启动中要**立刻**有反馈：建驱动/引擎预热就在这段时间里，以前这几秒按钮还写着「开始」
            setPanelText("start", panelState.win.startBtn, panelState.starting ? "取消" : panelState.running ? "暂停" : panelState.startError ? "重试" : "开始");
            setPanelText("side", panelState.win.sideBtn, panelState.mySideKey === "b" ? "我方黑" : "我方红");
            setPanelText("collapse", panelState.win.collapseBtn, panelState.collapsed ? "展开" : imguiCompact.available() ? "ImGui" : "收起");
            if (panelState.win.winSeekBtn) {
                setPanelText("winSeek", panelState.win.winSeekBtn, panelState.winSeekKey ? "求胜开" : "求胜");
            }
            panelActions.renderParamSliders();
            if (panelState.compactWin && panelState.compactWin.isImGui === true) {
                panelState.compactWin.updateState();
            } else if (panelState.compactWin) {
                setPanelText("compactStatus", panelState.compactWin.compactStatus, panelActions.compactStateText());
                setPanelText("compactMode", panelState.compactWin.compactMode, 
                    panelState.UI_MODE_LABEL[panelState.modeKey] || panelState.MODE_LABEL[panelState.modeKey]
                );
                setPanelText("compactStart", panelState.compactWin.compactStart, 
                    panelState.starting ? "取消" : panelState.running ? "暂停" : panelState.startError ? "重试" : "开始"
                );
            }
        } catch (error) {
            console.log("[floaty] 渲染失败：" + error);
        }
    }

    return {
        activePanelWindow: activePanelWindow,
        hidePanel: hidePanel,
        showPanel: showPanel,
        setActivePanelVisibility: setActivePanelVisibility,
        beginCaptureVisibility: beginCaptureVisibility,
        hideLogWindow: hideLogWindow,
        showLogWindow: showLogWindow,
        logHideOnce: logHideOnce,
        setPanelText: setPanelText,
        render: render
    };
}

module.exports = { create: create };
