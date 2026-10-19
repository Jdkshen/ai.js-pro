(function (global) {
    'use strict';
    var current = global.console;
    if (!current) return;
    var next = Object.assign({}, current);
    if (typeof next.debug !== 'function') next.debug = current.log;
    if (typeof next.assert !== 'function') {
        next.assert = function (condition) {
            if (condition) return;
            var parts = Array.prototype.slice.call(arguments, 1).map(function (value) {
                if (typeof value === 'string') return value;
                if (value instanceof Error) return value.stack || value.message;
                try {
                    var text = JSON.stringify(value);
                    return text === undefined ? String(value) : text;
                } catch (_) { return String(value); }
            });
            var message = parts.length ? parts.join(' ') : 'Assertion failed';
            __aiNativeLog(7, message);
            var failure = new Error(message);
            failure.name = 'AssertionError';
            throw failure;
        };
    }
    global.console = Object.freeze(next);
})(globalThis);
