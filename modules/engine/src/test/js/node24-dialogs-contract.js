'use strict';

/*
 * Focused production-prelude contract test for Node24 dialogs (dev-1095 MI8 failure:
 * "dialogs.showSelectDialog is not a function").
 *
 * Usage: node node24-dialogs-contract.js <prelude.js> [workDir]
 *
 * The fake host below implements the same JSON envelope as Node24HostBridge and the
 * NodeUiBridgeFacade static calls used by the prelude. It does NOT implement Android UI;
 * real AlertDialog behavior must still be checked on the MI8 by the main controller.
 */

const fs = require('fs');
const os = require('os');
const path = require('path');
const childProcess = require('child_process');

const preludePath = process.argv[2] ? path.resolve(process.argv[2]) : '';
const suppliedWorkDir = process.argv[3];
if (!preludePath) {
    console.error('FAIL: usage: node node24-dialogs-contract.js <prelude.js> [workDir]');
    process.exit(1);
}

const ownsWorkDir = !suppliedWorkDir;
const workDir = suppliedWorkDir
    ? path.resolve(suppliedWorkDir)
    : fs.mkdtempSync(path.join(os.tmpdir(), 'node24-dialogs-contract-'));
fs.mkdirSync(workDir, { recursive: true });
const originalCwd = process.cwd();

const FACADE = 'com.stardust.autojs.engine.node24.NodeUiBridgeFacade';
const state = {
    requests: [],
    nextDialog: null,
    nextError: null,
    facadeEvents: [],
    controls: []
};

function envelope(value) {
    return JSON.stringify({ ok: true, value: value === undefined ? null : value });
}

function hostError(message) {
    return JSON.stringify({ ok: false, error: message });
}

function mockHost(requestJson) {
    let request;
    try {
        request = JSON.parse(requestJson);
    } catch (error) {
        return hostError('invalid request JSON: ' + error.message);
    }
    state.requests.push(request);

    if (request.kind === 'poll') {
        return envelope({ '$events': [] });
    }
    if (request.kind === 'ui' && request.op === 'dialog') {
        if (state.nextError) {
            const message = state.nextError;
            state.nextError = null;
            return hostError(message);
        }
        if (request.async === true) {
            return envelope({ shown: true, async: true, token: request.token });
        }
        if (!state.nextDialog) {
            return hostError('missing mock dialog response for dialogType=' + request.dialogType);
        }
        const response = state.nextDialog;
        state.nextDialog = null;
        return JSON.stringify(response);
    }
    if (request.kind === 'java') {
        if (request.op === 'class') {
            return envelope({ '$class': request.name });
        }
        if (request.op === 'context') {
            return envelope({ '$obj': 1, '$class': 'android.content.Context' });
        }
        if (request.op === 'member' && request.cls === FACADE) {
            return envelope({ kind: 'method' });
        }
        if (request.op === 'static' && request.cls === FACADE) {
            if (request.method === 'ensureEvents') {
                return envelope(true);
            }
            if (request.method === 'sendControl') {
                const args = request.args || [];
                state.controls.push({
                    token: args[1],
                    op: args[2],
                    value: args[3]
                });
                return envelope(true);
            }
            if (request.method === 'pollEvents') {
                const events = state.facadeEvents.splice(0, state.facadeEvents.length);
                return envelope(JSON.stringify(events));
            }
        }
    }
    return envelope(null);
}

function assert(condition, message) {
    if (!condition) { throw new Error(message); }
}

function assertEqual(actual, expected, message) {
    if (actual !== expected) {
        throw new Error(message + ' (expected ' + JSON.stringify(expected)
            + ', got ' + JSON.stringify(actual) + ')');
    }
}

function assertDeepEqual(actual, expected, message) {
    const left = JSON.stringify(actual);
    const right = JSON.stringify(expected);
    if (left !== right) {
        throw new Error(message + ' (expected ' + right + ', got ' + left + ')');
    }
}

