# AI.js Pro 剩余细节审计与收尾清单

> **2026-10-25 MCP 终端**：[新增工具与编译边界](reports/2026-10-25_MCP手机终端与编译入口.md)。candidate-1245 已装 MI8 并通过终端、真实 D8/Smali 编译和界面验收；尚未发布，源仍 1244。完整 C/C++/Java/Android 工具链可选插件尚待实现，不能把终端接入等同于完整 APK 构建已验收。

> **当前发布 dev-1244（2026-10-24）**：[更新下载修复与发布](reports/2026-10-24_dev1244更新下载修复与发布.md)。R8 丢失网络泛型已修，15 项网络测试、最终 DEX 硬门、MI8 实际应用下载与包校验通过。原手机首次升级用浏览器覆盖安装；原场景分享、高级版全量行为、真正 16KB 内核仍保留验收。下面版本为历史记录。

> **当前发布 dev-1243（2026-10-24）**：[发布记录](reports/2026-10-24_dev1243发布.md)。大文件分享后台化等修复已发布，AOT 重建、7 项归档/3 项 Flutter、签名与 22 ELF 静态检查通过，双机可拉到更新，未安装。原手机实际分享/本次堆栈、高级版全量行为和真实 16KB 内核仍待验证。下方版本为历史记录。

> **2026-10-24 已发布 dev-1242（开发通道）**：[发布与下载核验](reports/2026-10-24_dev1242发布.md)。五项优化已发布，APK **131.27 MiB**，SHA-256 `9e3616b2…`。两台手机均能拉到新版清单，MI8 完整下载哈希一致；本轮没有安装，K40 仍 1199、MI8 仍候选 1241，供用户自行更新。下方“候选未发布”等描述为发布前的历史记录。


> **2026-10-24 五项优化已验收（未发布）**：[五项优化任务与验收](reports/2026-10-24_五项优化任务与验收.md)。更新源仍 **dev-1240**；MI8 当前安装 **candidate-1241**（测试签名、非 debuggable，131.27 MiB，SHA-256 `22888845…`）。M3/缓存/保守 R8/release/三引擎探针及诊断已完成；35 条公共行为、20 轮 UI / 28 断言和 M3 交互通过，最终安装后无新增 crash。高级版全量行为、真正 16KB 内核及原 Android 16 设备仍待专项验证。下方版本与装机描述为历史记录，以本条和链接报告为准。


> **dev-1229 已发布并装 MI8（2026-10-23）**：[Node M3 启动优化](reports/2026-10-23_Node_M3启动性能优化.md)。最终 major 51 / dx、并发定义与 Java 回调、冷/重复启动、滚动/切页、一次返回及编译中停止恢复已验证。发布包 28 项 UI、三次重建、两轮构建及 7 项动态类通过；750 JVM / 19 组 Node 契约通过。仍需更大样本完整入口首帧/帧耗时、原 Android 16 设备和生成类二次继承 final super helper 缺口，未关闭高级版全量兼容待办。

> dev-1223 已发布（2026-10-23）：[Node24 动态 UI 底层修复](reports/2026-10-23_Node24动态UI底层修复.md)已在 MI8 验证动态 XML、Widget 容器、接口监听、系统资源及三次 recreate。749 JVM、18 组 Node 契约及候选版 26 项功能断言/20 轮带日志构建通过；实际发布包另通过 26 项断言/三次重建/两轮构建，安装哈希与更新源一致。Android 16 原机卡死、系统配置变更/编译资源布局的重建及第三方组件全量仍待处理。

> dev-1221（2026-10-23）：[最新发布核验](reports/2026-10-23_dev1221发布核验.md)完成，小米 8 已安装且 APK 哈希与更新源一致；冷启动后三引擎退出回归 47 项通过，进程存活、浮窗清理正常。Node 浮窗 1.2.0 的 reload/多锚点拖动见[底层优化记录](reports/2026-10-23_Node悬浮窗底层优化.md)。原 Redmi Android 16、真正 16KB 内核、native 图形栈长跑及高级版 API 全量行为仍需专项验收，不能据此关闭。下文旧版本条目保留历史上下文。

> dev-1201（2026-10-22）：[选择器运行后编辑器卡顿修复](reports/2026-10-22_选择器运行后编辑器卡顿修复.md)已发布，小米 8 已安装。真机堆栈定位日志历史反复扫描/逐行排版，2000 行日志打开由约 5 秒降到 319–731ms，原始示例三轮、三引擎日志、编辑保存通过。视频设备未接入 ADB，仍需其更新后的同场景确认；不能据此关闭下面历史退出 ANR 或高级版 API 缺口。

> dev-1200（2026-10-22）：[DotJumpLoading 原生组件修复](reports/2026-10-22_DotJumpLoading原生组件修复.md)已发布。Rhino 原生 View render / 属性初始化修复，小米 8 原包、修正版及组件回归通过；K40 保留业务运行中的 dev-1199。QuickJS/Node JavaAdapter 类继承和高级版其余 API 缺口继续保留。

> dev-1199（2026-10-22）：[APK 开关与按钮颜色统一](reports/2026-10-22_APK开关与按钮颜色统一.md)已发布并双机安装；12 Flutter / 693 JVM / QuickJS 225 项通过。[三引擎 API / 高级版全量契约](reports/2026-10-22_三引擎API统一与高级版全量契约.md)已建立完整声明账本，首批 7 组公共示例、存储交接、MCP 直接调用双机通过，仍需其余模块行为验收。

