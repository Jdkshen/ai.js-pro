// @engine quickjs
// 截图首帧与稳定耗时测试
// 用途：测量首帧、稳定帧与 fresh 模式的耗时分布
// 前置：截图授权
// 覆盖：—

if (!requestScreenCapture('portrait')) {
    throw new Error('需要先授权屏幕捕获');
}

const ROUNDS = 15;

function captureOnce(options) {
    const startedAt = performance.now();
    const frame = captureScreen(options);
    const elapsedMs = performance.now() - startedAt;
    const dimensions = frame.width + 'x' + frame.height +
        '（Native ' + frame.pixelWidth + 'x' + frame.pixelHeight + '）';
    frame.recycle();
    return { elapsedMs: elapsedMs, dimensions: dimensions };
}

function percentile(sorted, ratio) {
    const index = Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1);
    return sorted[Math.max(0, index)];
}

function summarize(name, values, dimensions) {
    const sorted = values.slice().sort(function (left, right) { return left - right; });
    const total = values.reduce(function (sum, value) { return sum + value; }, 0);
    console.log('[' + name + '] 尺寸: ' + dimensions);
    console.log('[' + name + '] 每帧: ' + values.map(function (value) {
        return value.toFixed(3);
    }).join(', ') + ' ms');
    console.log('[' + name + '] 平均: ' + (total / values.length).toFixed(3) +
        ' ms，最小: ' + sorted[0].toFixed(3) +
        ' ms，P95: ' + percentile(sorted, 0.95).toFixed(3) +
        ' ms，最大: ' + sorted[sorted.length - 1].toFixed(3) + ' ms');
}

function benchmark(name, options, paced) {
    // 不把本模式的首次 Mat 分配计入稳定数据。
    sleep(30);
    captureOnce(options);

    const values = [];
    let dimensions = '';
    for (let i = 0; i < ROUNDS; i++) {
        // 等待 ImageReader 预先缓存一帧，主要观察取帧、复制和缩放成本。
        if (paced) sleep(20);
        const result = captureOnce(options);
        dimensions = result.dimensions;
        values.push(result.elapsedMs);
    }
    summarize(name, values, dimensions);
}

console.log('========== 截图首帧测试 ==========');
const cold = captureOnce({ mode: 'full', fresh: false });
console.log('[首次截图] ' + cold.dimensions + '，耗时: ' + cold.elapsedMs.toFixed(3) + ' ms');

console.log('========== 缓存帧/业务调用测试 ==========');
benchmark('全分辨率-缓存帧', { mode: 'full', fresh: false }, true);
benchmark('720p-缓存帧', { mode: 'fast', size: 720, fresh: false }, true);
benchmark('640p-缓存帧', { mode: 'fast', size: 640, fresh: false }, true);

console.log('========== 连续截图吞吐测试 ==========');
benchmark('全分辨率-连续', { mode: 'full', fresh: false }, false);
benchmark('720p-连续', { mode: 'fast', size: 720, fresh: false }, false);
benchmark('640p-连续', { mode: 'fast', size: 640, fresh: false }, false);

console.log('========== 等待新帧测试（最多 100ms） ==========');
benchmark('全分辨率-新帧', { mode: 'full', fresh: true, timeout: 100 }, false);
benchmark('720p-新帧', { mode: 'fast', size: 720, fresh: true, timeout: 100 }, false);

console.log('测试完成：默认模式立即复用最新缓存；fresh 模式最多等待 timeout 毫秒，超时自动回退最近有效帧。');
