# AI.js Pro — Miuix UI 试点说明与交接

日期：2026-09-05

## 0. 试点扩展（2026-09-05 下午，新增）

在核心服务页试点之后，额外迁移了 3 个低风险页面（同一 `miuix` flavor，普通变体不受影响）：

| 页面 | 文件（`apps/app/src/miuix/...`） | 分流入口 |
|---|---|---|
| 设置 | `ui/settings/MiuixSettingsActivity.kt` | `SettingsActivity` 按 `BuildConfig.MIUIX_PILOT` 跳转 |
| 关于 | `ui/settings/MiuixAboutActivity.kt` | `AboutActivity` 按 `BuildConfig.MIUIX_PILOT` 跳转 |
| 登录 / 注册 | `ui/user/MiuixLoginActivity.kt`、`ui/user/MiuixRegisterActivity.kt` | `LoginActivity` / `RegisterActivity` 按 `BuildConfig.MIUIX_PILOT` 跳转 |
| 抽屉 | `ui/main/drawer/MiuixDrawerHost.kt` | `DrawerFragment` 在 `MIUIX_PILOT` 下用反射加载 Miuix 抽屉 View（common 变体不引用） |
| 文档 | `ui/doc/MiuixDocumentationActivity.kt` | `DocumentationActivity` 按 `BuildConfig.MIUIX_PILOT` 跳转（URL extra 透传） |
| 日志 | `ui/log/MiuixLogActivity.kt` | `LogActivity` 按 `BuildConfig.MIUIX_PILOT` 跳转（复用 ConsoleView/ConsoleImpl + 清空按钮） |

主题统一：新增 `theme/AijsMiuixTheme.kt` 作为所有 Miuix 页面的单一主题入口——深浅色跟随应用内夜间模式开关（`Pref.isNightModeEnabled()`，与 `BaseActivity` 一致，不跟随系统）；主色浅色 `#009688` / 深色 `#4DD0E1`（与 `colors.xml`/`values-night` 对齐）。所有 `Button`/`TextButton` 显式使用 `buttonColorsPrimary()`/`textButtonColorsPrimary()`（Miuix 默认按钮为灰色 `secondaryVariant`，不显式使用则与开关颜色不一致——已统一为主题主色）。

抽屉说明：`DrawerFragment`（main 源集）增加 `BuildConfig.MIUIX_PILOT` 分支——通过反射调用 miuix 源集的 `MiuixDrawerHost.createView()` 返回 ComposeView（304dp），内容为 Miuix 用户卡 + 服务/录制/其他三组卡片 + 底部设置/退出按钮；`DrawerFragment` 暴露约 15 个 public 桥接方法复用原有全部逻辑（无障碍/稳定模式/通知/前台/统计/悬浮窗/音量键/夜间/主题色/连接/检查更新/设置/退出/用户区），`onResume` 经 View tag 的 Runnable 刷新 Compose 状态。同时 `MainActivity.bindViews()` 对 `setting`/`exit` 判空（Miuix 模式无这两个 id）。验证：抽屉打开、首屏各条目与 summary 渲染正常、无崩溃。截图 `.artifacts/miuix-drawer.png`。

要点：

