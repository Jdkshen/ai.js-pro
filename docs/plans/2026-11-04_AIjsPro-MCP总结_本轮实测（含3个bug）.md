<!-- 来源：手机 /storage/emulated/0/脚本/AI.js-Pro-MCP-总结_本轮实测.md（6,734 B / 198 行，经 MCP 从 192.168.10.4:8765 读取）。原文未改动。 -->

# AI.js Pro MCP —— 本轮（新工具）实测总结

> 实测时间：2026-11-04 22:00 ~ 23:30
> 设备：Xiaomi HyperOS **V816 / OS4.0.3.0.XPLCNXM**（arm64-v8a）
> 后端身份：`uid=2000(shell)`（shizuku）· `context=u:r:shell:s0` · app 进程 `com.jdkshen.aijspro`
> 被测对象：`com.sonic.topography.wallpaper`（云镜 VMP 保护）与 `libliuyun.so`
> MCP 组件版本：frida **17.25.2** · radare2/r2ghidra/capstone **5.0.6** · Node **24.22.0** · apksig 8.6.1

---

## 一、新工具实测裁决（12 个）

| 工具 | 实测结果 | 裁决 |
|---|---|---|
| `native_session_open` | 常驻 30 min · **默认 `bin.relocs.apply=true`** · 给出确切命令行 · maxSessions=4 | **★★★★★** |
| `native_session_query` | ★ **一次就突破了 MCP 段边界**，读到 `.data.rel.ro` 原始字节 | **★★★★★** |
| `native_session_plan` | 首次 `aaa;Ps <proj>`，后续仅 `Po <proj>`（**不重跑 aaa**） | **★★★★** |
| `native_session_list` | 会话/配额/TTL/projectExists/runnerConfigured 全披露 | **★★★★** |
| `native_analysis_open` | TTL **6 小时** · maxFunctions 4096 · **`analysisOnly=true` 防线** | **★★★★** |
| `apk_read_bytes` | 独立代码路径读出同批字节；**暴露「磁盘 fnPtr=0」关键事实** | **★★★★** |
| `evidence_export` | **claims 五级分级** + 完整解压+哈希复核（failures=[]） | **★★★★★** |
| `shell_capabilities` | 逐命令 available/unsupported/alternatives —— **本项目 4 大坑的官方答案** | **★★★** |
| `crash_buffer_semantics` | `sigkillNotCrash=true` + 完整验证协议（时间窗+PID） | **★★★** |
| `shell_classify_failure` | 精准判 `inaccessible` + 提示「查 backend 与 PATH」 | **★★★** |
| `native_session_close` / `native_analysis_close` 等 | 未逐一跑（接口一致） | 待用 |
| **`node_script_command`** | ✗ **Internal error（Kotlin SDK 异常栈）** | **✗ 真 bug** |

---

## 二、三个真 bug（可用步骤复现）

### Bug 1 ✗ `node_script_command` ⇒ Internal error

**调用：**
```json
{"scriptPath": "/storage/emulated/0/脚本/test.js",
 "args": "--out /storage/emulated/0/脚本/a.txt"}
```
**返回：**
```
[io.modelcontextprotocol.kotlin.sdk.types.McpException] Internal error
McpException: Internal error
  at io.modelcontextprotocol.kotlin.sdk.shared.Protocol.onResponse(...)
  at io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport.performSend(...)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(...)
```
**影响**：12 个新工具里**唯一坏的**；其余 11 个全部正常。
**猜测**：`args` 参数处理时抛 NPE/IllegalState（很可能对 `args` 做分割/引号包裹时空引用）。
**建议**：`args` 允许为空/null，或返回结构化错误而非异常栈。

### Bug 2 ✗ `apk_workspace_gc` 死锁（配额永满）

