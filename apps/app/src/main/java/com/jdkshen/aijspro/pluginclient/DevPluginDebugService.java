package com.jdkshen.aijspro.pluginclient;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;
import com.stardust.autojs.rhino.debug.DebugCallback;
import com.stardust.autojs.rhino.debug.Debugger;
import com.stardust.autojs.rhino.debug.Dim;
import com.stardust.autojs.script.StringScriptSource;

import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.model.script.Scripts;
import com.jdkshen.aijspro.ui.edit.debug.DebuggerSingleton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研 VS Code 扩展的断点调试会话（Rhino / 第一代 API）。
 *
 * <p>复用手机编辑器里那套调试器（{@code com.stardust.autojs.rhino.debug.Debugger}）：
 * run → attach → updateSourceText 里挂断点 → enterInterrupt 回调暂停。这里把事件/控制
 * 桥到扩展协议（debug_event 事件 + debug_* 命令），调用栈与变量通过 {@link Dim} 读取。
 *
 * <p>V1 限制：同一时间只有一个调试会话（Debugger 是应用级单例，编辑器调试与 VS Code
 * 调试互斥，后 attach 的会顶掉前一个）；QuickJS 引擎暂不支持断点（命令会返回错误）。
 */
public final class DevPluginDebugService implements DebugCallback {

    public interface EventSink {
        void sendEvent(String event, JsonObject payload);
    }

    public static final String DEBUG_RUN = "debug_run";
    public static final String DEBUG_RESUME = "debug_resume";
    public static final String DEBUG_SET_BREAKPOINTS = "debug_set_breakpoints";
    public static final String DEBUG_EVALUATE = "debug_evaluate";
    public static final String DEBUG_VARIABLES = "debug_variables";
    public static final String DEBUG_TERMINATE = "debug_terminate";

    private static final String LOG_TAG = "DevPluginDebug";
    private static final int MAX_VARIABLES = 300;

    private static final DevPluginDebugService INSTANCE = new DevPluginDebugService();

    public static DevPluginDebugService getInstance() {
        return INSTANCE;
    }

    public static boolean isDebugCommand(String command) {
        return DEBUG_RUN.equals(command) || DEBUG_RESUME.equals(command)
                || DEBUG_SET_BREAKPOINTS.equals(command) || DEBUG_EVALUATE.equals(command)
                || DEBUG_VARIABLES.equals(command) || DEBUG_TERMINATE.equals(command);
    }

    private final Debugger mDebugger = DebuggerSingleton.get();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Object mLock = new Object();
    private final List<Integer> mBreakpoints = new ArrayList<>();
    private final Map<Integer, Object> mVariableRefs = new HashMap<>();

    private volatile EventSink mSink;
    private volatile ScriptExecution mExecution;
    private volatile Dim.SourceInfo mSourceInfo;
    private volatile Dim.StackFrame mLastFrame;
    private volatile boolean mStopped;
    private volatile boolean mFinished = true;
    private volatile String mLastAction = "continue";
    private int mNextRef = 1;
    private ScriptExecutionListener mGlobalListener;

    private DevPluginDebugService() {
    }

    // ---- 命令（对外） ----

    public void start(final EventSink sink, final String name, final String script,
            final List<Integer> breakpoints) {
        mMainHandler.post(() -> doStart(sink, name, script, breakpoints));
    }

    public void resume(final String action) {
        mMainHandler.post(() -> doResume(action == null ? "continue" : action));
    }

    public void setBreakpoints(final List<Integer> lines) {
        mMainHandler.post(() -> {
            synchronized (mLock) {
                mBreakpoints.clear();
                if (lines != null) {
                    mBreakpoints.addAll(lines);
                }
            }
            Dim.SourceInfo sourceInfo = mSourceInfo;
            if (sourceInfo != null) {
                applyBreakpoints(sourceInfo);
            }
        });
    }

    /** 在指定栈帧求值；未暂停时返回 null。 */
    public String evaluate(final String expr, final int frameIndex) {
        if (!mStopped || !mDebugger.isAttached()) {
            return null;
        }
        try {
            Dim dim = mDebugger.dim();
            dim.contextSwitch(frameIndex);
            return dim.eval(expr);
        } catch (Throwable error) {
            Log.w(LOG_TAG, "evaluate failed", error);
            return null;
        }
    }

