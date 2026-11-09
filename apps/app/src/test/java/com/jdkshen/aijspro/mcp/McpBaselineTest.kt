package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * task-37 / 设备4 §6.4：MCP 基线自检（紧凑 baseline）。
 *
 * 覆盖：字段齐全 + 完整基线 ≤512 字节；同源断言（workspaces.apk ↔ ToolkitWorkspaceInventory、
 * toolchain ↔ McpBuildTools.readiness）；recovery 只有一条且点名组件/命令；预算裁剪保自救字段；
 * version 内容不变则不变；入口挂载点（首个 tools/call / 失败信封 / get_baseline）的源码契约。
 */
class McpBaselineTest {
    @get:Rule val temp = TemporaryFolder()

    private val mcpToolsSource: String by lazy {
        File("src/main/java/com/jdkshen/aijspro/mcp/McpTools.kt").readText()
    }

    private fun workspace(store: File, id: String, temporary: Boolean, bytes: Int) {
        val dir = File(store, id)
        assertTrue(dir.mkdirs())
        File(dir, "base.apk").writeBytes(ByteArray(bytes))
        File(dir, "workspace.json").writeText(ToolkitIo.json(
            "workspaceId" to id,
            "temporary" to temporary,
            "size" to bytes.toLong(),
            "createdAt" to System.currentTimeMillis(),
            "sha256" to ("sha-" + id),
        ).toString(), Charsets.UTF_8)
    }

    private fun inventoryFixture(): JsonObject {
        val store = temp.newFolder("store")
        workspace(store, "ws-temporary", true, 16)
        workspace(store, "ws-saved", false, 32)
        return ToolkitWorkspaceInventory(store).inventory(ToolkitIo.json("limit" to 5))
    }

    /**
     * 与 McpBuildTools.readiness(...) **同形状**的就绪对象（ready/missing/present/missingCount）。
     * 为什么不用真函数：McpBuildTools 依赖 McpAndroidTerminal/ToolkitApkVerifier 的 Android 依赖链，
     * 而 apps/app/build 已被构建清空、独立编译拉不起来；这里只验证**投影同源**（同形状 → 同值），
     * 生产侧的同源由 [entryAttachmentPolicyIsPinnedInTheSingleAssemblyPoint] 的源码断言 + Lead 的 gradle 复核覆盖。
     */
    private fun readiness(ready: Boolean, missing: List<String>, present: List<String>): JsonObject = ToolkitIo.json(
        "ready" to ready, "missing" to missing, "present" to present, "missingCount" to missing.size,
    )

    private val allComponents = listOf("jdk17", "aapt2", "d8", "apksigner", "python3", "aidl")

    private fun fullReadiness(): JsonObject = readiness(true, emptyList(), allComponents)

    @Test
    fun apkWorkspaceProjectionIsTheSameSourceAsInventory() {
        val inventory = inventoryFixture()
        val apk = McpBaseline.apkWorkspaces(inventory)
        assertEquals("baseline.workspaces.apk.quota 必须等于 inventory.quota",
            inventory.get("quota").asInt, apk.get("quota").asInt)
        assertEquals("used 必须等于 inventory.quotaUsedCount",
            inventory.get("quotaUsedCount").asInt, apk.get("used").asInt)
        assertEquals(2, apk.get("used").asInt)
        assertEquals(1, apk.get("temporary").asInt)
        assertTrue(apk.get("ttlHint").asString.contains("过期"))
    }

    @Test
    fun toolchainProjectionIsTheSameSourceAsGetBuildToolsReadiness() {
        val readiness = fullReadiness()
        val projected = McpBaseline.toolchain(readiness)
        assertEquals("baseline.toolchain.ready 必须等于 get_build_tools.ready",
            readiness.get("ready").asBoolean, projected.get("ready").asBoolean)
        assertEquals(readiness.getAsJsonArray("missing").toString(), projected.getAsJsonArray("missing").toString())
        assertEquals(readiness.getAsJsonArray("present").toString(), projected.getAsJsonArray("present").toString())
        // 缺组件时 ready=false 且 missing 如实透传
        val partial = readiness(false, listOf("python3", "aidl"), listOf("jdk17", "aapt2", "d8", "apksigner"))
        val partialProjected = McpBaseline.toolchain(partial)
        assertEquals(false, partialProjected.get("ready").asBoolean)
        assertTrue(partialProjected.getAsJsonArray("missing").map { it.asString }.contains("python3"))
        // 采样后：sampled=true（与 get_build_tools 逐字相等由上面三条断言保证）
        assertEquals(true, projected.get("sampled").asBoolean)
        // task-38（②）：未采样**不能**输出空 missing/present 数组（会被读成「什么都不缺」）
        val unsampled = McpBaseline.toolchain(null)
        assertEquals(false, unsampled.get("sampled").asBoolean)
        assertFalse("未采样不能有 missing", unsampled.has("missing"))
        assertFalse("未采样不能有 present", unsampled.has("present"))
        assertFalse("未采样不能有 ready（null 也误导）", unsampled.has("ready"))
        assertTrue(unsampled.get("note").asString.contains("get_build_tools"))
    }

