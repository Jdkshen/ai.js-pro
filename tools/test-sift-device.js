// @engine quickjs
// 真机回归：把本脚本复制到完整 SIFT 示例目录运行。Rhino 时改首行引擎标记。
// 结果写入 output/regression-quickjs.json 或 output/regression-rhino.json。
var sift = require('./sift');
var imagesOwned = [], featuresOwned = [], checks = [], failure = null;
var kind = typeof images.toMat === 'function' ? 'quickjs' : 'rhino';
function check(name, condition, detail) {
    checks.push({name: name, pass: !!condition, detail: detail || ''});
    if (!condition) throw new Error('FAIL ' + name + ': ' + detail);
}
function ownImage(image) { imagesOwned.push(image); return image; }
function detect(image, options) {
    var features = sift.detectAndComputeFeatures(image, options);
    featuresOwned.push(features);
    return features;
}
function throws(name, callback) {
    var rejected = false;
    try { callback(); } catch (error) { rejected = true; }
    check(name, rejected);
}
try {
    var large = ownImage(images.read(files.path('./大图.png')));
    var small = ownImage(images.read(files.path('./小图.png')));
    check('assets present', !!large && !!small);
    var scene = detect(large, {scale: 0.5}), target = detect(small, {scale: 1});
    var result = sift.matchFeatures(scene, target, {drawMatches: files.path('./output/regression-gray.jpg')});
    check('provided target located', result && Math.abs(result.centerX - 781.7) < 5
        && Math.abs(result.centerY - 975.5) < 5, JSON.stringify(result));
    check('actual SIFT descriptors', scene.descriptors.cols() === 128
        && target.descriptors.cols() === 128 && scene.method === 'SIFT');
    check('RANSAC inliers', result.matchCount >= 6 && result.matchCount <= result.candidateCount);
    var cropped = detect(large, {region: [600, 800, 351, 401], scale: 0.5, grayscale: false});
    var roiResult = sift.matchFeatures(cropped, target, {drawMatches: files.path('./output/regression-color-roi.jpg')});
    check('ROI and rounded scale restore original coordinates', roiResult
        && Math.abs(roiResult.centerX - result.centerX) < 5
        && Math.abs(roiResult.centerY - result.centerY) < 5, JSON.stringify(roiResult));
    check('color output retained', cropped.workMat.channels() === 3);
    var scaledTarget = detect(small, {scale: 0.5});
    check('explicit scale applies to small image', scaledTarget.workMat.cols() === Math.round(small.width * 0.5)
        && scaledTarget.workMat.rows() === Math.round(small.height * 0.5));
    var twice = sift.matchFeatures(scene, target);
    check('features reusable', twice && Math.abs(twice.centerX - result.centerX) < 5);
    // 原图上方的纯黑区域，不依赖两个引擎不同的 Mat→Image 所有权规则。
    var blank = ownImage(images.clip(large, 0, 140, 320, 160));
    var empty = detect(blank, {scale: 1});
    check('negative image returns null', empty.count === 0 && sift.matchFeatures(empty, target) === null);
    throws('invalid scale rejected', function () { sift.detectAndComputeFeatures(large, {scale: 0}); });
    throws('invalid ROI rejected', function () { sift.detectAndComputeFeatures(large, {region: [-1, 0, 100, 100]}); });
    throws('zero size ROI rejected', function () { sift.detectAndComputeFeatures(large, {region: [0, 0, 0, 100]}); });
    throws('invalid ratio rejected', function () { sift.matchFeatures(scene, target, {threshold: NaN}); });
    // 不应把写图失败静默报告为匹配成功。
    throws('write failure reported', function () {
        sift.matchFeatures(scene, target, {drawMatches: '/proc/aijs-sift-forbidden/result.jpg'});
    });
    cropped.recycle(); cropped.recycle();
    throws('recycled features rejected', function () { sift.matchFeatures(cropped, target); });
    // 回收特征后，原始图片仍然有效。
    check('source image survives feature cleanup', large.width === 1080 && typeof images.pixel(large, 20, 20) === 'number');
} catch (error) {
    failure = String(error.stack || error);
    console.error(failure);
} finally {
    for (var i = featuresOwned.length - 1; i >= 0; i--) featuresOwned[i].recycle();
    for (i = imagesOwned.length - 1; i >= 0; i--) if (imagesOwned[i]) imagesOwned[i].recycle();
    var output = files.path('./output/regression-' + kind + '.json');
    files.ensureDir(output);
    files.write(output, JSON.stringify({engine: kind, checks: checks, failure: failure,
        passed: !failure, time: Date.now()}, null, 2));
    console.log('SIFT_REGRESSION ' + kind + ' ' + (failure ? 'FAIL' : 'PASS') + ' checks=' + checks.length);
}
if (failure) throw new Error(failure);
