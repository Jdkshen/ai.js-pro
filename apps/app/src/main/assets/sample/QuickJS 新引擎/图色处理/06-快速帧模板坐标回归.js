// @engine quickjs

if (!requestScreenCapture('portrait')) throw new Error('需要截图权限');

let full = null;
let template = null;
let fast = null;
try {
    // 从全分辨率画面裁一块模板，再交给 720p 快速帧匹配。
    // 桥接层会自动缩放模板，并把结果映射回原屏幕坐标。
    full = captureScreen({ mode: 'full', fresh: false });
    // 使用跨屏宽的大区域，避免纯色壁纸小块在多个位置产生相同的 100% 匹配。
    const width = full.width;
    const height = Math.floor(full.height / 3);
    const x = 0;
    const y = Math.floor(full.height / 3);
    template = images.clip(full, x, y, width, height);

    // 两次都读取同一批缓存画面，避免页面变化干扰坐标回归。
    fast = captureScreen({ mode: 'fast', size: 720, fresh: false });
    const options = {
        region: [0, Math.max(0, y - 40), width, Math.min(full.height - y + 40, height + 80)],
        threshold: 0.75
    };

    const baseline = images.findImage(full, template, options);
    if (!baseline) throw new Error('全分辨率模板基准失败');

    const startedAt = performance.now();
    const point = images.findImage(fast, template, options);
    const findElapsedMs = performance.now() - startedAt;
    if (!point || Math.abs(point.x - baseline.x) > 3 || Math.abs(point.y - baseline.y) > 6) {
        throw new Error('快速帧模板坐标映射失败: ' + JSON.stringify(point));
    }

    const matchStartedAt = performance.now();
    const matches = images.matchTemplate(fast, template, {
        region: options.region,
        threshold: options.threshold,
        max: 3
    });
    const matchElapsedMs = performance.now() - matchStartedAt;
    if (!matches.matches.length) throw new Error('快速帧多结果模板匹配失败');

    console.log('FAST_TEMPLATE_MAPPING_OK', {
        fullResolutionBaseline: baseline,
        actual: point,
        logicalFrame: fast.width + 'x' + fast.height,
        nativeFrame: fast.pixelWidth + 'x' + fast.pixelHeight,
        findImageMs: Number(findElapsedMs.toFixed(3)),
        matchTemplateMs: Number(matchElapsedMs.toFixed(3)),
        matches: matches.matches.length
    });
} finally {
    if (fast) fast.recycle();
    if (template) template.recycle();
    if (full) full.recycle();
}
