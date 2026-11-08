# AI.js Pro MCP 使用说明

本说明对应本项目的 Rhino / QuickJS / Node.js 三引擎和当前公开工具。先调用 list_engines 确认可用引擎。

逆向 APK / DEX / SO：先 `reverse_guide({"topic":"index"})` → `reverse_capabilities({})` → `reverse_plan({"goal":"inspect"})`。独立教程、完整工具参数示例、组件安装、后台任务及错误处理已内置；不用从历史聊天找说明。标准 MCP 客户端还可通过 `resources/list/read` 读取 `aijs://reverse/guide`、`aijs://reverse/workflows`，通过 `prompts/list/get` 取得工作流提示模板。手机「文件与 APK 工具」也可打开同源调用指南。

## 工具分类

tools/list 的每项都带 category/categoryTitle，description 也以 [分类] 开头；get_status 的 toolCategories 返回各类数量。分类只用于导航，不改变工具名、参数和权限。

| 分类 | 工具 |
|---|---|
| 状态与诊断 | get_status（含 `toolCatalog`/`mcp`/`template`/`toolCategories` 等自检块）、read_apk_logs、get_mcp_log |
| 脚本与执行 | list_scripts、read_script、search_scripts、continue_result、run_script、check_syntax、list_executions、get_execution、wait_execution、stop_script、stop_all_scripts |
| 工作区 | workspace_open/list/read/write/delete/diff/request_apply/mkdir/cancel/cleanup |
| 项目目录 | project_folder（state/create/set/tidy；产出自动归置） |
| 引擎与 API | list_engines、list_engine_api、probe_engine_api、engine_api_diff、call_engine_api |
| 设备与无障碍 | get_ui_layout、capture_screen、ui_find/click/long_click/node_action/input/swipe/global/wait/scroll、app_list/info/launch/open_settings/open_url、apk_install/uninstall |
| 终端与构建 | terminal_exec/read/write/stop/list、shell_rish、get_build_tools |
| 悬浮窗 | list_overlays、close_overlays |
| 示例与帮助 | search_api_docs、read_api_doc、list_samples、read_sample、learn_autojs_skill |
| 文件与 APK 工具 | toolkit_status、file_*、zip_*、apk_*（工作区/会话/列表/读取/编辑/搜索/DEX/Smali/资源/ELF/打包/签名元数据；签名元数据不是密码学验签） |
| 逆向分析 | reverse_guide/capabilities/plan、Apktool 工程解包/回编译、native_*、yara_scan、native_emulate、frida_*；按需组件状态以 apk_component_status 为准 |

## 快速开始（AI 客户端必读）

1. 先调用一次 learn_autojs_skill，再调用 get_status / list_scripts / list_engines 了解现场。
2. 只读：list_scripts → read_script / search_scripts；不要猜文件是否存在。
3. 运行：run_script 的 path/file/source 三选一；需要结果与错误日志必须 wait:true（timeoutSeconds 建议 30~120）。
4. 改真实文件：workspace_open → workspace_read/write/delete → workspace_diff → workspace_request_apply（workspace_write 本身不落盘）。
5. 任何失败：先看返回的 error/status，再调用 get_execution / wait_execution / get_mcp_log；不要重复猜参数。

## 返回结构与参数错误（新版兼容契约）

### 扁平标量工具（get_status / toolkit_status / get_build_tools）

这三个工具的新版本会同时提供两种读法：

- content[0].text：保持旧扁平 JSON，老代码继续按原字段读，形状不变；
- structuredContent：标准信封 {ok:true,data:{...}}，data 内是同一批标量字段。

新代码优先读 structuredContent.data；老代码继续读 content[0].text 即可。若当前构建还没有 data 镜像（升级前），以 content[0].text 为准；升级后两处字段应等价，不要因为多了一个 data 就认为旧字段被删除。当前实现里 get_status/get_build_tools 已加镜像；toolkit_status 结果来自 :tools 桥接进程，若该构建尚未镜像同样以 text 为准。

`get_status` 里还有几块**不属于扁平标量**的自检信息，不要漏读：`toolCatalog{toolCount,build,fingerprint}` 与顶层 `toolCount`/`canonicalToolCount`（K40 dev-1412 实测 **219 / 210**）、`mcp{...}`（MCP 服务自身状态，字段与排查顺序见 Playbook「get_status.mcp」）、`template{source,path,sizeBytes,sha256,engineRequireRootFix,templateStale,warnings,nextActions}`（dev-1404：打包**实际会用哪份模板**、是否含 require 根修复——`templateStale=true` 时按 `nextActions` 重建模板）、`toolCategories{}`（各分类工具数）、以及 `baseline{}`/`jobs{}`/`limits{}`/`outputConventions{}`。

### 参数类错误的 error.details

INVALID_ARGUMENT 或缺参数时，人类可读的 message 仍会给「可用参数/必填/tool_schema 提示」；新版本还会在 structuredContent.error.details 提供机读字段：

- tool：工具名；
- availableParameters[]：该工具可用参数名；
- required[]：必填参数名；
- schemaTool："tool_schema"（可直接调用 tool_schema 查完整类型/默认值/别名）；当前实现也可能附 schemaArguments，作为 tool_schema 的 arguments 快捷值。

机读请读 error.details，不要解析 message；顶层 details 可能作为镜像出现，以 error.details 为准。若旧构建没有 details，退回 message + tool_schema，不要声称参数可用。

**参数默认值以 `tool_schema` 为准**（工具描述里的「省略时=…」与 schema 的 `default` 同源；不要凭旧文档猜必填）。dev-1416 起放宽了一批假必填：`file_list` **零必填**（`recursive` 缺省 false、`limit` 缺省 200）、`file_search.recursive` 缺省 true、`file_copy.overwrite` 缺省 false、`file_replace_text.expectedReplacements` 缺省 -1、`zip_create.level` 缺省 6；安全闸仍保留必填（`file_delete_directory.recursive`、`zip_extract.overwrite`、`zip_create.overwrite`）。

## 产出目录约定（必须遵守）

> 这是产品行为，不是建议。所有 MCP 产出都归到**项目目录**，不再散落在脚本根；AI 一上手就先读本节。

1. **统一位置**：APK/ZIP、反编译结果、截图、导出的证据/JSON/日志，一律放操作根下的项目目录 `<root>/<项目名>/`（例如 `<root>/云镜分析/build/out.apk`）。
2. **没有就先建**：先调 `project_folder {action:"create",name:"云镜分析"}`；再用 `project_folder {action:"set",name:"云镜分析"}` 设为默认项目。`state` 可随时查看根目录/当前项目/已有项目。
3. **不要直接写根目录**：`<root>/out.apk`、`<root>/__verify/x.apk`、`tmp/out/test` 这类一次性名字都不要再出现；用项目名 + 子目录（`build/`、`recovered/`、`analysis/`、`screenshots/`）。
4. **工具会自动归置**：裸文件名或落在根目录的输出会被改写进默认项目，返回体出现 `organized:{from,to,projectDir,reason}`。**看到 `organized` 就说明你原本写错了位置**；下次直接传项目目录，而不是反复依赖自动归置。
5. **显式关闭**：确实需要关闭自动归置时传 `organize:false`（例如一次性只读/临时命令）；不要用它绕过项目目录约定。

`project_folder` 用法：
```json
{"name":"project_folder","arguments":{"action":"state"}}
{"name":"project_folder","arguments":{"action":"create","name":"云镜分析"}}
{"name":"project_folder","arguments":{"action":"set","name":"云镜分析"}}
{"name":"project_folder","arguments":{"action":"tidy","dryRun":true}}
```

- `state`：查看操作根、当前/默认项目、已有项目；不确定放哪时先看它。
- `create`：在 `<root>/<name>/` 创建项目目录；已存在时幂等返回项目路径。
- `set`：把项目设为默认，后续裸输出/自动归置都落到它。
- `tidy`：把已有散落产物收进项目；**默认 `dryRun:true`**，先看计划，确认后传 `dryRun:false` 执行；只移动/归置，不删除源文件。

正确示例：
```json
{"name":"file_create_directory","arguments":{"path":"云镜分析/notes"}}
{"name":"file_edit_text","arguments":{"path":"云镜分析/notes/todo.txt","targetVersion":"missing","encoding":"utf-8","edits":[{"mode":"write_target","matchText":"","writeText":"hello"}]}}
{"name":"file_create_directory","arguments":{"path":"云镜分析/build"}}
{"name":"apk_build","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","overwrite":true,"sign":true,"outputName":"/storage/emulated/0/脚本/云镜分析/build/app-signed.apk"}}
{"name":"apk_job_export","arguments":{"jobId":"${JOB_ID}","outPath":"云镜分析/recovered/job-result.json"}}
```

**怎么验证**：
1. `project_folder {action:"state"}` 记录 `root/currentProject/projects`；
2. 调 `project_folder {action:"create",name:"验证项目"}` 和 `set` 后，用裸文件名/根目录路径调用一个输出工具（如 `file_edit_text`），返回应出现 `organized.from/to/projectDir/reason`；
3. 再按 `<root>/验证项目/build/out.apk` 直传项目路径，返回不应再有 `organized`；
4. `project_folder {action:"tidy"}` 应默认 `dryRun:true` 并列出计划；确认后再传 `dryRun:false`，用 `file_list` 核对文件在项目目录内。


## API 文档与代码示例（离线）

编写脚本前先用 `search_api_docs` 查接口，再用 `read_api_doc` 阅读参数、返回值、兼容范围及完整示例。两项工具读取与 App「API 文档」完全相同的离线索引，不访问网络、不占用三引擎运行槽、不执行示例。

```json
{"name":"search_api_docs","arguments":{"query":"loadResources","engine":"nodejs","limit":5}}
{"name":"read_api_doc","arguments":{"id":"上一步返回的 api-N","indexSha256":"上一步返回的 indexSha256"}}
```

- 搜索：`query` 可省略（列目录），支持多个空格分隔关键词、不区分大小写；`engine=all/rhino/quickjs/nodejs`（兼容 node/node24/rhinojs），`kind=all/guide/legacy`；`category` 必须精确使用响应 `categories` 中的一项，省略表示全部。`offset=0`、`limit=10`，limit 范围 1..20；用 `nextOffset` 继续搜索页。
- 读取：`id` 使用搜索结果，推荐同时带 `indexSha256`，App 更新后旧索引会明确报错、要求重搜，避免同一 ID 指向不同内容。返回 `contentFormat=markdown` 的 `content`，代码保留缩进、换行、中文。`offset/nextOffset/contentLength` 是 UTF-16 字符数，`maxChars=16000`、范围 128..32000；`hasMore=true` 时用 `nextOffset` 继续拼接，分页可能经过代码块中间。
- 详细条目可用 `category="API 详解与实践"` 查询。搜索结果带 `sectionTitles` / `exampleCount`；`read_api_doc` 可传 `section` 精确读取“参数”“返回值”“权限与前置条件”等目录项，省略则读全文。返回 `sections` 目录和 `examples:[{language,code}]` 原文代码数组；只在内容未分页时返回完整 examples，分段时 `examplesOmittedReason=paged_content`。只读说明可设 `includeExamples=false`，必须是 JSON 布尔值。高亮色与 HTML 装饰只供人看，MCP 始终读取纯 Markdown/原文代码。
- 每项返回 `kind/source/engines`，`supportEvidence=documentation_only`。`guide` 是现行使用手册/Node 专题，`legacy` 是传统 Rhino 原文，不能推断 QuickJS/Node 支持。真实导出/成员用 `list_engine_api` / `probe_engine_api` 探测；同名函数的行为仍需目标引擎验证。
- 文档工具是只读能力，不需要脚本执行/写入权限。示例工程仍通过 `list_samples` / `read_sample` 读取；没有搜索结果时不要凭空编造接口。
### 文档与实现不一致：先看 list_engine_api.documented（.4 实战反馈：matchTemplateMulti）

> 来源：`.4` 的 poker-recog 反馈：旧文档把 `matchTemplateMulti` 写成「仍未接入 Node24」，但 `list_engine_api` 里本来就有，真机实测 21ms 可用。以下是对账方法，不要再用旧文档反推实现不存在。

- **`documented` 的含义**：`list_engine_api {"engine":"nodejs","module":"image"}` 每项带 `documented`/`documentedItems`/`build`；`documented:false` 只表示当前离线索引没有逐成员记录（标题/指南启发式），不是运行时能力判定。`build` 告诉你索引对应版本。
- **三步实测**：
  1. `probe_engine_api {"engine":"nodejs","module":"image","name":"matchTemplateMulti"}` 看导出类型/arity；
  2. `run_script {"engine":"nodejs","path":"<示例根>/图片与颜色处理/多模板匹配(matchTemplateMulti).js","wait":true,"timeoutSeconds":60}`（示例名用 `list_samples` 查找），预期 `ok:true`、`api:Image.matchTemplateMulti`；
  3. 需要组合参数时用 `call_engine_api` 直接调用并记录耗时/返回；只有 probe 与 run_script 都明确不存在/报未实现，才写「未接入」，并附 `build` 和错误原文。
- 旧文档源（当前定位到 `docs/guides/Node24基础自动化API.md` §3.3）仍有同类『matchTemplate（多结果）…仍未接入』的过期句子（与 `.4` 反馈的 `matchTemplateMulti` 在同一段/同一批结论）；已报告维护者修源并重建索引。本页只改随包 `assets/mcp` 文档，`search_api_docs` 若读到旧句子，以上面的实测为准。


