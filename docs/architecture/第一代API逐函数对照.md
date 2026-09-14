# 第一代 Pro API 逐函数对照（Auto.js Pro 官方 API 文档 PDF）

对照日期：2026-09-15。对象：`C:\Users\18101\Downloads\Auto.js Pro官方API文档.pdf`（335 页，第一代 / Rhino 默认引擎）。
真机：**小米 8（`ce4d2bdb`，Android 15）**，两个引擎各跑「普通脚本」与 `"ui";` 脚本。

交付四层证据：**排版解析 → 静态源码对照 → 真机成员枚举 → 真机逐条路径取值**。
它仍然**不是行为验收**：`实机有` 只说明这个名字在设备上取得到、成员在，不代表默认值/异常/回调线程/权限与 Pro 一致。

## 1. 交付物

| 文件 | 内容 |
| --- | --- |
| [`docs/architecture/pro-api-compatibility.csv`](pro-api-compatibility.csv) | **主交付物**。938 行逐条对照，UTF-8 BOM |
| `tools/pro-api/rebuild.py` | 生成 CSV 的脚本（提取 → 建索引 → 对照 → 汇总），会自动并入真机结果 |
| `tools/pro-api/gen-device-probe.py` | 生成探针：成员枚举 4 变体 + **逐条路径取值 2 变体** |
| `tools/pro-api/parse-device-probe.py` / `parse-device-paths.py` | 解析探针日志 |
| `tools/pro-api/README.md` | 复现步骤、判定规则、设备侧踩坑 |
| `.artifacts/pro-api-{lines,index,status}.json`、`.artifacts/pro-api-device-*.json`、`.artifacts/pro-api-paths-*.json` | 中间产物（`.artifacts/` 被 git 忽略，可重建） |
| `.artifacts/probe-*.log` | 本轮 7 次真机运行的原始日志 |

CSV 列：`module_num, module, api, kind, pdf_page, version, section, rhino, quickjs, rhino_evidence, quickjs_evidence, signature, note`

## 2. 四层证据

### 2.1 排版解析（PDF → 条目）

这份 PDF 是公众号文章汇编，**标题字号逐页不同**（第 2 页 15.0、第 18 页 12.0），按字号绝对值切标题必然出错。稳定的是**排版样式**：

| 样式 | 用途 | 判定 |
| --- | --- | --- |
| 粗体 12.0 / 14.0 / 14.1 / 15.0 | API 条目标题 | `bold_ratio ≥ 0.99 且 size ≥ 11.8` |
| 部分粗体 ≥ 13.0 | 章节标题 | `size ≥ 13.0 且 bold_ratio ≥ 0.30 且以编号开头` |
| 粗体但 11.0 / 11.1 | `[Pro 8.8.17 新增]` 标记 | 靠字号排除（否则多出约 80 个假条目） |

**同一行里中英文是不同字体**（汉字 DengXian、数字 ArialMT），所以要看「整行有没有粗体字形」。
结果：**39 个模块章节 / 97 小节 / 994 条目** → 剔噪后 **938 行**。

### 2.2 静态源码对照

- **Rhino**：模块 JS（`assets/modules/__*.js`）、同名 Java/Kotlin 类、**父类**（`UiSelector` → `UiGlobalSelector`，按 `extends`/`:`/`implements` 递归）、全局兜底（`__globals__.js`/`init.js`/`ScriptRuntime.java`）。
- **QuickJS**：`cpp/quickjs_jni.cpp` 内嵌 bootstrap 的对象字面量（`const x = {`、`global.x = Object.freeze({`）、全局赋值、宿主桥调用点、回归脚本与样例。

### 2.3 真机成员枚举

79 个模块/对象名 + 207 个全局名，枚举 `getOwnPropertyNames` → 原型链(≤6) → 函数 `.prototype` → Java 反射(沿父类 ≤8) → Rhino `.class`。
⚠️ **它有一类系统性盲区**：Rhino 的 `ui` / `floaty 窗口` 是 `ProxyJavaObject` 包装，`getOwnPropertyNames` 只返回 JS 挂上去的 expando（`ui` 只看到 `bindingContext`），成员藏在包装后面枚举不到。所以还需要第 4 层。

