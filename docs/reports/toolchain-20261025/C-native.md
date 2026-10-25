# C/C++ 手机工具链可执行性原型（Termux aarch64 → MI8 应用 UID）

日期：2026-10-25  
设备：MI8 `ce4d2bdb`（MI 8 Explorer Edition，Android 15 / API 35，arm64-v8a，页面 4096B）  
宿主：candidate-1245（versionCode 1245，minSdk 26，**targetSdk 28**）  
结论：**PASS**。在应用自己的 UID（非 root、非 shell）下从 app-private 目录启动 Termux aarch64 clang，成功编译并运行 C 与使用 libc++ STL 的 C++ 程序；工具和产物均为 ELF64 AArch64，LOAD 对齐 0x4000（静态 16KB 兼容）。

## 1. 来源与边界

- 包源：Termux 官方主仓库 aarch64 stable
  - `https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages`
  - 索引实测：1,870,327 B，SHA-256 `acf53a23b20ab01a0d59eecf21d75464bc7dc667f188b74bc3d1486eeb0e4d2e`
- 只使用 **aarch64 Android 设备包**；没有使用电脑 NDK/LLVM 主机二进制，也不存在 root。
- 工具运行在 MCP 手机终端（应用 UID `u0_a296`，shell 由 candidate-1245 内部启动）；主机只做 `adb push`、`adb forward` 和 MCP 调用。
- Android 10+ 的 app-data exec 限制：本机 targetSdk=28，仍允许从 `/data/user/0/.../files` 执行；**不要为了本工具降低 targetSdk**。若宿主 targetSdk 升到 >=29，按 Android 10 行为，app-private 可执行文件会被禁止 exec，需要单独的兼容安装策略/降级（见 §7）。

## 2. 组件、版本、压缩体积与许可证

从上述索引递归解析 clang 的 16 个包（保持精确版本依赖）。全部 .deb 均 SHA-256 校验通过；逐包 SHA 由下载文件实测，索引字段一致。

| 包 | 版本 | .deb 字节 | SHA-256 | 许可证（upstream SPDX） |
|---|---|---:|---|---|
| clang | 21.1.8-3 | 30740228 | `9fb565013b96ce10abd8f8f217aaebb48f72873f53acbf8886329bcea841a21c` | Apache-2.0 WITH LLVM-exception |
| lld | 21.1.8-3 | 2812212 | `6299c8fdff59dcff972f6b444805bcdb211c2bd797a64d2ddb3875e699f30630` | Apache-2.0 WITH LLVM-exception |
| binutils | 2.47 | 2445400 | `332175eb2621c591f4ef80e335ddd42af8fef0d34cccd28123fc19ace8be8f74` | GPL-3.0-or-later |
| libcompiler-rt | 21.1.8-3 | 3024604 | `89de02f642e34fa6494ad16105bd08510bd1dbdbe4fdb2b297aaa4f4e8e534b6` | Apache-2.0 WITH LLVM-exception |
| llvm | 21.1.8-3 | 14911960 | `2d17e4dde485cae71e6872d19ce1d44555789208708b4e0fd15ebf6818a146d3` | Apache-2.0 WITH LLVM-exception |
| ndk-sysroot | 30 | 1996888 | `73502f9b04fce210c430c83bf9ae2b8e332ba5c319f3eb94a61ba5cef090f787` | Apache-2.0 |
| libllvm | 21.1.8-3 | 30733892 | `5056a73f2645fc9c0759e6f94a1bd6edcf36305acaef58dc685211ced9cc2a02` | Apache-2.0 WITH LLVM-exception |
| libc++ | 30 | 341604 | `53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca` | Apache-2.0 WITH LLVM-exception |
| zlib | 1.3.2 | 62840 | `75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9` | Zlib |
| zstd | 1.5.7-1 | 360488 | `e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3` | BSD-3-Clause OR GPL-2.0 |
| libffi | 3.8.0 | 37592 | `4f255badf74cd31f6a2801c17fa1444199c84c834b517b7c843e2fe9ebe91d77` | MIT |
| libxml2 | 2.15.4-1 | 442652 | `d315861bbcf716cfb088f53cddab9fb6a96430cc66769f79774cc69c2f53bcff` | MIT |
| ncurses | 6.6.20260307+really6.5.20250830 | 557792 | `f44bbfdc3d42ec0217bffa978309390e59cea5a48a9a83226d4a496c42ad0b99` | MIT/X11 |
| liblzma | 5.8.4 | 194796 | `44e95e6e60dddb3705e60a344a4f009a1085a210797342b721eaff41b44033c0` | 0BSD/public domain |
| libandroid-glob | 0.6-3 | 7032 | `2276ae8adedf0db76c2f4ffc94cc4cceb2f4f5d78e021b54e2e046d1233e7826` | BSD-3-Clause |
| libiconv | 1.19 | 562724 | `fe9481b1dc101c6c3552943f25435109fd522aecc615ae49594f9fbee863bb37` | LGPL-2.1-or-later |

