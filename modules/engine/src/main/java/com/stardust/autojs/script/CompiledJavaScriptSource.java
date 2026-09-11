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

    public CompiledJavaScriptSource(String name, String className, byte[] classBytes) {
        super(name);
        mClassName = className;
        mClassBytes = classBytes;
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
