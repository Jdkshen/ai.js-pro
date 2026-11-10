# SJBC 脚本保护挂载（L3 自定义指令集 VM）

> 来源：device4 的 **一页设计说明** `Download/DSH-Go/AIjsPro-挂载SJBC到手机打包.md`（§三「三件必须对齐的事」、§四「与打包界面的对应关系」）+ `AIjsPro-L3-SJBC/交接说明_M1-M17.md` + `pc-tools/README.md` + `m18/README.md`，并与**我们仓库里已落地的代码**逐条对齐。
> **2026-11-10 更新（dev-1456）**：App 侧 **VM/入口/打包/UI 全部落地**并在 K40 真机端到端跑通（打包版自己解自己跑，`steps=1990`）⇒ 本文的「（待落）」已收窄到 §5 表末三行。详见 §1.5 / §2.1 / §5.1。
>
> ⚠️ **原文可及性边界**：device4 当前 `adb offline`，我**没能读到用户那两份 md 的原文**；本文件基于 ①仓库内 `tools/sjbc/README.md:3-4` 明示的「来源=device4 `脚本/sjbc-tools/`，对照=device4 那份挂载 md」②`output/task-79-2026-11-10/mat/`（含 `交接说明_M1-M17.md`、`pc-tools__README.md`、`m18__README.md`、`m8__native__sjbc_v5.c`、`pc-tools__ajs2sjbc.js` 等 16 份材料）③我们已合并的实现与测试。**凡我推断而非引用之处都标了「（推断）」。**

## 0. 一句话

把 AI.js Pro 写出的脚本，在**电脑/手机侧**编译成**自定义指令集字节码**，用 **v5 容器**（HKDF-SHA256 双密钥 + AES-256-GCM 双层 AEAD）加密成 `.sjbc`，再让宿主 App **解密 + VM 解释执行**。密钥主根是**签名证书 SHA-256** ⇒ **换证书即解不开**（已实测）。

## 1. 两种接法

| | **A · 当资源随包** | **B · 完全自控** |
|---|---|---|
| 形态 | `.sjbc` 放进 APK 的 `assets/sjbc/`，随包分发，运行期由 App 解密执行 | 放**外置目录**（免重装热加载）或由**你自己**的宿主/VM 控制；App 侧不接管 |
| 配置 | `project.json` → `sjbc.assetsDir`（默认 `assets/sjbc`）+ `sjbc.certSha` | 无（走 `build_all.js` 顶部 `deployTo` 投放目录） |
| 落地命令 | 打包 CLI 产 `.sjbc` → 放进 `assets/sjbc/` → `zipalign -p 4` → `apksigner sign`（**必须与 `--cert` 同一把证书**） | `adb push out/hello.sjbc /sdcard/Android/data/<宿主包名>/files/modules/` → 宿主内点「刷新」 |
| 我们侧落点 | `ProjectConfig.SjbcConfig`（`assetsDir`/`certSha` 已有）；打包侧 = `SjbcPackaging.kt` + `ApkBuilder.stageSjbcAssets()`（**已落地**，只收 `.sjbc`+`manifest.json`；无 `.sjbc` 时零行为变化） | 工具链已入库（`tools/sjbc/build_all.js` 的 `deployTo`、`build-all.mjs --deploy-to`）；App 侧 VM = `SjbcVm`/`SjbcApi`（**已落地**，Rhino 口径） |
| 典型用途 | 交付给用户的成品包 | 迭代期、自控宿主、第三方宿主 |

