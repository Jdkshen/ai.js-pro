/*
 * Node24 prelude（task-58/59 冻结接口）。
 *
 * native 桥（libajsnode24bridge.so）只提供单向同步回调：
 *   Node: globalThis.__autojsHost(JSON.stringify(request)) -> JSON string
 * Java: Node24Engine.Host.invoke(String) -> String
 * 没有 Java→Node 反向调用，所以事件用「轮询」补：
 *   1) Java 侧（Node24HostBridge.enqueueEvent / B 的 NodeUiHost）把事件放队列；
 *   2) Node 侧 setInterval 调 __autojsPoll() -> {kind:"poll"} 取走事件，同步派发给 JS 监听。
 *
 * 冻结命名（B 依赖）：
 *   __autojsHost(request)        安全封装（返回 value，失败抛中文 Error；原始桥为 __autojsHostRaw）
 *   __ajsRegisterModule(name, factory)
 *   __ajsBuiltinModules          内置模块表（rhino/app）
 *   __autojsPoll()               手动拉取一轮事件（返回处理条数）
 *   __ajsProjectDir / __ajsScriptFile   由 Java 注入
 */
(function () {
  'use strict';
  if (globalThis.__ajsPreludeInstalled) { return; }
  globalThis.__ajsPreludeInstalled = true;

  var projectDir = typeof globalThis.__ajsProjectDir === 'string' ? globalThis.__ajsProjectDir : '';
  var scriptFile = typeof globalThis.__ajsScriptFile === 'string' ? globalThis.__ajsScriptFile : '';

  // Node24 原生进程的初始 cwd 是 "/"；脚本里的相对路径（axios 示例写 qq.com.html）
  // 会直接落到只读根目录。这里在用户脚本执行前调用真正的 process.chdir；
  // 不覆盖 process.cwd，也不按 Android 平台做条件分支（裸 node 测试同样生效）。
  // projectDir 由 Java 按脚本位置计算（工程根/脚本父目录），与 Pro 的 workingDirectory 对齐。
  var executionDir = projectDir || (scriptFile ? scriptFile.replace(/[\\/][^\\/]*$/, '') : '');
  if (executionDir) {
    try {
      process.chdir(executionDir);
    } catch (cwdError) {
      throw hostError('无法切换到脚本工作目录：' + executionDir + '（'
        + (cwdError && cwdError.message ? cwdError.message : cwdError) + '）');
    }
  }

  // ------------------------------------------------------------------
  // 宿主桥安全封装
  // ------------------------------------------------------------------
  var rawHost = typeof globalThis.__autojsHost === 'function' ? globalThis.__autojsHost : null;
  if (rawHost) { globalThis.__autojsHostRaw = rawHost; }

  function hostError(message) {
    var error = new Error('[Auto.js] ' + message);
    error.name = 'AutoJsHostError';
    return error;
  }

  /** 原生桥（string -> string）。B 的 ui.js 也按这个契约调用，不能被对象封装破坏。 */
  function hostRaw(request) {
    if (!rawHost) {
      throw hostError('Node24 宿主桥不可用：当前运行环境没有 __autojsHost。'
        + '（请确认在 App 的 Node24 引擎里运行，而不是裸 node）');
    }
    var payload = typeof request === 'string' ? request : JSON.stringify(request || {});
    try {
      return rawHost(payload);
    } catch (error) {
      throw hostError('宿主桥通信失败：' + (error && error.message ? error.message : error));
    }
  }

  /** 便捷封装：发请求 → 校验 ok → 返回 value（prelude 内部/需要字段级结果时用）。 */
  function hostValue(request) {
    var raw = hostRaw(request);
    var response;
    try {
      response = JSON.parse(raw);
    } catch (error) {
      throw hostError('宿主桥通信失败（JSON 解析）：' + (error && error.message ? error.message : error));
    }
    if (!response || response.ok !== true) {
      var message = response && response.error ? response.error : '未知错误';
      throw hostError(message);
    }
    return response.value;
  }

  // 冻结契约：__autojsHost 保持「JSON 字符串进 / JSON 字符串出」的原生语义（B 的 ui.js 依赖）；
  // 需要 {ok,value} 封装时用 __ajsHostValue。
  globalThis.__autojsHost = hostRaw;
  globalThis.__ajsHost = hostRaw;
  globalThis.__ajsHostValue = hostValue;

  // ------------------------------------------------------------------
  // wire <-> JS
  // ------------------------------------------------------------------
  var JAVA_OBJECT_MARK = '__ajsJavaObject__';

  function isJavaObject(value) {
    return !!(value && typeof value === 'object' && value[JAVA_OBJECT_MARK] === true);
  }

  function okValue(value) {
    if (value === null || value === undefined) { return null; }
    if (typeof value !== 'object') { return value; }
    if (value === true || value === 0) { return value; }
    if (Object.prototype.hasOwnProperty.call(value, '$obj')) {
      return javaObject(value.$obj, value.$class);
    }
    if (Object.prototype.hasOwnProperty.call(value, '$arr')) {
      var list = value.$arr || [];
      var out = [];
      for (var i = 0; i < list.length; i++) { out.push(okValue(list[i])); }
      return out;
    }
    return value;
  }

  function toWire(value) {
    if (value === null || value === undefined) { return null; }
    var type = typeof value;
    if (type === 'string' || type === 'number' || type === 'boolean') { return value; }
    if (type === 'function') {
      throw hostError('暂不支持把 JS 函数传给 Java：预编译 native 桥只有 Node→Java 单向调用，'
        + '回调/onClickListener 请用 __autojsOn() 或 __ajsRegisterModule 由宿主投递事件。');
    }
    if (isJavaObject(value)) { return { $obj: value.__handle }; }
    if (Array.isArray(value)) {
      var array = [];
      for (var i = 0; i < value.length; i++) { array.push(toWire(value[i])); }
      return { $arr: array };
    }
    if (value && value.__packageRef === true) {
      return resolvePackageValue(value.__path);
    }
    throw hostError('不能把普通 JS 对象传给 Java（' + Object.prototype.toString.call(value) + '）；'
      + '请用 Java 类/对象或基本类型。');
  }

  function toWireArray(args) {
    var list = [];
    for (var i = 0; i < args.length; i++) { list.push(toWire(args[i])); }
    return list;
  }

  function wrapJavaResult(value) {
    if (!value || typeof value !== 'object') { return value; }
    // okValue already converts wire object descriptors into Java proxies. Reading
    // $obj/$arr/$class on that proxy would perform unrelated reflected lookups.
    if (isJavaObject(value)) { return value; }
    if (value.$obj !== undefined) { return javaObject(value.$obj, value.$class); }
    if (Array.isArray(value.$arr)) {
      var wrapped = [];
      for (var i = 0; i < value.$arr.length; i++) { wrapped.push(wrapJavaResult(value.$arr[i])); }
      return wrapped;
    }
    if (value.$class !== undefined && value.$obj === undefined) { return classRef(value.$class); }
    return value;
  }

  function javaCall(request) {
    var full = { kind: 'java' };
    for (var key in request) { if (Object.prototype.hasOwnProperty.call(request, key)) { full[key] = request[key]; } }
    return wrapJavaResult(okValue(hostValue(full)));
  }

  // ------------------------------------------------------------------
  // Java 对象代理（RhinoJavaObject 等价物）
  // ------------------------------------------------------------------
  var memberCache = Object.create(null);

  function memberPrefix(target) {
    if (target.cls) { return 'class:' + target.cls; }
    return 'obj:' + target.obj;
  }

  function memberInfo(target, name) {
    var key = memberPrefix(target) + '#' + name;
    if (!Object.prototype.hasOwnProperty.call(memberCache, key)) {
      var request = { op: 'member', name: name };
      if (target.cls) { request.cls = target.cls; } else { request.obj = target.obj; }
      memberCache[key] = javaCall(request);
    }
    return memberCache[key];
  }

  function memberAccess(target, name) {
    var info = memberInfo(target, name);
    if (info && info.kind === 'method') {
      if (!info.invoke) {
        // Cache the callable and invariant JSON only. Each invocation still crosses
        // the host bridge, converts its current arguments and selects its overload.
        var request = { kind: 'java', op: target.cls ? 'static' : 'call', method: name };
        if (target.cls) { request.cls = target.cls; } else { request.obj = target.obj; }
        var prefix = JSON.stringify(request).slice(0, -1) + ',"args":';
        var noArgs = prefix + '[]}';
        info.invoke = function () {
          var payload = arguments.length === 0 ? noArgs
            : prefix + JSON.stringify(toWireArray(arguments)) + '}';
          return wrapJavaResult(okValue(hostValue(payload)));
        };
      }
      return info.invoke;
    }
    if (info && info.kind === 'field') { return okValue(info.value); }
    return undefined;
  }

  function javaObject(handle, className) {
    var target = {};
    target[JAVA_OBJECT_MARK] = true;
    target.__handle = handle;
    target.__class = className;
    return new Proxy(target, {
      get: function (self, prop) {
        if (prop === JAVA_OBJECT_MARK) { return true; }
        if (prop === '__handle') { return handle; }
        if (prop === '__class') { return className; }
        if (prop === 'toString') {
          return function () {
            try {
              var method = memberAccess({ obj: handle }, 'toString');
              if (typeof method === 'function') { return method(); }
            } catch (ignored) { /* 回退到调试字符串 */ }
            return '[JavaObject ' + className + '#' + handle + ']';
          };
        }
        if (prop === 'valueOf') { return function () { return self; }; }
        if (typeof prop === 'symbol') { return undefined; }
        return memberAccess({ obj: handle }, prop);
      },
      set: function (self, prop, value) {
        javaCall({ op: 'setField', obj: handle, name: prop, value: toWire(value) });
        return true;
      }
    });
  }

  var classCache = Object.create(null);

  /** name 是否是可解析的 Java 类（带缓存，避免每次属性访问都打一次宿主桥）。 */
  function resolveClass(name) {
    if (Object.prototype.hasOwnProperty.call(classCache, name)) {
      return classCache[name];
    }
    var resolved = false;
    try {
      javaCall({ op: 'class', name: name });
      resolved = true;
    } catch (ignored) {
      resolved = false;
    }
    classCache[name] = resolved;
    return resolved;
  }

  /** Java 类代理：支持 new / 静态方法 / 静态字段。 */
  function classRef(className) {
    var fn = function () { };
    fn.__classRef = true;
    fn.__className = className;
    return new Proxy(fn, {
      get: function (self, prop) {
        if (prop === '__classRef') { return true; }
        if (prop === '__className') { return className; }
        // 真实 Java 类代理也要能作为 extends 基类：返回代理目标函数的 prototype 对象。
        if (prop === 'prototype') { return self.prototype; }
        if (prop === 'getName') { return function () { return className; }; }
        if (prop === 'toString') { return function () { return '[JavaClass ' + className + ']'; }; }
        if (typeof prop === 'symbol') { return undefined; }
        return memberAccess({ cls: className }, prop);
      },
      apply: function (self, thisArg, args) {
        // 极少数写法把类当函数调用（Pro 里可用），按静态方法无法确定，直接给出明确提示。
        throw hostError('Java 类不能直接调用：' + className + '（请用 new 构造或调用具体静态方法）');
      },
      construct: function (self, args) {
        var value = javaCall({ op: 'newRef', cls: className, args: toWireArray(args) });
        if (value && value.$obj) { return javaObject(value.$obj, value.$class); }
        return value;
      }
    });
  }

  /**
   * 未安装/无法解析的 Java 类占位：允许 `class X extends android.foo.Bar` 语法先通过，
   * 但任何构造/静态调用都抛中文错误，提示 Node24 暂不支持继承 Java 类（defineClass 需反向通道）。
   */
  function unsupportedClassRef(className) {
    var message = '未安装/无法解析的 Java 类：' + className + '。'
      + 'Node24 暂不支持继承 Java 类或自定义 View（$java.defineClass 需要 Java→Node 反向回调，当前 native 桥只有单向调用）；'
      + '请改用 Node UI 的 ui.Widget + layoutXml，或把自定义 View 放到宿主 Java 侧。';
    var fn = function () { throw hostError(message); };
    fn.__classRef = true;
    fn.__className = className;
    fn.__unsupportedClass = true;
    return new Proxy(fn, {
      get: function (self, prop) {
        if (prop === '__classRef') { return true; }
        if (prop === '__className' || prop === '__unsupportedClass') { return true; }
        if (prop === 'prototype') { return {}; }
        if (prop === 'toString') { return function () { return '[UnsupportedJavaClass ' + className + ']'; }; }
        if (typeof prop === 'symbol') { return undefined; }
        return function () { throw hostError(message); };
      },
      apply: function () { throw hostError(message); },
      construct: function () { throw hostError(message); }
    });
  }

  function packageRef(pathName) {
    if (typeof pathName !== 'string' || pathName.length === 0) {
      throw hostError('Java 包名不能为空');
    }
    var fn = function () { };
    fn.__packageRef = true;
    fn.__path = pathName;
    return new Proxy(fn, {
      get: function (self, prop) {
        if (prop === '__packageRef') { return true; }
        if (prop === '__path') { return pathName; }
        if (prop === 'toString') { return function () { return '[JavaPackage ' + pathName + ']'; }; }
        if (typeof prop === 'symbol') { return undefined; }
        var child = pathName + '.' + prop;
        // task-176：只有末段是「大写开头的类名」才触发类解析；中间 package 段（view/os/io…）
        // 一律按包处理，绝不对 android.view 这类包名发 class 查询（会被宿主当 ClassNotFound）。
        if (/^[A-Z]/.test(prop)) {
          // 类优先：java.lang.Integer.MAX_VALUE、android.view.LayoutInflater.from(...) 都靠这里
          if (resolveClass(child)) { return classRef(child); }
          // task-155 follow-up：未安装类给可 extends 的占位（构造时抛中文错误），
          // 而不是 `Class extends value ... undefined` 的 TypeError。
          return unsupportedClassRef(child);
        }
        return packageRef(child);
      },
      apply: function (self, thisArg, args) {
        var parts = pathName.split('.');
        var method = parts.pop();
        var cls = parts.join('.');
        return javaCall({ op: 'static', cls: cls, method: method, args: toWireArray(args) });
      },
      construct: function (self, args) {
        return javaCall({ op: 'new', cls: pathName, args: toWireArray(args) });
      }
    });
  }

  /** 静态字段/类名引用（Integer.MAX_VALUE 这类在 JS 里只能以包代理存在，用到时才解析）。 */
  function resolvePackageValue(pathName) {
    var parts = pathName.split('.');
    for (var i = parts.length - 1; i > 0; i--) {
      var cls = parts.slice(0, i).join('.');
      var field = parts.slice(i).join('.');
      try {
        return javaCall({ op: 'field', cls: cls, name: field });
      } catch (ignored) { /* 继续更短的类名 */ }
    }
    throw hostError('无法解析 Java 静态字段：' + pathName
      + '（如果是类名，请直接用 new / 静态方法调用；类对象字面量本身不能当值传）');
  }

  // ------------------------------------------------------------------
  // 轮询式事件通道
  // ------------------------------------------------------------------
  var listeners = Object.create(null);
  var pollTimer = null;
  var polling = false;
  var keepAliveTimer = null;
  var POLL_INTERVAL = 25;

  function dispatchEvent(event) {
    if (!event || !event.type) { return; }
    var handlers = listeners[event.type];
    if (!handlers || handlers.length === 0) { return; }
    for (var i = 0; i < handlers.length; i++) {
      try {
        handlers[i](event.payload === undefined ? null : event.payload);
      } catch (error) {
        console.error('[Auto.js] 事件监听执行异常（' + event.type + '）：', error);
      }
    }
  }

  function pollOnce() {
    if (polling) { return 0; }
    polling = true;
    try {
      var batch = hostValue({ kind: 'poll' });
      var events = batch && batch.$events ? batch.$events : [];
      for (var i = 0; i < events.length; i++) { dispatchEvent(events[i]); }
      return events.length;
    } finally {
      polling = false;
    }
  }
  globalThis.__autojsPoll = pollOnce;

  function ensurePolling() {
    if (pollTimer) { return; }
    pollTimer = setInterval(function () {
      try {
        pollOnce();
      } catch (error) {
        if (String(error && error.message).indexOf('宿主桥不可用') >= 0) {
          clearInterval(pollTimer);
          pollTimer = null;
          return;
        }
        console.error('[Auto.js] 事件轮询失败：', error);
      }
    }, POLL_INTERVAL);
    if (pollTimer.unref) { pollTimer.unref(); }
  }

  function on(type, handler) {
    if (typeof handler !== 'function') { throw hostError('事件监听必须是函数'); }
    if (!listeners[type]) { listeners[type] = []; }
    listeners[type].push(handler);
    ensurePolling();
    return function off() { off(type, handler); };
  }

  function off(type, handler) {
    var handlers = listeners[type];
    if (!handlers) { return; }
    var index = handlers.indexOf(handler);
    if (index >= 0) { handlers.splice(index, 1); }
  }

  // 冻结接口之外的便利别名：宿主/UI 模块可直接注册事件监听。
  globalThis.__autojsOn = on;
  globalThis.__autojsOff = off;
  globalThis.__ajsOn = on;
  globalThis.__ajsOff = off;

  function setKeepAliveFlag(running) {
    try {
      // task-63：告诉 Java 宿主（:node24 的 Node24Service）保活，native execute 返回后它阻塞等待 STOP/关闭。
      hostRaw({ kind: 'keepalive', running: !!running });
    } catch (error) {
      console.warn('[Auto.js] keepalive 通知宿主失败：', error);
    }
  }

  function keepRunning() {
    setKeepAliveFlag(true);
    if (keepAliveTimer) { return; }
    // 保持 Node 事件循环存活（UI 项目让 Activity/事件轮询继续跑）；stopRunning 可退出。
    keepAliveTimer = setInterval(function () { }, 3600 * 1000);
    ensurePolling();
  }
  function stopRunning() {
    setKeepAliveFlag(false);
    if (keepAliveTimer) { clearInterval(keepAliveTimer); keepAliveTimer = null; }
  }

  // ------------------------------------------------------------------
  // 模块系统
  // ------------------------------------------------------------------
  var moduleFactories = Object.create(null);
  var moduleCache = Object.create(null);

  function registerModule(name, factory) {
    if (typeof name !== 'string' || !name) { throw hostError('__ajsRegisterModule(name, factory)：name 不能为空'); }
    if (typeof factory !== 'function') { throw hostError('__ajsRegisterModule(' + name + ', factory)：factory 必须是函数'); }
    moduleFactories[name] = factory;
    delete moduleCache[name];
  }
  globalThis.__ajsRegisterModule = registerModule;
  globalThis.__autojsRegisterModule = registerModule; // 兼容旧提案命名

  function moduleApi() {
    return {
      // B 的 ui.js 会优先用 globalThis.__autojsHost（string 契约），api.host 作为兼容备选也返回 string。
      host: hostRaw,
      java: javaApi,
      packages: packageRef,
      projectDir: projectDir,
      scriptFile: scriptFile,
      on: on,
      off: off,
      keepRunning: keepRunning,
      stopRunning: stopRunning,
      registerModule: registerModule,
      $autojs: autojs
    };
  }

  function loadRegistered(name) {
    if (Object.prototype.hasOwnProperty.call(moduleCache, name)) { return moduleCache[name]; }
    var factory = moduleFactories[name];
    if (!factory) { return undefined; }
    var value = factory(moduleApi());
    if (value === undefined) { value = {}; }
    moduleCache[name] = value;
    return value;
  }

  // ------------------------------------------------------------------
  // Java / Auto.js API
  // ------------------------------------------------------------------
  var contextCache = null;

  var javaApi = {
    defineClass: function () {
      return Promise.reject(hostError('$autojs.java.defineClass 暂不支持：JS 继承 Java 类并覆盖方法需要'
        + ' Java→Node 反向回调，而预编译 native 桥只有单向调用。'
        + '可选替代：用 __autojsOn() 接收宿主投递的事件，或把逻辑放到宿主侧 Java 类里。'));
    },
    Packages: {
      java: packageRef('java'),
      javax: packageRef('javax'),
      android: packageRef('android'),
      androidx: packageRef('androidx'),
      com: packageRef('com'),
      org: packageRef('org'),
      net: packageRef('net'),
      io: packageRef('io')
    },
    getClass: function (className) {
      if (typeof className !== 'string' || className.trim() === '') {
        throw hostError('$java.getClass/findClass 需要类名字符串');
      }
      var isArrayDescriptor = className.charAt(0) === '[';
      if (!isArrayDescriptor && className.indexOf('.') < 0) {
        // task-176：不能省略包名；给出完整类名格式提示。
        throw hostError('$java.getClass/findClass 需要完整类名（含包名），例如 android.view.KeyEvent；不能省略包名：' + className);
      }
      try {
        javaCall({ op: 'class', name: className }); // 校验存在
      } catch (error) {
        throw hostError('找不到 Java 类（已按完整类名解析）：' + className
          + (error && error.message ? '；' + error.message : ''));
      }
      // task-136-fix2：必须返回 classRef（construct→newRef→javaObject 句柄），
      // 返回 packageRef 时 new String 会走旧的 new op→toWire 原始值，Proxy construct 报
      // "trap returned non-object"。
      return classRef(className);
    },
    findClass: function (className) {
      return javaApi.getClass(className);
    }
  };

  var autojs = globalThis.$autojs || {};
  autojs.java = javaApi;
  Object.defineProperty(autojs, 'androidContext', {
    configurable: true,
    get: function () {
      if (!contextCache) { contextCache = okValue(hostValue({ kind: 'java', op: 'context' })); }
      return contextCache;
    }
  });
  autojs.projectDir = projectDir;
  autojs.scriptFile = scriptFile;
  autojs.keepRunning = keepRunning;
  autojs.stopRunning = stopRunning;
  autojs.on = on;
  autojs.off = off;
  autojs.pollEvents = pollOnce;
  autojs.registerModule = registerModule;
  autojs.isRoot = false;
  globalThis.$autojs = autojs;
  globalThis.$java = javaApi;
  globalThis.__ajsAutojs = autojs;

  // task-176：Pro 兼容的包链式类访问默认可用（android.view.KeyEvent / java.io.File 等），
  // 不再要求脚本先 require('rhino').install()。require('rhino').install() 仍可调用（幂等）。
  function installJavaGlobals() {
    globalThis.Packages = javaApi.Packages;
    globalThis.java = packageRef('java');
    globalThis.javax = packageRef('javax');
    globalThis.android = packageRef('android');
    globalThis.androidx = packageRef('androidx');
    globalThis.com = packageRef('com');
    globalThis.org = packageRef('org');
    globalThis.net = packageRef('net');
    globalThis.io = packageRef('io');
  }
  installJavaGlobals();

  // Rhino 兼容模块：install() 挂全局 Java 包/类访问（Auto.js Pro 的 require('rhino').install()）。
  var rhinoInstalled = false;
  var rhinoModule = {
    install: function () {
      if (rhinoInstalled) { return rhinoModule; }
      rhinoInstalled = true;
      installJavaGlobals();
      ensurePolling();
      return rhinoModule;
    },
    getClass: javaApi.getClass,
    defineClass: javaApi.defineClass,
    Packages: javaApi.Packages,
    java: javaApi
  };

  // app 模块最小面（示例用到 app.openUrl / app.startActivity / app.viewFile 等）。
  function appCall(action, args) { return hostValue({ kind: 'app', action: action, args: args || [] }); }
  var appModule = {
    openUrl: function (url) { return appCall('openUrl', [toWire(url)]); },
    startActivity: function (name) { return appCall('startActivity', [toWire(name)]); },
    // task-206：Auto.js app.launch(pkg) 语义返回 boolean；工程按返回值判断是否发出
    // pageAction（返回 falsy 会静默丢弃 获取屏幕/定位/识别/开始）。
    launch: function (pkg) {
      appCall('startActivity', [toWire(pkg)]);
      return true;
    },
    viewFile: function (path) {
      // 与 image 一致：相对路径按入口脚本目录解析，宿主只接收绝对路径。
      var absolute = resolveImagePath(path, 'app.viewFile 路径');
      var value = appCall('viewFile', [absolute]);
      if (typeof value !== 'boolean') {
        throw hostError('app.viewFile 返回值非法（期望 boolean）：' + String(value));
      }
      return value;
    },
    getPackageName: function () { return appCall('getPackageName', []); },
    getVersionName: function () { return appCall('versionName', []); },
    getVersionCode: function () { return appCall('versionCode', []); },
    getAppName: function () { return appCall('getAppName', []); },
    getClipboard: function () { return appCall('getClipboard', []); },
    setClipboard: function (text) { return appCall('setClipboard', [toWire(String(text))]); },
    sendBroadcast: function (action) { return appCall('sendBroadcast', [toWire(action)]); },
    isInstalled: function (pkg) { return appCall('isInstalled', [toWire(pkg)]); },
    toast: function (text) { appCall('toast', [toWire(String(text))]); }
  };

  // task-133 P1：device/files/storages/events/engines/threads（能力域转发宿主）。
  function domain(kind, action, args) { return hostValue({ kind: kind, action: action, args: args || {} }); }
  var deviceModule = {
    getInfo: function () { return domain('device', 'info'); },
    get width() { return domain('device', 'info').width; },
    get height() { return domain('device', 'info').height; },
    get sdkInt() { return domain('device', 'info').sdkInt; },
    get brand() { return domain('device', 'info').brand; },
    get model() { return domain('device', 'info').model; },
    get density() { return domain('device', 'info').density; }
  };
  var filesModule = {
    exists: function (path) { return domain('files', 'exists', { path: path }); },
    isFile: function (path) { return domain('files', 'isFile', { path: path }); },
    isDir: function (path) { return domain('files', 'isDir', { path: path }); },
    mkdir: function (path) { return domain('files', 'mkdir', { path: path }); },
    remove: function (path) { return domain('files', 'remove', { path: path }); },
    list: function (path) { return domain('files', 'list', { path: path }); },
    stat: function (path) { return domain('files', 'stat', { path: path }); },
    read: function (path) { return domain('files', 'read', { path: path }); },
    write: function (path, text) { return domain('files', 'writeText', { path: path, text: String(text) }); },
    readText: function (path) { return domain('files', 'readText', { path: path }); },
    writeText: function (path, text) { return domain('files', 'writeText', { path: path, text: String(text) }); }
  };
  var storagesModule = {
    get: function (key, name) { return domain('storages', 'get', { key: key, name: name || 'default' }); },
    put: function (key, value, name) { return domain('storages', 'put', { key: key, value: String(value), name: name || 'default' }); },
    remove: function (key, name) { return domain('storages', 'remove', { key: key, name: name || 'default' }); },
    clear: function (name) { return domain('storages', 'clear', { name: name || 'default' }); }
  };
  var eventsModule = {
    emit: function (type, payload) { return domain('events', 'emit', { type: type, payload: payload || {} }); }
  };
  // ------------------------------------------------------------------
  // 跨引擎（真实主进程 ScriptEngineService，经 kind:"engines" 同步桥）
  // 只在这一能力域内建 Node 侧句柄；引擎身份来自主进程，不在 :node24 伪造。
  // ------------------------------------------------------------------
  var engineEventInstalled = false;
  var engineListeners = Object.create(null);
  // Node 冷启动时 Java 可能先收到来自 Rhino 的 emit，而脚本还没执行到 on()。
  // 按 engineId/type 暂存，挂上监听后补发；不把消息静默丢掉。
  var engineBuffered = Object.create(null);
  var executionRegistry = Object.create(null);
  var executionSeq = 0;
  var myEngineCache = null;

  function engineBridge(action, args) {
    ensureEngineEvents();
    return domain('engines', action, args || {});
  }

  function engineListenerTable(engineId, type, create) {
    var key = String(engineId);
    var byType = engineListeners[key];
    if (!byType && create) {
      byType = engineListeners[key] = Object.create(null);
    }
    if (!byType) { return null; }
    var list = byType[type];
    if (!list && create) { list = byType[type] = []; }
    return list || null;
  }

  function dispatchLocalEngineEvent(engineId, type, args) {
    var list = engineListenerTable(engineId, type, false);
    if (!list) {
      var key = String(engineId);
      var byType = engineBuffered[key];
      if (!byType) { byType = engineBuffered[key] = Object.create(null); }
      var queue = byType[type];
      if (!queue) { queue = byType[type] = []; }
      if (queue.length < 64) { queue.push({ args: args || [] }); }
      return;
    }
    for (var i = 0; i < list.length; i++) {
      try {
        list[i].apply(null, args || []);
      } catch (error) {
        console.error('[Auto.js] engines 事件监听异常（' + type + '）：', error);
      }
    }
  }

  function flushEngineEvents(engineId, type) {
    var key = String(engineId);
    var byType = engineBuffered[key];
    if (!byType || !byType[type] || byType[type].length === 0) { return; }
    var list = engineListenerTable(engineId, type, false);
    if (!list) { return; }
    var queue = byType[type];
    while (queue.length > 0) {
      var item = queue.shift();
      dispatchLocalEngineEvent(engineId, type, item.args);
    }
  }

  function ensureEngineEvents() {
    if (engineEventInstalled) { return; }
    engineEventInstalled = true;
    // 宿主把 Rhino/子执行事件投进轮询队列；这里按 execution token / engineId 路由。
    on('__ajs_engine_event', function (payload) {
      if (!payload || typeof payload !== 'object') { return; }
      var args = Array.isArray(payload.args) ? payload.args : [];
      if (payload.token && executionRegistry[payload.token]) {
        executionRegistry[payload.token]._dispatch(payload.event, args, payload.engineId);
      }
      if (payload.engineId !== undefined && payload.engineId !== null) {
        dispatchLocalEngineEvent(Number(payload.engineId), payload.event, args);
      }
    });
  }

  // ------------------------------------------------------------------
  // task-197：Pro 兼容 —— 第三方 Node 工程会用
  //   engines.myEngine().executionConfig.projectConfig / getProjectDir()
  //   engines.myEngine().getClass().getName()
  // 这里提供 JS 侧 ExecutionConfig 兼容对象（projectConfig 从入口目录 project.json 读取，
  // 避免走 Gson/setProjectConfig 的 Java 反射路径）。
  // ------------------------------------------------------------------
  var projectConfigCache = null;
  function loadProjectConfigCompat() {
    if (projectConfigCache !== null) { return projectConfigCache || null; }
    projectConfigCache = false;
    try {
      var dir = projectDir || executionDir || '';
      if (dir) {
        var fsModule = require('fs');
        var pathModule = require('path');
        var configPath = pathModule.join(dir, 'project.json');
        if (fsModule.existsSync(configPath)) {
          projectConfigCache = JSON.parse(fsModule.readFileSync(configPath, 'utf8'));
        }
      }
    } catch (configError) {
      projectConfigCache = false;
    }
    return projectConfigCache || null;
  }

  function makeExecutionConfigCompat() {
    var dir = projectDir || executionDir || '';
    var execConfig = {
      projectConfig: loadProjectConfigCompat(),
      projectDir: dir,
      getProjectDir: function () { return dir; },
      getProjectConfig: function () { return execConfig.projectConfig; },
      setProjectConfig: function (project) {
        execConfig.projectConfig = project;
        return execConfig;
      },
      setProjectDir: function (value) {
        dir = String(value || '');
        execConfig.projectDir = dir;
        return execConfig;
      },
      getClass: function () {
        return {
          getName: function () { return 'com.stardust.autojs.execution.ExecutionConfig'; },
          getMethods: function () {
            function method(name, params) {
              return {
                getName: function () { return name; },
                getParameterTypes: function () { return params || []; }
              };
            }
            var projectClass = null;
            try { projectClass = javaApi.findClass('com.stardust.autojs.project.ProjectConfig'); }
            catch (classError) { projectClass = null; }
            return [
              method('getProjectConfig'), method('getProjectDir'),
              method('setProjectConfig', projectClass ? [projectClass] : []),
              method('setProjectDir')
            ];
          }
        };
      }
    };
    return execConfig;
  }

  function makeEngine(info) {
    if (!info || info.id === undefined || info.id === null) {
      throw hostError('主进程返回的引擎缺少 id');
    }
    var id = Number(info.id);
    var engine = {
      id: id,
      engine: info.engine || '',
      execArgv: info.execArgv || {},
      source: scriptFile || '',
      executionConfig: makeExecutionConfigCompat()
    };
    engine.getSource = function () { return scriptFile || ''; };
    engine.getClass = function () {
      return {
        getName: function () { return 'com.stardust.autojs.engine.Node24JavaScriptEngine'; }
      };
    };
    engine.getName = function () { return String(info.engine || 'node24'); };
    engine.forceStop = function () {
      try { return engineBridge('stop', { target: id }); } catch (stopError) { return false; }
    };
    engine.on = function (type, handler) {
      if (typeof handler !== 'function') { throw new TypeError('engines.on 的监听必须是函数'); }
      ensureEngineEvents();
      engineListenerTable(id, type, true).push(handler);
      flushEngineEvents(id, type);
      return engine;
    };
    engine.once = function (type, handler) {
      var off = null;
      off = engine.on(type, function () {
        engine.off(type, off);
        return handler.apply(engine, arguments);
      });
      return engine;
    };
    engine.off = function (type, handler) {
      var list = engineListenerTable(id, type, false);
      if (!list) { return engine; }
      var index = list.indexOf(handler);
      if (index >= 0) { list.splice(index, 1); }
      return engine;
    };
    engine.emit = function (type) {
      var args = Array.prototype.slice.call(arguments, 1);
      return engineBridge('emit', { target: id, event: type, args: args });
    };
    return engine;
  }

  function myEngineInfo() {
    if (!myEngineCache) {
      myEngineCache = engineBridge('my', {});
    }
    return myEngineCache;
  }

  function myEngine() {
    ensureEngineEvents();
    return makeEngine(myEngineInfo());
  }

  function getRunningEngines() {
    var list = engineBridge('list', {});
    var result = [];
    var ownId = Number(myEngineInfo().id);
    var sawOwn = false;
    for (var i = 0; i < list.length; i++) {
      var engine = makeEngine(list[i]);
      if (engine.id === ownId) { sawOwn = true; }
      result.push(engine);
    }
    // 注册表切换瞬间仍保证 myEngine 可被 all()/find 找到（身份仍是主进程真实 id）。
    if (!sawOwn) { result.push(myEngine()); }
    return result;
  }

  function makeExecution(token) {
    var listeners = Object.create(null);
    var buffered = Object.create(null);
    var latestEngineId = null;
    var latestEngineName = 'Rhino';
    var latestEngine = null;
    var promise = null;
    var done = false;
    var execution = {
      id: -1,
      token: token
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
      if (event === 'start') {
        done = false;
      } else if (event === 'success' || event === 'exception') {
        done = true;
      }
      var handlers = listeners[event];
      if (!handlers || handlers.length === 0) {
        if (!replay) {
          if (!buffered[event]) { buffered[event] = []; }
          buffered[event].push({ event: event, args: args || [], engineId: engineId });
        }
        return;
      }
      var callArgs = execution._callbackArgs(event, args || []);
      for (var i = 0; i < handlers.length; i++) {
        try {
          handlers[i].apply(execution, callArgs);
        } catch (error) {
          console.error('[Auto.js] execution 监听异常（' + event + '）：', error);
        }
      }
    };

    execution._callbackArgs = function (event, args) {
      if (event === 'exception') {
        var error = new Error(args.length > 0 ? String(args[0]) : '子引擎执行异常');
        error.name = 'ScriptExecutionError';
        return [execution, error];
      }
      return [execution];
    };

    execution.on = function (event, handler) {
      if (typeof handler !== 'function') { throw new TypeError('execution.on 的监听必须是函数'); }
      ensureEngineEvents();
      if (!listeners[event]) { listeners[event] = []; }
      listeners[event].push(handler);
      if (buffered[event] && buffered[event].length > 0) { flush(event); }
      return execution;
    };
    execution.once = function (event, handler) {
      var off = null;
      off = execution.on(event, function () {
        execution.off(event, off);
        return handler.apply(execution, arguments);
      });
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
        throw hostError('execution.engine 尚未创建，无法 forceStop；请等待 start 事件或 engine()');
      }
      return engineBridge('stop', { target: latestEngineId });
    };
    execution.engine = function () {
      if (promise) { return promise; }
      promise = new Promise(function (resolve, reject) {
        var start = Date.now();
        function poll() {
          var info;
          try {
            info = engineBridge('execution', { token: token });
          } catch (error) {
            reject(error);
            return;
          }
          if (info && info.engineId !== undefined && info.engineId !== null) {
            latestEngineId = Number(info.engineId);
            if (info.engine) { latestEngineName = String(info.engine); }
            if (!latestEngine) {
              latestEngine = makeEngine({ id: latestEngineId, engine: latestEngineName });
            }
            resolve(latestEngine);
            return;
          }
          if (info && info.done) {
            reject(new Error('子执行已结束但未创建引擎'));
            return;
          }
          if (Date.now() - start > 30000) {
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
        if (latestEngineId === null && !done) {
          try {
            var info = engineBridge('execution', { token: token });
            if (info && info.engineId !== undefined && info.engineId !== null) {
              latestEngineId = Number(info.engineId);
              if (info.engine) { latestEngineName = String(info.engine); }
            }
          } catch (ignored) { /* 执行记录已回收时保持 null */ }
        }
        if (latestEngineId !== null && !latestEngine) {
          latestEngine = makeEngine({ id: latestEngineId, engine: latestEngineName });
        }
        return latestEngine;
      }
    });
    return execution;
  }

  var enginesModule = {
    myEngine: myEngine,
    getRunningEngines: getRunningEngines,
    all: getRunningEngines,
    execScriptFile: function (path, options) {
      if (typeof path !== 'string' || path.length === 0) {
        throw new TypeError('engines.execScriptFile(path)：path 必须是非空字符串');
      }
      var nodePath = require('path');
      // task-154：Auto.js 语义是「相对路径以当前脚本所在目录为基准」，不是进程 cwd。
      // Node 引擎由 native 嵌入执行，cwd 通常是 /，所以这里优先用 __ajsScriptFile 的目录。
      var baseDir = '';
      try {
        if (scriptFile) { baseDir = nodePath.dirname(scriptFile); }
      } catch (ignored) { baseDir = ''; }
      if (!baseDir) { baseDir = process.cwd(); }
      var absolute = nodePath.isAbsolute(path) ? path : nodePath.resolve(baseDir, path);
      options = options || {};
      var workingDirectory;
      if (options.workingDirectory) {
        workingDirectory = nodePath.isAbsolute(String(options.workingDirectory))
          ? String(options.workingDirectory)
          : nodePath.resolve(baseDir, String(options.workingDirectory));
      } else {
        workingDirectory = nodePath.dirname(absolute);
      }
      var token = 'node-engine-' + Date.now() + '-' + (++executionSeq);
      var execution = makeExecution(token);
      executionRegistry[token] = execution;
      ensureEngineEvents();
      try {
        var result = engineBridge('exec', {
          token: token,
          path: absolute,
          workingDirectory: workingDirectory,
          arguments: options.arguments || {}
        });
        execution.id = result && result.executionId !== undefined ? result.executionId : -1;
      } catch (error) {
        delete executionRegistry[token];
        throw error;
      }
      return execution;
    },
    stopAll: function () { return engineBridge('stopAll', {}); },
    stopAllAndToast: function () { return engineBridge('stopAll', {}); }
  };

  // >>> task-154 threads start
  // threads：基于 Node 原生 worker_threads 的真实系统线程（Auto.js threads 简化版）。
  //
  // 与 Rhino/QuickJS 的差异：Node 的 Worker 有独立堆，无法共享闭包；函数体必须自包含，
  // 只能看到自己的参数、Node 内置模块（require）和本文件注入的 sleep/console。
  // 返回值与异常经 SharedArrayBuffer 回传：join() 之后可用 thread.result / thread.error。
  // join(timeout) 是 Atomics.wait 真阻塞；interrupt() = Worker.terminate()。
  // threads.exec / threads.atomic / $threads.pool 未实现，调用会明确抛错（见实现报告）。
  var threadsModule = (function () {
    var workerThreads = null;
    try { workerThreads = require('worker_threads'); } catch (error) { workerThreads = null; }
    var SharedBuffer = typeof SharedArrayBuffer === 'function' ? SharedArrayBuffer : null;
    var RESULT_HEADER = 8;
    var RESULT_CAPACITY = 256 * 1024;
    var STATE_RUNNING = 0;
    var STATE_RESULT = 1;
    var STATE_INTERRUPTED = 2;
    var liveThreads = [];

    function serializableArgs(rawArgs) {
      var result = [];
      for (var i = 0; i < rawArgs.length; i++) {
        var value = rawArgs[i];
        if (typeof value === 'function' || typeof value === 'symbol') {
          throw new TypeError('threads.start 参数必须是可 JSON 序列化的值（第 ' + (i + 1) + ' 个参数是 '
            + typeof value + '）');
        }
        try {
          var text = JSON.stringify(value);
          result.push(text === undefined ? null : JSON.parse(text));
        } catch (error) {
          throw new TypeError('threads.start 参数必须是可 JSON 序列化的值（第 ' + (i + 1) + ' 个参数）：'
            + String(error && error.message || error));
        }
      }
      return result;
    }

    function workerSource(fnSource) {
      return [
        '(function () {',
        'const { workerData } = require("worker_threads");',
        'const SAB = workerData.sab;',
        'const state = new Int32Array(SAB, 0, 1);',
        'const lengthSlot = new Int32Array(SAB, 4, 1);',
        'const bytes = new Uint8Array(SAB, 8, ' + RESULT_CAPACITY + ');',
        'globalThis.sleep = function (ms) {',
        '  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, Math.max(0, Number(ms) || 0));',
        '};',
        'function writeResult(payload) {',
        '  try {',
        '    let text;',
        '    try { text = JSON.stringify(payload); } catch (error) { text = JSON.stringify({ ok: false, error: String(error && error.message || error) }); }',
        '    if (text === undefined) { text = "null"; }',
        '    const encoded = new TextEncoder().encode(text).slice(0, ' + RESULT_CAPACITY + ');',
        '    bytes.set(encoded);',
        '    Atomics.store(lengthSlot, 0, encoded.length);',
        '  } catch (error) {',
        '    Atomics.store(lengthSlot, 0, 0);',
        '  }',
        '  Atomics.store(state, 0, 1);',
        '  Atomics.notify(state, 0);',
        '}',
        'let run;',
        'try {',
        '  const fn = (0, eval)("(" + workerData.fnSource + ")");',
        '  run = Promise.resolve().then(function () { return fn.apply(null, workerData.args || []); });',
        '} catch (error) {',
        '  writeResult({ ok: false, error: String(error && error.stack || error) });',
        '  return;',
        '}',
        'run.then(function (value) { writeResult({ ok: true, value: value === undefined ? null : value }); })',
        '  .catch(function (error) { writeResult({ ok: false, error: String(error && error.stack || error) }); });',
        '})();'
      ].join('\n');
    }

    function decodeResult(sab) {
      try {
        var length = Atomics.load(new Int32Array(sab, 4, 1), 0);
        if (!(length > 0)) { return { ok: false, error: '线程没有回传结果' }; }
        var bytes = new Uint8Array(sab, RESULT_HEADER, length);
        return JSON.parse(new TextDecoder().decode(bytes));
      } catch (error) {
        return { ok: false, error: '线程结果无法解析：' + String(error && error.message || error) };
      }
    }

    function makeThread(worker, sab) {
      var state = new Int32Array(sab, 0, 1);
      var info = { worker: worker, done: false, interrupted: false, decoded: false, result: undefined, error: null };
      function finished() { return Atomics.load(state, 0) !== STATE_RUNNING; }
      function finalState() { return Atomics.load(state, 0); }
      var thread = {
        id: worker.threadId,
        name: 'aijs-thread-' + worker.threadId,
        isAlive: function () { return !finished(); },
        interrupt: function () {
          info.interrupted = true;
          if (!finished()) {
            Atomics.store(state, 0, STATE_INTERRUPTED);
            Atomics.notify(state, 0);
          }
          try { worker.terminate(); } catch (ignored) { }
          return thread;
        },
        join: function (timeout) {
          var hasTimeout = timeout !== undefined && timeout !== null;
          var deadline = hasTimeout ? Date.now() + Math.max(0, Number(timeout) || 0) : 0;
          while (!finished()) {
            var waitMs = hasTimeout ? Math.max(0, deadline - Date.now()) : undefined;
            var status = Atomics.wait(state, 0, 0, waitMs);
            if (status === 'not-equal') { break; }
            if (hasTimeout && Date.now() >= deadline) { break; }
          }
          return finished();
        },
        get result() {
          if (!finished()) { return undefined; }
          if (!info.decoded) {
            info.decoded = true;
            var final = finalState();
            if (final === STATE_INTERRUPTED) {
              info.result = undefined;
              info.error = '线程被中断/终止';
            } else {
              var parsed = decodeResult(sab);
              info.result = parsed && parsed.ok ? parsed.value : undefined;
              info.error = parsed && !parsed.ok ? String(parsed.error) : null;
            }
          }
          return info.result;
        },
        get error() {
          if (!finished()) { return undefined; }
          if (!info.decoded) { void thread.result; }
          return info.error;
        },
        on: function (event, handler) {
          if (event !== 'exit' || typeof handler !== 'function') {
            throw new TypeError('thread.on 目前只支持 "exit" 事件与函数监听');
          }
          if (finished()) { setTimeout(handler, 0); } else { worker.once('exit', handler); }
          return thread;
        },
        off: function () { return thread; }
      };
      worker.on('exit', function () {
        info.done = true;
        var index = liveThreads.indexOf(thread);
        if (index >= 0) { liveThreads.splice(index, 1); }
      });
      worker.on('error', function (error) {
        info.done = true;
        info.error = String(error && error.stack || error);
        if (!finished()) {
          Atomics.store(state, 0, STATE_INTERRUPTED);
          Atomics.notify(state, 0);
        }
      });
      return thread;
    }

    function start(fn) {
      if (typeof fn !== 'function') { throw new TypeError('threads.start(fn)：fn 必须是函数'); }
      if (!workerThreads) { throw hostError('当前 Node 运行时缺少 worker_threads，无法创建真实线程'); }
      if (!SharedBuffer) { throw hostError('当前 Node 运行时缺少 SharedArrayBuffer，无法接收线程返回值'); }
      var args = serializableArgs(Array.prototype.slice.call(arguments, 1));
      var sab = new SharedBuffer(RESULT_HEADER + RESULT_CAPACITY);
      var worker = null;
      try {
        worker = new workerThreads.Worker(workerSource(fn.toString()), {
          eval: true,
          workerData: { sab: sab, fnSource: fn.toString(), args: args },
          execArgv: []
        });
      } catch (error) {
        throw hostError('创建 Node 线程失败：' + String(error && error.message || error));
      }
      var thread = makeThread(worker, sab);
      liveThreads.push(thread);
      return thread;
    }

    var mainThread = {
      id: 0,
      name: 'main',
      isAlive: function () { return true; },
      interrupt: function () {
        try { $autojs.stopRunning(); } catch (ignored) { }
        return mainThread;
      },
      join: function () { return true; },
      result: undefined,
      error: undefined,
      on: function () { return mainThread; },
      off: function () { return mainThread; }
    };

    return {
      start: start,
      currentThread: function () { return mainThread; },
      shutDownAll: function () {
        var count = liveThreads.length;
        for (var i = liveThreads.length - 1; i >= 0; i--) {
          try { liveThreads[i].interrupt(); } catch (ignored) { }
        }
        return count;
      },
      exec: function () {
        throw new Error('Node 引擎的 threads.exec 暂未实现；请用 threads.start(fn)（worker_threads 简化版）');
      },
      atomic: function () {
        throw new Error('Node 引擎的 threads.atomic 暂未实现；函数体运行在独立 Worker 里，请用 SharedArrayBuffer/Atomics');
      }
    };
  })();
  // <<< task-154 threads end


  // ------------------------------------------------------------------
  // task-136 P0：Auto.js 常用模块与全局（toast/lang/device/clip_manager/
  // datastore/settings/power_manager/axios + exit/sleep/toast）
  // ------------------------------------------------------------------
  function javaBuild(field, fallback) {
    try { var v = $autojs.java.Packages.android.os.Build[field]; return v === undefined ? fallback : v; }
    catch (e) { return fallback; }
  }
  function contextService(name) {
    try { return $autojs.androidContext.getSystemService(name); } catch (e) { return null; }
  }
  var toastModule = {
    showToast: function (text) { try { appModule.toast(String(text)); } catch (e) { console.log(String(text)); } },
    toast: function (text) { toastModule.showToast(text); }
  };
  var langModule = {
    delay: function (ms) { return new Promise(function (resolve) { setTimeout(resolve, Math.max(0, Number(ms) || 0)); }); },
    sleep: function (ms) {
      try {
        var sab = new SharedArrayBuffer(4);
        Atomics.wait(new Int32Array(sab), 0, 0, Math.max(0, Number(ms) || 0));
      } catch (e) { var end = Date.now() + (Number(ms) || 0); while (Date.now() < end) { } }
    },
    exit: function (code) { try { $autojs.stopRunning(); } catch (e) { } console.log('[Auto.js] exit(' + (code || 0) + ')'); }
  };
  var deviceModule = (function () {
    var info = {};
    try { info = domain('device', 'info'); } catch (e) { info = {}; }
    function safe(getter, fallback) { try { var v = getter(); return v === undefined ? fallback : v; } catch (e) { return fallback; } }
    var audioStreams = { alarm: 4, voice_call: 0, music: 3, ring: 2, notification: 5, system: 1, dtmf: 8 };
    var device = {
      get info() { return info; },
      get battery() {
        return safe(function () {
          var bm = contextService('batterymanager');
          return bm ? bm.getIntProperty(4) : null;
        }, null);
      },
      get androidId() {
        return safe(function () {
          var Settings = $autojs.java.Packages.android.provider.Settings;
          var Secure = Settings.Secure;
          return Secure.getString($autojs.androidContext.getContentResolver(), 'android_id');
        }, '');
      },
      get batteryPluggedTypes() { return 0; },
      get bootloader() { return javaBuild('BOOTLOADER', ''); },
      get brightness() { return safe(function () { return 128; }, 128); },
      get brightnessMode() { return 1; },
      get cpuApis() { return safe(function () { return javaBuild('SUPPORTED_ABIS', []); }, []); },
      get display() { return javaBuild('DISPLAY', ''); },
      get displayMetrics() { return info; },
      get fingerprint() { return javaBuild('FINGERPRINT', ''); },
      get hardware() { return javaBuild('HARDWARE', ''); },
      get imei() { throw new Error('device.imei 需要 READ_PHONE_STATE（当前版本未实现）'); },
      get macAddress() { return '02:00:00:00:00:00'; },
      get memoryInfo() { return safe(function () {
        var am = contextService('activity');
        var mi = new ($autojs.java.findClass('android.app.ActivityManager$MemoryInfo'))();
        am.getMemoryInfo(mi);
        return { totalMem: mi.totalMem, availMem: mi.availMem };
      }, {}); },
      get product() { return javaBuild('PRODUCT', ''); },
      get screenWidth() { return info.width || 0; },
      get screenHeight() { return info.height || 0; },
      get externalStorageDirectory() { return '/sdcard'; },
      getVolume: function (stream) {
        var index = audioStreams[stream] === undefined ? 3 : audioStreams[stream];
        return safe(function () { return contextService('audio').getStreamVolume(index); }, 0);
      },
      getVolumeRange: function (stream) {
        var index = audioStreams[stream] === undefined ? 3 : audioStreams[stream];
        return safe(function () {
          var am = contextService('audio');
          var max = am.getStreamMaxVolume(index);
          return { max: max, min: 0, current: am.getStreamVolume(index) };
        }, { max: 0, min: 0, current: 0 });
      },
      vibrate: function (ms) {
        try {
          var vibrator = contextService('vibrator');
          if (vibrator) vibrator.vibrate(Math.max(1, Number(ms) || 100));
        } catch (e) { console.log('[Auto.js] vibrate 失败：' + e.message); }
      },
      cancelVibration: function () {
        try { var vibrator = contextService('vibrator'); if (vibrator) vibrator.cancel(); } catch (e) { }
      },
      get sdkInt() { return info.sdkInt || javaBuild('VERSION.SDK_INT', 0); }
    };
    var OS = {
      get sdkVersionCode() { return info.sdkInt || 0; },
      ANDROID_P: { code: 28 }, ANDROID_Q: { code: 29 }, ANDROID_R: { code: 30 },
      ANDROID_S: { code: 31 }, ANDROID_T: { code: 33 }, ANDROID_U: { code: 34 }
    };
    return { device: device, OS: OS };
  })();
  var clipManagerModule = {
    setClip: function (text) { appModule.setClipboard(String(text)); },
    getClip: function () { try { return appModule.getClipboard(); } catch (e) { return ''; } },
    clearClip: function () { appModule.setClipboard(''); },
    hasClip: function () { try { return appModule.getClipboard() !== ''; } catch (e) { return false; } },
    clipboardManager: {
      on: function () { return function () { }; },
      off: function () { }
    }
  };
  // datastore：Pro 的异步 JSON key-value 存储（AES-GCM 加密可选）。
  // 宿主 $autojs.storages 只有同步 get/put/remove/clear 且值只能是字符串；
  // 这里补异步/Promise 契约、JSON 序列化、链式 editor 与随机 IV 的认证加密。
  var datastoreModule = (function () {
    // Keep the namespace used by dev-1095 so existing put() data stays readable.
    var PLAIN_STORE_PREFIX = '';
    var PLAIN_VALUE_PREFIX = 'AJSDSJSON1.';
    var ENCRYPTED_STORE_PREFIX = 'datastore.encrypted.';
    var KEY_CHECK_KEY = '__ajs_datastore_key_check_v1__';
    var ENVELOPE_PREFIX = 'AJSDS1.';
    var KEY_CHECK_TEXT = 'ajs-datastore-key-check-v1:';
    var ENCRYPTED_KEY_LENGTHS = [16, 32, 64];
    var cryptoModule = null;

    function datastoreError(message, cause) {
      var error = new Error('[Auto.js] ' + message);
      error.name = 'AutoJsDatastoreError';
      if (cause !== undefined) { error.cause = cause; }
      return error;
    }

    function nodeCrypto() {
      if (!cryptoModule) {
        try {
          cryptoModule = require('crypto');
        } catch (error) {
          throw datastoreError('datastore 加密需要 Node.js crypto 模块', error);
        }
        if (!cryptoModule || typeof cryptoModule.createCipheriv !== 'function'
          || typeof cryptoModule.createHmac !== 'function'
          || typeof cryptoModule.randomBytes !== 'function') {
          throw datastoreError('datastore 加密需要完整的 Node.js crypto 模块');
        }
      }
      return cryptoModule;
    }

    var jsonSerializer = {
      valueToString: function (data) {
        if (typeof data === 'undefined') { return ''; }
        return JSON.stringify(data);
      },
      valueFromString: function (data) {
        if (data === null || data.length === 0) { return undefined; }
        return JSON.parse(data);
      }
    };

    function storageGet(name, key) {
      var value = $autojs.storages.get(String(key), name);
      return value === undefined ? null : value;
    }
    function storagePut(name, key, value) { $autojs.storages.put(String(key), value, name); }
    function storageRemove(name, key) { $autojs.storages.remove(String(key), name); }
    function storageClear(name) { $autojs.storages.clear(name); }

    function normalizeName(name) {
      if (name === undefined || name === null || name === '') { return 'default'; }
      return String(name);
    }

    function normalizeEncryptionKey(rawKey, storeName) {
      if (rawKey === undefined || rawKey === null) { return null; }
      var bytes;
      if (typeof rawKey === 'string') {
        bytes = Buffer.from(rawKey, 'utf8');
      } else if (Buffer.isBuffer(rawKey)) {
        bytes = Buffer.from(rawKey);
      } else if (rawKey instanceof Uint8Array) {
        bytes = Buffer.from(rawKey);
      } else {
        throw datastoreError('datastore encryptionKey 必须是字符串或 Buffer');
      }
      if (ENCRYPTED_KEY_LENGTHS.indexOf(bytes.length) < 0) {
        throw datastoreError('datastore encryptionKey 长度必须为 16、32 或 64 字节（当前 '
          + bytes.length + ' 字节）');
      }
      // 统一派生 32 字节 AES-256 密钥：16/32/64 字节输入都可用，且与存储名绑定，
      // 避免把 A 存储的密文复制到 B 存储后被解密。
      var crypto = nodeCrypto();
      var cipherKey = crypto.createHmac('sha256', bytes)
        .update('ajs-datastore-key-v1\0' + storeName, 'utf8').digest();
      var indexKey = crypto.createHmac('sha256', cipherKey)
        .update('ajs-datastore-index-v1', 'utf8').digest();
      return { cipherKey: cipherKey, indexKey: indexKey };
    }

    function createDatastore(name, options) {
      var datastoreName = normalizeName(name);
      var opts = options || {};
      if (typeof opts !== 'object') {
        throw datastoreError('datastore options 必须是对象');
      }
      var serializer = opts.serializer || jsonSerializer;
      if (!serializer || typeof serializer.valueToString !== 'function'
        || typeof serializer.valueFromString !== 'function') {
        throw datastoreError('datastore serializer 必须实现 valueToString/valueFromString');
      }
      var encryption = normalizeEncryptionKey(opts.encryptionKey, datastoreName);
      var encrypted = encryption !== null;
      var prefsName = (encrypted ? ENCRYPTED_STORE_PREFIX : PLAIN_STORE_PREFIX) + datastoreName;
      var valueAad = Buffer.from('ajs-datastore-v1\0' + datastoreName + '\0value', 'utf8');
      var keyCheckAad = Buffer.from('ajs-datastore-v1\0' + datastoreName + '\0key-check', 'utf8');
      var keyCheckText = KEY_CHECK_TEXT + datastoreName;

      function serializeValue(value) {
        var text = serializer.valueToString(value);
        if (text === undefined || text === null) { return ''; }
        return typeof text === 'string' ? text : String(text);
      }

      function deserializeValue(text) {
        if (text === null || text === undefined || text.length === 0) { return undefined; }
        return serializer.valueFromString(text);
      }

      function deserializeStoredValue(text) {
        if (!encrypted) {
          if (text.slice(0, PLAIN_VALUE_PREFIX.length) !== PLAIN_VALUE_PREFIX) { return text; }
          text = text.slice(PLAIN_VALUE_PREFIX.length);
        }
        return deserializeValue(text);
      }

      function storageKey(key) {
        key = String(key);
        if (!encrypted) { return key; }
        return 'e:' + nodeCrypto().createHmac('sha256', encryption.indexKey)
          .update(key, 'utf8').digest('hex');
      }

      function encryptText(text, aad) {
        var crypto = nodeCrypto();
        var iv = crypto.randomBytes(12);
        var cipher = crypto.createCipheriv('aes-256-gcm', encryption.cipherKey, iv,
          { authTagLength: 16 });
        cipher.setAAD(aad);
        var ciphertext = Buffer.concat([
          cipher.update(Buffer.from(text, 'utf8')),
          cipher.final()
        ]);
        var tag = cipher.getAuthTag();
        return ENVELOPE_PREFIX + Buffer.concat([iv, tag, ciphertext]).toString('base64');
      }

      function decryptText(raw, aad) {
        if (typeof raw !== 'string' || raw.slice(0, ENVELOPE_PREFIX.length) !== ENVELOPE_PREFIX) {
          throw datastoreError('本地存储 "' + datastoreName + '" 的加密数据格式非法或已损坏');
        }
        var bytes = Buffer.from(raw.slice(ENVELOPE_PREFIX.length), 'base64');
        if (bytes.length < 28) {
          throw datastoreError('本地存储 "' + datastoreName + '" 的加密数据长度非法或已损坏');
        }
        var iv = bytes.subarray(0, 12);
        var tag = bytes.subarray(12, 28);
        var body = bytes.subarray(28);
        try {
          var decipher = nodeCrypto().createDecipheriv('aes-256-gcm',
            encryption.cipherKey, iv, { authTagLength: 16 });
          decipher.setAAD(aad);
          decipher.setAuthTag(tag);
          return Buffer.concat([decipher.update(body), decipher.final()]).toString('utf8');
        } catch (error) {
          throw datastoreError('加密本地存储 "' + datastoreName
            + '" 解密失败：encryptionKey 不正确或数据已损坏', error);
        }
      }

      // 加密存储首次写入时写入 key-check 记录；之后任何实例（含错误密钥的实例）
      // 都会在读写前用 GCM 认证校验密钥，避免错误密钥静默读到 undefined 或误清空数据。
      function ensureKeyCheck(writeIfMissing) {
        if (!encrypted) { return; }
        var raw = storageGet(prefsName, KEY_CHECK_KEY);
        if (raw !== null && raw !== '') {
          var text = decryptText(raw, keyCheckAad);
          if (text !== keyCheckText) {
            throw datastoreError('加密本地存储 "' + datastoreName + '" 的密钥校验记录不匹配');
          }
          return;
        }
        if (raw === '') {
          throw datastoreError('加密本地存储 "' + datastoreName + '" 的密钥校验记录已损坏');
        }
        if (writeIfMissing) {
          storagePut(prefsName, KEY_CHECK_KEY, encryptText(keyCheckText, keyCheckAad));
        }
      }

      function getSyncImpl(key) {
        ensureKeyCheck(false);
        var raw = storageGet(prefsName, storageKey(key));
        if (raw === null) { return undefined; }
        if (raw === '') {
          if (encrypted) {
            throw datastoreError('加密本地存储 "' + datastoreName + '" 的数据已损坏');
          }
          return '';
        }
        if (encrypted) { raw = decryptText(raw, valueAad); }
        return deserializeStoredValue(raw);
      }

      function setSyncImpl(key, value) {
        var text = serializeValue(value);
        ensureKeyCheck(true);
        storagePut(prefsName, storageKey(key), encrypted ? encryptText(text, valueAad) : PLAIN_VALUE_PREFIX + text);
      }

      function containsSyncImpl(key) {
        ensureKeyCheck(false);
        var raw = storageGet(prefsName, storageKey(key));
        if (raw === null) { return false; }
        if (encrypted) {
          if (raw === '') {
            throw datastoreError('加密本地存储 "' + datastoreName + '" 的数据已损坏');
          }
          decryptText(raw, valueAad);
        }
        return true;
      }

      function removeSyncImpl(key) {
        ensureKeyCheck(false);
        var storeKey = storageKey(key);
        var raw = storageGet(prefsName, storeKey);
        if (raw === null) {
          storageRemove(prefsName, storeKey);
          return undefined;
        }
        var text = raw;
        if (encrypted) {
          if (raw === '') {
            throw datastoreError('加密本地存储 "' + datastoreName + '" 的数据已损坏');
          }
          text = decryptText(raw, valueAad);
        }
        // 与 Pro 一致：先删除再反序列化，反序列化失败也不会留下半旧值。
        storageRemove(prefsName, storeKey);
        return deserializeStoredValue(text);
      }

      function clearSyncImpl() {
        ensureKeyCheck(false);
        storageClear(prefsName);
      }

      function DatastoreEditor() { this.ops = []; }
      DatastoreEditor.prototype.set = function (key, value) {
        this.ops.push({ type: 'set', key: storageKey(key), text: serializeValue(value) });
        return this;
      };
      DatastoreEditor.prototype.remove = function (key) {
        this.ops.push({ type: 'remove', key: storageKey(key) });
        return this;
      };
      DatastoreEditor.prototype.clear = function () {
        this.ops.push({ type: 'clear' });
        return this;
      };

      function commitOps(ops) {
        ensureKeyCheck(false);
        for (var i = 0; i < ops.length; i++) {
          var op = ops[i];
          if (op.type === 'set') {
            // clear 之后再 set 时 key-check 已被清掉，这里按需重建。
            ensureKeyCheck(true);
            storagePut(prefsName, op.key, encrypted ? encryptText(op.text, valueAad) : PLAIN_VALUE_PREFIX + op.text);
          } else if (op.type === 'remove') {
            storageRemove(prefsName, op.key);
          } else if (op.type === 'clear') {
            storageClear(prefsName);
          }
        }
      }

      function editSyncImpl(block) {
        if (typeof block !== 'function') {
          throw datastoreError('datastore.edit 需要 editor 回调函数');
        }
        var editor = new DatastoreEditor();
        block(editor);
        commitOps(editor.ops);
      }

      // Promise 包装：错误统一变为 rejection（与 async 函数一致），不向调用方同步抛。
      function asyncCall(call) { return Promise.resolve().then(call); }

      var api = {
        get: function (key) { return asyncCall(function () { return getSyncImpl(key); }); },
        getSync: getSyncImpl,
        set: function (key, value) { return asyncCall(function () { setSyncImpl(key, value); }); },
        setSync: setSyncImpl,
        contains: function (key) { return asyncCall(function () { return containsSyncImpl(key); }); },
        containsSync: containsSyncImpl,
        remove: function (key) { return asyncCall(function () { return removeSyncImpl(key); }); },
        removeSync: removeSyncImpl,
        clear: function () { return asyncCall(function () { clearSyncImpl(); }); },
        clearSync: clearSyncImpl,
        edit: function (block) { return asyncCall(function () { editSyncImpl(block); }); },
        editSync: editSyncImpl
      };
      // 兼容旧 prelude 的 put：保持同步写入并返回 true（旧实现返回宿主 put 的 true）；
      // 新代码请优先用异步 set 或 setSync。
      api.put = function (key, value) {
        if (encrypted) { setSyncImpl(key, value); }
        else { storagePut(prefsName, String(key), String(value)); }
        return true;
      };
      api.putSync = setSyncImpl;
      return api;
    }

    return { createDatastore: createDatastore };
  })();
  var settingsModule = (function () {
    var known = {};
    return new Proxy({}, {
      get: function (target, prop) {
        if (typeof prop === 'symbol') { return undefined; }
        if (!known[prop]) {
          known[prop] = {
            get value() { try { return $autojs.storages.get('settings.' + prop, 'settings') === 'true'; } catch (e) { return false; } },
            set value(v) { try { $autojs.storages.put('settings.' + prop, String(!!v), 'settings'); } catch (e) { } }
          };
        }
        return known[prop];
      }
    });
  })();
  var powerManagerModule = {
    isIgnoringBatteryOptimizations: function () { try { return true; } catch (e) { return false; } },
    requestIgnoreBatteryOptimizations: function () { console.log('[Auto.js] requestIgnoreBatteryOptimizations 暂未实现'); },
    isScreenOn: function () {
      try { return domain('device', 'isScreenOn') === true; } catch (e) { }
      try {
        var pm = contextService('power');
        return pm ? pm.isInteractive() : true;
      } catch (e) { return true; }
    },
    // task-179：宿主 PowerManager.wakeUp（失败时回退短时 ACQUIRE_CAUSES_WAKEUP wake lock）；
    // 权限/系统受限时由宿主抛中文错误，不静默吞掉。
    wakeUp: function () {
      var result = domain('device', 'wakeUp');
      return !!(result && result.wakeUp === true);
    }
  };
  var axiosModule = (function () {
    var MAX_REDIRECTS = 5;
    function request(method, url, data, config) {
      return requestWithRedirects(method, url, data, config, MAX_REDIRECTS);
    }
    function requestWithRedirects(method, url, data, config, redirectsLeft) {
      return new Promise(function (resolve, reject) {
        var parsed;
        try { parsed = new URL(String(url)); } catch (e) { reject(new Error('axios URL 非法：' + url)); return; }
        var transport = parsed.protocol === 'https:' ? require('https') : require('http');
        var body = null;
        var headers = Object.assign({}, (config && config.headers) || {});
        if (data !== undefined && data !== null && method !== 'GET') {
          if (typeof data === 'string') { body = data; }
          else { body = JSON.stringify(data); headers['Content-Type'] = headers['Content-Type'] || 'application/json'; }
          headers['Content-Length'] = Buffer.byteLength(body);
        }
        var options = {
          method: method,
          hostname: parsed.hostname,
          port: parsed.port || (parsed.protocol === 'https:' ? 443 : 80),
          path: parsed.pathname + parsed.search,
          headers: headers,
          rejectUnauthorized: false
        };
        var req = transport.request(options, function (res) {
          // task-179：跟随 3xx 重定向（等同真实 axios 的 follow-redirects）；
          // 网易云音乐链接会 302 到实际 mp3，不跟随会得到 0 字节文件。
          var status = res.statusCode;
          if (status >= 300 && status < 400 && res.headers.location && redirectsLeft > 0) {
            res.resume();
            var next;
            try { next = new URL(res.headers.location, parsed).toString(); }
            catch (redirectError) { reject(new Error('axios 重定向地址非法：' + res.headers.location)); return; }
            var nextMethod = (status === 301 || status === 302 || status === 303) ? 'GET' : method;
            var nextData = (nextMethod === 'GET') ? undefined : data;
            requestWithRedirects(nextMethod, next, nextData, config, redirectsLeft - 1).then(resolve, reject);
            return;
          }
          var responseType = config && config.responseType;
          if (responseType === 'stream') {
            resolve({ status: res.statusCode, headers: res.headers, data: res });
            return;
          }
          var chunks = [];
          res.on('data', function (chunk) { chunks.push(chunk); });
          res.on('end', function () {
            var buffer = Buffer.concat(chunks);
            var text = buffer.toString('utf8');
            var dataOut = text;
            if ((res.headers['content-type'] || '').indexOf('application/json') >= 0) {
              try { dataOut = JSON.parse(text); } catch (e) { dataOut = text; }
            }
            resolve({ status: res.statusCode, headers: res.headers, data: dataOut });
          });
        });
        req.on('error', reject);
        if (body !== null) req.write(body);
        req.end();
      });
    }
    var axios = {
      get: function (url, config) { return request('GET', url, undefined, config); },
      post: function (url, data, config) { return request('POST', url, data, config); },
      request: function (config) { return request((config && config.method) || 'GET', config && config.url, config && config.data, config); }
    };
    // task-179：ESM/TS 默认导入 interop——Auto.js Pro 内置 axios 支持 require('axios').default。
    axios.default = axios;
    axios.__esModule = true;
    return axios;
  })();
  // ------------------------------------------------------------------
  // 基础自动化 API（accessibility / image / color / media_projection）
  //
  // 宿主协议见 docs/guides/Node24基础自动化API.md 的“宿主协议摘要”：
  //   __ajsHostValue({kind:"automation"|"image"|"projection", action, ...})
  // 这里只做 JS 侧参数归一化、句柄生命周期与 Promise 语义，绝不吞掉宿主错误，
  // 也不伪造 enabled/动作成功。
  // ------------------------------------------------------------------
  var DEFAULT_IMAGE_THRESHOLD = 0;
  var DEFAULT_IMAGE_QUALITY = 100;
  var DEFAULT_PROJECTION_CAPTURE_TIMEOUT = 5000;
  var DEFAULT_ACCESSIBILITY_TIMEOUT = 60000;
  // Android GestureDescription 的单次手势总时长上限。
  var MAX_GESTURE_DURATION = 60000;

  function apiError(message) {
    var error = new Error('[Auto.js] ' + message);
    error.name = 'AutoJsApiError';
    return error;
  }
  function apiTypeError(message) { return new TypeError('[Auto.js] ' + message); }
  function apiRangeError(message) { return new RangeError('[Auto.js] ' + message); }

  function isIntegerValue(value) {
    return typeof value === 'number' && Number.isFinite(value) && Math.floor(value) === value;
  }

  function requireInt(value, name, min, max) {
    if (!isIntegerValue(value)) {
      throw apiTypeError(name + ' 必须是整数（收到 ' + String(value) + '）');
    }
    if (min != null && value < min) {
      throw apiRangeError(name + ' 不能小于 ' + min + '（收到 ' + value + '）');
    }
    if (max != null && value > max) {
      throw apiRangeError(name + ' 不能大于 ' + max + '（收到 ' + value + '）');
    }
    return value;
  }

  var nodePathCache = null;
  function nodePathModule() {
    if (!nodePathCache) { nodePathCache = require('path'); }
    return nodePathCache;
  }

  /** 相对图片路径按脚本所在目录解析（不是项目根；与 Pro 的入口脚本语义一致）。 */
  function resolveImagePath(value, label) {
    if (typeof value !== 'string' || value.trim() === '') {
      throw apiTypeError(label + ' 必须是非空字符串');
    }
    var path = nodePathModule();
    if (path.isAbsolute(value)) { return path.normalize(value); }
    var base = scriptFile ? path.dirname(scriptFile) : projectDir;
    if (!base) {
      throw apiError('无法解析图片路径（' + value + '）：当前脚本没有 __ajsScriptFile/__ajsProjectDir');
    }
    return path.resolve(base, value);
  }

  // ------------------------------------------------------------------
  // color
  // ------------------------------------------------------------------
  function normalizeColorNumber(value, label) {
    if (!isIntegerValue(value)) {
      throw apiTypeError(label + ' 必须是整数（收到 ' + String(value) + '）');
    }
    // 接受 signed int32 或 uint32 写法（0xFFFFFFFF -> -1），拒绝静默截断更大的数。
    if (value < -2147483648 || value > 4294967295) {
      throw apiRangeError(label + ' 超出 32 位 ARGB 范围：' + value);
    }
    return value | 0;
  }

  function parseColorInt(value) {
    if (value instanceof Color) { return value.toInt(); }
    if (typeof value === 'number') {
      return normalizeColorNumber(value, '颜色数值');
    }
    if (typeof value === 'string') {
      var text = value.trim();
      if (text === '') { throw apiTypeError('颜色字符串不能为空'); }
      if (/^[+-]?\d+$/.test(text)) {
        var decimal = Number(text);
        if (!Number.isFinite(decimal)) { throw apiRangeError('颜色整数超出范围：' + text); }
        return normalizeColorNumber(decimal, '颜色整数');
      }
      var hex = text;
      if (hex.indexOf('0x') === 0 || hex.indexOf('0X') === 0) {
        hex = hex.slice(2);
      } else if (hex.charAt(0) === '#') {
        hex = hex.slice(1);
      } else {
        throw apiTypeError('无法解析颜色：' + text + '（支持 #RRGGBB / #AARRGGBB / 十进制整数）');
      }
      if (!/^[0-9a-fA-F]+$/.test(hex)) { throw apiTypeError('无法解析颜色：' + text); }
      if (hex.length === 3) {
        hex = 'ff' + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1)
          + hex.charAt(2) + hex.charAt(2);
      } else if (hex.length === 4) {
        hex = hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1)
          + hex.charAt(2) + hex.charAt(2) + hex.charAt(3) + hex.charAt(3);
      } else if (hex.length === 6) {
        hex = 'ff' + hex;
      } else if (hex.length !== 8) {
        throw apiTypeError('无法解析颜色：' + text + '（支持 3/4/6/8 位十六进制）');
      }
      return parseInt(hex, 16) | 0;
    }
    if (value && typeof value === 'object') {
      if (typeof value.toInt === 'function') {
        var converted = value.toInt();
        if (typeof converted !== 'number' || !Number.isFinite(converted)) {
          throw apiTypeError('颜色对象的 toInt() 没有返回有限数字');
        }
        return normalizeColorNumber(converted, '颜色对象的 toInt()');
      }
      if (typeof value.value === 'number') {
        if (!Number.isFinite(value.value)) { throw apiTypeError('颜色对象的 value 不是有限数字'); }
        return normalizeColorNumber(value.value, '颜色对象的 value');
      }
    }
    throw apiTypeError('无法解析颜色对象：' + Object.prototype.toString.call(value));
  }

  function colorToInt(value) { return parseColorInt(value); }

  function Color(value) {
    if (!(this instanceof Color)) { return new Color(value); }
    this._value = parseColorInt(value);
  }

  Object.defineProperties(Color.prototype, {
    value: { enumerable: true, get: function () { return this._value; } },
    alpha: { enumerable: true, get: function () { return (this._value >>> 24) & 0xff; } },
    red: { enumerable: true, get: function () { return (this._value >>> 16) & 0xff; } },
    green: { enumerable: true, get: function () { return (this._value >>> 8) & 0xff; } },
    blue: { enumerable: true, get: function () { return this._value & 0xff; } }
  });

  Color.prototype.toInt = function () { return this._value; };
  Color.prototype.valueOf = function () { return this._value; };
  Color.prototype.toString = function () {
    return 'Color(0x' + ('00000000' + (this._value >>> 0).toString(16)).slice(-8) + ')';
  };
  Color.prototype.equals = function (other) {
    try { return parseColorInt(other) === this._value; } catch (ignored) { return false; }
  };
  Color.prototype.withAlpha = function (alpha) {
    return new Color(((requireInt(alpha, 'alpha', 0, 255) << 24) | (this._value & 0x00ffffff)) | 0);
  };

  Color.fromARGB = Color.argb = function (alpha, red, green, blue) {
    alpha = requireInt(alpha, 'alpha', 0, 255);
    red = requireInt(red, 'red', 0, 255);
    green = requireInt(green, 'green', 0, 255);
    blue = requireInt(blue, 'blue', 0, 255);
    return new Color(((alpha << 24) | (red << 16) | (green << 8) | blue) | 0);
  };
  Color.fromRGB = Color.rgb = function (red, green, blue) {
    return Color.fromARGB(255, red, green, blue);
  };
  Color.fromGray = function (gray) {
    gray = requireInt(gray, 'gray', 0, 255);
    return Color.fromRGB(gray, gray, gray);
  };
  Color.parse = function (value) {
    if (value instanceof Color) { return value; }
    return new Color(value);
  };
  Color.toColorInt = colorToInt;

  var colorModule = {
    Color: Color,
    toColorInt: colorToInt,
    parseColor: Color.parse,
    argb: Color.fromARGB,
    rgb: Color.fromRGB,
    alpha: function (value) { return Color.parse(value).alpha; },
    red: function (value) { return Color.parse(value).red; },
    green: function (value) { return Color.parse(value).green; },
    blue: function (value) { return Color.parse(value).blue; },
    isSimilar: function (first, second, threshold) {
      threshold = threshold == null ? DEFAULT_IMAGE_THRESHOLD : requireInt(threshold, 'threshold', 0, 255);
      var a = Color.parse(first);
      var b = Color.parse(second);
      return Math.abs(a.red - b.red) <= threshold
        && Math.abs(a.green - b.green) <= threshold
        && Math.abs(a.blue - b.blue) <= threshold;
    }
  };

  // ------------------------------------------------------------------
  // image
  // ------------------------------------------------------------------
  var imageHandles = Object.create(null);
  var recycledImageIds = Object.create(null);
  // findImage 的默认值与 Pro 9.3 的 images.findImage 对齐（与找色的 0..255 阈值不是同一量纲）。
  var DEFAULT_IMAGE_SIMILARITY = 0.9;
  var DEFAULT_IMAGE_WEAK_SIMILARITY = 0.6;
  var MAX_IMAGE_MATCH_LEVEL = 8;

  function imageDescriptor(value) {
    if (!value || typeof value !== 'object') {
      throw hostError('宿主返回了非法图片句柄：' + String(value));
    }
    if (!isIntegerValue(value.id) || value.id < 0) {
      throw hostError('宿主返回的图片句柄 id 非法：' + String(value.id));
    }
    if (!isIntegerValue(value.width) || value.width <= 0
      || !isIntegerValue(value.height) || value.height <= 0) {
      throw hostError('宿主返回的图片尺寸非法：' + value.width + 'x' + value.height);
    }
    return { id: value.id, width: value.width, height: value.height };
  }

  function Image(descriptor) {
    if (!(this instanceof Image)) { return new Image(descriptor); }
    var info = imageDescriptor(descriptor);
    if (recycledImageIds[info.id]) {
      throw hostError('图片句柄已回收：id=' + info.id);
    }
    if (imageHandles[info.id]) { return imageHandles[info.id]; }
    this._id = info.id;
    this._width = info.width;
    this._height = info.height;
    this._recycled = false;
    imageHandles[info.id] = this;
  }

  Object.defineProperties(Image.prototype, {
    id: { enumerable: true, get: function () { return this._id; } },
    width: {
      enumerable: true,
      get: function () { this.assertOpen(); return this._width; }
    },
    height: {
      enumerable: true,
      get: function () { this.assertOpen(); return this._height; }
    }
  });

  Image.prototype.assertOpen = function () {
    if (this._recycled) {
      throw hostError('图片句柄已回收：id=' + this._id);
    }
    return this;
  };
  Image.prototype.getWidth = function () { return this.width; };
  Image.prototype.getHeight = function () { return this.height; };
  Image.prototype.isRecycled = function () { return !!this._recycled; };
  Image.prototype.pixel = function (x, y) { return new Color(imagePixelValue(this, x, y)); };
  // 与模块级 findImage 共用同一套校验/宿主协议；Image 方法保持同步，await 同样可用。
  Image.prototype.findImage = function (template, options) {
    return imageFindImageSync(this, template, options);
  };
  Image.prototype.findImageSync = Image.prototype.findImage;
  // 多结果模板匹配（与 Rhino/QuickJS images.matchTemplate 同语义）。
  Image.prototype.matchTemplate = function (template, options) {
    return imageMatchTemplateSync(this, template, options);
  };
  Image.prototype.matchTemplateSync = Image.prototype.matchTemplate;
  // 批量像素：一次调用取回整块区域（argb 小端 4B/像素或 gray 1B/像素）。
  Image.prototype.getPixels = function (options) {
    return imageGetPixelsSync(this, options);
  };
  Image.prototype.getPixelsSync = Image.prototype.getPixels;
  // task-172：计算图片特征（Pro image 模块 Image.detectAndComputeFeatures）。
  // 返回 Promise；宿主保存特征对象，Node 只拿句柄（用完 recycle()）。
  Image.prototype.detectAndComputeFeatures = function (options) {
    var self = this;
    return Promise.resolve().then(function () { return imageDetectFeaturesSync(self, options); });
  };
  Image.prototype.detectAndComputeFeaturesSync = function (options) {
    return imageDetectFeaturesSync(this, options);
  };
  Image.prototype.recycle = function () {
    if (this._recycled) { return true; }
    var value = imageHostCall('recycle', { id: this._id });
    if (value !== true) {
      throw hostError('回收图片失败：宿主返回 ' + String(value) + '（id=' + this._id + '）');
    }
    this._recycled = true;
    recycledImageIds[this._id] = true;
    if (imageHandles[this._id] === this) { delete imageHandles[this._id]; }
    return true;
  };
  Image.prototype.close = function () { return this.recycle(); };
  Image.prototype.toJSON = function () {
    this.assertOpen();
    return { id: this._id, width: this._width, height: this._height };
  };

  function wrapImage(value) {
    if (value instanceof Image) { return value.assertOpen(); }
    return new Image(value);
  }

  function imageHostCall(action, fields) {
    var request = { kind: 'image', action: action };
    if (fields) {
      for (var key in fields) {
        if (Object.prototype.hasOwnProperty.call(fields, key)) { request[key] = fields[key]; }
      }
    }
    return hostValue(request);
  }

  function normalizeImageOptions(options) {
    if (options == null) { return {}; }
    if (typeof options === 'number') { return { threshold: options }; }
    if (typeof options !== 'object' || Array.isArray(options)) {
      throw apiTypeError('图片选项必须是对象（可含 threshold/region）');
    }
    return Object.assign({}, options);
  }

  function imageThreshold(options, fallback) {
    var threshold = options && options.threshold != null ? options.threshold : fallback;
    if (threshold == null) { threshold = DEFAULT_IMAGE_THRESHOLD; }
    return requireInt(threshold, 'threshold', 0, 255);
  }

  function normalizeImageRegion(region, frame) {
    if (region == null) { return null; }
    var x;
    var y;
    var width;
    var height;
    if (Array.isArray(region)) {
      if (region.length !== 2 && region.length !== 4) {
        throw apiTypeError('region 数组必须是 [x,y] 或 [x,y,width,height]');
      }
      x = region[0];
      y = region[1];
      width = region.length === 4 ? region[2] : frame._width - x;
      height = region.length === 4 ? region[3] : frame._height - y;
    } else if (region && typeof region === 'object') {
      x = region.x;
      y = region.y;
      width = region.width;
      height = region.height;
    } else {
      throw apiTypeError('region 必须是 [x,y]、[x,y,width,height] 或 {x,y,width,height}');
    }
    x = requireInt(x, 'region.x', 0);
    y = requireInt(y, 'region.y', 0);
    width = requireInt(width, 'region.width', 1);
    height = requireInt(height, 'region.height', 1);
    if (x + width > frame._width || y + height > frame._height) {
      throw apiRangeError('region 超出图片范围：图片 ' + frame._width + 'x' + frame._height
        + '，region [' + x + ',' + y + ',' + width + ',' + height + ']');
    }
    return [x, y, width, height];
  }

  function normalizeImagePoint(value, label) {
    if (value == null) { return null; }
    var x;
    var y;
    if (Array.isArray(value)) {
      x = value[0];
      y = value[1];
    } else if (typeof value === 'object') {
      x = value.x;
      y = value.y;
    } else {
      throw hostError(label + ' 返回了非法坐标：' + String(value));
    }
    return {
      x: requireInt(x, label + '.x', 0),
      y: requireInt(y, label + '.y', 0)
    };
  }

  function imagePixelValue(image, x, y) {
    var frame = wrapImage(image);
    x = requireInt(x, 'x', 0);
    y = requireInt(y, 'y', 0);
    var value = imageHostCall('pixel', { id: frame._id, x: x, y: y });
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      throw hostError('图片像素返回值非法：' + String(value));
    }
    return value | 0;
  }

  function imageRead(path) {
    var resolved = resolveImagePath(path, '图片路径');
    return wrapImage(imageHostCall('read', { path: resolved }));
  }

  function imageWrite(image, path, format, quality) {
    var frame = wrapImage(image);
    var resolved = resolveImagePath(path, '图片输出路径');
    var normalizedFormat = format == null ? 'png' : String(format).toLowerCase();
    if (normalizedFormat === 'jpg') { normalizedFormat = 'jpeg'; }
    if (normalizedFormat !== 'png' && normalizedFormat !== 'jpeg') {
      throw apiRangeError('图片格式只支持 png/jpeg（收到 ' + format + '）');
    }
    if (quality == null) { quality = DEFAULT_IMAGE_QUALITY; }
    quality = requireInt(quality, 'quality', 0, 100);
    var value = imageHostCall('write', {
      id: frame._id,
      path: resolved,
      format: normalizedFormat,
      quality: quality
    });
    if (typeof value !== 'boolean') {
      throw hostError('写入图片返回值非法：' + String(value));
    }
    return value;
  }

  function imageFindColorSync(image, color, options) {
    var frame = wrapImage(image);
    options = normalizeImageOptions(options);
    var request = {
      kind: 'image',
      action: 'findColor',
      id: frame._id,
      color: colorToInt(color),
      threshold: imageThreshold(options)
    };
    var region = normalizeImageRegion(options.region, frame);
    if (region) { request.region = region; }
    return normalizeImagePoint(hostValue(request), 'findColor');
  }

  /** 模板匹配阈值是 0..1 的相似度，不接受字符串或 NaN。 */
  function imageSimilarityThreshold(value, fallback, name) {
    if (value == null) { return fallback; }
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      throw apiTypeError(name + ' 必须是 0..1 之间的数字（收到 ' + String(value) + '）');
    }
    if (value < 0 || value > 1) {
      throw apiRangeError(name + ' 必须在 0..1 之间（收到 ' + value + '）');
    }
    return value;
  }

  function imageMatchLevel(value) {
    if (value == null) { return -1; }
    if (!isIntegerValue(value) || value < -1 || value > MAX_IMAGE_MATCH_LEVEL) {
      throw apiRangeError('level 必须是 -1..' + MAX_IMAGE_MATCH_LEVEL + ' 之间的整数（收到 '
        + String(value) + '）');
    }
    return value;
  }

  /**
   * 在 image 中查找 template，返回匹配左上角的 {x,y} 或 null。
   * 宿主执行真实 OpenCV 模板匹配；这里只校验参数、发送真实句柄 id 并映射结果。
   * 两张源图都不会被回收，调用方仍需自行 recycle()。
   */
  function imageFindImageSync(image, template, options) {
    var frame = wrapImage(image);
    var target = wrapImage(template);
    options = normalizeImageOptions(options);
    var threshold = imageSimilarityThreshold(options.threshold, DEFAULT_IMAGE_SIMILARITY, 'threshold');
    var weakThreshold = imageSimilarityThreshold(
      options.weakThreshold, DEFAULT_IMAGE_WEAK_SIMILARITY, 'weakThreshold');
    var level = imageMatchLevel(options.level);
    var region = normalizeImageRegion(options.region, frame);
    if (!region) { region = [0, 0, frame._width, frame._height]; }
    if (target._width > region[2] || target._height > region[3]) {
      throw apiRangeError('模板 ' + target._width + 'x' + target._height
        + ' 大于搜索区域 ' + region[2] + 'x' + region[3]);
    }
    var request = {
      kind: 'image',
      action: 'findImage',
      id: frame._id,
      templateId: target._id,
      threshold: threshold,
      weakThreshold: weakThreshold,
      level: level,
      region: region
    };
    return normalizeImagePoint(hostValue(request), 'findImage');
  }

  /**
   * 批量像素：一次宿主调用取回整块区域，返回 {width,height,format,data:Uint8Array}。
   * 这是「全 Node 化」的关键 API：把逐像素 pixel() 调用压成一次调用。
   */
  function imageGetPixelsSync(image, options) {
    var frame = wrapImage(image);
    options = normalizeImageOptions(options);
    var region = normalizeImageRegion(options.region, frame);
    if (!region) { region = [0, 0, frame._width, frame._height]; }
    var format = options.format == null ? 'argb' : String(options.format);
    if (format !== 'argb' && format !== 'gray') {
      throw new TypeError('getPixels 的 format 仅支持 argb/gray：' + format);
    }
    var value = hostValue({
      kind: 'image',
      action: 'getPixels',
      id: frame._id,
      region: region,
      format: format
    });
    if (!value || typeof value !== 'object' || typeof value.data !== 'string'
      || !isIntegerValue(value.width) || !isIntegerValue(value.height)) {
      throw hostError('getPixels 返回格式非法：' + JSON.stringify(value).slice(0, 120));
    }
    var buffer = Buffer.from(value.data, 'base64');
    return {
      width: value.width,
      height: value.height,
      format: value.format || format,
      data: new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength)
    };
  }

  /** 多结果模板匹配：返回 [{x,y,similarity,point:{x,y}}]，按相似度降序。 */
  function imageMatchTemplateSync(image, template, options) {
    var frame = wrapImage(image);
    var target = wrapImage(template);
    options = normalizeImageOptions(options);
    var threshold = imageSimilarityThreshold(options.threshold, DEFAULT_IMAGE_SIMILARITY, 'threshold');
    var weakThreshold = imageSimilarityThreshold(
      options.weakThreshold, DEFAULT_IMAGE_WEAK_SIMILARITY, 'weakThreshold');
    var level = imageMatchLevel(options.level);
    var limit = options.limit == null ? 32 : Math.max(1, Math.min(4096, Math.round(Number(options.limit) || 32)));
    var region = normalizeImageRegion(options.region, frame);
    if (!region) { region = [0, 0, frame._width, frame._height]; }
    if (target._width > region[2] || target._height > region[3]) {
      throw apiRangeError('模板 ' + target._width + 'x' + target._height
        + ' 大于搜索区域 ' + region[2] + 'x' + region[3]);
    }
    var value = hostValue({
      kind: 'image',
      action: 'matchTemplate',
      id: frame._id,
      templateId: target._id,
      threshold: threshold,
      weakThreshold: weakThreshold,
      level: level,
      limit: limit,
      region: region
    });
    var matches = value && value.matches;
    if (!Array.isArray(matches)) {
      throw hostError('matchTemplate 返回格式非法：' + JSON.stringify(value).slice(0, 120));
    }
    return matches.map(function (match) {
      return {
        point: { x: match.x, y: match.y },
        x: match.x,
        y: match.y,
        similarity: match.similarity
      };
    });
  }

  // task-172：特征对象（detectAndComputeFeatures 返回值）与 matchFeatures。
  var featureHandles = Object.create(null);
  var recycledFeatureIds = Object.create(null);

  function featureDescriptor(value) {
    if (!value || typeof value !== 'object') {
      throw hostError('宿主返回了非法特征句柄：' + String(value));
    }
    if (!isIntegerValue(value.id) || value.id < 0) {
      throw hostError('宿主返回的特征句柄 id 非法：' + String(value.id));
    }
    return {
      id: value.id,
      count: isIntegerValue(value.count) ? value.count : 0,
      width: isIntegerValue(value.width) ? value.width : 0,
      height: isIntegerValue(value.height) ? value.height : 0,
      scale: typeof value.scale === 'number' && Number.isFinite(value.scale) ? value.scale : 1,
      method: typeof value.method === 'string' ? value.method : 'SIFT'
    };
  }

  function ImageFeatures(descriptor) {
    if (!(this instanceof ImageFeatures)) { return new ImageFeatures(descriptor); }
    var info = featureDescriptor(descriptor);
    if (recycledFeatureIds[info.id]) {
      throw hostError('特征句柄已回收：id=' + info.id);
    }
    if (featureHandles[info.id]) { return featureHandles[info.id]; }
    this._id = info.id;
    this._count = info.count;
    this._width = info.width;
    this._height = info.height;
    this._scale = info.scale;
    this._method = info.method;
    this._recycled = false;
    featureHandles[info.id] = this;
  }

  Object.defineProperties(ImageFeatures.prototype, {
    id: { enumerable: true, get: function () { return this._id; } },
    count: { enumerable: true, get: function () { return this._count; } },
    width: { enumerable: true, get: function () { return this._width; } },
    height: { enumerable: true, get: function () { return this._height; } },
    scale: { enumerable: true, get: function () { return this._scale; } },
    method: { enumerable: true, get: function () { return this._method; } }
  });

  ImageFeatures.prototype.assertOpen = function () {
    if (this._recycled) {
      throw hostError('特征对象已回收：id=' + this._id);
    }
    return this;
  };
  ImageFeatures.prototype.isRecycled = function () { return !!this._recycled; };
  ImageFeatures.prototype.getCount = function () { return this._count; };
  ImageFeatures.prototype.recycle = function () {
    if (this._recycled) { return true; }
    var value = imageHostCall('recycleFeatures', { id: this._id });
    if (value !== true) {
      throw hostError('回收特征对象失败：宿主返回 ' + String(value) + '（id=' + this._id + '）');
    }
    this._recycled = true;
    recycledFeatureIds[this._id] = true;
    if (featureHandles[this._id] === this) { delete featureHandles[this._id]; }
    return true;
  };
  ImageFeatures.prototype.close = function () { return this.recycle(); };
  ImageFeatures.prototype.toJSON = function () {
    this.assertOpen();
    return {
      id: this._id, count: this._count, width: this._width, height: this._height,
      scale: this._scale, method: this._method
    };
  };

  function wrapImageFeatures(value) {
    if (value instanceof ImageFeatures) { return value.assertOpen(); }
    return new ImageFeatures(value);
  }

  function imageDetectFeaturesSync(image, options) {
    var frame = wrapImage(image);
    options = normalizeImageOptions(options);
    var request = {
      kind: 'image',
      action: 'detectAndComputeFeatures',
      id: frame._id,
      grayscale: options.grayscale === false ? false : true
    };
    if (options.scale != null) {
      if (typeof options.scale !== 'number' || !Number.isFinite(options.scale) || options.scale <= 0) {
        throw apiRangeError('detectAndComputeFeatures 的 scale 必须是大于 0 的数字');
      }
      request.scale = options.scale;
    }
    if (options.method != null) { request.method = String(options.method); }
    var region = normalizeImageRegion(options.region, frame);
    if (region) { request.region = region; }
    return new ImageFeatures(imageHostCall('detectAndComputeFeatures', request));
  }

  function imageMatchFeaturesSync(sceneFeatures, objectFeatures, options) {
    var scene = wrapImageFeatures(sceneFeatures);
    var object = wrapImageFeatures(objectFeatures);
    options = normalizeImageOptions(options);
    var raw = options.thredshold === undefined ? options.threshold : options.thredshold;
    var ratio = raw === undefined ? 0.7 : Number(raw);
    if (!Number.isFinite(ratio) || ratio <= 0 || ratio >= 1) {
      throw apiRangeError('matchFeatures 的 thredshold/threshold 必须在 0..1 之间');
    }
    var request = {
      kind: 'image',
      action: 'matchFeatures',
      sceneId: scene._id,
      objectId: object._id,
      ratio: ratio
    };
    if (options.drawMatches != null) {
      request.drawMatches = resolveImagePath(String(options.drawMatches), 'drawMatches');
    }
    var value = hostValue(request);
    if (value == null) { return null; }
    return value;
  }

  function imageMatchFeatures(sceneFeatures, objectFeatures, options) {
    return Promise.resolve().then(function () {
      return imageMatchFeaturesSync(sceneFeatures, objectFeatures, options);
    });
  }

  function isMultiColorTarget(value) {
    return !!(value && typeof value === 'object' && !Array.isArray(value)
      && Object.prototype.hasOwnProperty.call(value, 'firstColor'));
  }

  function parseOffsetColors(list, label) {
    if (!Array.isArray(list) || list.length === 0) {
      throw apiTypeError(label + ' 的 offsetColors 必须是非空数组');
    }
    var result = [];
    for (var i = 0; i < list.length; i++) {
      var item = list[i];
      var offsetX;
      var offsetY;
      var color;
      if (Array.isArray(item)) {
        if (item.length < 3) {
          throw apiTypeError(label + ' 的 offsetColors[' + i + '] 必须是 {offsetX,offsetY,color} 或 [dx,dy,color]');
        }
        offsetX = item[0];
        offsetY = item[1];
        color = item[2];
      } else if (item && typeof item === 'object') {
        offsetX = item.offsetX;
        offsetY = item.offsetY;
        color = item.color;
      } else {
        throw apiTypeError(label + ' 的 offsetColors[' + i + '] 格式非法');
      }
      result.push({
        offsetX: requireInt(offsetX, 'offsetColors[' + i + '].offsetX'),
        offsetY: requireInt(offsetY, 'offsetColors[' + i + '].offsetY'),
        color: colorToInt(color)
      });
    }
    return result;
  }

  function normalizeMultiArgs(colorOrTarget, offsetColorsOrOptions, maybeOptions, label) {
    var color;
    var offsets;
    var options;
    if (isMultiColorTarget(colorOrTarget)) {
      color = colorOrTarget.firstColor;
      offsets = colorOrTarget.offsetColors;
      options = offsetColorsOrOptions;
    } else {
      color = colorOrTarget;
      if (Array.isArray(offsetColorsOrOptions)) {
        offsets = offsetColorsOrOptions;
        options = maybeOptions;
      } else if (offsetColorsOrOptions && typeof offsetColorsOrOptions === 'object') {
        options = offsetColorsOrOptions;
        offsets = options.offsetColors;
      } else {
        throw apiTypeError(label + ' 缺少 offsetColors（推荐传 {firstColor,offsetColors} 或 [dx,dy,color] 数组）');
      }
    }
    options = normalizeImageOptions(options);
    return {
      color: colorToInt(color),
      offsetColors: parseOffsetColors(offsets, label),
      options: options
    };
  }

  function imageFindMultiColorsSync(image, colorOrTarget, offsetColorsOrOptions, maybeOptions) {
    var frame = wrapImage(image);
    var multi = normalizeMultiArgs(colorOrTarget, offsetColorsOrOptions, maybeOptions, 'findMultiColors');
    var request = {
      kind: 'image',
      action: 'findMultiColors',
      id: frame._id,
      color: multi.color,
      offsetColors: multi.offsetColors,
      threshold: imageThreshold(multi.options)
    };
    var region = normalizeImageRegion(multi.options.region, frame);
    if (region) { request.region = region; }
    return normalizeImagePoint(hostValue(request), 'findMultiColors');
  }

  function imageDetectsColor(image, color, x, y, options) {
    var frame = wrapImage(image);
    options = normalizeImageOptions(options);
    var request = {
      kind: 'image',
      action: 'detectsColor',
      id: frame._id,
      color: colorToInt(color),
      x: requireInt(x, 'x', 0),
      y: requireInt(y, 'y', 0),
      threshold: imageThreshold(options)
    };
    var value = hostValue(request);
    if (typeof value !== 'boolean') {
      throw hostError('detectsColor 返回值非法：' + String(value));
    }
    return value;
  }

  function imageDetectsMultiColors(image, colorOrTarget, offsetColorsOrX, xOrY, yOrOptions, maybeOptions) {
    var frame = wrapImage(image);
    var multi;
    var x;
    var y;
    var options;
    if (isMultiColorTarget(colorOrTarget)) {
      multi = normalizeMultiArgs(colorOrTarget, undefined, undefined, 'detectsMultiColors');
      x = offsetColorsOrX;
      y = xOrY;
      options = yOrOptions;
    } else {
      multi = normalizeMultiArgs(colorOrTarget, offsetColorsOrX, undefined, 'detectsMultiColors');
      x = xOrY;
      y = yOrOptions;
      options = maybeOptions;
    }
    options = normalizeImageOptions(options);
    var request = {
      kind: 'image',
      action: 'detectsMultiColors',
      id: frame._id,
      color: multi.color,
      offsetColors: multi.offsetColors,
      x: requireInt(x, 'x', 0),
      y: requireInt(y, 'y', 0),
      threshold: imageThreshold(options)
    };
    var value = hostValue(request);
    if (typeof value !== 'boolean') {
      throw hostError('detectsMultiColors 返回值非法：' + String(value));
    }
    return value;
  }

  function promiseCall(fn) {
    return function () {
      var args = arguments;
      return Promise.resolve().then(function () { return fn.apply(null, args); });
    };
  }

  var imageModule = {
    Image: Image,
    ImageFeatures: ImageFeatures,
    readImage: promiseCall(imageRead),
    readImageSync: imageRead,
    writeImage: promiseCall(imageWrite),
    writeImageSync: imageWrite,
    pixel: imagePixelValue,
    pixelSync: imagePixelValue,
    findColor: promiseCall(imageFindColorSync),
    findColorSync: imageFindColorSync,
    findMultiColors: promiseCall(imageFindMultiColorsSync),
    findMultiColorsSync: imageFindMultiColorsSync,
    findImage: promiseCall(imageFindImageSync),
    findImageSync: imageFindImageSync,
    matchTemplate: promiseCall(imageMatchTemplateSync),
    matchTemplateSync: imageMatchTemplateSync,
    getPixels: promiseCall(imageGetPixelsSync),
    getPixelsSync: imageGetPixelsSync,
    matchFeatures: imageMatchFeatures,
    matchFeaturesSync: imageMatchFeaturesSync,
    detectsColor: imageDetectsColor,
    detectsMultiColors: imageDetectsMultiColors,
    recycle: function (image) {
      if (image instanceof Image) { return image.recycle(); }
      if (image && typeof image === 'object' && isIntegerValue(image.id) && recycledImageIds[image.id]) {
        return true;
      }
      return wrapImage(image).recycle();
    }
  };

  // ------------------------------------------------------------------
  // accessibility
  // ------------------------------------------------------------------
  function accessibilityStatus() {
    var value = hostValue({ kind: 'automation', action: 'status' });
    if (!value || typeof value !== 'object'
      || typeof value.enabled !== 'boolean' || typeof value.ready !== 'boolean') {
      throw hostError('无障碍状态返回格式非法：' + JSON.stringify(value));
    }
    return { enabled: value.enabled, ready: value.ready };
  }

  function accessibilityOpenSettings() {
    var value = hostValue({ kind: 'automation', action: 'openSettings' });
    if (value !== true) {
      throw hostError('打开无障碍设置失败：宿主返回 ' + String(value));
    }
    return true;
  }

  function runAccessibilityAction(action, fields) {
    var request = { kind: 'automation', action: action };
    if (fields) {
      for (var key in fields) {
        if (Object.prototype.hasOwnProperty.call(fields, key)) { request[key] = fields[key]; }
      }
    }
    var value = hostValue(request);
    if (typeof value !== 'boolean') {
      throw hostError('无障碍动作 ' + action + ' 返回值非法：' + String(value));
    }
    return value;
  }

  function accessibilityClick(x, y) {
    return runAccessibilityAction('click', {
      x: requireInt(x, 'x'),
      y: requireInt(y, 'y')
    });
  }

  function accessibilityLongClick(x, y) {
    return runAccessibilityAction('longClick', {
      x: requireInt(x, 'x'),
      y: requireInt(y, 'y')
    });
  }

  function accessibilityPress(x, y, duration) {
    return runAccessibilityAction('press', {
      x: requireInt(x, 'x'),
      y: requireInt(y, 'y'),
      duration: requireInt(duration, 'duration', 1)
    });
  }

  function accessibilitySwipe(x1, y1, x2, y2, duration) {
    return runAccessibilityAction('swipe', {
      x1: requireInt(x1, 'x1'),
      y1: requireInt(y1, 'y1'),
      x2: requireInt(x2, 'x2'),
      y2: requireInt(y2, 'y2'),
      duration: requireInt(duration, 'duration', 1)
    });
  }

  function normalizeStrokePoints(points, label) {
    if (!Array.isArray(points) || points.length === 0) {
      throw apiTypeError((label || '手势') + ' points 必须是非空数组');
    }
    var result = [];
    var lastExplicitT = null;
    for (var i = 0; i < points.length; i++) {
      var point = points[i];
      var x;
      var y;
      var t;
      if (Array.isArray(point)) {
        x = point[0];
        y = point[1];
        t = point[2];
      } else if (point && typeof point === 'object') {
        x = point.x;
        y = point.y;
        t = point.t;
      } else {
        throw apiTypeError((label || '手势') + ' 第 ' + i + ' 个点格式非法');
      }
      var prefix = (label || '手势') + '.points[' + i + ']';
      var normalized = {
        x: requireInt(x, prefix + '.x', 0),
        y: requireInt(y, prefix + '.y', 0)
      };
      if (t != null) {
        normalized.t = requireInt(t, prefix + '.t', 0);
        if (lastExplicitT != null && normalized.t < lastExplicitT) {
          throw apiRangeError(prefix + '.t 不能递减（' + normalized.t + ' < ' + lastExplicitT + '）');
        }
        lastExplicitT = normalized.t;
      }
      result.push(normalized);
    }
    return result;
  }

  function requireGestureTiming(delay, duration, label) {
    if (delay + duration > MAX_GESTURE_DURATION) {
      throw apiRangeError(label + ' 的 delay + duration 不能超过 '
        + MAX_GESTURE_DURATION + 'ms（收到 ' + (delay + duration) + 'ms）');
    }
  }

  // 与 Pro 的 StrokeDescription(points, duration, delay) 对齐；delay 会写进每个点的 t。
  function StrokeDescription(points, duration, delay) {
    if (!(this instanceof StrokeDescription)) {
      return new StrokeDescription(points, duration, delay);
    }
    this._points = normalizeStrokePoints(points, 'StrokeDescription');
    this.duration = requireInt(duration, 'duration', 1);
    this.delay = delay == null ? 0 : requireInt(delay, 'delay', 0);
    requireGestureTiming(this.delay, this.duration, 'StrokeDescription');
    // points 作为只读快照暴露，便于调用方检查；真正发宿主时用 _points + delay。
    this.points = this.getPoints();
    // 兼容旧命名：startTime 与 delay 同义。
    this.startTime = this.delay;
  }

  StrokeDescription.prototype.getStartTime = function () { return this.delay; };
  StrokeDescription.prototype.getDuration = function () { return this.duration; };
  StrokeDescription.prototype.getPoints = function () {
    var result = [];
    for (var i = 0; i < this._points.length; i++) {
      var point = { x: this._points[i].x, y: this._points[i].y };
      if (this._points[i].t !== undefined) { point.t = this._points[i].t; }
      result.push(point);
    }
    return result;
  };
  StrokeDescription.prototype.toJSON = function () {
    return { points: this.getPoints(), startTime: this.startTime, duration: this.duration };
  };

  function strokeToHostPoints(stroke, index) {
    var points;
    var startTime = 0;
    var duration;
    if (stroke instanceof StrokeDescription) {
      points = stroke._points;
      startTime = stroke.delay;
      duration = stroke.duration;
    } else if (Array.isArray(stroke)) {
      points = normalizeStrokePoints(stroke, 'gestures[' + index + ']');
    } else if (stroke && typeof stroke === 'object' && Array.isArray(stroke.points)) {
      points = normalizeStrokePoints(stroke.points, 'gestures[' + index + ']');
      if (stroke.delay != null) {
        startTime = requireInt(stroke.delay, 'gestures[' + index + '].delay', 0);
      } else if (stroke.startTime != null) {
        startTime = requireInt(stroke.startTime, 'gestures[' + index + '].startTime', 0);
      }
      if (stroke.duration != null) {
        duration = requireInt(stroke.duration, 'gestures[' + index + '].duration', 1);
      }
    } else {
      throw apiTypeError('gestures[' + index + '] 必须是 StrokeDescription、点数组或 {points,duration}');
    }

    var explicitMax = 0;
    for (var i = 0; i < points.length; i++) {
      if (points[i].t != null && points[i].t > explicitMax) { explicitMax = points[i].t; }
    }
    if (duration == null) { duration = explicitMax > 0 ? explicitMax : 300; }
    duration = requireInt(duration, 'gestures[' + index + '].duration', 1);
    requireGestureTiming(startTime, duration, 'gestures[' + index + ']');

    // 单点手势必须发两个同位置点：宿主把首点 t 当作 stroke 的 startTime，
    // 把其余 t 减去首点 t；只发一个点无法同时保留 delay 和 duration。
    if (points.length === 1) {
      return [
        { x: points[0].x, y: points[0].y, t: startTime },
        { x: points[0].x, y: points[0].y, t: startTime + duration }
      ];
    }

    var output = [];
    var lastT = -1;
    for (var j = 0; j < points.length; j++) {
      var point = points[j];
      var offset;
      if (point.t != null) {
        offset = point.t;
      } else {
        offset = Math.round(j * duration / (points.length - 1));
      }
      var t = startTime + offset;
      if (t > MAX_GESTURE_DURATION) {
        throw apiRangeError('gestures[' + index + '] 的手势结束时间不能超过 '
          + MAX_GESTURE_DURATION + 'ms（收到 ' + t + 'ms）');
      }
      if (t < lastT) {
        throw apiRangeError('gestures[' + index + '] 的 t 必须非递减（' + t + ' < ' + lastT + '）');
      }
      output.push({ x: point.x, y: point.y, t: t });
      lastT = t;
    }
    return output;
  }

  function accessibilityPerformGesture(points, duration, delay) {
    return Promise.resolve().then(function () {
      var stroke = new StrokeDescription(points, duration, delay == null ? 0 : delay);
      return runAccessibilityAction('gesture', { strokes: [strokeToHostPoints(stroke, 0)] });
    });
  }

  function accessibilityPerformGestures() {
    var strokes = arguments.length === 1 && Array.isArray(arguments[0])
      ? arguments[0] : Array.prototype.slice.call(arguments);
    return Promise.resolve().then(function () {
      if (!Array.isArray(strokes) || strokes.length === 0) {
        throw apiTypeError('performGestures 至少需要一个手势');
      }
      var output = [];
      for (var i = 0; i < strokes.length; i++) {
        output.push(strokeToHostPoints(strokes[i], i));
      }
      return runAccessibilityAction('gesture', { strokes: output });
    });
  }

  function accessibilityTakeScreenshot() {
    return wrapImage(hostValue({ kind: 'automation', action: 'takeScreenshot' }));
  }

  function waitForAccessibilityReady(timeout, interval) {
    return new Promise(function (resolve, reject) {
      var deadline = Date.now() + timeout;
      var timer = null;
      function check() {
        var status;
        try {
          status = accessibilityStatus();
        } catch (error) {
          finish();
          reject(error);
          return;
        }
        if (status.ready) {
          finish();
          resolve(true);
          return;
        }
        var remain = deadline - Date.now();
        if (remain <= 0) {
          finish();
          reject(apiError('开启无障碍服务超时（' + timeout + 'ms）：请在系统设置中开启本应用的无障碍服务后重试'));
          return;
        }
        timer = setTimeout(check, Math.min(interval, remain));
      }
      function finish() {
        if (timer != null) { clearTimeout(timer); timer = null; }
      }
      check();
    });
  }

  function accessibilityEnableService(options) {
    var settings = options == null ? {} : options;
    return Promise.resolve().then(function () {
      if (typeof settings !== 'object' || Array.isArray(settings)) {
        throw apiTypeError('enableService(options) 的 options 必须是对象');
      }
      var timeout = settings.timeout == null
        ? DEFAULT_ACCESSIBILITY_TIMEOUT : requireInt(settings.timeout, 'timeout', 1);
      var interval = settings.interval == null ? 250 : requireInt(settings.interval, 'interval', 1);
      var status = accessibilityStatus();
      if (status.ready) { return true; }
      if (!status.enabled) {
        accessibilityOpenSettings();
        if (settings.toast) {
          try { appModule.toast('请开启无障碍服务，开启后返回脚本'); } catch (ignored) { /* toast 只是辅助提示 */ }
        }
      }
      return waitForAccessibilityReady(timeout, interval);
    });
  }

  /** task-171：通过 :node24 宿主广播到主进程 AccessibilityService。 */
  function accessibilityHostAction(action, fields) {
    var request = { kind: 'accessibility', action: action };
    if (fields) {
      for (var key in fields) {
        if (Object.prototype.hasOwnProperty.call(fields, key)) { request[key] = fields[key]; }
      }
    }
    var value = hostValue(request);
    if (value !== true) {
      throw hostError('accessibility.' + action + ' 失败：宿主返回 ' + String(value));
    }
    return true;
  }

  function accessibilityEnableKeyEvents(enabled) {
    return accessibilityHostAction('enableKeyEvents', { enabled: enabled !== false });
  }

  function accessibilityLockScreen() {
    return accessibilityHostAction('lockScreen');
  }

  function accessibilityBack() { return runAccessibilityAction('back'); }
  function accessibilityHome() { return runAccessibilityAction('home'); }
  function accessibilityRecents() { return runAccessibilityAction('recents'); }
  function accessibilityNotifications() { return runAccessibilityAction('notifications'); }

  // --- task-156：无障碍事件订阅（主进程 AccessibilityService -> 同 UID 广播 -> :node24 轮询队列） ---
  var accessibilityEventHandlers = Object.create(null);
  var accessibilityEventInstalled = false;
  var accessibilityEventSubscribed = false;

  function makeAccessibilityEvent(payload) {
    return {
      kind: payload.kind,
      eventType: payload.eventType,
      packageName: payload.packageName || '',
      className: payload.className || '',
      text: payload.text || '',
      windowId: payload.windowId,
      eventTime: payload.eventTime,
      contentChangeTypes: payload.contentChangeTypes,
      windowChangeTypes: payload.windowChangeTypes,
      getPackageName: function () { return this.packageName; },
      getClassName: function () { return this.className; },
      getWindowId: function () { return this.windowId; },
      getEventType: function () { return this.eventType; },
      getContentChangeTypes: function () { return this.contentChangeTypes; },
      getWindowChangeTypes: function () { return this.windowChangeTypes; },
      getText: function () { return this.text ? [this.text] : []; },
      toString: function () {
        return '[accessibility_event ' + this.kind + ' pkg=' + this.packageName + ' class=' + this.className + ']';
      }
    };
  }

  function dispatchAccessibilityEvent(kind, payload) {
    var list = accessibilityEventHandlers[kind];
    if (!list || list.length === 0) { return; }
    var event = makeAccessibilityEvent(payload);
    for (var i = 0; i < list.length; i++) {
      try { list[i](event); } catch (error) { console.error('[Auto.js] accessibility 事件监听异常（' + kind + '）：', error); }
    }
  }

  function ensureAccessibilityEvents() {
    if (accessibilityEventInstalled) { return; }
    accessibilityEventInstalled = true;
    on('__ajs_accessibility_event', function (payload) {
      if (!payload || typeof payload !== 'object') { return; }
      var kind = String(payload.kind || 'accessibility_event');
      dispatchAccessibilityEvent(kind, payload);
      if (kind !== 'accessibility_event') { dispatchAccessibilityEvent('accessibility_event', payload); }
    });
    if (!accessibilityEventSubscribed) {
      accessibilityEventSubscribed = true;
      try { hostValue({ kind: 'accessibility', action: 'subscribe' }); }
      catch (error) { accessibilityEventSubscribed = false; throw error; }
    }
  }

  function accessibilityOn(type, handler) {
    if (typeof handler !== 'function') { throw new TypeError('accessibility.on 的监听必须是函数'); }
    ensureAccessibilityEvents();
    var key = String(type);
    if (!accessibilityEventHandlers[key]) { accessibilityEventHandlers[key] = []; }
    accessibilityEventHandlers[key].push(handler);
    return accessibilityModule;
  }

  function accessibilityOff(type, handler) {
    var list = accessibilityEventHandlers[String(type)];
    if (!list) { return accessibilityModule; }
    var index = list.indexOf(handler);
    if (index >= 0) { list.splice(index, 1); }
    return accessibilityModule;
  }

  function accessibilityNextEvent(type, timeout) {
    ensureAccessibilityEvents();
    var key = String(type || 'accessibility_event');
    return new Promise(function (resolve, reject) {
      var done = false;
      var timer = null;
      function listener(event) {
        if (done) { return; }
        done = true;
        accessibilityOff(key, listener);
        if (timer) { clearTimeout(timer); }
        resolve(event);
      }
      accessibilityOn(key, listener);
      var ms = Number(timeout);
      if (isFinite(ms) && ms > 0) {
        timer = setTimeout(function () {
          if (done) { return; }
          done = true;
          accessibilityOff(key, listener);
          reject(new Error('等待无障碍事件超时：' + key + '（' + ms + 'ms）'));
        }, ms);
      }
    });
  }

  var accessibilityModule = {
    enableService: accessibilityEnableService,
    // status 不涉及动作，返回同步对象；await status() 同样可用。
    status: accessibilityStatus,
    statusSync: accessibilityStatus,
    openSettings: promiseCall(accessibilityOpenSettings),
    enableKeyEvents: accessibilityEnableKeyEvents,
    lockScreen: accessibilityLockScreen,
    click: promiseCall(accessibilityClick),
    longClick: promiseCall(accessibilityLongClick),
    press: promiseCall(accessibilityPress),
    swipe: promiseCall(accessibilitySwipe),
    performGesture: accessibilityPerformGesture,
    performGestures: accessibilityPerformGestures,
    gesture: accessibilityPerformGesture,
    back: promiseCall(accessibilityBack),
    home: promiseCall(accessibilityHome),
    recents: promiseCall(accessibilityRecents),
    notifications: promiseCall(accessibilityNotifications),
    takeScreenshot: promiseCall(accessibilityTakeScreenshot),
    on: accessibilityOn,
    off: accessibilityOff,
    nextEvent: accessibilityNextEvent,
    StrokeDescription: StrokeDescription
  };
  accessibilityModule.accessibility = accessibilityModule;

  // ------------------------------------------------------------------
  // media_projection
  // ------------------------------------------------------------------
  function ScreenCaptureRequestError(message) {
    this.name = 'ScreenCaptureRequestError';
    this.message = message;
    this.stack = new Error(message).stack;
  }
  ScreenCaptureRequestError.prototype = Object.create(Error.prototype);
  ScreenCaptureRequestError.prototype.constructor = ScreenCaptureRequestError;

  function ImageUnavailableError(message) {
    this.name = 'ImageUnavailableError';
    this.message = message;
    this.stack = new Error(message).stack;
  }
  ImageUnavailableError.prototype = Object.create(Error.prototype);
  ImageUnavailableError.prototype.constructor = ImageUnavailableError;

  function projectionHostCall(action, fields) {
    var request = { kind: 'projection', action: action };
    if (fields) {
      for (var key in fields) {
        if (Object.prototype.hasOwnProperty.call(fields, key)) { request[key] = fields[key]; }
      }
    }
    return hostValue(request);
  }

  var activeScreenCapturer = null;
  // 与 Java Session.mUsed 对齐：一个 Node 任务最多成功建立一次投影授权；
  // 拒绝/超时没有建立会话，仍可重试。
  var projectionSessionUsed = false;

  function normalizeScreenCaptureOptions(options) {
    if (options == null) { return {}; }
    if (typeof options !== 'object' || Array.isArray(options)) {
      throw apiTypeError('requestScreenCapture(options) 的 options 必须是对象');
    }
    var result = {};
    var hasWidth = options.width != null;
    var hasHeight = options.height != null;
    if (hasWidth !== hasHeight) {
      throw apiTypeError('width/height 必须同时提供或同时省略');
    }
    if (hasWidth) {
      result.width = requireInt(options.width, 'width', -1);
      result.height = requireInt(options.height, 'height', -1);
      if (result.width === 0 || result.height === 0) {
        throw apiRangeError('width/height 必须是 -1 或正整数');
      }
    }
    if (options.orientation != null) {
      if (typeof options.orientation === 'string') {
        var orientation = options.orientation.trim().toLowerCase();
        if (orientation !== 'auto' && orientation !== 'portrait' && orientation !== 'landscape') {
          throw apiRangeError('orientation 只支持 auto/portrait/landscape 或 -1/0/1/2');
        }
        result.orientation = orientation;
      } else {
        var value = requireInt(options.orientation, 'orientation');
        if (value !== -1 && value !== 0 && value !== 1 && value !== 2) {
          throw apiRangeError('orientation 只支持 -1/0(自动)、1(竖屏)、2(横屏)');
        }
        result.orientation = value;
      }
    }
    return result;
  }

  function normalizeCaptureOptions(options) {
    if (options == null) { return { timeoutMillis: DEFAULT_PROJECTION_CAPTURE_TIMEOUT }; }
    if (typeof options === 'number') {
      return { timeoutMillis: requireInt(options, 'timeoutMillis', 1) };
    }
    if (typeof options !== 'object' || Array.isArray(options)) {
      throw apiTypeError('截图选项必须是对象或整数 timeoutMillis');
    }
    var timeout = options.timeoutMillis == null
      ? DEFAULT_PROJECTION_CAPTURE_TIMEOUT : requireInt(options.timeoutMillis, 'timeoutMillis', 1);
    return { timeoutMillis: timeout };
  }

  function ScreenCapturer() {
    this._stopped = false;
  }

  ScreenCapturer.prototype.nextImage = function (options) {
    var self = this;
    return Promise.resolve().then(function () {
      if (self._stopped) {
        throw new ImageUnavailableError('屏幕截图已停止');
      }
      var normalized = normalizeCaptureOptions(options);
      var descriptor = projectionHostCall('capture', {
        fresh: true,
        timeoutMillis: normalized.timeoutMillis
      });
      return wrapImage(descriptor);
    });
  };

  ScreenCapturer.prototype.latestImage = function () {
    if (this._stopped) {
      throw new ImageUnavailableError('屏幕截图已停止');
    }
    var descriptor = projectionHostCall('capture', {
      fresh: false,
      timeoutMillis: DEFAULT_PROJECTION_CAPTURE_TIMEOUT
    });
    return wrapImage(descriptor);
  };

  ScreenCapturer.prototype.stop = function () {
    if (this._stopped) { return true; }
    var value = projectionHostCall('stop', {});
    if (value !== true) {
      throw hostError('停止屏幕截图失败：宿主返回 ' + String(value));
    }
    this._stopped = true;
    if (activeScreenCapturer === this) { activeScreenCapturer = null; }
    return true;
  };
  ScreenCapturer.prototype.close = function () { return this.stop(); };
  ScreenCapturer.prototype.isStopped = function () { return !!this._stopped; };

  function requestScreenCapture(options) {
    return Promise.resolve().then(function () {
      var normalized = normalizeScreenCaptureOptions(options);
      if (projectionSessionUsed) {
        throw apiError('本次 Node 任务已成功建立过屏幕投影；一个任务只能授权一次，'
          + '请重新运行脚本后再请求');
      }
      var granted = projectionHostCall('request', { options: normalized });
      if (typeof granted !== 'boolean') {
        throw hostError('屏幕截图授权返回值非法：' + String(granted));
      }
      if (!granted) {
        throw new ScreenCaptureRequestError('用户拒绝了屏幕截图权限');
      }
      projectionSessionUsed = true;
      activeScreenCapturer = new ScreenCapturer();
      return activeScreenCapturer;
    });
  }

  function captureScreen(options) {
    return Promise.resolve().then(function () {
      if (activeScreenCapturer && !activeScreenCapturer._stopped) {
        return activeScreenCapturer;
      }
      return requestScreenCapture(options);
    }).then(function (capturer) {
      return capturer.nextImage(options);
    });
  }

  var mediaProjectionModule = {
    requestScreenCapture: requestScreenCapture,
    captureScreen: captureScreen,
    ScreenCapturer: ScreenCapturer,
    ScreenCaptureRequestError: ScreenCaptureRequestError,
    ImageUnavailableError: ImageUnavailableError,
    // 与宿主 ScreenCapturer/Node24CaptureConsentActivity 的取值一致：
    // 0/-1 自动、1 竖屏、2 横屏（不是 Pro 旧枚举的 1/2/3 编号）。
    Orientation: { CURRENT: 0, AUTO: 0, PORTRAIT: 1, LANDSCAPE: 2 }
  };

  globalThis.exit = langModule.exit;
  globalThis.sleep = langModule.sleep;
  globalThis.toast = toastModule.showToast;


  // ------------------------------------------------------------------
  // task-139 P1：dialogs / floating_window / notification / work_manager / shell / sensors
  // 缺能力的模块统一给中文明确错误（不静默成功）；shell.exec 用 Node child_process 真实现。
  // ------------------------------------------------------------------
  function unsupportedModule(name, methods, hint) {
    var module = {};
    for (var i = 0; i < methods.length; i++) {
      (function (method) {
        module[method] = function () {
          throw apiError(name + '.' + method + ' 在 Node24 暂不支持'
            + (hint ? '：' + hint : '') + '（模块已加载，能力待实现）');
        };
      })(methods[i]);
    }
    return module;
  }
  var dialogsModule = (function () {
    // task-141：经 kind:"ui" op:"dialog" 走主进程 NodeUiDialogActivity（透明 Activity + AlertDialog）。
    // 简单方法（select/confirm/input…）同步等待最终结果；buildDialog/showDialog 使用真实异步 UI：
    // Activity 立刻回 shown 确认，之后的事件经 NodeUiBridgeFacade 广播 + prelude 轮询送达 JS。
    var dialogSequence = 0;
    var dialogFacade = null;
    var dialogFacadeContext = null;
    var activeDialogs = Object.create(null);
    var dialogPollTimer = null;
    var dialogPolling = false;
    var DIALOG_POLL_INTERVAL = 25;

    function assertDialogFields(value, pathText, depth) {
      if (value === undefined) { return; }
      if (typeof value === 'function') {
        throw apiError('对话框参数不能包含函数：' + pathText
          + '（事件请用 dialog.on(type, fn) 注册；函数不会跨进程 JSON 传输）');
      }
      if (value === null || typeof value !== 'object') { return; }
      if (depth > 12) { throw apiError('对话框参数嵌套过深：' + pathText); }
      if (Array.isArray(value)) {
        for (var i = 0; i < value.length; i++) {
          assertDialogFields(value[i], pathText + '[' + i + ']', depth + 1);
        }
        return;
      }
      for (var key in value) {
        if (Object.prototype.hasOwnProperty.call(value, key) && value[key] !== undefined) {
          assertDialogFields(value[key], pathText + '.' + key, depth + 1);
        }
      }
    }

    function requestDialog(fields) {
      assertDialogFields(fields, 'options', 0);
      var request = { kind: 'ui', op: 'dialog' };
      if (fields) { for (var key in fields) { if (Object.prototype.hasOwnProperty.call(fields, key)) { request[key] = fields[key]; } } }
      var raw = globalThis.__autojsHostRaw(JSON.stringify(request));
      var response;
      try {
        response = JSON.parse(raw);
      } catch (error) {
        throw apiError('对话框宿主返回非法 JSON：' + (error && error.message ? error.message : error));
      }
      if (!response || response.ok !== true) {
        throw apiError((response && response.error) || '对话框失败');
      }
      return response;
    }

    function normalizeTitleMessage(title, message) {
      var options = {};
      if (message === undefined && title && typeof title === 'object') {
        options = title;
      } else {
        options.title = title;
        options.message = message;
      }
      return options;
    }

    function dialogProperties(properties) {
      if (properties === undefined || properties === null) { return {}; }
      if (typeof properties !== 'object' || Array.isArray(properties)) {
        throw apiError('对话框 properties 必须是对象');
      }
      return properties;
    }

    function mergedProperties(properties, extra) {
      var source = dialogProperties(properties);
      var result = {};
      for (var key in source) {
        if (Object.prototype.hasOwnProperty.call(source, key)) { result[key] = source[key]; }
      }
      if (extra) {
        for (var name in extra) {
          if (Object.prototype.hasOwnProperty.call(extra, name)) { result[name] = extra[name]; }
        }
      }
      if (result.content !== undefined && result.message === undefined) {
        result.message = result.content;
      }
      return result;
    }

    function normalizeItems(items) {
      if (!Array.isArray(items)) { throw apiError('对话框 items 必须是字符串数组'); }
      var result = [];
      for (var i = 0; i < items.length; i++) {
        if (items[i] !== null && typeof items[i] === 'object') {
          throw apiError('对话框 items 只支持字符串，第 ' + i + ' 项不是字符串');
        }
        result.push(items[i] == null ? '' : String(items[i]));
      }
      return result;
    }

    function dialogIndex(value) {
      var number = Number(value);
      if (!isFinite(number) || number !== Math.trunc(number)) {
        throw apiError('对话框返回了非法索引：' + String(value));
      }
      return number;
    }

    function simpleDialog(options, adapt) {
      return new Promise(function (resolve, reject) {
        var response;
        try {
          response = requestDialog(options);
        } catch (error) {
          reject(error);
          return;
        }
        try {
          resolve(adapt(response));
        } catch (error) {
          reject(error);
        }
      });
    }

    function ensureDialogFacade() {
      if (dialogFacade) { return dialogFacade; }
      var autojs = globalThis.$autojs;
      if (!autojs || !autojs.java || typeof autojs.java.findClass !== 'function') {
        throw apiError('对话框事件通道不可用：$autojs.java 未初始化');
      }
      var facade = autojs.java.findClass('com.stardust.autojs.engine.node24.NodeUiBridgeFacade');
      var context = autojs.androidContext;
      if (!facade.ensureEvents(context)) {
        throw apiError('对话框事件通道注册失败（NodeUiBridgeFacade.ensureEvents 返回 false）');
      }
      dialogFacade = facade;
      dialogFacadeContext = context;
      return facade;
    }

    function sendDialogControl(token, op, value) {
      var facade = ensureDialogFacade();
      return !!facade.sendControl(dialogFacadeContext, token, op,
        value === null || value === undefined ? '' : String(value));
    }

    function stopDialogPolling(token) {
      if (token !== undefined && token !== null) { delete activeDialogs[token]; }
      for (var key in activeDialogs) {
        if (Object.prototype.hasOwnProperty.call(activeDialogs, key)) { return; }
      }
      if (dialogPollTimer) {
        clearInterval(dialogPollTimer);
        dialogPollTimer = null;
      }
    }

    function startDialogPolling() {
      if (dialogPollTimer) { return; }
      dialogPollTimer = setInterval(function () {
        if (dialogPolling) { return; }
        dialogPolling = true;
        try {
          var facade = ensureDialogFacade();
          var raw = facade.pollEvents('');
          var events;
          try {
            events = JSON.parse(String(raw === null || raw === undefined ? '[]' : raw));
          } catch (error) {
            throw apiError('对话框事件返回非法 JSON：' + (error && error.message ? error.message : error));
          }
          if (!Array.isArray(events)) { throw apiError('对话框事件格式非法（期望数组）'); }
          for (var i = 0; i < events.length; i++) {
            var event = events[i];
            if (!event || !event.token) { continue; }
            var dialog = activeDialogs[String(event.token)];
            if (!dialog) { continue; }
            dialog._receiveHostEvent(String(event.type || ''), event.payload || {});
          }
        } catch (error) {
          console.error('[Auto.js] 对话框事件轮询失败：', error);
        } finally {
          dialogPolling = false;
        }
      }, DIALOG_POLL_INTERVAL);
    }

    function makeDialogToken() {
      dialogSequence += 1;
      return 'node24-dialog-' + Date.now().toString(36) + '-' + dialogSequence + '-'
        + Math.random().toString(36).slice(2, 8);
    }

    try {
      process.on('exit', function () {
        for (var token in activeDialogs) {
          if (Object.prototype.hasOwnProperty.call(activeDialogs, token)) {
            try { sendDialogControl(token, 'dismiss', ''); } catch (ignored) { }
          }
        }
      });
    } catch (ignored) { }

    function explicitDialogType(value) {
      if (value === 'alert' || value === 'confirm' || value === 'prompt'
        || value === 'select' || value === 'single_choice' || value === 'multi_choice'
        || value === 'input' || value === 'progress') {
        return value;
      }
      return '';
    }

    function inferDialogType(options) {
      var explicit = explicitDialogType(options.dialogType) || explicitDialogType(options.type);
      if (explicit) { return explicit; }
      if (options.progress && typeof options.progress === 'object') { return 'progress'; }
      if (options.items !== undefined) {
        var mode = options.itemsSelectMode;
        if (mode === 'multi' || mode === 'multiChoice') { return 'multi_choice'; }
        if (mode === 'single' || mode === 'singleChoice') { return 'single_choice'; }
        return 'select';
      }
      if (options.input === true || options.inputPrefill !== undefined || options.inputHint !== undefined) {
        return 'input';
      }
      if (options.positive !== undefined && options.negative !== undefined) { return 'confirm'; }
      return 'alert';
    }

    function cancelEventArgs(dialogType, dialog) {
      if (dialogType === 'multi_choice') { return ['multi_choice', [[], [], dialog]]; }
      if (dialogType === 'single_choice') { return ['single_choice', [-1, null, dialog]]; }
      if (dialogType === 'item_select' || dialogType === 'select') { return ['item_select', [-1, null, dialog]]; }
      if (dialogType === 'input' || dialogType === 'prompt') { return ['input', [null, dialog]]; }
      return null;
    }

    function DialogHandle(options, token, dialogType) {
      this._options = options;
      this._token = token;
      this._dialogType = dialogType;
      this._listeners = Object.create(null);
      this._pending = [];
      this._showing = false;
      this._closed = false;
      this._dismissDelivered = false;
      this._dismissRequested = false;
      this._dismissTimer = null;
      this._progress = options.progress && typeof options.progress.value === 'number'
        ? Number(options.progress.value) : 0;
      this._progressMax = options.progress && typeof options.progress.max === 'number'
        ? Number(options.progress.max) : 100;
      this._lastProgressAt = 0;
      this._progressTimer = null;
    }

    DialogHandle.prototype._removeListener = function (type, entry) {
      var entries = this._listeners[type];
      if (!entries) { return; }
      var index = entries.indexOf(entry);
      if (index >= 0) { entries.splice(index, 1); }
      if (entries.length === 0) { delete this._listeners[type]; }
    };

    DialogHandle.prototype._deliver = function (type, args) {
      var entries = this._listeners[type];
      if (!entries || entries.length === 0) {
        this._pending.push({ type: type, args: args });
        return;
      }
      var copy = entries.slice();
      for (var i = 0; i < copy.length; i++) {
        var entry = copy[i];
        if (entry.once) { this._removeListener(type, entry); }
        try {
          entry.handler.apply(this, args);
        } catch (error) {
          console.error('[Auto.js] 对话框事件监听执行异常（' + type + '）：', error);
        }
      }
    };

    DialogHandle.prototype._flushPending = function (type) {
      for (var i = 0; i < this._pending.length;) {
        var entry = this._pending[i];
        if (entry.type === type) {
          this._pending.splice(i, 1);
          this._deliver(entry.type, entry.args);
        } else {
          i += 1;
        }
      }
    };

    DialogHandle.prototype.on = function (type, handler) {
      if (typeof handler !== 'function') { throw apiError('对话框事件监听必须是函数'); }
      if (!this._listeners[type]) { this._listeners[type] = []; }
      this._listeners[type].push({ handler: handler, once: false });
      this._flushPending(type);
      return this;
    };

    DialogHandle.prototype.once = function (type, handler) {
      if (typeof handler !== 'function') { throw apiError('对话框事件监听必须是函数'); }
      if (!this._listeners[type]) { this._listeners[type] = []; }
      this._listeners[type].push({ handler: handler, once: true });
      this._flushPending(type);
      return this;
    };

    DialogHandle.prototype.off = function (type, handler) {
      var entries = this._listeners[type];
      if (!entries) { return this; }
      if (handler === undefined) { delete this._listeners[type]; return this; }
      for (var i = entries.length - 1; i >= 0; i--) {
        if (entries[i].handler === handler) { entries.splice(i, 1); }
      }
      if (entries.length === 0) { delete this._listeners[type]; }
      return this;
    };

    DialogHandle.prototype.removeAllListeners = function (type) {
      if (type === undefined) { this._listeners = Object.create(null); }
      else { delete this._listeners[type]; }
      return this;
    };

    DialogHandle.prototype._finalize = function () {
      if (this._closed) { return; }
      this._closed = true;
      this._showing = false;
      this._dismissRequested = false;
      if (this._progressTimer) { clearTimeout(this._progressTimer); this._progressTimer = null; }
      if (this._dismissTimer) { clearTimeout(this._dismissTimer); this._dismissTimer = null; }
      stopDialogPolling(this._token);
    };

    DialogHandle.prototype._receiveHostEvent = function (type, payload) {
      var data = payload || {};
      var dialog = this;
      var terminal = false;

      function emit(eventType, args) { dialog._deliver(eventType, args); }

      if (type === 'multi_choice') {
        var indices = Array.isArray(data.indices) ? data.indices.map(function (value) { return dialogIndex(value); }) : [];
        var texts = Array.isArray(data.texts) ? data.texts.map(function (value) { return String(value); }) : [];
        emit('multi_choice', [indices, texts, dialog]);
        emit('positive', [dialog]);
        emit('any', ['positive', dialog]);
        terminal = true;
      } else if (type === 'single_choice' || type === 'item_select') {
        var index = data.index === undefined || data.index === null ? -1 : dialogIndex(data.index);
        var text = data.text === undefined || data.text === null ? null : String(data.text);
        emit(type, [index, text, dialog]);
        emit('positive', [dialog]);
        emit('any', ['positive', dialog]);
        terminal = true;
      } else if (type === 'input') {
        var inputText = data.value === undefined || data.value === null ? null : String(data.value);
        emit('input', [inputText, dialog]);
        emit('positive', [dialog]);
        emit('any', ['positive', dialog]);
        terminal = true;
      } else if (type === 'positive') {
        emit('positive', [dialog]);
        emit('any', ['positive', dialog]);
        terminal = true;
      } else if (type === 'negative') {
        // 按钮先按文档触发 negative，再触发 cancel（示例的 .on('cancel') 依赖后者）。
        // 选择/输入类事件随后带取消值触发，监听这些事件的调用方仍能拿到 -1/null/空数组。
        emit('negative', [dialog]);
        emit('cancel', [dialog]);
        var negativeChoice = cancelEventArgs(dialog._dialogType, dialog);
        if (negativeChoice) { emit(negativeChoice[0], negativeChoice[1]); }
        emit('any', ['negative', dialog]);
        terminal = true;
      } else if (type === 'neutral') {
        emit('neutral', [dialog]);
        emit('any', ['neutral', dialog]);
        terminal = true;
      } else if (type === 'cancel') {
        emit('cancel', [dialog]);
        var cancelChoice = cancelEventArgs(dialog._dialogType, dialog);
        if (cancelChoice) { emit(cancelChoice[0], cancelChoice[1]); }
        terminal = true;
      } else if (type === 'dismiss') {
        this._dismissDelivered = true;
        emit('dismiss', [dialog]);
        terminal = true;
      } else if (type === 'custom_view_click' || type === 'custom_view_text') {
        // task-161：自定义内容区事件，非终止（对话框保持打开）。
        var customId = data.id === undefined || data.id === null ? '' : String(data.id);
        emit(type, [customId, data, dialog]);
        emit('custom_view_event', [customId, data, dialog]);
        terminal = false;
      } else if (type) {
        emit(type, [data, dialog]);
        terminal = true;
      }

      if (terminal) {
        if (!this._dismissDelivered) {
          this._dismissDelivered = true;
          emit('dismiss', [dialog]);
        }
        this._finalize();
      }
    };

    DialogHandle.prototype.show = function () {
      var self = this;
      if (this._showing) { return Promise.resolve(this); }
      if (this._closed) { return Promise.reject(apiError('对话框已关闭，不能重复 show')); }
      return new Promise(function (resolve, reject) {
        var response;
        try {
          ensureDialogFacade();
          var options = {};
          for (var key in self._options) {
            if (Object.prototype.hasOwnProperty.call(self._options, key)) { options[key] = self._options[key]; }
          }
          options.async = true;
          options.token = self._token;
          options.dialogType = self._dialogType;
          activeDialogs[self._token] = self;
          startDialogPolling();
          response = requestDialog(options);
        } catch (error) {
          stopDialogPolling(self._token);
          reject(error);
          return;
        }
        var ack = response.value;
        if (!ack || ack.shown !== true) {
          stopDialogPolling(self._token);
          reject(apiError('对话框宿主未确认显示（需要 {shown:true}）'));
          return;
        }
        self._showing = true;
        self._deliver('show', [self]);
        resolve(self);
      });
    };

    DialogHandle.prototype.dismiss = function () {
      if (!this._showing || this._closed || this._dismissRequested) { return this; }
      if (!sendDialogControl(this._token, 'dismiss', '')) {
        throw apiError('对话框 dismiss 控制广播发送失败');
      }
      this._dismissRequested = true;
      if (this._progressTimer) { clearTimeout(this._progressTimer); this._progressTimer = null; }
      var self = this;
      this._dismissTimer = setTimeout(function () { self._finalize(); }, 1500);
      if (this._dismissTimer.unref) { this._dismissTimer.unref(); }
      return this;
    };

    DialogHandle.prototype.setProgress = function (progress) {
      if (!this._options.progress) {
        throw apiError('setProgress 仅适用于带 progress 配置的对话框');
      }
      var value = Number(progress);
      if (!isFinite(value)) { throw apiError('setProgress 需要有限数值：' + String(progress)); }
      this._progress = value;
      if (!this._showing || this._closed || this._dismissRequested) { return this; }
      var self = this;
      function send(fromTimer) {
        self._progressTimer = null;
        if (!self._showing || self._closed || self._dismissRequested) { return; }
        self._lastProgressAt = Date.now();
        if (!sendDialogControl(self._token, 'progress', String(Math.round(self._progress)))) {
          var error = apiError('进度更新控制广播发送失败');
          if (fromTimer) { console.error('[Auto.js] 对话框进度更新失败：', error); }
          else { throw error; }
        }
      }
      var now = Date.now();
      if (!this._lastProgressAt || now - this._lastProgressAt >= 40) { send(false); }
      else if (!this._progressTimer) {
        this._progressTimer = setTimeout(function () { send(true); }, 40);
        if (this._progressTimer.unref) { this._progressTimer.unref(); }
      }
      return this;
    };

    DialogHandle.prototype.getProgress = function () { return this._progress; };
    DialogHandle.prototype.getMaxProgress = function () { return this._progressMax; };
    DialogHandle.prototype.isShowing = function () { return this._showing && !this._closed && !this._dismissRequested; };
    DialogHandle.prototype.getToken = function () { return this._token; };
    DialogHandle.prototype.getOptions = function () {
      var result = {};
      for (var key in this._options) {
        if (Object.prototype.hasOwnProperty.call(this._options, key)) { result[key] = this._options[key]; }
      }
      return result;
    };

    /** task-161：给对话框设置 XML 自定义内容区；可在 show() 前调用。 */
    DialogHandle.prototype.customView = function (xml) {
      if (typeof xml !== 'string' || xml.trim() === '') {
        throw new TypeError('dialog.customView(xml)：xml 必须是非空字符串');
      }
      this._options.customView = xml;
      return this;
    };

    function buildDialog(properties) {
      return Promise.resolve().then(function () {
        var options = dialogProperties(properties);
        var copy = {};
        for (var key in options) {
          if (Object.prototype.hasOwnProperty.call(options, key)) { copy[key] = options[key]; }
        }
        if (copy.content !== undefined && copy.message === undefined) { copy.message = copy.content; }
        return new DialogHandle(copy, makeDialogToken(), inferDialogType(copy));
      });
    }

    function showDialog(properties) {
      return buildDialog(properties).then(function (dialog) { return dialog.show(); });
    }

    return {
      alert: function (title, message) {
        var options = normalizeTitleMessage(title, message);
        return new Promise(function (resolve, reject) {
          try {
            requestDialog({ dialogType: 'alert', title: options.title, message: options.message });
            resolve(true);
          } catch (error) {
            reject(error);
          }
        });
      },
      confirm: function (title, message) {
        var options = normalizeTitleMessage(title, message);
        return new Promise(function (resolve, reject) {
          try {
            var response = requestDialog({ dialogType: 'confirm', title: options.title, message: options.message });
            resolve(response.canceled === true ? false : response.value === true);
          } catch (error) {
            reject(error);
          }
        });
      },
      prompt: function (title, message, defaultValue) {
        var options = normalizeTitleMessage(title, message);
        return new Promise(function (resolve, reject) {
          try {
            var response = requestDialog({
              dialogType: 'prompt',
              title: options.title,
              message: options.message,
              defaultValue: defaultValue == null ? '' : String(defaultValue)
            });
            resolve(response.canceled === true || response.value === null || response.value === undefined
              ? null : String(response.value));
          } catch (error) {
            reject(error);
          }
        });
      },
      showAlertDialog: function (title, properties) {
        return Promise.resolve().then(function () {
          var options = mergedProperties(properties, { dialogType: 'alert', title: title });
          return simpleDialog(options, function () { return undefined; });
        });
      },
      showConfirmDialog: function (title, properties) {
        return Promise.resolve().then(function () {
          var options = mergedProperties(properties, { dialogType: 'confirm', title: title });
          return simpleDialog(options, function (response) {
            if (response.canceled === true) { return false; }
            return response.value === true;
          });
        });
      },
      showInputDialog: function (title, prefill, properties) {
        return Promise.resolve().then(function () {
          if (prefill && typeof prefill === 'object' && !Array.isArray(prefill) && properties === undefined) {
            properties = prefill;
            prefill = '';
          }
          var options = mergedProperties(properties, {
            dialogType: 'input',
            title: title,
            defaultValue: prefill == null ? '' : String(prefill)
          });
          return simpleDialog(options, function (response) {
            if (response.canceled === true) { return null; }
            return response.value === null || response.value === undefined ? null : String(response.value);
          });
        });
      },
      showSelectDialog: function (title, items, properties) {
        return Promise.resolve().then(function () {
          var options = mergedProperties(properties, {
            dialogType: 'select',
            title: title,
            items: normalizeItems(items)
          });
          return simpleDialog(options, function (response) {
            if (response.canceled === true) { return -1; }
            return dialogIndex(response.value);
          });
        });
      },
      showSingleChoiceDialog: function (title, items, index, properties) {
        return Promise.resolve().then(function () {
          var options = mergedProperties(properties, {
            dialogType: 'single_choice',
            title: title,
            items: normalizeItems(items),
            itemsSelectedIndex: index == null ? -1 : dialogIndex(index)
          });
          return simpleDialog(options, function (response) {
            if (response.canceled === true) { return -1; }
            return dialogIndex(response.value);
          });
        });
      },
      showMultiChoiceDialog: function (title, items, indices, properties) {
        return Promise.resolve().then(function () {
          var selected = [];
          if (indices !== undefined && indices !== null) {
            if (!Array.isArray(indices)) { throw apiError('showMultiChoiceDialog.indices 必须是数组'); }
            for (var i = 0; i < indices.length; i++) { selected.push(dialogIndex(indices[i])); }
          }
          var options = mergedProperties(properties, {
            dialogType: 'multi_choice',
            title: title,
            items: normalizeItems(items),
            itemsSelectedIndex: selected
          });
          return simpleDialog(options, function (response) {
            if (response.canceled === true) { return []; }
            if (!Array.isArray(response.value)) { throw apiError('多选对话框返回了非数组结果'); }
            return response.value.map(function (value) { return dialogIndex(value); });
          });
        });
      },
      build: function (properties) {
        // task-161：Pro 风格的链式 builder（dialogs.build().customView(xml).on(...).show()）。
        var options = dialogProperties(properties || {});
        var pending = [];
        function set(key, value) { options[key] = value; return builder; }
        var builder = {
          customView: function (xml) {
            if (typeof xml !== 'string' || xml.trim() === '') {
              throw new TypeError('dialogs.build().customView(xml)：xml 必须是非空字符串');
            }
            options.customView = xml;
            return builder;
          },
          title: function (value) { return set('title', value); },
          message: function (value) { options.message = value; options.content = value; return builder; },
          content: function (value) { options.content = value; options.message = value; return builder; },
          positive: function (value) { return set('positive', value); },
          negative: function (value) { return set('negative', value); },
          neutral: function (value) { return set('neutral', value); },
          items: function (value) { return set('items', value); },
          input: function (prefill, hint) {
            options.input = true;
            if (prefill !== undefined) { options.inputPrefill = prefill; }
            if (hint !== undefined) { options.inputHint = hint; }
            return builder;
          },
          cancelable: function (value) { return set('cancelable', !!value); },
          on: function (type, handler) {
            if (typeof handler !== 'function') { throw new TypeError('dialogs.build().on(type, handler) 需要函数'); }
            pending.push({ type: type, handler: handler });
            return builder;
          },
          show: function () {
            return buildDialog(options).then(function (dialog) {
              for (var i = 0; i < pending.length; i++) {
                dialog.on(pending[i].type, pending[i].handler);
              }
              return dialog.show();
            });
          }
        };
        return builder;
      },
      buildDialog: buildDialog,
      showDialog: showDialog
    };
  })();
  var floatingWindowModule = (function () {
    // task-142：主进程 WindowManager overlay（kind:ui op:floatingWindow，action show/close/closeAll）。
    function call(action, fields) {
      var request = { kind: 'ui', op: 'floatingWindow', action: action };
      if (fields) { for (var key in fields) { if (Object.prototype.hasOwnProperty.call(fields, key)) { request[key] = fields[key]; } } }
      var raw = globalThis.__autojsHostRaw(JSON.stringify(request));
      var response = JSON.parse(raw);
      if (!response || response.ok !== true) { throw apiError((response && response.error) || 'floating_window 命令失败'); }
      return response;
    }
    var handles = Object.create(null);
    function show(options) {
      var opts = typeof options === 'string' ? { text: options } : (options || {});
      var response = call('show', opts);
      var handle = {
        overlayId: response.overlayId,
        text: response.text,
        x: response.x,
        y: response.y,
        close: function () { return close(handle); }
      };
      handles[response.overlayId] = handle;
      return handle;
    }
    function close(handleOrId) {
      var id = handleOrId && handleOrId.overlayId ? handleOrId.overlayId : String(handleOrId || '');
      if (!id) { return false; }
      var response = call('close', { overlayId: id });
      delete handles[id];
      return true;
    }
    function closeAll() { call('closeAll', {}); handles = Object.create(null); return true; }
    return {
      show: show,
      close: close,
      closeAll: closeAll,
      rawWindow: function () { throw apiError('floating_window.rawWindow 暂不支持：请用 show({text,...}) 基础浮窗或 require("ui")'); },
      create: function () { throw apiError('floating_window.create 暂不支持：请用 show({text,...})'); }
    };
  })();
  var notificationModule = (function () {
    // task-156：真实通知由 :node24 宿主的 NotificationManager 创建（支持 action 按钮），
    // 内容/按钮点击经同 UID 广播进入 Node 轮询队列；这里做 API 与回调分发。
    var clickHandlers = [];
    var actionHandlers = [];
    var notificationListenerHandlers = [];
    var listenerEventsInstalled = false;
    var eventsInstalled = false;
    var lastOptions = Object.create(null);

    function hostCall(action, fields) {
      var request = { kind: 'notification', action: action };
      if (fields) {
        for (var key in fields) {
          if (Object.prototype.hasOwnProperty.call(fields, key)) { request[key] = fields[key]; }
        }
      }
      var raw = globalThis.__autojsHostRaw(JSON.stringify(request));
      var response;
      try { response = JSON.parse(raw); } catch (error) { throw apiError('通知宿主返回非法 JSON：' + raw); }
      if (!response || response.ok !== true) {
        throw apiError((response && response.error) || ('notification.' + action + ' 失败'));
      }
      return response.value;
    }

    function dispatch(list, event) {
      for (var i = 0; i < list.length; i++) {
        try { list[i](event); } catch (error) { console.error('[Auto.js] notification 监听异常：', error); }
      }
    }

    function ensureListenerEvents() {
      if (listenerEventsInstalled) { return; }
      listenerEventsInstalled = true;
      // 兜底通路：部分 ROM/应用通知不会触发 NotificationListenerService（或服务被杀），
      // 此时用无障碍的 notification/toast 事件补齐，保证 listener 能收到逐条事件。
      on('__ajs_accessibility_event', function (payload) {
        if (!payload || typeof payload !== 'object') { return; }
        var kind = String(payload.kind || '');
        if (kind !== 'notification' && kind !== 'toast') { return; }
        var text = payload.text ? String(payload.text) : '';
        var item = {
          id: 0,
          packageName: payload.packageName ? String(payload.packageName) : '',
          title: kind === 'toast' ? 'Toast' : text,
          text: text,
          ticker: '',
          postTime: Number(payload.eventTime || Date.now()),
          getTitle: function () { return this.title; },
          getText: function () { return this.text; },
          getPackageName: function () { return this.packageName; },
          getTickerText: function () { return ''; },
          getPostTime: function () { return this.postTime; },
          toString: function () { return '[notification(' + kind + ') ' + this.packageName + ']'; }
        };
        dispatch(notificationListenerHandlers, item);
      });
      on('__ajs_notification_listener', function (payload) {
        if (!payload || typeof payload !== 'object') { return; }
        var item = {
          id: Number(payload.id || 0),
          packageName: payload.packageName ? String(payload.packageName) : '',
          title: payload.title ? String(payload.title) : '',
          text: payload.text ? String(payload.text) : '',
          ticker: payload.ticker ? String(payload.ticker) : '',
          postTime: Number(payload.postTime || 0),
          getTitle: function () { return this.title; },
          getText: function () { return this.text; },
          getPackageName: function () { return this.packageName; },
          getTickerText: function () { return this.ticker; },
          getPostTime: function () { return this.postTime; },
          toString: function () { return '[notification ' + this.packageName + ' ' + this.title + ']'; }
        };
        dispatch(notificationListenerHandlers, item);
      });
    }

    function requestListeningNotifications(options) {
      ensureListenerEvents();
      try { hostValue({ kind: 'accessibility', action: 'subscribe' }); } catch (ignored) { }
      hostCall('requestListeningNotifications', options || {});
      var service = {
        on: function (type, handler) {
          if (type !== 'notification' && type !== 'notifications') {
            throw apiError('notificationListener.on 只支持 notification，收到：' + type);
          }
          if (typeof handler !== 'function') { throw new TypeError('notificationListener.on 的监听必须是函数'); }
          notificationListenerHandlers.push(handler);
          return service;
        },
        off: function (type, handler) {
          var index = notificationListenerHandlers.indexOf(handler);
          if (index >= 0) { notificationListenerHandlers.splice(index, 1); }
          return service;
        }
      };
      return Promise.resolve(service);
    }

    function ensureEvents() {
      if (eventsInstalled) { return; }
      eventsInstalled = true;
      on('__ajs_notification_event', function (payload) {
        if (!payload || typeof payload !== 'object') { return; }
        var id = Number(payload.id);
        var actionIndex = payload.action === undefined || payload.action === null ? -1 : Number(payload.action);
        var event = {
          id: id,
          notificationId: id,
          action: actionIndex,
          title: payload.title ? String(payload.title) : '',
          isAction: actionIndex >= 0
        };
        dispatch(clickHandlers, event);
        if (actionIndex >= 0) { dispatch(actionHandlers, event); }
      });
    }

    function normalizeActions(actions) {
      var result = [];
      if (!Array.isArray(actions)) { return result; }
      for (var i = 0; i < actions.length; i++) {
        var item = actions[i];
        if (typeof item === 'string') { result.push({ title: item }); }
        else if (item && typeof item === 'object' && item.title != null) { result.push({ title: String(item.title) }); }
        else { throw new TypeError('notification actions[' + i + '] 必须是字符串或 {title}'); }
      }
      return result;
    }

    function notify(id, options) {
      var numericId = Number(id);
      if (!isFinite(numericId)) { throw new TypeError('notification.notify(id, options)：id 必须是数字'); }
      var opts = options || {};
      ensureEvents();
      var fields = {
        id: numericId,
        title: opts.contentTitle != null ? opts.contentTitle : opts.title,
        text: opts.contentText != null ? opts.contentText : opts.text,
        ticker: opts.ticker,
        ongoing: !!opts.ongoing,
        autoCancel: opts.autoCancel !== false,
        silent: !!opts.silent,
        actions: normalizeActions(opts.actions)
      };
      lastOptions[numericId] = { fields: fields, title: fields.title, text: fields.text };
      hostCall('notify', fields);
      return { id: numericId };
    }

    return {
      notify: notify,
      requestListeningNotifications: requestListeningNotifications,
      stopListeningNotifications: function () { return hostCall('stopListeningNotifications', {}); },
      update: function (id, options) { return notify(id, options); },
      addAction: function (id, title) {
        var stored = lastOptions[Number(id)];
        if (!stored) { throw apiError('notification.addAction 需要先 notify 该 id'); }
        stored.fields = Object.assign({}, stored.fields, {
          actions: (stored.fields.actions || []).concat(normalizeActions([title]))
        });
        hostCall('notify', stored.fields);
        return true;
      },
      cancel: function (id) { delete lastOptions[Number(id)]; return hostCall('cancel', { id: Number(id) }); },
      cancelAll: function () { lastOptions = Object.create(null); return hostCall('cancelAll', {}); },
      on: function (type, handler) {
        if (typeof handler !== 'function') { throw new TypeError('notification.on 的监听必须是函数'); }
        ensureEvents();
        if (type === 'click') { clickHandlers.push(handler); }
        else if (type === 'action') { actionHandlers.push(handler); }
        else { throw apiError('notification.on 只支持 click / action，收到：' + type); }
        return notificationModule;
      },
      off: function (type, handler) {
        var list = type === 'click' ? clickHandlers : (type === 'action' ? actionHandlers : null);
        if (list) { var index = list.indexOf(handler); if (index >= 0) { list.splice(index, 1); } }
        return notificationModule;
      }
    };
  })();
  var workManagerModule = (function () {
    // task-143 化简版：当前 Node 任务内用 Node timers 调度（不持久化、不跨进程执行脚本）；
    // 真实 Auto.js 定时任务需要主进程 TimedTaskScheduler + 数据库，后续接入。
    var tasks = [];
    var nextId = 1;
    var MAX_TIMEOUT = 2147483647;
    function schedule(fireAt, onFire) {
      var delay = Math.max(0, fireAt - Date.now());
      function arm(ms) {
        var timer = setTimeout(function () {
          var left = fireAt - Date.now();
          if (left > 0) { arm(Math.min(left, MAX_TIMEOUT)); return; }
          try { onFire(); } catch (error) { console.error('[work_manager] 任务执行失败', error); }
        }, Math.min(ms, MAX_TIMEOUT));
        if (timer.unref) { timer.unref(); }
        return timer;
      }
      return arm(delay);
    }
    function newTask(type, options) {
      var id = nextId++;
      var task = Object.assign({ id: id, type: type, path: options && options.path }, options || {});
      return task;
    }
    function addOneTimeTask(options) {
      var time = Date.parse((options && options.time) || '');
      if (isNaN(time)) { return Promise.reject(apiError('addOneTimeTask.time 非法（需要 ISO 时间字符串）')); }
      var task = newTask('oneTime', options);
      task.time = time;
      task.timer = schedule(time, function () { task.finished = true; });
      tasks.push(task);
      return Promise.resolve(task);
    }
    function nextTimeFor(timeOfDay) {
      var match = /^(\d{1,2}):(\d{2})$/.exec(String(timeOfDay || ''));
      if (!match) { return null; }
      var now = new Date();
      var next = new Date(now.getFullYear(), now.getMonth(), now.getDate(), Number(match[1]), Number(match[2]), 0, 0);
      if (next.getTime() <= now.getTime()) { next.setDate(next.getDate() + 1); }
      return next.getTime();
    }
    function addDailyTask(options) {
      var next = nextTimeFor(options && options.time);
      if (!next) { return Promise.reject(apiError('addDailyTask.time 非法（需要 HH:mm）')); }
      var task = newTask('daily', options);
      task.time = next;
      task.timer = schedule(next, function () { console.log('[work_manager] 每日任务触发：' + task.path); });
      tasks.push(task);
      return Promise.resolve(task);
    }
    function addWeeklyTask(options) {
      var next = nextTimeFor(options && options.time);
      if (!next) { return Promise.reject(apiError('addWeeklyTask.time 非法（需要 HH:mm）')); }
      var task = newTask('weekly', options);
      task.time = next;
      task.timer = schedule(next, function () { console.log('[work_manager] 每周任务触发：' + task.path); });
      tasks.push(task);
      return Promise.resolve(task);
    }
    function addIntentTask() {
      return Promise.reject(apiError('addIntentTask 暂不支持：广播任务需要主进程 Intent 监听 + 脚本执行通道（后续接入）'));
    }
    function queryTimedTasks(filter) {
      var result = tasks.filter(function (task) {
        return task.type !== 'intent' && (!filter || !filter.path || task.path === filter.path);
      });
      return Promise.resolve(result.slice());
    }
    function queryIntentTasks() { return Promise.resolve([]); }
    function getTimedTask(id) {
      var found = tasks.filter(function (task) { return task.id === Number(id); });
      return Promise.resolve(found.length ? found[0] : null);
    }
    function removeTimedTask(id) {
      for (var i = 0; i < tasks.length; i++) {
        if (tasks[i].id === Number(id)) {
          if (tasks[i].timer) { clearTimeout(tasks[i].timer); }
          tasks.splice(i, 1);
          return Promise.resolve(true);
        }
      }
      return Promise.resolve(false);
    }
    return {
      addOneTimeTask: addOneTimeTask,
      addDailyTask: addDailyTask,
      addWeeklyTask: addWeeklyTask,
      addIntentTask: addIntentTask,
      getTimedTask: getTimedTask,
      removeTimedTask: removeTimedTask,
      queryTimedTasks: queryTimedTasks,
      queryIntentTasks: queryIntentTasks
    };
  })();
  var sensorsModule = (function () {
    // Pro Node 语义（反编译 v8-runtime/built_in_modules/sensors.js）：
    //   getSensor(name).enableSensorEvent(SensorDelay.X).on('change', (event, ...values) => {})
    // JS 不能直接 new SensorEventListener；宿主 Node24HostBridge 注册真实的
    // SensorEventListener，事件经 enqueueEvent → __autojsPoll 轮询通道回到这里。
    var SensorDelay = Object.freeze({
      Fastest: 0,
      Game: 1,
      Ui: 2,
      Normal: 3,
      0: 'Fastest',
      1: 'Game',
      2: 'Ui',
      3: 'Normal'
    });
    var sensorByToken = Object.create(null);
    var sensorEventBridgeInstalled = false;

    function sensorManager() {
      var manager = $autojs.androidContext.getSystemService('sensor');
      if (!manager) { throw apiError('SensorManager 不可用'); }
      return manager;
    }
    function toPlain(sensor) {
      return {
        name: String(sensor.getName()),
        type: Number(sensor.getType()),
        vendor: String(sensor.getVendor()),
        version: Number(sensor.getVersion()),
        power: Number(sensor.getPower()),
        maxRange: Number(sensor.getMaximumRange()),
        resolution: Number(sensor.getResolution())
      };
    }
    function list() {
      var list = sensorManager().getSensorList(-1);
      var count = Number(list.size());
      var result = [];
      for (var i = 0; i < count; i++) { result.push(toPlain(list.get(i))); }
      return result;
    }

    /** 字符串名走宿主 TYPE_<NAME> 映射；数字（含数字字符串）直接作为 Android sensor type。 */
    function sensorSelector(value) {
      if (typeof value === 'number') {
        if (!isFinite(value) || value !== Math.trunc(value)) { return null; }
        return value >= 0 ? { type: value } : null;
      }
      if (typeof value === 'string') {
        var text = value.trim();
        if (!text) { return null; }
        if (/^\d+$/.test(text)) { return { type: parseInt(text, 10) }; }
        return { name: text };
      }
      return null;
    }

    function sensorValues(payload) {
      var source = payload && payload.values;
      var values = [];
      if (source && typeof source.length === 'number') {
        for (var i = 0; i < source.length; i++) { values.push(Number(source[i])); }
      }
      return values;
    }

    function dispatchSensorChange(payload) {
      if (!payload) { return; }
      var sensor = sensorByToken[String(payload.token)];
      if (!sensor) { return; }
      var values = sensorValues(payload);
      var event = {
        // Pro 的 change 第一个参数是 android.hardware.SensorEvent；跨进程无法传真实 Java
        // 实例，这里提供同字段的只读快照（type/timestamp/accuracy/values/sensor）。
        type: Number(payload.sensorType),
        timestamp: Number(payload.timestamp),
        accuracy: Number(payload.accuracy),
        values: values,
        sensor: sensor
      };
      var args = ['change', event];
      for (var i = 0; i < values.length; i++) { args.push(values[i]); }
      sensor.emit.apply(sensor, args);
    }

    function dispatchSensorAccuracy(payload) {
      if (!payload) { return; }
      var sensor = sensorByToken[String(payload.token)];
      if (!sensor) { return; }
      sensor.emit('accuracy_change', Number(payload.accuracy));
    }

    function installSensorEventBridge() {
      if (sensorEventBridgeInstalled) { return; }
      sensorEventBridgeInstalled = true;
      // __autojsOn 已包含 ensurePolling；这里只挂一次全局路由，按宿主 token 找到 sensor 对象。
      globalThis.__autojsOn('sensor_change', dispatchSensorChange);
      globalThis.__autojsOn('sensor_accuracy_change', dispatchSensorAccuracy);
    }

    function createSensor(info) {
      var listeners = Object.create(null);
      var token = null;
      var sensorDelay = null;
      var sensor = {
        name: String(info.name == null ? '' : info.name),
        type: Number(info.type),
        vendor: String(info.vendor == null ? '' : info.vendor),
        version: Number(info.version),
        power: Number(info.power),
        maxRange: Number(info.maxRange),
        resolution: Number(info.resolution)
      };

      function addListener(type, handler, once) {
        if (typeof handler !== 'function') { throw apiError('传感器事件监听必须是函数'); }
        if (!listeners[type]) { listeners[type] = []; }
        listeners[type].push({ handler: handler, once: !!once });
        return sensor;
      }

      function removeListener(type, handler) {
        var entries = listeners[type];
        if (!entries) { return sensor; }
        for (var i = entries.length - 1; i >= 0; i--) {
          if (entries[i].handler === handler) { entries.splice(i, 1); }
        }
        if (entries.length === 0) { delete listeners[type]; }
        return sensor;
      }

      function emit(type) {
        var entries = listeners[type];
        if (!entries || entries.length === 0) { return false; }
        var args = Array.prototype.slice.call(arguments, 1);
        var copy = entries.slice();
        for (var i = 0; i < copy.length; i++) {
          if (copy[i].once) { removeListener(type, copy[i].handler); }
          try {
            copy[i].handler.apply(sensor, args);
          } catch (error) {
            console.error('[Auto.js] 传感器事件监听执行异常（' + type + '）：', error);
          }
        }
        return true;
      }

      sensor.on = function (type, handler) { return addListener(type, handler, false); };
      sensor.addListener = sensor.on;
      sensor.once = function (type, handler) { return addListener(type, handler, true); };
      sensor.off = removeListener;
      sensor.removeListener = removeListener;
      sensor.removeAllListeners = function (type) {
        if (type === undefined) { listeners = Object.create(null); }
        else { delete listeners[type]; }
        return sensor;
      };
      sensor.emit = emit;
      sensor.listenerCount = function (type) {
        return listeners[type] ? listeners[type].length : 0;
      };

      sensor.enableSensorEvent = function (requestedDelay) {
        var normalizedDelay = requestedDelay === undefined || requestedDelay === null
          ? SensorDelay.Normal : Number(requestedDelay);
        if (!isFinite(normalizedDelay) || normalizedDelay !== Math.trunc(normalizedDelay)
          || normalizedDelay < 0 || normalizedDelay > 3) {
          throw apiError('sensors.enableSensorEvent delay 非法：' + requestedDelay
            + '（请使用 sensors.SensorDelay.Fastest/Game/Ui/Normal）');
        }
        if (token !== null && sensorDelay === normalizedDelay) { return sensor; }
        if (token !== null) { sensor.disableSensorEvent(); }
        var request = { kind: 'sensors', op: 'register', delay: normalizedDelay };
        var selector = sensorSelector(sensor.type);
        if (selector) { request.type = selector.type; }
        else { request.name = sensor.name; }
        installSensorEventBridge();
        var registered = hostValue(request);
        if (!registered || registered.token === undefined || registered.token === null) {
          throw apiError('传感器注册失败（宿主未返回 token）：' + sensor.name);
        }
        token = registered.token;
        sensorDelay = normalizedDelay;
        sensorByToken[String(token)] = sensor;
        return sensor;
      };

      sensor.disableSensorEvent = function () {
        if (token === null) { return sensor; }
        var current = token;
        hostValue({ kind: 'sensors', op: 'unregister', token: current });
        delete sensorByToken[String(current)];
        token = null;
        sensorDelay = null;
        return sensor;
      };

      return sensor;
    }

    function getSensor(name) {
      var selector = sensorSelector(name);
      if (!selector) { return null; }
      selector.kind = 'sensors';
      selector.op = 'get';
      var info = hostValue(selector);
      if (!info) { return null; }
      return createSensor(info);
    }

    function disableAllSensorEvents() {
      var tokens = Object.keys(sensorByToken);
      for (var i = 0; i < tokens.length; i++) {
        var sensor = sensorByToken[tokens[i]];
        if (!sensor) { continue; }
        try {
          // 宿主 close() 也会兜底 unregister；这里让正常 process.exit 尽早释放硬件监听。
          sensor.disableSensorEvent();
        } catch (error) {
          // 进程退出阶段宿主可能已经关闭，不能让清理异常改变退出码。
        }
      }
    }
    try { process.on('exit', disableAllSensorEvents); } catch (ignored) { }

    return {
      getSensors: list,
      getAllSensors: list,
      getSensor: getSensor,
      SensorDelay: SensorDelay,
      // Pro Node 没有 modules 级 register/on；保留明确失败，绝不返回伪成功。
      register: function () {
        throw apiError('Node24 sensors 不提供 register：请用 getSensor(name).enableSensorEvent(delay).on("change", fn)');
      },
      unregister: function () {
        throw apiError('Node24 sensors 不提供模块级 unregister：请用 sensor.disableSensorEvent()');
      },
      on: function () {
        throw apiError('Node24 sensors 不提供模块级 on：请注册到 getSensor(name) 返回的 sensor 对象');
      },
      once: function () {
        throw apiError('Node24 sensors 不提供模块级 once：请注册到 getSensor(name) 返回的 sensor 对象');
      }
    };
  })();
  var shellOptions = {};
  function shellExec(command, options) {
    var childProcess = require('child_process');
    var opts = Object.assign({ encoding: 'utf8', timeout: 30000 }, shellOptions, options || {});
    try {
      var stdout = childProcess.execSync(String(command), opts);
      return { code: 0, result: String(stdout == null ? '' : stdout) };
    } catch (error) {
      return {
        code: typeof error.status === 'number' ? error.status : 1,
        result: String((error.stdout || '') + (error.stderr || error.message || ''))
      };
    }
  }
  var shellModule = {
    exec: shellExec,
    createShell: function (options) {
      var closed = false;
      var emitter = new (require('node:events').EventEmitter)();
      emitter.exec = function (command, perCommand) {
        if (closed) { return Promise.reject(apiError('Shell 已关闭')); }
        return Promise.resolve().then(function () {
          var result = shellExec(command, Object.assign({}, options || {}, perCommand || {}));
          String(result.result || '').split(/\r?\n/).filter(Boolean).forEach(function (line) { emitter.emit('line', line); });
          return result;
        });
      };
      emitter.exit = function () { closed = true; emitter.removeAllListeners(); return Promise.resolve(0); };
      return emitter;
    },
    checkAccess: function () { return false; },
    isRootAvailable: function () { return false; },
    setDefaultShellOptions: function (options) { shellOptions = Object.assign({}, options || {}); return true; },
    Tap: unsupportedModule('shell.Tap', ['tap', 'press', 'swipe', 'longClick'], 'root shell 通道待接入').constructor === Object
      ? { tap: function () { throw apiError('shell.Tap 需要 root，Node24 暂不支持'); } }
      : {},
    KeyEvent: { keyCode: function () { throw apiError('shell.KeyEvent 需要 root，Node24 暂不支持'); } }
  };


  // task-144：真实 work_manager（主进程 TimedTaskManager/DB），经 kind:ui op:timedTask 桥。
  var workManagerReal = (function () {
    function call(action, options, id) {
      var request = { kind: 'ui', op: 'timedTask', action: action };
      if (options !== undefined && options !== null) { request.options = options; }
      if (id !== undefined && id !== null) { request.id = id; }
      var raw = globalThis.__autojsHostRaw(JSON.stringify(request));
      var response = JSON.parse(raw);
      if (!response || response.ok !== true) { throw apiError((response && response.error) || 'work_manager 命令失败'); }
      return response.value;
    }
    function asyncCall(action, options, id) { return Promise.resolve().then(function () { return call(action, options, id); }); }
    return {
      addOneTimeTask: function (options) {
        var opts = Object.assign({}, options || {});
        if (!opts.date) { opts.date = opts.time; }
        return asyncCall('addDisposableTask', opts);
      },
      addDisposableTask: function (options) { return asyncCall('addDisposableTask', options); },
      addDailyTask: function (options) { return asyncCall('addDailyTask', options); },
      addWeeklyTask: function (options) { return asyncCall('addWeeklyTask', options); },
      addIntentTask: function (options) { return asyncCall('addBroadcastIntentTask', options); },
      addBroadcastIntentTask: function (options) { return asyncCall('addBroadcastIntentTask', options); },
      getTimedTask: function (id) { return asyncCall('getTimedTask', null, Number(id)); },
      removeTimedTask: function (id) { return asyncCall('removeTimedTask', null, Number(id)); },
      getIntentTask: function (id) { return asyncCall('getIntentTask', null, Number(id)); },
      removeIntentTask: function (id) { return asyncCall('removeIntentTask', null, Number(id)); },
      queryTimedTasks: function (filter) { return asyncCall('queryTimedTasks', filter || {}); },
      queryIntentTasks: function (filter) { return asyncCall('queryIntentTasks', filter || {}); },
      getAllTasks: function () { return asyncCall('getAllTasks'); },
      getAllIntentTasks: function () { return asyncCall('getAllIntentTasks'); },
      getTaskCount: function () { return asyncCall('getTaskCount'); }
    };
  })();

  // Preserve Node's real EventEmitter alongside Auto.js event helpers.
  eventsModule.EventEmitter = require('node:events').EventEmitter;

  globalThis.__ajsBuiltinModules = {
    rhino: rhinoModule,
    app: appModule,
    device: deviceModule,
    files: filesModule,
    storages: storagesModule,
    events: eventsModule,
    engines: enginesModule,
    threads: threadsModule,
    toast: toastModule,
    lang: langModule,
    device: deviceModule,
    clip_manager: clipManagerModule,
    datastore: datastoreModule,
    settings: settingsModule,
    power_manager: powerManagerModule,
    axios: axiosModule,
    accessibility: accessibilityModule,
    image: imageModule,
    color: colorModule,
    media_projection: mediaProjectionModule,
    dialogs: dialogsModule,
    floating_window: floatingWindowModule,
    notification: notificationModule,
    work_manager: workManagerReal,
    shell: shellModule,
    sensors: sensorsModule
  };
  // task-133-fix：模块变量在 $autojs 早期赋值时还未声明（var 提升为 undefined），这里统一补挂。
  autojs.device = deviceModule;
  autojs.files = filesModule;
  autojs.storages = storagesModule;
  autojs.events = eventsModule;
  autojs.engines = enginesModule;
  autojs.threads = threadsModule;
  autojs.toast = toastModule.showToast;
  autojs.lang = langModule;
  autojs.deviceModule = deviceModule;
  autojs.clip_manager = clipManagerModule;
  autojs.datastore = datastoreModule;
  autojs.settings = settingsModule;
  autojs.power_manager = powerManagerModule;
  autojs.axios = axiosModule;
  autojs.accessibility = accessibilityModule;
  autojs.image = imageModule;
  autojs.color = colorModule;
  autojs.media_projection = mediaProjectionModule;
  autojs.dialogs = dialogsModule;
  autojs.floating_window = floatingWindowModule;
  autojs.notification = notificationModule;
  autojs.work_manager = workManagerReal;
  autojs.shell = shellModule;
  autojs.sensors = sensorsModule;

  // task-198：Pro Node 环境常见 host 对象兼容（第三方工程用于动态 dex / classloader）。
  (function installProHostShim() {
    if (typeof globalThis.host !== 'undefined') { return; }
    try {
      var hostShim = {
        java: {
          lang: {
            Class: {
              forName: function (name, initialize, loader) {
                // 我们的 findClass 已包含动态 dex classloader，loader 参数仅作签名兼容。
                return javaApi.findClass(String(name));
              }
            }
          }
        },
        runtime: {
          getAndroidClassLoader: function () {
            return globalThis.$autojs.androidContext.getClassLoader();
          },
          loadDex: function (path) {
            var result = hostValue({ kind: 'runtime', action: 'loadDex', args: { path: String(path) } });
            return result === true || (result && result.ok === true);
          }
        },
        Packages: javaApi.Packages,
        __xiangqiProjectDir: projectDir
      };
      Object.defineProperty(hostShim, 'context', {
        configurable: true,
        get: function () { return globalThis.$autojs.androidContext; }
      });
      globalThis.host = hostShim;
    } catch (hostShimError) { /* 不阻断脚本 */ }
  })();

  globalThis.__ajsModuleFactories = moduleFactories;
  globalThis.__ajsModules = moduleCache;

  // task-195：process.exit 语义收口。
  // - 记录 exit 请求（引擎侧据此把 exit(0) 强制 SUCCESS/exitCode=0，避免 polling/宿主关闭
  //   竞态导致原生 exitCode=1 被误报 SCRIPT_ERROR）；
  // - exit/beforeExit 钩子抛错不能改变退出码（否则 Node 会以 1 退出）；
  // - 退出前停止 overlay 轮询和 keepalive，避免关闭瞬间再打宿主桥。
  (function installGracefulExit() {
    try {
      if (typeof process === 'undefined' || !process || process.__ajsExitPatched) { return; }
      var nativeExit = process.exit;
      if (typeof nativeExit !== 'function') { return; }
      process.__ajsExitPatched = true;
      function wrapExitHook(listener) {
        return function () {
          try { return listener.apply(this, arguments); }
          catch (hookError) {
            try {
              console.error('[Auto.js] exit hook failed: '
                + ((hookError && hookError.message) || hookError));
            } catch (ignored) { }
          }
        };
      }
      try {
        var originalOn = process.on;
        var originalOnce = process.once;
        if (typeof originalOn === 'function') {
          process.on = function (event, listener) {
            if ((event === 'exit' || event === 'beforeExit') && typeof listener === 'function') {
              listener = wrapExitHook(listener);
            }
            return originalOn.call(process, event, listener);
          };
        }
        if (typeof originalOnce === 'function') {
          process.once = function (event, listener) {
            if ((event === 'exit' || event === 'beforeExit') && typeof listener === 'function') {
              listener = wrapExitHook(listener);
            }
            return originalOnce.call(process, event, listener);
          };
        }
        // 已注册过的 hook 也包一层（dialog/sensors/ui 等在 prelude 前段注册）。
        try {
          var events = ['exit', 'beforeExit'];
          for (var e = 0; e < events.length; e++) {
            var eventName = events[e];
            var existing = typeof process.listeners === 'function' ? process.listeners(eventName) : [];
            if (existing && existing.length && typeof process.removeAllListeners === 'function') {
              process.removeAllListeners(eventName);
              for (var i = 0; i < existing.length; i++) {
                process.on(eventName, wrapExitHook(existing[i]));
              }
            }
          }
        } catch (wrapExistingError) { }
      } catch (hookInstallError) { }
      process.exit = function (code) {
        var value = (code === undefined || code === null) ? 0 : Number(code);
        if (!isFinite(value)) { value = 0; }
        value = value | 0;
        try {
          hostValue({ kind: 'lifecycle', action: 'exitRequested', args: { code: value } });
        } catch (ignored) { }
        try { $autojs.stopRunning(); } catch (ignored) { }
        try {
          if (typeof globalThis.__ajsStopOverlayPolling === 'function') {
            globalThis.__ajsStopOverlayPolling();
          }
        } catch (ignored) { }
        return nativeExit.call(process, value);
      };
    } catch (ignored) { }
  })();

  // ------------------------------------------------------------------
  // require 包装：只拦截内置/注册模块，其它交给 Node 原生解析。
  // ------------------------------------------------------------------
  try {
    var NodeModule = require('module');
    if (NodeModule && typeof NodeModule._load === 'function' && !NodeModule.__ajsPatched) {
      NodeModule.__ajsPatched = true;
      var originalLoad = NodeModule._load;
      NodeModule._load = function (request, parent, isMain) {
        if (typeof request === 'string') {
          var registered = loadRegistered(request);
          if (registered !== undefined) { return registered; }
          var builtin = globalThis.__ajsBuiltinModules[request];
          if (builtin !== undefined) { return builtin; }
        }
        return originalLoad.apply(this, arguments);
      };
    }
  } catch (error) {
    console.error('[Auto.js] 安装 require 包装失败：', error);
  }
})();
