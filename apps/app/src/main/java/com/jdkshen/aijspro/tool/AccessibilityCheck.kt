package com.jdkshen.aijspro.tool

import android.content.Context
import android.os.SystemClock
import com.jdkshen.aijspro.automation.AutomationConnectionState
import com.stardust.app.GlobalAppContext
import com.stardust.view.accessibility.AccessibilityService
import com.stardust.view.accessibility.AccessibilityServiceUtils

/**
 * 无障碍服务「是否已启用」的**只读检查**（Pro 对齐 ②，自研实现，不复制 Pro 代码）。
 *
 * 对齐 Pro 9.3.11 `AccessibilityServiceTool.checkAccessibilityService()/checkAccessibilityServiceBlocking()` 的
 * 形态：结果对象（Success / Failure(reason, hint)）+ 2s 默认等待 + 「超时不是成功」。
 *
 * 判定依据（按顺序，前两条任一成立即 Success）：
 * 1. **本进程内**服务实例已连接（[AccessibilityService.instance] != null）；
 * 2. 自动化通路已就绪（`AutomationConnectionState.isReady`）——跨进程等价证据：:script 进程里它读的就是
 *    同一个 instance，主进程里它走 `content://<pkg>.automation.state` 查 :script；
 * 3. 系统设置 `settings get secure enabled_accessibility_services`：**三种情况严格分开**
 *    （见 [SettingsRead]，绝不用 null 同时表示两件事）：
 *    - 读**抛异常** → `settings_unreadable`（不可判定，不谎报未启用）；
 *    - 读成功但值为 **null/空/"null"** → **`not_enabled`**（系统里没有任何已启用服务，这是最常见场景）；
 *    - 读成功有值但不含我们的组件 → `not_enabled`（同一份解析 [AccessibilityServiceUtils.settingsContainsComponent]）；
 * 4. 设置在、实例没连上：阻塞形态等满超时 → `timeout`（不假装成功）；非阻塞形态 → `not_connected`（没有等待，不能说超时）。
 *
 * **进程边界（必须知道）**：我们的服务声明在 `:script` 进程
 * （modules/engine/src/main/AndroidManifest.xml 的 `SelectToSpeakService`，`android:process=":script"`），
 * 所以主进程里 `AccessibilityService.instance` **恒为 null**——这不是「没启用」。本条靠判定依据 2
 * （automationConnectionReady）区分，绝不会因为「主进程看不到实例」就判未启用。
 *
 * 边界：
 * - 本对象**只读**，不改任何系统设置；启用仍走 [AccessibilityServiceTool.enableAccessibilityService]；
 * - 组件名只有一个来源：[SERVICE_CLASS_NAME]（[AccessibilityServiceTool] 的启用命令也用 [componentName] 拼，
 *   类初始化时还会校验两边一致，漂移会立刻抛异常而不是静默不一致）；
 * - 无障碍实例就绪 ≠ 全链路可自动化（还有截图/手势权限等），这里只回答「无障碍服务是否已启用并连上」。
 */
object AccessibilityCheck {

    /** 默认等待上限（与 Pro 的 WAIT_FOR_ENABLED_TIMEOUT 对齐）。 */
    const val WAIT_FOR_ENABLED_TIMEOUT = 2000L

    /** 我们的无障碍服务类名：**唯一来源**（启用链路与检查链路共用）。 */
    const val SERVICE_CLASS_NAME = "com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    const val REASON_CONNECTED = "connected"
    const val REASON_NOT_ENABLED = "not_enabled"
    const val REASON_NOT_CONNECTED = "not_connected"
    const val REASON_TIMEOUT = "timeout"
    const val REASON_SETTINGS_UNREADABLE = "settings_unreadable"

    /**
     * `settings get secure enabled_accessibility_services` 的读取结果。
     *
     * <p>**关键**：[readFailed]=true 表示**读抛异常**（权限/ROM 限制）；[readFailed]=false 且 [value] 为 null/空
     * 表示**读成功但系统里没有值**（没有任何已启用服务）。两者语义完全不同，不能用 null 同时表示。
     */
    data class SettingsRead(val readFailed: Boolean, val value: String?, val errorClass: String? = null) {
        /** 读成功但值为 null/空/"null"：系统里没有任何已启用服务。 */
        val valueAbsent: Boolean
            get() = !readFailed && (value == null || value.isEmpty() || "null".equals(value, ignoreCase = true))

        companion object {
            @JvmStatic
            fun read(value: String?): SettingsRead = SettingsRead(readFailed = false, value = value)

            @JvmStatic
            fun failed(errorClass: String?): SettingsRead = SettingsRead(readFailed = true, value = null, errorClass = errorClass)
        }
    }

