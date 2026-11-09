# 目录与产出规范（DIRECTORY_CONVENTIONS）

> **这份文档是给「每一个 AI 会话」看的，不管你是哪个模型、第几次接手。**
> 起因：同一个仓库被多轮不同 AI 会话接手，每轮都往不同地方丢文件，导致
> `output/` 根目录堆了 **1622 个散落文件**、`.artifacts/` 攒了 **350+ 个任务目录**、
> `C:\Users\18101\Downloads` 被 **20 个构建 APK** 塞满、`~\.codex\tmp` 长到 **4.75 GB**。
> 清理花了十几轮、释放约 **50 GB**。**这些成本本来可以避免。**
>
> 相关文档：[`REPO_CONVENTIONS.md`](REPO_CONVENTIONS.md)（仓库总规范）· [`ARCHITECTURE.md`](ARCHITECTURE.md)（架构）

---

## 0. 三句话版（记不住细节就记这三条）

1. **临时产出 → `output/<任务名>-<YYYYMMDD>/`**，**绝不允许**裸放在 `output/` 根目录、仓库根目录、`docs/` 根目录。
2. **构建/发布/验证产物 → `.artifacts/<任务名>-<YYYYMMDD>/`**，**一个任务一个目录**。
3. **任务收尾就清**；开工前先看盘，`C:` 可用 < 15 GB 就先清再干活。

> 违反这三条 = 你给下一个会话（可能是别的模型）制造了必须考古的垃圾堆。

---

## 1. 位置表：什么放哪里

| 目录 | 放什么 | 谁写 | 生命周期 | 能删吗 |
|---|---|---|---|---|
| `docs/reports/` | **正式报告**（`YYYY-MM-DD_主题.md`，六段式） | 所有会话 | **永久**（入库） | ❌ 绝不删 |
| `docs/plans/` | 方案/评估稿 | 所有会话 | 永久（入库） | ❌ |
| `docs/guides/` | 长期有效手册 | 所有会话 | 永久（入库） | ❌ |
| `output/<任务名>-<日期>/` | **临时分析产物**：抓包导出、反编译片段、截图、探测日志、对照数据 | 所有会话 | 一次性 | ✅ 任务结束后可删 |
| `output/` 根目录 | **【禁止】** 只允许放**被文档引用**的长期资产（如 `flutter-poc/`、`node-pro-gaps-20261021/`） | — | — | 见 §4 |
| `.artifacts/<任务名>-<日期>/` | **构建/发布/真机验证产物**：候选 APK、staging、publish 日志 | 所有会话 | 一次性 | ✅ 按 §5 规则 |
| `.artifacts/updates/` | **LAN 更新源**（`update.json` + 当前 APK） | 只由 `tools/publish.ps1` 写 | **永久** | ❌ **绝不删**（删了手机更新就断） |
| `.artifacts/flutter-release-candidate/` | 候选 APK 暂存 | 构建脚本 | 滚动 | ✅ 只留**最新 4 个** |
| `.gradle/`（仓库内） | Gradle 项目本地增量状态 | Gradle | 可再生 | ✅ 但**构建进行中不许删** |
| `~/.gradle`（用户目录） | Gradle 全局缓存 | Gradle | 可再生 | 已 junction 到 `E:\dev-cache\gradle` |
| `%TEMP%` | 系统临时 | 所有程序 | 极短 | ✅ 但**建不了 junction**（Windows 持有），靠环境变量 + 定期清 |
| `apps/*/build/`、`modules/*/build/` | 构建中间产物 | Gradle | 可再生 | ✅ **构建结束后**可删 |
| `src/test/**` | **测试源码** | 人 | 永久（入库） | ❌ **绝不删**（删了 `gradlew test` 就跑不了） |

### 仓库外（**不许往里写东西**）

| 目录 | 实际情况 | 规矩 |
|---|---|---|
| `C:\Users\18101\Downloads` | 人的下载目录 | ❌ **不许把构建 APK/临时产物写这里**。历史遗留的 20 个已清 |
| `C:\Users\18101\Documents\ai-js-pro-backups` | 备份 | 只由备份脚本写 |
| `E:\dev-cache\` | 缓存（本机迁移来的） | 只由环境变量指向，不手动放东西 |

---

## 2. 命名规则

**任务目录**：`<任务名>-<YYYYMMDD>`

```text
✅  sig-fix-20261109/                （小写英文 + 连字符 + 日期）
✅  mcp-align-1326-verified/
✅  dev_screen_diff-20261109/
✅  tinghai-node-20261026-183810/    （同一天多次 → 加时分秒）
❌  tmp/  test/  new/  temp/  1/  新建文件夹/  （无信息量，下次没人知道是什么）
❌  final/  final2/  final-final/   （版本靠日期，不靠形容词）
```

**日志文件**：`<任务名>-<阶段>.log`

```text
✅  build-candidate-1424.log
✅  unit-mcp-1424.log
✅  publish-20261109-084534.log
❌  test.log   build.log   out.log   1.log
    （会互相覆盖、无法追溯是哪次；本次清理里有 1013 个这种文件）
