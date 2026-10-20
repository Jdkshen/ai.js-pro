/*
 * Node24 color 模块兼容契约测试（真实 asset，不是镜像实现）。
 *
 * 回归背景：modules/color.js 覆盖 prelude 的 colorModule 后，曾丢失
 * parseColor/argb/rgb/alpha/red/green/blue/isSimilar 与 Color.prototype.toInt，
 * 且 Color.parse 只接受字符串，导致 docs/guides/Node24基础自动化API.md 的示例不可用。
 *
 * 本测试按 Node24Prelude.wrap(...) 的真实顺序执行：
 *   prelude.js -> ui.js -> modules/color.js -> modules/floating_window.js
 *   -> modules/accessibility_extra.js
 * 然后只通过 require('color') / require('image') 断言公开契约，不复制模块代码。
 *
 * 运行：node tools/test-node24-color-compat.cjs
 * 不构建、不装 APK、不连设备。
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const ASSET_DIR = path.join(ROOT, 'modules', 'engine', 'src', 'main', 'assets', 'node24');
const PRELUDE = path.join(ASSET_DIR, 'prelude.js');
const UI = path.join(ASSET_DIR, 'ui.js');
const COLOR = path.join(ASSET_DIR, 'modules', 'color.js');
const FLOATING_WINDOW = path.join(ASSET_DIR, 'modules', 'floating_window.js');
const ACCESSIBILITY_EXTRA = path.join(ASSET_DIR, 'modules', 'accessibility_extra.js');
const SCRIPT_FILE = path.join(ROOT, 'tmp-node24-color-contract', 'src', 'main.js');
const SCRIPT_DIR = path.dirname(SCRIPT_FILE);

function readAsset(file) {
  const source = fs.readFileSync(file, 'utf8');
  assert.ok(source.trim().length > 0, 'asset 为空：' + file);
  return source;
}

const state = {
  calls: [],
  findColorResult: {x: 1, y: 2},
  findMultiColorsResult: {x: 3, y: 4},
  detectsColorResult: true,
  pixelValue: 0xff009688
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
    return reply({bridge: 'mock-node24-color', version: 2, projectDir: path.dirname(SCRIPT_DIR)});
  }
  if (request.kind === 'automation') { return reply(true); }
  if (request.kind === 'image') {
    switch (request.action) {
      case 'read': return reply({id: 9001, width: 32, height: 32});
      case 'write': return reply(true);
      case 'pixel': return reply(state.pixelValue);
      case 'findColor': return reply(state.findColorResult);
      case 'findMultiColors': return reply(state.findMultiColorsResult);
      case 'detectsColor': return reply(state.detectsColorResult);
      case 'detectsMultiColors': return reply(true);
      case 'recycle': return reply(true);
      default: return failure('unsupported image action: ' + request.action);
    }
  }
  return failure('unsupported kind: ' + request.kind);
}

globalThis.__ajsProjectDir = path.dirname(SCRIPT_DIR);
globalThis.__ajsScriptFile = SCRIPT_FILE;
// Production prelude changes the real cwd; provide an actual test workspace.
fs.mkdirSync(globalThis.__ajsProjectDir, {recursive: true});
globalThis.__autojsHost = mockHost;

// 与 Node24Prelude.wrap(...) 完全一致：prelude（带 require）-> ui -> 三个模块。
// 这里逐项执行真实源码；顺序变化会让 color.js 的覆盖/别名测试失去意义。
new Function('require', readAsset(PRELUDE))(require);
new Function(readAsset(UI))();
// 先通过 require 拿到 prelude 旧 color，验证 color.js 注册时确实清掉模块缓存。
const legacyColorModule = require('color');
assert.equal(legacyColorModule, globalThis.__ajsBuiltinModules.color);
const legacyColor = legacyColorModule.Color;
new Function(readAsset(COLOR))();
new Function(readAsset(FLOATING_WINDOW))();
new Function(readAsset(ACCESSIBILITY_EXTRA))();

const color = require('color');
const image = require('image');

function resetCalls() { state.calls.length = 0; }

function requests(kind, action) {
  return state.calls.filter((call) => call.kind === kind && (action == null || call.action === action));
}

function lastRequest(kind, action) {
  const list = requests(kind, action);
  return list.length ? list[list.length - 1] : null;
}

const tests = [];
function test(name, fn) { tests.push({name, fn}); }

// ---------------------------------------------------------------------------
// 加载顺序 / 覆盖关系
// ---------------------------------------------------------------------------
test('真实加载顺序：prelude + ui + color + floating_window + accessibility_extra', () => {
  assert.equal(typeof globalThis.__ajsModuleFactories.ui, 'function', 'ui.js 未注册 ui');
  assert.equal(typeof globalThis.__ajsModuleFactories.color, 'function', 'color.js 未注册 color');
  assert.equal(typeof globalThis.__ajsModuleFactories.floating_window, 'function',
    'floating_window.js 未注册 floating_window');
  assert.equal(typeof globalThis.__ajsModuleFactories.ui_selector, 'function',
    'accessibility_extra.js 未注册 ui_selector');

  assert.equal(require('color'), color);
  assert.equal(globalThis.__ajsBuiltinModules.color, color);
  assert.equal(globalThis.$autojs.color, color);
  assert.equal(require('floating_window').FLAG_NOT_FOCUSABLE, 8);
  assert.equal(typeof require('accessibility').select, 'function');
});

test('不拿 prelude 旧 Color 覆盖新 Color：别名返回新类实例', () => {
  assert.notEqual(color.Color, legacyColor, 'color.Color 不应是 prelude 的旧 Color');
  assert.equal(typeof color.Color.prototype.toInt, 'function');

  const made = color.argb(0x80, 1, 2, 3);
  assert.ok(made instanceof color.Color, 'module.argb 应返回新 Color');
  assert.equal(made instanceof legacyColor, false, 'module.argb 不应返回旧 Color');
  assert.equal(made.constructor, color.Color);
  assert.notStrictEqual(color.argb(0x80, 1, 2, 3), color.argb(0x80, 1, 2, 3),
    '每次 argb 都应构造新 Color 实例');
  assert.ok(color.Color.parse('#009688') instanceof color.Color);
  assert.ok(color.HSVColor.fromAHSV(1, 0, 1, 1) instanceof color.HSVColor);
  assert.ok(color.HSLColor.fromAHSL(1, 0, 1, 0.5) instanceof color.HSLColor);
});

test('legacy/prelude Color（如 Image.pixel）可按 toInt 转成新 Color', () => {
  const oldInstance = new legacyColor(0xff010203);
  const parsed = color.Color.parse(oldInstance);
  assert.ok(parsed instanceof color.Color);
  assert.equal(parsed instanceof legacyColor, false);
  assert.equal(parsed.toInt(), oldInstance.toInt());

  const oldAlpha = new legacyColor(0x80010203);
  const strict = color.parseColor(oldAlpha);
  assert.ok(strict instanceof color.Color);
  assert.equal(strict instanceof legacyColor, false);
  assert.equal(strict.toInt(), (0x80010203 | 0));
  assert.equal(color.isSimilar(oldAlpha, 0x00010203, 0), true);
});

// ---------------------------------------------------------------------------
// docs 示例与基础 API
// ---------------------------------------------------------------------------
test('高级版 cv.Rect 可直接用作真实 image region 参数', () => {
  const {Rect} = require('@autojs/opencv');
  assert.deepEqual({...new Rect()}, {x: 0, y: 0, width: 0, height: 0});
  const region = new Rect(2, 3, 12, 10);
  assert.throws(() => { region.x = 99; }, TypeError);
  const frame = image.readImageSync('./fixture.png');
  try {
    image.findMultiColorsSync(frame, {firstColor: '#009688', offsetColors: [[1, 1, '#ffffff']]}, {region});
    assert.deepEqual(lastRequest('image', 'findMultiColors').region, [2, 3, 12, 10]);
  } finally { frame.recycle(); }
  assert.throws(() => new Rect(1), TypeError);
  assert.throws(() => new Rect(0, 0, NaN, 1), TypeError);
});

test('docs 1.4 示例可用：Color.parse/argb/rgb/toInt', () => {
  assert.equal(color.Color.parse('#009688').toInt(), (0xff009688 | 0));
  assert.equal(color.Color.parse('#80010203').toInt(), (0x80010203 | 0));
  assert.equal(color.Color.parse(-16711936).toInt(), -16711936);
  assert.equal(color.Color.argb(255, 1, 2, 3).toInt(), (0xff010203 | 0));
  assert.equal(color.Color.rgb(1, 2, 3).toInt(), (0xff010203 | 0));
  assert.equal(color.Color.argb, color.Color.fromARGB, 'Color.argb 应为 fromARGB 的静态别名');
  assert.equal(color.Color.rgb, color.Color.fromRGB, 'Color.rgb 应为 fromRGB 的静态别名');

  const parsed = color.Color.parse('#80010203');
  assert.equal(parsed.value, parsed.toInt());
  assert.equal(Number(parsed), parsed.toInt());
  assert.equal(parsed.valueOf(), parsed.toInt());
  assert.equal(parsed.toString(), 'Color(0x80010203)');
});

test('恢复全部模块别名并提供新 Color 实例', () => {
  for (const name of ['parseColor', 'argb', 'rgb', 'alpha', 'red', 'green', 'blue', 'isSimilar']) {
    assert.equal(typeof color[name], 'function', 'color.' + name);
  }
  assert.equal(typeof color.toColorInt, 'function');

  const fromArgb = color.argb(0x80, 1, 2, 3);
  const fromRgb = color.rgb(1, 2, 3);
  assert.ok(fromArgb instanceof color.Color);
  assert.ok(fromRgb instanceof color.Color);
  assert.equal(fromArgb.toInt(), (0x80010203 | 0));
  assert.equal(fromRgb.toInt(), (0xff010203 | 0));

  const sample = color.parseColor('#009688');
  assert.ok(sample instanceof color.Color);
  assert.equal(sample.toInt(), (0xff009688 | 0));
  assert.equal(color.alpha(sample), 0xff);
  assert.equal(color.red(sample), 0x00);
  assert.equal(color.green(sample), 0x96);
  assert.equal(color.blue(sample), 0x88);

  assert.equal(color.alpha(-1), 0xff);
  assert.equal(color.red(0xff123456), 0x12);
  assert.equal(color.green(0xff123456), 0x34);
  assert.equal(color.blue(0xff123456), 0x56);
  assert.throws(() => color.alpha('#bad'), TypeError, '通道别名对无效字符串也应抛 TypeError');
});

// ---------------------------------------------------------------------------
// Color.parse 整数边界 / 严格性
// ---------------------------------------------------------------------------
test('Color.parse/构造/toColorInt 接受 signed int32 与 unsigned 32bit', () => {
  const cases = [
    [-0x80000000, -0x80000000],
    [-1, -1],
    [0, 0],
    [0x7fffffff, 0x7fffffff],
    [0x80000000, -0x80000000],
    [0xfffffffe, -2],
    [0xffffffff, -1]
  ];
  for (const [input, expected] of cases) {
    const viaParse = color.Color.parse(input);
    const viaNew = new color.Color(input);
    assert.ok(viaParse instanceof color.Color, 'parse(' + input + ')');
    assert.equal(viaParse.toInt(), expected, 'Color.parse(' + input + ')');
    assert.equal(viaNew.toInt(), expected, 'new Color(' + input + ')');
    assert.equal(color.toColorInt(input), expected, 'toColorInt(' + input + ')');
  }

  const original = color.Color.parse('#80010203');
  const reparsed = color.Color.parse(original);
  assert.ok(reparsed instanceof color.Color);
  assert.equal(reparsed.toInt(), original.toInt());
});

test('Color.parse/构造/toColorInt 拒绝越界、NaN、Infinity 与小数', () => {
  for (const input of [-0x80000001, 0x100000000, 0x100000001]) {
    assert.throws(() => color.Color.parse(input), RangeError, 'parse(' + input + ')');
    assert.throws(() => new color.Color(input), RangeError, 'new Color(' + input + ')');
    assert.throws(() => color.toColorInt(input), RangeError, 'toColorInt(' + input + ')');
  }
  for (const input of [NaN, Infinity, -Infinity, 1.5, -0.5]) {
    assert.throws(() => color.Color.parse(input), TypeError, 'parse(' + input + ')');
    assert.throws(() => new color.Color(input), TypeError, 'new Color(' + input + ')');
    assert.throws(() => color.toColorInt(input), TypeError, 'toColorInt(' + input + ')');
  }
});

// ---------------------------------------------------------------------------
// 字符串解析：宽松 Color.parse 返回 null，严格 module.parseColor 抛 TypeError
// ---------------------------------------------------------------------------
test('Color.parse 字符串失败返回 null（保留原规则）', () => {
  assert.equal(color.Color.parse('#009688').toInt(), (0xff009688 | 0));
  assert.equal(color.Color.parse('#FFFFFFFF').toInt(), -1);

  for (const invalid of ['#xyz', '#12345', '#1234567', '#123456789', '', '009688',
    ' #009688', '#00968g', '#']) {
    assert.equal(color.Color.parse(invalid), null, 'Color.parse(' + JSON.stringify(invalid) + ')');
  }
});

test('严格 module.parseColor 对无效输入明确抛 TypeError', () => {
  assert.equal(color.parseColor('#009688').toInt(), (0xff009688 | 0));
  assert.equal(color.parseColor(-16711936).toInt(), -16711936);
  assert.equal(color.parseColor(0xffffffff).toInt(), -1);
  assert.equal(color.parseColor(color.parseColor('#009688')).toInt(), (0xff009688 | 0));

  for (const invalid of ['#xyz', '#12345', '', '009688', null, undefined, {}, NaN, 1.5]) {
    assert.throws(() => color.parseColor(invalid), TypeError,
      'parseColor(' + String(invalid) + ') 应抛 TypeError');
  }
  // 类型正确但超出 32 位 ARGB 范围：保留 RangeError，不静默截断。
  assert.throws(() => color.parseColor(0x100000000), RangeError);
});

// ---------------------------------------------------------------------------
// isSimilar
// ---------------------------------------------------------------------------
test('isSimilar 使用 RGB 最大通道差、忽略 alpha、threshold 0..255', () => {
  const base = 0xff112233;
  assert.equal(color.isSimilar(base, 0x00122433, 3), true);
  assert.equal(color.isSimilar(base, 0x00122433, 2), true);
  assert.equal(color.isSimilar(base, 0x00122433, 1), false);
  // 最大差 2，不是三通道差值之和。
  assert.equal(color.isSimilar(0xff102030, 0xff122230, 2), true);
  // alpha 完全不同，只要 RGB 相同且 threshold=0 就相似。
  assert.equal(color.isSimilar(0xff112233, 0x00112233, 0), true);
  assert.equal(color.isSimilar(base, base), true);
  assert.equal(color.isSimilar(base, 0xff112234), false);
  assert.equal(color.isSimilar(base, 0xff112234, 1), true);
  assert.equal(color.isSimilar(0xff000000, 0xffffffff, 255), true);
  assert.equal(color.isSimilar(0xff000000, 0xffffffff, 254), false);

  assert.throws(() => color.isSimilar(base, base, -1), RangeError);
  assert.throws(() => color.isSimilar(base, base, 256), RangeError);
  assert.throws(() => color.isSimilar(base, base, 1.5), TypeError);
  assert.throws(() => color.isSimilar(base, base, '3'), TypeError);
  assert.throws(() => color.isSimilar('#bad', base), TypeError);
  assert.throws(() => color.isSimilar(base, null), TypeError);
});

// ---------------------------------------------------------------------------
// HSV / HSL 往返
// ---------------------------------------------------------------------------
test('HSVColor/HSLColor 往返保持颜色（含 alpha）', () => {
  for (const value of [0xff000000, 0x00000000, 0xffffffff, 0xff808080]) {
    const colorValue = new color.Color(value);
    assert.equal(color.HSLColor.fromColor(colorValue).toColor().toInt(), colorValue.toInt());
  }
  const values = [0xffff0000, 0xff00ff00, 0xff0000ff, 0xff00ffff, 0xffff00ff, 0xffff3366,
    0xff009688, 0xff123456, 0x80336699];
  for (const value of values) {
    const original = new color.Color(value);
    assert.equal(color.HSVColor.fromColor(original).toColor().toInt(), original.toInt(),
      'HSV 往返 0x' + (value >>> 0).toString(16));
    assert.equal(color.HSLColor.fromColor(original).toColor().toInt(), original.toInt(),
      'HSL 往返 0x' + (value >>> 0).toString(16));
  }
  const alpha = color.HSVColor.fromAHSV(0.5, 180, 1, 1).toColor();
  assert.equal(alpha.toInt(), (0x8000ffff | 0));
  const alphaHsl = color.HSLColor.fromAHSL(0.5, 180, 1, 0.5).toColor();
  assert.equal(alphaHsl.toInt(), (0x8000ffff | 0));
});

// ---------------------------------------------------------------------------
// image 模块接受新 Color 的 toInt 归一化
// ---------------------------------------------------------------------------
test('image 模块接受新 Color（toInt）并发送 signed ARGB 整数', () => {
  const frame = {id: 9101, width: 32, height: 32};
  const tinted = color.argb(0x80, 1, 2, 3);
  resetCalls();

  assert.deepEqual(image.findColorSync(frame, tinted, {threshold: 3, region: [0, 0, 16, 16]}),
    {x: 1, y: 2});
  const findRequest = lastRequest('image', 'findColor');
  assert.equal(findRequest.color, tinted.toInt());
  assert.equal(findRequest.color, (0x80010203 | 0));
  assert.equal(findRequest.threshold, 3);
  assert.deepEqual(findRequest.region, [0, 0, 16, 16]);

  const parsed = color.parseColor('#009688');
  assert.equal(image.detectsColor(frame, parsed, 1, 2), true);
  const detectRequest = lastRequest('image', 'detectsColor');
  assert.equal(detectRequest.color, (0xff009688 | 0));
  assert.equal(typeof detectRequest.color, 'number');

  // docs 1.3 的 findMultiColorsSync 示例：firstColor 用新 Color，offset 颜色用字符串。
  resetCalls();
  assert.deepEqual(image.findMultiColorsSync(frame, {
    firstColor: color.Color.parse('#009688'),
    offsetColors: [{color: '#ffffff', offsetX: 20, offsetY: 20}]
  }, {threshold: 0, region: [0, 0, 16, 16]}), {x: 3, y: 4});
  const multiRequest = lastRequest('image', 'findMultiColors');
  assert.equal(multiRequest.color, (0xff009688 | 0));
  assert.deepEqual(multiRequest.offsetColors, [
    {offsetX: 20, offsetY: 20, color: (0xffffffff | 0)}
  ]);
  assert.equal(multiRequest.threshold, 0);
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
