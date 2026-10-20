// Node24 Pro-global shim for the chess (QuickJS) modules.
// Runs inside a Node24 script after prelude.js + node24/modules/*.js are loaded.
// Provides the Auto.js global surface used by CHESS src/runtime/aijs-adapter.js and vision modules.
"use strict";
(function () {
  var path = require('path');
  var fs = require('fs');

  var nodeImage = require('image');
  var colorModule = require('color');
  var filesModule = require('files');
  var enginesModule = require('engines');
  var deviceModule = require('device');
  var storagesModule = require('storages');
  var threadsModule = require('threads');
  var mediaProjection = require('media_projection');

  var projectDir = String(globalThis.__ajsProjectDir || '');
  var chessBase = String(globalThis.__xiangqiChessBase || projectDir || '');
  var caps = { errors: {} };

  function absPath(value) {
    var text = String(value == null ? '' : value);
    if (!text) { return text; }
    if (path.isAbsolute(text)) { return text; }
    return path.resolve(chessBase || process.cwd(), text);
  }
  function ensureParent(file) {
    var dir = path.dirname(file);
    try { fs.mkdirSync(dir, { recursive: true }); } catch (e) { /* existing */ }
  }
  function nowMono() {
    if (typeof performance !== 'undefined' && performance && typeof performance.now === 'function') {
      return performance.now();
    }
    return Date.now();
  }

  // QuickJS NativeFrame surface: width/height/pixelWidth/pixelHeight + pixel/copy/saveTo/recycle.
  // Crucially NO getWidth/getHeight: chess detection-stage uses typeof image.getWidth === 'function'
  // to decide full-screen vs board-aspect detection region; matching QuickJS keeps the region identical.
  var frameSource = new WeakMap();
  function unwrapFrame(image) {
    return (image && typeof image === 'object' && frameSource.has(image)) ? frameSource.get(image) : image;
  }
  function wrapFrame(raw) {
    if (!raw || typeof raw !== 'object' || frameSource.has(raw)) { return raw; }
    var recycled = false;
    var tiles = Object.create(null);
    var tileBudget = { calls: 0, full: 0 };
    var fullPixels = null, fullTried = false, fullError = null;
    var TILE = 256; // getPixelsSync 单次上限 256KB（256*256*4 == 256KB）
    // Full-frame ARGB via pixelsToFile: one bridge call + local file read (the 256KB getPixels cap
    // forces ~25 tile calls, each with base64 JSON overhead; file channel is much cheaper).
    function tryFullFramePixels() {
      if (fullTried) { return fullPixels; }
      fullTried = true;
      try {
        if (typeof nodeImage.exportPixelsSync !== 'function') { return null; }
        var desc = nodeImage.exportPixelsSync(raw, { format: 'argb' });
        if (!desc || !desc.path || desc.format !== 'argb') { return null; }
        var buf = fs.readFileSync(desc.path);
        var width = Number(desc.width) || frame.width;
        var height = Number(desc.height) || frame.height;
        if (buf.length !== width * height * 4) { return null; }
        var u32 = new Uint32Array(buf.buffer, buf.byteOffset, buf.byteLength >>> 2);
        // Guard byte order: compare a few points against the host pixel action.
        var probes = [[0, 0], [Math.floor(width / 2), Math.floor(height / 2)], [width - 1, height - 1]];
        for (var i = 0; i < probes.length; i++) {
          var px = probes[i][0], py = probes[i][1];
          if ((u32[py * width + px] | 0) !== (nodeImage.pixelSync(raw, px, py) | 0)) { return null; }
        }
        fullPixels = { width: width, height: height, data: u32 };
        tileBudget.full += 1;
        return fullPixels;
      } catch (e) {
        fullError = String(e && (e.message || e));
        return null;
      }
    }
    function pixelAt(x, y) {
      x = Math.round(Number(x) || 0);
      y = Math.round(Number(y) || 0);
      if (x < 0 || y < 0 || x >= frame.width || y >= frame.height) { return 0; }
      var full = tryFullFramePixels();
      if (full) { return (full.data[y * full.width + x] | 0); }
      var tx = Math.floor(x / TILE), ty = Math.floor(y / TILE);
      var key = tx + ',' + ty;
      var tile = tiles[key];
      if (!tile) {
        var tw = Math.min(TILE, frame.width - tx * TILE);
        var th = Math.min(TILE, frame.height - ty * TILE);
        var info = nodeImage.getPixelsSync(raw, { region: [tx * TILE, ty * TILE, tw, th], format: 'argb' });
        var bytes = info.data;
        var u32t = new Uint32Array(bytes.buffer, bytes.byteOffset, bytes.byteLength >>> 2);
        tile = { width: info.width || tw, height: info.height || th, data: u32t };
        tiles[key] = tile;
        tileBudget.calls += 1;
      }
      var lx = x - tx * TILE, ly = y - ty * TILE;
      return (tile.data[ly * tile.width + lx] | 0);
    }
    var frame = {
      width: Number(raw.width) || 0,
      height: Number(raw.height) || 0,
      pixelWidth: Number(raw.pixelWidth) || Number(raw.width) || 0,
      pixelHeight: Number(raw.pixelHeight) || Number(raw.height) || 0,
      captureMode: raw.captureMode == null ? null : raw.captureMode,
      pixel: pixelAt,
      copy: function () { return wrapFrame(raw); },
      saveTo: function (file, format, quality) { return nodeImage.writeImageSync(raw, absPath(file), format, quality); },
      recycle: function () {
        recycled = true;
        tiles = Object.create(null);
        fullPixels = null;
        try { raw.recycle(); } catch (e) { /* already recycled */ }
        return true;
      }
    };
    Object.defineProperty(frame, '__pixelTileCalls', { enumerable: false, get: function () { return tileBudget.calls; } });
    Object.defineProperty(frame, '__pixelFullFetches', { enumerable: false, get: function () { return tileBudget.full; } });
    Object.defineProperty(frame, '__pixelError', { enumerable: false, get: function () { return fullError; } });
    Object.defineProperty(frame, 'recycled', { enumerable: true, get: function () { return recycled; } });
    frameSource.set(frame, raw);
    return frame;
  }

  // ---------------- images ----------------
  function normalizeTemplateOptions(options) {
    var opts = options || {};
    var limit = opts.max != null ? opts.max : (opts.limit != null ? opts.limit : 32);
    var out = {
      threshold: opts.threshold,
      weakThreshold: opts.weakThreshold,
      level: opts.level,
      limit: limit
    };
    if (opts.region != null) { out.region = opts.region; }
    return out;
  }
  function wrapMatches(list) {
    var arr = [];
    for (var i = 0; i < (list ? list.length : 0); i++) {
      var m = list[i] || {};
      var point = m.point || { x: m.x, y: m.y };
      arr.push({ point: { x: point.x, y: point.y }, similarity: m.similarity });
    }
    return { matches: arr };
  }
  function matchTemplate(image, template, options) {
    var list = nodeImage.matchTemplateSync(unwrapFrame(image), unwrapFrame(template), normalizeTemplateOptions(options));
    return wrapMatches(list);
  }
  function findAllPointsForColor() {
    // Not provided by Node24; adapter falls back to countColor when this is missing.
    return null;
  }
  function readRaw() {
    throw new Error('shim: images.readRaw 未实现（Node24 无 raw 通道）');
  }
  function writeImage(image, file, options) {
    var opts = options || {};
    return nodeImage.writeImageSync(unwrapFrame(image), absPath(file), opts.format, opts.quality);
  }
  function analyzeXiangqiBoard(image) {
    var fn = nodeImage.analyzeXiangqiBoardSync || nodeImage.analyzeXiangqiBoard;
    if (typeof fn !== 'function') { return null; }
    return fn(unwrapFrame(image));
  }
  function detectXiangqiCircles(image, grid) {
    var fn = nodeImage.detectXiangqiCirclesSync || nodeImage.detectXiangqiCircles;
    if (typeof fn !== 'function') { return null; }
    return fn(unwrapFrame(image), grid);
  }
  var images = {
    Image: nodeImage.Image,
    read: function (file) { return wrapFrame(nodeImage.readImageSync(absPath(file))); },
    write: writeImage,
    save: function (image, file) { return writeImage(image, file); },
    readRaw: readRaw,
    copy: function (image) { return wrapFrame(unwrapFrame(image)); },
    pixel: function (image, x, y) {
      if (image && typeof image === 'object' && frameSource.has(image)) {
        return image.pixel(x, y);
      }
      return nodeImage.pixelSync(unwrapFrame(image), x, y);
    },
    sizeOf: function (image) {
      var raw = unwrapFrame(image);
      return nodeImage.sizeOfSync(raw);
    },
    clip: function (image, x, y, w, h) { return wrapFrame(nodeImage.clipSync(unwrapFrame(image), x, y, w, h)); },
    grayscale: function (image) { return wrapFrame(nodeImage.grayscaleSync(unwrapFrame(image))); },
    threshold: function (image, t, max, type) { return wrapFrame(nodeImage.thresholdSync(unwrapFrame(image), t, max, type)); },
    resize: function (image, size, interp) { return wrapFrame(nodeImage.resizeSync(unwrapFrame(image), size, interp)); },
    countColor: function (image, color, options) {
      var opts = options || {};
      var req = { threshold: opts.threshold == null ? 4 : opts.threshold };
      if (opts.region != null) { req.region = opts.region; }
      return nodeImage.countColorSync(unwrapFrame(image), color, req);
    },
    matchTemplate: matchTemplate,
    findAllPointsForColor: findAllPointsForColor,
    analyzeXiangqiBoard: analyzeXiangqiBoard,
    detectXiangqiCircles: detectXiangqiCircles
  };
  // only advertise analyze/circles when the API actually exists (adapter checks typeof)
  if (typeof (nodeImage.analyzeXiangqiBoardSync || nodeImage.analyzeXiangqiBoard) !== 'function') {
    delete images.analyzeXiangqiBoard;
    caps.errors.analyzeXiangqiBoard = 'missing image.analyzeXiangqiBoardSync/analyzeXiangqiBoard (task-26)';
  }
  if (typeof (nodeImage.detectXiangqiCirclesSync || nodeImage.detectXiangqiCircles) !== 'function') {
    delete images.detectXiangqiCircles;
    caps.errors.detectXiangqiCircles = 'missing image.detectXiangqiCirclesSync (optional)';
  }

  // ---------------- colors ----------------
  function channelFn(name) {
    if (colorModule && typeof colorModule[name] === 'function') { return colorModule[name]; }
    return function () { return 0; };
  }
  var colors = {
    Color: colorModule && colorModule.Color,
    red: channelFn('red'),
    green: channelFn('green'),
    blue: channelFn('blue'),
    alpha: channelFn('alpha'),
    rgb: colorModule && colorModule.rgb,
    argb: colorModule && colorModule.argb,
    parseColor: colorModule && colorModule.parseColor,
    isSimilar: colorModule && colorModule.isSimilar
  };

  // ---------------- files ----------------
  function listDir(dir) { return fs.readdirSync(absPath(dir)); }
  function readFile(file) { return fs.readFileSync(absPath(file), 'utf8'); }
  function writeFile(file, text) {
    var target = absPath(file);
    ensureParent(target);
    fs.writeFileSync(target, String(text == null ? '' : text));
    return true;
  }
  var files = {
    path: function (p) { return absPath(p); },
    join: function () { return path.join.apply(path, arguments); },
    getSdcardPath: function () { return '/sdcard'; },
    cwd: function () { return chessBase || process.cwd(); },
    exists: function (p) { try { return fs.existsSync(absPath(p)); } catch (e) { return false; } },
    isFile: function (p) { try { return fs.statSync(absPath(p)).isFile(); } catch (e) { return false; } },
    isDir: function (p) { try { return fs.statSync(absPath(p)).isDirectory(); } catch (e) { return false; } },
    ensureDir: function (dir) { try { fs.mkdirSync(absPath(dir), { recursive: true }); return true; } catch (e) { return false; } },
    mkdir: function (dir) { return files.ensureDir(dir); },
    read: readFile,
    write: writeFile,
    append: function (p, text) { var target = absPath(p); ensureParent(target); fs.appendFileSync(target, String(text == null ? '' : text)); return true; },
    listDir: function (dir) {
      try { return listDir(dir); } catch (e) { return []; }
    },
    remove: function (p) { try { fs.rmSync(absPath(p), { recursive: true, force: true }); return true; } catch (e) { return false; } },
    stat: function (p) {
      try { var st = fs.statSync(absPath(p)); return { size: st.size, lastModified: st.mtimeMs, isFile: st.isFile(), isDirectory: st.isDirectory() }; } catch (e) { return null; }
    },
    copy: function (from, to) { var target = absPath(to); ensureParent(target); fs.copyFileSync(absPath(from), target); return true; },
    open: function (p) { var fd = fs.openSync(absPath(p), 'r'); return { close: function () { try { fs.closeSync(fd); } catch (e) { } } }; }
  };

  // ---------------- misc globals ----------------
  function safeDeviceInfo() {
    try {
      if (deviceModule && typeof deviceModule.getInfo === 'function') { return deviceModule.getInfo(); }
    } catch (e) { /* fall through */ }
    var raw = (deviceModule && deviceModule.device) || deviceModule || {};
    return {
      width: Number(raw.width) || 0, height: Number(raw.height) || 0,
      brand: raw.brand || null, model: raw.model || null,
      sdkInt: raw.sdkInt || raw.sdk || null, density: raw.density || null
    };
  }
  var device = { width: 0, height: 0, getInfo: safeDeviceInfo };
  try {
    var info = safeDeviceInfo();
    if (info) { device.width = Number(info.width) || 0; device.height = Number(info.height) || 0; }
  } catch (e) { caps.errors.device = String(e && e.message || e); }

  var storages = Object.assign({}, storagesModule, {
    create: function (name) {
      var storageName = String(name || 'default');
      return {
        get: function (key, def) { var v = storagesModule.get(key, storageName); return v == null ? def : v; },
        put: function (key, value) { return storagesModule.put(key, value, storageName); },
        remove: function (key) { return storagesModule.remove(key, storageName); },
        clear: function () { return storagesModule.clear(storageName); }
      };
    }
  });

  function makeEmitter() {
    var listeners = Object.create(null);
    function on(type, fn) { (listeners[type] = listeners[type] || []).push(fn); }
    function off(type, fn) { var arr = listeners[type]; if (!arr) { return; } listeners[type] = arr.filter(function (x) { return x !== fn; }); }
    return {
      on: on, off: off, once: on, addListener: on, removeListener: off,
      emit: function (type) { var arr = listeners[type] || []; var args = Array.prototype.slice.call(arguments, 1); arr.slice().forEach(function (fn) { try { fn.apply(null, args); } catch (e) { } }); return arr.length; },
      bus: { on: on, off: off, emit: function (type) { return 0; } }
    };
  }
  var events = makeEmitter();

  // ---------------- yolo (task-26; optional) ----------------
  var yolo = undefined;
  try {
    var yoloModule = require('yolo');
    if (yoloModule && typeof yoloModule.load === 'function') {
      yolo = Object.assign({}, yoloModule, {
        load: function (options) {
          var opts = Object.assign({}, options || {});
          if (opts.model == null && opts.modelPath != null) { opts.model = opts.modelPath; }
          if (opts.modelPath == null && opts.model != null) { opts.modelPath = opts.model; }
          var detector = yoloModule.load(opts);
          if (!detector || typeof detector.detect !== 'function') { return detector; }
          var wrapped = {};
          for (var key in detector) {
            if (Object.prototype.hasOwnProperty.call(detector, key) && key !== 'detect' && key !== 'close') {
              wrapped[key] = detector[key];
            }
          }
          wrapped.detect = function (image, detectOptions) { return detector.detect(unwrapFrame(image), detectOptions); };
          wrapped.close = function () { return typeof detector.close === 'function' ? detector.close() : undefined; };
          return wrapped;
        }
      });
      caps.yolo = 'ok(module load)';
    } else if (yoloModule) {
      yolo = yoloModule;
      caps.yolo = 'module present but no load()';
    }
  } catch (error) {
    caps.errors.yolo = String(error && error.message || error);
  }

  // ---------------- ocr (optional; not needed for glyph path) ----------------
  var ocr = undefined;
  try { ocr = require('ocr'); } catch (e) { caps.errors.ocr = String(e && e.message || e); }

  var api = {
    images: images,
    colors: colors,
    files: files,
    engines: enginesModule,
    device: device,
    storages: storages,
    events: events,
    threads: threadsModule,
    yolo: yolo,
    ocr: ocr,
    sleep: globalThis.sleep || function (ms) { var end = Date.now() + Math.max(0, Number(ms) || 0); while (Date.now() < end) { } },
    nowMono: nowMono,
    chessBase: chessBase,
    projectDir: projectDir,
    capabilities: caps
  };

  // install globals used by the old CHESS modules
  globalThis.images = images;
  globalThis.colors = colors;
  globalThis.files = files;
  globalThis.engines = enginesModule;
  globalThis.device = device;
  globalThis.storages = storages;
  globalThis.events = events;
  globalThis.threads = threadsModule;
  globalThis.yolo = yolo;
  globalThis.$ocr = undefined;
  globalThis.$pikafish = undefined;
  globalThis.floaty = {
    window: function () { throw new Error('shim: floaty.window 未实现（M1 不需要）'); },
    rawWindow: function () { throw new Error('shim: floaty.rawWindow 未实现（M1 不需要）'); }
  };
  globalThis.ui = {};
  globalThis.auto = undefined;
  globalThis.shell = function (command, options) { return require('shell').exec(String(command), options || {}); };
  globalThis.shell.exec = function (command, options) { return require('shell').exec(String(command), options || {}); };
  globalThis.shell.isRootAvailable = function () { return false; };
  globalThis.shell.checkAccess = function () { return false; };
  globalThis.requestScreenCapture = function () { return false; };
  globalThis.captureScreen = function () { throw new Error('shim: captureScreen 未启用（离线 M1 输入）'); };
  globalThis.context = globalThis.$autojs && globalThis.$autojs.androidContext;
  globalThis.__dirname = chessBase || globalThis.__dirname;
  globalThis.__xiangqiProjectDir = chessBase;
  // old adapter reads globals.__dirname for resolveProjectFile: use chessBase so assets/models resolves.
  if (chessBase) { globalThis.__dirname = chessBase; }

  module.exports = api;
})();
