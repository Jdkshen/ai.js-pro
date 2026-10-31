'use strict';
/**
 * task-246 R02 契约：Widget 多层别名共享同一个根 View。
 *
 * 用法：node node24-widget-alias-receiver-harness.js <ui.js>
 *
 * 本文件忠实模拟 NodeUiWidgetInflater 的宿主调用顺序（frames 是 ArrayDeque：
 * push=addFirst，迭代 head→tail=内层→外层；根 View 创建时一次性
 * rootViewCreated 所有等待中的别名），因此不需要真机即可校验：
 *   - 三层别名（outer → mid → inner）共享一个根 View；
 *   - Widget.of/extras 归属为最外层；
 *   - 同名/不同名属性按「持有它的那个 Widget」路由；
 *   - 事件回调内通过 this.view 读写仍然命中自己的接收者；
 *   - release（停止）与重建后不会串到上一轮/别层的接收者。
 */
const fs = require('fs'), vm = require('vm');
const uiPath = process.argv[2];
if (!uiPath) { console.error('usage: node <harness.js> <ui.js>'); process.exit(2); }

let ui, factory;
const nativeWrites = [];
const session = {
    inflateXml: () => ({}),
    viewId: () => 0,
    viewAttributes: (view, json) => nativeWrites.push(['batch', json]),
    viewAttribute: (view, name, value, set) => nativeWrites.push([name, value, set])
};
const context = vm.createContext({require, console, setTimeout, clearTimeout,
    setInterval: () => 1, clearInterval() {}, __ajsEnableJavaCallbacks: () => 1,
    __ajsInstallWidgetFactory: (names, callbacks) => { factory = callbacks; },
    $autojs: {java: {findClass: name => /Listener$/.test(String(name))
        ? class Listener { constructor(callbacks) { Object.assign(this, callbacks); } }
        : session}},
    __ajsRegisterModule: (_name, create) => { ui = create({host: () => ({ok: true})}); }});
vm.runInContext(fs.readFileSync(uiPath, 'utf8'), context);

const failures = [];
function check(name, ok) {
    console.log((ok ? 'CHECK_OK ' : 'CHECK_FAIL ') + name);
    if (!ok) failures.push(name);
}
let nextHandle = 100;
const VIEW_MEMBERS = ['on', 'once', 'addListener', 'off', 'removeListener', 'removeAllListeners',
    'eventNames', 'listenerCount', 'listeners', 'emit', 'attr', 'extras', 'findView',
    'clearViewBindingCache', 'setItemTemplate', 'setDataSource', 'getAdapter'];
function makeView() {
    const handle = nextHandle++;
    const target = {__ajsJavaObject__: true, __class: 'android.widget.FrameLayout', __handle: handle,
        _tag: null, _listeners: {}, findViewById: () => null,
        setTag: value => { target._tag = value; }, getTag: () => target._tag};
    // prelude 的 javaObject 视图代理只对白名单成员走 __ajsJavaViewMember。
    ['setOnClickListener', 'setOnCheckedChangeListener', 'setOnLongClickListener', 'setOnTouchListener',
     'setOnKeyListener', 'setOnScrollChangeListener'].forEach(method => {
        target[method] = listener => { target._listeners[method] = listener; };
    });
    let proxy;
    proxy = new Proxy(target, {
        get(self, prop) {
            if (prop === '__ajsJavaObject__') return true;
            if (prop === '__handle') return handle;
            if (prop === '__class') return target.__class;
            if (typeof prop === 'string' && VIEW_MEMBERS.indexOf(prop) >= 0) {
                const extra = context.__ajsJavaViewMember(proxy, prop);
                if (extra !== undefined) return extra;
            }
            return Reflect.get(self, prop);
        },
        set(self, prop, value) { return Reflect.set(self, prop, value); }
    });
    return proxy;
}
function parseAttrs(text) {
    const out = {};
    const re = /([A-Za-z_][A-Za-z0-9_:.-]*)\s*=\s*("([^"]*)"|'([^']*)')/g;
    let match;
    while ((match = re.exec(text))) out[match[1]] = match[3] !== undefined ? match[3] : match[4];
    return out;
}
function parseRoot(xml) {
    const match = /^\s*<\s*([^\s/>]+)([\s\S]*?)(\/?>)\s*$/.exec(xml);
    return {tag: match[1], attrs: parseAttrs(match[2])};
}
function isWidget(tag) { return typeof ui.Widget.create(tag) !== 'undefined'; }

