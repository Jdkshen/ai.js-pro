package com.jdkshen.aijspro.mcp

import org.junit.Assert.*
import org.junit.Test

class McpSyntaxCheckerTest {
    @Test fun rhinoOnlyCompilesAndNeverExecutesSideEffectsOrLoops() {
        val result = McpSyntaxChecker.check("throw new Error('must not run'); while(true) {}", "rhino")
        assertTrue(result.get("valid").asBoolean)
        assertFalse(result.get("executed").asBoolean)
    }
    @Test fun rhinoReportsSyntaxErrorsWithLocation() {
        val result = McpSyntaxChecker.check("var a=1;\nvar b = ;", "rhino")
        assertFalse(result.get("valid").asBoolean)
        assertEquals(2, result.getAsJsonArray("errors")[0].asJsonObject.get("line").asInt)
    }
    @Test fun rhinoAcceptsCurrentTemplateAndXmlCompatibilitySyntax() {
        val result = McpSyntaxChecker.check("var a = `hello`; var node = <vertical><text text=\"hi\"/></vertical>;", "rhino")
        assertTrue(result.toString(), result.get("valid").asBoolean)
        assertTrue(result.get("preprocessed").asBoolean)
    }
}
