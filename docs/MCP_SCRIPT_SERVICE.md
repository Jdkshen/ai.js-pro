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
- `list_scripts`：按相对路径分页列出脚本目录。
- `read_script`：分页读取脚本目录内允许的文本文件，并返回 SHA-256。
- `search_scripts` / `continue_result`：递归搜索文件名和内容，并使用短期游标继续分页。
- `list_samples` / `read_sample`：分页列出和读取 `sample` 内置示例。
- `read_apk_logs`：增量读取 AI.js Pro 全局控制台日志。
- `list_executions` / `get_execution`：读取本 MCP 发起的运行任务状态、结果与异常。

手机端开启“允许运行脚本”后：

- `run_script`：只运行操作目录内已有 `.js`，继续使用应用现有脚本引擎和工作目录。
- `stop_script`：只停止该 MCP 服务启动并记录的 executionId。

手机端开启“允许编辑工作区”后：

- `workspace_open` / `workspace_list` / `workspace_read`：从真实脚本建立并查看 App 私有快照。
- `workspace_write` / `workspace_delete`：只改变私有副本，不直接覆盖真实脚本。
- `workspace_diff`：查看基线与私有副本差异。
- `workspace_request_apply`：提交待确认申请。真实写入只能由手机历史页调用；写入前校验原始哈希并建立备份，应用后可以回退。

单次读取或写入上限 256 KiB，列表单页最多 200 项。当前实现是无会话的单 JSON Streamable HTTP，兼容 `2024-11-05`、`2025-03-26` 和 `2025-06-18` 的单消息客户端，不支持 HTTP batch 或 SSE-only 响应。

## 安全边界

- 页面不会因打开而自动启动服务；进程被系统回收后也不会自动恢复。
- 默认仅绑定 loopback。本机兼容开启时 loopback 可免令牌；提供错误令牌仍会拒绝。局域网请求始终验证随机 Bearer 令牌。
- 浏览器 Origin 一律拒绝，Host 只接受绑定接口的 IP 字面量或 loopback，避免 DNS rebinding。
- 编辑与执行授权仅保存在内存，停止服务立即撤销；令牌重置只能在停止状态进行。
- MCP 客户端只能提交工作区应用申请；真实脚本的应用和回退只能在手机确认页面执行。
- 工具不提供任意文件读取、任意 Shell 或任意任务停止。
- 执行脚本会继承 AI.js Pro 已有的手机权限，开启执行前必须确认连接的是可信客户端。
