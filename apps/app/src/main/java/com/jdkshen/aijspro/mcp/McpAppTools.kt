package com.jdkshen.aijspro.mcp

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider
import com.stardust.util.ApkInstallActivity
import com.stardust.util.IntentUtil
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * MCP 应用管理纯函数（供 Lead 从 McpTools 接入）。
 *
 * 全部走 PackageManager / 系统 Intent，**不需要 root**：
 * - 列应用 / 应用详情：读取 PackageManager（依赖 manifest 的 QUERY_ALL_PACKAGES 才能看到全部应用）；
 * - 启动应用 / 打开设置 / 打开链接：普通 startActivity；
 * - 安装：只打开系统安装器（ApkInstallActivity 负责「安装未知应用」授权流程），
 *   **安装必须由用户在系统界面确认，绝不静默安装、不声称静默安装**；
 * - 卸载：只打开系统卸载确认界面，**绝不静默卸载**。
 *
 * 返回统一 JsonObject：成功 {success:true,...}，失败 {success:false,error:"明确中文原因"}。
 * 包过滤 / 分页 / 包名 / URL / APK 路径校验是纯 JVM 逻辑，见 McpAppToolsTest。
 */
internal object McpAppTools {

    /** 应用列表分页：默认 100，上限 200（与 McpTools 其它分页保持一致）。 */
    const val DEFAULT_LIST_LIMIT = 100
    const val MAX_LIST_LIMIT = 200

    private const val MAX_QUERY_CHARS = 128
    private const val MAX_URL_CHARS = 4096
    private const val MAX_PACKAGE_NAME_CHARS = 255

    /** Android 包名：至少一段，段间用 '.' 分隔，每段以字母开头（允许字母/数字/下划线）。 */
    private val packageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$")

    /** 与 Android 解耦的应用条目：过滤 / 排序 / 分页 / JSON 全部可纯 JVM 单测。 */
    data class AppSummary(
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val isSystem: Boolean,
        val enabled: Boolean,
        val targetSdk: Int,
    )

    data class AppPage(
        val items: List<AppSummary>,
        val offset: Int,
        val nextOffset: Int,
        val total: Int,
        val hasMore: Boolean,
    )

    // ------------------------------------------------------------------
    // MCP 工具入口（args 即 tools/call 的 arguments；不需要 root）
    // ------------------------------------------------------------------

    /** app_list：列出已安装应用（label/version/isSystem/enabled），支持 query 过滤与分页。 */
    fun packageList(context: Context, args: JsonObject): JsonObject = runTool("app_list") {
        val query = args.stringOrNull("query")?.trim()?.takeIf { it.isNotEmpty() }
        if (query != null && query.length > MAX_QUERY_CHARS) {
            throw IllegalArgumentException("query 过长（上限 $MAX_QUERY_CHARS 字符）")
        }
        val includeSystem = args.booleanOr("includeSystem", true)
        val all = loadInstalledApps(context.packageManager)
        val page = paginateApps(
            filterApps(all, query, includeSystem),
            args.intOr("offset", 0),
            args.intOr("limit", DEFAULT_LIST_LIMIT),
        )
        JsonObject().apply {
            addProperty("success", true)
            add("items", JsonArray().apply { page.items.forEach { add(appSummaryJson(it)) } })
            addProperty("count", page.items.size)
            addProperty("total", page.total)
            addProperty("offset", page.offset)
            addProperty("nextOffset", page.nextOffset)
            addProperty("hasMore", page.hasMore)
            addProperty("includeSystem", includeSystem)
            addProperty("requiresRoot", false)
            query?.let { addProperty("query", it) }
            if (page.total == 0 && query != null && !includeSystem) {
                addProperty("hint", "没有匹配的第三方应用；如需搜索系统应用，请加 includeSystem=true")
            }
        }
    }

