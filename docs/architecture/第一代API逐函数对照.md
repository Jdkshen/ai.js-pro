# 第一代 Pro API 逐函数对照（Auto.js Pro 官方 API 文档 PDF）

对照日期：2026-09-15（§4 / §5.4~§5.8 / §6 / §7 为 `$crypto`、`$zip`、`$debug`、`$power_manager`、`floaty` 权限、`$settings` 补齐后重跑）。对象：`C:\Users\18101\Downloads\Auto.js Pro官方API文档.pdf`（335 页，第一代 / Rhino 默认引擎）。
真机：**小米 8（`ce4d2bdb`，Android 15）**，两个引擎各跑「普通脚本」与 `"ui";` 脚本。

交付四层证据：**排版解析 → 静态源码对照 → 真机成员枚举 → 真机逐条路径取值**。
它仍然**不是行为验收**：`实机有` 只说明这个名字在设备上取得到、成员在，不代表默认值/异常/回调线程/权限与 Pro 一致。

## 1. 交付物

| 文件 | 内容 |
| --- | --- |
| [`docs/architecture/pro-api-compatibility.csv`](pro-api-compatibility.csv) | **主交付物**。938 行逐条对照，UTF-8 BOM |
| `tools/pro-api/rebuild.py` | 生成 CSV 的脚本（提取 → 建索引 → 对照 → 汇总），会自动并入真机结果 |
| `tools/pro-api/gen-device-probe.py` | 生成探针：成员枚举 4 变体 + **逐条路径取值 3 变体** |
| `tools/pro-api/parse-device-probe.py` / `parse-device-paths.py` | 解析探针日志 |
| `tools/pro-api/run-device-probes.ps1` | 一键跑完 7 个真机探针（4 变体成员枚举 + 3 变体路径取值）并重算对照 |
| `tools/pro-api/crypto-contract.js` | 2.8 `$crypto` 行为验收脚本（含文档黄金向量，§5.4） |
| `tools/pro-api/zip-contract.js` | 2.35 `$zip` 行为验收脚本（28 项，§5.5） |
| `tools/pro-api/debug-contract.js` | 2.9 `$debug` 行为验收脚本（18 项，§5.6） |
| `tools/pro-api/power-manager-contract.js` / `floaty-permission-contract.js` | 2.24 `$power_manager` / 2.14 `floaty` 权限（§5.7） |
| `tools/pro-api/settings-contract.js` | 2.28 `$settings` 行为验收脚本（21 项，§5.8） |
| `tools/pro-api/dialog-contract.js` / `colormapping-contract.js` | 2.11 `dialog.*` / 2.18.8 `ColorMapping`（§5.9） |
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
结果：**Rhino 395 / 818 取得到，QuickJS 230 / 818 取得到**。

## 3. 状态词表

