'use strict';
const assert = require('assert');
const path = require('path');
const jsonCalls = [], fastCalls = [];
let next = 1, ticks = 0, fields = 0, secondType = false;
globalThis.__autojsHost = payload => {
  const r = JSON.parse(payload); jsonCalls.push(r);
  let value = null;
  if (r.kind === 'java') {
    if (r.op === 'member') value = r.name === 'mutable' ? {kind:'field',value:'ignored snapshot'} :
      (['append','max'].includes(r.name) ? {kind: 'method', $method: 19} : {kind:'unknown'});
    else if (r.op === 'field') value = ++fields;
    else if (r.op === 'newRef') value = {$obj: next++, $class: r.cls, $type: secondType ? 78 : 77};
    else if (r.op === 'call' || r.op === 'static' || r.op === 'uiCall') value = 'fallback';
  }
  return JSON.stringify({ok: true, value});
};
globalThis.__ajsJavaFastCall = (member, receiver, args) => {
  fastCalls.push({member, receiver, args});
  assert.strictEqual(Number.isInteger(receiver),true);
  if (args[0] === 'fail') throw new Error('fixture failure');
  if (args[0] === 'self') return {$obj: receiver, $class: 'java.lang.StringBuilder'};
  return ++ticks;
};
require(path.resolve(__dirname, '../../main/assets/node24/prelude.js'));
const a = new java.lang.StringBuilder(), b = new java.lang.StringBuilder();
assert.strictEqual(a.append('中文🙂', 1.5, null, undefined, NaN, Infinity, true, b), 1);
assert.deepStrictEqual(fastCalls.at(-1), {member:19, receiver:a.__handle,
  args:['中文🙂',1.5,null,null,null,null,true,{$obj:b.__handle}]});
assert.strictEqual(a.append('self').__handle, a.__handle);
assert.strictEqual(java.lang.Math.max(3, 5), 2);
assert.strictEqual(fastCalls.at(-1).receiver, 0);
assert.strictEqual(a.append([1, 2]), 'fallback');
assert.strictEqual(a.append({x: b}), 'fallback');
assert.strictEqual(a.append(java.lang.String), 'fallback');
assert.strictEqual(a.append(1000000000000000100), 'fallback');
const n = fastCalls.length;
assert.strictEqual(a.append.invoke(b, ['receiver'], 'current'), 'fallback');
assert.strictEqual(a.append.invoke(a, ['ui'], 'ui-sync'), 'fallback');
assert.strictEqual(fastCalls.length, n);
assert.throws(() => a.append('fail'), /fixture failure/);
assert.strictEqual(a.append('fresh'), 3);
assert.strictEqual(b.append('different receiver'), 4);
assert.strictEqual(fastCalls.at(-1).receiver, b.__handle);
assert.strictEqual(jsonCalls.filter(r=>r.op==='member'&&r.name==='append').length,1,
  'instances of one Class reuse member metadata without sharing receiver closures');
assert.strictEqual(a.mutable,1);assert.strictEqual(b.mutable,2);assert.strictEqual(a.mutable,3);
assert.strictEqual(jsonCalls.filter(r=>r.op==='member'&&r.name==='mutable').length,1);
secondType=true;
const otherLoader=new java.lang.StringBuilder();otherLoader.append('another Class identity');
assert.strictEqual(jsonCalls.filter(r=>r.op==='member'&&r.name==='append').length,2,
  'same class name with a different identity must discover its own members');
assert.ok(jsonCalls.some(r => r.op === 'uiCall'));
console.log('PASS Node24 typed Java: scalars/references/results/errors/fallback/modes');
