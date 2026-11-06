// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.Context;
import android.util.Log;

/**
 * task-175：Node 宿主进程池（:node24_1..3）。每槽一个独立进程、每进程单 Node runtime；
 * :script 的 Node24JavaScriptEngine 通过本池分配/释放槽，实现多 Node 脚本并行、按 id 停止。
 * 原生桥每进程单会话（spike 已证），因此并行度 = 池槽数。
 */
public final class Node24Pool {
    private static final String TAG = "Node24Pool";
    private static final int SLOTS = 3;
    /** B7：允许短暂排队，但必须有界；超过队列/等待时间就明确失败，绝不无界等待。 */
    private static final int MAX_WAITERS = 5;
    private static final long ACQUIRE_TIMEOUT_MS = 30_000L;
    private static int sWaiters;
    private static final Class<?>[] SERVICE_CLASSES = {
            Node24PoolService1.class, Node24PoolService2.class, Node24PoolService3.class
    };

    public static final class Slot {
        final int index;
        Node24Client client;
        boolean busy;
        long lastUsed;
        Slot(int index) { this.index = index; }
        public Node24Client client() { return client; }
        public int index() { return index; }
    }

    /** 槽位已满（3/3 运行中）且排队已满/等待超时；调用方不得把它当成池启动失败去回退 legacy。 */
    public static final class PoolBusyException extends IllegalStateException {
        public PoolBusyException(String message) { super(message); }
    }

    /** 等待槽位时线程被取消/中断；调用方不得回退 legacy。 */
    public static final class PoolInterruptedException extends IllegalStateException {
        public PoolInterruptedException(String message) { super(message); }
    }

    private static final Slot[] sSlots = new Slot[SLOTS];
    static {
        for (int i = 0; i < SLOTS; i++) sSlots[i] = new Slot(i);
    }

    private Node24Pool() { }

    /**
     * 分配一个空闲槽。B7：全部忙时进入有界队列，最多等待 {@link #acquireTimeoutMillis()}
     * 且最多 {@link #maxWaiters()} 个等待者；队列满或等待超时返回带并发上限的明确错误。
     * 这是进程内调度队列，不是无界线程池；调用方仍应在 MCP 层串行/错峰。
     */
    public static synchronized Slot acquire(Context context) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new PoolInterruptedException("等待 Node 执行槽已被取消");
        }
        long deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT_MS;
        boolean queued = false;
        try {
            while (true) {
                for (Slot slot : sSlots) {
                    if (!slot.busy) {
                        if (slot.client == null || !slot.client.isAlive()) {
                            closeQuietly(slot.client);
                            slot.client = new Node24Client(context, SERVICE_CLASSES[slot.index]);
                        }
                        slot.busy = true;
                        slot.lastUsed = System.currentTimeMillis();
                        Log.i(TAG, "acquire slot=" + slot.index + " waiters=" + sWaiters);
                        return slot;
                    }
                }
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    throw new PoolBusyException(waitTimeoutMessage());
                }
                if (!queued) {
                    if (!hasQueueCapacity(sWaiters)) {
                        throw new PoolBusyException(queueFullMessage());
                    }
                    sWaiters++;
                    queued = true;
                    Log.i(TAG, "queue waiters=" + sWaiters + "/" + MAX_WAITERS);
                }
                try { Node24Pool.class.wait(Math.min(remain, 250L)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new PoolInterruptedException("等待 Node 执行槽被取消");
                }
            }
        } finally {
            if (queued) {
                sWaiters--;
                if (sWaiters < 0) sWaiters = 0;
            }
        }
    }

    /** 池容量（诊断/测试用）。 */
    public static int capacity() {
        return SLOTS;
    }

    /** B7 排队策略：等待队列上限（不含 3 个正在运行的槽）。 */
    public static int maxWaiters() {
        return MAX_WAITERS;
    }

    /** B7 排队策略：池满后最长等待时间。 */
    public static long acquireTimeoutMillis() {
        return ACQUIRE_TIMEOUT_MS;
    }

    /** 纯策略函数，便于 JVM 单测；等待者达到上限即不再排队。 */
    public static boolean hasQueueCapacity(int waiting) {
        return waiting >= 0 && waiting < MAX_WAITERS;
    }

    /** 队列已满时的稳定错误文案（MCP 返回体会原样带出）。 */
    public static String queueFullMessage() {
        return "Node 执行槽已满（并发上限 " + SLOTS + " 个），排队队列已满（最多 " + MAX_WAITERS
                + " 个）；请串行执行或先停止一个 Node 脚本";
    }

    /** 池满且排队超时的稳定错误文案。 */
    public static String waitTimeoutMessage() {
        return "Node 执行槽已满（并发上限 " + SLOTS + " 个），已排队等待 " + (ACQUIRE_TIMEOUT_MS / 1000L)
                + "s 仍无空闲槽；请串行执行或先停止一个 Node 脚本";
    }

    /** 当前进程内正在排队的请求数（诊断/测试用；跨进程不可见）。 */
    public static synchronized int waitingCount() {
        return sWaiters;
    }

    /**
     * 只有真正的池启动故障才允许回退旧 :node24。槽位忙/等待被取消属于正常调度结果，
     * 回退会绕开池容量并让调用方误以为任务已开始，因此明确禁止。
     */
    public static boolean shouldFallbackToLegacy(Throwable error, boolean interrupted) {
        if (interrupted) return false;
        int depth = 0;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (++depth > 32 || current instanceof Error
                    || current instanceof InterruptedException
                    || current instanceof java.util.concurrent.CancellationException
                    || current instanceof PoolBusyException || current instanceof PoolInterruptedException) {
                return false;
            }
            if (current.getCause() == current) break;
        }
        return true;
    }

    /** 诊断/调用方判断是否为可识别的繁忙或取消。 */
    public static boolean isBusyOrCancelled(Throwable error) {
        return !shouldFallbackToLegacy(error, false);
    }

    /** 释放槽；healthy=false 时关闭客户端，下次重建（进程崩溃恢复）。 */
    public static synchronized void release(Slot slot, boolean healthy) {
        if (slot == null) return;
        slot.busy = false;
        slot.lastUsed = System.currentTimeMillis();
        if (!healthy) {
            closeQuietly(slot.client);
            slot.client = null;
        }
        Node24Pool.class.notifyAll();
        Log.i(TAG, "release slot=" + slot.index + " healthy=" + healthy);
    }

    /** 执行失败/进程崩溃时作废槽客户端，下一次 acquire 重新拉起进程。 */
    public static synchronized void invalidate(Slot slot) {
        if (slot == null) return;
        closeQuietly(slot.client);
        slot.client = null;
        slot.busy = false;
        Node24Pool.class.notifyAll();
        Log.w(TAG, "invalidate slot=" + slot.index);
    }

    /** 当前进程的池占用数（仅本进程可见；跨 :script/:node24_N 不能当全局值）。 */
    public static synchronized int busyCount() {
        int count = 0;
        for (Slot slot : sSlots) if (slot.busy) count++;
        return count;
    }

    /** :node24 deprecated 回退开关：默认 false（用进程池）；可用 SharedPreferences "node24.legacy"=true 回退。 */
    public static boolean legacyEnabled(Context context) {
        if (Boolean.getBoolean("autojs.node24.legacy") || "1".equals(System.getenv("AJS_NODE24_LEGACY"))) {
            return true;
        }
        try {
            return context != null && context.getSharedPreferences("node24_pool", Context.MODE_PRIVATE)
                    .getBoolean("legacy", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void closeQuietly(Node24Client client) {
        if (client == null) return;
        try { client.close(); } catch (Throwable ignored) { }
    }
}
