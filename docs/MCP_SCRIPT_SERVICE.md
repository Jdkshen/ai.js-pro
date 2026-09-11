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

服务始终公布完整工具列表；需要授权的工具在未授权时返回明确错误，方便客户端稳定发现能力。

只读与检索：

- `get_status`：服务授权和脚本工作区状态。
- `list_scripts`：按相对路径分页列出脚本目录；`recursive=true` 时递归子目录（与 `search_scripts` 同一数据源，不再出现“搜得到、列不出”）。
- `read_script`：分页读取脚本目录内允许的文本文件，并返回 SHA-256。
- `search_scripts` / `continue_result`：递归搜索文件名和内容，并使用短期游标继续分页。
- `list_samples` / `read_sample`：分页列出和读取 `sample` 内置示例。
- `read_apk_logs`：增量读取 AI.js Pro 全局控制台日志。
- `list_executions` / `get_execution`：读取本 MCP 发起的运行任务状态、结果与异常。

手机端开启“允许运行脚本”后：

- `run_script`：只运行操作目录内已有 `.js`，继续使用应用现有脚本引擎和工作目录。可选 `engine` 参数（`rhino` / `quickjs`）会用**临时副本**指定引擎后运行，原文件不改动，运行结束自动删除副本。
- `stop_script`：只停止该 MCP 服务启动并记录的 executionId。

运行结果语义（`list_executions` / `get_execution` / `wait_execution` / `run_script`）：

| 字段 | 说明 |
|---|---|
| `status` | `QUEUED` / `RUNNING` / `SUCCEEDED` / `FAILED` / `STOPPED` |
| `stopReason` | 仅在 `STOPPED` 时出现：`user_stopped`（用户/客户端停止）或 `interrupted`（执行被中断）|
| `error` | 仅在**真的报错**（`FAILED`）时出现；脚本被停止时**不再**附带 `interrupted` 异常堆栈 |

> 主动停止是用**中断**实现的（等同于 Java 的 `InterruptedException`），中断点必然抛异常，
> 所以 `STOPPED` 不是错误：判定成功与否请用 `status`，不要再依据 `error` 是否存在。
> 引擎侧同时把 QuickJS 的中断异常统一成 `ScriptInterruptedException`，与 Rhino 行为一致。
- `list_engine_api` / `probe_engine_api`：枚举某引擎的全局 API、探测类型成员。
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
| 目标不存在 + `create=true` | 创建空的工作区（**自动创建父目录**），可直接 `workspace_write` 写入 |
| 目标已存在 + `create=true` | **幂等**：直接打开（不再报“新建目标已存在”）|
| 同一目标已有“未修改的 OPEN 工作区” | **复用**该工作区，不再新建（避免旧版本每个操作都建一个新工作区）|
| 传其他路径给单文件工作区 | 报错会带**完整路径**与正确做法，而不是旧的“单文件工作区不能新建其他文件”|

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

回归：在手机上通过 MCP `run_script` 跑全模块回归，`=== 回归测试完成: 225 通过, 0 失败 ===` + `=== QUICKJS_REGRESSION_OK ===`。

## 安全边界

- 页面不会因打开而自动启动服务；进程被系统回收后也不会自动恢复。
- 默认仅绑定 loopback。本机兼容开启时 loopback 可免令牌；提供错误令牌仍会拒绝。局域网请求始终验证随机 Bearer 令牌。
- 浏览器 Origin 一律拒绝，Host 只接受绑定接口的 IP 字面量或 loopback，避免 DNS rebinding。
- 编辑与执行授权仅保存在内存，停止服务立即撤销；令牌重置只能在停止状态进行。
- MCP 默认只读。手机端开启编辑授权后，客户端可直接应用工作区；回退仍在手机历史页执行。关闭授权会立即阻止后续写入和应用。
- 工具不提供任意文件读取、任意 Shell 或任意任务停止。
- 执行脚本会继承 AI.js Pro 已有的手机权限，开启执行前必须确认连接的是可信客户端。
