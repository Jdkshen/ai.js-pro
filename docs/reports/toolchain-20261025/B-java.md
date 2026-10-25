# 工具链 B：手机端 Java 源码编译可行性原型（ECJ）

日期：2026-10-25。设备：MI8 `ce4d2bdb`，当前安装 **candidate-1245**（versionCode 1245，Android API35 / sdkInt=35）。
范围：只做可行性原型与元数据建议；未安装新 APK、未发布、未改更新源；K40 未触碰。
本轮结论：**ECJ 3.33.0 可在 app UID 下跑通**（经 D8 转成 dex + 资源合并 + `javax.lang.model.SourceVersion` stub + Android SDK `android.jar` 作为 bootclasspath），
完整链路「多文件 Java（包结构 + 中文字符串）→ class → jar（设备内自举打包）→ D8 → dex → 独立 app_process 进程加载执行」已实测得到预期值。
Janino 3.1.12 作为备选：D8 转换正常，但 jar 无 CLI main（`org.codehaus.janino.Compiler` 无 `main([Ljava/lang/String;)V`），需要自建 driver，本轮未采用。

## 1. 环境与限制

- 设备：MI8 `ce4d2bdb`，LineageOS（Android API35），应用 UID 10296（`u0_a296`），candidate-1245。
- 启动方式：MCP `terminal_exec`（`uidScope=application`，非 PTY），先 `get_status` 确认 `activeMcpRuns=0`；没有用 adb shell 冒充应用 UID。
- 终端环境：`sh`、`/system/bin/app_process`、`unzip`、`sha256sum` 可用；`java/javac/d8/smali/zip/jar/node` 均不在 PATH。
- 内置工具：`AIJS_APK` 与 `AIJS_BUILD_MAIN=com.stardust.autojs.runtime.tool.AndroidBuildTool`，D8/Smali 通过独立 app_process 调用；D8 版本为 R8 8.5.35。
- 测试目录：`/storage/emulated/0/脚本/_toolchain_java_probe_mvn7w3c3`（应用 UID 可写）；编译器运行库放在 `/data/local/tmp/toolchain_java_probe`（应用 UID **只读**）。

## 2. 组件元数据（下载来源 / 版本 / 许可 / SHA-256 / 体积）

| 组件 | 版本 | 下载来源 | 许可证 | 体积（字节） | SHA-256 |
| --- | --- | --- | --- | ---: | --- |
| ECJ 原始 jar | 3.33.0（v20230218-1114） | `https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar` | EPL-2.0（POM：https://www.eclipse.org/legal/epl-2.0/） | 3,160,927 | `f7686c4960cf70c2ebc5c500a73a8cfc04541b730c18f1c5c21329889b137f45` |
| ECJ 运行库 ecj-full.jar | 由 ECJ 3.33.0 D8 后合并资源 | 本机生成（详见 §4.2） | 同 ECJ（EPL-2.0） | 1,583,762 | `e448aa6c0327d6b19375dbfe8e0599c503c37742786c6c33fcc0b50a1e1a5f82` |
| SourceVersion stub jar | 自写最小 stub（smali 汇编） | 本机生成（源码见 §4.3） | 项目自有（无第三方代码） | 445 | `ad9b0cb77885d822b04950f934f6f8b81d645f203e65d680577a226ec36a27cd` |
| Android API stub `android.jar` | SDK platform 35 | `C:\Android\platforms\android-35\android.jar`（本机 SDK） | Android SDK 许可（勿随 APK 再分发） | 27,092,450 | `4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b` |
| Janino（备选） | 3.1.12 | `https://repo1.maven.org/maven2/org/codehaus/janino/janino/3.1.12/janino-3.1.12.jar` | BSD-3-Clause（manifest Bundle-License） | 956,369 | `5699878c31e71c9a94cf472f95a2dad52ca7b0449c099276b8bd7e992a43595b` |
| commons-compiler（Janino 依赖） | 3.1.12 | `https://repo1.maven.org/maven2/org/codehaus/janino/commons-compiler/3.1.12/commons-compiler-3.1.12.jar` | BSD-3-Clause（manifest Bundle-License） | 171,943 | `17ea25d860225f4b59d0a512b4995f2e4711bdae5e15736cbcd5f6ce4dda8f7c` |
| Janino 运行库（未采用） | 3.1.12 + D8 + 资源合并 | 本机生成 | BSD-3-Clause | 375,641 | `2429292a486ec95babd0001a112f548118e76dbe2e583018f682dd94a96d6629` |
| D8（内置） | R8 8.5.35 | candidate-1245 内置 `AndroidBuildTool`，无需下载 | 随应用 | — | — |