    /** 检查结果。[Success] 与 Pro 的 `object Success` 对齐：没有字段，就是「已启用且已连上」。 */
    sealed class AccessibilityCheckResult {
        object Success : AccessibilityCheckResult()

        /**
         * 失败：reason 是稳定的机读码，hint 是给人看的解释，[nextActions] 是**可直接抄**的调用建议。
         */
        data class Failure(
            val reason: String,
            val hint: String,
            val nextActions: List<String> = emptyList(),
            val componentName: String? = null,
            /** null = 设置**读异常**（不可判定）；false = 读到了但（空/不含我们）；true = 设置里含我们。 */
            val settingsContainsComponent: Boolean? = null,
            /** 设置读异常时的异常类名（仅 readFailed 时有值）。 */
            val settingsReadError: String? = null,
            /** 本进程内的 AccessibilityService 实例是否已连上（服务在 :script，主进程恒 false）。 */
            val connected: Boolean = false,
            /** 跨进程的自动化通路是否已就绪（主进程判「已启用」主要看它）。 */
            val automationConnectionReady: Boolean = false,
            val elapsedMs: Long = 0L,
        ) : AccessibilityCheckResult()
    }

    /**
     * 可注入探针：生产实现见 [androidProbe]；JVM 单测注入假实现（含假时钟），**不需要 sleep 2s**。
     */
    interface Probe {
        /** 我们的服务组件名（`<pkg>/<service class>`）。 */
        fun componentName(): String
        /** 系统设置读取结果（**读异常**与**读到空**必须能区分**，见 [SettingsRead]）。 */
        fun enabledServicesSetting(): SettingsRead
        /** 本进程内服务实例是否已连接（= `AccessibilityService.instance != null`）。 */
        fun isServiceConnected(): Boolean
        /** 等待本进程实例连接（复用 `AccessibilityService.waitForEnabled`），返回是否在超时内连上。 */
        fun awaitServiceConnected(timeoutMs: Long): Boolean
        /** 单调时钟（毫秒）：只用于算 elapsed 与剩余预算，便于单测注入假时钟。 */
        fun uptimeMillis(): Long
        /** 跨进程自动化通路是否就绪（`AutomationConnectionState.isReady`）；假实现默认 false。 */
        fun automationConnectionReady(): Boolean = false
    }

    /** Java 友好的异步回调（Kotlin 函数类型不好从 Java 传）。 */
    fun interface Callback {
        fun onResult(result: AccessibilityCheckResult)
    }

    /** 组件名：`<pkg>/<SERVICE_CLASS_NAME>`（启用链路也用这一份）。 */
    @JvmStatic
    fun componentName(packageName: String): String = packageName + "/" + SERVICE_CLASS_NAME

    // ------------------------------------------------------------------ 检查

    /** 阻塞检查（最多等 [timeoutMs]）：已连上/自动化已就绪→Success；设置里没有→not_enabled（不白等）；有但没连上→timeout。 */
    fun checkBlocking(probe: Probe, timeoutMs: Long = WAIT_FOR_ENABLED_TIMEOUT): AccessibilityCheckResult {
        val component = probe.componentName()
        val started = probe.uptimeMillis()
        val budget = if (timeoutMs <= 0L) 0L else timeoutMs
        fun elapsed(): Long = (probe.uptimeMillis() - started).coerceAtLeast(0L)
        if (probe.isServiceConnected()) return AccessibilityCheckResult.Success
        val automationReady = probe.automationConnectionReady()
        if (automationReady) return AccessibilityCheckResult.Success
        val settings = probe.enabledServicesSetting()
        if (settings.readFailed) {
            return failure(REASON_SETTINGS_UNREADABLE, component, budget, null, automationReady, elapsed(), settings)
        }
        if (!AccessibilityServiceUtils.settingsContainsComponent(settings.value, component)) {
            // 读成功但值为 null/空/"null"（系统里没有任何已启用服务）与「有值但不含我们」都归 not_enabled。
            return failure(REASON_NOT_ENABLED, component, budget, false, automationReady, elapsed(), settings)
        }
        val remaining = (budget - elapsed()).coerceAtLeast(0L)
        if (probe.awaitServiceConnected(remaining)) return AccessibilityCheckResult.Success
        return failure(REASON_TIMEOUT, component, budget, true, automationReady, elapsed(), settings)
    }

    /** 非阻塞（只看当前，不等待）：设置里有组件但实例没连上→not_connected（不是 timeout，因为压根没等）。 */
    fun checkNow(probe: Probe): AccessibilityCheckResult {
        val component = probe.componentName()
        if (probe.isServiceConnected()) return AccessibilityCheckResult.Success
        val automationReady = probe.automationConnectionReady()
        if (automationReady) return AccessibilityCheckResult.Success
        val settings = probe.enabledServicesSetting()
        if (settings.readFailed) return failure(REASON_SETTINGS_UNREADABLE, component, 0L, null, false, 0L, settings)
        if (!AccessibilityServiceUtils.settingsContainsComponent(settings.value, component)) {
            return failure(REASON_NOT_ENABLED, component, 0L, false, false, 0L, settings)
        }
        return failure(REASON_NOT_CONNECTED, component, 0L, true, false, 0L, settings)
    }

