'use strict';
const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/node24/ui.js'), 'utf8');
const errors = [];
let ui;
const sandbox = vm.createContext({require, console: {log() {}, warn() {}, error: (...args) => errors.push(args)},
    setTimeout, clearTimeout, setInterval: () => 1, clearInterval() {},
    $autojs: {java: {findClass: name => name.endsWith('NodeUiLocalSession')
        ? {viewId: key => key === 'target' ? 42 : 0}
        : function Listener(callbacks) { return callbacks; }}},
    __ajsRegisterModule: (_name, factory) => { ui = factory({host: () => ({ok: true, events: []})}); }
});
vm.runInContext(source, sandbox);
const installed = {}, installs = {};
const view = {__class: 'android.widget.TextView'};
for (const method of ['setOnClickListener', 'setOnLongClickListener', 'setOnTouchListener', 'setOnKeyListener',
    'setOnScrollChangeListener', 'setOnCheckedChangeListener']) {
    view[method] = listener => { installed[method] = listener; installs[method] = (installs[method] || 0) + 1; };
}
const member = name => sandbox.__ajsJavaViewMember(view, name);
const on = (event, listener) => member('on')(event, listener);
const off = (event, listener) => member('off')(event, listener);
const clear = event => member('removeAllListeners')(event);

// A subscribed listener, or `return true`, must not swallow Android's default handling.
let longEvent;
on('long_click', (e, nativeView) => { longEvent = e; assert.strictEqual(nativeView, view); return true; });
assert.strictEqual(installed.setOnLongClickListener.onLongClick(view), false);
assert.strictEqual(longEvent.view, view);
assert.strictEqual(longEvent.consumed, false);
on('long_click', e => { e.consumed = true; });
assert.strictEqual(installed.setOnLongClickListener.onLongClick(view), true);
clear('long_click');
member('once')('long_click', e => { e.consumed = true; });
assert.strictEqual(installed.setOnLongClickListener.onLongClick(view), true);
assert.strictEqual(installed.setOnLongClickListener.onLongClick(view), false);

// The shared native listener emits phase before generic, using the same event object.
const order = [];
let touchEvent;
const down = e => { order.push('down'); touchEvent = e; e.consumed = true; };
on('touch_down', down);
on('touch', (e, nativeView) => {
    order.push('touch'); assert.strictEqual(nativeView, view);
    assert.strictEqual(e.view, view);
    if (e.getAction() === 0 && e.consumed) assert.strictEqual(e, touchEvent);
    assert.strictEqual(e.getX(), 17);
    assert.strictEqual(e.__handle, 91, 'Java object identity is retained for passing it back to Java');
});
on('touch_move', () => order.push('move'));
const motion = action => ({__handle: 91, getAction() { return action; },
    getX() { assert.strictEqual(this.__handle, 91); return 17; }});
assert.strictEqual(installed.setOnTouchListener.onTouch(view, motion(0)), true);
assert.deepStrictEqual(order, ['down', 'touch']);
assert.strictEqual(installs.setOnTouchListener, 1, 'phase registration must not replace the native listener');
order.length = 0;
assert.strictEqual(installed.setOnTouchListener.onTouch(view, motion(2)), false);
assert.deepStrictEqual(order, ['move', 'touch']);
off('touch_down', down);
assert.strictEqual(installed.setOnTouchListener.onTouch(view, motion(0)), false, 'removal changes native consumption');
clear('touch'); clear('touch_move');
assert.strictEqual(installed.setOnTouchListener.onTouch(view, motion(1)), false);

const keys = [];
on('key_down', (code, e, nativeView) => {
    keys.push('down'); assert.strictEqual(code, 66); assert.strictEqual(e.getKeyCode(), 66);
    assert.strictEqual(nativeView, view); e.consumed = true;
    assert.strictEqual(e.view, view);
});
on('key_up', () => keys.push('up'));
on('key', () => keys.push('key'));
const key = action => ({getAction: () => action, getKeyCode: () => 66});
assert.strictEqual(installed.setOnKeyListener.onKey(view, 66, key(0)), true);
assert.strictEqual(installed.setOnKeyListener.onKey(view, 66, key(1)), false);
assert.deepStrictEqual(keys, ['down', 'key', 'up', 'key']);
assert.strictEqual(installs.setOnKeyListener, 1);

let scroll;
on('scroll_change', (e, nativeView) => { scroll = e; assert.strictEqual(nativeView, view); });
installed.setOnScrollChangeListener.onScrollChange(view, 3, 40, 1, 20);
assert.strictEqual(scroll.view, view);
assert.deepStrictEqual([scroll.scrollX, scroll.scrollY, scroll.oldScrollX, scroll.oldScrollY], [3, 40, 1, 20]);
assert.strictEqual(scroll.consumed, false);

const extras = member('extras'); extras.state = {value: 7};
assert.strictEqual(member('extras'), extras, 'extras belongs to the same native View identity');
assert.strictEqual(member('extras').state.value, 7);
let child = {__class: 'android.widget.TextView'};
view.findViewById = id => id === 42 ? child : null;
assert.strictEqual(member('findView')(42), child);
assert.strictEqual(member('findView')(404), null);
assert.strictEqual(member('findView')(null), null);
assert.strictEqual(member('findView')('target'), child);
assert.strictEqual(member('findView')('missing'), null);
const binding = sandbox.__ajsBindJavaView(view), oldChild = child;
assert.strictEqual(binding.target, oldChild);
child = {__class: 'android.widget.TextView'};
assert.strictEqual(binding.target, oldChild, 'cached binding needs explicit invalidation after tree edits');
assert.strictEqual(member('clearViewBindingCache')(), undefined);
assert.strictEqual(binding.target, child, 'clearing also invalidates retained binding references');

const clicks = [];
function last() { clicks.push('last'); }
member('addListener')('click', last);
member('prependOnceListener')('click', () => clicks.push('first'));
assert.strictEqual(member('listenerCount')('click'), 2);
assert(member('eventNames')().includes('click'));
assert(member('listeners')('click').includes(last));
installed.setOnClickListener.onClick(view); installed.setOnClickListener.onClick(view);
assert.deepStrictEqual(clicks, ['first', 'last', 'last']);
assert.strictEqual(member('setMaxListeners')(27), view);
assert.strictEqual(member('getMaxListeners')(), 27);
member('removeAllListeners')();
assert.strictEqual(member('listenerCount')('click'), 0);
assert.strictEqual(member('emit')('click', view), false);

// A handler which failed after consuming still cannot accidentally re-enable default input.
on('long_click', e => { e.consumed = true; throw new Error('expected handler failure'); });
assert.strictEqual(installed.setOnLongClickListener.onLongClick(view), true);
assert.strictEqual(errors.length, 1);
assert.throws(() => on('unsupported_native_event', () => {}), /暂不支持/);
console.log('PASS Node View events: touch/key phases, consumed replies, scroll, once/off and emitter controls');
