// @engine rhino
// 资源释放检查 · 第 2 步：在一个**新脚本**里看上一个脚本留下的资源是否已被引擎清掉。
console.log('CLEANUP2|ocr=' + $ocr.liveCount()
    + ' RootAutomator2=' + RootAutomator2.liveCount()
    + ' 泄漏检测=' + $debug.isMemoryLeakDetectionEnabled());

// ColorMapping 单例不应该还留着上一个脚本的缓存
console.log('CLEANUP2|ColorMapping 单例已回收=' + $colors.mapping.singleton.isRecycled());

// 新脚本自己建的资源在本脚本里应该是「在用」状态（证明确实是按 runtime 记账）
var ocr = $ocr.create();
console.log('CLEANUP2|本脚本创建后 ocr=' + $ocr.liveCount());
ocr.release();
console.log('CLEANUP2|release() 之后 ocr=' + $ocr.liveCount());
console.log('CLEANUP2|_DONE');
