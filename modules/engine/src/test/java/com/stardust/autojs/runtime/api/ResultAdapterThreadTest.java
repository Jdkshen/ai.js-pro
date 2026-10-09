package com.stardust.autojs.runtime.api;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

public class ResultAdapterThreadTest {
    @Test public void enabledUiThreadUsesContinuation() throws Exception {
        assertEquals("await", route(true,true));
    }

    @Test public void backgroundThreadBlocksEvenWhenUiEngineEnablesContinuation() throws Exception {
        assertEquals("wait", route(false,true));
    }

    @Test public void ordinaryEngineKeepsBlockingWait() throws Exception {
        assertEquals("wait", route(false,false));
    }

    private static String route(boolean uiThread, boolean enabled) throws Exception {
        String source=new String(Files.readAllBytes(Paths.get("src/main/assets/modules/result_adapter.js")),
                StandardCharsets.UTF_8);
        Context cx=Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope=cx.initStandardObjects();
            cx.evaluateString(scope,"var module={exports:{}}; var ui={isUiThread:function(){return "
                    +uiThread+";}}; var continuation={enabled:"+enabled+"};"
                    +"function Promise(){} Promise.prototype.await=function(){return 'await';};"
                    +"Promise.prototype.wait=function(){return 'wait';};","setup",1,null);
            cx.evaluateString(scope,source,"result-adapter",1,null);
            return Context.toString(cx.evaluateString(scope,"module.exports.wait(new Promise());","test",1,null));
        } finally {Context.exit();}
    }
}
