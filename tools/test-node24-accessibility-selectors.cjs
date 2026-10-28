/*
 * Node24 accessibility 选择器 / NodeView 契约测试（mock host，非镜像实现）。
 *
 * 覆盖：
 *   - 样例 选择器.js / 无障碍使用示例.js 的实际调用面；
 *   - 纯条件走 findNodes/getNodeInfo，本地条件走 getLatestNodeDump；
 *   - 非法选择器参数拒绝、unknown 字段拒绝、bounds/path/boolean 严格校验；
 *   - 节点动作 path+identity 保护选择器，页面变化不误点、不坐标兜底；
 *   - setText 空串、非输入控件 false、成功后 snapshot text 更新；
 *   - NodeView 异步等待使用 setTimeout（不阻塞事件循环）、timeout 0、maxRetries；
 *   - waitFor 只吞 NotFound；宿主错误原样上抛；
 *   - root/tree/parent/children、正则 global 可重复、from 后代过滤。
 *
 * 运行：node tools/test-node24-accessibility-selectors.cjs
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const util = require('node:util');

const ROOT = path.resolve(__dirname, '..');
const PRELUDE = path.join(ROOT, 'modules', 'engine', 'src', 'main', 'assets', 'node24', 'prelude.js');
const EXTRA = path.join(ROOT, 'modules', 'engine', 'src', 'main', 'assets', 'node24', 'modules', 'accessibility_extra.js');
const SCRIPT_FILE = path.join(ROOT, 'tmp-node24-contract', 'src', 'accessibility.js');

function deepClone(value) { return JSON.parse(JSON.stringify(value)); }

function makeNode(pathValue, className, text, extra) {
  const value = {
    path: pathValue.slice(),
    text: text || '',
    desc: '',
    id: '',
    className,
    packageName: 'com.demo',
    bounds: [0, 0, 100, 100],
    clickable: false,
    enabled: true,
    scrollable: false,
    editable: false,
    childCount: 0,
    indexInParent: pathValue.length ? pathValue[pathValue.length - 1] : -1,
    depth: pathValue.length
  };
  return Object.assign(value, extra || {});
}

function makeTree() {
  return [
    makeNode([], 'android.widget.FrameLayout', '', {
      id: 'com.demo:id/root', bounds: [0, 0, 1080, 1920], childCount: 6
    }),
    makeNode([0], 'android.widget.LinearLayout', '', {
      id: 'com.demo:id/header', childCount: 1
    }),
    makeNode([0, 0], 'android.widget.TextView', '日志', {
      id: 'com.demo:id/title', desc: '日志标题', clickable: true
    }),
    makeNode([1], 'android.widget.LinearLayout', '', {
      id: 'com.demo:id/symbolBar', childCount: 2
    }),
    makeNode([1, 0], 'android.widget.TextView', '', {
      id: 'com.demo:id/btnA', desc: 'Symbol A', clickable: true
    }),
    makeNode([1, 1], 'android.widget.TextView', '', {
      id: 'com.demo:id/btnB', desc: 'Symbol B', clickable: true
    }),
    makeNode([2], 'androidx.recyclerview.widget.RecyclerView', '', {
      id: 'com.demo:id/list', scrollable: true, childCount: 2
    }),
    makeNode([2, 0], 'android.widget.LinearLayout', '第一项', {
      id: 'com.demo:id/row1', clickable: true
    }),
    makeNode([2, 1], 'android.widget.LinearLayout', '第二项', {
      id: 'com.demo:id/row2', clickable: true
    }),
    makeNode([3], 'android.widget.EditText', '旧文本', {
      id: 'com.demo:id/input', editable: true, clickable: true
    }),
    makeNode([4], 'android.widget.LinearLayout', '', {
      id: 'com.demo:id/panel', childCount: 2
    }),
    makeNode([4, 0], 'android.widget.TextView', '面板A', { id: 'com.demo:id/p1' }),
    makeNode([4, 1], 'android.widget.TextView', '面板B', { id: 'com.demo:id/p2' }),
    makeNode([5], 'android.widget.LinearLayout', '', { id: 'com.demo:id/footer' })
  ];
}

const state = {
  tree: makeTree(),
  calls: [],
  protocolErrors: [],
  errorActions: new Set(),
  failNodeAction: false,
  setTextResult: null,
  findNodesOverride: null,
  getNodeInfoMisses: 0
};

function reply(value) { return JSON.stringify({ ok: true, value: value === undefined ? null : value }); }
function failure(message) { return JSON.stringify({ ok: false, error: message }); }
function hasError(action) { return state.errorActions.has(action); }
function samePath(a, b) {
  return Array.isArray(a) && Array.isArray(b) && a.length === b.length
    && a.every((value, index) => value === b[index]);
}
function hostIdMatches(actual, expected) {
  if (actual === expected) { return true; }
  const slash = actual.indexOf('/');
  return slash >= 0 && actual.substring(slash + 1) === expected;
}
function hostMatch(raw, selector) {
  if (Object.prototype.hasOwnProperty.call(selector, 'path')
    && !samePath(raw.path, selector.path)) { return false; }
  if (Object.prototype.hasOwnProperty.call(selector, 'id')
    && !hostIdMatches(raw.id, selector.id)) { return false; }
  if (Object.prototype.hasOwnProperty.call(selector, 'text') && raw.text !== selector.text) { return false; }
  if (Object.prototype.hasOwnProperty.call(selector, 'textContains')
    && raw.text.indexOf(selector.textContains) < 0) { return false; }
  if (Object.prototype.hasOwnProperty.call(selector, 'textStartsWith')
    && raw.text.indexOf(selector.textStartsWith) !== 0) { return false; }
  if (Object.prototype.hasOwnProperty.call(selector, 'textMatches')
    && !new RegExp('^(?:' + selector.textMatches + ')$').test(raw.text)) { return false; }
  if (Object.prototype.hasOwnProperty.call(selector, 'desc') && raw.desc !== selector.desc) { return false; }
  if (Object.prototype.hasOwnProperty.call(selector, 'descContains')
    && raw.desc.indexOf(selector.descContains) < 0) { return false; }
  if (Object.prototype.hasOwnProperty.call(selector, 'className')
    && raw.className !== selector.className) { return false; }
  if (Object.prototype.hasOwnProperty.call(selector, 'packageName')
    && raw.packageName !== selector.packageName) { return false; }
  for (const key of ['clickable', 'enabled', 'scrollable']) {
    if (Object.prototype.hasOwnProperty.call(selector, key) && raw[key] !== selector[key]) { return false; }
  }
  if (Object.prototype.hasOwnProperty.call(selector, 'depth') && raw.depth !== selector.depth) { return false; }
  if (Object.prototype.hasOwnProperty.call(selector, 'index')
    && raw.indexInParent !== selector.index) { return false; }
  if (Object.prototype.hasOwnProperty.call(selector, 'bounds')) {
    const b = selector.bounds;
    if (!Array.isArray(raw.bounds)
      || raw.bounds[0] !== b.left || raw.bounds[1] !== b.top
      || raw.bounds[2] !== b.right || raw.bounds[3] !== b.bottom) { return false; }
  }
  return true;
}
function hostFind(selector) {
  return state.tree.filter((raw) => hostMatch(raw, selector));
}
function guardMatch(raw, selector) {
  if (!Object.prototype.hasOwnProperty.call(selector, 'path') || !samePath(raw.path, selector.path)) {
    return false;
  }
  if (selector.id && !hostIdMatches(raw.id, selector.id)) { return false; }
  if (selector.className && raw.className !== selector.className) { return false; }
  if (selector.packageName && raw.packageName !== selector.packageName) { return false; }
  if (Object.hasOwn(selector, 'text') && raw.text !== selector.text) { return false; }
  if (Object.hasOwn(selector, 'desc') && raw.desc !== selector.desc) { return false; }
  return true;
}
function checkSelector(selector) {
  if (!selector || typeof selector !== 'object' || Array.isArray(selector)) {
    state.protocolErrors.push('selector 不是对象: ' + JSON.stringify(selector));
    return false;
  }
  const allowed = new Set(['id', 'text', 'textContains', 'textStartsWith', 'textMatches',
    'desc', 'descContains', 'className', 'packageName', 'clickable', 'enabled', 'scrollable',
    'depth', 'index', 'bounds', 'path', 'childCount', 'editable', 'indexInParent']);
  for (const key of Object.keys(selector)) {
    if (!allowed.has(key)) {
      state.protocolErrors.push('下发了不支持的 selector 字段: ' + key);
      return false;
    }
    if (key === 'childCount' || key === 'editable' || key === 'indexInParent') {
      state.protocolErrors.push('本地条件被错误下发: ' + key);
      return false;
    }
  }
  return true;
}
function findGuardedNode(selector) {
  const matches = state.tree.filter((raw) => guardMatch(raw, selector));
  return matches.length ? matches[0] : null;
}

function mockHost(raw) {
  const request = JSON.parse(raw);
  state.calls.push(request);
  if (request.kind === 'accessibility' && request.action === 'lockScreen') return reply(false);
  if (request.kind !== 'automation') {
    state.protocolErrors.push('出现非 automation 宿主请求: ' + JSON.stringify(request));
    return failure('unexpected kind: ' + request.kind);
  }
  const action = request.action;
  if (hasError(action)) { return failure(action + ' exploded'); }

  switch (action) {
    case 'getLatestNodeDump':
      return reply(deepClone(state.tree));
    case 'findNodes': {
      if (!checkSelector(request.selector)) { return failure('bad selector'); }
      if (state.findNodesOverride) return reply(state.findNodesOverride(request.selector));
      return reply(deepClone(hostFind(request.selector)));
    }
    case 'getNodeInfo': {
      if (!checkSelector(request.selector)) { return failure('bad selector'); }
      if (state.getNodeInfoMisses > 0) {
        state.getNodeInfoMisses--;
        return reply(null);
      }
      const matches = hostFind(request.selector);
      return reply(matches[request.index] ? deepClone(matches[request.index]) : null);
    }
    case 'clickNode':
    case 'longClickNode': {
      if (!checkSelector(request.selector)) { return failure('bad selector'); }
      if (request.index !== 0) {
        state.protocolErrors.push(action + ' 动作 index 必须为 0，收到 ' + request.index);
      }
      if (state.failNodeAction || !findGuardedNode(request.selector)) { return reply(false); }
      return reply(true);
    }
    case 'nodeAction': {
      if (!checkSelector(request.selector)) return failure('bad selector');
      const actions = ['copy','cut','paste','select','collapse','expand','dismiss','show','scrollForward','scrollBackward','scrollUp','scrollDown','scrollLeft','scrollRight','contextClick','focus','clearFocus','accessibilityFocus','clearAccessibilityFocus','setSelection','setProgress','scrollTo'];
      if (!actions.includes(request.actionName) || !Array.isArray(request.arguments)) return failure('bad node action');
      return reply(!state.failNodeAction && !!findGuardedNode(request.selector));
    }
    case 'setTextNode': {
      if (!checkSelector(request.selector)) { return failure('bad selector'); }
      if (request.index !== 0) {
        state.protocolErrors.push('setTextNode 动作 index 必须为 0，收到 ' + request.index);
      }
      const raw = findGuardedNode(request.selector);
      if (!raw || raw.editable !== true) { return reply(false); }
      if (state.setTextResult === false) { return reply(false); }
      raw.text = request.text;
      return reply(true);
    }
    case 'swipe':
      return reply(true);
    case 'click':
    case 'longClick':
    case 'press':
      return reply(true);
    case 'status':
      return reply({ enabled: true, ready: true });
    case 'recents':
    case 'notifications':
    case 'back':
    case 'home':
      return reply(true);
    default:
      return failure('unsupported automation action: ' + action);
  }
}

globalThis.__ajsProjectDir = path.dirname(SCRIPT_FILE);
globalThis.__ajsScriptFile = SCRIPT_FILE;
// Production prelude changes the real cwd; provide an actual test workspace.
fs.mkdirSync(globalThis.__ajsProjectDir, {recursive: true});
globalThis.__autojsHost = mockHost;

// prelude / accessibility_extra 的真实源码直接执行，不镜像实现。
new Function('require', fs.readFileSync(PRELUDE, 'utf8'))(require);
new Function(fs.readFileSync(EXTRA, 'utf8'))();

const accessibility = require('accessibility');
const uiSelector = require('ui_selector');
const uiObject = require('ui_object');
const { select, UiSelector, NodeView, NotFoundError } = accessibility;

function resetState() {
  state.tree = makeTree();
  state.calls.length = 0;
  state.protocolErrors.length = 0;
  state.errorActions.clear();
  state.failNodeAction = false;
  state.setTextResult = null;
  state.findNodesOverride = null;
  state.getNodeInfoMisses = 0;
}
function requests(action) {
  return state.calls.filter((call) => call.kind === 'automation'
    && (action == null || call.action === action));
}
function last(action) {
  const list = requests(action);
  return list.length ? list[list.length - 1] : null;
}

const tests = [];
function test(name, fn) { tests.push({ name, fn }); }

// ---------------------------------------------------------------------------
// 注册面 / 样例
// ---------------------------------------------------------------------------
test('模块注册与 legacy 面保留', async () => {
  assert.equal(globalThis.__ajsBuiltinModules.accessibility, accessibility);
  assert.equal(globalThis.__ajsBuiltinModules.ui_selector, uiSelector);
  assert.equal(globalThis.__ajsBuiltinModules.selector, uiSelector);
  assert.equal(globalThis.__ajsBuiltinModules.ui_object, uiObject);
  assert.equal(globalThis.$autojs.accessibility, accessibility);
  assert.equal(accessibility.accessibility, accessibility);
  assert.equal(uiSelector.UiSelector, UiSelector);
  assert.equal(uiObject.UiObject, accessibility.UiObject);
  assert.equal(uiSelector.NodeView, NodeView);
  for (const name of ['enableService', 'status', 'statusSync', 'openSettings', 'click',
    'longClick', 'press', 'swipe', 'gesture', 'performGesture', 'performGestures',
    'back', 'home', 'recents', 'notifications', 'takeScreenshot', 'toggleRecents',
    'openNotifications', 'lockScreen']) {
    assert.equal(typeof accessibility[name], 'function', 'accessibility.' + name);
  }
  assert.equal(typeof accessibility.root, 'function');
  assert.equal(typeof accessibility.tree, 'function');
  assert.equal(typeof accessibility.waitFor, 'function');

  // legacy 坐标动作仍走原协议，不被选择器扩展改写。
  assert.equal(await accessibility.click(12, 34), true);
  assert.deepEqual(last('click'), { kind: 'automation', action: 'click', x: 12, y: 34 });
  assert.equal(await accessibility.toggleRecents(), true);
  assert.equal(last('recents').action, 'recents');
});

test('样例 1：select({text}).firstOrNull() 返回属性式节点并可点击', async () => {
  const log = await select({ text: '日志' }).firstOrNull();
  assert.ok(log instanceof NodeView);
  assert.equal(log.text, '日志');
  assert.equal(log.desc, '日志标题');
  assert.equal(log.id, 'com.demo:id/title');
  assert.equal(log.className, 'android.widget.TextView');
  assert.equal(log.packageName, 'com.demo');
  assert.deepEqual(log.path, [0, 0]);
  assert.equal(log.clickable, true);
  assert.equal(String(log), 'NodeView(android.widget.TextView, text=日志)');

  assert.equal(log.click(), true);
  const clickRequest = last('clickNode');
  assert.deepEqual(clickRequest.selector, {
    path: [0, 0],
    id: 'com.demo:id/title',
    className: 'android.widget.TextView',
    packageName: 'com.demo',
    text: '日志',
    desc: '日志标题'
  });
  assert.equal(clickRequest.index, 0);
  assert.equal(requests('click').length, 0, '节点点击不得退化成坐标点击');

  assert.equal(log.longClick(), true);
  assert.deepEqual(last('longClickNode').selector, clickRequest.selector);
  assert.equal(last('longClickNode').index, 0);
});

test('纯条件走 getNodeInfo/findNodes，本地条件才拉 getLatestNodeDump', async () => {
  await select({ text: '日志' }).firstOrNull();
  assert.equal(requests('getNodeInfo').length, 1);
  assert.equal(requests('getLatestNodeDump').length, 0);

  resetState();
  await select({ text: /日志/ }).firstOrNull();
  assert.equal(requests('getLatestNodeDump').length, 1);
  assert.equal(requests('getNodeInfo').length, 0);

  resetState();
  const all = await select({ className: 'android.widget.LinearLayout' }).all();
  assert.equal(all.length, 6);
  assert.equal(requests('findNodes').length, 1);
  assert.equal(requests('getLatestNodeDump').length, 0);

  resetState();
  await select({ childCount: { '>': 0 } }).all();
  assert.equal(requests('getLatestNodeDump').length, 1);
  assert.equal(requests('findNodes').length, 0);
});

test('样例 2：findFirst 支持 RegExp className + clickable', async () => {
  const node = await select({ className: /\.LinearLayout$/, clickable: true })
    .findFirst({ timeout: 500 });
  assert.ok(node instanceof NodeView);
  assert.equal(node.id, 'com.demo:id/row1');
  assert.equal(node.text, '第一项');
  assert.equal(requests('getLatestNodeDump').length, 1);
});

test('样例 3：where(fn).atMost(n).all() 使用属性面且不超上限', async () => {
  const nodes = await select().where((obj) => obj.text !== '').atMost(12).all();
  assert.deepEqual(nodes.map((node) => node.text),
    ['日志', '第一项', '第二项', '旧文本', '面板A', '面板B']);
  assert.equal(requests('getLatestNodeDump').length, 1);

  const limited = await select().where((obj) => obj.text !== '').atMost(2).all();
  assert.deepEqual(limited.map((node) => node.text), ['日志', '第一项']);
});

test('样例 4：childCount 比较对象 + atLeast + timeout + maxRetries', async () => {
  const nodes = await select({ childCount: { '>': 0 } })
    .where((obj) => obj.childCount % 2 === 0)
    .atLeast(4).timeout(500).maxRetries(2)
    .all();
  assert.equal(nodes.length, 4);
  assert.deepEqual(nodes.map((node) => node.childCount), [6, 2, 2, 2]);
  assert.deepEqual(nodes.map((node) => node.path), [[], [1], [2], [4]]);
});

test('atLeast 不足时异步轮询，超时返回已有结果', async () => {
  const started = Date.now();
  const nodes = await select({ childCount: { '>': 0 } })
    .where((obj) => obj.childCount % 2 === 0)
    .atLeast(5).timeout(80).maxRetries(10)
    .all();
  assert.equal(nodes.length, 4);
  assert.ok(requests('getLatestNodeDump').length >= 2);
  assert.ok(Date.now() - started >= 40, 'atLeast 未等待轮询');
});

test('样例 5：from(parentSelector) 后代过滤（id 支持 resource 简名）', async () => {
  const buttons = await select({ clickable: true, className: /TextView$/, desc: /Symbol/ })
    .from(select({ id: 'symbolBar' }))
    .all();
  assert.deepEqual(buttons.map((node) => node.desc), ['Symbol A', 'Symbol B']);
  assert.deepEqual(buttons.map((node) => node.path), [[1, 0], [1, 1]]);
  assert.equal(requests('getLatestNodeDump').length, 1);

  // 普通对象父选择器 + parent 快照：之后改父选择器不应影响已创建的 from 查询。
  const parent = select({ id: 'symbolBar' });
  const child = select({ desc: /Symbol/ }).from(parent);
  parent.id('footer');
  const after = await child.all();
  assert.deepEqual(after.map((node) => node.desc), ['Symbol A', 'Symbol B']);

  // from 只取严格后代，不包含父节点自身。
  const descendants = await select({ id: 'com.demo:id/symbolBar' }).from({ id: 'symbolBar' }).all();
  assert.equal(descendants.length, 0);
});

// ---------------------------------------------------------------------------
// root / tree / 遍历
// ---------------------------------------------------------------------------
test('root/tree 包含根节点，parent 根为 null，children/child 正确', async () => {
  const root = await accessibility.root();
  assert.ok(root instanceof NodeView);
  assert.deepEqual(root.path, []);
  assert.equal(root.parent(), null);

  const tree = root.tree();
  assert.ok(Array.isArray(tree));
  assert.equal(tree.length, 14);
  assert.deepEqual(tree[0].path, []);
  assert.equal(tree[0].id, 'com.demo:id/root');

  const title = tree.find((node) => node.text === '日志');
  assert.ok(title);
  assert.equal(title.parent().id, 'com.demo:id/header');
  assert.deepEqual(title.parent().path, [0]);
  assert.equal(title.parent().parent().id, 'com.demo:id/root');
  assert.equal(title.parent().parent().parent(), null);

  assert.equal(root.childCount, 6);
  assert.equal(root.children().length, 6);
  assert.equal(root.child(1).id, 'com.demo:id/symbolBar');
  assert.equal(accessibility.tree().length, 14);
});

test('方法式 UiObject/UiSelector 兼容：find/findOnce(index)/findOne/exists/waitFor', () => {
  const node = select({ text: '日志' }).findOnce();
  assert.ok(node);
  assert.equal(typeof node.text, 'function');
  assert.equal(typeof node.click, 'function');
  assert.equal(node.text(), '日志');
  assert.equal(node.className(), 'android.widget.TextView');
  assert.equal(node.parent().id(), 'com.demo:id/header');

  const layouts = select({ className: 'android.widget.LinearLayout' }).find();
  assert.equal(layouts.length, 6);
  assert.equal(layouts[1].id(), 'com.demo:id/symbolBar');

  const second = select({ className: 'android.widget.LinearLayout' }).findOnce(1);
  assert.equal(second.id(), 'com.demo:id/symbolBar');

  assert.equal(select({ text: '日志' }).exists(), true);
  assert.equal(select({ text: '不存在' }).exists(), false);
  assert.equal(select({ text: '日志' }).waitFor(0), true);
  assert.equal(select({ text: '不存在' }).waitFor(0), false);
  assert.throws(() => select({ text: '不存在' }).findOne(0), NotFoundError);

  // 本地过滤（RegExp）的方法式查询也走同一套过滤逻辑。
  const local = select({ className: /\.EditText$/ }).findOnce();
  assert.equal(local.id(), 'com.demo:id/input');
});

test('NodeView 元数据：childCount/scrollable/editable/indexInParent', async () => {
  const list = await select({ id: 'list' }).firstOrNull();
  assert.equal(list.scrollable, true);
  assert.equal(list.editable, false);
  assert.equal(list.childCount, 2);
  assert.equal(list.indexInParent, 2);
  assert.equal(list.depth, 1);

  const edit = await select({ id: 'input' }).firstOrNull();
  assert.equal(edit.editable, true);
  assert.equal(edit.indexInParent, 3);
  assert.equal(edit.childCount, 0);
});

// ---------------------------------------------------------------------------
// 严格参数校验
// ---------------------------------------------------------------------------
test('非法选择器参数严格拒绝，且不发宿主请求', () => {
  assert.throws(() => select({ checked: 'true' }), /boolean/);
  assert.throws(() => select({ foo: 1 }), /未知选择器字段/);
  assert.throws(() => select({ clickable: 'true' }), /boolean/);
  assert.throws(() => select({ enabled: 1 }), /boolean/);
  assert.throws(() => select({ editable: 'false' }), /boolean/);
  assert.throws(() => select({ text: 1 }), /字符串或 RegExp/);
  assert.throws(() => select({ textMatches: /x/ }), /字符串/);
  assert.throws(() => select({ path: [1.5] }), /整数/);
  assert.throws(() => select({ path: '1' }), /整数数组/);
  assert.throws(() => select({ bounds: [0, 0, 10, 10] }), /bounds/);
  assert.throws(() => select({ bounds: { left: 0, top: 0, right: 10 } }), /bounds/);
  assert.throws(() => select({ bounds: { left: 0, top: 0, right: 10, bottom: 20, x: 1 } }), /bounds/);
  assert.throws(() => select({ bounds: { left: 0, top: 0, right: 1.2, bottom: 20 } }), /bounds/);
  assert.throws(() => select({ childCount: { '>': '0' } }), /整数/);
  assert.throws(() => select({ childCount: { '>': 0, '<': 5 } }), /一个操作符/);
  assert.throws(() => select({ childCount: { '~': 0 } }), /不支持比较操作符/);
  assert.throws(() => select({ clickable: true }).atMost(-1), RangeError);
  assert.throws(() => select({ clickable: true }).atLeast(-1), RangeError);
  assert.throws(() => select({ clickable: true }).timeout(-1), RangeError);
  assert.throws(() => select({ clickable: true }).maxRetries(-1), RangeError);
  assert.throws(() => select({ clickable: true }).where(null), /需要函数/);
  assert.equal(requests(null).length, 0, '参数校验失败不能访问宿主');
});

test('path 空数组合法，可精确取根节点；bounds 对象按宿主精确匹配', async () => {
  const root = await select({ path: [] }).firstOrNull();
  assert.ok(root);
  assert.deepEqual(root.path, []);
  assert.equal(root.id, 'com.demo:id/root');

  // 空 path 也能组成动作保护选择器（根节点动作不做坐标兜底）。
  assert.equal(root.click(), true);
  assert.deepEqual(last('clickNode').selector.path, []);

  resetState();
  const byBounds = await select({
    bounds: { left: 0, top: 0, right: 1080, bottom: 1920 }
  }).firstOrNull();
  assert.ok(byBounds);
  assert.deepEqual(byBounds.path, []);
});

// ---------------------------------------------------------------------------
// 快照与动作保护
// ---------------------------------------------------------------------------
test('选择器对象/查询变更不影响已创建节点，动作仍用原快照', async () => {
  const initial = { text: '日志' };
  const query = select(initial);
  initial.text = '第二项';
  const node = await query.firstOrNull();
  assert.equal(node.text, '日志');

  query.text('第二项');
  assert.equal(node.text, '日志');
  assert.equal(node.click(), true);
  const clickRequest = last('clickNode');
  assert.equal(clickRequest.selector.text, '日志');
  assert.deepEqual(clickRequest.selector.path, [0, 0]);
});

test('页面变化后动作失败返回 false，绝不坐标兜底', async () => {
  const node = await select({ text: '日志' }).firstOrNull();
  const live = state.tree.find((raw) => samePath(raw.path, [0, 0]));
  live.text = '页面已变化';

  assert.equal(node.click(), false);
  assert.equal(requests('click').length, 0);
  assert.equal(requests('swipe').length, 0);
  assert.deepEqual(last('clickNode').selector.path, [0, 0]);
  assert.equal(last('clickNode').selector.text, '日志');
});

test('scroll 发送原生节点动作并保护身份，失败不伪造手势', async () => {
  const list = await select({id:'list'}).firstOrNull();
  assert.equal(list.scrollForward(), true);
  assert.equal(last('nodeAction').actionName, 'scrollForward');
  assert.deepEqual(last('nodeAction').selector.path, [2]);
  state.tree.find(raw => samePath(raw.path,[2])).className = 'Changed';
  assert.equal(list.scrollBackward(), false);
  assert.equal(requests('swipe').length, 0);
});

test('setText：空串成功并更新 snapshot，非输入控件返回 false', async () => {
  const edit = await select({ id: 'input' }).firstOrNull();
  assert.equal(edit.editable, true);
  assert.equal(edit.setText(''), true);
  const request = last('setTextNode');
  assert.equal(request.text, '');
  assert.equal(request.index, 0);
  assert.equal(request.selector.text, '旧文本');
  assert.equal(edit.text, '');

  // 更新后的 snapshot 可直接继续动作，空 text 也作为身份条件保留。
  assert.equal(edit.click(), true);
  assert.equal(last('clickNode').selector.text, '');

  resetState();
  const nonEditable = await select({ text: '日志' }).firstOrNull();
  assert.equal(nonEditable.setText('x'), false);
  assert.equal(requests('setTextNode').length, 0);
});

test('setText 宿主失败时 snapshot 不更新', async () => {
  const edit = await select({ id: 'input' }).firstOrNull();
  state.setTextResult = false;
  assert.equal(edit.setText('新文本'), false);
  assert.equal(edit.text, '旧文本');
});

// ---------------------------------------------------------------------------
// 异步等待
// ---------------------------------------------------------------------------
test('findFirst 异步轮询用 setTimeout，期间其它 timer 能运行', async () => {
  state.getNodeInfoMisses = 1;
  let timerRan = false;
  const timer = setTimeout(() => { timerRan = true; }, 5);
  const node = await select({ text: '日志' }).findFirst({ timeout: 500 });
  clearTimeout(timer);
  assert.ok(timerRan, '轮询阻塞了事件循环');
  assert.ok(node);
  assert.equal(node.text, '日志');
  assert.equal(requests('getNodeInfo').length, 2);
});

test('timeout 0 只尝试一次；firstOrNull 也只尝试一次', async () => {
  state.getNodeInfoMisses = 5;
  const node = await select({ text: '日志' }).findFirst({ timeout: 0 });
  assert.equal(node, null);
  assert.equal(requests('getNodeInfo').length, 1);

  resetState();
  state.getNodeInfoMisses = 5;
  const first = await select({ text: '日志' }).firstOrNull();
  assert.equal(first, null);
  assert.equal(requests('getNodeInfo').length, 1);

  resetState();
  const empty = await select({ text: '不存在' }).atLeast(1).timeout(0).all();
  assert.deepEqual(empty, []);
});

test('maxRetries 是首次尝试后的重试上限', async () => {
  state.getNodeInfoMisses = 99;
  const started = Date.now();
  const node = await select({ text: '日志' }).findFirst({ timeout: 5000, maxRetries: 2 });
  assert.equal(node, null);
  assert.equal(requests('getNodeInfo').length, 3);
  assert.ok(Date.now() - started < 2000);

  resetState();
  const nodes = await select({ text: '不存在' }).atLeast(1).timeout(5000).maxRetries(2).all();
  assert.deepEqual(nodes, []);
  assert.equal(requests('findNodes').length, 3);
});

test('findFirst 非法参数 reject，且不发宿主请求', async () => {
  await assert.rejects(select({ text: '日志' }).findFirst({ timeout: 100, foo: 1 }), /不支持字段/);
  await assert.rejects(select({ text: '日志' }).findFirst(-1), /必须 >= 0/);
  await assert.rejects(select({ text: '日志' }).findFirst('100'), /毫秒数/);
  assert.equal(requests(null).length, 0);
});

// ---------------------------------------------------------------------------
// 错误传播
// ---------------------------------------------------------------------------
test('waitFor 只对 NotFound 返回 false，宿主错误原样上抛', async () => {
  state.errorActions.add('getNodeInfo');
  assert.throws(() => select({ text: '日志' }).waitFor(0), /getNodeInfo exploded/);
  await assert.rejects(select({ text: '日志' }).firstOrNull(), /getNodeInfo exploded/);
  await assert.rejects(select({ text: '日志' }).findFirst({ timeout: 100 }), /getNodeInfo exploded/);
  state.errorActions.delete('getNodeInfo');

  state.errorActions.add('findNodes');
  await assert.rejects(select({ text: '日志' }).all(), /findNodes exploded/);
  state.errorActions.delete('findNodes');

  assert.equal(select({ text: '不存在' }).waitFor(0), false);
});

// ---------------------------------------------------------------------------
// 正则 / root 缺失 / 别名
// ---------------------------------------------------------------------------
test('五个字段支持 RegExp，global 正则 lastIndex 被重置', async () => {
  const repeated = select({ text: /第.项/g });
  assert.equal((await repeated.firstOrNull()).text, '第一项');
  assert.equal((await repeated.firstOrNull()).text, '第一项');
  assert.equal((await repeated.firstOrNull()).text, '第一项');

  assert.equal((await select({ id: /symbol/i }).firstOrNull()).id, 'com.demo:id/symbolBar');
  assert.equal((await select({ className: /\.EditText$/ }).firstOrNull()).id, 'com.demo:id/input');
  assert.equal((await select({ packageName: /^com\.demo$/ }).firstOrNull()).path.length, 0);
  assert.equal((await select({ desc: /Symbol/ }).firstOrNull()).desc, 'Symbol A');
});

test('textMatches 按宿主语义整串匹配', async () => {
  assert.equal((await select({ textMatches: '日志' }).firstOrNull()).text, '日志');
  assert.equal(await select({ textMatches: '日' }).firstOrNull(), null);
});

test('Java 正则与本地谓词组合仍由宿主匹配，不转译成 JS 正则', async () => {
  state.findNodesOverride = selector => {
    assert.equal(selector.textMatches, '(?i)\\Q日志\\E');
    return [deepClone(state.tree.find(n => n.text === '日志'))];
  };
  const nodes = await select({ textMatches: '(?i)\\Q日志\\E' }).where(n => n.clickable).all();
  assert.deepEqual(nodes.map(n => n.text), ['日志']);
  assert.equal(requests('findNodes').length, 1);
});

test('空文本身份条件不能在动作中丢弃', async () => {
  const live = state.tree.find(n => n.editable);
  live.text = '';
  const node = await select({ id: live.id }).firstOrNull();
  live.text = 'changed externally';
  assert.equal(node.setText('must not write'), false);
  assert.equal(last('setTextNode').selector.text, '');
  assert.equal(live.text, 'changed externally');
});

test('子节点 tree 仅返回自身子树；本地过滤也执行宿主参数范围校验', async () => {
  const panel = await select({ id: 'panel' }).firstOrNull();
  assert.deepEqual(panel.tree().map(n => n.path), [[4], [4, 0], [4, 1]]);
  assert.throws(() => select({ depth: 64 }).where(() => true), RangeError);
  assert.throws(() => select({ index: 8192 }).where(() => true), RangeError);
  assert.throws(() => select({ path: Array(64).fill(0) }), RangeError);
  assert.throws(() => select({ bounds: { left: 5, top: 0, right: 1, bottom: 10 } }), RangeError);
  assert.throws(() => select({ bounds: { left: 0, top: 0, right: 10000001, bottom: 10 } }), RangeError);
});

test('树缺少根节点时 root() 返回 null，不伪造根', async () => {
  state.tree = state.tree.filter((raw) => raw.path.length > 0);
  assert.equal(await accessibility.root(), null);
  assert.ok(Array.isArray(accessibility.tree()));
  assert.equal(accessibility.tree().length, 13);
});

test('toggleRecents/openNotifications 别名 legacy，lockScreen 转发宿主结果', async () => {
  await accessibility.toggleRecents();
  assert.equal(last('recents').action, 'recents');
  await accessibility.openNotifications();
  assert.equal(last('notifications').action, 'notifications');
  assert.throws(() => accessibility.lockScreen(), /宿主返回 false/);
  assert.equal(state.calls.filter(call => call.kind === 'accessibility' && call.action === 'lockScreen').length, 1);
});

test('打印节点数组只显示节点摘要，不重复展开每个节点持有的整棵树', async () => {
  state.tree = [makeNode([], 'android.widget.FrameLayout', '', { childCount: 1000 })];
  for (let i = 0; i < 1000; i++) {
    state.tree.push(makeNode([i], 'android.widget.TextView', '节点' + i));
  }
  const nodes = await select().where(node => node.depth === 1).atMost(8).all();
  const text = util.format('节点: %O', nodes);
  assert.ok(text.length < 3000, '日志随整棵树膨胀: ' + text.length);
  assert.ok(text.split('\n').length <= 10);
  assert.ok(text.includes('节点7'));
  assert.ok(!text.includes('_tree'));
  assert.equal(nodes[0].parent().children().length, 1000, '摘要不应破坏节点导航');
  assert.equal(nodes[0].tree().length, 1);
});

test('节点日志转义换行并限制长文本，属性和raw仍保留完整原文', async () => {
  const value = '第一行\n' + '正文'.repeat(400);
  state.tree[2].text = value;
  const node = await select({ id: 'title' }).firstOrNull();
  const legacy = select({ id: 'title' }).findOnce();
  for (const result of [node, legacy]) {
    const text = util.format('%O', result);
    assert.equal(text.split('\n').length, 1);
    assert.ok(text.length < 600);
    assert.ok(text.includes('…'));
    assert.equal(result.raw().text, value);
  }
  assert.equal(node.text, value);
  assert.equal(legacy.text(), value);
  state.tree[2].text = 'a'.repeat(199) + '😀' + '长文本'.repeat(100);
  const emojiNode = await select({ id: 'title' }).firstOrNull();
  const preview = JSON.parse(util.format('%O', emojiNode).slice('NodeView '.length));
  assert.equal(preview.text, 'a'.repeat(199) + '…', '摘要截断不得拆开 UTF-16 代理对');
});

test('新增节点动作逐个转发且宿主false保持false', async () => {
  const node = await select({id:'title'}).firstOrNull();
  for (const method of ['copy','cut','paste','select','collapse','expand','dismiss','show','scrollUp','scrollDown','scrollLeft','scrollRight','contextClick','focus','clearFocus','accessibilityFocus','clearAccessibilityFocus']) {
    assert.equal(node[method](), true, method);
    assert.equal(last('nodeAction').actionName,method);
    assert.deepEqual(last('nodeAction').arguments, []);
    assert.equal(last('nodeAction').selector.id,'com.demo:id/title');
  }
  state.failNodeAction=true;
  assert.equal(node.focus(),false);
  state.errorActions.add('nodeAction');
  assert.throws(()=>node.focus(), /exploded/);
});
test('带参数动作完整转发，整数溢出/颠倒选区/非有限进度不得调用宿主', async () => {
  const node = await select({id:'input'}).firstOrNull();
  assert.equal(node.setSelection(0,3),true);assert.deepEqual(last('nodeAction').arguments,[0,3]);
  assert.equal(node.scrollTo(2,3),true);assert.deepEqual(last('nodeAction').arguments,[2,3]);
  assert.equal(node.setProgress(0.5),true);assert.deepEqual(last('nodeAction').arguments,[0.5]);
  const before=requests('nodeAction').length;
  for(const fn of [()=>node.setSelection(4,1),()=>node.setSelection(0,2**32),()=>node.scrollTo(2**32,0),()=>node.setProgress(Infinity),()=>node.setProgress(1e100)])assert.throws(fn);
  assert.equal(requests('nodeAction').length,before);
});
test('NodeView的范围查找保持属性式，legacy保持方法式，findByText使用包含匹配', async () => {
  const view=await select({id:'panel'}).firstOrNull();
  assert.equal(typeof view.findOne({id:'p1'}).text,'string');
  assert.deepEqual(view.findByText('面板').map(n=>n.text),['面板A','面板B']);
  assert.equal(view.findByViewId('p2')[0].text,'面板B');
  const node=select({id:'panel'}).findOnce();
  assert.equal(typeof node.findOne({id:'p1'}).text,'function');
});
test('child使用真实下标而非压紧后的数组序号', async () => {
  state.tree=state.tree.filter(raw=>!samePath(raw.path,[1,0]));
  const view=await select({id:'symbolBar'}).firstOrNull();
  assert.equal(view.child(0),null);assert.equal(view.child(1).id,'com.demo:id/btnB');
  const node=select({id:'symbolBar'}).findOnce();
  assert.equal(node.child(0),null);assert.equal(node.child(1).id(),'com.demo:id/btnB');
});
test('密码节点读取和setText后不泄露文本或把明文放进保护选择器', async () => {
  const raw=state.tree.find(raw=>raw.editable);raw.password=true;raw.text='before-secret';
  const node=await select({id:'input'}).firstOrNull();assert.equal(node.text,'');
  assert.equal(node.setText('after-secret'),true);assert.equal(node.text,'');
  assert.ok(!Object.hasOwn(last('setTextNode').selector,'text'));
  assert.ok(!util.format('%O',node).includes('after-secret'));
});
test('新增布尔和集合字段可读取/本地过滤，不下发宿主未知字段', async () => {
  Object.assign(state.tree[2],{accessibilityFocused:true,isHierarchically:true,checked:true,row:2,column:3,rowCount:4,columnCount:5,rowSpan:1,columnSpan:1});
  const v=await select({checked:true,row:2,column:3}).firstOrNull();assert.equal(v.id,'com.demo:id/title');
  assert.equal(v.rowCount,4);assert.equal(v.accessibilityFocused,true);assert.equal(v.isHierarchically,true);
  assert.equal(select({id:"title"}).findOnce().isHierarchically(),true);
  assert.equal(select().checked().findOnce().row(),2);
  assert.equal(requests('findNodes').length,0);
});
test('未持有完整树时，旧path被其他节点复用不能返回其父/子或范围结果', async () => {
  const node=await select({id:'title'}).firstOrNull();
  state.tree[2].id='different';
  assert.equal(node.parent(),null);assert.deepEqual(node.children(),[]);assert.deepEqual(node.find({}),[]);
});

test('find集合保留Array并补齐动作，选择器动作执行全部结果', () => {
  const nodes=select({id:'title'}).find();
  assert.ok(Array.isArray(nodes));assert.equal(nodes.size(),1);assert.equal(nodes.empty(),false);
  assert.equal(nodes.get(0).text(),'日志');assert.equal(nodes.get(3),null);
  assert.equal(nodes.click(),true);assert.equal(select({id:'title'}).click(),true);
  assert.equal(select({id:'title'}).untilFind(0).size(),1);
  assert.throws(()=>select({id:'missing'}).untilFind(0), /未找到/);
});
test('空集合参数同样校验，非法参数不能伪装动作成功', () => {
  const empty=select({id:'missing'}).find();assert.equal(empty.size(),0);assert.equal(empty.click(),true);
  const before=requests('nodeAction').length;
  for(const fn of [()=>empty.setSelection(3,1),()=>empty.scrollTo(2**32,0),()=>empty.setProgress(1e100),()=>select({id:'missing'}).setSelection(3,1)])assert.throws(fn);
  assert.equal(requests('nodeAction').length,before);
});
test('clickCenter拒绝畸形边界，不把null或字符串转换成坐标0', () => {
  const node=select({id:'title'}).findOnce();
  const raw=state.tree.find(raw=>raw.id==='com.demo:id/title');
  for(const bounds of [[null,0,100,100],['0',0,100,100],[0,0,2**32,100]]){
    raw.bounds=bounds;assert.equal(node.clickCenter(),false);
  }
  assert.equal(requests('click').length,0);
});

test('现代all和两种节点children保留Array并提供集合方法', async () => {
  const all=await select({id:'panel'}).all();assert.ok(Array.isArray(all));assert.equal(all.size(),1);
  const children=all.get(0).children();assert.ok(Array.isArray(children));assert.equal(children.size(),2);
  assert.equal(typeof children.get(0).text,'string');
  const legacy=select({id:'panel'}).findOnce().children();assert.equal(legacy.size(),2);assert.equal(typeof legacy.get(0).text,'function');
});

test('全流程没有反射/Java 宿主请求', async () => {
  await select({ text: '日志' }).firstOrNull();
  await select({ text: /日志/ }).all();
  assert.ok(state.calls.length > 0);
  const nonAutomation = state.calls.filter((call) => call.kind !== 'automation');
  assert.deepEqual(nonAutomation, []);
});

// ---------------------------------------------------------------------------
(async function main() {
  let passed = 0;
  let failed = 0;
  for (const item of tests) {
    resetState();
    try {
      await item.fn();
      assert.deepEqual(state.protocolErrors, [], '宿主协议错误: ' + state.protocolErrors.join('; '));
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
