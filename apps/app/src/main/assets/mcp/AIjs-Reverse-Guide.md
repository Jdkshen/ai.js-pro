<!-- section:id=overview -->
# AI.js Pro 逆向 MCP 自发现指南

> 适用基线：**dev-1429**。本机公开 MCP 入口 **224 个**（**215 个规范名 + 9 个 Pro 兼容别名**：status 4 · script 11 · workspace 10 · engine 5 · device **27** · terminal 11 · overlay 2 · asset 5 · toolkit **96** · reverse **44**），另有 **16 个只派发族别名**（`dex_*`/`so_*`，**不出现在 tools/list**，见「族前缀别名」）。以上为 K40 dev-1429 实测：`tools/list` 返回 224 项、`serverInfo.version=dev-1429`，`get_status` 的 `toolCount=224`、`canonicalToolCount=215`，`toolCategories` 与上面逐项一致。旧文写的计数已过期，一律以本节与 `tools/list` 为准。
> 本指南部分细节段落写于 dev-1330 时代，若与 `tools/list` 或 [`AIjs-MCP-Guide.md`](AIjs-MCP-Guide.md) 的「dev-1409~1412 行为变化」「dev-1368 行为变化」冲突，以后者为准。其中**逆向类工具现为 44 个**（本指南写于 dev-1330，下面这句列的是当时那 19 个已注册工具，名单已扩充）：`reverse_guide`、`reverse_capabilities`、`reverse_plan`、`apk_decode_project`、`apk_build_project`、`apk_install_framework`、`native_analyze`、`native_functions`、`native_function_detail`、`native_decompile`、`native_xrefs`、`native_compare`、`artifact_fingerprint`、`yara_scan`、`native_emulate`、`frida_status`、`frida_processes`、`frida_eval`、`apk_inject_gadget`。
>
> 注册不等于可用：可选组件可能未安装、平台策略可能禁止执行下载的二进制、Frida使用免Root Gadget集成流程、r2ghidra 为按需安装组件，已在小米8验证伪代码输出。任何结论都必须落到真实 `tools/list`、`apk_component_status` 和 `apk_job_result` 证据上。

## 这份指南解决什么问题

新 AI 最常犯的错误是先猜一个工具名，再把不存在的调用写进流程。这里把三件事分开：

1. **教程**：从 APK/ELF/运行时证据走到结论，并明确哪些结论不能下。
2. **机读计划**：`goal` 对应固定工作流，逐 step 给出工具、参数、变量、组件和缺失状态。
3. **真实执行**：由已注册的 `apk_*`、`file_*`、`zip_*`、`native_*`、`frida_*` 等工具完成；计划本身不执行、不下载、不注入。

```text
reverse_guide(topic=index)      # 章节、工作流、44 个逆向工具状态
reverse_guide(topic=<section>)  # 按 section id 读正文，可分页
reverse_plan(goal=<goal>)       # 取机读 steps；组件和工具状态明确
按 plan 执行可用步骤             # 计划本身不执行
```

## 本版真实边界（`guideVersion` 字段仍为 `dev1330-1`，工具数已按 dev-1412 实测复核）

- **44 个逆向工具已注册**（dev-1429 实测；本节旧文写的「19 个」是 dev-1330 时代的数字），但每个工具仍受组件、平台执行策略、输入预算和用户授权约束。
- 可选组件共 9 个：`jadx`、`apk-resources`、`apktool`、`radare2`、`r2ghidra`、`yara`、`unicorn`、`frida`、`frida-gadget`。`apk_component_install/remove` 的 `component` 枚举就是这 9 个。
- **Apktool 3.0.3** 已在本机真机通过 decode → edit → rebuild：使用私有 `app_process` + AAPT2 wrapper，不把 apktool 类加载进主进程。但**任意 APK 不保证**可解包/回编译，失败必须报原始 stderr。
- **radare2** 的 `native_*` 工具直接读手机路径，不读 APK 快照 locator；要从快照分析 so，先用 `apk_copy_entry(direction=workspace_to_local)` 导出。
- **r2ghidra** 是可选伪代码后端，当前构建仍待完成；只能通过 `apk_component_status` 确认，不能声称已安装，也不能把 `native_decompile` 当作必然可用。
- **Frida免Root**：先 `apk_inject_gadget` 生成重签APK，安装打开后用返回的 `endpoint/token` 连接；无需Root或Server。
- **Unicorn** 只模拟 `codeHex` 原始机器码，不是 Android/ELF/JNI 运行时；不接受 so 路径。
- `artifact_fingerprint` 是内置启发式指纹，**不是完整 APKiD**，不能宣称做了 APKiD 检测或给出 APKiD 规则结论。
- `native_analysis_open` 从 dev-1368 起**会真校验文件 SHA-256**：与入参不一致直接报 `ANALYSIS_STALE_INPUT`，并在错误里给出**完整 64 位** expected/actual（可直接拿 actual 重试）。
  路径策略：它接受工具目录内相对路径与**允许的只读根**（`/system`、`/vendor`、`/apex`、`/data/local/tmp`、`/storage/emulated/0`）；`artifact_fingerprint`/`file_stat` **只接受工具目录内**路径，对 `/system` 会拒绝——外部文件请直接用报错里的 actual 全量哈希。
- `apk_component_status` 只接受 `{}`，**没有** `component` 字段；逐个组件看返回的 `data.components[]`。
- 所有逆向操作默认 `background=true`，返回 `jobId`；必须 `apk_job_status` 到终态，再 `apk_job_result` 分页。**任务登记不代表组件已安装，也不代表授权已取得。**

## 阅读约定

> 本文所有 `${...}` 和 `<...>` 都是占位符，复制前必须替换。`reverse_plan` 给每个变量 `placeholder` 和 `replaceBeforeUse=true`；把 `${WORKSPACE_ID}` 原样发给工具不是默认值，而是错误参数。

- 参数名、必填项、枚举以 `tools/list` 的 `inputSchema` 为准；本文例子按当前 schema 写全必填字段。
- `POST /mcp` 的 JSON-RPC 请求体只是传输外壳；真正参数在 `params.arguments`。
- 页面、结果、游标都以返回的 `nextOffset` / `nextCursor` 为准，不自己算偏移。
- 真实 token、cookie、密钥、设备路径中的个人信息不得写进提示词、日志或本文件；只引用设置页里的占位符。

<!-- section:id=quickstart -->
## 90 秒：从零到可复核的只读证据

### 0. 传输外壳

端点固定为 `http://<手机IP>:<端口>/mcp`。请求头至少包含：

```http
POST /mcp HTTP/1.1
Host: 192.168.10.7:8080
Content-Type: application/json
Accept: application/json, text/event-stream
MCP-Protocol-Version: 2025-03-26
Authorization: Bearer <LAN_TOKEN>
```

`<LAN_TOKEN>` 从 App 的 MCP 设置页读取，**不要把真实 token 嵌进本文、脚本或对话**。当前构建在可信局域网内即使不带 `Authorization` 也可能接受请求，但不要把它当作可依赖的鉴权保证；客户端配置里仍使用同一个 LAN token。`Accept` 同时列出 `application/json` 和 `text/event-stream` 以兼容 Streamable HTTP 客户端；本服务没有 SSE，`GET /mcp` 带 `Accept: text/event-stream` 会返回 405，改用 `POST` JSON-only。

**连不上 / 503 的排查顺序**：① 先调 `get_status.mcp` 看 `listening`（**进程内真实监听** = 服务实例活着 **且 accept 循环在跑**，不是心跳推断）与 `port`；② `listening=false` 时再看 `status`：**`degraded` 表示服务实例在、但 accept 循环已经死了**（看 `listener.alive=false` 与 `listener.lastFailure`，如 `respond_503: OutOfMemoryError: …`）→ 重开 App / 重开 MCP 服务，不是网络问题；`stopped`/`stale`/`error` 才是 **App 进程没在跑或服务没起来**（此时附带 `recovery`，含「**30 秒**后自动重试」；旧的「失败过就永不再试」已废）；③ **不要用 `persistedRunning` 判断健康**（它是旧的持久化布尔，进程被回收后仍可能为 true；`running` 已与 `listening` 同源，只有 `listening=true` 时才是 true）；④ 若返回 **503**，说明**连接队列满**（4 worker + 16 队列），body 是结构化 JSON（`error.code` 与顶层 `errorCode` 均为 `service_busy`、`recovery`、`retryAfterMs=1000`），按 `retryAfterMs` 重试即可——**503 不代表真实负载，也不代表工具失败**。

### 1. 看指南索引与计划

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"reverse_guide","arguments":{"topic":"index"}}}
```

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"reverse_plan","arguments":{"goal":"inspect"}}}
```

`reverse_guide` 的 `data` 含 `guideVersion`、`guideSha256`、`sections[]`、`sectionIds[]`、`workflows[]`、`candidateTools[]`、`content`、`offset`、`nextOffset`、`hasMore`、`truncated`。`reverse_plan` 的 `data` 是计划：`planOnly=true`、`executed=false`。

### 2. 找 APK、开快照、建会话

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"apk_list_available_apks","arguments":{"prefix":"","limit":50}}}
```

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"apk_workspace_open","arguments":{"path":"${APK_PATH}","temporary":true}}}
```

```json
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"apk_session_create","arguments":{"workspaceId":"${WORKSPACE_ID}"}}}
```

记下 `workspaceId`、`sha256`、`editSessionId`、`revision`。`apk_workspace_open` 是复制 + 哈希，源 APK 不会被后续编辑改动。

### 3. 组件状态、独立检查与搜索

```json
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"apk_component_status","arguments":{}}}
```

**`apk_component_status` 的输入是空对象 `{}`**。返回 `data.components[]`，逐项看 `id`、`installed`、`available`、`verified`、`backend`、`externalRequirements`。接口没有 `component` 字段；不要传 `{"component":"jadx"}`，它不会按组件过滤，应以返回数组中的 `id` 为准。

```json
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"apk_verify","arguments":{"workspaceId":"${WORKSPACE_ID}"}}}
```

```json
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"apk_search","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","target":"overview","query":"${QUERY}","queryType":"literal","caseSensitive":false,"matchMode":"contains","prefix":"","includeMatchOffsets":false,"limit":50,"snippetMaxChars":200}}}
```

`apk_search` 的 `overview` 覆盖 ZIP 路径、AXML 文本、资源名/值和 DEX 类/字段/方法/字符串。命中太多时先缩小 `prefix` 或改用 `target=dex_strings`/`native_symbols`，不要用灾难性正则硬扫。

