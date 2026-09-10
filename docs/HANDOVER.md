# AI.js Pro — 交接文档 (HANDOVER)

## 当前权威状态（2026-09-09）

- **Miuix 是主界面**；旧 ImGui 工作台、JNI/C++ 渲染桥和 `libautojs_imgui.so` 已删除。未来如果增加 ImGui，只作为 QuickJS 可调用的独立悬浮窗 API，不恢复旧主界面。
- 编辑器和终端已独立到 `ui.editor` / `ui.terminal`；多标签可直接关闭。YOLO 示例只保留 OpenCV DNN 路线，不引入 NCNN 或 ONNX Runtime。
- MCP 当前公开 22 个工具；手机开启一次写入授权后可直接应用，保留 Diff、冲突校验、自动备份和历史回退。
- Windows 中文检出路径会使 JDK/Gradle 参数文件错误解码，表现为单测 `ClassNotFoundException`。统一使用 `powershell -ExecutionPolicy Bypass -File tools/test-miuix.ps1`；脚本会临时映射 ASCII 盘符并自动清理。
- 新建脚本和新建项目默认 QuickJS；已有无标记脚本继续 Rhino。支持文件首行 `// @engine quickjs|rhino`、项目顶层 `engine` 和 `scripts.<path>.engine`，文件指令优先。
- 构建已拆为 `MiuixCompat*` 与 `MiuixLite*`。`:engine-rhino` 只进入 compat；lite 不注册 Rhino 执行引擎，但暂留 `:rhino-language` 供编辑器 Token/AST 使用。完整边界和命令见 `docs/architecture/ENGINE_FLAVORS.md`。
- `tools/test-engine-flavors-device.ps1` 已在 K40 通过 compat/lite 矩阵，覆盖 QuickJS 基础模块、threads/events、UI/floaty 真实创建、Rhino 23 项兼容及 lite 拒绝路径。剩余移除门禁见 `docs/architecture/QUICKJS_MIGRATION_MATRIX.md`。
- 本节与 `docs/REMAINING_WORK.md` 是当前结论；本文后续日期更早的内容仅作历史记录，冲突时以本节为准。

## Miuix 脚本 MCP 与示例页（2026-09-06，最新）

- 抽屉“开发”分组新增“**MCP 服务**”。Miuix 页面提供启动/停止、连接地址、二维码、令牌、端口/局域网/操作目录设置、调用历史，以及编辑和执行授权。
- 服务默认监听 `127.0.0.1:8788/mcp`。同一手机的 MT 客户端直接使用该地址；电脑端项目配置统一使用 USB Host 端口 `18790`（`adb forward tcp:18790 tcp:8788`）。本机兼容模式默认开启，loopback 可免令牌；错误令牌仍拒绝，局域网始终要求 Bearer 令牌。
- 当前共 22 个工具：脚本/示例分页读取、递归搜索及游标续页、运行任务状态/异常/停止、APK 全局日志、双引擎 API 枚举/探测，以及私有工作区的打开/读取/编辑/删除/Diff/应用。默认只读；手机端开启“允许编辑并应用”后，`workspace_request_apply` 会直接应用，应用前校验原文件并备份，历史页可查看和安全回退。
- 编辑和运行授权只在本次服务运行期间有效，停止服务自动撤销。路径、Host/Origin、正文/请求头、JSON 深度、文件大小、工作区总量和并发均有限制；支持有界 chunked 请求、宽松 `Accept` 以及 `2024-11-05`、`2025-03-26`、`2025-06-18` 客户端版本。
- 教程/示例页已统一为 Miuix：后台建立资产索引，支持搜索、全部/JavaScript/文件夹/其他文件筛选、查看、运行和原子导入，保留原有示例数据与脚本执行逻辑。
- 验证：Miuix Debug APK 构建成功；当前 6 个测试类共 52 个 JVM 测试通过（含 HTTP 传输、22 工具清单、工作区和示例目录）。K40 的历史快照已验证无令牌 loopback 初始化、搜索续页、APK 日志、chunked 请求和工作区读取/Diff；当前工具集仍需在 K40 执行完整客户端回归。
- MT 报错 `IllegalArgumentException: name is empty` 是客户端自定义请求头中存在空白“名称”行，发生在 OkHttp 发包之前；删除整条空白请求头即可。本机兼容模式不需要为了占位而新增请求头。
- 详细连接、工具和安全说明见 `docs/MCP_SCRIPT_SERVICE.md`。本功能只在 `miuix` flavor 存在，普通 flavor 不注册页面或服务。

