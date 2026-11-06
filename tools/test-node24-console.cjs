const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../modules/engine/src/main/assets/node24/modules/console.js'), 'utf8');

function setup() {
  const lines = [];
  const Console = function Console() {};
  const output = { log: (...args) => lines.push(require('node:util').format(...args)), Console };
  const context = vm.createContext({ console: output, require });
  vm.runInContext(source, context);
  return { lines, output, context, Console };
}

test('levels retain Node formatting and Unicode without changing Console constructor', () => {
  const { lines, output, Console } = setup();
  for (const [method, level] of Object.entries({ verbose: 'V', debug: 'D', log: 'D', info: 'I', warn: 'W', error: 'E' })) {
    output[method]('中文😀 %d %s', 12, 'value');
    assert.equal(lines.pop(), '\x1eAJSLOG1:' + level + ':中文😀 12 value');
  }
  assert.equal(output.Console, Console);
});

test('every line in a multiline error carries its error level', () => {
  const { lines, output } = setup();
  output.error('first\nsecond\r\nthird');
  assert.deepEqual(lines, ['first', 'second', 'third'].map(line => '\x1eAJSLOG1:E:' + line));
});

test('console.assert keeps Node nonthrowing semantics and distinguishes Assert', () => {
  const { lines, output } = setup();
  output.assert(true, 'not shown');
  assert.equal(lines.length, 0);
  assert.doesNotThrow(() => output.assert(false, 'value=%d', 42));
  assert.deepEqual(lines, ['\x1eAJSLOG1:A:Assertion failed: value=42']);
});

test('installing twice does not wrap frames twice, and empty lines survive', () => {
  const { lines, output, context } = setup();
  vm.runInContext(source, context);
  output.log();
  output.info('tail\n');
  assert.deepEqual(lines, ['\x1eAJSLOG1:D:', '\x1eAJSLOG1:I:tail', '\x1eAJSLOG1:I:']);
});
