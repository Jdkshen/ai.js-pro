# AGENTS.md

> **最新候选 1249（2026-10-25，MI8 已验、未发布）**：[Node 资源 Context 与 UI 进程隔离](docs/reports/2026-10-25_Node资源Context与UI进程隔离.md)。补齐 `$autojs.androidContext.loadResources`、独立资源/主题/inflater、编译 layoutId 的 recreate；旧 Node UI/对话框迁到 `:nodeui`，work/settings 保留主进程。843 JVM、7 组 Node 契约、MI8 资源与 20 轮重建及 renderer 退出隔离通过。最终 R8 包 134.26 MiB、SHA `774ba19e…`，22 ELF16KB/Retrofit DEX 通过；更新源仍 dev-1247，K40 未动。Rhino 传统 UI 仍有主进程路径，原听海项目未取得，不得宣称全部高级版 UI 已等价。

给 AI 助手 / 自动化 agent 的项目约定。dsh（DeepSeek Harness）、Codex、Copilot、Claude Code 都会读这个文件。

> **接手总纲（先读这个）**：[`docs/HANDOFF_AI.md`](docs/HANDOFF_AI.md) —— 主架构图、构建/发布、真机测试套路、已知缺口、分支策略。
> **当前发布 dev-1251（2026-10-25，回调异常热修）**：[Node 回调异常逃逸修复](docs/reports/2026-10-25_Node回调异常逃逸修复.md)：主线程/取消中的 Node JS 回调异常改为捕获并 logcat，不再逃逸 Looper 杀应用；release+R8，134.26 MiB，SHA `d1c888ae…`；GitHub <https://github.com/Jdkshen/ai.js-pro/releases/tag/dev-1251>；MI8 已装。
> **当前发布 dev-1250（2026-10-25）**：[Node 资源 Context 与 UI 进程隔离](docs/reports/2026-10-25_Node资源Context与UI进程隔离.md) + [编辑器剪贴板与全进程停止](docs/reports/2026-10-25_编辑器剪贴板与全进程停止修复.md)：release+R8 测试签名，134.26 MiB，SHA `76cfc216…`；GitHub Release <https://github.com/Jdkshen/ai.js-pro/releases/tag/dev-1250>；MI8 已装，K40 未动。
> **候选 1248（2026-10-25，MI8 已验、未发布）**：[编辑器剪贴板与全进程停止修复](docs/reports/2026-10-25_编辑器剪贴板与全进程停止修复.md)。Ace 1.44 长按需 `getClientPosition` 适配；原生复制剪切粘贴、主应用三引擎 / Node 三槽全局停止和独立 APK 停止中继、新 inrt 模板。App 390 / inrt 9 JVM 通过，最终 release/R8 真机回归通过；134.26 MiB，SHA `0aa1c2e4…`。MI8 1248，K40 1199 未动，更新源仍 dev-1247。旧独立 APK 必须重新打包；保持子进程 Glide 初始化，不要随主进程职责一起跳过。
> **MCP 连接指南（含截图）**：[MCP 连接指南](docs/guides/MCP连接指南.md)：手机开启 MCP、局域网/USB 地址、客户端配置、验证与排查。
> **MCP 手机终端（2026-10-25，候选已验收、未发布）**：[终端与编译入口](docs/reports/2026-10-25_MCP手机终端与编译入口.md)。candidate-1245 已装 MI8；25 项本地、59 次 MCP 调用、6 项界面、真实 D8/Smali 编译通过。复用原终端，六项新工具；编译器 main 必须走独立 app_process。JCommander 反射校验器需显式 keep，修改后须用 R8 包跑真实编译。**用户已决定不做 Java/C/C++/APK 源码编译工具链**：只保留 MCP 终端控制与既有 D8/Smali；不要重新引入 buildtools/编译插件。更新源已为 dev-1247；K40 1199 未动。
> **当前发布 dev-1247（2026-10-25）**：[MCP 无障碍 UI/工具分类](docs/reports/2026-10-25_MCP无障碍UI自动化.md)：ui_* 8 项、app_* 7 项、aijs-ui 终端桥、8 类工具目录；release+R8 测试签名，SHA `0eac1ef5…`；GitHub Release <https://github.com/Jdkshen/ai.js-pro/releases/tag/dev-1247>；MI8 已装，K40 未动。
> **当前发布 dev-1244（2026-10-24）**：[更新下载修复](docs/reports/2026-10-24_dev1244更新下载修复与发布.md)：R8 网络包装类泛型需显式 keep，`-keepattributes Signature` 单独不够；最终 APK 泛型/注解检查已加入构建和发布硬门，15 项网络回归及 MI8 真实下载器/包校验通过。旧版内置下载器需浏览器首次覆盖升级；K40 保持业务。下方旧版本为历史记录。
> **当前发布 dev-1243（2026-10-24）**：[大文件分享发布核验](docs/reports/2026-10-24_dev1243发布.md)：后台压缩/URI 准备、流式限额和清理、分享等待修复；AOT 已重建，131.27 MiB / SHA `a2195faf…`，双机清单及 MI8 完整下载通过，未安装。原手机实际分享仍待复测；下方旧版发布描述为历史记录。
> 最新进度：[`docs/reports/2026-10-21_Node_Dex插件WebUI与K40修复.md`](docs/reports/2026-10-21_Node_Dex插件WebUI与K40修复.md)
> 示例目录入口补充：[`docs/reports/2026-10-21_示例目录运行入口修复.md`](docs/reports/2026-10-21_示例目录运行入口修复.md)（React / Vue3 外部运行按钮均已真机重测）。
> 自定义组件/重复窗口补充：[`docs/reports/2026-10-21_Node自定义组件与重复窗口修复.md`](docs/reports/2026-10-21_Node自定义组件与重复窗口修复.md)。
> MCP/传统界面补充：[`docs/reports/2026-10-21_MCP三引擎与Node传统UI修复.md`](docs/reports/2026-10-21_MCP三引擎与Node传统UI修复.md)。
> 本轮优化：[`docs/reports/2026-10-21_MCP界面电脑连接与日志优化.md`](docs/reports/2026-10-21_MCP界面电脑连接与日志优化.md)。电脑连接使用自家 VS Code 扩展 0.3.3；旧 Pro ws-rpc 不再启动。
> 最新核对：[三引擎细节与 Pikafish 原生闪退修复](docs/reports/2026-10-21_三引擎细节复核与原生闪退修复.md)：非法局面/历史原来会触发原生 `exit(1)`，与 RenderThread destroyed-mutex 表现一起复现；已补语义校验。插件 APK 1.0.1 / 2。
> 插件最新：[象棋引擎预算升级](docs/reports/2026-10-23_象棋引擎插件预算升级.md)：**1.1.0/code3**，自带通用/优化 ARM64 UCI 进程，HWCAP 自动选择，Node createUci 支持 ponder/ponderhit；K40 已验，新手机仍离线待验。只更新插件附件，主程序仍 dev-1210。
> 拒和修复：[象棋引擎拒和评分修复](docs/reports/2026-10-23_象棋引擎拒和评分修复.md)：**1.1.1/code4**，修正原地递归走子导致的根方评分错误，search/qsearch统一；通用/优化版各15项真机分析通过。K40已更新，宿主发布源未变。
> 浮窗底层交付：[Node 浮窗底层修复](docs/reports/2026-10-23_Node悬浮窗底层优化.md)：**dev-1221** 已发布；新增 reload(xml) 热替换与多锚点拖动，模块 floating_window@1.2.0；移动/raw/hide-show/getSize/reload 17 项断言 ALL_PASS，APK 交付手机两个目录。
> 宿主已发布：**dev-1213**（2026-10-23 19:44 更新源，SHA-256 `af7e597f…`）[悬浮窗主线程退出崩溃修复](docs/reports/2026-10-23_悬浮窗主线程退出崩溃修复.md)：Timer/View/弹窗/浮窗/Node24 Java 回调边界捕获二次停止异常、保留嵌套 JS 中止语义；Node ui.R 缺失资源返回 0；709 JVM + MI8 17 项回调与 Aurora 通过。Redmi Android 16 待验，K40 保持 dev-1199。
> 最新升级：[Flutter 3.47.7 / Dart 3.13.5 与 16KB 修复](docs/reports/2026-10-22_Flutter升级与16KB兼容修复.md)：engine/embedding/AOT 成套锁定，Node libc++ 更新，发布强制无白名单 22 ELF 校验；真实 16KB 内核仍待验收。
> 当前发布：**dev-1240**（Flutter-only UI + Rhino/QuickJS/Node24 三引擎 + Node 进程池 `:node24_1..3`）；[Node M3 四项底层优化](docs/reports/2026-10-24_Node_M3四项底层优化.md)：类型化 Java JNI 快路径、Smali 直接 DEX、Glide 图片缓存、布局生成类加载器更新；MI8 20 轮/28 断言、DEX 中位 32ms vs 90ms、图片重复 26ms；整页暂未稳定提速。此前 dev-1232：[Node M3 启动性能优化](docs/reports/2026-10-23_Node_M3启动性能优化.md)：**dev-1231** 已发布（SHA-256 `7fd360d3…`）；新增 `ui.inflateBatch`/原生批量 inflate、M3 defineClass 并行与首帧后懒加载、grid_item 一次批量预生成；`onContentViewSet` 498→84ms、`onCreateViewHolder` 256→125ms；整页 list ready 仍 ~1.5s，Activity 预热/原生 M3 待下一阶段。
> 原生 setup：[原生 setup 延迟与控制台懒加载](docs/reports/2026-10-24_原生setup延迟与控制台懒加载.md)：**dev-1240** 已发布（SHA-256 `399d7e81…`）；`ui.afterFirstFrame` + 控制台切页懒加载；setup tail 1817→1558ms（-14%），首帧未稳定改善。
> 布局/绑定桥：[布局与绑定桥批量优化](docs/reports/2026-10-24_布局与绑定桥批量优化.md)：**dev-1239** 已发布（SHA-256 `ca7f3dc5…`）；binding name→id/子 View 缓存、`view.attr({...})` 一次宿主调用；M3 fast calls 93→72，整页未稳定提速。
> M3Kit 原生 DEX：[M3Kit 原生 DEX 接入与宿主 ClassLoader 修复](docs/reports/2026-10-24_M3Kit原生DEX接入与宿主ClassLoader修复.md)：**dev-1232** 已发布（SHA-256 `8c8e16cd…`）；修复 ui.inflate 使用生成类 ClassLoader，m3ui.NativeView 可 inflate；M3Kit A/B/C/D + 交互 facade 已应用到 .4：NativeView/switch/nav/segmented/slider/textfield 状态 API 与 JS facade、main 默认 NATIVE_ALL（失败回退 JS）、XML loading/预取；MI8 all-native page ready 641–671ms；.4 需升级 1232。
> 本轮：dev-1209/1210 **canvas 原生场景绘制**（NodeUiCanvasView.setScene/clearScene，图元 JSON 原生批量绘制，脚本不再逐帧回调 JS）+ Flutter UI 重新打包；APK 144.73MB。
> 最新优化：[无障碍截图 maxWidth](docs/reports/2026-10-22_无障碍截图maxWidth优化.md)：dev-1208 透传 maxWidth + 缩略回读/CPU 回退 + A11Y_SPLIT；MI8 同轮全屏 p50 239 → 640宽 181ms（-24%），Canvas HARDWARE 缩略回读不支持待 `.4` 复测。
> 最新修复：[无障碍截图宿主直传像素](docs/reports/2026-10-22_无障碍截图宿主直传像素.md)：dev-1206 Raw `.n24r` / dev-1207 PFD 管道 + 三级回退；小米 8 20 帧 p50 169→122ms（p95 163/180，`.4` 待复测）。
> 最新修复：[三引擎浮窗统一注册与清理](docs/reports/2026-10-22_三引擎浮窗统一注册与清理.md)：OverlayRegistry + MCP `list_overlays`/`close_overlays`/`overlayCount`，dev-1205 小米 8 三引擎 stop 后 0 残留、滑动穿透验证。
> 最新修复：[选择器运行后编辑器卡顿](docs/reports/2026-10-22_选择器运行后编辑器卡顿修复.md)：主线程日志全量扫描/逐行排版已改为批处理，Node 节点日志摘要化；小米 8 满 2000 行打开由约 5 秒降到 319–731ms，三轮原始示例及编辑保存通过。K40 业务运行中保留 dev-1199。
> 此前修复：[DotJumpLoading 原生组件](docs/reports/2026-10-22_DotJumpLoading原生组件修复.md)：Rhino Widget.render 返回 Android View，补齐属性前的 onViewCreated；原包/修正版动画操作真机通过。
> 本轮：[APK 开关/按钮颜色统一](docs/reports/2026-10-22_APK开关与按钮颜色统一.md)已发布；[三引擎 API / 高级版全量契约](docs/reports/2026-10-22_三引擎API统一与高级版全量契约.md)首批 7 组示例双机通过，全部高级版 API 仍有缺口。
> 主架构已冻结，实验/垂直业务走 `sandbox/*` 分支（见 HANDOFF_AI §8）。
> 当前剩余项见 [`docs/REMAINING_WORK.md`](docs/REMAINING_WORK.md) §0；旧接手指南
> [`docs/reports/2026-09-12_接手指南_Codex.md`](docs/reports/2026-09-12_接手指南_Codex.md) 仅作历史参考。

