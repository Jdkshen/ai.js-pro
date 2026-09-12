// @engine quickjs
// YOLO 模型管理（模型库插件）
// 用途：从文件管理导入 .onnx 模型与 .txt 标签、切换当前使用的模型；其它 YOLO 案例会自动改用当前模型并注明
// 前置：无（导入后建议跑一遍「YOLO运行环境测试.js」）
// 覆盖：yolo / files / storages / dialogs / ui

"ui";

var STORE_NAME = 'aijspro.yolo.models';
var BUILTIN_ID = '@builtin';
var BUILTIN = {
    id: BUILTIN_ID,
    name: '内置 yolo26_640',
    model: 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/yolo26_640.onnx',
    labels: 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/labels.txt',
    inputSize: 640,
    source: '内置资源（发布包自带，删不掉）'
};
var DEFAULT_DIR = files.join(files.getSdcardPath(), '脚本', '模型库');
var SCAN_DIRS = [
    files.join(files.getSdcardPath(), 'Download'),
    files.join(files.getSdcardPath(), 'Documents'),
    files.join(files.getSdcardPath(), '脚本')
];
var MAX_ROWS = 8;

var store = storages.create(STORE_NAME);
var models = [];

// ---- 模型库基础 ----

function libraryDir() {
    var dir = String(store.get('dir', DEFAULT_DIR));
    try {
        if (!files.isDir(dir)) files.ensureDir(dir);
    } catch (e) {
        console.warn('[模型管理] 无法创建目录：' + dir + '（' + e.message + '）');
    }
    return dir;
}

function baseName(path) {
    var text = String(path);
    var index = text.lastIndexOf('/');
    return index >= 0 ? text.substring(index + 1) : text;
}

function fileSizeText(path) {
    try {
        var file = new java.io.File(String(path));
        var size = Number(file.length());
        if (!size) return '?';
        return (size / 1048576).toFixed(1) + 'MB';
    } catch (e) {
        return '?';
    }
}

function scanLibrary() {
    var dir = libraryDir();
    var names = [];
    try {
        names = files.listDir(dir) || [];
    } catch (e) {
        console.warn('[模型管理] 读取目录失败：' + e.message);
    }
    var found = [];
    for (var i = 0; i < names.length; i++) {
        var name = String(names[i]);
        if (!/\.onnx$/i.test(name)) continue;
        var path = files.join(dir, name);
        if (!files.isFile(path)) continue;
        found.push({ id: name, name: name.replace(/\.onnx$/i, ''), path: path, size: fileSizeText(path) });
    }
    found.sort(function (a, b) { return a.id < b.id ? -1 : 1; });
    return found;
}

var invalidLabels = {};

function inputSizeFor(id) {
    var fallback = id === BUILTIN_ID ? BUILTIN.inputSize : 640;
    var size = Number(store.get('inputSize.' + id, fallback));
    return size >= 32 && size <= 2048 ? Math.round(size) : fallback;
}

// 竖屏矩形模型（如 160x320）：两个值都合法才算数；竖屏手机屏幕用矩形输入能省掉
// letterbox 两侧的灰边，同样内容质量下推理量减半（见 QUICKJS_ENGINE.md 实测）。
function inputRectFor(id) {
    var width = Number(store.get('inputWidth.' + id, 0));
    var height = Number(store.get('inputHeight.' + id, 0));
    var ok = width >= 32 && width <= 2048 && height >= 32 && height <= 2048;
    return ok ? { width: Math.round(width), height: Math.round(height) } : null;
}

// 显示用：方形是 "640"，矩形是 "160x320"
function shapeTextForId(id) {
    var rect = inputRectFor(id);
    return rect ? (rect.width + 'x' + rect.height) : String(inputSizeFor(id));
}

// 解析用户输入："640" → 方形；"160x320" / "160×320" / "160*320" → 矩形
function parseShapeText(text) {
    var trimmed = String(text).replace(/^\s+|\s+$/g, '');
    var rect = /^(\d{2,4})\s*[x×*_\-]\s*(\d{2,4})$/.exec(trimmed);
    if (rect) {
        var width = Number(rect[1]);
        var height = Number(rect[2]);
        if (!(width >= 32 && width <= 2048 && height >= 32 && height <= 2048)) return null;
        return { width: Math.round(width), height: Math.round(height) };
    }
    if (!/^\d{2,4}$/.test(trimmed)) return null;
    var size = Number(trimmed);
    return size >= 32 && size <= 2048 ? { size: Math.round(size) } : null;
}

