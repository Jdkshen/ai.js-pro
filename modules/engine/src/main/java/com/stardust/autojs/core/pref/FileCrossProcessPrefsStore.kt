package com.stardust.autojs.core.pref

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest

/**
 * [CrossProcessPrefs.Store] 的**真实现**（纯 JDK，无 Android 依赖，可在 JVM 单测里用真实文件跑）。
 *
 * - 原子写：先写同目录临时文件（名字带纳秒+线程号，避免相撞）→ fsync → rename 覆盖；
 *   rename 失败（跨文件系统/被占用）时抛异常，**绝不**留下半份正式文件。
 * - 跨进程互斥：对 sidecar 锁文件用 FileChannel.tryLock() 轮询；**锁超时策略**见 [lockTimeoutMillis]：
 *   超时**不抛异常、也不假装成功**——记 [lockTimeouts] 计数后**无锁继续**（写仍是「读盘→合并」，
 *   最坏退化成本次写与另一个进程的写撞在同一个 key 上，不会像 SharedPreferences 那样整份覆盖丢键），
 *   并把降级事实暴露成计数供上层诊断。
 * - 指纹：长度 + 最后修改时间 + 内容 SHA-256 前 8 字节（读前比对，变了才重载）。
 */
class FileCrossProcessPrefsStore(
    private val root: File,
    private val lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MS,
    private val lockPollMillis: Long = 5L
) : CrossProcessPrefs.Store {

    /** 锁超时次数（诊断用：大于 0 说明发生过降级）。 */
    @Volatile
    var lockTimeouts: Long = 0L
        private set

    private fun fileOf(name: String) = File(root, name)

    private fun lockOf(name: String) = File(root, name + ".lock")

    override fun read(file: String): ByteArray? {
        val target = fileOf(file)
        return if (target.isFile) target.readBytes() else null
    }

    override fun writeAtomically(file: String, bytes: ByteArray) {
        root.mkdirs()
        val target = fileOf(file)
        val unique = System.nanoTime().toString() + "-" + Thread.currentThread().id
        val tmp = File(root, file + ".tmp-" + unique)
        try {
            FileOutputStream(tmp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // 少数文件系统不允许 rename 覆盖：删旧再改名（互斥由文件锁保证）。
                if (target.exists() && !target.delete()) throw IOException("无法替换 " + target)
                if (!tmp.renameTo(target)) throw IOException("无法改名 " + tmp)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    override fun <T> withFileLock(file: String, block: () -> T): T {
        root.mkdirs()
        RandomAccessFile(lockOf(file), "rw").use { handle ->
            val channel = handle.channel
            var lock: FileLock? = null
            val deadline = System.currentTimeMillis() + lockTimeoutMillis
            while (lock == null) {
                lock = try {
                    channel.tryLock()
                } catch (contended: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    if (System.currentTimeMillis() >= deadline) break
                    try {
                        Thread.sleep(lockPollMillis)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            if (lock == null) lockTimeouts += 1
            try {
                return block()
            } finally {
                runCatching { lock?.release() }
            }
        }
    }

    override fun fingerprint(file: String): String? {
        val target = fileOf(file)
        if (!target.isFile) return null
        val bytes = target.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder()
        for (index in 0 until 8) builder.append(String.format("%02x", digest[index]))
        return bytes.size.toString() + ":" + target.lastModified() + ":" + builder
    }

    companion object {
        /** 默认锁超时：2s（写本来是毫秒级；超过说明有进程卡住）。 */
        const val DEFAULT_LOCK_TIMEOUT_MS = 2000L
    }
}