| 状态 | 来源 | 含义 |
| --- | --- | --- |
| `实机有` | 路径取值 / 成员枚举 | 设备上取到了这个名字（优先用路径取值） |
| `实机缺` | 路径取值 + 静态 | 设备上取不到，**且静态也没找到实现**；只对模块成员生效（类型名/实例方法不判缺） |
| `实机未见` | 成员枚举 | 模块在，但成员表里没有它（可能是实例成员或非枚举属性） |
| `已定义` / `已定义(全局)` | 静态 | 源码里有定义形状 |
| `已定义(改名)` | 静态 | 实现名与文档不同（见 `RENAME_HINTS`），证据里写明实现名 |
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
| 2.8 crypto - 加解密与消息摘要 | 12 | 6 | 8 | 3 | 0 | 1 | 0 | 0 | 1 | 3 | 8 | 0 |
| 2.9 debug - 调试工具 | 5 | 5 | 5 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 5 | 0 |
| 2.10 device - 设备 | 46 | 28 | 45 | 0 | 0 | 0 | 1 | 18 | 0 | 0 | 0 | 28 |
| 2.11 dialogs - 对话框 | 26 | 13 | 10 | 5 | 10 | 1 | 0 | 8 | 0 | 0 | 1 | 5 |
| 2.12 engines - 脚本引擎 | 21 | 15 | 7 | 4 | 5 | 4 | 1 | 6 | 0 | 6 | 4 | 2 |
| 2.13 events - 事件与监听 | 18 | 14 | 2 | 14 | 1 | 1 | 0 | 1 | 0 | 14 | 1 | 1 |
| 2.14 floaty - 悬浮窗 | 25 | 23 | 5 | 18 | 0 | 2 | 0 | 3 | 9 | 7 | 2 | 2 |
| 2.15 files - 文件系统 | 40 | 37 | 27 | 9 | 2 | 2 | 0 | 18 | 0 | 0 | 3 | 18 |
| 2.16 globals - 全局变量与函数 | 16 | 15 | 16 | 0 | 0 | 0 | 0 | 13 | 1 | 2 | 0 | 0 |
| 2.17 http - HTTP 网络请求 | 13 | 5 | 13 | 0 | 0 | 0 | 0 | 4 | 0 | 7 | 1 | 1 |
| 2.18 images - 图片处理 | 76 | 64 | 49 | 9 | 6 | 10 | 2 | 42 | 1 | 27 | 4 | 2 |
| 2.19 keys - 按键模拟 | 21 | 21 | 21 | 0 | 0 | 0 | 0 | 21 | 0 | 0 | 0 | 0 |
| 2.20 media - 多媒体 | 9 | 9 | 9 | 0 | 0 | 0 | 0 | 9 | 0 | 0 | 0 | 0 |
| 2.22 ocr - 文字识别 | 12 | 4 | 0 | 0 | 4 | 8 | 0 | 0 | 0 | 9 | 3 | 0 |
| 2.23 plugins - 插件 | 4 | 1 | 1 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 4 | 0 |
| 2.24 power_manager - 电源管理 | 4 | 2 | 2 | 0 | 1 | 1 | 0 | 0 | 0 | 0 | 4 | 0 |
| 2.25 sensors - 传感器 | 8 | 3 | 4 | 0 | 3 | 1 | 0 | 4 | 0 | 0 | 1 | 0 |
| 2.26 shell - Shell 命令 | 6 | 6 | 6 | 0 | 0 | 0 | 0 | 6 | 0 | 0 | 0 | 0 |
| 2.27 storages - 本地存储 | 8 | 7 | 2 | 5 | 0 | 1 | 0 | 2 | 0 | 5 | 1 | 0 |
| 2.28 settings - 设置 | 2 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 |
| 2.29 threads - 多线程 | 20 | 17 | 8 | 6 | 5 | 1 | 0 | 5 | 0 | 8 | 2 | 3 |
| 2.30 thirdPartyPlugins - 第三方插件 | 40 | 23 | 0 | 0 | 2 | 37 | 1 | 0 | 0 | 30 | 8 | 2 |
| 2.31 timers - 定时器 | 6 | 6 | 6 | 0 | 0 | 0 | 0 | 6 | 0 | 0 | 0 | 0 |
| 2.32 work_manager - 定时与任务 | 21 | 10 | 9 | 1 | 7 | 4 | 0 | 0 | 0 | 7 | 14 | 0 |
| 2.33 util - 工具 | 23 | 20 | 22 | 0 | 0 | 1 | 0 | 0 | 15 | 5 | 3 | 0 |
| 2.34 WebSocket | 6 | 5 | 5 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 2 | 4 |
| 2.35 zip - 压缩与解压 | 17 | 17 | 17 | 0 | 0 | 0 | 0 | 0 | 0 | 12 | 5 | 0 |
| 2.36 Ui - 用户界面 | 188 | 30 | 25 | 2 | 145 | 12 | 3 | 16 | 6 | 6 | 11 | 4 |
| **合计** | **938** | **624** | **419** | **197** | **212** | **98** | **11** | **247** | **33** | **250** | **95** | **137** |

