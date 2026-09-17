package com.stardust.autojs.engine;

import androidx.annotation.Nullable;

/**
 * 把 ES6 模板字符串（反引号）降级成 ES5 的字符串拼接，好让 Rhino（1.7.7，词法层没有反引号）
 * 也能跑那些「用了模板字符串、但真正依赖的是 Rhino 独有能力」的脚本。
 *
 * <p>典型场景：用 {@code <canvas>} 画悬浮标尺/辅助线的脚本，几乎都用模板字符串拼 XML：
 * <pre>
 * var board = floaty.rawWindow(`&lt;canvas id="canvas" w="*" h="*" /&gt;`);
 * </pre>
 * 这种脚本会被 {@code EngineFallbacks.shouldSwitchToQuickJs} 自动切到 QuickJS，
 * 而 canvas 的 {@code draw} 回调要求在**绘制线程内同步**进脚本（QuickJS 是单线程引擎，
 * 做不到从任意线程回调），于是脚本必然报 {@code Unable to create floaty window}。
 * 与其让用户手改脚本，这里把模板字符串翻译掉、继续留在 Rhino 上跑（canvas / E4X 都还在）。
 *
 * <p><b>保守实现</b>：只处理最常见的形态 —— 普通模板、<code>${}</code> 表达式、转义、多行。
 * 碰到标签模板（tagged template，<code>fn`...`</code>）、未闭合的反引号、表达式里再嵌模板等
 * 无法确定语义的写法就返回 {@code null}，调用方照旧回退到「换 QuickJS 引擎」。
 */
public final class TemplateLiteralPreprocessor {

    private TemplateLiteralPreprocessor() {
    }

    /** 脚本里是否出现反引号。 */
    public static boolean containsTemplateLiteral(@Nullable String script) {
        return script != null && script.indexOf('`') >= 0;
    }

    /** 能否安全降级（等价于 {@link #apply} 返回非 null）。 */
    public static boolean canApply(@Nullable String script) {
        return apply(script) != null;
    }

    /**
     * @return 降级后的脚本；脚本里没有模板字符串时原样返回；无法安全降级时返回 {@code null}。
     */
    @Nullable
    public static String apply(@Nullable String script) {
        if (script == null) {
            return null;
        }
        if (!containsTemplateLiteral(script)) {
            return script;
        }
        StringBuilder out = new StringBuilder(script.length() + 32);
        int index = 0;
        int length = script.length();
        while (index < length) {
            char c = script.charAt(index);
            // 注释 / 普通字符串原样搬过去（里面的反引号不算模板）
            if (c == '/' && index + 1 < length
                    && (script.charAt(index + 1) == '/' || script.charAt(index + 1) == '*')) {
                index = copyComment(script, index, out);
                continue;
            }
            if (c == '\'' || c == '"') {
                index = copyQuoted(script, index, out);
                continue;
            }
            if (c == '`') {
                if (looksLikeTag(out)) {
                    // 标签模板（tag`...`）：降级需要臆造标签函数的调用约定，不做。
                    return null;
                }
                int[] cursor = new int[]{index};
                String replaced = parseTemplate(script, cursor);
                if (replaced == null) {
                    return null;
                }
                out.append(replaced);
                index = cursor[0];
                continue;
            }
            out.append(c);
            index++;
        }
        return out.toString();
    }

    /** 模板字符串出现在一个“值”后面时（`tag`x`` / `fn()`x`` / `arr[i]`x``）就当标签模板，不做降级。 */
    private static boolean looksLikeTag(StringBuilder out) {
        int index = out.length() - 1;
        while (index >= 0 && Character.isWhitespace(out.charAt(index))) {
            index--;
        }
        if (index < 0) {
            return false;
        }
        char c = out.charAt(index);
        if (c == ')' || c == ']') {
            return true;
        }
        if (!Character.isJavaIdentifierPart(c)) {
            return false;
        }
        int end = index + 1;
        while (index >= 0 && Character.isJavaIdentifierPart(out.charAt(index))) {
            index--;
        }
        String word = out.substring(index + 1, end);
        return !KEYWORDS_BEFORE_TEMPLATE.contains(word);
    }

    /** 这些关键字后面跟反引号是“模板字符串作为表达式”，不是标签模板。 */
    private static final java.util.Set<String> KEYWORDS_BEFORE_TEMPLATE = new java.util.HashSet<>(
            java.util.Arrays.asList("return", "typeof", "instanceof", "case", "in", "of", "do",
                    "else", "new", "delete", "void", "yield", "await", "throw"));

    /** 复制一行注释或块注释，返回新的下标。 */
    private static int copyComment(String script, int start, StringBuilder out) {
        int index = start;
        if (script.charAt(index + 1) == '/') {
            while (index < script.length() && script.charAt(index) != '\n') {
                out.append(script.charAt(index++));
            }
            return index;
        }
        out.append("/*");
        index += 2;
        while (index < script.length()) {
            if (script.charAt(index) == '*' && index + 1 < script.length()
                    && script.charAt(index + 1) == '/') {
                out.append("*/");
                return index + 2;
            }
            out.append(script.charAt(index++));
        }
        return index;
    }

