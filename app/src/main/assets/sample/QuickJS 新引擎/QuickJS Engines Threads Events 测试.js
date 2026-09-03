// @engine quickjs

function assert(condition, message) {
    if (!condition) throw new Error('断言失败: ' + message);
}

console.log('=== QuickJS engines / threads / events 测试 ===');

// events 是当前脚本引擎内的同步事件总线。
var received = [];
function onMessage(value) { received.push('on:' + value); }
events.on('message', onMessage);
events.once('message', function (value) { received.push('once:' + value); });
assert(events.listenerCount('message') === 2, '监听器数量错误');
events.emit('message', 1);
events.emit('message', 2);
events.removeListener('message', onMessage);
assert(received.join(',') === 'on:1,once:1,on:2', '事件执行顺序错误');
assert(events.listenerCount('message') === 0, '监听器没有移除');
console.log('events 通过:', received.join(','));

// 每个 threads.start 都使用一个独立 QuickJS 引擎。
var resultStore = storages.create('quickjs_thread_example');
resultStore.clear();
var worker = threads.start(function () {
    var store = storages.create('quickjs_thread_example');
    store.put('answer', 6 * 7);
});

assert(worker.join(5000), '子线程 5 秒内没有结束');
assert(!worker.isAlive(), '已结束线程仍报告存活');
assert(resultStore.get('answer') === 42, '子线程结果错误');
console.log('threads 通过: answer=' + resultStore.get('answer'));

var current = engines.myEngine();
console.log('当前引擎:', JSON.stringify({ id: current.id, handle: current.handle, source: current.source }));
console.log('当前可见引擎数:', engines.all().length);
resultStore.clear();

console.log('=== engines / threads / events 测试通过 ===');
