package com.stardust.autojs.execution;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;

import com.stardust.autojs.ScriptEngineService;
import com.stardust.autojs.core.eventloop.EventEmitter;
import com.stardust.autojs.core.eventloop.SimpleEvent;
import com.stardust.autojs.engine.JavaScriptEngine;
import com.stardust.autojs.engine.LoopBasedJavaScriptEngine;
import com.stardust.autojs.engine.QuickJsJavaScriptEngine;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.engine.ScriptEngineManager;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.script.ScriptSource;

import org.mozilla.javascript.ContinuationPending;

/**
 * Created by Stardust on 2017/2/5.
 */

public class ScriptExecuteActivity extends AppCompatActivity {


    private static final String LOG_TAG = "ScriptExecuteActivity";
    private static final String EXTRA_EXECUTION_ID = ScriptExecuteActivity.class.getName() + ".execution_id";
    private Object mResult;
    private ScriptEngine mScriptEngine;
    private ScriptExecutionListener mExecutionListener;
    private ScriptSource mScriptSource;
    private ActivityScriptExecution mScriptExecution;
    private ScriptRuntime mRuntime;


    private EventEmitter mEventEmitter;

    public static ActivityScriptExecution execute(Context context, ScriptEngineManager manager, ScriptExecutionTask task) {
        ActivityScriptExecution execution = new ActivityScriptExecution(manager, task);
        Intent i = new Intent(context, ScriptExecuteActivity.class)
                .putExtra(EXTRA_EXECUTION_ID, execution.getId())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(task.getConfig().getIntentFlags());
        context.startActivity(i);
        return execution;
    }

