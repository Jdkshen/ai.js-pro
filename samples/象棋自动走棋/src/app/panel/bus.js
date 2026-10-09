// @engine quickjs
"use strict";

/** bus：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    /** 本面板的状态快照（页面/主界面读它来显示「面板在跑什么」）。 */
    function busSnapshot() {
        return {
            version: panelState.PROBE_BUILD_ID,
            running: panelState.running,
            starting: panelState.starting,
            readyAt: panelState.readyAt || 0,
            baseDir: panelState.BASE_DIR,
            startError: panelState.startError || "",
            progressText: panelActions.stepLine(),
            recentLogs: panelState.logLines.slice(-8).join("\n"),
            mode: panelState.modeKey,
            side: panelState.mySideKey,
            tap: panelState.clickMethodKey,
            capture: panelState.adapter.captureBackend(),
            drawMode: panelState.drawMode,
            collapsed: panelState.collapsed,
            lastPageAction: panelState.lastPageAction || null,
            // A1 批次2：跨引擎对齐口径（几何版本/状态机/实际引擎）——两边用同一渲染函数显示
            gridVersion: panelState.grid ? panelState.grid.version : null,
            machineState:
                panelState.driver && panelState.driver.machine ? panelState.driver.machine.state() : "STOPPED",
            backend: panelState.engineModule.lastEngine()
        };
    }

    /** 总线泵：发布自己 → 收页面命令。（面板心跳 1s 调一次，见 startKeepAlive） */
    function pumpBus() {
        panelActions.refreshSharedSettings();
        panelState.bus.publish(busSnapshot());
        let message;
        while ((message = panelState.bus.poll())) {
            applyBusCommand(message);
        }
    }

    /** 收到页面的命令：stop（双向停止）与 setConfig（配置同步）。 */
    function applyBusCommand(message) {
        if (message.name === "pageAction" && message.arg) {
            // 关闭/重开后不能重放上一实例的开始命令；超时命令也不能迟到执行。
            if (message.arg.targetReadyAt !== panelState.readyAt ||
                Date.now() - message.arg.requestedAt > 12000 || !message.arg.requestedAt) return;
            const name = message.arg.command;
            let result;
            if (["START", "RECOGNIZE", "RELOCATE", "NEW_SESSION", "DECLARE_TURN", "COLLAPSE", "DRAW"].indexOf(name) < 0) return;
            try {
                if (name === "COLLAPSE") {
                    panelActions.setPanelCollapsed(!panelState.collapsed);
                    result = {ok:true};
                } else if (name === "DRAW") {
                    if (panelState.drawMode !== "off") {
                        panelState.drawMode = "off"; panelActions.refreshOverlay(null, null); result = {ok:true};
                    } else {
                        panelActions.keepGameInFrontOnce();
                        result = panelActions.runPanelCommand("RECOGNIZE");
                    }
                } else {
                    if (name === "RECOGNIZE" || name === "RELOCATE") panelActions.keepGameInFrontOnce();
                    result = panelActions.runPanelCommand(name, name === "DECLARE_TURN" ? {side:panelState.mySideKey} : {});
                }
            } catch (error) { result = {ok:false,reason:String(error)}; }
            panelState.lastPageAction = { id:message.arg.id, command:name, ok:!!(result && result.ok),
                pending:name === "START" && panelState.starting,
                reason:result && result.reason || "", at:Date.now() };
            panelState.bus.publish(busSnapshot());
            panelActions.render();
            return;
        }
        if (message.name === "close") {
            panelActions.stopLoop();
            if (typeof exit === "function") exit();
            return;
        }
        if (message.name === "stop") {
            const why = message.arg && message.arg.why ? message.arg.why : "页面请求停止";
            if (panelState.running || panelState.starting) {
                panelActions.stopLoop();
                panelActions.logLine("页面请求停止：" + why);
                panelActions.render();
            } else {
                panelActions.logLine("页面请求停止（面板本来就没跑）");
            }
            return;
        }
        if (message.name === "setConfig" && message.arg) {
            applyConfigFromPage(message.arg.key, message.arg.value);
        }
    }

    /**
     * 应用页面同步过来的配置。
     * ⚠️ 只应用、不回发 —— 回发会变成两边互相回弹的死循环。
     */
    function applyConfigFromPage(key, value) {
        if (key === "mode" && panelState.MODES.indexOf(value) >= 0 && value !== panelState.modeKey) {
            panelState.modeKey = value;
            if (panelState.running || panelState.starting) {
                panelActions.stopLoop();
                panelActions.startLoop();
            }
            panelActions.logLine("页面把模式改成：" + panelState.MODE_LABEL[panelState.modeKey]);
            panelActions.render();
        } else if (
            key === "side" &&
            (value === "w" || value === "b") &&
            panelState.mySideKey !== value
        ) {
            panelActions.setPanelSide(value);
            panelActions.logLine("页面把「我方」改成：" + (value === "w" ? "红方" : "黑方") + "（旧基准已作废）");
            panelActions.render();
        } else if (key === "capture" && ["auto", "screen", "shell", "window"].indexOf(value) >= 0) {
            if (panelState.running || panelState.starting) panelActions.stopLoop();
            panelState.adapter.setCaptureBackend(value);
            // Keep the panel's renderer and collapsed state. Only frame geometry
            // and drawing belong to the screenshot source that is changing.
            if (panelState.overlayOn) panelState.adapter.overlayHide();
            panelState.overlayOn = false;
            panelActions.publishGrid(null); // invalidate panel, service and driver geometry together
            panelState.captureRectsNoted = false;
            panelState.capturePermissionAttempted = false;
            panelActions.render();
        } else if (key === "drawEnabled") {
            // 页面那个「绘制」是它自己的计划画板；面板这边的绘制是「识别绘制」档位（人工审核用），
            // 语义不同，所以只记一行、不跟着改（避免把审核档位莫名改掉）。
            panelActions.logLine(
                "页面把「绘制」改成：" + (value ? "开" : "关") + "（面板的「识别绘制」是另一档，未跟随）"
            );
        }
    }

    return {
        busSnapshot: busSnapshot,
        pumpBus: pumpBus,
        applyBusCommand: applyBusCommand,
        applyConfigFromPage: applyConfigFromPage
    };
}

module.exports = { create: create };
