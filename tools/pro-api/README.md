# tools/pro-api —— 第一代 Pro API 逐函数对照的生成链路

把《Auto.js Pro 官方 API 文档》（335 页 PDF）解析成逐条清单，并与**当前源码**做静态对照，产出
`docs/architecture/pro-api-compatibility.csv`。

产出文档：[`docs/architecture/第一代API逐函数对照.md`](../../docs/architecture/第一代API逐函数对照.md)。

## 跑一遍

```powershell
# 依赖（只需一次）
& '.venv\Scripts\python.exe' -m pip install pdfminer.six

# 全流程：提取 → 建索引 → 源码对照 → 汇总表
& '.venv\Scripts\python.exe' 'tools\pro-api\rebuild.py' --pdf 'C:\Users\18101\Downloads\Auto.js Pro官方API文档.pdf'

# 只重跑其中几步（不动 PDF 时最常用）
& '.venv\Scripts\python.exe' 'tools\pro-api\rebuild.py' --stage check --stage rollup
```

四个阶段可以自由组合：`extract`、`index`、`check`、`rollup`。
`--pdf` 默认指向上面那个下载路径；换成别的路径即可对照别的版本文档。

### 真机成员探针（可选，但强烈建议）

静态扫描看不到宿主对象（`UiObject`、`window`、`Tensor`…）的成员。把清单里出现的模块名与全局名交给设备枚举，可以把一大批 `待探针` / `未定位` 直接变成结论：

```powershell
# 1) 生成 4 个变体：rhino / rhino-ui / quickjs / quickjs-ui
#    ⚠️ 文件名与引擎的对应关系（2026-09-15 修正：以前写成 -qjs.js，没有这个文件）：
#      pro-api-probe.js        = quickjs
#      pro-api-probe-ui.js     = quickjs + "ui";
#      pro-api-probe-rhino.js  = rhino
#      pro-api-probe-rhino-ui.js = rhino + "ui";
& '.venv\Scripts\python.exe' 'tools\pro-api\gen-device-probe.py'

# 2) 一键跑完全部 7 个探针（4 成员枚举 + 3 路径取值）并重算对照
powershell -NoProfile -ExecutionPolicy Bypass -File 'tools\pro-api\run-device-probes.ps1'
```

脚本内置的坑（手工跑时也要注意）：

- 每次 `am start` 前必须先 `am force-stop com.jdkshen.aijspro`：应用在前台时 `am start` 只投递 onNewIntent，**脚本不会重跑**，日志会是空的（或上一次的）。
- 设备不用小米 8 时改脚本顶部的 `$adb` / `-s ce4d2bdb`。

手工命令（供参考）：

```powershell
$adb='C:\Android\platform-tools-2\adb.exe'
foreach ($v in @('rhino','rhino-ui')) {
  & $adb -s ce4d2bdb push ".artifacts\pro-api-probe-$v.js" "/sdcard/probe/current.js"
  & $adb -s ce4d2bdb shell am force-stop com.jdkshen.aijspro
  & $adb -s ce4d2bdb logcat -c
  & $adb -s ce4d2bdb shell am start -a android.intent.action.VIEW -d "file:///sdcard/probe/current.js" `
      -t text/plain -n com.jdkshen.aijspro/.external.open.RunIntentActivity
  & $adb -s ce4d2bdb shell sleep 25
  cmd /c "`"$adb`" -s ce4d2bdb logcat -d -s GlobalConsole:V > `".artifacts\probe-$v.log`""
}
```

# 3) 解析（引擎名要与变体对应）
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-probe.py' '.artifacts\probe-rhino.log' rhino
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-probe.py' '.artifacts\probe-rhino-ui.log' rhino-ui
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-probe.py' '.artifacts\probe-qjs.log' quickjs
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-probe.py' '.artifacts\probe-ui.log' quickjs-ui
# 4) 带上实机结论重新对照
& '.venv\Scripts\python.exe' 'tools\pro-api\rebuild.py' --stage check --stage rollup
```

### 逐条路径取值探针（更硬的一层，推荐一起跑）

