package com.stardust.autojs.runtime.api;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

public class PluginsMlkitCompatibilityTest {
    @Test public void absentOfficialPluginUsesOfflineOcrAndForwardsOptions() throws Exception {
        assertEquals("mlkit:7:4:true", evaluate(new FakeRuntime(),
                "var MLKit=plugins.load('org.autojs.autojspro.plugin.mlkit.ocr'); var o=new MLKit();"
                + "var r=o.detect({id:7},{max:4}); o.release(); backend+':'+r[0].text+':'+max+':'+o.isReleased();"));
    }

    @Test public void installedOfficialPluginRetainsItsOwnImplementation() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.plugins.missing = false;
        assertEquals("installed:0", evaluate(runtime,
                "plugins.load('org.autojs.autojspro.plugin.mlkit.ocr')+':'+created;"));
    }

@Test public void absentHrapsOcrPluginFallsBackToBuiltInInstance() throws Exception {
        // 真实脚本：analysisChartTools.js 顶层就 $plugins.load("com.hraps.ocr")，装不上会整脚本失败；
        // 且它按插件文档传 Bitmap（这里用带 getBitmap() 的对象模拟）与第二参 mode。
        assertEquals("hraps:true:7:1:true", evaluate(new FakeRuntime(),
                "var ocr=plugins.load('com.hraps.ocr');"
                + "var image={getBitmap:function(){return {id:7};},detect:null};"
                + "var r=ocr.detect(image.getBitmap?image.getBitmap():image,1);"
                + "var text=r[0].text; var shim=ocr.__aijsBuiltInShim===true;"
                + "ocr.end(); 'hraps:'+shim+':'+text+':'+mode+':'+ocr.isReleased();"));
    }

    @Test public void absentTomatoOcrPluginFallsBackToBuiltInConstructor() throws Exception {
        // tomato 的用法是 `new ($plugins.load("com.tomato.ocr"))()` 再调用 end()。
        assertEquals("tomato:true:1", evaluate(new FakeRuntime(),
                "var C=plugins.load('com.tomato.ocr'); var o=new C();"
                + "var r=o.detect({id:1},{max:1}); o.end();"
                + "'tomato:'+(o.__aijsBuiltInShim===true)+':'+r[0].text;"));
    }

    @Test public void installedHrapsPluginIsNotReplacedByShim() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        runtime.plugins.missing = false;
        assertEquals("installed:0", evaluate(runtime,
                "plugins.load('com.hraps.ocr')+':'+created;"));
    }

    @Test public void unrelatedMissingOcrLikePluginStillFails() throws Exception {
        assertEquals("true:0", evaluate(new FakeRuntime(),
                "var failed=false; try { plugins.load('com.other.ocr'); }"
                + "catch(e){failed=String(e).indexOf('NameNotFoundException')>=0;} failed+':'+created;"));
    }


    @Test public void unrelatedMissingPluginStillFails() throws Exception {
        assertEquals("true:0", evaluate(new FakeRuntime(),
                "var failed=false; try { plugins.load('other.plugin'); }"
                + "catch(e){failed=String(e).indexOf('NameNotFoundException')>=0;} failed+':'+created;"));
    }

    private static String evaluate(FakeRuntime runtime,String script) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("src/main/assets/modules/__plugins__.js")),
                StandardCharsets.UTF_8);
        Context cx = Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope,"runtime",Context.javaToJS(runtime,scope));
            cx.evaluateString(scope,"var module={exports:{}}; var created=0,backend='',max=0,mode=0,released=false;"
                    + "var $ocr={create:function(options){created++;backend=options.backend;return {"
                    + "detect:function(image,options){max=(options&&options.max)||0;mode=(typeof options==='number'?options:0);return [{text:String(image.id)}];},"
                    + "release:function(){released=true;},isReleased:function(){return released;}};}};"
                    + "function require(path){return function(instance){return 'installed';};}","setup",1,null);
            cx.evaluateString(scope,source,"plugins-module",1,null);
            return Context.toString(cx.evaluateString(scope,
                    "var plugins=module.exports(runtime,this);"+script,"test",1,null));
        } finally { Context.exit(); }
    }

    public static class FakeRuntime { public final FakePlugins plugins = new FakePlugins(); }
    public static class FakePlugins {
        public boolean missing = true;
        public FakePlugin load(String packageName) {
            if (missing) throw new IllegalStateException("NameNotFoundException: "+packageName);
            return new FakePlugin();
        }
    }
    public static class FakePlugin {
        public String getMainScriptPath() { return "installed.js"; }
        public Object unwrap() { return this; }
    }
}
