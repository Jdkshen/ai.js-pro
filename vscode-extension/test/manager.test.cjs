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
    createServer(callback) {
      accept = callback;
      const server = new EventEmitter();
      server.listen = (_, ready) => ready();
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
