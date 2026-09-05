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

- [x] commonDebug 与 miuixDebug 构建成功。
- [x] 最终布局微调后重新构建并安装 miuix arm64 APK。
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
