"nodejs";
// R5.9 performance probe: staged timing for chess recognizeImage on Node24.
// Measures module require / yolo model load / per-frame locate / detect / side sampling / assign+FEN,
// with shim per-frame tiled pixel cache enabled. Marker: R5_PERF_DONE
"use strict";
var fs = require('fs');
var ROOT = '/sdcard/Download/three-engine-bench';
var CHESS = ROOT + '/r5-chess';
var OUT = ROOT + '/r5-node24-perf.json';
globalThis.__xiangqiChessBase = CHESS;
var tRequire0 = Date.now();
var shim = require('/sdcard/Download/three-engine-bench/r5-node24/shim.node.js');
var nodeImage = require('image');
var board = require(CHESS + '/experiments/vision/recognize-board.js');
var tRequire = Date.now() - tRequire0;
var result = {
  probe: 'r5-node24-perf', engine: 'node24', generatedAt: new Date().toISOString(),
  requireMs: tRequire, loadMs: null, detector: null, source: null,
  cacheVerify: [], frames: []
};
var loaded = null;
try {
  var tLoad0 = Date.now();
  loaded = board.loadDetector({});
  result.loadMs = Date.now() - tLoad0;
  result.detector = loaded.request;
  result.source = loaded.source;
} catch (e) {
  result.loadError = String(e && (e.stack || e.message) || e);
}
function verifyCache(file) {
  var rec = { path: file, checked: 0, mismatch: 0, samples: [] };
  var wrapper = null, raw = null;
  try {
    wrapper = shim.images.read(file);
    raw = nodeImage.readImageSync(file);
    var w = Number(wrapper.width) || 0, h = Number(wrapper.height) || 0;
    var state = 12345;
    for (var i = 0; i < 200; i++) {
      state = (state * 1103515245 + 12345) & 0x7fffffff;
      var x = state % w;
      state = (state * 1103515245 + 12345) & 0x7fffffff;
      var y = state % h;
      var cached = wrapper.pixel(x, y);
      var direct = nodeImage.pixelSync(raw, x, y);
      rec.checked += 1;
      if ((cached | 0) !== (direct | 0)) {
        rec.mismatch += 1;
        if (rec.samples.length < 5) { rec.samples.push({ x: x, y: y, cached: cached, direct: direct }); }
      }
    }
  } catch (e) {
    rec.error = String(e && (e.stack || e.message) || e);
  } finally {
    if (wrapper) { try { wrapper.recycle(); } catch (e) { } }
    if (raw) { try { raw.recycle(); } catch (e) { } }
  }
  result.cacheVerify.push(rec);
  return rec;
}
var FRAMES = [{ id: 'f01', path: ROOT + '/r4-frames/f01.png' }, { id: 'skin-a', path: ROOT + '/skin-a.png' }];
if (loaded) {
  for (var fi = 0; fi < FRAMES.length; fi++) {
    var item = FRAMES[fi];
    var cache = verifyCache(item.path);
    var rec = { id: item.id, path: item.path, cache: cache, runs: [] };
    // extra: direct analyze timing (also part of locate; measured once for breakdown)
    try {
      var af = nodeImage.readImageSync(item.path);
      var a0 = Date.now();
      var ad = nodeImage.analyzeXiangqiBoardSync(af);
      rec.analyzeMs = Date.now() - a0;
      rec.analyzeOk = !!(ad && ad.ok);
      rec.analyzeCircles = ad && ad.circles ? ad.circles.length : null;
      try { af.recycle(); } catch (e) { }
    } catch (e) { rec.analyzeError = String(e); }
    for (var run = 0; run < 3; run++) {
      var t0 = Date.now();
      var out = null, error = null;
      try {
        out = board.recognizeImage(item.path, { detector: loaded.detector, detectorRequest: loaded.request });
      } catch (e) {
        error = String(e && (e.stack || e.message) || e);
      }
      var totalMs = Date.now() - t0;
      var timing = out && out.timing ? out.timing : null;
      var locateTiming = out && out.located && out.located.timing ? out.located.timing : null;
      rec.runs.push({
        run: run, totalMs: totalMs, ok: !!(out && out.ok),
        elapsedMs: out && out.elapsedMs,
        detectionCount: out && out.detectionCount,
        layout: out && out.layout || null,
        counts: (function () {
          if (!out || !out.chars) return null;
          var p = 0, u = 0;
          for (var i = 0; i < 90; i++) {
            var ch = out.chars[i];
            if (!ch) continue;
            if (ch === '?') u++; else p++;
          }
          return { pieces: p, unknown: u };
        })(),
        locateMs: locateTiming ? (locateTiming.locateMs != null ? locateTiming.locateMs : locateTiming.totalMs) : null,
        locateTotalMs: locateTiming ? locateTiming.totalMs : null,
        captureMs: locateTiming ? locateTiming.captureMs : null,
        nativeDetectMs: timing ? timing.detectMs : null,
        sideMs: timing ? timing.sideMs : null,
        recognizerTotalMs: timing ? timing.totalMs : null,
        assignFenMs: timing && timing.totalMs != null && timing.detectMs != null && timing.sideMs != null
          ? (timing.totalMs - timing.detectMs - timing.sideMs) : null,
        native: out && out.native ? { preprocessMs: out.native.preprocessMs, inferenceMs: out.native.inferenceMs, totalMs: out.native.totalMs, count: out.native.list ? out.native.list.length : null } : null,
        stats: out && out.stats ? { detected: out.stats.detected, placed: out.stats.placed, autoAlign: out.stats.autoAlign ? { dx: out.stats.autoAlign.dx, dy: out.stats.autoAlign.dy } : null } : null,
        error: error
      });
    }
    result.frames.push(rec);
  }
  try { if (loaded.detector && typeof loaded.detector.close === 'function') loaded.detector.close(); } catch (e) { }
}
result.finishedAt = new Date().toISOString();
try { fs.writeFileSync(OUT, JSON.stringify(result, null, 2)); result.written = OUT; } catch (e) { result.writeError = String(e); }
console.log('R5_PERF_DONE ' + JSON.stringify({
  requireMs: result.requireMs, loadMs: result.loadMs,
  cache: result.cacheVerify.map(function (c) { return { path: c.path, checked: c.checked, mismatch: c.mismatch }; }),
  frames: result.frames.map(function (f) {
    return { id: f.id, analyzeMs: f.analyzeMs, runs: f.runs.map(function (r) { return { run: r.run, totalMs: r.totalMs, elapsedMs: r.elapsedMs, locateMs: r.locateMs, detectMs: r.nativeDetectMs, sideMs: r.sideMs, assignFenMs: r.assignFenMs, count: r.detectionCount, ok: r.ok }; }) };
  })
}));
