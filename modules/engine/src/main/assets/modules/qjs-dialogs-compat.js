// Dialog object contracts shared by the QuickJS classic and modern adapters.
(function (global) {
    function emitter() {
        var listeners = new Map(), target = {};
        target.on = function (name, fn) {
            if (typeof fn !== 'function') throw new TypeError('listener must be a function');
            name = String(name);
            if (!listeners.has(name)) listeners.set(name, []);
            listeners.get(name).push(fn);
            return target;
        };
        target.addListener = target.on;
        target.once = function (name, fn) {
            if (typeof fn !== 'function') throw new TypeError('listener must be a function');
            function wrapper() { target.removeListener(name, wrapper); return fn.apply(target, arguments); }
            wrapper.listener = fn;
            return target.on(name, wrapper);
        };
        target.removeListener = function (name, fn) {
            var list = listeners.get(String(name));
            if (list) {
                for (var i = list.length - 1; i >= 0; i--) if (list[i] === fn || list[i].listener === fn) list.splice(i, 1);
                if (!list.length) listeners.delete(String(name));
            }
            return target;
        };
        target.off = target.removeListener;
        target.removeAllListeners = function (name) {
            if (name === undefined) listeners.clear(); else listeners.delete(String(name));
            return target;
        };
        target.emit = function (name) {
            var list = listeners.get(String(name));
            if (!list || !list.length) return false;
            var args = Array.prototype.slice.call(arguments, 1);
            list.slice().forEach(function (fn) { fn.apply(target, args); });
            return true;
        };
        target.eventNames = function () { return Array.from(listeners.keys()); };
        target.listenerCount = function (name) { return (listeners.get(String(name)) || []).length; };
        target.listeners = function (name) { return (listeners.get(String(name)) || []).slice(); };
        return target;
    }
    function nativeCall(id, method, args) {
        var raw = __aiNativeDialogCall(JSON.stringify({ id: id, method: method, args: args || [] }));
        var result = raw == null || raw === '' ? null : JSON.parse(raw);
        if (result && result.error !== undefined) throw new Error(String(result.error));
        return result;
    }
    function build(props) {
        var info = JSON.parse(__aiNativeDialogBuild(JSON.stringify(props || {})));
        if (info.error !== undefined) throw new Error(String(info.error));
        var dialog = emitter(), timer = null;
        dialog.id = Number(info.id);
        function poll() {
            (nativeCall(dialog.id, 'poll') || []).forEach(function (item) {
                if (item.event === 'dismiss' && timer !== null) { clearInterval(timer); timer = null; }
                var args = (item.args || []).map(function (arg) { return arg && arg.__dialog ? dialog : arg; });
                dialog.emit.apply(dialog, [item.event].concat(args));
            });
        }
        dialog.__poll = poll;
        dialog.show = function () {
            nativeCall(dialog.id, 'show');
            if (timer === null) timer = setInterval(poll, 40);
            return dialog;
        };
        dialog.dismiss = function () { nativeCall(dialog.id, 'dismiss'); poll(); return dialog; };
        dialog.cancel = function () { nativeCall(dialog.id, 'cancel'); poll(); return dialog; };
        dialog.isShowing = function () { return !!nativeCall(dialog.id, 'isShowing'); };
        dialog.close = function () {
            if (timer !== null) { clearInterval(timer); timer = null; }
            nativeCall(dialog.id, 'close');
            return dialog;
        };
        ['setTitle', 'setContent', 'setActionButton', 'setProgress', 'incrementProgress', 'setMaxProgress'].forEach(function (method) {
            dialog[method] = function () { nativeCall(dialog.id, method, Array.prototype.slice.call(arguments)); return dialog; };
        });
        ['getProgress', 'getMaxProgress', 'getInput'].forEach(function (method) {
            dialog[method] = function () { return nativeCall(dialog.id, method); };
        });
        return dialog;
    }
    function simple(kind, title, content, items, selected, callback) {
        var props = { title: String(title == null ? '' : title), positive: 'OK', negative: 'Cancel' };
        var settled = false, result, failure;
        var finish = function (value) { if (!settled) { settled = true; result = value; } };
        var fallback = kind === 'confirm' ? false : kind === 'rawInput' || kind === 'input' ? null
            : kind === 'multiChoice' ? [] : kind === 'alert' ? undefined : -1;
        if (kind === 'alert' || kind === 'confirm') props.content = String(content == null ? '' : content);
        if (kind === 'alert') delete props.negative;
        if (kind === 'rawInput' || kind === 'input') { props.inputHint = ''; props.inputPrefill = String(content == null ? '' : content); }
        if (items) {
            props.items = items;
            props.itemsSelectMode = kind === 'multiChoice' ? 'multi' : kind === 'singleChoice' ? 'single' : 'select';
            if (kind === 'select') { delete props.positive; delete props.negative; }
            else props.itemsSelectedIndex = selected;
        }
        var dialog = build(props);
        dialog.once('input', function (value) {
            try { finish(kind === 'input' ? (0, eval)(value) : value); }
            catch (error) { failure = error; settled = true; }
        });
        dialog.once('item_select', function (index) { finish(index); });
        dialog.once('single_choice', function (index) { finish(index); });
        dialog.once('multi_choice', function (indices) { finish(indices); });
        dialog.once('positive', function () { if (kind === 'confirm') finish(true); else if (kind === 'alert') finish(undefined); });
        dialog.once('negative', function () { finish(fallback); });
        dialog.once('cancel', function () { finish(fallback); });
        dialog.once('dismiss', function () { finish(fallback); });
        var names = ['input', 'item_select', 'single_choice', 'multi_choice', 'positive', 'negative', 'cancel', 'dismiss'];
        if (typeof callback === 'function') {
            var notified = false;
            function notify() {
                if (!settled || notified) return;
                notified = true;
                if (failure) throw failure;
                callback(result);
            }
            names.forEach(function (name) { dialog.on(name, notify); });
            dialog.show();
            return undefined;
        }
        if (__aiNativeIsMainThread()) {
            return new Promise(function (resolve, reject) {
                var notified = false;
                function notify() { if (settled && !notified) { notified = true; if (failure) reject(failure); else resolve(result); } }
                names.forEach(function (name) { dialog.on(name, notify); });
                dialog.show();
            });
        }
        dialog.show();
        while (!settled) { dialog.__poll(); if (!settled) sleep(40); }
        if (failure) throw failure;
        return result;
    }
    var dialogs = {
        alert: function (title, content, callback) {
            if (typeof content === 'function') { callback = content; content = ''; }
            return simple('alert', title, content, null, null, callback);
        },
        confirm: function (title, content, callback) {
            if (typeof content === 'function') { callback = content; content = ''; }
            return simple('confirm', title, content, null, null, callback);
        },
        rawInput: function (title, prefill, callback) {
            if (typeof prefill === 'function') { callback = prefill; prefill = ''; }
            return simple('rawInput', title, prefill, null, null, callback);
        },
        input: function (title, prefill, callback) {
            if (typeof prefill === 'function') { callback = prefill; prefill = ''; }
            return simple('input', title, prefill, null, null, callback);
        },
        select: function (title, items, callback) {
            if (!Array.isArray(items)) { items = Array.prototype.slice.call(arguments, 1); callback = undefined; }
            return simple('select', title, '', items, null, callback);
        },
        singleChoice: function (title, items, index, callback) {
            if (typeof index === 'function') { callback = index; index = 0; }
            return simple('singleChoice', title, '', items || [], index === undefined ? 0 : Number(index), callback);
        },
        multiChoice: function (title, items, indices, callback) {
            if (typeof indices === 'function') { callback = indices; indices = []; }
            return simple('multiChoice', title, '', items || [], indices || [], callback);
        },
        build: build,
        setDefaultDialogType: function (type) { nativeCall(0, 'setDefaultDialogType', [String(type)]); },
        getDefaultDialogType: function () { return nativeCall(0, 'getDefaultDialogType'); }
    };
    dialogs.prompt = dialogs.rawInput;
    global.dialogs = Object.freeze(dialogs);
    global.$dialogs = global.dialogs;
})(global);