成员枚举有个致命盲区：**Rhino 的 `ui` / `floaty` 窗口是 `ProxyJavaObject` 包装**，
`getOwnPropertyNames` 只能看到 JS 挂上去的 expando（`ui` 只有一个 `bindingContext`），
">UI API 全没挂"。逐条取值能穿过包装：

```powershell
# 生成 pro-api-paths*.js（818 个 a.b.c 形式的条目）
& '.venv\Scripts\python.exe' 'tools\pro-api\gen-device-probe.py'   # 同一条命令一起生成

# 在设备上跑（rhino / rhino-ui / qjs），日志名随意
foreach ($v in @('rhino','rhino-ui','qjs')) {
  & $adb -s ce4d2bdb push ".artifacts\pro-api-paths-$v.js" "/sdcard/probe/paths-$v.js"
  & $adb -s ce4d2bdb logcat -c
  & $adb -s ce4d2bdb shell am start -a android.intent.action.VIEW -d "file:///sdcard/probe/paths-$v.js" `
      -t text/plain -n com.jdkshen.aijspro/.external.open.RunIntentActivity
  & $adb -s ce4d2bdb shell sleep 20
  cmd /c "`"$adb`" -s ce4d2bdb logcat -d -s GlobalConsole:V > `".artifacts\probe-paths-$v.log`""
}

# 解析（多个日志可以合并到同一个引擎）
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-paths.py' `
    '.artifacts\probe-paths-rhino.log' '.artifacts\probe-paths-rhino-ui.log' --engine rhino
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-paths.py' `
    '.artifacts\probe-paths-qjs.log' --engine quickjs
```

探针只打印**取得到**的条目（`PROBE_PATH|ui.layout|function`），日志里没出现的即为取不到。

`.artifacts/pro-api-device-*.json` 不存在时，`check` 阶段会**自动跳过**实机合并，只出静态结果。

设备侧坑：

- 脚本名与路径用 ASCII（`/sdcard/probe/rhino.js`），中文路径 + `file://` 会被截断；
- 应用**已在顶层**时 `am start` 只投递 intent 不会重跑脚本（日志出现 “delivered to currently running top-most instance”）→ 先 `am force-stop`（会顺带关掉无障碍服务，之后要重开）；
- logcat 缓冲很小，长行会被截断 → 探针按 600 字节分片，并带 `PROBE_BEGIN/PROBE_END` 判断是否跑完（解析结果里的 `complete` 字段）；
- 普通模式与 `"ui";` 模式要**各跑一遍**：`canvas` 这类名字只在 UI 上下文才可能挂上（本项目实测两者无差异）。

## 行为验收脚本（按清单补实现时用）

清单里的 `实机有` / `已定义` 只证明「名字在」；**行为对不对**靠这两份脚本（补别的模块时照此加）：

| 脚本 | 覆盖 | 断言数 |
| --- | --- | --- |
| `crypto-contract.js` | 2.8 `$crypto`（含文档第 74~75 页黄金向量 `7hu7UQKpjBeOqpEoOoHjxQ==`） | 12 |
| `zip-contract.js` | 2.35 `$zip` / `ZipFile`（含加密压缩、AES-256、`includeRootFolder`、条目增删） | 28 |
| `debug-contract.js` | 2.9 `$debug`（dumpHprof / dumpAndSendHprof / getStackTrace / 内存泄露检测 / gc） | 18 |

⚠️ `debug-contract.js` 会真的 dump 两个 hprof 并压一个 zip（约 40~60 秒，脚本结束前自己删掉）；
真机跑它时 `sleep` 给到 70 秒。

