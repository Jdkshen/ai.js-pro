package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stardust.automator.GestureDispatchProbe
import java.lang.reflect.Field

/**
 * task-43（①量泄漏）：手势回调表泄漏探针（**只读 + 显式自压测**，自研）。
 *
 * 背景：AccessibilityService.dispatchGesture 在框架里维护一张手势状态回调表
 * （隐藏字段 mGestureStatusCallbackInfos / mGestureStatusCallbackSequence）。我们直接继承框架类、
 * 没有任何清理逻辑，所以要先量：表条目数会不会随派发线性增长。
 *
 * 诚实边界（同时写进工具描述与返回 notes）：
 *  - 反射隐藏字段是**跨版本脆**的：字段改名/被裁掉/被 OEM 改过就读不到 → supported=false + reason，
 *    **绝不伪造数字**，也绝不抛异常、绝不影响手势路径。
 *  - 表条目数只是**框架侧的一个计数**，不等于完整的内存泄漏证明（还要 Java heap/PSS 与长跑对比）。
 *  - stress 会**真的派发手势**（1px、极短时长、不改变界面语义），是诊断动作，不是只读。
 *
 * 纯逻辑都在这里，派发用一个可注入的 lambda，便于单测（不需要真机/无障碍服务）。
 */
internal object ToolkitGestureProbe {
    /** 一个时刻的框架表读数；读不到就是 supported=false。 */
    data class TableSnapshot(
        val supported: Boolean,
        val size: Int?,
        val sequence: Long?,
        val reason: String?,
        val instanceClass: String?,
        val probedFields: List<String>,
    ) {
        fun toJson(): JsonObject = ToolkitIo.json(
            "supported" to supported,
            "size" to size,
            "sequence" to sequence,
            "reason" to reason,
            "instanceClass" to instanceClass,
            "probedFields" to probedFields,
        )
    }

    data class StressOutcome(
        val requested: Int,
        val dispatched: Int,
        val failed: Int,
        val withCallback: Boolean,
        val elapsedMs: Long,
        val stoppedBy: String,
        val before: TableSnapshot,
        val after: TableSnapshot,
    )

    private const val FIELD_INFOS = "mGestureStatusCallbackInfos"
    private const val FIELD_SEQUENCE = "mGestureStatusCallbackSequence"

    /**
     * best-effort 反射读表：只读 SparseArray.size() 与 sequence 字段；任何异常都转成 supported=false。
     * instance 允许是任意对象（单测传普通对象走 null 分支）。
     */
    fun snapshot(instance: Any?): TableSnapshot {
        if (instance == null) {
            return TableSnapshot(false, null, null, "当前进程没有 AccessibilityService 实例（服务未开启或不在该进程）", null, emptyList())
        }
        val javaClass = instance.javaClass
        val probed = ArrayList<String>()
        return try {
            val infos = findField(javaClass, FIELD_INFOS)
            val sequence = findField(javaClass, FIELD_SEQUENCE)
            probed.add(FIELD_INFOS)
            probed.add(FIELD_SEQUENCE)
            if (infos == null && sequence == null) {
                return TableSnapshot(
                    false, null, null,
                    "在 " + javaClass.name + " 及其父类上找不到隐藏字段 " + FIELD_INFOS + " / " + FIELD_SEQUENCE +
                        "（不同 Android 版本字段可能改名或不存在）",
                    javaClass.name, emptyList(),
                )
            }
            val size = infos?.let { field ->
                val value = runCatching { field.get(instance) }.getOrNull()
                value?.let { readSize(it) }
            }
            val seq = sequence?.let { field ->
                runCatching { field.get(instance) }.getOrNull()?.let { readLong(it) }
            }
            if (size == null && seq == null) {
                TableSnapshot(false, null, null,
                    "字段存在但都读不出值（可能被 setAccessible 拒绝或类型不认识）", javaClass.name, probed)
            } else {
                TableSnapshot(true, size, seq, null, javaClass.name, probed)
            }
        } catch (error: Throwable) {
            // 反射 + 隐藏 API 的组合什么都可能抛：一律降级，不冒泡。
            TableSnapshot(false, null, null,
                "反射失败：" + error.javaClass.name + ": " + (error.message ?: ""), javaClass.name, probed)
        }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        var depth = 0
        while (current != null && depth < 8) {
            val found = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (found != null) {
                runCatching { found.isAccessible = true }
                return found
            }
            current = current.superclass
            depth++
        }
        return null
    }

