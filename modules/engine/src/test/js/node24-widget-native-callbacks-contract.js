'use strict';
const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm');
let ui, factory, tags;
const session = {inflateXml: () => ({}), viewId: () => 0};
const context = vm.createContext({require, console, setTimeout, clearTimeout,
    setInterval: () => 1, clearInterval() {}, __ajsEnableJavaCallbacks: () => 1,
    __ajsInstallWidgetFactory: (names, callbacks) => { tags = names; factory = callbacks; },
    $autojs: {java: {findClass: () => session}},
    __ajsRegisterModule: (_name, create) => { ui = create({host: () => ({ok: true})}); }
});
vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../main/assets/node24/ui.js'), 'utf8'), context);
const order = [], nativeContext = {}, parent = {}, root = {__ajsJavaObject__: true,
    __class: 'android.widget.TextView', findViewById: () => null};
class Native extends ui.Widget {
    created() { order.push('created'); }
    render(ctx, p) { assert.strictEqual(ctx, nativeContext); assert.strictEqual(p, parent); order.push('render'); return root; }
    rootViewCreated(view) { assert.strictEqual(this.view, view); order.push('root'); }
    get initialAttributes() { assert.strictEqual(this.view, root); order.push('initial'); return {count: '4', alpha: '0.5'}; }
    get attr$count() { return this.count; }
    set attr$count(value) { this.count = Number(value); }
    inflated() { assert.strictEqual(this.count, 4); order.push('inflated'); }
    mounted() { assert.strictEqual(this.count, 9); order.push('mounted'); }
}
ui.Widget.register('native-probe', Native);
ui.inflate('<text/>'); // installs the native callback protocol, not XML macro expansion
assert(tags.includes('native-probe'));
const protectedXml = factory.prepareXml('<native-probe size="large" caption="&quot;A&amp;B&#x4e2d;&#25991;&quot;"/>');
const encoded = /__aijsWidgetAttrs="([^"]+)"/.exec(protectedXml)[1];
const decoded = JSON.parse(encoded.replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&amp;/g, '&'));
assert.strictEqual(decoded.size, 'large', 'custom aliases retain their original names and values');
assert.strictEqual(decoded.caption, '"A&B中文"', 'XML entities decode exactly once');
assert.throws(() => factory.prepareXml('<native-probe size=broken/>'), /属性格式/);
assert.strictEqual(factory.render(1, 'native-probe', nativeContext, parent), root);
assert.deepStrictEqual(order, ['created', 'render'], 'initialAttributes must wait for a real View');
factory.rootViewCreated(1, root);
const writes = [];
root.attr = (name, value) => writes.push([name, value]);
factory.inflated(1, '{"android:alpha":"0.8"}');
assert.deepStrictEqual(writes, [], 'internal XML attributes take precedence over native defaults');
assert(factory.hasAttribute(1, 'count'));
factory.setAttribute(1, 'count', '9');
factory.mounted(1);
assert.deepStrictEqual(order, ['created', 'render', 'root', 'initial', 'inflated', 'mounted']);
assert.strictEqual(ui.Widget.of(root).count, 9);
factory.release(1);
assert.throws(() => factory.mounted(1), /no longer active/);
class Bad extends ui.Widget { render() { return Promise.resolve(root); } }
ui.Widget.register('bad-native', Bad);
assert(tags.includes('bad-native'), 'registration updates reach the current Java factory');
assert.throws(() => factory.render(2, 'bad-native', nativeContext, parent), /必须返回/);
factory.release(2);
console.log('PASS Node native Widget callbacks: context/parent, lazy defaults, stage attributes and release');
