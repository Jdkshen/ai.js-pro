# 手机端最小 Android APK 流水线预研（工具链 D）

> 日期：2026-10-25。设备：小米 8 Explorer Edition（`ce4d2bdb`），Android 15 / API 35，arm64-v8a；
> 宿主为 **candidate-1245**（versionCode 1245，versionName candidate-1245）。K40 `cccc62c7` 全程未访问。
> 目标：研究 ARM64 可运行的 aapt2/aapt、zipalign/apksigner（或 Java 签名实现）、android.jar 平台 stub 来源，
> 并在 MI8 上尽量实测「Manifest + res/assets + Java class → 可安装 APK」。

## 0. 结论摘要

- **端到端（从二进制 Manifest/资源表 + DEX 到可安装 APK）已在手机上跑通**，但**不是通过 aapt2**：
  设备上没有任何 aapt/aapt2/zipalign/apksigner、没有 Termux、没有 android.jar；完整重编资源不可行。
- 采用**宿主内已有的纯 Java 打包组件**（candidate-1245 APK 内类）+ 手机端 Smali/D8：在 `app_process` 里
  改写 AXML Manifest（包名/版本/标签）→ 收集 workspace → 生成自签身份 → 写 v1 + v2 签名 APK。
- 实测产物：**304,178 字节** APK（含 resources.arsc）/ **7,685 字节** APK（仅 Manifest+DEX），两者都
  **在 MI8 上 `adb install` 成功**（包名 `com.ai.jspro.pipeline.d` / `.d2`，独立包名，测试后已卸载）。
- 最小 APK **可安装但不可启动**：模板 Manifest 仍声明大量组件/application 属性（`appComponentFactory`、
  MLKit provider 等），而我们的 DEX 只含最小类 → 启动即 `ClassNotFoundException`。要「可启动」需裁剪组件
  或打包模板类；**该步未完成**。
- 关键阻塞：**aapt2/aapt 无 ARM64 设备端来源**（Google 只发 x86 主机预编译；原版 Pro 自带 `libaapt2.so` +
  `assets/aapt/android.jar` 26,467,929 B，但本宿主未捆绑）；**zipalign 缺失**（targetSdk≥30 时
  resources.arsc 未压缩+对齐是硬要求）；**无 v3/v4 签名**；**无 javac**（Java 源码编译归工具链 B）。
- 本报告所有「手机端」结论均来自 `adb shell` + `app_process` 实测；PC 上的 `aapt`/`apksigner`/Python
  仅用于**输入提取与事后校验**，已在对应小节明确标注，不参与流水线。

## 1. 环境、授权与边界

| 项 | 值 |
|---|---|
| 设备 | MI 8 Explorer Edition（`ce4d2bdb`），SELinux Enforcing，`adb shell` uid=2000(shell)，无 su/root |
| 系统 | Android 15，SDK 35，arm64-v8a，fingerprint `Xiaomi/ursa/...:8.1.0`（厂商指纹） |
| 宿主 | candidate-1245：versionCode 1245 / versionName candidate-1245 / minSdk 26 / targetSdk 28 |
| 宿主 APK 路径 | `/data/app/~~mAews893ug8-0T-PZOh3jQ==/com.jdkshen.aijspro-paA1Ghs4amsdAkO-hEJrFQ==/base.apk`（137,696,691 B，shell 可读） |
| 授权 | 测试 APK 使用**独立包名** `com.ai.jspro.pipeline.d` / `.d2`，仅装到 MI8 测试机；安装/卸载属本次预研授权范围，**不覆盖、不修改宿主 AI.js Pro**（结束后 `dumpsys` 仍为 candidate-1245），K40 未操作 |
| 清理 | 两个测试包已 `adb uninstall`；设备端 scratch `/data/local/tmp/toolchain-d` 已删除；PC 证据保留在 `.artifacts/toolchain-d-20261025/`（gitignore） |

## 2. 设备端工具盘点