// 导入时按文件名推断尺寸：yolo26_160x320.onnx → 160x320；yolo26_640.onnx → 640
function suggestShape(fileName) {
    var text = String(fileName);
    var rect = /(\d{2,4})\s*[x×]\s*(\d{2,4})/i.exec(text);
    if (rect) {
        var width = Number(rect[1]);
        var height = Number(rect[2]);
        if (width >= 32 && width <= 2048 && height >= 32 && height <= 2048) {
            return { width: Math.round(width), height: Math.round(height) };
        }
    }
    var match = /(\d{3,4})/.exec(text);
    if (!match) return { size: 640 };
    var size = Number(match[1]);
    return { size: size >= 32 && size <= 2048 ? size : 640 };
}

// 标签是按路径存的，可能指向一个「不是文件」的东西（手误填了名字、文件被删了、
// 或本来就是目录）；这种值不能直接交给 yolo.load，否则会报
// 「无法读取 YOLO labels」把整个模型判成坏的。
function isUsableLabelsPath(path) {
    var text = String(path || '');
    if (text.length === 0) return false;
    if (text.indexOf('asset://') === 0) return true; // 内置资源不走 files
    try {
        return files.isFile(text);
    } catch (e) {
        return false;
    }
}

function labelsFor(id) {
    var override = String(store.get('labels.' + id, '') || '');
    if (override.length > 0) {
        if (isUsableLabelsPath(override)) {
            delete invalidLabels[id];
            return override;
        }
        // 失效的标签设置：清掉并记一笔，让「验证」能告诉用户发生了什么
        invalidLabels[id] = override;
        store.remove('labels.' + id);
        console.log('[模型管理] 标签设置无效，已清掉：' + id + ' → ' + override);
    }
    if (id === BUILTIN_ID) return BUILTIN.labels;
    var dir = libraryDir();
    var same = files.join(dir, String(id).replace(/\.onnx$/i, '') + '.txt');
    if (files.isFile(same)) return same;
    var shared = files.join(dir, 'labels.txt');
    return files.isFile(shared) ? shared : '';
}

// 内置模型也允许改 inputSize / 标签（改完六个案例同样会读到）
function builtinEntry() {
    return {
        id: BUILTIN_ID,
        name: BUILTIN.name,
        model: BUILTIN.model,
        labels: labelsFor(BUILTIN_ID),
        inputSize: inputSizeFor(BUILTIN_ID),
        inputRect: inputRectFor(BUILTIN_ID),
        source: BUILTIN.source
    };
}

function entryOf(id) {
    if (!id || id === BUILTIN_ID) return builtinEntry();
    var dir = libraryDir();
    var path = files.join(dir, id);
    if (!files.isFile(path)) return BUILTIN;
    return {
        id: id,
        name: String(id).replace(/\.onnx$/i, ''),
        model: path,
        labels: labelsFor(id),
        inputSize: inputSizeFor(id),
        inputRect: inputRectFor(id),
        source: dir
    };
}

function currentEntry() {
    return entryOf(String(store.get('current', BUILTIN_ID)));
}

function setCurrent(id) {
    store.put('current', id);
    var entry = entryOf(id);
    console.log('[模型管理] 当前模型已切换为：' + entry.name + '（' + shapeTextForId(id) + '，' + entry.source + '）');
    return entry;
}

// ---- 校验 / 导入 / 删除 ----

function errorText(error) {
    if (!error) return '未知错误';
    return error.message ? String(error.message) : String(error);
}

