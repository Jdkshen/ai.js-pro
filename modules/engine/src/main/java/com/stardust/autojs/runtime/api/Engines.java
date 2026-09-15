package com.stardust.autojs.runtime.api;

import com.stardust.autojs.ScriptEngineService;
import com.stardust.autojs.engine.JavaScriptEngine;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.script.AutoFileSource;
import com.stardust.autojs.script.JavaScriptFileSource;
import com.stardust.autojs.script.StringScriptSource;

/**
 * Created by Stardust on 2017/8/4.
 */

public class Engines {

    private ScriptEngineService mEngineService;
    private JavaScriptEngine mScriptEngine;
    private ScriptRuntime mScriptRuntime;

    public Engines(ScriptEngineService engineService, ScriptRuntime scriptRuntime) {
        mEngineService = engineService;
        mScriptRuntime = scriptRuntime;
    }

    public ScriptExecution execScript(String name, String script, ExecutionConfig config) {
        return mEngineService.execute(new StringScriptSource(name, script), config);
    }

    public ScriptExecution execScript(String name, String script, ExecutionConfig config,
                                      ScriptExecutionListener listener) {
        return mEngineService.execute(new StringScriptSource(name, script), listener, config);
    }

    public ScriptExecution execScriptFile(String path, ExecutionConfig config) {
        return mEngineService.execute(new JavaScriptFileSource(mScriptRuntime.files.path(path)), config);
    }

    public ScriptExecution execScriptFile(String path, ExecutionConfig config,
                                          ScriptExecutionListener listener) {
        return mEngineService.execute(
                new JavaScriptFileSource(mScriptRuntime.files.path(path)), listener, config);
    }

    public ScriptExecution execAutoFile(String path, ExecutionConfig config) {
        return mEngineService.execute(new AutoFileSource(mScriptRuntime.files.path(path)), config);
    }

    public Object all() {
        return mScriptRuntime.bridges.toArray(mEngineService.getEngines());
    }

    /**
     * PDF §2.12（Pro 9.1.10 新增）：启动一个悬浮控制器（带启动/暂停/日志等按钮），
     * 用于控制给定路径的脚本引擎的启停。`options.runImmediately` 决定是否立即启动。
     */
    public FloatingController startFloatingController(String path, ExecutionConfig config,
                                                       boolean runImmediately) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        return FloatingController.start(mScriptRuntime, path,
                config == null ? new ExecutionConfig() : config, runImmediately);
    }

    public int stopAll() {
        return mEngineService.stopAll();
    }

    public void stopAllAndToast() {
        mEngineService.stopAllAndToast();
    }


    public void setCurrentEngine(JavaScriptEngine engine) {
        if (mScriptEngine != null)
            throw new IllegalStateException();
        mScriptEngine = engine;
    }

    public JavaScriptEngine myEngine() {
        return mScriptEngine;
    }
}
