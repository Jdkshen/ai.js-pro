"nodejs";
// 多模板匹配(matchTemplateMulti)
// templates 数组一次调用，返回每个模板各自的最佳匹配（同源图 Mat 内循环）。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/04_matchTemplateMulti.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

const images = require('image');
try {
    const base = typeof __dirname === 'string' ? __dirname : files.cwd();
    const scene = images.readImageSync(base + '/template.png');
    const templates = [
        images.clipSync(scene, 30, 30, 24, 24),
        images.clipSync(scene, 100, 100, 32, 32),
        images.clipSync(scene, 200, 40, 40, 40)
    ];
    const started = Date.now();
    const results = scene.matchTemplateMulti(templates, { threshold: 0.72, limit: 4 });
    const ms = Date.now() - started;
    const best = results.map(function (list, index) {
        const first = list[0] || null;
        return {
            templateIndex: index,
            count: list.length,
            best: first ? { x: first.x, y: first.y, similarity: first.similarity } : null
        };
    });
    writeResult({
        ok: true, api: 'Image.matchTemplateMulti',
        scene: { width: scene.width, height: scene.height },
        templateCount: templates.length, ms, best,
        note: '返回数组与 templates 同序；每个模板只跑一次检测'
    });
    templates.forEach(function (image) { image.recycle(); });
    scene.recycle();
} catch (error) {
    writeResult({ ok: false, api: 'Image.matchTemplateMulti', error: String(error && error.stack || error) });
}
