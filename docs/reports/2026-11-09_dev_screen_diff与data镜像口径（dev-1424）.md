# dev_screen_diff（逐帧像素差异）+ data 镜像口径修正（dev-1424）

> 日期：2026-11-09 08:5x–09:4x · 起点 HEAD b9128d0a · K40=cccc62c7（dev-1424）

## 0. 本轮做了什么

用户「继续」后，从交接单 ⑥⑦ 里挑 **`dev_screen_diff`**（范围提案里的第一优先）开工，并顺带修掉 **data 镜像护栏的口径偏差**（它是我在 dev-1418 观察到的实际问题：~48KiB 扁平结果镜像后单次响应 ~145KiB）。

## 1. 新增 `dev_screen_diff`（W4 task-26 + task-28）

- 复用 `capture_screen` 的截图管线（region/等比缩放**同一个实现**，不是另起一套）；`captureMode` 与 capture_screen 的 `mode` **同一套预设**。
- 基线：显式 `baseline` 路径 或 **上一帧**（进程内只留一帧、原始像素+sha256）；没有基线时**明确失败**（`NO_BASELINE`）而不是拿黑图当基线。
- 比较（纯函数）：每通道灰度差**严格大于** `tolerance` 才算变化；返回 `changedPixels/ratio/bbox/comparedPixels/maxChannelDelta/frameSha256`；`mode=mask` 生成掩码（白点数恒等于 changedPixels），`saveDiffTo` 落盘走既有归置。
- 诚实边界：像素级、不做语义/模板匹配、`ratio≠界面变化程度`；尺寸不一致**拒绝硬比**（`SIZE_MISMATCH`）。

### 真机跑出来的两个可用性问题（task-28 修掉）

| 问题 | 实测 | 修法 |
|---|---|---|
| `capture_screen{mode:"fast"}` 抓 **540x960**，紧接着 `dev_screen_diff` 抓 **720x1280** → 直接 `SIZE_MISMATCH` | K40 dev-1423 | 新增 `captureMode`（同套预设）+ **省略 maxDims 时沿用上一帧宽度**（`sizeSource:"previous_frame"`）；`SIZE_MISMATCH` 给**可抄**补救（`suggestedArguments` + `copyableCommand`） |
| **第一次 diff 抓的帧没记入帧缓存** → 连调两次永远没有基线 | K40：call1 `NO_BASELINE`、call2 仍失败 | 抓到帧**先 remember 再判基线**；`NO_BASELINE` 的 nextActions 改为「再调一次即可」+ `details.frameRemembered` |

### K40 真机四态（dev-1424，全部通过）

| 场景 | 结果 |
|---|---|
| `capture_screen{mode:"fast"}` → `dev_screen_diff{mode:"count"}` | `ok=true`、`changed=0`、`size=540x960`、`sizeSource=previous_frame`、`maxW=540` ✓（不再 MISMATCH） |
| 连调两次 diff（同屏） | 两次都 `ok=true`、`ratio=0`、`baseline=previous_frame` ✓ |
| 切到桌面后再 diff（中间不重新 capture） | `changedPixels=516343`、**`ratio=0.996`**、`bbox={0,0,540,960}` ✓ |
| SIZE_MISMATCH 补救 | 强制 360 宽 → `ERR=SIZE_MISMATCH` + `suggested={maxDims:540}` + `copyableCommand=dev_screen_diff{maxDims:540}` ✓（反向同理给出 maxDims:360） |

## 2. data 镜像护栏口径修正（W4 task-27）

- 判定口径由「镜像**前** structuredContent」改为「**镜像后** structuredContent + `content[0].text` 的总字节」，上限 64KiB→**32KiB**。
- `shell_capabilities` / `get_ui_layout` / `read_api_doc` / `get_mcp_log` 改为**轻量摘要 data**（只保留标量与容器计数，完整内容仍在 `content[0].text` 与扁平顶层字段）。
- **K40 实测体积**：`shell_capabilities` 95,972B → **48,410B**；`get_ui_layout` 94,282B → **47,564B**（两者现在给 `dataOmitted`）；`get_mcp_log` 517B 且带 `dataSummary:true` 的摘要；`crash_buffer_semantics`(18,976B) 与 `list_engines`(942B) 仍是整棵镜像；`coordinate_convert` 等信封工具不受影响。

## 3. 验证与发布

- gradle：`--tests com.jdkshen.aijspro.mcp.*` **全绿**（本轮 build 目录被清空过一次，全量重建 3m10s；新增 `ToolkitScreenDiffTest` 8 项、`McpToolResultShapesTest` 增至 7 项）。
- K40：dev-1424 装机后 **全量冒烟 PASS 29 / FAIL 0 / SKIP 0**；`toolCount=221`（canonical 212 / advertised 221）。
- 候选 144,395,769B / SHA-256 `64715572403db978511138a576e31544f4d6eba684d94bb24f3ca0851923c906`；**LAN 已发布**（`update.json`=1424，手机拉取 HTTP 200）。
- 提交 `1973a438`；工作树干净，**194 ahead，未 push**。

## 4. 未做 / 边界

1. **有意不做「基线自动缩放再比」**（W4 的取舍，我同意）：缩放引入插值噪声，会把「抓错了」伪装成「有差异」；现在是从源头对齐尺寸（沿用上一帧宽度）+ 硬失败 + 可抄补救。
2. 沿用上一帧宽度的副作用：上一帧若是 region 裁剪帧（如 300 宽），下一次全屏调用会被缩到 300 宽 → 宽高比不同仍会 MISMATCH（提示会让人用同一 region）。
3. `frameSha256` 是**原始像素**哈希；帧缓存是**进程内**的（`:script` 直调是另一份，MCP 客户端走不到）。
4. `saveDiffTo` 只接受 .png 或目录（掩码固定 PNG），传 .jpg 明确报错。
5. data 摘要的「名单外工具」在 >32KiB 时一律不补 data（新口径的必然结果）；三档阈值（小补/中摘要/大省略）未做。
6. 随包 md 与 `tools/mcp-smoke` 未同步 `dev_screen_diff`/`vm_survey`（W3/Lead scope，未做）。
