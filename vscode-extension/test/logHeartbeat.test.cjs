'use strict';

const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const { buildSync } = require('esbuild');

const filename = path.join(__dirname, '../src/logHeartbeat.ts');
const bundled = buildSync({
  entryPoints: [filename], bundle: true, platform: 'node', format: 'cjs', write: false,
}).outputFiles[0].text;

function loadHeartbeat() {
  const module = { exports: {} };
  vm.runInNewContext(bundled, {
    module,
    exports: module.exports,
    require: createRequire(filename),
    Date,
    Math,
    console,
  }, { filename });
  return module.exports.LogHeartbeat;
}

const LogHeartbeat = loadHeartbeat();

const START = Date.UTC(2026, 10, 7, 12, 0, 0);

function harness(intervalSeconds = 30) {
  let now = START;
  const lines = [];
  const heartbeat = new LogHeartbeat({
    intervalSeconds,
    now: () => now,
    output: (line) => lines.push(line),
  });
  return {
    heartbeat,
    lines,
    at(seconds) { now = START + seconds * 1000; },
    clear() { lines.length = 0; },
  };
}

test('does not emit while logs keep flowing within the threshold', () => {
  const h = harness();
  h.heartbeat.connect('phone-a', 'Redmi M2012K11AC');
  h.clear();
  h.heartbeat.onLog('phone-a', 'Redmi M2012K11AC', START);
  h.at(10);
  h.heartbeat.tick();
  assert.deepEqual(h.lines, []);
});

test('emits a heartbeat after a full silent interval', () => {
  const h = harness();
  h.heartbeat.connect('phone-a', 'Redmi M2012K11AC');
  h.clear();
  h.heartbeat.onLog('phone-a', 'Redmi M2012K11AC', START);
  h.heartbeat.onLog('phone-a', 'Redmi M2012K11AC', START);
  h.at(30);
  h.heartbeat.tick();
  assert.equal(h.lines.length, 1);
  assert.match(h.lines[0], /Redmi M2012K11AC/);
  assert.match(h.lines[0], /链路正常/);
  assert.match(h.lines[0], /已收 2 行/);
  assert.match(h.lines[0], /脚本静默 30s/);
});

test('emits recovery after at least twice the silent threshold', () => {
  const h = harness();
  h.heartbeat.connect('phone-a', 'Redmi M2012K11AC');
  h.clear();
  h.heartbeat.onLog('phone-a', 'Redmi M2012K11AC', START);
  h.at(95);
  h.heartbeat.onLog('phone-a', 'Redmi M2012K11AC');
  assert.deepEqual(h.lines, ['[Redmi M2012K11AC] ▶ 日志恢复（静默 95s）']);
});

test('intervalSeconds=0 disables startup, heartbeat and recovery output', () => {
  const h = harness(0);
  h.heartbeat.connect('phone-a', 'Phone A');
  assert.deepEqual(h.lines, []);
  h.heartbeat.onLog('phone-a', 'Phone A', START);
  h.at(300);
  h.heartbeat.tick();
  h.heartbeat.onLog('phone-a', 'Phone A');
  assert.deepEqual(h.lines, []);
  assert.equal(h.heartbeat.enabled, false);
  assert.equal(h.heartbeat.status().length, 1);
});

test('multiple connections keep independent counters and silence', () => {
  const h = harness();
  h.heartbeat.connect('phone-a', 'Phone A');
  h.heartbeat.connect('phone-b', 'Phone B');
  h.clear();
  h.at(10);
  h.heartbeat.onLog('phone-a', 'Phone A');
  h.at(30);
  h.heartbeat.tick();
  assert.equal(h.lines.length, 1);
  assert.match(h.lines[0], /Phone B/);
  assert.doesNotMatch(h.lines[0], /Phone A/);
  assert.equal(h.heartbeat.status().find((item) => item.key === 'phone-a').lines, 1);
  assert.equal(h.heartbeat.status().find((item) => item.key === 'phone-b').lines, 0);
});

test('each tick emits one heartbeat while the connection remains silent', () => {
  const h = harness();
  h.heartbeat.connect('phone-a', 'Phone A');
  h.clear();
  h.at(30);
  h.heartbeat.tick();
  h.at(60);
  h.heartbeat.tick();
  assert.equal(h.lines.length, 2);
  assert.match(h.lines[1], /静默 60s/);
});

test('statusLines includes received count, recent log and silence', () => {
  const h = harness();
  h.heartbeat.connect('phone-a', 'Phone A');
  h.heartbeat.onLog('phone-a', 'Phone A', START);
  h.at(45);
  const lines = h.heartbeat.statusLines();
  assert.equal(lines.length, 1);
  assert.match(lines[0], /已收 1 行/);
  assert.match(lines[0], /最近日志/);
  assert.match(lines[0], /静默 45s/);
});
