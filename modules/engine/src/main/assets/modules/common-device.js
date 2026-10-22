// Pro API v2 device module. The API v1 global device remains separate because
// v1 product is a string, whereas v2 product is a structured Product value.
(function (g) {
    'use strict';
    var node = typeof process === 'object' && process.versions && process.versions.node;
    function type(name) {
        if (node) return g.$autojs.java.findClass(name);
        if (g.Java && typeof g.Java.type === 'function') return g.Java.type(name);
        var value = g.Packages;
        name.split('.').forEach(function (part) { value = value[part]; });
        return value;
    }
    function context() { return node ? g.$autojs.androidContext : g.context; }
    function service(name) {
        var value = context().getSystemService(name);
        if (value == null) throw new Error('Android service unavailable: ' + name);
        return value;
    }
    function copyArray(value) { var out = []; for (var i = 0; value != null && i < Number(value.length); i++) out.push(String(value[i])); return out; }
    function Emitter() { this._listeners = Object.create(null); }
    Emitter.prototype.on = function (name, listener) {
        if (typeof listener !== 'function') throw new TypeError('listener must be a function');
        (this._listeners[name] || (this._listeners[name] = [])).push(listener); return this;
    };
    Emitter.prototype.addListener = Emitter.prototype.on;
    Emitter.prototype.once = function (name, listener) {
        var self = this;
        function once() { self.removeListener(name, once); return listener.apply(self, arguments); }
        once.listener = listener; return this.on(name, once);
    };
    Emitter.prototype.emit = function (name) {
        var list = this._listeners[name];
        var args = Array.prototype.slice.call(arguments, 1);
        if (!list || !list.length) { if (name === 'error') throw args[0] instanceof Error ? args[0] : new Error(String(args[0])); return false; }
        var self = this; list.slice().forEach(function (fn) { fn.apply(self, args); }); return true;
    };
    Emitter.prototype.removeListener = function (name, listener) {
        var list = this._listeners[name] || [];
        for (var i = list.length - 1; i >= 0; i--) if (list[i] === listener || list[i].listener === listener) { list.splice(i, 1); break; }
        return this;
    };
    Emitter.prototype.off = Emitter.prototype.removeListener;
    Emitter.prototype.removeAllListeners = function (name) {
        if (arguments.length) delete this._listeners[name]; else this._listeners = Object.create(null); return this;
    };
    Emitter.prototype.listenerCount = function (name) { return (this._listeners[name] || []).length; };
    Emitter.prototype.eventNames = function () { var self = this; return Object.keys(this._listeners).filter(function (name) { return self.listenerCount(name) > 0; }); };
    Emitter.prototype.listeners = function (name) { return (this._listeners[name] || []).map(function (fn) { return fn.listener || fn; }); };
    var EventEmitter = node ? require('node:events').EventEmitter : Emitter;
    var legacyClass;
    function legacy() {
        if (!legacyClass) legacyClass = type('com.stardust.autojs.runtime.api.Device');
        return legacyClass;
    }
    var streams = {music: 3, alarm: 4, notification: 5, voice_call: 0, system: 1, ring: 2, dtmf: 8, accessibility: 10};
    function stream(type) { if (!Object.prototype.hasOwnProperty.call(streams, type)) throw new TypeError('Unknown audio stream: ' + type); return streams[type]; }
    function getter(target, name, read, write) {
        Object.defineProperty(target, name, {get: read, set: write, enumerable: true, configurable: true});
    }
    function Device() {
        EventEmitter.call(this);
    }
    Device.prototype = Object.create(EventEmitter.prototype);
    Device.prototype.constructor = Device;
    getter(Device.prototype, '_native', function () {
        if (!this.__native) this.__native = new (legacy())(context());
        return this.__native;
    });
    var methods = {cancelVibration: 'cancelVibration', vibrate: 'vibrate'};
    Object.keys(methods).forEach(function (name) {
        Device.prototype[name] = function () { return this._native[methods[name]].apply(this._native, arguments); };
    });
    var values = {androidId: 'getAndroidId', battery: 'getBattery', brightness: 'getBrightness', brightnessMode: 'getBrightnessMode',
        imei: 'getIMEI', macAddress: 'getMacAddress'};
    Object.keys(values).forEach(function (name) {
        getter(Device.prototype, name, function () { return this._native[values[name]](); },
            name === 'brightness' ? function (n) { this._native.setBrightness(n); }
                : name === 'brightnessMode' ? function (n) { this._native.setBrightnessMode(n); } : undefined);
    });
    ['bootloader', 'buildId', 'fingerprint', 'hardware', 'serial'].forEach(function (name) {
        getter(Device.prototype, name, function () { return String(legacy()[name]); });
    });
    getter(Device.prototype, 'display', function () { return String(legacy().buildDisplay); });
    getter(Device.prototype, 'screenWidth', function () { return Number(legacy().width); });
    getter(Device.prototype, 'screenHeight', function () { return Number(legacy().height); });
    getter(Device.prototype, 'externalStorageDirectory', function () { return String(type('android.os.Environment').getExternalStorageDirectory().getAbsolutePath()); });
    getter(Device.prototype, 'cpuApis', function () { return copyArray(type('android.os.Build').SUPPORTED_ABIS); });
    getter(Device.prototype, 'product', function () {
        var result = {};
        var names = {name: 'product', device: 'device', board: 'board', brand: 'brand', model: 'model'};
        Object.keys(names).forEach(function (key) { result[key] = String(legacy()[names[key]]); });
        return result;
    });
    getter(Device.prototype, 'batteryPluggedTypes', function () {
        var Filter = type('android.content.IntentFilter');
        var intent = context().registerReceiver(null, new Filter('android.intent.action.BATTERY_CHANGED'));
        var bits = intent == null ? 0 : Number(intent.getIntExtra('plugged', 0));
        return [[1, 'ac'], [2, 'usb'], [4, 'wireless']].filter(function (row) { return bits & row[0]; }).map(function (row) { return row[1]; });
    });
    getter(Device.prototype, 'displayMetrics', function () {
        var Metrics = type('android.util.DisplayMetrics');
        var metrics = new Metrics();
        service('window').getDefaultDisplay().getRealMetrics(metrics);
        var result = {};
        ['widthPixels', 'heightPixels', 'density', 'densityDpi', 'scaledDensity', 'xdpi', 'ydpi'].forEach(function (key) { result[key] = Number(metrics[key]); });
        return result;
    });
    getter(Device.prototype, 'memoryInfo', function () {
        var Info = type('android.app.ActivityManager$MemoryInfo');
        var info = new Info();
        service('activity').getMemoryInfo(info);
        return {totalMem: Number(info.totalMem), availMem: Number(info.availMem), threshold: Number(info.threshold), lowMemory: Boolean(info.lowMemory)};
    });
    Device.prototype.getVolume = function (name) { return Number(service('audio').getStreamVolume(stream(name))); };
    Device.prototype.getVolumeRange = function (name) {
        var id = stream(name), audio = service('audio');
        return {min: OS.sdkVersionCode >= 28 ? Number(audio.getStreamMinVolume(id)) : 0, max: Number(audio.getStreamMaxVolume(id))};
    };
    Device.prototype.setVolume = function (name, volume, flags) {
        if (typeof volume !== 'number' || !isFinite(volume)) throw new TypeError('volume must be a finite number');
        var audio = type('android.media.AudioManager');
        var bits = 0;
        if (flags !== undefined && !Array.isArray(flags)) throw new TypeError('flags must be an array');
        (flags || []).forEach(function (flag) {
            var key = 'FLAG_' + String(flag).toUpperCase();
            var value = audio[key];
            if (typeof value !== 'number') throw new TypeError('Unknown audio flag: ' + flag);
            bits |= value;
        });
        service('audio').setStreamVolume(stream(name), Math.floor(volume), bits);
    };
    function OS() {}
    var versions = [['BASE', '1.0'], ['BASE_1_1', '1.1'], ['CUPCAKE', '1.5'], ['DONUT', '1.6'], ['ECLAIR', '2.0'], ['ECLAIR_0_1', '2.0.1'],
        ['ECLAIR_MR1', '2.1'], ['FROYO', '2.2'], ['GINGERBREAD', '2.3'], ['GINGERBREAD_MR1', '2.3.3'], ['HONEYCOMB', '3.0'],
        ['HONEYCOMB_MR1', '3.1'], ['HONEYCOMB_MR2', '3.2'], ['ICE_CREAM_SANDWICH', '4.0'], ['ICE_CREAM_SANDWICH_MR1', '4.0.3'],
        ['JELLY_BEAN', '4.1'], ['JELLY_BEAN_MR1', '4.2'], ['JELLY_BEAN_MR2', '4.3'], ['KITKAT', '4.4'], ['KITKAT_WATCH', '4.4W'],
        ['L', '5.0'], ['LOLLIPOP_MR1', '5.1'], ['M', '6.0'], ['N', '7.0'], ['N_MR1', '7.1'], ['O', '8.0'], ['O_MR1', '8.1'],
        ['P', '9'], ['Q', '10'], ['R', '11'], ['S', '12'], ['S_V2', '12L'], ['T', '13'], ['U', '14'], ['V', '15'], ['BAKLAVA', '16']];
    versions.forEach(function (row, index) { OS['ANDROID_' + row[0]] = Object.freeze({code: index + 1, name: row[1]}); });
    getter(OS, 'sdkVersionCode', function () { return Number(legacy().sdkInt); });
    getter(OS, 'sdkName', function () { var row = versions[OS.sdkVersionCode - 1]; return row ? row[1] : String(OS.release); });
    ['incremental', 'release', 'securityPatch'].forEach(function (name) { getter(OS, name, function () { return String(legacy()[name]); }); });
    OS.requiresAndroidVersion = function (version, message) {
        if (!version || typeof version.code !== 'number') throw new TypeError('version must be an AndroidVersion');
        if (OS.sdkVersionCode < version.code) throw new Error(message || ('Requires Android ' + version.name));
    };
    var api = {Device: Device, device: new Device(), OS: OS, BrightnessMode: {Automatic: 1, Manual: 0}, EventEmitter: EventEmitter};
    if (node) {
        g.__ajsRegisterModule('device', function () { return api; });
        g.__ajsBuiltinModules.device = api;
        g.$autojs.device = api;
    } else {
        var modules = g.__aiModernModules || (g.__aiModernModules = Object.create(null));
        modules.device = api;
        modules.files = g.files;
        modules.storages = g.storages;
    }
})(typeof globalThis === 'object' ? globalThis : global);
