# AI.js Pro 脚本 MCP

## 入口与连接

入口：AI.js Pro 左上角菜单 → 开发 → MCP 服务。

默认地址为 `http://127.0.0.1:8788/mcp`，传输类型选择 **Streamable HTTP**。

同一手机上的 MT 客户端直接填写该地址。默认开启“本机客户端兼容”，loopback 请求可不填写自定义请求头；MT 的自定义请求头列表不能保留名称为空的行，否则 OkHttp 会在发包前抛出 `name is empty`。

关闭本机兼容，或通过局域网连接时，发送：

```text
Authorization: Bearer <在 MCP 页面复制的访问令牌>
Accept: application/json, text/event-stream
Content-Type: application/json
MCP-Protocol-Version: 2025-06-18
```

电脑通过 USB 连接 K40 时，先执行：

```powershell
adb forward tcp:18790 tcp:8788
```

客户端填写 `http://127.0.0.1:18790/mcp`。服务接受 USB 转发后的 loopback Host 端口，不需要伪造 `Host`。也可在页面设置中开启局域网连接，再使用页面列出的 IPv4 地址；局域网 HTTP 未加密，不能暴露到公网。

## 工具

服务始终公布完整工具列表：32 个功能入口和 7 个高级版兼容别名，共 39 个名称。需要授权的工具在未授权时返回明确错误，方便客户端稳定发现能力。

高级版兼容名称与本项目名称调用同一实现：

| 高级版名称 | 本项目名称 | 用途 |
|---|---|---|
| `getUiLayout` | `get_ui_layout` | 当前窗口节点树、XML、窗口列表和诊断 |
| `captureScreen` | `capture_screen` | 无障碍截图，返回 MCP image 和尺寸元数据 |
| `checkSyntax` | `check_syntax` | Rhino / QuickJS 只编译检查，不执行源码 |
| `runScript` | `run_script` | 操作目录内文件或内存源码执行，支持配置参数 |
| `learnAutoJsSkill` | `learn_autojs_skill` | 项目专用 MCP、引擎和 API 使用说明 |
| `stopAllScripts` | `stop_all_scripts` | 停止整个应用的脚本 |
| `getMcpLog` | `get_mcp_log` | 任务日志、全局脚本日志或 MCP 请求记录 |

客户端应从 `tools/list` 读取完整参数定义。工具 JSON 结果同时提供 `structuredContent` 和兼容旧客户端的文本内容。

只读与检索：

- `get_status`：服务授权和脚本工作区状态。
- `list_scripts`：按相对路径分页列出脚本目录；`recursive=true` 时递归子目录（与 `search_scripts` 同一数据源，不再出现“搜得到、列不出”）。
- `read_script`：分页读取脚本目录内允许的文本文件，并返回 SHA-256。
- `search_scripts` / `continue_result`：递归搜索文件名和内容，并使用短期游标继续分页。
- `list_samples` / `read_sample`：分页列出和读取 `sample` 内置示例。
- `read_apk_logs`：增量读取 AI.js Pro 全局控制台日志。
- `list_executions` / `get_execution`：读取本 MCP 发起的运行任务状态、结果与异常。
- `check_syntax`：传 `code`，可选 `engine=rhino/quickjs`，返回 `valid`、错误位置和 `executed=false`。支持本项目 XML/模板预处理；错误位置可能来自预处理后的源码。语法通过不保证业务 API、权限或文件存在。
- `learn_autojs_skill`：读取应用内置的双引擎使用说明。
- `get_mcp_log`：可按 `executionId` 读取单个任务日志，或选择 `channel=script/service/all`。默认最多 20000 字符，最大 200000；服务记录最多最近 30 条，结果标明是否截断。

手机端开启“允许执行与设备操作”后：

- `run_script`：`path` / `file` / `source` 三选一；文件必须是操作目录内已有 `.js`，源码在内存中执行。可选 `engine=rhino/quickjs` 覆盖源码指令，可异步执行。文件入口会用**临时副本**指定引擎，原文件不改动。副本在脚本实际结束时删除；等待超时不会删副本或自动停止脚本。
- `stop_script`：只停止该 MCP 服务启动并记录的 executionId。
- `stop_all_scripts`：停止整个应用，包括其他入口启动的脚本；仅停止一个 MCP 任务时使用 `stop_script`。
- `get_ui_layout`：需要已启用无障碍；默认选择当前应用窗口，可用 `windowId` 指定目标。返回扁平节点属性、XML、窗口列表及诊断；空节点会明确返回不可读状态。默认 3000 节点、64 层、3000ms，上限 10000 节点、128 层、15000ms；`truncated=true` 表示未读完整。
- `capture_screen`：使用 Android 11+ 无障碍截图通道，不占用脚本的录屏会话。支持 PNG/JPG、压缩质量、等比缩小及可选 data URI；返回 MCP 图片和原始尺寸/缩放比例。无障碍未开启、受保护页面或旧系统会返回错误。旧系统可通过脚本 `requestScreenCapture()` / `captureScreen()` 使用录屏权限。

内存源码示例：

