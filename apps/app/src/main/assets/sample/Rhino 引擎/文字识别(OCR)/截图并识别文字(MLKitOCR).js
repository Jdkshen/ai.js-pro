// @engine rhino
// 用 ML Kit 识别截图里的文字（PDF §2.22）
//
// 与 Auto.js Pro 的差异：Pro 是通过插件机制加载官方 MLKitOCR 插件
// （$plugins.load('org.autojs.autojspro.plugin.mlkit.ocr') + new MLKitOCR()）；
// 本引擎把 ML Kit 内置了，直接用 $ocr 即可，不需要也不支持那个插件。
// 下面的代码就是「等价的插件用法」——把 new MLKitOCR() 换成 $ocr.create()。
let ocr = $ocr.create();

// 请求截图权限（不想弹系统授权框可用 {mode: 'accessibility'}）
requestScreenCapture();

for (let i = 0; i < 5; i++) {
    let capture = captureScreen();

    // 检测截图文字并计算检测时间，首次检测的耗时比较长
    let start = Date.now();
    let result = ocr.detect(capture);
    let end = Date.now();
    console.log(result);

    toastLog('第' + (i + 1) + '次检测: ' + (end - start) + 'ms');
    capture.recycle();
    sleep(3000);
}

ocr.release();
