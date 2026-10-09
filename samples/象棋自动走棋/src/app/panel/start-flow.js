// @engine quickjs
"use strict";

/** 启动分段让出事件循环；取消令牌阻止已经排队的旧步骤再次启动会话。 */
function create(panelState, panelActions) {
    function clearStartFeedback() {
        panelState.startGeneration = (panelState.startGeneration || 0) + 1;
        if (panelState.startFeedbackTimer != null) clearTimeout(panelState.startFeedbackTimer);
        panelState.startFeedbackTimer = null;
        panelState.starting = false;
        panelState.startStage = "";
    }

    function startLoop() {
        if (panelState.running || panelState.starting) return { ok: false, reason: "已在运行或准备中" };
        // 在定位前抢锁：其他入口运行时，不再白做一次截图和校准。
        const gate = panelState.loopLock.acquire(panelState.loopLock.OWNER_FLOATY);
        if (!gate.ok) {
            panelState.startError = gate.reason;
            panelActions.logLine("未开始：" + gate.reason);
            panelActions.render();
            return gate;
        }
        clearStartFeedback();
        const generation = panelState.startGeneration;
        const beganAt = Date.now();
        panelState.starting = true;
        panelState.startError = "";
        if (typeof panelActions.prepareContinuousMoveCapture === "function") panelActions.prepareContinuousMoveCapture();
        const steps = [
            { label: "切回游戏", run: function () { panelActions.keepGameInFrontOnce(); } },
            { label: "定位棋盘", run: function () {
                if (!panelState.grid && !panelActions.locateAndCalibrate())
                    throw new Error(panelState.gameOverSeen
                        ? "对局已结束，请进入新对局后再开始"
                        : "未找到棋盘；打开游戏对局后重试");
            } },
            { label: "检查运行条件", run: function () {
                if (panelState.modeKey === "auto") {
                    const pre = panelActions.foregroundGuard();
                    if (!pre || !pre.ok) throw new Error("游戏未在前台；切回棋盘后重试");
                }
                panelActions.buildDriver();
            } }
        ];
        // 观察模式根本不算棋，避免加载 NNUE。建议与自动模式保留预热。
        if (panelState.modeKey !== "watch") steps.push({ label: "准备算棋引擎", run: function () {
            const warm = panelState.engineModule.warmUpPikafish();
            if (!warm || !warm.ok) throw new Error(warm && warm.reason || "Pikafish 不可用");
        } });
        steps.push({ label: "开始观察", run: function () {
            const result = panelState.app && panelState.app.session.start();
            if (!result || result.ok === false) throw new Error(result && result.reason || "会话启动失败");
        } });

        function advance(index) {
            if (!panelState.starting || generation !== panelState.startGeneration) return;
            if (index === steps.length) {
                clearStartFeedback();
                panelState.running = true;
                panelState.startDurationMs = Date.now() - beganAt;
                panelActions.markPanelRunning(true);
                panelActions.logLine("已开始：" + panelState.UI_MODE_LABEL[panelState.modeKey] +
                    "｜准备耗时 " + panelState.startDurationMs + "ms");
                panelActions.render();
                panelActions.pumpBus();
                panelActions.scheduleNextTick(60);
                return;
            }
            const step = steps[index];
            panelState.startStage = (index + 1) + "/" + steps.length + " " + step.label;
            panelActions.render();
            // 给按钮/进度一次绘制和处理「取消」的机会；不跨线程共享 QuickJS 对象。
            panelState.startFeedbackTimer = setTimeout(function () {
                panelState.startFeedbackTimer = null;
                if (!panelState.starting || generation !== panelState.startGeneration) return;
                const at = Date.now();
                try {
                    step.run();
                    if (generation !== panelState.startGeneration) return;
                    panelActions.stageMark("start:" + step.label + " " + (Date.now() - at) + "ms");
                    advance(index + 1);
                } catch (error) {
                    if (generation !== panelState.startGeneration) return;
                    panelActions.stopLoop();
                    panelState.startError = String(error.message || error);
                    panelActions.logLine("启动未完成：" + panelState.startError);
                    panelActions.render();
                    panelActions.pumpBus();
                }
            }, 40);
        }
        advance(0);
        return { ok: true, pending: true };
    }

    function setPanelSide(side) {
        if (side !== "w" && side !== "b") return;
        panelState.mySideKey = side;
        if (panelState.driver) panelState.driver.setMySide(side);
        const store = panelActions.paramStore();
        if (store) store.put("mySide", side);
        panelActions.render();
    }

    return { clearStartFeedback: clearStartFeedback, startLoop: startLoop, setPanelSide: setPanelSide };
}
module.exports = { create: create };