| 工具/资产 | 手机端状态 | 证据/说明 |
|---|---|---|
| `sh` / `app_process` / `dalvikvm` | ✅ 有 | 系统 PATH；`app_process` 可直接跑 Java main |
| 内置 **D8**（R8） | ✅ 有 | 宿主 APK 内 `com.android.tools.r8.D8`；经 `AndroidBuildTool d8` 在独立 app_process 调用 |
| 内置 **Smali** | ✅ 有 | `org.jf.smali.Main`，版本 **2.5.2**，BSD license（工具自报） |
| AXML 读写/Manifest 编辑 | ✅ 有（宿主类） | `com.stardust.autojs.apkbuilder.ManifestEditor` + `pxb.android.axml.AxmlWriter` |
| APK 签名（v1+v2） | ✅ 有（宿主类） | `KeyStoreGenerator` / `SigningKey` / `ApkSignerV1` / `ApkSignerV2` / `KeyStoreApkSigner`（纯 Java，无三方依赖） |
| 打包模板 | ✅ 有（宿主 asset） | `assets/template.apk` 17,198,344 B；`assets/autojs.keystore` 2,062 B |
| `aapt` / `aapt2` | ❌ 无 | PATH 无；`/system/bin`、`/system/xbin`、`/sdcard`、`/data/local/tmp` 均无；宿主 APK 无 `libaapt2.so` |
| `zipalign` | ❌ 无 | 同上 |
| `apksigner` | ❌ 无 | 同上；仅宿主 APK 内的 Java 签名类可用 |
| Termux / 包管理器 | ❌ 未安装 | `pm list packages` 无 `com.termux` |
| JDK / javac | ❌ 无 | PATH 仅 `sh`/`app_process`；Java 源码编译见工具链 B |
| `android.jar` / 平台 stub | ❌ 无 | 设备与宿主 APK 内均未找到（见 §3.3） |

在 `adb shell` 里复用内置编译 CLI 的真实命令（candidate-1245 实测可用，shell UID 即可）：

```sh
APK=$(pm path com.jdkshen.aijspro | sed 's/package://')
# Smali：.smali -> .dex
CLASSPATH=$APK app_process /system/bin com.stardust.autojs.runtime.tool.AndroidBuildTool \
    smali assemble -o out/classes.dex App.smali SplashActivity.smali
# D8：JAR/class -> .dex
CLASSPATH=$APK app_process /system/bin com.stardust.autojs.runtime.tool.AndroidBuildTool \
    d8 --min-api 26 --output out input.jar
```

> 原理：`AndroidBuildTool.main` 在**独立 app_process**里分发 D8/Smali，避免编译器 `System.exit` 退出宿主
> （见 `docs/reports/2026-10-25_MCP手机终端与编译入口.md`）。

## 3. 三个关键组件调研（ARM64 手机端来源）

### 3.1 aapt2 / aapt

| 来源 | 是否 ARM64 Android 可运行 | 体积/许可 | 结论 |
|---|---|---|---|
| Google 官方 build-tools 预编译 | ❌ | `aapt2-*-linux.jar`/`-windows`/`-osx` 里是**主机**可执行 | 只有 host x86_64/win/mac，不能装进手机运行 |
| AOSP 源码自交叉编译 aapt2 | ⚠️ 理论可行 | Apache-2.0（AOSP `build-system/aapt2/NOTICE`）；二进制预计数 MB | 需要 NDK/CMake 交叉构建链，本轮设备无 clang/sysroot |
| 原版 Auto.js Pro APK 内置 | ✅ 设计如此 | `lib/arm64-v8a/libaapt2.so`（伪装成 .so 以绕开 Android 10+ 对非 lib 目录 exec 的限制）+ `assets/aapt/android.jar` **26,467,929 B**（Apache-2.0） | 本宿主未捆绑；MI8 也未安装原版 Pro。可参考其做法，但要把 26MB android.jar 带进插件，体积代价大 |
| Termux `aapt` 包 | ⚠️ 历史上不可重建 | Termux issue #18349「aapt package can not be rebuilt」；另有社区 `termux/android-build-tools`（非官方 CMake 构建 build-tools） | MI8 未装 Termux；即便安装也不能默认依赖 |
| 模板清单改写（Pro 默认路径） | ✅ 已实测 | 0 新增体积 | 不重新 link 资源表，只改包名/组件/权限字段——本报告采用 |

**本轮实测结论**：手机端**没有**可用的 aapt2/aapt；模板法可以完成「脚本 APK 式」打包（改写既有 AXML），
但无法把新的 `res/` 源目录编译成新资源表（`res` 只能用模板现成的或预编译 `resources.ap_`）。
若未来要在手机上做完整资源编译，最小方案是把原版 Pro 的 `libaapt2.so` + `android.jar` 作为可选插件分发，
或在电脑/CI 侧预生成 `resources.ap_` 再下发（后者违背纯手机流水线目标）。

### 3.2 zipalign / apksigner

