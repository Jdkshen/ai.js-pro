"quickjs";
// 由同目录的悬浮运行按钮启动；停止按钮负责结束这个子引擎。
var count = 0;
setInterval(function () {
    console.log('悬浮按钮子脚本运行中：' + (++count));
}, 1000);
