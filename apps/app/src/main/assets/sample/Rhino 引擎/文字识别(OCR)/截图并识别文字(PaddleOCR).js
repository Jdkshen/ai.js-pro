// @engine rhino
// 创建 OCR 对象，把截图里的文字识别出来（PDF §2.22）
//
// 与 Auto.js Pro 的差异：Pro 需要先在插件商店下载官方 PaddleOCR 插件；
// 本引擎**内置了 ML Kit 中文文字识别**（离线），所以 $ocr 开箱即用。
// 因此文档里的 models / labelsFile / cpuPowerMode / 线程数是 PaddleOCR 专用参数，
// 传了也不生效（接口接受，不会报错）。
let ocr = $ocr.create({
    models: 'slim', // PaddleOCR 专用参数，本引擎忽略
});

// 请求截图权限。不想弹系统授权框可以改用 requestScreenCapture({mode: 'accessibility'})
// （需要先开启本应用的无障碍服务；系统录屏在跑时也建议用这个模式）
requestScreenCapture();

for (let i = 0; i < 5; i++) {
    let capture = captureScreen();

    // 检测截图文字并计算检测时间，首次检测的耗时比较长
    // 检测时间取决于图片大小、内容、文字数量
    let start = Date.now();
    let result = ocr.detect(capture);
    let end = Date.now();

    // 遍历结果，打印文本与可信度（result 是数组，可以直接 forEach/filter/find）
    result.forEach(function (item) {
        console.log(item.text + '  confidence=' + item.confidence + '  bounds=' + item.bounds);
    });

    // 只要可信度 0.9 以上的
    let filtered = result.filter(function (item) {
        return item.confidence > 0.9;
    });
    console.log('识别到 ' + result.length + ' 行文字，其中高可信度 ' + filtered.length + ' 行');

    toastLog('第' + (i + 1) + '次检测: ' + (end - start) + 'ms，共 ' + result.length + ' 行');
    capture.recycle();
    sleep(3000);
}

ocr.release();
