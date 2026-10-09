// @engine quickjs
// 象棋识别 · 基础示例（先跑这个）
//
// 一句话：SIFT 先把棋盘框出来（只负责定位），再只在棋盘区域里识别棋子。
//
// 前置：
//   1) 允许截图（首次运行会弹授权弹窗）
//   2) 把棋局画面切到前台再运行 —— 脚本在后台跑，截的是「当前屏幕」
//      棋盘要**完整出现在画面里**：SIFT 用随包的木色棋盘模板找棋盘，找到后只把
//      「棋盘区域」送进模型识别，所以不再要求棋盘占满屏幕（占得越大、棋子越清楚越稳）。
//      换了非木色皮肤、棋盘被挡住时可能定位不到 → 自动退回全屏识别。
// 本文件只做识别：不点击屏幕、不申请其它权限（末尾「画框看看」只用到系统悬浮窗，
// 没有该权限就自动跳过）；不依赖工程里的任何模块，复制出去单独改也能用。
// 想看完整链路（定位棋盘 → 90 个交叉点 → FEN → 自动走棋）看同目录的 main.js。

// ============================ 参数（要改就改这里） ============================

// 想拿一张**已保存的截图**试（不用实时截屏），就把路径填这里，例如 '/sdcard/Pictures/棋局.png'。
// 留 null = 实时截当前屏幕。
var IMAGE_PATH = null;

// 工程自己的 assets（棋盘模板 / 模型 / 字形模板都在里面）。
// ⚠️ 工程已从主 APK 移出（改由「象棋引擎插件」分发，装机后用 xq.installProject() 还原），
//    所以这里不能再写 asset://sample/…/象棋自动走棋/…（APK 里已经没有这份工程了），
//    一律相对脚本目录取：工程放到哪，资源就跟到哪。
var PROJECT_ASSETS = (typeof __dirname === 'string' && __dirname ? __dirname.replace(/[\\/]+$/, '') : '.') + '/assets';

// 棋盘 SIFT 模板（只负责定位棋盘）：木色棋盘截图。工程里同一份模板还带交点标定
// （assets/board-sift/calibration.json），示例只用到「找棋盘」这一步。
// ⚠️ 模板是在 1080 宽屏上采的（棋盘步距 114.8px）：屏幕越宽棋盘越大（1280 屏 ≈135px、
//    1440 屏 ≈152px…），网格尺度和画面差太多时单档匹不上 → 下面多备几档逐档试。
var BOARD_TEMPLATE_PATH = PROJECT_ASSETS + '/board-sift/board.png';
// SIFT 模板缩放档位（templateScale），实际顺序按本机屏宽的预测值排（见下面的注释）
var SIFT_TEMPLATE_SCALES = [0.5, 0.55, 0.59, 0.63, 0.68, 0.42, 0.34];
// 本机最合适档位的预测（只管排先后）：竖屏棋局棋盘几乎占满屏宽，步距 ≈ 屏宽×0.106；
// 模板步距 114.8、画面 scale=0.5 下等效 229.6 → templateScale ≈ 屏宽×0.000463。
// （K40 1080 → 0.50；红米 1280 → 0.59。预测不准也没事，反正会逐档试。）
var SIFT_SCALE_PER_WIDTH = 0.000463;

// 随工程分发的模型（在工程的 assets/models 下）；换成自己导入的模型时写磁盘路径即可
// （如 '/sdcard/脚本/模型库/chess.onnx'）。
var MODEL_PATH = PROJECT_ASSETS + '/models/chessai_160x320_v5r.onnx';
var LABELS_PATH = PROJECT_ASSETS + '/models/chessai_160x320_v5r.txt';

// 模型输入尺寸：**必须与导出时一致**（这个随包模型是竖屏矩形 宽160 高320）。
// 换方形模型（160x160）时改成 INPUT_WIDTH = 160、INPUT_HEIGHT = 160，否则推理会直接报错。
var INPUT_WIDTH = 160;
var INPUT_HEIGHT = 320;

// 分数阈值：0.001 = **拉满召回**（宁多勿漏：基本不会漏子，代价是低分框/叠框多）。
// 想干净一点可以回调（0.1~0.2 少漏检；≥0.5 框干净但容易漏子）；
// 杂框本来就靠下面的兜底链收拾：DEDUPE_IOU 去重 → 框大小过滤 → 字形复核。
var CONFIDENCE = 0.001;
// nms：宿主目前只校验取值范围、不参与解码（这个模型是端到端 YOLO26，自带去重），调它基本没效果；
// 重复框在脚本侧用 DEDUPE_IOU 去掉。
var NMS = 0.001;

// 去重：按分数从高到低保留，跟已保留的框 IoU ≥ 这个值就当重复丢掉（0 = 不去重）。
// 0.3 ≈「两框重叠三分之一以上」；棋子之间至少隔一格，不会误伤邻子。
var DEDUPE_IOU = 0.3;

// 框大小过滤：棋子框大小彼此接近（≈一个格距），和「框宽中位数」差太远的多半是游戏 UI
// （头像、按钮、徽章）——全屏兜底（SIFT 没定位到）时最容易被模型当成棋子，这里按比例滤掉。
var SIZE_FILTER_MIN = 0.70;      // 小于中位数×0.70 的丢
var SIZE_FILTER_MAX = 1.40;      // 大于中位数×1.40 的丢

// 红黑判定（补上「这个是什么棋子」的另一半）：模型只给 7 个兵种类别，红黑是合并的
// （相/象、仕/士、帅/将、兵/卒 都分不出来），所以在每个检测框里**多点取色**、
// 数「红笔画 / 黑笔画」像素占比定阵营（思路同工程 src/vision/piece-side.js）。
// ⚠️ 红的三条线比工程更严：盘沿/木纹的暖色必须挡在「红笔画」之外——否则框一大，
//    黑子会被判红（2026-10-04 红米真机取证 + 9 张真机帧回放验证的结论）。
// 判不出来就算未知（画板标「车?」），不猜。不要就设 false。
var SIDE_DETECT = true;
var SIDE_SAMPLE_STEP = 3;        // 采样步长（像素）：越小越准、越慢
var SIDE_SAMPLE_INSET = 0.25;    // 采样区从框边内缩的比例（0.25 = 只采中间 50%）
var SIDE_RED_MIN = 120;          // 红笔画：红通道下限
var SIDE_RED_G_DOMINANCE = 1.6;  // 红笔画：红要压过绿的倍数
var SIDE_RED_B_DOMINANCE = 1.8;  // 红笔画：红要压过蓝的倍数（盘沿暖色过不了这关）
var SIDE_DARK_MAX = 105;         // 黑笔画：三通道都低于它
var SIDE_DARK_MIN_SHARE = 0.15;  // 黑判据下限：黑笔画占比不到它就算未知（UI 紫/粉小块的暗占比只有 3%~9%，别当黑子）
// 取色诊断：每枚棋子打一行「红/暗占比 + 采样均色」（查红黑误判用；平时可设 false）
var SIDE_DEBUG = true;

// 字形复核：模型会认错子（真机见过「帅→仕」），再拿工程的字形模板（assets/glyph-tpl，11 个）
// 在每个框里重新认一遍字；只有「分够（GLYPH_CONF）+ 头名拉开（GLYPH_MARGIN）」才按字形修正，
// 读不准就保持模型结论 —— 只修不改进坏（9 张真机帧回放：模型 90.2% → 复核后 99.6%，0 改坏）。
var GLYPH_CHECK = true;
var GLYPH_CONF = 0.70;       // 复核分下限（低于它的字形结论不用）
var GLYPH_MARGIN = 0.10;     // 头名与次名的分差下限（咬太近怕认错，不改）
var GLYPH_BOX_RATIO = 0.593; // 模板 68px 对应「检测框宽」的比例（K40 实测 68/114.8）
// 第一轮还有没认准的框时，换这两档缩放补试一遍（棋盘/棋子比推算尺寸大/小一点时救场）；
// 每枚取各轮里分最高的结论，门槛（GLYPH_CONF/GLYPH_MARGIN）照旧（全都认准就直接跳过补试）。
var GLYPH_RESCUE_SCALES = [1.06, 0.94];

