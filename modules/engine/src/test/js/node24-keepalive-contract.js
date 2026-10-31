'use strict';

/*
 * bugs 6.1/6.2/6.3 回归契约（真实 production prelude.js）。
 *
 *   6.1 keepRunning 运行时标记：每次 keepRunning 都向宿主上报 running+holds，
 *       宿主侧（Node24HostBridge）据此保持保活，不依赖入口源码扫描；
 *   6.2 持续保活：保活是引用计数（Pro cancelKeepRunning 语义），ui.js 的
 *       cleanupUiLifecycle() → $autojs.stopRunning() 只能释放它自己那一次，
 *       用户在入口/require 模块里申请的保活必须继续有效；
 *   6.3 纯 Java 回调上下文里的 setTimeout：进入 __ajsDispatchJavaCallback
 *       时挂宿主定时器兜底，回调里的 setTimeout 一定执行（经 poll 事件回灌），
 *       退出后恢复原生 setTimeout。
 *
 * Usage: node node24-keepalive-contract.js <prelude.js> [workDir]
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');

const preludePath = process.argv[2] ? path.resolve(process.argv[2]) : '';
const suppliedWorkDir = process.argv[3];
if (!preludePath) {
    console.error('FAIL: usage: node node24-keepalive-contract.js <prelude.js> [workDir]');
    process.exit(1);
}

const workDir = suppliedWorkDir
    ? path.resolve(suppliedWorkDir)
    : fs.mkdtempSync(path.join(os.tmpdir(), 'node24-keepalive-contract-'));
fs.mkdirSync(workDir, { recursive: true });

const state = {
    calls: [],
    events: [],
    keepAlive: [],
    hostTimers: new Map(),
    nextHostTimerCallback: 1
};

function envelope(value) {
    return JSON.stringify({ ok: true, value: value === undefined ? null : value });
}
function hostError(message) {
    return JSON.stringify({ ok: false, error: message });
}

globalThis.__ajsEnableJavaCallbacks = function () { return 991; };
globalThis.__autojsHost = function (requestJson) {
    let request;
    try { request = JSON.parse(requestJson); } catch (error) { return hostError('invalid JSON: ' + error.message); }
    state.calls.push(request);
    if (request.kind === 'poll') {
        return envelope({ $events: state.events.splice(0, 64) });
    }
    if (request.kind === 'keepalive') {
        state.keepAlive.push({ running: request.running === true, holds: Number(request.holds || 0) });
        return envelope(request.running === true);
    }
    if (request.kind === 'timers') {
        if (request.op === 'set') {
            state.hostTimers.set(Number(request.id), Number(request.delay) || 0);
            return envelope(true);
        }
        if (request.op === 'cancel') {
            state.hostTimers.delete(Number(request.id));
            return envelope(true);
        }
        if (request.op === 'cancelAll') {
            state.hostTimers.clear();
            return envelope(true);
        }
        return hostError('unsupported timers op: ' + request.op);
    }
    if (request.kind === 'java') {
        if (request.op === 'isInterface') return envelope(true);
        if (request.op === 'proxyInterface') {
            return envelope({ $obj: 7, $class: request.cls, $callback: request.callback.$callback, session: 991 });
        }
        if (request.op === 'member') return envelope({ kind: 'method' });
        if (request.op === 'class') return envelope({ $class: request.name });
        if (request.op === 'member') return envelope({ kind: 'method' });
        return envelope(null);
    }
    return envelope(null);
};

function assert(condition, message) {
    if (!condition) { throw new Error(message); }
}
function assertEqual(actual, expected, message) {
    if (actual !== expected) {
        throw new Error(message + ': expected ' + JSON.stringify(expected) + ', got ' + JSON.stringify(actual));
    }
}
function fail(message) { console.error('FAIL: ' + message); process.exit(1); }
function delay(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }

function deliverHostTimer(id) {
    state.events.push({ type: '__ajs_host_timer', payload: { id: Number(id) } });
    return globalThis.__autojsPoll();
}

(async function () {
try {
    globalThis.__ajsProjectDir = workDir;
    globalThis.__ajsScriptFile = path.join(workDir, 'main.js');
    // 入口源码里不出现 keepRunning 字面量：模拟 require 模块里调用（bugs 6.1）。
    require(preludePath);
    const autojs = globalThis.$autojs;
    assert(typeof autojs.keepRunning === 'function', 'keepRunning missing');
    assert(typeof autojs.cancelKeepRunning === 'function', 'cancelKeepRunning must exist (Pro 兼容)');

    // ---- 6.1/6.2: 引用计数 + 宿主运行时标记 ----
    const tokenUser = autojs.keepRunning();
    const tokenUi = autojs.keepRunning();
    assertEqual(autojs.keepRunningCount(), 2, 'two keepRunning calls must hold two references');
    assert(typeof tokenUser === 'number' && tokenUser !== tokenUi, 'keepRunning must return distinct tokens');
    assertEqual(state.keepAlive[0].holds, 1, 'first keepRunning reports holds=1');
    assertEqual(state.keepAlive[1].holds, 2, 'second keepRunning reports holds=2');
    assert(state.keepAlive.every(function (entry) { return entry.running === true; }),
        'runtime keepalive must stay running while any hold remains');

    // ui.js cleanupUiLifecycle() 只释放它自己那次：用户的保活必须保留。
    assertEqual(autojs.stopRunning(), true, 'stopRunning() must release one hold');
    assertEqual(autojs.keepRunningCount(), 1, 'user keepRunning must survive ui.js stopRunning');
    assertEqual(state.keepAlive[state.keepAlive.length - 1].running, true,
        'host must still see running=true after a single release');
    assertEqual(state.keepAlive[state.keepAlive.length - 1].holds, 1, 'host must see the remaining reference count');

    assertEqual(autojs.stopRunning(), true, 'releasing the last hold succeeds');
    assertEqual(autojs.keepRunningCount(), 0, 'no holds after the last release');
    assertEqual(state.keepAlive[state.keepAlive.length - 1].running, false, 'host must see running=false after the last release');
    assertEqual(state.keepAlive[state.keepAlive.length - 1].holds, 0, 'host must see holds=0 after the last release');
    assertEqual(autojs.stopRunning(), false, 'stopRunning on zero holds is a no-op');

    // Pro 的显式取消：只释放指定 token。
    const tokenA = autojs.keepRunning();
    const tokenB = autojs.keepRunning();
    assertEqual(autojs.cancelKeepRunning(tokenA), true, 'cancelKeepRunning(token) releases that token');
    assertEqual(autojs.keepRunningCount(), 1, 'sibling hold remains after explicit cancel');
    assertEqual(autojs.cancelKeepRunning(tokenA), false, 'cancelling the same token twice is a no-op');
    assertEqual(autojs.cancelKeepRunning(tokenB), true, 'cancel the remaining token');
    assertEqual(autojs.keepRunningCount(), 0, 'all holds released');

    // ---- 6.3: 宿主定时器兜底 ----
    let hostTimerFires = 0;
    const hostHandle = autojs.setTimeout(function () { hostTimerFires++; }, 5);
    assert(state.hostTimers.size === 1, 'autojs.setTimeout must arm the host fallback timer');
    const firstHostTimerId = Array.from(state.hostTimers.keys())[0];
    assertEqual(deliverHostTimer(firstHostTimerId), 1, 'host timer event must be polled');
    assertEqual(hostTimerFires, 1, 'host fallback timer must run its callback');
    assertEqual(state.hostTimers.size, 0, 'fired host timer is removed');

    let cancelledFires = 0;
    autojs.setTimeout(function () { cancelledFires++; }, 5);
    const cancelId = Array.from(state.hostTimers.keys())[0];
    autojs.clearTimeout(cancelId);
    assertEqual(state.hostTimers.size, 0, 'clearTimeout must cancel the host timer');
    deliverHostTimer(cancelId);
    assertEqual(cancelledFires, 0, 'cancelled host timer must not fire');

    // ---- 6.3: 纯 Java 回调上下文里的 setTimeout ----
    const iface = globalThis.$java.findClass('java.lang.Runnable');
    let javaCallbackTimerFires = 0;
    globalThis.$java.implement(iface, {
        run: function () {
            // 这个 setTimeout 位于 Java 回调上下文（native 从 Java 线程派发进来）。
            autojs.setTimeout(function () { javaCallbackTimerFires++; }, 5);
        }
    });
    const proxyCall = state.calls.filter(function (call) {
        return call.kind === 'java' && call.op === 'proxyInterface';
    }).pop();
    assert(proxyCall && proxyCall.callback && proxyCall.callback.$callback, 'interface callback must be registered');

    let globalTimerFires = 0;
    const nativeTimeoutBefore = globalThis.setTimeout;
    const reply = JSON.parse(globalThis.__ajsDispatchJavaCallback(JSON.stringify({
        callbackId: proxyCall.callback.$callback, method: 'run', args: []
    })));
    assertEqual(reply.ok, true, 'Java callback dispatch must succeed: ' + reply.error);
    assert(globalThis.setTimeout === nativeTimeoutBefore,
        'global setTimeout must be restored after the Java callback returns');
    assert(state.hostTimers.size >= 1, 'setTimeout inside a Java callback must arm the host fallback');
    const javaTimerId = Array.from(state.hostTimers.keys())[0];
    deliverHostTimer(javaTimerId);
    assertEqual(javaCallbackTimerFires, 1, 'setTimeout inside a Java callback must fire');
    globalThis.setTimeout(function () { globalTimerFires++; }, 0);
    await delay(20);
    assertEqual(globalTimerFires, 1, 'global setTimeout must keep working after the callback context');
    assertEqual(autojs.keepRunningCount(), 0, 'no keepalive hold must leak');

    // ---- 6.2: 保活真正撑住事件循环（子进程存活检查） ----
    const child = spawn(process.execPath, ['-e',
        'const p = ' + JSON.stringify(preludePath) + ';' +
        'globalThis.__ajsProjectDir = ' + JSON.stringify(workDir) + ';' +
        'globalThis.__ajsScriptFile = ' + JSON.stringify(path.join(workDir, 'child.js')) + ';' +
        'globalThis.__autojsHost = (s) => JSON.stringify({ ok: true, value: (JSON.parse(s).kind === "poll" ? { $events: [] } : true) });' +
        'require(p);' +
        // 入口里不出现 keepRunning 字面量：模拟 require 模块内调用。
        'globalThis.$autojs["keep" + "Running"]();' +
        'process.stdout.write("CHILD_READY\\n");'
    ], { stdio: ['ignore', 'pipe', 'pipe'] });
    let childOut = '';
    let childExit = null;
    child.stdout.on('data', function (chunk) { childOut += chunk.toString('utf8'); });
    child.on('exit', function (code) { childExit = code; });
    const deadline = Date.now() + 4000;
    while (Date.now() < deadline && childOut.indexOf('CHILD_READY') < 0 && childExit === null) {
        await delay(50);
    }
    assert(childOut.indexOf('CHILD_READY') >= 0, 'child script must reach keepRunning');
    await delay(1200);
    assertEqual(childExit, null, 'a script that only called keepRunning must keep running (6.2)');
    child.kill('SIGKILL');
    await delay(50);

    console.log('OK node24 keepalive contract holds=' + autojs.keepRunningCount()
        + ' hostTimerFires=' + hostTimerFires
        + ' javaCallbackTimerFires=' + javaCallbackTimerFires
        + ' childAlive=' + (childExit === null));
} catch (error) {
    console.error('FAIL: ' + (error && error.stack ? error.stack : error));
    process.exitCode = 1;
} finally {
    try { if (globalThis.__ajsReleaseAllKeepAlive) globalThis.__ajsReleaseAllKeepAlive(); } catch (ignored) { }
    if (!suppliedWorkDir) {
        try { fs.rmSync(workDir, { recursive: true, force: true }); } catch (ignored) { }
    }
}
})();
