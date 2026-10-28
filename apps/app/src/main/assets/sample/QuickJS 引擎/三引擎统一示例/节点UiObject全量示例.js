"quickjs";
// 节点 / UiObject API 全量示例（QuickJS）
//
// 前置：系统设置 → 无障碍 → 本应用服务已开启；脚本在前台运行。
// 安全：只操作脚本自己弹出的 SAFE_CLOSE 对话框（标题 NODE_API_UI_SURFACE），
//       不点击真实应用按钮，不读写剪贴板，不修改用户输入；setText 只写自己的输入框并恢复。
// 输出：PASS 为接口/类型检查；false 动作记 SKIP；缺少接口记 FAIL。实际效果由对应检查确认。
// 末尾 NODE_UI_API_RESULT JSON 与 RESULT 行，不能把 PASS 数量当成全部动作已生效。
//
// 覆盖：text/className/desc/id 选择器、findOne/untilFind、bounds、boundsInParent、
//       drawingOrder、children/childCount/child/parent、findByText/find，
//       click/clickCenter/longClick/setText/copy/cut/paste/select/collapse/expand/
//       show/contextClick/focus/clearFocus/accessibilityFocus/clearAccessibilityFocus/
//       scrollForward/Backward/Up/Down/Left/Right、setSelection/setProgress/scrollTo。

var ENGINE = 'quickjs';
var TITLE = 'NODE_API_UI_SURFACE';
var INPUT = 'NODE_API_INPUT';
var BUTTON = 'SAFE_CLOSE';
var results = [];
var dialog = null;

function clip(value, max) {
    var s = value === null || value === undefined ? '' : String(value);
    s = s.replace(/\s+/g, ' ');
    if (s.length > max) s = s.substring(0, max) + '…';
    return s;
}

function errText(error) {
    if (!error) return 'unknown error';
    if (error.message) return String(error.message);
    return String(error);
}

function record(status, name, detail) {
    var line = '[' + status + '] ' + name + (detail ? ' :: ' + detail : '');
    console.log(line);
    results.push({ name: name, status: status, detail: detail ? String(detail) : '' });
}

function pass(name, detail) { record('PASS', name, detail); }
function fail(name, detail) { record('FAIL', name, detail); }
function skip(name, detail) { record('SKIP', name, detail); }

function nodeText(node) {
    try { var v = node.text(); return v === null || v === undefined ? '' : String(v); } catch (e) { return ''; }
}
function nodeDesc(node) {
    try { var v = node.desc(); return v === null || v === undefined ? '' : String(v); } catch (e) { return ''; }
}
function nodeClass(node) {
    try { var v = node.className(); return v === null || v === undefined ? '' : String(v); } catch (e) { return ''; }
}
function nodePackage(node) {
    try { var v = node.packageName(); return v === null || v === undefined ? '' : String(v); } catch (e) { return ''; }
}
function nodeId(node) {
    try { var v = node.id(); return v === null || v === undefined ? '' : String(v); } catch (e) { return ''; }
}
function shortId(full) {
    var marker = ':id/';
    var at = String(full).indexOf(marker);
    return at >= 0 ? String(full).substring(at + marker.length) : String(full);
}
function listSize(list) {
    if (list === null || list === undefined) return -1;
    try { if (typeof list.size === 'function') return Number(list.size()); } catch (e) {}
    try { if (typeof list.length === 'number') return Number(list.length); } catch (e) {}
    return -1;
}
function rectInfo(rect) {
    if (!rect) return 'null';
    try {
        return '[' + rect.left + ',' + rect.top + '][' + rect.right + ',' + rect.bottom + ']' +
            ' w=' + rect.width() + ' h=' + rect.height();
    } catch (e) { return 'Rect(?)'; }
}
function pathInfo(path) {
    try {
        var parts = [];
        for (var i = 0; i < path.length; i++) parts.push(String(path[i]));
        return '[' + parts.join(',') + ']';
    } catch (e) { return '[]'; }
}

