package org.autojs.autojs.autojs;

import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.engine.JavaScriptEngine;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;
import org.autojs.autojs.App;
import org.autojs.autojs.R;
import org.autojs.autojs.external.foreground.ForegroundService;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Created by Stardust on 2017/5/3.
 */

public class ScriptExecutionGlobalListener implements ScriptExecutionListener {
    private static final String ENGINE_TAG_START_TIME = "org.autojs.autojs.autojs.Goodbye, World";
    private final Set<Integer> mForegroundExecutions =
            Collections.synchronizedSet(new HashSet<>());

    @Override
    public void onStart(ScriptExecution execution) {
        execution.getEngine().setTag(ENGINE_TAG_START_TIME, System.currentTimeMillis());
        if (mForegroundExecutions.add(execution.getId())
                && !ForegroundService.acquireExecutionLease(GlobalAppContext.get())) {
            mForegroundExecutions.remove(execution.getId());
        }
    }

    @Override
    public void onSuccess(ScriptExecution execution, Object result) {
        onFinish(execution);
    }

    private void onFinish(ScriptExecution execution) {
        if (mForegroundExecutions.remove(execution.getId())) {
            ForegroundService.releaseExecutionLease(GlobalAppContext.get());
        }
        Long millis = (Long) execution.getEngine().getTag(ENGINE_TAG_START_TIME);
        if (millis == null)
            return;
        double seconds = (System.currentTimeMillis() - millis) / 1000.0;
        AutoJs.getInstance().getScriptEngineService().getGlobalConsole()
                .verbose(GlobalAppContext.getString(R.string.text_execution_finished), execution.getSource().toString(), seconds);
    }

    @Override
    public void onException(ScriptExecution execution, Throwable e) {
        onFinish(execution);
    }

}
