# 去掉 Miuix，统一 Flutter 界面（task-177 / dev-1128）

> 用户决定：**删掉 Miuix，只留 Flutter 界面**。本报告记录 dev 通道从「Flutter 主入口 + Miuix 回退」
> 收敛为 **Flutter-only** 的全部改动、页面功能对等矩阵、缺口、验证与回退方式。
> 代码在 dev-1128 构建前落地；AOT 已重建（libapp.so sha 变化见「验证」）。

## 0. 一句话结论

- 入口：SplashActivity **无条件**进入 `GlassFlutterActivity`，不再回退 `MainActivity`/Miuix；
  桌面只保留一个 Flutter 图标（`flutterpoc` manifest 里「AI.js Pro（Miuix 回退）」入口已删）。
- 构建：`apps/app/build.gradle` 用 sourceSets 把 `src/miuix` 的 41 个 UI 页面文件与 `testMiuix` 排除出构建，
  并移除 `miuix-android / compose-foundation / compose-ui / zxing` 四个依赖；**源码仍留在磁盘上（先排除不物理删）**。
- 保留的非 UI 逻辑：`com.jdkshen.aijspro.mcp` 整包（21 个 Kotlin 文件，MCP HTTP 服务/工具/历史等）+
  `ui/project/PermissionCatalog.kt`、`PlatformPermissions.kt` 已 **git mv 到 src/main**，MCP 功能完整保留；
  `McpService` 声明从 src/miuix manifest 搬到 main manifest。
- main 源集里约 20 处对 Miuix 的直连全部改为：反射（找不到类即回退旧实现）/ 旧 Material 回退 /
  交给 Flutter 路由（打包、项目配置、关于、许可）。APK 里 `top/yukonga`（Miuix 库）与 `Miuix*` 类为 0。
- 任务名/产物路径保持不变：`:app:assembleMiuixCompatDebug -PflutterPoc=true` →
  `apps/app/build/outputs/apk/miuixCompat/debug/app-miuix-compat-arm64-v8a-debug.apk`。
- AOT：外置重建源 `output/autojs-pro-flutter-rebuild` 的 Flutter 改动已重建，
  `libapp.so` sha256 `e2f43ffb…` → `f265a483…`（5653400 → 5751704 bytes）。

## 1. 入口统一

| 文件 | 改动 |
|---|---|
| `apps/app/src/main/java/com/jdkshen/aijspro/ui/splash/SplashActivity.java` | 删除 `R.bool.flutter_main_entry` 分支与 `MainActivity` 回退：`enterNextActivity()` 无条件 `setClassName(GlassFlutterActivity)`；类缺失（未带 `-PflutterPoc=true` 构建）只 Toast + finish，**不再回退 Miuix 主界面** |
| `apps/app/src/flutterpoc/AndroidManifest.xml` | 删除「AI.js Pro（Miuix 回退）」第二个 LAUNCHER（MainActivity enabled=false）整块；保留 Splash 的 LAUNCHER priority 与 Flutter Activity/服务声明 |
| `apps/app/src/main/java/…/ui/main/MainActivity.java` | 超类改成「历史兜底」：不再安装 Compose 顶部导航/FAB（mMiuixFab 恒 null，各处已有空判断）；Tab 工厂直接用原生 `TaskManagerFragment/DocsFragment/MarketFragment/CommunityFragment`；`onCreate` 的「有 Flutter 类就跳 Flutter」逻辑保留 |

APK 校验：`aapt dump badging` 的 launchable-activity 只剩 `com.jdkshen.aijspro.ui.splash.SplashActivity`
（另有一个 leakcanary 调试入口），冷启动链路 Splash → GlassFlutterActivity → Flutter `SplashGate/BrandSplash` → Home。

## 2. 构建链：排除 Miuix（不物理删，可回退）

### 2.1 `apps/app/build.gradle`

