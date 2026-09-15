// @engine rhino
// ColorMapping 行为验收（对照 335 页 PDF §2.18.8 第 171~174 页）
// 跑法见 tools/pro-api/README.md。全部用内存里造的图片，不依赖截图权限。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

// 造一张 40x30 的图：黑底 + 两个品红点 + 一个白点 + 一个"接近品红"的点
// 注意：不能依赖 bitmap.eraseColor()（真机上实测不生效，未涂色处会是白的），这里逐像素填。
var bmp = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888);
for (var fy = 0; fy < 30; fy++) {
    for (var fx = 0; fx < 40; fx++) {
        bmp.setPixel(fx, fy, android.graphics.Color.BLACK);
    }
}
var magenta = android.graphics.Color.parseColor('#ff00ff');
var nearMagenta = android.graphics.Color.parseColor('#f800f8');
var white = android.graphics.Color.parseColor('#ffffff');
bmp.setPixel(5, 6, magenta);
bmp.setPixel(7, 8, nearMagenta);
bmp.setPixel(20, 10, magenta);
bmp.setPixel(30, 20, white);
var img = Image.ofBitmap(bmp);
check("造图成功", img.getWidth() + 'x' + img.getHeight(), '40x30');
check("背景真的是黑的", img.pixel(15, 16), android.graphics.Color.BLACK);
check("涂色点正确", img.pixel(5, 6), magenta);
check("$colors 包装没弄丢其他成员", typeof $colors.parseColor, 'function');

// 1) 文档用法：let ColorMapping = $colors.mapping; let cm = ColorMapping.singleton;
var ColorMapping = $colors.mapping;
check("$colors.mapping 存在", ColorMapping != undefined && ColorMapping != null, true);
check("$colors.mapping.singleton 存在", ColorMapping.singleton != undefined, true);
check("singleton 已实现接口", typeof ColorMapping.singleton.reset, 'function');

var cm = new ColorMapping();
check("new ColorMapping() 可用", typeof cm.findColor, 'function');
check("未 reset 时找色会报错", (function () {
    try {
        cm.findColor('#ff00ff');
        return 'no-throw';
    } catch (e) {
        return ('' + e).indexOf('reset') >= 0 ? 'ok' : ('' + e);
    }
})(), 'ok');

// 2) reset + findColor（默认 threshold = 4 → #f800f8 与 #ff00ff 差 7，不该被先命中）
cm.reset(img);
check("reset 后 isRecycled = false", cm.isRecycled(), false);
var first = cm.findColor('#ff00ff');
check("findColor 命中第一个点(5,6)", first ? (first.x + ',' + first.y) : 'null', '5,6');

// 3) region：跳过前面的点
var inRegion = cm.findColor('#ff00ff', { region: [10, 0, 40, 30] });
check("region 生效", inRegion ? (inRegion.x + ',' + inRegion.y) : 'null', '20,10');

// 4) threshold / similarity
var box = { region: [7, 8, 1, 1] };
check("threshold=0 不匹配近似色", cm.findColor('#ff00ff', { region: [7, 8, 1, 1], threshold: 0 }) === null, true);
var nearHit = cm.findColor('#ff00ff', { region: [7, 8, 1, 1], threshold: 8 });
check("threshold=8 匹配近似色", nearHit ? (nearHit.x + ',' + nearHit.y) : 'null', '7,8');
var simHit = cm.findColor('#ff00ff', { region: [7, 8, 1, 1], similarity: 0.97 });
check("similarity=0.97 等效 threshold=8", simHit ? (simHit.x + ',' + simHit.y) : 'null', '7,8');

// 5) findAllPointsForColor
var all = cm.findAllPointsForColor('#ff00ff');
check("findAllPointsForColor 两个精确点", all ? all.length : 'null', 2);
// threshold=0 时 #010203 和黑底不相等，1x1 区域里找不到
check("全部找不到时返回 null", cm.findAllPointsForColor('#010203', { region: [0, 0, 1, 1], threshold: 0 }), null);

// 6) findMultiColors：以 (20,10) 为第一个点，+10,+10 处是白色
var multi = cm.findMultiColors('#ff00ff', [[10, 10, '#ffffff']]);
check("findMultiColors 命中(20,10)", multi ? (multi.x + ',' + multi.y) : 'null', '20,10');
check("findMultiColors 偏移对不上返回 null",
    cm.findMultiColors('#ff00ff', [[1, 1, '#ffffff']]), null);

// 7) 单例可用
ColorMapping.singleton.reset(img);
check("singleton 可直接 resize 后找色",
    ColorMapping.singleton.findColor('#ffffff') ? 'hit' : 'miss', 'hit');
ColorMapping.singleton.recycle();

// 8) recycle
cm.recycle();
check("recycle 后 isRecycled = true", cm.isRecycled(), true);
check("recycle 后找色报错", (function () {
    try {
        cm.findColor('#ff00ff');
        return 'no-throw';
    } catch (e) {
        return ('' + e).indexOf('reset') >= 0 ? 'ok' : ('' + e);
    }
})(), 'ok');

console.log('COLORMAPPING_SUMMARY|pass=' + pass + ' failed=' + fail);