## 先用 CodeGraph，再考虑 grep

本仓库已经建好 CodeGraph 索引（`.codegraph/`，被 git 忽略；用法见 `docs/guides/CODEGRAPH.md`），CLI 已在 PATH 上（`codegraph`）。

- 找「某功能怎么工作 / X 在哪 / 调用链 / 影响面」→ `codegraph explore "<符号名或一句话问题>"`
  （一次调用返回相关符号的原文 + 它们之间的调用路径，比 grep + 逐个 Read 准得多、也省 token）
- 读单个符号（含 caller/callee 线索）或按行号读整文件 → `codegraph node <symbol>` / `codegraph node <file>`
- 谁调用了它 → `codegraph callers <symbol>`；它调用了谁 → `codegraph callees <symbol>`；改动影响面 → `codegraph impact <symbol>`
- 只按名字找位置 → `codegraph query <名字>`
- 索引落后约 1 秒（有文件 watcher 自动同步）；必要时 `codegraph sync`，状态用 `codegraph status`
  （长时间没动过仓库时，`status` 可能报几十上百个 pending 文件，先 `sync` 再查，否则会「查不到」）
- ⚠️ `codegraph impact` 在 Kotlin/Compose 大文件上会把同构的 UI 类误连（同类 Activity/Fragment 一起报进来），
  **精度低，别直接当调用方用**；影响面建议落到具体成员上查 `codegraph callers <成员名>`

