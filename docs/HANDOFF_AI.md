# AI 接手总纲（AI_HANDOFF）

> **2026-10-25 候选验收通过、未发布**：[MCP 手机终端与编译入口](reports/2026-10-25_MCP手机终端与编译入口.md)。既有终端/MCP 共用底层，六项新工具；candidate-1245 已装 MI8，25 项本地、59 次 MCP 调用、6 项界面与真实 D8/Smali 编译通过。131.32 MiB / SHA `f72c0ceb…`，更新源仍 dev-1244，K40 仍 1199。完整 C/C++/Java 源码/Android 工具链另走可选插件，未声称支持端到端 APK 构建。

> **工具链范围（2026-10-25）**：用户决定不做手机端 Java/C/C++/APK 源码编译工具链，只保留 MCP 终端控制与既有 D8/Smali；工具链 A-E 插件/预研已回退。
> **当前发布 dev-1247（2026-10-25）**：MCP 无障碍 UI 8 项、应用管理 7 项、aijs-ui 终端桥、8 类工具目录；release+R8，SHA `0eac1ef5…`；MI8 已装。
> **MCP 无障碍 UI（2026-10-25）**：新增 ui_* 8 项、app_* 7 项与 aijs-ui 终端桥；candidate-1246 已装 MI8，未发布；receiver 真机 ping/home/click/swipe 通过，MCP 服务端 E2E 待启动服务后补测。
> **MCP 工具分类（2026-10-25）**：tools/list 增加 category/categoryTitle 与描述前缀，get_status.toolCategories 计数；指南按 8 类重排。
> **当前发布 dev-1247（2026-10-24）**：[更新下载修复与发布](reports/2026-10-24_dev1244更新下载修复与发布.md)。修复 R8 泛型丢失导致的内置更新 0%；增加最终 DEX 硬门，15 项网络测试通过。MI8 已装，并经真实 DownloadManager 下载 131.27MiB APK 到 100%、SHA 与实际更新器包校验通过；K40 未动。旧版内置下载器坏，须浏览器首次覆盖升级。下面 1243/1242 等为历史记录。

> **当前发布 dev-1243（2026-10-24）**：[发布与下载核验](reports/2026-10-24_dev1243发布.md)。大文件分享后台化、流式限额与失败清理、URI 授权及等待时间修复已随新 AOT 发布，131.27 MiB / SHA-256 `a2195faf…`；双机清单、MI8 完整下载一致，本轮未安装。7 项归档及 3 项 Flutter 检查通过；原手机实际分享仍待日志及复测。下面 dev-1242 等条目为历史记录。

> **2026-10-24 已发布 dev-1242（开发通道）**：[发布与下载核验](reports/2026-10-24_dev1242发布.md)。五项优化已发布，APK **131.27 MiB**，SHA-256 `9e3616b2…`。两台手机均能拉到新版清单，MI8 完整下载哈希一致；本轮没有安装，K40 仍 1199、MI8 仍候选 1241，供用户自行更新。下方“候选未发布”等描述为发布前的历史记录。


> **2026-10-24 五项优化已验收（未发布）**：[五项优化任务与验收](reports/2026-10-24_五项优化任务与验收.md)。更新源仍 **dev-1240**；MI8 当前安装 **candidate-1241**（测试签名、非 debuggable，131.27 MiB，SHA-256 `22888845…`）。M3/缓存/保守 R8/release/三引擎探针及诊断已完成；35 条公共行为、20 轮 UI / 28 断言和 M3 交互通过，最终安装后无新增 crash。高级版全量行为、真正 16KB 内核及原 Android 16 设备仍待专项验证。下方版本与装机描述为历史记录，以本条和链接报告为准。


