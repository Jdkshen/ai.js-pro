(function (root) {
    'use strict';
    function install(ui, scope) {
        var bridge = null, timer = null, nextKey = 1, entries = [], callbacks = {};
        function support() {
            if (!bridge) {
                bridge = new scope.Packages.com.stardust.autojs.core.ui.AccessibilityClickSupport();
                scope.events.on('exit', function () {
                    if (timer !== null) scope.clearInterval(timer);
                    callbacks = {}; entries = [];
                    bridge.close();
                });
            }
            return bridge;
        }
        function nativeView(view) {
            if (typeof view === 'string') view = ui.findView(view);
            if (!view) throw new TypeError('view 必须是已有的 UI 或悬浮窗控件');
            if (view.__floatyView === true) return view.javaView;
            // QuickJS ui 控件是代理；从原生根视图按 Android id 找回实际 View。
            if (typeof view.__id === 'string') {
                var actual = view.getRootView().findViewById(view.getId());
                if (!actual) throw new TypeError('找不到原生控件：' + view.__id);
                return actual;
            }
            if (typeof view.getClass !== 'function') throw new TypeError('view 必须是 Android View');
            return view;
        }
        function actionId(action) {
            if (action === 'click' || action === 'ACTION_CLICK') return 16;
            if (action === 'long_click' || action === 'longClick' || action === 'ACTION_LONG_CLICK') return 32;
            if (action && typeof action.getId === 'function') action = Number(action.getId());
            if (action !== 16 && action !== 32) throw new TypeError('action 只支持 click/long_click 或 16/32');
            return action;
        }
        function dispatch() {
            for (var i = 0; i < 64; i++) {
                var key = Number(bridge.poll());
                if (!key) return;
                var entry = callbacks[key];
                if (entry) entry.callback(entry.view, { action:entry.action, label:entry.label });
            }
        }
        ui.replaceAccessibilityAction = function (view, action, label, callback) {
            if (typeof view === 'string') view = ui.findView(view);
            var native = nativeView(view), id = actionId(action);
            if (callback != null && typeof callback !== 'function') throw new TypeError('callback 必须是函数或 null');
            if (label != null && typeof label !== 'string') throw new TypeError('label 必须是字符串或 null');
            var previous = -1;
            for (var i = 0; i < entries.length; i++) {
                if (entries[i].action === id && native.equals(entries[i].native)) { previous = i; break; }
            }
            var entry = callback == null ? null : { native:native, view:view, action:id, label:label, callback:callback, key:nextKey++ };
            support().replaceAccessibilityAction(native, id, label == null ? null : label, entry ? entry.key : 0);
            if (previous >= 0) { delete callbacks[entries[previous].key]; entries.splice(previous, 1); }
            if (entry) {
                entries.push(entry); callbacks[entry.key] = entry;
                if (timer === null) timer = scope.setInterval(dispatch, 40);
            } else if (!entries.length && timer !== null) {
                scope.clearInterval(timer); timer = null;
            }
            return view;
        };
        ui.performClick = function (view) { return !!support().performClick(nativeView(view)); };
        return ui;
    }
    /**
     * task-248：QuickJS 公开 ui.inflate(xml, parent, attachToParent)。
     *
     * QuickJS 的 ui.layout/findView/ui.run 由 native bootstrap 提供，之前没有 ui.inflate；
     * 这里通过 QuickJsHostBridge.forEngine(id)（与 QuickJsApiExtensions 同一 Java.type 模式）
     * 调到宿主 inflater，真正创建 View，语义与 Rhino 的 __ui__.js / Node 的 ui.inflate 对齐：
     *   - xml 支持字符串，以及带 toXMLString() 的 XML 对象；
     *   - parent 支持 ui.<id> 控件代理（取 __id 交给宿主解析）、Java View/ViewGroup 或 null；
     *   - attachToParent 为真且 parent 是 ViewGroup 时才 addView，默认 false。
     * 只在 QuickJS 引导路径安装：Rhino 侧 __ui__.js 已有同名实现，不能覆盖。
     */
    function installInflate(ui, scope) {
        function hostBridge() {
            var Java = scope.Java;
            if (!Java || typeof Java.type !== 'function') {
                throw new Error('ui.inflate 需要 QuickJS Java 桥（当前宿主不可用）');
            }
            var id = 0;
            try {
                id = Number(scope.engines.myEngine().id);
            } catch (ignored) {
            }
            return Java.type('com.stardust.autojs.engine.QuickJsHostBridge').forEngine(id);
        }
        ui.inflate = function (xml, parent, attachToParent) {
            if (xml === null || xml === undefined) {
                throw new TypeError('ui.inflate 需要 XML 布局字符串');
            }
            if (typeof xml === 'object' && typeof xml.toXMLString === 'function') {
                xml = xml.toXMLString();
            }
            if (typeof xml !== 'string') {
                throw new TypeError('ui.inflate 第一个参数必须是 XML 字符串或 XML 对象');
            }
            if (xml.replace(/\s/g, '').length === 0) {
                throw new TypeError('ui.inflate 的 XML 布局不能为空');
            }
            var hostParent = null;
            if (parent !== null && parent !== undefined) {
                if (typeof parent === 'string') {
                    hostParent = parent;
                } else if (typeof parent.__id === 'string') {
                    hostParent = parent.__id;   // QuickJS 控件代理：宿主按 id 找回原生 View
                } else if (parent.__floatyView === true && parent.javaView) {
                    hostParent = parent.javaView;
                } else {
                    hostParent = parent;        // Java View/ViewGroup 代理，按引用传给宿主
                }
            }
            try {
                return hostBridge().uiInflateView(xml, hostParent, !!attachToParent);
            } catch (error) {
                var message = error && error.message ? error.message : String(error);
                if (message.indexOf('ui.inflate') >= 0) {
                    throw error;
                }
                var wrapped = new Error('ui.inflate 失败：' + message);
                wrapped.cause = error;
                throw wrapped;
            }
        };
        return ui;
    }

    if (typeof root.__aiRegisterCallback === 'function' && root.ui) {
        install(root.ui, root);
        installInflate(root.ui, root);
    } else if (typeof module !== 'undefined' && module.exports) {
        module.exports = install;
    }
})(typeof globalThis !== 'undefined' ? globalThis : this);