- `android.sourceSets` 内新增：

  ```groovy
  miuix { java.srcDirs = []; resources.srcDirs = []; assets.srcDirs = [] }
  testMiuix { java.srcDirs = [] }
  ```

  说明：flavor 名仍叫 `miuix`（`assembleMiuixCompatDebug` 任务名/产物路径不能变），但其默认源目录
  `src/miuix` 不再参与编译；`src/miuix/AndroidManifest.xml` 已清空为 `<application />`，
  避免 manifest merger 引用被排除的 Activity/Service。
- 依赖删除：`miuixImplementation top.yukonga.miuix.kmp:miuix-android`、`compose.foundation`、
  `compose.ui`、`com.google.zxing:core`（zxing 只被被排除的 `MiuixMcpActivity` 二维码使用）。
- APK 体积从 dev-1126 的 138,602,326 bytes 降到 **133,427,336 bytes（约 -5.2 MB）**。

### 2.2 从构建排除的 src/miuix 页面（41 个 .kt，全部仍在磁盘上）

- theme：`AijsMiuixTheme.kt`、`MiuixBackButton.kt`、`MiuixPullRefresh.kt`
- ui/common：`MiuixDialogHost.kt`、`MiuixFileChooserDialog.kt`、`MiuixHintDialog.kt`、`MiuixInputDialog.kt`
- ui/main：`MiuixFileSearch.kt`、`MiuixMainFabHost.kt`、`MiuixMainNavigationHost.kt`、`MiuixSearchActivity.kt`、`drawer/MiuixDrawerHost.kt`
- ui/explorer：`MiuixExplorerMenuHost.kt`、`MiuixProjectMenuHost.kt`、`MiuixScriptListHost.kt`
- ui/log：`MiuixLogActivity.kt`；ui/mcp：`MiuixMcpActivity.kt`、`MiuixMcpHistoryActivity.kt`
- ui/project：`MiuixBuildActivity.kt`、`MiuixProjectConfigActivity.kt`；ui/settings：`MiuixSettingsActivity.kt`、`MiuixAboutActivity.kt`、`MiuixLicensesActivity.kt`
- ui/sample：`MiuixSampleCatalog.kt`、`MiuixSampleFragment.kt`；ui/task：`MiuixTaskManagerFragment.kt`
- ui/plugin/market/resource/community/service/user/shortcut/doc：`MiuixPluginFragment.kt`、`MiuixMarketFragment.kt`、`MiuixResourceFragment.kt`、`MiuixCommunityFragment.kt`、`MiuixServiceActivity.kt`、`MiuixLoginActivity.kt`、`MiuixRegisterActivity.kt`、`MiuixIconPicker.kt`、`MiuixDocumentationActivity.kt`
- ui/update：`MiuixUpdateCheckDialog.kt`、`MiuixUpdateDialog.kt`、`MiuixDownloadProgressDialog.kt`
- ui/widget/floating：`FloatyCompose.kt`、`MiuixBubbleMenuHost.kt`、`ui/floating/MiuixActionMenuHost.kt`

### 2.3 testMiuix

- `src/testMiuix` 里 28 个**与 Miuix UI 无关**的测试（MCP 13 个：`McpHttpServerTest`、`McpTools/Schema/Catalog`、
  `McpRpcValidation`…；Explorer/Plugin/Sample/ScriptRepository/VersionService 等）已 **git mv 到 `src/test`**
  继续跑（保住覆盖率）；只有 2 个真正测 Miuix UI 的用例留在 `testMiuix` 并被 sourceSets 排除：
  `MiuixFileSearchTest.kt`、`MiuixSampleCatalogTest.kt`。

### 2.4 MCP 逻辑搬迁（21 个 Kotlin 文件 → src/main）

