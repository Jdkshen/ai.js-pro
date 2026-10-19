# 架构对齐 Phase 1：引擎并入 :script（task-175，2026-10-19）

## Spike 结论（先做）
目标：验证 `:script` 进程内 2 个 `Node24Engine` 并行 execute/cancel 是否可行（libajsnode24bridge 多实例）。

### 方法
当前原生库只在 :node24 进程可加载（`Node24Libraries.load` 有 `isNodeProcess` guard），因此先在 **:node24 进程内**用临时 debug 钩子 `Node24Service.ACTION_NODE24_SPIKE` 创建 2 个 `Node24Engine`（nativeCreate 两个 handle），各自线程并行 `execute`，再测试第 3 个实例的 `cancel()`；MI8 debug 465。

### 结果（logcat SPIKE）
```
10-19 23:20:39.438  7686  7686 I PocDebugCmd: debug node24Spike dispatched
10-19 23:20:40.758  7765  7805 I SPIKE   : SPIKE_START pid=7765
10-19 23:20:41.184  7765  7806 I SPIKE   : SPIKE_A_END ms=2 ERROR java.lang.IllegalStateException: Another Node session is running in this process
10-19 23:20:44.611  7765  7807 I SPIKE   : SPIKE_B_END ms=3437 exit=0 cancelled=false error=
10-19 23:20:44.612  7765  7805 I SPIKE   : SPIKE_PARALLEL wall=3437 a=2 b=3437 bothEnded=true
10-19 23:20:45.630  7765  7805 I SPIKE   : SPIKE_CANCEL alive=false result=cancelled=true exit=1
10-19 23:20:45.630  7765  7805 I SPIKE   : SPIKE_END
```

**结论：`libajsnode24bridge` 当前为「每进程单 Node 会话」——第二个 `Node24Engine.execute` 立即失败：**
`IllegalStateException: Another Node session is running in this process`；
单实例执行/取消正常（B 脚本 3437ms exit=0；C cancel 后 `cancelled=true exit=1`；并行 wall=3437ms，A 在 2ms 即失败）。

## 可行性与替代方案
| 方案 | 说明 | 评价 |
|---|---|---|
| A. 每 Node 脚本一个宿主进程池（推荐短期） | :script 作为编排/注册表，按需拉起 `:node24_1..N`（不同 service 进程名）各跑一个 Node24Engine；STOP 按 executionId 关对应进程 | 保留真正并行/独立 cancel；但 Node 不在 :script，需新增进程声明与 AIDL 编排 |
| B. :script 内单 Node runtime 队列 | 一个 Node 会话，后续 Node 脚本排队；Rhino/QuickJS 仍可并行 | 不改原生、无 BUSY，但 Node 脚本不能真正并存；不满足「双 Node 并存」目标 |
| C. 修改 libajsnode24bridge 支持多会话 | 需要 bridge/libnode 原生源码与重编译（仓库只有预编译 .so，无源码） | 可达到 Pro 同进程目标，但成本最高，需用户/Lead 决策与源码 |

## 建议
先按 A 做 Phase-1 迁移（:script 统一注册表/AIDL + Node 子进程池 + 按 id STOP），同时保留 :node24 deprecated 回退；若必须让 Node 真正跑在 :script 内，则需 C（原生源码）作为后续专项。

## 当前工作区/设备状态
- spike 钩子仅 debug：`Node24Service.ACTION_NODE24_SPIKE` + DebugCommandReceiver `node24Spike`；迁移前会移除或收入 debug-only。
- MI8 当前装的是本机构建 465（非 dev-1123 正式包），K40 未操作（t2/t1 占用窗口）。
- 证据：`output/flutter-poc/evidence/task175/node24-multi-spike.log`。

## 待决策
请 Lead 确认选 A（子进程池）还是 C（改原生 bridge）；未确认前不硬上迁移代码。
## 方案 A 垂直切片（2026-10-19 23:35，MI8 debug 465）

