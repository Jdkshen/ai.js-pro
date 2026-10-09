// Built-in extensions using the engine's existing Java interop boundary.
(function(g) {
    'use strict';
    let api;
    let shared = false;
    const originalImages = g.images;
    function host() {
        if (!api) api = g.Java.type('com.stardust.autojs.engine.QuickJsApiExtensions').forEngine(Number(g.engines.myEngine().id));
        return api;
    }
    function integer(value, name) {
        if (typeof value !== 'number' || !Number.isInteger(value) || value < 0 || value > 2147483647) {
            throw new TypeError(name + ' 必须是非负整数');
        }
        return value;
    }
    const share = function() {
        const token = String(host().getImages().shareScreenCapture());
        shared = true;
        return token;
    };
    const use = function(token) {
        if (typeof token !== 'string' || !token) throw new TypeError('需要有效的共享截图凭据');
        const connected = host().getImages().useSharedScreenCapture(token);
        if (connected) shared = true;
        return connected;
    };
    // The native images object is frozen. Extend its public wrapper, preserving all methods.
    g.images = g.$images = Object.freeze(Object.assign({}, originalImages, {
        shareScreenCapture: share, useSharedScreenCapture: use,
        captureScreen: function(options) {
            // A child has no local permission request to set the native accessibility timeout.
            if (shared) {
                if (options === undefined) options = {timeout: 2000};
                else if (typeof options === 'object' && options) options = Object.assign({timeout: 2000}, options);
                else if (typeof options === 'number') options = {mode: 'fast', size: options, timeout: 2000};
                else if (typeof options === 'string') options = {mode: options.toLowerCase() === 'fast' ? 'fast' : 'full', timeout: 2000};
            }
            return originalImages.captureScreen(options);
        },
        stopScreenCapture: function() {
            originalImages.stopScreenCapture();
            shared = false;
        }
    }));
    g.captureScreen = g.images.captureScreen;
    if (g.__aiModernModules && g.__aiModernModules.images) {
        g.__aiModernModules.images.shareScreenCapture = share;
        g.__aiModernModules.images.useSharedScreenCapture = use;
    }
    g.touch = g.$touch = {
        create: function(options) {
            options = options || {};
            const backend = options.backend === undefined ? 'root' : String(options.backend);
            if (backend !== 'root' && backend !== 'adb' && backend !== 'shizuku') throw new Error('未知触控通道: ' + backend);
            const session = host().createTouch(backend !== 'root');
            return {
                down: function(id, x, y) { session.down(integer(id, 'id'), integer(x, 'x'), integer(y, 'y')); },
                move: function(id, x, y) { session.move(integer(id, 'id'), integer(x, 'x'), integer(y, 'y')); },
                up: function(id) { session.up(integer(id, 'id')); },
                pointerCount: function() { return session.pointerCount(); },
                isClosed: function() { return session.isClosed(); },
                isAdb: function() { return session.isAdb(); },
                exit: function() { session.exit(); }
            };
        }
    };
})(globalThis);
