package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 设备4 反馈第六章 §6.4「MCP 基线自检」：把「运行时就绪状态」压成一条**紧凑** baseline（≤512 字节），
 * 挂在**首个** tools/call（或 get_status）与**所有失败信封**上，并提供 get_baseline 显式刷新。
 *
 * 同源强约束（本对象**不自己统计任何东西**）：
 *  - APK 工作区：调用方传入 ToolkitWorkspaceInventory.inventory(...) 的**同一份**输出，[apkWorkspaces] 只做投影；
 *  - 工具链：调用方传入 McpBuildTools.cachedReadiness()（get_build_tools 的同一份判定，不重新探测 PATH）；
 *  - jobs retention：调用方传入 get_status/toolkit_status 的同一份 retention 摘要。
 * 所以 baseline 的数字与对应工具**必然一致**（单测断言 baseline.toolchain.ready == readiness.ready、
 * baseline.workspaces.apk.quota == inventory.quota）。
 */
internal object McpBaseline {
    /** 紧凑上限：客户端每次入口都能白拿，不值得超过这个数。 */
    const val MAX_BYTES = 512

    /** 预算裁剪的**阶段**顺序（先丢统计、保自救）：每步只做一件事，可单测断言剩余字段集合。 */
    val SHRINK_STAGES = listOf(
        "drop_jobs", "shrink_workspaces_to_apk_used_quota",
        "shrink_toolchain_to_ready_missing", "drop_currentProject", "drop_workspaces", "drop_root",
    )

    /** APK 工作区投影（字段口径与 apk_workspace_inventory 一致，不重算）。 */
    fun apkWorkspaces(inventory: JsonObject): JsonObject {
        val byState = inventory.getAsJsonObject("byState")
        return ToolkitIo.json(
            "used" to inventory.tIntOrNull("quotaUsedCount"),
            "quota" to inventory.tIntOrNull("quota"),
            "temporary" to (byState?.tIntOrNull("temporary") ?: 0),
            "ttlHint" to "temporary 会过期",
        )
    }

    /** 工作区投影不可用时的兜底（明确说明，不编数字）。 */
    fun apkWorkspacesUnavailable(note: String): JsonObject = ToolkitIo.json(
        "used" to null, "quota" to null, "temporary" to null, "note" to note,
    )

    /**
     * 工具链投影：只搬 get_build_tools 的 ready/missing/present。
     *
     * task-38（②）：**未采样时不输出空数组**（旧形状 `missing:[]/present:[]` 会被读成「什么都不缺」），
     * 改为 `{sampled:false, note}`；采样后 `sampled:true` + ready/missing/present（与 get_build_tools 逐字相等）。
     */
    fun toolchain(readiness: JsonObject?): JsonObject {
        if (readiness == null) {
            return ToolkitIo.json(
                "sampled" to false,
                "note" to "未采样：先 get_build_tools（或 get_baseline{refresh:true}）拿 ready/missing/present",
            )
        }
        return ToolkitIo.json(
            "sampled" to true,
            "ready" to readiness.get("ready"),
            "missing" to (readiness.getAsJsonArray("missing") ?: JsonArray()),
            "present" to (readiness.getAsJsonArray("present") ?: JsonArray()),
        )
    }

    /**
     * task-39：把**同一份** [view] 注入失败结果的**文本镜像**（`content[]..text`）。
     *
     * - text 是 JSON 对象（信封或桥接载荷）→ 就地加 `baseline` 后重新序列化（键顺序保持，追加在尾部）；
     * - text 是纯文本（少数 catch 路径）→ 包成标准失败信封，原文留在 error.message（P1-A 口径），同样带上 baseline。
     * 由 `McpTools.withFailureBaseline`（rpcResult 单点）调用，不在各调用点补。
     */
    fun failureText(text: String, view: JsonObject, errorCode: String?, message: String?): String {
        val parsed = runCatching { com.google.gson.JsonParser().parse(text) }.getOrNull()
        if (parsed != null && parsed.isJsonObject) {
            val obj = parsed.asJsonObject
            if (!obj.has("baseline")) obj.add("baseline", view.deepCopy())
            return obj.toString()
        }
        return ToolkitIo.json(
            "ok" to false, "data" to null,
            "error" to ToolkitIo.json("code" to (errorCode ?: "TOOL_FAILED"),
                "message" to (message ?: text), "recoverable" to true, "retrySameArguments" to false),
            "nextActions" to emptyList<Any>(),
            "baseline" to view.deepCopy(),
        ).toString()
    }

    /**
     * task-38（①）：**失败专用裁剪版** —— 只保 `version` + `recovery`（有缺失时额外带 `missing`），
     * 不带宽表统计（workspaces/jobs/toolchain 明细），并回显 trimmed/trimNote 说明这是失败专用裁剪。
     * 失败信封必须始终能自救，所以它不受「首个 tools/call」latch 影响。
     */
    fun failureView(full: JsonObject): JsonObject {
        val out = JsonObject()
        out.addProperty("version", full.tIntOrNull("version") ?: -1)
        val recovery = full.get("recovery")
        if (recovery != null && !recovery.isJsonNull) out.add("recovery", recovery.deepCopy())
        val missing = full.getAsJsonArray("missing")
            ?: full.getAsJsonObject("toolchain")?.getAsJsonArray("missing")
        if (missing != null && missing.size() > 0) out.add("missing", missing.deepCopy())
        out.addProperty("trimmed", "failure")
        out.addProperty("trimNote", "失败专用裁剪：只带 version" + (if (out.has("recovery")) "/recovery" else "") +
            (if (out.has("missing")) "/missing" else "") + "；完整基线用 get_baseline")
        return out
    }

