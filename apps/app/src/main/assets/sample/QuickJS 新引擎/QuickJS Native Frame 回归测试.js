// @engine quickjs

if (!requestScreenCapture('portrait')) {
    throw new Error('用户取消了屏幕捕获授权');
}

function runMode(name, options, count) {
    let captured = 0;
    const startedAt = performance.now();
    for (let i = 0; i < count; i++) {
        const frame = captureScreen(options);
        try {
            const x = Math.floor(frame.width / 2);
            const y = Math.floor(frame.height / 2);
            const centerColor = images.pixel(frame, x, y);
            const point = images.findColor(frame, centerColor, {
                threshold: 0,
                region: [x, y, 1, 1]
            });
            if (!point || Math.abs(point.x - x) > 2 || Math.abs(point.y - y) > 2) {
                throw new Error(name + ' 原生找色/坐标映射回归失败: ' + JSON.stringify(point));
            }
            captured++;
        } finally {
            frame.recycle();
        }
    }
    const elapsed = performance.now() - startedAt;
    console.log('NATIVE_FRAME_MODE_OK', {
        mode: name,
        frames: captured,
        elapsedMs: Number(elapsed.toFixed(3)),
        averageMs: Number((elapsed / captured).toFixed(3))
    });
}

runMode('full', { mode: 'full' }, 10);
runMode('fast720', { mode: 'fast', size: 720 }, 10);
runMode('fast640', { mode: 'fast', size: 640 }, 10);
console.log('NATIVE_FRAME_LOOP_OK');