`src/miuix/java/com/jdkshen/aijspro/mcp/**` → `src/main/java/com/jdkshen/aijspro/mcp/**`
（McpService/McpHttpServer/McpTools/McpSettings/McpHistoryStore/McpStateStore/McpToolCatalog… 共 21 个）。
`McpService` 的 `<service>` 声明（前台服务类型 dataSync|connectedDevice、stopWithTask=false）与
`FOREGROUND_SERVICE_DATA_SYNC / CONNECTED_DEVICE` 两个权限搬到 `src/main/AndroidManifest.xml`。
`McpService` 通知点击原来跳 `MiuixMcpActivity`，改为打开 Flutter 玻璃界面（用户从侧栏 MCP 页查看地址）。
`ui/project/PermissionCatalog.kt`、`PlatformPermissions.kt` 一并搬到 main（打包页权限清单数据源）。

## 3. main 源集去 Miuix 化清单

| 文件 | 处理 |
|---|---|
| `ui/BaseActivity.java`、`theme/AijsSystemBars.java`、`theme/ThemeColorManagerCompat.java`、`ui/edit/theme/Themes.java`、`ui/explorer/ExplorerView.java` | 深色判定从 `AijsMiuixThemeKt.isAijsDarkThemeResolved()` 改为 `AppThemeRepository.get(ctx).isDark()`（同一份偏好） |
| `ui/settings/SettingsActivity.java`、`AboutActivity.java`、`ui/service/ServiceStatusActivity.java`、`ui/user/LoginActivity.java`、`RegisterActivity.java` | Miuix 转发壳退役：保留类名给旧 Intent，`onCreate` 直接 finish，不再启动 Miuix 页 |
| `ui/doc/DocumentationActivity.java` | 有 url 用系统浏览器打开，否则 finish（Flutter 侧「使用手册」= GuidePage） |
| `ui/project/BuildActivity.java`、`ProjectConfigActivity.java` | 改为把「打包/项目配置请求」写进 `flutter-glass.native_packaging_request` 并拉起 Flutter 玻璃界面；Flutter `consumeNativePackaging()` 读走后打开玻璃 ProjectPage（原生 Ace 编辑器的「打包/项目设置」菜单因此仍可用） |
| 新增 `ui/FlutterGlassEntry.java` | main 侧统一「交给 Flutter」入口：`open()` / `requestPackaging(path)`，失败只提示不回退 Miuix |
| `ui/update/UpdateCheckDialog.java` | 去掉 Miuix 进度框，统一 MaterialDialog（保留 400ms 最短显示时间） |
| `ui/update/UpdateInfoDialogBuilder.java` | 删除 `showWithMiuix()`，统一 Material 自定义对话框（更新说明/历史/直接下载仍完整） |
| `network/download/DownloadManager.java` | 下载进度 UI 去掉 Miuix 分支，统一 Material 进度框 |
| `ui/common/ScriptOperations.java` | 删除「新建文件/新建文件夹/文件名输入/导入」4 个 Miuix 弹窗分支（均已有 Material 回退，回退即原行为） |
| `ui/editor/ProCodeEditorActivity.java` | 长按 TAB 说明改回 `NotAskAgainDialog`；「另存为」改用 `FileChooserDialogBuilder`（选目录）+ MaterialDialog 输入文件名，保存逻辑不变 |
| `ui/log/LogActivity.java` | 统一日志源不可用时不再转发 `MiuixLogActivity`，只 Toast + finish（Flutter 构建日志入口都走 Flutter LogPage） |
| `ui/main/drawer/DrawerFragment.java` | 返回占位空容器（旧 XML 抽屉此前已删），不再加载 Miuix 抽屉 |
| `ui/main/scripts/MyScriptListFragment.java` | 删除 `MiuixScriptListHost` 分支/字段/状态保存，统一原生 ExplorerView（保留 `PREF_MIUIX_FILE_LIST` 常量兼容旧偏好） |
| `ui/widget/BubblePopupMenu.java`、`ui/floating/CircularMenu.java`、`ui/explorer/ExplorerView.java`、`ui/shortcut/ShortcutIconSelectActivity.java` | 原就是 `Class.forName` 可选依赖，类不存在时自动回退旧实现（无需改） |
| `ui/main/MainActivity.java` | 见 §1（历史兜底界面） |

