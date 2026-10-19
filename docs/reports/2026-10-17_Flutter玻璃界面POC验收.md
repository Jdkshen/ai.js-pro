# Flutter 玻璃界面 POC 验收（2026-10-17）

> 依据：`工程\output\ai-js-pro-refactor-20261017\统一Flutter界面-可行性评估-20261017.md` §9
> 基座：当前工作树（commit `b6316586` + 未提交的 Node24/示例集改动），包名 `com.jdkshen.aijspro`
> 结论：**POC 通过**——玻璃界面在 ai-js-pro 里跑通，首页渲染、真实脚本目录、主题与 Miuix 一致三项全部达成，
> 启动/内存/体积基线已记录。按评估的 A 路线（混合统一）继续；生产引擎建议切现代线。

## 1. 环境与设备

| 项 | 值 |
| --- | --- |
| 基座 | `C:\Users\18101\Documents\ai-js-pro`（miuixCompatDebug，arm64-v8a） |
| Flutter | 3.3.4（`C:\Android\flutter-3.3.4`，Dart 2.18；快线） |
| 玻璃界面源码 | `…\AutoJsPro-本地工程-20261008\工程\output\autojs-pro-flutter-rebuild\lib`（19 个 .dart，v181 同源） |
| 桥参考 | 同目录 `android-bridge\org\autojs\autojs\glass\FlutterBridge.java` |
| 真机 | K40 `cccc62c7`：LineageOS / Android 16（API 36），1080×2400，440 dpi |
| 构建 | JDK17（`tools/jdk17.ps1`）+ AGP 8.6.1 / Gradle 8.9 |

## 2. 集成方式（不碰生产代码）

- **开关**：`apps/app/build.gradle` 新增 `-PflutterPoc=true`（默认关闭，配置期校验 payload 存在）。
  打开后只作用于 **debug 源集**：
  - java：`src/flutterpoc/java`；
  - 清单：`src/flutterpoc/AndroidManifest.xml`（只加一个 exported 调试 Activity）；
  - 资产/原生库：`output/flutter-poc/payload/assets`、`payload/jniLibs`；
  - 依赖：`debugImplementation` Flutter 3.3.4 embedding（classes-only jar）
    与 `androidx.window:window-java:1.3.0`（FlutterView 构造时无条件创建
    `WindowInfoRepositoryCallbackAdapterWrapper`，缺它会在首帧崩溃）。
- **宿主**：`apps/app/src/flutterpoc/java/…/GlassFlutterActivity.java` 继承 `io.flutter.embedding.android.FlutterActivity`，
  在 `configureFlutterEngine()` 里注册唯一通道 `BasicMessageChannel<String>('autojs.local.bridge')`。
- **桥**：`PocBridge.java` 按重建线契约（JSON over StringCodec，`{ok,data|error}`）实现：
  - `bootstrap`：`scripts`=`Pref.getScriptDirPath()`、`samples`=`files/sample`、
    `storagePermission`、`dark`=`AppThemeRepository.get(context).isDark()`；
  - `list`：返回 `[{name,path,relativePath,directory,project,size,modified}]`，目录限定在脚本目录 / 示例目录内，
    隐藏目录（.开头、.autojs-glass-*、.flutter-*）按 `showHidden` 过滤；
  - `theme`：写 `AppThemeRepository.setThemeMode(dark?2:1)`（**与 Miuix 同一主题事实源**）；
  - 启动路径上的 `runtimePreferences`/`nativePackagingRequest`/`pendingAction`/`browserPreferences`/`drawerPreferences`/`importRecords`
    返回空；其余方法按契约返回 `{ok:false,error:"POC 尚未实现桥方法：…"}`，界面降级显示不崩溃。
- **payload 生成**（产物在 `output/`，被 git 忽略）：
  - `output/flutter-poc/build-aot.ps1`：把重建线 `lib/assets/third_party/pubspec` 暂存到 ASCII 路径后
    `flutter pub get --offline` + `flutter assemble … android_aot_bundle_release_android-arm64`；
  - `output/flutter-poc/prepare_payload.py`：从 v181 APK 取 `assets/flutter_assets`（84 文件）和 `libflutter.so`，
    用新构建的 `app.so`/资产覆盖，并从 SDK 的 `android-arm64-release/flutter.jar` 抽 classes-only `flutter_embedding_3.3.4.jar`。

## 3. 引擎产物一致性（可复现）

| 产物 | 大小 | SHA-256 |
| --- | --- | --- |
| `libapp.so`（新构建） | 5,817,240 B | `9e49637f21223a8a076548dd9155f075f8d6db7c2aad4d19f6749b03811a617e` |
| v181 APK 内 `libapp.so` | 5,817,240 B | 同上 → **逐字节相同** |
| `libflutter.so`（SDK `flutter.jar` 内） | 9,936,928 B | `dc44088c17d0583ffbd294524b58f92007c2703f40d0922f877ceeeef50163c3` |
| `flutter_assets`（v181 基线 + 新构建 overlay） | 84 文件 / 4.07 MiB | — |

⇒ 用当前重建线的同一份 `lib` + 本地 3.3.4 SDK 能**逐字节复现 v181 的 AOT 产物**，构建路径可信。

## 4. 验收结果（评估 §9.4 清单）

| # | 验收项 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 玻璃首页正常渲染，无红屏/黑屏 | ✅ | `output/flutter-poc/poc2.png`（深色首页）、`glass-light.png`（浅色抽屉） |
| 2 | 首页列出 AI.js Pro 真实脚本目录 | ✅ | 列出 `/sdcard/脚本` 真实条目（00-codex-ui、dsh 手机客户端、APK输出…）；示例 tab 列出 `files/sample` 的 QuickJS/Rhino |
| 3 | 主题色与 Miuix 设置一致 | ✅ | 深色时两侧一致；在玻璃抽屉关深色主题后 prefs 变 `key_night_mode=false`/`follow_system_theme=false`，Miuix 主界面同步变浅色（`miuix-light.png`） |
| 4 | 记录冷启动、内存、APK 体积基线 | ✅ | 见 §5 |

### 4.1 额外验证

- 抽屉可打开（`glass-drawer.png`）：服务状态、深色主题开关、设置入口都在；
  未实现的 `deviceState`/`mcpState`/`keepScreenOn` 按设计显示
  `Bad state: POC 尚未实现桥方法：…`，**不崩溃、不卡死**。
- 浅色主题下界面完整（`glass-light.png`）。
- 玻璃折射：着色器加载成功；首帧快照有一次内建重试（每次启动仅 1 条
  `素材快照失败，回落原有外观`），之后未再报错，导航镜片外观正常。

## 5. 基线数据（2026-10-17 K40 实测）

### 5.1 APK 体积（arm64-v8a、miuixCompatDebug、`-PdevPluginUsbDiscovery=false`）

| 构建 | 字节 | 说明 |
| --- | --- | --- |
| 带 Flutter POC | 269,424,760 B（256.94 MiB） | 本 POC |
| 同构建去 POC | 250,834,446 B（239.21 MiB） | 紧邻对照（干净重建） |
| **增量** | **+18,590,314 B（+17.73 MiB）** | 86 个条目，压缩后 17.47 MiB（libflutter 9.7 + libapp 5.6 + 资产/字体 ~1.7 + dex） |