## Miuix 核心服务页试点（2026-09-05，最新）

- 已实现并覆盖安装到 K40（cccc62c7），包名和数据目录不变。入口：左上角抽屉 → 首页 → 核心服务。当前由 Miuix/原生页面组成，ImGui 工作台已从源码和 APK 移除。
- 新增 `miuix` channel flavor；页面与清单位于 `apps/app/src/miuix/`。`ServiceStatusActivity` 仅在 `BuildConfig.MIUIX_PILOT` 为 true 时跳转；common/coolapk 保留原 View 服务页。
- 固定依赖 `top.yukonga.miuix.kmp:miuix-android:0.3.1`，Compose UI/Foundation 1.7.6。该 Miuix AAR 要求 minSdk 26，故仅 miuix flavor 提高到 26；普通版本仍为 21。根 Kotlin / Compose 编译插件升级到 2.1.0，影响全工程编译；app 公共依赖补充 Compose runtime 1.7.6，保证非试点变体也能编译。
- 页面复用既有悬浮窗管理器、前台服务及 Pref；权限页返回通过 onResume 刷新。支持跟随系统深浅色（实机本轮仅验证浅色）。无障碍是系统设置入口，不伪装成可直接授权的开关。
- 构建验证：`:app:assembleCommonDebug :app:assembleMiuixDebug` 成功；布局微调后再次构建 miuix 成功，arm64 APK 已重新安装。Gradle 8.9 / AGP 8.6.1 / JDK 17 保持不变。
- K40 验证：页面显示、无障碍设置往返、悬浮窗关闭并恢复开启通过；本轮 crash buffer 未发现本包匹配崩溃记录。旧 ImGui 工作台回归结果只属历史快照，当前 APK 已无该入口。未进行前台服务开关、所有系统权限、深色/大字体及性能基准全覆盖，不宣称已达到 Auto.js Pro 流畅度。
- 实机截图：`.artifacts/miuix-service-pilot.png`；工作台跳转截图：`.artifacts/miuix-workspace.png`。APK：`apps/app/build/outputs/apk/miuix/debug/app-miuix-arm64-v8a-debug.apk`。
- 历史回退 UI：构建并覆盖安装 commonDebug（同包名同签名，无需卸载）。该试点结论已被“Miuix 为主界面”的现状取代。

## 最新实机对齐状态（2026-09-05，优先于下文历史记录）

- 用户要求主动在 K40（cccc62c7）打开 Auto.js Pro 与 AI.js Pro 对照截图、交互及滑动，发现差异后完成修改、构建、安装和复核。
- 延续 Miuix + 原生 View 混合架构：普通文件列表和成熟业务逻辑仍由原生 View 承载；ImGui 工作台已移除。
- 启动直接进入脚本列表，顶部五个分页；原服务首页在抽屉中保留。主色已为 #009688，不再采用下文历史记录中的浅色菜单首页。
- 文件夹优先、文件随后，统一排序规则及升降序，移除第二条文件分类排序栏。根目录额外的“示例代码”为应用虚拟示例入口，不能算成读取实际目录不一致。
- JS 文件显示完整文件名（含 .js）、居中的黑底白色代码图标、大小与修改时间两行；行点击编辑，右侧运行和更多。文件行最小高度 66dp，K40 测得 183px（含分隔线）。
- ScrollAwareFABBehavior 在动画开始时更新目标隐藏状态，方向变化时取消旧动画，避免每个滚动回调反复重启动画。
- 最新截图：.artifacts/aijs-integrated.png；构建 :app:assembleCommonDebug 成功并已覆盖安装 K40。
- 滑动性能比较尚未形成有效结论：参考 Pro 的 gfxinfo 仅返回极少帧，SurfaceFlinger --latency 未返回逐帧数据；不能据此宣布两者同样流畅，也不能据 0 帧推断渲染框架。需要有效的同场景帧轨迹或连续画面进一步验证。
- 待继续对齐：普通非 JS 文件的参考图标、长文件名可见宽度、辅助文字层次、路径栏逐级导航、Miuix 弹层/菜单一致性，以及滑动的有效对比。