```json
{"name":"runScript","arguments":{"source":"console.log(engines.myEngine().execArgv.message);","engine":"quickjs","wait":true,"config":{"arguments":{"message":"hello"}}}}
```

工作目录默认操作目录；文件入口默认文件所在目录。`workingDirectory` 和模块搜索路径均限制在操作目录内。`config` 支持工作目录 `path`（字符串）或模块搜索路径 `path`（数组）、`workingDirectory`、`engine`、`delay`、`interval`、`loopTimes` 和 JSON 对象 `arguments`。顶层 `engine` 优先；`loopTimes=0` 为持续循环。

运行结果语义（`list_executions` / `get_execution` / `wait_execution` / `run_script`）：

| 字段 | 说明 |
|---|---|
| `status` | `QUEUED` / `RUNNING` / `SUCCEEDED` / `FAILED` / `STOPPED` |
| `engine` | 实际执行引擎 `rhino` / `quickjs` |
| `result` | 引擎提供时返回；不要依赖 Rhino 返回脚本末尾表达式 |
| `logs` | 当前任务自身控制台的最近 300 条，每条最多 16 KiB；不会混入并行任务输出 |
| `stopReason` | 仅在 `STOPPED` 时出现：`user_stopped`、`all_scripts_stopped`、`service_stopped` 或 `interrupted` |
| `error` | 仅在**真的报错**（`FAILED`）时出现；脚本被停止时**不再**附带 `interrupted` 异常堆栈 |

> 主动停止是用**中断**实现的（等同于 Java 的 `InterruptedException`），中断点必然抛异常，
> 所以 `STOPPED` 不是错误：判定成功与否请用 `status`，不要再依据 `error` 是否存在。
> 引擎侧同时把 QuickJS 的中断异常统一成 `ScriptInterruptedException`，与 Rhino 行为一致。
- `list_engine_api` / `probe_engine_api`：枚举某引擎的全局 API、探测类型成员。探针在内存中运行，显式指定 Rhino / QuickJS，每次使用独立日志标记。成员列表包含对象或函数的自身可枚举成员，最多 200 项，不包含原型和不可枚举成员。
- `engine_api_diff`：一次调用直接对比两引擎全局 API（返回 `onlyQuickJs` / `onlyRhino` / `commonCount` / `quickjsCount` / `rhinoCount`），迁移新引擎时用来快速定位缺口；lite 版无 Rhino 时 `rhinoAvailable=false`。

手机端开启“允许编辑工作区”后：

- `workspace_open` / `workspace_list` / `workspace_read`：从真实脚本建立并查看 App 私有快照。`workspace_open` 的 `path` 一律用**相对脚本目录的完整路径**（如 `悬浮球项目/README.md`），子目录文件可直接读写。
- `workspace_write` / `workspace_delete`：只改私有副本，不直接覆盖真实脚本。
- `workspace_diff`：查看基线与私有副本差异。
- `workspace_request_apply`：手机端开启“允许编辑并应用”后，直接把工作区写入真实脚本；工具名为兼容旧客户端保留。写入前校验原始哈希并建立备份，应用后可在手机历史页回退。
- `workspace_mkdir`：在真实脚本目录下创建目录（含多级），需写入授权。
- `workspace_cancel`：取消待确认的应用请求，把工作区恢复为可编辑状态（不再让 `pendingWorkspaceApprovals` 永远残留）。
- `workspace_cleanup`：清理无用的工作区私有副本（默认只删无修改的 OPEN 与已回退的；`applied=true` 时连已应用的也删，默认保留 `keepAppliedHours=24` 小时以便回退）。

`workspace_open` 语义：

| 情况 | 行为 |
|---|---|
| 目标存在 | 建立快照并返回工作区 |
| 目标不存在 + `create=true` | 创建空的工作区（**目录与文件都在 apply 时一起创建**，不再一 open 就建空目录），可直接 `workspace_read`（返回空串）/`workspace_write` |
| 目标已存在 + `create=true` | **幂等**：直接打开（不再报“新建目标已存在”）|
| 同一目标已有“未修改的 OPEN 工作区” | **复用**该工作区；若磁盘文件已变化，会用**同一个 workspaceId 重新抓快照**（不会拿旧快照豁人）|
| 传其他路径给单文件工作区 | 报错会带**完整路径**与正确做法，而不是旧的“单文件工作区不能新建其他文件”|

> `workspace_write` / `workspace_delete` 只改私有副本，返回里会带 `needsApply: true` 提醒；
> 真实脚本要调 `workspace_request_apply` 才更新（目录与文件在 apply 时一起创建）。
> `workspace_read` 报找不到时会分别列出**请求路径**与**快照内实际文件**，不再出现两边一模一样的误导信息。

> 注意：`workspace_mkdir` 会在真实脚本目录里建目录（不是私有副本），所以需要写入授权。

新建文件示例：

```json
{"name":"workspace_open","arguments":{"path":"新脚本.js","create":true}}
```

取得 `workspaceId` 后调用 `workspace_write`，其中 `path` 仍为 `新脚本.js`；查看 Diff 后调用 `workspace_request_apply` 即可应用。MCP 不允许绕过工作区直接创建真实文件。

