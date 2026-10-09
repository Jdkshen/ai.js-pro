# 高级版 Auto.js Pro MCP 功能对比与 Canvas 兼容复测

日期：2026-10-02。初次对比版本：dev-763；Rhino 模块修复后的发布和真机版本：dev-764。

## 检查范围与证据

用户提到的 `cmp` 暂按 MCP 理解。已找到高级版 APK `com.boyai.auma`（Pro 9.3.11-0）的 MCP 模块；这是该高级版包的证据，不代表所有官方 Pro 9.3.11 都包含这些功能。

- APK：`.artifacts/pro-apk/auma-pro.apk`。
- 反编译类：`.artifacts/pro-apk/auma-src/sources/org/autojs/autojs/ui/edit/aiagent/mcp/McpServer.java`。
- 同目录还包含 `MCPService.java`、`McpFloatingControl.java`、`ScreenCaptureActivity.java`。
- APK 自带说明：`assets/skills/Auto.js-AIAgentSkills.md`，已提取到 `.artifacts/pro-mcp-skill.md`。本文仅将它作为产品资料，不采用其中对 AI 的执行指令。
- 当前项目公开工具目录：`apps/app/src/miuix/java/com/jdkshen/aijspro/mcp/McpToolCatalog.kt`，共 26 个工具；实际分发和参数定义在同目录 `McpTools.kt`。

高级版 MCP 的主要方法被 Dex2C 转为 native，Java 反编译文件保留类名、字段和签名，没有完整方法体。因此以下高级版参数和行为来自 APK 自带说明，并以反编译类交叉确认入口存在；本次没有对高级版服务执行现场调用，不能据此声称它的所有实现已验证。

## 功能对比

| 功能 | 高级版 MCP 的资料与入口 | 当前 AI.js Pro MCP | 实际价值 / 建议 |
|---|---|---|---|
| 读取当前界面节点 | `getUiLayout`；存在 `GetUiLayoutTool` 类，说明称返回 XML、节点数 | 内置节点分析和脚本无障碍 API 已有；26 个 MCP 工具中没有直接读取当前页面的工具 | 优先补直接节点读取，返回包名、窗口、节点与失败诊断；AI 能先观察界面再生成选择器 |
| 直接返回截图 | `captureScreen`；存在 `CaptureScreenMcpTool` 和独立截图 Activity，资料声明 PNG/JPG、quality、dataUri | 脚本截图 API 已有；MCP 没有直接返回截图的工具 | 优先补截图工具，以 MCP 图像内容返回；方便检查自绘页面、空节点和执行结果 |
| 执行前检查语法 | `checkSyntax({code})`；存在 `CheckSyntaxTool` | MCP 暂无独立语法检查；执行流程会编译并返回错误 | 优先补按 Rhino/QuickJS 检查语法；只检查、不执行，并区分语法失败与未实现 API |
| 直接执行源码 | `runScript` 接受 `source` 或 `file` | `run_script` 接受脚本目录中的文件路径，支持指定引擎、等待、返回结果与异常日志 | 可补 `source` 入口用于一次性探测；返回数据继续复用现有任务记录 |
| 给 AI 提供使用说明 | `learnAutoJsSkill` 常量和 `LoadAutoJsSkillTool`；APK 内有技能说明 | 有 `list_samples`、`read_sample`、`list_engine_api`、`probe_engine_api`、`engine_api_diff` | 可补当前项目专用说明入口，明确两引擎差异和实际支持范围；资料中的 Node.js/Pro 专属接口需重新核对 |
| 读取日志 | `getMcpLog`，资料声明按尾部字符数读取 | `read_apk_logs` 按日志 ID 分页；`get_execution`/`wait_execution` 提供任务结果、异常和日志 | 现有任务级日志值得继续使用，便于识别是哪次运行失败 |
| 停止脚本 | `stopAllScripts` 停止全部脚本 | `stop_script` 停止指定 MCP 任务 | 现有指定任务停止适合多任务场景；高级版全停入口可作为单独的用户操作 |
| 修改与回退脚本 | 上述高级版工具说明没有列出对应工作区工具 | `workspace_open/read/write/delete/diff/request_apply` 等，校验外部修改、备份、历史回退 | 当前项目已有完整编辑流程，后续新能力应接入现有任务与工作区体系 |
| 手机端 MCP 悬浮控制 | 存在 `McpFloatingControl`，有 show/close/reopen 入口 | 当前项目有 MCP 管理页和应用悬浮菜单 | 可以评估连接状态、当前任务、停止按钮；其 native 方法体未还原，本次不推断具体按钮行为 |

