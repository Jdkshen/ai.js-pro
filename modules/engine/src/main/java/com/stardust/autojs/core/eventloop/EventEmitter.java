package com.stardust.autojs.core.eventloop;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;


import com.stardust.autojs.core.looper.Timer;
import com.stardust.autojs.runtime.ScriptBridges;
import com.stardust.autojs.runtime.exception.ScriptException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TooManyListenersException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Created by Stardust on 2017/7/19.
 */

public class EventEmitter {

    private static final String TAG = "EventEmitter";

    /**
     * 特殊事件：{@code exit} 的监听从「emit 调用栈内同步执行」改为「投递到下一 Looper tick」。
     *
     * <p>背景（P0 ImGui 兼容，dev-980 真机）：第三方 native UI 项目的脚本会在渲染帧回调里
     * 主动 {@code events.emit("exit")}（如 ImGui 首帧按钮返回 true）。若 exit 监听在 JS 调用栈
     * 内同步执行，监听里的 {@code nativeShutdown → ImGui::DestroyContext} 会插进当前帧的
     * nativeNewFrame/nativeRender 之间，紧接着的 Button 读到 GImGui==NULL → SIGSEGV（0x15e0）。
     * 投递到下一 tick 后，当前帧一定先跑完，DestroyContext 不会插进帧中间；其它事件（click 等）
     * 行为不变，仍是同步派发。
     */
    private static final String EXIT_EVENT = "exit";

    private static class ListenerWrapper {
        Object listener;
        boolean isOnce;

        public ListenerWrapper(Object listener, boolean isOnce) {
            this.listener = listener;
            this.isOnce = isOnce;
        }
    }


    private class Listeners {
        private final String eventName;
        Listeners(String eventName) { this.eventName = eventName; }
        private CopyOnWriteArrayList<ListenerWrapper> mListenerWrappers = new CopyOnWriteArrayList<>();

        void add(Object listener, boolean once) {
            ensureListenersNotAtLimit();
            mListenerWrappers.add(new ListenerWrapper(listener, once));
        }

        private void ensureListenersNotAtLimit() {
            if (mMaxListeners != 0 && mListenerWrappers.size() >= mMaxListeners) {
                throw new ScriptException(new TooManyListenersException("max = " + mMaxListeners));
            }
        }

        boolean empty() {
            return mListenerWrappers.isEmpty();
        }

        void emit(Object[] args) {
            Iterator<ListenerWrapper> listenerIterator = mListenerWrappers.iterator();
            while (listenerIterator.hasNext()) {
                ListenerWrapper listenerWrapper = listenerIterator.next();
                if (listenerWrapper.isOnce && !mListenerWrappers.remove(listenerWrapper)) continue;
                if (mTimer != null) {
                    mTimer.setImmediate(listenerWrapper.listener, args);
                } else {
                    mBridges.callFunction(listenerWrapper.listener, EventEmitter.this, args);
                }
                if (listenerWrapper.isOnce) {
                    EventEmitter.this.emit("removeListener", eventName, listenerWrapper.listener);
                }
            }
        }

        int count() {
            return mListenerWrappers.size();
        }

        Object[] toArray() {
            Iterator<ListenerWrapper> listenerIterator = mListenerWrappers.iterator();
            ArrayList<Object> listeners = new ArrayList<>(mListenerWrappers.size());
            while (listenerIterator.hasNext()) {
                listeners.add(listenerIterator.next().listener);
            }
            return listeners.toArray(new Object[listeners.size()]);
        }

        void prepend(Object listener, boolean once) {
            ensureListenersNotAtLimit();
            mListenerWrappers.add(0, new ListenerWrapper(listener, once));
        }

        boolean remove(Object listener) {
            // CopyOnWriteArrayList 的迭代器不支持 remove()，必须由列表自身按下标/对象删除。
            for (ListenerWrapper wrapper : mListenerWrappers) {
                if (wrapper.listener == listener) {
                    mListenerWrappers.remove(wrapper);
                    return true;
                }
            }
            return false;
        }
    }

