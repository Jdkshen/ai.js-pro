"nodejs";
const { requestScreenCapture } = require('media_projection');
const { readImage, findImage } = require('image');
const { showToast } = require('toast');
const { delay } = require('lang');
// 默认演示 30 帧，匹配成功会提前结束；全屏大模板匹配较慢，可缩小搜索区域。
const MAX_FRAMES = 30;
async function main() {
    const template = await readImage('./template.png');
    let capturer = null;
    let frames = 0;
    let result = null;
    try {
        capturer = await requestScreenCapture();
        showToast('开始找图');
        while (frames < MAX_FRAMES) {
            let capture = null;
            try {
                capture = await capturer.nextImage();
                frames++;
                result = await findImage(capture, template);
                if (result) { console.log('findImage:', result); break; }
            } finally {
                if (capture) capture.recycle();
            }
            await delay(50);
        }
        console.log('NODE_FIND_IMAGE_DONE frames=' + frames + ' matched=' + !!result);
        showToast(result ? '已找到模板' : '本轮未找到模板');
    } finally {
        template.recycle();
        if (capturer) capturer.stop();
    }
}
main().catch(console.error);
