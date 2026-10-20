"nodejs";
// R5.6 Node24 port probe: run chess recognition on saved frames.
// Output: /sdcard/Download/three-engine-bench/r5-node24-recognition.json
// Marker: R5_NODE24_DONE
"use strict";
var path = require('path');
var fs = require('fs');
var ROOT = '/sdcard/Download/three-engine-bench';
var CHESS = ROOT + '/r5-chess';
var OUT = ROOT + '/r5-node24-recognition.json';
globalThis.__xiangqiChessBase = CHESS;
var shim = require(path.join(__dirname, 'shim.node.js'));
var nodeImage = require('image');

function fileExists(p) { try { return fs.existsSync(p); } catch (e) { return false; } }
function errText(e) { return String(e && (e.stack || e.message) || e); }
function bboxFromGrid(g) {
  if (!g) { return null; }
  var sx = Number(g.stepX), sy = Number(g.stepY);
  return {
    x: Math.round(Number(g.originX) - sx / 2),
    y: Math.round(Number(g.originY) - sy / 2),
    width: Math.round(8 * sx + sx),
    height: Math.round(9 * sy + sy)
  };
}
function charsToPieces(chars) {
  var pieces = [], pieceCount = 0, unknownCount = 0;
  for (var i = 0; i < 90; i++) {
    var ch = chars ? chars[i] : null;
    if (ch == null || ch === '' || ch === '.') { continue; }
    var row = Math.floor(i / 9), col = i % 9;
    if (ch === '?') {
      unknownCount += 1;
      pieces.push({ row: row, col: col, side: 'unknown', kind: '?', unknown: true });
      continue;
    }
    pieceCount += 1;
    var upper = String(ch).toUpperCase();
    pieces.push({ row: row, col: col, side: String(ch) === upper ? 'red' : 'black', kind: upper, unknown: false });
  }
  return { pieces: pieces, counts: { pieces: pieceCount, unknown: unknownCount } };
}
function firstLayout(chars) {
  try {
    var fen = require(CHESS + '/src/chess/fen.js');
    return fen.layoutOf(chars, { allowUnknown: true });
  } catch (e) {
    return null;
  }
}

var result = {
  probe: 'r5-node24-recognize',
  engine: 'node24',
  generatedAt: new Date().toISOString(),
  chessBase: CHESS,
  capabilities: {
    imageModule: true,
    countColor: typeof nodeImage.countColorSync === 'function',
    matchTemplate: typeof nodeImage.matchTemplateSync === 'function',
    analyzeXiangqiBoard: typeof (nodeImage.analyzeXiangqiBoardSync || nodeImage.analyzeXiangqiBoard) === 'function',
    detectXiangqiCircles: typeof (nodeImage.detectXiangqiCirclesSync || nodeImage.detectXiangqiCircles) === 'function',
    yoloModule: false,
    ocrModule: false,
    shimErrors: shim.capabilities && shim.capabilities.errors ? shim.capabilities.errors : {}
  },
  inputs: [],
  notes: []
};
try { require('yolo'); result.capabilities.yoloModule = true; } catch (e) { result.capabilities.yoloModule = false; }
try { require('ocr'); result.capabilities.ocrModule = true; } catch (e) { result.capabilities.ocrModule = false; }

var INPUTS = [
  { input: 'f01', path: ROOT + '/r4-frames/f01.png' },
  { input: 'skin-a', path: ROOT + '/skin-a.png' }
];

// ---- optional B path: experiments/vision/recognize-board.js recognizeImage(path) ----
var boardModule = null, boardLoadError = null;
try {
  boardModule = require(CHESS + '/experiments/vision/recognize-board.js');
} catch (e) {
  boardLoadError = errText(e);
  result.notes.push('B 路径模块加载失败：' + boardLoadError.slice(0, 600));
}

// Load the detector once and reuse it for every input (production path loads once per session).
var sharedDetector = null, sharedDetectorRequest = null, detectorLoadMs = null, detectorLoadError = null;
if (boardModule) {
  try {
    var detectorStartedAt = Date.now();
    var loadedDetector = boardModule.loadDetector({});
    sharedDetector = loadedDetector.detector;
    sharedDetectorRequest = loadedDetector.request;
    detectorLoadMs = Date.now() - detectorStartedAt;
  } catch (e) {
    detectorLoadError = errText(e);
    result.notes.push('B 路径模型加载失败：' + detectorLoadError.slice(0, 400));
  }
}
result.detectorLoadMs = detectorLoadMs;
result.detectorLoadError = detectorLoadError;