### 4. 签名证据不要混用

```json
{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"apk_read_signature","arguments":{"workspaceId":"${WORKSPACE_ID}"}}}
```

`apk_read_signature` 只读 V1/V2/V3/V3.1 证书和方案元数据，`verified` 始终为 `false`；它不等于 `apk_verify`。`apk_verify` 才做密码学验签和 ZIP/ELF 结构检查，但仍不证明安装成功或业务行为。

<!-- section:id=rpc -->
## JSON-RPC 与工具参数：只发真实存在的调用

MCP 走 `POST /mcp`，方法固定为 `initialize`、`tools/list`、`tools/call`、`resources/*`、`prompts/*`；客户端会话管理由客户端负责。下面的 `name`/`arguments` 才是工具契约。

```json
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"reverse_capabilities","arguments":{}}}
```

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"apk_workspace_open","arguments":{"path":"${APK_PATH}","temporary":true}}}
```

成功时工具结果通常是 `{ok:true,data:{...},nextActions:[...]}`；失败时是 `{ok:false,data:null,error:{code,message,argument,recoverable,retrySameArguments},nextActions:[...]}`。不要把 HTTP 200 当成工具成功，必须看 `ok` 和 `error`。`reverse_guide`/`reverse_plan` 也走这个包装，核心字段在 `data` 内，字段名不变。

### 调用前检查清单

1. `tools/list` 有这个名字吗？没有就停止；`reverse_plan.unsupportedTools` 应已列出。
2. 必填参数齐全吗？例如 `apk_native_disassemble` 需要 `addressKind`、`address`、`instructionMode`、`stopAtSymbolBoundary`、`maxBytes`、`maxInstructions`，少一个就是 `INVALID_ARGUMENT`。
3. 是否在正确的 `workspaceId`/`editSessionId` 上？两个快照的 ID 不能交换。
4. 组件是否需要先 `apk_component_status` → `apk_component_install` → 任务终态？
5. 是否触发了分页/预算？有 `nextCursor`/`nextOffset` 就按它继续，不要重发同一请求。

### 读指南

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"reverse_guide","arguments":{"topic":"${SECTION_ID}","offset":0,"maxChars":6000}}}
```

`topic` 只接受：

- `index`：动态索引（章节、工作流、工具状态），可分页。
- `quickstart`、`rpc`、`workspace`、`jobs`、`components`、`dex`、`native`、`rebuild`、`dynamic`、`fingerprint`、`compare`、`errors`、`workflows`、`limitations`。
- `all`：全文。
- 其它值报错并列出合法 `sectionIds`。

`offset` 是 UTF-16 字符偏移，必须来自 `nextOffset`；`maxChars` 默认 6000、最大 24000。页尾不会切开 UTF-16 代理对；如果预算落在代理对中间，可能少 1 个码元，按 `nextOffset` 继续。**全文不保证一页读完**：`topic=all` 默认只返回前 6000 字符，必须继续分页。

### 读计划

```json
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"reverse_plan","arguments":{"goal":"native"}}}
```

`goal` 只接受 `inspect`、`decode`、`rebuild`、`native`、`dynamic`、`fingerprint`、`emulate`、`compare`。返回的 `steps[].args` 仍是模板，`${...}` 必须替换。`readiness=blocked` 表示有必填工具/组件未注册；`ready_with_component_check` 表示工具都在，但必须先查组件。

<!-- section:id=workspace -->
## 工作区、不可变快照与版本号

### 快照不是编辑

`apk_workspace_open` 把源 APK 复制到工具存储并计算 `sha256`；后续 `apk_session_create` 建写时复制会话。`apk_edit_text`、`apk_patch_bytes`、`apk_resource_edit`、`apk_native_patch_*` 都只写会话，不改快照，更不改源文件。

```json
{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"apk_workspace_open","arguments":{"path":"${APK_PATH}","temporary":false}}}
```

- `path` 必须在工具目录内；`aijs://current-apk` 只在已有 APK 上下文时可用；已保存工作区用 `aijs://workspace/<workspaceId>` 且 `temporary=false`。
- 单包上限 512 MiB，快照总量 1 GiB，最多 16 个快照；超限先 `apk_workspace_delete` 或换临时工作区。
- 返回的 `sha256` 是快照身份；跨版本比较必须记录它，不能只看文件名。

### 会话、revision 与 targetVersion

`apk_session_create` 返回 `editSessionId` 和 `revision`。每次写入都会改变会话 `revision`；分页游标绑定 revision，编辑后旧游标会报“内容已变更或游标已过期”。单工作区最多 8 个会话，单会话最多 2000 条变更/512 MiB。

读取类工具返回 `targetVersion`（文件/内容版本）。修改时原样传回：

```json
{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"apk_edit_text","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","locator":"${LOCATOR}","targetVersion":"${TARGET_VERSION}","edits":[{"mode":"replace_match","matchText":"${UNIQUE_OLD_TEXT}","writeText":"${NEW_TEXT}"}]}}}
```

看到“内容已改变”就重新读取并拿新 `targetVersion`，不要重试旧参数。新建目标可以用 `targetVersion="missing"` 加**单个** `write_target` 编辑；`write_target` 的 `matchText` 必须为空串（可省略，默认空串），`replace_match`/`insert_*` 模式必须给出且唯一匹配。

### 从快照导出条目

radare2 系列 `native_*` 工具直接读手机路径，不读 APK 快照 locator。要从快照拿 so，必须显式导出：

```json
{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"apk_copy_entry","arguments":{"direction":"workspace_to_local","workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","locator":"zip_entry:lib/arm64-v8a/libnative.so","localPath":"${LOCAL_SO_PATH}","overwrite":false}}}
```

- `locator` 必须是 **`zip_entry:`** 前缀；`entry:lib/...` 不是当前 schema 的格式。
- `editSessionId` 是 schema 必填项，即使 `direction=workspace_to_local` 也要传。
- `localPath` 必须在工具目录内，且默认 `overwrite=false`：目标已存在会失败，换新文件名或显式覆盖。
- 导出成功返回实际 `localPath`，后续 `native_analyze`/`native_functions`/`native_decompile` 等把这个路径作为 `path`。

### 检查点与回滚

```json
{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"apk_session_checkpoint","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","label":"before-patch","expectedRevision":"${REVISION}"}}}
```

- `apk_session_checkpoints` 列出检查点和各自 revision；最多 8 个检查点。
- 回滚必须带当前 `expectedRevision`，返回新 revision；不要拿旧 revision 重试。
- 检查点只保存变更条目，不是完整 APK 备份；源快照始终独立。

### 构建与签名

```json
{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"apk_build","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","outputName":"target_mcp_signed.apk","overwrite":false,"sign":true}}}
```

- `apk_build` 会再跑一次结构检查；失败不导出。
- `outputName` 是文件名或工具目录内绝对路径，不能覆盖源 APK；已存在时必须 `overwrite=true`。
- `sign=true` 使用本机 AI.js Pro 身份重签并移除原签名，不能保留原作者证书；`sign=false` 导出未签名包，通常不能直接覆盖安装。
- 返回 `sourceUnchanged=true`、输出 `sha256`、`verification`、`signatureSummary`。仍要用 `apk_verify` 对实际输出路径独立复核。
- `apk_read_signature` 的元数据不是验签；`apk_verify` 的密码学结论也不等于安装/业务行为通过。

<!-- section:id=jobs -->
## 后台任务：完整状态、分页与取消

### 哪些调用会进后台

- **所有逆向操作默认后台**：`apk_decode_project`、`apk_build_project`、`apk_install_framework`、`artifact_fingerprint`、`yara_scan`、`native_*`、`frida_*` 的 `background` 默认都是 `true`，立即返回 `jobId`。
- 既有 APK 工具中，`apk_component_install`、`apk_decompile_class`、`apk_decompile_method`、`android_resources_compile` 也默认后台；`apk_job_start` 只接受它声明的 operation。
- **登记任务不等于组件已安装**：`apk_job_start`/默认后台只负责排队；缺组件、缺权限或平台禁止执行会在 worker 里失败。先 `apk_component_status`，再安装，再启动依赖组件的任务。

```json
{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"native_analyze","arguments":{"path":"${LOCAL_SO_PATH}","background":true,"timeoutSeconds":180}}}
```

### 状态机（以返回为准）

| state | 含义 | 终态 |
|---|---|---|
| `QUEUED` | 已入队 | 否 |
| `RUNNING` | worker 正在执行 | 否 |
| `CANCELLING` | 已请求取消，等待 worker 真正结束 | 否 |
| `SUCCEEDED` | 完成并保留结果 | 是 |
| `FAILED` | 失败；`reason` 有信息 | 是 |
| `CANCELLED` | 已取消并结束 | 是 |
| `INTERRUPTED` | 工具进程重启，任务未自动重放 | 是 |

`progress.percent` 只在结束时为 100，过程中不要拿它推断剩余时间。`resultAvailable=true` 才可读结果。`CANCELLING` 不是完成，必须继续轮询。

```json
{"jsonrpc":"2.0","id":21,"method":"tools/call","params":{"name":"apk_job_status","arguments":{"jobId":"${JOB_ID}"}}}
```

轮询接口没有 `waitMillis`，不要发明长轮询参数。任务最多排队 4 个、2 个 worker，`timeoutSeconds` 1..1800（逆向操作、组件安装/decompile 与后台任务同用这一个上界；大工程回编请显式给 900~1800），超时会进入取消流程。

### 结果分页

```json
{"jsonrpc":"2.0","id":22,"method":"tools/call","params":{"name":"apk_job_result","arguments":{"jobId":"${JOB_ID}","offset":0,"maxChars":16000}}}
```

返回 `content`、`nextOffset`、`hasMore`、`totalChars`。下一页把 `offset` 设为上次 `nextOffset`，不要按行号或字节数自己算。结果文件上限 24 MiB；`apk_job_result.maxChars` 默认/上限 **1 MiB**（dev-1380；旧版 200000）。

**终止条件只有一个：`hasMore=false`。** 不要写成 `while (hasMore || nextOffset)`，也不要在 `hasMore=false` 后继续读；`nextOffset` 只是下一页位置，不是“还有内容”的独立信号。

```text
offset = 0
do:
  r = apk_job_result(jobId, offset, maxChars)
  offset = r.nextOffset
while r.hasMore == true
```

