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
| `tools/pro-api/click-center-contract.js` | 2.1 `UiObject.clickCenter` / `auto.clearCache` / `Rect.intersect`（§5.10） |
| `tools/pro-api/images-contract.js` | 2.18 `getSimilarity` / `detectsMultiColors` / `getScreenCaptureOptions` / `stopScreenCapture`（§5.11） |
| `tools/pro-api/automator-extra-contract.js` | 2.1 `takeScreenshot` / `switchToInputMethod` / `headsetHook` + 2.12 `getPath` + 2.29 `isAlive`（§5.12） |
| `tools/pro-api/engines-controller-contract.js` | 2.12 `engines.startFloatingController`（§5.13） |
| `tools/pro-api/rootautomator2-contract.js` | 2.2 `RootAutomator2` 整组（§5.14） |
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
结果：**Rhino 405 / 818 取得到，QuickJS 230 / 818 取得到**。

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
| 2.1 自动化-控件操作 | 117 | 106 | 23 | 92 | 1 | 1 | 0 | 16 | 0 | 90 | 1 | 10 |
| 2.2 自动化 - 坐标操作 | 32 | 30 | 25 | 7 | 0 | 0 | 0 | 11 | 0 | 12 | 2 | 0 |
| 2.3 app - 应用 | 25 | 21 | 24 | 0 | 1 | 0 | 0 | 17 | 0 | 0 | 0 | 8 |
| 2.4 base64 | 2 | 2 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 |
| 2.5 colors - 颜色 | 19 | 8 | 19 | 0 | 0 | 0 | 0 | 8 | 0 | 0 | 0 | 11 |
| 2.6 canvas - 画布 | 30 | 29 | 0 | 29 | 0 | 1 | 0 | 0 | 0 | 0 | 1 | 29 |
| 2.7 console - 控制台 | 18 | 18 | 18 | 0 | 0 | 0 | 0 | 11 | 0 | 0 | 0 | 7 |
| 2.8 crypto - 加解密与消息摘要 | 12 | 6 | 8 | 3 | 0 | 1 | 0 | 0 | 1 | 3 | 8 | 0 |
| 2.9 debug - 调试工具 | 5 | 5 | 5 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 5 | 0 |
| 2.10 device - 设备 | 46 | 28 | 45 | 0 | 0 | 0 | 1 | 18 | 0 | 0 | 0 | 28 |
| 2.11 dialogs - 对话框 | 26 | 13 | 10 | 5 | 10 | 1 | 0 | 8 | 0 | 0 | 1 | 5 |
| 2.12 engines - 脚本引擎 | 21 | 15 | 8 | 5 | 5 | 3 | 0 | 6 | 0 | 6 | 4 | 2 |
| 2.13 events - 事件与监听 | 18 | 14 | 2 | 14 | 1 | 1 | 0 | 1 | 0 | 14 | 1 | 1 |
| 2.14 floaty - 悬浮窗 | 25 | 23 | 5 | 18 | 0 | 2 | 0 | 3 | 9 | 7 | 2 | 2 |
| 2.15 files - 文件系统 | 40 | 37 | 27 | 9 | 2 | 2 | 0 | 18 | 0 | 0 | 3 | 18 |
| 2.16 globals - 全局变量与函数 | 16 | 15 | 16 | 0 | 0 | 0 | 0 | 13 | 1 | 2 | 0 | 0 |
| 2.17 http - HTTP 网络请求 | 13 | 5 | 13 | 0 | 0 | 0 | 0 | 4 | 0 | 7 | 1 | 1 |
| 2.18 images - 图片处理 | 76 | 64 | 53 | 9 | 6 | 8 | 0 | 42 | 1 | 27 | 4 | 2 |
| 2.19 keys - 按键模拟 | 21 | 21 | 21 | 0 | 0 | 0 | 0 | 21 | 0 | 0 | 0 | 0 |
| 2.20 media - 多媒体 | 9 | 9 | 9 | 0 | 0 | 0 | 0 | 9 | 0 | 0 | 0 | 0 |
| 2.22 ocr - 文字识别 | 12 | 4 | 0 | 0 | 4 | 8 | 0 | 0 | 0 | 9 | 3 | 0 |
| 2.23 plugins - 插件 | 4 | 1 | 1 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 4 | 0 |
| 2.24 power_manager - 电源管理 | 4 | 2 | 2 | 0 | 1 | 1 | 0 | 0 | 0 | 0 | 4 | 0 |
| 2.25 sensors - 传感器 | 8 | 3 | 4 | 0 | 3 | 1 | 0 | 4 | 0 | 0 | 1 | 0 |
| 2.26 shell - Shell 命令 | 6 | 6 | 6 | 0 | 0 | 0 | 0 | 6 | 0 | 0 | 0 | 0 |
| 2.27 storages - 本地存储 | 8 | 7 | 2 | 5 | 0 | 1 | 0 | 2 | 0 | 5 | 1 | 0 |
| 2.28 settings - 设置 | 2 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | 0 |
| 2.29 threads - 多线程 | 20 | 17 | 8 | 7 | 5 | 0 | 0 | 5 | 0 | 8 | 2 | 3 |
| 2.30 thirdPartyPlugins - 第三方插件 | 40 | 23 | 0 | 0 | 2 | 37 | 1 | 0 | 0 | 30 | 8 | 2 |
| 2.31 timers - 定时器 | 6 | 6 | 6 | 0 | 0 | 0 | 0 | 6 | 0 | 0 | 0 | 0 |
| 2.32 work_manager - 定时与任务 | 21 | 10 | 9 | 1 | 7 | 4 | 0 | 0 | 0 | 7 | 14 | 0 |
| 2.33 util - 工具 | 23 | 20 | 22 | 0 | 0 | 1 | 0 | 0 | 15 | 5 | 3 | 0 |
| 2.34 WebSocket | 6 | 5 | 5 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 2 | 4 |
| 2.35 zip - 压缩与解压 | 17 | 17 | 17 | 0 | 0 | 0 | 0 | 0 | 0 | 12 | 5 | 0 |
| 2.36 Ui - 用户界面 | 188 | 30 | 25 | 2 | 145 | 12 | 3 | 16 | 6 | 6 | 11 | 4 |
| **合计** | **938** | **624** | **442** | **208** | **194** | **88** | **5** | **247** | **33** | **250** | **95** | **137** |

