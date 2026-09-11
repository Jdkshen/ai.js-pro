// @engine quickjs
// Continuation 阻塞等待
// 用途：continuation.delay 阻塞等待与 enabled 行为的说明与验证
// 前置：无
// 覆盖：timers / continuation

console.log('continuation.enabled = ' + continuation.enabled);

var start = Date.now();
continuation.delay(300);
console.log('delay(300) 实际耗时 = ' + (Date.now() - start) + ' ms（阻塞在脚本线程）');

// 等价写法
sleep(200);
console.log('sleep(200) 返回后继续执行');

// 需要异步等待时用 Promise / async 语法（QuickJS 原生支持）
var pending = Promise.resolve('done');
var settled = false;
pending.then(function (value) {
    settled = true;
    console.log('Promise 结果 = ' + value);
});
sleep(200);
// 注意：sleep 是原生阻塞等待，不驱动 Promise job 队列，所以这里通常还是 false，
// 回调会在下一次进入事件循环（例如再 sleep / 脚本结束时）执行。
console.log('sleep 后 settled = ' + settled);
sleep(200);
console.log('再次 sleep 后 settled = ' + settled);

// 明确报错路径：捕获后可以指引脚本改写
try {
    continuation.await();
} catch (error) {
    console.log('continuation.await -> ' + error.message);
}
try {
    Promise.resolve(1).await();
} catch (error) {
    console.log('Promise.await -> ' + error.message);
}

// 定时器仍然是异步的（在引擎事件循环里执行）
var ticks = 0;
var timer = setInterval(function () {
    ticks++;
    if (ticks >= 3) {
        clearInterval(timer);
        console.log('setInterval 触发 ' + ticks + ' 次后清理');
    }
}, 100);
sleep(600);
