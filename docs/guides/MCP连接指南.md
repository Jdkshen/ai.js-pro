# MCP 连接指南（手机 AI.js Pro ↔ 电脑 AI 客户端）

<a id="rik"></a>

本文对应 **dev-1251+**。

> 同一台手机上用 Rikkahub / Rikk 连接：直接用本机地址 `http://127.0.0.1:8765/mcp`，见下文 [Rikkahub 配置示例](#rik)。目标：让电脑上的 MCP 客户端连接到手机的 AI.js Pro，在电脑上列脚本、跑脚本、读日志、控制手机界面。

## 一、准备

- 手机安装 AI.js Pro 开发版（当前 dev-1251 或更高）。
- 电脑与手机连同一个可信 WiFi；或电脑装好 ADB 用 USB。
- 可选：要用 `get_ui_layout` / `capture_screen` / `ui_*` / `app_*` 时，先在手机开启 AI.js Pro 无障碍服务。
- 不需要 root；MCP 以应用 UID 运行，权限与 AI.js Pro 自身相同。

## 二、在手机上开启 MCP

1. 打开 AI.js Pro，点左上角菜单（☰），找到 **MCP 服务**。
2. 打开右侧开关，等待状态变成 **服务运行中**。
3. 点 MCP 服务这一行进入“设备连接”页。

![打开 MCP 服务](images/mcp-guide-1-drawer.png)

> 图中 1 是 MCP 服务入口。开关打开后默认监听 `0.0.0.0:8765`，端口被占用时自动在 `8765..8795` 顺延；实际端口以页面显示为准。

## 三、复制地址

进入“设备连接”页后，会看到两组地址：

| 地址 | 使用场景 |
|---|---|
| 局域网地址 `http://<手机IP>:8765/mcp` | 电脑和手机在同一 WiFi，推荐 |
| 本机地址 `http://127.0.0.1:8765/mcp` | USB 连接，先执行 `adb forward tcp:8765 tcp:8765` |

MCP 服务当前**免访问令牌**；端点固定为 `POST /mcp`，JSON-RPC 2.0。

![MCP 地址页](images/mcp-guide-2-url.png)

> 图中 1 是局域网地址，2 是服务状态/端口。复制地址按钮会自动带入当前实际端口。

## 四、填入客户端

向下滚动到 **接入 AI 客户端**：

![接入 AI 客户端](images/mcp-guide-3-client.png)

客户端必须支持 **HTTP MCP server / Streamable HTTP**。在客户端的 MCP 设置里新增一个 HTTP server，名称可填 `ai-js-pro`，URL 填：

```text
http://192.168.10.6:8765/mcp
```

把 IP 换成你手机页面上的实际地址。也可以直接点“复制客户端配置”，得到：

```json
{
  "mcpServers": {
    "ai-js-pro": {
      "url": "http://192.168.10.6:8765/mcp"
    }
  }
}
```

不同客户端的字段略有差异：

- 通用 `mcpServers` 配置：使用上面的 `url`；
- Claude Code / 支持 remote MCP 的客户端：通常补 `"type": "http"`；
- 只接受 stdio 的旧客户端：需要额外的 HTTP-to-stdio 代理，本项目不内置；优先用支持 HTTP MCP 的新版客户端。

### USB 方式

```powershell
adb devices
adb forward tcp:8765 tcp:8765
```

然后客户端 URL 填：

```text
http://127.0.0.1:8765/mcp
```

如果手机页面显示端口不是 8765，把两处端口一起替换。

## 五、Rikkahub / Rikk 示例（同一台手机）

> **App 内入口**：AI.js Pro 的“设备连接 → 接入 AI 客户端”底部已新增 **查看完整连接指南（含 Rikkahub 截图）** 按钮，点开即本文。

![App 内指南入口](images/mcp-app-guide-link-red.png)

如果 Rikkahub（包名 `me.rerere.rik`）和 AI.js Pro 在**同一台手机**，不需要 ADB 转发，直接用本机地址：

```text
http://127.0.0.1:8765/mcp
```

### 操作步骤

1. 在 Rikkahub 打开 **MCP** 页面，点右上角 **+** 新建服务器。
2. 名称填 `aijspro`，传输类型选 **Streamable HTTP**。
3. 服务器地址填 `http://127.0.0.1:8765/mcp`（端口以 AI.js Pro 页面实际显示为准）。
4. 打开 **启用**，点 **保存**。
5. 回到 MCP 列表，看到 `aijspro`、**Connected**、**66/66 tools** 即连接成功。

![Rikkahub 新建 aijspro（红色备注）](images/rik-settings-red.jpg)

<p style="color:#c62828;font-weight:700">红色备注：① 启用 ② 传输类型 Streamable HTTP ③ URL 填 http://127.0.0.1:8765/mcp ④ 保存；返回列表看到 66/66 tools 即成功。</p>

![Rikkahub 已连接 66/66 工具](images/rik-mcp-servers-red.jpg)

### 列表里的 Failed 项

截图中的 `MTmpc`、`ajmcp`、`mymcpserver` 显示 `Failed to connect to /127.0.0.1:8787/8766/8899`，是旧配置或其它本地服务，与 AI.js Pro 无关。只保留 `aijspro` 这一项，避免同时启用多个同名/同端口服务。

### Rikkahub 在另一台设备上

- 同一 WiFi：把地址换成 AI.js Pro 页面上的局域网地址，例如 `http://192.168.10.6:8765/mcp`；
- USB：先在电脑执行 `adb forward tcp:8765 tcp:8765`，再填 `http://127.0.0.1:8765/mcp`；
- 手机 IP 变化后重新复制地址；Rikkahub 内保存后若仍是 Failed，关闭再启用一次。

### 使用链接

- 在 Rikkahub 中调用 `learn_autojs_skill` 获取完整工具说明；
- 调 `get_status` / `list_engines` 检查手机端状态；
- 用 `run_script` 时加 `wait: true` 获取真实输出和错误；
- 设备工具（`get_ui_layout` / `capture_screen` / `ui_*` / `app_*`）需要 AI.js Pro 无障碍服务。

## 六、确认连接成功

客户端连接后，先调用 `learn_autojs_skill`，再调用：

- `get_status`：确认 `scriptRoot`、MCP 服务、当前任务状态；
- `list_engines`：确认 Rhino / QuickJS / Node24 可用；
- `list_scripts` / `read_script`：读手机脚本；
- `run_script`（`wait:true`）：运行脚本并拿真实输出；
- `get_mcp_log` / `read_apk_logs`：查日志。

连接正常时 `tools/list` 会返回当前公开工具（本版 66 个名称，实际以客户端显示为准），分为：状态与诊断、脚本与执行、工作区、引擎与 API、设备与无障碍、终端与构建、悬浮窗、示例与帮助。

## 七、常见问题

### 电脑打不开地址

- 确认手机页面状态是“服务运行中”，不是“心跳过期”；点页面刷新。
- 确认电脑和手机在同一 WiFi，且路由器未开启 AP 隔离。
- 检查 Windows 防火墙是否拦截出站/入站；换 USB 方式验证。
- 手机 IP 可能变化，重新复制页面上的局域网地址。

### 端口不是 8765

MCP 会从 8765 起自动顺延，避免占用冲突。以页面显示的端口/地址为准；USB 转发时两处端口要一致。

### 手机锁屏/后台一段时间后断开

MCP 是前台服务；保持 AI.js Pro 后台存活，必要时重新打开应用并确认开关仍为开。系统清理后台后需要重新开启 MCP。

### 无障碍工具报错

`get_ui_layout`、`capture_screen`、`ui_*`、`app_*` 需要 AI.js Pro 无障碍服务；抽屉里确认“无障碍服务 已启用”。截图还要求 Android 11+ 且页面没有 `FLAG_SECURE`。

### 安全提醒

MCP 当前免令牌，任何能访问该端口的设备都能控制脚本和部分设备操作。只在可信局域网使用，不要把 8765 端口映射到公网；需要跨网络时用 SSH/VPN 等安全通道。

## 八、当前工具概览

| 分类 | 工具 |
|---|---|
| 状态与诊断 | `get_status`、`read_apk_logs`、`get_mcp_log` |
| 脚本与执行 | `list_scripts`、`read_script`、`search_scripts`、`continue_result`、`run_script`、`check_syntax`、`list_executions`、`get_execution`、`wait_execution`、`stop_script`、`stop_all_scripts` |
| 工作区 | `workspace_*`（open/list/read/write/delete/diff/request_apply/mkdir/cancel/cleanup） |
| 引擎与 API | `list_engines`、`list_engine_api`、`probe_engine_api`、`engine_api_diff`、`call_engine_api` |
| 设备与无障碍 | `get_ui_layout`、`capture_screen`、`ui_*`、`app_*`、`apk_*` |
| 终端与构建 | `terminal_exec/read/write/stop/list`、`get_build_tools` |
| 悬浮窗 | `list_overlays`、`close_overlays` |
| 示例与帮助 | `list_samples`、`read_sample`、`learn_autojs_skill` |

## 九、相关文档

- [MCP 手机终端与编译入口](../reports/2026-10-25_MCP手机终端与编译入口.md)
- [MCP 无障碍 UI 自动化](../reports/2026-10-25_MCP无障碍UI自动化.md)