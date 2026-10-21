'use strict';

// Exercise the full production prelude against a recording string-in/string-out
// host. Cached callables must keep fresh results, arguments, errors and receivers.
const assert = require('assert');
const path = require('path');
const requests = [];
let ticks = 0;
let nextObject = 1;
globalThis.__autojsHost = function (payload) {
  assert.strictEqual(typeof payload, 'string');
  const request = JSON.parse(payload);
  requests.push(request);
  let value = null;
  if (request.kind === 'java') {
    if (request.op === 'member') value = { kind:
      ['elapsedRealtime', 'max', 'append', 'self', 'fail'].includes(request.name) ? 'method' : 'unknown' };
    if (request.op === 'newRef') value = { $obj: nextObject++, $class: request.cls };
    if (request.op === 'static' || request.op === 'call' || request.op === 'uiCall') {
      if (request.method === 'elapsedRealtime') value = ++ticks;
      else if (request.method === 'fail') return JSON.stringify({ ok: false, error: 'Java fixture failure' });
      else if (request.method === 'self') value = { $obj: request.obj, $class: 'java.lang.StringBuilder' };
      else value = JSON.stringify({ receiver: request.cls || request.obj, args: request.args });
    }
  }
  return JSON.stringify({ ok: true, value: value });
};
require(path.resolve(__dirname, '../../main/assets/node24/prelude.js'));

const clock = android.os.SystemClock;
assert.strictEqual(clock.elapsedRealtime(), 1);
assert.strictEqual(clock.elapsedRealtime(), 2, 'a repeated method access must call Java again');
const elapsed = clock.elapsedRealtime;
assert.strictEqual(elapsed(), 3, 'a saved callable must call Java again');
const Math = java.lang.Math;
const ints = Math.max(7, 3);
assert.deepStrictEqual(JSON.parse(ints), { receiver: 'java.lang.Math', args: [7, 3] });
assert.deepStrictEqual(JSON.parse(Math.max(1.5, 2.75)).args, [1.5, 2.75]);
assert.deepStrictEqual(JSON.parse(Math.max(null, undefined)).args, [null, null]);
const a = new java.lang.StringBuilder();
const b = new java.lang.StringBuilder();
assert.notStrictEqual(a.__handle, b.__handle);
assert.strictEqual(JSON.parse(a.append('中文🙂')).receiver, a.__handle);
assert.strictEqual(JSON.parse(b.append('other')).receiver, b.__handle);
assert.deepStrictEqual(JSON.parse(a.append([1, null, ['x']], b)).args,
  [{ $arr: [1, null, { $arr: ['x'] }] }, { $obj: b.__handle }]);
assert.strictEqual(a.self().__handle, a.__handle, 'object results must still become proxies');
assert.throws(() => a.fail(), /Java fixture failure/);
assert.deepStrictEqual(JSON.parse(a.append({ nested: {value: b}, text: 'map' })).args,
  [{$map: {nested: {$map: {value: {$obj: b.__handle}}}, text: 'map'}}]);
assert.throws(() => a.append(new Date()), /不能把普通 JS 对象传给 Java/);
assert.throws(() => a.append(function () {}), /暂不支持把 JS 函数传给 Java/);
assert.strictEqual(JSON.parse(a.append('after-error')).args[0], 'after-error');
const clockCalls = requests.filter(r => r.kind === 'java' && r.op === 'static' && r.method === 'elapsedRealtime');
assert.strictEqual(clockCalls.length, 3);
clockCalls.forEach(r => assert.deepStrictEqual(r.args, []));
assert.deepStrictEqual(JSON.parse(a.append.invoke(b, ['current receiver'], 'current')),
  { receiver: b.__handle, args: ['current receiver'] });
assert.deepStrictEqual(JSON.parse(a.append.invoke(a, ['main thread'], 'ui-sync')),
  { receiver: a.__handle, args: ['main thread'] });
assert.strictEqual(requests.at(-1).op, 'uiCall');
assert.strictEqual(requests.filter(r => r.kind === 'java' && r.op === 'member'
  && r.cls === 'android.os.SystemClock' && r.name === 'elapsedRealtime').length, 1);
console.log('PASS Node24 Java call contract: fresh calls/results, numeric/null/array/object args, receivers and errors');
