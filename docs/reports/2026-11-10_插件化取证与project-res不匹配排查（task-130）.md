# task-130：插件化只读取证 + `project-res.apk` 与宿主资源表不匹配的排查

日期：2026-11-10。约束：**本轮未跑 gradle、未装机、未改任何源码**（B 的修复需要先钉死「谁在报这条错」，见 §2.3）。

## 1. A① 现有插件发现与加载链（取证，file:line）

| 环节 | 证据 | 说明 |
|---|---|---|
| 宿主怎么加载插件 | `apps/app/.../model/plugin/PluginRegistry.java:9-23` | 注释写明：宿主要 `loadDefault(宿主Context, 插件Context, runtime, scope)`，**反射**到插件里的同名静态方法 |
| 插件侧入口 | `apps/app/.../nodeplugin/Registry.java:4` | `public static Object loadDefault(Context host, Context plugin, Object runtime, Object scope)` —— 就是插件必须实现的契约 |
| 包名约定 | `plugin/MiuixPluginFragment.kt:730` | 插件的 `applicationId` = 宿主查表用的包名（插件模块 `plugins/<x>/build.gradle` 的 applicationId） |
| 版本读取 | `plugin/XiangqiPlugin.java:417`、`plugin/ApkBuilderPlugin.java:184` | `mPluginContext.getPackageManager().getPackageInfo(mPluginContext.getPackageName(), 0)` ⇒ 版本从**插件 Context** 取 |
| 开发态自启动 | `apps/app/.../aijspro/App.kt:94` | `registerActivityLifecycleCallbacks(DevPluginAutoStart(this))`（`pluginclient` 包）—— 开发/调试插件的自动装载 |
| 脚本侧入口 | `assets/modules/__plugins__.js` + `assets/modules/qjs-plugins.js:60-68` | `$plugins.load(packageName)`；QuickJS 侧对官方 mlkit.ocr 插件缺位时**降级**到随包实现 |

**⇒ 一个新插件必须满足的契约（最小集）**：⑴ 独立 APK，`applicationId` = 宿主约定的插件包名；⑵ 提供一个类（默认 `…PluginRegistry`/`Registry`）带 `public static Object loadDefault(Context hostContext, Context pluginContext, Object runtime, Object scope)`；⑶ 版本靠插件自身 `PackageInfo`（宿主不额外存版本）；⑷ 插件里需要脚本时把入口脚本放 `assets/scripts/index.js`（`qjs-plugins.js:68` 的错误文案「插件没有入口脚本（assets/scripts/index.js）」就是这条约定）；⑸ 宿主通过 `createPackageContext(插件包名, …)` 建插件 Context（**本轮未逐行核实调用点** ⇒ 记为未核）。

## 2. B（优先）`Node.js 使用 Android 原生界面` 的「资源包与宿主资源表不匹配」

### 2.1 `project-res.apk` 是谁、何时编的（已核）

- **编译**：MCP 工具 `android_resources_compile` —— `mcp/McpApkToolkit.kt:462`（描述：可选 Android AAPT2，把 `res/`+`Manifest` **按指定宿主**编译成 `project-res.apk` 及 sidecar）+ `mcp/AndroidToolkitRuntime.kt:283`（`compiled.zip` → `project-res.apk`）+ `:311`（返回 `path` 与 `sidecar`）+ `:376-378`（**原子写** `project-res.apk` + `project-res.json`）。
- **默认宿主**：`mcp/AIjs-MCP-Guide.md:988`「自动读 project.json 的 `androidResources`，**默认针对本机实际宿主 sourceDir 编译**，原子写 project-res…」⇒ 也就是说：**这份 apk 是「按某一台机器上的宿主」编出来的**，换宿主/换宿主版本就会与新的 host 资源表不符。
- **运行期位置**：`nativeproc/NativeProjectStagingTest.java:23-24` 说明 `build/project-res.apk`（可选 sidecar `build/project-res.json`）是运行期读的；`:43-44` 是它被**暂存/拷贝**进打包产物的证据（`files/project/build/project-res.apk` ✓ 与真机报错里的路径一致）。
- **报错文案的来源**：全仓 java/kt/js/c/cpp grep「资源包与宿主资源表不匹配」**0 命中**（只有 `mcp/AIjs-MCP-Playbook.md:421` 把它当**排障条目**写着）⇒ **这句话的发出点不在引擎/App 的 Java/Kotlin/JS 里**（很可能在 Node UI 桥的原生库或 `assets/node24/ui.js` 的某条分支，或样例工程自己的代码）⇒ **未核**。

### 2.2 为什么会不符（结论）

报错里的两个值 = 「资源包侧记录的**期望宿主资源表**（`8115cbb9…`）」vs「**当前宿主**实际资源表（`ab77cf32…`）」⇒ 说明产物里的 `project-res.apk` 是**在另一台宿主/另一个宿主版本上编的**（或宿主本身换过版本），而运行期校验按当前宿主比对 ⇒ 必然不符。

### 2.3 修法（三步，**我建议按顺序做**；本轮没动代码因为要先钉死发出点）

