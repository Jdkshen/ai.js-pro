/* Copy/cut/paste for the Ace workspace, backed by Android's ClipboardManager.
 * The selection is read on the JS thread and written to the native clipboard
 * synchronously, before any focus/keyboard change can collapse it. */
(function () {
    function parseEnvelope(raw) {
        if (typeof raw !== 'string') return {ok: false, reason: 'error'};
        try {
            const value = JSON.parse(raw);
            return value && typeof value === 'object' ? value : {ok: false, reason: 'error'};
        } catch (error) {
            return {ok: false, reason: 'error'};
        }
    }
    window.installEditorClipboard = function (editor) {
        const bridge = window.EditorBridge || null;
        function message(text) {
            if (!bridge || typeof bridge.showMessage !== 'function') return;
            try { bridge.showMessage(String(text)); } catch (error) { }
        }
        function copy(text, success, emptyMessage, failureMessage) {
            if (text === null || text === undefined || String(text).length === 0) {
                message(emptyMessage || '没有可复制的内容');
                return false;
            }
            if (!bridge || typeof bridge.copyToClipboard !== 'function') {
                message('剪贴板不可用');
                return false;
            }
            let result;
            try { result = bridge.copyToClipboard(String(text)); }
            catch (error) { result = ''; }
            if (result !== 'ok') {
                message(failureMessage || '复制失败');
                return false;
            }
            if (success) message(success);
            return true;
        }
        function selection() {
            return editor.getSelectedText() || '';
        }
        function focus() {
            try { editor.focus(); } catch (error) { }
            if (window.port && typeof window.port.showKeyboard === 'function') {
                try { window.port.showKeyboard(); } catch (error) { }
            }
        }
        function copySelection() {
            const text = selection();
            if (!text) { message('请先选中内容'); return false; }
            return copy(text, '已复制', '没有可复制的内容', '复制失败');
        }
        function cutSelection() {
            const text = selection();
            if (!text) { message('请先选中内容'); return false; }
            if (!copy(text, '', '没有可剪切的内容', '剪切失败')) return false;
            try {
                editor.session.remove(editor.getSelectionRange());
                editor.clearSelection();
            } catch (error) {
                message('剪切失败');
                return false;
            }
            message('已剪切');
            focus();
            return true;
        }
        function copyAll() {
            return copy(editor.getValue(), '已复制全部', '没有可复制的内容', '复制失败');
        }
        function copyLine() {
            const line = editor.session.getLine(editor.getCursorPosition().row);
            if (!line) { message('当前行没有可复制内容'); return false; }
            return copy(line, '已复制行', '当前行没有可复制内容', '复制失败');
        }
        function readClipboard() {
            if (!bridge || typeof bridge.readClipboard !== 'function') {
                return {ok: false, reason: 'unavailable'};
            }
            try { return parseEnvelope(bridge.readClipboard()); }
            catch (error) { return {ok: false, reason: 'error'}; }
        }
        function paste() {
            const value = readClipboard();
            if (!value.ok || typeof value.text !== 'string' || value.text.length === 0) {
                message(value.reason === 'empty' ? '剪贴板为空' : '无法读取剪贴板');
                return false;
            }
            editor.insert(value.text);
            focus();
            return true;
        }
        function selectAll() {
            editor.selectAll();
            focus();
            return true;
        }
        return {copySelection: copySelection, cutSelection: cutSelection,
            copyAll: copyAll, copyLine: copyLine, paste: paste, selectAll: selectAll};
    };
})();
