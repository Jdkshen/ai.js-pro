# AGENTS.md

> **2026-11-08 按 `.4`《总报告（218/218 冒烟）》修 4 条（dev-1392，已发 LAN）**：[报告](docs/reports/2026-11-08_按设备4MCP更新建议v4再迭代（dev-1387-1388）.md) · [回复稿 v3](output/MCP-FEEDBACK-REPLY-dev1392.md)（已推到 `.4`）。`.4` 在 01:42 又给出**218/218 工具全覆盖冒烟**，定位 1 个协议级 bug + 6 类一致性；本轮按他们优先级修完并真机复测：**P0-1（唯一协议级）** `coordinate_convert` 的 `result` 以前直接是 `{ok,data,error,nextActions}`、**没有 `content`**（该分支漏了统一包装）→ 改为 `toolJson(...)` + `isError`，实测 `result` keys = `content,structuredContent` ✅；**P1-E** 失败路径以前只回纯文本 → 新增 `toolFailure()`，`ToolError`→`TOOL_ERROR`、`IllegalArgumentException`→`INVALID_ARGUMENT`，全部「文本+结构化双写」（`isError/errorCode/structuredContent`），实测无效游标 → `errorCode=TOOL_ERROR` ✅；**P1-A** 返回结构多态 → workspace 成功统一 `{ok,data}`（data 内保留原字段）、`workspace_diff`→`data.diff`、`read_script`/`terminal_write`/`learn_autojs_skill` 补 `structuredContent`，**`content[0].text` 保留旧形状**（老调用方不断），内部异常给 `WORKSPACE_STATE_CONFLICT/PERMISSION_DENIED/IO_ERROR`；**P1-B** required 过严 → **38 个字段**移出 required 并补 default（`apk_read_text` 的 500/49152/0/0、`apk_native_list_items` 的 `""`/false/100 等），JSON 复扫 **`required_with_default=0`**，实测最小调用 `apk_read_text{workspaceId,editSessionId,locator}` 成功（17439 字符）；另修 `ToolkitNativePatches.replacementText` schema 可选但运行时报缺。同批还完成 v4 §10.3 **号表 string_const 兜底**（`apk_dex_interface_table` 新增 mode=auto|descriptor|string_const，实测真实接口 `transactSource=string_const+transact_const`、`isReady→code 1`、`evidence=writeInterfaceToken+transact`、`confidence=high`）、v4 §10.5 **terminal 组件缺失接清单**（`terminal_read` 顶层 `code=COMPONENT_MISSING` + `ready/missing/present` + `componentState` + `recovery` + 结构化 `nextActions`）、v4 §10.4 **目录变化通知**（`capabilities.tools.listChanged=true`；`initialize` 给 `_meta.aiJsProToolCatalog{previous,current,changed}` 并在 `instructions` **首行**插 ⚠️ 提示——实测 1390→1391 首次 `changed=true`、第二次 `false` 不刷屏；**踩坑：`instructions` 是单字符串不是数组，且 `serverInfo` 在 initialize/listTools 两处都有，锚错位置会插到 listTools**）。**MCP 单测全过**。**未做**：工具分级/tool filter、整族前缀重构、`dev_screen_diff`、`chain_resume`、`crypto_`、`entry_`。

> **2026-11-08 按 `.4`《AIjsPro-MCP-更新建议》落地 8 项（dev-1386，已发 LAN）**：[报告](docs/reports/2026-11-08_按设备4MCP更新建议落地（dev-1386）.md) · [回复稿](output/MCP-FEEDBACK-REPLY-dev1386.md)（已推到 `.4` 的 `脚本/MCP测试/`）。`.4` 用 dev-1382 实战后写了带「现象/复现/期望」的更新建议，本轮按**他们给的优先级**逐条落地并真机复测：**P0-1** 会话→`SESSION_EXPIRED`/工作区→`WORKSPACE_EXPIRED`，结构化细节提升为 **error 一级字段**（`details/recovery/ttlRemainingMs/workspaceId/reason` + **顶层结构化 `nextActions`**），`artifact_open` 同 path+sha256 幂等复用/续期；**P0-2** `get_build_tools` 顶层 `ready/missing/present` + `code=COMPONENT_MISSING`（缺 python3/aidl 给两条 recovery，明说 `apk_component_install` 装不了它们）；**第六章基线自检** `get_status.baseline`（root/currentProject/outputRule/workspaces/toolchain/backends/jobs，≤512B，`version` 由 digest 驱动，不新增往返/不写盘）；**P0-3** `apk_search` 增 `stopReason/complete/budgetExhausted/partialTargets/zeroResultMeaning`，只有 `COMPLETE` 的 0 命中才 `emptyResult`；**P0-4** `apk_job_result{format:"text"}` 顶层直接给正文 + `suggestedExportPath` + `exportHint`；**新工具** `apk_dex_interface_table`（AIDL/Binder 事务号表 + Stub/Proxy 两侧对照 + `transactSource` 标注编号来源）、`apk_dex_list_classes`、`apk_dex_list_methods`，`apk_dex_xref` 增 `includeImplementations/includeOverrides`；**P1-5** 收窄 `SUCCEEDED_WITH_WARNINGS`（JADX INFO/全局计数只进 notes）；**P1-6** **根因修** `ToolkitBackendRouting`（`sh build.sh`→BOTH、`sh -c "pm …"`→SHELL_ONLY）；**P1-7** app 后端查进程给 `processVisibility`。**K40 dev-1386 实测**：工具 **218**、baseline 全字段（采样后 toolchain 填充、version 1→2）、`WORKSPACE_EXPIRED` 带 `details`+结构化 `nextActions`、`SESSION_EXPIRED`、`stopReason=BUDGET`+`zeroResultMeaning=UNKNOWN_BUDGET_EXHAUSTED`、`format=text` 顶层正文+`suggestedExportPath`/`exportHint`、`apk_dex_interface_table` 在真实 APK 上 `transactSource=static_field` 并给出 `orderMismatches`（如 `back` 静态字段 14 / 声明顺序 1）、`apk_dex_list_classes{prefix:…}` 回 9 个 `IAutomationHost` 族类、`terminal_exec{sh build.sh,backend:app}` 不再误报需要 shizuku。**MCP 单测 602 项全过**。**Lead 集成修复**：provider 增加 `liftDetails()` 把 message 内 `details={…}` 提升为一级字段；新工具注册（catalog 206→209/215→218、分发、描述、JSON schema、`apk_job_result.format`、计数测试）；队友本地 kotlinc 过而 Gradle 不过的 4 类问题（`optString` 缺 `?.`、测试引号转义、Gson `JsonArray` 无 `isEmpty()`）；`waitFinal` 不认 `SUCCEEDED_WITH_WARNINGS`；裸 JSON 夹具被判「正文为空」→ `classifySuccess(data==null)` 不再造 warning。**未做**：工具分级/tool filter、整族前缀重构（`dex_/so_/entry_/chain_`）、`dev_screen_diff`/`chain_resume`/`crypto_`/`entry_`、P2-12 真验签（只核对 `apksigAvailable=true`/`schemes=[V1,V2,V3]`）。**环境**：过程中遇一次 AAPT2 daemon 崩溃 + Gradle 发行包/AAR 重下（网络瞬时），重跑即恢复，与代码无关。

> **2026-11-07 打包版日志桥 + 扩展日志心跳（dev-1385，已发 LAN）**：[报告](docs/reports/2026-11-07_打包版日志桥与扩展心跳（dev-1385）.md) · [开关位置调查](docs/reports/2026-11-07_打包版启动路径与日志桥开关位置.md)。用户问「为什么后面日志 VSCode 上不打印了」，并选择 **A+B 都做**。**A 打包版日志桥**：先扫 dex 证明两份打包模板**完全没有**调试通道（`DevPluginServer`/`DevPluginFrames`/`client_version`/`device_id`/`read_file`/`HostConsoleSink` 全 no，只有 `GlobalConsole`）→ 在 `apps/inrt` 内置 **log-only** 桥（`InrtLogFrames`+`InrtLogBridge`，473 行）：只在 **`:script` 进程**运行（脚本日志只进 `:script` 的 `AutoJs.globalConsole`）、只发 `hello`+`log`、**命令帧一律忽略**、默认**关闭**、端口 9317、bind 失败每 30s 重试；开关 = 打包项目 `project.json` 的 **`launchConfig.logBridge`**（打包页新增「VSCode 日志」开关，Dart 写；另有调试逃生口 `SharedPreferences: inrt_log_bridge_enabled`）。宿主侧打通：`LaunchConfig.logBridge`(engine) + `ApkBuilder`(AppConfig/解析/写产物/setter) + `PocPackaging`/`PocWorkspace` 默认值。**K40 真机验证**（用「打包版运行时」=inrt 模板包，链路同源）：logcat `log bridge enabled by assets/project/project.json launchConfig.logBridge` + `log bridge started (log-only) port=9317`；`ss -tlnp` 显示 9317 归属 **`pro.inrt:script`**；自建协议客户端收到设备 hello（`server_version=3`）+ **15 秒 15 条**连续日志（`BRIDGE_TICK 2..16`）；**真实 VSCode 扩展**握手成功（`app 1.0.2，协议 v3，678ms`）；模板 guard 0；dev-1385 内置模板 `19,811,683B/47D9C4EE…` 与重建模板逐位一致。**B 扩展心跳（0.3.7）**：`logHeartbeat.ts`（纯逻辑）+ `aijspro.logStatus` + `aijspro.logHeartbeatSeconds`（默认 30，0=关），静默时输出 `[设备] ⏱ 链路正常，已收 N 行；脚本静默 45s`、恢复时 `▶ 日志恢复`；typecheck/build/package 全 0 → **aijspro-vscode-0.3.7.vsix 已装本机**；单测 7/7（`npm test` 默认 home 57/58 是既有 `projectRoot` fixture 问题，干净 home 58/58）。**踩坑**：受限 JVM（`UseSerialGC`+`TieredStopAtLevel=1`）会让 `:inrt:dexBuilderRelease` 报 **`Out of space in CodeCache for adapters`** → **重建模板必须清空 `JAVA_TOOL_OPTIONS`**；`adb forward` 本地会先接受连接，不能用来判断设备端口是否在监听（要用 `ss -tln`）。**未做**：真实打包 APK 端到端（需宿主打包器/UI，本轮用等价的 inrt 运行时验证）、心跳行现场目视（**要重载 VSCode 窗口**才加载 0.3.7）、打包页开关真机目视、桥与 `DevPluginServer` 的帧编解码重复约 100 行未抽公共模块。

> **2026-11-07 修 `getClass().getName()` 回归（dev-1383/1384，已发 LAN）**：[报告](docs/reports/2026-11-07_修getClass回归（dev-1383）.md)。**用户打包版崩**：`InflateException: ... Java class "…JsLinearLayout" has no public instance field or method named "getName"`（`__ui__.js:408` 的 `view.getClass().getName()`）。**根因**：dev-1380 为支持 `Packages.<类>.class` 新增的 `AijsNativeJavaClass` 把**所有 Class 对象**都包成 `NativeJavaClass`（只解析**静态**成员），而 Rhino 原生把 `getClass()` 返回的 Class 包成 `NativeJavaObject`（Class 的**实例 API** 可见）→ `getClass().getName()/getSimpleName()/isArray()` 全挂。**影响 dev-1380/1381/1382**。**修法**：静态成员优先（避免抢走 `Array.newInstance` 这类与 `Class.newInstance` **同名**的静态方法——单测实测过），仅当属 `java.lang.Class` 实例 API 且本类无同名静态成员时先走实例语义，并保留静态找不到时的 Class 实例回退；`has/in` 同步。**K40 实测 13 项全过**：`view.getClass().getName()=android.widget.LinearLayout`、`getSimpleName`、`isArray`、`Packages.java.lang.String.getName()`、`.class.getName()`、`valueOf(123/1.5)`、`Integer.MAX_VALUE`、`Array.set/get=5`、`RomUtil.getName()`/`PFiles.getName(path)` **静态方法未被遮蔽**、**真实 UI 路径 `ui.inflate` 成功建 `JsLinearLayout`（不再 InflateException）**；引擎单测 7 项、MCP 单测全过。**⚠️ 关键发现（队友只读调查 `docs/reports/2026-11-07_打包版引擎来源与模板新旧.md`）**：独立 APK 的 engine dex 与 `assets/modules/*.js` **来自 `template.apk` 预置副本**（`ApkBuilderPluginHelper.java:74-103`、`ApkPackager.java:51-65`），不是当前 app 动态复制 → **光升级 app 修不了旧包**。处置：重建宿主瘦身模板（`08838A9D…`→`8B5F1009…`，18.9MB，dex 含 `classInstanceMember/CLASS_API_NAMES/staticMemberNames` 铁证）+ 插件模板（88,424,956→88,438,580）；`apps/inrt/build.gradle:129` 直接编 `:engine` 源码；**dev-1384 已把新模板打进 app** 且验证设备内 `assets/template.apk` 与仓库模板逐字节一致；K40 上优先级最高的老 `/sdcard/template.apk`（10-18）已可逆改名为 `.old-20261107`。**结论：必须用 dev-1384+ 重新打包**（旧包无法自愈）。**同类隐患（未做）**：`AijsJavaStaticMethod` 的 int/Integer 同分、范围检查缺失、varargs；WrapFactory 的 `setJavaPrimitiveWrap(false)`、UiObjectCollection/View/Paint 特化；`has("class")`/`getIds`。

