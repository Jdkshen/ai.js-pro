// SPDX-License-Identifier: MIT
/*
 * Node24 plugins 模块（task-158）：列出/查询/启动已安装插件（xiangqi / apkbuilder 等）。
 * 宿主协议：kind:"plugins"，action = list | isInstalled | info | launch（见 Node24HostBridge.handlePlugins）。
 *
 *   const plugins = require('plugins');
 *   plugins.list()                 -> [{packageName,label,versionName,versionCode,launcher,exportedServices}]
 *   plugins.isInstalled(pkg)       -> boolean
 *   plugins.info(pkg) / load(pkg)  -> 插件信息；未安装抛中文提示
 *   plugins.launch(pkg)            -> 启动插件界面；没有 launcher 的能力插件抛中文提示
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
        return plugins.info(packageName);
      },
      launch: function (packageName) {
        return hostCall('launch', { packageName: requirePackageName(packageName, 'launch') });
      }
    };
    plugins.open = plugins.launch;
    return plugins;
  });
})();
