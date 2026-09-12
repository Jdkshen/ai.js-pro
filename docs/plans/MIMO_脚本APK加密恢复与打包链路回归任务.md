# MiMo 任务链：脚本 APK 加密恢复 + 打包链路回归

> 核对日期：2026-09-04。当前分支 `chore/reorganize-project-layout`（HEAD `9130358`），项目目录已重组为 `apps/`、`modules/`、`third-party/`。

## 背景与当前问题

目录重组时，`com.jdkshen.aijspro.autojs.build`（`ApkBuilder`）与 `com.jdkshen.aijspro.build`（`ApkBuilderPluginHelper`、`TinySign`）源码包曾误删，已从归档 APK 反编译恢复。恢复后的 `ApkBuilder.encrypt()` 目前是**占位实现**：

```java
// apps/app/src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java:250
private void encrypt(FileOutputStream outputStream, File file) throws IOException {
    // Compatibility placeholder: template projects are packaged as-is; the original
    // implementation performed AES encryption using mKey/mInitVector. ...
    StreamUtils.write(new FileInputStream(file), outputStream);
}
```

后果：**应用内“打包脚本 APK”产出的脚本是明文写进 `assets/project/`**，运行时不再具备脚本保护。打通加密链还需要确认打包端与运行时端密钥派生一致、`project.json` 的 `buildId` 正确写入，否则运行时会解密失败。

## 完整加密链路（已核实，作为实现依据）

```
打包端（ApkBuilder，Android 应用内）
  key = MD5(packageName + versionName + mainScriptFile)         // AssetsProjectLauncher.kt:102
  vec = MD5(buildId + name).substring(0, 16)                     // AssetsProjectLauncher.kt:103
  ① project.json 写入 buildInfo.buildId / name / packageName / versionName / main
  ② 脚本文件 = EncryptedScriptFileHeader.writeHeader(flags=0) + AES/CBC/PKCS5 加密内容

运行时端（inrt，见 apps/inrt/src/main/java/com/jdkshen/aijspro/inrt/launch/AssetsProjectLauncher.kt）
  initKey(projectConfig)  第 101-107 行
    用同样的 MD5 派生 key/vec，反射设置 ScriptEncryption.mKey / mInitVector
  XJavaScriptEngine.kt  第 33-37 行
    EncryptedScriptFileHeader.isValidFile(bytes) 校验头 → ScriptEncryption.decrypt(...)
```

### 关键类（均已存在，勿重复实现）

| 类 | 位置 |
|---|---|
| `ScriptEncryption`（decrypt；key/vec 经反射注入） | `modules/engine/src/main/java/com/stardust/autojs/engine/encryption/ScriptEncryption.kt` |
| `AdvancedEncryptionStandard`（AES/CBC/PKCS5Padding，encrypt/decrypt） | `modules/common/src/main/java/com/stardust/util/AdvancedEncryptionStandard.kt` |
| `EncryptedScriptFileHeader`（`BLOCK_SIZE=8`，魔数 `77 01 17 7F 12 12` + 2 字节 flags） | `modules/engine/src/main/java/com/stardust/autojs/script/EncryptedScriptFileHeader.kt` |
| `BuildInfo`（`mBuildId` 等） | `modules/engine/src/main/java/com/stardust/autojs/project/BuildInfo.java` |
| `XJavaScriptEngine`（解密端） | `apps/inrt/src/main/java/com/jdkshen/aijspro/inrt/autojs/XJavaScriptEngine.kt` |

## 任务链

### 任务 1：补全 `ApkBuilder.encrypt()` 的 AES 加密

**文件**：`apps/app/src/main/java/com/jdkshen/aijspro/autojs/build/ApkBuilder.java`

1. 删除占位实现，改为：
   - 输出 `EncryptedScriptFileHeader.writeHeader(outputStream, 0)`（复用 `EncryptedScriptFileHeader`，注意它是 Kotlin object，Java 调用用 `EncryptedScriptFileHeader.INSTANCE.writeHeader(...)` 或 `EncryptedScriptFileHeader.writeHeader(...)`——查看实际编译器产物，Kotlin `object` 在 Java 侧是 `EncryptedScriptFileHeader.INSTANCE`）
   - 用 `AdvancedEncryptionStandard(mKey.toByteArray(), mInitVector).encrypt(bytes)` 加密脚本全文并写出
