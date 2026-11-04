package com.jdkshen.aijspro.mcp

import com.google.gson.*

/**
 * /proc/net/tcp 与 /proc/net/tcp6 的纯解析器（BUG-3/T1：让 Frida 诊断自动拿到证据）。
 *
 * 背景：Frida Gadget listen 模式跨 UID 时，loopback 的 SYN 可能被 SELinux 静默 DROP。
 * 设备侧典型证据：
 *   /proc/net/tcp:  0100007F:69A2 00000000:0000 0A  -> LISTEN
 *   客户端侧:       0100007F:8A88 0100007F:69A2 02  -> SYN_SENT（卡住 = 被静默丢弃，不是 RST）
 *
 * 本对象只接收调用方传入的文本，不读文件、不执行命令、不依赖 Android，便于单测；
 * 输出可直接喂给 ToolkitFridaDiagnosis.classifyConnectFailure（portListening / synSentObserved）。
 *
 * /proc/net/tcp 行格式（列之间任意空白）：
 *   sl local_address rem_address st tx_queue:rx_queue tr:tm->when retrnsmt uid timeout inode ...
 * 地址为小端十六进制：IPv4 8 位（0100007F = 127.0.0.1），IPv6 32 位；端口为 4 位十六进制。
 * 本对象把地址/端口/state 统一为大写十六进制，state 保留两位十六进制（不映射成名称）。
 */
internal object ToolkitProcNetTcp {
    const val STATE_LISTEN = "0A"
    const val STATE_SYN_SENT = "02"

    const val PROC_TCP = "/proc/net/tcp"
    const val PROC_TCP6 = "/proc/net/tcp6"

    private const val V4_ANY = "00000000"
    private const val V4_LOOPBACK = "0100007F"
    private const val V6_ANY = "00000000000000000000000000000000"
    private const val V6_LOOPBACK = "00000000000000000000000001000000"

    private val SLOT_PATTERN = Regex("\\d+:")
    private val ADDRESS_PATTERN = Regex("[0-9A-Fa-f]{8}|[0-9A-Fa-f]{32}")
    private val PORT_HEX_PATTERN = Regex("[0-9A-Fa-f]{4}")
    private val STATE_PATTERN = Regex("[0-9A-Fa-f]{2}")

    /** 一行 /proc/net/tcp 记录；地址/端口/state 均为大写十六进制原值。 */
    data class Entry(
        val localAddressHex: String,
        val localPortHex: String,
        val remoteAddressHex: String,
        val remotePortHex: String,
        val state: String,
        val uid: Int?,
        val inode: String?,
    )

    /** 解析统计：坏行/表头计数，便于在诊断里明确「解析是否可靠」。 */
    data class ParseResult(
        val entries: List<Entry>,
        val skippedLines: Int,
        val headerLines: Int,
        val totalLines: Int,
    )

    /**
     * 严格但宽容地解析。跳过表头、空行与坏行（不抛异常）；
     * 坏行计数见 [parseResult].skippedLines。
     */
    fun parse(text: String): List<Entry> = parseResult(text).entries

    /** 带统计的解析：供诊断输出 skippedLines/headerLines，避免坏行被静默忽略。 */
    fun parseResult(text: String): ParseResult {
        val entries = ArrayList<Entry>()
        var skipped = 0
        var header = 0
        var total = 0
        if (text.isEmpty()) return ParseResult(emptyList(), 0, 0, 0)
        for (rawLine in text.lineSequence()) {
            total++
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val fields = line.split(Regex("\\s+"))
            if (fields[0] == "sl") { header++; continue }
            if (fields.size < 4 || !SLOT_PATTERN.matches(fields[0])) { skipped++; continue }
            val local = splitAddress(fields[1])
            val remote = splitAddress(fields[2])
            val state = fields[3]
            if (local == null || remote == null || !STATE_PATTERN.matches(state)) { skipped++; continue }
            val uid = fields.getOrNull(7)?.toIntOrNull()
            val inode = fields.getOrNull(9)?.takeIf { it.isNotEmpty() && it != "-" }
            entries.add(
                Entry(
                    localAddressHex = local.first,
                    localPortHex = local.second,
                    remoteAddressHex = remote.first,
                    remotePortHex = remote.second,
                    state = state.uppercase(),
                    uid = uid,
                    inode = inode,
                ),
            )
        }
        return ParseResult(entries, skipped, header, total)
    }

    /** state=0A 且 local port 命中，地址为 0.0.0.0/127.0.0.1（或 IPv6 ::/::1）时视为 LISTEN。 */
    fun listenPresent(entries: List<Entry>, port: Int): Boolean {
        val portHex = hexPort(port)
        return entries.any { isListenEntry(it, portHex) }
    }

    /** state=02 且 remote port 命中（客户端侧 /proc/<pid>/net/tcp 里看 SYN_SENT）。 */
    fun synSentTo(entries: List<Entry>, port: Int): Boolean {
        val portHex = hexPort(port)
        return entries.any { it.state == STATE_SYN_SENT && it.remotePortHex == portHex }
    }

    /** 十进制端口 -> 4 位大写十六进制，例如 27042 -> 69A2、1 -> 0001、65535 -> FFFF。 */
    fun hexPort(port: Int): String {
        require(port in 1..65535) { "port 必须在 1..65535" }
        return port.toString(16).uppercase().padStart(4, '0')
    }

