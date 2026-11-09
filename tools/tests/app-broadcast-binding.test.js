// 装配级契约测试：断言三个引擎「脚本真正拿到的 app 对象」上确实挂上了广播接收 API。
// 为什么需要它（dev-1442 真机教训）：mock 级测试只验证「模块自己的 app 有方法」，
// 而脚本拿到的 app 是另一个对象（Rhino: Object.create(runtime.app) + __app__.js；
// QuickJS: C++ bootstrap 的 global app 被本模块**复制并冻结**；Node: assets/node24 的 appModule）
// → 只测模块内部就会漏掉「没挂到真 app」这类装配错误。这里直接**加载真实绑定文件**并断言返回/导出的对象。
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../..');
let checks = 0;
function check(name, run) { run(); checks++; console.log('PASS ' + name); }

/** 假的 Java 侧登记表（记录调用并返回与真实现同形状的句柄 JSON）。 */
function mockReceivers(state) {
    return {
        register(context, actions, categories, schemes, permission, exported, onIntent, onError) {
            state.calls.push({ context, actions: [].slice.call(actions), categories: [].slice.call(categories || []),
                schemes: [].slice.call(schemes || []), permission, exported, onIntent, onError });
            return JSON.stringify({ id: 'bcr-' + state.calls.length, actions: [].slice.call(actions), categories: [],
                schemes: [], permission: null, exported: !!exported, registeredAt: 1700000000000, exportFlag: exported ? 2 : 4 });
        },
        unregister(id) { state.unregistered.push(String(id)); return true; },
        unregisterAll() { return 0; }
    };
}

// ---------------------------------------------------------------------------
// ① Rhino：加载真实的 __app__.js，断言脚本 app 上有方法且能用
// ---------------------------------------------------------------------------
check('rhino __app__.js 把三个 API 挂到脚本 app 上并真的调用登记表', () => {
    const state = { calls: [], unregistered: [] };
    const receivers = mockReceivers(state);
    const sandbox = {
        module: { exports: null },
        importClass: () => {},
        android: { content: { Intent: class Intent {} } },
        Packages: { com: { stardust: { autojs: { core: { content: { ScriptBroadcastReceivers: receivers } } } } } },
        // __app__.js 在加载期还会读 BuildConfig（Rhino 里 com 是 Packages 的顶层别名）
        com: { jdkshen: { aijspro: { BuildConfig: { VERSION_CODE: 1, VERSION_NAME: '1.0' } } } },
        console
    };
    vm.runInNewContext(fs.readFileSync(path.join(root, 'modules/engine/src/main/assets/modules/__app__.js'), 'utf8'),
        sandbox, { filename: '__app__.js' });
    assert.equal(typeof sandbox.module.exports, 'function', '__app__.js 必须导出工厂函数');
    const jsApp = { viewFile() { return 'legacy'; } };
    const fakeContext = {
        name: 'ctx',
        getPackageName: () => 'com.demo.pkg',
        getPackageManager: () => ({ getPackageInfo: () => ({ versionCode: 42, versionName: '1.0' }) })
    };
    const fakeGlobal = { context: fakeContext, __asGlobal__: () => {} };
    const app = sandbox.module.exports({ app: jsApp }, fakeGlobal);
    for (const name of ['registerBroadcastReceiver', 'unregisterBroadcastReceiver', 'unregisterAllBroadcastReceivers']) {
        assert.equal(typeof app[name], 'function', 'Rhino app.' + name + ' 必须是函数');
    }
    const received = [];
    const handle = app.registerBroadcastReceiver('  demo.rhino  ', intent => received.push(intent));
    assert.equal(state.calls.length, 1);
    assert.deepEqual(state.calls[0].actions, ['demo.rhino']);   // 归一化（trim）
    assert.equal(state.calls[0].exported, false);               // 默认更安全
    assert.equal(state.calls[0].context.name, 'ctx');           // 用 global.context，不是模块自己的 context
    assert.equal(handle.id, 'bcr-1');
    assert.equal(String(handle), 'bcr-1');
    assert.equal(handle.exportFlag, 4);
    assert.equal(state.calls[0].onIntent, received.push ? state.calls[0].onIntent : null);
    state.calls[0].onIntent('intent-1');
    assert.deepEqual(received, ['intent-1']);
    assert.equal(app.unregisterBroadcastReceiver(handle), true);
    assert.deepEqual(state.unregistered, ['bcr-1']);
});