## 高级版兼容入口

| 高级版名称 | 本项目名称 | 参数与结果 |
|---|---|---|
| getUiLayout | get_ui_layout | 可选 windowId、maxNodes、maxDepth、timeoutMillis；返回 XML、nodeCount、节点属性、窗口和诊断 |
| captureScreen | capture_screen | 可选 format=png/jpg、quality=1..100、dataUri、maxWidth、timeoutMillis；返回 MCP image 和尺寸信息 |
| checkSyntax | check_syntax | code 必填；可选 engine=rhino/quickjs/nodejs、name；只编译，返回 valid、errors、executed=false |
| runScript | run_script | path/file/source 三选一；可选 engine、wait、timeoutSeconds、name、workingDirectory、config |
| learnAutoJsSkill | learn_autojs_skill | 返回本说明 |
| stopAllScripts | stop_all_scripts | 停止整个应用的脚本，包括非 MCP 启动的脚本 |
| getMcpLog | get_mcp_log | 可选 maxChars（默认 20000，最大 200000）、executionId、channel=script/service/all |

两套名称调用同一实现。客户端应从 tools/list 读取实际参数定义。JSON 参数中的布尔和数字要使用原生类型，不要传字符串 "true" 或 "100"。

## 权限与观察

手机端开启 MCP 服务后即可读写与运行；当前 dev 通道免令牌，客户端使用手机显示的 MCP URL。设备操作仍需要 Android 对应权限。

节点读取还需要启用 AI.js Pro 无障碍服务。默认选择当前应用窗口，避免把输入法或悬浮菜单当成目标；指定 windowId 可切换窗口。空节点时检查 success/readable/status/diagnostics，再使用截图确认页面。不能假设空树表示画面为空。

直接截图使用 Android 11+ 的无障碍截图通道，不占用脚本的 MediaProjection 会话。无障碍未开启或页面受系统保护时会返回失败原因。旧系统可通过脚本 requestScreenCapture()/captureScreen() 使用录屏权限。maxWidth=0 默认保留原图尺寸；缩小截图时按 sourceWidth/sourceHeight/scale 换算触摸坐标。

节点树默认最多 3000 个节点、64 层、3000ms；参数上限分别为 10000、128、15000ms。truncated=true 时不能认为已读完整页面。

## 无障碍 UI 自动化（无 root）

需要无障碍服务。以下工具操作当前活动窗口，不会 root、不会执行 shell 命令：

| 工具 | 作用 |
|---|---|
| ui_find | 按 text/resourceId/contentDesc/className/bounds 或 x/y 查找节点，返回 matches（含中心坐标） |
| ui_click | 点击匹配节点；节点不可点击时尝试可点击祖先；也支持只给 x/y |
| ui_long_click | 长按匹配节点或坐标 |
| ui_node_action | 对匹配节点执行通用 UiObject 动作：copy/cut/paste/select/collapse/expand/dismiss/show/scroll*/contextClick/focus/accessibilityFocus/setSelection/scrollTo/setProgress；args 为对象 |
| ui_input | 向匹配的 editable 节点写入 text |
| ui_swipe | 按像素坐标滑动 |
| ui_global | back / home / recents / notifications |
| ui_wait | 阻塞等待匹配节点出现（上限 15s） |
| ui_scroll | 按 up/down/left/right 执行滑动手势 |

选择器示例：

```json
{"name":"ui_find","arguments":{"text":"安装","limit":10}}
{"name":"ui_click","arguments":{"text":"安装"}}
{"name":"ui_input","arguments":{"resourceId":"com.example:id/input","text":"你好"}}
{"name":"ui_node_action","arguments":{"text":"推荐","action":"scrollDown"}}
{"name":"ui_node_action","arguments":{"text":"输入框","action":"setSelection","args":{"start":0,"end":2}}}
{"name":"ui_swipe","arguments":{"x1":540,"y1":1800,"x2":540,"y2":600,"durationMillis":300}}
{"name":"ui_global","arguments":{"action":"back"}}
```

节点匹配是精确匹配（text/contentDesc/className；className 也接受简单名）；坐标以屏幕像素计。动作失败会返回 success=false 和原因，不要把它当成功。仅遍历当前活动窗口，不覆盖其它窗口/输入法。无障碍截图仍用 capture_screen；先截图+ui_find，再点击，坐标按 sourceWidth/scale 换算。
## Node floating_window 坐标系与根布局（.4 实战反馈）

> 来源：`.4` poker-recog 反馈：「浮窗坐标系目前完全没有文档，建议写清『根 View 的 layout_* 会被 WindowManager 无视、必须 match_parent + 子 View 固定 px』这类已经验证过的结论」。下面按已验证结论写成可执行步骤。
>
> 本节只讲 **Node** 的 `floating_window`；Rhino/QuickJS 用 `floaty`，API、坐标参数和根布局规则不是同一套，不要互相套用。

### 坐标系与换算

- `floating_window` 的 `setPosition(x,y)` / `setSize(w,h)` / `getPosition()` / `getSize()` 使用 **WindowManager 窗口坐标（Android px）**；不是截图像素、不是 dp。
- 同一台设备可能有 `panel`（物理面板）与 `appDisplay`/`windowBounds` 差异（挖孔、虚拟显示、缩放）。先量：

```json
{"name":"coordinate_convert","arguments":{"action":"state","measureScreenshot":true}}
```

返回里的 `spaces.panel`、`spaces.appDisplay`、`spaces.windowBounds`、`spaces.screenshot` 与 `affine` 要先核对。两者不一致时用两点 `calibrate`，不要把截图坐标直接当窗口坐标：

```json
{"name":"coordinate_convert","arguments":{"action":"convert","from":"window","to":"screenshot","point":[100,200]}}
```

### 根布局（已验证结论）

- **根 View 的 `layout_width`/`layout_height`/其它 `layout_*` 会被 WindowManager 的窗口参数覆盖/无视**；在根上写 `w="400"`、`h="200"` 不能决定窗口大小。
- 根必须 `match_parent` + `match_parent`；窗口大小用 `setSize(w,h)` 或 `createWindow({initialSize:{width,height}})` 控制。
- 子 View 用固定 px（或按 density 换算出的 px）；`match_parent` 只用于根，子控件不要依赖根的 `layout_*` 撑开。
- 位置/尺寸改变后用 `getPosition()`/`getSize()` 记录真实值；需要截图对位时用 `coordinate_convert` 换算，不要用截图缩放比反推窗口位置。

```js
const fw = require("floating_window");
if (!fw.canDrawOverlays()) { fw.manageDrawOverlays(); throw new Error("请先授权悬浮窗"); }
const w = fw.createWindow({initialPosition:{x:100,y:200}, touchable:true});
w.setViewFromXml(
  '<frame w="match_parent" h="match_parent">' +
  '<vertical w="360" h="120"><text id="t" text="demo"/></vertical>' +
  '</frame>'
);
w.show();
w.setSize(360, 120); // 窗口大小由 setSize/initialSize 控制；不要依赖根 layout_*
console.log(JSON.stringify({position:w.getPosition(), size:w.getSize()}));
```

### 常见坑与验证

| 症状 | 根因 | 正确做法 |
|---|---|---|
| 根写 `w="400"` 但窗口仍全屏/0 尺寸 | WindowManager 覆盖根 `layout_*` | 根 `match_parent`，用 `setSize`/`initialSize` 定窗口大小，子 View 定 px |
| 点击位置偏左/偏上 | 用截图坐标直接 `setPosition`（两者可能不同空间） | 先 `coordinate_convert state`；不一致就 `calibrate`，再 `convert` |
| `setPosition` 后 `getPosition` 对但画面没动 | 窗口还没 `show()`，或宿主的窗口参数未更新 | 先 `show()` 再 `setPosition`；重新 `getPosition/getSize` 并截图复核 |
| 子 View 挤在一起/被裁 | 子 View 用了 `match_parent` 或 dp/px 混用 | 子 View 固定 px；dp 先按 density 换算；根只负责窗口 |

**怎么验证**（`.4` dev-1359 已返回过同样的 hint）：
1. 先调 `coordinate_convert{action:"state",measureScreenshot:true}`，记录 `windowBounds` 与 `screenshot`；
2. 运行上面的脚本，创建 360×120 窗口在 (100,200)，记录 `getPosition/getSize`；
3. `capture_screen` 后对 (100,200) 调 `coordinate_convert{action:"convert",from:"window",to:"screenshot",point:[100,200]}`，确认窗口左上角与截图位置一致；
4. 再把根改成固定 `w/h` 重复一次；若窗口大小仍由 `setSize` 决定（而不是根属性），说明已验证结论成立；若相反，说明宿主实现变了，记录版本后报告。


## dev-1294 后的节点 API 变化（MCP 相关）

以下变化属于脚本侧 Node API（`require('accessibility')`）。MCP 现有 `ui_find` / `ui_click` / `ui_node_action` / `ui_input` 只覆盖固定定位子集，**没有**直接暴露分页/快照/身份字段；要用这些能力，请用 `run_script`（engine=nodejs）在脚本里调用，或先 `search_api_docs` / `read_api_doc` 查参数再运行。

### 快照完整性与分页（R05/R06）

| 调用 | 返回 / 行为 |
| --- | --- |
| `accessibility.snapshotInfo()` | `{nodes, complete, truncated, firstMissingPath, generation}`；`complete=false` 时 `firstMissingPath` 指出首个缺失节点路径 |
| `accessibility.treePage(limit[, cursor][, {signal}])` | 整棵树稳定分页；返回 `{nodes, complete, truncated, snapshotComplete, firstMissingPath, generation, seen, cursor}`，`complete=true` 时 `cursor=null` |
| `select(spec).findPage(limit[, cursor][, {signal}])` | 同一快照内的查询结果分页；字段与 `treePage` 一致 |

- **游标绑定代次**：把上一页返回的 `cursor` 原样传回续页。树内容或查询条件发生变化（代次不同）时明确报“分页游标已过期”，不会把两页拼成看似完整的树；此时从第一页重新开始。
- **取消**：`signal.aborted` 为真时抛“已取消”，不是静默返回空页。
- **截断如实报告**：宿主单次遍历预算未变（约 200 KiB JSON / 500 节点 / 5 秒 / 5000 次访问），被截断时只设置 `truncated/snapshotComplete/firstMissingPath`，**不返回被截断的节点**。`complete=true` 才代表本次快照完整；服务端 cursor 分页仍未实现，客户端只能如实报告截断。
- **BFS 只对完整快照生效**：`algorithm("BFS")` 在完整快照上按真实层序执行；快照被截断时明确拒绝，不拿截断的 DFS 结果排序冒充 BFS。

### 跨布局身份与过期（R07）

- 节点新增 `token`（宿主身份串）、`generation`（快照代次）、`isStale()`（是否已过期）。
- 插入/删除/列表复用后，同一 `path` 换成别的节点时 `isStale()=true`，动作返回 `false`，**不做坐标兜底**。
- `index` / `findOnce(index)` 仍是 `0..255`；更大位置用 `path` + 身份条件重新定位。

### Java 反射可变参数（R01）

- `Class.getMethod(name)`（省略空参数数组）现在可用，与 `getMethod(name, [])` 行为一致；零个/一个/多个 varargs 均支持，基本类型按组件类型收窄，固定参数重载优先于 varargs。
- **#14 反射示例（Rhino，dev-1374 起已修）**：Rhino **没有全局 `System`**（写 `java.lang.System`）；`Math.abs` 是 JS 函数，不是 `java.lang.reflect.Method`，所以 `Math.abs.invoke` 不是有效反射。必须从 `java.lang.Class.forName("java.lang.Math").getDeclaredMethods()` 里取 `abs(int)`，再 `.invoke(null, 5)`。dev-1374 起按目标形参收窄，直接传 Number 返回 `5`；dev-1373 及更早才需要 `new java.lang.Integer(5)` 包装。

**#14 可跑示例（Rhino）**：
```js
var methods = java.lang.Class.forName("java.lang.Math").getDeclaredMethods();
var abs = null;
for (var i = 0; i < methods.length; i++) {
  var m = methods[i];
  if (m.getName() === "abs" && m.getParameterTypes().length === 1 &&
      String(m.getParameterTypes()[0].getName()) === "int") { abs = m; break; }
}
if (!abs) throw new Error("Math.abs(int) not found");
var result = abs.invoke(null, 5);   // dev-1374+ 预期 5
console.log(result);
```

### QuickJS 的 ui.inflate（R04）与边界

- QuickJS 公开 `ui.inflate(xml, parent?, attachToParent?)`，返回真实 Android View 代理；`$ui.inflate` 仍未暴露（C++ 白名单 Proxy，需改 `quickjs_jni.cpp` 并重编 native）。
- Node 的 `ui.inflate` 仍**只接受 XML 字符串**，传布局资源 ID 会报“找不到布局”；返回的 View 未挂到父容器时 `getParent()` 为 null，是否属于真实视图树依产物路径而异（设备实测见 `docs/guides/API_REFERENCE.md` 的 Node UI 已知问题表）。
- 完整参数、引擎差异与设备实测见 `docs/guides/Node24控件选择器API.md`「dev-1294 新增」与 `docs/guides/API_REFERENCE.md`。

## 应用管理（无 root）

`app_list`、`app_info`、`app_launch`、`app_open_settings`、`app_open_url` 使用 PackageManager/Intent，无需 root。