口径与评估 §8 的「约 +15 MB」一致（略高：多带了 4.07 MiB 的 84 个 flutter_assets，
其中基线图片约 1.4 MB；另 debug 包 dex 无优化）。

### 5.2 启动（debug 包，含 App.onCreate 全部初始化）

| 场景 | 实测 | 说明 |
| --- | --- | --- |
| 冷启动 Activity START→首帧 | **835 ms** | `ActivityTaskManager: Displayed … +835ms`；进程由通知监听/前台服务在 START 前 22 ms 被系统拉起，等效进程启动→首帧 ≈ 0.85 s |
| 热启动（进程在、Activity 冷） | 133–289 ms | 多次冷/热交替实测 |
| 对照：Miuix MainActivity | 987 ms | 同设备同口径 |
| 首帧后 GL/Flutter 就绪痕迹 | 着色器 0.49 s、桥注册 0.70 s | 见 `logcat-cold2.txt` 时间线 |

### 5.3 内存（PSS，debug 包，进程刚进入前台后）

| 场景 | TOTAL PSS |
| --- | --- |
| Flutter 玻璃界面（首页） | **230,233 KB（≈225 MiB）** |
| Miuix 主界面（对照，无 Flutter 引擎） | 193,771 KB（≈189 MiB） |
| **增量** | **+36,462 KB（+35.6 MiB）**（其中 EGL mtrack 51.4 MB） |

与评估 §8 的「内存 +几十 MB」一致；debug 包 dex/LeakCanary 等会放大绝对值，release 数字待后续测。

## 6. 已知问题与 POC 边界

1. **16 KB 页兼容性系统弹窗**：Android 16 对可调试包弹出兼容性提示，列出未 16 KB 对齐的
   `libflutter.so`、`libapp.so` 以及既有 `libopencv_*`、`libquickjs*`、`libmlkit_*` 等
   （见 `poc1.png`）。不影响本次运行；**生产切 Flutter 3.47.7（16 KB 对齐）时，既有 native 库也要一起处理**。
2. **未实现的桥**：仅按 §9 做 3 条桥（bootstrap/list/theme），抽屉里的
   `deviceState`/`mcpState`/`keepScreenOn` 等按设计降级；终端、编辑器、打包等页面不可用。
3. **只验证 arm64-v8a**：x86/armeabi-v7a 变体只随包了 flutter_assets（无 flutter .so），未装机验证。
4. **安装版本号**：POC 用仓库的 versionCode 465 装机，曾把设备上的 dev-900 降级；
   验收完成后已**回滚到 dev-900-node24**，并把主题偏好恢复为 `follow_system_theme=true`。
   如需再看 POC，用归档包（§7）重装，看完同样可回滚。
5. **折射效果**：首帧快照空断言会触发一次内建重试，属预期；若在低端机连续 8 次失败才会永久回落。
6. **系统对话框干扰**：首次启动/重新安装后 Android 16 的兼容性对话框会盖在界面上，点「确定/不再显示」即可。

## 7. 产物与复现

产物（均被 git 忽略，位于 `output/flutter-poc/`）：

| 路径 | 内容 |
| --- | --- |
| `build-aot.ps1` / `prepare_payload.py` | AOT + payload 生成（可重跑） |
| `aot/` | 本次 AOT 输出（`arm64-v8a/app.so` + `flutter_assets`） |
| `payload/` | Gradle 直接引用的 payload（embedding jar + jniLibs + assets） |
| `payload-report.json` | payload 各文件大小 / SHA-256 |
| `poc-apk/app-miuix-compat-arm64-v8a-debug-poc.apk` | 本次 POC 装机包（含 POC） |
| `poc1.png` / `poc2.png` / `glass-*.png` / `miuix-*.png` | 验收截图 |
| `logcat-*.txt` / `meminfo-*.txt` / `gradle-build*.log` | 日志与内存/构建证据 |

复现：

```powershell
# 1) 生成 AOT + payload（约 30 s）
powershell -ExecutionPolicy Bypass -File output/flutter-poc/build-aot.ps1

# 2) 构建 POC 包（arm64；本机需 -PdevPluginUsbDiscovery=false 才能与真 Auto.js Pro 共存）
. .\tools\jdk17.ps1
.\gradlew.bat :app:assembleMiuixCompatDebug -PflutterPoc=true -PdevPluginUsbDiscovery=false --console=plain

# 3) 安装并拉起（保留数据）
adb -s cccc62c7 install -r -d apps/app/build/outputs/apk/miuixCompat/debug/app-miuix-compat-arm64-v8a-debug.apk
adb -s cccc62c7 shell am start -n com.jdkshen.aijspro/com.jdkshen.aijspro.flutterpoc.GlassFlutterActivity
```

## 8. 下一步（按评估 A 路线）

1. **桥移植分批**（评估 §7 的 95 个分支）：
   第一批「启动/主题/偏好 8 + 文件/工作区/存储 18 + 运行/任务/日志 11」；
   第二批「MCP/电脑连接 6 + 设备/悬浮窗/Shizuku/无障碍 8 + 插件/示例/资源 9」；
   第三批（高风险）「项目打包/签名 14 + 编辑器 12 + LSP 5」，逐页验收。
2. **入口切换**：Splash → Flutter 玻璃首页；复杂页走原生兜底（复用重建线 `nativeRoute` 机制），
   Miuix 留作回退直到双机回归通过。
3. **工程化**：payload 生成/校验纳入构建脚本或独立 Gradle 模块；生产切 Flutter 3.47.7
   现代线（Dart 3 代码已迁移完，需重建 SDK/引擎并解决 16 KB 对齐与既有 native 库）。
4. **待拍板**（评估 §10）：引擎线（POC 用 3.3.4 抢时间；生产建议 3.47.7）、
   Miuix 保留时长、是否开始批量桥移植。

## 9. dev-902 发布与更新通道实测（2026-10-17 16:48）

按仓库既定发布流程把带 POC 的调试包发到自建更新源，并用手机自己的「检查更新」流程升级验证：

| 步骤 | 结果 |
| --- | --- |
| 构建 | 临时把 `project-versions.json` 补丁为 902/dev-902，`gradlew :app:assembleMiuixCompatDebug -PflutterPoc=true -PdevPluginUsbDiscovery=false`，构建后恢复 JSON |
| 发布 | `tools/publish.ps1 -SkipBuild -AllowMultiDevice -DeviceId cccc62c7`（前置校验/发布后 SHA 与手机 curl 验证全过），产物 `app-miuix-compat-arm64-v8a-dev-902-no-usb-debug.apk`，269,424,732 B，sha256 `643d93472b015c6661de4cd21e9fc5aed914d487fa480ead6ef58fe148db66af` |
| 手机发现更新 | 打开 App 自动弹「新版本 dev-902」，带本版说明（桌面新增 POC 入口），295 条历史可展开 |
| 下载 | App 内「直接下载」走局域网更新源，约 3 秒到 66%，总计数秒（269 MB） |
| 安装 | 系统包安装器「要更新此应用吗？」→「更新」，lastUpdateTime 16:49:12，数据保留 |
| 升级后 | `versionCode=902 / versionName=dev-902`；桌面出现两个入口：`SplashActivity`（Miuix）与 `GlassFlutterActivity`（Flutter 玻璃 POC，label「AI.js Pro · Flutter POC」） |
| POC 入口 | `am start -n com.jdkshen.aijspro/.flutterpoc.GlassFlutterActivity` → TotalTime 243 ms，玻璃首页正常、脚本目录为真实数据 |

