// @engine quickjs
// 图片只读取一次；循环内直接处理原生像素，不重复解码或绘图。
var scene = null, matcher = null, started = Date.now();
try {
    scene = images.read('./大图.png');
    console.log('图片读取耗时：' + (Date.now() - started) + ' ms');
    started = Date.now();
    matcher = images.createSiftMatcher('./小图.png', {fast: true, track: true, scale: 0.5});
    console.log('模板准备耗时：' + (Date.now() - started) + ' ms');
    for (var i = 0; i < 10; i++) {
        started = Date.now();
        var result = matcher.match(scene);
        console.log('第' + (i + 1) + '次匹配耗时：' + (Date.now() - started) + ' ms');
        if (result) console.log('中心：' + result.centerX + ', ' + result.centerY
            + '；' + result.searchMode + '；特征复用：' + result.featureCacheHit
            + '；分段耗时：' + JSON.stringify(result.timings));
    }
} finally {
    if (matcher) matcher.recycle();
    if (scene) scene.recycle();
}
