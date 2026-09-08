package com.stardust.autojs.engine;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Keeps the core runtime independent from the optional Rhino packaging module. */
public final class RhinoEngineLoader {

    private static final String TAG = "RhinoEngineLoader";
    private static final String PROVIDER = "com.stardust.autojs.rhino.compat.RhinoEngineProvider";
    private static Method sCreate;
    private static Method sWrapException;
    private static boolean sResolved;

    private RhinoEngineLoader() {
    }

    public static synchronized boolean initialize(Context context) {
        if (!resolve()) return false;
        try {
            Class.forName(PROVIDER).getMethod("initialize", Context.class).invoke(null, context);
            return true;
        } catch (ReflectiveOperationException error) {
            Log.e(TAG, "Cannot initialize optional Rhino engine", unwrap(error));
            sCreate = null;
            return false;
        }
    }

    public static JavaScriptEngine create(Context context) {
        if (!resolve()) {
            throw new IllegalStateException("Rhino compatibility engine is not installed");
        }
        try {
            return (JavaScriptEngine) sCreate.invoke(null, context);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot create Rhino compatibility engine", unwrap(error));
        }
    }

    public static Exception wrapExceptionIfNeeded(Exception exception) {
        if (!resolve()) return exception;
        try {
            return (Exception) sWrapException.invoke(null, exception);
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "Cannot apply optional Rhino exception wrapper", unwrap(error));
            return exception;
        }
    }

    private static synchronized boolean resolve() {
        if (sResolved) return sCreate != null;
        sResolved = true;
        try {
            sCreate = Class.forName(PROVIDER).getMethod("create", Context.class);
            sWrapException = Class.forName(PROVIDER).getMethod("wrapExceptionIfNeeded", Exception.class);
        } catch (ReflectiveOperationException error) {
            Log.i(TAG, "Rhino compatibility module is not packaged");
            sCreate = null;
            sWrapException = null;
        }
        return sCreate != null;
    }

    private static Throwable unwrap(ReflectiveOperationException error) {
        return error instanceof InvocationTargetException && error.getCause() != null
                ? error.getCause() : error;
    }
}