说明：
- 主入口**仍是 Miuix**，本次发布只是把 POC 以独立桌面入口带进包内，便于真机点开查看；不带 `-PflutterPoc` 的常规构建仍是单入口、小 17.7 MiB。
- 发布时手机原为 dev-901（本会话更早的 POC 测试曾临时降级到 900，随后被更新通道自动升回 901），本次升级到 dev-902。
- 想回到「只有 Miuix」的包：再走一次不带 `-PflutterPoc` 的发布即可（桌面图标随之消失，数据不受影响）。

## 10. 主入口切换到 Flutter（dev-903 ~ dev-905）

### 10.1 实现

- **入口开关**：新增 `R.bool.flutter_main_entry`（主 `res` 默认 `false`，POC 调试源集
  `src/flutterpoc/res` 覆盖为 `true`）。`SplashActivity.enterNextActivity()` 在开关为 true 时
  用**类名字符串**跳 `GlassFlutterActivity`（生产源集不依赖只在 POC 源集存在的类），失败时兜底回
  `MainActivity`。开关为 false 时行为与原来逐行一致。
- **桌面入口**：POC 调试清单里给 `SplashActivity` 的 LAUNCHER intent-filter 加 `priority=10`，
  让 `getLaunchIntentForPackage`（安装器「打开」、通知等）默认解析到 Splash → Flutter；
  给 `MainActivity` 增加 LAUNCHER 入口并改 label 为「AI.js Pro（Miuix 回退）」；
  `GlassFlutterActivity` 不再自己当桌面图标（避免三个入口）。
- **原生页兜底桥**（Flutter 抽屉/顶栏点击后跳原生，避免 POC 阶段“点了没反应”）：
  `openLog`→`ui.log.LogActivity`、`engineSettings`→`MiuixSettingsActivity`、
  `openTerminal`→`EmbeddedTerminalActivity`（工作目录=脚本目录）、`openEditor`(工作区按钮)→原生
  `MainActivity`、`exitApp`（二次确认退出）、`keepScreenOn`、`openUrl`、`deviceState`
  （无障碍/悬浮窗/存储真实状态）、`mcpState`、`accessibilitySettings`/`overlaySettings`/`storagePermission`、
  `floatingWindow`（只回报授权）、`drawerPreferences`/`browserPreferences`（落盘到 `flutter-glass` 偏好）、
  `runtimePreferences`（日志条数/日志上限/备份数/控制台读取/稳定模式 + 编辑器等布尔项，与 Flutter 设置页契约一致）。

### 10.2 发布迭代（每次都用 App 内检查更新通道升级）

| 版本 | 内容 | 结果 |
| --- | --- | --- |
| dev-903 | 首次主入口切换（Splash → Flutter，Miuix 桌面第二图标） | 升级成功；但有两个 LAUNCHER 时安装器「打开」/通知会解析到 ResolverActivity |
| dev-904 | 给 Splash 的 LAUNCHER filter 加 `priority=10`，默认解析固定到 Flutter | 安装器「打开」→ 直接进玻璃首页 |
| dev-905 | 补 `runtimePreferences` 桥 | 设置页（原「运行设置格式异常」）完整可用，偏好实时读写 |

### 10.3 真机验收（dev-905，K40）

| 场景 | 结果 |
| --- | --- |
| 桌面「AI.js Pro」图标 / 安装器「打开」 | → Flutter 玻璃首页（脚本目录为真实数据） |
| 桌面「AI.js Pro（Miuix 回退）」图标 | → 原生 Miuix 主界面 |
| 顶栏「工作区」按钮 | → 原生 `MainActivity` |
| 顶栏「运行日志」按钮 | → 原生 `MiuixLogActivity`（能看到真实运行日志） |
| 抽屉「终端」 | → 原生 `EmbeddedTerminalActivity`，工作目录 `/storage/emulated/0/脚本` |
| 抽屉「设置」 | → Flutter 设置页，工作空间/外观/日志/编辑器偏好全部可读可写（深色主题与 Miuix 同步） |
| 抽屉「退出」 | 二次确认后退出应用 |
| 抽屉服务状态 | 无障碍「未启用」、悬浮窗「已隐藏（已授权）」、MCP「服务已停止」均为原生真实状态 |

仍未实现（下一步桥移植）：脚本条目点击/运行、编辑器打开、打包、插件/示例导入、MCP 开关等。

### 10.4 工作区入口改跳新版工作区（dev-907 / dev-908）

用户反馈「顶栏 <> 不要再跳回旧的 Miuix 列表」。按评估 A 路线（编辑器保留原生 Ace 兜底），
桥的 `openEditor` 改为打开 **AI.js Pro 新版多文件工作区** `ui.editor.ProCodeEditorActivity`：

- 打开规则：优先恢复编辑器自己上次会话的 **活跃文件**（`pro_code_editor` 的 `active_tab`），
  其次会话里任一仍存在的标签（`open_tabs`），再次脚本目录里第一个 `.js`；都没有才退回原生工作区列表。
  编辑器按自己的规则把启动文件设为焦点，其余标签从会话恢复（文件树/日志面板/调试条都在）。
- 验证（K40，dev-908）：Flutter 首页点 `<>` → `ProCodeEditorActivity`，直接打开上次的 `main.js`
  并带完整会话；返回键回到 Flutter 首页。截图 `workspace-908.png`。
- 同时按「A」把抽屉头部副标题「本地工作空间」改为「**脚本工作区**」，
  启动状态文字同步改为「正在准备脚本工作区」（截图 `drawer-908.png`）。

## 11. 界面品牌统一为 AI.js Pro（dev-906）

用户要求把界面里与 **Auto.js Pro** 相关的品牌字样改为 **AI.js Pro**。本次只改 Flutter 玻璃界面的
用户可见品牌位（源码在重建线 `lib`，由 `output/flutter-poc/build-aot.ps1` 重新编译进 payload）：

| 位置 | 改动 |
| --- | --- |
| `lib/main.dart` MaterialApp title | `Auto.js Pro` → `AI.js Pro`（最近任务/系统界面标题） |
| `lib/main.dart` 首页顶栏标题 | `Auto.js Pro` → `AI.js Pro` |
| `lib/main.dart` 启动品牌字标 | 旧 wordmark 图片（`assets/brand/autojs.png`，画面是 Auto.js Pro）→ 主题色 `Text('AI.js Pro')` |
| `lib/service_drawer.dart` 抽屉头部 | `Auto.js Pro` → `AI.js Pro` |

验证：
- 重新编译的 `libapp.so`（5,784,472 B，sha256 `be8a8eea49bc32a3a838c969add21be6edb67fc73d26a610ce391d7429a44bc0`）
  里已搜不到字符串 `Auto.js Pro`，存在 `AI.js Pro`。
- K40 升到 dev-906 实测：原生启动页（本来就是 AI.js Pro）、Flutter 启动品牌字标、首页标题、
  抽屉头部均为 **AI.js Pro**（截图 `brand-900.png` / `brand-1900.png` / `brand-4000.png` / `drawer-906.png`）。
- 保留未改：指向外部产品/工具的技术引用（VS Code「Auto.js Pro 扩展」的通知文案、示例脚本注释、
  内置文档页），这些说的是真实存在的 Auto.js Pro 产品/扩展名，不是本产品品牌位；
  Miuix 界面与系统桌面名称原本就是 AI.js Pro。

