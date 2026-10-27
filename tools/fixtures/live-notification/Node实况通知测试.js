// @engine node24
// 三引擎实况通知对照：不创建悬浮窗、不播放音乐，180秒后结束。
console.log("Node 实况通知测试开始，请回桌面观察，并在结束前复制诊断。");
var notificationProbeTimer = setInterval(function () {
    console.log("Node 实况通知测试仍在运行");
}, 15000);
setTimeout(function () {
    clearInterval(notificationProbeTimer);
    console.log("Node 实况通知测试自然结束，系统应撤除实况通知。");
}, 180000);
