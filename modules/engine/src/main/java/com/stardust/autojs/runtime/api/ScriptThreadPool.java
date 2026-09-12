package com.stardust.autojs.runtime.api;

import androidx.annotation.NonNull;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Auto.js Pro 的 {@code $threads.pool(...)} 对应实现。
 *
 * <p>任务在脚本线程（{@link com.stardust.autojs.core.looper.TimerThread}）上执行，因此任务里可以直接用
 * {@code log}、{@code sleep}、{@code threads.currentThread()} 等脚本 API。
 * 队列容量取 maxPoolSize，队列满时扩容到 maxPoolSize，仍然满则由提交方线程直接执行（不抛异常）。
 */
public class ScriptThreadPool {

    private final ThreadPoolExecutor mExecutor;
    private final int mCorePoolSize;
    private final int mMaxPoolSize;

    ScriptThreadPool(@NonNull Threads threads, int corePoolSize, int maxPoolSize) {
        mCorePoolSize = Math.max(0, corePoolSize);
        mMaxPoolSize = Math.max(mCorePoolSize, maxPoolSize);
        mExecutor = new ThreadPoolExecutor(
                mCorePoolSize,
                Math.max(1, mMaxPoolSize),
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(Math.max(1, mMaxPoolSize)),
                threads::createPoolThread,
                new ThreadPoolExecutor.CallerRunsPolicy());
        mExecutor.allowCoreThreadTimeOut(true);
    }

    public void execute(Runnable runnable) {
        mExecutor.execute(runnable);
    }

    public void shutdown() {
        mExecutor.shutdown();
    }

    public boolean isShutdown() {
        return mExecutor.isShutdown();
    }

    public void shutdownNow() {
        mExecutor.shutdownNow();
    }

    public int getCorePoolSize() {
        return mCorePoolSize;
    }

    public int getMaximumPoolSize() {
        return mMaxPoolSize;
    }

    public int getPoolSize() {
        return mExecutor.getPoolSize();
    }

    public int getActiveCount() {
        return mExecutor.getActiveCount();
    }

    public long getCompletedTaskCount() {
        return mExecutor.getCompletedTaskCount();
    }

    public long getTaskCount() {
        return mExecutor.getTaskCount();
    }
}
