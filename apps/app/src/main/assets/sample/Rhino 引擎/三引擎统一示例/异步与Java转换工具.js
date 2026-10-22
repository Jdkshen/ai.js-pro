"rhino";
// Same source on Rhino / QuickJS / Node. Requires the shared common-lang.js module.
// Asserts the Pro API v2 lang + java_utils surface, including real Java conversions.
(function () {
    var node = typeof __ajsRegisterModule === 'function';
    var checks = [];

    function type(name) {
        if (node) {
            return $autojs.java.findClass(name);
        }
        if (typeof Java !== 'undefined' && Java && typeof Java.type === 'function') {
            return Java.type(name);
        }
        var value = Packages;
        var parts = String(name).split('.');
        for (var i = 0; i < parts.length; i++) {
            value = value[parts[i]];
        }
        return value;
    }

    function check(name, fn) {
        try {
            if (!fn()) {
                throw new Error('assertion returned false');
            }
            checks.push({name: name, ok: true});
        } catch (error) {
            checks.push({name: name, ok: false, error: String(error)});
        }
    }

    function checkAsync(name, fn) {
        return Promise.resolve().then(fn).then(function (value) {
            if (!value) {
                throw new Error('assertion returned false');
            }
            checks.push({name: name, ok: true});
        }, function (error) {
            checks.push({name: name, ok: false, error: String(error)});
        });
    }

    function report() {
        var failed = [];
        for (var i = 0; i < checks.length; i++) {
            if (!checks[i].ok) {
                failed.push(checks[i]);
            }
        }
        console.log('COMMON_LANG_RESULT=' + JSON.stringify({
            checks: checks,
            passed: checks.length - failed.length,
            failed: failed
        }));
    }

    var lang = require('lang');
    var javaUtils = require('java_utils');
    var langExports = ['lazy', 'lazyProp', 'promise', 'delay', 'Deferred', 'TimeoutError'];
    var javaExports = ['toStringOr', 'toStringOrEmpty', 'javaListToArray', 'javaSetToArray',
        'javaMapToObject', 'objectToJavaMap', 'bufferToByteArray'];

    check('lang.exports', function () {
        return langExports.every(function (name) { return typeof lang[name] === 'function'; });
    });
    check('java_utils.exports', function () {
        return javaExports.every(function (name) { return typeof javaUtils[name] === 'function'; });
    });

    check('lang.lazy.requiresGetter', function () {
        try {
            lang.lazy({}, 'missing');
            return false;
        } catch (error) {
            return error instanceof TypeError;
        }
    });
    check('lang.lazy.rejectsSetter', function () {
        var target = {};
        var descriptor = {
            configurable: true,
            get: function () { return 1; },
            set: function () {}
        };
        try {
            lang.lazy(target, 'value', descriptor);
            return false;
        } catch (error) {
            return error instanceof TypeError;
        }
    });
    check('lang.lazy.descriptor', function () {
        function Lazy() {}
        var calls = 0;
        var initializer = function () { calls++; return Number(this.n) * 2; };
        Object.defineProperty(Lazy.prototype, 'double', {
            configurable: true,
            enumerable: true,
            get: initializer
        });
        var decorated = lang.lazy(Lazy.prototype, 'double',
            Object.getOwnPropertyDescriptor(Lazy.prototype, 'double'));
        Object.defineProperty(Lazy.prototype, 'double', decorated);
        var instance = new Lazy();
        instance.n = 3;
        var first = instance.double;
        var second = instance.double;
        var own = Object.getOwnPropertyDescriptor(instance, 'double');
        var prototypeGetter = Object.getOwnPropertyDescriptor(Lazy.prototype, 'double').get;
        return calls === 1 && first === 6 && second === 6
            && own && own.value === 6 && own.enumerable === true && own.configurable === true
            && prototypeGetter.call(Lazy.prototype) === initializer;
    });
    check('lang.lazy.setter', function () {
        function Lazy() {}
        Object.defineProperty(Lazy.prototype, 'value', {
            configurable: true,
            enumerable: true,
            get: function () { return 1; }
        });
        Object.defineProperty(Lazy.prototype, 'value',
            lang.lazy(Lazy.prototype, 'value'));
        var instance = new Lazy();
        instance.value = 9;
        return instance.value === 9;
    });
    check('lang.lazyProp.caches', function () {
        var evaluations = 0;
        var property = lang.lazyProp(function () {
            evaluations++;
            return {value: evaluations};
        });
        var first = property.get();
        var second = property.get();
        return evaluations === 1 && first === second && first.value === 1
            && typeof property.get === 'function';
    });

    check('lang.TimeoutError', function () {
        var error = new lang.TimeoutError('late');
        return error instanceof Error && error instanceof lang.TimeoutError
            && error.message === 'late' && error.name === 'TimeoutError';
    });

    check('lang.Deferred.resolve', function () {
        var deferred = new lang.Deferred(false);
        var promise = deferred.promise();
        var first = deferred.resolve(5);
        var second = deferred.resolve(6);
        return promise === deferred.promise() && first === true && second === false
            && deferred.finished === true && deferred.value === 5;
    });
    check('lang.Deferred.reject', function () {
        var deferred = new lang.Deferred(false);
        var promise = deferred.promise();
        promise.then(function () {}, function () {});
        var first = deferred.reject(new Error('expected'));
        var second = deferred.reject(new Error('late'));
        return first === true && second === false && deferred.finished === true
            && deferred.value === undefined;
    });

    check('java_utils.toString', function () {
        return javaUtils.toStringOr(null, 'fallback') === 'fallback'
            && javaUtils.toStringOr(undefined, 'fallback') === 'fallback'
            && javaUtils.toStringOr(0, 'fallback') === '0'
            && javaUtils.toStringOr(false, 'fallback') === 'false'
            && javaUtils.toStringOr({toString: function () { return 'custom'; }}, '') === 'custom'
            && javaUtils.toStringOrEmpty(null) === ''
            && javaUtils.toStringOrEmpty(undefined) === ''
            && javaUtils.toStringOrEmpty(12) === '12';
    });
    check('java_utils.nullContract', function () {
        return javaUtils.javaListToArray(null) === null
            && javaUtils.javaListToArray(undefined) === null
            && javaUtils.javaSetToArray(null) === null
            && javaUtils.javaSetToArray(undefined) === null
            && javaUtils.javaMapToObject({}, null) === null
            && javaUtils.javaMapToObject({}, undefined) === null;
    });

    check('java_utils.realList', function () {
        var ArrayList = type('java.util.ArrayList');
        var list = new ArrayList();
        list.add('first');
        list.add('second');
        var array = javaUtils.javaListToArray(list);
        return array.length === 2 && String(array[0]) === 'first' && String(array[1]) === 'second';
    });
    check('java_utils.realSet', function () {
        var HashSet = type('java.util.HashSet');
        var set = new HashSet();
        set.add('beta');
        set.add('alpha');
        var array = javaUtils.javaSetToArray(set).sort();
        return array.length === 2 && String(array[0]) === 'alpha' && String(array[1]) === 'beta';
    });
    check('java_utils.realMap', function () {
        var HashMap = type('java.util.HashMap');
        var map = new HashMap();
        map.put('one', 1);
        map.put('two', 2);
        var target = {};
        var result = javaUtils.javaMapToObject(target, map, function (value) {
            return Number(value) * 10;
        });
        return result === target && result.one === 10 && result.two === 20;
    });
    check('java_utils.objectToJavaMap', function () {
        var map = javaUtils.objectToJavaMap({alpha: 1, beta: 'text'});
        return Number(map.size()) === 2 && Number(map.get('alpha')) === 1
            && String(map.get('beta')) === 'text';
    });
    check('java_utils.byteArray.signed', function () {
        var bytes = javaUtils.bufferToByteArray({length: 4, 0: 0, 1: 127, 2: 128, 3: 255});
        return Number(bytes.length) === 4 && Number(bytes[0]) === 0
            && Number(bytes[1]) === 127 && Number(bytes[2]) === -128
            && Number(bytes[3]) === -1;
    });
    check('java_utils.byteArray.empty', function () {
        var bytes = javaUtils.bufferToByteArray({length: 0});
        return Number(bytes.length) === 0;
    });

    var asyncChecks = [
        checkAsync('lang.delay.immediate', function () {
            return lang.delay(0).then(function () { return true; });
        }),
        checkAsync('lang.delay.timer', function () {
            var started = Date.now();
            return lang.delay(30).then(function () {
                return Date.now() - started >= 15;
            });
        }),
        checkAsync('lang.promise.resolve', function () {
            return lang.promise(function (resolve) {
                setTimeout(function () { resolve(9); }, 20);
            }).then(function (value) { return value === 9; });
        }),
        checkAsync('lang.promise.reject', function () {
            return lang.promise(function (resolve, reject) {
                setTimeout(function () { reject(new Error('promise')); }, 20);
            }).then(function () {
                return false;
            }, function (error) {
                return error.message === 'promise';
            });
        }),
        checkAsync('lang.promise.executorError', function () {
            return lang.promise(function () {
                throw new Error('sync');
            }).then(function () {
                return false;
            }, function (error) {
                return error.message === 'sync';
            });
        }),
        checkAsync('lang.Deferred.timerResolve', function () {
            var deferred = new lang.Deferred();
            setTimeout(function () { deferred.resolve('resolved'); }, 25);
            return deferred.promise().then(function (value) {
                return value === 'resolved' && deferred.value === 'resolved';
            });
        }),
        checkAsync('lang.Deferred.timerReject', function () {
            var deferred = new lang.Deferred();
            setTimeout(function () { deferred.reject(new Error('deferred')); }, 25);
            return deferred.promise().then(function () {
                return false;
            }, function (error) {
                return error.message === 'deferred' && deferred.value === undefined;
            });
        })
    ];

    Promise.all(asyncChecks).then(report, function (error) {
        check('async.suite', function () { throw error; });
        report();
    });
})();
