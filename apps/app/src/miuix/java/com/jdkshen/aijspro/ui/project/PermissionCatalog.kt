package com.jdkshen.aijspro.ui.project

/**
 * Permission catalogue for the packaging page.
 *
 * [TEMPLATE_DEFAULTS] mirrors the `uses-permission` entries of the bundled inrt template
 * (`apps/app/src/main/assets/template.apk`), so a freshly opened packaging page reproduces
 * the previous behaviour exactly: those entries start checked and unchecking one removes it
 * from the packaged manifest (see [com.stardust.autojs.apkbuilder.ManifestEditor]).
 */
object PermissionCatalog {

    data class Entry(val name: String, val label: String, val summary: String)

    data class Group(val title: String, val entries: List<Entry>)

    /** Permissions declared by the template manifest; checked by default. */
    val TEMPLATE_DEFAULTS: List<String> = listOf(
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_EXTERNAL_STORAGE",
        "com.android.launcher.permission.INSTALL_SHORTCUT",
        "com.android.launcher.permission.UNINSTALL_SHORTCUT",
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_PHONE_STATE",
        "android.permission.VIBRATE",
        "android.permission.WAKE_LOCK",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.WRITE_SETTINGS",
        "android.permission.WRITE_SECURE_SETTINGS"
    )

    /**
     * 打开页面时的初始勾选：保持模板自带的权限集（= Auto.js Pro 产物的 17 条）不变。
     *
     * 历史的教训：曾在这里提供过“Pro 推荐（ 59 条）”一键预设，结果用户一点就产出 61 条
     * 权限的包（含短信/联系人/通话/相机），被 MIUI 安装器报 RiskWare。Pro 界面上确实勾了
     * 59 条，但**它自己产出的包只有 17 条**——界面选择不等于落盘结果。
     * 因此现在只保留“模板默认”，需要更多权限就逐条勾。
     */
    val DEFAULT_DECLARED: List<String> = TEMPLATE_DEFAULTS

    /**
     * 打包产物在启动时自动申请的运行时权限（“启动时自动申请权限” 页的默认值）。
     * 与 Auto.js Pro 一致，默认只申请存储权限；用户可自行增删。
     */
    val DEFAULT_REQUEST: List<String> = listOf(
        "android.permission.WRITE_EXTERNAL_STORAGE"
    )

    private fun entry(name: String, label: String, summary: String) = Entry(name, label, summary)

    /**
     * 平台补充权限（见 [PlatformPermissions]）按授权级别成组展示：标签取权限名
     * （去掉 `x.permission.` 前缀）以便与官方文档对照，摘要说明能否被普通应用拿到。
     * 必须声明在 [GROUPS] 之前，否则 object 初始化顺序会让它还是 null。
     */
    private fun tail(title: String, summary: String, names: List<String>) =
        Group(title, names.map { Entry(it, it.substringAfterLast("permission."), summary) })

    private val PLATFORM_GROUPS: List<Group> = listOf(
        tail("更多危险权限（需用户授权）", "危险权限 · 安装后需在系统弹窗中授权",
            PlatformPermissions.DANGEROUS),
        tail("更多普通权限（安装即授予）", "普通权限 · 安装时自动授予",
            PlatformPermissions.NORMAL),
        tail("高级权限（需 root / adb / 系统签名）", "签名权限 · 需 root、adb 或系统签名才能获得",
            PlatformPermissions.SIGNATURE)
    )