## 4. Flutter 侧改动（外置重建源）

文件：`…\autojs-pro-flutter-rebuild\lib\settings_page.dart`（唯一 Dart 改动）

- 「高级引擎设置 / 打开原生引擎设置」行删除（目标 MiuixSettingsActivity 已退役；Flutter 设置页自身承载设置项）。
- 「关于」行改为 Flutter 自带 `showAboutDialog`；「开源许可」行改为 Flutter `LicensePage`
  （main.dart 启动时已注册 aijs-editor / Shizuku 的 LicenseRegistry）——不再依赖原生 Miuix 关于/许可页。
- 「检查更新 / 更新源 / 问题反馈」仍走桥 + 原生实现（更新链路的检查/下载/安装/校验都在 Java 侧，保留）。

## 5. 页面功能对等矩阵

图例：✅ 等价（Flutter 已实现且为入口）｜🟡 原生非 Miuix（保留原生页/Activity）｜⚠️ 缺口（仅 Miuix 曾有，Flutter 无；本版先标注）

| 页面/功能 | Flutter 实现 | 状态 | 说明 |
|---|---|---|---|
| 冷启动/首页 | SplashGate + BrandSplash → Home | ✅ | SplashActivity 无条件进 GlassFlutterActivity |
| 文件页 | `FileBrowser`：浏览/排序/搜索(含正则递归)/新建(文件/文件夹/Rhino/Node 项目)/导入/多选复制剪切粘贴/重命名/删除到回收站/分享/打开方式/运行/悬浮运行/打包/快捷方式 | ✅ | 与 MiuixScriptListHost 功能对齐（dev-916/946 等已移植） |
| 示例页 | 示例库（Rhino/QuickJS/Node 三棵引擎树）+ 查看/运行/悬浮运行/打包/导入/重置 | ✅ | MiuixSampleFragment 退役 |
| 资源页 | localCatalog 资源 + 导入记录/导入资源 | ✅ | MiuixResourceFragment 退役 |
| 插件页 | 已装插件列表/启停/图标/装本地或 URL 插件包 | ✅ | MiuixPluginFragment 退役 |
| 任务页 | 运行中/定时任务、停止/取消、单任务日志 | ✅ | MiuixTaskManagerFragment 退役 |
| 日志页 | `LogPage`：统一 liveLogs 实时流 + 等级过滤/搜索/复制/分享 | ✅ | MiuixLogActivity 退役；原生 LogActivity 仅作旧入口兜底 |
| 编辑器/工作区 | 原生 Ace `ProCodeEditorActivity`（非 Miuix） | 🟡 | 多文件工作区、标签、文件树、LSP/补全；沿用 dev-907 起的原生编辑器方案 |
| 设置页 | `GeneralSettingsPage`：工作空间/外观/运行开关/Node.js 镜像/编辑器字号/运行与日志(稳定模式+数值)/回收站/关于 5 项 | ✅/⚠️ | 见 §6 缺口（录制组/补全长度/Root 无障碍等未在 Flutter 设置页） |
| 设备/服务页 | `DevicePage`：Shizuku 状态/打开/启动无障碍、修复无障碍、MCP 状态与地址、电脑连接摘要 | ✅ | MiuixServiceActivity 退役；标题栏「设备」入口 |
| 无障碍/悬浮窗/存储授权 | 抽屉 `ServiceDrawer` 三个开关 + 「更多...」 | ✅ | 权限跳转沿用系统设置 |
| MCP 服务页 | 设备页 MCP 区块：开关/局域网开关/本机+局域网 URL/客户端配置/工具清单 | ✅/⚠️ | 无「MCP 历史」页（MiuixMcpHistoryActivity）、无二维码（原 MiuixMcpActivity zxing） |
| 打包页 | `ProjectPage`：项目加载/权限/签名/图标/构建进度/产物安装分享；单文件与项目都走它 | ✅ | MiuixBuildActivity 退役；原生编辑器「打包」经 native_packaging_request 转 Flutter |
| 项目配置 | Flutter ProjectPage（项目目录）/文件页新建项目 | ✅ | MiuixProjectConfigActivity 退役；「新建项目」请在 Flutter 文件页操作（原生编辑器菜单会提示） |
| 电脑连接 | `ComputerPluginSheet`：服务器模式/主动连接/设备信息/复制地址 | ✅ | 与 dev-914 一致 |
| 搜索 | 文件页顶栏搜索（本地/递归/正则） | ✅ | MiuixSearchActivity / MiuixFileSearch 退役 |
| 悬浮控制条 | 原生 `PocFloatingBar`（非 Miuix） | 🟡 | ▶/■/停止全部/编辑/日志/关闭，可拖动/收起 |
| 关于/开源许可 | Flutter `showAboutDialog` / `LicensePage` | ✅ | 新增（本任务补齐） |
| 检查更新/更新源/问题反馈 | 桥 → 原生 Material 更新对话框/浏览器 | 🟡 | 非 Miuix；更新检查/下载/安装链路保留原生 Java |
| 终端 | 原生 `EmbeddedTerminalActivity` | 🟡 | 非 Miuix；从抽屉进入 |
| 定时任务设置 | 原生 `TimedTaskSettingActivity` + Flutter 任务页入口 | 🟡 | 非 Miuix 页面保留 |
| 桌面快捷方式 | 原生 `ShortcutCreateActivity`；文件页快捷方式菜单 | 🟡/⚠️ | 图标选择器 MiuixIconPicker 退役 → 只能默认图标 |
| 使用手册 | Flutter `GuidePage` | ✅ | 首页菜单打开 |
| 权限引导 | Flutter `permission_guide.dart` + 存储授权流程 | ✅ | 首启引导保留 |
| 登录/注册（社区账号） | 无 | ⚠️ | MiuixLogin/Register 退役；Flutter 无登录入口（社区/市场旧页本就不可达） |
| 社区/市场旧页（NodeBB/市场） | 无（用「资源/插件」替代） | ⚠️ | 旧 MainActivity 的社区/市场 Tab 属旧原生 UI，已随入口退役 |
| 文档页 | Flutter GuidePage + 文档链接走系统浏览器 | ✅ | MiuixDocumentationActivity 退役 |
| 脚本录制设置组 | 无 Flutter 入口 | ⚠️ | 音量键录制/录制提示/Root 录制输出类型（原 MiuixSettingsActivity 分组） |
| 代码补全最大长度 | 无 Flutter 入口 | ⚠️ | 原 MiuixSettingsActivity「编辑」分组 |
| Root 启动无障碍 | 无 Flutter 开关 | ⚠️ | 原 MiuixSettingsActivity「无障碍服务」分组；Shizuku 启动无障碍在设备页有 |
| 前台服务/通知权限/电池优化开关 | 抽屉部分覆盖（保持屏幕常亮、通知权限入口见设备页） | ⚠️ | 原 MiuixServiceActivity 的完整服务开关页退役 |

