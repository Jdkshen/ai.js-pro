# 仓库规范（REPO_CONVENTIONS）

> 目的：**先备份、再动手；文件放对地方；同类问题只解释一次。**
> 新接手先读：[`ARCHITECTURE.md`](ARCHITECTURE.md)（框架）→ 本文（规范）→ [`../INDEX.md`](../INDEX.md)（历史报告索引）。

## 一、备份规范（改动前必做）

任何"整理/重构/批量改"之前，先在**仓库外**留一份可回滚的快照（三步都很快）：

```powershell
$bk="C:\Users\18101\Documents\ai-js-pro-backups"   # 仓库外，别放仓库里
New-Item -ItemType Directory -Force -Path $bk | Out-Null
Push-Location C:\Users\18101\Documents\ai-js-pro
$stamp=Get-Date -Format "yyyyMMdd-HHmmss"; $head=(git rev-parse --short HEAD)
git tag -f "backup/$stamp-$head"                                   # 1) 打标签，便于回看
git bundle create "$bk\ai-js-pro-all-$stamp.bundle" --all          # 2) 全历史（~65MB）
git archive --format=zip -o "$bk\ai-js-pro-src-$head-$stamp.zip" HEAD   # 3) 当前跟踪源码快照（~320MB）
Pop-Location
```

- 工作区若脏：先 `git status`，把未提交的改动也 `git stash` 或提交后再备份。
- **`.artifacts/`（约 29GB）与 `output/`（约 3.9GB）不入备份**——它们是产物，需要时重建。
- 恢复：`git bundle verify <bundle>` → `git clone <bundle>`；或直接 `git checkout <tag>`。

## 二、目录职责（什么该放哪里）

| 路径 | 职责 | 放什么 / 不放什么 |
|---|---|---|
| `apps/app` | 主 APK（Flutter 外壳 + 原生 + MCP 服务 + 引擎宿主） | 应用代码；大二进制走 LFS 或 `assets/` |
| `apps/inrt` | 独立运行 APK（inrt 运行时） | 与主包共享的宿主逻辑 |
| `modules/engine` | 三引擎运行时（Rhino/QuickJS/Node24）、prelude 资产、脚本 API | **脚本侧 API 与 prelude 都在这里**（`assets/modules/*.js`、`assets/node24/**`） |
| `modules/automator` | 无障碍/自动化核心（点击、节点、手势、截屏） | 与 UI 无关的自动化能力 |
| `modules/common` | 跨模块通用（`IntentUtil`、`MimeTypes`、`Pref`…） | 改这里**影响面最大**，必须全量回归 |
| `modules/rhino-language` | Rhino 引擎封装 + 多版本 jar | 引擎升级只动这里 |
| `apps/app/src/flutterpoc` | Flutter PoC 原生侧（Java，非标准 source set） | 与 `flutter-ui/` 配套；**不是** `main/java` |
| `apps/app/src/main/assets` | 随包资产 | `sample/`（内置示例）、`models/`（PP-OCR）、`mcp/`（指南）、`indices/`（API 文档索引） |
| `tools/` | 构建、发布、真机回归、审计脚本 | **默认全部跟踪**（见 §三）；产物/缓存除外 |
| `docs/guides` | 长期有效文档（架构、规范、API 手册） | 会随版本演进的手册 |
| `docs/reports` | 按日期的一次性报告 | 命名 `YYYY-MM-DD_主题.md`；见 §五 |
| `docs/plans` | 方案/评估稿（未落地或部分落地） | 落地后补一份 report 指向它 |
| `samples/` `third-party/` `plugins/` `vscode-extension/` `flutter-ui/` | 仓库内附带的独立子项目 | 各自有自己的构建，不动主流程 |

**硬规则**：脚本能在 App 内跑到的 API，必须同时照顾 Rhino/QuickJS/Node 三条路径；只改一条会在另两条上"示例失败"。

## 三、跟踪策略（`.gitignore` / `.gitattributes`）

