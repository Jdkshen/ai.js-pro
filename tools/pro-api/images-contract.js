// @engine rhino
// images.getSimilarity / images.detectsMultiColors / $images.getScreenCaptureOptions / $images.stopScreenCapture
// （对照 335 页 PDF §2.18.1 第 151 页、§2.18.2 第 154、160 页）
// 跑法见 tools/pro-api/README.md。用内存里造的图片 + 真实申请一次截图权限。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

// 造图工具：逐像素填（真机上 eraseColor 不生效）
function makeImage(width, height, color) {
    var bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
    for (var y = 0; y < height; y++) {
        for (var x = 0; x < width; x++) {
            bmp.setPixel(x, y, color);
        }
    }
    return { bmp: bmp, image: Image.ofBitmap(bmp) };
}

var black = android.graphics.Color.BLACK;
var white = android.graphics.Color.WHITE;
var magenta = android.graphics.Color.parseColor('#ff00ff');

// 1) getSimilarity：同一张图
var base = makeImage(32, 32, black);
var same = makeImage(32, 32, black);
check("MSSIM 默认算法 = 1", images.getSimilarity(base.image, same.image), 1);
check("type=PNSR 时相同图为 Infinity",
    images.getSimilarity(base.image, same.image, { type: 'PNSR' }), Infinity);
check("type=MSSIM 显式指定", images.getSimilarity(base.image, same.image, { type: 'MSSIM' }), 1);

// 不同图：MSSIM 应 < 1，PSNR 应 < 30（文档：PSNR 小于 30 就算比较相似）
var whiteImage = makeImage(32, 32, white);
var mssimDiff = images.getSimilarity(base.image, whiteImage.image);
var psnrDiff = images.getSimilarity(base.image, whiteImage.image, { type: 'Psnr' });
check("黑白图的 MSSIM < 1", mssimDiff < 1, true);
check("黑白图的 PSNR 有限且 < 30", psnrDiff < 30, true);
console.log('INFO | 黑白图 MSSIM=' + mssimDiff + ' PSNR=' + psnrDiff);

var unknownAlgorithm = '';
try {
    images.getSimilarity(base.image, whiteImage.image, { type: 'nope' });
} catch (e) {
    unknownAlgorithm = '' + e;
}
check("未知算法报错", unknownAlgorithm.indexOf('不支持的相似度算法') >= 0, true);

// 2) detectsMultiColors
var target = makeImage(40, 30, black);
var img = target.image;
img.pixel(10, 10);                       // 触发 mat 初始化
target.bmp.setPixel(10, 10, magenta);
target.bmp.setPixel(13, 14, white);
target.bmp.setPixel(18, 20, magenta);
var picture = Image.ofBitmap(target.bmp);

check("起点与偏移都匹配 → true",
    images.detectsMultiColors(picture, 10, 10, '#ff00ff', [[3, 4, '#ffffff'], [8, 10, '#ff00ff']]), true);
check("起点颜色不符 → false",
    images.detectsMultiColors(picture, 11, 10, '#ff00ff', [[3, 4, '#ffffff']]), false);
check("偏移颜色不符 → false",
    images.detectsMultiColors(picture, 10, 10, '#ff00ff', [[3, 4, '#000000']]), false);
check("超出图片范围 → false",
    images.detectsMultiColors(picture, 39, 29, '#ff00ff', [[3, 4, '#ffffff']]), false);
check("threshold=0 时近似色不算命中",
    images.detectsMultiColors(picture, 10, 10, '#ff00ff', [[3, 4, '#f8f8f8']], { threshold: 0 }), false);
check("threshold=10 时近似色命中",
    images.detectsMultiColors(picture, 10, 10, '#ff00ff', [[3, 4, '#f8f8f8']], { threshold: 10 }), true);

// 3) getScreenCaptureOptions / stopScreenCapture
// 用「无障碍（录屏兼容）」通道申请，原因：MediaProjection 弹窗需要人点（Android 15 是
// 「下一步」→「选应用/整个屏幕」→「开始共享」），不适合放进自动化契约；两条通道对
// getScreenCaptureOptions / stopScreenCapture 的语义是一样的。
$images.stopScreenCapture();     // 先确保没权限
check("未申请权限时返回 null", $images.getScreenCaptureOptions(), null);

var granted = $images.requestScreenCapture({ mode: 'accessibility' });
console.log('INFO | requestScreenCapture({mode:accessibility}) = ' + granted);
check("无障碍通道申请成功", granted, true);
if (granted) {
    var options = $images.getScreenCaptureOptions();
    check("申请后有配置对象", options !== null && typeof options === 'object', true);
    check("orientation 是 0/1/2 之一", [0, 1, 2].indexOf(options.orientation) >= 0, true);
    check("width/height 为正整数", options.width > 0 && options.height > 0, true);
    check("density 为正整数", options.density > 0, true);
    check("async 是 boolean", typeof options.async, 'boolean');
    console.log('INFO | options = ' + JSON.stringify({
        width: options.width, height: options.height,
        orientation: options.orientation, density: options.density, async: options.async
    }));
    var captured = $images.captureScreen();
    check("申请后能截图", captured != null, true);
    $images.stopScreenCapture();
    check("释放后 getScreenCaptureOptions 返回 null", $images.getScreenCaptureOptions(), null);
} else {
    check("申请后有配置对象", 'capture-denied', true);
    check("orientation 是 0/1/2 之一", 'capture-denied', true);
    check("width/height 为正整数", 'capture-denied', true);
    check("density 为正整数", 'capture-denied', true);
    check("async 是 boolean", 'capture-denied', true);
    check("申请后能截图", 'capture-denied', true);
    check("释放后 getScreenCaptureOptions 返回 null", 'capture-denied', true);
}

console.log('IMAGES_SUMMARY|pass=' + pass + ' failed=' + fail);
