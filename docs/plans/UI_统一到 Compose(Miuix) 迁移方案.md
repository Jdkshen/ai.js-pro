# UI 统一到 Compose(Miuix) 迁移方案

> 制定日期：2026-09-12
>
> 基线：`chore/upgrade-gradle-8`，Miuix 0.3.1 + Compose 1.7.6
>
> 目标状态：**新页面一律 Compose(Miuix)；只有编辑器 / 终端 / 悬浮窗这类性能敏感控件保留 View，作为 Compose 页面里的 View 岛。**

## 1. 现状盘点（2026-09-12）

| 部分 | 规模 | 说明 |
|---|---:|---|
| `apps/app/src/main/java` | 273 文件 / 33.8k 行 | 旧 View/XML 界面 + 引擎/网络/打包等非 UI 代码 |
| ├ `ui/` 中的旧界面 | 130 文件 / 20.9k 行 | Java + AppCompat + Material Dialogs |
| `apps/app/src/miuix`（Compose） | 40 文件 / 9.7k 行 | Kotlin + Compose + Miuix 组件（主界面已覆盖） |
| `res/layout` | 90 个 XML | 旧界面布局 |
| `BuildConfig.MIUIX_PILOT` 分支 | 26 处（改造前） | 双轨开关 + 反射桥 |
| product flavor | `miuix` / ~~`common`~~ / ~~`coolapk`~~ + `compat`/`lite` | channel 维度已只剩 miuix |

已经 Compose 化的页面：主界面导航/抽屉/FAB、搜索、日志、设置、关于、服务状态、打包页、市场、社区、文档、示例、资源、任务管理、MCP（含历史页）、登录/注册、更新弹窗三件套（检查中/新版本/下载进度）。

结论：**不是"重写"，而是"拆双轨 + 迁尾巴"**。

## 2. 第 1 步：退役 legacy 轨道（已完成，本方案同批提交）

1. **删除 `common` / `coolapk` flavor**（`apps/app/build.gradle`）
   - CI（`.github/workflows/android.yml`）与 `release.ps1` 一直只用 `MiuixCompat*` / `MiuixLite*`，删除后 `MIUIX_PILOT` 在所有变体恒为 `true`；
   - `CHANNEL` 仍保留在 miuix flavor 上（值为 `"common"`），避免影响历史代码。
2. **main ↔ miuix 的反射桥改为编译期直连**（miuix 源集随 flavor 成为唯一界面线后，反射的理由已消失）：

| 位置 | 改造前 | 改造后 |
|---|---|---|
| `App.kt` | `Class.forName("…McpAutoLifecycle")` | `registerActivityLifecycleCallbacks(McpAutoLifecycle(this))` |
| `DownloadManager` | 反射包装 `MiuixDownloadProgressDialog`（带 Material 回退） | 直接调用；仅 `context` 非 Activity 时回退 Material |
| `UpdateCheckDialog` | `Class.forName("…MiuixUpdateCheckDialog")` | 直接调用 `show/dismiss` |
| `UpdateInfoDialogBuilder` | `Class.forName("…MiuixUpdateDialog")` | 直接调用 `MiuixUpdateDialog.show(...)` |
| `DrawerFragment` | `Class.forName("…MiuixDrawerHost")` | `MiuixDrawerHost.createView(this)` |
| `MainActivity` | 反射创建 `MiuixMainNavigationHost` / `MiuixMainFabHost` / `MiuixTaskManagerFragment` / `MiuixSampleFragment` / `MiuixPluginFragment` / `MiuixResourceFragment` | 全部直接 `new` / 静态方法调用；`if (!MIUIX_PILOT) return` 守卫删除 |

验收：`:app:assembleMiuixCompatDebug` + `:app:testMiuixCompatDebugUnitTest` 通过；Mi8 真机冒烟（Miuix 导航栏/文件列表/FAB 正常、更新弹窗为 Miuix 样式、logcat 无 FATAL）。

## 3. 第 1.5 步：删除死代码（进行中）

### 已完成（2026-09-12，提交见 git log “UI 统一（二）”）

7 个"转发壳"Activity 的 legacy body 已删完，改成只做转发的空壳（保留类名/常量/静态方法，外部调用点不用改）：

| 转发壳 | 目标页 | 保留的对外契约 |
|---|---|---|
| `ui.doc.DocumentationActivity` | `MiuixDocumentationActivity` | `EXTRA_URL`；`SINGLE_TOP` 转发（已在文档页时走目标页 `onNewIntent` 换页） |
| `ui.settings.AboutActivity` | `MiuixAboutActivity` | — |
| `ui.log.LogActivity` | `MiuixLogActivity` | 类名（脚本 `class.console`、编辑器菜单按类启动） |
| `ui.service.ServiceStatusActivity` | `MiuixServiceActivity` | 类名 |
| `ui.user.LoginActivity` / `RegisterActivity` | `MiuixLoginActivity` / `MiuixRegisterActivity` | 类名 |
| `ui.project.BuildActivity` | `MiuixBuildActivity` | `EXTRA_SOURCE`（键名 = 类名 + `.extra_source_file`，不能改） |

