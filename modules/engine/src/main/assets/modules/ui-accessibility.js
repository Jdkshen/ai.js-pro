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
    if (typeof root.__aiRegisterCallback === 'function' && root.ui) install(root.ui, root);
    else if (typeof module !== 'undefined' && module.exports) module.exports = install;
})(typeof globalThis !== 'undefined' ? globalThis : this);
