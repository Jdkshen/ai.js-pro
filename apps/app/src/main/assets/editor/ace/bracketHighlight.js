/* Adapted from the user's bracketHighlight.js: matching brackets only.
 * Draw in Ace's front marker layer; never split or replace syntax-token DOM.
 * Matching work is bounded and cached independently of scrolling/rendering.
 */
(function (root) {
    'use strict';
    var TokenIterator = ace.require('ace/token_iterator').TokenIterator;
    var pairs = {'(': ')', '[': ']', '{': '}', ')': '(', ']': '[', '}': '{'};
    var styleId = 'ace-simple-bracket-highlight-style';

    function install(editor) {
        if (editor.$simpleBracketHighlighter) return editor.$simpleBracketHighlighter;
        if (!document.getElementById(styleId)) {
            var style = document.createElement('style');
            style.id = styleId;
            style.textContent = '.ace_marker-layer .ace_matching-bracket-character{' +
                'position:absolute;pointer-events:none;white-space:pre;}' +
                '.ace_matching-bracket-character::after{content:attr(data-bracket);' +
                'font:inherit;line-height:inherit;color:inherit;pointer-events:none;}';
            document.head.appendChild(style);
        }
        var session, marker, frame = null, destroyed = false, dirty = true;
        var positions = [], limited = false, scanMs = 0, scans = 0;

        function matchAtCursor() {
            positions = []; limited = false;
            var cursor = editor.getCursorPosition(), line = session.getLine(cursor.row);
            var column = cursor.column - 1, bracket = line.charAt(column);
            if (!pairs[bracket]) { column = cursor.column; bracket = line.charAt(column); }
            if (!pairs[bracket]) return;
            positions.push({row: cursor.row, column: column, character: bracket});
            // A very long line is expensive even to tokenize; keep its adjacent
            // bracket highlighted without starting a full-line token scan.
            if (line.length > 20000) { limited = true; return; }
            var forward = bracket === '(' || bracket === '[' || bracket === '{';
            // Ace treats token boundaries as belonging to the preceding token.
            // Position just after the bracket, as Ace's own matcher does.
            var iterator = new TokenIterator(session, cursor.row, column + 1);
            var token = iterator.getCurrentToken();
            if (!token) return;
            var type = new RegExp('(\\.?' + token.type.replace('.', '\\.')
                .replace(/[rl]paren/, '.paren')
                .replace(/\b(?:start|begin|end)\b/, '(?:start|begin|end)') + ')+');
            var index = column - iterator.getCurrentTokenColumn() + (forward ? 1 : -1);
            var depth = 1, characters = 0, tokens = 0, began = performance.now();
            while (token) {
                var row = iterator.getCurrentTokenRow();
                if (++tokens > 2000 || Math.abs(row - cursor.row) > 500 || performance.now() - began > 4) {
                    limited = true; break;
                }
                if (type.test(token.type)) {
                    while (index >= 0 && index < token.value.length) {
                        if (++characters > 65536 || (characters % 256 === 0 && performance.now() - began > 4)) {
                            limited = true; return;
                        }
                        var character = token.value.charAt(index);
                        if (character === pairs[bracket]) depth--;
                        else if (character === bracket) depth++;
                        if (depth === 0) {
                            positions.push({row: row, column: iterator.getCurrentTokenColumn() + index,
                                character: character});
                            return;
                        }
                        index += forward ? 1 : -1;
                    }
                }
                // Do not tokenize a pathological line reached while scanning.
                var nextRow = row + (forward ? 1 : -1);
                if (nextRow >= 0 && nextRow < session.getLength() && session.getLine(nextRow).length > 20000) {
                    limited = true; break;
                }
                token = forward ? iterator.stepForward() : iterator.stepBackward();
                index = forward ? 0 : token ? token.value.length - 1 : 0;
            }
        }
        function refresh() {
            if (destroyed || !session) return;
            if (frame !== null) { cancelAnimationFrame(frame); frame = null; }
            var previous = JSON.stringify(positions);
            if (dirty) {
                dirty = false; scans++;
                var began = performance.now(); matchAtCursor(); scanMs = performance.now() - began;
            }
            // Most cursor moves and tab switches have no adjacent bracket.
            // Do not enqueue a second render when the visible marks are unchanged.
            if (previous !== JSON.stringify(positions)) session._signal('changeFrontMarker');
        }
        function schedule() {
            dirty = true;
            if (!destroyed && frame === null) frame = requestAnimationFrame(function () { frame = null; refresh(); });
        }
        function bindSession() {
            if (session && marker) session.removeMarker(marker.id);
            session = editor.session; positions = []; dirty = true;
            marker = session.addDynamicMarker({update: function (html, layer, current, config) {
                if (destroyed || current !== session) return;
                var color = editor.container.classList.contains('ace_dark') ? '#76ff02' : '#ff0000';
                positions.forEach(function (position) {
                    if (position.row < config.firstRow || position.row > config.lastRow) return;
                    var screen = current.documentToScreenPosition(position.row, position.column);
                    var back = current.screenToDocumentPosition(screen.row, screen.column);
                    // Hidden folded text maps to the placeholder, not its own glyph.
                    if (back.row !== position.row || back.column !== position.column) return;
                    var left = layer.$padding + screen.column * config.characterWidth;
                    if (current.$bidiHandler.isBidiRow(screen.row)) {
                        var selection = current.$bidiHandler.getSelections(screen.column, screen.column + 1)[0];
                        if (selection) left = layer.$padding + selection.left;
                    }
                    layer.elt('ace_matching-bracket-character', 'left:' + left + 'px;top:' +
                        layer.$getTop(screen.row, config) + 'px;width:' + config.characterWidth +
                        'px;height:' + config.lineHeight + 'px;line-height:' + config.lineHeight + 'px;color:' + color + ';');
                    // Ace reuses marker divs. A data attribute keeps other marker
                    // classes clean without adding text nodes to their divs.
                    var element = layer.i < 0 ? layer.element.lastChild : layer.element.childNodes[layer.i - 1];
                    element.setAttribute('data-bracket', position.character);
                });
            }}, true);
            schedule();
        }
        function themeChanged() { if (session) session._signal('changeFrontMarker'); }
        function destroy() {
            if (destroyed) return;
            destroyed = true;
            if (frame !== null) cancelAnimationFrame(frame);
            editor.off('change', schedule); editor.off('changeSelection', schedule);
            editor.off('changeSession', bindSession); editor.off('destroy', destroy);
            editor.renderer.off('changeTheme', themeChanged);
            if (session && marker) session.removeMarker(marker.id);
            positions = []; frame = null; marker = null;
            delete editor.$simpleBracketHighlighter;
        }
        var api = {init: function () { return api; }, refresh: refresh, destroy: destroy,
            status: function () { return {positions: positions.slice(), limited: limited, scanMs: scanMs,
                scans: scans, markerId: marker && marker.id, destroyed: destroyed}; }};
        editor.$simpleBracketHighlighter = api;
        editor.on('change', schedule); editor.on('changeSelection', schedule);
        editor.on('changeSession', bindSession); editor.on('destroy', destroy);
        editor.renderer.on('changeTheme', themeChanged);
        bindSession();
        return api;
    }
    root.installBracketHighlight = install;
    if (root.editor) root.SimpleBracketHighlighter = install(root.editor);
})(window);
