package com.stardust.autojs.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link RhinoTemplateStringFallback} 的边界：只有在「Rhino 编译不过 + 源码里有反引号」时
 * 才该改投 QuickJS —— 能在 Rhino 上跑的脚本一律不许换引擎。
 */
public class RhinoTemplateStringFallbackTest {

    @Test
    public void templateStringScriptNeedsQuickJs() {
        assertTrue(RhinoTemplateStringFallback.needsQuickJs(
                "let name = \"张三\"\nlet age = 18\nlog(`姓名：${name}，年龄：${age}`)\n",
                "snip.js"));
    }

    @Test
    public void plainScriptDoesNotNeedQuickJs() {
        assertFalse(RhinoTemplateStringFallback.needsQuickJs(
                "var a = 1;\nconsole.log('hi ' + a);\n", "plain.js"));
    }

    /** 反引号出现在普通字符串里是合法 Rhino 语法，不能因此换引擎。 */
    @Test
    public void backtickInsideAStringDoesNotCount() {
        assertFalse(RhinoTemplateStringFallback.needsQuickJs(
                "var s = \"press ` key\";\n", "str.js"));
    }

    /** 注释里的反引号同理。 */
    @Test
    public void backtickInsideACommentDoesNotCount() {
        assertFalse(RhinoTemplateStringFallback.needsQuickJs(
                "// 这个 ` 只是个注释\nvar a = 1;\n", "comment.js"));
    }

    /** 没有反引号时，哪怕 Rhino 编译失败也不该动引擎 —— 那是脚本自己的语法错。 */
    @Test
    public void otherSyntaxErrorsDoNotTriggerTheSwitch() {
        assertFalse(RhinoTemplateStringFallback.needsQuickJs("var a = ;\n", "broken.js"));
    }

    @Test
    public void nullAndEmptySourceAreSafe() {
        assertFalse(RhinoTemplateStringFallback.needsQuickJs(null, "x.js"));
        assertFalse(RhinoTemplateStringFallback.needsQuickJs("", "x.js"));
    }
}
