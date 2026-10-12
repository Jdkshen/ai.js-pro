package com.jdkshen.aijspro.ui.editor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.jdkshen.aijspro.ui.edit.editor.CodeEditor;
import com.jdkshen.aijspro.ui.edit.theme.Theme;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Native workspace facade over Ace. File writes and script execution stay native. */
public final class AceCodeEditor extends FrameLayout {
    private static final String ORIGIN = "https://editor.aijspro.local/";
    private static final ConcurrentHashMap<String, AceCodeEditor> OPEN_DOCUMENTS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong NEXT_SESSION = new java.util.concurrent.atomic.AtomicLong();
    final String sessionId = Long.toString(NEXT_SESSION.incrementAndGet());
    private final AceEditorHost host;
    boolean loading;
    private float configuredFontSize = 17;
    private WebView webView;
    private final List<Runnable> pending = new ArrayList<>();
    private volatile State state = new State();
    private long textSequence;
    private volatile File workspaceRoot;
    private String canonicalDocumentPath;
    private String documentPath = "script.js";
    private String savedText = "";
    private boolean ready, destroyed, undoEnabled = true;
    private boolean active;
    private String themeName = "tomorrow_night";
    private Runnable changeListener;
    private CodeEditor.BreakpointChangeListener breakpointListener;
    private String replacement = "";

    private static final class State {
        long sequence;
        String text = "";
        int start, end, row, scrollX, scrollY;
        float fontSize = 17;
        boolean canUndo, canRedo;
        final LinkedHashMap<Integer, CodeEditor.Breakpoint> breakpoints = new LinkedHashMap<>();
    }

    @SuppressLint("SetJavaScriptEnabled")
    public AceCodeEditor(Context context) {
        super(context);
        host = AceEditorHost.obtain(context);
        host.documents.put(sessionId, this);
    }

    public android.view.View getEditorView() { return host.content; }
    private void ensureWebView() { if (!destroyed) host.select(this); }
    void setVisibleView(WebView view) { webView = view; }
    File workspaceRoot() { return workspaceRoot; }
    String documentPath() { return documentPath; }
    String themeName() { return themeName; }
    float initialFontSize() { return configuredFontSize; }
    boolean isActive() { return active; }
    String workspaceKey() {
        try { return workspaceRoot == null ? "" : workspaceRoot.getCanonicalPath(); }
        catch (IOException error) { return ""; }
    }
    static String openDocumentText(String path) {
        AceCodeEditor owner = OPEN_DOCUMENTS.get(path);
        return owner == null ? null : owner.getText();
    }
    void sessionPrepared() {
        if (destroyed) return;
        ready = true; loading = false;
        List<Runnable> queue = new ArrayList<>(pending); pending.clear();
        for (Runnable action : queue) action.run();
    }
    void breakpointChanged(int row, boolean enabled) {
        if (!destroyed && breakpointListener != null) breakpointListener.onBreakpointChange(row, enabled);
    }

    synchronized void acceptSnapshot(JSONObject json) {
        long sequence = json.optLong("sequence");
        boolean hasText = json.has("text") || json.has("edit");
        if (sequence <= state.sequence && (!hasText || sequence <= textSequence)) return;
        State previous = state;
        State next = new State();
        next.sequence = Math.max(sequence, previous.sequence);
        next.text = sequence > textSequence ? json.optString("text", previous.text) : previous.text;
        JSONObject edit = sequence > textSequence ? json.optJSONObject("edit") : null;
        if (edit != null) {
            int offset = edit.optInt("offset", -1), removed = edit.optInt("removed", -1);
            if (offset < 0 || removed < 0 || offset > previous.text.length()
                    || removed > previous.text.length() - offset) return;
            next.text = previous.text.substring(0, offset) + edit.optString("inserted")
                    + previous.text.substring(offset + removed);
        }
        if (hasText && sequence > textSequence) textSequence = sequence;
        boolean latest = sequence > previous.sequence;
        next.start = latest ? json.optInt("start") : previous.start;
        next.end = latest ? json.optInt("end") : previous.end;
        next.row = latest ? json.optInt("row") : previous.row;
        next.scrollX = latest ? json.optInt("scrollX") : previous.scrollX;
        next.scrollY = latest ? json.optInt("scrollY") : previous.scrollY;
        next.fontSize = latest ? (float) json.optDouble("fontSize", previous.fontSize) : previous.fontSize;
        next.canUndo = latest ? json.optBoolean("canUndo") : previous.canUndo;
        next.canRedo = latest ? json.optBoolean("canRedo") : previous.canRedo;
        JSONArray points = latest ? json.optJSONArray("breakpoints") : null;
        if (!latest) next.breakpoints.putAll(previous.breakpoints);
        if (points != null) for (int i = 0; i < points.length(); i++) {
            int row = points.optInt(i); next.breakpoints.put(row, new CodeEditor.Breakpoint(row));
        }
        state = next;
        if (!next.text.equals(previous.text) || next.canUndo != previous.canUndo || next.canRedo != previous.canRedo)
            host.handler.post(() -> { if (!destroyed && changeListener != null) changeListener.run(); });
    }