只有 CodeGraph 不索引的东西（YAML/JSON/文档/构建脚本）才回去用 grep / 直接读文件。

## 构建与测试

- 默认 JDK 是 8，**必须先切 17**：`. .\tools\jdk17.ps1 | Out-Null`
- 日常变体（MIUIX 皮肤）：`.\gradlew.bat :app:assembleMiuixCompatDebug --console=plain`
- ⚠️ `build-miuix-debug.ps1` 现在**编译成功后会默认自动发布**（复用产物发一版到更新源，版本号自动 +1，见桌面「编译并发布」/「发布更新」）：只想编译请加 `-NoPublish`；只想发布不重编用 `tools\publish.ps1 -SkipBuild`
- 单元测试：`.\gradlew.bat :app:testMiuixCompatDebugUnitTest`
- 与真 Auto.js Pro 同机安装冲突时（provider authority 被占，`INSTALL_FAILED_CONFLICTING_PROVIDER`）：
  用 `-PdevPluginUsbDiscovery=false` 构建去掉 debug-server provider（只影响 VS Code 扩展的 USB 自动发现，LAN 不受影响）
- 产物：`apps/app/build/outputs/apk/miuixCompat/debug/app-miuix-compat-arm64-v8a-debug.apk`
- 一键出 dev 包并发布到本地更新源：**`.\tools\publish.ps1 -ReleaseNotes "…"`**
  （带前置校验 + 发布后验证 + 自动日志；`-DryRun` 只算版本号不发布。详见下方「踩过的坑」一节）
