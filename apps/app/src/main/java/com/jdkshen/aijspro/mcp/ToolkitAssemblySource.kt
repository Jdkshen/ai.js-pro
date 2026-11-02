package com.jdkshen.aijspro.mcp

/**
 * Single source-stage policy gate for native assembly text.
 *
 * The optional clang backend writes the user text into a `.S` file, so the file is
 * run through the C preprocessor before it reaches the assembler. The built-in
 * AArch64 encoder shares the same gate. The policy is deliberately conservative:
 *
 * - only instructions, labels and the `.inst` directive are accepted;
 * - `.incbin`/`.include` and every `#`/`%:`/`??=` preprocessor form are rejected,
 *   including when they follow a label or a `;` statement separator;
 * - C-style block comments and string/character literals are rejected instead of
 *   being re-lexed, because comment/quote tricks can hide a directive;
 * - backslash line splicing is rejected, so the text cannot be joined with the
 *   provider's generated `.text`/`_start` wrapper;
 * - `//` line comments are allowed;
 * - `#` is only accepted as a decimal/hex numeric immediate (`#1`, `#-1`,
 *   `#0x10`), so ordinary AArch64/ARM source keeps working;
 * - `.inst` takes one numeric operand;
 * - the text is capped at 65,536 characters.
 *
 * `McpToolkitProvider` must call [validate] before writing the user source into
 * its `.S` input. `ToolkitNativePatches` and `ToolkitAssembler` call it as well.
 */
internal object ToolkitAssemblySource {
    data class ClangInput(val source: String, val absoluteSymbols: Map<String, Long>)

    /** Clang treats numeric branch/ADR operands as displacements. Public patch APIs use
     * absolute virtual addresses. Keep these operands unresolved until the linker, which
     * receives their absolute values through --defsym rather than folding .set constants. */
    fun forClang(source: String, instructionSet: String): ClangInput {
        validate(source)
        val symbols = linkedMapOf<String, Long>()
        val label = Regex("^([A-Za-z_.$][A-Za-z0-9_.$]*|[0-9]+)\\s*:\\s*")
        val number = Regex("#?(?:0[xX][0-9a-fA-F]+|[0-9]+)")
        val output = source.lineSequence().flatMap { it.substringBefore("//").split(';').asSequence() }.map { raw ->
            var body = raw.trim(); val labels = StringBuilder()
            while(true) {
                val match = label.find(body) ?: break
                labels.append(match.value); body = body.substring(match.value.length)
            }
            val head = body.takeWhile { !it.isWhitespace() }
            val operands = body.substring(head.length).trim().split(',').map { it.trim() }.toMutableList()
            val mnemonic = head.lowercase().removeSuffix(".w").removeSuffix(".n")
            val target = when(instructionSet) {
                "aarch64" -> when {
                    mnemonic in listOf("b", "bl") || mnemonic.startsWith("b.") -> 0
                    mnemonic in listOf("cbz", "cbnz", "adr", "adrp", "ldr", "ldrsw", "prfm") -> 1
                    mnemonic in listOf("tbz", "tbnz") -> 2
                    else -> -1
                }
                "arm", "thumb" -> when {
                    Regex("b(?:l|lx)?(?:eq|ne|cs|hs|cc|lo|mi|pl|vs|vc|hi|ls|ge|lt|gt|le|al)?").matches(mnemonic) -> 0
                    mnemonic in listOf("cbz", "cbnz", "adr", "ldr") -> 1
                    else -> -1
                }
                "x86", "x86_64" -> if(mnemonic.startsWith("j") || mnemonic in listOf("call", "callq", "loop", "loope", "loopne")) 0 else -1
                else -> -1
            }
            val token = operands.getOrNull(target)
            if(token != null && number.matches(token)) {
                val address = ToolkitElf.number(token.removePrefix("#"))
                var name = "_aijs_abs_${symbols.size}"
                while(source.contains(name) || name in symbols) name += "_"
                symbols[name] = address; operands[target] = name
                labels.toString() + head + " " + operands.joinToString(", ")
            } else labels.toString() + body
        }.joinToString("\n")
        return ClangInput(output, symbols)
    }
    // Directives that read other files. Checked anywhere in a statement because
    // label/`.incbin:` and operand positions are too ambiguous to second-guess.
    private val FILE_DIRECTIVE = Regex("(?i)\\.(?:incbin|include)\\b")

    // A numeric immediate starts immediately after '#'. Whitespace after '#' is
    // never accepted: that is the shape of a preprocessor directive.
    private val NUMERIC_IMMEDIATE = Regex("^[+-]?(?:0[xX][0-9a-fA-F]+|[0-9]+)")
    private val INST_OPERAND = Regex("[+-]?(?:0[xX][0-9a-fA-F]+|[0-9]+)")

