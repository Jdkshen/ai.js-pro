package com.stardust.autojs.execution;

import android.util.Log;

import com.stardust.autojs.core.console.ScriptRunLog;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.engine.ScriptEngineManager;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.autojs.script.ScriptSource;
import com.stardust.lang.ThreadCompat;

import org.mozilla.javascript.ContinuationPending;

/**
 * Created by Stardust on 2017/5/1.
 */

public class RunnableScriptExecution extends ScriptExecution.AbstractScriptExecution implements Runnable {

    private static final String TAG = "RunnableJSExecution";
    private ScriptEngine mScriptEngine;
    private ScriptEngineManager mScriptEngineManager;
    private ScriptRunLog mRunLog;

    public RunnableScriptExecution(ScriptEngineManager manager, ScriptExecutionTask task) {
        super(task);
        mScriptEngineManager = manager;
    }

    @Override
    public void run() {
        // Source paths may contain multi-byte characters. Native thread-name truncation
        // can split those bytes and abort JNI proc-file readers on some Android builds.
        ThreadCompat.currentThread().setName(ScriptThreadNames.execution(getId()));
        try {
            execute();
        } catch (Throwable error) {
            // Engine selection/creation can fail before execute(engine) enters
            // its guarded block (for example a Rhino script in a lite build).
            // Report it through the normal listener instead of killing the app process.
            onException(null, error);
        }
    }

    public Object execute() {
        mScriptEngine = mScriptEngineManager.createEngineOfSourceOrThrow(getSource(), getId());
        mScriptEngine.setTag(ExecutionConfig.getTag(), getConfig());
        // 运行日志落盘：跑完/崩了都能回头翻（内容 = 这次运行自己的控制台输出 + 结束原因/堆栈）
        mRunLog = ScriptRunLog.attach(mScriptEngine, getSource(), mScriptEngineManager.getAndroidContext());
        return execute(mScriptEngine);
    }

    private Object execute(ScriptEngine engine) {
        Throwable failure = null;
        try {
            prepare(engine);
            Object r = doExecution(engine);
            Throwable uncaughtException = engine.getUncaughtException();
            if (uncaughtException != null) {
                failure = uncaughtException;
                onException(engine, uncaughtException);
                return null;
            }
            getListener().onSuccess(this, r);
            return r;
        } catch (Throwable e) {
            failure = e;
            onException(engine, e);
            return null;
        } finally {
            Log.d(TAG, "Engine destroy");
            engine.destroy();
            if (mRunLog != null) {
                // destroy 之后收尾：destroy 过程里可能还有最后几行控制台输出（如异常堆栈）。
                mRunLog.finish(failure);
                mRunLog = null;
            }
        }
    }

    protected void onException(ScriptEngine engine, Throwable e) {
        Log.w(TAG, "onException: engine = " + engine, e);
        getListener().onException(this, e);
    }

    private void prepare(ScriptEngine engine) {
        engine.setTag(ScriptEngine.TAG_WORKING_DIRECTORY, getConfig().getWorkingDirectory());
        engine.setTag(ScriptEngine.TAG_ENV_PATH, getConfig().getPath());
        engine.init();
    }

    protected Object doExecution(ScriptEngine engine) {
        engine.setTag(ScriptEngine.TAG_SOURCE, getSource());
        getListener().onStart(this);
        Object result = null;
        long delay = getConfig().getDelay();
        int times = getConfig().getLoopTimes();
        if (times == 0) {
            times = Integer.MAX_VALUE;
        }
        long interval = getConfig().getInterval();
        sleep(delay);
        ScriptSource source = getSource();
        for (int i = 0; i < times; i++) {
            result = execute(engine, source);
            sleep(interval);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    protected Object execute(ScriptEngine engine, ScriptSource source) {
        return engine.execute(source);
    }

    protected void sleep(long i) {
        if (i <= 0) {
            return;
        }
        try {
            Thread.sleep(i);
        } catch (InterruptedException e) {
            throw new ScriptInterruptedException();
        }
    }

    @Override
    public ScriptEngine getEngine() {
        return mScriptEngine;
    }

}