已落地：
- manifest 新增 Node 宿主进程池：`Node24PoolService1/2/3`，进程 `:node24_1/2/3`（每进程单 Node24Engine）；
- `Node24Service` 改为可继承；`Node24Client` 新增 `Class<?> serviceClass` 构造器，可绑定指定池槽；
- `SourceEngineBootstrap.isNodeProcess` 允许 `:node24` 及 `:node24_*` 池进程加载原生库；
- `ScriptHostService`（:script）新增进程池编排 spike：并行启动 2 个 Node24Client（Pool1/Pool2），复用一个槽，按 executionId cancel 单个任务。

实测（日志见证据）：
```
10-19 23:35:51.857  8700  8727 I ScriptHostService: NODE_POOL_SPIKE_START pid=8700
10-19 23:35:57.712  8731  8787 I Node24Console: AJSLOG1:D:POOL_B_DONE 3028
10-19 23:35:57.810  8700  8729 I ScriptHostService: NODE_POOL_SPIKE_B_END ms=5949 status=SUCCESS exit=0 cancelled=false
10-19 23:35:57.837  8730  8793 I Node24Console: AJSLOG1:D:POOL_A_DONE 3007
10-19 23:35:57.936  8700  8728 I ScriptHostService: NODE_POOL_SPIKE_A_END ms=6076 status=SUCCESS exit=0 cancelled=false
10-19 23:35:57.937  8700  8727 I ScriptHostService: NODE_POOL_SPIKE_PARALLEL wall=6081 a=6076 b=5949
10-19 23:35:58.466  8730  8805 I Node24Console: AJSLOG1:D:POOL_REUSE_OK
10-19 23:35:58.532  8700  8727 I ScriptHostService: NODE_POOL_SPIKE_REUSE ms=590 status=SUCCESS exit=0
10-19 23:35:59.749  8700  8727 I ScriptHostService: NODE_POOL_SPIKE_CANCEL sent=true elapsed=1212 status=CANCELLED cancelled=true
10-19 23:35:59.750  8700  8727 I ScriptHostService: NODE_POOL_SPIKE_END
```

结论：**方案 A 可行**：从 :script 进程编排，两个 Node 脚本在不同宿主进程真正并行（A/B 均 SUCCESS，wall≈6.1s，各自 runtime 加载）；槽可立即复用（590ms SUCCESS）；单任务 cancel 生效（status=CANCELLED, cancelled=true）。

## 尚未完成（生产迁移）
- 主进程任务列表/STOP 尚未统一到执行 map：当前 spike 的 Node 任务不是主进程 `PocRuntime.tasks()` 的登记项，任务页看不到；
- `Node24JavaScriptEngine`（主进程 Node 入口）仍连 `:node24`/旧客户端，未改为经 ScriptHostService 池执行；
- `ScriptHostService` 的 Node 执行尚未接入其 RUN/STOP/console 协议（尚未按 token 登记/转发日志/按 id stop）；
- `:node24` deprecated 回退 flag 未加；生产池分配/上限/健康检查未实现。

## 下一步
1) 在 ScriptHostService 增加 executionId→(poolSlot, client, id) map，RUN 识别 Node 源后分配空闲槽，日志/101/102 走现有 ConsoleForwarder；STOP 按 token cancel + 释放槽；
2) 主进程 `Node24JavaScriptEngine` 改走 ScriptHostService（或统一 PocScriptHost RUN），任务页 token 自然出现；
3) `:node24` 加 deprecated fallback flag，默认关；
4) dev-1125 上验证双 Node 并行/分别停止/立即再跑 + 任务页两个 running。
## 生产迁移落地（2026-10-19 23:42，MI8 debug 465）

