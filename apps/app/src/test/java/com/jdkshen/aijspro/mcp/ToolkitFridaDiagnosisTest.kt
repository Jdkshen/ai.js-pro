package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ToolkitFridaDiagnosis 纯 JVM 单测：SELinux 跨 UID 判定、动作可执行性、脚本模式/ listen 边界。 */
class ToolkitFridaDiagnosisTest {
    private fun strings(result: JsonObject, key: String): List<String> {
        val array = result.getAsJsonArray(key) ?: return emptyList()
        return (0 until array.size()).map { array.get(it).asString }
    }

    private fun jsonText(result: JsonObject): String = result.toString()

    private fun diagnose(
        errorText: String,
        portListening: Boolean,
        synSentObserved: Boolean,
        sameUid: Boolean,
    ): JsonObject = ToolkitFridaDiagnosis.classifyConnectFailure(errorText, portListening, synSentObserved, sameUid)

    @Test
    fun listenSynSentCrossUidIsHighConfidenceSelinuxSuspect() {
        val result = diagnose(
            "connect 127.0.0.1:27042: Operation was cancelled (timedOut=true)",
            portListening = true,
            synSentObserved = true,
            sameUid = false,
        )
        assertEquals("selinux_loopback_suspect", result.get("classification").asString)
        assertTrue(result.get("selinuxLoopbackSuspect").asBoolean)
        assertEquals("high", result.get("confidence").asString)
        assertTrue(result.get("timeoutObserved").asBoolean)
        assertTrue(result.get("portListening").asBoolean)
        assertTrue(result.get("synSentObserved").asBoolean)
        assertFalse(result.get("sameUid").asBoolean)
        assertEquals("not_the_cause", result.get("componentVerdict").asString)
        assertTrue(result.getAsJsonObject("evidence").get("synSentDropSignature").asBoolean)
        assertEquals("0A", result.getAsJsonObject("evidence").get("listenStateHint").asString)
        assertEquals("02", result.getAsJsonObject("evidence").get("synSentStateHint").asString)

        // nextActions 必须可执行：script 模式 / 同 UID / frida_eval 一次性路径 / 确认 LISTEN 命令。
        val actions = jsonText(result)
        assertTrue(actions.contains("switch_to_gadget_script_mode"))
        assertTrue(actions.contains("run_client_in_same_uid"))
        assertTrue(actions.contains("frida_eval"))
        assertTrue(actions.contains("/proc/net/tcp"))
        assertTrue(actions.contains("grep -i ':69A2' /proc/net/tcp"))
        assertTrue(strings(result, "evidenceCommands").any { it.contains("/proc/net/tcp") })
    }

    @Test
    fun listenCrossUidTimeoutWithoutSynIsStillSuspectButMedium() {
        val result = diagnose(
            "connect failed: ETIMEDOUT",
            portListening = true,
            synSentObserved = false,
            sameUid = false,
        )
        assertEquals("selinux_loopback_suspect", result.get("classification").asString)
        assertTrue(result.get("selinuxLoopbackSuspect").asBoolean)
        assertEquals("medium", result.get("confidence").asString)
        assertFalse(result.getAsJsonObject("evidence").get("synSentDropSignature").asBoolean)
    }

    @Test
    fun listenSameUidTimeoutIsNotSelinuxAndHintsComponentOrPermission() {
        val result = diagnose(
            "connect 127.0.0.1:27042: Operation was cancelled (timedOut=true)",
            portListening = true,
            synSentObserved = false,
            sameUid = true,
        )
        assertFalse(result.get("selinuxLoopbackSuspect").asBoolean)
        assertEquals("same_uid_connect_failure", result.get("classification").asString)
        assertEquals("unknown", result.get("componentVerdict").asString)
        val text = jsonText(result)
        assertTrue(text.contains("组件"))
        assertTrue(text.contains("权限"))
        assertTrue(text.contains("frida_status"))
        // 不能把超时直接说成组件没装
        assertTrue(result.get("wordingGuidance").asString.contains("不能直接当成"))
    }

    @Test
    fun portNotListeningHintsComponentAndInjectionInsteadOfSelinux() {
        val result = diagnose(
            "connect 127.0.0.1:27042: Operation was cancelled (timedOut=true)",
            portListening = false,
            synSentObserved = false,
            sameUid = false,
        )
        assertFalse(result.get("selinuxLoopbackSuspect").asBoolean)
        assertEquals("port_not_listening", result.get("classification").asString)
        val text = jsonText(result)
        assertTrue(text.contains("注入"))
        assertTrue(text.contains("组件"))
        assertTrue(text.contains("启动"))
        assertTrue(strings(result, "evidenceCommands").any { it.contains("/proc/net/tcp") })
    }

