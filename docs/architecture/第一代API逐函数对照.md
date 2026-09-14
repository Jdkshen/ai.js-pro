# 第一代 Pro API 逐函数对照（Auto.js Pro 官方 API 文档 PDF）

对照日期：2026-09-15。对象：`C:\Users\18101\Downloads\Auto.js Pro官方API文档.pdf`（335 页，第一代 / Rhino 默认引擎）。

本文交付**逐函数清单 + 静态源码对照 + 真机成员探针**三层证据。真机结论取自 **小米 8（`ce4d2bdb`，Android 15）**，两个引擎各跑「普通脚本」与 `"ui";` 脚本两遍。
它仍然**不是行为验收**：`实机有` 只说明名字挂得上、成员在，不代表默认值/异常/回调线程/权限与 Pro 一致。

## 1. 交付物

| 文件 | 内容 |
| --- | --- |
| [`docs/architecture/pro-api-compatibility.csv`](pro-api-compatibility.csv) | **主交付物**。938 行逐条对照，UTF-8 BOM，可直接用 Excel / VS Code 打开 |
| `tools/pro-api/rebuild.py` | 生成上面 CSV 的可复现脚本（提取 → 建索引 → 对照 → 汇总） |
| `tools/pro-api/gen-device-probe.py` | 生成真机成员探针脚本（4 个变体：rhino / rhino-ui / quickjs / quickjs-ui） |
| `tools/pro-api/parse-device-probe.py` | 把探针日志解析成 `.artifacts/pro-api-device-*.json` |
| `tools/pro-api/README.md` | 复现步骤、判定规则、调试方法 |
| `.artifacts/pro-api-{lines,index,status}.json` | 中间产物（`.artifacts/` 被 git 忽略，可随时重建） |
| `.artifacts/pro-api-rollup.md` | 下面的模块汇总表（脚本生成，避免手工誊抄出错） |
| `.artifacts/probe-{rhino,rhino-ui,qjs,ui}.log` | 本轮 4 次真机探针的原始日志（证据留存） |

CSV 列：

```text
module_num, module, api, kind, pdf_page, version, section,
rhino, quickjs, rhino_evidence, quickjs_evidence, signature, note
```

`kind`：`function` / `constructor` / `member`（属性、常量）/ `class`（类型，如 `Image`、`Tensor`）/ `label`（文档里用粗体标出的条目，如 `事件: show`、`interval`）。
`version`：PDF 原文的 `[Pro 8.8.17 新增]` / `[v4.1.0 新增]` 标记（共 56 条）。

## 2. 三层证据是怎么来的

### 2.1 解析 PDF（`extract` / `index`）

这份 PDF 是公众号文章汇编，**标题字号逐页不同**（第 2 页 15.0、第 18 页 12.0），按字号绝对值切标题必然出错。稳定的是**排版样式**：

| 样式 | 用途 | 判定 |
| --- | --- | --- |
| 粗体 12.0 / 14.0 / 14.1 / 15.0 | API 条目标题（`UiObject.cut()`） | `bold_ratio ≥ 0.99 且 size ≥ 11.8` |
| 部分粗体 ≥ 13.0 | 章节标题（`2.1 自动化-控件操作` 16.0、`2.1.1 入门介绍` 15.0） | `size ≥ 13.0 且 bold_ratio ≥ 0.30 且以编号开头` |
| 粗体但只有 11.0 / 11.1 | `[Pro 8.8.17 新增]` 标记 | 靠字号排除，否则会多出约 80 个假条目 |
| 9.0 | 页眉页脚 | 拼接跨页签名时跳过 |

注意：**同一行里中英文用的是不同字体**（汉字 DengXian、数字 ArialMT），所以要看「整行有没有粗体字形」，不能只看首个字符的字体。

结果：**39 个模块章节 / 97 个小节 / 994 个条目**，剔除封面标题与纯排版噪声后 **938 行**入表。

### 2.2 静态源码对照（`check`）

