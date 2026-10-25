'use strict';
const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/node24/ui.js'), 'utf8');
const calls = [], android = {nativePackages: true};
let ui, recreated;
const session = {getActivity: () => ({}), getContentView: () => ({}), setActivityCallbacks() {}, recreate: xml => {recreated = xml;}};
const context = vm.createContext({console, android, setTimeout, clearTimeout,
    setInterval: () => 1, clearInterval() {}, __ajsEnableJavaCallbacks: () => 1,
    $autojs: {keepRunning() {}, stopRunning() {}, java: {findClass: name => {
        if (name.endsWith('$ActivityCallbacks')) return function Callback(v) { return v; };
        return session;
    }}},
    __ajsRegisterModule: (_name, factory) => {ui = factory({host: request => {
        calls.push(request);
        if (request.op === 'resourceState') return {ok: true, compiled: true};
        if (request.op === 'layoutId') return {ok: true, id: 0x7e010001};
        return {ok: true, views: {}, events: []};
    }});}
});
vm.runInContext(source, context);
class Page extends ui.Activity {get layoutId() {return ui.R.layout.resource_probe;}}
const page = ui.setMainActivity(Page).instance;
assert.equal(calls.find(r => r.op === 'show').realJava, true,
    'androidContext.loadResources alone must select the real Java Activity path');
assert.strictEqual(context.android, android, 'compiled UI must retain real Java packages');
assert(!calls.some(r => r.op === 'loadAndroidResources'), 'the explicit context contract needs no second resource load');
page.recreate();
assert.strictEqual(recreated, '', 'a compiled layoutId page recreates without requiring inline XML');
console.log('PASS Node UI explicit resource context selects native Activity without marker globals or duplicate loads');
