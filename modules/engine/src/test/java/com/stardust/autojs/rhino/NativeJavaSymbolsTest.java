package com.stardust.autojs.rhino;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import static org.junit.Assert.assertEquals;

public class NativeJavaSymbolsTest {
    @Test public void distinctSymbolsSupportReadWriteHasAndDelete() {
        assertEquals("3:7:true:false:7", evaluate("var a=Symbol('private'),b=Symbol('private');"
                + "host[a]=3;host[b]=7;var before=a in host;var first=host[a];delete host[a];"
                + "first+':'+host[b]+':'+before+':'+(a in host)+':'+host[b];"));
    }

    @Test public void childSymbolsDoNotOverwriteJavaPrototypeSymbols() {
        assertEquals("2:5:2", evaluate("var key=Symbol('key');host[key]=2;"
                + "var child=Object.create(host);var inherited=child[key];child[key]=5;"
                + "inherited+':'+child[key]+':'+host[key];"));
    }

    @Test public void inheritedSymbolAccessorReceivesNativeJavaReceiver() {
        assertEquals("9:9", evaluate("var key=Symbol('accessor');var holder={};"
                + "Object.defineProperty(holder,key,{get:function(){return this.value;},"
                + "set:function(v){this.value=v;}});host.__proto__=holder;host[key]=9;"
                + "host.value+':'+host[key];"));
    }

    private static String evaluate(String source) {
        Context cx=Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope=cx.initStandardObjects();
            ScriptableObject.putProperty(scope,"host",new NativeJavaObjectWithPrototype(scope,new Bean(),Bean.class));
            return Context.toString(cx.evaluateString(scope,source,"test",1,null));
        } finally {Context.exit();}
    }

    public static class Bean { public int value=4; }
}
