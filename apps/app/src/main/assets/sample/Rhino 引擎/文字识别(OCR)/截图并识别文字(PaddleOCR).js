"rhino";
// 创建OCR对象；默认后端是内置 ML Kit，这里明确使用随包的 PP-OCRv6 tiny 后端（只随包 arm64-v8a）。
// models 只接受包含固定模型文件的绝对目录；'slim' 这类档位名不会生效，故不传。
let ocr = $ocr.create({
    backend: 'ppocr',
});

requestScreenCapture();

for (let i = 0; i < 5; i++) {
    let capture = captureScreen();

    // 检测截图文字并计算检测时间，首次检测的耗时比较长
    // 检测时间取决于图片大小、内容、文字数量
    // 可通过调整$ocr.create()的线程、CPU模式等参数调整检测效率
    let start = Date.now();
    let result = ocr.detect(capture);
    let end = Date.now();
    console.log(result);

    toastLog(`第${i + 1}次检测: ${end - start}ms`);
    sleep(3000);
}

ocr.release();
