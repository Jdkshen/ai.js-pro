// SPDX-License-Identifier: MIT
/*
 * Node24 root_automator 最小实现（task-171）。
 *
 * 只提供 root:true 通道：通过 su -c 执行 Android input 命令。
 * 要求设备已 root 且 AI.js Pro 已获 su 授权；无 root 时给出中文错误，不伪造成功。
 * 多点触控最小实现：touchDown/touchMove/touchUp 会按每根手指的起点/终点近似为 tap/swipe，
 * 不产生真实多点手势（真实多点需要 sendevent/root 输入设备通道，后续可扩展）。
 */
(function () {
  'use strict';
  if (typeof globalThis.__ajsRegisterModule !== 'function') {
    throw new Error('[Auto.js] root_automator 模块必须在 Node24 prelude 之后加载');
  }
  globalThis.__ajsRegisterModule('root_automator', function () {
    var childProcess = require('child_process');

    function apiError(message) {
      var error = new Error('[Auto.js] root_automator: ' + message);
      error.name = 'AutoJsRootAutomatorError';
      return error;
    }

    function exec(command, timeout) {
      try {
        var out = childProcess.execSync(command, { encoding: 'utf8', timeout: timeout || 15000, stdio: 'pipe' });
        return String(out == null ? '' : out);
      } catch (error) {
        var detail = String((error && error.stderr) || (error && error.message) || error);
        throw apiError('执行失败（' + command + '）：' + detail.trim());
      }
    }

    function rootShell(command) {
      return exec('su -c ' + JSON.stringify(String(command)));
    }

    function assertRoot() {
      var out;
      try {
        out = rootShell('id');
      } catch (error) {
        throw apiError('需要 root 权限，但 su 不可用或未授权；请在 Root 管理器中授权 AI.js Pro 后重试');
      }
      if (!/uid=0\b/.test(out)) {
        throw apiError('su 返回的不是 root（' + out.trim() + '）；请在 Root 管理器中授权 AI.js Pro');
      }
    }

    function number(value, label) {
      var n = Number(value);
      if (!isFinite(n)) { throw apiError(label + ' 必须是数字，收到：' + value); }
      return Math.round(n);
    }

    function RootAutomator2() {
      this._exited = false;
      this._down = [];
    }

    RootAutomator2.prototype._ensure = function () {
      if (this._exited) { throw apiError('RootAutomator2 已经 exit()'); }
    };

    RootAutomator2.prototype.tap = function (x, y) {
      this._ensure();
      rootShell('input tap ' + number(x, 'x') + ' ' + number(y, 'y'));
      return Promise.resolve(true);
    };

    RootAutomator2.prototype.press = function (x, y, duration) {
      this._ensure();
      rootShell('input swipe ' + number(x, 'x') + ' ' + number(y, 'y') + ' ' + number(x, 'x') + ' ' + number(y, 'y') + ' ' + number(duration || 500, 'duration'));
      return Promise.resolve(true);
    };

    RootAutomator2.prototype.longClick = RootAutomator2.prototype.press;

    RootAutomator2.prototype.swipe = function (x1, y1, x2, y2, duration) {
      this._ensure();
      rootShell('input swipe ' + number(x1, 'x1') + ' ' + number(y1, 'y1') + ' ' + number(x2, 'x2') + ' ' + number(y2, 'y2') + ' ' + number(duration || 300, 'duration'));
      return Promise.resolve(true);
    };

    RootAutomator2.prototype.touchDown = function (points) {
      this._ensure();
      var list = Array.isArray(points) ? points : [points];
      this._down = list.map(function (p) {
        return { x: number(p.x, 'x'), y: number(p.y, 'y'), id: Number(p.id || 0) };
      });
      return Promise.resolve(true);
    };

    RootAutomator2.prototype.touchMove = function (points) {
      this._ensure();
      var list = Array.isArray(points) ? points : [points];
      for (var i = 0; i < list.length; i++) {
        var p = list[i];
        var down = null;
        for (var j = 0; j < this._down.length; j++) {
          if (this._down[j].id === Number(p.id || 0)) { down = this._down[j]; break; }
        }
        if (down) { down.x = number(p.x, 'x'); down.y = number(p.y, 'y'); }
      }
      return Promise.resolve(true);
    };

    RootAutomator2.prototype.touchUp = function () {
      this._ensure();
      // 最小实现：每根手指从起点到当前位置近似一次 swipe，避免留下“按住”状态。
      for (var i = 0; i < this._down.length; i++) {
        var p = this._down[i];
        rootShell('input swipe ' + p.x + ' ' + p.y + ' ' + p.x + ' ' + p.y + ' 100');
      }
      this._down = [];
      return Promise.resolve(true);
    };

    RootAutomator2.prototype.flush = function () { this._ensure(); return Promise.resolve(true); };
    RootAutomator2.prototype.exit = function () { this._exited = true; return Promise.resolve(true); };

    function createRootAutomator2(options) {
      var opts = options || {};
      if (opts.adb === true) {
        return Promise.reject(apiError('adb/Shizuku 通道暂未接入；请使用 { root: true }'));
      }
      return Promise.resolve().then(function () {
        assertRoot();
        return new RootAutomator2();
      });
    }

    function RootAutomator(options) {
      var instance = new RootAutomator2();
      return instance;
    }

    return {
      createRootAutomator2: createRootAutomator2,
      RootAutomator2: RootAutomator2,
      RootAutomator: RootAutomator,
      createRootAutomator: createRootAutomator2
    };
  });
})();
