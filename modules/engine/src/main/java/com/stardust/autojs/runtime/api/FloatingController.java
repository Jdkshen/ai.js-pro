package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.R;
import com.stardust.autojs.annotation.ScriptInterface;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;
import com.stardust.autojs.runtime.ScriptRuntime;

/**
 * PDF §2.12（Pro 9.1.10 新增）：{@code engines.startFloatingController(path[, config, options])}
 * 的悬浮控制器：带「启动/停止 + 日志 + 关闭」按钮，用于控制给定路径的脚本引擎的启停。
 *
 * <p>文档没写返回值；这里返回控制器对象本身（内含 {@link ScriptExecution}），
 * 脚本侧可以用它做启停/查询，属于「超集」而不是偏离。
 */
public class FloatingController {

    private final ScriptRuntime mRuntime;
    private final String mPath;
    private final ExecutionConfig mConfig;
    private final Floaty.JsResizableWindow mWindow;
    private final Button mToggleButton;
    private final Button mLogButton;

    private ScriptExecution mExecution;
    private boolean mRunning;
    private boolean mLogVisible;

    private final ScriptExecutionListener mListener = new ScriptExecutionListener() {
        @Override
        public void onStart(ScriptExecution execution) {
            updateRunning(true);
        }

        @Override
        public void onSuccess(ScriptExecution execution, Object result) {
            updateRunning(false);
        }

        @Override
        public void onException(ScriptExecution execution, Throwable e) {
            updateRunning(false);
        }
    };

    private FloatingController(ScriptRuntime runtime, String path, ExecutionConfig config) {
        mRuntime = runtime;
        mPath = runtime.files.path(path);
        mConfig = config;
        Context context = new ContextThemeWrapper(mRuntimeContext(), R.style.ScriptTheme);
        View view = View.inflate(context, R.layout.floaty_controller, null);
        TextView title = view.findViewById(R.id.controller_title);
        title.setText(fileName(mPath));
        mToggleButton = view.findViewById(R.id.controller_toggle);
        mLogButton = view.findViewById(R.id.controller_log);
        mToggleButton.setOnClickListener(v -> {
            if (isRunning()) {
                stop();
            } else {
                start();
            }
        });
        mLogButton.setOnClickListener(v -> toggleLog());
        view.findViewById(R.id.controller_close).setOnClickListener(v -> close());
        mWindow = runtime.floaty.window(view);
    }

    /** PDF：`options.runImmediately` —— 是否立即启动脚本引擎（默认 false）。 */
    public static FloatingController start(ScriptRuntime runtime, String path, ExecutionConfig config,
                                           boolean runImmediately) {
        FloatingController controller = new FloatingController(runtime, path, config);
        if (runImmediately) {
            controller.start();
        }
        return controller;
    }

    @ScriptInterface
    public synchronized boolean start() {
        if (mRunning) return false;
        mRunning = true;
        try {
            mExecution = mRuntime.engines.execScriptFile(mPath, mConfig, mListener);
        } catch (Throwable e) {
            mRunning = false;
            throw e;
        }
        updateToggleText();
        return true;
    }

    @ScriptInterface
    public synchronized boolean stop() {
        ScriptExecution execution = mExecution;
        if (!mRunning || execution == null) return false;
        ScriptEngine engine = execution.getEngine();
        if (engine != null) {
            engine.forceStop();
        }
        mRunning = false;
        updateToggleText();
        return true;
    }

    @ScriptInterface
    public synchronized boolean isRunning() {
        return mRunning;
    }

    @ScriptInterface
    public boolean toggleLog() {
        mLogVisible = !mLogVisible;
        if (mLogVisible) {
            mRuntime.console.show();
        } else {
            mRuntime.console.hide();
        }
        updateLogText();
        return mLogVisible;
    }

    @ScriptInterface
    public boolean isLogVisible() {
        return mLogVisible;
    }

    public String getPath() {
        return mPath;
    }

    /** 关闭控制器窗口本身；脚本引擎不受影响（要停脚本请先 {@link #stop()}）。 */
    public void close() {
        mWindow.close();
    }

    private void updateRunning(boolean running) {
        synchronized (this) {
            mRunning = running;
            if (!running) mExecution = null;
        }
        updateToggleText();
    }

    private void updateToggleText() {
        boolean running;
        synchronized (this) {
            running = mRunning;
        }
        final String text = running ? "停止" : "启动";
        mToggleButton.post(() -> mToggleButton.setText(text));
    }

    private void updateLogText() {
        mLogButton.post(() -> mLogButton.setText(mLogVisible ? "收起日志" : "日志"));
    }

    private static Context mRuntimeContext() {
        Context context = GlobalAppContext.get();
        if (context == null) {
            throw new IllegalStateException("应用上下文尚未初始化，无法创建悬浮控制器");
        }
        return context;
    }

    private static String fileName(String path) {
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return index < 0 ? path : path.substring(index + 1);
    }
}
