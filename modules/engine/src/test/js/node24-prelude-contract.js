'use strict';

/*
 * Production-prelude contract test for the two MI8 dev-1095 regressions:
 *   1. relative fs writes must land in the injected project directory (real process.chdir);
 *   2. sensors.getSensor(...).enableSensorEvent(...).on("change", ...) must route real
 *      host events (enqueueEvent/poll protocol) and unregister on disable.
 *
 * Usage: node node24-prelude-contract.js <prelude.js> [workDir]
 * The JavaScript host below implements the same JSON envelope as Node24HostBridge.
 */

const fs = require('fs');
const os = require('os');
const path = require('path');

const preludePath = process.argv[2] ? path.resolve(process.argv[2]) : '';
const suppliedWorkDir = process.argv[3];
if (!preludePath) {
    fail('usage: node node24-prelude-contract.js <prelude.js> [workDir]');
}

const ownsWorkDir = !suppliedWorkDir;
const workDir = suppliedWorkDir
    ? path.resolve(suppliedWorkDir)
    : fs.mkdtempSync(path.join(os.tmpdir(), 'node24-prelude-contract-'));
fs.mkdirSync(workDir, { recursive: true });
const originalCwd = process.cwd();
const markerName = 'relative-cwd-' + process.pid + '-' + Date.now() + '.txt';

const sensorDefs = [
    { key: 'ACCELEROMETER', name: 'Accelerometer', type: 1, vendor: 'contract', version: 1, power: 0.5, maxRange: 19.6, resolution: 0.001, minDelay: 0 },
    { key: 'ORIENTATION', name: 'Orientation', type: 3, vendor: 'contract', version: 1, power: 0.1, maxRange: 360, resolution: 0.1, minDelay: 0 },
    { key: 'LIGHT', name: 'Light', type: 5, vendor: 'contract', version: 1, power: 0.1, maxRange: 1000, resolution: 1, minDelay: 0 }
];

const state = {
    events: [],
    nextToken: 1,
    registrations: new Map(),
    calls: []
};

function sensorInfo(def) {
    return {
        name: def.name,
        type: def.type,
        vendor: def.vendor,
        version: def.version,
        power: def.power,
        maxRange: def.maxRange,
        resolution: def.resolution,
        minDelay: def.minDelay
    };
}

function resolveSensor(request) {
    if (typeof request.type === 'number' && isFinite(request.type)) {
        return sensorDefs.find(function (def) { return def.type === request.type; }) || null;
    }
    if (typeof request.type === 'string' && /^\d+$/.test(request.type)) {
        return resolveSensor({ type: Number(request.type) });
    }
    if (typeof request.name === 'string') {
        const key = request.name.trim().toUpperCase()
            .replace(/^TYPE_/, '')
            .replace(/[^A-Z0-9]/g, '');
        return sensorDefs.find(function (def) { return def.key === key; }) || null;
    }
    return null;
}

function envelope(value) {
    return JSON.stringify({ ok: true, value: value === undefined ? null : value });
}

function hostError(message) {
    return JSON.stringify({ ok: false, error: message });
}

globalThis.__autojsHost = function (requestJson) {
    let request;
    try {
        request = JSON.parse(requestJson);
    } catch (error) {
        return hostError('invalid request JSON: ' + error.message);
    }
    state.calls.push(request);

    if (request.kind === 'poll') {
        const batch = state.events.splice(0, 64);
        return envelope({ '$events': batch, '$more': state.events.length > 0 });
    }
    if (request.kind === 'sensors') {
        switch (request.op) {
            case 'get': {
                const def = resolveSensor(request);
                return envelope(def ? sensorInfo(def) : null);
            }
            case 'register': {
                const def = resolveSensor(request);
                if (!def) { return envelope(null); }
                const delay = request.delay === undefined ? 3 : Number(request.delay);
                if (delay !== 0 && delay !== 1 && delay !== 2 && delay !== 3) {
                    return hostError('invalid SensorDelay: ' + request.delay);
                }
                const token = state.nextToken++;
                state.registrations.set(token, { def: def, delay: delay });
                return envelope(Object.assign(sensorInfo(def), { token: token, delay: delay }));
            }
            case 'unregister': {
                state.registrations.delete(Number(request.token));
                return envelope(true);
            }
            case 'unregisterAll': {
                state.registrations.clear();
                return envelope(true);
            }
            default:
                return hostError('unsupported sensors op: ' + request.op);
        }
    }
    return envelope(null);
};

