package com.jdkshen.aijspro.inrt.autojs

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

import com.stardust.app.GlobalAppContext
import com.jdkshen.aijspro.inrt.App
import com.stardust.autojs.core.util.ProcessShell

import java.util.Locale

/**
 * Created by Stardust on 2017/7/1.
 */

object AccessibilityServiceTool {

    /**
     * 本变体（`:inrt` 打包运行时）用的无障碍服务**类对象**。
     *
     * <p>为什么这里保留一份引用（而不是复用宿主 App 的 `com.jdkshen.aijspro.tool.AccessibilityCheck.SERVICE_CLASS_NAME`）：
     * `:inrt` 是**独立的 application 模块**（自己的 applicationId/进程/清单），依赖方向不允许它引用 `:app` 的常量；
     * 而且这里用的是**类对象**（`SelectToSpeakService::class.java`），类对象不可能与真实服务类漂移——
     * 真正需要守卫的是宿主侧那个**字符串**常量，已在 `AccessibilityServiceTool` 的静态断言里钉住
     * （`AccessibilityCheck.SERVICE_CLASS_NAME == sAccessibilityServiceClass.getName()`，不一致直接抛异常）。
     * 两边的权威来源是同一个类：`:engine` 清单里声明的 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`。
     */
    private val accessibilityServiceClass = com.google.android.accessibility.selecttospeak.SelectToSpeakService::class.java

    private val cmd = "enabled=$(settings get secure enabled_accessibility_services)\n" +
            "pkg=%s\n" +
            "if [[ \$enabled == *\$pkg* ]]\n" +
            "then\n" +
            "echo already_enabled\n" +
            "else\n" +
            "enabled=\$pkg:\$enabled\n" +
            "settings put secure enabled_accessibility_services \$enabled\n" +
            "fi"

    fun enableAccessibilityServiceByRoot(context: Context, accessibilityService: Class<out AccessibilityService>): Boolean {
        val serviceName = context.packageName + "/" + accessibilityService.name
        return try {
            TextUtils.isEmpty(ProcessShell.execCommand(String.format(Locale.getDefault(), cmd, serviceName), true).error)
        } catch (ignored: Exception) {
            false
        }

    }

    fun enableAccessibilityServiceByRootAndWaitFor(context: Context, timeOut: Long): Boolean {
        if (enableAccessibilityServiceByRoot(context, accessibilityServiceClass)) {
            return com.stardust.view.accessibility.AccessibilityService.waitForEnabled(timeOut)
        }
        return false
    }

    fun goToAccessibilitySetting() {
        GlobalAppContext.get().startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

}