> dev-1197（2026-10-22）：[Flutter 3.47.7 / Dart 3.13.5 与 16KB 修复](reports/2026-10-22_Flutter升级与16KB兼容修复.md) 已发布。引擎/embedding/AOT、现代 shader、Node libc++ 与发布硬门完成；22 ELF 无白名单检查、337 JVM、双机回归通过。**仍需真正 16KB 内核下的启动/OCR/DNN/插件/长跑验收**，两台现有手机的页大小均为 4096。

> dev-1194：[三引擎细节复核与原生闪退修复](reports/2026-10-21_三引擎细节复核与原生闪退修复.md)。Node 并发身份/子执行归属/存储、QuickJS 参数/文件、Rhino 精确找色/远程 exists、MCP Java 对象查询已修复并回归。Pikafish 非法输入致命退出已修，插件 APK 1.0.1 / 2；出问题的 `.4` 象棋机尚未验收最终包长期对局。Node `trace_events` 当前 Android 构建不可用。下面 dev-1112 清单保留历史上下文，不能作为当前功能已实现/未实现的唯一依据。

> dev-1190 补充：[MCP、电脑连接与日志优化](reports/2026-10-21_MCP界面电脑连接与日志优化.md)。电脑连接改用自家扩展 0.3.2（9317/8327），不再维护旧 Pro 电脑连接路径；统一实时日志、跟随与清空已修复。引擎原有兼容范围不因此缩减。

> dev-1188 补充：[MCP 三引擎与 Node 传统 UI 修复](reports/2026-10-21_MCP三引擎与Node传统UI修复.md)。三引擎 MCP 运行/查询/调用/语法检查/任务控制已接入，两台各 42 次请求通过；传统样例的真实网格、列表、开关、增删和 Canvas 通过。复杂模板/外部控件、引擎本身尚未实现的 API 不在此结论内。

> dev-1186 补充：[自定义组件与重复窗口修复](reports/2026-10-21_Node自定义组件与重复窗口修复.md)。colored-button 事件/属性、默认颜色已在 K40 / 小米 8 验证；Node UI 默认在主应用任务中运行，K40 按一次返回到示例列表。其余原生 View 事件仍需逐项扩展验证，不能泛称全部兼容。

> dev-1184 补充：[示例目录运行入口修复](reports/2026-10-21_示例目录运行入口修复.md)。此前源码直接运行通过没有覆盖目录按钮；现已修正 React / Vue3 的开发入口选择，9 项单测含全部 19 个内置工程入口通过。

> 2026-10-21 最新续修：[Dex、插件、Web UI 与 K40 修复](reports/2026-10-21_Node_Dex插件WebUI与K40修复.md)，已发布 dev-1182。Dex 同进程真实回收、SDK v1/v2 及实际打包/象棋插件、Web UI 14 项、原始 React/Vue3 开发编译与热更新均已验证；K40 内置 M3 的日志按钮和输入提示已恢复。仍需验收第三方旧 V8 强制类型依赖、外部非编译资源子集、完整 OpenCV 和完整 44 候选；三引擎全量 API 不能据此认定等价。此前任务/设置记录见 [原生 UI / 任务 / 设置](reports/2026-10-21_Node高级版兼容_原生UI任务设置.md)。

> 当前更新：2026-10-19（dev-1112）
> 基线：`main` / HEAD `45fae285` / dev 更新通道 dev-1112
> 当前包名：`com.jdkshen.aijspro`；仓库版本 `1.0.2 (465)`
> 主要验证设备：K40 `cccc62c7`（Android 13 / API 33）、Mi8 `ce4d2bdb`
>
> 说明：下文 2026-09-08 的 P0/P1/P2 原始清单保留为历史审计记录；**当前剩余项以 §0 为准**。

## 0. 当前剩余工作（dev-1112，按优先级）

### P0 · 发布前必须收口

- [ ] **正式 release 配置复核**：签名、版本号、权限画像、覆盖安装/全新安装/降级拒绝仍需逐项复验（历史清单 §2.2 中未勾选项仍然有效）；
- [ ] **targetSdk 提升专项**：当前仍为 28；提升前必须回归存储、前台服务、通知、悬浮窗、包可见性和 APK 安装；
- [ ] **三引擎行为契约**：QuickJS/Rhino/Node 的“同名 API”需按参数、异常、线程、资源释放逐项验收；不能只以回归用例数量或 API 数量宣称等价；
- [ ] **`:script` 自动化代理边界**：Rhino/QuickJS 仅覆盖常用面（选择器链、坐标手势、截图、`auto.service` 代理）；无完整 `AccessibilityEvent`/`event.source`，服务状态变更非实时，事件未节流；
- [ ] **Node24 缺口**：真实 Java 子类/`defineClass`、部分 Pro 模块面（高级浮窗、无障碍更多 API、@autojs/opencv 等）仍不完整；`work_manager.add` 返回 `id:0` 需复核（旧记录，后续版本请实测确认）；
- [ ] **inrt 限制**：`addActivityIntentTask` 未实现；广播 Intent 动态 receiver 仅在主进程存活期有效；任务 path 仅支持打包工程内相对路径或绝对路径；
- [ ] **插件模板同步**：`plugins/apkbuilder-plugin/assets/template.apk` 仍是 70MB 旧全量模板，待下一次插件发版时用新 inrt release 整体重建（宿主打包优先使用内置瘦身模板）。

### P1 · 交付质量与一致性