**读法**：`实机有 + 源码有 + 待核实` = 有证据合计 → Rhino **828/938（88%）**，QuickJS **530/938（56%）**。

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

- **Rhino 11 条**：`automator.takeScreenshot/switchToInputMethod/headsetHook`、`images.getSimilarity/detectsMultiColors`、`engines.startFloatingController`、`app.getInstalledApps`（命名差异，实现叫 `getInstalledPackageList`）、`console.getInput/setColor/setTextSize`（2.36 的 `<console>` 控件属性）、`device.broad`（文档残片）、`ocr.filterScore`。
  （`floaty.checkPermission/requestPermission` 原本也在这张表里，已在 §5.7 补齐。）
- **QuickJS 133 条**，与仓库既有结论完全吻合：`canvas.*` 29、`device.*` 28、`files.*` 18、`colors` 常量 11、`app.*` 8、`console.*` 7、`WebSocket.*` 4、`threads.disposable/atomic/lock` 3 等。

### 5.4 已按清单补齐：2.8 `$crypto`（本轮，行为级验收）

清单里 2.8 的 4 条（`$crypto.encrypt` / `$crypto.decrypt` / `$crypto.generateKeyPair` / `Key`+`KeyPair`）已经实现并在真机验收：

| 项 | 位置 |
| --- | --- |
| Java 实现 | `modules/engine/src/main/java/com/stardust/autojs/runtime/api/Crypto.java`（`Crypto.encrypt/decrypt/__generateKeyPair` + Key/KeyPair 挂载字段） |
| JS 形状包装 | `modules/engine/src/main/assets/init.js`（`Key` 带 `data/algorithm/format`，`KeyPair` 带 `publicKey/privateKey`） |
| 验收脚本 | `tools/pro-api/crypto-contract.js`（12 项，含文档第 74~75 页的黄金向量） |

黄金向量（文档原样）：`"Hello Autojs"` + `new $crypto.Key("a"×16)` + `"AES"` → `7hu7UQKpjBeOqpEoOoHjxQ==`（字节形式 `-18,27,-69,81,2,-87,-116,23,-114,-86,-111,40,58,-127,-29,-59`）—— 真机 **逐字节命中**，说明默认补齐方式（`AES` → `AES/ECB/PKCS5Padding`、`RSA` → `RSA/ECB/PKCS1Padding`）与 Pro 一致。

实现过程中撞到 3 个 Rhino / Android 坑（已修，后来者别再踩）：

1. **不能给 Java 对象动态加属性**：`crypto.Key = Key` 报 `Java class ... has no public instance field or method named "Key"` → 现在在 `Crypto` 上先声明 `public Object Key/KeyPair/generateKeyPair` 字段，JS 才写得进去。
2. **Rhino 传进来的 Java 对象是 `NativeJavaObject` 包装**：`pair.getPublic()` 不能直接 `instanceof java.security.Key`，必须先 `Wrapper.unwrap()`；否则 RSA 密钥重建会失败。
3. **设备上 AndroidKeyStore 也注册了 RSA/EC 的 `KeyFactory`**，吃不下 X509/PKCS8 规范（报「To generate a key pair in Android Keystore…」）→ 选 provider 时必须跳过它。

验收后重跑四层证据： 2.8 的 `实机有` 由 1 → 8，整份清单 Rhino `未定位` 132 → 124。

### 5.5 已按清单补齐：2.35 `$zip`（本轮，行为级验收）

清单里 2.35 的 **17 条全部实现并在真机验收**（`tools/pro-api/zip-contract.js`，28 项断言全过）。

关键事实：文档第 257 页写着「**zip 功能来自 zip4j**」——所以对齐方式不是手搭 `java.util.zip`，而是直接包 zip4j（`modules/engine/build.gradle` 本来就依赖 `net.lingala.zip4j:zip4j:1.3.2`），选项名/取值与 zip4j 一一对应。