// ---------------------------------------------------------------------------
// ② QuickJS：加载真实的 pro-modern-platform.js，断言被冻结的 global app 也带这些方法
// ---------------------------------------------------------------------------
check('quickjs pro-modern-platform.js 把三个 API 拷进被冻结的 global app 且能调到登记表', () => {
    const qjsState = { calls: [], unregistered: [] };
    const qjsReceivers = mockReceivers(qjsState);
    const sandbox = {
        console, setTimeout, clearTimeout,
        context: { getPackageName: () => 'pkg', getSystemService: () => null },
        events: { bus: { on() {}, removeListener() {}, emit() {} } },
        Java: { type(name) {
            if (name === 'com.stardust.autojs.core.content.ScriptBroadcastReceivers') return qjsReceivers;
            throw new Error('Unexpected Java class ' + name);
        } },
        app: { viewFile() { return 'c++ legacy'; } }   // C++ bootstrap 的 global app（本模块会复制并冻结它）
    };
    const core = path.join(root, 'modules/engine/src/main/assets/modules/pro-modern-compat.js');
    const platform = path.join(root, 'modules/engine/src/main/assets/modules/pro-modern-platform.js');
    vm.runInNewContext(fs.readFileSync(core, 'utf8'), sandbox, { filename: core });
    vm.runInNewContext(fs.readFileSync(platform, 'utf8'), sandbox, { filename: platform });
    assert.equal(typeof sandbox.app, 'object', 'quickjs global app 必须存在');
    assert.equal(sandbox.app.viewFile(), 'c++ legacy', '未传 options 时保持 C++ 旧行为');
    for (const name of ['registerBroadcastReceiver', 'unregisterBroadcastReceiver', 'unregisterAllBroadcastReceivers']) {
        assert.equal(typeof sandbox.app[name], 'function', 'quickjs global app.' + name + ' 必须是函数');
    }
    // 冻结副本上的方法必须委托到真实现：调用要真的落到登记表，且返回值是可读句柄
    const qjsHandle = sandbox.app.registerBroadcastReceiver(' demo.qjs ', () => {});
    assert.equal(qjsState.calls.length, 1);
    assert.deepEqual(qjsState.calls[0].actions, ['demo.qjs']);
    assert.equal(qjsState.calls[0].exported, false);
    assert.equal(qjsHandle.id, 'bcr-1');
    assert.equal(qjsHandle.exportFlag, 4);
    assert.equal(sandbox.app.unregisterBroadcastReceiver(qjsHandle.id), true);
    assert.deepEqual(qjsState.unregistered, ['bcr-1']);
});

// ---------------------------------------------------------------------------
// ③ Node24：源码级装配断言（宿主 op 缺失时也必须是有名字的可辨识错误）
// ---------------------------------------------------------------------------
check('node24 legacy-runtime 把三个 API 挂到真实 appModule 上（缺 op 时明确报错）', () => {
    const source = fs.readFileSync(path.join(root, 'modules/engine/src/main/assets/node24/modules/legacy-runtime.js'), 'utf8');
    for (const name of ['registerBroadcastReceiver', 'unregisterBroadcastReceiver', 'unregisterAllBroadcastReceivers']) {
        assert.match(source, new RegExp('appModule\\.' + name + '\\s*='), 'Node appModule 必须有 ' + name);
    }
    assert.match(source, /globalThis\.app = appModule/, 'Node 脚本 globals 必须指向同一个 appModule');
    assert.match(source, /app\.registerBroadcastReceiver 在 nodejs 引擎暂不可用/, '缺宿主 op 时必须给出可辨识错误');
});
console.log('RESULT ' + checks + ' assembly checks passed (real binding sources; no device claim)');
