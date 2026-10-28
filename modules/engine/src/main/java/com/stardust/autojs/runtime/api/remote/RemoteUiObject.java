package com.stardust.autojs.runtime.api.remote;

import android.graphics.Rect;
import android.util.Log;

import com.stardust.autojs.runtime.exception.ScriptException;

import org.json.JSONArray;
import org.json.JSONObject;

/** Offline attributes and navigation for isolated scripts; guarded actions re-resolve in the host. */
public final class RemoteUiObject {

    private static final String TAG = "RemoteUiObject";

    private final RemoteAutomationBridge.Backend mBackend;
    /** 与主进程约定的选择器 JSON（只含契约支持的字段）。 */
    private final String mSelectorJson;
    /** 该节点在 findNodes 结果中的序号（契约 clickNode/setTextNode 的 index）。 */
    private final int mIndex;
    private final JSONObject mNode;
    /** true=选择器可直接下发给主进程（动作按 selector+index）；false=含本地过滤（动作走坐标）。 */
    private final boolean mProxyableSelector;

    RemoteUiObject(RemoteAutomationBridge.Backend backend, String selectorJson, int index,
                   JSONObject node, boolean proxyableSelector) {
        mBackend = backend;
        mSelectorJson = selectorJson == null ? "{}" : selectorJson;
        mIndex = index;
        mNode = node == null ? new JSONObject() : node;
        mProxyableSelector = proxyableSelector;
    }

    // ---- 节点属性（与 UiObject 同名，脚本直接调用） --------------------------

    public String text() {
        return password() ? "" : mNode.optString("text", "");
    }

    public String desc() {
        return mNode.optString("desc", "");
    }

    public String id() {
        return mNode.optString("id", "");
    }

    public String className() {
        return mNode.optString("className", "");
    }

    public String packageName() {
        return mNode.optString("packageName", "");
    }

    public boolean clickable() {
        return mNode.optBoolean("clickable", false);
    }

    public boolean enabled() {
        return mNode.optBoolean("enabled", false);
    }

    public boolean scrollable() {
        return mNode.optBoolean("scrollable", false);
    }

    public int depth() {
        return mNode.optInt("depth", -1);
    }

    public int indexInParent() {
        return mNode.optInt("indexInParent", -1);
    }

    /** 节点在屏幕上的边界；快照里没有时返回空 Rect（0,0,0,0）。 */
    public Rect bounds() {
        JSONArray array = mNode.optJSONArray("bounds");
        if (array == null || array.length() < 4) {
            return new Rect();
        }
        return new Rect(array.optInt(0), array.optInt(1), array.optInt(2), array.optInt(3));
    }

    /** 契约里的节点路径（从根窗口到该节点的子下标序列），JSON 字符串。 */
    public String path() {
        JSONArray array = mNode.optJSONArray("path");
        return array == null ? "[]" : array.toString();
    }

    /** Revalidate the node against the live window instead of treating a snapshot as live. */
    public boolean exists() {
        try { return live(false) != null; }
        catch (Throwable error) { throw wrap("exists", error); }
    }

