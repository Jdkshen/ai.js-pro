# Gadget ELF 16 KiB 页对齐调研（2026-11-06）

> 范围：只做调研，不改产品源码、不构建、不安装、不提交 git。
> 样本：LAN 组件 `frida-gadget-arm64-v1.zip`（55,207,283 B，sha256 `ae345aa9329d8a417944287cb7eaad20431c03f123fc56ffae091bc7026231c7`）；K40 dev-1379 上的 `apk_inject_gadget` 历史任务结果；目标样本原包 `yj-orig.apk`（sha256 `ffe1365a5cb35978bbd36714feddb104ac13ffaff5e1973166e3c20e5fdb7cff`）。
> 设备：K40 `cccc62c7`（SDK 36）、`.4`（SDK 37）实测页大小均为 **4096 B**。

## 1. 方法

1. 从 `http://192.168.10.7:8080/components/frida-gadget-arm64-v1.json` 读取组件清单，下载同目录 zip，核对 `size=55207283` / `sha256=ae345aa9…31c7`。
2. 解包 4 个 ABI 的 `gadget/<abi>/libfrida-gadget.so`；按 `upstream-lock.json` 的 `elfSha256` 逐个核对，4 个库均与官方 Frida 17.25.2 产物一致：
   - arm64-v8a `de481b674ff894a390f5c490430bda090ed2afbe487f880f9a49188d2a40a1b4`
   - armeabi-v7a `ce4deb9335bdc23fb687eaa1d31ab18de4804fa6536ad725031e4b2d22597d54`
   - x86 `d98d50d26c5281d2f0a250c2d3fdde1c95064be951022bbfa3dffc52321267f9`
   - x86_64 `f3f3c12c76b37aaf5336c5551643937236766418cea4ac92841f262adcc382ac`
3. 自写 ELF parser 读取 ELF header 的 `e_phoff/e_phentsize/e_phnum`，只筛 `PT_LOAD`，读取 `p_type/p_flags/p_offset/p_vaddr/p_filesz/p_memsz/p_align`；判定规则与 `ToolkitApkVerifier` 一致：
   - `p_align` 是 2 的幂且 `>= 0x4000`；
   - `p_offset % 0x4000 == p_vaddr % 0x4000`。
4. 另用 MCP 历史结果核对 `apk_inject_gadget` 的 5 条告警，并对**原始未注入 APK** 调 `apk_verify` 做对照，避免把目标 APK 的问题归因 Gadget。

原始数据：`output/gadget-16k-research/elf-loads.json`；哈希/清单：`output/gadget-16k-research/extracted/upstream-lock.json`。

## 2. Gadget 四个 ABI 的 PT_LOAD 全表

4 个库共 12 个 `PT_LOAD`。`PASS` = p_align 与 `p_offset/p_vaddr` 同余同时满足；`FAIL` 至少一项不满足。