function assertThrows(fn, pattern, message) {
    let thrown = null;
    try {
        fn();
    } catch (error) {
        thrown = error;
    }
    assert(thrown, message + ': expected an exception');
    if (pattern) {
        assert(pattern.test(String(thrown && thrown.message ? thrown.message : thrown)),
            message + ': wrong error: ' + thrown);
    }
}

async function assertRejects(promise, pattern, message) {
    let thrown = null;
    try {
        await promise;
    } catch (error) {
        thrown = error;
    }
    assert(thrown, message + ': expected rejection');
    if (pattern) {
        assert(pattern.test(String(thrown && thrown.message ? thrown.message : thrown)),
            message + ': wrong error: ' + thrown);
    }
}

function lastDialogRequest() {
    for (let i = state.requests.length - 1; i >= 0; i--) {
        const request = state.requests[i];
        if (request.kind === 'ui' && request.op === 'dialog') { return request; }
    }
    throw new Error('no dialog request recorded');
}

function queueDialog(response) {
    state.nextDialog = response;
}

function waitFor(predicate, timeoutMs) {
    const started = Date.now();
    return new Promise(function (resolve, reject) {
        (function poll() {
            let value = false;
            try {
                value = predicate();
            } catch (error) {
                reject(error);
                return;
            }
            if (value) { resolve(); return; }
            if (Date.now() - started > timeoutMs) {
                reject(new Error('timeout waiting for dialog event'));
                return;
            }
            setTimeout(poll, 10);
        })();
    });
}

function normalizePath(value) {
    const resolved = fs.realpathSync(value);
    return process.platform === 'win32' ? resolved.toLowerCase() : resolved;
}

let failed = false;

