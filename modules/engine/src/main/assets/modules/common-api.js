// Shared API v1 adapters. ES5 syntax is intentional: this asset also runs in Rhino.
(function (g) {
    'use strict';
    var node = typeof process === 'object' && process.versions && process.versions.node;
    var quick = !node && g.Java && typeof g.Java.type === 'function';
    function type(name) {
        if (node) return g.$autojs.java.findClass(name);
        if (quick) return g.Java.type(name);
        var value = g.Packages;
        name.split('.').forEach(function (part) { value = value[part]; });
        return value;
    }
    function context() { return node ? g.$autojs.androidContext : g.context; }
    function array(value) {
        var result = [];
        if (value == null) return result;
        if (typeof value.length === 'undefined') {
            var reflection = type('java.lang.reflect.Array');
            var length = Number(reflection.getLength(value));
            for (var n = 0; n < length; n++) result.push(reflection.get(value, n));
            return result;
        }
        for (var i = 0; i < Number(value.length); i++) result.push(value[i]);
        return result;
    }
    function missing(target, name, fn) {
        if (typeof target[name] === 'undefined') target[name] = fn;
    }
    function forward(target, name, provider) {
        target[name] = function () {
            var object = provider();
            return object[name].apply(object, arguments);
        };
    }
    var backingFiles;
    function nativeFiles() {
        if (!backingFiles) {
            if (!node && !quick) backingFiles = g.runtime.files;
            else {
                var Files = type('com.stardust.autojs.runtime.api.Files');
                backingFiles = new Files(context(), String(node ? (g.__ajsProjectDir || process.cwd()) : g.files.cwd()));
            }
        }
        return backingFiles;
    }
    var files = node ? require('files') : Object.create(g.files);
    var fileMethods = ['append', 'appendBytes', 'copy', 'create', 'createIfNotExists',
        'createWithDirs', 'cwd', 'dirname', 'ensureDir', 'exists', 'getExtension',
        'getHumanReadableSize', 'getName', 'getNameWithoutExtension', 'getSdcardPath',
        'getSimplifiedPath', 'isDir', 'isEmptyDir', 'isFile', 'move', 'open', 'path',
        'read', 'readAssets', 'readBytes', 'remove', 'removeDir', 'rename',
        'renameWithoutExtension', 'write', 'writeBytes'];
    fileMethods.forEach(function (name) {
        if (node || typeof files[name] === 'undefined') forward(files, name, nativeFiles);
    });
    if (quick) {
        // Native QuickJS text/open helpers historically ignored the encoding argument.
        // Copy frozen properties as configurable own properties before overriding them.
        var quickFiles = {};
        Object.keys(g.files).forEach(function (name) { quickFiles[name] = g.files[name]; });
        Object.keys(files).forEach(function (name) { quickFiles[name] = files[name]; });
        files = quickFiles;
        ['read', 'write', 'append', 'open'].forEach(function (name) { forward(files, name, nativeFiles); });
        ['writeBytes', 'appendBytes'].forEach(function (name) {
            files[name] = function (path, bytes) {
                var values = array(bytes).map(function (value) { value = Number(value) & 255; return value > 127 ? value - 256 : value; });
                nativeFiles()[name](String(path), g.Java.array('byte', values));
            };
        });
    }
    if (node) {
        // Java varargs/filter callbacks need a JS adapter, rather than a fake Func1 proxy.
        files.join = function (base) {
            var path = require('node:path');
            return path.join.apply(path, array(arguments));
        };
        files.listDir = function (path, filter) {
            var result = array(nativeFiles().listDir(String(path)));
            if (filter !== undefined && typeof filter !== 'function') throw new TypeError('filter must be a function');
            return filter ? result.filter(filter) : result;
        };
        files.stat = function (path) {
            var value = nativeFiles().stat(String(path));
            return {path: value.getPath(), size: Number(value.getSize()), lastModified: Number(value.getLastModified()),
                readable: value.isReadable(), writable: value.isWritable(), executable: value.isExecutable(), hidden: value.isHidden(),
                exists: files.exists(path), isFile: files.isFile(path), isDirectory: files.isDir(path)};
        };
        files.observe = function (path) {
            var absolute = files.path(String(path));
            function host(action, token) {
                return g.__ajsHostValue({kind: 'files', action: action, args: {path: absolute, token: token}});
            }
            var token = host('observe');
            var emitter = new (require('node:events').EventEmitter)();
            var watching = true;
            var hold = setInterval(function () {}, 1000);
            var eventName = 'file_observer:' + token;
            function receive(event) {
                if (!watching) return;
                emitter.emit(event.event, event.path);
                emitter.emit('any', event.event, event.path);
            }
            g.__autojsOn(eventName, receive);
            emitter.startWatching = function () {
                if (watching) return;
                host('startWatching', token);
                watching = true;
                g.__autojsOn(eventName, receive);
                hold = setInterval(function () {}, 1000);
            };
            emitter.stopWatching = function () {
                if (!watching) return;
                host('stopWatching', token);
                watching = false;
                clearInterval(hold);
                g.__autojsOff(eventName, receive);
            };
            emitter.isWatching = function () { return watching; };
            emitter.getPath = function () { return absolute; };
            return emitter;
        };
    }
    if (node || quick) {
        files.open = function () {
            var stream = nativeFiles().open.apply(nativeFiles(), arguments);
            if (stream == null) return null;
            var file = {}, closed = false;
            ['read', 'readline', 'write', 'writeline', 'writelines', 'flush', 'getPath'].forEach(function (name) {
                if (typeof stream[name] === 'function') file[name] = function () { return stream[name].apply(stream, arguments); };
            });
            if (typeof stream.readlines === 'function') file.readlines = function () { return array(stream.readlines()).map(String); };
            file.close = function () { stream.close(); closed = true; };
            file.isClosed = function () { return closed; };
            file.path = String(stream.getPath());
            return file;
        };
    }
    missing(files, 'list', function (path, filter) { return files.listDir(path, filter); });
    if (typeof files.sdcardPath === 'undefined') Object.defineProperty(files, 'sdcardPath', {
        enumerable: true, configurable: true, get: function () { return files.getSdcardPath(); }
    });
    missing(files, 'mkdir', function (path) { return Boolean(new (type('java.io.File'))(files.path(path)).mkdirs()) || files.isDir(path); });
    missing(files, 'readText', function (path, encoding) { return encoding === undefined ? files.read(path) : files.read(path, encoding); });
    missing(files, 'writeText', function (path, text, encoding) {
        files.ensureDir(path);
        if (encoding === undefined) files.write(path, String(text)); else files.write(path, String(text), encoding);
        return true;
    });
    // Named storage has the same JSON contract and persistent namespace in all engines.
    // Node's older flat string API is kept on require('storages')/$autojs.storages.
    function createStorage(name) {
        if (typeof name !== 'string' || !name) throw new TypeError('storage name must be a non-empty string');
        var LocalStorage = type('com.stardust.autojs.core.storage.LocalStorage');
        var storage = new LocalStorage(context(), name);
        return {
            put: function (key, value) {
                if (value === undefined) throw new TypeError('value cannot be undefined');
                var parents = [];
                var json = JSON.stringify(value, function (key, item) {
                    if (item !== null && typeof item === 'object') {
                        while (parents.length && parents[parents.length - 1] !== this) parents.pop();
                        if (parents.indexOf(item) >= 0) throw new TypeError('cyclic storage value');
                        if (parents.length >= 256) throw new RangeError('storage value is too deeply nested');
                        parents.push(item);
                    }
                    return item;
                });
                if (json === undefined) throw new TypeError('value must be JSON serializable');
                storage.put(String(key), json);
            },
            get: function (key, fallback) {
                var value = storage.getString(String(key), null);
                return value === null ? fallback : JSON.parse(String(value));
            },
            contains: function (key) { return Boolean(storage.contains(String(key))); },
            remove: function (key) { storage.remove(String(key)); },
            clear: function () { storage.clear(); }
        };
    }
    var storages = {create: createStorage, remove: function (name) { createStorage(name).clear(); }};
    if (node) {
        require('storages').create = createStorage;
        require('storages').removeStorage = storages.remove;
    }
    var device;
    var nativeDeviceValue;
    function nativeDevice() {
        if (!nativeDeviceValue) nativeDeviceValue = new (type('com.stardust.autojs.runtime.api.Device'))(context());
        return nativeDeviceValue;
    }
    var deviceMethods = ['getIMEI', 'getAndroidId', 'getBrightness', 'getBrightnessMode', 'setBrightness',
        'setBrightnessMode', 'getMusicVolume', 'getNotificationVolume', 'getAlarmVolume',
        'getMusicMaxVolume', 'getNotificationMaxVolume', 'getAlarmMaxVolume', 'setMusicVolume',
        'setAlarmVolume', 'setNotificationVolume', 'getScreenOrientation', 'getScreenRotation',
        'getBattery', 'getTotalMem', 'getAvailMem', 'isCharging', 'keepAwake', 'isScreenOn',
        'wakeUpIfNeeded', 'wakeUp', 'keepScreenOn', 'keepScreenDim', 'cancelKeepingAwake',
        'vibrate', 'cancelVibration', 'getMacAddress'];
    if (node || quick) {
        device = node ? require('device').device : Object.create(g.device);
        deviceMethods.forEach(function (name) {
            if (node || typeof device[name] === 'undefined') forward(device, name, nativeDevice);
        });
        ['width', 'height', 'buildId', 'buildDisplay', 'product', 'board', 'brand', 'device', 'model',
            'bootloader', 'hardware', 'fingerprint', 'sdkInt', 'incremental', 'release', 'baseOS',
            'securityPatch', 'codename', 'serial'].forEach(function (name) {
            if (node || typeof device[name] === 'undefined') Object.defineProperty(device, name, {
                enumerable: true, configurable: true, get: function () {
                    nativeDevice(); // Refresh dynamic screen dimensions before reading static fields.
                    return type('com.stardust.autojs.runtime.api.Device')[name];
                }
            });
        });
        if (node) {
            var properties = {brightness: 'getBrightness', brightnessMode: 'getBrightnessMode',
                androidId: 'getAndroidId', battery: 'getBattery', imei: 'getIMEI', macAddress: 'getMacAddress',
                screenWidth: 'width', screenHeight: 'height'};
            Object.keys(properties).forEach(function (key) {
                var member = properties[key];
                Object.defineProperty(device, key, {enumerable: true, configurable: true, get: function () {
                    return member === 'width' || member === 'height' ? device[member] : device[member]();
                }});
            });
            Object.defineProperty(device, 'batteryPluggedTypes', {enumerable: true, configurable: true, get: function () {
                var IntentFilter = type('android.content.IntentFilter');
                var intent = context().registerReceiver(null, new IntentFilter('android.intent.action.BATTERY_CHANGED'));
                return intent == null ? 0 : intent.getIntExtra('plugged', 0);
            }});
            process.on('exit', function () { if (nativeDeviceValue) nativeDeviceValue.cancelKeepingAwake(); });
        }
    } else device = g.device;
    g.files = files;
    g.$files = files;
    g.storages = storages;
    g.$storages = storages;
    g.device = device;
    g.$device = device;
    if (quick) {
        g.__aiModernModules.files = files;
        g.__aiModernModules.storages = storages;
    }
    if (node) g.open = function () { return files.open.apply(files, arguments); };
    if (g.io) {
        // QuickJS freezes io around its original files object; keep the alias current.
        var io = {};
        Object.keys(g.io).forEach(function (key) { io[key] = g.io[key]; });
        io.files = files;
        io.open = function () { return files.open.apply(files, arguments); };
        g.io = io;
        if (quick) g.__aiModernModules.io = io;
    }
    // This lists adapters only, never claims full Pro conformance.
    g.__aijsCommonApi = {revision: 1, files: fileMethods.concat(['join', 'listDir', 'stat', 'observe', 'list', 'mkdir', 'readText', 'writeText']),
        device: deviceMethods, storages: ['create', 'remove']};
})(typeof globalThis === 'object' ? globalThis : global);
