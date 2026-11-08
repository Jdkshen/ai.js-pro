/**
 *  Copyright 2014-2016 Red Hat, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License")
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
// Since we intend to use the Function constructor.
/* jshint evil: true */

module = (typeof module === 'undefined') ? {} : module;

(function () {
  var builtInModules = ["lodash.js"];

  var System = java.lang.System;
  var Scanner = java.util.Scanner;
  var File = java.io.File;

  NativeRequire = (typeof NativeRequire === 'undefined') ? {} : NativeRequire;
  if (typeof require === 'function' && !NativeRequire.require) {
    NativeRequire.require = require;
  }

  /** 目录内容速览：给「找不到模块」的报错用（最多 8 个 .js/.json，避免刷屏）。 */
  function briefDirListing(dir) {
    try {
      var names = files.listDir(dir) || [];
      var picked = [];
      for (var i = 0; i < names.length && picked.length < 8; i++) {
        var name = String(names[i]);
        if (name.charAt(0) === '.') continue;   // 跳过 .mcp-*/.aijs-* 等内部文件
        if (/\.(js|json)$/.test(name)) picked.push(name);
      }
      if (!picked.length) return '(没有 .js/.json 文件)';
      return picked.join(', ') + (names.length > picked.length ? ', …共 ' + names.length + ' 项' : '');
    } catch (error) {
      return '(无法列出该目录：' + error + ')';
    }
  }

  function Module(id, parent, core) {
    this.id = id;
    this.core = core;
    this.parent = parent;
    this.children = [];
    this.filename = id;
    this.loaded = false;

    Object.defineProperty(this, 'exports', {
      get: function () {
        return this._exports;
      }.bind(this),
      set: function (val) {
        Require.cache[this.filename] = val;
        this._exports = val;
      }.bind(this)
    });
    this.exports = {};

    if (parent && parent.children) parent.children.push(this);

    this.require = function (id) {
      return Require(id, this);
    }.bind(this);
  }

  Module._load = function _load(file, parent, core, main) {
    return NativeRequire.require(file);
  };

  Module.runMain = function runMain(main) {
    var file = Require.resolve(main);
    Module._load(file, undefined, false, true);
  };

  function Require(id, parent) {
    if (global.__aiModernModules && Object.prototype.hasOwnProperty.call(global.__aiModernModules, id)) {
      return global.__aiModernModules[id];
    }
    var normalizePath = normalizeName(id);
    if (builtInModules.indexOf(normalizePath) >= 0 && !files.exists(normalizePath)) {
      return NativeRequire.require(normalizePath);
    }
    if (id === "events") {
      return events;
    }
    if (id.startsWith("http://") || id.startsWith("https://")) {
      return NativeRequire.require(id);
    }

    var core;
    var native_;
    var file = Require.resolve(id, parent);

    if (!file) {
      // `.4` 的 Nycspro 场景：相对依赖缺失时旧实现直接回落 NativeRequire.require(id)，
      // 抛出的是 Rhino 那句「Can't resolve relative module ID … outside of a module」，
      // 完全看不出「在哪个目录找、那个目录里有什么」。这里先给出可执行的诊断。（自更新类脚本
      // 常把依赖下载到运行目录，缺文件时最需要看到的就是这两个信息。）
      try {
        if (typeof id === 'string' && (id.indexOf('./') === 0 || id.indexOf('../') === 0)) {
          var diagRoot = (typeof Require !== 'undefined' && Require.root) ? Require.root : files.cwd();
          var diagTarget = String(files.join(diagRoot, id)).replace('/./', '/');
          if (!files.exists(diagTarget)) {
            throw new ModuleError('找不到模块 "' + id + '"：相对 ' + diagRoot + ' 解析为 ' + diagTarget
                + '，该文件不存在。该目录下现有：' + briefDirListing(diagRoot)
                + '。若这是「云端下载 / 自更新」类脚本，请确认依赖已下载成功，且下载与运行使用同一个工作目录。',
                'MODULE_NOT_FOUND');
          }
        }
      } catch (diagError) {
        if (diagError instanceof ModuleError) throw diagError;
      }
      if (typeof NativeRequire.require === 'function') {
        if (Require.debug) {
          System.out.println(['Cannot resolve', id, 'defaulting to native'].join(' '));
        }
        native_ = NativeRequire.require(id);
        if (native_) return native_;
      }
      System.err.println('Cannot find module ' + id);
      throw new ModuleError('Cannot find module ' + id, 'MODULE_NOT_FOUND');
    }

    if (file.core) {
      file = file.path;
      core = true;
    }
    if (Require.cache[file]) {
      return Require.cache[file];
    } else if (file.endsWith('.js')) {
      return Module._load(file, parent, core);
    } else if (file.endsWith('.json')) {
      return loadJSON(file);
    }
  }

  Require.resolve = function (id, parent) {
    var roots = findRoots(parent);
    for (var i = 0; i < roots.length; ++i) {
      var root = roots[i];
      var result = resolveCoreModule(id, root) ||
        resolveAsFile(id, root, '.js') ||
        resolveAsFile(id, root, '.json') ||
        resolveAsDirectory(id, root) ||
        resolveAsNodeModule(id, root);
      if (result) {
        return result;
      }
    }
    return false;
  };

  Require.root = files.cwd();//System.getProperty('user.dir');
  Require.NODE_PATH = undefined;

  function findRoots(parent) {
    var r = [];
    r.push(findRoot(parent));
    return r.concat(Require.paths());
  }

  function parsePaths(paths) {
    if (!paths) {
      return [];
    }
    if (paths === '') {
      return [];
    }
    var osName = java.lang.System.getProperty('os.name').toLowerCase();
    var separator;

    if (osName.indexOf('win') >= 0) {
      separator = ';';
    } else {
      separator = ':';
    }

    return paths.split(separator);
  }

  Require.paths = function () {
    var r = [];
    r.push(java.lang.System.getProperty('user.home') + '/.node_modules');
    r.push(java.lang.System.getProperty('user.home') + '/.node_libraries');

    if (Require.NODE_PATH) {
      r = r.concat(parsePaths(Require.NODE_PATH));
    } else {
      var NODE_PATH = java.lang.System.getenv().NODE_PATH;
      if (NODE_PATH) {
        r = r.concat(parsePaths(NODE_PATH));
      }
    }
    // r.push( $PREFIX + "/node/library" )
    return r;
  };

  function findRoot(parent) {
    if (!parent || !parent.id) { return Require.root; }
    var pathParts = parent.id.split(/[\/|\\,]+/g);
    pathParts.pop();
    return pathParts.join('/');
  }

  Require.debug = true;
  Require.cache = {};
  Require.extensions = {};
  require = Require;

  Module.require = require;
  module.exports = Module;

  function loadJSON(file) {
    var json = JSON.parse(readFile(file));
    Require.cache[file] = json;
    return json;
  }

  function resolveAsNodeModule(id, root) {
    var base = [root, 'node_modules'].join('/');
    return resolveAsFile(id, base) ||
      resolveAsDirectory(id, base) ||
      (root ? resolveAsNodeModule(id, new File(root).getParent()) : false);
  }

  function resolveAsDirectory(id, root) {
    var base = [root, id].join('/');
    var file = new File([base, 'package.json'].join('/'));
    if (file.exists()) {
      try {
        var body = readFile(file.getCanonicalPath());
        var package_ = JSON.parse(body);
        if (package_.main) {
          return (resolveAsFile(package_.main, base) ||
            resolveAsDirectory(package_.main, base));
        }
        // if no package.main exists, look for index.js
        return resolveAsFile('index.js', base);
      } catch (ex) {
        throw new ModuleError('Cannot load JSON file', 'PARSE_ERROR', ex);
      }
    }
    return resolveAsFile('index.js', base);
  }

  function resolveAsFile(id, root, ext) {
    var file;
    if (id.length > 0 && id[0] === '/') {
      file = new File(normalizeName(id, ext));
      if (!file.exists()) {
        return resolveAsDirectory(id);
      }
    } else {
      file = new File([root, normalizeName(id, ext)].join('/'));
    }
    if (file.exists()) {
      return file.getCanonicalPath();
    }
  }

  function resolveCoreModule(id, root) {
    var name = normalizeName(id);
    var classloader = java.lang.Thread.currentThread().getContextClassLoader();
    if (classloader.getResource(name)) {
      return { path: name, core: true };
    }
  }

  function normalizeName(fileName, ext) {
    if (fileName.endsWith('.json')) {
      return fileName;
    }
    var extension = ext || '.js';
    if (fileName.endsWith(extension)) {
      return fileName;
    }
    return fileName + extension;
  }

  function readFile(filename, core) {
    var input;
    try {
      if (core) {
        var classloader = java.lang.Thread.currentThread().getContextClassLoader();
        input = classloader.getResourceAsStream(filename);
      } else {
        input = new File(filename);
      }
      // TODO: I think this is not very efficient
      return new Scanner(input).useDelimiter('\\A').next();
    } catch (e) {
      throw new ModuleError('Cannot read file [' + input + ']: ', 'IO_ERROR', e);
    }
  }

  function ModuleError(message, code, cause) {
    this.code = code || 'UNDEFINED';
    this.message = message || 'Error loading module';
    this.cause = cause;
  }

  // Helper function until ECMAScript 6 is complete
  if (typeof String.prototype.endsWith !== 'function') {
    String.prototype.endsWith = function (suffix) {
      if (!suffix) return false;
      return this.indexOf(suffix, this.length - suffix.length) !== -1;
    };
  }

  ModuleError.prototype = new Error();
  ModuleError.prototype.constructor = ModuleError;
}());
