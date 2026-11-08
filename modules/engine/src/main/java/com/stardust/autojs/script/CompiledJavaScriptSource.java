package com.stardust.autojs.script;

import androidx.annotation.NonNull;

import java.io.Reader;
import java.io.StringReader;

/**
 * 编译型脚本源：把「类名 + 类字节」当成一种脚本源交给引擎执行。
 *
 * <p>这样引擎的生命周期（初始化、日志、执行记录、continuation 特性等）与普通源码脚本
 * 完全一致，只是 {@code doExecution} 里换成了「加载类再 exec」。文本接口返回空串，
 * 真正的执行逻辑在 {@code RhinoJavaScriptEngine}。
 */
public class CompiledJavaScriptSource extends JavaScriptSource {

    private final String mClassName;
    private final byte[] mClassBytes;
    private final int mExecutionMode;

    public CompiledJavaScriptSource(String name, String className, byte[] classBytes) {
        this(name, className, classBytes, EXECUTION_MODE_NORMAL);
    }

    /**
     * 原始脚本文件路径。打包加密（编译型）入口没有文本源码，但 Rhino 引擎要在执行前用
     * 「脚本自己的目录」刷新 jvm-npm 的 require 根，否则 `require("./x.js")` 会报
     * "Can not resolve relative module ID ... outside of a module"（`.4` 实测）。
     * 从 native 库内嵌载荷还原时没有文件路径，为 null。
     */
    private final String mScriptPath;

    public CompiledJavaScriptSource(String name, String className, byte[] classBytes, int executionMode) {
        this(name, className, classBytes, executionMode, null);
    }

    public CompiledJavaScriptSource(String name, String className, byte[] classBytes, int executionMode,
                                    String scriptPath) {
        super(name);
        mClassName = className;
        mClassBytes = classBytes;
        mExecutionMode = executionMode;
        mScriptPath = scriptPath;
    }

    public String getScriptPath() {
        return mScriptPath;
    }

    @Override
    protected int parseExecutionMode() {
        return mExecutionMode;
    }

    public String getClassName() {
        return mClassName;
    }

    public byte[] getClassBytes() {
        return mClassBytes;
    }

    @NonNull
    @Override
    public String getScript() {
        // 编译产物没有可读源码；返回空串而不是抛异常，避免日志/预览路径意外崩掉。
        return "";
    }

    @NonNull
    @Override
    public Reader getScriptReader() {
        return new StringReader("");
    }

    @Override
    public String toString() {
        return "CompiledJavaScriptSource{" + getName() + ", class=" + mClassName + "}";
    }
}
