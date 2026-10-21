// SPDX-License-Identifier: MIT
/*
 * Node24 plugins 模块（task-158）：列出/查询/启动已安装插件（xiangqi / apkbuilder 等）。
 * 宿主协议：kind:"plugins"，action = list | isInstalled | info | launch（见 Node24HostBridge.handlePlugins）。
 *
 *   const plugins = require('plugins');
 *   plugins.list()                 -> [{packageName,label,versionName,versionCode,launcher,exportedServices}]
 *   plugins.isInstalled(pkg)       -> boolean
 *   plugins.info(pkg)              -> 插件信息；未安装抛中文提示
 *   plugins.load(pkg)              -> Promise<插件入口 module.exports 的返回值>（真实 APK）
 *   plugins.unload(pkg)            -> 卸载插件并清理 require 缓存
 *   plugins.unloadAll()            -> 卸载全部插件
 *   plugins.launch(pkg)            -> 启动插件界面；没有 launcher 的能力插件抛中文提示
 *
 * 内置兼容别名（只有一个固定包名，不伪装成已安装 APK）：
 *   plugins.load('org.autojs.autojspro.plugin.mlkit.ocr')
 *   返回内置 MLKitOCR 兼容构造器，可直接跑原版 Pro 示例：
 *     const MLKitOCR = await plugins.load('org.autojs.autojspro.plugin.mlkit.ocr');
 *     const ocr = new MLKitOCR();
 *     const result = await ocr.detect(capture);
 *   它桥接真实的 Node24 内置 ocr 模块（require('ocr').createOCR({backend:'mlkit'})，
 *   即宿主随包离线 ML Kit，不是 fake/模拟识别结果）。构造器本身惰性：new 不创建会话，
 *   第一次 detect 才创建；并发 detect 共享同一个底层会话；release() 幂等，若在会话创建
 *   完成前 release，则创建完成后立即释放，避免泄漏。识别/创建/释放的真实后端错误原样向外抛。
 *   其它包名的 load() 走通用 Node24PluginLoader：宿主必须返回真实 mainScriptPath 和
 *   javaObject，这里再 require 插件入口并调用 module.exports(plugin)；不再返回 package info。
 *   list/isInstalled/info/launch 的原有语义不变。
 */