| 组件 | 手机端现状 | 可用来源 | 备注 |
|---|---|---|---|
| zipalign | ❌ 无 | AOSP `build-tools/zipalign`（C++，Apache-2.0）；仓库内有 Python 参考实现 `.artifacts/16kb-eval/zipalign2.py`（PC 侧，非手机工具） | 32 位对齐/未压缩 `resources.arsc`；targetSdk≥30 时**必须**（见 §6） |
| apksigner | ❌ 无 | AOSP `tools/apksig`（Java，Apache-2.0）；社区 `fornwall/apksigner`（可跑在 Android 上）；**宿主已内置等价纯 Java v1+v2 签名** | 本报告用宿主 `KeyStoreApkSigner` 完成 v1+v2；无 v3/v4 |

**实测**：手机端签名成功，PC `apksigner verify` 仅作事后核对（明确标注 PC 工具，不参与流水线）：

```text
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Signer #1 certificate DN: C=CN, O=AI.js Pro, CN=ToolchainD
Signer #1 certificate SHA-256: 3bf6f7ca0cca3125ce3a1fe89c0a19c58203820320aea07bc11e0d2e3fdd1fd0
```

**zipalign 缺失的影响**：本次 targetSdk=28，`resources.arsc` 虽仍是**压缩存储**（zip `compress_type=8`）
且未对齐，安装仍成功；但 **targetSdk≥30** 时 Android 要求 `resources.arsc` 未压缩且 4 字节对齐，
当前宿主签名器直接 `DEFLATE` 全部条目 → 不满足（见 §6 阻塞点 3）。

### 3.3 android.jar / 平台 stub

| 来源 | 手机端可得性 | 体积 | 许可 |
|---|---|---|---|
| 原版 Pro `assets/aapt/android.jar` | 仅原版 Pro APK 内（本宿主未捆绑；MI8 未装原版） | **26,467,929 B** | Apache-2.0（Android SDK） |
| 设备 `/system/framework/framework.jar` | 有文件，但内容是 **dex/oat**，不是 javac 用的 class stub | — | 系统镜像，不可直接当 stub |
| Android SDK（电脑 `C:\Android\platforms\android-35\android.jar`） | ❌ 不在手机 | ~30MB | 不可作为手机端来源（除非用户自行 push，且只是复制数据） |
| Termux / 社区 SDK 移植 | ⚠️ 需安装 Termux + 额外包，非当前环境 | — | 不默认依赖 |
| **D8/Smali 路径** | ✅ 无需 android.jar | — | 编译器不解析 framework 类，只做 dex 转换 |

**结论**：本流水线（Smali/D8 + 模板 AXML）**不需要 android.jar**；只有「手机端 javac 编译 Java 源码」
才需要它，届时最现实的来源是**可选插件里带 26MB 的 Pro 同款 android.jar**（或由 PC 预编译 class 再下发）。

## 4. 实测流水线（MI8，手机端执行）

### 4.1 输入准备（PC 仅做文件提取，标注非 SDK 工具）

宿主 candidate-1245 APK 自身 assets 里已有打包骨架（与 Pro 的 `assets/template/*` 同思路）：

| 文件 | 大小 | SHA-256 | 说明 |
|---|---:|---|---|
| `assets/template.apk` | 17,198,344 | `e086933455db51189c6288b75708618b5209f95ef1f7144f43a172f776c0ea33` | 含二进制 AndroidManifest.xml / resources.arsc / 4 个 dex / res / assets |
| `assets/autojs.keystore` | 2,062 | `59df0df4338f00715a5d23ed6d42eec33e60e47c16eb0894da8263b697c3b9e6` | 旧 tiny-sign 公共证书（本轮未用，改自生成身份） |
| 从 template.apk 提取的 `AndroidManifest.xml` | 19,276 | `53f2230f9ac6a6b74376b56fb98e56815672c93c896e8220b584b3a59a5a8c37` | 二进制 AXML（模板清单，包名 `com.jdkshen.aijspro.inrt`） |
| 从 template.apk 提取的 `resources.arsc` | 1,433,152 | `76de5a2c51de44591908223c9e7ace30585cd1f8e6417f869fd386bb581cfba0` | 模板资源表 |

> 提取动作使用 PC Python `zipfile`（仅解压宿主自有 asset），**不是** aapt/apksigner 等 SDK 工具；
> 手机端后续未依赖任何 PC SDK 工具。更彻底的手机端提取需要 Java unzip 驱动（未做，列入后续）。