> `pc-tools__README.md `4` 里这两条叫「方式 A 热加载 / 方式 B 打进 assets」；本文件的 A/B 命名按任务书口径（**A=随包资源、B=完全自控**）——两者指同一组能力，只是叫法次序相反。

## 1.5 在打包 UI 里怎么选（⋮ → 项目配置与打包）

**操作路径（K40 真机走过，2026-11-10）**：脚本列表 **⋮ → 「项目配置与打包」** → 在**脚本加密等级**下拉里选 **「不加密（推荐：SJBC 容器自带加密）」** → 点 **「保存并打包」**。

- **为什么推荐「不加密」**：`.sjbc` **自带** v5 容器加密（§3）；再套一层 App 的脚本保护只是把已经加密的东西再包一层，收益小、还多一层解析。
- **下拉里的 4 个文案（真机实测，一个都没删）**：① `不加密（推荐：SJBC 容器自带加密）` ← **选这个**；② `离线加密（可选再套一层）`；③ `离线加密 · Dex（可选再套一层）`；④ `离线加密 · Snapshot（等同 Dex：引擎最高等级为 2）`（**Snapshot(3) 会被引擎 clamp 到 2**）。
- **页面还会显示**：引擎/版本/保存态（例：`Rhino / v1.0.0 / 已保存`）与上一次产物（例：`APK 已生成 41286311 字节 · com.aijspro.sjbce2e · 加密等级 0 · arm64-v8a` + SHA-256）。

**可核的两行 logcat**（点完「保存并打包」）：
~~~text
C-PKG: sjbc certSha=1b57505e… files=1 manifest=true source=sjbc        ← 收到 SJBC 资产 + 实际用的证书指纹
C-PKG: template available=true source=…/apk-builder/1456/template.apk ← 用的是哪个模板（打包版引擎新不新看这条）
（随后）build ok size=41286414 sha256=10d46729…  /  stage=success     ← 产物与阶段
~~~

**截图留档（只引用、不拷贝）**：`.artifacts/ui-test-2026-11-10/ui5.png`、`ui6.png`、`ui7.png`、`ui10.png`（同目录另有 `加密等级下拉（新文案）.png`）——想看 UI 直接开这几个文件。

**两个操作注记**：
1. **Dart 改动默认不进包**：`tools/flutter-poc/build-release-candidate.ps1` 默认复用锁定的 AOT payload ⇒ 要带上 UI 改动必须加 **`-RebuildAot`**（本次下拉新文案就是靠它才在真机出现）。
2. **打包时 `abis` 会带上 v7a ⇒ 每次出 `abis warning`**（**只警告不失败**，属「打包模板重建」那条线）；产物 `lib/` 仍只有 arm64。

## 2. 三件必须对齐的事（用户 md §三）

### 2.1 证书指纹 `certSha`
- **是什么**：签名证书的 **SHA-256（64 位 hex）**；主密钥唯一根（见 `3）。**换证书 ⇒ 全部脚本必须重打包**。
- **怎么拿**：`apksigner verify --print-certs x.apk | grep "SHA-256 digest"`；或 `keytool -printcert -jarfile x.apk | grep SHA256`；手机侧 MCP `apk_read_signature` → `certificates[].sha256`。
- **我们侧落点**：`ProjectConfig.SjbcConfig`（`modules/engine/src/main/java/com/stardust/autojs/project/ProjectConfig.java:182-200`）——**规范化 = 去冒号 + 转小写**（`ProjectConfigSjbcTest.java:36`：`AB:CD:EF:01:23:45` → `abcdef012345`）；空值被视为「没给、不比较」（`ProjectConfig.java:335`）。容器侧 `SjbcV5Container.masterFromCertSha256Hex()`（`:127-136`）只接受 64 位 hex。
- **CLI 侧**：`tools/sjbc/build-all.mjs --cert <apk 或 64 位 hex>`（`tools/sjbc/README.md:22-28`）。
- **真机证据（K40，2026-11-10）——「必须用打包器那把证书」不是理论**：打包版自己的证书是 `1b57505e…`；**先用候选包证书（`38166a4b…`）加密同一个 `hello` 再装 → 打包版报 `SjbcException: SJBC 池层解密失败（AEAD 拒绝：证书指纹不对或池被篡改）`**；换回打包器证书重加密 ⇒ 同一模块 `steps=1990` 正常跑完。⇒ **`.sjbc` 必须用「最后给这个 APK 签名的证书」加密**。

