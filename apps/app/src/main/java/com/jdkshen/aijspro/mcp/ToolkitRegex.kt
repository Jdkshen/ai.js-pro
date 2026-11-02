package com.jdkshen.aijspro.mcp

import java.util.regex.Matcher
import java.util.regex.Pattern

/** Java regex compatibility with a budget shared by every matcher over one input. */
internal object ToolkitRegex {
    class BudgetExceeded : IllegalArgumentException("正则匹配超过时间或工作量限制，请缩小输入或简化表达式")

    fun matcher(pattern: Pattern, input: CharSequence, maxMillis: Long = 750): Matcher =
        pattern.matcher(Input(input, 0, input.length, Budget(maxMillis)))
    fun compile(source: String, flags: Int = 0): Pattern = try { Pattern.compile(source, flags) }
        catch (_: StackOverflowError) { throw BudgetExceeded() }
    fun find(matcher: Matcher): Boolean = try { matcher.find() }
        catch (_: StackOverflowError) { throw BudgetExceeded() }
    fun matches(matcher: Matcher): Boolean = try { matcher.matches() }
        catch (_: StackOverflowError) { throw BudgetExceeded() }

    private class Budget(maxMillis: Long) {
        private val end = System.nanoTime() + maxMillis.coerceIn(1, 5000) * 1_000_000
        private var reads = 0L
        fun check() {
            if (++reads > 8_000_000 || Thread.currentThread().isInterrupted ||
                (reads and 255L == 0L && System.nanoTime() > end)) throw BudgetExceeded()
        }
    }
    private class Input(private val source: CharSequence, private val begin: Int,
                        private val end: Int, private val budget: Budget) : CharSequence {
        override val length: Int get() = end - begin
        override fun get(index: Int): Char {
            if (index !in 0 until length) throw IndexOutOfBoundsException()
            budget.check(); return source[begin + index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            require(startIndex >= 0 && endIndex >= startIndex && endIndex <= length)
            budget.check(); return Input(source, begin + startIndex, begin + endIndex, budget)
        }
        override fun toString(): String { budget.check(); return source.subSequence(begin, end).toString() }
    }
}
