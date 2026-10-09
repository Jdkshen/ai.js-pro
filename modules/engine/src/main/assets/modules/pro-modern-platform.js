// Modern platform modules backed by Android Context and the real engine service.
(function (g) {
    'use strict';
    const modules = g.__aiModernModules || (g.__aiModernModules = Object.create(null));
    const coverage = g.__aiModernModuleCoverage || (g.__aiModernModuleCoverage = Object.create(null));
    const classes = Object.create(null);
    function unsupported(feature) { throw new Error('Modern API is not available: ' + feature); }
    function java(name) {
        if (!g.Java || typeof g.Java.type !== 'function') return unsupported('Java.type');
        return classes[name] || (classes[name] = g.Java.type(name));
    }
    function context() {
        if (!g.context || typeof g.context.getSystemService !== 'function') return unsupported('Android Context');
        return g.context;
    }
    function service(name) {
        const value = context().getSystemService(name);
        if (value == null) return unsupported('Android service ' + name);
        return value;
    }
    function toArray(value) {
        if (value == null) return [];
        if (Array.isArray(value)) return value.slice();
        if (typeof value.toArray === 'function') {
            // Copy through public ArrayList: Android collection implementations may be private.
            const list = value.__javaHandle !== undefined ? new (java('java.util.ArrayList'))(value) : value;
            return toArray(list.toArray());
        }
        if (typeof value.iterator === 'function') {
            const result = [], iterator = value.iterator();
            while (iterator.hasNext()) result.push(iterator.next());
            return result;
        }
        if (typeof value.hasMoreElements === 'function') {
            if (value.__javaHandle !== undefined) return toArray(java('java.util.Collections').list(value));
            const result = [];
            while (value.hasMoreElements()) result.push(value.nextElement());
            return result;
        }
        return Array.from(value);
    }
    function mapToObject(value) {
        if (value == null) return {};
        if (typeof value.keySet !== 'function') return Object.assign({}, value);
        const result = {};
        toArray(value.keySet()).forEach(key => { result[String(key)] = javaValue(value.get(key)); });
        return result;
    }
    function javaValue(value) {
        if (value == null || typeof value !== 'object') return value;
        if (typeof value.keySet === 'function') return mapToObject(value);
        if (Array.isArray(value) || typeof value.toArray === 'function' || typeof value.iterator === 'function') return toArray(value).map(javaValue);
        return value;
    }
    function filePath(path) {
        if (g.files && typeof g.files.path === 'function') return g.files.path(String(path));
        const File = java('java.io.File'), file = new File(String(path));
        if (file.isAbsolute()) return file.getCanonicalPath();
        const cwd = g.process && typeof g.process.cwd === 'function' ? g.process.cwd() : '.';
        return new File(String(cwd), String(path)).getCanonicalPath();
    }
    function register(name, api, limitations) {
        modules[name] = api;
        coverage[name] = { exports: Object.keys(api), limitations: limitations || [] };
    }
    const EventEmitter = modules.events && (modules.events.EventEmitter || modules.events);
    if (typeof EventEmitter !== 'function') return unsupported('modern events module must load first');

    class OS {
        static get sdkVersionCode() { return Number(java('android.os.Build$VERSION').SDK_INT); }
        static get sdkName() { const version = OS.sdkVersions.find(v => v.code === OS.sdkVersionCode); return version ? version.name : 'unknown'; }
        static get incremental() { return java('android.os.Build$VERSION').INCREMENTAL; }
        static get release() { return java('android.os.Build$VERSION').RELEASE; }
        static get securityPatch() { return OS.sdkVersionCode >= 23 ? java('android.os.Build$VERSION').SECURITY_PATCH : ''; }
        static requiresAndroidVersion(version, message) {
            if (OS.sdkVersionCode < version.code) throw new Error(message || ('Requires Android ' + version.name + '(API ' + version.code + ') to run this code'));
        }
    }
    const versions = [
        ['BASE','1.0'],['BASE_1_1','1.1'],['CUPCAKE','1.5'],['DONUT','1.6'],['ECLAIR','2.0'],['ECLAIR_0_1','2.0.1'],['ECLAIR_MR1','2.1.x'],
        ['FROYO','2.2.x'],['GINGERBREAD','2.3'],['GINGERBREAD_MR1','2.3.3'],['HONEYCOMB','3.0.x'],['HONEYCOMB_MR1','3.1.x'],['HONEYCOMB_MR2','3.2'],
        ['ICE_CREAM_SANDWICH','4.0'],['ICE_CREAM_SANDWICH_MR1','4.0.3'],['JELLY_BEAN','4.1'],['JELLY_BEAN_MR1','4.2'],['JELLY_BEAN_MR2','4.3'],
        ['KITKAT','4.4.2'],['KITKAT_WATCH','4.4W'],['L','5.0'],['LOLLIPOP_MR1','5.1'],['M','6.0'],['N','7.0'],['N_MR1','7.1'],['O','8.0'],['O_MR1','8.1'],['P','9'],['Q','10'],['R','11'],['S','12']
    ];
    OS.sdkVersions = versions.map((value, index) => {
        const version = Object.freeze({ name: value[1], code: index + 1 });
        OS['ANDROID_' + value[0]] = version; return version;
    });
    const BrightnessMode = Object.freeze({ 0: 'manual', 1: 'automatic', manual: 0, automatic: 1 });
    function settings() { return java('android.provider.Settings$System'); }
    function audioStream(type) {
        const stream = java('android.media.AudioManager')['STREAM_' + String(type).toUpperCase()];
        if (typeof stream !== 'number') throw new TypeError('Unknown audio stream: ' + type);
        return stream;
    }
    class Device extends EventEmitter {
        static get instance() { return singletonDevice; }
        on(event, listener) {
            if (event === 'battery-changed') return unsupported('device battery-changed receiver');
            return super.on(event, listener);
        }
        get product() {
            const build = java('android.os.Build');
            return { name: build.PRODUCT, device: build.DEVICE, board: build.BOARD, brand: build.BRAND, model: build.MODEL };
        }
        get serial() { const build = java('android.os.Build'); return OS.sdkVersionCode >= 26 ? build.getSerial() : build.SERIAL; }
        get cpuApis() { return toArray(java('android.os.Build').SUPPORTED_ABIS); }
        get buildId() { return java('android.os.Build').ID; }
        get display() { return java('android.os.Build').DISPLAY; }
        get bootloader() { return java('android.os.Build').BOOTLOADER; }
        get hardware() { return java('android.os.Build').HARDWARE; }
        get fingerprint() { return java('android.os.Build').FINGERPRINT; }
        get imei() { return service('phone').getDeviceId(); }
        get androidId() { const Secure = java('android.provider.Settings$Secure'); return Secure.getString(context().getContentResolver(), Secure.ANDROID_ID); }
        getVolume(type) { return service('audio').getStreamVolume(audioStream(type)); }
        setVolume(type, volume, flags) {
            let mask = 0;
            (flags || []).forEach(flag => {
                const value = java('android.media.AudioManager')['FLAG_' + String(flag).toUpperCase()];
                if (typeof value !== 'number') throw new TypeError('Unknown volume flag: ' + flag);
                mask |= value;
            });
            service('audio').setStreamVolume(audioStream(type), volume, mask);
        }
        getVolumeRange(type) {
            const audio = service('audio'), stream = audioStream(type);
            return { min: OS.sdkVersionCode >= 28 ? audio.getStreamMinVolume(stream) : 0, max: audio.getStreamMaxVolume(stream) };
        }
        get battery() { return service('batterymanager').getIntProperty(java('android.os.BatteryManager').BATTERY_PROPERTY_CAPACITY); }
        get displayMetrics() {
            const metrics = new (java('android.util.DisplayMetrics'))();
            service('window').getDefaultDisplay().getRealMetrics(metrics);
            const result = {};
            ['widthPixels','heightPixels','density','scaledDensity','densityDpi','xdpi','ydpi'].forEach(key => { result[key] = metrics[key]; });
            return result;
        }
        get screenSize() {
            if (!this._screenSize) {
                const metrics = this.displayMetrics, rotation = service('window').getDefaultDisplay().getRotation();
                this._screenSize = rotation === 1 || rotation === 3 ? [metrics.heightPixels, metrics.widthPixels] : [metrics.widthPixels, metrics.heightPixels];
            }
            return this._screenSize;
        }
        get screenWidth() { return this.screenSize[0]; }
        get screenHeight() { return this.screenSize[1]; }
        get batteryPluggedTypes() {
            const Intent = java('android.content.Intent');
            const intent = context().registerReceiver(null, new (java('android.content.IntentFilter'))(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return [];
            const plugged = intent.getIntExtra('plugged', -1);
            return [[1,'ac'],[2,'usb'],[4,'wireless']].filter(v => (plugged & v[0]) !== 0).map(v => v[1]);
        }
        get macAddress() {
            if (g.device && typeof g.device.getMacAddress === 'function') return g.device.getMacAddress();
            const interfaces = toArray(java('java.net.NetworkInterface').getNetworkInterfaces());
            for (const network of interfaces) {
                if (String(network.getName()).toLowerCase() !== 'wlan0') continue;
                const address = network.getHardwareAddress();
                if (address != null) return toArray(address).map(n => ('0' + ((Number(n) & 255).toString(16))).slice(-2)).join(':').toUpperCase();
            }
            return null;
        }
        get brightness() { const System = settings(); return System.getInt(context().getContentResolver(), System.SCREEN_BRIGHTNESS); }
        set brightness(value) { const System = settings(); if (!System.putInt(context().getContentResolver(), System.SCREEN_BRIGHTNESS, value)) throw new Error('Cannot set screen brightness'); }
        get brightnessMode() { const System = settings(); return System.getInt(context().getContentResolver(), System.SCREEN_BRIGHTNESS_MODE); }
        set brightnessMode(value) { const System = settings(); if (!System.putInt(context().getContentResolver(), System.SCREEN_BRIGHTNESS_MODE, value)) throw new Error('Cannot set screen brightness mode'); }
        get memoryInfo() {
            const info = new (java('android.app.ActivityManager$MemoryInfo'))(); service('activity').getMemoryInfo(info);
            return { availMem: info.availMem, totalMem: info.totalMem, threshold: info.threshold, lowMemory: info.lowMemory };
        }
        vibrate(millis) { service('vibrator').vibrate(millis); }
        cancelVibration() { service('vibrator').cancel(); }
        get externalStorageDirectory() { return java('android.os.Environment').getExternalStorageDirectory().getPath(); }
    }
    const singletonDevice = new Device();
    register('device', { OS, BrightnessMode, Device, device: singletonDevice }, [
        'battery-changed Android receiver callbacks are unavailable and listener registration throws.',
        'OS version constants match Pro 9.3.11 through API 31; newer sdkName is unknown.',
        'Device identifiers and settings obey Android permissions; Java errors are propagated.'
    ]);

    function intentFlags(flags) {
        if (flags == null) return 0;
        return (Array.isArray(flags) ? flags : [flags]).reduce((mask, flag) => {
            const value = typeof flag === 'string' ? java('android.content.Intent')['FLAG_' + flag.toUpperCase()] : flag;
            if (typeof value !== 'number') throw new TypeError('Unknown Intent flag: ' + flag);
            return mask | value;
        }, 0);
    }
    function parseUri(uri) {
        if (uri != null && typeof uri.getScheme === 'function') return uri;
        const text = String(uri);
        if (text.startsWith('file://')) return getUriForFile(text);
        return java('android.net.Uri').parse(text);
    }
    function getUriForFile(path) {
        const file = new (java('java.io.File'))(filePath(String(path).replace(/^file:\/\//, '')));
        return java('androidx.core.content.FileProvider').getUriForFile(context(), context().getPackageName() + '.fileprovider', file);
    }
    function makeIntent(options) {
        options = options || {};
        const intent = new (java('android.content.Intent'))();
        if (options.packageName && options.className) intent.setClassName(String(options.packageName), String(options.className));
        addIntentExtras(intent, options.extras);
        if (options.category) (Array.isArray(options.category) ? options.category : [options.category]).forEach(category => intent.addCategory(category));
        if (options.action) intent.setAction(String(options.action).includes('.') ? String(options.action) : 'android.intent.action.' + options.action);
        if (options.flags != null) intent.setFlags(intentFlags(options.flags));
        if (options.type && options.data) intent.setDataAndType(parseUri(options.data), options.type);
        else if (options.type) intent.setType(options.type);
        else if (options.data) intent.setData(parseUri(options.data));
        return intent;
    }
    function addIntentExtras(intent, extras) {
        if (!extras) return;
        const bundle = new (java('android.os.Bundle'))();
        Object.entries(extras).forEach(entry => {
            const key = entry[0], value = entry[1];
            if (value == null) bundle.putString(key, null);
            else if (typeof value === 'string') bundle.putString(key, value);
            else if (typeof value === 'boolean') bundle.putBoolean(key, value);
            else if (typeof value === 'number' && Number.isFinite(value)) {
                if (Number.isInteger(value) && value >= -2147483648 && value <= 2147483647) bundle.putInt(key, value);
                else if (Number.isSafeInteger(value)) bundle.putLong(key, value);
                else bundle.putDouble(key, value);
            } else if (Array.isArray(value)) {
                if (value.every(item => typeof item === 'string')) bundle.putStringArray(key, value);
                else if (value.every(item => typeof item === 'boolean')) bundle.putBooleanArray(key, value);
                else if (value.every(item => typeof item === 'number' && Number.isFinite(item))) {
                    if (value.every(item => Number.isInteger(item) && item >= -2147483648 && item <= 2147483647)) bundle.putIntArray(key, value);
                    else if (value.every(Number.isSafeInteger)) bundle.putLongArray(key, value);
                    else bundle.putDoubleArray(key, value);
                } else throw new TypeError('Intent extras require homogeneous primitive arrays: ' + key);
            } else if (value.__javaHandle !== undefined) intent.putExtra(key, value);
            else throw new TypeError('Intent extras require primitives, primitive arrays or Java objects: ' + key);
        });
        intent.putExtras(bundle);
    }
    function quote(value) { return "'" + String(value).replace(/'/g, "'\\''") + "'"; }
    function extraType(value) {
        if (typeof value === 'string') return 's';
        if (typeof value === 'boolean') return 'z';
        if (typeof value === 'number' && Number.isFinite(value)) return Number.isInteger(value) ? (value >= -2147483648 && value <= 2147483647 ? 'i' : 'l') : 'f';
        throw new TypeError('Intent shell extras must be strings, booleans or finite numbers');
    }
    function intentToShell(options) {
        options = options || {}; const args = [];
        if (options.packageName && options.className) args.push('-n', quote(options.packageName + '/' + options.className));
        Object.entries(options.extras || {}).forEach(entry => {
            const key = entry[0], value = entry[1];
            if (Array.isArray(value)) {
                if (!value.length) throw new Error('Empty array: ' + key);
                const type = extraType(value[0]);
                if (value.some(v => extraType(v) !== type)) throw new TypeError('Intent shell arrays must have one type');
                if (type === 'z') return unsupported('boolean array in Android am command');
                const text = value.map(v => type === 's' ? String(v).replace(/\\/g, '\\\\').replace(/,/g, '\\,') : String(v)).join(',');
                args.push('--e' + type + 'a', quote(key), quote(text));
            } else args.push('--e' + extraType(value), quote(key), quote(value));
        });
        if (options.category) (Array.isArray(options.category) ? options.category : [options.category]).forEach(value => args.push('-c', quote(value)));
        if (options.action) args.push('-a', quote(String(options.action).includes('.') ? options.action : 'android.intent.action.' + options.action));
        if (options.flags != null) args.push('-f', String(intentFlags(options.flags)));
        if (options.type) args.push('-t', quote(options.type));
        if (options.data) args.push('-d', quote(options.data));
        return args.join(' ');
    }
    function platformTarget(target, kind) {
        if (typeof target !== 'string') return target || {};
        if (kind === 'activity') {
            const names = { console: 'com.jdkshen.aijspro.ui.log.LogActivity', settings: 'com.jdkshen.aijspro.ui.settings.SettingsActivity' };
            if (!names[target]) throw new Error('No such activity: ' + target);
            return { packageName: context().getPackageName(), className: names[target] };
        }
        if (kind === 'broadcast') return unsupported('named inspector broadcasts; use legacy app.sendBroadcast');
        throw new TypeError('Service options must be an object');
    }
    function rootAction(command, options) {
        const fullCommand = command + ' ' + intentToShell(options);
        // The QuickJS bootstrap exposes global shell(command, root), not require('shell').
        if (typeof g.shell !== 'function') return unsupported('global shell');
        return Promise.resolve(g.shell(fullCommand, true)).then(result => {
            if (!result || typeof result.code !== 'number') throw new Error('Shell did not return an exit code');
            if (result.code !== 0) throw new Error(result.error || 'Android am command failed: ' + result.code);
        });
    }
    function activity(intent) { context().startActivity(intent.addFlags(java('android.content.Intent').FLAG_ACTIVITY_NEW_TASK)); }
    const app = {
        makeIntent, intentToShell, parseUri, getUriForFile,
        startActivity(target) { return Promise.resolve().then(() => { const options = platformTarget(target, 'activity'); if (options.root === true) return rootAction('am start', options); activity(makeIntent(options)); }); },
        startService(target) { return Promise.resolve().then(() => { const options = platformTarget(target, 'service'); if (options.root === true) return rootAction('am startservice', options); context().startService(makeIntent(options)); }); },
        sendBroadcast(target) { return Promise.resolve().then(() => { const options = platformTarget(target, 'broadcast'); if (options.root === true) return rootAction('am broadcast', options); context().sendBroadcast(makeIntent(options)); }); },
        getPackageName(name) {
            const pm = context().getPackageManager();
            for (const info of toArray(pm.getInstalledApplications(0))) if (String(pm.getApplicationLabel(info)) === String(name)) return info.packageName;
            return null;
        },
        getAppName(packageName) {
            try { const pm = context().getPackageManager(), label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)); return label == null ? null : String(label); }
            catch (_) { return null; }
        },
        launch(packageName) {
            const intent = context().getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) return false;
            activity(intent); return true;
        },
        launchApp(name) { const packageName = app.getPackageName(name); return packageName == null ? false : app.launch(packageName); },
        openAppSettings(packageName) {
            try { activity(makeIntent({ action: 'android.settings.APPLICATION_DETAILS_SETTINGS', category: java('android.content.Intent').CATEGORY_DEFAULT, data: 'package:' + packageName })); return true; }
            catch (_) { return false; }
        },
        uninstall(packageName) { activity(makeIntent({ action: 'DELETE', data: 'package:' + packageName })); },
        openUrl(url) { activity(makeIntent({ action: 'VIEW', data: String(url) })); },
        viewFile(file) { openFile(file, 'VIEW'); },
        editFile(file) { openFile(file, 'EDIT'); },
        sendEmail(options) {
            options = options || {};
            const Intent = java('android.content.Intent'), intent = new Intent(Intent.ACTION_SEND);
            const recipients = {};
            [['email',Intent.EXTRA_EMAIL],['cc',Intent.EXTRA_CC],['bcc',Intent.EXTRA_BCC]].forEach(pair => {
                if (options[pair[0]] != null) recipients[pair[1]] = Array.isArray(options[pair[0]]) ? options[pair[0]] : [options[pair[0]]];
            });
            addIntentExtras(intent, recipients);
            if (options.subject != null) intent.putExtra(Intent.EXTRA_SUBJECT, options.subject);
            if (options.text != null) intent.putExtra(Intent.EXTRA_TEXT, options.text);
            if (options.attachment) { intent.putExtra(Intent.EXTRA_STREAM, parseUri(options.attachment)); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            intent.setType('message/rfc822'); activity(Intent.createChooser(intent, options.chooserTitle || 'Send email'));
        },
        getInstalledApps(options) { return toArray(context().getPackageManager().getInstalledApplications(pmFlags(options))); },
        getInstalledPackages(options) { return toArray(context().getPackageManager().getInstalledPackages(pmFlags(options))); },
        getApkInfo(path, options) { return context().getPackageManager().getPackageArchiveInfo(filePath(path), pmFlags(options)); },
        registerBroadcastReceiver() { return unsupported('Android BroadcastReceiver callbacks'); },
        unregisterBroadcastReceiver() { return unsupported('Android BroadcastReceiver callbacks'); }
    };
    Object.defineProperty(app, 'packageName', { enumerable: true, get() { return context().getPackageName(); } });
    function pmFlags(options) {
        let flags = 128;
        if (!options) return flags;
        ['get','match'].forEach(type => {
            const values = options[type]; if (values == null) return;
            if (!Array.isArray(values)) throw new TypeError(type + ' must be an array');
            values.forEach(flag => {
                const value = java('android.content.pm.PackageManager')[(type + '_' + flag).toUpperCase()];
                if (typeof value !== 'number') throw new TypeError('Unknown PackageManager flag: ' + flag);
                flags |= value;
            });
        });
        return flags;
    }
    function openFile(path, action) {
        const fullPath = filePath(path), extension = fullPath.split('/').pop().split('.');
        const mime = extension.length > 1 ? java('android.webkit.MimeTypeMap').getSingleton().getMimeTypeFromExtension(extension.pop().toLowerCase()) : null;
        activity(makeIntent({ action, data: getUriForFile(fullPath), type: mime || '*/*', flags: ['grant_read_uri_permission','grant_write_uri_permission'] }));
    }
    register('app', app, [
        'Android BroadcastReceiver callback registration is unavailable and throws.',
        'Named inspector broadcasts are unavailable in this adapter; legacy app.sendBroadcast remains available.',
        'Intent extras use typed Bundle writes for primitives and homogeneous arrays; plain JS object extras are unavailable.',
        'File intents require a registered FileProvider and its configured paths.',
        'Root actions use the native global shell(command, true), reject invalid/nonzero exit codes, and may block in the host shell; Promise<void> does not wait for the Android component.'
    ]);

    function engineService() {
        const value = java('com.stardust.autojs.ScriptEngineService').getInstance();
        if (value == null) return unsupported('ScriptEngineService');
        return value;
    }
    function bus() {
        if (!g.events || !g.events.bus || typeof g.events.bus.emit !== 'function') return unsupported('cross-engine event bus');
        return g.events.bus;
    }
    function engineEvent(id, event) { return '__ai_modern_engine__:' + id + ':' + String(event); }
    function broadcastEvent(event) { return '__ai_modern_broadcast__:' + String(event); }
    function jsonArgs(args) {
        return args.map(value => {
            const encoded = JSON.stringify(value);
            if (encoded === undefined) throw new TypeError('Engine event arguments must be JSON serializable');
            return JSON.parse(encoded);
        });
    }
    function readConfig(config) {
        if (config == null) return null;
        return Object.freeze({ javaObject: config, workingDirectory: config.getWorkingDirectory() || '', path: toArray(config.getPath()) });
    }
    class ScriptEngine {
        constructor(raw) { this.javaObject = raw; }
        get id() { return Number(this.javaObject.getId()); }
        forceStop() { this.javaObject.forceStop(); }
        getTag(key) { return this.javaObject.getTag(key); }
        get workingDirectory() { return this.javaObject.cwd(); }
        get source() { return this.getTag('source'); }
        get sourceFile() { return this.source.getFile().getPath(); }
        get executionConfig() { return readConfig(this.getTag('execution.config')); }
        emit(event) {
            const args = jsonArgs(Array.prototype.slice.call(arguments, 1));
            const name = String(this.javaObject.getClass().getSimpleName());
            if (name.indexOf('QuickJs') >= 0) { bus().emit.apply(bus(), [engineEvent(this.id, event)].concat(args)); return; }
            if (typeof this.javaObject.emit !== 'function') return unsupported('events for this engine type');
            this.javaObject.emit(String(event), args.map(javaArgument));
        }
    }
    class SelfScriptEngine extends ScriptEngine {
        constructor(raw) { super(raw); this._emitter = new EventEmitter(); this._subscriptions = new Map(); }
        get execArgv() { const config = this.getTag('execution.config'); return config == null ? {} : mapToObject(config.getArguments()); }
        get handler() { return unsupported('V8 SelfScriptEngine.handler callback bridge'); }
        _subscribe(event) {
            if (this._subscriptions.has(event)) return;
            const receive = (...args) => this._emitter.emit.apply(this._emitter, [event].concat(args));
            const sharedBus = bus(), direct = engineEvent(this.id, event), broadcast = broadcastEvent(event);
            sharedBus.on(direct, receive); sharedBus.on(broadcast, receive);
            this._subscriptions.set(event, { receive, direct, broadcast });
        }
        _unsubscribeUnused(event) {
            if (this._emitter.listenerCount(event)) return;
            const item = this._subscriptions.get(event); if (!item) return;
            const sharedBus = bus(); sharedBus.removeListener(item.direct, item.receive); sharedBus.removeListener(item.broadcast, item.receive);
            this._subscriptions.delete(event);
        }
        on(event, listener) { if (typeof listener !== 'function') throw new TypeError('Listener must be a function'); this._subscribe(event); this._emitter.on(event, listener); return this; }
        addListener(event, listener) { return this.on(event, listener); }
        once(event, listener) {
            if (typeof listener !== 'function') throw new TypeError('Listener must be a function');
            const self = this;
            function once() { self.removeListener(event, once); return listener.apply(self, arguments); }
            once.listener = listener; return this.on(event, once);
        }
        removeListener(event, listener) { this._emitter.removeListener(event, listener); this._unsubscribeUnused(event); return this; }
        off(event, listener) { return this.removeListener(event, listener); }
        removeAllListeners(event) {
            if (arguments.length) { this._emitter.removeAllListeners(event); this._unsubscribeUnused(event); }
            else { this._emitter.removeAllListeners(); Array.from(this._subscriptions.keys()).forEach(key => this._unsubscribeUnused(key)); }
            return this;
        }
        listeners(event) { return this._emitter.listeners(event); }
        listenerCount(event) { return this._emitter.listenerCount(event); }
        eventNames() { return this._emitter.eventNames(); }
    }
    let currentEngine;
    function myEngine() {
        if (!currentEngine) {
            const id = Number(g.engines.myEngine().id);
            const raw = toArray(engineService().getEngines()).find(engine => Number(engine.getId()) === id);
            if (!raw) return unsupported('current engine Java handle');
            currentEngine = new SelfScriptEngine(raw);
        }
        return currentEngine;
    }
    function javaArgument(value) {
        if (value === null || typeof value !== 'object') return value;
        if (Array.isArray(value)) {
            const list = new (java('java.util.ArrayList'))(); value.forEach(item => list.add(javaArgument(item))); return list;
        }
        const map = new (java('java.util.HashMap'))(); Object.entries(value).forEach(entry => map.put(entry[0], javaArgument(entry[1]))); return map;
    }
    class ScriptExecution {
        constructor(raw) { this.javaObject = raw; this._enginePromise = null; }
        get engineOrNull() { const raw = this.javaObject.getEngine(); return raw == null ? null : new ScriptEngine(raw); }
        get executionConfig() { return readConfig(this.javaObject.getConfig()); }
        get source() { return this.javaObject.getSource(); }
        get sourceFile() { return this.source.getFile().getPath(); }
        engine() {
            if (this._enginePromise) return this._enginePromise;
            this._enginePromise = new Promise((resolve, reject) => {
                const start = Date.now();
                const poll = () => {
                    try {
                        const engine = this.engineOrNull;
                        if (engine) return resolve(engine);
                        if (Date.now() - start >= 30000) return reject(new Error('Engine was not created within 30 seconds'));
                        g.setTimeout(poll, 10);
                    } catch (error) { reject(error); }
                };
                poll();
            });
            return this._enginePromise;
        }
        on() { return unsupported('ScriptExecution start/success/exception callbacks'); }
        once() { return unsupported('ScriptExecution start/success/exception callbacks'); }
        addListener() { return unsupported('ScriptExecution start/success/exception callbacks'); }
    }
    const engines = {
        myEngine,
        getRunningEngines() { return toArray(engineService().getEngines()).map(raw => new ScriptEngine(raw)); },
        stopAll(options) { if (options && options.toast) engineService().stopAllAndToast(); else engineService().stopAll(); },
        broadcast(event) {
            const args = jsonArgs(Array.prototype.slice.call(arguments, 1));
            bus().emit.apply(bus(), [broadcastEvent(event)].concat(args));
            engines.getRunningEngines().forEach(engine => {
                const name = String(engine.javaObject.getClass().getSimpleName());
                if (name.indexOf('QuickJs') < 0) engine.emit.apply(engine, [event].concat(args));
            });
        },
        execScriptFile(path, options) {
            options = options || {};
            const resolved = filePath(path), file = new (java('java.io.File'))(resolved);
            if (file.isDirectory()) return unsupported('project directory execution');
            const config = new (java('com.stardust.autojs.execution.ExecutionConfig'))();
            const cwd = options.workingDirectory == null ? myEngine().workingDirectory : filePath(options.workingDirectory);
            config.setWorkingDirectory(String(cwd || ''));
            Object.entries(options.arguments || {}).forEach(entry => {
                const encoded = JSON.stringify(entry[1]);
                if (encoded === undefined) throw new TypeError('Execution arguments must be JSON serializable');
                const value = JSON.parse(encoded);
                // The backing HashMap accepts null; the legacy Kotlin setter declares non-null.
                if (value === null) config.getArguments().put(entry[0], null);
                else config.setArgument(entry[0], javaArgument(value));
            });
            const source = new (java('com.stardust.autojs.script.JavaScriptFileSource'))(resolved);
            source.setForcedEngine('quickjs');
            return new ScriptExecution(engineService().execute(source, config));
        }
    };
    register('engines', engines, [
        'QuickJS modern engine events use the real shared event bus; payloads must be JSON serializable.',
        'Rhino engine events use JavaScriptEngine.emit with Java Map/List object payloads; Pro V8 emitJson conversion is unavailable.',
        'QuickJS legacy events.on does not receive modern bus channels.',
        'ScriptExecution.engine() observes engine creation, not the Pro V8 engine-ready callback; no start/success/exception listener bridge.',
        'Execution readiness polling rejects if no engine appears in 30 seconds.',
        'Project directories, WorkManager scheduling, V8 handler, projectConfig/projectDir and V8-specific lifecycle facilities are unavailable.',
        'execScriptFile starts QuickJS, with workingDirectory and serialized arguments; no Node package/runtime emulation.'
    ]);
})(globalThis);
