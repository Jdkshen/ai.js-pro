# AI.js Pro 文档索引（docs/INDEX.md）

> 本页是 `docs/` 的统一入口：**统计快照 + 分类索引 + 最近 20 篇摘要 + 重复/取代清单 + 读文档顺序**。
> 生成时间：2026/11/6 16:12:35（快照口径：docs/ 磁盘文件；统计不含本页 INDEX.md。并发整理任务新产出的 `ARCHITECTURE.md` 与 `2026-11-06_死代码与冗余资产盘点.md` 已补入本页，数字会随其它整理任务小幅漂移）。
> 维护约定见 §7；新增报告/指南后请在同一提交里补一行。

**一句话现状**：当前发布版本与测试机以 [`AGENTS.md`](../AGENTS.md) 顶部为准（本索引快照时为 **dev-1362 LAN，默认测试机 K40 cccc62c7**；`docs/HANDOFF_AI.md` 顶部到 dev-1352，`docs/PROJECT_STATUS.md`/`docs/README.md` 更旧）。AGENTS.md 顶部还包含 2026-11-06 源码整理/框架梳理记录（commit a8786cd7/822a072a/d491154e，未 push）。历史报告里的数字只代表当时环境，不代表当前工作树。

## 0. 30 秒上手：先读哪 3 篇

1. [`docs/guides/ARCHITECTURE.md`](guides/ARCHITECTURE.md) —— **架构总览（10 分钟版，2026-11-06 基线 dev-1362）**：模块图/进程模型/三引擎/MCP/UI/打包，带 `文件:行号` 证据；先建立全局图再读细节。
2. [`docs/guides/REPO_CONVENTIONS.md`](guides/REPO_CONVENTIONS.md) —— **仓库规范**：备份、目录职责、跟踪策略、报告六段、只本地提交/只发 LAN、测试机 K40。
3. [`docs/INDEX.md`](INDEX.md)（本页） —— 文档地图、457 篇 reports 的分类索引、重复/取代清单与读法。

> 只关心「现在是什么状态/怎么发版」而不读架构时，直接看 [`AGENTS.md`](../AGENTS.md) 顶部（最新发布/测试机/雷区）；[HANDOFF_AI.md](HANDOFF_AI.md) 是更完整但部分旧（§4 状态）的总纲。

## 1. 统计数字（真实快照）

> 统计不含本页 `docs/INDEX.md` 自身；当前磁盘上含本页共 588 个文件。整理任务在 2026-11-06 16:00 后仍在并发修改根目录状态页/新增文档，本页在最终生成时已按最新磁盘重算。

### 1.1 顶层目录

| 目录 | 全部文件 | Markdown | Markdown 体积 | 说明 |
| --- | ---: | ---: | ---: | --- |
| `(root)` | 11 | 11 | 249.8 KiB | 入口/规范/状态（HANDOFF、README、REMAINING_WORK 等） |
| `architecture` | 12 | 10 | 199.6 KiB | 架构、API 对照、契约 JSON/CSV |
| `archive` | 26 | 3 | 32.1 KiB | 已归档的一次性脚本与说明 |
| `guides` | 43 | 24 | 357.3 KiB | 长期有效的手册/指南（含截图资产） |
| `node24-xiangqi` | 16 | 8 | 47.2 KiB | Node24 象棋识别移植专项记录 |
| `plans` | 22 | 22 | 307.6 KiB | 方案/任务书 |
| `reports` | 457 | 380 | 3.02 MiB | 按日期的一次性报告（含评测夹具资产） |
| **合计** | **587** | **458** | **4.18 MiB** | docs/ 全部文件约 34.21 MiB |

> 结论：`reports/` 占文件数的绝大头（457/587 ≈ 78%），但其中只有 380 个是 Markdown，其余 77 个是 PNG/CSV/JSON/PY 等评测资产；真正的可读文档主体是 **458 篇 Markdown / 4.18 MiB**。

### 1.2 reports 时间分布（按文件名日期，2026 年）

| 月份 | reports 篇数 | 说明 |
| --- | ---: | --- |
| 2026-09 | 87 |  |
| 2026-10 | 243 |  |
| 2026-11 | 45 | 本次「最近 20 篇」即取 11-04～11-06 的最新 20 篇 |
| 无日期 | 5 | 3 篇早期完成报告 + 2 个 chess-skin-eval README |
| **合计** | **375（有日期）** | 另有 5 篇无日期；全部 reports Markdown 380 篇 |

- 日期范围：最早 `2026-09-12`，最新 `2026-11-06`；**不存在 2024/2025 年的 docs 文件**。
- 全部 docs Markdown 的月份口径：无日期 76、2026-09 90、2026-10 243、2026-11 49（合计 458）。
- reports/ 只有 1 个嵌套子目录 `reports/chess-skin-eval/`（评测脚本+模板图，2 篇 README）；其余 assets 与 md 平铺在 reports/ 下。

### 1.3 文件类型与资产

| 类型 | 数量 | 体积 | 说明 |
| --- | ---: | ---: | --- |
| `.md` | 458 | 4.18 MiB | 文档正文 |
| `.png` | 63 | 24.57 MiB | 截图/评测模板图 |
| `.js` | 28 | 162.0 KiB | 评测/探针脚本（多在 reports、node24-xiangqi、archive） |
| `.py` | 14 | 98.0 KiB | 评测脚本（chess-skin-eval 等） |
| `.json` | 13 | 4.19 MiB | 契约/评测结果/清单 |
| `.csv` | 3 | 276.1 KiB | API 对照/评测结果 |
| `.jpg` | 3 | 717.2 KiB | 截图 |
| `.txt` | 2 | 1.0 KiB | 探针输出 |
| `.pyc` | 2 | 31.3 KiB | ⚠️ Python 缓存（建议清理，见 §5.4） |
| `.ps1` | 1 | 6.9 KiB | 脚本 |

- 非 Markdown 资产共 **129** 个，主要是报告/指南的截图、评测夹具和归档探针，**§4 不逐条索引**，只在对应报告/指南行内说明；需要时按报告目录名找。
- 现有 `docs/archive/` 共 26 个文件：23 个象棋 P4 一次性探针 js + 3 个 README；**还没有 reports 归档目录**（建议见 §5）。

### 1.4 最大的文档（避免误以为都是短页）

| 文件 | 体积 |
| --- | ---: |
| [`reports/2026-09-20_差分视觉与卡点四修.md`](reports/2026-09-20_差分视觉与卡点四修.md) | 107.0 KiB |
| [`reports/2026-10-18_反编译Pro_打包实现.md`](reports/2026-10-18_反编译Pro_打包实现.md) | 60.7 KiB |
| [`architecture/第一代API逐函数对照.md`](architecture/第一代API逐函数对照.md) | 59.9 KiB |
| [`REMAINING_WORK.md`](REMAINING_WORK.md) | 56.8 KiB |
| [`reports/2026-10-18_Phase1_Pro脚本进程与AIDL架构spec.md`](reports/2026-10-18_Phase1_Pro脚本进程与AIDL架构spec.md) | 56.6 KiB |
| [`reports/2026-09-14_象棋自动走棋_真机定位与手势结论.md`](reports/2026-09-14_象棋自动走棋_真机定位与手势结论.md) | 49.6 KiB |
| [`HANDOFF_AI.md`](HANDOFF_AI.md) | 49.1 KiB |
| [`reports/2026-10-17_Flutter玻璃界面POC验收.md`](reports/2026-10-17_Flutter玻璃界面POC验收.md) | 49.0 KiB |

### 1.5 自动扫描结果

- 含「历史/已被取代/复核更正/保留历史」类表述的文档：**25 篇**（人工复核后的高置信项见 §5.1；其余多为「以…为准」的谨慎措辞，不等于整篇作废）。
- 文件名带 dev 版本号的报告：约 **79 篇**（发布记录/按版本修复），属于同一版本链而非重复；见 §5.2 的系列说明。
## 2. 分类规则（本索引怎么归档）

