package com.stardust.autojs.rhino.compat;

import android.content.Context;

import com.stardust.autojs.engine.JavaScriptEngine;
import com.stardust.autojs.engine.LoopBasedJavaScriptEngine;
import com.stardust.autojs.rhino.InterruptibleAndroidContextFactory;

import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.WrappedException;

import java.io.File;

/** Runtime entry point loaded reflectively only by compat builds. */
public final class RhinoEngineProvider {

    private RhinoEngineProvider() {
    }

    static {
        // 防御式保险丝：让 Context 在任何 ScriptRuntime 家族类之前初始化。
        // Rhino 1.7.15.x 中 Context.emptyArgs = ScriptRuntime.emptyArgs 是普通静态字段初始化；
        // 一旦 ScriptRuntime（或引用它的类，如 AndroidContextFactory 构造路径上的
        // initApplicationClassLoader → Kit.testIfCanLoadRhinoClasses）先初始化，Context 在
        // 嵌套初始化时会把 emptyArgs 永久读成 null，之后所有 bean getter 全部 NPE。
        try {
            org.mozilla.javascript.Context.getCurrentContext();
        } catch (Throwable t) {
            android.util.Log.w("RhinoEngineProvider", "pre-init Context failed", t);
        }
    }

    public static void initialize(Context context) {
        if (!ContextFactory.hasExplicitGlobal()) {
            ContextFactory.initGlobal(new InterruptibleAndroidContextFactory(
                    new File(context.getCacheDir(), "classes")));
        }
    }

    public static JavaScriptEngine create(Context context) {
        return new LoopBasedJavaScriptEngine(context);
    }

    public static Exception wrapExceptionIfNeeded(Exception exception) {
        return org.mozilla.javascript.Context.getCurrentContext() == null
                ? exception : new WrappedException(exception);
    }
}
