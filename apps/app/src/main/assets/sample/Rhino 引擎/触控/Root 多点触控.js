// @engine rhino
// 将当前页面的中间区域作为双指缩放演示区，需要设备 Root 并授予 su。
// 不再需要加载 touch.dex、选择 ABI 或手动复制 .so。
// down/move/up 的参数顺序和 ZIP 一样：id 在前，屏幕像素坐标在后。
if (!dialogs.confirm("Root 多点触控", "3 秒后在当前页面演示双指缩放。请先打开适合测试的页面。继续？")) exit();
sleep(3000);
var finger = touch.create({backend: "root"});
var cx = Math.floor(device.width / 2), cy = Math.floor(device.height / 2);
var start = Math.floor(device.width / 10);
try {
    finger.down(0, cx - start, cy);
    finger.down(1, cx + start, cy);
    for (var i = 1; i <= 20; i++) {
        finger.move(0, cx - start - i * 5, cy);
        finger.move(1, cx + start + i * 5, cy);
        sleep(20);
    }
    finger.up(0);
    // 验证抬起一指后再按下另一指，不复用仍在使用的触控槽。
    finger.down(2, cx - start, cy);
    finger.move(2, cx - start - 50, cy);
    finger.up(2);
    finger.up(1);
    toastLog("多点触控演示完成");
} finally {
    // 异常或脚本结束也会自动抬起尚未释放的手指。
    finger.exit();
}