```powershell
$adb='C:\Android\platform-tools-2\adb.exe'
foreach ($script in @('crypto-contract.js','zip-contract.js','debug-contract.js')) {
  & $adb -s ce4d2bdb push "tools\pro-api\$script" '/sdcard/probe/current.js'
  & $adb -s ce4d2bdb shell am force-stop com.jdkshen.aijspro   # 否则 am start 只投递 intent，不重跑
  & $adb -s ce4d2bdb logcat -c
  & $adb -s ce4d2bdb shell am start -a android.intent.action.VIEW -d 'file:///sdcard/probe/current.js' `
      -t text/plain -n com.jdkshen.aijspro/.external.open.RunIntentActivity
  & $adb -s ce4d2bdb shell sleep 30
  cmd /c "`"$adb`" -s ce4d2bdb logcat -d -s GlobalConsole:V > `.artifacts\$script.log`""
  Select-String -Path ".artifacts\$script.log" -Pattern 'OK   |FAIL |_SUMMARY' -Encoding utf8
}
```

期望：`crypto-contract` = 11 行 `OK` + `CRYPTO_SUMMARY|pass=12 failed=0`；
`zip-contract` = 28 行 `OK` + `ZIP_SUMMARY|pass=28 failed=0`；
`debug-contract` = 18 行 `OK` + `DEBUG_SUMMARY|pass=18 failed=0`（日志里还会出现若干条 `[MemoryLeak]`）。

## 产物

| 文件 | 说明 |
| --- | --- |
| `docs/architecture/pro-api-compatibility.csv` | 主产物，938 行，UTF-8 BOM |
| `.artifacts/pro-api-device-<引擎>.json` | 真机成员表（`rhino` / `rhino-ui` / `quickjs` / `quickjs-ui`） |
| `.artifacts/pro-api-paths-<引擎>.json` | 真机逐条路径存在性（`rhino` / `quickjs`） |
| `.artifacts/pro-api-lines.json` | PDF 逐行文本 + 字号/字体/粗体比例 |
| `.artifacts/pro-api-index.json` | 模块 / 小节 / 条目 + 参数表、返回值、版本标记 |
| `.artifacts/pro-api-status.json` | 对照结果（CSV 的 JSON 版，便于脚本消费） |
| `.artifacts/pro-api-rollup.md` | 模块汇总表（粘进文档用） |

`.artifacts/` 被 git 忽略，删掉也能随时重建。

## 为什么要读字体信息

这份 PDF 是公众号文章汇编，**标题字号并不统一**：第 2 页的条目标题是 15.0，第 18 页是 12.0。按绝对字号切标题必然出错。真正稳定的是样式：

| 样式 | 用途 | 判定 |
| --- | --- | --- |
| 粗体 12.0 / 14.0 / 14.1 / 15.0 | API 条目标题 | `bold_ratio >= 0.99 且 size >= 11.8` |
| 部分粗体 ≥ 13.0 | 章节标题（`2.1 xxx`、`2.1.1 xxx`） | `size >= 13.0 且 bold_ratio >= 0.30 且以编号开头` |
| 粗体但 11.0 / 11.1 | `[Pro 8.8.17 新增]` 标记 | 靠字号排除，否则会多出约 80 个假条目 |
| 9.0 | 页眉页脚 | 拼接跨页签名时跳过 |

注意：**中英文使用不同字体**（同一行里汉字是 `DengXian`，数字是 `ArialMT`），所以判定看的是
「整行里有没有粗体字形」而不是「首个字符的字体」。

## 对照规则（静态，不跑代码）

**Rhino**

1. 先取模块专属源文件：`modules/engine/src/main/assets/modules/__<模块>__.js`、同名 Java/Kotlin 类
   （映射表见 `rebuild.py` 的 `HINTS`）。
2. 若模块名是类名（首字母大写，如 `UiSelector`），再按 `extends` / Kotlin `:` / `implements`
   递归加上**父类**文件（`UiSelector` → `UiGlobalSelector`），深度 ≤ 4。
3. 都没有时回退到「提到过这个名字的所有文件」（如 `window.setPosition`）。
4. 成员判定：定义形状（`mod.m =`、`m: function`、`public ... m(`、`fun m(`、`m() {`）记 `已定义`；
   只出现名字记 `同模块`。
5. 仍未命中时再扫一遍全局兜底文件（`__globals__.js`、`init.js`、`ScriptRuntime.java` 等，
   因为 Auto.js 同时把这些函数暴露成全局），状态加后缀 `(全局)`。