// 宿主 inflater 模拟（顺序与 NodeUiWidgetInflater 一致）。
let nextToken = 1;
const frames = [];
const lives = {render: [], root: [], inflated: [], mounted: [], released: []};
function inflateWidget(tag, node, rootView) {
    const frame = {token: nextToken++, tag: tag, waitingRoot: true, root: null,
        internal: node.attrs || {}, attrs: node.attrs || {}};
    frames.unshift(frame);
    try {
        const rendered = factory.render(frame.token, tag, {rootView: rootView}, null);
        let root;
        if (typeof rendered === 'string') root = inflateNode(parseRoot(rendered), rootView);
        else { root = rendered; bindPendingRoots(root); }
        if (frame.waitingRoot || frame.root !== root) throw new Error('Widget render has no single root');
        factory.contentSlot(frame.token);
        factory.inflated(frame.token, JSON.stringify(frame.internal));
        const custom = {};
        Object.keys(frame.attrs).forEach(name => {
            if (factory.hasAttribute(frame.token, name)) custom[name] = frame.attrs[name];
        });
        Object.keys(custom).forEach(name => factory.setAttribute(frame.token, name, custom[name]));
        factory.mounted(frame.token);
        return root;
    } finally { frames.shift(); factory.release(frame.token); lives.released.push(frame.token); }
}
function inflateNode(node, rootView) {
    if (!isWidget(node.tag)) return rootView;
    const top = frames[0];
    if (top && top.waitingRoot && !top.rootNode) { top.rootNode = node; top.internal = node.attrs; }
    return inflateWidget(node.tag, node, rootView);
}
function bindPendingRoots(root) {
    for (const frame of frames) {
        if (!frame.waitingRoot) continue;
        frame.waitingRoot = false; frame.root = root;
        lives.root.push(frame.tag);
        factory.rootViewCreated(frame.token, root);
    }
}

class Chain extends ui.Widget {
    constructor(tag, label, renderTag) {
        super();
        this.tag = tag; this.label = label; this.renderTag = renderTag;
        this.attrsSeen = [];
    }
    set attr$label(value) { this.label = value; this.attrsSeen.push(['label', value]); }
    get attr$label() { return this.label; }
    render() { return this.renderTag ? ('<' + this.renderTag + ' label="internal-' + this.tag + '"/>') : this.rootView; }
}
/** 只有最内层声明 size：不同名属性必须落到声明它的那一层。 */
class SizedChain extends Chain {
    set attr$size(value) { this.size = value; this.attrsSeen.push(['size', value]); }
    get attr$size() { return this.size; }
}
function buildChain(tag, view) {
    const chain = new Chain(tag, 'initial-' + tag, null);
    chain.rootView = view;
    return chain;
}

// ---- 三层别名：top -> mid -> inner，共享同一个根 View ----
const root = makeView();
const top = buildChain('alias-top', root);
const mid = new Chain('alias-mid', 'initial-mid', 'alias-inner');
mid.rootView = root;
const inner = new SizedChain('alias-inner', 'initial-inner', null);
inner.rootView = root;
ui.Widget.register('alias-top', function () { return top; });
ui.Widget.register('alias-mid', function () { return mid; });
ui.Widget.register('alias-inner', function () { return inner; });
top.renderTag = 'alias-mid';
ui.inflate('<text/>');   // 安装 native Widget factory

inflateWidget('alias-top', {tag: 'alias-top', attrs: {label: 'external'}}, root);

check('three aliases bound to one root', typeof top.view === 'object' && ui.Widget.of(root) === top);
check('Widget.of is the outermost alias', ui.Widget.of(root) === top);
check('outermost owns extras', context.__ajsJavaViewMember(root, 'extras')['autojs.widget'] === top);
check('inner alias keeps its own receiver handle', inner.view !== root && mid.view !== root);
check('Widget.of(inner receiver handle) still resolves the root owner', ui.Widget.of(inner.view) === top);

