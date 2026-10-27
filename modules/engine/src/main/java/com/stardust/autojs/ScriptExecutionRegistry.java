package com.stardust.autojs;

import com.stardust.autojs.execution.ScriptExecution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行中脚本执行表。
 *
 * <p>这张表会被执行线程（登记）、引擎销毁线程（移除）和 UI/任务查询线程（读取、停止）
 * 同时访问。所有方法都在同一把锁上同步，{@link #snapshot()} 返回调用时刻的稳定副本，
 * 调用方遍历时不会与并发的登记/移除互相干扰。
 *
 * <p>内部 Map 不对外暴露，避免调用方绕过同步直接遍历。
 *
 * <p>锁对象就是本实例：{@link ScriptEngineService} 启动 ScriptExecuteActivity 时会临时
 * 持有它，使 Activity.onCreate 里的 {@link #get(int)} 等到执行登记完成后再返回。
 */
final class ScriptExecutionRegistry {

    private final Map<Integer, ScriptExecution> mExecutions = new LinkedHashMap<>();

    synchronized void register(ScriptExecution execution) {
        mExecutions.put(execution.getId(), execution);
    }

    synchronized void unregister(int executionId) {
        mExecutions.remove(executionId);
    }

    synchronized ScriptExecution get(int executionId) {
        return mExecutions.get(executionId);
    }

    synchronized Collection<ScriptExecution> snapshot() {
        return new ArrayList<>(mExecutions.values());
    }
}