    val GROUPS: List<Group> = listOf(
        Group("存储与文件", listOf(
            entry("android.permission.READ_EXTERNAL_STORAGE", "读取存储", "读取手机中的文件"),
            entry("android.permission.WRITE_EXTERNAL_STORAGE", "写入存储", "保存截图、日志和脚本数据"),
            entry("android.permission.MANAGE_EXTERNAL_STORAGE", "管理所有文件", "Android 11+ 访问整块存储"),
            entry("android.permission.READ_MEDIA_IMAGES", "读取图片", "Android 13+ 读取相册图片"),
            entry("android.permission.READ_MEDIA_VIDEO", "读取视频", "Android 13+ 读取相册视频"),
            entry("android.permission.READ_MEDIA_AUDIO", "读取音频", "Android 13+ 读取音频文件")
        )),
        Group("网络", listOf(
            entry("android.permission.INTERNET", "网络访问", "http、下载、上传等联网能力"),
            entry("android.permission.ACCESS_NETWORK_STATE", "网络状态", "判断当前是否有网络"),
            entry("android.permission.ACCESS_WIFI_STATE", "WiFi 状态", "读取 WiFi 连接信息"),
            entry("android.permission.CHANGE_WIFI_STATE", "修改 WiFi 状态", "开关或切换 WiFi"),
            entry("android.permission.CHANGE_NETWORK_STATE", "修改网络状态", "切换网络连接")
        )),
        Group("定位", listOf(
            entry("android.permission.ACCESS_FINE_LOCATION", "精确定位", "GPS 级别的位置信息"),
            entry("android.permission.ACCESS_COARSE_LOCATION", "粗略定位", "基站 / WiFi 定位"),
            entry("android.permission.ACCESS_BACKGROUND_LOCATION", "后台定位", "应用退到后台仍可定位"),
            entry("android.permission.ACCESS_MEDIA_LOCATION", "照片位置信息", "读取照片中的地理位置")
        )),
        Group("通话与短信", listOf(
            entry("android.permission.READ_PHONE_STATE", "读取手机状态", "获取设备号与通话状态"),
            entry("android.permission.READ_PHONE_NUMBERS", "读取手机号码", "读取本机号码"),
            entry("android.permission.CALL_PHONE", "拨打电话", "直接拨出电话"),
            entry("android.permission.ANSWER_PHONE_CALLS", "接听电话", "自动接听来电"),
            entry("android.permission.READ_SMS", "读取短信", "读取短信内容"),
            entry("android.permission.SEND_SMS", "发送短信", "发送短信"),
            entry("android.permission.RECEIVE_SMS", "接收短信", "监听新短信")
        )),
        Group("相机与麦克风", listOf(
            entry("android.permission.CAMERA", "相机", "拍照与扫码"),
            entry("android.permission.RECORD_AUDIO", "录音", "录制声音"),
            entry("android.permission.MODIFY_AUDIO_SETTINGS", "修改音频设置", "调整音量与音频通路")
        )),
        Group("联系人与日历", listOf(
            entry("android.permission.READ_CONTACTS", "读取联系人", "读取通讯录"),
            entry("android.permission.WRITE_CONTACTS", "修改联系人", "新增或编辑通讯录"),
            entry("android.permission.GET_ACCOUNTS", "获取账户", "读取设备上的账户列表"),
            entry("android.permission.READ_CALENDAR", "读取日历", "读取日程"),
            entry("android.permission.WRITE_CALENDAR", "写入日历", "创建日程")
        )),
        Group("传感器与健康", listOf(
            entry("android.permission.BODY_SENSORS", "身体传感器", "心率等身体数据"),
            entry("android.permission.ACTIVITY_RECOGNITION", "活动识别", "识别走路、跑步等状态"),
            entry("android.permission.HIGH_SAMPLING_RATE_SENSORS", "高频传感器", "更高频率读取传感器")
        )),
        Group("系统与后台", listOf(
            entry("android.permission.SYSTEM_ALERT_WINDOW", "悬浮窗", "在其他应用上层显示控件"),
            entry("android.permission.WAKE_LOCK", "保持唤醒", "脚本运行时阻止息屏"),
            entry("android.permission.VIBRATE", "振动", "调用振动马达"),
            entry("android.permission.WRITE_SETTINGS", "修改系统设置", "调整亮度、音量等设置"),
            entry("android.permission.WRITE_SECURE_SETTINGS", "修改安全设置", "需 root 或 adb 授权"),
            entry("android.permission.REQUEST_INSTALL_PACKAGES", "安装应用", "安装下载的 apk"),
            entry("android.permission.REQUEST_DELETE_PACKAGES", "请求卸载应用", "卸载其他应用"),
            entry("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "忽略电池优化", "减少后台被杀"),
            entry("android.permission.RECEIVE_BOOT_COMPLETED", "开机自启", "开机后自动运行"),
            entry("android.permission.FOREGROUND_SERVICE", "前台服务", "常驻通知保持运行"),
            entry("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION", "前台服务投屏", "投屏/截图类前台服务"),
            entry("android.permission.POST_NOTIFICATIONS", "发送通知", "Android 13+ 显示通知"),
            entry("android.permission.PACKAGE_USAGE_STATS", "使用情况访问", "读取应用使用时长"),
            entry("android.permission.QUERY_ALL_PACKAGES", "查询所有应用", "列出已安装应用"),
            entry("android.permission.KILL_BACKGROUND_PROCESSES", "结束后台进程", "杀掉其他应用进程"),
            entry("android.permission.GET_TASKS", "获取运行任务", "读取最近任务列表"),
            entry("android.permission.REORDER_TASKS", "重排任务", "把应用移到前台"),
            entry("android.permission.SCHEDULE_EXACT_ALARM", "精确闹钟", "定时唤醒脚本"),
            entry("android.permission.EXPAND_STATUS_BAR", "展开状态栏", "下拉或收起状态栏"),
            entry("android.permission.DISABLE_KEYGUARD", "解除锁屏", "运行时临时取消锁屏"),
            entry("android.permission.SET_WALLPAPER", "设置壁纸", "更换桌面壁纸"),
            entry("android.permission.FLASHLIGHT", "手电筒", "控制闪光灯"),
            entry("android.permission.READ_LOGS", "读取日志", "读取系统日志"),
            entry("android.permission.DUMP", "系统转储", "读取系统内部状态"),
            entry("android.permission.BLUETOOTH", "蓝牙", "传统蓝牙接口"),
            entry("android.permission.BLUETOOTH_CONNECT", "蓝牙连接", "Android 12+ 连接设备"),
            entry("android.permission.BLUETOOTH_SCAN", "蓝牙扫描", "Android 12+ 扫描设备"),
            entry("android.permission.NFC", "NFC", "读写 NFC 标签")
        )),
        Group("桌面快捷方式", listOf(
            entry("com.android.launcher.permission.INSTALL_SHORTCUT", "创建快捷方式", "在桌面添加脚本快捷方式"),
            entry("com.android.launcher.permission.UNINSTALL_SHORTCUT", "删除快捷方式", "移除桌面快捷方式")
        ))
    ) + PLATFORM_GROUPS

    val ALL: List<Entry> = GROUPS.flatMap { it.entries }

    /** Permissions present in the template but not offered in the catalogue above. */
    val UNLISTED_DEFAULTS: List<String> =
        TEMPLATE_DEFAULTS.filter { name -> ALL.none { it.name == name } }
}