    @Test
    fun recoveryIsSingleAndNamesTheConcreteObject() {
        val missing = McpBaseline.toolchain(readiness(false, allComponents, emptyList()))
        val apk = ToolkitIo.json("used" to 0, "quota" to 16, "temporary" to 0)
        val recovery = McpBaseline.recoveryFor(missing, apk)
        assertNotNull(recovery)
        assertTrue("必须点名组件：" + recovery, recovery!!.contains("jdk17") || recovery.contains("aapt2"))
        assertTrue("必须给可执行命令：" + recovery, recovery.contains("apk_component_install"))
        val ready = McpBaseline.toolchain(fullReadiness())
        assertEquals(true, ready.get("ready").asBoolean)
        assertNull("没有缺失就不能给 recovery", McpBaseline.recoveryFor(ready, apk))
        val full = McpBaseline.recoveryFor(ready, ToolkitIo.json("used" to 16, "quota" to 16, "temporary" to 3))
        assertNotNull("配额满也要能自救", full)
        assertTrue(full!!.contains("apk_workspace_gc"))
        assertTrue(full.contains("16/16"))
    }

    @Test
    fun assembledBaselineHasDocumentedFieldsAndFitsTheBudget() {
        val inventory = inventoryFixture()
        val baseline = McpBaseline.assemble(
            1, "/storage/emulated/0/脚本", "my-project",
            ToolkitIo.json("script" to 3, "apk" to McpBaseline.apkWorkspaces(inventory)),
            McpBaseline.toolchain(fullReadiness()),
            McpBaseline.jobs("6h / 64 条", "终态立刻 result 或 export"),
        )
        assertEquals(1, baseline.get("version").asInt)
        assertEquals("/storage/emulated/0/脚本", baseline.get("root").asString)
        assertEquals("my-project", baseline.get("currentProject").asString)
        assertEquals(3, baseline.getAsJsonObject("workspaces").get("script").asInt)
        assertEquals(16, baseline.getAsJsonObject("workspaces").getAsJsonObject("apk").get("quota").asInt)
        assertEquals(true, baseline.getAsJsonObject("toolchain").get("ready").asBoolean)
        assertEquals("6h / 64 条", baseline.getAsJsonObject("jobs").get("retention").asString)
        assertTrue(baseline.get("recovery").isJsonNull)
        assertFalse(baseline.has("trimmed"))
        assertTrue("完整基线必须 ≤512 字节，实际 " + McpBaseline.bytesOf(baseline) + "B",
            McpBaseline.bytesOf(baseline) <= McpBaseline.MAX_BYTES)
    }

    @Test
    fun budgetTrimKeepsSelfRescueFieldsAndDropsStatistics() {
        val fat = McpBaseline.assemble(
            9,
            "/storage/emulated/0/" + "很长的根目录".repeat(8),
            "p",
            ToolkitIo.json("script" to 2147483647, "apk" to ToolkitIo.json(
                "used" to 16, "quota" to 16, "temporary" to 16, "ttlHint" to "temporary 会过期，过期后重新 open")),
            McpBaseline.toolchain(readiness(false, allComponents, emptyList())),
            McpBaseline.jobs("6h / 64 条", "终态立刻 result 或 export"),
        )
        val fatBytes = McpBaseline.bytesOf(fat)
        assertTrue("构造的基线必须超预算，实际 " + fatBytes + "B", fatBytes > McpBaseline.MAX_BYTES)
        val trimmed = McpBaseline.fitBudget(fat, maxBytes = 200)
        assertTrue("裁剪不能改调用方对象（缓存那份要完整）", McpBaseline.bytesOf(fat) == fatBytes)
        assertTrue("裁剪后必须保留 version/missing/recovery，实际 keys=" + trimmed.keySet(),
            trimmed.has("version") && trimmed.has("missing") && trimmed.has("recovery"))
        assertFalse("先丢统计：jobs 必须被丢", trimmed.has("jobs"))
        assertFalse(trimmed.has("backends"))
        assertFalse(trimmed.has("outputRule"))
        assertTrue(trimmed.has("trimmed"))
        assertNotNull(trimmed.getAsJsonArray("missing"))
        assertTrue(trimmed.getAsJsonArray("missing").map { it.asString }.contains("python3"))
        assertTrue("裁剪后必须显著变小（fat=" + fatBytes + "B, trimmed=" + McpBaseline.bytesOf(trimmed) + "B）",
            McpBaseline.bytesOf(trimmed) < fatBytes)
    }

