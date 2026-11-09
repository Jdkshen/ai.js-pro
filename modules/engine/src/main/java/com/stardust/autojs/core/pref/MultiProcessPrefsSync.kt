package com.stardust.autojs.core.pref

/**
 * task-66：多进程偏好的**纯判定逻辑**（不依赖 Android，可直接单测）。
 *
 * 背景：白名单里的 key 仍可能有**只写 XML 的写者**（例如 DrawerFragment 的稳定模式开关、$settings），
 * 它们不经过本机制；如果只在「store 里没有」时播种，XML 的新值就永远进不了 store（引擎读到的会是旧值）。
 * 规则：**只要旧 XML 的指纹变了，就重播种白名单 key**（XML 是那些写者的真实来源；store 只负责跨进程新鲜度）。
 */
object MultiProcessPrefsSync {

    /** XML 是否比上次观察更新（null 表示此前没见过）。 */
    fun shouldReseed(legacyStamp: String?, lastSeenStamp: String?): Boolean {
        if (legacyStamp == null) return false
        return legacyStamp != lastSeenStamp
    }

    /** 旧 XML 的指纹（文件不存在返回 null）。 */
    fun stampOf(legacyFile: java.io.File?): String? {
        if (legacyFile == null || !legacyFile.isFile) return null
        return legacyFile.length().toString() + ":" + legacyFile.lastModified()
    }
}