**读法**：`实机有 + 源码有 + 待核实` = 有证据合计 → Rhino **844/938（90%）**，QuickJS **530/938（56%）**。

> 注：`源码有`/`待核实`/`未定位` 三列的分配受扫描规则影响（本轮把 12 行 `RootAutomator2` 成员从“待核实”提到了“实机有”），
> 所以**看趋势看 `实机有` 与 `未定位`**：实机有 424（§5.11 前）→ 442，未定位 93 → 88。

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

### 5.10 已按清单补齐：2.1 `clickCenter` / `clearCache` / `Rect.intersect`（本轮）

| 条目 | 实现 | 验收 |
| --- | --- | --- |
| `UiObject.clickCenter()`（Pro 8.8.17 新增） | `UiObject.clickCenter()` + 引擎侧注入坐标点击（automator 模块拿不到 ActionAutomator） | 真机点对话框“确定”按钮，positive 回调被触发 |
| `auto.clearCache()`（v9.0.14 新增） | 清的就是无障碍服务的 `mFastRootInActiveWindow` 缓存：`AccessibilityService.clearNodeCache()` + `AccessibilityBridge.clearCache()` + `__automator__.js` 里的 `auto.clearCache` | 清缓存后服务仍可用（仍能取到根节点） |
| `Rect.intersect(r)` | 本来就是 Android 框架方法（`UiObject.bounds()` 返回的就是 `android.graphics.Rect`）→ 扫描器新增平台类映射，不再当成漏实现 | 相交/不相交/自身变交集三个断言 |

踩坑/要点：