    private void whenReady(Runnable action) {
        if (destroyed) return;
        if (ready) action.run(); else pending.add(action);
    }

    private void js(String expression) {
        whenReady(() -> host.view.evaluateJavascript("port.withSession(" + JSONObject.quote(sessionId) + ",()=>{try{" + expression
                + ";}catch(error){EditorBridge.error('编辑操作失败：'+String(error));}return port.snapshot(false);})", result -> {
            if (destroyed) return;
            try { acceptSnapshot(new JSONObject(result)); } catch (JSONException ignored) { }
        }));
    }

    /** A JS evaluation barrier is required before save, run, close and save-as. */
    public void withSnapshot(Runnable action) {
        if (destroyed) return;
        // A snapshot of a hidden session never changes the visible tab or its IME.
        if (!ready) host.prepare(this, active);
        final boolean[] completed = {false};
        Runnable timeout = () -> {
            if (!destroyed && !completed[0]) {
                completed[0] = true;
                Toast.makeText(getContext(), "编辑器暂时无响应，请重试保存", Toast.LENGTH_LONG).show();
            }
        };
        host.handler.postDelayed(timeout, 12000);
        whenReady(() -> host.view.evaluateJavascript("port.snapshotSession(" + JSONObject.quote(sessionId) + ")", result -> {
            if (destroyed || completed[0]) return;
            completed[0] = true; host.handler.removeCallbacks(timeout);
            try { acceptSnapshot(new JSONObject(result)); action.run(); }
            catch (JSONException error) {
                Toast.makeText(getContext(), "无法读取编辑器内容，请重试", Toast.LENGTH_LONG).show();
            }
        }));
    }

    public void setDocument(File file, File root) {
        workspaceRoot = root;
        try {
            String base = root == null ? "" : root.getCanonicalPath() + File.separator;
            String path = file.getCanonicalPath();
            if (canonicalDocumentPath != null) OPEN_DOCUMENTS.remove(canonicalDocumentPath, this);
            canonicalDocumentPath = path;
            OPEN_DOCUMENTS.put(path, this);
            documentPath = path.startsWith(base) ? path.substring(base.length()).replace('\\', '/') : file.getName();
        } catch (IOException ignored) { documentPath = file.getName(); }
        js("port.setDocument(" + JSONObject.quote(documentPath) + ")");
    }
    public void setOnChangeListener(Runnable listener) { changeListener = listener; }
    public void setActive(boolean value) {
        if (active == value || destroyed) return;
        active = value;
        if (active) ensureWebView();
        else if (host.current == this && host.ready)
            host.view.evaluateJavascript("port.setActive(false)", null);
    }