1. **`tools/` 默认跟踪**（2026-11-06 改正）。旧策略是"先生成 `/tools/*` 全部忽略、再逐个 `!` 白名单"，
   结果是 **47 个真实脚本在磁盘上存在但从未入库**（device 驱动、引擎审计、Node24/QuickJS 契约测试、probes），
   而且**每新增一个脚本都会再踩一次**（AGENTS.md 里已两次记录同一坑）。现在只忽略：
   `__pycache__/`、`*.pyc`、`tools/templates/`、`tools/flutter-poc/*`（保留发布脚本）、`tools/apk-audit/*`（保留唯一源文件）、
   `apktool-android/build|dist`、`r2ghidra-build`、`apk-components/.gradle`。
   → **新增脚本不需要改 `.gitignore`**；如果发现又被忽略，说明策略被改回去了。
2. **`build/` 既是产物目录也是 Java 包名**：`apps/app/src/main/java/**/build/`、`autojs/build/` 是**合法源码包**，
   `.gitignore` 里有对应的 `!` 例外。**别再用 `(^|/)build/` 这种正则去"清理产物"**——会误伤 24 个源码文件（我第一次就误判了）。
3. **大二进制走 LFS**：`*.aar/*.so/*.a/*.bin/*.onnx/*.otf`、两个 `template.apk`、flutter embedding jar 都在 `.gitattributes` 里配了 LFS。
   新增同类二进制要同步加规则，否则会把几百 MB 直接塞进 git 历史。
4. **行尾**：`.gitattributes` 已声明 `* text=auto eol=lf`、`*.ps1/*.bat eol=crlf`。
   我们**没有**对存量做 `git add --renormalize`（会产出几万行无意义 diff）；新文件按上面规则走即可。

## 四、代码规范

- **注释/文档用中文，标识符用英文**（沿用现状）；注释写"为什么"，不写"做了什么"。
- **改脚本侧 API**：Java 侧在 `modules/engine/.../runtime/api/`，JS 侧在 `modules/engine/src/main/assets/modules/`；
  两处**必须同时改**，并在 prelude 里保留向后兼容（本次 OCR 的 MlKitContext/结果序列化就是没同时照顾的教训）。
- **R8 会裁掉只被脚本反射调用的东西**：新增"给脚本用的 Java 方法/类"时，检查 `apps/app/proguard-rules.pro` 是否需要 keep。
  真实案例：`okhttp3.MultipartBody$Builder.addFormDataPart`（文件上传示例挂）、`com.android.apksig.ApkSigner$Builder`（V3 签名永远回退）。
- **错误要可执行**：报错里给"下一步做什么"（参数名、可调常量名、下一步工具），不要只说"失败"。
- **不假装成功**：拿不到证据就报 `not_run`/`unavailable`/`assumed`（如 V1 只报"存在性"、`documentedAt` 标注来源）。

## 五、报告与文档规范

- 报告文件名：`docs/reports/YYYY-MM-DD_主题.md`；主题用中文，不带版本号（版本写在正文）。
- 报告**必备六段**：现象 / 证据（命令与原始输出）/ 根因 / 修复 / 验证（真机 + 单测）/ 边界与未做。
  **没有真机证据的不要写"已验证"**；无法验证的明确写"未验证 + 原因"。
- 每版发布后：更新 `AGENTS.md` 顶部一条（一段话说清改了什么+证据+SHA），并在 `docs/INDEX.md` 留一行。
- 引用文件用相对链接；行号用 `path:行` 形式，方便跳转。

## 六、提交与发布规范

- **只本地提交，默认不 `git push`**；只有用户明确说"推一次"才推。
- **只发 LAN**（`tools\publish.ps1`，`192.168.10.7:8080`），不逐版本建 GitHub Release/tag（业务约定，2026-10-31 起）。
- 版本号逐版本 +1（dev-NNNN）；发布前**必须**在真机跑一次（`publish.ps1` 会验证手机拉 `update.json`）。
- 测试机**默认 K40 `cccc62c7`**（`adb -s cccc62c7 forward tcp:18768 tcp:8765`）；MI8 `ce4d2bdb` 已退役，只作历史参照。
- 构建由 **Lead 独占**（本机 Gradle daemon 不稳，并发构建会互相打断）：`. .\tools\jdk17.ps1` 后跑
  `tools\flutter-poc\build-release-candidate.ps1 -VersionCode N -VersionName "dev-N" -R8 -TestSigning -NoUsbDiscovery -MaxHeapMb 2560 -MaxWorkers 1`。