## 12. 桥移植第一批：运行 / 任务 / 日志 + 文件增删改（dev-909）

按评估 §7 的第一批桥，在 ai-js-pro 源码里实现（新增 `PocRuntime.java` / `PocWorkspace.java`，
Flutter 侧零改动）：

| 类别 | 桥方法 | 实现 |
| --- | --- | --- |
| 运行 | `run` / `runProject` / `floatRun` | 走 `external.open.RunIntentActivity`（`path` extra）；项目按 `project.json.main` 找入口。`floatRun` 先按直接运行处理 |
| 任务 | `tasks` / `cancelTask` / `stopTasks` | 运行中来自 `ScriptEngineService.getScriptExecutions()`；定时/事件任务来自 `TimedTaskManager`；取消=forceStop 或移除任务 |
| 日志 | `logs` | 读最新一个 `ScriptRunLog`（引擎每次运行落盘），支持 window/from/end 增量窗口 |
| 定时任务 | `timedTask` | 打开原生 `TimedTaskSettingActivity` |
| 文件 | `createFile` / `createFolder` / `createProject` | 在脚本目录内创建（带项目上下文引擎校验；构造 project.json + main） |
| 文件 | `rename` / `delete` / `trashList` / `restoreTrash` | 改名；删除进**本地回收站**（`.autojs-glass-trash/<token>/payload` + 私有 records），回收站可列可恢复 |
| 文件 | `aceOpenFile` | 在新版多文件工作区 `ProCodeEditorActivity` 打开 |
| 文件 | `share`/`shareFile` / `openWith` | FileProvider + ACTION_SEND / ACTION_VIEW（目录打包 zip 后分享） |
| 上下文 | `workspaceContext` | 向上找 project.json，返回 engine/locked（新建文件对话框用） |

K40 真机验收（dev-909，全部通过）：

| 操作 | 结果 |
| --- | --- |
| 启动页 | 品牌图标换成 **AI.js Pro 新 Logo**（旧 Auto.js 机器人图标已删，含从 v181 基线继承的旧品牌资源） |
| 点击脚本文件 | 打开新版多文件工作区，内容/标签正确；返回回到 Flutter |
| 行内 ▶ 运行 | 脚本执行成功，运行日志落盘（`ScriptRunLog`：开始/Hello/正常结束） |
| 新建文件 | 生成 `poctest.js`（hello 模板）并自动在编辑器打开 |
| 重命名 | `poctest.js` → `poctest2.js`，磁盘生效 |
| 删除 | 进本地回收站（记录 + payload 生成，列表消失） |
| 回收站 | 设置 → 回收站列出记录，恢复后文件回原位、回收记录清理 |
| 任务页 | 运行中的脚本显示在「运行中任务」，取消后提示「任务已取消」、列表清空（脚本被 ScriptInterruptedException 正常中断） |

尚未实现（下一批）：项目打包/签名（`project*`/`prepareSingleFile`）、插件与示例导入
（`pluginStates`/`importWorkspace`/`resetSample` 等）、MCP 开关（`mcpSet`）、
LSP/Ace 编辑器设置（`ace*` 的编辑器内行为）、快捷方式；`logs` 桥已实现但 Flutter 日志页
当前没有入口（运行日志暂时从原生日志页进入）。

## 13. 桥移植第二批：MCP / 插件 / 导入 / 示例 / 快捷方式（dev-910）

新增 `PocIntegration.java`（≈600 行），并把桥协议从「同步返回」升级为「可延迟回复」：
`GlassFlutterActivity` 新增 `onActivityResult` 转发，系统选择器/下载类方法（importWorkspace、
importPlugin、importResource、installPlugin 等）先用 `startActivityForResult` 打开选择器，
拿到结果再回 `BasicMessageChannel`。

| 类别 | 桥方法 | 实现 |
| --- | --- | --- |
| MCP | `mcpState` / `mcpSet` | 反射调用 miuix 源集 `McpSettings`（端口/局域网）+ `McpService`（启动/停止/运行状态/最后错误）；局域网开关变化时自动重启服务 |
| 插件 | `pluginStates` / `pluginSetEnabled` | 应用侧停用集合（`flutter-glass` prefs），停在 UI/脚本可用计数；状态跨进程持久化 |
| 插件 | `pluginIcon` | 读 PackageManager 图标 → PNG 缓存到 `cacheDir/plugin-icons`，返回路径 |
| 插件 | `installPlugin` | 本地 APK 直接走安装器；http(s) URL 先下载到缓存（限 200MB）再安装；FileProvider 授权 |
| 导入 | `importWorkspace` / `importPlugin` / `importResource` | SAF 选择器（工作区可多选），后台线程复制到目标目录后回复；插件/资源选择 APK 或文件 |
| 导入 | `importCatalogResource` | 把导入记录里的文件复制进脚本目录 |
| 示例 | `resetSample` / `copyExampleTree` | 用 `SampleFiles` 从 assets 重新落盘 / `importForEdit` 复制到脚本目录 |
| 快捷方式 | `shortcut` | 打开原生 `ShortcutCreateActivity`（预填脚本名，可加 Android 7.0 Shortcut） |

K40 真机验收（dev-910，已全部通过）：

| 操作 | 结果 |
| --- | --- |
| 抽屉「MCP 服务」开关 | 打开后 `McpService` 以前台服务运行、抽屉显示「服务运行中」；再关闭后服务销毁 |
| 插件页 | 列出 2 个本机插件（APK 打包 / 象棋引擎），开关可用；停用后显示「已停用 1 个插件 · 脚本可用 1/2」，prefs 落盘；重新启用后恢复 |
| 快捷方式 | 文件菜单 → 快捷方式 → 原生创建页（名称预填 `api-probe4-qjs`，可选 Android 7.0 Shortcut） |
| 导入 | FAB → 导入 → 系统文件选择器（PickActivity）正常打开，选择后走延迟回复 |

暂不实现（下一批）：
- ~~**项目打包/签名**~~ → 已在 §14 改成「入口走原生打包页」，不再移植 Flutter 工程页。
- **悬浮控制条**：`floatRun` 目前等同直接运行；控制条在原生悬浮层（enhancedfloaty），需单独接。
- **设备连接信息**（`computerPluginState/Set`）：抽屉「开发者调试 / 电脑连接」仍会报未实现。
- Ace 编辑器显示设置已通过 `runtimePreferences` 生效（设置页开关），无需额外桥。

## 14. 打包/签名入口：走原生打包页（dev-911）

**决策**：不移植 Flutter `ProjectPage`（工程页契约有 `projectLoad/Save/Build/BuildStatus`、
`projectSigning*`、`importProjectIcon` 等 10+ 方法，且容易和原生打包行为漂移），
打包/签名**统一走原生打包页** `BuildActivity → MiuixBuildActivity`（页面内已有配置、权限、脚本保护、
签名模式：自动签名/选择签名/新建签名、进度与产物处理）。

实现：
- 桥新增 `openBuildPage`：单文件传脚本路径、工程传目录路径，落到
  `BuildActivity.EXTRA_SOURCE`（`*.extra_source_file`）；原生页面自行区分单文件/项目模式。
