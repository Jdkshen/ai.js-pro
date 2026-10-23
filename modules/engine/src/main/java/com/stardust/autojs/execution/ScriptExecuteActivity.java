package com.stardust.autojs.execution;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;

import com.stardust.autojs.ScriptEngineService;
import com.stardust.autojs.core.console.ScriptRunLog;
import com.stardust.autojs.core.eventloop.EventEmitter;
import com.stardust.autojs.core.eventloop.SimpleEvent;
import com.stardust.autojs.core.looper.MainThreadCallback;
import com.stardust.autojs.core.ui.inflater.CompiledProjectResources;
import com.stardust.autojs.engine.JavaScriptEngine;
import com.stardust.autojs.engine.LoopBasedJavaScriptEngine;
import com.stardust.autojs.engine.Node24JavaScriptEngine;
import com.stardust.autojs.engine.QuickJsJavaScriptEngine;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.engine.ScriptEngineManager;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.exception.ScriptException;
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
    private ScriptRunLog mRunLog;
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
        mExecutionListener = mScriptExecution.getListener();
        try {
            mScriptEngine = mScriptExecution.createEngine(this);
        } catch (Throwable error) {
            // 引擎创建/注册失败也要走脚本错误回调，不能让 Activity 启动异常把主进程带崩。
            rejectUiExecution(error);
            return;
        }
        // task-59 B：Node24 UI 不再硬拒绝。Node UI 的 Activity/界面由 :node24 进程里的
        // NodeUiHost 用 DynamicLayoutInflater inflate 到全屏 overlay（A 的 prelude 注入
        // ui 模块 → __autojsHost → NodeUiHost）；这里只负责让 Node24 引擎正常运行。
        // 没有真实 Activity 生命周期（无 getWindow/返回键），第一阶段见 task-59 报告。
        mRunLog = ScriptRunLog.attach(mScriptEngine, mScriptSource, this);
        mRuntime = ((JavaScriptEngine) mScriptEngine).getRuntime();
        // Node24 不初始化 ScriptRuntime（getRuntime() 为 null，见 Node24JavaScriptEngine），
        // 不能构造 EventEmitter；emit() 已做空保护。
        if (mRuntime != null) {
            mEventEmitter = new EventEmitter(mRuntime.bridges);
        }
        runScript();
        emit("create", savedInstanceState);
    }

    public EventEmitter getEventEmitter() {
        return mEventEmitter;
    }

    /**
     * UI 模式下如果挂了工程资源包（{@code build/project-res.apk}），这里返回**合并**后的资源表（宿主 + 工程）。
     *
     * <p>为什么必须换：{@code AnimationUtils.loadAnimation(activity, ui.R.anim.ball)}、
     * {@code activity.resources.getColor(ui.R.color.x)} 这类调用只认真 resource id，
     * 而它们拿的是 **Activity 自己的** Resources —— 不换掉就永远解析不到工程里的资源。
     * 挂载是懒的（脚本调 {@code ui.useAndroidResources()} 时才发生），所以这里每次读一下，没挂就原样返回。
     */
    @Override
    public Resources getResources() {
        Resources overlay = CompiledProjectResources.overlay();
        return overlay == null ? super.getResources() : overlay;
    }

    private void runScript() {
        if (mScriptEngine instanceof QuickJsJavaScriptEngine || mScriptEngine instanceof Node24JavaScriptEngine) {
            // QuickJS / Node24 的脚本必须在引擎自己的线程上跑：native 事件循环会一直占住执行线程
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

    /**
     * 拒绝在界面里执行当前脚本：给监听器报异常并关闭 Activity，绝不让异常冒到主线程。
     *
     * <p>用于两类情况：① 引擎创建/注册失败（{@link ActivityScriptExecution#createEngine} 抛错）；
     * ② Node24 UI 脚本策略（P0）——Node24 没有 Activity 界面桥、也没有 rhino 兼容模块，硬跑会先
     * 报 {@code Cannot find module 'rhino'}、再在 onDestroy 里销毁未初始化的 runtime 把主进程
     * 崩掉（dev-969 真机 FATAL: Unable to destroy activity ScriptExecuteActivity: NPE）。
     *
     * <p>先摘掉 mScriptExecution / mExecutionListener，避免 finish()/onDestroy 再上报一次
     * 成功/异常；引擎（如果已经创建）仍由 onDestroy 正常 destroy，执行表由引擎生命周期回调清理。
     */
    private void rejectUiExecution(Throwable error) {
        String message = error == null || error.getMessage() == null
                ? "脚本无法在界面中启动" : error.getMessage();
        Log.w(LOG_TAG, "reject UI script: " + message, error);
        ScriptExecutionListener listener = mExecutionListener;
        ActivityScriptExecution execution = mScriptExecution;
        mScriptExecution = null;
        mExecutionListener = null;
        if (listener != null && execution != null) {
            try {
                listener.onException(execution, error);
            } catch (Throwable callbackError) {
                Log.w(LOG_TAG, "report UI script rejection failed", callbackError);
            }
        }
        finish();
    }

    @Override
    public void finish() {
        if (mScriptExecution == null || mExecutionListener == null) {
            super.finish();
            return;
        }
        Throwable exception = mScriptEngine == null ? null : mScriptEngine.getUncaughtException();
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
            Throwable failure = null;
            try {
                failure = mScriptEngine.getUncaughtException();
                mScriptEngine.put("activity", null);
                mScriptEngine.setTag("activity", null);
                mScriptEngine.destroy();
            } catch (Throwable error) {
                // P0 防御：destroy 链（engine -> ScriptRuntime.onExit）已对未初始化 runtime 的
                // null 字段做判空；这里再兜一层，Activity 销毁失败绝不能把主进程带崩
                // （dev-969 的 FATAL: Unable to destroy activity ScriptExecuteActivity: NPE）。
                Log.w(LOG_TAG, "destroy engine failed, ignored", error);
            } finally {
                if (mRunLog != null) {
                    try {
                        mRunLog.finish(failure);
                    } catch (Throwable error) {
                        Log.w(LOG_TAG, "finish run log failed, ignored", error);
                    }
                    mRunLog = null;
                }
            }
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

    /**
     * 音量上键 = 停止全部脚本（脚本界面聚焦时的窗口路径）。
     *
     * <p>全局音量键监听依赖无障碍/root（HyperOS 上无障碍设置会被系统重置，物理按键就失效）；
     * 但脚本 Activity 聚焦时这里能直接收到 {@code KEYCODE_VOLUME_UP}，所以不依赖无障碍：
     * 执行宿主停止 hook（:script/:native）+ 主进程 {@link ScriptEngineService#stopAll()}，
     * 然后关闭界面。音量下键等其它键维持原逻辑。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                stopAllByVolumeUp();
            }
            // 按下/抬起都消费：避免同时触发系统音量变化（脚本 UI 聚焦时音量上键专用于停止）。
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void stopAllByVolumeUp() {
        Log.i(LOG_TAG, "VOLUME_UP: stop all scripts (script activity focused)");
        int stopped = 0;
        try {
            stopped = ScriptEngineService.stopAllByVolumeUp();
        } catch (Throwable error) {
            Log.w(LOG_TAG, "VOLUME_UP stop-all failed", error);
        }
        Log.i(LOG_TAG, "VOLUME_UP stop-all finished, main-process engines=" + stopped);
        finish();
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
        if (mEventEmitter == null) {
            // Node24 没有 ScriptRuntime/EventEmitter（task-59）：Activity 生命周期事件不派发。
            return;
        }
        try {
            mEventEmitter.emit(event, (Object[]) args);
        } catch (Exception e) {
            if (mRuntime == null) {
                return;
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                // exit() throws again after stopping the engine. Contain that second
                // throw only at the Android UI callback boundary; host defects propagate.
                MainThreadCallback.terminate(e, mRuntime::exit);
            } else {
                // Background callers still need the exit exception to unwind their script thread.
                mRuntime.exit(e);
            }
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