- ⚠️ **注入时机**：坐标点击实现一开始写在 `prepareForGesture()` 里（只有先调过 `click(x,y)` 才会执行）→ 第一次直接调 `clickCenter()` 时 clicker 还是 null，直接返回 false。改成 **SimpleActionAutomator 构造时**注入。
- ⚠️ **`clearCache` 会和方法名冲突**：Android 框架的 `AccessibilityService` 已有同名方法，Kotlin 编译报 *hides member of supertype* → 服务端方法改名 `clearNodeCache()`（脚本侧 API 仍是 `auto.clearCache()`）。
- ⚠️ **验证 `clickCenter` 必须先开无障碍服务**：本机用
  `adb shell settings put secure enabled_accessibility_services com.jdkshen.aijspro/com.stardust.autojs.core.accessibility.AccessibilityService`
  + `settings put secure accessibility_enabled 1`；两点注意：① 安装 APK 后系统会把这个设置清成 null，需重新写；② `am force-stop` 后服务不会自动重绑，也要重新写（后再启动脚本）。

### 5.11 已按清单补齐：2.18 `getSimilarity` / `detectsMultiColors` / `getScreenCaptureOptions` / `stopScreenCapture`（本轮）

| 条目 | 实现 | 验收 |
| --- | --- | --- |
| `images.getSimilarity(img1, img2, {type})` | 新建 `core/image/ImageMetrics.java`：PSNR（逐像素 MSE）与 MSSIM（8×8 窗口 SSIM 均值，C1/C2 同原论文）；`type` 纩写容忍（`PNSR`/`PSNR`/`MSSIM`） | 同图 MSSIM=1、PSNR=Infinity；黑白图 MSSIM≈1e-4、PSNR=0 |
| `images.detectsMultiColors(img, x, y, firstColor, colors, options)` | `ColorFinder.detectsMultiColors`（复用 `DifferenceDetector`）+ JS 侧解析颜色/偏移/`threshold`/`similarity` | 6 个用例（命中/起点不符/偏移不符/越界/threshold 0 与 10） |
| `$images.getScreenCaptureOptions()` | `Images.getScreenCaptureOptions()`：没申请过权限返回 `null`，否则返回 `{width,height,orientation,density,async}` | 未申请→null；无障碍通道申请后→`1080/2160/0/440/false`；释放后→null |
| `$images.stopScreenCapture()` | `Images.stopScreenCapture()` → `releaseScreenCapturer()`（同时清普通投影与无障碍兼容通道） | 释放后 `getScreenCaptureOptions()` 为 null |

要点：

- `orientation` 取值与文档一致（`Configuration.ORIENTATION_*` 正好是 0/1/2）；width/height 按申请时指定的方向取。
- `async` 固定为 `false`：本实现的截图都是阻塞取帧（`ScreenCapturer.capture`），没有异步通道。
- **无障碍（录屏兼容）通道也算“已申请截图权限”**：它不创建 `ScreenCapturer`，所以 `getScreenCaptureOptions` 额外判断了 `mAccessibilityCapture`。
- ⚠️ 验收用的是无障碍通道（`requestScreenCapture({mode:'accessibility'})`）：MediaProjection 弹窗在 Android 15 上是「下一步 → 选应用/整个屏幕 → 开始共享」，没法放进自动化契约（辅助脚本留在 `.artifacts/tools/tap-media-projection.py`）。
- `images.getSimilarity` 要求两张图尺寸相同，否则报错（文档没写，但不同尺寸没法逐像素比）。

### 5.12 已按清单补齐：2.1 `takeScreenshot` / `switchToInputMethod` / `headsetHook` + 2.12 `getPath` + 2.29 `isAlive`（本轮）

`tools/pro-api/automator-extra-contract.js`：真机 **28/28** 通过。

