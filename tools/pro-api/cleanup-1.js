// @engine rhino
// 资源释放检查 · 第 1 步：故意「用完不释放」，然后直接结束脚本。
// 配合 cleanup-2.js 使用（第二个脚本里看这些资源有没有被引擎自动清掉）。
console.log('CLEANUP1|创建前 ocr=' + $ocr.liveCount()
    + ' RootAutomator2=' + RootAutomator2.liveCount()
    + ' 泄漏检测=' + $debug.isMemoryLeakDetectionEnabled());

var ocr1 = $ocr.create();
var ocr2 = $ocr.create();
var ocr3 = $ocr.create();
var ra2 = new RootAutomator2({ adb: true });

// 文档用法：let ColorMapping = $colors.mapping; ColorMapping.singleton 是那个单例实例
var ColorMapping = $colors.mapping;
var cm = ColorMapping.singleton;
var img = com.stardust.autojs.core.image.ImageWrapper.ofBitmap(
    android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888));
cm.reset(img);
console.log('CLEANUP1|单例建好缓存，isRecycled=' + $colors.mapping.singleton.isRecycled());

// 打开图片内存泄漏检测（不改回去）
$debug.setMemoryLeakDetectionEnabled(true);

console.log('CLEANUP1|创建后 ocr=' + $ocr.liveCount()
    + ' RootAutomator2=' + RootAutomator2.liveCount()
    + ' 泄漏检测=' + $debug.isMemoryLeakDetectionEnabled());
console.log('CLEANUP1|故意不调用 release()/recycle()/exit()');
