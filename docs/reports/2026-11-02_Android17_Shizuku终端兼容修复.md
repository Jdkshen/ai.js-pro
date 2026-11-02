# Android 17 Shizuku 终端兼容修复

本轮承接 dev-1311 的无障碍修复，处理新手机上通用 Shizuku 命令仍无法启动的问题。**dev-1312 最终候选已安装到新手机，并通过下列验收。**手动包已放 Downloads 和 LAN，自动更新清单仍为 dev-1305。

## 问题与修改

新手机 `192.168.10.4` 为 Redmi 2602BRT18C、Android 17 / SDK 37、OS4.0.3.0.XPLCNXM，Shizuku 13.6.0-a17.1 已运行并授权，实际后端 UID 为 2000。原 UserService 启动器在系统 `LoadedApk.makeApplicationInner` 空指针退出，尚未进入我们的命令服务。dev-1311 已让无障碍启用绕过该路径，但通用终端仍需单独修复。

这次修改共用执行层，覆盖 App 终端的 Shizuku 后端、`rish` / `i`、MCP `shell_rish` / `terminal_exec`，以及 Rhino、QuickJS、Node 的 `shizuku.shell`。

1. SDK 37 及以上采用兼容引导。通过 Shizuku 现有进程接口，从**已安装、只读的本应用 APK**启动自己的 `app_process` 主类，直接创建原 `ShizukuUserService`，避开 `makeApplication`。较旧系统保留原 UserService 入口。
2. 服务通过受 `INTERACT_ACROSS_USERS_FULL` 保护的 Provider 交还 Binder。应用自身登记一次性 UUID、所有者 Binder 和回调；只接受登记时指定的 shell/root UID，令牌 15 秒失效、只可兑换一次，并限制待交接数量。Provider 不提供命令执行接口。
3. 每个客户端进程复用连接；引导进程持有客户端所有者，进程死亡会销毁自己的命令服务。实际命令仍走原有 AIDL、文件描述符、进程组、单任务停止与安装会话清理，不把用户命令直接交给旧进程接口。
4. 继续逐次检查 Shizuku 在线状态和权限；断连时清理本客户端服务，启动失败返回实际错误。引导退出判断使用 `ShizukuRemoteProcess.alive()`，避免 Binder 改写 `exitValue()` 的运行中异常后误判失败。
5. 更新宿主内置瘦身模板、API 文档及 App/MCP 的生成索引。外置打包插件模板不变；独立 APK 需重新打包才能包含本轮代码。

初版 Unix 本地套接字交接在这台新系统上被拒绝（`Permission denied`），已弃用。当前实现没有本地 TCP 监听，也不回落成普通应用 UID 冒充 Shizuku 成功。