    @Test
    fun notFoundTextIsNotTreatedAsSelinux() {
        val crossUid = diagnose("aijs-frida: not found", portListening = true, synSentObserved = false, sameUid = false)
        assertFalse(crossUid.get("selinuxLoopbackSuspect").asBoolean)
        assertEquals("component_missing_or_command_error", crossUid.get("classification").asString)
        assertEquals("possible_missing", crossUid.get("componentVerdict").asString)
        assertTrue(jsonText(crossUid).contains("apk_component_status"))

        val noListen = diagnose("No such file or directory: aijs-frida", portListening = false, synSentObserved = false, sameUid = false)
        assertFalse(noListen.get("selinuxLoopbackSuspect").asBoolean)
        assertEquals("component_missing_or_command_error", noListen.get("classification").asString)
    }

    @Test
    fun connectionRefusedIsNotSilentDrop() {
        val result = diagnose("connect failed: Connection refused", portListening = true, synSentObserved = false, sameUid = false)
        assertFalse(result.get("selinuxLoopbackSuspect").asBoolean)
        assertEquals("connection_refused", result.get("classification").asString)
        assertTrue(result.get("rstOrRefusedObserved").asBoolean)
        assertTrue(result.get("reason").asString.contains("拒绝"))
    }

    @Test
    fun insufficientEvidenceReturnsUnknownAndDoesNotGuessSelinux() {
        val result = diagnose("", portListening = false, synSentObserved = false, sameUid = true)
        assertEquals("unknown", result.get("classification").asString)
        assertFalse(result.get("selinuxLoopbackSuspect").asBoolean)
        assertEquals("unknown", result.get("confidence").asString)
        assertTrue(result.get("reason").asString.contains("证据不足"))
        val text = jsonText(result)
        assertTrue(text.contains("collect_connect_evidence"))
        assertTrue(text.contains("/proc/net/tcp"))
    }

    @Test
    fun noErrorTextWithListeningPortIsStillUnknown() {
        val result = diagnose("", portListening = true, synSentObserved = false, sameUid = false)
        assertEquals("unknown", result.get("classification").asString)
        assertFalse(result.get("selinuxLoopbackSuspect").asBoolean)
        assertTrue(result.get("reason").asString.contains("证据不足"))
    }

    @Test
    fun sameUidAndSynSentIsEvidenceConflictNotSelinux() {
        val result = diagnose("connect timed out", portListening = true, synSentObserved = true, sameUid = true)
        assertFalse(result.get("selinuxLoopbackSuspect").asBoolean)
        assertTrue(result.get("evidenceConflict").asBoolean)
        assertEquals("same_uid_connect_failure", result.get("classification").asString)
        assertTrue(result.get("reason").asString.contains("冲突"))
    }

    @Test
    fun gadgetScriptModeGuidanceRecommendsNetworkFreePath() {
        val guidance = ToolkitFridaDiagnosis.gadgetScriptModeGuidance()
        assertEquals("script", guidance.get("mode").asString)
        assertTrue(guidance.get("recommended").asBoolean)
        val interaction = guidance.getAsJsonObject("configSnippet").getAsJsonObject("interaction")
        assertEquals("script", interaction.get("type").asString)
        assertTrue(interaction.get("path").asString.contains("绝对路径"))
        assertTrue(guidance.get("why").asString.contains("SELinux"))
        assertTrue(guidance.get("why").asString.contains("loopback"))
        assertTrue(guidance.get("currentImplementation").asString.contains("固定"))
        assertTrue(guidance.get("currentImplementation").asString.contains("listen"))
        assertTrue(guidance.get("outputContract").asString.contains("文件"))
        assertTrue(guidance.getAsJsonArray("scriptPathRequirements").size() >= 2)
        assertTrue(guidance.getAsJsonArray("limits").size() >= 2)
        assertTrue(jsonText(guidance).contains("apk_inject_gadget"))
        assertTrue(jsonText(guidance).contains("grep -i ':69A2' /proc/net/tcp"))
    }

    @Test
    fun listenModeLimitsDocumentsBoundaryAndProcEvidence() {
        val limits = ToolkitFridaDiagnosis.listenModeLimits()
        assertEquals("listen", limits.get("mode").asString)
        val interaction = limits.getAsJsonObject("defaultConfig").getAsJsonObject("interaction")
        assertEquals("listen", interaction.get("type").asString)
        assertTrue(interaction.get("port").asInt == ToolkitFridaDiagnosis.DEFAULT_GADGET_PORT)
        val limitsText = jsonText(limits)
        assertTrue(limitsText.contains("SELinux"))
        assertTrue(limitsText.contains("跨 UID"))
        assertEquals("0A", limits.getAsJsonObject("tcpStates").get("LISTEN").asString)
        assertEquals("02", limits.getAsJsonObject("tcpStates").get("SYN_SENT").asString)
        val commands = jsonText(limits)
        assertTrue(commands.contains("/proc/net/tcp"))
        assertTrue(commands.contains("SYN_SENT"))
        assertTrue(limits.getAsJsonArray("decisionTable").size() >= 4)
        assertTrue(limits.getAsJsonArray("troubleshootingSteps").size() >= 5)
        assertTrue(limits.get("notComponentMissingNote").asString.contains("组件没装"))
        assertTrue(jsonText(limits).contains("switch_to_gadget_script_mode"))
        assertTrue(jsonText(limits).contains("run_client_in_same_uid"))
        assertTrue(jsonText(limits).contains("frida_eval"))
    }
}