## 6. 缺口清单（本版先标注，未实现）

1. 设置页缺失的 Miuix 专属项：**脚本录制设置组**、**代码补全最大长度**、**Root 启动无障碍开关**；
2. **MCP 历史页**与 MCP 连接**二维码**（MiuixMcpHistoryActivity / MiuixMcpActivity 的 zxing 二维码）；
3. **登录/注册**、社区/市场旧页面（属旧产品线入口，当前 Flutter 首页无对应入口）；
4. **快捷方式图标选择器**（MiuixIconPicker 退役，创建快捷方式只能默认图标）；
5. **服务状态完整页**（MiuixServiceActivity：前台服务/通知/电池优化开关）——抽屉与设备页已覆盖常用项；
6. 原生 Ace 编辑器、终端、定时任务设置、更新对话框仍是原生 Java（**非 Miuix**，属有意保留）。

> 上述缺口都属「原 Miuix 页面独有、Flutter 无对应入口」。若要继续统一，建议按优先级补：
> 设置页录制组/补全长度 → MCP 历史 → 快捷方式图标 → 登录/注册（若产品仍需要）。

## 7. 验证

### 7.1 Flutter AOT 重建（必修，否则 APK 还是旧界面）

| 项 | 值 |
|---|---|
| 命令 | `output/flutter-poc/build-aot.ps1`（复制外置重建源 lib → Flutter 3.3.4 assemble → payload） |
| AOT 前 `libapp.so` | sha256 `e2f43ffb51615ae38c903c15b5eecdd65d83b0fd0872a0de6a1e5068a8299c97`，5,653,400 bytes |
| AOT 后 `libapp.so` | sha256 **`f265a4838259c59d3592ddc08173bd4c4e9ac78e4f8cc9aca6d0db5f14ed4258`**，5,751,704 bytes |
| `libflutter.so` | 未变（`dc44088c…`，9,936,928 bytes） |
| `payload-report.json` | 已更新；APK 内 `lib/arm64-v8a/libapp.so` 校验与上面一致 ✅ |

