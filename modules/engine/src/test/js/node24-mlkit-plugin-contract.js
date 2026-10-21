'use strict';

/*
 * Node24 plugins MLKit compatibility contract test.
 *
 * Usage: node node24-mlkit-plugin-contract.js [plugins.js]
 *
 * This test executes the production Node24 plugins.js factory (the module registration
 * callback, not a copy) against:
 *   - a recording mock `ocr` module, so only the adapter contract is pinned here;
 *   - a JSON-envelope host mock for the generic plugin info/list/isInstalled calls.
 *
 * It deliberately does NOT load the real ocr module, ML Kit, Android, or the host bridge:
 * the real backend path (require('ocr').createOCR({backend:'mlkit'})) is covered by the
 * existing ocr module/runtime tests and must still be verified on a device by the main
 * controller. Here we only prove that the adapter forwards the backend, lazily shares one
 * session, delegates real results/errors, and releases exactly once.
 */

const fs = require('fs');
const path = require('path');
const Module = require('module');

const pluginsPath = process.argv[2]
    ? path.resolve(process.argv[2])
    : path.resolve(__dirname, '../../main/assets/node24/modules/plugins.js');

const MLKIT_PACKAGE = 'org.autojs.autojspro.plugin.mlkit.ocr';
const OTHER_PACKAGE = 'com.example.installed.plugin';

const hostState = {
    requests: [],
    info: {}
};

const ocrState = {
    requireCount: 0,
    createCalls: [],
    createImpl: null,
    nextDetectResult: null,
    sessions: []
};

function makeSession(label) {
    const session = {
        label: label,
        detectCalls: [],
        releaseCalls: 0,
        detectImpl: null,
        releaseImpl: null,
        detect: function (image, options) {
            session.detectCalls.push({ image: image, options: options });
            if (session.detectImpl) {
                return session.detectImpl(image, options);
            }
            return Promise.resolve(ocrState.nextDetectResult);
        },
        release: function () {
            session.releaseCalls += 1;
            if (session.releaseImpl) {
                return session.releaseImpl();
            }
            return true;
        },
        isReleased: function () {
            return session.releaseCalls > 0;
        }
    };
    ocrState.sessions.push(session);
    return session;
}

const ocrModule = {
    createOCR: function (options) {
        ocrState.createCalls.push({
            backend: options && options.backend,
            keys: Object.keys(options || {}).sort(),
            options: options
        });
        if (ocrState.createImpl) {
            return ocrState.createImpl(options);
        }
        return Promise.resolve(makeSession('auto-' + ocrState.createCalls.length));
    }
};

function envelope(value) {
    return JSON.stringify({ ok: true, value: value === undefined ? null : value });
}

function hostError(message) {
    return JSON.stringify({ ok: false, error: message });
}

function hostRaw(requestJson) {
    let request;
    try {
        request = JSON.parse(requestJson);
    } catch (error) {
        return hostError('invalid request JSON: ' + error.message);
    }
    hostState.requests.push(request);
    if (!request || request.kind !== 'plugins') {
        return hostError('unexpected host kind: ' + String(request && request.kind));
    }
    if (request.action === 'info') {
        const info = hostState.info[request.packageName];
        return info ? envelope(info) : hostError('未安装插件：' + request.packageName);
    }
    if (request.action === 'list') {
        return envelope([]);
    }
    if (request.action === 'isInstalled') {
        return envelope(false);
    }
    return hostError('unexpected plugin action: ' + request.action);
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

async function assertRejects(promise, expected, message) {
    let caught = null;
    try {
        await promise;
    } catch (error) {
        caught = error;
    }
    assert(caught, message + ': expected a rejection');
    if (expected instanceof RegExp) {
        assert(expected.test(String(caught && caught.message ? caught.message : caught)),
            message + ': wrong rejection: ' + caught);
    } else if (expected instanceof Error) {
        assert(caught === expected, message + ': rejection identity was not preserved (got '
            + String(caught && caught.message ? caught.message : caught) + ')');
    }
    return caught;
}

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise(function (onResolve, onReject) {
        resolve = onResolve;
        reject = onReject;
    });
    return { promise: promise, resolve: resolve, reject: reject };
}

async function flushMicrotasks() {
    await Promise.resolve();
    await Promise.resolve();
}

const originalModuleLoad = Module._load;
let registeredName = null;
let capturedFactory = null;
let failed = false;

// Intercept only the built-in `ocr` module; all other requires use Node's real loader.
Module._load = function (request) {
    if (request === 'ocr') {
        ocrState.requireCount += 1;
        return ocrModule;
    }
    return originalModuleLoad.apply(this, arguments);
};
globalThis.__autojsHostRaw = hostRaw;
globalThis.__ajsRegisterModule = function (name, factory) {
    registeredName = name;
    capturedFactory = factory;
};

