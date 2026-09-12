# AI.js Pro 项目现状与接手说明

> 更新日期：2026-09-08  
> 当前开发分支：`chore/upgrade-gradle-8`  
> 应用包名：`com.jdkshen.aijspro`  
> 当前版本：`1.0.2`（`versionCode 465`）

本文是当前项目的简明事实入口。`docs/plans/` 和 `docs/reports/` 记录的是阶段方案与历史测试，不应代替本文判断当前实现状态。

## 1. 当前结论

AI.js Pro 是从 Auto.js 4.4.1 演进而来的 Android JavaScript IDE 与自动化运行时，目前采用以下架构：

- **Miuix 是主要显示层**：`miuix` flavor 提供主导航、服务、设置、文档、日志、示例、资源、插件、任务和 MCP 页面。
- **原生 View 继续承载成熟业务逻辑**：脚本目录、文件操作、运行服务及部分编辑能力继续复用原实现，避免一次性重写导致脚本行为变化。
- **ImGui 工作台已彻底移除**：工作台 Activity、Java/JNI 渲染桥、C++ 源码及三 ABI 的 `libautojs_imgui.so` 已删除。代码编辑器和终端已分别迁入 `ui.editor` 与 `ui.terminal`，继续作为独立原生功能。
- **双引擎并存**：Rhino 负责旧 Auto.js 脚本兼容；首行声明 `// @engine quickjs` 时使用 QuickJS。
- **MCP 已接入脚本开发与调试**：支持脚本/示例读取、搜索分页、任务状态、异常与 APK 日志、工作区 Diff、应用和回退。
- **小米无障碍已改为快速开启**：首次通过 Shizuku、ADB 或 Root 授予 `WRITE_SECURE_SETTINGS`，以后直接写入安全设置；不可用时才回退系统设置。

## 2. 仓库结构

| 路径 | Gradle 模块 | 主要职责 |
| --- | --- | --- |
| `apps/app` | `:app` | 主 APK、Miuix/原生 UI、编辑器、脚本列表、悬浮窗、MCP、APK 打包入口 |
| `apps/inrt` | `:inrt` | 脚本打包后独立 APK 的运行时模板 |
| `modules/engine` | `:engine` | Rhino/QuickJS、脚本生命周期、运行时 API、截图图像、Shell、JNI |
| `modules/automator` | `:automator` | 无障碍服务、控件选择器、手势与布局分析 |
| `modules/common` | `:common` | 公共 Android、文件和工具基础设施 |
| `third-party/` | 多个库模块 | APK Builder、悬浮窗、主题、RootShell、设置兼容等内嵌依赖 |
| `docs/` | — | 当前说明、专题文档、阶段计划和历史报告 |
| `.artifacts/` | — | 本地 APK、截图、日志及验证资料；Git 忽略 |

## 3. UI 与业务边界

### Miuix 主体

`apps/app/src/miuix/` 只编入 `miuix` flavor。主界面仍由 `MainActivity` 管理 ViewPager、脚本数据和业务生命周期，再按需装入 Compose/Miuix 显示层。

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

QuickJS 已完成事件、worker 结果、悬浮窗生命周期、文件、网络、图像及 Native Frame 等多轮补齐，但不应宣称与 Rhino API 100% 等价。新增 API 时必须同时检查：参数、返回值、异常、线程、资源释放以及 Rhino/QuickJS 回归。

专题说明见 [Rhino / QuickJS 双引擎架构](architecture/QUICKJS_ENGINE.md) 和 [双引擎功能对比](architecture/双引擎功能对比.md)。

## 5. 脚本 MCP

MCP 仅存在于 `miuix` flavor，默认端点：

```text
http://127.0.0.1:8788/mcp
```

电脑通过 USB 连接时使用：

```powershell
adb forward tcp:18790 tcp:8788
```

电脑客户端随后连接 `http://127.0.0.1:18790/mcp`。手机本机客户端直接使用 `8788`。自定义请求头不能保留空白名称，否则 OkHttp 会在发包前抛出 `IllegalArgumentException: name is empty`。

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

Windows PowerShell 常用命令（统一入口会自己切 JDK 17，不必手动设 `JAVA_HOME`）：

```powershell
# 统一入口（默认 miuix + compat，自动选择 JDK 17）
.\build-miuix-debug.ps1 -SkipNative

# 等价的手工命令
.\gradlew.bat :app:assembleMiuixCompatDebug --no-daemon

# 精简执行版（仅注册 QuickJS；编辑器暂留 Rhino 语言解析库）
.\gradlew.bat :app:assembleMiuixLiteDebug --no-daemon

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

## 9. 当前验证基线

- `:app:assembleMiuixCompatDebug` 与 `:app:assembleMiuixLiteDebug` 构建成功；
- 新建脚本/项目默认 QuickJS；旧无标记脚本保持 Rhino，项目级 `engine` 与文件指令均已支持；
- K40：`com.jdkshen.aijspro` 版本 `1.0.2 (465)` 已覆盖安装；
- K40：当前系统仅保留 RustDesk 无障碍，AI.js Pro 无障碍需由用户按需重新开启；
- 快速开启路径已确认不再使用小米 10 秒手动倒计时；
- 最近完整 QuickJS 回归记录为 46/46；
- 当前分支已与 `origin/chore/upgrade-gradle-8` 同步。

历史测试数字只能说明当时快照，修改引擎、JNI、文件逻辑、权限或构建配置后必须重新运行对应验证。

## 10. 后续整理顺序

1. 继续按 Auto.js Pro 对齐 Miuix 页面密度、弹窗、搜索定位和滚动手感；
2. 对编辑器、终端、图像工具和调试工具逐项建立 Miuix/原生替代，再决定是否删除 ImGui 实现；
3. 补深色、大字体、横屏、安全区域和低版本 Android 回归；
4. 建立可重复的滑动帧耗时测试，避免只凭观感判断流畅度；
5. 对 QuickJS/Rhino API 建立自动化差异清单，不用“API 已全部完善”作为结论；
6. 正式发布前处理签名、隐私、目标 SDK、权限最小化及多 ABI 验收。

## 11. 文档阅读顺序

1. 本文：当前事实和接手入口；
2. [编译指南](guides/编译指南.md)：环境与构建故障；
3. [脚本 MCP 服务](MCP_SCRIPT_SERVICE.md)：MCP 配置与工具；
4. [双引擎架构](architecture/QUICKJS_ENGINE.md)：Rhino/QuickJS；
5. [HANDOVER](HANDOVER.md)：详细开发历史；
6. `plans/`、`reports/`：只在追溯阶段方案或测试证据时阅读。
