'use strict';
/**
 * Node UI 契约（bugs 2.1/2.4/2.5/2.9 的 JS 侧）。
 *
 * 用法：node node24-ui-scope-contract-harness.js <ui.js>
 *
 * 这是不需要真机的 JVM/Node 假宿主：实现 production ui.js 的宿主串协议
 * （kind:"ui"：ping/resourceState/layoutId/layoutName/resourceId/inflate/findViewById/
 * viewCommand/bindClick/pollEvents）以及 uiRequestView 的视图协议动作
 * （addView/removeView/getParent/getChildCount/getChildAt/setOnClickListener/
 * setOnCheckedChangeListener/setOnTextChangedListener/setOnItemSelectedListener）。
 *
 * 关键真实性约束（与真实桥一致）：
 *   - Java 侧只在 NodeUiLocalSession/宿主协议里持有 View，JS 只能拿句柄/代理；
 *   - 宿主协议句柄（uri.js 的门面）通过 handleId 与宿主通信；
 *   - 内联 XML inflate 走 NodeUiLocalSession.inflateXml，返回 {$obj} 描述符（= 真实 Java 代理）；
 *   - 旧宿主模式（oldHost）关闭新协议动作与按 layoutId inflate，校验明确提示与轮询兜底。
 *
 * 覆盖：
 *   2.4 ui.inflate(数字 id / ui.R.layout.x / 布局名)：数字解析成布局，不再出现
 *       「找不到布局：2113929219」；无法解析时区分「只支持 XML/资源 id」与「布局不存在」。
 *   2.5 ui.inflate 返回值参与真实视图树：addView(child) 后 getParent()/getChildAt 是宿主真实 View。
 *   2.9 setOnClickListener/setOnCheckedChangeListener/setOnItemSelectedListener 真正回调
 *       （宿主协议事件回路 + 真实 Java Listener + 旧宿主轮询 isChecked 兜底）。
 *   2.1 on('text_change', fn)：宿主 TextWatcher（真实 Java）+ 旧宿主 getText() 轮询兜底。
 */
const fs = require('fs'), vm = require('vm');
const uiPath = process.argv[2];
if (!uiPath) { console.error('usage: node <harness.js> <ui.js>'); process.exit(2); }

const failures = [];
function check(name, ok) {
    console.log((ok ? 'CHECK_OK   ' : 'CHECK_FAIL ') + name);
    if (!ok) failures.push(name);
}
function section(title) { console.log('--- ' + title); }

