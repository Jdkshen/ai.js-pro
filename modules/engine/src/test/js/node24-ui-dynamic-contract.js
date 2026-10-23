'use strict';
const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/node24/ui.js'), 'utf8');
let ui, shown, replacement, recreated, keepAlive = 0;
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
    return { __ajsJavaObject__: true, __class: 'android.widget.FrameLayout',
        findViewById: id => views.get(id) || null, getClass: () => 'FrameLayout' };
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
    setInterval: () => 1, clearInterval() {}, __ajsEnableJavaCallbacks: () => 1,
    $autojs: { keepRunning: () => keepAlive++, stopRunning: () => keepAlive--,
        java: { findClass: name => {
            if (name === 'android.os.Handler') return class Handler {post(task){task.run();return true;}};
            if (name === 'android.os.Looper') return {getMainLooper: () => 1};
            if (name === 'java.lang.Runnable' || name.endsWith('$ActivityCallbacks')) return function Listener(v){return v;};
            return session;
        } } },
    __ajsRegisterModule: (_name, factory) => { ui = factory({host: req => {
        if (req.op === 'show') {
            shown = req.layoutXml; currentView = view(shown);
            return {ok: true, views: ids};
        }
        if (req.op === 'pollEvents') return {ok: true, events: []};
        return {ok: true};
    }}); }
});
vm.runInContext(source, context);
class Card extends ui.Widget {
    render() { return '<frame><column id="slot"/></frame>'; }
}
class Leaf extends ui.Widget { render(){return '<text text="leaf"/>';} }
ui.Widget.register('m3-card', Card);
ui.Widget.register('m3-leaf', Leaf);
class Page extends ui.Activity {
    get layoutXml(){return '<frame id="root"><m3-card id="card"><m3-card id="nested"><text id="child" text="x > y"/></m3-card><m3-leaf/></m3-card><!-- keep <m3-card/> --></frame>';}
}
const p = ui.setMainActivity(Page).instance;
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
ui.runOnUiThread(() => 42).then(result => {
    assert.equal(result, 42, 'runOnUiThread has an awaitable result');
    ui.close();
    console.log('PASS Node24 dynamic UI contract: XML strings, nested Widget children, bindings, layout, recreate and keepalive');
}).catch(error => {console.error(error); process.exitCode = 1;});
