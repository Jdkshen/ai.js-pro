package com.stardust.autojs.runtime.api.remote;

import android.os.SystemClock;
import android.util.Log;

import com.stardust.autojs.runtime.exception.ScriptException;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.javascript.regexp.NativeRegExp;

import java.util.ArrayList;
import java.util.Iterator;
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
 * endsWith/matches、bounds/boundsInside/boundsContains、clickable/enabled/scrollable/depth、
 * algorithm(BFS/DFS)，以及 find/findOne/findOnce/untilFind/exists/waitFor/click/longClick/
 * setText/scroll*。
 * 布尔状态、集合行列与边界条件在宿主快照上本地匹配，并与下发条件取交集。
 */
public final class RemoteUiSelector {

    private static final String TAG = "RemoteUiSelector";
    private static final long POLL_INTERVAL_MILLIS = 50L;
    /** 与宿主/本地 UiGlobalSelector 对齐的规范算法名。 */
    private static final String ALGORITHM_BFS = "BFS";
    private static final String ALGORITHM_DFS = "DFS";

    /** 可下发给主进程的契约选择器字段（IAutomationHost 文档 §3）。 */
    private final JSONObject mRemote = new JSONObject();
    /** 契约不支持的本地过滤条件（在 :script 侧对快照结果再过滤）。 */
    private final List<LocalFilter> mLocalFilters = new ArrayList<>();
    public interface NodePredicate { boolean test(RemoteUiObject node); }
    private final List<NodePredicate> mNodePredicates=new ArrayList<>();
    public RemoteUiSelector filter(NodePredicate predicate) {
        if(predicate==null) throw new IllegalArgumentException("filter 需要函数");
        mNodePredicates.add(predicate);return this;
    }
    public RemoteUiSelector addFilter(NodePredicate predicate) {return filter(predicate);}


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

    public RemoteUiSelector clickable() { return clickable(true); }
    public RemoteUiSelector enabled() { return enabled(true); }
    public RemoteUiSelector scrollable() { return scrollable(true); }

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

