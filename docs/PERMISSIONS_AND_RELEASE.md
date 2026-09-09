# AI.js Pro 权限与发布基线

> 更新日期：2026-09-08。本文描述当前 `com.jdkshen.aijspro` 的发布约束，不包含任何签名密钥。

## 1. 发布构建

正式发布默认使用 Miuix compat 变体：

```powershell
$env:AIJSPRO_KEYSTORE='D:\private\aijspro.jks'
$env:AIJSPRO_STORE_PASSWORD='<store password>'
$env:AIJSPRO_KEY_ALIAS='<key alias>'
$env:AIJSPRO_KEY_PASSWORD='<key password>'
.\release.ps1 -Variant MiuixCompatRelease -SkipNative
```

四项签名变量缺少任意一项，或 keystore 路径无效时，release 构建会主动失败。密钥、口令和本机绝对路径不得写入 Git、Gradle 属性或日志。

开发安装使用：

```powershell
.\release.ps1 -Variant MiuixCompatDebug -SkipNative -Install
```

脚本会构建 arm64 APK、输出完整 SHA-256，并且只在恰好连接一台设备时执行安装。未传 `-SkipNative` 时只重建 QuickJS/NativeFrame 原生库；已移除的 ImGui 库不再属于发布链路。

当前为了保护反射、JNI 和脚本桥接，release 暂时保持 `minifyEnabled false`、`shrinkResources false`。只有补齐 release 回归和 keep rules 后才能开启 R8。

### GitHub Releases 更新约定

应用内更新已从失效的 `autojs.org` 切换到 GitHub Releases API：

```text
https://api.github.com/repos/Jdkshen/ai.js-pro/releases/latest
```

发布标签推荐写成 `v1.0.2+465`，其中 `1.0.2` 是 `versionName`，`465` 是 Android `versionCode`。也可以在 release notes 中单独写一行 `versionCode: 465`。APK 文件名必须包含 `compat` 或 `lite` 以及 ABI（例如 `arm64-v8a`），应用会优先选择与当前版本和设备 ABI 一致的文件。仓库尚无公开 release 或 GitHub 返回 404 时，客户端按“当前已是最新版”处理，不再产生旧站证书异常。

## 2. 当前 SDK 范围

| 项目 | 当前值 |
|---|---:|
| compileSdk | 35 |
| targetSdk | 28 |
| common minSdk | 21 |
| miuix minSdk | 26 |

`targetSdk 28` 保留了旧 Auto.js 文件、后台运行和通知行为，但会扩大现代 Android 的兼容与发布风险。提升 target SDK 必须分阶段处理存储、通知、前台服务、悬浮窗、包可见性和 APK 安装，不能只修改数字。

## 3. 权限审计

| 权限 | 当前用途 | 申请/授予方式 | 拒绝后的行为 | 发布前决定 |
|---|---|---|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | HTTP、更新、市场、MCP 局域网地址 | 普通权限 | 网络能力不可用 | 保留 |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | 旧脚本目录、导入导出、APK 打包 | 运行时/旧存储模型 | 文件功能降级 | 随 target SDK 迁移到分区存储/SAF |
| `SYSTEM_ALERT_WINDOW` | 悬浮按钮、悬浮控制台、布局分析 | 系统特殊设置 | 悬浮功能关闭 | 保留，按需引导 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 长脚本、定时任务、MCP 后台连接 | 系统特殊设置 | 后台可能被限制 | 不得首次启动强制申请 |
| `RECEIVE_BOOT_COMPLETED` | 定时任务及用户开启的启动行为 | 普通权限 | 重启后不自动恢复 | 保留，受用户设置控制 |
| `FOREGROUND_SERVICE` | 长任务、截图/引擎运行租约 | 普通权限 | 后台任务受限 | 提升 target SDK 时拆分服务类型 |
| `PACKAGE_USAGE_STATS` | 获取前台包名和 Activity | 使用情况访问设置 | 返回空/降级 | 保留，调用前检查 |
| `WRITE_SECURE_SETTINGS` | 小米无障碍快速开启并保留其他服务 | Shizuku、ADB 或 Root 一次授予 | 回退系统无障碍设置 | 仅 debug 保留；release 通过 Manifest overlay 明确移除 |
| `QUERY_ALL_PACKAGES` / MIUI `GET_INSTALLED_APPS` | 脚本 App API、包名与应用名查询 | Manifest/MIUI 运行时授权 | 只返回可见应用 | 发布渠道要求专项复核 |
| `ACCESS_FINE_LOCATION` | 用户脚本可选能力 | 脚本按需申请 | 对应脚本失败 | 不由主界面主动申请 |
| `RECORD_AUDIO` | 用户脚本录音能力 | 脚本按需申请 | 录音脚本失败 | 不由主界面主动申请 |
| `READ_PHONE_STATE` | 旧 `device` API 读取受保护设备标识 | 脚本按需申请 | 设备标识为空/受限 | 优先移除对唯一标识的依赖，再决定是否删除 |
| 安装/卸载桌面快捷方式 | 旧桌面快捷方式 | Launcher 能力 | 快捷方式不可用 | 迁移 ShortcutManager 后删除旧权限 |

## 4. 发布前权限验收

- 全新安装首次启动不集中弹出定位、录音或电话权限；
- 拒绝任意非核心权限时应用仍可进入脚本列表；
- 未授予 `WRITE_SECURE_SETTINGS` 时无障碍入口回退系统设置，不循环报错；
- release APK 不声明 `WRITE_SECURE_SETTINGS`；ADB/Shizuku 快速开启只作为开发设备能力；
- 快速开启无障碍不会覆盖 RustDesk 等其他已启用服务；
- 关闭悬浮窗、通知或电池优化授权后，页面显示真实状态；
- MCP 默认只监听 loopback，局域网模式始终需要令牌；
- release APK 使用正式签名，可覆盖安装上一正式版本；
- 每个发布 APK 记录版本、commit、ABI、大小、SHA-256、设备和回归结果。

## 5. 仍需人工决定

1. 正式分发渠道及其 target SDK、包可见性和隐私披露要求；
2. 是否继续向脚本开放电话状态/设备唯一标识；
3. 何时迁移旧外部存储模型；
4. 何时为反射/JNI 补齐 keep rules 并开启 R8；
5. 正式签名由谁保管、如何离线备份和轮换。