    /** 汇总监听与 SYN_SENT 证据；listeners/synSent 保留完整 Entry 字段。 */
    fun summarize(entries: List<Entry>, port: Int): JsonObject {
        val portHex = hexPort(port)
        val listeners = entries.filter { isListenEntry(it, portHex) }
        val synSent = entries.filter { it.state == STATE_SYN_SENT && it.remotePortHex == portHex }
        return ToolkitIo.json(
            "port" to port,
            "portHex" to portHex,
            "listenPresent" to listeners.isNotEmpty(),
            "synSentTo" to synSent.isNotEmpty(),
            "listeners" to entriesToJson(listeners),
            "synSent" to entriesToJson(synSent),
            "listenerCount" to listeners.size,
            "synSentCount" to synSent.size,
        )
    }

    /**
     * 把 /proc/net/tcp 与（可选）/proc/net/tcp6 文本合成可直接喂给
     * ToolkitFridaDiagnosis.classifyConnectFailure 的证据：
     * 顶层 portListening/synSentObserved 可直接 asBoolean 使用；同时给出解析统计与 raw 证据。
     *
     * sameUid 无法从这两个文件推断（tcp 里的 uid 是 socket 所属 UID，不是客户端/目标进程 UID），
     * 必须由调用方比较目标进程与客户端进程 UID 后传入 classifyConnectFailure。
     */
    fun forDiagnosis(tcpText: String, tcp6Text: String?, port: Int): JsonObject {
        val tcp = parseResult(tcpText)
        val tcp6 = tcp6Text?.let { parseResult(it) }
        val all = ArrayList<Entry>(tcp.entries)
        tcp6?.entries?.let { all.addAll(it) }
        val summary = summarize(all, port)
        val portHex = hexPort(port)
        return ToolkitIo.json(
            "source" to PROC_TCP,
            "sourceTcp6" to PROC_TCP6,
            "port" to port,
            "portHex" to portHex,
            "portListening" to summary.get("listenPresent").asBoolean,
            "synSentObserved" to summary.get("synSentTo").asBoolean,
            "sameUid" to null,
            "sameUidKnown" to false,
            "sameUidRequired" to true,
            "classifyConnectFailureArgs" to ToolkitIo.json(
                "portListening" to summary.get("listenPresent").asBoolean,
                "synSentObserved" to summary.get("synSentTo").asBoolean,
                "sameUid" to null,
            ),
            "listeners" to summary.get("listeners"),
            "synSent" to summary.get("synSent"),
            "parsedEntries" to all.size,
            "tcp" to ToolkitIo.json(
                "parsedEntries" to tcp.entries.size,
                "skippedLines" to tcp.skippedLines,
                "headerLines" to tcp.headerLines,
                "totalLines" to tcp.totalLines,
            ),
            "tcp6" to if (tcp6 == null) {
                null
            } else {
                ToolkitIo.json(
                    "parsedEntries" to tcp6.entries.size,
                    "skippedLines" to tcp6.skippedLines,
                    "headerLines" to tcp6.headerLines,
                    "totalLines" to tcp6.totalLines,
                )
            },
            "skippedLines" to (tcp.skippedLines + (tcp6?.skippedLines ?: 0)),
            "note" to "sameUid 不能从 /proc/net/tcp 推断：tcp 里的 uid 是 socket 所有者的 UID；请比较目标进程与客户端进程 UID 后再调用 classifyConnectFailure。sameUid 为 null 表示由调用方补齐，不要在这里猜。",
        )
    }

    private fun isListenEntry(entry: Entry, portHex: String): Boolean =
        entry.state == STATE_LISTEN && entry.localPortHex == portHex && isListenAddress(entry.localAddressHex)

    private fun isListenAddress(address: String): Boolean {
        if (address == V4_ANY || address == V4_LOOPBACK || address == V6_ANY || address == V6_LOOPBACK) return true
        // IPv4-mapped IPv6 的 loopback/wildcard 变体（::ffff:127.0.0.1 / ::ffff:0.0.0.0）。
        return address.length == 32 && address.startsWith("0000000000000000FFFF0000") &&
            (address.endsWith(V4_LOOPBACK) || address.endsWith(V4_ANY))
    }

    /** 拆 local/remote 的 "ADDRESS:PORT"；地址 8/32 位十六进制、端口 4 位十六进制才接受。 */
    private fun splitAddress(token: String): Pair<String, String>? {
        val index = token.lastIndexOf(':')
        if (index <= 0 || index == token.length - 1) return null
        val address = token.substring(0, index)
        val port = token.substring(index + 1)
        if (!ADDRESS_PATTERN.matches(address) || !PORT_HEX_PATTERN.matches(port)) return null
        return address.uppercase() to port.uppercase()
    }

    private fun entriesToJson(entries: List<Entry>): JsonArray = JsonArray().apply {
        entries.forEach { entry ->
            add(ToolkitIo.json(
                "localAddressHex" to entry.localAddressHex,
                "localPortHex" to entry.localPortHex,
                "remoteAddressHex" to entry.remoteAddressHex,
                "remotePortHex" to entry.remotePortHex,
                "state" to entry.state,
                "uid" to entry.uid,
                "inode" to entry.inode,
            ))
        }
    }
}
