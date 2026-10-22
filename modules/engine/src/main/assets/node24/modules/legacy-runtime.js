// SPDX-License-Identifier: MIT
/*
 * Node24 legacy runtime globals (Auto.js v1 surface).
 *
 * This asset runs after prelude.js and only adds the old global/module aliases
 * that are still missing from the Node24 adapter:
 *   - global app/$app + app.launchPackage/launchApp/intent/makeIntent
 *   - global events/$events (a local node:events EventEmitter instance)
 *   - global util/$util (pure legacy type predicates)
 *   - global engines + engines.execScript(name, source, config)
 *
 * require('events') / require('util') stay the native Node modules.  Modern
 * require('app') methods are kept by mutating the existing prelude object in
 * place instead of replacing the module.
 */
(function () {
  'use strict';

  if (typeof globalThis.__ajsRegisterModule !== 'function') {
    throw new Error('[Auto.js] legacy-runtime 必须在 Node24 prelude 之后加载');
  }

  var pathModule = require('node:path');
  var nativeEvents = require('node:events');
  var autojs = globalThis.$autojs;
  var builtin = globalThis.__ajsBuiltinModules;
  if (!autojs || !builtin) {
    throw new Error('[Auto.js] legacy-runtime 缺少 prelude 的 $autojs/__ajsBuiltinModules');
  }

  function apiError(message) {
    var error = new Error('[Auto.js] legacy-runtime: ' + message);
    error.name = 'AutoJsLegacyRuntimeError';
    return error;
  }

  function javaClass(name) {
    if (!autojs.java || typeof autojs.java.findClass !== 'function') {
      throw apiError('当前 Node24 桥缺少 Java 类访问能力');
    }
    return autojs.java.findClass(name);
  }

  function androidContext() {
    try {
      var context = autojs.androidContext;
      if (context) { return context; }
    } catch (error) {
      throw apiError('无法获取 Android Context：' + String(error && error.message || error));
    }
    throw apiError('无法获取 Android Context');
  }

  // ------------------------------------------------------------------
  // app/$app
  // ------------------------------------------------------------------
  var appUtilsClass = null;
  var appUtilsInstance = null;
  var intentClass = null;
  var uriClass = null;

  function appUtils() {
    if (!appUtilsInstance) {
      if (!appUtilsClass) {
        appUtilsClass = javaClass('com.stardust.autojs.runtime.api.AppUtils');
      }
      appUtilsInstance = new appUtilsClass(androidContext());
    }
    return appUtilsInstance;
  }

  function androidIntentClass() {
    if (!intentClass) { intentClass = javaClass('android.content.Intent'); }
    return intentClass;
  }

  function androidUriClass() {
    if (!uriClass) { uriClass = javaClass('android.net.Uri'); }
    return uriClass;
  }

  function stringOrNull(value) {
    return value === null || value === undefined ? null : String(value);
  }

  function currentPackageName() {
    return String(androidContext().getPackageName());
  }

  // Auto.js Pro semantics:
  //   app.getPackageName()        -> this app's package
  //   app.getPackageName(appName) -> installed package whose label is appName
  //   app.getAppName()            -> this app's label
  //   app.getAppName(pkg)         -> that package's label
  function getPackageName(appName) {
    if (arguments.length === 0 || appName === null || appName === undefined) {
      return currentPackageName();
    }
    return stringOrNull(appUtils().getPackageName(String(appName)));
  }

  function getAppName(packageName) {
    if (arguments.length === 0 || packageName === null || packageName === undefined) {
      packageName = currentPackageName();
    }
    return stringOrNull(appUtils().getAppName(String(packageName)));
  }

  function launchPackage(packageName) {
    if (packageName === null || packageName === undefined || String(packageName).length === 0) {
      return false;
    }
    return appUtils().launchPackage(String(packageName)) === true;
  }

  function launchApp(appName) {
    var packageName = getPackageName(appName);
    return packageName === null ? false : launchPackage(packageName);
  }

  function intentFlag(flag) {
    var Intent = androidIntentClass();
    var value;
    if (typeof flag === 'string') {
      value = Intent['FLAG_' + flag.toUpperCase()];
      if (typeof value !== 'number') {
        throw new TypeError('app.intent: 未知 Intent flag：' + flag);
      }
      return value;
    }
    if (typeof flag === 'number' && isFinite(flag)) { return flag | 0; }
    throw new TypeError('app.intent: flag 必须是字符串或数字');
  }

  function intentFlags(flags) {
    if (flags === null || flags === undefined) { return 0; }
    var list = Array.isArray(flags) ? flags : [flags];
    var mask = 0;
    for (var i = 0; i < list.length; i++) { mask |= intentFlag(list[i]); }
    return mask;
  }

  function parseUri(value) {
    if (value === null || value === undefined) {
      throw new TypeError('app.intent: data 不能为空');
    }
    // A Java Uri already has getScheme().  Keep it instead of stringifying it.
    if (typeof value === 'object' && typeof value.getScheme === 'function') {
      return value;
    }
    return androidUriClass().parse(String(value));
  }

  function addIntentExtras(intent, extras) {
    if (extras === null || extras === undefined) { return; }
    if (typeof extras !== 'object' || Array.isArray(extras)) {
      throw new TypeError('app.intent: extras 必须是对象');
    }
    var keys = Object.keys(extras);
    for (var i = 0; i < keys.length; i++) {
      var key = keys[i];
      var value = extras[key];
      if (value === undefined) { continue; }
      if (value === null || typeof value === 'string' || typeof value === 'boolean'
          || typeof value === 'number') {
        intent.putExtra(String(key), value);
        continue;
      }
      // The Android bridge cannot reliably choose putStringArray/putIntArray
      // overloads for a JS array.  Fail explicitly instead of writing a
      // comma-joined fake value.
      throw new TypeError('app.intent: extras["' + key + '"] 仅支持字符串/布尔/数字/null');
    }
  }

  function makeIntent(options) {
    options = options === null || options === undefined ? {} : options;
    if (typeof options !== 'object' || Array.isArray(options)) {
      throw new TypeError('app.intent(options): options 必须是对象');
    }
    var Intent = androidIntentClass();
    var intent = new Intent();
    var packageName = options.packageName;
    var className = options.className;
    if (packageName !== null && packageName !== undefined && String(packageName).length > 0) {
      if (className !== null && className !== undefined && String(className).length > 0) {
        intent.setClassName(String(packageName), String(className));
      } else {
        intent.setPackage(String(packageName));
      }
    }
    addIntentExtras(intent, options.extras);
    if (options.category !== null && options.category !== undefined) {
      var categories = Array.isArray(options.category) ? options.category : [options.category];
      for (var i = 0; i < categories.length; i++) {
        if (categories[i] !== null && categories[i] !== undefined
            && String(categories[i]).length > 0) {
          intent.addCategory(String(categories[i]));
        }
      }
    }
    if (options.action !== null && options.action !== undefined && String(options.action).length > 0) {
      var action = String(options.action);
      if (action.indexOf('.') < 0) { action = 'android.intent.action.' + action; }
      intent.setAction(action);
    }
    if (options.flags !== null && options.flags !== undefined) {
      intent.setFlags(intentFlags(options.flags));
    }
    if (options.type !== null && options.type !== undefined
        && String(options.type).length > 0) {
      if (options.data !== null && options.data !== undefined) {
        intent.setDataAndType(parseUri(options.data), String(options.type));
      } else {
        intent.setType(String(options.type));
      }
    } else if (options.data !== null && options.data !== undefined) {
      intent.setData(parseUri(options.data));
    }
    return intent;
  }

  var appModule = builtin.app;
  if (!appModule || typeof appModule !== 'object') {
    throw apiError('缺少 prelude 的 app 模块');
  }
  // Mutate the real prelude module: require('app') keeps every modern method
  // already provided by the Node24 adapter.
  appModule.getPackageName = getPackageName;
  appModule.getAppName = getAppName;
  appModule.launch = launchPackage;
  appModule.launchPackage = launchPackage;
  appModule.launchApp = launchApp;
  appModule.intent = makeIntent;
  appModule.makeIntent = makeIntent;
  globalThis.app = appModule;
  globalThis.$app = appModule;
  autojs.app = appModule;

  // ------------------------------------------------------------------
  // global events/$events: a local native EventEmitter, never the module.
  // ------------------------------------------------------------------
  var globalEvents = new nativeEvents.EventEmitter();
  globalThis.events = globalEvents;
  globalThis.$events = globalEvents;

  // ------------------------------------------------------------------
  // global util/$util: legacy pure predicates.
  // require('util') remains Node's native util module.
  // ------------------------------------------------------------------
  var util = {
    isArray: function (value) { return Array.isArray(value); },
    isBoolean: function (value) { return typeof value === 'boolean'; },
    isNull: function (value) { return value === null; },
    isNullOrUndefined: function (value) { return value === null || value === undefined; },
    isNumber: function (value) { return typeof value === 'number'; },
    isString: function (value) { return typeof value === 'string'; },
    isSymbol: function (value) { return typeof value === 'symbol'; },
    isUndefined: function (value) { return value === undefined; },
    isRegExp: function (value) { return Object.prototype.toString.call(value) === '[object RegExp]'; },
    isObject: function (value) { return value !== null && typeof value === 'object'; },
    isDate: function (value) { return Object.prototype.toString.call(value) === '[object Date]'; },
    isError: function (value) { return value instanceof Error; },
    isFunction: function (value) { return typeof value === 'function'; },
    isPrimitive: function (value) {
      return value === null || (typeof value !== 'object' && typeof value !== 'function');
    }
  };
  globalThis.util = util;
  globalThis.$util = util;

  // ------------------------------------------------------------------
  // engines: source execution + full config for execScriptFile.
  // ------------------------------------------------------------------
  var enginesModule = builtin.engines;
  if (!enginesModule || typeof enginesModule !== 'object') {
    throw apiError('缺少 prelude 的 engines 模块');
  }

  var executionRegistry = Object.create(null);
  var executionSeq = 0;
  var engineEventsInstalled = false;

  function baseDirectory() {
    var scriptFile = typeof globalThis.__ajsScriptFile === 'string'
      ? globalThis.__ajsScriptFile : '';
    if (scriptFile) { return pathModule.dirname(scriptFile); }
    return process.cwd();
  }

  function resolveFromBase(value) {
    var text = String(value);
    return pathModule.isAbsolute(text) ? text : pathModule.resolve(baseDirectory(), text);
  }

  function normalizePathList(raw) {
    if (raw === null || raw === undefined) { return []; }
    var values = Array.isArray(raw) ? raw : [raw];
    var result = [];
    for (var i = 0; i < values.length; i++) {
      if (values[i] === null || values[i] === undefined) { continue; }
      var value = String(values[i]);
      if (value.length > 0) { result.push(resolveFromBase(value)); }
    }
    return result;
  }

  function optionalNumber(options, name, integer) {
    if (!Object.prototype.hasOwnProperty.call(options, name)
        || options[name] === null || options[name] === undefined) {
      return undefined;
    }
    var value = Number(options[name]);
    if (!isFinite(value) || value < 0) {
      throw new TypeError('engines.' + name + ' 必须是非负数字');
    }
    return integer ? Math.trunc(value) : value;
  }

  function executionOptions(options) {
    options = options === null || options === undefined ? {} : options;
    if (typeof options !== 'object' || Array.isArray(options)) {
      throw new TypeError('engines 配置必须是对象');
    }
    var result = {};
    var paths = normalizePathList(options.path);
    var workingDirectory;
    if (options.workingDirectory !== null && options.workingDirectory !== undefined
        && String(options.workingDirectory).length > 0) {
      workingDirectory = resolveFromBase(options.workingDirectory);
    } else if (paths.length > 0) {
      workingDirectory = paths[0];
    } else {
      workingDirectory = baseDirectory();
    }
    result.workingDirectory = workingDirectory;
    if (paths.length > 0) { result.configPath = paths; }
    if (options.arguments !== null && options.arguments !== undefined) {
      if (typeof options.arguments !== 'object' || Array.isArray(options.arguments)) {
        throw new TypeError('engines 配置的 arguments 必须是对象');
      }
      result.arguments = options.arguments;
    }
    var delay = optionalNumber(options, 'delay', false);
    var interval = optionalNumber(options, 'interval', false);
    var loopTimes = optionalNumber(options, 'loopTimes', true);
    if (delay !== undefined) { result.delay = delay; }
    if (interval !== undefined) { result.interval = interval; }
    if (loopTimes !== undefined) { result.loopTimes = loopTimes; }
    return result;
  }

  function needsExtendedFileConfig(options) {
    if (!options || typeof options !== 'object') { return false; }
    return options.delay !== undefined || options.interval !== undefined
      || options.loopTimes !== undefined || options.path !== undefined;
  }

  function callEngine(action, args) {
    if (typeof globalThis.__ajsHostValue !== 'function') {
      throw apiError('Node24 引擎桥不可用（缺少 __ajsHostValue）');
    }
    return globalThis.__ajsHostValue({
      kind: 'engines',
      action: action,
      args: args || {}
    });
  }

  function ensureEngineEvents() {
    if (engineEventsInstalled) { return; }
    engineEventsInstalled = true;
    if (typeof globalThis.__ajsOn !== 'function') {
      throw apiError('Node24 事件桥不可用（缺少 __ajsOn）');
    }
    globalThis.__ajsOn('__ajs_engine_event', function (payload) {
      if (!payload || typeof payload !== 'object' || !payload.token) { return; }
      var execution = executionRegistry[payload.token];
      if (!execution) { return; }
      var args = Array.isArray(payload.args) ? payload.args : [];
      execution._dispatch(payload.event, args, payload.engineId);
    });
  }

  function makeEngineInfo(info) {
    var engine = {
      id: Number(info.id),
      engine: info.engine || '',
      className: info.className || '',
      source: info.source === undefined ? '' : info.source,
      execArgv: info.execArgv || {},
      get executionConfig() {
        return { path: [], workingDirectory: '', arguments: this.execArgv || {} };
      },
      forceStop: function () {
        return callEngine('stop', { target: engine.id });
      }
    };
    return engine;
  }

  function makeExecution(token, meta) {
    var listeners = Object.create(null);
    var buffered = Object.create(null);
    var latestEngineId = null;
    var latestEngine = null;
    var promise = null;
    var done = false;

    var execution = {
      id: -1,
      token: token,
      source: meta.source,
      sourceFile: meta.sourceFile,
      executionConfig: meta.executionConfig
    };

    function flush(event) {
      var pending = buffered[event];
      var handlers = listeners[event];
      if (!pending || !handlers) { return; }
      while (pending.length > 0) {
        var item = pending.shift();
        execution._dispatch(item.event, item.args, item.engineId, true);
      }
    }

    execution._dispatch = function (event, args, engineId, replay) {
      if (!event) { return; }
      if (engineId !== undefined && engineId !== null) {
        latestEngineId = Number(engineId);
      }
      if (event === 'start') { done = false; }
      else if (event === 'success' || event === 'exception') { done = true; }
      var handlers = listeners[event];
      if (!handlers || handlers.length === 0) {
        if (!replay) {
          if (!buffered[event]) { buffered[event] = []; }
          buffered[event].push({ event: event, args: args || [], engineId: engineId });
        }
        return;
      }
      var callArgs;
      if (event === 'exception') {
        var error = new Error(args.length > 0 ? String(args[0]) : '子引擎执行异常');
        error.name = 'ScriptExecutionError';
        callArgs = [execution, error];
      } else {
        callArgs = [execution];
      }
      handlers = handlers.slice();
      for (var i = 0; i < handlers.length; i++) {
        try { handlers[i].apply(execution, callArgs); }
        catch (listenerError) {
          console.error('[Auto.js] execution 监听异常（' + event + '）：', listenerError);
        }
      }
    };

    execution.on = function (event, handler) {
      if (typeof handler !== 'function') {
        throw new TypeError('execution.on 的监听必须是函数');
      }
      ensureEngineEvents();
      if (!listeners[event]) { listeners[event] = []; }
      listeners[event].push(handler);
      if (buffered[event] && buffered[event].length > 0) { flush(event); }
      return execution;
    };

    execution.once = function (event, handler) {
      if (typeof handler !== 'function') {
        throw new TypeError('execution.once 的监听必须是函数');
      }
      var onceHandler = function () {
        execution.off(event, onceHandler);
        return handler.apply(execution, arguments);
      };
      execution.on(event, onceHandler);
      return execution;
    };

    execution.off = function (event, handler) {
      var handlers = listeners[event];
      if (!handlers) { return execution; }
      var index = handlers.indexOf(handler);
      if (index >= 0) { handlers.splice(index, 1); }
      return execution;
    };

    execution.forceStop = function () {
      if (latestEngineId === null) {
        throw apiError('execution.engine 尚未创建，无法 forceStop；请等待 start 事件或 engine()');
      }
      return callEngine('stop', { target: latestEngineId });
    };

    execution.engine = function () {
      if (promise) { return promise; }
      promise = new Promise(function (resolve, reject) {
        var startedAt = Date.now();
        function poll() {
          var info;
          try {
            info = callEngine('execution', { token: token });
          } catch (error) {
            reject(error);
            return;
          }
          if (info && info.engineId !== undefined && info.engineId !== null) {
            latestEngineId = Number(info.engineId);
            latestEngine = makeEngineInfo(Object.assign({}, info, { id: latestEngineId }));
            resolve(latestEngine);
            return;
          }
          if (info && info.done) {
            reject(new Error('子执行已结束但未创建引擎'));
            return;
          }
          if (Date.now() - startedAt > 30000) {
            reject(new Error('等待子引擎创建超时（30 秒）'));
            return;
          }
          setTimeout(poll, 50);
        }
        poll();
      });
      return promise;
    };

    Object.defineProperty(execution, 'engineOrNull', {
      enumerable: true,
      get: function () {
        if (!done) {
          try {
            var info = callEngine('execution', { token: token });
            if (info && info.engineId !== undefined && info.engineId !== null) {
              latestEngineId = Number(info.engineId);
              latestEngine = makeEngineInfo(Object.assign({}, info, { id: latestEngineId }));
            }
          } catch (ignored) { /* execution record may already be gone */ }
        }
        return latestEngine;
      }
    });

    return execution;
  }

  function startExecution(payload, meta) {
    // prelude.myEngine() both supplies the real owner id and installs the
    // prelude event listener used by every existing engines method.
    var owner = enginesModule.myEngine();
    var token = 'node-engine-' + owner.id + '-' + Date.now() + '-' + (++executionSeq);
    var execution = makeExecution(token, meta);
    executionRegistry[token] = execution;
    ensureEngineEvents();
    try {
      var request = Object.assign({ token: token }, payload);
      var result = callEngine('exec', request);
      if (result && result.executionId !== undefined) {
        execution.id = Number(result.executionId);
      }
    } catch (error) {
      delete executionRegistry[token];
      throw error;
    }
    return execution;
  }

  function execScript(name, source, options) {
    if (typeof name !== 'string' || name.length === 0) {
      throw new TypeError('engines.execScript(name, source)：name 必须是非空字符串');
    }
    if (typeof source !== 'string') {
      throw new TypeError('engines.execScript(name, source)：source 必须是字符串');
    }
    var config = executionOptions(options);
    return startExecution({
      name: name,
      source: source,
      workingDirectory: config.workingDirectory,
      configPath: config.configPath,
      arguments: config.arguments,
      delay: config.delay,
      interval: config.interval,
      loopTimes: config.loopTimes
    }, {
      source: name,
      sourceFile: '',
      executionConfig: config
    });
  }

  function execScriptFile(path, options) {
    if (typeof path !== 'string' || path.length === 0) {
      throw new TypeError('engines.execScriptFile(path)：path 必须是非空字符串');
    }
    var original = enginesModule.__ajsLegacyExecScriptFile;
    if (!needsExtendedFileConfig(options)) {
      return original.call(enginesModule, path, options);
    }
    var absolute = resolveFromBase(path);
    var config = executionOptions(options);
    var hasWorkingDirectory = options && options.workingDirectory !== null
      && options.workingDirectory !== undefined && String(options.workingDirectory).length > 0;
    var hasPath = options && options.path !== null && options.path !== undefined
      && (!Array.isArray(options.path) || options.path.length > 0);
    // execScriptFile 未显式给 workingDirectory/path 时，子脚本工作目录是脚本文件父目录，
    // 与 prelude.execScriptFile 的既有语义一致（不是入口脚本目录）。
    if (!hasWorkingDirectory && !hasPath) {
      config.workingDirectory = pathModule.dirname(absolute);
    }
    return startExecution({
      path: absolute,
      workingDirectory: config.workingDirectory,
      configPath: config.configPath,
      arguments: config.arguments,
      delay: config.delay,
      interval: config.interval,
      loopTimes: config.loopTimes
    }, {
      source: absolute,
      sourceFile: absolute,
      executionConfig: config
    });
  }

  if (typeof enginesModule.execScript !== 'function') {
    enginesModule.execScript = execScript;
  }
  if (typeof enginesModule.execScriptFile === 'function') {
    enginesModule.__ajsLegacyExecScriptFile = enginesModule.execScriptFile;
    enginesModule.execScriptFile = execScriptFile;
  }
  globalThis.engines = enginesModule;
  globalThis.$engines = enginesModule;
  autojs.engines = enginesModule;

  globalThis.__ajsLegacyRuntime = {
    revision: 1,
    app: appModule,
    events: globalEvents,
    util: util,
    engines: enginesModule
  };
})();
