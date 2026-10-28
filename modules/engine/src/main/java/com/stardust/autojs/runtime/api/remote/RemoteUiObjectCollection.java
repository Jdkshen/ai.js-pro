package com.stardust.autojs.runtime.api.remote;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * {@code :script} 进程里的远程节点集合（替代 {@code com.stardust.automator.UiObjectCollection}）。
 *
 * <p>Rhino/QuickJS 脚本里 {@code text("x").find()} 的返回值常用 {@code size()/get()/empty()/
 * nonEmpty()} 等方法；这里保持与 UiObjectCollection 同名的最小接口，避免脚本拿到
 * {@code java.util.List} 后调用 {@code empty()} 等方法失败。
 */
public final class RemoteUiObjectCollection implements Iterable<RemoteUiObject> {

    private final List<RemoteUiObject> mNodes;

    public RemoteUiObjectCollection(List<RemoteUiObject> nodes) {
        mNodes = nodes == null ? Collections.<RemoteUiObject>emptyList() : nodes;
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
        return "RemoteUiObjectCollection" + mNodes;
    }
}