    /** SparseArray.size() 或任何 size()/getSize() 方法；不直接依赖 android.util.SparseArray（单测在 JVM 上跑）。 */
    private fun readSize(value: Any): Int? {
        if (value is Collection<*>) return value.size
        if (value is Map<*, *>) return value.size
        val method = runCatching { value.javaClass.getMethod("size") }.getOrNull()
            ?: runCatching { value.javaClass.getMethod("getSize") }.getOrNull()
        return runCatching { method?.invoke(value) as? Int }.getOrNull()
    }

    private fun readLong(value: Any): Long? = when (value) {
        is Number -> value.toLong()
        else -> runCatching { value.javaClass.getMethod("get").invoke(value) as? Long }.getOrNull()
    }

    /**
     * 自压测：连续派发 count 次极小手势（1px、1ms），返回 before/after 表读数与真实派发次数。
     * dispatch 由调用方注入（真机走 service.dispatchGesture；单测走假实现）。
     */
    fun stress(
        count: Int,
        timeoutSeconds: Int,
        withCallback: Boolean,
        intervalMs: Int,
        probe: () -> TableSnapshot,
        dispatch: (Boolean) -> Boolean,
        now: () -> Long = { System.currentTimeMillis() },
        sleep: (Long) -> Unit = { millis -> if (millis > 0) Thread.sleep(millis) },
    ): StressOutcome {
        val before = probe()
        val started = now()
        val deadline = started + timeoutSeconds.coerceAtLeast(1) * 1000L
        var dispatched = 0
        var failed = 0
        var stoppedBy = "completed"
        var index = 0
        while (index < count) {
            if (now() >= deadline) { stoppedBy = "timeout"; break }
            val ok = runCatching { dispatch(withCallback) }.getOrDefault(false)
            if (ok) dispatched++ else failed++
            index++
            if (intervalMs > 0) runCatching { sleep(intervalMs.toLong()) }
        }
        val after = probe()
        return StressOutcome(count, dispatched, failed, withCallback, now() - started, stoppedBy, before, after)
    }

    /** 汇总「派发次数」与「表条目数」：把两个口径并排给出，不做任何因果声称。 */
    fun report(instance: Any?, countersOverride: GestureDispatchProbe.Snapshot? = null): JsonObject {
        val table = snapshot(instance)
        val counters = countersOverride ?: GestureDispatchProbe.snapshot()
        val sites = JsonObject()
        counters.sites.forEach { (site, values) ->
            sites.add(site, ToolkitIo.json(
                "withCallback" to (values["withCallback"] ?: 0L),
                "withoutCallback" to (values["withoutCallback"] ?: 0L),
            ))
        }
        val notes = JsonArray()
        notes.add("反射隐藏字段是跨版本脆的：读不到就给 supported=false + reason，绝不伪造数字，也不影响手势路径。")
        notes.add("表条目数只是框架侧的一个计数，不等于完整的内存泄漏证明（要结合 Java heap/PSS 与长跑前后对比）。")
        notes.add("计数器跨进程汇总（每进程一个 gesture-dispatch-<pid>.txt），bySite 区分三处调用点；未 init(filesDir) 时 record 是 no-op。")
        if (!table.supported) notes.add("本次读不到表：" + (table.reason ?: "未知原因"))
        if (!counters.initialized) notes.add("计数器目录未初始化（App 未启动到 init 点）：派发次数会显示 0，不要据此判断没有派发。")
        if (counters.readError != null) notes.add("计数器读取有告警：" + counters.readError)
        return ToolkitIo.json(
            "table" to table.toJson(),
            "counters" to ToolkitIo.json(
                "initialized" to counters.initialized,
                "files" to counters.files,
                "dispatched" to counters.dispatched,
                "withCallback" to counters.withCallback,
                "withoutCallback" to counters.withoutCallback,
                "bySite" to sites,
                "readError" to counters.readError,
            ),
            "notes" to notes,
        )
    }
}