    /** app_info：单个应用的版本、安装时间、SDK、启动入口与目录等详情。 */
    fun packageInfo(context: Context, args: JsonObject): JsonObject = runTool("app_info") {
        val packageName = normalizePackageName(
            args.stringOrNull("packageName") ?: throw IllegalArgumentException("缺少参数 packageName"),
        )
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(packageName, 0)
        } catch (notFound: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("找不到应用（未安装或不可见）：$packageName")
        }
        val appInfo = info.applicationInfo
            ?: throw IllegalStateException("系统没有返回应用信息：$packageName")
        JsonObject().apply {
            addProperty("success", true)
            addProperty("packageName", packageName)
            addProperty("label", appLabel(pm, appInfo))
            addProperty("versionName", info.versionName ?: "")
            addProperty("versionCode", versionCodeOf(info))
            addProperty("firstInstallTime", info.firstInstallTime)
            addProperty("lastUpdateTime", info.lastUpdateTime)
            addProperty("targetSdk", appInfo.targetSdkVersion)
            addProperty("minSdk", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 0)
            addProperty("isSystem", isSystemApp(appInfo))
            addProperty("enabled", appInfo.enabled)
            addProperty("launchable", launchIntentOf(pm, packageName) != null)
            addProperty("sourceDir", appInfo.sourceDir ?: "")
            addProperty("dataDir", appInfo.dataDir ?: "")
            addProperty("uid", appInfo.uid)
            addProperty("isCurrentApp", packageName == context.packageName)
            addProperty("requiresRoot", false)
        }
    }

    /**
     * app_launch：按包名启动应用；可选 activity（Activity 类名，允许 ".MainActivity" 简写）
     * 精确指定入口，不传则用 PackageManager 的默认启动入口。
     */
    fun launch(context: Context, args: JsonObject): JsonObject = runTool("app_launch") {
        val packageName = normalizePackageName(
            args.stringOrNull("packageName") ?: throw IllegalArgumentException("缺少参数 packageName"),
        )
        val activityClass = args.stringOrNull("activity")?.trim()?.takeIf { it.isNotEmpty() }
        val pm = context.packageManager
        val appInfo = requireInstalled(pm, packageName)
        val intent = if (activityClass != null) {
            explicitLaunchIntent(pm, packageName, activityClass)
        } else {
            launchIntentOf(pm, packageName)
                ?: throw IllegalStateException("应用没有可启动的界面（可能已禁用或没有启动 Activity）：$packageName")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startOrThrow(context, intent, "启动应用")
        JsonObject().apply {
            addProperty("success", true)
            addProperty("launched", true)
            addProperty("packageName", packageName)
            addProperty("label", appLabel(pm, appInfo))
            addProperty("component", intent.component?.flattenToShortString() ?: "")
            addProperty("requiresRoot", false)
            addProperty("note", "已通过系统 startActivity 启动应用，不需要 root")
        }
    }

    /** app_open_settings：不带 packageName 打开系统设置首页；带 packageName 打开该应用的详情页。 */
    fun openSettings(context: Context, args: JsonObject): JsonObject = runTool("app_open_settings") {
        val rawPackage = args.stringOrNull("packageName")?.trim().orEmpty()
        if (rawPackage.isEmpty()) {
            val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startOrThrow(context, intent, "打开系统设置")
            return@runTool JsonObject().apply {
                addProperty("success", true)
                addProperty("target", "system")
                addProperty("action", Settings.ACTION_SETTINGS)
                addProperty("requiresRoot", false)
            }
        }
        val packageName = normalizePackageName(rawPackage)
        val appInfo = requireInstalled(context.packageManager, packageName)
        val opened = try {
            IntentUtil.goToAppDetailSettings(context, packageName)
        } catch (error: Exception) {
            false
        }
        if (!opened) throw IllegalStateException("系统没有可用的「应用详情」设置页：$packageName")
        JsonObject().apply {
            addProperty("success", true)
            addProperty("target", "app")
            addProperty("packageName", packageName)
            addProperty("label", appLabel(context.packageManager, appInfo))
            addProperty("action", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            addProperty("requiresRoot", false)
        }
    }

    /** app_open_url：只允许 http/https，交给系统浏览器打开（不下载、不执行本地文件）。 */
    fun openUrl(context: Context, args: JsonObject): JsonObject = runTool("app_open_url") {
        val url = normalizeHttpUrl(
            args.stringOrNull("url") ?: throw IllegalArgumentException("缺少参数 url"),
        )
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startOrThrow(context, intent, "打开链接")
        JsonObject().apply {
            addProperty("success", true)
            addProperty("url", url)
            addProperty("action", Intent.ACTION_VIEW)
            addProperty("requiresRoot", false)
            addProperty("note", "已交给系统浏览器/应用打开链接；仅允许 http/https，不会执行本地文件")
        }
    }

    /**
     * app_install：打开系统 APK 安装器（不是静默安装）。
     *
     * 先把 APK 交给 ApkInstallActivity：Android 8+ 若未允许「安装未知应用」会先跳到该授权页，
     * 允许后返回自动打开系统安装器；安装/取消必须由用户在系统界面确认。
     */
    fun openApkInstaller(context: Context, args: JsonObject): JsonObject = runTool("app_install") {
        val apk = requireApkFile(
            args.stringOrNull("path") ?: throw IllegalArgumentException("缺少参数 path"),
        )
        val pm = context.packageManager
        val archive = try {
            pm.getPackageArchiveInfo(apk.absolutePath, 0)
        } catch (error: RuntimeException) {
            null
        }
        if (archive == null || archive.packageName.isNullOrEmpty()) {
            throw IllegalArgumentException("不是有效的 APK 文件：${apk.absolutePath}")
        }
        val unknownSourcesAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || pm.canRequestPackageInstalls()
        try {
            ApkInstallActivity.start(context, apk.absolutePath, AppFileProvider.AUTHORITY)
        } catch (error: ActivityNotFoundException) {
            throw IllegalStateException("系统没有可用的 APK 安装界面：${apk.absolutePath}")
        } catch (error: SecurityException) {
            throw IllegalStateException("系统拒绝了打开 APK 安装界面的请求：${describe(error)}")
        }
        JsonObject().apply {
            addProperty("success", true)
            addProperty("installerOpened", true)
            addProperty("requiresUserConfirmation", true)
            addProperty("silentInstall", false)
            addProperty("apkPath", apk.absolutePath)
            addProperty("sizeBytes", apk.length())
            addProperty("packageName", archive.packageName)
            addProperty("versionName", archive.versionName ?: "")
            addProperty("versionCode", versionCodeOf(archive))
            addProperty("unknownSourcesAllowed", unknownSourcesAllowed)
            addProperty("requiresRoot", false)
            addProperty("note", if (unknownSourcesAllowed) {
                "已打开系统安装器；是否安装必须由用户在系统界面确认，本工具不会静默安装"
            } else {
                "系统尚未允许本应用安装未知来源应用：安装流程会先跳到「安装未知应用」设置页，允许后返回继续；" +
                    "最终仍需用户在系统界面确认，不会静默安装"
            })
        }
    }

    /** app_uninstall：打开系统卸载确认界面（不是静默卸载）。 */
    fun uninstallIntent(context: Context, args: JsonObject): JsonObject = runTool("app_uninstall") {
        val packageName = normalizePackageName(
            args.stringOrNull("packageName") ?: throw IllegalArgumentException("缺少参数 packageName"),
        )
        if (packageName == context.packageName) {
            throw IllegalArgumentException("不能通过此工具卸载 AI.js Pro 自身：$packageName")
        }
        val pm = context.packageManager
        val appInfo = requireInstalled(pm, packageName)
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startOrThrow(context, intent, "打开卸载确认界面")
        JsonObject().apply {
            addProperty("success", true)
            addProperty("uninstallUiOpened", true)
            addProperty("requiresUserConfirmation", true)
            addProperty("silentUninstall", false)
            addProperty("packageName", packageName)
            addProperty("label", appLabel(pm, appInfo))
            addProperty("action", Intent.ACTION_DELETE)
            addProperty("requiresRoot", false)
            addProperty("note", "已打开系统卸载确认界面；是否卸载必须由用户确认，本工具不会静默卸载")
        }
    }

    /**
     * 工具名 → 函数的路由（供 McpTools.call 的 else 分支一次接入）。
     * 未识别的名字返回 null，由调用方继续报「未知工具」。
     *
     * 规范名（与 McpToolCatalog 新增项一致）：
     * app_list / app_info / app_launch / app_open_settings / app_open_url / apk_install / apk_uninstall。
     * 其余别名（list_apps、install_apk、uninstall_app 等）仅为兼容调用方习惯。
     */
    fun handle(context: Context, name: String, args: JsonObject): JsonObject? = when (name) {
        "app_list", "list_apps", "package_list" -> packageList(context, args)
        "app_info", "get_app_info", "package_info" -> packageInfo(context, args)
        "app_launch", "launch_app" -> launch(context, args)
        "app_open_settings", "app_settings", "open_app_settings" -> openSettings(context, args)
        "app_open_url", "app_url", "open_url" -> openUrl(context, args)
        "apk_install", "app_install", "app_install_apk", "install_apk" -> openApkInstaller(context, args)
        "apk_uninstall", "app_uninstall", "uninstall_app" -> uninstallIntent(context, args)
        else -> null
    }

    // ------------------------------------------------------------------
    // 纯 JVM 逻辑（包名 / URL / APK 路径校验，过滤、排序、分页，JSON 转换）
    // ------------------------------------------------------------------

    /** 校验并 trim 包名；非法时抛 IllegalArgumentException（中文原因）。 */
    fun normalizePackageName(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) throw IllegalArgumentException("packageName 不能为空")
        if (value.length > MAX_PACKAGE_NAME_CHARS) {
            throw IllegalArgumentException("packageName 过长（上限 $MAX_PACKAGE_NAME_CHARS 字符）")
        }
        if (!packageNamePattern.matches(value)) {
            throw IllegalArgumentException("packageName 不是合法的应用包名：$raw")
        }
        return value
    }

    /** 校验 http/https URL；纯 JDK URI 解析，不访问网络。 */
    fun normalizeHttpUrl(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) throw IllegalArgumentException("url 不能为空")
        if (value.length > MAX_URL_CHARS) {
            throw IllegalArgumentException("url 过长（上限 $MAX_URL_CHARS 字符）")
        }
        val uri = try {
            URI(value)
        } catch (error: URISyntaxException) {
            throw IllegalArgumentException("url 格式无效：$raw")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("url 只支持 http/https：$raw")
        }
        if (uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("url 缺少主机名：$raw")
        }
        return value
    }

    /** 校验安装包路径：绝对路径、.apk 结尾、普通文件、可读且非空。 */
    fun requireApkFile(raw: String): File {
        val value = raw.trim()
        if (value.isEmpty()) throw IllegalArgumentException("apk path 不能为空")
        if (!value.endsWith(".apk", ignoreCase = true)) {
            throw IllegalArgumentException("apk path 必须以 .apk 结尾：$raw")
        }
        val file = File(value)
        if (!file.isAbsolute) throw IllegalArgumentException("apk path 必须是绝对路径：$raw")
        val canonical = try {
            file.canonicalFile
        } catch (error: Exception) {
            throw IllegalArgumentException("apk path 无效：$raw")
        }
        if (!canonical.isFile) throw IllegalArgumentException("APK 文件不存在：$value")
        if (!canonical.canRead()) throw IllegalArgumentException("APK 文件不可读：$value")
        if (canonical.length() <= 0L) throw IllegalArgumentException("APK 文件为空：$value")
        return canonical
    }

    /** 按 query（包名或 label，忽略大小写）与系统应用开关过滤，并按 label（忽略大小写）+ 包名排序。 */
    fun filterApps(all: List<AppSummary>, query: String?, includeSystem: Boolean = true): List<AppSummary> {
        val needle = query?.trim().orEmpty()
        return all.asSequence()
            .filter { includeSystem || !it.isSystem }
            .filter {
                needle.isEmpty() ||
                    it.packageName.contains(needle, ignoreCase = true) ||
                    it.label.contains(needle, ignoreCase = true)
            }
            .sortedWith(compareBy<AppSummary> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
            .toList()
    }

    /** 分页：offset 负数归 0、越界钳到总数；limit 钳到 1..MAX_LIST_LIMIT。 */
    fun paginateApps(all: List<AppSummary>, offset: Int, limit: Int): AppPage {
        val safeOffset = offset.coerceAtLeast(0).coerceAtMost(all.size)
        val safeLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        val end = (safeOffset + safeLimit).coerceAtMost(all.size)
        return AppPage(all.subList(safeOffset, end), safeOffset, end, all.size, end < all.size)
    }

    /** AppSummary → MCP 返回 JSON（稳定字段名，纯 JVM 可测）。 */
    fun appSummaryJson(app: AppSummary): JsonObject = JsonObject().apply {
        addProperty("packageName", app.packageName)
        addProperty("label", app.label)
        addProperty("versionName", app.versionName)
        addProperty("versionCode", app.versionCode)
        addProperty("isSystem", app.isSystem)
        addProperty("enabled", app.enabled)
        addProperty("targetSdk", app.targetSdk)
    }

    // ------------------------------------------------------------------
    // Android 细节（PackageManager / Intent）
    // ------------------------------------------------------------------

    private fun loadInstalledApps(pm: PackageManager): List<AppSummary> {
        val packages: List<PackageInfo> = try {
            pm.getInstalledPackages(0)
        } catch (error: RuntimeException) {
            throw IllegalStateException("读取已安装应用失败：${describe(error)}")
        }
        return packages.mapNotNull { info ->
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            AppSummary(
                packageName = info.packageName,
                label = appLabel(pm, appInfo),
                versionName = info.versionName ?: "",
                versionCode = versionCodeOf(info),
                isSystem = isSystemApp(appInfo),
                enabled = appInfo.enabled,
                targetSdk = appInfo.targetSdkVersion,
            )
        }
    }

    private fun appLabel(pm: PackageManager, appInfo: ApplicationInfo): String = try {
        appInfo.loadLabel(pm).toString().ifBlank { appInfo.packageName ?: "" }
    } catch (error: RuntimeException) {
        appInfo.packageName ?: ""
    }

    private fun requireInstalled(pm: PackageManager, packageName: String): ApplicationInfo = try {
        pm.getApplicationInfo(packageName, 0)
    } catch (notFound: PackageManager.NameNotFoundException) {
        throw IllegalArgumentException("找不到应用（未安装或不可见）：$packageName")
    }

    private fun launchIntentOf(pm: PackageManager, packageName: String): Intent? = try {
        pm.getLaunchIntentForPackage(packageName)
    } catch (error: RuntimeException) {
        null
    }

    /** 显式 activity 启动：".MainActivity" 简写按包名补全；Activity 不存在时给中文错误。 */
    private fun explicitLaunchIntent(
        pm: PackageManager,
        packageName: String,
        activity: String,
    ): Intent {
        val className = if (activity.startsWith(".")) packageName + activity else activity
        val component = ComponentName(packageName, className)
        try {
            pm.getActivityInfo(component, 0)
        } catch (notFound: PackageManager.NameNotFoundException) {
            throw IllegalArgumentException("找不到 Activity：$className（包 $packageName）")
        }
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
    }

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean =
        (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun startOrThrow(context: Context, intent: Intent, operation: String) {
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            throw IllegalStateException("系统没有可处理「$operation」的应用：${intent.action ?: ""}")
        } catch (error: SecurityException) {
            throw IllegalStateException("系统拒绝了「$operation」的权限请求：${describe(error)}")
        }
    }

    private inline fun runTool(tool: String, block: () -> JsonObject): JsonObject = try {
        block()
    } catch (error: Exception) {
        fail("$tool 失败：${describe(error)}")
    }

    private fun fail(message: String): JsonObject = JsonObject().apply {
        addProperty("success", false)
        addProperty("error", message)
    }

    private fun describe(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isEmpty()) error.javaClass.simpleName else message
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.booleanOr(name: String, fallback: Boolean): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: fallback

    private fun JsonObject.intOr(name: String, fallback: Int): Int =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: fallback
}
