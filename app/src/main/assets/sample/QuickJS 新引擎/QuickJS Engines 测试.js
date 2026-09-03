// @engine quickjs
// QuickJS Engines 模块完整测试
var pass = 0, fail = 0;
function assert(name, cond) {
    if (cond) { pass++; console.log('✅ ' + name); }
    else { fail++; console.error('❌ ' + name); }
}

assert('engines.execScript 函数存在', typeof engines.execScript === 'function');
assert('engines.execScriptFile 函数存在', typeof engines.execScriptFile === 'function');
assert('engines.myEngine 函数存在', typeof engines.myEngine === 'function');
assert('engines.all 函数存在', typeof engines.all === 'function');
assert('engines.stopAll 函数存在', typeof engines.stopAll === 'function');

// 测试 myEngine()
var me = engines.myEngine();
assert('myEngine 返回对象', typeof me === 'object' && me !== null);
assert('myEngine 有 id', typeof me.id === 'number');
assert('myEngine 有 handle', typeof me.handle === 'number');
assert('myEngine 有 source', typeof me.source === 'string');
assert('myEngine 有 engineName', typeof me.engineName === 'string');
assert('myEngine.isDestroyed 是函数', typeof me.isDestroyed === 'function');
assert('myEngine.forceStop 是函数', typeof me.forceStop === 'function');
assert('myEngine 当前未销毁', me.isDestroyed() === false);
console.log('  当前引擎: id=' + me.id + ', name=' + me.engineName + ', source=' + me.source);

// 测试 all()
var all = engines.all();
assert('all 返回数组', Array.isArray(all));
assert('all 至少有 1 个引擎', all.length >= 1);
console.log('  引擎数量: ' + all.length);
all.forEach(function (e) {
    console.log('    - id=' + e.id + ', name=' + e.engineName + ', destroyed=' + e.isDestroyed());
});

// 测试 execScript
console.log('即将启动子脚本...');
var child = engines.execScript('test-child', 'var x = 1 + 2; console.log("child result:", x);');
assert('execScript 返回引擎对象', typeof child === 'object' && child !== null);
assert('execScript 有 handle', typeof child.handle === 'number');
assert('execScript handle > 0', child.handle > 0);
console.log('  子脚本句柄: ' + child.handle);

// 等待子脚本完成
sleep(500);
assert('子脚本完成后 isDestroyed', child.isDestroyed() === true);

console.log('\n=== ENGINES 测试完成: ' + pass + ' 通过, ' + fail + ' 失败 ===');