> 面向所有接手的 AI（Codex / Claude / Copilot / Cursor / dsh 等）。
> 先读本文，再读 `AGENTS.md`（构建与踩坑细节）和 `docs/reports/2026-10-18_收尾交接_给Codex_v2.md`（最新进度）。
> 最后更新：2026-10-24；更新源 **dev-1240**（SHA-256 `399d7e815edd6aea8a7b23cd29ead486055968a7a27839992fb3f540f858e70d`）：[原生 setup 延迟与控制台懒加载](reports/2026-10-24_原生setup延迟与控制台懒加载.md) 已发布；`ui.afterFirstFrame` + 控制台懒加载，setup tail 1817→1558ms（-14%）；MI8 已装 dev-1240，K40 未操作。
> 同日发布复核：[dev-1221 发布核验](reports/2026-10-23_dev1221发布核验.md)：MI8 已装包与更新源哈希一致，冷启动后三引擎退出回归 47 项通过、主 PID 不变、浮窗清理为 0、crash buffer 为空；本机 VS Code 扩展为 0.3.5。下列旧版装机/更新源描述均为对应时点记录，当前状态以本条及 PROJECT_STATUS 为准。
> 前一版本：[Node24 动态 UI 底层修复](reports/2026-10-23_Node24动态UI底层修复.md)，dev-1227 已发布并装 MI8；此前 749 JVM、18 组 Node 契约、候选版 20 轮构建及退出回归保留为对应版本证据。当前发布及装机状态以上方 dev-1232 条目为准；Android 16 原问题设备未连接。
> 插件更新：**象棋引擎插件1.1.0/code3**，通用/优化ARM64预算引擎及Node异步API已发布为下载附件；K40完整链路通过，新手机仍未连接。见[本轮记录](reports/2026-10-23_象棋引擎插件预算升级.md)。
> 插件修复：**1.1.1/code4** 已在K40更新，修复Contempt反向奖励部分重复和棋；原始失败用例与新包双版本30项分析通过。见[拒和评分修复](reports/2026-10-23_象棋引擎拒和评分修复.md)。宿主更新源仍dev-1210，未改主APK。
> 浮窗底层交付：**dev-1221** 已发布，[Node 浮窗底层修复](reports/2026-10-23_Node悬浮窗底层优化.md)：reload(xml) 热替换、多锚点拖动、raw/hide-show/getSize；模块 1.2.0，17 项断言 ALL_PASS，APK 交付手机两目录。
> 宿主已发布：**dev-1213**（dev-1211 修复的发布版），[悬浮窗退出崩溃修复](reports/2026-10-23_悬浮窗主线程退出崩溃修复.md)：709 JVM、17 真机回调场景、Aurora 原脚本通过。MI8 已装 dev-1213，更新源 1213；Redmi Android 16 未连接待验，K40 象棋保持运行（dev-1199）。MCP wait 超时仅返回状态，不会自动停止脚本。
> 最新：dev-1210 在 [无障碍截图 maxWidth 优化](reports/2026-10-22_无障碍截图maxWidth优化.md) 基础上合入 canvas 场景绘制：NodeUiCanvasView.setScene/clearScene 接收图元 JSON 后由原生 draw 批量绘制，脚本不再逐帧回调 JS。
> 此前 [无障碍截图宿主直传像素](reports/2026-10-22_无障碍截图宿主直传像素.md)：dev-1206 Raw `.n24r` / dev-1207 PFD 管道 + 三级回退，小米 8 20 帧 p50 169→122ms。
> 此前 [三引擎浮窗统一注册与清理](reports/2026-10-22_三引擎浮窗统一注册与清理.md)：OverlayRegistry + MCP list_overlays/close_overlays/overlayCount，三引擎 stop 后 0 残留。
> 此前 [示例目录懒加载与插件发现优化](reports/2026-10-22_示例目录懒加载与插件发现优化.md)：离屏页不扫描、切页复用目录、刷新/重试保留。K40 业务运行中保留 dev-1199。
> 此前 [选择器运行后编辑器卡顿修复](reports/2026-10-22_选择器运行后编辑器卡顿修复.md)：主线程日志全量扫描/逐行排版改为批量刷新，节点日志摘要化；小米 8 2000 行日志打开由约 5 秒降到 319–731ms，原始示例三轮及日志/编辑保存通过。K40 业务运行中保留 dev-1199。
> 此前 [DotJumpLoading 原生组件修复](reports/2026-10-22_DotJumpLoading原生组件修复.md)：原生 View render 与初始化顺序，小米 8 实测 19 + 9 + 9 + 2 项通过。
> 此前 [APK 开关/按钮颜色统一](reports/2026-10-22_APK开关与按钮颜色统一.md)及[三引擎 API 首批对齐](reports/2026-10-22_三引擎API统一与高级版全量契约.md)保持。dev-1199 的 12 Flutter / 693 JVM / QuickJS 225 项通过，7 组共用示例和 MCP 直接调用双机通过，高级版全量 API 仍未全部实现。
> 此前 [Flutter 升级与 16KB 修复](reports/2026-10-22_Flutter升级与16KB兼容修复.md) 保持：Flutter 3.47.7 / Dart 3.13.5，engine/embedding/AOT 配套，发布无白名单检查全部 22 个 64 位 ELF；真正 16KB 内核仍待验收。
> 此前 [三引擎及 Pikafish 修复](reports/2026-10-21_三引擎细节复核与原生闪退修复.md) 保持；象棋插件 APK 1.0.1 / 2。原 `.4` 象棋设备长期对局待验收。工作区尚未提交。
> 电脑端扩展已更新 **0.3.3**：[设备选择修复](reports/2026-10-21_VSCode设备选择修复.md)，显式设备列表、USB 扫描与双机转发已验证。
> dev-1186 [自定义组件与重复窗口修复](reports/2026-10-21_Node自定义组件与重复窗口修复.md)：colored-button 真实事件/属性、统一应用任务与单次返回。
> dev-1184 的 [示例目录运行入口修复](reports/2026-10-21_示例目录运行入口修复.md)：React / Vue3 在尚无 dist 时从开发源码运行，19 个内置工程入口已检查。
> 最新续修先读：[Dex、插件、Web UI 与 K40 修复](reports/2026-10-21_Node_Dex插件WebUI与K40修复.md)：真实 Dex 回收、SDK v1/v2 与实际插件、Web UI 14 项、原始 React/Vue3 开发编译及热更新已验证；K40 日志按钮和输入提示已恢复。
> 此前任务/设置验证见 [Node 原生 UI / 任务 / 设置](reports/2026-10-21_Node高级版兼容_原生UI任务设置.md)。第三方强制依赖旧 V8 的插件、外部非编译 res 子集、完整 OpenCV 与 44 候选全量仍需分别验收，不能宣称全部兼容或三引擎等价。

