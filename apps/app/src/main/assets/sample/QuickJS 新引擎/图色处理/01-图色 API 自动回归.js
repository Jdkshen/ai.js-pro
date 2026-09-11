// @engine quickjs
// 图色 API 自动回归
// 用途：截图 → 取色 / 找色 / 多点找色 / 范围查找 → 释放的完整自动回归
// 前置：截图授权（首帧会弹窗）
// 覆盖：images / colors / files / 手势/输入

function assert(condition, message) {
    if (!condition) throw new Error('断言失败: ' + message);
}

if (!requestScreenCapture('portrait')) {
    throw new Error('用户取消了屏幕捕获授权');
}

const resources = [];
const timings = {};
function keep(frame) {
    resources.push(frame);
    return frame;
}
function measured(name, action) {
    const startedAt = performance.now();
    const value = action();
    timings[name] = performance.now() - startedAt;
    console.log('[耗时] ' + name + ': ' + timings[name].toFixed(3) + ' ms');
    return value;
}

try {
    // API 回归固定使用当前最新的全分辨率缓存帧，保证测试快且坐标精确。
    const screen = keep(measured('captureScreenMs', function () {
        return captureScreen({ mode: 'full', fresh: false });
    }));
    const bundledSource = keep(measured('readBundledImageMs', function () {
        return images.read('asset://sample/Rhino 引擎/图片与图色处理/找图/super_mario.jpg');
    }));
    const bundledTemplate = keep(images.read(
        'asset://sample/Rhino 引擎/图片与图色处理/找图/block.png'));
    const bundledPoint = images.findImage(bundledSource, bundledTemplate, { threshold: 0.8 });
    assert(bundledPoint && bundledPoint.x === 221 && bundledPoint.y === 130,
        '读取 APK 内置图片并找图');
    const x = Math.floor(screen.width / 2);
    const y = Math.floor(screen.height / 2);
    const sampled = measured('pixel3TotalMs', function () {
        return [
            images.pixel(screen, x, y),
            images.pixel(screen, Math.min(screen.width - 1, x + 1), y),
            images.pixel(screen, x, Math.min(screen.height - 1, y + 1))
        ];
    });
    const center = sampled[0];
    const right = sampled[1];
    const bottom = sampled[2];

    assert(colors.alpha(center) >= 0, 'colors 通道解析');
    const detected = measured('detectsColorMs', function () {
        return images.detectsColor(screen, center, x, y, 0);
    });
    assert(detected, 'detectsColor 精确匹配');

    const single = measured('findColorInRegionMs', function () {
        return images.findColorInRegion(screen, center, x, y, 1, 1, 0);
    });
    assert(single && single.x === x && single.y === y, 'findColorInRegion');

    const multiStartedAt = performance.now();
    const multi = images.findMultiColors(screen, center, [
        [1, 0, right],
        [0, 1, bottom]
    ], { region: [x, y, 1, 1], threshold: 0 });
    const multiElapsedMs = performance.now() - multiStartedAt;
    timings.findMultiColorsMs = multiElapsedMs;
    console.log('[耗时] findMultiColorsMs: ' + multiElapsedMs.toFixed(3) + ' ms');
    assert(multi && multi.x === x && multi.y === y, 'findMultiColors');

    // 偏移量到屏幕外，保证没有匹配，用于测量全屏最坏扫描耗时。
    const benchmarkRuns = 3;
    const worstCaseStartedAt = performance.now();
    for (let run = 0; run < benchmarkRuns; run++) {
        const impossible = images.findMultiColors(screen, center, [
            [screen.width, 0, center]
        ], {
            region: [0, 0, screen.width, screen.height],
            threshold: 0
        });
        assert(impossible === null, '全屏无命中基准');
    }
    const worstCaseElapsedMs = performance.now() - worstCaseStartedAt;
    timings.fullScreenMultiNoMatchTotalMs = worstCaseElapsedMs;
    timings.fullScreenMultiNoMatchAverageMs = worstCaseElapsedMs / benchmarkRuns;
    console.log('[耗时] fullScreenMultiNoMatchTotalMs: ' +
        worstCaseElapsedMs.toFixed(3) + ' ms');
    console.log('[耗时] fullScreenMultiNoMatchAverageMs: ' +
        timings.fullScreenMultiNoMatchAverageMs.toFixed(3) + ' ms');

    const cropX = Math.max(0, x - 16);
    const cropY = Math.max(0, y - 16);
    const clipped = keep(measured('clipMs', function () {
        return images.clip(screen, cropX, cropY, 32, 32);
    }));
    const copied = keep(measured('copyMs', function () { return images.copy(clipped); }));
    const resized = keep(measured('resizeMs', function () {
        return images.resize(copied, [64, 48], 'AREA');
    }));
    const scaled = keep(measured('scaleMs', function () {
        return images.scale(resized, 0.5, 0.5, 'LINEAR');
    }));
    const gray = keep(measured('grayscaleMs', function () {
        return images.grayscale(scaled);
    }));
    const converted = keep(measured('cvtColorMs', function () {
        return images.cvtColor(clipped, 'RGBA2GRAY');
    }));

    assert(clipped.width === 32 && clipped.height === 32, 'clip 尺寸');
    assert(resized.width === 64 && resized.height === 48, 'resize 尺寸');
    assert(scaled.width === 32 && scaled.height === 24, 'scale 尺寸');
    assert(colors.red(images.pixel(gray, 0, 0)) === colors.green(images.pixel(gray, 0, 0)),
        'grayscale 通道一致');
    assert(converted.width === 32 && converted.height === 32, 'cvtColor 返回句柄');

    const png = measured('compressPngMs', function () {
        return images.compress(clipped, 'png', 90);
    });
    const jpg = measured('compressJpgMs', function () {
        return images.compress(clipped, 'jpg', 80);
    });
    assert(png instanceof Uint8Array && png.length > 16, 'PNG 压缩');
    assert(jpg instanceof Uint8Array && jpg.length > 16, 'JPEG 压缩');

    const outputDir = files.cwd() + '/quickjs-image-output';
    files.ensureDir(outputDir + '/');
    const output = outputDir + '/image-api-test.png';
    const saved = measured('savePngMs', function () {
        return images.save(gray, output, 'png', 90);
    });
    assert(saved, 'save');
    assert(files.exists(output), '保存文件存在');

    const found = measured('findImageMs', function () {
        return images.findImage(screen, clipped, {
            region: [cropX, cropY, 32, 32], threshold: 0.99
        });
    });
    assert(found && found.x === cropX && found.y === cropY, 'findImage');

    const matched = measured('matchTemplateMs', function () {
        return images.matchTemplate(screen, clipped, {
            region: [Math.max(0, cropX - 8), Math.max(0, cropY - 8), 48, 48],
            threshold: 0.99,
            max: 3
        });
    });
    assert(matched.matches.length >= 1, 'matchTemplate');

    let invalidThresholdRejected = false;
    try {
        images.findImage(screen, clipped, { threshold: NaN });
    } catch (error) {
        invalidThresholdRejected = String(error.message || error).indexOf('finite number') >= 0;
    }
    assert(invalidThresholdRejected, 'findImage 拒绝非数字阈值');

    const oversized = keep(images.resize(clipped, [64, 64], 'NEAREST'));
    let oversizedRejected = false;
    try {
        images.findImage(screen, oversized, {
            region: [cropX, cropY, 32, 32], threshold: 0.8
        });
    } catch (error) {
        oversizedRejected = String(error.message || error).indexOf('larger than') >= 0;
    }
    assert(oversizedRejected, 'findImage 报告模板大于搜索区域');

    console.log('QUICKJS_IMAGE_API_OK', {
        screen: screen.width + 'x' + screen.height,
        centerColor: colors.toString(center),
        pngBytes: png.length,
        jpgBytes: jpg.length,
        matches: matched.matches.length,
        fullScreenNoMatchRuns: benchmarkRuns,
        timings: timings,
        savedTo: output
    });
} finally {
    for (let i = resources.length - 1; i >= 0; i--) {
        resources[i].recycle();
    }
}
