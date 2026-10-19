"rhino";
// 默认仅显示命令。确认需要结束其他应用后台进程后再设为 true。
var EXECUTE = false;
if (EXECUTE) { log(shell("am kill-all", true)); }
else { log("预览：su -c am kill-all（当前未执行）"); }
