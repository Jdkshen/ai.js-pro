package com.stardust.autojs.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.RhinoException;

/**
 * {@link EngineSyntaxHint} 的行为：只在「源码有反引号 + 编译失败」时追加提示，
 * 且必须原样保留原来的报错文本与行号。
 *
 * <p>官方 Rhino 1.7.14.1 已原生支持模板字符串，因此这些用例需要一个「真语法错」
 * 才会触发编译失败路径（模板字符串本身不再会报错）。
 */
public class EngineSyntaxHintTest {

    /** 反引号在第 3 行；第 6 行留了一个真语法错；Rhino 报错时给出的行号不能被我们的包装改掉。 */
    private static final String BACKTICK_SCRIPT = "var a = 1;\nvar b = 2;\nui.layout(`\n<frame/>\n`);\nvar broken = ;\n";

    private static RhinoException compileFailure(String script) {
        Context context = Context.enter();
        try {
            context.setLanguageVersion(Context.VERSION_ES6);
            context.compileString(script, "糖心日记.js", 1, null);
            throw new AssertionError("这份源码本该编译失败：" + script);
        } catch (RhinoException e) {
            return e;
        } finally {
            Context.exit();
        }
    }

    @Test
    public void backtickScriptGetsActionableHint() {
        RhinoException original = compileFailure(BACKTICK_SCRIPT);
        RhinoException decorated = EngineSyntaxHint.decorate(original, BACKTICK_SCRIPT);

        String message = decorated.getMessage();
        assertTrue("应带上提示：" + message, message.contains(EngineSyntaxHint.HINT));
        assertTrue("应保留脚本名：" + message, message.contains("糖心日记.js"));
        assertEquals("行号不能被改掉", original.lineNumber(), decorated.lineNumber());
        assertEquals("源码名不能被改掉", original.sourceName(), decorated.sourceName());
        // 剥后缀的那一步不能把原文吃掉
        assertFalse("原文应还在：" + message,
                message.startsWith(" —— " + EngineSyntaxHint.HINT));
    }

    @Test
    public void hintIsNotAppendedTwice() {
        RhinoException once = EngineSyntaxHint.decorate(compileFailure(BACKTICK_SCRIPT),
                BACKTICK_SCRIPT);
        RhinoException twice = EngineSyntaxHint.decorate(once, BACKTICK_SCRIPT);

        assertSame("已经带过提示就不该再包一层", once, twice);
    }

    @Test
    public void sourceWithoutBacktickKeepsTheOriginalError() {
        String script = "var a = ;\n";
        RhinoException original = compileFailure(script);

        assertSame(original, EngineSyntaxHint.decorate(original, script));
        assertSame(original, EngineSyntaxHint.decorate(original, null));
    }

    @Test
    public void decoratedMessageKeepsRhinoLocationSuffix() {
        RhinoException decorated = EngineSyntaxHint.decorate(compileFailure(BACKTICK_SCRIPT),
                BACKTICK_SCRIPT);

        // Rhino 自己拼的位置是「原文 (source#line)」，追加提示后这个约定仍要成立
        String suffix = "(" + decorated.sourceName() + "#" + decorated.lineNumber() + ")";
        assertTrue("应保留 Rhino 的位置后缀：" + decorated.getMessage(),
                decorated.getMessage().endsWith(suffix));
    }

    /** 手工构造的异常也要保住行号（构造新异常时最容易把位置丢掉）。 */
    @Test
    public void lineNumberSurvivesTheRepackage() {
        RhinoException manual = new EvaluatorException("boom", "a.js", 7, null, 3);
        RhinoException decorated = EngineSyntaxHint.decorate(manual, "var a = `x`;");

        assertEquals(7, decorated.lineNumber());
        assertEquals(3, decorated.columnNumber());
        assertEquals("a.js", decorated.sourceName());
        assertTrue(decorated.getMessage().contains(EngineSyntaxHint.HINT));
    }

    /** QuickJS 报了 XML 字面量的语法错时，要给「只有 Rhino 支持」这条出路。 */
    @Test
    public void quickJsXmlLiteralErrorGetsHint() {
        QuickJsException original = new QuickJsException(
                "SyntaxError: unexpected token in expression: '<'\n"
                        + "    at /sdcard/脚本/界面模板一.js:6:5");
        QuickJsException decorated = EngineSyntaxHint.decorateQuickJs(original);

        assertTrue("应带上提示：" + decorated.getMessage(),
                decorated.getMessage().contains("只有 Rhino 引擎支持"));
        assertTrue("原报错要保留：" + decorated.getMessage(),
                decorated.getMessage().contains("unexpected token in expression: '<'"));
        assertSame("已经带过提示就不该再包一层", decorated,
                EngineSyntaxHint.decorateQuickJs(decorated));
    }

    /** 无关的 QuickJS 报错不许加这句提示。 */
    @Test
    public void otherQuickJsErrorsAreLeftAlone() {
        QuickJsException original = new QuickJsException("SyntaxError: unexpected end of input");
        assertSame(original, EngineSyntaxHint.decorateQuickJs(original));
    }
}
