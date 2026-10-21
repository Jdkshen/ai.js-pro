'use strict';

const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { createRequire } = require('node:module');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const { buildSync } = require('esbuild');

const filename = path.join(__dirname, '../src/manager.ts');
const bundled = buildSync({
  entryPoints: [filename], bundle: true, platform: 'node', format: 'cjs',
  external: ['vscode'], write: false,
}).outputFiles[0].text;
const originalRequire = createRequire(filename);
const nodeNet = originalRequire('net');
const flush = async () => { await new Promise(setImmediate); };

class Socket extends EventEmitter {
  constructor(ip) { super(); this.remoteAddress = ip; this.remotePort = 42000; this.destroyed = false; }
  setNoDelay() {}
  setTimeout(ms, callback) { this.timeoutMs = ms; this.timeoutCallback = callback; }
  write() { return true; }
  destroy() {
    if (this.destroyed) return;
    this.destroyed = true;
    this.emit('close');
  }
}

function harness(initial = {}) {
  const scopes = {
    1: { devices: [], ignoredDevices: [], ...initial.global },
    2: { ...initial.workspace },
    3: { ...initial.folder },
  };
  const timers = new Set();
  const sockets = [];
  const log = [];
  let accept;
  let beforeUpdate;
  let listenPort;
  const configuration = {
    get(key, fallback) {
      for (const scope of [3, 2, 1]) if (scopes[scope][key] !== undefined) return structuredClone(scopes[scope][key]);
      return fallback;
    },
    inspect(key) {
      return {
        globalValue: structuredClone(scopes[1][key]),
        workspaceValue: structuredClone(scopes[2][key]),
        workspaceFolderValue: structuredClone(scopes[3][key]),
      };
    },
    async update(key, value, target) {
      await beforeUpdate?.(key, value, target);
      scopes[target][key] = structuredClone(value);
    },
  };
  const vscode = {
    ConfigurationTarget: { Global: 1, Workspace: 2, WorkspaceFolder: 3 },
    workspace: { getConfiguration: () => configuration },
  };
  const net = {
    isIP: nodeNet.isIP,
    createServer(callback) {
      accept = callback;
      const server = new EventEmitter();
      server.listen = (port, ready) => { listenPort = port; ready(); };
      server.close = () => {};
      return server;
    },
    createConnection({ host, port }) {
      const socket = new Socket(host);
      socket.host = host;
      socket.port = port;
      sockets.push(socket);
      return socket;
    },
  };
  const module = { exports: {} };
  vm.runInNewContext(bundled, {
    module, exports: module.exports, Buffer, console,
    require: (name) => name === 'vscode' ? vscode : name === 'net' ? net : originalRequire(name),
    setTimeout: (callback, ms) => {
      const timer = { callback, ms };
      timers.add(timer);
      return timer;
    },
    clearTimeout: (timer) => timers.delete(timer),
  }, { filename });
  const manager = new module.exports.DeviceManager((line) => log.push(line));
  return {
    manager, scopes, timers, sockets, log,
    listenPort: () => listenPort,
    accept: (socket) => accept(socket),
    setBeforeUpdate: (callback) => { beforeUpdate = callback; },
    advance() {
      const pending = [...timers];
      for (const timer of pending) { timers.delete(timer); timer.callback(); }
    },
  };
}

function hello(connection) {
  connection.attached = true;
  connection.emit('connected');
}

test('删除连接中的旧地址后停止重试，其它设备保留', async () => {
  const h = harness({ global: { devices: ['192.168.31.59', '192.168.10.5'] } });
  h.manager.startListening();
  const [old, current] = h.sockets;
  await h.manager.forgetDevices(['192.168.31.59']);
  assert.equal(old.destroyed, true);
  assert.equal(current.destroyed, false);
  assert.deepEqual(h.scopes[1].devices, ['192.168.10.5']);
  assert.deepEqual(h.scopes[1].ignoredDevices, ['192.168.31.59']);
  for (let i = 0; i < 4; i++) h.advance();
  assert.equal(h.sockets.filter((socket) => socket.host === '192.168.31.59').length, 1);
  h.manager.dispose();
});

test('删除最后一个地址会取消重试定时器', async () => {
  const h = harness({ global: { devices: ['192.168.31.59'] } });
  h.manager.startListening();
  await h.manager.forgetDevices(['192.168.31.59']);
  assert.equal(h.timers.size, 0);
  assert.equal(h.manager.all.length, 0);
  h.manager.dispose();
});

