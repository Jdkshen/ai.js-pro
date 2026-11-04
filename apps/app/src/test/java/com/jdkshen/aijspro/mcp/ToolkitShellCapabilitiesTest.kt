package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolkitShellCapabilitiesTest {
    private fun arrayText(value: com.google.gson.JsonElement): String = value.toString()

    @Test
    fun matrixListsKnownBusyboxGapsAndAlternatives() {
        val matrix = ToolkitShellCapabilities.matrix()
        assertEquals("shell", matrix.tString("backend"))
        assertEquals("busybox-like", matrix.tString("shellStyle"))
        val commands = matrix.getAsJsonObject("commands")
        val grep = commands.getAsJsonObject("grep")
        assertTrue(grep.get("available").asBoolean)
        assertTrue(arrayText(grep.get("unsupported")).contains("\\b"))
        assertTrue(arrayText(grep.get("unsupported")).contains("\\s"))
        assertTrue(arrayText(grep.get("alternatives")).contains("file_search_text"))
        assertTrue(arrayText(grep.get("alternatives")).contains("node"))

        val cat = commands.getAsJsonObject("cat")
        assertTrue(cat.get("available").asBoolean)
        assertTrue(arrayText(cat.get("unsupported")).contains("-n"))
        assertTrue(arrayText(cat.get("unsupported")).contains("-A"))
        assertTrue(arrayText(cat.get("alternatives")).contains("file_read_text"))
        assertTrue(arrayText(cat.get("alternatives")).contains("sed -n"))

        for (name in listOf("zip", "jar", "7z")) {
            val tool = commands.getAsJsonObject(name)
            assertFalse("$name should be unavailable", tool.get("available").asBoolean)
            assertTrue(arrayText(tool.get("alternatives")).contains("tar") ||
                arrayText(tool.get("alternatives")).contains("zip_"))
        }
        assertTrue(commands.getAsJsonObject("tar").get("available").asBoolean)
        val python = commands.getAsJsonObject("python")
        assertFalse(python.get("available").asBoolean)
        assertEquals("broken-symlink", python.tString("status"))
        assertTrue(arrayText(python.get("alternatives")).contains("node"))
        val node = commands.getAsJsonObject("node")
        assertTrue(node.get("available").asBoolean)
        assertTrue(arrayText(node.get("notes")).contains("argv[2]"))

        assertEquals(commands.size(), matrix.getAsJsonArray("capabilities").size())
        assertTrue(arrayText(matrix.get("capabilityNotes")).contains("不同设备"))
    }

    @Test
    fun classifiesOnlyClearFailureMarkersAndFallsBackToUnknown() {
        val option = ToolkitShellCapabilities.classifyFailure(
            "grep -P '\\bword' file",
            "grep: unrecognized option '-P'\ngrep: invalid option -- 'P'",
        )
        assertEquals("unrecognized_option", option.tString("category"))
        assertEquals("high", option.tString("confidence"))
        assertTrue(option.get("capabilityGap").asBoolean)
        assertTrue(arrayText(option.get("alternatives")).contains("file_search_text"))
        assertTrue(arrayText(option.get("alternatives")).contains("node"))

        val missing = ToolkitShellCapabilities.classifyFailure("zip -r out.zip dir", "zip: not found")
        assertEquals("not_found", missing.tString("category"))
        assertEquals("command", missing.tString("scope"))
        assertTrue(missing.get("capabilityGap").asBoolean)
        assertTrue(arrayText(missing.get("alternatives")).contains("tar"))
        assertTrue(arrayText(missing.get("alternatives")).contains("zip_create"))

        val inaccessible = ToolkitShellCapabilities.classifyFailure(
            "logcat -b crash -d",
            "logcat: inaccessible or not found",
        )
        assertEquals("inaccessible", inaccessible.tString("category"))
        assertTrue(inaccessible.get("capabilityGap").asBoolean)
        assertTrue(inaccessible.tString("backendHint").contains("shizuku"))
        assertTrue(arrayText(inaccessible.get("alternatives")).contains("terminal_exec"))

        val file = ToolkitShellCapabilities.classifyFailure(
            "cat /no/such/file",
            "cat: /no/such/file: No such file or directory",
        )
        assertEquals("not_found", file.tString("category"))
        assertEquals("file", file.tString("scope"))
        assertFalse(file.get("capabilityGap").asBoolean)

        val unknown = ToolkitShellCapabilities.classifyFailure("foo --bar", "something unexpected happened")
        assertEquals("unknown", unknown.tString("category"))
        assertEquals("low", unknown.tString("confidence"))
        assertFalse(unknown.get("capabilityGap").asBoolean)
        assertTrue(unknown.tString("alternative").contains("无法从输出判断"))
    }

    @Test
    fun nodeCommandUsesScriptFileAndExplicitArgvSemantics() {
        val script = "/data/local/tmp/带 空格.js"
        val result = ToolkitShellCapabilities.nodeScriptCommand(script, listOf("a b", "--flag"))
        assertEquals("file", result.tString("mode"))
        assertTrue(result.get("avoidNodeE").asBoolean)
        val command = result.tString("command")
        assertTrue(command.startsWith("node "))
        assertTrue(command.contains("'$script'"))
        assertTrue(command.contains("'a b'"))
        assertFalse(command.contains(" -e "))
        assertTrue(arrayText(result.get("avoid")).contains("node -e"))
        assertTrue(arrayText(result.get("notes")).contains("argv[2]"))

        val argv = result.getAsJsonArray("argv").map { it.asString }
        assertEquals(listOf("node", script, "a b", "--flag"), argv)
        val semantics = result.getAsJsonObject("argvSemantics")
        assertEquals("node", semantics.tString("0"))
        assertEquals(script, semantics.tString("1"))
        assertEquals("a b", semantics.tString("2"))
        assertEquals(2, semantics.get("scriptArgsStartIndex").asInt)
        assertTrue(result.getAsJsonObject("writeFirst").get("recommended").asBoolean)

        val noArgs = ToolkitShellCapabilities.nodeScriptCommand("/tmp/x.js", emptyList())
        assertEquals(2, noArgs.getAsJsonArray("argv").size())
        assertEquals("node /tmp/x.js", noArgs.tString("command"))
    }

    @Test
    fun parseArgsHandlesQuotesSpacesEmptyAndWhitespace() {
        assertEquals(emptyList<String>(), ToolkitShellCapabilities.parseArgs(null))
        assertEquals(emptyList<String>(), ToolkitShellCapabilities.parseArgs(""))
        assertEquals(emptyList<String>(), ToolkitShellCapabilities.parseArgs("   \t\n "))
        assertEquals(listOf("--out", "/storage/emulated/0/脚本/a.txt"), ToolkitShellCapabilities.parseArgs(
            "--out \"/storage/emulated/0/脚本/a.txt\"",
        ))
        assertEquals(listOf("--out", "/path with space/a.txt", "--name", "hello world", ""), ToolkitShellCapabilities.parseArgs(
            "--out '/path with space/a.txt'   --name 'hello world' \"\"",
        ))
        assertEquals(listOf("a", "b"), ToolkitShellCapabilities.parseArgs(listOf("a", "b")))
        assertEquals(listOf("a b", ""), ToolkitShellCapabilities.parseArgs(listOf("a b", "")))
        assertEquals(listOf("a b"), ToolkitShellCapabilities.parseArgs("a\\ b"))
        assertEquals(listOf("--flag"), ToolkitShellCapabilities.parseArgs("  --flag  "))
    }

    @Test
    fun rawArgsAcceptsJsonStringArrayNullAndRejectsInvalidTypesWithoutThrowing() {
        val script = "/storage/emulated/0/脚本/test.js"
        val stringArgs = ToolkitShellCapabilities.nodeScriptCommandFromRaw(
            script,
            JsonPrimitive("--out \"/storage/emulated/0/脚本/a.txt\" --name 'hello world'"),
        )
        assertTrue(stringArgs.get("ok").asBoolean)
        assertEquals("string", stringArgs.tString("argsMode"))
        assertEquals(
            listOf("--out", "/storage/emulated/0/脚本/a.txt", "--name", "hello world"),
            stringArgs.getAsJsonArray("args").map { it.asString },
        )
        assertTrue(stringArgs.tString("command").contains("node "))

        val arrayArgs = JsonArray().apply {
            add("--out")
            add("/storage/emulated/0/脚本/a.txt")
        }
        val fromArray = ToolkitShellCapabilities.nodeScriptCommandFromRaw(script, arrayArgs)
        assertTrue(fromArray.get("ok").asBoolean)
        assertEquals("array", fromArray.tString("argsMode"))
        assertEquals(listOf("--out", "/storage/emulated/0/脚本/a.txt"), fromArray.getAsJsonArray("args").map { it.asString })

        for (raw in listOf(null, JsonNull.INSTANCE)) {
            val empty = ToolkitShellCapabilities.nodeScriptCommandFromRaw(script, raw)
            assertTrue(empty.get("ok").asBoolean)
            assertEquals(0, empty.getAsJsonArray("args").size())
            assertEquals("null", empty.tString("argsMode"))
        }

        val blankPath = ToolkitShellCapabilities.nodeScriptCommandFromRaw("   ", JsonPrimitive("a"))
        assertFalse(blankPath.get("ok").asBoolean)
        assertEquals("INVALID_ARGUMENT", blankPath.getAsJsonObject("error").tString("code"))
        assertEquals("scriptPath", blankPath.getAsJsonObject("error").tString("argument"))

        val invalidNumber = ToolkitShellCapabilities.nodeScriptCommandFromRaw(script, JsonPrimitive(42))
        assertFalse(invalidNumber.get("ok").asBoolean)
        assertEquals("INVALID_ARGUMENT", invalidNumber.getAsJsonObject("error").tString("code"))
        assertEquals("args", invalidNumber.getAsJsonObject("error").tString("argument"))
        assertEquals("primitive", invalidNumber.getAsJsonObject("error").tString("receivedType"))

        val invalidObject = ToolkitShellCapabilities.nodeScriptCommandFromRaw(script, JsonObject())
        assertFalse(invalidObject.get("ok").asBoolean)
        assertEquals("object", invalidObject.getAsJsonObject("error").tString("receivedType"))

        val invalidArrayElement = ToolkitShellCapabilities.nodeScriptCommandFromRaw(
            script,
            JsonArray().apply { add(1); add("ok") },
        )
        assertFalse(invalidArrayElement.get("ok").asBoolean)
        assertEquals("INVALID_ARGUMENT", invalidArrayElement.getAsJsonObject("error").tString("code"))
    }

    @Test
    fun crashBufferDoesNotClaimOldEntriesOrSigkillAreNewCrashes() {
        val crash = ToolkitShellCapabilities.crashBufferSemantics()
        assertEquals("logcat -b crash -c", crash.tString("clearCommand"))
        assertEquals("logcat -b all -c", crash.tString("clearAllCommand"))
        assertTrue(crash.get("clearOnlyCrashBuffer").asBoolean)
        assertTrue(crash.get("doesNotClearMainBuffer").asBoolean)
        assertTrue(crash.get("clearIsNotBarrier").asBoolean)
        assertTrue(crash.get("canStillReadOldEntries").asBoolean)
        val why = arrayText(crash.get("whyStillOldEntries"))
        assertTrue(why.contains("main"))
        assertTrue(why.contains("竞态"))
        assertTrue(why.contains("持久化"))
        assertTrue(crash.tString("sigkill").contains("不是崩溃"))
        assertTrue(arrayText(crash.get("notEvidenceOfNewCrash")).contains("SIGKILL"))
        assertTrue(arrayText(crash.get("notCrashEvidence")).contains("SIGKILL"))

        val verification = crash.getAsJsonObject("verification")
        val required = arrayText(verification.get("requiredFields"))
        assertTrue(required.contains("windowStartEpochMs"))
        assertTrue(required.contains("pid"))
        assertTrue(required.contains("timestamp"))
        val steps = arrayText(verification.get("steps"))
        assertTrue(steps.contains("PID"))
        assertTrue(steps.contains("timestamp"))
        val doNot = arrayText(verification.get("doNot"))
        assertTrue(doNot.contains("SIGKILL"))
        assertTrue(doNot.contains("清缓冲前"))
        assertTrue(crash.tString("recommended").contains("窗口"))
    }
}