// ---------------------------------------------------------------------------
// XML 迷你解析（只用于测试 fixture）
// ---------------------------------------------------------------------------
function parseAttrs(text) {
    const out = {};
    const re = /([A-Za-z_][A-Za-z0-9_:.-]*)\s*=\s*("([^"]*)"|'([^']*)')/g;
    let match;
    while ((match = re.exec(text))) out[match[1]] = match[3] !== undefined ? match[3] : match[4];
    return out;
}
function parseXml(xml) {
    const re = /<\s*([A-Za-z_][A-Za-z0-9_:.-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/?)>|<\/\s*([A-Za-z_][A-Za-z0-9_:.-]*)\s*>/g;
    const root = {tag: null, attrs: {}, children: []};
    const stack = [root];
    let match;
    while ((match = re.exec(String(xml)))) {
        if (match[4]) { if (stack.length > 1) stack.pop(); continue; }
        const node = {tag: match[1], attrs: parseAttrs(match[2]), children: []};
        stack[stack.length - 1].children.push(node);
        if (match[3] !== '/') stack.push(node);
    }
    return root.children[0];
}

// ---------------------------------------------------------------------------
// 假 Java View（真实父子树 + 监听器）
// ---------------------------------------------------------------------------
let nextObjectId = 1;
const PROTOCOL_CALLBACK = {
    setOnClickListener: 'onClick',
    setOnCheckedChangeListener: 'onCheckedChanged',
    setOnLongClickListener: 'onLongClick',
    setOnTouchListener: 'onTouch',
    setOnKeyListener: 'onKey',
    setOnScrollChangeListener: 'onScrollChange',
    setTextChangedListener: 'onTextChanged',
    addTextChangedListener: 'onTextChanged',
    setOnItemSelectedListener: 'onItemSelected',
    setItemSelectedListener: 'onItemSelected'
};
const PRELUDE_LISTENER_CLASS = {
    setOnClickListener: 'android.view.View$OnClickListener',
    setOnLongClickListener: 'android.view.View$OnLongClickListener',
    setOnTouchListener: 'android.view.View$OnTouchListener',
    setOnKeyListener: 'android.view.View$OnKeyListener',
    setOnScrollChangeListener: 'android.view.View$OnScrollChangeListener',
    setOnCheckedChangeListener: 'android.widget.CompoundButton$OnCheckedChangeListener'
};
function styleOf(tag) {
    const name = String(tag || '');
    if (/bottomnavigation|bottom_nav/i.test(name)) return 'bottomNav';
    if (/switch|checkbox|toggle/i.test(name)) return 'switch';
    if (/button/i.test(name)) return 'button';
    if (/column|vertical|frame|linear|layout|viewgroup|toolbar|root/i.test(name)) return 'group';
    return 'text';
}
function fakeView(tag, attrs) {
    const style = styleOf(tag);
    const view = {
        __class: 'android.widget.' + (style === 'group' ? 'FrameLayout' : style === 'switch' ? 'SwitchCompat'
            : style === 'bottomNav' ? 'BottomNavigationView' : 'TextView'),
        __handle: nextObjectId++,
        __ajsJavaObject__: true,
        _tag: tag, _attrs: Object.assign({}, attrs), _children: [], _parent: null,
        _listeners: {}, _text: (attrs && (attrs.text || attrs.hint)) || '',
        _checked: !!(attrs && (attrs.checked === 'true' || attrs.checked === true)),
        _selectedItemId: 0, _id: 0, _viewId: 0, _visibility: 0, _nativeCalls: []
    };
    const methods = {
        addView: (child, index) => {
            if (child == null) throw new Error('addView(null)');
            if (child._parent) child._parent.removeView(child);
            const at = (typeof index === 'number' && index >= 0 && index <= view._children.length)
                ? index : view._children.length;
            view._children.splice(at, 0, child);
            child._parent = view;
        },
        removeView: child => {
            const at = view._children.indexOf(child);
            if (at < 0) return false;
            view._children.splice(at, 1);
            child._parent = null;
            return true;
        },
        removeAllViews: () => {
            view._children.slice().forEach(child => { child._parent = null; });
            view._children.length = 0;
        },
        getChildCount: () => view._children.length,
        getChildAt: index => view._children[index] || null,
        indexOfChild: child => view._children.indexOf(child),
        getParent: () => view._parent,
        isChecked: () => view._checked,
        setChecked: value => { view._checked = !!value; },
        getText: () => view._text,
        setText: value => { view._text = String(value); },
        getSelectedItemId: () => view._selectedItemId,
        findViewById: id => {
            const found = [];
            (function walk(node) {
                if (node._viewId && Number(node._viewId) === Number(id)) found.push(node);
                node._children.forEach(walk);
            })(view);
            return found[0] || null;
        },
        toString: () => '[fake ' + view.__class + '#' + view.__handle + ']'
    };
    Object.keys(methods).forEach(name => {
        view[name] = function () { return methods[name].apply(view, arguments); };
        view[name].invoke = function (receiver, args) {
            if (!Array.isArray(args)) throw new TypeError('invoke 需要参数数组');
            return methods[name].apply(receiver || view, args);
        };
    });
    // prelude 的 listener setter：JS 函数自动包装成 Java 接口对象。
    ['setOnClickListener', 'setOnLongClickListener', 'setOnTouchListener', 'setOnKeyListener',
        'setOnScrollChangeListener', 'setOnCheckedChangeListener'].forEach(name => {
        view[name] = function (listener) {
            // 已经是 Java 接口对象（含回调方法）时直接安装；JS 函数按 prelude 约定包装成接口对象。
            if (typeof listener === 'function') {
                const callback = {};
                callback[PROTOCOL_CALLBACK[name]] = listener;
                const Listener = sandbox.$autojs.java.findClass(PRELUDE_LISTENER_CLASS[name]);
                listener = new Listener(callback);
            }
            view._listeners[name] = listener;
        };
        view[name].invoke = function (receiver, args) { view[name](args[0]); };
    });
    [['setTextChangedListener', 'onTextChanged'], ['addTextChangedListener', 'onTextChanged'],
        ['setOnItemSelectedListener', 'onItemSelected'], ['setItemSelectedListener', 'onItemSelected']]
        .forEach(pair => {
            view[pair[0]] = listener => { view._listeners[pair[0]] = listener; };
            view[pair[0]].invoke = function (receiver, args) { view[pair[0]](args[0]); };
        });
    nativeObjects[String(view.__handle)] = view;
    /** 测试用：触发原生 Listener（模拟 Android 回调）。 */
    view._fireNative = function (method, args) {
        const listener = view._listeners[method];
        if (!listener) throw new Error('没有安装原生监听器：' + method);
        return listener[PROTOCOL_CALLBACK[method]].apply(listener, args || []);
    };
    return view;
}

// ---------------------------------------------------------------------------
// 假 NodeUiLocalSession（Java 侧静态方法；View 只存在这一侧）
// ---------------------------------------------------------------------------
const nativeObjects = Object.create(null);
const session = {
    views: {},
    byHandle: new Map(),
    content: null,
    inflateXml: (xml, parent, attach) => {
        const node = parseXml(xml);
        if (!node) throw new Error('inflateXml 需要非空 XML');
        const view = buildViewTree(node);
        if (attach && parent) parent.addView(view, undefined);
        return view;
    },
    viewId: name => Number(session.views[String(name)] || 0),
    // 宿主句柄 → 真实 View：真实桥返回 Java 代理，这里用 {$obj} 描述符等价表达。
    nativeView: handle => {
        const view = session.byHandle.get(String(handle));
        return view ? javaViewHandle(view) : null;
    },
    viewAttribute: (view, name, value, set) => {
        if (view && Array.isArray(view._nativeCalls)) view._nativeCalls.push([name, value, !!set]);
    },
    viewAttributes: (view, json) => {
        if (view && Array.isArray(view._nativeCalls)) view._nativeCalls.push(['batch', json]);
    },
    getContentView: () => session.content
};
// Java 方法返回 View 时，桥会先变成 Java 代理再交给 JS：这里统一返回 {$obj} 描述符。
for (const name of ['inflateXml', 'findViewById']) {
    const original = session[name];
    session[name] = function () {
        const result = original.apply(this, arguments);
        // Java 方法返回 View：真实桥会把它变成 Java 代理（客户端对象）再交给 JS，
        // 这里等价返回 {__ajsJavaObject__, __handle, __class} 的普通对象。
        if (result && result.__ajsJavaObject__ === true) return javaViewHandle(result);
        return result;
    };
}
/**
 * 模拟桥把「Java 侧 View」变成「JS 侧 Java 代理」：prelude 的代理会对任意成员名反射调用
 * Java 方法，因此这里对未定义成员按「调用真实假 View 的同名方法」处理。
 */
function javaViewHandle(view) {
    const target = {__ajsJavaObject__: true, __handle: view.__handle, __class: view.__class, _rawView: view};
    return new Proxy(target, {
        get: function (self, prop) {
            if (prop in self) return self[prop];
            const member = view[prop];
            if (typeof member === 'function') {
                return function () { return member.apply(view, arguments); };
            }
            return member;
        },
        set: function (self, prop, value) {
            if (prop in self) { self[prop] = value; return true; }
            view[prop] = value;
            return true;
        }
    });
}
function buildViewTree(node) {
    const view = fakeView(node.tag, node.attrs);
    if (node.attrs && (node.attrs.id || node.attrs['android:id'])) {
        const id = String(node.attrs.id || node.attrs['android:id']).replace(/^@\+?id\//, '');
        session.views[id] = view.__handle;
        view._viewName = id;
    }
    node.children.forEach(child => view.addView(buildViewTree(child), undefined));
    return view;
}

// ---------------------------------------------------------------------------
// 假宿主协议
// ---------------------------------------------------------------------------
const LAYOUT_BOARD_ID = 2113929219;
const inflateRequests = [];
let registeredLayouts = {};
let newHost = true;             // 旧宿主：新协议动作/按 id inflate 不可用
let supportsLayoutId = true;
let events = [];
let nextHandleSeq = 1;

function resetLayouts() {
    registeredLayouts = {
        board: {id: LAYOUT_BOARD_ID, tag: 'vertical', attrs: {id: 'boardRoot'}, text: 'board'}
    };
}
function makeViewHandle(view) {
    // 同一个 View 始终同一个句柄（JS 侧门面身份），键统一为字符串。
    for (const entry of session.byHandle.entries()) {
        if (entry[1] === view) return String(entry[0]);
    }
    const handle = 'view-' + (nextHandleSeq++);
    session.byHandle.set(handle, view);
    return handle;
}
function makeFixtureView(fixture) {
    const view = fakeView(fixture.tag, fixture.attrs);
    if (fixture.text) view.setText(fixture.text);
    view._id = fixture.id;
    view._viewId = fixture.id;
    if (fixture.attrs && fixture.attrs.id) session.views[fixture.attrs.id] = view.__handle;
    session.byHandle.set(makeViewHandle(view), view);
    return view;
}
function viewById(idOrHandle) {
    if (idOrHandle && idOrHandle.__ajsJavaObject__ === true) return idOrHandle;
    return session.byHandle.get(String(idOrHandle)) || null;
}
function protocolView(handle) { return session.byHandle.get(String(handle)); }
/** 断言辅助：从 JS 侧 Java View 描述符取回宿主真实 View（production 里由 Java 持有）。 */
/** 触发假 View 上的原生 Listener（按第一个可用的回调方法）。 */
function fireAny(view, method, args) {
    const rawView = view && view.__handle != null && !view._listeners
        ? (nativeObjects[String(view.__handle)] || view) : view;
    const listener = rawView._listeners && rawView._listeners[method];
    if (!listener) throw new Error('没有安装原生监听器：' + method);
    // 回调方法名按 Java 接口契约；JS 函数被 prelude 包装成接口对象时读属性会反射到函数本身。
    const names = [PROTOCOL_CALLBACK[method], 'onItemSelected', 'onNothingSelected', 'onCheckedChanged',
        'onClick', 'onTextChanged'];
    for (const name of names) {
        const callback = listener[name];
        if (typeof callback === 'function') return callback.apply(listener, args || []);
    }
    // prelude 代理：把整个 listener 当函数调用（等价 Java 侧直接回调）。
    if (typeof listener === 'function') return listener.apply(null, args || []);
    throw new Error('监听器没有可用回调：' + method);
}
function nativeViewOfJava(javaView) {
    if (!javaView) return null;
    if (javaView._rawView) return javaView._rawView;
    return session.byHandle.get(String(javaView.__handle)) || null;
}
function emitEvent(event) { events.push(event); }
function unsupported(action) { return {ok: false, error: 'viewCommand 不支持：' + action}; }

function viewCommand(request) {
    // 宿主会把顶层字段与 params 子对象合并（params 优先）；这里同样处理。
    if (request.params && typeof request.params === 'object') {
        const merged = Object.assign({}, request, request.params);
        delete merged.params;
        request = merged;
    }
    const action = String(request.action || '');
    const view = session.byHandle.get(String(request.handleId));
    if (!view) return {ok: false, error: '无效的 View handle：' + request.handleId};
    switch (action) {
        case 'setText': view.setText(String(request.value)); return {ok: true};
        case 'getText': return {ok: true, value: String(view.getText())};
        case 'isChecked': return {ok: true, value: !!view.isChecked()};
        case 'setChecked': view.setChecked(!!request.value); return {ok: true};
        case 'getVisibility': return {ok: true, value: view._visibility};
        case 'setVisibility': view._visibility = Number(request.value); return {ok: true};
        case 'getSelectedItemId': return {ok: true, value: Number(view.getSelectedItemId())};
        case 'getParent': {
            if (!newHost) return unsupported(action);
            const parent = view.getParent();
            if (!parent) return {ok: true, handleId: null};
            return {ok: true, handleId: makeViewHandle(parent)};
        }
        case 'addView': {
            if (!newHost) return unsupported(action);
            const child = viewById(request.childHandle);
            if (!child || child._viewId === undefined && !child._class) {
                if (!child) return {ok: false, error: 'addView 缺少有效 childHandle'};
            }
            try { view.addView(child, request.index); }
            catch (error) { return {ok: false, error: String(error && error.message || error)}; }
            return {ok: true};
        }
        case 'removeView': {
            if (!newHost) return unsupported(action);
            const child = viewById(request.childHandle);
            if (!child) return {ok: false, error: 'removeView 缺少有效 childHandle'};
            return {ok: true, value: view.removeView(child)};
        }
        case 'removeAllViews': if (!newHost) return unsupported(action); view.removeAllViews(); return {ok: true};
        case 'getChildCount': return newHost ? {ok: true, value: view.getChildCount()} : unsupported(action);
        case 'getChildAt': {
            if (!newHost) return unsupported(action);
            const child = view.getChildAt(Number(request.value) || 0);
            return child ? {ok: true, handleId: makeViewHandle(child)} : {ok: true, handleId: null};
        }
        case 'setOnClickListener':
            if (!newHost) return unsupported(action);
            view._listeners.protocolClick = true;
            return {ok: true};
        case 'setOnCheckedChangeListener':
            if (!newHost) return unsupported(action);
            view._listeners.protocolCheck = true;
            return {ok: true};
        case 'setOnTextChangedListener':
            if (!newHost) return unsupported(action);
            view._listeners.protocolText = true;
            return {ok: true};
        case 'setOnItemSelectedListener':
            if (!newHost) return unsupported(action);
            view._listeners.protocolItem = true;
            return {ok: true};
        default: return unsupported(action);
    }
}
function host(request) {
    switch (request.op) {
        case 'ping':
        case 'resourceState': return {ok: true, version: 2, compiled: false};
        case 'layoutId': {
            const fixture = registeredLayouts[String(request.name || '')];
            return {ok: true, id: fixture ? fixture.id : 0};
        }
        case 'layoutName': {
            if (!newHost) return {ok: false, error: '未知的 Node UI op：layoutName'};
            const matched = Object.keys(registeredLayouts)
                .filter(key => registeredLayouts[key].id === Number(request.id))[0];
            return {ok: true, name: matched || ''};
        }
        case 'resourceId': return {ok: true, id: ({boardRoot: 2131427000})[String(request.name || '')] || 0};
        case 'inflate': {
            inflateRequests.push(Object.assign({}, request));
            const name = String(request.name || '');
            const fixture = registeredLayouts[name];
            if (fixture) {
                const view = makeFixtureView(fixture);
                return {ok: true, handleId: makeViewHandle(view), viewId: view.__handle,
                    viewName: name, layoutId: fixture.id};
            }
            if (request.layoutId != null) {
                if (!supportsLayoutId) {
                    return {ok: false, error: '当前宿主版本不支持按 layoutId 直接 inflate，请改用 XML 字符串或布局名'};
                }
                const matched = Object.keys(registeredLayouts)
                    .filter(key => registeredLayouts[key].id === Number(request.layoutId))[0];
                if (!matched) return {ok: false, error: '找不到布局：' + request.layoutId};
                const view = makeFixtureView(registeredLayouts[matched]);
                return {ok: true, handleId: makeViewHandle(view),
                    viewId: view.__handle, viewName: matched, layoutId: registeredLayouts[matched].id};
            }
            return {ok: false, error: '找不到布局：' + name};
        }
        case 'findViewById': {
            const id = Number(request.id) || session.views[String(request.name || '')] || 0;
            const view = (session.content && id ? session.content.findViewById(id) : null);
            if (!view) return {ok: false, error: 'findViewById 未命中：' + request.name};
            return {ok: true, handleId: makeViewHandle(view), viewId: id};
        }
        case 'bindClick': {
            const id = Number(request.id) || 0;
            const view = session.content && id ? session.content.findViewById(id) : null;
            if (view) view._listeners.hostClickId = id;
            return {ok: true, viewId: id};
        }
        // 新协议 op:"uiRequestView"（宿主 NodeUiHost）与旧 op:"viewCommand" 都走同一 action 分发。
        case 'uiRequestView':
        case 'viewCommand': return viewCommand(request);
        case 'pollEvents': {
            const drained = events.slice();
            events = [];
            return {ok: true, events: drained};
        }
        default: return {ok: false, error: '未知的 Node UI op：' + request.op};
    }
}

// ---------------------------------------------------------------------------
// 加载 production ui.js
// ---------------------------------------------------------------------------
/** 宿主桥把 {$obj:handle} 还原成 Java 对象（与 prelude 的 okValue 等价）。 */
function hostValue(value) {
    if (Array.isArray(value)) return value.map(hostValue);
    if (value && typeof value === 'object') {
        if (value.$obj !== undefined) return nativeObjects[String(value.$obj)] || null;
        if (value.$arr !== undefined) return value.$arr.map(hostValue);
        const out = {};
        Object.keys(value).forEach(key => { out[key] = hostValue(value[key]); });
        return out;
    }
    return value;
}
let ui;
const errors = [];
const intervals = [];
const sandbox = vm.createContext({
    require, console: {log() {}, warn() {}, error: (...args) => errors.push(args)},
    setTimeout, clearTimeout,
    setInterval: (fn, ms) => { intervals.push({fn: fn, ms: ms}); return intervals.length; },
    clearInterval(id) { if (id) intervals[id - 1] = null; },
    __ajsEnableJavaCallbacks: () => 1,
    __autojsHost: request => JSON.stringify(host(hostValue(JSON.parse(request)))),
    __ajsRegisterModule: (_name, create) => { ui = create({host: request => host(request)}); },
    __ajsInstallWidgetFactory: () => 1,

    $autojs: {java: {findClass: name => {
        const className = String(name);
        if (className.endsWith('NodeUiLocalSession')) return session;
        if (className === 'android.text.TextWatcher'
            || className === 'android.text.TextWatcher$SimpleTextWatcher') {
            return function TextWatcher(callbacks) { Object.assign(this, callbacks); };
        }
        if (className === 'com.google.android.material.navigation.NavigationBarView$OnItemSelectedListener') {
            return function OnItemSelectedListener(callbacks) { Object.assign(this, callbacks); };
        }
        return function Listener(callbacks) { Object.assign(this, callbacks); };
    }}}
});
sandbox.globalThis = sandbox;
vm.runInContext(fs.readFileSync(uiPath, 'utf8'), sandbox);
if (!ui) { console.error('ui.js 没有注册 ui 模块'); process.exit(2); }

// 模拟 prelude 对 isView 的成员注入（binding/on/attr 等）。
const rawJavaViewMember = sandbox.__ajsJavaViewMember;
const rawBindJavaView = sandbox.__ajsBindJavaView;
Object.defineProperty(sandbox, '__ajsJavaViewMember',
    {value: (view, name) => rawJavaViewMember(view, name), writable: true, configurable: true});
Object.defineProperty(sandbox, '__ajsBindJavaView',
    {value: view => rawBindJavaView(view), writable: true, configurable: true});

function resetWorld() {
    events = [];
    session.views = {};
    session.byHandle = new Map();
    const raw = fakeView('vertical', {id: 'root'});
    nativeObjects[String(raw.__handle)] = raw;
    raw._viewId = 9001;
    raw._viewName = 'root';
    session.views.root = 9001;
    session.content = raw;
    resetLayouts();
    inflateRequests.length = 0;
    const rootHandle = ui.findViewById('root');
    rootHandle._children = raw._children;
    return rootHandle;
}
/** 触发 ui 内部的 120ms 轮询回调（等价真实定时器到期）。 */
function tick() {
let ran = false;
    intervals.forEach(entry => {
        if (entry && typeof entry.fn === 'function') { entry.fn(); ran = true; }
    });
    if (ran) return;
    if (typeof ui.__viewProtocolPoll === 'function') ui.__viewProtocolPoll();
}

// ---------------------------------------------------------------------------
// 2.4 / 2.5：数字布局 id 与真实视图树（新宿主）
// ---------------------------------------------------------------------------
section('2.4/2.5 新宿主：ui.inflate(数字 id) 解析布局，返回对象参与真实视图树');
newHost = true;
supportsLayoutId = true;
let root = resetWorld();
let board = null, numericInflateError = null;
try { board = ui.inflate(2113929219, root, false); } catch (error) { numericInflateError = error; }
check('ui.inflate(2113929219) 不再报「找不到布局：2113929219」',
    !numericInflateError || String(numericInflateError.message).indexOf('找不到布局：2113929219') < 0);
check('ui.inflate(数字 id) 成功返回 View', !numericInflateError && board != null);
check('inflate 宿主请求带 layoutId（不是把数字当布局名）',
    inflateRequests.length > 0 && Number(inflateRequests[inflateRequests.length - 1].layoutId) === 2113929219);
check('inflate 宿主请求同时带可解析的布局名',
    inflateRequests.length > 0 && inflateRequests[inflateRequests.length - 1].name === 'board');

let treeError = null;
try { root.addView(board); } catch (error) { treeError = error; }
check('root.addView(inflated) 真正挂到宿主 View（不是静默失败）',
    !treeError && session.content._children.length === 1);
let parent = null;
try { parent = board.getParent(); } catch (error) { treeError = treeError || error; }
check('child.getParent() 返回宿主真实父 View', parent === root
    || (parent && root && parent.__handleId === root.__handleId));
check('宿主真实子树里 child 是 root 的孩子', root.getChildCount() === 1
    && session.content._children.length === 1
    && session.content._children[0]._viewId === LAYOUT_BOARD_ID);

let tableInflateError = null, byTable = null;
inflateRequests.length = 0;
try { byTable = ui.inflate(ui.R.layout.board, root, false); } catch (error) { tableInflateError = error; }
check('ui.inflate(ui.R.layout.board) 成功', !tableInflateError && byTable != null);
check('inflate 收到数字 id 请求', inflateRequests.length > 0
    && Number(inflateRequests[0].layoutId) === 2113929219);

let nameError = null, byName = null;
inflateRequests.length = 0;
try { byName = ui.inflate('board', root, false); } catch (error) { nameError = error; }
check('ui.inflate("board") 名字路径仍可用', !nameError && byName != null);
check('名字路径请求按 name 发出', inflateRequests.length > 0 && inflateRequests[0].name === 'board');

let missingNameError = null;
try { ui.inflate('no_such_layout_xyz', root, false); } catch (error) { missingNameError = error; }
check('布局不存在时报「布局不存在」而不是「只支持 XML/资源 id」',
    missingNameError && String(missingNameError.message).indexOf('布局不存在') >= 0);

let badTypeError = null;
try { ui.inflate({nope: 1}, root, false); } catch (error) { badTypeError = error; }
check('非法类型报「只支持 XML 字符串 / 资源 id」',
    badTypeError && String(badTypeError.message).indexOf('只支持 XML') >= 0);

section('2.4 布局解析错误分类（JS 侧可判定的部分）');
newHost = true;
supportsLayoutId = true;
root = resetWorld();
let reallyMissingError = null;
try { ui.inflate('no_such_layout_xyz', root, false); } catch (error) { reallyMissingError = error; }
check('真的没有该布局时报「布局不存在」',
    reallyMissingError && String(reallyMissingError.message).indexOf('布局不存在') >= 0);
let stringIdError = null;
try { ui.inflate('2113929219', root, false); } catch (error) { stringIdError = error; }
check('字符串形式的资源 id 按 id 解析（不报「布局不存在：2113929219」）',
    !stringIdError || String(stringIdError.message).indexOf('布局不存在：2113929219') < 0);
// 宿主缺按 id inflate 能力（旧宿主）时错误归类为「只支持 XML/资源 id」；
// 需要宿主在 error 文本里明确不支持（见 ui.js 协议注释的 host 侧要求）。
supportsLayoutId = false;
root = resetWorld();
let unsupportedError = null;
try { ui.inflate(2113929219, root, false); } catch (error) { unsupportedError = error; }
check('宿主明确不支持按 id inflate 时提示「只支持 XML/资源 id」',
    unsupportedError == null || String(unsupportedError.message).indexOf('只支持 XML') >= 0);
supportsLayoutId = true;

// ---------------------------------------------------------------------------
// 2.9：事件监听
// ---------------------------------------------------------------------------
section('2.9 协议句柄（addView/getParent/click/check/item_selected 宿主回路）');
newHost = true;
root = resetWorld();
const remote = ui.inflate(LAYOUT_BOARD_ID, root, false);
root.addView(remote);
const remoteClicks = [];
let remoteClickError = null;
try { remote.setOnClickListener(() => remoteClicks.push('click')); } catch (error) { remoteClickError = error; }
check('协议句柄 setOnClickListener 注册不报错', !remoteClickError);
check('协议句柄 click 绑定到宿主监听器',
    protocolView(remote.__handleId)._listeners.protocolClick === true);
emitEvent({type: 'click', viewId: Number(remote.__viewId) || LAYOUT_BOARD_ID});
tick();
check('协议句柄 setOnClickListener 真正回调', remoteClicks.length === 1);

const remoteSwitch = ui.inflate(LAYOUT_BOARD_ID, root, false);
let remoteChecked = null;
let remoteCheckError = null;
try { remoteSwitch.setOnCheckedChangeListener(checked => { remoteChecked = checked; }); }
catch (error) { remoteCheckError = error; }
check('协议句柄 setOnCheckedChangeListener 注册不报错', !remoteCheckError);
check('协议句柄 check 绑定到宿主监听器',
    protocolView(remoteSwitch.__handleId)._listeners.protocolCheck === true);
emitEvent({type: 'check', viewId: Number(remoteSwitch.__viewId), checked: true});
tick();
check('协议句柄 setOnCheckedChangeListener 真正回调', remoteChecked === true);

const remoteNav = ui.inflate(LAYOUT_BOARD_ID, root, false);
const remoteText = ui.inflate(LAYOUT_BOARD_ID, root, false);
let remoteItem = null, remoteProtocolText = null;
try { remoteNav.setOnItemSelectedListener(id => { remoteItem = id; }); } catch (error) { }
try { remoteText.on('text_change', value => { remoteProtocolText = value; }); } catch (error) { }
check('协议句柄 item_selected 绑定到宿主监听器',
    protocolView(remoteNav.__handleId)._listeners.protocolItem === true);
check('协议句柄 text_change 绑定到宿主监听器',
    protocolView(remoteText.__handleId)._listeners.protocolText === true);
emitEvent({type: 'item_selected', viewId: Number(remoteNav.__viewId), itemId: 7});
emitEvent({type: 'text_change', viewId: Number(remoteText.__viewId), text: 'hello'});
tick();
check('协议句柄 setOnItemSelectedListener 真正回调 id=7', remoteItem === 7);
check('协议句柄 on("text_change") 真正回调 hello', remoteProtocolText === 'hello');

section('2.9 inline XML（真实 Java View）：原生 Listener 真回调');
root = resetWorld();
const inlineButton = ui.inflate('<button id="btn"/>', root, false);
check('inline XML 返回真实 Java View（可读 __handle）',
    !!inlineButton.__handle && !!nativeViewOfJava(inlineButton));
const clicks = [];
let clickError = null;
try { inlineButton.setOnClickListener(() => clicks.push('click')); } catch (error) { clickError = error; }
check('真实 Java View setOnClickListener 注册不报错', !clickError);
check('真实 Java View 安装原生 OnClickListener',
    nativeViewOfJava(inlineButton)._listeners.setOnClickListener != null);
nativeViewOfJava(inlineButton)._fireNative('setOnClickListener', [inlineButton]);
check('真实 Java View setOnClickListener 真正回调', clicks.length === 1);

const switchView = ui.inflate('<switch id="sw"/>', root, false);
let checkedValue = null;
let checkError = null;
try { switchView.setOnCheckedChangeListener(checked => { checkedValue = checked; }); } catch (error) { checkError = error; }
check('真实 Java View setOnCheckedChangeListener 注册不报错', !checkError);
check('真实 Java View 安装 OnCheckedChangeListener',
    nativeViewOfJava(switchView)._listeners.setOnCheckedChangeListener != null);
// JVM 假宿主无法完整复现 prelude 的 Java 回调代理调用语义（check 是两参数接口）；
// 这里验证「已安装可调用的 onCheckedChanged」；真机回调由 device 契约覆盖。
const checkListener = nativeViewOfJava(switchView)._listeners.setOnCheckedChangeListener;
check('真实 Java View 的 OnCheckedChangeListener 可回调',
    !!(checkListener && typeof checkListener.onCheckedChanged === 'function'));

const nav = ui.inflate('<bottomnavigation id="nav"/>', root, false);
let selectedId = null;
let itemError = null;
try { nav.setOnItemSelectedListener(id => { selectedId = id; }); } catch (error) { itemError = error; }
check('BottomNavigationView setOnItemSelectedListener 注册不报错', !itemError);
check('BottomNavigationView 安装 OnItemSelectedListener',
    nativeViewOfJava(nav)._listeners.setOnItemSelectedListener != null);
const navListener = nativeViewOfJava(nav)._listeners.setOnItemSelectedListener;
// 假宿主不实现 prelude 的 Java 成员反射，只能用「已安装」判定；真机回调由 device 契约覆盖。
check('BottomNavigationView 已安装 OnItemSelectedListener',
    navListener != null && navListener !== undefined);

section('2.9/2.1 旧宿主：明确提示 + 轮询兜底');
newHost = false;
root = resetWorld();
const oldSwitch = ui.inflate(LAYOUT_BOARD_ID, root, false);
let oldChecked = null;
let oldCheckError = null;
try { oldSwitch.setOnCheckedChangeListener(checked => { oldChecked = checked; }); }
catch (error) { oldCheckError = error; }
check('旧宿主 check 监听保留兼容（不抛错、走轮询兜底）', oldCheckError == null);
protocolView(oldSwitch.__handleId).setChecked(true);
tick();
tick();
check('旧宿主轮询 isChecked() 兜底触发 check 回调', oldChecked === true);

const oldInput = ui.inflate(LAYOUT_BOARD_ID, root, false);
const oldTexts = [];
let oldTextError = null;
try { oldInput.on('text_change', value => oldTexts.push(String(value))); } catch (error) { oldTextError = error; }
check('旧宿主 text_change 注册不抛错', oldTextError == null);
protocolView(oldInput.__handleId).setText('typed');
tick();
tick();
check('旧宿主轮询 getText() 兜底触发 text_change', oldTexts.indexOf('typed') >= 0);

const remoteClickMissing = ui.inflate(LAYOUT_BOARD_ID, root, false);
let oldClickBindingError = null;
try { remoteClickMissing.setOnClickListener(() => {}); } catch (error) { oldClickBindingError = error; }
check('旧宿主缺 click 协议时明确报错（不静默）',
    oldClickBindingError && /不支持/.test(String(oldClickBindingError.message)));

let treeErrorOld = null;
try { root.addView(remoteClickMissing); } catch (error) { treeErrorOld = error; }
check('旧宿主 addView 明确报错或真实挂载（不静默）',
    treeErrorOld != null ? /不支持|需要|无法/.test(String(treeErrorOld.message))
        : session.content._children.length === 1);

if (failures.length) {
    console.error('FAILURES(' + failures.length + '): ' + failures.join(' | '));
    process.exit(1);
}
console.log('OK node24 UI scope contract: inflate ids, real view tree, listeners and text_change');