> 生成日期：2026-09-05
> 交接对象：Codex / 后续开发者
> 当前分支：`chore/upgrade-gradle-8`（基于 `chore/reorganize-project-layout`）

---

## 1. 项目是什么

**AI.js Pro**（包名 `com.jdkshen.aijspro`，应用名「AI.js Pro」，当前版本 `1.0.2` / versionCode 465）：
- 基于 **Auto.js 4.4.1（Stardust）** 源码的定制增强版
- 双引擎：**Rhino + QuickJS**（JNI 桥接，`modules/engine`）
- 增强：Miuix 主界面、OpenCV 5.0 DNN / YOLO、Native Frame、Shizuku、悬浮窗重构（旧 ImGui 工作台已移除）

> ⚠️ K40 上另装有 **Auto.js Pro（`org.autojs.autojspro`，AutoX 商业版 9.3.11）**，与本项目同屏易混淆，勿改错包。本项目 UI 对标它的 Material 3 风格（但为原生 View 实现，非 Flutter）。

### 模块结构
```
apps/app       主应用（UI/打包/悬浮窗/定时任务等）
apps/inrt      运行时模板（打包成 assets/template.apk）
modules/       autojs(引擎) / automator(无障碍) / common(工具)
third-party/   EnhancedFloaty / MutableTheme / settingscompat / RootShell / ColorPicker / ApkBuilder / multi-level-listview
```

---

## 2. 构建环境

| 项 | 值 |
|---|---|
| JDK | 17（`JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot`），也可用 21 |
| Gradle Wrapper | **8.9**（bin 发行版，本地 `~/.gradle/wrapper/dists` 已有缓存） |
| AGP | **8.6.1** |
| Kotlin | **1.9.24** |
| compileSdk / buildTools | **35 / 34.0.0**（已安装到 `C:/Android`：platforms;android-35、build-tools;34.0.0） |
| minSdk / targetSdk | 21 / 28 |
| SDK 路径 | `local.properties` → `sdk.dir=C:/Android`（NDK r26d、platform-tools 在此） |

### 常用命令
```powershell
.\gradlew.bat :app:assembleCommonDebug --no-daemon   # 主应用（arm64/v7a/x86 三 ABI）
.\gradlew.bat :inrt:assembleDebug --no-daemon          # inrt 运行时
.\build-common-debug.ps1 -SkipNative                   # 一键构建（跳过 .so）
```

### 设备
- K40（Redmi M2012K11AC / alioth）：`cccc62c7`
- adb：`D:\VisualStudio\Shared\Android\android-sdk\platform-tools\adb.exe`
- 安装：`& $adb -s cccc62c7 install -r apps\app\build\outputs\apk\common\debug\app-common-arm64-v8a-debug.apk`
- 启动：`am start -n com.jdkshen.aijspro/.ui.splash.SplashActivity`（**MainActivity 未设 exported**，Android 12+ 禁止 shell 直接启动）

---

## 3. 本次大改动（Gradle 8 + M3，95 文件 / +967 -907）

### 3.1 构建链升级（Gradle 4.10.2 → 8.9）
- `gradle-wrapper.properties` 改 8.9-bin；根 `build.gradle`：AGP 8.6.1 + Kotlin 1.9.24，移除 ButterKnife 插件
- `project-versions.json`：compile 35 / buildTools 34.0.0 / 版本名 1.0.1（从 1.0.0 变更）
- `gradle.properties` 关键项（**不要轻易改**）：
  - `android.useAndroidX=true`、`android.enableJetifier=true`（老库仍需转换）
  - `android.overridePathCheck=true`（中文路径必需）
  - **`android.nonTransitiveRClass=false`**：老代码通过模块 R 引用依赖资源（如 `R.style.Theme_AppCompat_Light`）
  - **`android.nonFinalResIds=false`**：`switch (R.id.xxx)` 需要编译期常量（AGP 8 默认非 final 会报"需要常量表达式"）
