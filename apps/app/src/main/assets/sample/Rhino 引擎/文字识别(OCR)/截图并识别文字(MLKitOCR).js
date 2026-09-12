// @engine rhino
// ⚠ 需要 Auto.js Pro 专属 API：Pro 插件商店插件、ES6 模板字符串（当前 Rhino 1.7.7 不支持，请改成字符串拼接）（当前引擎暂未实现，直接运行会报错）

// 加载OCR插件，需要先在Auto.js Pro的插件商店中下载官方MLKitOCR插件
let MLKitOCR = $plugins.load('org.autojs.autojspro.plugin.mlkit.ocr');
let ocr = new MLKitOCR();

requestScreenCapture();

for (let i = 0; i < 5; i++) {
    let capture = captureScreen();

    // 检测截图文字并计算检测时间，首次检测的耗时比较长
    // 检测时间取决于图片大小、内容、文字数量
    let start = Date.now();
    let result = ocr.detect(capture);
    let end = Date.now();
    console.log(result);

    toastLog(`第${i + 1}次检测: ${end - start}ms`);
    sleep(3000);
}

ocr.release();