> **2026-11-07 产出自动归到项目目录（dev-1382，已发 LAN）**：[报告](docs/reports/2026-11-07_产出自动归到项目目录（dev-1382）.md)。用户诉求：「调 MCP 要能提醒 AI 不要到处掉、产出统一放项目文件夹、没有就建，不能每次靠人提醒」→ 做成**产品行为**。① `initialize.instructions` 加**强制规则**（产出必须落 `<root>/<项目名>/`，没有先 `project_folder{action:"create"}`，禁止根目录与 `tmp/out/test/__xxx`，**看到 `organized` 就说明写错位置**）；② 新工具 **`project_folder`**（`state`/`create`/`set`/`tidy`，`tidy` 默认 `dryRun`、只移动不删除）；③ **默认自动归置**：裸文件名/直接落操作根的输出自动进默认项目（无则自动建 `mcp-out-YYYYMMDD` 并持久化），返回 `organized{from,to,projectDir,reason,label}`；显式子目录与 `organize:false` 不动；已接 **23 个**产出工具（`file_*`/`capture_screen`/`evidence_export`/`apk_build`/`apk_*_project`/`apk_inject_gadget`/`zip_create`/`zip_extract`/`apk_job_export`…，`zip_update` 原地修改有意不归置）；④ `get_status`/`toolkit_status` 暴露 `outputConventions`；⑤ `file_move.overwrite` 假必填放宽。**K40 实测**：裸名 `file_write_bytes`/`apk_job_export`/`capture_screen` 都自动进 `MCP测试/` 且带 `organized`；显式子目录/`organize:false` 不动；`tidy` dryRun→真移动 ✓；`tools/list` **215**、`serverInfo=dev-1382`。现场还把脚本根目录原有 **4 个垃圾目录 + 5 个散落 APK（≈435MB）** 搬进 `MCP测试/`，根目录恢复干净。**Lead 合并期修**：C/A 接口分叉（加兼容重载）、KDoc 里 `apk_*/zip_*` 的 `*/` 提前结束注释导致语法错误、`transform` 未接 `organized`、`outputConventions` 两份实现合并、4 处 schema 补 `organize`、10 个单测因归置失败（测试统一 `organize=false`）。**MCP 单测全过；dev-1382 = SHA `7a625190413e84c9…`**。**未做**：`.4` 文档推送（设备离线，待上线补推）、脚本内 `files.write` 不经 MCP 归置、显式 `__xxx` 子目录不受归置（设计取舍，靠 instructions+tidy 兜）。

> **2026-11-07 按 `.4`《使用手册 + B1~B18》收口 13 条（dev-1381，已发 LAN）**：[报告](docs/reports/2026-11-07_按设备4使用手册B1-B18收口（dev-1381）.md)。`.4` 新增他们基于**真实批跑**（510 类反编译 + 240 smali）写的 `MCP-AIjsPro-总结-dev1378.md`（使用手册 + bug 清单 B1~B18）。K40 dev-1381 实测：**B14** `initialize`/`tools/list` 都回 `serverInfo{name:"AI.js Pro MCP",version:"dev-1381",versionCode:1381}`；**B8** `timeoutSeconds=601` 回「MCP 等待上限不是脚本运行上限；Node path 脚本 1h」；**B6** 旧工作区 → `WORKSPACE_EXPIRED`；**B5** 定位符错 → `LOCATOR_NOT_FOUND`（+JADX 侧三类细分）；**B16** AXML 命中给 `readLocator:"zip_entry:…"`；**B2** 空内容 → `EMPTY_RESULT`+`emptyResult` 且**作业状态提升 `SUCCEEDED_WITH_WARNINGS`**（用他们给的 `Core$MinMaxLocResult` 复现）；**B1** `effectiveConcurrency=1/per-workspace`；**B4** `by-locator.index.json` + `latestForLocator/latestByLocator`；**B10** `apk_job_list` 过滤/分页（实测 `total=24/nextCursor=jobs1-6`）；**B7** Node 池改 3 槽+5 排队+30s（JVM 过，真机并发未测）；**B3** `RESULT_TRUNCATED`（单测过，真机因 jadx 先超时未复现）；**B9/B11/B12/B13/B15/B17/B18** 随包文档 141 行。**Lead 接线**：`ToolkitJobs` 把 `data.warningCode` 提升为 `SUCCEEDED_WITH_WARNINGS`。**MCP 单测全过；dev-1381 = SHA `975787971a6add8e…`**。**更正**：他们手册说 `Packages…valueOf(42)`→`42` 已 OK，但 Rhino 上要 **dev-1380** 才成立（`.4` 的 dev-1378 实测仍 `42.0`）。**未做**：B3 真机/ B7 并发真机 / `JADX_CLASS_NOT_FOUND` 真机样本、`file_list.recursive` 假必填。

> **2026-11-07 四件事收口 + 16 KiB 归因更正（dev-1380，已发 LAN）**：[报告](docs/reports/2026-11-07_四件事收口与16KiB归因更正（dev-1380）.md) · [ELF 调研](docs/reports/2026-11-06_gadget-ELF-16KiB对齐调研.md)。① **Rhino `#15` 全修**：新增 `AijsNativeJavaClass`，**只经 WrapFactory 包装 Class**（不动 `Packages`/ClassCache，避免 dev-1377 全坏事故）。K40 实测：`console.log(1+1)=2`（引擎健康先查）、`Packages.java.lang.String.class.getName()=java.lang.String`、`valueOf(123)=123`（旧 `123.0`）、`valueOf(1.5)=1.5`、`Array.set(int[],0,5)=5`、`System.currentTimeMillis()` 可用。② 新增 **`apk_job_export{jobId,outPath}`**（结果原样原子导出、可分页；实测 81162 字符导出、`file_stat` 的 `targetVersion` 与工具 sha256 逐位一致），对 `.4` 那 32 条墓碑**找不回但以后不会再丢**；保留窗口维持 6h/64 条（评估结论见报告）。③ **更正 gadget 16 KiB 归因（我们之前说错了）**：那 5 条 `ELF_PT_LOAD_ALIGNMENT_16K` 来自**目标 APK 自带的** `libroot_automator.so`(3 段)+`libyj_aj_02_309.so`(2 段)——未注入原包单独验签同样 5 条，注入的 64 位 Gadget 3 段全通过；`partialReasons` 现逐条带 `entry/scope(target|injected)/owner/upstream/impact`（实测 5 条全 `scope=target`），note 与随包文档已改写，两份旧报告追加更正小节；**不改 Gadget 组件**（官方 64 位合规，32 位不合规但 16K 设备是 arm64；两台测试机都是 4096 页）。④ 目录计数 204/213 → **205/214**（新工具进 `McpToolkitCatalog`）、`apk_job_result.maxChars` schema 上限 → 1MiB。**MCP 单测全过；dev-1380 = SHA `3216169554dc79ad…`**。**教训：结构化诊断缺 `entry` 时归因等于猜**。**新发现**：`file_list` 仍要求 `recursive`（下批清）。

> **2026-11-06 再看 `.4`：证伪「升级清空结果」+ gadget partial 归因上游（dev-1379，已发 LAN）**：[报告](docs/reports/2026-11-06_再查设备4并归因job淘汰与gadget-partial（dev-1379）.md)。`.4` 已升 **dev-1378** 并写新主文档 `MCP-AIjsPro-功能与遗留问题-dev1378.md`：**第十节确认我们的 16K 修复生效**（`V8` 类导出 47,352 字节，旧 16,384）；**第九节怀疑「升级后 jobs 结果全没了」——Lead 在 `.4` 上取证证伪**：`apk_job_list` 回 **32 条墓碑 `JOB_EVICTED/retentionLimit`**，`retention={6h, maxRetainedFinished:64}` 且当时正好 64 finished + 3 活跃 → **是保留策略淘汰，与升级无关**（`ToolkitIo.version` 稳定、代码无"升级清库"）。据此收口：① `get_status.jobs` 新增摘要（K40 实测 `total 26/finished 26/evictedCount 3/lastEviction.reason=retentionWindow` + `retention` + `note`），`apk_job_list` 新增 `retentionNote`/`nextActions`，淘汰报错翻成可读并区分 `JOB_EVICTED` vs `SERVICE_RESTARTED`；② **gadget `partial` 归因上游**：`ToolkitGadget:62/:108` 原样拷贝官方 Frida 17.25.2 Gadget（组件清单自述 unmodified official），故 `partialReasons[]` 加 `upstream:true`/`impact:"static-check-only"`、`note` 追加可执行说明；③ 随包文档 36 行（结果保留策略与留档约定、`detail=summary`/`partialReasons` 排查）。**MCP 单测全过；dev-1379 = SHA `035443f87e89ba0c…`**。**仍未做**：#15 `.class`、Rhino 整数精度与 `Array.set`、gadget `.so` 16KiB 对齐（上游产物）、`apk_job_result` 显式 maxChars schema 上限 200000。

> **2026-11-06 拉 `.4` 真实样本 + 按 dev-1374 回归收口（dev-1378，已发 LAN）**：[报告](docs/reports/2026-11-06_拉设备4真实样本并按dev1374回归收口（dev-1378）.md)。扫 `.4` 脚本目录发现**新主文档 `MCP-AIjsPro-功能与遗留问题-dev1374.md`**（他们做的 dev-1374 回归：确认我们 **#12/#13/#14/#16/#17/#19/#21 全已修**，含真实 `Interpreter.interpretLoop` → `SUCCEEDED_WITH_WARNINGS/DECOMPILE_INCOMPLETE`、真实 Auto.js 包 → `providerProcess=":script"`）与**云镜样本 APK**。① 按用户要求把 `云镜快照样本-原件.apk`（7.8MB）用 MCP 分块拉回 PC（**sha256 `ffe1365a…` 与设备一致**，7.3s）并 push 到 K40，用它真实复测上述两条 ✓。② 修 `apk_inject_gadget` 无瘦身开关 → `detail=summary`（**42KB→480 字符**）+ **`partialReasons[]`**；实测点出 partial 真因 = native 检查 **5× `ELF_PT_LOAD_ALIGNMENT_16K`**（注入的 gadget `.so` 不满足 16KiB 页对齐，**新发现待修**）。③ 修「**>16K 反编译结果拿不全**」：`apk_job_result` 默认窗口 16384→**1,048,576**（实测一次取回 **81,162 字符**），`apk_decompile_*` 缺省 `maxChars=200000`。④ 随包文档 80 行（`outputName` 子目录/`outPath` 父目录/#14 Rhino 复现步骤/JADX 改名类绕法/providerProcess 真实样本证据）。**⚠️ 教训：dev-1377 曾把 Rhino 的 `Packages` 换成自定义类以修 `.class`，导致 Rhino 在任何脚本上都报 `Can't find top level scope for ClassCache.get`（引擎全坏、单测全绿、未发布）；dev-1378 已回退**并回归验证 `abs=5`/`catch=UnsatisfiedLinkError`/Node `Array.set=5` 均正常；**改 Rhino 包/作用域对象后必须先在设备跑 `console.log(1+1)`**。**MCP 单测全过；dev-1378 = 138.11MB / SHA `413218aa425cea08…`**。**仍未修**：#15 `.class`/任意类静态方法（用 `Class.forName` 代替）、Rhino 整数精度（`valueOf(42)`→`42.0`）、Rhino `Array.set`、gadget `.so` 16KiB 对齐。

> **2026-11-06 假必填清零 + 工作区 GC 真修（dev-1376，已发 LAN）**：[报告](docs/reports/2026-11-06_假必填清零与工作区GC真修（dev-1376）.md)。① **「假必填」清零 14 个字段**：`file_search`(5)/`file_search_text`(2)/`apk_search`(6)/`apk_list`(3)/`apk_list_available_apks`(2)/`apk_native_patch_string`(1)/`file_replace_text`(2) 全部移出 required 并写入 schema default；59 个 APK schema 扫描 **`required_with_default=0`**。K40 实测最小调用：`apk_list_available_apks{}`(18 候选)、`apk_list{workspaceId,editSessionId}`(7 条)、`apk_search{workspaceId,editSessionId,query}`(默认 target=overview)、`file_search{path,recursive,namePattern}`(截断给 `scanCursor`)。② **修真实故障 `apk_workspace_gc` 永久回收不了临时快照**：根因是任务重启恢复**只改内存不回写元数据**（元数据仍 RUNNING/QUEUED），且墓碑分支**完全不清理陈旧元数据**，叠加 `ToolkitWorkspaceInventory:173` 一刀切保护 → 一个死任务挡住全部临时工作区（K40 积压 10 个快照 **1.0 GiB** 一个都删不掉）。修：恢复时 `persist(job)` 写终态 + 墓碑分支删陈旧元数据 + `scanActiveJobs()` 加 mtime 兜底（>3600s 未更新视为陈旧）。**K40 实测：activeJobs 2→0，wouldDelete 0→10，实删 10 个回收 1,015,061,349 字节（≈968 MiB），配额回 16**。③ `apk_dex_search_members` 提示拆分：`stopReason=limit` → `limitHint`（明说"是按你传的 limit 截断，不是预算用尽"），只有 `scan_budget/time_budget` 才给 `budgetHint`+`nextActions`。**MCP 单测全过；dev-1376 = 138.1MB / SHA `05f70a786cb18b9d…`**。**未做**：`#17` 真实超限方法复测、GC 仍非「按 job workspaceId 精确保护」、工作区自动回收、file_search 内容预算不可续扫。

> **2026-11-06 扫 `.4` 全部 md + 按 dev1371 回归修 5 条（dev-1374，已发 LAN）**：[报告](docs/reports/2026-11-06_扫设备4全部md后按dev1371回归修5条（dev-1374）.md)。递归扫 `/storage/emulated/0/脚本` 得 **299 个 md**，筛出 **23 份 MCP/云镜相关**（4 份新：`MCP-AIjsPro-功能与遗留问题-dev1371.md` 主文档、`云镜快照-成果/{说明,对照表}.md`、`MCP_workspace新增问题.md`(历史·已全修)）。按主文档第七节「6 条仍未修」收口：① **#14 反射传 int 被装箱成 Double**（他们标最值得改）→ 三引擎按目标形参收窄，K40 实测 `Math.abs.invoke(null,5)`=**5**、溢出报「Java 反射参数 #1 超出 int 范围：2.147483648E9」；② **#16 Java Error 穿透 try/catch** → Rhino `AndroidContextFactory.hasFeature` 只在 Java 调用边界启用 feature 13，实测 **CAUGHT class=java.lang.UnsatisfiedLinkError**、去掉 catch 仍 FAILED；③ #17 jadx 不完整 → `decompileIncomplete/incompleteReason/nextActions` + `SUCCEEDED_WITH_WARNINGS/warningCode=DECOMPILE_INCOMPLETE`（**未用真实超限方法复测**）；④ #19 dex 搜索超预算 → `budgetUsed`+`budgetHint`+`nextActions`；⑤ #21 `file_search` 递归预算 → **`scanCursor` 续扫**+`scannedEntries/stoppedAt/hasMoreScan`（实测 `scannedEntries=792/timeBudget/hasMoreScan=true`，改 pattern 传旧游标被拒）。另：从 `云镜快照-成果/对照表.md` 沉淀两条——gadget 对 Auto.js 打包包要进 **`:script`**（我们新增独立 Provider、不复制原条目；`providerProcess` 自动判定实测 `auto=:script`）、Frida 17 取 `RegisterNatives` 符号与 JNI 表扫描校验（已写进 Playbook）。**构建期踩坑**：队友在描述里写 `android:process=":script"` 未转义 → `ToolkitReverseTools.kt:21` 语法错误编译失败，已修。**MCP 单测全过；dev-1374 = 138.1MB / SHA `27b7f53c058f759b…`**；`.4` 文档再推增量。**未做**：#17 真实样本复测、#16 的 OOM/ThreadDeath 排除、`file_search` 5 个 type 字段仍 required。