### 2.2 宿主函数表 `HOST=['print','len','push','slice0']`
- **按索引的白名单**：`0=print 1=len 2=push 3=slice0`（`交接说明_M1-M17.md:31`；`m18__README.md:43` 载明真机宿主已扩到 4 个）。
- **必须与宿主实现一致**：少一个 ⇒ 编译期把 `push(...)` 当「未定义函数」直接失败（`pc-tools__README.md `6.1`）。
- **我们侧落点**：编译器/打包器里是 `const HOST = ['print','len','push','slice0']`（`tools/sjbc/sjbc-pack.mjs:18`、`sjbc-pack.js:11`、`build_all.js:15`；`ajs2sjbc.js:298` 用 `hostTable.indexOf` 解析）。**App 侧 VM 里实现这 4 个宿主函数 = （待落）**。

### 2.3 入口 `main.js` 改成加载器
- 用户 md §四「与打包界面的对应关系」的核心：**产物入口不再是被保护的脚本本身，而是一个加载器**——它负责「读 `assets/sjbc/`（或外置目录）→ 解密 → 交给 VM 执行」。
- Demo 形态：宿主 `com.aijs.sjbc.demo/.HostActivity`，模块目录 `assets/sjbc/`（内置 4 个）+ 外置 `/sdcard/Android/data/com.aijs.sjbc.demo/files/modules/`，自证文件同目录 `sjbc_proof.txt`（`交接说明 `6`）。
- **我们侧落点**：**（待落）**——App/inrt 侧尚未看到加载器与 VM 执行链（见 `5）。

## 3. v5 容器格式与两级 HKDF（逐字段）

> 权威出处：`modules/engine/src/main/java/com/stardust/autojs/sjbc/SjbcV5Container.java:20-27`（类注释即格式表）+ `交接说明_M1-M17.md:32-36`；JS/C 侧同格式（`tools/sjbc/pack_v5.js`、材料 `m8__native__sjbc_v5.c`）。

~~~text
header : 'SJBE'(4) | ver=5 | kdf=1(HKDF-SHA256) | cipher=1(AES-256-GCM) | flags=0    = 8 字节
body   : ivP(12) | tagP(16) | poolLen(4, big-endian) | poolEnc | ivC(12) | tagC(16) | codeEnc

两级密钥（RFC 5869 HKDF-SHA256）：
  master = HKDF(ikm = 证书SHA256(32B), salt='sjbc-m9-cert', info='master', 32)
  kPool  = HKDF(master,            salt='sjbc-v5-salt', info='pool',   32)
  kCode  = HKDF(master,            salt='sjbc-v5-salt', info='code',   32)

池（明文，行式文本）：
  SJBC5
  ENTRIES n o1 o2 … on
  N <i> <num>        ← 数字常量（Double）
  S <i> <str>        ← 字符串常量（\ 与 \n 已转义）
码：原始字节码（每条指令 4 字节，LOADF 6 字节）
~~~

- **两层各自 AEAD** ⇒ 改池或改码都会在**对应层**被拒（`SjbcV5Container.java:28`）。
- 池解析的硬约定：首行必须 `SJBC5`；第 2 行必须 `ENTRIES n o1..`；每行形如 `N i num` / `S i str`（`:178-221`）。
- 反义转义是**单遍**扫描（避免二次反转义，`:223-242`）。
- 纯 JCE 实现、无 Android 依赖 ⇒ 可 JVM 单测；**没用 API 33 的 KDF**（`:138`）。

## 4. 失败排查（按症状 → 根因 → 动作）

| 症状（原文文案） | 根因 | 动作 |
|---|---|---|
| `SJBC 池层解密失败（AEAD 拒绝：证书指纹不对或池被篡改）`（`SjbcV5Container.java:106`） | 签名证书 ≠ `--cert` 那把（最常见），或池被改 | **重新用同一证书签名**；若确实换了证书 ⇒ 用新指纹**重打包全部脚本** |
| `SJBC 码层解密失败（AEAD 拒绝：… 或码被篡改）`（`:111`） | 同上（码层） | 同上 |
| `SJBC magic 不是 SJBE：实际 …` / `未知 SJBC 容器版本 N（本实现只支持 5）`（`:70/:74`） | 拿错文件 / 版本不符 | 确认是 `.sjbc` 且 ver=5 |
| `不支持的 kdf/cipher：kdf=… cipher=…`（`:79`） | 头字节被改 / 非本实现产出 | 用 `tools/sjbc/` 的 CLI 重产 |
| `SJBC 容器在 ivP\|tagP\|ivC\|tagC 处被截断` / `poolLen 越界` / `容器太短`（`:66/:89/:95/:246`） | 文件被截断/传输损坏 | 重传（`adb push` 后再比 sha256） |
| `池文本格式不对：首行应为 SJBC5` / `第 2 行应为 ENTRIES n o1..` / `池文本行类型未知（应为 N/S）`（`:181/:186/:217`） | 池被手工编辑 / 上游打包器 bug | 用 CLI 重产；**不要手改池** |
| **编译期**「未定义函数 push」 | 宿主函数表与实现不一致 | 对齐 `HOST`（见 `2.2）：CLI 与宿主必须同表 |
| Demo 自证文件写 `FAILED: FileNotFoundException sjbc/module_v5.enc` | Demo 宿主在找 `sjbc/module_v5.enc` 但 **assets 里没有这个名字的模块**（现有材料里模块名是 `<name>.sjbc`，如 `数组与统计.sjbc`） | **（真机现状，Lead 实测报）**：先确认 Demo `assets/sjbc/` 里到底有什么 + 宿主读的路径常量；**命名不一致（`module_v5.enc` vs `*.sjbc`）待核** |

