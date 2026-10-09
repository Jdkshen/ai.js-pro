package com.stardust.autojs.core.ui.nativeview;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;

public class NativeViewFunctionTest {
    @Test
    public void adaptedMethodSupportsCallApplyAndBind() throws Exception {
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            Function original = cx.compileFunction(scope,
                    "function(n){ return this.value + n; }", "test", 1, null);
            Class<?> type = Class.forName(NativeView.class.getName() + "$AdaptedFunction");
            Constructor<?> ctor = type.getDeclaredConstructor(Function.class);
            ctor.setAccessible(true);
            ScriptableObject.putProperty(scope, "method", ctor.newInstance(original));
            Object result = cx.evaluateString(scope,
                    "[method.call({value:5},7), method.apply({value:6},[7]), method.bind({value:7})(7)].join(',')",
                    "test", 1, null);
            assertEquals("12,13,14", Context.toString(result));
        } finally {
            Context.exit();
        }
    }
}