| ABI | 文件 | seg | p_flags | p_offset | p_vaddr | p_filesz | p_memsz | p_align | align16K | congruent16K | 判定 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|
| arm64-v8a | libfrida-gadget.so | 1 | 5 | 0x0 | 0x0 | 0x245a880 | 0x245a880 | 0x4000 | true | true | PASS |
| arm64-v8a | libfrida-gadget.so | 2 | 6 | 0x245a880 | 0x245e880 | 0x1108e8 | 0x111780 | 0x4000 | true | true | PASS |
| arm64-v8a | libfrida-gadget.so | 3 | 6 | 0x256c000 | 0x2574000 | 0x28f18 | 0x9ccb8 | 0x4000 | true | true | PASS |
| armeabi-v7a | libfrida-gadget.so | 1 | 5 | 0x0 | 0x0 | 0x15e0eb0 | 0x15e0eb0 | 0x1000 | false | true | FAIL |
| armeabi-v7a | libfrida-gadget.so | 2 | 6 | 0x15e0eb0 | 0x15e1eb0 | 0x95b78 | 0x96150 | 0x1000 | false | false | FAIL |
| armeabi-v7a | libfrida-gadget.so | 3 | 6 | 0x1677000 | 0x1679000 | 0x16acc | 0x61211 | 0x1000 | false | false | FAIL |
| x86 | libfrida-gadget.so | 1 | 5 | 0x0 | 0x0 | 0x2b5d340 | 0x2b5d340 | 0x1000 | false | true | FAIL |
| x86 | libfrida-gadget.so | 2 | 6 | 0x2b5d340 | 0x2b5e340 | 0x8fbbc | 0x8fcc0 | 0x1000 | false | false | FAIL |
| x86 | libfrida-gadget.so | 3 | 6 | 0x2bed000 | 0x2bef000 | 0x16b40 | 0x501fc | 0x1000 | false | false | FAIL |
| x86_64 | libfrida-gadget.so | 1 | 5 | 0x0 | 0x0 | 0x2502d30 | 0x2502d30 | 0x4000 | true | true | PASS |
| x86_64 | libfrida-gadget.so | 2 | 6 | 0x2502d30 | 0x2506d30 | 0x100f30 | 0x1012d0 | 0x4000 | true | true | PASS |
| x86_64 | libfrida-gadget.so | 3 | 6 | 0x2604000 | 0x260c000 | 0x29530 | 0x880e0 | 0x4000 | true | true | PASS |

### 2.1 逐 ABI 归纳

| ABI | ELF | LOAD 数 | 失败 LOAD 数 | 失败段 | 说明 |
|---|---|---:|---:|---|---|
| arm64-v8a | ELF64 LE, machine=183 | 3 | **0** | - | 3 段 p_align 均为 0x4000，offset/vaddr 同余，**已是 16 KiB 兼容** |
| armeabi-v7a | ELF32 LE, machine=40 | 3 | **3** | 1,2,3 | p_align 均 0x1000；段 2/3 同余也不成立 |
| x86 | ELF32 LE, machine=3 | 3 | **3** | 1,2,3 | p_align 均 0x1000；段 2/3 同余也不成立 |
| x86_64 | ELF64 LE, machine=62 | 3 | **0** | - | 3 段 p_align 均为 0x4000，offset/vaddr 同余，**已是 16 KiB 兼容** |

结论（仅就组件自身）：**64 位 ABI 的 Gadget 已经满足 16 KiB；32 位 ABI 的 Gadget 不满足，且 32 位不是只改 p_align 就能修（见 §4）。**

## 3. 这次 5 条 `partialReasons` 到底来自哪里

K40 历史任务 `9b4a2720-4b26-431b-9def-589431324e7c`（`apk_inject_gadget`，SUCCEEDED）的完整结果：
- 目标原包 `yj-orig.apk`：sha256 `ffe1365a…cff`，package `xxx.xx`，仅 `arm64-v8a` ABI；
- 输出 `yj-gadget4.apk`：sha256 `f76830c0…8010`，size 22,525,739，`installable=true`，`installed=false`；
- 输出里实际注入的 `abis=["arm64-v8a"]`；
- 验证的 native 库共 8 个：1 个 config（非 ELF）、1 个 Gadget、6 个目标 APK 原生库；
- `native.issueCount=5`，全部是 `ELF_PT_LOAD_ALIGNMENT_16K`。

**5 条告警是 2 个目标 APK 原生库的 5 个 `PT_LOAD` 段，不是 4 个 Gadget ABI，也不是“4 ABI + 第 5 个文件”：**