## 5. 我们这边的落点（真名 / 待落）

| 环节 | 落点（真名） | 状态 |
|---|---|---|
| PC/手机侧工具链 | `tools/sjbc/`：`ajs2sjbc.js`(27127) `opcodes.js`(1105) `pack_v5.js`(3137) `keyderiv.js`(671) `vm.js`(5450) `sjbc-pack.mjs`(3545) `sjbc-pack.js`(2885) `build_all.js`(2533) + `build-all.mjs` + `README.md` | ✅ **已入库**（`tools/sjbc/README.md:3`：逐字节来自 device4 `脚本/sjbc-tools/`，未改算法） |
| v5 容器（Java/JCE） | `modules/engine/src/main/java/com/stardust/autojs/sjbc/SjbcV5Container.java` + `SjbcException.java` | ✅ 已落地（与 JS/C 三方对拍口径写在类注释） |
| 容器单测 | `modules/engine/src/test/java/com/stardust/autojs/sjbc/SjbcV5ContainerTest.java`（证书指纹取自 demo 自己的 `assets/sjbc/manifest.json`） | ✅ |
| project.json 字段 | `ProjectConfig.SjbcConfig`（`sjbc.assetsDir` 默认 `assets/sjbc`；`sjbc.certSha` 规范化为去冒号小写；空值 warning） | ✅ |
| 字段回归 | `apps/app/src/test/java/com/stardust/autojs/project/ProjectConfigSjbcTest.java`（含「未用 SJBC 的工程 `toJson` 逐字不变」） | ✅ |
| 打包 CLI 参数化包装 | `tools/sjbc/build-all.mjs --src/--out/--cert/--deploy-to/--check`（只替换 `build_all.js` 顶部 4 行配置，算法未改） | ✅ |
| **App 侧 VM 解释器** | `modules/engine/src/main/java/com/stardust/autojs/sjbc/`：`SjbcVm.java`（逐行移植 `pc-tools/vm.js`：16 寄存器帧/`entries`/闭包/异常/`LOADF` 6 字节/跳转按真实长度）+ `SjbcValues.java` + `SjbcHostFunctions.java`（`print/len/push/slice0`）+ `SjbcKeySource.java`（**主密钥 = 本包签名证书 SHA-256 → HKDF**）+ `SjbcApi.java`（只读入口 `sjbc.run/runEntry`） | ✅ **已落地**（dev-1456；测试 `SjbcVmTest`/`SjbcApiTest`） |
| **加载器/脚本全局** | `runtime.getProperty('sjbc')` 由 `__globals__.js` 挂到 `global.sjbc`（`runtime.putProperty` **不会**变全局，见 §5.1） | ✅ **已落地**（dev-1456） |
| **打包侧接线** | `apps/app/src/main/java/com/jdkshen/aijspro/build/SjbcPackaging.kt`（`TARGET_DIR="assets/sjbc"`、只收 `.sjbc`+`manifest.json`）+ `ApkBuilder.java:517/612 stageSjbcAssets()`；`certSha` 取自**真签名证书**；logcat `C-PKG: sjbc certSha=… files=… manifest=… source=sjbc` | ✅ **已落地**（dev-1456） |
| `sjbc` 的随包文档/MCP 面 | — | **（待落）**（本文件是仓库内第一份 SJBC 指南） |