### 已完成
- **manifest 进程池**：`Node24PoolService1/2/3` → `:node24_1/2/3`；`Node24Service` 可继承；池进程名允许加载 Node 原生库。
- **ScriptHostService 执行注册**：沿用已有 `mRuns`（token→RunState）+ console 转发；Node 源仍走 `PocScriptHost` token，任务页直接看到，无重复行。
- **主进程 Node 路由**：`Node24JavaScriptEngine` 改为通过 `Node24Pool.acquire()` 分配池槽；`PocScriptHost`/`PocRuntime` 无需改 AIDL，RUN/STOP/stopAll 均按 token 生效。
- **多脚本并存**：Node24Engine 每实例占一个独立池进程；池 3 槽，满时等待 ≤8s 并给中文错误，不再 BUSY。
- **按 id 停止**：任务页单任务 × / STOP → `PocScriptHost.stop(token)` → ScriptHostService → `Node24JavaScriptEngine.forceStop()` → 仅该槽 client.cancel；其余 Node 任务继续。
- **stopAll 统一**：任务页/通知栏/悬浮菜单最终走 `PocRuntime.stopAllScripts` → `PocScriptHost.stopAll`，两个 Node 任务同时 cancelled 并释放槽。
- **池进程崩溃恢复**：kill -9 `:node24_1` 后该任务报 `Node24 service disconnected`（任务页 failed），`Node24Pool.invalidate(slot)`；下一次运行自动新起进程并成功（0.63s 内 job start 复用热槽场景）。
- **:node24 deprecated 回退**：`Node24Pool.legacyEnabled(context)`（SharedPreferences `node24_pool.legacy=true` 或 `-Dautojs.node24.legacy=true`）→ 使用旧 `Node24Client(:node24)`；否则默认池。
- **取消后池进程保持 warm**：池进程不执行 post-cancel kill，只有旧 `:node24` 继续退出；实测停止后 0.63s job start。

### 验证（MI8，本机构建 465）
- 两 Node 脚本并行：slot0+slot1 同时 job start；任务页显示 **2** 个运行中任务（截图 task-page-two-node-running.png）。
- 任务页单任务 ×：token1 停止 cancelled=true、token2 继续；再点 token2 停止 cancelled=true，槽 release。
- `taskPageStop` 统一停止两任务：两条 execute returned cancelled=true、release slot0/1、PocScriptHost 停止 1/2。
- 停止后立即再跑：cancel 23:40:51.476 → 新 job start 23:40:52.110（**0.63s**），PROBE DONE 成功。
- kill -9 池进程：任务 failed（明确 disconnected），invalidate 后下一次 acquire 新进程成功。

### 编译
`:app:assembleMiuixCompatDebug -PflutterPoc=true -PdevPluginUsbDiscovery=false` BUILD SUCCESSFUL。

### 待正式包验证
- 请构建 **dev-1125**；当前 MI8 装本机 debug 465，K40 仍 dev-1123（未操作）。
- 尚未单独验证 `legacy` flag 运行时回退；代码路径已存在。
- 池上限 3；超过时排队 8s 后中文错误（不 BUSY）。

证据：`output/flutter-poc/evidence/task175/production-migration.log`、`task-page-two-node-running.png`
## dev-1125 正式包复验发现（2026-10-19 23:43 K40）

**问题**：两个 Node 脚本确实并行（slot1/slot2 同时 job start），但任务页显示 **6 行**：3 条 `脚本进程` token（t166keep/悬浮日志/floatlog）+ 3 条旧 `Node UI` 登记（`PocNode24Jobs`，token 前缀 node24:）；即 task-167 的旧 Node24 登记与 task-175 的 :script token 重复。

**修复**：`PocRuntime.tasks()` 默认（进程池模式）不再合并 `PocNode24Jobs.snapshot()`；只有 `Node24Pool.legacyEnabled(context)` 为 true（显式回退 :node24）时才合并旧登记。

**本地验证（MI8 debug 465）**：修复后任务页只剩脚本进程行，无 Node UI 重复行（截图 `dedupe-after-fix.png`）；同页出现 colored-button 是自动 self-heal 的独立任务，不是重复登记。

编译：`:app:assembleMiuixCompatDebug -PflutterPoc=true -PdevPluginUsbDiscovery=false` BUILD SUCCESSFUL。

