# VS Code / Auto.js Pro 扩展自动连接

适用：本项目的 AI.js Pro 与本机安装的 `hyb1996.auto-js-pro-ext` 9.0.9。依据是该扩展 `dist/extension.js` 的实际 TCP 协议实现；不代表已经实现其全部调试功能。

## 为什么以前要反复输入 IP

手机的 `DevPluginAutoStart` 已能恢复“Pro 扩展服务器”开关，但恢复服务器只是监听 9317，等待电脑连接。

扩展虽然把连接过的 IP 存入历史，其启动逻辑仅恢复记录的 ADB RPC 设备，没有遍历旧协议 IP 并自动重连。因此“服务自动开启”和“电脑自动连接”以前是两件没有接起来的事。

## 使用方式

1. 手机打开 AI.js Pro，开启侧栏的 **Pro 扩展服务器**。
2. 电脑 VS Code 使用 **Auto.js Pro: 连接到新设备 → 输入设备 IP 地址（仅适用 Pro 9.2 及之前版本）**，首次填写手机侧显示的 IP。
3. 握手成功后，手机自动记住这台电脑的实际 IP。以后断线、重开手机应用或重新启动电脑扩展时，手机会主动回连，无须每次重新输入手机 IP。
4. 停止自动连接：关闭手机的 **Pro 扩展服务器**。重新开启后仍记住上一台电脑。

电脑扩展必须已经激活并在运行（例如打开 JavaScript 项目或执行它的命令）。手机调试服务也必须开启；被系统杀掉后，再打开 AI.js Pro 会按保存的开关恢复服务。

电脑 IP 更换时，先手动 IP 连接一次，手机会更新记忆。当前实现只记住最近一次成功连接的电脑，不扫描整个局域网，也不提供跨网络自动发现。

## 实现

```text
首次：VS Code ──TCP──> 手机:9317
                    hello 握手成功 → 保存电脑地址

以后：手机 ──TCP──> 已记住的电脑:8327
                  扩展现有 LegacyDeviceClient.accept()
                  同一套 hello / command / log / bytes_command
```

扩展自身已有 8327 接收端，因此不需要改装电脑插件，也不需要 V8。连接层与脚本执行引擎分开，收到脚本/工程后仍走项目现有的 Rhino / QuickJS 执行流程。

主要代码：

- `apps/app/src/main/java/com/jdkshen/aijspro/pluginclient/DevPluginServer.java`：学习握手对端、自动回连、会话去重、网络切换清理、停止时取消。
- `DevPluginAutoStart.java` / `DevPluginPrefs.java`：沿用已有的开关持久化与回前台启动逻辑。
- `DevPluginServerService.java`、`DrawerFragment.java`：展示已记住电脑及自动回连状态。

连接超时 3 秒、握手超时 10 秒。重试间隔退避至最多约 30 秒（另有调度周期），离线不反复弹提示。已有同电脑连接或正在握手时不重复拨号。Wi-Fi 丢失时释放失效会话，避免把半断开的 TCP 当成仍然在线。USB 转发的回环地址不会被记成电脑 LAN 地址。

## 边界与排查

- 手机连电脑需要电脑允许 VS Code 接收入站 TCP **8327**；电脑主动连手机仍使用手机的 **9317**。如果首次 IP 能连，但回连失败，先确认扩展已激活，再检查电脑入站防火墙及网络隔离。
- 发布包保留与官方 Pro 共存的配置：关闭冲突的 USB 自动发现 provider。这不会关闭 LAN 连接或本页的自动回连。
- 扩展的“断开所有连接”会关闭它的旧协议 8327 接收端。想恢复手机主动回连，需要让扩展重新启动（重载 VS Code 窗口）；手机调试开关关闭则始终不主动回连。
- 这次处理自动连接，不等同于完整支持官方的断点调试、设备文件浏览、布局分析或 Pro 新版 RPC。

## 验证与发布

- 构建通过，187 项单元测试通过。
- `DevPluginReconnectTest` 3 项真机测试通过：首次记忆、断线恢复、服务重启恢复、在线不重复连接、关闭后不再连接、USB 回环不记忆、缺失握手超时释放。
- K40 与本机实际运行的 VS Code 连接通过：首次 IP 握手并完成运行/日志往返后主动断开测试连接；手机随后主动连接电脑 8327，Windows 显示连接由真实 `Code.exe` 接收，手机显示同一条已建立 TCP 连接。
- 本轮没有主动断开用户 Wi-Fi；网络丢失回调的实网切换行为仍需专门回归。
- 测试日志：`.artifacts/dev-plugin-reconnect-build.log`、`.artifacts/dev-plugin-reconnect-device-tests.log`。
- 已发布并安装 **dev-705** 到 K40，手机确认 `versionCode=705`。安装后打开应用，未再输入 IP，自动建立新连接：手机 `192.168.31.59:46638` → 电脑 `192.168.31.109:8327`，由 `Code.exe` 接收；证据 `.artifacts/dev-plugin-reconnect-final-connection.log`。
- 更新包：`.artifacts/updates/app-miuix-compat-arm64-v8a-dev-705-no-usb-debug.apk`。
- SHA-256：`76b8c5f73550e1db5d5db90b4988fd09a27d6dd7d05c5437cfeb4d4af0335f07`。发布器已验证校验和与手机访问更新源 HTTP 200。
- 保留用户的调试开关，已记住本机电脑地址；测试辅助 APK 已卸载，电脑插件没有改动。
