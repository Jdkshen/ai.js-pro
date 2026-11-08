# dt_needed 夹具：满足 ToolkitElfNeeded 两个空隙条件的 arm64-v8a .so

> 目标：给交接单待办 **P1-4「dt_needed 真机成功路径」** 一个能被**就地**打上 DT_NEEDED 的 arm64 共享库
> （`libaijsgadget.so`），用于 `apk_inject_gadget{preloadMode:"dt_needed"}` 的成功路径。
> 生成/验证全部离线完成，**不跑 gradle、不装机、不改仓库源码**。

## 1. 两个条件（以代码为准，已复核）

`apps/app/src/main/java/com/jdkshen/aijspro/mcp/ToolkitElfNeeded.kt`：

| # | 条件 | 代码位置 | 判定细节 |
|---|---|---|---|
| 1 | `.dynstr` 有放 `libaijsgadget.so\0`（17 字节）的地方 | `:149-163` | 先 `findString` 找**完全同名的串**（`:151`）；找不到才看节尾：`lastNonZero = 最后一个非零字节`，`freeStart = lastNonZero + 2`，`have = dynstrSize - freeStart`，要求 `have >= 17`。**注意是 `+2`**，所以节尾真正需要的是 **≥18 个连续 NUL**（1 个空隙 + 17 个字符串字节）；中间的空洞不算。失败码 `DYNSTR_NO_ROOM:17xN`（`:160`） |
| 2 | dynamic 表里有「备用 DT_NULL」 | `:164-168` | 存在下标 i 使 `dynamic[i].tag == 0` **且** `dynamic[i+1].tag == 0`；补丁把 `[i]` 改写成 `DT_NEEDED`，`[i+1]` 继续当终止符。失败码 `DYNAMIC_NO_SPARE_NULL` |
| 3 | 写后自校验 | `:184-192` | 文件大小不变、只有 `changedRanges` 内的字节变化、读回能查到新 `DT_NEEDED` 与 dynstr 串 |

> **更正 Lead 转述的一处**：条件 1 的「≥17 字节 NUL padding」严格说是「最后一个非零字节之后要有
> **≥18** 个 NUL」（因为 `freeStart = lastNonZero + 2`）。本夹具给 64 个，余量足够。

## 2. 文件清单

| 文件 | 大小 | sha256 | 用途 |
|---|---|---|---|
| `libdtprobe.so` | 2,584 | `033002ff7167bb38aa28525180753f268434be964c021d4ba4935f0b0d2ad4d6` | **主夹具**：dynstr 节尾 64 个 NUL（走「写入 padding」分支）+ 备用 DT_NULL |
| `libdtprobe-preexisting.so` | 2,601 | `f1996ce57e5e7a4724e8d990144efbc260c41c0959485201eea503fba0f80db4` | 变体：dynstr 里**已存在** `libaijsgadget.so`（走 `findString` 命中分支） |
| `libdtprobe-raw-no-gap.so` | 2,464 | `9d6a2670d9e79059141d6d09404a1e6f7ad2881b13f059a59522443e19002765` | **对照组**：clang 原样输出，两个条件都不满足 → 预期 `DYNSTR_NO_ROOM:17x0` |
| `src/dtprobe.c` | — | — | 导出 `dtprobe_ping`/`dtprobe_tag`；`-nostdlib` 所以 dynamic 里没有 DT_NEEDED，补丁结果一眼可辨 |
| `make_fixture.py` | — | — | 生成脚本（NDK clang + 就地扩容，见 §3） |
| `check_dt_needed.py` | — | — | **独立检查脚本**：自己解析 ELF，逐字节复刻两个条件 + 内存内补丁模拟（见 §4） |
| `kotlin-verify/` | — | — | `VerifyMain.java` + 产物：用 **Gradle 已编出的真实 class** 跑 `ToolkitElfNeeded`（见 §5） |
| `run_impl_verify.ps1` | — | — | §5 的一键复现脚本 |
| `check-output.txt` / `kotlin-verify/verify-output.txt` | — | — | 两次验证的完整输出 |

三份都是 `ELF64 / little-endian / AArch64 / DYN`，`p_align = 0x4000`（16 KiB 页兼容，Android 15+ 要求；
4 KiB 页设备同样可加载）。

## 3. 生成（可复现）

```powershell
python .artifacts/fixtures-dt-needed/make_fixture.py          # clang 自动探测；可用 --clang 指定
```

做法：NDK clang `--target=aarch64-linux-android21 -shared -fPIC -nostdlib -O1 -Wl,-soname,libdtprobe.so -Wl,-z,max-page-size=16384`
编出真 .so 后，在同一页内做最小手术（插入点正好是 RW `PT_LOAD` 的文件尾，所以**新增映射的字节只有追加内容**）：