test('用户、工作区、文件夹及默认端口别名都不会带回已删地址', async () => {
  const h = harness({
    global: { devices: ['192.168.31.59', '192.168.10.5'] },
    workspace: { devices: ['192.168.31.59:9317', '192.168.10.6'] },
    folder: { devices: ['192.168.31.59', '192.168.10.8'] },
  });
  await h.manager.forgetDevices(['192.168.31.59:9317']);
  assert.deepEqual(h.scopes[1].devices, ['192.168.10.5']);
  assert.deepEqual(h.scopes[2].devices, ['192.168.10.6']);
  assert.deepEqual(h.scopes[3].devices, ['192.168.10.8']);
  h.manager.dispose();
});

test('删除后的迟到握手和手机主动回连不能重新记住地址', async () => {
  const h = harness({ global: { devices: ['192.168.31.59'] } });
  h.manager.startListening();
  const connecting = h.manager.all[0];
  await h.manager.forgetDevices(['192.168.31.59']);
  hello(connecting);
  const inbound = new Socket('::ffff:192.168.31.59');
  h.accept(inbound);
  await flush();
  assert.equal(inbound.destroyed, true);
  assert.deepEqual(h.scopes[1].devices, []);
  assert.equal(h.manager.all.length, 0);
  h.manager.dispose();
});

test('重启后继续忽略已删设备，手动连接可重新启用并保存自定义端口', async () => {
  const h = harness({ global: { ignoredDevices: ['192.168.31.59'] } });
  h.manager.startListening();
  const inbound = new Socket('192.168.31.59');
  h.accept(inbound);
  assert.equal(inbound.destroyed, true);
  await h.manager.connectTo('192.168.31.59:9420');
  assert.equal(h.sockets[0].port, 9420);
  hello(h.manager.all[0]);
  await flush();
  assert.deepEqual(h.scopes[1].ignoredDevices, []);
  assert.deepEqual(h.scopes[1].devices, ['192.168.31.59:9420']);
  h.manager.dispose();
});

test('正在保存的 hello 不会覆盖随后删除的结果', async () => {
  const h = harness();
  let release;
  let entered;
  const started = new Promise((resolve) => { entered = resolve; });
  h.setBeforeUpdate(async (key) => {
    if (key !== 'devices') return;
    h.setBeforeUpdate(undefined);
    entered();
    await new Promise((resolve) => { release = resolve; });
  });
  await h.manager.connectTo('192.168.31.59');
  hello(h.manager.all[0]);
  await started;
  const deleting = h.manager.forgetDevices(['192.168.31.59']);
  release();
  await deleting;
  assert.deepEqual(h.scopes[1].devices, []);
  assert.deepEqual(h.scopes[1].ignoredDevices, ['192.168.31.59']);
  h.manager.dispose();
});

test('删除可以取消尚未完成配置保存的手动连接', async () => {
  const h = harness({ global: { ignoredDevices: ['192.168.31.59'] } });
  const connecting = h.manager.connectTo('192.168.31.59');
  const deleting = h.manager.forgetDevices(['192.168.31.59']);
  await Promise.all([connecting, deleting]);
  assert.equal(h.sockets.length, 0);
  assert.deepEqual(h.scopes[1].ignoredDevices, ['192.168.31.59']);
  h.manager.dispose();
});

test('USB 回环地址不进入记住的设备列表', async () => {
  const h = harness();
  await h.manager.connectTo('127.0.0.1:9317');
  hello(h.manager.all[0]);
  await flush();
  assert.deepEqual(h.scopes[1].devices, []);
  h.manager.dispose();
});

test('监听默认 8327，并支持自定义回连端口', () => {
  const defaults = harness();
  defaults.manager.startListening();
  assert.equal(defaults.listenPort(), 8327);
  assert.ok(defaults.log.some((line) => /正在监听 8327（默认/.test(line)));
  defaults.manager.dispose();

  const custom = harness({ global: { listenPort: 9427 } });
  custom.manager.startListening();
  assert.equal(custom.listenPort(), 9427);
  assert.ok(custom.log.some((line) => /正在监听 9427（自定义/.test(line)));
  custom.manager.dispose();
});

