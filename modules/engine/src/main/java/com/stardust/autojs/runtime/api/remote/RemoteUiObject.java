package com.stardust.autojs.runtime.api.remote;

import android.graphics.Rect;
import android.util.Log;

import com.stardust.autojs.runtime.exception.ScriptException;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 远程节点：{@code :script} 进程里由主进程无障碍服务返回的节点快照（JSON 描述）的只读视图。
 *
 * <p>与 {@code com.stardust.automator.UiObject} 的关系：UiObject 要求真实
 * AccessibilityNodeInfo（:script 进程里没有 AccessibilityService，拿不到），所以这里用
 * {@link RemoteUiObject} 承载「节点 JSON + 来源选择器 + 匹配序号」：
 * <ul>
 *   <li>属性读取（text/desc/id/className/packageName/bounds/clickable/enabled/depth）直接读快照；</li>
 *   <li>动作（click/longClick/setText）通过 {@link RemoteAutomationBridge.Backend} 转给主进程，
 *       优先按「选择器 + 序号」调 clickNode/setTextNode（索引稳定），选择器含本地过滤条件时
 *       退化为 bounds 中心坐标点击；</li>
 *   <li>父子/兄弟遍历：批一不支持，调用时抛明确中文异常。</li>
 * </ul>
 *
 * <p>由 {@link RemoteUiSelector} 构造，脚本层只通过选择器链拿到本类实例。
 */
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
        return mNode.optString("text", "");
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
        try {
            String raw;
            JSONArray path = mNode.optJSONArray("path");
            if (path != null) {
                JSONObject selector = new JSONObject().put("path", path);
                for (String key : new String[]{"id", "className", "packageName"}) {
                    if (!mNode.optString(key, "").isEmpty()) selector.put(key, mNode.getString(key));
                }
                if (mNode.optString("id", "").isEmpty()) {
                    if (mNode.has("text")) selector.put("text", mNode.optString("text", ""));
                    if (mNode.has("desc")) selector.put("desc", mNode.optString("desc", ""));
                }
                raw = mBackend.getNodeInfo(selector.toString(), 0);
            } else if (mProxyableSelector) {
                raw = mBackend.getNodeInfo(mSelectorJson, mIndex);
            } else return false;
            return raw != null && !raw.isEmpty() && !"null".equals(raw) && new JSONObject(raw).length() > 0;
        } catch (Throwable error) { throw wrap("exists", error); }
    }

    // ---- 动作 ------------------------------------------------------------

    public boolean click() {
        try {
            if (mProxyableSelector) {
                // find→click 之间界面可能变化：主进程用「同 selector + index 重匹配」执行，
                // 失败重试一次；仍失败则用主进程扩展的 path selector 兜底（task-19 实现）。
                if (mBackend.clickNode(mSelectorJson, mIndex)) {
                    return true;
                }
                if (mBackend.clickNode(mSelectorJson, mIndex)) {
                    return true;
                }
                return clickNodeByPath();
            }
            if (clickNodeByPath()) {
                return true;
            }
            Rect rect = bounds();
            return mBackend.click(rect.centerX(), rect.centerY());
        } catch (Throwable error) {
            throw wrap("click", error);
        }
    }

    public boolean longClick() {
        try {
            if (mProxyableSelector) {
                if (mBackend.longClickNode(mSelectorJson, mIndex)) {
                    return true;
                }
                if (mBackend.longClickNode(mSelectorJson, mIndex)) {
                    return true;
                }
                return longClickNodeByPath();
            }
            if (longClickNodeByPath()) {
                return true;
            }
            Rect rect = bounds();
            return mBackend.longClick(rect.centerX(), rect.centerY());
        } catch (Throwable error) {
            throw wrap("longClick", error);
        }
    }

    public boolean setText(String text) {
        String value = text == null ? "" : text;
        try {
            if (mProxyableSelector) {
                if (mBackend.setTextNode(mSelectorJson, mIndex, value)) {
                    return true;
                }
                if (mBackend.setTextNode(mSelectorJson, mIndex, value)) {
                    return true;
                }
            }
            // 含本地过滤条件（或 selector+index 漂移）时用 path selector 兜底。
            String pathSelector = pathSelectorJson();
            if (pathSelector != null) {
                return mBackend.setTextNode(pathSelector, 0, value);
            }
            throw new ScriptException(
                    "隔离进程暂不支持「本地过滤条件 + setText」的组合，请改用可下发主进程的选择器条件");
        } catch (Throwable error) {
            throw wrap("setText", error);
        }
    }

    /** 主进程扩展：用 dump/findNodes 返回的 path 精确匹配节点后点击（task-19 支持）。 */
    private boolean clickNodeByPath() {
        String pathSelector = pathSelectorJson();
        return pathSelector != null && mBackend.clickNode(pathSelector, 0);
    }

    private boolean longClickNodeByPath() {
        String pathSelector = pathSelectorJson();
        return pathSelector != null && mBackend.longClickNode(pathSelector, 0);
    }

    /** {@code {"path":[...]}} 选择器；节点没有 path 时返回 null。 */
    private String pathSelectorJson() {
        JSONArray path = mNode.optJSONArray("path");
        if (path == null || path.length() == 0) {
            return null;
        }
        try {
            return new JSONObject().put("path", path).toString();
        } catch (Throwable error) {
            Log.w(TAG, "构造 path 选择器失败", error);
            return null;
        }
    }

    /** 点击节点中心（UiObject.clickCenter 的远程版本）。 */
    public boolean clickCenter() {
        Rect rect = bounds();
        try {
            return mBackend.click(rect.centerX(), rect.centerY());
        } catch (Throwable error) {
            throw wrap("clickCenter", error);
        }
    }

    /** 当前快照不含滚动动作，批一明确抛错而不是静默返回 false。 */
    public boolean scrollForward() {
        throw unsupported("scrollForward");
    }

    public boolean scrollBackward() {
        throw unsupported("scrollBackward");
    }

    // ---- 遍历（批一不支持） ----------------------------------------------

    public RemoteUiObject parent() {
        throw unsupported("parent");
    }

    public RemoteUiObject child(int index) {
        throw unsupported("child");
    }

    public int childCount() {
        throw unsupported("childCount");
    }

    public RemoteUiObject find(RemoteUiSelector selector) {
        throw unsupported("find");
    }

    public RemoteUiObject findOne(RemoteUiSelector selector) {
        throw unsupported("findOne");
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