`apk_install` 只打开系统 APK 安装器（Android 8+ 可能先要求允许安装未知应用），`apk_uninstall` 只打开系统卸载确认页；两者都必须由用户确认，**不会静默安装/卸载，也不报告“安装成功”**。返回里的 `requiresUserConfirmation=true`、`silentInstall=false`/`silentUninstall=false` 表示界面已打开，最终结果由系统 UI 决定。

```json
{"name":"app_list","arguments":{"query":"browser","includeSystem":true,"limit":20}}
{"name":"app_launch","arguments":{"packageName":"com.android.settings"}}
{"name":"app_open_settings","arguments":{"packageName":"com.example.app"}}
{"name":"app_open_url","arguments":{"url":"https://example.com"}}
```

### 终端里的 aijs-ui

手机终端可直接用 `/system/bin/am` 向本应用无障碍桥发命令（不需要 root；新版本 APK 才包含）：

```sh
am broadcast --user 0 -n com.jdkshen.aijspro/.mcp.AijsUiReceiver \
  -a com.jdkshen.aijspro.AIJS_UI --es command click --ei x 500 --ei y 900
```

支持 ping/status/click/tap/longClick/swipe/back/home/recents/notifications/input/settext；结果写入应用私有 `files/aijs-ui/last-result.json`，普通 am broadcast 不展示结果，可用终端读取该文件或看 logcat tag=AijsUi。receiver 不导出，只接受同 UID 显式广播。

## 检查并执行

```json
{"name":"checkSyntax","arguments":{"engine":"quickjs","code":"console.log('hello');"}}
```

```json
{"name":"runScript","arguments":{"engine":"quickjs","source":"console.log('hello'); 1 + 2;","wait":true,"timeoutSeconds":30}}
```

source 在内存中执行，不写入或覆盖真实脚本。workingDirectory 默认操作目录；文件入口默认文件所在目录，路径均限制在手机选定的 MCP 操作目录内。

config 支持：

- path：工作目录字符串；或模块搜索目录数组。
- workingDirectory：工作目录；显式字段优先于 path 字符串。
- engine：引擎；顶层 engine 优先。
- delay、interval：0..600000 毫秒。
- loopTimes：默认 1；0 表示持续循环，其他值最多 100000。
- arguments：传给 engines.myEngine().execArgv 的 JSON 对象。

```json
{"name":"run_script","arguments":{"file":"main.js","engine":"rhino","wait":true,"config":{"arguments":{"message":"hello"},"loopTimes":1}}}
```

任务结果包含 executionId、engine、status、logs，以及引擎提供的 result 或失败时的 error。engine 表示实际执行引擎。wait 超时返回 timedOut=true，任务仍可能在运行；用 wait_execution/get_execution 继续查询，或用 stop_script 停止指定任务。不要把超时当成成功。

任务 logs 仅取该任务自身控制台，最多保留最近 300 条，每条最多 16 KiB。全局 read_apk_logs/getMcpLog 不指定 executionId 时会包含其他脚本输出。channel=service 读取 MCP 服务最近 30 条请求记录，channel=all 同时读取。

stopAllScripts 影响整个应用；只处理一个任务时使用 stop_script(executionId)。停止状态是 STOPPED，不属于脚本执行错误。

### 后台任务结果保留（dev-1378）

`apk_job_*` 的结果不是长期存档：输出只保留 **6 小时**（`windowMillis=21600000`）或**最多 64 条已完成任务**（`maxRetainedFinished=64`）；单结果最大 24 MiB（`maxResultBytes=25165824`），墓碑最多 256 条。超出后**删除输出缓存**并留墓碑：`apk_job_list.evicted[]` 里 `code=JOB_EVICTED`、`reason=retentionLimit`/`retentionWindow`；不是 `SERVICE_RESTARTED`。

- **`.4` 实测真因**：升级后看不到结果，实际是 64 条 finished + 3 条活跃触发了 `retentionLimit`（`apk_job_list` 返回 32 条墓碑），与升级清空无关；升级清空只会出现 `SERVICE_RESTARTED`。先看 `retention` 和 `evicted[]`，不要臆断。
- **正确做法**：任务一到终态就立即 `apk_job_result` 把 `content` 取回，写入自己的项目目录（如 `<项目>/recovered/`），不要等几个小时或升级后再找；Playbook 的留档约定也点名这条。
- **原样留档用 `apk_job_export`**：`apk_job_export {jobId,outPath}` 把结果原样原子导出到工具操作目录（outPath 父目录会自动创建；省略 offset/maxChars 时导出全部，显式 maxChars ≤1 MiB；同名文件覆盖）。导出后内容归用户，但**不会延长任务缓存的 6 小时/64 条保留**，所以仍要尽早导出。
- `apk_job_result` 默认读取窗口 **1,048,576 字符**（dev-1378；旧版 16,384），大结果一次可拿回；响应里 `hasMore`/`nextOffset`/`totalChars`/`defaultMaxChars` 仍按分页语义。显式传 `maxChars` 时 schema 上限已是 **1 MiB**（dev-1380）；但 `apk_decompile_*` 是另一套：默认/上限 **200000**，仍会在 200000 截断（详见下方 B11），大结果用 `apk_job_export`/`offset` 续读。

**怎么验证**：调 `apk_job_list {}`，检查 `retention` 是否包含 `windowMillis/maxRetainedFinished/maxTombstones/maxResultBytes`；若有 `evicted[]`，确认 `code=JOB_EVICTED` 且 `reason=retentionLimit|retentionWindow`。对墓碑 jobId 调 `apk_job_result` 应报 `JOB_EVICTED`；对 `SERVICE_RESTARTED` 的 job 才说明是进程重启丢失。再跑一个 >16K 的后台结果（如反编译类），不传 `maxChars` 调 `apk_job_result`，检查 `content` 长度 >16384、`defaultMaxChars=1048576`、必要时 `hasMore/nextOffset`。


## 三种引擎与 API 调用

- engine 支持 rhino、quickjs、nodejs；node / node24 是 Node 别名。首行 `// @engine quickjs`、`// @engine rhino`、`// @engine node24` 也可选择引擎；工具的 engine 参数优先于源码首行。字符串指令如 `"nodejs";` 应独占一行。
- 无引擎参数且源码无指令时，compat 版默认 Rhino。lite 版没有 Rhino 执行引擎，应显式选 QuickJS。
- Rhino 保留 E4X/XML 布局，并应用本项目模板字符串和对象简写预处理；QuickJS 使用本项目 XML 布局转换。
- checkSyntax 不调用业务 API，不请求脚本中的权限，也不执行无限循环；语法通过不表示所用 API、控件、文件或权限都存在。
- 返回 preprocessed=true 时错误位置来自转换后源码，尤其列号可能与原代码不同。
- Node 引擎使用真实 Node24 进程池，支持 Buffer、fs、child_process、process 及本项目内置 require 模块。Rhino/QuickJS 不自动拥有 Node 模块。
- list_engine_api(engine) 返回实际全局名称，Node 同时返回模块目录；增加 module 参数可枚举 require(module) 的导出。模块可枚举不代表其全部功能已验收或设备权限齐全。
- probe_engine_api(engine,name,module?,includeInherited?) 探测成员类型、原型成员及函数 arity，不执行 getter。arity 是 JS 函数长度，不代表完整 Java 重载或参数签名。动态 Java 包/代理成员不能只靠静态枚举，使用 run_script 调用真实反射 API。
- call_engine_api(engine,name,module?,args?) 调用全局或模块函数，保持函数接收者，支持 Promise；args 是 JSON 数组，返回 JSON 结果、returnType 或 error。BigInt 以十进制文本返回。需要函数回调、持久对象句柄、连续识别、UI 或多个 API 组合时使用 run_script，在同一个真实引擎任务内完成。
- engine_api_diff 对比三引擎的全局名称，保留此前 Rhino/QuickJS 字段并增加 Node 名称和三引擎交集；同名不代表语义完全一致。
- Node check_syntax 使用与运行时一致的真实 Node CLI --check，只编译 CommonJS 源码，不注入或执行任何脚本；不接受 Node E4X/XML 字面量（应使用 XML 字符串）。
- ui.performClick(view) 用于脚本自身 UI/悬浮窗控件；目标应用自动化点击使用选择器节点 click() 或坐标 click()/press()。

```json
{"name":"call_engine_api","arguments":{"engine":"nodejs","module":"path","name":"join","args":["/sdcard","脚本","main.node.js"]}}
```

```json
{"name":"probe_engine_api","arguments":{"engine":"nodejs","module":"ui","name":"Activity.prototype","includeInherited":true}}
```

## 文件、示例与修改

- list_scripts/read_script/search_scripts/continue_result：检索操作目录。
- list_samples/read_sample：获取实际内置示例。
- workspace_open/read/write/delete/diff/request_apply：建立私有副本、修改、查看并应用；应用会校验外部修改、备份并支持手机历史回退。
- workspace_mkdir/cancel/cleanup：目录创建、请求取消和副本清理。
- list_executions/get_execution/wait_execution/stop_script：管理本 MCP 服务启动的任务。

不要把工作区写入当作已修改真实文件；必须完成 workspace_request_apply，并检查返回结果。

## 文件与 APK 工具（toolkit，全部本地实现，不依赖 MT）

这组工具由 AI.js Pro 自己实现，运行在独立的 `:tools` 进程（与主进程、脚本进程隔离），通过私有 ContentProvider + 文件 IPC 通信，不会因为解析大 APK 拖垮脚本引擎。`toolkit_status` 返回工具数、分组和真实限制；参数以 `tools/list` 的 inputSchema 为准。路径和 ZIP 条目都按不可信输入校验，拒绝 `..`、绝对越界、URI、反斜线和符号链接逃逸。

参数与预算以当前设备的 `tools/list`、`toolkit_status` 和工具返回为准。

### 文件工具

`file_get_access_policy/list/stat/read_text/read_bytes/search_text` 只读；`file_edit_text`、`file_write_bytes`、`file_patch_bytes`、`file_append_*`、`file_truncate_bytes`、`file_replace_text` 等修改接口按 schema 要求 `targetVersion`/`expectedHex` 或原字节校验，路径始终限制在操作目录。文本处理上限 10MiB，二进制写入上限更小；搜索使用 Java 正则并受节点/时间预算，结果可能标 `truncated`。

### 推荐流程：快照 → 编辑会话 → 读取/修改 → 检查 → 构建

1. `toolkit_status` 确认工具可用性与限制；`apk_list_available_apks` 找包。
2. `apk_workspace_open` 建不可变快照：`temporary=true` 为一次性工作区；已保存工作区用 `temporary=false` 打开。已有 APK 上下文可用 `path="aijs://current-apk"` 取回（没有上下文时先按实际路径打开）；保存后的工作区路径是 `aijs://workspace/<workspaceId>`。公开写法只使用 `aijs://`；旧 URI 输入和旧 `mt_*` 工具名仅作兼容，不应写进新配置。
3. `apk_session_create` 建写时复制编辑会话；之后的浏览、读取和修改都在会话视图上完成，不会改源 APK。
4. 先读取再修改：`apk_read_text`/`apk_read_bytes`/`apk_resource_read` 返回 `targetVersion`；编辑时原样传回。`apk_read_text` 的 `offset`/`nextOffset` 是 **Unicode 码点**，普通 `zip_entry:` 文本也会真正按 offset 切；续读优先用 `nextOffset`，`offset` 与 `startLine/startColumn` 二选一（同时传 offset 优先）。DEX 类/方法先用 `apk_read_text` 的 `dex_class:`/`dex_method:` 定位符读取文本，再编辑。`apk_edit_text` 新建目标用 `targetVersion="missing"` 加唯一 `write_target`（可新建 DEX 类或普通文本条目）；`write_target` 的 `matchText` 必须为空串（可省略，默认空串），其它模式必须唯一匹配。
5. 修改后调用 `apk_session_check(runBuildChecks=true)`；`apk_build` 会再检查一次，失败不导出。构建默认不签名；`sign=true` 使用本机 AI.js Pro 身份重签并移除原签名，不能沿原作者签名。
6. 保留条目保护：普通 `zip_entry:` 的 `apk_patch_bytes` 不能处理 `classes*.dex`、`resources.arsc`、二进制 AXML/Manifest；`apk_delete_target` 不能删除 `classes*.dex`/`resources.arsc`，源码包中这些保留条目被移除时 `apk_session_check` 也会失败。DEX 用 `dex_class:`/`dex_method:`，资源用 `apk_resource_*`，**AXML/Manifest 用 `apk_read_text(locator="axml:...")` + `apk_edit_text(locator="axml:...", ...)`**（不要拿 `apk_resource_*` 改 AXML）。
### 改打包版应用资源（.4 实战反馈：解包→改资源→回编→重签→安装）

> 来源：`.4` 的 MCP反馈-云镜逆向会话要求把「改打包版应用资源」写成可执行一节；如果以后有「装探针包」类工具，应把 clear + 授权 + 启动串成一步（当前没有该工具，下面是手动步骤）。

**适用**：改已打包 APK 里的字符串/布局/资源表，再覆盖安装做验证；不是改源码工程。先 `apk_component_status` 看 `apktool`/`apk-resources`，缺组件就用 `apk_component_install` 装。