### 7.2 Gradle 编译/打包（本机）

- `:app:compileMiuixCompatDebugKotlin` + `:app:compileMiuixCompatDebugJavaWithJavac`：成功；
- `:app:assembleMiuixCompatDebug -PflutterPoc=true -PdevPluginUsbDiscovery=false`：**BUILD SUCCESSFUL**；
- `:app:compileMiuixCompatReleaseKotlin / JavaWithJavac`：成功（release 变体不含 Flutter 源集，见风险）；
- `:app:testMiuixCompatDebugUnitTest`：**BUILD SUCCESSFUL**，43 个测试类 308 个用例 0 失败/0 跳过
  （含从 testMiuix 搬到 src/test 的 13 个 MCP 单测与 Explorer/Plugin/Sample/Repository 等；日志 `output/flutter-poc/de-miuix-unittests-1.log`）。

> 注：期间 `engine/NodeUiActivity.java` / `NodeUiBridgeService.java` 由另一位队友（NodeUI 返回键修复）在改，
> 曾出现一次短暂的瞬时编译失败（在对方文件内，非本任务改动）；对方提交后重跑即 BUILD SUCCESSFUL。

### 7.3 APK 内容校验

| 检查 | 结果 |
|---|---|
| `aapt dump badging` launchable-activity | 只有 `com.jdkshen.aijspro.ui.splash.SplashActivity`（+ leakcanary 调试入口）；无 Miuix 主界面 |
| manifest 中 Miuix Activity/Service | 0 处（`Miuix*` 类名一个都没有） |
| `com.jdkshen.aijspro.mcp.McpService` | 已声明（dataSync\|connectedDevice） |
| dex 中 `top/yukonga`（Miuix 库） | **0** |
| dex 中 `MiuixMainNavigationHostKt / MiuixSettingsActivity / MiuixMcpActivity / MiuixBuildActivity` | 均 **0** |
| APK 体积 | 133,427,336 bytes（dev-1126 138,602,326，约 -5.2 MB） |

### 7.4 真机（K40）——由 Lead 在 dev-1128 构建后执行

- 冷启动：桌面图标 → Splash（AI.js Pro Logo）→ 直达 Flutter 玻璃首页（无 Miuix 主界面/无第二图标）；
- 逐页：文件/示例/资源/插件/任务/日志/编辑器/设置/设备(MCP/Shizuku)/打包/工作区/搜索/悬浮条/关于；
- 关键回归：原生 Ace 编辑器「打包」→ 玻璃 ProjectPage；设置→关于（本地 AboutDialog/LicensePage）；
  更新检查（Material 原生对话框）；MCP 开关启停服务；打包 APK 安装/分享。

