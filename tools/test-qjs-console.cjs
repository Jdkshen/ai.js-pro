const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../modules/engine/src/main/assets/modules/qjs-console-compat.js'), 'utf8');

test('QuickJS console.assert preserves Assert level and throwable assertion semantics', () => {
  const lines = [];
  const log = function () {};
  const context = vm.createContext({ console: Object.freeze({ log }), __aiNativeLog: (level, text) => lines.push([level, text]) });
  vm.runInContext(source, context);
  assert.equal(context.console.log, log);
  assert.equal(context.console.debug, log);
  assert(Object.isFrozen(context.console));
  context.console.assert(true, 'unused');
  assert.equal(lines.length, 0);
  assert.throws(() => context.console.assert(false, '中文😀', { code: 42 }), e => e.name === 'AssertionError');
  assert.deepEqual(lines, [[7, '中文😀 {"code":42}']]);
  assert.throws(() => context.console.assert(false), /Assertion failed/);
});

test('reloading keeps an existing assert implementation', () => {
  const existing = () => 'existing';
  const context = vm.createContext({ console: Object.freeze({ log() {}, assert: existing }) });
  vm.runInContext(source, context);
  assert.equal(context.console.assert, existing);
});
