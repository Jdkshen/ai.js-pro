// @engine quickjs

if (!requestScreenCapture('portrait')) {
    throw new Error('用户取消了屏幕捕获授权');
}

let captured = 0;
const startedAt = Date.now();
for (let i = 0; i < 20; i++) {
    const frame = captureScreen();
    try {
        const x = Math.floor(frame.width / 2);
        const y = Math.floor(frame.height / 2);
        const centerColor = images.pixel(frame, x, y);
        const point = images.findColor(frame, centerColor, {
            threshold: 0,
            region: [Math.max(0, x - 4), Math.max(0, y - 4), 9, 9]
        });
        if (!point) throw new Error('原生找色回归失败');
        captured++;
    } finally {
        frame.recycle();
    }
}

const elapsed = Date.now() - startedAt;
console.log('NATIVE_FRAME_LOOP_OK', {
    frames: captured,
    elapsedMs: elapsed,
    averageMs: elapsed / captured
});