### 取消

```json
{"jsonrpc":"2.0","id":23,"method":"tools/call","params":{"name":"apk_job_cancel","arguments":{"jobId":"${JOB_ID}"}}}
```

取消只是请求：调用后继续 `apk_job_status`，直到 `CANCELLED`/`FAILED`/`INTERRUPTED`。已提交的写操作可能在取消前完成；取消不是回滚。工具进程重启后任务是 `INTERRUPTED`，没有 `resume`，需要重新评估后再显式启动新任务。

### 任务读不到

`apk_job_status` 报“任务不存在”时，先 `apk_job_list` 对账；不要拿旧 `jobId` 猜。`apk_job_result` 报“任务尚无结果”时先读状态，不要连续重试结果接口。

<!-- section:id=components -->
## 组件：先查、再装、再用

### 先查再装

```json
{"jsonrpc":"2.0","id":30,"method":"tools/call","params":{"name":"apk_component_status","arguments":{}}}
```

只接受 `{}`。返回 `data.components[]`，每项含 `id`、`installed`、`available`、`verified`、`backend`、`requiresExecutableDownloadPolicy`、`externalRequirements`、`manifestUrl`。`available=false` 不等于“没下载”：可能是 Android 版本低于 8、ABI 不匹配、下载执行策略不允许、组件文件不完整或外部服务未就绪。

### 当前 8 个组件

`apk_component_install` 和 `apk_component_remove` 的 `component` 枚举只有：

```text
jadx, apk-resources, apktool, radare2, r2ghidra, yara, unicorn, frida
```

| 组件 | 用途 | 额外条件 |
|---|---|---|
| `jadx` | Java 类/方法阅读 | Android 8+，隔离 app_process |
| `apk-resources` | AAPT2 资源编译 / apktool 的 AAPT2 wrapper | Android 8+，可执行下载策略 |
| `apktool` | 完整工程 decode/build/framework | Android 8+，私有 app_process；产物不保证任意 APK 成功 |
| `radare2` | native_analyze/functions/detail/xrefs/compare | 可执行下载策略 |
| `r2ghidra` | native_decompile 伪代码 | **build pending**；需匹配 radare2 ABI |
| `yara` | yara_scan | 可执行下载策略；规则 ≤1 MiB |
| `unicorn` | native_emulate | 可执行下载策略；仅 CPU 模拟 |
| `frida` | frida_status/processes/eval | 免Root客户端，连接已打开的Gadget应用，传endpoint/token |
| `frida-gadget` | apk_inject_gadget | 官方17.25.2四架构库，按需下载；只复制入目标APK |

```json
{"jsonrpc":"2.0","id":31,"method":"tools/call","params":{"name":"apk_component_install","arguments":{"component":"radare2","background":true,"timeoutSeconds":900}}}
```

- 默认后台返回 `jobId`；按 `jobs` 章节轮询到 `SUCCEEDED`，再重试原操作一次。
- 组件安装会下载、校验、展开；需要用户明确同意。**同一任务里用户已经授权过安装/下载时，不要重复走一遍权限询问**；沿用该授权，仍要记录安装了哪个组件、哪个 URL/版本。
- `r2ghidra` 提供 Android arm64 的独立 C++ 插件和 SLEIGH 数据（不需要桌面 Ghidra）；安装完成仍需以真实调用结果为准；`native_decompile` 返回缺插件错误时，报告组件缺口，不要伪造伪代码。
- `apk_component_remove` 可移除单个组件，保留源工程和另一个组件。

### 组件缺失的错误处理

`apk_decompile_class`/`apk_decompile_method` 缺 JADX 时报 `COMPONENT_NOT_INSTALLED`；`android_resources_compile` 缺 `apk-resources`、apktool 缺 `apktool`、radare2 缺 `radare2`、yara/unicorn/frida 缺对应组件时同理。正确顺序：

1. `apk_component_status` 确认缺哪个；
2. `apk_component_install(component=<id>, background=true)`；
3. 轮询 `apk_job_status` 到 `SUCCEEDED`；
4. 再重试原操作一次。

不要在组件还没装好时循环重试原操作。**任务已登记/已排队不是“组件已安装”**；只认 `apk_component_status` 的明确字段和安装任务的成功终态。

<!-- section:id=dex -->
## DEX：先定位，再读 Java；不要拿阅读产物当源码

### 定位符

- 类：`dex_class:Lcom/example/Foo;`
- 方法：`dex_method:Lcom/example/Foo;->bar(I)V`，描述符完整，重载不能省略参数。
- 同名类跨 DEX：加 `dexEntry`，或用返回的 `uniqueLocator`/`dex_entry:<entry>!dex_method:...`。
- 普通条目：`zip_entry:path/in/apk`；AXML：`axml:AndroidManifest.xml`；资源：`resource:...`。
- locator 来自 `apk_dex_search_members`、`apk_dex_read_class_outline`、`apk_list` 或搜索结果，不要手写猜的描述符。

### 首选：apk_read_text 直接读方法 Smali（.4 实战反馈）

> 来源：`.4` 的 MCP反馈-云镜逆向会话：「`apk_read_text(locator="dex_method:…")` 直接给方法 smali 是本次破案的关键（`.packed-switch` 表就是 opcode→语义），建议文档里重点写」。这是首选路径：先读 Smali，不要一上来就用 JADX。

**为什么首选**：`apk_read_text` 对 `dex_method:` 定位符返回 baksmali 的完整方法体（从 `.method` 到 `.end method`），保留 `.packed-switch`/`.sparse-switch` 表、寄存器编号和真实 opcode；不经过反编译近似，也不会被 JADX 展平成 if/else。

**步骤 1：先拿精确方法 locator**（重载必须带完整参数描述符）：

```json
{"jsonrpc":"2.0","id":35,"method":"tools/call","params":{"name":"apk_dex_search_members","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","query":"${METHOD_OR_SYMBOL}","queryType":"literal","matchMode":"contains","caseSensitive":false,"scope":"method","prefix":"${PACKAGE_PREFIX}","limit":50,"snippetMaxChars":200,"includeMatchOffsets":true}}}
```

搜索结果里取 `locator`（跨 DEX 同名类用 `uniqueLocator`/`dexEntry`）；不要手写猜测的 `dex_method:`。

**步骤 2：直接读方法 Smali**（当前 schema 仍要求带分页参数；先传默认 500/49152/0/0）：

```json
{"jsonrpc":"2.0","id":36,"method":"tools/call","params":{"name":"apk_read_text","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","locator":"dex_method:Lcom/example/Foo;->bar(I)V","limit":500,"maxChars":49152,"startLine":0,"startColumn":0}}}
```

返回的 `data.text` 就是方法 Smali；`data.locator`/`targetVersion`/`editable` 原样保留。方法大于一页时看 `hasMore`/`nextOffset` 或 `nextLine`/`nextColumn`：续读优先用 `offset=nextOffset`（Unicode 码点），也可用 `startLine`/`startColumn`；普通 `zip_entry:` 文本的 offset 现在会真正切片，不再从文件头返回整段。

**步骤 3：在 Smali 里读 switch 表和 opcode**：

- 搜 `data.text` 里的 `.packed-switch` / `.sparse-switch`：其后的标签顺序就是 case 索引→目标标签；再用 `apk_dex_read_class_outline`/`apk_dex_call_graph` 补充类和调用关系。
- 需要修改时，拿着 `targetVersion` 调 `apk_edit_text`（`matchText` 必须唯一），再用 `apk_session_check`；Smali 能编译通过不等于业务语义正确。
- `apk_decompile_method`/JADX 仅在需要 Java 可读伪代码时作为补充；伪代码不能当源码，也不能反推 `.packed-switch` 的精确表。

**怎么验证**：
1. `apk_dex_search_members` 返回的 `locator` 与读取时 `data.locator` 一致；
2. `data.text` 以 `.method` 开头、以 `.end method` 结束，且包含你搜索的方法名/描述符；
3. 在同一 `targetVersion` 下用 `apk_decompile_method`（可选 JADX）对照方法签名/常量；若 JADX 展开 switch 而 Smali 有 `.packed-switch`，以 Smali 的表为准；
4. 修改后 `apk_session_check(runBuildChecks=true)` 成功且 `apk_build(sign=true)` 产物可安装；回读 `apk_read_text` 确认改动落盘。


### 精确搜索

```json
{"jsonrpc":"2.0","id":40,"method":"tools/call","params":{"name":"apk_dex_search_members","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","query":"${SYMBOL_QUERY}","queryType":"literal","matchMode":"contains","caseSensitive":false,"scope":"all","prefix":"","limit":100,"snippetMaxChars":200,"includeMatchOffsets":true}}}
```

`scope` 可用 `method`、`name`、`descriptor`、`string`、`reference`、`all`。结果有预算和截断标记，`truncated=true` 时缩小查询，不要假设“没有更多”。

### 类大纲与调用图

```json
{"jsonrpc":"2.0","id":41,"method":"tools/call","params":{"name":"apk_dex_read_class_outline","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","locator":"${CLASS_LOCATOR}","limit":200}}}
```

```json
{"jsonrpc":"2.0","id":42,"method":"tools/call","params":{"name":"apk_dex_call_graph","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","locator":"${METHOD_LOCATOR}","direction":"both","methodResolution":"dispatch","maxDepth":3,"maxNodes":300,"maxEdges":1000,"includePotential":true,"includeExternal":false}}}
```

调用图只覆盖静态可解析边；反射、动态代理、字符串拼接类名和运行时注册会缺失。

### Java 阅读流程

```json
{"jsonrpc":"2.0","id":43,"method":"tools/call","params":{"name":"apk_decompile_class","arguments":{"workspaceId":"${WORKSPACE_ID}","locator":"${CLASS_LOCATOR}","background":true,"timeoutSeconds":180,"maxChars":16000}}}
```

按 `jobs` 章节轮询 `apk_job_status`，用 `apk_job_result(offset=nextOffset)` 读取，只在 `hasMore=true` 时继续。

**Java 伪代码不等于源码。** JADX 输出是反编译器的可读近似：可能丢泛型/注解/注释，可能把 `switch`、内联、合成类还原得和原工程不同，也可能根本不能编译。它不能直接喂给 `apk_build`；`apk_build` 只做 ZIP/条目/结构检查和重签，不做 Java 源码编译。需要修改时用 Smali/DEX 定位符 + `apk_edit_text`，并用 `apk_session_check` 验证；能通过检查也不等于原业务语义。

