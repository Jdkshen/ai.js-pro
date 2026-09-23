// @engine quickjs
// 前置：Android 11+、AI.js Pro 无障碍；通知使用权由弹窗手动开启。
// 运行后打开/关闭系统录屏，观察日志中的截图通道变化；不自动批准系统权限。
if (!requestScreenCapture({ mode: 'auto' })) throw new Error('未启用自动截图');
console.log('自动切换测试开始：可启动系统录屏，再停止录屏并确认高速截图授权');
var started = Date.now();
var count = 0;
while (Date.now() - started < 180000) {
    var frame = images.captureScreen({ mode: 'fast', size: 720, fresh: false });
    try {
        count++;
        if (count === 1 || count % 30 === 0) {
            console.log('截图成功：帧=' + count + ' 尺寸=' + frame.width + 'x' + frame.height);
        }
    } finally {
        frame.recycle();
    }
    sleep(30);
}
console.log('自动切换测试完成：帧=' + count);