### 5.1 脚本侧用法（Rhino 口径，2026-11-10 真机实测）

~~~js
typeof sjbc            // 'object'
typeof sjbc.run        // 'function'
var r = sjbc.runEntry('assets/sjbc/hello.sjbc', 0);
r.get('steps')         // 1990      ← 现在返回 Java Map ⇒ 必须用 get()
r.get('ok')            // true
r.get('prints')        // ['fib(10)=55','counter=1,2','sum=12','caught:boom','done']
r.get('certShaUsed')   // '1b57505e…'   ← 实际用来解密的那把证书指纹
~~~

- **⚠️ 用法更正（重要）**：入口返回 **Java `Map`** ⇒ 脚本要写 **`r.get('steps')`**，**不能**写 `r.steps`（会得到 `undefined`）。**W1 正在改成「属性访问可用的 Rhino 友好对象」；落地后本节会改成 `r.steps` 并注明起始版本**（在那之前请用 `get()`）。
- **真机实测**（K40 dev-1456，打包版自己解自己跑）：`runEntry(…,0) → ok=true steps=1990 codeBytes=476 certShaUsed=1b57505e…`；`entry1/2 → steps=5/6`（无打印）；`sjbc.run(path)` 等价 entry0；**与用户 `pc-tools/vm.js` 逐条一致**（比结果 + 步数）。
- **入口目前只有 Rhino 口径**：QuickJS / Node24 未接（§5 表末）。
- **一个踩过的坑**：`runtime.putProperty("sjbc", …)` **只是 `mProperties`、不是脚本全局**（app 版与打包版都实测 `typeof sjbc` / `runtime.sjbc` 为 undefined，而 `runtime.getProperty('sjbc')` **拿得到对象**）⇒ 现由 `__globals__.js` 用既有公共方法读出来挂 `global.sjbc`。

## 6. 语言能力边界（**别写你写不了的代码**）

| 能力 | 状态 |
|---|---|
| 数值/字符串/数组/对象、算术与比较、`if/else/while/for/break/continue`、三元 `c?a:b`、复合赋值、`++/--` | ✅ |
| 函数、递归、闭包（可读写外层）、异常 `try/catch/finally/throw`、`+= -= *= /=` | ✅ |
| 宿主 `print(x)`、`len(x)`、`push(arr,v)`（返回**新**数组）、`slice0(arr,n)` | ✅ |
| 局部变量上限 | **64**（前 7 个进寄存器 `r0..r6`，其余走 `r7` 帧数组） |
| **未实现** | **`class`/`this`/原型链、`switch`、正则、`for...in`**（`交接说明 `4`） |
| 编译器行为 | 无 `return` 的函数**自动补隐式返回**（旧版本会让程序提前结束，已修）；跳转偏移按**真实指令长度**累加（`LOADF` 6 字节，`交接说明 `3-8`） |