---

## 0. 一句话

这是 **AI.js Pro（Flutter Glass POC）**：Android 上兼容 Auto.js Pro 的脚本工具。
当前主架构 = **Flutter-only UI + Rhino/QuickJS/Node24 三引擎 + Node 进程池**，通过本地更新源发布 dev 通道包。

**主架构已冻结为现在这一版**（见 §3）。与主架构无关的实验/垂直业务放在 `sandbox/*` 分支，不合入主线。

---

## 1. 接手第一小时怎么干

```powershell
cd C:\Users\18101\Documents\ai-js-pro
. .\tools\jdk17.ps1 | Out-Null                 # 必须先 JDK17（默认是 8）
adb devices                                       # K40 cccc62c7 是首选测试机
Invoke-RestMethod http://192.168.10.7:8080/update.json | Select versionCode,versionName   # 当前发布版本
git log --oneline -10                             # 最近改动
```

- 构建产物：`apps/app/build/outputs/apk/miuixCompat/debug/app-miuix-compat-arm64-v8a-debug.apk`
- Flutter AOT + dev 包：`tools/flutter-poc/build-aot.ps1`（生成 `output/flutter-poc/payload` 的 libapp.so）→
  `tools/flutter-poc/publish-poc-build.ps1 -VersionCode N -VersionName "dev-N"`（自动 AOT + gradle 构建，产物在标准路径；
  带 `-SkipAot` 复用上次 AOT）→ `tools/publish.ps1 -ReleaseNotes "..." -SkipBuild -PcHost 192.168.10.7 -AllowMultiDevice -DeviceId cccc62c7`
