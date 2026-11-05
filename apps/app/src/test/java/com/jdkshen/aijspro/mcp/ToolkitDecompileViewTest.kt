package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ToolkitDecompileView 纯 JVM 单测：注解默认剔除/裁剪、边界来源分级、xref 分类与保守证据。 */
class ToolkitDecompileViewTest {
    private fun raw(
        code: String = "int main() { return 0; }",
        annotations: List<String> = emptyList(),
        warnings: List<String> = emptyList(),
        coverage: String? = null,
        address: String? = null,
        functionSize: Int? = null,
        detectedSize: Int? = null,
        symbol: String? = null,
    ): ToolkitDecompileView.RawDecompile = ToolkitDecompileView.RawDecompile(
        code = code,
        annotations = annotations,
        warnings = warnings,
        coverage = coverage,
        functionAddress = address,
        functionSize = functionSize,
        detectedSize = detectedSize,
        symbolName = symbol,
    )

    private fun noteOf(result: com.google.gson.JsonObject): String = result.get("note").asString

    private fun notesOf(result: com.google.gson.JsonObject): List<String> {
        val array = result.getAsJsonArray("notes")
        return (0 until array.size()).map { array.get(it).asString }
    }

    @Test
    fun projectOmitsAnnotationsByDefaultAndReportsSizes() {
        val annotations = listOf("@A r2ghidra annotation", "@B 汉字注解")
        val expectedAnnotationBytes = annotations.sumOf { it.toByteArray(Charsets.UTF_8).size }
        val out = ToolkitDecompileView.project(
            raw(
                code = "int x = 1;",
                annotations = annotations,
                warnings = listOf("backend warning"),
                coverage = "partial",
                address = "0x1000",
                functionSize = 32,
                detectedSize = 48,
                symbol = "JNI_OnLoad",
            ),
        )
        assertEquals("int x = 1;", out.get("code").asString)
        assertEquals(10, out.get("charCount").asInt)
        assertEquals(10, out.get("bytes").asInt)
        assertEquals(10, out.get("originalCharCount").asInt)
        assertEquals(10, out.get("originalBytes").asInt)
        assertFalse(out.get("truncated").asBoolean)
        assertTrue(out.get("annotationsOmitted").asBoolean)
        assertFalse(out.get("annotationsIncluded").asBoolean)
        assertEquals(2, out.get("annotationCount").asInt)
        assertEquals(expectedAnnotationBytes, out.get("annotationBytes").asInt)
        assertTrue(out.get("annotations").isJsonNull)
        assertEquals(2, out.getAsJsonArray("warnings").size())
        assertTrue(out.getAsJsonArray("warnings").get(1).asString.contains("includeAnnotations=true"))
        assertEquals("partial", out.get("coverage").asString)
        assertEquals("0x1000", out.get("functionAddress").asString)
        assertEquals(32, out.get("functionSize").asInt)
        assertEquals(48, out.get("detectedSize").asInt)
        assertEquals("JNI_OnLoad", out.get("symbolName").asString)
        assertEquals(ToolkitDecompileView.PROJECTION_VERSION, out.get("projectionVersion").asInt)
    }

    @Test
    fun projectIncludesAnnotationsOnlyWhenRequested() {
        val annotations = listOf("@A", "@B")
        val out = ToolkitDecompileView.project(raw(annotations = annotations), includeAnnotations = true)
        assertFalse(out.get("annotationsOmitted").asBoolean)
        assertTrue(out.get("annotationsIncluded").asBoolean)
        assertEquals(0, out.get("annotationBytes").asInt)
        assertEquals(2, out.get("annotationCount").asInt)
        val array = out.getAsJsonArray("annotations")
        assertEquals(2, array.size())
        assertEquals("@A", array.get(0).asString)
        assertEquals(0, out.getAsJsonArray("warnings").size())
    }

