// @engine quickjs
// Storages 模块测试
// 用途：本地存储的增删改查与清空
// 前置：无
// 覆盖：storages

var pass = 0, fail = 0;
function assert(name, cond) {
    if (cond) { pass++; console.log('✅ ' + name); }
    else { fail++; console.error('❌ ' + name); }
}

var store = storages.create('quickjs_test_' + Date.now());

// 基础读写
store.put('str', 'hello');
assert('写入字符串', store.get('str') === 'hello');

store.put('num', 42);
assert('写入数字', store.get('num') === 42);

store.put('bool', true);
assert('写入布尔', store.get('bool') === true);

store.put('null', null);
assert('写入 null', store.get('null') === null);

// 嵌套对象
store.put('obj', { a: 1, b: [2, 3], c: '中文' });
var obj = store.get('obj');
assert('嵌套对象', obj !== null && obj.a === 1 && obj.b[0] === 2 && obj.c === '中文');

// 默认值
assert('不存在的键默认值', store.get('notExist', 'default') === 'default');
assert('不存在的键 null 默认值', store.get('notExist2', null) === null);

// contains
assert('contains 已有键', store.contains('str') === true);
assert('contains 不存在的键', store.contains('notExist') === false);

// remove
store.remove('str');
assert('remove 后不存在', store.contains('str') === false);

// 中文键名
store.put('中文键', '中文值');
assert('中文键名', store.get('中文键') === '中文值');

// 存储隔离
var store2 = storages.create('quickjs_test_other_' + Date.now());
store2.put('key', 'other');
assert('存储隔离 - store 无 key', store.get('key', 'none') === 'none');
assert('存储隔离 - store2 有 key', store2.get('key') === 'other');

// clear
store.clear();
store2.clear();
assert('clear 后为空', store.get('str', 'gone') === 'gone');

console.log('\n=== STORAGES 测试完成: ' + pass + ' 通过, ' + fail + ' 失败 ===');
