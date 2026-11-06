# 再看 `.4`：证伪「升级清空结果」+ gadget partial 归因上游（dev-1379）

> 用户：「再看看」。做法：重扫 `.4`（已升 **dev-1378**）找出新文档与新问题，逐条取证并收口。

## 一、`.4` 新出现的东西

| 项 | 内容 |
|---|---|
| 新主文档 | `MCP-AIjsPro-功能与遗留问题-dev1378.md`（35,296 B）——第九节 dev-1376 复测、第十节 dev-1378 复测 |
| 他们的结论 | **第十节确认我们的 16K 修复生效**：`V8` 类导出 **47,352 字节**（旧 16,384），`V8Object`/`UiObject`/`ScriptService` 同批重反都完整 |
| 他们的新疑问 | 第九节：「dev-1374 → dev-1376 更新后 `jobs/*.result.json` 全消失」，怀疑升级会清空 job 目录，建议纳入保留策略或在 `get_status` 报一句 |
| 其他 | `autojs_crack/脱壳验证报告.md`、`反编译/{导航,索引}.md`（他们的逆向成果，无新 MCP 诉求；只有已知的 `JADX未找到类` 限制） |

## 二、证伪：「升级清空结果」其实是保留策略淘汰

**Lead 在 `.4` 上直接取证**（不是推测）：

```text
apk_job_list → count=67，其中 evicted(墓碑)=32
  样本：{"code":"JOB_EVICTED","reason":"retentionLimit","operation":"apk_decompile_class",...}
  retention = {"windowMillis":21600000,"maxRetainedFinished":64,"maxActive":4,"maxResultBytes":25165824,"maxTombstones":256}
  states = {SUCCEEDED:62, SUCCEEDED_WITH_WARNINGS:2, RUNNING:2, QUEUED:1}   ← 正好 64 条 finished + 3 活跃
```

- 结论：**是 6 小时窗口 / 最多 64 条已完成 的淘汰**（超出即删输出缓存并留墓碑），**与升级无关**；只有 `SERVICE_RESTARTED` 才是工具进程重启丢失。
- 反证：`ToolkitIo.version` 是稳定 SHA-256，store key（`filesDir/apk-toolkit/<hash16>`）不会因版本变化；代码里也**没有任何"升级清库"逻辑**。

## 三、本轮改动（dev-1379）

| 项 | 内容（K40 实测）|
|---|---|
| `get_status.jobs` | `{total:26, active:0, finished:26, evictedCount:3, retention{windowMillis:21600000,maxRetainedFinished:64,maxActive:4,maxResultBytes:25165824}, lastEviction{jobId,operation:"apk_component_install",reason:"retentionWindow",recordedAt}, note:"结果只保留 6 小时或最多 64 条…"}` |
| `apk_job_list` | 新增 `retentionNote` + `nextActions`（「要留档：结果取回后立刻写自己的目录」；点明 `JOB_EVICTED ≠ SERVICE_RESTARTED`）|
| 淘汰任务报错 | 「任务结果已被淘汰（JOB_EVICTED）：…；原因=超出保留窗口（6 小时）或数量上限（64 条已完成任务）（retentionLimit）；输出缓存已删除，无法再读取；请重新提交任务」 |
| gadget `partial` 归因 | `partialReasons[]` 新增 `upstream: true` 与 `impact: "static-check-only"`（K40 实测 native/`ELF_PT_LOAD_ALIGNMENT_16K` 项正是如此）；`note` 追加「来自上游 Frida Gadget 预编译库的 PT_LOAD 页对齐…要消除需替换为 16 KiB 对齐的 Gadget 构建」|
| 文档 | Guide 两块（任务保留策略 / `detail=summary` 与 `partialReasons`）+ Playbook 附录 D 与工具节，共 36 行 |

### 为什么说是"上游"（代码级证据）

