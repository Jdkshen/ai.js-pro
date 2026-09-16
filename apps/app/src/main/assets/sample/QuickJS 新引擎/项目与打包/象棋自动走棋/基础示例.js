// @engine quickjs
// 象棋识别 · 基础示例（先跑这个）
//
// 一句话：加载随包模型 → 取一帧画面 → 数出里面有几个棋子、分别是什么。
//
// 前置：
//   1) 允许截图（首次运行会弹授权弹窗）
//   2) 把棋局画面切到前台再运行 —— 脚本在后台跑，截的是「当前屏幕」
//      （识别效果最好的口径是「棋盘占满屏幕的竖屏棋局页」，模型就是这么训的）
// 本文件只做识别：不点击屏幕、不申请其它权限、不依赖工程里的任何模块，
// 复制出去单独改也能用。想看完整链路（定位棋盘 → 90 个交叉点 → FEN → 自动走棋）看同目录的 main.js。

// ============================ 参数（要改就改这里） ============================

// 想拿一张**已保存的截图**试（不用实时截屏），就把路径填这里，例如 '/sdcard/Pictures/棋局.png'。
// 留 null = 实时截当前屏幕。
var IMAGE_PATH = null;

// 随包模型。asset:// 指向 APK 里的 assets/sample/…，所以文件复制到哪儿都不会丢；
// 换成自己导入的模型时写磁盘路径即可（如 '/sdcard/脚本/模型库/chess.onnx'）。
var MODEL_PATH = 'asset://sample/QuickJS 新引擎/项目与打包/象棋自动走棋/assets/models/chessai_160x320_v5r.onnx';
var LABELS_PATH = 'asset://sample/QuickJS 新引擎/项目与打包/象棋自动走棋/assets/models/chessai_160x320_v5r.txt';

// 模型输入尺寸：**必须与导出时一致**（这个随包模型是竖屏矩形 宽160 高320）。
// 换方形模型（160x160）时改成 INPUT_WIDTH = 160、INPUT_HEIGHT = 160，否则推理会直接报错。
var INPUT_WIDTH = 160;
var INPUT_HEIGHT = 320;

// 分数阈值：低于它的小框大多是误检（桌面截图常见），0.5 以上基本是真棋子。
var CONFIDENCE = 0.5;
var NMS = 0.45;

// full = 原生分辨率（棋盘上 32 个棋子不能降采样），fresh = 要新帧而不是缓存帧
var CAPTURE_OPTIONS = { mode: 'full', fresh: true, timeout: 1500 };

// 7 类的字母顺序固定为 R N B A K C P（与 labels 文件一致），这里只用来打印中文名。
// ⚠️ 模型**分不出红黑**：相/象、仕/士、帅/将、兵/卒 各自合并成一类。
var CLASS_TEXT = { R: '车', N: '马', B: '相/象', A: '仕/士', K: '帅/将', C: '炮', P: '兵/卒' };

// ============================ 0. 能力检查 ============================

if (typeof yolo === 'undefined' || !yolo.isAvailable('dnn')) {
    throw new Error('这个版本没有 OpenCV DNN 推理能力，跑不了象棋识别');
}

// ============================ 1. 加载模型（一次就够） ============================

console.log('[象棋] 加载模型：' + MODEL_PATH + '（输入 ' + INPUT_WIDTH + 'x' + INPUT_HEIGHT + '）');
var loadStarted = performance.now();
var detector = yolo.load({
    backend: 'dnn',
    model: MODEL_PATH,
    labels: LABELS_PATH,
    inputWidth: INPUT_WIDTH,
    inputHeight: INPUT_HEIGHT,
    threads: 4
});
console.log('[象棋] 模型就绪 ' + (performance.now() - loadStarted).toFixed(0) + 'ms');

// ============================ 2. 取一帧画面 ============================

/** 实时截图；第一帧可能是全黑（系统刚授权），所以失败/全黑都重试几次。 */
function grabFrame() {
    var lastError = null;
    for (var i = 0; i < 3; i++) {
        try {
            return captureScreen(CAPTURE_OPTIONS);
        } catch (error) {
            lastError = error;
        }
        sleep(150);
    }
    throw new Error('连续 3 次截图失败：' + (lastError && lastError.message ? lastError.message : lastError));
}

var image = null;
var describeImage = '';
if (IMAGE_PATH) {
    image = images.read(String(IMAGE_PATH));
    if (!image) {
        throw new Error('读不到图片：' + IMAGE_PATH);
    }
    describeImage = '来自文件 ' + IMAGE_PATH;
} else {
    if (!requestScreenCapture('portrait')) {
        throw new Error('用户取消了截图授权');
    }
    image = grabFrame();
    describeImage = '来自实时截屏';
}
console.log('[象棋] ' + describeImage + '，尺寸 ' + image.width + 'x' + image.height);
// 先记下尺寸：下面识别完会把图回收掉，回收后再读 width/height 就不一定还准了
var frameSize = image.width + 'x' + image.height;