- 根 `build.gradle` 的 `configurations.all { resolutionStrategy.force ... }` 已升级为 M3 兼容集：
  material **1.12.0**、appcompat **1.7.0**、core **1.13.1**、fragment 1.6.2、activity 1.8.2、recyclerview 1.3.2、preference 1.2.1 等
  - ⚠️ `drawerlayout` 强制 **1.1.0**：1.2.0 的 `Openable.open()` 签名变了，与 `ImGuiWorkspaceDrawer.open()` 冲突（已改名 `openDrawerSurface()` 双保险）
- 根 `build.gradle` 末尾 `subprojects { afterEvaluate { ... } }` 统一 **Java/Kotlin jvmTarget = 1.8**（否则 Kotlin 默认 17 与 Java 1.8 冲突）
- 使用 `BuildConfig` 的模块（app/inrt/autojs/automator/common）都加了 `buildFeatures { buildConfig true }`
- `settingscompat` 的 `android.getBootClasspath()` javadoc 任务已删除（AGP 8 移除此 API）

### 3.2 注解框架迁移（AA + ButterKnife 已完全移除）
- **AndroidAnnotations（~200 处 / 25 文件）** → 原生生命周期：
  `@EActivity/@EFragment/@EViewGroup` → `onCreate/onCreateView` + `findViewById`；
  `@AfterViews` → 手动调用；`@Click/@CheckedChange` → `setOnClickListener/setOnCheckedChangeListener`
- **ButterKnife（~160 处 / 18 文件）** → `findViewById` 手动绑定
- `bindItemClick(Object)` 反射机制 → **`com.stardust.app.OperationItemClickListener`** 接口
  （`OperationDialogBuilder`/`OptionListView` 改用接口；`CircularMenu`、`CommunityWebView` 实现它）
- Manifest 中 17 处 `XxxActivity_` → 真实类名
- **⚠️ 布局文件陷阱**（已修复但需警惕）：`activity_main.xml` 的 `DrawerFragment_`、`activity_edit.xml` 的根 `<EditorView_>` 是迁移时漏网的生成类引用，会导致 `ClassNotFoundException` 崩溃——**搜索 `_` 后缀类引用时应包含 res/layout**
- Kotlin synthetic 仅 2 文件（ImageText/MarketFragment）已迁 `findViewById`；`kotlin-android-extensions` 插件已全部移除
- `@UiThread` 在 `JsDialog.java` 是 `androidx.annotation.UiThread`（非 AA，勿动）
- 删除了无用的 Glide `kapt`（无 @GlideModule）；如需 Glide 注解需加 `apply plugin: 'kotlin-kapt'`

### 3.3 M3 UI 改造（本轮重点）
- 主题：`Theme.Material3.DayNight.NoActionBar`（styles.xml `AppTheme`），新增 M3 色板
- **主色 = 青绿 `#00838F`**（对标 Auto.js Pro；`colors.xml` + `values-night/colors.xml`）
- 新增 styles：`HomeQuickCard`/`HomeItemCard`/`HomeBigCard`（CardView.Filled 圆角 14-16dp）、`MainToolbarStyle`（浅色大标题 22sp 深色图标）、`HomeToolbarTitle`
- **首页（MainActivity）**：
  - 浅色 surface 大标题栏 + 顶部**图标**（menu 已加 `app:iconTint="?attr/colorOnSurfaceVariant"`，原来白图标浅色栏看不见）
  - **TabLayout 已 `visibility=gone`**（首页=纯菜单规格；ViewPager 保留！页签切换入口在抽屉，`MainActivity.showPage(int)`）
  - 菜单卡片：大卡「核心服务」(item_home_big_card)、两列小卡「无障碍/悬浮窗」(item_home_small_card)、列表「开发工具/退出 AI > 」(item_home_row)、底部版本号
  - 图标：`ic_chevron_right.xml` 为新增 vector（`ic_keyboard_arrow_right_black_24dp` 不存在）
- **核心服务页 `ui/service/ServiceStatusActivity`**（新，已注册 Manifest）：
  M3 卡片列表：无障碍(跳设置)/悬浮窗(直接切换)/前台服务(开关,Pref 持久化)/通知权限/电池优化；`Pref` 新增 `setForegroundServiceEnabled`