- 只需要底层发布器时：`tools\dev-update.ps1 -NoServe -ReleaseNotes "…"`
  （版本号自动 +1，产物落在 `.artifacts/updates/`）—— ⚠️ 它**不受 `-NoServe` 保护，会真的发布**
- 装机验证：`C:\Android\platform-tools\adb.exe -s ce4d2bdb install -r <apk>`（小米 8 = 首选真机）

## 本仓库踩过的坑：发布与脚本（改之前先读）

这几条都是真实发生过的事故，不是理论风险。

### 插件 APK 只挑已签名的：`*-unsigned.apk` 发出去装不上

`assembleRelease` 在**没配 `AIJSPRO_*` 时产出的是 `*-release-unsigned.apk`**。第一次发插件时
`dev-update.ps1` 的「优先 release」正好挑了它，发布源里的 `apkbuilder-plugin.apk` 是未签名的：
手机能下到（HTTP 200、字节数正确）但**装不上**，打包页只会报「下载失败」。
现在 `dev-update.ps1` 一律排除 `-unsigned.apk`（同类里优先 release、其次 debug），
发布后也可用 `apksigner verify --print-certs <apk>` 复核。

### `tools/dev-update.ps1` 会发布，`-NoServe` 拦不住

`-NoServe` 只跳过**起 HTTP 服务**；**写 `update.json` + 拷贝 APK 的发布动作照常执行**。

