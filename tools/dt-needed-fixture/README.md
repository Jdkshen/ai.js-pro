# dt-needed fixture（P1-4「就地写 DT_NEEDED」的真机成功路径夹具）

> 用途：给 `apk_inject_gadget{preloadMode:"dt_needed"}` 造一个**满足两个空隙条件**的 arm64 目标 .so，用来在真机上跑通它的**成功路径**（此前只有 JVM 合成夹具）。2026-11-08 dev-1415 轮次建立。

## 为什么要这个

`ToolkitElfNeeded`（`apps/app/src/main/java/com/jdkshen/aijspro/mcp/ToolkitElfNeeded.kt:148-192`）**只就地改字节、绝不改变文件大小**，所以目标 .so 必须已经具备：

1. **`.dynstr` 尾部有足够 NUL padding**，或 `.dynstr` 里已存在该串。判定见 `:154-162`：`freeStart = 最后一个非零字节 + 2`，可用字节数 `have = dynstrSize - freeStart` 必须 **≥ 17**（默认 soname `libaijsgadget.so` = 16 字符 + NUL）——等价于**尾部至少有 18 个连续 NUL**（多出来的 1 个是 `+2` 跳过的那个）。扫描是整节范围，**中间的空洞不算**。
2. **`.dynamic` 里有一个「改掉后仍保留 DT_NULL 终止符」的空槽**（`:164-168`：某个 tag==0 的条目，其下一个条目也是 tag==0）。

真实 `.so` 通常两条都不满足 → 工具按设计**明确失败**（`DYNSTR_NO_ROOM:<need>x<have>` / `DYNAMIC_NO_SPARE_NULL`）并给 `patchelf` 回退建议。本目录提供「造出空隙」的脚本与一个最小可运行 APK，用于验证成功路径与运行时提前加载。

## 文件

| 文件 | 作用 |
|---|---|
| `native/dtprobe.c` | 极小的 arm64 共享库（一个导出函数 + 构造函数） |
| `pad_so.py` | **核心**：把 `.dynstr`/`.dynamic` 的副本搬到文件尾并扩出 NUL padding / 备用 DT_NULL，同时更新 PT_DYNAMIC、DT_STRTAB、DT_STRSZ、包含段的 filesz/memsz 与节头；**不移动既有字节** |
| `check_elf.py` | 独立检查器：打印 dynstr 尾部可用 padding、`.dynamic` 的 DT_NULL 下标与备用槽（复刻上面两条判定） |
| `verify_patched.py` | 从注入后的 APK 里抽出目标 .so，与注入前的 padded 版本逐字节比对，并打印 `DT_NEEDED` / 残留 `DT_NULL` 数 |
| `AndroidManifest.xml` + `src/.../MainActivity.java` | 最小宿主 App：`System.loadLibrary("dtprobe")`（用来观察 gadget 是否在 dlopen 阶段被 DT_NEEDED 拉起来） |
| `build.ps1` | 用 NDK clang + javac + d8 + aapt2 + zipalign + apksigner 打出可安装的最小 APK |

## 用法

```powershell
# 1) 编 .so 并造空隙
& 'C:\Android\ndk-r26d\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android21-clang.cmd' -shared -fPIC -O2 -o native\libdtprobe.so native\dtprobe.c
python pad_so.py native\libdtprobe.so native-padded\libdtprobe-padded.so
python check_elf.py native-padded\libdtprobe-padded.so      # 期望 have>=17、spare_slots 非空

# 2) 用 padded 版本建 APK（build.ps1 在 .so 已存在时会跳过 clang）
Copy-Item native-padded\libdtprobe-padded.so native\libdtprobe.so -Force
.\build.ps1            # 产出 out\dtprobe-signed.apk

# 3) 推到设备的 MCP 操作根下（工具只允许操作根内的路径），再调 MCP：
#    apk_inject_gadget{path, outPath, preloadMode:"dt_needed", providerProcess:":probe_only", background:false}
# 4) 拉回输出 APK 自查：
python verify_patched.py <injected.apk> native-padded\libdtprobe-padded.so
```

## 2026-11-08 实测结论（K40 cccc62c7，dev-1414）

- **失败路径**（未 padded 的 .so）：`PRELOAD_DT_NEEDED_FAILED`，`targets[0].reason=DYNSTR_NO_ROOM:17x0`，**不产出 APK** ✓
- **成功路径**（padded）：`applied=true / verified=true / earlyLoad=true / sizeUnchanged=true`，`targets[0]={path:lib/arm64-v8a/libdtprobe.so, strtabOffset:64, dynamicIndex:23}` ✓
- **独立字节核对**：注入后 .so 大小不变（5536B），仅 18 字节变化（16 字节 DT_NEEDED 条目 + 2 字节 tag/value），`DT_NEEDED=[libdl.so, libc.so, libaijsgadget.so]`，`DT_NULL` 终止符仍在 ✓
- **运行时**：把注入的 Provider 指到惰性进程（`providerProcess=:probe_only`，排除「Provider 加载」这条替代解释）后装机运行，logcat 顺序为
  `DTPROBE before loadLibrary` → `nativeloader Load libdtprobe.so ok` → `DTPROBE after loadLibrary` → **`Frida: Listening on 127.0.0.1 TCP port 27043`** → `onCreate`，即 gadget 确实在 dlopen 阶段由 DT_NEEDED 拉起并完成初始化 ✓

## 边界（别当成已验证）

- 本夹具是**我们自己的** .so；真实加固 `.so` 多无空隙，仍需外部 `patchelf --add-needed libaijsgadget.so --page-size 16384` 后重打包。
- 夹具用 4KiB 页对齐（clang 默认），K40 是 4096 页设备；工具会把「目标自带库 PT_LOAD 页对齐」记为 `partial`（16KiB 设备/上架校验会判不合格），本次实测不影响加载。
- `pad_so.py` 只处理 **ELF64 小端、且 `.dynamic` 所在 PT_LOAD 与文件尾之间没有第二个可加载段** 的情形；换目标 .so 前先用 `check_elf.py` 确认。
- 运行时结论是在**我们自己的宿主 App** 上得到的；真实加固应用里 gadget 是否早于壳的 native 初始化，取决于壳何时加载目标 .so。
