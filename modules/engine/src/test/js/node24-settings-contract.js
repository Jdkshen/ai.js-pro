'use strict';
const assert = require('assert');
const path = require('path');
const values = {};
let failure;
const requests = [];
globalThis.__autojsHostRaw = raw => {
  const request = JSON.parse(raw);
  requests.push(request);
  if (failure) return JSON.stringify({ok: false, error: failure});
  assert.strictEqual(request.kind, 'ui');
  assert.strictEqual(request.op, 'settings');
  assert.strictEqual(request.domain, 'settings');
  if (request.action === 'set') values[request.name] = request.value;
  return JSON.stringify({ok: true, value: values[request.name] === true});
};
require(path.resolve(__dirname, '../../main/assets/node24/prelude.js'));
const settings = $autojs.settings;
const names = ['stableMode', 'enableAccessibilityServiceByRoot', 'stopAllOnVolumeUp', 'noConsoleOnLaunch', 'foregroundService'];
assert.deepStrictEqual(Object.keys(settings), names);
for (const name of names) {
  assert.strictEqual(settings[name].value, false);
  settings[name].value = true;
  assert.strictEqual(settings[name].value, true);
  settings[name].value = false;
  assert.strictEqual(settings[name].value, false);
  assert.throws(() => {settings[name].value = 1;}, /boolean/);
}
failure = 'main process rejected setting';
assert.throws(() => settings.stableMode.value, /main process rejected/);
assert.throws(() => {settings.stableMode.value = true;}, /main process rejected/);
assert.strictEqual(settings.unknownSetting, undefined);
assert(!requests.some(request => request.kind === 'storage'));
console.log('PASS settings: five real host settings, synchronous read/write, strict boolean and propagated host errors');
