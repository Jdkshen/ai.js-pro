# vivo / iQOO Android 16 实况通知测试

本包配套 AI.js Pro dev-1271-vivo-live-test，arm64-v8a。它走标准Android实况接口，未接入vivo专有云推送/原子通知SDK；原子岛画面需对方真机验证。没有“vivo已上岛”的验收结论。

## 操作

1. 安装配套APK，先手动结束顶部彩色浮窗演示。三个JS只使用定时器，均不创建浮窗、不播放音乐、不点击其它应用，180秒后自然结束。
2. 先只运行Rhino测试；回到桌面等待3秒，观察原子岛/状态栏胶囊/通知栏。不要只在AI.js Pro自己的状态页判断岛的可见性。
3. 点运行通知进入“脚本运行状态”，在“实况样式”里可选自动、文字状态、运行进度。vivo自动尝试进度样式；API不存在或构造失败会保留文字通知，并记录styleFallbackReason。进度条表示正在运行，不是完成百分比。
4. 在脚本仍运行时复制诊断。需要 runningCount>0、actualNotification.active=true；promoted=true只表示系统提升，不保证原子岛画面。
5. 分别对比文字/进度，再返回桌面观察。若单引擎正常，可一起运行三个JS观察3项；也可用“停止当前/停止全部”或等待自然结束核对清理。
6. 反馈：原子岛/状态栏的桌面截图，以及运行期间的完整诊断。若仍没有原子岛，说明需继续检查OriginOS展示策略/系统版本/厂商专有通道，不能仅凭小米focusProtocol=0判不支持。

## 新诊断

schemaVersion=3；notificationFamily=vivo；xiaomiInterfaceApplicable=false。小米islandSupported/focusProtocol保留为兼容字段，但只描述小米接口。

platform.xiaomiRegistration.authorizationStatus=not_applicable；vivo不需要配置小米App ID。

runningChannelExists=false时，runningChannelAllowed=null，表示尚未创建，不能误报已开启。

presentation.state区分idle、clearing、service_not_active、notification_query_error、delivery_error、waiting_for_notification、promotion_not_observed、promoted等；islandPresentation=not_verified不自动推断原子岛已经显示。

actualNotification.notificationTemplate记录文字或ProgressStyle；stylePreference/selectedStyle/styleFallbackReason可用于对照。
