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
 *    defineClass 由 prelude 的真实 Java 类生成桥提供；存在生成类时保留真实包代理。
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
        let realUiMode = false;
        let realInlineMode = false;
        let resourceCompiled = false;
        let realActivity = null;
        let realSession = null;
        let realImageLoader = null;
        let suppressAllDestroyed = false;
        const inlineBindingNames = new Set();
        const realBindings = new WeakMap();
        const realBindingCaches = new WeakMap();
        let realViewExtras = new WeakMap();
        function viewExtras(view) {
            let extras = realViewExtras.get(view);
            if (!extras) {
                extras = {events: new (require('events').EventEmitter)(), bound: Object.create(null), widget: null,
                    data: Object.create(null)};
                realViewExtras.set(view, extras);
            }
            return extras;
        }
        function isNodeUiView(view, classNamePart) {
            try {
                return String(view && view.__class || '').indexOf(classNamePart) >= 0;
            } catch (ignored) {
                return false;
            }
        }

        /** holder.position/data 必须是「访问时」的最新值，不能是创建时快照。 */
        function itemHolderFacade(holder) {
            return {
                get position() {
                    try { return Number(holder.getPosition()); } catch (error) { return -1; }
                },
                get data() {
                    try { return JSON.parse(String(holder.getDataJson() || '{}')); } catch (error) { return null; }
                },
                getPosition: function () { return this.position; },
                getData: function () { return this.data; }
            };
        }

        function ensureItemCreatedListener(view, extras) {
            if (extras.itemCreatedBound) return;
            const Listener = globalThis.$autojs.java.findClass(
                'com.stardust.autojs.engine.node24.NodeUiRecyclerView$ItemCreatedListener');
            view.setItemCreatedListener(new Listener({
                onItemCreated: function (itemView, holder) {
                    try {
                        extras.events.emit('item_created', itemView, itemHolderFacade(holder));
                    } catch (error) {
                        console.error('[ui] item_created 回调异常：', error);
                    }
                }
            }));
            extras.itemCreatedBound = true;
        }

        function ensureDrawListener(view, extras) {
            if (extras.drawBound) return;
            const Listener = globalThis.$autojs.java.findClass(
                'com.stardust.autojs.engine.node24.NodeUiCanvasView$DrawListener');
            view.setDrawListener(new Listener({
                onDraw: function (canvas) {
                    try {
                        extras.events.emit('draw', canvas);
                    } catch (error) {
                        console.error('[ui] draw 回调异常：', error);
                    }
                }
            }));
            extras.drawBound = true;
        }

        function requireNodeView(view, feature) {
            if (!isNodeUiView(view, 'NodeUiRecyclerView') && !isNodeUiView(view, 'NodeUiGridView') && !isNodeUiView(view, 'NodeUiListView')) {
                throw new Error(feature + ' 需要 Node 原生 grid/list（当前：'
                    + String(view && view.__class || 'unknown') + '）');
            }
        }

        function callAdapterNative(view, method, args, fallback) {
            if (typeof view[method] === 'function') {
                view[method].apply(view, args);
                return;
            }
            if (typeof fallback === 'function') {
                fallback();
                return;
            }
            throw new Error('当前 grid/list 缺少适配器方法：' + method);
        }

        // Match the native View event contract: listener presence/return values do not
        // consume input. Only event.consumed controls the synchronous Android reply.
        function inputEvent(nativeEvent, fields) {
            const state = Object.assign({consumed: false}, fields || {});
            if (!nativeEvent) return state;
            return new Proxy(state, {
                get: function (target, name) {
                    if (Object.prototype.hasOwnProperty.call(target, name)) return target[name];
                    const member = nativeEvent[name];
                    return typeof member === 'function' ? member.bind(nativeEvent) : member;
                },
                set: function (target, name, value) {
                    if (name === 'consumed') { target.consumed = !!value; return true; }
                    nativeEvent[name] = value;
                    return true;
                }
            });
        }

        function bindViewEvent(view, extras, event) {
            const group = /^touch(?:_down|_up|_move)?$/.test(event) ? 'touch'
                : /^key(?:_down|_up)?$/.test(event) ? 'key' : event;
            if (extras.bound[group]) return;
            if (event === 'item_created') {
                requireNodeView(view, event);
                ensureItemCreatedListener(view, extras);
            } else if (event === 'draw') {
                if (!isNodeUiView(view, 'NodeUiCanvasView')) {
                    throw new Error('draw 事件需要 Node 原生 canvas（当前：'
                        + String(view && view.__class || 'unknown') + '）');
                }
                ensureDrawListener(view, extras);
            } else {
                let listenerClass, method, callbacks;
                function dispatch(name, args) {
                    try { return extras.events.emit.apply(extras.events, [name].concat(args)); }
                    catch (error) { console.error('[ui] ' + name + ' 回调异常：', error); return false; }
                }
                switch (group) {
                    case 'click':
                        listenerClass = 'android.view.View$OnClickListener'; method = 'setOnClickListener';
                        callbacks = {onClick: source => { dispatch('click', [source]); }};
                        break;
                    case 'check':
                        listenerClass = 'android.widget.CompoundButton$OnCheckedChangeListener'; method = 'setOnCheckedChangeListener';
                        callbacks = {onCheckedChanged: (source, checked) => { dispatch('check', [!!checked, source]); }};
                        break;
                    case 'long_click':
                        listenerClass = 'android.view.View$OnLongClickListener'; method = 'setOnLongClickListener';
                        callbacks = {onLongClick: source => {
                            const e = inputEvent(null, {view: source});
                            dispatch('long_click', [e, source]);
                            return !!e.consumed;
                        }};
                        break;
                    case 'touch':
                        listenerClass = 'android.view.View$OnTouchListener'; method = 'setOnTouchListener';
                        callbacks = {onTouch: (source, nativeEvent) => {
                            const e = inputEvent(nativeEvent, {view: source});
                            const action = Number(nativeEvent.getAction()) & 255;
                            const phase = action === 0 ? 'touch_down' : action === 1 ? 'touch_up' : action === 2 ? 'touch_move' : null;
                            if (phase) dispatch(phase, [e, source]);
                            dispatch('touch', [e, source]);
                            return !!e.consumed;
                        }};
                        break;
                    case 'key':
                        listenerClass = 'android.view.View$OnKeyListener'; method = 'setOnKeyListener';
                        callbacks = {onKey: (source, keyCode, nativeEvent) => {
                            const e = inputEvent(nativeEvent, {view: source}), action = Number(nativeEvent.getAction());
                            if (action === 0 || action === 1) dispatch(action === 0 ? 'key_down' : 'key_up', [keyCode, e, source]);
                            dispatch('key', [keyCode, e, source]);
                            return !!e.consumed;
                        }};
                        break;
                    case 'scroll_change':
                        listenerClass = 'android.view.View$OnScrollChangeListener'; method = 'setOnScrollChangeListener';
                        callbacks = {onScrollChange: (source, scrollX, scrollY, oldScrollX, oldScrollY) => {
                            dispatch('scroll_change', [inputEvent(null, {view: source, scrollX, scrollY, oldScrollX, oldScrollY}), source]);
                        }};
                        break;
                    default: throw new Error('Node View 事件暂不支持：' + event);
                }
                const Listener = globalThis.$autojs.java.findClass(listenerClass);
                view[method](new Listener(callbacks));
            }
            extras.bound[group] = true;
        }

        // Extend the existing Java proxy, so binding/findViewById/native callbacks all
        // keep the same View identity and Java method/argument conversion.
        function realViewMember(view, name) {
            const extras = viewExtras(view);
            if (name === 'extras') return extras.data;
            if (name === 'findView') return function (idOrName) {
                const id = typeof idOrName === 'number' ? idOrName
                    : idOrName == null ? 0 : resolveViewId(String(idOrName));
                const child = id ? view.findViewById(id) : null;
                return child && isWebViewObject(child) ? webViewFacade(child) : child;
            };
            if (name === 'clearViewBindingCache') return function () {
                const cache = realBindingCaches.get(view);
                if (cache) Object.keys(cache).forEach(key => { delete cache[key]; });
            };
            if (['eventNames', 'listenerCount', 'listeners', 'getMaxListeners', 'emit'].includes(name)) {
                return extras.events[name].bind(extras.events);
            }
            if (name === 'setMaxListeners') return function (count) {
                extras.events.setMaxListeners(count);
                return view;
            };
            if (name === 'attr') return function (attribute, value) {
                if (attribute && typeof attribute === 'object') {
                    // Preserve custom Widget setters and insertion order while batching adjacent
                    // ordinary attributes. A missing old-host method is the only fallback case.
                    let pending = Object.create(null);
                    function flush() {
                        const keys = Object.keys(pending);
                        if (!keys.length) return;
                        try {
                            javaSession().viewAttributes(view, JSON.stringify(pending));
                        } catch (error) {
                            const text = String(error && error.message || error);
                            const missing = text.indexOf('viewAttributes') >= 0 &&
                                (text.indexOf('找不到方法') >= 0 || text.indexOf('NoSuchMethod') >= 0 ||
                                 /viewAttributes\s+is not a function/.test(text));
                            if (!missing) throw error;
                            keys.forEach(function (key) { view.attr(key, pending[key]); });
                        }
                        pending = Object.create(null);
                    }
                    Object.keys(attribute).forEach(function (key) {
                        const widget = extras.widget;
                        const descriptor = widget && findPropertyDescriptor(widget, 'attr$' + key);
                        if (descriptor && (descriptor.set || descriptor.writable)) {
                            flush();
                            if (descriptor.set) descriptor.set.call(widget, attribute[key]);
                            else Reflect.set(widget, 'attr$' + key, attribute[key]);
                            widget.attrs[key] = attribute[key];
                        } else pending[key] = attribute[key];
                    });
                    flush();
                    return view;
                }
                const key = String(attribute);
                const widget = extras.widget;
                const descriptor = widget && findPropertyDescriptor(widget, 'attr$' + key);
                if (arguments.length < 2) {
                    if (descriptor && (descriptor.get || Object.prototype.hasOwnProperty.call(descriptor, 'value'))) {
                        return Reflect.get(widget, 'attr$' + key);
                    }
                    if (descriptor && descriptor.set) return widget.attrs[key];
                    return javaSession().viewAttribute(view, key, '', false);
                }
                if (descriptor && (descriptor.set || descriptor.writable)) {
                    if (descriptor.set) descriptor.set.call(widget, value);
                    else Reflect.set(widget, 'attr$' + key, value);
                    widget.attrs[key] = value;
                } else {
                    javaSession().viewAttribute(view, key, String(value), true);
                }
                return view;
            };
            if (name === 'removeAllListeners') return function (event) {
                if (arguments.length) extras.events.removeAllListeners(String(event));
                else extras.events.removeAllListeners();
                return view;
            };
            // 传统界面示例：这些属性需 prelude 白名单放行（见交付说明）。未放行时
            // Java 侧的 setItemTemplate/setDataSource 仍可直接工作（数据同步有限）。
            if (name === 'setItemTemplate') return function (xml) {
                requireNodeView(view, 'setItemTemplate');
                extras.itemTemplate = String(xml);
                view.nativeSetItemTemplate(String(xml));
                return view;
            };
            if (name === 'setDataSource') return function (data) {
                requireNodeView(view, 'setDataSource');
                extras.itemData = data;
                view.nativeSetDataSource(data);
                return view;
            };
            if (name === 'getAdapter' && !isNodeUiView(view, 'NodeUiGridView') && !isNodeUiView(view, 'NodeUiListView')
                && !isNodeUiView(view, 'NodeUiRecyclerView')) return undefined;
            if (name === 'getAdapter') return function () {
                requireNodeView(view, 'getAdapter');
                const data = function () {
                    const current = extras.itemData;
                    return Array.isArray(current) ? current : [];
                };
                return {
                    getItemCount: function () { return data().length; },
                    notifyItemInserted: function (index) {
                        callAdapterNative(view, 'nativeNotifyItemInserted', [data(), Number(index) || 0]);
                    },
                    notifyItemRemoved: function (index) {
                        callAdapterNative(view, 'nativeNotifyItemRemoved', [data(), Number(index) || 0]);
                    },
                    notifyItemChanged: function (index) {
                        callAdapterNative(view, 'nativeNotifyItemChanged', [data(), Number(index) || 0]);
                    },
                    notifyDataSetChanged: function () {
                        callAdapterNative(view, 'nativeNotifyDataSetChanged', [data()]);
                    },
                    notifyItemRangeInserted: function (start, count) {
                        callAdapterNative(view, 'nativeNotifyDataSetChanged', [data()]);
                    },
                    notifyItemRangeRemoved: function (start, count) {
                        callAdapterNative(view, 'nativeNotifyDataSetChanged', [data()]);
                    },
                    notifyItemRangeChanged: function (start, count) {
                        callAdapterNative(view, 'nativeNotifyDataSetChanged', [data()]);
                    }
                };
            };
            return function (event, handler) {
                event = String(event);
                if (name === 'off' || name === 'removeListener') {
                    extras.events.removeListener(event, handler);
                    return view;
                }
                if (typeof handler !== 'function') throw new TypeError('View.on/once 需要函数');
                bindViewEvent(view, extras, event);
                const emitterMethod = name === 'addListener' ? 'on' : name;
                extras.events[emitterMethod](event, handler);
                return view;
            };
        }
        // WebView 的 JS 门面与宿主 webId 一一对应；cleanup 时必须整体清掉，
        // 否则上一脚本的 page handler/console 监听会串到下一次运行。
        const webFacadeByHandle = Object.create(null);
        const webStateById = Object.create(null);
        function javaSession() {
            if (!realSession) realSession = globalThis.$autojs.java.findClass('com.stardust.autojs.engine.node24.NodeUiLocalSession');
            return realSession;
        }
        function resolveViewId(name) {
            const key = String(name);
            if (Object.prototype.hasOwnProperty.call(viewIdCache, key)) return viewIdCache[key];
            // show 已经返回过 name→id；先用它，避免每个 binding 属性多一次 Java viewId 调用。
            let id = Number(mainViews[key]) || 0;
            if (!id) id = Number(javaSession().viewId(key) || 0);
            if (!id) {
                // item 模板里的 id 不在 Activity 的 views 表里；Ids.parse 是 inflater
                // 设置 id 时的同一张全局表，按名字取回稳定数值。
                try {
                    id = Number(globalThis.$autojs.java.findClass(
                        'com.stardust.autojs.core.ui.inflater.util.Ids').parse(key));
                } catch (ignored) {
                    id = 0;
                }
            }
            viewIdCache[key] = id;
            return id;
        }

        function realViewBinding(view) {
            let bindings = realBindings.get(view);
            if (!bindings) {
                const names = function () {
                    return Array.from(new Set(Object.keys(mainViews).concat(Array.from(inlineBindingNames))))
                        .filter(function (name) { const id = resolveViewId(name); return id && view.findViewById(id); });
                };
                const childCache = Object.create(null);
                realBindingCaches.set(view, childCache);
                const getChild = function (name) {
                    if (Object.prototype.hasOwnProperty.call(childCache, name)) return childCache[name];
                    const id = resolveViewId(name);
                    const child = id ? view.findViewById(id) : null;
                    childCache[name] = child || null;
                    return child || null;
                };
                bindings = new Proxy({}, {get: function (_target, name) {
                    if (typeof name === 'symbol' || name === 'then' || name === 'toJSON') return undefined;
                    const child = getChild(String(name));
                    if (!child) return undefined;
                    return isWebViewObject(child) ? webViewFacade(child) : child;
                }, has: function (_target, name) {
                    if (typeof name !== 'string') return false;
                    return !!getChild(name);
                }, ownKeys: names, getOwnPropertyDescriptor: function (_target, name) {
                    if (typeof name === 'string' && (inlineBindingNames.has(name)
                            || Object.prototype.hasOwnProperty.call(mainViews, name))) {
                        if (getChild(name)) return {configurable: true, enumerable: true};
                    }
                }});
                realBindings.set(view, bindings);
            }
            return bindings;
        }

        function isWebViewObject(value) {
            if (!value) return false;
            try {
                const className = String(value.__class || '');
                return className.indexOf('WebView') >= 0;
            } catch (ignored) {
                return false;
            }
        }

        /** task-237：首帧后再执行重型原生 setup；view.post 保证 attach/布局后再排队，定时器兜底。 */
        function afterFirstFrame(view, callback) {
            if (typeof callback !== 'function') throw new TypeError('ui.afterFirstFrame 需要函数');
            let done = false;
            const run = function () {
                if (done) return;
                done = true;
                setTimeout(function () {
                    try { callback(); } catch (error) { console.error('[ui] afterFirstFrame 回调失败：', error); }
                }, 0);
            };
            try {
                view.post(new java.lang.Runnable({run: function () { run(); }}));
            } catch (ignored) {
                setTimeout(run, 32);
            }
            setTimeout(run, 250);
        }

        function realContentView() {
            return javaSession().getContentView();
        }

        function findRealViewByName(name) {
            if (name == null) return null;
            const root = javaSession().getContentView();
            if (!root) return null;
            if (typeof name === 'number') {
                const byId = root.findViewById(name);
                return byId ? (isWebViewObject(byId) ? webViewFacade(byId) : byId) : null;
            }
            const id = resolveViewId(String(name));
            if (!id) return null;
            const child = root.findViewById(id);
            if (!child) return null;
            return isWebViewObject(child) ? webViewFacade(child) : child;
        }

        function parseHostJson(text, fallback) {
            try {
                return JSON.parse(String(text == null ? '' : text));
            } catch (error) {
                return fallback;
            }
        }

        function webErrorText(error) {
            return String(error && error.message ? error.message : error);
        }

        /**
         * 真实 WebView 门面：loadUrl/on 走宿主主线程，jsBridge 走 Java <-> Node 的真实
         * 请求-响应链（不是 marker，也不是假 HTML）。
         */
        function webViewFacade(javaView) {
            const handle = String(javaView.__handle || '');
            if (handle && webFacadeByHandle[handle]) return webFacadeByHandle[handle];
            let webId;
            try {
                webId = Number(javaSession().webAttach(javaView, projectDir));
            } catch (error) {
                throw new Error('WebView 绑定失败：' + webErrorText(error));
            }
            if (!(webId > 0)) throw new Error('WebView 绑定失败：宿主没有返回 webId');
            const state = {
                view: javaView,
                webId: webId,
                events: Object.create(null),
                bridgeHandlers: Object.create(null),
                bridgeEvents: Object.create(null)
            };
            function ensureAlive() {
                if (uiClosed) throw new Error('Node UI 已关闭');
                return javaSession();
            }
            const facade = {
                __webView: true,
                webId: webId,
                loadUrl: function (url) {
                    ensureAlive().webLoadUrl(webId, String(url));
                    return facade;
                },
                reload: function () {
                    const current = parseHostJson(ensureAlive().webEval(webId,
                        'window.location.href', 3000), null);
                    ensureAlive().webLoadUrl(webId, String(current || 'about:blank'));
                    return facade;
                },
                on: function (eventName, handler) {
                    const name = String(eventName);
                    (state.events[name] = state.events[name] || []).push(handler);
                    return facade;
                },
                once: function (eventName, handler) {
                    const name = String(eventName);
                    const wrapped = function () {
                        facade.off(name, wrapped);
                        return handler.apply(null, arguments);
                    };
                    return facade.on(name, wrapped);
                },
                off: function (eventName, handler) {
                    const list = state.events[String(eventName)];
                    if (Array.isArray(list)) {
                        const index = list.indexOf(handler);
                        if (index >= 0) list.splice(index, 1);
                    }
                    return facade;
                },
                jsBridge: {
                    handle: function (channel, handler) {
                        if (typeof handler !== 'function') throw new TypeError('jsBridge.handle 需要函数');
                        state.bridgeHandlers[String(channel == null ? '' : channel)] = handler;
                        return facade.jsBridge;
                    },
                    on: function (eventName, handler) {
                        const name = String(eventName);
                        (state.bridgeEvents[name] = state.bridgeEvents[name] || []).push(handler);
                        return facade.jsBridge;
                    },
                    off: function (eventName, handler) {
                        const list = state.bridgeEvents[String(eventName)];
                        if (Array.isArray(list)) {
                            const index = list.indexOf(handler);
                            if (index >= 0) list.splice(index, 1);
                        }
                        return facade.jsBridge;
                    },
                    invoke: function (channel) {
                        const args = Array.prototype.slice.call(arguments, 1);
                        // Keep Node's event loop free: a page handler may call Node again.
                        return ensureAlive().webInvokePage.invoke(null,
                            [webId, String(channel), JSON.stringify(args), 10000], 'io').then(function (raw) {
                            const result = parseHostJson(raw, {ok: false, error: '页面返回非法 JSON：' + raw});
                            if (!result || result.ok !== true) {
                                throw new Error((result && result.error) || ('页面请求失败：' + channel));
                            }
                            return result.value;
                        });
                    },
                    send: function (eventName) {
                        const args = Array.prototype.slice.call(arguments, 1);
                        ensureAlive().webSend(webId, String(eventName), JSON.stringify(args));
                        return facade.jsBridge;
                    },
                    eval: function (script) {
                        const expression = '(function(){try{return {ok:true,value:(0,eval)('
                            + JSON.stringify(String(script)) + ')};}catch(e){return {ok:false,error:String(e)}}})()';
                        return ensureAlive().webEval.invoke(null, [webId, expression, 5000], 'io').then(function (raw) {
                            const result = parseHostJson(raw, null);
                            if (!result || !result.ok) throw new Error(result && result.error || '页面求值失败');
                            return result.value;
                        });
                    }
                },
                __state: state
            };
            if (handle) webFacadeByHandle[handle] = facade;
            webStateById[String(webId)] = state;
            return facade;
        }

        const markState = typeof WeakMap === 'function' ? new WeakMap() : null;
        // Lead 约束（task-69/70）：全局包代理替换延迟到真正进入 UI（setMainActivity/show 前），
        // 保存原始引用，close/出错/进程 exit 时恢复；不 require('ui') 的纯 Node 脚本不受影响。
        let originalGlobals = null;
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
                },
                ownKeys: function () { return Object.keys(state.views || {}); },
                getOwnPropertyDescriptor: function (_t, name) {
                    if (Object.prototype.hasOwnProperty.call(state.views || {}, name)) {
                        return {configurable: true, enumerable: true};
                    }
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
            // UI's facade must retain its represented Java class identity.
            // defineClass reads these own properties and generates a real JVM
            // subclass; returning the JS specification as a fake class is unsafe.
            Object.defineProperties(ctor, {
                __classRef: {value: true},
                __className: {value: name}
            });
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
                    if (key === '__classRef') return true;
                    if (key === '__className') return name;
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
            if (globalThis.__ajsHasGeneratedJavaClasses) return;
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
            // 脚本结束/异常退出时恢复（close() 也会主动恢复）；exit 回调里只做纯赋值，安全。
            if (!exitRestoreInstalled && typeof process !== 'undefined' && process && typeof process.once === 'function') {
                exitRestoreInstalled = true;
                try { process.once('exit', restoreJavaGlobals); } catch (error) { console.warn('[ui] 注册恢复钩子失败：', error); }
            }
        }

        /** 恢复全局包代理（可重复调用）。 */
        function restoreJavaGlobals() {
            if (!globalThis.__ajsUiGlobalsPatched) return;
            globalThis.__ajsUiGlobalsPatched = false;
            if (originalGlobals) {
                Object.keys(originalGlobals).forEach(function (name) {
                    try { globalThis[name] = originalGlobals[name]; } catch (ignored) { /* noop */ }
                });
            }
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
                                    // task-199：记录最近请求的工程布局名，供 floating_window.setView(真实 View)
                                    // 回退到对应 res/layout XML（主进程 overlay 按 XML inflate）。
                                    globalThis.__ajsLastLayoutName = resName;
                                    const value = hostCall({op: 'layoutId', name: resName});
                                    return typeof value.id === 'number' && value.id !== -1 ? value.id : 0;
                                }
                                const value = hostCall({op: 'resourceId', type: typeName, name: resName});
                                return typeof value.id === 'number' && value.id !== -1 ? value.id : 0;
                            } catch (error) {
                                // Android treats 0 as "no resource". A -1 icon
                                // is loaded later by AppCompat on the UI thread
                                // and crashes outside the script's try/catch.
                                return 0;
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
            return !!(descriptor && (descriptor.set || descriptor.writable));
        }

        class Widget {
            constructor() {
                this.attrs = {};
                this._view = null;
                this.__ajsNodeUiWidget = true;
            }
            get view() {
                if (!this._view) throw new Error('widget is not mounted');
                return this._view;
            }
            created() {}
            get initialAttributes() { return {}; }
            mounted() {}
            hasAttribute(name) {
                return !!findPropertyDescriptor(this, 'attr$' + String(name))
                    || Object.prototype.hasOwnProperty.call(this.attrs, name);
            }
            getAttribute(name, fallback) {
                const descriptor = findPropertyDescriptor(this, 'attr$' + String(name));
                if (descriptor && (descriptor.get || !descriptor.set)) {
                    return Reflect.get(this, 'attr$' + String(name));
                }
                if (Object.prototype.hasOwnProperty.call(this.attrs, name)) return this.attrs[name];
                if (typeof fallback !== 'function') throw new TypeError('Widget.getAttribute 缺少默认读取函数');
                return fallback();
            }
            setAttribute(name, value, fallback) {
                const descriptor = findPropertyDescriptor(this, 'attr$' + String(name));
                if (descriptor && descriptor.set) {
                    descriptor.set.call(this, value);
                    this.attrs[name] = value;
                } else if (descriptor && descriptor.writable) {
                    Reflect.set(this, 'attr$' + String(name), value);
                    this.attrs[name] = value;
                } else if (!descriptor && Object.prototype.hasOwnProperty.call(this.attrs, name)) {
                    this.attrs[name] = value;
                } else {
                    if (typeof fallback !== 'function') throw new TypeError('Widget.setAttribute 缺少默认写入函数');
                    fallback(value);
                }
            }
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
            if (view == null) return undefined;
            try {
                const extras = realViewExtras.get(view);
                if (extras) return extras.data['autojs.widget'];
                const widget = view.__ajsWidget;
                return widget && widget.__ajsNodeUiWidget ? widget : undefined;
            } catch (error) { return undefined; }
        };
        Widget.create = function (name) {
            const Ctor = widgetRegistry[normalizeWidgetTag(name)];
            return Ctor ? new Ctor() : undefined;
        };

        // Read element boundaries without splitting quoted '>' characters, comments or CDATA.
        function xmlElements(xml) {
            const roots = [], stack = [];
            const tokens = /<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>|<(?:(?:"[^"]*"|'[^']*'|[^'">])*)>/g;
            let match;
            while ((match = tokens.exec(xml))) {
                const text = match[0];
                if (/^<[!?]/.test(text)) continue;
                if (/^<\//.test(text)) {
                    const node = stack.pop();
                    const tag = /^<\/\s*([^\s>]+)/.exec(text);
                    if (!node || !tag || tag[1] !== node.tag) throw new Error('XML 标签未匹配：' + text);
                    node.close = match.index; node.end = tokens.lastIndex;
                    continue;
                }
                const opening = /^<\s*([^\s/>]+)([\s\S]*?)(\/?>)$/.exec(text);
                if (!opening) continue;
                const node = {tag: opening[1], attrs: opening[2], start: match.index,
                    openEnd: tokens.lastIndex, end: tokens.lastIndex, children: [], selfClosing: /\/\s*>$/.test(text)};
                (stack.length ? stack[stack.length - 1].children : roots).push(node);
                if (!node.selfClosing) stack.push(node);
            }
            if (stack.length) throw new Error('XML 标签未关闭：' + stack[stack.length - 1].tag);
            return roots;
        }

        function appendWidgetContent(xml, children, slotName) {
            if (!children || !children.trim()) return xml;
            const roots = xmlElements(xml);
            if (roots.length !== 1) throw new Error('Widget.render() 必须包含一个根节点');
            const queue = roots.slice();
            let target = roots[0], found = false;
            while (queue.length) {
                const node = queue.shift();
                const attrs = parseXmlAttributes(node.attrs);
                const id = String(attrs.id || attrs['android:id'] || '').replace(/^@\+?id\//, '');
                if (slotName ? id === slotName : (id === 'content' || id === 'slot')) {
                    target = node; found = true; break;
                }
                queue.push.apply(queue, node.children);
            }
            if (slotName && !found) throw new Error('Widget contentSlot 不存在：' + slotName);
            if (target.selfClosing) {
                const open = xml.slice(target.start, target.openEnd).replace(/\/\s*>$/, '>');
                return xml.slice(0, target.start) + open + children + '</' + target.tag + '>' + xml.slice(target.end);
            }
            return xml.slice(0, target.close) + children + xml.slice(target.close);
        }

        let nextWidgetId = 1;
        function expandOneWidget(tag, attrText, records, children) {
            const Ctor = widgetRegistry[tag];
            if (typeof Ctor !== 'function') return '<' + tag + attrText + (selfClosing ? '/>' : '>');
            const attrs = parseXmlAttributes(attrText);
            const widget = new Ctor();
            if (typeof widget.created === 'function') widget.created();
            // Subclass fields/created() must be initialized before reading defaults.
            const defaults = Object.assign({}, widget.initialAttributes || {}, widget.attrs || {});
            Object.keys(defaults).forEach(function (name) {
                const descriptor = findPropertyDescriptor(widget, 'attr$' + name);
                if (descriptor && Object.prototype.hasOwnProperty.call(descriptor, 'value')
                        && descriptor.value !== undefined
                        && !Object.prototype.hasOwnProperty.call(widget.attrs, name)) delete defaults[name];
            });
            widget.attrs = Object.assign({}, defaults, attrs);
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
            const renderedAttrs = parseXmlAttributes(rootAttrs);
            const idValue = String(attrs.id || attrs['android:id'] || renderedAttrs.id || renderedAttrs['android:id']
                || ('__node_widget_' + nextWidgetId++)).replace(/^@\+?id\//, '');
            if ((attrs.id || attrs['android:id']) && /(?:^|\s)(?:android:)?id\s*=/.test(rootAttrs)) {
                rootAttrs = rootAttrs.replace(/((?:^|\s)(?:android:)?id\s*=)("[^"]*"|'[^']*')/,
                    '$1"' + xmlEscape(idValue) + '"');
            }
            if (idValue && !/\bid\s*=/.test(rootAttrs)) {
                extra += ' id="' + xmlEscape(idValue) + '"';
            }
            function applyNativeAttribute(name, value, override) {
                if (name === 'id' || name === 'android:id' || value == null) return;
                if (hasWidgetAttrSetter(widget, name)) return;
                const pattern = new RegExp('((?:^|\\s)(?:android:)?'
                    + escapeRegExp(name.replace(/^android:/, '')) + '\\s*=)("[^"]*"|\'[^\']*\')');
                if (pattern.test(rootAttrs)) {
                    if (override) rootAttrs = rootAttrs.replace(pattern,
                        (_match, prefix) => prefix + '"' + xmlEscape(value) + '"');
                } else rootAttrs += ' ' + name + '="' + xmlEscape(value) + '"';
            }
            Object.keys(defaults).forEach(function (name) {
                applyNativeAttribute(name, defaults[name], false);
            });
            Object.keys(attrs).forEach(function (name) {
                applyNativeAttribute(name, attrs[name], true);
            });
            const newRoot = rootMatch[1] + rootMatch[2] + rootAttrs + extra + rootMatch[4];
            const expanded = appendWidgetContent(
                rendered.replace(rendered.slice(0, rootMatch[0].length), newRoot), children,
                widget.contentSlot ? String(widget.contentSlot) : '');
            records.push({ tag: tag, id: idValue, attrs: attrs, widget: widget, rendered: expanded });
            return expanded;
        }

        function expandCustomWidgetsInXml(xml) {
            const records = [];
            function expand(input, depth) {
                if (depth > 32) throw new Error('Widget 嵌套或 render() 递归超过 32 层');
                function render(node) {
                    let children = '', cursor = node.openEnd;
                    node.children.forEach(function (child) {
                        children += input.slice(cursor, child.start) + render(child); cursor = child.end;
                    });
                    if (!node.selfClosing) children += input.slice(cursor, node.close);
                    const tag = normalizeWidgetTag(node.tag);
                    if (widgetRegistry[tag]) return expand(expandOneWidget(tag, node.attrs, records, children), depth + 1);
                    return input.slice(node.start, node.openEnd) + children
                        + (node.selfClosing ? '' : input.slice(node.close, node.end));
                }
                let output = '', cursor = 0;
                xmlElements(input).forEach(function (node) {
                    output += input.slice(cursor, node.start) + render(node); cursor = node.end;
                });
                return output + input.slice(cursor);
            }
            const output = expand(String(xml || ''), 0);
            const ids = /(?:\s)(?:android:)?id\s*=\s*["'](?:@\+?id\/)?([A-Za-z_][A-Za-z0-9_]*)["']/g;
            let id;
            while ((id = ids.exec(output))) inlineBindingNames.add(id[1]);
            return {xml: output, records: records};
        }

        function attachCustomWidgets(records, views, root) {
            (records || []).forEach(function (record) {
                const widget = record.widget;
                const idName = record.id;
                if (!widget || !idName) return;
                const viewId = views && typeof views[idName] === 'number' ? views[idName] : 0;
                const key = viewId > 0 ? ('id:' + viewId) : ('name:' + idName);
                let binding = root ? root.findViewById(resolveViewId(idName))
                    : (realUiMode ? findRealViewByName(idName) : viewBindings[key]);
                if (!binding) {
                    binding = markerObject('View', {viewName: idName, viewId: viewId, views: views});
                    viewBindings[key] = binding;
                }
                if (realUiMode || root) {
                    const extras = viewExtras(binding);
                    extras.widget = widget;
                    extras.data['autojs.widget'] = widget;
                }
                else binding.__ajsWidget = widget;
                if (widget.__ajsNodeUiWidget) widget._view = binding;
                else widget.view = binding;
                Object.keys(widget.attrs || {}).forEach(function (name) {
                    if (name === 'id') return;
                    const setterName = 'attr$' + name;
                    const descriptor = findPropertyDescriptor(widget, setterName);
                    if (!descriptor || (!descriptor.set && !descriptor.writable)) return;
                    try {
                        widget[setterName] = widget.attrs[name];
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
                {
                    return new Proxy(this, {get: function (target, prop, receiver) {
                        if (prop === '__ajsJavaObject__') return realActivity != null;
                        if (prop === '__handle') return realActivity && realActivity.__handle;
                        if (prop in target || typeof prop === 'symbol') return Reflect.get(target, prop, receiver);
                        if (!realActivity) return undefined;
                        return realActivity[prop];
                    }});
                }
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
            // task-197：Pro/AndroidX 示例会用 insetsController 设置系统栏外观；第一阶段给 no-op 兼容。
            get insetsController() {
                if (realActivity) return globalThis.$autojs.java.findClass('androidx.core.view.WindowCompat')
                    .getInsetsController(realActivity.getWindow(), realActivity.getWindow().getDecorView());
                return markerObject('WindowInsetsControllerCompat');
            }
            getWindow() { return realActivity ? realActivity.getWindow() : markerObject('Window'); }
            getTheme() { return realActivity ? realActivity.getTheme() : markerObject('Theme'); }
            /** 传统界面示例用 this.contentView 作 Snackbar 的父 View。 */
            get contentView() {
                if (realUiMode) return realContentView();
                return markerObject('View', {views: mainViews});
            }
            setSupportActionBar(toolbar) { return realActivity ? realActivity.setSupportActionBar(toolbar) : markerObject('ActionBar'); }
            getMenuInflater() { return realActivity ? realActivity.getMenuInflater() : markerObject('MenuInflater'); }
            getApplicationContext() { return realActivity ? realActivity.getApplicationContext() : markerObject('Context'); }
            /**
             * 参考示例的 WebActivity 用 this.finish()（LoadingActivity 等）关闭 Node UI。
             * 不依赖真实 Activity 代理，inline/非编译资源模式也能工作。
             */
            finish() {
                // 只关闭仍然属于当前 Node UI 的实例；旧 LoadingActivity 晚到的 finish()
                // 不能把 startActivity 打开的 WebActivity 关掉。
                if (mainActivity === this) close();
                return this;
            }
            recreate() {
                if (mainActivity !== this || uiClosed || !realUiMode) throw new Error('当前 Activity 已关闭');
                const xml = typeof this.layoutXml === 'string' ? this.layoutXml : '';
                const expanded = xml ? expandCustomWidgetsInXml(xml) : {xml: '', records: []};
                pendingWidgets = expanded.records;
                javaSession().recreate(expanded.xml);
            }
            isFinishing() { return !realUiMode || uiClosed; }
            startActivity(Ctor, options) { return startActivity(Ctor, options); }
        }

        // ------------------------------------------------------------------
        // 宿主 show 返回的 views / binding / 事件
        // ------------------------------------------------------------------
        let mainActivity = null;
        let mainViews = {};
        // task-236：name→viewId 与 binding 子 View 句柄缓存；binding 属性访问不再每次走 Java 桥。
        let viewIdCache = Object.create(null);
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
            if (realUiMode) {
                // 编译资源布局返回真实 Java View 代理本身（保留 M3 身份断言）；inline
                // 布局才用薄代理补 Pro 的 contentView.findView(name) 语义。
                return realInlineMode ? realContentView() : javaSession().getContentView();
            }
            const views = (event && event.views) || mainViews || {};
            globalThis.__ajsUiViews = views || {};
            return markerObject('View', {views: views, rootId: (event && event.rootId) || 0});
        }

        /**
         * 共享的 UI 生命周期清理（destroy 回调之后 / 显式 ui.close / 轮询失败）：
         * 清掉 120ms 轮询、释放 $autojs.keepRunning 的保活、清主界面引用并恢复 Java 全局代理。
         * 可重复调用；destroy 回调里若打开了替换界面（mainActivity 已变），不能清掉新界面。
         */
        function cleanupUiLifecycle(instance, suppressLifecycle) {
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
            realActivity = null;
            realImageLoader = null;
            if (globalThis.__ajsBindJavaView === realViewBinding) delete globalThis.__ajsBindJavaView;
            if (globalThis.__ajsJavaViewMember === realViewMember) delete globalThis.__ajsJavaViewMember;
            realViewExtras = new WeakMap();
            mainViews = {};
            viewIdCache = Object.create(null);
            Object.keys(clickStates).forEach(function (id) { delete clickStates[id]; });
            Object.keys(viewBindings).forEach(function (key) { delete viewBindings[key]; });
            Object.keys(webFacadeByHandle).forEach(function (key) { delete webFacadeByHandle[key]; });
            Object.keys(webStateById).forEach(function (key) { delete webStateById[key]; });
            restoreJavaGlobals();
            if (!suppressLifecycle && !suppressAllDestroyed) emitActivityLifecycle('all_activities_destroyed');
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

        // ------------------------------------------------------------------
        // WebView 事件（与生命周期事件共用 120ms pollEvents 队列）
        // ------------------------------------------------------------------
        function dispatchWebEvent(event) {
            const webId = String(event && event.webId);
            const state = webStateById[webId];
            if (!state) return;
            const type = String(event.type || '');
            if (type === 'webview_console') {
                const message = {
                    sourceId: function () { return String(event.sourceId == null ? '' : event.sourceId); },
                    lineNumber: function () { return Number(event.lineNumber) || 0; },
                    message: function () { return String(event.message == null ? '' : event.message); },
                    level: function () { return String(event.level || ''); }
                };
                emitWebLocal(state, 'console_message', [message]);
                return;
            }
            if (type === 'webview_page_started' || type === 'webview_page_finished') {
                emitWebLocal(state, type === 'webview_page_started' ? 'page_started' : 'page_finished',
                    [{url: String(event.url || '')}]);
                return;
            }
            if (type === 'webview_event') {
                const args = parseHostJson(event.argsJson, []);
                emitWebLocal(state, String(event.name || ''), Array.isArray(args) ? args : [args]);
                return;
            }
            if (type === 'webview_request') {
                handleWebRequest(state, event);
            }
        }

        function emitWebLocal(state, eventName, args) {
            const listeners = state.events[eventName];
            if (Array.isArray(listeners)) {
                const event = {name: eventName, arguments: args || [], consumed: false};
                listeners.slice().forEach(function (handler) {
                    try { handler.apply(null, [event].concat(args || [])); }
                    catch (error) { console.error('[ui] webview 事件处理异常：', error); }
                });
            }
            const bridgeListeners = state.bridgeEvents[eventName];
            if (Array.isArray(bridgeListeners)) {
                bridgeListeners.slice().forEach(function (handler) {
                    try { handler.apply(null, [{name: eventName, arguments: args || []}].concat(args || [])); }
                    catch (error) { console.error('[ui] jsBridge 事件处理异常：', error); }
                });
            }
        }

        function respondWebRequest(state, requestId, ok, value) {
            let payload;
            try {
                payload = ok ? JSON.stringify(value === undefined ? null : value)
                    : JSON.stringify({error: String(value == null ? '页面请求失败' : value)});
            } catch (error) {
                payload = JSON.stringify({error: '页面返回值无法序列化：' + webErrorText(error)});
                ok = false;
            }
            try {
                javaSession().webBridgeRespond(state.webId, String(requestId || ''), !!ok, payload);
            } catch (error) {
                console.warn('[ui] WebView 响应发送失败（界面可能已关闭）：', error);
            }
        }

        function handleWebRequest(state, event) {
            const channel = String(event.channel || '');
            const handler = state.bridgeHandlers[channel] || state.bridgeHandlers[''];
            const args = parseHostJson(event.argsJson, []);
            const requestArgs = Array.isArray(args) ? args : [args];
            const requestEvent = {name: channel, arguments: requestArgs, consumed: false};
            if (typeof handler !== 'function') {
                respondWebRequest(state, event.requestId, false, 'no handler for action: ' + channel);
                return;
            }
            let result;
            try {
                result = handler.apply(null, [requestEvent].concat(requestArgs));
            } catch (error) {
                respondWebRequest(state, event.requestId, false, webErrorText(error));
                return;
            }
            Promise.resolve(result).then(function (value) {
                respondWebRequest(state, event.requestId, true, value);
            }, function (error) {
                respondWebRequest(state, event.requestId, false, webErrorText(error));
            });
        }


        // ------------------------------------------------------------------
        // ui.activityLifecycle：至少覆盖 all_activities_destroyed（Pro 示例用它退出进程）
        // ------------------------------------------------------------------
        const activityLifecycleHandlers = Object.create(null);

        function emitActivityLifecycle(name) {
            const listeners = activityLifecycleHandlers[name];
            if (!Array.isArray(listeners)) return;
            listeners.slice().forEach(function (handler) {
                try {
                    handler();
                } catch (error) {
                    console.error('[ui] activityLifecycle.' + name + ' 回调异常：', error);
                }
            });
        }

        function onActivityLifecycle(name, handler, once) {
            if (typeof handler !== 'function') throw new TypeError('activityLifecycle.on 需要函数');
            const key = String(name);
            const listeners = activityLifecycleHandlers[key] || (activityLifecycleHandlers[key] = []);
            if (once) {
                const wrapped = function () {
                    const index = listeners.indexOf(wrapped);
                    if (index >= 0) listeners.splice(index, 1);
                    handler();
                };
                listeners.push(wrapped);
            } else {
                listeners.push(handler);
            }
        }

        const activityLifecycle = {
            on: function (name, handler) {
                onActivityLifecycle(name, handler, false);
                return activityLifecycle;
            },
            once: function (name, handler) {
                onActivityLifecycle(name, handler, true);
                return activityLifecycle;
            },
            off: function (name, handler) {
                const listeners = activityLifecycleHandlers[String(name)];
                if (Array.isArray(listeners)) {
                    const index = listeners.indexOf(handler);
                    if (index >= 0) listeners.splice(index, 1);
                }
                return activityLifecycle;
            }
        };

        function dispatch(event) {
            const instance = mainActivity;
            if (!instance || !event) return;
            const name = String(event.type || '');
            if (realUiMode && name === 'create') {
                // recreate replaces the Android object while keeping the JS Activity/session alive.
                realActivity = javaSession().getActivity();
                if (instance.initialStatusBar) javaSession().configureStatusBar(JSON.stringify(instance.initialStatusBar));
            }
            if (event.views) { mainViews = event.views; viewIdCache = Object.create(null); }
            if (realUiMode && name === 'content_view_set' && pendingWidgets.length) {
                attachCustomWidgets(pendingWidgets, mainViews);
                pendingWidgets = [];
            }
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
            events.forEach(function (event) {
                if (event && String(event.type || '').indexOf('webview_') === 0) {
                    dispatchWebEvent(event);
                } else if (realUiMode && event) {
                    // Java's main-thread Runnable supplies the callback's host-call
                    // context. V8 remains on Node; nested View/Looper calls run on main.
                    uiRunOnUiThread(function () { dispatch(event); }).catch(function (error) {
                        console.error('[ui] Activity 回调调度失败：', error);
                    });
                } else {
                    dispatch(event);
                }
            });
        }

        function loadAndroidResources() {
            return Promise.resolve().then(function () {
                const result = hostCall({op: 'loadAndroidResources'});
                resourceCompiled = result.compiled === true;
                if (resourceCompiled && typeof globalThis.__ajsEnableJavaCallbacks === 'function') {
                    realUiMode = true;
                }
                return result;
            });
        }

        function layoutOf(rawLayout) {
            // Android package ids >= 0x80 are signed negative int32 values.
            if (typeof rawLayout === 'number' && Number.isInteger(rawLayout) && rawLayout !== 0) {
                return {layoutId: rawLayout};
            }
            if (rawLayout && typeof rawLayout === 'object' && rawLayout.__ajsLayout) {
                const parts = String(rawLayout.__ajsLayout).split('/');
                return {type: parts[0] || 'layout', name: parts[parts.length - 1]};
            }
            return {name: String(rawLayout || '')};
        }

        function setMainActivity(Ctor, options) {
            if (typeof Ctor !== 'function') {
                throw new TypeError('ui.setMainActivity 需要一个 Activity 子类');
            }
            // Advanced projects may call androidContext.loadResources() directly, without ui.loadAndroidResources().
            if (!resourceCompiled) {
                try { resourceCompiled = hostCall({op: 'resourceState'}).compiled === true; }
                catch (_) { /* Older hosts still support ui.loadAndroidResources(). */ }
                if (resourceCompiled && typeof globalThis.__ajsEnableJavaCallbacks === 'function') realUiMode = true;
            }
            // 进入 UI 前按需切换包代理；inline 布局会走真实 Activity，发现后立即恢复真实 Java 包。
            if (!realUiMode) patchJavaGlobals();
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
                // 非编译资源 + layoutXml 也可以走真实 Activity（不依赖 project-res.apk）；
                // 只有原生 layoutId 在 compiled=false 时继续明确 unsupported（overlay/marker）。
                const callbacksAvailable = typeof globalThis.__ajsEnableJavaCallbacks === 'function';
                if (callbacksAvailable && (!!layoutXml || resourceCompiled)) {
                    if (!realUiMode) {
                        restoreJavaGlobals();
                        realUiMode = true;
                    }
                    realInlineMode = !!layoutXml;
                }
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
            if (options && options.flags != null) {
                request.flags = Number(options.flags) || 0;
            }
            if (realUiMode) {
                globalThis.__ajsEnableJavaCallbacks();
                globalThis.__ajsBindJavaView = realViewBinding;
                globalThis.__ajsJavaViewMember = realViewMember;
                request.realJava = true;
                request.backHandled = instance.onBackPressed !== Activity.prototype.onBackPressed;
            }
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
                if (realUiMode) {
                    realActivity = javaSession().getActivity();
                    if (instance.initialStatusBar) javaSession().configureStatusBar(JSON.stringify(instance.initialStatusBar));
                }
            } catch (error) {
                restoreJavaGlobals();
                throw error;
            }
            mainActivity = instance;
            mainViews = result.views && typeof result.views === 'object' ? result.views : {};
            viewIdCache = Object.create(null);
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
            if (realUiMode) {
                const callbacks = globalThis.$autojs.java.findClass('com.stardust.autojs.engine.node24.NodeUiLocalSession$ActivityCallbacks');
                javaSession().setActivityCallbacks(new callbacks({
                    onCreateOptionsMenu: function (menu) { return !!instance.onCreateOptionsMenu(menu); },
                    onOptionsItemSelected: function (item) { return !!instance.onOptionsItemSelected(item); }
                }));
            }
            return {instance: instance, layout: layout, host: result, real: realUiMode, inline: realInlineMode};
        }

        /**
         * ui.startActivity(WebActivity,{flags})：单 Node Activity 会话模型下先关闭当前本地
         * Activity，再启动新的 NodeUiLocalActivity；旧 Activity 的 destroy 不会清掉新会话。
         * 返回 Promise<instance>，同时保留 Pro 示例的 fire-and-forget 用法。
         */
        function startActivity(Ctor, options) {
            if (typeof Ctor !== 'function') {
                throw new TypeError('ui.startActivity 需要一个 Activity 子类');
            }
            if (!realUiMode) {
                throw new Error('ui.startActivity 需要真实 Node Activity（请先 setMainActivity / loadAndroidResources）');
            }
            return Promise.resolve().then(async function () {
                suppressAllDestroyed = true;
                try {
                    if (mainActivity != null) {
                        close({suppressLifecycle: true});
                        await delayUntil(function () {
                            try { return !javaSession().isShowing(); } catch (error) { return true; }
                        }, 2500);
                    }
                } finally {
                    // 旧 Activity 的关闭已经处理完；新 Activity 随后的 close 必须照常发
                    // all_activities_destroyed，不能被这里的过渡标记吞掉。
                    suppressAllDestroyed = false;
                }
                const result = setMainActivity(Ctor, options || {});
                return result ? result.instance : null;
            });
        }

        function delayUntil(predicate, timeoutMs) {
            return new Promise(function (resolve) {
                const deadline = Date.now() + Math.max(0, Number(timeoutMs) || 0);
                (function check() {
                    let ready = false;
                    try { ready = !!predicate(); } catch (error) { ready = true; }
                    if (ready || Date.now() >= deadline) {
                        resolve(ready);
                        return;
                    }
                    setTimeout(check, 30);
                })();
            });
        }

        function close(options) {
            const instance = mainActivity;
            const suppress = !!(options && options.suppressLifecycle);
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
            cleanupUiLifecycle(instance, suppress);
        }

        // task-134：第二批 UI API（基于现有 host 能力，能真实工作的先落地；
        // setContentView/inflate/layout 依赖宿主 View 句柄，先给明确中文错误并在 probe 中标记）。
        // JS stays on the Node thread. Reflected View operations are marshalled to
        // Android main by the host; isUiThread reports the actual Looper, never a logical scope.
        function uiRun(fn) {
            if (typeof fn !== 'function') { throw new TypeError('ui.run 需要一个函数'); }
            return fn();
        }
        function uiPost(fn, delay) {
            if (typeof fn !== 'function') { throw new TypeError('ui.post 需要一个函数'); }
            return setTimeout(function () { try { fn(); } catch (error) { console.error('[ui] post 回调异常', error); } },
                Math.max(0, Number(delay) || 0));
        }
        function uiRunOnUiThread(fn) {
            if (typeof fn !== 'function') return Promise.reject(new TypeError('ui.runOnUiThread 需要一个函数'));
            return new Promise(function (resolve, reject) {
                // JS stays on Node's thread. Holding Android's main thread in a
                // synchronous Runnable for a large JS page build can ANR and
                // hit the JNI callback deadline. Marshal each Java operation,
                // leaving main-Looper turns available for input and drawing.
                const schedule = typeof setImmediate === 'function' ? setImmediate : setTimeout;
                schedule(function () {
                    try {
                        // Fix the host-worker identity before entering its
                        // thread-local scope (fn may install Java callbacks).
                        globalThis.__ajsEnableJavaCallbacks();
                        hostCall({kind: 'java', op: 'beginUiScope'});
                        let value, layoutRoot = null;
                        try {
                            // Building an attached tree one bridge call at a
                            // time otherwise repeatedly measures intermediate
                            // trees. Preserve a caller's existing suppression.
                            if (realUiMode) {
                                const root = javaSession().getContentView();
                                if (root && typeof root.isLayoutSuppressed === 'function'
                                        && !root.isLayoutSuppressed()) {
                                    root.suppressLayout(true);
                                    layoutRoot = root;
                                }
                            }
                            value = fn();
                        } finally {
                            try { if (layoutRoot) layoutRoot.suppressLayout(false); }
                            finally { hostCall({kind: 'java', op: 'endUiScope'}); }
                        }
                        resolve(value);
                    } catch (error) { reject(error); }
                }, 0);
            });
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
            if (realUiMode) {
                const activity = requireRealActivity();
                const id = typeof idOrName === 'number' ? idOrName : javaSession().viewId(String(idOrName));
                if (!id) return null;
                const view = activity.findViewById(id);
                if (!view) return null;
                return isWebViewObject(view) ? webViewFacade(view) : view;
            }
            var id = typeof idOrName === 'number' ? idOrName : 0;
            var name = typeof idOrName === 'string' ? idOrName : '';
            return uiWrapHandle(uiRequestView('findViewById', {id: id, name: name}));
        }
        function uiSetContentView(layoutOrView) {
            if (!realUiMode && typeof layoutOrView === 'string' && layoutOrView.trim().charAt(0) === '<'
                    && typeof globalThis.__ajsEnableJavaCallbacks === 'function') {
                class InlineActivity extends Activity { get layoutXml() { return layoutOrView; } }
                setMainActivity(InlineActivity);
                return realContentView();
            }
            if (realUiMode) {
                requireRealActivity();
                const view = layoutOrView && layoutOrView.__ajsJavaObject__ === true
                    ? layoutOrView : uiInflate(layoutOrView);
                javaSession().setContentView(view);
                return view;
            }
            var name = '';
            if (typeof layoutOrView === 'string') { name = layoutOrView; }
            else if (layoutOrView && layoutOrView.__layoutName) { name = layoutOrView.__layoutName; }
            return uiWrapHandle(uiRequestView('setContentView', {name: name}));
        }
        function uiInflate(layoutName, parent, attachToParent) {
            if (typeof layoutName === 'string' && layoutName.trim().charAt(0) === '<') {
                if (typeof globalThis.__ajsEnableJavaCallbacks !== 'function') {
                    throw new Error('XML 字符串 inflate 需要原生 Node Java 桥');
                }
                globalThis.__ajsEnableJavaCallbacks();
                hostCall({op: 'ping'}); // seed application Context for offscreen inflation
                const expanded = expandCustomWidgetsInXml(layoutName);
                const view = javaSession().inflateXml(expanded.xml, parent || null, !!attachToParent);
                attachCustomWidgets(expanded.records, mainViews, view);
                return view;
            }
            if (realUiMode) {
                const activity = requireRealActivity();
                const layout = layoutOf(layoutName);
                const id = layout.layoutId || (layout.name && R.layout[layout.name]);
                if (!id) throw new Error('未找到布局资源：' + String(layoutName));
                return activity.getLayoutInflater().inflate(id, parent || null, !!attachToParent);
            }
            return uiWrapHandle(uiRequestView('inflate', {name: String(layoutName || '')}));
        }
        /**
         * 批量 inflate 子 View：把 count 次 inflate 合并成一次宿主调用（减少 JNI/回调与逐条 render）。
         *
         * 典型用途：RecyclerView item / 重复卡片 / 列表行等需要一次性生成多个同结构 View 的场景。
         * 返回普通 JS 数组，元素是真实 Java View。
         *
         * @param {number|string} layoutOrXml ui.R.layout.* 数字 id（调 inflateLayoutBatch）
         *        或 XML 字符串（调 inflateXmlBatch；需以 &lt; 开头，自定义 ui.Widget 标签不支持）
         * @param {number} count 生成数量，1..256
         * @param {object|null} [parent] 可选 ViewGroup（可作为 attach 目标；null 表示不挂到父容器）
         * @returns {Array<object>} 真实 Java View 的 JS 数组
         *
         * @example
         *   const views = ui.inflateBatch(ui.R.layout.grid_item, 12, parent);
         *   const rows = ui.inflateBatch('&lt;TextView .../&gt;', 32, null);
         */
        function uiInflateBatch(layoutOrXml, count, parent) {
            const n = Number(count);
            if (!Number.isInteger(n) || n < 1 || n > 256) {
                throw new RangeError('ui.inflateBatch count 必须是 1..256 的整数，收到：' + String(count));
            }
            if (parent != null && typeof parent !== 'object') {
                throw new TypeError('ui.inflateBatch parent 必须是 ViewGroup 或 null');
            }
            const parentArg = parent == null ? null : parent;
            const isLayoutId = typeof layoutOrXml === 'number';
            const isXml = typeof layoutOrXml === 'string';
            if (!isLayoutId && !isXml) {
                throw new TypeError('ui.inflateBatch 第一个参数必须是 ui.R.layout.* 数字 id 或 XML 字符串，收到：'
                        + Object.prototype.toString.call(layoutOrXml));
            }
            if (isLayoutId && (!Number.isInteger(layoutOrXml) || layoutOrXml <= 0)) {
                throw new TypeError('ui.inflateBatch 布局 id 必须是正整数（如 ui.R.layout.grid_item；Java 侧要求 > 0），收到：'
                        + String(layoutOrXml));
            }
            if (isXml) {
                const xml = layoutOrXml;
                if (!xml || xml.trim().length === 0) {
                    throw new TypeError('ui.inflateBatch XML 字符串不能为空');
                }
                if (xml.trim().charAt(0) !== '<') {
                    throw new TypeError('ui.inflateBatch 的字符串参数必须是 XML（以 "<" 开头）；布局请传 ui.R.layout.* 数字 id');
                }
                const registeredTags = Object.keys(widgetRegistry);
                for (let i = 0; i < registeredTags.length; i++) {
                    const tag = registeredTags[i];
                    if (new RegExp('<\\s*' + escapeRegExp(tag) + '(?=\\s|/|>)', 'i').test(xml)) {
                        throw new Error('ui.inflateBatch 的 XML 暂不支持 ui.Widget 自定义标签：' + tag + '（请改用普通控件标签）');
                    }
                }
            }
            if (typeof globalThis.__ajsEnableJavaCallbacks !== 'function') {
                throw new Error('ui.inflateBatch 需要原生 Node Java 桥（当前 Node24 宿主未启用 Java 回调）');
            }
            // 与 ui.inflate 的 XML 路径一致：先 seed application Context，再调用宿主批量 inflate。
            try { globalThis.__ajsEnableJavaCallbacks(); } catch (ignored) { }
            try { hostCall({op: 'ping'}); } catch (ignored) { }
            let batch;
            try {
                if (isLayoutId) {
                    if (typeof javaSession().inflateLayoutBatch !== 'function') {
                        throw new Error('宿主暂未提供 NodeUiLocalSession.inflateLayoutBatch()（Java 侧批量 inflate 尚未合入/编译）');
                    }
                    batch = javaSession().inflateLayoutBatch(layoutOrXml, n, parentArg);
                } else {
                    if (typeof javaSession().inflateXmlBatch !== 'function') {
                        throw new Error('宿主暂未提供 NodeUiLocalSession.inflateXmlBatch()（Java 侧批量 inflate 尚未合入/编译）');
                    }
                    batch = javaSession().inflateXmlBatch(layoutOrXml, n, parentArg);
                }
            } catch (error) {
                const wrapped = new Error('ui.inflateBatch 失败（' + (isLayoutId ? 'layout' : 'xml')
                        + ' count=' + n + '）：' + (error && error.message ? error.message : String(error)));
                wrapped.cause = error;
                throw wrapped;
            }
            if (batch == null) {
                throw new Error('ui.inflateBatch 宿主返回空（Java 侧 inflate*Batch 未实现或失败）');
            }
            const views = Array.prototype.slice.call(batch);
            if (!Array.isArray(views) || views.length !== n) {
                throw new Error('ui.inflateBatch 宿主返回数量不符：期望 ' + n + '，实际 '
                        + (views && views.length != null ? views.length : '未知'));
            }
            if (n > 0 && !(views[0] && views[0].__ajsJavaObject__ === true)) {
                throw new Error('ui.inflateBatch 返回的元素不是 Java View');
            }
            return views;
        }

        function requireRealActivity() {
            if (!realActivity) throw new Error('当前没有运行中的 Node UI Activity');
            return realActivity;
        }
        function uiCurrentActivity() { return realUiMode ? realActivity : markerObject('Activity'); }
        function uiGetWindow() {
            if (realUiMode) return requireRealActivity().getWindow();
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

        globalThis.__ajsBindJavaView = realViewBinding;
        globalThis.__ajsJavaViewMember = realViewMember;

        const ui = {
            afterFirstFrame: afterFirstFrame,
            imageLoader: {loadImageInto: function (view, address) {
                if (!realUiMode) return Promise.reject(new Error('imageLoader 需要原生 Java UI'));
                if (!realImageLoader) {
                    realImageLoader = javaSession().getImageLoader();
                    globalThis.$autojs.java.setThreadMode(realImageLoader, 'io');
                }
                return realImageLoader.loadImageInto(view, String(address));
            }},
            Activity: Activity,
            Widget: Widget,
            run: uiRun,
            post: uiPost,
            runOnUiThread: uiRunOnUiThread,
            findViewById: uiFindViewById,
            setContentView: uiSetContentView,
            inflate: uiInflate,
            inflateBatch: uiInflateBatch,
            layout: uiSetContentView,
            getWindow: uiGetWindow,
            currentActivity: uiCurrentActivity,
            getCurrentActivity: uiGetCurrentActivity,
            activityLifecycle: activityLifecycle,
            startActivity: startActivity,
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