> **2026-11-06 按 `.4` 实战文档再修一批（dev-1373，已发 LAN）**：[报告](docs/reports/2026-11-06_按设备4实战文档再修MCP（dev-1373）.md)。把 `.4`（已升 dev-1371）20 份 MCP md 重读，抽 12 条剩余诉求落地 8 条：① **`app.viewFile(path,{fresh:true})`**（poker P0-3 要的"解法"）——唯一副本打开，Node/Rhino 实测两次 `viewerKey` 不同、`fallback:false`，目录路径如实 `fallback:true`；**踩坑：Rhino 里 JS 覆盖 Java 方法会抛 `Java method "viewFile" cannot be assigned to`，删掉 `__app__.js` 的包装改用 `AppUtils.viewFile(String,Object)` 重载才对**（只有真机能发现）。② **`apk_inject_gadget`**：script 模式不再有 endpoint/token（实测无这两个字段）、`nextActions` 清理；新增 **`providerProcess`**（实测显式 `:remote` → `source=explicit`；省略按入口 activity 的 `android:process` 自动判定）；失败但产物已写出 → `partial:true` 结果而非丢产物。③ **`search_scripts` 可续扫**：`scanCursor`（不透明+代次校验），实测 284→396 条，篡改即报「扫描游标失效」。④ `shell_capabilities` 增 `pm_install_fuse_storage`（/storage 被拒 → 先 `cp /data/local/tmp`）。⑤ `apk_build` 增 `detail=summary`。⑥ 随包文档补 6 节（floating_window 坐标系、`apk_read_text(dex_method)` 读 smali、Java Error 不被 JS catch、`System.load` 绝对路径、改打包版应用资源、pm install FUSE）+ 更正 `matchTemplateMulti`「未接入」过期说法（源文档 + **API 索引重建 783 条**）。**MCP 单测全过；dev-1373 = 138.09MB / SHA `6926739215202ebd…`**。**未做/边界**：`providerProcess` 的 manifest/默认分支只本地 harness 验证、失败产物 catch 无法自然复现、QuickJS global `app.viewFile` 可能仍旧行为（C++ global 不可写）、`dex→smali` 回退开关未做。

> **2026-11-06 修三个不一致 + 清「假必填」 + 同步 `.4` 文档（dev-1371，已发 LAN）**：[报告](docs/reports/2026-11-06_MCP参数放宽与路径白名单与设备4文档同步（dev-1371）.md) · [回复稿](output/feedback-dev1365/MCP-FEEDBACK-REPLY-dev1371.md)。
> ① **路径策略**：`native_analysis_open` 加只读允许根白名单（`/system`、`/vendor`、`/apex`、`/data/local/tmp`、`/storage/emulated/0` + 工具操作根），之外报 `ANALYSIS_PATH_FORBIDDEN`，逃生口 `allowOutsideOperationRoot`（默认 false，**Lead 已接线** `AndroidReverseRuntime`）；`artifact_fingerprint`/`file_stat` 仍是工具目录策略（有意保留），提示改准。② **`ANALYSIS_STALE_INPUT` 给完整 64 位** expected/actual（K40 实测可直接重试成功）。③ **6 个 zip 工具的 `password` 改可选**（实测从 APK 抽出 `libquickjs_jni.so` 1,120,408B）。④ **「假必填」清理**：`file_read_text`(只需 path)/`file_read_bytes`/`file_list`/`file_search`/`file_search_text`/`file_discover_apk`/`zip_list`(只需 path)/`zip_read_text`/`zip_read_bytes`/`apk_resource_read`/`apk_search.prefix`/`apk_build.outputName` —— schema 与运行时缺省同步（`McpZipToolkit` 新增 `optionalInt/optionalLong(4参)` 助手）。⑤ **`.4` 文档**：20 份 MCP md 全读，5 份更新并**推回设备**（sha256 逐份一致）、2 份追加「dev-1369~1371 增量」、13 份历史不动；两份 REPLY 只追加不改历史。⑥ 随包文档：Guide 增「dev-1368 行为变化」12 行表，Playbook/Reverse-Guide 更新 matchMode/password/工具数（170→**213**）。**MCP 单测全过、dev-1371 构建成功并发布 LAN**（138.06MB，SHA `7a5114524a9bfc…`）。**未做**：`file_search` 的 5 个 type 字段、`apk_search` 的 8 个必填、`zip_extract.overwrite`；`.4` 仍是 dev-1365（文档已更新、行为待升级后生效）。

> **2026-11-06 按 `MCP反馈-AIjsPro-dev1365.zip` 修第三批（dev-1368，K40 已装机）**：[报告](docs/reports/2026-11-06_按dev1365反馈修MCP第三批（dev-1368）.md) · [回复稿](output/feedback-dev1365/MCP-FEEDBACK-REPLY-dev1368.md)。
> 反馈 = **11 条 bug + 6 项缺功能 + 13 条描述补丁**（dev-1360 提的补丁到 dev-1365 仍未落实）。四路队友 + Lead 一轮收口，**11 条全部实现并在 K40 上按用户复现动作复测通过**：① `withLogs` 改 boolean（**并审计出同类 4 处**：`dryRun/protectApplied/pid/keepLatest`，新增 `McpToolSchemaParamsTest` 覆盖 Kotlin set/内联 field/`num·str·bool` 助手/JSON 资产**三处声明来源**）；② `native_analysis_open` 真校验 sha256 → `ANALYSIS_STALE_INPUT`（实测全 0 哈希被拒）；③ native `close` 归档 + 同 sha 重开恢复（实测 `restored=true/functionCount=1`，修复前 0）；④ 6 个会话工具返回 `revision/revisionChanged`（实测 edit→`2aa88945…`→checkpoint 成功）；⑤ locator 报错带四前缀+示例；⑥ `apk_search overview` 真执行（实测 9 个 target + 逐 target 命中数）；⑦ `matchMode=literal|literalWord|regex`（实测搜 `cript`：literal=77 vs literalWord=0）；⑧ `terminal_*` 加 `JOB_NOT_FOUND/JOB_EVICTED/SERVICE_RESTARTED`+retention/evicted/`ownershipEnforced:false`；⑨ `file_access_diagnose` → `STAT_ONLY`+`probeStatus`；⑩ `ToolkitIo.id(label)` 修 jobId 文案串位；⑪ frida 失败分类码 + job 层暴露 `errorCode`（实测 `COMPONENT_NOT_INSTALLED`）。**Lead 接线**：`terminal.listReport()`、`ToolkitException`→MCP `errorCode/argument`、frida catch 分类、`ApacheTerminalJobs` 构造函数 `@JvmOverloads`（否则 Java 调用方编不过）。另验看图器：两次 `app.viewFile` 的 URI `?v=4-…`→`?v=10-…`。**541 项 MCP 单测全过、dev-1368 构建成功**；提交 `8b4ec453`/`8632dc40`。
> **已发布 LAN dev-1368**（138.06 MB，SHA `8c2b170e573b64cc…`）。补测完成：`apk_build_project` 真回编成功（复制模式 `copyMs=71`）；`buildInPlace:true` → `stagingMode=in_place / copyMs=0 / sourceTreeUnchanged=true`（前后工程树哈希一致）。踩坑：20MB `template.apk` 解码 900s 超时，换 8.5KB valid fixture 1 分钟成功——**选样本比加超时有效**。本轮新发现 3 个不一致待下批：`native_analysis_open` 能读 `/system` 但提示去用的 `artifact_fingerprint` 拒绝 `/system`；`ANALYSIS_STALE_INPUT` 只回 actual 前 16 位；`zip_extract` 把 `password` 当必填。

> **2026-11-06 六项待决整理全部落地（后续提交）**：① **flutter 旧资产**：删 `flutter-payload-base/assets/**`+`flutter_embedding_3.3.4.jar`（84 文件 / 9.43MB，新管线零消费，[引用面报告](docs/reports/2026-11-06_flutter旧资产引用面.md)），同步 `.gitattributes`/`API_REFERENCE.md`/`README` 并重生成文档索引（783 条）；dev-1367 构建通过（含 `verifyApiReference`+`verifyFlutterPayload`）。② **rhino-1.7.7.2.jar**：确认**有意保留**并在 `build.gradle` 写明（1.24MB 换一行即可回退），防止下次被当「无引用 jar」误删。③ **0 字节 `resources.arsc`**：它与测试自己生成的 `out.txt` 一起删除（`Assume` 对 0 字节照样通过，真跑会拿空文件喂 `ArscReader`），删后 `:apkbuilder:testDebugUnitTest` 通过。④ **copyTree 合并**：新增 `FsCopyTree`（一个 core + Mode 参数），`PocPackaging`/`PocWorkspace` 各变成一行委托、签名与行为不变，`NativeProjectStaging` 保留独立（depth/字节上限等暂存语义不同，仅补 javadoc）；等价性用 7 场景×2 模式 javac 差分证明，编译通过。⑤ **插件模板**：跑 `:inrt:buildApkPlugin` 发现**原跟踪模板是过期产物**（`1CA77AD1` 88,335,762B → `5E70C543` 88,424,956B），已提交新模板+manifest 并同步 README；**未做真机验收**，插件正式发版仍需重跑并 bump 版本（清单见 [可行性报告](docs/reports/2026-11-06_插件模板重建可行性.md)）。⑥ **磁盘**：清 `.artifacts` 53 个旧 APK + 1430 个旧日志、`output/ci-head.zip` 等，**释放 ≈5.2GB**（工作区 11.5→11.0GB）；保留 `updates/`（LAN 源）、最新 4 个候选、`node-pro-gaps-20261021`（被 5 篇文档引用）与 `toolchain-20261025`（组件重打包暂存）；规则与命令模板已写进 [REPO_CONVENTIONS §七](docs/guides/REPO_CONVENTIONS.md)。提交 `4c1376c5`/`92749c29`；tracked 6604→**6521**；**设备仍掉线**，dev-1366/1367 的装机验证（buildInPlace / apk_build_project 真跑 / 看图器 URI）待 K40 复插。

> **2026-11-06 源码整理与框架梳理（先备份后动手）**：[架构总览](docs/guides/ARCHITECTURE.md) · [仓库规范](docs/guides/REPO_CONVENTIONS.md) · [文档索引](docs/INDEX.md) · [死代码盘点](docs/reports/2026-11-06_死代码与冗余资产盘点.md)。
> ① **备份**（仓库外 `C:\Users\18101\Documents\ai-js-pro-backups`）：tag `backup/20261106-160020-d0f5d3e5` + `--all` bundle（65.5MB）+ tracked 源码 zip（322MB）。
> ② **整理规范**：`tools/` 从「忽略全部+逐个白名单」改为**默认全部跟踪**——旧策略导致 **47 个真实脚本在磁盘上却从未入库**（device 驱动、引擎审计、Node24/QuickJS 契约测试、probes），每次新增都会再踩；入库前扫过密钥（命中均为测试夹具）；删掉**无任何构建引用**的 `rhino-1.7.14.1.jar`（1.39MB，实际只用 1.7.15.1）；修两处文档漂移（Rhino 版本 1.7.7.2→1.7.15.1、插件模板体积 67.5MB→实测 84.24MB）；根目录 15 个本地垃圾（6×`hs_err_pid*.log`、6×`replay_pid*.log`、截图、构建日志片段，共 11.29MB）移到 `output/local-cleanup-20261106/`（保留未删）；给 `HANDOFF_AI.md`/`PROJECT_STATUS.md`/`docs/README.md` 加「状态以 AGENTS.md 为准」横幅（这几页长期停在旧版本，HANDOFF §4 还写 dev-1177）。
> ③ **框架**：[ARCHITECTURE.md](docs/guides/ARCHITECTURE.md) 450 行——Gradle 模块图（16 模块）、**进程模型**（main/`:script`/`:native`/`:node24_1..3`/`:nodeui`/`:tools`/`:inspector`/`:background`/`aijs_shizuku_<pid>` 与各自通信方式）、三引擎入口与 6 个 API 对照、MCP 定义与分发（**204 canonical + 9 aliases = 213 名称**）、UI 边界、资产与打包流、「我要加 X 该动哪里」速查表、**不确定项单列**。
> ④ 清理后 `:app:compileMiuixCompatDebugKotlin` **编译通过**；提交 `a8786cd7`/`822a072a`/`d491154e`（领先远端正 135，未 push）。**待你决定**：flutter 旧 assets+3.3.4 jar（约 9.4MB，文档仍引用旧路径）、rhino `1.7.7.2.jar` 是否继续作回退、0 字节 `resources.arsc` 样本、`copyTree` 四处实现是否合并重构、插件模板是否重建、`.artifacts`（29GB）/`output`（3.9GB）是否清理。

> **测试机约定（2026-11-06 用户要求，先读这条）**：默认测试机 = **K40 `cccc62c7`**（M2012K11AC / lineage_alioth，USB）。**不要再用小米 8 `ce4d2bdb`**（仍在 adb 列表，但只作历史参照）。K40 上 MCP 走 `adb -s cccc62c7 forward tcp:18768 tcp:8765`；无障碍组件同为 `com.jdkshen.aijspro/com.google.android.accessibility.selecttospeak.SelectToSpeakService`。历史上「K40 保持业务、不要动」的限制**已被本条取代**。