1. **入口文档**（repo 根 / docs 根）：HANDOFF、README、REMAINING_WORK、HANDOVER、PERMISSIONS、MCP 调用说明等；带「历史/已归档」标记的逐条标出。
2. **guides/**：长期有效的手册；按「规范与构建 / API 与脚本能力 / MCP 与工具 / 专项」分组，给出摘要与链接（指南一般无日期）。
3. **architecture/**：架构与 API 对照；全部带 2026-10-19 时效说明且多在 Node24 加入前写成，阅读时先看文首时效说明。
4. **plans/**：方案/任务书；summary 保留「已作废/部分失效/时效说明」等状态，建议归档的见 §5。
5. **reports/**：按**主题 + 日期倒序**，主题按标题/文件名关键词优先级判定（MCP → 逆向/APK → 发布 → 业务专项 → Node/引擎/UI → Flutter/界面 → 自动化/图像 → 性能 → 构建/16KB → 文档），同日按标题排序；无日期报告单列。每个主题的条数标在标题里，方便判断该领域历史厚度。
6. **node24-xiangqi/**：专项移植记录，保留原目录顺序；**archive/**：已归档（只读历史）。
7. 每条一行格式固定为：`日期 | 主题（标题） | 一句话 | 链接`；`⚠️历史/被取代` 是自动提示，详细依据见 §5。

## 3. 最近 20 篇（2026-11，详细摘要）

> 选取口径：reports/ 中日期最新的 20 篇（2026-11-04～11-06，按日期倒序；同日按标题）。每条 2~3 行，只摘「做了什么 / 证据与边界」；完整内容点链接。

**2026-11-06 · 按交付包《MCP-改造-checklist.md》落实（dev-1360）** · [原文](<reports/2026-11-06_按交付包落实MCP改造清单（dev-1360）.md>)
- 逐条对账交付包《MCP-改造-checklist.md》（基于反汇编 dev-1359）：工程复制上限 30000→200000、展开字节 512MiB→2GiB；apktool/jadx 子进程 `-Xmx2048m` 放到 `/system/bin` 前；超时 300→1800 并同步四处下游旧上限。
- K40/MI8 真机验证 31,001 文件工程 147s 的失败文案、`get_status.limits` 可诊断、apksig keep 后可用；内置 apksigner/zipalign、分片/流式构建、工作区自动回收、真实 5.7 万文件样本未做。

**2026-11-06 · 按 `.4` 项目反馈修的第二批（dev-1359）** · [原文](<reports/2026-11-06_按poker-recog反馈修MCP第二批（dev-1359）.md>)
- 做反馈剩余三条：`list_engine_api` 每项 `documented` 并附 `documentedItems/documentedCount/build`；新增 `coordinate_convert` 三坐标系（state/calibrate/convert/clear）；图片句柄超限错误带 live handles 诊断。
- 真机实测两点标定复现 `(1,1.0009,152,0)`、`rect[100,200,300,400]→[252,200,452,400]`、27 帧未回收显式报 62,985,600/64,000,000；生成器写 `generatedAt` 仍列下一步。

**2026-11-06 · 按 `.4` 项目反馈修的第一批（dev-1356）** · [原文](<reports/2026-11-06_按poker-recog反馈修MCP第一批（dev-1356）.md>)
- 按 `.4` 反馈落地 7 条：`capture_screen` 增 `savePath/saveFormat`（raw=RGBA8888 无头，整屏 401ms vs PNG 625ms）；`run_script` 接受 `code` 别名；`app_list` 增 `rawCounts/coverageSuspect`；`get_status` 增 app/build/toolCount；工作区清理提示；`search_scripts` 增 `stopReason`；`logsText`。
- 文档对账与图片句柄两项留下一批；报告保留设备侧反例，声明「未扫完≠不存在」「没有对照就不声称看全」。

**2026-11-06 · 多任务收口：第 4 项（apksig V1+V2+V3）+ 对齐端到端 + 工作区回收（dev-1365）** · [原文](<reports/2026-11-06_多任务收口_apksigV3与对齐与工作区回收（dev-1365）.md>)
- 三件收口：`apk_build(sign=true)` 产出 V1+V2+V3（`schemesApplied` 与 `schemesVerified` 分开、失败回退 keystore-legacy 并报原因）；alignment 端到端（未签名/重签/反编译回编）；工作区回收补齐返回体与提示。
- K40 真 APK 138MB 实测；修了两个坑：R8 裁掉 apksig 签名 API（补 keep 后 available false→true）、预检把 apksig 不单验 V1 当成失败。真实 5.7 万文件工程回编仍未做（无样本）。

**2026-11-06 · 内置「谷歌 OCR」示例报错修复 + 测试机切 K40（dev-1360 → dev-1362）** · [原文](<reports/2026-11-06_谷歌OCR示例报错修复与K40测试机（dev-1362）.md>)
- 两个真因：① Rhino 的 `:script` 进程从未初始化 MlKitContext（Node 侧早就有），`$ocr.create` 成功但首次 `detect` 才抛错，故既有 ppocr 兜底从未触发；② `console.log(result)` 直接序列化 Java `OCRResult`（带 Rect/ML Kit 原始对象）导致 `RecyclerView declares multiple JSON fields...`。
- 修：Ocr 幂等 `ensureMlKitInitialized()`、三引擎降级链暴露 requested/backend/degraded；detect 结果转纯 JS 对象（raw() 保留原始）。K40 三个真实示例 SUCCEEDED（Rhino MLKit 418ms/18 块、ppocr 18 块、Node frames=5）；apksig keep 与对齐核查也有进展。

**2026-11-06 · MCP 截图：质量模式与快速模式（dev-1353/1354）** · [原文](<reports/2026-11-06_MCP截图质量与速度双模式.md>)
- `capture_screen` 新增 `mode=fast|balanced|quality`（后续追加 `fastest` 与 `region`，覆盖 dev-1353/1354/1355）：预设只管未传参数，显式 format/quality/maxWidth 始终覆盖，未知 mode 报错。
- 实测 PNG 编码约 540ms 是主因：fast 540宽 JPEG60 约 222–271ms/36KB，fastest 360宽 JPEG40 188ms/18KB，quality 原图 599–633ms；`region` 先裁后缩 [0,1000,1080,1400] 175ms/50KB（约 4.5× 快且像素不缩）。

**2026-11-06 · 死代码与冗余资产盘点（只读）** · [原文](<reports/2026-11-06_死代码与冗余资产盘点.md>)
- 只读盘点 6601 个 tracked 文件（基线 a8786cd7）：真正可判定「无构建/脚本引用」的只有 `rhino-1.7.14.1.jar`（仅注释提到）与 `third-party/ApkBuilder/apkbuilder/out.txt`（测试调试输出）；另有 1 个 0 字节 `resources.arsc` 样本需拍板。
- 两份 `template.apk` 内容不同（宿主瘦身 20,418,813B/0 个 lib；插件全量 88,335,762B/16 个 lib），都必须保留；同名 asset 54 组（8 组同内容、45 组同名不同容 + 两份 template）多数是 sample/模板工程各自文件，按名去重会破坏示例；prelude 只有一份。
- 结论：本轮未删/未改代码；建议只清理已确认无引用的 jar/out.txt 与 0 字节样本，并单独修 `PERMISSIONS_AND_RELEASE`/`项目说明` 等 1.7.7.2 与 template 体积的文档漂移。

**2026-11-05 · 《华仔部落_佣兵战纪》脚本报错定位与修复（dev-1346/1347）** · [原文](<reports/2026-11-05_华仔部落佣兵战纪脚本报错定位与修复.md>)
- 复现用户 Auto.js Pro 工程：四个原因——缺 log4j 使 `console.setGlobalLogConfig` 致命、第三方 OCR 插件包缺失、`Ocr.detect` 不收 Bitmap、无障碍未开（设备状态）。
- 修前三个：console 探测降级不打断脚本；`__plugins__.js` 对 hraps/tomato 包回退内置 OCR（ppocr 优先、mlkit 兜底，带 `__aijsBuiltInShim`）；`Ocr/PPOcr.detect` 新增 Bitmap 重载。MI8 实测脚本 FAILED→RUNNING；K40 未动、未发布。

**2026-11-05 · 「悬浮窗关闭不了」定位与修复（dev-1352）** · [原文](<reports/2026-11-05_悬浮窗关闭问题定位与修复.md>)
- 先实测两类窗口：脚本 `floaty.window` 用真实点击 × / `window.close()` / MCP `close_overlays` / 停止脚本都能关；用户「关不掉」的是应用自身悬浮球（主进程常驻，之前没有可靠入口）。
- 根因是 `CircularMenuWindow.close()` 三步清理串在同一 try，菜单未 attach 时第一步抛异常被吞 → 后续 removeView/FloatyService.removeWindow 不执行；改为三步独立 + `removeViewSafely` + hide 先摘引用，并新增 MCP `floating_menu {state|show|close}`。实测 close 后 0 窗。

**2026-11-05 · MCP 改进第二批：自动证据、结构化路由、r2 会话持久化（dev-1338/1339）** · [原文](<reports/2026-11-05_MCP改进第二批（自动证据_路由字段_会话持久化）.md>)
- 三项改进：Frida 诊断自动解析 `/proc/net/tcp[6]`，错误带真实 `portHex`；`terminal_read/list` 返回 requested/effectiveBackend、routed、routingRequirement；r2 会话用 `sessions.index.json` 原子持久化，force-stop/升级后 restored=1。
- 用新工具复核真实 `libyj-vmp-lib.so`（分页读回 sha 一致、首分析 10.0s、后续查询 5.5s）；报告同时记录未做项：sameUid 自动判定、/proc 反推历史、路由行只取首行。

**2026-11-05 · MCP 改进第三批：常驻 r2 会话与大函数分段投影（dev-1340→1344）** · [原文](<reports/2026-11-05_MCP改进第三批（常驻r2会话）.md>)
- 新增 `native_resident_open/command/batch/close/list` 常驻 r2 进程：单条查询 45–72ms、5 条批量 149ms（旧的 native_session 每次启动约 9.9–15.5s）；framing 用 `<cmd>;?e <marker>` + r2 `-0` + open 预热，三轮踩坑后才对齐（只有 NUL 会错位、只有 marker 会卡死）。
- 补 `projectSegments`/`summarizeBigFunction` 分段投影 82KB 级函数；两个协作者中途失败，Lead 收口修编译错误并补测；常驻会话不跨进程持久化、`pdg` 需插件的边界保留。

**2026-11-05 · $plugins 缺失行为核查（Rhino / QuickJS / Node24）** · [原文](<reports/2026-11-05_plugins缺失行为核查.md>)
- 只读核查三引擎 `$plugins` 缺失行为：Rhino 对已知 OCR 包回退内置 shim（`__aijsBuiltInShim=true`，有 warning 无错误码）；QuickJS 抛 Java 异常（长类名，不清晰）；Node24 `$plugins` 直接 ReferenceError，`require('plugins')` 才有中文错误。
- 结论：不能笼统说「会/不会中断」，必须先看引擎；Rhino 的回退与另外两引擎不一致。本轮不改产品源码、不发布。

**2026-11-05 · 内置 Rhino 示例全量运行与引擎修复（dev-1348 → 1351）** · [原文](<reports/2026-11-05_Rhino内置示例全量运行与引擎修复.md>)
- 把 160 个内置 Rhino 示例整树（含图片/res，避免假失败）推到设备全量跑：57 成功 / 95 常驻（UI/循环）/ 8 失败，8 个全是环境或已清晰提示（root、通知监听、无障碍未连、headless、示例自停等）。
- 修 6 个引擎缺陷：R8 裁 OkHttp 反射成员、`__http__` 空 body 崩、`$zip` 相对路径、log4j 降级、`Events.observeKey` NPE、`ScriptCanvasView` 停止时 FATAL；dev-1348→1351 发布链。

**2026-11-04 · MCP 免 Root Gadget APK 集成** · [原文](<reports/2026-11-04_MCP免RootGadget集成.md>)
- 新增 `apk_inject_gadget`：不重打包，直接按二进制 Manifest 注入，独立 API21 DEX 用随机类名加载器在 attachBaseContext→onCreate 之间加载 Frida Gadget；四 ABI 组件按需下载，仅监听 127.0.0.1 + 48 位令牌，重签/验签/对齐/原子输出。
- 背景：主包不内嵌约 149MB 四架构库，主包只增约 18KiB；MI8 普通 UID 安装/附加/原生 42/重开/拒绝/取消通过；真实 16KiB 页、Android17 与其他 ABI 未验。

**2026-11-04 · MCP 逆向工具与自发现指南** · [原文](<reports/2026-11-04_MCP逆向工具与自发现指南.md>)
- 新增 18 个规范逆向工具（Apktool/AAPT2、r2 + r2ghidra、YARA、Unicorn 五架构、Frida 有界客户端、指纹/证据导出），公开 169 名（160+9）、toolkit 99；补 MCP 标准 resources/prompts、8 种机读工作流、内置分页指南。
- 设计边界：可选组件按需下载、独立 setsid 子进程隔离、取消/超时清理子孙、源 APK 不覆盖、失败给可执行 nextActions；MI8 伪代码/取消恢复/回编译验签通过，主包仅增约 91KiB；Frida 需 Server/Gadget。

**2026-11-04 · MCP 逆向能力优化第二批（Manifest 兜底 / 应用查询 / 任务保留）** · [原文](<reports/2026-11-04_MCP逆向能力优化第二批.md>)
- Manifest 独立 AXML 解析兜底（ARSCLib 失败仍取 package/minSdk/targetSdk 等，并自报 `parseSource=independent-axml`）；`app_list` 增 `visibilityLimited/scanScope/scanTotal/matchedBy` 且 NFKC 归一化；后台任务保留窗口/墓碑/`JOB_EVICTED`/按 owner 过滤。
- 真机用 AutoJs6 打包 APK 复现「Can not decode without package」并验证独立解析；MCP 364 单测全过，dev-1332 LAN 发布；未做 §6.3 Node 运行形态、artifact_open 回退与全部 P1/P2。

**2026-11-04 · MCP 逆向能力优化第三阶段（P1/P2 全部落地）** · [原文](<reports/2026-11-04_MCP逆向能力优化第三阶段（P1P2）.md>)
- 把 GPT 复盘 P1/P2 全部落地：有状态 native 分析会话 10 工具、精简反编译 + 交叉引用证据分级、app 启动/日志/退出原因采集 6 工具、Frida 会话 4 工具、`binary_transform` 断言驱动、`evidence_export` 脱敏/完整解压校验。
- 规模到公开入口 196、toolkit 120、逆向 33；MI8 全生命周期（open→define→overlay→save→close，真实释放 937B）验证；未验：Frida 持久 attach、r2 命令执行、其它应用完整 logcat、二进制内容脱敏。

**2026-11-04 · MCP 逆向能力优化第三批（Node 运行形态 / artifact_open 结构发现）** · [原文](<reports/2026-11-04_MCP逆向能力优化第三批.md>)
- `get_status` 增 `runtime.nodeRuntimeForm`：明确嵌入式 Node 形态（embedded-shared-library、hostExecutable、standaloneCli 三项条件、canSpawnStandaloneNode），并说明 `process.execPath` 不能当 CLI 依据。
- `artifact_open` 改结构判定：`.apk.1`/无扩展名按 ZIP 魔数+APK 结构打开，伪 `.apk` 报「不是 ZIP」，目录返回有界候选/置信度；dev-1333 发布，366 MCP 单测全过。

**2026-11-04 · MCP 逆向能力优化第一批（工作区 / 文件发现 / 验签分层）** · [原文](<reports/2026-11-04_MCP逆向能力优化第一批.md>)
- 工作区配额可解释化：`apk_workspace_inventory`/`apk_workspace_gc`（默认 dryRun、保护 saved/活动/未知 owner）；`file_discover_apk` ZIP 魔数+APK 结构；`file_access_diagnose` 显式 app|shizuku + 原始 errno；`apk_verify.signature` 改分方案状态模型 + ASN.1 backendDiagnosis。
- MI8 实测配额/`.apk.1` 发现/验签后端诊断；公开入口 174、toolkit 104、MCP 344 单测；未做 Manifest 兜底、应用查询、任务所有权等（后两批才做）。

**2026-11-04 · 用户实测三个 bug 的修复（dev-1337）** · [原文](<reports/2026-11-04_MCP实测三个bug修复（dev-1337）.md>)
- 按用户实测修 3 个：`node_script_command` args 支持字符串/数组/null 并给结构化 INVALID_ARGUMENT；工作区配额死锁解除（显式 includeUnknownOwner+Temporary+minAge=0 可回收，编辑会话可释放）并新增 `apk_session_release`、新工作区默认 owner=mcp-host；Frida listen 跨 UID SELinux 诊断 + Gadget script 模式。
- MCP 478 单测全过，公开入口 206；报告也写清 owner 不是安全边界、无定时 GC、r2 会话不持久等边界。

## 4. 分类索引

> 行格式：`日期 | 主题 | 一句话 | 链接`；同一主题内按日期倒序。点击「原文」打开报告。

### 4.1 入口文档（repo 根 / docs 根，11 篇）

- — | AI 接手总纲（AI_HANDOFF） | 发布/推送约定（2026-10-31）：默认只发 LAN，不逐版本建 GitHub Release/tag；代码提交默认只本地，不自动 push——只有用户明确说「推一次」才推。 | [原文](<HANDOFF_AI.md>) （§4 状态旧；已加去过期横幅；架构/流程权威）
- — | AI.js Pro 文档索引 | 本文档目录按用途分类。除文档间的显式相对链接外，文中代码路径均以仓库根目录为基准。 | [原文](<README.md>) （旧索引正文 + 2026-11-06 状态横幅；最新看 AGENTS/INDEX）
- — | AI.js Pro 剩余细节审计与收尾清单 ⚠️历史/被取代 | 当前高级版/三引擎对齐总账（2026-10-30，dev-1292）：见 16 个后续工作包与验收标准。8 个实现/行为收口、4 个兼容范围盘点、4 个实机专项；不是只差 16 个 API。声明账本尚未逐条回填， | [原文](<REMAINING_WORK.md>) 
- — | AI.js Pro 项目现状 ⚠️历史/被取代 | 当前发布 dev-1256（2026-10-26）：启动主题与文档复制优化。统一深浅启动底色与系统栏，文档页面更紧凑、代码可一键完整复制。399 JVM、MI8深浅启动和最终R8包19项文档检查通过； | [原文](<PROJECT_STATUS.md>) （状态到 dev-1259；2026-11-06 已加去过期横幅）
- — | AI.js Pro — 交接文档 (HANDOVER) ⚠️历史/被取代 | 当前 HEAD：45fae285（main，与 origin/main 同步）；dev 更新通道 dev-1112（AI-js-Pro-Flutter-dev-1112.apk，约 132.1 MB， | [原文](<HANDOVER.md>) （旧交接文档，dev-1112）
- — | AI.js Pro 脚本 MCP | 本文是早期实现记录，以下旧工具数量和引擎范围已过期。dev-1257 的当前说明请读 MCP 连接指南 与 apps/app/src/main/assets/mcp/AIjs-MCP-Guide.md： | [原文](<MCP_SCRIPT_SERVICE.md>) （旧 MCP 记录，工具数已过期）
- — | AI.js Pro MCP 逆向调用说明 | 完整教程与 App、MCP 共用同一份资源：内置逆向指南，机读工作流。本页用于把入口交给其他 AI，无需复制历史聊天。 | [原文](<MCP逆向调用说明.md>) 
- — | 脚本 APK 签名 | 本文描述脚本 APK 的签名（打包页）。AI.js Pro 自身的 dev 更新通道使用仓库发布脚本的签名产物（当前 dev-1112），与这里的“脚本产物签名”是两件事。 | [原文](<APK_SIGNING.md>) （脚本 APK 签名，非宿主发布）
- — | AI.js Pro 权限与发布基线 | 正式发布默认使用 Miuix compat 变体： | [原文](<PERMISSIONS_AND_RELEASE.md>) （正式发布基线，非 dev 通道）
- — | AI.js Pro — Miuix UI 试点说明与交接 | 本文写作时 miuix 只是试点轨道，与 common / coolapk 两个 channel flavor 并存， | [原文](<MIUIX_PILOT.md>) （试点历史）
- — | 教程与示例 API 对齐说明 | Node24（"nodejs";，真实 Node v24.22.0）已作为第三引擎接入，Node 示例按引擎重排为 Rhino / QuickJS / Node 三类； | [原文](<TUTORIAL_API_ALIGNMENT.md>) （已部分过期）

> 根文档状态提示：`README.md`（旧索引，最新只到 dev-1299）、`PROJECT_STATUS.md`（dev-1259）、`HANDOVER.md`（dev-1112）已旧；`HANDOFF_AI.md` 架构与流程仍权威但 §4 状态写 dev-1177；`MCP_SCRIPT_SERVICE.md` 文首自述工具数量已过期。详见 §5.3。

### 4.2 长期指南（guides/，24 篇 Markdown）

**核心架构与规范**

- — | AI.js Pro 架构总览（10 分钟版） | 2026-11-06 基线的模块/进程/三引擎/MCP/UI/打包总览，带 `文件:行号` 证据；§0 先记住 8 件事，§8 列不确定项。 | [原文](<guides/ARCHITECTURE.md>)

- — | 仓库规范（REPO_CONVENTIONS） | 新接手先读：ARCHITECTURE.md（框架）→ 本文（规范）→ ../INDEX.md（历史报告索引）。 | [原文](<guides/REPO_CONVENTIONS.md>)
- — | AI.js Pro 编译指南 | 路径/版本核对日期：2026-10-20（基线 main，Flutter-only UI；历史日期只作背景）。 本文路径均以仓库根目录为基准；发版/更新的雷区细节见 AGENTS.md， | [原文](<guides/编译指南.md>)
- — | CodeGraph 使用说明 | CodeGraph 是本仓库的代码知识图谱工具：它在本地为全部源码建立索引（符号定义、引用、调用边），让开发者与 AI 助手一次查询即可拿到源码 + 调用链，无需反复全文搜索。 | [原文](<guides/CODEGRAPH.md>)
- — | 安装包瘦身：插件化与子集 | 主 APK 从 253MB 降到 ~141MB 的做法汇总（2026-10-19 dev-1112 的 Flutter 构建约 132.1 MB）。四件事，各自独立、都能单独回退； 原则是「只有用到的人付体积」。 | [原文](<guides/安装包瘦身_插件与子集.md>)
- — | 瘦身打包模板生成（:inrt → 宿主内置 template.apk） | 脚本：tools/build-slim-template.ps1 产物：apps/app/src/main/assets/template.apk + apps/app/src/main/assets/exclude… | [原文](<guides/瘦身打包模板生成.md>)
- — | 自建更新源测试 SOP（改代码 → 手机收到更新） | 适用场景：改完一版代码，想通过 app 自己的「检查更新」链路把新版本推到手机上自测。 全程只用自建更新源（update.json + 本机 HTTP 服务），不碰 GitHub。 | [原文](<guides/自建更新源测试SOP.md>)

**API 与脚本能力**

- — | AI.js Pro API 参考（Rhino / QuickJS / Node24） | 面向在本项目里写脚本的开发者。版本号不写死，以仓库更新页和实际 APK 为准；文中用“仅 Node/仅 Rhino”等文字标注归属，未标注的功能不等于三引擎都支持。 “有导出”不等于“真机已验证”； | [原文](<guides/API_REFERENCE.md>)
- — | AI.js Pro API 细节：7 个高频入口 | 本页把 7 个高频入口按用途、参数、返回值、权限、示例和清理方式展开，供人阅读，也供 MCP 查阅后写脚本。内容依据源码核对；文档标注不代表所有手机和全部调用组合已通过验证。 | [原文](<guides/API_DETAILS.md>)
- — | Node.js UI 与 Java 资源接口 | 本文对应 AI.js Pro 的 require('ui') 和 $autojs.androidContext。这是 Node 引擎专用接口；Rhino/QuickJS 的传统 ui 用法请看各自章节。 | [原文](<guides/NODE_UI_API.md>)
- — | Node24 基础自动化 API（accessibility / image / color / media_projection / app.viewFile） | 当前验证基线：dev-1112（Node 21/21、颜色 6/6、Node UI basic 8 steps，dev-1103； | [原文](<guides/Node24基础自动化API.md>)
- — | Node24 控件查询、点击与文字输入 | 当前实现：dev-1294（R01–R08 节点对齐集成）。主进程与 :script 分开验收； | [原文](<guides/Node24控件选择器API.md>)
- — | 共享截图与连续触控 | 主引擎先申请授权，再发布共享凭据： | [原文](<guides/共享截图与连续触控.md>)
- — | SIFT 特征匹配 API 与示例 | QuickJS、Rhino 共用内置真实 SIFT + FLANN + RANSAC API，不需要辅助模块。 | [原文](<guides/SIFT特征匹配示例.md>)
- — | 无障碍指纹手势 | Rhino 和 QuickJS 使用相同接口，监听指纹传感器上的四向滑动。需要 Android 8（API 26）以上、已启用 AIjs Pro 无障碍服务，以及支持手势的指纹硬件和 ROM。 | [原文](<guides/无障碍指纹手势.md>)
- — | 指定窗口截图与 ImGui 绘制 | images.isWindowCaptureAvailable()：Android 14+、无障碍服务连接且具有截图能力时返回 true。 | [原文](<guides/WINDOW_CAPTURE_IMGUI.md>)
- — | 悬浮窗开发踩坑与性能数据（脚本作者视角） | 引擎侧对应修复见 docs/guides/悬浮窗触摸穿透说明.md 与需求清单「批 E/F」。 | [原文](<guides/悬浮窗开发踩坑与性能.md>)
- — | 悬浮窗触摸穿透与圆形形状说明 | 结论先说：「按区域接收触摸」（圆内可点、圆外穿透）在 Android 上做不到， clipToOutline 只裁剪本窗口的绘制，不会改变窗口对系统的输入区域。 本文给出实测数据与真正可行的替代方案。 | [原文](<guides/悬浮窗触摸穿透说明.md>)
- — | 液态玻璃导航：实现、接入与优化 | 本文介绍 AI.js Pro 顶部图标栏的液态玻璃选中胶囊。完整 Java 组件源码附在文末，可以复制到其他 Android 项目；接入本项目时直接使用已有组件。 | [原文](<guides/液态玻璃导航_实现接入与优化.md>)

**MCP 与工具**

- — | MCP 连接指南（手机 AI.js Pro ↔ 电脑 AI 客户端） | 本文对应 dev-1251+；文件/APK 工具一节对应 dev-1326，验证和发布状态见本轮验收。 | [原文](<guides/MCP连接指南.md>)
- — | AI.js Pro 自研 VS Code 扩展（vscode-extension/） | 当前配套宿主 dev-1112。legacy 通道仍为「电脑拨 9317 / 手机回连 8327」；Flutter 构建另有 ws-rpc 动态端口通道，两条链路独立，扩展按需选择。 | [原文](<guides/VSCode_AIjsPro扩展.md>)
- — | VS Code / Auto.js Pro 扩展自动连接 | 适用：本项目的 AI.js Pro 与本机安装的 hyb1996.auto-js-pro-ext 9.0.9。依据是该扩展 dist/extension.js 的实际 TCP 协议实现；不代表已经实现其全部调试功能。 | [原文](<guides/VSCode_Pro扩展自动连接.md>)

**专项**

- — | 象棋引擎插件（Auto.js 插件 APK） | 同步 JNI 接口保留。Node 可用 engine.createUci() 创建异步会话，支持 ponder/ponderhit，自动选择通用/优化 ARM64 引擎。 | [原文](<guides/象棋插件_打包与使用.md>)
- — | 双引擎 API 与 UI 性能验证 | 仓库路径包含中文时，Windows 上的 Gradle Test Worker 可能无法加载测试类。使用临时 ASCII 盘符运行： | [原文](<guides/ENGINE_API_AND_PERFORMANCE.md>)

> 指南也需注意时效：`ENGINE_API_AND_PERFORMANCE.md` 只覆盖 Rhino/QuickJS（2026-10-19，Node24 未纳入）；`API_REFERENCE`/`NODE_UI_API` 以最新生成索引为准；`REPO_CONVENTIONS.md` 是 2026-11-06 新规范（提到尚未出现的 `ARCHITECTURE.md`）。

### 4.3 架构与 API 对照（architecture/，10 篇 Markdown + 契约数据）

- — | 第一代 Pro API 逐函数对照（Auto.js Pro 官方 API 文档 PDF） | 对照日期：2026-09-15（§4 / §5.4~§5.8 / §6 / §7 为 $crypto、$zip、$debug、$power_manager、floaty 权限、$settings 补齐后重跑）。对象： | [原文](<architecture/第一代API逐函数对照.md>)
- — | 工程模式 Android 资源（`project.json` 的 `androidResources`） | 给「照着 Auto.js Pro 的工程写的脚本」兜底：工程里带 res/ 时，ui.useAndroidResources()、 ui.R.*、ui.layoutFile("名字")、布局里的 @color/@dr… | [原文](<architecture/工程模式Android资源.md>)
- — | 双引擎功能对比（Rhino / QuickJS） | 变更：P0（选择器 / UiObject、setScreenMetrics）、P1（顶层别名、random/sync/auto/print/err、应用快捷别名、选择器动作全局形式）、P1b（gesture/gest… | [原文](<architecture/双引擎功能对比.md>)
- — | AI.js Pro 接入 V8：框架瓶颈与迁移方案 | 本文为源码审阅后的接入设计。本次只新增设计文档，不修改默认引擎，不发布新 APK。 | [原文](<architecture/V8接入_框架瓶颈与迁移方案.md>)
- — | AI.js Pro 历史架构记录（已归档） | 当前替代结论（2026-10-19，dev-1112）： 界面是 Flutter（dev 通道主入口）+ Miuix（默认构建/回退）双轨；旧 ImGui 工作台和对应 .so 已删除； | [原文](<architecture/项目说明.md>)
- — | AI.js Pro 双 JavaScript 引擎架构 | 路径核对日期：2026-09-04。双引擎、Native Frame + C++ OpenCV 图色处理、OpenCV YOLO 和白名单 API 已落地；QuickJS 为显式选择的新引擎， | [原文](<architecture/QUICKJS_ENGINE.md>)
- — | Auto.js Pro 第一代 API 兼容性与 V8 选型 | 附件第 1 页明确说明这是第一代 API，对应 Rhino 默认引擎。该 PDF 共 335 页，是微信公众号整理的文档汇编，不能据此认为它覆盖当前 Pro 全部 API 或第二代 API。 | [原文](<architecture/Auto.jsPro_API兼容性与V8选型.md>)
- — | QuickJS 默认与 Rhino 兼容分层 | 选择优先级从高到低： | [原文](<architecture/ENGINE_FLAVORS.md>)
- — | QuickJS 迁移与 Rhino 移除门禁 | `powershell .\tools\test-engine-flavors-device.ps1 -Serial cccc62c7 | [原文](<architecture/QUICKJS_MIGRATION_MATRIX.md>)
- — | QuickJS ImGui 悬浮窗 API 边界 | ImGui 不再用于 APK 主界面或工作台。它作为 QuickJS 脚本引擎的可选悬浮窗 API，用于脚本创建实时调试面板、按钮、日志和图形，定位与 Auto.js Pro 的脚本悬浮 UI 类 似。 | [原文](<architecture/QUICKJS_IMGUI_FLOATY.md>)

> 全部 architecture 文档都带 2026-10-19 时效说明（Node24 第三引擎加入之前的快照）；`项目说明.md` 自述已归档；`pro-api-contract.json`（4.2MB）与 `pro-api-compatibility.csv`（195KB）是静态对照数据，不逐条索引。

### 4.4 历史报告（reports/，380 篇 Markdown，按主题）

> 主题由标题/文件名关键词优先级判定（见 §2.5），同一篇只进一个主题；「⚠️历史/被取代」只表示正文/标题出现明确历史措辞，详细依据见 §5。

#### 4.4.1 MCP 与工具链（30 篇）

- 2026-11-06 | 按 `.4` 项目反馈修的第二批（dev-1359） | 接 第一批（dev-1356）。本批做反馈里剩下的三条：P0-2/P2-12（文档落后于构建）、P1-6（三套坐标系，用户标★最痛）、P0-5（图片句柄泄漏没有线索）。 分工：Lead 做 P1-6 并接线； | [原文](<reports/2026-11-06_按poker-recog反馈修MCP第二批（dev-1359）.md>)
- 2026-11-06 | 按 `.4` 项目反馈修的第一批（dev-1356） | 「只落盘」比「更快地回传」重要一个数量级：反馈里说的「PNG 编码 550ms 是主因」被实测证实——而真正的解法不是把 PNG 压得更小， | [原文](<reports/2026-11-06_按poker-recog反馈修MCP第一批（dev-1356）.md>)
- 2026-11-06 | 按交付包《MCP-改造-checklist.md》落实（dev-1360） | 其中 MCP-改造-checklist.md 是基于反汇编我们 dev-1359 得出的可勾选改造清单；本报告逐条对账「改了没 / 怎么验的 / 和交付方判断不一致的地方」。 另 5 份文档的性质： | [原文](<reports/2026-11-06_按交付包落实MCP改造清单（dev-1360）.md>)
- 2026-11-06 | MCP 截图：质量模式与快速模式（dev-1353/1354） | 用户要求：MCP 截图「一个质量好、一个快速的」。做法：给 capture_screen 加 mode 预设，并把真实耗时/字节回传，让调用方自己权衡。 | [原文](<reports/2026-11-06_MCP截图质量与速度双模式.md>)
- 2026-11-05 | MCP 改进第二批：自动证据、结构化路由、r2 会话持久化（dev-1338/1339） | 承接 用户实测三个 bug 修复。本批把三个「遗留/待改进」项做完，并用新工具对真实 VMP 目标做了一次独立复核。 | [原文](<reports/2026-11-05_MCP改进第二批（自动证据_路由字段_会话持久化）.md>)
- 2026-11-05 | MCP 改进第三批：常驻 r2 会话与大函数分段投影（dev-1340→1344） | 承接 改进第二批。本批把用户最初要的「一次加载、多次查询」真正做到毫秒级，并补上 82KB 级函数的可读性。 | [原文](<reports/2026-11-05_MCP改进第三批（常驻r2会话）.md>)
- 2026-11-04 | 用户实测三个 bug 的修复（dev-1337） | 同批收录被逆向项目侧总结：SonicTopography 项目总结。 三个 bug 由三个并行工作流实现，Lead 负责接线、编译、真机验证与发布。 | [原文](<reports/2026-11-04_MCP实测三个bug修复（dev-1337）.md>)
- 2026-11-04 | MCP 免 Root Gadget APK 集成 | 按用户要求，只增加免Root Gadget路径，不部署Root Server。主要功能通过手机端MCP完成，不依赖电脑作为分析主机。电脑用于本轮开发、打包和真机验收。 | [原文](<reports/2026-11-04_MCP免RootGadget集成.md>)
- 2026-11-04 | MCP 用户体验反馈第一批（用户侧复盘 §2.1–§2.7） | 这份复盘讲「实际卡在哪」，与开发侧建议书互补；本轮按它的「优先三件事」+ 其余痛点逐条落地。四个工作流并行实现，Lead 负责接线、编译、真机验证与发布。 | [原文](<reports/2026-11-04_MCP用户体验反馈第一批（UX1-5）.md>)
- 2026-11-03 | MCP 对齐实现与验收（dev-1326，已发LAN） | 用户要求先完成全部功能修改，再统一测试。范围为上一轮真实差异，对应MT2.26.10公开的57种MCP方法。本轮识别的核心实现差异已修复，验收仅证明下列场景，不推断任意设备、APK、参数组合完全等价。 | [原文](<reports/2026-11-03_MCP对齐实现进度.md>)
- 2026-11-03 | MCP 与 MT 功能实现对照及缺口 | 此文是dev1325实施前的审查快照。本轮识别的核心差异随后已修复并完成dev1326验收，见实现与验收，现已发布LAN1326。以下保留原始证据和当时结论，不代表当前仍全部未实现。 | [原文](<reports/2026-11-03_MCP与MT功能实现对照及缺口.md>)
- 2026-11-02 | Android 17 Shizuku 终端兼容修复 | 本轮承接 dev-1311 的无障碍修复，处理新手机上通用 Shizuku 命令仍无法启动的问题。dev-1312 最终候选已安装到新手机，并通过下列验收。手动包已放 Downloads 和 LAN， | [原文](<reports/2026-11-02_Android17_Shizuku终端兼容修复.md>)
- 2026-11-02 | MCP / 内置终端按需 C/C++ 编译组件 ⚠️历史/被取代 | 后续修订：公网地址已补齐，dev-1307支持默认公网安装并修复HTTPS安装结束后的CLI退出；见公网下载与修正版验收。下文LAN命令与1306记录保留历史时点；组件ZIP及摘要未变。 | [原文](<reports/2026-11-02_MCP终端按需C与C++编译组件.md>)
- 2026-11-02 | MCP 文件/APK 工具箱与 Capstone 反汇编（dev-1315） ⚠️历史/被取代 | 运行在独立 :tools 进程（McpToolkitProvider，私有 ContentProvider + 文件 IPC，请求≤20MiB、响应≤24MiB，避免 Binder 事务上限）； | [原文](<reports/2026-11-02_MCP文件APK工具与Capstone反汇编.md>)
- 2026-11-01 | Shizuku 三引擎、终端与 MCP 接入 | 主应用及独立 APK 在 Application.attachBaseContext 后立即启用 SDK 多进程模式，工作线程按需取 Binder。每进程维护接收/死亡监听、授权和真实后端 UID； | [原文](<reports/2026-11-01_Shizuku三引擎终端接入与局域网发布.md>)
- 2026-10-31 | dev-1295 文档线：MCP 与 API 文档更新 | 来源依据：设备 192.168.10.4 上 chess-auto/docs/已知问题.md（第 2 章、第 2.15 节）与 chess-auto/node-port/ui/README.md（第 6 章）， | [原文](<reports/2026-10-31_dev1295文档线MCP与API更新.md>)
- 2026-10-26 | MCP 接入 API 文档与 dev-1257 | 用户要求把 App 内 API 文档提供给 MCP，更新安装包。新增 searchapidocs 和 readapidoc 两项只读工具；现有引擎执行、示例读取与接口探针保留。主助手实现、集成与验收， | [原文](<reports/2026-10-26_MCP接入API文档与dev1257.md>)
- 2026-10-25 | MCP 连接指南 App 入口与 dev-1252（2026-10-25） | 点击后由 Flutter LocalBridge openUrl 打开仓库指南： https://github.com/Jdkshen/ai.js-pro/blob/main/docs/guides/MCP连接指南.… | [原文](<reports/2026-10-25_MCP连接指南入口与dev1252.md>)
- 2026-10-25 | MCP 连接指南本地化与 dev-1253 | 用户反馈网络卡，App 内指南链接改为本地内置文档：assets/docs/mcp-guide.html，由 DocumentationActivity 的 WebView 直接加载，不经过浏览器/网络。 | [原文](<reports/2026-10-25_MCP连接指南本地化与dev1253.md>)
- 2026-10-25 | MCP 手机终端与编译入口 ⚠️历史/被取代 | 后续范围更新（2026-11-02）：用户重新授权“C/C++通过终端按需下载、主APK保持小”，此部分已实现候选dev-1306，见新组件与验收。下文2026-10-25取消决定保留为历史， | [原文](<reports/2026-10-25_MCP手机终端与编译入口.md>)
- 2026-10-25 | MCP 无障碍 UI 自动化与应用管理（2026-10-25） | 用户要求在没有 root 的情况下，用已有无障碍权限给 MCP 增加 UI 自动化和应用管理能力；只保留 MCP 终端，不做 Java/C/C++/APK 编译工具链。本版实现： | [原文](<reports/2026-10-25_MCP无障碍UI自动化.md>)
- 2026-10-22 | 三引擎浮窗统一注册与清理（OverlayRegistry + MCP overlay 工具） | 规格来源：C:\Users\18101\Downloads\xiangqi-draw\xiangqi-draw\docs\悬浮窗-MCP注册-三引擎改造.md | [原文](<reports/2026-10-22_三引擎浮窗统一注册与清理.md>)
- 2026-10-21 | MCP 三引擎接入与 Node 传统 UI 修复 | APK：AI-js-Pro-Flutter-dev-1188.apk，151385529 字节。 SHA-256： | [原文](<reports/2026-10-21_MCP三引擎与Node传统UI修复.md>)
- 2026-10-21 | MCP、电脑连接和日志优化 | 当前发布 dev-1190；K40 cccc62c7、小米 8 ce4d2bdb 已覆盖安装。VS Code 扩展为 AI.js Pro 0.3.2（jdkshen.aijspro-vscode）， | [原文](<reports/2026-10-21_MCP界面电脑连接与日志优化.md>)
- 2026-10-20 | MCP 去额外授权（dev-1157） ⚠️历史/被取代 | 背景：用户在手机端找不到「编辑授权 / 运行授权」开关（该 UI 原在已移出构建的 Miuix 页面），MCP 工具实际永远拒绝写入/运行（写入授权未开启 / 运行授权未开启）。 决定： | [原文](<reports/2026-10-20_MCP去额外授权.md>)
- 2026-10-18 | 打包 / MCP / 电脑连接 — 功能细节对照与待修清单 | Flutter 入口（lib/browser.dart）： - 项目目录 →「项目配置与打包」→ 打开玻璃自绘打包页（ProjectPage）； - 单文件/示例文件 → 先调桥把它落盘成临时项目，再打开打包页； | [原文](<reports/2026-10-18_打包MCP电脑连接_功能对照.md>)
- 2026-10-18 | MCP 与电脑连接修复验收 | 最终版本：dev-1048，已发布并安装到 K40。更新源 http://192.168.10.7:8080/update.json 与 APK 均返回 HTTP 200；清单大小、SHA-256 与实际产物一致。 | [原文](<reports/2026-10-18_MCP与电脑连接修复验收.md>)
- 2026-10-03 | MCP 接口补齐检查与现有接口修正 | 后续更新：本文保存修复前的审计状态。所列 MCP 缺项、停止范围和任务日志差异已在 dev-781 补齐并发布；59 项单测和 21 项真机检查通过，详见 MCP 接口对齐记录。 | [原文](<reports/2026-10-03_MCP接口补齐检查.md>)
- 2026-10-03 | MCP 接口对齐与双引擎参数修复（dev-781） | 后续发布：2026-10-04 已将相同 APK 上传到 GitHub Release，并补齐三个 ABI 和更新清单；K40 的真实应用检查更新及下载链接验证见 GitHub 发布记录。 | [原文](<reports/2026-10-03_MCP接口对齐_dev781.md>)
- 2026-10-02 | 高级版 Auto.js Pro MCP 功能对比与 Canvas 兼容复测 | 用户提到的 cmp 暂按 MCP 理解。已找到高级版 APK com.boyai.auma（Pro 9.3.11-0）的 MCP 模块；这是该高级版包的证据，不代表所有官方 Pro 9.3.11 都包含这些功能。 | [原文](<reports/2026-10-02_高级版MCP对比与Canvas兼容复测.md>)

#### 4.4.2 Node / QuickJS / Rhino 引擎与 UI（117 篇）

- 2026-11-05 | 「悬浮窗关闭不了」定位与修复（dev-1352） | 用户反馈：悬浮窗好像有 bug，关闭不了。以下把两类悬浮窗都实测了一遍，定位到真正的缺陷并修掉。 | [原文](<reports/2026-11-05_悬浮窗关闭问题定位与修复.md>)
- 2026-11-05 | $plugins 缺失行为核查（Rhino / QuickJS / Node24） | 脚本执行 $plugins.load("com.hraps.ocr") 时，当前三个引擎行为不一致： | [原文](<reports/2026-11-05_plugins缺失行为核查.md>)
- 2026-11-05 | 内置 Rhino 示例全量运行与引擎修复（dev-1348 → 1351） | 起因：用户反馈「跑一下 rhino 里面的示例 有报错了」。做法：把内置 160 个 Rhino 示例真机全量跑一遍，把属于我们引擎的问题修掉。 | [原文](<reports/2026-11-05_Rhino内置示例全量运行与引擎修复.md>)
- 2026-11-03 | Node 原生界面示例加载失败（dev-1313+ 回归）— 历史排查记录 ⚠️历史/被取代 | 当前 LAN 发布版为 dev-1318；MI8 已装 1318；.4 已装 1317。仅 LAN、本地提交，不要 push、不要建 GitHub Release。 | [原文](<reports/2026-11-03_Node原生界面编译资源加载失败.md>)
- 2026-11-03 | Node 原生资源与动态回调修复验收 | 第 53 行 BottomNavigationView 崩溃来自平台 API 副本。 | [原文](<reports/2026-11-03_Node原生资源与动态回调修复验收.md>)
- 2026-11-02 | 脚本目录、文件管理与示例规则对齐 | dev-1308 测试包已在小米 8（ce4d2bdb，Android 35）安装验收。仅提供手动候选包；LAN 自动更新源仍为 dev-1305，未 push、未创建 GitHub APK 发布。K40 未动。 | [原文](<reports/2026-11-02_脚本目录与文件示例规则对齐.md>)
- 2026-11-01 | 剩余对齐清单（dev-1302） ⚠️历史/被取代 | 这里的“五个方向”是工作分类，不是五个小 bug。旧总账的 16 个工作包是 1292 时点范围，不能作为当前未实现数量；3,980 条高级版声明也不能当成 3,980 个缺失 API。 | [原文](<reports/2026-11-01_剩余对齐清单_dev1302.md>)
- 2026-11-01 | Node 内联 UI 与原生监听语义修复 | 后续进展：旧协议句柄监听、轮询清理、转发隔离和资源误告警已在 dev-1301 候选 续修，并修复实际 :nodeui 句柄误查。下文为 dev-1300 的原始证据与当时边界，不作为最新待办重复计数。 | [原文](<reports/2026-11-01_Node内联UI监听语义修复.md>)
- 2026-11-01 | Node 协议句柄、监听与界面进程转发兼容 | 后续 dev-1302 已补原生选择与旧协议五项菜单命令，并通过 MI8/独立包回归，见 后续报告。下文菜单待办是1301时点，完整旧视图树和更多第三方事件仍保留。 | [原文](<reports/2026-11-01_Node协议句柄与事件转发兼容.md>)
- 2026-11-01 | Node 选中事件与旧协议菜单兼容 ⚠️历史/被取代 | 原生导航的 item_selected 使用正确的 onNavigationItemSelected(MenuItem)，通知回调返回接受选中。旧代码实现了另一个方法名，实际选择不触发 JS； | [原文](<reports/2026-11-01_Node选中事件与旧协议菜单.md>)
- 2026-10-31 | Node UI 事件与视图树修复（dev-1297） | 本轮针对其中的 Node UI 问题做实现并在 MI8 上做真机复测（dev-1297）。 | [原文](<reports/2026-10-31_NodeUI事件与视图树修复dev-1297.md>)
- 2026-10-31 | Node UI：JS 回调默认值与工程资源路径（dev-1299） ⚠️历史/被取代 | 根因不是 dev-1297 的补丁失效，而是他们的工程没有可用的编译资源包： | [原文](<reports/2026-10-31_NodeUI回调默认值与资源路径dev-1299.md>)
- 2026-10-31 | R01：Node Java 反射空可变参数修复（候选 dev-1293） | Node24 的 Java 互操作在 findMethod 里只按「参数个数完全相等」匹配： | [原文](<reports/2026-10-31_R01反射空可变参数修复.md>)
- 2026-10-30 | 高级版与三引擎剩余对齐总账（dev-1292） | 最新状态（2026-11-01，候选 dev-1302）：当前剩余清单，选中与旧协议菜单验收。菜单五项命令/原生选中已修，宿主107项与独立包12项通过；R04 $ui.inflate 不再计缺失， | [原文](<reports/2026-10-30_高级版与三引擎剩余对齐总账.md>)
- 2026-10-30 | Node Widget 标签注册兼容续修 | 原流程先将 row/grid 等替换为 Node 控件类，再由 XmlConverter 映射 button 等内置简称；Widget 注册名因此丢失。父组件 render 才注册外部子组件时， | [原文](<reports/2026-10-30_Node_Widget标签注册兼容.md>)
- 2026-10-29 | Node 原生 View 与高级版兼容续修 | Node 尚未全部对齐高级版。 dev-1286 已完成引擎宿主、停止、回调退出、资源回收和日志架构的这一轮改造，并在小米 8 验证独立 Node APK、三引擎隔离与 M3 停止。 | [原文](<reports/2026-10-29_Node原生View事件与高级版兼容续修.md>)
- 2026-10-29 | Node 原生列表停止崩溃（dev-1284 / Android 36） | 用户确认在停止脚本时发生崩溃。堆栈为 RecyclerView.validateViewHolderForOffsetPosition → GridLayoutManager → ViewPager， | [原文](<reports/2026-10-29_Node原生列表停止崩溃.md>)
- 2026-10-29 | Node Widget 属性兼容续修 | 仍使用已确认身份的 output/auma-base.apk：com.boyai.auma / 9.3.11-0， | [原文](<reports/2026-10-29_Node_Widget属性兼容续修.md>)
- 2026-10-28 | 节点 API 隔离回归与后续对齐 | 基线：DP 已将上一轮工作提交为 0278ced3，dev-1279 已发布 LAN 并安装 MI8。此报告记录在该提交之后的 dev-1280 测试候选；当前 LAN 仍为 1279。 | [原文](<reports/2026-10-28_节点API隔离回归与后续对齐.md>)
- 2026-10-28 | 三引擎节点 API 复核与修复（dev-1279） | 接过 GPT 在 2026-10-28 上午未提交的节点 API 复核工作，先修复其编译断点，再完成验证、构建与发布。 | [原文](<reports/2026-10-28_三引擎节点API复核与修复.md>)
- 2026-10-27 | 小米超级岛：三引擎脚本运行状态测试版 | Rhino、QuickJS、Node 脚本共用一条实时运行通知；支持系统时附加小米 OS2 焦点通知 / OS3 超级岛参数。主机不支持或权限未开通时保留普通运行通知，前提是系统允许该应用和通知通道发通知。 | [原文](<reports/2026-10-27_小米超级岛脚本运行状态测试版.md>)
- 2026-10-27 | vivo实况通知适配与测试说明 | # vivo / iQOO 实况通知适配候选 dev-1271 | [原文](<reports/2026-10-27_vivo实况通知适配与测试说明.md>)
- 2026-10-26 | 三引擎 API 离线文档与 dev-1254 | 主界面顶部「本地使用指南」新增「三引擎 API 文档 · 离线搜索」入口。文档随 APK 分发，搜索不请求网络。 756 条参考条目来自 4 份维护中的指南与 25 份传统 API HTML； | [原文](<reports/2026-10-26_API离线文档与dev1254.md>)
- 2026-10-25 | Node 资源 Context 兼容与 UI 进程隔离 | 原来 $autojs.androidContext 返回普通 Application Context，缺少高级版的 loadResources(String)。这是 Java 桥返回类型不兼容，日志本身属于脚本异常。 | [原文](<reports/2026-10-25_Node资源Context与UI进程隔离.md>)
- 2026-10-25 | Node24 JS 回调异常逃逸主线程修复（dev-1251） | java.lang.IllegalStateException: JavaScript callback threw an exception or was cancelled org.autojs.source.e… | [原文](<reports/2026-10-25_Node回调异常逃逸修复.md>)
- 2026-10-24 | 高级版 M3 框架与加载路径对比 | 下载目录同名高级版 APK 的 SHA-256 为 70eb340a…，与已安装包不同，部分 DEX 和 UI 模块也不同。最终结论使用已安装包，不混用两个版本的实现细节。 REA 包清单 Evidence： | [原文](<reports/2026-10-24_高级版M3框架与加载路径对比.md>)
- 2026-10-24 | DP 任务 1：Node M3 示例首屏 setup 调度与重建（2026-10-24） | 此为 DP 首次交付记录；主助手的后续修正、实际构建和最终 MI8 验收以五项优化任务与验收为准。 | [原文](<reports/2026-10-24_DP任务1_M3首屏.md>)
- 2026-10-24 | DP 任务 5：三引擎 API 契约与诊断 | 此为 DP 首次交付记录；主助手的后续修正、实际构建和最终 MI8 验收以五项优化任务与验收为准。 | [原文](<reports/2026-10-24_DP任务5_三引擎诊断.md>)
- 2026-10-24 | DP 任务2：Node24 运行时缓存校验（2026-10-24） | 此为 DP 首次交付记录；主助手的后续修正、实际构建和最终 MI8 验收以五项优化任务与验收为准。 | [原文](<reports/2026-10-24_DP任务2_Node冷启动.md>)
- 2026-10-24 | M3Kit 性能报告复核（192.168.10.4） | 报告记录的 dev-1242 启动和页面构建数据明显改善，但还应继续处理以下五项。先修功能与生命周期，再处理微基准；“200 个 View 上限”应从待修清单撤下，现有日志不支持该结论。 | [原文](<reports/2026-10-24_M3Kit性能报告复核.md>)
- 2026-10-24 | M3Kit 原生 DEX 接入与宿主 ClassLoader 修复（dev-1232） | M3Kit native.js 通过 host.runtime.loadDex + $java.defineClass(..., {packageName:"m3ui"}) 生成 m3ui.NativeView； | [原文](<reports/2026-10-24_M3Kit原生DEX接入与宿主ClassLoader修复.md>)
- 2026-10-24 | Node M3 四项底层优化（2026-10-24） | 按用户确认的四个方向独立实现，复用本仓库和公开库接口。高级版反编译结果只用于判断调用路径，没有复制其实现。 | [原文](<reports/2026-10-24_Node_M3四项底层优化.md>)
- 2026-10-23 | Node 浮窗底层修复交付（dev-1215 基线 → dev-1221 终版） | 规格：C:\Users\18101\Downloads\悬浮窗底层改造方案.md | [原文](<reports/2026-10-23_Node悬浮窗底层优化.md>)
- 2026-10-23 | Node M3 示例启动性能优化 | 更新：2026-10-23。本轮处理用户反馈的 Node 原生 M3 示例启动慢、初始化卡顿。曾按用户要求等待同机另一任务先完成；用户随后要求发布新版本，核实 MI8 已空闲后恢复验证， | [原文](<reports/2026-10-23_Node_M3启动性能优化.md>)
- 2026-10-23 | Node24 动态 UI 底层修复 | 更新：2026-10-23。输入为用户提供的 C:/Users/18101/Downloads/AIjsPro-Node24-UI-Bugs.md，该文件保留原样。本轮针对其中明确的 API 缺陷修改底层， | [原文](<reports/2026-10-23_Node24动态UI底层修复.md>)
- 2026-10-22 | 三引擎 API 统一与高级版全量契约 | SHA-256 bceab5c6a793852a1ee60e5d64749f83a5023eb41aee496a4ff6366df9af6c25；151,730,558 字节。 | [原文](<reports/2026-10-22_三引擎API统一与高级版全量契约.md>)
- 2026-10-22 | DotJumpLoading 原生组件兼容修复 | APK：AI-js-Pro-Flutter-dev-1200.apk，151,737,565 字节。 SHA-256： | [原文](<reports/2026-10-22_DotJumpLoading原生组件修复.md>)
- 2026-10-21 | 三引擎细节复核与 Pikafish 致命退出修复 | 本轮由“把三个引擎的功能细节核对一次”开始，随后接到象棋手机 native SIGABRT 的诊断记录。最终发布 dev-1194，象棋插件 APK 更新为 1.0.1 / 2。 | [原文](<reports/2026-10-21_三引擎细节复核与原生闪退修复.md>)
- 2026-10-21 | 示例列表“运行项目”入口修复 | APK SHA-256：42e2396c0d8c8859b81b992fa910da179ef8901dbc6561455a0c5f69f8503fab。 K40 / 小米 8 均已安装最终 dev-1184； | [原文](<reports/2026-10-21_示例目录运行入口修复.md>)
- 2026-10-21 | Node 高级版兼容续修：原生 UI、任务和设置 | 开始基线 7cd0dbaf / 更新源 dev-1177。测试设备：小米 8 ce4d2bdb，Android 15。 本批为本地 dev-1178 候选，尚未写入更新源；不代表高级版全部示例已兼容。 | [原文](<reports/2026-10-21_Node高级版兼容_原生UI任务设置.md>)
- 2026-10-21 | Node 自定义组件与重复应用窗口修复 | APK：AI-js-Pro-Flutter-dev-1186.apk，151131026 字节。 SHA-256： | [原文](<reports/2026-10-21_Node自定义组件与重复窗口修复.md>)
- 2026-10-20 | 高级版日志交互与三引擎统一日志验收 | dev-1154 已发布到本机更新源，并安装到小米 8 透明探索版（ce4d2bdb，系统报告 Android 15）。K40 未覆盖安装。 | [原文](<reports/2026-10-20_统一日志移植验收.md>)
- 2026-10-20 | 悬浮日志 SCRIPT_ERROR 与 item 绑定 warning（task-194） | 用户日志（dev-1138）： 悬浮日志.node.js 首次运行：01:01:04.886 打印结束行 运行结束，用时15.121000秒， | [原文](<reports/2026-10-20_悬浮日志SCRIPT_ERROR与item绑定warning.md>)
- 2026-10-20 | 悬浮日志长时间运行后 ✕ 关闭 exitCode=1 修复（task-195） | 新机（dev-1139）用户日志：悬浮日志运行 15.124s → 结束行 → 200ms 后 E SCRIPT_ERROR: Node 脚本异常退出，exitCode=1； | [原文](<reports/2026-10-20_悬浮日志长时间运行后关闭修复.md>)
- 2026-10-20 | 引擎单测修复：Rhino 样例引擎判定 + 文本文件 Intent 样例 | :engine:testDebugUnitTest 330 项中 3 项失败（327 pass / 3 fail）： | [原文](<reports/2026-10-20_引擎单测修复.md>)
- 2026-10-20 | Node 高级版示例兼容：代码与实测进度 | 仓库：C:/Users/18101/Documents/ai-js-pro，测试设备小米 8 ce4d2bdb（Android 15）。 原始参照： | [原文](<reports/2026-10-20_Node高级版示例兼容_实测进度.md>)
- 2026-10-20 | Node 示例分类与覆盖（2026-10-20） | 配套工具：tools/test-node-samples-device.ps1（自动发现 → push → 逐个 RunIntentActivity → logcat 判定 → summary.json/md）。 | [原文](<reports/2026-10-20_Node示例分类与覆盖.md>)
- 2026-10-20 | Node 特征匹配 drawMatches 与 viewFile 路径修复（task-184） | 示例 #34「特征匹配(全分辨率找图).js」跑完后 app.viewFile('./matches.jpg') 报文件不存在。 | [原文](<reports/2026-10-20_Node_feature_drawMatches路径修复.md>)
- 2026-10-20 | Node 新增 API 示例补齐与 K40 真机验证 | 新增资产：象棋识别/board.png（从 samples/象棋自动走棋/assets/board-sift/board.png 复制，1.2 MB）； | [原文](<reports/2026-10-20_Node新增API示例与K40验证.md>)
- 2026-10-20 | Node Java 桥二次提速（2026-10-20） | 本轮针对反复调用 Java 方法，未改 Node/V8 二进制。最终有效同机对照中，2000 次无参数 Java 调用的中位耗时从 23.832ms 降到 16.441ms，减少约 31.0%。 | [原文](<reports/2026-10-20_NodeJava桥二次提速.md>)
- 2026-10-20 | Node UI 关闭后清理窗口优化（task-186） | task-185 修复后，BACK 关闭 Node UI 不再误报错，但 UI/Binder 清理最长约 15s；此窗口内重跑被拒「当前 Node 脚本正在停止，请稍后重试」。目标：BACK 后 ≤1s 可重跑。 | [原文](<reports/2026-10-20_NodeUI关闭清理窗口优化.md>)
- 2026-10-20 | Node UI 关闭后误报错修复（task-185） | t2 dev-1131 必现：Node UI（colored-button / NodeAndroidUI）按 BACK 关闭后 ~2.6s 弹「错误/脚本运行失败： | [原文](<reports/2026-10-20_NodeUI关闭误报错修复.md>)
- 2026-10-20 | Node UI owner 隔离：双 job 停一个不再互删浮窗（task-188） | t2 dev-1133 复验：悬浮日志连跑两次 → 2 个独立面板（overlay-1/overlay-2，任务页 2 行）；停第一行后， | [原文](<reports/2026-10-20_NodeUI_owner隔离双job修复.md>)
- 2026-10-20 | xiangqi-aj-node 0.6.80 Node 入口兼容性分析（task-197） | 用户提供的第三方工程 xiangqi-aj-node-0.6.80（AJ Pro v172 兼容版，Node16 UI 入口）在 K40 dev-1140 运行 AJ-Node启动.node.js 即失败： | [原文](<reports/2026-10-20_xiangqi-aj-node兼容性分析.md>)
- 2026-10-19 | 2026-10-19 Node UI 返回键语义（task-178） | 用户反馈：Node UI（NodeUiActivity）按导航返回键后脚本没有立即结束，页面返回/桌面后脚本仍在跑。 | [原文](<reports/2026-10-19_NodeUI返回键语义.md>)
- 2026-10-19 | 架构对齐 Phase 1：引擎并入 :script（task-175，2026-10-19） | 当前原生库只在 :node24 进程可加载（Node24Libraries.load 有 isNodeProcess guard）， | [原文](<reports/2026-10-19_架构对齐Phase1_引擎并入script.md>)
- 2026-10-19 | 三引擎性能与 AutoJs 高级版日志移植 | Node 的纯 JS 运算接近高级版；原来的主要差距在 Java 桥。缓存反射元数据之后，2000 次 Java 调用的中位耗时由 324ms 降至 102ms，约减少 69%；高级版同项为 83ms。 | [原文](<reports/2026-10-19_三引擎性能与高级版日志移植.md>)
- 2026-10-19 | 停止脚本入口修复（task-165） | （K40 cccc62c7 本轮 adb 掉线，改在新手机验证；K40 复测项见 §6。） | [原文](<reports/2026-10-19_停止脚本入口修复.md>)
- 2026-10-19 | 停止全部/任务页覆盖 Node24 uiScript（task-167） | t2 在 K40 dev-1115 复验：任务页「运行中任务=空无一物」（:node24 明明在跑）； | [原文](<reports/2026-10-19_停止全部覆盖Node脚本.md>)
- 2026-10-19 | 小米8三引擎示例实测与修复（2026-10-19） | 289项原始清单中，272个独立入口均已在小米8 ce4d2bdb 启动尝试，17个工程配置、模块及子脚本不计独立入口。日志与结果逐条保存在账本。启动过不等于全部功能通过。 按目前核对到的业务结果、断言和实际交互， | [原文](<reports/2026-10-19_小米8三引擎示例实测与修复.md>)
- 2026-10-19 | dev-1126 样例复跑 + BUSY 场景（K40/MI8） | MODULE axios/stream/util/fs/path/http/https 全部 OK axios：keys=get,post,request； | [原文](<reports/2026-10-19_Node失败示例复跑dev1126.md>)
- 2026-10-19 | MI8 旧账本 Node 失败示例复跑（dev-1120） | 复跑工具：tools/run-mi8-samples.py（GPT 留下，--asset 走 APK 内置示例，实际源码与仓库一致： | [原文](<reports/2026-10-19_MI8失败示例复跑dev1120.md>)
- 2026-10-19 | Node 低成本 API 补全（task-171，2026-10-19） | MI8（debug 465 本机构建）冒烟：enableKeyEvents / lockScreen / notification.requestListeningNotifications / media.play… | [原文](<reports/2026-10-19_Node低成本API补全.md>)
- 2026-10-19 | Node 浮窗示例关闭/停止修复（task-164，2026-10-19） | dev-1112 新手机（520dpi/1280x2772）实测 Node 示例「悬浮日志.node.js」： | [原文](<reports/2026-10-19_Node浮窗示例关闭修复.md>)
- 2026-10-19 | Node 截图授权修复（task-180） | t2（MI8 dev-1126）：#34 特征匹配(全分辨率找图) 走到 requestScreenCapture → 启动 Node24CaptureConsentActivity（:node24）后系统 Medi… | [原文](<reports/2026-10-19_Node截图授权修复.md>)
- 2026-10-19 | Node 模态与通知浮窗事件（task-156） | 通知（K40 已验证）：Node 端 notification.notify 现在由 :node24 宿主的 NotificationManager 创建，支持 action 按钮； | [原文](<reports/2026-10-19_Node_模态与通知浮窗事件.md>)
- 2026-10-19 | Node 失败示例复跑（dev-1122，K40 cccc62c7） | 无障碍：settings put secure enabledaccessibilityservices com.jdkshen.aijspro/com.google.android.accessibility.se… | [原文](<reports/2026-10-19_Node失败示例复跑dev1122.md>)
- 2026-10-19 | Node 引擎管理与线程 API（task-154） | Node24 子进程（:node24）与 :script 进程现在都能通过 app-private Binder 桥访问真实主进程/ScriptEngineService 的引擎注册表； | [原文](<reports/2026-10-19_Node_engines_threads实现.md>)
- 2026-10-19 | Node axios 默认导出与 wakeUp 实现（task-179） | #37 播放音乐.js：require("axios").default 为 undefined → axios.get TypeError 被 async 吞成 SCRIPT_ERROR（下载阶段 24.4s 无消… | [原文](<reports/2026-10-19_Node_axios默认导出与wakeUp.md>)
- 2026-10-19 | Node dialogs.customView：XML 布局描述协议（task-161） | dialogs.build({...}).customView(xml).on(...).show() 现在可用了（K40 已验证）：Node 只传 XML 布局字符串， | [原文](<reports/2026-10-19_Node_dialogs_customView.md>)
- 2026-10-19 | Node image 特征提取/匹配（task-172） | t2 复跑 Node「特征匹配(全分辨率找图).js」失败：image 模块无 matchFeatures、Image 实例无 detectAndComputeFeatures。 基线（K40 dev-1120， | [原文](<reports/2026-10-19_Node_image特征匹配.md>)
- 2026-10-19 | Node Java 类路径访问兼容（task-176） | t2 dev-1122 K40：示例 #42 按键监听失败——const KeyEvent = android.view.KeyEvent; 触发 ClassNotFoundException: 找不到 Java 类… | [原文](<reports/2026-10-19_Node_Java类路径访问兼容.md>)
- 2026-10-19 | Node media / ocr / zip / plugins 最小可用实现（task-158） | zip / ocr：审计确认已有真实实现（task-146），已补 K40 真机冒烟（PASS）； media： | [原文](<reports/2026-10-19_Node_media_ocr_zip_plugins.md>)
- 2026-10-19 | Node UI layoutXml/Widget/自定义 View 修复（task-155，2026-10-19） | layoutXml：dev-1096 已具备（Activity.layoutXml → NodeUiHost.inflateInlineLayout， | [原文](<reports/2026-10-19_NodeUI_layoutXml_Widget修复.md>)
- 2026-10-19 | Node24 控件 API 完善与验证 | 基线为 DP 后续发布并安装到 K40 的 dev-1093。用户授权暂停象棋识别后，由 Codex 负责方向、集成、复核和设备验证，DP 分别实施 Java 桥和 JS 选择器适配。 | [原文](<reports/2026-10-19_Node24控件API完善与验证.md>)
- 2026-10-19 | Node24 BUSY 级联修复（task-174） | t2 复现（MI8 dev-1120 / K40 dev-1121）： | [原文](<reports/2026-10-19_Node_BUSY级联修复.md>)
- 2026-10-19 | Phase1 P1：IAutomationHost 契约与 JSON 协议 | 主进程（com.jdkshen.aijspro）持有 AccessibilityService / Automator / ScreenCapturer / UiSelector 能力， | [原文](<reports/2026-10-19_IAutomationHost契约与JSON协议.md>)
- 2026-10-19 | QuickJS :script 选择器链修复（task-153，2026-10-19） | QuickJS 脚本在 :script 进程执行 text("设置").findOnce() 报： :script 进程暂不支持 QuickJS 选择器链（批一）； | [原文](<reports/2026-10-19_QuickJS_script选择器修复.md>)
- 2026-10-19 | Rhino :script 无障碍事件反向通道（task-163） | IAutomationHost 增加主进程→:script 的 oneway 回调通道（K40 已验证）： | [原文](<reports/2026-10-19_Rhino_script无障碍事件回调.md>)
- 2026-10-19 | Rhino、QuickJS、Node API 与示例核对 | 本轮基线为 dev-1094，检查对象是仓库公开给脚本的应用 API、内置示例及其依赖。Java/Android 可反射接口和 Node 标准库不作为已经逐项验证的全集。修补包与最终验证结果见文末。 | [原文](<reports/2026-10-19_三引擎API与示例核对.md>)
- 2026-10-18 | 多进程 Pref 与设置同步评估（Phase 1-D） | 风险在哪、第一批必须做什么。**结论：风险真实存在且已有存量代码踩线；Phase 1 第一批不做 prefs 底层替换，只做「写者分区 + 每次 run 下发快照 + 隔离进程不写设置」三条约束； | [原文](<reports/2026-10-18_多进程Pref与设置同步评估.md>)
- 2026-10-18 | Node 引擎示例 require/全局依赖与缺口对照（2026-10-18） | 当前 Node24 已提供：Node 24 全部内置模块（fs/path/http/https/stream/util/...）； | [原文](<reports/2026-10-18_Node示例依赖与缺口对照.md>)
- 2026-10-18 | Node UI 返回白页修复（dev-1033） | DP 实现代码，Codex 审查、构建并在 DQKFJB59596L45BI 验证。 | [原文](<reports/2026-10-18_NodeUI返回白页修复.md>)
- 2026-10-18 | Node UI 渲染收尾 — 交给 Codex 的交接文档 | 仓库：C:\Users\18101\Documents\ai-js-pro Flutter 源码工程： | [原文](<reports/2026-10-18_NodeUI渲染收尾_交给Codex.md>)
- 2026-10-18 | Node UI 渲染收尾结果 ⚠️历史/被取代 | 后续修正：用户指出返回会经过空白页。dev-1032 的验收没有覆盖带 UI 标记的 Node 入口；dev-1033 已修复重复 Activity 启动，补充了文件列表点运行和 Rhino 对照验证。 | [原文](<reports/2026-10-18_NodeUI渲染收尾结果.md>)
- 2026-10-18 | Node UI 长时间停留与退出 | 本轮从 Rhino 页面对照回归中发现：Node M3 页面停留超过约 30 秒再返回，会报 TIMEOUT。此前修复的额外空 Activity 已消除；这次是脚本执行寿命与界面寿命脱节。 | [原文](<reports/2026-10-18_NodeUI长时间停留与退出.md>)
- 2026-10-18 | Node24 基础自动化 API 实现与 K40 验证 | 本轮按用户“继续弄 node.js 接口和 API”的要求实施。Codex 负责协议、任务边界、集成审查和 K40 实测，DP 负责分块代码实现。没有改动原生 C++、Flutter Dart 或打包运行时模板。 | [原文](<reports/2026-10-18_Node24基础自动化API实现与K40验证.md>)
- 2026-10-18 | Node24 API 现状与缺口（2026-10-18） | 任务：Node API 对齐第一步——盘点 Node24 引擎 API/模块现状并在 K40 实测探测（fs/path/process/Buffer/require/ui 等）。 | [原文](<reports/2026-10-18_Node24_API现状与缺口.md>)
- 2026-10-18 | Phase 1 P0：脚本全面迁入 :script 进程（dev-954/955/956） | 主进程 com.jdkshen.aijspro :script 进程 Flutter 玻璃界面 / 任务页 / 悬浮条 ScriptHostService（Service+Messenger） PocScriptHo… | [原文](<reports/2026-10-18_Phase1_脚本迁入script进程P0.md>)
- 2026-10-18 | Rhino UI 与 Pro 同机对照 | 同一台设备 DQKFJB59596L45BI，屏幕 1280×2772、density 520。 同一份原始脚本 /sdcard/脚本/hz_ybzj/main.js，标题为“华仔部落-佣兵战纪脚本”， | [原文](<reports/2026-10-18_Rhino界面与Pro同机对照.md>)
- 2026-10-17 | 第三方 ImGui 项目跑不起来的诊断（auto_jspro_imgui） | 对象：C:\Users\18101\Downloads\autojsproimgui-fdf74aaeafba4e7f8c52cc0b8f4a516.zip | [原文](<reports/2026-10-17_第三方ImGui项目跑不起来诊断.md>)
- 2026-10-17 | 示例集按引擎重排：Rhino 引擎 / QuickJS 引擎 / Node 引擎（dev-928） | apps/app/src/main/assets/sample/ Rhino 引擎/<分类>/... ← Auto.js Pro 原包示例集（中文， | [原文](<reports/2026-10-17_示例集按引擎重排为Rhino-QuickJS-Node.md>)
- 2026-10-17 | Node24 第三引擎接入与 Auto.js Pro 示例集实测（2026-10-17） | 本文记录把"Auto.js Pro 引擎升级实验"的成果按本仓库源码重构的落地情况： | [原文](<reports/2026-10-17_Node24第三引擎与AutoJsPro示例集接入.md>)
- 2026-10-04 | 窗口截图与 ImGui 基础接入 | 根据用户“怎么添加 / 继续”落实窗口截图 API、象棋窗口通道与原生透明绘制。参考 Downloads 内的架构 MD；未把该文档中的性能目标当作实测结论。 | [原文](<reports/2026-10-04_窗口截图与ImGui基础接入.md>)
- 2026-10-04 | dev-786：ImGui 操作窗与字体闪退修复 ⚠️历史/被取代 | 新手机 Xiaomi 2602BRT18C / Android 16 / SDK 36 在 dev-785 的 ImGui 第一帧发生 SIGSEGV，fault address 0x48。 | [原文](<reports/2026-10-04_ImGui操作窗与字体闪退修复_dev786.md>)
- 2026-10-02 | 节点“更多”代码生成与停止按钮（dev-761） ⚠️历史/被取代 | 用户明确指出控件信息右上角的“更多”仍未优化，并要求对照 Auto.js Pro；随后指出悬浮球菜单中的“×”在背景上不清楚。 | [原文](<reports/2026-10-02_节点更多代码生成与停止按钮_dev761.md>)
- 2026-10-02 | 内置节点分析细节对齐（dev-760） | 继续核对参考视频，补齐内置分析窗细节并发布。用户进一步指出视频没有固定顶部工具栏，已按该反馈删除工具栏和层次树的顶部留白。范围图点选节点打开菜单，层次树长按节点打开菜单，返回键和菜单提供退出入口。 | [原文](<reports/2026-10-02_节点分析细节对齐_dev760.md>)
- 2026-10-02 | 内置节点分析悬浮窗（dev-759） | 用户确认修改的是应用自带的节点分析悬浮窗，按所附大柒悬浮菜单视频实现相近操作。参考 ZIP 为 C:\Users\18101\Downloads\悬浮菜单.zip，其中只有悬浮球菜单示例和按钮 1～4， | [原文](<reports/2026-10-02_内置节点分析悬浮窗_dev759.md>)
- 2026-10-02 | 微信布局分析对照与修复（dev-755） | 用户反馈悬浮窗分析微信节点时完全空白。本次已修复源码中的缓存、窗口选择、节点遍历及失败通知问题，并发布、安装 dev-755。 | [原文](<reports/2026-10-02_微信布局分析对照与修复_dev755.md>)
- 2026-10-02 | QuickJS 画布与佣兵项目兼容修复（dev-765） | 将 ZIP 解压到 .artifacts/user-project-mercenaries/hzybzj，在 K40（Android 16、1080×2400、440 DPI）运行测试副本。 | [原文](<reports/2026-10-02_QuickJS画布与佣兵项目兼容修复_dev765.md>)
- 2026-10-02 | Rhino 模块反引号兼容修复（dev-764） | 用户截图为 utils.js 内的 floaty.rawWindow()，布局使用多行反引号字符串，包含 button、canvas，以及箭头函数点击监听和 Canvas draw 回调。 | [原文](<reports/2026-10-02_Rhino模块反引号兼容修复_dev764.md>)
- 2026-09-28 | 悬浮窗重复堆叠：取证与修复 | 面板默认每 60 分钟用 engines.execScriptFile 启动一个新实例。旧实例只清自身心跳和部分定时器，假设运行时会回收脚本；它可能继续存活，旧窗口因此累积。退出钩子也漏掉独立日志窗。 | [原文](<reports/2026-09-28_悬浮窗重复堆叠修复_dev721.md>)
- 2026-09-27 | 2026-09-27 特级大师负局复盘：求胜模式硬排除保和着法 | 本次检查的是 K40（cccc62c7）上 20:42 开始、21:21:57 结束的一局。手机实际安装 dev-717，脚本日志版本 v0.6.37。结论来自手机运行日志、从手机取出的实际源码， | [原文](<reports/2026-09-27_特级大师负局_求胜硬排除复盘.md>)
- 2026-09-27 | 22:34 局：关闭求胜后，独立的循环禁手仍强制变劣 | 手机 dev-717、脚本 v0.6.37。本次取证中 winSeek=false，求胜确实已关闭；日志也没有求胜排除。这次第 33 回合，另一条独立的“疑似长将”逻辑将 c5c9 排除，红方被迫走 c5e5， | [原文](<reports/2026-09-27_2234局_关闭求胜后仍误禁长将.md>)
- 2026-09-27 | 悬浮窗文字与图片生命周期检查 | 项目版本：象棋自动走棋 0.6.34。日期：2026-09-27。App 更新包：dev-712。 | [原文](<reports/2026-09-27_悬浮窗文字与图片生命周期检查.md>)
- 2026-09-27 | dev-716 独立复核 | 复核对象：截图中的 QuickJS 升级、JNI 适配，以及远程算棋清理、prethink 装配、openingRecord.reset 护栏。 | [原文](<reports/2026-09-27_dev716_独立复核.md>)
- 2026-09-27 | dev-716 独立复核处置：定时器 UAF 修复 + 竞争策略补链 | 处置结论：复核的 P1/P2 全部属实，已修复并随 dev-717 发布装机；§4 的失败测试已修好并纳入发布闸门。 | [原文](<reports/2026-09-27_dev716复核处置_定时器UAF与竞争策略补链.md>)
- 2026-09-27 | QuickJS 引擎升级（quickjs-ng 2026-06-04 → 2026-09-24） | 新源来自 git@github.com:quickjs-ng/quickjs.git（HEAD 19dbe85，2026-09-24）。原版快照备份： | [原文](<reports/2026-09-27_QuickJS引擎升级_quickjs-ng-20260924.md>)
- 2026-09-25 | Pikafish 裸 FEN 静默猝死（2026-09-25） | 改动文件（仓库根起）： | [原文](<reports/2026-09-25_Pikafish裸FEN静默猝死_根因与修复.md>)
- 2026-09-23 | 平台 B1 前置：UI / 设置 / 打包器面完整调用图（2026-09-23） | 用途：B1「统一脚本事实（ScriptDescriptor / ScriptRepository）」的开工底稿。 平台报告 §7/§8 已覆盖脚本链与 MCP/inrt/定时任务三条链； | [原文](<reports/2026-09-23_平台B1前置_UI设置打包器调用图.md>)
- 2026-09-23 | AI.js Pro 平台重构 · P0 基线与入口概览（第一批） | 依据：docs/plans/AI.jsPro整体框架重构任务书_2026-09-23.md §7 的 P0。 本批只冻结事实（git/版本/模块/文档），不改业务行为；模块与入口的详细调用图留第二批。 核对时间： | [原文](<reports/2026-09-23_平台重构P0_基线与入口概览.md>)
- 2026-09-20 | 红黑判色改用引擎「找色」（2026-09-20，K40 真机） | 触发：用户在真机排查颜色判错时提出「新引擎不是有找色的案例吗」—— 引擎自带 images.findColor / findMultiColors / findAllPointsForColor（原生实现）， | [原文](<reports/2026-09-20_红黑判色改用找色.md>)
- 2026-09-15 | 第一代 Pro API 兼容现状 ⚠️历史/被取代 | 检查日期：2026-09-15。目标：用户提供的 335 页第一代 Rhino API PDF。 | [原文](<reports/2026-09-15_第一代API兼容现状.md>)
- 2026-09-15 | 手机端「AutoJs Pro 9.3.11 分析包」复核 | （11 篇 markdown，约 250 KB，已解到 .artifacts/autojs-pro-analysis/，该目录不入 git）。 | [原文](<reports/2026-09-15_手机端AutoJsPro分析包复核.md>)
- 2026-09-14 | 把「算棋」挪到独立引擎线程（worker）：真机量化与验收（2026-09-14） | 对象：apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋 | [原文](<reports/2026-09-14_算棋挪到worker线程.md>)
- 2026-09-12 | AI.js Pro 项目接手检查 | 检查日期：2026-09-12 仓库：C:\Users\18101\Documents\ai-js-pro 分支：chore/upgrade-gradle-8 检查提交： | [原文](<reports/2026-09-12_项目接手检查.md>)
- 2026-09-12 | UI 分辨率/大字体/横屏 适配记录 | 交付产物：.artifacts/ui-fit/AIJSPro-ui-fit-538-arm64-v8a-debug.apk（versionCode 538，versionName dev-538-uifit， | [原文](<reports/2026-09-12_UI适配_大字体横屏侧边栏.md>)

#### 4.4.3 逆向 / APK / ELF（33 篇）

- 2026-11-06 | 多任务收口：第 4 项（apksig V1+V2+V3）+ 对齐端到端 + 工作区回收（dev-1365） | 用户要求：开工、能补就补，测试机默认 K40、MI8 退役。本轮多任务并行：队友 A 做 apksig 签名切换、队友 B 做工作区回收，Lead 做对齐端到端与全部接线/构建/真机验证。一轮构建收口三件事。 | [原文](<reports/2026-11-06_多任务收口_apksigV3与对齐与工作区回收（dev-1365）.md>)
- 2026-11-04 | MCP 逆向工具与自发现指南 | 新增 18 个规范工具，公开目录为 160 个规范名 + 9 个兼容别名 = 169；文件/APK 工具类共 99 项，其中逆向分类 18 项。原接口和兼容别名保留。 | [原文](<reports/2026-11-04_MCP逆向工具与自发现指南.md>)
- 2026-11-04 | MCP 逆向能力优化第二批（Manifest 兜底 / 应用查询 / 任务保留） | 本批覆盖复盘 P0 第一阶段的 §4.1、§5.3、§6.1；GPT（另一路会话）已停止，本轮由 Lead 完成实现、编译、真机验收与发布。 | [原文](<reports/2026-11-04_MCP逆向能力优化第二批.md>)
- 2026-11-04 | MCP 逆向能力优化第三阶段（P1/P2 全部落地） | 本阶段把 GPT 复盘里剩余的 P1（§7–§10）与 P2（§11–§12）全部落地；四个工作流由并行任务完成，Lead 负责接线、编译、真机验证与发布。 | [原文](<reports/2026-11-04_MCP逆向能力优化第三阶段（P1P2）.md>)
- 2026-11-04 | MCP 逆向能力优化第三批（Node 运行形态 / artifact_open 结构发现） | 本批覆盖 §6.3（嵌入式 Node 运行形态）与 §5.2 收尾（非标准扩展名的打开路径）。 | [原文](<reports/2026-11-04_MCP逆向能力优化第三批.md>)
- 2026-11-04 | MCP 逆向能力优化第一批（工作区 / 文件发现 / 验签分层） | 本批只做复盘里的 P0 第一阶段中的 3 组可靠性项；P1/P2 见文末「未做」。 | [原文](<reports/2026-11-04_MCP逆向能力优化第一批.md>)
- 2026-11-03 | APK 编译与逆向 MCP 增强评估 | 本轮为源码核查、只读 MCP 工具清单读取、电脑 ZIP 对齐夹具验证及上游文档核对；未改产品源码，未安装新组件，未操作手机界面或第三方 APK，未发布新版本。下列新增工具名全部为建议，不是已经可调用的接口。 | [原文](<reports/2026-11-03_APK编译逆向MCP增强评估.md>)
- 2026-11-03 | MCP 编译/逆向增强与 Node 资源恢复 | 按用户“先修改完，再统一测试”的要求实施。DP 两项任务均超时，留下代码草稿；由主助手接管审查、修正并验收，不能记作 DP 已完成。 | [原文](<reports/2026-11-03_MCP编译逆向增强与Node资源恢复.md>)
- 2026-10-29 | Node 原生与独立 APK 兼容 | K40（主宿主 dev-1273）用自身打包能力构造了独立 Node 测试项目：首次启动红字 Unsupported JavaScript engine: nodejs。 | [原文](<reports/2026-10-29_Node原生与独立APK兼容.md>)
- 2026-10-27 | 节点分析悬浮窗_AJPro高级版逆向对照 | # 节点分析悬浮窗：AJ Pro 高级版与当前实现对照 | [原文](<reports/2026-10-27_节点分析悬浮窗_AJPro高级版逆向对照.md>)
- 2026-10-26 | APK 卡顿定位与优化（2026-10-26） | 用户要求检查四类场景：启动/进入页面、滑动/切页、编辑器/日志/API 文档、脚本运行后的整体响应。本轮在 小米 8 透明探索版 ce4d2bdb，Android 15 / API 35 测试；没有操作 K40。 | [原文](<reports/2026-10-26_APK卡顿定位与优化.md>)
- 2026-10-26 | dev-1260 发布：APK 卡顿优化 | 目录扫描、文件 stat/排序、历史日志读取移到独立工作线程，主线程不再被目录轮询阻塞。 文件页首次/切页只读一次；PageView 停稳后才扫描和轮询；自动刷新 2s，滚动/后台/抽屉期间暂停。 | [原文](<reports/2026-10-26_dev1260发布.md>)
- 2026-10-25 | 独立 APK 音量上键停止链路（inrt / DP） | 独立 APK 里音量上键停不掉脚本悬浮窗，根因不是单个开关，而是三条链路叠在一起： | [原文](<reports/2026-10-25_独立APK停止链路_DP.md>)
- 2026-10-24 | DP 任务 3+4：Flutter 正式 release 接入、调试广播收紧与 APK 体积审计 | 此为 DP 首次交付记录；主助手的后续修正、实际构建和最终 MI8 验收以五项优化任务与验收为准。 | [原文](<reports/2026-10-24_DP任务34_瘦身与正式构建.md>)
- 2026-10-22 | 无障碍截图宿主直传像素（Phase A Raw 文件 + Phase B ParcelFileDescriptor 管道） | 规格：C:\Users\18101\Downloads\xiangqi-draw\xiangqi-draw\docs\无障碍截图-宿主直传像素-20261022.md | [原文](<reports/2026-10-22_无障碍截图宿主直传像素.md>)
- 2026-10-22 | APK 宿主开关与按钮颜色统一 | 发布包 151,730,558 字节（144.70 MiB）；SHA-256 bceab5c6a793852a1ee60e5d64749f83a5023eb41aee496a4ff6366df9af6c25。 | [原文](<reports/2026-10-22_APK开关与按钮颜色统一.md>)
- 2026-10-20 | 2026-10-20 高级版脚本「结束」日志对照 RE（Pro 9.3.11-0 vs 我们 dev-1133） | 任务：task-187。用户想知道高级版 AutoJs Pro 脚本结束时控制台/日志是什么样，和我们「脚本运行失败 / 脚本已停止」等输出对照。 样本： | [原文](<reports/2026-10-20_高级版脚本结束日志对照_RE.md>)
- 2026-10-19 | 2026-10-19 高级版三引擎运行架构 RE（auma-base.apk = com.boyai.auma 9.3.11-0） | 任务：task-173。用户反馈我方「脚本宿主」跑脚本有问题，需要对照手机已有 AutoJs 高级版（com.boyai.auma，Pro 9.3.11-0 / versionCode 9131100， | [原文](<reports/2026-10-19_高级版三引擎运行架构_RE.md>)
- 2026-10-19 | inrt 定时任务补全（task-162，2026-10-19） | 在 task-160 基础上补齐 Lead 指出的 4 个限制，K40 用 inrt debug APK（= 打包运行时本体） 验证： 广播 Intent 任务： | [原文](<reports/2026-10-19_inrt定时任务补全.md>)
- 2026-10-18 | 2026-10-18 Phase1：Pro 的 :script 进程 + AIDL 架构 spec（Phase1-A） | 证据来源（只读）： - Pro 9.3.11 反编译：.artifacts/pro-official/（classes*.dex + jadx 源码； | [原文](<reports/2026-10-18_Phase1_Pro脚本进程与AIDL架构spec.md>)
- 2026-10-18 | 反编译 Pro 打包实现（ProjectBuilder / ApkBuilder） | 目标 APK：C:\Users\18101\Documents\ai-js-pro\output\auma-base.apk（Auto.js Pro 高级版，包名 com.boyai.auma， | [原文](<reports/2026-10-18_反编译Pro_打包实现.md>)
- 2026-10-18 | 反编译 Pro 的 Node.js 引擎：Pro 提供 vs 标准 Node vs 我们 | 任务：task-131「Node API 对齐（二）反编译 Pro 的 nodejs 引擎：模块/API/桥暴露清单」。 输入样本： | [原文](<reports/2026-10-18_反编译Pro_NodeAPI.md>)
- 2026-10-18 | 反编译 Pro：电脑连接（DevPluginServer / ComputerPlugin / 远程调试）实现对照 | 手段：jadx 1.5.6 + baksmali 3.0.9 + DEX 字符串表/类表自解析 + libapp.so（Flutter AOT）字符串扫描 产物（不入库，均在 .artifacts/ 下）： | [原文](<reports/2026-10-18_反编译Pro_电脑连接实现.md>)
- 2026-10-18 | 反编译 Pro：MCP 服务实现对照（Pro 9.3.11-0 → AI.js Pro POC） | APK SHA-256：E0010EAFDD7AB8BD204E67FFDCA3EC1AC7C1C0A4180CA42311FE8EBE2C0FB31A 证据设备： | [原文](<reports/2026-10-18_反编译Pro_MCP实现.md>)
- 2026-10-17 | 2026-10-17 ImGui 桥 native 上下文定位（选项 1：静态逆向） | 材料：output/imgui-probe/autojsproimgui/{main.js,src/js/imgui.js,lib/arm64-v8a/libImGuiBridge.so,lib/ImGuiBridg… | [原文](<reports/2026-10-17_ImGui桥native上下文定位.md>)
- 2026-10-11 | 反编译官方 Auto.js Pro（9.3.11-0）—— 界面权威规格 | 官方 Auto.js Pro 是"原生 View + Flutter"混合应用，不是纯 Flutter。 | [原文](<reports/2026-10-11_反编译官方AutoJsPro界面规格.md>)
- 2026-10-04 | GitHub 加速更新内置到 APK（dev-783） | GitHubUpdateProxy 内置 GH-Proxy 更新清单地址；更新源留空即可使用。以前手动配置的同一个代理源也按默认策略处理。 默认清单每次请求增加时间参数，禁用本地响应缓存。 | [原文](<reports/2026-10-04_GitHub加速内置_dev783.md>)
- 2026-10-02 | 微信空节点诊断与兼容服务 — dev-758 | 用户在 Xiaomi 2602BRT18C、Android 16 / API 36、dev-757 上提供了 2026-10-02 01:02:41 的诊断日志，并确认 Auto.js Pro 在同一页面能读取。 | [原文](<reports/2026-10-02_微信空节点诊断与兼容服务_dev758.md>)
- 2026-10-01 | AutoJs Pro 逆向与图色 API 对照（2026-10-01） | 本报告汇总 2026-09-30 ~ 10-01 的 Auto.js Pro 逆向、真机实测与四引擎兼容矩阵， 并落地了配套工具（Pro 自动跑脚本）与发布门禁（图色 API 样本）。 | [原文](<reports/2026-10-01_AutoJsPro逆向与图色API对照.md>)
- 2026-09-30 | 全量 API 签名级对照（Pro 9.3.11 高级版 vs 自研引擎，2026-09-30） | 上一轮（2026-10-01_AutoJsPro逆向与图色API对照.md）做到「Java 类表面 + 三引擎探针」； 本轮把粒度推到每个 public 成员的完整签名， | [原文](<reports/2026-09-30_全量API签名级对照.md>)
- 2026-09-29 | 打包产物里还看得到 js 文件：目录工程脚本收进加密容器 | 触发：用户在打包产物（象棋助手 APK）里发现除入口外的上百个 .js 文件全部可读。 | [原文](<reports/2026-09-29_目录工程脚本收进加密容器.md>)
- 2026-09-15 | 手机端分析包 vs 本仓库：模块 / API 对照 | （MT 反编译 + MiMo AI，11 篇 md，对象是第三方魔改包 AutoJs五云Ai_Pro 9.3.11-0.apk）。 | [原文](<reports/2026-09-15_手机端分析包_模块与API对照.md>)
- 2026-09-12 | dsh 手机客户端（QuickJS 独立 APK）验证记录 | 承接上一轮（停在「已装到 Mi 8、下一步验扫码/网页加载/重启状态」）与 .artifacts/dsh-link-qr.html 的永久入口。 本轮只做真机验证与定位，未改任何示例源码、未提交、未发布。 证据目录： | [原文](<reports/2026-09-12_dsh手机客户端_独立APK验证.md>)

#### 4.4.4 Flutter / 界面 / 编辑器（40 篇）

- 2026-11-02 | 编程器打开文件闪屏修复（dev-1310 候选） | 用户反馈从文件列表打开编程器会闪一下。在小米 8（ce4d2bdb、Android 35）已安装的 dev-1309 上实际录屏复现：编辑器工具栏和代码上半部分已经呈深色，下半部分短暂露出白块。 | [原文](<reports/2026-11-02_编程器打开文件闪屏修复.md>)
- 2026-11-02 | 文件、示例与项目界面美化 | dev-1309 已装小米 8 验收，Downloads 和 LAN 提供手动测试包。自动更新源仍为 dev-1305；本地提交，不 push、不创建 GitHub APK 发布，K40 未动。 | [原文](<reports/2026-11-02_文件与项目界面美化.md>)
- 2026-10-29 | 高级版文档全量审阅与当前架构对照 | 总体分层和若干关键技术已经接近，但当前实现与这些文档、现有高级版 APK 都不是完全相同的架构。 原生 Material 3、Glide、Smali→Dex、方法 ID 缓存和类型化 JNI 快通道已经有实现； | [原文](<reports/2026-10-29_高级版文档全量审阅与架构对照.md>)
- 2026-10-26 | 启动主题统一与文档复制优化（dev-1256） | 原生启动页、Flutter window、品牌页、主页和文档统一使用深色 #1C2022 / 浅色 #F7F7F8，状态栏和导航栏同步配色与图标明暗。 | [原文](<reports/2026-10-26_启动主题统一与文档复制优化.md>)
- 2026-10-26 | API 文档语法高亮、主题同步与结构化详解 | 用户要求文档代码有高亮、美观易读，补充 API 细节供人和 MCP 共用，并跟随 App 主题。最终版本 dev-1259；最终包验收与发布状态见下方。 | [原文](<reports/2026-10-26_API文档高亮与结构化详解.md>)
- 2026-10-26 | API 文档直接打开与 dev-1255 | 主界面顶部原「本地使用指南」图标改名为「API 文档」，点击直接通过本地桥打开内置 api-reference.html，不再 push Flutter 的 GuidePage。 初次打开后按一次返回即可回到主界面； | [原文](<reports/2026-10-26_API文档直接打开与dev1255.md>)
- 2026-10-25 | 编辑器剪贴板/长按选择链路修复（DP） | 任务：AI.js Pro 编程器复制/剪切/粘贴与长按选择菜单 | [原文](<reports/2026-10-25_编辑器剪贴板修复_DP.md>)
- 2026-10-25 | 编辑器剪贴板与全进程停止修复 | 悬浮窗口属于创建它的引擎所在进程，不是所有窗口都在主进程。主程序的“停止全部”控制 AI.js Pro 本包的脚本进程；独立 APK 有自己的包名 / UID 和停止链路。 | [原文](<reports/2026-10-25_编辑器剪贴板与全进程停止修复.md>)
- 2026-10-24 | 大文件分享后台化与复核 | 用户确认：从 AI.js Pro 分享到其他应用，内容约 80 多 MB。尚未明确是单文件还是文件夹，也未明确是在接收应用选择前或选择后退出。 | [原文](<reports/2026-10-24_大文件分享后台化与复核.md>)
- 2026-10-22 | 选择器运行后打开编辑器卡顿 | 用户视频：运行 Node.js / 无障碍 / 选择器.js，返回列表后打开编辑器，工具栏出现，代码区长时间空白；重启后恢复。 | [原文](<reports/2026-10-22_选择器运行后编辑器卡顿修复.md>)
- 2026-10-22 | Flutter 升级与 16 KB 兼容修复 | Flutter 3.3.4 → 3.47.7 stable，Dart 2.18.2 → 3.13.5。 Framework revision： | [原文](<reports/2026-10-22_Flutter升级与16KB兼容修复.md>)
- 2026-10-20 | 存储权限申请修复：运行时授权替代「所有文件访问」死胡同（dev-1169） | 现象（用户反馈）：新安装后，首启「完善首次使用权限」里存储权限一直显示未开启；点「去开启」跳到系统「所有文件访问权限」页面，但对该应用无法授予（切换无效/没有可用入口），首页显示「无法读取目录：脚本」。 | [原文](<reports/2026-10-20_存储权限申请修复.md>)
- 2026-10-20 | 关于页对齐高级版：细节对比（dev-1161） | 参照：Auto.js Pro 高级版 9.3.11-0（com.boyai.auma，深色）设置 → 关于 → 关于项目与开发者 实现： | [原文](<reports/2026-10-20_关于页对齐高级版_细节对比.md>)
- 2026-10-20 | 日志对齐 Pro（task-191） | Pro 9.3.11-0 行为： 正常结束：TRACE 结束行 \n------------\n［路径］运行结束，用时1.336000秒（6 位小数），无 Toast/ERROR； 异常：先结束行， | [原文](<reports/2026-10-20_日志对齐Pro.md>)
- 2026-10-19 | 2026-10-19 日志页与 Pro 对照及改造建议（task-24） | Pro 反编译：.artifacts/pro-official/src/sources/com/stardust/autojs/core/console/{FileConsoleView,FileConsoleVie… | [原文](<reports/2026-10-19_日志页与Pro对照及改造建议.md>)
- 2026-10-19 | 去掉 Miuix，统一 Flutter 界面（task-177 / dev-1128） | 用户决定：删掉 Miuix，只留 Flutter 界面。本报告记录 dev 通道从「Flutter 主入口 + Miuix 回退」 收敛为 Flutter-only 的全部改动、页面功能对等矩阵、缺口、验证与回退方式… | [原文](<reports/2026-10-19_去Miuix统一Flutter.md>)
- 2026-10-18 | 16 KB 页对齐升级评估（Flutter / OpenCV / ML Kit / androidx） | 评估对象：apps/app（Miuix + Flutter 玻璃 POC）、modules/engine（QuickJS / PP-OCR / OpenCV / ML Kit 原生） 结论一句话： | [原文](<reports/2026-10-18_16KB对齐升级评估.md>)
- 2026-10-18 | AI.js Pro（Flutter Glass POC）交接文档 v2 — 给 Codex ⚠️历史/被取代 | 仓库：C:\Users\18101\Documents\ai-js-pro Flutter 外置源（AOT 从这拷 lib）： | [原文](<reports/2026-10-18_收尾交接_给Codex_v2.md>)
- 2026-10-18 | AI.js Pro（Flutter Glass POC）收尾交接 — 给 Codex | 仓库：C:\Users\18101\Documents\ai-js-pro Flutter 外置源（AOT 从这拷 lib）： | [原文](<reports/2026-10-18_收尾交接_给Codex.md>)
- 2026-10-17 | 玻璃打包页接回：ProjectPage + PocPackaging 桥（dev-930 / dev-931） | 依据：工程/output/ai-js-pro-refactor-20261017/打包界面代码定位-20261017.md 第 7 节「把玻璃打包页接回来的步骤」。 | [原文](<reports/2026-10-17_玻璃打包页接回ProjectPage.md>)
- 2026-10-17 | Flutter 玻璃界面 POC 验收（2026-10-17） | 依据：工程\output\ai-js-pro-refactor-20261017\统一Flutter界面-可行性评估-20261017.md §9 基座： | [原文](<reports/2026-10-17_Flutter玻璃界面POC验收.md>)
- 2026-10-17 | Phase 0：「:native」隔离脚本进程 MVP（dev-942/943/944） | 主进程 com.jdkshen.aijspro :native 进程 com.jdkshen.aijspro:native Flutter 玻璃界面 / 文件页 ──Messenger──▶ NativeScript… | [原文](<reports/2026-10-17_Phase0_native隔离进程MVP.md>)
- 2026-10-12 | 标签关闭按钮与工作区主题修复（2026-10-12） | 当前交付：AI.js Pro dev-897、AJ v165。用户反馈标签关闭 × 消失、工作区颜色主题未对齐。本次恢复两版的直接关闭入口，并修复 AJ 原生工作区抽屉的主题遗漏。 | [原文](<reports/2026-10-12_标签关闭按钮与工作区主题修复.md>)
- 2026-10-12 | 两个版本接入配对括号高亮（2026-10-12） | 用户提供 C:/Users/18101/Downloads/bracketHighlight.js，要求接入 AI、AJ 两个版本。下载目录原文件保留；两个工程使用同一份适配脚本， | [原文](<reports/2026-10-12_双版本括号高亮接入.md>)
- 2026-10-12 | 双版本编辑器对齐原包工作区布局 | 对照包为本机保存的 autoJsPro.apk，org.autojs.autojspro，Pro9.3.11-0，启动名称“AutoJs五云i”。它已与两版并存安装。没有核实这是发行方未经修改的官方包。 | [原文](<reports/2026-10-12_双版本原包工作区布局对齐.md>)
- 2026-10-12 | 双版本工作区配色与 QQ 群入口处理（2026-10-12） | 当前交付为 AI dev-899 与 AJ v167。两版工作区采用相同的深浅配色；AJ 编辑器的加 QQ 群按钮与点击跳转已移除，原位置留空，方向键、TAB、ESC、fx 的布局保留。 | [原文](<reports/2026-10-12_双版本工作区配色与QQ群入口处理.md>)
- 2026-10-12 | Ace 编辑器功能对齐检查（dev-888） | 检查日期：2026-10-12。检查范围为 AI.js Pro 新编辑器与原 Auto.js Pro 编辑器的功能对齐；不包含整个应用、脚本引擎或授权系统的全面兼容性评估。本轮只新增检查报告和探针证据， | [原文](<reports/2026-10-12_Ace功能对齐检查.md>)
- 2026-10-12 | Ace 升级与 AJ 编辑器功能对齐（AI dev-891 / AJ v160） | 官方 Ace 最新发布页 经当天核对为 v1.44.0。资源来自 ace-builds 1.44.0 npm 元数据，下载 tarball 的 SHA-512 与 registry integrity 相符。 | [原文](<reports/2026-10-12_Ace升级与双版本功能对齐.md>)
- 2026-10-12 | fx 函数面板配色与微信公众号入口处理（2026-10-12） | AJ 原生 fx 函数面板曾在浅色编辑器下保留深色背景。本次删除函数索引中的“微信公众号”按钮，补齐面板背景、函数文字和分类选中／未选中颜色，并跟随应用明暗主题。 | [原文](<reports/2026-10-12_fx函数面板主题与微信公众号入口处理.md>)
- 2026-10-11 | Ace 编辑器源码移植与双指缩放修复（2026-10-11） ⚠️历史/被取代 | 后续 dev-888 已发布：共享编辑页面，改善大文件标签切换、普通点击与键盘唤起；K40 与小米 8 各 20 项真机测试通过。最新结果见 标签切换与键盘修复。以下保留首次移植与 dev-883 的历史记录。 | [原文](<reports/2026-10-11_Ace编辑器移植与缩放修复.md>)
- 2026-10-10 | AI.js Pro Miuix 界面逐页问题清单（2026-10-10） | 不靠品味判断 —— 只列能用代码/度量证明的不一致与缺失。 | [原文](<reports/2026-10-10_Miuix界面逐页问题清单.md>)
- 2026-10-10 | Flutter v34 与 AI.js Pro 界面核对（位置 / 比例 / 显示） ⚠️历史/被取代 | 核对日期：2026-10-10 依据：两侧源码数值 + 文件:行号 - Flutter： | [原文](<reports/2026-10-10_Flutter_v34与AI.jsPro界面数值核对.md>)
- 2026-09-29 | 打包加密后「界面固定、返回不了桌面」：根因与修复验证 | 打包（加密/快照档）之后打开 App：工作台界面固定在屏幕上；返回键无效（返回不了桌面）；按 Home 回桌面后，这块界面仍盖在最上面，整台手机像被"钉"住。 | [原文](<reports/2026-09-29_打包加密后界面固定_根因与验证.md>)
- 2026-09-29 | 自研 AI.js Pro VS Code 扩展（连接/运行/日志/推送/文件浏览） | 「连接 → 运行文件/工程 → 实时日志 → 停止 → 保存推送 → 设备文件浏览」。 | [原文](<reports/2026-09-29_VSCode自研扩展.md>)
- 2026-09-27 | API 清单复核与导入、打开界面统一 | 本次接收的是用户整理的 smali、403 个示例和 @autojs/types-pro9 对照结论，没有重新运行这 403 个官方示例，也没有据此宣布两套引擎完全兼容。本次 UI 修改不更换引擎、不修改象棋算法。 | [原文](<reports/2026-09-27_API清单复核与导入打开界面统一.md>)
- 2026-09-14 | 顶部图标液态玻璃导航 | 根据用户提供的酷安视频，为主界面顶部五个图标实现类似底部导航栏的玻璃选中胶囊。 | [原文](<reports/2026-09-14_顶部图标液态玻璃导航.md>)
- 2026-09-14 | 切页浮动按钮空指针修复 | 用户堆栈：ViewPagerFragment.onPageShow → MiuixSampleFragment.onPageShow → MainActivity.onPageSelected， | [原文](<reports/2026-09-14_切页浮动按钮空指针修复.md>)
- 2026-09-14 | 示例、商店切换与编辑器日志修复 | 主界面 ViewPager 保留全部五个页面，提前创建内容，避免跨页切换时创建远处页面；同时保留已加载数据和列表位置。 示例浏览器按目录保留 LazyListState，返回父目录恢复原位置； | [原文](<reports/2026-09-14_示例商店切换与编辑器日志修复.md>)
- 2026-09-14 | 主题切换提速与系统跟随统一 | 侧栏手动主题触发 AppCompat 全局 uiMode 变更，主界面未声明处理 uiMode，会重建 Activity 和五个预加载页面。侧栏“跟随系统”原来只更新 Compose 状态； | [原文](<reports/2026-09-14_主题切换提速与系统跟随统一.md>)
- 2026-09-12 | 文件管理修复记录 | 基线：2a3968f，分支 chore/upgrade-gradle-8。本次是工作区代码修改，未自动提交或推送；修复验证后按用户要求发布了 dev-537 到局域网更新源，未通过 ADB 安装。 | [原文](<reports/2026-09-12_文件管理修复.md>)

#### 4.4.5 自动化 / 图像 / OCR / 无障碍（33 篇）

- 2026-11-06 | 内置「谷歌 OCR」示例报错修复 + 测试机切 K40（dev-1360 → dev-1362） | 用户报：「谷歌 OCR 示例报错了」，并要求测试机默认改用 K40（不再用 MI8）。 | [原文](<reports/2026-11-06_谷歌OCR示例报错修复与K40测试机（dev-1362）.md>)
- 2026-11-02 | 新手机 Shizuku 已授权却无法启动无障碍（dev-1311 候选） | 用户新手机：型号 2602BRT18C，Android 17 / SDK 37，系统 OS4.0.3.0.XPLCNXM；MCP 192.168.10.4:8765。初始应用 dev-1309， | [原文](<reports/2026-11-02_新手机Shizuku启动无障碍修复.md>)
- 2026-10-24 | dev-1240 截图核对与优化建议 | 当前更新源：dev-1240 / code 1240。 APK：AI-js-Pro-Flutter-dev-1240.apk，154,868,699 字节（147.69 MiB）。 SHA-256： | [原文](<reports/2026-10-24_dev1240截图核对与优化建议.md>)
- 2026-10-22 | 无障碍截图 maxWidth 优化：透传 + 缩略回读 + 分段打点 | 规格：C:\Users\18101\Downloads\xiangqi-draw\xiangqi-draw\docs\无障碍截图-宿主maxWidth-优化空间-20261022.md | [原文](<reports/2026-10-22_无障碍截图maxWidth优化.md>)
- 2026-10-20 | 截图授权修复：强制整屏 + 自动授权跨进程（dev-1164 → dev-1167） | 现象（用户反馈）：自动化获取截图权限时，系统弹窗总是选「共享一个应用」；切到别的 App 后截图变黑/报错（单应用模式 MediaProjection 只投被选应用，切后台即黑帧）。 影响范围： | [原文](<reports/2026-10-20_截图授权整屏修复.md>)
- 2026-10-19 | #34 截图授权人工验证记录（dev-1126） | am broadcast ... assetPath 'sample/…/特征匹配(全分辨率找图).js' 日志：GlassFlutterPoc: 脚本使用本地截图能力 ［requestScreenCapture］， | [原文](<reports/2026-10-19_34截图授权验证dev1126.md>)
- 2026-10-19 | 2026-10-19 自动化 API 使用面盘点 + :script 代理拦截点 spec（Phase1 P1-A） | 契约（已定稿，不再改）：apps/app/src/main/aidl/com/stardust/autojs/host/IAutomationHost.aidl（17 个方法）、docs/reports/2026-1… | [原文](<reports/2026-10-19_自动化API使用面与代理拦截点.md>)
- 2026-10-19 | 远程运行 require 验证（task-150，2026-10-19） | 协议帧构造成功：PC 侧 adb forward tcp:9317 后，用正确 hello + command 帧模拟 VS Code 扩展，远程 run 成功触发脚本并收到 response/log 帧。 | [原文](<reports/2026-10-19_远程运行require验证.md>)
- 2026-10-19 | Phase 1 P1：自动化代理（IAutomationHost）实现记录（dev-957，待真机验收） | flutterpoc AndroidManifest 注册（主进程、exported=false）。 | [原文](<reports/2026-10-19_Phase1P1_自动化代理实现记录.md>)
- 2026-10-18 | 图色示例修复与 dev-1049 验收 | APK：.artifacts/updates/app-miuix-compat-arm64-v8a-dev-1049-no-usb-debug.apk 大小：181,496,201 字节 SHA-256： | [原文](<reports/2026-10-18_图色示例修复与dev1049验收.md>)
- 2026-10-18 | DP 交接 dev-1073 复核与 YOLO 三通道测试 | 已读取 2026-10-18 19:20 的《收尾交接给Codexv2》。K40 cccc62c7 与更新源均为 dev-1073。 | [原文](<reports/2026-10-18_DP交接1073复核与YOLO三通道.md>)
- 2026-10-18 | OpenCV原生库strip减重 | docs/guides/SIFT特征匹配示例.md 里的 AAR 重打包命令会从官方 SDK 重新装入未 strip 的原生库； 下次重生成后需要重跑本文第 2 节的 strip + 校验， | [原文](<reports/2026-10-18_OpenCV原生库strip减重.md>)
- 2026-10-05 | 新手机车炮往返与盘面独立核对 | 设备为 Xiaomi 2602BRT18C（Android 16），宿主 dev-801，象棋工程 0.6.70。 | [原文](<reports/2026-10-05_新手机车炮往返与盘面独立核对_dev802.md>)
- 2026-10-05 | K40 dev-800 完整对局测试 | 11:59 开始，12:20:34 完成最后一手 b6d7，12:20:41 识别结算页后自动停止。实际对局约 21 分 30 秒。 | [原文](<reports/2026-10-05_K40_dev800完整对局测试.md>)
- 2026-10-03 | 自定义触摸与无障碍点击 API（dev-780） | 两个接口均支持 Rhino、QuickJS。控件可传 Android View、UI 控件代理、悬浮窗控件或当前 UI 的 id 字符串。 | [原文](<reports/2026-10-03_自定义触摸与无障碍点击API_dev780.md>)
- 2026-10-02 | 共享截图与连续触控 API（dev-763） | 把用户提供的 引擎共享截图权限.zip、touch.zip 整理为内置 API，并添加到应用示例。ZIP 保留原样，解压和反编译检查产物在本机 .artifacts/user-api-packages/。 | [原文](<reports/2026-10-02_共享截图与连续触控API_dev763.md>)
- 2026-10-02 | SIFT 定位 + 格点字形/颜色识别：修改与真机复测 | 在 Redmi K40（Android 16 / SDK 36）上，QuickJS、Rhino 均完成同一组测试：9 张原始象棋截图、2 张变换图片、1 张无棋盘图片，以及 3 次重复帧识别。 | [原文](<reports/2026-10-02_SIFT定位与格点识别实测.md>)
- 2026-10-02 | SIFT 缓存与渐进搜索（dev-771） | 用户要求参考象棋引擎减少计算量。检查了仓库 third-party/Pikafish/src/thread.h、engine.h、nnue/simd.h、misc.h 和 Android CMake： | [原文](<reports/2026-10-02_SIFT缓存与渐进搜索_dev771.md>)
- 2026-10-02 | SIFT 模板匹配适配 · dev-766 | 输入为用户提供的 C:/Users/18101/Downloads/特征匹配--SIFT模板匹配法.rar，原包未修改。 | [原文](<reports/2026-10-02_SIFT模板匹配适配_dev766.md>)
- 2026-10-02 | SIFT 内置 API 与速度优化（dev-768） | 用户希望真实 SIFT 示例减少代码，并提高重复匹配速度。新增两个双引擎 API： | [原文](<reports/2026-10-02_SIFT内置API与速度优化_dev768.md>)
- 2026-10-02 | SIFT 示例耗时日志（dev-769） | 用户指出 dev-768 示例没有明确显示耗时。此前结果 JSON 的 elapsedMs 是后端场景匹配耗时，单次调用不包含模板准备，也缺少便于查看的独立日志。 | [原文](<reports/2026-10-02_SIFT示例耗时日志_dev769.md>)
- 2026-10-02 | SIFT C++ 后端与原生帧直连（dev-770） | 用户要求继续提速并下沉 C++。OpenCV 的 SIFT/FLANN 算法此前已经是 C++，dev-768/769 的 Java 后端负责组织调用和处理特征点。本版新增 sift_matcher.cpp， | [原文](<reports/2026-10-02_SIFT_Cpp后端与原生帧直连_dev770.md>)
- 2026-10-01 | 无障碍指纹手势 — dev-753 | 无障碍服务声明 canRequestFingerprintGestures 和 USE_FINGERPRINT 普通权限。 新增 FingerprintGestureDispatcher 共享订阅； | [原文](<reports/2026-10-01_无障碍指纹手势_dev753.md>)
- 2026-09-27 | dev-719：31 回合双方不变作和 | 23:40 结算截图确认「人机对战，双方不变着作和，31 回合」。手机运行 dev-719 / 脚本 0.6.39，源码指纹与发布版本一致。已从日志合法重建 61 个半回合，终局与截图一致。 | [原文](<reports/2026-09-27_dev719_31回合和棋复盘.md>)
- 2026-09-20 | 场景回归框架：把一次真机故障固化成一条场景（2026-09-20） | 起因：用户「重新弄这个框架吧」。之前所有离线测试都是 tools/self-check.js 那种 一个模块一个断言 的写法， | [原文](<reports/2026-09-20_场景回归框架.md>)
- 2026-09-20 | 方案对照：实现现状与验收缺口（2026-09-20） | 对照对象：docs/plans/象棋YOLOOCR_红黑分离视觉架构重构方案.md（下称「方案」） 代码基线： | [原文](<reports/2026-09-20_方案对照_实现现状与验收缺口.md>)
- 2026-09-20 | 全盘 OCR 补正的「写基准前闸门」 + 几何体检（2026-09-20，K40） | 上一轮真机的拦路石不是颜色，而是可靠基准被单帧 OCR 读数改漂： 基准里出现重复黑炮 (4,4)+(4,5)、红帅 (4,9) 丢失、(7,7) 变 N， 于是确认阶段反复 UNRELIABLE： | [原文](<reports/2026-09-20_OCR补正闸门与基准防漂.md>)
- 2026-09-20 | 视觉流水线：性能与准确率（方案 §12 交付物 #9） | 数据全部来自 K40 真机产物（由 .artifacts/make-vision-report.py 自动汇总，无手填数字）。 生成时间：2026-09-20 08:11 | [原文](<reports/2026-09-20_视觉流水线_性能与准确率.md>)
- 2026-09-20 | OCR 前先处理一下图？—— 真机实测：只用「灰度当重试」 | 探针：apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋/tools/ocr-prep-probe.js （报告与裁剪图落 /sdcard/xiangqi… | [原文](<reports/2026-09-20_OCR预处理实测_灰度重试.md>)
- 2026-09-14 | 截图自动切换与授权弹窗 | requestScreenCapture({mode: 'auto'})。Android 11+，需用户开启 AI.js Pro 无障碍服务。 | [原文](<reports/2026-09-14_截图自动切换与授权弹窗.md>)
- 2026-09-13 | 单应用截图兼容修复与小米 8 排查 | ScreenCaptureRequester 之前在 API 34+ 使用 createConfigForDefaultDisplay()，主动取消了单应用选择。 | [原文](<reports/2026-09-13_单应用截图兼容修复.md>)
- 2026-09-13 | 录屏兼容截图性能优化 | 无障碍回调只转交 HardwareBuffer 所有权，不在回调线程复制整屏 Bitmap。 QuickJS 原生层通过官方 AHardwareBuffer API 直接锁定 RGBA 像素， | [原文](<reports/2026-09-13_录屏兼容截图性能优化.md>)
- 2026-09-13 | 系统录屏并用：独立截图 API | 小米 8（ce4d2bdb，Android 15）日志 23:18:01 明确记录：Stopped MediaProjection to start new incoming projection。 | [原文](<reports/2026-09-13_系统录屏并用截图API.md>)

#### 4.4.6 示例 / 插件 / 业务项目（75 篇）

- 2026-11-05 | 《华仔部落_佣兵战纪》脚本报错定位与修复（dev-1346/1347） | 复现方式：解压后推到 MI8 /storage/emulated/0/脚本/hzybzj，用 MCP runscript {path:"hz_ybzj/main.js", engine:"rhino"} 运行。 | [原文](<reports/2026-11-05_华仔部落佣兵战纪脚本报错定位与修复.md>)
- 2026-11-03 | Node Widget 入口与 chess-auto 编译界面资源修复 | MI8 从应用内“示例 → Node 引擎 → Node.js → Node.js中使用UI → Widget原生生命周期”点击运行，日志报 identifier is a reserved word: class。 | [原文](<reports/2026-11-03_NodeWidget入口与象棋编译资源修复.md>)
- 2026-10-27 | 听海 1.2.1：运行卡顿与主题统一 | 原来暂停/后台仍每 500ms 设置同一批文字、图标、ProgressBar，并对根容器 suppressLayout 和重新发布 PlaybackState。现在暂停后无周期 UI 重画；播放时每次只写变化的值。 | [原文](<reports/2026-10-27_听海运行卡顿与主题统一.md>)
- 2026-10-27 | 听海 Node 功能实现与原版接口接入 | 私有工程：.artifacts/tinghai-align-20261027/project/听海复刻/，1.1.0，main.js → 听海UI.js，Node引擎。 宿主候选： | [原文](<reports/2026-10-27_听海功能实现与原版接口接入.md>)
- 2026-10-27 | 听海 Node UI：构造器缓存与首屏优化 | 后续功能核查见 原版与Node示例功能对照。本报告的稳定性/构建速度通过，不等于搜索、歌单、设置等业务已实现。 | [原文](<reports/2026-10-27_听海NodeUI性能与缓存修复.md>)
- 2026-10-27 | 听海播放器细节与律动 1.2.2 | 源工程：.artifacts/tinghai-player-polish-20261027/project/听海复刻/，入口 main.js；project.json 为 1.2.2 / code 5。 | [原文](<reports/2026-10-27_听海播放器细节与律动.md>)
- 2026-10-27 | 听海原版界面对齐与点击闪屏修复 | 用户补充“所有操作点击都会闪一下，等一会才显示界面”。代码中确实存在先移除旧层、逐个跨 Java 桥创建控件，随后异步重画的链路。首页还一次创建全部歌单，曲目列表逐行读取原生播放状态；普通设置变更使无关页面失效。 | [原文](<reports/2026-10-27_听海原版界面对齐与点击闪屏修复.md>)
- 2026-10-27 | 听海原版与三份 Node 示例：功能对照与补修 | 目前没有完整复刻原版功能。 上一轮通过的是 Node UI 稳定性和页面构建优化。五页 听海UI.js 实际完成的交互仍主要是五页导航和单条合成海浪的播放/暂停； | [原文](<reports/2026-10-27_听海原版与Node示例功能对照.md>)
- 2026-10-27 | Android 16 实况通知与小米超级岛：实机鉴权排查 | 用户在dev-1269复制的诊断全部开启：Xiaomi、Android16、islandSupported=true、focusProtocol=3、notificationsAllowed/focusAllowed… | [原文](<reports/2026-10-27_Android16实况通知与超级岛鉴权排查.md>)
- 2026-10-26 | 听海项目 Node UI 兼容修复 | 用户项目：C:/Users/18101/Downloads/听海复刻.zip，原文件保留不变。阅读 README、崩溃报告、实际脚本和四个最小复现脚本。 参考 APK： | [原文](<reports/2026-10-26_听海NodeUI兼容修复.md>)
- 2026-10-26 | Materi-UI 适配与运行（2026-10-26） | 来源压缩包：C:\Users\18101\Downloads\6.Materi-ui.zip，其中项目 AutoJsPro-GoogleMaterial-main。 | [原文](<reports/2026-10-26_MateriUI适配运行.md>)
- 2026-10-26 | Materi-UI 原生兼容与 dev-1261（2026-10-26） | 增加 androidx.documentfile:documentfile:1.0.1 依赖与 R8 keep，原脚本的 importClass(DocumentFile) 可用。 | [原文](<reports/2026-10-26_MateriUI原生兼容与dev1261.md>)
- 2026-10-24 | K40 象棋停走诊断 | 手机仍为宿主 dev-1199；前台是 QQ 象棋。当前任务为 MCP mcp-538 / engine 547，入口 xiangqi-draw/node-port/main.js，Node PID 9736。 | [原文](<reports/2026-10-24_K40象棋停走诊断.md>)
- 2026-10-23 | 象棋引擎插件 1.1.0：独立 UCI 预算 | 用户要求编译更新插件，让未连接的新手机也能用预算。旧插件1.0.1/code2只提供同步JNI算棋，K40此前靠项目内另带可执行文件。现升级为 1.1.0/code3。 | [原文](<reports/2026-10-23_象棋引擎插件预算升级.md>)
- 2026-10-23 | 象棋引擎插件1.1.1：拒和评分修复 | 插件1.1.0的本地Contempt补丁在递归中比较pos.sidetomove与rootPos.sidetomove。搜索从search<Root>(rootPos,...)传入同一可变Position， | [原文](<reports/2026-10-23_象棋引擎拒和评分修复.md>)
- 2026-10-22 | 示例目录懒加载与插件发现优化（dev-1202） | 小米 8 已安装；K40 业务运行中保留 dev-1199，未打扰。 | [原文](<reports/2026-10-22_示例目录懒加载与插件发现优化.md>)
- 2026-10-21 | Node Dex、插件、Web UI 与 K40 内置示例修复 | 本轮实测包为 dev-1180 / dev-1181；dev-1182 已发布，更新源手机访问 HTTP 200。 发布 APK 为 AI-js-Pro-Flutter-dev-1182.apk， | [原文](<reports/2026-10-21_Node_Dex插件WebUI与K40修复.md>)
- 2026-10-19 | inrt 定时任务端到端（task-160，2026-10-19） | inrt 打包模板补齐了定时任务的调度初始化 / 持久化 / 服务 / 权限 / workmanager**；在 K40 上以 inrt debug APK（= 打包运行时本体）验证： | [原文](<reports/2026-10-19_inrt定时任务端到端.md>)
- 2026-10-18 | dev1046 打包警告与产物复验 | 验证时间：2026-10-18；设备：K40（cccc62c7）；宿主：com.jdkshen.aijspro，versionCode 1046。 | [原文](<reports/2026-10-18_dev1046打包警告复验.md>)
- 2026-10-05 | 象棋“等稳定”卡住：现场与绘制隔离 | 发布：宿主 dev-796 / 象棋工程 0.6.65，2026-10-05。 | [原文](<reports/2026-10-05_象棋等稳定卡住与绘制隔离_dev796.md>)
- 2026-10-05 | 象棋常驻搜索与 IO 队列：dev-794 / 0.6.64 | 生产代码新增 runtime/task-worker.js、task-service.js、io-queue.js、io-task.js 和 automation/search-task.js。 | [原文](<reports/2026-10-05_象棋常驻搜索与IO队列_dev794.md>)
- 2026-10-05 | 象棋吃子断谱与重复和棋修复 | 宿主：dev-798 / versionCode 798。 象棋工程：0.6.67 / versionCode 67。 源码 SHA-256： | [原文](<reports/2026-10-05_象棋吃子断谱与重复和棋_dev798.md>)
- 2026-10-05 | 象棋将帅安全与轮次等待：dev-793 / 工程 0.6.63 | 设备 cccc62c7，Android 16，游戏 com.tencent.qqgame.xq。读取了手机 panel-log.txt、后台视觉 JSON 以及截图。 | [原文](<reports/2026-10-05_象棋将帅安全与轮次等待_dev793.md>)
- 2026-10-05 | 象棋确认超时恢复盘面与棋谱：dev-801 | 修复的是 dev-800 完整对局测试 暴露的第 129 步确认超时：已发生的吃子和对方应手能够推导出来，但恢复时没有使用推导出的校正盘面，导致完整棋谱降为部分历史，并多等了一次重新定位。 | [原文](<reports/2026-10-05_象棋确认超时恢复盘面与棋谱_dev801.md>)
- 2026-10-05 | 象棋同炮反吃与连续往返修复 | K40 dev-798 / 象棋 0.6.67，08:40:53 开始、09:11:40 结束的这局，结果页面显示人机对战总步数达到上限判和。 | [原文](<reports/2026-10-05_象棋同炮反吃与往返和棋_dev799.md>)
- 2026-10-05 | 象棋无攻子残局与末大子保护：dev-800 | dev-799 这一局从 10:35 开始，车马炮陆续交换。11:10:32 日志记录对方 e8e9（将 吃 兵），我方最后一个兵被吃掉，仍继续计算士相走法。停止时的真实盘面为： | [原文](<reports/2026-10-05_象棋无攻子残局与末大子保护_dev800.md>)
- 2026-10-05 | 新手机 43 回合和棋与提前求胜（dev-803 / 象棋 0.6.72） | 新手机 Xiaomi 2602BRT18C 当时为 dev-802，象棋 0.6.71。 终盘明确显示「人机对战，双方不变着作和，43 回合，10/05 19:08」，对手为特级大师。 | [原文](<reports/2026-10-05_新手机43回合和棋与提前求胜_dev803.md>)
- 2026-10-04 | 象棋 ImGui 免无障碍显示适配（dev-787） | 应用：dev-787 / versionCode 787。 内置象棋示例：0.6.57 / project versionCode 57。 象棋源码清单：303 个文件， | [原文](<reports/2026-10-04_象棋ImGui免无障碍显示适配_dev787.md>)
- 2026-10-04 | 象棋 RenderState 与 ImGui 走法绘制 — dev-785 | 象棋工程更新为 0.6.55。搜索结果的实际深度、评分、耗时、PV、引擎来源沿 JOB_RESULT → move-ticket 传递。 新增 src/ui/render-state.js， | [原文](<reports/2026-10-04_象棋RenderState与ImGui走法绘制_dev785.md>)
- 2026-10-04 | 象棋后台观察与面板响应：dev-792 / 工程 0.6.62 | 实施与测试：2026-10-04 夜间；本地发布及 K40 安装：2026-10-05 00:01 后。 | [原文](<reports/2026-10-04_象棋后台观察_dev792.md>)
- 2026-10-04 | 象棋截图悬浮窗闪烁修复（dev-788） | 普通录屏和 Shell 截图包含覆盖窗。旧抓帧路径判断任一窗口遮挡后会隐藏操作窗和日志窗，等待 90ms，再截图恢复；循环识别因此反复藏显。 | [原文](<reports/2026-10-04_象棋截图悬浮窗闪烁修复_dev788.md>)
- 2026-10-04 | 象棋默认 ImGui 操作窗 — dev-791 | 工程 0.6.61 / code 61。运行悬浮窗入口时，默认显示 ImGui 六按钮操作窗：模式、开始/暂停、停止、定位、识别、更多。 完整参数和日志窗先准备布局、隐藏根 View，再挂载， | [原文](<reports/2026-10-04_象棋默认ImGui操作窗_dev791.md>)
- 2026-10-04 | 象棋与软件悬浮窗冲突检查 | K40（cccc62c7）安装 dev-783。检查时象棋脚本未运行，最近的象棋状态和日志停在 10 月 2 日。窗口记录中是软件悬浮球和收起的菜单；当时通知栏展开，系统暂时隐藏应用悬浮窗， | [原文](<reports/2026-10-04_象棋与软件悬浮窗冲突检查.md>)
- 2026-10-04 | 象棋自动走棋双箭头与连续显示（dev-790） | 用户要求：只在自动走棋时画方向箭头，不要建议绘制、方框或圆圈；显示对方预判，并避免反复闪烁。 | [原文](<reports/2026-10-04_象棋自动走棋双箭头与连续显示_dev790.md>)
- 2026-10-02 | K40 象棋运行与悬浮窗修复 | 宿主：dev-779 / versionCode 779，已发布并安装 K40（cccc62c7）。 象棋项目：0.6.53，源码、APK 内置示例、手机公开项目和运行缓存均已更新。 手机项目： | [原文](<reports/2026-10-02_K40自动预算与重定位及悬浮窗修复_dev779.md>)
- 2026-10-02 | SIFT 棋盘定位接入象棋项目 | 源码目录：apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋。 主界面、悬浮面板均配置项目内的 locate-board.js， | [原文](<reports/2026-10-02_SIFT棋盘定位接入象棋项目.md>)
- 2026-10-02 | SIFT 象棋图片实测（dev-771） | QuickJS、Rhino 在同一台 Redmi K40 上完成 872 次匹配调用，无接口异常，两个引擎的命中、漏检和错认数量一致。 | [原文](<reports/2026-10-02_SIFT象棋图片实测_dev771.md>)
- 2026-09-27 | 打包图片残留与细节检查 | 检查日期：2026-09-27。范围：脚本打包、项目图标/启动图、项目目录刷新，以及近期导入/打开流程的剩余细节。不是全仓库完工验收。 | [原文](<reports/2026-09-27_打包图片残留与细节检查.md>)
- 2026-09-27 | 恢复历史保留与中盘评分复核 | 恢复时丢失棋谱。K40 现场日志显示，已经确认对方 a5a3 后进入恢复，恢复后的盘面仍与已确认基准一致，却调用 trackAdopt 重新开段，完整历史变为部分历史。 恢复后一直等待。状态机曾收到“几何不可信”时， | [原文](<reports/2026-09-27_恢复历史保留与中盘评分复核.md>)
- 2026-09-27 | 天天象棋·特级大师：搜索与开局战绩 | 象棋项目版本：0.6.37。对手档案按用户确认设为「天天象棋·特级大师」。运行时全部在手机内完成，不连接电脑算棋。 | [原文](<reports/2026-09-27_特级大师搜索与开局战绩.md>)
- 2026-09-27 | 象棋 UI 与启动流程优化（0.6.28） | 主页面按钮统一高度、圆角、间距与配色；固定状态卡和停止入口。操作引导为「打开游戏棋盘 → 打开悬浮窗 → 选模式后开始」。悬浮窗状态从高级区移到顶部。 页面打开面板时先显示「正在打开」， | [原文](<reports/2026-09-27_象棋UI与启动流程优化.md>)
- 2026-09-27 | 象棋主界面 M3 改版与功能对齐 | 工程版本：0.6.33。主界面使用浅色绿调的 Material 3 风格令牌：大圆角分组卡片、清晰的文字层级、选中态和固定底部操作栏。这是现有 QuickJS XML 控件的样式实现， | [原文](<reports/2026-09-27_象棋主界面M3与功能对齐.md>)
- 2026-09-26 | 象棋面板 quickjs 崩溃取证 + 「崩溃前哨 / 自动重载」兜底（2026-09-26） | 用户报「为什么跑着软件就崩了」。取证结论：崩在 quickjs 引擎内部（libquickjs.so 的 JSDefineProperty）， | [原文](<reports/2026-09-26_象棋面板quickjs崩溃取证与自动重载兜底.md>)
- 2026-09-26 | 象棋四项结构修复交付 | 交付时间：2026-09-26。宿主已发布 dev-699，内置象棋工程 0.6.27。源码与 APK 已逐文件核对；手机能读取更新清单（HTTP 200）。 | [原文](<reports/2026-09-26_象棋四项结构修复交付.md>)
- 2026-09-25 | 象棋 Contempt 下发通道与 UCI 选项自检（0.6.26）· 2026-09-25 | 回答的是「开 setoption / 升级权重」两件事的实际接法：前者做掉，后者核出没有可升级的官方权重。 | [原文](<reports/2026-09-25_象棋Contempt下发通道与UCI选项自检.md>)
- 2026-09-25 | 象棋离线开局库试验（2026-09-25） | 给现有 AI.js Pro 象棋脚本加入一个可撤销的本地开局库试验。本试验只覆盖开局；中残局仍由设备上的 Pikafish 搜索。它不能单独解决上一局在 109 回合后和棋的问题。 | [原文](<reports/2026-09-25_象棋离线开局库试验.md>)
- 2026-09-25 | 象棋实战搜索策略（工程 0.6.21） | 保留原有 position fen ... moves ... 的完整已确认着法历史链路；不重建、不伪造历史。只有日志显示“历史 完整”时，才能期望 Pikafish 正确判断长将、长捉和重复局面。 | [原文](<reports/2026-09-25_象棋实战搜索策略_0.6.21.md>)
- 2026-09-25 | 象棋算力结论与参数开放（含规则胜前置）· 2026-09-25 | 回答用户问题：「是算力不够还是怎么样，赢不了对面的？」 | [原文](<reports/2026-09-25_象棋算力结论与参数开放.md>)
- 2026-09-25 | 象棋悬浮窗刷新慢（2026-09-25） | 「悬浮窗点击开启，悬浮窗按钮刷新很慢」。确认后的口径是五种都中：空载（只打开面板不开始）也慢、 面板刚出现时按钮要等几秒才「活」、点「开始」后按钮文字与状态行要等几秒才变、自动循环跑起来后按钮普遍 迟钝（点一下等 1… | [原文](<reports/2026-09-25_象棋悬浮窗刷新慢_根因与修复.md>)
- 2026-09-25 | 字形识别 · 多尺度抢救 + 电脑侧评测（2026-09-25） | K40 现场报「有一格棋子读不出来（未知格 1）→ 一直卡在恢复中不出手」，以及「计划行里实际层数显示 ?」。 | [原文](<reports/2026-09-25_字形识别_多尺度抢救与电脑侧评测.md>)
- 2026-09-24 | 象棋图色定位与悬浮窗适配（2026-09-24） | 手机运行时的棋盘几何只走 src/vision/lattice-detector.js：原生找色统计、9×10 格线梳状拟合、格距与边界校验。 | [原文](<reports/2026-09-24_象棋图色定位与悬浮窗适配.md>)
- 2026-09-24 | 象棋选中态识别与定位校验（dev-679） | 天天象棋选中棋子时会放大棋子、改变字的尺度，并叠加可走点或高亮。原字形识别只有在「没匹配到」或「把九宫外棋子认成仕/士」时才用 1.10、1.18 倍模板复核；高分误认成其他合法棋子会绕过复核。 | [原文](<reports/2026-09-24_象棋选中态识别与定位校验.md>)
- 2026-09-24 | 象棋助手当前算法与剩余工作 | `mermaid flowchart TD A［手机抓屏<br/>系统截屏或 Shizuku］ --> B［稳定帧检查］ B --> C［棋盘格线定位<br/>9列×10行］ C --> D［检测框回退与精校准］ D… | [原文](<reports/2026-09-24_象棋当前算法与剩余工作.md>)
- 2026-09-23 | 象棋 P4：已确认棋谱接入 Pikafish 试验（2026-09-23） | 本轮在 AI.js Pro QuickJS 象棋项目中接通 PositionTracker.positionCommand：驱动只有在账本局面与待算局面逐格、轮次一致时， | [原文](<reports/2026-09-23_象棋P4_棋谱历史接入试验.md>)
- 2026-09-23 | 象棋“特级大师”对局：为何暂时走不赢 | 手机游戏 com.tencent.qqgame.xq 的“特级大师”对手也在调用 Pikafish。 | [原文](<reports/2026-09-23_象棋特级大师对局诊断.md>)
- 2026-09-23 | 象棋"特级大师"攻坚实录（供 GPT 分析） | 本文只陈述已核实的事实、数据与已做过的实验，末尾列出开放问题，供外部分析。 | [原文](<reports/2026-09-23_象棋特级大师_攻坚实录_供GPT分析.md>)
- 2026-09-23 | 象棋项目简化第一轮（2026-09-23） | 项目已能识别、算棋和执行，但入口有主页面与悬浮窗两套 UI，视觉有 legacy、auto、diff 三种路径；auto-driver.js 与悬浮窗脚本都很长。直接大规模重写会触及落子安全闸门和大量真机特例。 | [原文](<reports/2026-09-23_象棋项目简化第一轮.md>)
- 2026-09-23 | 象棋自动走棋 · 重构 P0 基线冻结报告 | 依据：docs/plans/象棋自动走棋框架重构任务书2026-09-23.md §9 的 P0。 本报告只冻结事实：不改业务代码、不发布、不打包。 核对时间：2026-09-23 晚； | [原文](<reports/2026-09-23_象棋重构P0_基线冻结.md>)
- 2026-09-20 | 象棋当前架构路线图 | 依据：当前仓库中的实际调用关系，而不是早期设计稿。DP 正在继续修改恢复链路，因此恢复细节以后续代码为准。 | [原文](<reports/2026-09-20_象棋当前架构路线图.md>)
- 2026-09-20 | 象棋自动走棋 · 差分视觉与四个卡点修复（2026-09-20，K40 真机） | 口径（用户）：YOLO 只定位一次棋盘，剩下的用「找色判走动 + OCR 认子」。 本轮把它落成差分模式，并在真机上把随之暴露出来的四个卡点逐一定位修掉。 相关文件： | [原文](<reports/2026-09-20_差分视觉与卡点四修.md>)
- 2026-09-20 | 象棋自动走棋：评审问题修复交付说明（2026-09-20） | 所有结论都带可复现命令或真机日志原文；标注了哪些是当天实测、哪些只有离线自检、哪些还没做。 | [原文](<reports/2026-09-20_评审修复交付说明.md>)
- 2026-09-20 | 象棋自动走棋代码评审 | 评审日期：2026-09-20 评审范围：象棋项目生产入口、新视觉流水线、OCR/颜色融合、棋盘校验与相关测试 基础验证：全部JavaScript文件通过node --check； | [原文](<reports/2026-09-20_象棋代码评审.md>)
- 2026-09-20 | 象棋自动走棋项目文件结构评审 | 评审日期：2026-09-20 项目位置：apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋 验证结果： | [原文](<reports/2026-09-20_象棋项目文件结构评审.md>)
- 2026-09-19 | 象棋项目：UI 优化与收尾执行单 | 在保持 ai.js Pro、QuickJS、全本地 ONNX 推理和现有自动走棋安全闸门的前提下，先把主页面与悬浮窗做得清楚、紧凑、好看，再完成当前剩余缺陷和真机验收。需要实际修改、验证并回填报告，不能只提供设计稿。 | [原文](<reports/2026-09-19_象棋项目_UI与收尾执行单.md>)
- 2026-09-14 | 象棋 ③ §14 工程框架落地 + 「同字异色」关键冲突修复（真机验证记录） | 游戏：com.tencent.qqgame.xq（天天象棋，离线 vs 小白） | [原文](<reports/2026-09-14_象棋_框架落地与同字异色修复.md>)
- 2026-09-14 | 象棋 ③ 自动走棋：真机（K40 + 天天象棋离线局）定位报告 | 场景：悬浮窗运行与测试.js 面板 → ③ 自动走棋 → 腾讯天天象棋 com.tencent.qqgame.xq 离线局 vs「小白」 目录： | [原文](<reports/2026-09-14_象棋自动走棋_真机定位与手势结论.md>)
- 2026-09-14 | 象棋 v5 模型接入与「检测区域」配对评测（2026-09-14） | 对象：apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋 | [原文](<reports/2026-09-14_象棋v5模型接入与配对评测.md>)
- 2026-09-14 | 追加：§15 复审回填（2026-09-14 晚） | 本节回填两件事：① §5 的结论被推翻并给出新证据；② 按 §9–§13 的清单已落地的实现与实测结果。 所有真机数据来自 K40（cccc62c7）+ 天天象棋离线局 vs「小白」。 | [原文](<reports/2026-09-14_象棋_复审回填_单手探针与预算.md>)
- 2026-09-14 | 追加：§16 派单清单执行回填（2026-09-14 深夜） | 对应"上一次没做"的清单逐条执行结果。判定口径不变：接口调用成功 / 手势完成 / 棋规确认落子， 三件事分开统计，不拿"点了 N 次"当"走了 N 手"。 | [原文](<reports/2026-09-14_象棋_派单清单执行回填.md>)
- 2026-09-13 | 几何自动微调（autoAlign）与颜色采样：根因报告（2026-09-13） | 执行单 §9「第二批」里那条 「缓存框几何对颜色不利的根因」 结案报告。 结论先说：驱动侧那串「非法局面」不是漏检，也不是几何定位错，而是 **autoAlign 把几何挪到了 颜色采样不能待的位置**； | [原文](<reports/2026-09-13_象棋识别_几何微调与颜色采样_根因报告.md>)
- 2026-09-13 | 象棋棋子检测：**速度与精度**权衡分析（供 GPT 分析） | 背景：手机端「象棋自动走棋」脚本的视觉识别系统。目前识别质量已经不错，但仍有强化的空间： 推理要快（实时闭环），识别要准（错一个子就下错棋）。请基于本文的数据与约束，给出优化方案排序。 | [原文](<reports/2026-09-13_象棋视觉_速度与精度_供GPT分析.md>)
- 2026-09-13 | 象棋识别：第一批实施报告（执行单 §9 第一批） | 本报告只写实际执行过并有证据的结论。没有做 APK 构建与装机验证，因此不声称「APK 已通过」。 模型资产未被覆盖：assets/models/chessai_160.onnx 未改动（第三次微调未开跑， | [原文](<reports/2026-09-13_象棋识别_第一批实施报告.md>)
- 2026-09-13 | 象棋识别细节优化：DeepSeek 执行单 | 本单是代码阅读和文档审查形成的开发要求，不代表修复已落地、实验已重跑或 APK 已验证。原外部评审包的实测数字仍是历史报告，不能当作本次独立复核结果。 | [原文](<reports/2026-09-13_象棋识别_细节优化_DeepSeek执行单.md>)
- 2026-09-13 | 象棋悬浮窗「运行与测试」面板（2026-09-13） | 在手机上不依赖 PC、不依赖编辑器，直接跑象棋工程的识别 / 定位 / 规则 / 引擎 / 自动循环， | [原文](<reports/2026-09-13_象棋悬浮窗运行与测试面板.md>)
- 2026-09-13 | 象棋自动走棋「识别 + 微调」外部评审包 | 用途：把这份文档整份贴给外部模型（GPT/Claude）做优化与细节审查。 它自带背景、实测数字、代码片段与失败复盘，不需要读仓库也能推理。 项目： | [原文](<reports/2026-09-13_象棋识别与微调_外部评审包.md>)

#### 4.4.7 发布 / 更新 / 签名（35 篇）

- 2026-11-03 | dev-1326 局域网发布 | 包含1325的Node原生界面资源加载、平台API阴影与动态基本类型回调修复。 补齐本轮MCP审查列出的保留条目保护、签名元数据、AXML/DEX/资源读写与搜索、AES/ZipCrypto、Java有界正则、多架构… | [原文](<reports/2026-11-03_dev1326局域网发布.md>)
- 2026-11-02 | 应用内更新安装失败修复（FileProvider → PackageInstaller 会话） | 真机（.4 Android 17、MI8 Android 15 均可复现）日志： | [原文](<reports/2026-11-02_应用内更新安装失败修复.md>)
- 2026-11-02 | C/C++ 公网下载与 HTTPS 终端退出修复 | 组件已公网发布；dev-1307修正版已在MI8安装验收。APK仍提供手动测试包，主LAN更新源保持1305。 | [原文](<reports/2026-11-02_C与C++公网下载及终端退出修复.md>)
- 2026-11-01 | dev-1303 局域网发布 | Node 原生/协议/marker 监听共用 EventEmitter，修 once/off/prepend、重复注册、幽灵监听、轮询清理和异常隔离。 兼容界面进程正确接收句柄命令，重复查找身份稳定； | [原文](<reports/2026-11-01_dev1303局域网发布.md>)
- 2026-10-31 | 节点对齐 R01–R08 集成与发布（dev-1294） | 四个专项并行完成后由 Lead 集成、回归并发布。dev-1294 是当前 LAN 发布版本。 | [原文](<reports/2026-10-31_节点对齐R01-R08集成发布.md>)
- 2026-10-30 | Node Widget 原生生命周期与 dev-1291 发布 | 接续 dev-1288，将当前 Node 的自定义 Widget 从提前展开 XML 改为原生 inflater 分阶段创建。保持脚本槽和 UI 进程隔离，没有把同步脚本工作搬回主应用进程。 | [原文](<reports/2026-10-30_Node_Widget原生生命周期.md>)
- 2026-10-30 | VS Code 手机日志转发修复（本地候选 dev-1290） | 手机端统一日志改用 GlobalConsole.printlnAt(level, text, time, source) 后，AutoJs.createGlobalConsole() 仍只覆盖 println， | [原文](<reports/2026-10-30_VSCode手机日志转发修复.md>)
- 2026-10-29 | 三引擎架构对齐实施（dev-1286 已发布） | 用户要求先完成代码架构对齐，再集中测试及发布。本轮已按该顺序完成并发布局域网 dev-1286；最终证据、包位置和剩余边界见发布验收。旧 dev-1285 的结果没有用来替代本轮验收。 | [原文](<reports/2026-10-29_三引擎架构对齐实施.md>)
- 2026-10-29 | dev-1286 发布与架构对齐验收 | 电脑安装包：C:/Users/18101/Downloads/AIjsPro-dev-1286.apk。 小米 8 已安装最终包； | [原文](<reports/2026-10-29_dev1286发布与架构对齐验收.md>)
- 2026-10-28 | dev-1281 发布记录 | 用户要求先发布当前修复，剩余功能后续再完善和测试。本版基于 18ff588f，功能代码与已验证的 1280 候选一致；仅递增版本为 1281 / dev-1281，使已经安装 1280 测试版的设备也能正常收到更新。 | [原文](<reports/2026-10-28_dev1281发布.md>)
- 2026-10-27 | AI.js Pro dev-1269 发布记录 | 用户授权发布更新。1268 是已交付的超级岛测试候选，因此本次使用1269 / dev-1269，使用户已装1268的手机也能升级。 | [原文](<reports/2026-10-27_dev1269发布.md>)
- 2026-10-24 | dev-1242 开发通道发布 | 用户在五项优化验收后明确要求“发布新版本，我更新”。本轮已发布到现有局域网更新源，未安装或重启手机 App，供用户自行检查更新。 | [原文](<reports/2026-10-24_dev1242发布.md>)
- 2026-10-24 | dev-1243 开发通道发布 | 后续发现：本页当时的下载校验是 HTTP/curl，未覆盖应用内 Retrofit 适配器。用户截图暴露 release 泛型裁剪故障，已在 dev-1244 修复并实际装机验证。 | [原文](<reports/2026-10-24_dev1243发布.md>)
- 2026-10-24 | dev-1244：更新下载 0% 修复与发布 | 用户能检查到 dev-1243，但点击应用内直接下载后停在 0%，提示： | [原文](<reports/2026-10-24_dev1244更新下载修复与发布.md>)
- 2026-10-23 | 悬浮窗主线程退出崩溃修复（dev-1211 修复，dev-1213 发布） | 用户提供 悬浮窗-ab2e79fab35444808d1cf5ec86266d08.zip， | [原文](<reports/2026-10-23_悬浮窗主线程退出崩溃修复.md>)
- 2026-10-23 | dev-1221 发布核验 | 更新：2026-10-23。当前更新源已发布 dev-1221 / 1221；本轮复核现有发布包，没有用较旧候选包覆盖更新源。 | [原文](<reports/2026-10-23_dev1221发布核验.md>)
- 2026-10-21 | VS Code 设备选择修复（扩展 0.3.3） | 手机仍使用 dev-1190；本次仅更新电脑端 jdkshen.aijspro-vscode 到 0.3.3，VSIX 已安装到本机。 | [原文](<reports/2026-10-21_VSCode设备选择修复.md>)
- 2026-10-20 | 关于页对齐高级版（dev-1159） | 参照：Auto.js Pro 高级版 9.3.11-0（com.boyai.auma）设置 → 关于 → 关于项目与开发者 页面 实现： | [原文](<reports/2026-10-20_关于页对齐高级版.md>)
- 2026-10-20 | 新环境编译问题与原作者补充清单（上游更新 2026-10-20） | 本文在原清单基础上，于文首补充「上游更新（2026-10-20）」：逐条回应 P0/P1 现状、给出一份「新环境从零编译步骤」，并保留外部原文作为附录。 上游更新核对时间：2026-10-20， | [原文](<reports/2026-10-20_新环境编译问题与补充清单_上游更新.md>)
- 2026-10-20 | GitHub App 分支显示 main ✕ 排查记录（修订版） | 仓库：https://github.com/Jdkshen/ai.js-pro | [原文](<reports/2026-10-20_GitHubApp_main分支红叉排查.md>)
- 2026-10-18 | PP-OCR 模型策略变更全程记录：按需下载 → 回滚内置 → 彻底禁用下载 ⚠️历史/被取代 | 涉及任务：task-121（按需下载）、task-125（回滚内置）、task-128（彻底禁用下载）。 | [原文](<reports/2026-10-18_models按需下载_PP-OCR.md>)
- 2026-10-12 | Ace 标签切换与键盘唤起修复（dev-888） | dev-886 为限制多标签内存，最多缓存两个编辑页面。连续切换三个及以上已访问文件时，页面会反复释放和重建，重新读取文档、加载 Ace 和启动补全服务。切换时从容器移除 WebView，还会影响它的输入连接。 | [原文](<reports/2026-10-12_Ace标签切换与键盘唤起修复.md>)
- 2026-10-05 | 新手机未知格与将帅短暂漏读（dev-804） | 设备 Xiaomi 2602BRT18C，序列号 DQKFJB59596L45BI。此次已经更新至宿主 dev-803、工程 0.6.72；与上一局仍运行 dev-802 的记录不同。 | [原文](<reports/2026-10-05_新手机未知格与将帅短暂漏读_dev804.md>)
- 2026-10-04 | GitHub 发布与应用检查更新（dev-781） | 沿用已验证的 Miuix Compat 开发签名安装包，支持 Rhino 和 QuickJS。此次 GitHub 上传不重建 APK。 | [原文](<reports/2026-10-04_GitHub发布_dev781.md>)
- 2026-10-04 | K40 更新实测（dev-783） | 局域网 APK 与 GitHub 发布的 arm64 APK 相同，SHA-256 为 a6ef1a09b95ef787ffe4e918171f3043a7f1cf7d33c0895a64fbaa754548017b… | [原文](<reports/2026-10-04_K40更新实测_dev783.md>)
- 2026-10-02 | dev-772 发布记录 | 已发布到现有更新源；K40 拉取更新清单返回 HTTP 200。 K40（cccc62c7）安装成功，实际版本为 dev-772 / code 772。 内置象棋项目更新到 0.6.46， | [原文](<reports/2026-10-02_dev772_SIFT象棋项目发布.md>)
- 2026-09-27 | 两局负局的禁手策略修复 | 20:42 开始的一局：第 96 回合，求胜模式硬排除 g7g4/g7g8 等近均势候选，迫使引擎选择明显劣势着法。上次算棋分数不能证明本次换招安全。 22:34 开始的一局：求胜已关闭， | [原文](<reports/2026-09-27_禁手策略修复_dev719.md>)
- 2026-09-27 | 移除电脑远程算棋入口 | 交付版本：App dev-714，象棋项目 0.6.36。 | [原文](<reports/2026-09-27_移除电脑远程算棋入口.md>)
- 2026-09-27 | QQ / 外部文件的打开、导入入口 | 在 dev-702 手机上查询 Android 候选应用时，ACTIONVIEW + application/octet-stream 和 ACTIONSEND + text/plain 均找不到 AI.js Pro… | [原文](<reports/2026-09-27_QQ外部文件入口修复.md>)
- 2026-09-25 | 特级大师 109 回合和棋：实测与修复 | 新手机的结算页写明“人机对战，总步数达到上限判和，109 回合”。本局最后几手的 AI.js Pro 日志显示：棋子识别验证通过、点击后盘面确认成功；Pikafish 每步却只搜索约 1000 ms， | [原文](<reports/2026-09-25_特级大师109回合和棋复盘与修复.md>)
- 2026-09-23 | 象棋 P4 开发版 dev-661 发布与验收（2026-09-23） | 发布前 K40 和更新源均为 dev-660。使用仓库 tools/publish.ps1 -AllowMultiDevice -DeviceId cccc62c7 流程按三方基准（仓库 465、更新源 660、K4… | [原文](<reports/2026-09-23_象棋P4_dev-661发布验收.md>)
- 2026-09-16 | 示例「一运行就报错」：伴生资源没跟着走（已修，dev-604 已发布并真机复验） | 发布：dev-604（versionCode 604，SHA-256 6dad0030…，手机拉清单 200）。 | [原文](<reports/2026-09-16_示例运行缺伴生资源.md>)
- 2026-09-15 | 内置示例升级后不会自动更新（已修） | 新版本里改好的示例，手机升级安装后仍然是旧的： 「示例」在新版本里已经修好（本仓库 2026-09-15 修了 7 个一运行就报错的 Rhino 样例、 2 个 OCR 样例改用内置 ocr 模块）， | [原文](<reports/2026-09-15_内置示例升级同步.md>)
- 2026-09-14 | 发布历史持续保留 ⚠️历史/被取代 | 截图中的 30 条来自 tools/serve-updates.ps1 的默认参数 HistoryLimit = 30。此前每次发布都会截断 update-history.json， | [原文](<reports/2026-09-14_发布历史持续保留.md>)
- 2026-09-14 | K40 验证：自建更新源 + 象棋识别链路（2026-09-14） | 对象：K40（cccc62c7，M2012K11AC，LineageOS 23.2 / Android 16 / SDK 36，1080×2400，无 root） 对照：小米8（ce4d2bdb， | [原文](<reports/2026-09-14_K40验证_更新源与象棋链路.md>)

#### 4.4.8 性能 / 卡顿 / 启动（7 篇）

- 2026-10-28 | 节点分析：隔离、超时取消与大树优化（2026-10-28） | 抓取统一入口：CaptureRequestGate + NodeCaptureBudget；单物理抓取、8 s 预算、15000 节点/256 层上限；超时/取消丢弃晚到结果， | [原文](<reports/2026-10-28_节点分析隔离与大树优化.md>)
- 2026-10-24 | 布局与绑定桥批量优化（2026-10-24） | 承接《Node M3 四项底层优化》，本轮只处理布局/绑定桥的细碎调用，不再改 DEX/Java JNI 主体。 | [原文](<reports/2026-10-24_布局与绑定桥批量优化.md>)
- 2026-10-24 | 五项优化任务与验收 | 后续状态：用户新增发布指令后，已重新构建并发布 dev-1242；见发布与下载核验。下文保留候选 1241 的原始验收与发布前状态。 | [原文](<reports/2026-10-24_五项优化任务与验收.md>)
- 2026-10-24 | 原生 setup 延迟与控制台懒加载（2026-10-24） | 《布局与绑定桥批量优化》测得 M3 示例 Java fast calls 仅 72–93 次，整页主要成本是 ViewPager/Drawer/Grid/Console 的原生 setup。 | [原文](<reports/2026-10-24_原生setup延迟与控制台懒加载.md>)
- 2026-10-12 | 双指缩放卡顿修复（2026-10-12，AI dev-890 / AJ v159） | 用户反馈放大、缩小卡顿。两个版本使用同一套手势缩放机制，因此同时修复。下载目录原始 bracketHighlight.js 保留。 | [原文](<reports/2026-10-12_双指缩放性能修复.md>)
- 2026-10-12 | Ace 大文件、多标签和缩小后拖动修复（dev-886） ⚠️历史/被取代 | 后续 dev-888 已改为共享编辑页面，改善缓存标签切换与软键盘唤起；两台各 20 项测试通过。最新结果见 标签切换与键盘修复。以下保留 dev-886 历史记录。 | [原文](<reports/2026-10-12_Ace大文件多标签与拖动修复.md>)
- 2026-09-15 | 项目管理与路径交互优化 | 项目保存增加提交中状态与按钮禁用，阻止重复提交；新建检查绝对路径、同名目标、父目录存在及可写性、名称中的路径分隔符。 切换项目存在未保存字段/图标改动时，提供保存后切换、放弃修改并切换、取消。 | [原文](<reports/2026-09-15_项目管理与路径交互优化.md>)

#### 4.4.9 构建 / 16KB / 原生库（2 篇）

- 2026-10-19 | 16KB 页对齐守卫修复 + task-80 RELRO 圆整（2026-10-19） | 现状：主引擎 modules/engine/src/main/cpp/CMakeLists.txt（4 处）与 pikafish/CMakeLists.txt 已有 -Wl,-z,max-page-size=1638… | [原文](<reports/2026-10-19_16KB_RELRO守卫与task80.md>)
- 2026-10-01 | JavaKotlin2Dex Kotlin 编译失败修复 | 用户明确要求修复 AIjsPro 应用的兼容性。下方修改第三方 dex 的方案属于先前走偏的诊断方案，已撤回交付建议；Downloads 中的两个工具补丁包保留为旧实验产物，不用于本次修复。 | [原文](<reports/2026-10-01_Kotlin编译工具类加载冲突修复.md>)

#### 4.4.10 文档 / 规范 / 交接（3 篇）
- 2026-11-06 | 死代码与冗余资产盘点（只读） | 只读盘点 6601 个 tracked 文件：可判定无引用的只有 rhino-1.7.14.1.jar（仅注释）与 third-party/ApkBuilder/apkbuilder/out.txt（测试输出），另有 1 个 0 字节 resources.arsc 需拍板；两份 template.apk 内容不同（宿主瘦身 20.4MB/0 lib vs 插件全量 88.3MB/16 lib）都必须保留，同名 asset 54 组多数是 sample 各自文件。 | [原文](<reports/2026-11-06_死代码与冗余资产盘点.md>)

- 2026-11-01 | Shizuku 内置改造：指南审阅与修订方案 | 后续：用户批准后已完成本轮接入并发布LAN dev-1305，见实现、验收与发布记录。其中rish采用只读APK＋本地通道替代外置DEX；下文保留评审当时的1303基线和待办描述，不能直接当作当前缺失项。 | [原文](<reports/2026-11-01_Shizuku改造指南审阅与实施方案.md>)
- 2026-09-12 | AI.js Pro 接手指南（给 Codex / 下一个 agent） | 撰写日期：2026-09-12 ｜ 基线 commit：7252b58（分支 chore/upgrade-gradle-8， | [原文](<reports/2026-09-12_接手指南_Codex.md>)

#### 4.4.11 无日期报告 / 专项资料（5 篇）

- — | 电脑 py 识别（参考实现）—— 2026-09-22 建于 `.artifacts`，2026-09-24 存档到本目录 | 这套 Python/OpenCV 脚本是「字形模板识别」的参考实现，手机端 src/vision/glyph-recognizer.js 就是它的 1:1 移植（差异见该模块文件头：搜索窗不对称 + 跨分辨率缩放）。 | [原文](<reports/chess-skin-eval/py/README.md>)
- — | 皮肤盘面级评测集（腾讯天天象棋 + K40，2026-09-13 建立） | 这是交付域的盘面级评测集：3 帧真机截图，FEN 由人逐格核对（并用 fen.validateBoard 验过合法性）。 存在的理由：mAP 对「整盘读没读对」不敏感（历史上微调后 mAP 只差 0.006， | [原文](<reports/chess-skin-eval/README.md>)
- — | AI.js Pro 高精度耗时与案例同步更新说明 | 历史快照说明，更新日期：2026-08-31。 适用范围：QuickJS 新引擎、Rhino 兼容引擎、图色与 YOLO 示例；结论应结合当前源码复核。 | [原文](<reports/高精度耗时与案例同步更新说明.md>)
- — | Native Frame 稳定测试报告 | 历史快照报告，更新时间：2026-09-02。以下结果来自当时的源码和真机环境，不自动代表当前工作树已重新通过同一套验证。 | [原文](<reports/NATIVE_FRAME_稳定测试报告.md>)
- — | QuickJS 新引擎开发完成报告 | 历史快照报告，核对日期：2026-09-01。本报告记录当时的实现与测试结果，不自动代表当前工作树已重新通过同一套验证。 | [原文](<reports/QUICKJS_完成报告.md>)

### 4.5 方案与任务书（plans/，22 篇）

- 2026-11-04 | 声动地形图 1.1 —— VMP 逆向与源码重建 项目总结 | 目标包：com.sonic.topography.wallpaper（声动地形图 1.1） 保护方案：云镜 VMP（方法级保护，非整体壳） 重建包名： | [原文](<plans/2026-11-04_SonicTopography项目总结（被逆向项目侧）.md>)
- 2026-11-04 | AI.js Pro MCP —— 本轮（新工具）实测总结 | 后端身份：uid=2000(shell)（shizuku）· context=u:r:shell:s0 · app 进程 com.jdkshen.aijspro 被测对象： | [原文](<plans/2026-11-04_AIjsPro-MCP总结_本轮实测（含3个bug）.md>)
- 2026-11-04 | AI.js Pro MCP 逆向能力优化建议 | 文档性质：开发建议与实战复盘，不代表下述功能已经实现。 | [原文](<plans/2026-11-04_AIjsPro-MCP逆向能力优化建议（GPT复盘）.md>)
- 2026-11-04 | AI.js Pro MCP 使用体验反馈（用户侧实战复盘） | 与 AI.js-Pro-MCP-逆向能力优化建议.md（开发侧）互补：那份讲「应该做什么」，本篇讲「实际卡在哪」。 文档性质：实测反馈，不代表任何功能已实现。数字（次数/耗时）为会话内估计值。 | [原文](<plans/2026-11-04_AIjsPro-MCP使用体验反馈（用户侧）.md>)
- 2026-09-23 | 象棋自动走棋框架重构任务书（AI.js Pro QuickJS） | 对象：samples/象棋自动走棋/（写作时的 apps/app/src/main/assets/sample/QuickJS 新引擎/项目与打包/象棋自动走棋/ 已迁出主 APK） 用途： | [原文](<plans/象棋自动走棋_框架重构任务书_2026-09-23.md>)
- 2026-09-23 | AI.js Pro 整仓重构 · 全程里程碑表（2026-09-23） | 提交已全部推送到 origin，当前 main = 45fae285，不再有“本地未推送”的提交； - 平台线：Flutter dev 通道完成 :script / :native / :node24 多进程隔离； | [原文](<plans/整仓重构全程计划_2026-09-23.md>)
- 2026-09-23 | AI.js Pro 整体框架重构任务书 | 用途：交给 DeepSeek / 接手开发者分批实施与验收。本文是目标方案，不代表这些改造已经完成。 | [原文](<plans/AI.js_Pro_整体框架重构任务书_2026-09-23.md>)
- — | 象棋棋盘定位：多证据融合实施方案 | 适用项目：AI.js Pro 新引擎 / 象棋自动走棋 目标设备：Android 手机屏幕截图，不处理相机拍摄的透视棋盘 | [原文](<plans/象棋棋盘定位_多证据融合实施方案.md>)
- — | 象棋自动走棋：YOLO + OCR + 红黑分离视觉架构重构方案 | 目标平台：AI.js Pro QuickJS 引擎、Redmi K40（1080×2400，Android 13/MIUI） 运行方式：完全本地，不访问云端，不依赖网络 文档用途： | [原文](<plans/象棋_YOLO_OCR_红黑分离视觉架构重构方案.md>)
- — | AI.js Pro 侧边栏按钮与全分辨率 UI 适配方案 ⚠️历史/被取代 | 已作废（2026-09-10）： 本文针对的 ImGui 主工作台已整体删除（见 项目现状与接手说明），方案不再执行，仅作历史记录保留。 | [原文](<plans/侧边栏按钮与全分辨率UI适配方案.md>)
- — | AI.js Pro 悬浮窗 API 需求清单 | 批A 真机验证（Mi8 ce4d2bdb）：初始坐标 600/400 ✅、visible:false 不显示 ✅、显示前 findView 可用 ✅、show/hide/setVisibility 往返 ✅、setP… | [原文](<plans/QUICKJS_悬浮窗API需求清单.md>)
- — | AI.js Pro 悬浮窗流畅度优化方案 | 状态更新（2026-09-10）： 涉及 ImGui 主工作台的条目（目标描述中的 ImGui 空闲负载、2.1、3.1、4.1）已随工作台删除失效（见 项目现状与接手说明）。悬浮球条目中， | [原文](<plans/悬浮窗流畅度优化方案.md>)
- — | AI.js Pro ImGui 左侧抽屉菜单改造任务 ⚠️历史/被取代 | 已作废（2026-09-10）： 任务针对的 ImGui 主工作台（ImGuiWorkspaceDrawer、autojs_imgui.cpp 等）已整体删除（见 项目现状与接手说明），不再执行，仅作历史记录保留。 | [原文](<plans/IMGUI_左侧抽屉菜单改造任务.md>)
- — | MiMo 返修任务：抽屉按钮点击无响应 ⚠️历史/被取代 | 已作废（2026-09-10）： 返修目标 ImGuiWorkspaceDrawer 所属的 ImGui 主工作台已整体删除（见 项目现状与接手说明），本任务不再执行，仅作历史记录保留。 | [原文](<plans/MIMO_抽屉按钮无响应返修任务.md>)
- — | MiMo 改造任务：全局主题完整覆盖 | 工作台抽屉中的“主题”目前只执行： | [原文](<plans/MIMO_全局主题完整覆盖改造任务.md>)
- — | MiMo 任务链：脚本 APK 加密恢复 + 打包链路回归 | 核对日期：2026-09-04。当前分支 chore/reorganize-project-layout（HEAD 9130358），项目目录已重组为 apps/、modules/、third-party/。 | [原文](<plans/MIMO_脚本APK加密恢复与打包链路回归任务.md>)
- — | Native Frame 截图、图色、模板与 YOLO 稳定测试任务 | 主验收设备：小米 K40（ADB 序列号 cccc62c7） 应用包名：com.jdkshen.aijspro | [原文](<plans/NATIVE_FRAME_截图图色模板YOLO稳定测试任务.md>)
- — | PP-OCR 接入评估（方案 · 工作量 · 风险） | ⚠️ 2026-09-18 起作废：本地 PP-OCR 资产全是 NCNN 格式（.param/.bin），OpenCV DNN 吃不了，本文的 ONNX 路线不再采用。 | [原文](<plans/PP-OCR接入评估.md>)
- — | PP-OCRv6 (NCNN) 接入方案 | 取代 docs/plans/PP-OCR接入评估.md（那份的 ONNX / OpenCV DNN 路线已作废 —— 本地资产全是 NCNN 格式，OpenCV DNN 吃不了 .param/.bin）。 相关： | [原文](<plans/PP-OCRv6_NCNN接入方案.md>)
- — | QuickJS 下一阶段兼容桥开发任务书 | 本文件用于交给后续 Codex 任务直接执行。开始前必须检查当前工作区，当前源码是唯一基线；不要根据旧对话或旧文档猜测架构。 | [原文](<plans/QUICKJS_下一阶段开发任务.md>)
- — | Rhino / QuickJS 双引擎回归与发布验收任务 | 用途：把本文件直接交给开发代理执行。目标不是只修改脚本，而是在连接的手机上完成可复现的 Rhino、QuickJS 双引擎回归，并提交真实日志。 | [原文](<plans/RHINO_双引擎回归与发布验收任务.md>)
- — | UI 统一到 Compose(Miuix) 迁移方案 | 制定日期：2026-09-12 | [原文](<plans/UI_统一到 Compose(Miuix) 迁移方案.md>)

> plans 状态：已作废（ImGui 主工作台已删除）3 篇、PP-OCR ONNX 路线作废 1 篇、部分失效 1 篇、多数任务书带 2026-10-19 时效说明；详见 §5。

### 4.6 专项记录（node24-xiangqi/，8 篇 Markdown + 脚本）

- — | M2a：QuickJS 面板 ↔ Node24 识别后端（产品集成第一步） | 产物：正式象棋工程内「识别一帧 → Node24 识别 → 面板显示 FEN」全链路；以及自动走棋实跑的回归证据。 | [原文](<node24-xiangqi/m2a-product-integration.md>)
- — | Node24 象棋识别移植工具链（R5） | 本目录存放「把象棋真实识别链跑在 Node24 上」的验证工具与基线，配套产品代码（modules/engine 下的 Node24 image/yolo 桥）使用。 | [原文](<node24-xiangqi/README.md>)
- — | Node24 vs QuickJS 图像解码 parity（task-29） | 每帧 1090 点 = 棋盘 9x10 交点（90）+ 1000 个固定种子随机点（覆盖全画面）；像素取 images.pixel / image.pixelSync 的 ARGB。 原图： | [原文](<node24-xiangqi/decode-parity.md>)
- — | Node24 vs QuickJS YOLO 逐框 parity + assign/autoAlign 逻辑摘录（task-29） | 结论先行：YOLO 宿主逐框完全一致；B 路径此前的 33 vs 32 差异来自检测区域选择（chess detection-stage 用 typeof image.getWidth === 'function' … | [原文](<node24-xiangqi/yolo-parity.md>)
- — | R5 最终报告：真实象棋识别链移植到 Node24 —— 逐格等价 + 反超 QuickJS（2026-10-20） | 设备 K40｜旁路包 com.jdkshen.aijspro.nodebench（测试后卸载）｜HEAD b773138f | [原文](<node24-xiangqi/R5最终报告-Node24识别移植.md>)
- — | R5 Node24 识别性能优化报告（task-30） | 稳态单帧 352–416ms（f01 408/416/462ms、skin-a 352/357/380ms，共享 detector；第 2/3 次为稳态）， | [原文](<node24-xiangqi/r5-perf-report.md>)
- — | R5 Node24 移植探针状态与精确缺口（task-27） | 文件：output/three-engine-bench/r5-node24/shim.node.js、r5-node24-recognize.node24.js、本文件。 | [原文](<node24-xiangqi/r5-node24-port-notes.md>)
- — | R5.4 Node24 识别结果 vs QuickJS 基线的自动对照 | 生成：2026-10-20T08:40:55 QuickJS 基线：C:\Users\18101\Documents\ai-js-pro\output\three-engine-bench\quickjs-basel… | [原文](<node24-xiangqi/r5-recognition-parity.md>)

> 这是 2026-10-20 前后把象棋识别链从 QuickJS 移植到 Node24 的专项工具与基线记录；报告内数字只代表当时设备（K40）与版本。

### 4.7 已归档（archive/，26 个文件 / 3 篇 Markdown）

- — | 象棋 P4 历史探针 | 这里保存 2026 年 9 月调试棋盘定位、截图与点击时使用的 23 个一次性 p4-*.js。生产入口、自检和回归没有引用它们；它们已从 APK 的内置示例工程移出。 | [原文](<archive/xiangqi-p4-probes/README.md>)
- — | 象棋自动走棋 · QuickJS 工程骨架 | 中国象棋自动走棋工程的落点：FEN / 棋盘几何 / 格线自动定位 / 格点分配 / 时序融合 / 棋规与合法着法 / 识别链路（模型随包） / 本机算棋 / 自动执行（票据 + 点击 + 落子确认） 都是真代码。 | [原文](<archive/xiangqi-project-readme-before-simplification.md>)
- — | tools 目录说明与分类（2026-09-20） | 背景：tools/ 一度长到 42 个脚本，正式工具与阶段性探针混在一起，看名字分不出谁还有效。 这份说明先做分类与搬迁方案（不改文件、零风险），等确认后再按「搬迁步骤」执行。 | [原文](<archive/xiangqi-tools-readme-before-simplification.md>)

### 4.8 非 Markdown 资产（不逐条索引）

- `guides/images/`：19 张截图（MCP 连接、Materi-UI、Inspector、M3 日志等），被 `MCP连接指南.md` 等引用。
- `reports/` 下 77 个资产：`chess-skin-eval/`（皮肤盘面评测脚本、模板图、真值 JSON）、`chess-sift-*`（SIFT/YOLO 评测 csv/json）、`2026-09_API覆盖探针_引擎对照/`（两个 txt 探针输出）等；属于报告证据，不单独列条目。
- `architecture/`：`pro-api-contract.json`、`pro-api-compatibility.csv`（第一代 Pro API 对照数据）。
- `node24-xiangqi/`：8 个探针/基线脚本与 JSON；`archive/`：23 个 P4 一次性 js。

## 5. 重复 / 已被取代 / 建议归档（本轮只标记，不移动）

> 原则：**历史报告是证据，不轻易删**；明确被后续取代的进 `docs/archive/`，仍在用的入口页只加状态横幅。以下建议都不在本轮执行。

### 5.1 高置信「已被取代 / 已作废」（建议归档到 `docs/archive/`）

- [`reports/2026-10-10_Flutter_v34与AI.jsPro界面数值核对.md`](<reports/2026-10-10_Flutter_v34与AI.jsPro界面数值核对.md>) —— 正文自述「本报告已作废三轮结论」，只保留失败教训。**建议：归档到 `docs/archive/reports-2026-10/`**。
- [`reports/2026-10-18_models按需下载_PP-OCR.md`](<reports/2026-10-18_models按需下载_PP-OCR.md>) —— 正文自述「已被 task-125/128 取代」。**建议：归档**。
- [`plans/PP-OCR接入评估.md`](<plans/PP-OCR接入评估.md>) —— 2026-09-18 起作废（ONNX/OpenCV 路线不适用）；`PP-OCRv6_NCNN接入方案` 已明确取代。**建议：归档到 `docs/archive/plans/`**。
- [`plans/IMGUI_左侧抽屉菜单改造任务.md`](<plans/IMGUI_左侧抽屉菜单改造任务.md>) —— 自述已作废：ImGui 主工作台已删除。**建议：归档**。
- [`plans/MIMO_抽屉按钮无响应返修任务.md`](<plans/MIMO_抽屉按钮无响应返修任务.md>) —— 自述已作废：ImGui 主工作台已删除。**建议：归档**。
- [`plans/侧边栏按钮与全分辨率UI适配方案.md`](<plans/侧边栏按钮与全分辨率UI适配方案.md>) —— 自述已作废：ImGui 主工作台已删除。**建议：归档**。
- [`plans/悬浮窗流畅度优化方案.md`](<plans/悬浮窗流畅度优化方案.md>) —— 自述部分失效（ImGui 条目已随工作台删除）。**建议：保留有效小节，其余归档/加横幅**。
- [`reports/2026-10-11_Ace编辑器移植与缩放修复.md`](<reports/2026-10-11_Ace编辑器移植与缩放修复.md>) —— 自述后续 dev-888 已发布，最新见 `2026-10-12_Ace标签切换与键盘唤起修复`。**建议：归档（保留过程）**。
- [`reports/2026-10-12_Ace大文件多标签与拖动修复.md`](<reports/2026-10-12_Ace大文件多标签与拖动修复.md>) —— 同上，dev-886 中间记录，被 dev-888 标签/键盘修复取代。**建议：归档**。
- [`reports/2026-10-18_NodeUI渲染收尾_交给Codex.md`](<reports/2026-10-18_NodeUI渲染收尾_交给Codex.md>) —— 交接过程稿；结果被 `2026-10-18_NodeUI返回白页修复` 及后续监听系列取代。**建议：归档**。
- [`reports/2026-10-18_NodeUI渲染收尾结果.md`](<reports/2026-10-18_NodeUI渲染收尾结果.md>) —— 自述「后续修正…以下保留 dev-… 历史」；返回白页问题另有修复报告。**建议：归档**。
- [`reports/2026-10-25_编辑器剪贴板修复_DP.md`](<reports/2026-10-25_编辑器剪贴板修复_DP.md>) —— DP 中间记录，最终交付见 `2026-10-25_编辑器剪贴板与全进程停止修复`。**建议：归档**。
- [`reports/2026-11-01_Shizuku改造指南审阅与实施方案.md`](<reports/2026-11-01_Shizuku改造指南审阅与实施方案.md>) —— 仅评审/方案；落地与验收见 `2026-11-01_Shizuku三引擎终端接入与局域网发布`。**建议：归档**。
- [`reports/2026-11-01_剩余对齐清单_dev1302.md`](<reports/2026-11-01_剩余对齐清单_dev1302.md>) —— 清单快照；dev-1303 发布后过期。**建议：归档**。
- [`reports/2026-11-02_MCP终端按需C与C++编译组件.md`](<reports/2026-11-02_MCP终端按需C与C++编译组件.md>) —— 文首「后续修订」：公网地址与退出修复见 `2026-11-02_C与C++公网下载及终端退出修复`。**建议：归档（保留历史时点）**。
- [`reports/2026-11-03_Node原生界面编译资源加载失败.md`](<reports/2026-11-03_Node原生界面编译资源加载失败.md>) —— 标题/正文自述「历史排查记录」，根因已修复。**建议：归档（保留根因供预防）**。
- [`reports/2026-11-03_MCP与MT功能实现对照及缺口.md`](<reports/2026-11-03_MCP与MT功能实现对照及缺口.md>) —— 文首说明是 dev-1325 实施前审查快照；核心差异已由 dev-1326 修复/发布。**建议：标历史；归档候选**。

### 5.2 同主题系列（不是简单重复；建议保留证据、合并摘要或归档旧版）

1. **听海私有工程（7 篇，2026-10-26～10-27）**：`听海NodeUI兼容修复` → `听海NodeUI性能与缓存修复` → `听海功能实现与原版接口接入` → `听海原版与Node示例功能对照` → `听海原版界面对齐与点击闪屏修复` → `听海运行卡顿与主题统一` → `听海播放器细节与律动`。后者逐版覆盖前者；建议保留 `听海播放器细节与律动`（1.2.2 最终）与 `听海原版与Node示例功能对照`（功能基线），其余 5 篇归档 `docs/archive/reports-tinghai/`，并在 INDEX 保留一行系列入口。
2. **Node UI 早期迭代（9 篇，2026-10-18～10-20）**：`NodeUI渲染收尾_交给Codex`、`渲染收尾结果`、`返回白页修复`、`长时间停留与退出`、`layoutXml_Widget修复`、`返回键语义`、`owner隔离双job`、`关闭清理窗口优化`、`关闭误报错修复`。当前应以 2026-10-31/11-01 的 Node UI 事件/监听系列为准；早期 9 篇建议归档（保留 1 篇时间线索引）。
3. **Ace 编辑器（6 篇，2026-10-11～10-12）**：保留 `2026-10-12_Ace标签切换与键盘唤起修复`（dev-888 最新）与 `2026-10-12_Ace功能对齐检查`；其余 4 篇（移植、升级、大文件、双版本布局）归档。
4. **MCP 逆向能力优化（4 篇，2026-11-04）**：`第一批`→`第二批`→`第三批`→`第三阶段（P1P2）` 是增量链，每批都有独立验收；**建议全部保留**，在 INDEX 主题内按时间读，不要合并正文（否则丢失逐批证据）。
5. **MCP 11 月用户反馈/交付链（9 篇，2026-11-04～11-06）**：`用户反馈第一批（UX1-5）`、`三个bug修复（dev-1337）`、`改进第二批（1338/1339）`、`改进第三批（1340→1344）`、`poker-recog 第一批（1356）`、`第二批（1359）`、`交付包清单（1360）`、`谷歌 OCR（1362）`、`多任务收口（1365）`。属于同一条用户反馈线，**全部保留**；后续可增加一页「MCP 2026-11 反馈链总览」作为入口（本页暂以时间顺序列出）。
6. **发布记录（约 79 篇文件名带 dev 版本号）**：这是逐版本证据链，**不是重复**。建议保留每个已发布版本的记录；未来可把 ≤ dev-1200 的旧发布记录归档到 `docs/archive/reports-release/` 并在本页保留一行索引，避免 reports 根目录继续膨胀。
7. **Materi-UI（2 篇，2026-10-26）**：`MateriUI适配运行`（首次跑通）与 `MateriUI原生兼容与dev1261`（宿主图标/资源 fallback，真正集成）——保留后者作为主入口，前者作为背景；可合并为系列。
8. **DP 任务交付（4 篇，2026-10-24）**：`DP任务1_M3首屏`、`DP任务2_Node冷启动`、`DP任务34_瘦身与正式构建`、`DP任务5_三引擎诊断`；最终验收以 `2026-10-24_五项优化任务与验收` 为准。建议保留五项验收为入口，DP 4 篇标为过程证据（归档候选）。
9. **编辑器剪贴板（2 篇，2026-10-25）**：见 §5.1 第 12 条；保留最终版。
10. **象棋 2026-09-20 系列（12 篇）**：以 `2026-09-20_差分视觉与卡点四修` 为主报告，其余是分项证据；不建议删除或合并（识别链调试依赖这些原始数据），只在 INDEX 主题内按日期列。

### 5.3 入口文档陈旧/重复（部分已加横幅；建议进一步精简/标注）

- [`docs/README.md`](README.md)：**旧索引正文**（最新只到 dev-1299），但 2026-11-06 整理时已加「状态页去过期」横幅并指向 AGENTS/ARCHITECTURE/REPO_CONVENTIONS/INDEX。建议下一步把正文压成短页（只留 API 文档入口），旧内容归档。
- [`docs/PROJECT_STATUS.md`](PROJECT_STATUS.md)：数字停在 dev-1259，已加去过期横幅（指向 AGENTS 顶部）。保留横幅即可，当前状态不要引用此页。
- [`docs/HANDOFF_AI.md`](HANDOFF_AI.md)：主交接仍有效，已加去过期横幅；§4「当前状态」仍写 dev-1177，建议只更新 §4/顶部发布链，不要重写 §3 架构。
- [`docs/HANDOVER.md`](HANDOVER.md)：旧交接（dev-1112），与 HANDOFF_AI 重复且没有去过期横幅；建议归档或合并进 HANDOFF_AI 的「历史」节。
- [`docs/MCP_SCRIPT_SERVICE.md`](MCP_SCRIPT_SERVICE.md)：文首自述「早期实现、工具数量和引擎范围已过期」；应合并进 `guides/MCP连接指南.md` 或加「历史」横幅。
- [`docs/MIUIX_PILOT.md`](MIUIX_PILOT.md)、[`docs/TUTORIAL_API_ALIGNMENT.md`](TUTORIAL_API_ALIGNMENT.md)、[`docs/PERMISSIONS_AND_RELEASE.md`](PERMISSIONS_AND_RELEASE.md)、[`docs/APK_SIGNING.md`](APK_SIGNING.md)：都只覆盖某一轨道/时点；保留但需以 AGENTS 顶部为准（PERMISSIONS 的 Rhino 版本漂移已在 2026-11-06 整理中修正）。
- [`docs/architecture/项目说明.md`](architecture/项目说明.md)：自述「历史架构记录（已归档）」但仍在 architecture/；建议移动到 `docs/archive/architecture/`。

### 5.4 清理候选（本轮未执行）

- `reports/chess-skin-eval/py/__pycache__/` 下 2 个 `.pyc`（约 32KB）是 Python 缓存，不应入库；建议在下次整理时删除并确认 `__pycache__/` 已被忽略。
- `reports/chess-skin-eval/py/cv-templates2-old-backup/` 是旧模板备份；若 `cv-templates3/` 已是最终版，可归档/删除旧备份（先核对评测脚本引用）。
- `reports/` 下 77 个非 Markdown 资产（PNG/CSV/JSON/PY）是报告证据，**不建议删**；建议未来按报告目录分组（如 `reports/chess-skin-eval/` 保持自包含）。
- 现有 `docs/archive/` 只归档了象棋 P4 探针；建议新增 `docs/archive/reports-superseded/`、`docs/archive/reports-release/`、`docs/archive/plans/` 三个约定目录（本轮不创建）。

## 6. 读文档的正确顺序（新接手 / 新任务）

### 6.1 第一小时（按顺序读，不要从 reports 第一页开始）

1. [`docs/guides/ARCHITECTURE.md`](guides/ARCHITECTURE.md) —— 10 分钟建立「模块 → 进程 → 引擎 → MCP → UI → 打包」全局图；比零散报告先读。
2. [`docs/guides/REPO_CONVENTIONS.md`](guides/REPO_CONVENTIONS.md) —— 备份、目录职责、跟踪策略、报告六段、只本地提交/只发 LAN、测试机 K40、构建由 Lead 独占。
3. [`docs/INDEX.md`](INDEX.md)（本页）§0～§2 —— 知道 docs 放什么、历史报告怎么分类。

### 6.2 第一小时之后（按需）

4. [`AGENTS.md`](../AGENTS.md) —— 仓库根，先读顶部发布记录与「踩过的坑」：当前版本/测试机/构建/发布雷区，事实优先级最高。
5. [`docs/HANDOFF_AI.md`](HANDOFF_AI.md) §0～§3 —— 一句话定位、仓库地图、主架构（冻结版）；**§4 状态写 dev-1177，跳过**，状态看 AGENTS 顶部和最新报告。
6. **最新状态（2～3 篇）**：`reports/2026-11-06_谷歌OCR示例报错修复与K40测试机（dev-1362）.md`（AGENTS 顶部当前发布）+ `reports/2026-11-06_多任务收口_apksigV3与对齐与工作区回收（dev-1365）.md`（更新但 AGENTS 顶部未列，发布状态以 AGENTS/最新发布记录为准）+ 其前置 `按交付包落实MCP改造清单（dev-1360）`。
7. [`docs/REMAINING_WORK.md`](REMAINING_WORK.md) §0 —— 已知剩余项（注意它更新到 dev-1330，之后的变化看新报告）；`PROJECT_STATUS.md` 只当更旧的历史快照。
8. 构建/发布： [`docs/guides/编译指南.md`](guides/编译指南.md)（工具链/JDK17/AOT/构建变体）+ AGENTS「踩过的坑」；需要发布命令时先看 `tools/publish.ps1 -DryRun` 与 REPO_CONVENTIONS §六。
9. 框架细节： [`docs/architecture/QUICKJS_ENGINE.md`](architecture/QUICKJS_ENGINE.md)、[`ENGINE_FLAVORS.md`](architecture/ENGINE_FLAVORS.md)、[`QUICKJS_MIGRATION_MATRIX.md`](architecture/QUICKJS_MIGRATION_MATRIX.md)（三者都需注意 2026-10-19 时效说明；Node24 是后加的第三引擎）。
10. 写脚本/查 API： [`guides/API_REFERENCE.md`](guides/API_REFERENCE.md)、[`guides/NODE_UI_API.md`](guides/NODE_UI_API.md)、[`guides/Node24基础自动化API.md`](guides/Node24基础自动化API.md)、[`guides/Node24控件选择器API.md`](guides/Node24控件选择器API.md)。
11. 用 MCP/逆向： [`guides/MCP连接指南.md`](guides/MCP连接指南.md)、[`MCP逆向调用说明.md`](MCP逆向调用说明.md)，以及随包 `apps/app/src/main/assets/mcp/AIjs-Reverse-Guide.md`（docs 外但最新）。
12. 做具体主题：先看 §4 对应主题的**最新 3～5 篇**；只有需要溯源时才按日期往下读。不要顺序通读 457 个 reports。

### 6.3 不建议的读法

- 不要用 `docs/README.md` 当当前索引（旧到 dev-1299）；不要从 `PROJECT_STATUS.md`/`HANDOVER.md` 判断现状；不要把历史报告里的数字当成当前性能/通过结论。
- 不要先读 2026-09 的架构/移植长文再上手；当前实现以 `guides/ARCHITECTURE.md`（2026-11-06 基线）+ AGENTS 顶部为准，旧 `architecture/` 文章注意文首时效说明。

## 7. 维护约定（以后怎么加）

1. **命名**：报告 `docs/reports/YYYY-MM-DD_主题.md`（不带版本号，版本写正文）；方案 `docs/plans/...`；长期手册 `docs/guides/...`。
2. **发布后**：按 `REPO_CONVENTIONS.md` §五，在 `AGENTS.md` 顶部加一条（改了什么+证据+SHA），并在本页 §4.4 对应主题加一行（日期/标题/一句话/链接）；本页 §3 的「最近 20 篇」滚动更新。
3. **报告必备六段**：现象 / 证据（命令与原始输出）/ 根因 / 修复 / 验证（真机+单测）/ 边界与未做；没有真机证据不要写「已验证」。
4. **重复处理**：新报告若取代旧报告，在旧报告顶部加一行「已被…取代」并登记到本页 §5；确认归档时再移动到 `docs/archive/`（移动前在本页留索引）。
5. **分类变更**：主题规则改动请在本页 §2.5 说明；同一篇报告只进一个主题，系列报告保留多条并在 §5.2 登记。
6. **统计更新**：本页 §1 的数字由 docs/ 目录实时统计生成；大整理后重新统计并更新快照时间，不要手工改明细数字。

## 8. 快照说明与已知差异

- 快照时间：2026/11/6 16:12:35；口径是 `docs/` 磁盘文件（含未跟踪/未提交文件，不含本页 INDEX.md）。整理任务在本页生成期间仍在修改根目录状态页/新增报告，本页 §1 已按最终磁盘重算；后续并发变更以 AGENTS 顶部和最新报告为准。
- 本轮**只新增 `docs/INDEX.md`**，没有移动/删除/改名任何文档，也没有改代码、文档其它文件；`.artifacts/` 下的统计中间产物被 gitignore。
- 并发差异：同批整理任务产出的 `docs/guides/ARCHITECTURE.md`、`docs/guides/REPO_CONVENTIONS.md`、`2026-11-06_死代码与冗余资产盘点.md` 均已出现在统计中；根目录 `README/PROJECT_STATUS/HANDOFF_AI` 也已在 2026-11-06 加「状态页去过期」横幅（统计按最终文件大小）。
- 状态差异：`AGENTS.md` 顶部最新到 dev-1362；`HANDOFF_AI.md` 顶部到 dev-1352；`PROJECT_STATUS.md` 到 dev-1259；`docs/README.md` 到 dev-1299；`reports/2026-11-06_多任务收口...dev-1365` 报告晚于 AGENTS 顶部，发布状态以最新发布记录为准。
- 报告里的「真机通过」都带当时设备/版本（多为 MI8，2026-11-06 起切 K40）；换机/换版本后必须重跑，不能直接引用旧结论。

---

> 本索引由文档整理任务生成；统计脚本只读扫描 `docs/`，分类/摘要为启发式，发现错分请在下次维护时调整本页 §2.5 规则并重新生成。