**路线 A：Apktool 工程（完整解包 → 改 → 回编）**
1. 解包：
```json
{"name":"apk_decode_project","arguments":{"path":"<源 APK>","outDir":"<新工程目录>","decodeResources":true,"background":true,"timeoutSeconds":300}}
```
2. 在返回的 `PROJECT_DIR` 改 `res/values/strings.xml`、`res/layout/*.xml` 等；用 `terminal_exec` 的 `sed`/`perl`/`cp` 或 `file_*` 工具写回。不要手改 `apktool.yml`、签名文件或二进制 `resources.arsc`。
3. 回编 + 重签：
```json
{"name":"apk_build_project","arguments":{"projectDir":"<PROJECT_DIR>","outPath":"<新 APK>","sign":true,"background":true,"timeoutSeconds":300}}
```
4. 用 `apk_verify` 检查输出并记录 SHA-256；日志与组件版本一起留档。
5. 安装：**不要直接 `pm install /storage/...`**，先按下一节「安装测试包」`cp` 到 `/data/local/tmp` 再安装。
6. 启动前按需 `pm clear`（仅测试包，会清数据/运行时权限）→ `pm grant` 需要的运行时权限 → `am start -W -n <pkg>/.<Activity>`；以 `pm path`/`dumpsys package`/logcat 确认。

**路线 B：MCP 编辑会话（无 Apktool 工程，直接重打包）**
```text
apk_workspace_open → apk_session_create → apk_resource_read（拿 targetVersion）
  → apk_resource_edit（edits[] 带 locator/variant/targetVersion/valueXml）
  → apk_session_check(runBuildChecks=true) → apk_build(overwrite=true, sign=true, outputName="...")
```
- 只改 `resources.arsc` 资源值时用这条；`apk_resource_edit` 的 `edits[]` 必须带 `locator`/`variant`/`targetVersion`/`valueXml`，不要用普通 `zip_entry:` 改 `resources.arsc`。**AXML/Manifest 不走 `apk_resource_*`：先 `apk_read_text(locator="axml:...")` 拿 targetVersion，再 `apk_edit_text(locator="axml:...", ...)`；或 `apk_decode_project` → `file_edit_text` → `apk_build_project`。**
- `apk_build` 默认不签名；`sign=true` 用本机 AI.js Pro 身份并移除原签名，**不能沿用原作者签名**。覆盖安装要求同证书，否则报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；测试包可先卸载（会丢数据）。
- 回编/构建失败时保留 stderr、组件版本和实际输出路径；不要把 `apk_build_project` 的结构检查通过当成业务可启动。


**怎么验证**：
- 构建：`apk_verify` 检查输出并记录 SHA-256；`apk_build_project`/`apk_build` 的 `signed`/`signer`/`schemes` 与日志一起留档。
- 安装：`pm path <pkg>` + `dumpsys package <pkg> | grep versionName` 与本次构建版本一致；启动用 `am start -W -n ...` 看 `Status`/`LaunchState`，`logcat` 无新 FATAL。
- 资源：安装后重新 `apk_workspace_open` 快照，或直接看 UI/截图确认改后的字符串/布局出现；同时用 `apk_resource_read` 读回对应 `variant`/值，确认不是只改了本地工程目录。
- 覆盖安装失败（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）先核对签名是否同证书；不同证书只能卸载旧包（会丢数据）或重新用同证书签名，不要把未安装成功写成通过。


### 搜索与定位

`apk_search` 的 `target` 覆盖：`overview`（ZIP 路径、解码后的 AXML 文本、资源名/值、DEX 类/字段/方法/字符串）、`files`（ZIP 路径及 AXML 文本）、`resource_table_names/values/file_paths`（`file_paths` 单独使用）、`dex_names`（类名、字段/方法；`prefix` 可用 Java 包名前缀）、`dex_strings`（字段常量、注解和指令字符串）、`dex_string_members`、`smali`（完整 Smali 类）、`native_symbols/native_strings`（仅 native 目标扫描 .so）。

- `queryType=literal|regex`；正则使用 Java `java.util.regex`，支持环视/反向引用。`prefix` 按目标类型过滤（DEX 可用 Java 包名，资源可用名称或 0x ID，ZIP/native 用路径；`overview` 时必须为空）；`matchMode=literal|literalWord|regex|contains|exact`（`literal`=子串，默认；`literalWord`=按词/标识符边界，搜 `frida` 不会命中 `FRIDAY/friday/blackfriday`；`regex`=Java 正则；`contains/exact` 为兼容旧值），`smali` 只支持 contains。`caseSensitive=false` 时是**子串**匹配，要按标识符匹配请显式用 `literalWord`；返回 `matchModeUsed/caseSensitiveUsed/matchModeHint` 自证匹配方式。`overview` 现在**真执行**：对每个 target 跑小预算并聚合，返回 `searchedTargets/skippedTargets/partialTargets/targetHitCounts/overviewLimits`，不要因为 `total=0` 就当成没搜。
- `snippetMaxChars` 默认 240，设 0 不返回 snippet；`includeMatchOffsets=true` 返回 Java UTF-16 `start/end` 偏移和 `matchOffset`，不是 Unicode 码点偏移。结果同时放在 `items` 和 `hits`，并带 `nextCursor`/`hasMore`。
- 扫描是预算内的部分搜索：`truncated=true` 或出现 `notSearchedTargets`/`skippedScopes` 时不能当作“没有匹配”或全量结果；缩小 `prefix` 或目标后重试。

### 正则预算（Java）

`apk_search`、`file_search_text`、`file_replace_text` 使用 Java 正则语义（lookahead/lookbehind、反向引用、内联 flags 等可用）。每个 matcher 默认 750ms（实现上限 5000ms），并限制 8,000,000 次字符访问；同时检查线程中断和栈溢出。文件搜索另有 3 秒总预算，APK 搜索另有 8 秒总预算。超预算时，搜索返回部分结果并标 `truncated`、`stopReason` 或跳过范围；不能返回部分结果的操作报 `REGEX_BUDGET_EXCEEDED`。先加 `prefix` 缩小候选。

### ZIP：AES/ZipCrypto 与预算

`zip_list/read_text/read_bytes/test/extract/create/update` 都支持未加密条目；读取支持 **ZipCrypto 和 AES**（AES 条目必须有合法 0x9901 extra，压缩方式只能是 Stored/Deflate），**加密条目**才必须传非空 `password`；`password` 是可选项，未加密条目省略或传空字符串皆可（dev-1368 起不再强制）。写入只生成 **AES-256** 或明文：`zip_create`/`zip_update` 的非空 `password` 使新增/替换文件条目用 AES-256，目录条目不加密；不新写 ZipCrypto。`zip_update` 对未改/重命名条目保留原始压缩/加密字节与密码，失败保留原档，且不做 APK 签名。

- `zip_read_text`：每页 1..2000 行、2..131072 码点；`startLine/startColumn` 从 0 开始，续页沿用返回的 `encoding`。预览不校验整个条目，完整性用 `zip_test`。
- `zip_read_bytes`：每页 1..65536 字节，`byteOffset` 是未压缩偏移；同样只是预览。
- `zip_test`：完整读取每个文件条目并核对长度/CRC；AES 额外校验 HMAC，ZipCrypto 校验密码与 CRC。预算为 50,000 条目/512MiB；预算不足或中断时报告未完成，绝不当作通过。
- `zip_extract`：显式 `entryPath→destinationPath` 映射；拒绝绝对路径、`..`、反斜线、重复/冲突目标、符号链接和不安全覆盖，`overwrite=true` 才能替换普通文件。解压前检查 50,000 条目、512MiB 声明总量和 250:1 压缩比（小文件按 256KiB 阈值判断）。
- `zip_create`：Stored/Deflate，level 0..9；输出不能位于源目录内或同时作为输入；最多 50,000 条目，写入/源数据预算 2GiB。
- `zip_update`：操作无序且不能形成链、交换或竞争目标；`targetVersion` 可选，传入时先校验，省略时也在写入期间检查源未变化。
- `zip_list`：每页 1..1000；`listing` 保留旧字符串行（“压缩后 原始 加密 路径”），新增同页同序的 `items[]`（name/path/relativePath/fullPath/isDirectory/size/compressedSize/encrypted/method/crc32 等），推荐按 `items` 解析；分页游标 30 分钟过期且绑定 ZIP 版本，源变化后必须从空 cursor 重新列出。

### 签名：只读元数据，不是验签

`apk_read_signature` 读取 V1/V2/V3/V3.1 方案和证书元数据：方案、证书 SHA-256/MD5/SHA-1、算法 OID/名称、subject/issuer、序列号和有效期、`hasDifferentCertificates` 等，`verified` 始终为 false。工作区打开摘要只给 `available/signaturePresent/schemes/certificateCount`；构建结果中的 `signatureSummary` 同样是只读元数据。只读取签名块/证书，不做密码学验签、证书链信任、内容摘要核对，也不保证 APK 可安装、可升级或未被篡改。解析失败时 `available=false`，并可能在 `warnings` 中给出原因。

### 资源读取：逐项预算、默认配置与 XML

`apk_resource_read` 一次 1..200 项，`variant` 省略或传 `default` 表示默认配置；精确配置名不存在返回 `RESOURCE_VARIANT_NOT_FOUND`。默认 `maxValueChars=4096`（1..32768）、`maxValueXmlChars=32768`（1..1048576）、`maxItemsPerValue=50`（0..2000）、`resolveDepth=0`（0..1）。

- 每项独立返回；一个 locator 失败只写该项的 `index/locator/variant/errorCode/message`，不丢弃同批其他结果；整批输出上限 8MiB。
- `value` 按码点截断；`valueXml` 超预算时返回 `null`，并给 `valueXmlTruncated/valueXmlComplete/valueXmlCharCount`，`editableReason` 会标 `VALUE_XML_TRUNCATED`；不要拿半截 XML 写回。复杂资源另给 `itemCount/itemsTruncated/items`。
- `resolveDepth=1` 仅解析当前项直接引用的第一级目标（优先同配置，其次默认配置），不递归展开；引用目标也受同一预算限制。
- 写入/编辑 AXML/资源 XML 时会拒绝 DOCTYPE/ENTITY；XML 文本上限 10MiB；解码结果把数字字符引用还原为字符，命名实体保持原样。

### ELF 指令分析与补丁

`apk_native_inspect/list_items/map_address/xref/disassemble/analyze_control_flow` 支持 32/64 位 ELF。原生库可用时用本机 Capstone 5.0.6 解码 **AArch64、ARM/Thumb、x86、x86-64**；原生库不可用时只回退有界 AArch64 小端常见指令子集。未知字节明确标 `decoded=false`；xref 只覆盖直接静态引用和可静态解析的 REL/RELA 槽，CFG 只覆盖有界直接控制流，不保证完整调用图、数据流或语义等价。

- `apk_native_patch_instructions` 支持 **AArch64、ARM/Thumb、x86、x86-64** 的小端等长补丁。内置汇编器只覆盖 AArch64 常见指令；其他架构或内置不覆盖的形式需要可选外部 clang（应用终端运行 `aijs-toolchain install cpp`，不是电脑端工具）。缺后端会明确报错，不会静默改成其他架构。
- 指令补丁先用 `expectedHex` 校验原字节，汇编结果不得超过原窗口；`paddingMode=none|nop`，最多 256 字节窗口，Thumb/x86 边界校验依赖 Capstone。只保证局部等长替换，不保证栈平衡、寄存器约定、重定位或函数语义。
- `apk_native_patch_string` 只处理严格 UTF-8 字符串：默认等长；`null_pad`/`space_pad` 只能利用目标位置已有的 NUL/空格 padding 增长，窗口最大 4096 字节；不调整条目大小、长度字段、指针或重定位。

### 分页与陈旧保护

- 分页上限按类型区分：APK列表/DEX大纲1000项、native列表500项，其余列表200项。以 `pagination.limitMax` 为准；有更多时返回 `nextCursor` 和 `nextActions`，用 `apk_continue` 续页。**例外**：`apk_dex_list_classes`/`apk_dex_list_methods` 的 `nextCursor` 属于原工具，回传原工具的 `cursor`（同过滤条件、同 workspace/session/revision），不能传给 `apk_continue`。
- 分页游标绑定 `workspaceId`、`editSessionId` 和会话 revision，5 分钟过期；编辑、删除、重建会话或超时后必须重新查询，不能把两页拼成一份视图。
- `apk_edit_text`/`apk_resource_edit` 使用 `targetVersion` 防止旧读覆盖；`apk_patch_bytes` 用逐段 `expectedHex`；`apk_native_patch_*` 用 `expectedHex`/`expectedText`。看到“内容已改变”时重新读取，不要重试旧参数。
- `apk_read_text` 的普通文本源超过 10MiB 时只返回前缀并标 `editable=false`/`sourceTruncated=true`；此时不要写回，改用分页或字节接口。
- 构建时保留每个条目的 Stored/Deflate 方式，移除 `META-INF` 旧签名文件；Stored 的 `.so` 按 16KiB 对齐、Stored 的 `resources.arsc` 按 4 字节对齐。`apk_session_check` 会检查 Manifest、不能删除的 `classes*.dex`/`resources.arsc` 以及变更的 DEX/ARSC/ELF/AXML/Manifest 结构，但不保证业务行为、安装性或原签名兼容。

### 容量上限