// 空中子过滤（模糊门）：落子动画里“飞在半空”的子也会被模型框出来（它是运动模糊的）。
// 口径同工程 glyph-recognizer 的 MOVING_*：框心窗内采样离散拉普拉斯方差（越大越清晰），
// 以“字形复核认准过的框”中位数为基准，低于 基准×MOVING_RATIO 的判「没落稳」不数它。
// （真机实测：静止子 ≥1289、空中子 248~395——差 4~5 倍，零误伤。）
var MOVING_FILTER = true;
var MOVING_RATIO = 0.5;      // 清晰度低于「基准×它」的判空中子
var MOVING_STEP = 3;         // 清晰度采样步长（像素）

// full = 原生分辨率（棋盘上 32 个棋子不能降采样），fresh = 要新帧而不是缓存帧
var CAPTURE_OPTIONS = { mode: 'full', fresh: true, timeout: 1500 };

// 画框看看：把「SIFT 匹配到的模板位置 / 棋盘区域 / 棋子框」画到屏幕上（系统悬浮窗，
// 需要「显示在其他应用上层」权限；没有就只打日志，识别照常）。
// 画板会被截图截进去 —— 本示例是「先截帧、后画框」所以没影响；
// 连续识别时要每帧先 drawing.hide() 再截图（主工程面板就是这么做的）。
var DRAW = true;
// 画完停留多少毫秒再收（给人看一眼）；0 = 不等待（脚本一结束画板就被宿主关掉）
var DRAW_HOLD_MS = 8000;

// 对照看看：同一帧再用「整屏」跑一次 yolo，日志对比框数/耗时，画板把 region 会筛掉的
// 「棋盘外」框用「屏外·」前缀画出来 —— 一眼看出 region 到底做了什么；不要对照就设 false。
var COMPARE_FULL = true;

// 连续识别：0 = 只跑一次（默认）；>0 = 连续跑这么多帧（想接近「一直跑」就设个大数，停止脚本即结束）。
// 连续时每帧先收画板再截图（画板是系统悬浮窗会被截进画面），逐帧打「第 N 帧耗时分期」，
// 结尾给「平均/最小/最大」；单次模式的输出与原来完全一致。
var LOOP_FRAMES = 0;
// 连续识别两帧之间的歇息（毫秒）；0 = 不歇（截图本身通常已够慢）
var LOOP_SLEEP_MS = 200;

// 7 类的字母顺序固定为 R N B A K C P（与 labels 文件一致），这里只用来打印中文名。
// ⚠️ 模型**分不出红黑**：相/象、仕/士、帅/将、兵/卒 各自合并成一类。
var CLASS_TEXT = { R: '车', N: '马', B: '相/象', A: '仕/士', K: '帅/将', C: '炮', P: '兵/卒' };

// 字形模板（11 个 68×68，取自「浅木皮肤」）：字形 → 兵种类别 / 阵营。
// 車馬炮两边同形（阵营看颜色）；相/象、仕/士、帅/将、兵/卒 各自不同字，连阵营一起认出来。
var GLYPH_TEMPLATE_DIR = PROJECT_ASSETS + '/glyph-tpl';
var GLYPH_NAMES = ['車', '馬', '象', '士', '將', '炮', '卒', '兵', '相', '仕', '帥'];
var GLYPH_CLASS = { '車': 'R', '馬': 'N', '炮': 'C', '相': 'B', '象': 'B', '仕': 'A', '士': 'A',
    '帥': 'K', '將': 'K', '兵': 'P', '卒': 'P' };
var GLYPH_SIDE = { '相': 'RED', '仕': 'RED', '帥': 'RED', '兵': 'RED',
    '象': 'BLACK', '士': 'BLACK', '將': 'BLACK', '卒': 'BLACK' };
var GLYPH_TEXT = { '車': '车', '馬': '马', '炮': '炮', '象': '象', '士': '士', '將': '将',
    '卒': '卒', '兵': '兵', '相': '相', '仕': '仕', '帥': '帅' };

// ============================ 0. 能力检查 ============================

if (typeof yolo === 'undefined' || !yolo.isAvailable('dnn')) {
    throw new Error('这个版本没有 OpenCV DNN 推理能力，跑不了象棋识别');
}

// 耗时分期（末尾汇总一行；画板停留不计入「合计」）：模型 / 模板 / 取帧 / 定位 / 检测 / 取色 / 字形 / 空中子
var runStarted = performance.now();
var modelMs = 0, glyphTplMs = 0, frameMs = 0, locateMs = 0, detectMs = 0, sideMs = 0, glyphMs = 0, movingMs = 0;
var loopCount = 0, loopSum = 0, loopMin = 0, loopMax = 0;   // 连续模式的单帧合计统计（单次模式不打印）

// ============================ 1. 加载模型与棋盘模板（一次就够） ============================

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
modelMs = performance.now() - loadStarted;
console.log('[象棋] 模型就绪 ' + modelMs.toFixed(0) + 'ms');

// SIFT 只干「找棋盘」一件事：拿模板在画面里匹配，输出棋盘四角；棋子是什么、红黑、
// 棋规统统不管（交给下面的模型）。匹配器在「3. SIFT 只定位棋盘」里现建 ——
// 模板是 1080 宽屏采的，屏幕越宽棋盘越大（1280 屏大 ~17%），要按屏宽试几档缩放才对得上。

// 字形模板（复核用）：11 个 68×68 灰度 PNG（工程的 assets/glyph-tpl），单次模式本帧用完就回收；连续模式到循环结束才回收。
var glyphTemplates = null;
if (GLYPH_CHECK) {
    var glyphStarted = performance.now();
    try {
        var loadedGlyphs = [];
        for (var g = 0; g < GLYPH_NAMES.length; g++) {
            var glyphImage = images.read(GLYPH_TEMPLATE_DIR + '/tpl_' + GLYPH_NAMES[g] + '.png');
            if (!glyphImage) {
                throw new Error('读不到字形模板：' + GLYPH_NAMES[g]);
            }
            loadedGlyphs.push({ name: GLYPH_NAMES[g], image: glyphImage });
        }
        glyphTemplates = loadedGlyphs;
        glyphTplMs = performance.now() - glyphStarted;
        console.log('[象棋] 字形模板就绪 ' + glyphTplMs.toFixed(0) + 'ms（' +
            glyphTemplates.length + ' 个）');
    } catch (error) {
        glyphTplMs = performance.now() - glyphStarted;
        console.log('[象棋] ⚠ 字形模板加载失败（' + String(error && error.message ? error.message : error) + '）→ 跳过字形复核');
        glyphTemplates = null;
    }
}

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

/**
 * 跑一轮完整识别：取帧 → 定位 → 检测 → 后处理 → 看结果 → 画板。
 * 单次模式（LOOP_FRAMES≤1）调用 1 次；连续模式由文件末尾的驱动循环反复调用。
 * 每轮重置阶段计时；模型与字形模板是一次性加载（已有各自打点，不计入每轮）。
 */
