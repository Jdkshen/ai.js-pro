package com.jdkshen.aijspro.autojs.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * task-131 钉子：OCR 插件骨架的两处**真机踩过**的坑不能再犯。
 *
 * ① settings.gradle 必须登记模块（否则 :ocr-plugin 不在构建图里）；
 * ② 清单必须有插件注册 meta-data（PluginRegistry.REGISTRY_KEY），否则宿主发现不了该插件；
 * ③ release 变体必须有签名（配了 AIJSPRO_* 用发布签名，没配回退 debug 签名），
 *    不能产出 -unsigned.apk。
 */
class OcrPluginSkeletonPinTest {
    private fun read(relative: String): String {
        val file = File(relative)
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun moduleIsRegisteredInSettings() {
        val settings = read("../../settings.gradle")
        assertTrue("settings.gradle 必须 include ':ocr-plugin'", settings.contains("':ocr-plugin'"))
    }

    @Test fun manifestRegistersThePlugin() {
        val manifest = read("../../plugins/ocr-plugin/src/main/AndroidManifest.xml")
        assertTrue("清单必须有插件注册键 org.autojs.plugin.sdk.registry",
            manifest.contains("org.autojs.plugin.sdk.registry"))
        assertTrue("注册值必须指向插件的 Registry 类",
            manifest.contains("org.autojs.autojspro.plugin.ocr.Registry"))
    }

    @Test fun releaseVariantAlwaysHasASigningConfig() {
        val gradle = read("../../plugins/ocr-plugin/build.gradle")
        assertTrue("release 必须有签名：配了 AIJSPRO_* 用发布签名，否则回退 debug",
            gradle.contains("signingConfig pluginSigningReady ? signingConfigs.release : signingConfigs.debug"))
        assertTrue("debug 变体也要用 debug 签名", gradle.contains("signingConfig signingConfigs.debug"))
        assertTrue("包名按契约", gradle.contains("org.autojs.autojspro.plugin.ocr"))
        assertTrue("版本取 project-versions.json 的键", gradle.contains("versions.ocrPluginVersionCode")
            && gradle.contains("versions.ocrPluginVersionName"))
        assertTrue("只随 arm64-v8a", gradle.contains("abiFilters 'arm64-v8a'"))
    }

    @Test fun payloadLockIsPresentAndCheckedByScript() {
        val lock = File("../../plugins/ocr-plugin/payload-sources.json")
        assertTrue("锁文件必须存在（脚本生成）", lock.isFile)
        val text = lock.readText()
        assertTrue("锁文件要记 sha256 与来源", text.contains("\"sha256\"") && text.contains("\"source\""))
        assertTrue("mlkit 来源要记 Gradle 坐标", text.contains("text-recognition-bundled-common:17.0.0"))
        assertTrue("libc++ 来源要记仓库内 AAR", text.contains("opencv-5.0.0-16kb-page-fix.aar"))
    }
}