APK 快照单包 512MiB、工作区总量 1GiB、最多 16 个工作区；每个工作区最多 8 个编辑会话，单会话最多 2000 个变更条目/512MiB；单条目分析上限 64MiB，普通文本 10MiB，大条目用 `apk_read_bytes` 分页；分页结果最多 80,000 项、16 个游标；工具响应上限 24MiB。旧 `mt_*` 名称只是 dispatch 兼容别名，不额外注册为规范工具。

## 手机终端与编译

MCP 和应用内终端复用同一底层，默认使用手机应用 UID，无需占用三引擎运行槽。

> B13：执行前先 `shell_capabilities` 自检；`terminal_exec` 无 PTY，grep/cat/归档/python 有缺口，详见本节末「dev-1380 手册补丁」B13。`terminal_exec` 可显式选择 `backend="shizuku"`；Shizuku/Sui须已运行并授权，失败不会回退 app。后端实际身份可能为shell或root，诊断以真实状态为准。仍是管道终端（`pty=false`）；全屏编辑器、终端控制序列、交互PTY和完整作业控制不属于本接口。workingDirectory须在MCP操作目录内，命令可访问所选身份实际有权限的文件。

1. `get_build_tools` 检测 PATH 中 clang/clang++、javac/java、Gradle、aapt2、zipalign、apksigner 等；`found=true` 仅表示定位到命令，`verified=false` 表示尚未试编译。`optionalComponents` 另返回可选 C/C++ 组件的状态与安装命令；此查询不会下载或执行编译器。
2. `terminal_exec(command, workingDirectory?, env?, timeoutSeconds?, backend?)` 异步创建任务，立即返回jobId；backend只接受app（默认）/shizuku。env是字符串对象，不能伪造内部后端变量或替换RISH_APPLICATION_ID。最多3个并发、保留最近16条；超时默认300秒，范围1..3600。
3. `terminal_read(jobId, offset?, maxChars?, waitMillis?)` 按上次 nextOffset 继续读合并后的 stdout/stderr。offset/nextOffset 是 UTF-16 字符偏移，默认 0；maxChars 默认 16384、最大 65536；waitMillis 为 0..10000，仅控制等待输出，不会停止任务。每任务保留最近 256Ki 字符；truncated=true 时有旧输出被淘汰，可将完整编译日志重定向到项目文件。
4. `terminal_write(jobId,input?,eof?)` 排队写 stdin，不自动补换行；eof=true 关闭输入。accepted 只表示入队，读取结果中的 inputError/pendingInput 确认是否写入。
5. `terminal_stop(jobId)` 只停止该命令及同进程组子进程，`terminal_list` 查询本 MCP 所有终端任务。MCP 服务停止会清理自己的终端，应用界面的终端由界面生命周期独立管理。
6. `shell_rish(command, workingDirectory?, env?, timeoutSeconds?)` 是同一Shizuku任务后端的兼容工具，**异步返回jobId**，不是同步code/result/error；用terminal_read/write/stop继续管理。停止Shizuku任务由远端执行服务回收所属进程组。

状态：STARTING / RUNNING / SUCCEEDED / FAILED / STOPPED / TIMED_OUT。停止后 processActive 可能短暂仍为 true，应继续读取直到清理完成。判断编译成功需状态 SUCCEEDED、exitCode=0，并检查实际产物；服务/应用进程重启后任务记录不保留。后台守护进程自行 setsid 脱离进程组不受本接口管理，不要用于启动守护服务。

```json
{"name":"terminal_exec","arguments":{"command":"pwd; ls -la","timeoutSeconds":30}}
```

```json
{"name":"terminal_read","arguments":{"jobId":"terminal-返回的ID","offset":0,"waitMillis":1000}}
```

```json
{"name":"terminal_exec","arguments":{"command":"id","backend":"shizuku","timeoutSeconds":30}}
```

app后端及内置终端可直接使用`rish -c 'id'`、`rish "id"`、`i "/路径/有 空格.apk"`。这是APK内置的命令模式兼容入口，不是官方交互PTY rish；无外置DEX或~/r脚本依赖。`i`通过安装会话流式写入单个或多个split APK，支持私有文件；默认覆盖安装、使用调用应用所在用户，不默认授予全部权限。支持显式-r/-d/-t/-g/--user；文件名前以`--`结束选项。Node子进程和脚本普通shell不自动注入终端函数，脚本使用`shizuku.shell`。

三引擎全局`shizuku`提供isAvailable/hasPermission/getState/requestPermission/shell；Node也可`require('shizuku')`。Node授权/命令返回Promise，Rhino/QuickJS在脚本线程同步返回；shell参数为`command,{timeout,maxOutput}`，结果`{code,result,error}`保留非零退出时stdout。先用search_api_docs查询“Shizuku”。

内置已有 D8 和 Smali，通过独立 app_process 执行，编译器 CLI 的退出不会退出宿主。环境变量 AIJS_APK 是当前 APK 路径，AIJS_BUILD_MAIN 是编译入口：

```sh
# JAR/class → DEX；D8 需 Android 8.0/API26+。Java 源码必须先由 javac/ECJ 编译成 class。
mkdir -p out && CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" d8 --min-api 26 --output out input.jar
# Smali → DEX
CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" smali assemble src -o classes.dex
```

终端本身不等于完整工具链。主 APK 没有预装 clang、JDK/javac、完整 Android SDK/NDK/Gradle；C/C++、Java 源码、完整 Android 项目需另行配置手机 ABI/Android 兼容工具链。电脑 SDK/NDK 的 Windows/Linux 主机可执行文件不能直接在手机运行，`./gradlew assembleDebug` 也要求兼容 Java 和资源编译器。Android 共享存储通常不能执行 ELF；普通应用还受到可执行文件存储和 SELinux 规则限制。大型工具链应走可选插件，不往主 APK 塞整套 SDK。
### 安装测试包：pm install 不能直接读 /storage（.4 实战反馈 FUSE 坑）

> 来源：`.4` 云镜会话/安装测试要求：FUSE 路径喂 install 会失败；先拷到 `/data/local/tmp` 再 `pm install`。`.4` 的 Android 16/17 也要求这样处理。

- 症状：`pm install -r /storage/emulated/0/脚本/xxx.apk` 读取失败或 `No packages staged`；`/sdcard`/FUSE 路径可能被 SELinux 拒读，`pm install-write` 不能直接拿 FUSE 路径当源。
- 正确：用 **shizuku/root shell**（`terminal_exec backend="shizuku"` 或 `rish`）先 `cp` 到 `/data/local/tmp`，再安装。**app UID 不能写/列 `/data/local/tmp`**，不要用默认 app backend 做这两步。
```sh
cp /storage/emulated/0/脚本/build/app-signed.apk /data/local/tmp/app-signed.apk
chmod 644 /data/local/tmp/app-signed.apk
pm install -r /data/local/tmp/app-signed.apk
```
PC 侧等价：`adb push <apk> /data/local/tmp/app-signed.apk`，再 `adb shell pm install -r /data/local/tmp/app-signed.apk`。

- 不确定当前后端能做什么时先调 `shell_capabilities`：app 与 shizuku 的 `cp`/`pm` 能力不同，不要假设 app backend 能 `pm install`。
- 安装后按测试需要清数据/授权/启动（仅测试包；`pm clear` 会清数据，运行时权限通常也要重新授予）：
```sh
pm clear com.example.pkg
pm grant com.example.pkg android.permission.READ_EXTERNAL_STORAGE  # 按实际声明/需求
am start -W -n com.example.pkg/.MainActivity
```
- 验证：`pm path com.example.pkg`、`dumpsys package com.example.pkg | grep versionName`、`am start -W` 的 Status/LaunchState、`logcat` 无新 FATAL；覆盖安装失败先核对签名是否相同。
- 如果以后做「装探针包」工具，建议把 `cp`→`pm install`→`pm clear`→按需 `pm grant`→`am start` 串成一步，并在返回里给实际包名/版本/启动结果；当前没有该工具，不要假装有。


### C/C++ 按需下载组件

新增 `aijs-toolchain` 函数只出现在 App 终端/MCP 的 **app 后端**。主 APK 只含管理入口；编译器 ZIP 及头文件独立下载到应用 no_backup 私有目录，不会增加主 APK 的编译器载荷。安装可停止，进度按终端输出读取；失败保留原先激活的版本。使用已核验的组件清单，下载完成验证 SHA-256/大小，再解压和切换版本。当前基础组件为 ARM64、Android 9/API28+ 主机，目标为 Android API26+ ARM64；不含跨 ABI、CMake/Ninja、JDK、Gradle 或完整 APK 构建环境。

dev-1307起默认使用公网加速源，网络失败依次尝试备用加速源和GitHub直连，手机不需要连接开发电脑。显式`--manifest`仍支持LAN/自托管，不替换用户指定源。dev-1306也能使用下方公网完整地址，但不支持省略`--manifest`。

```json
{"name":"terminal_exec","arguments":{"backend":"app","command":"aijs-toolchain install cpp","timeoutSeconds":1800}}
```

返回 jobId 后持续 `terminal_read(offset=nextOffset)`，直到 processActive=false，且 status=SUCCEEDED、exitCode=0；不能把开始下载当成安装成功。之后新命令自动有 clang/clang++/llvm-ar，`get_build_tools.optionalComponents` 可读取安装状态。一个任务内 `install ... && clang --version` 也会刷新 PATH。

```sh
aijs-toolchain status
# 公网默认安装（dev-1307+）
aijs-toolchain install cpp
# 公网完整地址，dev-1306也可用；网络不通时可换另一条
aijs-toolchain install cpp --manifest https://gh-proxy.org/https://github.com/Jdkshen/ai.js-pro/releases/download/toolchains-cpp-r29-v1/cpp-arm64-v1.json
# 备用加速：https://gh-proxy.com/https://github.com/Jdkshen/ai.js-pro/releases/download/toolchains-cpp-r29-v1/cpp-arm64-v1.json
# GitHub直连：https://github.com/Jdkshen/ai.js-pro/releases/download/toolchains-cpp-r29-v1/cpp-arm64-v1.json
# 局域网自定义：aijs-toolchain install cpp --manifest http://192.168.10.7:8080/toolchains/cpp-arm64-v1.json
clang --version
mkdir -p "$HOME/cpp-work"
cd "$HOME/cpp-work"
printf '#include <stdio.h>\nint main(){puts("Hello C");return 0;}\n' > main.c
clang main.c -O2 -o hello
./hello
# C++：clang++ main.cpp -std=c++17 -O2 -o hello-cxx
# 共享库：clang -shared -fPIC main.c -Wl,-z,max-page-size=16384 -o libdemo.so
```

编译不需要 Shizuku；Shizuku 身份通常不能读取应用私有编译目录。源码可来自共享存储，可执行产物应放私有目录。Android 对 target29+ 应用执行私有目录下载文件有限制；入口会报告不支持，不更改 targetSdk 绕过。卸载组件前先结束使用它的任务，再执行 `aijs-toolchain remove cpp`；项目源码不放组件目录，卸载不会主动删除项目目录。该管理入口不代表 Node 原生 child_process 已自动注入终端函数。

应用内终端与 MCP 终端复用同一底层，默认执行期限 **1 小时**。界面（dev-1411/1412）：控制台**跟随式自动滚动**（只在你本就在底部时才跟随，上翻阅读不被打断，滚回底部自动恢复）、**运行状态行**（空闲=后端+cwd；运行中=jobId+200ms 计时；结束=成功或退出码+耗时）、**快捷命令横滑条**（pwd / ls -l / id / 日志=`logcat -d -t 200` / 前台=`dumpsys window | grep -i mCurrentFocus` / 端口=`ss -tlnp`）、**↑/↓ 或长按输入框**回溯最近 **50 条**命令（软键盘没有方向键，所以长按输入框也能弹历史）、**清空 / 复制全部**。

控制台保留上限 **20 万字符**（旧的 64Ki 说法已过期）：超出时**一次丢弃到约 10 万字符**，并在**状态行**提示一次「控制台已截断」——提示放状态行是因为它不随日志滚动，不会被截掉。运行中输入的 stdin 不进命令历史；退出界面会清理自己的命令。

## 运行项目与暂存目录（dev-1410）

App 内「运行项目」会把共享存储里的脚本项目**暂存到应用私有目录再执行**（`:script` 用 `files/script-staging/<hash>`，`:native` 用 `files/native-staging/<hash>`）。dev-1410 起**暂存不再是「每次清空重建」**：

- **脚本自己下载/写下的文件会保留**。自更新类脚本把 `脚本.js`、任务脚本下载到运行目录后，下一次 RUN **不会再被清掉**——dev-1410 修的就是「第二次运行报 `Module ".../script-staging/<hash>/脚本.js" not found`」。
- 只删**上次从源项目拷过、这次源项目里已经没有**的文件；清单文件 `.aijs-staging-manifest.txt` 记录上次拷过的相对路径。
- 复制失败**不再整目录删除**（旧实现会把脚本下载的依赖一起删掉）。
- 过滤规则不变：跳过 `.git/` 与普通 `*.apk`；任意嵌套的 `build/` 只保留 `project-res.apk`（+可选 `project-res.json`）；单文件 64MB / 整树 256MB 上限，超限直接报错、不静默截断。
- `.aijs-staging-origin.json` 记录「源目录 ↔ 暂存目录」映射；删掉它会让设置与编译资源映射失效。

### 相对依赖找不到时，先看引擎给的可执行诊断（dev-1410）

Rhino 下 `require("./脚本.js")` 相对路径解析失败时，不再只回 Rhino 那句 `Can not resolve relative module ID … outside of a module`，而是先给可执行诊断：

