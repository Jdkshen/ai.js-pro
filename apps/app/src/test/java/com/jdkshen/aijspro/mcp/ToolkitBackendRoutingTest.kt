package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolkitBackendRoutingTest {

    private fun jsonArrayContains(json: JsonObject, field: String, value: String): Boolean =
        json.getAsJsonArray(field).any { it.asString == value }

    @Test fun requirementRecognizesAppOnlyCommandsAndPathForms() {
        assertEquals(ToolkitBackendRouting.Requirement.APP_ONLY, ToolkitBackendRouting.requirement("javac"))
        assertEquals(ToolkitBackendRouting.Requirement.APP_ONLY, ToolkitBackendRouting.requirement("java -version"))
        assertEquals(ToolkitBackendRouting.Requirement.APP_ONLY, ToolkitBackendRouting.requirement("/data/user/0/com.example/files/javac"))
        assertEquals(ToolkitBackendRouting.Requirement.APP_ONLY, ToolkitBackendRouting.requirement("env FOO=1 /data/user/0/com.example/files/keytool"))
        assertEquals(ToolkitBackendRouting.Requirement.APP_ONLY, ToolkitBackendRouting.requirement("JAVA -version"))
        assertEquals(ToolkitBackendRouting.Requirement.APP_ONLY, ToolkitBackendRouting.requirement("d8 --version"))
        assertEquals(ToolkitBackendRouting.Requirement.APP_ONLY, ToolkitBackendRouting.requirement("aapt2 dump badging app.apk"))
        assertEquals(ToolkitBackendRouting.Requirement.APP_ONLY, ToolkitBackendRouting.requirement("zipalign -c 4 app.apk"))
        assertEquals(ToolkitBackendRouting.Requirement.APP_ONLY, ToolkitBackendRouting.requirement("apksigner verify app.apk"))
    }

    @Test fun requirementRecognizesShellOnlyCommandsAndWrappers() {
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("pm list packages"))
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("/system/bin/pm list"))
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("PM list packages"))
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("sh -c \"pm list\""))
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("sh -lc \"/system/bin/pm list\""))
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("env FOO=1 /system/bin/pm list"))
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("su -c 'pm list'"))
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("busybox pm list"))
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("timeout 5 logcat -d"))
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("dumpsys activity"))
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("uiautomator dump"))
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("settings get global adb_enabled"))
        assertEquals(ToolkitBackendRouting.Requirement.SHELL_ONLY, ToolkitBackendRouting.requirement("input keyevent 3"))
    }

    @Test fun requirementDefaultsToBothForOrdinaryCommands() {
        assertEquals(ToolkitBackendRouting.Requirement.BOTH, ToolkitBackendRouting.requirement("ls -la"))
        assertEquals(ToolkitBackendRouting.Requirement.BOTH, ToolkitBackendRouting.requirement("cat file.txt"))
        assertEquals(ToolkitBackendRouting.Requirement.BOTH, ToolkitBackendRouting.requirement("node -e 'console.log(1)'"))
        assertEquals(ToolkitBackendRouting.Requirement.BOTH, ToolkitBackendRouting.requirement(""))
        assertEquals(ToolkitBackendRouting.Requirement.BOTH, ToolkitBackendRouting.requirement("   "))
    }

    @Test fun checkExplainsRequiredBackendAndSuggestsSwitch() {
        val mismatch = ToolkitBackendRouting.check("app", "pm list packages")
        assertFalse(mismatch.get("ok").asBoolean)
        assertEquals("SHELL_ONLY", mismatch.get("requirement").asString)
        assertEquals("shizuku", mismatch.get("requiredBackend").asString)
        assertTrue(mismatch.get("hint").asString.contains("shizuku"))
        assertTrue(mismatch.get("hint").asString.contains("建议改用 backend=shizuku"))
        assertTrue(jsonArrayContains(mismatch, "nextActions", "改用 backend=shizuku 重试该命令"))
        assertEquals("shizuku", mismatch.get("autoRouteTarget").asString)
        assertTrue(mismatch.get("autoRouteAllowed").asBoolean)
    }

    @Test fun checkExplainsAppBackendForJavacOnShell() {
        val mismatch = ToolkitBackendRouting.check("shizuku", "javac -version")
        assertFalse(mismatch.get("ok").asBoolean)
        assertEquals("APP_ONLY", mismatch.get("requirement").asString)
        assertEquals("app", mismatch.get("requiredBackend").asString)
        assertTrue(mismatch.get("hint").asString.contains("app"))
        assertTrue(mismatch.get("hint").asString.contains("建议改用 backend=app"))
        assertTrue(jsonArrayContains(mismatch, "nextActions", "改用 backend=app 重试该命令"))
        assertEquals("app", mismatch.get("autoRouteTarget").asString)
    }

    @Test fun checkAllowsBothBackendsForOrdinaryCommands() {
        val both = ToolkitBackendRouting.check("app", "ls -la")
        assertTrue(both.get("ok").asBoolean)
        assertEquals("BOTH", both.get("requirement").asString)
        assertEquals("", both.get("hint").asString)
        assertFalse(both.get("autoRouteAllowed").asBoolean)
        assertNull(both.get("autoRouteTarget"))
    }

    @Test fun checkRejectsUnknownBackendName() {
        val result = ToolkitBackendRouting.check("adb", "ls")
        assertFalse(result.get("ok").asBoolean)
        assertTrue(result.get("hint").asString.contains("backend=app"))
        assertTrue(result.get("hint").asString.contains("backend=shizuku"))
    }

    @Test fun autoRouteOnlyAllowsReadOnlyWhitelist() {
        assertEquals("shizuku", ToolkitBackendRouting.autoRouteTarget("app", "pm list packages"))
        assertEquals("shizuku", ToolkitBackendRouting.autoRouteTarget("app", "pm path com.example.app"))
        assertEquals("shizuku", ToolkitBackendRouting.autoRouteTarget("app", "pm dump com.example.app"))
        assertEquals("shizuku", ToolkitBackendRouting.autoRouteTarget("app", "logcat -d"))
        assertEquals("shizuku", ToolkitBackendRouting.autoRouteTarget("app", "logcat -d -v time"))
        assertEquals("shizuku", ToolkitBackendRouting.autoRouteTarget("app", "dumpsys activity"))
        assertEquals("shizuku", ToolkitBackendRouting.autoRouteTarget("app", "settings get global adb_enabled"))
        assertEquals("shizuku", ToolkitBackendRouting.autoRouteTarget("app", "sh -c \"pm list\""))
        assertEquals("app", ToolkitBackendRouting.autoRouteTarget("shizuku", "javac -version"))
    }

    @Test fun autoRouteNeverRoutesWritesOrStreams() {
        assertNull(ToolkitBackendRouting.autoRouteTarget("app", "pm install app.apk"))
        assertNull(ToolkitBackendRouting.autoRouteTarget("app", "pm uninstall com.example.app"))
        assertNull(ToolkitBackendRouting.autoRouteTarget("app", "pm clear com.example.app"))
        assertNull(ToolkitBackendRouting.autoRouteTarget("app", "am start -n com.example/.Main"))
        assertNull(ToolkitBackendRouting.autoRouteTarget("app", "settings put global adb_enabled 1"))
        assertNull(ToolkitBackendRouting.autoRouteTarget("app", "logcat -c"))
        assertNull(ToolkitBackendRouting.autoRouteTarget("app", "logcat"))
        assertNull(ToolkitBackendRouting.autoRouteTarget("app", "logcat -d -f /sdcard/log.txt"))
        assertNull(ToolkitBackendRouting.autoRouteTarget("shizuku", "javac Main.java"))
        assertNull(ToolkitBackendRouting.autoRouteTarget("shizuku", "aapt2 compile -o out res/"))
        assertNull(ToolkitBackendRouting.autoRouteTarget("app", "ls -la"))
    }

    @Test fun capabilityMatrixListsBackendsAndShellLimitations() {
        val matrix = ToolkitBackendRouting.capabilityMatrix()
        assertEquals(1, matrix.get("version").asInt)
        assertTrue(jsonArrayContains(matrix.getAsJsonObject("requirements"), "APP_ONLY", "javac"))
        assertTrue(jsonArrayContains(matrix.getAsJsonObject("requirements"), "SHELL_ONLY", "pm"))
        assertTrue(jsonArrayContains(matrix.getAsJsonObject("requirements"), "SHELL_ONLY", "logcat"))
        val shell = matrix.getAsJsonObject("shellCapabilities")
        assertTrue(shell.get("grep").asString.contains("\\b"))
        assertTrue(shell.get("grep").asString.contains("\\s"))
        assertTrue(shell.get("cat").asString.contains("-n"))
        assertTrue(shell.get("zip").asString.contains("tar"))
        assertTrue(shell.get("python").asString.contains("node"))
        assertTrue(shell.get("node").asString.contains("可用"))
        assertFalse(matrix.getAsJsonObject("autoRoute").get("default").asBoolean)
        assertTrue(matrix.get("summary").asString.contains("autoRoute"))
    }

    @Test fun capabilitySummaryMentionsBothBackendsAndShellLimits() {
        val summary = ToolkitBackendRouting.capabilitySummary()
        assertTrue(summary.contains("app"))
        assertTrue(summary.contains("shizuku"))
        assertTrue(summary.contains("grep"))
        assertTrue(summary.contains("zip"))
        assertTrue(summary.contains("autoRoute"))
    }
}
