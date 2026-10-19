package com.stardust.autojs.runtime.api.remote;

import org.json.JSONObject;
import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RemoteUiSelectorRegexTest {
    private String wire(RemoteUiSelector selector) throws Exception {
        Field field = RemoteUiSelector.class.getDeclaredField("mRemote");
        field.setAccessible(true);
        return ((JSONObject) field.get(selector)).getString("textMatches");
    }

    @Test public void rhinoOverloadPreservesRegexpSourceAndFlags() throws Exception {
        Context context = Context.enter();
        try {
            Scriptable scope = context.initStandardObjects();
            RemoteUiSelector selector = new RemoteUiSelector();
            ScriptableObject.putProperty(scope, "selector", Context.javaToJS(selector, scope));
            context.evaluateString(scope, "selector.textMatches(/^点我-test$/im)", "selector-regression", 1, null);
            assertTrue(Pattern.compile(wire(selector)).matcher("点我-TEST").matches());
        } finally { Context.exit(); }
    }

    @Test public void stringPatternsKeepLiteralSlashes() throws Exception {
        RemoteUiSelector selector = new RemoteUiSelector().textMatches("/abc/");
        assertEquals("/abc/", wire(selector));
    }

    @Test public void javaPatternsKeepCaseAndMultilineFlags() throws Exception {
        RemoteUiSelector selector = new RemoteUiSelector().textMatches((Object)
                Pattern.compile("^hello$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
        assertTrue(Pattern.compile(wire(selector)).matcher("before\nHELLO\nafter").find());
    }
}