## 七、磁盘卫生（仓库外的大户）

| 目录 | 体积（2026-11-06） | 说明 |
|---|---|---|
| `.artifacts/` | **29 GB** | 构建产物 + 发布日志 + 历史候选 APK |
| `output/` | **3.9 GB** | 本地分析/回归产物（MCP 导出、示例镜像、日志） |
| `.codegraph/` | 250 MB | 代码索引，可 `codegraph init` 重建 |
| `.gradle/` | 204 MB | Gradle 缓存，可删（下次构建重建，变慢） |
| `runs/` | 88 MB | 运行时残留 |
| `chess-preview/` | 120 MB | 象棋预览产物 |

### 2026-11-06 实际执行过的清理（可照抄）

| 对象 | 规则 | 结果 |
|---|---|---|
| `.artifacts/updates/**` | **永不删**（LAN 更新源，`update.json` 指向它） | 保留 |
| `.artifacts/flutter-release-candidate/*.apk` | 只留**最新 4 个** | 保留 1363–1366 |
| `.artifacts` 下其它 `*.apk` | 删 **14 天前**的（构建产物可重建） | 删 53 个 / 4.97 GB |
| `.artifacts` 下 `*.log` | 删 **30 天前**的 | 删 1430 个 / 105 MB |
| `output/ci-head.zip`、`output/local-cleanup-20261106/` | 无 docs 引用 → 删 | 268.9 MB |
| `output/node-pro-gaps-20261021/`（2.9 GB） | **被 5 篇文档引用 → 保留** | 保留 |
| `output/toolchain-20261025/`（226 MB） | 组件重打包的暂存（debs/repack.py）→ 保留 | 保留 |

合计释放 **≈5.2 GB**（工作区 11.5 → 11.0 GB；`.artifacts` 29 → 23.5 GB）。命令模板：

```powershell
$art="C:\Users\18101\Documents\ai-js-pro\.artifacts"
$keep = Get-ChildItem -Recurse -File "$art\flutter-release-candidate" -Filter *.apk |
  Sort-Object LastWriteTime -Descending | Select-Object -First 4
$del = Get-ChildItem -Recurse -File $art -Filter *.apk |
  Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-14) -and
                 $_.FullName -notmatch "\\updates\\" -and
                 ($keep.FullName -notcontains $_.FullName) }
$del | Measure-Object -Property Length -Sum   # 先看再删
# $del | Remove-Item -Force                   # 确认后执行
```

**删除前必做**：`git grep --fixed-strings "<目录名>" -- docs` 查有没有报告把它当证据引用（`node-pro-gaps-20261021` 就是被引用而保留的）。
安全清理（**先看再删**）：

```powershell
# 只看：最近 30 天没动过的构建产物有多大
Get-ChildItem C:\Users\18101\Documents\ai-js-pro\.artifacts -Recurse -File |
  Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-30) } |
  Measure-Object -Property Length -Sum | ForEach-Object { [math]::Round($_.Sum/1GB,2) }
```

**不要**把 `.artifacts/`、`output/`、`.codegraph/` 提交进 git（已在 `.gitignore`）。

### 2026-11-07 C 盘反复被吃满 → 把开发缓存搬到 E:（**已生效的环境变量**）

上面那套清理只是「回到安全水位」，**不解决每次都长回来**：实测 7 次清理后，
C: 仍在 17 小时内从 11.4 GB 掉到 **0.74 GB（100%）**。

根因是这几个目录的**默认位置就在 C 盘**，每次开发必涨：

| 增长源 | 每轮增长 | 环境变量 | 现状 |
|---|---:|---|---|
| Gradle 依赖缓存 + `transforms` | 2–3 GB | `GRADLE_USER_HOME` | **`E:\dev-cache\gradle`** |
| 各类临时文件 | 5–7 GB | `TEMP`/`TMP` | **`E:\dev-cache\temp`** |
| Codex 会话 + 任务暂存 | 曾累积到 9 GB | `CODEX_HOME` | **`E:\dev-cache\codex`** |
| npm / pip 缓存 | 0.3 GB | `npm_config_cache` / `PIP_CACHE_DIR` | **`E:\dev-cache\{npm,pip}`** |

