'use strict';
const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/node24/ui.js'), 'utf8');
const errors = [];
const context = vm.createContext({require, console: {...console, error: (...args) => errors.push(args)},
    setTimeout, clearTimeout, setInterval: () => 1, clearInterval() {},
    $autojs: {java: {findClass: name => name === 'android.widget.AdapterView'
        ? {isInstance: view => view.kind === 'adapter'}
        : function Listener(callbacks) { return {...callbacks, listenerType: name}; }}},
    __ajsRegisterModule: (_name, factory) => factory({host: () => ({ok: true, events: []})})});
vm.runInContext(source, context);
const member = (view, name) => context.__ajsJavaViewMember(view, name);
function nativeView(kind) {
    return {kind, __ajsJavaObject__: true, __class: kind === 'adapter' ? 'android.widget.Spinner'
        : 'com.google.android.material.navigationrail.NavigationRailView',
        setOnItemSelectedListener(listener) { this.listener = listener; }};
}
const nav = nativeView('navigation'), received = [];
member(nav, 'once')('item_selected', (...args) => received.push(args));
assert.equal(nav.listener.listenerType, 'com.google.android.material.navigation.NavigationBarView$OnItemSelectedListener');
assert.equal(typeof nav.listener.onNavigationItemSelected, 'function', 'Material calls onNavigationItemSelected');
assert.equal(nav.listener.onNavigationItemSelected({getItemId: () => 42}), true, 'notification listeners allow selection');
assert.deepStrictEqual(received, [[42, nav]]);
assert.equal(nav.listener.onNavigationItemSelected({getItemId: () => 43}), true);
assert.equal(received.length, 1, 'once remains once for the actual Material callback name');
member(nav, 'once')('item_selected', () => { throw new Error('expected selection failure'); });
assert.equal(nav.listener.onNavigationItemSelected({getItemId: () => 44}), true);
assert.equal(errors.length, 1, 'callback errors do not veto navigation');

const spinner = nativeView('adapter'), rows = [], selected = {label: 'selected row'};
member(spinner, 'on')('item_selected', (...args) => rows.push(args));
assert.equal(spinner.listener.listenerType, 'android.widget.AdapterView$OnItemSelectedListener');
spinner.listener.onItemSelected(spinner, selected, 7, 9001);
assert.deepStrictEqual(rows, [[9001, spinner, 7, selected]]);
spinner.listener.onNothingSelected(spinner);
assert.equal(rows.length, 1);
member(spinner, 'removeAllListeners')();
spinner.listener.onItemSelected(spinner, selected, 8, 9002);
assert.equal(rows.length, 1, 'removal reaches the actual AdapterView listener');
console.log('PASS native selection: Material callback name/acceptance, AdapterView interface/arguments, once and removal');