1. **立刻可用的修复（用户侧）**：对**当前这台设备上的宿主**重新编一次：调 MCP `android_resources_compile`（它会默认按本机宿主 sourceDir 编），产物覆盖工程里 `build/project-res.apk` + `build/project-res.json`，然后**重新打包**。这与 `AIjs-MCP-Playbook.md:421` 的既有指引一致（「按出问题手机的实际宿主重新编译；换 Android 版本不能替代资源表同步，也不要伪改 sidecar」）。
2. **把错误变成可执行指令（建议改文案）**：现在这句话只给了两个 hash，用户不知道该干什么。改成：`资源包与宿主资源表不匹配：请对**当前宿主**重新编译 project-res.apk（MCP: android_resources_compile 或宿主内「资源包」入口），期望=<hash> 当前=<hash>`。**落点未定**（取决于 2.1 的发出点）⇒ 钉死发出点后我 3 行就能改。
3. **加一道陈旧性守卫（防再犯）**：`project-res.json` sidecar 里应当带「编译时的宿主资源表 hash」（`AndroidToolkitRuntime.kt:378` 写的 sidecar ✓）；暂存/装载时**比对当前宿主**，不一致就**早失败并给出上面那句可执行指引**（或自动触发重编）。这条能同时覆盖「同工程换设备」「宿主升级」两类现场。判据（真机）：改宿主后首次启动即给指引句，而不是深层失败。

**当前缺的唯一输入**：真机产物里 `files/project/build/project-res.json`（sidecar）的内容 —— 有它就能确定「发出点 + 期望值来源」，我下一步就能落 2/3 两步。

## 3. A② OCR 能否插件化（方案 + 代价 + 依据）

**硬约束（依据）**：Android 的 `System.loadLibrary("ppocr")` **只从「调用方 App 自己的 `nativeLibraryDir`」解析**，不能直接加载**别的 APK** 里的 `.so`（`mcp/McpTools.kt:212` 那条 dlopen 粘性说明、以及 MCP 里大量「dlopen/加载路径」经验条目都指向同一事实）。要加载插件里的库，只能把 `.so` **落到宿主可读路径**再用**绝对路径** `System.load(path)`（或由宿主 `dlopen` 绝对路径）。

| 方案 | 做法 | 代价 | 依据/风险 |
|---|---|---|---|
| **A（推荐）插件带库 + 宿主拷贝后加载** | 插件 APK 带 `libppocr.so`/模型；宿主在插件安装后把 `nativeLibraryDir` 下的 `.so` 拷到 `files/plugins/<pkg>/lib/…`，再 `System.load(绝对路径)` | 一次拷贝（3.9 MB 级）+ 需要宿主新增「插件库加载」钩子；升级插件时要重拷 | 与现有 `nativeproc` 暂存手法同源（`NativeProjectStagingTest` 就是「拷贝 + sidecar」的模式） |
| **B（改动最小）`$ocr` 变分发器** | `$ocr` 保持内置实现；装了 OCR 插件时**优先** `$plugins.load('…ocr')` 走插件，否则回落内置（**默认行为不变**） | 需要 `__ocr__.js` 加一层分发 + 插件实现 `RecordDefault` 契约；包体**不减小**（内置库还在） | 与 `qjs-plugins.js:60-68` 既有的「插件缺位 ⇒ 降级内置」是**同一套语义**，可直接复用 |
| **C（只拆模型）** | `.so` 留在宿主，只把 **OCR 模型（mlkit 42 个文件 ≈2.49 MB）** 做成可下载插件 | 节省有限；实现最快（模型本来就在 `assets/**`） | 证据：task-117/瘦身清单里 OCR 资产分两桶（模型 2.49 MB vs 库 ≈17 MB 未压缩） |

**结论（有依据）**：**能插件化，但要宿主提供「插件库落盘 + 绝对路径加载」这一钩子**；否则插件里的 `.so` 根本进不了进程（方案 A 的前提）。若目标是「包体变小 + 可按需下载」，**A 是唯一真正减包的路径**；若目标是「快速上线插件而不动宿主」，**B 最小且风险最低**（但包体不变）。**建议：先 B 把「插件优先、内置回退」的分发语义立起来（复用现有降级链），再按 A 把两个 `.so` 与模型移出宿主**。

## 4. A③ 历史更新机制现状（已核 + 未核）

- **插件下载分发已不在模板里**：`apps/app/src/test/.../MiuixBuildActivityTemplateActionTest.kt:28` 断言「不得再引用 `PluginDownloads`」⇒ 说明**旧的 `PluginDownloads` 通道已被移除/弃用**，现状是「插件=APK，靠安装/更新」而不是「宿主内下载」。
- **宿主更新通道**：仓库里既有 `update.json` + `.artifacts/updates/**`（LAN 更新源，AGENTS 红线里明确「不删 `.artifacts/updates/**`」）+ `tools/publish.ps1`（发布脚本）⇒ **插件若要「像 C/C++ 那样快更新」，可以复用这套「清单 + 制品 + 增量」的骨架**，但要新增：插件清单（包名/版本/abi/大小/sha256）、公网基址、断点续传/校验、以及「安装中不可用」的状态呈现。
- **未核**：`GitHubUpdateProxy` 的实际实现与是否可用于插件公网下载；`tools/publish.ps1` 现在具体发布什么（宿主 or 也含插件）；`.artifacts/updates/**` 的清单格式与增量能力。这三条我没读（本轮只读窗口用在了 B 上）。

## 5. 未验证边界（照实）

1. **B 的发出点未核**（引擎/App 的 java/kt/js 0 命中）⇒ 因此**本轮一行代码都没改**；给我真机 `project-res.json` sidecar 或让我在 Node UI 桥/原生库那一侧再挖一轮即可定位。
2. `createPackageContext` 的实际调用点未逐行核实（A① 的 ⑸）。
3. A③ 的三处未核项（上节）。
4. 本轮**没跑 gradle/没装机**：B 的「重编后是否即好」需要真机验证（判据：重新打包后启动 `Node.js 使用 Android 原生界面`，不再出现资源表不匹配；或按 2.3.3 改完后首启即给可执行指引句）。