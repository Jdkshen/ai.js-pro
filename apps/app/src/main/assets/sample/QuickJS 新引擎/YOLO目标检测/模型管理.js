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

function inputSizeFor(id) {
    var size = Number(store.get('inputSize.' + id, 640));
    return size >= 32 && size <= 2048 ? Math.round(size) : 640;
}

function labelsFor(id) {
    var override = store.get('labels.' + id, '');
    if (override && String(override).length > 0) return String(override);
    var dir = libraryDir();
    var same = files.join(dir, String(id).replace(/\.onnx$/i, '') + '.txt');
    if (files.isFile(same)) return same;
    var shared = files.join(dir, 'labels.txt');
    return files.isFile(shared) ? shared : '';
}

function entryOf(id) {
    if (!id || id === BUILTIN_ID) return BUILTIN;
    var dir = libraryDir();
    var path = files.join(dir, id);
    if (!files.isFile(path)) return BUILTIN;
    return {
        id: id,
        name: String(id).replace(/\.onnx$/i, ''),
        model: path,
        labels: labelsFor(id),
        inputSize: inputSizeFor(id),
        source: dir
    };
}

function currentEntry() {
    return entryOf(String(store.get('current', BUILTIN_ID)));
}

function setCurrent(id) {
    store.put('current', id);
    var entry = entryOf(id);
    console.log('[模型管理] 当前模型已切换为：' + entry.name + '（inputSize=' + entry.inputSize + '，' + entry.source + '）');
    return entry;
}

function suggestInputSize(fileName) {
    var match = /(\d{3,4})/.exec(String(fileName));
    if (!match) return 640;
    var size = Number(match[1]);
    return size >= 32 && size <= 2048 ? size : 640;
}

// ---- 校验 / 导入 / 删除 ----

