# 与 AutoJs Pro 的差距刷新（2026-11-10，task-118）

> **口径**：每条都带证据（`file:line` 或真机/实测）。**Pro 侧没有证据的一律写「待核」**，闭源细节不编。
> **不照抄旧结论**：`1 每一行都在 2026-11-10 复核过（下方注明「本轮复核」或引用发布记录）；`2 只保留**仍然成立**的缺口。
> 参考基线：`docs/reports/2026-11-09_Pro真缺口功能级复核终表.md`（124 条非 V8 候选：真缺 60 / 已等价 50 / 待定 16 / 忽略 2）、`docs/plans/2026-11-09_与Pro9.3.11对齐方案.md`、以及本轮新能力（瘦身开关 / 页面可见 / SJBC 三引擎 / 打包版 UI / 新鲜度门禁）。

## 0. 一句话

**自动化与无障碍主线已对齐（多处逐数相同），打包主线已反超；最大缺口仍是 Pro 的 V8 那条线**（Pro 树 `v8` 命中 **52** 个文件，其中 `v8` 目录 **40** 个 `.java`、`j2v8` 目录 11 个；脚本侧 `v8.*` API 我们**完全没有**）。其次是**可视化编辑器**（Pro 有 `org/autojs/autojs/ui/edit/visual` + `org/autojs/hrapps/visual`）与**按需 OEM 插件形态**（Pro 用 11.3 MB 插件 APK，我们内建 14.33 MiB 但可开关）。

## 1. 已对齐 / 已等价（每条给证据）

### 1.1 自动化运行时

| # | 项 | 证据 |
|---|---|---|
| 1 | **automator 三层包逐数相同**：`filter 16` / `search 3` / `simple_action 10` | **本轮复核**：`modules/automator/src/main/java/com/stardust/automator/{filter,search,simple_action}` = **16 / 3 / 10**（Kotlin），Pro 同路径 = **16 / 3 / 10**（`.java`）⇒ 数量与命名一致 |
| 2 | `UiObject`/`UiObjectCollection`/`UiGlobalSelector`/`ActionArgument` 同名同包 + `AccessibilityBridge` | 我方文件实列（`modules/automator/.../UiObject.kt` 等）；与 Pro `business/org/autojs/autojs/automator/**` 同名 |
| 3 | 无障碍**能力对象** API（Pro `AccessibilityServiceTool.checkAccessibilityService` 形态） | dev-1438 真机（`AccessibilityCheck` 返回对象 + 判据与 Pro 同源） |
| 4 | 无障碍**事件三通道** + 本地直订 | dev-1446 真机：`events.on('accessibility_event')` **12 秒 73 条** + `view_clicked` 1 次（修前 0）；`Events` 本地优先 `priority=9300` |
| 5 | `AccessibilityEventWrapper.toJson`（抽成纯类 + 单测） | dev-1445；`modules/automator/.../AccessibilityEventWrapper.kt` |
| 6 | `GestureOnMainThreadException` | dev-1445；我方 `GestureOnMainThreadException.kt`（**我们额外**：主线程只 `Log.w` 不误伤能跑的脚本） |
| 7 | `LinearLayoutAttributes`（脚本侧 `attr()` 注册 7 个名字） | dev-1445 真机：`orientation/showDividers/weightSum/measureWithLargestChild/baselineAligned/baselineAlignedChildIndex/divider` 全生效，`bogus` 静默 no-op |
| 8 | `ImagePool` + 未归还护栏 + `UnrecycledImageDetectedException` | dev-1447 真机：`findAllPointsForColor` **477,227 点**、`recycle` 正常、正常路径 logcat 静默；**差异**：Pro 默认抛，我们 **opt-in**（`ImagePools.setStrictMode`，默认 false，不破坏现有脚本）⇒ 记为**有意差异** |
| 9 | `Capture` / `WindowInfo` | 终表复核（task-65）：`LayoutInspector.CapturedWindow(id,type,active,focused,layer,root)` 是 Pro `WindowInfo` 的**超集**；脚本可见面 `auto.root`/`windowRoots()`/`currentPackage` |
| 10 | `LooperThread` | 终表复核（task-65）：Pro 只是**单方法内部接口、无脚本绑定** ⇒ 已等价（我们有 `Loopers.initServantThread/getServantLooper` + TimerThread） |
| 11 | **广播接收**（Pro `core/content/BroadcastReceiver` 系） | dev-1443 真机往返：注册 → `am broadcast` → **收到 1 次** → 注销 → 不再收到；三引擎 `typeof` 全 `function` |
| 12 | 定时任务（Pro `TimedTask`/`TaskScheduler`） | dev-1440/1444：`TimedTasks` + `taskChanged` 事件（含 MCP 路径） |
| 13 | 脚本反射/JS 语义（Pro 依赖 Rhino 同源） | dev-1440 真机 `Method.invoke(target,argArray)` varargs 展开；dev-1441 双机 `apply/call/bind` + `util.java.array`；`ScriptJavaSemanticsTest` 75 条 |

