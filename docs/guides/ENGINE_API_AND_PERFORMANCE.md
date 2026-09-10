# 双引擎 API 与 UI 性能验证

## Miuix JVM 测试

仓库路径包含中文时，Windows 上的 Gradle Test Worker 可能无法加载测试类。使用临时 ASCII 盘符运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\test-miuix.ps1
```

脚本结束后会自动删除临时盘符映射。

## Rhino/QuickJS API 差异

1. 在手机 MCP 页启动服务，并开启脚本运行授权。
2. 运行 `powershell -ExecutionPolicy Bypass -File .\tools\compare-engine-api.ps1 -Device cccc62c7`。

脚本默认使用 USB Host 固定地址 `http://127.0.0.1:18790/mcp`，并自动执行
`adb forward tcp:18790 tcp:8788`。如果手机端修改过 MCP 端口，使用
`-DevicePort <端口>`；如果已自行配置网络或转发，传入 `-Url` 并加
`-SkipAdbForward`。

结果保存到 `.artifacts/engine-api/engine-api-diff.json`，包含两引擎的共有 API 和单边缺失 API。

2026-09-08 K40 实测基线（大小写精确比较并去重）：Rhino 225 项、QuickJS 57 项、共有 50 项、Rhino 独有 175 项、QuickJS 独有 7 项。该数量用于发现桥接变化，不代表 API 行为已经等价。

## 真机滑动帧耗时

将手机停在待测长列表页，然后运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\measure-ui-performance.ps1 -Device cccc62c7
```

脚本会重置 `gfxinfo`、执行往返滑动，并输出总帧数、卡顿帧和 P50/P95/P99 帧时间。原始报告保存到 `.artifacts/performance/`，用于保留同机、同页面的前后对比。

2026-09-08 K40 首次长列表基线：418 帧、4 个卡顿帧（0.96%）、P50 8 ms、P95 11 ms、P99 11 ms。后续比较必须停在相同页面、使用相同脚本数量与滑动次数。

## Debug APK 基线

> 记录时间 2026-09-08，对应版本 `1.0.1 (464)`；项目当前版本为 `1.0.2 (465)`。以下体积与哈希为历史快照，重新测量后请更新本节。

- 包名/版本：`com.jdkshen.aijspro`，`1.0.1 (464)`；
- 变体/ABI：`miuixDebug`，`arm64-v8a`；
- 文件大小：155,300,827 字节；
- SHA-256：`8DE843DEFDE2005C68AFFA625763C3F4FE142499051B509DD32BA075E401A5D7`；
- K40 覆盖安装、冷启动通过；APK 不包含 `libautojs_imgui.so`。
