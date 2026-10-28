package com.stardust.autojs.engine;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
/** Preserve the original script/API error across the reflective automator boundary. */
final class AutomatorInvocation {
    private AutomatorInvocation() {}
    static Object invoke(Method method,Object target,Object[] arguments) throws Exception {
        try { return method.invoke(target,arguments); }
        catch(InvocationTargetException wrapper) {
            Throwable cause=wrapper.getCause();
            if(cause instanceof Exception) throw (Exception)cause;
            if(cause instanceof Error) throw (Error)cause;
            throw wrapper;
        }
    }
}