    public String getText() { return state.text; }
    public int getSelectionStart() { return state.start; }
    public int getSelectionEnd() { return state.end; }
    public int getViewportScrollX() { return state.scrollX; }
    public int getViewportScrollY() { return state.scrollY; }
    public float getTextSizeSp() { return state.fontSize; }
    public void setTextSizeSp(float size) { configuredFontSize = size; js("port.setFontSize(" + size + ")"); }
    public void restoreViewState(int cursor, int x, int y) { js("port.restore(" + cursor + "," + x + "," + y + ")"); }
    public void setInitialText(String text) {
        State next = new State(); next.sequence = state.sequence; next.text = text; state = next; savedText = text;
        // The first document is fetched from /document after the page loads. Avoid compiling
        // a megabyte-sized JS literal on the Android UI thread for every restored file.
        if (ready) js("port.initialText(" + JSONObject.quote(text) + ",false)");
    }
    public void setText(String text) { js("port.setText(" + JSONObject.quote(text) + ")"); }
    public boolean canUndo() { return undoEnabled && state.canUndo; }
    public boolean canRedo() { return undoEnabled && state.canRedo; }
    public void undo() { if (undoEnabled) js("editor.undo()"); }
    public void redo() { if (undoEnabled) js("editor.redo()"); }
    public boolean isTextChanged() { return !savedText.equals(state.text); }
    public void markTextAsSaved() { savedText = state.text; }
    public void markTextAsSaved(String savedSource) { savedText = savedSource; }
    public void setRedoUndoEnabled(boolean enabled) { undoEnabled = enabled; }
    public void setTheme(Theme theme) {
        int bg = theme.getBackgroundColor();
        boolean dark = Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114 < 128000;
        themeName = dark ? "tomorrow_night" : "textmate";
        js("editor.setTheme('ace/theme/" + themeName + "')");
    }
    public void insert(String text) { js("editor.insert(" + JSONObject.quote(text) + ")"); }
    public void insert(int line, String text) { js("editor.session.insert({row:" + line + ",column:0}," + JSONObject.quote(text) + ")"); }
    public void jumpTo(int line, int column) { js("editor.gotoLine(" + (line + 1) + "," + column + ",true)"); }
    public void jumpToStart() { js("editor.navigateFileStart()"); }
    public void jumpToEnd() { js("editor.navigateFileEnd()"); }
    public void jumpToLineStart() { js("editor.navigateLineStart()"); }
    public void jumpToLineEnd() { js("editor.navigateLineEnd()"); }
    public void moveCursorHorizontal(int delta) { js("editor.navigate" + (delta < 0 ? "Left" : "Right") + "()"); }
    public void moveCursorVertical(int delta) { js("editor.navigate" + (delta < 0 ? "Up" : "Down") + "()"); }
    public void moveCurrentLine(int direction) { js("editor.moveLines" + (direction < 0 ? "Up" : "Down") + "()"); }
    public void copyLine() { js("EditorBridge.copied(editor.session.getLine(editor.getCursorPosition().row))"); }
    public void deleteLine() { js("editor.removeLines()"); }
    public void deleteSelectionOrCharacter() { js("editor.remove('right')"); }
    public void toggleFoldAtSelection() { js("port.toggleFold()"); }
    public void foldAllTopLevel() { js("editor.session.foldAll()"); }
    public void unfoldAll() { js("editor.session.unfold()"); }
    public void beautifyCode() { js("port.beautify()"); }
    private void validatePattern(String query, boolean regex) throws CodeEditor.CheckedPatternSyntaxException {
        if (regex) try { Pattern.compile(query); }
        catch (PatternSyntaxException error) { throw new CodeEditor.CheckedPatternSyntaxException(error); }
    }
    public void find(String query, boolean regex) throws CodeEditor.CheckedPatternSyntaxException {
        validatePattern(query, regex);
        js("editor.find(" + JSONObject.quote(query) + ",{regExp:" + regex + ",wrap:true})");
    }
    public void findPrev() { js("editor.findPrevious()"); }
    public void replace(String query, String value, boolean regex) throws CodeEditor.CheckedPatternSyntaxException {
        replacement = value; find(query, regex);
    }
    public void replaceSelection() { js("editor.replace(" + JSONObject.quote(replacement) + ")"); }
    public void replaceAll(String query, String value, boolean regex) throws CodeEditor.CheckedPatternSyntaxException {
        validatePattern(query, regex);
        js("editor.replaceAll(" + JSONObject.quote(value) + ",{needle:" + JSONObject.quote(query) + ",regExp:" + regex + "})");
    }
    public LinkedHashMap<Integer, CodeEditor.Breakpoint> getBreakpoints() { return new LinkedHashMap<>(state.breakpoints); }
    public void setBreakpointChangeListener(CodeEditor.BreakpointChangeListener listener) { breakpointListener = listener; }
    public void addOrRemoveBreakpointAtCurrentLine() { js("port.toggleBreakpoint(editor.getCursorPosition().row)"); }
    public void removeAllBreakpoints() {
        int count = state.breakpoints.size(); js("editor.session.clearBreakpoints()");
        if (breakpointListener != null) breakpointListener.onAllBreakpointRemoved(count);
    }
    public void setDebuggingLine(int line) { js("port.debugLine(" + line + ")"); }
    public void destroy() {
        if (destroyed) return;
        destroyed = true; pending.clear(); changeListener = null; breakpointListener = null;
        if (canonicalDocumentPath != null) OPEN_DOCUMENTS.remove(canonicalDocumentPath, this);
        host.remove(this); webView = null;
    }
}