### 2.4 真机逐条路径取值（最硬的一层）

把清单里 818 个 `a.b.c` 形式的条目交给设备**逐个求值并报告 typeof**（`PROBE_PATH|ui.layout|function`）。
这一层能穿过 `ProxyJavaObject`（`$ui.layout` 取得到），是逐 API 对照真正需要的证据，也是"这个函数到底在不在"的直接答案。
结果：**Rhino 358 / 818 取得到，QuickJS 230 / 818 取得到**。

## 3. 状态词表

| 状态 | 来源 | 含义 |
| --- | --- | --- |
| `实机有` | 路径取值 / 成员枚举 | 设备上取到了这个名字（优先用路径取值） |
| `实机缺` | 路径取值 + 静态 | 设备上取不到，**且静态也没找到实现**；只对模块成员生效（类型名/实例方法不判缺） |
| `实机未见` | 成员枚举 | 模块在，但成员表里没有它（可能是实例成员或非枚举属性） |
| `已定义` / `已定义(全局)` | 静态 | 源码里有定义形状 |
| `回归已覆盖` / `样例已用` | 静态 | QuickJS：出现在回归脚本 / 仓库样例里 |
| `同模块` / `同模块(全局)` | 静态 | 成员名在模块自己的源文件里，但没有定义形状 |
| `仅引用` | 静态 | 只找到调用点 |
| `待探针(类成员)` | 静态 | 模块名是对象/类，QuickJS 侧静态看不到 → **不等于不支持** |
| `未定位` | 四层 | 都没有命中，是整改清单 |
| `不适用` | — | 章节标题混进来的伪条目 |

## 4. 模块汇总