**现象链：**
```
1. artifact_open 报「最多保留 16 个 APK 工作区，当前 16 个」
   ⇒ 提示：先 apk_workspace_inventory，再用 apk_workspace_gc(dryRun=true) 预览
2. 照做 ⇒ apk_workspace_gc(dryRun=true) ⇒ **wouldDeleteCount = 0** ✗
   ⇒ 原因：默认保护 `unknown_owner` + `unknown_client`（旧记录 owner 全为 null）
3. 且大量工作区被 `edit_session` 保护 ⇒ 即便加 includeUnknownOwner=true 仍删不动
4. ⇒ **gc 永远无法回收到 ≤16** ⇒ artifact_open 永久打不开（死锁）
5. 唯一出路：手动 `apk_workspace_delete` 按 ID 逐个删（本次删了 11 个才腾出空间）
```
**根因两条：**
- `workspace.json` 写入方**未落 owner/clientId** ⇒ inventory 里全为 null ⇒ gc 只能靠 unknown_owner 兜底 ⇒ 保护面被放大
- 报错信息给的建议（gc dryRun）**无法真正解决问题**，用户会原地打转
**建议：**
1. `artifact_open` 报错时**直接给出可执行命令**（含 `includeUnknownOwner=true` + `apk_workspace_delete` 指引）
2. 对 `expired` + `pendingCleanup=true` 的工作区**放宽 unknown_owner 保护**
3. **提供释放 `edit_session` 的入口**（当前缺失）
4. `clientId` 由服务端**自动落盘**，不要依赖调用方

### Bug 3 ✗ Frida 网络路径被 SELinux 静默 DROP

**现象：**
```
1. apk_inject_gadget 成功 ⇒ 输出 APK 已签名（CN=AI.js Pro）⇒ 装 + 启动 ⇒ 27042 确实 LISTEN
   /proc/net/tcp: 0100007F:69A2 state=0A (LISTEN) ✓
2. 但无论用 MCP 的 frida_status（app 进程）还是用 shizuku 跑 frida CLI：
   connect 127.0.0.1:27042: Operation was cancelled  (timedOut=true)
3. 决定性证据（/proc/<pid>/net/tcp）：
   0100007F:8A88 → 0100007F:69A2  state=**02 (SYN_SENT)**  ← ★ 卡在 SYN_SENT
```
**判读**：若是防火墙**拒绝**会收到 RST（state 变 CLOSE）；现在是 **SYN 被静默丢弃** ⇒ **SELinux DROP**（跨 UID loopback 默认禁止）⇒ 换 UID 也救不了，**这条网络路径结构性不通**。
**建议**：
1. 文档/message 里**明确标注**「免 Root Gadget 的 listen 模式在跨 UID 时可能被 SELinux 拦」
2. 引导用户改用 **Gadget `interaction.type="script"`**（不碰网络，启动即加载脚本）
3. 或提供「把脚本交给 Gadget 配置」的一键路径

**附带发现（有价值的正确设计）：** `aijs-frida` 的调用协议是
`aijs-frida <request.json>`，请求形如
`{"operation":"status","endpoint":"127.0.0.1:27042","token":"..."}`
——格式正确时它会正确回显 `operation`（本次已校验通过）。

---

## 三、工具类目变化对撞（上次 → 本次）

```
类目          上次  →  现在   变化
status          3  →   3     —
script         11  →  11     —
workspace      10  →  10     —
engine          5  →   5     —
device         24  →  24     —
overlay         2  →   2     —
asset           5  →   5     —
toolkit        87  →  87     —
★ terminal      7  →  **11**  **+4**
★ reverse      33  →  **38**  **+5**
⇒ 合计新增 **9 个工具**
```

### 本轮新增的 4 个 terminal 工具 —— **全部冲着实际痛点**

