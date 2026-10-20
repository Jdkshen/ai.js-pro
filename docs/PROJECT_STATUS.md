# AI.js Pro 项目现状与接手说明

> 更新日期：2026-10-19（dev-1112）
> 当前 HEAD：`45fae285`（`main`，与 `origin/main` 同步）
> 应用包名：`com.jdkshen.aijspro`
> 仓库版本：`1.0.2`（`versionCode 465`，见 `version.json` / `project-versions.json`）
> dev 更新通道：**dev-1112**（`AI-js-Pro-Flutter-dev-1112.apk`，约 132.1 MB，K40 已装）

> **说明**：仓库里的 `versionCode 465` 是正式构建基线；日常迭代走 dev 更新通道（当前 dev-1112）。
> 两者不要混用：发布脚本按更新源版本递增，`project-versions.json` 的 465 只在正式 release 时更新。

本文是当前项目的简明事实入口。`docs/plans/` 和 `docs/reports/` 记录的是阶段方案与历史测试，不应代替本文判断当前实现状态。
> 最新执行记录以 [`docs/reports/2026-10-18_收尾交接_给Codex_v2.md`](reports/2026-10-18_收尾交接_给Codex_v2.md) 末尾的“2026-10-19 20:30 更新（dev-1112）”为准。

## 1. 当前结论

AI.js Pro 是从 Auto.js 4.4.1 演进而来的 Android JavaScript IDE 与自动化运行时，目前采用以下架构：

- **界面双轨**：仓库默认构建仍是 Miuix + 原生 View（`:app:assembleMiuixCompatDebug`）；dev 更新通道自 dev-905 起发布 **Flutter 玻璃界面**构建（`-PflutterPoc=true`，入口 `GlassFlutterActivity`），桌面同时保留「AI.js Pro（Miuix 回退）」入口。Flutter 代码位于 `apps/app/src/flutterpoc/`，默认不编入发布外的普通构建。
- **三引擎并存**：Rhino（旧 Auto.js 兼容，默认）、QuickJS（首行 `// @engine quickjs`）、Node24（`"nodejs";` 入口，真实 Node v24.22.0 / ABI 137，运行在独立的 `:node24` 进程）。
- **多进程执行**：Flutter POC 把普通脚本放进 `:script` 进程（`ScriptHostService`/`PocScriptHost`），带 `lib/*.so` 或 `.dex` 的 native 项目放进 `:native` 进程（`NativeScriptService`）；主进程只做 UI 与 IPC，脚本崩溃不再带崩主 App。
- **ImGui**：旧 ImGui 工作台已彻底删除；现有 ImGui 只作为 QuickJS 可调用的独立悬浮窗 API，不恢复旧主界面。
- **MCP 已接入脚本开发与调试**：默认端口 **8765**（被占用时 8765–8795 顺延，绑 `0.0.0.0`，**dev-1176 起完全免令牌**），支持脚本/示例读取、搜索分页、任务状态、异常与 APK 日志、工作区 Diff/应用/回退。
- **小米无障碍快速开启**：首次通过 Shizuku、ADB 或 Root 授予 `WRITE_SECURE_SETTINGS`，以后直接写入安全设置；不可用时才回退系统设置。
- **插件化**：象棋识别/棋规/算棋在 `plugins/xiangqi-plugin`，打包模板在 `plugins/apkbuilder-plugin`；主 APK 不再内嵌这两部分。

## 2. 仓库结构

| 路径 | Gradle 模块 | 主要职责 |
| --- | --- | --- |
| `apps/app` | `:app` | 主 APK、Miuix/原生 UI、Flutter POC UI、编辑器、脚本列表、悬浮窗、MCP、APK 打包入口 |
| `apps/inrt` | `:inrt` | 脚本打包后独立 APK 的运行时模板（含定时任务、广播 Intent 任务） |
| `modules/engine` | `:engine` | Rhino/QuickJS/Node24、脚本生命周期、运行时 API、截图图像、Shell、JNI |
| `modules/engine-rhino` | `:engine-rhino` | Rhino 引擎实现（仅 compat 变体） |
| `modules/rhino-language` | `:rhino-language` | 编辑器 Rhino 语法/语言支持 |
| `modules/automator` | `:automator` | 无障碍服务、控件选择器、手势与布局分析 |
| `modules/common` | `:common` | 公共 Android、文件和工具基础设施 |
| `plugins/` | — | 独立插件 APK：`xiangqi-plugin`、`apkbuilder-plugin` |
| `samples/` | — | 不随主 APK 分发的大型示例工程（如象棋自动走棋） |
| `vscode-extension/` | — | 自研 VS Code 扩展（远程调试 9317 / 手机回连 8327） |
| `third-party/` | 多个库模块 | APK Builder、悬浮窗、主题、RootShell、设置兼容等内嵌依赖 |
| `docs/` | — | 当前说明、专题文档、阶段计划和历史报告 |
| `.artifacts/` | — | 本地 APK、截图、日志及验证资料；Git 忽略 |