### 1.2 工程 / 打包

| # | 项 | 证据 |
|---|---|---|
| 14 | `project.json` 读 Pro 方言（`scripts` 字符串/`displaySplash`/`features.nodejs`…） | dev-1448 真机读**真 Pro 工程**：`parsed=true`、`warnings=4`、**写路径零污染**（`toJson` 逐字不变，单测钉住） |
| 15 | 工程打包不套娃 + 策略裁剪 | device4 实测 **−59.15 MB**（`build/` 旧产物排除）；`assets/autojs.keystore` 不进产物；`.so` 白名单（`libpikafish` 判 leak）；插件覆盖回显 `coveredBy=builtin-ocr` |
| 16 | **瘦身开关**（Pro 无同类按工程开关的证据，**待核**） | dev-1465 真机 A/B：默认 **39,039,966 B / 2,256 条** vs `removeOcr=true` **30,905,651 B / 2,211 条**，**Δ −8,134,315 B**，差集**正好 45 条**；`features.nodejs=disabled` ⇒ node24 相关 **27 → 0** |
| 17 | **打包页可见性**（这是我们补的体验缺口，不是 Pro 对齐项） | dev-1463：`大小构成（未压缩）：引擎与模板 ~45.9 MB · OpenCV ~31.4 MB · 内建 OCR ~16.7 MB · 你的工程 ~0 MB`（截图 `ui24.png`）；dev-1469：`打包提示（1）` 展开可见（`ui45.png`） |
| 18 | 构件新鲜度门禁（Pro 无此机制的证据，**待核**） | `tools/check-artifact-freshness.ps1` + 接进 `build-release-candidate.ps1:170-177`（gradle 前 `-Deep`、STALE throw）；dev-1462 真机**拦下过一次**真阳性 |
| 19 | 跨进程偏好（Pro `TraySharedPreference`/`PrefContentProvider` 形态） | **自研** `MultiProcessPrefs`（白名单 key + 锁内读改写 + 重播种）；dev-1447 真机 A/B/D 三态实测 |

## 2. 仍缺（按影响排序）

### 2.1 运行时（影响最大）