- 设置页与旧 `preferences.xml` **共享同一个 Default SharedPreferences**（无 `Pref.java` 改动），护眼模式联动 `AccessibilityConfig`、音量键触发 `GlobalKeyObserver.init()` 等既有监听照常生效；覆盖 7 组 15 项：开关（SuperSwitch）、列表选择（SuperDropdown）、代码补全长度（SuperDialog + TextField）、主题色/检查更新/关于/许可/问题反馈（SuperArrow 复用原入口）、脚本目录（对话框 + 仅刷新/复制/移动，复用 `FileObservable`）。
- 关于页：logo + 版本 + 开发者/QQ/邮箱/GitHub/分享 + 版权；logo 连点 5 次保留 Crash Test 彩蛋（SuperDialog 确认后 `CrashReport.testJavaCrash()`）。
- 登录/注册页：Miuix TextField（密码用 `PasswordVisualTransformation`）+ 提交按钮（loading 状态）+ 行内错误提示；复用 `UserService`/`NodeBB` 网络逻辑与校验规则（邮箱格式、密码 ≥6 位等），未改动网络层。
- 每页均跟随系统深浅色，状态栏/导航栏与 Miuix 背景同步。
- 本轮构建：`:app:assembleMiuixDebug :app:assembleCommonDebug` 成功；已覆盖安装 K40（miuix arm64 APK）。
- 实机验证状态：设置页、关于页已通过 uiautomator dump 确认渲染（卡片/开关/下拉值/版本号均显示）；登录/注册页已验证编译与安装，UI 渲染待用户实机确认（入口：左上角菜单 → 用户头像）。K40 的 `uiautomator dump` 时好时坏（MIUI 限制），部分导航验证依赖人工。

## 1. 当前结论

已完成 Miuix「核心服务」页面试点，构建成功并覆盖安装到 K40。当前仍为混合架构，不是全应用更换框架，也不代表整体卡顿已解决。

- 应用包名：`com.jdkshen.aijspro`。
- 测试设备：K40，ADB 序列号 `cccc62c7`。
- 页面入口：左上角菜单 → 首页 → 核心服务。
- 普通文件列表保留原生 View，开发工作台保留 ImGui。
- 试点 APK 与普通 APK 使用同一包名，覆盖安装共享原应用数据，并非两个独立应用。

## 2. 试点实现

页面使用 Miuix 的标题栏、卡片、箭头条目和开关，支持跟随系统深浅色。移除了重复的大标题，保留简短说明。

| 条目 | 当前行为 |
|---|---|
| 无障碍服务 | 显示现有状态，点击进入系统无障碍设置 |
| 悬浮窗 | 复用现有悬浮窗管理器；缺少覆盖权限时进入系统授权页 |
| 前台服务 | 复用既有服务启动/停止接口及偏好设置 |
| 通知读取权限 | 进入系统通知读取权限设置 |
| 电池优化 | 显示是否忽略电池优化，进入系统优化设置列表 |
| 开发工作台 | 打开现有 ImGui 工作台 |

从系统设置返回时，通过 `onResume` 刷新页面状态。前台服务开关读取的是既有 Pref 配置，并非新增的服务存活检测。

## 3. 代码位置

以下路径相对于仓库根目录：

- `build.gradle`：Kotlin 升级及 Compose 编译插件依赖。
- `apps/app/build.gradle`：Compose 配置、miuix flavor、依赖和试点标记。
- `apps/app/src/miuix/AndroidManifest.xml`：试点 Activity 声明。
- `apps/app/src/miuix/java/com/jdkshen/aijspro/ui/service/MiuixServiceActivity.kt`：Miuix 页面及现有服务接口接入。
- `apps/app/src/main/java/com/jdkshen/aijspro/ui/service/ServiceStatusActivity.java`：按 `BuildConfig.MIUIX_PILOT` 跳转，普通版本仍使用原 View 页面。
- `docs/HANDOVER.md`：项目总体交接记录。

## 4. 版本与兼容范围

| 项目 | 当前配置 |
|---|---|
| Miuix | `top.yukonga.miuix.kmp:miuix-android:0.3.1` |
| Compose UI / Foundation / Runtime | 1.7.6 |
| Kotlin / Compose 编译插件 | 2.1.0 |
| Gradle / AGP | 8.9 / 8.6.1 |
| 本轮构建 JDK | 17 |
| miuix flavor 最低系统 | API 26（Android 8.0） |
| 普通 flavor 最低系统 | API 21（Android 5.0） |

所选 Miuix AAR 要求 minSdk 26，因此只提高 miuix flavor 的最低系统版本。不要直接将其作为普通版本的公共 UI 依赖。

注意：隔离的是页面、Miuix 依赖和最低系统版本；Kotlin 升级仍影响全工程。应用级 Compose 编译插件会作用于普通变体，因此公共依赖中补充了 Compose Runtime，解决普通变体缺少运行时导致的编译失败。