    // FIXME: 2018/3/16 如果Activity被回收则得不到改进
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int executionId = getIntent().getIntExtra(EXTRA_EXECUTION_ID, ScriptExecution.NO_ID);
        if (executionId == ScriptExecution.NO_ID) {
            super.finish();
            return;
        }
        ScriptExecution execution = ScriptEngineService.getInstance().getScriptExecution(executionId);
        if (execution == null || !(execution instanceof ActivityScriptExecution)) {
            super.finish();
            return;
        }
        mScriptExecution = (ActivityScriptExecution) execution;
        mScriptSource = mScriptExecution.getSource();
        mScriptEngine = mScriptExecution.createEngine(this);
        mExecutionListener = mScriptExecution.getListener();
        mRuntime = ((JavaScriptEngine) mScriptEngine).getRuntime();
        mEventEmitter = new EventEmitter(mRuntime.bridges);
        runScript();
        emit("create", savedInstanceState);
    }

    public EventEmitter getEventEmitter() {
        return mEventEmitter;
    }

    private void runScript() {
        if (mScriptEngine instanceof QuickJsJavaScriptEngine) {
            // QuickJS 的脚本必须在引擎自己的线程上跑：native 事件循环会一直占住执行线程
            // （界面还在就用定时器维持），在 onCreate 里同步执行会把主线程占死（输入超时 ANR）。
            // Rhino 的 LoopBasedJavaScriptEngine 本来就是异步回调，这里对齐它的语义：
            // 主线程只负责建界面，脚本在后台线程跑。
            new Thread(() -> {
                try {
                    prepare();
                    doExecution();
                } catch (Throwable e) {
                    onException(e);
                }
            }, "QuickJsScriptHost").start();
            return;
        }
        try {
            prepare();
            doExecution();
        } catch (ContinuationPending pending) {
            pending.printStackTrace();
        } catch (Exception e) {
            onException(e);
        }
    }

    private void onException(Throwable e) {
        // 异常可能来自脚本线程（QuickJS），而监听器/收尾都要在主线程做。
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(() -> onException(e));
            return;
        }
        // Activity 已经收掉时（返回键/停止脚本/音量键停止都会走到这里）execution 已被置空，
        // 此时脚本的结束本来就是预期结果，再上报只会把 null 塞给监听器（曾因此 NPE 崩溃）。
        if (mScriptExecution == null || mExecutionListener == null) {
            Log.d(LOG_TAG, "ignore exception after the activity was destroyed: " + e);
            return;
        }
        mExecutionListener.onException(mScriptExecution, e);
        super.finish();
    }

    private void doExecution() {
        // 脚本线程可能晚于 Activity 生命周期启动：此时 execution/listener 已被 onDestroy 置空，
        // 直接不跑（监听器要求非空参数，传 null 会直接崩溃）。
        final ScriptExecution execution = mScriptExecution;
        final ScriptExecutionListener listener = mExecutionListener;
        if (execution == null || listener == null) {
            Log.d(LOG_TAG, "activity was destroyed before the script started; abort");
            return;
        }
        mScriptEngine.setTag(ScriptEngine.TAG_SOURCE, mScriptSource);
        listener.onStart(execution);
        if (mScriptEngine instanceof LoopBasedJavaScriptEngine) {
            ((LoopBasedJavaScriptEngine) mScriptEngine).execute(mScriptSource, new LoopBasedJavaScriptEngine.ExecuteCallback() {
                @Override
                public void onResult(Object r) {
                    mResult = r;
                }

                @Override
                public void onException(Exception e) {
                    ScriptExecuteActivity.this.onException(e);
                }
            });
            return;
        }
        // QuickJS：同步执行，native 事件循环在脚本存活期间继续派发定时器/回调；
        // 界面收在本 Activity 里，退到后台与返回键的行为与 Rhino 一致。
        try {
            mResult = mScriptEngine.execute(mScriptSource);
        } catch (Exception e) {
            onException(e);
        }
    }

    private void prepare() {
        mScriptEngine.put("activity", this);
        mScriptEngine.setTag("activity", this);
        // QuickJS 的 put() 只收简单类型（对象会被忽略），因此宿主 Activity 另走一条通道：
        // host bridge 拿到它以后会把 ui.layout 结果挂到本 Activity 的内容视图。
        if (mScriptEngine instanceof QuickJsJavaScriptEngine) {
            ((QuickJsJavaScriptEngine) mScriptEngine).setHostActivity(this);
        }
        mScriptEngine.setTag(ScriptEngine.TAG_ENV_PATH, mScriptExecution.getConfig().getPath());
        mScriptEngine.setTag(ScriptEngine.TAG_WORKING_DIRECTORY, mScriptExecution.getConfig().getWorkingDirectory());
        mScriptEngine.init();
    }

    @Override
    public void finish() {
        if (mScriptExecution == null || mExecutionListener == null) {
            super.finish();
            return;
        }
        Throwable exception = mScriptEngine.getUncaughtException();
        if (exception != null) {
            onException(exception);
        } else {
            mExecutionListener.onSuccess(mScriptExecution, mResult);
        }
        super.finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(LOG_TAG, "onDestroy");
        if (mScriptEngine != null) {
            mScriptEngine.put("activity", null);
            mScriptEngine.setTag("activity", null);
            mScriptEngine.destroy();
        }
        mScriptExecution = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mScriptExecution != null)
            outState.putInt(EXTRA_EXECUTION_ID, mScriptExecution.getId());
        emit("save_instance_state", outState);
    }

    @Override
    public void onBackPressed() {
        SimpleEvent event = new SimpleEvent();
        emit("back_pressed", event);
        if (!event.consumed) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        emit("pause");
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        emit("resume");
    }

    @Override
    protected void onRestart() {
        super.onRestart();
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        emit("restore_instance_state", savedInstanceState);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        SimpleEvent e = new SimpleEvent();
        emit("key_down", keyCode, event, e);
        return e.consumed || super.onKeyDown(keyCode, event);

    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        SimpleEvent e = new SimpleEvent();
        emit("generic_motion_event", event, e);
        return super.onGenericMotionEvent(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        emit("activity_result", requestCode, resultCode, data);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        emit("create_options_menu", menu);
        return menu.size() > 0;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        SimpleEvent e = new SimpleEvent();
        emit("options_item_selected", e, item);
        return e.consumed || super.onOptionsItemSelected(item);
    }

    public void emit(String event, Object... args) {
        try {
            mEventEmitter.emit(event, (Object[]) args);
        } catch (Exception e) {
            mRuntime.exit(e);
        }
    }

    private static class ActivityScriptExecution extends ScriptExecution.AbstractScriptExecution {

        private ScriptEngine mScriptEngine;
        private ScriptEngineManager mScriptEngineManager;

        ActivityScriptExecution(ScriptEngineManager manager, ScriptExecutionTask task) {
            super(task);
            mScriptEngineManager = manager;
        }

        @SuppressWarnings("unchecked")
        public ScriptEngine createEngine(Activity activity) {
            if (mScriptEngine != null) {
                mScriptEngine.forceStop();
            }
            mScriptEngine = mScriptEngineManager.createEngineOfSourceOrThrow(getSource(), getId());
            mScriptEngine.setTag(ExecutionConfig.getTag(), getConfig());
            return mScriptEngine;
        }

        @Override
        public ScriptEngine getEngine() {
            return mScriptEngine;
        }

    }


}
