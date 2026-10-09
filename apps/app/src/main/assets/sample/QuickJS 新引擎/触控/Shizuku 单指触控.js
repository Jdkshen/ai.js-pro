// @engine quickjs
// 免 Root 的连续单指触控，需要先启动 Shizuku 并授权 AI.js Pro。
// 此通道仅支持一个手指；需要多指请运行同目录的 Root 多点触控示例。
if (!dialogs.confirm("Shizuku 单指触控", "3 秒后在当前页面中间区域演示一次向上滑动。继续？")) exit();
sleep(3000);
var finger = touch.create({backend: "shizuku"});
var x = Math.floor(device.width / 2), y = Math.floor(device.height * 0.65);
try {
    finger.down(0, x, y);
    for (var i = 1; i <= 15; i++) {
        finger.move(0, x, y - i * 10);
        sleep(25);
    }
    finger.up(0); // UP 使用最后一次 MOVE 的坐标。
    toastLog("单指触控演示完成");
} finally { finger.exit(); }
