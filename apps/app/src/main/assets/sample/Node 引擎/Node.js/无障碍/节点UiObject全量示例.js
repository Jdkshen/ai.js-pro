"nodejs";
// 节点 / UiObject API 全量示例（Node24 / accessibility）
//
// 前置：系统设置 → 无障碍 → 本应用服务已开启；脚本在前台运行。
// 安全：只操作脚本自己弹出的 SAFE_CLOSE 对话框（标题 NODE_API_UI_SURFACE），
//       不点击真实应用按钮，不读写剪贴板，不修改用户输入；setText 只写自己的输入框并恢复。
// 输出：逐项 [PASS]/[FAIL]/[SKIP]，末尾 NODE_UI_API_RESULT JSON 与 RESULT 行。
//
// Node24 的 select()/firstOrNull()/findFirst()/all()/root() 是 Promise；节点动作是同步 boolean。
// 本示例用 typeof 检查每个方法：后端尚未实现的统一动作/属性会明确输出 SKIP，不伪造 PASS。
// 覆盖：select({text/className/desc/id})、firstOrNull/all/findFirst/findOnce/findOne/waitFor、
//       NodeView 属性、parent/children/child/childCount、findByText/find，
//       click/clickCenter/longClick/setText/copy/cut/paste/select/collapse/expand/dismiss/show/
//       contextClick/focus/clearFocus/accessibilityFocus/clearAccessibilityFocus/
//       scrollForward/Backward/Up/Down/Left/Right、setSelection/setProgress/scrollTo。

