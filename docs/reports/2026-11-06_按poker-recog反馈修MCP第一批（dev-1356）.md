# 按 `.4` 项目反馈修的第一批（dev-1356）

> 来源：`/storage/emulated/0/脚本/poker-recog/MCP-FEEDBACK.md`（10,966 B，扑克牌识别项目连续实测）。
> 环境：AI.js Pro dev-1347 → dev-1354，Node 24.22.0，Android 无 root。

## 一、逐条对账

| 编号 | 反馈 | 本批状态 | 证据 / 说明 |
|---|---|---|---|
| **P1-7** | `capture_screen` 要「只落盘、不回传」 | ✅ 已修 | 新增 `savePath` + `saveFormat`（`auto`/`png`/`jpg`/**`raw`**）。raw = RGBA8888 行优先、`stride=width*4`、无文件头（与 `image.exportPixels` 同布局），**不做 PNG 编码也不回传 base64**。MI8 实测整屏：`raw` = 采集 142ms + 像素拷贝 12ms + 写盘 245ms = **401ms**、9,331,200 B（=1080×2160×4，字节数自证）；同帧 `png` = **625ms（编码 552ms）**；`fast`+`savePath` = **171ms**。 |
| **P0-4** | `run_script` 参数别名与报错不 actionable | ✅ 已修 | `code` 直接作为 `source` 的别名；`source`+`code` 同给会明确拒绝；空输入报「只给了 engine：内存源码请放 source/code，磁盘脚本请放 path/file」。实测 `run_script{code:"..."}` → SUCCEEDED。 |
| **P0-1** | `app_list` 返回值自相矛盾 | ✅ 已修 | 新增 `rawCounts{getInstalledPackages, launcherActivities, packageManagerReturned}` 与 `coverageSuspect`；当 PM 回包 < launcher 可见包数时：`scanScope=package_manager_returned`、`visibilityLimited=true`、`scanNote` 写明原因；并新增 `visibilityVerified:false`（没做对照就不声称看全）。MI8：256/37/256、`coverageSuspect=false`（本机确实可见）；`.4` 那台 1 个包的情形会直接标可疑。 |
| **P1-9** | `get_status` 应直接回答「更新了什么」 | ✅ 已修 | 增 `app{packageName,versionName,build,versionCode,lastUpdateTime}`、`toolCount`、`canonicalToolCount`。实测：`build=dev-1356 / versionCode=1356 / toolCount=212（canonical 203）`。 |
| **P2-10** | 工作区残留 193 个 | ✅ 已修（提示侧） | `workspaces > 50` 或有待确认工作区时，`get_status` 返回 `nextActions`：先 `workspace_cleanup{dryRun:true}` 看能回收多少、APK 工作区走 `apk_workspace_inventory`/`apk_workspace_gc`。 |
| **P1-8** | `search_scripts` 要区分「扫完没有」和「没扫完」 | ✅ 已修（语义侧） | 增 `stopReason`（`completed`/`hit_limit_reached`/`file_scan_limit_reached`）、`scanLimits{maxFiles,maxHits,scannedFiles,hits}`、`warning`（**不能据此判定「不存在」**）、`resumeHint`（把 `path` 指到子目录）。实测未限定路径：`stopReason=file_scan_limit_reached`；限定子目录：`completed`。**未做**可续扫的 cursor（见下）。 |
| **P2-11** | `result.logs` 原始串带控制前缀 | ✅ 已修 | 除结构化 `logs[]` 外新增 `logsText`（干净文本，无 `\u001eAJSLOG1:` 前缀），调用方直接可读。 |
| P0-2 / P2-12 | 文档落后于构建、没有版本标注 | ⏳ 下一批 | 需要在文档索引里带 `documentedAt` + `build`，并给 `list_engine_api` 每项加 `documented:true/false` 双向对账（含重新生成 APK 内置索引）。 |
| P0-5 | 图片句柄泄漏错误不含线索 | ⏳ 下一批 | 需要引擎侧暴露 `registeredPixels/limit/句柄表{id,width,height,ageMs,创建栈}` 并新增 `image_handles` 诊断工具（照 `list_overlays` 的样子）。 |
| P0-3 | 看图器按路径缓存，改图仍显示旧图 | ⏳ 下一批 | 缓存键改 `(path,size,mtime)` + 提供 `fresh:true`（App 侧查看器）。 |
| P1-6 | 三套坐标系没有换算工具（★最痛） | ⏳ 下一批 | 计划新增 `coordinate_convert`：一次返回截图空间 / 显示空间（`getDisplayMetrics`）/ 浮窗空间三者的尺寸与仿射关系，并把「浮窗 layout_* 被 WindowManager 无视、必须 match_parent + 子 View 固定 px」写进 `floating_window` 文档。 |

## 二、这次特别值得记的两点

1. **「只落盘」比「更快地回传」重要一个数量级**：反馈里说的「PNG 编码 550ms 是主因」被实测证实——而真正的解法不是把 PNG 压得更小，而是**根本不编码**（raw 像素拷贝 12ms）+ 不回传（省掉 base64 与 JSON 传输）。401ms 里有 245ms 还是写盘，若目标是 `/dev/...` 或缓存目录还能再降。
2. **诚实优先于好看**：`app_list` 的 `visibilityLimited:false` 正是让人误判「这台机器只装了 1 个应用」的原因；现在宁可报 `coverageSuspect:true` + `package_manager_returned`，也不自称 `all_installed`。同一原则用在 `search_scripts` 的 `truncatedScan` 上——`total=0` 不再等于「不存在」。

## 三、边界与未做

1. `search_scripts` **没有**可续扫 `cursor`：当前用「显式 stopReason + warning + resumeHint」把不确定性说清楚（用户自己的建议里 cursor 是「像 file_search 那样」，这需要把目录遍历状态持久化成游标，列为下一批）。
2. `savePath` 受**脚本目录**边界约束（与 `file_*` 一致）；`raw` 布局不写文件头，尺寸/格式在返回值里给，调用方需自己带上宽高（避免给 CV 流水线加一次解析）。
3. `app_list` 的 `coverageSuspect`只在 launcher 可见包数 > PM 回包数时触发；若两者都被系统遮挡（都为 1），只能靠 `rawCounts` 两个数都异常小来人工判断。
4. 本批只在 MI8 上验证；`.4`（反馈来源机）需装上 dev-1356 后复跑它自己的复现清单再确认。
