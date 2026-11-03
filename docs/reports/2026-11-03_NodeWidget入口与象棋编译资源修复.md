# Node Widget 入口与 chess-auto 编译界面资源修复

日期：2026-11-03。基线：LAN / MI8 dev-1326。已发布 LAN：dev-1327。

## 已复现的根因

1. MI8 从应用内“示例 → Node 引擎 → Node.js → Node.js中使用UI → Widget原生生命周期”点击运行，日志报 `identifier is a reserved word: class`。同一源码通过 MCP 强制 Node 则可以启动。真实差异是首行 `"ui"; "nodejs";` 写在同一行；旧的按行识别器未识别第二个字符串指令，文件入口回落 Rhino。直接指定引擎的测试无法覆盖这个问题。
2. 用户原始 `chess-auto.zip` 已含 `node-port/build/project-res.apk`，并非完全没有资源。但 sidecar 按 dev-1303 的宿主资源表编译，摘要 `3b615b49…` 与 dev-1326 的 `fb8f9e48…` 不同，原生资源加载会拒绝。不能伪改摘要，应重新编译。
3. `ScriptHostStaging` 复用的 `NativeProjectStaging` 又会过滤整个 `build/` 及普通 APK，即使包已更新，外部项目进入私有运行目录时也会丢资源。原项目依赖固定 `.config-target` 自救，重命名后不可靠。

## 代码修改

- `JavaScriptSource` 共用前导指令读取，按字符串语句而非整行读取引擎及 UI/auto 模式；支持同一行多个指令、前导注释/BOM/use strict，保持显式强制引擎优先，遇到实际代码停止。
- `NativeProjectStaging` 在任意嵌套的 `build` 下仅保留 `project-res.apk` 和存在时的 `project-res.json`，沿用原复制大小/统计/错误清理；其他构建产物、普通 APK、`.git` 仍过滤。同步 `ScriptHostStaging` 说明。
- 用户 ZIP 解压副本重编 `node-port` 资源；UI 启动要求真实编译资源，缺包时给出路径和修复说明，避免继续用合成 ID 显示失败。原 ZIP 保持不动。

## 验证记录

- JDK17，`:engine:testDebugUnitTest :app:testMiuixCompatDebugUnitTest -PflutterPoc=true -PdevPluginUsbDiscovery=false`：Engine 623 + App 629，全部通过，无跳过。
- 新增 6 项指令识别测试、5 项转存资源回归。DP 独立会话完成转存代码及纯 JVM 验证，主助手审阅并执行上述正式 Gradle 测试。
- `aapt2 dump resources` 确认 `activity_main`、`page_appearance`、`page_engine`、`page_recognition` 和两个菜单；项目修改 JS 的语法检查通过。
- MI8 安装最终 R8 APK，安装包摘要核对一致。通过应用内真实示例页分别运行并正常关闭四项：
  - Widget原生生命周期：创建→render→rootViewCreated→inflated→mounted，点击计数 0→1，重建后计数复位并再次走生命周期。
  - Widget标签覆盖与晚注册：界面可见，真实点击产生“自定义按钮已点击”日志。
  - 自定义Widget属性：初值 3，真实点击后 4。
  - 自定义组件/colored-button：两按钮可见，点击均无异常。此例原本使用合并的 `nodejs ui` 指令，旧 1326 实机也能打开，本轮没有伪称它与其他三个相同根因，也未改其源码。
- 隔离象棋测试副本从“运行项目”按钮启动（外层 project.json → node-port/main.js），实际走 `ScriptHostStaging`。日志 `CHESS_UI_SMOKE_RESOURCE=true`、`C-RES compiled=true`、`绑定完成 page=0`；控制/引擎/识别三页通过实际点击切换，布局/开关/输入框/日志可见。表现优化日志 1,136 调用、errors=[]。
- 安装后的这轮 48 条 UI 日志无 ERROR，各任务正常结束。测试项目的公共目录、私有 staging 及临时 XML 已清理，只清理本轮自建目录，不调用全局停止。

## 范围

手机测试仅使用 MI8；象棋测试副本关闭自动开游戏，仅加载设置界面，不启动对弈、不操作 K40。此报告不把独立打包 APK、长时间游戏/识别或所有第三方 Widget 计为已验。

## 交付与局域网更新

- APK：`C:/Users/18101/Downloads/AIjsPro-dev-1327.apk`，144,099,804 字节 / 137.42MiB，较 1326 仅增 184 字节。
- APK SHA256：`b03e1638adf7a0374bf16cdc3b6e9355eef81cd8e93dd77bd5f850a75d504ff5`。
- LAN：`http://192.168.10.7:8080/update.json`，下载 `http://192.168.10.7:8080/AI-js-Pro-Flutter-dev-1327.apk`。沿用既有 LAN 开发通道相同签名，复用已在 MI8 实装验收的原包发布；不 push、不创建远程 Release。
- 工程修复包：`C:/Users/18101/Downloads/chess-auto-NodeUI修复-dev1327.zip`，16,208,683 字节，SHA256 `fca3b13f7e5170b364650f69421ecbf4be4206b929da2fca7dee84e160c14ba6`；原 `chess-auto.zip` 完整保留。
- ZIP 逐文件比对：仅资源 APK/sidecar、UI 的资源加载诊断、历史资源文档提示这四项改变；新增 `NodeUI修复说明.md`，排除旧 `build/_x` 临时反编译文件。其余源码、模型、资源源码与配置逐字节保持原样。ZIP CRC 全部通过。工程 ZIP 仅本地交付，没有上传到 LAN。
- 资源 APK SHA256：`a184a9ddac1c0dbecb4fe33aaacdfddfb9c8d07c2528c39d045041dd9bd9df04`；1327 宿主资源表摘要仍为 `fb8f9e481a2ff43269eda13695c556ca30a4d192222a332bf5195aa1f656dc5e`。已使用最终 1327 APK 真正重新编译，不改摘要绕过检查。
- 构建完成 R8、签名、23 ELF/16KB、Retrofit 及平台 API 阴影守卫；发布器重新核对安装摘要及更新清单。PC/MI8 均已通过真实 LAN 地址完整读取 144,099,804 字节，摘要与清单、已安装包和 Downloads 副本一致，结果见 `lan-verification.json`。

证据均在 `.artifacts/chess-auto-20261103/`：`regression.log`、`1327-final-ui-logs.json`、四个 Widget 的 XML/截图、象棋三页 XML/截图、`delivery-verification.json`、`publish-1327.log`、`lan-verification.json`。

内置独立 APK 模板本轮未刷新，独立打包产物不计入验收；后续刷新模板后仍需独立 APK 回归。宿主示例和用户工程资源问题已在以上范围验收。
