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

    RemoteUiObjectCollection(List<RemoteUiObject> nodes) {
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

    @Override
    public Iterator<RemoteUiObject> iterator() {
        return mNodes.iterator();
    }

    @Override
    public String toString() {
        return "RemoteUiObjectCollection" + mNodes;
    }
}
