package com.stardust.autojs.engine;

import org.mozilla.javascript.Context;

/**
 * Rhino 1.7.7 的词法分析器里**没有**「模板字符串」这条 token：含反引号的脚本在 Rhino 下
 * 编译期必挂（{@code illegal character}），改写法也救不回来 —— 它在这条引擎上就是跑不了。
 *
 * <p>所以当脚本被判给 Rhino、而 Rhino 连编译都过不去时，这里给出「改投 QuickJS」的判断。
 * 边界收得很紧，必须同时满足三条：
 * <ol>
 *   <li>引擎是 Rhino（脚本里没有 {@code // @engine quickjs}，工程也没配 quickjs）；</li>
 *   <li>源码里出现反引号；</li>
 *   <li>Rhino 真的编译不过。</li>
 * </ol>
 * 换句话说：**本来能在 Rhino 上跑的脚本不会被换引擎**。调用方换引擎时要往控制台写一条
 * 明确说明，不做静默切换。
 */
public final class RhinoTemplateStringFallback {

    private static final char BACKTICK = '`';

    private RhinoTemplateStringFallback() {
    }

    /** 见类注释的三条前提；只有都满足才返回 true。 */
    public static boolean needsQuickJs(String script, String scriptName) {
        if (script == null || script.isEmpty() || script.indexOf(BACKTICK) < 0) {
            return false;
        }
        return !compilesOnRhino(script, scriptName);
    }

    private static boolean compilesOnRhino(String script, String scriptName) {
        Context context = Context.enter();
        try {
            // 与 RhinoJavaScriptEngine 保持一致，否则「编译得过得去」的结论会对不上。
            context.setLanguageVersion(Context.VERSION_ES6);
            context.compileString(script, scriptName == null ? "script" : scriptName, 1, null);
            return true;
        } catch (RuntimeException e) {
            // EvaluatorException（语法错误）也是 RuntimeException
            return false;
        } finally {
            Context.exit();
        }
    }
}