    fun jobs(retention: String, advice: String): JsonObject = ToolkitIo.json("retention" to retention, "advice" to advice)

    /**
     * 有缺失时给**一条**最短可执行建议（点名组件或工作区命令），没有缺失返回 null。
     * 文案口径沿用既有 recovery/nextActions（apk_component_install、apk_workspace_gc）。
     */
    fun recoveryFor(toolchain: JsonObject, apk: JsonObject): String? {
        val missing = toolchain.getAsJsonArray("missing")?.map { it.asString }.orEmpty().filter { it.isNotBlank() }
        if (missing.isNotEmpty()) {
            return missing.first() + " 缺失：" + "apk_component_install{component:\"" + missing.first() + "\"}"
        }
        if (toolchain.get("ready")?.isJsonPrimitive == true && !toolchain.get("ready").asBoolean) {
            return "工具链未就绪：先 get_build_tools 采样 ready/missing/present"
        }
        val used = apk.tIntOrNull("used")
        val quota = apk.tIntOrNull("quota")
        if (used != null && quota != null && quota > 0 && used >= quota) {
            return "APK 工作区配额满(" + used + "/" + quota + ")：" + ToolkitWorkspaceInventory.GC_RELAXED_PREVIEW
        }
        return null
    }

    /** 组装（字段顺序固定，便于客户端 diff；version 由调用方给，内容不变则不变）。 */
    fun assemble(
        version: Int,
        root: String,
        currentProject: String?,
        workspaces: JsonObject,
        toolchain: JsonObject,
        jobs: JsonObject,
        outputRule: String? = null,
    ): JsonObject = ToolkitIo.json(
        // §6.4 的字段；outputRule 是设备4 样例里就有的短文案（超预算时由 fitBudget 第一个丢）。
        "version" to version,
        "root" to root,
        "currentProject" to currentProject,
        "outputRule" to outputRule,
        "workspaces" to workspaces,
        "toolchain" to toolchain,
        "jobs" to jobs,
        "recovery" to recoveryFor(toolchain, workspaces.getAsJsonObject("apk") ?: JsonObject()),
    )

    fun bytesOf(json: JsonObject): Int = json.toString().toByteArray(Charsets.UTF_8).size

    /**
     * 预算裁剪：超过 [maxBytes] 时按 [SHRINK_STAGES] 逐步丢统计，**永远保留** version/missing/recovery
     * （先丢统计、保自救），并记录 trimmed 便于调用方知道这不是完整基线。
     */
    fun fitBudget(source: JsonObject, maxBytes: Int = MAX_BYTES): JsonObject {
        if (bytesOf(source) <= maxBytes) return source
        // 防御性拷贝：裁剪不修改调用方对象（缓存里存的那份必须保持完整）。
        val baseline = source.deepCopy()
        val trimmed = ArrayList<String>()
        fun mark(stage: String) {
            if (stage !in trimmed) trimmed.add(stage)
        }
        fun over() = bytesOf(baseline) > maxBytes
        if (over() && baseline.has("jobs")) { baseline.remove("jobs"); mark("jobs") }
        if (over() && (baseline.has("backends") || baseline.has("outputRule"))) {
            baseline.remove("backends"); baseline.remove("outputRule"); mark("backends,outputRule")
        }
        if (over()) {
            val workspaces = baseline.getAsJsonObject("workspaces")
            val apk = workspaces?.getAsJsonObject("apk")
            if (workspaces != null && apk != null && (workspaces.has("script") || apk.has("temporary"))) {
                baseline.add("workspaces", ToolkitIo.json("apk" to ToolkitIo.json(
                    "used" to apk.get("used"), "quota" to apk.get("quota"))))
                mark("workspaces.script+temporary+ttlHint")
            } else if (workspaces != null) {
                baseline.remove("workspaces"); mark("workspaces")
            }
        }
        if (over()) {
            val toolchain = baseline.getAsJsonObject("toolchain")
            if (toolchain != null && toolchain.has("present")) {
                // 先保 missing：把它提到顶层（丢掉 toolchain 也还能自救）
                toolchain.getAsJsonArray("missing")?.let { baseline.add("missing", it.deepCopy()) }
                toolchain.remove("present"); mark("toolchain.present")
            }
        }
        if (over() && baseline.has("currentProject")) { baseline.remove("currentProject"); mark("currentProject") }
        if (over()) {
            val toolchain = baseline.getAsJsonObject("toolchain")
            if (toolchain != null) {
                toolchain.getAsJsonArray("missing")?.let { if (!baseline.has("missing")) baseline.add("missing", it.deepCopy()) }
                baseline.remove("toolchain"); mark("toolchain")
            }
        }
        if (over() && baseline.has("root")) { baseline.remove("root"); mark("root") }
        if (trimmed.isNotEmpty()) {
            baseline.addProperty("trimmed", trimmed.joinToString(","))
            baseline.addProperty("trimNote", "超过 " + maxBytes + " 字节：先丢统计、保留 version/missing/recovery")
        }
        return baseline
    }
}

/**
 * 内容摘要版本号：**内容不变则不变**（客户端可据此去重），内容变了才 +1。
 * task-37 从 McpBuildTools.kt 移到这里（与 baseline 同一文件，纯 JVM 可测）。
 */
internal class McpBaselineVersion {
    private var lastDigest: String? = null
    private var current = 0

    @Synchronized
    fun versionFor(content: JsonObject): Int {
        val digest = ToolkitIo.version(content.toString().toByteArray(Charsets.UTF_8)).take(16)
        if (digest != lastDigest) {
            lastDigest = digest
            current += 1
        }
        return current
    }
}

/** 只读取值助手：缺字段/类型不对时返回 null，不抛（baseline 不能被统计拖垮）。 */
internal fun JsonObject.tIntOrNull(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