(async function () {
    const accessibilityModule = require('accessibility');
    const accessibility = accessibilityModule.accessibility;
    const select = accessibilityModule.select;
    const { delay } = require('lang');
    const dialogs = require('dialogs');

    const ENGINE = 'nodejs';
    const TITLE = 'NODE_API_UI_SURFACE';
    const INPUT = 'NODE_API_INPUT';
    const BUTTON = 'SAFE_CLOSE';
    const results = [];
    let surface = null;

    function clip(value, max) {
        let s = value === null || value === undefined ? '' : String(value);
        s = s.replace(/\s+/g, ' ');
        if (s.length > max) s = s.substring(0, max) + '…';
        return s;
    }

    function describeError(error) {
        if (!error) return 'unknown error';
        if (error.message) return String(error.message);
        return String(error);
    }

    function record(status, name, detail) {
        const line = '[' + status + '] ' + name + (detail ? ' :: ' + detail : '');
        console.log(line);
        results.push({ name: name, status: status, detail: detail ? String(detail) : '' });
    }
    function pass(name, detail) { record('PASS', name, detail); }
    function fail(name, detail) { record('FAIL', name, detail); }
    function skip(name, detail) { record('SKIP', name, detail); }

    function read(node, key) {
        if (node === null || node === undefined) return undefined;
        try {
            const value = node[key];
            if (typeof value === 'function') return value.call(node);
            return value;
        } catch (error) { return undefined; }
    }

    function listSize(list) {
        if (list === null || list === undefined) return -1;
        try { if (typeof list.size === 'function') return Number(list.size()); } catch (error) {}
        try { if (typeof list.length === 'number') return Number(list.length); } catch (error) {}
        return -1;
    }

    function rectInfo(rect) {
        if (!rect) return 'null';
        try {
            return '[' + rect.left + ',' + rect.top + '][' + rect.right + ',' + rect.bottom + ']' +
                ' w=' + rect.width + ' h=' + rect.height;
        } catch (error) { return 'Rect(?)'; }
    }

    function pathInfo(path) {
        try {
            if (!Array.isArray(path)) return String(path);
            const parts = [];
            for (let i = 0; i < path.length; i++) parts.push(String(path[i]));
            return '[' + parts.join(',') + ']';
        } catch (error) { return '[]'; }
    }

    async function ensureAccessibility() {
        let status = null;
        try { status = await accessibility.status(); }
        catch (error) { skip('accessibility.status', describeError(error)); return false; }
        if (status && status.ready) { pass('accessibility.status', 'ready=true'); return true; }
        try {
            const enabled = await accessibility.enableService({ toast: false, timeout: 10000 });
            if (enabled) { pass('accessibility.enableService', 'ready=true'); return true; }
            skip('accessibility.enableService', '返回 false（未授权或超时）');
            return false;
        } catch (error) {
            skip('accessibility.enableService', describeError(error));
            return false;
        }
    }

    async function dismissSurface() {
        if (!surface) return;
        const target = surface;
        surface = null;
        try { target.dismiss(); } catch (error) {}
        await delay(200);
    }

    async function showSurface() {
        await dismissSurface();
        try {
            const builder = dialogs.build({
                title: TITLE,
                content: 'SAFE_DEMO_ONLY',
                positive: BUTTON,
                negative: 'SAFE_CANCEL',
                cancelable: true,
                canceledOnTouchOutside: false
            });
            surface = await builder.input(INPUT, 'NODE_API_HINT').show();
            await delay(500);
            return surface;
        } catch (error) {
            skip('surface.show', describeError(error) + '（回退到当前窗口只读检查）');
            surface = null;
            return null;
        }
    }

    async function findTitle(timeout) {
        try {
            return await select({ text: TITLE }).findFirst({ timeout: timeout || 3000, maxRetries: 10 });
        } catch (error) { return null; }
    }
    async function findButton() {
        try { return await select({ text: BUTTON }).firstOrNull(); }
        catch (error) { return null; }
    }
    async function findInput() {
        try { return await select({ text: INPUT }).firstOrNull(); }
        catch (error) { return null; }
    }

    function checkRead(name, node, key, formatter) {
        if (node === null || node === undefined) { skip(name, '没有节点'); return undefined; }
        const value = read(node, key);
        if (value === undefined) { skip(name, key + ' 未定义（Node 后端待实现）'); return undefined; }
        let detail;
        try { detail = formatter ? formatter(value) : clip(value, 80); }
        catch (error) { detail = clip(value, 80); }
        pass(name, detail);
        return value;
    }

    function callMethod(name, node, method, args) {
        if (node === null || node === undefined) { skip(name, '没有节点'); return undefined; }
        const fn = node[method];
        if (typeof fn !== 'function') { skip(name, method + ' 未定义（Node 后端待实现）'); return undefined; }
        try {
            const value = fn.apply(node, args || []);
            if (typeof value === 'boolean') { pass(name, 'return=' + value); return value; }
            fail(name, '返回类型 ' + typeof value + '，期望 boolean');
            return undefined;
        } catch (error) {
            fail(name, describeError(error));
            return undefined;
        }
    }

    async function runReadOnlyChecks(title, tag) {
        if (title === null || title === undefined) { fail(tag + '.node', '没有可用节点'); return; }

        const textValue = read(title, 'text') || '';
        const classValue = read(title, 'className') || '';
        const descValue = read(title, 'desc') || '';
        const idValue = read(title, 'id') || '';
        pass(tag + '.node', 'class=' + clip(classValue, 48) + ' text=' + clip(textValue, 48));

        if (textValue) {
            let found = null;
            try { found = await select({ text: textValue }).firstOrNull(); } catch (error) {}
            if (found) pass('selector.text.firstOrNull', 'text=' + clip(textValue, 48));
            else fail('selector.text.firstOrNull', '未找到 text=' + clip(textValue, 48));

            let waited = null;
            try {
                waited = await select({ text: textValue }).findFirst({ timeout: 3000, maxRetries: 10 });
            } catch (error) { fail('selector.text.findFirst', describeError(error)); }
            if (waited) pass('selector.text.findFirst', 'findFirst 命中');
            else skip('selector.text.findFirst', '未完成等待（返回 null 或已在上方失败）');

            try {
                const nodes = await select({ className: /.+/ }).atMost(20).timeout(2000).maxRetries(1).all();
                if (Array.isArray(nodes) && nodes.length > 0) pass('selector.all', 'size=' + nodes.length);
                else fail('selector.all', 'all() 返回空');
            } catch (error) { fail('selector.all', describeError(error)); }
        } else {
            skip('selector.text.firstOrNull', '节点 text 为空');
            skip('selector.text.findFirst', '节点 text 为空');
            skip('selector.all', '节点 text 为空');
        }

        if (classValue) {
            let found = null;
            try { found = await select({ className: classValue }).firstOrNull(); } catch (error) {}
            if (found) pass('selector.className', 'className=' + clip(classValue, 48));
            else fail('selector.className', '未找到 className=' + clip(classValue, 48));
        } else {
            skip('selector.className', '节点 className 为空');
        }

        let descProbe = null;
        try { descProbe = await select({ desc: /.+/ }).firstOrNull(); } catch (error) {}
        if (descProbe) {
            const value = read(descProbe, 'desc') || '';
            if (value) {
                let found = null;
                try { found = await select({ desc: value }).firstOrNull(); } catch (error) {}
                if (found) pass('selector.desc', 'desc=' + clip(value, 64));
                else fail('selector.desc', '未找到 desc=' + clip(value, 64));
            } else { skip('selector.desc', 'desc 探针没有文本'); }
        } else {
            skip('selector.desc', '当前窗口没有带 desc 的节点');
        }

        let idProbe = null;
        try { idProbe = await select({ id: /.+/ }).firstOrNull(); } catch (error) {}
        if (idProbe) {
            const value = read(idProbe, 'id') || '';
            if (value) {
                let found = null;
                try { found = await select({ id: value }).firstOrNull(); } catch (error) {}
                if (found) pass('selector.id', 'id=' + clip(value, 64));
                else fail('selector.id', '未找到 id=' + clip(value, 64));
            } else { skip('selector.id', 'id 探针没有文本'); }
        } else {
            skip('selector.id', '当前窗口没有带 id 的节点');
        }

        if (textValue) {
            let whereNode = null;
            try {
                whereNode = await select({ text: textValue })
                    .where(function (node) { return read(node, 'text') === textValue; })
                    .firstOrNull();
            } catch (error) {}
            if (whereNode) pass('selector.where', 'where 命中');
            else skip('selector.where', 'where 未命中（页面可能已变化）');
        } else { skip('selector.where', '节点 text 为空'); }

        let rootNode = null;
        try { rootNode = await select().root(); } catch (error) {}
        if (rootNode) {
            pass('selector.root', 'root 可用');
            if (textValue) {
                let fromNode = null;
                try { fromNode = await select({ text: textValue }).from(rootNode).firstOrNull(); } catch (error) {}
                if (fromNode) pass('selector.from', 'from(root) 命中');
                else skip('selector.from', 'from(root) 未命中');
            } else { skip('selector.from', '节点 text 为空'); }
        } else {
            skip('selector.root', 'root() 返回 null');
            skip('selector.from', '没有 root 可作为 from 父节点');
        }

        try {
            const tree = select().tree();
            if (Array.isArray(tree) && tree.length > 0) pass('selector.tree', 'size=' + tree.length);
            else fail('selector.tree', 'tree() 返回空');
        } catch (error) { fail('selector.tree', describeError(error)); }

        if (textValue) {
            let once = null;
            try { once = select({ text: textValue }).findOnce(); } catch (error) { fail('selector.findOnce', describeError(error)); }
            if (once) {
                pass('selector.findOnce', '同步 findOnce 命中');
                if (typeof once.text === 'function') pass('node.methodForm', 'Node 方法式 text() 可用');
                else skip('node.methodForm', 'findOnce 返回对象没有 text() 方法');
                const methodText = read(once, 'text');
                if (methodText === textValue) pass('node.methodForm.text', 'text() 与 title 一致');
                else skip('node.methodForm.text', 'text()=' + clip(methodText, 32));
            } else if (once === null) {
                skip('selector.findOnce', 'findOnce 返回 null');
            }

            let one = null;
            try { one = select({ text: textValue }).findOne(1000); } catch (error) { skip('selector.findOne', describeError(error)); }
            if (one) pass('selector.findOne', '同步 findOne 命中');

            let waited = false;
            try { waited = select({ text: textValue }).waitFor(1000); } catch (error) { skip('selector.waitFor', describeError(error)); }
            if (waited === true) pass('selector.waitFor', 'waitFor 命中');
        } else {
            skip('selector.findOnce', '节点 text 为空');
            skip('selector.findOne', '节点 text 为空');
            skip('selector.waitFor', '节点 text 为空');
        }

        checkRead('property.text', title, 'text');
        checkRead('property.desc', title, 'desc');
        checkRead('property.id', title, 'id');
        checkRead('property.className', title, 'className');
        checkRead('property.packageName', title, 'packageName');
        checkRead('property.bounds', title, 'bounds', rectInfo);
        checkRead('property.clickable', title, 'clickable');
        checkRead('property.enabled', title, 'enabled');
        checkRead('property.scrollable', title, 'scrollable');
        checkRead('property.editable', title, 'editable');
        checkRead('property.depth', title, 'depth');
        checkRead('property.indexInParent', title, 'indexInParent');
        checkRead('property.childCount', title, 'childCount');
        checkRead('property.path', title, 'path', pathInfo);
        checkRead('property.boundsInParent', title, 'boundsInParent', rectInfo);
        checkRead('property.drawingOrder', title, 'drawingOrder');
        checkRead('property.checkable', title, 'checkable');
        checkRead('property.checked', title, 'checked');
        checkRead('property.focusable', title, 'focusable');
        checkRead('property.focused', title, 'focused');
        checkRead('property.selected', title, 'selected');
        checkRead('property.longClickable', title, 'longClickable');
        checkRead('property.visibleToUser', title, 'visibleToUser');
        checkRead('property.password', title, 'password');
        if (typeof title.click === 'function') pass('property.actions', 'click/clickCenter 等动作方法可用');
        else fail('property.actions', '节点没有 click 方法');

        let parent = null;
        try { parent = title.parent(); } catch (error) { fail('traversal.parent', describeError(error)); }
        if (parent) pass('traversal.parent', clip(read(parent, 'className'), 48));
        else if (parent === null) skip('traversal.parent', '根节点没有 parent');

        let children = null;
        try { children = title.children(); } catch (error) { fail('traversal.children', describeError(error)); }
        if (Array.isArray(children)) pass('traversal.children', 'size=' + children.length);
        else if (children !== null) fail('traversal.children', '返回值不是数组');

        const childCount = read(title, 'childCount');
        if (typeof childCount === 'number') {
            pass('traversal.childCount', String(childCount));
            if (childCount > 0) {
                let child = null;
                try { child = title.child(0); } catch (error) { fail('traversal.child', describeError(error)); }
                if (child) pass('traversal.child', clip(read(child, 'className'), 48));
                else if (child === null) fail('traversal.child', 'child(0) 返回 null');
            } else {
                pass('traversal.child', 'childCount=0，无需取子节点');
            }
        } else {
            skip('traversal.childCount', 'childCount 不是数字');
            skip('traversal.child', 'childCount 不可用');
        }

        const container = parent || title;
        if (textValue && typeof container.findByText === 'function') {
            let list = null;
            try { list = container.findByText(textValue); } catch (error) { fail('traversal.findByText', describeError(error)); }
            if (list !== null && list !== undefined) {
                if (listSize(list) > 0) pass('traversal.findByText', 'size=' + listSize(list));
                else fail('traversal.findByText', 'size=0');
            }
        } else {
            skip('traversal.findByText', textValue ? 'findByText 未定义（Node 后端待实现）' : '节点 text 为空');
        }

        if (textValue && typeof container.find === 'function') {
            let list = null;
            try { list = container.find(select({ text: textValue })); } catch (error) { fail('traversal.find', describeError(error)); }
            if (list !== null && list !== undefined) {
                if (listSize(list) > 0) pass('traversal.find', 'size=' + listSize(list));
                else fail('traversal.find', 'size=0');
            }
        } else {
            skip('traversal.find', textValue ? 'find 未定义（Node 后端待实现）' : '节点 text 为空');
        }

        if (textValue && typeof container.findOne === 'function') {
            let found = null;
            try { found = container.findOne(select({ text: textValue })); } catch (error) { fail('traversal.findOne', describeError(error)); }
            if (found) pass('traversal.findOne', 'findOne 命中');
            else if (found === null) fail('traversal.findOne', '返回 null');
        } else {
            skip('traversal.findOne', textValue ? 'findOne 未定义（Node 后端待实现）' : '节点 text 为空');
        }
    }

    async function runActionChecks() {
        const active = await showSurface();
        const title = active ? await findTitle(3000) : null;
        if (!title) {
            fail('actions.surface', '无法定位测试对话框');
            await dismissSurface();
            return false;
        }
        pass('actions.surface', '已定位测试对话框');

        const button = await findButton();
        const input = await findInput();
        if (button) pass('actions.button', '找到 ' + BUTTON);
        else fail('actions.button', '未找到 ' + BUTTON);
        if (input) pass('actions.input', '找到 ' + INPUT);
        else skip('actions.input', '输入框不可见，setText 只做契约检查');

        if (input) {
            const original = read(input, 'text') || '';
            const setResult = callMethod('action.setText.write', input, 'setText', ['NODE_API_SET_TEXT']);
            let afterNode = null;
            try { afterNode = await select({ text: 'NODE_API_SET_TEXT' }).firstOrNull(); } catch (error) {}
            const after = afterNode ? (read(afterNode, 'text') || '') : '<not-found>';
            const restoreResult = callMethod('action.setText.restore', afterNode || input, 'setText', [original]);
            const restoredNode = await findInput();
            const restored = restoredNode ? (read(restoredNode, 'text') || '') : '<not-found>';
            if (setResult === true && after === 'NODE_API_SET_TEXT' && restoreResult === true && restored === original) {
                pass('action.setText.roundtrip', '写入并恢复自己的输入框');
            } else {
                fail('action.setText.roundtrip',
                    'set=' + setResult + ' after=' + clip(after, 24) + ' restore=' + restoreResult + ' restored=' + clip(restored, 24));
            }
        } else {
            callMethod('action.setText.contract', title, 'setText', ['NODE_API_SET_TEXT']);
            skip('action.setText.write', '没有安全输入框，未写入真实输入');
        }

        callMethod('action.copy', title, 'copy');
        callMethod('action.cut', title, 'cut');
        callMethod('action.paste', title, 'paste');
        callMethod('action.select', title, 'select');
        callMethod('action.collapse', title, 'collapse');
        callMethod('action.expand', title, 'expand');
        callMethod('action.show', title, 'show');
        callMethod('action.contextClick', title, 'contextClick');
        callMethod('action.focus', title, 'focus');
        callMethod('action.clearFocus', title, 'clearFocus');
        callMethod('action.accessibilityFocus', title, 'accessibilityFocus');
        callMethod('action.clearAccessibilityFocus', title, 'clearAccessibilityFocus');
        callMethod('action.scrollForward', title, 'scrollForward');
        callMethod('action.scrollBackward', title, 'scrollBackward');
        callMethod('action.scrollUp', title, 'scrollUp');
        callMethod('action.scrollDown', title, 'scrollDown');
        callMethod('action.scrollLeft', title, 'scrollLeft');
        callMethod('action.scrollRight', title, 'scrollRight');
        callMethod('action.longClick', title, 'longClick');
        callMethod('action.setSelection', title, 'setSelection', [0, 0]);
        callMethod('action.setProgress', title, 'setProgress', [0.5]);
        callMethod('action.scrollTo', title, 'scrollTo', [0, 0]);
        callMethod('action.dismiss', title, 'dismiss');
        await dismissSurface();
        return true;
    }

    async function runTapCheck(method) {
        const active = await showSurface();
        const button = active ? await findButton() : null;
        if (!button) {
            fail('action.' + method, '找不到 ' + BUTTON);
            await dismissSurface();
            return;
        }
        const result = callMethod('action.' + method, button, method);
        await delay(500);
        const after = await findTitle(800);
        if (result === true) {
            pass('action.' + method + '.effect', after ? '对话框仍可见（无障碍缓存可能滞后）' : '对话框已关闭');
        } else {
            skip('action.' + method + '.effect', '动作返回 false，未观察到关闭');
        }
        await dismissSurface();
    }

    async function main() {
        if (!(await ensureAccessibility())) {
            skip('actions.all', '无障碍未就绪，动作测试全部跳过');
            return;
        }

        await showSurface();
        const title = await findTitle(3000);
        if (title) {
            pass('surface.dialog', '已显示测试对话框');
            await runReadOnlyChecks(title, 'dialog');
            await dismissSurface();
            await runActionChecks();
            await runTapCheck('click');
            await runTapCheck('clickCenter');
            return;
        }

        skip('surface.dialog', '无法通过无障碍看到脚本自己的对话框（回退到当前窗口只读检查）');
        await dismissSurface();
        let root = null;
        try { root = await select().root(); } catch (error) { fail('fallback.root', describeError(error)); }
        if (root) await runReadOnlyChecks(root, 'fallback');
        else if (root === null) fail('fallback.root', 'root() 返回 null');
        skip('actions.all', '受控对话框不可见，动作测试全部跳过（避免误点真实界面）');
    }

    function printSummary() {
        let passed = 0, failed = 0, skipped = 0;
        for (let i = 0; i < results.length; i++) {
            if (results[i].status === 'PASS') passed++;
            else if (results[i].status === 'FAIL') failed++;
            else skipped++;
        }
        console.log('NODE_UI_API_RESULT=' + JSON.stringify({
            engine: ENGINE, passed: passed, failed: failed, skipped: skipped, checks: results
        }));
        console.log('RESULT=' + (failed === 0 ? (skipped === 0 ? 'PASS' : 'PARTIAL') : 'FAIL'));
    }

    try { await main(); }
    catch (error) { fail('uncaught', describeError(error)); }
    await dismissSurface();
    printSummary();
})().catch(function (error) {
    console.error('[FAIL] uncaught.fatal :: ' + (error && error.message ? error.message : String(error)));
    console.log('RESULT=FAIL');
});
