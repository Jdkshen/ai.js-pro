// @engine quickjs
// Shizuku Shell 点击
// 用途：通过 Shizuku 授权执行 shell 输入点击
// 前置：Shizuku 服务或 root
// 覆盖：shizuku / shell

const x = 500;
const y = 800;

if (!shizuku.isAvailable()) {
    throw new Error('Shizuku 未运行。请先打开 Shizuku 并启动服务，再重新运行本例。');
}

if (!shizuku.hasPermission()) {
    console.log('正在请求 AI.js Pro 的 Shizuku 权限……');
    if (!shizuku.requestPermission(60000)) {
        throw new Error('未获得 Shizuku 权限，请到 Shizuku 的“已授权应用”中检查。');
    }
}

const command = 'input tap ' + Math.trunc(x) + ' ' + Math.trunc(y);
const startedAt = performance.now();
const result = shizuku.shell(command, { timeout: 5000, maxOutput: 4096 });
const elapsedMs = performance.now() - startedAt;

console.log('SHIZUKU_SHELL_CLICK', {
    command: command,
    code: result.code,
    result: result.result,
    error: result.error,
    elapsedMs: Number(elapsedMs.toFixed(3))
});

if (result.code !== 0) {
    throw new Error('Shizuku 点击失败：' + (result.error || result.result || result.code));
}

toastLog('Shizuku 点击完成：' + x + ', ' + y + '，耗时 ' + elapsedMs.toFixed(3) + ' ms');

// 等价写法：
// shell('input tap 500 800', { shizuku: true, timeout: 5000 });
