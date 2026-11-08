package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Frida Gadget listen 端口的**主动连接探测**（设备4 Bug3 证据链 ★①）。
 *
 * 背景：应用域（untrusted_app）在部分 ROM 上读不了 /proc/net/tcp[6]（SELinux 对
 * proc_net_tcp_udp 的拒绝），而 frida_* 跑在 app 进程 —— 「读 /proc 判端口」这条路
 * 结构性不可用。应用域**允许**发起 socket 连接，所以改为主动 connect：
 *
 *   listening_reachable  connect 成功 → 端口上有监听者（portListening=true）
 *   refused              立刻 ECONNREFUSED/RST → 端口明确拒绝（不是静默丢弃）
 *   timeout              在超时内没有结果 → loopback 上正常关闭的端口会**立刻** refused，
 *                        因此超时更像 SYN 被策略静默 DROP（跨 UID SELinux）或对端无响应
 *   error:<类名>         其它 IOException（unknown host/no route/socket 建立失败…）
 *
 * **超时与拒绝必须分开**：只有 refused 才是「没在听」的强证据；timeout 既不能证明端口未监听，
 * 也不能证明组件没装（见 [ToolkitFridaDiagnosis]）。
 *
 * 本对象是纯逻辑：连接器（[Connector]）可注入，单测用假连接器或本机 ServerSocket；
 * 不读 /proc、不依赖 Android，也不执行任何命令。
 */
internal object ToolkitFridaPortProbe {

    const val RESULT_LISTENING_REACHABLE = "listening_reachable"
    const val RESULT_REFUSED = "refused"
    const val RESULT_TIMEOUT = "timeout"
    /** 其它失败的统一前缀，完整值形如 {@code error:java.net.NoRouteToHostException}。 */
    const val RESULT_ERROR_PREFIX = "error:"

    /** 默认 800ms：够覆盖 loopback 的握手，又不会让 frida_status 长时间卡住。 */
    const val DEFAULT_TIMEOUT_MS = 800
    const val MIN_TIMEOUT_MS = 50
    const val MAX_TIMEOUT_MS = 10_000

    private val TIMEOUT_HINT = Regex("(?i)(timed? ?out|timeout|etimedout)")
    private val REFUSED_HINT = Regex("(?i)(refused|econnrefused|reset|econnreset)")

    /**
     * 可注入的连接器：连上就正常返回，失败就抛异常。
     * 生产实现是 [socketConnector]；单测注入假实现即可覆盖四种分类，无需真网络。
     */
    fun interface Connector {
        @Throws(IOException::class)
        fun connect(host: String, port: Int, timeoutMs: Int)
    }

    /** 生产实现：TCP connect + 连接超时（应用域允许 connect，不需要读 /proc/net/tcp）。 */
    val socketConnector = Connector { host, port, timeoutMs ->
        Socket().use { socket -> socket.connect(InetSocketAddress(host, port), timeoutMs) }
    }