(function () {
  'use strict';

  if (typeof globalThis.__ajsRegisterModule !== 'function') {
    throw new Error('[Auto.js] plugins 模块必须在 Node24 prelude 之后加载（缺少 __ajsRegisterModule）');
  }

  globalThis.__ajsRegisterModule('plugins', function (api) {
    function apiError(message) {
      var error = new Error('[Auto.js] plugins: ' + message);
      error.name = 'AutoJsPluginsError';
      return error;
    }

    function hostCall(action, fields) {
      var request = { kind: 'plugins', action: action };
      if (fields) {
        for (var key in fields) {
          if (Object.prototype.hasOwnProperty.call(fields, key)) {
            request[key] = fields[key];
          }
        }
      }
      var raw;
      try {
        raw = globalThis.__autojsHostRaw(JSON.stringify(request));
      } catch (error) {
        throw apiError('宿主桥不可用：' + String(error && error.message || error));
      }
      var response;
      try {
        response = JSON.parse(raw);
      } catch (error) {
        throw apiError('宿主返回非法 JSON：' + raw);
      }
      if (!response || response.ok !== true) {
        throw apiError((response && response.error) || (action + ' 失败'));
      }
      return response.value;
    }

    function requirePackageName(value, method) {
      if (typeof value !== 'string' || value.trim() === '') {
        throw new TypeError('plugins.' + method + '(packageName)：必须是非空字符串');
      }
      return value;
    }

    // ---- 内置 MLKit OCR 兼容别名：只桥接真实内置 mlkit 后端，不代表 APK 已安装 ----
    var MLKIT_OCR_PACKAGE = 'org.autojs.autojspro.plugin.mlkit.ocr';
    var MLKIT_BACKEND = 'mlkit';

    function messageOf(error) {
      if (error && error.message) {
        return String(error.message);
      }
      return String(error);
    }

    function mlkitState() {
      return {
        sessionPromise: null,
        session: null,
        released: false,
        releaseStarted: false,
        releasePromise: null,
        sessionReleased: false,
        sessionReleaseResult: undefined
      };
    }

    function loadOcrModule() {
      var module;
      try {
        module = require('ocr');
      } catch (error) {
        throw apiError('内置 ocr 模块不可用：' + messageOf(error));
      }
      if (!module || typeof module.createOCR !== 'function') {
        throw apiError('内置 ocr 模块缺少 createOCR');
      }
      return module;
    }

    function createMlkitSession(state) {
      if (state.sessionPromise) {
        return state.sessionPromise;
      }
      var promise = Promise.resolve().then(function () {
        return loadOcrModule().createOCR({ backend: MLKIT_BACKEND });
      }).then(function (session) {
        if (!session || typeof session.detect !== 'function'
          || typeof session.release !== 'function') {
          throw apiError('内置 MLKit OCR 会话缺少 detect/release');
        }
        state.session = session;
        return session;
      });
      state.sessionPromise = promise;
      // 创建失败后允许后续 detect 重试；单独挂 catch，避免产生未处理的 rejection。
      promise.then(null, function () {
        if (state.sessionPromise === promise) {
          state.sessionPromise = null;
        }
      });
      return promise;
    }

    function ensureMlkitSession(state) {
      if (state.released) {
        return Promise.reject(apiError('MLKitOCR 已 release()，不能继续 detect'));
      }
      return state.sessionPromise || createMlkitSession(state);
    }

    function releaseMlkitSession(state, session) {
      if (state.sessionReleased) {
        return state.sessionReleaseResult;
      }
      state.sessionReleased = true;
      if (!session || typeof session.release !== 'function') {
        state.sessionReleaseResult = false;
        return false;
      }
      // 只调用一次真实后端的 release（无论 release() 在创建完成前后被调用）。
      state.sessionReleaseResult = session.release();
      return state.sessionReleaseResult;
    }

    function releaseMlkit(state) {
      if (state.releaseStarted) {
        return state.releasePromise !== null ? state.releasePromise : false;
      }
      state.releaseStarted = true;
      state.released = true;
      if (state.session) {
        return releaseMlkitSession(state, state.session);
      }
      if (state.sessionPromise) {
        var pending = state.sessionPromise.then(function (session) {
          return releaseMlkitSession(state, session);
        }, function () {
          // create 失败时没有可释放的会话，但本 Promise 仍应按 release 生命周期结束。
          return false;
        });
        state.releasePromise = pending;
        return pending;
      }
      return false;
    }

    function detectMlkit(state, image, options) {
      return Promise.resolve().then(function () {
        if (state.released) {
          throw apiError('MLKitOCR 已 release()，不能继续 detect');
        }
        return ensureMlkitSession(state).then(function (session) {
          if (state.released) {
            throw apiError('MLKitOCR 已 release()，不能继续 detect');
          }
          // 不包装、不改写识别结果；真实后端的返回值/异常原样传递。
          return session.detect(image, options);
        });
      });
    }

    function mlkitOcrConstructor() {
      function MLKitOCR() {
        if (!(this instanceof MLKitOCR)) {
          throw new TypeError('MLKitOCR 必须用 new 创建：new MLKitOCR()');
        }
        this.backend = MLKIT_BACKEND;
        this._state = mlkitState();
      }
      MLKitOCR.prototype.detect = function (image, options) {
        return detectMlkit(this._state, image, options);
      };
      MLKitOCR.prototype.release = function () {
        return releaseMlkit(this._state);
      };
      MLKitOCR.prototype.isReleased = function () {
        return this._state.released;
      };
      MLKitOCR.backend = MLKIT_BACKEND;
      MLKitOCR.packageName = MLKIT_OCR_PACKAGE;
      return MLKitOCR;
    }

    var builtInMlkitOcr = mlkitOcrConstructor();

    // Pass the actual APK Java object through the existing engine bridge.
    var loadedMainScripts = Object.create(null);
    async function loadNativePlugin(packageName) {
      var record = hostCall('load', { packageName: packageName });
      try {
        if (!record || typeof record.mainScriptPath !== 'string' || !record.javaObject) {
          throw apiError('宿主没有返回真实插件入口和 Java 对象：' + packageName);
        }
        loadedMainScripts[packageName] = record.mainScriptPath;
        var index = require(record.mainScriptPath);
        if (typeof index !== 'function') throw apiError('插件入口 module.exports 必须是函数');
        var javaObject = api.wrapJavaValue(record.javaObject);
        // Legacy factories accept the host Plugin wrapper (unwrap + entry path),
        // while Node factories call the SDK object directly. Forward Java members
        // and its bridge identity, and provide the two host wrapper methods.
        var argument = new Proxy(javaObject, {
          get: function (target, property) {
            if (property === 'unwrap') return function () { return javaObject; };
            if (property === 'getMainScriptPath') return function () { return record.mainScriptPath; };
            if (property === 'mainScriptPath') return record.mainScriptPath;
            return Reflect.get(target, property);
          }
        });
        return await index(argument);
      } catch (error) {
        clearPluginRequireCache(packageName);
        try { hostCall('unload', { packageName: packageName }); } catch (cleanupError) { }
        throw error;
      }
    }
    function clearPluginRequireCache(packageName) {
      var entry = loadedMainScripts[packageName];
      if (!entry) return;
      var directory = require('path').dirname(entry) + require('path').sep;
      Object.keys(require.cache).forEach(function (file) {
        if (file === entry || file.indexOf(directory) === 0) delete require.cache[file];
      });
      delete loadedMainScripts[packageName];
    }

    var plugins = {
      list: function () { return hostCall('list'); },
      all: function () { return plugins.list(); },
      isInstalled: function (packageName) {
        return hostCall('isInstalled', { packageName: requirePackageName(packageName, 'isInstalled') });
      },
      info: function (packageName) {
        return hostCall('info', { packageName: requirePackageName(packageName, 'info') });
      },
      load: function (packageName) {
        requirePackageName(packageName, 'load');
        // 仅此一个官方 Pro 包名走内置适配器；不做宿主元数据查询，也不表示 APK 已安装。
        if (packageName === MLKIT_OCR_PACKAGE) {
          return builtInMlkitOcr;
        }
        return Promise.resolve().then(function () {
          return loadNativePlugin(packageName);
        });
      },
      unload: function (packageName) {
        requirePackageName(packageName, 'unload');
        clearPluginRequireCache(packageName);
        var result = hostCall('unload', { packageName: packageName });
        return !!(result && (result.unloaded === undefined ? result : result.unloaded));
      },
      unloadAll: function () {
        Object.keys(loadedMainScripts).forEach(clearPluginRequireCache);
        hostCall('unloadAll');
      },
      launch: function (packageName) {
        return hostCall('launch', { packageName: requirePackageName(packageName, 'launch') });
      }
    };
    plugins.open = plugins.launch;
    return plugins;
  });
})();
