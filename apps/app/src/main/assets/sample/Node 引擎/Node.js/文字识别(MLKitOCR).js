"nodejs";
// 使用应用内置 ML Kit 后端，无需 Auto.js Pro 独占插件。
const { createOCR } = require('ocr');
const { requestScreenCapture } = require('media_projection');
const { delay } = require('lang');
const { showToast } = require('toast');
async function main() {
    console.log('正在初始化 OCR...');
    const ocr = await createOCR({ backend: 'mlkit' });
    console.log('OCR 已初始化，正在申请截图...');
    let capturer = null;
    try {
        capturer = await requestScreenCapture();
        console.log('截图通道已就绪');
        for (let i = 0; i < 5; i++) {
            let capture = null;
            try {
                capture = await capturer.nextImage();
                const start = Date.now();
                const blocks = await ocr.detect(capture);
                console.log(blocks);
                showToast(`第${i + 1}次检测: ${Date.now() - start}ms`);
            } finally {
                if (capture) capture.recycle();
            }
            await delay(1000);
        }
        console.log('NODE_OCR_SAMPLE_OK backend=mlkit frames=5');
    } finally {
        ocr.release();
        if (capturer) capturer.stop();
    }
}
main().catch(console.error);