- .deb 合计：**89,232,704 B**（85.1 MiB）
- 重新打包为 `payload.tar.gz`（保留 symlink）：**145,682,354 B**（138.9 MiB）
  - SHA-256：`6a5f12ab7bc88933ccc120b807d2f46ed38fd7c8458dfc6f449687e5e737dd29`
  - 说明：.deb 是 xz，转成 gzip 后体积变大；实际分发可用 .deb + 设备端解包或 zstd tar，当前原型用 gzip tar 便于 Android toybox tar 直接解压。
- 安装后（`du -sk`，已清理临时 work 目录）：
  - 总计：**537,308 KiB（约 524.7 MiB）**
  - `bin/` 119,296 KiB；`lib/` 309,168 KiB；`include/` 103,376 KiB；`share/` 5,416 KiB；`libexec/` 48 KiB
- 许可证补充：包内 `share/doc/*/copyright` 只覆盖部分包；LLVM/Clang/lld/libLLVM/compiler-rt/libc++ 使用 Apache-2.0 WITH LLVM-exception，binutils 使用 GPL-3.0-or-later。若做插件分发，必须把每个包的 copyright/NOTICE 原样放进 `licenses/`，不能只记录 SPDX 名称。

## 3. 安装布局与启动方式

设备 app-private 目录（最终清理后）：

```text
/data/user/0/com.jdkshen.aijspro/files/toolchain230/
  bin/            clang-21, clang -> clang-21, lld, ld.lld -> lld, greadelf, gar, gstrip ...
  lib/            libLLVM-21.so, libLLVM.so, libclang-cpp.so, libc++_shared.so, libz.so, libzstd.so ...
  include/        stdio.h, sys/, linux/, aarch64-linux-android/asm/...
  lib/clang/21/   clang resource headers
  libexec/        clang internal helpers
  share/          doc/copyright 等
```

关键路径/细节：

- `clang`、`clang++` 都是 `clang-21` 的 symlink；`ld.lld` → `lld`。
- Termux 把 GNU binutils 与 LLVM 工具做了冲突规避：GNU 实为 `greadelf/gobjdump/gstrip/gar/gnm`；裸 `readelf/ar/nm/objdump/strip` 解析到 LLVM 工具。GNU 实测 `greadelf --version` = GNU readelf (GNU Binutils) 2.47。
- 本原型的 app UID 终端 `HOME=/data/user/0/com.jdkshen.aijspro/files`；解包后工具位于 app-private 路径，非 /sdcard。

## 4. 实际命令、退出码与程序输出

先在 MCP 手机终端确认身份（这是 **应用 UID**，不是 root/shell）：

```sh
id
# uid=10296(u0_a296) gid=10296(u0_a296) ... context=u:r:untrusted_app_27:s0...
```

安装/解包（主机 push；tar 在手机 app UID 中执行）：

```sh
# 主机
adb push payload.tar.gz /storage/emulated/0/脚本/.toolchain230/payload.tar.gz

# 手机 MCP 终端，UID=u0_a296
mkdir -p "$HOME/toolchain230"
tar -xzf /storage/emulated/0/脚本/.toolchain230/payload.tar.gz -C "$HOME/toolchain230"
```

环境（所有后续编译/运行都在此环境）：

```sh
TC=$HOME/toolchain230
export PATH="$TC/bin:$PATH" LD_LIBRARY_PATH="$TC/lib" PREFIX="$TC" TERMUX_PREFIX="$TC"
```

### 4.1 启动 clang

```sh
clang --version
# clang version 21.1.8
# Target: aarch64-unknown-linux-android24
# Thread model: posix
# InstalledDir: .../toolchain230/bin
# exit 0

greadelf --version | head -1
# GNU readelf (GNU Binutils) 2.47
```

`lld` 单独作为 driver 时按设计失败：

```sh
lld --version
# lld is a generic driver.
# Invoke ld.lld ...
# exit 1

ld.lld --version
# LLD 21.1.8 (compatible with GNU linkers)  # exit 0
```

### 4.2 C 编译/运行

```c
#include <stdio.h>
int main(void) { printf("C_OK %d\n", 42); return 0; }
```

```sh
clang --target=aarch64-linux-android24 \
  -isystem "$TC/include" \
  -isystem "$TC/include/aarch64-linux-android" \
  -B"$TC/bin" -L"$TC/lib" -L/system/lib64 \
  -fuse-ld=lld -o hello hello.c
# exit 0

./hello
# C_OK 42
# exit 0
```

