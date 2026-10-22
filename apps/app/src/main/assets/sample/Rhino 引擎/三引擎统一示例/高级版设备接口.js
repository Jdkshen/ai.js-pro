"rhino";
(function () {
    var api = require('device');
    var modern = api.device;
    var checks = [];
    function check(name, fn) {
        try { if (!fn()) throw new Error('assertion returned false'); checks.push({name: name, ok: true}); }
        catch (error) { checks.push({name: name, ok: false, error: String(error)}); }
    }
    check('module.exports', function () { return typeof api.Device === 'function' && typeof api.OS === 'function' && api.BrightnessMode.Automatic === 1 && api.BrightnessMode.Manual === 0; });
    check('Device.instance', function () { return modern instanceof api.Device && modern instanceof api.EventEmitter; });
    check('Device.constructor', function () { return new api.Device().getVolume('music') >= 0; });
    check('device.product', function () { var value = modern.product; return ['name', 'device', 'board', 'brand', 'model'].every(function (key) { return typeof value[key] === 'string' && value[key].length > 0; }); });
    check('device.v1_product', function () { return String(device.product) === modern.product.name; });
    check('device.brightness_real', function () { return Number(modern.brightness) === Number(device.getBrightness()) && Number(modern.brightnessMode) === Number(device.getBrightnessMode()); });
    check('device.brightness_setters', function () { return typeof Object.getOwnPropertyDescriptor(api.Device.prototype, 'brightness').set === 'function' && typeof Object.getOwnPropertyDescriptor(api.Device.prototype, 'brightnessMode').set === 'function'; });
    check('device.battery', function () { return modern.battery >= 0 && modern.battery <= 100 && Array.isArray(modern.batteryPluggedTypes); });
    check('device.androidId', function () { return String(modern.androidId) === String(device.getAndroidId()); });
    check('device.displayMetrics', function () { var value = modern.displayMetrics; return ['widthPixels', 'heightPixels', 'density', 'densityDpi', 'scaledDensity', 'xdpi', 'ydpi'].every(function (key) { return typeof value[key] === 'number' && value[key] > 0; }); });
    check('device.memoryInfo', function () { var info = modern.memoryInfo; return info.totalMem > 0 && info.availMem > 0 && info.threshold > 0 && typeof info.lowMemory === 'boolean'; });
    check('device.cpuApis', function () { return Array.isArray(modern.cpuApis) && modern.cpuApis.length > 0 && typeof modern.cpuApis[0] === 'string'; });
    check('device.dimensions', function () { return modern.screenWidth > 0 && modern.screenHeight > 0; });
    check('device.externalStorageDirectory', function () { return files.isDir(modern.externalStorageDirectory); });
    check('device.audio', function () { var range = modern.getVolumeRange('music'); return range.min >= 0 && range.max >= range.min && modern.getVolume('music') >= range.min; });
    check('device.bad_stream', function () { try { modern.getVolume('invalid'); return false; } catch (error) { return error instanceof TypeError; } });
    check('device.bad_volume', function () { try { modern.setVolume('music', NaN); return false; } catch (error) { return error instanceof TypeError; } });
    check('device.events', function () { var calls = []; modern.once('test', function (a, b) { calls.push(a + b); }); modern.emit('test', 1, 2); modern.emit('test', 3, 4); return JSON.stringify(calls) === '[3]'; });
    check('OS.version', function () { return api.OS.sdkVersionCode === Number(device.sdkInt) && typeof api.OS.release === 'string' && api.OS.sdkName.length > 0; });
    check('OS.constants', function () { return api.OS.ANDROID_BASE.code === 1 && api.OS.ANDROID_L.code === 21 && api.OS.ANDROID_S.code === 31; });
    check('OS.requiresAndroidVersion', function () { api.OS.requiresAndroidVersion(api.OS.ANDROID_BASE); try { api.OS.requiresAndroidVersion({code: 999, name: 'future'}, 'expected'); return false; } catch (error) { return String(error).indexOf('expected') >= 0; } });
    var failed = checks.filter(function (row) { return !row.ok; });
    console.log('COMMON_DEVICE_V2_RESULT=' + JSON.stringify({checks: checks, passed: checks.length - failed.length, failed: failed}));
})();