**混淆类被 JADX 改名/内联时**：`apk_decompile_class` 仍可能报 `JADX未找到类`；这不是 locator 写错，JADX 无法为合成/内联类还原源码。两条可执行绕法：

1. **从调用方找内联代码**：先用 `apk_dex_xref` 找 caller（`methodResolution:"dispatch"`、`limit:50`），再对 caller 调 `apk_decompile_method`，在伪代码/Smali 里搜目标常量、字符串或方法名；内联代码通常就在 caller 里。
2. **直接读 Smali（首选，不依赖 JADX）**：用 `apk_read_text` 的 `dex_method:` 定位符读原方法，类被改名/内联也能看到真实字节码：
```json
{"jsonrpc":"2.0","id":46,"method":"tools/call","params":{"name":"apk_read_text","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","locator":"dex_method:Lcom/example/Foo;->bar(I)V","limit":500,"maxChars":49152,"startLine":0,"startColumn":0}}}
```

locator 从 `apk_dex_search_members`/`apk_dex_read_class_outline` 取；返回的 `data.text` 从 `.method` 到 `.end method`。需要 Java 可读伪代码时再用 `apk_decompile_class`，不要把 `JADX未找到类` 当成“方法不存在”。

**怎么验证**：先用原 class locator 复现 `JADX未找到类`；再用同一方法 locator 的 `apk_read_text`，确认 `data.text` 以 `.method` 开头、以 `.end method` 结束并包含方法名/描述符；若是内联，在 caller 的 `apk_decompile_method` 或 Smali 里找到对应常量/调用。

**B18 非 ASCII 类名/文件名**：批量反编译产物文件名可能含非 ASCII 类名（如 `okhttp3.internal.io.ვ.java`），部分文件/压缩工具链会踩坑。机器处理时做 **name→ASCII 映射**（base64/hex 安全名 + 单独 manifest 记录原名），或跳过/转义；不要把文件名当类名。`apk_read_text` 用 locator、`apk_job_export` 用指定 `outPath`，不经过反编译文件名，不受影响。


### Apktool 工程路线

`apk_decode_project` 可以直接把 APK 解成 Manifest/XML/Smali 工程；这是独立于 JADX 的路线：

```json
{"jsonrpc":"2.0","id":44,"method":"tools/call","params":{"name":"apk_decode_project","arguments":{"path":"${APK_PATH}","outDir":"${OUT_DIR}","decodeResources":true,"background":true,"timeoutSeconds":300}}}
```

`outDir` 必须是父目录存在的新目录；工具不会覆盖源 APK/目录。解码成功返回 `path`、`backend=apktool`、`rebuildGuaranteed=false`。**真机 3.0.3 已通过 decode/edit/rebuild 样例，但任意 APK 不保证成功**；资源或 Manifest 异常时要保留原始诊断并停止，不要假称已完整反编译。

<!-- section:id=native -->
## 原生 ELF：Capstone 静态路径与 radare2 路径分开

### 两条路径

- **既有 `apk_native_*`**：用 `workspaceId` + `editSessionId` + `locator=zip_entry:...`，直接读 APK 快照（Capstone）。
- **radare2 系列 `native_*`**：只接受手机文件 `path`，不接 `workspaceId`/`locator`。先从快照导出 so，再传 `localPath`。
- **`native_emulate`**：只接受 `codeHex` 原始机器码，不接 `path`/`locator`；详见 `emulate` 章节。

**怎么选**：已导出/本机 so、要 r2ghidra 伪代码 → `native_*`（path）；APK 内 so、不导出、要 patch → `apk_native_*`（workspace/session/locator）；要从 APK 走 r2 → 先 `apk_copy_entry direction=workspace_to_local` 再传 path。不要两套参数混用。

### 从快照导出 so（新 native 工具的前置）

```json
{"jsonrpc":"2.0","id":50,"method":"tools/call","params":{"name":"apk_copy_entry","arguments":{"direction":"workspace_to_local","workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","locator":"zip_entry:lib/arm64-v8a/libnative.so","localPath":"${LOCAL_SO_PATH}","overwrite":false}}}
```

`locator` 必须是 `zip_entry:`，不要写 `entry:`；`editSessionId` 必填；`localPath` 在工具目录内且默认不能已存在。导出后再用 `path=${LOCAL_SO_PATH}` 调用新工具。

### 既有 Capstone 检查

```json
{"jsonrpc":"2.0","id":51,"method":"tools/call","params":{"name":"apk_native_inspect","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","locator":"zip_entry:lib/arm64-v8a/libnative.so"}}}
```

`inspect` 返回位数、架构、段/节、导入、`disassembler` 和 `instructionCoverage`。so 可能按 ABI 分包；先确认路径存在，别把 `arm64-v8a` 的地址拿到 `armeabi-v7a` 上用。

### 地址模式不要混

- `addressKind=virtual`：ELF 虚拟地址，来自符号、反汇编目标或 `apk_native_inspect`。
- `addressKind=file_offset`：文件偏移，工具会映射到已加载段；不能把文件偏移当虚拟地址。
- 先 `apk_native_map_address` 确认 `isLoaded`、`hasFileBytes`、`executable`、`virtualAddress`、`fileOffset` 和所在段。

```json
{"jsonrpc":"2.0","id":52,"method":"tools/call","params":{"name":"apk_native_map_address","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","locator":"zip_entry:lib/arm64-v8a/libnative.so","addressKind":"virtual","address":"${ADDRESS_HEX}"}}}
```

### ARM32 Thumb 规则

ELF 符号 `st_value` 最低位是 **T-bit**：`1` 表示 Thumb，实际指令地址最低位不参与取指。`apk_native_disassemble` 的 `instructionMode=auto` 会按 T-bit 推断 Thumb，并在解码前对齐；也可显式传 `thumb`。不要把带 T-bit 的符号地址当文件偏移；记录证据时写清原始符号值和最终用于解码的地址。AArch64 固定 4 字节对齐；`maxBytes`/`maxInstructions` 只做上界，不保证按函数边界停。

### 反汇编与 xref（Capstone）

```json
{"jsonrpc":"2.0","id":53,"method":"tools/call","params":{"name":"apk_native_disassemble","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","locator":"zip_entry:lib/arm64-v8a/libnative.so","addressKind":"virtual","address":"${ADDRESS_HEX}","instructionMode":"auto","stopAtSymbolBoundary":false,"maxBytes":256,"maxInstructions":80}}}
```

```json
{"jsonrpc":"2.0","id":54,"method":"tools/call","params":{"name":"apk_native_xref","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","locator":"zip_entry:lib/arm64-v8a/libnative.so","addressKind":"virtual","address":"${ADDRESS_HEX}","instructionMode":"auto","scanKind":"code","referenceKinds":["call","jump","data","unknown"],"maxScanBytes":67108864,"limit":50}}}
```

Capstone 可用时覆盖 AArch64、ARM/Thumb、x86、x86-64；不可用时只回退有界 AArch64 常见指令子集。`xref` 只覆盖直接静态引用和可静态解析的重定位槽；间接跳转、JNI 动态注册、数据流和完整调用图不保证。

### 新 radare2 工具（直接 path）

| 工具 | 必填参数 | 说明 |
|---|---|---|
| `native_analyze` | `path` | ELF/二进制元数据，不执行目标 |
| `native_functions` | `path`（`offset`/`limit` 有默认） | 函数列表；`offset` 是**条目索引，不是地址**，用 `nextOffset` 续页，`limit≤200`；返回 `heuristic:true`，不保证覆盖全部函数 |
| `native_function_detail` | `path`、`address` | `address` 是十进制或 `0x` 字符串，不是表达式 |
| `native_decompile` | `path`、`address` | 需要 radare2 + r2ghidra；伪代码不是源码 |
| `native_xrefs` | `path`、`address` | 到该地址的引用，`offset`/`limit` 分页 |
| `native_compare` | `path`、`otherPath` | 有界字节差异 + radare2 元数据，不是语义等价 |

`native_functions` 的 `offset` 是**函数条目索引**（不是地址）：跨页把上次 `nextOffset` 传给 `offset`；不要拿 `address` 当 offset。返回值 `heuristic:true` 表示启发式扫描，**不保证覆盖全部函数或边界准确**；要按地址看单个函数用 `native_function_detail`。

**怎么验证**：`native_functions {path,offset:0,limit:10}` 后用返回的 `nextOffset` 取下一页；确认响应 `heuristic:true`，并在证据里标注“启发式列表，不是完整函数表”。

```json
{"jsonrpc":"2.0","id":55,"method":"tools/call","params":{"name":"native_analyze","arguments":{"path":"${LOCAL_SO_PATH}","background":true,"timeoutSeconds":180}}}
```

```json
{"jsonrpc":"2.0","id":56,"method":"tools/call","params":{"name":"native_decompile","arguments":{"path":"${LOCAL_SO_PATH}","address":"0x12340","background":true,"timeoutSeconds":180}}}
```

`native_decompile` 的 `address` 必须是字符串（十进制或 `0x`），不要传数字/表达式。r2ghidra **为按需安装组件（6.2.4 / ABI152）**：先 `apk_component_status` 确认 `r2ghidra.installed/available`，缺插件时如实报 `COMPONENT_NOT_INSTALLED`/`COMPONENT_FAILED`，不要声称生成了伪代码。

### native_emulate 不是 ELF 加载器

`native_emulate` 只接受 `codeHex`（1..65536 字节的十六进制机器码），不是 `path`，也不是 so 文件。可选 `dataHex`（≤4096 字节）、`registers`、`timeoutMs`（≤1000）、`instructionLimit`（≤100000）。架构为 `arm64`、`arm`、`thumb`、`x86`、`x86_64`。

```json
{"jsonrpc":"2.0","id":57,"method":"tools/call","params":{"name":"native_emulate","arguments":{"architecture":"arm64","codeHex":"${CODE_HEX}","dataHex":"","timeoutMs":100,"instructionLimit":10000,"background":true,"timeoutSeconds":60}}}
```

它不是 Android 运行时：JNI、binder、系统调用、TLS、反调试、真实内存布局和并发都可能不同；模拟成功只说明“在这组受限输入/内存模型下走到某个状态”。

<!-- section:id=rebuild -->
## 重建：会话编辑与 Apktool 工程两条路线

### 路线 A：会话编辑 + apk_build

