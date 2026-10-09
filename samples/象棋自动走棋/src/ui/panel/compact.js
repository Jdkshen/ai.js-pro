// @engine quickjs
"use strict";
const imguiCompact = require("../imgui-compact-window.js");

/** compact：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    /**
     * 状态栏高度（面板默认停在屏幕顶端，不能压住状态栏）。
     * 量不到给 88px 兜底（K40 = 80px）。
     */
    function statusBarHeight() {
        try {
            const res = context.getResources();
            const id = res.getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                const h = res.getDimensionPixelSize(id);
                if (h > 0) {
                    return h;
                }
            }
        } catch (error) {
            // 拿不到就用兜底值
        }
        return 88;
    }

    /** 创建真正只有一行高的收起条。避免在整块面板里把控件 GONE 后仍留下遮挡触摸的大空白。 */
    function ensureCompactWindow(x, y) {
        // Window creation chooses accessibility or ordinary overlay permission in
        // the host. The screenshot source does not decide the panel renderer.
        const native = imguiCompact.available();
        if (!panelState.originalCompactSize) panelState.originalCompactSize = {width:panelState.COMPACT_W,height:panelState.COMPACT_H};
        if (panelState.compactWin && (panelState.compactWin.isImGui === true) !== !!native) {
            panelState.compactWin.close(); panelState.compactWin = null;
            panelState.COMPACT_W = panelState.originalCompactSize.width; panelState.COMPACT_H = panelState.originalCompactSize.height;
        }
        if (panelState.compactWin) {
            return panelState.compactWin;
        }
        if (native) {
            try {
                const panel = imguiCompact.createWindow(panelState, panelActions, x, y);
                if (panel) {
                    panelState.compactWin = panel;
                    panelState.COMPACT_W = panel.getWidth(); panelState.COMPACT_H = panel.getHeight();
                    return panel;
                }
            } catch (error) { panelActions.logLine("ImGui 操作窗创建失败：" + error); }
            panelActions.logLine("ImGui 操作窗不可用，使用兼容小窗；请检查悬浮窗权限");
        }
        panelState.compactWin = floaty.window(panelState.COMPACT_XML, { x: x, y: y, visible: true });
        panelState.compactWin.setTouchable(true);
        // 标题区在原生 UI 线程拖动；旧宿主保留 JS 兜底。
        let compactNativeDrag = false;
        try {
            if (typeof panelState.compactWin.setDragHandle === "function") {
                panelState.compactWin.setDragHandle("compactStatus", function () {
                    panelState.panelUserMoved = true;
                });
                compactNativeDrag = true;
            }
        } catch (error) {
            compactNativeDrag = false;
        }
        if (!compactNativeDrag) {
            // 旧平台没有原生拖动时，给状态区一个限频拖动兜底
            let downX = 0;
            let downY = 0;
            let winX = x;
            let winY = y;
            let lastMoveAt = 0;
            panelState.compactWin.compactStatus.on("touch", function (event) {
                const action = event.getAction();
                if (action === 0) {
                    downX = event.getRawX();
                    downY = event.getRawY();
                    winX = panelState.compactWin.getX(true);
                    winY = panelState.compactWin.getY(true);
                } else if (action === 2 && Date.now() - lastMoveAt >= 40) {
                    lastMoveAt = Date.now();
                    panelState.compactWin.setPosition(winX + event.getRawX() - downX, winY + event.getRawY() - downY);
                }
                return true;
            });
        }
        panelState.compactWin.compactMode.on("click", function () {
            const index = panelState.MODES.indexOf(panelState.modeKey);
            panelState.modeKey = panelState.MODES[(index + 1) % panelState.MODES.length];
            if (panelState.running || panelState.starting) {
                panelActions.stopLoop();
            }
            panelState.bus.syncConfig("mode", panelState.modeKey);
            panelActions.logLine("模式切换为 " + panelState.MODE_LABEL[panelState.modeKey]);
            panelActions.render();
        });
        panelState.compactWin.compactStart.on("click", function () {
            if (panelState.running || panelState.starting) {
                panelActions.stopLoop();
            } else {
                panelActions.startLoop();
            }
            panelActions.render();
        });
        panelState.compactWin.compactStop.on("click", function () {
            panelActions.stopLoop();
            const other = panelState.bus.readOther();
            if (other && other.fresh && other.state && other.state.running) {
                panelState.bus.stopOther("面板点了停止");
                panelActions.logLine("已请页面（主界面）也停止");
            }
            panelActions.render();
        });
        panelState.compactWin.compactExpand.on("click", function () {
            setPanelCollapsed(false);
        });
        return panelState.compactWin;
    }

    /** 真正切到一行高的小窗；宿主的 setContentVisibility 只切根 View，不移除窗口。 */
    function setPanelCollapsed(next) {
        next = !!next;
        if (next === panelState.collapsed) {
            return;
        }
        let x = panelState.PANEL.marginX;
        let y = panelState.panelY == null ? Math.round(2400 * panelState.PANEL.defaultTopRatio) : panelState.panelY;
        try {
            if (next) {
                x = panelState.win.getX(true);
                y = panelState.win.getY(true);
                const bar = ensureCompactWindow(x, y);
                y = panelActions.clampPanelY(y, panelState.adapter.screenSize(), panelState.COMPACT_H);
                bar.setPosition(x, y);
                bar.setContentVisibility(0);
                bar.setTouchable(true);
                panelState.win.setContentVisibility(8);
                panelState.win.setTouchable(false);
                if (panelState.logWin) {
                    panelState.logWin.setContentVisibility(8);
                }
                panelState.collapsed = true;
                panelState.panelY = y;
            } else {
                x = panelState.compactWin.getX(true);
                y = panelState.compactWin.getY(true);
                panelState.win.setPosition(
                    x,
                    panelActions.clampPanelY(y, panelState.adapter.screenSize(), panelState.PANEL_H)
                );
                panelState.win.setContentVisibility(0);
                panelState.win.setTouchable(true);
                panelState.compactWin.setContentVisibility(8);
                panelState.compactWin.setTouchable(false);
                panelState.collapsed = false;
                panelState.panelY = panelState.win.getY(true);
                if (panelState.logWin) {
                    panelState.logWin.setContentVisibility(0);
                }
                panelActions.showLogWindow();
                if (panelState.logWin && typeof panelState.logWin.setPosition === "function") {
                    try {
                        const size = panelState.adapter.screenSize();
                        panelActions.snapLogWindowBelowBoard(size);
                    } catch (error) {
                        // 摆不了就保留原位
                    }
                }
            }
        } catch (error) {
            panelActions.logLine("收起/展开失败：" + error);
            try {
                panelState.win.setPosition(x, y);
            } catch (inner) {
                /* 保留现有窗口 */
            }
            try {
                panelState.win.setContentVisibility(0);
                panelState.win.setTouchable(true);
            } catch (inner) {
                /* 保留现有窗口 */
            }
            try {
                if (panelState.compactWin) {
                    panelState.compactWin.setContentVisibility(8);
                    panelState.compactWin.setTouchable(false);
                }
            } catch (inner) {
                /* 保留现有窗口 */
            }
            try {
                if (panelState.logWin) {
                    panelState.logWin.setContentVisibility(0);
                }
            } catch (inner) {
                /* 日志不影响控制 */
            }
            panelState.collapsed = false;
        } finally {
            panelActions.render();
        }
    }

    return {
        statusBarHeight: statusBarHeight,
        ensureCompactWindow: ensureCompactWindow,
        setPanelCollapsed: setPanelCollapsed
    };
}

module.exports = { create: create };