> 另有 8 个已修真 bug 的清单（C 侧 `EQ` 指针比较、宿主 `len` 不认字符串、`%g` 科学计数、v5 行式池换行被破坏、具名调用缺闭包环境、寄存器预算过紧、隐式返回、跳转偏移）——见 `交接说明_M1-M17.md `3` 与 `m18__README.md`。**写新脚本前值得扫一眼**。

## 7. 最小验证清单（谁跑）

| # | 步骤 | 期望 | 谁 |
|---|---|---|---|
| 1 | `node tools/sjbc/build-all.mjs --src <源码目录> --out <产出> --cert <apk|hex>` | 每个源出一个 `.sjbc` + `manifest.json`（含 `cert`） | 任意（PC） |
| 2 | `node tools/sjbc/sjbc-pack.mjs <src> <out.sjbc> --cert … --check` | 「解密成功，代码 N B / 池 M 项」+「篡改已被拒绝 ✓」 | 任意（PC） |
| 3 | 宿主自证文件里的指纹 | 与 `--cert` **逐位相同** | 真机 |
| 4 | 点按钮跑模块 | 输出与本地 `vm.js` 一致（含**步数**） | 真机 |
| 5 | **换证书反证**：用别的证书签名再装 | 报**解密失败（AEAD 拒绝）** | 真机（Demo 已实测） |
| 6 | 我们侧回归 | `SjbcV5ContainerTest` / `ProjectConfigSjbcTest`（gradle，Lead 跑） | Lead |

## 8. 未验证 / 待核（诚实边界）
1. **用户两份 md 原文未读到**（device4 `adb offline`）：§三/§四 的**小节号**来自 `tools/sjbc/README.md:4` 的对照说明，**内容按材料与代码重建**；若原文有出入，以原文为准（我可在设备上线后逐条核对）。
2. **`module_v5.enc` 命名不一致**：真机 demo 自证文件当前是 `FAILED: FileNotFoundException sjbc/module_v5.enc`（Lead 实测报），而材料里的模块名形如 `数组与统计.sjbc`/`hello.sjbc` ⇒ **宿主读的路径常量与打包产出的命名需要对齐**（未核）。
3. ~~App 侧 VM/加载器/打包接线三处均（待落）~~ → **已落地并在 K40 真机端到端跑通（dev-1456）**：打包版自己解自己跑 `steps=1990`，`C-PKG: sjbc certSha=…` 与 `C-PKG: template available=true …` 两行日志可核。**仍待落/未验**：① 入口只有 **Rhino** 口径（QuickJS/Node24 未接）；② 用户 Demo `com.aijs.sjbc.demo` 的自证文件仍是 `FAILED`（其宿主找遗留名 `sjbc/module_v5.enc`）；③ `GET_SIGNING_CERTIFICATES`/`GET_SIGNATURES` 只跑了 **API≥28** 那条分支。
4. **__sjbc__ 的运行时安全性未评**：密钥主根是证书指纹（公开信息）+ 固定 salt/info ⇒ 属于**防呆式保护**（防直接读源码），**不防有动机的攻击者**（能从 APK 里拿到证书指纹并复现 HKDF）。这条**不是本次新发现**，但必须写进指南，避免误当成强加密。
5. **本文件未跑任何命令**（只写文档）；`7` 的 1–2 条我**没有执行**（不跑构建/产物的约定），`3–5 条是材料里记录的既有结论。
