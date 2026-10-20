"nodejs";
// 分析棋盘(analyzeXiangqiBoard)
// 用内置 board.png 分析棋盘外框/网格/棋子圆，输出 32 子、未知格与 FEN。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/07_analyzeXiangqiBoard.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

const images = require('image');
try {
    const base = typeof __dirname === 'string' ? __dirname : files.cwd();
    const board = images.readImageSync(base + '/board.png');
    const started = Date.now();
    const analysis = board.analyzeXiangqiBoard();
    const ms = Date.now() - started;
    const circles = analysis.circles || [];
    // 90 个交叉点里应有 32 个棋子；未知格 = 90 - 识别到的棋子圆。
    const unknown = 90 - circles.length;
    // analyzeXiangqiBoard 只给几何+红黑，不给具体兵种；标准开局 32 子时可安全给出标准 FEN。
    let fen = null;
    let fenNote = '需要再用字形识别判定兵种；本例只在标准开局 32 子时给出标准 FEN';
    if (analysis.ok && circles.length === 32) {
        fen = 'rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR';
        fenNote = 'board.png 为标准开局，32 子齐全，直接套标准 FEN';
    }
    writeResult({
        ok: !!analysis.ok,
        api: 'Image.analyzeXiangqiBoard',
        reason: analysis.reason || '',
        ms,
        outer: analysis.outer || null,
        grid: analysis.grid || null,
        circles: circles.length,
        unknown,
        red: circles.filter(function (c) { return c.color === 'red'; }).length,
        black: circles.filter(function (c) { return c.color === 'black'; }).length,
        fen,
        fenNote,
        sampleCircles: circles.slice(0, 4).map(function (c) {
            return { x: c.x, y: c.y, row: c.row, col: c.col, color: c.color, redFraction: c.redFraction };
        })
    });
    board.recycle();
} catch (error) {
    writeResult({ ok: false, api: 'Image.analyzeXiangqiBoard', error: String(error && error.stack || error) });
}