- **Rhino**：模块自身 JS（`assets/modules/__*.js`）、同名 Java/Kotlin 类，外加**父类**（`UiSelector` → `UiGlobalSelector`，按 `extends` / Kotlin `:` / `implements` 递归解析，深度 ≤ 4）；成员命中定义形状记 `已定义`。另有全局兜底（`__globals__.js`、`init.js`、`ScriptRuntime.java`），因为 Auto.js 同时把很多 API 暴露成全局函数。
- **QuickJS**：`cpp/quickjs_jni.cpp` 内嵌 bootstrap 的对象字面量（`const files = {`、`global.threads = Object.freeze({`）、`global.x =` 赋值、`function x(`；再加宿主桥调用点、QuickJS 回归脚本与 `sample/QuickJS 新引擎/**` 样例。

### 2.3 真机成员探针（`gen-device-probe.py` + `parse-device-probe.py`）

把清单里出现的 79 个模块/对象名与 207 个全局名交给设备枚举：

- 取值：`Object.getOwnPropertyNames` → 原型链（≤6 层）→ 函数的 `.prototype` → Java 反射（`getClass().getMethods()/getFields()` 沿父类 ≤8 层）→ Rhino 的 `.class` 包装；
- 打印成 `PROBE_MEM|<名字>|<分片序号>|<csv>`（每行 ≤600 字节，因为 logcat 会截断长行），带 `PROBE_BEGIN` / `PROBE_END` 便于判断是否跑完；
- **普通脚本与 `"ui";` 脚本各跑一遍**：`canvas` 这类只在 UI 上下文才有的名字要用 UI 变体才判得准（本轮实测两者**没有差异**，见 §5）。

跑法（Mi8）：

```powershell
adb -s ce4d2bdb push .artifacts\pro-api-probe-rhino.js /sdcard/probe/rhino.js
adb -s ce4d2bdb logcat -c
adb -s ce4d2bdb shell am start -a android.intent.action.VIEW -d file:///sdcard/probe/rhino.js `
  -t text/plain -n com.jdkshen.aijspro/.external.open.RunIntentActivity
