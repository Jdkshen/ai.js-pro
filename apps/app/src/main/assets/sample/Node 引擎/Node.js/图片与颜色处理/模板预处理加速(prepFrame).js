"nodejs";
// 模板预处理加速(prepFrame)
// prepFrame 一次完成灰度/缩放，再重复 matchTemplate，对比直接匹配耗时。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/05_prepFrame.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

const images = require('image');
try {
    const base = typeof __dirname === 'string' ? __dirname : files.cwd();
    const scene = images.readImageSync(base + '/template.png');
    const template = images.clipSync(scene, 70, 70, 40, 40);
    const rounds = 3;

    // 基线：直接在原图上重复匹配。
    const directStarted = Date.now();
    let directHits = 0;
    for (let i = 0; i < rounds; i++) {
        directHits += scene.matchTemplate(template, { threshold: 0.8, limit: 4 }).length;
    }
    const directMs = Date.now() - directStarted;

    // 预处理：缩放到一半 + 灰度（needGray:false 可省灰度）；模板同步缩放。
    const prepStarted = Date.now();
    const prep = scene.prepFrame({ resize: [Math.round(scene.width / 2), Math.round(scene.height / 2)], needGray: false });
    const resizedTemplate = images.resizeSync(template, Math.round(template.width / 2), Math.round(template.height / 2));
    const prepCostMs = Date.now() - prepStarted;
    const matchStarted = Date.now();
    let prepHits = 0;
    for (let i = 0; i < rounds; i++) {
        prepHits += prep.resized.matchTemplate(resizedTemplate, { threshold: 0.72, limit: 4 }).length;
    }
    const prepMatchMs = Date.now() - matchStarted;

    writeResult({
        ok: true, api: 'Image.prepFrame',
        rounds,
        size: { width: scene.width, height: scene.height },
        prepSize: prep.size,
        direct: { totalMs: directMs, perRoundMs: directMs / rounds, hits: directHits },
        prepped: { costMs: prepCostMs, matchMs: prepMatchMs, perRoundMs: prepMatchMs / rounds, hits: prepHits },
        note: 'prepFrame 把灰度/缩放合成一次宿主调用；小图收益有限，大图/多帧循环收益更明显'
    });
    resizedTemplate.recycle();
    if (prep.gray) prep.gray.recycle();
    if (prep.resized) prep.resized.recycle();
    template.recycle(); scene.recycle();
} catch (error) {
    writeResult({ ok: false, api: 'Image.prepFrame', error: String(error && error.stack || error) });
}
