/*
 * Node24 内置模块：floating_window 高级 API（task-146）。
 *
 * 在当前宿主能能力内对齐 Pro floating_window 的主要 API 面：
 *   createWindow / rawWindow / canDrawOverlays / manageDrawOverlays / FLAG_*
 *   window.setText/setViewFromXml/show/hide/close/setPosition/getPosition/setSize/getSize/on/addFlags/removeFlags
 *
 * 说明：当前 :node24 宿主（kind:ui op:floatingWindow）只支持 show(text,x,y,width,height)/close/closeAll，
 * 没有 update/touch 事件通道。因此：
 * - 位置/尺寸：通过 close+show 重建实现（可用但有闪烁，后续宿主支持 update 后可去掉）；
 * - on('touch'|'drag'|'click') / enableDrag：明确抛错（需要主进程 overlay 触摸事件 -> Java→Node 反向通道）；
 * - addFlags/removeFlags/setFlags 保留 API 形状但当前宿主忽略（不会静默声称生效）；
 * - show({touchable:false}) / setViewFromXml(xml,{touchable:false}) 支持不可触摸绘制型浮窗（默认 true）。
 *
 * 本文件是独立 IIFE，可由 Node24Prelude 在 prelude 之后拼接加载（见同目录 REGISTER-PATCH.md）。
 */