function normalizePath(value) {
    const resolved = fs.realpathSync(value);
    return process.platform === 'win32' ? resolved.toLowerCase() : resolved;
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

function fail(message) {
    console.error('FAIL: ' + message);
    process.exit(1);
}

(async function () {
try {
    globalThis.__ajsProjectDir = workDir;
    globalThis.__ajsScriptFile = path.join(workDir, 'main.js');
    require(preludePath);

    // ---- 1. real process.cwd + real fs write (the axios sample's qq.com.html case) ----
    assertEqual(normalizePath(process.cwd()), normalizePath(workDir),
        'prelude must process.chdir to __ajsProjectDir');
    try {
        fs.writeFileSync(markerName, 'relative-ok', 'utf8');
    } catch (error) {
        throw new Error('relative fs write still fails after prelude: ' + error.message);
    }
    assert(fs.existsSync(path.join(workDir, markerName)),
        'relative file must exist in the execution directory');
    const rootCopy = path.join(path.parse(workDir).root, markerName);
    assert(!fs.existsSync(rootCopy), 'relative file must not be written to filesystem root');

    // ---- 2. sensors API surface / string+numeric mapping / missing hardware ----
    assert(globalThis.__ajsBuiltinModules && globalThis.__ajsBuiltinModules.sensors,
        'prelude must expose the built-in sensors module');
    const sensors = require('sensors');
    assert(sensors === globalThis.__ajsBuiltinModules.sensors, 'require("sensors") must return the built-in module');
    assertEqual(sensors.SensorDelay.Fastest, 0, 'SensorDelay.Fastest');
    assertEqual(sensors.SensorDelay.Game, 1, 'SensorDelay.Game');
    assertEqual(sensors.SensorDelay.Ui, 2, 'SensorDelay.Ui');
    assertEqual(sensors.SensorDelay.Normal, 3, 'SensorDelay.Normal');

    const accelerometer = sensors.getSensor('accelerometer');
    assert(accelerometer, 'getSensor("accelerometer") must resolve on the fake device');
    assertEqual(accelerometer.type, 1, 'accelerometer type');
    assertEqual(accelerometer.name, 'Accelerometer', 'accelerometer name');
    assertEqual(typeof accelerometer.enableSensorEvent, 'function', 'enableSensorEvent exists');
    assertEqual(typeof accelerometer.disableSensorEvent, 'function', 'disableSensorEvent exists');
    assertEqual(typeof accelerometer.on, 'function', 'sensor.on exists');
    assertEqual(sensors.getSensor('ACCELEROMETER').type, 1, 'case-insensitive name mapping');
    assertEqual(sensors.getSensor('TYPE_ACCELEROMETER').type, 1, 'TYPE_ prefix mapping');
    assertEqual(sensors.getSensor(1).type, 1, 'numeric sensor type mapping');
    assertEqual(sensors.getSensor('1').type, 1, 'numeric string sensor type mapping');
    assertEqual(sensors.getSensor(1.5), null, 'fractional sensor type must be null');
    assertEqual(sensors.getSensor(-1), null, 'negative sensor type must be null');
    assertEqual(sensors.getSensor('ambient_temperature'), null, 'missing ambient_temperature must be null');
    assertEqual(sensors.getSensor('relative_humidity'), null, 'missing relative_humidity must be null');
    assertEqual(sensors.getSensor('not_a_sensor'), null, 'unknown sensor must be null');

    assertThrows(function () { sensors.register('accelerometer'); }, /getSensor/,
        'module-level register must not fake success');
    assertThrows(function () { sensors.unregister({}); }, /disableSensorEvent/,
        'module-level unregister must not fake success');

    // ---- 3. real host registration + change/accuracy routing + disable ----
    const changes = [];
    const accuracy = [];
    accelerometer.on('change', function (event, ax, ay, az) {
        changes.push({ event: event, ax: ax, ay: ay, az: az });
    });
    accelerometer.on('accuracy_change', function (value) {
        accuracy.push(value);
    });

    assertEqual(accelerometer.enableSensorEvent(sensors.SensorDelay.Fastest), accelerometer,
        'enableSensorEvent must return the sensor object');
    assertEqual(state.registrations.size, 1, 'host must have exactly one registration');
    const registered = Array.from(state.registrations.entries())[0];
    const token = registered[0];
    assertEqual(registered[1].delay, 0, 'host registration delay');
    assertEqual(registered[1].def.type, 1, 'host registration sensor type');

    state.events.push({
        type: 'sensor_change',
        payload: {
            token: token,
            sensorType: 1,
            timestamp: 123456789,
            accuracy: 3,
            values: [0.1, 0.2, 0.3]
        }
    });
    assertEqual(globalThis.__autojsPoll(), 1, 'one host event must be polled');
    assertEqual(changes.length, 1, 'one change callback');
    assertEqual(changes[0].ax, 0.1, 'change value 0');
    assertEqual(changes[0].ay, 0.2, 'change value 1');
    assertEqual(changes[0].az, 0.3, 'change value 2');
    assertEqual(changes[0].event.values.length, 3, 'event.values length');
    assertEqual(changes[0].event.timestamp, 123456789, 'event.timestamp');
    assertEqual(changes[0].event.accuracy, 3, 'event.accuracy');
    assertEqual(changes[0].event.sensor, accelerometer, 'event.sensor points at the facade');

    state.events.push({
        type: 'sensor_accuracy_change',
        payload: { token: token, sensorType: 1, accuracy: 2 }
    });
    assertEqual(globalThis.__autojsPoll(), 1, 'one accuracy event must be polled');
    assertEqual(accuracy.length, 1, 'one accuracy callback');
    assertEqual(accuracy[0], 2, 'accuracy value');

    accelerometer.disableSensorEvent();
    assertEqual(state.registrations.size, 0, 'disableSensorEvent must unregister on the host');
    state.events.push({
        type: 'sensor_change',
        payload: { token: token, sensorType: 1, timestamp: 1, accuracy: 3, values: [9, 9, 9] }
    });
    assertEqual(globalThis.__autojsPoll(), 1, 'stale event is still polled');
    assertEqual(changes.length, 1, 'stale event must not reach the disabled sensor');

    // Default SensorDelay.Normal and invalid-delay rejection.
    const light = sensors.getSensor('light');
    light.on('change', function () {});
    light.enableSensorEvent();
    assertEqual(Array.from(state.registrations.values())[0].delay, 3, 'default delay is Normal(3)');
    light.disableSensorEvent();
    assertThrows(function () { light.enableSensorEvent(9); }, /delay 非法/,
        'invalid SensorDelay must throw, not silently succeed');

    // Re-enable and leave one active registration on purpose: the prelude's process exit
    // handler must unregister it through the host before Node exits.
    const orientationChanges = [];
    const orientation = sensors.getSensor('orientation');
    orientation.on('change', function (event, dx, dy, dz) {
        orientationChanges.push([dx, dy, dz]);
    });
    orientation.enableSensorEvent(sensors.SensorDelay.Game);
    assertEqual(state.registrations.size, 1, 'orientation registration before exit');
    const orientationToken = Array.from(state.registrations.keys())[0];
    state.events.push({
        type: 'sensor_change',
        payload: {
            token: orientationToken,
            sensorType: 3,
            timestamp: 42,
            accuracy: 3,
            values: [10, 20, 30]
        }
    });
    // Do not call __autojsPoll manually here: verify the production 25ms polling loop
    // installed by sensor.on()/ensurePolling actually delivers the queued event.
    await new Promise(function (resolve) { setTimeout(resolve, 150); });
    assertEqual(orientationChanges.length, 1, 'automatic polling must deliver one change event');
    assertEqual(orientationChanges[0][0], 10, 'automatic poll value 0');
    assertEqual(orientationChanges[0][1], 20, 'automatic poll value 1');
    assertEqual(orientationChanges[0][2], 30, 'automatic poll value 2');

    process.on('exit', function () {
        // prelude registered its cleanup before this handler, so it must have run first.
        if (state.registrations.size !== 0) {
            console.error('FAIL: process exit cleanup left sensor registrations: '
                + Array.from(state.registrations.keys()).join(','));
            process.exitCode = 1;
        }
    });

    console.log('OK node24 prelude contract cwd=' + process.cwd()
        + ' changeEvents=' + changes.length + ' accuracyEvents=' + accuracy.length
        + ' autoPollEvents=' + orientationChanges.length);
} catch (error) {
    console.error('FAIL: ' + (error && error.stack ? error.stack : error));
    process.exitCode = 1;
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
})();