- [ ] **Flutter POC 与 Miuix 双轨回归**：dev 通道 Flutter 页面与 Miuix 回退入口的行为/主题/返回键需成对验证（尤其设置、日志、工作区、打包、悬浮控制条）；
- [ ] **弹窗/菜单统一**：冷启动无障碍引导等 Material 弹框仍是纯白直角，与 Miuix/玻璃界面不一致；
- [ ] **大字体/横屏/安全区矩阵**：首页、编辑器、示例、资源、任务、MCP、设置、悬浮窗仍需覆盖；
- [ ] **性能基线**：没有 Macrobenchmark/JankStats；冷启动、长列表、悬浮窗、引擎长跑的 P50/P95 与前对比仍需同条件数据；
- [ ] **远程运行/require 真实 VS Code 环境复验**：协议帧闭环已做，仍需用真实扩展完整走一遍；
- [ ] **editor 退出偶发 ANR**：历史记录过一次，未稳定复现；需要保留复现条件与 trace；
- [ ] **`:native`/`:script`/`:node24` 多进程设置同步**：多进程 Pref 的读取快照已有评估，新增设置项需确认跨进程可见性。

### P2 · 持续整理

- [ ] 文档中仍散落大量旧版本号（465/dev-6xx/双引擎表述），新增功能时应同步更新索引与状态文档；
- [ ] Miuix/Flutter 页面内硬编码中文逐步迁资源文件；
- [ ] `lite` 仍未完全移除 Rhino language jar（`rhino-language` 静态依赖）;
- [ ] 历史报告保持时点快照，不回写新结论；需要当前结论时引用本节或 `PROJECT_STATUS.md`。

---

## 1. 历史结论（2026-09-08）

> 以下内容保留为历史审计记录，其中“Miuix 是主要显示层 / 双引擎”等表述已被 dev-905 起的 Flutter dev 通道与 Node24 第三引擎扩展。

项目当前已经可以继续日常开发和真机测试：Miuix 是主要显示层，Rhino/QuickJS 双引擎可用，MCP、搜索、示例、资源、任务、日志、编辑器和小米无障碍快速开启均已落地。

但项目还不能表述为“全部完成”或“可直接正式发布”。剩余工作主要集中在以下六类：

1. **MCP 写入安全模型已收口，仍需扩大兼容性回归**；
2. **正式发布配置、权限和新版 Android 兼容尚未收口**；
3. **Miuix 页面仍需统一弹窗、菜单、滚动和安全区域细节**；
4. **旧 ImGui 渲染栈已移除，未来悬浮 API 需按 QuickJS 模块重新设计**；
5. **QuickJS 不是 Rhino/Auto.js Pro API 的完整替代**；
6. **自动化 UI、性能和多设备回归覆盖不足**。

优先级定义：

- **P0**：正式发布或允许 AI 修改真实脚本前必须处理；
- **P1**：下一阶段应优先完成，直接影响体验、可靠性或后续删旧代码；
- **P2**：不阻塞当前开发，但应纳入持续整理。

## 2. P0：先解决的事项（历史清单）

### 2.1 统一 MCP“申请应用”与“直接应用”的行为

当前实现存在明确的语义冲突：

- `McpTools.kt` 实际公开 **26 个工具**，旧 `HANDOVER.md` 仍写 19 个；
- `workspace_request_apply` 当前会先调用 `requestApply()`，随后立即调用 `applyConfirmed()`，即 MCP 客户端可在手机开启写入授权后直接修改真实脚本；
- Miuix 设置页也写明“免审批，自动备份可回退”；
- `MCP_SCRIPT_SERVICE.md`、旧交接记录和 `McpWorkspaceStore.kt` 注释仍写“只能由手机历史页确认应用”。

最终采用低摩擦授权模型：用户在手机端开启一次“允许编辑并应用”，随后 MCP 可直接应用工作区；仍保留原文件冲突检查、自动备份、历史 Diff 和手机回退：

- [x] MCP 调用 `workspace_request_apply` 时检查手机端写入授权；
- [x] 授权后直接应用，不再要求每次回到手机确认；
- [x] 手机历史页展示 Diff、目标路径、文件变更数量并提供回退；
- [x] 应用前继续校验原文件哈希，并保留自动备份；
- [x] 应用后如果真实文件再次变化，回退必须拒绝覆盖并说明冲突文件；
- [x] 文档、工具说明、页面文案和服务返回值统一为直接应用语义；
- [x] 工具数统一更新为 22，并由测试直接断言工具名集合，避免以后再次写错。

`workspace_request_apply` 名称为兼容现有客户端保留，工具说明明确其实际为直接应用；开启授权时显示强警告，避免名称、注释和行为继续互相矛盾。

验收标准：未授权时不能写入或应用；已授权时可连续修改，且修改、冲突拒绝、应用、回退四条路径均有自动测试和 K40 实测记录。

### 2.2 建立正式发布配置

`apps/app/build.gradle` 当前没有正式签名配置，`release` 仍是：

- `minifyEnabled false`；
- `shrinkResources false`；
- `lint.abortOnError false`。

这适合开发验证，不适合作为正式发布基线。

- [x] 使用环境变量配置 release keystore，密钥文件不得提交；
- [x] 增加可复现的 `assembleMiuixRelease` 流程并记录 APK SHA-256；
- [x] release 暂不启用 R8 和资源压缩，以保护反射、JNI 和脚本桥接；原因及 Debug APK 体积基线已记录；
- [x] 发布构建让关键 lint 错误阻断，不再全局 `abortOnError false`；
- [x] 更新检查已切换到 `Jdkshen/ai.js-pro` 的 GitHub Releases API，支持从标签或 release notes 读取 `versionCode`，并按 compat/lite 与 ABI 选择 APK；首次正式发布仍需核对实际资产名与签名；
- [ ] 验证覆盖安装、全新安装、升级后数据保留及降级拒绝行为。

