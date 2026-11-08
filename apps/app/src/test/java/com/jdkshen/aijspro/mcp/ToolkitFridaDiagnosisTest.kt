package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /**
     * 用假连接器造一个**真实形状**的 probe 对象（不碰真 socket）：
     * 字段来自 ToolkitFridaPortProbe 生产实现，避免测试手写字段与之漂移。
     */
    private fun probeOf(result: String): JsonObject {
        val connector = ToolkitFridaPortProbe.Connector { _, _, _ ->
            when (result) {
                "listening_reachable" -> Unit
                "refused" -> throw java.net.ConnectException("Connection refused")
                "timeout" -> throw java.net.SocketTimeoutException("connect timed out")
                else -> throw java.io.IOException("boom")
            }
        }
        return ToolkitFridaPortProbe.probe("127.0.0.1", 27042, ToolkitFridaPortProbe.DEFAULT_TIMEOUT_MS, connector)
    }

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

    // ---- 主动探测（设备4 ★①：端口状态不再依赖 /proc/net/tcp）-------------------

    @Test
    fun probeTimeoutWithoutProcEvidenceIsSelinuxSuspectNotComponentMissing() {
        // 场景：应用域读不到 /proc/net/tcp（portListening=false）+ 主动探测超时（跨 UID）。
        val result = ToolkitFridaDiagnosis.classifyConnectFailure(
            "Unable to connect to remote frida-server: connect timed out", false, false, false, probeOf("timeout"))
        assertEquals("selinux_loopback_suspect", result.get("classification").asString)
        assertTrue(result.get("selinuxLoopbackSuspect").asBoolean)
        assertEquals("medium", result.get("confidence").asString)
        assertEquals("active_probe", result.get("evidenceSource").asString)
        assertEquals("not_the_cause", result.get("componentVerdict").asString)
        assertTrue(result.get("probeTimeoutObserved").asBoolean)
        assertFalse(result.get("probeReachable").asBoolean)
        val text = jsonText(result)
        assertTrue(text.contains("probe=timeout"))
        assertTrue(text.contains("SYN 被静默丢弃"))
        // 「/proc 读不到是预期」必须进建议与恢复步骤，且绝不能报组件缺失
        assertTrue(result.get("recommendations").toString().contains("预期"))
        assertTrue(result.get("recovery").toString().contains("预期"))
        assertFalse(result.getAsJsonObject("recovery").get("componentMissing").asBoolean)
        assertTrue(text.contains("switch_to_gadget_script_mode"))
        assertTrue(text.contains("/proc/net/tcp"))

        // 更极端：连客户端错误文本都没有（/proc 读不到、客户端还没回话）时，
        // 单靠 probe=timeout 也必须判 SELinux 嫌疑，而不是「组件缺失/证据不足」。
        val bare = ToolkitFridaDiagnosis.classifyConnectFailure("", false, false, false, probeOf("timeout"))
        assertEquals("selinux_loopback_suspect", bare.get("classification").asString)
        assertTrue(bare.get("selinuxLoopbackSuspect").asBoolean)
        assertFalse(bare.getAsJsonObject("recovery").get("componentMissing").asBoolean)
    }

    @Test
    fun probeReachableRefutesSelinuxDrop() {
        // probe 连上了端口 → 同一条 loopback 路径可用，跨 UID 静默 DROP 的假设被否掉。
        val result = ToolkitFridaDiagnosis.classifyConnectFailure(
            "connect timed out", true, false, false, probeOf("listening_reachable"))
        assertEquals("port_reachable", result.get("classification").asString)
        assertFalse(result.get("selinuxLoopbackSuspect").asBoolean)
        assertEquals("high", result.get("confidence").asString)
        assertTrue(result.get("probeReachable").asBoolean)
        assertTrue(result.get("effectivePortListening").asBoolean)
        assertEquals("not_the_cause", result.get("componentVerdict").asString)
        assertTrue(result.get("reason").asString.contains("probe"))
    }

    @Test
    fun probeRefusedIsConnectionRefusedEvenWithoutErrorText() {
        val result = ToolkitFridaDiagnosis.classifyConnectFailure("", false, false, false, probeOf("refused"))
        assertEquals("connection_refused", result.get("classification").asString)
        assertTrue(result.get("rstOrRefusedObserved").asBoolean)
        assertTrue(result.get("reason").asString.contains("没有监听者"))
        assertFalse(result.getAsJsonObject("recovery").get("componentMissing").asBoolean)
    }

    @Test
    fun probeErrorIsInconclusiveAndNeverComponentMissing() {
        val result = ToolkitFridaDiagnosis.classifyConnectFailure("", false, false, false, probeOf("error"))
        assertEquals("unknown", result.get("classification").asString)
        assertTrue(result.get("probeInconclusive").asBoolean)
        assertTrue(result.get("reason").asString.contains("主动探测本身失败"))
        assertFalse(result.getAsJsonObject("recovery").get("componentMissing").asBoolean)
    }

    @Test
    fun probeTimeoutWithSameUidStaysSameUidPath() {
        val result = ToolkitFridaDiagnosis.classifyConnectFailure("", false, false, true, probeOf("timeout"))
        assertEquals("same_uid_connect_failure", result.get("classification").asString)
        assertFalse(result.get("selinuxLoopbackSuspect").asBoolean)
    }

    @Test
    fun missingProcEvidenceAloneNeverBecomesComponentMissing() {
        // /proc 读不到（tcpText 为空）在解析器里表现为 portListening=false；配合 probe=refused
        // 只能是 connection_refused，不能升级成 component_missing（设备4 Bug3 的核心诉求）。
        val evidence = ToolkitProcNetTcp.forDiagnosis("", null, 27042)
        assertFalse(evidence.get("portListening").asBoolean)
        val result = ToolkitFridaDiagnosis.classifyConnectFailure(
            "", evidence.get("portListening").asBoolean, evidence.get("synSentObserved").asBoolean, false,
            probeOf("refused"))
        assertEquals("connection_refused", result.get("classification").asString)
        assertNotEquals("component_missing_or_command_error", result.get("classification").asString)
        assertFalse(result.getAsJsonObject("recovery").get("componentMissing").asBoolean)
    }
}