// 截图和模型是两回事，分开报：截图失败不该说成「推理失败」，否则会让人以为是模型有问题。
// 系统（MIUI）会弹「开始录制或投屏」确认框，所以这里先发起请求，再给用户几秒时间点允许，
// 期间一有画面就顺手验证。
// 返回 { frame } 或 { reason: 'auth' | 'capture', message }
function captureForValidate() {
    var authorized = false;
    try {
        authorized = !!requestScreenCapture('portrait');
    } catch (e) {
        authorized = false;
    }
    var deadline = Date.now() + 9000;
    var warned = false;
    var lastError = null;
    while (true) {
        if (!warned && Date.now() > deadline - 6000) {
            warned = true;
            report('正在等截图画面：手机上若弹「开始录制或投屏」，请点「立即开始」');
        }
        try {
            // 只取「最近一帧」：拿到就有，拿不到立刻报错，不用等屏幕出新画面
            // （屏幕静止时等新画面是白等，之前就是这么误报成「推理失败」的）
            return { frame: captureScreen({ mode: 'fast', size: 720, fresh: false, timeout: 600 }) };
        } catch (error) {
            lastError = error;
        }
        if (Date.now() >= deadline) break;
        sleep(400);
    }
    return { reason: authorized ? 'capture' : 'auth', message: errorText(lastError) };
}

function yoloLoadOptions(entry, withoutLabels) {
    var options = {
        backend: 'dnn',
        model: entry.model,
        labels: withoutLabels ? undefined : (entry.labels || undefined),
        threads: 4
    };
    if (entry.inputRect) {
        options.inputWidth = entry.inputRect.width;
        options.inputHeight = entry.inputRect.height;
    } else {
        options.inputSize = entry.inputSize;
    }
    return options;
}

// 打开检测器。标签坏掉（路径不合法/文件读不了）不应该把模型判成坏的：
// 六个识别案例就是「不是文件就不传」，这里保持一致，并且把失效的标签设置顺手清掉。
function openDetector(entry) {
    if (entry.id) labelsFor(entry.id); // 顺手体检 + 清理失效的标签设置
    var repaired = entry.id ? invalidLabels[entry.id] : '';
    var note = repaired
        ? '· 标签设置「' + repaired + '」不是文件，已忽略并清除（现在按库里的同名 .txt / labels.txt 找）'
        : '';
    try {
        return { detector: yolo.load(yoloLoadOptions(entry, false)), note: note };
    } catch (error) {
        var text = errorText(error);
        // 标签文件存在但读不了（比如它是目录、没权限）时退一步：不带标签也要能把模型用起来
        if (!/labels/i.test(text) || !entry.labels) return { error: text };
        try {
            return {
                detector: yolo.load(yoloLoadOptions(entry, true)),
                note: (note ? note + '\n' : '') +
                    '· 标签读不了（' + text + '），本次按「无标签」加载，只能拿到 classId'
            };
        } catch (again) {
            return { error: errorText(again) };
        }
    }
}

function validate(entry, withFrame) {
    var opened = openDetector(entry);
    if (opened.error) {
        return '✗ 加载失败：' + opened.error +
            '\n（需要 OpenCV DNN 能读的 ONNX，建议按 opset 12 / simplify / end2end 导出）';
    }
    var detector = opened.detector;
    var message = '✓ 加载成功' + (opened.note ? '\n' + opened.note : '');
    if (!withFrame) {
        try { detector.close(); } catch (e) { }
        return message;
    }
    var shot = captureForValidate();
    if (!shot.frame) {
        try { detector.close(); } catch (e) { }
        if (shot.reason === 'auth') {
            return message + '\n· 跳过推理验证：系统没同意投屏，没拿到画面' +
                '\n  → 弹「开始录制或投屏」时要点「立即开始」；被拒过就重开本应用再试' +
                '\n（模型已入库，不影响使用；之后可以点那一行的「验证」重来）';
        }
        return message + '\n· 跳过推理验证：等不到画面（截图这一环的问题，和模型无关）' +
            '\n  → ' + shot.message +
            '\n  → 屏幕保持点亮、弹窗里点「立即开始」、重开应用后再试；也可以点那一行的「验证」重来' +
            '\n（模型已入库，不影响使用）';
    }
    var frame = shot.frame;
    try {
        var result = detector.detect(frame, { confidence: 0.25 });
        message += '\n✓ 推理成功：检出 ' + result.length + ' 个目标，pre=' + result.preprocessMs.toFixed(2) +
            'ms inf=' + result.inferenceMs.toFixed(2) + 'ms';
        if (result.length === 0) {
            message += '\n· 当前画面没检到目标属正常；若换几个画面始终为 0，检查模型输出是否为 end2end [1,300,6]';
        }
    } catch (error) {
        // 截图问题上面已经拦掉了，走到这里才是模型/推理本身的问题
        message += '\n✗ 推理失败：' + errorText(error) +
            '\n（输出需要是 end2end 的 [1,300,6]）';
    } finally {
        try { frame.recycle(); } catch (e) { }
        try { detector.close(); } catch (e) { }
    }
    return message;
}