1. `apk_workspace_open` → `apk_session_create`；
2. 读取目标拿 `targetVersion`；
3. `apk_session_checkpoint`；
4. `apk_edit_text` / `apk_patch_bytes` / `apk_resource_edit` / `apk_native_patch_*`；
5. `apk_session_check(runBuildChecks=true)`；
6. `apk_session_diff` 复核；
7. `apk_build(sign=...)`；
8. `apk_verify(path=<实际输出>)`。

```json
{"jsonrpc":"2.0","id":60,"method":"tools/call","params":{"name":"apk_edit_text","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","locator":"${LOCATOR}","targetVersion":"${TARGET_VERSION}","edits":[{"mode":"replace_match","matchText":"${UNIQUE_OLD_TEXT}","writeText":"${NEW_TEXT}"}]}}}
```

- 一次最多 200 条编辑，基于同一份不可变原文；`matchText` 必须唯一。
- DEX/AXML 会走编译，失败不会留下半截修改；普通文本最大 10 MiB。
- AXML 写入/编辑拒绝 DOCTYPE/ENTITY；资源读写用 `apk_resource_read`/`apk_resource_edit`/`apk_resource_add`，不要拿普通 `zip_entry:` 去改 `resources.arsc`。
- 保留条目（`classes*.dex`、`resources.arsc`、Manifest 等）不能走普通删除/字节补丁；用 DEX/资源专用工具。

### AXML / AndroidManifest 的读与改（P1-F；无需新工具）

AXML 不是普通文本，但也有**已接通**的读写路径：

1. 读：`apk_read_text(locator="axml:AndroidManifest.xml")` 或 `apk_read_text(locator="zip_entry:AndroidManifest.xml")`；返回解码后的 XML 文本和 `targetVersion`。
2. 改：`apk_edit_text(locator="axml:AndroidManifest.xml", targetVersion, edits=[{"mode":"write_target","matchText":"","writeText":"<完整XML>"}])`。工具内部用 `ToolkitResources.encodeXml` 重新编码 AXML，并原子写回会话；失败不会留下半截修改。
3. 或走工程路线：`apk_decode_project` 解出可读 XML → `file_edit_text` 改 → `apk_build_project` 回编。

边界：`apk_patch_bytes` 只做等长原始字节补丁，遇到 AXML/Manifest 会拒绝并改指向上面路径；`apk_resource_read/edit/add` **只操作 resources.arsc**，不等于 AXML 编辑。AXML 编码要求 XML 合法、实体白名单、文本 ≤10 MiB；编码失败或结构不合法会明确报错，不伪造成功。

### 路线 B：Apktool 工程 decode/build

```json
{"jsonrpc":"2.0","id":61,"method":"tools/call","params":{"name":"apk_decode_project","arguments":{"path":"${APK_PATH}","outDir":"${PROJECT_DIR}","decodeResources":true,"background":true,"timeoutSeconds":300}}}
```

```json
{"jsonrpc":"2.0","id":62,"method":"tools/call","params":{"name":"apk_build_project","arguments":{"projectDir":"${PROJECT_DIR}","outPath":"${BUILT_PROJECT_APK}","sign":true,"background":true,"timeoutSeconds":300}}}
```

- `apk_decode_project`：`outDir` 必须是父目录存在的新目录；返回 `rebuildGuaranteed=false`。
- `apk_build_project`：`projectDir` 必须有 `apktool.yml`；`outPath` 必须是不存在的新文件；需要 `apktool` + `apk-resources`。默认 `sign=false`，`sign=true` 使用本应用签名并验签，不保留旧签名。
- 真机 **Apktool 3.0.3** 已通过样例 decode → edit → rebuild，使用私有 `app_process` 和 AAPT2 wrapper（只给 AAPT2 子进程设置 `LD_LIBRARY_PATH`，不污染 ART 系统库）。这不是“任意 APK 都能成功”的保证；失败时保留 stderr 和组件版本。
- `apk_install_framework` 把厂商 framework APK 装进 Apktool 私有缓存，不装进 Android 系统；`tag` 只允许字母数字点下划线横线。

```json
{"jsonrpc":"2.0","id":63,"method":"tools/call","params":{"name":"apk_install_framework","arguments":{"path":"${FRAMEWORK_APK}","tag":"vendor","background":true,"timeoutSeconds":180}}}
```

### 不能下的结论

- `valid=true` 只说明结构检查通过；不证明业务行为、安装成功率、反调试或商店合规。
- `apk_build` 只做 ZIP/条目/结构检查和重签，不做 Java 源码编译；不要用 `apk_build` 冒充工程构建。
- `apk_build_project` 成功也不保证目标 App 能启动或业务正常；必须记录构建日志、输出 SHA-256 和独立 `apk_verify` 结果。

<!-- section:id=dynamic -->
## 免Root Gadget：生成APK、安装打开、MCP连接

本版仅提供免Root集成，不需要Root或Frida Server。`apk_inject_gadget` 将官方17.25.2 Gadget复制到APK，直接修改二进制Manifest，新增启动Provider和独立DEX；原DEX、资源表和其他资源不重编。输出自动ZIP对齐、重新签名并验签，原APK保留。

### 完整调用顺序

```json
{"name":"apk_component_install","arguments":{"component":"frida-gadget","background":true,"timeoutSeconds":900}}
{"name":"apk_component_install","arguments":{"component":"frida","background":true,"timeoutSeconds":900}}
```

旧版Frida客户端会显示 `needsUpdate=true/available=false`；重新安装frida会升级为 `v2-token`，无需卸载旧组件。每个安装都先轮询 `apk_job_status`，终态用 `apk_job_result` 按 `nextOffset` 读完；成功后继续。

```json
{"name":"apk_inject_gadget","arguments":{"path":"target.apk","outPath":"target-gadget.apk","port":27042,"background":true,"timeoutSeconds":300}}
```

同样读完集成任务结果，保存 `path/packageName/endpoint/token/sha256`。**这一步不会安装或卸载应用**。由用户安装输出APK并打开，之后再连接。重签证书与原应用不同，系统不能直接覆盖安装；必须先保留原数据并安排安装，不要自动卸载原应用。

```json
{"name":"frida_status","arguments":{"endpoint":"127.0.0.1:27042","token":"替换为实际返回的token"}}
{"name":"frida_processes","arguments":{"endpoint":"127.0.0.1:27042","token":"替换为实际返回的token"}}
{"name":"frida_eval","arguments":{"endpoint":"127.0.0.1:27042","token":"替换为实际返回的token","pid":12345,"script":"send({platform:Process.platform,arch:Process.arch});","durationMs":1000}}
```

`pid`必须替换为该次 `frida_processes` 返回的JSON整数。以上为 `tools/call` 的 params；完整RPC格式见 rpc 章节。所有后台任务均要读取最终结果，注册/排队不等于成功。

### 加载及连接行为

- Gadget只监听 `127.0.0.1`，使用随机连接令牌；`on_load=resume`让应用立即继续启动，不会停在白屏等AI连接。
- 可改 `port`（1024..65535）。冲突使用 `fail`，不会悄悄切换端口或误连接另一个应用。换端口需从原APK重新生成。
- Android库名为 `libaijsgadget.so`，配置为官方Android命名 `libaijsgadget.config.so`；强制 `extractNativeLibs=true`，Manifest新增普通INTERNET权限、非导出Provider。
- 原APK含原生库时覆盖其全部支持ABI；纯Java APK使用当前手机ABI。组件含arm64-v8a/armeabi-v7a/x86/x86_64，宿主下载组件仍需arm64/API26+。
- 自动加载发生于应用默认进程Provider初始化，早于Application.onCreate，但晚于attachBaseContext；其他进程不自动加载。Provider加载失败会记录AIjsGadget日志，保留应用启动。
- `frida_eval`脚本≤64KiB、durationMs≤10000；结束时卸载脚本/分离，取消只终止本次客户端进程组。
- **Frida17不内置Java桥**：直接 `Java.perform()` 会报Java未定义；需要用frida-java-bridge和frida-compile打包脚本后传入。本工具不冒充已内置Java桥编译器。

### Gadget 提前加载：preloadMode=provider / dt_needed / clinit

apk_inject_gadget.preloadMode 有三个取值：provider（默认）| dt_needed | **clinit**（**dev-1416 起**；dev-1414 及更早只接受前两个）。不要把它和 providerProcess 混用：providerProcess 决定注入 Provider 在哪个进程（例如 `:script` / `:remote`），preloadMode 决定 Gadget 在启动链的哪一段加载。

**provider（当前路径；对加固壳太晚）**

- 通过注入的 ContentProvider.onCreate 加载；ContentProvider 在 Application.attachBaseContext 之后才创建，因此对在 attachBaseContext / JNI_OnLoad 阶段就初始化的加固壳可能太晚。
- 成功时 preload.earlyLoad=false、preload.preloadImplemented=false 是诚实标注；这不是“已提前加载”，不能据此声称能抢在壳的 native 初始化前。

**dt_needed（支持时；失败/不支持时明确失败）**

