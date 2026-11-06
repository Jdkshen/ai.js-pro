/*
 * Node24 基础自动化 API 契约测试（mock host，不是镜像实现）。
 *
 * 约束：只验证 modules/engine/src/main/assets/node24/{prelude,ui}.js 的 JS 适配层：
 *   - 发往 __ajsHost 的请求 shape / 参数归一化；
 *   - Promise 与错误传播、无障碍 enableService 轮询超时；
 *   - image 句柄包装/回收、颜色/region 归一化；
 *   - app.viewFile 路径解析、boolean 与失败传播；
 *   - media_projection capturer 生命周期；
 *   - ui.isUiThread 真实 Looper 判定路径与 ui.run/post 的 Node 线程语义。
 *
 * 不代替 Java 宿主真机测试；host mock 按 docs/guides/Node24基础自动化API.md
 * 的“宿主协议摘要”返回数据。
 * 运行：node tools/test-node24-automation-api.cjs
 * 也可在仓库根目录：node tools/test-node24-automation-api.cjs
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const PRELUDE = path.join(ROOT, 'modules', 'engine', 'src', 'main', 'assets', 'node24', 'prelude.js');
const UI = path.join(ROOT, 'modules', 'engine', 'src', 'main', 'assets', 'node24', 'ui.js');
const SCRIPT_FILE = path.join(ROOT, 'tmp-node24-contract', 'src', 'main.js');
const SCRIPT_DIR = path.dirname(SCRIPT_FILE);

const state = {
  calls: [],
  statusProvider: () => ({enabled: true, ready: true}),
  actionResults: {},
  appResults: {},
  appError: null,
  readError: null,
  readDescriptor: null,
  writeResult: true,
  writeError: null,
  pixelValue: 0xff009688,
  findColorResult: {x: 10, y: 20},
  findMultiColorsResult: {x: 30, y: 40},
  detectsColorResult: true,
  detectsMultiColorsResult: false,
  recycleResult: true,
  recycleError: null,
  screenshotDescriptor: null,
  screenshotError: null,
  projectionMode: 'grant',
  projectionError: null,
  captureError: null,
  stopError: null,
  stopResult: true,
  nextImageId: 100,
  nextCaptureId: 500,
  stopCalls: 0,
  captureWidth: 1080,
  captureHeight: 1920,
  looperCurrentHandle: null,
  looperMainHandle: 77,
  looperEquals: true
};

function reply(value) {
  return JSON.stringify({ok: true, value: value === undefined ? null : value});
}

function failure(message) {
  return JSON.stringify({ok: false, error: message});
}

function mockHost(raw) {
  const request = JSON.parse(raw);
  state.calls.push(request);

  if (request.kind === 'poll') { return reply({events: []}); }
  if (request.kind === 'keepalive') { return reply(true); }
  if (request.kind === 'app') {
    if (state.appError) { return failure(state.appError); }
    const result = state.appResults[request.action];
    if (result === 'ERROR') { return failure('app ' + request.action + ' failed'); }
    return reply(result === undefined ? true : result);
  }
  if (request.kind === 'host') {
    return reply({bridge: 'mock-node24-host', version: 2, projectDir: path.dirname(SCRIPT_DIR)});
  }

  if (request.kind === 'automation') {
    if (request.action === 'status') {
      let value;
      try {
        value = state.statusProvider();
      } catch (error) {
        return failure(String(error && error.message ? error.message : error));
      }
      if (value === 'ERROR') { return failure('status failed'); }
      return reply(value);
    }
    if (request.action === 'openSettings') {
      if (state.openSettingsError) { return failure(state.openSettingsError); }
      return reply(true);
    }
    if (request.action === 'takeScreenshot') {
      if (state.screenshotError) { return failure(state.screenshotError); }
      const descriptor = state.screenshotDescriptor || {
        id: ++state.nextImageId,
        width: state.captureWidth,
        height: state.captureHeight
      };
      state.screenshotDescriptor = null;
      return reply(descriptor);
    }
    const result = state.actionResults[request.action];
    if (result === 'ERROR') { return failure('action ' + request.action + ' failed'); }
    return reply(result === undefined ? true : result);
  }

  if (request.kind === 'image') {
    switch (request.action) {
      case 'read':
        if (state.readError) { return failure(state.readError); }
        if (state.readDescriptor) {
          const descriptor = state.readDescriptor;
          state.readDescriptor = null;
          return reply(descriptor);
        }
        return reply({id: ++state.nextImageId, width: 100, height: 80});
      case 'write':
        if (state.writeError) { return failure(state.writeError); }
        return reply(state.writeResult);
      case 'pixel':
        return reply(state.pixelValue);
      case 'findColor':
        return reply(state.findColorResult);
      case 'findMultiColors':
        return reply(state.findMultiColorsResult);
      case 'detectsColor':
        return reply(state.detectsColorResult);
      case 'detectsMultiColors':
        return reply(state.detectsMultiColorsResult);
      case 'recycle':
        if (state.recycleError) { return failure(state.recycleError); }
        return reply(state.recycleResult);
      default:
        return failure('unsupported image action: ' + request.action);
    }
  }

  if (request.kind === 'projection') {
    if (request.action === 'request') {
      if (state.projectionMode === 'deny') { return reply(false); }
      if (state.projectionMode === 'error') {
        return failure(state.projectionError || 'projection request failed');
      }
      return reply(true);
    }
    if (request.action === 'capture') {
      if (state.captureError) { return failure(state.captureError); }
      return reply({
        id: ++state.nextCaptureId,
        width: state.captureWidth,
        height: state.captureHeight
      });
    }
    if (request.action === 'stop') {
      state.stopCalls++;
      if (state.stopError) { return failure(state.stopError); }
      return reply(state.stopResult);
    }
    return failure('unsupported projection action: ' + request.action);
  }

  // 只实现 ui.isUiThread 需要的 Looper 反射面，证明它确实走 Java 桥。
  if (request.kind === 'java') {
    if (request.op === 'class' && request.name === 'android.os.Looper') {
      return reply({$class: 'android.os.Looper'});
    }
    if (request.op === 'member' && request.cls === 'android.os.Looper'
      && (request.name === 'myLooper' || request.name === 'getMainLooper')) {
      return reply({kind: 'method'});
    }
    if (request.op === 'member' && request.obj === state.looperMainHandle
      && request.name === 'equals') {
      return reply({kind: 'method'});
    }
    if (request.op === 'static' && request.cls === 'android.os.Looper') {
      if (request.method === 'myLooper') {
        return reply(state.looperCurrentHandle == null ? null : {
          $obj: state.looperCurrentHandle,
          $class: 'android.os.Looper'
        });
      }
      if (request.method === 'getMainLooper') {
        return reply({$obj: state.looperMainHandle, $class: 'android.os.Looper'});
      }
    }
    if (request.op === 'call' && request.obj === state.looperMainHandle
      && request.method === 'equals') {
      return reply(state.looperEquals);
    }
    return failure('unsupported java request: ' + request.op);
  }

  return failure('unsupported kind: ' + request.kind);
}

globalThis.__ajsProjectDir = path.dirname(SCRIPT_DIR);
globalThis.__ajsScriptFile = SCRIPT_FILE;
// Production prelude changes the real cwd; provide an actual test workspace.
fs.mkdirSync(globalThis.__ajsProjectDir, {recursive: true});
globalThis.__autojsHost = mockHost;

// prelude/ui 的真实源码直接在 Node 进程里执行；require 传入测试进程的 Node require。
// 这不是镜像：模块实现与 App 内加载的是同一份 assets。
new Function('require', fs.readFileSync(PRELUDE, 'utf8'))(require);
new Function(fs.readFileSync(UI, 'utf8'))();

const accessibility = require('accessibility');
const app = require('app');
const image = require('image');
const color = require('color');
const mediaProjection = require('media_projection');
const ui = require('ui');

function resetCalls() { state.calls.length = 0; }

function requests(kind, action) {
  return state.calls.filter((call) => call.kind === kind && (action == null || call.action === action));
}

function lastRequest(kind, action) {
  const list = requests(kind, action);
  return list.length ? list[list.length - 1] : null;
}

function sequenceStatus(values) {
  let index = 0;
  state.statusProvider = () => values[Math.min(index++, values.length - 1)];
}

const tests = [];
function test(name, fn) { tests.push({name, fn}); }

// ---------------------------------------------------------------------------
// 注册面
// ---------------------------------------------------------------------------
test('模块注册到内置表与 $autojs', () => {
  assert.equal(globalThis.__ajsBuiltinModules.accessibility, accessibility);
  assert.equal(globalThis.__ajsBuiltinModules.image, image);
  assert.equal(globalThis.__ajsBuiltinModules.color, color);
  assert.equal(globalThis.__ajsBuiltinModules.media_projection, mediaProjection);

  assert.equal(globalThis.$autojs.accessibility, accessibility);
  assert.equal(globalThis.$autojs.image, image);
  assert.equal(globalThis.$autojs.color, color);
  assert.equal(globalThis.$autojs.media_projection, mediaProjection);

  assert.equal(accessibility.accessibility, accessibility);
  assert.equal(typeof accessibility.StrokeDescription, 'function');
  for (const name of ['enableService', 'status', 'openSettings', 'click', 'longClick',
    'press', 'swipe', 'performGesture', 'performGestures', 'back', 'home',
    'recents', 'notifications', 'takeScreenshot']) {
    assert.equal(typeof accessibility[name], 'function', 'accessibility.' + name);
  }
  for (const name of ['readImage', 'readImageSync', 'writeImage', 'writeImageSync',
    'pixel', 'findColor', 'findColorSync', 'findMultiColors', 'findMultiColorsSync',
    'detectsColor', 'detectsMultiColors', 'recycle']) {
    assert.equal(typeof image[name], 'function', 'image.' + name);
  }
  assert.equal(typeof mediaProjection.requestScreenCapture, 'function');
  assert.equal(typeof mediaProjection.captureScreen, 'function');
});

// ---------------------------------------------------------------------------
// color
// ---------------------------------------------------------------------------
test('Color 解析 #RRGGBB/#AARRGGBB/整数并转换 ARGB', () => {
  const rgb = color.Color.parse('#009688');
  assert.equal(rgb.toInt(), (0xff009688 | 0));
  assert.equal(rgb.alpha, 255);
  assert.equal(rgb.red, 0x00);
  assert.equal(rgb.green, 0x96);
  assert.equal(rgb.blue, 0x88);
  assert.equal(rgb.toString(), 'Color(0xff009688)');
  assert.equal(Number(rgb), (0xff009688 | 0));

  const argb = color.Color.parse('#80010203');
  assert.equal(argb.alpha, 0x80);
  assert.equal(argb.red, 0x01);
  assert.equal(argb.green, 0x02);
  assert.equal(argb.blue, 0x03);

  assert.equal(new color.Color(0xffffff).toInt(), 0xffffff);
  assert.equal(new color.Color(-1).toInt(), -1);
  assert.equal(new color.Color('-16711936').toInt(), -16711936);
  assert.equal(color.toColorInt(rgb), (0xff009688 | 0));
  assert.equal(color.toColorInt('#009688'), (0xff009688 | 0));
});

test('Color 工厂与非法输入', () => {
  assert.equal(color.Color.argb(0x80, 1, 2, 3).toInt(), (0x80010203 | 0));
  assert.equal(color.Color.fromARGB(0xff, 1, 2, 3).toInt(), (0xff010203 | 0));
  assert.equal(color.Color.rgb(1, 2, 3).toInt(), (0xff010203 | 0));
  assert.equal(color.Color.fromRGB(10, 20, 30).toInt(), (0xff0a141e | 0));
  assert.equal(color.Color.fromGray(7).toInt(), (0xff070707 | 0));
  assert.throws(() => color.Color.argb(1, 2, 3), TypeError);
  assert.throws(() => color.Color.parse('#xyz'), TypeError);
  assert.throws(() => color.Color.parse('#12345'), TypeError);
  assert.throws(() => color.Color.argb(256, 0, 0, 0), RangeError);
  assert.throws(() => new color.Color(NaN), TypeError);
  assert.throws(() => new color.Color(1.5), TypeError);
  assert.throws(() => new color.Color(4294967296), RangeError);
  assert.equal(new color.Color(4294967295).toInt(), -1);
  assert.throws(() => color.Color.fromRGB(1, 2, 300), RangeError);
});

// ---------------------------------------------------------------------------
// accessibility
// ---------------------------------------------------------------------------
test('accessibility.status 返回同步状态对象，await 也兼容', async () => {
  state.statusProvider = () => ({enabled: true, ready: false});
  const status = accessibility.status();
  assert.deepEqual(status, {enabled: true, ready: false});
  assert.deepEqual(await accessibility.status(), {enabled: true, ready: false});
  assert.deepEqual(accessibility.statusSync(), {enabled: true, ready: false});
  state.statusProvider = () => ({enabled: true, ready: true});
});

test('enableService：status -> openSettings -> Node timer 轮询 -> ready', async () => {
  resetCalls();
  sequenceStatus([
    {enabled: false, ready: false},
    {enabled: false, ready: false},
    {enabled: true, ready: true}
  ]);
  const result = await accessibility.enableService({timeout: 500, interval: 5, toast: false});

  assert.equal(result, true);
  // 第三次 status 必须来自 setTimeout 轮询（同步路径最多只会调用两次）。
  const statusCalls = requests('automation', 'status');
  assert.ok(statusCalls.length >= 3, 'status 调用次数不足: ' + statusCalls.length);
  assert.equal(requests('automation', 'openSettings').length, 1);
});

test('enableService：已 enabled 但未 ready 时不开设置，继续轮询', async () => {
  resetCalls();
  sequenceStatus([
    {enabled: true, ready: false},
    {enabled: true, ready: true}
  ]);
  const result = await accessibility.enableService({timeout: 500, interval: 5});
  assert.equal(result, true);
  assert.equal(requests('automation', 'openSettings').length, 0);
});

test('enableService：超时明确 reject，不伪造 enabled', async () => {
  resetCalls();
  sequenceStatus([{enabled: false, ready: false}]);
  await assert.rejects(
    accessibility.enableService({timeout: 25, interval: 5}),
    (error) => {
      assert.match(error.message, /开启无障碍服务超时/);
      assert.match(error.message, /25ms/);
      return true;
    }
  );
  assert.equal(requests('automation', 'openSettings').length, 1);
});

test('enableService：status 宿主错误原样 reject', async () => {
  state.statusProvider = () => 'ERROR';
  await assert.rejects(accessibility.enableService({timeout: 100}), /status failed/);
  state.statusProvider = () => ({enabled: true, ready: true});
});

test('accessibility 动作是 Promise 且请求 shape 正确', async () => {
  resetCalls();
  state.actionResults = {click: true, longClick: true, press: true, swipe: true};
  const clickPromise = accessibility.click(12, 34);
  assert.ok(clickPromise instanceof Promise);
  assert.equal(await clickPromise, true);
  assert.deepEqual(lastRequest('automation', 'click'), {
    kind: 'automation', action: 'click', x: 12, y: 34
  });

  assert.equal(await accessibility.longClick(5, 6), true);
  assert.deepEqual(lastRequest('automation', 'longClick'), {
    kind: 'automation', action: 'longClick', x: 5, y: 6
  });

  assert.equal(await accessibility.press(7, 8, 900), true);
  assert.deepEqual(lastRequest('automation', 'press'), {
    kind: 'automation', action: 'press', x: 7, y: 8, duration: 900
  });

  assert.equal(await accessibility.swipe(1, 2, 3, 4, 300), true);
  assert.deepEqual(lastRequest('automation', 'swipe'), {
    kind: 'automation', action: 'swipe', x1: 1, y1: 2, x2: 3, y2: 4, duration: 300
  });

  for (const action of ['back', 'home', 'recents', 'notifications']) {
    assert.equal(await accessibility[action](), true);
    assert.deepEqual(lastRequest('automation', action), {kind: 'automation', action: action});
  }
});

test('accessibility：宿主 false 原样返回，宿主错误 reject', async () => {
  state.actionResults = {click: false};
  assert.equal(await accessibility.click(1, 2), false);

  state.actionResults = {click: 'ERROR'};
  await assert.rejects(accessibility.click(1, 2), /action click failed/);
  state.actionResults = {};
});

test('accessibility：参数边界在 JS 侧 reject', async () => {
  await assert.rejects(accessibility.click('1', 2), TypeError);
  await assert.rejects(accessibility.click(1, 2.5), TypeError);
  await assert.rejects(accessibility.press(1, 2, 0), RangeError);
  await assert.rejects(accessibility.swipe(1, 2, 3, 4, -1), RangeError);
  await assert.rejects(accessibility.performGesture([], 300), TypeError);
  await assert.rejects(accessibility.performGesture([{x: 1, y: 2}], 0), RangeError);
  await assert.rejects(accessibility.performGestures([]), TypeError);
});

test('performGesture/performGestures 生成带 t 的 strokes', async () => {
  resetCalls();
  assert.equal(await accessibility.performGesture([
    {x: 10, y: 20},
    {x: 30, y: 40}
  ], 400), true);
  assert.deepEqual(lastRequest('automation', 'gesture').strokes, [[
    {x: 10, y: 20, t: 0},
    {x: 30, y: 40, t: 400}
  ]]);

  resetCalls();
  const strokes = [
    new accessibility.StrokeDescription([{x: 1, y: 2}, {x: 3, y: 4}], 350),
    new accessibility.StrokeDescription([{x: 5, y: 6}, {x: 7, y: 8}], 350)
  ];
  assert.equal(await accessibility.performGestures(strokes), true);
  assert.deepEqual(lastRequest('automation', 'gesture').strokes, [
    [{x: 1, y: 2, t: 0}, {x: 3, y: 4, t: 350}],
    [{x: 5, y: 6, t: 0}, {x: 7, y: 8, t: 350}]
  ]);

  resetCalls();
  const delayed = new accessibility.StrokeDescription([{x: 1, y: 2}, {x: 3, y: 4}], 300, 100);
  assert.equal(delayed.duration, 300);
  assert.equal(delayed.delay, 100);
  await accessibility.performGestures([delayed]);
  assert.deepEqual(lastRequest('automation', 'gesture').strokes, [
    [{x: 1, y: 2, t: 100}, {x: 3, y: 4, t: 400}]
  ]);

  // 多指各自带不同 delay：每指首点 t 即该指起始时间，其余 t 随之整体平移。
  resetCalls();
  await accessibility.performGestures([
    new accessibility.StrokeDescription([{x: 1, y: 2}, {x: 3, y: 4}], 300, 100),
    new accessibility.StrokeDescription([{x: 5, y: 6}, {x: 7, y: 8}], 250, 200)
  ]);
  assert.deepEqual(lastRequest('automation', 'gesture').strokes, [
    [{x: 1, y: 2, t: 100}, {x: 3, y: 4, t: 400}],
    [{x: 5, y: 6, t: 200}, {x: 7, y: 8, t: 450}]
  ]);

  resetCalls();
  await accessibility.performGesture([{x: 9, y: 9}], 200, 50);
  assert.deepEqual(lastRequest('automation', 'gesture').strokes, [[
    {x: 9, y: 9, t: 50},
    {x: 9, y: 9, t: 250}
  ]]);

  // 单点 StrokeDescription 同样发“起点 + 终点”两个同位置点，宿主才能保留 delay 与 duration。
  resetCalls();
  const single = new accessibility.StrokeDescription([{x: 11, y: 12}], 175, 25);
  assert.equal(await accessibility.performGestures([single]), true);
  assert.deepEqual(lastRequest('automation', 'gesture').strokes, [[
    {x: 11, y: 12, t: 25},
    {x: 11, y: 12, t: 200}
  ]]);
});

test('手势边界：负坐标、显式 t 递减、总时长超过 60000ms 均在 JS 侧拒绝', async () => {
  await assert.rejects(
    accessibility.performGesture([{x: -1, y: 2}], 100),
    (error) => error instanceof RangeError && /不能小于 0/.test(error.message)
  );
  await assert.rejects(
    accessibility.performGesture([{x: 1, y: -2}], 100),
    (error) => error instanceof RangeError && /不能小于 0/.test(error.message)
  );
  await assert.rejects(
    accessibility.performGesture([{x: 1, y: 2, t: 100}, {x: 3, y: 4, t: 50}], 300),
    (error) => error instanceof RangeError && /不能递减/.test(error.message)
  );
  await assert.rejects(
    accessibility.performGesture([{x: 1, y: 2}], 60000, 1),
    (error) => error instanceof RangeError && /60000ms/.test(error.message)
  );
  assert.throws(
    () => new accessibility.StrokeDescription([{x: 1, y: 2}], 60000, 1),
    (error) => error instanceof RangeError && /60000ms/.test(error.message)
  );
  await assert.rejects(
    accessibility.performGesture([{x: 1, y: 2, t: 60001}, {x: 3, y: 4, t: 60001}], 60000),
    (error) => error instanceof RangeError && /结束时间不能超过 60000ms/.test(error.message)
  );

  // 边界值允许：delay + duration 恰好 60000ms。
  resetCalls();
  assert.equal(await accessibility.performGesture([{x: 1, y: 2}], 59999, 1), true);
  assert.deepEqual(lastRequest('automation', 'gesture').strokes, [[
    {x: 1, y: 2, t: 1},
    {x: 1, y: 2, t: 60000}
  ]]);
});

test('takeScreenshot 返回 Image 包装，宿主错误 reject', async () => {
  state.screenshotError = 'screenshot unsupported';
  await assert.rejects(accessibility.takeScreenshot(), /screenshot unsupported/);
  state.screenshotError = null;

  const shot = await accessibility.takeScreenshot();
  assert.ok(shot instanceof image.Image);
  assert.equal(shot.width, state.captureWidth);
  assert.equal(shot.height, state.captureHeight);
  assert.equal(shot.recycle(), true);
});

// ---------------------------------------------------------------------------
// app
// ---------------------------------------------------------------------------
test('app.viewFile：相对脚本路径解析、精确请求、真实 boolean 与失败传播', () => {
  assert.equal(typeof app.viewFile, 'function');

  resetCalls();
  state.appResults = {};
  state.appError = null;
  assert.equal(app.viewFile('./shot.png'), true);
  assert.deepEqual(lastRequest('app', 'viewFile'), {
    kind: 'app',
    action: 'viewFile',
    args: [path.join(SCRIPT_DIR, 'shot.png')]
  });

  resetCalls();
  const absolute = path.join(ROOT, 'fixtures', 'absolute.png');
  state.appResults = {viewFile: false};
  assert.equal(app.viewFile(absolute), false);
  assert.deepEqual(lastRequest('app', 'viewFile'), {
    kind: 'app',
    action: 'viewFile',
    args: [path.normalize(absolute)]
  });

  state.appResults = {viewFile: 1};
  assert.throws(() => app.viewFile('./shot.png'), /返回值非法/);

  state.appResults = {};
  state.appError = 'viewFile failed';
  assert.throws(() => app.viewFile('./shot.png'), /viewFile failed/);
  state.appError = null;

  assert.throws(() => app.viewFile(''), TypeError);
});

// ---------------------------------------------------------------------------
// image
// ---------------------------------------------------------------------------
test('readImage/readImageSync：相对路径按脚本目录解析并包装句柄', async () => {
  resetCalls();
  const asyncImage = await image.readImage('./template.png');
  assert.ok(asyncImage instanceof image.Image);
  assert.equal(asyncImage.width, 100);
  assert.equal(asyncImage.height, 80);
  assert.equal(asyncImage.getWidth(), 100);
  assert.equal(asyncImage.getHeight(), 80);
  assert.equal(lastRequest('image', 'read').path, path.join(SCRIPT_DIR, 'template.png'));
  assert.equal(asyncImage.recycle(), true);

  resetCalls();
  const absolute = path.join(ROOT, 'fixtures', 'absolute.png');
  const syncImage = image.readImageSync(absolute);
  assert.equal(lastRequest('image', 'read').path, path.normalize(absolute));
  assert.equal(syncImage.recycle(), true);
});

test('readImage 错误传播；非法返回值/IPC 错误不静默', async () => {
  state.readError = 'read failed';
  await assert.rejects(image.readImage('missing.png'), /read failed/);
  assert.throws(() => image.readImageSync('missing.png'), /read failed/);
  state.readError = null;

  state.readDescriptor = {id: 9001, width: 0, height: 10};
  await assert.rejects(image.readImage('bad.png'), /图片尺寸非法/);
  state.readDescriptor = null;
  await assert.rejects(image.readImage(''), TypeError);
  assert.throws(() => image.readImageSync(''), TypeError);
});

test('writeImage/writeImageSync：默认 png/100、格式归一化、false 不伪造', async () => {
  const frame = image.readImageSync('./source.png');
  resetCalls();
  assert.equal(await image.writeImage(frame, './out.png'), true);
  assert.deepEqual(lastRequest('image', 'write'), {
    kind: 'image',
    action: 'write',
    id: frame.id,
    path: path.join(SCRIPT_DIR, 'out.png'),
    format: 'png',
    quality: 100
  });

  resetCalls();
  state.writeResult = false;
  assert.equal(image.writeImageSync(frame, path.join(ROOT, 'out.jpg'), 'JPG', 80), false);
  assert.deepEqual(lastRequest('image', 'write'), {
    kind: 'image',
    action: 'write',
    id: frame.id,
    path: path.normalize(path.join(ROOT, 'out.jpg')),
    format: 'jpeg',
    quality: 80
  });
  state.writeResult = true;

  assert.throws(() => image.writeImageSync(frame, 'x.png', 'gif'), RangeError);
  assert.throws(() => image.writeImageSync(frame, 'x.png', 'png', 101), RangeError);
  assert.throws(() => image.writeImageSync(frame, ''), TypeError);
  frame.recycle();
});

test('pixel：模块级返回 ARGB 整数，Image.pixel 返回 Color', () => {
  const frame = image.readImageSync('./pixel.png');
  resetCalls();
  assert.equal(image.pixel(frame, 1, 2), (0xff009688 | 0));
  assert.deepEqual(lastRequest('image', 'pixel'), {
    kind: 'image', action: 'pixel', id: frame.id, x: 1, y: 2
  });
  const wrapped = frame.pixel(1, 2);
  assert.ok(wrapped instanceof color.Color);
  assert.equal(wrapped.toInt(), (0xff009688 | 0));
  assert.throws(() => image.pixel(frame, -1, 0), RangeError);
  frame.recycle();
});

test('findColor/findColorSync：颜色对象、region [x,y] 补全、对象 region', async () => {
  const frame = image.readImageSync('./find.png');
  const target = color.Color.parse('#009688');

  resetCalls();
  state.findColorResult = {x: 10, y: 20};
  assert.deepEqual(image.findColorSync(frame, target, {
    threshold: 12,
    region: [10, 20]
  }), {x: 10, y: 20});
  assert.deepEqual(lastRequest('image', 'findColor'), {
    kind: 'image',
    action: 'findColor',
    id: frame.id,
    color: (0xff009688 | 0),
    threshold: 12,
    region: [10, 20, 90, 60]
  });

  resetCalls();
  const asyncResult = await image.findColor(frame, '#009688', {
    threshold: 3,
    region: {x: 5, y: 6, width: 20, height: 30}
  });
  assert.deepEqual(asyncResult, {x: 10, y: 20});
  assert.deepEqual(lastRequest('image', 'findColor').region, [5, 6, 20, 30]);
  assert.equal(lastRequest('image', 'findColor').threshold, 3);

  resetCalls();
  state.findColorResult = null;
  assert.equal(image.findColorSync(frame, target), null);
  assert.deepEqual(lastRequest('image', 'findColor'), {
    kind: 'image', action: 'findColor', id: frame.id, color: (0xff009688 | 0), threshold: 0
  });
  state.findColorResult = {x: 10, y: 20};

  await assert.rejects(image.findColor(frame, target, {region: [90, 0, 20, 20]}), RangeError);
  assert.throws(() => image.findColorSync(frame, target, {region: {x: 0, y: 0}}), TypeError);
  assert.throws(() => image.findColorSync(frame, target, {region: [0, 0, 0, 10]}), RangeError);
  assert.throws(() => image.findColorSync(frame, target, {threshold: 256}), RangeError);
  assert.throws(() => image.findColorSync(frame, 'not-a-color'), TypeError);
  frame.recycle();
});

test('findMultiColors：兼容 {firstColor,offsetColors} 与分离参数', async () => {
  const frame = image.readImageSync('./multi.png');
  const target = {
    firstColor: color.Color.parse('#009688'),
    offsetColors: [
      {color: new color.Color(0xffffff), offsetX: 20, offsetY: 20},
      {color: '#000000', offsetX: -5, offsetY: 7}
    ]
  };
  resetCalls();
  state.findMultiColorsResult = {x: 30, y: 40};
  assert.deepEqual(image.findMultiColorsSync(frame, target, {threshold: 0, region: [0, 0, 50, 50]}), {x: 30, y: 40});
  const request = lastRequest('image', 'findMultiColors');
  assert.equal(request.color, (0xff009688 | 0));
  assert.deepEqual(request.offsetColors, [
    {offsetX: 20, offsetY: 20, color: 0xffffff},
    {offsetX: -5, offsetY: 7, color: (0xff000000 | 0)}
  ]);
  assert.deepEqual(request.region, [0, 0, 50, 50]);

  resetCalls();
  const result = await image.findMultiColors(
    frame,
    color.Color.parse('#009688'),
    [[10, 10, 0xffffff]],
    {threshold: 4}
  );
  assert.deepEqual(result, {x: 30, y: 40});
  assert.deepEqual(lastRequest('image', 'findMultiColors').offsetColors, [
    {offsetX: 10, offsetY: 10, color: 0xffffff}
  ]);
  assert.throws(() => image.findMultiColorsSync(frame, {
    firstColor: '#009688',
    offsetColors: []
  }), TypeError);
  frame.recycle();
});

test('detectsColor/detectsMultiColors：阈值与返回布尔', async () => {
  const frame = image.readImageSync('./detect.png');
  resetCalls();
  assert.equal(image.detectsColor(frame, '#009688', 1, 2), true);
  assert.deepEqual(lastRequest('image', 'detectsColor'), {
    kind: 'image',
    action: 'detectsColor',
    id: frame.id,
    color: (0xff009688 | 0),
    x: 1,
    y: 2,
    threshold: 0
  });

  resetCalls();
  assert.equal(await Promise.resolve(image.detectsColor(frame, '#009688', 1, 2, {threshold: 5})), true);
  assert.equal(lastRequest('image', 'detectsColor').threshold, 5);

  resetCalls();
  const target = {
    firstColor: '#009688',
    offsetColors: [{offsetX: 1, offsetY: 2, color: '#ffffff'}]
  };
  assert.equal(image.detectsMultiColors(frame, target, 3, 4), false);
  assert.deepEqual(lastRequest('image', 'detectsMultiColors'), {
    kind: 'image',
    action: 'detectsMultiColors',
    id: frame.id,
    color: (0xff009688 | 0),
    offsetColors: [{offsetX: 1, offsetY: 2, color: (0xffffffff | 0)}],
    x: 3,
    y: 4,
    threshold: 0
  });
  frame.recycle();
});

test('图片句柄回收：幂等、回收后访问报错、宿主 recycle 失败不标记', async () => {
  const frame = image.readImageSync('./recycle.png');
  resetCalls();
  assert.equal(frame.recycle(), true);
  assert.equal(requests('image', 'recycle').length, 1);
  assert.equal(frame.recycle(), true);
  assert.equal(frame.close(), true);
  assert.equal(requests('image', 'recycle').length, 1);
  assert.equal(image.recycle(frame), true);
  assert.equal(requests('image', 'recycle').length, 1);
  assert.throws(() => frame.width, /已回收/);
  assert.throws(() => frame.getWidth(), /已回收/);
  assert.throws(() => image.pixel(frame, 1, 2), /已回收/);
  assert.throws(() => image.findColorSync(frame, '#ffffff'), /已回收/);
  assert.equal(image.recycle({id: frame.id, width: 100, height: 80}), true);
  assert.throws(() => image.pixel({id: frame.id, width: 100, height: 80}, 1, 2), /已回收/);

  const failing = image.readImageSync('./recycle-fail.png');
  state.recycleError = 'recycle failed';
  assert.throws(() => failing.recycle(), /recycle failed/);
  assert.equal(failing.isRecycled(), false);
  state.recycleError = null;
  assert.equal(failing.recycle(), true);
});

// ---------------------------------------------------------------------------
// media_projection
// ---------------------------------------------------------------------------
test('requestScreenCapture deny rejects；宿主错误 reject；参数边界', async () => {
  resetCalls();
  state.projectionMode = 'deny';
  await assert.rejects(mediaProjection.requestScreenCapture(), (error) => {
    assert.equal(error.name, 'ScreenCaptureRequestError');
    assert.match(error.message, /拒绝/);
    return true;
  });
  assert.equal(requests('projection', 'request').length, 1);

  resetCalls();
  state.projectionMode = 'error';
  state.projectionError = 'projection failed';
  await assert.rejects(mediaProjection.requestScreenCapture(), /projection failed/);
  state.projectionMode = 'grant';
  state.projectionError = null;

  await assert.rejects(mediaProjection.requestScreenCapture(123), TypeError);
  await assert.rejects(mediaProjection.requestScreenCapture({width: 0, height: 100}), RangeError);
  await assert.rejects(mediaProjection.requestScreenCapture({width: 100}), TypeError);
  await assert.rejects(mediaProjection.requestScreenCapture({orientation: 3}), RangeError);
});

test('requestScreenCapture 成功、captureScreen 复用、一个任务只授权一次', async () => {
  assert.deepEqual(mediaProjection.Orientation, {
    CURRENT: 0, AUTO: 0, PORTRAIT: 1, LANDSCAPE: 2
  });
  resetCalls();
  state.projectionMode = 'grant';
  const capturer = await mediaProjection.requestScreenCapture({
    width: 1080, height: 2400, orientation: 2
  });
  assert.ok(capturer instanceof mediaProjection.ScreenCapturer);
  assert.deepEqual(lastRequest('projection', 'request'), {
    kind: 'projection',
    action: 'request',
    options: {width: 1080, height: 2400, orientation: 2}
  });

  resetCalls();
  const next = await capturer.nextImage({timeoutMillis: 1234});
  assert.ok(next instanceof image.Image);
  assert.deepEqual(lastRequest('projection', 'capture'), {
    kind: 'projection', action: 'capture', fresh: true, timeoutMillis: 1234
  });
  assert.equal(next.recycle(), true);

  resetCalls();
  const latest = capturer.latestImage();
  assert.ok(latest instanceof image.Image);
  assert.deepEqual(lastRequest('projection', 'capture'), {
    kind: 'projection', action: 'capture', fresh: false, timeoutMillis: 5000
  });
  assert.equal(latest.recycle(), true);

  resetCalls();
  const viaCaptureScreen = await mediaProjection.captureScreen({timeoutMillis: 777});
  assert.ok(viaCaptureScreen instanceof image.Image);
  assert.equal(requests('projection', 'request').length, 0);
  assert.equal(lastRequest('projection', 'capture').fresh, true);
  assert.equal(lastRequest('projection', 'capture').timeoutMillis, 777);
  viaCaptureScreen.recycle();

  resetCalls();
  state.stopCalls = 0;
  assert.equal(capturer.stop(), true);
  assert.equal(state.stopCalls, 1);
  assert.equal(capturer.close(), true);
  assert.equal(state.stopCalls, 1);

  resetCalls();
  await assert.rejects(capturer.nextImage(), (error) => error.name === 'ImageUnavailableError');
  assert.throws(() => capturer.latestImage(), (error) => error.name === 'ImageUnavailableError');
  assert.equal(requests('projection', 'capture').length, 0);

  // Java 宿主 Session.mUsed 一个 job 只成功建立一次；JS 侧提前给出同样明确的错误。
  resetCalls();
  await assert.rejects(mediaProjection.requestScreenCapture(), /已成功建立过/);
  await assert.rejects(mediaProjection.captureScreen(), /已成功建立过/);
  assert.equal(requests('projection', 'request').length, 0);
});

// ---------------------------------------------------------------------------
// ui honesty
// ---------------------------------------------------------------------------
test('ui.isUiThread/ui.uiThread 走真实 Looper 判定而非永远 true', () => {
  const originalPackages = globalThis.$autojs.java.Packages;
  const mainLooper = {name: 'main-looper'};
  let currentLooper = null;

  globalThis.$autojs.java.Packages = {
    android: {
      os: {
        Looper: {
          myLooper: () => currentLooper,
          getMainLooper: () => mainLooper
        }
      }
    }
  };

  try {
    assert.equal(ui.isUiThread(), false);
    assert.equal(ui.uiThread, false);
    assert.equal(ui.run(() => ui.isUiThread()), false);

    currentLooper = mainLooper;
    assert.equal(ui.isUiThread(), true);
    assert.equal(ui.uiThread, true);

    currentLooper = {equals: (other) => other === mainLooper};
    assert.equal(ui.isUiThread(), true);
  } finally {
    globalThis.$autojs.java.Packages = originalPackages;
  }
});

test('ui.isUiThread 在 Looper 读取失败时返回 false 并告警', () => {
  const originalPackages = globalThis.$autojs.java.Packages;
  const originalWarn = console.warn;
  const warnings = [];
  globalThis.$autojs.java.Packages = {
    android: {
      os: {
        Looper: {
          myLooper: () => { throw new Error('looper unavailable'); },
          getMainLooper: () => null
        }
      }
    }
  };
  console.warn = (...args) => { warnings.push(args.join(' ')); };
  try {
    assert.equal(ui.isUiThread(), false);
    assert.equal(ui.uiThread, false);
    assert.ok(warnings.some((line) => line.includes('isUiThread 判定失败')));
  } finally {
    console.warn = originalWarn;
    globalThis.$autojs.java.Packages = originalPackages;
  }
});

test('ui.isUiThread 真实走 Java Looper 桥（mock host）', () => {
  resetCalls();
  state.looperCurrentHandle = null;
  assert.equal(ui.isUiThread(), false);
  assert.ok(requests('java', null).some((call) => call.method === 'myLooper'));

  resetCalls();
  state.looperCurrentHandle = state.looperMainHandle;
  state.looperEquals = true;
  assert.equal(ui.isUiThread(), true);
  assert.ok(requests('java', null).some((call) => call.method === 'getMainLooper'));
  // Same native handle is interned to the same proxy, so identity can finish before equals().

  state.looperCurrentHandle = null;
});

test('ui.run/ui.post 保持调用兼容（回调仍在 Node 线程）', async () => {
  assert.equal(ui.run(() => 42), 42);
  assert.throws(() => ui.run('not a function'), TypeError);
  let posted = false;
  await new Promise((resolve) => {
    const timer = ui.post(() => {
      posted = true;
      resolve();
    }, 0);
    assert.ok(timer && typeof timer.unref === 'function');
  });
  assert.equal(posted, true);
  assert.throws(() => ui.post(null), TypeError);
});

// ---------------------------------------------------------------------------
(async function main() {
  let passed = 0;
  let failed = 0;
  for (const item of tests) {
    try {
      await item.fn();
      passed++;
      console.log('ok ' + item.name);
    } catch (error) {
      failed++;
      console.error('FAIL ' + item.name);
      console.error(error && error.stack ? error.stack : error);
    }
  }
  console.log('\n' + passed + ' passed, ' + failed + ' failed');
  if (failed > 0) { process.exitCode = 1; }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
