package com.stardust.autojs.script;

import androidx.annotation.NonNull;

import java.io.Reader;
import java.io.StringReader;

/**
 * QuickJS 字节码脚本源：把「编译好的字节码」当成一种脚本源交给 QuickJS 引擎执行。
 *
 * <p>与 {@link CompiledJavaScriptSource}（Rhino 编译类）对应，只是载荷换成 QuickJS
 * 自己的字节码格式（`JS_WriteObject(..., JS_WRITE_OBJ_BYTECODE)` 产物）。
 * 文本接口返回空串，真正的执行在 {@code QuickJsJavaScriptEngine} 里。
 */
public class QuickJsBytecodeSource extends JavaScriptSource {

    private final byte[] mBytecode;

    public QuickJsBytecodeSource(String name, byte[] bytecode) {
        super(name);
        mBytecode = bytecode;
    }

    public byte[] getBytecode() {
        return mBytecode;
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
        return "QuickJsBytecodeSource{" + getName() + ", bytes=" + mBytecode.length + "}";
    }
}