- Dart 侧三处入口改指向它：文件菜单「打包单文件」、工程目录工具栏「项目配置与打包」、
  启动/resume 时消费后台打包请求（`nativePackagingRequest`）；`pendingAction` 也无待办时返回空，
  不再在启动时toast「POC 尚未实现」。
- 顺带修复：`browser.dart` 在批量写入时被截断（丢了文件尾部 24 行，`_WorkspaceNameDialog` 的
  build 后半段）——从 engine-upgrade 备份恢复尾部，类/函数集合与备份一致，AOT 重新编译通过，
  真机验证新建文件对话框正常。

K40 真机验收（dev-911）：

| 操作 | 结果 |
| --- | --- |
| 工程目录工具栏「项目配置与打包」 | 打开原生「打包应用」页（项目模式）：脚本文件(夹)=脚本/00-codex-ui、应用名/包名/版本/图标/权限（声明 16）/运行配置齐全 |
| 下滑打包页 | 可见脚本引擎、无障碍、图色模块、脚本保护（加密/快照/so）、**签名**（自动/选择/新建，当前签名 C=CN,O=AI.js Pro）与「打包应用」按钮 |
| 文件菜单「打包单文件」 | 打开同一打包页的单文件模式：脚本文件=`脚本/api-probe4-qjs.js`，应用名自动取文件名，包名自动生成 |
| 启动/返回首页 | 无「nativePackagingRequest 尚未实现」toast；文件列表、新建文件对话框（被恢复的尾部类）均正常 |
| 回归 | Flutter 首页、目录浏览、搜索框、文件菜单、运行入口不受影响 |

## 15. 桥移植第三批：电脑连接 + 悬浮控制条（dev-912 → dev-914）

### 15.1 电脑连接（VS Code 调试插件）

新增 `PocDeviceLink.java`：

| 桥方法 | 实现 |
| --- | --- |
| `computerPluginState` | `stateCode` + `listening`（`DevPluginServer.isRunning()`）+ `addresses`（手机 IPv4 列表）+ `port`=9317 + `clientPort`=8327 + `lastHost` + `clientStateKnown/clientState`（CONNECTED / CONNECTING / DISCONNECTED，连接中状态反射 `mConnectingSocket` 判断） |
| `computerPluginSet` | mode 2：服务器模式启停（`DevPluginServerService.start/stop` + 持久化 `DevPluginPrefs`）；mode 1：主动连接电脑（写 `computer_host` 让 `DevPluginServer` 的 reconnect 线程拨 8327；断开时清空 host） |

- 等待逻辑放在**后台线程**（`computerPluginSet` 走异步桥）：服务在应用主线程创建，同步 sleep 等待会把 `startForeground` 一起堵住导致状态误报（dev-912/913 上出现过「服务器未能启动/尚未停止」，dev-914 修复）。
- 实测 PC（192.168.10.7）的 VS Code 扩展在线时，手机会自动 reconnect 并完成 hello 握手，界面显示「已连接到电脑 · 上次地址 192.168.10.7」；PC 扩展不在线时显示「正在连接电脑…/未连接电脑」。

### 15.2 悬浮运行（悬浮控制条）

新增 `PocFloatingBar.java`：文件菜单「悬浮运行」→ 在屏幕上层显示控制条
`▶ 脚本名  ▶  ■  ✕`（`TYPE_APPLICATION_OVERLAY`，不抢焦点）：

- `▶`：用 `ScriptEngineService.execute(JavaScriptFileSource, ExecutionConfig)` 无界面启动脚本（不弹 RunIntentActivity），控制条标题变为「● 脚本名」；
- `■`：`engine.forceStop()`，标题恢复「▶」；
- `✕`：停止并移除悬浮条；
- 无悬浮窗权限时返回 `{needsPermission:true}` 并跳到系统授权页；`floatingWindow` 桥同时回报 `showing` 状态。

K40 真机验收（dev-912 / dev-914）：

| 操作 | 结果 |
| --- | --- |
| 文件菜单 → 悬浮运行 | 悬浮条出现在屏幕左上（▶ 脚本名 ▶ ■ ✕） |
| 悬浮条 ▶ | 脚本开始运行（logcat `开始运行[/storage/emulated/0/脚本/api-probe4-qjs.js]`），标题变「●」，脚本产出结果文件 |
| 悬浮条 ■ | 脚本停止，标题恢复「▶」 |
| 悬浮条 ✕ | 控制条消失 |
| 设备连接 → 服务器模式 开/关 | 开：监听 9317、列出 192.168.10.5 / 10.28.93.160、状态「服务器已开启」；关：状态「服务器模式未开启」；均无报错 |
| 设备连接 → 主动连接 | 上次地址 192.168.10.7；PC 扩展在线时自动连接成功（CONNECTED） |

## 16. 对齐收尾：6 个未对齐项补齐（dev-915）

按「还有什么没有对齐」清单的 1-6 项补齐（Agent Teams 分 4 个任务并行实现，Lead 接线/构建/验收）：

| # | 未对齐项 | 实现 | K40 验收 |
| --- | --- | --- | --- |
| 1 | 设备连接页 Shizuku 区 / 修复无障碍按钮报「尚未实现」 | 新增 `PocServices.java`：`shizukuState`（running/authorized/supported/installed/busy/phase/hint）、`shizukuEnableAccessibility`（异步，Shizuku 授权 + 启用无障碍）、`shizukuOpen`、`repairAccessibility`（Shizuku → WRITE_SECURE_SETTINGS → root 三通道，4s 预算，失败给 adb 命令） | ✅ 显示「Shizuku 运行中 · 已授权」；点修复后无障碍变「已启用」并提示「已恢复无障碍授权」 |
| 2 | 抽屉「悬浮窗」开关无效 | `floatingWindow {enabled}` 接到原生 `FloatyWindowManger.showCircularMenu/hideCircularMenu`；`deviceState.floatingWindowShowing` 回读 `isCircularMenuShowing()` | ✅ 开：状态「已显示」+ 左侧出现原生悬浮菜单圆钮；关：「已隐藏」 |
| 3 | 抽屉「允许远程调试」只存本地偏好 | `service_drawer.dart` 改为读 `computerPluginState.listening` 作为初始状态，开关调 `computerPluginSet {mode:2, enabled}` 真启停 `DevPluginServerService`（失败回滚+提示） | ✅ 关：服务停止、副标题回「VSCode 调试插件 9.0」、主动连接项禁用；开：服务运行 |
| 4 | 资源页「我的 / 导入记录」永远空 | 新增 `PocImportRecords.java`（JSON 持久化 `files/flutter-glass/import-records.json`，同 path 去重、200 条上限、临时文件+rename 原子写）；`PocIntegration` 在 importPlugin/importResource/importWorkspace/installPlugin/importCatalogResource 成功回调里登记 | ✅ 资源页导入一个文件后「我的导入 1 项」显示文件名/路径/大小 |
| 5 | 设置页「新版编辑器选项」是摆设 | 删除那组假开关，改为「编辑器字号」（8-40，写原生 Ace 真实偏好 `pro_code_editor/text_size_sp`；`PocAppInfo.editorSettings`） | ✅ 8→20 sp 保存后原生工作区字号明显变大；已恢复 8 |
| 6 | 设置页缺「检查更新/更新源/问题反馈/关于/许可」 | 新增 `PocAppInfo.java`（`checkUpdate` 原生 UpdateCheckDialog、`updateSource` 读写 `key_update_source_url`、`openIssueReport`、`openAbout`→MiuixAboutActivity、`openLicenses`→MiuixLicensesActivity）；`settings_page.dart` 新增「关于」区 5 行 | ✅ 检查更新提示「已经是最新版本」；更新源弹窗显示当前源；问题反馈开浏览器；关于页 dev-915；开源许可页正常 |

