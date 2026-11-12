# flutter-ui 代码优化调研（W4 · Phase 1 只读）

> 范围：`flutter-ui/lib/**`（本轮**未改任何代码** ✓）。基线：`docs/reports/2026-11-12_全仓代码体检与优化流程（Lead）.md`。
> 度量：`lib/` 24 个 dart / **15,660 行**；`LocalBridge.call(` **126** 次/13 文件；`catch (` **128**；`setState(()` **290**；`ValueKey(` **98**；测试里 `readAsStringSync` **21 处/14 文件**。

## 按收益/风险排序（上限 8 条）

### 1. `pluginRow` 是死代码（≈145 行）
- **目标**：删掉已被网格取代的旧行渲染器。
- **证据**：`catalog_page.dart:1304` 只有**定义**、全仓无调用（grep `pluginRow(` = 1 命中 ✓）；插件列表在 task-142 已改为 `pluginCard` 网格（`catalog_page.dart:1964` 调用 ✓）。
- **影响面/改动量**：1 文件、-145 行；可能连带少量只被它引用的私有 helper（需一并核）。
- **风险/回退**：低（无引用）；`git revert` 即可。
- **验证**：`flutter analyze`（0 issue）+ `flutter test`（58 项）+ `grep -c 'pluginRow('` 应为 0。
- **工作量**：0.5h。

### 2. 业务规则写在 `State` 里 ⇒ 只能靠「读源码字符串」测试
- **目标**：把纯规则抽到 `lib/*_logic.dart`，使其可被真实单测覆盖。
- **证据**：`pluginStateLabel`/`pluginHasUpdate`/`pluginLooksInstalled`/`pluginManifestState`/`categoryTail`/`resourceEngineLabel` 都在 `_CatalogPageState` 内（`catalog_page.dart:112/341/519/526/…`）；同类判断在 `project_page.dart`/`browser.dart` 亦有；测试里 **21 处 `readAsStringSync`**（14 文件）—— 全部是「字符串包含」断言 ✓。
- **影响面/改动量**：新增 2–3 个 logic 文件 + 就地改为调用；测试从「源码钉」升级为「行为断言」。
- **风险/回退**：中低（纯搬迁、无行为变化）；逐个函数迁、每步跑测试。
- **验证**：新增 `test/plugin_logic_test.dart` 直接断言（如 `pluginHasUpdate(state:'update')==true`）；`flutter test` 全绿。
- **工作量**：3–5h。

### 3. 桥调用样板重复（126 次）
- **目标**：统一「调用桥 → 可读失败 → 不静默」，消灭 9 套 `message/notify` 助手。
- **证据**：`LocalBridge.call(` **126 次/13 文件**、`catch (` **128**；失败提示助手 `message(` **86 次/9 文件**（`notify(` 仅 catalog 1 处）。同一「失败要显式可见」的原则被反复实现。
- **影响面/改动量**：抽 `BridgeCall.run(context, method, args, {onError, timeout})`；**分批**替换（每文件一轮）。
- **风险/回退**：中（126 处替换、错误文案可能被改变）；按文件小步 + 每步跑测试。
- **验证**：替换后 `flutter test` 全绿 + 抽查 3 个失败路径（超时/未知方法/返回非 Map）仍给出可读文案。
- **工作量**：6–10h。

### 4. 同一实体的多套渲染（字段改动要改 2–4 处）
- **目标**：`EntityCard(entity, {dense})` 承载行/网格两态；详情用共享 `detailRows(entity)`。
- **证据**：插件：`pluginCard`（网格 ✓）+ 详情对话框（`catalog_page.dart:1449` 起）+（已死的 `pluginRow`）；资源：`resourceCard` + 详情对话框；任务：`row(...)` + `details(...)`。近几轮我为「大小/需重启/更新说明/状态行」确实在**卡片与详情两处**重复改动（task-141/144/146）。
- **影响面/改动量**：`catalog_page.dart` 为主 + `browser.dart`/`task_page.dart` 参照；改动大但机械化。
- **风险/回退**：中高（触碰布局与 **98 个 `ValueKey`** 的测试面）；先做「详情行」共用（无需动布局）再谈卡片统一。
- **验证**：`flutter test` + 真机截图对比（网格/列表/详情三处字段一致）。
- **工作量**：8–12h。

### 5. 页面激活/加载/代次样板（6 文件、35 处）
- **目标**：`AsyncPageState<T>`（进入时加载 + 代次防竞态 + 节流 + 失败态）替代各页手写。
- **证据**：`revision|requestId` **35 处/6 文件**（catalog 单文件就有 `requestId`/`importRequestId`/`pluginStateRequestId` 三个计数器 ✓）；`setState(()` **290 次**。
- **影响面/改动量**：先在**一个**页面（如 `task_page.dart`）试点，再推广。
- **风险/回退**：中（时序/竞态语义敏感）；试点页保留旧实现于分支，出问题回退。
- **验证**：试点页的既有测试全绿 + 真机快速进出/切页 20 次无「旧结果覆盖新结果」。
- **工作量**：6–8h（试点 2h + 推广 4–6h）。

### 6. `god` 文件（4 个占全仓 ≈46%）
- **目标**：按职责拆文件（catalog：插件 / 示例 / 详情；project：表单 / 打包卡）。
- **证据**：`catalog_page.dart` 1914、`project_page.dart` 1872、`browser.dart` 1809、`utility_pages.dart` 1546 ⇒ 7,141/15,660 ≈ 46%。
- **影响面/改动量**：纯搬迁 + import/`part` 调整；**但 21 处 `readAsStringSync` 钉子按文件路径+字面量读源码** ⇒ 拆文件会成批弄红钉子（本轮已遇到 2 次）。
- **风险/回退**：中（钉子成批失败、需同步迁移）；一文件一拆、每步全量测试。
- **验证**：`flutter test` 全绿 + `wc -l` 单文件 < 800 行。
- **工作量**：4–6h/文件。

### 7. 降级/失败文案散落
- **目标**：统一「降级行」组件 + 文案表。
- **证据**：`插件目录不可用：…`、`大小构成：暂不可用（…）`、`下载失败：…`、`打包提示：不可用（…）` 分别写在 catalog/`project_page` 的不同位置（本轮新增 3 条）。
- **影响面/改动量**：抽 `DegradedLine(text)` + 一处文案常量；顺带利于 `ui-contrast`/文案审计。
- **风险/回退**：低（样式与文案不变）。
- **验证**：`flutter test` + 真机触发三条失败路径各一次。
- **工作量**：2–3h。

### 8. 测试与实现文件强耦合
- **目标**：源码钉只保留「接口契约」（桥名/文案关键句），其余改为**行为断言**。
- **证据**：21 处 `readAsStringSync`（14 文件）；本轮 task-150/156 各有一次「实现改了 ⇒ 旧钉子成批红」。
- **影响面/改动量**：随第 2 条一起做（规则抽成纯函数后，钉子自然变行为断言）。
- **风险/回退**：低。
- **验证**：`flutter test` 全绿；故意改一处文案，确认**行为测试**仍绿、只有契约钉红。
- **工作量**：3–4h。

## 汇总
- **总工作量**：≈33–52h（不含真机）。
- **建议批次**：**P0**（1、7：低风险、当天可验收）→ **P1**（2、8：让测试有牙）→ **P2**（3、5：样板收敛）→ **P3**（4、6：结构重排，风险最高、最后做）。
- **不报**：`withOpacity`（已清 ✓）、命名/格式、纯观感（信息密度之类）。
- **本轮边界**：只读调研；所有数字均为**可复核**的 grep/行数指标（命令见各条「验证」）。
