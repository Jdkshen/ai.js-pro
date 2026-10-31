package com.stardust.autojs.runtime.api.remote;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * {@code :script} 进程里的远程节点集合（替代 {@code com.stardust.autojs.object.UiObjectCollection}）。
 *
 * <p>Rhino/QuickJS 脚本里 {@code text("x").find()} 的返回值常用 {@code size()/get()/empty()/
 * nonEmpty()} 等方法；这里保持与 UiObjectCollection 同名的最小接口，避免脚本拿到
 * {@code java.util.List} 后调用 {@code empty()} 等方法失败。
 *
 * <p><b>R05 完整性预算（向后兼容的追加元数据）</b>：集合额外携带这一次查询的快照预算，
 * 老代码只用 size()/get() 时行为完全不变：
 * <ul>
 *   <li>{@link #complete()}：本次结果覆盖快照里全部匹配项，且快照本身没有被宿主预算截断；</li>
 *   <li>{@link #truncated()}：{@code !complete()}，即结果或快照被截断（存在未取到的节点）；</li>
 *   <li>{@link #snapshotComplete()}：宿主快照（getLatestNodeDump）本身是否完整；纯下发
 *       findNodes 的快通道没有完整树语义，返回 {@code true} 仅在未触达宿主返回上限时成立；</li>
 *   <li>{@link #firstMissingPath()}：快照截断时第一个缺失子节点的 path（JSON 数组字符串），
 *       完整时为 {@code null}；</li>
 *   <li>{@link #generation()}：本次快照的代次（每次向宿主取树自增），用于判断身份/游标是否过期；</li>
 *   <li>{@link #nextCursor()}：稳定分页游标；完整时为 {@code null}，否则可交给下一次分页；</li>
 *   <li>{@link #seen()}：本次查询实际检查过的快照节点数。</li>
 * </ul>
 */
public final class RemoteUiObjectCollection implements Iterable<RemoteUiObject> {

    private final List<RemoteUiObject> mNodes;
    private final boolean mComplete;
    private final boolean mSnapshotComplete;
    private final String mFirstMissingPath;
    private final int mGeneration;
    private final String mNextCursor;
    private final int mSeen;

    /** 旧构造器：无预算信息的历史查询，等价于「单次结果就是全部」。 */
    public RemoteUiObjectCollection(List<RemoteUiObject> nodes) {
        this(nodes, true, true, null, 0, null, nodes == null ? 0 : nodes.size());
    }

    public RemoteUiObjectCollection(List<RemoteUiObject> nodes, boolean complete,
                                    boolean snapshotComplete, String firstMissingPath,
                                    int generation, String nextCursor, int seen) {
        mNodes = nodes == null ? Collections.<RemoteUiObject>emptyList() : nodes;
        mComplete = complete;
        mSnapshotComplete = snapshotComplete;
        mFirstMissingPath = firstMissingPath;
        mGeneration = generation;
        mNextCursor = nextCursor;
        mSeen = seen;
    }

    public int size() {
        return mNodes.size();
    }

    public boolean isEmpty() {
        return mNodes.isEmpty();
    }

    /** Auto.js 的 UiObjectCollection.empty() 同名兼容。 */
    public boolean empty() {
        return mNodes.isEmpty();
    }

    /** Auto.js 的 UiObjectCollection.nonEmpty() 同名兼容。 */
    public boolean nonEmpty() {
        return !mNodes.isEmpty();
    }

    public RemoteUiObject get(int index) {
        return mNodes.get(index);
    }

    public RemoteUiObject[] toArray() {
        return mNodes.toArray(new RemoteUiObject[0]);
    }

    // ---- R05：完整性/截断/游标/预算（只追加，不改变旧行为） ----------------

    /** 结果是否覆盖快照里的全部匹配项，且快照未被宿主预算截断。 */
    public boolean complete() {
        return mComplete;
    }

    /** 结果或快照被截断：存在没取到的节点，不能当成完整结果使用。 */
    public boolean truncated() {
        return !mComplete;
    }

    /** 宿主快照本身是否完整（childCount 与 path 结构自洽、没有缺子节点）。 */
    public boolean snapshotComplete() {
        return mSnapshotComplete;
    }

    /** 快照截断时第一个缺失子节点的 path（JSON 数组字符串）；完整时为 null。 */
    public String firstMissingPath() {
        return mFirstMissingPath;
    }

    /** 本次查询使用的宿主快照代次；节点身份/游标过期判断用它。 */
    public int generation() {
        return mGeneration;
    }

    /** 稳定分页游标：把该值交回 query(limit, cursor) 可继续下一页；完整时为 null。 */
    public String nextCursor() {
        return mNextCursor;
    }

    /** 本次查询检查过的快照节点数（预算可观察）。 */
    public int seen() {
        return mSeen;
    }

    private boolean act(String action, Object... arguments) {
        boolean success = true;
        for (RemoteUiObject node : mNodes) {
            boolean result;
            if ("click".equals(action)) result = node.click();
            else if ("longClick".equals(action)) result = node.longClick();
            else if ("clickCenter".equals(action)) result = node.clickCenter();
            else if ("setText".equals(action)) result = node.setText((String)arguments[0]);
            else if ("setSelection".equals(action)) result = node.setSelection((Integer)arguments[0], (Integer)arguments[1]);
            else if ("setProgress".equals(action)) result = node.setProgress((Float)arguments[0]);
            else if ("scrollTo".equals(action)) result = node.scrollTo((Integer)arguments[0], (Integer)arguments[1]);
            else result = node.action(action, arguments);
            success &= result;
        }
        return success;
    }
    public boolean click() { return act("click"); }
    public boolean longClick() { return act("longClick"); }
    public boolean clickCenter() { return act("clickCenter"); }
    public boolean scrollForward() { return act("scrollForward"); }
    public boolean scrollBackward() { return act("scrollBackward"); }
    public boolean scrollUp() { return act("scrollUp"); }
    public boolean scrollDown() { return act("scrollDown"); }
    public boolean scrollLeft() { return act("scrollLeft"); }
    public boolean scrollRight() { return act("scrollRight"); }
    public boolean copy() { return act("copy"); }
    public boolean cut() { return act("cut"); }
    public boolean paste() { return act("paste"); }
    public boolean select() { return act("select"); }
    public boolean collapse() { return act("collapse"); }
    public boolean expand() { return act("expand"); }
    public boolean dismiss() { return act("dismiss"); }
    public boolean show() { return act("show"); }
    public boolean contextClick() { return act("contextClick"); }
    public boolean focus() { return act("focus"); }
    public boolean clearFocus() { return act("clearFocus"); }
    public boolean accessibilityFocus() { return act("accessibilityFocus"); }
    public boolean clearAccessibilityFocus() { return act("clearAccessibilityFocus"); }
    public boolean setText(String text) { return act("setText", text); }
    public boolean setSelection(int start, int end) {
        if (start < 0 || end < start) throw new IllegalArgumentException("setSelection 需要 0 <= start <= end");
        return act("setSelection", start, end);
    }
    public boolean setProgress(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("setProgress 需要有限数字");
        return act("setProgress", value);
    }
    public boolean scrollTo(int row, int column) {
        if (row < 0 || column < 0) throw new IllegalArgumentException("scrollTo 需要非负行列");
        return act("scrollTo", row, column);
    }

    @Override
    public Iterator<RemoteUiObject> iterator() {
        return mNodes.iterator();
    }

    @Override
    public String toString() {
        return "RemoteUiObjectCollection" + mNodes
                + (mComplete ? "" : " truncated(gen=" + mGeneration
                        + (mNextCursor == null ? "" : ", cursor=" + mNextCursor) + ")");
    }
}
