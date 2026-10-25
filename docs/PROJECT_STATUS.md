# AI.js Pro 项目现状

> **2026-10-25 候选验收通过、未发布**：[MCP 手机终端与编译入口](reports/2026-10-25_MCP手机终端与编译入口.md)。candidate-1245 已装 MI8，公开工具 51 项（新增终端 6 项）；25 项本地、59 次 MCP 调用、6 项界面和真实 D8/Smali 编译通过。131.32 MiB / SHA `f72c0ceb…`。C/C++/Java 源码/完整 Android 工具链仍需可选插件；发布源维持 dev-1244，K40 1199 未动。

> **当前发布 dev-1244（2026-10-24）**：[更新下载修复与发布](reports/2026-10-24_dev1244更新下载修复与发布.md)。修复 R8 泛型丢失导致的内置更新 0%；增加最终 DEX 硬门，15 项网络测试通过。MI8 已装，并经真实 DownloadManager 下载到 100%、SHA 与实际更新器包校验通过；K40 未动。旧版须浏览器首次覆盖升级，下面版本为历史记录。

> **当前发布 dev-1243（2026-10-24）**：[发布与下载核验](reports/2026-10-24_dev1243发布.md)。大文件分享后台化、流式限额与失败清理、URI 授权及等待时间修复已随新 AOT 发布，131.27 MiB / SHA-256 `a2195faf…`；双机清单、MI8 完整下载一致，本轮未安装。7 项归档及 3 项 Flutter 检查通过；原手机实际分享仍待日志及复测。下面 dev-1242 等条目为历史记录。

> **2026-10-24 已发布 dev-1242（开发通道）**：[发布与下载核验](reports/2026-10-24_dev1242发布.md)。五项优化已发布，APK **131.27 MiB**，SHA-256 `9e3616b2…`。两台手机均能拉到新版清单，MI8 完整下载哈希一致；本轮没有安装，K40 仍 1199、MI8 仍候选 1241，供用户自行更新。下方“候选未发布”等描述为发布前的历史记录。


> **2026-10-24 五项优化已验收（未发布）**：[五项优化任务与验收](reports/2026-10-24_五项优化任务与验收.md)。更新源仍 **dev-1240**；MI8 当前安装 **candidate-1241**（测试签名、非 debuggable，131.27 MiB，SHA-256 `22888845…`）。M3/缓存/保守 R8/release/三引擎探针及诊断已完成；35 条公共行为、20 轮 UI / 28 断言和 M3 交互通过，最终安装后无新增 crash。高级版全量行为、真正 16KB 内核及原 Android 16 设备仍待专项验证。下方版本与装机描述为历史记录，以本条和链接报告为准。


> M3 性能修复已发布 **dev-1229**（2026-10-23）：[测量与交付记录](reports/2026-10-23_Node_M3启动性能优化.md)。defineClass 真异步、轻量编译与停止清理；750 JVM / 19 组 Node 契约通过。候选 MI8 列表安装冷进程 1460 ms、重复 1270/1253 ms；发布包 28 项 UI、三次重建、两轮构建、7 项动态类及 M3 滚动/切页/一次返回复验通过。

更新：2026-10-23。当前 dev 更新通道 **dev-1229**，小米 8 已安装并核验。K40 保留 dev-1199，象棋业务运行中。
发布包 `AI-js-Pro-Flutter-dev-1229.apk`，151783514 字节（144.75 MiB）。
SHA-256：`40a1e7437e311a8900c87774a7caa9595c1ed6f0fe753217233306e65579dfa4`。
手机访问本地更新清单和 APK HTTP 200；已安装 APK 哈希与发布源一致，全部 22 个 ELF/RELRO/ZIP 对齐检查通过。版本和主进程 PID 在发布包复验中不变，本轮未新增 crash 条目，旧 crash buffer 未清除。

前序：[Node24 动态 UI 底层修复（dev-1227）](reports/2026-10-23_Node24动态UI底层修复.md)：动态 XML、Widget 子内容、接口对象、R 嵌套类、binding 缺失 id 与 setContentView 主线程补丁；原 20 轮压力及退出回归保留为对应版本证据。Android 16 原机非确定性卡死仍待复测，未据此宣称根因已查明。

最新：[dev-1221 发布核验](reports/2026-10-23_dev1221发布核验.md)：冷启动最新发布包，Rhino 17 / QuickJS 17 / Node 13 项退出回归共 47 项通过，版本与主进程 PID 全程不变、浮窗清理为 0、crash buffer 为空；签名及全部 22 个 ELF64 对齐静态检查通过。Node 浮窗 reload 与多锚点拖动见 [底层优化记录](reports/2026-10-23_Node悬浮窗底层优化.md)。

最新：[选择器运行后编辑器卡顿修复](reports/2026-10-22_选择器运行后编辑器卡顿修复.md)：真机主线程堆栈定位日志全量扫描/逐行排版，改为批量刷新与节点日志摘要；2000 行日志打开由约 5 秒降到 319–731ms，原始示例三轮、节点摘要 9 项及日志/编辑保存 7 项通过。33 项选择器、4 项日志格式本地回归通过。

此前：[DotJumpLoading 原生组件修复](reports/2026-10-22_DotJumpLoading原生组件修复.md)：Rhino 原生 View render / 初始化顺序，19 项组件契约、原包和修正版各 9 项动画操作、2 项修正版尺寸/轮换真机通过；修正版 ZIP 已提供，原始 ZIP 保留。

源码基线为 `main` / `9b3a4f00`；本轮 Node24 动态 UI 源码、回归工具与核验文档修改尚未提交。
本轮：[APK 开关与按钮颜色统一](reports/2026-10-22_APK开关与按钮颜色统一.md)，12 项 Flutter、693 项 JVM、QuickJS 225/225 与发布检查通过。
[三引擎公共 API 首批统一及高级版全量契约](reports/2026-10-22_三引擎API统一与高级版全量契约.md)中，
7 组共用示例、跨引擎存储、MCP 直接调用已双机通过；全部高级版接口仍有缺口。
最新：[Flutter 升级与 16KB 兼容修复](reports/2026-10-22_Flutter升级与16KB兼容修复.md)。Flutter 3.47.7 / Dart 3.13.5，engine/embedding/AOT 配套，修复 Node 资产中的旧 libc++；22 个 64 位 ELF 无白名单通过，发布前强制检查。K40 / 小米 8 均为 4KB 内核，真实 16KB 环境仍待专项验收。
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
  电脑连接统一使用自家 AI.js Pro VS Code 扩展，本机已安装 0.3.5，手机 9317 / 电脑默认 8327，支持自定义端口；不再启动旧 Pro ws-rpc 兼容服务。
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
