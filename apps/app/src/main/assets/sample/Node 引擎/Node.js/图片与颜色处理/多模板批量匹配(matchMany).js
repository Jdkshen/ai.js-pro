"nodejs";
// 多模板批量匹配(matchMany)
// windows × templates 一次宿主调用完成，输出每个窗口的最佳结果与总耗时。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/03_matchMany.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

const images = require('image');
try {
    const base = typeof __dirname === 'string' ? __dirname : files.cwd();
    const scene = images.readImageSync(base + '/template.png');
    const templateA = images.clipSync(scene, 20, 20, 32, 32);
    const templateB = images.clipSync(scene, 120, 60, 40, 40);
    const windows = [
        [0, 0, 220, 220],
        [100, 40, 220, 220]
    ];
    const started = Date.now();
    const results = scene.matchMany(windows, [templateA, templateB], { threshold: 0.7, limit: 3 });
    const ms = Date.now() - started;
    // results[windowIndex][templateIndex] = 匹配数组（坐标是源图坐标系）。
    const summary = results.map(function (perTemplate, w) {
        return perTemplate.map(function (list, t) {
            const best = list[0] || null;
            return {
                window: windows[w], templateIndex: t,
                best: best ? { x: best.x, y: best.y, similarity: best.similarity } : null
            };
        });
    });
    writeResult({
        ok: true, api: 'Image.matchMany',
        windows: windows.length, templates: 2, ms,
        summary,
        note: '每个窗口独立返回 × 每个模板的匹配列表；宿主已把窗口偏移加回源图坐标'
    });
    templateA.recycle(); templateB.recycle(); scene.recycle();
} catch (error) {
    writeResult({ ok: false, api: 'Image.matchMany', error: String(error && error.stack || error) });
}