## 3. UI 与业务边界

### Flutter POC 主体（dev 通道）

`apps/app/src/flutterpoc/` 只在 `-PflutterPoc=true` 时编入 debug 变体（payload 来自 `output/flutter-poc/payload`）。dev 通道自 dev-905 起把主入口切到 Flutter 玻璃首页：首页、抽屉、工作区、运行日志、打包/签名、设备页、悬浮控制条等逐步由 Flutter 实现；日志/终端/设置/工作区/退出等仍走原生页兜底。构建/发布入口：

```powershell
# Dart 改动先同步外置源 → dart analyze → build-aot.ps1，再构建
& output/flutter-poc/publish-poc-build.ps1 -VersionCode N -VersionName "dev-N"
& tools/publish.ps1 -ReleaseNotes "..." -SkipBuild -PcHost 192.168.10.7 -AllowMultiDevice -DeviceId cccc62c7
```

普通仓库构建（不带 `-PflutterPoc=true`）不包含 Flutter，仍是 Miuix + 原生 View。

### Miuix 主体（默认构建 / 回退入口）

`apps/app/src/miuix/` 只编入 `miuix` flavor。主界面由 `MainActivity` 管理 ViewPager、脚本数据和业务生命周期，再按需装入 Compose/Miuix 显示层。桌面保留「AI.js Pro（Miuix 回退）」入口。

当前已接入：

- 主导航、搜索入口和 Miuix FAB；
- 脚本文件列表、文件夹优先排序、类型图标、运行及更多操作；
- 教程/示例搜索、类型筛选、查看、运行与导入；
- 资源、插件和任务管理页；
- 设置、核心服务、日志、文档、登录注册；
- MCP 设置、工具列表、二维码、调用历史和工作区历史。

### 原生逻辑仍需保留

以下内容不能仅为了“统一 UI”而重写：

- `ScriptEngineService` 的执行、停止和监听；
- 脚本目录、项目识别、文件操作与 APK 打包；
- Rhino 兼容 API 和已有脚本数据；
- 无障碍、悬浮窗、截图和通知服务；
- `common` flavor 的原生回退页面。

### ImGui 现状

真正的 ImGui 工作台渲染栈已经删除，不再参与 APK 或原生构建。`ProCodeEditorActivity` 与 `EmbeddedTerminalActivity` 已分别迁入 `ui.editor` 与 `ui.terminal`，且不加载 ImGui 动态库。未来需要的 ImGui 是 QuickJS 可调用的独立悬浮窗 API，不恢复旧工作台入口。

## 4. JavaScript 引擎

| 引擎 | 选择方式 | 定位 |
| --- | --- | --- |
| Rhino | 默认 | 兼容 Auto.js 4.x、UI DSL、E4X、Java 互操作及旧脚本 |
| QuickJS | 文件第一条非空行为 `// @engine quickjs` | 现代 JavaScript、较轻运行时及已桥接的原生能力 |
| Node24 | 脚本/项目声明 `"nodejs";` | 真实 Node v24.22.0（V8 13.6 / ABI 137），独立 `:node24` 进程，面向 Node 生态示例 |

三引擎的能力面不同：Rhino 仍是旧脚本的默认兼容层；QuickJS 已完成事件、worker、悬浮窗生命周期、文件、网络、图像及 Native Frame 等；Node24 已覆盖 CJS/ESM、node_modules、`$autojs`、`app`、`ui`、无障碍/图片/投屏/通知/浮窗/定时任务等，但仍有明确缺口（见 REMAINING_WORK）。新增 API 时必须同时检查参数、返回值、异常、线程、资源释放以及三引擎回归。