## 5. 构建、安装与回退

在仓库根目录执行以下 PowerShell 命令。

### 构建试点和普通版本

```powershell
& 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\java.exe' -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleCommonDebug :app:assembleMiuixDebug --console=plain
```

### 安装试点到 K40

```powershell
& 'D:\VisualStudio\Shared\Android\android-sdk\platform-tools\adb.exe' -s cccc62c7 install -r 'apps/app/build/outputs/apk/miuix/debug/app-miuix-arm64-v8a-debug.apk'
```

### 启动

```powershell
& 'D:\VisualStudio\Shared\Android\android-sdk\platform-tools\adb.exe' -s cccc62c7 shell am start -n com.jdkshen.aijspro/.ui.splash.SplashActivity
```

### 回退到普通 UI

```powershell
& 'D:\VisualStudio\Shared\Android\android-sdk\platform-tools\adb.exe' -s cccc62c7 install -r 'apps/app/build/outputs/apk/common/debug/app-common-arm64-v8a-debug.apk'
```

使用同签名的当前构建覆盖安装即可，不要先卸载或清除数据。此操作只回退 UI 变体，不会回退源码中的 Kotlin 工具链升级。

## 6. 本轮验证结果

- [x] 主界面第一阶段：Miuix 顶部导航外壳已接入，保留原生 ViewPager/RecyclerView 和脚本逻辑；非 miuix 变体仍使用原 Toolbar。
- [x] **全局颜色统一**：Miuix 顶栏/状态栏/Tab 背景统一为 `AijsMiuixTheme` 背景色（浅 `#F7F7F7`、深色模式黑色），与 Miuix 页面（核心服务/设置/抽屉）完全一致；K40 像素采样确认（状态栏/顶栏均为 247,247,247）。「顶栏改 teal 实色 → 改回 Miuix 浅色」已按统一 Miuix 方向裁定，保留。
- [x] 顶栏的抽屉、ImGui 工作台、日志和文档桥接已接入；K40 实机确认工作台与 Miuix 文档页可打开。
- [x] 修复 Miuix 抽屉不创建旧 `DrawerMenuAdapter` 时，远程连接状态回调触发空指针崩溃的问题。
- [x] commonDebug 与 miuixDebug 构建成功。
- [x] 最终布局微调后重新构建并安装 miuix arm64 APK。
- [x] 顶栏搜索已改为 Miuix 行内搜索，直接复用原 QueryEvent 过滤逻辑。
- [x] **五个 tab 全部 Miuix 化**：文件（View 列表 + Miuix 菜单/FAB）、教程（MiuixSampleFragment：搜索/分类/运行/导入）、社区（MiuixCommunityFragment：复用 CommunityWebView 全部行为）、市场（MiuixMarketFragment：卡片列表）、管理（View 任务列表，文案对齐 Auto.js Pro「运行中任务」）。
- [x] **FAB 修复与功能对齐**：teal 圆形 56dp（对齐 Auto.js Pro 圆形）、移除旧蓝色 Material FAB（`onPageShow` 的 `mFab.show()` 会把旧件带回，已 `removeView`）、`elevation=14f` 防被页面内容遮挡、跟随 `ViewPagerFragment` 显隐（教程页隐藏）、文件页=新建菜单/管理页=停止全部/社区=回复发帖（复用 `performMainFabClickFromMiuix`）。
- [x] 顶栏补齐 **ImGui 工作台** 入口（`<>`），与 Auto.js Pro 顶栏功能入口一致；验证顶栏 4 图标（搜索/日志/文档/工作台）+ 菜单。
- [x] 打包页/项目配置/定时任务保持 View 实现（表单复杂、功能稳定优先），工具栏已统一 Miuix 浅色观感。
- [x] FAB 及项目/导入/文件/文件夹展开项已全部改为 Miuix Compose，保留原创建操作回调。
- [x] 文件/文件夹更多菜单已改为 Miuix 操作面板，并在 K40 实机验证打开无崩溃。
- [x] 项目入口已接入 Miuix 运行/构建/同步/设置面板，操作复用原项目逻辑。
- [x] K40 浅色页面实际显示，主要文字和条目未出现截断。
- [x] 无障碍设置页面进入及返回。
- [x] ImGui 工作台进入及返回 Miuix 页面。
- [x] 悬浮窗关闭后状态为 false，再开启恢复为 true。
- [x] 检查当时 crash buffer，未发现本包匹配的崩溃记录；这不等于完整稳定性测试。
- [ ] 前台服务开关及通知行为。
- [ ] 通知读取、电池优化和拒绝授权等全部权限流程。
- [ ] 深色模式、大字体、横屏及其他 Android 版本。
- [ ] 进程重建、长时间后台恢复、连续切页压力测试。
- [ ] 有效的滑动/点击帧耗时测试及 Auto.js Pro 同场景比较。

