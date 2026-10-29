'use strict';
const assert = require('assert'), path = require('path');
const calls = [];
globalThis.__ajsEnableJavaCallbacks = () => 91;
globalThis.__autojsHost = text => {
    const r = JSON.parse(text); calls.push(r);
    let value = null;
    if (r.kind === 'java') {
        if (r.op === 'member') value = {kind: r.name === 'color' || r.name === 'system_accent1_500' ? 'field' :
            r.name === 'setOnSeekBarChangeListener' ? 'method' : 'unknown'};
        if (r.op === 'field') value = r.name === 'color' ? {$class:'android.R$color'} : 0x106003e;
        if (r.op === 'newRef') value = {$obj:3, $class:r.cls, $view:true};
        if (r.op === 'proxyInterface') value = {$obj:4, $class:r.cls};
    }
    return JSON.stringify({ok:true, value});
};
require(path.resolve(__dirname, '../../main/assets/node24/prelude.js'));
assert.equal(android.R.color.system_accent1_500, 0x106003e);
const seek = new android.widget.SeekBar(null);
const listener = {count:0, onProgressChanged(){this.count++;}, onStartTrackingTouch(){}, onStopTrackingTouch(){}};
seek.setOnSeekBarChangeListener(listener);
const map = calls.at(-1).args[0].$map;
assert.equal(map.count, 0);
assert(map.onProgressChanged.$callback);
function dispatch(callback, method) {
    const reply = JSON.parse(__ajsDispatchJavaCallback(JSON.stringify({callbackId:callback.$callback, method, args:[]})));
    assert.equal(reply.ok, true, reply.error);
}
dispatch(map.onProgressChanged, 'onProgressChanged');
assert.equal(listener.count, 1, 'object literal interface methods keep their JS this');
const Runnable = $java.findClass('java.lang.Runnable');
const implementation = {runs:0, run(){this.runs++;}};
$java.implement(Runnable, implementation);
const callback = calls.at(-1).callback;
dispatch(callback, 'run');
assert.equal(implementation.runs, 1);
assert.throws(() => $java.implement([Runnable, Runnable], implementation), /一个 Java 接口/);
const extras = {}, child = {}, views = [];
globalThis.__ajsJavaViewMember = (view, name) => {
    views.push(view);
    if (name === 'extras') return extras;
    if (name === 'findView') return id => id === 'child' ? child : null;
    if (name === 'clearViewBindingCache') return () => { extras.invalidated = true; };
};
assert.strictEqual(seek.extras, extras);
assert.strictEqual(seek.findView('child'), child);
seek.clearViewBindingCache();
assert.strictEqual(extras.invalidated, true);
assert(views.every(view => view === seek), 'View extensions receive the original Java proxy');
console.log('PASS Node24 UI interop: lowercase R classes, callback maps, this receiver and explicit implement');