| 模块 | 条目 | 函数 | Rhino 实机有 | Rhino 源码有 | Rhino 待核实 | Rhino 未定位 | Rhino 实机缺 | QuickJS 实机有 | QuickJS 源码有 | QuickJS 待探针 | QuickJS 未定位 | QuickJS 实机缺 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1.2 如何阅读本文档 | 2 | 2 | 2 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 |
| 2.1 自动化-控件操作 | 117 | 106 | 19 | 90 | 1 | 4 | 3 | 16 | 0 | 90 | 1 | 10 |
| 2.2 自动化 - 坐标操作 | 32 | 30 | 11 | 0 | 18 | 3 | 0 | 11 | 0 | 12 | 2 | 0 |
| 2.3 app - 应用 | 25 | 21 | 24 | 0 | 1 | 0 | 0 | 17 | 0 | 0 | 0 | 8 |
| 2.4 base64 | 2 | 2 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 |
| 2.5 colors - 颜色 | 19 | 8 | 19 | 0 | 0 | 0 | 0 | 8 | 0 | 0 | 0 | 11 |
| 2.6 canvas - 画布 | 30 | 29 | 0 | 29 | 0 | 1 | 0 | 0 | 0 | 0 | 1 | 29 |
| 2.7 console - 控制台 | 18 | 18 | 18 | 0 | 0 | 0 | 0 | 11 | 0 | 0 | 0 | 7 |
| 2.8 crypto - 加解密与消息摘要 | 12 | 6 | 1 | 1 | 1 | 9 | 0 | 0 | 1 | 3 | 8 | 0 |
| 2.9 debug - 调试工具 | 5 | 5 | 0 | 1 | 0 | 4 | 0 | 0 | 0 | 0 | 5 | 0 |
| 2.10 device - 设备 | 46 | 28 | 45 | 0 | 0 | 0 | 1 | 18 | 0 | 0 | 0 | 28 |
| 2.11 dialogs - 对话框 | 26 | 13 | 10 | 0 | 12 | 4 | 0 | 8 | 0 | 4 | 1 | 1 |
| 2.12 engines - 脚本引擎 | 21 | 15 | 7 | 4 | 5 | 4 | 1 | 6 | 0 | 6 | 4 | 2 |
| 2.13 events - 事件与监听 | 18 | 14 | 2 | 14 | 1 | 1 | 0 | 1 | 0 | 14 | 1 | 1 |
| 2.14 floaty - 悬浮窗 | 25 | 23 | 3 | 18 | 0 | 2 | 2 | 3 | 9 | 7 | 2 | 2 |
| 2.15 files - 文件系统 | 40 | 37 | 27 | 9 | 2 | 2 | 0 | 18 | 0 | 0 | 3 | 18 |
| 2.16 globals - 全局变量与函数 | 16 | 15 | 16 | 0 | 0 | 0 | 0 | 13 | 1 | 2 | 0 | 0 |
| 2.17 http - HTTP 网络请求 | 13 | 5 | 13 | 0 | 0 | 0 | 0 | 4 | 0 | 7 | 1 | 1 |
| 2.18 images - 图片处理 | 76 | 64 | 43 | 9 | 7 | 15 | 2 | 42 | 1 | 27 | 4 | 2 |
| 2.19 keys - 按键模拟 | 21 | 21 | 21 | 0 | 0 | 0 | 0 | 21 | 0 | 0 | 0 | 0 |
| 2.20 media - 多媒体 | 9 | 9 | 9 | 0 | 0 | 0 | 0 | 9 | 0 | 0 | 0 | 0 |
| 2.22 ocr - 文字识别 | 12 | 4 | 0 | 0 | 4 | 8 | 0 | 0 | 0 | 9 | 3 | 0 |
| 2.23 plugins - 插件 | 4 | 1 | 1 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 4 | 0 |
| 2.24 power_manager - 电源管理 | 4 | 2 | 0 | 0 | 1 | 3 | 0 | 0 | 0 | 0 | 4 | 0 |
| 2.25 sensors - 传感器 | 8 | 3 | 4 | 0 | 3 | 1 | 0 | 4 | 0 | 0 | 1 | 0 |
| 2.26 shell - Shell 命令 | 6 | 6 | 6 | 0 | 0 | 0 | 0 | 6 | 0 | 0 | 0 | 0 |
| 2.27 storages - 本地存储 | 8 | 7 | 2 | 5 | 0 | 1 | 0 | 2 | 0 | 5 | 1 | 0 |
| 2.28 settings - 设置 | 2 | 2 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 2 | 0 |
| 2.29 threads - 多线程 | 20 | 17 | 8 | 6 | 5 | 1 | 0 | 5 | 0 | 8 | 2 | 3 |
| 2.30 thirdPartyPlugins - 第三方插件 | 40 | 23 | 0 | 0 | 2 | 37 | 1 | 0 | 0 | 30 | 8 | 2 |
| 2.31 timers - 定时器 | 6 | 6 | 6 | 0 | 0 | 0 | 0 | 6 | 0 | 0 | 0 | 0 |
| 2.32 work_manager - 定时与任务 | 21 | 10 | 9 | 1 | 7 | 4 | 0 | 0 | 0 | 7 | 14 | 0 |
| 2.33 util - 工具 | 23 | 20 | 22 | 0 | 0 | 1 | 0 | 0 | 15 | 5 | 3 | 0 |
| 2.34 WebSocket | 6 | 5 | 5 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 2 | 4 |
| 2.35 zip - 压缩与解压 | 17 | 17 | 0 | 1 | 6 | 10 | 0 | 0 | 0 | 12 | 5 | 0 |
| 2.36 Ui - 用户界面 | 188 | 30 | 25 | 2 | 145 | 12 | 3 | 16 | 6 | 6 | 11 | 4 |
| **合计** | **938** | **624** | **378** | **192** | **222** | **132** | **13** | **247** | **33** | **254** | **95** | **133** |

**读法**：`实机有 + 源码有 + 待核实` = 有证据合计 → Rhino **776/938（83%）**，QuickJS **550/938（59%）**。

