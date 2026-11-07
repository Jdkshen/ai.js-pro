package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpBuildToolsTest {
    @Test
    fun readinessSummaryMakesMissingCompileComponentsObvious() {
        val summary = McpBuildTools.readiness(
            mapOf("java" to "/system/bin/java", "javac" to "/system/bin/javac", "aapt2" to "/sdk/aapt2", "apksigner" to "/sdk/apksigner"),
            builtInD8 = true,
            builtInSmali = true,
        )
        assertFalse(summary.get("ready").asBoolean)
        assertEquals(listOf("python3", "aidl"), summary.getAsJsonArray("missing").map { it.asString })
        assertEquals(listOf("jdk17", "aapt2", "d8", "apksigner"), summary.getAsJsonArray("present").map { it.asString })
        assertEquals(2, summary.get("missingCount").asInt)
    }

    @Test
    fun readinessSummaryCanBeReadyWithOnlyBuiltInD8() {
        val summary = McpBuildTools.readiness(
            mapOf("java" to "/system/bin/java", "javac" to "/system/bin/javac", "aapt2" to "/sdk/aapt2",
                "apksigner" to "/sdk/apksigner", "python3" to "/data/py-tree/bin/python3", "aidl" to "/data/aidl-tree/bin/aidl"),
            builtInD8 = true,
            builtInSmali = true,
        )
        assertTrue(summary.get("ready").asBoolean)
        assertEquals(0, summary.getAsJsonArray("missing").size())
        assertTrue(summary.getAsJsonArray("present").map { it.asString }.contains("d8"))
    }

    @Test
    fun identifiesPythonAndAidlFromRealisticInaccessibleErrors() {
        val output = """
            tools/bin/python3[4]: /data/user/0/com.example/files/py-tree/install/bin/python3.14: inaccessible or not found
            android-sdk/build-tools/34.0.0/aidl[4]: /data/user/0/com.example/files/aidl-tree/install/aidl: inaccessible or not found
        """.trimIndent()
        assertEquals(listOf("python3", "aidl"), McpBuildTools.detectMissingComponents("sh build.sh", output))

        val payload = McpBuildTools.componentMissingPayload("sh build.sh", output)
        assertNotNull(payload)
        assertEquals("COMPONENT_MISSING", payload!!.get("code").asString)
        assertEquals(listOf("python3", "aidl"), payload.getAsJsonArray("components").map { it.asString })
        assertNotNull(payload.getAsJsonObject("error"))
        assertEquals("COMPONENT_MISSING", payload.getAsJsonObject("error").get("code").asString)
        val recovery = payload.getAsJsonObject("recovery")
        assertNotNull(recovery)
        assertTrue(recovery.has("apk_component_install"))
        assertTrue(recovery.getAsJsonObject("localDeps").getAsJsonArray("commands").size() >= 2)
        assertTrue(recovery.getAsJsonArray("nextActions").size() >= 2)
        assertTrue(recovery.getAsJsonArray("nextActions").any {
            it.asJsonObject.get("tool")?.asString == "apk_component_status"
        })
    }

    @Test
    fun doesNotMisclassifyOrdinaryFileNotFoundAsComponentMissing() {
        assertTrue(McpBuildTools.detectMissingComponents("sh build.sh", "main.js: No such file or directory").isEmpty())
        assertNull(McpBuildTools.componentMissingPayload("sh build.sh", "main.js: No such file or directory"))
    }

    @Test
    fun shellClassificationIsEnrichedWithoutDestroyingExistingFields() {
        val base = ToolkitIo.json("category" to "not_found", "kind" to "not_found")
        val output = "tools/bin/python3[4]: /data/files/py-tree/python3.14: inaccessible or not found"
        val enriched = McpBuildTools.enrichComponentFailure(base, "sh build.sh", output)
        assertEquals("COMPONENT_MISSING", enriched.get("code").asString)
        assertEquals("not_found", enriched.get("category").asString)
        assertNotNull(enriched.getAsJsonObject("error"))
        assertTrue(enriched.getAsJsonArray("nextActions").size() > 0)
    }

    @Test
    fun shellWrapperRoutingCorrectionSeparatesPermissionFromBackendSwitch() {
        val result = ToolkitIo.json(
            "command" to "sh build.sh",
            "backend" to "app",
            "output" to "AIJS_BACKEND_ROUTING routed=false requestedBackend=app effectiveBackend=app requirement=SHELL_ONLY; 该命令需要 shizuku 后端，当前是 app 后端\nbuild ok\n",
        )
        val enriched = McpBuildTools.enrichTerminalResult(result)
        assertTrue(enriched.get("routingCorrected").asBoolean)
        val output = enriched.get("output").asString
        assertTrue(output.contains("requirement=BOTH"))
        assertFalse(output.contains("requirement=SHELL_ONLY"))
        assertTrue(output.contains("sh build.sh"))
        assertTrue(output.contains("build ok"))
    }

    @Test
    fun appBackendProcessQueryGetsProcessVisibilityHint() {
        val result = ToolkitIo.json(
            "command" to "pidof shizuku_server",
            "backend" to "app",
            "output" to "",
        )
        val enriched = McpBuildTools.enrichTerminalResult(result)
        assertNotNull(enriched.getAsJsonObject("processVisibility"))
        assertTrue(enriched.getAsJsonObject("processVisibility").get("hint").asString.contains("shizuku"))
    }

    @Test
    fun terminalComponentFailureIsStructured() {
        val result = ToolkitIo.json(
            "command" to "sh build.sh",
            "backend" to "app",
            "output" to "tools/bin/python3[4]: /data/py-tree/bin/python3.14: inaccessible or not found",
        )
        val enriched = McpBuildTools.enrichTerminalResult(result)
        assertEquals("COMPONENT_MISSING", enriched.get("code").asString)
        assertNotNull(enriched.getAsJsonObject("componentError"))
        assertEquals("COMPONENT_MISSING", enriched.getAsJsonObject("componentError").get("code").asString)
        assertEquals(listOf("python3"), enriched.getAsJsonObject("componentMissing").getAsJsonArray("components").map { it.asString })
    }

    @Test
    fun baselineContentHasSelfServiceFieldsAndVersionIsStable() {
        val workspaces = ToolkitIo.json(
            "script" to 2,
            "apk" to ToolkitIo.json("used" to 5, "quota" to 16, "temporary" to 3, "ttlHint" to "temporary 会过期，过期后重新 open"),
        )
        val toolchain = ToolkitIo.json("ready" to false, "missing" to listOf("python3", "aidl"),
            "present" to listOf("jdk17", "aapt2", "d8", "apksigner"))
        val jobs = ToolkitIo.json("retention" to "6h / 64 条", "advice" to "终态立刻 result 或 export")
        val baseline = McpBuildTools.baselineContent("/root", "云镜分析", workspaces, toolchain, jobs)
        assertEquals("/root", baseline.get("root").asString)
        assertEquals("云镜分析", baseline.get("currentProject").asString)
        assertEquals("所有产出落 <root>/<项目名>/", baseline.get("outputRule").asString)
        assertNotNull(baseline.getAsJsonObject("workspaces").getAsJsonObject("apk"))
        assertEquals(listOf("python3", "aidl"), baseline.getAsJsonObject("toolchain").getAsJsonArray("missing").map { it.asString })
        assertEquals("6h / 64 条", baseline.getAsJsonObject("jobs").get("retention").asString)

        val bytes = baseline.toString().toByteArray(Charsets.UTF_8).size
        assertTrue("baseline should stay compact, actual=$bytes bytes", bytes <= 512)
        val versions = McpBaselineVersion()
        val first = versions.versionFor(baseline)
        val same = baseline.deepCopy()
        assertEquals(1, first)
        assertEquals(1, versions.versionFor(same))
        same.addProperty("currentProject", "另一个项目")
        assertEquals(2, versions.versionFor(same))
    }
}