- **版本号必须等于** `max(仓库 project-versions, 已发布, 设备版本)+1`，否则 `publish.ps1` 会校验失败。
- 设备安装：`adb -s cccc62c7 install -r <apk>`（新机 `DQKFJB59596L45BI`、MI8 `ce4d2bdb`）。

⚠️ 雷区（详细原因见 `AGENTS.md`）：不要用管道过滤 gradle 输出；`.ps1` 保留 UTF-8 BOM（含中文路径的脚本必须带 BOM，否则 PS5.1 按 GBK 解码会把中文路径读坏）；Dart 改动改 `flutter-ui/` 后必须重建 AOT；不要裸跑 `dev-update.ps1`。

### Flutter AOT / dev 包流水线（2026-10-20 重建 + 源码入库）

- **Dart 源码已入库**（2026-10-20 起）：`flutter-ui/`（`lib/`、`pubspec.yaml/lock`、`third_party/`、pubspec 引用的 `assets/`）。
  不再依赖个人电脑上的外部重建线（历史路径仅作背景）。
- **payload 基础已入库**：`flutter-payload-base/`（`jniLibs/arm64-v8a/libflutter.so`、`flutter_embedding_release.jar`、
  `runtime.json` 版本/SHA 锁定；LFS + 普通 git；不含生成的 `libapp.so`）。引擎与 embedding 来自同一 SDK。
- 流水线脚本：`tools/flutter-poc/build-aot.ps1` + `tools/flutter-poc/publish-poc-build.ps1`（仓库内跟踪）；
  运行时工作目录 `output/flutter-poc/`（gitignore，删除后可自动重建）。
- AOT 构建：`flutter-ui/{lib,assets,third_party,pubspec*}` 同步到 `output/flutter-poc/src` → `flutter pub get`（默认在线；`-Offline` 可选）→
  `flutter assemble --output build --define=BuildMode=release --define=TargetPlatform=android-arm64 android_aot_bundle_release_android-arm64`
  → `build/arm64-v8a/app.so` 复制为 `payload/jniLibs/arm64-v8a/libapp.so`；engine / embedding 从 `flutter-payload-base/` 补齐；assets 来自本次 `build/flutter_assets`，不再复用旧基线资产。
- Flutter SDK 固定 Flutter **3.47.7 / Dart 3.13.5**（本机 `C:\Android\flutter-3.47.7`，不在 PATH 上；换机需安装同版本）。
- 构建命令必须带 `-PflutterPoc=true`；同机装有正版 Auto.js Pro 时还要加 `-PdevPluginUsbDiscovery=false`（否则
  provider authority 冲突，install 报 `INSTALL_FAILED_CONFLICTING_PROVIDER`）。
- 出包脚本会自动临时改写 `project-versions.json` 注入 versionCode/Name，构建结束后恢复——**不要手工改它**。
- 关于页：Flutter 通过桥方法 `aboutInfo` 读真实版本/作者/git 提交；git 信息由 `apps/app/build.gradle` 构建时注入
  `BuildConfig.GIT_*`（无 git 环境回退 unknown）。

- **`miuixCompat` 只是保留的 Gradle 变体名**（不代表 UI 还是 Miuix）；完整可安装包必须带 `-PflutterPoc=true`，
  不带该参数的构建只适合引擎/单测等非 UI 验证。
