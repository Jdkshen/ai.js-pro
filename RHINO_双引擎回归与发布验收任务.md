# Rhino / QuickJS 双引擎回归与发布验收任务

> 用途：把本文件直接交给开发代理执行。目标不是只修改脚本，而是在连接的手机上完成可复现的 Rhino、QuickJS 双引擎回归，并提交真实日志。

## 1. 最终目标

完成以下闭环：

1. 修正 Rhino 与 QuickJS 回归脚本的验收逻辑。
2. 构建包含最新实例的 APK。
3. 安装到当前选定的一台物理手机。
4. 自动把最新测试脚本同步到手机脚本目录。
5. 自动启动 Rhino 和 QuickJS 回归，不要求用户手动翻目录、点击脚本或复制日志。
6. 修复所有真实失败项并重新测试。
7. 目标手机上的两个引擎全部通过后，才允许更新完成报告。

## 2. 相关文件

- Rhino 回归：`app/src/main/assets/sample/脚本引擎/Rhino 回归测试.js`
- QuickJS 回归：`app/src/main/assets/sample/QuickJS 新引擎/QuickJS 全模块回归测试.js`
- QuickJS 报告：`QUICKJS_完成报告.md`
- 一键发布：`release.ps1`
- APK：`app/build/outputs/apk/common/debug/app-common-arm64-v8a-debug.apk`

## 3. 强制规则

1. Rhino 脚本不能添加 `// @engine quickjs`；默认引擎就是 Rhino。
2. 不要把脚本移动到根目录来规避定位问题。脚本所在目录不会改变默认 Rhino 选择规则。
3. 不要让用户在手机上向下滚动、进入文件夹、手动点击运行或截图日志。
4. 必须通过 ADB 精确同步、启动和读取日志。
5. 不能只看到 `*_OK` 字样就判定通过；必须同时确认：
   - 失败数为 0；
   - 没有 `FAIL`；
   - 没有未捕获异常；
   - 没有崩溃；
   - 成功标记是在全部断言后输出。
6. 不得通过删除断言、放宽条件或隐藏错误来“修复”测试。
7. 保留工作区中与本任务无关的现有修改。
8. 不得使用以前运行留下的日志作为本次证据；每次运行必须带新的开始时间，并保存本次完整原始日志。
9. 不能只验证 API 的 `typeof`。文件、存储、Shell、事件等可自动化模块至少要包含一次真实调用和结果断言。
10. 测试产生的临时文件、Storage、定时器、事件监听、线程和子引擎必须在 `finally` 中清理，失败路径也不能残留。
11. 必须分别证明实际运行的是 Rhino 和 QuickJS，不能只根据脚本所在目录或文件名推断引擎。
12. 完成报告只能引用本轮构建、本轮安装和本轮运行生成的证据；无法验证的项目写 `未验证`，不能写 `PASS`。

## 4. 先修正回归脚本

### 4.1 Rhino 回归

当前 Rhino 脚本必须在结尾使用严格验收：

```javascript
console.log('\n=== Rhino Regression Result: ' + pass + ' passed, ' + fail + ' failed ===');
if (fail > 0) {
    throw new Error('Rhino 回归失败: ' + fail + ' 项未通过');
}
console.log('RHINO_REGRESSION_OK');
```

保留 Rhino 专属验证：

```javascript
assert('java.lang.String exists', typeof java.lang.String === 'function');
```

该断言同时用于证明脚本确实运行在 Rhino，而不是 QuickJS。

### 4.2 QuickJS 回归

确认 QuickJS 脚本包含 `// @engine quickjs`，并在 `fail > 0` 时抛出异常。当前基线的 42 项断言必须全部通过；若本任务新增断言，则以更新后的总数为准。全部通过后才能输出：

```text
QUICKJS_REGRESSION_OK
```

### 4.3 静态检查

至少执行：

```powershell
node --check "app/src/main/assets/sample/脚本引擎/Rhino 回归测试.js"
node --check "app/src/main/assets/sample/QuickJS 新引擎/QuickJS 全模块回归测试.js"
```

同时统计两个脚本中的断言数量，避免测试项被意外删除。当前基线为 Rhino 32 项、QuickJS 42 项；修改后数量不得低于基线。若合理新增断言，报告和验收条件必须同步更新，不能继续硬编码旧数字。

### 4.4 引擎身份与行为检查