> **2026-11-06 已发布 LAN dev-1362**：[内置谷歌 OCR 示例报错修复 + 测试机切 K40](docs/reports/2026-11-06_谷歌OCR示例报错修复与K40测试机（dev-1362）.md)。用户报「谷歌 OCR 示例报错」，实测**两个独立真因**：① **Rhino 的 `:script` 进程从没初始化 MlKitContext**——Node 侧 `Node24HostBridge:659-669` 一直有这段初始化，Rhino 漏了，于是 `$ocr.create({backend:"mlkit"})`「创建成功、第一次 detect 才抛 `MlKitContext has not been initialized`」（所以既有 ppocr 兜底从未被触发）→ `Ocr.java` 加幂等 `ensureMlKitInitialized()`，`__ocr__.js`/`__plugins__.js`/`qjs-plugins.js` 加降级链并暴露 `requestedBackend/backend/degraded/degradedFrom/degradeReason`；② **`console.log(result)` 序列化 Java 对象图**——`detect()` 原来把 Java `OCRResult`（带 `Rect` + 原始 ML Kit `javaObject`）直接塞进数组，我们的 console 走 `__json2__.js` 一路走进宿主 Activity/RecyclerView，报 `RecyclerView declares multiple JSON fields named mAccessibilityDelegate` → 现在逐项转纯 JS 对象 `{text,confidence,bounds{left,top,right,bottom,x,y,width,height},rotation,rotationConfidence}`，原始对象改为 `raw()` 方法（不进 JSON）。**K40 实测**（dev-1362）：官方示例原文 SUCCEEDED 418ms/18 块、`$ocr.create()` 默认 `backend=mlkit degraded=false`、ppocr 18 块，三个**真实示例文件全部 SUCCEEDED**（Rhino MLKit 末轮 199ms / Rhino PP-OCR 430ms / Node `NODE_OCR_SAMPLE_OK frames=5`）。**第 4 项进展**：R8 把 apksig 签名 API 裁掉了（实测 `ApkSigner$Builder` 整类消失、`ApkSigner` 只剩验签辅助方法）→ 补 proguard keeps 后 `get_build_tools.signing.apksigAvailable` **false→true**；新增 `get_build_tools.signing`（无口令）与 `apk_build.alignment` 只读诊断；核查结论：`ToolkitArchiveAlignment` **本来就在真补齐**（Stored 4B / Stored .so 16KiB，写 `0xd935` extra），4 处调用链都会 align 且最终 verifier 强制 `zip.status=valid`。**未做**：`apk_build` 实际切 apksig（含 V3，当前仍 `KeyStoreApkSigner` V1+V2）、`apk_build_project`/`apk_inject_gadget` 顶层 alignment、QuickJS mlkit 路径在新构建上复跑、MI8 历史结论在 K40 复测。K40 未 push。

> **2026-11-06 已发布 LAN dev-1360**：[按交付包落实 MCP 改造清单](docs/reports/2026-11-06_按交付包落实MCP改造清单（dev-1360）.md)。用户给的 `MCP-AIjsPro-交付包.zip` 里 `MCP-改造-checklist.md` 是**基于反汇编 dev-1359** 得出的清单，本轮 4 项源码改造全部落地并真机验证：① 工程上限 `ToolkitReverseFiles.copyTree` 30000→`McpLimits.MAX_PROJECT_ENTRIES=200000`，**并补上交付方漏掉的第二道闸门** `ToolkitIo.MAX_APK`=512MiB → `MAX_PROJECT_BYTES=2GiB`；② 子进程堆 `apktoolCommand`/`jadxCommand` 在 `/system/bin` **之前**注入 `-Xmx2048m`（VM 选项位置，放后面无效），失败文案回显生效值；③ 超时 300→`MAX_TIMEOUT_SECONDS=1800` 统一（schema/后台闸门/执行侧 coerce/Jobs 恢复 clamp），**并同步交付方未列的下游旧上限** `ToolkitR2Session` 300、`ToolkitR2Resident` 300000、`reverse_capabilities.timeoutSecondsMax`、逆向指南两处「1..300」；④ 可诊断：`get_status.limits`/`toolkit_status.limits` 直接回报生效值（`toolkit_status` 原 6 条人类可读说明移到 `capabilityNotes` 未丢）。真机证据：`timeoutSeconds:900` 真实调用**通过参数闸门**（改前被 300 拒）；设备上造 **31,001 文件**工程跑构建 → 147s 后 apktool 报「Could not smali file」且文案含 `（子进程堆 -Xmx2048m）`——**改前会秒拒「工程文件过多或不是普通文件」**。新增 5 项 JVM 单测（31k 文件 copyTree、-Xmx 必须在 /system/bin 之前、四项上限放宽断言），MCP 535 项全过。核实纠正：`NativeProjectStaging.copyTree` 的 int 是 **depth 不是条目上限**，`PocPackaging/PocWorkspace` 无上限 → flutterpoc 三条未改；`ToolkitCliMain` 早已 `setProperty("os.name","Linux")`。未做：内置 apksigner/zipalign、分片构建、工作区自动回收、真实 5.7 万文件 apktool 回编样本。K40 未动、未 push。

> **2026-11-06 已发布 LAN dev-1359**：[按 poker-recog 反馈修 MCP 第二批](docs/reports/2026-11-06_按poker-recog反馈修MCP第二批（dev-1359）.md)。① **P0-2/P2-12 文档对账**：`list_engine_api` 每项给 `documented`（原 `items` 字符串数组保持不变，另附 `documentedItems`+`documentedCount`/`undocumentedCount`/`build`/`note`）——MI8 实测 `nodejs/image` **documented=3/55**，反馈里「文档写未接入、实测 21ms 能用」的 `matchTemplateMulti` 正好是 `documented:false`；`search_api_docs`/`read_api_doc` 加 `build`+`documentedAt`（索引无 `generatedAt` 时退到 `apk.lastUpdateTime` 并**明确标注不是文档生成时间**）。② **P1-6 新工具 `coordinate_convert`**（用户标★最痛的三套坐标系）：`state` 区分 panel 与 appDisplay（不同时给 `derived_*`+`affineAssumed=true`）、`calibrate` 两点标定持久化、`convert` 按 `point|rect` 换算、`clear`；MI8 实测两点标定**复现他们量出的 (1, 1.0009, 152, 0)**，`rect[100,200,300,400]→[252,200,452,400]`。③ **P0-5 图片句柄**：像素超限错误带 `registeredPixels/limit`、`本次新增`、`liveCount` 与最近 5 个句柄 `{id,width,height,ageMs}` + 「是否忘记 recycle()」可执行提示；新增 `require("image").diagnostics()`；实测 27 帧 1080×2160 未回收 → `62985600/64000000`、`liveCount=27`。队友 `manifest-detector`（文档对账）与 `native-process`（引擎诊断）各改各的文件，Lead 接线/构建/验证/发布。公开入口 **213**（204+9）。未做：生成器写 `generatedAt`、`floating_window` 文档本体、`.4` 复跑。K40 未动、未 push。

> **2026-11-06 已发布 LAN dev-1356**：[按 poker-recog 反馈修 MCP 第一批](docs/reports/2026-11-06_按poker-recog反馈修MCP第一批（dev-1356）.md)。`.4` 上扑克识别项目写了 11 条 MCP 反馈（含复现清单），本批修 7 条并实测：① **P1-7 `capture_screen` 加 `savePath`/`saveFormat`**——只落盘不回传，`raw` 写 RGBA8888 原始像素（`stride=width*4`、无头，与 `image.exportPixels` 同布局）：MI8 整屏 **401ms**（采集142+拷贝12+写盘245）vs 同帧 PNG **625ms**（编码占552），`fast`+savePath **171ms**，且零 base64/JSON 传输；② P0-4 `run_script` 接受 **`code` 作为 `source` 别名**、空输入报错点名「内存源码放 source/code，磁盘脚本放 path/file」；③ P0-1 `app_list` 加 `rawCounts{getInstalledPackages,launcherActivities,packageManagerReturned}`+`coverageSuspect`，PM 回包少于 launcher 可见数时 `scanScope=package_manager_returned`/`visibilityLimited=true`（不再自称看全，`.4` 那种「只返回 1 个包」会被标可疑）；④ P1-9 `get_status` 加 `app{versionName,build,versionCode,lastUpdateTime}`/`toolCount`(212)/`canonicalToolCount`(203)；⑤ P2-10 工作区>50 给 `nextActions` 清理建议；⑥ P1-8 `search_scripts` 加 `stopReason`/`scanLimits`/`warning`（**未扫完≠不存在**）/`resumeHint`；⑦ P2-11 加 `logsText` 干净文本。回复文档已写到 `.4` 的 `poker-recog/MCP-FEEDBACK-REPLY.md`。下一批：P0-2/P2-12 文档 `documentedAt`+`build` 与 `list_engine_api.documented`、P0-5 `image_handles` 诊断、P0-3 看图器缓存键、P1-6 `coordinate_convert` 三套坐标系。K40 未动、未 push。

> **2026-11-06 已发布 LAN dev-1355**：[截图再加 `fastest` 与区域截图](docs/reports/2026-11-06_MCP截图质量与速度双模式.md)。① `mode=fastest` = 360 宽 + JPEG40，MI8 实测 **188ms / 18KB**；② `region` 区域截图，与 `ui_*` 的 bounds 同写法（`[l,t,r,b]` / `"[l,t][r,b]"` / `{x,y,width,height}`，越界自动夹取），实现**先裁后缩** → 只截一块时又小又快且保留 1:1 像素：实测 `quality+region[0,1000,1080,1400]` = **175ms / 50KB**（整屏 782ms / 215KB，约 4.5× 快且不缩像素），`fast+region{200,800,400,300}` = 203ms / 11KB。返回新增 `cropped/region/regionClamped/regionNote`，`scale` 以区域宽度为基准；未知 mode 与坏 region 都明确报错。MCP 单测全过（`McpCapturePresetTest` 9 项）。K40 未动、未 push。

> **2026-11-06 已发布 LAN dev-1354**：[MCP 截图质量/速度双模式](docs/reports/2026-11-06_MCP截图质量与速度双模式.md)。`capture_screen` 新增 `mode=fast|balanced|quality`：**fast** = 540 宽 + JPEG60（MI8 实测 **222/244/271 ms、36 KB**），**quality** = 原图 PNG + 更长超时（**599/613/633 ms、216 KB**），**balanced** 与历史默认完全一致（原图 PNG，保证老调用不变）。耗时主因是 **PNG 编码 ~540 ms**（采集本身仅 50~200 ms），fast 靠缩小+JPEG 把编码压到 ~20 ms → 约 3 倍快、6 倍小；显式覆盖参数可更极端（`maxWidth:180`+`quality:40` → **104 ms / 7 KB**）。返回新增 `mode/modeNote/captureMs/encodeMs/totalMs/effective`，未知 mode 明确报错。踩坑：**重装 APK 后无障碍需重新开启**（否则截图报「录屏兼容截图需要 Android 11+…请关闭再开启一次」）。MCP 单测全过（新增 `McpCapturePresetTest` 5 项）。K40 未动、未 push。

> **2026-11-05 已发布 LAN dev-1352**：[「悬浮窗关闭不了」定位与修复](docs/reports/2026-11-05_悬浮窗关闭问题定位与修复.md)。先把两类窗口都实测：**脚本悬浮窗**（`floaty.window`）用真实 `input tap` 点 ×、`window.close()`、`close_overlays`、停止脚本**都能关**（`dumpsys window` 里窗口真的消失）；问题在**应用自身悬浮球**（48dp、主进程常驻）——① `CircularMenuWindow.close()` 把「移除菜单视图/移除小球视图/FloatyService.removeWindow」三步串在同一个 try 里，菜单未 inflate/attach 时第一步抛异常被吞，后两步不执行 → 小球留在屏幕上，点了没反应；② `FloatyWindowManger.hideCircularMenu()` 先 close 后清引用，部分失败会留下陈旧引用 → 此后既关不掉也打不开。修复：三步各自独立 + `removeViewSafely`（null/attach 判断 + `removeViewImmediate` 回退 + 日志）、hide 先摘引用再关闭、新增 MCP 工具 **`floating_menu {action:state|show|close}`**（主线程、回读真实状态）。真机实测：state=showing/2 窗 → close 后 **0 窗**、showing=false → show 恢复 2 窗。公开入口 **212**（203+9），MCP 单测全过。K40 未动、未 push。

> **2026-11-05 已发布 LAN dev-1351**：[内置 Rhino 示例全量运行与引擎修复](docs/reports/2026-11-05_Rhino内置示例全量运行与引擎修复.md)。用户反馈「跑 rhino 示例有报错」→ 把 160 个内置 Rhino 示例真机全跑（先整树 adb push，含图片/res，否则会假失败）：**57 成功 / 95 常驻运行 / 8 失败（全是环境或已给清晰提示）**。修掉 6 个引擎缺陷：① **R8 裁掉只被脚本反射调用的 OkHttp 成员**（`文件上传.js` 报 `Cannot find function addFormDataPart`；实测 R8 包里该类只剩 13 个方法）→ 加 `-keep class okhttp3.** { public *; }`/okio，重建后 **DEX 实测字符串已存在**；② `__http__.js` 响应 body 为 null 时 `body.string.bind` 崩（获取网页/文件下载）→ 空 body 给空内容 + 跨版本 RequestBody 创建；③ `$zip`/`ZipFile` 相对路径没按脚本 cwd 解析（Zip 示例「不是文件夹」）→ 统一 `resolve()`（复用 `Files.path()`），示例 SUCCEEDED；④ `console.setGlobalLogConfig` 缺 log4j 降级（上批）；⑤ `Events.observeKey` 在 `serviceInfo==null` 时给可读错误（原来 NPE）；⑥ **`ScriptCanvasView` 绘制线程 `ScriptInterruptedException` 逃逸成 FATAL、直接杀 `:script` 进程**（画布示例停止即触发）→ 池任务整体兜住，实测停止后 FATAL_COUNT=0、进程存活。137.97 MiB，SHA `2edda451…`。K40 未动、未 push。