    /** 复制一个单/双引号字符串（含转义），返回新的下标。 */
    private static int copyQuoted(String script, int start, StringBuilder out) {
        char quote = script.charAt(start);
        int index = start;
        out.append(quote);
        index++;
        while (index < script.length()) {
            char c = script.charAt(index);
            if (c == '\\' && index + 1 < script.length()) {
                out.append(c).append(script.charAt(index + 1));
                index += 2;
                continue;
            }
            out.append(c);
            index++;
            if (c == quote || c == '\n') {
                return index;
            }
        }
        return index;
    }

    /**
     * 从 {@code cursor[0]} 指向的反引号开始解析一个模板字符串。
     *
     * @return 等价的 ES5 表达式（如 <code>'a' + (x) + 'b'</code>）；无法安全降级时返回 null。
     *         cursor[0] 会被改成模板结束后的下标。
     */
    @Nullable
    private static String parseTemplate(String script, int[] cursor) {
        int index = cursor[0];
        int length = script.length();
        StringBuilder result = new StringBuilder();
        StringBuilder text = new StringBuilder();
        boolean hasPart = false;
        index++;   // 跳过起始反引号
        while (true) {
            if (index >= length) {
                return null;   // 未闭合
            }
            char c = script.charAt(index);
            if (c == '\\') {
                if (index + 1 >= length) {
                    return null;
                }
                char next = script.charAt(index + 1);
                // \` 和 \$ 在模板里是字面量；降级到单引号串后反斜杠要去掉，其余转义原样保留
                if (next == '`' || next == '$') {
                    text.append(next);
                } else {
                    text.append(c).append(next);
                }
                index += 2;
                continue;
            }
            if (c == '`') {
                index++;
                break;
            }
            if (c == '$' && index + 1 < length && script.charAt(index + 1) == '{') {
                if (text.length() > 0 || !hasPart) {
                    appendStringLiteral(result, text);
                    text.setLength(0);
                    hasPart = true;
                }
                int[] exprCursor = new int[]{index + 2};
                String expression = readExpression(script, exprCursor);
                if (expression == null) {
                    return null;
                }
                if (containsTemplateLiteral(expression)) {
                    // 表达式里还有模板字符串：递归降级，失败就整体放弃
                    String nested = apply(expression);
                    if (nested == null) {
                        return null;
                    }
                    expression = nested;
                }
                if (result.length() > 0) {
                    result.append(" + ");
                }
                result.append('(').append(expression).append(')');
                index = exprCursor[0];
                continue;
            }
            text.append(c);
            index++;
        }
        if (text.length() > 0) {
            appendStringLiteral(result, text);
        }
        if (result.length() == 0) {
            result.append("''");
        }
        cursor[0] = index;
        return result.toString();
    }

    /**
     * 读取 <code>${</code> 之后到配对的 <code>}</code> 之前的表达式文本。
     * 会跳过字符串、注释与嵌套的大括号。
     */
    @Nullable
    private static String readExpression(String script, int[] cursor) {
        int index = cursor[0];
        int length = script.length();
        int depth = 1;
        int start = index;
        while (index < length) {
            char c = script.charAt(index);
            if (c == '/' && index + 1 < length
                    && (script.charAt(index + 1) == '/' || script.charAt(index + 1) == '*')) {
                StringBuilder skip = new StringBuilder();
                index = copyComment(script, index, skip);
                continue;
            }
            if (c == '\'' || c == '"') {
                StringBuilder skip = new StringBuilder();
                index = copyQuoted(script, index, skip);
                continue;
            }
            if (c == '`') {
                // 表达式里的模板字符串：整段跳过（内容交给外层的递归降级处理）
                int probe = index + 1;
                while (probe < length) {
                    char inner = script.charAt(probe);
                    if (inner == '\\') {
                        probe += 2;
                        continue;
                    }
                    if (inner == '`') {
                        probe++;
                        break;
                    }
                    if (inner == '$' && probe + 1 < length && script.charAt(probe + 1) == '{') {
                        int[] nestedCursor = new int[]{probe + 2};
                        if (readExpression(script, nestedCursor) == null) {
                            return null;
                        }
                        probe = nestedCursor[0];
                        continue;
                    }
                    probe++;
                }
                if (probe > length) {
                    return null;
                }
                index = probe;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    cursor[0] = index + 1;
                    return script.substring(start, index);
                }
            }
            index++;
        }
        return null;
    }

    /** 模板文本 → ES5 单引号字符串字面量（换行转义、单引号与反斜杠转义）。 */
    private static void appendStringLiteral(StringBuilder out, StringBuilder text) {
        if (out.length() > 0) {
            out.append(" + ");
        }
        out.append('\'');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\'':
                    out.append("\\'");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\u2028':
                    out.append("\\u2028");
                    break;
                case '\u2029':
                    out.append("\\u2029");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        out.append('\'');
    }
}