| # | entry（目标 APK） | seg | p_flags | p_align | p_offset | p_vaddr | congruent16K | 失败原因 |
|---|---:|---:|---:|---:|---:|---|---|---|
| 1 | `lib/arm64-v8a/libroot_automator.so` | 2 | 5 | 0x1000 | 0x0 | 0x0 | true | 只有 p_align < 0x4000 |
| 2 | `lib/arm64-v8a/libroot_automator.so` | 3 | 6 | 0x1000 | 0x2400 (9216) | 0x3400 (13312) | false | p_align 不足 + 不同余 |
| 3 | `lib/arm64-v8a/libroot_automator.so` | 4 | 6 | 0x1000 | 0x2788 (10120) | 0x4788 (18312) | false | p_align 不足 + 不同余 |
| 4 | `lib/arm64-v8a/libyj_aj_02_309.so` | 2 | 6 | 0x4000 | 0x3750 (14160) | 0x4750 (18256) | false | p_align 已是 16K，但 offset/vaddr 不同余 |
| 5 | `lib/arm64-v8a/libyj_aj_02_309.so` | 3 | 6 | 0x4000 | 0x3ab8 (15032) | 0x5ab8 (23224) | false | p_align 已是 16K，但 offset/vaddr 不同余 |

### 3.1 对照证据（关键）

对**原始未注入的 `yj-orig.apk`** 单独执行 `apk_verify`：
- `native.status=partial`、`native.issueCount=5`；
- 5 条与上表完全相同（同样是 `libroot_automator.so` 3 段 + `libyj_aj_02_309.so` 2 段）；
- 说明这 5 条在注入前就存在，**不是 Gadget 引入的**。

输出 APK 中 Gadget 自身：
- `lib/arm64-v8a/libaijsgadget.so`：3 个 LOAD，p_align 全部 0x4000，3 段同余，`allPtLoad16KbCompatible=true`；
- `lib/arm64-v8a/libaijsgadget.config.so`：非 ELF，不参与 PT_LOAD；
- 因此输出中 native 失败的库只有目标 APK 的 2 个；当前样本的 Gadget 注入没有新增 16K 告警。

### 3.2 为什么返回体看起来像 Gadget 的错

`AndroidReverseRuntime.gadgetPartialReasons()` 只汇总 `checks.native` 的 `messages` 和 `errorCodes`，没有把每条 issue 的 `entry` 带进 `partialReasons[]`；这 5 条消息文本完全一样，所以从 summary 看不出来源。`upstream:true` 现在按“整个 native check 含 ELF_PT_LOAD_ALIGNMENT_16K”赋值，在这个样本上会把**目标 APK 的库**误标为上游 Gadget 问题。这是返回体的归因缺陷，不是 Gadget 二进制缺陷。

## 4. 可选修法可行性

### (a) Frida 上游是否有 16 KiB 变体或更新版本

- 官方 17.25.2 GitHub release 的 Android Gadget 资产只有四个按 ABI 的 `.so.xz`：
  - `frida-gadget-17.25.2-android-arm.so.xz` (7,898,568 B)
  - `...-android-arm64.so.xz` (9,847,476 B)
  - `...-android-x86.so.xz` (11,076,724 B)
  - `...-android-x86_64.so.xz` (10,006,464 B)
  没有名为 `16k`/page-size 的独立变体。
- 最新 release（截至本调研为 **17.26.1**，2026-11-06 发布）仍是同样四个 Android Gadget 资产名；**没有单独的 16 KiB 变体**。
- 直接解析 17.25.2 官方 arm64 / x86_64 产物：p_align 已经都是 **0x4000**；32 位 arm/x86 是 **0x1000**。因此“16 KiB 变体”对 64 位并不存在可找的替代品——现在的官方 64 位 Gadget 本身就是 16 KiB 兼容的。
- 最新 17.26.1 二进制因当前网络无法从 GitHub release 直链/API 资产端点完成下载（尝试在 3.7 MB 处超时，已删除半包），所以没有对 17.26.1 的 ELF 做直接解析；只确认了资产列表。若需要，可后续用可达网络补解析，但升级版本不会改变“目标 APK 的库仍 misaligned”这一点。

判定：**64 位不需要找 16K 变体；没有证据表明升级 17.26.1 会修目标 APK 的原生库。**