## 7.5 K40 真机逐页验证结果（dev-1130，2026-10-20）

> 验证方式：adb 冷启动 + 逐页截图（截图在 `.artifacts/verify-dev1127/`、`.artifacts/verify-dev1130/`）。
> dev-1127 坏包修复后，dev-1128/1129/1130 为同一份 Flutter AOT + 去 Miuix 构建，以下结论在 dev-1130 上复核通过。

| 验证项 | 结果 | 证据/说明 |
|---|---|---|
| 冷启动直达 Flutter | ✅ | 桌面图标 → Splash（AI Logo）→ Flutter 玻璃首页；无 Miuix 主界面、桌面只有一个图标 |
| 文件页 | ✅ | 脚本目录文件列表、排序/搜索/新建 FAB 正常 |
| 示例页 | ✅ | Rhino / QuickJS / Node 引擎三棵示例树 |
| 资源页 | ✅ | 内置示例目录 19 项，卡片/导入图标正常 |
| 插件页 | ✅ | 本机已安装插件（象棋引擎插件）显示/开关正常 |
| 任务页 | ✅ | 运行中任务/任务两组、刷新与设备入口正常 |
| 日志页 | ✅ | 实时统一流（含 OpenCV init 行），等级过滤/搜索/复制/分享可见 |
| 本地使用指南 | ✅ | 各段说明完整 |
| 搜索 | ✅ | 文件页输入 yolo，「包含子目录/正则表达式」筛选与结果列表正常 |
| 抽屉 | ✅ | 无障碍/悬浮窗/更多、MCP、开发者调试、终端、深色主题、设置、退出 全部可见 |
| 设置页 | ✅ | 工作空间/外观/调试与脚本运行/Node.js/编辑器字号（读原生偏好 15sp）/运行与日志/关于 |
| 检查更新 | ✅ | 点击后弹原生检查升级（Material）→ Toast「已经是最新版本」 |
| 更新源 | ✅ | 显示当前生效值（自建源 http://192.168.10.7:8080/update.json） |
| 关于（Flutter） | ✅ | Flutter AboutDialog（AI.js Pro / 玻璃界面版本 / VIEW LICENSES / CLOSE） |
| 开源许可（Flutter） | ✅ | Flutter LicensePage：StackWalker/abseil-cpp/accessibility/AI.js Pro 编辑器参考 等列表 |
| 设备连接页 | ✅ | MCP 状态/局域网地址、电脑连接（ws-rpc / mDNS）、无障碍入口、Shizuku 状态、存储权限 |
| MCP 开关 | ✅ | 开→「服务运行中」+ 本机 curl `http://127.0.0.1:8765/mcp` HTTP 200；关→「服务已停止」+ 端口连不上 |
| 编辑器/工作区 | ✅ | 顶栏 `<>` 打开原生 Ace 工作区（多文件标签/文件树/工具栏） |
| 编辑器打包 → ProjectPage | ✅ | 编辑器「其他→打包单文件」→ 自动准备单文件项目并打开 Flutter「项目配置与打包」页（本任务新增的 native→Flutter 路由） |
| 终端 | ✅ | 抽屉→终端→原生终端，cwd 为 `/storage/emulated/0/脚本` |
| 无障碍服务 | ✅ | 抽屉/设备页状态正确；重装后 Android 会关闭该服务（App 有「无障碍服务已关闭…」提示），adb 重新开启后显示「已启用」 |
| 崩溃/异常 | ✅ | 多轮 logcat 扫描无 FATAL / UnsatisfiedLinkError / E/flutter |

### 本次真机验证发现的非阻塞问题/注意项