> **2026-11-05 已发布 LAN dev-1347**：[《华仔部落_佣兵战纪》脚本报错定位与修复](docs/reports/2026-11-05_华仔部落佣兵战纪脚本报错定位与修复.md)。用户给的 Auto.js Pro 工程一启动即失败，实测定位四个原因并修前三个（第四个是设备状态）：① **我们的 bug**——`__console__.js` 无条件 `new Packages["de.mindpipe.android.logging.log4j"].LogConfigurator()`，缺 log4j 时脚本秒挂且报错看不懂 → 改为探测+**降级**（记录 `__aijsLogConfig`、提示一次，绝不打断脚本）；② 第三方 OCR 插件缺失（`com.hraps.ocr`/`ocr32`/`com.tomato.ocr`，脚本在模块顶层就 load）→ `__plugins__.js` 回退内置 OCR（hraps 返实例、tomato 返构造函数；**ppocr 优先**、mlkit 兜底；`__aijsBuiltInShim=true` + 一次性提示；其它包名仍抛真实错误）；③ `Ocr/PPOcr.detect` 新增 `Object` 重载接受 `Bitmap`（插件式调用）。MI8 实测：脚本从 FAILED → **RUNNING**（初始化 OCR ×4、请求截图成功）。引擎插件兼容单测 7 项全过，137.93 MiB，SHA `c444f213…`。边界：内置 OCR ≠ 浩然/tomato 插件（装了就优先用插件）、无障碍需用户开启（本轮 ADB 打开验证）。K40 未动、未 push。

> **2026-11-05 已发布 LAN dev-1344**：[MCP 改进第三批（常驻 r2 会话）](docs/reports/2026-11-05_MCP改进第三批（常驻r2会话）.md)。① `native_resident_open/command/batch/close/list`：**常驻 r2 进程**一次加载多次查询，真机实测单条 45~72ms、5 条批量 149ms（同文件 project 复用路径 9.9~15.5s）；framing = `<cmd>;?e <marker>` + r2 `-0`（NUL 只管刷新、终止看 marker）+ open 预热，三轮真机踩坑后才对齐（只有 NUL 会错位一条命令、只有 marker 会被块缓冲卡死）。② `projectSegments`/`summarizeBigFunction` 分段投影 82KB 级函数（`nextOffset` 续读、`static_inferred`/`relocAware`）。③ 两个协作者中途失败，Lead 接手修完：`ToolkitR2Resident` 编译错误（可空接收者/`return@withLock@for`/SAM）、`ToolkitDecompileView` 的 `JsonParser.parseString` 不存在 + 零测试。公开入口 **211**（202+9）、MCP **517 单测**全过（dev-1344 发布说明写「531」是笔误），137.93 MiB，SHA `07090676…`。边界：常驻会话不持久化（死了报 SESSION_DEAD）、常驻里跑 `pdg` 需插件（走 native_decompile）。K40 未动、未 push。

> **2026-11-05 已发布 LAN dev-1339**：[MCP 改进第二批](docs/reports/2026-11-05_MCP改进第二批（自动证据_路由字段_会话持久化）.md)。① Frida 诊断自动取证据：`ToolkitProcNetTcp` 解析 `/proc/net/tcp[6]`（或用 `tcpText/tcp6Text` 传原文），错误信息带真实 `portHex`（实测 `223D` LISTEN → SELinux 嫌疑），`sameUid` 仍需调用方比较；② 终端路由结构化：`terminal_read/list` 直接返回 `requestedBackend/effectiveBackend/routed/routingRequirement/routingHint`（实测 app→shizuku 自动路由）；③ r2 会话跨进程持久化：`sessions.index.json` 原子索引，force-stop/升级后 `restored=1` 且可直接查询（实测 `iE~vmInterpret` → `0x3a1e4 82684 vmInterpret`）。另用新工具复核真实 VMP 目标 `libyj-vmp-lib.so`（分页 MCP 读回 + adb push，sha256 一致）：首分析 10.0s、后续查询 5.5s。MCP 500 单测全过，137.91 MiB，SHA `509e39ed…`。未做：sameUid 自动判定、/proc 反推历史会话、路由行只取首行。K40 未动、未 push。

> **2026-11-04 已发布 LAN dev-1337**：[用户实测三个 bug 修复](docs/reports/2026-11-04_MCP实测三个bug修复（dev-1337）.md)。① `node_script_command` 支持字符串/数组/null 三种 args（实测字符串 args 曾报 MCP Internal error），非法输入给结构化错误；② 工作区配额死锁解除：显式 `includeUnknownOwner+includeTemporary+minAgeMinutes=0` 可回收 expired/编辑会话占用（MI8 实测 4 个 null-owner 工作区删除、回收 157MB、配额回到 16），配额报错改为可执行命令，新增 `apk_session_release`，新工作区落 `owner=mcp-host/ownerSource=server-default`；③ Frida listen 跨 UID SELinux 诊断（`connectDiagnosis`/`listenModeLimits`，超时不再被当成组件没装），并实现 Gadget **script 模式**（`interactionType=script`+`scriptPath`，不碰网络）。公开入口 206（197+9）、MCP 478 单测全过，137.90 MiB，SHA `9e1f7bae…`。K40 未动、未 push。

> **2026-11-04 已发布 LAN dev-1336**：[MCP 用户体验反馈第一批（UX1-5）](docs/reports/2026-11-04_MCP用户体验反馈第一批（UX1-5）.md)。按用户侧实战复盘（[反馈稿](docs/plans/2026-11-04_AIjsPro-MCP使用体验反馈（用户侧）.md)）逐条落地：① §2.1 终端后端路由——用错后端时首行写明「该命令需要 X 后端」，`autoRoute` 仅只读白名单自动切换并标注（真机实测 pm/app 与 logcat 自动路由）；② §2.2 常驻 r2 会话 `native_session_*`（project 保存/加载，一次 aaa 多次查询：Q1 1015ms→Q2 807ms，close 释放 303KB）；③ §2.3 `ToolkitDisasmEvidence` 明确 pdf/pdfj/axtj 不解析重定位、0 命中不是结论，并给 `e bin.relocs.apply=true`（native_function_detail/native_xrefs 增 `relocsApply`）；④ §2.4/§2.5/§2.6 新增 `shell_capabilities`/`shell_classify_failure`/`node_script_command`/`crash_buffer_semantics`；⑤ §2.7 `get_status.rootStability`。公开入口 205（196+9），MCP 460 单测全过，137.89 MiB，SHA `4870f46f…`。未做：autoRoute 仅只读白名单、routedFrom/To 目前只在输出首行、r2 会话不跨进程持久化、大库 aaa 仍需显式超时；K40 未动、未 push。

> **2026-11-04 已发布 LAN dev-1335**：[MCP 逆向能力优化第三阶段（P1/P2 全部落地）](docs/reports/2026-11-04_MCP逆向能力优化第三阶段（P1P2）.md)。四个并行工作流 + Lead 接线：① §7 有状态 native 分析会话（10 工具，哈希变化失效、覆盖层 analysisOnly、r2 命令只生成不执行）；② §8 反编译默认精简输出 + 函数边界来源 + 交叉引用证据分级；③ §9 `app_exit_info`/`app_trace_*`/`app_launch_trace`（七类退出原因，SIGKILL 不臆断检测分支）；④ §10 `frida_session_*` 有界会话 + `frida_status.javaBridge`（native 可用≠Java.perform 可用）；⑤ §11 `binary_transform`（断言驱动，verified 只代表断言通过）；⑥ §12 `evidence_export`（证据分级/脱敏/完整解压校验）。公开入口 196（187+9）、toolkit 120、逆向 33；MCP 424 单测全过，137.85 MiB，SHA `546902d9…`，LAN 已发布。修过两处集成缺陷：工具定义顺序不一致导致 MCP 启动自检停服、同步逆向工具误落 workspaceId 分支。未验/未做：Frida 持久 attach、r2 命令执行、其它应用完整 logcat、二进制内容脱敏；K40 未动、未 push。

> **2026-11-04 已发布 LAN dev-1333**：[MCP 逆向能力优化第三批](docs/reports/2026-11-04_MCP逆向能力优化第三批.md)。① §6.3 嵌入式 Node 运行形态：`engineDiagnostics.runtime.nodeRuntimeForm` 给出 `runtimeMode=embedded-shared-library`、`hostExecutable`（app_process64/32）、`standaloneCli` 各项与 `canSpawnStandaloneNode`、`supportedChildScriptRunner`，并明确 process.execPath 不能当 CLI 依据；MI8 实测 CLI 启动器已提取（present/executable/libnode 同目录）故 canSpawn=true。② §5.2 收尾：artifact_open 改结构判定——`.apk.1` 真机直接打开成功，伪 `.apk` 报「不是 ZIP」而非 ZipException，目录返回有界扫描候选与置信度。MCP 单测 366 全过（Diagnostics+1、ApkToolkit+1）；137.78 MiB，SHA `a3dda080…`，LAN 已发布、手机可拉取。未做：Node CLI 实际执行、hostExecutable 仅存在性判断、目录候选不自动跟随；P1/P2 仍待；K40 未动、未 push。

> **2026-11-04 已发布 LAN dev-1332**：[MCP 逆向能力优化第二批](docs/reports/2026-11-04_MCP逆向能力优化第二批.md)。继续 GPT 复盘 P0：① Manifest 基础字段兜底——新增独立 AXML 解析 `ToolkitAxmlLite`，ARSCLib 失败时（真机实测 `Can not decode without package`）仍能取回 package/minSdk/targetSdk，并自报 `parseSource=independent-axml`/`fullXmlDecoded=false`/`failureKind`/原始错误；② `app_list` 增 `visibilityLimited`/`scanScope`/`normalizedQuery`/`matchedBy`（NFKC 全半角归一化）且保持先过滤后分页；③ 后台任务保留窗口+墓碑与 `JOB_NOT_FOUND`/`JOB_EVICTED`/`SERVICE_RESTARTED` 错误码，元数据与输出缓存分离、可选 owner/clientId/sessionId 过滤。MCP 单测 364 全过（AppTests16/Jobs11/AxmlLite3 新增）；137.77 MiB，SHA `95620986…`，PC/MI8 LAN 下载一致。未做：§6.3 Node 运行形态、artifact_open 回退候选、全部 P1/P2；owner 非安全边界、无定时 GC；K40 未动、未 push。

> **2026-11-04 已发布 LAN dev-1331**：[MCP 逆向能力优化第一批](docs/reports/2026-11-04_MCP逆向能力优化第一批.md)。按手机上的 GPT 实战复盘（[建议稿](docs/plans/2026-11-04_AIjsPro-MCP逆向能力优化建议（GPT复盘）.md)）做 P0 第一批：新增 `apk_workspace_inventory`/`apk_workspace_gc`（配额 16 可解释、默认 dryRun、保护 saved/活动引用/未知 owner）、`file_discover_apk`（ZIP 魔数+APK 结构，`.apk.1` 真机 high）、`file_access_diagnose`（显式 app|shizuku、原始 errno、不确定给 UNCERTAIN），`file_stat` 增 apkProbe；验签改分方案状态模型 valid/invalid/backend_error/input_error/not_run 并新增 `backendDiagnosis` ASN.1 注解探针（MI8 实测 retained=true、V2 valid、V1/V3/V3.1 not_run），后端异常不再当成目标签名无效；proguard 补 apksig ASN.1 keeps。公开入口 174（165+9）、toolkit 104；MCP 344 单测、23 ELF 16KB/R8/Retrofit 通过；144,444,969 B，SHA `5b6e6bca…`，PC/MI8 LAN 下载一致。未做：Manifest 兜底解析、应用查询 visibilityLimited、任务所有权/错误码、Node 运行形态、P1/P2；K40 未动、未 push。

> **2026-11-04 已发布 LAN dev-1330**：[MCP免Root Gadget集成](docs/reports/2026-11-04_MCP免RootGadget集成.md)。新增apk_inject_gadget，四ABI组件按需下载，二进制Manifest/独立DEX加载器、原资源保留、重签验签、本机令牌与客户端v2升级；不需要Root/Server。170入口（161+9）、toolkit100、650 App回归和MI8普通UID安装/附加/原生42/重开/拒绝/取消通过。主包137.70MiB，仅增约18KiB，SHA43c50a05…；PC/MI8完整LAN下载与实装摘要一致。真实16KiB页、Android17及其他ABI设备未验，K40未动、无push。


> **2026-11-04 已发布 LAN dev-1329**：[MCP逆向工具、自发现与验收](docs/reports/2026-11-04_MCP逆向工具与自发现指南.md)。新增18项逆向工具，公开169名（160+9）、toolkit99；Apktool完整工程/Android九宫格、radare2+r2ghidra、YARA、Unicorn五架构、Frida有界客户端。标准资源/提示、分页指南和8种机读计划已内置，另见 [MCP调用入口](docs/MCP逆向调用说明.md)。1270 JVM、MI8正式MCP/伪代码/取消恢复/回编译验签通过；23主包和209可选ELF项静态16KiB通过；主包只增约91KiB。APK144374557B，SHA f3ded732…，PC/MI8完整LAN下载一致。Frida需Server/Gadget；完整APKiD、实际16KiB页设备及Android17原机未验；K40未动、无push。


> **2026-11-03 已发布 LAN dev-1328**：[实现、验收与边界](docs/reports/2026-11-03_MCP编译逆向增强与Node资源恢复.md)。新增23项独立MCP工具（151公开入口）：真实验签/ZIP对齐、方法级DEX导航、可选JADX/AAPT2、后台取消与会话回退、独立DEX/SO/分包读取。修复chess-auto资源摘要不匹配的本机重编/恢复页和源目录映射。1246本地回归、MI8完整项目入口/三页/错误摘要→一键修复→重开、同名DEX选择及签名导出通过；PC/手机完整LAN下载摘要一致。APK144281420B/SHA7068ee94…；Downloads有APK和新工程ZIP。原ZIP保留。Android17原机/独立APK/长时对弈未验，K40未动、未push。

