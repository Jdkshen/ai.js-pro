package com.jdkshen.aijspro.autojs;

import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.external.foreground.ForegroundService;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Created by Stardust on 2017/5/3.
 */

public class ScriptExecutionGlobalListener implements ScriptExecutionListener {
    private static final String ENGINE_TAG_START_TIME = "com.jdkshen.aijspro.autojs.Goodbye, World";
    private final Set<Integer> mForegroundExecutions =
            Collections.synchronizedSet(new HashSet<>());

    @Override
    public void onStart(ScriptExecution execution) {
        if(execution==null)return;
        if(execution.getEngine()!=null)execution.getEngine().setTag("aijspro.notification.startedAt",System.currentTimeMillis());
        if (mForegroundExecutions.add(execution.getId())
                && !ForegroundService.acquireExecutionLease(GlobalAppContext.get())) {
            mForegroundExecutions.remove(execution.getId());
        }
    }

    @Override
    public void onSuccess(ScriptExecution execution, Object result) {
        onFinish(execution);
    }

    /**
     * task-191：开始/结束行已统一由 ScriptEngineService.GLOBAL_LISTENER 打印
     * （结束行在错误堆栈之前），这里只负责前台执行租约；不要再打第二遍结束行。
     */
    private void onFinish(ScriptExecution execution) {
        if(execution==null)return;
        if (mForegroundExecutions.remove(execution.getId())) {
            ForegroundService.releaseExecutionLease(GlobalAppContext.get());
        }
    }

    @Override
    public void onException(ScriptExecution execution, Throwable e) {
        onFinish(execution);
    }

}
