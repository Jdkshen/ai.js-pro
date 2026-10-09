package com.stardust.autojs.sample;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

public class TextFileIntentSampleTest {
    private static final String SAMPLE = "../../apps/app/src/main/assets/sample/Rhino 引擎/任务/读取文本文件.js";

    @Test
    public void viewStreamAndClipDataReadTheirUris() throws Exception {
        assertEquals("data/closed/file", run("'data'", "null", "null", false));
        assertEquals("extra/closed/file", run("null", "'extra'", "null", false));
        assertEquals("clip/closed/file", run("null", "null",
                "{getItemCount:function(){return 1},getItemAt:function(){return {getUri:function(){return 'clip'}}}}", false));
    }

    @Test
    public void missingUriShowsSetupAndReadFailureClosesStream() throws Exception {
        assertEquals("none/open/setup", run("null", "null", "null", false));
        assertEquals("data/closed/read failed", run("'data'", "null", "null", true));
    }

    private static String run(String data, String extra, String clip, boolean failRead) throws Exception {
        Context cx = Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            String setup = "var opened='none',closed='open',result='';"
                    + "var mockIntent={getData:function(){return " + data + "},getParcelableExtra:function(k){"
                    + "if(k!=='stream')throw Error('wrong key');return " + extra + "},getClipData:function(){return " + clip + "}};"
                    + "var $engines={myEngine:function(){return {execArgv:{intent:mockIntent}}}};"
                    + "var android={content:{Intent:{EXTRA_STREAM:'stream'}}};"
                    + "var context={contentResolver:{openInputStream:function(uri){opened=uri;return {close:function(){closed='closed'}}}}};"
                    + "var $files={read:function(stream){" + (failRead ? "throw Error('read failed');" : "return 'text';") + "}};"
                    + "function alert(title,content){result=content==='text'?'file':'setup'}"
                    + "function exit(){throw 'exit'};";
            cx.evaluateString(scope, setup, "setup", 1, null);
            String sample = new String(Files.readAllBytes(Paths.get(SAMPLE)), StandardCharsets.UTF_8);
            try {
                cx.evaluateString(scope, sample, "sample", 1, null);
            } catch (JavaScriptException error) {
                Object value = error.getValue();
                if (!"exit".equals(Context.toString(value))) {
                    if (!(value instanceof Scriptable)) throw error;
                    ScriptableObject.putProperty(scope, "result",
                            ScriptableObject.getProperty((Scriptable) value, "message"));
                }
            }
            return Context.toString(cx.evaluateString(scope, "opened+'/'+closed+'/'+result", "result", 1, null));
        } finally {
            Context.exit();
        }
    }
}
