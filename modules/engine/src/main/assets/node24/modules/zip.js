// SPDX-License-Identifier: MIT
/*
 * Node24 zip 模块（Pro 语义的 zip4j 适配器）。
 *
 * 与 Rhino/QuickJS 的内置实现共用同一套 Java 真实现：
 *   com.stardust.autojs.runtime.api.Zips       -> zip4j 1.3.2（加密/AES/压缩级别/追加/删除）
 *   com.stardust.autojs.runtime.api.ZipFile    -> $zip.open(file) 的会话对象
 *
 * 这里不做任何「用 gzip 冒充 zip」或纯 JS 的 ZIP 编码；所有压缩/解压都走 zip4j。
 * JS 侧只负责：路径解析、Promise 包装、JS options -> Rhino Scriptable 参数、错误归一化。
 *
 * 公开接口（与 Node 示例 `zip压缩与解压.js` 对齐）：
 *   await zipDir(dir, dest[, options])
 *   await zipFile(file, dest[, options])
 *   await zipFiles(fileList, dest[, options])
 *   await unzip(zipFile, dest[, options])
 *   const z = open(zipFile[, {password}]);  // 同步、只构造包装对象，不读盘
 *   await z.addFile(file[, options]);       // 追加（支持 password/AES 等压缩选项）
 *   await z.removeFile(name);               // 删除条目
 *   await z.addFiles([...][, options])
 *   await z.addFolder(dir[, options])
 *   await z.extractAll(dest[, options])
 *   await z.extractFile(name, dest[, options[, newFileName]])
 *   await z.getFileHeaders() / z.getFileHeader(name)
 *   await z.isValidZipFile() / z.isEncrypted()
 *   z.getPath() / z.setPassword(password)   // setPassword 同步，返回自身便于链式调用
 *
 * ⚠️ 异步语义：这些方法返回 Promise，但 Java 宿主桥目前只有同步调用，zip4j 的工作仍在
 * Node 线程上同步执行（Promise 只保证调用方可以 await / catch）。这不是 worker 线程。
 *
 * options 与 Pro/zip4j 同名：password、compressionMethod（COMP_STORE/COMP_DEFLATE）、
 * encryptionMethod（ENC_NO_ENCRYPTION/ENC_METHOD_STANDARD/ENC_METHOD_AES）、
 * compressionLevel、aesKeyStrength（AES_STRENGTH_128/192/256）、readHiddenFiles、
 * includeRootFolder、rootFolderInZip。password 未显式给 encryptionMethod 时，Java 侧默认 AES。
 */
