'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const assets = path.resolve(__dirname, '../apps/app/src/main/assets/editor/ace');
const read = name => fs.readFileSync(path.join(assets, name), 'utf8');

function clipboardSandbox() {
    const messages = [];
    const clipboard = {text: null, failWrite: false, readMode: 'normal'};
    let focusCount = 0, keyboardCount = 0;
    let text = 'const a = 1;\nconst b = 2;';
    let start = 0, end = 0;

    function position(offset) {
        const before = text.slice(0, offset).split('\n');
        return {row: before.length - 1, column: before[before.length - 1].length};
    }
    function offset(pos) {
        let value = 0;
        for (let row = 0; row < pos.row; row++) value += text.split('\n')[row].length + 1;
        return value + pos.column;
    }
    const editor = {
        getValue: () => text,
        getSelectedText: () => text.slice(start, end),
        getSelectionRange: () => ({start: position(start), end: position(end)}),
        getCursorPosition: () => position(start),
        session: {
            remove(range) {
                const a = Math.min(offset(range.start), offset(range.end));
                const b = Math.max(offset(range.start), offset(range.end));
                text = text.slice(0, a) + text.slice(b);
                start = end = a;
            },
            getLine(row) { return text.split('\n')[row] || ''; }
        },
        insert(value) {
            const a = Math.min(start, end), b = Math.max(start, end);
            text = text.slice(0, a) + value + text.slice(b);
            start = end = a + value.length;
        },
        clearSelection() { end = start; },
        selectAll() { start = 0; end = text.length; },
        focus() { focusCount++; }
    };
    const bridge = {
        copyToClipboard(value) {
            if (clipboard.failWrite) return 'failed';
            clipboard.text = String(value);
            return 'ok';
        },
        readClipboard() {
            if (clipboard.readMode === 'bad') return '{bad json';
            if (clipboard.readMode === 'unavailable') return null;
            return clipboard.text === null
                ? JSON.stringify({ok: false, reason: 'empty'})
                : JSON.stringify({ok: true, text: clipboard.text});
        },
        showMessage(value) { messages.push(String(value)); }
    };
    const sandbox = {console, JSON, setTimeout, clearTimeout, EditorBridge: bridge};
    sandbox.window = sandbox;
    sandbox.port = {showKeyboard() { keyboardCount++; }};
    vm.createContext(sandbox);
    vm.runInContext(read('editorClipboard.js'), sandbox, {filename: 'editorClipboard.js'});
    const api = sandbox.installEditorClipboard(editor);
    return {
        api, messages, clipboard, editor,
        select(a, b) { start = a; end = b; },
        setText(value) { text = value; start = end = 0; },
        text: () => text,
        state: () => ({start, end, focusCount, keyboardCount})
    };
}

function testClipboardModule() {
    let t = clipboardSandbox();
    assert.equal(t.api.copySelection(), false, 'copy without selection fails');
    assert.equal(t.clipboard.text, null, 'copy without selection leaves clipboard untouched');
    assert.equal(t.messages.at(-1), '请先选中内容');

    t = clipboardSandbox();
    t.select(0, 'const a = 1'.length);
    assert.equal(t.api.copySelection(), true);
    assert.equal(t.clipboard.text, 'const a = 1');
    assert.equal(t.messages.at(-1), '已复制');

    t = clipboardSandbox();
    t.select(0, 'const a = 1;'.length);
    assert.equal(t.api.cutSelection(), true);
    assert.equal(t.clipboard.text, 'const a = 1;');
    assert.equal(t.text(), '\nconst b = 2;');
    assert.deepEqual(t.state().start, t.state().end, 'cut collapses the removed range');
    assert.ok(t.state().focusCount > 0 && t.state().keyboardCount > 0, 'cut restores editor focus');
    assert.equal(t.messages.at(-1), '已剪切');

    t = clipboardSandbox();
    t.api.copyAll();
    assert.equal(t.clipboard.text, 'const a = 1;\nconst b = 2;');
    assert.equal(t.messages.at(-1), '已复制全部');

    t = clipboardSandbox();
    t.select(t.text().length, t.text().length);
    assert.equal(t.api.copyLine(), true);
    assert.equal(t.clipboard.text, 'const b = 2;');
    assert.equal(t.messages.at(-1), '已复制行');

    t = clipboardSandbox();
    t.setText('');
    assert.equal(t.api.copyAll(), false);
    assert.equal(t.clipboard.text, null, 'empty copy must not clear clipboard');
    assert.equal(t.messages.at(-1), '没有可复制的内容');

    t = clipboardSandbox();
    t.clipboard.failWrite = true;
    assert.equal(t.api.copyAll(), false);
    assert.equal(t.messages.at(-1), '复制失败', 'write failure is not reported as empty content');

    t = clipboardSandbox();
    t.setText('a\n\nb');
    t.select(2, 2);
    assert.equal(t.api.copyLine(), false, 'empty line is not copied');
    assert.equal(t.messages.at(-1), '当前行没有可复制内容');

    t = clipboardSandbox();
    assert.equal(t.api.paste(), false, 'empty clipboard is rejected');
    assert.equal(t.text(), 'const a = 1;\nconst b = 2;');
    assert.equal(t.messages.at(-1), '剪贴板为空');

    t = clipboardSandbox();
    t.clipboard.text = 'INSERTED';
    t.select(6, 11);
    assert.equal(t.api.paste(), true);
    assert.equal(t.text(), 'const INSERTED;\nconst b = 2;');
    assert.ok(t.state().focusCount > 0 && t.state().keyboardCount > 0, 'paste restores editor focus');

    t = clipboardSandbox();
    t.clipboard.readMode = 'bad';
    assert.equal(t.api.paste(), false, 'malformed clipboard JSON is rejected');
    assert.equal(t.messages.at(-1), '无法读取剪贴板');

    t = clipboardSandbox();
    t.clipboard.failWrite = true;
    t.select(0, 5);
    assert.equal(t.api.cutSelection(), false, 'failed clipboard write must not delete text');
    assert.equal(t.text(), 'const a = 1;\nconst b = 2;');
    assert.equal(t.messages.at(-1), '剪切失败');

    t = clipboardSandbox();
    t.api.selectAll();
    assert.deepEqual(t.state().start, 0);
    assert.equal(t.state().end, 'const a = 1;\nconst b = 2;'.length);
    assert.equal(t.messages.length, 0, 'select-all is silent');
    return 'clipboard selection/copy/cut/paste/empty cases';
}