### (b) 直接改 `p_align` 是否安全

不安全，且对当前 Gadget 也基本不适用：

- 32 位 Gadget 的段 1 `offset=0, vaddr=0` 同余成立，理论上可把 p_align 改成 0x4000；但段 2/3 不同余：
  - armeabi-v7a 段 2 `offset%0x4000=3760`，`vaddr%0x4000=7856`；段 3 为 12288 vs 4096；
  - x86 段 2 为 4928 vs 9024；段 3 为 4096 vs 12288。
  仅改 p_align 仍会失败；要改 `p_vaddr` 或 `p_offset`，就涉及段布局/文件偏移/重定位，不能当二进制补丁。
- `libyj_aj_02_309.so` 的 p_align 已是 0x4000，但 offset/vaddr 差 0x1000；改 p_align 无济于事，必须改 vaddr/offset 或重链接。
- 即使把 `p_vaddr` 调成同余，也会改变段间相对布局和符号/重定位语义；对普通 ELF 静态检查可能通过，但运行时仍可能崩。对 39 MB 的 Gadget 更不应做这种补丁。
- 归档层（ZIP 16K 对齐）不会改变 ELF 的 `p_align`；`ToolkitArchiveAlignment` 只能改 APK 内条目偏移，不能修 ELF。

判定：**不可作为通用修法；对 32 位 6 个段不可行，对目标 APK 的 2 个段也不可行。**

### (c) 用 NDK 重编 Gadget 的现实性

- Frida Gadget 是 Frida 的完整 native 工程（Gum/GumJS/V8 等），构建链是 Meson + Python + NDK r27+，不是普通的单文件 `ndk-build`；要复现官方 4 ABI 产物、版本、符号和配置，工作量大。
- 如果只为 32 位补 `-Wl,-z,max-page-size=16384`，可能可以重编 arm/x86；但 16 KiB 页设备是 arm64 设备，32 位 Gadget 不会被加载；对当前 arm64 目标没有收益。
- 64 位官方产物已经是 16K 对齐，重编不会让当前样本的 5 条告警消失（它们来自目标 APK）。
- 粗估：有经验构建者 **约 1–3 人日**（环境 + 4 ABI 编译 + 与官方产物核对；不含下游回归），且需要维护自有二进制/上游安全更新；不建议为当前问题做。
- 若目标 APK 自己的库要修，必须由目标 APK 的提供方用 NDK r27+ 重编；我们无法通过换 Gadget 修它们。

判定：**不现实且不必要；只有“确实要支持 32 位 16K 进程”才有意义，而这不是当前设备画像。**

### (d) 保持现状 + 文档/返回体解释

- Gadget 组件不动：arm64/x86_64 已合规，32 位 4K 不阻塞 arm64 16K 设备。
- 但返回体需要**按 entry 归因**：`partialReasons.native` 应带 `entry`（或 `scope: gadget|target`），并只对 `lib/*/libaijsgadget.so` 的 issue 标 `upstream:true`；目标 APK 的库标为 `target-owned`，不冒充上游问题。
- 文档说明：`pageSizeBytes:16384` 是静态策略常量，不是设备实际页大小；K40/.4 都是 4096。
- 若只是要“消除 partial”，不能靠改 Gadget；要么目标 APK 的原生库修好，要么把 `verification` 中目标 APK 的 native issue 降级为单独 warning（但这会隐藏真实兼容性问题，建议保留可见、改变归因）。

判定：**最低风险、最高性价比；建议采用。**

## 5. 影响面：现在真的会加载失败吗

实测：

| 设备 | 系统 | `getconf PAGE_SIZE` | 结论 |
|---|---|---:|---|
| K40 `cccc62c7` | Android/SDK 36（4.19 内核） | **4096** | 4K 页，Gadget/目标库当前不受 16K 规则影响 |
| `.4` | Android 17 / SDK 37 | **4096** | 也是 4K 页；Android 17 不等于 16K 页设备 |