安装包内已安装 APK 未改动：candidate-1245 仍为验收版本（`AI-js-Pro-Flutter-release-candidate-1245.apk`，SHA-256 `f72c0ceb...e202158`）。

## 3. 安装布局与权限（关键前提）

```text
/data/local/tmp/toolchain_java_probe/        0755 shell:shell（应用 UID 只能读、不能写）
  ├─ ecj-full.jar        1,583,762 B 0644  # D8 后的 classes.dex + ECJ 资源
  ├─ stub.jar                 445 B 0644  # javax.lang.model.SourceVersion stub
  ├─ android.jar       27,092,450 B 0644  # SDK platform 35 的 class stub（-bootclasspath）
  └─ apps-dex.jar          1,164 B 0644  # 本测试编译产物 D8 结果（运行用）
```

- 应用 UID 不能写 `/data/local/tmp`：本机用 `adb push` 到 `/sdcard/脚本/...` 再 `adb shell cp` 到 `/data/local/tmp/...`（直接 `adb push` 到 `/data/local/tmp` 在本轮返回 exit 1，无详细错误；cp 方式成功）。
- 必须 `chmod 755` 目录、`chmod 644` 文件：**父目录可写**也会让 ART 判为 writable dex（见 §5）。
- `/sdcard` 上的 jar 不能用于 app_process 系统类加载器（FUSE 可写挂载，`chmod 444` 无效），只能放在只读路径。

## 4. 实测步骤、命令与结果

下列命令均在测试目录（`cwd=/storage/emulated/0/脚本/_toolchain_java_probe_mvn7w3c3`）执行；设备时间约 10:36–10:47。

### 4.1 ECJ jar → DEX（现有 D8）

```sh
CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" d8 --min-api 26 --output ecj-dex.jar ecj.jar
# exit 0；输出 1,426,275 B；内含 classes.dex 3,117,908 B（压缩前）
```

### 4.2 把 ECJ 资源合并回 dex jar（关键坑）

D8 **不复制资源**，只产 `classes.dex`；ECJ 的 `org/eclipse/jdt/internal/compiler/batch/messages.properties` 等资源缺失会抛 `MissingResourceException`（§5.2）。
在电脑上用 .NET **ZipArchive** 重新打包（不能用 PowerShell 5.1 `Compress-Archive`：它把路径写成 `org\eclipse\...` 反斜杠，Java 读不到）：

```text
ecj-full.jar = ecj-dex.jar 里的 classes.dex + ecj-3.33.0.jar 里的全部非 .class 资源（保留正向斜杠）
# 107 entries；1,583,762 B；sha256 e448aa6c0327d6b19375dbfe8e0599c503c37742786c6c33fcc0b50a1e1a5f82
```

### 4.3 SourceVersion stub（Android 缺 javax.lang.model）

ECJ `FileSystem.<clinit>` 调用 `javax.lang.model.SourceVersion.valueOf("RELEASE_12")` 判空；Android 没有该包。
用现有 Smali CLI 汇编一个最小 stub（应用 UID 自举，未用桌面 javac）：

```smali
.class public final Ljavax/lang/model/SourceVersion;
.super Ljava/lang/Object;
.method public static valueOf(Ljava/lang/String;)Ljavax/lang/model/SourceVersion;
    .registers 2
    const/4 v0, 0x0
    return-object v0
.end method
```

```sh
CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" smali assemble javastub -o stub.dex   # exit 0，588 B
# 电脑把它封成 stub.jar（classes.dex）并推到 /data/local/tmp，chmod 644：445 B
```

说明：stub `valueOf` 返回 null → ECJ `isJRE12Plus=false`；对 `-source 8` 编译足够（未使用注解处理，`-proc:none`）。

