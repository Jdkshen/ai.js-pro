# MCP 文件/APK 工具箱与 Capstone 反汇编（dev-1315）

> 2026-11-02。接续 Codex 因额度用尽中途停止的工作（其已写 29 个文件 + `tools/build-toolkit-native.py`，但 native 库在真机上加载失败、资源 XML 测试失败、构建未通过）。本轮补齐并发布 LAN dev-1315。

## 一、交付内容

### 1. 60 个本地 MCP 工具（不依赖 MT）

| 组 | 工具 | 说明 |
|---|---|---|
| 文件 `file_*` | 20 个 | 读/写/补丁/复制/移动/删除/建目录/搜索/替换/截断/追加/搜索文本；写操作必须带 `targetVersion` 或原字节校验 |
| ZIP `zip_*` | 8 个 | 列表/读文本/读字节/测试/解压/创建/更新 |
| APK `apk_*` | 31 个 | 快照工作区 → 编辑会话（COW）→ 列表/搜索/读取/编辑/删除 → DEX（baksmali）/资源（ARSCLib）/ELF → 校验 → 打包/签名 |
| 状态 `toolkit_status` | 1 个 | 工具数、分组、真实限制 |

- 运行在独立 `:tools` 进程（`McpToolkitProvider`，私有 ContentProvider + 文件 IPC，请求≤20MiB、响应≤24MiB，避免 Binder 事务上限）；主进程 `App.kt` 对 `:tools` 提前返回，不初始化引擎/UI/调度器。
- `McpToolCatalog` 注册后公开工具从 61 个规范名增加到 **119 个规范名 + 9 个别名 = 128 个名称**；新增分类「文件与APK工具」（`McpToolCategories`）。
- Flutter 侧新增「文件与 APK 工具」页面（`toolkit_page.dart`，服务抽屉入口），`PocBridge` 增加独立 `toolkitCall` 线程池与 60s 慢调用超时。

### 2. 原生反汇编（Capstone 5.0.6）

- `apps/app/src/main/toolkit-native/binary_tools.cpp`：JNI `decodeWindow`，按 `e_machine` 选择 AArch64(183)/ARM(40)/x86_64(62)/x86(3)，输出 address/hex/mnemonic/operands/target/referenceKind/conditional/terminal/decoded/size；含 call/jump/ret 分组与 ARM64 条件分支识别。
- `tools/build-toolkit-native.py`：在授权 arm64 设备（K40 `cccc62c7`）用可选 clang r29 编译 Capstone 5.0.6（ARM/ARM64/X86）+ JNI，`-O2 -fPIC -ffunction-sections -Wl,--gc-sections -Wl,-s -Wl,-z,max-page-size=16384 -static-libstdc++`，拉回 `jniLibs/arm64-v8a/libaijs_binarytools.so`，写 `binary-tools.lock.json`（capstone/source/recipe/library 四个 SHA-256）。
- 新增 Gradle `verifyToolkitNativeFresh`（挂 `preBuild`）：source/recipe/library 三个哈希与 lock 不一致即失败，防止旧 `.so` 进包。

## 二、接手后修掉的问题

1. **编译失败**：`McpApkToolkit.search()` 里 `ignore` 在声明前使用（`Unresolved reference 'ignore'`）→ 提前声明。
2. **资源 XML 不可读**：ARSCLib 序列化把中文写成 `&#21407;&#20540;`，`apk_resource_read` 的 `valueXml` 与员工测试 `resourceXmlRoundTripAndVariantPreservesItsResourceId` 都失败 → 新增 `ToolkitResources.unescapeNumericRefs()` 还原数字字符引用（保留 `&amp;` 等命名实体），`xml()` 与 `decodeXml()` 都走它。
3. **原生库在真机加载失败**：`dlopen failed: cannot locate symbol "X86_ATT_printInst"`——构建漏了 `arch/X86/X86ATTInstPrinter.c`，且没开未定义符号检查 → 补源文件 + `-Wl,--no-undefined`，此类缺符号改为构建期失败。修复后 `disassembler=capstone-5.0.6`，覆盖 ARM64/ARM/Thumb/x86/x86-64。
4. **构建 daemon JVM 崩溃**（hs_err，C2 编译器 native OOM）出现 2 次 → 重试用 `JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1` 成功；属环境缓解，未写死进脚本。
5. **描述与实现不符**：`toolkit_status` 与 `apk_native_*` 帮助仍写「仅 AArch64 子集」，已改为「原生库可用时 4 架构，不可用回退有界 AArch64」。
6. 顺手补了 `:tools` 进程的 ARSCLib 资源排除（`frameworks/**`）、`proguard` keep `ToolkitNative`。

## 三、验证

- **JVM**：`:app:testMiuixCompatDebugUnitTest --tests "com.jdkshen.aijspro.mcp.*"` → **186 个测试 0 失败**（含 APK 快照不可变、会话版本校验、ZIP 读写、DEX outline、ELF 补丁、资源往返）。
- **Flutter**：`flutter analyze` 56 条均为既有 info / 3 个既有 warning，新文件 0 error。
- **构建**：debug 构建通过（含 `verifyToolkitNativeFresh`）；release 候选 dev-1314（144,042,031 B / SHA `eb8c7bea…`）与 dev-1315（144,042,959 B / SHA `a41adc8e…`）R8 构建通过。
- **MI8 真机（Android，dev-1315）**：
  - `tools/list` = 128 个名称（60 个 toolkit）；
  - fixture APK（含 3 个 ABI 的 ELF）打开/列目录/读文本/搜索正常；
  - `apk_native_inspect` → `disassembler=capstone-5.0.6`；
  - `.text` 反汇编：AArch64 `bti/nop/adr/b`、ARM `ldr/add/b/bx`、Thumb `movs/b`（2 字节）、x86 `push/sub/call` 全部 `decoded=true`；
  - `apk_native_analyze_control_flow` 输出有界直接控制流且标注 `complete=false`。

## 四、边界

- 原生 `.so` 仅 arm64-v8a；其它 ABI 保留 ELF 元数据/字节，反汇编回退有界 AArch64 子集。
- 没有完整 Java 反编译，DEX 只输出 Smali；正则用 RE2（无回溯/反向引用/环视）。
- APK 快照上限 512MiB、总量 1GiB；单条目分析 64MiB、文本 10MiB；`apk_native_patch_instructions` 仅 AArch64 等长补丁。
- 反汇编只作有界直接控制流，不证明间接调用目标或完整数据流。

## 五、复现

```powershell
# 1) 原生库（K40，需可选 clang 组件已装）
C:\Python314\python.exe -X utf8 tools\build-toolkit-native.py
# 校验 lock
C:\Python314\python.exe -X utf8 tools\build-toolkit-native.py --check

# 2) 单元测试 + debug 构建
. .\tools\jdk17.ps1
.\gradlew.bat :app:testMiuixCompatDebugUnitTest --tests "com.jdkshen.aijspro.mcp.*" --console=plain
.\gradlew.bat :app:assembleMiuixCompatDebug --console=plain

# 3) 发布候选
& .\tools\flutter-poc\build-release-candidate.ps1 -VersionCode 1315 -VersionName "dev-1315" -R8 -TestSigning -NoUsbDiscovery -MaxHeapMb 4096 -MaxWorkers 1
```

## 六、发布

LAN 更新源 `http://192.168.10.7:8080/update.json` → **dev-1315**（144,042,959 B，SHA-256 `a41adc8e44313ae6ca2c24040eb24600960cba9a36035a4780a229c0fd30fae1`），MI8 已装 dev-1315。GitHub 未推送、未建 Release（遵循发布约定）。
