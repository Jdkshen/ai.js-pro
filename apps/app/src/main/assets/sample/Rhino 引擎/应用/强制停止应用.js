"auto";
"rhino";
var appName = rawInput("请输入应用名称");
if (!appName) exit();
var targetPackage = getPackageName(appName);
if (!targetPackage) { toastLog("应用不存在：" + appName); exit(); }
if (!openAppSetting(targetPackage)) { toastLog("无法打开应用详情"); exit(); }
var stopButton = textMatches("(?i)强行停止|强制停止|Force stop").findOne(5000);
if (!stopButton) { toastLog("未找到停止按钮，请在应用详情页操作"); exit(); }
if (!stopButton.enabled()) { toastLog("应用已经停止"); exit(); }
stopButton.click();
// 系统可能再弹确认框，请核对目标应用后确认。
