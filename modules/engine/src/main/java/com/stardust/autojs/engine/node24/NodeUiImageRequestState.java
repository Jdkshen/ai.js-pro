package com.stardust.autojs.engine.node24;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Node UI 图片请求的纯 Java 状态机，不依赖 Android/Glide，便于 JVM 单测。
 *
 * <p>每个 View 同一时刻只允许一个“当前”请求。新请求会淘汰旧请求；被淘汰、取消、
 * 关闭或已被新请求接管的回调都只能得到 {@code false}，不能把旧图片写回 View。
 * View 用弱引用作为 key，且 {@link #applyIfCurrent}/{@link #failIfCurrent} 完成后会
 * 立即移除条目，因此已完成的请求不会强引用已经离开界面的 View。
 *
 * <p>所有方法都在本对象的锁内完成“判断是否仍为当前请求 + 写 View”的原子操作。
 * {@link #applyIfCurrent} 会持有该锁执行 action，因此 action 内不要做耗时工作；
 * 它可以安全地调用 {@code begin()}（锁可重入），新请求不会被本次收尾误删。
 */
public final class NodeUiImageRequestState {

    /** 在状态锁内执行的写 View 动作；返回 true 表示图片已经真正应用。 */
    public interface Action {
        boolean run();
    }

    /** 一次 loadImageInto 的请求票据。只做身份比较，不重写 equals/hashCode。 */
    public static final class Ticket {
        // 票据会被 WeakHashMap 的 value 强引用，因此这里必须用弱引用持有 View，
        // 否则 value -> Ticket -> View 会把 WeakHashMap 的弱 key 变相变成强引用。
        private final WeakReference<Object> view;
        private final long generation;

        private Ticket(Object view, long generation) {
            this.view = new WeakReference<Object>(view);
            this.generation = generation;
        }

        public long generation() {
            return generation;
        }

        private Object view() {
            return view.get();
        }
    }

    /** begin() 的结果：新票据及被它淘汰的旧票据（可能为 null）。 */
    public static final class Begin {
        private final Ticket ticket;
        private final Ticket superseded;

        private Begin(Ticket ticket, Ticket superseded) {
            this.ticket = ticket;
            this.superseded = superseded;
        }

        public Ticket ticket() {
            return ticket;
        }

        public Ticket superseded() {
            return superseded;
        }
    }

    private final WeakHashMap<Object, Ticket> current = new WeakHashMap<Object, Ticket>();
    private boolean closed;
    private long nextGeneration;

    /**
     * 为 view 建立新请求，并淘汰该 view 之前的当前请求。
     *
     * @throws IllegalStateException 状态机已经 close
     */
    public synchronized Begin begin(Object view) {
        if (closed) {
            throw new IllegalStateException("图片请求状态已关闭");
        }
        if (view == null) {
            throw new NullPointerException("view");
        }
        Ticket ticket = new Ticket(view, ++nextGeneration);
        Ticket previous = current.put(view, ticket);
        return new Begin(ticket, previous);
    }

    /** 票据是否仍然是该 View 的当前请求。 */
    public synchronized boolean isCurrent(Ticket ticket) {
        return isCurrentLocked(ticket);
    }

    /**
     * 仅当票据仍是当前请求时执行写 View 动作；无论动作返回 true/false，
     * 本次请求都会从状态表中移除。动作抛异常时也会移除，避免遗留 View 引用。
     */
    public synchronized boolean applyIfCurrent(Ticket ticket, Action action) {
        Object view = currentViewLocked(ticket);
        if (view == null) {
            return false;
        }
        if (action == null) {
            throw new NullPointerException("action");
        }
        boolean applied;
        try {
            applied = action.run();
        } finally {
            // 动作里可能同步触发了同一 View 的新请求；只删除本次票据，不误删新票据。
            Object currentView = ticket.view();
            if (currentView != null && current.get(currentView) == ticket) {
                current.remove(currentView);
            }
        }
        return applied;
    }

    /**
     * 标记请求失败并移除。只有仍是当前请求时返回 true；被替换/取消/关闭的旧失败
     * 不能覆盖新请求，也不应再向脚本抛错。
     */
    public synchronized boolean failIfCurrent(Ticket ticket) {
        Object view = currentViewLocked(ticket);
        if (view == null) {
            return false;
        }
        current.remove(view);
        return true;
    }

    /** 取消某 View 的当前请求，返回被取消的票据；没有则返回 null。 */
    public synchronized Ticket cancel(Object view) {
        if (view == null) {
            return null;
        }
        return current.remove(view);
    }

    /** 仅当票据仍是当前请求时取消它。 */
    public synchronized boolean cancel(Ticket ticket) {
        Object view = currentViewLocked(ticket);
        if (view == null) {
            return false;
        }
        current.remove(view);
        return true;
    }

    /**
     * 关闭状态机并返回所有未完成票据。关闭后 begin 抛错，已有票据全部失效。
     * 重复调用返回空列表。
     */
    public synchronized List<Ticket> close() {
        if (closed) {
            return Collections.emptyList();
        }
        closed = true;
        List<Ticket> tickets = new ArrayList<Ticket>(current.values());
        current.clear();
        return tickets;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    /** 仅供诊断/测试：当前仍有效的请求数。 */
    public synchronized int pendingCount() {
        return current.size();
    }

    /** 仅供诊断/测试：某 View 是否仍有当前请求。 */
    public synchronized boolean hasPending(Object view) {
        return view != null && current.containsKey(view);
    }

    private boolean isCurrentLocked(Ticket ticket) {
        return currentViewLocked(ticket) != null;
    }

    private Object currentViewLocked(Ticket ticket) {
        if (closed || ticket == null) {
            return null;
        }
        Object view = ticket.view();
        if (view == null || current.get(view) != ticket) {
            return null;
        }
        return view;
    }
}
