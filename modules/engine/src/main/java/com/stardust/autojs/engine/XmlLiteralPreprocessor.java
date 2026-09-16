package com.stardust.autojs.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Auto.js 那套 XML 字面量写法改写成字符串，让**没有 E4X 的 QuickJS** 也能跑：
 *
 * <pre>
 * ui.layout(                 ui.layout(`
 *     &lt;vertical&gt;        →           &lt;vertical&gt;
 *         &lt;text/&gt;                  &lt;text/&gt;
 *     &lt;/vertical&gt;                  &lt;/vertical&gt;`)
 * )
 * </pre>
 *
 * <p>为什么用模板字符串而不是普通引号：模板字符串**可以跨行**，所以改写前后**行号完全不变** ——
 * 报错行号、{@code // @engine} 指令的位置都不会被挪动。XML 里出现反引号、{@code \} 或
 * {@code ${} 会按需转义。
 *
 * <p>只处理 {@code ui.layout(...)} / {@code ui.inflate(...)} 且实参**以 {@code <} 开头**的情况；
 * 实参本来就是字符串（{@code ui.layout('…')}）或别的表达式一律不动。
 *
 * <p>已知边界：E4X 的花括号插值（{@code <text text={foo}/>}）不会被求值，会原样变成 XML 文本 ——
 * 这种脚本本来就只有 Rhino 能跑，改写成字符串至少不再报语法错。
 */
public final class XmlLiteralPreprocessor {

    private static final Pattern CALL =
            Pattern.compile("\\bui\\s*\\.\\s*(?:layout|inflate)\\s*\\(");

    private XmlLiteralPreprocessor() {
    }

    /** 没命中任何 XML 字面量时原样返回。 */
    public static String apply(String script) {
        if (script == null || script.isEmpty() || script.indexOf('<') < 0) {
            return script;
        }
        List<int[]> spans = new ArrayList<>();
        Matcher matcher = CALL.matcher(script);
        while (matcher.find()) {
            int open = matcher.end() - 1;
            int argumentStart = skipWhitespace(script, matcher.end());
            if (argumentStart >= script.length() || script.charAt(argumentStart) != '<') {
                continue;
            }
            int close = findClosingParen(script, open);
            if (close < 0) {
                continue;
            }
            int argumentEnd = lastIndexOf(script, '>', argumentStart, close - 1);
            if (argumentEnd < 0) {
                continue;
            }
            spans.add(new int[]{argumentStart, argumentEnd + 1});
        }
        if (spans.isEmpty()) {
            return script;
        }
        // 从后往前替换，前面的下标才不会被前面的改动带偏。始终用原始 script 取子串。
        StringBuilder builder = new StringBuilder(script);
        for (int i = spans.size() - 1; i >= 0; i--) {
            int[] span = spans.get(i);
            builder.replace(span[0], span[1],
                    toTemplateLiteral(script.substring(span[0], span[1])));
        }
        return builder.toString();
    }

    private static int skipWhitespace(String script, int from) {
        int index = from;
        while (index < script.length() && Character.isWhitespace(script.charAt(index))) {
            index++;
        }
        return index;
    }

    /**
     * 从 {@code (} 起找配对的 {@code )}。XML 的字面量里可能出现括号（属性文本、说明），
     * 所以对引号要敏感 —— XML 的属性引号正好也就是 JS 的字符串引号，两边能共用一套判断。
     */
    private static int findClosingParen(String script, int open) {
        int depth = 1;
        int index = open + 1;
        while (index < script.length()) {
            char c = script.charAt(index);
            if (c == '\\') {
                index += 2;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                index = skipString(script, index, c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
            index++;
        }
        return -1;
    }

    /** 返回结束引号之后的下标；没闭合时返回脚本末尾。 */
    private static int skipString(String script, int start, char quote) {
        int index = start + 1;
        while (index < script.length()) {
            char c = script.charAt(index);
            if (c == '\\') {
                index += 2;
                continue;
            }
            if (c == quote) {
                return index + 1;
            }
            index++;
        }
        return index;
    }

    private static int lastIndexOf(String script, char target, int from, int to) {
        for (int index = Math.min(to, script.length() - 1); index >= from; index--) {
            if (script.charAt(index) == target) {
                return index;
            }
        }
        return -1;
    }

    /** XML 文本 → 跨行模板字符串（保留换行，行号不变）。 */
    private static String toTemplateLiteral(String xml) {
        StringBuilder builder = new StringBuilder(xml.length() + 2);
        builder.append('`');
        for (int i = 0; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (c == '\\' || c == '`') {
                builder.append('\\').append(c);
            } else if (c == '$' && i + 1 < xml.length() && xml.charAt(i + 1) == '{') {
                builder.append('\\').append(c);
            } else {
                builder.append(c);
            }
        }
        builder.append('`');
        return builder.toString();
    }
}
