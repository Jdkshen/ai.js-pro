package com.stardust.view.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

import java.util.Locale


/**
 * Created by Stardust on 2017/1/26.
 */

object AccessibilityServiceUtils {

    fun goToAccessibilitySetting(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * 纯函数：系统设置 [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] 原文里是否含目标组件。
     *
     * <p>语义与 [isAccessibilityServiceEnabled] 完全一致：值按 ':' 分隔（系统写入格式
     * `pkg/full.Class:other/pkg.Svc`），逐项**大小写不敏感**比较；null / 空串 / "null" 一律 false。
     * 抽成纯函数有两个目的：① 「检查是否已启用」的判定可以被 JVM 单测直接覆盖（不依赖 Context/ComponentName）；
     * ② 检查与启用链路共用同一份解析，避免两处漂移。
     */
    fun settingsContainsComponent(enabledServicesSetting: String?, componentName: String?): Boolean {
        if (enabledServicesSetting.isNullOrEmpty() || "null".equals(enabledServicesSetting, ignoreCase = true)) return false
        if (componentName.isNullOrEmpty()) return false
        // 两侧都归一化后再比：期望值本身也可能是 `pkg/.Svc` 短形式（旧实现比较的就是归一化后的 ComponentName）。
        val expected = expandShortComponent(componentName.trim())
        return enabledServicesSetting.split(':').any { entry ->
            expandShortComponent(entry.trim()).equals(expected, ignoreCase = true)
        }
    }

    /** `pkg/.Svc` 形式的短组件名按包名展开成 `pkg/pkg.Svc`（与 ComponentName.unflattenFromString 同义）。 */
    private fun expandShortComponent(value: String): String {
        val slash = value.indexOf('/')
        if (slash <= 0 || slash + 1 >= value.length) return value
        val pkg = value.substring(0, slash)
        val cls = value.substring(slash + 1)
        return if (cls.startsWith(".")) pkg + "/" + pkg + cls else value
    }

    fun isAccessibilityServiceEnabled(context: Context, accessibilityService: Class<out AccessibilityService>): Boolean {
        val expectedComponentName = ComponentName(context, accessibilityService).flattenToString()
        val enabledServicesSetting = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (error: Exception) {
            // 读不到就按「未启用」处理（历史行为），调用方若要区分「读不到」请用 settingsContainsComponent 的原始值。
            null
        }
        return settingsContainsComponent(enabledServicesSetting, expectedComponentName)
    }


}