| 条目 | 实现 | 验收（真机断言） |
| --- | --- | --- |
| `automator.takeScreenshot()`（Pro 8.8.0） | `SimpleActionAutomator.takeScreenshot()` → `AccessibilityScreenCapturer().capture(2000)` → `ImageWrapper` | 返回非空 Image、尺寸 `1080×2160` == `device.width/height`、能取像素；连续两次调用不抛异常且耗时 ≥ 300ms（命中限频排队） |
| `automator.switchToInputMethod(pkg)`（Pro 8.8.0） | `SimpleActionAutomator.switchToInputMethod()`：解析包名对应输入法 id → `InputMethodManager.setInputMethod` → 失败再写 `Settings.Secure.DEFAULT_INPUT_METHOD`（需 `WRITE_SECURE_SETTINGS`）→ 读回系统当前输入法自证 | 不存在的包名 → `false`；切到 AOSP / 讯飞 → `true`，且 `default_input_method` 跟着变（两次都断言到了） |
| `automator.headsetHook()`（Pro 8.8.0） | `SimpleActionAutomator.headsetHook()`：Shizuku 注入 → root 注入 → `AudioManager.dispatchMediaKeyEvent(KEYCODE_HEADSETHOOK)` 降级 | 返回 boolean（无 root/Shizuku 的本机走第三条，返回 `true`） |
| `getPath()`（2.12 `ScriptConfig`） | 本来就是 `ExecutionConfig.path: Array<out String>` 的 Kotlin getter；本轮把 `__engines__.js` 的 `fillConfig` 补齐：`path` 支持 **string 或 Array**，两种都写进 `ExecutionConfig.path`，字符串/数组首项同时作为 `workingDirectory` | `getPath()` 长度 2、两项内容与传入目录一致、`getWorkingDirectory()` == 首项；传字符串时 `getPath()[0]` 也拿得到 |
| `isAlive()`（2.29 线程对象） | 脚本线程本身就是 Java `Thread`（主线程是 `MainThreadProxy`），`isAlive()` 是平台方法，无需实现 | 运行中 `true`、`join()` 之后 `false` |

要点：

- `takeScreenshot` 与文档的**唯一差异**：文档写「超过频率限制会抛异常」，我们让 `AccessibilityScreenCapturer` 按 340ms 排队等（Android 15 的硬限制是 >333ms）—— 行为上是「更宽容的超集」，不会在 Pro 会抛的场景下抛（记入 §9）。
- `switchToInputMethod` 的**第二级回退**（写安全设置）本机不需要（第一级 `setInputMethod` 就成功），保留是为了没有 `WRITE_SECURE_SETTINGS` 时也能工作；两条路径最后都用「系统当前输入法」自证，不是“调完就返回 true”。
- `headsetHook` 的三条通道按“由强到弱”排：Shizuku/root 注入 `input keyevent 79` 是真正的系统级按键，`AudioManager.dispatchMediaKeyEvent` 只是应用级媒体键（无 root 设备的可用路径）；本机（小米 8，无 root、Shizuku 服务未运行）返回 `true`。
- `getPath()` 是文档里典型的“裸成员名”（不带类型前缀），扫描器过去按全局名去扫 → 永远 `未定位`；`rebuild.py` 新增 `LOOSE_MEMBER_HINTS` 显式指到实现（`getPath` / `isAlive` 两条），这类条目以后可以直接登记映射。

### 5.13 已按清单补齐：2.12 `engines.startFloatingController`（本轮，行为级验收）

`tools/pro-api/engines-controller-contract.js`：真机 **21/21** 通过。

| 项 | 位置 |
| --- | --- |
| 控制器实现 | 新建 `runtime/api/FloatingController.java`（悬浮窗口 + 启动/停止、日志、关闭三个按钮 + 引擎状态回写） |
| 布局 | 新建 `modules/engine/src/main/res/layout/floaty_controller.xml`（另有两处旧布局 `floating_console_expand/floating_window_collapse` 是仓库自带悬浮日志窗，没动） |
| 入口 | `Engines.startFloatingController(path, config, runImmediately)` + `__engines__.js` 的 `options.runImmediately` 解析 |

行为验收（用“目标脚本有没有真的跑起来”自证，而不是只看返回值）：

- 默认 `runImmediately=false`：返回控制器对象、`isRunning()==false`、目标脚本没被启动（标记文件不存在）
- `start()`：启动后 500ms `isRunning()==true` 且标记文件还没写；2500ms 后标记文件出现（说明真的跑完了）、`isRunning()` 回到 `false`
- `toggleLog()`：日志开关往返（真机 `console.show()/hide()`，`isLogVisible()` 跟着变）
- `runImmediately=true`：传参当场把脚本跑起来
- `stop()`：跑一半停掉后，标记文件不会被写出来；重复 `stop()` 返回 `false`
- `path` 不是字符串 → 直接报错（不静默）

要点：

