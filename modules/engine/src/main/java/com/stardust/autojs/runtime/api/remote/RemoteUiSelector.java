package com.stardust.autojs.runtime.api.remote;

import android.os.SystemClock;
import android.util.Log;

import com.stardust.autojs.runtime.exception.ScriptException;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.regexp.NativeRegExp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * :script 进程里的远程选择器（Rhino 选择器全局函数/链式查询的载体）。
 *
 * <p>为什么需要它：Rhino 的 {@code __selector__.js} 把 {@code __runtime__.selector()} 返回对象的
 * 所有方法安装成全局函数（text/id/desc/.../findOne/...）。{@code :script} 进程没有
 * AccessibilityService，无法用本地 {@code UiSelector}（会得到空结果），因此
 * {@code ScriptRuntime.selector()} 在远程模式下返回本类实例：
 * <ul>
 *   <li>筛选条件通过 {@link RemoteAutomationBridge.Backend#findNodes(String)}（主进程用 UiSelector
 *       匹配）或 {@link RemoteAutomationBridge.Backend#getLatestNodeDump()}（本地过滤）完成；</li>
 *   <li>find/findOne/untilFind/exists 返回 {@link RemoteUiObject} / {@code List<RemoteUiObject>}；</li>
 *   <li>click()/longClick()/setText() 直接对第一个匹配节点执行（动作经主进程）。</li>
 * </ul>
 *
 * <p>覆盖范围（批一）：id/text/desc/className/packageName 的 equals/contains/startsWith/
 * endsWith/matches、bounds/boundsInside/boundsContains、clickable/enabled/scrollable/depth，
 * 以及 find/findOne/findOnce/untilFind/exists/waitFor/click/longClick/setText/scroll*。
 * 其它 UiSelector 条件（checked/focused/row/column/filter 等）快照里没有对应字段，调用时抛明确
 * 中文异常，不静默返回错误结果。
 */
public final class RemoteUiSelector {

    private static final String TAG = "RemoteUiSelector";
    private static final long POLL_INTERVAL_MILLIS = 50L;

    /** 可下发给主进程的契约选择器字段（IAutomationHost 文档 §3）。 */
    private final JSONObject mRemote = new JSONObject();
    /** 契约不支持的本地过滤条件（在 :script 侧对快照结果再过滤）。 */
    private final List<LocalFilter> mLocalFilters = new ArrayList<>();

    /** 本地过滤条件：对节点 JSON 做断言。 */
    private interface LocalFilter {
        boolean matches(JSONObject node);
    }

    public RemoteUiSelector() {
    }

    // ---- 第一类筛选（契约字段，可下发主进程） ----------------------------

    public RemoteUiSelector id(String value) {
        return putString("id", value);
    }

    public RemoteUiSelector text(String value) {
        return putString("text", value);
    }

    public RemoteUiSelector textContains(String value) {
        return putString("textContains", value);
    }

    public RemoteUiSelector textStartsWith(String value) {
        return putString("textStartsWith", value);
    }

    public RemoteUiSelector textMatches(String regex) {
        return putString("textMatches", regex);
    }

    public RemoteUiSelector textMatches(Object regex) {
        return textMatches(regexSource(regex));
    }

    public RemoteUiSelector desc(String value) {
        return putString("desc", value);
    }

    public RemoteUiSelector descContains(String value) {
        return putString("descContains", value);
    }

    public RemoteUiSelector descMatches(Object regex) {
        return descMatches(regexSource(regex));
    }

    public RemoteUiSelector className(String value) {
        return putString("className", value);
    }

    public RemoteUiSelector packageName(String value) {
        return putString("packageName", value);
    }

    public RemoteUiSelector clickable(boolean value) {
        return putBoolean("clickable", value);
    }

    public RemoteUiSelector enabled(boolean value) {
        return putBoolean("enabled", value);
    }

    public RemoteUiSelector scrollable(boolean value) {
        return putBoolean("scrollable", value);
    }

    public RemoteUiSelector depth(int value) {
        return putInt("depth", value);
    }

    public RemoteUiSelector bounds(int left, int top, int right, int bottom) {
        try {
            mRemote.put("bounds", new JSONObject()
                    .put("left", left).put("top", top)
                    .put("right", right).put("bottom", bottom));
        } catch (Throwable error) {
            throw new ScriptException("构造 bounds 条件失败：" + error.getMessage(), error);
        }
        return this;
    }

    // ---- 本地过滤条件（快照里有的字段，:script 侧过滤） -------------------

    public RemoteUiSelector idContains(final String value) {
        return localString("id", value, MatchMode.CONTAINS);
    }

    public RemoteUiSelector idStartsWith(final String value) {
        return localString("id", value, MatchMode.STARTS_WITH);
    }

    public RemoteUiSelector idEndsWith(final String value) {
        return localString("id", value, MatchMode.ENDS_WITH);
    }

    public RemoteUiSelector idMatches(String regex) {
        return localString("id", regex, MatchMode.MATCHES);
    }

    public RemoteUiSelector idMatches(Object regex) {
        return idMatches(regexSource(regex));
    }

    public RemoteUiSelector textEndsWith(final String value) {
        return localString("text", value, MatchMode.ENDS_WITH);
    }

    public RemoteUiSelector descStartsWith(final String value) {
        return localString("desc", value, MatchMode.STARTS_WITH);
    }

    public RemoteUiSelector descEndsWith(final String value) {
        return localString("desc", value, MatchMode.ENDS_WITH);
    }

    public RemoteUiSelector descMatches(String regex) {
        return localString("desc", regex, MatchMode.MATCHES);
    }

    public RemoteUiSelector classNameContains(final String value) {
        return localString("className", value, MatchMode.CONTAINS);
    }

    public RemoteUiSelector classNameStartsWith(final String value) {
        return localString("className", value, MatchMode.STARTS_WITH);
    }

    public RemoteUiSelector classNameEndsWith(final String value) {
        return localString("className", value, MatchMode.ENDS_WITH);
    }

    public RemoteUiSelector classNameMatches(String regex) {
        return localString("className", regex, MatchMode.MATCHES);
    }

    public RemoteUiSelector classNameMatches(Object regex) {
        return classNameMatches(regexSource(regex));
    }

    public RemoteUiSelector packageNameContains(final String value) {
        return localString("packageName", value, MatchMode.CONTAINS);
    }

    public RemoteUiSelector packageNameStartsWith(final String value) {
        return localString("packageName", value, MatchMode.STARTS_WITH);
    }

    public RemoteUiSelector packageNameEndsWith(final String value) {
        return localString("packageName", value, MatchMode.ENDS_WITH);
    }

    public RemoteUiSelector packageNameMatches(String regex) {
        return localString("packageName", regex, MatchMode.MATCHES);
    }

    public RemoteUiSelector packageNameMatches(Object regex) {
        return packageNameMatches(regexSource(regex));
    }

    private static String regexSource(Object regex) {
        if (regex instanceof org.mozilla.javascript.Wrapper) {
            regex = ((org.mozilla.javascript.Wrapper) regex).unwrap();
        }
        if (regex instanceof NativeRegExp) {
            NativeRegExp value = (NativeRegExp) regex;
            Object flagValue = value.get("flags", value);
            String display = value.toString();
            String flags = flagValue instanceof CharSequence ? flagValue.toString()
                    : display.substring(display.lastIndexOf('/') + 1);
            String modifiers = (flags.contains("i") ? "iu" : "")
                    + (flags.contains("m") ? "m" : "")
                    + (flags.contains("s") ? "s" : "");
            return (modifiers.isEmpty() ? "" : "(?" + modifiers + ")")
                    + value.get("source", value);
        }
        if (regex instanceof Pattern) {
            Pattern value = (Pattern) regex;
            String modifiers = ((value.flags() & Pattern.CASE_INSENSITIVE) != 0 ? "i" : "")
                    + ((value.flags() & Pattern.UNICODE_CASE) != 0 ? "u" : "")
                    + ((value.flags() & Pattern.MULTILINE) != 0 ? "m" : "")
                    + ((value.flags() & Pattern.DOTALL) != 0 ? "s" : "")
                    + ((value.flags() & Pattern.COMMENTS) != 0 ? "x" : "")
                    + ((value.flags() & Pattern.UNIX_LINES) != 0 ? "d" : "")
                    + ((value.flags() & Pattern.UNICODE_CHARACTER_CLASS) != 0 ? "U" : "");
            String source = (value.flags() & Pattern.LITERAL) != 0
                    ? Pattern.quote(value.pattern()) : value.pattern();
            if ((value.flags() & Pattern.CANON_EQ) != 0) {
                throw new IllegalArgumentException("远程正则暂不支持 Pattern.CANON_EQ");
            }
            return (modifiers.isEmpty() ? "" : "(?" + modifiers + ")") + source;
        }
        return String.valueOf(regex);
    }

    /** 选择器算法（BFS/DFS）：快照由主进程按遍历顺序返回，这里只做兼容不改变结果。 */
    public RemoteUiSelector algorithm(String algorithm) {
        return this;
    }

    // ---- 查询 ------------------------------------------------------------

    /** task-159：auto.root/rootInActiveWindow 的远程只读根（来自主进程当前窗口 dump）。 */
    public RemoteUiObject root() {
        RemoteUiObjectCollection roots = windowRoots();
        return roots.size() > 0 ? roots.get(0) : null;
    }

    /** task-159：当前窗口根集合（主进程 dump 只有活动窗口；多窗口暂不可对齐）。 */
    public RemoteUiObjectCollection windowRoots() {
        RemoteAutomationBridge.ensureAccessibility();
        RemoteAutomationBridge.Backend backend = RemoteAutomationBridge.requireBackend();
        List<RemoteUiObject> roots = new ArrayList<>();
        try {
            JSONArray nodes = new JSONArray(backend.getLatestNodeDump());
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.optJSONObject(i);
                if (node == null) { continue; }
                JSONArray path = node.optJSONArray("path");
                if (path != null && path.length() == 0) {
                    roots.add(new RemoteUiObject(backend, "{\"path\":[]}", 0, node, true));
                }
            }
        } catch (ScriptException error) {
            throw error;
        } catch (Throwable error) {
            throw new ScriptException("远程节点根查询失败：" + messageOf(error), error);
        }
        return new RemoteUiObjectCollection(roots);
    }

    public RemoteUiObjectCollection find() {
        return find(Integer.MAX_VALUE);
    }

    public RemoteUiObjectCollection find(int max) {
        RemoteAutomationBridge.ensureAccessibility();
        RemoteAutomationBridge.Backend backend = RemoteAutomationBridge.requireBackend();
        List<RemoteUiObject> result = new ArrayList<>();
        String selectorJson = mRemote.toString();
        boolean proxyable = mLocalFilters.isEmpty();
        try {
            JSONArray nodes;
            if (proxyable) {
                // 契约支持的条件全部下发主进程，让主进程用真正的 UiSelector 语义匹配。
                nodes = new JSONArray(backend.findNodes(selectorJson));
            } else {
                // 含本地过滤条件：先取全量快照，:script 侧过滤（避免下发后索引对不上）。
                nodes = new JSONArray(backend.getLatestNodeDump());
            }
            for (int i = 0; i < nodes.length() && result.size() < max; i++) {
                JSONObject node = nodes.optJSONObject(i);
                if (node == null || !matchesLocal(node)) {
                    continue;
                }
                result.add(new RemoteUiObject(backend, selectorJson, result.size(), node, proxyable));
            }
        } catch (ScriptException error) {
            throw error;
        } catch (Throwable error) {
            throw new ScriptException("远程选择器查询失败：" + messageOf(error), error);
        }
        return new RemoteUiObjectCollection(result);
    }

    public RemoteUiObject findOnce() {
        return findOnce(0);
    }

    public RemoteUiObject findOnce(int index) {
        RemoteUiObjectCollection nodes = find(index + 1);
        return index >= 0 && index < nodes.size() ? nodes.get(index) : null;
    }

    /** 与本地 UiSelector.findOne() 一致：阻塞直到找到（脚本被停止时抛 ScriptInterruptedException）。 */
    public RemoteUiObject findOne() {
        return untilFindOne();
    }

    public RemoteUiObject findOne(long timeout) {
        return findOne(timeout, 0);
    }

    public RemoteUiObject findOne(long timeout, long interval) {
        ensureNonUiThread();
        long start = SystemClock.elapsedRealtime();
        while (true) {
            RemoteUiObject node = findOnce();
            if (node != null) {
                return node;
            }
            checkInterrupted();
            long now = SystemClock.elapsedRealtime();
            if (timeout > 0 && now - start > timeout) {
                return null;
            }
            sleep(timeout > 0 ? Math.min(interval > 0 ? interval : POLL_INTERVAL_MILLIS,
                    Math.max(1, timeout - (now - start))) : (interval > 0 ? interval : POLL_INTERVAL_MILLIS));
        }
    }

    public RemoteUiObject untilFindOne() {
        return findOne(-1);
    }

    public RemoteUiObjectCollection untilFind() {
        return untilFind(0);
    }

    public RemoteUiObjectCollection untilFind(long timeout) {
        return untilFind(timeout, POLL_INTERVAL_MILLIS);
    }

    public RemoteUiObjectCollection untilFind(long timeout, long interval) {
        ensureNonUiThread();
        long start = SystemClock.elapsedRealtime();
        while (true) {
            RemoteUiObjectCollection nodes = find();
            if (nodes.nonEmpty()) {
                return nodes;
            }
            checkInterrupted();
            long now = SystemClock.elapsedRealtime();
            if (timeout > 0 && now - start > timeout) {
                return nodes;
            }
            sleep(timeout > 0 ? Math.min(interval > 0 ? interval : POLL_INTERVAL_MILLIS,
                    Math.max(1, timeout - (now - start))) : (interval > 0 ? interval : POLL_INTERVAL_MILLIS));
        }
    }

    public boolean exists() {
        return find(1).nonEmpty();
    }

    public void waitFor() {
        untilFind();
    }

    // ---- 选择器级动作（对第一个匹配节点执行） -----------------------------

    public boolean click() {
        return firstNodeOrThrow("click").click();
    }

    public boolean longClick() {
        return firstNodeOrThrow("longClick").longClick();
    }

    public boolean setText(String text) {
        return firstNodeOrThrow("setText").setText(text);
    }

    public boolean scrollForward() {
        throw unsupported("scrollForward");
    }

    public boolean scrollBackward() {
        throw unsupported("scrollBackward");
    }

    // ---- 其它 UiSelector 条件：批一快照里没有字段，明确抛错 ---------------

    public RemoteUiSelector checkable(boolean value) {
        throw unsupportedCondition("checkable");
    }

    public RemoteUiSelector checkable() {
        throw unsupportedCondition("checkable");
    }

    public RemoteUiSelector checked(boolean value) {
        throw unsupportedCondition("checked");
    }

    public RemoteUiSelector checked() {
        throw unsupportedCondition("checked");
    }

    public RemoteUiSelector focusable(boolean value) {
        throw unsupportedCondition("focusable");
    }

    public RemoteUiSelector focused() {
        throw unsupportedCondition("focused");
    }

    public RemoteUiSelector selected(boolean value) {
        throw unsupportedCondition("selected");
    }

    public RemoteUiSelector selected() {
        throw unsupportedCondition("selected");
    }

    public RemoteUiSelector visibleToUser(boolean value) {
        throw unsupportedCondition("visibleToUser");
    }

    public RemoteUiSelector visibleToUser() {
        throw unsupportedCondition("visibleToUser");
    }

    public RemoteUiSelector row(int value) {
        throw unsupportedCondition("row");
    }

    public RemoteUiSelector column(int value) {
        throw unsupportedCondition("column");
    }

    public RemoteUiSelector indexInParent(int value) {
        throw unsupportedCondition("indexInParent");
    }

    public RemoteUiSelector boundsInside(int left, int top, int right, int bottom) {
        throw unsupportedCondition("boundsInside");
    }

    public RemoteUiSelector boundsContains(int left, int top, int right, int bottom) {
        throw unsupportedCondition("boundsContains");
    }

    @Override
    public String toString() {
        return "RemoteUiSelector" + mRemote + (mLocalFilters.isEmpty() ? "" : " +" + mLocalFilters.size() + " local");
    }

    // ---- 内部辅助 --------------------------------------------------------

    private RemoteUiObject firstNodeOrThrow(String action) {
        RemoteUiObjectCollection nodes = find(1);
        if (nodes.isEmpty()) {
            throw new ScriptException("远程选择器没有匹配到节点，无法执行 " + action + "()");
        }
        return nodes.get(0);
    }

    private RemoteUiSelector putString(String key, String value) {
        try {
            mRemote.put(key, value == null ? "" : value);
        } catch (Throwable error) {
            throw new ScriptException("构造选择器条件失败：" + error.getMessage(), error);
        }
        return this;
    }

    private RemoteUiSelector putBoolean(String key, boolean value) {
        try {
            mRemote.put(key, value);
        } catch (Throwable error) {
            throw new ScriptException("构造选择器条件失败：" + error.getMessage(), error);
        }
        return this;
    }

    private RemoteUiSelector putInt(String key, int value) {
        try {
            mRemote.put(key, value);
        } catch (Throwable error) {
            throw new ScriptException("构造选择器条件失败：" + error.getMessage(), error);
        }
        return this;
    }

    private enum MatchMode {
        CONTAINS, STARTS_WITH, ENDS_WITH, MATCHES
    }

    private RemoteUiSelector localString(final String key, final String value, final MatchMode mode) {
        final String expected = value == null ? "" : value;
        final Pattern pattern = mode == MatchMode.MATCHES ? Pattern.compile(expected) : null;
        mLocalFilters.add(node -> {
            String actual = node.optString(key, "");
            switch (mode) {
                case CONTAINS:
                    return actual.contains(expected);
                case STARTS_WITH:
                    return actual.startsWith(expected);
                case ENDS_WITH:
                    return actual.endsWith(expected);
                case MATCHES:
                    return pattern.matcher(actual).find();
                default:
                    return false;
            }
        });
        return this;
    }

    private boolean matchesLocal(JSONObject node) {
        for (LocalFilter filter : mLocalFilters) {
            if (!filter.matches(node)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUiThread() {
        return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
    }

    private void ensureNonUiThread() {
        if (isUiThread()) {
            throw new IllegalThreadStateException(
                    "不能在ui线程执行阻塞操作, 请在子线程或子脚本执行findOne()或untilFind()");
        }
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ScriptInterruptedException();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ScriptInterruptedException();
        }
    }

    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private ScriptException unsupported(String api) {
        return new ScriptException("隔离进程暂不支持 Selector." + api + "()（批一未代理）");
    }

    private ScriptException unsupportedCondition(String condition) {
        return new ScriptException(
                "隔离进程暂不支持选择器条件：" + condition + "()（批一快照里没有该字段，"
                        + "请改用 text/id/desc/className/packageName/clickable/enabled/scrollable/depth/bounds）");
    }
}
