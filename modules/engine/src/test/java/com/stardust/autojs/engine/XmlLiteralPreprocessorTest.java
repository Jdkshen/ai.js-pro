package com.stardust.autojs.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link XmlLiteralPreprocessor}：把 Auto.js 的 XML 字面量包成模板字符串，
 * 让没有 E4X 的 QuickJS 也能跑，且**不改动行号**。
 */
public class XmlLiteralPreprocessorTest {

    @Test
    public void wrapsXmlLiteralIntoATemplateString() {
        String script = "ui.layout(\n    <vertical>\n    </vertical>\n);\n";
        assertEquals("ui.layout(\n    `<vertical>\n    </vertical>`\n);\n",
                XmlLiteralPreprocessor.apply(script));
    }

    /** 行数必须一模一样 —— 报错行号、引擎指令位置都靠它。 */
    @Test
    public void keepsTheLineCount() {
        String script = "\"ui\";\nui.layout(\n    <vertical>\n        <text text=\"a\"/>\n"
                + "    </vertical>\n    )\nlet x = 1;\n";
        assertEquals(countLines(script), countLines(XmlLiteralPreprocessor.apply(script)));
    }

    /** ui.inflate 同款写法。 */
    @Test
    public void handlesUiInflateToo() {
        assertEquals("var v = ui.inflate(`<frame id=\"a\"/>`);\n",
                XmlLiteralPreprocessor.apply("var v = ui.inflate(<frame id=\"a\"/>);\n"));
    }

    /** 实参本来就是字符串（或别的表达式）时不许动。 */
    @Test
    public void leavesStringArgumentsAlone() {
        String script = "ui.layout('<frame id=\"root\"/>');\nvar v = ui.inflate(read('./a.xml'));\n";
        assertEquals(script, XmlLiteralPreprocessor.apply(script));
    }

    @Test
    public void leavesScriptsWithoutXmlLiteralsAlone() {
        String script = "var a = 1;\nif (a < 2) console.log('ok');\n";
        assertEquals(script, XmlLiteralPreprocessor.apply(script));
        // 连 '<' 都没有时应当直接原样返回（不做任何扫描）
        assertSame(script, XmlLiteralPreprocessor.apply(script));
    }

    /** XML 里的反引号与 ${ 要转义，否则模板字符串会被提前结束 / 插值。 */
    @Test
    public void escapesTemplateStringHazards() {
        String script = "ui.layout(<text text=\"a`b\"/>);\n";
        String out = XmlLiteralPreprocessor.apply(script);
        assertTrue(out, out.contains("\\`"));
        assertFalse("不该把 ` 原样留在模板字符串里", out.contains("text=\"a`b\"/>"));

        String dollar = XmlLiteralPreprocessor.apply("ui.layout(<text text=\"${x}\"/>);\n");
        assertTrue(dollar, dollar.contains("\\${x}"));
    }

    /** 属性文本里带括号也不能把实参提前切断。 */
    @Test
    public void parensInsideXmlDoNotCutTheArgument() {
        String script = "ui.layout(<text text=\"a(b)\"/>);\nvar after = 1;\n";
        String out = XmlLiteralPreprocessor.apply(script);
        assertTrue(out, out.contains("`<text text=\"a(b)\"/>`"));
        assertTrue("后半截不能丢", out.contains("var after = 1;"));
    }

    /** 用户报的那种混搭脚本：XML 字面量 + 模板字符串，一起改写后应当只剩模板字符串。 */
    @Test
    public void handlesTheMixedScriptThatWasReported() {
        String script = "\"ui\";\nui.layout(\n    <vertical>\n         <text text=\"测试\" />\n"
                + "    </vertical>\n    )\nlet name = \"张三\"\nlog(`姓名：${name}`)\n";
        String out = XmlLiteralPreprocessor.apply(script);

        assertTrue(out, out.contains("`<vertical>"));
        assertFalse("XML 不该再以字面量形式出现", out.contains("ui.layout(\n    <vertical>"));
        assertEquals(countLines(script), countLines(out));
    }

    private static int countLines(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