6. 最后才做全仓库 `<模块>.<成员>` 调用点搜索，记 `仅引用`。

**QuickJS**

1. 从 `modules/engine/src/main/cpp/quickjs_jni.cpp` 抽出内嵌 bootstrap 的对象字面量成员：
   `const files = {` 与 `global.threads = Object.freeze({` 两种写法都支持。
2. 全局函数（`toast`、`setClip`…）单独匹配 `global.<名字> =` / `function <名字>(`。
3. 再依次找 `quickjs_jni.cpp`、`QuickJsHostBridge.java` 的调用点，然后是 QuickJS 回归脚本
   （`回归已覆盖`）和 `sample/QuickJS 新引擎/**` 样例（`样例已用`）。
4. 模块名不是引擎模块（`window`、`UiObject`、`Tensor`…）且都没命中时标 `待探针(类成员)` ——
   这些在 QuickJS 里是宿主 Java 对象，静态扫描必然看不到，**不能当作不支持**。

### 真机结果的合并优先级（`check` 阶段）

1. **路径取值命中的直接判 `实机有`**（最强，能穿过 `ProxyJavaObject`）；
2. 成员枚举命中也判 `实机有`；
3. **否定要保守**：只有「本来就是模块的名字」（`MODULE_NAMES`、非 `globals`）才允许判 `实机缺`，
   而且还要静态也没找到实现 —— 类型名（`Rect`/`Key`/`ScriptEngine`，`kind=class`）与
   实例方法路径（`RootAutomator.tap`）本来就不是全局，取不到不代表没实现；
4. 模块在、成员表里没它 → `实机未见`（可能是实例成员或非枚举属性）。

## 状态词表

| 状态 | 来源 | 含义 |
| --- | --- | --- |
| `实机有` | 探针 | 真机（普通或 `"ui";` 变体）枚举到了该成员 |
| `实机缺` | 探针 + 静态 | 两遍都没挂载，**且静态也没找到实现**；只对「本来就是模块」或「2.16 全局章节里的函数」生效 |
| `实机未见` | 探针 | 模块挂上了，但成员表里没有它（可能是非枚举属性 / 实例成员） |
| `已定义` / `已定义(全局)` | 静态 | 源码里有定义形状 |
| `回归已覆盖` / `样例已用` | 静态 | QuickJS：出现在回归脚本 / 仓库样例里 |
| `同模块` / `同模块(全局)` | 静态 | 成员名在模块自己的源文件里，但没有定义形状 |
| `仅引用` | 静态 | 只找到调用点 |
| `待探针(类成员)` | 静态 | 模块名是对象/类，QuickJS 侧静态与探针都看不到成员 → **不等于不支持** |
| `未定位` | 三层 | 都没有命中，是整改清单 |
| `不适用` | — | 章节标题之类的伪条目 |

## 加新规则 / 改口径

- 模块与源文件的映射写在 `HINTS`；引擎真实模块名集合写在 `MODULE_NAMES`。
- 想调试某个模块取到了哪些源文件：

  ```powershell
  $env:PRO_API_DEBUG_MOD='floaty'
  & '.venv\Scripts\python.exe' 'tools\pro-api\rebuild.py' --stage check
  ```

改完规则请重跑 `--stage check --stage rollup`，并把汇总表同步进文档（表里数字会变）。

## 边界

- 这是**静态对照 + 成员枚举**，不是行为验收；`已定义` / `实机有` 只代表源码里有、设备上挂得上。
- 动态挂载（`Proxy`、`Object.defineProperty`、反射注册）的成员扫不到，`未定位` ≠ 未实现。
- 探针只能枚举**对象上的成员**：类型名（`Rect`、`Key`、`UiObject`）与回调参数对象（`canvas`）本来就不是全局，探不到不代表缺 —— `check` 阶段已经把这些排除在 `实机缺` 之外。
- 结论要以 CSV 里的证据路径（`*_evidence`）为准，不要只看统计数字。
