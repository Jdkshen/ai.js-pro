'use strict';
const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/node24/ui.js'), 'utf8');
let ui, shown, replacement, recreated, keepAlive = 0;
let uiScopeDepth = 0, callbackWorkerEnabled = false;
const ids = {}, requests = [];
let sequence = 1;
function readIds(xml) {
    for (const m of xml.matchAll(/\bid="(?:@\+?id\/)?([\w]+)"/g)) {
        if (!ids[m[1]]) ids[m[1]] = sequence++;
        views.set(ids[m[1]], {__ajsJavaObject__: true, findViewById: id => views.get(id)});
    }
}
const views = new Map();
function view(xml) {
    readIds(xml);
    let suppressed = false;
    return { __ajsJavaObject__: true, __class: 'android.widget.FrameLayout',
        findViewById: id => views.get(id) || null, getClass: () => 'FrameLayout',
        isLayoutSuppressed: () => suppressed, suppressLayout: value => { suppressed = value; } };
}
let currentView;
const session = {
    getActivity: () => ({activity: true}),
    getContentView: () => currentView,
    viewId: name => ids[name] || 0,
    setActivityCallbacks() {},
    inflateXml: (xml, parent, attach) => { requests.push({xml, parent, attach}); return view(xml); },
    setContentView: root => { replacement = root; },
    recreate: xml => { recreated = xml; }
};
const context = vm.createContext({require, console, setTimeout, clearTimeout,
    setInterval: () => 1, clearInterval() {}, __ajsEnableJavaCallbacks: () => {
        callbackWorkerEnabled = true; return 1;
    },
    $autojs: { keepRunning: () => keepAlive++, stopRunning: () => keepAlive--,
        java: { findClass: name => {
            if (name === 'android.os.Handler') return class Handler {post(task){task.run();return true;}};
            if (name === 'android.os.Looper') return {getMainLooper: () => 1};
            if (name === 'java.lang.Runnable' || name.endsWith('$ActivityCallbacks') ||
                    name === 'android.widget.CompoundButton$OnCheckedChangeListener') return function Listener(v){return v;};
            return session;
        } } },
    __ajsRegisterModule: (_name, factory) => { ui = factory({host: req => {
        if (req.kind === 'java') {
            assert(callbackWorkerEnabled, 'host worker is enabled before entering its UI scope');
            if (req.op === 'beginUiScope') uiScopeDepth++;
            else if (req.op === 'endUiScope') uiScopeDepth--;
            else assert.fail('unexpected scoped operation: ' + req.op);
            return {ok: true};
        }
        if (req.op === 'show') {
            shown = req.layoutXml; currentView = view(shown);
            return {ok: true, views: ids};
        }
        if (req.op === 'pollEvents') return {ok: true, events: []};
        return {ok: true};
    }}); }
});
vm.runInContext(source, context);
const cards = [], attributeCalls = [];
class Card extends ui.Widget {
    render() { return '<frame><column id="slot"/></frame>'; }
    set attr$title(value) { attributeCalls.push(['custom', value]); }
    mounted(nativeView) { cards.push({widget: this, nativeView}); }
}
class Leaf extends ui.Widget { render(){return '<text text="leaf"/>';} }
ui.Widget.register('m3-card', Card);
ui.Widget.register('m3-leaf', Leaf);
class Page extends ui.Activity {
    get layoutXml(){return '<frame id="root"><m3-card id="card"><m3-card id="nested"><text id="child" text="x > y"/></m3-card><m3-leaf/></m3-card><!-- keep <m3-card/> --></frame>';}
}
const p = ui.setMainActivity(Page).instance;
const card = cards.find(entry => entry.nativeView === views.get(ids.card));
assert(card, 'custom widget receives its native view');
card.nativeView.attr = context.__ajsJavaViewMember(card.nativeView, 'attr');
session.viewAttributes = (_view, json) => attributeCalls.push(['native', JSON.parse(json)]);
card.nativeView.attr({alpha: 1, title: 'hello', enabled: true});
assert.deepEqual(attributeCalls, [['native', {alpha: 1}], ['custom', 'hello'], ['native', {enabled: true}]],
    'batch attrs use Widget setters and preserve ordering across native batches');