```

**报告文件**：`docs/reports/YYYY-MM-DD_主题.md` —— 主题用中文、**不带版本号**（版本写在正文）。

---

## 3. 每个任务的收尾清单

任务结束前逐条打勾（**这一步比看起来重要**）：

- [ ] 这次产生的临时文件**都在** `output/<任务名>-<日期>/` 里（不在仓库根、不在 `docs/` 根）
- [ ] 构建/发布产物在 `.artifacts/<任务名>-<日期>/`
- [ ] **没有**往 `Downloads`、桌面、仓库根目录丢任何东西
- [ ] 删掉了本次的**中间产物**，只保留**最终证据**（结论截图/关键日志/最终 APK）
- [ ] 报告已写到 `docs/reports/`，并在 `docs/INDEX.md` 留一行
- [ ] 若改动了环境/目录约定 → 更新本文档

---

## 4. 红线（做了要回滚）

| 红线 | 为什么 |
|---|---|
| ❌ 在仓库根 / `docs/` 根 / `tools/` 根丢临时文件 | 会被当成源码资产，git status 被污染，下个会话要考古 |
| ❌ 往 `C:\Users\18101\Downloads` 写构建产物 | 那是人的目录，不是你的暂存区 |
| ❌ 删 `.artifacts/updates/**` | LAN 更新源，删了手机更新直接断 |
| ❌ 删 `src/test/**` | 那是测试**源码**，不是测试**产物**（两回事，长得像） |
| ❌ 删官方基线 APK | `autoJsPro.apk` / `AutoJs高级版_Pro 9.3.11-0.apk` / `Pro 9.3.11-googleplay.apk` 是逆向对照用的 |
| ❌ 提交 `.artifacts/` `output/` `.codegraph/` `build/` `*.log` | 已在 `.gitignore`，别绕过去 |
| ❌ **构建进行中**删 `build/` 或 `.gradle/8.9/executionHistory` | 会把构建搞挂（见过 `Unexpected lock protocol` 和 R8 半途被删） |
| ❌ 用**目录名正则**清理（如 `-Filter build`） | 会误伤 `com/jdkshen/aijspro/**/build/` **真实源码包**（24 个文件） |

### 判断「构建是否真的结束了」—— 别信日志

**实测教训**：构建日志静默 **4.4 分钟**，但 `build/` 近 3 分钟写了 **622 个文件** ——
当时正在 `:app:minifyMiuixCompatReleaseWithR8`（**R8 混淆是好几分钟的长任务，期间完全不刷日志**）。

```powershell
# ✅ 正确判据：看 build/ 的写入时间
Get-ChildItem apps\app\build,apps\inrt\build,modules\engine\build -Recurse -File -Force |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
# 静默 >= 4 分钟 才视为结束

# ❌ 错误判据：看日志静默
```

---

## 5. 清理规则

### 5.1 按年龄

| 对象 | 规则 |
|---|---|
| `.artifacts/**/*.apk`（除 `updates/`） | 删 **14 天前**的 |
| `.artifacts/flutter-release-candidate/*.apk` | 只留**最新 4 个** |
| `.artifacts/**/*.log` | 删 **30 天前**的 |
| `.artifacts/<任务目录>` | **0 文档引用** 且 **7 天未动** → 可删 |
| `output/` 根散落文件 | 未被文档引用 → 可删 |
| `apps/*/build`、`modules/*/build` | 构建结束后随时可删（可再生） |
| `.gradle/8.9/executionHistory`、`fileHashes` | 可删（代价：下次构建慢一点） |

### 5.2 删前**必须**做的一步

```powershell
# 查这个目录名有没有被报告当证据引用
git grep --fixed-strings "node-pro-gaps-20261021" -- docs
```

**被引用 ≠ 不能删**，但要分清删什么：
- 被引用的**目录名**要留着（报告里的链接不能断）
- 目录里的**构建 APK** 可以删（可重建；证据是报告和里面的非 APK 数据）
  - 实例：`output/node-pro-gaps-20261021/` 删了 19 个 APK（2.00 GB），
    **65,609 个非 APK 文件（876 MB 的 Node 包结构对照）全部保留**

### 5.3 一键自检

```powershell
pwsh -File tools\check-dir-hygiene.ps1            # 只报告
pwsh -File tools\check-dir-hygiene.ps1 -Fix       # 报告 + 按规则清理（会先打印将删清单）
```

---

## 6. 磁盘阈值

| 盘 | 余量 | 该做什么 |
|---|---|---|
| `C:` | **< 15 GB** | 先清再开工（`%TEMP%` + 仓库 `build/` 是主要回涨源） |
| 任意盘 | **< 10%** | **停止**产生产物的任务，先协商清理 |

**历史回涨速度**（实测，用来判断"为什么又满了"）：

| 增长源 | 每轮增长 | 现在的落点 |
|---|---:|---|
| Gradle 依赖缓存 + transforms | 2–3 GB | `E:\dev-cache\gradle`（junction） |
| 各类临时文件 | 5–7 GB | `E:\dev-cache\temp`（环境变量；`%TEMP%` 建不了 junction） |
| Codex 会话 + 任务暂存 | 曾累积 9 GB | `E:\dev-cache\codex` |
| 仓库 `build/` 产物 | 2–4 GB | **仍在 C:**（唯一没搬走的） |
| `.artifacts` 候选/暂存 | 每版 0.14 GB × N | **仍在 D:**（靠 §5 规则滚动清） |

---

## 7. 为什么「只写文档」不够

本文档 + `tools/check-dir-hygiene.ps1` 是配套的：**规范负责说清，脚本负责抓违规**。
但两者都依赖**每个会话真的读 AGENTS.md 顶部**——所以 `AGENTS.md` 顶部有一条指向本文档的短块
（放在顶部是因为：AGENTS.md 已 190 KB，会被**指令预算截断到 64 KB**，写在后面的规范读不到）。

**如果你是新接手的 AI**：读到这里说明你读了，请在本次任务里照做。
**如果你发现本文档和实际不符**：先按实际做，然后把本文档改对（别默默绕过）。
