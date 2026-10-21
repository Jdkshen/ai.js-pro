# AI.js Pro 项目现状

更新：2026-10-21。当前 dev 更新通道 **dev-1194**，K40 和小米 8 已安装。
发布包 `AI-js-Pro-Flutter-dev-1194.apk`，151168394 字节（144.17 MiB）。
SHA-256：`616c7175040e4c463866f9e770fd2e60579ff48026c762c966f90c3067c7fcca`。
手机访问本地更新清单 HTTP 200。

源码基线仍为 `main` / `ad8fcb7c`，本轮修复在工作区，尚未提交。
最新：[三引擎细节与 Pikafish 致命退出](reports/2026-10-21_三引擎细节复核与原生闪退修复.md)。修复 QuickJS 参数/文件、Node 并发归属/存储/events/退出、Rhino 精确找色和 MCP Java 对象查询；Pikafish 非法局面或历史不再终止宿主，插件 1.0.1 / 2 同步修复。原 `.4` 象棋设备的最终包长期对局仍未验收。
仓库正式版本 `1.0.2 / 465` 不随 dev 发布手工递增。

先读 [接手总纲](HANDOFF_AI.md)、[最新修复与验收边界](reports/2026-10-21_Node_Dex插件WebUI与K40修复.md)、
[剩余清单](REMAINING_WORK.md)。旧报告保留其当时结论，不能代替当前验收结果。

本轮 [MCP、电脑连接与日志优化](reports/2026-10-21_MCP界面电脑连接与日志优化.md)：134 项 JVM 测试和扩展 25 项测试通过，两台各 42 次 MCP 回归通过；远程三引擎、真实扩展回连与日志交互已验收。

## 当前架构

- **Flutter-only 主界面**：源码唯一真源为 `flutter-ui/`，基础 payload 在 `flutter-payload-base/`。
  `apps/app/src/main` 与 `src/flutterpoc` 提供宿主桥。保留的 `miuixCompat` 是 Gradle 变体名，
  不能据此认为旧 Miuix 页面仍参与当前主界面构建。
- **三引擎**：Rhino / QuickJS / Node24。主进程负责界面和 IPC，`:script` 是协调器；
  Node24 使用现有 `:node24_1..3` 池，支持并发、单独停止和故障恢复。
- **固定 Node 核心**：Node 24.22.0、full ICU 78.3 / Unicode 17.0，JNI 与核心配置匹配。
  npm 开发命令使用同一个核心的 CLI，未引入另一套 App 引擎架构。
- **插件**：象棋与打包模板分别在 `plugins/xiangqi-plugin`、`plugins/apkbuilder-plugin`；
  主 APK 不重新内嵌这两套大资源。
- **MCP**：默认 8765；被占用时顺延。免令牌，开启服务即授权三引擎运行及工作区读写，界面显示实际状态、工具数与请求耗时。
  电脑连接统一使用自家 AI.js Pro VS Code 扩展 0.3.3，手机 9317 / 电脑默认 8327，支持自定义端口；不再启动旧 Pro ws-rpc 兼容服务。
- **独立 ImGui 悬浮 API** 保留，旧 ImGui 工作台不恢复。

## 本轮已验证

| 项目 | 结果 |
| --- | --- |
| 动态 Dex 回收 | 同一 K40 Node 活进程释放 8 个生成类 loader 和 8 个 DexFile；pending=0、弱引用存活=0、失败=0。 |
| 通用 APK 插件 | SDK v1/v2、Java/JNI/resources/assets、双向 Java 句柄、跨进程 Binder、异常、重载、解绑通过；实际打包/象棋插件通过。 |
| Web UI | 真 WebView 的双向异步调用、重入、事件、错误、Activity 切换/关闭等 14 项通过。 |
| 高级版原始前端示例 | React / Vue3 开发安装、编译、页面、热更新和源文件恢复通过；Vue2 SFC、文件浏览器及正常生产构建也已验证。 |
| K40 内置 M3 | 卡片回传、日志筛选和记录、输入提示恢复；资源随 APK 自动编译和升级同步。 |
| npm / Shell | 真实 Node/npm 版本、流式输出、私有 bin、原生异常堆栈及停止通过。 |
| 本地检查 | Java 相关单测 102 项无失败，12 组 Node JS 契约通过；应用构建与发布校验通过。 |

任务/设置等此前的验证见 [原生 UI、任务及设置记录](reports/2026-10-21_Node高级版兼容_原生UI任务设置.md)。
dev-1184 补充了 [示例目录运行入口](reports/2026-10-21_示例目录运行入口修复.md)：19 个内置工程入口解析和 9 项新增单测通过，修复源码运行通过但目录按钮缺失 dist 的差异。
dev-1186 补充了 [自定义组件与重复窗口](reports/2026-10-21_Node自定义组件与重复窗口修复.md)：colored-button 的真实 View 事件、自定义及基础属性通过两台手机验证；K40 实际示例按钮与返回键通过，Node UI 默认复用宿主任务。
dev-1188 补充了 [MCP 三引擎与传统 UI](reports/2026-10-21_MCP三引擎与Node传统UI修复.md)：三引擎现有 API 通过统一脚本及通用调用入口接入 MCP，支持模块查询、真实语法检查、日志和停止；两台各 42 次 MCP 请求、传统 UI 8 项通过。复杂模板及外部自定义控件仍需单独验证。
本轮没有重新测量和承诺反射调用加速数字。

## 仍需验收

- 所有三引擎 API 的参数、错误、线程、生命周期全量等价；现有通过项不能代表整套等价。
- 第三方强制依赖旧 V8 Runtime / 原生句柄的插件；尚无可测试的官方 FFmpeg APK。
- 外部工程未编译 res 时的动态 inflater 子集，以及完整 OpenCV 和完整 44 个高级版候选。
- 正式 release 签名/权限画像与 targetSdk 提升、inrt 全量同步等，继续按剩余清单执行。

## 构建与发布

```powershell
. ./tools/jdk17.ps1 | Out-Null
# 本地完整 Flutter APK；Dart 无变更时可复用已验证的 AOT payload
./gradlew.bat :app:assembleMiuixCompatDebug -PflutterPoc=true -PdevPluginUsbDiscovery=false --console=plain
```

有 Dart 变更先跑仓库内 `tools/flutter-poc/build-aot.ps1`。
发版走 `tools/flutter-poc/publish-poc-build.ps1` 注入正确版本，随后用
`tools/publish.ps1 -SkipBuild -PcHost 192.168.10.7 -AllowMultiDevice -DeviceId cccc62c7`，
并提供 ReleaseNotes。下一版本按仓库、更新源及目标设备的最大值加一。
不裸跑底层发布器，不用假版本号试发布。

Node 核心重编见 `tools/node24-runtime/README.md`；JNI、CLI/npm、内置工程资源生成器均在 `tools/`。
详见 [编译指南](guides/编译指南.md)。真实设备证据在忽略目录 `output/node-pro-gaps-20261021/`，
可执行探针和插件 fixture 源码随本轮代码保留。
