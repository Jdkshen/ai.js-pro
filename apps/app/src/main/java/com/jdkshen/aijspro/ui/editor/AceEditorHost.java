package com.jdkshen.aijspro.ui.editor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import com.jdkshen.aijspro.BuildConfig;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** One attached Chromium view per activity; documents retain separate Ace sessions. */
final class AceEditorHost {
    private static final Map<Context, AceEditorHost> HOSTS = new WeakHashMap<>();
    static AceEditorHost obtain(Context context) {
        AceEditorHost host = HOSTS.get(context);
        if (host == null) { host = new AceEditorHost(context); HOSTS.put(context, host); }
        return host;
    }
    final Handler handler = new Handler(Looper.getMainLooper());
    final FrameLayout content;
    final ConcurrentHashMap<String, AceCodeEditor> documents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, File> roots = new ConcurrentHashMap<>();
    private final Context context;
    volatile AceCodeEditor current;
    WebView view;
    boolean ready, destroyed;

    private AceEditorHost(Context context) { this.context = context; content = new FrameLayout(context); }
    @SuppressLint("SetJavaScriptEnabled") private void ensure() {
        if (view != null || destroyed) return;
        view = new WebView(context);
        view.setBackgroundColor(Color.TRANSPARENT);
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setSupportZoom(false);
        view.getSettings().setBuiltInZoomControls(false);
        view.getSettings().setDisplayZoomControls(false);
        view.getSettings().setUseWideViewPort(false);
        view.getSettings().setTextZoom(100);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        view.addJavascriptInterface(new Bridge(), "EditorBridge");
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"editor.aijspro.local".equals(uri.getHost())) return missing();
                String path = uri.getPath();
                if (path == null) return missing();
                try {
                    if ("/document".equals(path)) {
                        String id = uri.getQueryParameter("session");
                        AceCodeEditor owner = id == null ? null : documents.get(id);
                        return owner == null ? missing() : new WebResourceResponse("text/plain", "UTF-8",
                                new ByteArrayInputStream(owner.getText().getBytes(StandardCharsets.UTF_8)));
                    }
                    if (path.startsWith("/workspace/")) {
                        String key = uri.getQueryParameter("workspace");
                        File root = key == null ? (current == null ? null : current.workspaceRoot()) : roots.get(key);
                        if (root == null) return missing();
                        File file = new File(root, path.substring(11)).getCanonicalFile();
                        if (!file.getPath().startsWith(root.getCanonicalPath() + File.separator)
                                || file.length() > 2 * 1024 * 1024 || !file.getName().matches(".*\\.(?:js|ts|json)$")) return missing();
                        String unsaved = AceCodeEditor.openDocumentText(file.getPath());
                        if (unsaved != null) return new WebResourceResponse("text/plain", "UTF-8",
                                new ByteArrayInputStream(unsaved.getBytes(StandardCharsets.UTF_8)));
                        return file.isFile() ? new WebResourceResponse("text/plain", "UTF-8", new FileInputStream(file)) : missing();
                    }
                    if (path.contains("..")) return missing();
                    String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css"
                            : path.endsWith(".json") ? "application/json" : "application/javascript";
                    return new WebResourceResponse(mime, "UTF-8", context.getAssets().open("editor/ace" + path));
                } catch (IOException error) { return missing(); }
            }
        });
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.loadUrl("https://editor.aijspro.local/editor.html");
    }
    private static WebResourceResponse missing() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
    }
    void select(AceCodeEditor owner) {
        ensure();
        if (current != owner) {
            // Finish the old document's composing span before changing the shared
            // textarea. Keep its input connection attached to retain the IME.
            if (current != null) {
                android.view.inputmethod.InputConnection input = view.onCreateInputConnection(
                        new android.view.inputmethod.EditorInfo());
                if (input != null) input.finishComposingText();
                current.setVisibleView(null);
            }
            current = owner;
        }
        owner.setVisibleView(view);
        prepare(owner, true);
    }
    void prepare(AceCodeEditor owner, boolean select) {
        ensure(); owner.loading = true;
        if (!ready) return;
        String key = owner.workspaceKey();
        if (owner.workspaceRoot() != null) roots.put(key, owner.workspaceRoot());
        view.evaluateJavascript("port.openSession(" + JSONObject.quote(owner.sessionId) + ","
                + JSONObject.quote(owner.documentPath()) + "," + owner.initialFontSize() + ","
                + JSONObject.quote(owner.themeName()) + "," + JSONObject.quote(key) + "," + select + ")", null);
    }
    void remove(AceCodeEditor owner) {
        documents.remove(owner.sessionId);
        if (current == owner) { current = null; owner.setVisibleView(null); }
        if (ready && view != null) view.evaluateJavascript("port.closeSession(" + JSONObject.quote(owner.sessionId) + ")", null);
        if (!documents.isEmpty()) return;
        destroyed = true; HOSTS.remove(context); handler.removeCallbacksAndMessages(null);
        if (view != null) {
            view.removeJavascriptInterface("EditorBridge"); content.removeView(view); view.stopLoading(); view.destroy(); view = null;
        }
    }
    private void showKeyboard(String id) {
        AceCodeEditor owner = current;
        if (destroyed || view == null || owner == null || !owner.sessionId.equals(id) || !owner.isActive()
                || !view.hasWindowFocus()) return;
        view.requestFocus();
        InputMethodManager keyboard = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        keyboard.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }
    private final class Bridge {
        @JavascriptInterface public void ready() {
            handler.post(() -> {
                if (destroyed) return;
                ready = true;
                for (AceCodeEditor owner : new ArrayList<>(documents.values()))
                    if (owner.loading || owner == current) prepare(owner, owner == current);
            });
        }
        @JavascriptInterface public void prepared(String id) {
            handler.post(() -> { AceCodeEditor owner = documents.get(id); if (owner != null) owner.sessionPrepared(); });
        }
        @JavascriptInterface public void update(String source) {
            try {
                JSONObject value = new JSONObject(source);
                AceCodeEditor owner = documents.get(value.optString("id"));
                if (owner != null) owner.acceptSnapshot(value);
            } catch (JSONException ignored) { }
        }
        @JavascriptInterface public void breakpoint(String id, int row, boolean enabled) {
            handler.post(() -> { AceCodeEditor owner = documents.get(id); if (owner != null) owner.breakpointChanged(row, enabled); });
        }
        /**
         * Clipboard operations stay synchronous on the bridge thread: the editor
         * reads its current selection and writes it before any focus transition.
         */
        @JavascriptInterface public String copyToClipboard(String text) {
            return EditorClipboard.write(context, text);
        }
        @JavascriptInterface public String readClipboard() {
            return EditorClipboard.readJson(context);
        }
        @JavascriptInterface public void showMessage(String message) {
            handler.post(() -> {
                if (!destroyed && message != null)
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            });
        }
        @JavascriptInterface public void startActionMode(float left, float top, float right,
                                                          float bottom, boolean selected,
                                                          boolean preserve) {
            // Cursor-only action modes would open on every tap. The native menu is
            // only useful while a real selection exists.
            if (!selected) return;
            AceCodeEditor owner = current;
            handler.post(() -> {
                if (destroyed || owner == null || owner != current || !owner.isActive()) return;
                if (context instanceof ProCodeEditorActivity)
                    ((ProCodeEditorActivity) context).showEditorSelectionActionMode(owner);
            });
        }
        @JavascriptInterface public void finishActionMode() {
            handler.post(() -> {
                if (!destroyed && context instanceof ProCodeEditorActivity)
                    ((ProCodeEditorActivity) context).finishEditorSelectionActionMode();
            });
        }
        @JavascriptInterface public void showKeyboard(String id) {
            // Ace focuses its textarea asynchronously. Keep the attached input connection
            // and explicitly reopen an IME that the user has dismissed before this tap.
            handler.postDelayed(() -> AceEditorHost.this.showKeyboard(id), 40);
            handler.postDelayed(() -> AceEditorHost.this.showKeyboard(id), 140);
        }
        @JavascriptInterface public void error(String message) {
            handler.post(() -> { if (!destroyed) Toast.makeText(context, message, Toast.LENGTH_LONG).show(); });
        }
    }
}