1. **重装/升级 APK 后系统会关闭本应用的无障碍服务**（Android 正常行为，非 App bug）。App 会弹提示引导重新开启；换包后若验无障碍相关功能需重新授权一次（K40 已通过 adb 恢复）。
2. **Flutter 关于/许可页文案为英文**（VIEW LICENSES / CLOSE / Licenses / Powered by Flutter）：Flutter 默认 Material 本地化缺失，未引入 `flutter_localizations`。不影响功能，后续要中文可补依赖 + `localizationsDelegates`。
3. K40 自动化期间出现过系统「要与 AI.js Pro 共享屏幕吗？」MediaProjection 授权框、通知栏遮挡、屏幕休眠等**系统级干扰**；排查为 adb 盲点误触（本 App 悬浮控制条/其它已装 AutoJS 测试 App）触发截图授权，不是页面功能问题。真机人工验收时留意即可。
4. **MCP 默认端口为 8765**（旧文档 8766 已过时）：本机地址 `http://127.0.0.1:8765/mcp` 返回 200，局域网地址返回 401（需鉴权/策略），服务本身监听正常。
5. 抽屉「开发者调试」展开后的「允许远程调试/主动连接电脑」子项未逐项点击（dev-914 已验收过该链路，本次只确认状态副标题 ws-rpc 正常）。

## 8. 回退方式（先排除不物理删，全部可回退）

1. `apps/app/build.gradle`：删除 `sourceSets` 里的 `miuix { java.srcDirs = [] ... }` 与 `testMiuix { ... }` 两块；
   恢复 4 行 `miuixImplementation` 依赖（miuix-android / compose-foundation / compose-ui / zxing）。
2. `src/miuix/AndroidManifest.xml`：用 `git show <dev-1126>:apps/app/src/miuix/AndroidManifest.xml` 恢复 Activity/Service 声明
   （McpService 声明可留在 main，不冲突）。
3. `src/miuix` 页面源码没有删除，恢复上述两步即可重新编译；`Miuix*` 类名引用在 main 中已改为反射/字符串，
   重新编入后会自动生效（反射类名保持原样）。
4. MCP 搬迁：`git mv src/main/java/com/jdkshen/aijspro/mcp src/miuix/java/com/jdkshen/aijspro/mcp`
   （PermissionCatalog/PlatformPermissions 同理），并恢复 manifest 中 Service 声明位置。
5. 入口：`SplashActivity.enterNextActivity()` 恢复 `R.bool.flutter_main_entry` 分支（src/flutterpoc/res 里该资源仍在）；
   恢复 flutterpoc manifest 的 MainActivity 回退图标（如需）。
6. Flutter：`git checkout <dev-1126> -- lib/settings_page.dart`（外置重建源），然后重跑 `build-aot.ps1`。

## 9. 风险与遗留

- **release 变体**：本次 Flutter 源集仍只在 `-PflutterPoc=true` 时挂进 **debug** 变体（沿用原 POC 结构）。
  dev 通道发布的就是该 debug 产物；`assembleMiuixCompatRelease` 能编译通过，但 release 包没有 Flutter 界面
  （Splash 会提示「需要 -PflutterPoc=true」）。若将来要发正式 release，需要把 `src/flutterpoc` + payload + embedding
  同时挂到 release 变体（版本号/签名链路不在本任务范围）。
- 原生 Ace 编辑器/终端/定时任务设置/更新对话框仍是原生 Java（非 Miuix，按既有产品决策保留）。
- 缺口见 §6；其中「设置页录制组/补全长度/Root 无障碍」是原 MiuixSettingsActivity 里仅存的功能点。
- 命名遗留：flavor/任务名仍叫 `miuix`（发布脚本依赖），但界面已与 Miuix 无关；
  `BubblePopupMenu` 等旧代码里仍有 `mMiuix*` 字段名（仅变量名/反射字符串，无 Miuix 依赖）。
