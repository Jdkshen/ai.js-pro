# AI.js Pro 权限与发布基线

> 更新日期：2026-09-09。本文描述当前 `com.jdkshen.aijspro` 的发布约束，不包含任何签名密钥。

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

“直接下载”会把 APK 放进应用私有缓存 `cache/updates`，随后校验 GitHub 返回的 SHA-256（新 Release 有 digest 时）、APK 格式、包名、版本号及与已安装版本相同的签名。校验通过后才交给系统安装器；Android 8 及以上若尚未允许“安装未知应用”，应用会打开本应用的授权页，用户授权返回后自动继续安装。发布 APK 必须始终使用同一正式签名，否则客户端会在系统安装前拒绝更新包。

推送与 `project-versions.json` 匹配的标签（当前为 `v1.0.2+465`）会触发 GitHub Actions 发布任务。仓库 Actions Secrets 必须配置 `AIJSPRO_KEYSTORE_BASE64`、`AIJSPRO_STORE_PASSWORD`、`AIJSPRO_KEY_ALIAS`、`AIJSPRO_KEY_PASSWORD`；前者是 keystore 文件的 Base64 内容，另外三项与本地发布变量含义一致。Secrets 缺失时发布任务会明确失败，不会生成无签名或 debug 签名的 Release。

## 1.1 脚本 APK 的保护等级（`encryptLevel`）

打包页「特性」分组里的 **脚本加密** 开关，以及工程 `project.json` 里的 `encryptLevel` 字段，控制产物中脚本的存放形式。判定逻辑集中在 `com.stardust.autojs.project.ScriptProtection`（打包端与打包出的 App 共用同一份语义）：

| 等级 | 含义 | 产物内 `assets/project/main.js` |
|---:|---|---|
| 0 | 不加密 | 原始脚本文本（无文件头） |
| 1 | 加密（默认） | `77 01 17 7F 12 12` + 2 字节 flags + AES/CBC/PKCS5 密文 |
| 2 | 编译 + 加密 | 内容同样是「文件头 + 密文」，但载荷是**编译产物**：Rhino 工程是生成的 class 字节（`CompiledScriptPayload`：类名 + 类字节），QuickJS 工程是 **QuickJS 字节码**（`JS_WriteObject(..., JS_WRITE_OBJ_BYTECODE)` 产物） |

取值规则：

1. 打包页显式选了开关 → 以页面为准（开 = 1，关 = 0）；
2. 页面没指定（例如由 `AppConfig.fromProjectConfig` 或外部调用打包）→ 以工程 `project.json` 为准；
3. 工程里没有该字段 → 默认 1，保持历史行为（以前是无条件加密）。

`encryptLevel` 会被写回产物内的 `assets/project/project.json`，所以产物自带的配置与实际行为始终一致。密钥仍由 `key = MD5(packageName + versionName + mainScriptFile)`、`vec = MD5(buildId + name)[0,16)` 派生，打包端与运行端（`AssetsProjectLauncher.initKey`）必须同时改。

文件头的 2 字节 flags 里，低字节留给执行模式等既有标记，**高字节是载荷类型**（`0` 文本 / `1` Rhino 编译类 / `2` QuickJS 字节码，见 `EncryptedScriptFileHeader`）。
运行时按载荷类型分发：文本走原来的 `StringScriptSource`；编译类交给 `AndroidClassLoader`（dx → DexClassLoader）加载后在引擎作用域里 `exec`。

### 等级 2（编译）的注意事项

- **两种引擎都支持**：Rhino → class 字节（运行端用 `AndroidClassLoader`（dx → DexClassLoader）加载执行）；
  QuickJS → 字节码（运行端 `JS_ReadObject` + `JS_EvalFunction`，原生层新增 `compileToBytecode` / `evaluateBytecode`）。
  打包端按产物的 `engine` 字段自动选择，无需手工切换。
- **两端必须同源**：Rhino 编译端与运行端用同一份 `modules/rhino-language/libs/rhino-1.7.7.2.jar`；
  QuickJS 字节码与 quickjs 版本严格绑定（`JS_ReadObject` 自校验），打包端与模板必须来自同一份仓库构建。
- 编译时都关掉了源码信息：Rhino 侧 `setGeneratingSource(false)`，QuickJS 侧字节码本身不带源码文本。
  产物里搜不到脚本源码（字符串常量仍会在，因为脚本运行时要用）。
- **改动 `:inrt` 或原生库后必须重新生成模板**：`.\gradlew.bat :inrt:assembleRelease` 会把运行端产物复制成
  `apps/app/src/main/assets/template.apk`。打包出来的 App 用的是这份模板里的运行端代码（含 `.so`），
  不重建模板就会出现「打包端已是新逻辑、产物运行时还是旧的」这种半新半旧状态。
- 运行端解密已统一到 `EncryptedScripts`：包入口脚本按载荷类型分发（文本 / Rhino class / QuickJS 字节码），
  Rhino 与 QuickJS 引擎共用同一套逻辑（此前 QuickJS 的打包应用会把密文当源码解析）。

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
| `REQUEST_INSTALL_PACKAGES` | 应用内下载更新及安装项目构建 APK | Android 8+“安装未知应用”单独授权 | 保留下载包并结束安装流程，可再次点击安装 | 保留；只在用户主动安装时引导 |
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