| 项 | 位置 |
| --- | --- |
| 与文档同名的压缩接口 | `Zips.java`：`zipDir/zipFile/zipFiles/unzip/open` |
| `ZipFile` 对象 | 新建 `runtime/api/ZipFile.java`（`getPath/isValidZipFile/setPassword/isEncrypted/addFile(s)/addFolder/removeFile/extractFile/extractAll/getFileHeader(s)`） |
| 选项翻译 | 新建 `runtime/api/ZipSupport.java`（`password`/`compressionMethod`/`encryptionMethod`/`compressionLevel`/`aesKeyStrength`/`readHiddenFiles`/`includeRootFolder`/`rootFolderInZip`） |
| 脚本全局名 | `init.js`：`global.$zip`（文档叫 `$zip`，仓库内部叫 `zips`）+ `importClass(...api.ZipFile)` |

对齐过程中确认/踩到的点：

1. **`includeRootFolder` 默认 true**（文档与 zip4j 一致）——`$zip.zipDir(dir, dest)` 的条目名带父目录前缀（`zip-demo/a.txt`）；要平铺得显式传 `{includeRootFolder: false}`。验收脚本两种都断言到了。
2. **只给 `password` 不给强度时 zip4j 会直接报错**（`invalid AES key strength`）：文档说「设置密码但没设置加密方法→默认 AES」，没说强度，按文档示例取 `AES_STRENGTH_256`。
3. **zip4j 1.3.2 的 ProgressMonitor 状态绑在 ZipFile 实例上**，同一个实例连着做多个操作会报 `invalid operation - Zip4j is in busy state` → 包装类里每次操作新建实例（密码自己记着）。
4. **Rhino 按参数个数精确匹配重载**：只写 `zipDir(dir, dest, options)` 会报 `Can't find method ... zipDir(string,string)` → 可选参数的 API 必须补一份少参数的 overload。
5. `$files.create` 建的是**文件**（不是目录），`$files.ensureDir` 的返回值在设备上不靠谱（`exists` 立刻查不到）→ 脚本里用 `$files.createWithDirs`。

验收后重跑四层证据： 2.35 的 `实机有` 由 0 → 17（路径取值 17 条全命中）。

### 5.6 已按清单补齐：2.9 `$debug`（本轮，行为级验收）

清单里 2.9 的 **5 条全部实现并在真机验收**（`tools/pro-api/debug-contract.js`，18 项断言全过）。

| 项 | 位置 |
| --- | --- |
| `$debug` 模块 | 新建 `runtime/api/Debug.java`（`dumpHprof`/`dumpAndSendHprof`/`getStackTrace`/`setMemoryLeakDetectionEnabled`/`gc`） |
| 内存泄露检测 | 新建 `runtime/api/MemoryLeakDetector.java`（弱引用 + `ReferenceQueue`：对象被 GC 时还没 `recycle()` 就报一条日志） |
| 登记钩子 | `core/image/ImageWrapper.java`：实例初始化块 `watch(this, "Image")`，`recycle()` 里 `markRecycled(this)`（未开启检测时是空操作） |
| 脚本全局名 | `init.js`：`global.$debug = runtime.debug` |

关键事实与设计决定：

- `dumpHprof` 直接用 `android.os.Debug.dumpHprofData(path)`；`dumpAndSendHprof` 是先 dump 到 `<目标>.tmp.hprof`，再用 `Deflater.BEST_COMPRESSION`（文档：「使用压缩程度最高的压缩等级」）压成 zip，最后删临时文件。
- **默认值与文档一致**：文档说「在 Auto.js Pro 运行时默认开启，在打包软件中默认关闭」；打包产物是 inrt 模板应用（包名/类名里带 `inrt`），据此判断（`Debug.isPackagedApp`）。
- `getStackTrace` 同时支持 **JS 异常**（取 `name/message/stack`）与 **Java 异常**（`printStackTrace` 到 StringWriter），真机两类都断言到了。
- 文档没写、我们额外加的验收辅助接口：`$debug.isMemoryLeakDetectionEnabled()` / `$debug.getMemoryLeakCount()`（否则脚本无法自证泄露检测是否生效）。
- 真机实测：20 张故意不 `recycle()` 的图片全部报出 `[MemoryLeak] Image 对象没有调用 recycle() …`，20 张正常 `recycle()` 的**一张都没报**。
- ⚠️ 验证注意：ART 的 `System.gc()` 只是「建议」，小对象往往要等内存压力才真被回收（验收脚本里先分配一块大数组逼一次 GC 再轮询，不然计数一直是 0）。

