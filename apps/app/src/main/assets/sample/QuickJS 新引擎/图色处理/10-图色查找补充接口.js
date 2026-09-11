// @engine quickjs
// 图色查找补充接口
// 用途：findColorEquals / detectsColor / findImageInRegion / colors 工具补充用法
// 前置：截图授权（未授权时用 OpenCV 合成帧）
// 覆盖：images / colors

function withFrame(callback) {
    var frame = null;
    var synthetic = false;
    try {
        frame = images.captureScreen();
    } catch (error) {
        frame = null;
    }
    if (frame === null || frame === undefined) {
        var opencv = images.opencv;
        var mat = new opencv.Mat(64, 64, opencv.CvType.CV_8UC4);
        mat.setTo(new opencv.Scalar(16, 96, 240, 255));
        frame = images.matToImage(mat);
        mat.release();
        synthetic = true;
    }
    try {
        return callback(frame, synthetic);
    } finally {
        frame.recycle();
    }
}

withFrame(function (frame, synthetic) {
    var target = synthetic ? '#1060F0' : '#FFFFFF';
    console.log('帧来源 = ' + (synthetic ? 'OpenCV 合成帧' : '屏幕截图')
        + '，目标颜色 = ' + target + '，尺寸 ' + frame.width + 'x' + frame.height);

    // 1) findColorEquals：精确匹配（threshold = 0）
    console.log('findColorEquals = ' + JSON.stringify(images.findColorEquals(frame, target)));

    // 2) detectsColor：逐点判定是否与目标色相似
    var point = images.findColor(frame, target, { threshold: 4 });
    if (point !== null) {
        console.log('detectsColor(命中点) = ' + images.detectsColor(frame, target, point.x, point.y, 4));
    } else {
        console.log('未命中目标色，用左上角像素判定 = '
            + images.detectsColor(frame, target, 0, 0, 255));
    }

    // 3) findImageInRegion：在指定区域内做模板匹配（模板用帧自身裁剪，必定命中）
    var template = images.clip(frame, 0, 0, Math.min(32, frame.width), Math.min(32, frame.height));
    try {
        var found = images.findImageInRegion(frame, template, 0, 0, frame.width, frame.height, 0.9);
        console.log('findImageInRegion(全屏) = ' + JSON.stringify(found));

        var missRegion = images.findImageInRegion(frame, template, 0, 0, 8, 8, 0.9);
        console.log('findImageInRegion(8x8 过小区域) = ' + JSON.stringify(missRegion));
    } finally {
        template.recycle();
    }

    // 4) 颜色工具：colors.isSimilar / rgb / argb / 通道读取
    var parsed = colors.parseColor(target);
    console.log('colors.parseColor = ' + parsed
        + ', isSimilar(自身) = ' + colors.isSimilar(parsed, target, 0)
        + ', alpha = ' + colors.alpha(parsed)
        + ', red = ' + colors.red(parsed)
        + ', toString = ' + colors.toString(parsed));
});

console.log('=== IMAGE_LOOKUP_EXTRA_DONE ===');