// 同名属性：内层/外层各自命中自己的 attr$label。
top.view.attr('label', 'OUTER');
mid.view.attr('label', 'MIDDLE');
inner.view.attr('label', 'INNER');
check('same-name attribute stays with the outer receiver', top.label === 'OUTER');
check('same-name attribute stays with the middle receiver', mid.label === 'MIDDLE');
check('same-name attribute stays with the inner receiver', inner.label === 'INNER');
check('outer read returns its own value', top.view.attr('label') === 'OUTER');
check('middle read returns its own value', mid.view.attr('label') === 'MIDDLE');
check('inner read returns its own value', inner.view.attr('label') === 'INNER');
check('attr batch is per receiver', (() => {
    top.view.attr({label: 'OUTER_BATCH'}); mid.view.attr({label: 'MID_BATCH'});
    return top.label === 'OUTER_BATCH' && mid.label === 'MID_BATCH';
})());

// 不同名属性：size 只有最内层声明，外层调用必须落到原生属性。
nativeWrites.length = 0;
inner.view.attr('size', '9');
check('innermost receives size', inner.attrsSeen.some(entry => entry[0] === 'size' && entry[1] === '9'));
check('innermost size does not leak to native', nativeWrites.length === 0);
nativeWrites.length = 0;
top.view.attr('size', '3');
check('outer without a size setter falls back to native', nativeWrites.length === 1 && nativeWrites[0][0] === 'size');
nativeWrites.length = 0;
top.view.attr('alpha', '0.5');
check('attribute nobody declares reaches the native view', nativeWrites.length === 1 && nativeWrites[0][0] === 'alpha');

// 事件回调内读写：走各自 Widget 自己的接收者。
let innerEvents = 0, innerReadBack = null;
mid.view.on('click', () => {
    innerEvents++;
    mid.view.attr('label', 'FROM_EVENT');
    innerReadBack = mid.view.attr('label');
});
top.view.on('click', () => { top.view.attr('label', 'FROM_OUTER_EVENT'); });
root.emit('click', root);
check('event handler registered on the shared root fires once', innerEvents === 1);
check('event callback reads the middle receiver', innerReadBack === 'FROM_EVENT');
check('event callback writes the middle receiver', mid.label === 'FROM_EVENT');
check('event callback writes the outer receiver independently', top.label === 'FROM_OUTER_EVENT');

// 监听器管理仍然作用于同一个真实 View（事件状态共享，不按 Widget 复制）。
check('events are shared on the real view', root.listenerCount('click') === 2);
check('extras is the real view data', context.__ajsJavaViewMember(inner.view, 'extras') ===
    context.__ajsJavaViewMember(root, 'extras'));

// ---- 停止：release 后阶段回调失效，但各接收者仍属于自己 ----
let staleFailed = false;
try { factory.mounted(lives.released[lives.released.length - 1]); } catch (error) { staleFailed = /no longer active/.test(String(error)); }
check('released token rejects further stage callbacks', staleFailed);
mid.view.attr('label', 'AFTER_RELEASE');
check('released widget keeps its own receiver', mid.label === 'AFTER_RELEASE' && top.label === 'FROM_OUTER_EVENT');

// ---- 重建：同一链重新 inflate 到新根，旧接收者不串台 ----
const rebuiltRoot = makeView();
const top2 = buildChain('alias-top', rebuiltRoot);
const mid2 = new Chain('alias-mid', 'rebuilt-mid', 'alias-inner');
mid2.rootView = rebuiltRoot;
const inner2 = new SizedChain('alias-inner', 'rebuilt-inner', null);
inner2.rootView = rebuiltRoot;
let current = {top: top2, mid: mid2, inner: inner2};
ui.Widget.register('alias-top', function () { return current.top; });
ui.Widget.register('alias-mid', function () { return current.mid; });
ui.Widget.register('alias-inner', function () { return current.inner; });
top2.renderTag = 'alias-mid';
inflateWidget('alias-top', {tag: 'alias-top', attrs: {}}, rebuiltRoot);
check('rebuild binds the new outermost', ui.Widget.of(rebuiltRoot) === top2);
check('rebuild old owner untouched', ui.Widget.of(root) === top);
mid2.view.attr('label', 'REBUILT_MID');
top2.view.attr('label', 'REBUILT_TOP');
check('rebuild routes to new receivers', mid2.label === 'REBUILT_MID' && top2.label === 'REBUILT_TOP');
check('rebuild does not touch previous run receivers', mid.label === 'AFTER_RELEASE');

if (failures.length) {
    console.error('FAILURES(' + failures.length + '): ' + failures.join(', '));
    process.exit(1);
}
console.log('OK node24 Widget alias receiver contract: 3-level aliases, receivers, events, release and rebuild');