### 2.3 权限与目标 SDK 专项审计

当前 `compileSdk 35`，但 `targetSdk 28`。Manifest 同时声明了存储、悬浮窗、电池优化、开机启动、前台服务、使用情况、录音、定位、读取电话状态、查询全部应用及 `WRITE_SECURE_SETTINGS` 等高敏感权限。

- [x] 按功能列出每项权限的用途、申请时机、拒绝后的降级行为；
- [x] 明确非核心功能权限应在使用时申请，不在首次启动集中索取；
- [x] `WRITE_SECURE_SETTINGS` 文档明确说明它只能通过 Shizuku、ADB 或 Root 授予；
- [ ] 检查 `QUERY_ALL_PACKAGES`、电话状态、录音、定位是否仍为实际功能所需；
- [ ] 确定发布渠道后，再分阶段提升 target SDK；不要一次升级后仅凭能编译就认为兼容；
- [ ] 每次提升 target SDK 都回归存储、前台服务、通知、悬浮窗、包可见性、APK 安装和后台脚本。

验收标准：权限清单与真实功能一一对应，拒绝权限不会导致启动崩溃，发布包没有无法解释的敏感权限。

## 3. P1：界面与架构收尾

### 3.1 删除真正的 ImGui 渲染栈

旧工作台 Activity、Java/JNI 渲染桥、C++ 源码和三 ABI 预编译库已经移除。编辑器与终端已迁到独立包名。APK 原生库审计确认不再包含 `libautojs_imgui.so`。

- [x] 确认没有通知、快捷方式、外部 Intent、旧偏好或反射仍能打开工作台；
- [x] 删除 Manifest 注册、工作台 Activity、Surface/Bridge/AccessibilityProvider 和 C++ 实现；
- [x] 删除三 ABI 的 `libautojs_imgui.so`；APK 体积变化在本轮构建后记录；
- [x] 将 `ProCodeEditorActivity`、`EmbeddedTerminalActivity` 分别移入 `ui.editor` 和 `ui.terminal`，避免与未来 QuickJS ImGui 悬浮 API 混淆；
- [x] 构建变体：`:app:assembleMiuixCompatDebug` 与 `:app:assembleMiuixLiteDebug` 均已构建成功
      （6 个 ABI 包：compat 144.9/96.8/127.8 MB、lite 144.9/122.5/153.4 MB，arm64 两者同为 144.9 MB）；
      Miuix Debug 已在 K40 覆盖安装，设置稳定模式和独立终端已点击通过。
      **2026-09-12 更正**：原条目写的 `miuixDebug` / `commonDebug` 是已退役的变体名（`common` channel 已删除），
      本条已按现存的 `miuixCompat` / `miuixLite` 重写；**编辑器、示例和资源页仍未完整回归**。

注意：QuickJS 和 OpenCV 所需 `.so` 仍是正式运行时输入。后台 Shell 与独立终端已改用 `ProcessBuilder`，不再需要仅支持旧 ABI 的 Jackpal 终端 AAR/`.so`。

### 3.2 统一 Miuix 弹窗和菜单

Miuix 已有 `MiuixExplorerMenuHost`，但原生 `ExplorerView` 仍保留 3 处旧 `PopupMenu` 作为公共/回退路径；布局分析悬浮窗仍使用旧 `BubblePopupMenu`。需要确认 Miuix 流程不会意外回落到旧菜单。

**已完成（2026-09-17，悬浮球 + 环形菜单的外观层）**：

- 环形菜单五个球原来是写死的饱和色（`@color/colorPrimary` / `#ef5350` / `#42a5f5` / `#fdd835` / `#C0C0C0`，
  资源 `circle_{green,red,blue,yellow,gray}.xml` 已删），现在改成主题语义色：
  主操作 = `?attr/colorPrimary` + `colorOnPrimary` 图标；录制 = `?attr/colorError` + `colorOnError`；
  其余 = `?attr/colorSurfaceContainerHigh` + `colorOutlineVariant` 描边，图标按语义给 `colorOnSurfaceVariant` / `colorError`。
- 顺带修掉「悬浮窗跟的是**系统**主题而不是应用主题」：`FloatyService` 的 context 不归 AppCompat 管，
  直接 inflate 会拿到系统 uiMode（系统深色 + 应用浅色时，菜单渲染成深底 + 粉色 error 圆）。
  现在 `CircularMenu` 用 `createThemedContext()` 把应用自己的深浅规则（跟随系统 / 手动）写进子 context 再套 `AppTheme`，
  悬浮球与菜单都用它 inflate。
- 仍是 View 实现（沿用迁移方案的 B 类：窗口机制/弧形展开动画不动），**未**换成 Compose。

**已完成（2026-09-17，布局分析悬浮窗的气泡菜单）**：

- `BubblePopupMenu`（`ui/widget/`，只有两个 layout inspector 窗口在用）的**内容**换成 miuix 源集的
  `MiuixBubbleMenuHost`（Miuix `Card` + 文本行，反射取、取不到回落旧的深色气泡）。对外 API 一字未改
  （`showAsDropDownAtLocation` / `preMeasure` / `setOnItemClickListener`）。
