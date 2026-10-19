package com.jdkshen.aijspro.ui.project

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo

/**
 * 从系统读取权限的保护级别与官方（已本地化）描述，
 * 用于在打包页上与 Auto.js Pro 一样展示「危险 / 特权 / 签名」徽章和系统解释文案。
 * 第三方或系统未安装的权限拿不到信息时返回 null，界面回退到目录里的自写说明。
 */
internal class PermissionDetails(context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val cache = HashMap<String, Info?>()

    data class Info(val level: Level, val description: String?)

    enum class Level { NORMAL, DANGEROUS, PRIVILEGED, SIGNATURE }

    fun of(permission: String): Info? = cache.getOrPut(permission) {
        try {
            val info = packageManager.getPermissionInfo(permission, 0)
            Info(levelOf(info.protectionLevel), info.loadDescription(packageManager)?.toString())
        } catch (e: Exception) {
            // 未在本机声明的权限（如第三方/新版权限）无法查询。
            null
        }
    }

    private fun levelOf(protectionLevel: Int): Level {
        val privileged = protectionLevel and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0
        return when (protectionLevel and 0x3) {
            PermissionInfo.PROTECTION_DANGEROUS -> Level.DANGEROUS
            PermissionInfo.PROTECTION_SIGNATURE -> if (privileged) Level.PRIVILEGED else Level.SIGNATURE
            0 -> if (privileged) Level.PRIVILEGED else Level.NORMAL
            else -> Level.NORMAL
        }
    }
}

/**
 * Packaged-app permission catalogue supplied by the Android platform.
 *
 * Generated from `platform/frameworks/base/core/res/AndroidManifest.xml` and kept here so
 * the packaging page can offer the same breadth as Auto.js Pro without hand-maintaining
 * hundreds of entries:
 *  - [DANGEROUS]: `android:protectionLevel="dangerous"` → user must grant at runtime.
 *  - [NORMAL]: `android:protectionLevel="normal"` → granted at install time.
 *  - [SIGNATURE]: hand-picked system permissions that only root/adb/system-signed builds
 *    can obtain, useful for advanced scripts.
 *
 * Entries the curated [PermissionCatalog.GROUPS] already describe are not repeated here.
 * Labels fall back to the platform permission name so users can match official docs;
 * they start unchecked.
 */
internal object PlatformPermissions {

    val DANGEROUS: List<String> = listOf(
        "android.permission.ACCEPT_HANDOVER",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.BODY_SENSORS_BACKGROUND",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.RANGING",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_CELL_BROADCASTS",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.RECEIVE_MMS",
        "android.permission.RECEIVE_WAP_PUSH",
        "android.permission.USE_SIP",
        "android.permission.UWB_RANGING",
        "android.permission.WRITE_CALL_LOG",
        "com.android.voicemail.permission.ADD_VOICEMAIL"
    )

    val NORMAL: List<String> = listOf(
        "android.permission.ACCESS_HIDDEN_PROFILES",
        "android.permission.ACCESS_LOCATION_EXTRA_COMMANDS",
        "android.permission.ACCESS_NOTIFICATION_POLICY",
        "android.permission.APPLY_PICTURE_PROFILE",
        "android.permission.AUTHENTICATE_ACCOUNTS",
        "android.permission.BLUETOOTH_ADMIN",
        "android.permission.BROADCAST_STICKY",
        "android.permission.CALL_COMPANION_APP",
        "android.permission.CHANGE_WIFI_MULTICAST_STATE",
        "android.permission.CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS",
        "android.permission.CREDENTIAL_MANAGER_SET_ALLOWED_PROVIDERS",
        "android.permission.CREDENTIAL_MANAGER_SET_ORIGIN",
        "android.permission.DELIVER_COMPANION_MESSAGES",
        "android.permission.DETECT_SCREEN_CAPTURE",
        "android.permission.DETECT_SCREEN_RECORDING",
        "android.permission.ENFORCE_UPDATE_OWNERSHIP",
        "android.permission.FOREGROUND_SERVICE_CAMERA",
        "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.FOREGROUND_SERVICE_FILE_MANAGEMENT",
        "android.permission.FOREGROUND_SERVICE_HEALTH",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.FOREGROUND_SERVICE_PHONE_CALL",
        "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
        "android.permission.GET_PACKAGE_SIZE",
        "android.permission.HIDE_OVERLAY_WINDOWS",
        "android.permission.MANAGE_ACCOUNTS",
        "android.permission.MANAGE_OWN_CALLS",
        "android.permission.NFC_PREFERRED_PAYMENT_INFO",
        "android.permission.NFC_TRANSACTION_EVENT",
        "android.permission.PERSISTENT_ACTIVITY",
        "android.permission.QUERY_ADVANCED_PROTECTION_MODE",
        "android.permission.READ_BASIC_PHONE_STATE",
        "android.permission.READ_COLOR_ZONES",
        "android.permission.READ_INSTALL_SESSIONS",
        "android.permission.READ_NEARBY_STREAMING_POLICY",
        "android.permission.READ_PROFILE",
        "android.permission.READ_SOCIAL_STREAM",
        "android.permission.READ_SYNC_SETTINGS",
        "android.permission.READ_SYNC_STATS",
        "android.permission.READ_USER_DICTIONARY",
        "android.permission.REQUEST_COMPANION_PROFILE_GLASSES",
        "android.permission.REQUEST_COMPANION_PROFILE_WATCH",
        "android.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND",
        "android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND",
        "android.permission.REQUEST_COMPANION_USE_DATA_IN_BACKGROUND",
        "android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE",
        "android.permission.REQUEST_PASSWORD_COMPLEXITY",
        "android.permission.RESTART_PACKAGES",
        "android.permission.RUN_USER_INITIATED_JOBS",
        "android.permission.SET_WALLPAPER_HINTS",
        "android.permission.SUBSCRIBED_FEEDS_READ",
        "android.permission.SUBSCRIBED_FEEDS_WRITE",
        "android.permission.TRANSMIT_IR",
        "android.permission.TV_IMPLICIT_ENTER_PIP",
        "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION",
        "android.permission.USE_BIOMETRIC",
        "android.permission.USE_CREDENTIALS",
        "android.permission.USE_EXACT_ALARM",
        "android.permission.USE_FINGERPRINT",
        "android.permission.USE_FULL_SCREEN_INTENT",
        "android.permission.WRITE_PROFILE",
        "android.permission.WRITE_SMS",
        "android.permission.WRITE_SOCIAL_STREAM",
        "android.permission.WRITE_SYNC_SETTINGS",
        "android.permission.WRITE_USER_DICTIONARY",
        "com.android.alarm.permission.SET_ALARM",
        "com.android.browser.permission.READ_HISTORY_BOOKMARKS",
        "com.android.browser.permission.WRITE_HISTORY_BOOKMARKS"
    )