| # | 缺口 | Pro 行为（证据） | 我们现状（证据） | 成本估计 |
|---|---|---|---|---|
| **R1** | **V8 / `v8.*` 脚本 API 整条线** | Pro 树 `v8` 命中 **52** 个文件：`v8` 目录 **40** `.java`（**本轮实测**）、`j2v8` 目录 11；即 Pro 暴露 `v8` 全局（j2v8 系） | **没有**：脚本侧 `v8` 全局 **0 命中**（`modules/engine/src/main/assets/modules` 无 `v8**` 文件/注册）；engine 里 7 处「V8」全是 **Node24 自带 V8** 的守卫与注释（`Node24Libraries.java:20-21` 挡 `/libv8.so`、`Node24HostBridge.java:79` 注释），**非 j2v8** | **shim（只覆盖常用 `v8.api.*`）1–2 周**；**真 V8 6–12 周** + 原生库来源/ABI/16KB 页 |
| **R2** | Pro 的 **可视化编辑器**（`VisualEditor*`） | Pro 树：`VisualEditor` 命中 3、`visual` 命中 7；目录 `org/autojs/autojs/ui/edit/visual`、`org/autojs/hrapps/visual` | 我们只有**代码编辑器**（`ui/edit/editor` 对应物）；无可视化编辑器（终表归入「待定/高成本」） | 高（UI 框架级）；**建议单独立项评估** |
| R3 | mlkit 分支未加 `OcrAvailability` 同等早退 | —（Pro 侧无对应物） | dev-1465 边界：Rhino/QuickJS/Node24 三处同句早退已做，**mlkit 分支未做**（需宿主加 `Ocr.isAvailable()` 类判据） | 小（一处判据 + 单测） |
| R4 | **v7a（armeabi-v7a）运行时支持** | **待核**：我没有 Pro 的 ABI 集合证据 | 产物**只有 arm64-v8a**；v7a 缺 `libppocr.so`（唯一硬缺口）+ Flutter payload 只有 arm64 | L1 **半天–1 天**（v7a AOT 重建 + 走内置模板 + OCR 走 mlkit）；L2 vendor ncnn **≈半天** |
| R5 | Node24 侧 `sjbc`（**我们独有能力的内部缺口**，非 Pro 差距） | — | 三引擎口径：Rhino ✅ / QuickJS ✅（dev-1460 真机）/ **Node24 ✗**（无 `sjbc`/`require('sjbc')`）；建议入口 `require('sjbc').run(path)` | 中（host op + 绑定） |

### 2.2 打包

| # | 缺口 | Pro 行为（证据） | 我们现状 | 成本估计 |
|---|---|---|---|---|
| **P1** | **OCR 交付形态**：Pro 用 **按需 OEM 插件 APK（11.3 MB）** | 引用 dev-1446 的 device4 对照（`plugins=[…mlkit.ocr] coveredBy={…=builtin-ocr}` 那条链）；**Pro 插件下载机制的细节待核** | **内建**：`libmlkit*` 11.06 MiB + `libppocr` 3.96 MiB + 模型 2.37 MiB（≈14.33 MiB）⇒ 安装包**默认就带**；可用 `optimization.removeOcr` **关掉（省 ≈7.76 MiB 实测）**，但**没有「按需下载」体验** | 中（插件分发 + 下载器 + 版本协商）；**产品决策** |
| P2 | OpenCV 形态（Pro 单库 `libopencv_java4.so` ≈17 MB arm64 vs 我们拆库 8 个 32.94 MiB） | 引用 dev-1446 对照（Pro 28.5 MB vs 我们 40.4 MB 的差因之一） | **功能等价、形态不同**；DT_NEEDED 10/10 必需 ⇒ **单库化收益 = 0**（已决策**不做**，重开条件：需要更小包体且愿意重建 AAR） | 已关闭 |
| P3 | v7a 产物（同 R4） | 待核 | 只有 arm64；`AbiPolicy` 每次警告 `missing=[armeabi-v7a]`（dev-1469 双 ABI 工程实测该警告现在**能上页面**） | 同 R4 |
| P4 | `META-INF` v1 签名 0.54 MiB | 待核（Pro 是否 v2-only） | 是**我们自己写的** v1 签名（`ApkSignerV1.java:13-17`）⇒ 默认必须留；改 v2/v3-only 省 ≈0.5 MiB，但改兼容性 | 小（需 minSdk/策略决策） |

### 2.3 UI

| # | 缺口 | 证据 | 成本 |
|---|---|---|---|
| U1 | 可视化编辑器（同 R2） | Pro 目录实列 | 高 |
| U2 | Flutter 运行方式/布局与 Pro 原生 UI 不同 | **用户已决策保留**（`docs/plans/2026-11-09_Flutter桥API与功能对齐方案.md`：只做 API/功能对齐，不改 UI 布局）⇒ **不算缺口** | — |
| U3 | 打包页「插件按需下载」入口 | 同 P1；**Pro 侧是否有该入口待核** | 中 |

## 3. 我们更强 / Pro 没有（只在有证据时写）