function recognizeOnce(roundIndex) {
    // 连续模式：每轮重置计时起点（单次模式保持「从脚本启动算起」的合计口径）
    if (LOOP_FRAMES > 1) {
        runStarted = performance.now();
    }
    frameMs = 0; locateMs = 0; detectMs = 0; sideMs = 0; glyphMs = 0; movingMs = 0;
    // 连续模式：先把上一帧的画卷收掉，别截进这一帧（单帧模式没有画板，不必收）
    if (LOOP_FRAMES > 1 && typeof drawing !== 'undefined' && drawing && typeof drawing.hide === 'function') {
        try { drawing.hide(); } catch (error) { /* 没开画板就算了 */ }
    }
    var image = null;
    var describeImage = '';
    if (IMAGE_PATH) {
        var frameStarted = performance.now();
        image = images.read(String(IMAGE_PATH));
        frameMs = performance.now() - frameStarted;
        if (!image) {
            throw new Error('读不到图片：' + IMAGE_PATH);
        }
        describeImage = '来自文件 ' + IMAGE_PATH;
    } else {
        var captureStarted = performance.now();
        if (!requestScreenCapture('portrait')) {
            throw new Error('用户取消了截图授权');
        }
        image = grabFrame();
        frameMs = performance.now() - captureStarted;
        describeImage = '来自实时截屏';
    }
    console.log('[象棋] ' + describeImage + '，尺寸 ' + image.width + 'x' + image.height);
    // 先记下尺寸：下面识别完会把图回收掉，回收后再读 width/height 就不一定还准了
    var frameSize = image.width + 'x' + image.height;

    // ============================ 3. SIFT 只定位棋盘 ============================

    /** 四角 → 棋盘区域 [x,y,w,h]（像素，夹紧在画面内，再向外放一点）。 */
    function regionOfCorners(location, frameWidth, frameHeight) {
        var corners = [location.topLeft, location.topRight, location.bottomRight, location.bottomLeft];
        var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        for (var i = 0; i < corners.length; i++) {
            var point = corners[i];
            if (!point || !isFinite(point.x) || !isFinite(point.y)) {
                return null;
            }
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
        // 模板自带一圈边距，这里只再外放 4%（棋子压在棋盘外沿时不被裁掉）
        var pad = Math.round(Math.min(maxX - minX, maxY - minY) * 0.04);
        var left = Math.max(0, Math.floor(minX) - pad);
        var top = Math.max(0, Math.floor(minY) - pad);
        var right = Math.min(frameWidth, Math.ceil(maxX) + pad);
        var bottom = Math.min(frameHeight, Math.ceil(maxY) + pad);
        return right - left >= 40 && bottom - top >= 40 ? [left, top, right - left, bottom - top] : null;
    }

    // 四角必须接近「等比放大」：模板横竖两边各自映射后，尺度比值应接近 1（允许 15% 内差异）。
    // 防住宿主 fast 快跑（2/3 降档）在低特征画面（空盘残局）上收敛到压扁的错误单应这类误匹。
    function quadScaleRatio(location, templateWidth, templateHeight) {
        var wEdge = Math.sqrt(Math.pow(location.topRight.x - location.topLeft.x, 2) +
            Math.pow(location.topRight.y - location.topLeft.y, 2));
        var hEdge = Math.sqrt(Math.pow(location.bottomLeft.x - location.topLeft.x, 2) +
            Math.pow(location.bottomLeft.y - location.topLeft.y, 2));
        if (!(wEdge > 0) || !(hEdge > 0)) {
            return Infinity;
        }
        var sx = wEdge / templateWidth;
        var sy = hEdge / templateHeight;
        return Math.max(sx, sy) / Math.min(sx, sy);
    }

    var boardRegion = null;
    var boardCorners = null;
    var locateInfo = null;
    if (typeof images.createSiftMatcher !== 'function') {
        console.log('[象棋] 这个版本没有 SIFT 接口（images.createSiftMatcher）→ 跳过定位，退回全屏识别');
    } else {
        // 档位顺序：按「离本屏预测值近的」先试（竖屏棋局棋盘几乎占满屏宽，预测 ≈ 屏宽×0.000463）
        var predictedTs = Math.min(4, Math.max(0.1,
            Math.round(image.width * SIFT_SCALE_PER_WIDTH * 1000) / 1000));
        var scaleOrder = SIFT_TEMPLATE_SCALES.slice().sort(function (a, b) {
            return Math.abs(a - predictedTs) - Math.abs(b - predictedTs);
        });
        var templateImage = null;
        try {
            templateImage = images.read(BOARD_TEMPLATE_PATH);
            if (!templateImage) {
                throw new Error('读不到棋盘模板：' + BOARD_TEMPLATE_PATH);
            }
            var location = null;
            var usedScale = null;
            var attempts = [];
            var locateStarted = performance.now();
            for (var a = 0; a < scaleOrder.length && !location; a++) {
                var ts = scaleOrder[a];
                var matcher = null;
                var attemptStarted = performance.now();
                var attemptNote = '';
                try {
                    // scale 减半提速（与工程 sift-board-locator 同一组参数）；匹配结果坐标会还原到
                    // 原图尺度，不需要自己乘回去。templateScale 逐档试：和本机棋盘尺度对不上时救场。
                    // 不开 fast：它会先用 2/3 降档试配，低特征画面（空盘残局）上可能收敛到压扁的
                    // 错误单应（新手机 1280 屏实测：竖边被压 21%，区域错到棋盘外）；宁可多花一点时间。
                    matcher = images.createSiftMatcher(templateImage, { scale: 0.5, templateScale: ts });
                    location = matcher.match(image); // 没匹配到返回 null
                    if (location) {
                        var skew = quadScaleRatio(location, templateImage.width, templateImage.height);
                        if (skew > 1.15) {
                            attemptNote = '匹到但四角比例失衡（横竖差 ' + ((skew - 1) * 100).toFixed(0) + '%），判为误匹';
                            location = null;
                        } else {
                            usedScale = ts;
                            attemptNote = '匹到';
                        }
                    } else {
                        attemptNote = '没匹到';
                    }
                } catch (error) {
                    attemptNote = '出错（' + String(error && error.message ? error.message : error) + '）';
                } finally {
                    if (matcher) {
                        try { matcher.recycle(); } catch (error) { /* 已回收 */ }
                    }
                }
                attempts.push('×' + ts.toFixed(2) + ' ' + attemptNote);
                console.log('[象棋] SIFT 试模板×' + ts.toFixed(2) + '（第 ' + (a + 1) + '/' + scaleOrder.length +
                    ' 档，特征 ' + (matcher ? matcher.templateFeatureCount : 0) + '）：' +
                    (performance.now() - attemptStarted).toFixed(0) + 'ms ' + attemptNote);
            }
            locateMs = performance.now() - locateStarted;
            if (location) {
                boardRegion = regionOfCorners(location, image.width, image.height);
                if (boardRegion) {
                    boardCorners = [location.topLeft, location.topRight, location.bottomRight, location.bottomLeft];
                }
            }
            if (boardRegion) {
                locateInfo = { matchCount: location.matchCount, locateMs: Math.round(locateMs),
                    region: boardRegion, templateScale: usedScale };
                var cornerText = '';
                var corners = [['左上', location.topLeft], ['右上', location.topRight],
                    ['右下', location.bottomRight], ['左下', location.bottomLeft]];
                for (var c = 0; c < corners.length; c++) {
                    cornerText += (c ? ' ' : '') + corners[c][0] + '(' + Math.round(corners[c][1].x) + ',' +
                        Math.round(corners[c][1].y) + ')';
                }
                console.log('[象棋] SIFT 定位棋盘 ' + locateMs.toFixed(0) + 'ms（模板×' + usedScale.toFixed(2) +
                    '）：区域 (' + boardRegion[0] + ', ' + boardRegion[1] + ') ' + boardRegion[2] + 'x' +
                    boardRegion[3] + '，可靠内点 ' + location.matchCount);
                console.log('        四角：' + cornerText);
            } else {
                console.log('[象棋] SIFT 没定位到棋盘' + (location ? '（四角不完整）' : '（试了 ' +
                    attempts.join('、') + '）') + ' → 退回全屏识别（皮肤不同 / 棋盘被挡 / 不在画面里都可能）');
            }
        } catch (error) {
            console.log('[象棋] ⚠ 棋盘模板加载失败（' + String(error && error.message ? error.message : error) +
                '）→ 退回全屏识别');
        } finally {
            if (templateImage) {
                try { templateImage.recycle(); } catch (error) { /* 已回收 */ }
            }
        }
    }

    // ============================ 4. 区域识别（yolo + region） ============================

    // region=[x,y,w,h]：宿主只在棋盘区域里推理（原生先裁出区域、检测框再平移回全屏坐标），
    // 区域外的吃子栏、按钮、面板不会算成棋子；坐标不用自己加偏移。
    var regionOptions = { confidence: CONFIDENCE, nms: NMS };
    if (boardRegion) {
        regionOptions.region = boardRegion;
        console.log('[象棋] 识别范围：棋盘区域 (' + boardRegion.join(', ') + ')');
    } else {
        console.log('[象棋] 识别范围：整屏（SIFT 不可用时的兜底口径）');
    }

    var result = null;
    var fullResult = null;
    var rawCount = 0;
    var rawFullCount = null;
    var sizeFilterRemoved = 0;
    var sizeFilterRemovedFull = 0;
    var movingDropped = 0;
    var glyphStats = null;
    try {
        var detectStarted = performance.now();
        result = detector.detect(image, regionOptions);
        // 对照看看：同一帧再按整屏跑一次，对比 region 到底筛掉了什么
        if (COMPARE_FULL && boardRegion) {
            fullResult = detector.detect(image, { confidence: CONFIDENCE, nms: NMS });
        }
        detectMs = performance.now() - detectStarted;   // 检测（含对照的全屏那一遍）
        // 去重、框大小过滤、红黑判定都必须放原图回收前（取色要在每个框里多点取）
        rawCount = result.length;
        result = dedupeDetections(result, DEDUPE_IOU);
        if (fullResult) {
            rawFullCount = fullResult.length;
            fullResult = dedupeDetections(fullResult, DEDUPE_IOU);
        }
        if (rawCount !== result.length || (rawFullCount != null && rawFullCount !== fullResult.length)) {
            console.log('[象棋] 去重（重叠 IoU≥' + DEDUPE_IOU + ' 只留分高的）：区域 ' + rawCount + '→' +
                result.length + (rawFullCount == null ? '' : '；全屏 ' + rawFullCount + '→' + fullResult.length));
        }
        // 框大小过滤：和「框宽中位数」差太远的多半是游戏 UI（头像/按钮/徽章），不是棋子
        var sizeKept = filterByRelativeSize(result);
        sizeFilterRemoved = sizeKept.removed;
        result = sizeKept.kept;
        if (fullResult) {
            var fullKept = filterByRelativeSize(fullResult);
            sizeFilterRemovedFull = fullKept.removed;
            fullResult = fullKept.kept;
        }
        if (sizeFilterRemoved + sizeFilterRemovedFull > 0) {
            console.log('[象棋] 框大小过滤（中位×' + SIZE_FILTER_MIN + '~' + SIZE_FILTER_MAX + ' 之外）：区域 ' +
                result.length + '（去掉 ' + sizeFilterRemoved + '）' +
                (fullResult ? '；全屏 ' + fullResult.length + '（去掉 ' + sizeFilterRemovedFull + '）' : ''));
        }
        if (SIDE_DETECT) {
            var sideStarted = performance.now();
            judgeSides(image, result);
            sideMs = performance.now() - sideStarted;
        }
        // 字形复核：模型认错的子（如帅→仕）按字形模板改回来；单次模式用完即回收
        if (glyphTemplates) {
            var glyphRecheckStarted = performance.now();
            try {
                glyphStats = glyphRecheck(image, result);
            } catch (error) {
                console.log('[象棋] ⚠ 字形复核跳过：' + String(error && error.message ? error.message : error));
            }
            glyphMs = performance.now() - glyphRecheckStarted;
            if (LOOP_FRAMES <= 1) {   // 连续模式留到循环结束再回收（不然下一帧没模板可用）
                for (var gr = 0; gr < glyphTemplates.length; gr++) {
                    try { glyphTemplates[gr].image.recycle(); } catch (error) { /* 已回收 */ }
                }
                glyphTemplates = null;
            }
        }
        // 空中子过滤：落子动画里飞在半空的子是运动模糊的——不该当棋子数（按空处理，同工程口径）
        if (MOVING_FILTER) {
            var movingStarted = performance.now();
            try {
                var movingKept = movingFilter(image, result);
                movingDropped = movingKept.removed;
                result = movingKept.kept;
            } catch (error) {
                console.log('[象棋] ⚠ 空中子过滤跳过：' + String(error && error.message ? error.message : error));
            }
            movingMs = performance.now() - movingStarted;
        }
    } finally {
        // 帧/图用完要回收，否则连续跑会一直占内存
        try { image.recycle(); } catch (error) { /* 已经回收过就算了 */ }
    }

    /** 两框的交并比（IoU）。 */
    function overlapRatio(a, b) {
        var left = Math.max(a.left, b.left), top = Math.max(a.top, b.top);
        var right = Math.min(a.right, b.right), bottom = Math.min(a.bottom, b.bottom);
        if (right <= left || bottom <= top) {
            return 0;
        }
        var inter = (right - left) * (bottom - top);
        var union = (a.right - a.left) * (a.bottom - a.top) +
            (b.right - b.left) * (b.bottom - b.top) - inter;
        return union > 0 ? inter / union : 0;
    }

    /** 去重：同一个棋子常会出好几个叠在一起的框（阈值调低后更明显）——按分数保留最高的。 */
    function dedupeDetections(detections, iouThreshold) {
        if (iouThreshold <= 0) {
            return detections;
        }
        var sorted = detections.slice().sort(function (a, b) { return b.score - a.score; });
        var kept = [];
        for (var i = 0; i < sorted.length; i++) {
            var candidate = sorted[i].bounds;
            var duplicate = false;
            if (candidate) {
                for (var j = 0; j < kept.length; j++) {
                    if (kept[j].bounds && overlapRatio(candidate, kept[j].bounds) >= iouThreshold) {
                        duplicate = true;
                        break;
                    }
                }
            }
            if (!duplicate) {
                kept.push(sorted[i]);
            }
        }
        // 耗时字段是挂在数组上的，换数组后要搬过来（下面「耗时」还要打）
        kept.preprocessMs = detections.preprocessMs;
        kept.inferenceMs = detections.inferenceMs;
        kept.totalMs = detections.totalMs;
        return kept;
    }

    /** 框大小过滤：棋子框大小彼此接近（≈一个格距），和「中位宽」差太远的多半是 UI 元素，丢掉。 */
    function filterByRelativeSize(detections) {
        var widths = [];
        for (var i = 0; i < detections.length; i++) {
            var box = detections[i].bounds;
            if (box) {
                widths.push(box.right - box.left);
            }
        }
        if (widths.length < 4 || widths.length < detections.length * 0.6) {
            return { kept: detections, removed: 0 };   // 框太少 / 大多没框：样本不够，不过滤
        }
        widths.sort(function (a, b) { return a - b; });
        var median = widths[Math.floor(widths.length / 2)];
        var kept = [];
        var removed = 0;
        for (var d = 0; d < detections.length; d++) {
            var bounds = detections[d].bounds;
            if (!bounds) {
                kept.push(detections[d]);
                continue;
            }
            var width = bounds.right - bounds.left;
            if (width < median * SIZE_FILTER_MIN || width > median * SIZE_FILTER_MAX) {
                removed += 1;
                continue;
            }
            kept.push(detections[d]);
        }
        kept.preprocessMs = detections.preprocessMs;
        kept.inferenceMs = detections.inferenceMs;
        kept.totalMs = detections.totalMs;
        return { kept: kept, removed: removed };
    }

    /**
     * 空中子过滤（模糊门）：落子动画里飞在半空的子是运动模糊的——它不该被当棋子数。
     * 框心窗内采样离散拉普拉斯方差（越大越清晰），以「字形复核认准过的框」的中位数为基准，
     * 低于 基准×MOVING_RATIO 的判「没落稳」丢弃（同工程 glyph-recognizer 的 MOVING_* 口径）。
     * @return { kept, removed }
     */
    function movingFilter(image, detections) {
        if (detections.length < 3) {
            return { kept: detections, removed: 0 };
        }
        var widths = [];
        for (var i = 0; i < detections.length; i++) {
            var box = detections[i].bounds;
            if (box) {
                widths.push(box.right - box.left);
            }
        }
        if (widths.length < 3) {
            return { kept: detections, removed: 0 };
        }
        widths.sort(function (a, b) { return a - b; });
        var side = Math.round(widths[Math.floor(widths.length / 2)] * GLYPH_BOX_RATIO);
        var radius = Math.max(8, Math.round(side / 2));
        var values = [];
        var refs = [];
        for (var d = 0; d < detections.length; d++) {
            var bounds = detections[d].bounds;
            if (!bounds) {
                values.push(null);
                continue;
            }
            var value = windowSharpness(image,
                Math.round((bounds.left + bounds.right) / 2),
                Math.round((bounds.top + bounds.bottom) / 2), radius);
            values.push(value);
            if (value != null && detections[d].glyph) {
                refs.push(value);   // 字形复核认准过 = 静止子，当基准样本
            }
        }
        if (refs.length < 2) {
            refs = values.filter(function (v) { return v != null; });
        }
        if (refs.length < 3) {
            return { kept: detections, removed: 0 };   // 样本不够，不判
        }
        refs.sort(function (a, b) { return a - b; });
        var base = refs[Math.floor(refs.length / 2)];
        var gate = base * MOVING_RATIO;
        var kept = [];
        var removed = 0;
        for (var k = 0; k < detections.length; k++) {
            if (values[k] != null && values[k] < gate) {
                removed += 1;
                continue;   // 没落稳：按空处理
            }
            kept.push(detections[k]);
        }
        if (removed > 0) {
            console.log('[象棋] 空中子过滤（清晰度 < 中位×' + MOVING_RATIO + '）：' + detections.length +
                ' 枚里滤掉 ' + removed + ' 枚（基准 ' + Math.round(base) + '，门槛 ' + Math.round(gate) + '）');
        }
        kept.preprocessMs = detections.preprocessMs;
        kept.inferenceMs = detections.inferenceMs;
        kept.totalMs = detections.totalMs;
        return { kept: kept, removed: removed };
    }

    /** 框心窗内的清晰度（采样网格的离散拉普拉斯方差）：越大越清晰；取不到像素返回 null。 */
    function windowSharpness(image, cx, cy, radius) {
        var n = Math.floor((radius * 2) / MOVING_STEP) + 1;
        if (n < 3) {
            return null;
        }
        var vals = [];
        for (var j = 0; j < n; j++) {
            for (var i = 0; i < n; i++) {
                var color = null;
                try {
                    color = images.pixel(image, cx - radius + i * MOVING_STEP, cy - radius + j * MOVING_STEP);
                } catch (error) {
                    return null;
                }
                var r = (color >>> 16) & 0xFF;
                var g = (color >>> 8) & 0xFF;
                var b = color & 0xFF;
                vals.push((r * 299 + g * 587 + b * 114) / 1000);
            }
        }
        var sum = 0;
        var sumSq = 0;
        var count = 0;
        for (var jj = 1; jj < n - 1; jj++) {
            for (var ii = 1; ii < n - 1; ii++) {
                var l = 4 * vals[jj * n + ii] - vals[jj * n + ii - 1] - vals[jj * n + ii + 1] -
                    vals[(jj - 1) * n + ii] - vals[(jj + 1) * n + ii];
                sum += l;
                sumSq += l * l;
                count += 1;
            }
        }
        if (!count) {
            return null;
        }
        var mean = sum / count;
        return sumSq / count - mean * mean;
    }

    // （去重/过滤的日志已经在上面 try 里打过；后面的统计/画板/标记用的都是去重 + 过滤后的框）

    // 红黑汇总（给日志/画板/标记用；判不出的算 unknown，不猜）
    var sideSummary = null;
    if (SIDE_DETECT) {
        sideSummary = { red: 0, black: 0, unknown: 0 };
        for (var sx = 0; sx < result.length; sx++) {
            if (result[sx].side === 'RED') {
                sideSummary.red += 1;
            } else if (result[sx].side === 'BLACK') {
                sideSummary.black += 1;
            } else {
                sideSummary.unknown += 1;
            }
        }
    }

    /** 红笔画像素：红要够亮、且明显压过绿和蓝（盘沿/木纹的暖色压不过才挡得住）。 */
    function isRedInk(r, g, b) {
        return r > SIDE_RED_MIN && r > g * SIDE_RED_G_DOMINANCE && r > b * SIDE_RED_B_DOMINANCE;
    }

    /** 黑笔画像素：三个通道都暗。 */
    function isDarkInk(r, g, b) {
        return r < SIDE_DARK_MAX && g < SIDE_DARK_MAX && b < SIDE_DARK_MAX;
    }

    /** 在检测框里**多点取色**判红黑：只采框中间（SIDE_SAMPLE_INSET 内缩），红/黑笔画占比说话。 */
    function judgeSide(image, bounds) {
        var width = bounds.right - bounds.left;
        var height = bounds.bottom - bounds.top;
        var inset = SIDE_SAMPLE_INSET;
        var left = Math.round(bounds.left + width * inset);
        var top = Math.round(bounds.top + height * inset);
        var right = Math.round(bounds.right - width * inset);
        var bottom = Math.round(bounds.bottom - height * inset);
        var red = 0, dark = 0, counted = 0, rSum = 0, gSum = 0, bSum = 0;
        for (var y = top; y <= bottom; y += SIDE_SAMPLE_STEP) {
            for (var x = left; x <= right; x += SIDE_SAMPLE_STEP) {
                var color = null;
                try {
                    color = images.pixel(image, x, y);   // 逻辑坐标，宿主自己换算到像素
                } catch (error) {
                    continue;   // 越界/已回收的采样点，跳过
                }
                var r = (color >>> 16) & 0xFF;
                var g = (color >>> 8) & 0xFF;
                var b = color & 0xFF;
                counted += 1;
                rSum += r; gSum += g; bSum += b;
                if (isRedInk(r, g, b)) {
                    red += 1;
                } else if (isDarkInk(r, g, b)) {
                    dark += 1;
                }
            }
        }
        var evidence = {
            side: 'UNKNOWN', counted: counted, redShare: 0, darkShare: 0,
            meanR: counted ? Math.round(rSum / counted) : 0,
            meanG: counted ? Math.round(gSum / counted) : 0,
            meanB: counted ? Math.round(bSum / counted) : 0
        };
        if (counted < 30) {
            return evidence;
        }
        evidence.redShare = red / counted;
        evidence.darkShare = dark / counted;
        if (evidence.redShare >= 0.02 && evidence.redShare > evidence.darkShare * 1.2) {
            evidence.side = 'RED';
        } else if (evidence.darkShare >= SIDE_DARK_MIN_SHARE && evidence.redShare <= evidence.darkShare * 1.2) {
            evidence.side = 'BLACK';
        }
        return evidence;
    }

    /** 给去重后的每个框挂 side 字段（RED / BLACK / UNKNOWN）；SIDE_DEBUG 开时逐枚打证据。 */
    function judgeSides(image, detections) {
        for (var i = 0; i < detections.length; i++) {
            var bounds = detections[i].bounds;
            var evidence = bounds ? judgeSide(image, bounds) : { side: 'UNKNOWN', counted: 0 };
            detections[i].side = evidence.side;
            if (SIDE_DEBUG) {
                var spot = bounds ? '(' + Math.round((bounds.left + bounds.right) / 2) + ',' +
                    Math.round((bounds.top + bounds.bottom) / 2) + ')' : '(无框)';
                console.log('[取色] ' + pieceName(detections[i]) + spot + ' → ' + evidence.side +
                    '  红=' + (evidence.redShare || 0).toFixed(3) + ' 暗=' + (evidence.darkShare || 0).toFixed(3) +
                    ' 均色=(' + (evidence.meanR || 0) + ',' + (evidence.meanG || 0) + ',' + (evidence.meanB || 0) + ')' +
                    ' n=' + (evidence.counted || 0));
            }
        }
    }

    /** 检测项 → 中文名（红车 / 黑炮 / 马?）。字形复核认到的字更准（不再用「相/象」这种合并名）。 */
    function pieceName(item) {
        var name = item.glyph
            ? (GLYPH_TEXT[item.glyph] || item.glyph)
            : (CLASS_TEXT[item.label] || item.label);
        if (!SIDE_DETECT || item.side === undefined) {
            return name;
        }
        if (item.side === 'RED') {
            return '红' + name;
        }
        if (item.side === 'BLACK') {
            return '黑' + name;
        }
        return name + '?';
    }

    /**
     * 字形复核：拿 11 个模板在框里认字（整帧共用一个缩放：框宽中位数 × GLYPH_BOX_RATIO）。
     * 只认「分数 ≥ GLYPH_CONF 且头名拉开 ≥ GLYPH_MARGIN」的；认不准就保持模型结论。
     * 第一轮还有没认准的框时，按 GLYPH_RESCUE_SCALES 换缩放补试（棋盘/棋子比推算尺寸大/小一点时救场），
     * 每枚取各轮里分最高的结论，门槛照旧 —— 只修不改进坏。
     */
    function glyphRecheck(image, detections) {
        var stats = { checked: 0, accepted: 0, fixes: 0, rounds: 0, bestSim: 0 };
        var widths = [];
        for (var i = 0; i < detections.length; i++) {
            var box = detections[i].bounds;
            if (box) {
                widths.push(box.right - box.left);
            }
        }
        if (!widths.length) {
            return stats;
        }
        widths.sort(function (a, b) { return a - b; });
        var side = Math.round(widths[Math.floor(widths.length / 2)] * GLYPH_BOX_RATIO);
        if (side < 16 || side >= Math.min(image.width, image.height)) {
            return stats;
        }
        var gray = null;
        var roundSides = [side];   // 第一轮用推算值；没全认准就补几档
        try {
            gray = images.grayscale(image);   // 模板是灰度图，帧也转灰度再匹配（同工程口径）
            var merged = {};   // 框序号 → 各轮里最优的 { name, sim, gap }
            for (var r = 0; r < roundSides.length; r++) {
                var pass = glyphPass(gray, detections, roundSides[r], image.width, image.height);
                stats.rounds = r + 1;
                stats.checked = Math.max(stats.checked, pass.checked);
                var acceptedNow = 0;
                for (var key in pass.picks) {
                    if (!Object.prototype.hasOwnProperty.call(pass.picks, key)) {
                        continue;
                    }
                    var pick = pass.picks[key];
                    if (!merged[key] || pick.sim > merged[key].sim) {
                        merged[key] = pick;
                    }
                }
                for (var checkKey in merged) {
                    if (Object.prototype.hasOwnProperty.call(merged, checkKey) &&
                        merged[checkKey].sim >= GLYPH_CONF && merged[checkKey].gap >= GLYPH_MARGIN) {
                        acceptedNow += 1;
                    }
                }
                if (acceptedNow >= pass.checked) {
                    break;   // 全都认准了才收工；还有没认准的框就换档补试（棋子尺寸略偏时救命）
                }
                if (r === 0 && pass.checked >= 3) {
                    for (var rs = 0; rs < GLYPH_RESCUE_SCALES.length; rs++) {
                        roundSides.push(Math.round(side * GLYPH_RESCUE_SCALES[rs]));
                    }
                }
            }
            // 合并后统一裁决（按框的先后修正，日志顺序稳定）
            var indexes = [];
            for (var mergedKey in merged) {
                if (Object.prototype.hasOwnProperty.call(merged, mergedKey)) {
                    indexes.push(Number(mergedKey));
                }
            }
            indexes.sort(function (a, b) { return a - b; });
            for (var n = 0; n < indexes.length; n++) {
                var best = merged[indexes[n]];
                if (best.sim > stats.bestSim) {
                    stats.bestSim = best.sim;
                }
                if (best.sim < GLYPH_CONF || best.gap < GLYPH_MARGIN) {
                    continue;   // 不够确信：不改
                }
                stats.accepted += 1;
                var item = detections[indexes[n]];
                var itemBounds = item.bounds;
                var cx = Math.round((itemBounds.left + itemBounds.right) / 2);
                var cy = Math.round((itemBounds.top + itemBounds.bottom) / 2);
                var nextLabel = GLYPH_CLASS[best.name] || item.label;
                var nextSide = GLYPH_SIDE[best.name] || item.side;
                if (nextLabel !== item.label || nextSide !== item.side) {
                    item.modelLabel = item.label;
                    console.log('[象棋] 字形复核：修正 ' + (CLASS_TEXT[item.label] || item.label) +
                        '(' + cx + ',' + cy + ') → ' + (GLYPH_TEXT[best.name] || best.name) +
                        '（sim ' + best.sim.toFixed(2) + '、差 ' + best.gap.toFixed(2) + '）');
                    item.label = nextLabel;
                    item.side = nextSide;
                    stats.fixes += 1;
                }
                item.glyph = best.name;
            }
        } finally {
            if (gray) {
                try { gray.recycle(); } catch (error) { /* 已回收 */ }
            }
        }
        console.log('[象棋] 字形复核：' + stats.checked + ' 枚里认准 ' + stats.accepted +
            ' 枚（模板 ' + side + 'px' + (stats.rounds > 1 ? ' + 补试 ' + (stats.rounds - 1) + ' 档' : '') +
            '，最高分 ' + stats.bestSim.toFixed(2) + '），修正 ' + stats.fixes + ' 枚');
        return stats;
    }

    /** 字形复核对全部框跑一轮（指定模板边长）：返回每框的最优字与分差。 */
    function glyphPass(gray, detections, side, frameWidth, frameHeight) {
        var pass = { checked: 0, picks: {} };
        var scaled = [];
        try {
            for (var t = 0; t < glyphTemplates.length; t++) {
                var base = glyphTemplates[t].image;
                if (base.width === side && base.height === side) {
                    scaled.push({ name: glyphTemplates[t].name, image: base, owned: false });
                } else {
                    scaled.push({ name: glyphTemplates[t].name,
                        image: images.resize(base, [side, side]), owned: true });
                }
            }
            // 窗口「横紧纵松」：横方向字形几乎不漂（±7px 够），纵方向弱框/带影子的框中心
            // 会比字形中心低 10~15px（新手机实测兵框中心低 14px）——纵窗给 ±25%，同工程链口径。
            var padX = Math.max(6, Math.round(side * 0.09));
            var padY = Math.max(12, Math.round(side * 0.25));
            for (var d = 0; d < detections.length; d++) {
                var bounds = detections[d].bounds;
                if (!bounds) {
                    continue;
                }
                var cx = Math.round((bounds.left + bounds.right) / 2);
                var cy = Math.round((bounds.top + bounds.bottom) / 2);
                var rx = cx - Math.round(side / 2) - padX;
                var ry = cy - Math.round(side / 2) - padY;
                var rw = side + padX * 2;
                var rh = side + padY * 2;
                if (rx < 0 || ry < 0 || rx + rw > frameWidth || ry + rh > frameHeight) {
                    continue;   // 贴边：窗口放不下就不复核（保持模型结论）
                }
                pass.checked += 1;
                var scores = [];
                for (var m = 0; m < scaled.length; m++) {
                    var found = images.matchTemplate(gray, scaled[m].image,
                        { region: [rx, ry, rw, rh], threshold: 0, max: 1 });
                    var hit = found && found.matches && found.matches.length ? found.matches[0] : null;
                    if (hit) {
                        scores.push({ name: scaled[m].name, sim: hit.similarity });
                    }
                }
                if (!scores.length) {
                    continue;
                }
                scores.sort(function (a, b) { return b.sim - a.sim; });
                var top = scores[0];
                pass.picks[d] = {
                    name: top.name,
                    sim: top.sim,
                    gap: scores.length > 1 ? top.sim - scores[1].sim : 1
                };
            }
        } finally {
            for (var s = 0; s < scaled.length; s++) {
                if (scaled[s].owned) {
                    try { scaled[s].image.recycle(); } catch (error) { /* 已回收 */ }
                }
            }
        }
        return pass;
    }

    /** 检测框中心是否落在棋盘区域外（2px 容差）。 */
    function outsideRegion(bounds) {
        if (!boardRegion || !bounds || bounds.centerX == null) {
            return false;
        }
        return bounds.centerX < boardRegion[0] - 2 || bounds.centerX > boardRegion[0] + boardRegion[2] + 2 ||
            bounds.centerY < boardRegion[1] - 2 || bounds.centerY > boardRegion[1] + boardRegion[3] + 2;
    }

    // 全屏多出来的「棋盘外」框（region 口径会把这些筛掉；画板用「屏外·」前缀画出来）
    var fullOutside = [];
    if (fullResult) {
        for (var fi = 0; fi < fullResult.length; fi++) {
            if (outsideRegion(fullResult[fi].bounds || {})) {
                fullOutside.push(fullResult[fi]);
            }
        }
        console.log('[象棋] 对照：全屏 ' + fullResult.length + ' 框/' + fullResult.totalMs.toFixed(1) + 'ms ｜ 区域 ' +
            result.length + ' 框/' + result.totalMs.toFixed(1) + 'ms ｜ 全屏多出（棋盘外）' + fullOutside.length + ' 框');
    }

    // 区域结果里不该出现棋盘外的框；出现了就说明这个宿主没接住 region（或版本太老）
    if (boardRegion) {
        var leaked = 0;
        for (var pi = 0; pi < result.length; pi++) {
            if (outsideRegion(result[pi].bounds || {})) {
                leaked++;
            }
        }
        if (leaked > 0) {
            console.log('[象棋] ⚠ 区域结果里有 ' + leaked + ' 个框落在棋盘区域外 —— 这个宿主可能还不支持 region（参数被忽略）');
        }
    }

    console.log('[象棋] 耗时：预处理 ' + result.preprocessMs.toFixed(1) + 'ms / 推理 ' + result.inferenceMs.toFixed(1) +
        'ms / 合计 ' + result.totalMs.toFixed(1) + 'ms');

    // 耗时分期：一轮各阶段用时（「合计」不含画板停留；单次模式从脚本启动算起，连续模式为本帧用时）
    var runTotalMs = performance.now() - runStarted;
    loopCount += 1;
    loopSum += runTotalMs;
    if (loopCount === 1 || runTotalMs < loopMin) loopMin = runTotalMs;
    if (runTotalMs > loopMax) loopMax = runTotalMs;
    if (LOOP_FRAMES > 1) {
        console.log('[象棋] 第 ' + roundIndex + ' 帧耗时分期：取帧 ' + Math.round(frameMs) + 'ms｜SIFT 定位 ' +
            Math.round(locateMs) + 'ms｜检测 ' + Math.round(detectMs) + 'ms｜取色 ' + Math.round(sideMs) +
            'ms｜字形复核 ' + Math.round(glyphMs) + 'ms｜空中子 ' + Math.round(movingMs) + 'ms｜合计 ' +
            Math.round(runTotalMs) + 'ms');
    } else {
        console.log('[象棋] 耗时分期：模型 ' + Math.round(modelMs) + 'ms｜模板 ' + Math.round(glyphTplMs) +
            'ms｜取帧 ' + Math.round(frameMs) + 'ms｜SIFT 定位 ' + Math.round(locateMs) + 'ms｜检测 ' +
            Math.round(detectMs) + 'ms｜取色 ' + Math.round(sideMs) + 'ms｜字形复核 ' + Math.round(glyphMs) +
            'ms｜空中子 ' + Math.round(movingMs) + 'ms｜合计 ' + Math.round(runTotalMs) + 'ms');
    }

    // ============================ 5. 看结果 ============================

    var counts = {};       // 每类几个（合并类）
    var boxes = [];        // 每个框：{label, side, score, x, y}
    var minScore = 1;
    var maxScore = 0;
    for (var i = 0; i < result.length; i++) {
        var item = result[i];
        counts[item.label] = (counts[item.label] || 0) + 1;
        var bounds = item.bounds || {};
        boxes.push({
            label: item.label,
            side: item.side,
            glyph: item.glyph,
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
        if (SIDE_DETECT) {
            console.log('[象棋] 红黑（框内多点取色）：红=' + sideSummary.red + ' 黑=' + sideSummary.black +
                ' 未知=' + sideSummary.unknown);
            var nameByClass = {};      // 类 → { 全名: 个数 }（字形复核认准的用精确字名）
            for (var nc = 0; nc < result.length; nc++) {
                var pieceFullName = pieceName(result[nc]);
                if (!nameByClass[result[nc].label]) {
                    nameByClass[result[nc].label] = {};
                }
                nameByClass[result[nc].label][pieceFullName] =
                    (nameByClass[result[nc].label][pieceFullName] || 0) + 1;
            }
            var nameParts = [];
            for (var nk = 0; nk < order.length; nk++) {
                var group = nameByClass[order[nk]];
                for (var nm in group) {
                    if (Object.prototype.hasOwnProperty.call(group, nm)) {
                        nameParts.push(nm + '=' + group[nm]);
                    }
                }
            }
            console.log('[象棋] 全名：' + (nameParts.length ? nameParts.join('  ') : '（没认出来）'));
        }
        console.log('[象棋] 分数：' + minScore.toFixed(2) + ' ~ ' + maxScore.toFixed(2));
        console.log('[象棋] 分数最高的 10 个：');
        for (var b = 0; b < boxes.length && b < 10; b++) {
            var box = boxes[b];
            console.log('        ' + pieceName(box) +
                ' ' + (box.score * 100).toFixed(1) + '%  中心 (' + Math.round(box.x) + ', ' + Math.round(box.y) + ')');
        }
    } else {
        console.log('[象棋] 一个棋子都没认出来，按顺序查这三条：');
        console.log('        ① 画面里得真的是棋局（桌面/聊天页认不出来是正常的，模型只见过棋盘）');
        console.log('        ② 棋盘要完整、别太小：看上面 SIFT 有没有定位到；没定位到会走全屏兜底，棋子更小');
        console.log('        ③ 阈值已是拉满召回档（0.001），不是阈值问题 —— 先确认画面里是棋局、棋盘完整');
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
        mode: boardRegion ? 'sift+region' : 'full-frame',
        board: locateInfo,
        fullDetections: fullResult ? fullResult.length : null,
        model: MODEL_PATH,
        input: INPUT_WIDTH + 'x' + INPUT_HEIGHT,
        detections: result.length,
        rawDetections: rawCount,
        sizeFiltered: sizeFilterRemoved,
        movingFiltered: movingDropped,
        sides: sideSummary,
        glyph: glyphStats,
        counts: counts,
        preprocessMs: result.preprocessMs,
        inferenceMs: result.inferenceMs,
        totalMs: result.totalMs,
        timing: { modelMs: Math.round(modelMs), glyphTplMs: Math.round(glyphTplMs), frameMs: Math.round(frameMs),
            locateMs: Math.round(locateMs), detectMs: Math.round(detectMs), sideMs: Math.round(sideMs),
            glyphMs: Math.round(glyphMs), movingMs: Math.round(movingMs), runMs: Math.round(runTotalMs) }
    });

    // ============================ 6. 画框看看（可选，需悬浮窗权限） ============================

    // 宿主画板只有「矩形 + 标签」（颜色写死：绿框、黄字），线就用细长矩形代替；
    // 框上的黄字是宿主固定拼的「标签 + 分数%」，空标签也会画（只剩一串分数%，不是识别错误）。
    // 画板是系统悬浮窗、会被截进画面：本示例「先截帧、后画框」所以没事；连续跑要每帧先 drawing.hide()。
    if (DRAW && typeof drawing !== 'undefined' && typeof drawing.show === 'function') {
        if (!drawing.show()) {
            console.log('[象棋] ⚠ 画板打不开（多半是没给「显示在其他应用上层」权限）→ 只打日志不画框');
        } else {
            var marks = [];
            if (boardCorners) {
                // 1) SIFT 匹配到的模板外框：四边（细长矩形当线）+ 四角标记
                var half = 1.5;
                var topY = (boardCorners[0].y + boardCorners[1].y) / 2;
                var bottomY = (boardCorners[2].y + boardCorners[3].y) / 2;
                var leftX = (boardCorners[0].x + boardCorners[3].x) / 2;
                var rightX = (boardCorners[1].x + boardCorners[2].x) / 2;
                marks.push({ x1: boardCorners[0].x, y1: topY - half, x2: boardCorners[1].x, y2: topY + half, label: '', score: 1 });
                marks.push({ x1: boardCorners[3].x, y1: bottomY - half, x2: boardCorners[2].x, y2: bottomY + half, label: '', score: 1 });
                marks.push({ x1: leftX - half, y1: boardCorners[0].y, x2: leftX + half, y2: boardCorners[3].y, label: '', score: 1 });
                marks.push({ x1: rightX - half, y1: boardCorners[1].y, x2: rightX + half, y2: boardCorners[2].y, label: '', score: 1 });
                var cornerNames = ['左上', '右上', '右下', '左下'];
                for (var n = 0; n < boardCorners.length && n < cornerNames.length; n++) {
                    marks.push({ x1: boardCorners[n].x - 10, y1: boardCorners[n].y - 10,
                        x2: boardCorners[n].x + 10, y2: boardCorners[n].y + 10, label: cornerNames[n], score: 1 });
                }
            }
            // 2) 交给识别的「棋盘区域」
            if (boardRegion) {
                marks.push({ x1: boardRegion[0], y1: boardRegion[1],
                    x2: boardRegion[0] + boardRegion[2], y2: boardRegion[1] + boardRegion[3],
                    label: '棋盘区域', score: 1 });
            }
            // 3) 识别出的棋子框（标签用中文名，方便肉眼比对）
            for (var m = 0; m < result.length && m < 60; m++) {
                var mark = result[m].bounds || {};
                if (mark.left == null || mark.top == null) {
                    continue;
                }
                marks.push({ x1: mark.left, y1: mark.top, x2: mark.right, y2: mark.bottom,
                    label: pieceName(result[m]), score: result[m].score });
            }
            // 4) 对照看看：全屏口径里「棋盘外」的框（region 会筛掉它们，用前缀区分）
            for (var q = 0; q < fullOutside.length && q < 20; q++) {
                var fb = fullOutside[q].bounds || {};
                if (fb.left == null || fb.top == null) {
                    continue;
                }
                marks.push({ x1: fb.left, y1: fb.top, x2: fb.right, y2: fb.bottom,
                    label: '屏外·' + (CLASS_TEXT[fullOutside[q].label] || fullOutside[q].label),
                    score: fullOutside[q].score });
            }
            var dedupedOut = rawCount - result.length - sizeFilterRemoved;
            var statsText = (boardRegion ? 'SIFT 区域' : '全屏兜底') + ' · 棋子 ' + result.length +
                (dedupedOut > 0 ? ' · 去重 ' + dedupedOut : '') +
                (sizeFilterRemoved > 0 ? ' · 滤掉 ' + sizeFilterRemoved : '') +
                (movingDropped > 0 ? ' · 空中子 ' + movingDropped : '') +
                (fullOutside.length > 0 ? ' · 屏外 ' + fullOutside.length : '') +
                ' · ' + Math.round(runTotalMs) + 'ms';
            drawing.update(marks, statsText);
            var holdMs = LOOP_FRAMES > 1 ? 0 : DRAW_HOLD_MS;   // 连续模式不在帧内停留（下一帧前会收画板）
            console.log('[象棋] 画板已显示：' + statsText + (holdMs > 0
                ? '（' + (holdMs / 1000) + ' 秒后自动关闭，先看屏幕）'
                : (LOOP_FRAMES > 1 ? '（连续模式：下一帧前自动收）' : '（脚本结束即关闭）')));
            if (holdMs > 0) {
                sleep(holdMs);
            }
            if (LOOP_FRAMES <= 1) {
                drawing.hide();
                console.log('[象棋] 画板已关闭');
            }
        }
    }
}

// ============================ 连续识别驱动 ============================
// LOOP_FRAMES ≤ 1：只跑一次（默认，输出与单帧版一致）；> 1：连续跑 N 帧。
// 每帧前收画板→截图→识别→画框；逐帧打耗时分期；结束给平均/最小/最大。停止脚本即结束。
if (LOOP_FRAMES > 1) {
    var loopWallStarted = performance.now();
    for (var loopIndex = 1; loopIndex <= LOOP_FRAMES; loopIndex++) {
        recognizeOnce(loopIndex);
        if (loopIndex < LOOP_FRAMES && LOOP_SLEEP_MS > 0) {
            sleep(LOOP_SLEEP_MS);
        }
    }
    var loopWallMs = performance.now() - loopWallStarted;
    console.log('[象棋] 连续统计：共 ' + loopCount + ' 帧｜单帧合计 平均 ' + Math.round(loopSum / loopCount) +
        'ms（最小 ' + Math.round(loopMin) + '｜最大 ' + Math.round(loopMax) + '）｜总时长 ' +
        Math.round(loopWallMs) + 'ms');
    if (glyphTemplates) {   // 连续模式到这里才回收字形模板
        for (var gt = 0; gt < glyphTemplates.length; gt++) {
            try { glyphTemplates[gt].image.recycle(); } catch (error) { /* 已回收 */ }
        }
        glyphTemplates = null;
    }
    if (typeof drawing !== 'undefined' && drawing && typeof drawing.hide === 'function') {
        try { drawing.hide(); } catch (error) { /* 已关 */ }
    }
    console.log('[象棋] 画板已关闭（连续结束）');
} else {
    recognizeOnce(1);
}

// ============================ 下一步 ============================
// · 分类计数偶尔会差一两个（比如 马=3 仕=5）：这是 7 类模型的混淆，不是脚本算错；
//   完整工程靠「逐格复核 + 多帧时序融合」修正，见 src/vision/。
// · 这里只演示到「SIFT 定位 + 区域识别」；工程里同一份模板还带交点标定（calibration.json），
//   能把四角换算成 9×10 个交叉点，再按格做字形识别 → 完整 FEN：
//   同目录 main.js（完整工程）与 tools/recognize-board.js（定位 + 识别 → FEN）
// · 想换/接入自己的模型：YOLO目标检测/模型管理.js 可以从文件导入 .onnx + .txt 标签
// · 画板是系统悬浮窗、会被截进画面：本示例「先截帧、后画框」所以没事；
//   连续跑（循环识别）时要每帧先 drawing.hide() 再截图，参考 main.js / 悬浮面板。
// · 阈值拉满召回（CONFIDENCE 0.001）会冒出大量低分框/叠框：示例用 IoU 去重（DEDUPE_IOU）
//   + 框大小过滤 + 字形复核兜底；工程里是按 9×10 交叉点归并（每格只留一个），更不容易误伤，见 src/vision/。
// · 红黑（SIDE_DETECT）：模型只给 7 个兵种类别，红黑靠「框中央多点取色」补；红笔画判据
//   比工程 piece-side.js 更严（挡掉盘沿/木纹暖色），判不准标「车?」，不硬猜。
// · 字形复核（GLYPH_CHECK）：模型认错的兵种用 11 个字形模板在框里重认一遍，只认
//   「分够 + 头名拉得开」的；认不准保持模型结论（真机帧回放：90.2% → 99.6%，0 改坏）。
//   没全认准会换 ×1.06/×0.94 缩放补试（逐框取最优）；摘要里的「最高分」是关键诊断——
//   最高分都很低（<0.5）＝这套棋盘皮肤和模板对不上（要重新采模板），接近门槛就该调 GLYPH_CONF。
// · SIFT 多档缩放（SIFT_TEMPLATE_SCALES）：模板是 1080 宽屏采的，屏幕越宽棋盘越大（1280 屏大
//   ~17%），单档匹不上就逐档试；日志逐档打「特征数/耗时/匹没匹到」，全没匹到才全屏兜底。
// · 框大小过滤（SIZE_FILTER_MIN/MAX）：全屏兜底时游戏的 UI（头像/按钮/徽章）会被当成棋子——
//   和「框宽中位数」差太远的（<0.70× 或 >1.40×）滤掉，日志/画板/统计里都看不到。
// · 空中子过滤（MOVING_FILTER）：落子动画里飞在半空的子（运动模糊）会被判「没落稳」按空处理——
//   口径同工程 glyph-recognizer：框心窗清晰度 < 本帧「认准的框」中位×0.5（真机实测差 4~5 倍）。
// · 耗时分期：运行末尾打「模型/模板/取帧/SIFT 定位/检测/取色/字形复核/空中子/合计」一行，
//   画板标题也会带毫秒数，CHESS_BASIC_OK 里另有 timing 字段；「合计」不含画板停留。
//   排查「一次比一次慢」先看它：取帧偏大＝截图/授权慢，定位偏大＝逐档试配多，字形偏大＝在跑补试。
// · 连续识别（LOOP_FRAMES>1）：循环「收画板→取帧→识别→画框」，逐帧打「第 N 帧耗时分期」，
//   结束给「共 N 帧｜单帧合计 平均/最小/最大｜总时长」；帧间歇 LOOP_SLEEP_MS。默认 0＝只跑一次。
//   ⚠️ 安卓 14+ 上截图授权弹窗会被算进「取帧」（小米8 实测）；长跑建议用工程主链/悬浮面板。