> **2026-11-03 APK 编译/逆向 MCP 增强评估（未改产品）**：[评估与任务草案](docs/reports/2026-11-03_APK编译逆向MCP增强评估.md)。源码+手机128工具清单核对，DP只读复核已完成。电脑调用生产归档对齐函数并经独立zipalign复现普通Stored Manifest/assets未4字节对齐；P0待修，签名元数据也仍非密码学验签。建议补方法级搜索/出向调用、可选JADX/AAPT2、独立DEX/SO、异步任务/会话回退；Java/Gradle源码工具链仅备选，未恢复安装。LAN仍1327，本轮没有发布/改APK/操作手机界面。

> **2026-11-03 已发布 LAN dev-1327**：[Node Widget 入口与象棋编译资源修复](docs/reports/2026-11-03_NodeWidget入口与象棋编译资源修复.md)。修同一行 `"ui"; "nodejs";` 回落 Rhino，以及 staging 丢 `build/project-res.apk`/sidecar；Engine623+App629全过，MI8四Widget真实入口/交互和象棋UI三页通过，无ERROR。137.42MiB，仅增184B，SHA `b03e1638…`，沿用现有LAN签名原包发布；Downloads含修复ZIP与APK，原ZIP保留。内置独立模板未刷新/独立APK未验；K40未动、未push。

> **2026-11-03 已发布LAN dev-1326**：[发布与下载验证](docs/reports/2026-11-03_dev1326局域网发布.md)。用户授权后复用已验收、MI8已安装的相同APK发布（AllowInstalledCandidate），新旧LAN证书相同；SHA `9149a9f6fc48…`，137.42MiB。PC/MI8通过真实LAN完整下载，摘要/大小与清单、实装、本地Downloads副本一致；23 ELF16KB/Retrofit/平台阴影守卫通过。继承同包597 App、双端57方法各80调用/20扩展/80MiB/拒绝原子性的既有验收。本轮未重装、K40/无线设备未动，未push/建Release。以下1326“候选未切LAN”是发布前历史状态，当前源为1326；沿用开发通道证书，不是改用新的公开正式签名。

> **2026-11-03 MCP候选dev-1326（MI8已验，未切LAN）**：[实现与验收](docs/reports/2026-11-03_MCP对齐实现进度.md)。补齐下方审查识别的保留条目保护、V2/V3/V3.1元数据、AXML/DEX/搜索/资源、AES/ZipCrypto、Java正则、多架构汇编/重定位引用、返回分页和流式构建。597 App JVM全过；双端各80调用覆盖57/57方法、20项扩展断言、80MiB流程及11项拒绝/原子性真机通过。修手机LLD小地址布局与绝对目标、x86 Intel语法；MCP指南已实际读回。最终APK137.42MiB，SHA `9149a9f6fc48…`，MI8安装摘要一致；23 ELF/16KB/R8/签名/Retrofit/平台阴影守卫通过。工具128名称（119+9），toolkit57+状态1=58。LAN仍1318，候选为测试签名，未发布/未push；K40/无线设备未动。下方“仅审查未改产品、缺口仍存在”是实施前的历史时点，勿重复当作当前结论。

> **2026-11-03 MCP 对照 MT：入口齐，行为未齐（未发布）**：[57工具矩阵与双端实测缺口](docs/reports/2026-11-03_MCP与MT功能实现对照及缺口.md)。MI8 dev1325 对 MT2.26.10 实时MCP，每端38次/20种方法，另做收尾；57对应入口+状态1=58，历史“60”统计不准确。实测缺：v2/v3签名、AXML/DEX/字符串搜索、AXML locator、新建DEX类、资源default/逐项错误/完整XML预算、AES ZIP、zip_update强制额外参数、多架构指令/字符串padding、lookahead。P0是普通ZIP接口可删classes.dex且check仍valid；源码另有重定位xref/保留条目copy与patch/编辑原子性等。不是所有57方法行为均验；Java反编译不在MT MCP契约内。本轮仅审查与MD，产品源码未改、缺口仍存在；自建4工作区/2目录已清，LAN仍1318、MI8仍1325，K40/无线设备未动、未push。发布条件“没有缺口”未满足，不能声称已对齐。

> **2026-11-03 Node 原生资源/动态回调候选 dev-1325（MI8 已验，未切主源）**：[实际根因与验收](docs/reports/2026-11-03_Node原生资源与动态回调修复验收.md)。ARSCLib 平台接口副本造成 R8 将 XML parser 错误优化为空，已净化依赖并加 JAR/最终 DEX 构建发布守卫；另修动态 DEX 空值分支反向导致 getItemCount=0。528 App+617 Engine JVM、5 扫描器测试、MI8 原始编译 M3 卡片图片/三页可见性/停止、Node/Rhino 内联及实际 Java 基本类型回调通过。23 ELF16KB/R8/签名/Retrofit通过，137.39MiB，SHA f5822d67…，Downloads 有候选且与已安装摘要一致。测试残留已清；LAN 仍1318，K40/新机未动，未push。独立模板本轮未刷新，不能称独立APK已验。原资源挂载假设已撤销。

> **2026-11-02 当前LAN dev-1318（.4 已装 1317，可应用内更新）**：[应用内更新安装失败修复](docs/reports/2026-11-02_应用内更新安装失败修复.md)。现象「更新不了」：检查/下载/校验都正常，只卡在安装——`androidx.core` FileProvider 在真机 `resolveContentProvider(...,GET_META_DATA)` 拿不到 provider meta-data，抛 `Missing android.support.FILE_PROVIDER_PATHS meta-data`。`ApkInstallActivity` 改为优先 PackageInstaller 会话安装（MODE_FULL_INSTALL + commit(IntentSender) + STATUS_PENDING_USER_ACTION 拉起系统安装器），保留 FileProvider 回退；`.4`(Android 17)/MI8(Android 15) 日志无异常，`.4` 端到端（检查→下载→校验→系统安装器弹出）通过。dev-1317 修复测试包（144,048,420 B，SHA `5484e449…`），dev-1318 发布版（144,048,424 B，SHA `c96dd6c0…`）。旧版(≤1316)内置更新仍坏，需手动装一次 1317+；K40 仍 1312 未动。仅 LAN、本地提交。