本轮未修改用户的无障碍授权；悬浮窗测试后恢复开启状态。

## 7. 实机截图

截图保存在仓库本地 `.artifacts/`，该目录为忽略目录；仅复制本文或克隆仓库不一定能获得图片。

- 服务页：`.artifacts/miuix-service-pilot.png`。
- 工作台：`.artifacts/miuix-workspace.png`。

## 8. 后续建议

1. 先补齐本页权限、主题、生命周期和真实手感验证，发现问题优先修复试点。
2. 试点稳定后，再迁移普通设置列表、关于页等低风险页面。
3. 文件管理页需要单独验证排序、文件名显示、长列表滑动和操作菜单，不能只替换外观。
4. 暂时保留 ImGui 工作台及脚本引擎；不要将 UI 库迁移扩大为引擎重写。
5. 是否迁移整个应用外壳，应依据实机体验、兼容成本和测量结果决定，而非仅凭静态截图。

## 9. 下一位开发者注意事项

仓库已有大量其他修改，应保留并区分本轮试点范围。不要直接清理工作区、覆盖历史修改或把所有变更一起提交。本说明仅记录已完成工作与待验证项，不表示全应用迁移已经获准或完成。

## 10. Auto.js Pro / ImGui / Miuix 差异清单（2026-09-05）

### 已对齐

- Miuix 顶部导航外壳、青绿色双层导航及五个主分页。
- 文件夹优先、文件随后的单列表，文件名、大小、修改时间、运行和更多入口保留。
- 路径/项目栏从 RecyclerView 拆出后固定，滑动只作用于文件列表。
- 项目页删除旧式大项目卡，改为单行项目名、返回、项目设置、排序和筛选。
- 服务、设置、关于、日志、文档、登录、注册和抽屉已有 Miuix 页面或桥接。

### 视觉仍未对齐

- 文件类型图标已将 `project.json` 设为黑色代码图标、`main.js` 设为绿色可运行图标，仍需要在更多扩展名下校对。
- 项目名的可见宽度、省略位置、图标间距、文件行高和次要文字灰度还需逐像素调整。
- FAB 与展开后的四个次级动作均已迁移到 Miuix/Compose；动画尚未与 Auto.js Pro 做完整同场帧耗时测试。
- 深色、大字、横屏及刘海/挖孔屏安全区尚未完整对齐。

### ImGui 已有、Miuix 尚未完整承接的能力

- 脚本搜索、筛选、排序、刷新和面包屑逐级跳转。
- 新建脚本、新建目录和全部停止；重命名、删除和文件更多菜单已由 Miuix 面板承接。
- 示例搜索、筛选、排序、运行、导入及逐级返回。
- 资源导入、打开、上传、筛选和详情。
- 插件管理、搜索、刷新和帮助。
- 运行任务的打开、取消、搜索、刷新及定时任务创建。
- 旧 ImGui 终端、图像/调试工具仍无 Miuix 等价页面；在替代实现完成前只隐藏用户入口，不删除底层代码。
