// @engine rhino
// 特征匹配（PDF §2.18.2 第 164~166 页，v9.2 新增）：
//   $images.detectAndComputeFeatures(img[, options]) / $images.matchFeatures(scene, object[, options])
//   ImageFeatures.recycle() / ObjectFrame.topLeft..bottomRight / center / centerX / centerY
//
// 验证策略（全离线、确定性）：
//   1) 画一张“场景图”（几个随机黑方块当特征点）和它的一个子区域当“目标图”
//      → matchFeatures 应该找到，且四角/中心落在裁剪位置附近
//   2) 目标图来自完全不同的图案 → 应该返回 null（负向对照）
//   3) scale / grayscale / region / method / matcher / thredshold 选项都走一遍
//   4) drawMatches 写出文件（调试选项）
//
// ⚠️ 本实现不是 OpenCV 的 SIFT/ORB（这个 AAR 没有 features 模块，连 goodFeaturesToTrack 都没有），
// 用的是 cornerMinEigenVal + 自写二进制描述子，详见对照文档 §5.16 / §9。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

function info(msg) {
    console.log('INFO | ' + msg);
}

function makeBitmap(width, height, seed, blocks, blockMin, blockMax) {
    var bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
    var canvas = new android.graphics.Canvas(bmp);
    // ⚠️ 不能用 canvas.drawColor(int)（Android 12+ 的 drawColor(long) 重载会让 Rhino 走进 Color.colorSpace）
    canvas.drawColor(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC);
    var paint = new android.graphics.Paint();
    paint.setColor(android.graphics.Color.BLACK);
    paint.setAntiAlias(false);
    // 确定性伪随机方块：方块越多、越小，角点越密
    var value = seed;
    function rnd() {
        value = (value * 1103515245 + 12345) & 0x7fffffff;
        return value;
    }
    for (var i = 0; i < blocks; i++) {
        var x = rnd() % (width - blockMax);
        var y = rnd() % (height - blockMax);
        var w = blockMin + rnd() % (blockMax - blockMin);
        var h = blockMin + rnd() % (blockMax - blockMin);
        canvas.drawRect(x, y, x + w, y + h, paint);
    }
    return bmp;
}

function cropImage(bitmap, x, y, w, h) {
    return com.stardust.autojs.core.image.ImageWrapper.ofBitmap(
        android.graphics.Bitmap.createBitmap(bitmap, x, y, w, h));
}

// ---------------------------------------------------------------- 基本信息
check('$images.detectAndComputeFeatures 是函数', typeof $images.detectAndComputeFeatures, 'function');
check('$images.matchFeatures 是函数', typeof $images.matchFeatures, 'function');

var sceneBitmap = makeBitmap(900, 700, 20260915, 240, 10, 40);
var objectBitmap = makeBitmap(260, 200, 424242, 160, 8, 26);
// 把目标图贴到场景图的 (300, 220)：位置已知，可以直接断言匹配结果的中心
var sceneCanvas = new android.graphics.Canvas(sceneBitmap);
sceneCanvas.drawBitmap(objectBitmap, 300, 220, null);
var scene = com.stardust.autojs.core.image.ImageWrapper.ofBitmap(sceneBitmap);
var object = com.stardust.autojs.core.image.ImageWrapper.ofBitmap(objectBitmap);

var objectFeatures = $images.detectAndComputeFeatures(object);
check('detectAndComputeFeatures 返回 ImageFeatures', objectFeatures != null, true);
info('目标图特征数 ' + objectFeatures.getCount() + '，scale=' + objectFeatures.getScale() + '，method=' + objectFeatures.getMethod());
check('目标图算出了特征点', objectFeatures.getCount() > 10, true);
check('method 默认是 SIFT（文档默认值）', objectFeatures.getMethod(), 'SIFT');
check('小图 scale 默认 1', objectFeatures.getScale(), 1);
check('recycle() 是函数', typeof objectFeatures.recycle, 'function');

var sceneFeatures = $images.detectAndComputeFeatures(scene);
info('场景图特征数 ' + sceneFeatures.getCount() + '，scale=' + sceneFeatures.getScale());
check('大图特征点更多', sceneFeatures.getCount() > objectFeatures.getCount(), true);
check('大图（900x700）scale 默认 1', sceneFeatures.getScale(), 1);

// ---------------------------------------------------------------- 正向匹配
var frame = $images.matchFeatures(sceneFeatures, objectFeatures);
check('matchFeatures 找到目标', frame != null, true);

