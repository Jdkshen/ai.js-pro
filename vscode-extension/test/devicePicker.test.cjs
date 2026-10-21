'use strict';
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { EventEmitter } = require('node:events');
const { buildSync } = require('esbuild');
const { createRequire } = require('node:module');
const path = require('node:path');
const vm = require('node:vm');
const filename = path.join(__dirname, '../src/devicePicker.ts');
const code = buildSync({ entryPoints: [filename], bundle: true, platform: 'node', format: 'cjs', external: ['vscode'], write: false }).outputFiles[0].text;
const originalRequire = createRequire(filename);
const device = (name, host) => ({ deviceName: name, deviceAddress: host, label: host, key: name, attached: true, isClosed: () => false });
function harness({ online = [], saved = [], usbList = [], choices = [], usbError } = {}) {
  const captured = [], warnings = [], errors = [], commands = [];
  let connectedNew = 0, scans = 0;
  const manager = new EventEmitter();
  manager.all = online;
  manager.activeConnection = online[0];
  manager.connectionAt = (host) => manager.all.find((conn) => conn.deviceAddress === host);
  manager.connectTo = async (host) => {
    const conn = device(host, host);
    manager.all.push(conn);
    manager.emit('changed');
  };
  manager.setActive = (conn) => { manager.activeConnection = conn; };
  const vscode = {
    workspace: { getConfiguration: () => ({ get: (key, fallback) => key === 'devices' ? saved : fallback }) },
    ProgressLocation: { Notification: 15 },
    commands: { executeCommand: async (...args) => commands.push(args) },
    window: {
      showQuickPick: async (promise, options) => {
        const items = await promise; captured.push({ items, options });
        const choice = choices.shift();
        return choice ? items.find(choice) : undefined;
      },
      withProgress: async (_, run) => run(),
      setStatusBarMessage: () => {},
      showWarningMessage: async (text) => warnings.push(text),
      showErrorMessage: async (text) => errors.push(text),
    },
  };
  const usb = { addressFor: () => undefined, list: async () => { scans++; if (usbError) throw new Error(usbError); return usbList; }, connectAddress: async (serial) => `127.0.0.1:${serial === 'one' ? 19001 : 19002}` };
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, Buffer, process, console, setTimeout, clearTimeout,
    require: (id) => id === 'vscode' ? vscode : originalRequire(id) }, { filename });
  return { manager, captured, warnings, errors, commands, scans: () => scans, connectedNew: () => connectedNew,
    run: () => module.exports.selectDevice(manager, usb, async () => { connectedNew++; }) };
}

test('显式选择只有一台在线手机时也必须弹出列表', async () => {
  const conn = device('K40', '192.168.10.5:9317');
  const h = harness({ online: [conn], choices: [(item) => item.conn === conn] });
  await h.run();
  assert.equal(h.captured.length, 1);
  assert.equal(h.captured[0].options.title, '选择 AI.js Pro 设备');
  assert.equal(h.manager.activeConnection, conn);
});

test('零在线设备展示两台 USB 手机、记住地址及手动连接入口', async () => {
  const h = harness({ saved: ['desktop-phone:9317'], usbList: [
    { serial: 'one', name: 'K40', state: 'device' }, { serial: 'two', name: 'MI 8', state: 'device' },
  ] });
  await h.run();
  const rows = h.captured[0].items;
  assert.equal(rows.filter((item) => item.usb).length, 2);
  assert.ok(rows.some((item) => item.host === 'desktop-phone:9317'));
  assert.ok(rows.some((item) => item.action === 'connect'));
  assert.equal(h.connectedNew(), 0, '取消不应再跳出连接提示');
});

test('选 USB 后连接该手机并设为当前设备', async () => {
  const h = harness({ usbList: [{ serial: 'one', name: 'K40', state: 'device' }], choices: [(item) => item.usb?.serial === 'one'] });
  await h.run();
  assert.equal(h.manager.activeConnection.deviceAddress, '127.0.0.1:19001');
});

test('选离线的记住地址会先连接，握手后设为当前设备', async () => {
  const h = harness({ saved: ['phone.local:9317'], choices: [(item) => item.host === 'phone.local:9317'] });
  await h.run();
  assert.equal(h.manager.activeConnection.deviceAddress, 'phone.local:9317');
});

test('两台在线时可明确切换设备，取消不会切换', async () => {
  const one = device('K40', 'phone1:9317'), two = device('MI 8', 'phone2:9317');
  const h = harness({ online: [one, two], choices: [(item) => item.conn === two] });
  await h.run(); await h.run();
  assert.equal(h.manager.activeConnection, two);
  assert.equal(h.captured.length, 2);
});

test('USB 未授权给出手机授权提示，不连接，仍可退出列表', async () => {
  const h = harness({ usbList: [{ serial: 'one', name: 'K40', state: 'unauthorized' }], choices: [(item) => !!item.usb] });
  await h.run();
  assert.equal(h.manager.all.length, 0);
  assert.match(h.warnings[0], /USB 调试授权/);
});

test('找不到 adb 时仍可选择在线设备', async () => {
  const conn = device('K40', 'phone:9317');
  const h = harness({ online: [conn], usbError: '找不到 adb', choices: [(item) => item.conn === conn] });
  await h.run();
  assert.equal(h.manager.activeConnection, conn);
  assert.ok(h.captured[0].items.some((item) => item.action === 'adb'));
});

test('刷新重新扫描，手动入口只触发一次地址连接', async () => {
  const h = harness({ choices: [(item) => item.action === 'refresh', (item) => item.action === 'connect'] });
  await h.run();
  assert.equal(h.scans(), 2);
  assert.equal(h.connectedNew(), 1);
});