```text
找不到模块 "./脚本.js"：相对 /storage/emulated/0/脚本 解析为 /storage/emulated/0/脚本/脚本.js，该文件不存在。该目录下现有：(没有 .js/.json 文件)。若这是「云端下载 / 自更新」类脚本，请确认依赖已下载成功，且下载与运行使用同一个工作目录。
```

看到这句时按顺序排查：① 诊断里给的绝对路径是否真的存在；② 自更新脚本的**下载目录**是否等于**运行目录**（暂存后 cwd 是私有暂存目录，不是脚本根）；③ 目录列表是否为空——为空通常说明**下载这一步就没成功**（常见伴随「获取用户信息错误」＝用户信息/卡密校验失败）。

> **版本门禁（重要）**：这条诊断在仓库工作树的模板里**已重建但尚未发布**；**当前发布的 dev-1412 打包产物仍是旧文案**（其模板 `assets/modules/jvm-npm.js` 不含该诊断）。要让打包版拿到它，必须用含该修复的模板**重新打包**；`get_status.template.engineRequireRootFix` 只能证明 require 根修复，**不能**证明诊断存在——判断依据是模板内 `assets/modules/jvm-npm.js` 是否含「找不到模块」。

## MCP 状态面：running / listener / degraded（dev-1414 起）

`get_status.mcp` 这一批有 5 处语义变化，**字段名基本不变但判读方式变了**（完整字段表与判读顺序见 Playbook「get_status.mcp」）：

1. **`running` 与 `listening` 同源**：都等于「服务实例活着 **且 accept 循环在跑**」。进程被回收、或 accept 循环死掉时，**同一次响应里 `running` 不再为 true**（旧实现直接回持久化布尔，只看 `running` 的调用方会误判成健康）。
2. **新增 `persistedRunning`**：持久化布尔，即**旧 `running` 的值**。它只代表「上次记录是运行中」，**不是健康判据**（进程被回收后仍可能为 true）。
3. **新增 `listener{alive,failureCount,degraded,lastFailure}`**：accept 循环现场。`alive`=循环在跑；`failureCount`=监听侧失败次数；`lastFailure` 形如 `respond_503: OutOfMemoryError: …`、`prepare_socket: <异常类>: <消息>`（写 503 响应失败连 `Error` 也会被记录，且**不再让 accept 循环退出**）。注意 **`listener.degraded=true` 只表示「失败过」（`failureCount>0`），不等于循环已死**——别和 `status=degraded` 混为一谈。
4. **`status` 新增 `degraded`**：完整枚举 `starting|running|stopped|error|degraded|stale`。**`degraded` = 服务实例还在、但 accept 循环已经死了**（占着端口却不再 accept 的「静默僵尸」）→ 此时 `listening=false`、`listener.alive=false`，排查看 `listener.lastFailure`。
5. **`recovery` 改为 30 秒冷却**：`recovery` 仍只在 `listening=false` 时出现，但文案已改为「若刚刚启动失败过（例如端口被占），自动拉起会在 **30 秒**后重试，期间请在 MCP 页面手动开启」。旧的「失败过就永不再试、除非手动重启」已废：失败后冷却 `RETRY_AFTER_MS=30000` 就允许自动拉起（手动开启不受冷却限制）。

**判读顺序**：① 先看 `listening`；② `listening=false` 时再看 `status`——`degraded` = 实例在但 accept 循环死了（看 `listener.lastFailure`），`stopped`/`stale`/`error` = 进程没跑或服务没起来（看 `recovery`）；③ **任何时候都不要用 `persistedRunning` 判健康**。

> 版本边界：以上是 **dev-1414 起** 的语义。**dev-1413 及更早为旧契约**：`running` 是持久化布尔、没有 `persistedRunning`/`listener`、`status` 没有 `degraded`、`recovery` 也没有 30 秒冷却。

## dev-1409~1412 行为变化（dev-1409~1412 新增项；上一节见 dev-1368）

| 工具/字段 | 现在的行为 |
|---|---|
| `get_status.mcp` | **新增**（dev-1411 P1-G / dev-1412 J3-J5；**dev-1414 起字段与判读方式有变 → 见上一节**）：`status`（`starting|running|stopped|error|degraded|stale`）/`listening`/`fresh`/`running`（**与 `listening` 同源**）/`persistedRunning`（旧 `running` 的值）/`listener{alive,failureCount,degraded,lastFailure}`/`port`/`localUrl`/`lanUrl`/`updatedAt`/`heartbeatAgeMs`/`heartbeatIntervalMs`/`heartbeatTimeoutMs`/`lastError`/`startReason`/`autoStarted`/`foregroundService`/`sticky`/`state`，仅 `listening=false` 时多一个 `recovery`。`listening` 是**进程内真实监听**（= 实例活着 **且 accept 循环在跑**），不是心跳推断。K40 dev-1412 实测：`status=running`/`listening=true`/`state=running`/`startReason=auto`/`heartbeatAgeMs≈0.9s` |
| `get_status.template` | **新增**（dev-1404）：`source`（`bundled_assets`/`host_cache`/`sdcard`）、`path`、`sizeBytes`、`sha256`、`engineRequireRootFix`、`templateStale`、`warnings`、`nextActions` |
| HTTP **503** | body 改为**结构化 JSON**（`error.code` + 顶层 `errorCode` = `service_busy`、`recovery`、`retryAfterMs`）；503 的原因是**连接队列满**（4 worker + 16 队列），**不代表真实负载**，也不代表工具失败 |
| 相对 `require` 失败 | 先给可执行诊断（解析后绝对路径 + 目录现有 `.js/.json` + 自更新脚本排查提示），见上节 |
| 项目暂存目录 | 清单化合并：脚本自己写下的文件保留，只清「上次拷过、这次源里没有的」；失败不再整目录删除，见上节 |
| 内置终端界面 | 跟随式滚动 / 运行状态行 / 快捷命令条 / ↑↓ 与长按历史 50 条 / 清空+复制全部；控制台上限 **20 万字符**（超限丢到约一半 + 状态行提示一次） |
| `reverse_guide` 参数 | 只有 `topic`（`index`/`all`/`<section-id>`）、`offset`、`maxChars`；**没有** `sectionIds` 入参——`sectionIds` 只是**响应字段**（章节名列表），逐节读取要用 `topic=<section-id>` |

## dev-1368 行为变化（相对 dev-1365，改前请对照）

这一批按用户反馈修了 11 条工具行为；**调用方按下列新语义写代码**：

| 工具/字段 | 现在的行为 |
|---|---|
| `app_trace_start.withLogs` | **布尔**（以前 schema 错写成 string，任何客户端都传不进去）。同批修正的还有 `workspace_cleanup.dryRun/protectApplied`（布尔）、`pid/keepLatest`（整数） |
| `native_analysis_open` | **会真校验文件 SHA-256**：与入参不一致直接报 `ANALYSIS_STALE_INPUT`（写盘前拦截，不再落 `native-analysis/0000000000000000/`）；`sourceSha256` 是实测值 |
| `native_analysis_close` / `open` | `close` 把状态**归档**为 `archive-<sessionId>.json`（返回 `archived/archivePath/retainedStateBytes`）；同 sha 再 `open` 会恢复 `functions/types/noreturns/overlays` 并返回 `reused=true/restored=true/restoredFrom`；坏归档回 `restored=false + restoreError`，不静默归零 |
| `apk_session_check/diff/edit_text/patch_bytes/delete_target/copy_entry` | 成功返回新增 **`revision` + `revisionChanged`**：编辑后用新 `revision` 调 `apk_session_checkpoint` 才能通过；跨版本报错会给出当前值与你传的值 |
| locator 报错 | 直接列出前缀清单与示例（`zip_entry:` / `dex_class:` / `dex_method:` / `resource:`，跨 DEX 用 `uniqueLocator`） |
| `apk_search` | `target=overview` 真执行（见「搜索与定位」）；新增 `matchMode=literal|literalWord|regex` |
| `terminal_read/write/stop/list` | 结构化错误码 **`JOB_NOT_FOUND` / `JOB_EVICTED` / `SERVICE_RESTARTED`**；`terminal_list` 返回 `retention{…}`、`evicted[]`（含 code/reason/recordedAt）、`tombstoneCount`、`ownershipEnforced:false`、`service{…}` |
| MCP 错误体 | 工具抛出的结构化错误现在带 **`errorCode` 与 `argument`**（以前被折叠成纯文本）；`errorCode` 只在基础设施级失败时填，操作级失败看 `reason` |
| `file_access_diagnose` | `conclusion` 从 `ACCESSIBLE` 改为 **`STAT_ONLY`**（只证明存在/可 stat），新增 `probeStatus`（`not_probed`/`eacces`/`read_probe_ok`）与 `legacyConclusion`；可读性以 errno/notes 为准 |
| `apk_job_status/list` | 失败任务现在给出 **`errorCode` + `errorHint`**（例如 Frida 失败会给 `FRIDA_CONNECT_FAILED`/`SERVER_MISSING`/`COMPONENT_MISSING`） |
| `apk_build_project` | 新增 **`buildInPlace`**（默认 false）：工程在工具目录内且可写时可**原地构建**，跳过整树复制；会前后各算一次工程树 SHA-256，不一致报 `IN_PLACE_SOURCE_MODIFIED`。返回 `stagingMode`（copy/in_place）、`copyMs`、`copiedEntries/skippedEntries/skippedBytes`、`sourceTreeUnchanged` |
| `get_status.limits` | 新增 `childHeapMbAppliesTo`（该 `-Xmx` 只对我们拼装的 apktool/jadx 子进程生效）与 `observedChildHeapBytes`（已知 jadx 实测 256MB）；`maxProjectEntries/maxProjectBytes/maxTimeoutSeconds` 不变 |

> `apk_build_project` 的顶层 `alignment` 只读诊断：未签名导出时 `checked=false`（没有 verifier 报告就不声称对齐），签名导出时为 `checked=true` 并给 `storedEntriesChecked/storedEntriesMisaligned4Bytes/nativeLibrariesMisaligned16Kb` 等。

## dev-1373 已修（对照 .4 反馈 #12/#13/#18）

> 来源：`.4` 的 `MCP-AIjsPro-功能与遗留问题-dev1371.md` 第七节仍把 #12/#13/#18 列为未修；dev-1373 已修并 K40 实测。以下可直接复测，复测后请自行划掉。

### #12 `apk_inject_gadget interactionType=script`：不再要求 endpoint/token

现在 script 模式不构建网络配置：结果只有 `interaction=script` + `scriptPath`（以及 `providerProcess/providerProcessSource`），**没有 endpoint/token**；`nextActions` 也不会再提 endpoint/token。旧版本「endpoint 必须为字符串」的报错不再出现。

复现（把路径换成本机工具目录内真实文件；`outPath` 必须是父目录存在的新文件）：
```json
{"name":"apk_inject_gadget","arguments":{"path":"/storage/emulated/0/脚本/target.apk","outPath":"/storage/emulated/0/脚本/target-script.apk","interactionType":"script","scriptPath":"/data/local/tmp/a.js","providerProcess":":remote"}}
```

预期：`interaction=script`、`scriptPath=/data/local/tmp/a.js`、`providerProcess=:remote`、`providerProcessSource=explicit`、`signed=true`，**结果里没有 `endpoint`/`token`**；`nextActions` 无 endpoint/token。安装打开后 Gadget 按 `scriptPath` 加载（不碰网络）。

**怎么验证**：记录返回 JSON 的 `interaction/scriptPath/providerProcess/providerProcessSource`，确认 `endpoint`/`token` 不存在；安装后用 `frida_status`/logcat 确认 Gadget 加载，script 模式不找网络端口。
**输出路径坑（dev-1374）**：`apk_inject_gadget.outPath` 必须是**父目录已存在的新文件**，工具不会建父目录；父目录不存在会报 `outPath 必须为父目录存在的新文件`。要写子目录先建目录再传绝对路径：
```json
{"name":"file_create_directory","arguments":{"path":"autojs_crack/_mcp_test"}}
{"name":"apk_inject_gadget","arguments":{"path":"/storage/emulated/0/脚本/target.apk","outPath":"/storage/emulated/0/脚本/autojs_crack/_mcp_test/target-script.apk","interactionType":"script","scriptPath":"/data/local/tmp/a.js"}}
```

**怎么验证**：先传一个父目录不存在的 `outPath` 复现错误；`file_create_directory` 返回 `created:true` 后再传同一路径（`outPath` 不能已存在）应成功。

### apk_inject_gadget detail=summary 与 partialReasons（dev-1378 引入，dev-1379 更正归因）

`apk_inject_gadget` 新增可选 `detail=full|summary`（默认 full，老调用不变）。摘要只回 `path/size/sha256/signed/signer/schemesApplied/providerProcess/providerProcessSource/partial/installable/note`；同一云镜样本实测 full 42 KB → summary 480 字符。**summary 不含 `partialReasons`**；要定位 partial 真因必须用 full（默认）或 `apk_verify`。

```json
{"name":"apk_inject_gadget","arguments":{"path":"<原APK>","outPath":"<新APK>","interactionType":"script","scriptPath":"/data/local/tmp/a.js","detail":"summary"}}
```

full 诊断示例：

```json
{"name":"apk_inject_gadget","arguments":{"path":"<原APK>","outPath":"<新APK>","interactionType":"script","scriptPath":"/data/local/tmp/a.js","detail":"full"}}
{"name":"apk_verify","arguments":{"path":"<输出APK>"}}
```

