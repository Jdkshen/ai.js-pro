// @engine quickjs
// Shell 自动测试
// 用途：自动化校验 shell 返回值与错误路径
// 前置：无
// 覆盖：shell

function assert(condition, message) {
    if (!condition) throw new Error('断言失败: ' + message);
}

console.log('=== QuickJS shell 自动测试 ===');

var normal = shell("printf 'quickjs-shell-ok'");
console.log('普通命令:', JSON.stringify(normal));
assert(normal.code === 0, '普通命令退出码应为 0');
assert(normal.result === 'quickjs-shell-ok', '普通命令输出不匹配');

var limited = shell("printf '1234567890'", { maxOutput: 4 });
console.log('输出限制:', JSON.stringify(limited));
assert(limited.result === '1234', 'maxOutput 没有生效');

var started = performance.now();
var timedOut = shell('sleep 2', { timeout: 150, maxOutput: 1024 });
var elapsed = performance.now() - started;
console.log('超时测试:', JSON.stringify(timedOut), '耗时=' + elapsed.toFixed(3) + 'ms');
assert(timedOut.code === -1 && timedOut.error === 'timeout', '超时结果不正确');
assert(elapsed < 1500, 'shell 超时没有及时中止进程');

console.log('=== shell 自动测试通过 ===');