- Miuix 路径去掉指向节点的小三角（卡片自带圆角/阴影），改成把 x 夹在屏幕内；旧的三角+`bubble_bg` 路径原样保留。
- ⚠️ **Compose 放进 PopupWindow / 悬浮窗的三个坑**（都在真机踩到，改这块前先读）：
  1. `ComposeView` 在**挂窗口之前被 measure** 会抛 `Cannot locate windowRecomposer` —— 而调用方要求先
     `preMeasure()` 拿尺寸再定位，所以 `MiuixBubbleMenuHost` 返回的是一层**固定尺寸包装**（自己回答尺寸、
     attach 之后才去量 Compose 子树）；
  2. attach 时还会要 `ViewTreeSavedStateRegistryOwner`，而 popup 的根是系统建的 `PopupDecorView`、
     从它往上找不到 owner → 必须**显式** `setParentCompositionContext(自带 Recomposer)`；
  3. 自己造的 owner 里 `SavedStateRegistryController` 必须 `performAttach()` → `performRestore(null)` →
     **最后**才把 lifecycle 置成 RESUMED（顺序错了会抛 `Restarter must be created only during owner's initialization stage`）。
- 真机验证（Mi8，dev-621）：菜单渲染为 Miuix 卡片（3 行、宽 132dp／行高 46dp，`miuixView=true size=363x380`），
  点第二行回调 `position=1` 并消失。
- ⚠️ **另有一个既有问题（不属于本次改动）**：布局分析要先用无障碍服务抓当前窗口节点，Mi8 上用
  `adb shell settings put secure enabled_accessibility_services …` 开启后，抓到的根节点没有子节点
  → 检查器浮层是空的、点哪儿都选不中节点（所以气泡菜单也不会弹）。要在真机上回归这个菜单，得先查这条抓取链路。

- [ ] 文件“更多”、排序、项目操作全部验证走 Miuix 菜单宿主；
- [ ] 弹层统一圆角、遮罩、标题字号、按钮顺序、底部安全区和返回键行为；
- [ ] 长文件名、超长菜单和大字体下不能被截断或挤出屏幕；
- [x] 布局分析悬浮窗的 `BubblePopupMenu` 单独改造，不与普通页面菜单混在一次提交；
- [ ] 深色模式下检查菜单背景、分割线、禁用态和危险操作颜色（悬浮球菜单的深色值走 M3 dark 调色板，尚未单独截图）；
- [ ] 悬浮球 + 环形菜单若要真 Miuix：用 Compose 重做壳与菜单项（`MiuixMainFabHost` 那套 `createView()` + 反射的路子）。

**已完成（2026-09-17，视觉第二版：底色 / 圆角 / 描边，dev-623）**：

真机反馈「优化的不是很好看 白的地方太白了 没有圆角」，三处弹层一起调：

- ⚠️ **先量了 Miuix 调色板的真实值**（`LaunchedEffect` 里打日志，别凭感觉猜）：
  `surface = #FFFFFF`（纯白）、`surfaceContainer = #F9F9F9`（几乎看不出灰）、
  `surfaceContainerHigh = surfaceContainerHighest = #E8E8E8`、`surfaceVariant = #FFFFFF`。
  所以「改用 `surfaceContainer`」其实**解决不了**「太白」，真正能拉出层次的是 `surfaceContainerHigh`。
- 气泡菜单（`MiuixBubbleMenuHost`）：底色 `surfaceContainer` → `surfaceContainerHigh`，圆角提到 20dp，
  补 1dp `onSurface` 12% 描边。**描边是必需的**：悬浮窗只有内容那么大，`shadow` 会被窗口裁掉，
  没描边就「糊」在背景上（实测气泡菜单窗口尺寸 = 内容尺寸）。
- 「布局分析」列表弹框（`MiuixActionMenuHost`，新增）：全屏 `PopupWindow` + 深色遮罩 + 居中卡片
  （26dp 圆角、`surfaceContainerHigh`、1dp 描边），标题走 `onSurfaceVariantSummary`、行图标 `onSurface`；
  卡片自身 `clickable(interactionSource, indication=null)` 吞事件，避免点空白处被当成「点遮罩」而关掉。
  `CircularMenu.inspectLayout()` / `settings()` 先试 Miuix（反射），失败 `Log.w` 并回落旧的
  `OperationDialogBuilder`（文案、id、图标两边必须一致）。
- 悬浮球弧菜单：去掉近白的圆形底色，改为主色实心（主操作）、错误色实心（录制）、错误色空心描边（停止全部）。
  悬浮球本身是深色圆角图标（`ai_js_pro_float_icon_background` + 深色 logo），本来就不白。
- 新增 `FloatyCompose`（miuix 源集）：把「Compose 放进悬浮窗/弹窗」的三个坑（离屏 measure、attach 要 owner、
  自造 owner 的初始化顺序）收成一个共用底座，`MiuixBubbleMenuHost` / `MiuixActionMenuHost` 都走它。
- 验证方式：临时在 `MainActivity` 加 `--ez action_menu_demo true` / `--ez bubble_menu_demo true` 两个反射入口
  单独截图（悬浮球菜单的坐标点不准，容易误触），**验证完已删除**。
  ⚠️ 顺带踩到：Mi8 截图是 1080×2160，按渲染图目测比例算坐标会点错（连点错两次，点进别的文件夹）。

**已完成（2026-09-17，第三轮：触摸 / 返回 / 跟主题，dev-624）**：

真机反馈「触摸、返回不是很好」「圆角界面和其它界面没统一」「颜色要跟主题同步」，逐条落实：

