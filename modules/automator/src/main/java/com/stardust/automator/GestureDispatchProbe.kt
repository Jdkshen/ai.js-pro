package com.stardust.automator

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 手势派发计数器（自研、纯 JVM、可单测）。
 *
 * 为什么：AccessibilityService.dispatchGesture 在框架里维护一张「手势状态回调表」
 * （隐藏字段 mGestureStatusCallbackInfos / mGestureStatusCallbackSequence）。长跑脚本反复派发手势时，
 * 需要把「派发了多少次（带回调 / 不带回调）」与「表里现在有多少条目」对起来，才能判断是不是真的在涨。
 *
 * 设计约束（避免影响手势路径本身）：
 *  - 绝不抛异常、绝不阻塞：所有 I/O 失败都吞掉并记 lastError；未 init 时 record() 是 no-op。
 *  - 不依赖 Android API：进程内原子计数 + 每次落盘到 gesture-dispatch-pid.txt（临时文件 + rename）。
 *  - 跨进程读取：每个进程一个文件，snapshot() 汇总所有文件（script 进程与主进程都能被统计到）。
 *  - 不做采样抖动：写盘是每次 record 一次（诊断用途，量级可控）；文件很小（十几行 key-TAB-value）。
 */
object GestureDispatchProbe {
    const val FILE_PREFIX = "gesture-dispatch-"
    private const val FILE_SUFFIX = ".txt"

    private val dispatched = AtomicLong()
    private val withCallback = AtomicLong()
    private val withoutCallback = AtomicLong()
    private val bySite = ConcurrentHashMap<String, LongArray>()

    @Volatile private var directory: File? = null
    @Volatile private var ready = false
    @Volatile private var lastError: String? = null
    @Volatile private var lastWriteAt = 0L
    @Volatile private var pid = -1L

    /** 应用启动时调用一次（filesDir 之类）；返回是否可用。 */
    fun init(dir: File?): Boolean {
        directory = dir
        pid = resolvePid()
        ready = try {
            dir != null && (dir.isDirectory || dir.mkdirs())
        } catch (error: Throwable) {
            lastError = error.javaClass.simpleName + ": " + (error.message ?: "")
            false
        }
        return ready
    }

    fun initialized(): Boolean = ready
    fun directoryPath(): String? = directory?.absolutePath
    fun lastError(): String? = lastError
    fun lastWriteAt(): Long = lastWriteAt

    /** 记录一次派发尝试（在调用 dispatchGesture 之前调用）。任何异常都不外抛。 */
    fun record(site: String, withCb: Boolean) {
        val key = site.ifBlank { "unknown" }
        dispatched.incrementAndGet()
        if (withCb) withCallback.incrementAndGet() else withoutCallback.incrementAndGet()
        val bucket = bySite.computeIfAbsent(key) { LongArray(2) }
        synchronized(bucket) {
            if (withCb) bucket[0]++ else bucket[1]++
        }
        flush()
    }

    /** 汇总所有进程文件的计数（含本进程已落盘部分）。读不到就是 0/空表，不伪造数字。 */
    fun snapshot(): Snapshot {
        val totals = LinkedHashMap<String, Long>()
        val sites = LinkedHashMap<String, MutableMap<String, Long>>()
        val files = ArrayList<String>()
        var failed: String? = null
        val dir = directory
        if (dir != null && dir.isDirectory) {
            val list = try {
                dir.listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX) }
            } catch (error: Throwable) {
                failed = error.javaClass.simpleName + ": " + (error.message ?: ""); null
            }
            list?.sortedBy { it.name }?.forEach { file ->
                files.add(file.name)
                try {
                    file.readLines(Charsets.UTF_8).forEach { line ->
                        val parts = line.split('\t')
                        if (parts.size != 2) return@forEach
                        val key = parts[0].trim()
                        val value = parts[1].trim().toLongOrNull() ?: return@forEach
                        if (key.startsWith("site:")) {
                            val rest = key.substringAfter("site:")
                            val site = rest.substringBefore('/')
                            val field = rest.substringAfter('/', "withCallback")
                            val bucket = sites.getOrPut(site) { LinkedHashMap() }
                            bucket[field] = (bucket[field] ?: 0L) + value
                        }
                        totals[key] = (totals[key] ?: 0L) + value
                    }
                } catch (error: Throwable) {
                    failed = error.javaClass.simpleName + ": " + (error.message ?: "")
                }
            }
        }
        return Snapshot(
            initialized = ready || files.isNotEmpty(),
            files = files,
            dispatched = totals["dispatched"] ?: 0L,
            withCallback = totals["withCallback"] ?: 0L,
            withoutCallback = totals["withoutCallback"] ?: 0L,
            sites = sites,
            readError = failed ?: lastError,
        )
    }

    /** 清空计数（内存 + 所有进程文件）；诊断工具显式调用，不影响正在运行的手势。 */
    fun reset(): Int {
        dispatched.set(0L)
        withCallback.set(0L)
        withoutCallback.set(0L)
        bySite.clear()
        val dir = directory ?: return 0
        var removed = 0
        try {
            dir.listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX) }?.forEach {
                if (it.delete()) removed++
            }
        } catch (error: Throwable) {
            lastError = error.javaClass.simpleName + ": " + (error.message ?: "")
        }
        return removed
    }

    data class Snapshot(
        val initialized: Boolean,
        val files: List<String>,
        val dispatched: Long,
        val withCallback: Long,
        val withoutCallback: Long,
        val sites: Map<String, MutableMap<String, Long>>,
        val readError: String?,
    )

    /**
     * 解析当前进程 pid —— **只在 init() 里调一次**，两个坑都踩过：
     *  ① `java.lang.ProcessHandle`（JDK 9+）在 android.jar 里没有 → 编译期 Unresolved reference；
     *  ② `java.lang.management.*` 同样**不在 android.jar** → 编译期 Unresolved reference；
     *  ③ `android.os.Process.myPid()` 在 JVM 单测里是 stub（抛 `RuntimeException: Stub!` 或返回 0）。
     * 所以：用 ③，但**在 init() 里解析并兜底**，绝不在 flush() 的写盘路径上调用 —— 否则异常被 catch 吞掉后
     * 文件永远不写、snapshot 恒为 0（这正是本地单测把 dispatched 读成 0 的原因）。拿不到就用 -1，
     * 文件名 `gesture-dispatch--1.txt`，绝不与真实 pid 撞。
     */
    private fun resolvePid(): Long = try {
        android.os.Process.myPid().toLong()
    } catch (error: Throwable) {
        -1L
    }

    private fun flush() {
        val dir = directory ?: return
        if (!ready) return
        try {
            val body = StringBuilder()
            body.append("dispatched\t").append(dispatched.get()).append('\n')
            body.append("withCallback\t").append(withCallback.get()).append('\n')
            body.append("withoutCallback\t").append(withoutCallback.get()).append('\n')
            bySite.forEach { (site, counters) ->
                synchronized(counters) {
                    body.append("site:").append(site).append("/withCallback\t").append(counters[0]).append('\n')
                    body.append("site:").append(site).append("/withoutCallback\t").append(counters[1]).append('\n')
                }
            }
            val pid = this.pid
            val target = File(dir, FILE_PREFIX + pid + FILE_SUFFIX)
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(body.toString(), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.writeText(body.toString(), Charsets.UTF_8)
                temp.delete()
            }
            lastWriteAt = System.currentTimeMillis()
        } catch (error: Throwable) {
            lastError = error.javaClass.simpleName + ": " + (error.message ?: "")
        }
    }
}
