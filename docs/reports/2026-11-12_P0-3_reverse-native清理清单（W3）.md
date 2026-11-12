# P0-3：`tools/reverse-native` 清理清单（**只读出清单，未删除任何东西**）— 2026-11-12（W3）

> 依据 Lead 的 P0-3 任务与红线（**不删任何东西**、不动 `.artifacts/updates/**` 与 `src/test/**`）。本文件是**第 1 步**，等 Lead 确认后才执行第 2 步。

## 1. 结论（先说能不能删、该删什么）

| 项 | 判定 |
|---|---|
| 待清对象 | **只有 2 个目录**：`tools/reverse-native/r2ghidra-build/`（7,060 文件 / **170.3 MB**）与 `tools/reverse-native/__pycache__/`（1 文件） |
| 是否跟踪（git） | **都不跟踪** ✓（`git status --porcelain --ignored` 对这两项只输出 `!!`；跟踪的只有 5 个源文件，见 `2） |
| 是否可重建 | **可重建 ✓**（脚本 + 固定版本 + sha256 + 前置齐全，见 `4） |
| 是否有代码依赖它 | **没有**（36 处文本命中逐条分类后**真依赖 = 0**，见 `5） |
| 是否符合仓库规范 | **符合**：`docs/guides/REPO_CONVENTIONS.md:51` 已把 `r2ghidra-build` 明确列进「忽略」策略 ⇒ 它是**预期的可再生产物** |
| 建议 | **可以删这 2 个目录**（附 dry-run 命令 `6）；**跟踪的 5 个源文件绝不能动** ✗ |

## 2. 跟踪 vs 未跟踪（可复算）

~~~powershell
git ls-files tools/reverse-native
# tools/reverse-native/README.md
# tools/reverse-native/build-r2ghidra.py
# tools/reverse-native/frida_runner.c
# tools/reverse-native/unicorn_runner.c
# tools/reverse-native/yara_runner.c

git status --porcelain --ignored tools/reverse-native
# !! tools/reverse-native/__pycache__/
# !! tools/reverse-native/r2ghidra-build/
~~~

⇒ **只有 2 行 `!!`（ignored）**，没有任何 `M/??` ⇒ 删除**不会**触碰任何被跟踪内容。

## 3. 体积与来源（可复算）

~~~powershell
Get-ChildItem tools/reverse-native | ForEach-Object {
  $f = if ($_.PSIsContainer) { Get-ChildItem $_.FullName -Recurse -File } else { $_ }
  '{0,-24} files={1,-6} {2} MB' -f $_.Name, ($f|Measure-Object).Count, [math]::Round((($f|Measure-Object Length -Sum).Sum)/1MB,1) }
~~~

实测输出：`r2ghidra-build` = **7,060 文件 / 170.3 MB**；`__pycache__` = 1 文件；其余 5 个均为**源文件**（README.md / build-r2ghidra.py / frida_runner.c / unicorn_runner.c / yara_runner.c）。

- **`r2ghidra-build/` 来源**：`tools/reverse-native/build-r2ghidra.py` 的构建输出（docstring 明写「Output layout (under `r2ghidra-build/dist`)」：`lib/core_ghidra.so` + `share/r2ghidra/*.sla` + `manifest.json`）。它是 **Meson 配置 + 编译的中间/产物树**（含下载解包的上游源码）。
- **`__pycache__/` 来源**：Python 字节码缓存（同目录脚本执行后生成）。

## 4. 可重建性（**关键判据**：有脚本 ⇒ 可重建 ✓）

| 需要 | 现状（实测） |
|---|---|
| 构建脚本 | ✓ `tools/reverse-native/build-r2ghidra.py`（**已跟踪**）+ 顶层 `tools/prepare-reverse-components.py`（16,873 B，**已跟踪**） |
| 固定输入 | ✓ 脚本 docstring 写死版本与 sha256：r2ghidra **6.2.4**（`8cdd84c2…`）、ghidra-native `483ae94…`（`916adfe4…`）、sleigh-6.2.4（`64c441a3…`） |
| radare2 SDK 前置 | ✓ `.artifacts/reverse-suite-20261103/sdk/radare2`（**存在，283 文件**；`lib` 也在） |
| 整个 reverse-suite 目录 | ✓ `.artifacts/reverse-suite-20261103`（645 文件） |
| Android NDK | ✓ `C:/Android/ndk-r26d`（`Test-Path` = True） |
| 网络 | ⚠️ **需要**：上游 tar 包只在缺失且传 `--fetch` 时下载（docstring 明写「No … network operation is performed unless a pinned source archive is missing and `--fetch` is passed」）⇒ **离线环境下若归档不在，就重建不了** |

**⇒ 可重建**，但代价是「重新跑一次耗时的 C 编译 + 可能要联网」；**建议**：清理前把 `r2ghidra-build/dist/manifest.json`（版本/哈希证据）拷进 `.artifacts/` 留档（**几 KB**），这样即使重建要联网，哈希链仍在。

## 5. 依赖面核查：36 处文本命中，**真依赖 0**

我用 `r2ghidra-build|reverse-native|reverse-suite-20261103` 全仓 grep，得到 36 处命中，逐条分类：

