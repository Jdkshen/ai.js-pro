package com.stardust.autojs.engine;

import java.util.regex.Pattern;

import org.mozilla.javascript.Context;

/**
 * 两个引擎各有一处「对方能跑、它连解析都过不去」的死角。这里集中判定，**只在这类注定
 * 跑不起来的情况下**换引擎，并由调用方同时写一条 W 级说明（不做静默切换）。
 *
 * <ol>
 *   <li>Rhino 1.7.14.1 已原生支持模板字符串（反引号）；这条兜底仍保留：含反引号的脚本
 *       若在 Rhino 下编译不过（通常意味着另有它不支持的语法，如 class / 展开 / for-of），
 *       交给 QuickJS 试跑。</li>
 *   <li>QuickJS 没有 E4X：XML 字面量（{@code ui.layout(<frame/>)} 这种 Auto.js 经典写法）
 *       在它那里是语法错误（{@code unexpected token in expression: '<'}），Rhino 支持。</li>
 * </ol>
 *
 * 两边都用不了的脚本（既含反引号、又是 XML 字面量）不会被来回倒腾：每次换引擎前都要求
 * **目标引擎真的能编译**，编译不过就保持原样，让带提示的报错落到用户手里。
 */
public final class EngineFallbacks {

    /** XML 字面量写法：{@code ui.layout(<xml>…)} / {@code ui.inflate(<xml>…)}。 */
    private static final Pattern XML_LAYOUT =
            Pattern.compile("\\bui\\s*\\.\\s*(?:layout|inflate)\\s*\\(\\s*<");

    private static final char BACKTICK = '`';

    private EngineFallbacks() {
    }

    /** 情况 1：源码含反引号、且 Rhino 编译不过 → 兜底交给 QuickJS（模板字符串本身已支持，触发者多为其它新语法）。 */
    public static boolean shouldSwitchToQuickJs(String script, String scriptName) {
        if (script == null || script.isEmpty() || script.indexOf(BACKTICK) < 0) {
            return false;
        }
        return !compilesOnRhino(script, scriptName);
    }

    /** 情况 2：用了 XML 字面量、且 Rhino 编译得过 → QuickJS 解析不了，该交给 Rhino。 */
    public static boolean shouldSwitchToRhinoForXmlLayout(String script, String scriptName) {
        if (script == null || script.isEmpty() || !XML_LAYOUT.matcher(script).find()) {
            return false;
        }
        return compilesOnRhino(RhinoSyntaxPreprocessor.apply(script), scriptName);
    }

    private static boolean compilesOnRhino(String script, String scriptName) {
        Context context = Context.enter();
        try {
            // 与 RhinoJavaScriptEngine 保持一致，否则「编译得过去」的结论会对不上。
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
