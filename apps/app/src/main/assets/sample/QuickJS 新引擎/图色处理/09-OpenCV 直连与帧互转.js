// @engine quickjs
// OpenCV 直连与帧互转
// 用途：帧↔Mat 互转后直接调用 OpenCV Java API（与 Rhino 写法一致），并演示 inRange / findAllPointsForColor
// 前置：截图授权（未授权时用 OpenCV 合成帧演示）
// 覆盖：images

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
        mat.setTo(new opencv.Scalar(16, 96, 240, 255));   // BGRA
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
    console.log('帧来源 = ' + (synthetic ? 'OpenCV 合成帧' : '屏幕截图')
        + ', 逻辑 ' + frame.width + 'x' + frame.height
        + ', 像素 ' + frame.pixelWidth + 'x' + frame.pixelHeight);

    // 1) 帧 → Mat → 直接调用 OpenCV Java API（Rhino 里是 img.mat 的隐式转换）
    var source = images.toMat(frame);                       // CV_8UC4，独立副本
    var gray = new images.opencv.Mat();
    var binary = new images.opencv.Mat();
    try {
        images.opencv.Imgproc.cvtColor(source, gray, images.opencv.Imgproc.COLOR_RGBA2GRAY);
        images.opencv.Imgproc.threshold(gray, binary, 120, 255,
            images.opencv.Imgproc.THRESH_BINARY);
        console.log('Mat: rows/cols = ' + binary.rows() + 'x' + binary.cols()
            + ', type = ' + binary.type() + ', depth/channels = '
            + binary.depth() + '/' + binary.channels());

        // 2) Mat → 帧（内部 clone，Mat 之后 release 也不影响）
        var back = images.matToImage(binary);
        try {
            console.log('回写帧: ' + back.width + 'x' + back.height
                + ', 左上角像素 = ' + images.pixel(back, 0, 0));
        } finally {
            back.recycle();
        }
    } finally {
        source.release();
        gray.release();
        binary.release();
    }

    // 3) 快捷封装：inRange / interval / 模糊 / 阈值（内部同样走 OpenCV Java API + 帧桥）
    var mask = images.inRange(frame, '#000000', '#808080');
    try {
        console.log('inRange: ' + mask.width + 'x' + mask.height
            + ', 左上角 = ' + images.pixel(mask, 0, 0));
    } finally {
        mask.recycle();
    }

    var blurred = images.medianBlur(frame, 3);
    try {
        console.log('medianBlur(3) 左上角 = ' + images.pixel(blurred, 0, 0));
    } finally {
        blurred.recycle();
    }

    // 4) findAllPointsForColor：一次拿到区域内所有命中点（Rhino 同名 API）
    var points = images.findAllPointsForColor(frame, synthetic ? '#1060F0' : '#FFFFFF', {
        threshold: 4,
        region: [0, 0, Math.min(200, frame.width), Math.min(200, frame.height)]
    });
    console.log('findAllPointsForColor 命中 ' + points.length + ' 个点'
        + (points.length > 0 ? '，第一个 ' + JSON.stringify(points[0]) : ''));

    // 5) 也可以完全不经过帧，直接 new Mat 做纯计算
    var kernel = images.opencv.Imgproc.getGaussianKernel(3, 0);
    try {
        console.log('直接构造 Mat: ' + kernel.rows() + 'x' + kernel.cols());
    } finally {
        kernel.release();
    }
});

console.log('OpenCV 直连示例结束（images.opencv 提供 Mat/Core/Imgproc/CvType/Scalar/Size/Point/Rect/Bitmap/BitmapFactory）');
