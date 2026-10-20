'use strict';
const assert = require('assert');
const path = require('path');
const calls = [];
let fieldReads = 0;
const intArray = [0, 0, 0, 0];
globalThis.__autojsHost = payload => {
  const r = JSON.parse(payload);
  calls.push(r);
  let value = null;
  if (r.kind === 'java') {
    if (r.op === 'class' && r.name === 'android.os.Build$Missing') {
      return JSON.stringify({ok: false, error: 'missing class'});
    }
    if (r.op === 'member') {
      value = {kind: ['TYPE', 'changing'].includes(r.name) ? 'field'
        : ['newInstance', 'setInt', 'getInt', 'append', 'getClass', 'isPrimitive', 'getComponentType',
          'getMethod', 'isAssignableFrom'].includes(r.name) ? 'method' : 'unknown'};
      // Class instance methods must be called on the Class object, not on represented-class statics.
      if (r.cls && ['isPrimitive', 'getComponentType', 'getMethod', 'isAssignableFrom'].includes(r.name)) {
        value = {kind: 'unknown'};
      }
    }
    if (r.op === 'field') value = r.name === 'TYPE' ? {$class: 'int'} : ++fieldReads;
    if (r.op === 'classObject') value = {$obj: 90, $class: 'java.lang.Class'};
    if (r.op === 'newRef') value = {$obj: 20, $class: r.cls};
    if (r.op === 'static' || r.op === 'call') {
      if (r.method === 'newInstance') value = {$obj: 30, $class: '[I', $length: 4};
      if (r.method === 'setInt') intArray[r.args[1]] = r.args[2];
      if (r.method === 'getInt') value = intArray[r.args[1]];
      if (r.method === 'getClass') value = {$class: '[I'};
      if (r.method === 'getComponentType') value = {$class: 'int'};
      if (r.method === 'isPrimitive' || r.method === 'isAssignableFrom') value = true;
      if (r.method === 'getMethod') value = {$obj: 40, $class: 'java.lang.reflect.Method'};
    }
    if (r.op === 'arrayGet') value = intArray[r.index];
    if (r.op === 'arraySet') intArray[r.index] = r.value;
  }
  return JSON.stringify({ok: true, value});
};
require(path.resolve(__dirname, '../../main/assets/node24/prelude.js'));
const A = java.lang.reflect.Array;
const intType = java.lang.Integer.TYPE;
assert.strictEqual(intType.getName(), 'int');
assert.strictEqual(intType.isPrimitive(), true);
const array = A.newInstance(intType, 4);
assert.strictEqual(array.length, 4);
assert.strictEqual(array.getClass().getName(), '[I');
assert.strictEqual(array.getClass().getComponentType().getName(), 'int');
array[0] = 23;
assert.strictEqual(A.getInt(array, 0), 23, 'index writes must update the same Java array');
A.setInt(array, 1, 42);
assert.strictEqual(array[1], 42, 'Java mutations must be visible to JS');
assert.deepStrictEqual(array.map(x => x + 1), [24, 43, 1, 1]);
assert.throws(() => {array[4] = 1;}, /长度固定/);
assert.strictEqual(java.lang.String.isAssignableFrom(java.lang.String.class), true);
java.lang.String.getMethod('valueOf', [intType]);
assert.strictEqual(android.view.View.OnClickListener.getName(), 'android.view.View$OnClickListener');
assert.strictEqual(android.os.Build.VERSION.getName(), 'android.os.Build$VERSION');
assert.strictEqual(java.lang.Integer.changing, 1);
assert.strictEqual(java.lang.Integer.changing, 2, 'cached field metadata must not freeze its value');
assert.strictEqual($java.boxInt(18).__class, 'java.lang.Integer');
assert(calls.some(r => r.method === 'newInstance' && r.args[0].$class === 'int'));
assert(calls.some(r => r.method === 'getMethod' && r.obj === 90 && r.args[1].$arr[0].$class === 'int'));
assert(calls.some(r => r.method === 'getInt' && r.args[0].$obj === 30));
console.log('PASS Node24 Java reflection: Class parameters, primitive TYPE, live typed arrays, nested classes, Class methods, mutable fields and boxed numbers');