- **本地 Debug 安装 vs dev 发布是两套命令**：本地用 `gradlew :app:assembleMiuixCompatDebug -PflutterPoc=true ...` + `adb install`；
  dev 发版用 `tools/flutter-poc/publish-poc-build.ps1 -VersionCode N -VersionName "dev-N"` → `tools/publish.ps1 -SkipBuild ...`。
  完整命令、真实工具链版本（JDK17 / Gradle 8.9 / AGP 8.6.1 / Kotlin 2.1.0 / Flutter 3.47.7 / Python+fonttools /
  NDK r27c / CMake≥3.18.1 / Ninja 1.13.2）与原生 `.so` 复用/重编规则见 [`docs/guides/编译指南.md`](guides/编译指南.md)（2026-10-20 固化）。

---

## 2. 仓库地图

| 目录 | 作用 |
|---|---|
| `apps/app` | Android 主 App（`main` 源集 = 核心 + Flutter 入口类；`flutterpoc` = Flutter POC 入口/策略；`miuix` = 已排除出构建的旧 UI，仅保留 MCP/权限逻辑，已 git mv 到 main） |
| `modules/engine` | 脚本引擎：Rhino / QuickJS（含 `quickjs_jni.cpp`）/ Node24 宿主桥（`org.autojs.source.engine.android`）、Node 资产 `assets/node24/` |
| `modules/automator` | 无障碍/自动化服务 |
| `modules/engine-rhino` / `common` | Rhino 运行时 / 公共库 |
| `apps/inrt` | 打包运行时模板（输出到插件 assets 的 `template.apk`） |
| `plugins/` | apkbuilder 打包插件、xiangqi 象棋插件（引擎/识别） |
| `flutter-ui/` | Flutter Glass UI 的 Dart 源码（唯一真源，AOT 由它编译） |
| `flutter-payload-base/` | Flutter 3.47.7 payload 基础（libflutter.so / embedding jar / runtime.json，LFS） |
| `samples/` | 大型示例工程（象棋自动走棋等） |
| `docs/` | 架构/指南/报告；`docs/HANDOFF_AI.md` 即本文 |
| `tools/` | 构建/发布/真机脚本 |
| `output/` / `.artifacts/` | 构建产物与证据，**被 gitignore** |

---

## 3. 主架构（冻结版，2026-10-20）

```
主进程 main
  ├─ Flutter UI（唯一 UI；SplashActivity → GlassFlutterActivity；AOT libapp.so 随包）
  ├─ 路由/调度：PocRuntime / PocBridge / PocNode24Jobs / 任务页数据
  ├─ 自动化 & 无障碍（AccessibilityService、automator）
  ├─ Node UI 主进程桥：NodeUiBridgeService / Node24LocalOverlay 回退 / NodeUiDialogActivity
  └─ 打包、MCP(8765)、电脑连接(9317)、更新检查

:script 进程（ScriptHostService）
  ├─ Rhino 引擎执行
  ├─ QuickJS 引擎执行（含 threads worker）
  ├─ Node 执行编排：Node24Pool / PocScriptHost token / console / STOP / 任务
  └─ engines.execScriptFile 子引擎

:node24_1 / _2 / _3 进程（Node24PoolService，每进程单 Node runtime）
  ├─ Node24Engine（libnode 24.22.0 + libajsnode24bridge.so 同进程 JNI）
  ├─ Node24HostBridge：$autojs / $java / ui / image / media / notification / accessibility / runtime / engine
  ├─ Node24LocalOverlay：Node 脚本浮窗「进程内」WindowManager overlay（优先；失败回退主进程）
  └─ Node24CaptureConsentActivity1/2/3 + 主进程 Node24CaptureRelay（MediaProjection 授权）
```

关键设计决定（**不要再回退**）：
1. **UI 只有 Flutter**，Miuix 页面已排除构建（`src/miuix` 仅保留非 UI 逻辑；不要重新引入）。
   Gradle 变体名 `miuixCompat` 只是历史命名，**不要据此判断 UI 仍是 Miuix**。
