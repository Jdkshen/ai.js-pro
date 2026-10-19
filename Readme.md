# AI.js Pro

AI.js Pro 是一个运行在 Android 上、支持无障碍自动化的 JavaScript IDE 和脚本运行时。项目兼容传统 Auto.js 脚本，提供 Rhino / QuickJS / Node24 三引擎，并支持 Flutter 玻璃界面与 Miuix 两套界面。

## 主要能力

- 基于无障碍服务的控件查找、点击、滑动和布局分析；
- Rhino 兼容引擎、QuickJS 现代 JavaScript 引擎与 Node24（真实 Node v24.22.0）第三引擎；
- 普通脚本 `:script`、native 项目 `:native`、Node `:node24` 多进程隔离执行；
- 截图、找色、找图、OpenCV 图像处理与 YOLO 推理；
- 悬浮窗、定时任务、通知与按键监听、Root/Shizuku Shell；
- 内置编辑器、示例、离线文档、控制台和 APK 打包能力；
- 兼容原有的 [Auto.js VS Code 插件](https://github.com/hyb1996/Auto.js-VSCode-Extension)，并提供自研扩展（`vscode-extension/`）。

仓库版本由 `project-versions.json` 定义（**1.0.2 / versionCode 465**）；日常迭代发布走 dev 更新通道，当前为 **dev-1112**（`AI-js-Pro-Flutter-dev-1112.apk`，约 132.1 MB）。目录名不代表实际 APK 版本。

> 界面说明：dev 通道自 dev-905 起以 Flutter 玻璃界面为主入口（`-PflutterPoc=true`，`GlassFlutterActivity`），桌面保留「AI.js Pro（Miuix 回退）」；仓库默认构建（不带 `-PflutterPoc=true`）仍是 Miuix + 原生 View。

## 仓库结构

```text
apps/
├─ app/                 主应用（Gradle 模块 :app；含 miuix 与 flutterpoc 源集）
└─ inrt/                独立脚本 APK 运行时（:inrt）
modules/
├─ engine/              JavaScript 引擎与运行时 API（:engine，含 Rhino/QuickJS/Node24）
├─ engine-rhino/        Rhino 引擎实现（:engine-rhino，仅 compat）
├─ rhino-language/      编辑器 Rhino 语法支持（:rhino-language）
├─ automator/           无障碍自动化核心（:automator）
└─ common/              公共基础库（:common）
plugins/                独立插件 APK（象棋、打包模板）
samples/                不随主 APK 分发的大型示例工程
vscode-extension/       自研 VS Code 扩展
third-party/            内嵌第三方 Android 模块
docs/                   架构、编译指南、开发计划和历史报告
gradle/                 Gradle Wrapper
.artifacts/             本地构建与调试归档（不纳入 Git）
```

Gradle 逻辑模块名、Java/Kotlin 包名和脚本公开 API 均保持不变，物理目录映射统一定义在 `settings.gradle`。

## 快速构建

在 Windows PowerShell 中执行：

```powershell
# 已有预编译原生库，只组装主应用（miuix + compat，即 Miuix 皮肤 + Rhino/QuickJS/Node24）
.\build-miuix-debug.ps1 -SkipNative

# 先重建 QuickJS 原生库，再组装主应用
.\build-miuix-debug.ps1

# 精简执行版（仅注册 QuickJS）
.\build-miuix-debug.ps1 -SkipNative -Lite

# 同时产出独立脚本 APK 运行时
.\build-miuix-debug.ps1 -SkipNative -IncludeInrt

# Flutter POC（dev 通道界面；需先生成 output/flutter-poc/payload）
.\gradlew.bat :app:assembleMiuixCompatDebug -PflutterPoc=true --no-daemon

# dev 通道构建与发布（版本号自动 +1，发布前先读手机/update.json）
& output\flutter-poc\publish-poc-build.ps1 -VersionCode N -VersionName "dev-N"
.\tools\publish.ps1 -ReleaseNotes "..." -SkipBuild -PcHost 192.168.10.7 -AllowMultiDevice -DeviceId cccc62c7
```

Debug APK 输出到 `apps/app/build/outputs/apk/miuixCompat/debug/`（`-Lite` 时是 `miuixLite/debug/`），
每个 ABI 一个包，K40/arm64 装 `app-miuix-compat-arm64-v8a-debug.apk`。
完整环境要求和故障排查见[编译指南](docs/guides/编译指南.md)。

## 文档

- [当前项目状态与接手说明](docs/PROJECT_STATUS.md)
- [文档索引](docs/README.md)
- [源码项目说明](docs/architecture/项目说明.md)
- [Rhino / QuickJS 双引擎架构](docs/architecture/QUICKJS_ENGINE.md)
- [编译指南](docs/guides/编译指南.md)
- [内置示例](apps/app/src/main/assets/sample/)
- [Auto.js 兼容 API 在线文档](https://hyb1996.github.io/AutoJs-Docs/)

项目仓库：[Jdkshen/ai.js-pro](https://github.com/Jdkshen/ai.js-pro)

版本发布：[Releases](https://github.com/Jdkshen/ai.js-pro/releases)

## 许可证

源码基于 [Mozilla Public License Version 2.0](LICENSE.md)，并附加非商业性使用条款。修改、分发或发布衍生版本前，请完整阅读 `LICENSE.md`。
