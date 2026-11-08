# MCP 冒烟 / 回归脚手架

`.4` 每轮用 21 个 `smoke*.mjs` 手工重放 218 个工具（覆盖很好，但**不在我们仓库里、改坏了不会被发现**）。
这里把我们**最容易被改坏的不变量**固化成一条命令，改完 MCP 直接回归。

## 跑法

```powershell
# K40（USB，默认）：自动 adb forward 后跑
.\tools\mcp-smoke\run.ps1

# 局域网设备（例如 .4），完全不碰 adb
.\tools\mcp-smoke\run.ps1 -Base http://192.168.10.4:8765/mcp

# 只跑某几组 / 跳过某几组 / 输出 JSON
.\tools\mcp-smoke\run.ps1 -Extra @("--only","shape")
.\tools\mcp-smoke\run.ps1 -Extra @("--skip","apk", "--json")

# 直接用 node（任何平台）
node tools/mcp-smoke/smoke.mjs --base http://127.0.0.1:18768/mcp
```

退出码：`0` = 全过（`SKIP` 不算失败）；`1` = 有 `FAIL`；`2` = 脚手架自身出错。

> ⚠️ **别用 PowerShell 的 `Invoke-WebRequest` 打这个 MCP**：它会发 `Expect: 100-continue`，
> 而 MCP 服务端按设计对 `Expect:` 直接回 **417 Expectation Failed**。用 `node`/`curl`/`fetch` 都可以。

## 覆盖什么

| 组 | 检查 | 为什么固化成回归 |
|---|---|---|
| `init.*` | `serverInfo`（build/versionCode）、`capabilities.tools.listChanged`、`instructions` **是字符串**、`toolCategories` | 升级后宿主刷新（v4 §10.4）；`instructions` 曾经被当数组处理而漏掉提示 |
| `tools.list.*` | 每个工具有 `name/description/object inputSchema`；`toolFilter` 默认不裁剪；`groups`/`tier=core` 生效 | 第七章上下文成本；schema 被改空是"照抄必失败"的根源 |
| `shape.*` | 成功 = `content`+`structuredContent`+`ok:true`；**失败 = 信封 + `isError` + 顶层 `errorCode` 与信封一致** | P1-A/P0-1 的核心不变量；dev-1398 修过"桥接到 `:tools` 进程的工具顶层没有 errorCode" |
| `coordinate.*` | `coordinate_convert` 有 `content`；未知空间名报 `INVALID_ARGUMENT`；标定只对它那一对生效且不匹配给 `calibrationNote` | dev-1392 的 P0-1（协议级）+ dev-1398 的两处诚实性修复 |
| `edit.*` | `file_edit_text` 的 `write_target` **不传 `matchText`** 也能写 | P1-C：skill 文档示例曾经照抄必失败 |
| `status.*` / `build.*` | `get_status.baseline`、`get_build_tools` 的 `ready/missing/present` | 基线自检与组件可见性（P0-2） |
| `apk.*`（可选） | 最小 `apk_read_text`、`axml:` 定位符读回 XML、dex 游标 `cursorTool` + 跨工具点名 | P1-B/P1-F/P1-E；设备上没有 APK 时自动 `SKIP` |

## 设计约定

- **零依赖**：只用 Node 内置 `fetch`（Node >= 18），不进 `package.json`，不拖慢构建。
- **只读为主**：唯一的写操作是 `edit.writeTargetWithoutMatchText`（写 `MCP测试/__smoke__/shape.txt` 后立即 `file_delete`），
  `coordinate.calibrationScope` 会在 `finally` 里 `clear`，`apk.*` 用完 `apk_session_release` + `apk_workspace_delete`。
- **不假设工具总数**：只断言"两次 `tools/list` 一致"和数量下界，避免每加一个工具就要改脚手架。
- 失败信息尽量带**期望与实际**，方便直接贴进回复稿。

## 排查

- **`FAIL` 后不知道期望/实际？** 失败行带期望与实际，直接贴进回复稿即可。
- **只跑一组**：`--only coordinate`（按 id/标题子串匹配）。
- **PowerShell 里 `Invoke-WebRequest` 打 MCP 报 417**：见上（`Expect:` 头）。
- **Gradle 测试"卡死"但其实没死**：本机曾出现「测试任务 30 分钟无输出、`jstack` 显示 Test worker BLOCKED 在
  `ToolkitJobs.status()` 监视器上」——根因是上一轮被中断的 **`GradleWorkerMain` 残留进程**仍持有旧 monitor。
  处置：`Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ? { $_.CommandLine -match "GradleWorkerMain" } | % { Stop-Process -Id $_.ProcessId -Force }`
  然后重跑，同一套测试 **65 秒** 全过。

## 现状（2026-11-08）

在 K40（dev-1398）上全绿；`.4`（局域网）可直接 `-Base http://192.168.10.4:8765/mcp` 复跑同一套。