- 可选 preloadTarget：指定目标 .so（完整 lib/... 路径或文件名）；省略时自动从 lib/*/*.so 选择第一个可安全 patch 的目标（排除 gadget/config）。
- 支持时的语义：就地给目标 .so 的 .dynamic 增加 DT_NEEDED=libaijsgadget.so，并在 .dynstr 追加库名或复用已有同名串，让 linker 在加载目标壳 .so 之前先加载 Gadget。等价于 .4 手工执行的 patchelf --add-needed libaijsgadget.so --page-size 16384 的自动化版本。
- 成功保证（仅当工具明确支持并成功时）：只使用 .dynamic 已有的可替换 DT_NULL 空槽（通常保留后一个 DT_NULL 终止符）和 .dynstr 尾部 NUL 余量，不移动节/段/PT_LOAD，输出文件大小不变；原子写出，失败不留下半坏产物，原文件不动。
- 成功看 preload.applied=true、earlyLoad=true、preloadImplemented=true、sizeUnchanged=true、verified=true、targetPath；只有这些成立才算提前加载。provider 成功时是 earlyLoad=false/preloadImplemented=false。
- 失败时顶层通常是 PRELOAD_DT_NEEDED_FAILED（旧版/模式不可用可能是 PRELOAD_MODE_UNAVAILABLE），错误信息里带 preload 计划（applied=false）；reason 举例：DYNAMIC_NO_SPARE_NULL、DYNSTR_NO_ROOM、NO_DYNAMIC_SEGMENT、NOT_ELF、UNSUPPORTED_ENDIAN、UNSUPPORTED_ELF_CLASS 等。完整清单以错误/计划里的 preload.targets[].reason/reasons 为准，不要当全集。
- 不支持或失败时不要重试到“成功”：用目标 .so 副本执行外部 patchelf --add-needed libaijsgadget.so --page-size 16384 <目标.so>（page-size 按目标/设备页大小，16 KiB 用 16384），再回填 APK、重新签名并用 apk_verify 复核。外部 patchelf 不是本工具自动完成的步骤。
- 必须回退外部 patchelf 的情形：目标不是动态 ELF、没有 .dynamic、没有空闲且可替换的 DT_NULL 槽或 .dynstr 没有 NUL 余量、需要移动节/段/PT_LOAD、字节序/架构不支持，或返回 PRELOAD_DT_NEEDED_FAILED 及任何细分 reason；不要反复试到“看起来成功”。
- 边界：目标 .so 必须在壳初始化前被 linker 加载；多 .so / 多 ABI / 分包、自定义 linker、页对齐不满足的目标不保证成功。provider 可作为兼容回退，但不解决早于 attachBaseContext 的时序问题。

**clinit（dev-1416 起：Application 类初始化阶段）**

- 语义：生成 Application 子类 `com.aijs.instrumentation.AijsApplication`（`.super` = 原 `application@name`），其 `<clinit>` 里 `System.loadLibrary("aijsgadget")`（整段 `.catch Ljava/lang/Throwable;` 只 `Log.e`，加载失败不崩 App），作为**新 `classesN.dex` 追加**；Manifest **只改 `application@name`**，不重编资源、既有 DEX 一字节不动。可选 `clinitTarget` 覆盖父类（省略则读 Manifest 的 `application@name`，未声明用 `android.app.Application`）。
- **时序边界**：保证早于 `Application.attachBaseContext`；父类先初始化（JLS 12.4.2），**不保证早于加固壳自身的 native 初始化**。返回 `runtimeVerified=false`、`verificationScope=static_dex_manifest_only`、`timingNote`——只做静态字节级校验，不声称运行时已验证。
- 成功看 `preload.applied=true`、`earlyLoad=true`、`preloadImplemented=true`、`method=application_clinit_loadLibrary`、`clinitClass`、`originalApplication`、`targetSource`。
- 失败码（**失败不产出 APK**）：`PRELOAD_CLINIT_UNSUPPORTED_APPLICATION`（父类 final / 接口 / 不在 `classes*.dex` 里（壳加密或动态加载）/ 声明了 `appComponentFactory` / 已存在同名生成类）、`PRELOAD_CLINIT_MANIFEST_PATCH_FAILED`、`PRELOAD_CLINIT_DEX_ASSEMBLY_FAILED`。

### Frida 17 API 迁移对照（P0-3）

Frida 17 起，一批 Module 静态方法被移除，不是函数本身消失。遇到 TypeError: ... is not a function，先按 API 迁移排查，不要把它当成“目标函数不存在”。

| 旧写法（Frida 16 / 旧 typings） | Frida 17 写法 |
|---|---|
| Module.getExportByName("libc.so", "syscall") | Process.getModuleByName("libc.so").getExportByName("syscall") |
| Module.findExportByName("libc.so", "open") | Process.getModuleByName("libc.so").findExportByName("open") |
| Module.findBaseAddress("libc.so") | Process.getModuleByName("libc.so").base |
| Module.getBaseAddress("libc.so") | Process.getModuleByName("libc.so").base |
| Module.getSymbolByName("libc.so", "open") | Process.getModuleByName("libc.so").getSymbolByName("open") |
| Module.getSymbolByName(null, "open") | Module.getGlobalExportByName("open") |
| Module.findSymbolByName("libc.so", "open") | Process.getModuleByName("libc.so").findSymbolByName("open") |
| Module.enumerateExports("libc.so") | Process.getModuleByName("libc.so").enumerateExports() |
| Module.ensureInitialized() | 不再需要；直接使用 Process.getModuleByName(...) |
| Interceptor.attach(Module.findExportByName("libc.so", "open"), {...}) | Interceptor.attach(Process.getModuleByName("libc.so").getExportByName("open"), {...}) |
| Interceptor.replace(Module.findExportByName(...), ...) | 先把目标解析成 NativePointer（同上）；replace/revert 和 onEnter/onLeave 本身没有改名 |
| Interceptor.revert(originalPointer) | 不变；仍传原函数指针 |

首选先拿 Module 对象再取多个符号：

    const libc = Process.getModuleByName("libc.so");
    const syscall = libc.getExportByName("syscall");
    const open = libc.findExportByName("open"); // 找不到返回 null，不抛

只要看到 Module.xxx is not a function / TypeError: not a function，先查本表；若是 Process.getModuleByName 抛 module not found，那才是模块名/进程问题。

### Interceptor 两个隐蔽陷阱（P1-1）

(a) 不要用 onLeave 的 x0 判断短函数 patch 是否生效。 对被 patch 成 mov w0,#1; ret 之类的极短函数 attach 时，入口 trampoline 会改动/保存寄存器；onLeave 读到的 x0 可能是 trampoline 的临时值，而不是目标函数语义返回值。先直接调用该函数并检查返回值，或用 Memory.patchCode/反汇编核对入口字节，再决定 patch 成功与否。

(b) 不要 attach 到调用指令地址。 例如对 blr x27 这条指令地址 attach，Interceptor 会改写该指令来接管控制流，结果目标函数可能根本不被调用，表现为“流程没走到”。要 hook 的是函数入口（Process.getModuleByName(...).getExportByName(...) 的 NativePointer，或已解析的函数地址）；需要观察调用点/调用者时，用 Stalker 或 hook 被调函数入口，不要改调用指令。

结论：只 hook 函数入口，不 hook 调用点，不对被 patch 的短函数用 onLeave 做成功判据。

### Gadget on_load="wait" 与 resume（P1-3；先看代码事实）

- 我们生成的包不使用 wait：ToolkitGadget.prepare 对 listen 和 script 两种 interaction 都固定写 on_load:"resume"（ToolkitGadget.kt:83-88，结果中的 onLoad 也是 resume，:74）。所以用当前 apk_inject_gadget 生成的 APK 不会在启动早期卡住，也不需要额外 resume。
- on_load:"wait" 是 Frida Gadget 的上游默认行为，适用于“要赶在 Application 启动前 attach”的早期抢占；它会让目标在早期阻塞，直到控制端明确 resume。我们的 frida_runner.c 只有 attach/load/eval/unload/detach，没有 frida_session_resume 调用（tools/reverse-native/frida_runner.c:1019 是 frida_device_attach_sync，:1190-1192 是 detach）；从我们的代码角度，frida_eval 既没有 %resume 也没有 session resume 调用，detach 也不是 resume，所以 wait 包不能依赖本工具自动放行，需要外部控制端或改回 resume。
- 如果你手工把配置改成 on_load:"wait"：不要指望断开连接自动恢复；frida_eval 的 unload/detach 不是 resume。要用支持 resume 的 Frida 控制端（CLI 的 %resume 或 API 的 Session.resume()/Device.resume()），或把配置改回 on_load:"resume" 后重新注入并启动。上游文档：https://frida.re/docs/gadget/（on_load 默认 wait，wait 时连接后要 resume）。
- 需要“启动早期就加载脚本”而不是“启动时阻塞”时，优先用 interactionType=script（配置同样 on_load:"resume"，不做网络监听）；它靠脚本在启动时运行，不靠 on_load:wait。

### 当前边界

只支持完整单APK；Split/依赖分包、sharedUserId及重复集成明确拒绝。输出Manifest的minSdk至少21，运行需Android21+。不改包名，不绕过签名校验、完整性校验或加固；重签可能影响签名权限/第三方登录/原数据覆盖。保留原APK，目标能否正常工作必须实际验证。

上游行为依据：[Frida Gadget官方说明](https://frida.re/docs/gadget/)。`reverse_plan(goal=dynamic)`返回同一套机读步骤，不执行、不自动安装。

<!-- section:id=emulate -->
## Unicorn 原始机器码模拟

`native_emulate` 已注册，输入是 **`codeHex` 原始机器码**，不是 APK/ELF/so 路径。需要 `unicorn` 组件；它只模拟 CPU 指令，不提供完整 Android/ELF/JNI 环境。

### 输入

- `architecture`：`arm64`、`arm`、`thumb`、`x86`、`x86_64`。
- `codeHex`：1..65536 字节的十六进制机器码。
- 可选 `dataHex`（≤4096 字节）、`registers`（最多 64 个，值为十六进制字符串）。
- `timeoutMs` 1..1000；`instructionLimit` 1..100000。

```json
{"jsonrpc":"2.0","id":75,"method":"tools/call","params":{"name":"native_emulate","arguments":{"architecture":"arm64","codeHex":"${CODE_HEX}","dataHex":"","timeoutMs":100,"instructionLimit":10000,"background":true,"timeoutSeconds":60}}}
```

### 边界

- 不能把 so 路径、APK locator 或 ELF 入口地址直接传给 `native_emulate`；先准备确切字节。
- 模拟成功只说明“在这组字节、寄存器、初始数据和上限下走到某个状态”，不是目标 App 在真机上的行为。
- 必须记录字节来源/哈希、架构、初始寄存器、数据、超时和指令上限；不能写“已运行目标 so”。
- `unicorn` 组件需显式安装；工具注册/任务登记都不代表组件已安装。

<!-- section:id=fingerprint -->
## 指纹与完整性：哈希、验签、规则扫描分开写

### 现有可用证据

```json
{"jsonrpc":"2.0","id":80,"method":"tools/call","params":{"name":"artifact_fingerprint","arguments":{"path":"${ARTIFACT_PATH}","background":true,"timeoutSeconds":120}}}
```

- `artifact_fingerprint` 返回 SHA-256、格式、APK 库/壳/框架**启发式线索**和置信度；后端是 `aijs-heuristics-v1`，`fullApkid=false`。它**不是 APKiD**，没有完整 APKiD 规则/Python 运行环境，未命中不能证明无壳/无框架。
- `file_stat` 返回类型、大小、修改时间和普通文件流式 SHA-256 `targetVersion`；缺失路径返回 `type=missing`。
- `apk_workspace_open` 返回快照 `sha256`，适合固定“这份字节”。
- `apk_verify` 做密码学验签、ZIP/ELF 对齐和 Manifest 结构检查；失败项看 `checks`/`issues`。
- `apk_read_signature` 只读证书/方案元数据；`verified` 始终 false。
- `zip_test` 做完整 CRC/HMAC 校验；`password` 是**可选**项（dev-1368 起），未加密 ZIP 省略或传空字符串皆可，**加密条目**才必须传非空密码。

```json
{"jsonrpc":"2.0","id":81,"method":"tools/call","params":{"name":"yara_scan","arguments":{"path":"${ARTIFACT_PATH}","rulesPath":"${RULES_PATH}","scanTimeoutSeconds":20,"includeOffsets":true,"background":true,"timeoutSeconds":60}}}
```

`yara_scan` 需要 `yara` 组件；规则 ≤1 MiB、输入 ≤64 MiB、扫描 ≤30 秒、禁用 include。返回命中/偏移及截断标记。**“无匹配”不等于安全，“有匹配”也要人工复核规则、上下文和误报。**

### 结论写法

- 指纹：`文件大小 + SHA-256 + 采集时间 + 来源`，四者一起写。
- 签名：分开写“证书是谁/有哪些方案”和“apk_verify 是否通过”。
- 完整性：`zip_test` 通过只说明当前 ZIP 条目 CRC/HMAC 一致；不证明 APK 未被替换、未恶意或可安装。
- 启发式：写“`artifact_fingerprint` 命中某线索，置信度 X”，不要写“APKiD 判定”。

<!-- section:id=compare -->
## 两个版本怎么对照

### 固定两份快照

```json
{"jsonrpc":"2.0","id":90,"method":"tools/call","params":{"name":"apk_workspace_open","arguments":{"path":"${APK_PATH_A}","temporary":true}}}
```

```json
{"jsonrpc":"2.0","id":91,"method":"tools/call","params":{"name":"apk_workspace_open","arguments":{"path":"${APK_PATH_B}","temporary":true}}}
```

分别记下 `workspaceIdA`、`sha256A`、`workspaceIdB`、`sha256B`。后续所有读取/验签都要带对应 `workspaceId`，不能混用。

### 对照维度

1. 文件级：大小、SHA-256、签名证书/方案、APK 对齐/结构检查。
2. Manifest/资源级：`versionCode`、`versionName`、权限、ABI、导出组件；以实际解码证据为准。
3. DEX 级：类/方法 locator、字符串、引用；用 `apk_dex_search_members`/`apk_dex_call_graph` 分别读，不要把一个快照的 locator 套到另一个。
4. native 级：so 条目、架构、符号、直接 xref；需要字节级比较时先导出两个文件，再用 `native_compare`：

```json
{"jsonrpc":"2.0","id":92,"method":"tools/call","params":{"name":"native_compare","arguments":{"path":"${LOCAL_BINARY_A}","otherPath":"${LOCAL_BINARY_B}","offset":0,"limit":100,"background":true,"timeoutSeconds":180}}}
```

`native_compare` 返回有界字节变化 + radare2 元数据；相同大小或相同哈希片段都不是语义等价证明。

### 不能下的结论

- 证书相同 ≠ 内容相同；哈希相同 ≠ 行为相同；版本号更高 ≠ 更安全。
- 静态差异不是行为等价证明；`native_compare` 不是函数级语义 diff。
- `apk_session_diff` 只比较同一会话相对其原快照的变更，不是两个 APK 的 diff。

<!-- section:id=newtools -->
## 新工具与族前缀别名（dev-1422~1429）

这 5 个工具 dev-1422 起陆续加入；参数一律以 `tool_schema{tool:"…"}` 为准。

### `vm_survey`（dev-1422）

静态找 dispatch 壳候选：`candidates[]{score,contributions,features}` + `scoreBuckets`（与 `candidateCount` **同集合**、不被 `limit` 截断）、opcode 聚类、大常量 `payloads[]`、dispatch 数组摊平、按 `opcodeRules` **线性**反汇编（`coveragePercent` + `stopsAt`/`stopReason`）、只到块起点的 CFG。

- 参数：`workspaceId`(+`editSessionId`) 或工具目录内 `path`；**只给 `blobHex`(+`opcodeRules`) 即 blob-only**（不扫 DEX）。
- 边界：静态启发式、**不做反编译、不承诺任意 VMP 一键还原**；**0 候选是合法结果**（返回写清扫了多少 dex/类/方法）；权重未用真实 VMP 样本校准；CFG 偏移是 code units；`fill-array-data`/native 解密后的常量看不到。
- 权重校准（dev-1430 起）：可选 `weights{switchCount,switchCases,dispatchLoopBackEdges,arrayIndexOps,invokeCount,instructionCount,classPayloadBytes}` 覆盖 **7 个**特征权重；返回 `weightsSource=default|custom` + **实际生效权重表** + `scoreFormula`（`score=min(100, Σ normalized×weight)`）。**封顶 100 会并列 → 校准看候选的 `rawScore`（不封顶）**；负数/非整数/**全 0**/未知键 → `INVALID_ARGUMENT`（消息列出 7 个可用键），**显式 0 = 关闭该项**（该贡献项仍在 `contributions` 里、值为 0）。
- 误用：把 `candidateCount` 大当「壳多」；把分数当结论；用 `payloadRef` 却不给 dex 集。

