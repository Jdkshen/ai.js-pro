'use strict';

const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { createRequire } = require('node:module');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const { buildSync } = require('esbuild');

const filename = path.join(__dirname, '../src/connection.ts');
const bundled = buildSync({
  entryPoints: [filename], bundle: true, platform: 'node', format: 'cjs', write: false,
}).outputFiles[0].text;
const originalRequire = createRequire(filename);

function loadDeviceConnection() {
  const module = { exports: {} };
  vm.runInNewContext(bundled, {
    module, exports: module.exports, Buffer, console, require: originalRequire,
    setTimeout, clearTimeout, setImmediate,
  }, { filename });
  return module.exports.DeviceConnection;
}

const DeviceConnection = loadDeviceConnection();

class Socket extends EventEmitter {
  constructor() {
    super();
    this.destroyed = false;
    this.writes = [];
    this.timeoutMs = 0;
    this.timeoutCallback = undefined;
    this.onWrite = undefined;
  }
  setNoDelay() {}
  setTimeout(ms, callback) { this.timeoutMs = ms; this.timeoutCallback = callback; }
  write(frame) {
    if (this.destroyed) throw new Error('write after destroy');
    const copy = Buffer.from(frame);
    this.writes.push(copy);
    this.onWrite?.(copy);
    return true;
  }
  destroy() {
    if (this.destroyed) return;
    this.destroyed = true;
    this.emit('close');
  }
}

function makeConnection() {
  const socket = new Socket();
  const log = [];
  const conn = new DeviceConnection(socket, '192.168.31.59:9317', (line) => log.push(line));
  return { conn, socket, log };
}

function encodeFrame(type, payload) {
  const frame = Buffer.allocUnsafe(8 + payload.length);
  frame.writeUInt32BE(payload.length, 0);
  frame.writeUInt32BE(type, 4);
  payload.copy(frame, 8);
  return frame;
}

function encodeJson(value) {
  return encodeFrame(1, Buffer.from(JSON.stringify(value), 'utf8'));
}

function parseFrame(frame) {
  const length = frame.readUInt32BE(0);
  const type = frame.readUInt32BE(4);
  return { type, value: JSON.parse(frame.subarray(8, 8 + length).toString('utf8')) };
}

function lastRequest(socket) {
  return parseFrame(socket.writes[socket.writes.length - 1]).value;
}

function respond(socket, id, data) {
  socket.emit('data', encodeJson({ type: `response_${id}`, data }));
}

async function rejectServerError(conn, socket, action, command) {
  const promise = action();
  respond(socket, lastRequest(socket).id, { error: '设备端失败原因' });
  await assert.rejects(promise, new RegExp(`命令 ${command} 失败：设备端失败原因`));
}

test('断线后发送命令立即失败，不会静默等满超时', async () => {
  const { conn, socket } = makeConnection();
  conn.close();
  const started = Date.now();
  await assert.rejects(
    conn.sendCommand({ command: 'save', id: 'main.js', name: 'main.js', script: '' }),
    /连接已断开，命令未发送/,
  );
  assert.ok(Date.now() - started < 1000, '应立即可失败，而不是等 20 秒');
  assert.equal(socket.writes.length, 0, '断线后不应再往 socket 写数据');
  assert.equal(conn.pending.size, 0, '失败后不能留下 pending 定时器');
});

test('极快 response 在 write 内同步到达也能正确配对', async () => {
  const { conn, socket } = makeConnection();
  socket.onWrite = (frame) => {
    const request = parseFrame(frame);
    if (request.type === 1 && request.value.type === 'command') {
      respond(socket, request.value.id, { ok: true });
    }
  };
  const result = await conn.sendCommand({ command: 'fast_command' });
  assert.equal(result.ok, true);
  assert.equal(conn.pending.size, 0);
  conn.close();
});

test('run/save/debug 的服务端 error 统一 reject', async () => {
  const run = makeConnection();
  await rejectServerError(run.conn, run.socket, () => run.conn.run('main.js', 'run-1', 'console.log(1)'), 'run');
  run.conn.close();

  const save = makeConnection();
  await rejectServerError(save.conn, save.socket, () => save.conn.save('main.js', ''), 'save');
  save.conn.close();

  const debugRun = makeConnection();
  await rejectServerError(
    debugRun.conn,
    debugRun.socket,
    () => debugRun.conn.debugRun('main.js', '', [1]),
    'debug_run',
  );
  debugRun.conn.close();

  const evaluate = makeConnection();
  await rejectServerError(
    evaluate.conn,
    evaluate.socket,
    () => evaluate.conn.debugEvaluate('a + 1', 0),
    'debug_evaluate',
  );
  evaluate.conn.close();
});

