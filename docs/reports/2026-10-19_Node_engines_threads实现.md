# Node 引擎管理与线程 API（task-154）

日期：2026-10-19。执行：t1-native-services。验证机：Redmi K40（cccc62c7，LineageOS），dev-1098。

## 1. 结论

- Node24 子进程（:node24）与 :script 进程现在都能通过 app-private Binder 桥访问真实主进程/ScriptEngineService 的引擎注册表；
- prelude 的 engines 模块提供 myEngine() / getRunningEngines()/all() / execScriptFile() / stopAll() / stopAllAndToast()，以及 execution 对象（on/once/off/forceStop/engine()/engineOrNull、start/success/exception 事件）；
- threads 从占位实现改为基于 Node 原生 worker_threads 的真实系统线程（简化版 Auto.js 语义）；
- K40 dev-1098 冒烟：engines 全链路 PASS，threads 12 项冒烟全 PASS，官方示例「Node.js启动Rhino引擎与通信」可跑，Rhino→Node 跨引擎双向事件 PASS。

## 2. engines：实现结构

### 2.1 Java 桥（modules/engine/src/main/java/com/stardust/autojs/engine/node24/）

- NodeEngineBridgeProtocol：JSON-over-Binder 协议（TRANSACT_JSON=0x4E45），action：status/my/list/exec/execution/emit/stop/stopAll，事件 type=__ajs_engine_event；
- NodeEngineBridgeService：同进程真实 ScriptEngineService 适配器——exec 用 JavaScriptFileSource + SimpleScriptExecutionListener 创建子执行（Rhino/Node 由文件内容标记决定），start/success/exception 回调经 Node24JavaScriptEngine.postEventToRunning 投递到 Node 轮询队列；emit 对 Rhino 引擎做 NodeRhinoEventArguments 值转换，对 Node 引擎直接入队；
- NodeEngineBridgeScriptService：:script 进程注册的同一实现子类；
- NodeEngineBridgeClient：先查主进程与 :script 两个候选的 status（hasNode/startedAt/pid），用 selectOwner 选真正有活动 Node 引擎的进程并固定，不做虚拟 id 回退；
- Node24HostBridge：kind=engines → handleEngineRequest(request)，不再走 domainAction 的 engines 分支；
- Node24JavaScriptEngine：emit() 走协议 engineEvent 入轮询队列，带 mPendingEmits 首轮重试（最多 5s，避免 Rhino 的 sleep(1000) 竞态）。

### 2.2 prelude JS（modules/engine/src/main/assets/node24/prelude.js）

- engineBridge(action,args) → kind=engines；myEngine 缓存主进程返回的真实 id；all() 额外兜底：注册表切换瞬间仍包含自己；
- makeEngine：id/engine/execArgv + on/once/off/emit/forceStop；
- ensureEngineEvents：on(__ajs_engine_event) 按 token（execution）与 engineId 路由；监听未挂时按 engineId/type 缓冲 64 条，on() 时补发；
- makeExecution：token 与主进程 ExecutionRecord 对应；start/success/exception 事件 → 回调；engine() 轮询 execution 状态拿真实 engineId（30s 超时）；engineOrNull 惰性取；forceStop 走 stop；
- execScriptFile(path, options)：校验非空；相对路径以 __ajsScriptFile 的父目录为基准（task-154 修复；之前的 process.cwd() 在嵌入执行时是 /，示例的 ./悬浮窗.js 会解析错），绝对路径原样；workingDirectory 默认脚本父目录；arguments 透传到子执行 ExecutionConfig；
- stopAll/stopAllAndToast → stopAll。

## 3. threads：worker_threads 真实线程（简化版）

Node 的 Worker 是独立堆，无法像 Rhino/QuickJS 那样共享闭包，因此实现为「函数源码 + 可 JSON 序列化参数」在 Worker 内重建执行：

- threads.start(fn, ...args)：创建 Worker（eval 模式，execArgv 清空），返回 Thread 句柄；参数必须可 JSON 序列化（函数/symbol 直接 TypeError）；
- Thread：id/name/isAlive()/interrupt()/join([timeout])/result/error/on('exit')；
- join：主线程 Atomics.wait 在 SharedArrayBuffer 状态字上真阻塞；超时返回 false，完成返回 true；
- result/error：Worker 把 JSON 结果写进 SAB（header 8B + 256KB payload），join 后读取；异常栈完整带回；
- interrupt：Atomics.show 状态置为 INTERRUPTED + Worker.terminate()，join 立即返回 true；
- threads.shutDownAll()：终止全部存活 Worker，返回数量；threads.currentThread()：返回 id=0 的 main 句柄；
- 明确未实现并抛错：threads.exec / threads.atomic / $threads.pool（函数体在独立 Worker，闭包不可见；需要共享状态请用 SharedArrayBuffer/Atomics 或 engines 事件）。

