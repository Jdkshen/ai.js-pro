package com.stardust.autojs.rhino;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ArrowFunctionSupportTest {
    @Test
    public void sameArrowCanRemoveItsJavaListener() {
        Context cx = Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            ScriptRuntime.doTopCall((context, global, self, args) -> {
                Object arrow = cx.evaluateString(scope, "() => 7", "test", 1, null);
                Object add = ArrowFunctionSupport.adaptArgument(cx, scope, arrow);
                Object remove = ArrowFunctionSupport.adaptArgument(cx, scope, arrow);
                assertSame(add, remove);
                List<Runnable> listeners = new ArrayList<>();
                listeners.add((Runnable) Context.jsToJava(add, Runnable.class));
                assertTrue(listeners.remove(Context.jsToJava(remove, Runnable.class)));
                assertTrue(listeners.isEmpty());
                return null;
            }, cx, scope, scope, new Object[0]);
        } finally {
            Context.exit();
        }
    }

    @Test
    public void adapterPreservesArrowLexicalThisAndArguments() {
        Context cx = Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            Object arrow = cx.evaluateString(scope,
                    "var owner = { value: 5, make: function(){ return (n) => this.value + n; } }; owner.make()",
                    "test", 1, null);
            Function adapted = (Function) ArrowFunctionSupport.adaptArgument(cx, scope, arrow);
            assertEquals(12.0, Context.toNumber(adapted.call(cx, scope, scope, new Object[]{7})), 0);
        } finally {
            Context.exit();
        }
    }
}