assert.equal(card.widget.attrs.title, 'hello');
session.viewAttributes = () => { throw new Error('viewAttributes: invalid value'); };
assert.throws(() => card.nativeView.attr({alpha: 'bad'}), /viewAttributes: invalid value/,
    'host validation errors mentioning the batch method must not be swallowed');
delete session.viewAttributes;
session.viewAttribute = (_view, name, value, write) => { attributeCalls.push(['fallback', name, value, write]); };
card.nativeView.attr({alpha: 3});
assert.deepEqual(attributeCalls.at(-1), ['fallback', 'alpha', '3', true], 'old hosts fall back per attribute');
let checkListener, observedCheck;
const checkView = {setOnCheckedChangeListener: listener => { checkListener = listener; }};
context.__ajsJavaViewMember(checkView, 'on')('check', (checked, source) => { observedCheck = [checked, source]; });
checkListener.onCheckedChanged(checkView, true);
assert.deepEqual(observedCheck, [true, checkView], 'check event matches Rhino argument order');
checkListener.onCheckedChanged(checkView, false);
assert.deepEqual(observedCheck, [false, checkView], 'check event preserves unchecked state');
assert.equal(keepAlive, 1, 'Activity keeps the Node event loop alive without manual keepRunning');
assert(shown.includes('id="child" text="x > y"'), 'nested user content survives widget expansion');
assert(shown.includes('text="leaf"'), 'self-closing custom widgets expand');
assert(shown.includes('<!-- keep <m3-card/> -->'), 'comments are preserved verbatim');
assert(!shown.replace(/<!--[\s\S]*?-->/g, '').includes('<m3-'), 'nested same-name widget tags all expand');
const child = {getText: () => 'child'};
views.set(ids.child, child);
const native = {findViewById: id => id === ids.child ? child : null};
const binding = context.__ajsBindJavaView(native);
assert.deepEqual(Object.keys(binding), ['child']);
assert.strictEqual(binding.child, child);
const parent = {__ajsJavaObject__: true};
const dynamic = ui.inflate('<column id="newroot"><text id="dynamic"/></column>', parent, true);
assert.equal(requests.at(-1).parent, parent);
assert.equal(requests.at(-1).attach, true);
assert.equal(dynamic.getClass(), 'FrameLayout');
ui.layout('<frame id="replacement"/>');
assert(replacement, 'ui.layout installs the inflated View');
p.recreate();
assert(recreated.includes('id="child"'), 'recreate re-evaluates and expands layoutXml');
assert(!recreated.replace(/<!--[\s\S]*?-->/g, '').includes('<m3-card'));
;(async function () {
    let ran = false;
    const pending = ui.runOnUiThread(() => {
        ran = true;
        assert.equal(uiScopeDepth, 1);
        assert.equal(currentView.isLayoutSuppressed(), true);
        return 42;
    });
    assert.equal(ran, false, 'defer out of a synchronous Java callback before running UI JS');
    const result = await pending;
    assert.equal(result, 42, 'runOnUiThread has an awaitable result');
    assert.equal(uiScopeDepth, 0);
    assert.equal(currentView.isLayoutSuppressed(), false);
    await assert.rejects(ui.runOnUiThread(() => { throw new Error('user callback'); }), /user callback/);
    assert.equal(uiScopeDepth, 0, 'throwing callbacks release the host-worker scope');
    assert.equal(currentView.isLayoutSuppressed(), false, 'throwing callbacks restore layout');
    currentView.suppressLayout(true);
    await ui.runOnUiThread(() => 42);
    assert.equal(currentView.isLayoutSuppressed(), true, 'preserve explicit caller suppression');
    currentView.suppressLayout(false);
    assert.equal(await ui.runOnUiThread(() => ui.runOnUiThread(() => {
        assert.equal(uiScopeDepth, 1, 'a deferred nested callback has its own scope');
        return 73;
    })), 73);
    await assert.rejects(ui.runOnUiThread(null), /需要一个函数/);
    assert.equal(uiScopeDepth, 0);
    ui.close();
    console.log('PASS Node24 dynamic UI contract: XML, Widgets, bindings, recreate, keepalive and cooperative UI scopes');
})().catch(error => {console.error(error); process.exitCode = 1;});