（`2.36` 的 188 条多是 XML 属性与控件属性标签，不是 JS 函数；`2.30` 是插件 SDK 类。与普通模块不可直接比较。）

## 5. 真机查出来的事实

### 5.1 模块挂载

| 类别 | 内容 |
| --- | --- |
| **两边都有（25）** | `$ui`、`app`(+`app.autojs`)、`auto`、`automator`、`colors`、`console`、`device`、`dialogs`、`engines`、`events`、`files`、`floaty`、`http`、`images`、`media`、`runtime`、`sensors`、`storages`、`threads`、`Image`/`RootAutomator`/`Shell` |
| **只有 Rhino（9）** | `$crypto`、`$images`、`$plugins`、`$util`、`$util.java`、`$web`、`$work_manager`、`Response`、`WebSocket` |
| **只有 QuickJS（1）** | `events.bus` |
| **两边都缺（44）** | `$base64`、`$debug`、`$ocr`、`$power_manager`、`$settings`、`$zip`、`canvas`、`dialog`、`globalconsole`、`$ui.imageCache`，以及类型名（`Rect`/`Key`/`UiObject`/`ZipFile`/`Tensor`…） |

### 5.2 三条纠正/发现

1. ⚠️ **`$ui.*` 是好的**（本次纠正）：成员枚举只能看到 `ui` 的 1 个 expando 属性，一度看起来像"UI API 全没挂"；**逐条路径取值证明 `$ui.layout`、`$ui.findView`、`$ui.post`… 在两个引擎上都是 function**。`2.36` 的判定因此从"全未定位"变成 25 条有证据 + 16 条 QuickJS 实机有。
   → 教训：**枚举不到 ≠ 不存在**，`ProxyJavaObject` 包装的对象必须用取值法判定。
2. **`"ui";` 模式与普通模式没有差异**：同一批名字两边都缺，说明缺失不是"没进 UI 上下文"。
3. **类型名与实例方法不能判缺**：`Rect`/`Key`/`ScriptEngine` 是返回值类型，`canvas` 是 `<canvas>` 回调传入的对象（Rhino 侧由 `ScriptCanvas` 实现，示例就在用），`RootAutomator.tap` 是实例方法 —— 探针取不到它们都是正常的。

### 5.3 真机直接确认"取不到"的条目（`实机缺`）

- **Rhino 13 条**：`automator.takeScreenshot/switchToInputMethod/headsetHook`、`floaty.checkPermission/requestPermission`、`images.getSimilarity/detectsMultiColors`、`engines.startFloatingController`、`app.getInstalledApps`（命名差异，实现叫 `getInstalledPackageList`）、`console.getInput/setColor/setTextSize`（2.36 的 `<console>` 控件属性）、`device.broad`（文档残片）、`ocr.filterScore`。
- **QuickJS 133 条**，与仓库既有结论完全吻合：`canvas.*` 29、`device.*` 28、`files.*` 18、`colors` 常量 11、`app.*` 8、`console.*` 7、`WebSocket.*` 4、`threads.disposable/atomic/lock` 3 等。

## 6. 未定位 / 实机缺 的定性结论

对 §7 清单逐条查过源码与真机后，分三类：

### 6.1 漏扫 / 命名差异 —— 实现其实存在（13 条）

**这 13 条已经修进扫描器**：`rebuild.py` 现在带 `HINTS`（`UiCollection` → `UiObjectCollection`、`ReadableTextFile` → `PReadableTextFile` …）与 `RENAME_HINTS`（PDF 名 → 实现名），并修掉了 `$` 前缀模块取不到 `HINTS` 的问题。重跑后它们的状态是 `已定义` / `已定义(改名)`，CSV 里能看到 `实现名 xxx` 的证据。