(function () {
  'use strict';
  if (typeof globalThis.__ajsRegisterModule !== 'function') { return; }
  var legacy = (globalThis.__ajsBuiltinModules && globalThis.__ajsBuiltinModules.floating_window) || {};

  // WindowManager.LayoutParams 常量（与 Pro floating_window 导出一致）。
  var FLAG_DIM_BEHIND = 2;
  var FLAG_NOT_FOCUSABLE = 8;
  var FLAG_NOT_TOUCHABLE = 16;
  var FLAG_KEEP_SCREEN_ON = 128;
  var FLAG_LAYOUT_IN_SCREEN = 256;
  var FLAG_LAYOUT_NO_LIMITS = 512;
  var FLAG_FULLSCREEN = 1024;
  var FLAG_SECURE = 8192;
  var FLAG_WATCH_OUTSIDE_TOUCH = 0x40000;

  function apiError(message) {
    var error = new Error('[Auto.js] ' + message);
    error.name = 'AutoJsFloatingWindowError';
    return error;
  }
  function requireInt(value, label, min) {
    if (typeof value !== 'number' || !isFinite(value) || Math.floor(value) !== value) {
      throw new TypeError('[Auto.js] floating_window.' + label + ' 必须是整数，收到：' + String(value));
    }
    if (min != null && value < min) {
      throw new RangeError('[Auto.js] floating_window.' + label + ' 必须 >= ' + min + '，收到：' + value);
    }
    return value;
  }
  function overlayCall(action, fields) {
    var request = { kind: 'ui', op: 'floatingWindow', action: action };
    if (fields) { for (var key in fields) { if (Object.prototype.hasOwnProperty.call(fields, key)) { request[key] = fields[key]; } } }
    var raw;
    try { raw = globalThis.__autojsHostRaw(JSON.stringify(request)); }
    catch (error) { throw apiError('宿主桥不可用：' + ((error && error.message) || error)); }
    var response;
    try { response = JSON.parse(raw); } catch (error) { throw apiError('宿主返回非法 JSON：' + raw); }
    if (!response || response.ok !== true) {
      throw apiError((response && response.error) || ('floating_window ' + action + ' 失败'));
    }
    return response;
  }

  function canDrawOverlays() {
    try {
      var java = globalThis.$autojs && globalThis.$autojs.java;
      if (!java || !java.findClass) { return false; }
      var Settings = java.findClass('android.provider.Settings');
      return !!Settings.canDrawOverlays(globalThis.$autojs.androidContext);
    } catch (error) { return false; }
  }
  function manageDrawOverlays() {
    try {
      var java = globalThis.$autojs && globalThis.$autojs.java;
      var context = globalThis.$autojs && globalThis.$autojs.androidContext;
      if (!java || !context) { return false; }
      var Intent = java.findClass('android.content.Intent');
      var Uri = java.findClass('android.net.Uri');
      var intent = new Intent('android.settings.action.MANAGE_OVERLAY_PERMISSION');
      intent.setData(Uri.parse('package:' + String(context.getPackageName())));
      intent.addFlags(0x10000000); // FLAG_ACTIVITY_NEW_TASK
      context.startActivity(intent);
      return true;
    } catch (error) { return false; }
  }

  function extractTextFromXml(xml) {
    if (typeof xml !== 'string' || xml.trim() === '') { throw new TypeError('[Auto.js] setViewFromXml 需要非空 XML 字符串'); }
    var matches = xml.match(/text\s*=\s*"([^"]*)"/g) || [];
    var texts = [];
    for (var i = 0; i < matches.length; i++) {
      var value = matches[i].replace(/^text\s*=\s*"/, '').replace(/"$/, '');
      if (value) { texts.push(value); }
    }
    if (texts.length === 0) {
      throw apiError('floating_window.setViewFromXml 当前宿主只支持文本内容（未在 XML 中找到 text="..."）；'
        + '完整自定义 View/布局需要主进程 overlay 支持 inflate + 触摸事件');
    }
    return texts.join(' ');
  }

  function FloatingWindowImpl(options) {
    var opts = options || {};
    var position = opts.initialPosition || {};
    var size = opts.initialSize || {};
    this._x = requireInt(position.x == null ? 100 : position.x, 'initialPosition.x', 0);
    this._y = requireInt(position.y == null ? 200 : position.y, 'initialPosition.y', 0);
    this._width = size.width == null ? 0 : requireInt(size.width, 'initialSize.width', 0);
    this._height = size.height == null ? 0 : requireInt(size.height, 'initialSize.height', 0);
    this._text = opts.text == null ? 'Node24' : String(opts.text);
    this._layoutNoLimit = !!opts.layoutNoLimit;
    // task-218：touchable 默认 true；绘制型 overlay 显式 touchable:false 才加 NOT_TOUCHABLE。
    this._touchable = opts.touchable === false ? false : true;
    this._overlayId = null;
    this._visible = false;
    this._closed = false;
    this._flags = 0;
    this._listeners = Object.create(null);
    this._xml = '';
    this._views = Object.create(null);
    this._viewHandlers = Object.create(null);
    this._bindingCache = Object.create(null);
    this._clickNames = Object.create(null);
    this._dragAnchor = 0;
    this._dragOptions = null;
  }
  FloatingWindowImpl.prototype._emit = function (event) {
    var handlers = this._listeners[event];
    if (!handlers) { return; }
    var args = Array.prototype.slice.call(arguments, 1);
    for (var i = 0; i < handlers.length; i++) { try { handlers[i].apply(this, args); } catch (error) { console.error('[floating_window] 监听器异常', error); } }
  };
  FloatingWindowImpl.prototype.on = function (event, handler) {
    if (typeof handler !== 'function') { throw new TypeError('[Auto.js] floating_window.on(event, handler) 的 handler 必须是函数'); }
    var name = String(event || '');
    if (['touch', 'drag', 'click'].indexOf(name) >= 0) {
      if (!this._listeners[name]) { this._listeners[name] = []; }
      this._listeners[name].push(handler);
      if (this._visible) { ensureOverlayPolling(); }
      return this;
    }
    if (['show', 'hide', 'close', 'position', 'size'].indexOf(name) < 0) {
      throw apiError('floating_window.on 支持 show/hide/close/position/size；触摸/拖拽事件需要宿主扩展');
    }
    if (!this._listeners[name]) { this._listeners[name] = []; }
    this._listeners[name].push(handler);
    return this;
  };
  FloatingWindowImpl.prototype.setText = function (text) { this._text = String(text); return this; };
  FloatingWindowImpl.prototype.setTouchable = function (touchable) {
    this._touchable = touchable !== false;
    if (this._visible) { this._reShow(); }
    return this;
  };
  FloatingWindowImpl.prototype.isTouchable = function () { return this._touchable !== false; };
  FloatingWindowImpl.prototype.getText = function () { return this._text; };
  /**
   * task-198：Pro floating_window.setView(View) 兼容。
   * 当前浮窗由主进程 NodeUiBridgeService 按 XML/文本渲染，:node24 进程里的真实 View
   * 不能跨进程挂载；字符串/带 XML 的包装对象走 setViewFromXml，其余明确降级提示。
   */
  FloatingWindowImpl.prototype.setView = function (view) {
    if (typeof view === 'string') { return this.setViewFromXml(view); }
    if (view && typeof view === 'object' && typeof view.xml === 'string') { return this.setViewFromXml(view.xml); }
    if (view && typeof view === 'object' && typeof view.__ajsXml === 'string') { return this.setViewFromXml(view.__ajsXml); }
    // task-199：真实 View（:node24 内 LayoutInflater 结果）无法跨进程挂载；
    // 若刚通过 ui.R.layout.<name> 请求过工程布局，则读 res/layout/<name>.xml 交给主进程按 XML inflate。
    try {
      var layoutName = globalThis.__ajsLastLayoutName;
      var baseDir = globalThis.__ajsProjectDir
        || (globalThis.$autojs && globalThis.$autojs.projectDir) || '';
      if (layoutName && baseDir) {
        var fs = require('fs'), path = require('path');
        var xmlPath = path.join(baseDir, 'res', 'layout', String(layoutName) + '.xml');
        if (fs.existsSync(xmlPath)) {
          var xml = fs.readFileSync(xmlPath, 'utf8');
          this._layoutName = String(layoutName);
          this.setViewFromXml(xml);
          try { console.log('[floating_window] setView(real View) 回退工程布局 XML：' + xmlPath); } catch (ignored) { }
          return this;
        }
      }
    } catch (fallbackError) {
      try { console.debug('[floating_window] setView real View 回退失败：' + fallbackError); } catch (ignored) { }
    }
    throw apiError('floating_window.setView(原生 View) 暂不支持：当前浮窗由主进程按 XML/文本渲染，'
      + ':node24 里的真实 View 无法跨进程挂载，且未找到对应工程 res/layout XML；'
      + '请改用 window.setViewFromXml(xml)，或把该界面放到 ui.Activity / NodeUiActivity 中显示');
  };
  FloatingWindowImpl.prototype.setViewFromXml = function (xml, options) {
    if (typeof xml !== 'string' || xml.trim() === '') { throw new TypeError('[Auto.js] setViewFromXml 需要非空 XML 字符串'); }
    this._xml = String(xml);
    if (options && typeof options.touchable === 'boolean') { this._touchable = options.touchable; }
    try { this._text = extractTextFromXml(xml); } catch (error) { this._text = 'Node24'; }
    return this;
  };
  FloatingWindowImpl.prototype._showNow = function () {
    if (this._closed) { throw apiError('floating_window 已关闭，不能再次 show'); }
    var fields = { text: this._text, x: this._x, y: this._y, flags: this._flags || 0,
      touchable: this._touchable !== false };
    if (this._xml) { fields.xml = this._xml; }
    if (this._width > 0) { fields.width = this._width; }
    if (this._height > 0) { fields.height = this._height; }
    var showResult;
    try {
      showResult = overlayCall('show', fields);
    } catch (showError) {
      var message = String((showError && showError.message) || showError);
      if (message.indexOf('超时') >= 0) {
        // task-200：show 已成功创建窗口但回复丢失时，用 info 找回结果，避免整段 start 失败。
        try {
          showResult = overlayCall('info', {});
          try { console.log('[floating_window] show reply lost, recovered via info id=' + showResult.overlayId); } catch (ignored) { }
        } catch (infoError) {
          throw showError;
        }
      } else {
        throw showError;
      }
    }
    this._overlayId = String(showResult.overlayId || '');
    this._views = Object.create(null);
    var rawViews = showResult.views && typeof showResult.views === 'object' ? showResult.views : {};
    for (var viewName in rawViews) { if (Object.prototype.hasOwnProperty.call(rawViews, viewName)) { this._views[viewName] = Number(rawViews[viewName]); } }
    Object.keys(this._clickNames).forEach(function (name) {
      var id = this._views[name];
      if (id) {
        try { overlayCall('viewCommand', { overlayId: this._overlayId, viewId: id, command: 'setClickable', value: true }); } catch (ignored) { }
      }
    }, this);
    if (this._pendingDragName) {
      var pendingId = this._views[this._pendingDragName] || 0;
      try { console.log('[floating_window] enableDrag pending name=' + this._pendingDragName + ' viewId=' + pendingId); } catch (ignored) { }
      if (pendingId) {
        this._dragAnchor = pendingId;
        this._pendingDragName = null;
        overlayCall('enableDrag', { overlayId: this._overlayId, viewId: pendingId });
      }
    }
    activeWindows.add(this);
    ensureOverlayPolling();
    this._visible = true;
    return this;
  };
  FloatingWindowImpl.prototype.show = function (options) {
    if (options && typeof options.touchable === 'boolean') { this._touchable = options.touchable; }
    if (this._visible) { return this; }
    this._showNow();
    this._emit('show', this._overlayId);
    return this;
  };
  FloatingWindowImpl.prototype._hideNow = function (emitEvent) {
    if (!this._visible) { return this; }
    try {
      // task-194：关闭是幂等操作；宿主已移除/窗口已关闭时不能抛异常，
      // 否则示例的 close 回调会在 process.exit() 前抛错，被报成 SCRIPT_ERROR。
      overlayCall('close', { overlayId: this._overlayId });
    } catch (closeError) {
      if (typeof console !== 'undefined' && console.debug) {
        console.debug('[floating_window] close ignored: ' + ((closeError && closeError.message) || closeError));
      }
    }
    this._overlayId = null;
    this._visible = false;
    if (emitEvent) { this._emit('hide'); }
    activeWindows.delete(this);
    if (activeWindows.size === 0) { stopOverlayPolling(); }
    return this;
  };
  FloatingWindowImpl.prototype.hide = function () { return this._hideNow(true); };
  FloatingWindowImpl.prototype.close = function () {
    if (this._closed) { return true; }
    this._hideNow(false);
    this._closed = true;
    this._emit('close');
    return true;
  };
  FloatingWindowImpl.prototype._reShow = function () {
    if (!this._visible) { return; }
    this._hideNow(false);
    this._showNow();
  };
  FloatingWindowImpl.prototype.setPosition = function (x, y) {
    this._x = requireInt(x, 'x', 0);
    this._y = requireInt(y, 'y', 0);
    this._reShow();
    this._emit('position', { x: this._x, y: this._y });
    return this;
  };
  FloatingWindowImpl.prototype.moveTo = FloatingWindowImpl.prototype.setPosition;
  FloatingWindowImpl.prototype.getPosition = function () { return { x: this._x, y: this._y }; };
  FloatingWindowImpl.prototype.setSize = function (width, height) {
    this._width = requireInt(width, 'width', 0);
    this._height = requireInt(height, 'height', 0);
    this._reShow();
    this._emit('size', { width: this._width, height: this._height });
    return this;
  };
  FloatingWindowImpl.prototype.getSize = function () { return { width: this._width, height: this._height }; };
  FloatingWindowImpl.prototype.isShowing = function () { return this._visible; };
  function wrapTouchEvent(event) {
    var actionMap = { down: 0, up: 1, move: 2, cancel: 3, outside: 4 };
    return {
      __ajsTouch: true,
      overlayId: event.overlayId,
      getAction: function () { return actionMap[String(event.action)] != null ? actionMap[String(event.action)] : -1; },
      getRawX: function () { return Number(event.x || 0); },
      getRawY: function () { return Number(event.y || 0); },
      getX: function () { return Number(event.x || 0); },
      getY: function () { return Number(event.y || 0); },
      getEventTime: function () { return Number(event.time || Date.now()); },
      toString: function () { return '[Node24Touch ' + event.action + ']'; }
    };
  }

  function overlayViewCommand(window, name, command, value) {
    var viewId = window._views[name] || 0;
    if (!viewId) { throw apiError('浮窗里没有 id="' + name + '" 的控件'); }
    var fields = { overlayId: window._overlayId, viewId: viewId, command: command };
    if (value !== undefined) { fields.value = value; }
    var response = overlayCall('viewCommand', fields);
    return response ? response.value : undefined;
  }

  function bindingFor(window, name) {
    if (window._bindingCache[name]) { return window._bindingCache[name]; }
    var binding = {
      __name: name,
      on: function (eventName, handler) {
        if (String(eventName) !== 'click') { throw apiError('浮窗控件目前只支持 click 事件'); }
        if (typeof handler !== 'function') { throw new TypeError('click 监听需要一个函数'); }
        window._viewHandlers[name] = window._viewHandlers[name] || [];
        window._viewHandlers[name].push(handler);
        window._clickNames[name] = true;
        // task-164：<img> 等非 clickable 控件，绑定了 click 后要让宿主的 hit-test 能命中。
        if (window._overlayId && window._views[name]) {
          try { overlayCall('viewCommand', { overlayId: window._overlayId, viewId: window._views[name], command: 'setClickable', value: true }); } catch (ignored) { }
        }
        return binding;
      },
      setOnClickListener: function (handler) { return binding.on('click', handler); },
      click: function () { overlayViewCommand(window, name, 'click'); return binding; },
      setText: function (text) { overlayViewCommand(window, name, 'setText', String(text)); return binding; },
      getText: function () { return String(overlayViewCommand(window, name, 'getText') || ''); },
      setVisibility: function (value) { overlayViewCommand(window, name, 'setVisibility', Number(value)); return binding; },
      setEnabled: function (value) { overlayViewCommand(window, name, 'setEnabled', !!value); return binding; },
      setChecked: function (value) { overlayViewCommand(window, name, 'setChecked', !!value); return binding; },
      getChecked: function () { return !!overlayViewCommand(window, name, 'getChecked'); },
      getViewId: function () { return window._views[name] || 0; },
      toString: function () { return '[Node24OverlayView ' + name + ']'; }
    };
    window._bindingCache[name] = binding;
    return binding;
  }

  Object.defineProperty(FloatingWindowImpl.prototype, 'view', {
    get: function () {
      var window = this;
      if (!this._bindingView) {
        this._bindingView = {
          binding: new Proxy({}, {
            get: function (_target, prop) {
              if (typeof prop === 'symbol' || prop === 'then') { return undefined; }
              return bindingFor(window, String(prop));
            }
          }),
          // task-199：工程会直接对根 View 调这些方法；overlay 主进程渲染时给安全兼容。
          // 注意：start 阶段工程会把“前台时隐藏浮窗”落实为 setVisibility(8)；若立即 close
          // 会与刚完成的 show 竞态（主进程 overlay 命令可能 15s 超时），这里先记录状态不立即 close，
          // 浮窗保持可见（后续可见性由 hide/show 显式调用控制）。
          setVisibility: function (value) {
            // task-199：start 阶段工程会调 setVisibility(8) 隐藏根视图；此时窗口刚 show 完，
            // 不能再触发 show/hide（会与主进程 overlay 命令竞态 15s 超时）。overlay 可见性
            // 由显式 window.show()/hide() 控制，这里只记录，不重入。
            window._rootVisibility = Number(value);
            return window._bindingView;
          },
          getWidth: function () { return Number(window._lastWidth) || 0; },
          getHeight: function () { return Number(window._lastHeight) || 0; },
          getLocationOnScreen: function (intArray) {
            // 主进程 overlay 没有真实 View 句柄；保持数组原值，不抛错（几何由 NodeUiBridge 的窗口坐标为准）。
            return undefined;
          },
          on: function (eventName, handler) { return window.on(eventName, handler); },
          toString: function () { return '[Node24OverlayRoot]'; }
        };
      }
      return this._bindingView;
    }
  });

  FloatingWindowImpl.prototype.enableDrag = function (viewOrName, options) {
    var name = typeof viewOrName === 'string' ? viewOrName : (viewOrName && viewOrName.__name);
    if (!name) { throw new TypeError('[Auto.js] enableDrag(view, options) 需要浮窗控件或 id 名'); }
    var viewId = this._views[name] || 0;
    this._dragOptions = options || {};
    if (!viewId) {
      // sample 会在 show() 之前调用 enableDrag：等 show 拿到 views 后再补发。
      this._pendingDragName = name;
      return this;
    }
    this._dragAnchor = viewId;
    this._pendingDragName = null;
    if (this._overlayId) { overlayCall('enableDrag', { overlayId: this._overlayId, viewId: viewId }); }
    return this;
  };
  FloatingWindowImpl.prototype.addFlags = function (flags) {
    this._flags |= requireInt(flags, 'flags', 0);
    this._applyFlags('addFlags', requireInt(flags, 'flags', 0));
    return this;
  };
  FloatingWindowImpl.prototype.removeFlags = function (flags) {
    this._flags &= ~requireInt(flags, 'flags', 0);
    this._applyFlags('removeFlags', requireInt(flags, 'flags', 0));
    return this;
  };
  FloatingWindowImpl.prototype.setFlags = function (flags) {
    this._flags = requireInt(flags, 'flags', 0);
    this._applyFlags('setFlags', this._flags);
    return this;
  };
  FloatingWindowImpl.prototype._applyFlags = function (action, flags) {
    if (!this._overlayId) { return; }
    try { overlayCall(action, { overlayId: this._overlayId, flags: flags }); } catch (ignored) { }
  };
  FloatingWindowImpl.prototype.getFlags = function () { return this._flags; };

  // task-157：触摸/点击/拖拽事件轮询（overlay 主进程队列 -> JS handler）。
  var activeWindows = new Set();
  var overlayPollTimer = null;
  function stopOverlayPolling() {
    if (overlayPollTimer != null) {
      clearInterval(overlayPollTimer);
      overlayPollTimer = null;
    }
  }
  function ensureOverlayPolling() {
    if (overlayPollTimer != null) { return; }
    overlayPollTimer = setInterval(function () {
      // task-195：关闭/宿主失效瞬间 polling 可能抛错；整个 tick 兜住，
      // 不能让未处理异常改变 Node 退出码。
      try {
        if (activeWindows.size === 0) { stopOverlayPolling(); return; }
        activeWindows.forEach(function (window) {
          if (!window._visible || !window._overlayId || window._closed) { return; }
          var response;
          try { response = overlayCall('pollEvents', { overlayId: window._overlayId }); } catch (error) { return; }
          var events = response && response.events ? response.events : [];
          for (var i = 0; i < events.length; i++) {
            if (events[i] && events[i].type === 'click') {
              try { console.log('[floating_window] click event viewId=' + events[i].viewId); } catch (ignored) { }
            }
            try { dispatchOverlayEvent(window, events[i]); }
            catch (dispatchError) {
              try {
                console.error('[floating_window] 事件分发异常：'
                  + ((dispatchError && dispatchError.message) || dispatchError));
              } catch (ignored) { }
            }
          }
        });
      } catch (tickError) {
        try {
          console.error('[floating_window] polling 异常（已忽略）：'
            + ((tickError && tickError.message) || tickError));
        } catch (ignored) { }
      }
    }, 100);
    if (overlayPollTimer.unref) { overlayPollTimer.unref(); }
  }
  // 允许 prelude 的 process.exit 包装在退出前停止轮询。
  globalThis.__ajsStopOverlayPolling = stopOverlayPolling;
  function dispatchOverlayEvent(window, event) {
    if (!event || !event.type) { return; }
    if (event.type === 'touch') {
      var touch = wrapTouchEvent(event);
      window._emit('touch', touch);
      return;
    }
    if (event.type === 'drag') {
      window._x = Number(event.x || window._x);
      window._y = Number(event.y || window._y);
      window._emit('drag', { x: window._x, y: window._y });
      return;
    }
    if (event.type === 'click') {
      var viewId = Number(event.viewId || 0);
      Object.keys(window._viewHandlers).forEach(function (name) {
        if (Number(window._views[name] || 0) !== viewId) { return; }
        window._viewHandlers[name].slice().forEach(function (handler) {
          try { handler({ x: event.x, y: event.y, viewId: viewId }); } catch (error) { console.error('[floating_window] click handler 异常：', error); }
        });
      });
      if (window._dragOptions && window._dragOptions.onClick && Number(window._dragAnchor) === viewId) {
        try { window._dragOptions.onClick({ x: event.x, y: event.y }); } catch (error) { console.error('[floating_window] drag onClick 异常：', error); }
      }
      window._emit('click', { x: event.x, y: event.y, viewId: viewId });
    }
  }

  function createWindow(options) {
    if (options != null && (typeof options !== 'object' || Array.isArray(options))) {
      throw new TypeError('[Auto.js] floating_window.createWindow(options) 的 options 必须是对象');
    }
    return new FloatingWindowImpl(options || {});
  }
  function rawWindow(options) {
    if (typeof options === 'string') { return createWindow({ text: options }); }
    return createWindow(options || {});
  }
  function closeAll() {
    var result = legacy.closeAll ? legacy.closeAll() : overlayCall('closeAll', {});
    return result;
  }

  var module = Object.assign({}, legacy, {
    createWindow: createWindow,
    rawWindow: rawWindow,
    FloatingWindow: FloatingWindowImpl,
    closeAll: closeAll,
    canDrawOverlays: canDrawOverlays,
    manageDrawOverlays: manageDrawOverlays,
    FLAG_DIM_BEHIND: FLAG_DIM_BEHIND,
    FLAG_NOT_FOCUSABLE: FLAG_NOT_FOCUSABLE,
    FLAG_NOT_TOUCHABLE: FLAG_NOT_TOUCHABLE,
    FLAG_KEEP_SCREEN_ON: FLAG_KEEP_SCREEN_ON,
    FLAG_LAYOUT_IN_SCREEN: FLAG_LAYOUT_IN_SCREEN,
    FLAG_LAYOUT_NO_LIMITS: FLAG_LAYOUT_NO_LIMITS,
    FLAG_FULLSCREEN: FLAG_FULLSCREEN,
    FLAG_SECURE: FLAG_SECURE,
    FLAG_WATCH_OUTSIDE_TOUCH: FLAG_WATCH_OUTSIDE_TOUCH
  });
  globalThis.__ajsRegisterModule('floating_window', function () { return module; });
  if (globalThis.__ajsBuiltinModules) { globalThis.__ajsBuiltinModules.floating_window = module; }
  if (globalThis.$autojs) { globalThis.$autojs.floating_window = module; }
})();