专题说明见 [Rhino / QuickJS 双引擎架构](architecture/QUICKJS_ENGINE.md)、[双引擎功能对比](architecture/双引擎功能对比.md) 与 [Node24 基础自动化 API](guides/Node24基础自动化API.md)。

## 5. 脚本 MCP

MCP 仅存在于 `miuix` flavor，默认端点：

```text
http://127.0.0.1:8765/mcp
```

默认绑 `0.0.0.0`，端口被占用时在 **8765–8795** 之间顺延；仍保留 Bearer 令牌鉴权。电脑通过 USB 连接时使用：

```powershell
adb forward tcp:18790 tcp:8765
```

电脑客户端随后连接 `http://127.0.0.1:18790/mcp`。手机本机客户端直接使用 `8765`（或页面显示的实际端口）。自定义请求头不能保留空白名称，否则 OkHttp 会在发包前抛出 `IllegalArgumentException: name is empty`。

当前 MCP 覆盖脚本与示例分页读取、递归搜索和续页、脚本运行/停止/状态/异常、APK 日志、引擎 API 探测，以及工作区读取、编辑、Diff、应用和历史回退。写入和执行仍受手机端授权控制。完整说明见 [脚本 MCP 服务](MCP_SCRIPT_SERVICE.md)。

## 6. 小米无障碍快速开启

小米系统对 ADB 安装或调试签名 APK 的无障碍授权可能显示约 10 秒安全倒计时。项目现在按以下顺序处理：

1. 已有 `WRITE_SECURE_SETTINGS`：应用直接合并并写入无障碍服务列表；
2. Shizuku 可用：请求一次授权，同时授予上述权限并开启服务；
3. 用户启用了 Root 自动开启：通过 Root 完成相同操作；
4. 以上均不可用：打开系统无障碍设置，由用户手动确认。

写入时会保留 RustDesk 等其他已启用服务。K40 已验证 AI.js Pro 与 RustDesk 可同时绑定，后续直接开启无需再次运行 Shizuku shell。卸载应用会撤销权限，重新安装后需重新完成一次授权。

## 7. 构建配置

| 项目 | 当前值 |
| --- | --- |
| Gradle Wrapper | 8.9 |
| Android Gradle Plugin | 8.6.1 |
| Kotlin / Compose 编译插件 | 2.1.0 |
| JDK | 17 |
| compileSdk / Build Tools | 35 / 34.0.0 |
| targetSdk | 28 |
| 默认 minSdk | 21 |
| Miuix minSdk | 26 |
| Miuix | 0.3.1 |
| Compose UI/Foundation | 1.7.6 |
| Flutter | 3.3.4（仅 `-PflutterPoc=true`；AOT `libapp.so`） |
| 16 KB 页对齐 | 已处理（LOAD/RELRO；见 2026-10-18/19 报告） |

Windows PowerShell 常用命令（统一入口会自己切 JDK 17，不必手动设 `JAVA_HOME`）：

```powershell
# 统一入口（默认 miuix + compat，自动选择 JDK 17）
.\build-miuix-debug.ps1 -SkipNative

# 等价的手工命令
.\gradlew.bat :app:assembleMiuixCompatDebug --no-daemon

# 精简执行版（仅注册 QuickJS；编辑器暂留 Rhino 语言解析库）
.\gradlew.bat :app:assembleMiuixLiteDebug --no-daemon

# Flutter POC（dev 通道界面；需先生成 output/flutter-poc/payload）
.\gradlew.bat :app:assembleMiuixCompatDebug -PflutterPoc=true --no-daemon

# 独立脚本 APK 运行时
.\gradlew.bat :inrt:assembleDebug --no-daemon
```

> **2026-09-12 更正**：本节原先还列了 `:app:assembleCommonCompatDebug`（标注为「原生回退版本」），
> 但 `common` / `coolapk` 两个 channel flavor 已随「UI 统一到 Compose」退役，该任务名不存在；
> 入口脚本也从 `build-miuix-debug.ps1` 改名为 `build-miuix-debug.ps1`。

K40（arm64）安装文件：

```text
apps/app/build/outputs/apk/miuixCompat/debug/app-miuix-compat-arm64-v8a-debug.apk
```

安装命令：

```powershell
adb -s cccc62c7 install -r -d `
  apps/app/build/outputs/apk/miuixCompat/debug/app-miuix-compat-arm64-v8a-debug.apk