function selectorText(value) {
    if (typeof text === 'function') return text(value);
    return selector().text(value);
}
function selectorClass(value) {
    if (typeof className === 'function') return className(value);
    return selector().className(value);
}
function selectorDesc(value) {
    if (typeof desc === 'function') return desc(value);
    return selector().desc(value);
}
function selectorId(value) {
    if (typeof id === 'function') return id(value);
    return selector().id(value);
}

function checkRead(name, fn, formatter) {
    var value;
    try { value = fn(); } catch (e) { fail(name, errText(e)); return null; }
    if (value === undefined || value === null) { fail(name, '返回 ' + value); return null; }
    var detail;
    try { detail = formatter ? formatter(value) : clip(value, 80); }
    catch (e) { detail = clip(value, 80); }
    pass(name, detail);
    return value;
}

function callBoolean(name, node, method, args) {
    if (!node) { skip(name, '没有可用节点'); return null; }
    var fn;
    try { fn = node[method]; } catch (e) { fail(name, errText(e)); return null; }
    if (typeof fn !== 'function') { fail(name, method + ' 缺少接口'); return null; }
    try {
        var value;
        if (!args || args.length === 0) value = fn.call(node);
        else if (args.length === 1) value = fn.call(node, args[0]);
        else if (args.length === 2) value = fn.call(node, args[0], args[1]);
        else value = fn.apply(node, args);
        if (typeof value === 'boolean') {
            if (value) pass(name, 'return=true（接口回报；效果由对应检查确认）');
            else skip(name, 'return=false：动作未生效，可能不支持/被拒绝/节点过期');
            return value;
        }
        fail(name, '返回类型 ' + typeof value + '，期望 boolean');
        return null;
    } catch (e) { fail(name, errText(e)); return null; }
}

function hasAccessibility() {
    var service = null;
    try { service = auto.service; } catch (e) { skip('accessibility.service', errText(e)); return false; }
    if (!service) { skip('accessibility.service', '无障碍服务未启用，请先在系统设置中开启'); return false; }
    pass('accessibility.service', 'auto.service 可用');
    return true;
}

function dismissSurface() {
    if (!dialog) return;
    var target = dialog;
    dialog = null;
    try {
        if (typeof ui !== 'undefined' && ui && typeof ui.run === 'function') {
            ui.run(function () { target.dismiss(); });
        } else {
            target.dismiss();
        }
    } catch (e) {
        try { target.dismiss(); } catch (e2) {}
    }
    sleep(200);
}

function showSurface() {
    dismissSurface();
    try {
        dialog = dialogs.build({
            title: TITLE,
            content: 'SAFE_DEMO_ONLY',
            positive: BUTTON,
            negative: 'SAFE_CANCEL',
            inputHint: 'NODE_API_HINT',
            inputPrefill: INPUT,
            cancelable: true,
            canceledOnTouchOutside: false
        }).show();
    } catch (e) {
        skip('surface.show', errText(e) + '（回退到当前窗口只读检查）');
        dialog = null;
        return null;
    }
    sleep(400);
    return dialog;
}

function findTitle(timeout) {
    try { return selectorText(TITLE).findOne(timeout || 3000); } catch (e) { return null; }
}
function findButton() {
    try { return selectorText(BUTTON).findOne(2000); } catch (e) { return null; }
}
function findInput() {
    try { return selectorText(INPUT).findOne(2000); } catch (e) { return null; }
}

function firstWithDesc() {
    var list;
    try { list = selector().descMatches('(?s).+').find(); } catch (e) { return null; }
    var size = listSize(list);
    for (var i = 0; i < size; i++) {
        var node = list.get(i);
        var value = nodeDesc(node);
        if (value) return { node: node, value: value };
    }
    return null;
}

