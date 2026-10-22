// Shared Pro API v2 "lang" and "java_utils" modules for Rhino, QuickJS and Node.
// ES5 syntax is intentional: the same asset is evaluated by Rhino.
(function (g) {
    'use strict';

    var node = typeof g.__ajsRegisterModule === 'function';
    var hasOwn = Object.prototype.hasOwnProperty;
    var defineProperty = Object.defineProperty;
    var getOwnPropertyDescriptor = Object.getOwnPropertyDescriptor;
    var getPrototypeOf = Object.getPrototypeOf;

    function type(name) {
        if (node) {
            if (!g.$autojs || !g.$autojs.java || typeof g.$autojs.java.findClass !== 'function') {
                throw new TypeError('java_utils requires $autojs.java.findClass');
            }
            return g.$autojs.java.findClass(name);
        }
        if (g.Java && typeof g.Java.type === 'function') {
            return g.Java.type(name);
        }
        if (!g.Packages) {
            throw new TypeError('java_utils requires Java.type or Packages');
        }
        var value = g.Packages;
        var parts = String(name).split('.');
        for (var i = 0; i < parts.length; i++) {
            if (value === null || value === undefined) {
                throw new TypeError('Cannot resolve Java class: ' + name);
            }
            value = value[parts[i]];
        }
        if (value === null || value === undefined) {
            throw new TypeError('Cannot resolve Java class: ' + name);
        }
        return value;
    }

    function noop() {}

    // A pending Deferred/lang.promise keeps a timer alive across engines. Rhino,
    // QuickJS and the Node24 host all expose setInterval/clearInterval, and the
    // timer is removed as soon as the promise settles.
    function holdOpen() {
        if (typeof g.setInterval === 'function') {
            return g.setInterval(noop, 1000);
        }
        return null;
    }

    function release(id) {
        if (id === null || id === undefined) {
            return;
        }
        if (typeof g.clearInterval === 'function') {
            g.clearInterval(id);
        }
    }

    function ownerName(target) {
        var constructor = target && target.constructor;
        return constructor && constructor.name ? String(constructor.name) : 'unknown';
    }

    function lazy(target, name, descriptor) {
        if (target === null || target === undefined
            || (typeof target !== 'object' && typeof target !== 'function')) {
            throw new TypeError('@lazy target must be an object');
        }
        if (typeof name !== 'string' || name.length === 0) {
            throw new TypeError('@lazy property name must be a non-empty string');
        }
        descriptor = descriptor || getOwnPropertyDescriptor(target, name) || {};
        var initializer = descriptor.get;
        if (typeof initializer !== 'function') {
            throw new TypeError('@lazy can only decorate a getter without a setter on '
                + ownerName(target) + '.' + name);
        }
        if (descriptor.set) {
            throw new TypeError('@lazy cannot decorate ' + ownerName(target) + '.' + name
                + ' because a setter already exists');
        }

        var owner = target.constructor;
        var enumerable = descriptor.enumerable === true;
        var configurable = descriptor.configurable === true;

        function store(receiver, value) {
            defineProperty(receiver, name, {
                value: value,
                writable: true,
                enumerable: enumerable,
                configurable: configurable
            });
            return value;
        }

        function get() {
            if (this === target) {
                return initializer;
            }
            // Accessing a getter on a subclass prototype must keep the decorator
            // metadata visible; accessing an instance computes and caches it.
            if (owner && this && this.constructor !== owner) {
                var proto = getPrototypeOf(this);
                if (proto && proto.constructor === owner) {
                    return initializer;
                }
            }
            return store(this, initializer.call(this));
        }

        function set(value) {
            return store(this, value);
        }

        var result = {get: get, set: set};
        if (hasOwn.call(descriptor, 'enumerable')) {
            result.enumerable = descriptor.enumerable;
        }
        if (hasOwn.call(descriptor, 'configurable')) {
            result.configurable = descriptor.configurable;
        }
        return result;
    }

    function lazyProp(evaluator) {
        if (typeof evaluator !== 'function') {
            throw new TypeError('lazyProp evaluator must be a function');
        }
        var ready = false;
        var value;
        return {
            get: function () {
                if (!ready) {
                    value = evaluator();
                    ready = true;
                }
                return value;
            }
        };
    }

    function promise(executor) {
        if (typeof executor !== 'function') {
            throw new TypeError('promise executor must be a function');
        }
        var hold = holdOpen();
        return new Promise(executor).then(function (value) {
            release(hold);
            return value;
        }, function (reason) {
            release(hold);
            throw reason;
        });
    }

    function delay(timeout) {
        var ms = Number(timeout);
        if (!(ms > 0)) {
            return Promise.resolve();
        }
        return new Promise(function (resolve) {
            if (typeof g.setTimeout !== 'function') {
                throw new TypeError('delay requires setTimeout');
            }
            g.setTimeout(resolve, ms);
        });
    }

    function Deferred(keepRunning) {
        if (!(this instanceof Deferred)) {
            return new Deferred(keepRunning);
        }
        var self = this;
        self.finished = false;
        self._value = undefined;
        self._keep = null;
        self._promise = new Promise(function (resolve, reject) {
            self._resolve = resolve;
            self._reject = reject;
        });
        if (keepRunning === undefined ? true : !!keepRunning) {
            self._keep = holdOpen();
        }
    }

    function finish(deferred) {
        deferred.finished = true;
        release(deferred._keep);
        deferred._keep = null;
    }

    defineProperty(Deferred.prototype, 'resolve', {
        configurable: true,
        writable: true,
        value: function (value) {
            if (this.finished) {
                return false;
            }
            this._value = value;
            finish(this);
            this._resolve(value);
            return true;
        }
    });
    defineProperty(Deferred.prototype, 'reject', {
        configurable: true,
        writable: true,
        value: function (reason) {
            if (this.finished) {
                return false;
            }
            finish(this);
            this._reject(reason);
            return true;
        }
    });
    defineProperty(Deferred.prototype, 'promise', {
        configurable: true,
        writable: true,
        value: function () {
            return this._promise;
        }
    });
    defineProperty(Deferred.prototype, 'value', {
        configurable: true,
        get: function () {
            return this._value;
        }
    });

    function TimeoutError(message) {
        if (!(this instanceof TimeoutError)) {
            return new TimeoutError(message);
        }
        defineProperty(this, 'name', {
            configurable: true,
            writable: true,
            value: 'TimeoutError'
        });
        defineProperty(this, 'message', {
            configurable: true,
            writable: true,
            value: message === undefined ? '' : String(message)
        });
        if (typeof Error.captureStackTrace === 'function') {
            Error.captureStackTrace(this, TimeoutError);
        } else {
            // Rhino/QuickJS do not implement V8's captureStackTrace; still expose
            // the stack shape callers expect from an Error subclass.
            var temporary = new Error(this.message);
            if (temporary.stack !== undefined) {
                defineProperty(this, 'stack', {
                    configurable: true,
                    writable: true,
                    value: temporary.stack
                });
            }
        }
    }
    TimeoutError.prototype = Object.create(Error.prototype);
    defineProperty(TimeoutError.prototype, 'constructor', {
        configurable: true,
        writable: true,
        value: TimeoutError
    });
    defineProperty(TimeoutError.prototype, 'name', {
        configurable: true,
        writable: true,
        value: 'TimeoutError'
    });

    var lang = {
        lazy: lazy,
        lazyProp: lazyProp,
        promise: promise,
        delay: delay,
        Deferred: Deferred,
        TimeoutError: TimeoutError
    };

    function toStringOr(value, fallback) {
        if (value === null || value === undefined) {
            return fallback;
        }
        return value.toString();
    }

    function toStringOrEmpty(value) {
        return toStringOr(value, '');
    }

    function javaListToArray(javaList) {
        if (javaList === null || javaList === undefined) {
            return null;
        }
        var size = Number(javaList.size());
        var array = new Array(size);
        for (var i = 0; i < size; i++) {
            array[i] = javaList.get(i);
        }
        return array;
    }

    function javaSetToArray(javaSet) {
        if (javaSet === null || javaSet === undefined) {
            return null;
        }
        var iterator = javaSet.iterator();
        var array = [];
        while (iterator.hasNext()) {
            array.push(iterator.next());
        }
        return array;
    }

    function javaMapToObject(object, javaMap, valueTransform) {
        if (javaMap === null || javaMap === undefined) {
            return null;
        }
        if (object === null || object === undefined) {
            throw new TypeError('javaMapToObject target must be an object');
        }
        if (valueTransform !== null && valueTransform !== undefined
            && typeof valueTransform !== 'function') {
            throw new TypeError('javaMapToObject valueTransform must be a function');
        }
        var transform = valueTransform || null;
        if (typeof javaMap.keySet === 'function') {
            var iterator = javaMap.keySet().iterator();
            while (iterator.hasNext()) {
                var key = iterator.next();
                object[key] = transform ? transform(javaMap.get(key)) : javaMap.get(key);
            }
        } else {
            // Some Java map adapters expose entrySet but not keySet.
            var entries = javaMap.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                var entryKey = entry.getKey();
                object[entryKey] = transform ? transform(entry.getValue()) : entry.getValue();
            }
        }
        return object;
    }

    function objectToJavaMap(object, valueTransform) {
        if (object === null || object === undefined
            || (typeof object !== 'object' && typeof object !== 'function')) {
            throw new TypeError('objectToJavaMap requires an object');
        }
        if (valueTransform !== null && valueTransform !== undefined
            && typeof valueTransform !== 'function') {
            throw new TypeError('objectToJavaMap valueTransform must be a function');
        }
        var HashMap = type('java.util.HashMap');
        var map = new HashMap();
        var keys = Object.keys(object);
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            map.put(key, valueTransform ? valueTransform(object[key]) : object[key]);
        }
        return map;
    }

    function bufferToByteArray(buffer) {
        if (buffer === null || buffer === undefined) {
            throw new TypeError('bufferToByteArray requires a Buffer');
        }
        var length = buffer.length;
        if (length === undefined) {
            length = buffer.byteLength;
        }
        length = Number(length);
        if (!isFinite(length) || length < 0) {
            throw new TypeError('bufferToByteArray buffer length is invalid');
        }
        length = Math.floor(length);
        var values = new Array(length);
        for (var i = 0; i < length; i++) {
            // Java bytes are signed. Convert 128..255 to -128..-1 instead of
            // handing unsigned numbers to the Java array bridge.
            var value = Number(buffer[i]) & 255;
            values[i] = value > 127 ? value - 256 : value;
        }

        if (!node && g.Java && typeof g.Java.array === 'function') {
            return g.Java.array('byte', values);
        }
        if (node) {
            var ByteArray = type('[B');
            var array = new ByteArray(length);
            for (var j = 0; j < length; j++) {
                array[j] = values[j];
            }
            return array;
        }
        var ArrayClass = type('java.lang.reflect.Array');
        var ByteClass = type('java.lang.Byte');
        var bytes = ArrayClass.newInstance(ByteClass.TYPE, length);
        for (var k = 0; k < length; k++) {
            bytes[k] = values[k];
        }
        return bytes;
    }

    var javaUtils = {
        toStringOr: toStringOr,
        toStringOrEmpty: toStringOrEmpty,
        javaListToArray: javaListToArray,
        javaSetToArray: javaSetToArray,
        javaMapToObject: javaMapToObject,
        objectToJavaMap: objectToJavaMap,
        bufferToByteArray: bufferToByteArray
    };

    if (node) {
        g.__ajsRegisterModule('lang', function () {
            return lang;
        });
        g.__ajsRegisterModule('java_utils', function () {
            return javaUtils;
        });
        var builtins = g.__ajsBuiltinModules || (g.__ajsBuiltinModules = Object.create(null));
        var previousLang = builtins.lang;
        builtins.lang = lang;
        builtins.java_utils = javaUtils;
        if (g.$autojs) {
            g.$autojs.lang = lang;
        }
        // The Node24 prelude historically also exposed sleep()/exit() through
        // require('lang'). Keep those host helpers reachable without adding
        // them to the Pro v2 export inventory.
        if (previousLang && previousLang !== lang) {
            var names = Object.getOwnPropertyNames(previousLang);
            for (var n = 0; n < names.length; n++) {
                var key = names[n];
                if (!hasOwn.call(lang, key)) {
                    defineProperty(lang, key, {
                        configurable: true,
                        writable: true,
                        enumerable: false,
                        value: previousLang[key]
                    });
                }
            }
        }
    } else {
        var modules = g.__aiModernModules || (g.__aiModernModules = Object.create(null));
        modules.lang = lang;
        modules.java_utils = javaUtils;
    }
})(typeof globalThis === 'object' ? globalThis : global);
