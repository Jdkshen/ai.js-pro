# tools/list 分级暴露：设计与收益测算（W4 · task-137）

> 本轮**只读**（未改任何源码）。基线：HEAD 8d9c7103 / LAN dev-1551。
> 所有 file:line 均本轮实读；**设备侧字节/token 数沿用 Lead 实测**（我无法在本轮跑真机，见「未验证边界」）。

## 0. 结论（先看这条）

1. **方案 B（分层暴露）已经在代码里了** ✓ —— `tools/list` 支持 `params.tier=core` 与 `params.groups=[...]`，
   **不认识这些参数的客户端行为完全不变**（走 `else -> true` 分支返回全量）⇒ **无需改协议、无需新参数**。
   建议：**先用它**（零代码改动，收益最大），再决定要不要做方案 A 的分页。
2. **方案 A（标准分页 cursor/nextCursor）尚未实现** ✗ ⇒ 只有确需「一次拉全量、分多页」的客户端才值得做，
   实现点很小（2 处），但要动 MCP 规范面（`nextCursor` + 客户端循环语义）。
3. **第 3 项（删自定义字段）我建议「不做」** ✗：`category/categoryTitle` 在 `tools/list` 里**只有 2 处 JVM 测试 + 4 处文档**消费
   （**Flutter UI 与 tools/ 都是 0 命中** ✓），逻辑不依赖（分组判定走 `McpToolCategories.categoryFor`，不读返回字段）；
   但它们同时是 **`groups` 过滤与文档 UX 的对外契约**，省下的字节（约 11.6 KB / 3.6k token ≈ 5%）远小于方案 B 的 94%。

## 1. 现状取证（本轮实读）

| 事实 | 位置 |
|---|---|
| 工具总数 **227**（217 规范名 + 10 别名） | `apps/app/src/test/java/com/jdkshen/aijspro/mcp/McpToolCatalogTest.kt:13` |
| 每个工具的 JSON 形状：`name/category/categoryTitle/description/inputSchema` | `apps/app/src/main/java/com/jdkshen/aijspro/mcp/McpTools.kt:1728-1737`（`private fun tool(...)`） |
| `tools/list` 路由入口 | `McpTools.kt:116` → `listTools(McpRpcValidation.optionalParams(params))` |
| `tools/list` 组装本体 | `McpTools.kt:269-393`（`add("tools", JsonArray…)`） |
| **裁剪过滤（已存在）** | `McpTools.kt:399-443` `applyToolFilter`：`tier=core` ✓ / `groups`（数组或逗号串）✓ / 未知参数 ⇒ `else -> true`（**返回全量**）✓ |
| **core 白名单 28 个** | `McpToolCategories.kt:67-76` `coreToolNames` |
| 10 个分组定义 | `McpToolCategories.kt:12-23`（`status/script/workspace/engine/device/terminal/overlay/asset/toolkit/reverse`） |
| 过滤结果回显（含 `applied/total/returned/availableGroups/note`） | `McpTools.kt:417-442` |
| 客户端可见提示 | `McpTools.kt:239` `toolFilterHint`；`get_status` 另带 `toolCategories`（`McpTools.kt:229-238`、`:569`） |
| 无 `tools/list` 分页 | 全仓 `nextCursor` 命中都在 **APK 工具的 schema/文档**（如 `apps/app/src/main/assets/mcp/toolkit-apk-schemas.json:809`），**不在 list 处理器** ✗ |

## 2. 方案 A：MCP 标准分页（`params.cursor` ⇒ `nextCursor`）

**实现位置与改动量（预计 2 处、约 40–60 行）**
1. `McpTools.kt:116` 路由处：把 `params` 里的 `cursor`（字符串）与 `limit`（可选）透传进 `listTools(params)`。
2. `McpTools.kt:269-393`：组装完成后**先分页再过滤**（顺序很关键：过滤后的集合才是分页的域，
   否则 `nextCursor` 会跳页）—— 返回体加 `nextCursor`（首页/续页同一字段；末页省略或 null）。
   游标建议用**无状态编码**（`base64(工具名列表指纹 + 偏移)` 或直接 `offset` 签名），避免服务端会话状态。

**N 的建议与依据**
- 分页“第一页最常用 N”与 `tier=core` 的 28 个高度重合 ⇒ 建议 **N=40**（core 28 + 12 个次常用：
  `read_apk_logs/get_mcp_log/tool_schema/ui_find/ui_click/app_launch/apk_install/apk_uninstall/list_engines/probe_engine_api/workspace_diff/read_sample`）。
- 依据：core 白名单在 `McpToolCategories.kt:67-76` 是**主观白名单**（注释自陈）；N=40 时首屏 ≈ 全量的 18%，
  且能覆盖“起手就要用”的两端（状态/脚本/文件/终端/APK 会话）。
- **必须保证工具一个不少**：`nextCursor` 走到底必须回到 227/227（可加单测：分页拼接 == 全量）。

**兼容风险**：MCP 客户端**不认识 `nextCursor` 时会只看到第一页** ✗ ⇒ 这是本方案最大风险
（当前 74k token 的现状说明客户端**本来就能吃全量**；分页反而可能让不支持循环的客户端**看不到后面的工具**）。
**是否需要改协议版本**：不必（`cursor/nextCursor` 是 2025-06-18 规范的**可选**能力，不改 `initialize` 的版本协商）。

## 3. 方案 B：分层暴露（**已实现，建议直接用**）

- 现状即所需：`params.tier=core` ✓、`params.groups=[组id]`（数组或逗号串）✓、不带参数 ⇒ **全量** ✓。