- 用**任意假版本号**试脚本，会把这个假版本发布到真实更新源。曾用 `-VersionCode 9999` 试
  `-DeviceId`，结果更新源变成 9999、手机永远提示「发现新版本」，历史里也多出一条垃圾记录。
- **要验证参数解析或报错路径，用 `-DryRun`**（只算版本号并打印将要做什么；不构建、不发布、
  不写任何文件）。已验证：`-VersionCode 9999 -DryRun` 不会改动 update.json /
  project-versions.json / 更新历史。
- 不要用 `-SkipBuild` + 假 `-VersionCode` 去"只试一下"：那仍然会真的发布。`-SkipBuild` 时版本号
  读自现有 APK；不指定 `-VersionCode` 会退回读**仓库**的 `project-versions.json`（通常是 465），
  把历史污染成 465。

### 发布用 `tools/publish.ps1`，不要裸跑 `dev-update.ps1`

`tools/publish.ps1` 在 `dev-update.ps1` 之上包了前置校验与发布后验证：

- 用 `-s` 自己读手机 versionCode 算基准，再**显式**把版本号传给 `dev-update.ps1`
  （绕过它在多设备下把手机版本静默读成 0 的问题）
- 发布后校验 versionCode == 预期、SHA-256 与清单一致、**手机自己 curl 能拉到清单**，任一不符即失败
- 自动把完整输出写进 `.artifacts/publish-<时间戳>.log`
- 支持 `-DryRun`（只检查与算版本号，不发布）

```powershell
.\tools\publish.ps1 -ReleaseNotes "本版改了什么"     # 正式发布
.\tools\publish.ps1 -ReleaseNotes "..." -DryRun     # 只看会发什么版本
```

⚠️ `-AllowMultiDevice` 只放开**它自己**的检查；`-DeviceId` 必须一并传给下游
（漏传会在 `dev-update.ps1` 的「检测到 2 台设备」处直接失败 —— 这个调用点已经踩过一次）。

### 不要用 `Select-String` / `Where-Object` 过滤会调 gradle 的脚本输出

管道被提前关闭会**杀掉 gradle 进程**，而且 `$LASTEXITCODE` 会变成 cmdlet 的、不再反映真实结果。
本仓库已因此损失过两次构建，并发现在同一进程组里的常驻服务（`python -m http.server 8080`）
也会被一起干掉。

正确做法：`*> 日志文件` 再读文件，或用 `tools/publish.ps1`（它自己写 transcript 日志）。

### PowerShell 脚本的路径：别依赖 cwd 或 `$PSScriptRoot`

