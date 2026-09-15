// @engine rhino
// 新 API 在全屏尺寸（1080x2160）下的耗时基准，用于优化前后对比。
//
// 用法：与普通契约脚本一样跑，看 BENCH 行。
var W = 1080, H = 2160;

function bench(name, fn, times) {
    times = times || 3;
    // 预热一次（首次会加载 native 库 / 初始化 ML Kit）
    try { fn(); } catch (e) { console.log('BENCH|' + name + '|预热失败: ' + e); }
    var total = 0;
    for (var i = 0; i < times; i++) {
        var start = Date.now();
        try {
            fn();
        } catch (e) {
            console.log('BENCH|' + name + '|失败: ' + e);
            return;
        }
        total += Date.now() - start;
    }
    console.log('BENCH|' + name + '|平均 ' + Math.round(total / times) + 'ms / ' + times + ' 次');
}

// 造一张全屏尺寸的图（噪点方块，保证有足够多的特征点）
function makeFullScreenBitmap() {
    var bmp = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888);
    var canvas = new android.graphics.Canvas(bmp);
    canvas.drawColor(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC);
    var paint = new android.graphics.Paint();
    paint.setColor(android.graphics.Color.BLACK);
    var value = 20260915;
    function rnd() { value = (value * 1103515245 + 12345) & 0x7fffffff; return value; }
    for (var i = 0; i < 900; i++) {
        var x = rnd() % (W - 40), y = rnd() % (H - 40);
        var w = 8 + rnd() % 26, h = 8 + rnd() % 26;
        canvas.drawRect(x, y, x + w, y + h, paint);
    }
    return bmp;
}

var big = com.stardust.autojs.core.image.ImageWrapper.ofBitmap(makeFullScreenBitmap());
var small = com.stardust.autojs.core.image.ImageWrapper.ofBitmap(
    android.graphics.Bitmap.createBitmap(big.getBitmap(), 200, 300, 260, 200));

console.log('BENCH|尺寸|' + W + 'x' + H + '（另有 260x200 小图）');

bench('images.getSimilarity(MSSIM) 全屏', function () {
    $images.getSimilarity(big, big, { type: 'MSSIM' });
}, 3);

bench('images.getSimilarity(PNSR) 全屏', function () {
    $images.getSimilarity(big, big, { type: 'PNSR' });
}, 3);

bench('detectAndComputeFeatures 全屏默认 scale', function () {
    var f = $images.detectAndComputeFeatures(big);
    f.recycle();
}, 3);

bench('detectAndComputeFeatures 全屏 scale=1', function () {
    var f = $images.detectAndComputeFeatures(big, { scale: 1 });
    f.recycle();
}, 2);

var sceneFeatures = $images.detectAndComputeFeatures(big);
var objectFeatures = $images.detectAndComputeFeatures(small);
console.log('BENCH|特征数|场景 ' + sceneFeatures.getCount() + ' / 目标 ' + objectFeatures.getCount());
bench('matchFeatures 全屏', function () {
    $images.matchFeatures(sceneFeatures, objectFeatures);
}, 3);
sceneFeatures.recycle();
objectFeatures.recycle();

bench('ColorMapping findMultiColors 全屏', function () {
    var mapping = $colors.mapping.singleton;
    mapping.reset(big);
    mapping.findMultiColors(-1, [[0, 0, '#000000']]);
    mapping.recycle();
}, 2);

var ocr = $ocr.create();
bench('OCR.detect 全屏', function () {
    ocr.detect(big);
}, 2);
ocr.release();

bench('automator.takeScreenshot', function () {
    var shot = automator.takeScreenshot();
    shot.recycle();
}, 2);

big.recycle();
small.recycle();
console.log('BENCH_DONE');
