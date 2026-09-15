// @engine rhino
// OCR（PDF §2.22，第 182~185 页）：$ocr.create / OCR.detect / OCRResult.* / OCR.release
//
// 验证策略：
//   1) 用 Canvas 画一张已知文字的位图 → detect → 断言文本/可信度/范围
//   2) region / max 两个选项用“换区域/限数量”做正负对照
//   3) clickCenter() 做端到端：弹对话框（按钮文字唯一）→ 无障碍截图 → OCR 找到按钮 →
//      clickCenter() 点中 → positive 回调触发
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

function info(msg) {
    console.log('INFO | ' + msg);
}

function makeImage(lines) {
    var bmp = android.graphics.Bitmap.createBitmap(720, 360, android.graphics.Bitmap.Config.ARGB_8888);
    var canvas = new android.graphics.Canvas(bmp);
    // ⚠️ 不能用 canvas.drawColor(int)：Android 12+ 的 Canvas 有 drawColor(long) 重载，
    // Rhino 默认挑 long 版本 → 走进 Color.colorSpace(long) → "IllegalArgumentException: Invalid ID: 63"。
    // 两个参数的重载没有歧义。
    canvas.drawColor(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC);
    var paint = new android.graphics.Paint();
    paint.setColor(android.graphics.Color.BLACK);
    paint.setTextSize(56);
    paint.setAntiAlias(true);
    for (var i = 0; i < lines.length; i++) {
        canvas.drawText(lines[i], 30, 90 + i * 110, paint);
    }
    return com.stardust.autojs.core.image.ImageWrapper.ofBitmap(bmp);
}

function textsOf(results) {
    var out = [];
    for (var i = 0; i < results.length; i++) {
        out.push(String(results[i].text));
    }
    return out;
}

function findResult(results, needle) {
    for (var i = 0; i < results.length; i++) {
        if (String(results[i].text).indexOf(needle) >= 0) {
            return results[i];
        }
    }
    return null;
}

// ------------------------------------------------------------------ 基本形状
check('$ocr 模块存在', typeof $ocr, 'object');
check('$ocr.create 是函数', typeof $ocr.create, 'function');

var ocr = $ocr.create();
check('create() 返回对象', ocr != null && typeof ocr === 'object', true);
check('OCR.detect 是函数', typeof ocr.detect, 'function');
check('OCR.release 是函数', typeof ocr.release, 'function');

// ------------------------------------------------------------------ 识别已知文字
var image = makeImage(['OCR TEST 12345', 'AUTO JS PRO']);
var started = Date.now();
var results = ocr.detect(image);
info('首次 detect 用时 ' + (Date.now() - started) + 'ms，结果 ' + results.length + ' 条：' + textsOf(results).join(' / '));

check('detect 返回数组', typeof results.length, 'number');
check('至少识别出一行', results.length > 0, true);
check('识别到了第一行内容', findResult(results, '12345') != null, true);

var first = findResult(results, '12345') || results[0];
check('confidence 在 [0,1]', first.confidence >= 0 && first.confidence <= 1, true);
check('text 是字符串', typeof String(first.text), 'string');
check('bounds 有宽度', first.bounds.width() > 0, true);
check('bounds 在图片范围内', first.bounds.left >= 0 && first.bounds.right <= 720
    && first.bounds.top >= 0 && first.bounds.bottom <= 360, true);
check('rotation 为 0（ML Kit 不做旋转检测）', first.rotation, 0);
check('rotationConfidence 为 0', first.rotationConfidence, 0);
check('javaObject 不是 null', first.javaObject != null, true);

// ------------------------------------------------------------------ max 选项
var limited = ocr.detect(image, { max: 1 });
check('max=1 时只返回 1 条（有两条文字）', limited.length, 1);

// ------------------------------------------------------------------ region 选项
// 文字只画在左半边（x < 400），所以识别右半边应该什么都没找到
var leftRegion = ocr.detect(image, { region: [0, 0, 720, 200] });
check('region=上半张 还能识别到', findResult(leftRegion, '12345') != null, true);

var emptyRight = ocr.detect(image, { region: [500, 0, 220, 360] });
check('region=右半张 识别不到左边文字', findResult(emptyRight, '12345'), null);

var twoElement = ocr.detect(image, { region: [500, 0] });
check('region 只给两个元素（到右下角）也不报错', typeof twoElement.length, 'number');

// 超小区域：ML Kit 要求输入至少 32x32，传更小的不能整体报错（钳到 32）
var tiny = ocr.detect(image, { region: [10, 10, 4, 4] });
check('region 小于 32px 也不报错（自动钳到 32）', typeof tiny.length, 'number');

// ---------------------------------------------------------- clickCenter 端到端
// 弹一个按钮文字唯一的对话框，截图后 OCR 找到它并点击
var clicked = false;
var dialog = dialogs.build({ title: 'OCR clickCenter', positive: '确认点击', negative: '取消' })
    .on('positive', function () {
        clicked = true;
    });
dialog.show();
sleep(2000);

var shot = null;
try {
    // 无障碍通道截图（不弹系统授权框，与 images-contract 一致）
    images.requestScreenCapture({ mode: 'accessibility' });
    sleep(1000);
    shot = images.captureScreen();
} catch (e) {
    info('截图失败：' + e);
}
check('能拿到截图', shot != null, true);

if (shot != null) {
    var shotResults = ocr.detect(shot);
    info('截图里识别出 ' + shotResults.length + ' 条文字');
    var button = findResult(shotResults, '确认点击');
    check('在截图里找到了对话框按钮文字', button != null, true);
    if (button != null) {
        info('按钮 bounds=' + button.bounds);
        var ok = button.clickCenter();
        sleep(1000);
        check('clickCenter() 返回 true', ok, true);
        check('clickCenter() 真的点中了按钮', clicked, true);
    }
} else {
    check('在截图里找到了对话框按钮文字', false, true);
}
dialog.dismiss();

// ------------------------------------------------------------------ release
var released = $ocr.create();
released.release();
check('release() 之后 isReleased() 为 true', released.isReleased(), true);
var afterRelease = 'no-error';
try {
    released.detect(image);
} catch (e) {
    afterRelease = 'error';
}
check('release() 之后再 detect 会报错', afterRelease, 'error');

ocr.release();
console.log('OCR_SUMMARY|pass=' + pass + ' failed=' + fail);
