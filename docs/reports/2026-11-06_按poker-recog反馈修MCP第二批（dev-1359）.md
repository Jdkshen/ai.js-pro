# 按 `.4` 项目反馈修的第二批（dev-1359）

> 接 [第一批（dev-1356）](2026-11-06_按poker-recog反馈修MCP第一批（dev-1356）.md)。本批做反馈里剩下的三条：**P0-2/P2-12（文档落后于构建）**、**P1-6（三套坐标系，用户标★最痛）**、**P0-5（图片句柄泄漏没有线索）**。
> 分工：Lead 做 P1-6 并接线；`manifest-detector` 写 P0-2 的文档/对账逻辑；`native-process` 写 P0-5 的引擎侧诊断（两人都只改自己那份文件，构建/验证/发布由 Lead 统一做）。

## P0-2 / P2-12：文档 vs 实际构建，现在可以一眼对账

`list_engine_api` 的每一项都会给出 `documented`，**同时保持向后兼容**（原 `items` 仍是字符串数组）：

```jsonc
{ "engine":"nodejs", "module":"image", "count":55, "items":[...原有字符串...],
  "documentedItems":[{"name":"matchTemplate","documented":false}, …],
  "documentedCount":3, "undocumentedCount":52, "build":"dev-1359",
  "documentedNote":"…同一 module 优先、任意 module 兜底；记录 engines 必须含本次 engine；指南标题提及不等于逐成员文档…" }
```

**MI8 实测**：`nodejs/image` 55 项里 **documented=3 / undocumented=52**，而反馈里那条**「文档写着未接入、实测 21ms 能用」的 `matchTemplateMulti` 正好是 `documented:false`** —— 这类偏差从此不用靠真跑才发现。

`search_api_docs` / `read_api_doc` 新增：`build`、`documentedAt`、`documentedAtSource`、`documentedAtNote`。
时间来源按「不猜」原则分层：索引内 `generatedAt` → APK 内 `assets/docs/api-reference-data.js` 的 ZIP 条目时间（≥2000 年才采信）→ **该 build 的安装时间**（`apk.lastUpdateTime`）。MI8 实测拿到 `documentedAt=1793922789266 / source=apk.lastUpdateTime`，并在 `documentedAtNote` 里写明**「这不是文档生成时间，只是你手上这个 build 有多新」**。
> 真正的解法是生成器写 `generatedAt`（实现已优先读取，`tools/build-api-reference.py` 尚未加字段）——列为下一步。

## P1-6：`coordinate_convert`（三套坐标系，★最痛那条）

新增工具，四种 action：

```jsonc
{"action":"state","measureScreenshot":true}   // panel / appDisplay / windowBounds / 实测截图尺寸 + 仿射
{"action":"calibrate","windowPoint1":[0,0],"screenshotPoint1":[152,0],
                      "windowPoint2":[1000,1000],"screenshotPoint2":[1152,1000.9]}
{"action":"convert","from":"window","to":"screenshot","rect":[100,200,300,400]}
{"action":"clear"}
```

- `state` 明确区分 **panel（物理面板，通常=截图尺寸）** 与 **appDisplay（无障碍与浮窗同一套）**；两者不同就自动给出仿射并标 `affineSource=derived_from_panel_vs_app` + `affineAssumed=true`（**不假装精确**）；
- `calibrate` 用**两组对应点**求逐轴缩放+平移并持久化；只有一组点时退化为纯平移并说明；
- `convert` 支持 `point:[x,y]` 与 `rect:[l,t,r,b]`，返回 `affineSource`（`calibrated` / `derived_*` / `identity`）；
- `state` 里还带 `calibrationHowTo`（三步）和浮窗那条硬结论：**根 View 的 `layout_*` 会被 WindowManager 无视，根必须 `match_parent`、子 View 用固定 px**。

**MI8 实测**（该机 panel=appDisplay=1080×2160，本就不需要映射）：

| 调用 | 结果 |
|---|---|
| `state` | panel/appDisplay/screenshot 均 1080×2160、density 2.75、`affineSource=no_mapping_needed`、`screenshotEqualsPanel=true` |
| `calibrate`（用反馈里标定出的两组点） | 复现出他们的关系：`scaleX=1, scaleY=1.0009, offsetX=152, offsetY=0` |
| `convert` `rect[100,200,300,400]` | → `[252,200,452,400]`，`affineSource=calibrated` |
| `clear` | `{cleared:true}` |

## P0-5：图片句柄泄漏现在会自己说清是哪 5 个句柄

触发限额时的错误（真机原文，27 帧 1080×2160 全屏图未回收）：

```text
图片总像素超过上限（registeredPixels=62985600/64000000，本次新增=2332800，liveCount=27，
最近创建的句柄（最多5个）=[{"id":27,"width":1080,"height":2160,"ageMs":4},
{"id":26,...,"ageMs":85},{"id":25,...,"ageMs":166},{"id":24,...,"ageMs":261},{"id":23,...,"ageMs":354}]）。
是否忘记 recycle()？请调用 images.recycle(img) / img.recycle()，或宿主 action=recycle {id}；
任务结束/宿主 close() 也会统一释放；只读诊断：__ajsHostValue({kind:'image',action:'diagnostics'})
```

并新增**只读诊断**（脚本侧一行）：

```js
var d = require("image").diagnostics();   // 也可 images.handles()
// {registeredPixels, limit, liveCount, maxLiveImages, maxImagePixels, handles:[{id,width,height,ageMs}]}
```

实现细节（队友交付）：句柄创建时间与句柄表同步维护、沿用原有 synchronized 锁、**注册热路径只多一次 O(1) put**，只有诊断/报错时才遍历。

## 边界与未做

1. **`documentedAt` 不是文档生成时间**：真实索引（schema v3）没有 `generatedAt`，现在退到 build 安装时间并明确标注；要拿到真实生成时间需改 `tools/build-api-reference.py` 并在下次构建重新生成资产。
2. `documented` 是**标题/指南标题 + module 的启发式**匹配，不是全文语义检索；**指南标题提及 ≠ 逐成员文档**；Rhino-only 记录不会把 QuickJS/Node 标成已文档化（这是刻意的）。
3. `coordinate_convert` 的自动推导只在「两边都被认为是整块空间」时成立；不同 ROM 的挖孔/圆角/虚拟显示可能让 panel 与截图不一致，此时**必须用 `calibrate` 实测标定**（工具也会提示 `affineAssumed=true`）。
4. `floating_window` 文档本身本轮未改（只把结论写进了工具返回的 `hint` 与 `calibrationHowTo`）；`coordinate_convert` 只做换算，不改变任何窗口行为。
5. 本批只在 MI8 上验证；`.4`（反馈来源机）需装 **dev-1359** 后复跑它自己的复现清单确认（尤其它那台 panel≠appDisplay 的情形，正好能验证 `derived_from_panel_vs_app`）。