## 4. K40 验证（dev-1098，已装）

### 4.1 engines 冒烟（path 模式，:script 进程）

步骤（/sdcard/node154-engines/main.js）：myEngine → all() → execScriptFile('./child.js',{arguments}) → 子 Rhino 引擎 emit 回父引擎 → 事件断言。

结果文件 /sdcard/node154-engines/result.json：

    myEngine.id=0 / myEngine.engine=Node24JavaScriptEngine / all.ids=0 / all.hasMy=true
    execScriptFile.id=1 token=node-engine-... / execution.started /
    child_emit {"from":"child","childId":1} / execution.success

日志：.artifacts/node154-engines-threads/g1098b-engines-smoke-logcat.txt

### 4.2 官方示例「Node.js启动Rhino引擎与通信」

- 广播 assetPath 运行，Node 主脚本 main.js 启动（NodeEngineBridge pinned owner=:script engineId=0）；
- execScriptFile('./悬浮窗.js',{arguments:{serverEngineId:myEngine().id,title,content}}) 成功，子 Rhino 引擎 悬浮窗.js 打开且打印 execArgv = { serverEngineId: 0, title: '计时器', content: '初始化中...' }，说明 arguments 透传与相对路径修复都生效；
- 子引擎悬浮窗被 Node 父引擎 emit 更新（Rhino 侧 $events 收到），stopAll 可清理。

日志：.artifacts/node154-engines-threads/g1098c-sample1-node-rhino-logcat.txt（1097 上同结果：sample1-nodejs-rhino-logcat.txt）。

### 4.3 Rhino→Node 跨引擎双向（补充，覆盖示例 2 的桥部分）

- Rhino 侧 $engines.execScriptFile('./node-child.js',{arguments}) 起 Node 子引擎（engineId=1），子引擎读到 execArgv.serverEngineId；
- 子引擎 engines.getRunningEngines() 找到 Rhino 父引擎并 emit('node_ping')，Rhino $events.on('node_ping') 收到 {"ok":true,"from":"node","parentId":0,"childId":1}；
- 官方示例「Rhino中启动Node.js代码与通信」本体：跨引擎启动/emit 已验证；该示例最后一步 httpbin.org 请求在 K40 上 15 秒超时（外部网络），属示例依赖环境，不是桥问题。

日志：.artifacts/node154-engines-threads/rhino-node-ping-logcat.txt、g1098b-sample2-rhino-node-logcat.txt。

### 4.4 threads 冒烟（path 模式）

/sdcard/node154-engines/threads-result.json（THREADS_ALL_PASS）：

    t1.id=1 alive=true / t1.join=true / t1.result=42（同步函数 + 参数）
    t2.join=true result=7（async 函数 + await）
    t3.join=true error=Error: boom-154（异常栈回传）
    t4.join100=false waited=101 / t4.alive=true / t4.afterInterruptJoin=true（超时与 interrupt）
    shutDownAll=6 / a.join=true b.join=true
    currentThread.id=0 alive=true

日志：.artifacts/node154-engines-threads/threads-smoke-logcat.txt

### 4.5 本机 Node 24 harness（实现回归）

桌面 Node v24.16.0 上从 prelude 抽出 threads 模块跑 24 项断言：join/result/异步/异常/超时/interrupt/shutDownAll/currentThread/参数校验/未实现 API 报错全部 PASS（ALL_THREADS_CHECKS_PASS）。脚本：.artifacts/node154-threads-local/threads-test.js。

## 5. 变更文件

- modules/engine/src/main/assets/node24/prelude.js：engines.execScriptFile 相对路径修复；threads 模块真实实现（约 300 行，标记 task-154 threads start/end 便于维护）；
- modules/engine/src/main/java/com/stardust/autojs/engine/node24/*（协议/服务/客户端/子类，随工作区实现）；
- modules/engine/src/main/java/com/stardust/autojs/engine/Node24JavaScriptEngine.java（事件队列/emit）；
- modules/engine/src/main/java/org/autojs/source/engine/android/Node24HostBridge.java（kind=engines 路由）。

## 6. 已知限制 / 后续

- threads 不共享闭包：auto.js 旧脚本里靠闭包改外层变量/UI 的写法在 Node 引擎不适用；跨线程通信请用 SharedArrayBuffer/Atomics 或 engines 事件；
- Node console 输出在部分路由（Node 作为 Rhino 子引擎时）不进入 logcat GlobalConsole，只能从 App 控制台看；这不影响事件投递（4.1/4.3 有日志证据）；
- 官方示例 2 的外部 http 请求依赖网络可达性；桥与事件已用 4.3 的 ping 用例单独覆盖。

证据目录：.artifacts/node154-engines-threads/（result JSON、threads-result JSON、各 logcat）；.artifacts/node154-threads-local/（本机 harness）。
