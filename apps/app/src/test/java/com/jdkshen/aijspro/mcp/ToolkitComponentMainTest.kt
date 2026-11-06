package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolkitComponentMainTest {
    @Test
    fun emptyDecompileResultIsWarnedAndPointsToSmali() {
        val warning = ToolkitComponentMain.resultWarnings("", 0, 0, "dex_class:Lorg/opencv/core/Core\$MinMaxLocResult;")
        assertEquals("EMPTY_RESULT", warning.tString("warningCode"))
        assertTrue(warning.tBool("emptyResult"))
        assertTrue(warning.tString("warningHint").contains("apk_read_text"))
        assertTrue(warning.getAsJsonArray("nextActions").any {
            it.asJsonObject.tString("tool") == "apk_read_text"
        })
    }

    @Test
    fun truncatedDecompileResultIsWarnedWithOffsetAndActions() {
        val code = "x".repeat(300_000)
        val warning = ToolkitComponentMain.resultWarnings(code, 0, 200_000, "dex_class:Lfixture/Huge;")
        assertEquals("RESULT_TRUNCATED", warning.tString("warningCode"))
        assertTrue(warning.tBool("truncated"))
        assertFalse(warning.tBool("emptyResult"))
        assertTrue(warning.tString("warningHint").contains("offset=200000"))
        assertTrue(warning.getAsJsonArray("nextActions").any {
            it.asJsonObject.tString("tool") == "apk_decompile_class" &&
                it.asJsonObject.get("offset").asInt == 200_000
        })
    }

    @Test
    fun completeDecompileResultHasNoWarning() {
        val warning = ToolkitComponentMain.resultWarnings("class A {}", 0, 11, "dex_class:Lfixture/A;")
        assertEquals("", warning.tString("warningCode"))
        assertFalse(warning.tBool("truncated"))
        assertFalse(warning.tBool("emptyResult"))
    }

    @Test
    fun jadxFailureCodesDistinguishClassNotFound() {
        assertEquals("JADX_CLASS_NOT_FOUND", jadxFailureCode("java.lang.IllegalStateException: JADX未找到类：Lx;"))
        assertEquals("JADX_METHOD_NOT_FOUND", jadxFailureCode("java.lang.IllegalStateException: JADX未找到精确方法"))
        assertEquals("JADX_DECOMPILE_FAILED", jadxFailureCode("jadx crash"))
    }
}