    /**
     * 非阻塞形态（线程风格）：立刻返回，后台线程等满 [timeoutMs] 后回调**同一个** [checkBlocking] 结果。
     * [executor] 可注入（单测传同步执行器，不建线程）。
     */
    fun checkAsync(
        probe: Probe,
        timeoutMs: Long = WAIT_FOR_ENABLED_TIMEOUT,
        executor: (Runnable) -> Unit = { task -> Thread(task, "AccessibilityCheck").apply { isDaemon = true }.start() },
        callback: (AccessibilityCheckResult) -> Unit,
    ) {
        executor(Runnable { callback(checkBlocking(probe, timeoutMs)) })
    }

    @JvmStatic
    @JvmOverloads
    fun checkBlocking(timeoutMs: Long = WAIT_FOR_ENABLED_TIMEOUT): AccessibilityCheckResult =
        checkBlocking(androidProbe(), timeoutMs)

    @JvmStatic
    fun checkNow(): AccessibilityCheckResult = checkNow(androidProbe())

    @JvmStatic
    @JvmOverloads
    fun checkAsync(timeoutMs: Long = WAIT_FOR_ENABLED_TIMEOUT, callback: Callback) {
        checkAsync(androidProbe(), timeoutMs) { result -> callback.onResult(result) }
    }

    /** 生产探针：只读系统设置 + 复用 `AccessibilityService.waitForEnabled`；不写任何状态。 */
    @JvmStatic
    fun androidProbe(): Probe = AndroidProbe

    private object AndroidProbe : Probe {
        private fun contextOrNull(): Context? = try { GlobalAppContext.get() } catch (error: Throwable) { null }

        override fun componentName(): String {
            val context = contextOrNull() ?: return "/" + SERVICE_CLASS_NAME
            return AccessibilityServiceTool.getAccessibilityServiceComponentName(context)
        }

        override fun enabledServicesSetting(): SettingsRead {
            val context = contextOrNull() ?: return SettingsRead.failed("no_context")
            // 读异常**不吞**：AccessibilityServiceTool.readEnabledAccessibilityServices 会原样抛出，这里区分两种空。
            return try {
                SettingsRead.read(AccessibilityServiceTool.readEnabledAccessibilityServices(context))
            } catch (error: Throwable) {
                SettingsRead.failed(error.javaClass.name)
            }
        }

        override fun isServiceConnected(): Boolean = AccessibilityService.instance != null

        override fun awaitServiceConnected(timeoutMs: Long): Boolean =
            AccessibilityService.waitForEnabled(if (timeoutMs <= 0L) 0L else timeoutMs)

        override fun uptimeMillis(): Long = SystemClock.uptimeMillis()

        override fun automationConnectionReady(): Boolean {
            val context = contextOrNull() ?: return false
            // :script 里它就是读同一个 instance；主进程里走 content://<pkg>.automation.state（有 250ms 缓存）。
            return try { AutomationConnectionState.isReady(context) } catch (error: Throwable) { false }
        }
    }

    // ------------------------------------------------------------------ 文案 / 建议 / 摘要（纯函数）

    private fun failure(
        reason: String,
        componentName: String,
        timeoutMs: Long,
        inSettings: Boolean?,
        automationReady: Boolean,
        elapsed: Long,
        settings: SettingsRead,
    ): AccessibilityCheckResult.Failure = AccessibilityCheckResult.Failure(
        reason = reason,
        hint = hintFor(reason, componentName, timeoutMs, settings),
        nextActions = nextActionsFor(reason, componentName),
        componentName = componentName,
        settingsContainsComponent = inSettings,
        settingsReadError = if (settings.readFailed) settings.errorClass else null,
        connected = false,
        automationConnectionReady = automationReady,
        elapsedMs = elapsed,
    )

