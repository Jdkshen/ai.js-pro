// @engine quickjs
// Shizuku Shell 自动滑动
// 用途：通过 Shizuku 执行滑动与手势输入
// 前置：Shizuku 服务或 root
// 覆盖：device / shizuku / shell

/**
 * 使用 Shizuku Shell 自动向上滑动屏幕。
 *
 * 运行前：
 * 1. 启动 Shizuku 服务；
 * 2. 给 AI.js Pro 授予 Shizuku 权限；
 * 3. 按需要修改下面的配置。
 */

const config = {
    count: 10,          // 滑动次数；设为 0 时持续滑动，需手动停止脚本
    durationMs: 450,    // 每次滑动持续时间
    intervalMs: 900,    // 两次滑动之间的等待时间
    startXRatio: 0.5,   // 起点 X：屏幕宽度的 50%
    startYRatio: 0.78,  // 起点 Y：屏幕高度的 78%
    endXRatio: 0.5,     // 终点 X：屏幕宽度的 50%
    endYRatio: 0.28     // 终点 Y：屏幕高度的 28%
};

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, Math.trunc(value)));
}

function requireShizuku() {
    if (!shizuku.isAvailable()) {
        throw new Error('Shizuku 未运行，请先启动 Shizuku 服务。');
    }
    if (!shizuku.hasPermission()) {
        console.log('正在请求 AI.js Pro 的 Shizuku 权限……');
        if (!shizuku.requestPermission(60000)) {
            throw new Error('未获得 Shizuku 权限，请在 Shizuku 中检查授权。');
        }
    }
}

requireShizuku();

const screenWidth = Math.max(1, Math.trunc(device.width));
const screenHeight = Math.max(1, Math.trunc(device.height));
const startX = clamp(screenWidth * config.startXRatio, 0, screenWidth - 1);
const startY = clamp(screenHeight * config.startYRatio, 0, screenHeight - 1);
const endX = clamp(screenWidth * config.endXRatio, 0, screenWidth - 1);
const endY = clamp(screenHeight * config.endYRatio, 0, screenHeight - 1);
const durationMs = clamp(config.durationMs, 1, 60000);
const intervalMs = Math.max(0, Math.trunc(config.intervalMs));

console.log('开始自动滑动', {
    screen: screenWidth + 'x' + screenHeight,
    from: [startX, startY],
    to: [endX, endY],
    count: config.count === 0 ? '持续运行' : config.count
});

for (let index = 0; config.count === 0 || index < config.count; index++) {
    const command = 'input swipe '
        + startX + ' ' + startY + ' '
        + endX + ' ' + endY + ' '
        + durationMs;
    const startedAt = performance.now();
    const result = shizuku.shell(command, {
        timeout: durationMs + 5000,
        maxOutput: 4096
    });
    const elapsedMs = performance.now() - startedAt;

    console.log('第 ' + (index + 1) + ' 次滑动', {
        code: result.code,
        error: result.error,
        elapsedMs: Number(elapsedMs.toFixed(3))
    });

    if (result.code !== 0) {
        throw new Error('Shell 滑动失败：'
            + (result.error || result.result || ('code=' + result.code)));
    }

    if ((config.count === 0 || index + 1 < config.count) && intervalMs > 0) {
        sleep(intervalMs);
    }
}

toastLog('Shell 自动滑动完成');

// Root 手机可将执行语句替换为：
// shell(command, { root: true, timeout: durationMs + 5000, maxOutput: 4096 });
