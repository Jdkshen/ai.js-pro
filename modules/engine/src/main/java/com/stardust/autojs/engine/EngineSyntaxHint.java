package com.stardust.autojs.engine;

import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.RhinoException;

/**
 * 历史上 Rhino 1.7.7 的词法分析器不认 ES6 模板字符串（反引号），脚本里只要出现一处，
 * 编译期就报 {@code illegal character}，看不出「换个引擎就能跑」；本仓库已升级到
 * 官方 Rhino 1.7.14.1，模板字符串已原生支持，这条提示退化为兜底：含反引号的脚本若仍
 * 编译失败，多半是命中了 Rhino 尚未支持的其它新语法（class / 展开 / for-of / 可选链等）。
 *
 * <p>这里在**保留原报错文本与行号**的前提下追加一句可操作的提示。判定只看
 * 「源码里有反引号 + 编译失败」，刻意不匹配具体报错文案：Rhino 在不同调用路径下
 * 给出的文案并不一致（单条 {@code illegal character}，或聚合的
 * {@code Compilation produced N syntax errors.}）。
 */
public final class EngineSyntaxHint {

    public static final String HINT =
            "提示：Rhino 引擎解析这份脚本失败。"
                    + "若脚本使用了 Rhino 尚未支持的新语法（如 class、展开、for-of、可选链），"
                    + "可在脚本第一行加 // @engine quickjs 改用 QuickJS 引擎";

    private static final char BACKTICK = '`';

    /** QuickJS 遇到 XML 字面量时给出的原文。 */
    private static final String QUICKJS_XML_TOKEN = "unexpected token in expression: '<'";

    private static final String XML_HINT =
            "提示：<…> 是 XML 字面量（Auto.js 经典写法 ui.layout(<frame/>)），只有 Rhino 引擎支持。"
                    + "QuickJS 请把 XML 写成字符串（ui.layout('<frame/>')），"
                    + "或把脚本第一行改成 // @engine rhino。";

    private EngineSyntaxHint() {
    }

    /**
     * QuickJS 报 {@code unexpected token in expression: '<'} 时补一句可操作提示。
     *
     * <p>这条报错来自 QuickJS 自己的词法阶段，Java 侧只是转发，所以文案不受
     * QuickJS 那个 256 字节缓冲的限制（那是 native 抛 JS 异常才有的）。
     */
    public static QuickJsException decorateQuickJs(QuickJsException error) {
        String message = error.getMessage();
        if (message == null || !message.contains(QUICKJS_XML_TOKEN)
                || message.contains(XML_HINT)) {
            return error;
        }
        return new QuickJsException(message + "\n" + XML_HINT);
    }

    /**
     * 源码里没有反引号（或已经加过提示）时原样返回 {@code e}；
     * 否则返回一个保留原信息、message 里多一句提示的异常。
     */
    public static RhinoException decorate(RhinoException e, String source) {
        if (source == null || source.indexOf(BACKTICK) < 0
                || (e.getMessage() != null && e.getMessage().contains(HINT))) {
            return e;
        }
        try {
            // Rhino 1.7.7 里 details() 是「报错原文」本身，位置是 getMessage() 自己拼的
            // 「原文 (source#line)」。所以这里只换原文，位置照原样传下去，
            // 脚本名 / 行号 / 列号都不会变。
            return new EvaluatorException(
                    rawMessage(e) + " —— " + HINT,
                    e.sourceName(),
                    e.lineNumber(),
                    e.lineSource(),
                    e.columnNumber());
        } catch (RuntimeException unsupported) {
            // 拿不到源信息就退回原异常：提示是锦上添花，不能因此改变报错本身。
            return e;
        }
    }

    private static String rawMessage(RhinoException e) {
        String details = e.details();
        return details == null || details.isEmpty() ? e.getMessage() : details;
    }
}