test('run/run_project 成功确认后才更新 lastRunId，失败不污染 stop 目标', async () => {
  const { conn, socket } = makeConnection();

  const failedRun = conn.run('first.js', 'run-failed', '');
  assert.equal(conn.lastRunId, undefined, '发出后、设备确认前不应提前赋值');
  respond(socket, lastRequest(socket).id, { error: '没有启动' });
  await assert.rejects(failedRun, /命令 run 失败/);
  assert.equal(conn.lastRunId, undefined);

  const okRun = conn.run('second.js', 'run-ok', '');
  respond(socket, lastRequest(socket).id, {});
  await okRun;
  assert.equal(conn.lastRunId, 'run-ok');

  const failedProject = conn.sendBytesCommand('run_project', 'project-failed', Buffer.from('zip'), []);
  assert.deepEqual(socket.writes.slice(-2).map((frame) => frame.readUInt32BE(4)), [2, 1]);
  respond(socket, lastRequest(socket).id, { error: '解压失败' });
  await assert.rejects(failedProject, /命令 run_project 失败/);
  assert.equal(conn.lastRunId, 'run-ok', '失败的工程不能覆盖上一次有效运行');

  const okProject = conn.sendBytesCommand('run_project', 'project-ok', Buffer.from('zip'), []);
  respond(socket, lastRequest(socket).id, {});
  await okProject;
  assert.equal(conn.lastRunId, 'project-ok');
  conn.close();
});

test('断线会立即 reject 所有 pending 命令', async () => {
  const { conn } = makeConnection();
  const save = conn.sendCommand({ command: 'save', id: 'a.js', name: 'a.js', script: '' });
  const evaluate = conn.debugEvaluate('x', 0);
  conn.close();
  await assert.rejects(save, /连接已断开（命令 save 未完成）/);
  await assert.rejects(evaluate, /连接已断开（命令 debug_evaluate 未完成）/);
});

test('等待 bytes 时断线会立即清理 waiter 和定时器', async () => {
  const { conn, socket } = makeConnection();
  const reading = conn.readFile('/sdcard/main.js');
  respond(socket, lastRequest(socket).id, { md5: '0123456789abcdef' });
  await new Promise(setImmediate);
  assert.equal(conn.byteWaiters.size, 1, 'response 到达后应在等 bytes 帧');

  conn.close();
  await assert.rejects(reading, /连接已断开（等待 read_file 的文件内容时连接关闭）/);
  assert.equal(conn.byteWaiters.size, 0);
});

test('坏 gzip 帧不会抛穿扩展宿主，会记录原因并断开连接', async () => {
  const { conn, socket, log } = makeConnection();
  const pending = conn.sendCommand({ command: 'save', id: 'a.js', name: 'a.js', script: '' });

  assert.doesNotThrow(() => {
    socket.emit('data', encodeFrame(3, Buffer.from('this is not gzip')));
  });
  assert.equal(conn.isClosed(), true);
  assert.ok(log.some((line) => /处理帧失败（type=3）/.test(line)));
  await assert.rejects(pending, /连接已断开（命令 save 未完成）/);
});

test('响应超时信息包含命令名、时长和 id', async () => {
  const { conn } = makeConnection();
  await assert.rejects(
    conn.sendCommand({ command: 'slow_command' }, 30),
    /命令 slow_command 响应超时（30 毫秒，id=1）/,
  );
  conn.close();
});

test('bytes 响应缺少 md5 时立即报错，不再空等 5 秒', async () => {
  const { conn, socket } = makeConnection();
  const reading = conn.readFile('/sdcard/main.js');
  respond(socket, lastRequest(socket).id, {});
  await assert.rejects(reading, /命令 read_file 的响应缺少文件 md5/);
  conn.close();
});

test('停止当前没有运行目标时不会误停设备上的全部任务', async () => {
  const { conn, socket } = makeConnection();
  await assert.rejects(conn.stop(), /当前连接尚未运行脚本/);
  assert.equal(socket.writes.length, 0);
  conn.close();
});

test('单独停止工程没有明确确认时不会降级成 stopAll', async () => {
  const { conn, socket } = makeConnection();
  const running = conn.sendBytesCommand('run_project', 'one-project', Buffer.from('zip'), []);
  respond(socket, lastRequest(socket).id, {});
  await running;
  const stopping = conn.stop();
  respond(socket, lastRequest(socket).id, {});
  await assert.rejects(stopping, /设备未确认停止该工程/);
  assert.equal(lastRequest(socket).data.command, 'stop');
  conn.close();
});