### 4.4 在设备上编译多文件 Java（包结构 + 中文）

源码：`demo/tool/A.java`（返回 `你好，手机编译！`）、`demo/tool/B.java`（调用 A，断言中文结果并打印）、`JarPack.java`（设备内 jar 打包工具）。

```sh
CLASSPATH=/data/local/tmp/toolchain_java_probe/ecj-full.jar:/data/local/tmp/toolchain_java_probe/stub.jar \
app_process /system/bin org.eclipse.jdt.internal.compiler.batch.Main \
  -proc:none -source 8 -target 8 -encoding UTF-8 -nowarn -d out \
  -bootclasspath android.jar -classpath android.jar \
  src/demo/tool/A.java src/demo/tool/B.java src/JarPack.java
# exit 0；out/demo/tool/A.class、out/demo/tool/B.class、out/JarPack.class；单次 707ms（含 app_process 启动）
```

Android API 引用也验证通过：`AndroidRef.java` 引用 `android.os.Build.VERSION.SDK_INT`，ECJ 用 android.jar stub 编译 exit 0；
`d8 --min-api 26 --lib android.jar --output androidref-dex.jar out/demo/tool/AndroidRef.class` exit 0（727 B；D8 仅打印 api database 警告）。

### 4.5 class → DEX（现有 D8）

```sh
CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" \
  d8 --min-api 26 --output apps-dex.jar out/demo/tool/A.class out/demo/tool/B.class
# exit 0；apps-dex.jar 1,164 B；sha256 1ea2d2c3...5bd37ed0；单次 1,326ms（含 app_process 启动）
```

### 4.6 独立进程加载执行（得到预期值）

```sh
CLASSPATH=/data/local/tmp/toolchain_java_probe/apps-dex.jar app_process /system/bin demo.tool.B
# exit 0
# stdout: JAVA_SOURCE_DEX_OK 你好，手机编译！ message=42
# od 字节核对：... 20 e4 bd a0 e5 a5 bd ef bc 8c ...（UTF-8 中文正确）
# 单次 1,192ms（app_process 冷启动占大头）
```

### 4.7 设备内生成 JAR（无 zip/jar 命令，ECJ 自举 JarPack）

`JarPack.java` 用 `java.util.zip` 把 class 目录写成 jar；它本身由 ECJ 编译、D8 生成 dex，再用 app_process 执行：

```sh
CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" d8 --min-api 26 --output jarpack-dex.jar out/JarPack.class  # exit 0，1,651 B
CLASSPATH=/data/local/tmp/toolchain_java_probe/jarpack-dex.jar app_process /system/bin JarPack "$PWD/out" "$PWD/classes.jar"
# exit 0；JARPACK_OK 2662；classes.jar 2,662 B；sha256 ee81886a...b4f8c6f
# unzip -l classes.jar：demo/tool/B.class、JarPack.class、demo/tool/A.class（3 files）
```

### 4.8 JAR → DEX → 再运行（完整链路复核）

```sh
CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" d8 --min-api 26 --output jar-dex.jar classes.jar
# exit 0；jar-dex.jar 2,119 B；sha256 90868948...358f26c；unzip -l 内含 classes.dex 3,532 B
CLASSPATH=/data/local/tmp/toolchain_java_probe/jar-dex.jar app_process /system/bin demo.tool.B
# exit 0；输出与 4.6 相同：JAVA_SOURCE_DEX_OK 你好，手机编译！ message=42
```

### 4.9 耗时（单次，含 app_process 冷启动；仅作原型参考）

| 阶段 | 命令/对象 | 时间 |
| --- | --- | ---: |
| ECJ 编译 3 个类 | ecj-full + stub，-source 8 | 707 ms |
| D8 A/B 2 个 class | 现有 D8 CLI | 1,326 ms |
| app_process 运行 B | apps-dex.jar，含 JVM 启动 | 1,192 ms |

## 5. 失败边界（实测，并给出处理）

