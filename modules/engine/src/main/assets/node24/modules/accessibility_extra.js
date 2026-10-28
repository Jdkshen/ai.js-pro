/*
 * Node24 内置模块：accessibility 选择器/节点扩展。
 *
 * 设计要点：
 *   - 只走宿主协议 globalThis.__ajsHostValue({kind:'automation', action, ...})，
 *     不再反射 AutomationHostClient / 不单独 connect（旧实现会泄漏 Binder 连接）。
 *   - 纯可下发条件走 findNodes / getNodeInfo；含 RegExp、childCount、where、from 等
 *     本地条件时拉一次 getLatestNodeDump 在 JS 侧过滤（同一棵树，避免索引错位）。
 *   - 方法式 UiSelector/UiObject 面保持兼容；新增属性式 NodeView 面（select().firstOrNull()、
 *     findFirst()、where().atMost().all()、root()、tree()）。
 *   - 节点动作只使用快照 path + id/className/packageName/text/desc 组成保护选择器，index=0；
 *     页面变化/选择器变化时由宿主重新匹配失败返回 false，绝不做坐标点击兜底。
 *
 * 本文件是独立 IIFE，可由 Node24Prelude 在 prelude 之后拼接加载（见同目录 REGISTER-PATCH.md）。
 */
(function () {
  'use strict';
  if (typeof globalThis.__ajsRegisterModule !== 'function') { return; }
  var legacy = (globalThis.__ajsBuiltinModules && globalThis.__ajsBuiltinModules.accessibility) || {};
  var POLL_INTERVAL_MILLIS = 250;
  var DEFAULT_TIMEOUT_MILLIS = 10000;
  var MAX_TIMER_DELAY = 2147483647;
  var HOST_FIELDS = ['id', 'text', 'textContains', 'textStartsWith', 'textMatches', 'desc',
    'descContains', 'className', 'packageName', 'clickable', 'enabled', 'scrollable',
    'depth', 'index', 'bounds', 'path', 'algorithm'];
  var ALL_FIELDS = HOST_FIELDS.concat(['childCount', 'editable', 'indexInParent', 'checkable', 'checked', 'focusable', 'focused', 'selected', 'longClickable', 'visibleToUser', 'password', 'accessibilityFocused', 'contextClickable', 'dismissable', 'contentInvalid', 'multiLine', 'drawingOrder', 'row', 'column', 'rowCount', 'columnCount', 'rowSpan', 'columnSpan', 'isHierarchically']);
  var REGEXP_FIELDS = ['id', 'text', 'desc', 'className', 'packageName'];
  var STRING_FIELDS = ['textContains', 'textStartsWith', 'textMatches', 'descContains'];
  var BOOLEAN_FIELDS = ['clickable', 'enabled', 'scrollable', 'editable', 'checkable', 'checked', 'focusable', 'focused', 'selected', 'longClickable', 'visibleToUser', 'password', 'accessibilityFocused', 'contextClickable', 'dismissable', 'contentInvalid', 'multiLine', 'isHierarchically'];
  var INTEGER_FIELDS = ['depth', 'index', 'indexInParent', 'drawingOrder', 'row', 'column', 'rowCount', 'columnCount', 'rowSpan', 'columnSpan'];
  var EXTRA_INTEGER_FIELDS = ['drawingOrder', 'row', 'column', 'rowCount', 'columnCount', 'rowSpan', 'columnSpan'];
  var COMPARISON_OPERATORS = ['>', '>=', '<', '<=', '==', '===', '!=', '!=='];

  function apiError(message) {
    var error = new Error('[Auto.js] ' + message);
    error.name = 'AutoJsAccessibilityError';
    return error;
  }
  function typeError(message) { return new TypeError('[Auto.js] ' + message); }
  function rangeError(message) { return new RangeError('[Auto.js] ' + message); }
  function isSafeInteger(value) {
    return typeof value === 'number' && isFinite(value)
      && Math.floor(value) === value && Math.abs(value) <= 9007199254740991;
  }
  function requireInt(value, label, min) {
    if (!isSafeInteger(value)) {
      throw typeError('accessibility.' + label + ' 必须是整数，收到：' + String(value));
    }
    if (min != null && value < min) {
      throw rangeError('accessibility.' + label + ' 必须 >= ' + min + '，收到：' + value);
    }
    return value;
  }
  function requireBoolean(value, label) {
    if (typeof value !== 'boolean') {
      throw typeError('accessibility.' + label + ' 必须是 boolean，收到：' + String(value));
    }
    return value;
  }
  function sleepSync(ms) {
    try {
      if (typeof SharedArrayBuffer === 'function' && typeof Atomics !== 'undefined') {
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
        return;
      }
    } catch (ignored) { /* 回退到忙等 */ }
    var deadline = Date.now() + ms;
    while (Date.now() < deadline) { /* busy wait */ }
  }

  /* ------------------------------- 宿主调用 ------------------------------ */
  function hostValue(request) {
    var fn = globalThis.__ajsHostValue;
    if (typeof fn !== 'function') {
      throw apiError('__ajsHostValue 未就绪，无法使用无障碍选择器（需要在 Node24 引擎中运行）');
    }
    // 宿主错误（AutoJsHostError）原样向上传，不在这里吞掉/改写。
    return fn(request);
  }
  function automation(action, fields) {
    var request = { kind: 'automation', action: action };
    if (fields) {
      for (var key in fields) {
        if (Object.prototype.hasOwnProperty.call(fields, key)) { request[key] = fields[key]; }
      }
    }
    return hostValue(request);
  }
  function nodePathOrThrow(raw) {
    if (!raw || !Array.isArray(raw.path)) {
      throw apiError('宿主返回的节点缺少 path 数组，无法安全定位（拒绝坐标兜底）');
    }
    return raw.path;
  }
  function cloneRaw(raw) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
      throw apiError('宿主返回的节点必须是对象，收到：' + Object.prototype.toString.call(raw));
    }
    var out = {};
    for (var key in raw) {
      if (Object.prototype.hasOwnProperty.call(raw, key)) { out[key] = raw[key]; }
    }
    var path = nodePathOrThrow(raw);
    for (var i = 0; i < path.length; i++) { requireInt(path[i], 'node.path[]', 0); }
    out.path = path.slice();
    if (Array.isArray(raw.bounds)) { out.bounds = raw.bounds.slice(); }
    if (out.password === true) out.text = '';
    return out;
  }
  function normalizeNode(value, label) {
    if (value == null) { return null; }
    if (typeof value === 'string') {
      var text = value.trim();
      if (text === '' || text === 'null') { return null; }
      try { value = JSON.parse(text); }
      catch (error) { throw apiError(label + ' 返回非法节点 JSON：' + ((error && error.message) || error)); }
      if (value == null) { return null; }
    }
    if (typeof value !== 'object' || Array.isArray(value)) {
      throw apiError(label + ' 返回值不是节点对象：' + Object.prototype.toString.call(value));
    }
    return cloneRaw(value);
  }
  function normalizeNodes(value, label) {
    if (value == null || value === '') { return []; }
    if (typeof value === 'string') {
      try { value = JSON.parse(value); }
      catch (error) { throw apiError(label + ' 返回非法节点数组 JSON：' + ((error && error.message) || error)); }
    }
    if (!Array.isArray(value)) {
      throw apiError(label + ' 必须返回节点数组，收到：' + Object.prototype.toString.call(value));
    }
    var result = [];
    for (var i = 0; i < value.length; i++) {
      var raw = normalizeNode(value[i], label + '[' + i + ']');
      if (raw) { result.push(raw); }
    }
    return result;
  }

  /* ------------------------------- Rect --------------------------------- */
  function Rect(left, top, right, bottom) {
    this.left = left; this.top = top; this.right = right; this.bottom = bottom;
  }
  Object.defineProperties(Rect.prototype, {
    width: { enumerable: true, get: function () { return this.right - this.left; } },
    height: { enumerable: true, get: function () { return this.bottom - this.top; } },
    centerX: { enumerable: true, get: function () { return Math.floor((this.left + this.right) / 2); } },
    centerY: { enumerable: true, get: function () { return Math.floor((this.top + this.bottom) / 2); } }
  });
  Rect.prototype.toString = function () {
    return 'Rect(' + this.left + ', ' + this.top + ' - ' + this.right + ', ' + this.bottom + ')';
  };
  function boundsFromArray(bounds) {
    if (!Array.isArray(bounds) || bounds.length < 4) { return new Rect(0, 0, 0, 0); }
    return new Rect(Number(bounds[0]) || 0, Number(bounds[1]) || 0,
      Number(bounds[2]) || 0, Number(bounds[3]) || 0);
  }

  /* --------------------------- 节点属性读取 ------------------------------ */
  function rawString(raw, key) {
    var value = raw && raw[key];
    return value == null ? '' : String(value);
  }
  function rawBoolean(raw, key) { return !!(raw && raw[key] === true); }
  function rawInt(raw, key, fallback) {
    var value = raw && raw[key];
    return typeof value === 'number' && isFinite(value) && Math.floor(value) === value
      ? value : fallback;
  }
  function rawPath(raw) {
    return raw && Array.isArray(raw.path) ? raw.path : [];
  }
  function pathPrefixMatches(path, prefix) {
    if (!Array.isArray(path) || !Array.isArray(prefix) || path.length < prefix.length) { return false; }
    for (var i = 0; i < prefix.length; i++) { if (path[i] !== prefix[i]) { return false; } }
    return true;
  }
  function isStrictDescendant(path, parentPath) {
    return Array.isArray(path) && Array.isArray(parentPath)
      && path.length > parentPath.length && pathPrefixMatches(path, parentPath);
  }
  function directChildrenRaws(dump, parentPath) {
    var result = [];
    for (var i = 0; i < dump.length; i++) {
      var path = dump[i].path;
      if (Array.isArray(path) && path.length === parentPath.length + 1
        && pathPrefixMatches(path, parentPath)) {
        result.push(dump[i]);
      }
    }
    return result;
  }
  function countChildren(raw, tree) {
    var explicit = rawInt(raw, 'childCount', null);
    if (explicit != null) { return explicit; }
    if (Array.isArray(tree)) {
      return directChildrenRaws(tree, rawPath(raw)).length;
    }
    return 0;
  }
  function indexInParentOf(raw) {
    var explicit = rawInt(raw, 'indexInParent', null);
    if (explicit != null) { return explicit; }
    var path = rawPath(raw);
    return path.length ? path[path.length - 1] : -1;
  }
  function depthOf(raw) {
    var explicit = rawInt(raw, 'depth', null);
    return explicit != null ? explicit : rawPath(raw).length;
  }
  function findRawByPath(dump, path) {
    for (var i = 0; i < dump.length; i++) {
      if (pathPrefixMatches(dump[i].path, path) && dump[i].path.length === path.length) {
        return dump[i];
      }
    }
    return null;
  }
  function findRootRaw(dump) {
    for (var i = 0; i < dump.length; i++) {
      if (Array.isArray(dump[i].path) && dump[i].path.length === 0) { return dump[i]; }
    }
    return null;
  }

  /* --------------------------- 选择器校验 -------------------------------- */
  function isRegExp(value) {
    return value instanceof RegExp
      || (value && typeof value === 'object' && Object.prototype.toString.call(value) === '[object RegExp]');
  }
  function isPlainObject(value) {
    return !!value && typeof value === 'object' && !Array.isArray(value)
      && Object.prototype.toString.call(value) === '[object Object]';
  }
  function copyRegExp(value) { return new RegExp(value.source, value.flags); }
  function copyBounds(value) {
    return { left: value.left, top: value.top, right: value.right, bottom: value.bottom };
  }
  function copyComparison(value) {
    var out = {};
    for (var key in value) {
      if (Object.prototype.hasOwnProperty.call(value, key)) { out[key] = value[key]; }
    }
    return out;
  }
  function cloneSpec(spec) {
    var out = {};
    for (var key in spec) {
      if (!Object.prototype.hasOwnProperty.call(spec, key)) { continue; }
      var value = spec[key];
      if (isRegExp(value)) { out[key] = copyRegExp(value); }
      else if (key === 'path') { out[key] = value.slice(); }
      else if (key === 'bounds') { out[key] = copyBounds(value); }
      else if (key === 'childCount' && value && typeof value === 'object') {
        out[key] = copyComparison(value);
      } else { out[key] = value; }
    }
    return out;
  }
  function parseBounds(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      throw typeError('选择器 bounds 必须是 {left,top,right,bottom} 对象');
    }
    var keys = Object.keys(value);
    if (keys.length !== 4) {
      throw typeError('选择器 bounds 必须且只能包含 left/top/right/bottom 四个整数坐标');
    }
    var expected = ['left', 'top', 'right', 'bottom'];
    var out = {};
    for (var i = 0; i < expected.length; i++) {
      var key = expected[i];
      if (!Object.prototype.hasOwnProperty.call(value, key) || !isSafeInteger(value[key])) {
        throw typeError('选择器 bounds.' + key + ' 必须是整数，收到：' + String(value[key]));
      }
      out[key] = value[key];
      if (Math.abs(out[key]) > 10000000) { throw rangeError('选择器 bounds 坐标绝对值不能超过 10000000'); }
    }
    for (var j = 0; j < keys.length; j++) {
      if (expected.indexOf(keys[j]) < 0) {
        throw typeError('选择器 bounds 不支持字段：' + keys[j]);
      }
    }
    if (out.right < out.left || out.bottom < out.top) {
      throw rangeError('选择器 bounds 必须满足 right >= left、bottom >= top');
    }
    return out;
  }
  function parseComparison(value, label) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      throw typeError('选择器 ' + label + ' 比较条件必须是对象，如 {">":0}');
    }
    var keys = Object.keys(value);
    if (keys.length !== 1) {
      throw typeError('选择器 ' + label + ' 比较条件必须且只能有一个操作符');
    }
    var operator = keys[0];
    if (COMPARISON_OPERATORS.indexOf(operator) < 0) {
      throw typeError('选择器 ' + label + ' 不支持比较操作符：' + operator);
    }
    if (!isSafeInteger(value[operator])) {
      throw typeError('选择器 ' + label + ' 的比较值必须是整数，收到：' + String(value[operator]));
    }
    var out = {};
    out[operator] = value[operator];
    return out;
  }
  function parseSpecField(key, value) {
    if(key==='algorithm') {
      if(typeof value!=='string' || !/^(BFS|DFS)$/i.test(value)) throw typeError('algorithm 只支持 BFS/DFS');
      return value.toUpperCase();
    }

    if (REGEXP_FIELDS.indexOf(key) >= 0) {
      if (typeof value === 'string') { return value; }
      if (isRegExp(value)) { return copyRegExp(value); }
      throw typeError('选择器 ' + key + ' 必须是字符串或 RegExp，收到：' + String(value));
    }
    if (STRING_FIELDS.indexOf(key) >= 0) {
      if (typeof value !== 'string') {
        throw typeError('选择器 ' + key + ' 必须是字符串，收到：' + String(value));
      }
      // textMatches is a Java Pattern, validated by the host at query time.
      return value;
    }
    if (BOOLEAN_FIELDS.indexOf(key) >= 0) {
      return requireBoolean(value, '选择器 ' + key);
    }
    if (INTEGER_FIELDS.indexOf(key) >= 0) {
      var constrained = key === 'depth' || key === 'index' || key === 'indexInParent';
      var integer = requireInt(value, '选择器 ' + key, constrained ? 0 : -2147483648);
      if ((key === 'depth' && integer > 63) || (constrained && key !== 'depth' && integer > 8191) || integer > 2147483647) {
        throw rangeError('选择器 ' + key + ' 超出宿主范围');
      }
      return integer;
    }
    if (key === 'path') {
      if (!Array.isArray(value)) { throw typeError('选择器 path 必须是整数数组（空数组合法）'); }
      if (value.length > 63) { throw rangeError('选择器 path 最多 63 项'); }
      var path = [];
      for (var i = 0; i < value.length; i++) {
        var part = requireInt(value[i], '选择器 path[' + i + ']', 0);
        if (part > 2147483647) { throw rangeError('选择器 path 子下标超出宿主范围'); }
        path.push(part);
      }
      return path;
    }
    if (key === 'bounds') { return parseBounds(value); }
    if (key === 'childCount') {
      if (typeof value === 'number') {
        return requireInt(value, '选择器 childCount', 0);
      }
      return parseComparison(value, 'childCount');
    }
    throw typeError('未知选择器字段：' + key);
  }
  function parseSpecObject(initial) {
    if (initial == null) { return {}; }
    if (!isPlainObject(initial)) {
      throw typeError('accessibility.select(...) 需要对象或 UiSelector');
    }
    var out = {};
    for (var key in initial) {
      if (!Object.prototype.hasOwnProperty.call(initial, key)) { continue; }
      if (ALL_FIELDS.indexOf(key) < 0) {
        throw typeError('未知选择器字段：' + key
          + '（支持 ' + ALL_FIELDS.join('/') + '）');
      }
      out[key] = parseSpecField(key, initial[key]);
    }
    return out;
  }
  function specHasLocal(spec) {
    for (var key in spec) {
      if (!Object.prototype.hasOwnProperty.call(spec, key)) { continue; }
      if (isRegExp(spec[key])) { return true; }
      if (HOST_FIELDS.indexOf(key) < 0) { return true; }
    }
    return false;
  }
  function hostSpec(spec) {
    var out = {};
    for (var key in spec) {
      if (!Object.prototype.hasOwnProperty.call(spec, key)) { continue; }
      if (isRegExp(spec[key])) { continue; }
      if (HOST_FIELDS.indexOf(key) < 0) { continue; }
      if (key === 'path') { out[key] = spec[key].slice(); }
      else if (key === 'bounds') { out[key] = copyBounds(spec[key]); }
      else { out[key] = spec[key]; }
    }
    return out;
  }

  /* --------------------------- 本地匹配 ---------------------------------- */
  function regexTest(regex, value) {
    // 全局/粘性正则的 lastIndex 会跨调用累积；每次测试前后都重置。
    regex.lastIndex = 0;
    var matched = regex.test(String(value == null ? '' : value));
    regex.lastIndex = 0;
    return matched;
  }
  function idMatches(actual, expected) {
    if (typeof expected === 'string') {
      if (actual === expected) { return true; }
      var slash = actual.indexOf('/');
      return slash >= 0 && actual.substring(slash + 1) === expected;
    }
    return regexTest(expected, actual);
  }
  function compareNumber(actual, comparison) {
    for (var op in comparison) {
      if (!Object.prototype.hasOwnProperty.call(comparison, op)) { continue; }
      var expected = comparison[op];
      switch (op) {
        case '>': return actual > expected;
        case '>=': return actual >= expected;
        case '<': return actual < expected;
        case '<=': return actual <= expected;
        case '==': return actual == expected; // eslint-disable-line eqeqeq
        case '===': return actual === expected;
        case '!=': return actual != expected; // eslint-disable-line eqeqeq
        case '!==': return actual !== expected;
        default: return false;
      }
    }
    return false;
  }
  function matchesSpec(raw, spec, tree, javaMatched) {
    var key;
    if (Object.prototype.hasOwnProperty.call(spec, 'path')) {
      var actualPath = rawPath(raw);
      var expectedPath = spec.path;
      if (actualPath.length !== expectedPath.length) { return false; }
      for (var p = 0; p < expectedPath.length; p++) {
        if (actualPath[p] !== expectedPath[p]) { return false; }
      }
    }
    if (Object.prototype.hasOwnProperty.call(spec, 'id')) {
      if (!idMatches(rawString(raw, 'id'), spec.id)) { return false; }
    }
    var text = rawString(raw, 'text');
    if (Object.prototype.hasOwnProperty.call(spec, 'text')) {
      if (isRegExp(spec.text)) { if (!regexTest(spec.text, text)) { return false; } }
      else if (text !== spec.text) { return false; }
    }
    if (Object.prototype.hasOwnProperty.call(spec, 'textContains')
      && text.indexOf(spec.textContains) < 0) { return false; }
    if (Object.prototype.hasOwnProperty.call(spec, 'textStartsWith')
      && text.indexOf(spec.textStartsWith) !== 0) { return false; }
    if (Object.prototype.hasOwnProperty.call(spec, 'textMatches') && !javaMatched) {
      throw apiError('textMatches 必须经过 Java 宿主匹配');
    }
    var desc = rawString(raw, 'desc');
    if (Object.prototype.hasOwnProperty.call(spec, 'desc')) {
      if (isRegExp(spec.desc)) { if (!regexTest(spec.desc, desc)) { return false; } }
      else if (desc !== spec.desc) { return false; }
    }
    if (Object.prototype.hasOwnProperty.call(spec, 'descContains')
      && desc.indexOf(spec.descContains) < 0) { return false; }
    var className = rawString(raw, 'className');
    if (Object.prototype.hasOwnProperty.call(spec, 'className')) {
      if (isRegExp(spec.className)) { if (!regexTest(spec.className, className)) { return false; } }
      else if (className !== spec.className) { return false; }
    }
    var packageName = rawString(raw, 'packageName');
    if (Object.prototype.hasOwnProperty.call(spec, 'packageName')) {
      if (isRegExp(spec.packageName)) { if (!regexTest(spec.packageName, packageName)) { return false; } }
      else if (packageName !== spec.packageName) { return false; }
    }
    for (var b = 0; b < BOOLEAN_FIELDS.length; b++) {
      key = BOOLEAN_FIELDS[b];
      if (Object.prototype.hasOwnProperty.call(spec, key)
        && rawBoolean(raw, key) !== spec[key]) { return false; }
    }
    for (var scalarIndex = 0; scalarIndex < EXTRA_INTEGER_FIELDS.length; scalarIndex++) {
      var scalar = EXTRA_INTEGER_FIELDS[scalarIndex];
      if (Object.prototype.hasOwnProperty.call(spec, scalar) && rawInt(raw, scalar, -1) !== spec[scalar]) return false;
    }
    if (Object.prototype.hasOwnProperty.call(spec, 'depth')
      && depthOf(raw) !== spec.depth) { return false; }
    if (Object.prototype.hasOwnProperty.call(spec, 'index')
      && indexInParentOf(raw) !== spec.index) { return false; }
    if (Object.prototype.hasOwnProperty.call(spec, 'indexInParent')
      && indexInParentOf(raw) !== spec.indexInParent) { return false; }
    if (Object.prototype.hasOwnProperty.call(spec, 'bounds')) {
      var bounds = raw.bounds;
      if (!Array.isArray(bounds) || bounds.length < 4) { return false; }
      if (bounds[0] !== spec.bounds.left || bounds[1] !== spec.bounds.top
        || bounds[2] !== spec.bounds.right || bounds[3] !== spec.bounds.bottom) {
        return false;
      }
    }
    if (Object.prototype.hasOwnProperty.call(spec, 'childCount')) {
      var count = countChildren(raw, tree);
      if (typeof spec.childCount === 'number') {
        if (count !== spec.childCount) { return false; }
      } else if (!compareNumber(count, spec.childCount)) { return false; }
    }
    return true;
  }

  /* --------------------------- 查询状态快照 ------------------------------ */
  function cloneState(state, seen) {
    seen = seen || [];
    if (seen.indexOf(state) >= 0) {
      throw typeError('from 选择器不能循环引用');
    }
    seen.push(state);
    if (state.pathOnly) {
      seen.pop();
      return { pathOnly: state.pathOnly.slice(), identity: state.identity ? cloneSpec(state.identity) : null };
    }
    var out = {
      spec: cloneSpec(state.spec || {}),
      where: (state.where || []).slice(),
      from: null,
      atMost: state.atMost == null ? null : state.atMost,
      atLeast: state.atLeast == null ? 0 : state.atLeast,
      timeout: state.timeout == null ? DEFAULT_TIMEOUT_MILLIS : state.timeout,
      maxRetries: state.maxRetries == null ? Infinity : state.maxRetries
    };
    out.from = state.from ? cloneState(state.from, seen) : null;
    seen.pop();
    return out;
  }
  function stateFromSelector(selector) {
    return {
      spec: selector._spec,
      where: selector._where,
      from: selector._from,
      atMost: selector._atMost,
      atLeast: selector._atLeast,
      timeout: selector._timeout,
      maxRetries: selector._maxRetries
    };
  }
  function snapshotState(selector, seen) {
    return cloneState(stateFromSelector(selector), seen);
  }
  function stateHasLocal(state) {
    return !!state.from || (state.where && state.where.length > 0) || specHasLocal(state.spec || {});
  }

  /* ----------------------------- NodeView -------------------------------- */
  function NodeView(options) {
    if (!options || !options.raw) {
      throw typeError('NodeView 只能由查询结果构造');
    }
    this._raw = cloneRaw(options.raw);
    this._tree = Array.isArray(options.tree) ? options.tree : null;
    this._fromPath = Array.isArray(options.fromPath) ? options.fromPath.slice() : null;
  }
  Object.defineProperties(NodeView.prototype, {
    text: { enumerable: true, get: function () { return rawString(this._raw, 'text'); } },
    desc: { enumerable: true, get: function () { return rawString(this._raw, 'desc'); } },
    id: { enumerable: true, get: function () { return rawString(this._raw, 'id'); } },
    className: { enumerable: true, get: function () { return rawString(this._raw, 'className'); } },
    packageName: { enumerable: true, get: function () { return rawString(this._raw, 'packageName'); } },
    bounds: { enumerable: true, get: function () { return boundsFromArray(this._raw.bounds); } },
    clickable: { enumerable: true, get: function () { return rawBoolean(this._raw, 'clickable'); } },
    enabled: { enumerable: true, get: function () { return rawBoolean(this._raw, 'enabled'); } },
    scrollable: { enumerable: true, get: function () { return rawBoolean(this._raw, 'scrollable'); } },
    editable: { enumerable: true, get: function () { return rawBoolean(this._raw, 'editable'); } },
    checkable: { enumerable: true, get: function () { return rawBoolean(this._raw, 'checkable'); } },
    checked: { enumerable: true, get: function () { return rawBoolean(this._raw, 'checked'); } },
    focusable: { enumerable: true, get: function () { return rawBoolean(this._raw, 'focusable'); } },
    focused: { enumerable: true, get: function () { return rawBoolean(this._raw, 'focused'); } },
    selected: { enumerable: true, get: function () { return rawBoolean(this._raw, 'selected'); } },
    longClickable: { enumerable: true, get: function () { return rawBoolean(this._raw, 'longClickable'); } },
    visibleToUser: { enumerable: true, get: function () { return rawBoolean(this._raw, 'visibleToUser'); } },
    password: { enumerable: true, get: function () { return rawBoolean(this._raw, 'password'); } },
    drawingOrder: { enumerable: true, get: function () { return rawInt(this._raw, 'drawingOrder', -1); } },
    boundsInParent: { enumerable: true, get: function () { return boundsFromArray(this._raw.boundsInParent); } },
    depth: { enumerable: true, get: function () { return depthOf(this._raw); } },
    indexInParent: { enumerable: true, get: function () { return indexInParentOf(this._raw); } },
    childCount: { enumerable: true, get: function () { return countChildren(this._raw, this._tree); } },
    path: { enumerable: true, get: function () { return rawPath(this._raw).slice(); } }
  });
  NodeView.prototype.raw = function () { return this._raw; };
  NodeView.prototype.toString = function () {
    return 'NodeView(' + this.className + ', text=' + this.text + ')';
  };
  NodeView.prototype.tree = function () {
    var dump = this._tree || getDump();
    var path = rawPath(this._raw);
    return dump.filter(function (raw) { return pathPrefixMatches(rawPath(raw), path); }).map(function (raw) {
      return new NodeView({ raw: raw, tree: dump, fromPath: null });
    });
  };
  NodeView.prototype.root = function () {
    return Promise.resolve().then(fetchRootView);
  };
  NodeView.prototype.parent = function () {
    var path = rawPath(this._raw);
    if (path.length === 0) { return null; }
    var tree = navigationTree(this);
    var raw = findRawByPath(tree, path.slice(0, path.length - 1));
    return raw ? new NodeView({ raw: raw, tree: tree, fromPath: this._fromPath }) : null;
  };
  NodeView.prototype.child = function (index) {
    requireInt(index, 'child.index', 0);
    var tree = navigationTree(this);
    var raw = findRawByPath(tree, rawPath(this._raw).concat([index]));
    return raw ? new NodeView({ raw: raw, tree: tree, fromPath: this._fromPath }) : null;
  };
  NodeView.prototype.children = function () {
    var tree = navigationTree(this);
    var path = rawPath(this._raw);
    return nodeCollection(directChildrenRaws(tree, path).map(function (raw) {
      return new NodeView({ raw: raw, tree: tree, fromPath: path });
    }));
  };

  /* ------------------------------- Node ---------------------------------- */
  function Node(raw, spec, index, context) {
    this._raw = cloneRaw(raw || {});
    this._spec = cloneSpec(spec || {});
    this._index = index || 0;
    this._tree = context && Array.isArray(context.tree) ? context.tree : null;
    this._fromPath = context && Array.isArray(context.fromPath) ? context.fromPath.slice() : null;
  }
  Node.prototype.raw = function () { return this._raw; };
  Node.prototype.text = function () { return rawString(this._raw, 'text'); };
  Node.prototype.desc = function () { return rawString(this._raw, 'desc'); };
  Node.prototype.id = function () { return rawString(this._raw, 'id'); };
  Node.prototype.className = function () { return rawString(this._raw, 'className'); };
  Node.prototype.packageName = function () { return rawString(this._raw, 'packageName'); };
  Node.prototype.bounds = function () { return boundsFromArray(this._raw.bounds); };
  Node.prototype.clickable = function () { return rawBoolean(this._raw, 'clickable'); };
  Node.prototype.enabled = function () { return rawBoolean(this._raw, 'enabled'); };
  Node.prototype.scrollable = function () { return rawBoolean(this._raw, 'scrollable'); };
  Node.prototype.editable = function () { return rawBoolean(this._raw, 'editable'); };
  Node.prototype.checkable = function () { return rawBoolean(this._raw, 'checkable'); };
  Node.prototype.checked = function () { return rawBoolean(this._raw, 'checked'); };
  Node.prototype.focusable = function () { return rawBoolean(this._raw, 'focusable'); };
  Node.prototype.focused = function () { return rawBoolean(this._raw, 'focused'); };
  Node.prototype.selected = function () { return rawBoolean(this._raw, 'selected'); };
  Node.prototype.longClickable = function () { return rawBoolean(this._raw, 'longClickable'); };
  Node.prototype.visibleToUser = function () { return rawBoolean(this._raw, 'visibleToUser'); };
  Node.prototype.password = function () { return rawBoolean(this._raw, 'password'); };
  Node.prototype.drawingOrder = function () { return rawInt(this._raw, 'drawingOrder', -1); };
  Node.prototype.boundsInParent = function () { return boundsFromArray(this._raw.boundsInParent); };
  Node.prototype.depth = function () { return depthOf(this._raw); };
  Node.prototype.indexInParent = function () { return indexInParentOf(this._raw); };
  Node.prototype.path = function () { return rawPath(this._raw).slice(); };
  Node.prototype.toString = function () { return 'UiObject(' + this.className() + ', text=' + this.text() + ')'; };
  // A queried node retains its shared tree for parent/children navigation. Node's
  // console must not expand that same tree again for every result in an array.
  // Explicit raw()/tree() access remains available for full diagnostics.
  var inspectNode = Symbol.for('nodejs.util.inspect.custom');
  function nodePreview(raw) {
    function preview(value) {
      value = value == null ? '' : String(value);
      if (value.length <= 200) { return value; }
      var end = 200;
      var last = value.charCodeAt(end - 1);
      if (last >= 0xD800 && last <= 0xDBFF) { end--; }
      return value.slice(0, end) + '…';
    }
    return {
      className: rawString(raw, 'className'),
      id: rawString(raw, 'id'),
      text: preview(raw.text),
      desc: preview(raw.desc),
      bounds: raw.bounds,
      path: rawPath(raw).slice(),
      clickable: rawBoolean(raw, 'clickable'),
      childCount: rawInt(raw, 'childCount', null)
    };
  }
  Object.defineProperty(NodeView.prototype, inspectNode, {
    value: function () { return 'NodeView ' + JSON.stringify(nodePreview(this._raw)); }
  });
  Object.defineProperty(Node.prototype, inspectNode, {
    value: function () { return 'UiObject ' + JSON.stringify(nodePreview(this._raw)); }
  });
  Node.prototype.parent = function () {
    var path = rawPath(this._raw);
    if (path.length === 0) { return null; }
    var tree = navigationTree(this);
    var raw = findRawByPath(tree, path.slice(0, path.length - 1));
    return raw ? new Node(raw, { path: rawPath(raw).slice() }, 0, {
      tree: tree, fromPath: this._fromPath
    }) : null;
  };
  Node.prototype.children = function () {
    var tree = navigationTree(this);
    var path = rawPath(this._raw);
    return nodeCollection(directChildrenRaws(tree, path).map(function (raw) {
      return new Node(raw, { path: rawPath(raw).slice() }, 0, { tree: tree, fromPath: path });
    }));
  };
  Node.prototype.child = function (index) {
    requireInt(index, 'child.index', 0);
    var tree = navigationTree(this);
    var raw = findRawByPath(tree, rawPath(this._raw).concat([index]));
    return raw ? new Node(raw, { path: rawPath(raw).slice() }, 0,
      { tree: tree, fromPath: this._fromPath }) : null;
  };
  Node.prototype.childCount = function () {
    var explicit = rawInt(this._raw, 'childCount', null);
    return explicit != null ? explicit : this.children().length;
  };

  /* --------------------------- 节点动作（共用） --------------------------- */
  function identitySelector(node) {
    var raw = node._raw;
    var path = nodePathOrThrow(raw);
    var selector = { path: path.slice() };
    var fields = ['id', 'className', 'packageName', 'text', 'desc'];
    for (var i = 0; i < fields.length; i++) {
      var value = raw[fields[i]];
      if (fields[i] === 'text' && raw.password === true) continue;
      if (typeof value === 'string' && (fields[i] !== 'id' || value !== '')) {
        selector[fields[i]] = value;
      }
    }
    return selector;
  }
  function navigationTree(node) {
    if (node._tree) return node._tree;
    var dump = getDump(), current = findRawByPath(dump, rawPath(node._raw));
    return current && matchesSpec(current, identitySelector(node), dump, true) ? dump : [];
  }
  function actionBoolean(action, value) {
    if (typeof value !== 'boolean') {
      throw apiError(action + ' 返回值非法（期望 boolean）：' + String(value));
    }
    return value;
  }
  function performNodeAction(node, action) {
    var request = {
      kind: 'automation',
      action: action,
      selector: identitySelector(node),
      index: 0
    };
    return actionBoolean(action, hostValue(request));
  }
  /** Pro UiObject 的其余无参动作统一走主进程 nodeAction；老宿主返回 false。 */
  function performGenericAction(node, action) {
    var request = {
      kind: 'automation',
      action: 'nodeAction',
      selector: identitySelector(node),
      index: 0,
      actionName: action,
      arguments: []
    };
    return actionBoolean(action, hostValue(request));
  }
  function performGenericActionWithArgs(node, action, args) {
    var request = {
      kind: 'automation',
      action: 'nodeAction',
      selector: identitySelector(node),
      index: 0,
      actionName: action,
      arguments: args
    };
    return actionBoolean(action, hostValue(request));
  }
  function performSetText(node, text) {
    if (typeof text !== 'string') {
      throw typeError('setText 需要字符串，收到：' + String(text));
    }
    // 非输入控件明确返回 false，不伪造成功。
    if (node._raw.editable === false) { return false; }
    var request = {
      kind: 'automation',
      action: 'setTextNode',
      selector: identitySelector(node),
      index: 0,
      text: text
    };
    var result = actionBoolean('setTextNode', hostValue(request));
    if (result) { node._raw.text = node._raw.password === true ? '' : text; }
    return result;
  }
  function performScroll(node, forward) {
    // 宿主协议没有 scrollNode：先按 path+identity 重新确认节点仍在原处，再在它当前的
    // 边界内做显式手势。这样做不是 click/longClick/setText 失败后的坐标兜底。
    var selector = identitySelector(node);
    var current = normalizeNode(
      automation('getNodeInfo', { selector: selector, index: 0 }),
      'getNodeInfo');
    if (!current) { return false; }
    var bounds = boundsFromArray(current.bounds);
    if (bounds.width <= 0 || bounds.height <= 0) { return false; }
    var request = {
      kind: 'automation',
      action: 'swipe',
      x1: bounds.centerX,
      y1: forward ? bounds.bottom - 10 : bounds.top + 10,
      x2: bounds.centerX,
      y2: forward ? bounds.top + 10 : bounds.bottom - 10,
      duration: 300
    };
    return actionBoolean('swipe', hostValue(request));
  }
  function performClickCenter(node) {
    // Pro 8.8.17: click the center of the CURRENT bounds. Re-resolve the node first;
    // never fall back to a stale snapshot's coordinates after the page changed.
    var selector = identitySelector(node);
    var current = normalizeNode(
      automation('getNodeInfo', { selector: selector, index: 0 }),
      'getNodeInfo');
    if (!current) { return false; }
    if (!Array.isArray(current.bounds) || current.bounds.length !== 4
        || current.bounds.some(function (v) { return typeof v !== 'number' || !Number.isFinite(v) || Math.floor(v) !== v || v < -2147483648 || v > 2147483647; })) return false;
    var bounds = boundsFromArray(current.bounds);
    if (bounds.width <= 0 || bounds.height <= 0
        || bounds.left < 0 || bounds.top < 0) {
      return false;
    }
    return actionBoolean('click', hostValue({
      kind: 'automation',
      action: 'click',
      x: bounds.centerX,
      y: bounds.centerY
    }));
  }
  function rangeInfoOf(raw) {
    if (raw.rangeInfo == null) return null;
    var r=raw.rangeInfo;
    if (typeof r !== 'object' || Array.isArray(r)) throw apiError('rangeInfo 数据非法');
    ['min','max','current','type'].forEach(function(k){
      if(typeof r[k] !== 'number' || !Number.isFinite(r[k]) || Math.abs(r[k])>3.4028234663852886e38)
        throw apiError('rangeInfo.'+k+' 必须是有限数字');
    });
    if(r.type<0 || r.type>2147483647 || Math.floor(r.type)!==r.type) throw apiError('rangeInfo.type 必须是非负32位整数');
    var info={min:r.min,max:r.max,current:r.current,type:r.type};
    info.getMin=function(){return info.min;};info.getMax=function(){return info.max;};
    info.getCurrent=function(){return info.current;};info.getType=function(){return info.type;};
    return Object.freeze(info);
  }
  Object.defineProperty(NodeView.prototype,'rangeInfo',{enumerable:true,get:function(){return rangeInfoOf(this._raw);}});
  NodeView.prototype.getRangeInfo=Node.prototype.getRangeInfo=function(){return rangeInfoOf(this._raw);};
  function installNodeActions(proto) {
    proto.click = function () { return performNodeAction(this, 'clickNode'); };
    proto.clickCenter = function () { return performClickCenter(this); };
    proto.longClick = function () { return performNodeAction(this, 'longClickNode'); };
    proto.setText = function (text) { return performSetText(this, text); };
    proto.scrollForward = function () { return performGenericAction(this, 'scrollForward'); };
    proto.scrollBackward = function () { return performGenericAction(this, 'scrollBackward'); };
    proto.scrollUp = function () { return performGenericAction(this, 'scrollUp'); };
    proto.scrollDown = function () { return performGenericAction(this, 'scrollDown'); };
    proto.scrollLeft = function () { return performGenericAction(this, 'scrollLeft'); };
    proto.scrollRight = function () { return performGenericAction(this, 'scrollRight'); };
    proto.copy = function () { return performGenericAction(this, 'copy'); };
    proto.cut = function () { return performGenericAction(this, 'cut'); };
    proto.paste = function () { return performGenericAction(this, 'paste'); };
    proto.select = function () { return performGenericAction(this, 'select'); };
    proto.collapse = function () { return performGenericAction(this, 'collapse'); };
    proto.expand = function () { return performGenericAction(this, 'expand'); };
    proto.dismiss = function () { return performGenericAction(this, 'dismiss'); };
    proto.show = function () { return performGenericAction(this, 'show'); };
    proto.contextClick = function () { return performGenericAction(this, 'contextClick'); };
    proto.focus = function () { return performGenericAction(this, 'focus'); };
    proto.clearFocus = function () { return performGenericAction(this, 'clearFocus'); };
    proto.accessibilityFocus = function () { return performGenericAction(this, 'accessibilityFocus'); };
    proto.clearAccessibilityFocus = function () { return performGenericAction(this, 'clearAccessibilityFocus'); };
    proto.setSelection = function (start, end) {
      start = requireInt(start, 'setSelection.start', 0); end = requireInt(end, 'setSelection.end', 0);
      if (start > 2147483647 || end > 2147483647) throw rangeError('setSelection 参数超过 32 位整数');
      if (end < start) { throw typeError('setSelection 需要 start <= end'); }
      return performGenericActionWithArgs(this, 'setSelection', [start, end]);
    };
    proto.setProgress = function (value) {
      if (typeof value !== 'number' || !isFinite(value) || Math.abs(value) > 3.4028234663852886e38) {
        throw typeError('setProgress 需要有限数字');
      }
      return performGenericActionWithArgs(this, 'setProgress', [value]);
    };
    proto.scrollTo = function (row, column) {
      row = requireInt(row, 'scrollTo.row', 0); column = requireInt(column, 'scrollTo.column', 0);
      if (row > 2147483647 || column > 2147483647) throw rangeError('scrollTo 参数超过 32 位整数');
      return performGenericActionWithArgs(this, 'scrollTo', [row, column]);
    };
  }
  installNodeActions(Node.prototype);
  installNodeActions(NodeView.prototype);
  function installFindHelpers(proto) {
    var propertyView = proto === NodeView.prototype;
    proto.find = function (selector) {
      var state=snapshotState(select(selector).from(this));state.includeFromRoot=true;
      var nodes=executeAll(state);
      return nodeCollection(propertyView ? nodes : nodes.map(toNode));
    };
    proto.findOne = function (selector) {
      var state=snapshotState(select(selector).from(this));state.includeFromRoot=true;
      var node=executeAt(state,0);return propertyView ? node : node ? toNode(node) : null;
    };
    proto.findByText = function (text) { return this.find({ textContains: String(text) }); };
    proto.findByViewId = function (id) { return this.find({ id: String(id) }); };
  }
  installFindHelpers(Node.prototype);
  installFindHelpers(NodeView.prototype);

  // Scalar getters read the same host snapshot as the Java engines.
  ["accessibilityFocused", "contextClickable", "dismissable", "contentInvalid", "multiLine", "isHierarchically"].forEach(function (key) {
    Node.prototype[key] = function () { return rawBoolean(this._raw, key); };
    Object.defineProperty(NodeView.prototype, key, { enumerable: true, get: function () { return rawBoolean(this._raw, key); } });
  });
  EXTRA_INTEGER_FIELDS.forEach(function (key) {
    if (!Node.prototype[key]) Node.prototype[key] = function () { return rawInt(this._raw, key, key === "row" || key === "column" ? -1 : 0); };
    if (!Object.getOwnPropertyDescriptor(NodeView.prototype, key))
      Object.defineProperty(NodeView.prototype, key, { enumerable: true, get: function () { return rawInt(this._raw, key, key === "row" || key === "column" ? -1 : 0); } });
  });

  function validateCollectionAction(name, args) {
    if (name === 'setSelection' || name === 'scrollTo') {
      var first = requireInt(args[0], name + '.first', 0), second = requireInt(args[1], name + '.second', 0);
      if (first > 2147483647 || second > 2147483647) throw rangeError(name + ' 参数超过 32 位整数');
      if (name === 'setSelection' && first > second) throw typeError('setSelection 需要 start <= end');
    } else if (name === 'setProgress') {
      if (typeof args[0] !== 'number' || !Number.isFinite(args[0]) || Math.abs(args[0]) > 3.4028234663852886e38)
        throw typeError('setProgress 需要有限数字');
    }
  }
  function nodeCollection(nodes) {
    function method(name, fn) { Object.defineProperty(nodes, name, {value:fn, configurable:true}); }
    method('size', function () { return nodes.length; });
    method('empty', function () { return nodes.length === 0; });
    method('nonEmpty', function () { return nodes.length > 0; });
    method('get', function (index) { requireInt(index, 'collection.get.index', 0); return nodes[index] || null; });
    method('toArray', function () { return nodes.slice(); });
    ['click','clickCenter','longClick','setText','copy','cut','paste','select','collapse','expand','dismiss','show',
      'scrollForward','scrollBackward','scrollUp','scrollDown','scrollLeft','scrollRight','contextClick','focus',
      'clearFocus','accessibilityFocus','clearAccessibilityFocus','setSelection','scrollTo','setProgress'].forEach(function (name) {
      method(name, function () {
        var args = arguments, success = true;
        validateCollectionAction(name, args);
        for (var i = 0; i < nodes.length; i++) if (nodes[i][name].apply(nodes[i], args) !== true) success = false;
        return success;
      });
    });
    return nodes;
  }

  /* ----------------------------- 查询执行 -------------------------------- */
  function getDump() {
    return normalizeNodes(automation('getLatestNodeDump'), 'getLatestNodeDump');
  }
  function fetchRootView() {
    var dump = getDump();
    var raw = findRootRaw(dump);
    return raw ? new NodeView({ raw: raw, tree: dump, fromPath: null }) : null;
  }
  function collectParentPaths(state, dump) {
    if (state.pathOnly) {
      var parent = findRawByPath(dump, state.pathOnly);
      if (!parent || (state.identity && !matchesSpec(parent, state.identity, dump, true))) return [];
      return [state.pathOnly.slice()];
    }
    var parents = evaluateState(state, dump);
    var paths = [];
    for (var i = 0; i < parents.length; i++) { paths.push(rawPath(parents[i]._raw).slice()); }
    return paths;
  }
  function matchDumpTextPattern(pattern, dump) {
    var result=[], offset=0;
    do {
      var texts=[], chars=0;
      while(offset+texts.length<dump.length && texts.length<256) {
        var value=rawString(dump[offset+texts.length],'text');
        if(value.length>65536) throw rangeError('textMatches 单节点文本超过64K字符');
        if(texts.length && chars+value.length>65536) break;
        texts.push(value);chars+=value.length;
      }
      var matched=automation('matchTextPatternBatch',{pattern:pattern,texts:texts});
      if(!Array.isArray(matched) || matched.length!==texts.length) throw apiError('textMatches 批量返回值非法');
      for(var i=0;i<matched.length;i++) {
        if(typeof matched[i]!=='boolean') throw apiError('textMatches 批量返回值不是boolean');
        result.push(matched[i]);
      }
      offset+=texts.length;
    } while(offset<dump.length);
    return result;
  }
  function evaluateState(state, dump) {
    if(state.spec.algorithm==='BFS') throw apiError('BFS 暂不能与本地谓词、JS正则或范围快照组合；请使用宿主可下发条件');

    var parentPaths = state.from ? collectParentPaths(state.from, dump) : null;
    // Preserve Java Pattern semantics over the FULL retained snapshot. Host findNodes
    // caps candidates before local predicates, so intersecting its paths loses late matches.
    var javaMatches=Object.prototype.hasOwnProperty.call(state.spec,'textMatches')
      ? matchDumpTextPattern(state.spec.textMatches,dump) : null;
    var result = [];
    for (var i = 0; i < dump.length; i++) {
      var raw = dump[i];
      if (javaMatches && !javaMatches[i]) { continue; }
      if (!matchesSpec(raw, state.spec, dump, javaMatches != null)) { continue; }
      var matchedParent = null;
      if (parentPaths) {
        for (var p = 0; p < parentPaths.length; p++) {
          if (isStrictDescendant(rawPath(raw), parentPaths[p]) || (state.includeFromRoot && pathPrefixMatches(rawPath(raw),parentPaths[p]))) { matchedParent = parentPaths[p]; break; }
        }
        if (!matchedParent) { continue; }
      }
      var view = new NodeView({
        raw: raw,
        tree: dump,
        fromPath: matchedParent
      });
      var accepted = true;
      for (var w = 0; w < state.where.length; w++) {
        if (!state.where[w](view, result.length)) { accepted = false; break; }
      }
      if (accepted) { result.push(view); }
    }
    return result;
  }
  function executeFirst(state) {
    if (!stateHasLocal(state)) {
      var raw = normalizeNode(
        automation('getNodeInfo', { selector: hostSpec(state.spec), index: 0 }),
        'getNodeInfo');
      return raw ? new NodeView({ raw: raw, tree: null, fromPath: null }) : null;
    }
    var views = evaluateState(state, getDump());
    return views.length ? views[0] : null;
  }
  function executeAt(state, index) {
    if (!stateHasLocal(state)) {
      var raw = normalizeNode(
        automation('getNodeInfo', { selector: hostSpec(state.spec), index: index }),
        'getNodeInfo');
      return raw ? new NodeView({ raw: raw, tree: null, fromPath: null }) : null;
    }
    var views = evaluateState(state, getDump());
    return index < views.length ? views[index] : null;
  }
  function executeAll(state) {
    if (!stateHasLocal(state)) {
      var raws = normalizeNodes(
        automation('findNodes', { selector: hostSpec(state.spec) }),
        'findNodes');
      return raws.map(function (raw) {
        return new NodeView({ raw: raw, tree: null, fromPath: null });
      });
    }
    return evaluateState(state, getDump());
  }
  function toNode(view) {
    return new Node(view._raw, { path: rawPath(view._raw).slice() }, 0, {
      tree: view._tree,
      fromPath: view._fromPath
    });
  }
  function findOnceRaw(state, index) {
    return executeAt(state, index);
  }
  function isNotFound(error) {
    return error instanceof NotFoundError || !!(error && error.name === 'NotFoundError');
  }

  /* --------------------------- 异步轮询（timer） --------------------------- */
  function effectiveLimits(state, options) {
    if (options == null) {
      return { timeout: state.timeout, maxRetries: state.maxRetries };
    }
    if (typeof options === 'number') {
      return {
        timeout: requireInt(options, 'findFirst.timeout', 0),
        maxRetries: state.maxRetries
      };
    }
    if (typeof options !== 'object' || Array.isArray(options) || !isPlainObject(options)) {
      throw typeError('findFirst(...) 参数必须是毫秒数或 {timeout,maxRetries} 对象');
    }
    for (var key in options) {
      if (!Object.prototype.hasOwnProperty.call(options, key)) { continue; }
      if (key !== 'timeout' && key !== 'maxRetries') {
        throw typeError('findFirst(...) 不支持字段：' + key + '（只支持 timeout/maxRetries）');
      }
    }
    return {
      timeout: options.timeout == null
        ? state.timeout : requireInt(options.timeout, 'findFirst.timeout', 0),
      maxRetries: options.maxRetries == null
        ? state.maxRetries : requireInt(options.maxRetries, 'findFirst.maxRetries', 0)
    };
  }
  function shouldStop(started, attempts, limits) {
    if (limits.timeout <= 0) { return true; }
    if (limits.maxRetries !== Infinity && attempts >= limits.maxRetries + 1) { return true; }
    return Date.now() - started >= limits.timeout;
  }
  function nextDelay(started, limits) {
    var remaining = limits.timeout - (Date.now() - started);
    if (remaining <= 0) { return 0; }
    return Math.min(POLL_INTERVAL_MILLIS, remaining, MAX_TIMER_DELAY);
  }
  function pollFirst(state, limits) {
    var started = Date.now();
    var attempts = 0;
    return new Promise(function (resolve, reject) {
      function attempt() {
        attempts++;
        var view;
        try { view = executeFirst(state); }
        catch (error) { reject(error); return; }
        if (view) { resolve(view); return; }
        if (shouldStop(started, attempts, limits)) { resolve(null); return; }
        setTimeout(attempt, nextDelay(started, limits));
      }
      // 首次查询也延后到微任务，保证 findFirst() 先返回 Promise，调用方不被同步宿主调用卡住。
      Promise.resolve().then(attempt).catch(reject);
    });
  }
  function pollAll(state) {
    var cap = state.atMost == null ? Infinity : state.atMost;
    if (cap === 0) { return Promise.resolve([]); }
    var target = state.atLeast;
    if (cap !== Infinity && target > cap) { target = cap; }
    var started = Date.now();
    var attempts = 0;
    var limits = { timeout: state.timeout, maxRetries: state.maxRetries };
    return new Promise(function (resolve, reject) {
      function attempt() {
        attempts++;
        var result;
        try { result = executeAll(state); }
        catch (error) { reject(error); return; }
        if (cap !== Infinity && result.length > cap) { result = result.slice(0, cap); }
        if (result.length >= target) { resolve(result); return; }
        if (shouldStop(started, attempts, limits)) { resolve(result); return; }
        setTimeout(attempt, nextDelay(started, limits));
      }
      Promise.resolve().then(attempt).catch(reject);
    });
  }

  /* ---------------------------- NotFoundError ---------------------------- */
  function NotFoundError(message, selector) {
    Error.call(this, message);
    this.name = 'NotFoundError';
    this.message = message;
    this.selector = selector ? cloneSpec(selector) : null;
    this.stack = (new Error(message)).stack;
  }
  NotFoundError.prototype = Object.create(Error.prototype);
  NotFoundError.prototype.constructor = NotFoundError;

  /* ----------------------------- UiSelector ------------------------------ */
  function UiSelector(initial) {
    this._spec = {};
    this._where = [];
    this._from = null;
    this._atMost = null;
    this._atLeast = 0;
    this._timeout = DEFAULT_TIMEOUT_MILLIS;
    this._maxRetries = Infinity;
    if (initial == null) { return; }
    if (initial instanceof UiSelector) {
      var state = snapshotState(initial);
      this._spec = state.spec;
      this._where = state.where;
      this._from = state.from;
      this._atMost = state.atMost;
      this._atLeast = state.atLeast;
      this._timeout = state.timeout;
      this._maxRetries = state.maxRetries;
      return;
    }
    this._spec = parseSpecObject(initial);
  }
  for (var fi = 0; fi < ALL_FIELDS.length; fi++) {
    (function (field) {
      UiSelector.prototype[field] = function (value) {
        if (arguments.length === 0 && BOOLEAN_FIELDS.indexOf(field) >= 0) value = true;
        this._spec[field] = parseSpecField(field, value);
        return this;
      };
    })(ALL_FIELDS[fi]);
  }
  UiSelector.prototype.timeout = function (ms) {
    this._timeout = requireInt(ms, 'timeout', 0);
    return this;
  };
  UiSelector.prototype.maxRetries = function (count) {
    this._maxRetries = requireInt(count, 'maxRetries', 0);
    return this;
  };
  UiSelector.prototype.atMost = function (count) {
    this._atMost = requireInt(count, 'atMost', 0);
    return this;
  };
  UiSelector.prototype.atLeast = function (count) {
    this._atLeast = requireInt(count, 'atLeast', 0);
    return this;
  };
  UiSelector.prototype.algorithm=function(value) {this._spec.algorithm=parseSpecField('algorithm',value);return this;};
  // Native-style predicates receive method-style nodes; where() keeps property-style NodeView.
  UiSelector.prototype.filter=UiSelector.prototype.addFilter=function(fn) {
    if(typeof fn!=='function') throw typeError('filter/addFilter 需要函数');
    this._where.push(function(view){return fn(toNode(view))===true;});return this;
  };
  UiSelector.prototype.where = function (fn) {
    if (typeof fn !== 'function') { throw typeError('where(fn) 需要函数'); }
    this._where.push(fn);
    return this;
  };
  UiSelector.prototype.from = function (parent) {
    if (parent === this) { throw typeError('from(parent) 不能引用当前选择器自身'); }
    if (parent instanceof UiSelector) {
      this._from = snapshotState(parent, []);
    } else if (parent instanceof Node || parent instanceof NodeView) {
      this._from = { pathOnly: rawPath(parent._raw).slice(), identity: identitySelector(parent) };
    } else if (parent && typeof parent === 'object' && !Array.isArray(parent)
      && isPlainObject(parent)) {
      this._from = snapshotState(new UiSelector(parent), []);
    } else {
      throw typeError('from(parent) 需要 UiSelector、选择器对象或节点');
    }
    return this;
  };
  UiSelector.prototype.spec = function () { return cloneSpec(this._spec); };
  UiSelector.prototype.find = function () {
    var state = snapshotState(this);
    return nodeCollection(executeAll(state).map(toNode));
  };
  UiSelector.prototype.untilFind = function (timeout) {
    var limit = timeout == null ? Infinity : requireInt(timeout, 'untilFind.timeout', 0), started = Date.now();
    for (;;) {
      var matches = this.find();
      if (matches.length) return matches;
      if (Date.now() - started >= limit) throw new NotFoundError('未找到匹配的节点', snapshotState(this).spec);
      sleepSync(POLL_INTERVAL_MILLIS);
    }
  };
  ['click','clickCenter','longClick','setText','copy','cut','paste','select','collapse','expand','dismiss','show',
    'scrollForward','scrollBackward','scrollUp','scrollDown','scrollLeft','scrollRight','contextClick','focus',
    'clearFocus','accessibilityFocus','clearAccessibilityFocus','setSelection','scrollTo','setProgress'].forEach(function (name) {
      UiSelector.prototype[name] = function () {
        validateCollectionAction(name, arguments);
        var nodes = this.untilFind();
        return nodes[name].apply(nodes, arguments);
      };
    });
  UiSelector.prototype.findOnce = function (index) {
    var state = snapshotState(this);
    var position = index == null ? 0 : requireInt(index, 'findOnce.index', 0);
    if (position > 255) { throw rangeError('findOnce.index 不能超过 255'); }
    var view = findOnceRaw(state, position);
    return view ? toNode(view) : null;
  };
  UiSelector.prototype.findOne = function (timeout) {
    var state = snapshotState(this);
    var limit = timeout == null ? state.timeout : requireInt(timeout, 'timeout', 0);
    var started = Date.now();
    for (;;) {
      var view = findOnceRaw(state, 0);
      if (view) { return toNode(view); }
      var elapsed = Date.now() - started;
      if (elapsed >= limit) { break; }
      sleepSync(Math.min(POLL_INTERVAL_MILLIS, limit - elapsed));
    }
    throw new NotFoundError('未找到匹配的节点：' + JSON.stringify(state.spec), state.spec);
  };
  UiSelector.prototype.firstOrNull = function () {
    var state = snapshotState(this);
    return Promise.resolve().then(function () {
      var view = executeFirst(state);
      return view || null;
    });
  };
  UiSelector.prototype.findFirst = function (options) {
    var state = snapshotState(this);
    var limits;
    try {
      limits = effectiveLimits(state, options);
    } catch (error) {
      return Promise.reject(error);
    }
    return pollFirst(state, limits);
  };
  UiSelector.prototype.all = function () {
    var state = snapshotState(this);
    return Promise.resolve().then(function () { return pollAll(state); }).then(nodeCollection);
  };
  UiSelector.prototype.exists = function () { return this.findOnce(0) != null; };
  UiSelector.prototype.waitFor = function (timeout) {
    try {
      this.findOne(timeout);
      return true;
    } catch (error) {
      if (isNotFound(error)) { return false; }
      throw error;
    }
  };
  UiSelector.prototype.waitUntilFound = function (timeout) { return this.waitFor(timeout); };
  UiSelector.prototype.root = function () { return Promise.resolve().then(fetchRootView); };
  UiSelector.prototype.tree = function () {
    var dump = getDump();
    return dump.map(function (raw) { return new NodeView({ raw: raw, tree: dump, fromPath: null }); });
  };
  UiSelector.prototype.toString = function () { return 'UiSelector(' + JSON.stringify(this._spec) + ')'; };

  function select(initial) { return new UiSelector(initial); }
  function waitFor(target, timeout) {
    var selector = target instanceof UiSelector ? target : new UiSelector(target);
    return selector.waitFor(timeout);
  }
  function root() { return Promise.resolve().then(fetchRootView); }
  function tree() {
    var dump = getDump();
    return dump.map(function (raw) { return new NodeView({ raw: raw, tree: dump, fromPath: null }); });
  }
  function dumpNodes() {
    return getDump().map(function (raw) {
      return new Node(raw, { path: rawPath(raw).slice() }, 0, { tree: null, fromPath: null });
    });
  }

  /* ---------------------------- 模块注册 ----------------------------- */
  var extended = Object.assign({}, legacy, {
    select: select,
    root: root,
    tree: tree,
    waitFor: waitFor,
    UiSelector: UiSelector,
    NodeView: NodeView,
    UiObject: Node,
    Rect: Rect,
    NotFoundError: NotFoundError,
    dumpNodes: dumpNodes,
    nodes: dumpNodes,
    enableService: legacy.enableService,
    status: legacy.status,
    statusSync: legacy.statusSync,
    openSettings: legacy.openSettings,
    click: legacy.click,
    longClick: legacy.longClick,
    press: legacy.press,
    swipe: legacy.swipe,
    gesture: legacy.performGesture,
    performGesture: legacy.performGesture,
    performGestures: legacy.performGestures,
    back: legacy.back,
    home: legacy.home,
    recents: legacy.recents,
    notifications: legacy.notifications,
    toggleRecents: legacy.toggleRecents || legacy.recents,
    openNotifications: legacy.openNotifications || legacy.notifications,
    takeScreenshot: legacy.takeScreenshot,
    StrokeDescription: legacy.StrokeDescription,
    enableKeyEvents: legacy.enableKeyEvents,
    lockScreen: legacy.lockScreen
  });
  extended.accessibility = extended;
  var uiSelectorModule = {
    UiSelector: UiSelector,
    NodeView: NodeView,
    NotFoundError: NotFoundError,
    select: select,
    waitFor: waitFor,
    root: root,
    tree: tree
  };
  var uiObjectModule = {
    Rect: Rect,
    UiObject: Node,
    NodeView: NodeView,
    NotFoundError: NotFoundError
  };
  globalThis.__ajsRegisterModule('accessibility', function () { return extended; });
  globalThis.__ajsRegisterModule('ui_selector', function () { return uiSelectorModule; });
  globalThis.__ajsRegisterModule('ui_object', function () { return uiObjectModule; });
  globalThis.__ajsRegisterModule('selector', function () { return uiSelectorModule; });
  if (globalThis.__ajsBuiltinModules) {
    globalThis.__ajsBuiltinModules.accessibility = extended;
    globalThis.__ajsBuiltinModules.ui_selector = uiSelectorModule;
    globalThis.__ajsBuiltinModules.ui_object = uiObjectModule;
    globalThis.__ajsBuiltinModules.selector = uiSelectorModule;
  }
  if (globalThis.$autojs) {
    globalThis.$autojs.accessibility = extended;
    globalThis.$autojs.ui_selector = uiSelectorModule;
    globalThis.$autojs.ui_object = uiObjectModule;
  }
})();