- Rhino：保留 `java.lang.String` 的真实调用或类型断言，并在日志中记录 `engines.myEngine()` 可读取的引擎信息。
- QuickJS：除 `// @engine quickjs` 外，还要记录 `engines.myEngine().engineName`（或等价运行时信息），确认实际创建了 QuickJS 引擎。
- QuickJS 运行环境专项脚本至少验证 `class`、`Promise`、`async/await`、可选链中的适用项；解析或运行失败必须计入失败，不能跳过后仍记为通过。
- `images`、截图和 YOLO 若因权限、模型文件或设备条件无法运行，应明确写 `SKIP/未验证` 及原因，不能用“API 存在”代替功能通过。

## 5. 构建与 APK 校验

如果只修改 JavaScript、Markdown 或 Java，可执行：

```powershell
.\release.ps1 -SkipNative
```

如果修改了 C/C++、CMake 或原生库，必须执行完整构建：

```powershell
.\release.ps1
```

构建完成后必须验证：

1. Gradle 返回码为 0。
2. arm64 APK 存在且更新时间为本次构建时间。
3. APK 内同时包含 Rhino 和 QuickJS 两个回归脚本。
4. 记录 APK 完整路径、字节数和完整 SHA256。
5. 记录本次 Git 提交号（若有）和工作区是否存在未提交修改，确保 APK 能对应到源代码状态。
6. 对 APK 内两个回归脚本计算 SHA256，并与工作区源文件比较，防止打包进旧实例。

不要因为终端中文文件名显示乱码，就误判 APK 内不存在资源；应按 ZIP 条目、文件数量或 UTF-8 工具再次确认。

## 6. 安装到目标物理手机

先执行：

```powershell
adb devices -l
```

识别当前连接的物理设备，并明确选定一台目标手机。若该手机同时存在 USB 和无线 ADB，只测试一次，优先使用 USB 序列号。

对目标手机执行：

```powershell
adb -s SERIAL install -r "app/build/outputs/apk/common/debug/app-common-arm64-v8a-debug.apk"
adb -s SERIAL shell am force-stop org.autojs.autojs
adb -s SERIAL shell monkey -p org.autojs.autojs -c android.intent.category.LAUNCHER 1
```

必须检查目标手机返回 `Success`。不能因为它同时出现 USB 与无线 ADB 连接而重复安装，也不能在未确认设备身份时随意使用 `adb devices` 返回的第一条连接。

安装后还必须验证：

```powershell
adb -s SERIAL shell pm path org.autojs.autojs
adb -s SERIAL shell dumpsys package org.autojs.autojs
```

记录 `versionName`、`versionCode` 和安装路径，并确认启动后进程存在。设备处于锁屏、离线、未授权或存储空间不足时应直接报错，不得继续生成通过报告。

## 7. 同步手机脚本目录

在目标手机创建目录：

```text
/sdcard/脚本/脚本引擎/
/sdcard/脚本/QuickJS 新引擎/
```

然后使用 `adb push` 精确复制文件到完整目标路径：

```powershell
adb -s SERIAL push "app/src/main/assets/sample/脚本引擎/Rhino 回归测试.js" "/sdcard/脚本/脚本引擎/Rhino 回归测试.js"
adb -s SERIAL push "app/src/main/assets/sample/QuickJS 新引擎/QuickJS 全模块回归测试.js" "/sdcard/脚本/QuickJS 新引擎/QuickJS 全模块回归测试.js"
```

注意：推送目录时 ADB 可能把目录内容展开到目标根目录。本任务只推送两个明确文件，避免再次出现文件位置错误。

推送后使用 `ls -l`、文件大小或 SHA256 校验手机文件与电脑源文件一致。

## 8. 自动运行 Rhino 回归

目标手机测试前清空日志：

```powershell
adb -s SERIAL logcat -c
```

通过运行入口直接启动脚本：

```powershell
adb -s SERIAL shell am start -n org.autojs.autojs/.external.open.RunIntentActivity --es path "/sdcard/脚本/脚本引擎/Rhino 回归测试.js"
```

最多等待 30 秒，主动轮询日志，查找：

```text
=== Rhino Regression Test ===
=== Rhino Regression Result: ... ===
RHINO_REGRESSION_OK
FAIL
ScriptException
QuickJsException
FATAL EXCEPTION
```

通过条件：

- `fail = 0`；
- 出现 `RHINO_REGRESSION_OK`；
- `java.lang.String` 断言通过；
- 没有脚本异常和应用崩溃。

## 9. 自动运行 QuickJS 回归