把这些输入 `adb push` 到 `/data/local/tmp/toolchain-d/` 后，以下全部在手机端执行。

### 4.2 DEX：Smali 与 D8 两条路都在手机上验证通过

```sh
# 1) Smali 汇编（用于本 APK 的最小类：App + SplashActivity）
CLASSPATH=$APK app_process /system/bin com.stardust.autojs.runtime.tool.AndroidBuildTool \
    smali assemble -o dex/classes.dex App.smali SplashActivity.smali
# 结果：classes.dex 740 B（dex 035），driver.dex 1,924 B（pipeline 驱动）
# 2) D8 把 Java8 JAR 转 DEX（复用仓库 tiny-sign-0.9.jar 作为输入样本）
CLASSPATH=$APK app_process /system/bin com.stardust.autojs.runtime.tool.AndroidBuildTool \
    d8 --min-api 26 --output d8out tiny-sign.jar
# 结果：D8_EXIT=0，classes.dex 9,472 B，
#       sha256 d6092e8718134eb746b7450a28d5e2dff3ca231bcca9acba87226c291d9c537e
# 输入 jar sha256 971e9d851df61417ead51fb9b47c60ac3f1e8a590e73e428c50b7b60a603dcf2
```

### 4.3 改写二进制 Manifest（手机端 Java）

驱动 DEX（smali 手写，1,924 B）调用宿主类完成；核心等价代码：

```java
ManifestEditor ed = new ManifestEditor(new FileInputStream(args[0]));
ed.setPackageName(args[1]).setVersionName(args[2]).setVersionCode(1).setAppName(args[3]);
ed.commit();
ed.writeTo(new FileOutputStream(args[4]));
SigningKey key = KeyStoreGenerator.generate("ToolchainD", "AI.js Pro", "CN");
new KeyStoreApkSigner(key, "toolchaind").sign(new File(args[5]), new File(args[6]));
```

真实执行命令（全部在 MI8 的 shell 里）：

```sh
APK=$(pm path com.jdkshen.aijspro | sed 's/package://')
cd /data/local/tmp/toolchain-d
CLASSPATH="$APK:/data/local/tmp/toolchain-d/dex/driver.dex" \
  app_process /system/bin toolchain.Pipeline \
  src/AndroidManifest.xml com.ai.jspro.pipeline.d d-1 ToolchainD \
  ws/AndroidManifest.xml ws out/toolchain-d.apk
# 输出：PIPELINE_OK，EXIT=0
```

> ManifestEditor 用 `pxb.android.axml` 解析/重写 AXML：改 `package`、`versionCode/Name`、`application@label`，
> 并把以原包名开头的自定义 `<permission>` 改名（`com.ai.jspro.pipeline.d.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`）。
> 组件 `android:name` 是绝对类名，不随包名替换——这也是后面「启动崩溃」的原因之一。

### 4.4 打包+签名（手机端纯 Java，v1+v2）

`KeyStoreApkSigner.sign(workspace, outputApk)` 在手机上完成：遍历 workspace → 写 zip → 生成
`META-INF/MANIFEST.MF`、`CERT.SF`、`CERT.RSA`（v1）→ 原地插入 APK Signing Block（v2）；
签名身份由 `KeyStoreGenerator.generate` 现场生成 RSA-2048 自签名（不依赖 `autojs.keystore`）。

## 5. 产物与验证结果

### 5.1 变体 A（Manifest + classes.dex + resources.arsc）

| 项 | 值 |
|---|---|
| 路径（手机） | `/data/local/tmp/toolchain-d/out/toolchain-d.apk` |
| 大小 / SHA-256 | **304,178 B** / `c09c97f6306f317146ef510084077aa9a476c247df12c4f397a0a6e4d2acb2d8` |
| zip 条目 | `AndroidManifest.xml` 14,420 / `classes.dex` 740 / `resources.arsc` 1,433,152 / `META-INF/*`（v1） |
| 包名 / 版本 | `com.ai.jspro.pipeline.d` / versionCode 1 / versionName `d-1` / label `ToolchainD` |
| sdk | minSdk 21 / targetSdk 28（来自模板 Manifest） |
| 签名 | v1+v2，RSA-2048，证书 `C=CN, O=AI.js Pro, CN=ToolchainD`，SHA-256 `3bf6f7ca0cca3125ce3a1fe89c0a19c58203820320aea07bc11e0d2e3fdd1fd0` |
| 安装 | **成功**：`adb install -r` → `Success`；`pm path` 返回 base.apk；`dumpsys` 显示 version:2 签名 |
| 启动 | **失败**（预期，已复现取证）：10:47:40 logcat `LoadedApk: ClassNotFoundException: androidx.core.app.CoreComponentFactory`
→ `AndroidRuntime: RuntimeException: Unable to get provider com.google.mlkit.common.internal.MlKitInitProvider`
（模板 Manifest 引用的类不在我们最小 DEX 中） |

