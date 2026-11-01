'use strict';
// Protocol-handle View events (uiRequestView inflate/viewCommand + pollEvents).
// This file deliberately does NOT expose __ajsEnableJavaCallbacks, so ui.js must
// use the host protocol path rather than native Java View listeners.
//
// Target semantics: once/off(original), duplicate registrations, prepend order,
// reentrancy, exception isolation, rollback of failed registrations,
// removeAllListeners stopping fallback polling, and stable facade identity.
const assert = require('assert'), fs = require('fs'), path = require('path'), vm = require('vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/node24/ui.js'), 'utf8');
const VIEW_ID = 101;

function createHost(options) {
    const settings = options || {};
    const viewResponse = {
        ok: true, handleId: 'handle-1', viewId: VIEW_ID, viewName: 'probe',
        className: 'android.widget.Button'
    };
    const host = {
        bind: Object.assign({click: 'ok', check: 'ok', text_change: 'ok'}, settings.bind || {}),
        events: [], requests: [], errors: [], warnings: [],
        checked: false, text: '',
        reads: {isChecked: 0, getText: 0}
    };
    host.console = {
        log() {},
        warn: (...args) => host.warnings.push(args),
        error: (...args) => host.errors.push(args)
    };
    function bindMode(action) {
        if (action === 'setOnClickListener') return host.bind.click;
        if (action === 'setOnCheckedChangeListener') return host.bind.check;
        if (action === 'setOnTextChangedListener') return host.bind.text_change;
        return 'ok';
    }
    function viewAction(request) {
        const action = String(request.action || '');
        if (action === 'isChecked') {
            host.reads.isChecked++;
            return {ok: true, value: !!host.checked};
        }
        if (action === 'getText') {
            host.reads.getText++;
            return {ok: true, value: String(host.text)};
        }
        if (action === 'getSelectedItemId') return {ok: true, value: 0};
        const mode = bindMode(action);
        if (mode === 'unsupported') return {ok: false, error: 'viewCommand 不支持 ' + action};
        if (mode === 'fail') return {ok: false, error: 'injected bind failure: ' + action};
        return {ok: true};
    }
    host.request = function (request) {
        const op = String(request && request.op || '');
        host.requests.push(request);
        if (op === 'pollEvents') {
            // One event per poll: a once callback can synchronously poll again and
            // observe whether its own registration was removed before re-entry.
            const events = host.events.length ? [host.events.shift()] : [];
            return {ok: true, events: events};
        }
        if (op === 'inflate' || op === 'findViewById') return Object.assign({}, viewResponse);
        if (op === 'show') return {ok: true, views: {probe: VIEW_ID}};
        if (op === 'layoutId') return {ok: true, id: 0};
        if (op === 'layoutName') return {ok: true, name: 'main'};
        if (op === 'ping') return {ok: true};
        if (op === 'uiRequestView' || op === 'viewCommand') return viewAction(request);
        return {ok: false, error: '未知 op: ' + op};
    };
    return host;
}

function loadUi(host) {
    let ui;
    const context = vm.createContext({
        require: require,
        console: host.console,
        setTimeout: setTimeout,
        clearTimeout: clearTimeout,
        setInterval: () => 1,
        clearInterval() {},
        __ajsRegisterModule: (name, factory) => {
            assert.strictEqual(name, 'ui');
            ui = factory({host: request => host.request(request)});
        }
    });
    vm.runInContext(source, context, {filename: 'node24/ui.js'});
    assert(ui, 'ui module must register through __ajsRegisterModule');
    assert.strictEqual(typeof context.__ajsEnableJavaCallbacks, 'undefined',
        'this contract must stay on the host protocol path, not native Java callbacks');
    return ui;
}

function pushEvent(host, type, extra) {
    host.events.push(Object.assign({type: type, viewId: VIEW_ID}, extra || {}));
}
function deliver(ui, host, type, extra) {
    pushEvent(host, type, extra);
    ui.__viewProtocolPoll();
}

