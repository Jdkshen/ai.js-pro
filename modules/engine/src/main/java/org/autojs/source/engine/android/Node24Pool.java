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
    private static final long ACQUIRE_TIMEOUT_MS = 8000L;
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

    private static final Slot[] sSlots = new Slot[SLOTS];
    static {
        for (int i = 0; i < SLOTS; i++) sSlots[i] = new Slot(i);
    }

    private Node24Pool() { }

    /** 分配一个空闲槽；全部忙时等待释放（≤8s），超时给明确中文错误。 */
    public static synchronized Slot acquire(Context context) throws Exception {
        long deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT_MS;
        while (true) {
            for (Slot slot : sSlots) {
                if (!slot.busy) {
                    if (slot.client == null || !slot.client.isAlive()) {
                        closeQuietly(slot.client);
                        slot.client = new Node24Client(context, SERVICE_CLASSES[slot.index]);
                    }
                    slot.busy = true;
                    slot.lastUsed = System.currentTimeMillis();
                    Log.i(TAG, "acquire slot=" + slot.index);
                    return slot;
                }
            }
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                throw new IllegalStateException("Node 执行槽已满（" + SLOTS + " 个），请先停止一个 Node 脚本");
            }
            try { Node24Pool.class.wait(Math.min(remain, 250L)); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Node 执行槽被中断");
            }
        }
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

    /** 当前池占用数（诊断/任务页用）。 */
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