// ============================ 3. 识别 ============================

var result = null;
try {
    result = detector.detect(image, { confidence: CONFIDENCE, nms: NMS });
} finally {
    // 帧/图用完要回收，否则连续跑会一直占内存
    try { image.recycle(); } catch (error) { /* 已经回收过就算了 */ }
}

console.log('[象棋] 耗时：预处理 ' + result.preprocessMs.toFixed(1) + 'ms / 推理 ' + result.inferenceMs.toFixed(1) +
    'ms / 合计 ' + result.totalMs.toFixed(1) + 'ms');

// ============================ 4. 看结果 ============================

var counts = {};       // 每类几个
var boxes = [];        // 每个框：{label, score, x, y}
var minScore = 1;
var maxScore = 0;
for (var i = 0; i < result.length; i++) {
    var item = result[i];
    counts[item.label] = (counts[item.label] || 0) + 1;
    var bounds = item.bounds || {};
    boxes.push({
        label: item.label,
        score: item.score,
        x: bounds.centerX == null ? item.x : bounds.centerX,
        y: bounds.centerY == null ? item.y : bounds.centerY
    });
    if (item.score < minScore) minScore = item.score;
    if (item.score > maxScore) maxScore = item.score;
}

// 分数高的在前
boxes.sort(function (a, b) { return b.score - a.score; });

console.log('[象棋] 共 ' + result.length + ' 个棋子框');
if (result.length > 0) {
    var parts = [];
    for (var key in counts) {
        if (Object.prototype.hasOwnProperty.call(counts, key)) {
            parts.push((CLASS_TEXT[key] || key) + '=' + counts[key]);
        }
    }
    // 7 类固定顺序输出，方便一眼比对
    var order = ['R', 'N', 'B', 'A', 'K', 'C', 'P'];
    var ordered = [];
    for (var k = 0; k < order.length; k++) {
        if (counts[order[k]]) {
            ordered.push((CLASS_TEXT[order[k]] || order[k]) + '=' + counts[order[k]]);
        }
    }
    console.log('[象棋] 分类：' + (ordered.length ? ordered.join('  ') : parts.join('  ')));
    console.log('[象棋] 分数：' + minScore.toFixed(2) + ' ~ ' + maxScore.toFixed(2));
    console.log('[象棋] 分数最高的 10 个：');
    for (var b = 0; b < boxes.length && b < 10; b++) {
        var box = boxes[b];
        console.log('        ' + (CLASS_TEXT[box.label] || box.label) +
            ' ' + (box.score * 100).toFixed(1) + '%  中心 (' + Math.round(box.x) + ', ' + Math.round(box.y) + ')');
    }
} else {
    console.log('[象棋] 一个棋子都没认出来，按顺序查这三条：');
    console.log('        ① 画面里得真的是棋局（桌面/聊天页认不出来是正常的，模型只见过棋盘）');
    console.log('        ② 棋盘要**占满屏幕**：棋盘只占屏幕一小块时棋子太小，会全被漏掉');
    console.log('        ③ 分数阈值先降到 0.25 试试（把 CONFIDENCE 改小）');
}

// 一盘棋最多 32 个棋子，明显超出就说明误检（比如皮肤/图案被当成棋子）：
if (result.length > 40) {
    console.log('[象棋] 注意：框数明显超过 32，画面里可能有非棋盘内容被误检，别直接当棋局用');
}

// 给自动化验证留的标记行（跑通就是这一行）
console.log('CHESS_BASIC_OK', {
    engine: __engine__.name,
    source: IMAGE_PATH ? String(IMAGE_PATH) : 'captureScreen',
    frame: frameSize,
    model: MODEL_PATH,
    input: INPUT_WIDTH + 'x' + INPUT_HEIGHT,
    detections: result.length,
    counts: counts,
    preprocessMs: result.preprocessMs,
    inferenceMs: result.inferenceMs,
    totalMs: result.totalMs
});

// ============================ 下一步 ============================
// · 分类计数偶尔会差一两个（比如 马=3 仕=5）：这是 7 类模型的混淆，不是脚本算错；
//   完整工程靠「逐格复核 + 多帧时序融合」修正，见 src/vision/。
// · 想知道每个棋子在棋盘的哪一格、整盘是什么局面（FEN）：
//   同目录 main.js（完整工程）与 tools/recognize-board.js（定位 + 识别 → FEN）
// · 想换/接入自己的模型：YOLO目标检测/模型管理.js 可以从文件导入 .onnx + .txt 标签