- 按钮和 `start()/stop()` 走**同一份代码**（监听器与按钮回调都调同一个方法），所以契约里测方法等价于测按钮；窗口本身用 `dumpsys window` 确认存在（`appop=SYSTEM_ALERT_WINDOW` 的 overlay 窗口）。
- 引擎状态不用自己猜：给 `execScriptFile` 挂 `ScriptExecutionListener`（`onStart` / `onSuccess` / `onException`）回写，`isRunning()` 与按钮文字都从这里来。
- ⚠️ **文档没写返回值**；我们返回控制器对象（超集：多了 `start/stop/isRunning/toggleLog/isLogVisible/getPath/close`）—— 记入 §9。
- `close()` 只关窗口、**不停脚本**（要停先 `stop()`）：控制器被关掉时把用户正在跑的脚本一并杀掉更危险。
- 悬浮窗权限不足时 `startFloatingController` 会走仓库原有的 `Floaty` 权限流程（`FloatingPermission.waitForPermissionGranted`），与 `floaty.window` 一致。

### 5.14 已按清单补齐：2.2 `RootAutomator2` 整组（本轮，adb 通道真实注入验收）

`tools/pro-api/rootautomator2-contract.js`：真机 **21/21** 通过。扫描后 2.2 的 `实机有` 11 → 25，
`RootAutomator2` / 构造函数 / `tap` / `longPress` / `press` / `swipe` / `touchDown`(两种) / `touchMove`(两种) / `touchUp`(两种) / `flush` / `exit` 全部归位。

| 项 | 实现 |
| --- | --- |
| 新类 | 新建 `core/inputevent/RootAutomator2.java`：`{adb, root}` 两个通道（文档：`adb` 默认 false，未指定 adb 时 `root` 默认 true） |
| root 通道 | 复用仓库已有的 `RootAutomator`（打包的输入事件二进制 + `su`），支持多点触控 |
| adb 通道 | 走 Shizuku/adb shell 的 `input` 命令：`input tap` / `input swipe`（起点=终点 = 长按/按住）/ `input motionevent DOWN|MOVE|UP` |
| JS 侧 | `__RootAutomator__.js` 新增 `RootAutomator2`（挂到 `scope`，文档里它就是全局名），并在 JS 层把文档的**数组形式** `touchDown([{x,y,id}...])` 展开成单指调用 |

真机验收（小米 8，**没有 root，但 Shizuku 服务在跑**）：

- **adb 通道真实注入**：弹一个对话框，用无障碍选择器拿到“确定”按钮的 `bounds()` 中心，
  `adb.tap(中心)` → positive 回调被触发；`adb.press(中心, 300)` 同样点中 ✓
- **负向对照**：向按钮下方偏 500px 点一下 → 回调**没**触发（证明真的是按坐标落在按钮上）
- **root 通道**：本机没有 `su` → `touchDown` 直接抛出「root 通道不可用……要免 root 用 input 注入请改用 `new RootAutomator2({adb:true})`（需要 Shizuku）」，
  **不静默成功、也不会把脚本卡死**（先在 Java 层用 `ProcessShell.exec({"id"}, true).code == 0` 探一下，避免 `RootAutomator` 在等壳回调时永久阻塞）
- 选项校验：`adb` 与 `root` 同时为 true 报错；两个都不给报错
- 多点触控：adb 通道收到多指数组时**明确报错**（不会只动一根手指还返回成功）

踩到的坑：

- ⚠️ **JS 包装不能被构造函数里的 `bind` 遮蔽**：一开始把 Java 的 `tap/touchDown/...` 一并 `bind` 到实例上，
  原型上的包装方法（数组展开、多指拦截）就永远不生效 —— 数组参数直接落到 Java 重载上报 Rhino 类型错。
  现在只 `bind` `setScreenMetrics`/`isAdb`，其余全在原型上实现。
- ⚠️ **验收脚本的对话框顺序**：点在对话框外面会把它 dismiss 掉 → “负向对照”必须用**另一个**对话框，
  否则后续的正向点击会扑空（先试了一次就踩到了）。

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

⚠️ §5.4~§5.14 补齐 / 归位的模块不再属于这一节（`$crypto`、`$zip`、`$debug`、`$power_manager`+`floaty` 权限、`$settings`、`dialog.*`+`ColorMapping`、`clickCenter`/`clearCache`/`Rect.intersect`、images 四条、automator 三条+`getPath`+`isAlive`、`startFloatingController`、`RootAutomator2` 整组）。
剩余只 3 条，两个功能组：
`OCR.detect`/`OCRResult.clickCenter`（要接 OCR 引擎）、
`$images.detectAndComputeFeatures`（+ `matchFeatures`/`ImageFeatures`/`ObjectFrame`/`MatchingResult` 一组，共 ~11 行成员）。

