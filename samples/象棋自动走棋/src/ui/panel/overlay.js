// @engine quickjs
"use strict";
const renderState = require("../render-state.js");

/** overlay：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */

function create(panelState, panelActions) {
    let lastPacketKey = null;
    let retainedTicket = null;
    function clearOverlay(reason) {
        panelState.overlayClearReason = reason || "";
        panelState.lastOverlay = null;
        panelState.lastRenderState = null;
        panelState.lastClickPlan = null;
        retainedTicket = null;
        const text = "", key = JSON.stringify({items:[], text:text});
        if (panelState.overlayOn && lastPacketKey !== key) try {
            if (panelState.adapter.overlayUpdate([], text) !== false) lastPacketKey = key;
        } catch (error) {}
    }

    /** 在首次定位/抓帧前确定隔离通道；运行中不悄悄换坐标来源。 */
    function prepareContinuousMoveCapture() {
        const adapter = panelState.adapter;
        if (panelState.running || panelState.modeKey !== "auto" || panelState.drawMode === "off" ||
            adapter.captureBackend() === "window" || typeof adapter.capabilities !== "function" ||
            !adapter.capabilities().captureWindow) return false;
        adapter.setCaptureBackend("window", {persist:false});
        panelState.grid = null;
        if (panelState.boardService) panelState.boardService.clear();
        if (typeof panelActions.clearRecentFrame === "function") panelActions.clearRecentFrame();
        clearOverlay("连续箭头切换到隔离截图，重新确定几何");
        panelActions.logLine("连续走法绘制采用窗口截图：截图不清箭头，操作窗保持显示");
        return true;
    }

    /** 整屏截图前清空像素，保留 GL 窗口。下一次有效观察/计划重新编制绘制内容。 */
    function prepareOverlayForCapture() {
        const emptyKey = JSON.stringify({items:[], text:""});
        if (panelState.adapter.captureBackend() === "window" || !panelState.overlayOn ||
            !lastPacketKey || lastPacketKey === emptyKey) return false;
        if (panelState.adapter.overlayUpdate([], "") === false) throw new Error("无法清空绘制层，本次截图取消");
        lastPacketKey = emptyKey;
        panelState.lastOverlay = null;
        panelState.lastRenderState = null;
        return true;
    }
    function overlayTextNow() {
        const parts = [];
        try {
            const state = panelState.driver && panelState.driver.machine ? panelState.driver.machine.state() : null;
            if (state) {
                parts.push("状态 " + (panelState.STATE_LABEL[state] || state));
            }
        } catch (error) {
            // 没驱动就算了
        }
        if (panelState.progress.turn) {
            parts.push("轮次 " + panelState.progress.turn);
        }
        if (panelState.progress.think) {
            parts.push("算棋 " + panelState.progress.think);
        }
        try {
            const latest =
                panelState.driver && typeof panelState.driver.lastResult === "function"
                    ? panelState.driver.lastResult()
                    : null;
            if (latest && latest.ocr) {
                const o = latest.ocr;
                parts.push("OCR " + (o.accepted || 0) + "确认/" + (o.conflicts || 0) + "冲突");
            }
        } catch (error) {
            // OCR 状态仅用于绘制，不能影响识别与走棋。
        }
        return parts.join("｜");
    }

    /**
     * 用「当前几何 + 这一帧的识别结果」刷新画板。
     *
     * 窗口截图隔离绘制层；整屏/shell 在抓帧租约内清空内容，再根据新观察更新。
     *
     * @param result  recognizer 的结果（可为 null —— 那就只画网格）
     * @param expectChars 期望盘面（验证阶段用票据的预期后局面，能把读错的格标成 X!）
     * @param options.movePlan 刚通过执行检查的走法，供首次点击前展示
     */
    function refreshOverlay(result, expectChars, options) {
        if (panelState.drawMode === "off") {
            if (panelState.overlayOn) {
                try {
                    panelState.adapter.overlayHide();
                } catch (error) {
                    // 收不掉也不影响业务
                }
                panelState.overlayOn = false;
                panelState.lastOverlay = null;
                lastPacketKey = null;
            }
            retainedTicket = null;
            return;
        }
        if (panelState.drawMode === "moves" && panelState.modeKey !== "auto") {
            clearOverlay("仅自动走棋显示走法");
            return;
        }
        if (panelState.running && panelState.modeKey === "auto" && panelState.adapter.captureBackend() !== "window") {
            if (!panelState.moveCaptureNotice) {
                panelState.moveCaptureNotice = true;
                panelActions.logLine("连续箭头需要窗口截图（Android 14+ 与无障碍服务）；当前通道保持自动走棋，不反复清屏闪箭头");
            }
            clearOverlay("当前截图没有隔离绘制层");
            return;
        }
        if (!panelState.grid || panelState.renderCaptureFailed ||
            (typeof panelState.adapter.windowCaptureNeedsCalibration === "function" &&
                panelState.adapter.captureBackend() === "window" && panelState.adapter.windowCaptureNeedsCalibration())) {
            clearOverlay(panelState.grid ? "窗口帧不可用，等待重新定位" : "等待定位棋盘");
            return;
        }
        if (!panelState.overlayOn) {
            try {
                panelState.overlayOn = panelState.adapter.overlayShow() === true;
            } catch (error) {
                panelState.overlayOn = false;
            }
            if (!panelState.overlayOn) {
                panelActions.logLine("画板打不开（多半是没有悬浮窗权限）");
                return;
            }
        }
        const points = [];
        const isolated = panelState.adapter.captureBackend() === "window";
        if (!isolated && panelState.lastClickPlan && panelState.lastClickPlan.from) {
            points.push({
                x: panelState.lastClickPlan.from.x,
                y: panelState.lastClickPlan.from.y,
                label: "▶" + panelState.lastClickPlan.move
            });
            points.push({
                x: panelState.lastClickPlan.to.x,
                y: panelState.lastClickPlan.to.y,
                label: "◀" + panelState.lastClickPlan.move
            });
        }
        // 「最近着法」框（2026-09-25 用户要求）：我方/对方各画「起/落」两个框（我起/我落/敌起/敌落）
        let moveMarks = null;
        try {
            if (panelState.driver && typeof panelState.driver.lastMoves === "function") {
                moveMarks = panelState.driver.lastMoves();
            }
        } catch (error) {
            moveMarks = null;
        }
        let built = { items: [], text: "" };
        try {
            if (panelState.drawMode !== "moves" && !(panelState.running && panelState.modeKey === "auto")) built = panelState.overlayDraw.build({
                grid: panelState.grid,
                result: result,
                expectChars: expectChars || null,
                mode: panelState.drawMode,
                points: panelState.drawMode === "grid" ? null : points,
                moveMarks: panelState.drawMode === "grid" ? null : moveMarks,
                extraText: overlayTextNow()
            });
            {
                const machine = panelState.driver && panelState.driver.machine;
                const state = machine ? machine.state() : "IDLE";
                const planned = options && options.movePlan;
                const renderInput = {
                    grid:panelState.grid, result:result,
                    ticket:planned ? planned.ticket : machine && typeof machine.currentTicket === "function" ? machine.currentTicket() : null,
                    versions:planned ? planned.versions : machine && typeof machine.versions === "function" ? machine.versions() : null,
                    state:planned ? planned.state : state, stateLabel:panelState.STATE_LABEL[planned ? planned.state : state] || state,
                    retainedTicket:retainedTicket,
                    prediction:panelState.driver && typeof panelState.driver.predictionForDisplay === "function"
                        ? panelState.driver.predictionForDisplay() : null,
                    running:panelState.running, nowMono:Date.now(),
                    capture:result && result.frame && result.frame.captureMetadata,
                    timing:panelState.boardService && typeof panelState.boardService.lastTiming === "function"
                        ? panelState.boardService.lastTiming() : null
                };
                if (panelState.modeKey === "auto" && renderState.validTicket(renderInput))
                    retainedTicket = Object.assign({}, renderInput.ticket, {pvIccs:(renderInput.ticket.pvIccs || []).slice()});
                renderInput.retainedTicket = retainedTicket;
                const snapshot = renderState.build(renderInput);
                if (retainedTicket && snapshot.hasMove) retainedTicket.displayPositionVersion = renderInput.versions.positionVersion;
                // 隔离截图的短暂识别不确定不反复收放；硬失败/暂停/换几何仍走 clearOverlay。
                if (panelState.drawMode === "moves" && panelState.running && retainedTicket &&
                    result && (result.ok === false || result.unknownCells > 0) &&
                    ["CLICKING","VERIFYING","WAIT_STABLE","WAIT_TURN","THINKING"].indexOf(renderInput.state) >= 0 &&
                    renderInput.versions && retainedTicket.modelVersion === renderInput.versions.modelVersion &&
                    retainedTicket.sessionId === renderInput.versions.sessionId &&
                    retainedTicket.geometryVersion === panelState.grid.version) return;
                if (retainedTicket && !snapshot.hasMove && (result && result.ok !== false && !result.unknownCells ||
                    ["PAUSED","STOPPED","RECOVERING","ERROR","GAME_OVER"].indexOf(renderInput.state) >= 0)) retainedTicket = null;
                panelState.lastRenderState = snapshot;
                if (panelState.drawMode !== "grid" || (panelState.running && panelState.modeKey === "auto"))
                    built.items = built.items.concat(snapshot.items);
                built.text = snapshot.text;
                if (panelState.drawMode !== "moves" && !(panelState.running && panelState.modeKey === "auto") &&
                    typeof panelState.adapter.screenSize === "function" && typeof panelActions.panelSafeBand === "function") {
                    const screen = panelState.adapter.screenSize();
                    const blocked = [];
                    if (typeof panelActions.panelRect === "function") blocked.push(panelActions.panelRect());
                    const board = typeof panelActions.boardRect === "function" ? panelActions.boardRect() : null;
                    if (board) {
                        const padding = Math.max(Math.abs(panelState.grid.stepX), Math.abs(panelState.grid.stepY)) * 0.6;
                        blocked.push({y:board.y - padding, height:board.height + padding * 2});
                    }
                    if (panelState.logWin && !panelState.collapsed) try {
                        blocked.push({y:panelState.logWin.getY(true), height:panelState.LOG_H});
                    } catch (error) {}
                    const hud = renderState.hudBounds(screen, panelActions.panelSafeBand(screen), blocked);
                    snapshot.hudBounds = hud;
                    built.items.push({kind:"hud", x1:hud ? hud.left : 0, y1:hud ? hud.top : 0,
                        x2:hud ? hud.right : 0, y2:hud ? hud.bottom : 0});
                    if (!hud && !panelState.hudSpaceNoted) {
                        panelState.hudSpaceNoted = true;
                        panelActions.logLine("绘制诊断没有空带：收起操作面板可显示，避免遮住按钮和棋盘");
                    }
                    if (hud) panelState.hudSpaceNoted = false;
                }
                if (panelState.drawMode === "moves" || (panelState.running && panelState.modeKey === "auto")) built.text = "";
            }
        } catch (error) {
            panelActions.logLine("绘制失败：" + error);
            return;
        }
        panelState.lastOverlay = built;
        const packetKey = JSON.stringify(built);
        if (lastPacketKey === packetKey) return; // idle search polls do not re-submit identical geometry
        try {
            if (panelState.adapter.overlayUpdate(built.items, built.text) !== false) lastPacketKey = packetKey;
        } catch (error) {
            // 画不上去不影响业务
        }
    }

    return { overlayTextNow: overlayTextNow, refreshOverlay: refreshOverlay, clearOverlay:clearOverlay,
        prepareOverlayForCapture:prepareOverlayForCapture, prepareContinuousMoveCapture:prepareContinuousMoveCapture };
}

module.exports = { create: create };
