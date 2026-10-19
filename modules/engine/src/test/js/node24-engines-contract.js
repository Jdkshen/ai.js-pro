// Focused Node24 prelude contract: no Android, no native bridge, fake JSON host.
// Run: node modules/engine/src/test/js/node24-engines-contract.js
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const preludePath = path.join(__dirname, '..', '..', '..', 'src', 'main',
  'assets', 'node24', 'prelude.js');
const hostEvents = [];
const calls = [];
let emitCount = 0;
let stopCount = 0;
let execCount = 0;

function fakeHost(requestJson) {
  const request = JSON.parse(requestJson);
  if (request.kind === 'poll') {
    return JSON.stringify({ ok: true, value: { $events: hostEvents.splice(0), $more: false } });
  }
  if (request.kind !== 'engines') {
    return JSON.stringify({ ok: false, error: 'unexpected kind: ' + request.kind });
  }
  const action = request.action;
  const args = request.args || {};
  calls.push(request);
  switch (action) {
    case 'my':
      return JSON.stringify({ ok: true, value: {
        id: 42, engine: 'Node24JavaScriptEngine',
        execArgv: { serverEngineId: 7 }
      } });
    case 'list':
      return JSON.stringify({ ok: true, value: [
        { id: 42, engine: 'Node24JavaScriptEngine', execArgv: { serverEngineId: 7 } },
        { id: 7, engine: 'RhinoJavaScriptEngine', execArgv: { serverEngineId: 42 } }
      ] });
    case 'exec':
      execCount++;
      assert.strictEqual(args.path.endsWith('悬浮窗.js'), true, 'path should be absolute');
      assert.deepStrictEqual(args.arguments, { serverEngineId: 42, title: '计时器' });
      return JSON.stringify({ ok: true, value: { token: args.token, executionId: 101 } });
    case 'execution':
      return JSON.stringify({ ok: true, value: {
        token: args.token, executionId: 101, engineId: 7, done: false
      } });
    case 'emit':
      emitCount++;
      assert.ok(args.target === 7 || args.target === 42, 'emit target must be a real engine id');
      return JSON.stringify({ ok: true, value: true });
    case 'stop':
      stopCount++;
      return JSON.stringify({ ok: true, value: true });
    case 'stopAll':
      return JSON.stringify({ ok: true, value: 2 });
    default:
      return JSON.stringify({ ok: false, error: 'unknown action: ' + action });
  }
}

globalThis.__ajsProjectDir = process.cwd();
globalThis.__ajsScriptFile = path.join(process.cwd(), 'main.js');
globalThis.__autojsHost = fakeHost;
// Node24Prelude wraps the whole script in a CommonJS function, so `require` exists there.
// Indirect eval runs at global scope, so expose the host module's require for this contract.
globalThis.require = require;
// The prelude is an IIFE that installs globals. Evaluate exactly as Node24Service does.
// eslint-disable-next-line no-eval
(0, eval)(fs.readFileSync(preludePath, 'utf8'));

const engines = globalThis.__ajsBuiltinModules.engines;
assert.strictEqual(typeof engines.myEngine, 'function');
assert.strictEqual(typeof engines.execScriptFile, 'function');
assert.strictEqual(typeof engines.getRunningEngines, 'function');
assert.strictEqual(typeof engines.all, 'function');

(async function main() {
  const my = engines.myEngine();
  assert.strictEqual(my.id, 42);
  assert.deepStrictEqual(my.execArgv, { serverEngineId: 7 });
  assert.strictEqual(typeof my.on, 'function');
  assert.strictEqual(typeof my.emit, 'function');
  assert.strictEqual(typeof my.forceStop, 'function');

  const all = engines.getRunningEngines();
  assert.deepStrictEqual(all.map(e => e.id), [42, 7]);
  assert.strictEqual(engines.all().find(e => e.id === 7).engine, 'RhinoJavaScriptEngine');

  let parentEvent = null;
  // Rhino→Node 的 command 可能比脚本的 on() 早到（冷启动），必须缓冲补发。
  hostEvents.push({ type: '__ajs_engine_event', payload: {
    engineId: 42, event: 'exit_button_click', args: [{ source: 'rhino' }]
  } });
  globalThis.__autojsPoll();
  my.on('exit_button_click', (value) => { parentEvent = value; });
  assert.deepStrictEqual(parentEvent, { source: 'rhino' });

  const execution = engines.execScriptFile('./悬浮窗.js', {
    arguments: { serverEngineId: engines.myEngine().id, title: '计时器' }
  });
  // start 可能比 JS 的 .on('start') 先到：prelude 必须缓冲，不能假成功吞掉。
  hostEvents.push({ type: '__ajs_engine_event', payload: {
    token: execution.token, engineId: 7, event: 'start', args: []
  } });
  globalThis.__autojsPoll();
  let started = false;
  let succeeded = false;
  execution.on('start', () => { started = true; })
    .on('success', () => { succeeded = true; });
  assert.strictEqual(started, true, 'buffered start event must dispatch when listener attaches');
  assert.strictEqual(execution.engineOrNull && execution.engineOrNull.id, 7,
    'engineOrNull must expose the real Rhino id');

  const child = await execution.engine();
  assert.strictEqual(child.id, 7);
  assert.strictEqual(child.emit('update', { content: '1' }), true);
  assert.strictEqual(emitCount, 1, 'child.emit must hit the host bridge');
  assert.strictEqual(child.forceStop(), true);
  assert.strictEqual(stopCount, 1, 'forceStop must hit the host bridge');

  hostEvents.push({ type: '__ajs_engine_event', payload: {
    token: execution.token, engineId: 7, event: 'success', args: []
  } });
  globalThis.__autojsPoll();
  assert.strictEqual(succeeded, true, 'success event must dispatch');
  assert.strictEqual(execCount, 1);
  assert.ok(calls.some(c => c.action === 'list'));

  // The sample's exact chain: myEngine().on(...) + execution.on(...).on(...) + engine().
  console.log('NODE24_ENGINES_CONTRACT_OK');
})().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exitCode = 1;
});