> **2026-11-02 当时LAN dev-1316（MI8已装）**：[MCP 实战要点并入](docs/reports/2026-11-02_MCP文件APK工具与Capstone反汇编.md#七mcp-实战要点dev-1316)。把用户在 `.4` 实战「刷步借壳解密」沉淀的操作要点并入产品：新增 `mcp/AIjs-MCP-Playbook.md`，`learn_autojs_skill` 返回末尾自动追加（冷进程/dlopen 粘住、terminal 大输出轮询、workspace 文本限制、借目标 App 解密函数流程、坑表 A-D、留档约定）；`initialize.instructions` 增补 4 条高频要点。144,047,708 B，SHA `a58a5bd0…`，仅 LAN、本地提交；MI8 已装，K40 未动。

> **2026-11-02 当前LAN dev-1315（MI8已装）**：[MCP 文件/APK 工具箱与 Capstone 反汇编](docs/reports/2026-11-02_MCP文件APK工具与Capstone反汇编.md)。接续 Codex 未完成工作并发布：60 个本地 MCP 工具（file_*/zip_*/apk_*，独立 :tools 进程 + 文件 IPC、APK 不可变快照/编辑会话、DEX/Smali、ARSC 资源、ELF），工具总数 128 名称（119 规范 + 9 别名）；新增 Flutter「文件与 APK 工具」页；K40 上用可选 clang 编译 Capstone 5.0.6 JNI（AArch64/ARM/Thumb/x86/x86-64），lock 四哈希 + `verifyToolkitNativeFresh` 防旧库；修 4 个接手问题（`ignore` 前置声明、ARSCLib 中文数字字符引用、X86 ATT printer 缺符号导致 dlopen 失败、构建 daemon JVM 崩溃的缓解）。186 项 MCP JVM、flutter analyze、MI8 真机 128 工具/三架构反汇编/CFG 通过；144,042,959 B，SHA `a41adc8e…`，仅 LAN、本地提交，未 push/建 Release；K40 只用于编译。

> **2026-11-02 Android17 Shizuku终端候选 dev-1312（新机已装已验，未切主源）**：[共用后端修复与验收](docs/reports/2026-11-02_Android17_Shizuku终端兼容修复.md)。SDK37通过只读APK的app_process直接创建执行服务，受权限保护的Provider核对一次性令牌/后端UID交接Binder，沿用命令所有者/进程组/FD安装；旧系统保留UserService。1117 JVM、终端/MCP/三引擎、单停/超时/意外死亡与Node恢复、私有/split安装、空闲重连通过，新手机C/C++运行及so编译通过，AX保持连接。最终SHA 3be39faf…，Downloads/LAN手动包与手机安装摘要一致，新模板/783文档同步；主源仍1305，未push、旧机/K40未动。可视终端逐按钮、root/Sui/多用户/真实16KB未验。覆盖下方1311“通用终端仍失败”的历史状态。

> **2026-11-02 新手机 Shizuku 无障碍候选 dev-1311（新机已验，未切主源）**：[定位与修复](docs/reports/2026-11-02_新手机Shizuku启动无障碍修复.md)。`.4` 新机 Android17/SDK37、Shizuku已授权，但其UserService在系统LoadedApk.makeApplication空指针退出。无障碍改走Shizuku Binder直调settings，保留其他服务/用户并以真实连接判成功，错误透传。519 App JVM、最终R8/22ELF16KB/Retrofit、新机3轮启用78/62/63ms和专用按钮通过，已装1311且摘要一致；无障碍当前已连接。Downloads/LAN有手动包和MD，主源仍1305，旧机/K40未动、未push。通用Shizuku终端仍受UserService异常影响；附加修复按钮稳定复测未完成，不能算通过。

> **2026-11-02 编程器闪屏候选 dev-1310（MI8 已验，未切主源）**：[首帧与主题修复](docs/reports/2026-11-02_编程器打开文件闪屏修复.md)。1309 录屏抓到半屏白块；提前设置原生/网页底色、预载主题，首个文档恢复后经布局与视觉状态回调再显示，并防旧回调。JS 回归及 MI8 深浅/重开/进程重启六段录屏通过，输入/保存/撤销通过；R8/签名/22 ELF16KB/Retrofit 通过，仅增2134字节。Downloads/LAN有手动包和MD，主源仍1305，主题/用户标签保留、自建文件已清，K40未动、未push。

> **2026-11-02 界面美化候选 dev-1309（MI8 已验，未切主源）**：[文件/示例/项目美化](docs/reports/2026-11-02_文件与项目界面美化.md)。低饱和圆角类型图标、项目卡片、配置概览、分组折叠与固定操作栏；折叠值/验证保留，修窄屏下拉溢出。41 Flutter 测试和 MI8 明暗主题检查通过，包仅增12.97KiB，Downloads有APK/截图/MD，LAN有手动包；主源仍1305，主题/浮窗已恢复，K40未动、未push。

> **2026-11-02 文件管理候选 dev-1308（MI8 已验，未切主源）**：[目录与文件/示例规则对齐](docs/reports/2026-11-02_脚本目录与文件示例规则对齐.md). 修 scriptsRoot 只读不写、旧 /脚本/ 相对语义与跨进程缓存、旧列表回包/标题/编辑器目录；统一图标和编辑/运行/预览/打包能力，补 XML/AUTO 路由与真正递归正则筛选。9 JVM、39 Flutter、MI8 路径/重启/三引擎回读/筛选/预览/示例通过；Downloads/LAN 有手动包，主源仍1305，K40未动、未push。Root录制实际执行与Android36未验。

> **2026-11-02 C/C++公网修正版候选1307（MI8已验）**：[公网地址与HTTPS收尾](docs/reports/2026-11-02_C与C++公网下载及终端退出修复.md)。组件按用户本轮外网要求上传固定toolchains-cpp-r29-v1；三路公网清单可用，默认.org加速/备用.com/GitHub。aijs-toolchain install cpp、MCP源地址和文档已接入；修独立CLI在HTTPS成功后不退出。真机全新目录公网下载并运行C/C++、默认入口和5项切换测试通过，模板/编译组件未改。候选比1305仅增20.43KiB，Downloads/LAN有包；主LAN仍1305，GitHub latest仍1297，代码仅本地提交、K40未动。此前1306仅LAN地址的说明已由本轮补齐。

> **2026-11-02 可选C/C++候选dev-1306（MI8已装，未切主源）**：[实现与验收](docs/reports/2026-11-02_MCP终端按需C与C++编译组件.md)。用户新授权只恢复按需下载C/C++，覆盖下方历史取消中的此部分，不恢复Java/Gradle/APK工具链。APK仅增19896字节，编译组件另下75.47MiB/展开226.82MiB；终端aijs-toolchain、动态PATH与MCP状态完成。1113 JVM、真实下载中断重装、C/C++/so/静态库、错误与卸载重装、783条文档通过。Downloads和LAN有手动测试包，主更新源仍1305，K40未动；DP草稿任务超时，主助手集成验收。Android17/16KB和完整APK编译不在已验结论内。

> **2026-11-01 当前LAN dev-1305（MI8已装）**：[Shizuku三引擎、终端与发布](docs/reports/2026-11-01_Shizuku三引擎终端接入与局域网发布.md)。补多进程Binder/inrt Provider，受控UserService与所有者清理；三引擎统一shizuku API，终端app/Shizuku、MCP shell_rish、内置rish/i及私有/split流式安装；新模板、示例、782条文档同步。1103 JVM、三引擎43项、终端/停止/大文件/split/独立APK/界面/空闲重连通过，PC/MI8完整HTTP下载摘要一致；136.12MiB，Downloads有包。仅LAN、本地提交，K40未动。Android17/root-Sui/完整权限矩阵仍待；外置插件模板未改。以下1303“仅审阅”是历史时点，已由本轮覆盖。

> **2026-11-01 Shizuku指南审阅（未改产品代码）**：[修订与四任务方案](docs/reports/2026-11-01_Shizuku改造指南审阅与实施方案.md)。MCP读取`.4`两份20363字节/486行文档且摘要一致。确认多进程初始化缺口、inrt缺Provider、QuickJS已有shizuku而Rhino/Node未统一；终端实际共用底层，原文返回契约/超时/参数处理需修订。仅评审和文档，LAN仍1303；DP只读审阅超时，未计通过。

> **2026-11-01 已发布 dev-1303（仅LAN）**：[发布记录](docs/reports/2026-11-01_dev1303局域网发布.md)。包含1300–1302已验修复；沿用既有开发通道签名以支持覆盖更新。PC/MI8完整HTTP下载摘要一致，136.06 MiB；Downloads有包。未安装1303、K40未动、未push/建Release；以下候选和LAN1299描述保留历史时点，当前更新源为1303。

> **2026-11-01 最新候选 dev-1302（MI8 已装，未发布）**：[选中事件与旧协议菜单](docs/reports/2026-11-01_Node选中事件与旧协议菜单.md)，[当前剩余清单](docs/reports/2026-11-01_剩余对齐清单_dev1302.md)。修原生导航/Spinner 监听接口与选中接受、旧 :nodeui 五项菜单命令、反射监听返回；1,062 JVM、9 套 JS、MI8 107 项、独立 APK 12 项通过。App/MCP 781 条文档同步。QuickJS `$ui.inflate` 实测可用，别再列为缺失；defineClass/内置模板旧结论已纠正。LAN 仍1299，候选是测试签名，不可直接发布，未 push。

> **2026-11-01 最新候选 dev-1301（MI8 已装，未发布）**：[协议句柄与界面进程事件转发](docs/reports/2026-11-01_Node协议句柄与事件转发兼容.md)。协议/marker/原生监听共用 EventEmitter，修 once/off/prepend/幽灵监听/轮询清理；修 :nodeui 句柄被本地误查及重复查找身份，复用监听安装与 Activity 自有队列；纯内联不误报缺资源。1,060 JVM、13 协议组、MI8 IPC 19 项两轮＋原生 23/32、独立 APK 23 项通过。LAN 仍 1299，未 push；完整旧树/菜单/第三方事件与参数形状边界见报告。

> **2026-11-01 候选 dev-1300（MI8 已装，未发布）**：[Node 内联 UI 监听语义](docs/reports/2026-11-01_Node内联UI监听语义修复.md)。原生回调与 EventEmitter 单表统一，修 once/排序/重复/移除/重入；最终宿主新 23＋旧 32、内置新模板独立 APK 23 项通过，DP 只读评审完成。LAN 仍 dev-1299，未 push；旧协议句柄、轮询隔离与过宽资源告警另列未完成，不重报“内联 binding 无 viewId”。

> **2026-10-31 当前发布 dev-1299（LAN，MI8 已装）**：[Node UI 回调默认值与工程资源路径](docs/reports/2026-10-31_NodeUI回调默认值与资源路径dev-1299.md)。修 JS 回调返回 undefined 导致的基本类型拆箱 NPE（`setSelectedItemId`/`performLongClick`）；缺少 `build/project-res.apk` 时显式告警（兼容路径受限）；`build-project-res.ps1` 自动补裸数字尺寸 `dp`、对账缺失 `?attr`。chess-auto 的 40 处裸尺寸 + `materialButtonTonalStyle` 已修并编译资源推回设备。

> **2026-10-31 前次发布 dev-1297（LAN，MI8 已装）**：[Node UI 事件与视图树修复](docs/reports/2026-10-31_NodeUI事件与视图树修复dev-1297.md)。真机验证：Activity/绑定路径 click/check/text_change 全部回调（含真实点击）、`ui.inflate(R.layout.x)`、`addView/getParent`、`JsViewPager childCount=3/adapterCount=3`、BottomNav 菜单与监听；keepRunning 引用计数保活；文档索引 781 条。已知剩余：`setSelectedItemId` NPE、`ui.layout` 内联 binding 无 viewId、vector 仅诊断。

> **发布/推送约定（2026-10-31 用户要求，先读这条）**：① 默认**只发 LAN**（`192.168.10.7:8080`），**不要逐版本建 GitHub Release/tag**；GitHub 只作代码托管。② **代码提交默认只本地提交，不要自动 `git push`**；只有用户明确说「推一次」才推。按此约定已删除 dev-1292 / dev-1294 的 Release+tag，并停止逐版本推送。

> **2026-10-31 前次发布 dev-1294（LAN，MI8 已装，GitHub Release 已按约定删除）**：[节点对齐 R01–R08 集成与发布](docs/reports/2026-10-31_节点对齐R01-R08集成发布.md)。R01 Java 反射 varargs、R02/R03 Widget 共享根接收者与构造期 style/theme、R04 QuickJS ui.inflate、R05–R07 快照完整性/真 BFS/token 代次、R08 Node 等级2 自解包载荷与模板对账；562 引擎 JVM + 47 打包测试 + 契约 57 项；MI8 实测 R01/R04/R05/R07。135.98 MiB，SHA `f767e404…`；LAN 已发布，MI8 已装。

> **2026-10-30 前次发布 dev-1292（LAN，MI8 已装）**：[Widget 标签/晚注册兼容与验收](docs/reports/2026-10-30_Node_Widget标签注册兼容.md)。注册优先于原生别名转换，保留原始名称/属性/空值/实体；列表模板到 bind 时判断。522 JVM、7 组 JS、宿主新 36+旧 41/30/32 项、独立 APK 36 项、真实点击/重建/停止通过；773 条文档同步。135.95 MiB，SHA befa99b6…，完整手机 LAN 下载一致，Downloads 有 APK/MD。保持 1291 日志修复，K40 未动。style/theme 全组合、复杂别名共享根、反射空 varargs、通用插件与全量高级 API 仍待继续。以下为历史记录。

> **2026-10-31 候选 dev-1293（已被 dev-1294 取代）**：[R01 反射空可变参数修复](docs/reports/2026-10-31_R01反射空可变参数修复.md)。Class.getMethod(name) 省略空数组、单个/多个 varargs、显式数组一致性与固定重载优先均通过；1007 JVM、真机 6 项；候选 SHA `170292d2…`。

> **2026-10-30 当前发布 dev-1291（LAN，MI8 已装）**：[Node Widget 原生生命周期与完整验收](docs/reports/2026-10-30_Node_Widget原生生命周期.md)。真实 rootViewCreated/inflated、render(Context,parent) 返回 XML/原生 View、原始自定义属性、旧构造器默认值和 detached weight 已补；516 引擎 JVM、7 组 Node UI 契约、最终宿主 41+30+32 项、独立 APK 41 项、重建/停止、三引擎电脑日志 15 条通过。保留旁线 1290 日志修复，主工作区旁线文件未改。135.94 MiB，SHA 7900689f…，手机完整 LAN 下载摘要一致；Downloads 有包/MD。高级版全量、复杂别名和 style/theme 等仍有边界，以下为历史记录。

> **当前发布 dev-1286（2026-10-29，LAN）**：[架构对齐与完整验收](docs/reports/2026-10-29_dev1286发布与架构对齐验收.md)。三引擎生命周期/资源归属、Node 软停与退役屏障、QuickJS JNI 与 UI 交接、传统 UI/浮窗/无障碍/日志进程隔离、MCP 执行和自动化转发、新独立模板已交付。1,032 JVM、MI8 三引擎并发/单停、17 轮终态停止、独立 Node 节点点击、日志和故障恢复通过；完整手机 HTTP 下载摘要一致。APK 142,528,242 字节，SHA 439b5aaa…。LAN 为 192.168.10.7:8080；Downloads 有包，MI8 已装，K40 只读校验且任务未停。Android36 原机和高级版全量 API 边界见报告；下方为历史记录。

> **2026-10-29 高级版停止路径与12份MD架构审阅（无产品代码修改）**：[完整对照和待办](docs/reports/2026-10-29_高级版文档全量审阅与架构对照.md)。用户实际ZIP在 `C:/Users/18101/DownloadsAutoJs_Pro_Analysis(1).zip`，12MD、SHA `ba99d2b0…`；手机高级版com.boyai.auma与output/auma-base.apk摘要e0010eaf一致，另用JADX定向导出8类。已实现M3/Glide/Smali→Dex/方法ID与类型化JNI；高级版外部停止优先process.exit(-9000)，第4次请求升级V8强停，我们仍UI屏障后native cancel。传统Activity/普通Floaty/无障碍/日志仍有主进程组件，API全量行为未完成；ZIP内QuickJS依赖、hash索引与内存计算有错误，无依据性能数字不能采纳。没有启动高级版或变更LAN。

> **2026-10-29 Node 列表停止崩溃候选 dev-1285（MI8 50轮通过，未发布）**：[时序修复与验收](docs/reports/2026-10-29_Node原生列表停止崩溃.md)。主线程先解绑原生网格、移除内容，再关闭Node回调。510引擎JVM、四套UI契约、R8/22ELF16KB/签名通过；用户授权自动打开后恢复MCP，MI8 Android35固定网格20轮、M3示例20轮、音量上键10轮全部停止成功，无崩溃；确认30次解绑回调仍可执行。旧1284对照24轮未复现，Android36原机仍待复测。候选SHA `4f80ff33…`，Downloads有包；LAN仍1281，K40象棋未动。

> **2026-10-29 Node 原生和独立 APK 候选 dev-1284（未发布）**：[修复与验收](docs/reports/2026-10-29_Node原生与独立APK兼容.md)。修复 nodejs 工程别名、独立运行服务/Node UI Activity、精简模板 Node 资产与无障碍桥，补部分原生控件事件；K40 实际打包并验证 Node、简单 UI、check 事件及 Java 调用；MI8 独立包验证节点查询及点击实际生效，包含无脚本标记的 type=node 工程。982 JVM 及四套 Node UI 契约通过。候选 SHA `2f4b57e0…`，MI8 已装；LAN 仍 1281。高级 UI 事件、编译加密、手势截图和混合引擎实机场景待完善。

> **当前发布 dev-1281（2026-10-28，仅 LAN）**：[发布记录](docs/reports/2026-10-28_dev1281发布.md)。发布 1280 已验的节点 API 修复，版本递增以便测试机覆盖更新；142,412,154 字节，SHA `60d2130b…`。签名/22 ELF 16KB/Retrofit/手机清单200与完整HTTP下载摘要通过；Downloads 有 APK。按用户要求后续再做功能回归，本轮未安装1281、未动K40；大树分页、BFS组合、跨布局身份仍待完善。

> **2026-10-28 节点 API 后续候选 dev-1280（MI8 已验，未发布）**：[隔离回归与后续对齐](docs/reports/2026-10-28_节点API隔离回归与后续对齐.md)。基于 DP 已提交 1279，补混合筛选晚匹配、Java Pattern 批处理、真实宿主 BFS/DFS、filter/addFilter、范围读数与自身子树查询；原生 QuickJS 保留谓词原始错误，进度参数去宽松转换。1,009 JVM + 50 Node、六入口 150 项、MCP 8 项及三套只读示例真机通过；文档 771 条可由 MCP 读取。APK SHA `54abf19f…`，MI8 已装；LAN 仍 1279，K40 未动。BFS 与本地条件组合、大树完整性/分页和稳定身份仍有边界，不能称全量高级版完全等价。

> **当前发布 dev-1279（2026-10-28，仅 LAN）**：[三引擎节点 API 复核](docs/reports/2026-10-28_三引擎节点API复核与修复.md)：接手 GPT 未提交工作，修复 Rhino UI 线程停止信号、RemoteUiObject/RemoteUiSelector 属性动作/遍历、NodeActionArguments 严格校验、密码脱敏/setText 上限、MCP 嵌套 selector/index/exact；956 JVM + 44 Node 契约通过。142,401,636 字节，SHA `c8c91cfb…`；LAN 已发布，MI8 已装。

> **2026-10-27 节点分析与 AJ Pro 高级版静态对照（未改代码/未发布）**：[逆向对照与优化优先级](docs/reports/2026-10-27_节点分析悬浮窗_AJPro高级版逆向对照.md)。指定 com.boyai.auma 9.3.11-0 APK 重新定向反编译；双方服务清单确认 AJ :script、当前主进程，AJ 音量下退出分析当前未接；属性当前31项/AJ28项，搜索/快照/高级生成器已存在。优先抓取预算/取消和恢复、退出、大树性能、隔离与主题。没有双方真机测速或修复通过结论；DP只读超时，主助手核对。

> **2026-10-27 vivo/iQOO适配候选 dev-1271（未安装/未发布）**：[适配与转交测试](docs/reports/2026-10-27_vivo实况通知适配与测试说明.md)。用户他人vivo Android16已允许并提升标准实况；小米协议0非vivo不支持。分厂商诊断、空闲/故障分离、通道不存在改null；新增自动/文字/ProgressStyle及异常回退，vivo自动尝试进度。436项App JVM、release/R8/16KB/签名通过；APK/三引擎180秒无浮窗测试ZIP/MD在Downloads。仅小米做未发送的标准API对象检查，不等于vivo原子岛验收；需对方桌面运行态反馈。公开仍1269，本机仍1270。


> **2026-10-27 超级岛实机修复候选 dev-1270（已装当前USB手机，未发布）**：[证据与验收](docs/reports/2026-10-27_Android16实况通知与超级岛鉴权排查.md)。USB确认旧模板FocusPlugin onAuthFailed；改补Android16标准实况权限/请求/短文案，真实桌面1项/三引擎3项、单停/全停/清理均通过。427项App JVM、release/R8/签名/16KB通过；R2 SHA `1f1f89d5…`，已安装base.apk一致。诊断分用户开关/小米平台注册/实际通知/Native提升；最终标准通道不混用未注册模板，无需Root或播放音乐。公开仍1269；仅DQKFJB59596L45BI（dash/2602BRT18C），K40/MI8未动。


> **2026-10-27 当前发布 dev-1269（LAN/GitHub latest已完成）**：[发布记录](docs/reports/2026-10-27_dev1269发布.md)。三引擎统一运行通知/超级岛载荷与权限检测，任务状态页、单项/全部停止、幽灵计数修复；包含近期Node UI调度/元数据缓存与按执行资源回收。878项JVM通过；release/R8/签名一致/16KB/Retrofit通过，135.73MiB，SHA `324e5b3a…`。MI8完整LAN下载摘要一致但未安装；GitHub远端digest/代理清单通过，当前网络代理大包150秒超时，优先LAN/Downloads；HyperOS3实际显示待用户验证，K40未动。publish可显式指定真实新版本且保留最低版本保护。


> **2026-10-27 小米超级岛测试候选 dev-1268（未发布/未安装）**：[实现与测试](docs/reports/2026-10-27_小米超级岛脚本运行状态测试版.md)。三引擎聚合运行通知、OS2/3载荷/权限检测、单项token/全部停止、空任务清理与主题化诊断页；执行表同步快照/先登记再启动，修复幽灵计数。461 engine +417 app JVM通过；release/R8/16KB/签名检查通过，135.73MiB，SHA `ddcf70f9…`。APK/三引擎90秒脚本/MD在Downloads，由用户HyperOS3手机安装验证；无上岛实机通过结论，不绕过小米权限，不含主进程重启全面恢复。MI8仍1267，K40未动。

> **2026-10-27 听海播放器细节/律动 1.2.2**：[实现与验收](docs/reports/2026-10-27_听海播放器细节与律动.md)。封面内可关闭原生帧动效、响应式封面/滚动/收起/复制/涟漪；换色保留原 View、旧封面请求 token、seek 提交锁及无障碍调整、音量松手单次写盘。Application 生命周期监听补本地 Node Activity 后台缺口。73 Node通过，MI8 13+5项及真实音量写盘通过；正常入口录屏164帧几何稳定。动效为装饰非FFT；开启仍有成本（单核29.39%），宿主1267沿用，K40未动。

> **2026-10-27 听海运行性能/主题统一 1.2.1**：[测量与修改](docs/reports/2026-10-27_听海运行卡顿与主题统一.md)。暂停停 ticker 的 UI 重画、变化才写 View、轻量播放采样、MediaSession 事件立即/稳定5秒校准、图片索引/歌词两行更新、换色仅先重画可见页。MI8 同机空闲CPU16.36→7.06%、播放20.26→11.99%，八滑P95 31→17ms；58 Node通过。按用户确认只统一听海角色色与原生输入框，宿主1267沿用，K40未动。初次复杂页/持久化等仍有成本。

> **2026-10-27 听海原版 UI / 点击优化 1.2.0**：[实现与验收](docs/reports/2026-10-27_听海原版界面对齐与点击闪屏修复.md)。私有五页工程按原版重做，Palette动态色/播放器定位对照；离屏构建后替换、普通开关局部更新、页缓存、滚动分批创建及原生XML/路径。52 Node通过；MI8深浅/系统主题菜单切换通过，修复旧锚点PopupWindow崩溃，最终12项/113帧有效录屏无均匀空白或白闪；播放暂停MediaSession实测通过。宿主沿用1267未新发布，K40未动。仍有DSP/音频缓存及部分界面差异，不能称100%复刻。

> **2026-10-27 听海功能实现/原版接口**：[实现、验证与边界](docs/reports/2026-10-27_听海功能实现与原版接口接入.md)。五页私有工程已接真实媒体库/队列/歌单/主题/设置、原APK酷我搜索/推荐/榜单/普通分类/播放、酷狗歌词；38 Node + 458 JVM + MI8 13联网检查通过。宿主候选dev-1267补Node硬停后的媒体/接收器/自有通知回收，两脚本互不误停；未推送更新源。42项对照与API MD/JSON同步；QQ/酷狗完整导入、DSP等仍部分缺失。K40未动。


> **此前快照：2026-10-27 听海功能对照（已由上方功能版推进）**：[42项入口、缺项与补修](docs/reports/2026-10-27_听海原版与Node示例功能对照.md)。MI8直接运行原版3.0.9对照；五页示例主要完成导航/海浪播放，搜索/歌单/设置等尚未实现，不能当引擎兼容通过。本轮仅补修私有工程M3真实循环/曲终/跨循环暂停恢复/重建计时，以及白噪音版音量标签；真机通过。宿主沿用1266性能候选，无新APK/发布，K40未动。

> **2026-10-27 未发布：听海 Node UI 性能版**：[当前交付与验收](docs/reports/2026-10-27_听海NodeUI性能与缓存修复.md)。构造器 newRef 补 `$type`，类方法元数据缓存生效；全五页构建约 12.2→5.1 秒，密度采样和首页优先后首屏三次中位 2.86 秒。页面按需创建、重建/快速返回取消、状态栏统一；真实构造器/字段/接收对象、12 秒忙碌输入、播放暂停/100 切页及重建通过。候选 `dev-1266-tinghai-perf` / SHA `9db5ab1a…` 已装 MI8，未发布；K40 未动，Android 16/Mali 待复测。下方 2026-10-26 候选为历史阶段。

> **2026-10-26 未发布：听海 Node UI 兼容修复**：[定位与验收](docs/reports/2026-10-26_听海NodeUI兼容修复.md)。MI8 原脚本复现 reason=6 ANR；改逐次主线程调度、布局合并并保留 JNI/JSON/异步语义。453 引擎/409 应用 JVM、21 Node 合约和最终真机回归通过；候选 `dev-1266-tinghai-candidate` / SHA `0da7501a…` 已装 MI8，公开版本仍 dev-1266，K40 未动。主脚本修正版约 12.2 秒构建，启动性能仍需优化；Android 16/Mali 尚未复测，MCP ui_input 另有待修问题，详见报告。

> **Materi-UI 适配（2026-10-26）**：[Materi-UI 适配与运行](docs/reports/2026-10-26_MateriUI适配运行.md)：用户提供的 MD3 UI 在 dev-1260/Rhino 上跑通；删除不存在的 DocumentFile 导入、`ui.R` 图标/字符串改为系统资源；四页与 BottomNav 实测通过。源码副本在 `samples/Materi-UI-adapted`，手机 `/storage/emulated/0/脚本/Materi-UITest`。
> **Materi-UI 独立 APK（2026-10-26）**：`C:\Users\18101\Downloads\Materi-UI-original-1.0.0.apk`，包名 `com.materi.ui`，1.0.0；源工程 `samples/Materi-UI-packaged`。
> **当前发布 dev-1266（2026-10-26，仅 LAN）**：[Node/M3 历史日志](docs/reports/2026-10-26_MateriUI原生兼容与dev1261.md)：Node UI 日志页回放最近一次运行日志 + 实时 console；M3 Demo 实测历史头/当前输出同屏；135.71 MiB，SHA `19015419…`；MI8 已装。
> **当前发布 dev-1265（2026-10-26，仅 LAN）**：[Node/M3 日志页修复](docs/reports/2026-10-26_MateriUI原生兼容与dev1261.md)：`FileConsoleView` 误映射到 ConsoleView 导致 M3/Node 日志空白，已修复；M3 Demo 实测显示。
> **当前发布 dev-1264（2026-10-26，仅 LAN）**：[全量图标移植](docs/reports/2026-10-26_MateriUI原生兼容与dev1261.md)：Auto.js Pro 967 个 `ic_*_48dp` 全部加入宿主，原工程无 res 可直跑；135.71 MiB，SHA `35209091…`；MI8 已装。
> **当前发布 dev-1262（2026-10-26，仅 LAN）**：[宿主内置图标回退](docs/reports/2026-10-26_MateriUI原生兼容与dev1261.md)：原 zip 无 res/androidResources 也可直接运行 Materi-UI；135.70 MiB，SHA `945bb18b…`；MI8 已装。
> **当前发布 dev-1261（2026-10-26，仅 LAN）**：[Materi-UI 原生兼容](docs/reports/2026-10-26_MateriUI原生兼容与dev1261.md)：DocumentFile 依赖/keep、UI prelude 自动 ui.R、宿主图标 fallback；原 Materi-UI 工程无 wrapper 直跑，MI8 验证通过；135.70 MiB，SHA `0003db6d…`。
> **当前发布 dev-1260（2026-10-26）**：[APK 卡顿优化](docs/reports/2026-10-26_dev1260发布.md)：目录/历史日志后台读取、切页停稳后加载、离屏动画暂停、启动初始化并行、编辑器隐藏日志懒加载、实时日志合并、文档缓存；409 JVM/32 Flutter/59 高亮检查与 MI8 最终包通过；135.70 MiB，SHA `2914e61c…`；GitHub/LAN 已发布，MI8 已装，K40 未动。
> **2026-10-26 最新工作：APK 卡顿优化，本地候选未发布**：[定位与验证报告](docs/reports/2026-10-26_APK卡顿定位与优化.md)。目录/历史日志移到后台、切页停稳后加载、离屏动画暂停、启动遮罩与初始化并行；编辑器隐藏日志懒加载，实时日志合并通知，文档正文缓存。409 JVM、32 Flutter、10 生成/59 高亮检查及 MI8 三引擎并发/页面验证通过；候选 `dev-1259-perf-candidate` / SHA `5c073d3b…`，公开更新仍 dev-1259。K40 未动，具体测量与边界见报告。

> **2026-10-26 当前发布 dev-1259**：[API 文档高亮与结构化详解](docs/reports/2026-10-26_API文档高亮与结构化详解.md)。离线多语言代码高亮、原文复制/换行、章节导航与7篇高频API详解；764条/30来源。实时同步深浅/主题色、保留阅读状态，修复重复历史写入干扰跳转；MCP按section读取并返回language/code示例，仍68名称。完整JVM、10项生成、59项高亮/字符/主题回调检查、MI8最终包36项/942次MCP及41项页面检查通过，跟随系统/单次返回亦通过；135.70MiB，SHA `74e5cdcd…`。GitHub latest/LAN已发布，MI8实际APK摘要一致、主题已恢复/调试关闭/无残留任务；K40未动。1258未公开GitHub的失败候选记录保留于报告，下方旧状态为历史。

> **2026-10-26 当前发布 dev-1257**：[MCP 接入 API 文档](docs/reports/2026-10-26_MCP接入API文档与dev1257.md)。新增 search_api_docs/read_api_doc，与 App 共用 756 条离线文档；按引擎/专题检索并读取参数、兼容说明及原文代码，索引摘要防错配。MCP 59 项规范工具/含别名 68 名称，更新后客户端须重连/刷新。408 JVM、8 生成测试、MI8 最终 R8 包 29 项/857 次 MCP 调用、19 项文档页面/复制及最小三引擎冒烟通过；135.65MiB，SHA `43035e00…`。GitHub latest/LAN 已发布，MI8 实装摘要匹配，K40 未动。文档不是全 API 行为验证证明；下方旧状态为历史。

> **当前发布 dev-1256（2026-10-26）**：[启动主题与文档复制优化](docs/reports/2026-10-26_启动主题统一与文档复制优化.md)。启动/品牌/主页/文档共用深浅底色；Android12+ 同步本应用夜间模式，系统启动图标无白底/裁切。API 文档更紧凑，代码块一键复制保留原文，搜索复位、分页保留展开、收起和返回完善。399 App JVM、MI8 深浅启动采样及最终 R8 包19项文档检查/三引擎冒烟通过；135.61MiB，SHA `a96ce8ba…`。GitHub latest/LAN已发布、MI8已装且base.apk摘要一致，K40未动。当前网络GitHub原始附件直链超时，GH-Proxy清单实拉正常。下方旧版状态为历史记录。

> **当前发布 dev-1255（2026-10-26）**：[API 文档直接打开](docs/reports/2026-10-26_API文档直接打开与dev1255.md)。顶部「API 文档」一点直达离线搜索，不再经过 GuidePage，首次打开后一次返回回主页。Flutter AOT 重建、最终 R8 包 MI8 两轮 9 项检查通过；135.60 MiB，SHA `3f69dc2e…`；GitHub latest/LAN 已发布，MI8 已装且实际 APK 摘要一致，K40 未动。三引擎接口与 756 条索引未变。

> **当前发布 dev-1254（2026-10-26）**：[API 离线文档与发布验收](docs/reports/2026-10-26_API离线文档与dev1254.md)。APK 内「本地使用指南」→「三引擎 API 文档 · 离线搜索」；756 条参考条目，当前使用手册与 Node 专题/传统 Rhino 参考分开标注。397 App JVM、6 文档测试、MI8 文档 11 项及最终 R8 包 11 项复验通过；135.60 MiB，SHA `664410e2…`；GitHub/LAN 已发布，MI8 已装且 base.apk 哈希一致，K40 未动。文档源见 [API_REFERENCE.md](docs/guides/API_REFERENCE.md)、[NODE_UI_API.md](docs/guides/NODE_UI_API.md)。改指南后须运行 `python tools/build-api-reference.py` 并提交生成资产；preBuild 会检查过期。下方旧版状态保留为历史记录。

> **最新候选 1249（2026-10-25，MI8 已验、未发布）**：[Node 资源 Context 与 UI 进程隔离](docs/reports/2026-10-25_Node资源Context与UI进程隔离.md)。补齐 `$autojs.androidContext.loadResources`、独立资源/主题/inflater、编译 layoutId 的 recreate；旧 Node UI/对话框迁到 `:nodeui`，work/settings 保留主进程。843 JVM、7 组 Node 契约、MI8 资源与 20 轮重建及 renderer 退出隔离通过。最终 R8 包 134.26 MiB、SHA `774ba19e…`，22 ELF16KB/Retrofit DEX 通过；更新源仍 dev-1247，K40 未动。Rhino 传统 UI 仍有主进程路径，原听海项目未取得，不得宣称全部高级版 UI 已等价。

给 AI 助手 / 自动化 agent 的项目约定。dsh（DeepSeek Harness）、Codex、Copilot、Claude Code 都会读这个文件。

> **接手总纲（先读这个）**：[`docs/HANDOFF_AI.md`](docs/HANDOFF_AI.md) —— 主架构图、构建/发布、真机测试套路、已知缺口、分支策略。
> **当前发布 dev-1253（2026-10-25，仅 LAN）**：[MCP 指南本地化](docs/reports/2026-10-25_MCP连接指南本地化与dev1253.md)：指南改为 assets 内置 HTML/WebView，App 内打开不联网；AOT 重建，135.40 MiB，SHA `fc9c3312…`；MI8 已装。
> **当前发布 dev-1252（2026-10-25，仅 LAN）**：[MCP 连接指南 App 入口](docs/reports/2026-10-25_MCP连接指南入口与dev1252.md)：设备连接页新增完整指南按钮（Rikkahub 红字截图）；AOT 重建，134.27 MiB，SHA `31eed226…`；MI8 已装；按用户要求不新增 GitHub Release。
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