const failures = [];
function test(name, fn) {
    try {
        fn();
        console.log('PASS ' + name);
    } catch (error) {
        failures.push(name);
        console.error('FAIL ' + name + '\n  '
            + String(error && error.stack || error).replace(/\n/g, '\n  '));
    }
}

test('click/check/text_change once fires exactly once', () => {
    const host = createHost(), ui = loadUi(host), view = ui.inflate('main');
    const calls = {click: 0, check: 0, text_change: 0};
    view.once('click', () => { calls.click++; });
    view.once('check', () => { calls.check++; });
    view.once('text_change', () => { calls.text_change++; });
    deliver(ui, host, 'click');
    deliver(ui, host, 'check', {checked: true});
    deliver(ui, host, 'text_change', {text: 'first'});
    assert.deepStrictEqual(calls, {click: 1, check: 1, text_change: 1});
    deliver(ui, host, 'click');
    deliver(ui, host, 'check', {checked: false});
    deliver(ui, host, 'text_change', {text: 'second'});
    assert.deepStrictEqual(calls, {click: 1, check: 1, text_change: 1},
        'pollEvents must not redeliver a consumed once listener');
});

test('off(original) removes a pending once wrapper', () => {
    const host = createHost(), ui = loadUi(host), view = ui.inflate('main');
    const calls = {click: 0, check: 0, text_change: 0};
    function clickOnce() { calls.click++; }
    function checkOnce() { calls.check++; }
    function textOnce() { calls.text_change++; }
    view.once('click', clickOnce);
    view.once('check', checkOnce);
    view.once('text_change', textOnce);
    view.off('click', clickOnce);
    view.off('check', checkOnce);
    view.off('text_change', textOnce);
    deliver(ui, host, 'click');
    deliver(ui, host, 'check', {checked: true});
    deliver(ui, host, 'text_change', {text: 'value'});
    assert.deepStrictEqual(calls, {click: 0, check: 0, text_change: 0},
        'off(original) must find the EventEmitter once wrapper');
});

test('duplicate registrations are independent; off removes one copy', () => {
    const host = createHost(), ui = loadUi(host), view = ui.inflate('main');
    let calls = 0;
    function duplicate() { calls++; }
    view.on('click', duplicate);
    view.on('click', duplicate);
    deliver(ui, host, 'click');
    assert.strictEqual(calls, 2, 'two identical listeners are two registrations');
    view.off('click', duplicate);
    deliver(ui, host, 'click');
    assert.strictEqual(calls, 3, 'off removes only one of the duplicates');
    view.off('click', duplicate);
    deliver(ui, host, 'click');
    assert.strictEqual(calls, 3, 'the last copy can be removed with the original function');
});

test('prependListener/prependOnceListener preserve EventEmitter order', () => {
    const host = createHost(), ui = loadUi(host), view = ui.inflate('main');
    const order = [];
    view.on('click', () => order.push('last'));
    view.prependListener('click', () => order.push('middle'));
    view.once('click', () => order.push('onceLast'));
    view.prependOnceListener('click', () => order.push('first'));
    deliver(ui, host, 'click');
    assert.deepStrictEqual(order, ['first', 'middle', 'last', 'onceLast']);
    order.length = 0;
    deliver(ui, host, 'click');
    assert.deepStrictEqual(order, ['middle', 'last'],
        'prependOnceListener must be consumed while prependListener remains');
});

test('once is removed before its callback can re-enter polling', () => {
    const host = createHost(), ui = loadUi(host), view = ui.inflate('main');
    let onceCalls = 0, regularCalls = 0, reentered = false;
    view.once('click', () => {
        onceCalls++;
        if (reentered) return;
        reentered = true;
        pushEvent(host, 'click');
        ui.__viewProtocolPoll(); // synchronous nested pollEvents dispatch
    });
    view.on('click', () => { regularCalls++; });
    deliver(ui, host, 'click');
    assert.strictEqual(onceCalls, 1, 'the nested event must not invoke the once wrapper again');
    assert.strictEqual(regularCalls, 2, 'normal listeners should see both outer and nested events');
});

