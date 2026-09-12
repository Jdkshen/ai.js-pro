# AGENTS.md

给 AI 助手 / 自动化 agent 的项目约定。dsh（DeepSeek Harness）、Codex、Copilot、Claude Code 都会读这个文件。

## 先用 CodeGraph，再考虑 grep

本仓库已经建好 CodeGraph 索引（`.codegraph/`，被 git 忽略；用法见 `docs/guides/CODEGRAPH.md`），CLI 已在 PATH 上（`codegraph`）。

- 找「某功能怎么工作 / X 在哪 / 调用链 / 影响面」→ `codegraph explore "<符号名或一句话问题>"`
  （一次调用返回相关符号的原文 + 它们之间的调用路径，比 grep + 逐个 Read 准得多、也省 token）
- 读单个符号（含 caller/callee 线索）或按行号读整文件 → `codegraph node <symbol>` / `codegraph node <file>`
- 谁调用了它 → `codegraph callers <symbol>`；它调用了谁 → `codegraph callees <symbol>`；改动影响面 → `codegraph impact <symbol>`
- 只按名字找位置 → `codegraph query <名字>`
- 索引落后约 1 秒（有文件 watcher 自动同步）；必要时 `codegraph sync`，状态用 `codegraph status`
  （长时间没动过仓库时，`status` 可能报几十上百个 pending 文件，先 `sync` 再查，否则会「查不到」）
- ⚠️ `codegraph impact` 在 Kotlin/Compose 大文件上会把同构的 UI 类误连（同类 Activity/Fragment 一起报进来），
  **精度低，别直接当调用方用**；影响面建议落到具体成员上查 `codegraph callers <成员名>`

只有 CodeGraph 不索引的东西（YAML/JSON/文档/构建脚本）才回去用 grep / 直接读文件。

## 构建与测试

- 默认 JDK 是 8，**必须先切 17**：`. .\tools\jdk17.ps1 | Out-Null`
- 日常变体（MIUIX 皮肤）：`.\gradlew.bat :app:assembleMiuixCompatDebug --console=plain`
- 单元测试：`.\gradlew.bat :app:testMiuixCompatDebugUnitTest`
- 产物：`apps/app/build/outputs/apk/miuixCompat/debug/app-miuix-compat-arm64-v8a-debug.apk`
- 一键出 dev 包并发布到本地更新源：`tools\dev-update.ps1 -NoServe -ReleaseNotes "…"`
  （版本号自动 +1，产物落在 `.artifacts/updates/`）
- 装机验证：`C:\Android\platform-tools\adb.exe -s ce4d2bdb install -r <apk>`（小米 8 = 首选真机）

## 本仓库踩过的坑：发布与脚本（改之前先读）

这几条都是真实发生过的事故，不是理论风险。

### `tools/dev-update.ps1` 会发布，`-NoServe` 拦不住

`-NoServe` 只跳过**起 HTTP 服务**；**写 `update.json` + 拷贝 APK 的发布动作照常执行**。

- 用**任意假版本号**试脚本，会把这个假版本发布到真实更新源。曾用 `-VersionCode 9999` 试
  `-DeviceId`，结果更新源变成 9999、手机永远提示「发现新版本」，历史里也多出一条垃圾记录。
- 只想验证参数解析/报错路径时，必须同时满足：加 `-SkipBuild`、**且**用一个临时 `-Dir`
  （或干脆别碰真实源）。任何会让它走到「发布」这一步的调用都要先想清楚。
- `-SkipBuild` 时版本号读自现有 APK；不指定 `-VersionCode` 时会退回读**仓库**的
  `project-versions.json`（通常是 465），会把历史污染成 465。

### PowerShell 脚本的路径：别依赖 cwd 或 `$PSScriptRoot`

- `$PSScriptRoot` 在 `powershell -File` / 从别的目录调用时**可能不是你期望的值**。
  `tools/serve-updates.ps1` 曾把默认输出目录算成 `C:\.artifacts\updates`（仓库外），
  照样打印成功路径与 SHA —— 清单写丢了却毫无提示。现已改为用 `$MyInvocation.MyCommand.Path`
  推断仓库根并校验 `project-versions.json` 存在。
- 自己写临时脚本时：**先把目标路径 `Resolve-Path` 成绝对路径再 `Push-Location`**，
  否则相对路径会解析到临时目录里（本仓库已因此多次误判「文件不存在」）。

### PowerShell 5.1 的编码

- PS 5.1 读**无 BOM** 的 UTF-8 `.ps1` 会按 GBK 解码。含中文注释/输出的脚本会显示乱码，
  更糟的是中文字节可能让引号错位、直接**解析报错**（报错行号会指向完全无辜的代码行）。
- 本仓库脚本混用：`tools/*.ps1` 多数无 BOM，根目录 `release.ps1` / `build-miuix-debug.ps1` 有 BOM。
  **给脚本加中文控制台输出时，必须补 UTF-8 BOM。**
- ⚠️ 用文件编辑工具改完 `.ps1` 后要**回头确认 BOM 还在**——部分工具重写文件时会去掉它。
- 读这些脚本/JSON 时显式指定编码，别用 `Get-Content` 默认值：
  `Get-Content -Encoding UTF8` 或 `[IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)`。

### `$ErrorActionPreference = 'Stop'` 下跑原生命令会假失败

Gradle/JVM 会把 `注: 某些输入文件使用或覆盖了已过时的 API` 写到 **stderr**；
`Stop` 模式把原生命令的任何 stderr 当终止错误，脚本以 1 退出 —— **即使构建其实 BUILD SUCCESSFUL**。
判断成败只认 `$LASTEXITCODE`，必要时临时放宽 `ErrorActionPreference`。

## 仓库结构

- `apps/app`：Android 应用（Miuix/Compose 皮肤与旧界面并存）
- `modules/engine`：脚本引擎 + QuickJS 原生；`modules/engine-rhino`：Rhino 引擎；`modules/common`、`modules/automator`
- `inrt`：打包产物用的运行时模板；`third-party/`：第三方库
- `docs/`：架构 / 指南 / 计划 / 报告，索引见 `docs/README.md`；`tools/`：构建与真机脚本
- `.artifacts/`：本地构建、截图、日志产物，**被 git 忽略**（点开头目录，部分 UI 预览不了里面的图）

## 注意

- 不要把 `.artifacts/`、`build/`、`*.log` 之类产物提交进 git
- 改过 `modules/engine/src/main/cpp/` 下的原生代码后，要先跑 `modules/engine/src/main/cpp/build-quickjs.ps1` 再构建，
  否则 `:app:verifyQuickJsNativeLibrariesFresh` 会失败
- 打包页的权限默认值不要随手改（产物的权限画像是风控敏感点，见 `docs/PERMISSIONS_AND_RELEASE.md`）
- 示例脚本文本改动要满足 `:app:verifyQuickJsSamplesMarked`、`:app:verifyBundledSamplesNoNode` 两个校验任务