| # | 项 | 证据 |
|---|---|---|
| 1 | **MCP 工具面**（224 个工具 + **16 个族别名**；Pro 完全没有） | **本轮实测**：`McpToolCatalog.kt` 的 `familyAliases` **16** 对（`dex_*→apk_dex_*` 等）；**Pro 树 `Mcp` 命中 0** |
| 2 | **SJBC 自研 L3 VM + v5 容器**（Pro 无此能力） | 真机：`r.ok=true steps=1990 codeBytes=476`、`prints` 5 条可 `forEach`、错证书 `SjbcException(AEAD 拒绝)`；三引擎口径表 |
| 3 | **打包瘦身开关 + 大小构成可视化 + 打包提示上页面** | dev-1465 A/B 实测；dev-1463/1469 真机截图 |
| 4 | **构件新鲜度门禁**（出包前 STALE 直接拦） | `build-release-candidate.ps1` + dev-1462 真机拦下一次真阳性 |
| 5 | **MCP 侧逆向工具链族**（`apk_dex_*`/`crypto_*`/`entry_*`/`chain_resume`/`dev_screen_diff`/`vm_survey`…） | 族别名 16 对实测 + AGENTS dev-1424~1429 各工具真机交付记录 |
| 6 | **三引擎并存**（Rhino + QuickJS + Node24；Pro 是 Rhino + V8） | 三引擎口径表（`docs/reports/2026-11-10_SJBC入口三引擎口径（task-73_83_87）.md`）；**结构不同，各有胜场**（V8 API 面我们缺，见 R1） |
| 7 | **多进程隔离**（`:script` / `:node24` / `:tools` 等） | dev-1446/1464 真机日志见 `am_proc_start`（主进程 + `:script`）；**Pro 的进程结构待核** |

## 4. 本次未核清单（**没核过的不许当已核**）

1. **Pro 的 ABI 集合**：我只有「我们只产 arm64」的证据，**Pro 是否支持 armeabi-v7a 完全没核**（R4/P3 的「Pro 行为」因此留空）。
2. **Pro 的 APK 体积构成逐项**：只有「Pro 28.5 MB」这个引用值，**没有 Pro 产物的分桶实测**（`res/`/`assets/` 逐项未核）。
3. **Pro 的可视化编辑器行为面**：只知道类与目录存在（`VisualEditor` 3 / `visual` 7 / 两个目录），**功能面/数据格式未核**。
4. **Pro 的按需插件下载机制**：只从 device4 对照知道「用 11.3 MB 插件 APK」，**下载/版本协商/权限细节未核**。
5. **Pro 的 v8 线脚本 API 面**：只知文件数（52 命中 / 40 目录），**具体暴露哪些 `v8.*` 未逐条核**。
6. **Pro 的 `imagePool` 语义**：Pro 侧命中 3，**是否默认抛异常未核**（我们侧取 opt-in）。
7. **Pro 是否也有「按工程裁 OCR/Node」开关**：**待核**（`removeOcr` 是我们自研的 `optimization.*` 扩展）。
8. **task-45 的 124 条终表未逐条重跑**：本轮是「**新增能力 + 抽样复核（automator 三层、Pro 树关键词普查、SJBC 三引擎）**」，其余行仍以终表为基线；要「逐条当前态」需另开一轮。
9. **Pro 侧对应的「打包页」能力面**：我们只核到自己的打包页（大小构成/打包提示），**Pro 是否有等价 UI 未核**。

## 5. 复跑命令（本文用到的只读核对）

~~~powershell
# 1) automator 三层与我们/Pro 的逐数对比（本轮实测 16/3/10）
Get-ChildItem modules\automator\src\main\java\com\stardust\automator -Directory | ForEach-Object { $_.Name + ' ' + (Get-ChildItem $_.FullName -File).Count }
# 2) Pro 树关键词普查（v8 / VisualEditor / Mcp）
$pro='.artifacts\AutoJsPro源码树-2026-11-09\源码树\business'
Get-ChildItem $pro -Recurse -File -Include *.java,*.kt | Where-Object { (Get-Content $_.FullName -Raw) -match 'Mcp' } | Measure-Object   # 期望 0
# 3) 我方 MCP 族别名条数（期望 16）
Select-String -Path apps\app\src\main\java\com\jdkshen\aijspro\mcp\McpToolCatalog.kt -Pattern ' to "' | Measure-Object
~~~