function copyIntoLibrary(sourcePath) {
    var dir = libraryDir();
    var name = baseName(sourcePath);
    var target = files.join(dir, name);
    if (files.exists(target) && !dialogs.confirm('覆盖确认', name + ' 已存在，覆盖吗？')) {
        return '';
    }
    if (!files.copy(sourcePath, target)) {
        report('✗ 复制失败：' + sourcePath + ' → ' + target);
        return '';
    }
    report('已复制到：' + target + '（' + fileSizeText(target) + '）');
    var labelSource = String(sourcePath).replace(/\.onnx$/i, '.txt');
    if (files.isFile(labelSource)) {
        var labelTarget = String(target).replace(/\.onnx$/i, '.txt');
        if (files.copy(labelSource, labelTarget)) report('已一并导入标签：' + baseName(labelTarget));
    }
    var shape = suggestShape(name);
    if (shape.width) {
        store.put('inputWidth.' + name, shape.width);
        store.put('inputHeight.' + name, shape.height);
        store.remove('inputSize.' + name);
        report('按文件名认出竖屏尺寸：' + shape.width + 'x' + shape.height);
    } else {
        store.put('inputSize.' + name, shape.size);
        store.remove('inputWidth.' + name);
        store.remove('inputHeight.' + name);
    }
    return name;
}
function findCandidates() {
    var found = [];
    for (var i = 0; i < SCAN_DIRS.length; i++) {
        var dir = SCAN_DIRS[i];
        if (!files.isDir(dir)) continue;
        var names = [];
        try { names = files.listDir(dir) || []; } catch (e) { continue; }
        for (var j = 0; j < names.length; j++) {
            var name = String(names[j]);
            if (!/\.onnx$/i.test(name)) continue;
            var path = files.join(dir, name);
            if (!files.isFile(path)) continue;
            found.push({ path: path, label: name + '（' + fileSizeText(path) + '）' + dir });
        }
    }
    return found;
}

function importModel() {
    var choice = dialogs.singleChoice('导入模型', [
        '手动输入模型文件路径',
        '在常见目录里找一找（Download / Documents / 脚本）'
    ], 0);
    if (choice === null || choice === undefined || choice < 0) return;
    var sourcePath = '';
    if (choice === 0) {
        sourcePath = dialogs.prompt('输入 .onnx 的完整路径', files.join(files.getSdcardPath(), 'Download', ''));
        if (!sourcePath) return;
    } else {
        var candidates = findCandidates();
        if (candidates.length === 0) {
            dialogs.alert('没找到模型', '在 ' + SCAN_DIRS.join('、') + ' 里都没找到 .onnx。\n' +
                '可以先用任意文件管理器把模型放进这些目录，或选「手动输入路径」。');
            return;
        }
        var picked = dialogs.singleChoice('选择要导入的模型',
            candidates.map(function (c) { return c.label; }), 0);
        if (picked === null || picked === undefined || picked < 0) return;
        sourcePath = candidates[picked].path;
    }
    if (!files.isFile(sourcePath)) {
        dialogs.alert('导入失败', '文件不存在：\n' + sourcePath);
        return;
    }
    var id = copyIntoLibrary(sourcePath);
    if (!id) return;
    var entry = entryOf(id);
    report('导入完成：' + entry.name + '（' + shapeTextForId(id) + '）');
    var verify = dialogs.confirm('导入完成', entry.name + '\n输入尺寸=' + shapeTextForId(id) +
        '\n\n现在跑一帧验证吗（需要截图授权）？');
    report(validate(entry, verify));
    if (dialogs.confirm('设为当前模型', '把「' + entry.name + '」设为当前识别模型吗？')) {
        setCurrent(id);
    }
    render();
}