    /**
     * task-38（①④）：失败信封**始终**带失败专用裁剪版 baseline（版本 + recovery，体积远小于完整版）。
     */
    @Test
    fun failureViewIsSmallAndAlwaysSelfRescuing() {
        val missing = readiness(false, allComponents, emptyList())
        val full = McpBaseline.assemble(
            7, "/storage/emulated/0/脚本", "p",
            ToolkitIo.json("script" to 4, "apk" to ToolkitIo.json("used" to 3, "quota" to 16, "temporary" to 1, "ttlHint" to "temporary 会过期")),
            McpBaseline.toolchain(missing),
            McpBaseline.jobs("6h / 64 条", "终态立刻 result 或 export"),
        )
        val view = McpBaseline.failureView(full)
        assertEquals(7, view.get("version").asInt)
        assertTrue("失败时必须能给自救建议", view.get("recovery").asString.contains("apk_component_install"))
        assertTrue(view.getAsJsonArray("missing").map { it.asString }.contains("python3"))
        assertEquals("failure", view.get("trimmed").asString)
        assertTrue(view.get("trimNote").asString.contains("失败专用裁剪"))
        assertFalse("失败版不带宽表统计", view.has("workspaces"))
        assertFalse(view.has("jobs"))
        assertFalse(view.has("toolchain"))
        assertTrue("失败裁剪版必须 ≤ 完整版（fat=" + McpBaseline.bytesOf(full) + "B, view=" + McpBaseline.bytesOf(view) + "B）",
            McpBaseline.bytesOf(view) <= McpBaseline.bytesOf(full))
        // 无缺失时只保 version（不带 recovery），同样不带宽表统计
        val clean = McpBaseline.failureView(McpBaseline.assemble(3, "/r", null,
            ToolkitIo.json("script" to 0, "apk" to JsonObject()), McpBaseline.toolchain(fullReadiness()),
            McpBaseline.jobs("6h / 64 条", "x")))
        assertEquals(3, clean.get("version").asInt)
        assertFalse(clean.has("recovery"))
        assertFalse(clean.has("missing"))
        // 连续两次失败必须都带（不受「首个 tools/call」latch 影响）
        assertEquals(view.toString(), McpBaseline.failureView(full).toString())
    }

    /**
     * task-39（①②）：失败结果的 `content[0].text` 里必须有**同一份**裁剪版 baseline（与 structuredContent 逐字段相同），
     * 且连续两次失败都一样（不受「首个 tools/call」latch 影响）。
     */
    @Test
    fun failureTextMirrorCarriesTheSameBaseline() {
        val full = McpBaseline.assemble(
            11, "/storage/emulated/0/脚本", "p",
            ToolkitIo.json("script" to 4, "apk" to ToolkitIo.json("used" to 3, "quota" to 16, "temporary" to 1, "ttlHint" to "temporary 会过期")),
            McpBaseline.toolchain(readiness(false, allComponents, emptyList())),
            McpBaseline.jobs("6h / 64 条", "终态立刻 result 或 export"),
        )
        val view = McpBaseline.failureView(full)
        // 桥接失败：text 是工具进程返回的载荷（JSON 对象）→ 就地补 baseline
        val payload = "{\"ok\":false,\"data\":null,\"error\":{\"code\":\"INVALID_ARGUMENT\",\"message\":\"缺少参数 workspaceId\"},\"nextActions\":[]}"
        val withBaseline = McpBaseline.failureText(payload, view, "INVALID_ARGUMENT", null)
        val parsed = com.google.gson.JsonParser().parse(withBaseline).asJsonObject
        assertTrue("text 里必须有 baseline", parsed.has("baseline"))
        assertEquals("text.baseline 必须与 structuredContent.baseline 逐字段相同", view.toString(), parsed.getAsJsonObject("baseline").toString())
        assertEquals("原始字段不能丢", "INVALID_ARGUMENT", parsed.getAsJsonObject("error").get("code").asString)
        // 纯文本失败（少数 catch 路径）→ 包成标准信封，原文进 error.message，同样带 baseline
        val wrapped = McpBaseline.failureText("工作区状态冲突", view, "WORKSPACE_STATE_CONFLICT", null)
        val wrappedJson = com.google.gson.JsonParser().parse(wrapped).asJsonObject
        assertEquals(false, wrappedJson.get("ok").asBoolean)
        assertEquals("工作区状态冲突", wrappedJson.getAsJsonObject("error").get("message").asString)
        assertEquals(view.toString(), wrappedJson.getAsJsonObject("baseline").toString())
        // 连续两次（幂等 + 同值）
        assertEquals("重复注入必须幂等且同值", withBaseline, McpBaseline.failureText(withBaseline, view, "INVALID_ARGUMENT", null))
        assertEquals(withBaseline, McpBaseline.failureText(payload, McpBaseline.failureView(full), "INVALID_ARGUMENT", null))
    }

