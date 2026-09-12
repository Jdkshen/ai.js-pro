# AI.js Pro 剩余细节审计与收尾清单

> 审计日期：2026-09-08
>
> 基线分支：`chore/upgrade-gradle-8`
>
> 当前包名：`com.jdkshen.aijspro`
>
> 当前版本：`1.0.2 (465)`
>
> 主要验证设备：小米 K40，Android 13 / API 33

## 1. 结论

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

## 2. P0：先解决的事项

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

- [ ] 文件“更多”、排序、项目操作全部验证走 Miuix 菜单宿主；
- [ ] 弹层统一圆角、遮罩、标题字号、按钮顺序、底部安全区和返回键行为；
- [ ] 长文件名、超长菜单和大字体下不能被截断或挤出屏幕；
- [ ] 布局分析悬浮窗的 `BubblePopupMenu` 单独改造，不与普通页面菜单混在一次提交；
- [ ] 深色模式下检查菜单背景、分割线、禁用态和危险操作颜色。

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
