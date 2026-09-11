// @engine quickjs
// Shell 模块测试
// 用途：普通与 root shell 命令执行、超时与输出
// 前置：普通命令无需 root（root 用例会检测可用性）
// 覆盖：shell

var pass = 0, fail = 0;
function assert(name, cond) {
    if (cond) { pass++; console.log('✅ ' + name); }
    else { fail++; console.error('❌ ' + name); }
}

assert('shell 函数存在', typeof shell === 'function');
assert('shell.isRootAvailable 函数存在', typeof shell.isRootAvailable === 'function');

// 基本非 Root 命令
var r1 = shell('echo hello');
assert('echo 返回 code 0', r1.code === 0);
assert('echo 返回结果包含 hello', r1.result.trim() === 'hello');
assert('echo 无错误', r1.error === '');
console.log('  echo 结果: ' + JSON.stringify(r1));

// 带超时的命令
var r2 = shell('sleep 0.1 && echo done', { timeout: 5000 });
assert('sleep 后 echo 返回 done', r2.result.trim() === 'done');

// 超时测试
var start = Date.now();
var r3 = shell('sleep 5', { timeout: 1000 });
var elapsed = Date.now() - start;
assert('超时测试 code != 0 或 error 包含 timeout', r3.error === 'timeout' || r3.code === -1);
assert('超时在 1-3 秒内', elapsed >= 900 && elapsed <= 3000);
console.log('  超时耗时: ' + elapsed + 'ms, error: ' + r3.error);

// 空命令
var r4 = shell('');
assert('空命令不崩溃', typeof r4.code === 'number');

// 最大输出限制测试
var r5 = shell('for i in $(seq 1 10000); do echo "line$i"; done', { maxOutput: 1000 });
assert('输出被截断', r5.result.length <= 2000);

// Root 检测
var hasRoot = shell.isRootAvailable();
assert('isRootAvailable 返回布尔', typeof hasRoot === 'boolean');
console.log('  Root 可用: ' + hasRoot);

console.log('\n=== SHELL 测试完成: ' + pass + ' 通过, ' + fail + ' 失败 ===');