function deleteModel(index) {
    if (index >= models.length) return;
    var model = models[index];
    if (!dialogs.confirm('确认删除', '删除 ' + model.path + ' ？此操作不可撤销。')) return;
    if (files.remove(model.path)) {
        report('已删除：' + model.path);
        store.remove('inputSize.' + model.id);
        store.remove('inputWidth.' + model.id);
        store.remove('inputHeight.' + model.id);
        store.remove('labels.' + model.id);
        delete invalidLabels[model.id];
        if (String(store.get('current', '')) === model.id) {
            store.put('current', BUILTIN_ID);
            report('删掉的正是当前模型，已回退到「' + BUILTIN.name + '」');
        }
    } else {
        report('✗ 删除失败：' + model.path);
    }
    render();
}

function changeInputSize(target) {
    var id = target === 'builtin' ? BUILTIN_ID : target;
    var name = id === BUILTIN_ID ? BUILTIN.name : id;
    var value = dialogs.prompt(
        '输入尺寸：方形填一个数（如 640）；竖屏矩形填「宽x高」（如 160x320）；要与模型导出时一致',
        shapeTextForId(id));
    if (!value) return;
    var parsed = parseShapeText(value);
    if (!parsed) {
        dialogs.alert('格式不对', '填 640 这样的方形边长，或 160x320 这样的宽x高（每边 32~2048）。');
        return;
    }
    if (parsed.width) {
        store.put('inputWidth.' + id, parsed.width);
        store.put('inputHeight.' + id, parsed.height);
        store.remove('inputSize.' + id);
        report('已设置：' + name + ' → ' + parsed.width + 'x' + parsed.height + '（竖屏矩形）');
    } else {
        store.put('inputSize.' + id, parsed.size);
        store.remove('inputWidth.' + id);
        store.remove('inputHeight.' + id);
        report('已设置：' + name + ' → inputSize=' + parsed.size);
    }
    render();
}

function changeLabels(target) {
    var id = target === 'builtin' ? BUILTIN_ID : target;
    var name = id === BUILTIN_ID ? BUILTIN.name : id;
    var current = labelsFor(id);
    var value = dialogs.prompt('输入标签 .txt 完整路径（清空表示自动找同名 .txt / labels.txt）', String(current));
    if (value === null || value === undefined) return;
    if (String(value).length === 0) {
        store.remove('labels.' + id);
        delete invalidLabels[id];
        report('已恢复自动查找标签：' + name + '（现在用 ' + (labelsFor(id) || '无') + '）');
        render();
        return;
    }
    var candidate = String(value);
    // 这里填的是「文件的完整路径」（不是标签名）。不校验的话，一个手误的名字
    // 会让下次验证直接报「无法读取 YOLO labels」，看起来像模型坏了。
    if (!isUsableLabelsPath(candidate)) {
        dialogs.alert('这个路径不是文件', '找不到这个文件：\n' + candidate +
            '\n\n这里要填 .txt 的完整路径，例如：\n' +
            files.join(libraryDir(), 'labels.txt') +
            '\n如果想自动找同名 .txt / labels.txt，把输入框清空后确定。');
        return;
    }
    store.put('labels.' + id, candidate);
    delete invalidLabels[id];
    report('已设置标签：' + name + ' → ' + candidate);
    render();
}

function changeLibraryDir() {
    var value = dialogs.prompt('输入模型库目录', libraryDir());
    if (!value || String(value).length === 0) return;
    store.put('dir', String(value));
    report('模型库目录已改为：' + String(value));
    render();
}