    /**
     * 选择器算法：只接受 BFS/DFS（大小写不敏感），与本地 {@code UiGlobalSelector.algorithm}
     * 一致；保存规范大写值，由宿主按所选算法真实遍历。未知算法立即失败，不再静默退回 DFS。
     *
     * <p>默认不下发 {@code algorithm} 字段（宿主默认 DFS，保持旧行为）。
     */
    public RemoteUiSelector algorithm(String algorithm) {
        final String canonical;
        if (ALGORITHM_BFS.equalsIgnoreCase(algorithm)) {
            canonical = ALGORITHM_BFS;
        } else if (ALGORITHM_DFS.equalsIgnoreCase(algorithm)) {
            canonical = ALGORITHM_DFS;
        } else {
            throw new IllegalArgumentException("unknown algorithm: " + algorithm);
        }
        try {
            mRemote.put("algorithm", canonical);
        } catch (Throwable error) {
            throw new ScriptException("构造选择器 algorithm 条件失败：" + error.getMessage(), error);
        }
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

    /** Query this live root and its descendants, matching native UiObject.find semantics. */
    RemoteUiObjectCollection findWithin(RemoteUiObject root) {
        return root.exists() ? find(Integer.MAX_VALUE,root) : new RemoteUiObjectCollection(new ArrayList<>());
    }

    public RemoteUiObjectCollection find() {
        return find(Integer.MAX_VALUE);
    }

    public RemoteUiObjectCollection find(int max) {
        return find(max,null);
    }
    private RemoteUiObjectCollection find(int max, RemoteUiObject root) {
        // A BFS query cannot be answered from the host's full dump: that dump is DFS-ordered.
        // Re-sorting it after the host's 500-node cap (and any future pagination) is not a true
        // level-order traversal, so fail loudly instead of returning a plausible but wrong order.
        if ((!mLocalFilters.isEmpty() || !mNodePredicates.isEmpty() || root!=null)
                && ALGORITHM_BFS.equals(mRemote.optString("algorithm", ALGORITHM_DFS))) {
            throw new ScriptException("BFS 算法暂不支持本地过滤条件：宿主只提供 DFS 顺序的完整快照，"
                    + "截断后排序无法等价于真正 BFS；请改用默认 DFS，或去掉本地过滤条件");
        }
        RemoteAutomationBridge.ensureAccessibility();
        RemoteAutomationBridge.Backend backend = RemoteAutomationBridge.requireBackend();
        List<RemoteUiObject> result = new ArrayList<>();
        String selectorJson = mRemote.toString();
        boolean proxyable = mLocalFilters.isEmpty() && mNodePredicates.isEmpty() && root==null;
        try {
            // Wire-only queries keep the host-side fast path. Once a local predicate exists, the
            // host's findNodes result is not sufficient: it is capped (500 nodes) before local
            // filtering, so a late node that satisfies both the wire conditions and the local
            // predicates can be omitted. Dump the full window instead, then apply the wire
            // predicates locally (same semantics as AutomationHostService.SelectorSpec) and
            // intersect with the local filters in host traversal order.
            WireMatcher wire = null;
            JSONArray nodes;
            if (proxyable) {
                nodes = new JSONArray(backend.findNodes(selectorJson));
            } else {
                wire = new WireMatcher(mRemote);
                nodes = new JSONArray(backend.getLatestNodeDump());
            }
            for (int i = 0; i < nodes.length() && result.size() < max; i++) {
                JSONObject node = nodes.optJSONObject(i);
                if (node == null || (wire != null && !wire.matches(node)) || !matchesLocal(node)) {
                    continue;
                }
                RemoteUiObject object=new RemoteUiObject(backend, selectorJson,result.size(),node,proxyable);
                if(root!=null && !object.isDescendantOf(root)
                        && !(object.path().equals(root.path()) && object.packageName().equals(root.packageName()))) continue;
                boolean accepted=true;
                for(NodePredicate predicate:mNodePredicates) if(!predicate.test(object)) {accepted=false;break;}
                if(accepted) result.add(object);
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
        return untilFind().click();
    }

    public boolean longClick() {
        return untilFind().longClick();
    }

    public boolean setText(String text) {
        return untilFind().setText(text);
    }

    public boolean scrollForward() {
        return untilFind().scrollForward();
    }

    public boolean scrollBackward() {
        return untilFind().scrollBackward();
    }

    // ---- Local predicates over the wire-filtered candidate set ---------------

    private RemoteUiSelector localBoolean(String key, boolean expected) {
        mLocalFilters.add(node -> node.optBoolean(key, false) == expected); return this;
    }
    private RemoteUiSelector localInt(String key, int expected) {
        mLocalFilters.add(node -> node.optInt(key, -1) == expected); return this;
    }
    public RemoteUiSelector checkable(boolean value) { return localBoolean("checkable", value); }
    public RemoteUiSelector checkable() { return checkable(true); }
    public RemoteUiSelector checked(boolean value) { return localBoolean("checked", value); }
    public RemoteUiSelector checked() { return checked(true); }
    public RemoteUiSelector focusable(boolean value) { return localBoolean("focusable", value); }
    public RemoteUiSelector focusable() { return focusable(true); }
    public RemoteUiSelector focused(boolean value) { return localBoolean("focused", value); }
    public RemoteUiSelector focused() { return focused(true); }
    public RemoteUiSelector selected(boolean value) { return localBoolean("selected", value); }
    public RemoteUiSelector selected() { return selected(true); }
    public RemoteUiSelector visibleToUser(boolean value) { return localBoolean("visibleToUser", value); }
    public RemoteUiSelector visibleToUser() { return visibleToUser(true); }
    public RemoteUiSelector editable(boolean value) { return localBoolean("editable", value); }
    public RemoteUiSelector editable() { return editable(true); }
    public RemoteUiSelector longClickable(boolean value) { return localBoolean("longClickable", value); }
    public RemoteUiSelector longClickable() { return longClickable(true); }
    public RemoteUiSelector password(boolean value) { return localBoolean("password", value); }
    public RemoteUiSelector password() { return password(true); }
    public RemoteUiSelector accessibilityFocused(boolean value) { return localBoolean("accessibilityFocused", value); }
    public RemoteUiSelector accessibilityFocused() { return accessibilityFocused(true); }
    public RemoteUiSelector contextClickable(boolean value) { return localBoolean("contextClickable", value); }
    public RemoteUiSelector contextClickable() { return contextClickable(true); }
    public RemoteUiSelector dismissable(boolean value) { return localBoolean("dismissable", value); }
    public RemoteUiSelector dismissable() { return dismissable(true); }
    public RemoteUiSelector contentInvalid(boolean value) { return localBoolean("contentInvalid", value); }
    public RemoteUiSelector contentInvalid() { return contentInvalid(true); }
    public RemoteUiSelector multiLine(boolean value) { return localBoolean("multiLine", value); }
    public RemoteUiSelector multiLine() { return multiLine(true); }
    public RemoteUiSelector drawingOrder(int value) { return localInt("drawingOrder", value); }
    public RemoteUiSelector row(int value) { return localInt("row", value); }
    public RemoteUiSelector column(int value) { return localInt("column", value); }
    public RemoteUiSelector rowCount(int value) { return localInt("rowCount", value); }
    public RemoteUiSelector columnCount(int value) { return localInt("columnCount", value); }
    public RemoteUiSelector rowSpan(int value) { return localInt("rowSpan", value); }
    public RemoteUiSelector columnSpan(int value) { return localInt("columnSpan", value); }
    public RemoteUiSelector indexInParent(int value) { return localInt("indexInParent", value); }
    public RemoteUiSelector boundsInside(int left, int top, int right, int bottom) {
        mLocalFilters.add(node -> { JSONArray b = node.optJSONArray("bounds");
            return b != null && b.length() == 4 && b.optInt(0) >= left && b.optInt(1) >= top
                && b.optInt(2) <= right && b.optInt(3) <= bottom; }); return this;
    }
    public RemoteUiSelector boundsContains(int left, int top, int right, int bottom) {
        mLocalFilters.add(node -> { JSONArray b = node.optJSONArray("bounds");
            return b != null && b.length() == 4 && b.optInt(0) <= left && b.optInt(1) <= top
                && b.optInt(2) >= right && b.optInt(3) >= bottom; }); return this;
    }

    @Override
    public String toString() {
        return "RemoteUiSelector" + mRemote + (mLocalFilters.isEmpty() ? "" : " +" + mLocalFilters.size() + " local");
    }

    // ---- 内部辅助 --------------------------------------------------------

    public boolean clickCenter() { return untilFind().clickCenter(); }
    public boolean scrollUp() { return untilFind().scrollUp(); }
    public boolean scrollDown() { return untilFind().scrollDown(); }
    public boolean scrollLeft() { return untilFind().scrollLeft(); }
    public boolean scrollRight() { return untilFind().scrollRight(); }
    public boolean copy() { return untilFind().copy(); }
    public boolean cut() { return untilFind().cut(); }
    public boolean paste() { return untilFind().paste(); }
    public boolean select() { return untilFind().select(); }
    public boolean collapse() { return untilFind().collapse(); }
    public boolean expand() { return untilFind().expand(); }
    public boolean dismiss() { return untilFind().dismiss(); }
    public boolean show() { return untilFind().show(); }
    public boolean contextClick() { return untilFind().contextClick(); }
    public boolean focus() { return untilFind().focus(); }
    public boolean clearFocus() { return untilFind().clearFocus(); }
    public boolean accessibilityFocus() { return untilFind().accessibilityFocus(); }
    public boolean clearAccessibilityFocus() { return untilFind().clearAccessibilityFocus(); }
    public boolean setSelection(int start, int end) { return untilFind().setSelection(start, end); }
    public boolean setProgress(float value) { return untilFind().setProgress(value); }
    public boolean scrollTo(int row, int column) { return untilFind().scrollTo(row, column); }

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
                    return pattern.matcher(actual).matches();
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

    /**
     * Snapshot-side equivalent of {@code AutomationHostService.SelectorSpec}: every condition in
     * {@link #mRemote} must be applied to the same node before the local filters run.
     *
     * <p>Compiled once per query (only when local filters force the snapshot path); the wire
     * {@code textMatches} regex is compiled here, not per node. Unknown wire fields fail loudly
     * instead of being silently ignored.
     */
    private static final class WireMatcher {

        private String id;
        private String text;
        private String textContains;
        private String textStartsWith;
        private Pattern textMatches;
        private String desc;
        private String descContains;
        private String className;
        private String packageName;
        private Boolean clickable;
        private Boolean enabled;
        private Boolean scrollable;
        private Integer depth;
        /** left/top/right/bottom, null when the selector has no bounds condition. */
        private int[] bounds;

        WireMatcher(JSONObject wire) throws org.json.JSONException {
            for (Iterator<String> keys = wire.keys(); keys.hasNext(); ) {
                String key = keys.next();
                switch (key) {
                    case "id": id = wire.optString(key, ""); break;
                    case "text": text = wire.optString(key, ""); break;
                    case "textContains": textContains = wire.optString(key, ""); break;
                    case "textStartsWith": textStartsWith = wire.optString(key, ""); break;
                    case "textMatches": textMatches = Pattern.compile(wire.optString(key, "")); break;
                    case "desc": desc = wire.optString(key, ""); break;
                    case "descContains": descContains = wire.optString(key, ""); break;
                    case "className": className = wire.optString(key, ""); break;
                    case "packageName": packageName = wire.optString(key, ""); break;
                    case "clickable": clickable = wire.getBoolean(key); break;
                    case "enabled": enabled = wire.getBoolean(key); break;
                    case "scrollable": scrollable = wire.getBoolean(key); break;
                    case "depth": depth = wire.getInt(key); break;
                    case "algorithm": {
                        String algorithm = wire.optString(key, "");
                        if (!ALGORITHM_BFS.equalsIgnoreCase(algorithm)
                                && !ALGORITHM_DFS.equalsIgnoreCase(algorithm)) {
                            throw new ScriptException("远程选择器 algorithm 取值无效：" + algorithm);
                        }
                        // BFS cannot use the DFS-ordered dump; the find() guard normally catches
                        // this earlier, but keep the matcher itself from silently applying BFS.
                        if (ALGORITHM_BFS.equalsIgnoreCase(algorithm)) {
                            throw new ScriptException("BFS 算法无法在 DFS 顺序的宿主快照上应用本地过滤条件");
                        }
                        break;
                    }
                    case "bounds": {
                        JSONObject value = wire.optJSONObject(key);
                        if (value == null) {
                            throw new ScriptException("远程选择器 bounds 条件格式无效");
                        }
                        bounds = new int[]{
                                value.optInt("left", 0), value.optInt("top", 0),
                                value.optInt("right", 0), value.optInt("bottom", 0)};
                        break;
                    }
                    default:
                        throw new ScriptException("远程选择器无法在快照节点上应用字段：" + key);
                }
            }
        }

        boolean matches(JSONObject node) {
            if (id != null) {
                String fullId = node.optString("id", "");
                // The dump collapses a null view id to "". SelectorSpec rejects null ids, so an
                // absent id must not satisfy id("").
                if (fullId.isEmpty()) {
                    return false;
                }
                if (!fullId.equals(id) && !simplifyId(fullId).equals(id)) {
                    return false;
                }
            }
            String nodeText = node.optString("text", "");
            if (text != null && !nodeText.equals(text)) return false;
            if (textContains != null && !nodeText.contains(textContains)) return false;
            if (textStartsWith != null && !nodeText.startsWith(textStartsWith)) return false;
            if (textMatches != null && !textMatches.matcher(nodeText).matches()) return false;
            String nodeDesc = node.optString("desc", "");
            if (desc != null && !nodeDesc.equals(desc)) return false;
            if (descContains != null && !nodeDesc.contains(descContains)) return false;
            if (className != null && !className.equals(node.optString("className", ""))) return false;
            if (packageName != null && !packageName.equals(node.optString("packageName", ""))) {
                return false;
            }
            if (clickable != null && node.optBoolean("clickable", false) != clickable) return false;
            if (enabled != null && node.optBoolean("enabled", false) != enabled) return false;
            if (scrollable != null && node.optBoolean("scrollable", false) != scrollable) return false;
            if (depth != null && node.optInt("depth", -1) != depth) return false;
            if (bounds != null) {
                JSONArray nodeBounds = node.optJSONArray("bounds");
                if (nodeBounds == null || nodeBounds.length() != 4) {
                    return false;
                }
                for (int i = 0; i < bounds.length; i++) {
                    if (nodeBounds.optInt(i, Integer.MIN_VALUE) != bounds[i]) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** Same normalization as AutomationHostService.SelectorSpec.simplifyId. */
        private static String simplifyId(String resourceName) {
            int slash = resourceName.indexOf('/');
            return slash >= 0 ? resourceName.substring(slash + 1) : resourceName;
        }
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
