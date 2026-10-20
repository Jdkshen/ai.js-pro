"nodejs";
var fs = require('fs');
var ROOT = '/sdcard/Download/three-engine-bench';
var image = require('image');
var OUT = ROOT + '/r5-node24-analyze-diag.json';
var result = { generatedAt: new Date().toISOString(), inputs: [] };
var INPUTS = [{ input: 'f01', path: ROOT + '/r4-frames/f01.png' }, { input: 'skin-a', path: ROOT + '/skin-a.png' }];
for (var i = 0; i < INPUTS.length; i++) {
  var it = INPUTS[i], rec = { input: it.input, path: it.path };
  var frame = null;
  try {
    frame = image.readImageSync(it.path);
    var t0 = Date.now();
    var r = image.analyzeXiangqiBoardSync(frame, { debug: true });
    rec.ms = Date.now() - t0;
    rec.ok = !!(r && r.ok);
    rec.raw = r || null;
    rec.reason = r && r.reason || null;
    rec.keys = r ? Object.keys(r) : null;
    rec.outer = r && r.outer || null;
    rec.grid = r && r.grid || null;
    rec.lineScoreX = r && r.lineScoreX;
    rec.lineScoreY = r && r.lineScoreY;
    rec.verticalSegments = r && r.verticalSegments;
    rec.horizontalSegments = r && r.horizontalSegments;
    rec.circles = r && r.circles && r.circles.length != null ? r.circles.length : null;
    if (r && r.circles && r.circles.length) { rec.circle0 = r.circles[0]; rec.circleLast = r.circles[r.circles.length - 1]; }
    // detectXiangqiCircles with analyze grid if available
    if (r && r.grid) {
      try {
        var t1 = Date.now();
        var circles = image.detectXiangqiCirclesSync(frame, r.grid);
        rec.circlesCall = { ms: Date.now() - t1, count: circles ? circles.length : null };
      } catch (e) { rec.circlesCall = { error: String(e && e.message || e) }; }
    }
  } catch (e) {
    rec.error = String(e && (e.stack || e.message) || e);
  } finally {
    if (frame) { try { frame.recycle(); } catch (e) { } }
  }
  result.inputs.push(rec);
}
fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
console.log('R5_ANALYZE_DONE ' + JSON.stringify(result.inputs.map(function (r) {
  return { input: r.input, ok: r.ok, reason: r.reason, ms: r.ms, grid: r.grid, outer: r.outer, circles: r.circles, error: r.error ? r.error.slice(0, 200) : null };
})));
