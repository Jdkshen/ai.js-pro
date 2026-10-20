# AI.js Pro

[![Android CI and Release](https://github.com/Jdkshen/ai.js-pro/actions/workflows/android.yml/badge.svg)](https://github.com/Jdkshen/ai.js-pro/actions/workflows/android.yml)


AI.js Pro 是一个运行在 Android 上、支持无障碍自动化的 JavaScript IDE 和脚本运行时。兼容传统 Auto.js 脚本，提供 **Rhino / QuickJS / Node24** 三引擎；界面已统一为 **Flutter（Glass UI）**，旧 Miuix/Compose 界面已从构建中移除。

> **接手开发先读**：[docs/HANDOFF_AI.md](docs/HANDOFF_AI.md) —— 主架构图、构建/发布、真机测试套路、已知缺口、分支策略（面向所有 AI/人类接手者）。

## 主要能力

- 基于无障碍服务的控件查找、点击、滑动、布局分析与事件监听；
- **Rhino** 兼容引擎、**QuickJS** 现代 JavaScript 引擎、**Node24**（真实 Node v24.22.0，JNI 同进程）第三引擎；
- **Node 进程池**：`:node24_1/2/3` 三进程并行跑多个 Node 脚本（每个进程单 runtime），池满排队、按任务 token 定点停止；
- 截图、找色/找图/特征匹配、OpenCV 图像处理、YOLO 推理；
- 悬浮窗（XML/真实 View、拖动、点击、通知栏停止）、定时任务、通知与按键监听、Root/Shizuku Shell；
- 内置编辑器、示例、离线文档、日志页与 APK 打包能力；
- 兼容 [Auto.js VS Code 插件](https://github.com/hyb1996/Auto.js-VSCode-Extension)，并提供自研扩展（`vscode-extension/`）。

仓库版本由 `project-versions.json` 定义（**1.0.2 / versionCode 465**）；日常迭代发布走 dev 更新通道，当前为 **dev-1154**（`AI-js-Pro-Flutter-dev-1154.apk`，约 127.3 MB）。

## 架构（2026-10-20 冻结基线）

```
main 进程
  ├─ Flutter UI（唯一界面：SplashActivity → GlassFlutterActivity，AOT libapp.so 随包）
  ├─ 调度：PocRuntime / PocBridge / PocNode24Jobs（任务页、通知栏、stopAll）
  ├─ 无障碍自动化（AccessibilityService / automator）
  ├─ Node UI 主进程桥（NodeUiBridgeService / NodeUiDialogActivity，兜底路径）
  └─ 打包、MCP(8765)、电脑连接(9317)、更新检查

:script 进程
  ├─ Rhino 执行 / QuickJS 执行（含 threads worker）
  ├─ Node 执行编排：Node24Pool、脚本 token、console、STOP
  └─ engines.execScriptFile 子引擎

:node24_1 / _2 / _3 进程（Node24PoolService，每个进程一个 Node runtime）
  ├─ Node24Engine（libnode v24.22.0 + libajsnode24bridge.so 同进程 JNI）
  ├─ Node24HostBridge（$autojs / $java / ui / image / media / notification / runtime / engine）
  ├─ Node24LocalOverlay（Node 浮窗进程内 WindowManager overlay，优先；失败回退主进程）
  └─ Node24CaptureConsentActivity + 主进程 Node24CaptureRelay（MediaProjection 授权）
```

不可回退的设计决定（详见 HANDOFF_AI §3）：UI 只保留 Flutter；Node 引擎必须在池进程内（原生桥单会话）；Node 浮窗走进程内 overlay；任务/停止统一走 `:script` 注册表；引擎按脚本头 directives 路由。

## 仓库结构

```text
apps/
├─ app/                 主应用（:app；flutterpoc 源集为唯一 UI，旧 miuix 仅保留非 UI 逻辑）
└─ inrt/                独立脚本 APK 运行时（:inrt）
modules/
├─ engine/              JavaScript 引擎与运行时 API（:engine，含 Rhino/QuickJS/Node24 与 Node 资产）
├─ engine-rhino/        Rhino 引擎实现（:engine-rhino）
├─ rhino-language/      编辑器 Rhino 语法支持
├─ automator/           无障碍自动化核心（:automator）
└─ common/              公共基础库
plugins/                独立插件 APK（象棋引擎/识别、打包模板）
samples/                不随主 APK 分发的大型示例工程
vscode-extension/       自研 VS Code 扩展
third-party/            内嵌第三方 Android 模块
tools/                  构建 / 发布 / 真机脚本
docs/                   架构、指南、报告；接手总纲 docs/HANDOFF_AI.md
.artifacts/ output/     本地构建与证据归档（不纳入 Git）
```

## 快速构建

在 Windows PowerShell 中执行：

```powershell
. .\tools\jdk17.ps1 | Out-Null      # 默认 JDK 是 8，构建前必须切 JDK17

# 日常调试构建（Flutter 唯一 UI；同机有正版 Auto.js Pro 时加 -PdevPluginUsbDiscovery=false）
.\gradlew.bat :app:assembleMiuixCompatDebug -PflutterPoc=true -PdevPluginUsbDiscovery=false --no-daemon

# dev 通道：构建 + 发布到本地更新源（版本号 = max(仓库/已发布/在线设备)+1）
& tools\flutter-poc\publish-poc-build.ps1 -VersionCode N -VersionName "dev-N"
.\tools\publish.ps1 -ReleaseNotes "..." -SkipBuild -PcHost 192.168.10.7 -AllowMultiDevice -DeviceId cccc62c7

# 只计算下一版本、不发布
.\tools\publish.ps1 -ReleaseNotes "..." -DryRun
```

Debug APK 输出到 `apps/app/build/outputs/apk/miuixCompat/debug/`，arm64 设备装 `app-miuix-compat-arm64-v8a-debug.apk`。
完整环境要求（真实工具链版本、原生 `.so` 何时复用/重编、AOT/payload、本地安装 vs dev 发布）与踩坑见 [编译指南](docs/guides/编译指南.md) 与 [AGENTS.md](AGENTS.md)。

## 文档

- **[AI 接手总纲（先读）](docs/HANDOFF_AI.md)**：架构、构建发布、真机测试、缺口、分支策略
- [当前进度交接](docs/reports/2026-10-18_收尾交接_给Codex_v2.md)
- [剩余工作](docs/REMAINING_WORK.md)
- [文档索引](docs/README.md)
- [源码项目说明](docs/architecture/项目说明.md)
- [三引擎架构](docs/architecture/QUICKJS_ENGINE.md)
- [内置示例](apps/app/src/main/assets/sample/)
- [Auto.js 兼容 API 在线文档](https://hyb1996.github.io/AutoJs-Docs/)

## 分支策略

- `main`：主架构 + 稳定能力（当前冻结基线 tag `arch-baseline-20261020`）
- `sandbox/*`：与主架构无关的实验/垂直业务（如 Node 图像/象棋识别性能移植），验证稳定后再评估合入

项目仓库：[Jdkshen/ai.js-pro](https://github.com/Jdkshen/ai.js-pro) · 版本发布：[Releases](https://github.com/Jdkshen/ai.js-pro/releases)

## 许可证

源码基于 [Mozilla Public License Version 2.0](LICENSE.md)，并附加非商业性使用条款。修改、分发或发布衍生版本前，请完整阅读 `LICENSE.md`。
