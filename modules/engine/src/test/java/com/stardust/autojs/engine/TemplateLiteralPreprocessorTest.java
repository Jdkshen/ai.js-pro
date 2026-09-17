package com.stardust.autojs.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link TemplateLiteralPreprocessor}：把模板字符串降级成 ES5 拼接，让 Rhino 也能跑这类脚本。 */
public class TemplateLiteralPreprocessorTest {

    @Test
    public void downgradesSimpleTemplate() {
        assertEquals("var s = 'a' + (x) + 'b';",
                TemplateLiteralPreprocessor.apply("var s = `a${x}b`;"));
    }

    @Test
    public void downgradesPureTextTemplate() {
        assertEquals("var s = 'abc';", TemplateLiteralPreprocessor.apply("var s = `abc`;"));
    }

    @Test
    public void downgradesMultilineXmlTemplate() {
        String script = "var w = floaty.rawWindow(`<frame>\n<text id=\"t\"/>\n</frame>`);";
        String out = TemplateLiteralPreprocessor.apply(script);
        assertNotNull(out);
        assertTrue(out, out.contains("'<frame>\\n<text id=\"t\"/>\\n</frame>'"));
        // 降级后不应再有反引号，否则 Rhino 还是编译不过
        assertTrue(out, out.indexOf('`') < 0);
    }

    @Test
    public void keepsTemplateEscapes() {
        assertEquals("var s = 'a`b$c';", TemplateLiteralPreprocessor.apply("var s = `a\\`b\\$c`;"));
    }

    @Test
    public void expressionWithStringsAndBraces() {
        String out = TemplateLiteralPreprocessor.apply("var s = `${f('}') + g({a: 1})}`;");
        assertNotNull(out);
        assertTrue(out, out.indexOf('`') < 0);
        assertTrue(out, out.contains("f('}')"));
    }

    @Test
    public void escapesSingleQuoteInText() {
        assertEquals("var s = 'it\\'s';", TemplateLiteralPreprocessor.apply("var s = `it's`;"));
    }

    @Test
    public void noTemplateReturnsSameInstance() {
        String script = "var a = 'x';\n";
        assertSame(script, TemplateLiteralPreprocessor.apply(script));
    }

    @Test
    public void ignoresBackticksInsideStringsAndComments() {
        String script = "var a = 'he said `hi`'; /* `x` */ // `y`\n";
        assertEquals(script, TemplateLiteralPreprocessor.apply(script));
    }

    @Test
    public void refusesTaggedTemplate() {
        assertNull(TemplateLiteralPreprocessor.apply("var s = tag`a${b}`;"));
        assertNull(TemplateLiteralPreprocessor.apply("var s = fn()`a`;"));
    }

    @Test
    public void allowsTemplateAfterKeyword() {
        assertEquals("function f() { return 'a' + (b); }",
                TemplateLiteralPreprocessor.apply("function f() { return `a${b}`; }"));
    }

    @Test
    public void refusesUnclosedTemplate() {
        assertNull(TemplateLiteralPreprocessor.apply("var s = `abc;"));
    }

    @Test
    public void downgradesNestedTemplateInsideExpression() {
        String out = TemplateLiteralPreprocessor.apply("var s = `${x ? `${y}` : ''}`;");
        assertNotNull(out);
        assertTrue(out, out.indexOf('`') < 0);
    }

    /** 用户脚本「十字坐标线.js」里的两处真实写法。 */
    @Test
    public void handlesRealWorldScriptFragment() {
        String script = "var board = floaty.rawWindow(`<canvas id=\"canvas\" w=\"*\" h=\"*\" />`);\n"
                + "var ctrl = floaty.window(\n"
                + "    `<frame>\n"
                + "        <text id=\"txt_coord\" text=\"${Math.round(w / 2)},${Math.round(h / 2)}\" />\n"
                + "    </frame>`\n"
                + ");";
        String out = TemplateLiteralPreprocessor.apply(script);
        assertNotNull(out);
        assertTrue(out, out.indexOf('`') < 0);
        assertTrue(out, out.contains("floaty.rawWindow('<canvas id=\"canvas\" w=\"*\" h=\"*\" />')"));
        assertTrue(out, out.contains("(Math.round(w / 2))"));
    }
}