function renameModel() {
    if (models.length === 0) {
        dialogs.alert('模型库是空的', '先「导入模型」再来改名。');
        return;
    }
    var items = models.map(function (m) { return m.name + '（' + m.size + '）'; });
    var choice = dialogs.singleChoice('重命名模型', items, 0);
    if (choice === null || choice === undefined || choice < 0) return;
    var model = models[choice];
    var input = dialogs.prompt('输入新的模型名（不用带 .onnx）', model.name);
    if (input === null || input === undefined) return;
    var newName = String(input).replace(/\.onnx$/i, '').replace(/^\s+|\s+$/g, '');
    if (newName.length === 0) return;
    if (newName.indexOf('/') >= 0 || newName.indexOf('\\') >= 0) {
        dialogs.alert('名字不合法', '模型名里不能带路径分隔符，只能改名字本身。');
        return;
    }
    if (newName === model.name) {
        report('名字没变，已跳过');
        return;
    }
    var dir = libraryDir();
    var newId = newName + '.onnx';
    if (files.exists(files.join(dir, newId))) {
        dialogs.alert('重名了', newId + ' 已经在模型库里，换个名字吧。');
        return;
    }
    if (!files.rename(model.path, newId)) {
        report('✗ 重命名失败：' + model.path + ' → ' + newId);
        return;
    }
    var oldId = model.id;
    // 输入尺寸、标签这些是按文件名存的，一起挪到新名字上
    var size = store.get('inputSize.' + oldId, null);
    if (size !== null && size !== undefined) {
        store.put('inputSize.' + newId, size);
        store.remove('inputSize.' + oldId);
    }
    var rectWidth = store.get('inputWidth.' + oldId, null);
    var rectHeight = store.get('inputHeight.' + oldId, null);
    if (rectWidth !== null && rectWidth !== undefined) {
        store.put('inputWidth.' + newId, rectWidth);
        store.remove('inputWidth.' + oldId);
    }
    if (rectHeight !== null && rectHeight !== undefined) {
        store.put('inputHeight.' + newId, rectHeight);
        store.remove('inputHeight.' + oldId);
    }
    var labels = store.get('labels.' + oldId, null);
    if (labels !== null && labels !== undefined) {
        store.put('labels.' + newId, labels);
        store.remove('labels.' + oldId);
    }
    // 同名 .txt 一起改名，自动找标签才不会落空
    var oldTxt = files.join(dir, model.name + '.txt');
    if (files.isFile(oldTxt) && !files.exists(files.join(dir, newName + '.txt'))) {
        if (files.rename(oldTxt, newName + '.txt')) report('标签一起改名：' + newName + '.txt');
    }
    if (String(store.get('current', BUILTIN_ID)) === oldId) {
        store.put('current', newId);
        report('当前模型跟着改名了：' + newName);
    }
    report('已重命名：' + model.name + ' → ' + newName);
    render();
}

// ---- 页面 ----

function buildLayout(rows) {
    // 内容会比屏幕长（模型行 + 按钮 + 日志），最外层套 scroll，否则下面看得到却滑不动
    var xml = ['<scroll>', '<vertical padding="12">'];
    xml.push('<text id="title" text="YOLO 模型管理" textSize="20sp" textColor="#FF2196F3"/>');
    xml.push('<text id="current" text="" textSize="14sp" marginTop="6"/>');
    xml.push('<text id="libdir" text="" textSize="12sp" textColor="#888888" marginTop="2"/>');
    for (var i = 0; i < rows; i++) {
        xml.push('<linear orientation="horizontal" gravity="center_vertical" marginTop="4">');
        xml.push('<text id="name' + i + '" text="" layout_weight="1" textSize="14sp"/>');
        xml.push('<button id="use' + i + '" text="使用" textSize="11sp" w="52dp"/>');
        xml.push('<button id="try' + i + '" text="验证" textSize="11sp" w="52dp"/>');
        xml.push('<button id="del' + i + '" text="删除" textSize="11sp" w="52dp"/>');
        xml.push('</linear>');
    }
    xml.push('<text id="more" text="" textSize="12sp" textColor="#CC6600" marginTop="2"/>');
    xml.push('<linear orientation="horizontal" marginTop="6">');
    xml.push('<button id="import" text="导入模型" layout_weight="1"/>');
    xml.push('<button id="useBuiltin" text="用内置模型" layout_weight="1"/>');
    xml.push('</linear>');
    xml.push('<linear orientation="horizontal">');
    xml.push('<button id="size" text="设置输入尺寸" layout_weight="1"/>');
    xml.push('<button id="labels" text="设置标签" layout_weight="1"/>');
    xml.push('</linear>');
    xml.push('<linear orientation="horizontal">');
    xml.push('<button id="rename" text="重命名模型" layout_weight="1"/>');
    xml.push('<button id="refresh" text="刷新" layout_weight="1"/>');
    xml.push('</linear>');
    xml.push('<button id="dir" text="更换库目录"/>');
    xml.push('<text id="log" text="" textSize="12sp" textColor="#333333" marginTop="6"/>');
    xml.push('</vertical>');
    xml.push('</scroll>');
    ui.layout(xml.join(''));
}

var LOG_LINES = 12;