test('非法监听端口只记日志，不阻断手动连接/自动重连', () => {
  const h = harness({ global: { listenPort: 70000 } });
  h.manager.startListening();
  assert.equal(h.listenPort(), undefined);
  assert.ok(h.log.some((line) => /监听端口配置无效/.test(line)));
  h.manager.dispose();
});

test('IPv6 地址与端口解析：支持 [IPv6]:端口 和裸 IPv6', async () => {
  const bracketed = harness();
  await bracketed.manager.connectTo('[fe80::1]:9420');
  assert.equal(bracketed.sockets[0].host, 'fe80::1');
  assert.equal(bracketed.sockets[0].port, 9420);
  assert.equal(bracketed.manager.all[0].label, '[fe80::1]:9420');
  hello(bracketed.manager.all[0]);
  await flush();
  assert.deepEqual(bracketed.scopes[1].devices, ['[fe80::1]:9420']);
  bracketed.manager.dispose();

  const bare = harness();
  await bare.manager.connectTo('fe80::1');
  assert.equal(bare.sockets[0].host, 'fe80::1');
  assert.equal(bare.sockets[0].port, 9317, '裸 IPv6 的最后一个冒号段不是端口');
  assert.equal(bare.manager.all[0].label, '[fe80::1]:9317');
  hello(bare.manager.all[0]);
  await flush();
  assert.deepEqual(bare.scopes[1].devices, ['[fe80::1]:9317']);
  bare.manager.dispose();
});

test('IPv6 回环地址不进入记住的设备列表', async () => {
  const h = harness();
  await h.manager.connectTo('[::1]:9317');
  assert.equal(h.sockets[0].host, '::1');
  hello(h.manager.all[0]);
  await flush();
  assert.deepEqual(h.scopes[1].devices, []);
  h.manager.dispose();
});

test('无效端口/地址在拨号前拒绝，且不会解除已删除状态', async () => {
  const h = harness({ global: { ignoredDevices: ['192.168.31.59'] } });
  for (const host of ['192.168.31.59:0', '192.168.31.59:65536', '192.168.31.59:abc', '[::1', '']) {
    await assert.rejects(h.manager.connectTo(host), /(端口无效|设备地址|IPv6 地址)/, host);
  }
  assert.equal(h.sockets.length, 0);
  assert.deepEqual(h.scopes[1].ignoredDevices, ['192.168.31.59']);
  assert.equal(h.manager.all.length, 0);
  h.manager.dispose();
});

test('删除 IPv6 设备时，带端口与裸地址的写法一起清理', async () => {
  const h = harness({
    global: { devices: ['[fe80::1]:9317', 'fe80::1', '192.168.10.5'] },
  });
  await h.manager.forgetDevices(['[fe80::1]:9317']);
  assert.deepEqual(h.scopes[1].devices, ['192.168.10.5']);
  assert.deepEqual(h.scopes[1].ignoredDevices, ['fe80::1']);
  h.manager.dispose();
});

test('两台 USB 手机转发到同一回环地址的不同端口时都能连接', async () => {
  const h = harness();
  await h.manager.connectTo('127.0.0.1:19001');
  hello(h.manager.all[0]);
  await h.manager.connectTo('127.0.0.1:19002');
  assert.equal(h.manager.all.length, 2);
  hello(h.manager.all[1]);
  assert.equal(h.manager.connectionAt('127.0.0.1:19001'), h.manager.all[0]);
  assert.equal(h.manager.connectionAt('127.0.0.1:19002'), h.manager.all[1]);
  assert.deepEqual(h.scopes[1].devices, []);
  h.manager.dispose();
});

test('显式选择的设备供运行复用，不被另一台的握手覆盖', async () => {
  const h = harness();
  await h.manager.connectTo('127.0.0.1:19001');
  const one = h.manager.all[0]; one.hello = { device_id: 'one' }; hello(one);
  h.manager.setActive(one);
  await h.manager.connectTo('127.0.0.1:19002');
  const two = h.manager.all[1]; two.hello = { device_id: 'two' }; hello(two);
  assert.equal(h.manager.activeConnection, one);
  assert.equal(await h.manager.pickConnection(), one);
  one.close();
  assert.equal(h.manager.activeConnection, undefined, '所选设备掉线不能暗中切到另一台手机');
  assert.equal(await h.manager.pickConnection(), undefined);
  h.manager.setActive(two);
  assert.equal(await h.manager.pickConnection(), two);
  h.manager.dispose();
});
