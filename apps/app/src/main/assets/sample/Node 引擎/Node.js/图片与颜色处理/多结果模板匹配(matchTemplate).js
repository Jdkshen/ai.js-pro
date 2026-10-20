"nodejs";
// 多结果模板匹配(matchTemplate)
// threshold 放宽后一次返回多个匹配点，按相似度降序取前 N 个。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/02_matchTemplate.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

const images = require('image');
try {
    const base = typeof __dirname === 'string' ? __dirname : files.cwd();
    const scene = images.readImageSync(base + '/template.png');
    // 从场景里裁一块做模板，保证一定存在真实匹配（避免依赖外部截图）。
    const template = images.clipSync(scene, 80, 80, 40, 40);
    const started = Date.now();
    const matches = scene.matchTemplate(template, { threshold: 0.75, limit: 10 });
    const ms = Date.now() - started;
    const sorted = matches
        .map(function (item) { return { x: item.x, y: item.y, similarity: item.similarity }; })
        .sort(function (a, b) { return b.similarity - a.similarity; })
        .slice(0, 5);
    writeResult({
        ok: true,
        api: 'Image.matchTemplate',
        scene: { width: scene.width, height: scene.height },
        template: { width: template.width, height: template.height },
        threshold: 0.75,
        count: matches.length,
        top: sorted,
        ms,
        note: 'limit 控制最多返回结果数；相似度降序由脚本排序'
    });
    template.recycle();
    scene.recycle();
} catch (error) {
    writeResult({ ok: false, api: 'Image.matchTemplate', error: String(error && error.stack || error) });
}
