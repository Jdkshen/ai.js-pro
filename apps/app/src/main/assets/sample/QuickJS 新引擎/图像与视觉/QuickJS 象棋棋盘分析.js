// @engine quickjs
// 象棋棋盘分析
// 用途：images.analyzeXiangqiBoard 一帧定位棋盘几何（木色 → 格线 → 9×10 交点），images.detectXiangqiCircles 校验交点上的棋子圆，附带画板预览
// 前置：截图授权；运行时把棋局画面切到前台（脚本先等 3 秒）
// 覆盖：images / drawing

var DELAY_MS = 3000;      // 从编辑器点运行后留几秒切到棋局画面（脚本在后台继续跑）；0 = 立即抓
var SHOW_OVERLAY = true;  // 画板预览（需要「显示在其他应用上层」权限）；false 只看日志
var KEEP_MS = 8000;       // 画板保留时长

function fixedOrDash(value) {
    return value != null ? Number(value).toFixed(2) : '-';
}

/** 画板预览：网格线 + 交点圆。宿主画板只能画「矩形 + 一行标签」，所以圆用方框标。 */
function drawPreview(grid, circles) {
    try { drawing.hide(); } catch (error) { /* 上一轮没画过 */ }
    var shown = false;
    try { shown = drawing.show() === true; } catch (error) { shown = false; }
    if (!shown) {
        console.log('画板打不开（没有悬浮窗权限）——跳过预览');
        return;
    }
    var items = [];
    for (var r = 0; r < 10; r++) {
        var y = grid.originY + grid.stepY * r;
        items.push({ x1: grid.originX - 24, y1: y - 1.5,
            x2: grid.originX + grid.stepX * 8 + 24, y2: y + 1.5, label: '', score: 1 });
    }
    for (var c = 0; c < 9; c++) {
        var x = grid.originX + grid.stepX * c;
        items.push({ x1: x - 1.5, y1: grid.originY - 24,
            x2: x + 1.5, y2: grid.originY + grid.stepY * 9 + 24, label: '', score: 1 });
    }
    for (var i = 0; i < circles.length; i++) {
        var circle = circles[i];
        items.push({ x1: circle.x - circle.radius, y1: circle.y - circle.radius,
            x2: circle.x + circle.radius, y2: circle.y + circle.radius, label: '○', score: 0 });
    }
    drawing.update(items, '棋盘分析：线 ' + (10 + 9) + ' 条｜圆 ' + circles.length + ' 个');
    console.log('画板已显示：' + items.length + ' 个框');
}

console.log('=== 象棋棋盘分析 ===');
console.log('依赖：天天象棋类「棋盘占满屏幕」的竖屏棋局页；棋盘要完整、别被悬浮窗挡住。');

if (DELAY_MS > 0) {
    console.log(DELAY_MS + 'ms 后抓屏 —— 请把棋局画面切到前台');
    sleep(DELAY_MS);
}

if (!requestScreenCapture('portrait')) {
    throw new Error('截图授权被拒绝');
}

// 全分辨率 + 等新帧：棋盘定位对整幅画面敏感，不拿半更新的缓存帧
var frame = captureScreen({ mode: 'full', fresh: true, timeout: 200 });
console.log('帧 ' + frame.width + 'x' + frame.height);

var analysis = images.analyzeXiangqiBoard(frame);
if (!analysis.ok) {
    console.log('⚠ 没找到棋盘：' + (analysis.reason || '未知原因'));
    console.log('   检查：① 棋局页在前台且棋盘完整可见 ② 棋盘是木色皮肤 ③ 屏幕没被悬浮窗大面积遮挡');
    frame.recycle();
    console.log('=== XIANGQI_BOARD_ANALYZE_DONE ===');
} else {
    var grid = analysis.grid;
    console.log('定位成功：外框 ' + JSON.stringify(analysis.outer));
    console.log('  原点 (' + grid.originX.toFixed(1) + ', ' + grid.originY.toFixed(1) + ')' +
        '，格距 (' + grid.stepX.toFixed(1) + ', ' + grid.stepY.toFixed(1) + ')' +
        '，线分 横 ' + fixedOrDash(analysis.lineScoreX) + ' / 竖 ' + fixedOrDash(analysis.lineScoreY));
    console.log('  交点圆 ' + analysis.circles.length + ' 枚（analyzeXiangqiBoard 顺带给出）');
    // 每格中心 = (originX + col*stepX, originY + row*stepY)；col 0..8 从左到右，row 0..9 从上到下

    // 用已定位的几何再检测一遍「交点上的棋子圆」
    var circles = images.detectXiangqiCircles(frame, grid);
    var box = {};                      // 圆心就近归到交点（容忍 0.3 格）
    var radii = [];
    for (var i = 0; i < circles.length; i++) {
        var c = circles[i];
        radii.push(c.radius);
        var col = Math.round((c.x - grid.originX) / grid.stepX);
        var row = Math.round((c.y - grid.originY) / grid.stepY);
        if (col < 0 || col > 8 || row < 0 || row > 9) continue;
        if (Math.abs(c.x - (grid.originX + col * grid.stepX)) > grid.stepX * 0.3) continue;
        if (Math.abs(c.y - (grid.originY + row * grid.stepY)) > grid.stepY * 0.3) continue;
        box[row * 9 + col] = c;
    }
    var onCrossing = 0;
    for (var key in box) {
        if (Object.prototype.hasOwnProperty.call(box, key)) onCrossing++;
    }
    console.log('detectXiangqiCircles：找到 ' + circles.length + ' 个圆；落在交点上的 ' + onCrossing + ' 个');
    if (radii.length > 0) {
        console.log('  半径 ' + Math.min.apply(null, radii).toFixed(1) + ' ~ ' +
            Math.max.apply(null, radii).toFixed(1) + ' 逻辑像素');
    }

    if (SHOW_OVERLAY) {
        drawPreview(grid, circles);
        if (KEEP_MS > 0) {
            sleep(KEEP_MS);
            try { drawing.hide(); } catch (error) { /* 没画过 */ }
            console.log('画板已收起（KEEP_MS=' + KEEP_MS + '）');
        }
    }
    frame.recycle();
    console.log('=== XIANGQI_BOARD_ANALYZE_DONE ===');
}
