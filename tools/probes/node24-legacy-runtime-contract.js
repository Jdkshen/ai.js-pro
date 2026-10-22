/*
 * Node24 legacy-runtime contract probe (no device, no build, no UI launch).
 *
 * Loads the real modules/engine/src/main/assets/node24/modules/legacy-runtime.js
 * into a vm with a small Java/host shim, then verifies:
 *   - app name <-> package roundtrip through AppUtils
 *   - launch/launchPackage false for a missing package (and no Activity launch)
 *   - app.intent categories/extras/action construction without launching
 *   - global events/$events are one local native EventEmitter (sync multi-arg/once)
 *   - global util/$util predicates and native require('events'/'util') untouched
 *   - engines.execScript source request keeps delay/interval/loopTimes/path/
 *     workingDirectory/arguments and routes execution events
 *   - engines.execScriptFile still delegates plain config and carries extended config
 *
 * Run: node tools/probes/node24-legacy-runtime-contract.js
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '..', '..');
const ASSET = path.join(ROOT, 'modules', 'engine', 'src', 'main', 'assets', 'node24', 'modules', 'legacy-runtime.js');
const SCRIPT_FILE = path.join(ROOT, 'tmp-node24-legacy-runtime-probe', 'src', 'main.js');
const SCRIPT_DIR = path.dirname(SCRIPT_FILE);
fs.mkdirSync(SCRIPT_DIR, {recursive: true});

const nativeEvents = require('node:events');
const nativeUtil = require('node:util');

let activityLaunches = 0;
const appCalls = [];
const engineRequests = [];
const engineEvents = Object.create(null);
const registeredModules = [];

function Intent() {
  this.action = null;
  this.categories = [];
  this.extras = {};
  this.flags = 0;
  this.className = null;
  this.packageName = null;
  this.data = null;
  this.type = null;
}
Intent.FLAG_ACTIVITY_NEW_TASK = 0x10000000;
Intent.FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;
Intent.prototype.setClassName = function (packageName, className) {
  this.packageName = packageName;
  this.className = className;
  return this;
};
Intent.prototype.setPackage = function (packageName) { this.packageName = packageName; return this; };
Intent.prototype.setAction = function (action) { this.action = action; return this; };
Intent.prototype.addCategory = function (category) { this.categories.push(category); return this; };
Intent.prototype.putExtra = function (key, value) { this.extras[key] = value; return this; };
Intent.prototype.setFlags = function (flags) { this.flags = flags; return this; };
Intent.prototype.setData = function (data) { this.data = data; return this; };
Intent.prototype.setType = function (type) { this.type = type; return this; };
Intent.prototype.setDataAndType = function (data, type) {
  this.data = data;
  this.type = type;
  return this;
};

function AppUtils(context) { this.context = context; }
AppUtils.prototype.getPackageName = function (appName) {
  appCalls.push(['getPackageName', appName]);
  return appName === '设置' ? 'com.android.settings' : null;
};
AppUtils.prototype.getAppName = function (packageName) {
  appCalls.push(['getAppName', packageName]);
  if (packageName === 'com.android.settings') { return '设置'; }
  if (packageName === 'com.example.host') { return '宿主'; }
  return null;
};
AppUtils.prototype.launchPackage = function (packageName) {
  appCalls.push(['launchPackage', packageName]);
  return packageName === 'com.android.settings';
};

const Uri = {
  parse(value) {
    return {
      raw: String(value),
      getScheme() { return String(value).split(':')[0]; }
    };
  }
};

function findClass(name) {
  if (name === 'com.stardust.autojs.runtime.api.AppUtils') { return AppUtils; }
  if (name === 'android.content.Intent') { return Intent; }
  if (name === 'android.net.Uri') { return Uri; }
  throw new Error('unexpected Java class: ' + name);
}

const androidContext = {
  getPackageName() { return 'com.example.host'; },
  startActivity() { activityLaunches += 1; }
};

const modernStartActivity = function (target) { return Promise.resolve(target); };
const baseApp = {
  startActivity: modernStartActivity,
  openUrl() {},
  getPackageName() { return 'com.example.host'; },
  getAppName() { return '宿主'; },
  launch() { return true; }
};

let delegatedFileCalls = 0;
const baseEngines = {
  myEngine() { return {id: 77, engine: 'Node24JavaScriptEngine'}; },
  execScriptFile(file, options) {
    delegatedFileCalls += 1;
    return {delegated: true, file, options};
  },
  stopAll() { return 0; }
};

function engineBridge(request) {
  engineRequests.push(request);
  if (request.kind !== 'engines') {
    throw new Error('unexpected host kind: ' + request.kind);
  }
  if (request.action === 'exec') {
    return {token: request.args.token, executionId: 123};
  }
  if (request.action === 'execution') {
    return {
      token: request.args.token,
      executionId: 123,
      engineId: 501,
      engine: 'RhinoJavaScriptEngine',
      done: true,
      success: true,
      source: 'child',
      execArgv: {from: 'request'}
    };
  }
  if (request.action === 'stop') { return true; }
  throw new Error('unexpected engine action: ' + request.action);
}

function on(type, handler) {
  if (!engineEvents[type]) { engineEvents[type] = []; }
  engineEvents[type].push(handler);
  return () => off(type, handler);
}
function off(type, handler) {
  const list = engineEvents[type] || [];
  const index = list.indexOf(handler);
  if (index >= 0) { list.splice(index, 1); }
}
function emitEngine(payload) {
  (engineEvents.__ajs_engine_event || []).slice().forEach(handler => handler(payload));
}

const context = vm.createContext({
  console,
  process,
  require,
  setTimeout,
  clearTimeout,
  setInterval,
  clearInterval,
  __ajsScriptFile: SCRIPT_FILE,
  __ajsProjectDir: path.dirname(SCRIPT_DIR),
  __ajsRegisterModule(name, factory) {
    registeredModules.push(name);
    assert.equal(typeof factory, 'function');
  },
  __ajsBuiltinModules: {app: baseApp, engines: baseEngines},
  __ajsHostValue: engineBridge,
  __ajsOn: on,
  __ajsOff: off,
  $autojs: {
    androidContext,
    java: {findClass}
  }
});

const source = fs.readFileSync(ASSET, 'utf8');
assert.ok(source.trim().length > 0, 'legacy-runtime.js is empty');
vm.runInContext(source, context, {filename: ASSET});

// --- app: module shape and modern behavior preserved -------------------------
assert.equal(context.app, baseApp);
assert.equal(context.$app, baseApp);
assert.equal(baseApp.startActivity, modernStartActivity);
assert.equal(typeof baseApp.openUrl, 'function');
assert.equal(baseApp.getPackageName(), 'com.example.host');
assert.equal(baseApp.getPackageName('设置'), 'com.android.settings');
assert.equal(baseApp.getPackageName('不存在'), null);
assert.equal(baseApp.getAppName(), '宿主');
assert.equal(baseApp.getAppName('com.android.settings'), '设置');
assert.equal(baseApp.getAppName('missing.package'), null);
assert.deepEqual(appCalls.slice(-5), [
  ['getPackageName', '设置'],
  ['getPackageName', '不存在'],
  ['getAppName', 'com.example.host'],
  ['getAppName', 'com.android.settings'],
  ['getAppName', 'missing.package']
]);

// --- app: missing package launch is false, no Activity launch ---------------
assert.equal(baseApp.launch('missing.package'), false);
assert.equal(baseApp.launchPackage('missing.package'), false);
assert.equal(baseApp.launchApp('不存在'), false);
assert.equal(baseApp.launch('com.android.settings'), true);
assert.equal(baseApp.launchApp('设置'), true);
assert.equal(activityLaunches, 0, 'app.intent/launch tests must not start an Activity');

// --- app.intent: categories/extras/action without launching -----------------
const intent = baseApp.intent({
  action: 'VIEW',
  category: ['android.intent.category.DEFAULT', 'com.example.CATEGORY'],
  data: 'https://example.com/a',
  type: 'text/plain',
  extras: {text: 'hello', enabled: true, count: 3, missing: null},
  flags: ['activity_new_task']
});
assert.ok(intent instanceof Intent);
assert.equal(intent.action, 'android.intent.action.VIEW');
assert.deepEqual(intent.categories, ['android.intent.category.DEFAULT', 'com.example.CATEGORY']);
assert.deepEqual(intent.extras, {text: 'hello', enabled: true, count: 3, missing: null});
assert.equal(intent.flags, Intent.FLAG_ACTIVITY_NEW_TASK);
assert.equal(intent.data.getScheme(), 'https');
assert.equal(intent.type, 'text/plain');
assert.equal(baseApp.makeIntent, baseApp.intent);
assert.equal(activityLaunches, 0, 'app.intent must only construct an Intent');

// --- global events/$events: local native EventEmitter -----------------------
assert.equal(context.events, context.$events);
assert.ok(context.events instanceof nativeEvents.EventEmitter);
assert.notEqual(context.events, context.require('node:events'));
const eventCalls = [];
context.events.on('demo', function (a, b) { eventCalls.push(['on', a, b]); });
context.events.once('demo', function (a, b) { eventCalls.push(['once', a, b]); });
assert.equal(context.events.emit('demo', 1, 2), true);
assert.deepEqual(eventCalls, [['on', 1, 2], ['once', 1, 2]]);
assert.equal(context.events.listenerCount('demo'), 1);
assert.equal(context.require('node:events').EventEmitter, nativeEvents.EventEmitter);

// --- global util/$util: pure predicates, native util untouched -------------
assert.equal(context.util, context.$util);
assert.equal(context.util.isArray([]), true);
assert.equal(context.util.isArray({}), false);
assert.equal(context.util.isBoolean(false), true);
assert.equal(context.util.isNull(null), true);
assert.equal(context.util.isNullOrUndefined(undefined), true);
assert.equal(context.util.isNumber(NaN), true);
assert.equal(context.util.isString('x'), true);
assert.equal(context.util.isUndefined(undefined), true);
assert.equal(context.util.isRegExp(/x/), true);
assert.equal(context.util.isDate(new Date(0)), true);
assert.equal(context.util.isError(vm.runInContext('new Error("x")', context)), true);
assert.equal(context.util.isFunction(() => {}), true);
assert.equal(context.util.isObject([]), true);
assert.equal(context.util.isObject(null), false);
assert.equal(context.util.isPrimitive('x'), true);
assert.equal(context.util.isPrimitive({}), false);
assert.equal(context.require('node:util').format('%s:%d', 'n', 2), 'n:2');
assert.equal(context.require('node:util'), nativeUtil);

// --- engines: module aliases + execScript source request -------------------
assert.equal(context.engines, baseEngines);
assert.equal(typeof context.engines.execScript, 'function');
assert.equal(typeof context.engines.execScriptFile, 'function');
assert.ok(!registeredModules.includes('events'));
assert.ok(!registeredModules.includes('util'));

const sourceExecution = context.engines.execScript('child-name', 'console.log(1);', {
  delay: 5,
  interval: 7,
  loopTimes: 2,
  path: ['lib', 'shared'],
  workingDirectory: 'work',
  arguments: {token: 'abc', nested: {n: 1}}
});
assert.equal(sourceExecution.id, 123);
assert.equal(sourceExecution.token, engineRequests[engineRequests.length - 1].args.token);
const sourceRequest = engineRequests[engineRequests.length - 1];
assert.equal(sourceRequest.kind, 'engines');
assert.equal(sourceRequest.action, 'exec');
assert.equal(sourceRequest.args.name, 'child-name');
assert.equal(sourceRequest.args.source, 'console.log(1);');
assert.equal(sourceRequest.args.delay, 5);
assert.equal(sourceRequest.args.interval, 7);
assert.equal(sourceRequest.args.loopTimes, 2);
assert.deepEqual(sourceRequest.args.arguments, {token: 'abc', nested: {n: 1}});
assert.equal(path.dirname(sourceRequest.args.workingDirectory), SCRIPT_DIR);
assert.equal(path.basename(sourceRequest.args.workingDirectory), 'work');
assert.equal(sourceRequest.args.configPath.length, 2);
assert.equal(path.basename(sourceRequest.args.configPath[0]), 'lib');
assert.equal(path.basename(sourceRequest.args.configPath[1]), 'shared');

let successCalls = 0;
let successArg = null;
sourceExecution.on('success', (execution) => { successCalls += 1; successArg = execution; });
emitEngine({
  token: sourceExecution.token,
  event: 'start',
  engineId: 501,
  args: []
});
emitEngine({
  token: sourceExecution.token,
  event: 'success',
  engineId: 501,
  args: []
});
assert.equal(successCalls, 1);
assert.equal(successArg, sourceExecution);
returnSourceEngine(sourceExecution).then(engine => {
  assert.equal(engine.id, 501);
  assert.equal(engine.engine, 'RhinoJavaScriptEngine');
  assert.equal(engine.execArgv.from, 'request');
  finishFileAndExit();
}).catch(error => { throw error; });

function returnSourceEngine(execution) {
  return execution.engine();
}

function finishFileAndExit() {
  // --- engines.execScriptFile: plain config delegates, extended config routes
  const plain = context.engines.execScriptFile('plain.js', {
    workingDirectory: 'plain-work',
    arguments: {a: 1}
  });
  assert.equal(plain.delegated, true);
  assert.equal(delegatedFileCalls, 1);
  assert.equal(plain.file, 'plain.js');

  const fileExecution = context.engines.execScriptFile('child.js', {
    delay: 11,
    interval: 13,
    loopTimes: 4,
    path: ['file-lib'],
    arguments: {kind: 'file'}
  });
  assert.equal(fileExecution.id, 123);
  const fileRequest = engineRequests[engineRequests.length - 1];
  assert.equal(fileRequest.action, 'exec');
  assert.ok(path.isAbsolute(fileRequest.args.path));
  assert.equal(path.basename(fileRequest.args.path), 'child.js');
  assert.equal(fileRequest.args.delay, 11);
  assert.equal(fileRequest.args.interval, 13);
  assert.equal(fileRequest.args.loopTimes, 4);
  assert.deepEqual(fileRequest.args.arguments, {kind: 'file'});
  assert.equal(fileRequest.args.configPath.length, 1);
  assert.equal(path.basename(fileRequest.args.configPath[0]), 'file-lib');
  assert.equal(fileRequest.args.workingDirectory, fileRequest.args.configPath[0]);

  // delay 是扩展配置，但没有显式 workingDirectory/path：默认应为子脚本父目录。
  context.engines.execScriptFile('nested/delayed.js', {delay: 1});
  const delayedRequest = engineRequests[engineRequests.length - 1];
  assert.equal(delayedRequest.args.workingDirectory, path.join(SCRIPT_DIR, 'nested'));
  assert.equal(delayedRequest.args.delay, 1);

  console.log('node24 legacy-runtime contract probe: ok');
}
