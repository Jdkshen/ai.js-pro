package com.stardust.autojs.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link EngineFallbacks} 的边界：只在「当前引擎连解析都过不去、另一个引擎真的编译得过」时
 * 才该换引擎 —— 两边都用不了的脚本不许来回倒腾。
 */
public class EngineFallbacksTest {

    /** Auto.js 经典的 XML 字面量 UI 写法（Rhino 的 E4X）。 */
    private static final String XML_LAYOUT_SCRIPT =
            "// @engine quickjs\n\"ui\";\n\nui.layout(\n    <frame id=\"root\">\n        <text text=\"hi\"/>\n    </frame>\n);\n";

    /** 官方 Rhino 1.7.14.1 原生支持模板字符串：纯模板脚本不再需要换引擎。 */
    @Test
    public void templateStringScriptNoLongerNeedsQuickJs() {
        assertFalse(EngineFallbacks.shouldSwitchToQuickJs(
                "let name = \"张三\"\nlog(`姓名：${name}`)\n", "snip.js"));
    }

    /** 但反引号脚本里另有 Rhino 不认的新语法（如 class）时，兜底换引擎仍然生效。 */
    @Test
    public void backtickWithRhinoUnsupportedSyntaxStillSwitches() {
        assertTrue(EngineFallbacks.shouldSwitchToQuickJs(
                "class A {}\nlog(`x`)\n", "cls.js"));
    }

    @Test
    public void plainScriptDoesNotNeedQuickJs() {
        assertFalse(EngineFallbacks.shouldSwitchToQuickJs(
                "var a = 1;\nconsole.log('hi ' + a);\n", "plain.js"));
    }

    /** 反引号出现在普通字符串里是合法 Rhino 语法，不能因此换引擎。 */
    @Test
    public void backtickInsideAStringDoesNotCount() {
        assertFalse(EngineFallbacks.shouldSwitchToQuickJs(
                "var s = \"press ` key\";\n", "str.js"));
    }

    /** 注释里的反引号同理。 */
    @Test
    public void backtickInsideACommentDoesNotCount() {
        assertFalse(EngineFallbacks.shouldSwitchToQuickJs(
                "// 这个 ` 只是个注释\nvar a = 1;\n", "comment.js"));
    }

    /** 没有反引号时，哪怕 Rhino 编译失败也不该动引擎 —— 那是脚本自己的语法错。 */
    @Test
    public void otherSyntaxErrorsDoNotTriggerTheSwitch() {
        assertFalse(EngineFallbacks.shouldSwitchToQuickJs("var a = ;\n", "broken.js"));
    }

    @Test
    public void nullAndEmptySourceAreSafe() {
        assertFalse(EngineFallbacks.shouldSwitchToQuickJs(null, "x.js"));
        assertFalse(EngineFallbacks.shouldSwitchToQuickJs("", "x.js"));
        assertFalse(EngineFallbacks.shouldSwitchToRhinoForXmlLayout(null, "x.js"));
        assertFalse(EngineFallbacks.shouldSwitchToRhinoForXmlLayout("", "x.js"));
    }

    @Test
    public void xmlLiteralLayoutNeedsRhino() {
        assertTrue(EngineFallbacks.shouldSwitchToRhinoForXmlLayout(XML_LAYOUT_SCRIPT, "tpl.js"));
    }

    @Test
    public void xmlLiteralWithCompatibleModernObjectSyntaxUsesRhino() {
        assertTrue(EngineFallbacks.shouldSwitchToRhinoForXmlLayout(
                "var name='menu'; var options={name, show(){return `Hi ${name}`;}};"
                        + "ui.layout(<frame><text text={options.show()}/></frame>);", "mixed.js"));
        assertFalse(EngineFallbacks.shouldSwitchToRhinoForXmlLayout(
                "var options={name, bad:};ui.layout(<frame/>);", "broken.js"));
    }

    /** ui.inflate 也是同一套 XML 字面量写法。 */
    @Test
    public void xmlLiteralInflateAlsoNeedsRhino() {
        assertTrue(EngineFallbacks.shouldSwitchToRhinoForXmlLayout(
                "var v = ui.inflate(<frame id=\"a\"/>);\n", "tpl2.js"));
    }

    /** 把 XML 写成字符串的脚本两个引擎都能跑，不该换。 */
    @Test
    public void stringXmlLayoutDoesNotNeedRhino() {
        assertFalse(EngineFallbacks.shouldSwitchToRhinoForXmlLayout(
                "ui.layout('<frame id=\"root\"/>');\n", "str.js"));
    }

    /** 模板字符串 + XML 字面量：Rhino 编译不过，就别往 Rhino 换（否则两边来回倒腾）。 */
    @Test
    public void backtickScriptWithXmlLiteralIsNotSwitchedToRhino() {
        assertFalse(EngineFallbacks.shouldSwitchToRhinoForXmlLayout(
                "ui.layout(`\n<frame id=\"root\"/>\n`);\n", "both.js"));
    }
}