    private Map<String, Listeners> mListenersMap = new HashMap<>();
    public static int defaultMaxListeners = 10;
    private int mMaxListeners = defaultMaxListeners;
    protected ScriptBridges mBridges;
    private Timer mTimer;
    /** exit 异步派发用的 Handler（当前 Looper；懒创建，每 Looper 一个实例足够）。 */
    private volatile Handler mAsyncDispatchHandler;
    /** 取消/换代用：cancelPendingEmitAsync 之后，旧 runnable 看到代数不匹配直接丢弃。 */
    private volatile int mAsyncEmitEpoch;
    /** 已投递但尚未执行的 exit 异步派发数量（cancel 时返回给调用方决定是否同步补发）。 */
    private final Object mExitDispatchLock = new Object();
    private int mPendingExitDispatches;

    public EventEmitter(ScriptBridges bridges) {
        mBridges = bridges;
    }

    public EventEmitter(ScriptBridges bridges, Timer timer) {
        mTimer = timer;
        mBridges = bridges;
    }

    public EventEmitter once(String eventName, Object listener) {
        getListeners(eventName).add(listener, true);
        emit("newListener", eventName, listener);
        return this;
    }
    @NonNull
    private Listeners getListeners(String eventName) {
        Listeners listeners = mListenersMap.get(eventName);
        if (listeners == null) {
            listeners = new Listeners(eventName);
            mListenersMap.put(eventName, listeners);
        }
        return listeners;
    }


    public EventEmitter on(String eventName, Object listener) {
        getListeners(eventName).add(listener, false);
        emit("newListener", eventName, listener);
        return this;
    }

    public EventEmitter addListener(String eventName, Object listener) {
        return on(eventName, listener);
    }

    public boolean emit(String eventName, Object... args) {
        Listeners listeners = mListenersMap.get(eventName);
        if (listeners == null || listeners.empty())
            return false;
        if (EXIT_EVENT.equals(eventName) && mTimer == null) {
            // JS 调用栈内绝不执行 exit 监听：投递到下一 Looper tick（见 EXIT_EVENT 注释）。
            // mTimer != null 的 emitter 本来就走 setImmediate 异步，保持原样。
            dispatchExitAsync(listeners, args);
            return true;
        }
        listeners.emit(args);
        return true;
    }

    /**
     * 同步派发（运行时自己的退出清理专用，例如 ScriptRuntime.onExit）。
     *
     * <p>B（task-49）的引擎退出串行化依赖「exit 监听在屏障内执行完再继续 destroy 清理」，
     * 因此运行时 onExit 必须用 emitSync 而不是 emit（emit 现在会把 exit 投递到下一 tick）。
     */
    public boolean emitSync(String eventName, Object... args) {
        Listeners listeners = mListenersMap.get(eventName);
        if (listeners == null || listeners.empty())
            return false;
        listeners.emit(args);
        return true;
    }

    /**
     * 取消尚未执行的 exit 异步派发，返回取消前是否有待执行派发。
     *
     * <p>用于 runtime 退出清理之前：避免脚本先 emit(exit) 又立刻结束、投递的 exit 监听
     * 在 engine destroy 之后才执行（那时 Rhino 上下文已销毁）。运行时随后会用
     * {@link #emitSync} 再派发一次，保证 nativeShutdown 这类清理仍会执行。
     *
     * <p>返回值供 suppressExitEvent 的进程（:script）使用：那里不会自动 emit exit，
     * 但脚本自己手动 emit(exit) 的监听不能因引擎销毁而丢掉 —— 调用方据此补一次 emitSync。
     *
     * @return true 表示取消时确实有尚未执行的 exit 异步派发
     */
    public boolean cancelPendingEmitAsync() {
        final boolean hadPending;
        synchronized (mExitDispatchLock) {
            hadPending = mPendingExitDispatches > 0;
            mPendingExitDispatches = 0;
            mAsyncEmitEpoch++;
        }
        Handler handler = mAsyncDispatchHandler;
        if (handler != null) {
            try {
                handler.removeCallbacksAndMessages(null);
            } catch (Throwable ignored) {
            }
        }
        return hadPending;
    }