### 5.7 已按清单补齐：2.24 `$power_manager` + 2.14 `floaty` 权限（本轮）

| 模块 | 条数 | 实现 | 验收 |
| --- | --- | --- | --- |
| 2.24 `$power_manager` | 2 | 新建 `runtime/api/PowerManager.java`（`isIgnoringBatteryOptimizations([pkg])` / `requestIgnoreBatteryOptimizations(forceRequest, pkg)`），`init.js` 暴露 `global.$power_manager` | `power-manager-contract.js`：5/5 |
| 2.14 `floaty` 权限 | 2 | `Floaty.java` 新增 `checkPermission/requestPermission`；**脚本里的 `floaty` 是 `__floaty__.js` 这个 JS 模块**，所以还要在它上面转发一层 | `floaty-permission-contract.js`：4/4 |

对齐细节：

- `requestIgnoreBatteryOptimizations` 严格按文档：`forceRequest=false` 且已开启时**不发起请求**；请求用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（系统弹窗、异步、不返回结果），被 ROM 拒绝时回落 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`。
- 真机交叉验证：`$power_manager.isIgnoringBatteryOptimizations()` 与直接调 `android.os.PowerManager` 结果一致（本机为 `false`）；请求后系统确实弹出“忽略电池优化”确认框。
- `floaty.checkPermission()` 与 `android.provider.Settings.canDrawOverlays(context)` 结果一致（本机为 `true`）；`requestPermission()` 后 `dumpsys window` 的 `mCurrentFocus` 变成 `com.android.settings/...SpaActivity`（证明真的跳到了系统页）。
- ⚠️ **踩到的坑**：脚本作用域里的 `floaty` / `$floaty` 并不是 Java 的 `runtime.floaty`，而是 `assets/modules/__floaty__.js` 返回的 JS 对象（只手动挂了 `window`/`rawWindow`/`closeAll`）。新增 API 必须同时改 **Java 类 + `__floaty__.js`**，否则 `Cannot find function checkPermission in object [object Object]`。

### 5.8 已按清单补齐：2.28 `$settings`（本轮，行为级验收）

清单里 2.28 的 **2 条**（`$settings.setEnabled` / `isEnabled`）已实现并在真机验收（`settings-contract.js`：21/21）。

这个模块与前面几个不同：**它必须由 App 侧提供实现**（引擎拿不到应用偏好），所以走仓库已有的注入约定
（`runtime.putProperty("settings", …)` + `init.js` 的 `injected` 列表 → 全局 `$settings`）。

| 端 | 实现 |
| --- | --- |
| 引擎 | `init.js`：`injected` 列表加上 `'settings'`（不依赖 App 具体类） |
| 主应用 | 新建 `app/…/autojs/api/SettingsModule.java`，`AutoJs.createRuntime()` 里 `putProperty`；`Pref` 补齐几个 setter |
| 打包版 | 新建 `inrt/…/autojs/api/SettingsModule.kt`，同样 `putProperty` |

key 映射（文档 3 个 + 仓库现有设置项里能对上的 2 个，样例脚本在用）：

| 文档 key | 主应用 | 打包版 |
| --- | --- | --- |
| `stop_all_on_volume_up` | `key_use_volume_control_running`（默认 **关**；Pro 文档默认**开** —— 差异见 §9） | 同左（打包版默认**开**） |
| `not_show_console` | 新键 `not_show_console`（默认关） | `key_dont_show_main_activity` |
| `foreground_service` | `Pref.isForegroundServiceEnabled()`（`ForegroundService` 真的读它） | — |
| `stable_mode`（样例在用） | `key_stable_mode` | — |
| `enable_accessibility_service_by_root`（样例在用） | 同名键 | 同名键 |

要点：

- 未知 key **明确报错**（`未知的设置项: xxx`），不静默返回 `false`——静默会让脚本以为设置生效了。
- 真机验证方式是「读 → 取反 → 读 → 还原 → 读」的往返，跑完不改设备状态；并与 App 的 `Pref` 类交叉对比。
- 样例 `apps/app/src/main/assets/sample/Rhino 引擎/设置/设置.js` 原来写着“当前引擎暂未实现”，现已能直接跑（头注释已更新）。

### 5.9 已按清单补齐：2.11 `dialog.*` + 2.18.8 `ColorMapping`（本轮）

| 条目 | 结论 | 验证 |
| --- | --- | --- |
| `dialog.getProgress` | **扫描器漏报**：实现在 `JsDialog.getProgress()/getCurrentProgress()`，但 PDF 写的是 `dialog.` 前缀，扫描器过去把 `dialog` 当成了模块名，找不到对应源文件 | `dialog-contract.js`：读到 0/100 |
| `dialog.getMaxProgress` | 同上（`JsDialog.getMaxProgress()` 已存在，无需实现） | 同上 |
| `dialog.setDefaultDialogType` | **新实现**：`Dialogs.setDefaultDialogType/getDefaultDialogType` + Context 解析；`dialogs.build({type: …})` 可覆盖 | 4 个合法值 + 非法值报错 |
| `ColorMapping.singleton/reset/recycle/findColor/findMultiColors/findAllPointsForColor` | **新实现**：新建 `core/image/ColorMapping.java`（缓存像素数组，后续找色不再走 JNI），`$colors.mapping` 暴露成可 `new` 的类 | `colormapping-contract.js`：22 项 |

要点/踩坑：

- **`dialog.*` 是对象方法，不是模块**：`dialogs.build()` 返回的 `JsDialog` 上带这些方法。扫描器新增 `'dialog' → ['Dialogs','JsDialog','JsDialogBuilder']` 映射后，这三条全部归位。
- **对话框类型本来就有两种模式**：`BlockedMaterialDialog` 在拿不到 Activity 时会把窗口设成 `TYPE_APPLICATION_OVERLAY`（悬浮窗），拿到 Activity 就是应用内——即仓库原行为 = `app-or-overlay`。新代码就是把这四种类型变成可选，并把「没权限/没界面」变成明确报错（文档语义）。
  ⚠️ 文档写的默认值是 `overlay`（兼容旧版），我们保留 `app-or-overlay` 作为默认（否则所有现有脚本的对话框都会从应用内变成悬浮层）→ 差异记在 §9。
- **`$colors` 这个别名以前不存在**（文档 2.18.8 在用）→ 已补进 `init.js` 的别名表；`$colors.mapping` 需要是**能 new 的类对象**，而给 Java 对象加属性会被 Rhino 拒绝、Java 字段里存 `Class` 又只会被包成不可 new 的对象 → 最后用一层 JS 包装（`Object.create`）只给 `$colors` 加 `mapping`，其余成员走原型链照旧。
- `ColorMapping` 返回的是**图片坐标**（不跟 `images.findColor` 一样做屏幕缩放），因为缓存是按图片建的；`similarity` 与 `threshold` 二选一、同时存在以 `similarity` 为准（文档原文）。
- 真机上 `Bitmap.eraseColor()` **不生效**（未涂色处是白的）→ 验收脚本改成逐像素填背景，并加了一条背景断言。

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

### 6.2 真缺 —— 两个引擎都没有（约 18 条）

⚠️ 2.8 `$crypto`、2.35 `$zip`、2.9 `$debug`、2.24 `$power_manager`、2.14 `floaty` 权限、2.28 `$settings`、2.11 `dialog.*`、2.18.8 `ColorMapping` 已分别在 §5.4~§5.9 补齐 / 归位并真机验收，不再属于这一节。
剩余大头：
`ColorMapping.*`（4）、`RootAutomator2` 与 `RootAutomator2.longPress`、
`OCR.detect`/`OCRResult.clickCenter`/`$ocr.create`、
`$images.getScreenCaptureOptions/stopScreenCapture/detectAndComputeFeatures/matchFeatures`、
`images.getSimilarity/detectsMultiColors`、
`automator.takeScreenshot/switchToInputMethod/headsetHook`、`engines.startFloatingController`、
`UiObject.clickCenter`、`dialog.setDefaultDialogType/getProgress/getMaxProgress`、`isAlive`、`getPath`。

### 6.3 类型名 —— 不是缺口

`Rect`、`Key`、`KeyPair`、`OCR`、`OCRResult`、`TimedTask`、`EventEmitter`、`Storages`、`ScriptEngine`、`ScriptExecution`、`Dialog`、`FloatyWindow`、`FloatyRawWindow`、`SensorEventEmitter`、`Bug` 等：文档里是**返回值类型**，本来就不该是全局。已排除在"缺"之外。

## 7. 该从哪些行开始

CSV 里筛 `rhino ∈ {未定位, 实机缺}` 且 `kind ∈ {function, constructor}`、排除 `2.30`/`2.36`，共 **18 条**（未定位 10 + 实机缺 8）：

| 模块 | 未定位 | 实机缺 | 例子 |
| --- | --- | --- | --- |
| 2.1 控件操作 | 3 | 3 | `UiObject.clickCenter`、`auto.clearCache`、`Rect.intersect`、`automator.takeScreenshot/switchToInputMethod/headsetHook` |
| 2.18 images | 3 | 2 | `$images.getScreenCaptureOptions/stopScreenCapture/detectAndComputeFeatures`、`matchFeatures`、`images.getSimilarity/detectsMultiColors` |
| 2.12 engines | 1 | 1 | `engines.startFloatingController`、`getPath` |
| 2.2 坐标操作 / 2.22 ocr / 2.29 threads | 各 1~2 | 0 | `RootAutomator2.longPress`、`OCR.detect`、`isAlive` |
| ~~2.8 crypto / 2.9 debug / 2.11 dialog.* / 2.14 floaty 权限 / 2.18.8 ColorMapping / 2.24 power_manager / 2.28 settings / 2.35 zip~~ | ~~36~~ | 0 | **已补齐（§5.4~§5.9），从清单移出** |

QuickJS 侧同类共 **150 行**（未定位 43 + 实机缺 107），大头是 `canvas.*`(29)、`device.*`(28)、`files.*`(18)、`colors` 常量(11)、`app.*`(8)、`console.*`(7)。

## 8. 怎么复现

```powershell
& '.venv\Scripts\python.exe' -m pip install pdfminer.six