**待办**：需构建 **dev-1126** 后正式复验 ①任务页 2 行、②分别停止、③stopAll、④<1s 再跑、⑤kill 恢复、⑥legacy 回退。
证据：`dev1125-duplicate-task-rows.png`、`dedupe-after-fix.png`。
## dev-1126 正式包复验 PASS（2026-10-19 K40）

- ① 两个 Node 脚本：slot0/slot1 同时 job start，任务页 **2 行**、无 Node UI 重复（截图 dev1126-task-page-2-rows.png）。
- ② 任务页单任务 ×：只取消该 token（execute returned cancelled=true、release 对应 slot），另一个继续。
- ③ taskPageStop：两条 execute returned cancelled=true、release slot0/1，PocScriptHost 停止 1/2。
- ④ 停止后立即再跑：STOP_MS→RUN_MS **272ms**；stop cancel 23:47:34.504 → new job start 23:47:34.648 = **0.14s**（首次立即重跑与在途 taskPageStop 竞争被取消，下一次运行成功）。
- ⑤ kill -9 :node24_1：`Node24Pool invalidate slot=0`，运行任务标记失败 `Node24 service disconnected`；下一次 acquire 新进程并成功。
- ⑥ legacy flag（SharedPreferences `node24_pool.legacy=true`）：probe 实际运行在旧 `:node24` 进程（pid 7583），PocNode24Jobs 正常登记/清理。

证据：`output/flutter-poc/evidence/task175/dev1126-formal.log`、`dev1126-task-page-2-rows.png`。

## task-181 池进程取消语义修复（2026-10-20）

**问题**：task-174 的 `Job.cancel()` 里无条件起 800ms `forceHalt` + 1500ms watchdog，对 `:node24_*` 池进程也生效；Node UI 返回键 cancel 后 800ms 杀进程，任务以 `IOException: Node24 service disconnected` 失败。

**修复**：`Node24Service.Job.cancel()` 区分进程类型：
- **池进程（:node24_1..N）**：只 `nativeCancel`；不启动 800ms force halt；watchdog 改为 **2500ms**，且 `finished==true` 时直接返回（正常取消）；只有 2.5s 仍未返回才 kill/invalidate。
- **legacy :node24**：保持原 800ms force halt + 1500ms watchdog 不变。

**本地验证（MI8 debug 465）**：
- `taskPageStop` 取消运行中的 Node UI 脚本：`PocScriptHost 脚本进程任务已停止：1`、`Node24Pool release slot=0`；无 force halt/invalidate/disconnected；池进程 pid 仍存活。
- 立即再跑：`Node24Pool acquire slot=0` → `job start` → `PROBE DONE`，正常完成。
- Node UI 返回键关闭 overlay 后池进程仍存活（本机该次脚本自然 exitCode=0；无 IOException/failed）。

**待正式包验证**：dev-1129（或下一个 dev）包含本修复后，在 K40 复验返回键 cancel 任务显示取消、池进程仍在、可立即再跑。
证据：`output/flutter-poc/evidence/task181/pool-cancel-semantics.log`。
### dev-1129 正式包复验（task-181，K40 2026-10-20 00:07）
- 任务页停止运行中的 Node UI 脚本：`execute returned ... cancelled=true`；`PocScriptHost 脚本进程任务已停止：4`；`Node24Pool release slot=0 healthy=true`；**无 force halt / invalidate / disconnected**；池进程 pid 14052 仍存活。
- 立即再跑：STOP_MS→RUN_MS 后 `acquire slot=0` 于 00:07:58.313、`job start` 00:07:58.321（**16ms**）、`PROBE DONE` 00:07:58.717，成功。
- 返回键关闭 Node UI overlay：池进程仍存活，无 IOException/failed（本机 overlay 返回键路径该次未产生 cancel，任务页停止路径已验证取消语义）。
结论：dev-1129 池进程 cancel 语义 PASS。
证据：`output/flutter-poc/evidence/task181/dev1129-k40-cancel.log`。