    /** 把 exit 监听投递到「下一 Looper tick」；没有 Looper/Looper 已 quit 时回退同步。 */
    private void dispatchExitAsync(final Listeners listeners, final Object[] args) {
        final Looper looper = Looper.myLooper();
        if (looper == null) {
            // 极端非 Looper 线程路径：没有「下一 tick」可用，保持旧行为。
            listeners.emit(args);
            return;
        }
        final int epoch = mAsyncEmitEpoch;
        Log.d(TAG, "exit 监听投递到下一 tick 执行（避免 nativeShutdown 插入渲染帧）");
        Handler handler = mAsyncDispatchHandler;
        if (handler == null || handler.getLooper() != looper) {
            synchronized (this) {
                handler = mAsyncDispatchHandler;
                if (handler == null || handler.getLooper() != looper) {
                    handler = new Handler(looper);
                    mAsyncDispatchHandler = handler;
                }
            }
        }
        synchronized (mExitDispatchLock) {
            mPendingExitDispatches++;
        }
        try {
            boolean posted = handler.post(new Runnable() {
                @Override
                public void run() {
                    decrementPendingExitDispatch();
                    if (epoch != mAsyncEmitEpoch) {
                        return;
                    }
                    try {
                        listeners.emit(args);
                    } catch (Throwable error) {
                        // 异步路径无法把异常抛回 JS 调用栈，只能记录（exit 清理不应炸 Looper）。
                        Log.w(TAG, "exit 监听执行异常", error);
                    }
                }
            });
            if (!posted) {
                // Looper 正在 quit：退回同步，保证 exit 清理不丢。
                decrementPendingExitDispatch();
                listeners.emit(args);
            }
        } catch (Throwable error) {
            Log.w(TAG, "投递 exit 监听失败，回退同步执行", error);
            decrementPendingExitDispatch();
            listeners.emit(args);
        }
    }

    private void decrementPendingExitDispatch() {
        synchronized (mExitDispatchLock) {
            if (mPendingExitDispatches > 0) {
                mPendingExitDispatches--;
            }
        }
    }

    public String[] eventNames() {
        return mListenersMap.keySet().toArray(new String[0]);
    }

    public int listenerCount(String eventName) {
        Listeners listeners = mListenersMap.get(eventName);
        if (listeners == null)
            return 0;
        return listeners.count();
    }

    public Object[] listeners(String eventName) {
        return getListeners(eventName).toArray();
    }

    public EventEmitter prependListener(String eventName, Object listener) {
        getListeners(eventName).prepend(listener, false);
        emit("newListener", eventName, listener);
        return this;
    }

    public EventEmitter prependOnceListener(String eventName, Object listener) {
        getListeners(eventName).prepend(listener, true);
        emit("newListener", eventName, listener);
        return this;
    }

    public EventEmitter removeAllListeners() {
        for (String event : eventNames()) {
            if (!"removeListener".equals(event)) removeAllListeners(event);
        }
        removeAllListeners("removeListener");
        return this;
    }

    public EventEmitter removeAllListeners(String eventName) {
        Listeners removed = mListenersMap.remove(eventName);
        if (removed != null) {
            for (Object listener : removed.toArray()) emit("removeListener", eventName, listener);
        }
        return this;
    }

    public EventEmitter removeListener(String eventName, Object listener) {
        Listeners listeners = mListenersMap.get(eventName);
        if (listeners != null && listeners.remove(listener))
            emit("removeListener", eventName, listener);
        return this;
    }

    public EventEmitter setMaxListeners(int n) {
        mMaxListeners = n;
        return this;
    }

    public int getMaxListeners() {
        return mMaxListeners;
    }

    public static int defaultMaxListeners() {
        return defaultMaxListeners;
    }


}