| 命中位置 | 判定 |
|---|---|
| `apps/app/…/mcp/ToolkitKnowledge.kt:31` | **假阳性**：那是 prompt 名 `reverse-native-analysis`（字符串里含 `reverse-native`），与目录无关 |
| `tools/check-ps1-encoding.mjs:21` | **假阳性**：它是**跳过目录名单**里的一项（`'reverse-native'`），属有意配置 |
| `docs/guides/REPO_CONVENTIONS.md:51` | **有意配置**：`.gitignore` 策略里显式忽略 `r2ghidra-build` ✓ |
| `apps/app/src/main/assets/mcp/AIjs-Reverse-Guide.md:698` | 提到的是 **`frida_runner.c`（已跟踪源文件）**，不是构建树 |
| `tools/apktool-android/README.md:78/114` | 指向 **`.artifacts/reverse-suite-20261103/sdk/…`**（★ 产物本就在 `.artifacts`，不在 `tools/`） |
| `docs/reports/2026-11-04_MCP逆向工具与自发现指南.md:58` | 链接的是 **两个已跟踪脚本**（`prepare-reverse-components.py`、`build-r2ghidra.py`） |
| `ToolkitKnowledgeTest.kt:99/117` | 测试断言的是 prompt 名/知识面，非目录 |
| 其余（含我这份与 Lead 的 Phase1/Phase2 清单） | 文档自引用 |

⇒ **没有任何代码/脚本/文档把 `tools/reverse-native/r2ghidra-build` 当输入或产物位置**（真正的产物位置是 `.artifacts/reverse-suite-20261103/**`）。

## 6. 建议清理动作（**等 Lead 明确说「删哪些」后**才执行）

~~~powershell
# ① 先 dry-run（不会删任何东西）—— 只列被 ignore 的未跟踪文件
git clean -ndX tools/reverse-native
# ② 确认输出只有 __pycache__/ 与 r2ghidra-build/ 之后，再执行（本步我未执行）
# Remove-Item -Recurse -Force tools/reverse-native/r2ghidra-build
# Remove-Item -Recurse -Force tools/reverse-native/__pycache__
~~~

- **不要**用 `git clean -fdx` 之类宽范围命令；**不要**动 `tools/reverse-native` 下那 5 个源文件。
- 清理后可复算校验：`git status --porcelain --ignored tools/reverse-native` 应**无输出**；`git ls-files tools/reverse-native` 仍应是那 5 个。
- 收益：`tools/` 目录立即减少 **170.3 MB / 7,061 文件**。

## 7. 「疑似无主」根脚本清单（**只列不删** ✗；有动态调用风险）

判据：把 `docs` + `tools` + `.github` + `apps` + `modules` + `plugins` 的文本一起算，**除自身外被提及 ≤1 次**、且在 `.github/workflows/android.yml` 中**无引用**、且**没有被任何 .py import**。**收紧语料后是 18 个**（我上一份报告里的「36」是只用 `docs/tools/.github` 三处语料的更宽口径 ⇒ 以本清单为准）。

| # | 脚本 | 提及 | 初判 |
|---|---|---:|---|
| 1 | `audit-engine-samples.py` | 0 | 引擎审计类（一次性） |
| 2 | `audit-pro-api-surface.py` | 1 | API 面审计 |
| 3 | `build-engine-sample-target.py` | 0 | 样例构建辅助 |
| 4 | `drive-mi8-app-dialogs.js` | 0 | 真机(MI8)驱动 |
| 5 | `drive-mi8-sample-dialogs.js` | 0 | 真机(MI8)驱动 |
| 6 | `mi8-sample-plan.py` | 0 | 真机计划 |
| 7 | `node24-ui-perf.py` | 1 | 性能测量 |
| 8 | `package-android-cpp-toolchain.py` | 1 | 工具链打包 |
| 9 | `remote-engine-server.py` | 0 | 远程引擎服务端 |
| 10 | `run-engine-audit-device.py` | 0 | 真机审计驱动 |
| 11 | `summarize-engine-audit.py` | 0 | 审计汇总 |
| 12 | `test-android-platform-shadowing.py` | 0 | 门禁（**注意**：`check-android-platform-shadowing.py` 被 `publish.ps1:280` 调用 ⇒ 两者不同，勿混） |
| 13 | `test-check-android-16kb.py` | 1 | 16KB 校验自测 |
| 14 | `test-sift-device.js` | 1 | 真机(SIFT)驱动 |
| 15 | `test-ui-accessibility-device.js` | 1 | 真机驱动 |
| 16 | `upload-plugin-release.mjs` | 0 | 插件发布上传（**可能是发布流程备用**，建议先问 W2） |
| 17 | `verify-mcp-alignment-device.py` | 1 | 真机验证 |
| 18 | `verify-mcp-terminal.py` | 1 | 终端验证 |

**建议**：**一个都不要删**（PowerShell/Python 可能用变量拼路径调用，我的统计看不见）⇒ 二选一：① 建 `tools/legacy/` 归档（同时在 `REPO_CONVENTIONS` 记一句）；② 各自补 3 行用途说明。**收益是「可发现性」，不是「删文件」。**

## 8. 边界（本轮）
1. **只读**：我**没有执行任何删除**（`Remove-Item`、`git clean` 均未跑）；上面 `6` 的命令是**待确认后**才用的。
2. 未触碰 `.artifacts/updates/**` 与 `src/test/**` 红线。
3. `「可重建」`的判据是「脚本 + 固定版本/hash + 前置存在」，**我没有真的重建一次**（耗时且需要 NDK 编译）⇒ 「重建会成功」属**未验证**；但 **「源码与前置都在」是实测 ✓**。
4. 18 个「疑似无主」的判定是**文本统计**，动态调用（变量拼路径、别名、外部 CI）**看不见** ⇒ **不得据此删除**。