单次读取或写入上限 256 KiB，列表单页最多 200 项。当前实现是无会话的单 JSON Streamable HTTP，兼容 `2024-11-05`、`2025-03-26` 和 `2025-06-18` 的单消息客户端，不支持 HTTP batch 或 SSE-only 响应。

## 真机验证记录（2026-09-11 · Redmi 2602BRT18C / Android 16 / 局域网 MCP）

| 场景 | 修复前 | 修复后 |
|---|---|---|
| 子目录文件 | `workspace_open('悬浮球项目/README.md')` 成功但 `workspace_read` 报“工作区文件不存在” | ✅ open → read 正常（size=1016）|
| `create=true` 已存在目标 | 报“新建目标已存在” | ✅ 幂等，直接 OPEN |
| 新建嵌套文件 | 报“父目录不存在” | ✅ 自动建目录，写入→应用→真实文件读回一致 |
| 重复 open 同一目标 | 每次新建工作区（累积到 232）| ✅ 未修改的 OPEN 工作区被复用（同一个 workspaceId）|
| 待确认残留 | `pendingWorkspaceApprovals: 1` 无法清除 | ✅ `workspace_cancel ws-c6bc793d` → OPEN / pending=false |
| 工作区累积 | 232 个 | ✅ `workspace_cleanup` 一次清掉 135 个（默认保留已应用 1–24h 供回退）|
| 停止脚本 | `STOPPED` 仍带 `WrappedException → ScriptInterruptedException` | ✅ `STOPPED` + `stopReason: user_stopped`，`error` 缺省（Rhino 与 QuickJS 两个引擎都验过）|

回归：在手机上通过 MCP `run_script` 跑全模块回归，`=== 回归测试完成: 228 通过, 0 失败 ===` + `=== QUICKJS_REGRESSION_OK ===`。

## 真机验证记录（2026-09-11 第二轮 · MI 8 Explorer Edition / Android 15 / USB 端口转发 MCP）

通过 `adb forward tcp:18788 tcp:8788` 直接访问设备内 MCP 服务，无需 Wi-Fi。

| 场景 | 修复前 | 修复后 |
|---|---|---|
| `workspace_open(create=true)` 新路径 | 一 open 就在真实脚本目录建出空目录 | ✅ apply 前磁盘无任何变化（`list_scripts` 报“目录不存在”），apply 后文件才出现（size=42）|
| 新建但未写入的文件 `workspace_read` | 报“工作区文件不存在” | ✅ 返回空串（size=0 / 空 sha256），可直接写 |
| `workspace_write` / `workspace_delete` 返回 | 只有 `changedFiles`，容易被当成已落盘 | ✅ 附带 `needsApply: true` + 提示要调 `workspace_request_apply` |
| 快照过期复用 | 复用旧 workspaceId 却拿旧内容 | ✅ 同一 workspaceId 复用，同时按磁盘重新抓快照（外部改成 v2 后 read 返回 v2）|
| `workspace_read` 找不到文件 | 文案重复请求路径 | ✅ 目录工作区会列出**快照内实际文件**；快照为空时给出原因与做法 |
| `read_script` 目标不存在 | 报“仅支持文本文件”（误导成扩展名问题）| ✅ 分三类：`文件不存在：<路径>` / `这是目录，不是文件：<路径>` / `仅支持文本文件` |

## 接口对齐记录（dev-781）

2026-10-03 对照高级版包内 MCP 入口和说明补齐节点树、截图、语法检查、源码执行、专用说明、停止全体及服务日志，并兼容全部 7 个调用名。执行配置、任务日志隔离、探针隔离与临时副本收尾一并修正。验证和发布记录见 [MCP 接口对齐](reports/2026-10-03_MCP接口对齐_dev781.md)；此前缺项见 [历史检查](reports/2026-10-03_MCP接口补齐检查.md)。

## 安全边界

- 默认手动启动服务；可在设置中启用“跟随应用前台自动启动/后台停止”。
- 默认仅绑定 loopback。本机兼容开启时 loopback 可免令牌；提供错误令牌仍会拒绝。局域网默认验证随机 Bearer 令牌，另有默认关闭的局域网免令牌兼容选项。
- 浏览器 Origin 一律拒绝，Host 只接受绑定接口的 IP 字面量或 loopback，避免 DNS rebinding。
- 编辑与执行授权会保存到设置中，重启服务会恢复；停止服务会撤销当前实例的运行授权状态。若希望重启后仍不授权，应在手机设置中关闭对应开关。令牌重置只能在停止状态进行。
- MCP 默认只读。手机端开启编辑授权后，客户端可直接应用工作区；回退仍在手机历史页执行。关闭授权会立即阻止后续写入和应用。
- 文件工具的读写限制在操作目录内。停止单任务仅接受本 MCP 的 executionId；全体停止单独需要执行与设备操作授权。
- 执行脚本会继承 AI.js Pro 已有的手机权限，开启执行前必须确认连接的是可信客户端。