    /** Matches ToolkitAssembler's source bound and the provider's historical limit. */
    private const val MAX_SOURCE = 65536

    fun validate(source: String) {
        require(source.isNotEmpty()) { "汇编源码不能为空" }
        require(source.length <= MAX_SOURCE) { "汇编源码超过 $MAX_SOURCE 字符" }
        require(!source.contains('\u0000')) { "汇编源码不能包含 NUL" }

        source.lineSequence().forEachIndexed { index, raw ->
            val line = index + 1
            // Backslash-newline is removed by the C preprocessor before comments
            // are processed. Rejecting it on the raw line (comments included)
            // avoids a line-splicing/comment mismatch with the mini assembler.
            require(!raw.contains('\\')) {
                "汇编第 $line 行：不支持行拼接或反斜杠转义"
            }
            require(!raw.contains("??/")) {
                "汇编第 $line 行：不允许预处理指令"
            }
            // '//' is the AArch64 ELF comment string. Strip it before splitting on
            // ';' so punctuation inside a comment is not treated as a statement.
            val code = raw.substringBefore("//")
            require(!code.contains("/*") && !code.contains("*/")) {
                "汇编第 $line 行：不支持块注释，请使用 // 注释"
            }
            require(!code.contains('"') && !code.contains('\'')) {
                "汇编第 $line 行：不接受字符串或字符字面量"
            }
            for (statement in code.split(';')) {
                validateStatement(statement.trim(), line)
            }
        }
    }

    private fun validateStatement(statement: String, line: Int) {
        if (statement.isEmpty()) return

        // A '#' at the head of a statement is a preprocessor directive (or an
        // invalid bare immediate), never an instruction. %: and ??= are the C
        // digraph/trigraph spellings of '#'. Rejecting them anywhere is safe for
        // the supported instruction syntaxes.
        require(!statement.startsWith("#") && !statement.startsWith("%:") && !statement.startsWith("??=")) {
            "汇编第 $line 行：不允许预处理指令"
        }
        require(!statement.contains("%:") && !statement.contains("??=")) {
            "汇编第 $line 行：不允许预处理指令"
        }
        // Check the whole statement, not just the post-label body: `.incbin:` is
        // not a safe label spelling to second-guess in the external lexer.
        if (FILE_DIRECTIVE.containsMatchIn(statement)) {
            fail(line, "不允许 .incbin/.include 文件包含指令")
        }

        // Strip one or more leading label definitions. Whitespace before the
        // colon is accepted, but the label itself must be a single token. This
        // is intentionally broader than the mini assembler's label regex so that
        // unusual-but-valid labels cannot hide the directive that follows them.
        var start = 0
        while (true) {
            val colon = statement.indexOf(':', start)
            if (colon <= start) break
            val label = statement.substring(start, colon).trim()
            if (label.isEmpty() || label.any { it.isWhitespace() }) break
            start = colon + 1
            while (start < statement.length && statement[start].isWhitespace()) start++
            if (start >= statement.length) return
        }
        val body = statement.substring(start)

        if (body.startsWith("#") || body.startsWith("%:") || body.startsWith("??=")) {
            fail(line, "不允许预处理指令")
        }

        if (body.startsWith(".")) {
            val head = body.takeWhile { !it.isWhitespace() }
            if (!head.equals(".inst", ignoreCase = true)) {
                fail(line, "仅接受指令、标签与 .inst 指令")
            }
            val operand = body.substring(head.length).trim()
            if (!INST_OPERAND.matches(operand)) {
                fail(line, ".inst 只接受一个数值")
            }
            return
        }

        checkHashTokens(body, line)
    }

    private fun checkHashTokens(statement: String, line: Int) {
        var index = statement.indexOf('#')
        while (index >= 0) {
            val afterHash = statement.substring(index + 1)
            val match = NUMERIC_IMMEDIATE.find(afterHash)
            if (match == null || match.range.first != 0) {
                fail(line, "# 只能用于数值立即数（如 #1、#-1、#0x10）")
            }
            val end = match.range.last + 1
            if (end < afterHash.length && !afterHash[end].isWhitespace() && afterHash[end] !in ",])}") {
                fail(line, "# 只能用于数值立即数（如 #1、#-1、#0x10）")
            }
            index = statement.indexOf('#', index + 1)
        }
    }

    private fun fail(line: Int, message: String): Nothing =
        throw IllegalArgumentException("汇编第 $line 行：$message")
}