test('a throwing listener does not block later listeners or events', () => {
    const host = createHost(), ui = loadUi(host), view = ui.inflate('main');
    let afterClick = 0, afterCheck = 0;
    view.on('click', () => { throw new Error('click listener failure'); });
    view.on('click', () => { afterClick++; });
    view.on('check', () => { throw new Error('check listener failure'); });
    view.on('check', () => { afterCheck++; });
    deliver(ui, host, 'click');
    deliver(ui, host, 'click');
    deliver(ui, host, 'check', {checked: true});
    assert.strictEqual(afterClick, 2, 'later click listeners still run for every event');
    assert.strictEqual(afterCheck, 1, 'later check listeners still run after a throw');
    assert(host.errors.length >= 1, 'callback exceptions should be reported through console.error');
});

test('failed click registration leaves no ghost listener', () => {
    const host = createHost(), ui = loadUi(host), view = ui.inflate('main');
    let ghost = 0;
    host.bind.click = 'fail';
    assert.throws(() => view.on('click', () => { ghost++; }),
        'on(click) must surface the host bind failure');
    host.bind.click = 'ok';
    deliver(ui, host, 'click');
    assert.strictEqual(ghost, 0, 'a rejected registration must not stay in the dispatch table');
    let good = 0;
    view.on('click', () => { good++; });
    deliver(ui, host, 'click');
    assert.strictEqual(ghost, 0);
    assert.strictEqual(good, 1, 'a later successful registration still works');
});

test('failed check registration leaves no ghost listener', () => {
    const host = createHost(), ui = loadUi(host), view = ui.inflate('main');
    let ghost = 0;
    host.bind.check = 'fail';
    assert.throws(() => view.on('check', () => { ghost++; }),
        'on(check) must surface the host bind failure');
    host.bind.check = 'ok';
    deliver(ui, host, 'check', {checked: true});
    assert.strictEqual(ghost, 0, 'a rejected check registration must not stay in the dispatch table');
    let good = 0;
    view.on('check', () => { good++; });
    deliver(ui, host, 'check', {checked: false});
    assert.strictEqual(ghost, 0);
    assert.strictEqual(good, 1, 'a later successful check registration still works');
});

test('removeAllListeners stops fallback polling; event channel does not double deliver', () => {
    const supportedHost = createHost(), supportedUi = loadUi(supportedHost);
    const supportedView = supportedUi.inflate('main');
    let supportedCalls = 0;
    supportedView.on('check', () => { supportedCalls++; });
    assert.strictEqual(supportedHost.reads.isChecked, 0,
        'a host check event channel must not also start isChecked polling');
    deliver(supportedUi, supportedHost, 'check', {checked: true});
    assert.strictEqual(supportedCalls, 1, 'one host event produces one callback');
    assert.strictEqual(supportedHost.reads.isChecked, 0,
        'pollEvents must not poll state when the host channel is active');

    const host = createHost({bind: {check: 'unsupported'}});
    const ui = loadUi(host), view = ui.inflate('main');
    let calls = 0;
    view.on('check', () => { calls++; });
    const baselineReads = host.reads.isChecked;
    assert(baselineReads > 0, 'unsupported host binding should establish a polling baseline');
    host.checked = true;
    ui.__viewProtocolPoll();
    assert.strictEqual(calls, 1, 'fallback polling delivers one callback per state change');
    assert(host.reads.isChecked > baselineReads, 'fallback polling reads the changed state');
    view.removeAllListeners('check');
    const readsAfterRemoval = host.reads.isChecked;
    host.checked = false;
    ui.__viewProtocolPoll();
    assert.strictEqual(host.reads.isChecked, readsAfterRemoval,
        'removeAllListeners must stop the fallback polling loop');
    assert.strictEqual(calls, 1, 'removed polling listeners are not called again');
    pushEvent(host, 'check', {checked: true});
    ui.__viewProtocolPoll();
    assert.strictEqual(calls, 1, 'a later host event must not reach the removed listener');
});

