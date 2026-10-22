package com.stardust.autojs.runtime.api;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import com.stardust.autojs.core.looper.Timer;
import com.stardust.autojs.core.looper.TimerThread;
import com.stardust.autojs.core.looper.TimerId;
import com.stardust.autojs.runtime.ScriptBridges;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.concurrent.VolatileBox;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Stardust on 2017/7/21.
 */

public class Timers {

    private static final String LOG_TAG = "Timers";

    private VolatileBox<Long> mMaxCallbackUptimeMillisForAllThreads = new VolatileBox<>(0L);
    private Threads mThreads;
    private Timer mMainTimer;
    private Timer mUiTimer;
    private final ConcurrentHashMap<Integer, WeakReference<Timer>> mTimers = new ConcurrentHashMap<>();
    private final AtomicInteger mNextTimerId = new AtomicInteger(2);


    public Timers(ScriptRuntime runtime) {
        mMainTimer = new Timer(runtime, mMaxCallbackUptimeMillisForAllThreads, 0);
        mUiTimer = new Timer(runtime, mMaxCallbackUptimeMillisForAllThreads, Looper.getMainLooper(), 1);
        mThreads = runtime.threads;
    }

    public Timer getMainTimer() {
        return mMainTimer;
    }

    VolatileBox<Long> getMaxCallbackUptimeMillisForAllThreads() {
        return mMaxCallbackUptimeMillisForAllThreads;
    }

    public Timer getTimerForCurrentThread() {
        return getTimerForThread(Thread.currentThread());
    }

    public Timer getTimerForThread(Thread thread) {
        if (thread == mThreads.getMainThread()) {
            return mMainTimer;
        }
        Timer timer = TimerThread.getTimerForThread(thread);
        if (timer == null && Looper.myLooper() == Looper.getMainLooper()) {
            return mUiTimer;
        }
        return timer;
    }

    public double setTimeout(Object callback, long delay, Object... args) {
        return getTimerForCurrentOrMainThread().setTimeout(callback, delay, args);
    }

    public boolean clearTimeout(double id) {
        Timer timer = getTimerForId(id);
        return timer != null && timer.clearTimeout(id);
    }

    public double setInterval(Object listener, long interval, Object... args) {
        return getTimerForCurrentOrMainThread().setInterval(listener, interval, args);
    }

    public boolean clearInterval(double id) {
        Timer timer = getTimerForId(id);
        return timer != null && timer.clearInterval(id);
    }

    public double setImmediate(Object listener, Object... args) {
        return getTimerForCurrentOrMainThread().setImmediate(listener, args);
    }

    public boolean clearImmediate(double id) {
        Timer timer = getTimerForId(id);
        return timer != null && timer.clearImmediate(id);
    }

    public Timer newTimer(ScriptRuntime runtime) {
        int timerId = mNextTimerId.getAndIncrement();
        if (timerId > TimerId.MAX_TIMER_ID) throw new IllegalStateException("Too many script timers");
        Timer timer = new Timer(runtime, mMaxCallbackUptimeMillisForAllThreads, timerId);
        mTimers.put(timerId, new WeakReference<>(timer));
        return timer;
    }

    public Timer getTimerForCurrentOrMainThread() {
        Timer timer = getTimerForCurrentThread();
        return timer != null ? timer : mMainTimer;
    }

    private Timer getTimerForId(double id) {
        int timerId = TimerId.timerId(id);
        if (timerId == 0) return mMainTimer;
        if (timerId == 1) return mUiTimer;
        WeakReference<Timer> reference = mTimers.get(timerId);
        Timer timer = reference == null ? null : reference.get();
        if (reference != null && timer == null) mTimers.remove(timerId, reference);
        return timer;
    }

    public boolean hasPendingCallbacks() {
        // Main execution waits for actual callbacks owned by this runtime, including child timers.
        if (mThreads.getMainThread() == Thread.currentThread()) {
            if (mMainTimer.hasPendingCallbacks() || mUiTimer.hasPendingCallbacks()) return true;
            for (WeakReference<Timer> reference : mTimers.values()) {
                Timer timer = reference.get();
                if (timer != null && timer.hasPendingCallbacks()) return true;
            }
            return false;
        }
        // 否则检查当前线程的定时回调
        Timer timer = getTimerForCurrentThread();
        return timer != null && timer.hasPendingCallbacks();
    }

    public void recycle() {
        mMainTimer.removeAllCallbacks();
        mUiTimer.removeAllCallbacks();
        for (WeakReference<Timer> reference : mTimers.values()) {
            Timer timer = reference.get();
            if (timer != null) timer.removeAllCallbacks();
        }
        mTimers.clear();
    }

}