    @Test
    fun projectTruncatesWithoutSplittingSurrogatePairs() {
        val ascii = "a".repeat(20)
        val asciiOut = ToolkitDecompileView.project(raw(code = ascii), maxCodeChars = 10)
        assertEquals(10, asciiOut.get("charCount").asInt)
        assertEquals(10, asciiOut.get("bytes").asInt)
        assertEquals(20, asciiOut.get("originalCharCount").asInt)
        assertEquals(20, asciiOut.get("originalBytes").asInt)
        assertTrue(asciiOut.get("truncated").asBoolean)
        assertEquals(10, asciiOut.get("truncatedAtChar").asInt)
        assertEquals(10, asciiOut.get("maxCodeChars").asInt)
        assertEquals("a".repeat(10), asciiOut.get("code").asString)

        // 第 10 个 UTF-16 单元正好是 emoji 的高代理，必须退回到 9，不能切出半个字符。
        val emojiCode = "a".repeat(9) + "🙂" + "b"
        val emojiOut = ToolkitDecompileView.project(raw(code = emojiCode), maxCodeChars = 10)
        assertEquals(9, emojiOut.get("charCount").asInt)
        assertEquals("a".repeat(9), emojiOut.get("code").asString)
        assertTrue(emojiOut.get("truncated").asBoolean)

        val exact = ToolkitDecompileView.project(raw(code = ascii), maxCodeChars = 20)
        assertFalse(exact.get("truncated").asBoolean)
        assertTrue(exact.get("truncatedAtChar").isJsonNull)
        assertEquals(0, ToolkitDecompileView.project(raw(code = ""), maxCodeChars = 0).get("charCount").asInt)
    }

    @Test
    fun boundaryUserSpecifiedHasHighestPriority() {
        val out = ToolkitDecompileView.boundary(
            forceFunction = true,
            forcedSize = 64,
            cfgSize = 128,
            symbolRange = 256,
            detectedSize = 512,
        )
        assertEquals("user_specified", out.get("source").asString)
        assertEquals("high", out.get("confidence").asString)
        assertEquals(64, out.get("functionSize").asInt)
        assertEquals("user_specified", out.get("sizeSource").asString)
        assertEquals("yes_explicit", out.get("addressOwnership").asString)
        assertTrue(out.get("requestedStartForced").asBoolean)
        assertTrue(noteOf(out).contains("显式指定"))

        val forcedSizeOnly = ToolkitDecompileView.boundary(false, 32, 128, 256, 512)
        assertEquals("user_specified", forcedSizeOnly.get("source").asString)
        assertEquals(32, forcedSizeOnly.get("functionSize").asInt)

        val startOnly = ToolkitDecompileView.boundary(true, null, 128, 256, 512)
        assertEquals("user_specified", startOnly.get("source").asString)
        assertEquals(128, startOnly.get("functionSize").asInt)
        assertEquals("cfg", startOnly.get("sizeSource").asString)
        assertTrue(noteOf(startOnly).contains("未指定长度"))
    }

    @Test
    fun boundarySymbolRangeIsLowConfidenceAndWarnsJniOffsetDoesNotProveOwnership() {
        val out = ToolkitDecompileView.boundary(false, null, 128, 256, 512)
        assertEquals("symbol_range", out.get("source").asString)
        assertEquals("low", out.get("confidence").asString)
        assertEquals(256, out.get("functionSize").asInt)
        assertEquals("symbol_range", out.get("sizeSource").asString)
        assertEquals("unproven_symbol_range", out.get("addressOwnership").asString)
        val note = noteOf(out)
        assertTrue(note.contains("JNI_OnLoad"))
        assertTrue(note.contains("不能证明"))
        assertFalse(note.contains("已确认属于"))
    }