### 4.3 C++ STL 编译/运行

```cpp
#include <iostream>
#include <vector>
#include <string>
#include <numeric>
int main() {
  std::vector<int> v{1,2,3,4,5};
  int sum = std::accumulate(v.begin(), v.end(), 0);
  std::string s = "STL";
  std::cout << "CPP_OK " << s << " sum=" << sum << " size=" << v.size() << std::endl;
  return 0;
}
```

```sh
clang++ --target=aarch64-linux-android24 -stdlib=libc++ \
  -isystem "$TC/include/c++/v1" \
  -isystem "$TC/include" \
  -isystem "$TC/include/aarch64-linux-android" \
  -B"$TC/bin" -L"$TC/lib" -L/system/lib64 \
  -fuse-ld=lld -o cpp cpp.cpp -lc++_shared
# exit 0

LD_LIBRARY_PATH="$TC/lib" ./cpp
# CPP_OK STL sum=15 size=5
# exit 0
```

## 5. ELF、ABI、DT_NEEDED 与 16KB 对齐

设备页面大小：`getconf PAGE_SIZE` = **4096**；`uname -m` = aarch64。

产物：

| 文件 | ELF 头 | 解释器 | DT_NEEDED | LOAD p_align |
|---|---|---|---|---|
| hello (C) | ELF64, little endian, AArch64, DYN (PIE) | /system/bin/linker64 | libdl.so, libc.so | 0x4000 |
| cpp (C++ STL) | ELF64, little endian, AArch64, DYN (PIE) | /system/bin/linker64 | libc++_shared.so, libdl.so, libm.so, libc.so | 0x4000 |

`file` 对两个产物的关键描述一致：

```text
ELF shared object, 64-bit LSB arm64, dynamic (/system/bin/linker64),
for Android 24, built by NDK r30 (16248370), not stripped
```

工具链本身（16KB 静态检查）：

| ELF | LOAD p_align | DT_NEEDED 摘要 |
|---|---|---|
| bin/clang-21 | 0x4000 | libc.so, libclang-cpp.so, libLLVM.so, libc++_shared.so |
| bin/lld | 0x4000 | libc.so, libz.so.1, libzstd.so.1, libLLVM.so, libc++_shared.so |
| lib/libLLVM-21.so | 0x4000 | — |
| lib/libc++_shared.so | 0x4000 | — |

结论：在**静态 ELF 对齐**意义上，clang/lld/LLVM/libc++ 和用户程序都满足 16KB PAGE 的 0x4000 对齐；但 MI8 是 4KB 页面，本次没有在真实 16KB 页面设备上运行（不能宣称 16KB 运行时已验收）。

## 6. 与电脑 NDK 的差异 / 可运行原因

- 没有使用电脑 `aarch64-linux-android*-clang` 主机工具；它是 x86_64 主机程序，不能当手机结果。
- Termux 包中的 `clang` 是 AArch64 **目标程序**，动态依赖 Android bionic；因本机 app targetSdk=28，app-private exec 没有被 Android 10+ 限制拦。
- Termux 二进制默认按 Termux 前缀 `/data/data/com.termux/files/usr` 查找资源/库；本原型前缀是 app-private 的 `.../files/toolchain230`，所以必须显式给 include、binutils/资源目录、库目录与 `LD_LIBRARY_PATH`（见 §4）。这是“Termux 包可运行但路径需适配”的核心边界。

## 7. 失败边界（实测或直接可判定的）

1. **默认前缀不适用**：不指定 `-isystem "$TC/include"` 与 `-isystem "$TC/include/aarch64-linux-android"` 时，`stdio.h → sys/types.h → linux/types.h` 报：
   `fatal error: 'asm/types.h' file not found`（退出 1）。
2. **缺 LD_LIBRARY_PATH**：`env -u LD_LIBRARY_PATH ./clang --version` 报：
   `CANNOT LINK EXECUTABLE "./clang": library "libclang-cpp.so" not found`（退出 1）。
3. **/sdcard 不可执行**：把 hello 复制到 `/storage/emulated/0/脚本/.toolchain230/hello_sdcard` 后执行，报
   `can't execute: Permission denied`（退出 126）；必须放 app-private 可执行目录。