    /** 列出变量：ref=0 为当前栈帧作用域，其余为之前返回过的对象引用；未暂停返回 null。 */
    public JsonArray variables(final int ref) {
        if (!mStopped || !mDebugger.isAttached()) {
            return null;
        }
        Object scope;
        if (ref == 0) {
            Dim.StackFrame frame = mLastFrame;
            scope = frame == null ? null : frame.scope();
        } else {
            scope = mVariableRefs.get(ref);
        }
        if (scope == null) {
            return null;
        }
        JsonArray result = new JsonArray();
        try {
            Dim dim = mDebugger.dim();
            Object[] ids = dim.getObjectIds(scope);
            if (ids == null) {
                return result;
            }
            int count = 0;
            for (Object id : ids) {
                if (count++ >= MAX_VARIABLES) {
                    break;
                }
                JsonObject item = new JsonObject();
                item.addProperty("name", String.valueOf(id));
                Object value;
                try {
                    value = dim.getObjectProperty(scope, id);
                } catch (Throwable error) {
                    continue;
                }
                item.addProperty("value", dim.objectToString(value));
                Object[] children = null;
                try {
                    children = dim.getObjectIds(value);
                } catch (Throwable ignored) {
                }
                if (children != null) {
                    int childRef = mNextRef++;
                    mVariableRefs.put(childRef, value);
                    item.addProperty("ref", childRef);
                }
                result.add(item);
            }
        } catch (Throwable error) {
            Log.w(LOG_TAG, "variables failed", error);
        }
        return result;
    }

    public void terminate() {
        mMainHandler.post(() -> {
            stopExecution();
            finish(true);
        });
    }

    public boolean isActive() {
        return !mFinished;
    }

    // ---- 内部 ----

    private void doStart(EventSink sink, String name, String script, List<Integer> breakpoints) {
        stopExecution();
        finish(false);
        mFinished = false;
        mSink = sink;
        mStopped = false;
        mLastFrame = null;
        mNextRef = 1;
        synchronized (mLock) {
            mBreakpoints.clear();
            if (breakpoints != null) {
                mBreakpoints.addAll(breakpoints);
            }
            mVariableRefs.clear();
        }
        try {
            mGlobalListener = new ScriptExecutionListener() {
                @Override
                public void onStart(ScriptExecution execution) {
                }

                @Override
                public void onSuccess(ScriptExecution execution, Object result) {
                    onExecutionFinished(execution);
                }

                @Override
                public void onException(ScriptExecution execution, Throwable e) {
                    onExecutionFinished(execution);
                }
            };
            AutoJs.getInstance().getScriptEngineService()
                    .registerGlobalScriptExecutionListener(mGlobalListener);
            mDebugger.setDebugCallback(this);
            ScriptExecution execution = Scripts.INSTANCE
                    .run(new StringScriptSource("[debug]" + (name == null ? "debug.js" : name), script));
            if (execution == null) {
                emit("error", errorPayload("脚本没有启动（执行服务拒绝）"));
                finish(true);
                return;
            }
            mExecution = execution;
            mDebugger.attach(execution);
            JsonObject payload = new JsonObject();
            payload.addProperty("name", name);
            emit("started", payload);
            if (mDebugger.isAttached()) {
                // 断点会在 updateSourceText（Dim 拿到源码）时统一挂上。
                Dim.SourceInfo sourceInfo = mSourceInfo;
                if (sourceInfo != null) {
                    applyBreakpoints(sourceInfo);
                }
            }
        } catch (Throwable error) {
            Log.w(LOG_TAG, "debug start failed", error);
            emit("error", errorPayload(String.valueOf(error.getMessage())));
            finish(true);
        }
    }

    private void doResume(String action) {
        if (mFinished || !mDebugger.isAttached()) {
            return;
        }
        mLastAction = action;
        mStopped = false;
        try {
            switch (action) {
                case "next":
                    mDebugger.stepOver();
                    break;
                case "stepIn":
                    mDebugger.stepInto();
                    break;
                case "stepOut":
                    mDebugger.stepOut();
                    break;
                default:
                    mDebugger.resume();
            }
        } catch (Throwable error) {
            Log.w(LOG_TAG, "resume failed", error);
        }
    }