    @Test
    fun boundaryFallsBackToCfgThenAutoCandidateThenUnknown() {
        val cfg = ToolkitDecompileView.boundary(false, null, 128, null, 512)
        assertEquals("cfg", cfg.get("source").asString)
        assertEquals("medium", cfg.get("confidence").asString)
        assertEquals("probably_cfg", cfg.get("addressOwnership").asString)

        val detected = ToolkitDecompileView.boundary(false, null, null, null, 512)
        assertEquals("auto_candidate", detected.get("source").asString)
        assertEquals("low", detected.get("confidence").asString)
        assertEquals("unproven_heuristic", detected.get("addressOwnership").asString)

        val none = ToolkitDecompileView.boundary(false, null, null, null, null)
        assertEquals("auto_candidate", none.get("source").asString)
        assertEquals("none", none.get("confidence").asString)
        assertTrue(none.get("functionSize").isJsonNull)
        assertEquals("unknown", none.get("addressOwnership").asString)
    }

    @Test
    fun boundaryIgnoresInvalidSizesAndRecordsEvidence() {
        val out = ToolkitDecompileView.boundary(false, null, 128, -5, -1)
        assertEquals("cfg", out.get("source").asString)
        assertEquals(128, out.get("functionSize").asInt)
        val evidence = out.getAsJsonObject("evidence")
        assertEquals(-5, evidence.get("symbolRange").asInt)
        assertEquals(-1, evidence.get("detectedSize").asInt)
        assertTrue(evidence.get("normalizedSymbolRange").isJsonNull)
        val invalid = evidence.getAsJsonArray("invalidInputs")
        assertTrue(invalid.size() >= 2)
        assertTrue((0 until invalid.size()).any { invalid.get(it).asString.contains("symbolRange") })
    }

    @Test
    fun classifyXrefCoversAllKindsAndAliases() {
        val expected = mapOf(
            "direct_call" to "direct_call",
            "direct_jump" to "direct_jump",
            "relocation_pointer" to "relocation_pointer",
            "static_data_reference" to "static_data_reference",
            "inferred_indirect_reference" to "inferred_indirect_reference",
            "uncovered_dynamic_reference" to "uncovered_dynamic_reference",
        )
        expected.forEach { (input, kind) -> assertEquals(kind, ToolkitDecompileView.classifyXref(input)) }

        val aliases = mapOf(
            "call" to "direct_call",
            "direct-call" to "direct_call",
            "  Direct_Call " to "direct_call",
            "jump" to "direct_jump",
            "jmp" to "direct_jump",
            "reloc" to "relocation_pointer",
            "relocation" to "relocation_pointer",
            "static-data" to "static_data_reference",
            "static_data" to "static_data_reference",
            "indirect" to "inferred_indirect_reference",
            "inferred_indirect" to "inferred_indirect_reference",
            "dynamic" to "uncovered_dynamic_reference",
            "uncovered_dynamic" to "uncovered_dynamic_reference",
            // r2 axtj 常见的 type/opcode 取值
            "CALL" to "direct_call",
            "CODE" to "direct_jump",
            "DATA" to "static_data_reference",
            "STRING" to "static_data_reference",
        )
        aliases.forEach { (input, kind) -> assertEquals(input, kind, ToolkitDecompileView.classifyXref(input)) }
    }

    @Test
    fun classifyXrefUnknownKeepsRawValueOnlyInEvidence() {
        assertEquals("unknown", ToolkitDecompileView.classifyXref("magic_kind"))
        assertEquals("unknown", ToolkitDecompileView.classifyXref(""))
        val evidence = ToolkitDecompileView.xrefEvidence("magic_kind", false, false, false)
        assertEquals("unknown", evidence.get("evidenceKind").asString)
        assertEquals("magic_kind", evidence.get("rawKind").asString)
        assertEquals("unknown", evidence.get("confidence").asString)
        assertFalse(evidence.get("directReference").asBoolean)
    }

