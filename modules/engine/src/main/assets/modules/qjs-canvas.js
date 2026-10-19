// Canvas callbacks run in QuickJS's event loop. Completed bitmap frames are rendered separately.
(function(g) {
    'use strict';
    const states = new Map();
    const wrappedWindows = new WeakMap();
    let api;
    function host() {
        if (!api) api = g.Java.type('com.stardust.autojs.engine.QuickJsApiExtensions')
            .forEngine(Number(g.engines.myEngine().id));
        return api;
    }
    function resolve(state) {
        if (!state.native) {
            state.native = host().findCanvasView(state.windowId, state.id);
            state.canvas = state.native.getQuickJsCanvas();
        }
        return state.native;
    }
    function cancel(state) {
        if (state.timer !== null) clearTimeout(state.timer);
        state.timer = null;
    }
    function dispose(state) {
        cancel(state);
        state.listeners.length = 0;
        states.delete(state.key);
    }
    function schedule(state, delay) {
        if (!state.listeners.length || state.timer !== null) return;
        state.timer = setTimeout(function() { tick(state); }, Math.max(1, delay));
    }
    function tick(state) {
        state.timer = null;
        const native = resolve(state);
        if (native.isQuickJsCanvasClosed()) { dispose(state); return; }
        const start = Date.now();
        if (state.listeners.length && native.beginQuickJsFrame()) {
            let success = false;
            try {
                for (const entry of state.listeners.slice()) {
                    if (entry.once) {
                        const index = state.listeners.indexOf(entry);
                        if (index >= 0) state.listeners.splice(index, 1);
                    }
                    entry.fn.call(state.proxy, state.canvas, state.proxy);
                }
                success = true;
            } catch (error) {
                dispose(state);
                throw error;
            } finally {
                native.endQuickJsFrame(success);
            }
        }
        if (state.listeners.length) schedule(state, Number(native.getDrawIntervalMillis()) - (Date.now() - start));
    }
    function wrapView(base, windowId, id) {
        const key = windowId + ':' + id;
        const previous = states.get(key);
        if (previous) return previous.proxy;
        const state = {key: key, windowId: windowId, id: id, native: null, canvas: null,
            listeners: [], timer: null, proxy: null};
        function add(fn, once, prepend) {
            if (typeof fn !== 'function') throw new TypeError('draw listener must be a function');
            const native = resolve(state);
            if (native.isQuickJsCanvasClosed()) throw new Error('Canvas 已关闭：' + id);
            const entry = {fn: fn, once: once};
            if (prepend) state.listeners.unshift(entry); else state.listeners.push(entry);
            states.set(key, state);
            schedule(state, Number(native.getDrawIntervalMillis()));
            return state.proxy;
        }
        function remove(fn) {
            for (let i = state.listeners.length - 1; i >= 0; i--) {
                if (state.listeners[i].fn === fn) { state.listeners.splice(i, 1); break; }
            }
            if (!state.listeners.length) cancel(state);
            return state.proxy;
        }
        const methods = {
            on: function(event, fn) { return String(event) === 'draw' ? add(fn, false, false) : fallback('on', arguments); },
            addListener: function(event, fn) { return String(event) === 'draw' ? add(fn, false, false) : fallback('on', arguments); },
            once: function(event, fn) { return String(event) === 'draw' ? add(fn, true, false) : fallback('once', arguments); },
            prependListener: function(event, fn) { return String(event) === 'draw' ? add(fn, false, true) : fallback('prependListener', arguments); },
            prependOnceListener: function(event, fn) { return String(event) === 'draw' ? add(fn, true, true) : fallback('prependOnceListener', arguments); },
            removeListener: function(event, fn) { return String(event) === 'draw' ? remove(fn) : fallback('removeListener', arguments); },
            off: function(event, fn) { return methods.removeListener(event, fn); },
            removeAllListeners: function(event) {
                if (event === undefined || String(event) === 'draw') { state.listeners.length = 0; cancel(state); return state.proxy; }
                return fallback('removeAllListeners', arguments);
            },
            listenerCount: function(event) { return String(event) === 'draw' ? state.listeners.length : fallback('listenerCount', arguments); },
            listeners: function(event) { return String(event) === 'draw' ? state.listeners.map(function(e) { return e.fn; }) : fallback('listeners', arguments); },
            setMaxFps: function(fps) {
                if (typeof fps !== 'number' || !Number.isFinite(fps) || !Number.isInteger(fps)) throw new TypeError('maxFps 必须是整数');
                resolve(state).setMaxFps(fps);
                cancel(state);
                schedule(state, Number(state.native.getDrawIntervalMillis()));
                return state.proxy;
            }
        };
        function fallback(name, args) {
            const fn = base[name];
            if (typeof fn !== 'function') throw new Error('不支持的控件事件方法：' + name);
            const result = fn.apply(base, args);
            return result === base ? state.proxy : result;
        }
        const methodCache = new Map();
        state.proxy = new Proxy(base, {
            get: function(target, prop) {
                if (Object.prototype.hasOwnProperty.call(methods, prop)) return methods[prop];
                const value = target[prop];
                if (typeof value !== 'function') return value;
                if (!methodCache.has(prop)) methodCache.set(prop, function() { return fallback(prop, arguments); });
                return methodCache.get(prop);
            },
            set: function(target, prop, value) { target[prop] = value; return true; }
        });
        states.set(key, state);
        return state.proxy;
    }
    function stopWindow(windowId) {
        for (const state of Array.from(states.values())) {
            if (state.windowId === windowId) dispose(state);
        }
    }
    function wrapWindow(base) {
        if (wrappedWindows.has(base)) return wrappedWindows.get(base);
        const windowId = Number(base.id);
        const methodCache = new Map();
        let proxy;
        proxy = new Proxy(base, {
            get: function(target, prop) {
                const value = target[prop];
                if (value && typeof value === 'object' && value.__floatyView === true) return wrapView(value, windowId, String(prop));
                if (typeof value !== 'function') return value;
                if (!methodCache.has(prop)) methodCache.set(prop, function() {
                    if (prop === 'close') stopWindow(windowId);
                    const result = value.apply(target, arguments);
                    if (result === target) return proxy;
                    if (result && result.__floatyView === true) return wrapView(result, windowId, String(arguments[0]));
                    return result;
                });
                return methodCache.get(prop);
            },
            set: function(target, prop, value) { target[prop] = value; return true; }
        });
        wrappedWindows.set(base, proxy);
        return proxy;
    }
    const originalFloaty = g.floaty;
    // task-204：不要 freeze floaty —— 第三方 Node 后端（xiangqi headless-window）会覆盖
  // global.floaty.window / rawWindow 做逻辑窗口；与 Rhino 语义保持一致。
  g.floaty = g.$floaty = Object.assign({}, originalFloaty, {
        window: function() { return wrapWindow(originalFloaty.window.apply(originalFloaty, arguments)); },
        rawWindow: function() { return wrapWindow(originalFloaty.rawWindow.apply(originalFloaty, arguments)); },
        windowById: function(id) { return wrapWindow(originalFloaty.windowById(id)); },
        getWindow: function(id) { return wrapWindow(originalFloaty.getWindow(id)); },
        closeAll: function() {
            for (const state of Array.from(states.values())) if (state.windowId !== 0) dispose(state);
            return originalFloaty.closeAll();
        }
    });
    const originalUi = g.ui;
    if (originalUi) {
        g.ui = g.$ui = new Proxy(originalUi, {
            get: function(target, prop) {
                const value = target[prop];
                if (value && typeof value === 'object' && typeof value.__id === 'string') return wrapView(value, 0, value.__id);
                if (prop === 'layout' && typeof value === 'function') return function() {
                    stopWindow(0);
                    return value.apply(target, arguments);
                };
                return value;
            },
            set: function(target, prop, value) { target[prop] = value; return true; }
        });
    }
})(globalThis);