# PDF -> 清单 -> 静态对照（可分开跑 --stage extract|index|check|rollup）
& '.venv\Scripts\python.exe' 'tools\pro-api\rebuild.py' --pdf '<PDF 路径>'

# 真机探针：成员枚举 4 变体 + 路径取值 3 变体（设备上跑 7 次，每次约 20~25 秒）
& '.venv\Scripts\python.exe' 'tools\pro-api\gen-device-probe.py'
# 一键跑完 + 解析 + 重算（设备 id 写在脚本里，默认小米 8 ce4d2bdb）
powershell -NoProfile -ExecutionPolicy Bypass -File 'tools\pro-api\run-device-probes.ps1'
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-probe.py' '.artifacts\probe-rhino.log' rhino
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-paths.py' '.artifacts\probe-paths-rhino.log' '.artifacts\probe-paths-rhino-ui.log' --engine rhino
& '.venv\Scripts\python.exe' 'tools\pro-api\parse-device-paths.py' '.artifacts\probe-paths-qjs.log' --engine quickjs

# 带上真机结论重新对照
& '.venv\Scripts\python.exe' 'tools\pro-api\rebuild.py' --stage check --stage rollup
```

`.artifacts/pro-api-device-*.json` / `pro-api-paths-*.json` 缺失时，`check` 自动退化为纯静态结果。

## 9. 这份清单不能证明什么

- **不是行为验收**：名字在 ≠ 行为一致。默认值、异常、回调线程、权限取消、资源释放仍要按 [AutoJsPro API 兼容性与 V8 选型](Auto.jsPro_API兼容性与V8选型.md) §7 的场景逐项验证。
- **已补齐的模块只验了本轮脚本覆盖到的东西**：`$crypto`/`$zip`/`$debug`/`$power_manager`/`floaty` 权限/`$settings` 都有对应的 `tools/pro-api/*-contract.js`（共 88 项断言），但**默认值差异仍存在**，已知两处：
  - `$settings.isEnabled('stop_all_on_volume_up')`：主应用沿用仓库原有键 `key_use_volume_control_running`，其默认值是**关**，而 Pro 文档写的是默认**开**（打包版默认开，与文档一致）。
  - `$settings.isEnabled('not_show_console')`：主应用没有「脚本启动即弹日志界面」的行为，这个开关只负责存取值。
  - `dialog.setDefaultDialogType` 的**默认值**：文档写 `overlay`，我们保留仓库原有的 `app-or-overlay`（否则现有脚本的对话框会从应用内变成悬浮层）；四种类型本身都支持，也可以随时显式设置。
- **单机单版本**：本轮只覆盖小米 8 + 当时构建，换设备/版本要重跑（脚本已固化）。
- **仍有盲区**：动态挂载（`Proxy`、`Object.defineProperty`、反射注册）与"要满足条件才存在"的成员（实例方法、惰性加载）都测不到；`未定位` 只代表四层证据都没找到。
- **没有 Pro 对照设备**：`version` 列只说明该条目属于哪个版本，不代表我们对齐到了同一版本。

## 10. 下一步

1. **按 §6.1 修扫描规则**：把 `UiObjectCollection`、`PReadableTextFile`/`PWritableTextFile`、`$crypto.base64*`、`addBroadcastIntentTask`、`getInstalledPackageList` 这些"名字对不上"的映射写进 `rebuild.py`，让 13 条漏扫自动归位。
2. **补真缺口**（按性价比）：`UiObject.clickCenter`、`auto.clearCache`、`Rect.intersect`、`$images.*`（先做短的，`detectAndComputeFeatures/matchFeatures` 要接特征匹配，工作量较大）；
   （`$crypto` 见 §5.4、`$zip` 见 §5.5、`$debug` 见 §5.6、`$power_manager`+`floaty` 权限见 §5.7、`$settings` 见 §5.8、`dialog.*`+`ColorMapping` 见 §5.9，已完成）；
3. **把本轮两套实现同步给 QuickJS**：`$crypto` 与 `$zip` 现在只有 Rhino 侧能用（QuickJS 只有 `digest`/`base64*` 与旧的 `zip/unzip/list`）。
   QuickJS 走字符串桥，需要一个新的 JSON 形式宿主方法；改完先跑 `modules/engine/src/main/cpp/build-quickjs.ps1`。
4. **2.36 单独建表**：XML 属性与控件代理方法要和 JS 函数分开核对。
5. **补 Pro 侧对照**（可选）：用同一套探针跑一遍 Pro 9.x，把“我们缺”与“实现不同”彻底分开。
6. **CSV 当验收登记册**：验收一条改一条状态。

## 11. 依据

- [Auto.js Pro 官方 API 文档（335 页）](C:/Users/18101/Downloads/Auto.js%20Pro官方API文档.pdf)：唯一来源，页码与 PDF 实际页号一致。
- [第一代 API 兼容性与 V8 选型](Auto.jsPro_API兼容性与V8选型.md)、[兼容现状报告](../reports/2026-09-15_第一代API兼容现状.md)。
- 脚本与中间产物：`tools/pro-api/`、`.artifacts/pro-api-*.json`、`.artifacts/probe-*.log`。
