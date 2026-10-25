# AI.js Pro MCP 使用说明

本说明对应本项目的 Rhino / QuickJS / Node.js 三引擎和当前公开工具。先调用 list_engines 确认可用引擎。

## 工具分类

tools/list 的每项都带 category/categoryTitle，description 也以 [分类] 开头；get_status 的 toolCategories 返回各类数量。分类只用于导航，不改变工具名、参数和权限。

| 分类 | 工具 |
|---|---|
| 状态与诊断 | get_status、read_apk_logs、get_mcp_log |
| 脚本与执行 | list_scripts、read_script、search_scripts、continue_result、run_script、check_syntax、list_executions、get_execution、wait_execution、stop_script、stop_all_scripts |
| 工作区 | workspace_open/list/read/write/delete/diff/request_apply/mkdir/cancel/cleanup |
| 引擎与 API | list_engines、list_engine_api、probe_engine_api、engine_api_diff、call_engine_api |
| 设备与无障碍 | get_ui_layout、capture_screen、ui_find/click/long_click/input/swipe/global/wait/scroll、app_list/info/launch/open_settings/open_url、apk_install/uninstall |
| 终端与构建 | terminal_exec/read/write/stop/list、get_build_tools |
| 悬浮窗 | list_overlays、close_overlays |
| 示例与帮助 | list_samples、read_sample、learn_autojs_skill |

## 快速开始（AI 客户端必读）

1. 先调用一次 learn_autojs_skill，再调用 get_status / list_scripts / list_engines 了解现场。
2. 只读：list_scripts → read_script / search_scripts；不要猜文件是否存在。
3. 运行：run_script 的 path/file/source 三选一；需要结果与错误日志必须 wait:true（timeoutSeconds 建议 30~120）。
4. 改真实文件：workspace_open → workspace_read/write/delete → workspace_diff → workspace_request_apply（workspace_write 本身不落盘）。
5. 任何失败：先看返回的 error/status，再调用 get_execution / wait_execution / get_mcp_log；不要重复猜参数。

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
{"name":"ui_swipe","arguments":{"x1":540,"y1":1800,"x2":540,"y2":600,"durationMillis":300}}
{"name":"ui_global","arguments":{"action":"back"}}
```

节点匹配是精确匹配（text/contentDesc/className；className 也接受简单名）；坐标以屏幕像素计。动作失败会返回 success=false 和原因，不要把它当成功。仅遍历当前活动窗口，不覆盖其它窗口/输入法。无障碍截图仍用 capture_screen；先截图+ui_find，再点击，坐标按 sourceWidth/scale 换算。

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

## 手机终端与编译

MCP 和应用内已有终端复用同一执行底层，使用手机应用 UID，无需占用 Rhino/QuickJS/Node 运行槽。它是管道终端（`pty=false`），支持编译日志和 stdin；全屏编辑器、终端控制序列、Ctrl-C 作业控制不属于本接口。不要把 cwd 校验当成 shell 沙箱：workingDirectory 必须在 MCP 操作目录内，命令仍可访问应用权限允许的其他路径。

1. `get_build_tools` 检测 PATH 中 clang/clang++、javac/java、Gradle、aapt2、zipalign、apksigner 等；`found=true` 仅表示定位到命令，`verified=false` 表示尚未试编译。
2. `terminal_exec(command, workingDirectory?, env?, timeoutSeconds?)` 异步创建任务，立即返回 jobId。env 是字符串对象。最多 3 个并发，保留最近 16 条。timeoutSeconds 默认 300、范围 1..3600；这是命令执行期限，到期终止进程组。
3. `terminal_read(jobId, offset?, maxChars?, waitMillis?)` 按上次 nextOffset 继续读合并后的 stdout/stderr。offset/nextOffset 是 UTF-16 字符偏移，默认 0；maxChars 默认 16384、最大 65536；waitMillis 为 0..10000，仅控制等待输出，不会停止任务。每任务保留最近 256Ki 字符；truncated=true 时有旧输出被淘汰，可将完整编译日志重定向到项目文件。
4. `terminal_write(jobId,input?,eof?)` 排队写 stdin，不自动补换行；eof=true 关闭输入。accepted 只表示入队，读取结果中的 inputError/pendingInput 确认是否写入。
5. `terminal_stop(jobId)` 只停止该命令及同进程组子进程，`terminal_list` 查询本 MCP 所有终端任务。MCP 服务停止会清理自己的终端，应用界面的终端由界面生命周期独立管理。

状态：STARTING / RUNNING / SUCCEEDED / FAILED / STOPPED / TIMED_OUT。停止后 processActive 可能短暂仍为 true，应继续读取直到清理完成。判断编译成功需状态 SUCCEEDED、exitCode=0，并检查实际产物；服务/应用进程重启后任务记录不保留。后台守护进程自行 setsid 脱离进程组不受本接口管理，不要用于启动守护服务。

```json
{"name":"terminal_exec","arguments":{"command":"pwd; ls -la","timeoutSeconds":30}}
```

```json
{"name":"terminal_read","arguments":{"jobId":"terminal-返回的ID","offset":0,"waitMillis":1000}}
```

内置已有 D8 和 Smali，通过独立 app_process 执行，编译器 CLI 的退出不会退出宿主。环境变量 AIJS_APK 是当前 APK 路径，AIJS_BUILD_MAIN 是编译入口：

```sh
# JAR/class → DEX；D8 需 Android 8.0/API26+。Java 源码必须先由 javac/ECJ 编译成 class。
mkdir -p out && CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" d8 --min-api 26 --output out input.jar
# Smali → DEX
CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" smali assemble src -o classes.dex
```

终端本身不等于完整工具链。主 APK 没有预装 clang、JDK/javac、完整 Android SDK/NDK/Gradle；C/C++、Java 源码、完整 Android 项目需另行配置手机 ABI/Android 兼容工具链。电脑 SDK/NDK 的 Windows/Linux 主机可执行文件不能直接在手机运行，`./gradlew assembleDebug` 也要求兼容 Java 和资源编译器。Android 共享存储通常不能执行 ELF；普通应用还受到可执行文件存储和 SELinux 规则限制。大型工具链应走可选插件，不往主 APK 塞整套 SDK。

应用内终端同步增加实时输出、输入与停止，退出界面会清理自己的命令；其默认执行期限为 1 小时，界面仅保留最近 64Ki 字符。

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

