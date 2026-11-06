// @engine quickjs
// Place next to 大图.png / 小图.png, 大图旋转180.png and 大图微变.png (one changed background pixel).
// Optional baseline.js enables fair performance comparison.
var engine = typeof images.toMat === 'function' ? 'quickjs' : 'rhino';
var checks = [], benchmark = {}, failure = null, owned = [], sessions = [];
var scenePath = files.path('./大图.png'), templatePath = files.path('./小图.png');
function check(name, value, detail) {
    checks.push({name: name, pass: !!value, detail: detail || ''});
    if (!value) throw new Error(name + ': ' + detail);
}
function rejects(name, callback) {
    var rejected = false;
    try { callback(); } catch (error) { rejected = true; }
    check(name, rejected);
}
function located(result) {
    return result && result.method === 'SIFT' && result.matchCount >= 6
        && Math.abs(result.centerX - 781.7) < 5 && Math.abs(result.centerY - 975.5) < 5;
}
function own(image) { owned.push(image); return image; }
function session(template, options) {
    var matcher = images.createSiftMatcher(template, options);
    sessions.push(matcher); return matcher;
}
function time(name, callback) {
    var samples = [], result;
    callback(); // Warm-up is excluded.
    for (var i = 0; i < 5; i++) {
        var started = Date.now(); result = callback(); samples.push(Date.now() - started);
        check(name + ' location ' + i, located(result), JSON.stringify(result));
    }
    var sorted = samples.slice().sort(function(a, b) { return a - b; });
    benchmark[name] = {samples: samples, medianMs: sorted[2], matchCount: result.matchCount,
        backend:result.backend || 'legacy-js', inputMode:result.inputMode || '', timings:result.timings || null,
        featureCacheHit:result.featureCacheHit, indexCacheHit:result.indexCacheHit,
        searchScale:result.searchScale, searchPasses:result.searchPasses};
}
try {
    check('both public APIs registered', typeof images.matchSift === 'function' && typeof images.createSiftMatcher === 'function');
    check('$images alias registered', typeof $images.matchSift === 'function' && typeof $images.createSiftMatcher === 'function');
    var result = images.matchSift(scenePath, templatePath, {drawMatches: './output/api-color.jpg'});
    check('path inputs and color output', located(result), JSON.stringify(result));
    check('native C++ backend', result.backend === 'cpp' && result.inputMode === 'path');
    check('per-stage timings', result.timings && result.timings.inputMs >= 0 && result.timings.featureMs > 0
        && result.timings.flannMs >= 0 && result.timings.geometryMs >= 0 && result.timings.drawMs > 0);
    var full = images.matchSift(scenePath, templatePath, {scale: 1});
    check('full resolution', located(full), JSON.stringify(full));
    var roi = images.matchSift(scenePath, templatePath, {region: [600,800,351,401], scale: 0.5,
        drawMatches: './output/api-roi.jpg'});
    check('ROI odd dimensions return original coordinates', located(roi), JSON.stringify(roi));
    var large = own(images.read(scenePath)), small = own(images.read(templatePath));
    var directInput = images.matchSift(large, templatePath);
    check('image input and mixed input', located(directInput));
    check('native input route', directInput.inputMode === (engine === 'quickjs' ? 'frame' : 'mat'), JSON.stringify(directInput));
    var cached = session(small);
    check('template features cached', cached.templateFeatureCount > 0);
    check('template preparation timing', cached.templatePrepareMs > 0);
    small.recycle(); owned.pop();
    check('cached template independent of source lifetime', located(cached.match(large)));
    check('cache accepts options override', located(cached.match(large, {scale: 1})));
    cached.match(large, {scale: 0.5});
    var same = cached.match(large, {scale: 0.5});
    check('unchanged pixels reuse SIFT features and FLANN index', located(same)
        && same.featureCacheHit && same.indexCacheHit && same.timings.cacheCheckMs >= 0, JSON.stringify(same));
    var identicalCopy = own(images.copy(large));
    check('feature cache compares pixels across different image handles', cached.match(identicalCopy, {scale:0.5}).featureCacheHit);
    var changed = own(images.read('./大图微变.png'));
    var changedResult = cached.match(changed, {scale:0.5});
    check('one changed input pixel invalidates full scene and index', located(changedResult)
        && !changedResult.featureCacheHit && !changedResult.indexCacheHit, JSON.stringify(changedResult));
    if (engine === 'rhino') {
        var mutable = own(images.copy(large)), mutableMat = mutable.getMat();
        cached.match(mutable, {scale:0.5});
        var mutation = java.lang.reflect.Array.newInstance(java.lang.Byte.TYPE, mutableMat.channels());
        for (var channel = 0; channel < mutation.length; channel++) mutation[channel] = -1;
        mutableMat.put(200,40,mutation);
        var mutatedResult = cached.match(mutable, {scale:0.5});
        check('in-place pixel mutation invalidates cache for same Mat handle', located(mutatedResult)
            && !mutatedResult.featureCacheHit && !mutatedResult.indexCacheHit, JSON.stringify(mutatedResult));
    }
    cached.match(large, {region:[600,800,351,401],scale:0.5});
    check('background outside ROI does not invalidate identical ROI pixels',
        cached.match(changed, {region:[600,800,351,401],scale:0.5}).featureCacheHit);
    var roiImage = own(images.clip(large,600,800,351,401));
    var cropMatch = cached.match(roiImage, {region:[],scale:0.5});
    check('reused ROI pixels restore coordinates for a different image origin', cropMatch
        && cropMatch.featureCacheHit && cropMatch.indexCacheHit
        && Math.abs(cropMatch.centerX - 181.7) < 5 && Math.abs(cropMatch.centerY - 175.5) < 5, JSON.stringify(cropMatch));
    var originalOrigin = cached.match(large, {region:[600,800,351,401],scale:0.5});
    check('reused ROI returns original scene coordinates after origin changes',
        located(originalOrigin) && originalOrigin.featureCacheHit, JSON.stringify(originalOrigin));
    var forced = cached.match(large, {scale:0.5,reuseScene:false});
    check('reuseScene false forces computation', located(forced) && !forced.featureCacheHit && !forced.indexCacheHit);
    var reconfigured = cached.match(large, {scale:0.5,maxFeatures:10000});
    check('detector configuration invalidates feature cache', located(reconfigured) && !reconfigured.featureCacheHit);
    cached.match(large, {scale:0.5});
    check('cached features still honor stricter geometry requirements', cached.match(large, {scale:0.5,minMatches:100}) === null);
    var fast = session(templatePath, {fast:true,scale:0.5});
    var coarse = fast.match(large);
    check('fast search accepts strong coarse match with restored coordinates', located(coarse)
        && coarse.matchCount >= 8 && coarse.searchScale < 0.5 && coarse.searchPasses === 1, JSON.stringify(coarse));
    var fallback = fast.match(large, {scale:0.45});
    check('coarse miss falls back to requested resolution', located(fallback)
        && Math.abs(fallback.searchScale - 0.45) < 0.001 && fallback.searchPasses === 2, JSON.stringify(fallback));
    var blank = own(images.clip(large, 0, 140, 320, 160));
    check('no target returns null', cached.match(blank) === null);
    var emptyTemplate = session(blank);
    check('empty template returns null', emptyTemplate.match(large) === null);
    rejects('missing path', function() { images.matchSift('./missing-sift-input.png', templatePath); });
    rejects('invalid scale', function() { cached.match(large, {scale: 0}); });
    rejects('invalid ROI', function() { cached.match(large, {region: [-1,0,200,200]}); });
    rejects('empty ROI', function() { cached.match(large, {region: [0,0,0,100]}); });
    rejects('invalid ratio', function() { cached.match(large, {threshold: NaN}); });
    rejects('write error', function() { cached.match(large, {drawMatches: '/proc/aijs-forbidden/result.jpg'}); });
    check('failure leaves matcher usable', located(cached.match(large)));
    if (engine === 'quickjs') {
        // Exceed the 65,536 Java bridge handle limit. Short-lived frame tokens must be released.
        for (var tokenIndex = 0; tokenIndex < 70000; tokenIndex++) {
            var token = images.__siftNativeInput(large); images.__releaseSiftInput(token);
        }
        check('frame token stress preserves cached Java handles', located(cached.match(large)));
    }
    var tracking = session(templatePath, {track: true});
    check('tracking starts with full frame', tracking.match(large).searchMode === 'full');
    var tracked = tracking.match(large, {drawMatches: './output/api-tracked.jpg'});
    check('tracking narrows next frame and preserves coordinates', located(tracked) && tracked.searchMode === 'tracked', JSON.stringify(tracked));
    var stable = tracking.match(large);
    check('stable tracking ROI reuses exact pixels and FLANN index', located(stable)
        && stable.featureCacheHit && stable.indexCacheHit, JSON.stringify(stable));
    var moved = own(images.read('./大图旋转180.png'));
    var movedResult = tracking.match(moved);
    check('moved target falls back to full frame', movedResult && movedResult.searchMode === 'full'
        && Math.abs(movedResult.centerX - (1080 - 781.7)) < 5
        && Math.abs(movedResult.centerY - (2340 - 975.5)) < 5, JSON.stringify(movedResult));
    check('tracking recovers original position', located(tracking.match(large)));
    check('explicit ROI overrides tracking', tracking.match(large, {region: [600,800,351,401]}).searchMode === 'region');
    check('tracking miss clears previous position', tracking.match(blank) === null && tracking.match(large).searchMode === 'full');
    check('source survives native cleanup', typeof images.pixel(large, 20, 20) === 'number');
    var released = session(templatePath); released.recycle(); released.recycle();
    check('idempotent recycle', released.isRecycled());
    rejects('recycled matcher', function() { released.match(large); });
    // All measurements exclude drawing and use the same already decoded scene/template and scale.
    var template = own(images.read(templatePath));
    if (files.exists('./baseline.js')) {
        var old = require('./baseline');
        time('oldJsOneShot', function() {
            var a = null, b = null;
            try {
                a = old.detectAndComputeFeatures(large, {scale: 0.5});
                b = old.detectAndComputeFeatures(template, {scale: 1});
                return old.matchFeatures(a, b);
            } finally { if (b) b.recycle(); if (a) a.recycle(); }
        });
    }
    time('nativeOneShot', function() { return images.matchSift(large, template, {scale: 0.5}); });
    time('nativeCached', function() { return cached.match(large, {scale: 0.5}); });
    time('nativeTracked', function() { return tracking.match(large, {scale: 0.5}); });
    time('nativeCachedRecompute', function() { return cached.match(large, {scale:0.5,reuseScene:false}); });
    time('nativeTrackedRecompute', function() { return tracking.match(large, {scale:0.5,reuseScene:false}); });
    var alternate = false;
    time('nativeCachedBackgroundChange', function() { alternate = !alternate; return cached.match(alternate ? large : changed, {scale:0.5}); });
    time('nativeTrackedBackgroundChange', function() { alternate = !alternate; return tracking.match(alternate ? large : changed, {scale:0.5}); });
    time('nativeFastOneShot', function() { return images.matchSift(large, template, {scale:0.5,fast:true}); });
    time('nativePaths', function() { return images.matchSift(scenePath, templatePath); });
    // Record optional feature/scale caps; do not silently select a profile that misses the sample.
    benchmark.profiles = [];
    var profiles = [{scale:0.5,maxFeatures:1500},{scale:0.5,maxFeatures:2000},{scale:0.4,maxFeatures:1500}];
    for (var j = 0; j < profiles.length; j++) {
        var start = Date.now(), found = cached.match(large, profiles[j]);
        benchmark.profiles.push({options:profiles[j], elapsedMs:Date.now()-start,
            located:!!located(found), result:found});
    }
    console.log('SIFT_API_BENCH ' + engine + ' ' + JSON.stringify(benchmark));
} catch (error) {
    failure = String(error) + '\n' + String(error.stack || ''); console.error(failure);
} finally {
    for (var k = sessions.length - 1; k >= 0; k--) sessions[k].recycle();
    for (k = owned.length - 1; k >= 0; k--) if (owned[k]) owned[k].recycle();
    var output = files.path('./output/api-regression-' + engine + '.json'); files.ensureDir(output);
    files.write(output, JSON.stringify({engine: engine, passed: !failure, failure: failure,
        checks:checks, benchmark:benchmark, time:Date.now()}, null, 2));
    console.log('SIFT_API_REGRESSION ' + engine + ' ' + (failure ? 'FAIL' : 'PASS') + ' checks=' + checks.length);
}
if (failure) throw new Error(failure);