其他：
- `PocBridge.handle` 异步分发同时咨询 `PocServices.isAsync`；`bridge.dart` 给 `shizukuEnableAccessibility` 放宽超时到 5 分钟（Shizuku 首次授权可能超过 20s）。
- 重建线 `test/` 下与「新版编辑器选项」相关的旧断言（settings_alignment_test 等）未更新；这些测试不参与 APK 构建，后续做 UI 测试时需同步。

## 17. 第四批：文件浏览器复制/移动/批量 + 悬浮控制条增强（dev-916）

### 17.1 文件浏览器：复制 / 剪切 / 粘贴 / 批量操作

- 桥（`PocWorkspace`）新增：
  - `fileClipboard {mode?, paths?, clear?}`：剪贴板放应用私有偏好（跨页面/重启保留），读取时剔除已删除的源；`count` 返回数量。
  - `pastePaths {path}`：粘贴到目标目录；复制保留剪贴板、移动成功清空；同名自动改名 `名字 (1).ext`；禁止把目录移动进自身/子目录；逐条容错返回 `{pasted, errors, cleared}`。
  - `deletePaths {paths}`：批量移入回收站，逐条容错返回 `{deleted, errors}`。
- Dart（`browser.dart`）：
  - 长按文件进入多选：顶部工具条换成「已选 N 项 + 全选 / 复制 / 剪切 / 移入回收站 / 取消选择」，行右侧显示勾选圈、选中行高亮；点击行切换选中。
  - 文件菜单新增「复制 / 剪切」，目录菜单在有剪贴板时新增「粘贴到此处」。
  - 有剪贴板时文件页工具栏出现「粘贴 N 项到当前目录」按钮；粘贴/批量删除后自动刷新并提示结果。

### 17.2 悬浮控制条增强（`PocFloatingBar`）

- 可拖动：左侧「≡」把手支持拖动，位置写入 `flutter-glass`（`floaty_bar.x/y`），关闭后再次悬浮运行会回到上次位置。
- 按钮扩为：▶ 运行当前脚本 / ■ 停止当前 / ⏹ 停止全部脚本 / 📄 打开运行日志（原生 MiuixLogActivity）/ ✕ 关闭；运行中标题显示「● 脚本名」。
- 切换悬浮运行的目标脚本时，先停掉控制条上正在跑的旧脚本，避免「标题是 A、跑的是 B」。

K40 真机验收（dev-916）：

| 操作 | 结果 |
| --- | --- |
| 长按进入多选 + 勾选第二项 | 顶部「已选 2 项」，多选操作条可用 |
| 多选 → 复制 → 进入空目录 → 粘贴 | 两个文件出现在目标目录，提示「已粘贴 2 项到当前目录」；剪贴板保留 |
| 单文件「剪切」→ 另一目录粘贴 | 源文件消失、目标出现文件，提示「已粘贴 1 项」；剪贴板清空、粘贴按钮消失 |
| 多选 → 移入回收站（确认） | 文件消失、提示「已将 1 项移入回收站」、回收站记录生成 |
| 悬浮运行 | 控制条显示「≡ ▶ 名称 ▶ ■ ⏹ 📄 ✕」 |
| 拖动「≡」把手 | 控制条移动到新位置；`floaty_bar.x/y` 落盘 |
| 控制条 ▶ | 脚本开始运行，标题变「● 名称」 |
| 控制条 📄 | 打开原生运行日志页，控制条仍悬浮在日志页上 |
| 控制条 ■ / ⏹ | 停止当前/全部后标题恢复「▶」 |
| 控制条 ✕ 后再次悬浮运行 | 新控制条出现在上次拖动后的位置 |

## 18. 悬浮控制条对齐原厂源码：收起/展开 + 编辑（dev-917 → dev-919）

### 18.1 原厂源码依据

反编译原厂 APK 后找到 `com.stardust.autojs.extension.FloatingControllerView`（classes3.dex）：

- 布局 `floating_controller` 的 id：`title`、`action`（⌄ 箭头）、`container`、`buttonContainer`、`runOrStop`、`edit`（✎）、`log`（▤）、`exit`（✕）。
- `collapse()`：`action` 箭头旋转 180°（⌄→⌃），Fade + ChangeBounds 300ms，然后 **`buttonContainer` 与 `title` 都 setVisibility(GONE)**。
- `expand()`：箭头转回 0°，`buttonContainer`/`title` 重新 VISIBLE。
- `toggle()`：按 `buttonContainer` 可见性在收起/展开间切换。
- 另有 `runOrStop()`、`showLog()`（BottomSheetDialog + LogView，占屏幕 3/4 高）、`setOnEditClick()`（无回调时隐藏 ✎）、`setOnExitClick()`。
- 原厂布局 `res/layout/floating_controller.xml` 的描述与重建线文档 `Auto.js-Pro-悬浮控制条-v28.md`/`v30`（⌄ 收起、高度 132→110px）一致。

### 18.2 本工程实现（`PocFloatingBar`）

- 新按钮行：`≡ 拖动把手 | 标题 | ⌄ 收起 | ▶ 运行 | ■ 停止当前 | ⏹ 停止全部 | ✎ 编辑 | 📄 日志 | ✕ 关闭`。
- **收起**按原厂语义：点 ⌄ 后标题与整排操作按钮全部隐藏，只剩 `[≡][⌃]` 小胶囊；再点 ⌃ 展开；收起状态写入 `floaty-glass/floaty_bar.collapsed` 持久化。拖动把手收起时保留（原厂没有拖动，本实现额外保留以便继续移动）。
- **✎ 编辑**：打开 `ProCodeEditorActivity` 并传当前脚本 `path`，与文件菜单「编辑文件」同一条原生 Ace 工作区。
- 修复：按钮变多后控制条右侧 `✕` 被挤出屏幕——加宽后（含收起/展开切换）按布局宽度自动夹回屏幕内，标题区再收窄。
- 与原厂的差异（保留更实用的行为）：原厂 `▤` 是底部 LogView 面板，本实现 `📄` 打开原生 `MiuixLogActivity`；原厂只有 ▶/✎/▤/✕，本实现额外保留 ■（停止当前）与 ⏹（停止全部）；原厂没有拖动把手/位置记忆。

K40 真机验收（dev-919，逐像素定位按钮后点击）：

| 操作 | 结果 |
| --- | --- |
| ⌄ 收起 | 控制条缩成 `[≡][⌃]` 小胶囊；`floaty_bar.collapsed=true` |
| ⌃ 展开 | 恢复完整 9 个控件；`floaty_bar.collapsed=false` |
| ✎ 编辑 | 打开原生 Ace 工作区，标签为当前脚本 `api-probe4-qjs.js`，控制条仍悬浮在编辑器上 |
| ✕ 关闭 | 控制条窗口移除 |
| 收起后再悬浮运行 | 按持久化状态恢复为收起小胶囊（位置也保持） |
| 加宽后 ✕ | 夹回屏幕内可见、可点（dev-918 前会被裁掉） |

## 19. 悬浮控制条视觉完善（dev-920 → dev-923）