协议参考：[Shizuku API](https://github.com/RikkaApps/Shizuku-API)、[服务 AIDL](https://github.com/RikkaApps/Shizuku-API/blob/master/aidl/src/main/aidl/moe/shizuku/server/IShizukuService.aidl)、[远端进程实现](https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/ShizukuRemoteProcess.java)、[官方启动器 Provider 交接](https://github.com/RikkaApps/Shizuku/blob/master/starter/src/main/java/moe/shizuku/starter/ServiceStarter.java)。

## 使用方式

在 AI.js Pro 的终端中将执行后端选为 **Shizuku**，输入：

```sh
id
```

预期包含 `uid=2000(shell)`。如果终端保持“应用”后端，可输入：

```sh
rish -c 'id'
```

MCP 可直接调用：

```json
{"name":"terminal_exec","arguments":{"command":"id","backend":"shizuku","timeoutSeconds":10}}
```

持续任务按返回的 `jobId` 调用 `terminal_read`、`terminal_write`、`terminal_stop`。编译组件安装、C/C++ 编译沿用应用终端及 `aijs-toolchain`；本轮没有捆绑或重新下载工具链。

## 验收与交付

- 本地 JVM：engine 616、App 492、inrt 9，共 1117 项，失败/错误/跳过均为 0。
- API 文档生成器 10 项测试通过；生成索引 `--check` 通过，783 条记录、30 个来源。
- Provider 登记权限：ADB shell 直接调用 `register` 被拒绝，返回“仅本应用可登记执行进程”。
- 最终 release/R8、签名、22 个 ELF 的 16KB 静态检查、Retrofit 泛型/注解检查及模板校验通过。

| 新手机实测 | 结果 |
| --- | --- |
| MCP `terminal_exec` 的 Shizuku 后端、应用后端 `rish`、`shell_rish` | 均返回真实 `uid=2000(shell)` |
| stdout / stderr 与退出 7 | 输出保留、退出码为 7，不报成功 |
| 环境变量、中文/空格目录、引号/美元符号、stdin/EOF | 原值一致；越出允许范围的工作目录仍被拒绝，未放宽路径授权 |
| 单停 Shizuku 和应用 `rish` 各一组 | 子进程消失、任务 STOPPED，并行的另一任务正常完成 |
| 超时 2 秒 | TIMED_OUT、exitCode=130、processActive=false，子进程消失 |
| 120000 字节连续输出 | 无堵塞，按 offset 读取到末尾标记 |
| Rhino / QuickJS / Node | 均核对身份、独立 stdout/stderr 与退出 7；Node 等待期间 20ms 定时器触发 28 次 |
| 所有者意外死亡 | 仅专用 `:node24_1` 测试工作进程自杀，所属 sleep 子进程被清理；主应用和 MCP 存活，下一次 Node 调用成功 |
| 私有 APK 与 split 安装 | 含中文/引号/美元符号的私有路径安装成功；split 实际得到两个 package 路径；坏 APK 返回失败和真实原因 |
| 服务空闲释放与重连 | 旧引导进程退出，下一次 `id` 成功；一次重连 297ms、紧接复用 54ms，仅为该次观察值 |
| 可选 C/C++ 组件 | 使用手机原有组件，C/C++ 实际编译并运行，`.so` 编译成功；未重新下载或修改组件 |
| 无障碍与清理 | 最终 ax=true、Shizuku 授权仍有效；临时包、公有/私有测试文件已清，activeMcpRuns/activeTerminalJobs/overlayCount 均为 0 |

终端可视界面本轮没有做逐按钮操作回归：手机前台正被另一客户端使用，避免争用。以上命令通过实际 MCP 执行，复用的就是 App 终端后端；不将后端验收写成完整界面验收。

最终包：

- 版本：`dev-1312` / versionCode `1312`；沿用开发通道测试签名。
- 大小：`142800971` 字节（136.19 MiB），比 dev-1311 增加 `30088` 字节（29.38 KiB）。
- SHA-256：`3be39fafd6eb1317eee5ccc3ac82d0f27acc1517df603dcc4ba7bac31ca017e7`。
- Downloads：`C:/Users/18101/Downloads/AIjsPro-dev-1312-Shizuku终端修复.apk`。
- [LAN 手动下载](http://192.168.10.7:8080/AI-js-Pro-Flutter-candidate-dev-1312.apk)。PC 完整 HTTP 下载和手机已安装 base.apk 的 SHA 均与最终包一致。
- 瘦身模板：`20416533` 字节，SHA-256 `eef4be353823ab8b8133ee2d426f2ccb406a1a57c426b827ffb251a989a6f21d`；16 个剔除库对账通过，外置插件模板未改。

本地证据位于 `.artifacts/shizuku-terminal-20261102/`：`verify-binder-final.log`、`engines-binder-final.log`、`install-binder-final.log`、`private-cleanup-final.json`、`idle-reconnect.json`、`node-recovery.json`、`cpp-newphone.json`、`delivery.json`、`cleanup.json`、`final-status.json`。安装测试的清理脚本首轮因占位符误替换而报语法错误，之后单独清理成功，证据见 `private-cleanup-final.json`；这不是产品 API 错误。

构建途中一次因电脑系统提交内存不足而退出；将构建堆限制为 1536MiB 后完成 R8 最终包，未绕过 R8 或校验。中间失败的套接字包、旧退出检测包均没有放入 Downloads/LAN 手动交付路径。

## 边界

- 本轮目标是这台 Android 17 新手机的 Shizuku 命令兼容，不代表所有厂商系统已覆盖。
- 较旧系统分支保留，但本轮未重新连接旧机、MI8 或 K40 做硬件回归。
- 独立 APK 的新模板已构建并做结构校验，本轮没有另建独立 APK 做新手机运行回归；不能将宿主测试计为独立 APK 实测。
- root/Sui、多用户、真实 16KB 内核尚未实测；APK ELF 对齐静态检查不能替代真实内核验收。
- 兼容引导依赖当前 Shizuku 仍支持的旧进程接口；它仅用于启动受控引导，未来管理器移除此接口时需再次适配。
- 主更新源保持 dev-1305，候选包手动交付；不自动推送代码、不创建逐版本 GitHub Release。