function validate(entry, withFrame) {
    var detector = null;
    try {
        detector = yolo.load({
            backend: 'dnn',
            model: entry.model,
            labels: entry.labels || undefined,
            inputSize: entry.inputSize,
            threads: 4
        });
    } catch (error) {
        return '✗ 加载失败：' + (error && error.message ? error.message : String(error)) +
            '\n（需要 OpenCV DNN 能读的 ONNX，建议按 opset 12 / simplify / end2end 导出）';
    }
    var message = '✓ 加载成功';
    if (!withFrame) {
        try { detector.close(); } catch (e) { }
        return message;
    }
    var frame = null;
    try {
        if (!requestScreenCapture('portrait')) {
            return message + '\n· 跳过推理验证（未授权截图）';
        }
        frame = captureScreen({ mode: 'fast', size: 720, fresh: true, timeout: 3000 });
        var result = detector.detect(frame, { confidence: 0.25 });
        message += '\n✓ 推理成功：检出 ' + result.length + ' 个目标，pre=' + result.preprocessMs.toFixed(2) +
            'ms inf=' + result.inferenceMs.toFixed(2) + 'ms';
        if (result.length === 0) {
            message += '\n· 当前画面没检到目标属正常；若换几个画面始终为 0，检查模型输出是否为 end2end [1,300,6]';
        }
    } catch (error) {
        message += '\n✗ 推理失败：' + (error && error.message ? error.message : String(error)) +
            '\n（输出需要是 end2end 的 [1,300,6]）';
    } finally {
        if (frame) frame.recycle();
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
    store.put('inputSize.' + name, suggestInputSize(name));
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
    report('导入完成：' + entry.name + '（inputSize=' + entry.inputSize + '）');
    var verify = dialogs.confirm('导入完成', entry.name + '\ninputSize=' + entry.inputSize +
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
    var value = dialogs.prompt('输入尺寸（方形边长，如 640；与模型导出时一致）', String(inputSizeFor(id)));
    if (!value) return;
    var size = Number(value);
    if (!(size >= 32 && size <= 2048)) {
        dialogs.alert('数值不合法', '请输入 32~2048 之间的整数');
        return;
    }
    store.put('inputSize.' + id, Math.round(size));
    report('已设置：' + name + ' → inputSize=' + Math.round(size));
    render();
}

function changeLabels(target) {
    var id = target === 'builtin' ? BUILTIN_ID : target;
    var name = id === BUILTIN_ID ? BUILTIN.name : id;
    var current = id === BUILTIN_ID ? BUILTIN.labels : labelsFor(id);
    var value = dialogs.prompt('输入标签 .txt 完整路径（清空表示自动找同名 .txt / labels.txt）', String(current));
    if (value === null || value === undefined) return;
    if (String(value).length === 0) {
        store.remove('labels.' + id);
        report('已恢复自动查找标签：' + name);
    } else {
        store.put('labels.' + id, String(value));
        report('已设置标签：' + name + ' → ' + value);
    }
    render();
}

function changeLibraryDir() {
    var value = dialogs.prompt('输入模型库目录', libraryDir());
    if (!value || String(value).length === 0) return;
    store.put('dir', String(value));
    report('模型库目录已改为：' + String(value));
    render();
}

// ---- 页面 ----

function buildLayout(rows) {
    var xml = ['<vertical padding="12">'];
    xml.push('<text id="title" text="YOLO 模型管理" textSize="20sp" textColor="#FF2196F3"/>');
    xml.push('<text id="current" text="" textSize="14sp" marginTop="6"/>');
    xml.push('<text id="libdir" text="" textSize="12sp" textColor="#888888" marginTop="2"/>');
    for (var i = 0; i < rows; i++) {
        xml.push('<linear orientation="horizontal" gravity="center_vertical" marginTop="4">');
        xml.push('<text id="name' + i + '" text="" layout_weight="1" textSize="14sp"/>');
        xml.push('<button id="use' + i + '" text="使用" textSize="12sp" w="60dp"/>');
        xml.push('<button id="del' + i + '" text="删除" textSize="12sp" w="60dp"/>');
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
    xml.push('<button id="dir" text="更换库目录" layout_weight="1"/>');
    xml.push('<button id="refresh" text="刷新" layout_weight="1"/>');
    xml.push('</linear>');
    xml.push('<text id="log" text="" textSize="12sp" textColor="#333333" marginTop="6"/>');
    xml.push('</vertical>');
    ui.layout(xml.join(''));
}

function report(text) {
    console.log('[模型管理] ' + String(text).replace(/\n/g, ' ｜ '));
    try { ui.log.setText(text + '\n' + ui.log.getText()); } catch (e) { }
}

function render() {
    models = scanLibrary();
    var current = currentEntry();
    ui.current.setText('当前模型：' + current.name + '\ninputSize=' + current.inputSize +
        '　来源：' + current.source);
    ui.libdir.setText('模型库：' + libraryDir() + '（' + models.length + ' 个模型）');
    for (var i = 0; i < MAX_ROWS; i++) {
        var has = i < models.length;
        ui['name' + i].setText(has
            ? (models[i].id === current.id ? '★ ' : '') + models[i].name + '（' + models[i].size + '）'
            : '');
        ui['use' + i].setVisibility(has ? 0 : 8);
        ui['del' + i].setVisibility(has ? 0 : 8);
    }
    ui.more.setText(models.length > MAX_ROWS
        ? '（只列出前 ' + MAX_ROWS + ' 个，其余请在文件管理器里清理）'
        : '★ = 当前使用；「导入模型」会把 .onnx 复制进模型库，同名 .txt 会一起带上');
}

function bindActions() {
    for (var i = 0; i < MAX_ROWS; i++) {
        bindRow(i);
    }
    ui.useBuiltin.click(function () { setCurrent(BUILTIN_ID); render(); });
    ui.import.click(function () { importModel(); });
    ui.refresh.click(function () { render(); report('已刷新'); });
    ui.dir.click(function () { changeLibraryDir(); });
    ui.size.click(function () {
        var items = ['内置 yolo26_640（inputSize=' + inputSizeFor(BUILTIN_ID) + '）'].concat(
            models.map(function (m) { return m.name + '（inputSize=' + inputSizeFor(m.id) + '）'; }));
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
    ui['del' + index].click(function () {
        deleteModel(index);
    });
}

// ---- 启动 ----

buildLayout(MAX_ROWS);
bindActions();
render();
report('其它 YOLO 案例会读取「当前模型」，并在识别结果里注明模型名');
console.log('[模型管理] 模型库目录=' + libraryDir() + ' 当前=' + currentEntry().name +
    ' 库内=' + scanLibrary().length + ' 个');