2. **Node 引擎在独立池进程**（每进程单 runtime；同进程多实例不可行，原生桥单会话）。多脚本并行走池分配；池满排队；stop 按 token 定点；池进程崩溃 invalidate + 自动重拉；`:node24` legacy 通道保留做回退 flag。
3. **Node 浮窗走进程内 overlay**（`Node24LocalOverlay`），不再依赖主进程跨进程 show（大 XML 会丢 reply/超时）。
4. 任务页/通知栏/悬浮菜单/stopAll 统一走 `:script` 的 token 注册表（`PocScriptHost` / `PocNode24Jobs`），不要新增第二套任务登记。
5. 引擎选择：脚本头 directives（`"auto"`/`"ui"`/`"nodejs"`/engine 注释）→ 路由到对应引擎/进程。

---

## 4. 当前状态（dev-1177）

| 领域 | 状态 |
|---|---|
| Flutter UI（去 Miuix） | ✅ 逐页验收通过；APK 约 127MB |
| Rhino / QuickJS / Node 三引擎 | ✅ 全部可用；回归脚本见 `/sdcard/脚本/00-qjsregress` |
| Node 进程池 / 多脚本并行 / 停止 | ✅ 正式包验证 |
| 浮窗（XML/真实 View/拖动/点击/通知栏停止） | ✅ |
| 日志对齐 Pro（6 位小数/统一日志页/静默停止） | ✅ |
| xiangqi-aj-node Node 原生界面 | ✅ 全链路（截图→识别 32 子→Pikafish 建议着法）；修复包 `output/dist/xiangqi-aj-node-0.6.80-fixed.zip` |
| 打包（免插件/内置模板）/inrt 定时任务 | ✅ |
| 「全 Node 化」图像/象棋识别性能移植 | 🚧 在 `sandbox/node24-image-xq`（快照），主线只保留稳定 API |

回归基线（跑前先看 `docs/REMAINING_WORK.md` §0）：
- QuickJS 全模块 227/227；
- Rhino 行为 22/22；Node 行为 21/21；Node 颜色 6/6；
- Node UI basic 8 steps；
- 三引擎示例账本在 `output/mi8-three-engines-20261019/`。

---

## 5. 真机测试套路（别自己发明）

1. **单工程 + 结果 JSON** 最稳：把脚本推到 `/sdcard/脚本/<probe>/`，用 RunIntent 或 UI 运行，结果写 `/sdcard/.../result.json` 再 `adb pull` 断言。
2. 启动脚本：`adb shell am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity --es path /sdcard/脚本/xxx.js`
3. 要干净环境：`am force-stop com.jdkshen.aijspro`，并清 `shared_prefs/external_run_resume.xml`（否则会断点续跑上次脚本）。
4. 日志过滤用 tag：`Node24Service|Node24Engine|Node24Console|NodeUiBridgeService|Node24Pool|PocScriptHost|GlobalConsole|C-OVERLAY|C-LOCAL|C-UI|C-REMOTE`。
5. 重装 APK 后 Android 会关闭无障碍服务，需要重新开启；MediaProjection 每次授权一次。
6. 用 uiautomator dump 时注意：overlay 内容不在 dump 里（只有 Activity 节点）。

---

## 6. 发布与版本

- 更新源：`http://192.168.10.7:8080/update.json`（PC 常驻 8080，docroot = `.artifacts/updates`）
- APK 命名：`AI-js-Pro-Flutter-dev-<versionCode>.apk`
- 版本规则：`expected = max(repo project-versions, update.json, 在线设备 versionCode) + 1`；APK versionCode 必须等于 expected。
- 发布前确认 `project-versions.json` 会被脚本临时改写后还原；发布后用 `Invoke-RestMethod update.json` 复核。
- 每日 03:30 有自动提交任务（GitHub main）；也可以随手提交。

---

## 7. 已知缺口 / 不要走的弯路

