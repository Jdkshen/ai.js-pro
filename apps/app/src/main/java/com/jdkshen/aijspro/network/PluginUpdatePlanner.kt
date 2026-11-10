package com.jdkshen.aijspro.network

// task-131/132：给定清单 + 本机状态，算出「每个插件现在该干什么」。纯逻辑，可 JVM 单测。
object PluginUpdatePlanner {
    data class DeviceState(
        val appVersionCode: Int,
        val deviceAbis: List<String>,
        val installedVersionCode: Map<String, Int>,
        val installedPackages: Set<String>
    )

    data class Decision(
        val entry: PluginManifest.Entry,
        val reason: PluginPlanReason,
        val detail: String
    ) {
        val actionable: Boolean
            get() = reason == PluginPlanReason.INSTALL || reason == PluginPlanReason.UPDATE
    }

    @JvmStatic
    fun plan(entries: List<PluginManifest.Entry>, state: DeviceState): List<Decision> =
        entries.map { entry -> decide(entry, state) }

    @JvmStatic
    fun decide(entry: PluginManifest.Entry, state: DeviceState): Decision {
        if (!entry.isDownloadable()) {
            return Decision(entry, PluginPlanReason.PENDING_ARTIFACT, "清单标 pending：该插件 APK 尚未构建，暂不可下载")
        }
        if (entry.minAppVersion > state.appVersionCode) {
            return Decision(
                entry, PluginPlanReason.APP_TOO_OLD,
                "需要主程序 versionCode >= " + entry.minAppVersion + "（当前 " + state.appVersionCode + "）：请先升级主程序"
            )
        }
        if (entry.abis.isNotEmpty() && state.deviceAbis.none { it in entry.abis }) {
            return Decision(
                entry, PluginPlanReason.ABI_UNSUPPORTED,
                "设备 ABI " + state.deviceAbis + " 与插件 " + entry.abis + " 不匹配：不下白流量"
            )
        }
        val installed = state.installedVersionCode[entry.id]
            ?: if (state.installedPackages.contains(entry.packageName)) 0 else null
        if (installed == null) {
            return Decision(entry, PluginPlanReason.INSTALL, "未安装：可安装 " + entry.version)
        }
        return if (entry.versionCode > installed) {
            Decision(entry, PluginPlanReason.UPDATE, "已装 versionCode " + installed + " ⇒ 可更新到 " + entry.versionCode)
        } else {
            Decision(entry, PluginPlanReason.UP_TO_DATE, "已装 versionCode " + installed + "，不低于清单 " + entry.versionCode)
        }
    }
}
