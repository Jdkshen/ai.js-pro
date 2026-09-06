# AI.js Pro

AI.js Pro 是一个运行在 Android 上、支持无障碍自动化的 JavaScript IDE 和脚本运行时。项目兼容传统 Auto.js 脚本，并提供可显式选择的 Native QuickJS 引擎。

## 主要能力

- 基于无障碍服务的控件查找、点击、滑动和布局分析；
- Rhino 兼容引擎与 QuickJS 现代 JavaScript 引擎；
- 截图、找色、找图、OpenCV 图像处理与 YOLO 推理；
- 悬浮窗、定时任务、通知与按键监听、Root/Shizuku Shell；
- 内置编辑器、示例、离线文档、控制台和 APK 打包能力；
- 兼容原有的 [Auto.js VS Code 插件](https://github.com/hyb1996/Auto.js-VSCode-Extension)。

当前构建版本由 `project-versions.json` 定义，为 **1.0.1**；目录名不代表实际 APK 版本。

## 仓库结构

```text
apps/
├─ app/                 主应用（Gradle 模块 :app）
└─ inrt/                独立脚本 APK 运行时（:inrt）
modules/
├─ autojs/              JavaScript 引擎与运行时 API（:autojs）
├─ automator/           无障碍自动化核心（:automator）
└─ common/              公共基础库（:common）
third-party/            内嵌第三方 Android 模块
docs/                   架构、编译指南、开发计划和历史报告
gradle/                 Gradle Wrapper
.artifacts/             本地构建与调试归档（不纳入 Git）
```

Gradle 逻辑模块名、Java/Kotlin 包名和脚本公开 API 均保持不变，物理目录映射统一定义在 `settings.gradle`。

## 快速构建

在 Windows PowerShell 中执行：

```powershell
# 已有预编译原生库，只组装主应用
.\build-common-debug.ps1 -SkipNative

# 重建 ImGui、QuickJS 原生库并组装主应用
.\build-common-debug.ps1
```

Debug APK 输出到 `apps/app/build/outputs/apk/common/debug/`。完整环境要求和故障排查见[编译指南](docs/guides/编译指南.md)。

## 文档

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