### 5.2 变体 B（仅 Manifest + classes.dex，无 resources.arsc）

| 项 | 值 |
|---|---|
| 大小 / SHA-256 | **7,685 B** / `670ee59c72affc17deec2f14889b0071911759053f08c63578fc0bdaace186a8` |
| 包名 | `com.ai.jspro.pipeline.d2` / versionName `d-2` |
| 安装 | **成功**（`Success`，versionCode 1、version:2 签名）→ 说明**安装阶段不要求 resources.arsc** 存在/可解析 |
| 启动 | 未尝试（同理缺类）；安装成功后已卸载 |

结论：**「最小可安装 APK」= 二进制 Manifest + classes.dex + v1/v2 签名（共 ~7.7KB）** 即可；
但 Manifest 中声明的组件/资源引用会让「可启动」需要额外内容（见 §6）。

### 5.3 清理与宿主状态

- 测试包 `com.ai.jspro.pipeline.d` / `.d2` 均已 `adb uninstall`（`pm path` 空）；为核实启动崩溃，变体 A
  曾再次重装 → monkey 启动 → 抓取 10:47:40 logcat 崩溃链 → 再次卸载，当前仍无残留包。
- 宿主 `com.jdkshen.aijspro`：安装前后均 `candidate-1245`，未被覆盖/停止之外无改动。
- 设备 scratch 目录 `/data/local/tmp/toolchain-d` 已删除。
- 证据（PC `.artifacts/toolchain-d-20261025/`，gitignore）：`template.apk`、`autojs.keystore`、
  `template-AndroidManifest.xml`、`template-resources.arsc`、两个 APK、`pipeline.smali`、`App.smali`、
  `SplashActivity.smali`、`Args.smali`。

## 6. 未完成步骤与阻塞点

1. **没有设备端 aapt2/aapt**：无法把新的 `res/` 源编译进 resources.arsc / 重新 link 清单；当前只能
   「改写模板清单 + 复用模板资源表」。要支持自定义资源必须交叉编译或插件分发 aapt2（+26MB android.jar）。
2. **没有 zipalign**：`KeyStoreApkSigner` 用 `ZipOutputStream` 默认 DEFLATE；本 APK 的
   `resources.arsc` 为压缩条目、未 4 字节对齐。targetSdk=28 时安装通过；**targetSdk≥30 不满足
   “resources.arsc 未压缩且对齐”**（Android 11 行为变更），需要：`ZipEntry.STORED` 写 arsc +
   对齐实现（纯 Java 可能）或插件带 zipalign。
3. **签名只有 v1+v2**：宿主 `KeyStoreApkSigner` 无 v3/v4；普通安装 OK，密钥轮换/应用商店要求需补 v3。
4. **无 javac / Java 源码编译链**：本报告用 Smali（手写）+ D8（Java8 JAR）证明字节码→DEX 可用；
   `Manifest + res + Java source` 里的「源码→class」仍需工具链 B 的 Java 编译器与 android.jar stub。
5. **可安装 ≠ 可启动**：模板 Manifest 的 `appComponentFactory`、MLKit/FileProvider/无障碍服务等组件
   引用类不在最小 DEX 中；要可启动需 (a) `setComponentsToRemove` 裁剪组件 + 提供
   `AppComponentFactory` stub，或 (b) 直接把模板的 4 个 dex/资源打进 APK（体积回到 ~17MB）。
   本轮未做该分支。
6. **输入提取仍依赖 PC 一步**：模板 APK/资源表/Manifest 是 PC 侧 Python 解压后 push 到手机的；
   纯手机端可用 Java 解压驱动补齐（未做）。
7. **Termux 方案未验证**：MI8 未安装 Termux；上游 `aapt` 包历史不可构建，社区 build-tools 移植非官方，
   不能作为交付依赖。
8. **Android 16 / 真实 16KB 页设备未测**：本机 Android 15；native 库对齐（16KB）与目标设备行为未覆盖。

## 7. 组件元数据建议（给 buildtools 插件）