真机验证（Mi8）：逐个 `am start` 这 7 个入口，`topResumedActivity` 全部落到对应的 `Miuix*` 页面。

### 待做

- ~~`ui.settings.SettingsActivity`~~ 已于 2026-09-12 完成：`selectThemeColor` 旧主题色选择器随 legacy 设置页一起删除
  （Miuix 抽屉自带「主题」分组：暗色/跟随系统），DrawerFragment 里引用它的失效菜单项一并移除；
  旧资源 `activity_settings/activity_about(_items)/activity_build/activity_documentation/activity_log/activity_login/activity_register/activity_service_status.xml`
  与 `res/xml/preferences.xml` 已删；
- `MainActivity` 的 legacy 分支残留、`DrawerFragment` 的 `fragment_drawer` 回退分支与其私有 helper（下一个提交）；
- 全仓 `MIUIX_PILOT` 已退场：`BuildConfig` 字段删除（2026-09-12），代码里没有任何判断，只剩 Miuix 源集里几处历史注释；
  同时删掉 `MainActivity` 里已成空壳的 `syncStatusBarWithAppBar()`。

验收：`assembleMiuixCompatDebug` + 单测 + 真机点检（入口转发 + 页面渲染）确认没有页面变空白。

## 4. 第 2 步：迁移仍以 View 形态存在的页面

| 类别 | 目标 | 理由 |
|---|---|---|
| **A：建议迁移到 Compose** | `explorer/ExplorerView` 列表与菜单宿主、设置子页（定时任务等）、`CodeGenerateDialog`、`EditorMenu`、`DebugToolbarFragment`、任务列表相关 View | 结构简单、Compose 组件已就绪；迁完可删大量 adapter + layout |
| **B：保留 View（Compose 里的 View 岛）** | `ProCodeEditorActivity` + `edit/editor/*`（`CodeEditText`/`EditorView`，合计 150k+ 字符的自定义输入控件）、`EmbeddedTerminalActivity` 终端视图、`floating/*` 悬浮窗 | 语法高亮、光标/输入法、终端 I/O 在 Compose 里重做成本远超收益；只把外围壳、菜单、对话框换成 Miuix |
| **C：壳层** | `MainActivity`（ViewPager + AppBarLayout + DrawerLayout） | 可以继续用 View 壳装 Compose 页面；若后续要换 `Scaffold`，单独排期 |

每个页面迁移的固定流程：Compose 实现 → 真机点检（浅色/深色/大字/横屏）→ 相关单测或截图留档 → 删旧 View 与 layout。

## 5. 第 3 步：依赖与文档收尾

- 移除 `com.afollestad.material-dialogs`（0.9.2.3）、`com.google.android.material:material:1.1.0-alpha01`、`third-party/MutableTheme`（主题并入 `AijsMiuixTheme`）；
- `docs/architecture/项目说明.md` 更新"UI 边界"一节；`docs/README.md` 索引同步；
- 增加静态护栏：脚本检查 `src/main` 中不再新增 `MaterialDialog` / `android.widget.` 布局用法（CI 里跑，防回退）。

## 6. 已知坑（本项目已验证）

- ComposeView 放进 `Dialog` 时必须挂在 `ComponentActivity` 上（`ComponentDialog` 自带 `ViewTreeLifecycleOwner`），裸 `android.app.Dialog` 会崩；
- Miuix 不自动挂弹窗宿主：每页要恰好挂一次 `MiuixPopupUtil.MiuixPopupHost()`；
- `MiuixPopupUtil.dismissDialog(show)` 才是关闭对话框的正规方式，只把 `show=false` 不会隐藏；
- `AndroidView` 岛注意测量与生命周期（编辑器键盘弹出、滚动嵌套）；
- 删除 flavor 后，`BuildConfig.MIUIX_PILOT` 仍存在于生成的 BuildConfig 中，旧判断不会编译报错——容易留下"看着像分支"的死代码，第 1.5 步要主动清。

## 7. 每次改动的验收清单

1. `:app:assembleMiuixCompatDebug`、`:app:testMiuixCompatDebugUnitTest`、`:app:testMiuixLiteDebugUnitTest`、`:app:lintMiuixCompatDebug`；
2. Mi8 真机冒烟：主界面（导航栏/文件列表/FAB）、抽屉、设置页、检查更新弹窗、打包页脚本保护五档、日志页；
3. 打包链路 e2e（脚本保护档位 + 产物能跑）；
4. release 构建时 `apksigner verify` + 与 `project-versions.json` 一致的版本号。