**16 KiB 页对齐告警的真实归因（dev-1379 更正）**：full 结果若 `verification.status="partial"`，先看 `partialReasons[].entry`/`scope`（`target`=目标包自带的库，`injected`=本工具注入的 Gadget；`upstream`/`impact` 是辅助字段）。**本次 K40 云镜样本的 5 条告警不是 Gadget 引入的**：

- `lib/arm64-v8a/libroot_automator.so` 3 段（`p_align=0x1000`，其中 2 段还不同余）；
- `lib/arm64-v8a/libyj_aj_02_309.so` 2 段（`p_align=0x4000` 但 offset/vaddr 不同余）；
- 对**未注入的原包**单独 `apk_verify` 也是同样 5 条；注入的 `lib/arm64-v8a/libaijsgadget.so` 3 段全部通过。

**三种情形**：
1. **目标库不合规**：只能由目标 APK 提供方用 NDK r27+（`-Wl,-z,max-page-size=16384`）重编；我们不要直接改 `p_align`——32 位段不同余时要连带改 `p_vaddr/p_offset` 或重链接，风险高且不保证运行。
2. **注入的 Gadget 不合规**：只有 32 位 ABI 会（上游 armeabi-v7a/x86 的 `p_align=0x1000`）；64 位 arm64/x86_64 的 Gadget 已合规。16 KiB 页设备是 arm64，32 位 Gadget 不会用于该场景，当前无需为它重编/替换。
3. **4096 页设备上不影响加载**：K40 与 `.4` 实测 `getconf PAGE_SIZE=4096`，没有真实 16 KiB 加载失败证据；但真正的 16 KiB 页硬件仍需实测，不能把「4K 设备能加载」当成 16K 合格。

**排查怎么验证**：
1. 用 `detail=full`（summary 不含 `partialReasons`）注入同一 APK，读取顶层 `partialReasons[]`（`verification` 里也会带一份）；每项看 `entry/scope/errorCodes/messages`。`scope=target` 即目标自有库；`scope=injected` 才是我们注入的 Gadget。若旧构建还没有 `entry/scope`，用 `upstream`/`impact` 辅助，并对原包/输出做 `apk_verify` 对照。
2. **原包 vs 注入包对比**：对未注入的原包（本次样本为 `yj-orig.apk`）和注入输出各跑一次 `apk_verify`。native 的 `ELF_PT_LOAD_ALIGNMENT_16K` 条目在两边相同 ⇒ target-owned；只有注入后才多出的 `entry=lib/.../libaijsgadget.so` 才是 injected Gadget。
3. 在设备终端跑 `terminal_exec {"command":"getconf PAGE_SIZE"}`（K40/`.4` 当前为 4096）；16 KiB 页设备另行实测，报告里要写清是静态检查还是真实加载结果。


### #13 `providerProcess` 已进 schema，省略时自动判定

`apk_inject_gadget` 新增可选 `providerProcess`。省略时按 MAIN+LAUNCHER 入口 `activity/activity-alias` 的 `android:process` 自动判定；入口未声明则用 `application` 的 `android:process`；都没有则默认进程。返回 `providerProcess` + `providerProcessSource=explicit|manifest|default`。

复现：
```json
{"name":"apk_inject_gadget","arguments":{"path":"<原APK>","outPath":"<新APK>","interactionType":"script","scriptPath":"/data/local/tmp/a.js"}}
```
预期 `providerProcessSource` 为 `manifest` 或 `default`（取决于该 APK 入口/application 的 `android:process`）；显式传 `"providerProcess":":remote"` 时必须是 `:remote`/`explicit`。
- **真实样本实测（dev-1374）**：对 `.4` 的 `云镜快照样本-原件.apk`（真实 Auto.js 打包包，入口 activity 声明 `android:process=":script"`）省略 `providerProcess`，`apk_inject_gadget` 返回 `providerProcess=":script"`、`providerProcessSource="manifest"`；**不需要再手工把 Provider 挪到 `:script`**——工具新增独立 Provider 条目，不复制原 Provider 条目，也不会复制成两条。
- 边界：显式分支和该真实 manifest 分支已实测；其它 APK 仍按上面的入口/application/default 规则判定；遇到入口和 application 都没有 `android:process` 的包返回 default，不要假设一定是 `:script`。

**怎么验证**：用该样本按下面命令省略 `providerProcess`，检查返回 `providerProcess=":script"`、`providerProcessSource="manifest"`；安装后看 `:script` 进程/日志是否加载 Gadget。显式传 `:remote` 时仍应返回 `:remote`/`explicit`。


### apk_inject_gadget.preloadMode：provider / dt_needed / clinit

apk_inject_gadget 的 preloadMode 有三个取值：**provider（默认）、dt_needed、clinit**（clinit **dev-1416 起**；dev-1414 及更早只接受 provider|dt_needed，传 clinit 会被参数校验直接拒绝）。它和 providerProcess 不是一回事：providerProcess 决定注入的 Provider 跑在哪个进程（`:script`/`:remote` 等），preloadMode 决定 Gadget 在启动链的哪个阶段加载。

**provider（当前可用，较晚）**

- 走现有 ContentProvider.onCreate 路径；ContentProvider 在 Application.attachBaseContext 之后才创建，对加固壳可能太晚。
- 成功时以 preload.earlyLoad=false、preload.preloadImplemented=false 如实标注（字段以当前返回为准）；不要把它当成已提前加载，也不要因为有 provider 就声称能抢在壳的 native 初始化前。

**dt_needed（支持时；失败/不支持时明确失败）**