adb -s ce4d2bdb shell sleep 25
adb -s ce4d2bdb logcat -d -s GlobalConsole:V > .artifacts\probe-rhino.log
```

## 3. 状态词表

| 状态 | 含义 | 可信度 |
| --- | --- | --- |
| `实机有` | 真机探针在该对象上枚举到了这个成员 | 高（"名字在"，不等于行为对齐） |
| `实机缺` | 真机两遍都**没有**这个模块/成员，且**静态也没找到**实现 | 高（当前最硬的"缺"） |
| `实机未见` | 模块在，但成员表里没有它 | 中（可能是非枚举属性或实例成员） |
| `已定义` / `已定义(全局)` | 源码里有定义形状的成员 | 高（源码级） |
| `回归已覆盖` | QuickJS：出现在 `QuickJS 全模块回归测试.js`（该套件当前通过） | 高 |
| `样例已用` | QuickJS：出现在仓库自带的 QuickJS 样例里 | 中 |
| `同模块` / `同模块(全局)` | 成员名在模块自己的源文件里，但没有匹配到定义形状 | 中，需人工看一眼 |
| `仅引用` | 只找到调用点 | 低 |
| `待探针(类成员)` | 模块名是**对象/类**（`UiObject`、`window`、`Tensor`），QuickJS 里是宿主 Java 对象，静态 JS 扫不到、探针也枚举不到成员 | 未知，**不是"不支持"** |
| `未定位` | 三层证据都没有命中 | 整改清单 |
| `不适用` | 章节标题混进来的伪条目 | — |

**为什么探针找不到就等于不知道而不是"缺"**：`Rect`、`Dialog`、`Key`、`UiObject` 这些名字在文档里是**类型**，由 API 返回值给出，本来就不该是全局；`canvas` 是 `<canvas>` 控件回调里传进来的**画布对象**（本轮已确认 Rhino 侧由 `ScriptCanvas` 实现，贪吃蛇示例就在用）。所以只有「本来就是模块、且两遍都没有」才判 `实机缺`。

## 4. 模块汇总

| 模块 | 条目 | 函数 | Rhino 实机有 | Rhino 源码有 | Rhino 待核实 | Rhino 未定位 | Rhino 实机缺 | QuickJS 实机有 | QuickJS 源码有 | QuickJS 待探针 | QuickJS 未定位 | QuickJS 实机缺 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1.2 如何阅读本文档 | 2 | 2 | 2 | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 |
| 2.1 自动化-控件操作 | 117 | 106 | 19 | 83 | 5 | 10 | 0 | 16 | 0 | 97 | 1 | 0 |
| 2.2 自动化 - 坐标操作 | 32 | 30 | 11 | 0 | 18 | 3 | 0 | 11 | 0 | 12 | 2 | 0 |
| 2.3 app - 应用 | 25 | 21 | 24 | 0 | 1 | 0 | 0 | 17 | 0 | 0 | 0 | 0 |
| 2.4 base64 | 2 | 2 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 2 | 0 |
| 2.5 colors - 颜色 | 19 | 8 | 18 | 1 | 0 | 0 | 0 | 7 | 1 | 0 | 0 | 0 |
| 2.6 canvas - 画布 | 30 | 29 | 0 | 29 | 0 | 1 | 0 | 0 | 0 | 0 | 1 | 29 |
| 2.7 console - 控制台 | 18 | 18 | 18 | 0 | 0 | 0 | 0 | 11 | 0 | 0 | 0 | 0 |
| 2.8 crypto - 加解密与消息摘要 | 12 | 6 | 1 | 0 | 1 | 10 | 0 | 0 | 1 | 3 | 8 | 0 |
| 2.9 debug - 调试工具 | 5 | 5 | 0 | 1 | 0 | 4 | 0 | 0 | 0 | 0 | 5 | 0 |
| 2.10 device - 设备 | 46 | 28 | 45 | 0 | 1 | 0 | 0 | 18 | 0 | 0 | 0 | 0 |
| 2.11 dialogs - 对话框 | 26 | 13 | 10 | 0 | 12 | 4 | 0 | 8 | 0 | 4 | 1 | 0 |
| 2.12 engines - 脚本引擎 | 21 | 15 | 7 | 4 | 6 | 4 | 0 | 6 | 0 | 6 | 4 | 0 |
| 2.13 events - 事件与监听 | 18 | 14 | 2 | 14 | 1 | 1 | 0 | 1 | 0 | 14 | 1 | 0 |
| 2.14 floaty - 悬浮窗 | 25 | 23 | 3 | 18 | 2 | 2 | 0 | 3 | 9 | 7 | 2 | 0 |
| 2.15 files - 文件系统 | 40 | 37 | 27 | 5 | 2 | 6 | 0 | 18 | 0 | 9 | 3 | 0 |
| 2.16 globals - 全局变量与函数 | 16 | 15 | 16 | 0 | 0 | 0 | 0 | 13 | 1 | 2 | 0 | 0 |
| 2.17 http - HTTP 网络请求 | 13 | 5 | 6 | 0 | 7 | 0 | 0 | 4 | 0 | 7 | 1 | 0 |
| 2.18 images - 图片处理 | 76 | 64 | 39 | 13 | 9 | 15 | 0 | 38 | 1 | 31 | 4 | 0 |
| 2.19 keys - 按键模拟 | 21 | 21 | 21 | 0 | 0 | 0 | 0 | 21 | 0 | 0 | 0 | 0 |
| 2.20 media - 多媒体 | 9 | 9 | 9 | 0 | 0 | 0 | 0 | 9 | 0 | 0 | 0 | 0 |
| 2.22 ocr - 文字识别 | 12 | 4 | 0 | 0 | 4 | 8 | 0 | 0 | 0 | 9 | 3 | 0 |
| 2.23 plugins - 插件 | 4 | 1 | 1 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 4 | 0 |
| 2.24 power_manager - 电源管理 | 4 | 2 | 0 | 0 | 1 | 3 | 0 | 0 | 0 | 0 | 4 | 0 |
| 2.25 sensors - 传感器 | 8 | 3 | 4 | 0 | 3 | 1 | 0 | 4 | 0 | 0 | 1 | 0 |
| 2.26 shell - Shell 命令 | 6 | 6 | 2 | 4 | 0 | 0 | 0 | 2 | 0 | 4 | 0 | 0 |
| 2.27 storages - 本地存储 | 8 | 7 | 2 | 5 | 0 | 1 | 0 | 2 | 0 | 5 | 1 | 0 |
| 2.28 settings - 设置 | 2 | 2 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 2 | 0 |
| 2.29 threads - 多线程 | 20 | 17 | 8 | 6 | 5 | 1 | 0 | 5 | 0 | 8 | 2 | 0 |
| 2.30 thirdPartyPlugins - 第三方插件 | 40 | 23 | 0 | 0 | 2 | 37 | 1 | 0 | 0 | 30 | 8 | 2 |
| 2.31 timers - 定时器 | 6 | 6 | 6 | 0 | 0 | 0 | 0 | 6 | 0 | 0 | 0 | 0 |
| 2.32 work_manager - 定时与任务 | 21 | 10 | 9 | 0 | 7 | 5 | 0 | 0 | 0 | 7 | 14 | 0 |
| 2.33 util - 工具 | 23 | 20 | 22 | 0 | 0 | 1 | 0 | 0 | 15 | 5 | 3 | 0 |
| 2.34 WebSocket | 6 | 5 | 1 | 0 | 5 | 0 | 0 | 0 | 0 | 0 | 2 | 4 |
| 2.35 zip - 压缩与解压 | 17 | 17 | 0 | 1 | 3 | 13 | 0 | 0 | 0 | 12 | 5 | 0 |
| 2.36 Ui - 用户界面 | 188 | 30 | 0 | 25 | 59 | 103 | 0 | 0 | 9 | 6 | 167 | 0 |
| **合计** | **938** | **624** | **333** | **209** | **156** | **238** | **1** | **222** | **37** | **278** | **251** | **35** |

（`2.36` 的 188 条绝大多数是 XML 布局属性与控件属性标签，不是 JS 函数；`2.30` 是插件 SDK 类。这两块与普通模块不可直接比较。）

**读法**：`实机有 + 源码有 + 待核实` 是"有证据"的合计 —— Rhino **698/938（74%）**，QuickJS **537/938（57%）**；剩下的是未定位与待探针。

## 5. 真机探针查出来的事实（小米 8，2026-09-15）

### 5.1 模块挂载差异

| 类别 | 内容 |
| --- | --- |
| **两边都有（25）** | `$ui`、`app`(+`app.autojs`)、`auto`、`automator`、`colors`、`console`、`device`、`dialogs`、`engines`、`events`、`files`、`floaty`、`http`、`images`、`media`、`runtime`、`sensors`、`storages`、`threads`、`Image`/`RootAutomator`/`Shell`（类名） |
| **只有 Rhino（9）** | `$crypto`、`$images`、`$plugins`、`$util`、`$util.java`、`$web`、`$work_manager`、`Response`、`WebSocket` |
| **只有 QuickJS（1）** | `events.bus` |
| **两边都缺（44）** | `$base64`、`$debug`、`$ocr`、`$power_manager`、`$settings`、`$zip`、`canvas`、`dialog`、`ui.console`、`globalconsole`、`$ui.imageCache`，以及类型名（`Rect`/`Point`/`Key`/`KeyPair`/`UiObject`/`UiSelector`/`ZipFile`/`OCR`/`Tensor`…）与 2.30 的插件类 |

其中**类型名不是缺口**（它们由 API 返回值给出，本来就不是全局）；真正的模块缺口是 `$base64`、`$debug`、`$ocr`、`$power_manager`、`$settings`、`$zip`（**两引擎都没有**）与 `$crypto`/`$util`/`$web`/`$work_manager`/`$plugins`/`$images`（**只有 Rhino 有**）。

### 5.2 两个反直觉的发现

1. **`"ui";` 模式与普通模式没有任何差异** —— 同一批 45 个（Rhino）/ 53 个（QuickJS）名字两边都缺，说明这些模块不是"没进 UI 上下文"，而是**真的没挂**。
2. **静态扫描会把 `canvas` 判成"已实现"**：它确实实现了（`com.stardust.autojs.core.graphics.ScriptCanvas`，示例 `Rhino 引擎/画布/贪吃蛇.js` 就在用），但它不是全局模块，而是 `<canvas>` 回调里传进来的对象。只信静态代码搜索会把这类名字误判成"兼容"。

### 5.3 全局函数

探针另外检查了 207 个"不带点"的名字：Rhino 有 70 个存在、QuickJS 有 67 个存在，**137 个两边都缺** —— 但这里面绝大多数是文档里写成 `xxx()` 的**类成员**（`isChecked`、`attr`、`getPath`…），不是全局函数。这也是为什么 `实机缺` 只对「本来就是模块 / 全局章节（2.16）里的函数」生效。

## 6. 结论

1. **Rhino 侧是当前最贴近第一代 API 的引擎**：938 条里 74% 有证据（333 条被真机直接确认），未定位 238 条里扣掉类型名与 2.36 的 UI 属性后，真缺的可执行 API 只有 **56 条**（详见 §7）。
2. **QuickJS 侧的缺口是"整模块缺"，不是"零散缺函数"**：`$zip`(5)、`$work_manager`(10)、`$crypto`(5)、`$debug`(5)、`$settings`(2)、`$power_manager`(2)、`$ocr`(1)、`$plugins`(1)、`$util.inspect`、`$web.newWebSocket`、`$images.*`(4) 全部未定位，`canvas.*` 整块 29 条判 `实机缺`。这与仓库既有的引擎结论一致。
3. **两引擎共有的真缺口**（最值得排期）：
   - `$zip`：Rhino 13 条 + QuickJS 5 条未定位，加密压缩（`ZipFile.setPassword/isEncrypted/extractAll(options)`）没有实现；
   - `$crypto`：`encrypt`/`decrypt`/`generateKeyPair` 与 `Key`/`KeyPair`（现在只有摘要/HMAC/base64）；
   - `$debug`：`dumpHprof`/`dumpAndSendHprof`/`gc`/`setMemoryLeakDetectionEnabled`；
   - `$base64`、`$power_manager`：条目少但整块缺；
   - `$work_manager.addIntentTask`；
   - `$images.getScreenCaptureOptions/stopScreenCapture/detectAndComputeFeatures/matchFeatures`。
4. **`2.36 Ui` 是最大的"看不清"块**（188 条，Rhino 仅 25 条有源码证据）：XML 属性、`<web>`/`<console>` 内置控件、控件代理方法用文本搜索与成员枚举都确认不了，需要按 XML 属性表单独建表核对。

## 7. 该从哪些行开始

CSV 里筛 `rhino = 未定位` 且 `kind in (function, constructor)`、排除 `2.30` / `2.36`，剩 **56 行**：

| 模块 | 条数 | 例子 |
| --- | --- | --- |
| 2.35 zip | 13 | `$zip.zipDir/zipFiles/open`、`ZipFile.setPassword/isEncrypted/addFile/extractAll` |
| 2.1 控件操作 | 9 | `UiObject.clickCenter`、`UiCollection.size/each/empty/nonEmpty/find/findOne`、`auto.clearCache` |
| 2.18 images | 7 | `$images.getScreenCaptureOptions/stopScreenCapture/detectAndComputeFeatures`、`ColorMapping.*` |
| 2.8 crypto | 5 | `$crypto.encrypt/decrypt/generateKeyPair`、`new Key`、`new KeyPair` |
| 2.9 debug | 4 | `$debug.dumpHprof`、`dumpAndSendHprof`、`setMemoryLeakDetectionEnabled`、`gc` |
| 2.15 files | 4 | `ReadableTextFile.read/readline/readlines`（文本文件对象成员） |
| 2.11 dialogs | 3 | `dialog.setDefaultDialogType`、`dialog.getProgress/getMaxProgress` |
| 2.2 坐标操作 | 2 | `new RootAutomator2`、`RootAutomator2.longPress` |
| 2.4 base64 | 2 | `$base64.encode/decode` |
| 2.22 ocr | 2 | `OCR.detect`、`OCRResult.clickCenter` |
| 2.24 power_manager | 2 | `$power_manager.isIgnoringBatteryOptimizations/requestIgnoreBatteryOptimizations` |
| 2.12 / 2.29 / 2.32 | 各 1 | `getPath`、`isAlive`、`$work_manager.addIntentTask` |

QuickJS 侧另有 **43 行**未定位函数（同样排除 2.30/2.36），前几位：`$work_manager.*`(10)、`$crypto`(5)、`$debug`(5)、`$zip`(5)、`$images`(4)。

## 8. 怎么复现

```powershell
# 1) 依赖
& '.venv\Scripts\python.exe' -m pip install pdfminer.six