### 6.3 类型名 —— 不是缺口

`Rect`、`Key`、`KeyPair`、`OCR`、`OCRResult`、`TimedTask`、`EventEmitter`、`Storages`、`ScriptEngine`、`ScriptExecution`、`Dialog`、`FloatyWindow`、`FloatyRawWindow`、`SensorEventEmitter`、`Bug` 等：文档里是**返回值类型**，本来就不该是全局。已排除在"缺"之外。

## 7. 该从哪些行开始

CSV 里筛 `rhino ∈ {未定位, 实机缺}` 且 `kind ∈ {function, constructor}`、排除 `2.30`/`2.36`，只剩 **3 条**：

| 模块 | 条目 | 状态 |
| --- | --- | --- |
| 2.22 ocr | `OCR.detect`、`OCRResult.clickCenter` | 未定位（要接 OCR 引擎） |
| 2.18 images | `$images.detectAndComputeFeatures` | 未定位（特征匹配，最大一件） |

建议顺序：`RootAutomator2`（可以照搬仓库已有的 `RootAutomator` 通道）→ `OCR.detect`（要选并接 OCR 引擎）→ `$images` 特征匹配（最大，见下）。

⚠️ 2.18 的 **特征匹配一组**（`$images.detectAndComputeFeatures` / `matchFeatures` / `ImageFeatures` / `ObjectFrame.*`，共 ~11 行，其中多数是类型成员所以不在上表）没实现，而**仓库自带的样例已经在用它**（`sample/Rhino 引擎/图片与图色处理/…`）—— 已列入 §10 下一步。

QuickJS 侧同类共 **150 行**（未定位 43 + 实机缺 107），大头是 `canvas.*`(29)、`device.*`(28)、`files.*`(18)、`colors` 常量(11)、`app.*`(8)、`console.*`(7)；⚠️ 本轮五条也是 **Rhino 先落地**，QuickJS 侧仍记 `实机缺`（同一批 JNI 桥的活，见 §10）。

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
- **已补齐的模块只验了本轮脚本覆盖到的东西**：§5.4~§5.14 每一条都有对应的 `tools/pro-api/*-contract.js`（共 **223 项**断言），但**默认值/行为差异仍存在**，已知的几处：
  - `$settings.isEnabled('stop_all_on_volume_up')`：主应用沿用仓库原有键 `key_use_volume_control_running`，其默认值是**关**，而 Pro 文档写的是默认**开**（打包版默认开，与文档一致）。
  - `$settings.isEnabled('not_show_console')`：主应用没有「脚本启动即弹日志界面」的行为，这个开关只负责存取值。
  - `dialog.setDefaultDialogType` 的**默认值**：文档写 `overlay`，我们保留仓库原有的 `app-or-overlay`（否则现有脚本的对话框会从应用内变成悬浮层）；四种类型本身都支持，也可以随时显式设置。
  - `UiObject.clickCenter()` 依赖无障碍服务，而本机 MIUI 会在**安装 APK 后**把 `enabled_accessibility_services` 清空、`am force-stop` 后也不自动重绑（验证前需重新写一次，见 §5.10）。⚠️ 顺序有讲究：必须 **先 force-stop、再写设置**，反了会被 force-stop 一并干掉。
  - `automator.takeScreenshot()`：文档说「超过频率限制会抛异常」，我们按 340ms **排队等**（Android 15 硬限制 >333ms），不抛错。
  - `automator.headsetHook()`：优先 Shizuku/root 注入 `input keyevent 79`，都不可用时降级为 `AudioManager.dispatchMediaKeyEvent`（应用级媒体键）—— 后者在没有前台媒体会话/来电时看不到可观察副作用，本机只能断言“返回 true”。
  - `automator.switchToInputMethod()`：只传包名时，同一包名下若启用了多个输入法 service，取列表里的第一个（文档也只给包名）。
  - `getPath()`：文档里 `path` 可传 string 或 Array；我们两种都接，但数组只有第一项会当作 `workingDirectory`（require 的基准目录）。
  - `engines.startFloatingController()`：文档**没写返回值**，我们返回控制器对象（多出 `start/stop/isRunning/toggleLog/isLogVisible/getPath/close`）；另外 `close()` 只关窗口不停脚本。
  - `RootAutomator2`：文档说“adb 权限”用的是 ShellOptions 语义，我们实现成：`root` 通道 = 仓库原有 RootAutomator（需 `su`，支持多指），`adb` 通道 = Shizuku/adb shell 的 `input`（**不支持多点触控**，数组形式会报错）。
    本机（小米 8）没 root 但有 Shizuku，所以 root 通道只有“不可用时报错”的验证，**装 root 的设备应该补一次真实注入验收**。
