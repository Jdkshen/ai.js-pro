// @engine quickjs
// 裁剪、缩放、灰度与保存
// 用途：NativeFrame 的 clip / resize / scale / grayscale / save 串起来看效果
// 前置：截图授权 + 存储权限（保存到脚本目录）
// 覆盖：images / files / 手势/输入

if (!requestScreenCapture('portrait')) throw new Error('需要截图权限');

// 保存截图必须取一张全分辨率新帧；等待超过 100ms 时回退到最近有效帧。
const CAPTURE_OPTIONS = { mode: 'full', fresh: true, timeout: 100 };
const frames = [];
const timings = {};
function measured(name, action) {
    const startedAt = performance.now();
    const value = action();
    timings[name] = performance.now() - startedAt;
    console.log('[耗时] ' + name + ': ' + timings[name].toFixed(3) + ' ms');
    return value;
}
try {
    const screen = measured('captureScreenMs', function () {
        return captureScreen(CAPTURE_OPTIONS);
    });
    frames.push(screen);
    const crop = measured('clip480Ms', function () {
        return images.clip(screen, 0, 0,
            Math.min(480, screen.width), Math.min(480, screen.height));
    });
    frames.push(crop);
    const thumbnail = measured('resize240Ms', function () {
        return images.resize(crop, [240, 240], 'AREA');
    });
    frames.push(thumbnail);
    const gray = measured('grayscaleMs', function () { return images.gray(thumbnail); });
    frames.push(gray);

    const dir = files.cwd() + '/quickjs-image-output/';
    files.ensureDir(dir);
    measured('saveCropPngMs', function () { return images.save(crop, dir + 'crop.png'); });
    measured('saveThumbnailWebpMs', function () {
        return images.save(thumbnail, dir + 'thumbnail.webp', 'webp', 80);
    });
    measured('saveGrayJpgMs', function () {
        return gray.saveTo(dir + 'gray.jpg', 'jpg', 85);
    });

    const encoded = measured('compressJpgMs', function () {
        return images.compress(thumbnail, 'jpg', 70);
    });
    console.log('处理完成:', {
        captureMode: screen.captureMode,
        nativeFrame: screen.pixelWidth + 'x' + screen.pixelHeight,
        crop: crop.width + 'x' + crop.height,
        thumbnail: thumbnail.width + 'x' + thumbnail.height,
        compressedBytes: encoded.length,
        timings: timings,
        outputDirectory: dir
    });
} finally {
    for (let i = frames.length - 1; i >= 0; i--) frames[i].recycle();
}
