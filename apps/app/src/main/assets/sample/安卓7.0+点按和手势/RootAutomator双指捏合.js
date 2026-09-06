// RootAutomator 使用 Root 发送多点触控事件。
// 请先确认设备已取得 Root 权限；运行结束时必须调用 exit() 释放输入设备。
var ra = null;

try {
    ra = new RootAutomator();
    sleep(2000);

    var width = device.width;
    var height = device.height;
    var startLeft = { x: Math.round(width * 0.2), y: Math.round(height * 0.25) };
    var startRight = { x: Math.round(width * 0.8), y: Math.round(height * 0.75) };
    var steps = 60;

    // 两个触点分别使用 0 和 1 作为手指 ID，并同时向屏幕中心移动。
    ra.touchDown(startLeft.x, startLeft.y, 0);
    ra.touchDown(startRight.x, startRight.y, 1);

    for (var i = 1; i <= steps; i++) {
        var progress = i / steps;
        ra.touchMove(
            Math.round(startLeft.x + (width / 2 - startLeft.x) * progress),
            Math.round(startLeft.y + (height / 2 - startLeft.y) * progress),
            0
        );
        ra.touchMove(
            Math.round(startRight.x + (width / 2 - startRight.x) * progress),
            Math.round(startRight.y + (height / 2 - startRight.y) * progress),
            1
        );
        sleep(8);
    }

    ra.touchUp(0);
    ra.touchUp(1);
} finally {
    if (ra) {
        ra.exit();
    }
}
