"nodejs";
// 检测棋盘圆(detectXiangqiCircles)
// 先由 analyzeXiangqiBoard 得到固定网格，再单独检测棋子圆，输出数量与坐标。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/08_detectXiangqiCircles.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

// 坐标约定：返回的是源图坐标系下的圆心/半径，可直接复用给点击或绘制。
const images = require('image');
try {
    const base = typeof __dirname === 'string' ? __dirname : files.cwd();
    const board = images.readImageSync(base + '/board.png');
    const analysis = board.analyzeXiangqiBoard();
    if (!analysis.ok) {
        writeResult({ ok: false, api: 'Image.detectXiangqiCircles', stage: 'analyze', reason: analysis.reason || '', analysis });
    } else {
        const grid = analysis.grid;
        const started = Date.now();
        const circles = board.detectXiangqiCircles(grid);
        const ms = Date.now() - started;
        writeResult({
            ok: true, api: 'Image.detectXiangqiCircles',
            grid,
            count: circles.length,
            ms,
            circles: circles.slice(0, 8).map(function (c) {
                return { x: c.x, y: c.y, radius: c.radius, row: c.row, col: c.col, color: c.color, redFraction: c.redFraction };
            }),
            // 说明：grid 来自 analyzeXiangqiBoard；也可以使用固定标定值（originX/originY/stepX/stepY）。
            note: '输入固定 grid 后检测 9×10 交叉点上的棋子圆，结果按 row*9+col 升序'
        });
        board.recycle();
    }
} catch (error) {
    writeResult({ ok: false, api: 'Image.detectXiangqiCircles', error: String(error && error.stack || error) });
}
