package com.stardust.autojs.engine;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import static org.junit.Assert.*;

public class RhinoSyntaxPreprocessorTest {
    @Test public void objectPropertiesReadValuesAndKeepReferenceIdentity() {
        assertEquals("true:7:中文", evaluate("var drawMatches={}; var n=7, 中文='中文';"
                + "var o={drawMatches, n, 中文}; (o.drawMatches===drawMatches)+':'+o.n+':'+o.中文;"));
    }

    @Test public void methodsKeepThisAndArgumentsIncludingNestedObjects() {
        assertEquals("9", evaluate("var a=4; var o={a, nested:{a}, sum(b){return this.a+b;}};"
                + "o.sum(o.nested.a+1);"));
    }

    @Test public void parserDistinguishesBlocksRegexXmlAndComments() {
        assertEquals("3:3", evaluate("var n=3; function f(){n;} if(n){n;}"
                + "var r=/{n}/; var xml=<text value='{n}'/>;"
                + "var o={n // comment containing fake property, fake\n};"
                + "var p={n /* comment */}; o.n+':'+p.n;"));
    }

    @Test public void templatesAndDestructuringContinueWorking() {
        assertEquals("value:5:5", evaluate("var a=5; var o={a}; const {a:b}=o;"
                + "var t=`value:${a}`; t+':'+b;"));
    }

    @Test public void multilinePropertiesKeepLineCountAndSkipStrings() {
        String source="var a=1;\r\nvar o={a\r\n}; var text='{a}';";
        String converted=RhinoSyntaxPreprocessor.apply(source);
        assertEquals(source.chars().filter(c -> c=='\n').count(),
                converted.chars().filter(c -> c=='\n').count());
        assertTrue(converted.contains("'{a}'"));
        assertEquals("1", evaluate(source+"o.a;"));
    }

    @Test public void malformedPropertiesAreNotAcceptedOrRewritten() {
        String[] invalid={"var o={'a'};", "var o={a+1};", "var o={a}; var = ;"};
        for(String source:invalid) assertEquals(source,RhinoSyntaxPreprocessor.apply(source));
    }

    private static String evaluate(String source) {
        Context cx=Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope=cx.initStandardObjects();
            return Context.toString(cx.evaluateString(scope,RhinoSyntaxPreprocessor.apply(source),"test",1,null));
        } finally { Context.exit(); }
    }
}