| 条目 | 结论与证据 |
| --- | --- |
| `UiCollection.size/each/empty/nonEmpty/find/findOne` | **漏扫**：实现在 `modules/automator/.../UiObjectCollection.kt:207/220/227/237/246/250`，类名与文档写法不同 |
| `ReadableTextFile.read/readline/readlines`（+ 类型本身） | **漏扫**：实现在 `modules/common/.../pio/PReadableTextFile.java`（类名带 `P` 前缀） |
| `PWritableTextFile.write/writeline/writelines/flush/close` | **漏扫**：同上，`PWritableTextFile.java` |
| `RootAutomator.tap/swipe/press/longPress/touchDown/touchMove/touchUp` | **漏扫**：`__RootAutomator__.js` 用 methods 列表动态挂到**实例**上，静态查不到（真机成员表也看不到，属已知盲区） |
| `$base64.encode/decode` | **命名差异**：实现在 `$crypto.base64Encode/base64Decode`（`Crypto.java:117`，实机已确认存在） |
| `$work_manager.addIntentTask` | **命名差异**：实现叫 `addBroadcastIntentTask`（`WorkManagerModule.kt:65`，实机确认） |
| `app.getInstalledApps` | **命名差异**：实现叫 `getInstalledPackageList`（历史改名，避免与 Java 方法冲突） |

### 6.2 真缺 —— 两个引擎都没有（约 35 条）

`$zip.zipDir/zipFiles/open` 与 `ZipFile.setPassword/isEncrypted/extractAll/addFile/…`（13 条）、
`$crypto.encrypt/decrypt/generateKeyPair` 与 `Key`/`KeyPair`、
`$debug.dumpHprof/dumpAndSendHprof/gc/setMemoryLeakDetectionEnabled`、
`ColorMapping.*`（4）、`RootAutomator2` 与 `RootAutomator2.longPress`、
`$power_manager.*`（2）、`OCR.detect`/`OCRResult.clickCenter`/`$ocr.create`、
`$images.getScreenCaptureOptions/stopScreenCapture/detectAndComputeFeatures/matchFeatures`、
`floaty.checkPermission/requestPermission`、`images.getSimilarity/detectsMultiColors`、
`automator.takeScreenshot/switchToInputMethod/headsetHook`、`engines.startFloatingController`、
`UiObject.clickCenter`、`dialog.setDefaultDialogType/getProgress/getMaxProgress`、`isAlive`、`getPath`。

### 6.3 类型名 —— 不是缺口

`Rect`、`Key`、`KeyPair`、`OCR`、`OCRResult`、`TimedTask`、`EventEmitter`、`Storages`、`ScriptEngine`、`ScriptExecution`、`Dialog`、`FloatyWindow`、`FloatyRawWindow`、`SensorEventEmitter`、`Bug` 等：文档里是**返回值类型**，本来就不该是全局。已排除在"缺"之外。

## 7. 该从哪些行开始

CSV 里筛 `rhino ∈ {未定位, 实机缺}` 且 `kind ∈ {function, constructor}`、排除 `2.30`/`2.36`，共 **49 条**（未定位 41 + 实机缺 8）：

| 模块 | 未定位 | 实机缺 | 例子 |
| --- | --- | --- | --- |
| 2.35 zip | 10 | 0 | `$zip.zipDir/zipFiles/open`、`ZipFile.setPassword/isEncrypted/extractAll` |
| 2.18 images | 7 | 2 | `$images.getScreenCaptureOptions/stopScreenCapture/detectAndComputeFeatures`、`ColorMapping.*`、`images.getSimilarity/detectsMultiColors` |
| 2.8 crypto | 4 | 0 | `$crypto.encrypt/decrypt/generateKeyPair`、`Key`/`KeyPair` |
| 2.9 debug | 4 | 0 | `$debug.dumpHprof/dumpAndSendHprof/gc/setMemoryLeakDetectionEnabled` |
| 2.11 dialogs | 3 | 0 | `dialog.setDefaultDialogType/getProgress/getMaxProgress` |
| 2.1 控件操作 | 3 | 3 | `UiObject.clickCenter`、`automator.takeScreenshot/switchToInputMethod/headsetHook` |
| 2.2 坐标操作 / 2.22 ocr / 2.24 power_manager / 2.28 settings | 各 2 | 0 | `RootAutomator2.longPress`、`OCR.detect`、`$power_manager.*`、`$settings.*` |
| 2.12 engines / 2.29 threads / 2.14 floaty | 各 1 / 0 | 1 / 0 / 2 | `engines.startFloatingController`、`getPath`、`isAlive`、`floaty.checkPermission/requestPermission` |