同样先清空日志，然后启动：

```powershell
adb -s SERIAL shell am start -n org.autojs.autojs/.external.open.RunIntentActivity --es path "/sdcard/脚本/QuickJS 新引擎/QuickJS 全模块回归测试.js"
```

通过条件：

- 日志显示全部断言通过、`0 失败`；通过数不得低于当前 42 项基线，新增断言后以更新后的总数为准；
- 出现 `QUICKJS_REGRESSION_OK`；
- 没有 `FAIL`、`QuickJsException`、未捕获异常或崩溃。

## 10. 稳定性与证据留存

### 10.1 防止旧日志误判

每个引擎单独执行以下闭环：

1. 记录手机时间和测试开始时间。
2. 清空日志并启动一个新的脚本引擎实例。
3. 轮询到结果标记或 30 秒超时；超时必须返回失败。
4. 结果出现后继续检查是否随后发生脚本异常或应用崩溃。
5. 保存未删减的原始日志，不能只保存 `grep` 后的几行。

建议按以下目录留证：

```text
.codex-artifacts/regression/YYYYMMDD-HHMMSS/
├─ device.txt
├─ apk.txt
├─ rhino-run-1.log
├─ rhino-run-2.log
├─ quickjs-run-1.log
└─ quickjs-run-2.log
```

证据目录中至少记录：设备序列号、型号、Android/API 版本、build fingerprint、APK 完整 SHA256、手机端脚本 SHA256、执行命令、开始/结束时间和返回结论。

### 10.2 重复运行与资源清理

- Rhino 和 QuickJS 在目标手机上各连续运行至少 2 次，两次都满足严格通过条件才算稳定通过。
- 第二次运行前不得重装 APK，用它验证引擎关闭、监听移除、线程终止和临时资源清理是否可靠。
- 每次结束后检查不存在仍运行的测试子引擎、Shell 子进程或同名临时文件。
- 对截图、图色、YOLO 等依赖外部条件的专项测试，分别记录截图权限、模型路径、输入尺寸和后端；没有模型时标记 `未验证`。

## 11. 失败处理

出现失败时必须：

1. 保存完整失败日志和对应手机信息。
2. 定位到具体断言、API 桥或引擎选择逻辑。
3. 修复实现，而不是修改预期结果。
4. 重新构建并安装到目标设备。
5. 从 Rhino 开始重新跑完整双引擎回归。

同一设备上的一次偶然通过不算完成；超时、Shell、截图等容易波动的项目应连续运行多次。

## 12. `release.ps1` 验收

检查并修正一键发布脚本的以下问题：

1. 构建失败必须返回非 0，不能吞掉关键错误输出。
2. 找不到 APK 时应明确失败，不能继续访问空对象。
3. `-Install` 应明确选择一台目标物理设备；同一手机的 USB 与无线 ADB 连接必须去重并优先使用 USB，不能无说明地取第一条连接。
4. 必须检查目标设备的 `adb install` 返回码。
5. 输出完整 SHA256，同时可以附加短摘要用于显示。
6. 安装后应验证包 `org.autojs.autojs` 确实存在并能启动。
7. 建议增加 `-Serial` 参数用于显式选择目标手机；检测到多个不同物理设备且未指定时应停止并提示，而不是猜测。
8. 构建、安装、启动或测试任一阶段失败时，脚本最终退出码必须为非 0。
9. 不得用 `2>$null` 或 `Out-Null` 吞掉构建失败所需的诊断信息；可以同时输出到终端并保存日志文件。

## 13. 最终交付格式

完成时提供一张结果表：

| 手机 | 型号/系统 | Rhino | QuickJS | 异常/崩溃 | 结论 |
|------|-----------|-------|---------|-----------|------|
| 目标设备 | 实际读取 | 实际通过数/0 | 实际通过数/0（不少于 42 项） | 0 | PASS/FAIL |

同时提供：

- 修改文件清单；
- 构建命令和结果；
- APK 路径、大小、SHA256；
- 目标手机的关键原始日志；
- 原始证据目录的完整路径；
- 目标手机序列号、型号、Android/API 版本、build fingerprint、应用版本号；
- 两轮 Rhino 与两轮 QuickJS 的独立结果；
- 测试后的临时文件、线程、子引擎和子进程清理结果；
- 是否仍有未解决限制。

只有目标手机的 Rhino 与 QuickJS 都满足通过条件，才可以写“发布验收完成”。
