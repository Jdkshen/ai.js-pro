/*
 * Node24 image.findImage 契约测试（加载真实 prelude.js，不复制实现）。
 *
 * 回归背景：MI8 all3sample 的 Node「截图与找图」示例依赖 image.findImage，
 * 而 Node24 prelude 只有 findColor/findMultiColors，导致示例缺 API。
 *
 * 本测试用 mock 宿主只验证 JS 契约（真实模板匹配在 Node24Images + OpenCV）：
 *   - 默认 threshold=0.9 / weakThreshold=0.6 / level=-1 / 全图 region；
 *   - 实际发送源图与模板的句柄 id（不是像素、不是 findColor 替身）；
 *   - 返回值 {x,y} 或 null，宿主错误原样抛出；
 *   - 阈值/region/模板尺寸在 JS 侧先校验，脏请求不发宿主；
 *   - findImage/findImageSync/Image.prototype.findImage 三条入口一致；
 *   - 匹配后不回收、不移除任何一张源图句柄。
 *
 * 运行：node tools/test-node24-find-image.cjs
 * 不构建、不装 APK、不连设备。
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const PRELUDE = path.join(ROOT, 'modules', 'engine', 'src', 'main', 'assets', 'node24', 'prelude.js');
const SCRIPT_FILE = path.join(ROOT, 'tmp-node24-find-image-contract', 'src', 'main.js');
const SCRIPT_DIR = path.dirname(SCRIPT_FILE);

const state = {
  calls: [],
  result: {x: 12, y: 34},
  pixel: 0xff009688,
  failMessage: null
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
  if (request.kind === 'host') {
    return reply({bridge: 'mock-node24-find-image', version: 1, projectDir: path.dirname(SCRIPT_DIR)});
  }
  if (request.kind === 'image') {
    if (state.failMessage) { return failure(state.failMessage); }
    switch (request.action) {
      case 'read': return reply({id: 9001, width: 32, height: 32});
      case 'write': return reply(true);
      case 'pixel': return reply(state.pixel);
      case 'findColor': return reply({x: 1, y: 2});
      case 'findMultiColors': return reply({x: 3, y: 4});
      case 'detectsColor': return reply(true);
      case 'detectsMultiColors': return reply(true);
      case 'findImage': return reply(state.result);
      case 'recycle': return reply(true);
      default: return failure('unsupported image action: ' + request.action);
    }
  }
  return failure('unsupported kind: ' + request.kind);
}

fs.mkdirSync(SCRIPT_DIR, {recursive: true});
globalThis.__ajsProjectDir = path.dirname(SCRIPT_DIR);
globalThis.__ajsScriptFile = SCRIPT_FILE;
globalThis.__autojsHost = mockHost;

new Function('require', fs.readFileSync(PRELUDE, 'utf8'))(require);

const image = require('image');

function resetCalls() { state.calls.length = 0; }
function imageCalls(action) {
  return state.calls.filter((call) => call.kind === 'image'
    && (action == null || call.action === action));
}
function lastImageCall(action) {
  const list = imageCalls(action);
  return list.length ? list[list.length - 1] : null;
}

function source(width, height, id) {
  return new image.Image({id: id || 101, width: width || 640, height: height || 480});
}
function template(width, height, id) {
  return new image.Image({id: id || 102, width: width || 32, height: height || 32});
}

const tests = [];
function test(name, fn) { tests.push({name, fn}); }

// ---------------------------------------------------------------------------
test('findImageSync 默认值：0.9/0.6/-1/全图，发送真实两个句柄 id', () => {
  resetCalls();
  const frame = source();
  const tpl = template();
  assert.deepEqual(image.findImageSync(frame, tpl), {x: 12, y: 34});

  const request = lastImageCall('findImage');
  assert.ok(request, '应发出 findImage 请求');
  assert.equal(request.id, 101, '源图句柄 id');
  assert.equal(request.templateId, 102, '模板句柄 id');
  assert.equal(request.threshold, 0.9);
  assert.equal(request.weakThreshold, 0.6);
  assert.equal(request.level, -1);
  assert.deepEqual(request.region, [0, 0, 640, 480]);
  assert.equal(Object.prototype.hasOwnProperty.call(request, 'color'), false,
    'findImage 不能退化成 findColor 请求');
  assert.equal(Object.prototype.hasOwnProperty.call(request, 'pixels'), false);
});

test('Image.prototype.findImage/findImageSync 与模块级同步入口一致', () => {
  resetCalls();
  const frame = source(320, 240, 201);
  const tpl = template(16, 16, 202);
  state.result = {x: 7, y: 9};
  assert.deepEqual(frame.findImage(tpl, {threshold: 0.8, level: 2}), {x: 7, y: 9});
  assert.equal(typeof frame.findImageSync, 'function');
  assert.deepEqual(frame.findImageSync(tpl, {threshold: 0.8, level: 2}), {x: 7, y: 9});

  const requests = imageCalls('findImage');
  assert.equal(requests.length, 2);
  for (const request of requests) {
    assert.equal(request.id, 201);
    assert.equal(request.templateId, 202);
    assert.equal(request.threshold, 0.8);
    assert.equal(request.weakThreshold, 0.6);
    assert.equal(request.level, 2);
    assert.deepEqual(request.region, [0, 0, 320, 240]);
  }
  state.result = {x: 12, y: 34};
});

test('findImage 返回 Promise，未命中时 resolve(null)', async () => {
  resetCalls();
  const frame = source();
  const tpl = template();
  state.result = null;
  const pending = image.findImage(frame, tpl);
  assert.ok(pending instanceof Promise, 'image.findImage 必须返回 Promise');
  assert.equal(imageCalls('findImage').length, 0, '宿主调用应在微任务中发生');
  assert.equal(await pending, null);
  assert.equal(imageCalls('findImage').length, 1);
  state.result = {x: 12, y: 34};
});

test('显式 options：threshold/weakThreshold/level/region 原样归一化', () => {
  resetCalls();
  const frame = source(640, 480, 301);
  const tpl = template(64, 48, 302);
  state.result = {x: 110, y: 140};
  assert.deepEqual(image.findImageSync(frame, tpl, {
    threshold: 0.75,
    weakThreshold: 0.5,
    level: 0,
    region: [10, 20, 100, 80]
  }), {x: 110, y: 140});
  const request = lastImageCall('findImage');
  assert.equal(request.threshold, 0.75);
  assert.equal(request.weakThreshold, 0.5);
  assert.equal(request.level, 0);
  assert.deepEqual(request.region, [10, 20, 100, 80]);

  // 数字 options 沿用 Node24 既有约定，解释为相似度阈值。
  resetCalls();
  image.findImageSync(frame, tpl, 0.8);
  const numeric = lastImageCall('findImage');
  assert.equal(numeric.threshold, 0.8);
  assert.equal(numeric.weakThreshold, 0.6);

  // [x,y] 由 JS 补成 [x,y,width,height]。
  resetCalls();
  image.findImageSync(frame, tpl, {region: [30, 40]});
  assert.deepEqual(lastImageCall('findImage').region, [30, 40, 610, 440]);
});

test('阈值必须是 0..1 的有限数字，脏参数不发宿主', () => {
  const frame = source();
  const tpl = template();
  resetCalls();
  for (const value of [-0.01, 1.01, 2]) {
    assert.throws(() => image.findImageSync(frame, tpl, {threshold: value}), RangeError);
    assert.throws(() => image.findImageSync(frame, tpl, {weakThreshold: value}), RangeError);
  }
  for (const value of [NaN, Infinity, -Infinity, '0.9', {}, null]) {
    // null 在 Node24 约定里等同未提供；其余类型/NaN 直接拒绝。
    if (value === null) { continue; }
    assert.throws(() => image.findImageSync(frame, tpl, {threshold: value}), TypeError);
    assert.throws(() => image.findImageSync(frame, tpl, {weakThreshold: value}), TypeError);
  }
  assert.equal(imageCalls('findImage').length, 0);
});

test('level 只接受 -1..8 的整数', () => {
  const frame = source();
  const tpl = template();
  resetCalls();
  for (const value of [-2, 9, 1.5, '1', NaN]) {
    assert.throws(() => image.findImageSync(frame, tpl, {level: value}), RangeError);
  }
  assert.equal(imageCalls('findImage').length, 0);
});

test('region 越界与模板大于搜索区域在 JS 侧拒绝', () => {
  const frame = source(100, 80, 601);
  const tpl = template(32, 32, 602);
  resetCalls();
  assert.throws(() => image.findImageSync(frame, tpl, {region: [90, 10, 20, 20]}), RangeError);
  assert.throws(() => image.findImageSync(frame, tpl, {region: [0, 0, 31, 32]}), /大于搜索区域/);
  assert.throws(() => image.findImageSync(frame, template(101, 32, 603)), /大于搜索区域/);
  assert.equal(imageCalls('findImage').length, 0, '本地校验失败时不能发宿主');
});

test('宿主错误原样抛出，不伪造成 null/命中', () => {
  const frame = source();
  const tpl = template();
  state.failMessage = 'Template 64x64 is larger than source/search region 32x32';
  try {
    assert.throws(() => image.findImageSync(frame, tpl), /larger than source\/search region/);
  } finally {
    state.failMessage = null;
  }
});

test('宿主返回非法坐标时明确报协议错误', () => {
  const frame = source();
  const tpl = template();
  state.result = {x: 'bad', y: 1};
  try {
    assert.throws(() => image.findImageSync(frame, tpl), /findImage 返回了非法坐标|findImage\.x/);
  } finally {
    state.result = {x: 12, y: 34};
  }
});

test('匹配后两张源图都不被回收，句柄仍可继续使用（借用语义）', () => {
  resetCalls();
  const frame = source(640, 480, 401);
  const tpl = template(32, 32, 402);
  assert.deepEqual(image.findImageSync(frame, tpl), {x: 12, y: 34});
  assert.deepEqual(image.findImageSync(frame, tpl), {x: 12, y: 34});

  assert.equal(frame.isRecycled(), false);
  assert.equal(tpl.isRecycled(), false);
  assert.equal(imageCalls('recycle').length, 0, 'findImage 不得回收源图或模板');
  assert.equal(frame.width, 640);
  assert.equal(tpl.width, 32);

  // 继续用同一批句柄做其它 image 操作，证明登记表没有被移除。
  assert.equal(image.pixelSync(frame, 0, 0), state.pixel | 0);
  assert.deepEqual(image.findColorSync(frame, '#009688'), {x: 1, y: 2});

  // 显式回收后才不可用。
  frame.recycle();
  tpl.recycle();
  assert.equal(frame.isRecycled(), true);
  assert.throws(() => image.findImageSync(frame, tpl), /已回收/);
});

test('已回收的模板/源图在发宿主前就报错', () => {
  resetCalls();
  const frame = source(320, 240, 501);
  const tpl = template(16, 16, 502);
  tpl.recycle();
  assert.throws(() => image.findImageSync(frame, tpl), /已回收/);
  assert.equal(imageCalls('findImage').length, 0);
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