for (var ii = 0; ii < INPUTS.length; ii++) {
  var item = INPUTS[ii];
  var rec = { input: item.input, path: item.path, source: null, ms: 0 };
  if (!fileExists(item.path)) {
    rec.error = 'file missing';
    result.inputs.push(rec);
    continue;
  }
  var startedAt = Date.now();
  // diagnostic: direct analyzeXiangqiBoard (task-26 API) so failures are recorded precisely
  try {
    var diagFrame = nodeImage.readImageSync(item.path);
    var diagStart = Date.now();
    var diag = nodeImage.analyzeXiangqiBoardSync ? nodeImage.analyzeXiangqiBoardSync(diagFrame) : null;
    rec.analyzeDiag = {
      ms: Date.now() - diagStart,
      ok: !!(diag && diag.ok),
      reason: diag && diag.reason || null,
      keys: diag ? Object.keys(diag) : null,
      grid: diag && diag.grid || null,
      outer: diag && diag.outer || null,
      lineScoreX: diag && diag.lineScoreX,
      lineScoreY: diag && diag.lineScoreY,
      verticalSegments: diag && diag.verticalSegments,
      horizontalSegments: diag && diag.horizontalSegments,
      circles: diag && diag.circles && diag.circles.length != null ? diag.circles.length : null
    };
    try { diagFrame.recycle(); } catch (e) { }
  } catch (e) {
    rec.analyzeDiag = { error: errText(e) };
  }
  var bErr = null;
  if (boardModule) {
    try {
      var bOut = boardModule.recognizeImage(item.path, sharedDetector
        ? { detector: sharedDetector, detectorRequest: sharedDetectorRequest }
        : {});
      rec.bPath = {
        ok: !!(bOut && bOut.ok),
        stage: bOut && bOut.stage || null,
        reason: bOut && bOut.reason || null,
        layout: bOut && bOut.layout || null,
        unknownCells: bOut && bOut.unknownCells,
        detectionCount: bOut && bOut.detectionCount,
        modelFailures: bOut && bOut.modelFailures || null,
        elapsedMs: bOut && bOut.elapsedMs,
        native: bOut && bOut.native ? {
          count: bOut.native.list ? bOut.native.list.length : null,
          preprocessMs: bOut.native.preprocessMs, inferenceMs: bOut.native.inferenceMs, totalMs: bOut.native.totalMs
        } : null,
        stats: bOut && bOut.stats ? {
          detected: bOut.stats.detected, placed: bOut.stats.placed, merged: bOut.stats.merged,
          unknown: bOut.stats.unknown, sideRed: bOut.stats.sideRed, sideBlack: bOut.stats.sideBlack,
          autoAlign: bOut.stats.autoAlign || null
        } : null,
        locatedGrid: bOut && bOut.located && bOut.located.grid ? {
          originX: bOut.located.grid.originX, originY: bOut.located.grid.originY,
          stepX: bOut.located.grid.stepX, stepY: bOut.located.grid.stepY,
          orientation: bOut.located.grid.orientation, version: bOut.located.grid.version
        } : null
      };
      if (bOut && bOut.ok && bOut.chars) {
        rec.source = 'B-recognizeImage';
        rec.chars = bOut.chars;
        rec.layout = bOut.layout || firstLayout(bOut.chars);
        rec.grid = bOut.grid ? {
          originX: bOut.grid.originX, originY: bOut.grid.originY,
          stepX: bOut.grid.stepX, stepY: bOut.grid.stepY,
          orientation: bOut.grid.orientation || 'RED_BOTTOM'
        } : (bOut.located && bOut.located.grid ? {
          originX: bOut.located.grid.originX, originY: bOut.located.grid.originY,
          stepX: bOut.located.grid.stepX, stepY: bOut.located.grid.stepY,
          orientation: bOut.located.grid.orientation || 'RED_BOTTOM'
        } : null);
        rec.located = bOut.located ? {
          ok: bOut.located.ok, method: bOut.located.method, reason: bOut.located.reason,
          columns: bOut.located.columns, rows: bOut.located.rows, region: bOut.located.region || null
        } : null;
      }
    } catch (e) {
      bErr = errText(e);
      rec.bPath = { ok: false, error: bErr };
      result.notes.push(item.input + ' B 路径异常：' + bErr.slice(0, 600));
    }
  }

  // ---- A fallback: lattice grid + glyph templates (if B unavailable/failed) ----
  if (!rec.chars) {
    var frame = null, templates = null, glyphModule = null;
    try {
      var lattice = require(CHESS + '/src/vision/lattice-detector.js');
      var glyph = require(CHESS + '/src/vision/glyph-recognizer.js');
      glyphModule = glyph;
      var host = {
        sizeOf: function (img) { var s = nodeImage.sizeOfSync(img); return { width: s.width, height: s.height }; },
        countColor: function (img, color, opts) {
          var o = { threshold: opts && opts.threshold != null ? opts.threshold : 4 };
          if (opts && opts.region) { o.region = opts.region; }
          return nodeImage.countColorSync(img, color, o);
        },
        grayscale: function (img) { return nodeImage.grayscaleSync(img); },
        clip: function (img, x, y, w, h) { return nodeImage.clipSync(img, x, y, w, h); },
        threshold: function (img, t, m) { return nodeImage.thresholdSync(img, t, m); },
        matchTemplate: shim.images.matchTemplate,
        pixel: function (img, x, y) { return nodeImage.pixelSync(img, x, y); },
        red: shim.colors.red, green: shim.colors.green, blue: shim.colors.blue,
        resize: function (img, size) { return nodeImage.resizeSync(img, size); },
        read: function (p) { return nodeImage.readImageSync(p); },
        nowMono: function () { return Date.now(); }
      };
      frame = nodeImage.readImageSync(item.path);
      var lat = lattice.detect({ sizeOf: host.sizeOf, countColor: host.countColor }, frame, { orientation: 'RED_BOTTOM' });
      rec.aPath = { latticeOk: !!(lat && lat.ok), reason: lat && lat.reason || null, score: lat && lat.score };
      if (!lat || !lat.ok || !lat.grid) { throw new Error('lattice 定位失败：' + (lat && lat.reason || 'unknown')); }
      rec.grid = { originX: lat.grid.originX, originY: lat.grid.originY, stepX: lat.grid.stepX, stepY: lat.grid.stepY, orientation: lat.grid.orientation || 'RED_BOTTOM' };
      rec.located = { ok: true, method: 'lattice', reason: lat.reason || null, columns: lat.evidence && lat.evidence.vertical ? lat.evidence.vertical.matched : null, rows: lat.evidence && lat.evidence.horizontal ? lat.evidence.horizontal.matched : null };
      var tplDir = CHESS + '/assets/glyph-tpl';
      templates = glyph.loadTemplates(host, tplDir);
      var scale = glyph.scaleFor(lat.grid);
      var prepared = glyph.prepareFor(host, templates, scale);
      var g = glyph.recognize(host, frame, lat.grid, {
        templates: prepared.templates, TW: prepared.TW, ANCHOR_DY: prepared.ANCHOR_DY,
        coarseTemplates: prepared.coarse, collect: true
      });
      if (!g || g.error) { throw new Error('glyph 识别失败：' + (g && g.error || 'unknown')); }
      var chars = glyph.normalizeBoard(g.chars);
      rec.source = 'A-glyph';
      rec.chars = chars;
      rec.layout = firstLayout(chars);
      rec.aPath.glyph = { inked: g.inked, unknown: g.unknown, rescues: g.rescues || 0, profile: g.profile || null, ms: g.ms };
    } catch (e) {
      rec.aPath = rec.aPath || {};
      rec.aPath.error = errText(e);
      result.notes.push(item.input + ' A 回退异常：' + errText(e).slice(0, 700));
    } finally {
      if (templates && glyphModule && typeof glyphModule.releaseTemplates === 'function') {
        try { glyphModule.releaseTemplates(templates); } catch (e) { }
        templates = null;
      }
      if (frame) { try { frame.recycle(); } catch (e) { } }
    }
  }

  rec.ms = Date.now() - startedAt;
  if (rec.chars) {
    var mapped = charsToPieces(rec.chars);
    rec.pieces = mapped.pieces;
    rec.counts = mapped.counts;
    rec.placement = rec.layout || firstLayout(rec.chars);
    rec.bbox = bboxFromGrid(rec.grid);
  } else {
    rec.counts = { pieces: 0, unknown: 0 };
    rec.placement = null;
    rec.bbox = null;
  }
  result.inputs.push(rec);
}

if (sharedDetector && typeof sharedDetector.close === 'function') {
  try { sharedDetector.close(); } catch (e) { }
}
result.finishedAt = new Date().toISOString();
try {
  fs.mkdirSync(ROOT, { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  result.written = OUT;
} catch (e) {
  result.writeError = errText(e);
}
var brief = {
  capabilities: result.capabilities,
  inputs: result.inputs.map(function (r) {
    return {
      input: r.input, source: r.source, ok: !!(r.chars && r.placement),
      placement: r.placement, counts: r.counts, ms: r.ms,
      bPath: r.bPath ? { ok: r.bPath.ok, stage: r.bPath.stage, reason: (r.bPath.reason || '').slice(0, 160), error: r.bPath.error ? r.bPath.error.slice(0, 160) : null } : null,
      aPath: r.aPath ? { latticeOk: r.aPath.latticeOk, reason: (r.aPath.reason || '').slice(0, 160), error: r.aPath.error ? r.aPath.error.slice(0, 160) : null } : null
    };
  }),
  notes: result.notes.map(function (n) { return n.slice(0, 300); }),
  writeError: result.writeError || null
};
console.log('R5_NODE24_DONE ' + JSON.stringify(brief));
