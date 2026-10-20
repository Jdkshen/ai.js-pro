"nodejs";
// SIFT快速匹配(matchSiftFast)
// 半分辨率优先匹配，未命中自动回退全分辨率；与 matchSift 结果/耗时对照。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/06_matchSiftFast.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

const images = require('image');
try {
    const base = typeof __dirname === 'string' ? __dirname : files.cwd();
    const scene = images.readImageSync(base + '/hellokitty.jpg');
    const template = images.clipSync(scene, 40, 30, Math.min(120, scene.width - 40), Math.min(120, scene.height - 30));

    const fastStarted = Date.now();
    const fast = scene.matchSiftFast(template, { ratio: 0.7 });
    const fastMs = Date.now() - fastStarted;

    const normalStarted = Date.now();
    const normal = scene.matchSift(template, { ratio: 0.7 });
    const normalMs = Date.now() - normalStarted;

    writeResult({
        ok: true, api: 'Image.matchSiftFast',
        scene: { width: scene.width, height: scene.height },
        template: { width: template.width, height: template.height },
        fast: {
            matched: !!fast.matched, count: fast.count, ms: fastMs,
            totalMs: fast.totalMs, fallback: !!fast.fallback,
            center: fast.center || null
        },
        normal: { matched: !!normal.matched, count: normal.count, ms: normalMs, center: normal.center || null },
        note: 'matchSiftFast=先 0.5 倍缩放检测，无匹配点再回退 1.0 倍；matchSift 只跑一次指定缩放'
    });
    template.recycle(); scene.recycle();
} catch (error) {
    writeResult({ ok: false, api: 'Image.matchSiftFast', error: String(error && error.stack || error) });
}
