'use strict';

/*
 * Node24 ui.js 契约测试：Activity.layoutXml 字符串入口 + 真实基础 view binding + click。
 *
 * 只加载 modules/engine/src/main/assets/node24/ui.js，用 vm + mock __autojsHost 模拟
 * :node24 宿主；不构建、不连设备、不执行真实 Android 代码。
 *
 * 运行：node tools/test-node24-layout-xml.cjs
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const UI_JS = path.join(__dirname, '..', 'modules', 'engine', 'src', 'main', 'assets', 'node24', 'ui.js');
const SOURCE = fs.readFileSync(UI_JS, 'utf8');

let passed = 0;
let failed = 0;

function delay(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function loadUi(host, globals) {
    let factory = null;
    const requests = [];
    const sandbox = {
        console: {
            log: function () {},
            warn: function () {},
            error: function () {},
        },
        setTimeout: setTimeout,
        clearTimeout: clearTimeout,
        setInterval: setInterval,
        clearInterval: clearInterval,
        process: {
            once: function () {},
            on: function () {},
        },
        __ajsProjectDir: '/data/user/0/com.jdkshen.aijspro/files/project',
        __ajsRegisterModule: function (name, registered) {
            if (name === 'ui') factory = registered;
        },
        __autojsHost: function (raw) {
            const request = JSON.parse(raw);
            requests.push(request);
            return JSON.stringify(host(request, requests));
        },
    };
    Object.assign(sandbox, globals || {});
    sandbox.globalThis = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(SOURCE, sandbox, {filename: 'node24/ui.js'});
    assert.ok(factory, 'ui.js 应注册 ui 模块');
    const ui = factory({
        host: function () { throw new Error('测试应走 __autojsHost'); },
    });
    return {ui: ui, requests: requests, sandbox: sandbox};
}

async function test(name, fn) {
    try {
        await fn();
        passed++;
        console.log('PASS ' + name);
    } catch (error) {
        failed++;
        console.error('FAIL ' + name);
        console.error(error && error.stack ? error.stack : error);
    }
}

async function testLayoutXmlRequestAndRealBinding() {
    const xml = '<column>\n'
        + '  <text id="text" text="hello"/>\n'
        + '  <input id="input" hint="type"/>\n'
        + '  <button id="button" text="ok"/>\n'
        + '</column>';
    const events = [];
    const commands = [];
    let bindClickRequest = null;
    let clicked = 0;
    let gotText = null;
    let contentView = null;

    const loaded = loadUi(function (request) {
        switch (request.op) {
            case 'show':
                return {ok: true, mode: 'activity', views: {text: 1, input: 2, button: 3}};
            case 'pollEvents': {
                const next = events.shift();
                return {ok: true, events: next ? [next] : []};
            }
            case 'findViewById':
                return {ok: true, handleId: 'handle-' + request.id, viewId: request.id};
            case 'viewCommand':
                commands.push(request);
                if (request.action === 'getText') {
                    return {ok: true, value: 'from-host'};
                }
                return {ok: true};
            case 'bindClick':
                bindClickRequest = request;
                return {ok: true};
            case 'close':
                return {ok: true};
            default:
                return {ok: true};
        }
    });
    const ui = loaded.ui;

    class MainActivity extends ui.Activity {
        get layoutXml() {
            return '\n' + xml + '\n';
        }
        onContentViewSet(view) {
            contentView = view;
            view.binding.text.setText('changed');
            gotText = view.binding.input.getText();
            view.binding.button.on('click', function () { clicked++; });
            // 再取一次 binding.button：应复用同一 marker，两个 handler 都要被调用。
            view.binding.button.on('click', function () { clicked += 10; });
        }
    }

    events.push({type: 'content_view_set', views: {text: 1, input: 2, button: 3}});
    ui.setMainActivity(MainActivity);

    const show = loaded.requests.find((request) => request.op === 'show');
    assert.ok(show, '应发出 show 请求');
    assert.strictEqual(show.layoutXml, xml, 'show 应携带 trim 后的 layoutXml');
    assert.strictEqual(show.layoutId, undefined, 'layoutXml 模式不应再要求 layoutId');
    assert.ok(contentView, 'onContentViewSet 应被派发');
    assert.strictEqual(gotText, 'from-host', 'input.getText 应返回宿主真实值');

    const setText = commands.find((command) => command.action === 'setText');
    assert.ok(setText, 'binding.text.setText 应发真实 viewCommand');
    assert.strictEqual(setText.value, 'changed');
    assert.ok(commands.some((command) => command.action === 'getText'),
        'binding.input.getText 应发真实 viewCommand');
    assert.ok(bindClickRequest, 'button.on("click") 应发 bindClick');
    assert.strictEqual(bindClickRequest.id, 3);
    assert.strictEqual(clicked, 0);

    events.push({type: 'click', viewId: 3});
    await delay(180);
    assert.strictEqual(clicked, 11, 'click 事件应派发到同一个 binding 的两个 handler');
    ui.close();
}

async function testLayoutIdBehaviorUnchanged() {
    let showRequest = null;
    const loaded = loadUi(function (request) {
        if (request.op === 'show') {
            showRequest = request;
            return {ok: true, mode: 'overlay', views: {}};
        }
        if (request.op === 'pollEvents') return {ok: true, events: []};
        if (request.op === 'close') return {ok: true};
        return {ok: true};
    });
    const ui = loaded.ui;
    class LegacyActivity extends ui.Activity {
        get layoutId() { return 123; }
    }
    ui.setMainActivity(LegacyActivity);
    assert.ok(showRequest, '应发出 show 请求');
    assert.strictEqual(showRequest.layoutId, 123, '原 layoutId 入口必须保留');
    assert.strictEqual(showRequest.layoutXml, undefined, '无 layoutXml 时不能带字符串入口');
    ui.close();
}

async function testNativeUiPublicApis() {
    const layoutId = -2130640896;
    const button = {__ajsJavaObject__: true};
    const inflated = {__ajsJavaObject__: true};
    const window = {};
    let content = {findViewById: id => id === 7 ? button : null};
    const native = {
        __ajsJavaObject__: true, __handle: 99,
        findViewById: id => content.findViewById(id),
        getWindow: () => window,
        getLayoutInflater: () => ({inflate: (id, parent) => {
            assert.strictEqual(id, layoutId);
            assert.strictEqual(parent, null);
            return inflated;
        }}),
    };
    const session = {
        getActivity: () => native,
        getContentView: () => content,
        viewId: name => name === 'button' ? 7 : 0,
        setContentView: view => { content = view; },
        setActivityCallbacks: () => {},
    };
    const originalAndroid = {};
    const loaded = loadUi(request => {
        if (request.op === 'loadAndroidResources') return {ok: true, compiled: true};
        if (request.op === 'layoutId') return {ok: true, id: layoutId};
        if (request.op === 'pollEvents') return {ok: true, events: []};
        return {ok: true, views: {button: 7}};
    }, {
        android: originalAndroid,
        __ajsEnableJavaCallbacks: () => {},
        $autojs: {java: {findClass: name => name.endsWith('$ActivityCallbacks')
            ? function (value) {return value;} : session}},
    });
    const ui = loaded.ui;
    await ui.loadAndroidResources();
    assert.strictEqual(ui.currentActivity(), null);
    class NativeActivity extends ui.Activity { get layoutId() { return layoutId; } }
    try {
        const opened = ui.setMainActivity(NativeActivity);
        const show = loaded.requests.find(r => r.op === 'show');
        assert.strictEqual(show.realJava, true);
        assert.strictEqual(show.layoutId, layoutId, 'signed Android resource ids must survive');
        assert.strictEqual(loaded.sandbox.android, originalAndroid);
        assert.strictEqual(opened.instance.__handle, 99);
        assert.strictEqual(ui.currentActivity(), native);
        assert.strictEqual(ui.getCurrentActivity(), native);
        assert.strictEqual(ui.getWindow(), window);
        assert.strictEqual(ui.findViewById('button'), button);
        assert.strictEqual(ui.findViewById(7), button);
        assert.strictEqual(ui.findViewById('missing'), null);
        assert.strictEqual(loaded.sandbox.__ajsBindJavaView(content).button, button);
        assert.strictEqual(ui.inflate(layoutId), inflated);
        assert.strictEqual(ui.inflate('main'), inflated);
        assert.strictEqual(ui.setContentView(inflated), inflated);
        assert.strictEqual(content, inflated);
        assert.strictEqual(ui.setContentView('main'), inflated);
        assert(!loaded.requests.some(r => ['findViewById', 'inflate', 'setContentView'].includes(r.op)),
            'native UI must not route to legacy marker/remote View operations');
    } finally { ui.close(); }
    assert.strictEqual(ui.currentActivity(), null);
    assert.strictEqual(loaded.sandbox.__ajsBindJavaView, undefined);
    assert.throws(() => ui.getWindow(), /没有运行中的/);
}

async function testComplexMarkersDoNotThrow() {
    let testBody = null;
    let bindClickRequest = null;
    const loaded = loadUi(function (request) {
        if (request.op === 'show') {
            return {
                ok: true,
                mode: 'activity',
                views: {toolbar: 11, grid: 12, list: 13, canvas: 14, button: 15},
            };
        }
        if (request.op === 'pollEvents') {
            return {
                ok: true,
                events: [{type: 'content_view_set', views: {toolbar: 11, grid: 12, list: 13, canvas: 14, button: 15}}],
            };
        }
        if (request.op === 'bindClick') {
            bindClickRequest = request;
            return {ok: true};
        }
        return {ok: true};
    });
    const ui = loaded.ui;
    class SampleActivity extends ui.Activity {
        get layoutXml() { return '<column><button id="button"/></column>'; }
        onContentViewSet(view) {
            testBody = () => {
                this.setSupportActionBar(view.binding.toolbar);
                const grid = view.binding.grid;
                grid.setItemTemplate('<text/>');
                grid.on('item_created', () => {});
                grid.setDataSource([]);
                const list = view.binding.list;
                list.setItemTemplate('<text/>');
                list.on('item_created', () => {});
                list.setDataSource([]);
                view.binding.canvas.on('draw', () => {});
                view.binding.button.on('click', () => {});
            };
        }
    }
    ui.setMainActivity(SampleActivity);
    assert.ok(testBody, 'onContentViewSet 应被派发');
    testBody();
    assert.ok(bindClickRequest, 'button click 仍应绑定');
    assert.strictEqual(bindClickRequest.id, 15);
    ui.close();
}

async function testNoLayoutRestoresJavaGlobals() {
    const originalAndroid = {marker: 'original-android'};
    const loaded = loadUi(function () {
        throw new Error('不应调用宿主');
    });
    loaded.sandbox.android = originalAndroid;
    class EmptyActivity extends loaded.ui.Activity {}
    let thrown = null;
    try {
        loaded.ui.setMainActivity(EmptyActivity);
    } catch (error) {
        thrown = error;
    }
    assert.ok(thrown, '没有 layoutId/layoutXml 应抛错');
    assert.match(String(thrown.message), /layoutId 或 layoutXml/);
    assert.strictEqual(loaded.sandbox.android, originalAndroid,
        '抛错后必须恢复被替换的 Java 全局代理');
    assert.strictEqual(loaded.sandbox.__ajsUiGlobalsPatched, false);
}

async function testHostFailureRestoresJavaGlobals() {
    const originalAndroid = {marker: 'original-android'};
    const loaded = loadUi(function (request) {
        if (request.op === 'show') return {ok: false, error: 'layoutXml inflate 失败：boom'};
        return {ok: true, events: []};
    });
    loaded.sandbox.android = originalAndroid;
    class InlineActivity extends loaded.ui.Activity {
        get layoutXml() { return '<text id="x" text="x"/>'; }
    }
    let thrown = null;
    try {
        loaded.ui.setMainActivity(InlineActivity);
    } catch (error) {
        thrown = error;
    }
    assert.ok(thrown, '宿主 show 失败必须向上抛');
    assert.match(String(thrown.message), /layoutXml inflate 失败/);
    assert.strictEqual(loaded.sandbox.android, originalAndroid,
        '宿主失败后必须恢复 Java 全局代理');
    assert.strictEqual(loaded.sandbox.__ajsUiGlobalsPatched, false);
}

async function testLayoutXmlGetterFailureRestores() {
    const originalAndroid = {marker: 'original-android'};
    const loaded = loadUi(function () {
        return {ok: true, events: []};
    });
    loaded.sandbox.android = originalAndroid;
    class BadActivity extends loaded.ui.Activity {
        get layoutXml() { throw new Error('getter boom'); }
    }
    let thrown = null;
    try {
        loaded.ui.setMainActivity(BadActivity);
    } catch (error) {
        thrown = error;
    }
    assert.ok(thrown);
    assert.match(String(thrown.message), /getter boom/);
    assert.strictEqual(loaded.sandbox.android, originalAndroid);
}

(async function main() {
    console.log('Node24 layoutXml contract tests');
    await test('layoutXml 传给宿主，基础 text/input binding 走真实 viewCommand，button click 回传', testLayoutXmlRequestAndRealBinding);
    await test('原 layoutId 入口行为不变', testLayoutIdBehaviorUnchanged);
    await test('原生 UI 公共接口返回真实对象，负资源 id 保留，关闭后清理绑定', testNativeUiPublicApis);
    await test('grid/list/canvas marker 链式调用不抛错，button click 仍可绑定', testComplexMarkersDoNotThrow);
    await test('没有 layout 时抛错并恢复 JavaGlobals', testNoLayoutRestoresJavaGlobals);
    await test('宿主 show 失败时抛错并恢复 JavaGlobals', testHostFailureRestoresJavaGlobals);
    await test('layoutXml getter 抛错时恢复 JavaGlobals', testLayoutXmlGetterFailureRestores);

    console.log('');
    console.log(passed + ' passed, ' + failed + ' failed');
    if (failed > 0) {
        process.exitCode = 1;
    }
})();