function report(text) {
    console.log('[模型管理] ' + String(text).replace(/\n/g, ' ｜ '));
    try {
        var lines = [String(text)].concat(ui.log.getText().split('\n'));
        if (lines.length > LOG_LINES) lines = lines.slice(0, LOG_LINES);
        ui.log.setText(lines.join('\n'));
    } catch (e) { }
}

function render() {
    models = scanLibrary();
    var current = currentEntry();
    ui.current.setText('当前模型：' + current.name + '\n输入尺寸=' + shapeTextForId(BUILTIN_ID === current.id ? BUILTIN_ID : current.id) +
        '　来源：' + current.source);
    ui.libdir.setText('模型库：' + libraryDir() + '（' + models.length + ' 个模型）');
    for (var i = 0; i < MAX_ROWS; i++) {
        var has = i < models.length;
        ui['name' + i].setText(has
            ? (models[i].id === current.id ? '★ ' : '') + models[i].name + '（' + models[i].size + '）'
            : '');
        ui['use' + i].setVisibility(has ? 0 : 8);
        ui['try' + i].setVisibility(has ? 0 : 8);
        ui['del' + i].setVisibility(has ? 0 : 8);
    }
    ui.more.setText(models.length > MAX_ROWS
        ? '（只列出前 ' + MAX_ROWS + ' 个，其余请在文件管理器里清理）'
        : '★ = 当前使用；「导入模型」会把 .onnx 复制进模型库，同名 .txt 会一起带上；竖屏矩形模型（如 160x320.onnx）导入时会自动识别，也可在「设置输入尺寸」里填「160x320」');
}

function bindActions() {
    for (var i = 0; i < MAX_ROWS; i++) {
        bindRow(i);
    }
    ui.useBuiltin.click(function () { setCurrent(BUILTIN_ID); render(); });
    ui.import.click(function () { importModel(); });
    ui.rename.click(function () { renameModel(); });
    ui.refresh.click(function () { render(); report('已刷新'); });
    ui.dir.click(function () { changeLibraryDir(); });
    ui.size.click(function () {
        var items = ['内置 yolo26_640（' + shapeTextForId(BUILTIN_ID) + '）'].concat(
            models.map(function (m) { return m.name + '（' + shapeTextForId(m.id) + '）'; }));
        var choice = dialogs.singleChoice('设置输入尺寸', items, 0);
        if (choice === null || choice === undefined || choice < 0) return;
        changeInputSize(choice === 0 ? 'builtin' : models[choice - 1].id);
    });
    ui.labels.click(function () {
        var items = ['内置 yolo26_640（' + baseName(BUILTIN.labels) + '）'].concat(
            models.map(function (m) {
                var labels = labelsFor(m.id);
                return m.name + '（' + (labels ? baseName(labels) : '无') + '）';
            }));
        var choice = dialogs.singleChoice('设置标签文件', items, 0);
        if (choice === null || choice === undefined || choice < 0) return;
        changeLabels(choice === 0 ? 'builtin' : models[choice - 1].id);
    });
}

function bindRow(index) {
    ui['use' + index].click(function () {
        if (index >= models.length) return;
        setCurrent(models[index].id);
        render();
    });
    ui['try' + index].click(function () {
        if (index >= models.length) return;
        var entry = entryOf(models[index].id);
        report('验证「' + entry.name + '」（' + shapeTextForId(entry.id) + '）：');
        report(validate(entry, true));
        render();
    });
    ui['del' + index].click(function () {
        deleteModel(index);
    });
}

// ---- 启动 ----

buildLayout(MAX_ROWS);
bindActions();
render();

// 在文件管理器里改名 / 增删模型后，面板自动跟上（2 秒一次；内容没变就不重绘，不打断操作）
var lastSignature = '';
function autoRefresh() {
    try {
        var signature = scanLibrary().map(function (m) { return m.id + ':' + m.size; }).join('|') +
            '#' + String(store.get('current', BUILTIN_ID)) + '#' + libraryDir();
        if (signature === lastSignature) return;
        lastSignature = signature;
        render();
    } catch (e) {
        // 自动刷新出问题不能影响正常使用
    }
}
autoRefresh();
setInterval(autoRefresh, 2000);

report('其它 YOLO 案例会读取「当前模型」，并在识别结果里注明模型名');
console.log('[模型管理] 模型库目录=' + libraryDir() + ' 当前=' + currentEntry().name +
    ' 库内=' + scanLibrary().length + ' 个');
