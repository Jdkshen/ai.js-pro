/*
 * Node UI 支持（task-59 B；task-69/70 第二阶段扩展）：Node24 下的 require("ui") 实现。
 *
 * 运行位置：:node24 进程的 Node 24 运行时（A 的 prelude.js 在用户脚本之前追加执行）。
 * 注册：__ajsRegisterModule("ui", factory)（兼容 __autojsRegisterModule）。
 *
 * 第一阶段（冻结）：ui.Activity / ui.R / loadAndroidResources / setMainActivity / pollEvents；
 * 宿主显示由 NodeUiHost 在 :node24 进程全屏 overlay（DynamicLayoutInflater）。
 *
 * 第二阶段（task-69/70）：
 *  - Java 类标记：prelude 的 rhino.install() 把 android/androidx/com 等装成真实 Java 包代理；
 *    ui.js 在 require("ui") 时把它们换成 UI 标记包代理（可 new/extends、方法 no-op 返回 marker），
 *    并包装 $autojs.java.defineClass：返回原 class（不再 Promise.reject）；
 *  - view.binding.<name> 用宿主 show 返回的 views 映射生成 View marker；
 *  - marker.setAdapter(adapter) 拦截：从 adapter 实例取 data、捕获 grid_item layoutId，
 *    发 {kind:"ui",op:"setAdapter"} 让宿主构建原生 RecyclerView.Adapter 显示卡片。
 */
