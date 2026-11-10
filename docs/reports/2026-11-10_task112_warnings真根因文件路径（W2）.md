# task-112：warnings 恒空的真根因（文件读取路径）+ 修复（W2）

## 真根因（钉死，file:line）
页面轮询走的是**文件**路径：
- PocPackaging.status() 不带 id 时：先看内存 active（:654），再落到 latestJob(...)（文件）（:659）；
- 而 runBuild 的 finally 里 ACTIVE.compareAndSet(job.id, null)（:573）在构建结束时就清空 active ⇒ 任务完成后页面轮询（只传 path）**必然**走文件路径；
- 文件路径的唯一入口是 Job.from(JSONObject)（:1009），它还原了 status/stage/error/sha256/size/abis/path/events，**唯独没有还原 warnings** ⇒ 返回的 Job warnings 列表为空 ⇒ json() 写出 warnings:[] ✓ —— 与真机 JSON（status:success、size 有、warnings:[]）逐字吻合，也解释了「双 ABI 对照同样空」。

## 逐条回答 4 个查点
1. job JSON 何时写盘：PocPackaging:574（runBuild 的 finally 里），**在 warnings 附加与 job.success(...) 之后** ⇒ 文件里其实**有** warnings ⇒ **不是**「快照早于 warnings」。
2. projectBuildStatus 读哪一份：带 id → 内存 JOBS.get(id)（:642），没有才 readJob（:646）；**不带 id**（页面就是这么轮的）→ 内存 active（:654），active 被清后 → latestJob（:659）。两条文件路径都经 Job.from。
3. json() 里 warnings 是否所有返回路径都写：是。json() 只有一条组装路径，:1084 value.put("warnings", new JSONArray(warnings)) ⇒ 写出侧无问题。
4. 确认：是「**读取时丢失**」——内存路径有、文件路径丢（from() 不还原）。因此修法是在 from() 里还原（比「warnings 之后重新持久化」更根本；写入侧本来就是对的）。

## 改动（1 处功能 + 契约钉扩展）
1. PocPackaging.Job.from(...)：新增还原 warnings（跳空白、上限 50，与写入侧 warnings(List) 语义一致）——readJob/latestJob 共用本方法，一处修好两条文件路径。
2. PackagingWarningsContractTest 增加第 4 项 persistedJobRestoresWarningsSoBothReadPathsCarryThem，钉住：① from() 里必须出现 optJSONArray + warnings + job.warnings.add；② json() 必须写 new JSONArray(warnings)；③ 两条读路径仍在（readJob( / latestJob( ）且 ACTIVE.compareAndSet(job.id, null) 这条依赖仍在（解释为什么轮询会落到文件路径）。task-111 的 3 项契约仍在同文件里。

## 验证方式
· 离线 kotlinc + JUnit：PackagingWarningsContractTest ⇒ **OK (4 tests)**（本轮只重跑这个契约类，它覆盖 task-111 + task-112 的全部钉子）。
· 按 Lead 要求**没跑 gradle**；未跑构建/装机/发布/git。
· 真机两态（Lead）：removeOcr 工程 ⇒ warnings 至少含 OCR 文案；双 ABI 工程 ⇒ 至少含 abis: 那条；默认工程 ⇒ [] 且 UI 该块隐藏；UI「打包提示（N）」出现。

## 未验证边界
1. 没有真机：由 Lead 复验（本轮修的是**文件读取**路径，内存路径本来就有 ⇒ 只有「任务结束后的轮询」能暴露，正好是实测形态）。
2. from() 按写入侧同样截断到 50 条（与内存语义一致，不是新限制）。
3. 未改写入侧（persist 本来就在 warnings 之后，:574）；若将来把 persist 提前，json() 仍是唯一组装点，语义不变。
4. task-111 的顺序前置与读时刷新保留（内存路径的窗口仍需要它们）；本轮只在文件路径上补回丢失的字段。
