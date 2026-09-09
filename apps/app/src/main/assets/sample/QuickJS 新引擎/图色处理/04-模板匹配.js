// @engine quickjs

// 复用 Rhino 兼容示例中的马里奥素材；素材本身不依赖 Rhino。
// 从当前“QuickJS 新引擎/图色处理”返回 sample 根目录后再进入 Rhino 目录。
var exampleRoot = '../../Rhino 引擎/图片与图色处理/找图/';
var sourcePath = exampleRoot + 'super_mario.jpg';
var templatePath = exampleRoot + 'block.png';

if (!files.exists(sourcePath) || !files.exists(templatePath)) {
    throw new Error('找不到原项目模板素材，请确认示例目录结构未被移动: ' + files.path(exampleRoot));
}

// ---- 统计工具 ----
function percentile(sorted, ratio) {
    var index = Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1);
    return sorted[Math.max(0, index)];
}

function summarize(name, values) {
    if (values.length === 0) {
        console.log('[BENCH] name=' + name + ' rounds=0');
        return;
    }
    var sorted = values.slice().sort(function (a, b) { return a - b; });
    var total = 0;
    for (var i = 0; i < values.length; i++) total += values[i];
    console.log('[BENCH] name=' + name + ' rounds=' + values.length);
    console.log('[BENCH] p50=' + percentile(sorted, 0.50).toFixed(3) +
        'ms p95=' + percentile(sorted, 0.95).toFixed(3) +
        'ms max=' + sorted[sorted.length - 1].toFixed(3) +
        'ms avg=' + (total / values.length).toFixed(3) + 'ms');
}

var ROUNDS = 100;
var source = images.read(sourcePath);
var template = images.read(templatePath);

try {
    // 预热 5 次
    console.log('[BENCH] 预热 5 次...');
    for (var w = 0; w < 5; w++) {
        images.findImage(source, template, { threshold: 0.8 });
    }

    var findImageMs = [];
    var matchTemplateMs = [];
    var errors = 0;

    console.log('[BENCH] 模板匹配 x' + ROUNDS + ' source=' + source.width + 'x' + source.height +
        ' template=' + template.width + 'x' + template.height);

    for (var i = 0; i < ROUNDS; i++) {
        try {
            var fiStart = performance.now();
            var first = images.findImage(source, template, { threshold: 0.8 });
            findImageMs.push(performance.now() - fiStart);

            var mtStart = performance.now();
            var result = images.matchTemplate(source, template, {
                threshold: 0.8,
                max: 20
            });
            matchTemplateMs.push(performance.now() - mtStart);

            if (i === 0) {
                console.log('马里奥问号方块模板匹配:', {
                    sourceSize: source.width + 'x' + source.height,
                    templateSize: template.width + 'x' + template.height,
                    firstPoint: first,
                    matchCount: result.matches.length
                });
                result.matches.forEach(function (match, index) {
                    console.log(index, match.point, match.similarity);
                });
            }
        } catch (e) {
            errors++;
            if (errors <= 5) console.log('[ERROR] round=' + i + ' ' + String(e.message || e));
        }
    }

    console.log('');
    console.log('[BENCH] errors=' + errors);
    summarize('findImage', findImageMs);
    summarize('matchTemplate', matchTemplateMs);
    toastLog('模板匹配 ' + ROUNDS + ' 次完成，errors=' + errors);

} finally {
    template.recycle();
    source.recycle();
}
