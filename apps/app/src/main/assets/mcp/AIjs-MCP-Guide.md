# AI.js Pro MCP 使用说明

本说明对应本项目的 Rhino / QuickJS / Node.js 三引擎和当前公开工具。先调用 list_engines 确认可用引擎。

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