function touchSandbox(useLegacy) {
    const calls = {start: 0, finish: 0, args: null};
    const handlers = new Map();
    const timers = new Map();
    let nextTimer = 1;
    const editor = {
        actionMode: '',
        selection: {
            empty: false,
            isEmpty() { return this.empty; },
            getRange() { return {start: {row: 0, column: 0}, end: {row: 0, column: 2}}; }
        },
        renderer: {
            scroller: {getBoundingClientRect() { return {top: 0, height: 100}; }},
            textToScreenCoordinates() { return {pageX: 30, pageY: 20}; }
        },
        on(name, handler) {
            if (!handlers.has(name)) handlers.set(name, []);
            handlers.get(name).push(handler);
        },
        off(name, handler) {
            const list = handlers.get(name) || [];
            const index = list.indexOf(handler);
            if (index >= 0) list.splice(index, 1);
        }
    };
    const target = {
        startActionMode(...args) { calls.start++; calls.args = args; },
        finishActionMode() { calls.finish++; }
    };
    const sandbox = {
        console,
        setTimeout(fn) { const id = nextTimer++; timers.set(id, fn); return id; },
        clearTimeout(id) { timers.delete(id); },
        ace: {
            require(name) {
                if (name === 'ace/mouse/mouse_handler') return {MouseHandler: function () {}};
                return {};
            },
            config: {defineOptions() {}},
            Editor: function () {}
        }
    };
    sandbox.ace.Editor.prototype = {};
    sandbox.window = sandbox;
    if (useLegacy) sandbox.autojs = target; else sandbox.EditorBridge = target;
    vm.createContext(sandbox);
    vm.runInContext(read('androidTouch.js'), sandbox, {filename: 'androidTouch.js'});
    sandbox.installAndroidEditorTouch(editor);
    return {
        editor, calls, handlers,
        fire(name) { for (const fn of handlers.get(name) || []) fn(); },
        runTimers() { const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn()); }
    };
}

