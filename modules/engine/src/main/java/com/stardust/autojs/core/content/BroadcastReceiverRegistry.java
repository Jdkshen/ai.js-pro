package com.stardust.autojs.core.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本侧广播接收器登记表（task-54，自研、纯 JVM 可测）。
 *
 * 职责边界：
 *  - 本类只做句柄生命周期 + 回调隔离 + 计数，不碰 Android（平台调用交给 Host）；
 *  - 句柄带 owner 线程：脚本线程结束（正常 exit 或异常）后，sweep() 与任意 API 入口都会
 *    把该 owner 的接收器注销掉；进程被杀不会恢复（属系统行为，见 notes）；
 *  - Listener.onIntent 抛出的任何 Throwable 都被吞掉并转给 Listener.onError，
 *    绝不让 onReceive 拖垮进程；
 *  - 注销后 dispatch 不再回调（未知 id 直接忽略）。
 */
public final class BroadcastReceiverRegistry {
    /** 平台侧回调：intent 故意用 Object，纯层不依赖 android.content.Intent。 */
    public interface Listener {
        void onIntent(Object intent);
        void onError(Throwable error);
    }

    /** 平台侧登记/注销实现（真机是 Context.registerReceiver；单测是假实现）。 */
    public interface Host {
        void register(Handle handle);
        void unregister(Handle handle);
    }

    public static final class Handle {
        public final String id;
        public final BroadcastFilter filter;
        public final boolean exported;
        public final long registeredAt;
        public final Thread owner;

        Handle(String id, BroadcastFilter filter, boolean exported, long registeredAt, Thread owner) {
            this.id = id;
            this.filter = filter;
            this.exported = exported;
            this.registeredAt = registeredAt;
            this.owner = owner;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    private static final class Entry {
        final Handle handle;
        final Listener listener;
        final Host host;

        Entry(Handle handle, Listener listener, Host host) {
            this.handle = handle;
            this.listener = listener;
            this.host = host;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private long sequence = 0L;
    private long registered = 0L;
    private long unregistered = 0L;
    private long dispatched = 0L;
    private long callbackErrors = 0L;
    private long swept = 0L;

    public synchronized Handle register(BroadcastFilter filter, boolean exported, int apiLevel,
                                        Listener listener, Host host) {
        if (filter == null) throw new IllegalArgumentException("filter 不能为空");
        if (listener == null) throw new IllegalArgumentException("callback 必须是函数");
        if (host == null) throw new IllegalArgumentException("内部错误：缺少平台登记实现");
        sweepLocked();
        Handle handle = new Handle("bcr-" + (++sequence), filter, exported, System.currentTimeMillis(),
                Thread.currentThread());
        // 先把平台登记做完再入表：平台抛异常时不留下看似已注册的句柄。
        host.register(handle);
        entries.put(handle.id, new Entry(handle, listener, host));
        registered++;
        return handle;
    }

    /** 幂等：不存在或已注销返回 false；成功注销返回 true。 */
    public synchronized boolean unregister(String idOrHandle) {
        sweepLocked();
        String id = idOf(idOrHandle);
        if (id == null) return false;
        Entry entry = entries.remove(id);
        if (entry == null) return false;
        unregisterQuietly(entry);
        unregistered++;
        return true;
    }

    public synchronized int unregisterAll() {
        int count = 0;
        for (Entry entry : new ArrayList<Entry>(entries.values())) {
            if (unregisterQuietly(entry)) count++;
            unregistered++;
        }
        entries.clear();
        return count;
    }

    /** 指定 owner 线程的清理（脚本结束时可显式调用）。 */
    public synchronized int unregisterForOwner(Thread owner) {
        if (owner == null) return 0;
        int count = 0;
        for (Map.Entry<String, Entry> item : new ArrayList<Map.Entry<String, Entry>>(entries.entrySet())) {
            if (item.getValue().handle.owner == owner) {
                if (unregisterQuietly(item.getValue())) count++;
                unregistered++;
                entries.remove(item.getKey());
            }
        }
        return count;
    }

    /** 清理 owner 线程已结束的句柄（在每次 API 入口调用，保证幂等与不泄漏）。 */
    public synchronized int sweep() {
        return sweepLocked();
    }

    private int sweepLocked() {
        int count = 0;
        for (Map.Entry<String, Entry> item : new ArrayList<Map.Entry<String, Entry>>(entries.entrySet())) {
            Thread owner = item.getValue().handle.owner;
            if (owner != null && !owner.isAlive()) {
                if (unregisterQuietly(item.getValue())) count++;
                swept++;
                unregistered++;
                entries.remove(item.getKey());
            }
        }
        return count;
    }

    /** 平台回调入口：0=未知/已注销（不回调）、1=成功、2=回调抛异常（已隔离）。 */
    public int dispatch(String idOrHandle, Object intent) {
        String id = idOf(idOrHandle);
        Entry entry;
        synchronized (this) {
            entry = id == null ? null : entries.get(id);
            if (entry != null) dispatched++;
        }
        if (entry == null) return 0;
        try {
            entry.listener.onIntent(intent);
            return 1;
        } catch (Throwable error) {
            synchronized (this) {
                callbackErrors++;
            }
            try {
                entry.listener.onError(error);
            } catch (Throwable ignored) {
                // onError 自己抛也不再向上传播。
            }
            return 2;
        }
    }

    public synchronized int activeCount() {
        return entries.size();
    }

    public synchronized List<Handle> activeHandles() {
        List<Handle> result = new ArrayList<Handle>(entries.size());
        for (Entry entry : entries.values()) result.add(entry.handle);
        return result;
    }

    public synchronized Map<String, Long> counters() {
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        result.put("registered", registered);
        result.put("unregistered", unregistered);
        result.put("active", (long) entries.size());
        result.put("dispatched", dispatched);
        result.put("callbackErrors", callbackErrors);
        result.put("swept", swept);
        return result;
    }

    private boolean unregisterQuietly(Entry entry) {
        try {
            entry.host.unregister(entry.handle);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 句柄可以是 id 字符串，也可以是 Handle（或任何 toString 成 bcr-N 的对象）。 */
    private static String idOf(Object idOrHandle) {
        if (idOrHandle == null) return null;
        String id = String.valueOf(idOrHandle).trim();
        return id.isEmpty() ? null : id;
    }
}