    /**
     * task-41（①②③）：未采样时失败版也必须有一条**零成本**建议；采样后仍是点名组件那条；描述口径要准确。
     */
    @Test
    fun unsampledFailureStillGivesAZeroCostRecovery() {
        val apk = ToolkitIo.json("used" to 0, "quota" to 16, "temporary" to 0)
        // ① 未采样：不能是 null；必须点出「先采样」且不触发采样（形状不变）
        val unsampled = McpBaseline.toolchain(null)
        assertEquals(false, unsampled.get("sampled").asBoolean)
        val recovery = McpBaseline.recoveryFor(unsampled, apk)
        assertNotNull("未采样时失败也必须能给自救建议", recovery)
        assertTrue("要说明是「未采样」：" + recovery, recovery!!.contains("未采样"))
        assertTrue("要给出可执行入口：" + recovery, recovery.contains("get_build_tools") || recovery.contains("get_baseline"))
        assertFalse("未采样形状不能被改写（零成本、不触发采样）", unsampled.has("missing") || unsampled.has("present") || unsampled.has("ready"))
        // 失败裁剪版也要能看到它
        val unsampledView = McpBaseline.failureView(McpBaseline.assemble(5, "/r", null,
            ToolkitIo.json("script" to 0, "apk" to apk), unsampled, McpBaseline.jobs("6h", "x")))
        assertTrue("失败版必须带 recovery", unsampledView.has("recovery") && unsampledView.get("recovery").asString.contains("未采样"))
        // ② 采样后（有缺失）仍是点名组件那条（K40 已实测，不能回归）
        val sampled = McpBaseline.toolchain(readiness(false, listOf("jdk17"), listOf("aapt2", "d8")))
        val sampledRecovery = McpBaseline.recoveryFor(sampled, apk)
        assertTrue("采样后要点名组件：" + sampledRecovery, sampledRecovery!!.contains("jdk17") && sampledRecovery.contains("apk_component_install"))
        // ③ 描述口径：成功路径 text 不保证包含（get_status 的扁平 text 会含）
        val toolsSource = File("src/main/java/com/jdkshen/aijspro/mcp/McpTools.kt").readText()
        assertTrue("描述要写「不保证包含」", toolsSource.contains("不保证包含"))
        assertTrue("描述要点出 get_status 的扁平 text 会含", toolsSource.contains("get_status` 的扁平 text 会含"))
        assertTrue("描述要写失败路径三处同值", toolsSource.contains("失败路径三处同值"))
        // 未采样分支必须零成本：recoveryFor 里不出现 inspect / 桥调用
        val recoveryBody = File("src/main/java/com/jdkshen/aijspro/mcp/McpBaseline.kt").readText()
            .substringAfter("fun recoveryFor(toolchain: JsonObject, apk: JsonObject): String? {")
            .substringBefore("/** 组装")
        assertFalse("不触发采样（不能出现 McpBuildTools.inspect 调用）", recoveryBody.contains("McpBuildTools.inspect("))
        assertFalse("不触发桥调用", recoveryBody.contains("toolkit_status"))
    }

    /** task-39（③）：注入必须在**序列化单点**，成功路径按描述**有意不带**文本镜像。 */
    @Test
    fun failureTextIsInjectedAtTheSingleSerializationPointOnly() {
        assertTrue("单点：rpcResult 兜底", mcpToolsSource.contains("add(\"result\", withFailureBaseline(result))"))
        assertEquals("withFailureBaseline 只能有一个调用点", 1,
            Regex("withFailureBaseline\\(result\\)").findAll(mcpToolsSource).count())
        assertTrue("文本重写在 withFailureBaseline 里",
            mcpToolsSource.contains("obj.addProperty(\"text\", McpBaseline.failureText(text, view, errorCode, null))"))
        val failureBody = mcpToolsSource.substringAfter("private fun withFailureBaseline(result: JsonObject): JsonObject {")
            .substringBefore("private fun withEntryBaseline")
        assertFalse("失败路径不能被 latch 影响", failureBody.contains("entryBaselineDelivered"))
        assertTrue("失败路径确实重写 text", failureBody.contains("McpBaseline.failureText("))
        val entryBody = mcpToolsSource.substringAfter("private fun withEntryBaseline(result: JsonObject): JsonObject {")
            .substringBefore("private fun toolJson")
        assertFalse("成功路径有意不重写文本镜像（省一次大载荷重序列化）", entryBody.contains("failureText"))
        assertTrue("成功路径 text 的口径必须在描述里写明（不保证包含）", mcpToolsSource.contains("不保证包含"))
    }