(function () {
    'use strict';
    const register = globalThis.__ajsRegisterModule || globalThis.__autojsRegisterModule;
    if (typeof register !== 'function') {
        return;
    }

    register('ui', function (api) {
        const projectDir = String(
            (globalThis.__ajsProjectDir != null && globalThis.__ajsProjectDir)
            || (globalThis.__dirname != null && globalThis.__dirname)
            || ''
        );

        // ------------------------------------------------------------------
        // 宿主桥（string 契约，与 prelude/A 冻结）
        // ------------------------------------------------------------------
        function hostCall(request) {
            const payload = Object.assign({kind: 'ui', projectDir: projectDir}, request || {});
            let raw;
            if (typeof globalThis.__autojsHost === 'function') {
                raw = globalThis.__autojsHost(JSON.stringify(payload));
            } else if (api && typeof api.host === 'function') {
                raw = api.host(payload);
            } else {
                throw new Error('Node UI 需要宿主桥（__autojsHost）：当前 Node24 宿主未启用（A/task-58 未合入？）');
            }
            if (raw == null || raw === '') {
                throw new Error('Node UI 宿主没有返回结果（宿主桥未启用？）');
            }
            let value;
            if (typeof raw === 'string') {
                try {
                    value = JSON.parse(raw);
                } catch (error) {
                    throw new Error('Node UI 宿主返回了非法 JSON：' + raw);
                }
            } else {
                value = raw;
            }
            if (!value || value.ok !== true) {
                throw new Error((value && value.error) ? String(value.error) : 'Node UI 宿主调用失败');
            }
            return value;
        }

        // ------------------------------------------------------------------
        // task-69：Java 类/对象标记（无真实 Java 回调，只做 UI 占位与数据提取）
        // ------------------------------------------------------------------
        const lastInflate = {layoutId: 0};
        const markState = typeof WeakMap === 'function' ? new WeakMap() : null;
        // Lead 约束（task-69/70）：全局包代理替换延迟到真正进入 UI（setMainActivity/show 前），
        // 保存原始引用，close/出错/进程 exit 时恢复；不 require('ui') 的纯 Node 脚本不受影响。
        let originalGlobals = null;
        let originalJavaDefineClass = null;
        let originalRhinoDefineClass = null;
        let exitRestoreInstalled = false;

        function markerStateOf(value) {
            try {
                if (value && (typeof value === 'object' || typeof value === 'function') && markState) {
                    return markState.get(value);
                }
            } catch (ignored) { /* noop */ }
            return undefined;
        }

        // 同一 viewId 的 binding marker 复用：重复访问 binding.button 时 click handler / 真实
        // handle 不会各存一份（否则后一次 on("click") 会覆盖前一组 handler）。
        const viewBindings = Object.create(null);
        function bindingProxy(state) {
            return new Proxy({}, {
                get: function (_t, prop) {
                    if (typeof prop === 'symbol' || prop === 'then' || prop === 'toJSON') return undefined;
                    const name = String(prop);
                    const id = state.views && typeof state.views[name] === 'number' ? state.views[name] : 0;
                    const key = id > 0 ? ('id:' + id) : ('name:' + name);
                    let binding = viewBindings[key];
                    if (!binding) {
                        binding = markerObject('View', {viewName: name, viewId: id, views: state.views});
                        viewBindings[key] = binding;
                    }
                    return binding;
                }
            });
        }

        /**
         * 懒解析真实 View 句柄：binding 只拿到 id 名→viewId，第一次操作时才向宿主
         * findViewById，Activity 尚未 inflate 完时由宿主/重试逻辑给出中文错误。
         */
        function bindingHandle(state) {
            if (!state.handleId) {
                const found = uiRequestView('findViewById', {
                    id: state.viewId || 0,
                    name: state.viewName || ''
                });
                state.handleId = found.handleId;
            }
            return state.handleId;
        }

        function runBindingCommand(state, action, value, hasValue) {
            const request = {
                handleId: bindingHandle(state),
                action: action
            };
            if (hasValue) request.value = value;
            return uiRequestView('viewCommand', request);
        }

        /** button.on("click", fn) / setOnClickListener(fn)：宿主侧真实监听，事件经 pollEvents 回来。 */
        function registerViewClick(state, handler) {
            if (typeof handler !== 'function') {
                throw new TypeError('click 监听需要一个函数');
            }
            if (!state.viewId) {
                // task-194：Adapter item 探测/动态视图可能没有 id，click 绑定降级为 no-op，
                // 不再抛错（否则示例会打出「无法绑定 click」warning）。
                return false;
            }
            state.clickHandlers = state.clickHandlers || [];
            state.clickHandlers.push(handler);
            clickStates[String(state.viewId)] = state;
            if (!state.clickBound) {
                uiRequestView('bindClick', {
                    id: state.viewId,
                    name: state.viewName || ''
                });
                state.clickBound = true;
            }
        }

        function markerObject(name, extra) {
            const state = Object.assign({name: name}, extra || {});
            const target = function () {};
            const proxy = new Proxy(target, {
                get: function (_t, prop) {
                    if (typeof prop === 'symbol') return undefined;
                    const key = String(prop);
                    if (key === 'then') return undefined;
                    if (key === '__ajsMarker') return true;
                    if (key === '__markerName') return state.name;
                    if (key === '__markerState') return state;
                    if (key === 'toString') return function () { return '[NodeUI ' + state.name + ']'; };
                    if (key === 'valueOf') return function () { return 0; };
                    if (key === 'toJSON') return function () { return {}; };
                    if (key === 'on') return function (eventName, handler) {
                        if (String(eventName) === 'click') {
                            registerViewClick(state, handler);
                        }
                        return proxy;
                    };
                    if (key === 'setOnClickListener') return function (handler) {
                        registerViewClick(state, handler);
                        return proxy;
                    };
                    if (state.viewId > 0) {
                        // task-24：binding.<id> 对 text/input/button 等基础控件做真实 View 命令，
                        // 不再用 marker no-op。grid/list/canvas 等复杂控件仍走下面的 marker 兼容层。
                        switch (key) {
                            case 'setText':
                                return function (text) {
                                    runBindingCommand(state, 'setText', String(text), true);
                                    return proxy;
                                };
                            case 'getText':
                                return function () {
                                    return runBindingCommand(state, 'getText', undefined, false).value;
                                };
                            case 'setEnabled':
                                return function (enabled) {
                                    runBindingCommand(state, 'setEnabled', !!enabled, true);
                                    return proxy;
                                };
                            case 'setVisibility':
                                return function (visibility) {
                                    runBindingCommand(state, 'setVisibility', Number(visibility), true);
                                    return proxy;
                                };
                            case 'getVisibility':
                                return function () {
                                    return runBindingCommand(state, 'getVisibility', undefined, false).value;
                                };
                            case 'setChecked':
                                return function (checked) {
                                    runBindingCommand(state, 'setChecked', !!checked, true);
                                    return proxy;
                                };
                            case 'isChecked':
                                return function () {
                                    return runBindingCommand(state, 'isChecked', undefined, false).value;
                                };
                            case 'toggle':
                                return function () {
                                    runBindingCommand(state, 'toggle', undefined, false);
                                    return proxy;
                                };
                            case 'click':
                                return function () {
                                    return runBindingCommand(state, 'click', undefined, false).value;
                                };
                            default:
                                break;
                        }
                    }
                    if (Object.prototype.hasOwnProperty.call(state, key)) return state[key];
                    if (key === 'binding') return bindingProxy(state);
                    if (key === 'initAdapterFromChildren') return function () {
                        if (state.viewName === 'viewPager') {
                            try {
                                hostCall({op: 'setPager', viewId: state.viewId || 0, viewName: 'viewPager'});
                            } catch (error) {
                                console.warn('[ui] setPager 失败（页面可能空白）：', error);
                            }
                        }
                        return proxy;
                    };
                    if (key === 'setAdapter') return function (adapter) {
                        if (state.viewName === 'viewPager') {
                            try {
                                hostCall({op: 'setPager', viewId: state.viewId || 0, viewName: 'viewPager'});
                            } catch (error) {
                                console.warn('[ui] setPager 失败（页面可能空白）：', error);
                            }
                            return proxy;
                        }
                        adoptAdapter(state, adapter);
                        return proxy;
                    };
                    if (key === 'setLayoutManager') return function (layoutManager) {
                        state.layoutManager = layoutManager;
                        const args = layoutManager && layoutManager.__ajsArgs;
                        state.spanCount = (Array.isArray(args) && typeof args[1] === 'number') ? args[1] : (state.spanCount || 2);
                        return proxy;
                    };
                    if (key === 'inflate') return function (layoutId) {
                        if (typeof layoutId === 'number' && layoutId > 0) lastInflate.layoutId = layoutId;
                        return markerObject('View');
                    };
                    if (key === 'getContext') return function () { return markerObject('Context'); };
                    if (key === 'getId') return function () { return state.viewId || 0; };
                    if (key === 'isChecked') return function () { return !!state.checked; };
                    if (key === 'setChecked') return function (value) { state.checked = !!value; return proxy; };
                    if (key === 'getItemCount') return function () {
                        const data = state.data;
                        return Array.isArray(data) ? data.length : 0;
                    };
                    if (key === 'equals') return function (other) { return proxy === other; };
                    if (key === 'hashCode') return function () { return 0; };
                    if (/^[A-Z]/.test(key)) return markerClass(state.name + '.' + key);
                    return function () { return proxy; };
                },
                set: function (_t, prop, value) {
                    state[String(prop)] = value;
                    return true;
                },
                apply: function () { return proxy; },
                construct: function (_t, args, newTarget) {
                    // 作为父类被 super() 调用时不要替换 this：让派生类拿到正常实例，
                    // 这样用户 class 的 prototype 方法（onCreateViewHolder/bind…）仍然可用。
                    if (newTarget && newTarget !== proxy) {
                        return undefined;
                    }
                    return markerObject(state.name, {args: args});
                }
            });
            try { if (markState) markState.set(proxy, state); } catch (ignored) { /* noop */ }
            return proxy;
        }

        /**
         * Java 类标记：普通 function + Proxy 静态属性。
         * 作为 extends 父类时 super() 走默认构造（不替换 this），派生类实例仍能访问自己的
         * prototype 方法（onCreateViewHolder/bind…）；作为 new 目标时实例保存 __ajsArgs。
         */
        function markerClass(name) {
            const ctor = function () {
                this.__ajsMarkerName = name;
                this.__ajsArgs = Array.prototype.slice.call(arguments);
            };
            try {
                Object.defineProperty(ctor, 'name', {value: name.split('.').pop()});
            } catch (ignored) { /* noop */ }
            // 实例上的未知方法/属性：返回 no-op（链式调用继续），常见方法单独实现。
            const prototypeFallback = new Proxy({}, {
                get: function (_t, prop) {
                    if (typeof prop === 'symbol' || prop === 'then' || prop === 'toJSON') return undefined;
                    const key = String(prop);
                    if (key === 'constructor') return ctor;
                    if (key === 'getId') return function () { return 0; };
                    if (key === 'getContext') return function () { return markerObject('Context'); };
                    if (key === 'inflate') return function (layoutId) {
                        if (typeof layoutId === 'number' && layoutId > 0) lastInflate.layoutId = layoutId;
                        return markerObject('View');
                    };
                    if (key === 'setChecked') return function (value) { this.__checked = !!value; return this; };
                    if (key === 'isChecked') return function () { return !!this.__checked; };
                    if (/^[A-Z]/.test(key)) return markerClass(name + '.' + key);
                    return function () { return this; };
                }
            });
            ctor.prototype = prototypeFallback;
            return new Proxy(ctor, {
                get: function (_t, prop) {
                    if (typeof prop === 'symbol' || prop === 'then' || prop === 'toJSON') return undefined;
                    const key = String(prop);
                    if (key === 'prototype') return prototypeFallback;
                    if (key === '__ajsMarkerName') return name;
                    if (key === '__ajsClass') return true;
                    if (key === 'toString') return function () { return '[NodeUIJava ' + name + ']'; };
                    if (/^[A-Z]/.test(key)) return markerClass(name + '.' + key);
                    // 静态方法（LayoutInflater.from / Snackbar.make / WindowCompat.set...）：返回对象 marker。
                    return function () { return markerObject(name); };
                }
            });
        }

        function markerPackage(path) {
            const fn = function () {};
            return new Proxy(fn, {
                get: function (_t, prop) {
                    if (typeof prop === 'symbol') return undefined;
                    const key = String(prop);
                    if (key === 'then') return undefined;
                    if (key === 'toString') return function () { return '[NodeUIJava ' + path + ']'; };
                    if (key === '__packageRef') return true;
                    const child = path + '.' + key;
                    if (key.length > 0 && key[0] === key[0].toUpperCase()) return markerClass(child);
                    return markerPackage(child);
                },
                apply: function () { return markerObject(path); },
                construct: function (_t, args) { return markerObject(path, {args: args}); }
            });
        }

        /** 把 rhino.install() 装的真实 Java 包代理换成 UI marker（延迟到进入 UI 前，保存原引用）。 */
        function patchJavaGlobals() {
            if (globalThis.__ajsUiGlobalsPatched) return;
            globalThis.__ajsUiGlobalsPatched = true;
            originalGlobals = Object.create(null);
            const names = ['android', 'androidx', 'com', 'org', 'net', 'io', 'java', 'javax'];
            names.forEach(function (name) {
                if (!globalThis[name]) return;
                originalGlobals[name] = globalThis[name];
                try { globalThis[name] = markerPackage(name); } catch (error) {
                    globalThis[name] = originalGlobals[name];
                    console.warn('[ui] 替换包代理失败：' + name, error);
                }
            });
            try {
                const autojs = globalThis.$autojs;
                if (autojs && autojs.java && typeof autojs.java === 'object') {
                    originalJavaDefineClass = autojs.java.defineClass;
                    autojs.java.defineClass = function (spec) { return Promise.resolve(spec); };
                }
                const rhino = globalThis.__ajsBuiltinModules && globalThis.__ajsBuiltinModules.rhino;
                if (rhino) {
                    originalRhinoDefineClass = rhino.defineClass;
                    rhino.defineClass = function (spec) { return Promise.resolve(spec); };
                }
            } catch (error) {
                console.warn('[ui] 包装 defineClass 失败：', error);
            }
            // 脚本结束/异常退出时恢复（close() 也会主动恢复）；exit 回调里只做纯赋值，安全。
            if (!exitRestoreInstalled && typeof process !== 'undefined' && process && typeof process.once === 'function') {
                exitRestoreInstalled = true;
                try { process.once('exit', restoreJavaGlobals); } catch (error) { console.warn('[ui] 注册恢复钩子失败：', error); }
            }
        }

        /** 恢复全局包代理与 defineClass（可重复调用）。 */
        function restoreJavaGlobals() {
            if (!globalThis.__ajsUiGlobalsPatched) return;
            globalThis.__ajsUiGlobalsPatched = false;
            if (originalGlobals) {
                Object.keys(originalGlobals).forEach(function (name) {
                    try { globalThis[name] = originalGlobals[name]; } catch (ignored) { /* noop */ }
                });
            }
            try {
                const autojs = globalThis.$autojs;
                if (autojs && autojs.java && originalJavaDefineClass) autojs.java.defineClass = originalJavaDefineClass;
                const rhino = globalThis.__ajsBuiltinModules && globalThis.__ajsBuiltinModules.rhino;
                if (rhino && originalRhinoDefineClass) rhino.defineClass = originalRhinoDefineClass;
            } catch (ignored) { /* noop */ }
        }

        function extractItems(adapter) {
            let data = adapter && adapter.data;
            if (!Array.isArray(data) && adapter && Array.isArray(adapter.__ajsArgs)) {
                data = adapter.__ajsArgs[0];
            }
            if (!Array.isArray(data)) data = adapter && adapter.items;
            if (!Array.isArray(data)) return [];
            return data.slice(0, 20).map(function (item) {
                const title = item && item.title != null ? String(item.title) : '';
                const url = item && (item.url != null ? item.url : item.image);
                return {title: title, url: url != null ? String(url) : '', checked: !!(item && item.checked)};
            });
        }

        /** 调用 adapter.onCreateViewHolder(markerParent,0) 捕获 ui.R.layout.grid_item 的 layoutId。 */
        function detectItemLayout(adapter) {
            lastInflate.layoutId = 0;
            try {
                if (adapter && typeof adapter.onCreateViewHolder === 'function') {
                    adapter.onCreateViewHolder(markerObject('ViewGroup', {views: {}}), 0);
                }
            } catch (error) {
                // task-194：item 探测允许失败（无 id/非 View 的 marker），降级为调试输出。
                if (typeof console !== 'undefined' && console.debug) {
                    console.debug('[ui] 探测 item 布局失败（可忽略）：'
                            + ((error && error.message) || String(error)));
                }
            }
            return lastInflate.layoutId || 0;
        }

        function adoptAdapter(gridState, adapter) {
            try {
                if (!adapter) {
                    console.warn('[ui] setAdapter 收到空 adapter');
                    return;
                }
                const items = extractItems(adapter);
                const itemLayoutId = detectItemLayout(adapter);
                console.log('[ui] setAdapter -> host: view=' + (gridState.viewName || 'grid')
                    + ' viewId=' + (gridState.viewId || 0)
                    + ' items=' + items.length + ' itemLayoutId=' + itemLayoutId
                    + ' spanCount=' + (gridState.spanCount || 2));
                const result = hostCall({
                    op: 'setAdapter',
                    viewId: gridState.viewId || 0,
                    viewName: gridState.viewName || 'grid',
                    itemLayoutId: itemLayoutId,
                    items: items,
                    spanCount: gridState.spanCount || 2
                });
                console.log('[ui] 卡片适配器已交给宿主: items=' + items.length
                    + ' itemLayoutId=' + itemLayoutId
                    + (result && result.debug ? (' debug=' + JSON.stringify(result.debug)) : ''));
            } catch (error) {
                console.warn('[ui] setAdapter 失败（卡片区可能为空）：', error);
            }
        }

        // task-72：UI 脚本里的异步 _setupXxx 若 reject，要能在 logcat 看到，不能静默卡片区空白。
        try {
            if (!globalThis.__ajsUiRejectionHooked && typeof process !== 'undefined' && process
                && typeof process.on === 'function') {
                globalThis.__ajsUiRejectionHooked = true;
                process.on('unhandledRejection', function (reason) {
                    console.error('[ui] unhandledRejection:', reason && reason.stack ? reason.stack : reason);
                });
            }
        } catch (error) { /* noop */ }

        // 注意：不在这里替换全局（Lead 约束）。延迟到 setMainActivity 前，见 patchJavaGlobals()。

        // ------------------------------------------------------------------
        // ui.R / Activity（第一阶段面 + task-69/70 marker 适配）
        // ------------------------------------------------------------------
        function resourceTable() {
            return new Proxy({}, {
                get: function (_target, type) {
                    if (type === 'then' || type === 'toJSON' || typeof type === 'symbol') return undefined;
                    const typeName = String(type);
                    return new Proxy({}, {
                        get: function (_t, name) {
                            if (name === 'then' || name === 'toJSON' || typeof name === 'symbol') return undefined;
                            const resName = String(name);
                            try {
                                if (typeName === 'layout') {
                                    const value = hostCall({op: 'layoutId', name: resName});
                                    return typeof value.id === 'number' ? value.id : 0;
                                }
                                const value = hostCall({op: 'resourceId', type: typeName, name: resName});
                                return typeof value.id === 'number' ? value.id : 0;
                            } catch (error) {
                                return typeName === 'layout' ? 0 : -1;
                            }
                        }
                    });
                }
            });
        }

        const R = resourceTable();

        // ------------------------------------------------------------------
        // task-155：ui.Widget（自定义组件最小子集）
        //  - Widget.register(name, Ctor)：注册 XML 自定义标签；
        //  - Activity.layoutXml 中的自定义标签在发宿主前展开为 Ctor.render() 返回的 XML；
        //  - 宿主 show 成功后把 widget.view 绑定到真实 View binding，并调用 mounted()；
        //  - Widget.of(view) 从 binding 取回 widget 实例。
        // ------------------------------------------------------------------
        const widgetRegistry = Object.create(null);
        let pendingWidgets = [];

        function normalizeWidgetTag(name) {
            return String(name == null ? '' : name).trim().toLowerCase();
        }
        function escapeRegExp(text) {
            return String(text).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        }
        function parseXmlAttributes(text) {
            const attrs = Object.create(null);
            const re = /([A-Za-z_][A-Za-z0-9_:.-]*)\s*=\s*("([^"]*)"|'([^']*)')/g;
            let match;
            while ((match = re.exec(String(text || '')))) {
                attrs[match[1]] = match[3] !== undefined ? match[3] : match[4];
            }
            return attrs;
        }
        function xmlEscape(value) {
            return String(value).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
        }
        function findPropertyDescriptor(target, name) {
            let current = target;
            while (current) {
                const descriptor = Object.getOwnPropertyDescriptor(current, name);
                if (descriptor) return descriptor;
                current = Object.getPrototypeOf(current);
            }
            return null;
        }
        function hasWidgetAttrSetter(widget, name) {
            const descriptor = findPropertyDescriptor(widget, 'attr$' + name);
            return !!(descriptor && descriptor.set);
        }

        class Widget {
            constructor() {
                this.attrs = Object.assign({}, this.initialAttributes || {});
                this.view = null;
                this.__ajsNodeUiWidget = true;
            }
            get initialAttributes() { return {}; }
            render() { throw new Error('ui.Widget.render() 必须返回 XML 字符串'); }
        }
        Widget.register = function (name, Ctor) {
            const tag = normalizeWidgetTag(name);
            if (!tag) throw new TypeError('ui.Widget.register 需要非空标签名');
            if (typeof Ctor !== 'function') throw new TypeError('ui.Widget.register 需要一个 class');
            widgetRegistry[tag] = Ctor;
            return Ctor;
        };
        Widget.of = function (view) {
            if (view == null) return null;
            try {
                const widget = view.__ajsWidget;
                return widget && widget.__ajsNodeUiWidget ? widget : null;
            } catch (error) { return null; }
        };

        function expandOneWidget(tag, attrText, records, selfClosing) {
            const Ctor = widgetRegistry[tag];
            if (typeof Ctor !== 'function') return '<' + tag + attrText + (selfClosing ? '/>' : '>');
            const attrs = parseXmlAttributes(attrText);
            const widget = new Ctor();
            widget.attrs = Object.assign({}, widget.initialAttributes || {}, attrs);
            const rendered = widget.render();
            if (typeof rendered !== 'string' || rendered.trim() === '') {
                throw new Error('ui.Widget「' + tag + '」render() 必须返回非空 XML 字符串');
            }
            const rootMatch = /^(\s*<)([A-Za-z_][A-Za-z0-9_:.-]*)([\s\S]*?)(\/?>)/.exec(rendered);
            if (!rootMatch) {
                throw new Error('ui.Widget「' + tag + '」render() 返回的 XML 缺少根标签');
            }
            let rootAttrs = rootMatch[3] || '';
            let extra = '';
            const idValue = attrs.id ? String(attrs.id).replace(/^@\+?id\//, '') : '';
            if (idValue && !/\bid\s*=/.test(rootAttrs)) {
                extra += ' id="' + xmlEscape(idValue) + '"';
            }
            Object.keys(attrs).forEach(function (name) {
                if (name === 'id') return;
                if (hasWidgetAttrSetter(widget, name)) return;
                if (new RegExp('(^|\\s)' + escapeRegExp(name) + '\\s*=').test(rootAttrs)) return;
                extra += ' ' + name + '="' + xmlEscape(attrs[name]) + '"';
            });
            const newRoot = rootMatch[1] + rootMatch[2] + rootAttrs + extra + rootMatch[4];
            const expanded = rendered.replace(rendered.slice(0, rootMatch[0].length), newRoot);
            records.push({ tag: tag, id: idValue, attrs: attrs, widget: widget, rendered: expanded });
            return expanded;
        }

        function expandCustomWidgetsInXml(xml) {
            let output = String(xml || '');
            const records = [];
            for (let depth = 0; depth < 4; depth++) {
                let changed = false;
                Object.keys(widgetRegistry).forEach(function (tag) {
                    const quoted = escapeRegExp(tag);
                    const paired = new RegExp('<\\s*' + quoted + '\\b([^>]*)>([\\s\\S]*?)<\\s*/\\s*' + quoted + '\\s*>', 'gi');
                    output = output.replace(paired, function (_all, attrText) {
                        changed = true;
                        return expandOneWidget(tag, attrText, records, false);
                    });
                    const selfClosing = new RegExp('<\\s*' + quoted + '\\b([^>]*?)/\\s*>', 'gi');
                    output = output.replace(selfClosing, function (_all, attrText) {
                        changed = true;
                        return expandOneWidget(tag, attrText, records, true);
                    });
                });
                if (!changed) break;
            }
            return {xml: output, records: records};
        }

        function attachCustomWidgets(records, views) {
            (records || []).forEach(function (record) {
                const widget = record.widget;
                const idName = record.id;
                if (!widget || !idName) return;
                const viewId = views && typeof views[idName] === 'number' ? views[idName] : 0;
                const key = viewId > 0 ? ('id:' + viewId) : ('name:' + idName);
                let binding = viewBindings[key];
                if (!binding) {
                    binding = markerObject('View', {viewName: idName, viewId: viewId, views: views});
                    viewBindings[key] = binding;
                }
                try { binding.__ajsWidget = widget; } catch (ignored) { /* noop */ }
                widget.view = binding;
                Object.keys(record.attrs || {}).forEach(function (name) {
                    if (name === 'id') return;
                    const setterName = 'attr$' + name;
                    const descriptor = findPropertyDescriptor(widget, setterName);
                    if (!descriptor || !descriptor.set) return;
                    try {
                        widget[setterName] = record.attrs[name];
                    } catch (error) {
                        console.warn('[ui] Widget 属性设置失败 ' + record.tag + '.' + name + '：', error);
                    }
                });
                try {
                    if (typeof widget.mounted === 'function') widget.mounted(binding);
                } catch (error) {
                    console.error('[ui] Widget.mounted 回调异常：', error);
                }
            });
        }

        class Activity {
            constructor() {
                this.__ajsNodeUiActivity = true;
                this.__ajsHandlers = Object.create(null);
            }
            get layoutId() { return null; }
            onCreate() {}
            onStart() {}
            onResume() {}
            onPause() {}
            onStop() {}
            onDestroy() {}
            onContentViewSet() {}
            onBackPressed() {}
            onCreateOptionsMenu() { return false; }
            onOptionsItemSelected() { return false; }
            on(name, handler) {
                if (typeof handler === 'function') {
                    (this.__ajsHandlers[name] = this.__ajsHandlers[name] || []).push(handler);
                }
                return this;
            }
            // task-69：示例 onCreate/onContentViewSet 会调这些真实 Activity API；第一阶段用 marker 容错，
            // 不能 throw，否则后面的 _setupGrid 不会执行、卡片区永远为空。
            getWindow() { return markerObject('Window'); }
            getTheme() { return markerObject('Theme'); }
            setSupportActionBar() { return markerObject('ActionBar'); }
            getMenuInflater() { return markerObject('MenuInflater'); }
            getApplicationContext() { return markerObject('Context'); }
        }

        // ------------------------------------------------------------------
        // 宿主 show 返回的 views / binding / 事件
        // ------------------------------------------------------------------
        let mainActivity = null;
        let mainViews = {};
        // 真实 View binding 的 click 监听：Activity/overlay 只回传 viewId，这里按 id 找到
        // 注册过 handler 的 binding 状态。每个 ui 模块实例独立，close 时清空。
        const clickStates = Object.create(null);
        let pollTimer = null;
        let dispatchingDestroy = false;
        let destroyingInstance = null;
        let uiClosed = false;
        const EVENT_METHODS = {
            create: 'onCreate',
            start: 'onStart',
            resume: 'onResume',
            pause: 'onPause',
            stop: 'onStop',
            destroy: 'onDestroy',
            content_view_set: 'onContentViewSet',
            back_pressed: 'onBackPressed'
        };

        function makeViewProxy(event) {
            const views = (event && event.views) || mainViews || {};
            globalThis.__ajsUiViews = views || {};
            return markerObject('View', {views: views, rootId: (event && event.rootId) || 0});
        }

        /**
         * 共享的 UI 生命周期清理（destroy 回调之后 / 显式 ui.close / 轮询失败）：
         * 清掉 120ms 轮询、释放 $autojs.keepRunning 的保活、清主界面引用并恢复 Java 全局代理。
         * 可重复调用；destroy 回调里若打开了替换界面（mainActivity 已变），不能清掉新界面。
         */
        function cleanupUiLifecycle(instance) {
            if (instance && mainActivity && mainActivity !== instance) {
                return false;
            }
            if (uiClosed) {
                return false;
            }
            uiClosed = true;
            if (pollTimer != null) {
                clearInterval(pollTimer);
                pollTimer = null;
            }
            try {
                if (globalThis.$autojs && typeof globalThis.$autojs.stopRunning === 'function') {
                    globalThis.$autojs.stopRunning();
                }
            } catch (error) {
                console.warn('[ui] $autojs.stopRunning() 调用失败：', error);
            }
            mainActivity = null;
            mainViews = {};
            Object.keys(clickStates).forEach(function (id) { delete clickStates[id]; });
            Object.keys(viewBindings).forEach(function (key) { delete viewBindings[key]; });
            restoreJavaGlobals();
            return true;
        }

        function dispatchViewClick(event) {
            const viewId = Number(event && event.viewId) || 0;
            if (!viewId) return;
            const state = clickStates[String(viewId)];
            if (!state || !Array.isArray(state.clickHandlers)) return;
            state.clickHandlers.slice().forEach(function (handler) {
                try {
                    handler(event);
                } catch (error) {
                    console.error('[ui] click 回调异常：', error);
                }
            });
        }

        function dispatch(event) {
            const instance = mainActivity;
            if (!instance || !event) return;
            const name = String(event.type || '');
            const method = EVENT_METHODS[name];
            const argument = name === 'content_view_set' ? makeViewProxy(event) : (event.savedInstanceState || null);
            const isDestroy = name === 'destroy';
            if (isDestroy) {
                dispatchingDestroy = true;
                destroyingInstance = instance;
            }
            try {
                if (method && typeof instance[method] === 'function') {
                    try {
                        if (name === 'content_view_set') {
                            console.log('[ui] dispatch content_view_set, binding keys='
                                + Object.keys((event && event.views) || mainViews || {}).join(','));
                        }
                        instance[method](argument);
                    } catch (error) {
                        console.error('[ui] ' + method + ' 回调异常：', error);
                    }
                }
                const handlers = instance.__ajsHandlers && instance.__ajsHandlers[name];
                if (Array.isArray(handlers)) {
                    handlers.slice().forEach(function (handler) {
                        try {
                            handler(argument);
                        } catch (error) {
                            console.error('[ui] 事件 ' + name + ' 处理异常：', error);
                        }
                    });
                }
                if (name === 'click') {
                    dispatchViewClick(event);
                }
            } finally {
                if (isDestroy) {
                    dispatchingDestroy = false;
                    destroyingInstance = null;
                    // 回调执行完再清理；若 onDestroy 里 setMainActivity 了替换界面，identity guard 会跳过。
                    cleanupUiLifecycle(instance);
                }
            }
        }

        function pollEvents() {
            if (pollTimer == null) return;
            let value;
            try {
                value = hostCall({op: 'pollEvents'});
            } catch (error) {
                console.error('[ui] pollEvents 失败，停止轮询并恢复全局代理：', error);
                cleanupUiLifecycle(mainActivity);
                return;
            }
            const events = Array.isArray(value.events) ? value.events : [];
            events.forEach(dispatch);
        }

        function loadAndroidResources() {
            return Promise.resolve().then(function () {
                return hostCall({op: 'loadAndroidResources'});
            });
        }

        function layoutOf(rawLayout) {
            if (typeof rawLayout === 'number' && rawLayout > 0) {
                return {layoutId: rawLayout};
            }
            if (rawLayout && typeof rawLayout === 'object' && rawLayout.__ajsLayout) {
                const parts = String(rawLayout.__ajsLayout).split('/');
                return {type: parts[0] || 'layout', name: parts[parts.length - 1]};
            }
            return {name: String(rawLayout || '')};
        }

        function setMainActivity(Ctor) {
            if (typeof Ctor !== 'function') {
                throw new TypeError('ui.setMainActivity 需要一个 Activity 子类');
            }
            // Lead 约束：进入 UI 前才替换全局 Java 包代理/defineClass；失败恢复后抛出。
            patchJavaGlobals();
            let instance;
            let layout;
            let layoutXml = '';
            try {
                instance = new Ctor();
                const rawLayoutXml = instance.layoutXml;
                if (rawLayoutXml != null && typeof rawLayoutXml !== 'string') {
                    throw new TypeError('Activity.layoutXml 必须返回字符串');
                }
                layoutXml = typeof rawLayoutXml === 'string' ? rawLayoutXml.trim() : '';
                // task-155：自定义 Widget 标签在发宿主前展开（宿主只 inflate 普通控件标签）。
                if (layoutXml) {
                    const expanded = expandCustomWidgetsInXml(layoutXml);
                    layoutXml = expanded.xml;
                    pendingWidgets = expanded.records;
                } else {
                    pendingWidgets = [];
                }
                // layoutXml 优先：不要再为了 layoutId 去查不存在的工程 R 表。
                layout = layoutXml ? {inline: true} : layoutOf(instance.layoutId);
            } catch (error) {
                restoreJavaGlobals();
                throw error;
            }
            if (!layout.layoutId && !layout.name && !layoutXml) {
                restoreJavaGlobals();
                throw new Error('Activity 没有设置 layoutId 或 layoutXml'
                    + '（get layoutId() 返回 ui.R.layout.xxx，或 get layoutXml() 返回 XML 字符串）');
            }
            const request = {op: 'show', className: Ctor.name || 'MainActivity'};
            if (layoutXml) {
                // 字符串布局入口：不要求工程 res/build，宿主动态 inflate 并返回真实 views 映射。
                request.layoutXml = layoutXml;
                layout = {inline: true};
            } else if (layout.layoutId) {
                request.layoutId = layout.layoutId;
            } else if (layout.name) {
                request.layout = {type: 'layout', name: layout.name};
            }
            let result;
            try {
                result = hostCall(request);
            } catch (error) {
                restoreJavaGlobals();
                throw error;
            }
            mainActivity = instance;
            mainViews = result.views && typeof result.views === 'object' ? result.views : {};
            Object.keys(viewBindings).forEach(function (key) { delete viewBindings[key]; });
            Object.keys(clickStates).forEach(function (id) { delete clickStates[id]; });
            uiClosed = false;
            attachCustomWidgets(pendingWidgets, mainViews);
            pendingWidgets = [];
            if (pollTimer == null) {
                pollTimer = setInterval(pollEvents, 120);
            }
            try {
                if (globalThis.$autojs && typeof globalThis.$autojs.keepRunning === 'function') {
                    globalThis.$autojs.keepRunning();
                }
            } catch (error) {
                console.warn('[ui] $autojs.keepRunning() 调用失败：', error);
            }
            pollEvents();
            return {instance: instance, layout: layout, host: result};
        }

        function close() {
            const instance = mainActivity;
            // 收到当前 Activity 的 destroy 时宿主已在关闭流程里，不能再 hostCall close（避免递归 destroy）；
            // 若 destroy 回调已经打开了替换界面，则显式 close 仍应关闭那个新界面。
            const closingReceivedDestroy = dispatchingDestroy && instance != null
                && instance === destroyingInstance;
            if (!uiClosed && !closingReceivedDestroy) {
                try {
                    hostCall({op: 'close'});
                } catch (error) {
                    console.warn('[ui] close 失败：', error);
                }
            }
            cleanupUiLifecycle(instance);
        }

        // task-134：第二批 UI API（基于现有 host 能力，能真实工作的先落地；
        // setContentView/inflate/layout 依赖宿主 View 句柄，先给明确中文错误并在 probe 中标记）。
        // 注意：ui.run/ui.post 只保证「调用兼容」，回调始终在 Node 线程执行；真正的 View 命令
        // 由 uiRequestView -> 宿主 Binder 切到 Android UI 线程，不能把 Node 回调误当成 main。
        function uiRun(fn) {
            if (typeof fn !== 'function') { throw new TypeError('ui.run 需要一个函数'); }
            return fn();
        }
        function uiPost(fn, delay) {
            if (typeof fn !== 'function') { throw new TypeError('ui.post 需要一个函数'); }
            return setTimeout(function () { try { fn(); } catch (error) { console.error('[ui] post 回调异常', error); } },
                Math.max(0, Number(delay) || 0));
        }
        function uiRequestView(op, extra) {
            var request = {};
            if (extra) { for (var key in extra) { if (Object.prototype.hasOwnProperty.call(extra, key)) { request[key] = extra[key]; } } }
            request.op = op;
            var result = hostCall(request);
            if (!result || result.ok !== true) {
                throw new Error((result && result.error) || ('ui 命令失败：' + op));
            }
            return result;
        }
        function uiWrapHandle(result) {
            var handleId = result.handleId;
            function command(action, value) {
                var response = uiRequestView('viewCommand', {handleId: handleId, action: action, value: value});
                return response ? response.value : undefined;
            }
            return {
                __handleId: handleId,
                __viewId: result.viewId,
                setText: function (text) { command('setText', String(text)); return this; },
                getText: function () { return command('getText'); },
                setVisibility: function (value) { command('setVisibility', Number(value)); return this; },
                setEnabled: function (value) { command('setEnabled', !!value); return this; },
                setChecked: function (value) { command('setChecked', !!value); return this; },
                isChecked: function () { return command('isChecked'); },
                click: function () { return command('click'); }
            };
        }
        function uiFindViewById(idOrName) {
            var id = typeof idOrName === 'number' ? idOrName : 0;
            var name = typeof idOrName === 'string' ? idOrName : '';
            return uiWrapHandle(uiRequestView('findViewById', {id: id, name: name}));
        }
        function uiSetContentView(layoutOrView) {
            var name = '';
            if (typeof layoutOrView === 'string') { name = layoutOrView; }
            else if (layoutOrView && layoutOrView.__layoutName) { name = layoutOrView.__layoutName; }
            return uiWrapHandle(uiRequestView('setContentView', {name: name}));
        }
        function uiInflate(layoutName) {
            return uiWrapHandle(uiRequestView('inflate', {name: String(layoutName || '')}));
        }
        function uiCurrentActivity() { return markerObject('Activity'); }
        function uiGetWindow() {
            return {
                __nodeUiMarker: 'Window',
                setStatusBarColor: function (color) { try { hostCall({op: 'setStatusBarColor', color: color}); } catch (error) { } return this; },
                setNavigationBarColor: function (color) { try { hostCall({op: 'setNavigationBarColor', color: color}); } catch (error) { } return this; },
                setSoftInputMode: function () { return this; },
                addFlags: function () { return this; },
                getDecorView: function () { return markerObject('View'); }
            };
        }
        /**
         * 真实线程判定：Looper.myLooper() === Looper.getMainLooper()。
         * 走 prelude 已有的 Java 反射代理，不伪造 true；Node 线程没有 Android Looper 时为 false。
         * 判定失败也返回 false（宁可承认不是 UI 线程，也不让脚本误以为能直接改 View）。
         */
        function uiIsUiThread() {
            try {
                const autojs = globalThis.$autojs;
                const Packages = autojs && autojs.java && autojs.java.Packages;
                const android = Packages && Packages.android;
                const os = android && android.os;
                const Looper = os && os.Looper;
                if (!Looper) return false;
                const current = Looper.myLooper();
                const main = Looper.getMainLooper();
                if (current == null || main == null) return false;
                if (current === main) return true;
                if (typeof current.equals === 'function') {
                    return !!current.equals(main);
                }
                return false;
            } catch (error) {
                console.warn('[ui] isUiThread 判定失败（按非 UI 线程处理）：', error);
                return false;
            }
        }
        function uiGetCurrentActivity() { return uiCurrentActivity(); }

        const ui = {
            Activity: Activity,
            Widget: Widget,
            run: uiRun,
            post: uiPost,
            runOnUiThread: uiPost,
            findViewById: uiFindViewById,
            setContentView: uiSetContentView,
            inflate: uiInflate,
            layout: uiInflate,
            getWindow: uiGetWindow,
            currentActivity: uiCurrentActivity,
            getCurrentActivity: uiGetCurrentActivity,
            isUiThread: uiIsUiThread,
            get uiThread() { return uiIsUiThread(); },
            R: R,
            loadAndroidResources: loadAndroidResources,
            setMainActivity: setMainActivity,
            close: close,
            __pollTimer: function () { return pollTimer; }
        };
        return ui;
    });
})();
