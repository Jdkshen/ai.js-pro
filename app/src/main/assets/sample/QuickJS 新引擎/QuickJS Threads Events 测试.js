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

// shutDownAll
threads.shutDownAll();
assert('shutDownAll 无异常', true);

console.log('\n=== THREADS+EVENTS 测试完成: ' + pass + ' 通过, ' + fail + ' 失败 ===');