- `ToolkitGadget.kt:62` 取 `gadget/<abi>/libfrida-gadget.so`，`:108` **原样写入** `lib/<abi>/libaijsgadget.so` —— 全程无 ELF patch。
- 组件清单（LAN `components/frida-gadget-arm64-v1.json`）自述 `"license":"LGPL-2.1-or-later (unmodified official Gadget libraries…)"`、`"source":"https://github.com/frida/frida/releases/tag/17.25.2"`。
- `.4` 的云镜样本注入后**实测能加载**（他们自己的 `frida5` 跑通），所以这是**静态校验告警**而非加载失败。

## 四、交付

| 项 | 值 |
|---|---|
| 发布 | **LAN dev-1379**：SHA `035443f87e89ba0c…`，手机拉 `update.json` HTTP 200 |
| 单测 | MCP 全过（dev-1379）|
| `.4` 文档 | 两份主文档追加「dev-1379 增量」并推回（sha256 一致）|
| 样本 | `output/samples/云镜快照样本-原件.apk`（7.8MB，sha256 `ffe1365a…`）|

## 五、仍未做

1. **`.4` 上的 32 条墓碑只能"解释"，不能被"找回"**——输出缓存已删，需重新提交任务（文档已写明）。
2. `#15` `Packages.<类>.<静态方法>`/`.class`（Rhino）—— 仍不支持，替代方案 `java.lang.Class.forName`；要真做需绕开 top-level package（dev-1377 已证明风险）。
3. Rhino 整数精度（`valueOf(42)` → `42.0`）与 Rhino `Array.set(int[],0,5)`。
4. gadget `.so` 的 16 KiB 对齐**未修**（属上游产物；要修需替换为 16 KiB 对齐的 Gadget 构建并更新 LAN 组件）。
5. `apk_job_result` 显式 `maxChars` 的 schema 上限仍是 200000（默认读取已 1 MiB，够用）。

---

## ⚠️ 更正（2026-11-06 当晚，由队友 ELF 调研推翻）

本报告曾把 `apk_inject_gadget` 的 `verification.status=partial` 归因成「**注入的 gadget `.so` 不满足 16 KiB 页对齐**」。**这个归因是错的。**

`docs/reports/2026-11-06_gadget-ELF-16KiB对齐调研.md` 逐段解析 ELF 后证明：

| 结论 | 证据 |
|---|---|
| 那 5 条 `ELF_PT_LOAD_ALIGNMENT_16K` **来自目标 APK 自带的库** | `lib/arm64-v8a/libroot_automator.so`（段 2/3/4，`p_align=0x1000`，段 3/4 还不同余）+ `lib/arm64-v8a/libyj_aj_02_309.so`（段 2/3，`p_align=0x4000` 但不同余）|
| **注入前后完全一致** | 对**未注入的**原包 `yj-orig.apk`（sha256 `ffe1365a…`）单独 `apk_verify` → **同样 5 条** |
| **注入的 Gadget 没有新增告警** | `libaijsgadget.so`（只注 arm64）3 个 `PT_LOAD` 全部 `p_align=0x4000` 且同余 |
| 上游 Gadget 的差异只在 **32 位** | arm64-v8a / x86_64：3/3 通过；armeabi-v7a / x86：3/3 失败（`p_align=0x1000`）|
| 两台测试机都**不是** 16 KiB 页设备 | K40 `getconf PAGE_SIZE=4096`、`.4`（SDK 37）也 `=4096` |

**为什么会误判**：`partialReasons` 当时只给了 `check=native` + 错误码，没给**具体是哪个文件**；我按"5 条 ≈ 4 ABI + 1"猜成了 gadget，并用它写了 note 与文档。**教训**：结构化诊断必须带 `entry`，否则"归因"就是猜。

处置：① `partialReasons[]` 增加 `entry`/`scope(target|injected)`，`upstream:true` 只给注入的 Gadget；② note 与随包文档改写；③ 不改 Gadget 组件（官方 64 位已合规）。