test('facade identity survives re-lookup and shares listener state', () => {
    const host = createHost(), ui = loadUi(host), view = ui.inflate('main');
    assert.strictEqual(ui.inflate('main'), view, 're-inflating the same handle reuses the facade');
    assert.strictEqual(ui.findViewById('probe'), view, 'findViewById reuses the protocol handle facade');
    assert.strictEqual(view.findViewById(VIEW_ID), view, 'findViewById on the facade keeps its identity');

    let clicks = 0, pendingCheck = 0, texts = 0;
    ui.findViewById('probe').on('click', () => { clicks++; });
    deliver(ui, host, 'click');
    assert.strictEqual(clicks, 1, 'a re-looked-up facade shares the registered listener table');

    view.once('check', () => { pendingCheck++; });
    ui.findViewById('probe').on('text_change', () => { texts++; });
    view.removeAllListeners();
    deliver(ui, host, 'click');
    deliver(ui, host, 'check', {checked: true});
    deliver(ui, host, 'text_change', {text: 'ignored'});
    assert.strictEqual(clicks, 1, 'removeAllListeners clears click listeners');
    assert.strictEqual(pendingCheck, 0, 'removeAllListeners clears pending once wrappers');
    assert.strictEqual(texts, 0, 'removeAllListeners clears all event names');
});

test('old viewCommand-only hosts retain event delivery and bind listeners once', () => {
    const host = createHost(), original = host.request;
    host.request = request => request.op === 'uiRequestView'
        ? {ok: false, error: '未知的 Node UI op uiRequestView'} : original(request);
    const ui = loadUi(host), view = ui.inflate('main');
    let calls = 0;
    assert.strictEqual(view.once('check', () => { calls++; }), view, 'registration chains on the cached facade');
    deliver(ui, host, 'check', {checked: true});
    view.removeAllListeners('check');
    view.on('check', () => { calls++; });
    deliver(ui, host, 'check', {checked: false});
    assert.strictEqual(calls, 2);
    assert.strictEqual(host.requests.filter(r => r.op === 'viewCommand' && r.action === 'setOnCheckedChangeListener').length, 1,
        'removing JS subscriptions must not add a second native listener on re-registration');
});

test('legacy binding markers share subscriptions with protocol findViewById', () => {
    const host = createHost(), ui = loadUi(host);
    class Page extends ui.Activity { get layoutId() { return 'main'; } }
    const page = ui.setMainActivity(Page).instance;
    const binding = page.contentView.binding.probe, view = ui.findViewById('probe');
    let marked = 0, found = 0;
    binding.once('click', () => { marked++; });
    view.on('click', () => { found++; });
    deliver(ui, host, 'click'); deliver(ui, host, 'click');
    assert.strictEqual(marked, 1); assert.strictEqual(found, 2);
    assert.strictEqual(binding.listenerCount('click'), 1);
    binding.removeAllListeners(); deliver(ui, host, 'click');
    assert.strictEqual(found, 2, 'removal through a marker reaches the shared facade');
    ui.close();
});

test('consumed polling once stops reads; failed baseline leaves no subscription', () => {
    const host = createHost({bind: {check: 'unsupported'}}), ui = loadUi(host), view = ui.inflate('main');
    let calls = 0;
    view.once('check', () => { calls++; }); host.checked = true;
    ui.__viewProtocolPoll();
    const reads = host.reads.isChecked;
    host.checked = false; ui.__viewProtocolPoll();
    assert.strictEqual(calls, 1); assert.strictEqual(host.reads.isChecked, reads);
    const request = host.request;
    host.request = r => r.action === 'isChecked' ? {ok: false, error: 'baseline unavailable'} : request(r);
    assert.throws(() => view.on('check', () => { calls++; }), /baseline unavailable/);
    assert.strictEqual(view.listenerCount('check'), 0);
});

if (failures.length > 0) {
    console.error('\n' + failures.length + ' protocol-handle event test(s) FAILED');
    process.exitCode = 1;
} else {
    console.log('PASS Node UI protocol handle events: once/off, duplicates, prepend, reentrancy, '
        + 'exceptions, failed-registration rollback, polling removal and facade identity');
}