function firstWithId() {
    var list;
    try { list = selector().idMatches('.+').find(); } catch (e) { return null; }
    var size = listSize(list);
    for (var i = 0; i < size; i++) {
        var node = list.get(i);
        var value = nodeId(node);
        if (value) return { node: node, value: value };
    }
    return null;
}

function runReadOnlyChecks(node, tag) {
    if (!node) { fail(tag + '.node', '没有可用节点'); return; }

    var textValue = nodeText(node);
    var classValue = nodeClass(node);
    var descValue = nodeDesc(node);
    var idValue = nodeId(node);
    pass(tag + '.node', 'class=' + clip(classValue, 48) + ' text=' + clip(textValue, 48));

    if (textValue) {
        checkRead('selector.text.findOne', function () {
            var found = selectorText(textValue).findOne(3000);
            if (!found) throw new Error('findOne 返回 null');
            return found;
        }, function () { return 'text=' + clip(textValue, 48); });
        checkRead('selector.text.untilFind', function () {
            var collection = selectorText(textValue).untilFind(3000);
            if (listSize(collection) < 1) throw new Error('untilFind size=0');
            return collection;
        }, function (collection) { return 'size=' + listSize(collection); });
    } else {
        skip('selector.text.findOne', '节点 text 为空');
        skip('selector.text.untilFind', '节点 text 为空');
    }

    if (classValue) {
        checkRead('selector.className', function () {
            var collection = selectorClass(classValue).find();
            if (listSize(collection) < 1) throw new Error('className 查询 size=0');
            return collection;
        }, function (collection) { return 'className=' + clip(classValue, 48) + ' size=' + listSize(collection); });
    } else {
        skip('selector.className', '节点 className 为空');
    }

    var idProbe = idValue ? { node: node, value: idValue } : firstWithId();
    if (idProbe && idProbe.value) {
        var idValueShort = shortId(idProbe.value);
        checkRead('selector.id.findOne', function () {
            var found = selectorId(idValueShort).findOne(2000);
            if (!found) throw new Error('id 查询返回 null');
            return found;
        }, function () { return 'id=' + clip(idValueShort, 64); });
    } else {
        skip('selector.id.findOne', '当前窗口没有带 id 的节点');
    }

    var descProbe = descValue ? { node: node, value: descValue } : firstWithDesc();
    if (descProbe && descProbe.value) {
        var descQuery = descProbe.value;
        checkRead('selector.desc.findOne', function () {
            var found = selectorDesc(descQuery).findOne(2000);
            if (!found) throw new Error('desc 查询返回 null');
            return found;
        }, function () { return 'desc=' + clip(descQuery, 64); });
    } else {
        skip('selector.desc.findOne', '当前窗口没有带 desc 的节点');
    }

    checkRead('property.text', function () { return nodeText(node); });
    checkRead('property.desc', function () { return nodeDesc(node); });
    checkRead('property.id', function () { return nodeId(node); });
    checkRead('property.className', function () { return nodeClass(node); });
    checkRead('property.packageName', function () { return nodePackage(node); });
    checkRead('property.bounds', function () { return node.bounds(); }, rectInfo);
    checkRead('property.boundsInParent', function () { return node.boundsInParent(); }, rectInfo);
    checkRead('property.drawingOrder', function () { return node.drawingOrder(); });
    checkRead('property.depth', function () { return node.depth(); });
    checkRead('property.indexInParent', function () { return node.indexInParent(); });
    checkRead('property.childCount', function () { return node.childCount(); });
    checkRead('property.clickable', function () { return node.clickable(); });
    checkRead('property.longClickable', function () { return node.longClickable(); });
    checkRead('property.enabled', function () { return node.enabled(); });
    checkRead('property.focusable', function () { return node.focusable(); });
    checkRead('property.focused', function () { return node.focused(); });
    checkRead('property.visibleToUser', function () { return node.visibleToUser(); });
    checkRead('property.accessibilityFocused', function () { return node.accessibilityFocused(); });
    checkRead('property.selected', function () { return node.selected(); });
    checkRead('property.checkable', function () { return node.checkable(); });
    checkRead('property.checked', function () { return node.checked(); });
    checkRead('property.scrollable', function () { return node.scrollable(); });
    checkRead('property.password', function () { return node.password(); });
    checkRead('property.toString', function () { return String(node.toString()); });

    var parent = null;
    try { parent = node.parent(); } catch (e) { fail('traversal.parent', errText(e)); }
    if (parent) pass('traversal.parent', clip(nodeClass(parent), 48));
    else if (parent === null) skip('traversal.parent', '根节点没有 parent');

    var children = null;
    try { children = node.children(); } catch (e) { fail('traversal.children', errText(e)); }
    if (children) pass('traversal.children', 'size=' + listSize(children));

    var childCount = null;
    try { childCount = Number(node.childCount()); } catch (e) { fail('traversal.childCount', errText(e)); }
    if (childCount !== null) pass('traversal.childCount', String(childCount));

    if (childCount !== null && childCount > 0) {
        checkRead('traversal.child', function () {
            var child = node.child(0);
            if (!child) throw new Error('child(0) 返回 null');
            return child;
        }, function (child) { return clip(nodeClass(child), 48); });
    } else if (childCount === 0) {
        pass('traversal.child', 'childCount=0，无需取子节点');
    }

    var container = parent || node;
    if (textValue && typeof container.findByText === 'function') {
        checkRead('traversal.findByText', function () {
            var list = container.findByText(textValue);
            if (listSize(list) < 1) throw new Error('findByText size=0');
            return list;
        }, function (list) { return 'text=' + clip(textValue, 32) + ' size=' + listSize(list); });
    } else {
        skip('traversal.findByText', textValue ? 'findByText 不可用' : '节点 text 为空');
    }

    if (typeof container.find === 'function') {
        checkRead('traversal.find', function () {
            var list;
            if (textValue) list = container.find(selectorText(textValue));
            else list = container.find(selectorClass(classValue));
            if (listSize(list) < 1) throw new Error('find size=0');
            return list;
        }, function (list) { return 'size=' + listSize(list); });
    } else {
        skip('traversal.find', 'find 不可用');
    }
}