    /** 解析 {@code host:port}；host 非空、端口 1..65535 才接受，否则返回 null。 */
    fun parseEndpoint(endpoint: String): Pair<String, Int>? {
        val value = endpoint.trim()
        val index = value.lastIndexOf(':')
        if (index <= 0 || index == value.length - 1) return null
        val host = value.substring(0, index)
        val port = value.substring(index + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }

    /** 端口 -> 4 位大写十六进制（与 /proc/net/tcp 的 local_port 对齐，便于交叉核对）。 */
    fun portHex(port: Int): String = port.toString(16).uppercase().padStart(4, '0')

    /** 按 endpoint 字符串探测；endpoint 非法时返回 {@code error:java.lang.IllegalArgumentException}，不抛异常。 */
    fun probeEndpoint(
        endpoint: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        connector: Connector = socketConnector,
    ): JsonObject {
        val parsed = parseEndpoint(endpoint)
            ?: return result(
                endpoint = endpoint, host = endpoint, port = 0, timeoutMs = timeoutMs,
                probeResult = RESULT_ERROR_PREFIX + IllegalArgumentException::class.java.name,
                failure = IllegalArgumentException("endpoint 必须为 host:port（端口 1..65535）：$endpoint"),
                elapsedMs = 0L,
            )
        return probe(parsed.first, parsed.second, timeoutMs, connector)
    }

    /**
     * 主动 connect 探测一次并返回结构化结果。
     *
     * @param timeoutMs 连接超时，默认 [DEFAULT_TIMEOUT_MS]，会被夹到 [MIN_TIMEOUT_MS]..[MAX_TIMEOUT_MS]
     * @param connector 连接器（默认真 socket；单测注入假实现）
     */
    fun probe(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        connector: Connector = socketConnector,
    ): JsonObject {
        val effective = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val startedAt = System.nanoTime()
        val failure = try {
            connector.connect(host, port, effective)
            null
        } catch (error: Exception) {
            error
        }
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
        return result(
            endpoint = host + ":" + port, host = host, port = port, timeoutMs = effective,
            probeResult = classifyFailure(failure), failure = failure, elapsedMs = elapsed,
        )
    }

    /**
     * 纯分类：把一次探测的异常映射成四类结果之一（超时与拒绝分开）。
     *
     * 语义：
     * - {@code null} → listening_reachable；
     * - [SocketTimeoutException] → timeout；
     * - [ConnectException] 消息里带 timed out → timeout；其余（含无消息）→ refused
     *   （Linux loopback 上关闭端口抛的就是 {@code ConnectException: Connection refused}）；
     * - 其它异常消息带 timeout 字样 → timeout；否则 {@code error:<异常类名>}。
     */
    fun classifyFailure(failure: Throwable?): String = when {
        failure == null -> RESULT_LISTENING_REACHABLE
        failure is SocketTimeoutException -> RESULT_TIMEOUT
        failure is ConnectException -> {
            val text = failure.message.orEmpty()
            if (TIMEOUT_HINT.containsMatchIn(text)) RESULT_TIMEOUT else RESULT_REFUSED
        }
        else -> {
            val text = failure.message.orEmpty()
            if (TIMEOUT_HINT.containsMatchIn(text)) RESULT_TIMEOUT
            else RESULT_ERROR_PREFIX + failure.javaClass.name
        }
    }

    /** 探测结果是否是「端口可达」的正面证据（等价于旧的 portListening=true）。 */
    fun reachable(probe: JsonObject?): Boolean = probeResultOf(probe) == RESULT_LISTENING_REACHABLE

    /** 探测结果是否是「超时」（等价于旧的 timeoutObserved；SELinux 静默 DROP 的典型特征）。 */
    fun timeoutObserved(probe: JsonObject?): Boolean = probeResultOf(probe) == RESULT_TIMEOUT

    /** 探测结果是否明确被拒绝（ECONNREFUSED/RST）。 */
    fun refusedObserved(probe: JsonObject?): Boolean = probeResultOf(probe) == RESULT_REFUSED

    /** 探测是否「无结论」（探测本身失败，如 unknown host / no route）。 */
    fun inconclusive(probe: JsonObject?): Boolean =
        probeResultOf(probe)?.startsWith(RESULT_ERROR_PREFIX) == true

    /** 从探测对象里取 probeResult（读不到返回 null）。 */
    fun probeResultOf(probe: JsonObject?): String? =
        probe?.get("probeResult")?.takeIf { it.isJsonPrimitive }?.asString

    private fun result(
        endpoint: String,
        host: String,
        port: Int,
        timeoutMs: Int,
        probeResult: String,
        failure: Throwable?,
        elapsedMs: Long,
    ): JsonObject {
        val reachable = probeResult == RESULT_LISTENING_REACHABLE
        val timeout = probeResult == RESULT_TIMEOUT
        val refused = probeResult == RESULT_REFUSED
        val family = when {
            reachable -> "reachable"
            timeout -> "timeout"
            refused -> "refused"
            else -> "error"
        }
        val safePort = if (port in 1..65535) port else 0
        val portHexValue = if (safePort > 0) portHex(safePort) else null
        return ToolkitIo.json(
            "source" to "active_socket_connect",
            "endpoint" to endpoint,
            "host" to host,
            "port" to safePort,
            "portHex" to portHexValue,
            "probeResult" to probeResult,
            "resultFamily" to family,
            "reachable" to reachable,
            // 下面三个字段刻意与 ToolkitFridaDiagnosis.classifyConnectFailure 的证据参数同名，
            // 调用方可以直接合并（probe 优先，/proc 文本只是可选补充）。
            "portListening" to reachable,
            "timeoutObserved" to timeout,
            "refusedObserved" to refused,
            "elapsedMs" to elapsedMs,
            "timeoutMs" to timeoutMs,
            "connector" to "socket",
            "errorClass" to failure?.javaClass?.name,
            "errorMessage" to failure?.message?.take(300),
            "interpretation" to when {
                reachable -> "主动 socket.connect 成功（耗时 ${elapsedMs}ms）：端口上有进程在监听，应用域可以做 loopback 连接。"
                refused -> "对端立刻拒绝（ECONNREFUSED/RST，耗时 ${elapsedMs}ms）：端口上没有监听者；这与「SYN 被静默 DROP」相反。" +
                    "（注意 Gadget 只在目标应用默认进程存活且已加载时监听，端口未起时同样表现为 refused。）"
                timeout -> "connect 在 ${timeoutMs}ms 内没有结果（实际 ${elapsedMs}ms）：loopback 上关闭的端口会立刻 refused，" +
                    "因此超时更像 SYN 被策略静默丢弃（跨 UID SELinux）或对端无响应；这不能证明组件没装。"
                else -> "主动探测本身失败（${probeResult}）：既不能证明端口未监听，也不能证明组件没装；先按 errorClass 排查探测侧。"
            },
            "diagnosisArgs" to ToolkitIo.json(
                "portListening" to reachable,
                "timeoutObserved" to timeout,
                "refusedObserved" to refused,
                "probeResult" to probeResult,
            ),
            "probePrecedence" to "probe 是权威证据；/proc/net/tcp[6] 只是可选补充（应用域读不到是预期行为）；" +
                "调用方显式传入的 portListening/synSentObserved 仍然优先于自动采集。",
        )
    }
}
