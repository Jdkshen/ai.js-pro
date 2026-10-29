'use strict';
const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm');
let ui, nextId = 1;
const ids = {}, requests = [], views = new Map(), trace = [];
function native(xml) {
    const root = {__ajsJavaObject__: true, __class: 'android.widget.TextView',
        findViewById: id => views.get(id) || null};
    for (const m of xml.matchAll(/\bid="([^"]+)"/g)) {
        if (!ids[m[1]]) ids[m[1]] = nextId++;
        views.set(ids[m[1]], root);
    }
    return root;
}
const session = {viewId: name => ids[name] || 0, inflateXml: xml => { requests.push(xml); return native(xml); }};
const context = vm.createContext({require, console, setTimeout, clearTimeout,
    __ajsEnableJavaCallbacks: () => 1,
    setInterval: () => 1, clearInterval() {},
    $autojs: {java: {findClass: () => session}},
    __ajsRegisterModule: (_name, factory) => { ui = factory({host: () => ({ok: true})}); }
});
vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../main/assets/node24/ui.js'), 'utf8'), context);
class Badge extends ui.Widget {
    constructor() { super(); this.seed = 'constructor'; this.attr$label = 'field'; trace.push('constructor'); }
    created() { this.seed = 'created'; trace.push('created'); }
    get initialAttributes() {
        assert.notStrictEqual(this.seed, undefined, 'defaults are never read inside super()');
        trace.push('initial'); return {text: this.seed, alpha: '0.5', label: 'default must not overwrite a field'};
    }
    get attr$count() { return this.count; }
    set attr$count(value) { this.count = Number(value); }
    get attr$readonly() { return 'readonly'; }
    render() { trace.push('render'); return '<text text="internal"/>'; }
    mounted(view) { trace.push('mounted'); assert.strictEqual(this.view, view); }
}
class Inherited extends Badge {}
ui.Widget.register('badge', Inherited);
assert.strictEqual(ui.Widget.create('missing'), undefined);
const fresh = ui.Widget.create('badge');
assert(fresh instanceof Inherited);
assert.deepStrictEqual(trace, ['constructor'], 'create instantiates but does not invent inflate phases');
assert.throws(() => fresh.view, /widget is not mounted/);
assert.deepStrictEqual(Object.keys(fresh.attrs), []);
assert(fresh.hasAttribute('count') && fresh.hasAttribute('label') && !fresh.hasAttribute('missing'));
fresh.setAttribute('count', '7');
assert.strictEqual(fresh.getAttribute('count'), 7);
fresh.setAttribute('label', 'changed');
assert.strictEqual(fresh.getAttribute('label'), 'changed');
fresh.attrs.only = 'dictionary'; fresh.setAttribute('only', 'updated');
assert.strictEqual(fresh.getAttribute('only'), 'updated');
let fallback;
fresh.setAttribute('missing', 'value', value => { fallback = value; });
assert.strictEqual(fallback, 'value');
assert.strictEqual(fresh.getAttribute('missing', () => 'native fallback'), 'native fallback');
fresh.setAttribute('readonly', 'ignored', value => { fallback = value; });
assert.strictEqual(fallback, 'ignored');
assert.strictEqual(fresh.getAttribute('readonly'), 'readonly');
assert.throws(() => fresh.getAttribute('absent'), /默认读取函数/);
assert.throws(() => fresh.setAttribute('absent', 'x'), /默认写入函数/);
trace.length = 0;
const root = ui.inflate('<badge id="one" text="external" count="9"/>');
assert.deepStrictEqual(trace, ['constructor', 'created', 'initial', 'render', 'mounted']);
assert(requests.at(-1).includes('text="external"'), 'external XML overrides rendered native attributes');
assert(requests.at(-1).includes('alpha="0.5"'), 'native defaults reach the inflated XML');
assert(!requests.at(-1).includes('count='), 'custom setters are not passed to Android as unknown attributes');
const badge = ui.Widget.of(root);
assert.strictEqual(badge.count, 9);
assert.strictEqual(badge.view, root);
const attr = context.__ajsJavaViewMember(root, 'attr');
assert.strictEqual(attr('label'), 'field');
attr('label', 'single'); assert.strictEqual(badge.attr$label, 'single');
attr({label: 'batch'}); assert.strictEqual(badge.attr$label, 'batch');
assert.strictEqual(ui.Widget.of({}), undefined);
const overridden = ui.inflate('<badge id="two" label="external field"/>');
assert.strictEqual(ui.Widget.of(overridden).attr$label, 'external field');
assert(!requests.at(-1).includes('label='), 'JS data attributes also bypass Android attribute parsing');
class Defaults extends Badge { render() { return '<text/>'; } }
ui.Widget.register('defaults', Defaults);
ui.inflate('<defaults android:id="default"/>');
assert(requests.at(-1).includes('text="created"'), 'default values use initialized subclass state');
assert(requests.at(-1).includes('id="default"'), 'android:id also overrides the rendered root ID');
class Bad extends ui.Widget { created() { throw Error('creation failed'); } render() { return '<text/>'; } }
ui.Widget.register('bad', Bad);
assert.throws(() => ui.inflate('<bad/>'), /creation failed/);
console.log('PASS Node Widget: create, inherited attributes, constructor timing, defaults and XML precedence');