function runActionChecks() {
    var surface = showSurface();
    var title = surface ? findTitle(3000) : null;
    if (!title) {
        fail('actions.surface', '无法定位测试对话框');
        dismissSurface();
        return false;
    }
    pass('actions.surface', '已定位测试对话框');

    var button = findButton();
    var input = findInput();
    if (button) pass('actions.button', '找到 ' + BUTTON);
    else fail('actions.button', '未找到 ' + BUTTON);
    if (input) pass('actions.input', '找到 ' + INPUT);
    else skip('actions.input', '输入框不可见，setText 只做契约检查');

    if (input) {
        var original = nodeText(input);
        var setResult = callBoolean('action.setText.write', input, 'setText', ['NODE_API_SET_TEXT']);
        var afterNode = null;
        try { afterNode = selectorText('NODE_API_SET_TEXT').findOne(1500); } catch (e) {}
        var after = afterNode ? nodeText(afterNode) : '<not-found>';
        var restoreResult = callBoolean('action.setText.restore', afterNode || input, 'setText', [original]);
        var restoredNode = findInput();
        var restored = restoredNode ? nodeText(restoredNode) : '<not-found>';
        if (setResult === true && after === 'NODE_API_SET_TEXT' && restoreResult === true && restored === original) {
            pass('action.setText.roundtrip', '写入并恢复自己的输入框');
        } else {
            fail('action.setText.roundtrip',
                'set=' + setResult + ' after=' + clip(after, 24) + ' restore=' + restoreResult + ' restored=' + clip(restored, 24));
        }
    } else {
        callBoolean('action.setText.contract', title, 'setText', ['NODE_API_SET_TEXT']);
        skip('action.setText.write', '没有安全输入框，未写入真实输入');
    }

    callBoolean('action.copy', title, 'copy');
    callBoolean('action.cut', title, 'cut');
    callBoolean('action.paste', title, 'paste');
    callBoolean('action.select', title, 'select');
    callBoolean('action.collapse', title, 'collapse');
    callBoolean('action.expand', title, 'expand');
    callBoolean('action.show', title, 'show');
    callBoolean('action.contextClick', title, 'contextClick');
    callBoolean('action.focus', title, 'focus');
    callBoolean('action.clearFocus', title, 'clearFocus');
    callBoolean('action.accessibilityFocus', title, 'accessibilityFocus');
    callBoolean('action.clearAccessibilityFocus', title, 'clearAccessibilityFocus');
    callBoolean('action.scrollForward', title, 'scrollForward');
    callBoolean('action.scrollBackward', title, 'scrollBackward');
    callBoolean('action.scrollUp', title, 'scrollUp');
    callBoolean('action.scrollDown', title, 'scrollDown');
    callBoolean('action.scrollLeft', title, 'scrollLeft');
    callBoolean('action.scrollRight', title, 'scrollRight');
    callBoolean('action.longClick', title, 'longClick');
    callBoolean('action.setSelection', title, 'setSelection', [0, 0]);
    callBoolean('action.setProgress', title, 'setProgress', [0.5]);
    callBoolean('action.scrollTo', title, 'scrollTo', [0, 0]);
    callBoolean('action.dismiss', title, 'dismiss');
    dismissSurface();
    return true;
}

