package com.stardust.autojs.runtime.api;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

public class ThreadsArrowCallbacksTest {
    @Test public void startConvertsArrowToJavaRunnable() throws Exception {
        assertEquals("1", evaluate("threads.start(()=>7); runtime.threads.accepted;"));
    }

    @Test public void poolConvertsArrowAndNormalFunction() throws Exception {
        assertEquals("2", evaluate("var pool=threads.pool({corePoolSize:1,maxPoolSize:2});"
                + "pool.execute(()=>2).execute(function(){return 3;}); runtime.threads.poolValue.accepted;"));
    }

    private static String evaluate(String script) throws Exception {
        String module = new String(Files.readAllBytes(Paths.get("src/main/assets/modules/__threads__.js")),
                StandardCharsets.UTF_8);
        Context cx = Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope,"runtime",Context.javaToJS(new FakeRuntime(),scope));
            cx.evaluateString(scope,"var module={exports:{}}; var global=this;"
                    + "global.Promise=function(){}; var result=0;", "setup",1,null);
            cx.evaluateString(scope,module,"threads-module",1,null);
            return Context.toString(cx.evaluateString(scope,
                    "var threads=module.exports(runtime,global);"+script,"test",1,null));
        } finally { Context.exit(); }
    }

    public static class FakeRuntime { public final FakeThreads threads = new FakeThreads(); }
    public static class FakeThreads {
        public int accepted;
        public final FakePool poolValue = new FakePool();
        public Object start(Runnable runnable) { if (runnable != null) accepted++; return runnable; }
        public FakePool createPool(int core,int max,long keepAlive) { return poolValue; }
    }
    public static class FakePool {
        public int accepted;
        public void execute(Runnable runnable) { if (runnable != null) accepted++; }
    }
}