```

## 8. Git 与生成物规则

- 模块级 `build/`、Gradle/Kotlin/CMake 缓存、APK、日志、截图和 `.artifacts/` 不提交。
- 两个 Java 源码包名为 `build`，已在 `.gitignore` 中显式保留：
  - `apps/app/src/main/java/com/jdkshen/aijspro/build/`
  - `apps/app/src/main/java/com/jdkshen/aijspro/autojs/build/`
- 仓库当前只跟踪 QuickJS、OpenCV 等正式运行时需要的预编译 `.so`。旧 ImGui 与 Jackpal 终端原生库已移除；本地验证产物不能混入 UI 或目录整理提交。
- `local.properties`、`.codegraph/` 和本机工具路径不提交。

## 9. 当前验证基线（dev-1112，2026-10-19）

- 构建：`:app:assembleMiuixCompatDebug`、`:app:assembleMiuixLiteDebug`、`:inrt:assembleRelease` 均成功；dev 通道 Flutter 构建 `AI-js-Pro-Flutter-dev-1112.apk` 约 **132.1 MB**，K40 已装；
- 三引擎回归（dev-1103，后续版本增量复验）：QuickJS **227/227**、Rhino **22/22**、Node **21/21**、Node 颜色 **6/6**、Node UI basic **8 steps**，0 失败 0 跳过；
- dev-1111/1112 新增：Rhino `:script` 无障碍反向通道（`auto.service` 远程代理、事件订阅；K40 收到 accessibility 46 条 / view_clicked 4 条 / toast 1 条）；inrt 定时任务补全（广播 Intent 任务、按 path 执行、headless `exit()` 中止、exact alarm 引导）；
- K40 `cccc62c7`、Mi8 `ce4d2bdb`；RustDesk 与 AI.js Pro 无障碍可共存，重装后需重新授权；
- MCP 默认 **8765**（8765–8795 顺延，绑 `0.0.0.0`，dev-1176 起完全免令牌）；
- 当前分支 `main`（HEAD `45fae285`）已与 `origin/main` 同步。

已知限制（详见 [REMAINING_WORK](REMAINING_WORK.md) §0）：Rhino/QuickJS 的 `:script` 自动化是常用面子集（无 `AccessibilityEvent`/`event.source`，状态变更非实时）；Node24 的真实 Java 继承与部分模块仍有缺口；inrt 的 `addActivityIntentTask` 未实现；`plugins/apkbuilder-plugin/assets/template.apk` 仍是 70MB 旧全量，尚未用新 inrt release 重建。

历史测试数字只能说明当时快照；修改引擎、JNI、文件逻辑、权限或构建配置后必须重新运行对应验证。

## 10. 后续整理顺序（dev-1112）

1. 按 [REMAINING_WORK §0](REMAINING_WORK.md) 完成发布前 P0：正式 release 配置复核、targetSdk 专项、三引擎行为契约、`:script` 自动化边界、Node24 缺口；
2. Flutter dev 通道与 Miuix 回退入口成对回归（页面行为、主题、返回键、设置/日志/工作区/打包/悬浮控制条）；
3. 打包链路：主 App ↔ inrt 成对运行、插件模板用新 inrt release 重建（当前仍是 67.5MB 旧全量）；
4. 补深色、大字体、横屏、安全区域与多设备（K40/Mi8）回归；
5. 建立可重复的滑动/长跑性能基线，避免只凭观感判断流畅度；
6. 三引擎 API 差异用 MCP `list_engine_api` / `probe_engine_api` 与真机回归持续测量，不以“API 数量接近”作为等价结论。

## 11. 文档阅读顺序

1. 本文：当前事实和接手入口；
2. [REMAINING_WORK](REMAINING_WORK.md)：当前剩余工作（§0）与历史审计；
3. [收尾交接给 Codex v2](reports/2026-10-18_收尾交接_给Codex_v2.md)：逐版交付记录（末尾 dev-1112）；
4. [编译指南](guides/编译指南.md)：环境、构建与 dev 发布；
5. [脚本 MCP 服务](MCP_SCRIPT_SERVICE.md)：MCP 配置与工具；
6. [Rhino / QuickJS 架构](architecture/QUICKJS_ENGINE.md) 与 [Node24 基础自动化 API](guides/Node24基础自动化API.md)：三引擎能力面；
7. [HANDOVER](HANDOVER.md)：详细开发历史；
8. `plans/`、`reports/`：只在追溯阶段方案或测试证据时阅读。