| # | 现象 | 复现/命令 | 结果 | 处理 |
| --- | --- | --- | --- | --- |
| 5.1 | 可写路径被 ART 拒绝 | `CLASSPATH="$PWD/ecj-full.jar:..." app_process ...`（jar 在 /sdcard） | exit 134 `Aborted`；logcat `SecurityException: Writable dex file ... is not allowed` | 移到 `/data/local/tmp`，父目录 0755、文件 0644；`/sdcard` 上 `chmod 444` 无效 |
| 5.2 | 父目录可写同样被拒 | 目录曾为 0777（文件 0644） | exit 134，告警同上 | `chmod 755` 目录 |
| 5.3 | D8 丢资源 | 用 ecj-dex.jar 跑 `-help` | exit 137；stdout `Missing resource : .../messages.properties ...`；logcat `MissingResourceException`（Main.<init>） | 合并全部非 .class 资源回 jar，保留正向斜杠 |
| 5.4 | 缺 SourceVersion | 不带 stub.jar 编译 | exit 137；logcat `NoClassDefFoundError: Failed resolution of: Ljavax/lang/model/SourceVersion;`（FileSystem.<clinit>） | 加 stub.jar 到 CLASSPATH（冒号分隔多路径可用） |
| 5.5 | 缺 bootclasspath | 不加 `-bootclasspath android.jar` | exit 255；13 个错误，如 `System cannot be resolved` | 必须同时传 `-bootclasspath android.jar -classpath android.jar` |
| 5.6 | 编译错误 | `Bad.java` 引用 MissingType | exit 255；ECJ 打出 `MissingType cannot be resolved to a type` | 按退出码 ≠0 判失败 |
| 5.7 | D8 输入不存在 | `d8 missing.jar` | exit 1；`NoSuchFileException: missing.jar` | 输入必须存在/可读 |
| 5.8 | 主类不存在 | `app_process ... demo.tool.Missing` | exit 134 `Aborted` | 校验主类名/CLASSPATH |
| 5.9 | Janino 无 CLI main | `app_process ... org.codehaus.janino.Compiler -help` | exit 137；logcat `NoSuchMethodError: no static method main([Ljava/lang/String;)V` | 需要自建 driver；本轮未采用 |
| 5.10 | adb push 到 /data/local/tmp 失败 | `adb push file /data/local/tmp/...` | exit 1，无详细错误 | 先 push 到 /sdcard，再 `adb shell cp` + chmod |

退出码语义提醒：app_process 下**未捕获异常**会走 RuntimeInit kill（shell 看到 137）；**ART 检查失败/主类缺失**会 SIGABRT（134）；两者都不是编译成功的信号。

## 6. 组件元数据建议（供后续插件/工具链落地）

### 6.1 推荐运行时包（最小 3 件）

```text
java-compiler/
  ecj-full.jar    1,583,762 B  sha256 e448aa6c...1a5f82   # EPL-2.0，D8 dex+资源
  stub.jar              445 B  sha256 ad9b0cb7...36a27cd   # 项目自有 SourceVersion stub
  android-stub.jar 27,092,450 B sha256 4566663c...b32e619b # 或由使用者提供 SDK platform android.jar
```

- 编译命令模板（Java 8 目标）：

```sh
CLASSPATH=<dir>/ecj-full.jar:<dir>/stub.jar \
app_process /system/bin org.eclipse.jdt.internal.compiler.batch.Main \
  -proc:none -source 8 -target 8 -encoding UTF-8 -nowarn -d <outdir> \
  -bootclasspath <dir>/android.jar -classpath <dir>/android.jar <sources...>
```

- 产物 DEX：`CLASSPATH="$AIJS_APK" app_process /system/bin "$AIJS_BUILD_MAIN" d8 --min-api 26 --output <out.jar> <class-dir-or-jar>`。
- JAR 打包：设备无 zip/jar，可带一个自举 `JarPack`（4 KB dex 级）或直接用 D8 从 class 文件产 DEX；JAR 不是必需中间物。

### 6.2 Android stub 的体积策略

- 27 MB `android.jar` 不适合随 APK 分发；建议：
  a) 要求使用者提供本机 SDK platform（MCP/终端场景可接受）；
  b) 或生成**最小 java-only + 所需 android 类的 stub jar**（后续任务）；
  c) 若只编译纯 Java（不 import android.*），可只带 `java.*` stub 子集。

### 6.3 交付形态建议