### `entry_`（dev-1425）

一次拿入口画像：`application{class,classSource,appComponentFactory}`、`launcher[]`（`activity-alias` 给声明值+展开值）、`exportedComponents[]`（含 `android:process`）、`nativeEntryHints[]`、`preloadRecommendation{mode,reason,reasonSource,suggestedArguments}`、`staticConclusions[]`/`needsDynamicConfirmation[]`。

- 参数：`path` 或 `workspaceId`(+`editSessionId`) 二选一。
- 边界：**纯静态、`runtimeVerified=false`**；壳可动态注册/替换组件；**「没找到 JNI_OnLoad」≠「没有 native 初始化」**；建议与 `apk_inject_gadget` **同源**（`reasonSource` 回显函数名+实参），但注入时以 `PRELOAD_*` 失败码为准。
- 误用：把 `preloadRecommendation.mode` 当保证；忽略 `needsDynamicConfirmation[]`。

### `chain_resume`（dev-1426；构建侧 AXML 分层 dev-1427）

`checkpoint → 回编 → 对齐/签名 → 验签` 一条链。必填 `workspaceId`+`editSessionId`；`steps` 默认 `all`；`checkpoint` 必须配 `expectedRevision`（不存在/过期 → `CHAIN_RESTORE_FAILED`，链未开始且**无产物**）。

- 返回：`ok`、`stoppedAt`、`failure{where,phase,code,message}`、`steps[]`（含 `skipped`）、`finalArtifact{path,bytes,sha256}`（磁盘实测）、`verified`（**只来自 verifier 逐方案结论**）、`runtimeVerified`、`restoredFrom`。
- 边界：**任一步失败返回 payload `ok=false`，不抛异常 → 客户端必须看 `ok`**；`verified` ≠「签过名」；`finalArtifact` ≠ 可安装证明；不重编资源、不安装、不覆盖源包；dev-1427 起 Manifest 缺资源上下文转 `warnings[]` 的 `MANIFEST_PRIMARY_DECODER_FALLBACK` 并继续构建，`res/**/*.xml` 仍失败。

### `crypto_`（dev-1428）

JCA：`op=hash|hmac|aes_gcm|rsa_sign|rsa_verify`（仅 `op` 必填）；密钥**只允许显式** `keyHex` 或工具目录内 `keyRef`（不读 App 私有密钥库，只回显 `keyBytes`/`keySha256Prefix`）。失败码 `ALG_UNSUPPORTED`/`KEY_INVALID`/`MAC_INVALID`/`DECRYPT_FAILED`/`INPUT_TOO_LARGE`；**失败不留半成品**。

- 边界：不管理密钥、不做 KDF/PSS/CBC；GCM 通过只说明 tag 校验通过，**不构成信任判断**；`rsa_verify` 公钥路径 `valid=false` 是「签名不匹配」（工具执行成功）。
- 误用：`keyRef:"../x"` → `KEY_INVALID`；`inputHex`+`path` 同给 → `INVALID_ARGUMENT`。

### `dev_screen_diff`（dev-1424）

与 `capture_screen` **同一条截图管线**、**无必填**；不传 `baseline` 就与进程内上一帧比。返回 `changedPixels`/`ratio`/`bbox`/`maxChannelDelta`/`frameSha256`/`baselineSource`/`sizeSource`。

- 边界：像素级、不做语义/模板匹配；**`ratio` ≠ 界面变化程度**；尺寸不一致**直接 `SIZE_MISMATCH`**（不裁剪不缩放，等比时给 `suggestedArguments`/`copyableCommand`）。
- 误用：第一次 `NO_BASELINE` 以为坏了（已记帧，**同参数再调一次**）。

### 族前缀别名：`dex_*` / `so_*`（dev-1429）

`dex_read_class_outline`/`dex_xref`/`dex_search_members`/`dex_call_graph`/`dex_hierarchy`/`dex_interface_table`/`dex_list_classes`/`dex_list_methods` → `apk_dex_*`；`so_inspect`/`so_list_items`/`so_map_address`/`so_xref`/`so_disassemble`/`so_analyze_control_flow`/`so_patch_instructions`/`so_patch_string` → `apk_native_*`。共 **16 个**；**新旧等价、旧名长期保留**；**不出现在 tools/list**（advertised 仍 224）；`tool_schema{tool:"dex_xref"}` 会回 `resolved=apk_dex_xref` + `familyAliases[]`；`tier=core`/`groups` 不含别名。

<!-- section:id=errors -->
## 错误、nextActions 与预算

### 先读错误信封

```json
{"ok":false,"data":null,"error":{"code":"INVALID_ARGUMENT","argument":"address","recoverable":true,"retrySameArguments":false,"message":"..."},"nextActions":[]}
```

- `error.code` 决定处理方式；`error.argument` 指出参数。
- `retrySameArguments=false` 表示**不要原样重试**；先改参数/先补前置。
- `nextActions` 可能给出具体下一步；为空时按 code 和本文处理，不要自己编工具名。
- `recoverable=true` 不等于“重试就能过”；预算/缺组件/旧版本都需要先改变状态。

### 参数错误 details 与扁平标量返回