(async function () {
    let plugins;
    try {
        const resolvedPluginsPath = require.resolve(pluginsPath);
        delete require.cache[resolvedPluginsPath];
        require(resolvedPluginsPath);

        // ---- the production factory must register as 'plugins' ----
        assertEqual(registeredName, 'plugins', 'plugins.js must register the plugins module');
        assertEqual(typeof capturedFactory, 'function', 'plugins.js must expose its factory');
        plugins = capturedFactory({
            host: hostRaw,
            scriptFile: path.join(__dirname, 'contract-main.js'),
            projectDir: __dirname
        });
        assert(plugins && typeof plugins.load === 'function', 'factory must return plugins.load');

        // ---- existing generic plugin info/list behavior stays intact ----
        hostState.info[OTHER_PACKAGE] = {
            packageName: OTHER_PACKAGE,
            label: '示例插件',
            versionName: '1.2.3',
            versionCode: 7,
            launcher: OTHER_PACKAGE + '.MainActivity',
            exportedServices: []
        };
        assertDeepEqual(plugins.info(OTHER_PACKAGE), hostState.info[OTHER_PACKAGE],
            'generic plugins.info must still query and return host metadata');
        await assertRejects(plugins.load(OTHER_PACKAGE), /unexpected plugin action: load/,
            'generic plugins.load must load an APK rather than return metadata');
        assertEqual(ocrState.createCalls.length, 0, 'generic plugin load must not touch OCR');
        assertEqual(ocrState.requireCount, 0, 'generic plugin load must not load OCR');

        // ---- load for the one built-in package: constructor, no host metadata lookup ----
        const hostCallsBeforeLoad = hostState.requests.length;
        const MLKitOCR = plugins.load(MLKIT_PACKAGE);
        assertEqual(typeof MLKitOCR, 'function', 'MLKit load must return a constructor');
        assertEqual(MLKitOCR.backend, 'mlkit', 'constructor must expose backend=mlkit');
        assertEqual(MLKitOCR.packageName, MLKIT_PACKAGE, 'constructor must expose its alias');
        assertEqual(hostState.requests.length, hostCallsBeforeLoad,
            'MLKit load must not perform any host call');
        assertEqual(hostState.requests.filter(function (request) {
            return request.kind === 'plugins' && request.action === 'info'
                && request.packageName === MLKIT_PACKAGE;
        }).length, 0, 'MLKit load must not perform a metadata lookup');
        assertEqual(ocrState.createCalls.length, 0, 'MLKit load must not create OCR');
        assertEqual(ocrState.requireCount, 0, 'MLKit load must not load the OCR module');

        // ---- constructor is lazy; isInstalled does not claim the APK is installed ----
        const ocr = new MLKitOCR();
        assertEqual(ocr.backend, 'mlkit', 'instance must expose backend=mlkit');
        assertEqual(ocr.isReleased(), false, 'new instance starts unreleased');
        assertEqual(ocrState.createCalls.length, 0, 'new MLKitOCR() must not create a session');
        assertEqual(ocrState.requireCount, 0, 'unused constructor must not load OCR');
        assertEqual(plugins.isInstalled(MLKIT_PACKAGE), false,
            'isInstalled must still report host truth, not pretend the APK is installed');

        // ---- concurrent detect: one backend session, real result delegated unchanged ----
        const expectedResult = [{ text: '真实识别结果', confidence: 0.98 }];
        ocrState.nextDetectResult = expectedResult;
        const detectOne = ocr.detect({ id: 41 }, { max: 5, region: [0, 0, 10, 10] });
        const detectTwo = ocr.detect({ id: 42 });
        const results = await Promise.all([detectOne, detectTwo]);
        assert(results[0] === expectedResult,
            'detect must return the real backend result object unchanged');
        assert(results[1] === expectedResult,
            'concurrent detect must also return the real backend result unchanged');
        assertEqual(ocrState.createCalls.length, 1,
            'concurrent detect must share a single createOCR call');
        assertEqual(ocrState.createCalls[0].backend, 'mlkit',
            'createOCR must be called with backend=mlkit');
        assertDeepEqual(ocrState.createCalls[0].keys, ['backend'],
            'the adapter must forward only the real mlkit backend option');
        const sharedSession = ocrState.sessions[0];
        assertEqual(sharedSession.detectCalls.length, 2,
            'both concurrent detects must use the shared session');
        assertEqual(sharedSession.detectCalls[0].image.id, 41, 'first image handle delegated');
        assertEqual(sharedSession.detectCalls[1].image.id, 42, 'second image handle delegated');
        assertDeepEqual(sharedSession.detectCalls[0].options,
            { max: 5, region: [0, 0, 10, 10] }, 'detect options delegated unchanged');

        // ---- resolved-session lifecycle: release exactly once, detect-after-release rejects ----
        assertEqual(ocr.release(), true, 'resolved session release result must be delegated');
        assertEqual(sharedSession.releaseCalls, 1, 'the shared session must be released once');
        assertEqual(ocr.isReleased(), true, 'isReleased must flip after release');
        assertEqual(ocr.release(), false, 'second release must not reach the backend');
        assertEqual(sharedSession.releaseCalls, 1, 'second release must not increment backend calls');
        const detectsBeforeReleaseCheck = sharedSession.detectCalls.length;
        await assertRejects(ocr.detect({ id: 43 }), /release/,
            'detect after release must reject');
        assertEqual(sharedSession.detectCalls.length, detectsBeforeReleaseCheck,
            'detect after release must not reach the backend session');
        assertEqual(ocrState.createCalls.length, 1,
            'detect after release must not create another session');

        // ---- release while create is in flight: release after resolve, no leak, no detect ----
        const createGate = deferred();
        ocrState.createImpl = function () {
            return createGate.promise;
        };
        const racing = new MLKitOCR();
        const racingOne = racing.detect({ id: 51 });
        const racingTwo = racing.detect({ id: 52 });
        await flushMicrotasks();
        assertEqual(ocrState.createCalls.length, 2,
            'the racing instance must start exactly one createOCR call');
        const releaseOne = racing.release();
        const releaseTwo = racing.release();
        assertEqual(releaseOne, releaseTwo,
            'concurrent release calls must share one pending release lifecycle');
        assertEqual(racing.isReleased(), true,
            'isReleased must flip immediately even before create resolves');
        const lateSession = makeSession('late-created');
        createGate.resolve(lateSession);
        assertEqual(await releaseOne, true,
            'release must wait for the in-flight create and delegate its result');
        assertEqual(lateSession.releaseCalls, 1,
            'session created after release must be released exactly once');
        assertEqual(lateSession.detectCalls.length, 0,
            'pending detects must not run on a late-created released session');
        await assertRejects(racingOne, /release/, 'detect racing with release must reject');
        await assertRejects(racingTwo, /release/, 'every racing detect must reject');
        assertEqual(lateSession.releaseCalls, 1,
            'the racing detects must not cause a second backend release');
        ocrState.createImpl = null;

        // ---- real createOCR errors propagate unchanged ----
        const createFailure = new Error('真实 createOCR 失败');
        ocrState.createImpl = function () {
            return Promise.reject(createFailure);
        };
        const failingCreate = new MLKitOCR();
        await assertRejects(failingCreate.detect({ id: 61 }), createFailure,
            'createOCR rejection must propagate unchanged');
        assertEqual(failingCreate.isReleased(), false,
            'a failed create must leave the instance unreleased until release()');
        failingCreate.release();
        ocrState.createImpl = null;

        // ---- real detect errors propagate unchanged, and release still works once ----
        const detectFailure = new Error('真实 detect 失败');
        const detectFailureSession = makeSession('detect-failure');
        detectFailureSession.detectImpl = function () {
            return Promise.reject(detectFailure);
        };
        ocrState.createImpl = function () {
            return Promise.resolve(detectFailureSession);
        };
        const failingDetect = new MLKitOCR();
        await assertRejects(failingDetect.detect({ id: 71 }), detectFailure,
            'session.detect rejection must propagate unchanged');
        assertEqual(detectFailureSession.releaseCalls, 0,
            'a failed detect must not release the session behind the caller');
        assertEqual(failingDetect.release(), true,
            'a session with a failed detect must still release normally');
        assertEqual(detectFailureSession.releaseCalls, 1,
            'release after a failed detect must reach the backend once');
        ocrState.createImpl = null;

        // ---- release before any detect / create: no resource initialization ----
        const unused = new MLKitOCR();
        const createCallsBeforeUnused = ocrState.createCalls.length;
        assertEqual(unused.release(), false,
            'release before any detect has nothing to release');
        assertEqual(unused.isReleased(), true,
            'release before any detect must still mark the instance released');
        await assertRejects(unused.detect({ id: 81 }), /release/,
            'detect after early release must reject');
        assertEqual(ocrState.createCalls.length, createCallsBeforeUnused,
            'an early-released instance must never create an OCR session');

        // ---- the compatibility alias must never have consulted plugin metadata ----
        assertEqual(hostState.requests.filter(function (request) {
            return request.kind === 'plugins' && request.action === 'info'
                && request.packageName === MLKIT_PACKAGE;
        }).length, 0, 'MLKit compatibility must never perform plugin metadata lookup');

        console.log('OK node24 mlkit plugin contract: backend=' + ocrState.createCalls[0].backend
            + ' createCalls=' + ocrState.createCalls.length
            + ' sessions=' + ocrState.sessions.length
            + ' mlkitInfoLookups=0');
    } catch (error) {
        failed = true;
        console.error('FAIL: ' + (error && error.stack ? error.stack : error));
    } finally {
        Module._load = originalModuleLoad;
        delete globalThis.__ajsRegisterModule;
        delete globalThis.__autojsHostRaw;
        try {
            delete require.cache[require.resolve(pluginsPath)];
        } catch (ignored) {
        }
    }
    if (failed) { process.exit(1); }
    process.exit(0);
})();