实测一个独立包名的最小 APK 所需字段（来自 `aapt dump badging` / `dumpsys`，PC 工具仅校验）：

| 字段 | 建议值 / 说明 |
|---|---|
| `package` | `com.ai.jspro.pipeline.d`（测试）/ 插件正式建议 `com.jdkshen.aijspro.toolchain.pkg` 之类；**独立于宿主包名**，避免覆盖 |
| `versionCode` / `versionName` | 1 / `d-1`（测试）；正式建议单调递增，与构建产物名绑定 |
| `minSdkVersion` | 模板给 21；宿主/D8 实际 26 更稳（Node24/Flutter 主机为 minSdk 26） |
| `targetSdkVersion` | 模板给 28（本轮可安装）；**新产物建议 35，但必须同时解决 arsc 未压缩+对齐**（见阻塞 2） |
| `application@label` | `ToolchainD`（ManifestEditor.setAppName 改写） |
| `application@name` | 模板 `com.jdkshen.aijspro.inrt.App`；最小 DEX 需提供同名类（本轮已提供 smali stub） |
| `appComponentFactory` | 模板带 `androidx.core.app.CoreComponentFactory`；**可启动必须提供该类或移除该属性**（ManifestEditor 无此 setter，是当前缺口） |
| 组件 | 安装-only APK 可以保留模板组件声明（不计类存在性）；**可启动**需裁掉无关 activity/service/receiver/provider，或补齐类/asset |
| 权限 | 模板带大量权限（存储/网络/悬浮窗/无障碍/MLKit 等）；最小产物建议`setPermissionsToRemove` 只保留实际用到的 |
| 签名 | 自签 RSA-2048，v1+v2；正式插件建议固定一套身份（如 `toolchain.keystore`）以保证可覆盖升级 |
| 测试安装授权 | 仅测试机 MI8；独立包名；安装后验证再卸载，宿主与 K40 不动 |

## 8. 许可证/体积汇总

| 组件 | 来源 | 体积 | 许可 |
|---|---|---:|---|
| aapt2（若引入） | AOSP build-tools / 原版 Pro `libaapt2.so` | 数 MB | Apache-2.0（AOSP `build-system/aapt2/NOTICE`） |
| android.jar（若引入） | 原版 Pro `assets/aapt/android.jar` | **26,467,929 B** | Apache-2.0（Android SDK） |
| zipalign（若引入） | AOSP build-tools | <1MB | Apache-2.0 |
| apksig / apksigner（若引入） | AOSP `tools/apksig` | 数百 KB~MB | Apache-2.0 |
| Smali/Baksmali 2.5.2（宿主已带） | 宿主 candidate-1245 | 已含 | **BSD**（工具自报） |
| D8/R8（宿主已带） | AOSP R8 | 已含 | AOSP R8 项目许可（BSD 风格；以 AOSP 仓库 LICENSE 为准） |
| 宿主打包类（ManifestEditor/Signer 等，已带） | 本仓库 `third-party/ApkBuilder` + `apps/app` | 已含 | 本仓库许可 |
| 模板 apk / autojs.keystore（已带） | 宿主 assets | 17.2MB / 2KB | 本仓库产物 |

## 9. 后续建议

1. **buildtools 插件优先走「模板清单改写」路线**（本轮已验证），不捆绑 aapt2/android.jar；
   仅当用户需要自定义 `res/` 时再提供可选 aapt2 插件（Pro 同款：`libaapt2.so` + 26MB android.jar）。
2. **补 zipalign 等价实现**：Java/JS 把 `resources.arsc` 以 `STORED` + 4 字节对齐写入（targetSdk≥30 必需），
   并保留一个可校验 zipalign 的 PC 侧检查（仅验证）。
3. **补 v3 签名**（apksig 或自行实现 v3 block）以支持密钥轮换与更严格设备；v1+v2 已可安装。
4. **可启动最小 APK**：裁剪模板组件 + 提供 `AppComponentFactory` stub，或按需打包模板 dex/asset；
   建议下一步实测 `setComponentsToRemove` 全组件裁剪 + stub 的启动。
5. **纯手机端输入提取**：用 Java 解压驱动替代 PC Python 解压模板（保持「全手机端」目标）。
6. **与工具链 B/C 合并验收**：B 产出 class/dex、C 产出 ELF/.so，D 负责组装/签名/安装；
   统一由 buildtools 插件 API 编排，权限/元数据按 §7 固定。