- **脚本列表卡片化**：`script_file_list_file.xml`、`script_file_list_directory.xml`、`file_choose_list_directory.xml` → MaterialCardView + HomeItemCard；分类/市场/打包/项目配置/定时任务页 CardView 全部升级
- 抽屉：`drawer_menu_item.xml` M3（52dp、图标 20dp、`colorOnSurface` 系）；抽屉「其他」组新增 教程/社区/市场/管理（调 `showPage`）
- 深色模式：`values-night/colors.xml` 完整 M3 深色色板（自动跟随，应用内切换兼容）
- 状态栏：MainActivity 覆写 `shouldApplyThemeColorToStatusBar()=false` + `syncStatusBarWithAppBar()`（浅色+深图标）
- 轻页面配色清理：about/login 硬编码色 → `?attr/colorOnSurface*`

### 3.4 构建脚本
- `build-common-debug.ps1` / `release.ps1`：移除 JDK 17 `--add-opens/--add-exports` hack 与 `--max-workers=1`（Gradle 8.9 不再需要）
- 启动入口为 **Splash → MainActivity**；旧 ImGui 工作台及入口均已移除。

### 3.5 Miuix 第三页（资源）
- 主导航第三页已从「社区 WebView」改为 `ui/resource/MiuixResourceFragment`，显示名同步改为「资源」。
- 数据与 ImGui 资源页共用相同约定：内置数据读取 `assets/sample`，导入到脚本目录的「下载资源」，本机上传读写「我的资源」。
- 显示层已对齐 Auto.js Pro：紧凑资源卡片、来源/分类/大小元数据、下载/已导入状态、底部「全部/上传/我的」和原页弹出详情。
- 顶栏搜索现在进入当前资源页的内联搜索状态，不再跳转或叠加旧式搜索弹窗。
- K40 真机滑动检查：206 帧，jank 2 帧（0.97%），50/90/95/99 分位为 11/15/16/17 ms。

---

## 4. 遗留问题 / 注意点

1. **MainActivity 无 `android:exported`**：若需要 shell 直启，加 `android:exported="true"`（仅调试）
2. `Preview`/编辑器相关布局仍有硬编码颜色（`editor_view`、`debug_bar`、`dialog_*` 等），深色模式下对比度可接受但未 M3 化
3. 设置页 `SettingsActivity` 仍是老 `android.preference` 列表（M3 主题观感一般，未重排）
4. 抽屉 header（fragment_drawer.xml 顶部）未 M3 化
5. 图标：首页入口仍用成品彩色图标（`ic_service_green` 等），Auto.js Pro 为单色线稿；后续可换 M3 风格
6. `org.autojs.autojspro`（Auto.js Pro 9.3.11）与本项目同装 K40，测试前先 `am force-stop` 它
7. MCP：`bin.mt.plus`（MT 管理器）在 K40 提供 "MT APK MCP"（`http://192.168.10.9:8787/mcp`）——APK 需先放入 MT 的 apks 索引才能在 MCP 打开（`mt_apk_list_available_apks` 为空是正常的）

## 5. 下一步候选（按优先级）

1. SettingsActivity → M3 分组卡片菜单（替换老 Preference）
2. 抽屉 header / 编辑器页 M3 化（硬编码色清理）
3. 图标统一 M3 线稿（无障碍/悬浮窗/开发工具/退出）
4. 版本号发布：`project-versions.json` 已升至 `1.0.2/465`；剩余：在 GitHub 配置 4 个签名 Secrets 后触发 Release
5. 收尾：`git commit`（建议 message：`feat: upgrade to Gradle 8.9 + Material 3 UI`）

## 6. 快速定位文件

| 功能 | 文件 |
|---|---|
| 首页逻辑 | `apps/app/.../ui/main/MainActivity.java` |
| 首页布局 | `apps/app/src/main/res/layout/activity_main.xml` |
| 卡片 item | `item_home_big_card/small_card/row.xml` |
| 核心服务页 | `.../ui/service/ServiceStatusActivity.java` + `activity_service_status.xml` |
| 主题/色板 | `res/values/styles.xml`、`colors.xml`、`values-night/colors.xml` |
| 依赖强制版本 | 根 `build.gradle` → `configurations.all` |
| 版本号 | `project-versions.json` |