# 2) PDF -> 清单 -> 静态对照 -> 汇总（可分开跑 --stage extract|index|check|rollup）
& '.venv\Scripts\python.exe' 'tools\pro-api\rebuild.py' --pdf '<PDF 路径>'

# 3) 真机探针（需连一台设备；下面 4 条对每个变体各跑一次）
& '.venv\Scripts\python.exe' 'tools\pro-api\gen-device-probe.py'
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-probe.py' '.artifacts\probe-rhino.log' rhino
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-probe.py' '.artifacts\probe-qjs.log' quickjs

# 4) 合并实机结论（把 .artifacts/pro-api-device-*.json 生成后再跑一次 check+rollup）
& '.venv\Scripts\python.exe' 'tools\pro-api\rebuild.py' --stage check --stage rollup
```

详细规则与调试方法见 [`tools/pro-api/README.md`](../../tools/pro-api/README.md)。

## 9. 这份清单不能证明什么

- **不是行为验收**：`实机有` 只说明名字与成员在。默认值、异常、回调线程、权限取消、资源释放是否与 Pro 一致，仍要按 [Auto.jsPro_API兼容性与V8选型](Auto.jsPro_API兼容性与V8选型.md) §7 的实机场景逐项验证。
- **静态检索一定有漏**：动态挂载（`Proxy`、`Object.defineProperty`、反射注册）扫不到；`未定位` 只代表"三层证据都没找到"，不等于"没实现"。
- **探针在单机单版本上跑**：本轮只覆盖小米 8 + 当时的构建；换设备/换版本要重跑（脚本已固化，一分钟内可复现）。
- **没有 Pro 对照设备**：`version` 列只说明"这个条目是哪个版本加的"，不代表当前实现对齐到了同一版本。

## 10. 下一步

1. **按 §7 清账**：56 条 Rhino 未定位逐条定性 —— 是"漏扫"就补规则，是"真缺"就转开发任务，并把结论写回 CSV 的 `note`。
2. **`$zip` / `$crypto` 优先**：两引擎都缺、且在真实脚本里出现频率高。
3. **2.36 Ui 单独建表**：XML 属性与控件代理方法要和 JS 函数分开核对。
4. **补 Pro 侧对照**（可选）：装一份 Pro 9.x，用同一套探针跑一遍，就能把"我们缺"与"Pro 有但我们不同"区分开。
5. **把 CSV 当验收登记册**：验收一条改一条状态，禁止直接写"完全兼容"。

## 11. 依据

- [Auto.js Pro 官方 API 文档（335 页）](C:/Users/18101/Downloads/Auto.js%20Pro官方API文档.pdf)：本清单的唯一来源，页码与 PDF 实际页号一致。
- [第一代 API 兼容性与 V8 选型](Auto.jsPro_API兼容性与V8选型.md)：36 模块定位、语义缺口、验收场景。
- [第一代 API 兼容现状（2026-09-15 报告）](../reports/2026-09-15_第一代API兼容现状.md)：本次对照之前的人工结论。
- 生成脚本与中间产物：`tools/pro-api/`、`.artifacts/pro-api-*.json`、`.artifacts/probe-*.log`。
