// @engine quickjs
"use strict";

/** Small native touch window; command handling stays on the panel's script thread. */
function available() {
    const api = typeof drawing !== "undefined" && drawing.panel;
    return !!api && ["show", "update", "poll", "hide"].every(function (name) { return typeof api[name] === "function"; });
}

function createWindow(state, actions, x, y) {
    if (!available()) return null;
    const api = drawing.panel;
    let closed = false, visible = true, touchable = true, timer = null, lastState = "";
    function snapshot() {
        const busy = !!(state.running || state.starting);
        return {title:"象棋 · " + actions.compactStateText(), buttons:[
            {id:"mode",label:state.modeKey === "auto" ? "自动" : state.modeKey === "advice" ? "建议" : "识别"},
            {id:"toggle",label:state.starting ? "取消" : busy ? "暂停" : state.startError ? "重试" : "开始"},
            {id:"stop",label:"停止"},
            {id:"locate",label:"定位",enabled:!busy},
            {id:"recognize",label:"识别",enabled:!busy},
            {id:"more",label:"更多"}
        ]};
    }
    // Six touch targets need the expanded panel's width; retaining the old
    // four-button strip width made this "compact" window taller than the panel.
    const options = snapshot(); options.x = x; options.y = y;
    options.width = state.PANEL_W || state.COMPACT_W; options.columns = 6;
    const initial = api.show(options);
    if (!initial || !initial.ok) return null;
    let position = initial;
    lastState = JSON.stringify(snapshot());
    function updateState() {
        if (closed) return;
        const next = snapshot(), key = JSON.stringify(next);
        if (key !== lastState) { api.update(next); lastState = key; }
    }
    function stop() {
        actions.runPanelCommand("STOP");
        const other = state.bus.readOther();
        if (other && other.fresh && other.state && other.state.running) state.bus.stopOther("ImGui 面板停止");
    }
    function poll() {
        if (closed) return;
        try {
            const batch = api.poll();
            if (!batch || !batch.ok) {
                actions.logLine("ImGui 操作窗已失效，恢复高级面板");
                actions.setPanelCollapsed(false); window.close(); return;
            }
            position = batch;
            if ((batch.events || []).some(function (e) { return e.id === "drag"; })) {
                state.panelUserMoved = true; state.panelY = position.y;
            }
            if (!visible || !touchable || !state.collapsed) return;
            const events = (batch.events || []).filter(function (e) {
                const age = Date.now() - e.at;
                return age >= 0 && age <= (e.id === "stop" ? 12000 : 1500);
            });
            // A stop in this batch cancels queued starts, including rapid double taps.
            if (events.some(function (e) { return e.id === "stop"; })) { stop(); actions.render(); return; }
            for (let i = 0; i < events.length; i++) {
                const event = events[i];
                if (Date.now() - event.at > 1500) continue;
                if (event.id === "drag") { state.panelUserMoved = true; state.panelY = position.y; }
                else if (event.id === "mode") {
                    if (state.running || state.starting) actions.runPanelCommand("STOP");
                    const index = state.MODES.indexOf(state.modeKey);
                    state.modeKey = state.MODES[(index + 1) % state.MODES.length];
                    require("../app/session-settings.js").write({mode:state.modeKey});
                    state.bus.syncConfig("mode", state.modeKey);
                    actions.logLine("模式切换为 " + state.MODE_LABEL[state.modeKey]);
                } else if (event.id === "toggle") actions.runPanelCommand(state.running || state.starting ? "STOP" : "START");
                else if (event.id === "locate" && !state.running && !state.starting) actions.runPanelCommand("RELOCATE");
                else if (event.id === "recognize" && !state.running && !state.starting) actions.runPanelCommand("RECOGNIZE");
                else if (event.id === "more") { actions.setPanelCollapsed(false); break; }
            }
            updateState();
            if (events.length) actions.render();
        } catch (error) { actions.logLine("ImGui 操作失败：" + error); }
    }
    const window = {
        isImGui:true,
        getX:function () { return position.x; }, getY:function () { return position.y; },
        getWidth:function () { return position.width; }, getHeight:function () { return position.height; },
        setPosition:function (nx, ny) { position.x = nx; position.y = ny; api.update({x:nx,y:ny}); },
        setContentVisibility:function (value) { visible = value === 0; api.update({visible:visible}); },
        setTouchable:function (value) { touchable = !!value; api.update({touchable:touchable}); },
        updateState:updateState, poll:poll,
        close:function () { if (closed) return; closed = true; if (timer) clearInterval(timer); timer = null; api.hide(); }
    };
    timer = setInterval(poll, 100);
    actions.logLine("ImGui 操作窗已启用：拖动状态栏，更多打开完整参数；定位/识别在暂停后可用");
    return window;
}
module.exports = {available:available, createWindow:createWindow};
