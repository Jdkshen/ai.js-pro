"nodejs";
// 批量像素读取(getPixels)
// 一次宿主调用读取整块像素（argb/gray），对比逐像素 pixel() 的调用开销。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/01_getPixels.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

const images = require('image');
try {
    const base = typeof __dirname === 'string' ? __dirname : files.cwd();
    const image = images.readImageSync(base + '/template.png');
    const size = images.sizeOf(image);
    const region = [0, 0, Math.min(48, size.width), Math.min(48, size.height)];

    // argb：4 字节/像素，适合直接做颜色判断；gray：1 字节/像素，适合匹配/OCR。
    const argbStarted = Date.now();
    const argb = image.getPixels({ format: 'argb', region: region });
    const argbMs = Date.now() - argbStarted;
    const grayStarted = Date.now();
    const gray = image.getPixels({ format: 'gray', region: region });
    const grayMs = Date.now() - grayStarted;

    // 逐像素对照：只取 4x4 个点，说明「桥调用次数」的差异。
    const pixelStarted = Date.now();
    const pixels = [];
    for (let y = 0; y < 4; y++) {
        for (let x = 0; x < 4; x++) pixels.push(image.pixel(x, y));
    }
    const pixelMs = Date.now() - pixelStarted;

    writeResult({
        ok: true,
        api: 'Image.getPixels',
        image: { width: size.width, height: size.height },
        region,
        argb: {
            width: argb.width, height: argb.height, format: argb.format,
            bytes: argb.data.length, firstBytes: Array.from(argb.data.slice(0, 8)), ms: argbMs
        },
        gray: {
            width: gray.width, height: gray.height, format: gray.format,
            bytes: gray.data.length, firstValues: Array.from(gray.data.slice(0, 8)), ms: grayMs
        },
        pixelCompare: {
            count: pixels.length, ms: pixelMs,
            note: 'pixel() 每个点一次宿主调用；getPixels 一次取回整块区域，适合批量分析'
        }
    });
    image.recycle();
} catch (error) {
    writeResult({ ok: false, api: 'Image.getPixels', error: String(error && error.stack || error) });
}
