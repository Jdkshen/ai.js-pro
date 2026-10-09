// Rhino 1.7.7.2 has Symbol and array iterators, but no Map constructor.
// Small ordered-map compatibility implementation; lookup is linear.
module.exports = function (scope) {
    if (typeof scope.Map === 'function') return scope.Map;
    var slot = Symbol('Map data');
    var own = Object.prototype.hasOwnProperty;
    function data(receiver) {
        if (!receiver || !own.call(receiver, slot)) throw new TypeError('Incompatible Map receiver');
        return receiver[slot];
    }
    function index(entries, key) {
        for (var i = 0; i < entries.length; i++) {
            var e = entries[i];
            if (e.live && (e.key === key || (e.key !== e.key && key !== key))) return i;
        }
        return -1;
    }
    function Map(iterable) {
        if (!(this instanceof Map)) throw new TypeError('Map requires new');
        Object.defineProperty(this, slot, { value: { entries: [], size: 0, walkers: 0 } });
        if (iterable == null) return;
        var adder = this.set;
        if (typeof adder !== 'function') throw new TypeError('Map.set must be callable');
        var method = iterable[Symbol.iterator];
        if (typeof method !== 'function') throw new TypeError('Map entries must be iterable');
        var iterator = method.call(iterable);
        if (!iterator || typeof iterator.next !== 'function') throw new TypeError('Invalid iterator');
        var step;
        while (!(step = iterator.next()).done) {
            try {
                var entry = step.value;
                if (entry === null || (typeof entry !== 'object' && typeof entry !== 'function')) {
                    throw new TypeError('Map entry must be an object');
                }
                adder.call(this, entry[0], entry[1]);
            } catch (error) {
                if (typeof iterator['return'] === 'function') {
                    try { iterator['return'](); } catch (ignored) {}
                }
                throw error;
            }
        }
    }
    function method(name, fn) {
        Object.defineProperty(Map.prototype, name, { value: fn, writable: true, configurable: true });
    }
    method('set', function (key, value) {
        var d = data(this), i = index(d.entries, key);
        if (i >= 0) d.entries[i].value = value;
        else {
            if (!d.walkers && d.entries.length > 32 && d.entries.length > d.size * 2) {
                d.entries = d.entries.filter(function (entry) { return entry.live; });
            }
            d.entries.push({ key: key === 0 ? 0 : key, value: value, live: true }); d.size++;
        }
        return this;
    });
    method('get', function (key) {
        var d = data(this), i = index(d.entries, key);
        return i < 0 ? undefined : d.entries[i].value;
    });
    method('has', function (key) { return index(data(this).entries, key) >= 0; });
    function erase(entry) { entry.live = false; entry.key = undefined; entry.value = undefined; }
    method('delete', function (key) {
        var d = data(this), i = index(d.entries, key);
        if (i < 0) return false;
        erase(d.entries[i]); d.size--; return true;
    });
    method('clear', function () {
        var d = data(this);
        d.entries.forEach(erase); d.size = 0;
        if (!d.walkers) d.entries = [];
    });
    method('forEach', function (callback, receiver) {
        var d = data(this);
        if (typeof callback !== 'function') throw new TypeError('Callback must be callable');
        d.walkers++;
        try {
            for (var i = 0; i < d.entries.length; i++) {
                var e = d.entries[i];
                if (e.live) callback.call(receiver, e.value, e.key, this);
            }
        } finally { d.walkers--; }
    });
    function iterator(map, kind) {
        var d = data(map), cursor = 0, finished = false;
        d.walkers++;
        var result = { next: function () {
            if (!finished) {
                while (cursor < d.entries.length) {
                    var e = d.entries[cursor++];
                    if (e.live) return { done: false, value: kind === 0 ? e.key : kind === 1 ? e.value : [e.key, e.value] };
                }
                finished = true;
                d.walkers--;
            }
            return { done: true, value: undefined };
        } };
        Object.defineProperty(result, Symbol.iterator, { value: function () { return this; } });
        return result;
    }
    method('keys', function () { return iterator(this, 0); });
    method('values', function () { return iterator(this, 1); });
    method('entries', function () { return iterator(this, 2); });
    method(Symbol.iterator, Map.prototype.entries);
    Object.defineProperty(Map.prototype, 'size', { configurable: true, get: function () { return data(this).size; } });
    if (Symbol.toStringTag) Object.defineProperty(Map.prototype, Symbol.toStringTag, { value: 'Map', configurable: true });
    scope.Map = Map;
    return Map;
};