function editorPortSandbox() {
    const messages = [];
    const clipboard = {text: null};
    let focusCount = 0, keyboardCount = 0;
    let text = 'const a = 1;\nconst b = 2;';
    let start = 0, end = 0;
    function position(offset) {
        const before = text.slice(0, offset).split('\n');
        return {row: before.length - 1, column: before[before.length - 1].length};
    }
    function offset(pos) {
        let value = 0;
        for (let row = 0; row < pos.row; row++) value += text.split('\n')[row].length + 1;
        return value + pos.column;
    }
    const editor = {
        actionMode: '', completers: [],
        getValue: () => text,
        getSelectedText: () => text.slice(start, end),
        getSelectionRange: () => ({start: position(start), end: position(end)}),
        getCursorPosition: () => position(start),
        getFontSize: () => 17,
        getTheme: () => 'ace/theme/tomorrow_night',
        getScrollLeft: () => 0, getScrollTop: () => 0,
        setOptions() {}, setOption() {}, setTheme() {}, on() {},
        focus() { focusCount++; },
        insert(value) {
            const a = Math.min(start, end), b = Math.max(start, end);
            text = text.slice(0, a) + value + text.slice(b);
            start = end = a + value.length;
        },
        clearSelection() { end = start; },
        selectAll() { start = 0; end = text.length; },
        session: {
            setMode() {}, setUseWorker() {},
            getLine(row) { return text.split('\n')[row] || ''; },
            remove(range) {
                const a = Math.min(offset(range.start), offset(range.end));
                const b = Math.max(offset(range.start), offset(range.end));
                text = text.slice(0, a) + text.slice(b);
                start = end = a;
            }
        }
    };
    const signature = {style: {}, textContent: ''};
    const bridge = {
        ready() {},
        update() {},
        copyToClipboard(value) { clipboard.text = String(value); return 'ok'; },
        readClipboard() {
            return clipboard.text === null
                ? JSON.stringify({ok: false, reason: 'empty'})
                : JSON.stringify({ok: true, text: clipboard.text});
        },
        showMessage(value) { messages.push(String(value)); },
        showKeyboard() { keyboardCount++; }
    };
    const sandbox = {
        console, JSON, Map, Set, Date, performance, URL,
        setTimeout, clearTimeout,
        location: {origin: 'https://editor.aijspro.local/'},
        document: {
            getElementById(id) { return id === 'signature' ? signature : {style: {}}; }
        },
        ResizeObserver: class { observe() {} },
        ace: {
            config: {set() {}},
            edit() { return editor; },
            require(name) {
                if (name === 'ace/range') return {Range: class {}};
                return {};
            }
        },
        installAndroidEditorTouch(value) { value.actionMode = ''; },
        installEditorAppearance() {},
        installEditorPinch() { return {setFontSize() {}, destroy() {}}; },
        EditorBridge: bridge
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(read('editorClipboard.js'), sandbox, {filename: 'editorClipboard.js'});
    vm.runInContext(read('editor.js'), sandbox, {filename: 'editor.js'});
    return {
        sandbox, messages, clipboard, editor,
        select(a, b) { start = a; end = b; },
        text: () => text,
        state: () => ({start, end, focusCount, keyboardCount})
    };
}

function testEditorPortIntegration() {
    let t = editorPortSandbox();
    assert.equal(typeof t.sandbox.port.copySelection, 'function');
    t.select(0, 5);
    assert.equal(t.sandbox.port.copySelection(), true);
    assert.equal(t.clipboard.text, 'const');
    assert.equal(t.messages.at(-1), '已复制');

    t = editorPortSandbox();
    t.select(0, 5);
    assert.equal(t.sandbox.port.cutSelection(), true);
    assert.equal(t.text(), ' a = 1;\nconst b = 2;');
    assert.equal(t.clipboard.text, 'const');

    t = editorPortSandbox();
    t.sandbox.port.copyAll();
    assert.equal(t.clipboard.text, 'const a = 1;\nconst b = 2;');
    t.sandbox.port.selectAll();
    assert.deepEqual(t.state().start, 0);
    assert.equal(t.state().end, 'const a = 1;\nconst b = 2;'.length);

    t = editorPortSandbox();
    t.clipboard.text = null;
    assert.equal(t.sandbox.port.paste(), false);
    assert.equal(t.messages.at(-1), '剪贴板为空');
    t.clipboard.text = 'X';
    t.select(0, 1);
    assert.equal(t.sandbox.port.paste(), true);
    assert.equal(t.text(), 'Xonst a = 1;\nconst b = 2;');

    t = editorPortSandbox();
    t.editor.actionMode = 'selectionActionMode';
    t.sandbox.port.onNativeActionModeClosed();
    assert.equal(t.editor.actionMode, '');
    return 'editor.js port wiring';
}

function testTouchModule() {
    let t = touchSandbox(false);
    assert.deepEqual(JSON.parse(JSON.stringify(t.editor.renderer.getClientPosition({row:0,column:2}))),
        {x:30,y:20}, 'legacy long-press renderer helper must exist on Ace 1.44');
    t.editor.selection.empty = true;
    t.editor.startActionMode();
    assert.equal(t.calls.start, 0, 'plain tap must not open a cursor action mode');
    assert.equal(t.editor.actionMode, '');

    t = touchSandbox(false);
    t.editor.startActionMode();
    assert.equal(t.calls.start, 1);
    assert.equal(t.calls.args[4], true);
    assert.equal(t.editor.actionMode, 'selectionActionMode');
    t.editor.finishActionMode();
    assert.equal(t.calls.finish, 1);
    assert.equal(t.editor.actionMode, '');

    t = touchSandbox(false);
    t.editor.startActionModeIfSelectionInView(true);
    assert.equal(t.calls.start, 0, 'selection menu waits for the gesture to settle');
    t.runTimers();
    assert.equal(t.calls.start, 1, 'in-view selection opens the native menu');

    t = touchSandbox(false);
    t.editor.startActionMode();
    t.editor.selection.empty = true;
    t.fire('changeSelection');
    assert.equal(t.calls.finish, 1, 'collapsing selection closes the native menu');
    assert.equal(t.editor.actionMode, '');

    t = touchSandbox(true);
    t.editor.startActionMode();
    assert.equal(t.calls.start, 1, 'legacy autojs bridge still receives selection menu');
    assert.equal(t.editor.actionMode, 'selectionActionMode');
    return 'androidTouch native action-mode bridge';
}

console.log('PASS:', testClipboardModule());
console.log('PASS:', testEditorPortIntegration());
console.log('PASS:', testTouchModule());
