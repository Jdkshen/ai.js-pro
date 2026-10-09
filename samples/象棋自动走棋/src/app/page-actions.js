// @engine quickjs
"use strict";
// 一次只发一个棋盘操作；必须等同项目面板 ready，并等执行回执再报告完成。
function create(options) {
    const o = options;
    const now = o.now || Date.now;
    let pending = null;
    let seq = 0;
    const labels = { START:"启动对局", RELOCATE:"定位棋盘", RECOGNIZE:"识别一帧", NEW_SESSION:"新会话",
        DECLARE_TURN:"声明轮次", COLLAPSE:"收起 / 展开悬浮窗", DRAW:"识别绘制" };
    function ready() {
        const other = o.bus.readOther();
        return other && other.fresh && other.state && other.state.readyAt &&
            other.state.baseDir === o.baseDir ? other.state : null;
    }
    function request(command) {
        if (pending) { o.report("上一项操作还未完成，请稍候。"); return false; }
        if (!labels[command]) return false;
        pending = {command:command,id:String(now())+"-"+(++seq),at:now(),sent:false};
        if (!ready()) o.open();
        o.report("正在准备" + labels[command] + "…");
        pump();
        return true;
    }
    function pump() {
        if (!pending) return;
        const state = ready();
        const ack = state && state.lastPageAction;
        if (ack && ack.id === pending.id) {
            o.report(labels[pending.command] + (ack.ok ? (ack.pending ? "正在准备，可点停止取消。" : "已完成。") : "未完成：" + (ack.reason || "请查看悬浮窗日志")));
            pending = null; return;
        }
        if (now() - pending.at > (pending.sent ? 45000 : 12000)) {
            o.report("未收到" + labels[pending.command] + "的反馈，请检查悬浮窗权限和运行记录后重试。");
            pending = null; return;
        }
        if (!pending.sent && state) {
            pending.sent = o.bus.send("floaty", "pageAction", {id:pending.id,command:pending.command,
                targetReadyAt:state.readyAt, requestedAt:now()});
            if (pending.sent) o.report(labels[pending.command] + "执行中…");
        }
    }
    return {request:request,pump:pump,cancel:function(){
        if (pending && pending.sent) o.bus.stopOther("页面取消待执行操作");
        pending=null;
    }};
}
module.exports = {create:create};
