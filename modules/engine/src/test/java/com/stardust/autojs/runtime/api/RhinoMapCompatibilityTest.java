package com.stardust.autojs.runtime.api;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

public class RhinoMapCompatibilityTest {
    @Test public void distinctObjectKeysNanAndZeroKeepIdentityAndSize() throws Exception {
        assertEquals("4:2:3:4:true:false:3", evaluate("var a={},b={},m=new Map([[a,1],[b,2],[NaN,3],[-0,4]]);"
                + "m.set(a,9);m.size+':'+m.get(b)+':'+m.get(NaN)+':'+m.get(0)+':'+m.delete(a)+':'+m.has(a)+':'+m.size;"));
    }

    @Test public void iterationsFollowChangesAndStayDoneAfterExhaustion() throws Exception {
        assertEquals("a,c,d:0:true:a,c,d:0", evaluate("var m=new Map([['a',1],['b',2]]),seen=[],it=m.keys();"
                + "seen.push(it.next().value);m.delete('b');m.set('c',3);seen.push(it.next().value);"
                + "m.clear();m.set('d',4);seen.push(it.next().value);it.next();m.set('e',5);"
                + "var done=it.next().done;m.clear();m.set('a',1);m.set('b',2);var order=[];"
                + "m.forEach(function(v,k,owner){order.push(k);if(k==='a'){owner.delete('b');owner.set('c',3);}"
                + "if(k==='c'){owner.clear();owner.set('d',4);}});"
                + "seen.join(',')+':'+Object.keys(m).length+':'+done+':'+order.join(',')+':'+Object.keys(Map.prototype).length;"));
    }

    @Test public void iteratorConstructorAndForEachReceiverWork() throws Exception {
        assertEquals("4:true:8:true", evaluate("var m=new Map([['x',4]]),copy=new Map(m),r={sum:0};"
                + "m.forEach(function(v,k,map){this.sum+=v;this.valid=map===m&&k==='x';},r);"
                + "var keys=m.keys();copy.get('x')+':'+(keys[Symbol.iterator]()===keys)+':'+(r.sum+copy.get('x'))+':'+r.valid;"));
    }

    @Test public void invalidEntryClosesIteratorAndNativeConstructorIsPreserved() throws Exception {
        assertEquals("true:true:true:true", evaluate("var closed=false,source={};source[Symbol.iterator]=function(){return {"
                + "next:function(){return {done:false,value:7};},'return':function(){closed=true;return {};}};};"
                + "var bad=false,noNew=false;try{new Map(source);}catch(e){bad=e instanceof TypeError;}"
                + "try{Map();}catch(e){noNew=e instanceof TypeError;}"
                + "var nativeMap=function NativeMap(){};var other={Map:nativeMap};"
                + "module.exports(other);closed+':'+bad+':'+noNew+':'+(other.Map===nativeMap);"));
    }

    private static String evaluate(String script) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("src/main/assets/modules/rhino-map.js")), StandardCharsets.UTF_8);
        Context cx = Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            cx.evaluateString(scope, "var module={exports:{}};", "setup", 1, null);
            cx.evaluateString(scope, source, "map-module", 1, null);
            return Context.toString(cx.evaluateString(scope, "module.exports(this);" + script, "test", 1, null));
        } finally { Context.exit(); }
    }
}
