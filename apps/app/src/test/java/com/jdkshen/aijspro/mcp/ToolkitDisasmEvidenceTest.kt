package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ToolkitDisasmEvidence 纯 JVM 单测：形式描述、重定位硬化命令、能力差异、annotate 字段。 */
class ToolkitDisasmEvidenceTest {
    private fun strings(result: JsonObject, key: String): List<String> {
        val array = result.getAsJsonArray(key) ?: return emptyList()
        return (0 until array.size()).map { array.get(it).asString }
    }

    private fun joined(result: JsonObject, key: String): String = strings(result, key).joinToString(" ")

    @Test
    fun evidenceLevelsMatchSection12() {
        assertEquals(
            listOf(
                "documented",
                "static_inferred",
                "static_confirmed",
                "runtime_confirmed",
                "post_change_verified",
            ),
            ToolkitDisasmEvidence.EVIDENCE_LEVELS,
        )
        assertEquals("e bin.relocs.apply=true", ToolkitDisasmEvidence.RELOC_HARDENING_COMMAND)
    }

    @Test
    fun describeRawPdfStatesRelocationGapExplicitly() {
        val view = ToolkitDisasmEvidence.describe("PDF")
        assertEquals("raw_disasm", view.kind)
        assertFalse(view.relocAware)
        assertEquals("static_confirmed", view.evidenceLevel)
        val notes = view.notes.joinToString(" ")
        assertTrue(notes.contains("adrp/add"))
        assertTrue(notes.contains("0 命中"))
        assertTrue(notes.contains("bin.relocs.apply=true"))
        assertTrue(notes.contains("pdg"))
        assertTrue(notes.contains("不能当成"))

        assertEquals("raw_disasm", ToolkitDisasmEvidence.describe("raw_disasm").kind)
        assertEquals("raw_disasm", ToolkitDisasmEvidence.describe(" asm ").kind)
    }

    @Test
    fun describeDecompiledAndStructuredKinds() {
        val decompiled = ToolkitDisasmEvidence.describe("pdg")
        assertEquals("decompiled", decompiled.kind)
        assertTrue(decompiled.relocAware)
        assertEquals("static_inferred", decompiled.evidenceLevel)
        assertTrue(decompiled.notes.joinToString(" ").contains("解析重定位"))
        assertTrue(decompiled.notes.joinToString(" ").contains("静态推断"))

        val functionJson = ToolkitDisasmEvidence.describe("pdfj")
        assertEquals("function_json", functionJson.kind)
        assertFalse(functionJson.relocAware)
        assertEquals("static_confirmed", functionJson.evidenceLevel)
        assertTrue(functionJson.notes.joinToString(" ").contains("重定位"))

        val xrefs = ToolkitDisasmEvidence.describe("axtj")
        assertEquals("xrefs", xrefs.kind)
        assertFalse(xrefs.relocAware)
        assertEquals("static_confirmed", xrefs.evidenceLevel)
        assertTrue(xrefs.notes.joinToString(" ").contains("0 条引用"))

        val functions = ToolkitDisasmEvidence.describe("aflj")
        assertEquals("functions", functions.kind)
        assertFalse(functions.relocAware)
        assertEquals("static_inferred", functions.evidenceLevel)
        assertTrue(functions.notes.joinToString(" ").contains("启发式"))
    }

    @Test
    fun describeUnknownKindIsConservativeAndKeepsRawKind() {
        val view = ToolkitDisasmEvidence.describe("magic_output")
        assertEquals("unknown", view.kind)
        assertFalse(view.relocAware)
        assertEquals("documented", view.evidenceLevel)
        val notes = view.notes.joinToString(" ")
        assertTrue(notes.contains("magic_output"))
        assertTrue(notes.contains("最低可信"))
        assertTrue(notes.contains("pdf/pdg/pdfj/axtj"))
    }

    @Test
    fun hardeningCommandsOnlyForRelocSensitiveKindsWhenNotApplied() {
        val command = ToolkitDisasmEvidence.RELOC_HARDENING_COMMAND
        assertEquals(listOf(command), ToolkitDisasmEvidence.hardeningCommands("pdf", false))
        assertEquals(listOf(command), ToolkitDisasmEvidence.hardeningCommands("pdfj", false))
        assertEquals(listOf(command), ToolkitDisasmEvidence.hardeningCommands("axtj", false))
        assertEquals(emptyList<String>(), ToolkitDisasmEvidence.hardeningCommands("pdf", true))
        assertEquals(emptyList<String>(), ToolkitDisasmEvidence.hardeningCommands("pdg", false))
        assertEquals(emptyList<String>(), ToolkitDisasmEvidence.hardeningCommands("aflj", false))
        // 未知类型不给出针对性硬化命令：无法确认输出形式；annotate 会提示按最低可信处理。
        assertEquals(emptyList<String>(), ToolkitDisasmEvidence.hardeningCommands("magic", false))
    }