- `$PSScriptRoot` 在 `powershell -File` / 从别的目录调用时**可能不是你期望的值**。
  `tools/serve-updates.ps1` 曾把默认输出目录算成 `C:\.artifacts\updates`（仓库外），
  照样打印成功路径与 SHA —— 清单写丢了却毫无提示。现已改为用 `$MyInvocation.MyCommand.Path`
  推断仓库根并校验 `project-versions.json` 存在。
- 自己写临时脚本时：**先把目标路径 `Resolve-Path` 成绝对路径再 `Push-Location`**，
  否则相对路径会解析到临时目录里（本仓库已因此多次误判「文件不存在」）。

### PowerShell 5.1 的编码

- PS 5.1 读**无 BOM** 的 UTF-8 `.ps1` 会按 GBK 解码。含中文注释/输出的脚本会显示乱码，
  更糟的是中文字节可能让引号错位、直接**解析报错**（报错行号会指向完全无辜的代码行）。
- 本仓库脚本混用：`tools/*.ps1` 多数无 BOM，根目录 `release.ps1` / `build-miuix-debug.ps1` 有 BOM。
  **给脚本加中文控制台输出时，必须补 UTF-8 BOM。**
- ⚠️ 用文件编辑工具改完 `.ps1` 后要**回头确认 BOM 还在**——部分工具重写文件时会去掉它。
- 读这些脚本/JSON 时显式指定编码，别用 `Get-Content` 默认值：
  `Get-Content -Encoding UTF8` 或 `[IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)`。

### `$ErrorActionPreference = 'Stop'` 下跑原生命令会假失败

Gradle/JVM 会把 `注: 某些输入文件使用或覆盖了已过时的 API` 写到 **stderr**；
`Stop` 模式把原生命令的任何 stderr 当终止错误，脚本以 1 退出 —— **即使构建其实 BUILD SUCCESSFUL**。
判断成败只认 `$LASTEXITCODE`，必要时临时放宽 `ErrorActionPreference`。

## 仓库结构

- `apps/app`：Android 应用（Miuix/Compose 皮肤与旧界面并存）
- `modules/engine`：脚本引擎 + QuickJS 原生；`modules/engine-rhino`：Rhino 引擎；`modules/common`、`modules/automator`
- `apps/inrt`：打包产物用的运行时模板，输出到 `plugins/apkbuilder-plugin/src/main/assets/template.apk`；`third-party/`：第三方库
- `plugins/`：独立插件 APK（`xiangqi-plugin` 象棋引擎/识别、`apkbuilder-plugin` 打包模板）；宿主 APK 不再内嵌这些东西
- `samples/`：不随主 APK 分发的大型示例工程（`samples/象棋自动走棋` 随象棋插件分发）
- `docs/`：架构 / 指南 / 计划 / 报告，索引见 `docs/README.md`；`tools/`：构建与真机脚本
- `.artifacts/`：本地构建、截图、日志产物，**被 git 忽略**（点开头目录，部分 UI 预览不了里面的图）

## 注意

- 不要把 `.artifacts/`、`build/`、`*.log` 之类产物提交进 git
- 改过 `modules/engine/src/main/cpp/` 下的原生代码后，要先跑 `modules/engine/src/main/cpp/build-quickjs.ps1` 再构建，
  否则 `:app:verifyQuickJsNativeLibrariesFresh` 会失败
- 打包页的权限默认值不要随手改（产物的权限画像是风控敏感点，见 `docs/PERMISSIONS_AND_RELEASE.md`）
- 示例脚本文本改动要满足 `:app:verifyQuickJsSamplesMarked`、`:app:verifyBundledSamplesNoNode` 两个校验任务
- 主 APK 只保留「所有人都会用到」的东西：模板（打包）、象棋工程与 NNUE（象棋）都在 `plugins/` 下，
  体积与搬迁记录见 `docs/guides/安装包瘦身_插件与子集.md`
- 改了 `modules/engine/src/main/cpp/imgui_font.h` 的字形范围要跑 `python tools/imgui-font-subset.py --write`，
  否则 `:app:verifyImGuiFontSubset` 会失败