用户反馈：⌄ 太小不好看；▶/■/⏹/📄/✕ 要「用颜色区分开明显一点」；运行中标题「● 脚本名」也要更明显。

### 19.1 改动

- **⌄ 收起按钮**：从 14sp 小字改为 20sp、30dp 圆形按钮（同色半透明圆底），触控目标 = 30dp，收起/展开时字符在 ⌄/⌃ 间切换。
- **彩色按钮芯片**：所有操作改成 30dp 圆形底 + 同色半透明背景，文字颜色区分：
  | 按钮 | 颜色 |
  | --- | --- |
  | ▶ 运行 | 绿 `#6BE88A` |
  | ■ 停止当前 | 红 `#FF6B6B` |
  | ⏹ 停止全部 | 橙 `#FFB74D` |
  | ✎ 编辑 | 蓝 `#7EC8FF` |
  | 📄 日志 | 青 `#4DD0E1` |
  | ✕ 关闭 | 红 `#FF7B8A` |
  | ≡ 拖动把手 | 近白 `#E8F3F1`（无圆底） |
- **运行中标题**：「● 脚本名」由近白改为绿色 `#6BE88A` + 加粗；未运行仍为「▶ 脚本名」近白。
- **窗口宽度修复**（dev-921/922）：按钮变彩色圆底后整体变宽，最右 ✕ 会被窗口裁掉。改为在 `addView` 之前先 `measure` 内容、把窗口宽高固定为内容尺寸，再按屏幕宽度夹 x/y；收起/展开切换时重建窗口，保证两种形态都量对宽度。

### 19.2 验收（K40，dev-922/923）

| 项 | 结果 |
| --- | --- |
| 彩色按钮 | 绿▶ / 红■ / 橙⏹ / 蓝✎ / 青📄 / 红✕ 一眼可分 |
| ⌄ 收起 | 变成 `[≡][⌃]` 小胶囊，箭头明显变大 |
| ⌃ 展开 | 重建窗口后恢复 9 个控件，最右 ✕ 完整可见（不再被裁） |
| ▶ 运行 | 标题变绿色加粗「● __bar_test.js」，toast 正常 |
| ■ 停止 | 标题恢复「▶ 脚本名」 |
| ✕ 关闭 | 窗口移除 |

## 20. 悬浮控制条细节再优化（dev-924）

用户反馈：⌄ 收起没有居中、想要个颜色、再优化细节。

### 20.1 改动

- **收起按钮换成居中三角**：字符由 `⌄` 改为 `▼`（Unicode 三角在字体里天然几何居中，不再因箭头字形基线偏上而显得没居中），字号 13sp、颜色**紫色 `#C9A7FF`**（区别于一排功能色）；展开态 `▼`，收起态用 `setRotation(180)` 变成 `▲`，语义同原厂 action 箭头。
- **拖动把手也带圆底**（半透明白），收起后的 `[≡][▲]` 两颗圆形胶囊大小一致、左右对齐，视觉更均衡。
- **运行状态贯穿到收起态**：脚本运行时把手 `≡` 变绿色（标题区收起时也看得出在跑）。
- **按下水波反馈**：所有圆形芯片改为 `RippleDrawable`（白色 25% 水波），点按有反馈。
- **排版微调**：标题最大宽度 96→84dp，给右侧彩色按钮留出边距；所有芯片 `includeFontPadding=false` + `gravity=CENTER`，字形垂直居中。
- 修掉一个编译期问题：`ColorStateList`/`RippleDrawable` 的 import 缺失（构建失败一次，补 import 后通过）。

### 20.2 验收（K40，dev-924）

| 项 | 结果 |
| --- | --- |
| ▼ 收起按钮 | 紫色圆底、三角正中；点后变 ▲ 且胶囊只剩 `[≡][▲]`，两圆居中对称 |
| 收起态运行 | 脚本运行时 `≡` 变绿色，收起状态也能看到「在跑」 |
| 彩色芯片 | 绿▶/红■/橙⏹/蓝✎/青📄/红✕，按下有水波 |
| 运行标题 | 「● __bar_test.js」绿色加粗（展开态） |
| ✕ 关闭 | 窗口移除正常 |

## 21. 悬浮控制条：居中 chevron + 收起提示（dev-925）

用户反馈：「这个没在中间」「这个好提示缩小悬浮窗吗」——即三角字形看着不居中，希望收起是「缩小悬浮窗」的明确提示。

### 21.1 改动

- **换成自绘 chevron**：新增内部类 `ChevronView extends View`，用 `Canvas.drawPath` 画 `⌄` 折线（2.1dp 圆头描边、紫色 `#C9A7FF`），**几何居中**，不再受字体基线/字形内边距影响；收起时 `canvas.rotate(180°)` 变 `⌃`。
- **提示语义**：
  - 展开态 chevron 朝下 = 「收起悬浮窗」；收起态朝上 = 「展开悬浮窗」。
  - `contentDescription` 与 `setTooltipText` 随状态切换（长按可见 tooltip）。
  - 首次点收起弹 Toast：**「已收起悬浮窗，点 ⌃ 展开」**（只提示一次，写 `floaty_bar.hint_shown`）。
- 保留：紫色圆底 + 水波、与拖动把手 `[≡][⌃]` 对称、运行中把手变绿。

### 21.2 验收（K40，dev-925）

| 项 | 结果 |
| --- | --- |
| 展开态 chevron | 紫色圆内 `⌄` 水平/垂直居中（自绘，逐像素对齐） |
| 点收起 | 变 `⌃`；首次弹「已收起悬浮窗，点 ⌃ 展开」Toast；胶囊 `[≡][⌃]` 两圆对称 |
| 再点展开 | 恢复完整条，chevron 回 `⌄` |
| 长按 | tooltip 显示「收起悬浮窗 / 展开悬浮窗」 |
| ✕ | 关闭正常 |

## 22. 示例页对齐原厂 Auto.js Pro（dev-926 / dev-927）

用户反馈：「现在就到示例的问题了，你看看 auto.js pro 的」。

### 22.1 先看原厂怎么做

对照 ai-js-pro 原生示例页 **`MiuixSampleFragment.kt`**（Auto.js Pro 风格示例浏览器）：

| 原厂行为 | 说明 |
| --- | --- |
| 面包屑 | 「示例文件 › QuickJS 新引擎 › …」，不是「内置示例」 |
| 行副标题 | 文件夹 / JavaScript 示例 / Markdown 文档 / JSON 文件 / 安装包 / 示例资源；**不显示修改时间** |
| 点击文件 | `SampleFiles.materialize` 落盘后 `ProCodeEditorActivity.sampleIntent(file, assetPath)` 打开，编辑器工具栏出现「重置」（可恢复内置版本） |
| 文件菜单 | 查看 / 运行（`SampleFiles.materialize` 后按磁盘路径跑）/ 打包（落到原生打包页）/ 导入（命名对话框）/ 打开已导入副本 |
| 目录菜单 | 打开 / 打包（项目，带 project.json 时）/ 重置所有示例 |
| 重置所有示例 | 用 APK 内 assets/sample 覆盖 files/sample（二次确认） |

而此前 Flutter 示例页只是一个只读 `FileBrowser`：标题「内置示例」、行显示「文件夹 + 修改于 …」、菜单只有 查看示例/复制到脚本目录/重置示例，没有 运行示例、打包、导入、重置所有示例；点示例走 `aceOpenFile` 也不带 sample_asset_path，编辑器里没有「重置」。

