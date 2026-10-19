"quickjs";
// 开启无障碍或悬浮窗权限；只有操作窗范围接收触摸，拖动标题可移动。
const panel = drawing.panel;
let count = 0, finished = false;
function state() {
    return {title:"ImGui 操作窗 · 点击 " + count + " 次", buttons:[
        {id:"count",label:"计数"}, {id:"clear",label:"清零"}, {id:"close",label:"关闭"}
    ]};
}
const options = state(); options.x = 24; options.y = 250; options.columns = 3;
if (!panel.show(options).ok) throw new Error("无法创建 ImGui 操作窗，请检查权限");
log("ImGui 操作窗已启动，拖动标题移动；30 秒后自动关闭");
let timer = setInterval(function () {
    const result = panel.poll();
    (result.events || []).forEach(function (event) {
        log("ImGui 事件：", event.id, "位置", result.x, result.y);
        if (event.id === "count") { count++; panel.update(state()); }
        else if (event.id === "clear") { count = 0; panel.update(state()); }
        else if (event.id === "close") close();
    });
}, 100);
function close() {
    if (finished) return;
    finished = true;
    if (timer) clearInterval(timer); timer = null; panel.hide();
    exit();
}
setTimeout(close, 30000);
events.on("exit", close);