(async function () {
try {
    globalThis.__ajsProjectDir = workDir;
    globalThis.__ajsScriptFile = path.join(workDir, 'main.js');
    globalThis.__autojsHost = mockHost;
    require(preludePath);

    assertEqual(normalizePath(process.cwd()), normalizePath(workDir),
        'production prelude must process.chdir to the existing __ajsProjectDir');
    assert(globalThis.__ajsBuiltinModules && globalThis.__ajsBuiltinModules.dialogs,
        'prelude must expose the built-in dialogs module');
    const dialogs = require('dialogs');
    assert(dialogs === globalThis.__ajsBuiltinModules.dialogs,
        'require("dialogs") must return the built-in module');

    // ---- API surface: old methods stay, Pro Promise methods exist ----
    [
        'alert', 'confirm', 'prompt',
        'showAlertDialog', 'showConfirmDialog', 'showInputDialog',
        'showSelectDialog', 'showSingleChoiceDialog', 'showMultiChoiceDialog',
        'buildDialog', 'showDialog'
    ].forEach(function (name) {
        assertEqual(typeof dialogs[name], 'function', 'dialogs.' + name + ' must exist');
    });

    // ---- legacy alert/confirm/prompt stay working ----
    let request;
    queueDialog({ ok: true, value: true, which: 'positive' });
    assertEqual(await dialogs.alert('legacy alert', 'body'), true, 'legacy alert resolves true');
    queueDialog({ ok: true, value: false, which: 'negative' });
    assertEqual(await dialogs.confirm('legacy confirm'), false, 'legacy confirm negative');
    queueDialog({ ok: true, value: 'typed', which: 'input' });
    assertEqual(await dialogs.prompt('legacy prompt', 'body', 'def'), 'typed',
        'legacy prompt returns text');
    request = lastDialogRequest();
    assertEqual(request.defaultValue, 'def', 'legacy prompt prefill is transported');
    queueDialog({ ok: true, canceled: true, value: null });
    assertEqual(await dialogs.prompt('legacy prompt'), null, 'legacy prompt cancel returns null');

    // ---- invalid input rejects asynchronously (all Pro Node dialog methods are Promises) ----
    await assertRejects(dialogs.showSelectDialog('bad items', 'not-an-array'), /items/,
        'invalid select items must reject, not throw before returning a Promise');

    // ---- showSelectDialog / cancel ----
    queueDialog({ ok: true, value: 1, which: 'item_select' });
    assertEqual(await dialogs.showSelectDialog('pick', ['A', 'B', 'C']), 1,
        'showSelectDialog returns the clicked index');
    request = lastDialogRequest();
    assertEqual(request.dialogType, 'select', 'select request dialogType');
    assertDeepEqual(request.items, ['A', 'B', 'C'], 'select request items');
    assertEqual(request.async, undefined, 'convenience dialogs stay on the one-shot transport');

    queueDialog({ ok: true, canceled: true, value: null });
    assertEqual(await dialogs.showSelectDialog('pick', ['A']), -1,
        'showSelectDialog cancel returns -1');

    // ---- confirm / alert / input ----
    queueDialog({ ok: true, value: true, which: 'positive' });
    assertEqual(await dialogs.showConfirmDialog('sure?'), true, 'confirm positive');
    queueDialog({ ok: true, value: false, which: 'negative' });
    assertEqual(await dialogs.showConfirmDialog('sure?'), false, 'confirm negative');
    queueDialog({ ok: true, canceled: true, value: false });
    assertEqual(await dialogs.showConfirmDialog('sure?'), false, 'confirm cancel false');

    queueDialog({ ok: true, value: true, which: 'positive' });
    assertEqual(await dialogs.showAlertDialog('notice'), undefined,
        'showAlertDialog resolves undefined after dismissal');

    queueDialog({ ok: true, value: 'hello', which: 'input' });
    assertEqual(await dialogs.showInputDialog('name', 'prefill'), 'hello', 'input positive');
    request = lastDialogRequest();
    assertEqual(request.defaultValue, 'prefill', 'input prefill is transported');
    queueDialog({ ok: true, canceled: true, value: null });
    assertEqual(await dialogs.showInputDialog('name'), null, 'input cancel returns null');

    // ---- single / multi choice ----
    queueDialog({ ok: true, value: 2, which: 'single_choice' });
    assertEqual(await dialogs.showSingleChoiceDialog('gender', ['M', 'F', 'X'], -1), 2,
        'single choice positive');
    queueDialog({ ok: true, canceled: true, value: -1 });
    assertEqual(await dialogs.showSingleChoiceDialog('gender', ['M', 'F'], -1), -1,
        'single choice cancel returns -1');

    queueDialog({ ok: true, value: [0, 2], which: 'multi_choice' });
    assertDeepEqual(await dialogs.showMultiChoiceDialog('fruit', ['a', 'b', 'c'], [1]), [0, 2],
        'multi choice returns selected indices');
    queueDialog({ ok: true, canceled: true, value: [] });
    assertDeepEqual(await dialogs.showMultiChoiceDialog('fruit', ['a'], []), [],
        'multi choice cancel returns an empty array');

    // ---- earliest host error must reject, not resolve a fallback ----
    state.nextError = '真实宿主失败';
    await assertRejects(dialogs.showSelectDialog('boom', ['A']), /真实宿主失败/,
        'host error must propagate');
    state.nextError = '异步真实失败';
    await assertRejects(dialogs.showDialog({ title: 'async boom' }), /异步真实失败/,
        'async show error must reject before any event polling succeeds');

    // ---- functions must not be silently dropped by JSON.stringify ----
    await assertRejects(dialogs.showDialog({
        title: 'callback must be local',
        onClick: function () { }
    }), /不能包含函数/, 'function options must be rejected instead of serialized away');

    // ---- buildDialog is async and returns a real event emitter ----
    const pendingBuilder = await dialogs.buildDialog({ title: 'builder' });
    assertEqual(typeof pendingBuilder.on, 'function', 'buildDialog resolves a Dialog object');
    assertThrows(function () { pendingBuilder.setProgress(1); }, /progress/,
        'setProgress on a non-progress dialog must fail clearly');

    // ---- showDialog multi-choice: resolves when shown, then routes real events ----
    const multiDialog = await dialogs.showDialog({
        title: '喜欢的水果有',
        items: ['苹果', '香蕉', '菠萝', '椰子'],
        itemsSelectMode: 'multi',
        positive: '确定',
        negative: '取消'
    });
    request = lastDialogRequest();
    assertEqual(request.async, true, 'builder uses the async transport');
    assertEqual(request.dialogType, 'multi_choice', 'builder multi dialogType');
    assertEqual(typeof request.token, 'string', 'builder request carries a token');
    const multiHandler = [];
    const cancelHandler = [];
    const dismissHandler = [];
    multiDialog
        .on('multi_choice', function (indices, texts, dialog) {
            multiHandler.push({ indices: indices, texts: texts, sameDialog: dialog === multiDialog });
        })
        .on('cancel', function () { cancelHandler.push('cancel'); })
        .on('dismiss', function () { dismissHandler.push('dismiss'); });
    state.facadeEvents.push({
        token: request.token,
        type: 'multi_choice',
        payload: { indices: [0, 2], texts: ['苹果', '菠萝'] }
    });
    await waitFor(function () { return multiHandler.length === 1; }, 1000);
    assertDeepEqual(multiHandler[0].indices, [0, 2], 'multi_choice indices reached JS');
    assertDeepEqual(multiHandler[0].texts, ['苹果', '菠萝'], 'multi_choice texts reached JS');
    assertEqual(multiHandler[0].sameDialog, true, 'multi_choice receives the dialog argument');
    assertEqual(cancelHandler.length, 0, 'positive multi_choice must not emit cancel');
    await waitFor(function () { return dismissHandler.length === 1; }, 1000);
    assertEqual(multiDialog.isShowing(), false, 'terminal event closes the dialog handle');

    // ---- negative button emits negative + cancel + documented cancel result ----
    const negativeDialog = await dialogs.showDialog({
        title: 'multi cancel',
        items: ['A', 'B'],
        itemsSelectMode: 'multi',
        positive: '确定',
        negative: '取消'
    });
    request = lastDialogRequest();
    const negativeEvents = [];
    negativeDialog
        .on('multi_choice', function (indices, texts) {
            negativeEvents.push(['multi_choice', indices, texts]);
        })
        .on('negative', function () { negativeEvents.push(['negative']); })
        .on('cancel', function () { negativeEvents.push(['cancel']); })
        .on('dismiss', function () { negativeEvents.push(['dismiss']); });
    state.facadeEvents.push({ token: request.token, type: 'negative', payload: {} });
    await waitFor(function () { return negativeEvents.length >= 4; }, 1000);
    assertDeepEqual(negativeEvents[0], ['negative'], 'negative event reaches JS');
    assertDeepEqual(negativeEvents[1], ['cancel'], 'negative also emits cancel for the sample');
    assertDeepEqual(negativeEvents[2], ['multi_choice', [], []],
        'multi_choice cancel values follow the cancel event');
    assertDeepEqual(negativeEvents[3], ['dismiss'], 'negative emits dismiss last');

    // ---- input builder ----
    const inputDialog = await dialogs.showDialog({ title: 'input', inputPrefill: 'x' });
    request = lastDialogRequest();
    assertEqual(request.dialogType, 'input', 'inputPrefill infers input dialog');
    const inputs = [];
    inputDialog.on('input', function (text, dialog) {
        inputs.push({ text: text, sameDialog: dialog === inputDialog });
    });
    state.facadeEvents.push({ token: request.token, type: 'input', payload: { value: 'hello' } });
    await waitFor(function () { return inputs.length === 1; }, 1000);
    assertEqual(inputs[0].text, 'hello', 'input event text reaches JS');
    assertEqual(inputs[0].sameDialog, true, 'input event receives the dialog argument');

    // ---- progress: shown immediately, setProgress/dismiss use real control broadcasts ----
    const progressDialog = await dialogs.showDialog({
        title: '文件下载中',
        progress: { max: 100, showMinMax: true },
        cancelable: false
    });
    request = lastDialogRequest();
    assertEqual(request.dialogType, 'progress', 'progress dialogType');
    assertEqual(progressDialog.getMaxProgress(), 100, 'progress max is exposed');
    assertEqual(progressDialog.isShowing(), true, 'progress dialog is showing after await');
    progressDialog.setProgress(42);
    let control = state.controls.filter(function (entry) {
        return entry.token === request.token && entry.op === 'progress';
    })[0];
    assert(control, 'setProgress must send a progress control');
    assertEqual(control.value, '42', 'progress control value');
    const progressDismissed = [];
    progressDialog.on('dismiss', function (dialog) {
        progressDismissed.push(dialog === progressDialog);
    });
    progressDialog.dismiss();
    control = state.controls.filter(function (entry) {
        return entry.token === request.token && entry.op === 'dismiss';
    })[0];
    assert(control, 'dismiss must send a dismiss control');
    state.facadeEvents.push({ token: request.token, type: 'dismiss', payload: {} });
    await waitFor(function () { return progressDismissed.length === 1; }, 1000);
    assertEqual(progressDismissed[0], true, 'dismiss event reaches JS with the dialog');
    assertEqual(progressDialog.isShowing(), false, 'dismiss closes the local handle');

    // ---- two async dialogs must route by token, not by arrival order ----
    const first = await dialogs.showDialog({ title: 'first', positive: 'OK' });
    const firstRequest = lastDialogRequest();
    const second = await dialogs.showDialog({ title: 'second', positive: 'OK' });
    const secondRequest = lastDialogRequest();
    const routed = [];
    first.on('positive', function () { routed.push('first'); });
    second.on('positive', function () { routed.push('second'); });
    state.facadeEvents.push({ token: secondRequest.token, type: 'positive', payload: {} });
    state.facadeEvents.push({ token: firstRequest.token, type: 'positive', payload: {} });
    await waitFor(function () { return routed.length === 2; }, 1000);
    assertDeepEqual(routed.sort(), ['first', 'second'], 'dialog events route by token');

    // ---- the production prelude must still fail loudly on a bad cwd ----
    const missing = path.join(workDir, 'missing-project-' + process.pid);
    const probeOutput = path.join(workDir, 'cwd-probe-' + process.pid + '.txt');
    const script = 'const fs = require("fs");'
        + 'globalThis.__ajsProjectDir = ' + JSON.stringify(missing) + ';'
        + 'try { require(' + JSON.stringify(preludePath) + ');'
        + 'fs.writeFileSync(' + JSON.stringify(probeOutput) + ', "NO_ERROR"); }'
        + 'catch (error) { fs.writeFileSync(' + JSON.stringify(probeOutput)
        + ', String(error && error.message ? error.message : error)); }';
    // The harness sandbox cannot capture another Node process through stdio pipes
    // (EPERM on the named pipe); write the probe result to a file instead.
    const child = childProcess.spawnSync(process.execPath, ['-e', script], {
        cwd: originalCwd,
        stdio: 'ignore'
    });
    assertEqual(child.status, 0, 'cwd failure probe must run (error='
        + String(child.error || '') + ')');
    const probeResult = fs.readFileSync(probeOutput, 'utf8');
    assert(probeResult.indexOf('无法切换到脚本工作目录') >= 0,
        'prelude must not swallow process.chdir errors: ' + probeResult);
    assert(probeResult.indexOf('NO_ERROR') < 0, 'bad cwd must not load prelude successfully');

    console.log('OK node24 dialogs contract cwd=' + process.cwd()
        + ' dialogRequests=' + state.requests.filter(function (entry) {
            return entry.kind === 'ui' && entry.op === 'dialog';
        }).length
        + ' events=' + (multiHandler.length + negativeEvents.length + inputs.length));
} catch (error) {
    failed = true;
    console.error('FAIL: ' + (error && error.stack ? error.stack : error));
} finally {
    try {
        process.chdir(originalCwd);
    } catch (ignored) {
    }
    if (ownsWorkDir) {
        try {
            fs.rmSync(workDir, { recursive: true, force: true });
        } catch (ignored) {
        }
    }
}
if (failed) { process.exit(1); }
process.exit(0);
})();
