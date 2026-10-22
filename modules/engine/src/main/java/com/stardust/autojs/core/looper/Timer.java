package com.stardust.autojs.core.looper;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;

import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.concurrent.VolatileBox;

/**
 * Created by Stardust on 2017/12/27.
 */

public class Timer {

    private static final String LOG_TAG = "Timer";

    private SparseArray<Runnable> mHandlerCallbacks = new SparseArray<>();
    private final java.util.Set<Runnable> mDirectCallbacks = new java.util.HashSet<>();
    private int mCallbackMaxId = 0;
    private final int mTimerId;
    private ScriptRuntime mRuntime;
    private Handler mHandler;
    private long mMaxCallbackUptimeMillis = 0;
    private final VolatileBox<Long> mMaxCallbackMillisForAllThread;

    public Timer(ScriptRuntime runtime, VolatileBox<Long> maxCallbackMillisForAllThread) {
        this(runtime, maxCallbackMillisForAllThread, 0);
    }

    public Timer(ScriptRuntime runtime, VolatileBox<Long> maxCallbackMillisForAllThread, int timerId) {
        this(runtime, maxCallbackMillisForAllThread, Looper.myLooper(), timerId);
    }

    public Timer(ScriptRuntime runtime, VolatileBox<Long> maxCallbackMillisForAllThread, Looper looper) {
        this(runtime, maxCallbackMillisForAllThread, looper, 0);
    }

    public Timer(ScriptRuntime runtime, VolatileBox<Long> maxCallbackMillisForAllThread, Looper looper, int timerId) {
        mTimerId = timerId;
        mRuntime = runtime;
        mMaxCallbackMillisForAllThread = maxCallbackMillisForAllThread;
        mHandler = new Handler(looper);
    }

    public synchronized double setTimeout(final Object callback, final long delay, final Object... args) {
        if (mCallbackMaxId == Integer.MAX_VALUE) throw new IllegalStateException("Too many callbacks");
        final int id = ++mCallbackMaxId;
        Runnable r = () -> {
            callFunction(callback, null, args);
            synchronized (Timer.this) { mHandlerCallbacks.remove(id); }
        };
        mHandlerCallbacks.put(id, r);
        schedule(r, delay);
        return TimerId.encode(mTimerId, id);
    }

    private void callFunction(Object callback, Object thiz, Object[] args) {
        if(Looper.myLooper() == Looper.getMainLooper()){
            try {
                mRuntime.bridges.callFunction(callback, thiz, args);
            }catch (Exception e){
                mRuntime.exit(e);
            }
        }else {
            mRuntime.bridges.callFunction(callback, thiz, args);
        }
    }

    public synchronized boolean clearTimeout(double id) {
        return clearCallback(TimerId.callbackId(id, mTimerId));
    }

    public synchronized double setInterval(final Object listener, final long interval, final Object... args) {
        if (mCallbackMaxId == Integer.MAX_VALUE) throw new IllegalStateException("Too many callbacks");
        final int id = ++mCallbackMaxId;
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                synchronized (Timer.this) {
                    if (mHandlerCallbacks.get(id) == null) return;
                }
                callFunction(listener, null, args);
                synchronized (Timer.this) {
                    if (mHandlerCallbacks.get(id) != null) schedule(this, interval);
                }
            }
        };
        mHandlerCallbacks.put(id, r);
        schedule(r, interval);
        return TimerId.encode(mTimerId, id);
    }

    public synchronized void postDelayed(Runnable r, long interval) {
        Runnable tracked = new Runnable() {
            @Override public void run() {
                try { r.run(); }
                finally { synchronized (Timer.this) { mDirectCallbacks.remove(this); } }
            }
        };
        mDirectCallbacks.add(tracked);
        schedule(tracked, interval);
    }

    private void schedule(Runnable r, long interval) {
        long uptime = SystemClock.uptimeMillis() + interval;
        mHandler.postAtTime(r, uptime);
        mMaxCallbackUptimeMillis = Math.max(mMaxCallbackUptimeMillis, uptime);
        synchronized (mMaxCallbackMillisForAllThread) {
            mMaxCallbackMillisForAllThread.set(Math.max(mMaxCallbackMillisForAllThread.get(), uptime));
        }
    }

    public void post(Runnable r) {
        postDelayed(r, 0);
    }

    public synchronized boolean clearInterval(double id) {
        return clearCallback(TimerId.callbackId(id, mTimerId));
    }

    public synchronized double setImmediate(final Object listener, final Object... args) {
        if (mCallbackMaxId == Integer.MAX_VALUE) throw new IllegalStateException("Too many callbacks");
        final int id = ++mCallbackMaxId;
        Runnable r = () -> {
            callFunction(listener, null, args);
            synchronized (Timer.this) { mHandlerCallbacks.remove(id); }
        };
        mHandlerCallbacks.put(id, r);
        schedule(r, 0);
        return TimerId.encode(mTimerId, id);
    }

    public synchronized boolean clearImmediate(double id) {
        return clearCallback(TimerId.callbackId(id, mTimerId));
    }

    private boolean clearCallback(int id) {
        Runnable callback = mHandlerCallbacks.get(id);
        if (callback != null) {
            mHandler.removeCallbacks(callback);
            synchronized (Timer.this) { mHandlerCallbacks.remove(id); }
            return true;
        }
        return false;
    }

    public synchronized boolean hasPendingCallbacks() {
        // A canceled far-future callback must not keep an idle looper alive.
        return mHandlerCallbacks.size() > 0 || !mDirectCallbacks.isEmpty();
    }

    public synchronized void removeAllCallbacks() {
        mHandler.removeCallbacksAndMessages(null);
        mHandlerCallbacks.clear();
        mDirectCallbacks.clear();
    }

}