- 参数类失败（INVALID_ARGUMENT、缺参数）除 message 里的「可用参数/必填」外，新版本会在 structuredContent.error.details 提供 {tool,availableParameters[],required[],schemaTool:"tool_schema"}（可能附 schemaArguments）；机读用 details，不要解析 message。顶层 details 可能镜像；旧构建没有 details 时退回 message + tool_schema。
- get_status / toolkit_status / get_build_tools 这类扁平标量工具的新版本会同时给：content[0].text 旧扁平 JSON（老代码照旧读），以及 structuredContent={ok:true,data:{...}}（新代码读 data）。当前构建若尚未加 data 镜像，以 content[0].text 为准；get_status/get_build_tools 已加镜像，toolkit_status 走 :tools 桥接，若该构建尚未镜像同样以 text 为准。

### 常见错误与正确动作

| 现象 / code | 含义 | 正确动作 |
|---|---|---|
| `COMPONENT_NOT_INSTALLED` | JADX/apktool/radare2/r2ghidra/yara/unicorn/frida 未装 | `apk_component_status` → `apk_component_install` → 轮询 job → 重试一次 |
| `EXECUTION_POLICY` | 平台禁止执行下载的组件 | 报告策略限制；不要重复安装碰运气 |
| `COMPONENT_FAILED` | 组件子进程失败 | 保留 stderr/exitCode；检查版本、ABI、输入；不要伪造结果 |
| `REGEX_BUDGET_EXCEEDED` | 正则超时/字符预算 | 改字面量、缩小 `prefix`/`target`/`limit` |
| `RESPONSE_TOO_LARGE` | 响应超过 24 MiB | 分页/缩小 `limit`/`maxChars`/查询范围 |
| `INVALID_ARGUMENT` | 缺必填或类型不对 | 读 `tools/list` schema，补齐字段；不要丢参数“试试” |
| 分页游标不存在/过期 | 超过 5 分钟、会话/revision 已改，或把别的工具的游标传错工具 | 先看返回的 `cursorTool`：`apk_continue` 只接 APK 分页游标；`apk_dex_list_classes`/`list_methods` 的游标要回传原工具 `cursor`；重新查询第一页，不要复用旧 `nextCursor` |
| 内容已改变 | `targetVersion` 旧了 | 重新读取拿新版本，再编辑 |
| 任务不存在 | jobId 不属于当前工具目录/已清理 | `apk_job_list` 对账；不要猜 |
| `INTERRUPTED` | 工具进程重启，任务未重放 | 重新评估输入后显式启动新任务；没有 `resume` |

### 预算（以实际返回为准）

- APK 快照：单包 512 MiB，总量 1 GiB，最多 16 个；单条目分析 64 MiB，文本 10 MiB。
- 编辑会话：每工作区 8 个，每会话 2000 条变更/512 MiB；检查点最多 8 个。
- 文本读取：`apk_read_text` 的 `maxChars` 上限 131072；`file_read_text` 单页 2..131072 码点。
- 正则：每个 matcher 默认 750 ms、最多 8,000,000 次字符访问，并受总预算/取消检查限制。
- 任务结果：24 MiB；`apk_job_result.maxChars` 1..1048576（dev-1380）；逆向任务 `timeoutSeconds` 1..1800（上界由 `McpLimits.MAX_TIMEOUT_SECONDS` 统一控制）。
- 原生输入：`native_*` 文件 ≤64 MiB；`yara_scan` 规则 ≤1 MiB、输入 ≤64 MiB、扫描 ≤30 秒。
- `native_emulate`：代码 1..65536 字节、数据 ≤4096 字节、指令 ≤100000、时间 ≤1000 ms。
- 分页：APK 列表/DEX 大纲 1000 项、native 列表 500 项、其余列表 200 项；以 `pagination.limitMax` 为准。

超预算不是“工具坏了”。缩小范围、分页、换 target，或先停掉不必要的大任务；不要用重复调用碰运气。

<!-- section:id=workflows -->
## 机读工作流：让计划先回答“能不能做”

### 八个 goal

| goal | 典型输入 | 当前可执行性 |
|---|---|---|
| `inspect` | APK 路径、搜索词 | 现有工具可执行；`artifact_fingerprint` 可选增强 |
| `decode` | APK、符号/类 locator | 需先确认 jadx；JADX 阅读可执行，apktool 工程 decode 可选 |
| `rebuild` | 工作区/会话/locator/targetVersion | 会话编辑+构建可执行；apktool 工程 build 可选 |
| `native` | APK、so 条目、地址 | Capstone 静态路径可执行；radare2 工具已注册，需组件和导出路径 |
| `dynamic` | endpoint、PID、Frida 脚本 | 免Root集成；需frida/frida-gadget组件及安装打开输出APK |
| `fingerprint` | 文件/APK 路径、规则 | 现有工具 + 启发式指纹可执行；YARA 可选 |
| `emulate` | `codeHex`、架构 | 已注册；需 unicorn 组件，仅 CPU 模拟 |
| `compare` | 两个 APK/两个二进制 | 现有快照/验签对照可执行；`native_compare` 可选 |

`reverse_plan` 不执行任何步骤。它把 `reverse-workflows.json` 展开为：

- `steps[]`：`tool`、`args` 模板、`required`、`available`、`supported`、`status`、`unresolvedVariables`；
- `requiredComponents[]`：`checkTool`/`installTool`/`installArgumentSupported`/`status`，`checkedByPlan=false`；
- `variables[]`：`placeholder`、`replaceBeforeUse=true`、示例、必填；
- `limitations[]`、`readiness`、`unsupportedTools`、`unsupportedSteps`、`unknownVariables`；
- `toolReferences[]`：每个工具引用及其 `available`/`usedBy`。

### 怎么读计划结果

- `readiness=ready`：必填工具都在；可选组件仍可能未装，按步骤检查。
- `readiness=ready_with_component_check`：必填工具/组件检查路径都在，但计划没有实际检查；先 `apk_component_status`。
- `readiness=blocked`：有必填工具或组件未注册；不要执行，也不要找相似工具替代。
- `supported=false` + `required=false`：候选步骤缺失，只是可选增强。
- `supported=false` + `required=true`：计划阻塞，必须等集成或换已支持路线。

### 变量和占位符

`variables[]` 里的 `${name}` 必须替换成真实值；`example` 只是形状示例，不是可直接使用的真实 ID。`unresolvedVariables` 非空时，`args` 还是模板；`unknownVariables` 非空说明资产/调用方变量声明不一致，先修模板。

### 44 个逆向工具与组件

`candidateTools[]` 保留 **19** 个名字用于校验（`guideVersion=dev1330-1`；这 19 个是「计划会引用到」的子集，不是全部 44 个逆向工具）；这 19 个全部已注册，`available` 应为 true。名字存在仍不等于组件已装：

- 静态/构建：`apk_decode_project`（apktool）、`apk_build_project`（apktool + apk-resources）、`apk_install_framework`（apktool）。
- 原生：`native_analyze`、`native_functions`、`native_function_detail`、`native_xrefs`、`native_compare`（radare2）；`native_decompile`（radare2 + r2ghidra，r2ghidra 按需安装）。
- 指纹/规则/模拟：`artifact_fingerprint`（内置启发式，不是 APKiD）、`yara_scan`（yara）、`native_emulate`（unicorn，`codeHex` 不是 path）。
- 动态：`frida_status`、`frida_processes`、`frida_eval`（免Root Gadget集成，frida/frida-gadget组件）。
- 指南：`reverse_guide`、`reverse_capabilities`、`reverse_plan` 只规划/说明，不执行。

组件检查统一用 `apk_component_status` 的 **`{}`**，不要传 `component` 字段。安装用 `apk_component_install`，enum 为 8 个 ID。若用户在本任务里已经授权安装/下载，不要重复询问同一授权；仍要记录组件、来源和结果。

<!-- section:id=limitations -->
## 全局限制与不可声称的结论

1. **注册不是可用**：18 个工具已注册，但组件、平台执行策略、外部 Frida 服务、输入预算都可能让调用失败。
2. **静态为主**：`apk_native_*`、radare2 和 artifact 工具不执行目标代码；Frida/Unicorn 是独立的有界动态/模拟路径。
3. **Apktool 不是万能**：真机 3.0.3 样例 decode/edit/rebuild 通过，但任意 APK 不保证；失败要保留原始诊断，不能声称“完整反编译成功”。
4. **r2ghidra 需另行安装**：`native_decompile` 前必须查组件；缺插件时不能伪造伪代码。
5. **不是 APKiD**：`artifact_fingerprint` 是内置启发式，`fullApkid=false`；未命中不能证明无壳/无框架。
6. **不是 Android 运行时**：`native_emulate` 只模拟 `codeHex` 原始指令，不提供完整 ELF/JNI/binder/系统调用环境。
7. **Frida免Root条件**：须安装打开集成后的APK，传endpoint/token；重签不能直接覆盖不同证书应用，不支持Split/sharedUserId。
8. **签名两条线**：`apk_read_signature` 是元数据，`apk_verify` 是密码学检查；二者都不保证安装或业务行为。
9. **没有自动重放**：后台任务重启后 `INTERRUPTED`，没有 `resume`；没有 `waitMillis`；结果只在 `hasMore=true` 时继续分页。
10. **任务登记不代表安装/授权**：`jobId` 只是排队；组件安装看 `apk_component_status` 和安装任务终态，权限看用户明确授权。

如果下一步需要的工具不在 `tools/list`，正确动作是：记录缺口、用 `reverse_plan` 标 unsupported、继续用已支持工具收集证据；不是编一个相似名字，也不是把静态阅读结果写成动态结论。


### 本轮 Android 兼容验收

Apktool 3.0.3 通过独立 ART 子进程运行，九宫格 PNG 使用 Android Bitmap 解码，避免 ImageIO/AWT 缺类；标准黑色伸缩与内容边框的解包、资源修改、Smali/资源回编译与签名已验证。当前不恢复 npLb/npOl 红色光学边框，PNG 辅助色彩信息和原始压缩也可能改变，不能承诺任意 APK 原样回编译。Frida 17.25.2 在自建 Gadget 上验证连接、枚举、原生函数结果42、脚本错误、消息截断和取消后重连；Unicorn 2.1.4 五种架构及指令预算已验证。r2ghidra 6.2.4 已带完整上游补丁并验证伪代码输出。以上是真机样本证据，不能替代目标设备或目标 APK 的实际验收。
