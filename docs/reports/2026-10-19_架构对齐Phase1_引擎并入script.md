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