(function () {
  'use strict';

  if (typeof __ajsRegisterModule !== 'function') {
    throw new Error('[Auto.js] zip 模块必须在 Node24 prelude 之后加载（缺少 __ajsRegisterModule）');
  }

  __ajsRegisterModule('zip', function (api) {
    var nodePath = require('path');
    var nodeFs = require('fs');

    // 延迟解析 Java 类，避免 require('zip') 时在纯 Node / 无宿主环境中就失败。
    var classCache = Object.create(null);

    function findClass(name) {
      if (!Object.prototype.hasOwnProperty.call(classCache, name)) {
        classCache[name] = api.java.findClass(name);
      }
      return classCache[name];
    }

    function zipError(label, error) {
      if (error && error.name === 'AutoJsZipError') {
        return error;
      }
      var message = error && error.message ? String(error.message) : String(error);
      if (message.indexOf('[Auto.js] ') === 0) {
        message = message.substring(10);
      }
      var wrapped = new Error('[Auto.js] zip.' + label + ' 失败：' + message);
      wrapped.name = 'AutoJsZipError';
      wrapped.code = 'ZIP_' + String(label).replace(/[^A-Za-z0-9]+/g, '_').toUpperCase();
      wrapped.cause = error;
      return wrapped;
    }

    function requireObject(value, label) {
      if (value == null) {
        throw new TypeError(label + ' 必须是对象');
      }
      if (typeof value !== 'object' || Array.isArray(value)) {
        throw new TypeError(label + ' 必须是对象');
      }
      return value;
    }

    function resolvePath(value, label) {
      if (typeof value !== 'string' || value.trim() === '') {
        throw new TypeError(label + ' 必须是非空字符串');
      }
      return nodePath.resolve(value);
    }

    /**
     * JS options -> Rhino NativeObject。
     * Java 的 ZipSupport 只读 org.mozilla.javascript.Scriptable，直接传普通 JS 对象会
     * 被 Node24HostBridge.toWire 拒绝（且 Java 侧也读不到属性）。
     */
    function scriptableOptions(options) {
      if (options == null) {
        return null;
      }
      requireObject(options, 'options');
      var NativeObject = findClass('org.mozilla.javascript.NativeObject');
      var ScriptableObject = findClass('org.mozilla.javascript.ScriptableObject');
      var result = new NativeObject();
      var keys = Object.keys(options);
      for (var i = 0; i < keys.length; i++) {
        var key = keys[i];
        var value = options[key];
        if (value === undefined) {
          continue;
        }
        if (value !== null
          && typeof value !== 'string'
          && typeof value !== 'number'
          && typeof value !== 'boolean') {
          throw new TypeError('zip options.' + key + ' 只支持字符串/数字/布尔值');
        }
        // putProperty 是 Rhino 的公开静态入口；避免 NativeObject.put 的重载歧义。
        ScriptableObject.putProperty(result, key, value);
      }
      return result;
    }

    /** JS 文件数组 -> Rhino NativeArray（Zips.zipFiles 的 toFileList 只认 Scriptable）。 */
    function scriptableFileList(list) {
      if (!Array.isArray(list) || list.length === 0) {
        throw new TypeError('fileList 必须是非空字符串数组');
      }
      var files = [];
      for (var i = 0; i < list.length; i++) {
        files.push(resolvePath(list[i], 'fileList[' + i + ']'));
      }
      var NativeArray = findClass('org.mozilla.javascript.NativeArray');
      return new NativeArray(files);
    }

    function javaZips() {
      var Zips = findClass('com.stardust.autojs.runtime.api.Zips');
      // Zips 只保存 Context（zip4j 操作本身不需要调用 Context 方法）。
      return new Zips(api.$autojs.androidContext);
    }

    /** Promise 包装：Java 调用仍是同步的，但错误统一转成 AutoJsZipError。 */
    function run(label, action) {
      return Promise.resolve().then(function () {
        try {
          return action();
        } catch (error) {
          throw zipError(label, error);
        }
      });
    }

    function zipDir(dir, dest, options) {
      return run('zipDir', function () {
        var source = resolvePath(dir, 'zipDir dir');
        var target = resolvePath(dest, 'zipDir dest');
        var value = javaZips().zipDir(source, target, scriptableOptions(options));
        if (value !== true) {
          throw new Error('Java Zips.zipDir 返回了 ' + String(value));
        }
        return true;
      });
    }

    function zipFile(file, dest, options) {
      return run('zipFile', function () {
        var source = resolvePath(file, 'zipFile file');
        var target = resolvePath(dest, 'zipFile dest');
        var value = javaZips().zipFile(source, target, scriptableOptions(options));
        if (value !== true) {
          throw new Error('Java Zips.zipFile 返回了 ' + String(value));
        }
        return true;
      });
    }

    function zipFiles(fileList, dest, options) {
      return run('zipFiles', function () {
        var files = scriptableFileList(fileList);
        var target = resolvePath(dest, 'zipFiles dest');
        var value = javaZips().zipFiles(files, target, scriptableOptions(options));
        if (value !== true) {
          throw new Error('Java Zips.zipFiles 返回了 ' + String(value));
        }
        return true;
      });
    }

    function unzip(file, dest, options) {
      return run('unzip', function () {
        var source = resolvePath(file, 'unzip file');
        var target = resolvePath(dest, 'unzip dest');
        var value = javaZips().unzip(source, target, scriptableOptions(options));
        if (value !== true) {
          throw new Error('Java Zips.unzip 返回了 ' + String(value));
        }
        return true;
      });
    }

    /**
     * 打开压缩包（同步，只构造包装对象；真正的读/写发生在各方法里）。
     * 与 Pro 一样支持 open(file).removeFile(...) / addFile(...) 的同步取对象 + await 方法形式。
     */
    function open(file, options) {
      var target = resolvePath(file, 'open file');
      var openOptions = options == null ? {} : requireObject(options, 'open options');
      var javaZip;
      try {
        javaZip = javaZips().open(target);
      } catch (error) {
        throw zipError('open', error);
      }

      var state = {
        password: openOptions.password == null || String(openOptions.password) === ''
          ? null : String(openOptions.password),
        passwordApplied: false
      };

      /**
       * zip4j 的 ZipFile.setPassword 会打开/校验压缩包；对尚不存在的「新建包」会直接报
       * `zip file does not exist`。因此只在文件已存在时惰性应用会话密码；新建包由
       * addFile/addFiles/addFolder 的 options.password 负责加密。
       */
      function ensureJavaPassword() {
        if (state.password == null || state.passwordApplied) {
          return;
        }
        if (!nodeFs.existsSync(target)) {
          return;
        }
        try {
          javaZip.setPassword(state.password);
          state.passwordApplied = true;
        } catch (error) {
          throw zipError('setPassword', error);
        }
      }

      function optionsFor(method, methodOptions) {
        var merged = {};
        if (methodOptions != null) {
          requireObject(methodOptions, method + ' options');
          var keys = Object.keys(methodOptions);
          for (var i = 0; i < keys.length; i++) {
            merged[keys[i]] = methodOptions[keys[i]];
          }
        }
        if (state.password != null && merged.password == null) {
          merged.password = state.password;
        }
        return scriptableOptions(merged);
      }

      var handle = {
        getPath: function () {
          return target;
        },
        setPassword: function (password) {
          state.password = password == null || String(password) === ''
            ? null : String(password);
          state.passwordApplied = false;
          return handle;
        },
        removeFile: function (name) {
          return run('removeFile', function () {
            if (typeof name !== 'string' || name === '') {
              throw new TypeError('removeFile 需要非空条目名');
            }
            ensureJavaPassword();
            javaZip.removeFile(name);
            return true;
          });
        },
        addFile: function (filePath, methodOptions) {
          return run('addFile', function () {
            var source = resolvePath(filePath, 'addFile file');
            ensureJavaPassword();
            javaZip.addFile(source, optionsFor('addFile', methodOptions));
            return true;
          });
        },
        addFiles: function (fileList, methodOptions) {
          return run('addFiles', function () {
            var files = scriptableFileList(fileList);
            ensureJavaPassword();
            javaZip.addFiles(files, optionsFor('addFiles', methodOptions));
            return true;
          });
        },
        addFolder: function (folder, methodOptions) {
          return run('addFolder', function () {
            var source = resolvePath(folder, 'addFolder folder');
            ensureJavaPassword();
            javaZip.addFolder(source, optionsFor('addFolder', methodOptions));
            return true;
          });
        },
        extractAll: function (dest, methodOptions) {
          return run('extractAll', function () {
            var targetDir = resolvePath(dest, 'extractAll dest');
            ensureJavaPassword();
            javaZip.extractAll(targetDir, optionsFor('extractAll', methodOptions));
            return true;
          });
        },
        extractFile: function (name, dest, methodOptions, newFileName) {
          return run('extractFile', function () {
            if (typeof name !== 'string' || name === '') {
              throw new TypeError('extractFile 需要非空条目名');
            }
            var targetDir = resolvePath(dest, 'extractFile dest');
            var rename = newFileName == null ? null : String(newFileName);
            ensureJavaPassword();
            javaZip.extractFile(name, targetDir, optionsFor('extractFile', methodOptions), rename);
            return true;
          });
        },
        getFileHeaders: function () {
          return run('getFileHeaders', function () {
            ensureJavaPassword();
            return javaZip.getFileHeaders();
          });
        },
        getFileHeader: function (name) {
          return run('getFileHeader', function () {
            if (typeof name !== 'string' || name === '') {
              throw new TypeError('getFileHeader 需要非空条目名');
            }
            ensureJavaPassword();
            return javaZip.getFileHeader(name);
          });
        },
        isValidZipFile: function () {
          return run('isValidZipFile', function () {
            return javaZip.isValidZipFile() === true;
          });
        },
        isEncrypted: function () {
          return run('isEncrypted', function () {
            return javaZip.isEncrypted() === true;
          });
        }
      };

      return handle;
    }

    return {
      zipDir: zipDir,
      zipFile: zipFile,
      zipFiles: zipFiles,
      unzip: unzip,
      open: open
    };
  });
})();