4. **裸 lld 不是 driver**：`lld --version` 退出 1；链接必须用 `ld.lld`（或 `-fuse-ld=lld`）。
5. **GNU binutils 被 LLVM 别名覆盖**：裸 `readelf/ar/nm` 是 LLVM 工具；GNU 版本要用 `greadelf/gar/gnm/gobjdump/gstrip`。
6. **targetSdk 限制**：本机 targetSdk=28 才能 app-private exec；若宿主 targetSdk 变 >=29，Android 10+ 会禁止 app 私有目录 exec，本原型不能直接复用（不得通过降低 targetSdk 规避，需单独插件/安装位置策略）。
7. **C++ 不是全静态**：默认产物 NEEDED libc++_shared.so；运行时要 `LD_LIBRARY_PATH=$TC/lib`；若要分发单文件需另评估静态链接与许可证。
8. **ABI/API**：target triple 固定 `aarch64-linux-android24`（Termux clang 默认/本次显式），实际系统 API35；仅 arm64-v8a，不支持 32 位。
9. **体积**：解包 524.7 MiB，远大于主 APK 可承受范围；不能内置主 APK，必须作为可选插件/外部组件。

## 8. 组件元数据建议

建议把工具链做成**可选插件/独立组件**，元数据至少包含：

```json
{
  "schemaVersion": 1,
  "id": "android-arm64-clang",
  "version": "21.1.8-3",
  "source": {
    "kind": "termux-apt",
    "repo": "termux-main/dists/stable/main/binary-aarch64",
    "indexSha256": "acf53a23b20ab01a0d59eecf21d75464bc7dc667f188b74bc3d1486eeb0e4d2e",
    "packageCount": 16
  },
  "target": {
    "triple": "aarch64-unknown-linux-android24",
    "abi": "arm64-v8a",
    "apiLevel": 24,
    "ndkSysrootVersion": 30,
    "pageSizeCheck": 16384,
    "elfLoadAlign": 16384
  },
  "execution": {
    "uid": "app",
    "requiresAppPrivate": true,
    "sdcardExec": false,
    "targetSdkMax": 28,
    "interpreter": "/system/bin/linker64",
    "ldLibraryPath": "<prefix>/lib"
  },
  "payload": {
    "debBytes": 89232704,
    "compressedBytes": 145682354,
    "compressedSha256": "6a5f12ab7bc88933ccc120b807d2f46ed38fd7c8458dfc6f449687e5e737dd29",
    "installedKiB": 537308
  },
  "tools": {
    "clang": "bin/clang-21",
    "clangxx": "bin/clang++",
    "lld": "bin/ld.lld",
    "gnuReadelf": "bin/greadelf",
    "gnuAr": "bin/gar"
  },
  "licenses": ["Apache-2.0 WITH LLVM-exception", "GPL-3.0-or-later", "LGPL-2.1-or-later", "MIT", "BSD-3-Clause", "Zlib", "0BSD"],
  "verification": [
    "clang --version => 21.1.8",
    "hello.c => C_OK 42",
    "cpp.cpp => CPP_OK STL sum=15 size=5",
    "readelf -l *.elf => LOAD Align 0x4000"
  ],
  "wrapper": "<prefix>/bin/clang-m3 adds: --target=aarch64-linux-android24 -isystem <prefix>/include -isystem <prefix>/include/aarch64-linux-android -B<prefix>/bin -L<prefix>/lib -L/system/lib64 -fuse-ld=lld"
}
```

安装器建议：

1. 只允许安装到 **app-private** `context.getFilesDir()/toolchain/<version>`；拒绝 /sdcard 与外部路径。
2. 安装前校验 payload SHA-256 与目标磁盘余量（>=1GiB 安全余量），解包后校验关键文件 SHA/ELF（clang/lld/libLLVM）。
3. 生成 `profile.json` 与 wrapper（`clang-m3`/`clang++-m3`），不要把 `lld` 裸名暴露为默认 linker；GNU 工具用 `g*` 区分。
4. 记录 execution 限制：若宿主 targetSdk >=29，组件必须标记不可执行或使用单独的 targetSdk 28 兼容入口；不得降低宿主 targetSdk。
5. 可选 UI：仅在用户显式安装/启用后把工具链路径加入 PATH；不改变主 APK 体积和普通脚本执行路径。
6. 许可证/Notice 原样随包；C++ 运行时必须提示 `libc++_shared.so` 与 `LD_LIBRARY_PATH`。

## 9. 证据与限制

- 设备：MI8 `ce4d2bdb`，candidate-1245，Android 15/API35，arm64-v8a，页面 4096。
- 应用 UID 实测：`uid=10296(u0_a296)`；没有 root/su。
- 命令均通过 MCP 手机终端执行（应用 UID），主机只做 adb push/forward；MCP 任务状态均为 SUCCEEDED，C/C++ 编译与运行退出码均为 0。
- 证据/包目录（本机）：`output/toolchain-20261025/`（Packages、debs、payload.tar.gz、downloads.json）。
- **未验证**：真实 16KB 页面设备、targetSdk>=29 宿主、Android 低于/高于 MI8 的 bionic 差异、长期运行/大工程编译、分发包签名与许可证全文归档。
- 本次只写本报告；未改产品代码、未提交/发布、未碰 K40。