- **降级行为已满足**：`McpTools.kt:415` 的 `else -> { _ -> true }` ⇒ **不认识新参数的客户端行为不变** ✓。
- 可选增强（**建议先不做**，需 Lead 裁决）：
  1. 把 `tier=core` 写进 `initialize` 的 `instructions` 引导（纯文案）；
  2. `coreToolNames` 从 28 扩到 40，与方案 A 的 N 对齐；
  3. 允许 `tier=core&groups=...` 组合（现在是 `tier` 优先、`groups` 被忽略 ✗ —— **唯一的口径含糊点**）。

## 4. 收益测算（**派生**自 Lead 实测：全量 74k token、inputSchema 111,437 B ≈ 34,824 token、227 工具）

| 形态 | 工具数 | 预计字节 | 预计 token | 相对全量 |
|---|---|---|---|---|
| 全量（现状） | 227 | ~230 KB | ~74k | 100% |
| `tier=core` | 28 | ≈13.7 KB | ≈4.3k | **≈6%** |
| 首页 N=40 | 40 | ≈19.6 KB | ≈6.2k | ≈8% |
| 去掉 category/categoryTitle | 227 | −11.6 KB | −3.6k | ≈95% |

（单工具均值 ≈491 B ≈153 token = 111,437 B ÷ 227；首屏估算 = 均值 × 工具数 + 过滤/分页回显 ≈0.3 KB。）
**⇒ 方案 B 的收益是方案 A 与第 3 项的 10 倍以上，且零代码、零协议风险。**

## 5. 第 3 项：去掉 category/categoryTitle（含`_meta`/annotations/title）会不会破坏什么

**消费方实查（本轮 grep）**
- **Flutter UI（`flutter-ui/**`）：0 命中** ✓（不消费）。
- **`tools/**`：0 命中** ✓。
- **JVM 测试：2 处** ✗ —— `McpFileToolkitTest.kt:96`、`McpZipToolkitTest.kt:51`（都断言 `categoryTitle == 文件与APK工具`）。
- **文档：4 处** —— `docs/HANDOFF_AI.md:133`、`docs/guides/ARCHITECTURE.md:211`、`docs/reports/2026-10-25_MCP无障碍UI自动化.md:62/69`。
- **逻辑不依赖** ✓：分组过滤走 `McpToolCategories.categoryFor(name)`（`McpToolCategories.kt:27-61` 的表），**不读返回字段**。

**口径更正（重要 ✗）**：`_meta` / `annotations` / `title` **不是 `tools/list` 每项字段** ——
- `_meta` 出现在 **`initialize` 结果**（`McpTools.kt:251` `_meta.aiJsProToolCatalog`）；
- `title`/`annotations` 出现在 **toolkit/reverse 子集的 definition**（`McpZipToolkit.kt:142-156`）与该类工具的**会话返回**里；
- 而 `McpTools.tool(...)`（`McpTools.kt:1728-1737`）**只发 `name/category/categoryTitle/description/inputSchema`**。
⇒ 「11,635 B 的五个字段」是**混合口径** ✗；真正能省的 list 内字段只有 `category/categoryTitle`（`tool(...)` 子集），
且 toolkit/reverse 子集的 `title/annotations` 是否被并入 `listTools` 数组**我未逐行确认**（见未验证边界）。

**建议**：**不做**。若要做，改动 = `McpTools.kt:1731-1732` 删 2 行 + 同步 2 个 JVM 测试 + 4 处文档；
风险 = 破坏对外可见的自描述契约且收益仅 ≈5%。

## 6. 推荐动作（按性价比）

1. **立刻可用（零代码）** ✓：把 `tools/list{params:{tier:"core"}}` 写进 `initialize.instructions` 与 MCP 指南
   —— 现在只有 `get_status.toolFilterHint`（`McpTools.kt:239`）与代码注释提到它 ⇒ **发现性不足** ✗。
2. **小改（1 处、~10 行）**：定清 `tier` 与 `groups` 的**组合语义**（现在 `tier` 优先、`groups` 被静默忽略 ✗）。
3. **中等（2 处、40–60 行）**：方案 A 分页 —— 仅当出现「客户端不接受 4 万 token 首屏、又不支持裁剪参数」的实例再做；
   做时必须配「分页拼接 == 全量 227」单测。
4. **不建议** ✗：删 `category/categoryTitle`（收益 5%、破坏对外契约与 2 个测试）。

## 7. 未验证边界（照实）

1. **字节/token 数字是派生的** ✗：74k / 111,437 B / 11,635 B 均来自 Lead 实测口径；我本轮**没有**设备侧测量
   （本轮只读；构建/装机/发布归 Lead）。首屏 13.7 KB ≈4.3k 是「均值 × 工具数」的估算 ✓。
2. **`listTools` 是否把 toolkit/reverse 的 `definition()`（带 `title/annotations`）并入返回数组**：我只确认了
   `McpTools.tool(...)`（`McpTools.kt:1728-1737`）与 `McpZipToolkit.definition()`（`:142-156`）两处形状，
   **未**逐行确认 `listTools` 的合并点 ✗ ⇒ 「五字段 11,635 B」里 `title/annotations` 的占比我**不背书** ✗。
3. **未跑任何编译/测试** ✓（只读任务；改动量为估算）。
4. **客户端对 `cursor/nextCursor` 的支持**只有规范与常识依据 ✗，无本机客户端实证。
5. `coreToolNames` 是**主观白名单**（源码注释自陈 ✓）⇒ N=40 的建议同样主观，需 Lead/用户认可。

## 8. 交付物

- 本文件（**未改任何源码** ✓）。