    @Test
    fun xrefEvidenceDirectCallAndJumpAreStrongButStillBounded() {
        val call = ToolkitDecompileView.xrefEvidence("direct_call", false, true, false)
        assertEquals("direct_call", call.get("evidenceKind").asString)
        assertEquals("high", call.get("confidence").asString)
        assertTrue(call.get("directReference").asBoolean)
        assertEquals("not_applicable", call.get("noDirectReferenceConclusion").asString)
        assertFalse(call.get("callerAbsenceConclusive").asBoolean)
        assertTrue(notesOf(call).any { it.contains("直接 call") })
        assertTrue(notesOf(call).any { it.contains("没有直接引用不等于没有调用者") })

        val jump = ToolkitDecompileView.xrefEvidence("direct-jump", false, false, false)
        assertEquals("direct_jump", jump.get("evidenceKind").asString)
        assertEquals("high", jump.get("confidence").asString)
        assertTrue(notesOf(jump).any { it.contains("尾调用") || it.contains("CFG") })
    }

    @Test
    fun xrefEvidenceUncoveredDynamicExplicitlySeparatesAbsenceFromCallers() {
        val evidence = ToolkitDecompileView.xrefEvidence(
            "uncovered_dynamic_reference",
            hasRelocation = false,
            staticallyResolvable = false,
            dynamicCovered = false,
        )
        assertEquals("uncovered_dynamic_reference", evidence.get("evidenceKind").asString)
        assertEquals("none", evidence.get("confidence").asString)
        assertFalse(evidence.get("directReference").asBoolean)
        assertEquals("cannot_conclude_absent", evidence.get("noDirectReferenceConclusion").asString)
        assertFalse(evidence.get("callerAbsenceConclusive").asBoolean)
        val notes = notesOf(evidence)
        assertTrue(notes.any { it.contains("没有直接引用不等于没有调用者") })
        assertTrue(notes.any { it.contains("动态覆盖未完成") })
    }

    @Test
    fun xrefEvidenceRelocationStaticDataAndIndirectStayConservative() {
        val relocation = ToolkitDecompileView.xrefEvidence("relocation_pointer", true, true, false)
        assertEquals("relocation_pointer", relocation.get("evidenceKind").asString)
        assertEquals("medium", relocation.get("confidence").asString)
        assertTrue(notesOf(relocation).any { it.contains("不能证明它被当作函数调用") })

        val relocationNoFlag = ToolkitDecompileView.xrefEvidence("reloc", false, false, false)
        assertEquals("low", relocationNoFlag.get("confidence").asString)

        val data = ToolkitDecompileView.xrefEvidence("static_data_reference", false, true, false)
        assertEquals("low", data.get("confidence").asString)
        assertTrue(notesOf(data).any { it.contains("数据引用不等于调用") })

        val dataUnresolved = ToolkitDecompileView.xrefEvidence("static_data", false, false, false)
        assertEquals("none", dataUnresolved.get("confidence").asString)

        val indirect = ToolkitDecompileView.xrefEvidence("indirect", false, false, true)
        assertEquals("low", indirect.get("confidence").asString)
        assertTrue(notesOf(indirect).any { it.contains("静态推断") })
        assertTrue(notesOf(indirect).any { it.contains("动态覆盖已执行") })
    }
    // ---- T5：大函数分段投影与体量摘要（manifest-detector 中途失败，由 Lead 补测） ----

    private fun notesOf2(json: com.google.gson.JsonObject): List<String> =
        json.getAsJsonArray("notes").map { it.asString }

    @Test
    fun projectSegmentsSplitsInOrderAndContinuesFromNextOffset() {
        val text = (1..40).joinToString("\n") { "line" + it + " body" }
        val first = ToolkitDecompileView.projectSegments(text, "0x1000", maxCodeChars = 60, maxSegments = 2)
        assertEquals("segmented", first.get("view").asString)
        assertEquals(text.length, first.get("totalChars").asInt)
        assertTrue(first.get("segmentCount").asInt in 1..2)
        assertTrue(first.get("hasMore").asBoolean)
        assertTrue(first.get("nextOffset").asInt > 0)
        assertTrue(first.get("returnedChars").asInt <= text.length)
        assertEquals("static_inferred", first.get("evidenceLevel").asString)
        assertEquals(true, first.get("relocAware").asBoolean)
        assertEquals(true, first.get("omittedAnnotations").asBoolean)
        assertTrue(notesOf2(first).any { it.contains("static_inferred") })
        assertTrue(notesOf2(first).any { it.contains("relocAware=true") })
        assertTrue(notesOf2(first).any { it.contains("不是运行时确认") })

        val second = ToolkitDecompileView.projectSegments(text, "0x1000", maxCodeChars = 60,
            maxSegments = 2, offset = first.get("nextOffset").asInt)
        assertEquals(first.get("nextOffset").asInt, second.get("offset").asInt)
        val firstCode = first.getAsJsonArray("segments")[0].asJsonObject.get("code").asString
        val secondCode = second.getAsJsonArray("segments")[0].asJsonObject.get("code").asString
        assertFalse(secondCode.isNotEmpty() && secondCode == firstCode)
    }

