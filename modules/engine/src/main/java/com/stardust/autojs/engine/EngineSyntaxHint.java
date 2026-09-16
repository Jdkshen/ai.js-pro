package com.stardust.autojs.engine;

import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.RhinoException;

/**
 * Rhino 1.7.7 的词法分析器不认 ES6 模板字符串（反引号）：脚本里只要出现一处，
 * 编译期就报 {@code illegal character} —— 既看不出原因，也看不出「换个引擎就能跑」。
 *
 * <p>这里在**保留原报错文本与行号**的前提下追加一句可操作的提示。判定只看
 * 「源码里有反引号 + 编译失败」，刻意不匹配具体报错文案：Rhino 在不同调用路径下
 * 给出的文案并不一致（单条 {@code illegal character}，或聚合的
 * {@code Compilation produced N syntax errors.}）。
 */
public final class EngineSyntaxHint {

    public static final String HINT =
            "本脚本里有反引号（ES6 模板字符串），Rhino 引擎不支持；"
                    + "可在脚本第一行加 // @engine quickjs 改用 QuickJS 引擎，或把反引号改写成字符串拼接";

    private static final char BACKTICK = '`';

    private EngineSyntaxHint() {
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