| 工具 | 解决的坑 | 本项目是否踩过 |
|---|---|---|
| `shell_capabilities` | grep 无 `\b`/`\s`；cat 无 `-n`/`-A`；无 zip/jar/7z；python 坏；node argv 语义 | **全部踩过 ✓** |
| `shell_classify_failure` | `java: not found` 类误判（先怀疑能力缺失） | **踩过 ✓**（漏 export PATH） |
| `node_script_command` | `node -e` 双重转义；argv[1] 误当参数 | **踩过 3 次 ✓** |
| `crash_buffer_semantics` | 清 crash buffer 后仍读到旧崩溃 | **踩过 ✓** |

### 新增 `rootStability` 字段（治路径漂移）

```json
"rootStability": {
  "path": "/storage/emulated/0/脚本",
  "previousPath": null, "changedThisProcess": false,
  "observations": 1, "stableRoot": true,
  "note": "本进程内 root 未变；若脚本引用的绝对路径被外部移动，仍会失效
           ——用相对路径或 list_scripts 重新确认",
  "scope": "current-process"
}
```
> 本项目踩过：路径双重拼接 / 写错目录 / `/tmp` 不可写 / FUSE 路径喂 install ⇒ 该字段正好覆盖这一类。

---

## 四、值得称赞的设计（本轮确认）

```
★★★★ ① `native_session_open` 默认 relocsApply=true
   ⇒ 把「必须 -e bin.relocs.apply=true」这条经验【固化成默认】✓
   ⇒ 本项目正是靠它才第一次读到 .data.rel.ro 的真实字节

★★★★ ② `native_analysis_*` 的 analysisOnly=true / buildSafe=false
   ⇒ 明确「分析态不得流入构建产物或回写源文件」，防线干净

★★★★ ③ `evidence_export` 的证据五级分级
   documented < static_inferred < static_confirmed
              < runtime_confirmed < post_change_verified
   ⇒ 把「我觉得」升级成「可核查」；且写出后自动做完整解压+哈希复核

★★★ ④ 大量「诚实的限制声明」
   · fingerprint: fullApkid=false · executesTarget=false
   · engineDiagnostics: behaviorStatus 保持 unverified（没跑就说没跑）
   · nodePool: busy=null 明说「不能当成 0 个空闲」
   · apk_native_*: 未识别指令明确标出
   ⇒ 这类「不夸大」的措辞对逆向判断非常重要

★★★ ⑤ `shell_capabilities` 给 alternatives 而不是只报错
   ⇒ 每个不可用能力都配了替代写法（file_search_text / sed -n / zip_* / node 脚本）
```

---

## 五、遗留/待改进清单

| # | 项 | 类型 |
|---|---|---|
| 1 | `node_script_command` Internal error | ✗ bug |
| 2 | `apk_workspace_gc` 死锁 + 建议不可执行 | ✗ bug |
| 3 | Frida listen 模式跨 UID 被 SELinux 拦，未在文档提示 | ✗ 文档 |
| 4 | `workspace.json` 不落 owner/clientId | ✗ 设计 |
| 5 | 缺 `edit_session` 释放入口 | ✗ 缺功能 |
| 6 | `native_session_close` / `native_analysis_*` 未逐一实测 | ⚠ 待验 |

---

## 六、本项目对 MCP 的依赖与受益

```
★★★★ 最大受益：native_session_*（常驻 r2 + 默认 relocs）
   ⇒ 本项目 VMP 逆向【从"推断"到"字节级实证"】的关键一步

★★★★ 第二大受益：apk_read_bytes + apk_native_list_items
   ⇒ 提供【独立代码路径】做交叉验证（r2 / MCP字节 / MCP节表 三方一致）

★★★ evidence_export ⇒ 交接形态升级（可核查）

★ 未受益（本项目走了更便宜的路）：Frida（SELinux 拦网络 ⇒ 改用自打 Log）
   ⇒ 但这也验证了「Gadget script 模式」才是免 Root 正路
```

---

*生成时间：2026-11-04 · 本轮实测汇总*
*同目录另有：`AI.js-Pro-MCP-使用体验反馈_用户侧.md`（上轮）· `AI.js-Pro-MCP-逆向能力优化建议.md`（开发侧）*
