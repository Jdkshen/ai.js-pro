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