    private void stopExecution() {
        ScriptExecution execution = mExecution;
        mExecution = null;
        if (execution == null) {
            return;
        }
        try {
            ScriptEngine engine = execution.getEngine();
            if (engine != null && !engine.isDestroyed()) {
                engine.forceStop();
            }
        } catch (Throwable error) {
            Log.w(LOG_TAG, "force stop failed", error);
        }
    }

    private void onExecutionFinished(ScriptExecution execution) {
        ScriptExecution current = mExecution;
        if (current == null || (execution != null && execution.getId() != current.getId())) {
            return;
        }
        mMainHandler.post(() -> finish(true));
    }

    private void finish(boolean sendTerminated) {
        if (mFinished) {
            return;
        }
        mFinished = true;
        mStopped = false;
        try {
            if (mDebugger.isAttached()) {
                mDebugger.detach();
            }
        } catch (Throwable error) {
            Log.w(LOG_TAG, "detach failed", error);
        }
        mDebugger.setDebugCallback(null);
        ScriptExecutionListener listener = mGlobalListener;
        mGlobalListener = null;
        if (listener != null) {
            try {
                AutoJs.getInstance().getScriptEngineService()
                        .unregisterGlobalScriptExecutionListener(listener);
            } catch (Throwable ignored) {
            }
        }
        mExecution = null;
        mSourceInfo = null;
        mLastFrame = null;
        synchronized (mLock) {
            mVariableRefs.clear();
        }
        if (sendTerminated) {
            emit("terminated", new JsonObject());
        }
        mSink = null;
    }

    private void emit(String event, JsonObject payload) {
        EventSink sink = mSink;
        if (sink != null) {
            sink.sendEvent(event, payload == null ? new JsonObject() : payload);
        }
    }

    private static JsonObject errorPayload(String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("message", message == null ? "未知错误" : message);
        return payload;
    }

    private void applyBreakpoints(Dim.SourceInfo sourceInfo) {
        try {
            sourceInfo.removeAllBreakpoints();
            synchronized (mLock) {
                for (int line : mBreakpoints) {
                    if (line > 0 && sourceInfo.breakableLine(line)) {
                        sourceInfo.breakpoint(line, true);
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(LOG_TAG, "apply breakpoints failed", error);
        }
    }

    // ---- DebugCallback（Dim 回调，可能来自任意线程） ----

    @Override
    public void updateSourceText(Dim.SourceInfo sourceInfo) {
        mSourceInfo = sourceInfo;
        applyBreakpoints(sourceInfo);
    }

    @Override
    public void enterInterrupt(Dim.StackFrame lastFrame, String threadTitle, String alertMessage) {
        if (mFinished) {
            return;
        }
        mStopped = true;
        mLastFrame = lastFrame;
        JsonObject payload = new JsonObject();
        String reason = mLastAction != null && !"continue".equals(mLastAction) ? "step" : "breakpoint";
        payload.addProperty("reason", reason);
        try {
            payload.addProperty("line", lastFrame.getLineNumber());
            payload.addProperty("url", lastFrame.getUrl());
            payload.addProperty("name", shortName(lastFrame.getUrl()));
        } catch (Throwable ignored) {
        }
        try {
            JsonArray frames = new JsonArray();
            Dim dim = mDebugger.dim();
            Dim.ContextData contextData = dim.currentContextData();
            int count = contextData == null ? 0 : contextData.frameCount();
            for (int i = 0; i < count && i < 50; i++) {
                Dim.StackFrame frame = contextData.getFrame(i);
                if (frame == null) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("index", i);
                item.addProperty("name", frame.getFunctionName() == null ? "<anonymous>" : frame.getFunctionName());
                item.addProperty("line", frame.getLineNumber());
                item.addProperty("url", frame.getUrl());
                frames.add(item);
            }
            payload.add("frames", frames);
        } catch (Throwable error) {
            Log.w(LOG_TAG, "stack build failed", error);
        }
        emit("stopped", payload);
    }

    private static String shortName(String url) {
        if (url == null) {
            return "script.js";
        }
        int index = url.lastIndexOf('/');
        return index >= 0 ? url.substring(index + 1) : url;
    }
}
