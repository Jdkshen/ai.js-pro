// @engine quickjs

var worker = threads.start(function () {
    return __args.a * __args.b;
}, {a: 6, b: 7});

console.log('启动后状态:', worker.getResult());
var value = worker.waitForResult(5000);
if (value !== 42) {
    throw new Error('Worker 返回值错误: ' + value);
}
if (!worker.join(1000)) {
    throw new Error('Worker 未在预期时间内结束');
}

console.log('QUICKJS_WORKER_RESULT_OK', value);