- 当前测试机都不是 16 KiB 页设备；没有在真正的 16K 设备上复现加载失败。K40 历史注入结果 `installable=true`、`installed=false`，没有实际加载证据。
- 即使换到真实 16K 页 arm64 设备：
  - Gadget arm64 3 个 LOAD 全部合规；
  - 该样本的 `libroot_automator.so` 和 `libyj_aj_02_309.so` 会失败；但这是**目标 APK 本身**在 16K 设备上的兼容性问题，注入 Gadget 前后都存在（原始 APK `apk_verify` 已是 5 条）。
- 32 位 Gadget 的 6 个失败段只有在“目标 APK 同时包含 32 位 ABI、且被注入”时才会出现在输出里；本次 arm64 目标只注入 arm64，实际输出没有 Gadget 告警。若目标有多 ABI，当前代码会把所有目标 ABI 的 Gadget 都注入，届时才会多出 32 位 Gadget 的告警；这是另一个“多 ABI 包”的边界，不是本样本的 5 条。

## 6. 结论与建议

**一句话建议：不要为 16 KiB 去改/重编 Gadget——官方 17.25.2 的 arm64/x86_64 已经是 p_align=0x4000 且同余；当前 5 条 `partialReasons` 来自目标 APK 的 `libroot_automator.so`（3 段）和 `libyj_aj_02_309.so`（2 段），保持组件不变，改为在返回体按 entry 区分 target-owned 与 gadget/upstream。**

具体：

1. **不改 Gadget 二进制**：64 位已合规；32 位重编无收益，且 16K 设备是 arm64。
2. **不改 p_align 字节**：32 位段 2/3 与目标库段不同余，改了也不能通过；需重链接，风险高。
3. **返回体归因修正**（后续任务，非本调研改产品源码）：`partialReasons[]` 带 `entry/scope`；`upstream:true` 只给 Gadget 库；目标 APK 的 native issue 标为 `target-owned`；可另给 `gadgetNativeIssues/targetNativeIssues` 计数。
4. **文档**：说明 `pageSizeBytes=16384` 是策略常量，K40/.4 实测 4096；真正的 16K 设备验证仍需硬件。

## 附录 A：可复现命令/文件

- 组件 JSON：`http://192.168.10.7:8080/components/frida-gadget-arm64-v1.json`
- 组件 zip：`http://192.168.10.7:8080/components/frida-gadget-arm64-v1.zip`
- 解包：`Expand-Archive` 或任意 zip 工具
- ELF 解析结果：`output/gadget-16k-research/elf-loads.json`（本报告表格的原始数据）
- 官方哈希：`output/gadget-16k-research/extracted/upstream-lock.json`
- 5 条告警证据：K40 MCP job `9b4a2720-4b26-431b-9def-589431324e7c`；原始包路径 `/storage/emulated/0/脚本/__verify/yj-orig.apk`
- 页大小：K40 `adb -s cccc62c7 shell getconf PAGE_SIZE`；`.4` MCP `terminal_exec getconf PAGE_SIZE`
- 官方 release 资产列表：`https://api.github.com/repos/frida/frida/releases?per_page=5`（17.25.2、17.26.1）
- Android 16 KiB 页规则：`https://developer.android.com/guide/practices/page-sizes`

## 附录 B：尚未覆盖

- 未在真实 16 KiB 页硬件上加载验证；当前设备均为 4K。
- 17.26.1 Android arm64 二进制未直接解析（GitHub 资产直链下载失败，半包已删除）；只核对了资产命名。
- 未验证多 ABI 目标 APK 同时注入 32 位 Gadget 时的完整告警集合；按组件解析预估会新增 6 个 32 位 Gadget 段告警。
- 未做 `p_align` 重链接实验（会改变 ELF，不应在产品二进制上直接做）。