### 22.2 本轮改动

- **桥（`PocIntegration.sampleAction` + `PocBridge` 分发）**：
  - `view`：materialize 后 `ProCodeEditorActivity.sampleIntent(activity, file, assetPath)`，编辑器带「重置」；
  - `run`：materialize 后走 `PocRuntime.execute("run")`，与文件页运行同一引擎链路；
  - `build` / `buildProject`：落到原生打包页（单文件 / 项目）；
  - `import`：复用 `copyExampleTree`（`importForEdit`，伴生文件一起导入、不覆盖已有）；
  - `resetAll`：`SampleFiles.copyAssetTree("sample", files/sample, overwrite=true)`。
  - 路径白名单：只允许 files/sample 下（或根本身）。
- **Dart（`browser.dart` + `main.dart`）**：
  - 标题改「示例文件」；行副标题按扩展名显示示例类型，示例页不再显示「修改于」；
  - 文件菜单新增「运行示例 / 打包示例 / 导入到我的脚本」，隐藏示例页无意义的「打开工作区」；
  - 目录菜单新增「打包（项目）」（有 project.json 时）与「重置所有示例」（带二次确认）；
  - 点示例改走 `sampleAction.view`，编辑器带「重置」。
- **修复**：dev-926 首次点示例报 `Bad state: POC 尚未实现集成桥方法：sampleAction` —— `PocIntegration.execute` 的 switch 漏了分发（dev-927 补上）。

### 22.3 验收（K40，dev-927）

| 项 | 结果 |
| --- | --- |
| 示例首页 | 标题「示例文件」；行副标题「文件夹」，无修改时间 |
| 进入 QuickJS 新引擎 → 悬浮窗 | 行副标题「JavaScript 示例」，行高 74dp |
| 点示例 | 打开原生 Ace 工作区，工具栏出现「重置」按钮（sample_asset_path 生效） |
| 文件菜单 | 查看示例 / 运行示例 / 打包示例 / 导入到我的脚本 / 重置示例 等齐全 |
| 导入到我的脚本 | Toast「已导入：/storage/emulated/0/脚本/QuickJS 圆形触摸穿透/…js」，脚本目录出现目录（测试后已删除） |
| 目录菜单 | 含「重置示例」「重置所有示例」 |
| 重置所有示例 | 二次确认对话框正常（测试后取消） |

**空目录处理（已做）**：`assets/sample` 下有 6 个没有任何文件的空壳目录（Git 不跟踪空目录，属于本地残留），已全部删除：
`sample/图色处理`、`QuickJS 新引擎/YOLO目标检测/opencv`、`QuickJS 新引擎/YOLO目标检测/YOLO目标检测/OpenCV 5.0 DNN版本`、`QuickJS 新引擎/项目与打包/象棋自动走棋`（已随插件分发）、`Rhino 引擎/图片与图色处理/YOLO目标检测/models`、`Rhino 引擎/项目与打包/dsh 手机客户端/libs`；
删完连带清掉两个只剩空壳的上级目录，现 `assets/sample` 下 **0 个空目录、311 个文件**，示例根目录只剩真正有内容的 `QuickJS 新引擎` 与 `Rhino 引擎`。空目录本来就不会进 APK，本次纯属仓库工作区清理，**APK 内容无变化、无需重新发布**。

## 23. 本次改动文件

- 修改：`apps/app/build.gradle`（新增 `-PflutterPoc` 调试源集/依赖开关，默认关闭、不影响发布）。
- 新增：`apps/app/src/flutterpoc/java/com/jdkshen/aijspro/flutterpoc/{GlassFlutterActivity,PocBridge,
  PocRuntime,PocWorkspace,PocIntegration}.java`、`apps/app/src/flutterpoc/AndroidManifest.xml`。
- 新增：本报告；`docs/README.md` 索引更新。
- 未改动：主源码、主清单、资源与生产依赖（POC 全部条件编译在 debug 源集）。

## 24. dev-935：跑脚本不再需要「先按返回才能点击/滑动」

### 现象

在文件/示例页点 ▶ 运行脚本后，页面要点一下返回键才恢复正常点击、滑动；长脚本期间整个界面像被盖了一层。

### 原因

PocRuntime.run() 以前是启动 `RunIntentActivity`（半透明 Activity 一直占前台到脚本结束，用来给脚本弹窗合法 window token）。它没有内容视图却位于顶层，会吞掉所有触摸事件，所以必须按一次返回把它关掉。

### 修复（dev-935）

- `PocRuntime.run()` 改为直接 `AutoJs.scriptEngineService.execute(source, config)`：普通脚本在后台线程跑，不占前台窗口；
- 工作目录 = 脚本所在目录（项目运行 = 项目目录），相对 import/read 仍能找到同目录资源；
- 带 UI 执行模式声明的脚本仍由引擎服务自动路由到 `ScriptExecuteActivity`（真正的 UI 页面，返回键行为不变）；
- 悬浮控制条的运行同样补上了脚本目录作为工作目录。

### 验证（K40，dev-935）

- 非 UI 探针 `__run_probe.js`（`sleep(6000)`）：点 ▶ 1 秒后 `mCurrentFocus` 仍是 `GlassFlutterActivity`；脚本运行期间点「示例」tab 立即切换（截图 `output/flutter-poc/run-while-running.png`）；日志 `run-probe start` / `run-probe done`。
- UI 探针 `__ui_probe.js`：点 ▶ 后打开 `ScriptExecuteActivity` 并显示 UI（截图 `output/flutter-poc/ui-script-page2.png`），返回键关闭。
- APK（dev-935）：266,030,657 B，sha256 `4105efc49c7b75b9112f7d1542ecfda860607bbce2544c43c1aa47f79c35e0d9`；K40 与小米 8 均已安装 dev-935。

## 25. dev-936/937/938：停止任务直接执行（不弹确认框）

### 改动

- 任务管理页「停止脚本 / 停止全部脚本 / 取消任务」不再弹确认框，点一下直接执行，结束后用浮动提示反馈（`所选脚本已结束` / `任务已取消`）；
- 修正运行中任务的显示：桥里的 `kind: script` 归一为「运行中」（按钮/详情文案由「取消」变回「停止」），引擎标签按 Rhino / QuickJS / Node 显示（Rhino 头像恢复红色 R，之前全是 N）；
- 定时/意图任务的引擎标签按脚本首行引擎标记判断。

改动文件：`task_page.dart`（去掉 confirm 帮助方法与两处调用、RuntimeTask 归一化）、`PocRuntime.java`（engineLabel/engineOf）。

### 验证（K40，dev-938）

- 运行 30s 探针脚本 → 任务管理页出现运行中任务（Rhino 红 R）；点行尾 ✕：**不弹窗**，1 秒内运行列表变空并提示「所选脚本已结束」（截图 `output/flutter-poc/tasks-938-stopped.png`）；
- 再运行一次 → 点右下角「停止全部脚本」：同样直接清空、提示「所选脚本已结束」（截图 `output/flutter-poc/tasks-938-stopall2.png`）；
- APK（dev-938）：266,030,661 B，sha256 `3b763b11d1a9f7c32a788d01ea89aa81747edb2e0ebd95bbe5ae8c6df95f02cc`；K40 与小米 8 均已安装 dev-938。