**排查时容易漏掉的**：`~\.codex`（默认不在 `AppData` 下，前几轮扫描都没覆盖）。
它的 `tmp\<任务名>-<日期>\` 是**过期任务的构建暂存**（如
`aijs-node-widget-release-20261030` 2.7 GB），可删；`sessions\` 是会话日志，
删 30 天前的；`arg0`/`arg0-windows` 是**运行时 shim，不要删**。

**验收（迁移后必须做）**：

```powershell
$env:GRADLE_USER_HOME = 'E:\dev-cache\gradle'
. .\tools\jdk17.ps1
.\gradlew.bat --version          # 应打印 Gradle 8.9
.\gradlew.bat :app:tasks --all   # 应 BUILD SUCCESSFUL（会解析 AGP 插件与全部依赖）
```

**回滚**（环境变量 + 缓存搬回）：见
[`docs/reports/2026-11-07_C盘优化与开发缓存迁移E盘.md`](../reports/2026-11-07_C盘优化与开发缓存迁移E盘.md) §六。

⚠️ 两条注意：① 改的是**用户级注册表变量**，只有**新启动的进程**读到 —— VS Code 要完全退出重开；
② E: 从此是构建依赖，别把 E: 拔了/清空。

### 2026-11-08 补：环境变量不够，加 junction 兜底；以及「合并缓存」的坑

**为什么还要 junction**：16 小时后 C: 又到 99%，因为**老进程仍持有旧环境变量**
（实测 `TEMP` 进程值还是 `C:\...\Temp`），于是 `~\.gradle` 又长回 3.16 GB、`Temp` 长回 4.79 GB。
用**目录 junction** 让 C: 路径直接指向 E:，**任何进程**（含老进程）写入都落 E:：

```powershell
cmd /c mklink /J "C:\Users\18101\.gradle" "E:\dev-cache\gradle"
cmd /c mklink /J "C:\Users\18101\AppData\Local\npm-cache" "E:\dev-cache\npm"
cmd /c mklink /J "C:\Users\18101\AppData\Local\pip"       "E:\dev-cache\pip"
```

`~\.codex` 与 `%TEMP%` **建不了 junction**（目录被进程/系统持续持有，删不掉）→ 只能靠环境变量。

> ⚠️ **事故教训：迁移 Gradle 缓存只能用 `robocopy /MOVE` 整体搬，绝不能把两份 merge 到一起。**
> 混了两份不同状态的增量缓存后 Gradle 直接拒绝启动：
> `Could not create service of type JavaModuleDetector ... Unexpected lock protocol found in lock file. Expected 3, found 0.`
> **修法**：停掉全部 daemon → 删掉缓存里**所有 `*.lock`**（本次 257 个）→ Gradle 重新获取即可，
> 不必重建整个 `caches/`（依赖 jar 能保住）。修后 `:app:tasks --all` BUILD SUCCESSFUL（176 tasks）。

> ⚠️ 迁移后**别并发跑构建**：`R.jar: 另一个程序正在使用此文件` 就是与并发构建撞锁，
> 不是缓存坏了 —— 见 §六「构建由 Lead 独占」。


## 八、常见坑速查

| 坑 | 结论 |
|---|---|
| 新增 `tools/` 脚本后 `git status` 看不到 | 策略已改为默认跟踪；若再现，说明 `.gitignore` 被改回 `/tools/*` |
| 用 `build/` 正则清理产物 | 会误伤 `com/jdkshen/aijspro/**/build/` 源码包（24 个文件） |
| 重装 APK 后无障碍截图报"需要 Android 11+" | 关一次再开无障碍（服务未重绑） |
| 脚本里新加的 Java 方法在 R8 包里"不存在" | 补 `proguard-rules.pro` keep（反射调用 R8 看不见） |
| `adb shell` 卡住 / 设备从列表消失 | USB/Hub 问题：换线或重插；`adb kill-server; adb start-server` 后确认只剩目标机 |
| 同一 API 在 Node 能跑、Rhino 挂 | 检查各进程自己的初始化（如 ML Kit 的 `MlKitContext`：Node 侧有、Rhino 侧曾漏） |
