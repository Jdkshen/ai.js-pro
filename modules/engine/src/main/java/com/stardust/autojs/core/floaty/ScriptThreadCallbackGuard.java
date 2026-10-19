package com.stardust.autojs.core.floaty;

import android.util.Log;

import com.stardust.autojs.ScriptEngineService;
import com.stardust.autojs.engine.RhinoJavaScriptEngine;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.execution.ScriptExecution;

import java.util.ArrayList;
import java.util.Collection;

/**
 * 脚本线程回调的「引擎是否还活着」判断（B/task-49 barrier 的 A 侧配套保护）。
 *
 * <p>背景：B 的退出 barrier 在**主 Looper（=脚本线程）**上 destroy 时是 inline 零等待
 * （不能在同一个 Looper 上同步排水，否则嵌套 loop 死锁），只会清 timers 的定时器；
 * 而 A 手工 {@code Handler.post} 到脚本线程的 surfaceCreated/Changed/Destroyed 回调不会被
 * 排掉，可能排在 {@code DestroyContext} 之后执行。所以 A 在 runnable 里需要第二道判断：
 * 「engine 已经 destroy 就不要再碰 native/JS」。
 *
 * <p>做法：脚本线程调用 {@code SurfaceHolder.addCallback} 时，从 {@link ScriptEngineService}
 * 的执行/引擎表里找到**本线程对应的 Rhino 引擎**（引擎 init 时记录了 thread）。回调真正执行
 * 前查 {@link ScriptEngine#isDestroyed()}（AbstractScriptEngine 的 volatile 标记，destroy 时置位）。
 * 找不到引擎线索时返回 true（保守放行，只依赖窗口 close 的 released 标记），避免误杀正常回调。
 */
final class ScriptThreadCallbackGuard {

    private static final String TAG = "ScriptThreadCallbackGuard";

    private ScriptThreadCallbackGuard() {
    }

    /**
     * 在发起回调的脚本线程上捕获执行引擎线索；找不到返回 null（此时调用方不做额外限制）。
     */
    static Object captureEngineOfCurrentThread() {
        final Thread current = Thread.currentThread();
        ScriptEngineService service;
        try {
            service = ScriptEngineService.getInstance();
        } catch (Throwable error) {
            return null;
        }
        if (service == null) {
            return null;
        }
        try {
            Collection<ScriptExecution> executions =
                    new ArrayList<>(service.getScriptExecutions());
            for (ScriptExecution execution : executions) {
                if (execution == null) {
                    continue;
                }
                Object match = matchEngine(execution.getEngine(), current);
                if (match != null) {
                    return match;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "scan script executions failed", error);
        }
        try {
            Collection<ScriptEngine> engines = new ArrayList<>(service.getEngines());
            for (ScriptEngine engine : engines) {
                Object match = matchEngine(engine, current);
                if (match != null) {
                    return match;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "scan engines failed", error);
        }
        return null;
    }

    private static Object matchEngine(ScriptEngine engine, Thread current) {
        if (!(engine instanceof RhinoJavaScriptEngine)) {
            return null;
        }
        try {
            return ((RhinoJavaScriptEngine) engine).getThread() == current ? engine : null;
        } catch (Throwable error) {
            // engine.thread 是 lateinit，极端情况（还没 init 完）会抛，忽略该引擎。
            return null;
        }
    }

    /**
     * 引擎（线索）是否还活着。无线索时返回 true（保守放行）；destroy 之后返回 false。
     */
    static boolean isEngineAlive(Object engineHint) {
        if (!(engineHint instanceof ScriptEngine)) {
            return true;
        }
        try {
            return !((ScriptEngine) engineHint).isDestroyed();
        } catch (Throwable error) {
            return true;
        }
    }
}
