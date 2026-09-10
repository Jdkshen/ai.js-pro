// @engine quickjs
// QuickJS Threads + Events 模块完整测试
var pass = 0, fail = 0;
function assert(name, cond) {
    if (cond) { pass++; console.log('✅ ' + name); }
    else { fail++; console.error('❌ ' + name); }
}

// === events 测试 ===
assert('events.on 函数存在', typeof events.on === 'function');
assert('events.once 函数存在', typeof events.once === 'function');
assert('events.emit 函数存在', typeof events.emit === 'function');
assert('events.removeListener 函数存在', typeof events.removeListener === 'function');
assert('events.removeAllListeners 函数存在', typeof events.removeAllListeners === 'function');
assert('events.listenerCount 函数存在', typeof events.listenerCount === 'function');

// 基础事件
var received = [];
events.on('test', function (data) { received.push(data); });
events.emit('test', 'hello');
events.emit('test', 'world');
assert('on/emit 收到两次', received.length === 2 && received[0] === 'hello' && received[1] === 'world');

// once
var onceVal = null;
events.once('once-test', function (v) { onceVal = v; });
events.emit('once-test', 42);
assert('once 第一次触发', onceVal === 42);
events.emit('once-test', 99);
assert('once 第二次不触发', onceVal === 42);

// removeListener
var count = 0;
var listener = function () { count++; };
events.on('rm-test', listener);
events.emit('rm-test');
events.removeListener('rm-test', listener);
events.emit('rm-test');
assert('removeListener 有效', count === 1);

// listenerCount
assert('listenerCount 正确', events.listenerCount('test') === 1);

// removeAllListeners
events.removeAllListeners('test');
assert('removeAllListeners 清空', events.listenerCount('test') === 0);

events.removeAllListeners();
assert('removeAllListeners 全部清空', events.listenerCount('rm-test') === 0);

// === threads 测试 ===
assert('threads.start 函数存在', typeof threads.start === 'function');
assert('threads.exec 函数存在', typeof threads.exec === 'function');
assert('threads.currentThread 函数存在', typeof threads.currentThread === 'function');
assert('threads.shutDownAll 函数存在', typeof threads.shutDownAll === 'function');

// currentThread
var ct = threads.currentThread();
assert('currentThread 返回对象', typeof ct === 'object');
assert('currentThread.isAlive', ct.isAlive() === true);
assert('currentThread.getEngine', typeof ct.getEngine === 'function');

// threads.exec 带参数
console.log('启动 worker 线程...');
var t1 = threads.exec('test-worker', 'console.log("worker args:", JSON.stringify(__args));', { name: 'test', count: 42 });
assert('exec 返回 thread 对象', typeof t1 === 'object');
assert('exec thread.isAlive 初始为 true', t1.isAlive() === true);
sleep(500);
assert('exec thread 完成后 isAlive 为 false', t1.isAlive() === false);
assert('exec thread.join 返回 true', t1.join(100) === true);

// threads.start 函数模式
var t2 = threads.start(function () { console.log('function thread'); });
assert('start(function) 返回 thread 对象', typeof t2 === 'object');
assert('start thread.isAlive', t2.isAlive() === true);
sleep(300);
assert('start thread 完成', t2.isAlive() === false);

// === 共享事件总线测试(跨 worker) ===
var busReceived = [];
var busWorker = threads.exec('bus-worker',
    'events.bus.emit("bus-test", {from: "worker", n: 1});\n' +
    'events.bus.emit("bus-test", {from: "worker", n: 2});');
events.bus.on('bus-test', function (payload) { busReceived.push(payload); });
busWorker.join(5000);
sleep(300);
assert('共享总线收到 worker 事件(2条)', busReceived.length === 2);
assert('共享总线 payload 正确',
    busReceived.length === 2 && busReceived[0].from === 'worker' && busReceived[1].n === 2);

// 主线程 emit 也投递给自己
var selfBus = [];
events.bus.on('bus-self', function (v) { selfBus.push(v); });
events.bus.emit('bus-self', 7);
sleep(100);
assert('共享总线本地 emit 也收到', selfBus.length === 1 && selfBus[0] === 7);
events.bus.removeAllListeners();
assert('bus.removeAllListeners 清空', events.bus.listenerCount('bus-test') === 0);

// === thread.promise 测试 ===
var t4 = threads.exec('promise-worker', '({value: 42, name: __args && __args.name})', { name: 'p1' });
t4.join(5000);
var pResult = null, pError = null;
t4.promise(5000).then(function (v) { pResult = v; }, function (e) { pError = e; });
sleep(200);
assert('thread.promise 成功解析返回值', pResult !== null && pResult.value === 42);
assert('thread.promise 传入 __args', pResult !== null && pResult.name === 'p1');

// shutDownAll
threads.shutDownAll();
assert('shutDownAll 无异常', true);

console.log('\n=== THREADS+EVENTS 测试完成: ' + pass + ' 通过, ' + fail + ' 失败 ===');