QuickJS 侧同类共 **150 行**（未定位 43 + 实机缺 107），大头是 `canvas.*`(29)、`device.*`(28)、`files.*`(18)、`colors` 常量(11)、`app.*`(8)、`console.*`(7)。

## 8. 怎么复现

```powershell
& '.venv\Scripts\python.exe' -m pip install pdfminer.six

# PDF -> 清单 -> 静态对照（可分开跑 --stage extract|index|check|rollup）
& '.venv\Scripts\python.exe' 'tools\pro-api\rebuild.py' --pdf '<PDF 路径>'

# 真机探针：成员枚举 4 变体 + 路径取值 2 变体（设备上跑 6 次，每次约 20 秒）
& '.venv\Scripts\python.exe' 'tools\pro-api\gen-device-probe.py'
# ... 逐个 push + am start + logcat（完整循环见 tools/pro-api/README.md）
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-probe.py' '.artifacts\probe-rhino.log' rhino
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-paths.py' '.artifacts\probe-paths-rhino.log' '.artifacts\probe-paths-rhino-ui.log' --engine rhino
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-paths.py' '.artifacts\probe-paths-qjs.log' --engine quickjs

# 带上真机结论重新对照
& '.venv\Scripts\python.exe' 'tools\pro-api\rebuild.py' --stage check --stage rollup
```

`.artifacts/pro-api-device-*.json` / `pro-api-paths-*.json` 缺失时，`check` 自动退化为纯静态结果。

## 9. 这份清单不能证明什么

- **不是行为验收**：名字在 ≠ 行为一致。默认值、异常、回调线程、权限取消、资源释放仍要按 [AutoJsPro API 兼容性与 V8 选型](Auto.jsPro_API兼容性与V8选型.md) §7 的场景逐项验证。
- **单机单版本**：本轮只覆盖小米 8 + 当时构建，换设备/版本要重跑（脚本已固化）。
- **仍有盲区**：动态挂载（`Proxy`、`Object.defineProperty`、反射注册）与"要满足条件才存在"的成员（实例方法、惰性加载）都测不到；`未定位` 只代表四层证据都没找到。
- **没有 Pro 对照设备**：`version` 列只说明该条目属于哪个版本，不代表我们对齐到了同一版本。

## 10. 下一步

1. **按 §6.1 修扫描规则**：把 `UiObjectCollection`、`PReadableTextFile`/`PWritableTextFile`、`$crypto.base64*`、`addBroadcastIntentTask`、`getInstalledPackageList` 这些"名字对不上"的映射写进 `rebuild.py`，让 13 条漏扫自动归位。
2. **补真缺口**（按性价比）：`$zip` 与 `$crypto` 优先（两引擎都缺、真实脚本常用）；其次 `$debug`、`ColorMapping`、`$power_manager`、`$settings`。
3. **2.36 单独建表**：XML 属性与控件代理方法要和 JS 函数分开核对。
4. **补 Pro 侧对照**（可选）：用同一套探针跑一遍 Pro 9.x，把"我们缺"与"实现不同"彻底分开。
5. **CSV 当验收登记册**：验收一条改一条状态。

## 11. 依据

- [Auto.js Pro 官方 API 文档（335 页）](C:/Users/18101/Downloads/Auto.js%20Pro官方API文档.pdf)：唯一来源，页码与 PDF 实际页号一致。
- [第一代 API 兼容性与 V8 选型](Auto.jsPro_API兼容性与V8选型.md)、[兼容现状报告](../reports/2026-09-15_第一代API兼容现状.md)。
- 脚本与中间产物：`tools/pro-api/`、`.artifacts/pro-api-*.json`、`.artifacts/probe-*.log`。