- `dialogs.customView` 已支持（XML 协议）；Node 端 View 跨进程不可直接挂载，统一走 XML/句柄。
- Node 单进程单 runtime：不要试图在 `:script` 内起两个 Node runtime（原生桥直接拒绝）。
- 继承 Java 类/`defineClass` 仅中文提示，未实现（需要 Java→Node 反向回调）。
- 无障碍事件在 `:script` 是代理子集；Rhino `:script` 无完整事件对象。
- inrt 仅 disposable/daily/weekly 任务；`addActivityIntentTask` 未实现。
- 插件自带旧模板（70MB）未同步；宿主打包优先内置瘦身模板。
- About/License 仍英文（需 `flutter_localizations`）；MCP 端口 8765（不是 8766）。
- 历史失败示例/审计结论见 `docs/reports/`，不要重复踩。

---

## 8. 分支与实验隔离策略

- `main`：**主架构 + 稳定能力**，只接受能通过回归基线的改动。
- `sandbox/*`：与主架构无关的实验/垂直业务（如 node24 图像/象棋识别移植、性能探索）。需要时从 main 切出，验证稳定后再单独评估合入；**不要直接把实验提交推 main**。
- 当前快照：`sandbox/node24-image-xq`（dev-1154 时点的全 Node 化图像/象棋识别工作），`main` 已包含与主架构相关的基础 API（`Image.getPixels/matchTemplate` 等）。
- 标签：`arch-baseline-20261020` 标记本次冻结的主架构基线。

---

## 9. 关键文件速查

| 主题 | 文件 |
|---|---|
| Flutter 入口 | `apps/app/src/flutterpoc/java/.../GlassFlutterActivity.java`、`SplashActivity` |
| App 入口/进程路由 | `PocRuntime.java`、`PocBridge.java`、`PocServices.java` |
| :script 宿主 | `scriptproc/ScriptHostService.java`、`PocScriptHost.java` |
| Node 池/客户端 | `Node24PoolService*.java`、`Node24Pool.java`、`Node24Client.java`、`Node24Service.java` |
| Node 桥 | `Node24HostBridge.java`、`Node24LocalOverlay.java`、`Node24CaptureRelay.java` |
| Node 运行时 JS | `modules/engine/src/main/assets/node24/prelude.js`、`ui.js`、`modules/*.js` |
| QuickJS | `modules/engine/src/main/cpp/quickjs_jni.cpp`、`assets/modules/qjs-*.js` |
| UI 桥 | `engine/NodeUiActivity.java`、`NodeUiBridgeService.java`、`NodeUiDialogActivity.java` |
| 自动化 | `modules/automator/`、`AccessibilityEventForwarder.kt` |
| 打包 | `PocPackaging.java`、`apps/inrt/`（生成插件模板）；主 APK 瘦身模板 `apps/app/src/main/assets/template.apk`（LFS 预编译资产，存在时 `verifySlimPackagingTemplate` 严格校验） |
| 更新/发布 | `tools/publish.ps1`、`tools/dev-update.ps1`、`tools/flutter-poc/publish-poc-build.ps1` |
| Flutter AOT | `tools/flutter-poc/build-aot.ps1`；Dart 源码 `flutter-ui/`；payload 基础 `flutter-payload-base/` |
| 关于页/构建信息 | `apps/app/build.gradle`（GIT_* BuildConfig）、`PocAppInfo.aboutInfo`、外部 `lib/settings_page.dart` |

---

## 10. 交接检查清单（离开前）

- [ ] `git status` 干净或在 `sandbox/*` 分支上
- [ ] 改过的能力有真机证据（logcat/截图/result JSON），路径写进 `docs/reports/`
- [ ] 新版本号已按规则发布，`update.json` 与设备一致
- [ ] 更新 `docs/reports/2026-10-18_收尾交接_给Codex_v2.md`（最新版本段）与 `docs/REMAINING_WORK.md` §0
- [ ] 不把 `.artifacts/`、`output/`、`build/` 提交进 git