    /** Match the original identity, never retry an action against a different path/old coordinates. */
    private JSONObject identity(boolean strictContent) throws Exception {
        JSONArray path = mNode.optJSONArray("path");
        if (path == null) return null;
        JSONObject selector = new JSONObject().put("path", new JSONArray(path.toString()));
        for (String key : new String[]{"id", "className", "packageName"})
            if (!mNode.optString(key, "").isEmpty()) selector.put(key, mNode.getString(key));
        if (strictContent || mNode.optString("id", "").isEmpty())
            for (String key : new String[]{"text", "desc"})
                if (mNode.has(key) && !(key.equals("text") && password())) selector.put(key, mNode.optString(key, ""));
        return selector;
    }
    private JSONObject live(boolean strict) throws Exception {
        JSONObject selector = identity(strict);
        if (selector == null && !mProxyableSelector) return null;
        String raw = mBackend.getNodeInfo(selector == null ? mSelectorJson : selector.toString(), selector == null ? mIndex : 0);
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return null;
        JSONObject value = new JSONObject(raw);
        return value.length() == 0 ? null : value;
    }
    boolean action(String name, Object... values) {
        try {
            JSONObject selector = identity(true);
            if (selector == null) return false;
            JSONArray args = new JSONArray(); for (Object value : values) args.put(value);
            return mBackend.nodeAction(selector.toString(), 0, name, args.toString());
        } catch (Throwable error) { throw wrap(name, error); }
    }
    public boolean click() {
        try { JSONObject selector = identity(true); return selector != null && mBackend.clickNode(selector.toString(), 0); }
        catch (Throwable error) { throw wrap("click", error); }
    }
    public boolean longClick() {
        try { JSONObject selector = identity(true); return selector != null && mBackend.longClickNode(selector.toString(), 0); }
        catch (Throwable error) { throw wrap("longClick", error); }
    }
    public boolean setText(String text) {
        try {
            JSONObject selector = identity(true); String value = text == null ? "" : text;
            if (selector == null || !mBackend.setTextNode(selector.toString(), 0, value)) return false;
            mNode.put("text", password() ? "" : value); return true;
        } catch (Throwable error) { throw wrap("setText", error); }
    }
    public boolean clickCenter() {
        try {
            JSONObject current = live(true); if (current == null) return false;
            JSONArray bounds = current.optJSONArray("bounds");
            if (bounds == null || bounds.length() != 4) return false;
            int left = bounds.getInt(0), top = bounds.getInt(1), right = bounds.getInt(2), bottom = bounds.getInt(3);
            if (left < 0 || top < 0 || right <= left || bottom <= top) return false;
            return mBackend.click((int)(((long)left + right) / 2), (int)(((long)top + bottom) / 2));
        } catch (Throwable error) { throw wrap("clickCenter", error); }
    }
    public boolean scrollForward() { return action("scrollForward"); }
    public boolean scrollBackward() { return action("scrollBackward"); }
    public boolean scrollUp() { return action("scrollUp"); }
    public boolean scrollDown() { return action("scrollDown"); }
    public boolean scrollLeft() { return action("scrollLeft"); }
    public boolean scrollRight() { return action("scrollRight"); }
    public boolean copy() { return action("copy"); }
    public boolean cut() { return action("cut"); }
    public boolean paste() { return action("paste"); }
    public boolean select() { return action("select"); }
    public boolean collapse() { return action("collapse"); }
    public boolean expand() { return action("expand"); }
    public boolean dismiss() { return action("dismiss"); }
    public boolean show() { return action("show"); }
    public boolean contextClick() { return action("contextClick"); }
    public boolean focus() { return action("focus"); }
    public boolean clearFocus() { return action("clearFocus"); }
    public boolean accessibilityFocus() { return action("accessibilityFocus"); }
    public boolean clearAccessibilityFocus() { return action("clearAccessibilityFocus"); }
    public boolean setSelection(int start, int end) {
        if (start < 0 || end < start) throw new IllegalArgumentException("setSelection 需要 0 <= start <= end");
        return action("setSelection", start, end);
    }
    public boolean setProgress(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("setProgress 需要有限数字");
        return action("setProgress", value);
    }
    public boolean scrollTo(int row, int column) {
        if (row < 0 || column < 0) throw new IllegalArgumentException("scrollTo 行列不能为负数");
        return action("scrollTo", row, column);
    }