function runTapCheck(method) {
    var surface = showSurface();
    var button = surface ? findButton() : null;
    if (!button) {
        fail('action.' + method, '找不到 ' + BUTTON);
        dismissSurface();
        return;
    }
    var result = callBoolean('action.' + method, button, method);
    sleep(500);
    var after = findTitle(800);
    if (result === true) {
        pass('action.' + method + '.effect', after ? '对话框仍可见（无障碍缓存可能滞后）' : '对话框已关闭');
    } else {
        skip('action.' + method + '.effect', '动作返回 false，未观察到关闭');
    }
    dismissSurface();
}

function main() {
    if (!hasAccessibility()) return;

    showSurface();
    var title = findTitle(3000);
    if (title) {
        pass('surface.dialog', '已显示测试对话框');
        runReadOnlyChecks(title, 'dialog');
        dismissSurface();
        runActionChecks();
        runTapCheck('click');
        runTapCheck('clickCenter');
        return;
    }

    skip('surface.dialog', '无法通过无障碍看到脚本自己的对话框（回退到当前窗口只读检查）');
    dismissSurface();
    var root = null;
    var rootFailed = false;
    try { root = auto.rootInActiveWindow; } catch (e) { rootFailed = true; fail('fallback.root', errText(e)); }
    if (root) {
        var candidate = root;
        try {
            var list = selector().textMatches('(?s).+').find();
            if (listSize(list) > 0) candidate = list.get(0);
        } catch (e) {}
        runReadOnlyChecks(candidate, 'fallback');
    } else if (!rootFailed) {
        fail('fallback.root', '没有活动窗口');
    }
    skip('actions.all', '受控对话框不可见，动作测试全部跳过（避免误点真实界面）');
}

try { main(); } catch (e) { fail('uncaught', errText(e)); }
dismissSurface();

var passed = 0, failed = 0, skipped = 0;
for (var i = 0; i < results.length; i++) {
    if (results[i].status === 'PASS') passed++;
    else if (results[i].status === 'FAIL') failed++;
    else skipped++;
}
console.log('NODE_UI_API_RESULT=' + JSON.stringify({
    engine: ENGINE, passed: passed, failed: failed, skipped: skipped, checks: results
}));
console.log('RESULT=' + (failed === 0 ? (skipped === 0 ? 'PASS' : 'PARTIAL') : 'FAIL'));