    @Test
    fun versionIsStableWhileContentIsUnchanged() {
        val version = McpBaselineVersion()
        val content = McpBaseline.assemble(0, "/root", "p",
            ToolkitIo.json("script" to 1, "apk" to JsonObject()),
            McpBaseline.toolchain(null), McpBaseline.jobs("6h / 64 条", "x"))
        val first = version.versionFor(content)
        assertEquals("内容不变 → version 不变（客户端可去重）", first, version.versionFor(content))
        val changed = content.deepCopy().apply { addProperty("currentProject", "other") }
        assertEquals(first + 1, version.versionFor(changed))
    }

    @Test
    fun entryAttachmentPolicyIsPinnedInTheSingleAssemblyPoint() {
        assertTrue(mcpToolsSource.contains("private fun baselineSnapshot("))
        assertTrue("首个 tools/call 成功挂载", mcpToolsSource.contains("withEntryBaseline(McpToolResultShapes.mirrorFlatResult("))
        assertTrue("入口只挂一次", mcpToolsSource.contains("entryBaselineDelivered.compareAndSet(false, true)"))
        assertTrue("get_status 走同一份", mcpToolsSource.contains("add(\"baseline\", baselineSnapshot(jobsSummary))"))
        assertTrue("get_baseline 走同一份且可强刷",
            mcpToolsSource.contains("\"get_baseline\" -> toolJson(baselineSnapshot(force = args.booleanOr(\"refresh\", false)))"))
        // task-38（①）：失败信封走失败专用裁剪版，且**兜底在 rpcResult**（任何 isError 都补）
        assertTrue("toolFailure 把裁剪版放进信封", mcpToolsSource.contains("envelope.add(\"baseline\", failureBaseline.deepCopy())"))
        assertTrue("toolFailure 顶层也带", mcpToolsSource.contains("add(\"baseline\", failureBaseline)"))
        assertTrue("toolResult(error) 带裁剪版", mcpToolsSource.contains("if (error && !has(\"baseline\")) add(\"baseline\", failureBaselineView())"))
        assertTrue("桥接失败带裁剪版", mcpToolsSource.contains("add(\"baseline\", failureBaselineView())"))
        assertTrue("rpcResult 兜底所有 isError", mcpToolsSource.contains("add(\"result\", withFailureBaseline(result))"))
        assertTrue("失败版不受 latch 影响", mcpToolsSource.contains("private fun failureBaselineView(): JsonObject = runCatching {"))
        assertTrue("失败路径不触发采样（保持轻量）", mcpToolsSource.contains("McpBaseline.failureView(baselineSnapshot(sampleIfMissing = false))"))
        assertTrue("未采样时触发一次同源采样（30s 内不重试）", mcpToolsSource.contains("if (now - toolchainSampleAt < 30_000L) return McpBuildTools.cachedReadiness()"))
        assertTrue(mcpToolsSource.contains("McpBuildTools.inspect(terminalBackend, root); McpBuildTools.cachedReadiness()"))
        // 同源生产侧：baseline 的工具链只取 get_build_tools 的内存缓存（不重新探测 PATH）
        assertTrue("工具链只取 cachedReadiness（采样走同一条 inspect）",
            mcpToolsSource.contains("val readiness = McpBuildTools.cachedReadiness() ?: if (sampleIfMissing) sampleToolchain() else null") &&
                mcpToolsSource.contains("McpBaseline.toolchain(readiness)"))
        val buildToolsSource = File("src/main/java/com/jdkshen/aijspro/mcp/McpBuildTools.kt").readText()
        assertTrue("readiness 是 get_build_tools 的唯一判定实现", buildToolsSource.contains("internal fun readiness(commandResults: Map<String, String>"))
        assertTrue("cachedReadiness 暴露的是同一份判定", buildToolsSource.contains("fun cachedReadiness(): JsonObject? = lastReadiness?.deepCopy()"))
    }
}