1. `.dynamic` 之后插入 16 字节零 = 多一个 `DT_NULL` 条目；
2. 再追加一份 dynstr 副本（可选带 `libaijsgadget.so`）+ 64 字节 NUL；
3. `.dynstr` 的 `sh_offset/sh_size`、`DT_STRTAB/DT_STRSZ` 指向副本；
4. `PT_DYNAMIC` 与 RW `PT_LOAD` 的 `p_filesz/p_memsz` 扩到新尾（`p_offset/p_vaddr` 不动，同页，congruence 保持）；
5. 插入点之后的非分配节（`.comment/.symtab/.shstrtab/.strtab`）与节头表整体平移并更新 `sh_offset`/`e_shoff`。

## 4. 独立检查（不依赖 Kotlin 实现）

```powershell
python .artifacts/fixtures-dt-needed/check_dt_needed.py libdtprobe.so libdtprobe-preexisting.so libdtprobe-raw-no-gap.so
# 退出码：0 = 两个条件都满足；1 = 有输入会被 ToolkitElfNeeded 拒绝（raw 对照组就是 1）
```

打印：文件大小/sha256、每个 `PT_LOAD`/`PT_DYNAMIC`、dynamic 表逐条（标注 DT_NULL）、dynstr 解析来源
（`SHT_DYNAMIC.sh_link` 还是 `DT_STRTAB` 回落）、条件 1 的 `lastNonZero/freeStart/have` + 末尾 24 字节 hex、
条件 2 的 DT_NULL 下标对与 `spareIndex`、以及一次**内存内补丁模拟**（applied/verified/changedRanges/新 sha256，不写文件）。

## 5. 跑真实实现（用 Gradle 已经编好的 class，不跑 gradle）

```powershell
powershell -File .artifacts/fixtures-dt-needed/run_impl_verify.ps1
```

它用 `apps/app/build/tmp/kotlin-classes/miuixCompatRelease` 里**真实的** `ToolkitElfNeeded`（+ 真 `ToolkitIo`、
gson、kotlin-stdlib 2.1.0），对三份夹具调用 `patch()`，打印 Outcome 全字段与 JSON，并把补丁结果写到
`kotlin-verify/out/*.patched.so`。

## 6. 已得到的结论（2026-11-08，离线）

| 夹具 | 真实 `ToolkitElfNeeded.patch` | changedRanges | 补丁后 sha256 |
|---|---|---|---|
| `libdtprobe.so` | `applied=true / verified=true / dynamicIndex=9 / strtabOffset=40` | `[0x3c8-0x3d7, 0x410-0x420]` | `2e0898e5a01c95b09c826aa4df2ec09a598398e5df9516604f1c599cfa102142` |
| `libdtprobe-preexisting.so` | `applied=true / verified=true`（只改 dynamic） | `[0x3c8-0x3d7]` | `bfa8c121c710ef604593a64ce7b1f47b9b775cfa3f7f857780b8ba497eab3c6a` |
| `libdtprobe-raw-no-gap.so` | `applied=false / reason=DYNSTR_NO_ROOM:17x0`（**无半坏产物**） | — | — |

- `check_dt_needed.py` 的内存模拟与真实实现产出的补丁文件 **sha256 完全一致**（两份都一致）→ 交叉验证成立。
- `llvm-readelf` 看补丁后的产物：`NEEDED Shared library: [libaijsgadget.so]` 出现、其后仍有一个 `NULL` 终止符、
  `SONAME` 不变、`DT_STRTAB=0x83e8 / DT_STRSZ=104|121`、`PT_DYNAMIC p_filesz=0xb0`、新 dynstr 落在 RW `PT_LOAD` 内。
- raw 对照组的 `DYNSTR_NO_ROOM:17x0` 与 dev-1403 在真机 `real.apk` 上看到的失败**完全同形**。

## 7. 边界（未验证）

- **未在 Android 上 dlopen / 未装机**：本目录只有结构级 + 实现级验证；`p_align=0x4000`、`DT_STRTAB` 落在
  已映射 `PT_LOAD` 内、补丁后 `DT_NEEDED` 出现在 dynamic 表里，这些都是「可加载」的必要条件，
  **不等于真机加载成功**。
- 真机端到端还需要：把夹具放进 APK 的 `lib/arm64-v8a/`，**并且 APK 里同时要有 `libaijsgadget.so`**
  （否则补丁虽然 applied=true，运行时会因找不到依赖库而加载失败）。这部分由 Lead 的 `apk_inject_gadget` 流程完成。
- `.dynstr` 副本被放在 RW `PT_LOAD`（原 `.dynstr` 在只读段）里，且落在 `GNU_RELRO` 范围内：RELRO 生效后它是
  只读的，读字符串没问题；**没有**在真机上验证过 bionic 对「STRTAB 在可写段」的容忍度。
- 手术只处理「`.dynamic` 恰好是最后一个分配节、紧贴 RW `PT_LOAD` 文件尾、无 `.bss`」的紧凑布局
  （clang/lld 对这个极小 .so 就是这个布局）；遇到别的布局脚本会直接报错退出，不会产出可疑文件。