- 🔴 **最关键的一条：`PopupWindow` 在悬浮窗里弹不出来**。调用方是 `FloatyService` 的 context，
  它**没有窗口 token** → `showAtLocation` 抛 `WindowManager$BadTokenException: token null is not valid`
  → `MiuixActionMenuHost` 静默回落到旧的 Material 白框（上一版真机上看到的「小圆角白框」就是它，
  不是我们的 Miuix 卡片）。修法：`windowLayoutType = FloatyWindowManger.getWindowType()`
  （`BubblePopupMenu` 同样补上）。**从悬浮窗/服务弹任何 `PopupWindow` 都要设这个。**
- 底色定案：**用 `surface`**（= `MiuixDialogHost` / `MiuixUpdateDialog` 的容器色，深色主题自动变深），
  靠「阴影 + 极淡描边」交代边界。前两版在 `surfaceContainer`(#F9F9F9) 与 `surfaceContainerHigh`(#E8E8E8)
  之间来回试，结论是**都不对**：一个太白、一个发灰，而其它 Miuix 对话框本来就是 `surface`。
- 圆角统一 **26dp**（气泡菜单从 20dp 改过来），行按下高亮 16dp 圆角。
- 按下反馈：`Modifier.clickable(interactionSource, indication = null)` + 自己叠
  `onSurface.copy(alpha = 0.08f)` —— 覆盖层窗口里系统 ripple 不可靠，必须自己画。
- 返回键：除了 `PopupWindow.isFocusable = true`，再在 `showAtLocation` **之后**给根 view
  `isFocusableInTouchMode = true` + `requestFocus()` + `OnKeyListener` 兜一层（实测 dev-624 可关）。
- 遮罩跟主题：浅色 32% / 深色 50%（用 `isAijsDarkThemeResolved(context)` 判断，非 Compose 版）。
- 行首图标 tint 用 `primary`（跟主题设置里选的主色同步）。
- **菜单窗口收起时必须整窗 `FLAG_NOT_TOUCHABLE`**：它是 `672×668` 的大矩形，View 虽然 GONE，
  窗口矩形仍然吃触摸 —— 这就是真机上的「挡住下面应用点不到」和「菜单关掉后还吞一会儿触摸」。
  实测 `dumpsys` 里能看到 `fl=NOT_FOCUSABLE NOT_TOUCHABLE`，且点原菜单区域能穿透到下层的文件列表。
- 弧菜单项热区 42dp → **48dp**（视觉仍 42dp，靠 drawable 里的 `<inset>` 3dp 收进去），
  按下反馈从很弱的 `foreground="?selectableItemBackgroundBorderless"` 改成 background 自带 ripple。
- 悬浮球改成**正圆**（原来是 16dp 圆角方块，被 `RoundedImageView` 的 `riv_mutate_background` 把
  background 压成了圆角矩形），底盘改用主题容器色 `colorSurfaceContainerHigh`。
  ⚠️ `app:tint` 对 `RoundedImageView` **不生效**（它不是 AppCompatImageView），logo 保留原色也挺好；
  `values(-night)/colors.xml` 里的 `floaty_logo_tint` 目前是预留的。
- 未验证：布局分析的气泡菜单（要先由无障碍服务抓窗口节点，Mi8 上这条链路仍然抓不到子节点，
  点「布局范围分析」会跳到系统无障碍设置页），所以 `BubblePopupMenu` 的 `windowLayoutType`
  修复只是**同类问题的同样修法**，没做端到端复核。

- [ ] 冷启动「需要打开无障碍服务」的 `NotAskAgainDialog`（`ThemeColorMaterialDialogBuilder`，`md_*` 资源 id）
  仍是**纯白 + 直角**的 Material 弹框，且每次冷启动都弹，观感与上面几处不一致 → 建议用 Miuix 卡片重做；
- [ ] 同类 Material 弹框（进度、确认、权限引导）统一排查一遍底色与圆角。



### 3.3 搜索、定位与编辑器交互回归

此前要求的关键体验应形成固定验收用例，避免后续 UI 调整再次退化：

- [ ] 搜索弹层不替换主页面，关闭后仍回到原目录和原滚动位置；
- [ ] 点击文件结果：进入所在目录、滚动到目标项并高亮，不只打开编辑器；
- [ ] 点击文件夹结果：进入其父目录、定位并高亮该文件夹，不直接进入目标文件夹；
- [ ] 高亮有自动消退，不影响后续多选和点击；
- [ ] 返回键先关闭键盘/弹层，再回目录，不直接退出应用；
- [ ] 编辑器回归多标签、查找替换、撤销重做、断点、运行、日志、输入法和横屏；
- [ ] 测试空目录、隐藏文件、中文路径、超长路径、大文件和文件被外部删除的情况。

### 3.4 补齐页面适配矩阵

目前未形成完整证明的组合包括：

| 维度 | 至少覆盖 |
|---|---|
| 主题 | 浅色、深色、跟随系统动态切换 |
| 字体 | 100%、130%、150% |
| 屏幕 | K40 竖屏、横屏、分屏、全面屏手势 |
| 系统 | API 26 最低版本、API 28 附近、API 33 K40、较新 Android |
| 页面 | 首页、搜索、编辑器、示例、资源、插件、任务、MCP、设置、悬浮窗 |
| 状态 | 空数据、加载中、错误、长列表、离线、权限拒绝 |

Miuix 源集中目前仍有大量直接写在 Kotlin 中的中文文案，应逐步迁移到资源文件，以便统一修改、无障碍朗读和后续多语言支持。

### 3.5 用数据处理“滑动有点卡”

仓库当前没有 Macrobenchmark、JankStats 或 FrameMetrics 自动基准。仅凭肉眼比较 Auto.js Pro 容易受到录屏、悬浮 FPS、后台任务和列表数据量影响。

- [x] 已建立 K40 固定滑动脚本与首份 `gfxinfo` 基线；后续前后对比仍须保持相同页面和脚本数量；
- [ ] 分别测首页文件列表、搜索结果、示例、资源、插件和任务长列表；
- [ ] 记录慢帧比例、P50/P95 帧耗时、峰值内存和首次进入耗时；
- [ ] 检查列表 key 稳定性、图片解码、主线程文件 IO、重复排序和过度重组；
- [ ] 性能修改前后保留同条件数据，不用悬浮 FPS 单一数值下结论。

## 4. P1：引擎与 API 收尾

### 4.1 不宣称 QuickJS 与 Rhino/Auto.js Pro 100% 等价

现有实测文档记录 Rhino 全局 API 约 225 项、QuickJS 约 57 项。QuickJS 采用白名单桥接，目标是现代 JavaScript 和高性能场景，不是复制 Rhino 的 Java 反射环境。

> **2026-09-12 更正**：上面的「QuickJS 约 57 项」是 2026-09-08 的旧基线，已严重滞后。
> 按 `architecture/双引擎功能对比.md` 的 2026-09-11 实测，QuickJS 已注册全局 API **250 项**（Rhino 225 项），
> 差异表只剩 4 项。本文其余涉及「约 57 项」的表述同此更正。但**数量接近不等于行为等价**——
> 第 4.1 节的结论（不可宣称 100% 等价）依然成立。

已知仍不等价或暂不支持的能力包括：

- E4X/JSX、完整 UI DSL 和任意 Java 反射；
- CommonJS `require()` 的完整 Rhino 行为；
- `console.show()` 悬浮控制台；
- worker 间共享事件总线和自动捕获外层闭包；
- `$work_manager`、`$zip`、`$crypto`、`sqlite`、`RootAutomator2`、`$ocr`、特征匹配及新版 WebSocket 等 Auto.js Pro 专属能力。

Node.js 不纳入当前 APK，也不应为了对齐下载目录示例而引入。

`lite` 当前只移除了 Rhino **执行提供器**，尚未完全移除 Rhino language jar。编辑器语法高亮和共享 `UI -> ProxyObject` 仍有静态链接；实机证明直接删除该依赖会在第一次 QuickJS 运行时因 `ProxyObject/NativeObject` 缺失而失败。完成 flavor 适配层之前，不再把 lite 宣传为明显缩小体积的版本。

- [ ] 从真实脚本需求决定下一批 API，不按名称数量盲目补齐；
- [ ] 为每个新增 API 同时定义参数、返回值、异常、线程模型和资源释放；
- [x] 可通过 `tools/compare-engine-api.ps1` 从真实 MCP 服务自动生成 Rhino/QuickJS 大小写精确差异表；发布时仍需重新探测；
- [ ] 示例只展示当前引擎真实可用的能力，不复制无法运行的 Pro/Node 示例；
- [ ] 将编辑器高亮和共享 UI 的 Rhino 类型引用移入 compat 适配层，再从 lite 安全移除 `rhino-language`；
- [ ] 保持 Rhino 为旧脚本默认引擎，QuickJS 继续显式声明。

### 4.2 扩展引擎回归

当前 QuickJS 最近记录为 46/46，Rhino 有独立回归记录，但测试仍应覆盖失败和资源边界：

- [ ] 强制停止、超时、脚本异常和 Activity 退后台；
- [ ] 截图权限取消、无障碍断开、Root/Shizuku 拒绝；
- [ ] NativeFrame、OpenCV、YOLO 连续运行后的句柄和内存回收；
- [ ] worker 返回值、异常、join/interrupt 与引擎销毁竞态；
- [ ] APK 打包后的 Rhino/QuickJS 脚本运行，而不只测试主应用内运行。

## 5. P2：代码和文档整理

### 5.1 生命周期、弃用 API 与文案资源

- [x] `MiuixMarketFragment` 的 `GlobalScope` 改为 `viewLifecycleOwner.lifecycleScope`，页面销毁时自动取消网络更新；
- [ ] Miuix 页面直接设置 `statusBarColor/navigationBarColor` 的代码改为统一 Window Insets/System Bars 方案；
- [ ] `android.preference.PreferenceManager` 逐步迁移到 AndroidX；
- [ ] 修复 Kotlin/Java 可空性编译警告，避免隐藏空指针问题；
- [ ] 把 Miuix 页面的硬编码中文、尺寸和颜色集中到资源或主题 token；
- [ ] 升级依赖前先处理全局 `resolutionStrategy.force`，防止被强制版本掩盖兼容问题。

### 5.2 测试结构

当前 Miuix 测试已覆盖 MCP HTTP、26 工具清单、工作区应用/冲突/回退、文件搜索和示例目录。

**2026-09-12 进展（本次新增/清理）**：

- 新增 `ExplorerListRowsTest`（17 例）：文件列表的排序/分组/折叠纯状态层；
- 新增 `ExplorerSorterParityTest`（4 例）：**直接调用真实 `ExplorerSorter` 当 oracle** 比对排序方向。
  这条抓到了三处方向写反——`reversed()` 是交换参数而非取负，方向取决于原比较器把谁放在 `o1` 位；
  实测 `ascending=false`（默认）时 NAME 名称升序 / SIZE 大小升序 / DATE 时间降序 / TYPE 类型升序；
- 新增 `ExplorerViewHelperTest`（12 例）：图标与首字母选择规则。**注意 `getIconColor` 覆盖不到**——
  它内部调用 `android.graphics.Color.rgb(...)`，本地单测里是 not-mocked 桩，除非引入 Robolectric；
  那条路径目前只有真机截图作为证据（S3.5 节）；
- 清理 `apps/app` 的 `ExampleUnitTest`：删掉一个只 `println` 的方法和一个空方法体（空测试的"绿"是误导性的），
  保留 1 个确认源集被收集的用例；
- 合计 **139 例 0 失败**。

下一步至少补：

- [ ] MCP 空请求头兼容、USB Host 和鉴权；26 工具清单及 workspace 新建、应用、冲突、回退已有 JVM 测试；
- [ ] 搜索结果点击后的目录定位、高亮和滚动恢复；
- [ ] 编辑器打开/保存/外部修改冲突；
- [ ] 小米无障碍快速开启时保留其他无障碍服务；
- [ ] Miuix 页面最小截图或语义树回归；
- [ ] release APK 安装和升级烟雾测试；
- [ ] 把 `getIconColor` 的「后缀/类型 -> 色值」抽成不依赖 `android.graphics` 的纯函数，使颜色映射可单测。

### 5.3 文档和根目录清理

- [x] `HANDOVER.md` 作为历史流水保留，并纠正工具数量和旧 ImGui 主入口等明显过时结论；
- [x] `MCP_SCRIPT_SERVICE.md` 与最终直接应用模型同步；
- [x] `architecture/项目说明.md` 已改为“历史架构记录（已归档）”并在头部标明当前替代文档；旧 Gradle/JDK/ImGui 描述保留在正文作为历史快照，当前架构以 PROJECT_STATUS.md 为准；
- [x] 已作废的 ImGui 方案文档（`plans/侧边栏按钮与全分辨率UI适配方案.md`、`plans/IMGUI_左侧抽屉菜单改造任务.md`、`plans/MIMO_抽屉按钮无响应返修任务.md`）已加“已作废”批注，`plans/悬浮窗流畅度优化方案.md` 标注部分失效；`docs/README.md` 索引同步；
- [ ] 每份测试报告标记提交号、APK SHA-256、设备、系统和测试时间；
- [x] 根目录 `move-dotnet-to-d-DELETE.bat` 已确认完成使命（`dotnet` 已迁至 `D:\dotnet`，配套脚本与旧 junction 已清理），随 2026-09-10 清理提交移除；同时移除全仓库无引用的旧 logo `autojs.png`、`autojs_material.png` ×2；
- [x] 保持 `.gitignore` 的模块级 `**/build/` 规则及两个 Java `build` 源码包例外不变。

## 6. 推荐实施顺序

1. **发布基线**：版本/更新源、权限精简、release 签名、升级与降级测试；
2. **UI 验收**：搜索定位、弹层、菜单、编辑器多标签、深色、大字体、横屏和安全区；
3. **MCP 兼容回归**：USB Host、不同客户端版本、26 工具、直接应用、冲突与回退；
4. **性能基准**：先测量，再处理列表重组、主线程 IO 和动画；
5. **引擎差异自动化**：持续补真实需要的 QuickJS API，并保持 Rhino 回归；
6. **文档与根目录收尾**：历史报告明确归档，确认后单独删除无用脚本。

## 7. 下一版本最低验收门槛

准备下一个可交付 APK 前，至少满足：

- [x] `:app:assembleMiuixCompatDebug`、`:app:assembleMiuixLiteDebug` 已成功（2026-09-12 复验，见第 3.1 节）；目标 release 变体仍需正式签名环境；
- [x] 当前 Miuix JVM 测试全部通过，MCP 工具清单与工作区关键路径有真实断言；Windows 中文路径请使用 `tools/test-miuix.ps1`；
      **2026-09-12 复验：128 例 0 失败**（含新增的 `ExplorerListRowsTest` 17 例与
      `ExplorerSorterParityTest` 4 例——后者直接调用真实 `ExplorerSorter` 做 oracle 比对）；
- [ ] K40 全新安装和覆盖安装均通过；
- [ ] 首页、搜索定位、编辑器、运行、日志、示例、资源、插件、任务和 MCP 可走通；
- [ ] 小米无障碍快速开启不覆盖 RustDesk 等其他服务；
- [ ] MCP 的读取、运行、错误、日志、Diff、确认/应用、冲突和回退与文档一致；
- [ ] compat 的 Rhino 与 QuickJS 回归、lite 的 QuickJS 回归全部通过，原生句柄和进程内存无持续增长；
- [ ] 深色、150% 字体、横屏和底部手势安全区无明显遮挡；
- [x] 当前 Miuix ARM64 APK 的 `.so` 清单与源码决定一致，不含旧 ImGui 或本地验证产物；
- [ ] Git 工作树干净，本地分支与远程同步，发布 APK 有版本号和 SHA-256 记录。

> **2026-09-12 新增待办：Miuix 文件管理在「文件管理」与「设置」中仍以构建变体/开关暴露**
>
> 文件列表的 Compose 实现（`MiuixScriptListHost`）目前由实验开关
> `aijspro.experimental.miuix_file_list` 控制，默认关闭且**界面上没有入口**——只能改
> SharedPreferences 才能打开。发布前二选一：
> 1. 验收通过后把开关默认值改为 true 并删除旧 `ExplorerView` 族群（原计划 S4）；
> 2. 或在设置页补一个可见开关，避免"只能靠 adb 改 XML"的隐藏状态。
>
> 相关：`docs/plans/UI_统一到 Compose(Miuix) 迁移方案.md` 的 S3.5 节。

完成以上条目后，项目才适合从“持续改造版”转入“候选发布版”。