    // Read-only scalar snapshot attributes; mutations always go through the live host.
    public boolean editable() { return mNode.optBoolean("editable", false); }
    public boolean checkable() { return mNode.optBoolean("checkable", false); }
    public boolean checked() { return mNode.optBoolean("checked", false); }
    public boolean focusable() { return mNode.optBoolean("focusable", false); }
    public boolean focused() { return mNode.optBoolean("focused", false); }
    public boolean selected() { return mNode.optBoolean("selected", false); }
    public boolean longClickable() { return mNode.optBoolean("longClickable", false); }
    public boolean visibleToUser() { return mNode.optBoolean("visibleToUser", false); }
    public boolean password() { return mNode.optBoolean("password", false); }
    public boolean accessibilityFocused() { return mNode.optBoolean("accessibilityFocused", false); }
    public boolean contextClickable() { return mNode.optBoolean("contextClickable", false); }
    public boolean contentInvalid() { return mNode.optBoolean("contentInvalid", false); }
    public boolean isHierarchically() { return mNode.optBoolean("isHierarchically", false); }
    public boolean multiLine() { return mNode.optBoolean("multiLine", false); }
    public boolean dismissable() { return mNode.optBoolean("dismissable", false); }
    public int drawingOrder() { return mNode.optInt("drawingOrder", 0); }
    public int row() { return mNode.optInt("row", -1); }
    public int column() { return mNode.optInt("column", -1); }
    public int rowCount() { return mNode.optInt("rowCount", 0); }
    public int columnCount() { return mNode.optInt("columnCount", 0); }
    public int rowSpan() { return mNode.optInt("rowSpan", 0); }
    public int columnSpan() { return mNode.optInt("columnSpan", 0); }
    public Rect boundsInParent() {
        JSONArray a = mNode.optJSONArray("boundsInParent");
        return a == null || a.length() < 4 ? new Rect() : new Rect(a.optInt(0), a.optInt(1), a.optInt(2), a.optInt(3));
    }
    private java.util.List<RemoteUiObject> relatives(int length, boolean direct) {
        java.util.List<RemoteUiObject> result = new java.util.ArrayList<>();
        try {
            if (live(true) == null) return result;
            JSONArray path = mNode.optJSONArray("path"); if (path == null) return result;
            JSONArray all = new JSONArray(mBackend.getLatestNodeDump());
            for (int i = 0; i < all.length(); i++) {
                JSONObject raw = all.optJSONObject(i); if (raw == null) continue;
                JSONArray other = raw.optJSONArray("path"); if (other == null) continue;
                if (direct && other.length() != length || !direct && other.length() <= path.length()) continue;
                int common = Math.min(path.length(), other.length()); boolean match = true;
                for (int j = 0; j < common; j++) if (path.getInt(j) != other.getInt(j)) { match = false; break; }
                if (match) result.add(new RemoteUiObject(mBackend, "{}", 0, raw, false));
            }
            return result;
        } catch (Throwable error) { throw wrap("遍历", error); }
    }
    public RemoteUiObject parent() {
        JSONArray path = mNode.optJSONArray("path");
        if (path == null || path.length() == 0) return null;
        java.util.List<RemoteUiObject> values = relatives(path.length()-1, true);
        return values.isEmpty() ? null : values.get(0);
    }
    public RemoteUiObjectCollection children() {
        JSONArray path = mNode.optJSONArray("path");
        return new RemoteUiObjectCollection(path == null ? java.util.Collections.emptyList() : relatives(path.length()+1, true));
    }
    public RemoteUiObject child(int index) {
        if (index < 0) throw new IllegalArgumentException("child.index 不能为负数");
        for (RemoteUiObject child : children()) if (child.indexInParent() == index) return child;
        return null;
    }
    public int childCount() { return mNode.has("childCount") ? mNode.optInt("childCount", 0) : children().size(); }
    public RemoteUiObjectCollection find(RemoteUiSelector selector) {
        if (selector == null) throw new IllegalArgumentException("find 需要选择器");
        return selector.findWithin(this);
    }
    public RemoteUiObject findOne(RemoteUiSelector selector) {
        RemoteUiObjectCollection result = find(selector); return result.empty() ? null : result.get(0);
    }
    public java.util.List<RemoteUiObject> findByText(String text) {
        java.util.List<RemoteUiObject> values = new java.util.ArrayList<>();
        for (RemoteUiObject value : find(new RemoteUiSelector().textContains(text))) values.add(value);
        return values;
    }
    public java.util.List<RemoteUiObject> findByViewId(String id) {
        java.util.List<RemoteUiObject> values = new java.util.ArrayList<>();
        for (RemoteUiObject value : find(new RemoteUiSelector().id(id))) values.add(value);
        return values;
    }
    boolean isDescendantOf(RemoteUiObject root) {
        JSONArray parent = root.mNode.optJSONArray("path"), path = mNode.optJSONArray("path");
        if (parent == null || path == null || path.length() <= parent.length()) return false;
        for (int i = 0; i < parent.length(); i++) if (parent.optInt(i, -1) != path.optInt(i, -2)) return false;
        return root.packageName().equals(packageName());
    }

    @Override
    public String toString() {
        return "RemoteUiObject{class=" + className() + ", text=" + text()
                + ", id=" + id() + ", bounds=" + bounds() + "}";
    }

    private ScriptException unsupported(String api) {
        return new ScriptException("隔离进程暂不支持 UiObject." + api + "()（批一未代理，可改用 selector 链）");
    }

    private ScriptException wrap(String api, Throwable error) {
        if (error instanceof ScriptException) {
            return (ScriptException) error;
        }
        Log.w(TAG, "remote node action failed: " + api, error);
        String message = error == null ? "未知错误" : error.getMessage();
        return new ScriptException("主进程自动化服务调用失败（UiObject." + api + "）：" + message, error);
    }
}