if (frame != null) {
    info('匹配到的四边形：' + frame.topLeft + ' / ' + frame.topRight + ' / '
        + frame.bottomRight + ' / ' + frame.bottomLeft + '，中心 ' + frame.center);
    // 目标图是从 (300, 220) 裁出来的 260x200
    var expectedCenterX = 300 + 130;
    var expectedCenterY = 220 + 100;
    check('中心 x 落在裁剪区域附近', Math.abs(frame.centerX - expectedCenterX) < 25, true);
    check('中心 y 落在裁剪区域附近', Math.abs(frame.centerY - expectedCenterY) < 25, true);
    check('center.x 与 centerX 一致', frame.center.x, frame.centerX);
    check('center.y 与 centerY 一致', frame.center.y, frame.centerY);
    check('四个角都有坐标', frame.topLeft != null && frame.topRight != null
        && frame.bottomLeft != null && frame.bottomRight != null, true);
    check('四边形的宽度接近目标图宽度', Math.abs(
        Math.hypot(frame.topRight.x - frame.topLeft.x, frame.topRight.y - frame.topLeft.y) - 260) < 40, true);
    check('四边形的高度接近目标图高度', Math.abs(
        Math.hypot(frame.bottomLeft.x - frame.topLeft.x, frame.bottomLeft.y - frame.topLeft.y) - 200) < 40, true);
}

// ---------------------------------------------------------------- 负向对照
var otherImage = com.stardust.autojs.core.image.ImageWrapper.ofBitmap(makeBitmap(260, 200, 777, 160, 8, 26));
var otherFeatures = $images.detectAndComputeFeatures(otherImage);
var noMatch = $images.matchFeatures(sceneFeatures, otherFeatures);
check('完全不同的图案匹配不到（返回 null）', noMatch, null);

// ---------------------------------------------------------------- 选项
var bigScaleFeatures = $images.detectAndComputeFeatures(scene, { scale: 0.5 });
info('scale=0.5 时场景特征数 ' + bigScaleFeatures.getCount());
check('scale=0.5 生效', bigScaleFeatures.getScale(), 0.5);
check('缩放后仍能匹配上', $images.matchFeatures(bigScaleFeatures, objectFeatures) != null, true);
bigScaleFeatures.recycle();

var regionFeatures = $images.detectAndComputeFeatures(scene, { region: [300, 220, 260, 200] });
check('region 选项生效（只算这块区域的特征）', regionFeatures.getCount() > 0, true);
check('region 的特征能匹配到', $images.matchFeatures(regionFeatures, objectFeatures) != null, true);
regionFeatures.recycle();

var grayFalse = $images.detectAndComputeFeatures(object, { grayscale: false, method: 'ORB' });
check('grayscale=false + method=ORB 不报错', grayFalse.getCount() > 0, true);
check('method=ORB 被记录', grayFalse.getMethod(), 'ORB');
grayFalse.recycle();

check('matcher=BRUTEFORCE_HAMMING 也能用',
    $images.matchFeatures(sceneFeatures, objectFeatures, { matcher: 'BRUTEFORCE_HAMMING' }) != null, true);
check('thredshold=0.5（文档拼写）也能用',
    $images.matchFeatures(sceneFeatures, objectFeatures, { thredshold: 0.5 }) != null, true);

var drawPath = '/sdcard/probe/features-matches.jpg';
files.remove(drawPath);
$images.matchFeatures(sceneFeatures, objectFeatures, { drawMatches: drawPath });
check('drawMatches 写出了调试图', files.exists(drawPath), true);

var errorType = 'no-error';
try {
    $images.detectAndComputeFeatures(scene, { scale: 'abc' });
} catch (e) {
    errorType = 'error';
}
check('非法 scale 报错', errorType, 'error');

// ---------------------------------------------------------------- recycle
objectFeatures.recycle();
sceneFeatures.recycle();
check('recycle 后 count 归零', objectFeatures.getCount(), 0);
var afterRecycle = 'no-error';
try {
    $images.matchFeatures(sceneFeatures, objectFeatures);
} catch (e) {
    afterRecycle = 'error';
}
check('recycle 后再匹配会报错', afterRecycle, 'error');
var reRecycle = 'ok';
try {
    objectFeatures.recycle();
} catch (e) {
    reRecycle = String(e);
}
check('重复 recycle 不报错', reRecycle, 'ok');

files.remove(drawPath);
console.log('FEATURES_SUMMARY|pass=' + pass + ' failed=' + fail);