1. **MCP/手机终端路径（本次已验证）**：adb/桌面向 `/data/local/tmp/<toolchain>/` 安装只读 jar（0755 目录、0644 文件），命令行按 §6.1 调用。
2. **插件/APK 路径**：不要在应用运行时把编译器 jar 写到应用可写目录再交给 `app_process`（会被 writable dex 规则拒绝）。
   推荐把编译器 dex 放进插件 APK（或独立 helper APK）的 classes.dex，在**专用 :build 进程**里用 `DexClassLoader` 加载（应用自身加载自己的 dex 不受该限制；ECJ 的 System.exit 也被进程隔离）。
   若坚持 app_process，来源必须是**对应用 UID 只读**的路径（如已安装 APK 的 /data/app/... 路径），并单独确认资源（messages.properties）的 ClassLoader 解析。
3. **许可**：ECJ EPL-2.0（分发时保留许可证与获取源码途径说明）；Janino BSD-3-Clause 更宽松但无 CLI main；android.jar 属 Android SDK 许可，不应随产品 APK 再分发。

### 6.4 组件清单建议字段

```text
name/version:    ecj 3.33.0（ecj-full.jar 自定义运行包）
download:        https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar
license:         EPL-2.0（https://www.eclipse.org/legal/epl-2.0/）
sha256:          f7686c49...137f45（原始 jar） / e448aa6c...1a5f82（ecj-full.jar）
size:            3,160,927 B（原始）/ 1,583,762 B（运行包）
installLayout:   <toolchain>/ecj-full.jar、<toolchain>/stub.jar、<toolchain>/android.jar（只读、0755/0644）
classpath:       ecj-full.jar:stub.jar
bootclasspath:   android.jar（SDK platform 35；或最小 stub）
requires:        Android API26+ 应用 UID；app_process 需只读路径；无 JDK/javac
maxSourceLevel:  本轮验证 -source 8；更高版本待测
```

## 7. 未验证 / 剩余风险

- 仅验证 `-source 8 -target 8` 与普通类；Java 9+/records/注解处理（`-proc:none`）未测，ECJ 3.33 对更高 source 的支持与 D8/ART 兼容性待确认。
- 样例只覆盖 `java.*` + 一个 `android.os.Build` 引用；未覆盖完整 Android API 面（android.jar stub 方法体为空，只保证编译期符号）。
- 性能是单次冷启动参考；ECJ 编译较大工程、D8 多 class 的耗时需另行 benchmark。
- 资源解析依赖把 properties/rsc 合并进 jar；其他 ClassLoader 资源访问模式（如 SPI services）未逐项验证。
- Janino 未实际驱动编译（无 CLI main）；如因许可证/体积改选 Janino，需要额外 driver 与兼容性测试。
- 未验证 DexClassLoader 路线（本轮用独立 app_process 进程满足要求）；插件化落地需按 §6.3 做专用进程方案。
- android.jar 为本机 SDK 文件，量产分发前需按许可与体积策略处理。

## 8. 证据与产物索引

- 设备测试目录：`/storage/emulated/0/脚本/_toolchain_java_probe_mvn7w3c3/`（src/out/classes.jar/apps-dex.jar/jar-dex.jar/jarpack-dex.jar 及日志）。
- 设备只读运行库：`/data/local/tmp/toolchain_java_probe/`（ecj-full.jar / stub.jar / android.jar / apps-dex.jar / jar-dex.jar / jarpack-dex.jar，权限 0644、目录 0755）。
- 本机证据：`.artifacts/toolchain-20261025/`（ecj 原始 jar、ecj-full.jar、stub.jar、janino jars、probe 源码及 evidence/ 日志）。
- 设备 jar 哈希（sha256sum）与 §2 一致；编译/运行日志已拉到 evidence/：compile-timed.log、ecj-compile3.log、ecj-help.out、run-b.out、run-jar.out、jarpack.out、bad-compile.log、d8-missing.log、ecj-nores.log、ecj-writable.log、nostub.log、noboot.log。

> 本轮未使用桌面 javac 生成被测结果；桌面只做 jar 下载、资源 zip 合并与证据归档，编译、D8、JAR 打包与执行均在 MI8 应用 UID 内完成。
