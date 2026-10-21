# MCP 三引擎接入与 Node 传统 UI 修复

2026-10-21，已发布 **dev-1188**，K40 / 小米 8 均已安装。
APK：`AI-js-Pro-Flutter-dev-1188.apk`，151385529 字节。
SHA-256：`12130528621be5e58f4d9711b29fd571304df7ecac5dae3e1ba1bd16247b395a`。
本地更新清单手机访问 HTTP 200；源码仍在 main 工作区，未提交。

## MCP

此前 dev-1186 实测拒绝 Node 参数，原因是 McpRunInput、McpEngineProbe、运行分支、
API 查询及语法检查只接受 Rhino / QuickJS。现统一接入三引擎，复用既有 ScriptEngineService、
Node24JavaScriptEngine 与 Node 进程池，没有新增解释器或独立任务管理架构。

- engine 支持 rhino / quickjs / nodejs，Node 别名 node / node24 归一化；源码、文件、config
  与显式覆盖走同一选择规则。任务返回实际引擎名称，Node 不再标为 Rhino。
- list_engine_api 支持全局和 require(module) 导出；Node 返回真实注册模块与原生内置模块目录。
  probe_engine_api 支持成员、原型成员和 JS arity，枚举成员时不执行其 getter。
- 新增 list_engines 和 call_engine_api，公开工具共 41 个（含原有 7 个兼容别名）。
  通用调用保持 receiver，传递 JSON 参数，支持 Promise、错误与返回类型。
- Node check_syntax 使用同版本、哈希校验的真实 Node CLI --check，只编译 CommonJS，
  不运行源码。Node24Tools 只准备 CLI 文件，不再为了这个动作向 MCP 主进程加载 Node 核心。
- 每次任务保留自身日志；wait/get/单独停止/全部停止覆盖三个引擎。
  stop_all_scripts 同时调用现有 HostScripts 协调入口，覆盖宿主进程脚本。
- 修正 Node UI mode 判断，使用实际 source executionMode，带 UI 指令的入口包装脚本
  不再因业务代码位于 require 的文件中而漏掉 UI 生命周期策略。
- MCP 使用说明及工具描述更新为三引擎，清除旧的“双引擎/不是 Node/必须令牌”描述。

这里的“全部接入”指三个现有引擎的能力均有 MCP 调用入口，并非把每个函数生成独立工具，
也不代表三个引擎的同名 API 语义完全相同。JSON 工具不能传活的 Java 对象和函数回调；
此类 UI、连续识别、插件对象及多 API 组合通过 run_script 在同一真实任务内执行。
模块目录、JS arity 和成员枚举不等同于全部功能验收、权限或完整 Java 重载签名。

## 传统 UI

原始 `Node.js中使用UI/传统界面/main.node.js` 未修改。原先 grid/list/canvas 是文本占位。
现在使用真实 NodeUiGridView / NodeUiListView / NodeUiCanvasView：

- 两列网格、列表 item 模板、this 字段及三元表达式、真实 item_created 回调；
  holder.position/data 访问时读取当前数据，增删和开关更新同步至原始 JS 数组。
- prelude 放行 list/grid 通用方法，普通原生 RecyclerView.getAdapter 继续返回原 Java 对象。
  Java 的 setOnClickListener(fn) / setOnCheckedChangeListener(fn) 自动接真实接口回调。
- Activity 保持真实 Java 身份，支持 About 弹窗、Snackbar 和原生菜单；无工程资源表时
  ui.R 查询宿主内置资源，消除 -1 图标资源导致的主线程闪退。
- 修正 Android ICU 正则对模板闭括号的要求；修复 XmlConverter 二次输出时遗漏实体转义，
  `CheckBox & Radio` 及引号文本正常。保留模板的 margin。
- Canvas 约 30fps 软件绘制，使用两个复用的 Bitmap/Canvas；后台只绘制背缓冲，
  主线程只显示前缓冲，关闭和尺寸变化不会回收正在绘制的 Bitmap。
  RecyclerView 回调执行器在重新 attach 时恢复。

模板表达式目前支持字段路径与简单三元式，不是完整 JS 模板求值器；Canvas 为软件绘制。
不能把这个示例通过理解为所有外部自定义控件或复杂模板都已完整兼容。

## 实测

- K40、小米 8 最终 dev-1188 各 **42 次 MCP 请求**通过：三引擎运行、实际名称与日志、
  全局/模块/原型查询、JSON 参数与 receiver、Node 异步 fs、config 嵌套参数、
  三引擎差异、语法合法/非法且不执行、两个 Node 并发单独停止、三引擎同时运行后全部停止。
- K40 另 **15 次 MCP 请求**通过文件/UI 入口：显式 Node 覆盖文件中的 Rhino 指令、
  相对 require、原文件内容保持不变、通过 MCP 运行原样传统 UI 文件并实际显示 Demo/Buttons、
  随后单独停止该 UI 任务。
- 两台手机最终包各通过传统 UI **8 项真实交互检查**，exitCode=0；包括网格内容和点击、
  开关同步 JS 与红色文本、删除/插入、回收后位置、持续绘制、真实 Activity 弹窗。
- K40 实际从 Flutter 示例列表点击 `运行：main.node.js`；点“按钮”→“确定”，
  列表出现 New。About 菜单、网格 Snackbar 均实际点击。Flutter、Node UI、确认弹窗
  全在同一 task **6662**；关闭确认后按一次返回到示例列表，无额外白页。
- K40 原始样例画布连续绘制超过 6 分钟，之后最终双缓冲实现重新通过交互探针。
- MCP JVM 单测 **78 项**、XmlConverter 单测 **11 项**无失败；最终 APK 构建、发布验证通过。
- 最终包另回归 colored-button、Web UI、编译资源 M3（见各结果日志），保留原有对象身份和回调。

证据位于 `output/node-pro-gaps-20261021/`：
`{k40,mi8}-mcp-final-1188.log`、`mcp-three-engines-{cccc62c7,ce4d2bdb}.json`、
`{k40,mi8}-traditional-final-1188.log`、`k40-traditional-example-*-1188.{png,xml,log}`、
`k40-traditional-added-1188.{png,xml}`、`k40-traditional-actual-tasks-1188.txt`、
`k40-traditional-one-back-1188.{png,xml}`、`k40-*-regression-1188.log`、
`build-mcp-ui-1188-final.log`、`publish-mcp-ui-1188.log`。
文件/UI 入口补测：`mcp-file-ui-final-1188.{json,log}`、`k40-mcp-ui-file-1188.xml`。

可复用探针：`tools/probes/node24-traditional-ui.js`，需与原样 main.node.js 放同一目录。
DP 任务 `6309805097034128` 已正常完成基础控件实现；主助手完成 MCP、集成修复、编译和上述验收。