    @Test
    fun projectSegmentsTruncatesOversizedSingleLine() {
        val text = "x".repeat(500)
        val json = ToolkitDecompileView.projectSegments(text, "0x2000", maxCodeChars = 64, maxSegments = 4)
        val segment = json.getAsJsonArray("segments")[0].asJsonObject
        assertEquals(true, segment.get("truncated").asBoolean)
        assertTrue(segment.get("code").asString.length <= 64)
        assertTrue(json.get("truncated").asBoolean)
    }

    @Test
    fun projectSegmentsClampsBudgetsAndHandlesEmptyInput() {
        val clamped = ToolkitDecompileView.projectSegments("a\nb\n", "0x1", maxCodeChars = 0, maxSegments = 0)
        assertTrue(clamped.get("maxCodeChars").asInt >= 1)
        assertEquals(1, clamped.get("maxSegments").asInt)
        assertEquals(true, clamped.get("maxSegmentsClamped").asBoolean)
        val empty = ToolkitDecompileView.projectSegments("", "0x1", maxCodeChars = 10, maxSegments = 4)
        assertEquals(0, empty.get("totalChars").asInt)
        assertEquals(0, empty.get("segmentCount").asInt)
        assertFalse(empty.get("hasMore").asBoolean)
    }

    @Test
    fun summarizeBigFunctionUsesPdfjWhenPresent() {
        val pdfj = "{\"blocks\":[{\"ops\":[1,2,3]},{\"ops\":[4,5]}]}"
        val pdg = "void f() {\n  a();\n  b();\n}"
        val json = ToolkitDecompileView.summarizeBigFunction(pdg, pdfj, "0x3a1e4")
        assertEquals("big-function-summary", json.get("view").asString)
        assertEquals(5, json.get("instructionCount").asInt)
        assertEquals(2, json.get("basicBlockCount").asInt)
        // pseudoCodeLines = 1 + 换行数（"void f() {\n  a();\n  b();\n}" 有 3 个换行 → 4 行）
        assertEquals(4, json.get("pseudoCodeLines").asInt)
        assertEquals(pdg.length, json.get("pseudoCodeChars").asInt)
        assertEquals("static_inferred", json.get("evidenceLevel").asString)
        assertEquals(true, json.get("relocAware").asBoolean)
        assertEquals(true, json.get("pdfjParsable").asBoolean)
    }

    @Test
    fun summarizeBigFunctionKeepsCountsNullWithoutPdfj() {
        val json = ToolkitDecompileView.summarizeBigFunction("void f() {}", null, "0x3a1e4")
        assertFalse(json.get("pdfjPresent").asBoolean)
        assertFalse(json.get("pdfjParsable").asBoolean)
        assertEquals(com.google.gson.JsonNull.INSTANCE, json.get("instructionCount"))
        assertEquals(com.google.gson.JsonNull.INSTANCE, json.get("basicBlockCount"))
        assertTrue(notesOf2(json).any { it.contains("pdfj 缺失") })
        val bad = ToolkitDecompileView.summarizeBigFunction("void f() {}", "{not json", "0x3a1e4")
        assertEquals(com.google.gson.JsonNull.INSTANCE, bad.get("instructionCount"))
        assertTrue(notesOf2(bad).any { it.contains("不是合法 JSON") })
    }
}