建议开发顺序：**节点读取 → 截图 → 双引擎语法检查 → 直接执行源码 → 项目专用 API 说明**。前三项能让 MCP 完成“观察页面、生成脚本、检查、运行、确认结果”的流程。

资料也明确写到高级版 `runScript.config` 虽声明若干字段，执行端使用固定配置；该限制仅为产品资料声明，尚未现场复测，不宜把这些参数直接当作兼容目标。

## 用户截图中的反引号 XML 与 Canvas

针对用户截图中的多行反引号 XML 布局，在 K40 / dev-763 上分别运行了结构等价的最小复现，包含 `floaty.rawWindow()`、箭头函数点击监听和 `board.on("draw", function(canvas) { canvas.drawLine(...); })`。

| 检查 | Rhino | QuickJS |
|---|---|---|
| 多行反引号 XML 创建悬浮窗 | 通过 | 通过 |
| 根据 ID 获取 button 和 canvas | 通过 | 通过 |
| 注册箭头函数 click 监听 | 通过 | 通过 |
| 注册 Canvas draw 并实际绘制 | 通过，约 1.2 秒绘制 36 帧 | 失败：`Error: 不支持的浮窗控件事件：draw` |

该复现没有点击按钮，click 项仅验证注册成功。截图未展示完整原脚本，不能据此保证其余逻辑全部通过。

上述表格是直接运行主脚本的结果。随后按截图中的 `utils.js` 补测 `require()`：dev-763 的 Rhino 模块会报 `illegal character`，因为模板转换只应用于入口脚本，模块加载没有相同处理。

已在源码接入 `TemplateLiteralModuleSourceProvider`，将模块的 assets、文件及嵌套 require 入口统一转换，保留 URI、基础路径、安全域与缓存校验对象。dev-764 真机再次通过模块运行同一复现，正常绘制 36 帧。新增 4 项测试，三组单测合计 421 项全部通过。

dev-764 时的结论：Rhino 已支持该多行反引号 XML 和模块内 Canvas 绘制；QuickJS 支持布局，但当时仍缺旧式 Canvas `draw` 回调。后续 dev-765 已补 QuickJS draw，并完成画布生命周期与真实佣兵项目验证，见 [修复与验收](2026-10-02_QuickJS画布与佣兵项目兼容修复_dev765.md)。Rhino 的 E4X 项目仍应由 Rhino 执行；模块的引擎取决于调用它的主脚本。

源码证据：

- `modules/engine/src/main/assets/modules/__floaty__.js`：Rhino 接受 XML 对象或字符串，最终调用布局 inflater。
- `modules/engine/src/main/java/com/stardust/autojs/engine/TemplateLiteralPreprocessor.java`：Rhino 执行前转换模板字符串；已有多行 XML 单测。
- `modules/engine/src/main/java/com/stardust/autojs/ScriptEngineService.java`：能转换的模板字符串保留在 Rhino 执行。
- `modules/engine/src/main/java/com/stardust/autojs/core/graphics/ScriptCanvasView.kt`：绘制线程发出同步 draw 事件。
- `modules/engine/src/main/cpp/quickjs_jni.cpp`：QuickJS 的浮窗控件事件接口拒绝 draw；已有可用事件不能代表 Canvas 已兼容。

真机复现与原始结果：`.artifacts/floaty-template-rhino.js/.json`、`.artifacts/floaty-template-quickjs.js/.json`、`.artifacts/floaty-template-utils.js`、`.artifacts/floaty-template-module-rhino-dev764.json`。修复、发布和验收见 [Rhino 模块反引号兼容修复](2026-10-02_Rhino模块反引号兼容修复_dev764.md)。