    @Test
    fun capabilityGapPdfToPdgRequiresPdgOrRelocApply() {
        val gap = ToolkitDisasmEvidence.capabilityGap("pdf", "pdg")
        assertEquals("raw_disasm", gap.get("fromKind").asString)
        assertEquals("decompiled", gap.get("toKind").asString)
        assertEquals("pdf", gap.get("requestedFromKind").asString)
        assertEquals("pdg", gap.get("requestedToKind").asString)
        assertFalse(gap.get("fromRelocAware").asBoolean)
        assertTrue(gap.get("toRelocAware").asBoolean)
        assertTrue(gap.get("gap").asBoolean)
        assertTrue(gap.get("noHitIsNotConclusion").asBoolean)
        val requirements = strings(gap, "requirements")
        assertTrue(requirements.contains("pdg"))
        assertTrue(requirements.contains(ToolkitDisasmEvidence.RELOC_HARDENING_COMMAND))
        assertTrue(strings(gap, "missingEvidence").isNotEmpty())
        val note = gap.get("note").asString
        assertTrue(note.contains("需要 pdg 或 relocs.apply=true"))
        assertTrue(note.contains("重定位"))
    }

    @Test
    fun capabilityGapDowngradeSameKindAndCompletelyStatic() {
        val downgrade = ToolkitDisasmEvidence.capabilityGap("pdg", "pdf")
        assertTrue(downgrade.get("gap").asBoolean)
        assertTrue(downgrade.get("note").asString.contains("降级"))

        val same = ToolkitDisasmEvidence.capabilityGap("axtj", "xrefs")
        assertFalse(same.get("gap").asBoolean)
        assertTrue(same.get("note").asString.contains("同一反汇编形式"))
        assertEquals("xrefs", same.get("fromKind").asString)

        val bothStatic = ToolkitDisasmEvidence.capabilityGap("pdf", "pdfj")
        assertFalse(bothStatic.get("gap").asBoolean)
        assertTrue(bothStatic.get("note").asString.contains("都不能解析重定位"))
        assertTrue(bothStatic.get("noHitIsNotConclusion").asBoolean)
    }

    @Test
    fun capabilityGapUnknownIsConservative() {
        val gap = ToolkitDisasmEvidence.capabilityGap("pdf", "magic")
        assertTrue(gap.get("gap").asBoolean)
        assertTrue(gap.get("note").asString.contains("未知类型"))
        assertEquals("unknown", gap.get("toKind").asString)
    }

    @Test
    fun annotateRawPdfAddsFieldsAndWarnsZeroHits() {
        val result = ToolkitIo.json("code" to "mov x0, x1", "existing" to 1)
        val returned = ToolkitDisasmEvidence.annotate(result, "pdf", relocsApply = false)
        assertSame(result, returned)
        assertEquals("mov x0, x1", result.get("code").asString)
        assertEquals(1, result.get("existing").asInt)
        assertEquals("raw_disasm", result.get("disasmKind").asString)
        assertEquals("pdf", result.get("requestedKind").asString)
        assertFalse(result.get("relocAware").asBoolean)
        assertEquals("static_confirmed", result.get("evidenceLevel").asString)
        val detail = result.getAsJsonObject("evidenceLevelDetail")
        assertEquals("static_confirmed", detail.get("code").asString)
        assertEquals("静态确认", detail.get("zh").asString)
        assertFalse(result.get("relocsApply").asBoolean)
        assertTrue(result.get("noHitIsNotConclusion").asBoolean)
        val notes = joined(result, "capabilityNotes")
        assertTrue(notes.contains("adrp/add"))
        assertTrue(notes.contains("0 命中"))
        assertTrue(notes.contains("relocsApply=false"))
        val commands = strings(result, "hardeningCommands")
        assertEquals(listOf(ToolkitDisasmEvidence.RELOC_HARDENING_COMMAND), commands)
    }

    @Test
    fun annotateWithRelocsApplyUpgradesRawVisibility() {
        val result = ToolkitIo.json("code" to "x")
        ToolkitDisasmEvidence.annotate(result, "pdfj", relocsApply = true)
        assertEquals("function_json", result.get("disasmKind").asString)
        assertTrue(result.get("relocAware").asBoolean)
        assertEquals("static_confirmed", result.get("evidenceLevel").asString)
        assertFalse(result.get("noHitIsNotConclusion").asBoolean)
        assertTrue(joined(result, "capabilityNotes").contains("relocsApply=true"))
        assertTrue(result.getAsJsonArray("hardeningCommands").size() == 0)
    }

    @Test
    fun annotateDecompiledIsRelocAwareButStaticInferred() {
        val result = ToolkitIo.json("code" to "return 1")
        ToolkitDisasmEvidence.annotate(result, "pdg", relocsApply = false)
        assertEquals("decompiled", result.get("disasmKind").asString)
        assertTrue(result.get("relocAware").asBoolean)
        assertEquals("static_inferred", result.get("evidenceLevel").asString)
        assertEquals("static_inferred", result.getAsJsonObject("evidenceLevelDetail").get("code").asString)
        assertFalse(result.get("noHitIsNotConclusion").asBoolean)
        assertEquals(0, result.getAsJsonArray("hardeningCommands").size())
    }

    @Test
    fun annotateUnknownKindIsDocumentedAndKeepsRequestedKind() {
        val result = ToolkitIo.json("whatever" to true)
        ToolkitDisasmEvidence.annotate(result, "magic", relocsApply = true)
        assertEquals("unknown", result.get("disasmKind").asString)
        assertEquals("magic", result.get("requestedKind").asString)
        assertFalse(result.get("relocAware").asBoolean)
        assertEquals("documented", result.get("evidenceLevel").asString)
        assertEquals("documented", result.getAsJsonObject("evidenceLevelDetail").get("code").asString)
        assertTrue(result.get("noHitIsNotConclusion").asBoolean)
        assertEquals(0, result.getAsJsonArray("hardeningCommands").size())
        assertTrue(joined(result, "capabilityNotes").contains("未知"))
    }
}
