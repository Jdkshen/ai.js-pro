// Pro modern module adapters for the QuickJS host. Coverage is exported explicitly.
(function (g) {
    'use strict';
    const modules = Object.create(null);
    const coverage = Object.create(null);
    function register(name, api, limitations) {
        modules[name] = api;
        coverage[name] = { exports: Object.keys(api), limitations: limitations || [] };
    }
    function asyncCall(fn) { return function () { const args = arguments; return Promise.resolve().then(() => fn.apply(null, args)); }; }
    class EventEmitter {
        constructor() { this._events = new Map(); }
        on(name, listener) {
            if (typeof listener !== 'function') throw new TypeError('Listener must be a function');
            const list = this._events.get(name) || [];
            list.push(listener); this._events.set(name, list);
            if (name !== 'newListener') this.emit('newListener', name, listener);
            return this;
        }
        addListener(name, listener) { return this.on(name, listener); }
        once(name, listener) {
            const self = this;
            function once() { self.removeListener(name, once); return listener.apply(self, arguments); }
            once.listener = listener; return this.on(name, once);
        }
        emit(name) {
            const args = Array.prototype.slice.call(arguments, 1);
            const list = this._events.get(name);
            if (!list || !list.length) {
                if (name === 'error' && args[0] instanceof Error) throw args[0];
                return false;
            }
            list.slice().forEach(listener => listener.apply(this, args)); return true;
        }
        removeListener(name, listener) {
            const list = this._events.get(name);
            if (!list) return this;
            for (let i = list.length - 1; i >= 0; i--) {
                if (list[i] === listener || list[i].listener === listener) {
                    const original = list.splice(i, 1)[0];
                    if (!list.length) this._events.delete(name);
                    if (name !== 'removeListener') this.emit('removeListener', name, original.listener || original);
                    break;
                }
            }
            return this;
        }
        off(name, listener) { return this.removeListener(name, listener); }
        removeAllListeners(name) {
            if (arguments.length) {
                const list = this._events.get(name);
                if (list) list.slice().reverse().forEach(listener => this.removeListener(name, listener));
            } else {
                this.eventNames().filter(n => n !== 'removeListener').forEach(n => this.removeAllListeners(n));
                this._events.delete('removeListener');
            }
            return this;
        }
        listenerCount(name) { return (this._events.get(name) || []).length; }
        listeners(name) { return (this._events.get(name) || []).map(listener => listener.listener || listener); }
        eventNames() { return Array.from(this._events.keys()); }
    }
    EventEmitter.EventEmitter = EventEmitter;
    register('events', EventEmitter);
    const b64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    function encodingName(encoding) { return String(encoding || 'utf8').toLowerCase().replace(/[-_]/g, ''); }
    function encodeBytes(text, encoding) {
        const enc = encodingName(encoding);
        if (enc === 'hex') {
            const out = [];
            for (let i = 0; i + 1 < text.length && /^[0-9a-f]{2}$/i.test(text.slice(i, i + 2)); i += 2) out.push(parseInt(text.slice(i, i + 2), 16));
            return out;
        }
        if (enc === 'base64' || enc === 'base64url') {
            const clean = text.replace(/-/g, '+').replace(/_/g, '/').replace(/[^A-Za-z0-9+/]/g, '');
            const out = []; let value = 0, bits = 0;
            for (const ch of clean) { value = (value << 6) | b64.indexOf(ch); bits += 6; if (bits >= 8) { bits -= 8; out.push((value >>> bits) & 255); } }
            return out;
        }
        const out = [];
        if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') { for (let i = 0; i < text.length; i++) out.push(text.charCodeAt(i) & 255); return out; }
        if (enc === 'utf16le' || enc === 'ucs2') { for (let i = 0; i < text.length; i++) { const n = text.charCodeAt(i); out.push(n & 255, n >>> 8); } return out; }
        if (enc !== 'utf8') throw new TypeError('Unsupported buffer encoding: ' + encoding);
        for (const ch of text) {
            let n = ch.codePointAt(0); if (n >= 0xd800 && n <= 0xdfff) n = 0xfffd;
            if (n < 128) out.push(n);
            else if (n < 2048) out.push(0xc0 | n >>> 6, 0x80 | n & 63);
            else if (n < 65536) out.push(0xe0 | n >>> 12, 0x80 | n >>> 6 & 63, 0x80 | n & 63);
            else out.push(0xf0 | n >>> 18, 0x80 | n >>> 12 & 63, 0x80 | n >>> 6 & 63, 0x80 | n & 63);
        }
        return out;
    }
    class ByteBuffer extends Uint8Array {
        static from(value, encoding, length) {
            if (typeof value === 'string') return new ByteBuffer(encodeBytes(value, encoding));
            if (value instanceof ArrayBuffer) return new ByteBuffer(value, encoding || 0, length);
            if (ArrayBuffer.isView(value)) return new ByteBuffer(value);
            return new ByteBuffer(value);
        }
        static alloc(size, fill, encoding) { const result = new ByteBuffer(size); if (fill !== undefined) { const data = typeof fill === 'string' ? ByteBuffer.from(fill, encoding) : null; for (let i = 0; i < size; i++) result[i] = data ? (data.length ? data[i % data.length] : 0) : Number(fill); } return result; }
        static isBuffer(value) { return value instanceof ByteBuffer; }
        static concat(list, length) { const total = length === undefined ? list.reduce((n, bytes) => n + bytes.length, 0) : length; const out = ByteBuffer.alloc(total); let pos = 0; for (const bytes of list) { out.set(bytes.subarray(0, total - pos), pos); pos += Math.min(bytes.length, total - pos); if (pos >= total) break; } return out; }
        static byteLength(value, encoding) { return typeof value === 'string' ? encodeBytes(value, encoding).length : value.byteLength; }
        toString(encoding, start, end) {
            const bytes = this.subarray(start || 0, end === undefined ? this.length : end), enc = encodingName(encoding);
            if (enc === 'hex') return Array.from(bytes, n => n.toString(16).padStart(2, '0')).join('');
            if (enc === 'base64' || enc === 'base64url') {
                let out = ''; for (let i = 0; i < bytes.length; i += 3) {
                    const a = bytes[i], b = bytes[i + 1] || 0, c = bytes[i + 2] || 0;
                    out += b64[a >>> 2] + b64[((a & 3) << 4) | b >>> 4] + (i + 1 < bytes.length ? b64[((b & 15) << 2) | c >>> 6] : '=') + (i + 2 < bytes.length ? b64[c & 63] : '=');
                }
                return enc === 'base64url' ? out.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') : out;
            }
            if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') return Array.from(bytes, n => String.fromCharCode(enc === 'ascii' ? n & 127 : n)).join('');
            if (enc === 'ucs2' || enc === 'utf16le') { let out = ''; for (let i = 0; i + 1 < bytes.length; i += 2) out += String.fromCharCode(bytes[i] | bytes[i + 1] << 8); return out; }
            if (enc !== 'utf8') throw new TypeError('Unsupported buffer encoding: ' + encoding);
            // Percent decoding validates UTF-8; malformed sequences use replacement characters.
            let out = '';
            for (let i = 0; i < bytes.length;) {
                const a = bytes[i]; let size = a < 128 ? 1 : a >= 0xc2 && a <= 0xdf ? 2 : a >= 0xe0 && a <= 0xef ? 3 : a >= 0xf0 && a <= 0xf4 ? 4 : 0;
                let code = size ? a & (size === 1 ? 127 : (1 << (7 - size)) - 1) : 0;
                let valid = size > 0 && i + size <= bytes.length;
                for (let j = 1; valid && j < size; j++) { const b = bytes[i + j]; if ((b & 0xc0) !== 0x80) valid = false; else code = code << 6 | b & 63; }
                if (valid && ((size === 2 && code < 128) || (size === 3 && code < 2048) || (size === 4 && code < 65536) || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff))) valid = false;
                out += valid ? String.fromCodePoint(code) : '\ufffd'; i += valid ? size : 1;
            }
            return out;
        }
        equals(other) { return this.length === other.length && this.every((value, i) => value === other[i]); }
        slice(start, end) { return this.subarray(start, end); }
        toJSON() { return { type: 'Buffer', data: Array.from(this) }; }
    }
    const BufferClass = g.Buffer || ByteBuffer;
    if (!g.Buffer) g.Buffer = BufferClass;
    register('buffer', { Buffer: BufferClass }, ['Only the encoding and byte-array operations used by image APIs are provided; this is not the full Node Buffer API.']);
    class Color {
        constructor(value) { this._value = Number(value) | 0; }
        get value() { return this._value; }
        get alpha() { return this.value >>> 24; } get opacity() { return this.alpha / 255; }
        get red() { return this.value >>> 16 & 255; } get green() { return this.value >>> 8 & 255; } get blue() { return this.value & 255; }
        withAlpha(n) { return Color.fromARGB(n, this.red, this.green, this.blue); }
        withOpacity(n) { return this.withAlpha(Math.round(n * 255)); }
        withRed(n) { return Color.fromARGB(this.alpha, n, this.green, this.blue); }
        withGreen(n) { return Color.fromARGB(this.alpha, this.red, n, this.blue); }
        withBlue(n) { return Color.fromARGB(this.alpha, this.red, this.green, n); }
        equals(other) { return other instanceof Color && other.value === this.value; }
        isSimilarTo(other, options) {
            options = options || {}; const threshold = options.threshold === undefined ? 16 : options.threshold;
            return Math.abs(this.red - other.red) < threshold && Math.abs(this.green - other.green) < threshold && Math.abs(this.blue - other.blue) < threshold && (options.ignoreAlphaChannel !== false || Math.abs(this.alpha - other.alpha) < threshold);
        }
        toString() { return 'Color(0x' + (this.value >>> 0).toString(16).padStart(8, '0') + ')'; }
        static fromARGB(a, r, b, c) { return new Color((a & 255) << 24 | (r & 255) << 16 | (b & 255) << 8 | c & 255); }
        static fromRGB(r, b, c) { return Color.fromARGB(255, r, b, c); }
        static fromGray(n) { return Color.fromRGB(n, n, n); }
        static fromRGBO(r, b, c, opacity) { return Color.fromARGB(Math.round(opacity * 255), r, b, c); }
        static parse(value) {
            if (!value.startsWith('#')) return null;
            const hex = value.substring(1);
            if (hex.length === 6) return new Color(0xff000000 | parseInt(hex, 16));
            if (hex.length === 8) return new Color(parseInt(hex, 16));
            return null;
        }
    }
    register('color', { Color: Color, toColorInt: n => Number(n) | 0 }, ['HSVColor and HSLColor class APIs are not yet implemented.']);
    function keepRunning() { return g.setInterval(function () {}, 1000); }
    function release(id) { if (id !== undefined) g.clearInterval(id); }
    class Deferred {
        constructor(keepAlive) { this.finished = false; this._value = undefined; if (keepAlive !== false) this._keep = keepRunning(); this._promise = new Promise((resolve, reject) => { this._resolve = resolve; this._reject = reject; }); }
        resolve(value) { if (this.finished) return false; this.finished = true; this._value = value; release(this._keep); this._resolve(value); return true; }
        reject(error) { if (this.finished) return false; this.finished = true; release(this._keep); this._reject(error); return true; }
        promise() { return this._promise; } get value() { return this._value; }
    }
    class TimeoutError extends Error {}
    const lang = {
        lazy: function (target, name, descriptor) {
            descriptor = descriptor || Object.getOwnPropertyDescriptor(target, name) || {};
            if (!descriptor.get || descriptor.set) throw new TypeError('lazy requires a getter without a setter');
            const initializer = descriptor.get;
            function set(value) { Object.defineProperty(this, name, { value: value, enumerable: !!descriptor.enumerable, configurable: !!descriptor.configurable }); return value; }
            return { get: function () { return this === target ? initializer : set.call(this, initializer.call(this)); }, set: set };
        },
        lazyProp: function (evaluator) { let ready = false, value; return { get: function () { if (!ready) { value = evaluator(); ready = true; } return value; } }; },
        promise: function (executor) { const id = keepRunning(); return new Promise(executor).finally(() => release(id)); },
        delay: function (timeout) { return Number(timeout) <= 0 ? Promise.resolve() : new Promise(resolve => g.setTimeout(resolve, timeout)); },
        Deferred: Deferred, TimeoutError: TimeoutError
    };
    register('lang', lang);
    const imageState = new WeakMap();
    const imageMats = new WeakMap();
    function frameOf(image) { const frame = imageState.get(image); if (!frame || frame.recycled) throw new Error('Image has been recycled'); return frame; }
    function interpolation(value) { return typeof value === 'number' ? ['NEAREST', 'LINEAR', 'CUBIC', 'AREA', 'LANCZOS4'][value] || String(value) : value; }
    function rect(value) { return Array.isArray(value) ? value : [value.x, value.y, value.width, value.height]; }
    class Image {
        constructor(mat) {
            const frame = mat instanceof g.NativeFrame ? mat : g.images.matToImage(mat);
            if (!frame) throw new Error('Could not decode image'); imageState.set(this, frame);
            if (!(mat instanceof g.NativeFrame)) imageMats.set(this, mat);
        }
        get width() { return frameOf(this).width; } get height() { return frameOf(this).height; }
        get mat() {
            const frame = frameOf(this);
            if (!imageMats.has(this)) imageMats.set(this, g.images.toMat(frame));
            return imageMats.get(this);
        }
        pixel(x, y) { return new Color(g.images.pixel(frameOf(this), x, y)); }
        copySync() { return new Image(g.images.copy(frameOf(this))); } copy() { return Promise.resolve().then(() => this.copySync()); }
        clipSync(region) { return new Image(g.images.clip.apply(g.images, [frameOf(this)].concat(rect(region)))); }
        clip(region) { return Promise.resolve().then(() => this.clipSync(region)); }
        resizeSync(width, height, method) { return new Image(g.images.resize(frameOf(this), [width, height], interpolation(method))); }
        resize(width, height, method) { return Promise.resolve().then(() => this.resizeSync(width, height, method)); }
        scaleSync(fx, fy, method) { return new Image(g.images.scale(frameOf(this), fx, fy === undefined ? fx : fy, interpolation(method))); }
        scale(fx, fy, method) { return Promise.resolve().then(() => this.scaleSync(fx, fy, method)); }
        grayscaleSync() { return new Image(g.images.grayscale(frameOf(this))); } grayscale() { return Promise.resolve().then(() => this.grayscaleSync()); }
        rotateSync(degree, center) { if (center !== undefined) throw new TypeError('Custom rotation center is not supported by this host'); return new Image(g.images.rotate(frameOf(this), degree)); }
        rotate(degree, center) { return Promise.resolve().then(() => this.rotateSync(degree, center)); }
        recycle() {
            const frame = imageState.get(this);
            if (!frame) return;
            const mat = imageMats.get(this);
            imageState.delete(this); imageMats.delete(this);
            try { if (mat) mat.release(); } finally { frame.recycle(); }
        }
    }
    function colorInt(value) { return value instanceof Color ? value.value : value; }
    function options(value) { const result = Object.assign({}, value || {}); if (result.region) result.region = rect(result.region); return result; }
    function byteEncoding(image, encoding, format, quality) { return BufferClass.from(g.images.toBytes(frameOf(image), format || 'png', quality === undefined ? 100 : quality)).toString(encoding || 'base64'); }
    const image = {
        Image: Image, MAX_LEVEL_AUTO: -1,
        readImageSync: file => new Image(g.images.read(file)),
        loadImage: asyncCall(url => new Image(g.images.load(url))),
        writeImageSync: (img, file, quality) => { if (!g.images.save(frameOf(img), file, String(file).split('.').pop().toLowerCase(), quality === undefined ? 100 : quality)) throw new Error('Could not write image: ' + file); },
        decodeImageSync: (text, encoding) => new Image(g.images.fromBytes(BufferClass.from(text, encoding || 'base64'))),
        encodeImageSync: byteEncoding,
        encodeImageToBufferSync: (img, format, quality) => BufferClass.from(g.images.toBytes(frameOf(img), format || 'png', quality === undefined ? 100 : quality)),
        decodeImageFromBufferSync: bytes => new Image(g.images.fromBytes(bytes)),
        findImageSync: (src, template, opt) => g.images.findImage(frameOf(src), frameOf(template), options(opt)),
        findImageInRegionSync: (src, template, x, y, width, height, threshold) => g.images.findImageInRegion(frameOf(src), frameOf(template), x, y, width, height, threshold),
        findColorSync: (src, color, opt) => g.images.findColor(frameOf(src), colorInt(color), options(opt)),
        detectsColor: (src, color, x, y, opt) => src.pixel(x, y).isSimilarTo(color instanceof Color ? color : typeof color === 'number' ? new Color(color) : Color.parse(color), opt),
        findMultiColorsSync: (src, path, opt) => g.images.findMultiColors(frameOf(src), colorInt(path.firstColor), path.offsetColors.map(item => [item.offsetX, item.offsetY, colorInt(item.color)]), options(opt)),
        detectsMultiColors: (src, path, x, y, opt) => image.detectsColor(src, path.firstColor, x, y, opt) && path.offsetColors.every(item => image.detectsColor(src, item.color, x + item.offsetX, y + item.offsetY, opt)),
        matchTemplateSync: (src, template, opt) => g.images.matchTemplate(frameOf(src), frameOf(template), options(opt)).matches
    };
    ['readImage','writeImage','decodeImage','encodeImage','encodeImageToBuffer','decodeImageFromBuffer','findImage','findImageInRegion','findColor','findMultiColors','matchTemplate'].forEach(name => { image[name] = asyncCall(image[name + 'Sync']); });
    register('image', image, ['Feature descriptors/matchFeatures, Bitmap conversion, c4mat and custom rotation centers require additional OpenCV/Java adapters.']);
    class ScreenCaptureRequestError extends Error { constructor(message) { super(message); this.name = 'ScreenCaptureRequestError'; } }
    class ImageUnavailableError extends Error { constructor(message) { super(message); this.name = 'ImageUnavailableError'; } }
    let activeCapturer = null;
    let captureRequestQueue = Promise.resolve();
    class ScreenCapturer extends EventEmitter {
        constructor() {
            super(); this._stopped = false; this._image = null; this._pendingFrame = null; this._waiting = new Set();
            this._listener = frame => {
                if (this._stopped) { frame.recycle(); return; }
                if (this._pendingFrame && this._pendingFrame !== frame) this._pendingFrame.recycle();
                this._pendingFrame = frame; this.emit('image_available');
            };
            g.images.on('screen_capture', this._listener);
        }
        latestImageOrNull() {
            if (this._stopped) return null;
            if (this._pendingFrame) {
                const next = new Image(this._pendingFrame);
                this._pendingFrame = null;
                if (this._image) this._image.recycle();
                this._image = next;
            }
            return this._image;
        }
        latestImage() { const image = this.latestImageOrNull(); if (!image) throw new ImageUnavailableError('No image is available'); return image; }
        nextImage() {
            if (this._stopped) return Promise.reject(new ImageUnavailableError('Screen capturing has stopped'));
            if (this._pendingFrame) return Promise.resolve(this.latestImage());
            return new Promise((resolve, reject) => {
                const wait = { reject: reject, listener: () => { this._waiting.delete(wait); resolve(this.latestImage()); } };
                this._waiting.add(wait); this.once('image_available', wait.listener);
            });
        }
        awaitForImageAvailable() { return this._image || this._pendingFrame ? Promise.resolve() : this.nextImage().then(() => undefined); }
        stop() {
            if (this._stopped) return; this._stopped = true;
            const ownedSession = activeCapturer === this;
            if (ownedSession) activeCapturer = null;
            g.images.removeListener('screen_capture', this._listener);
            if (ownedSession) g.images.stopScreenCapture();
            if (this._pendingFrame) this._pendingFrame.recycle(); this._pendingFrame = null;
            if (this._image) this._image.recycle(); this._image = null;
            this._waiting.forEach(wait => wait.reject(new ImageUnavailableError('Screen capturing has stopped'))); this._waiting.clear(); this.removeAllListeners();
        }
    }
    register('media_projection', {
        ScreenCapturer: ScreenCapturer, ScreenCaptureRequestError: ScreenCaptureRequestError, ImageUnavailableError: ImageUnavailableError,
        Orientation: {CURRENT:0,AUTO:1,LANDSCAPE:2,PORTRAIT:3},
        requestScreenCapture: asyncCall(opt => {
            opt = Object.assign({}, opt || {});
            const mapping = [0, -1, 1, 2], orientation = opt.orientation === undefined ? 1 : Number(opt.orientation);
            if (!Number.isInteger(orientation) || orientation < 0 || orientation > 3) throw new RangeError('Invalid screen orientation');
            opt.orientation = mapping[orientation]; opt.async = true;
            const pending = captureRequestQueue.then(() => {
                if (activeCapturer) activeCapturer.stop();
                const request = typeof g.images.requestScreenCaptureAsync === 'function'
                    ? g.images.requestScreenCaptureAsync(opt) : g.images.requestScreenCapture(opt);
                return Promise.resolve(request).then(granted => {
                    if (!granted) throw new ScreenCaptureRequestError('Screen capture request was denied');
                    activeCapturer = new ScreenCapturer(); return activeCapturer;
                });
            });
            captureRequestQueue = pending.catch(() => undefined);
            return lang.promise((resolve, reject) => pending.then(resolve, reject));
        })
    }, ['One active screen capture session is supported per engine.']);
    function dialogProperties(properties) {
        const result = Object.assign({}, properties || {});
        ['titleColor', 'contentColor', 'itemsColor', 'positiveColor', 'negativeColor', 'neutralColor', 'buttonRippleColor'].forEach(key => {
            if (result[key] instanceof Color) result[key] = result[key].value;
        });
        return result;
    }
    function showResult(properties, event, fallback, adapt) {
        return lang.promise((resolve, reject) => {
            let dialog, closed = false;
            function close() {
                if (closed) return; closed = true;
                if (dialog && typeof dialog.close === 'function') dialog.close();
            }
            try {
                dialog = g.dialogs.build(dialogProperties(properties));
                dialog.once(event, function () { resolve(adapt ? adapt.apply(null, arguments) : arguments[0]); });
                dialog.once('dismiss', () => { resolve(fallback); close(); });
                dialog.show();
            } catch (error) {
                reject(error);
                try { close(); } catch (_) {}
            }
        });
    }
    const dialogs = {
        setDefaultDialogType: type => g.dialogs.setDefaultDialogType(type),
        showConfirmDialog: (title, props) => showResult(Object.assign({title:title, positive:'OK', negative:'Cancel'}, props || {}), 'positive', false, () => true),
        showAlertDialog: (title, props) => showResult(Object.assign({title:title, positive:'OK'}, props || {}), 'dismiss', undefined, () => undefined),
        showInputDialog: (title, prefill, props) => showResult(Object.assign({title:title, positive:'OK', inputPrefill:prefill === undefined ? '' : prefill}, props || {}), 'input', null),
        showSelectDialog: (title, items, props) => showResult(Object.assign({title:title, positive:'OK', items:items}, props || {}), 'item_select', -1),
        showSingleChoiceDialog: (title, items, index, props) => showResult(Object.assign({title:title, positive:'OK', items:items, itemsSelectedIndex:index, itemsSelectMode:'single'}, props || {}), 'single_choice', -1),
        showMultiChoiceDialog: (title, items, indices, props) => showResult(Object.assign({title:title, positive:'OK', items:items, itemsSelectedIndex:indices, itemsSelectMode:'multi'}, props || {}), 'multi_choice', null),
        buildDialog: props => Promise.resolve().then(() => g.dialogs.build(dialogProperties(props))),
        showDialog: props => Promise.resolve().then(() => { const dialog = g.dialogs.build(dialogProperties(props)); return Promise.resolve(dialog.show()).then(() => dialog); })
    };
    register('dialogs', dialogs);
    // Small modern modules with the same observable host contract.
    register('clip_manager', {
        getClip: () => g.context.getSystemService('clipboard').hasPrimaryClip() ? g.getClip() : null, setClip: text => g.setClip(text),
        hasClip: () => Boolean(g.context.getSystemService('clipboard').hasPrimaryClip()),
        clearClip: () => { const manager = g.context.getSystemService('clipboard'); if (Number(g.device.sdkInt) >= 28) manager.clearPrimaryClip(); else g.setClip(''); }
    }, ['Clipboard listeners and rich ClipData are not provided.']);
    register('toast', { showToast: function (message, options) {
        if (!options || options === 'short') return g.toast(String(message));
        const duration = options === 'long' || options.duration === 'long' ? 1 : 0;
        return g.Java.type('android.widget.Toast').makeText(g.context, String(message), duration).show();
    } }, ['Custom toast layout options require Android UI adapters.']);
    register('java_utils', {
        javaListToArray: list => { if (list === null || list === undefined) return null; if (Array.isArray(list)) return list.slice(); const out = []; for (let i = 0; i < Number(list.size()); i++) out.push(list.get(i)); return out; },
        javaSetToArray: set => { if (set === null || set === undefined) return null; const result = [], it = set.iterator(); while (it.hasNext()) result.push(it.next()); return result; },
        javaMapToObject: (object, map, transform) => { if (map === null || map === undefined) return null; const it = map.entrySet().iterator(); while (it.hasNext()) { const entry = it.next(); object[String(entry.getKey())] = transform ? transform(entry.getValue()) : entry.getValue(); } return object; },
        objectToJavaMap: (object, transform) => { const map = new (g.Java.type('java.util.HashMap'))(); Object.keys(object).forEach(key => map.put(key, transform ? transform(object[key]) : object[key])); return map; },
        bufferToByteArray: buffer => g.Java.array('byte', Array.from(buffer, value => value > 127 ? value - 256 : value)),
        toStringOr: (value, fallback) => value === null || value === undefined ? fallback : String(value),
        toStringOrEmpty: value => value === null || value === undefined ? '' : String(value)
    }, ['Java class lookup uses the existing Java.type host API.']);
    g.__aiModernModules = modules;
    g.__aiModernModuleCoverage = coverage;
})(globalThis);