- 可选 preloadTarget：指定 APK 内目标 .so（完整 lib/... 路径或文件名）；省略时从 lib/*/*.so 自动选择第一个可安全 patch 的目标（排除 gadget/config）。
- 支持时的语义：就地给目标 .so 的 .dynamic 增加一条 DT_NEEDED=libaijsgadget.so，并在 .dynstr 追加库名或复用已有同名串；让 linker 在加载目标壳 .so 之前先加载 Gadget。这是 .4 手工 patchelf --add-needed 的自动化版本。
- 成功保证（仅当工具明确支持并成功时）：只使用 .dynamic 已有的可替换 DT_NULL 空槽（通常要保留后一个 DT_NULL 作终止符）和 .dynstr 尾部 NUL 余量，不移动节/段/PT_LOAD，输出文件大小不变；原子写出，失败不留下半坏产物、不改原文件。
- 成功时以 preload.applied=true、earlyLoad=true、preloadImplemented=true、sizeUnchanged=true、verified=true、targetPath 为准；只有这些字段都成立才算提前加载。只有 provider 时是 earlyLoad=false、preloadImplemented=false。
- 失败时不要伪造成功：顶层通常是 PRELOAD_DT_NEEDED_FAILED（旧版/模式不可用可能是 PRELOAD_MODE_UNAVAILABLE），错误信息里带 preload 计划（applied=false）；reason 举例：DYNAMIC_NO_SPARE_NULL（没有可替换且后续仍保留 DT_NULL 的槽）、DYNSTR_NO_ROOM（尾部 NUL 余量不足）、NO_DYNAMIC_SEGMENT（没有可读 PT_DYNAMIC/.dynamic）、NOT_ELF/UNSUPPORTED_ENDIAN/UNSUPPORTED_ELF_CLASS 等；完整清单以错误/计划里的 preload.targets[].reason/reasons 为准，不要把这里当成全集。
- 失败/不支持时的正确路径：对目标 .so 的副本执行外部 patchelf --add-needed libaijsgadget.so --page-size 16384 <目标.so>（page-size 按目标/设备页大小，16 KiB 用 16384），再回填 APK、重签并用 apk_verify 复核；这不是工具自动完成的步骤。
- 必须回退外部 patchelf 的情形：目标不是动态 ELF、没有 .dynamic、没有空闲且可替换的 DT_NULL 槽或 .dynstr 没有 NUL 余量、需要移动节/段/PT_LOAD、字节序/架构不支持，或返回 PRELOAD_DT_NEEDED_FAILED 及任何细分 reason；不要反复试到“看起来成功”。
- 边界：目标 .so 必须在壳初始化前被 linker 加载；多 .so/多 ABI/分包、加固壳自定义 linker、页对齐不满足的包不保证成功。provider 模式仍可作为兼容回退，但它不解决“早于 attachBaseContext”的问题。

**clinit（dev-1416 起：Java 类初始化阶段，最早的一条路径）**

- 语义：生成 Application 子类 `com.aijs.instrumentation.AijsApplication`（`.super` = 原 `application@name`），其 `<clinit>` 里 `System.loadLibrary("aijsgadget")`（整段 `.catch Ljava/lang/Throwable;` 只 `Log.e`，加载失败不会崩 App），作为**新的 `classesN.dex` 追加**；`AndroidManifest.xml` **只改 `application@name` 一个属性**——不重编资源、既有 DEX 一个字节不动。
- 可选 `clinitTarget`：显式指定要继承的原 Application 全限定类名（例如解包后确认的壳 Application）；省略则读 Manifest 的 `application@name`，未声明时用 `android.app.Application`。
- **时序边界**：保证早于 `Application.attachBaseContext`；但父类先初始化（JLS 12.4.2），**不保证早于加固壳自身的 native 初始化**（壳可以在 native 或自身 `<clinit>` 里抢跑）。工具返回 `runtimeVerified=false`、`verificationScope=static_dex_manifest_only`、`timingNote`——**只做静态字节级校验，不声称运行时已验证**。
- 成功看 `preload.applied=true`、`earlyLoad=true`、`preloadImplemented=true`、`method=application_clinit_loadLibrary`、`clinitClass`、`originalApplication`、`targetSource`（`application@name`|`clinitTarget`）；结果顶层另有 `clinitClass`/`clinitOriginalApplication`/`clinitDexEntry`。
- 失败码（失败**不产出任何 APK**）：`PRELOAD_CLINIT_UNSUPPORTED_APPLICATION`（原 Application 是 final / 是接口 / 不在 APK 的 `classes*.dex` 里（壳加密或动态加载）/ 声明了 `android:appComponentFactory` / APK 里已存在同名生成类）、`PRELOAD_CLINIT_MANIFEST_PATCH_FAILED`、`PRELOAD_CLINIT_DEX_ASSEMBLY_FAILED`。
- 边界：只覆盖 Java 初始化路径；新增 DEX 与重签会改变签名，壳若自校验 DEX/签名会检测到。clinit 与 provider 钩子并存（二次 `loadLibrary` 是同 classloader no-op）。provider/dt_needed 的 `earlyLoad` 语义不变（provider 仍是 false）。

### #18 `apk_build` 增加 `detail=summary`

`apk_build` 新增可选 `detail=full|summary`（默认 full，老调用不变）。summary 只回关键字段：`path/size/sha256/signed/signer/schemesApplied/schemesVerified/changed*/signature{verified,schemes}/alignment{checked,misalignedEntries,zipStatus}/detail/detailNote`，不再回完整 `verification`/`signatureSummary`。

复现（build 仍要求 `workspaceId/editSessionId/overwrite/sign`）：
```json
{"name":"apk_build","arguments":{"workspaceId":"${WORKSPACE_ID}","editSessionId":"${EDIT_SESSION_ID}","overwrite":true,"sign":true,"detail":"summary"}}
```

**怎么验证**：对同一编辑会话分别用 `detail=full`（默认）和 `detail=summary` 构建，比较返回键集合；summary 不应有完整 `verification`/`signatureSummary`，且 `path/sha256/signed` 必须与 full 一致。`detailNote` 会说明这是精简结果。

**outputName 路径限制（dev-1374）**：`apk_build.outputName` 只接受**纯文件名或绝对路径**；带子目录的相对路径（如 `autojs_crack/_mcp_test/x.apk`）会被拒，报 `outputName 只接受文件名或绝对路径`。要输出到子目录：
1. 先 `file_create_directory {"path":"autojs_crack/_mcp_test"}`（目录已存在时返回 `created:false`，可安全重试）；
2. `apk_build` 用绝对路径 `outputName`：`/storage/emulated/0/脚本/autojs_crack/_mcp_test/x.apk`（必须在工具操作根内）。纯文件名会落在操作根，不要写 `../`/符号链接。

**怎么验证**：先直接用相对子目录复现报错；建目录后改绝对路径应成功，返回的 `path` 与传入绝对路径一致。

## dev-1380 手册补丁（B9/B11/B12/B13/B15/B17/B18 + §11）

> 来源：`.4` 的 `MCP-AIjsPro-总结-dev1378.md`（使用手册 + B1~B15）及 §11 实测。以下每条给正确用法与验证；旧版差异单独标注。

### B9 `apk_inject_gadget.detail=summary`

`apk_inject_gadget` 可选 `detail=full|summary`（默认 full，dev-1378 起）。`summary` 只回 `path/size/sha256/signed/signer/schemesApplied/providerProcess/providerProcessSource/partial/installable/note`，同一云镜样本实测 **42 KB→480 字符**；**summary 不含 `partialReasons`**，要诊断 partial 必须 `detail=full`（或 `apk_verify`）。

**怎么验证**：同一输入分别 `detail=summary`/`full`，summary 只有上述关键字段、full 有 `verification/partialReasons`；不要用 summary 判断 partial 原因。

### B11 `maxChars` 两套上限不要混

- `apk_decompile_class`/`apk_decompile_method`：`maxChars` **默认 200000、上限 200000**；超过仍在 **200000 截断**（B3 正在修，先如实现状），不会自动分页。要拿全，用 `apk_job_export`/`offset` 续读，或把类/方法拆小。
- `apk_job_result`：默认读取窗口 **1,048,576 字符**，schema 上限 **1 MiB**（dev-1380 起；旧构建曾为 200000）。大结果一次取回，超过再用 `nextOffset` 续读。
- `apk_job_export`：默认导出全部结果，`offset`/`maxChars` 可选（`maxChars ≤1 MiB`）；适合把反编译等大结果原样留档。

**怎么验证**：对一个 81,162 字符的反编译结果不传 `maxChars` 调 `apk_job_result`，确认 `totalChars=81162`、`hasMore=false`、`defaultMaxChars=1048576`；再对输出调 `apk_job_export`，`file_stat` 的 SHA/字节与导出返回一致。`apk_decompile_*` 传 `maxChars=200000` 与传更大值对比，确认仍按 200000 截断（不要宣称已修）。

### B12 `zip_create` 与 `file_edit_text` 两个易错参数

- `zip_create` **文件源**的 `entryPath` 必须是完整文件路径、**不能以 `/` 结尾**（传 `脚本/` 会报「文件源的 entryPath 必须是非空文件路径」）；目录源才以 `/` 结尾。**输出 ZIP 不能位于任一源目录内**（把整个项目目录当源会失败）。
- `file_edit_text` **新建文件**必须 `targetVersion="missing"`、`encoding` 用实际编码、`edits` 只能是**一个** `write_target`；传 `"new"` 会报 `INVALID_ARGUMENT`（它只接受 64 位 SHA-256 或 missing）。
- `edits[].matchText`：`write_target` 模式下必须为空串（可省略，省略时默认空串）；`replace_match`/`insert_before_match`/`insert_after_match` 模式必须给出，且必须在原文中恰好出现一次。

正确示例：

```json
{"name":"zip_create","arguments":{"outputPath":"out/archive.zip","sources":[{"sourcePath":"脚本/a.txt","entryPath":"a.txt"}],"overwrite":false,"level":6}}
{"name":"file_edit_text","arguments":{"path":"notes/new.txt","targetVersion":"missing","encoding":"utf-8","edits":[{"mode":"write_target","matchText":"","writeText":"hello"}]}}
```

**怎么验证**：`zip_create` 文件源 `entryPath="脚本/"` 复现报错，改 `a.txt` 成功；输出放在源目录内应失败，移到 `out/archive.zip` 成功。`file_edit_text` 传 `targetVersion="new"` 复现 INVALID_ARGUMENT，改 `missing` 成功；成功后 `file_read_text` 回读。

### B13 `terminal_exec` 无 PTY 与能力缺口（执行前自检）

`terminal_exec` 是**无 PTY** 管道终端，stdout/stderr 合并；`ls/grep/cat` 等是设备 shell 实现，不保证 GNU 特性。执行前先调 `shell_capabilities`（无参数）看当前后端清单，再按替代写法：

- `grep` 可能不支持 `\b`/`\s`：用 `[[:space:]]`、显式边界或 `awk`；先 `grep --help` 确认。
- `cat` 可能没有 `-n`/`-A`：用 `sed -n '1,20p'`、`awk '{print NR":"$0}'`、`od`/`xxd` 代替。
- 没有 `zip`/`jar`/`7z`，通常只有 `tar`；ZIP 操作直接用 MCP 的 `zip_create`/`zip_extract`/`zip_update`，不要在终端里赌工具存在。
- `python` 可能是坏软链：先 `command -v python3`/`ls -l`，用可用绝对路径；别再假设 `python` 可用。

**怎么验证**：`shell_capabilities {}` 返回后逐项对照；再跑 `command -v zip jar 7z python3; grep --help | head; cat --help | head`，用 `grep -E '[[:space:]]'`/`sed -n` 各跑一条；在报告里写实际能力，不要写「命令不存在」为工具 bug。

### B15 两套 native 工具别混

| 目的 | 用哪套 | 输入 | 环境 | 不适合 |
|---|---|---|---|---|
| 分析本机/已导出的 so 或任意文件 | `native_*`（radare2） | 手机文件 `path` | r2/r2ghidra 组件 | 不能传 `workspaceId`/`locator` |
| 分析 APK 快照内的 so | `apk_native_*`（Capstone） | `workspaceId`+`editSessionId`+`locator=zip_entry:...` | 不落盘 | 不能传本机 `path` |
| 要 r2ghidra 伪代码 | `native_decompile` | 先 `apk_copy_entry` 导出，再 `path` | r2ghidra | 先导出再分析 |
| 要改 APK 内 so | `apk_native_patch_*` | 会话/locator | 内置汇编/可选 clang | 不改源包 |

**怎么验证**：对同一 so，先 `apk_native_inspect`（locator）确认可读；再 `apk_copy_entry direction="workspace_to_local"` 导出，用 `native_analyze {path}` 分析；两边地址/架构一致即路径选对。反向把 `workspaceId` 传给 `native_*` 应报参数错误，把 `path` 传给 `apk_native_*` 应报 locator 错误。

### B17 `native_functions` 的 offset 是条目索引

`native_functions(path,offset,limit)` 的 `offset` 是**函数条目索引，不是地址**；跨页用返回的 `nextOffset`，不要拿 `address` 当 offset。返回 `heuristic=true` 表示启发式扫函数，**不保证覆盖全部函数/边界准确**；要按地址看单个函数用 `native_function_detail`。

**怎么验证**：`native_functions {path,offset:0,limit:10}` → 用 `nextOffset` 取下一页（不要 `address`）；确认响应 `heuristic:true`，并在报告里写明「启发式列表，不是完整函数表」。

### B18 批量反编译产物的非 ASCII 文件名

批量反编译输出的文件名可能含非 ASCII 类名（如 `okhttp3.internal.io.ვ.java`），部分文件/压缩工具链会踩坑。机器处理时做 **name→ASCII 映射**（如 base64/hex 安全名 + 单独 manifest 记录原类名），或跳过/转义；不要把文件名当类名。MCP 的 `apk_read_text` 用 locator、`apk_job_export` 用指定 `outPath`，**不经过反编译产物文件名**，不受影响。

**怎么验证**：对一个非 ASCII 类名的目标跑 `apk_decompile_class`，检查产物文件名；改用 `apk_read_text(locator="dex_class:...")` 读取同一类，确认内容可达；`apk_job_export` 导出到自定义 `outPath` 检查文件存在。

### §11 `run_script` 超时与 Node 执行槽

- MCP `run_script.timeoutSeconds` 上限 **600**；`wait:true` 最多等 600 秒，超时返回 `timedOut=true`，但脚本可能仍在运行。用 `get_execution`/`wait_execution`/`stop_script` 继续处理，**不要把 timedOut 当脚本终止**。
- 按 `path` 运行的脚本原生超时是 **1 小时**（`nativeTimeoutMillis=3600000`），MCP 的 600 秒不会真杀脚本；需要停止要显式 `stop_script`。
- Node 执行槽 **3 个**；第 4 个会等待（≤8 秒）后抛 `PoolBusyException`，不是「engine 不存在」。先停一个 Node 脚本或复用任务；C 正在改排队/补文案，先按现状处理。

**怎么验证**：`run_script {path:"<长脚本>",wait:true,timeoutSeconds:1}` 预期 `timedOut=true`，随后 `get_execution` 仍 `RUNNING`，再 `stop_script`；同时启动 3 个 Node 长任务再起第 4 个，预期 `PoolBusyException`（不要在真机留无限循环，验证后立即停止）。

### Rhino 版本边界：`String.valueOf(42)`

`.4` 的 **dev-1378** 在 Rhino 上 `Packages.java.lang.String.valueOf(42)` 仍是 `42.0`；**dev-1380+** 才返回 `42`（K40 实测；`valueOf(1.5)` 仍 `1.5`）。Node 早就返回 `42`。不要按手册的「已 OK」在旧 Rhino 上断言整数输出；先 `get_status.app.build` 或实测。

**怎么验证**：分别用 `run_script {engine:"rhino",...}` 与 `engine:"nodejs"` 打印 `Packages.java.lang.String.valueOf(42)`；dev-1380+ Rhino 应为 `42`，`.4` dev-1378 是 `42.0`，Node 一直 `42`；报告里写清构建版本。


## 常见错误与修法

| 返回/现象 | 常见原因 | 正确做法 |
|---|---|---|
| 路径超出操作目录 / 文件不存在 | 传了 /sdcard 绝对路径或路径写错 | path 用相对操作目录的路径；先 list_scripts 看实际文件 |
| 必须提供 path/file/source 之一 | run_script 三个入口都没给 | path/file 是相对路径字符串；source 直接放源码字符串 |
| engine 无效 | 传了 node / node24 / 大小写错误 | 可用 rhino、quickjs、nodejs（node24 是 nodejs 别名） |
| run_script 成功但拿不到 result/logs | 没传 wait:true | 加 wait:true, timeoutSeconds:60；或事后 wait_execution |
| wait=true 返回 timedOut=true | 脚本还在跑/死循环 | 不能当成成功；继续 wait_execution 或 stop_script |
| workspace_write 后真实脚本没变 | 只改了私有工作区 | 继续调用 workspace_request_apply 并检查应用结果 |
| get_ui_layout 返回空/失败 | 无障碍未开启或页面受保护 | 先确认无障碍；用 capture_screen 截图确认页面；看 diagnostics |
| capture_screen 后点击坐标不对 | 截图被 maxWidth 缩小 | 用 sourceWidth/sourceHeight/scale 换算回原图坐标 |
| 参数类型错误 | 布尔/数字传成了字符串 | JSON 原生类型：true/false、数字；不要 "true"/"100" |

## 给 AI 客户端的接入提示（可复制）

> 你已连接 AI.js Pro MCP。首次先调用 learn_autojs_skill 读取完整说明；参数以 tools/list 的 inputSchema 为准。JSON 布尔/数字用原生类型；path 用相对操作目录；run_script 的 path/file/source 三选一；要结果必须 wait:true；修改真实文件必须走 workspace_open→write→diff→request_apply；失败先看 error 再调用 get_execution/wait_execution/get_mcp_log，禁止猜字段和猜文件路径。


## APK 深入分析与可选资源编译

新增接口以 tools/list 的 schema 为准；不要继续仅靠工具名称猜参数。

- `apk_verify` 用 apksig 真正验签，并分开报告 ZIP 4B/SO 16KB、ELF LOAD、Manifest/ABI。证书元数据仍由 `apk_read_signature` 读取，二者用途不同；静态报告不保证安装或业务行为。
- `apk_dex_search_members` 直接定位方法与行/指令；`apk_dex_call_graph` 查 callers/callees/both；`apk_dex_hierarchy` 查继承/实现/覆盖。保留原始描述符 locator，跨 DEX 同名类要带 dexEntry；动态/反射与预算截断不得当作完整解析。
- `artifact_open` 可直接开 DEX、SO/ELF 或 APK 分包容器；独立文件用 `artifact_export` 导出，不用 apk_build 冒充 APK。分包用 apk_set_members → apk_set_open_member 后继续 APK 工具。
- 修改前可 `apk_session_checkpoint`；预览 `apk_session_diff`；回退 `apk_session_rollback` 必须给当前 expectedRevision，回退不改原 APK。
- 大任务：apk_job_start → apk_job_status → apk_job_result（分块拼接其 content，offset=nextOffset）；apk_job_cancel 请求取消，CANCELLING 仍在等待真实 worker 结束，已提交操作可能完成。进程重启后不自动重放写入任务。
- 主 APK 不包含 JADX/AAPT2 负载。apk_component_status 只查询；apk_component_install 显式下载 jadx/apk-resources，manifestUrl 可指定清单。当前默认组件在局域网更新服务器的 components/ 下；不在该局域网时需给可访问的清单地址。
- `apk_decompile_class/method` 使用可选 JADX，Java 仅供分析，不能承诺任意反编译代码能原样重建。offset/maxChars 分页，保留方法锚点。
- 混淆类被 JADX 改名/内联时仍可能报 `JADX未找到类`；改用 `apk_read_text(locator="dex_method:…")` 直接读 Smali，或去调用方反编译结果里找内联代码；详见 `AIjs-Reverse-Guide.md` 的「Java 阅读流程」。
- `android_resources_compile` 输入项目目录，自动读 project.json 的 androidResources，默认针对本机实际宿主 sourceDir 编译，原子写 project-res.apk 与 sidecar。自定义 hostApk 只能使用工具目录中的 APK。主机版本或资源变动后重编，不要手改 sidecar 摘要。
- component_install、decompile、resources_compile 默认返回后台 jobId；确需同步时 background=false。首次生成组件的安装不代表本轮编译探测通过，能力表会标明 verified=false。

示例（以下为 tools/call 的 arguments）：

```json
{"projectDir":"chess-auto/node-port"}
```

上例调用 android_resources_compile；收到 jobId 后调用 apk_job_status，结束再调用 apk_job_result。

```json
{"workspaceId":"<返回的工作区ID>","locator":"dex_method:Ldemo/Main;->start()V","direction":"callees","maxDepth":2,"maxNodes":100,"maxEdges":300}
```

上例调用 apk_dex_call_graph。先从列表/搜索得到真实 locator，不照抄示例中的类名。