    /** 可抄的下一步：每条都是能直接调用的入口（不是「请去设置里打开」这类空话）。 */
    @JvmStatic
    fun nextActionsFor(reason: String, componentName: String): List<String> {
        val common = listOf(
            "AccessibilityServiceTool.goToAccessibilitySetting()  // 引导用户到系统无障碍设置（需要用户手动确认时）",
            "AccessibilityServiceTool.enableAccessibilityService()  // 走既有快启链路（Shizuku / secure-settings / root）",
            "AccessibilityServiceTool.getFastEnableError()  // 快启失败时读具体错误文案",
            "AccessibilityCheck.checkBlocking(2000)  // 处理后再查一次（阻塞最多 2s）",
        )
        val specific = when (reason) {
            REASON_NOT_ENABLED -> listOf(
                "AccessibilityServiceTool.enableAccessibilityService()  // 系统设置里没有 " + componentName + "：直接走启用链路",
            )
            REASON_NOT_CONNECTED -> listOf(
                "AccessibilityCheck.checkBlocking(2000)  // 设置里已有 " + componentName + "，实例还没连上：阻塞再等最多 2s",
            )
            REASON_TIMEOUT -> listOf(
                "AccessibilityServiceTool.getFastEnableError()  // 等满超时仍没连上：先看快启错误",
                "AccessibilityServiceTool.goToAccessibilitySetting()  // 部分 ROM 需要用户在设置页手动点一下",
            )
            REASON_SETTINGS_UNREADABLE -> listOf(
                "AccessibilityCheck.checkBlocking(2000)  // 读异常时以「实例/自动化通路」为准，阻塞再查一次",
            )
            else -> emptyList()
        }
        return specific + common
    }

    @JvmStatic
    @JvmOverloads
    fun hintFor(
        reason: String,
        componentName: String,
        timeoutMs: Long,
        settings: SettingsRead? = null,
    ): String = when (reason) {
        REASON_NOT_ENABLED ->
            "系统设置 enabled_accessibility_services 里没有 " + componentName +
                "（读成功但值为空/不含该组件）：服务未启用（这不代表服务本身有问题）。"
        REASON_NOT_CONNECTED ->
            "系统设置里已有 " + componentName + "，但本进程没有连上的实例、自动化通路也没就绪；这是非阻塞检查（没有等待），" +
                "请用 checkBlocking() 等最多 2s 再判。注意我们的服务跑在 :script 进程：主进程里 AccessibilityService.instance 恒为 null，" +
                "那种情况应看 AutomationConnectionState.isReady（本结果的 automationConnectionReady 字段）。"
        REASON_TIMEOUT ->
            "已等待 " + timeoutMs + "ms，服务实例仍未连接：**不算成功**。常见原因：ROM 需要用户在设置页手动确认、服务被系统限制、" +
                "或无障碍开关被其它应用抢占。"
        REASON_SETTINGS_UNREADABLE ->
            "读取 settings get secure enabled_accessibility_services **抛异常**" +
                (settings?.errorClass?.let { "（" + it + "）" } ?: "") +
                "：这是「读不到」，不能据此判定未启用；请以服务实例/自动化通路是否就绪为准。" +
                "（注：读成功但值为空是 not_enabled，不是本码。）"
        else -> "无障碍服务未就绪（reason=" + reason + "）。"
    }

    /**
     * 只读证据（**不判定**）：把三条判据的原始取值打出来，便于脚本/logcat 核对「到底哪一条成立」。
     * 设置那条**分开打印**三种情况：`settings=<值>` / `settings=(空)` / `settings=(读取异常:<类名>)`。
     * 真机自检：`Packages.com.jdkshen.aijspro.tool.AccessibilityCheck.evidence()`。
     */
    @JvmStatic
    @JvmOverloads
    fun evidence(probe: Probe = androidProbe()): String {
        val component = probe.componentName()
        val settings = probe.enabledServicesSetting()
        val settingsText = when {
            settings.readFailed -> "settings=(读取异常:" + (settings.errorClass ?: "unknown") + ")"
            settings.value.isNullOrEmpty() || "null".equals(settings.value, ignoreCase = true) -> "settings=(空)"
            else -> "settings=" + settings.value
        }
        return "component=" + component +
            " connected=" + probe.isServiceConnected() +
            " automationConnectionReady=" + probe.automationConnectionReady() +
            " " + settingsText +
            " settingsReadFailed=" + settings.readFailed +
            " settingsValueAbsent=" + settings.valueAbsent +
            " settingsContainsComponent=" +
            (!settings.readFailed && AccessibilityServiceUtils.settingsContainsComponent(settings.value, component))
    }

    /** 单行摘要（纯字符串；日志/脚本/真机核对用）。 */
    @JvmStatic
    fun summary(result: AccessibilityCheckResult): String = when (result) {
        is AccessibilityCheckResult.Success -> "ok=true reason=" + REASON_CONNECTED
        is AccessibilityCheckResult.Failure -> "ok=false reason=" + result.reason +
            " settingsContainsComponent=" + result.settingsContainsComponent +
            " settingsReadError=" + result.settingsReadError +
            " connected=" + result.connected +
            " automationConnectionReady=" + result.automationConnectionReady +
            " elapsedMs=" + result.elapsedMs +
            " component=" + result.componentName + " hint=" + result.hint
    }
}