package com.jdkshen.aijspro.mcp

import com.jdkshen.aijspro.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** B14: serverInfo must expose a real build/version, not an empty object. */
class McpServerMetadataTest {
    @Test fun exposesNameAndRealBuildVersion() {
        val info = McpServerMetadata.toJson()
        assertFalse(info.get("name").asString.isBlank())
        assertEquals("AI.js Pro MCP", info.get("name").asString)
        assertEquals(BuildConfig.VERSION_NAME, info.get("version").asString)
        assertEquals(BuildConfig.VERSION_NAME, info.get("build").asString)
        assertEquals(BuildConfig.VERSION_CODE, info.get("versionCode").asInt)
        assertFalse(info.get("version").asString.isBlank())
    }
}