- **单机单版本**：本轮只覆盖小米 8 + 当时构建，换设备/版本要重跑（脚本已固化）。
- **仍有盲区**：动态挂载（`Proxy`、`Object.defineProperty`、反射注册）与"要满足条件才存在"的成员（实例方法、惰性加载）都测不到；`未定位` 只代表四层证据都没找到。
- **没有 Pro 对照设备**：`version` 列只说明该条目属于哪个版本，不代表我们对齐到了同一版本。

## 10. 下一步

1. **按 §6.1 修扫描规则**：把 `UiObjectCollection`、`PReadableTextFile`/`PWritableTextFile`、`$crypto.base64*`、`addBroadcastIntentTask`、`getInstalledPackageList` 这些"名字对不上"的映射写进 `rebuild.py`，让 13 条漏扫自动归位。
1. **补完最后 3 条**：`OCR.detect` / `OCRResult.clickCenter`（用 ML Kit 文字识别，新增依赖，需先定体积/权限影响）→ `$images.detectAndComputeFeatures` 一组。
   ⚠️ 特征匹配已确认：当前 `opencv-5.0.0-16kb-page-fix.aar` 是**按模块拆 .so 的构建，里面没有 features 模块**；
   OpenCV 5.0 把这一组 Java 包改名叫 `org.opencv.features`（ORB/BFMatcher/SIFT 都在），官方 SDK 的 `libopencv_java5.so` 里有 `Java_org_opencv_features_*`（330 个）
   且 arm64 LOAD 对齐已是 16KB —— 所以可以直接用 imgproc 自写 ORB-lite，或换/重编带 features 模块的原生库（后者要动 `build-quickjs.ps1` 链接与 QuickJS 原生库）。
   （已完成：`$crypto` §5.4、`$zip` §5.5、`$debug` §5.6、`$power_manager`+`floaty` 权限 §5.7、`$settings` §5.8、`dialog.*`+`ColorMapping` §5.9、`clickCenter`/`clearCache`/`Rect.intersect` §5.10、images 四条 §5.11、automator 三条 + `getPath` + `isAlive` §5.12、`startFloatingController` §5.13、`RootAutomator2` §5.14）；
2. **把本轮实现同步给 QuickJS**：`$crypto`/`$zip`/`clickCenter`/images 四条/automator 三条 现在只有 Rhino 侧能用（QuickJS 只实现了各自的一部分）。
   QuickJS 走字符串桥，需要新的宿主方法；改完先跑 `modules/engine/src/main/cpp/build-quickjs.ps1`。
4. **2.36 单独建表**：XML 属性与控件代理方法要和 JS 函数分开核对。
5. **补 Pro 侧对照**（可选）：用同一套探针跑一遍 Pro 9.x，把“我们缺”与“实现不同”彻底分开。
6. **CSV 当验收登记册**：验收一条改一条状态。

## 11. 依据

- [Auto.js Pro 官方 API 文档（335 页）](C:/Users/18101/Downloads/Auto.js%20Pro官方API文档.pdf)：唯一来源，页码与 PDF 实际页号一致。
- [第一代 API 兼容性与 V8 选型](Auto.jsPro_API兼容性与V8选型.md)、[兼容现状报告](../reports/2026-09-15_第一代API兼容现状.md)。
- 脚本与中间产物：`tools/pro-api/`、`.artifacts/pro-api-*.json`、`.artifacts/probe-*.log`。
