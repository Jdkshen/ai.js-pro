// @engine quickjs
// 多线程与并发（worker 并发 / 串行对比）
// 用途：用 threads.start 起 worker 并发跑任务，并与串行耗时对比；同时演示 engines 起独立脚本
// 前置：无
// 覆盖：threads / engines / events

var startedAt = Date.now();

// ---------------- 1) 串行：3 个任务依次睡 1 秒 ----------------
function work(name) {
    sleep(1000);                 // 模拟耗时任务（网络请求、图像处理……）
    return name + '@' + Date.now();
}

var serialStart = Date.now();
var serialResults = [work('A'), work('B'), work('C')];
var serialCost = Date.now() - serialStart;
console.log('串行 3 个任务耗时 ' + serialCost + 'ms');
for (var i = 0; i < serialResults.length; i++) {
    console.log('  ' + serialResults[i]);
}

// ---------------- 2) 并发：3 个 worker 各睡 1 秒 ----------------
var parallelStart = Date.now();
var workers = [];
for (var i = 0; i < 3; i++) {
    workers.push(threads.start(function () {
        sleep(1000);
        return __args.name + '@' + Date.now();
    }, {name: String.fromCharCode(65 + i)}));      // 'A' / 'B' / 'C'
}

var parallelResults = [];
for (var i = 0; i < workers.length; i++) {
    var value = workers[i].waitForResult(8000);
    if (value === undefined) {
        throw new Error('worker ' + i + ' 超时未返回');
    }
    parallelResults.push(value);
}
var parallelCost = Date.now() - parallelStart;
console.log('并发 3 个 worker 耗时 ' + parallelCost + 'ms');
for (var i = 0; i < parallelResults.length; i++) {
    console.log('  ' + parallelResults[i]);
}

// ---------------- 3) 事件总线：worker 里发、主脚本收 ----------------
var hits = [];
events.on('worker-done', function (name) {
    hits.push(name);
});
for (var i = 0; i < 2; i++) {
    threads.start(function () {
        sleep(300);
        events.emit('worker-done', __args.name);   // 注意：worker 之间不共享内存，事件总线也各自独立
    }, {name: 'W' + i});
}
sleep(1200);
console.log('主脚本收到的事件数 = ' + hits.length + '（worker 的事件总线是独立的，所以通常是 0，跨线程通信用 storages 或回传结果）');

// ---------------- 4) 独立引擎：真并行跑另一个脚本文件 ----------------
if (engines.myEngine().execArgv && engines.myEngine().execArgv.parallelDemo) {
    var child = engines.execScript('toast("子引擎在另一个线程里运行"); sleep(500);', {name: '并发子脚本'});
    console.log('子引擎 id = ' + child.getEngine().getId());
}

console.log('全部结束，总耗时 ' + (Date.now() - startedAt) + 'ms');
console.log('结论：并发耗时≈单个任务耗时（' + parallelCost + 'ms），串行≈任务数×单个耗时（' + serialCost + 'ms）');

// 小结：
// 1) threads.start(fn, args) 起 worker，函数里用 __args 取参数，返回值用 waitForResult(超时) 拿；
// 2) 需要跑完整脚本用 engines.execScript / execScriptFile（独立引擎，互不阻塞）；
// 3) worker 之间不共享内存与事件总线，回传结果用返回值，传大对象用 storages。
