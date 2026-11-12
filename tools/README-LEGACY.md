# tools/ 疑似无主脚本索引（**归档不删**，2026-11-12，W3）

> **这份文件只做「可发现性」，不移动、不删除任何脚本**（Lead 2026-11-12 决策）。
> 用途全部**摘自脚本自身的 docstring / 首注释**（不是我猜的）；摘不出来的标「**用途待考**」。

## 为什么会有这份名单

Phase 1 全仓体检里，我用「文本提及次数」统计出 tools/ 根目录一批脚本**除自身外几乎无人提及**。
判据（可复算，见文末命令）：在 `docs` + `tools` + `.github` + `apps` + `modules` + `plugins` 的文本里，**除自身外被提及 ≤1 次**、`.github/workflows/android.yml` 中**无引用**、且**没有被任何 `.py` `import`** ⇒ 共 **18 个**。

⚠️ **这不等于「死代码」**：PowerShell/Python 可以用变量拼路径调用、外部 CI 或人工按文档敲命令，**文本统计看不见**。所以本名单的用途是「**下次要用时能找到**」，**不是删除依据** ✗。

## 名单（18 个，用途摘自各自文件头）

| # | 脚本 | 用途（摘自已身注释） | 来源 |
|---|---|---|---|
| 1 | `audit-engine-samples.py` | 清点全部随包示例（**不执行**）；产出设备编译器探针消费的 `sources.json`；参考扫描是评审证据，**不是** JS 解析器/可运行性证明 | docstring |
| 2 | `audit-pro-api-surface.py` | 用 MCP `list_engine_api` + 有界「不调用」的点号属性查询，盘点 Pro API 契约声明的 API 面；**形状审计，不是兼容性/行为测试** | docstring |
| 3 | `build-engine-sample-target.py` | 为示例的手势/选择器/卸载测试构建一个本地 Android 靶标 | docstring |
| 4 | `drive-mi8-app-dialogs.js` | 真机(MI8)驱动：app 内跑对话框相关脚本（`\"rhino\"`，30s 窗口） | 首注释/代码 |
| 5 | `drive-mi8-sample-dialogs.js` | 真机(MI8)驱动：**只**驱动显式选中的对话框示例（45s 窗口） | 首注释 |
| 6 | `mi8-sample-plan.py` | 为全部随包 JS 示例生成 MI8（`ce4d2bdb`）执行计划；对产品代码与示例**只读** | docstring |
| 7 | `node24-ui-perf.py` | 在**空闲 MI8** 上跑 Node24 UI 性能探针并存基线/回归 JSON；**不碰 K40**、有活跃运行时拒绝启动 | docstring |
| 8 | `package-android-cpp-toolchain.py` | 从已验证的 Android-host NDK 归档构建**可选** ARM64 C/C++ 组件；产物 ZIP/manifest 走 LAN/HTTPS，**不放进 app assets** | docstring |
| 9 | `remote-engine-server.py` | 远程算棋服务（LAN 助攻，标注日期 2026-09-26） | 首注释 |
| 10 | `run-engine-audit-device.py` | 跑**一个显式选中**的示例并收集新的 Android 日志证据 | docstring |
| 11 | `summarize-engine-audit.py` | 把源码审计、运行期导出与逐文件编译证据**合并**；不把「导出」当成行为已验证 | docstring |
| 12 | `test-android-platform-shadowing.py` | `check-android-platform-shadowing.py` 的**策略回归用例**（unittest + 临时文件） | 代码结构 |
| 13 | `test-check-android-16kb.py` | Android **16KB 发布门禁**的策略回归用例 | docstring |
| 14 | `test-sift-device.js` | 真机回归：复制到**完整 SIFT 示例目录**运行（QuickJS，Rhino 改首行引擎标记）；结果写 `output/regression-*.json` | 首注释 |
| 15 | `test-ui-accessibility-device.js` | 真机 UI/无障碍检查（QuickJS/Rhino 同一份脚本，靠首行标记切换） | 首注释 |
| 16 | `upload-plugin-release.mjs` | 把**插件发布资产**上传到 GitHub Release（最新版）；需 `env:GITHUB_TOKEN` | docstring |
| 17 | `verify-mcp-alignment-device.py` | **专用测试设备**上的集成门禁（连接信息来自 context） | docstring |
| 18 | `verify-mcp-terminal.py` | 验证手机侧 MCP **管道终端**（只写唯一命名的探针目录；**不做**设备发现/安装/全局停止/下载） | docstring |

## ⚠️ 两个容易混的脚本（Lead 特别提醒，写在这里）

- `test-android-platform-shadowing.py` —— **疑似无主**（本名单第 12 项），是**测试**。
- `check-android-platform-shadowing.py` —— **不是**本名单成员：它被 **`tools/publish.ps1:280` 调用**（发布门禁）⇒ **绝对不要**当成无主删掉 ✗。
- 同理别混：`test-check-android-16kb.py`（测试，本名单）vs `check-android-16kb.py`（**被 `publish.ps1:275` 调用**的发布门禁）。

## 复算命令（怎么重新得到这 18 个）

~~~powershell
# ① tools 根脚本清单（.ps1/.py/.mjs/.js）
Get-ChildItem tools -File | Where-Object { $_.Extension -in '.ps1','.py','.mjs','.js' } | Select-Object -ExpandProperty Name
# ② 对每个脚本名统计「全仓文本提及次数」（排除自身文件）—— 提及 <=1 且 workflow 无引用、无 import 的进名单
#    完整脚本逻辑见 docs/reports/2026-11-12_全仓结构优化Phase1（W3：重复死代码索引）.md `3 的 ②b 复算命令
~~~

## 维护约定

1. **不移动文件**（移动会改路径，可能弄坏外部引用）；要「复活」某个脚本，就在对应报告里写清入口命令。
2. 新增脚本若暂时没有入口，**不用**加进本文件（本文件是「历史遗留索引」，不是脚本目录）。
3. 若某脚本被接入发布/门禁流程，请**从这里划掉并注明**（例：`check-android-platform-shadowing.py` 就不在本名单）。