    val SIGNATURE: List<String> = listOf(
        "android.permission.CHANGE_CONFIGURATION",
        "android.permission.MOUNT_UNMOUNT_FILESYSTEMS",
        "android.permission.CHANGE_COMPONENT_ENABLED_STATE",
        "android.permission.SET_ALWAYS_FINISH",
        "android.permission.SET_PROCESS_LIMIT",
        "android.permission.SET_ANIMATION_SCALE",
        "android.permission.FORCE_STOP_PACKAGES",
        "android.permission.INSTALL_PACKAGES",
        "android.permission.DELETE_PACKAGES",
        "android.permission.GRANT_RUNTIME_PERMISSIONS",
        "android.permission.REVOKE_RUNTIME_PERMISSIONS",
        "android.permission.CAPTURE_AUDIO_OUTPUT",
        "android.permission.CAPTURE_VIDEO_OUTPUT",
        "android.permission.MODIFY_PHONE_STATE",
        "android.permission.READ_PRIVILEGED_PHONE_STATE",
        "android.permission.READ_PRECISE_PHONE_STATE",
        "android.permission.STATUS_BAR",
        "android.permission.SET_TIME",
        "android.permission.SET_TIME_ZONE",
        "android.permission.CHANGE_APP_IDLE_STATE",
        "android.permission.GET_APP_OPS_STATS",
        "android.permission.UPDATE_DEVICE_STATS",
        "android.permission.READ_NETWORK_USAGE_HISTORY",
        "android.permission.WRITE_MEDIA_STORAGE",
        "android.permission.WRITE_APN_SETTINGS",
        "android.permission.MODIFY_AUDIO_ROUTING",
        "android.permission.INTERACT_ACROSS_USERS",
        "android.permission.INTERACT_ACROSS_USERS_FULL",
        "android.permission.MANAGE_USERS",
        "android.permission.CREATE_USERS",
        "android.permission.HARDWARE_TEST",
        "android.permission.DEVICE_POWER",
        "android.permission.REBOOT",
        "android.permission.SHUTDOWN",
        "android.permission.RECOVERY",
        "android.permission.MASTER_CLEAR",
        "android.permission.SET_WALLPAPER_COMPONENT",
        "android.permission.SET_SCREEN_COMPATIBILITY",
        "android.permission.OVERRIDE_WIFI_CONFIG",
        "android.permission.MANAGE_NETWORK_POLICY",
        "android.permission.ACCESS_SURFACE_FLINGER",
        "android.permission.READ_FRAME_BUFFER",
        "android.permission.MEDIA_CONTENT_CONTROL",
        "android.permission.BLUETOOTH_PRIVILEGED",
        "android.permission.LOCAL_MAC_ADDRESS",
        "android.permission.MANAGE_DEVICE_ADMINS",
        "android.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS",
        "android.permission.FREEZE_SCREEN",
        "android.permission.KILL_UID",
        "android.permission.OBSERVE_APP_USAGE",
        "android.permission.UPDATE_APP_OPS_STATS",
        "android.permission.GET_DETAILED_TASKS",
        "android.permission.MANAGE_ACTIVITY_TASKS",
        "android.permission.START_ANY_ACTIVITY",
        "android.permission.REAL_GET_TASKS",
        "android.permission.SET_ORIENTATION"
    )
}