2. `mKey` / `mInitVector` 当前是 `"Auto.js"` / `"Auto.js"`（恢复时保留的旧常量）。**必须与运行时端保持一致**：以 `AssetsProjectLauncher.initKey()` 的派生公式为准，即打包端也要用 `MD5.packageName+versionName+mainScriptFile` / `MD5(buildId+name).substring(0,16)`。
   - 因此任务 1 需要同时：把 `encrypt()` 改为按 `mAppConfig`（AppConfig 含 packageName/versionName/sourcePath）+ `mScriptFile`/project.json 的 `name`、`buildId` 计算 key/vec。
   - 若 `AppConfig`/`BuildInfo` 缺字段（如 `buildId` 生成），需新增：打包时生成随机 `buildId` 写入 `project.json` 的 `buildInfo.buildId`，并把它用于密钥派生（**生成后固化，运行时不重算**——运行时从 project.json 读取）。
3. 注意调用链：`copyProjectToWorkspace()` 目前 `encrypt(...)` 加密 `main.js`；项目模式下走 `copyDir` 则应对每个 `.js` 文件加密（至少对入口 `main` 加密，其余按原版行为）。

**验收**：单文件打包后，APK 内 `assets/project/main.js` 不再是明文；文件头 6 字节等于 `77 01 17 7F 12 12`。

### 任务 2：`project.json` 写入 BuildInfo 且密钥派生一致

**文件**：`apps/app/.../build/ApkBuilder.java`、`modules/engine/src/main/java/com/stardust/autojs/project/BuildInfo.java`

1. 打包时更新/生成 `project.json`：确保 `buildInfo.buildId`、`name`、`packageName`、`versionName`、`main` 存在（`ProjectConfig.fromFile` 读回时可解析）。
2. 校验打包端与运行时端 `initKey()` 的派生公式严格一致：
   - `key = MD5(packageName + versionName + mainScriptFile)`
   - `vec = MD5(buildId + name).substring(0, 16)`（16 字节，AES/CBC IV 长度要求）
   - 若 `mainScriptFile` 为空/带路径前缀，与 `initKey` 中 `projectConfig.mainScriptFile` 使用同源（不要一处带 `assets/project/` 一处不带）。
3. `MD5` 工具类确认：项目内是否有 `MD5`（`AssetProjectLauncher` 用的 `MD5.md5(...)`）——确认是哪个类（如 `com.stardust.util.MD5`），打包端复用同一个，避免两种哈希库结果不同。

**验收**：主机端单测——用固定输入跑打包端派生函数，输出等于 `AgentProjectLauncher.initKey` 的派生值（或在手机端打一个包，inrt 能正常解密执行）。

### 任务 3：端到端打包运行验证（真机）

**前置**：完成 1、2 后重新构建 `:app:assembleCommonDebug` 并安装到设备（小米 K40 / `cccc62c7`，arm64-v8a）。

1. 应用内“单文件打包”：选一个脚本 → 打包出独立 APK → 安装该 APK → 启动 → 脚本应正常执行，无 `javax.crypto` / `BadPaddingException` / 空字符串报错。
2. 应用内“项目打包”：含 `project.json` 的项目 → 打包 → 安装启动 → 同样正常。
3. 反编译检查：`unzip -p 产物.apk assets/project/main.js | head -c 16` 应为 `77 01 17 7F 12 12` + 密文（非明文头部）。
4. 对照验证：一个**不经过打包**的主应用内联脚本（直接 Run）仍能执行——确认改动只影响打包链路。

**验收**：三端（打包端、运行端）可跑通；产物 APK 内脚本为密文；直接 Run 不受影响。

### 任务 4：回归与提交

1. `gradlew test`（至少 `:engine:test`、`:common:test`、`:apkbuilder:test`）通过；`build-miuix-debug.ps1 -SkipNative` 构建成功。
2. 提交信息（参考项目现有风格）：
   ```
   fix: restore script encryption in bundled APK packaging
   ```
3. 若影响已推送内容，push 到 `origin`（当前 `main` 与 `chore/reorganize-project-layout` 均在 `9130358`）。

## 注意事项

- **不要改 `ScriptEncryption`/`AdvancedEncryptionStandard`/`EncryptedScriptFileHeader`**——它们是运行时端契约，只允许补齐打包端调用。
- `EncryptedScriptFileHeader` 是 Kotlin `object`，Java 调用注意 `INSTANCE`；或把 `writeHeader` 改为 `@JvmStatic`（若改动允许）。
- `inrt` 是打包产物运行时，`assets/project/` 的脚本在 `mProjectDir` 解包后由 `XJavaScriptEngine` 执行；解密失败时会 `catch (GeneralSecurityException)` 后静默——调试改为先看 logcat。
- 打包端 `mKey`/`mInitVector` 不能是固定 `"Auto.js"`（那是恢复时的占位值，与运行时派生公式不匹配）。
