# MCP 实战要点（操作手册补充）

> 来源：2026-11-02 在 192.168.10.4（Android 16 / arm64 / 无 root / MIUI）的实战记录。文件/APK 工具说明于2026-11-03更新，参数与预算以当前设备返回为准。
> 本文件随 APK 打包，`learn_aijspro_skill` 会把它追加在完整说明后面；参数仍以 `tools/list` 的 inputSchema 为准。

## 冷进程与库状态（最容易浪费时间，先读）

- `run_script` 会复用常驻脚本进程：dlopen 过的 .so、`isAvailable()` 结果都会粘住。换 .so 或做对照实验前，先 `stop_all_scripts` 再 `run_script`（冷进程）。
- 必须先 `setHostContext(宿主)` 再第一次调用 `isAvailable()`；顺序反了会把「不可用」缓存进该进程，之后恒为 false。
- AutoX 系 `NativeEncryptor` 从 `Context.getAssets()` 读 `autox/native/<abi>/*.so`，走 java-memfd 加载，**不落盘**；改 `files/autox_native/` 缓存文件无效，要换 .so 只能换 AssetManager 指向的 zip/APK。
- 进程内读 `/proc/self/maps`、`/proc/self/mem` 会被拒绝（空 / EACCES），不要拿它做内存取证。

## 大输出与文件传输

- `terminal_*` 现在有结构化错误码：`JOB_NOT_FOUND`（registry 无记录）/`JOB_EVICTED`（结果被保留窗口淘汰，清单见 `terminal_list.evicted[]`）/`SERVICE_RESTARTED`（服务换过实例）；`terminal_list` 另给 `retention/tombstoneCount/ownershipEnforced:false`。
- terminal 只保留最近一段：`terminal_exec` 拿 `jobId` → `terminal_read(offset=上次 nextOffset, maxChars≤65536, waitMillis≤10000)` 轮询到 `running=false` 且 `nextOffset==outputEnd`。
- `workspace_open/write/request_apply` 只支持部分文本类型（`.mjs`/`.so` 会被拒）；二进制或 `.mjs` 用 `terminal_exec` + base64（分块 8KB echo 追加后 `base64 -d`）。
- 设备端之间优先 `cp`，不要经 PC 中转；中文文件名交给设备端 shell，别在 JS 里逐字符拼（会乱码）。
- 写文件先 `mkdirs` 建目录；app UID 可直接读写 `/storage/emulated/0`。

## 方法学（逆向/解密类任务）

- 找解密入口优先选**无状态/自包含**的 API（每次独立解一段），不要一上来碰需要维护流/MAC 状态的状态机 API——无 root 无法内存取证时后者极难复现。
- 关键数字（长度、字节序）先实测再信文档；旧文档可能过时（例如 64 vs 72 字节）。
- 同脚本两次结果不同，通常是进程复用 + native 全局状态残留：每次 `nativeReset()` + 冷进程，一次只验一个变量。

## 调用习惯

- 相对路径相对「操作目录」（脚本根 `/storage/emulated/0/脚本`），不要写 `/sdcard` 绝对路径；先 `list_scripts` 看清根目录。
- **产出目录（必须）**：所有 APK/ZIP/反编译/截图/证据放 `<root>/<项目名>/`；没有先 `project_folder {action:"create",name:"…"}` 再 `set`。裸文件名或写在根目录会被自动归置并返回 `organized:{from,to,projectDir,reason}`——看到它说明原本写错位置，下次直接传项目目录；`organize:false` 可显式关闭。
- 需要 shell（uid=2000，可 `pm`/`am`/`dumpsys`）时用设备上的 rish（`terminal_exec backend="shizuku"` 或 `shell_rish`）；terminal 默认是应用 UID，跑不了 `pm`。
- 文件/APK 工具全部本地实现：先 `toolkit_status`；APK 按 `apk_list_available_apks` → `apk_workspace_open` → `apk_session_create` → 读取/搜索 → 修改 → `apk_session_check` → `apk_build`。旧 `mt_*` 名称只是 dispatch 兼容别名；公开写法和 URI 用 `aijs://current-apk` / `aijs://workspace/<id>`，不需要另装 MT。
- 浮窗：Node 用 `require("floating_window")`；坐标/根布局看 Guide「Node floating_window 坐标系与根布局（.4 实战反馈）」——根 `layout_*` 会被 WindowManager 无视，根 `match_parent`，窗口大小用 `setSize`/`initialSize`。
- 外部 MT 管理器 MCP 只作历史对照，不是本机工具链依赖。APK 工具用于静态查看和编辑，不执行目标 APK 的代码；脚本和终端由各自工具管理。

## 返回结构与提前加载速查（W-G）

### 扁平标量工具的两种读法

get_status / toolkit_status / get_build_tools 新版本同时给两种读法：content[0].text 是旧扁平 JSON（老代码不变），structuredContent={ok:true,data:{...}} 是同一批字段（新代码读 data）。当前构建若还没有 data 镜像，继续读 content[0].text；get_status/get_build_tools 已加镜像，toolkit_status 走 :tools 桥接，若该构建尚未镜像同样读 text。

### 参数类错误的 error.details

INVALID_ARGUMENT / 缺参数时，message 仍有人类可读的「可用参数/必填」；新版本会在 structuredContent.error.details 提供 {tool,availableParameters[],required[],schemaTool:"tool_schema"}，可能附 schemaArguments。机读读 details，不解析 message；旧构建没有 details 时退回 message + tool_schema。顶层 details 可能镜像，以 error.details 为准。

### get_status.mcp（服务自检第一入口）

连接被拒 / 超时 / 静默无响应时，**先看 `get_status.mcp.listening`，不要先怀疑网络**：

- `listening`：**进程内真实监听** = 服务实例活着 **且 accept 循环在跑**（`listener.alive`），**不是心跳推断**；
- `running`：**与 `listening` 同源**——`listening=true` 才 `running=true`。进程被回收或 accept 循环死掉时，**同一次响应里 `running` 不会再是 true**；旧实现直接回持久化布尔，只看 `running` 的调用方会误判；
- `persistedRunning`：持久化布尔（**旧 `running` 的值**）。它只说明「上次记录是运行中」，**不能当健康判据**（进程被回收后仍可能为 true）；
- `status`：`starting|running|stopped|error|degraded|stale`。**`degraded` = 服务实例还在、但 accept 循环已经死了**——占着端口却不再 accept 的「静默僵尸」，此时 `listening=false`、`listener.alive=false`，必须看 `listener.lastFailure`；
- `listener{alive,failureCount,degraded,lastFailure}`：accept 循环现场。`alive`=循环在跑；`failureCount`=监听侧失败次数；**`listener.degraded=true` 只表示「失败过」（`failureCount>0`），不代表循环已死**——不要与 `status=degraded` 混为一谈；`lastFailure` 形如 `respond_503: OutOfMemoryError: …` 或 `prepare_socket: <异常类>: <消息>`（写 503 响应失败连 `Error` 也记录、且不再让循环退出）；
- `fresh` / `heartbeatAgeMs`：心跳新鲜度（5s 一跳、10s 超时；心跳跑在主 looper 上，主线程被阻塞时可能短暂为 false）；
- `state`：持久化的显式状态 `starting|running|stopped|error`——**与顶层 `status` 不是一回事**；
- `startReason` / `autoStarted`：本次是 `auto`（回前台等自动拉起）还是 `manual`；
- `port` / `localUrl` / `lanUrl` / `updatedAt` / `lastError`；`heartbeatIntervalMs=5000`、`heartbeatTimeoutMs=10000`；
- `foregroundService:true` / `sticky:true`：前台服务 + `START_STICKY` 保活；
- `recovery`：**只有** `listening=false` 时才出现。文案已含「若刚刚启动失败过（例如端口被占），自动拉起会在 **30 秒**后重试，期间请在 MCP 页面手动开启」——旧的「失败过就永不再试」已废（失败后冷却 `RETRY_AFTER_MS=30000` 再允许自动拉起，手动开启不受冷却限制）。

**判读顺序（照这个来）**：

1. 先看 `listening`。`true` → 服务在 accept（通常 `status=running`）；若此时仍连不上，核对 `port` 是不是你连的那个，以及 ADB forward / 局域网地址。
2. `listening=false` + `status=degraded` → **服务实例在但 accept 循环死了**（看 `listener.alive=false` 与 `listener.lastFailure`）→ 重开 App / 重开 MCP 服务，不要当作网络问题。
3. `listening=false` + `status=stopped|stale|error` → **App 进程没在跑或服务没起来**；`recovery` 会说明 30 秒后自动重试，也可在 MCP 页面手动开启。
4. **任何时候都不要用 `persistedRunning` 判健康**（它不含 accept 循环状态，进程被回收后也可能为 true）。

> 版本边界：`running` 同源、`persistedRunning`、`listener{...}`、`status=degraded`、`recovery` 的 30 秒冷却都是 **dev-1414 起** 的语义。**dev-1413 及更早为旧契约**：`running` 是持久化布尔、没有 `persistedRunning`/`listener`、`status` 没有 `degraded`（那时 `recovery` 也没有 30 秒冷却）。

（顺带）`get_status` 还有 `toolCatalog{toolCount,build,fingerprint}`、顶层 `toolCount`/`canonicalToolCount`（**dev-1433** 实测 **225 / 216**）、`template{source,path,sizeBytes,sha256,engineRequireRootFix,templateStale,warnings,nextActions}`、`toolCategories{}`、`baseline{}`/`jobs{}`/`limits{}`/`outputConventions{}`。

### HTTP 503 不是工具失败

连接队列满（**4 个 worker + 16 个排队位**）时，服务返回 **503** + 结构化 JSON body：

```json
{"error":{"code":"service_busy","message":"MCP 连接队列已满（等待上限 16），本次请求未进入处理","recoverable":true,"retrySameArguments":true},"errorCode":"service_busy","recovery":"稍后重试；若持续出现，先 get_status.mcp 看服务状态（listening/port/lastError），必要时重开 App 让服务拉起","retryAfterMs":1000}
```

- `error.code` 与顶层 `errorCode` 都是 `service_busy`（形状与**工具失败信封**一致：`error` 是对象 + 顶层 `errorCode`）；
- 按 `retryAfterMs`（1000ms）重试即可；
- **503 不代表真实负载**：它数的是「同时打进来的连接数」，不是「工具在忙」，更不代表某个工具真的在执行；
- 状态行是 `503 Service Unavailable`（旧构建曾错写成 `503 Bad Request`）；
- 持续 503 时先 `get_status.mcp` 看服务状态并降低并发，不要当成工具 bug 反复重试。

### apk_inject_gadget.preloadMode=provider|dt_needed|clinit

preloadMode 有三个取值：provider（默认）、dt_needed、clinit（**dev-1416 起**；dev-1414 及更早只接受前两个）；providerProcess 是另一个字段（决定 Provider 跑在哪个进程），不要混。

- provider：ContentProvider.onCreate 路径，在 Application.attachBaseContext 之后，对加固壳可能太晚；成功时 preload.earlyLoad=false / preload.preloadImplemented=false，不能声称已提前加载。
- dt_needed（支持时）：可选 preloadTarget 指定目标 .so（省略自动选择第一个可安全 patch 的 lib/*/*.so，排除 gadget/config）；就地给 .dynamic 增加 DT_NEEDED=libaijsgadget.so、.dynstr 追加库名或复用同名串，让 linker 在壳之前加载 Gadget。成功保证：只用已有可替换 DT_NULL 空槽（保留终止符）和 .dynstr 尾部 NUL 余量，不移动节/PT_LOAD、文件大小不变；原子写出，失败不留半坏产物、不改原文件。成功看 preload.applied/earlyLoad/preloadImplemented/sizeUnchanged/verified/targetPath。
- 不支持/失败时：顶层通常 PRELOAD_DT_NEEDED_FAILED（旧版/模式不可用可能 PRELOAD_MODE_UNAVAILABLE），错误信息里带 preload 计划（applied=false）；reason 举例 DYNAMIC_NO_SPARE_NULL、DYNSTR_NO_ROOM、NO_DYNAMIC_SEGMENT、NOT_ELF、UNSUPPORTED_ENDIAN/UNSUPPORTED_ELF_CLASS，完整清单看错误/计划里的 preload.targets[].reason/reasons，不要当全集。不要伪造成功；对目标 .so 副本用外部 patchelf --add-needed libaijsgadget.so --page-size 16384 <目标.so>（page-size 按目标/设备页大小），回填 APK、重签并 apk_verify 复核。
- 必须回退外部 patchelf 的情形：目标不是动态 ELF、没有 .dynamic、没有空闲且可替换的 DT_NULL 槽或 .dynstr 没有 NUL 余量、需要移动节/段/PT_LOAD、字节序/架构不支持，或返回 PRELOAD_DT_NEEDED_FAILED 及任何细分 reason；不要反复试到看起来成功。
- 边界：目标 .so 必须在壳初始化前被 linker 加载；多 .so/多 ABI/分包、自定义 linker、页对齐不满足不保证成功。若返回失败，不会生成半成品。
- clinit（**dev-1416 起**，三条里最早）：生成 Application 子类 `com.aijs.instrumentation.AijsApplication`（`.super`=原 `application@name`），其 `<clinit>` 里 `System.loadLibrary("aijsgadget")`（整段 `.catch Ljava/lang/Throwable;` 只 `Log.e`，失败不崩 App），作为**新 `classesN.dex` 追加**；Manifest **只改 `application@name`**，不重编资源、既有 DEX 一字节不动。可选 `clinitTarget` 覆盖父类（省略读 Manifest，未声明用 `android.app.Application`）。时序：保证早于 `attachBaseContext`，但父类先初始化（JLS 12.4.2）→ **不保证早于加固壳自身的 native 初始化**；返回 `runtimeVerified=false`/`verificationScope=static_dex_manifest_only`，不要当成运行时已验证。成功看 `preload.applied=true`/`earlyLoad=true`/`preloadImplemented=true`/`method=application_clinit_loadLibrary`/`clinitClass`/`originalApplication`/`targetSource`；失败码 `PRELOAD_CLINIT_UNSUPPORTED_APPLICATION`（父类 final/接口/不在 `classes*.dex` 里/声明了 `appComponentFactory`/已存在同名生成类）、`PRELOAD_CLINIT_MANIFEST_PATCH_FAILED`、`PRELOAD_CLINIT_DEX_ASSEMBLY_FAILED`，**失败不产出 APK**。

## 文件与 APK 工具：读取 → 修改 → 检查 → 构建

- 快照/会话：`apk_workspace_open` 建不可变快照（已有 APK 上下文可用 `aijs://current-apk` 取回，没有上下文时先按实际路径打开；已保存工作区用 `aijs://workspace/<id>` 且 `temporary=false`），`apk_session_create` 建写时复制会话。会话不会改源 APK。
- 先读后改：`apk_read_text`/`apk_read_bytes`/`apk_resource_read` 返回 `targetVersion`；DEX 类/方法用 `apk_read_text` 的 `dex_*` 定位符读取文本；`apk_edit_text`/`apk_resource_edit` 原样传回。`apk_read_text` 的 `offset`/`nextOffset` 是 **Unicode 码点**，普通 `zip_entry:` 文本也会真正按 offset 切；续读优先用 `nextOffset`，`offset` 与 `startLine/startColumn` 二选一（同时传 offset 优先）。`apk_edit_text` 新建目标用 `targetVersion="missing"` + 单个 `write_target`，且 `write_target` 的 `matchText` 必须为空串（可省略，默认空串）。看到“内容已改变”重新读取，不要重试旧参数。
- 输出路径坑（dev-1374）：`apk_build.outputName` 只接受**纯文件名或绝对路径**，相对子目录（如 `autojs_crack/_mcp_test/x.apk`）会被拒；`apk_inject_gadget.outPath` **不建父目录**，父目录必须已存在。先 `file_create_directory` 建目录，再传绝对路径；详见 Guide「dev-1373 已修」的 #18/#12。
- 两个易错参数（B12）：`zip_create` 的**文件源** `entryPath` 不能以 `/` 结尾（传 `脚本/` 会报错）；输出 ZIP 不能位于任一源目录内。`file_edit_text` 新建文件必须 `targetVersion="missing"`（`"new"` 会报 INVALID_ARGUMENT），且 `edits` 只能有一个 `write_target`；`write_target` 的 `matchText` 必须为空串（可省略，默认空串），其它模式必须唯一匹配。详见 Guide「dev-1380 手册补丁」B12。
- gadget 瘦身/partial（dev-1379 更正归因）：`detail=summary` 只回关键字段（同一云镜样本 42 KB→480 字符，**不含 `partialReasons`**）；看 `partialReasons[].entry`/`scope`（`target`=目标包自带的库，`injected`=本工具注入的 Gadget；`upstream`/`impact` 辅助）必须用 `detail=full`。K40 云镜样本 5 条实为 target 的 `lib/arm64-v8a/libroot_automator.so`（3 段）+ `libyj_aj_02_309.so`（2 段），**未注入原包同样 5 条**；注入的 `libaijsgadget.so` arm64 3 段全过。① target 库只能由目标方用 NDK r27+ 重编，不要直接改 `p_align`（32 位段不同余，要连带 p_vaddr/p_offset 或重链接）；② injected Gadget 只有 32 位 ABI 不合规，64 位已合规，16 KiB 设备是 arm64、当前无需重编；③ 4096 页设备不影响加载（K40/`.4` `getconf PAGE_SIZE=4096`，无真实 16K 加载失败证据，16K 硬件仍需实测）。验证：full 注入看 `entry/scope`，再对原包 vs 注入包跑 `apk_verify` 对比 native 告警是否仅在注入后新增。
- 分页：列表/搜索/native/资源 xref 最多 200/页，返回 `nextCursor`，用 `apk_continue` 续页；游标 5 分钟过期且绑定会话 revision，编辑后必须重查。不能把两页拼成一份视图。
  - **例外（游标归属）**：`apk_dex_list_classes` / `apk_dex_list_methods` 的 `nextCursor` 是**原工具 cursor**，必须原样回传给同一个工具的 `cursor` 字段（同过滤条件、同 workspace/session/revision），**不能传给 `apk_continue`**；反之 `apk_continue` 的游标也不能回传给这两个工具。
- 检查/构建：先 `apk_session_check(runBuildChecks=true)`，再 `apk_build`；构建会再检查，失败不导出。默认未签名；`sign=true` 用本机 AI.js Pro 身份重签并移除原签名。`apk_read_signature` 只读 V1/V2/V3/V3.1 证书元数据，`verified=false`，不是验签。
- 搜索/正则：搜索和文件替换使用 Java `java.util.regex`（支持环视/反向引用），每个 matcher 默认 750ms、最多 8M 字符访问，并受 3s/8s 总预算和中断检查约束；超预算报 `REGEX_BUDGET_EXCEEDED`。先加 prefix/缩小目标，别用灾难性回溯。
- ZIP：读取支持 ZipCrypto/AES，写入只生成 AES-256 或明文；`zip_test` 才做完整 CRC/HMAC 校验，`zip_read_*` 只是预览。大包先看 50k 条目/512MiB 扫描预算、250:1 压缩比和 2GiB 创建预算；**加密**条目才必须传非空 `password`（未加密条目可省略或传空字符串，dev-1368 起不再强制）。
- 资源：`apk_resource_read` 每项独立错误，默认 `variant=default`；`maxValueXmlChars` 超预算时 `valueXml=null`，不要写回半截 XML；`resolveDepth=1` 只解析一级直接引用。AXML/资源 XML 的写入/编辑拒绝 DOCTYPE/ENTITY。**AXML/AndroidManifest 不在 `apk_resource_*` 范围内：读用 `apk_read_text(locator="axml:...")`，改用 `apk_edit_text` 同一 locator + targetVersion。**
- 原生：指令分析在原生库可用时用 Capstone 覆盖 AArch64/ARM/Thumb/x86/x86-64，不可用时只回退有界 AArch64 子集。指令补丁支持四架构，但内置汇编器只有 AArch64 常见子集；ARM/Thumb/x86/x86-64 或内置未覆盖形式要可选 clang（`aijs-toolchain install cpp`）。字符串增长只利用已有 NUL/空格 padding，不改长度/指针/重定位。
- 容量：APK 快照单包 512MiB、总量 1GiB；单条分析 64MiB、文本 10MiB；单工作区 8 会话、2000 变更条目/512MiB。旧 `mt_*` 名称不注册为规范工具。

## Frida 17 / Interceptor / Gadget 避坑（P0-3 / P1-1 / P1-3）

### Frida 17 API 迁移（遇到 TypeError: not a function 先查这里）

- Module 静态方法在 Frida 17 被移除，不代表目标导出不存在：
  - Module.getExportByName("libc.so","open") → Process.getModuleByName("libc.so").getExportByName("open")
  - Module.findExportByName(...) → Process.getModuleByName(...).findExportByName(...)
  - Module.findBaseAddress("libc.so") / Module.getBaseAddress(...) → Process.getModuleByName("libc.so").base
  - Module.getSymbolByName(null,"open") → Module.getGlobalExportByName("open")
  - Module.enumerateExports(...) → Process.getModuleByName(...).enumerateExports()
  - Interceptor.attach(Module.findExportByName(...), cb) → Interceptor.attach(Process.getModuleByName(...).getExportByName(...), cb)
- Interceptor.attach/replace/revert 和 onEnter/onLeave 回调本身没有改名；变的是目标指针的解析方式。
- 先缓存 const libc = Process.getModuleByName("libc.so")，再多次 libc.getExportByName(...)；不要每次重复查模块。
- TypeError 可能是“静态 API 被移除”，不是拼写错误，也不是目标函数缺失。

### Interceptor 两个隐蔽陷阱

(a) 不要用 onLeave 的 x0 判断被 patch 的极短函数（如 mov w0,#1; ret）是否生效：入口 trampoline 可能让 x0 读到临时值。应直接调用并检查返回值，或反汇编/核对入口字节。

(b) 不要 attach 到调用指令地址（如 blr x27）：Interceptor 会改写该指令，目标函数可能根本不被调用。只 hook 函数入口；需要观察调用者用 Stalker，不要改调用点。

### Gadget on_load=wait 与 resume 的代码事实

- 当前 ToolkitGadget.prepare 对 listen 和 script 都固定生成 on_load:"resume"（ToolkitGadget.kt:74、83-88）；用 apk_inject_gadget 生成的包不会因 wait 卡在启动早期，也不需要手动 resume。
- Frida 上游默认 on_load:"wait" 会阻塞目标启动，直到控制端明确 resume；我们的 frida_runner.c 只做 attach/load/eval/unload/detach，没有 frida_session_resume（frida_runner.c:1019 attach、:1190-1192 detach）；代码里既没有 %resume 也没有 session resume，detach 不是 resume，所以 wait 包需要外部控制端放行或改回 resume。
- 手工改成 wait 时：不要依赖断开连接自动恢复；unload/detach 不是 resume。用支持 resume 的 Frida 控制端（CLI %resume / API Session.resume() 或 Device.resume()），或改回 on_load:"resume" 重新注入。上游：https://frida.re/docs/gadget/
- 想要“启动早期加载脚本”而不是“启动时阻塞”：优先 interactionType=script（同样 on_load:"resume"），不要用 wait。

## 新工具速查（dev-1422~1433）

四个逆向/构建向 + 一个 UI 向。**参数以 `tool_schema{tool:"…"}` 为准**，这里只给「怎么用对 + 别踩什么」。

- **`vm_survey`**（dev-1422）：静态找 dispatch 壳候选（`candidates[].score/contributions`）、opcode 聚类、大常量负载、dispatch 数组、按 `opcodeRules` 线性反汇编、只到块起点的 CFG。**只给 `blobHex`(+`opcodeRules`) 就是 blob-only**（不扫 DEX、不需要 workspace）；`scoreBuckets` 与 `candidateCount` 同集合、不被 `limit` 截断。**0 候选是合法结果**（返回会写扫了多少 dex/类/方法）；不做反编译、不承诺一键还原；权重未用真实 VMP 样本校准。**可用 `weights{switchCount,switchCases,dispatchLoopBackEdges,arrayIndexOps,invokeCount,instructionCount,classPayloadBytes}` 自行校准（dev-1430 起）**：返回 `weightsSource=default|custom` + 生效权重表 + `scoreFormula`；**封顶 100 会并列，校准看 `rawScore`（不封顶）**；负数/非整数/**全 0**/未知键 → `INVALID_ARGUMENT`（列可用键），**显式 0 = 关闭该项**（仍出现在 `contributions`）。→ 别把候选多当壳多，别把分数当结论。
- **`dev_screen_diff`**（dev-1424）：与 `capture_screen` **同一条截图管线**（`captureMode` 同套预设），**无必填**；不传 `baseline` 就与进程内上一帧比。返回 `changedPixels/ratio/bbox/maxChannelDelta/frameSha256/baselineSource/sizeSource`。**`ratio` ≠ 界面变化程度**；尺寸不一致**直接 `SIZE_MISMATCH`**（不裁剪不缩放，等比时给可抄的 `suggestedArguments`/`copyableCommand`）。→ 第一次报 `NO_BASELINE` 属正常，**同参数再调一次**即可。
- **`entry_`**（dev-1425）：一次拿入口画像（`application` 含 `appComponentFactory`、`launcher` 含 `activity-alias` 声明值/展开值、`exportedComponents` 含 `android:process`、`nativeEntryHints`、`preloadRecommendation`）。参数 `path` 或 `workspaceId` 二选一。**纯静态、`runtimeVerified=false`**；**「没找到 JNI_OnLoad」≠「没有 native 初始化」**；建议与 `apk_inject_gadget` **同源**（`reasonSource` 回显），但注入时以 `PRELOAD_*` 失败码为准。→ 别把建议当保证。
- **`chain_resume`**（dev-1426/1427）：`checkpoint → 回编 → 对齐/签名 → 验签`。必填 `workspaceId`+`editSessionId`；`steps` 默认 `all`、`checkpoint` 必须配 `expectedRevision`。**任一步失败返回 payload `ok=false`（不抛异常）——必须看 `ok`**，另有 `stoppedAt`/`failure{where,phase,code,message}`/后续 `skipped`；`finalArtifact.sha256` 是磁盘实测，**`verified` 只来自 verifier 逐方案结论**（≠「签过名」）。dev-1427 起「Manifest 缺资源上下文」不再阻断构建（转 `warnings[]` 的 `MANIFEST_PRIMARY_DECODER_FALLBACK`）。→ 别把 `schemes` 存在当验签通过。
- **`crypto_`**（dev-1428）：`op=hash|hmac|aes_gcm|rsa_sign|rsa_verify`（只 **`op` 必填**）；输入 `inputHex|inputText|path` 三选一；密钥**只允许显式** `keyHex` 或**工具目录内** `keyRef`（不读 App 私有密钥库，只回显 `keyBytes/keySha256Prefix`）。失败码 `ALG_UNSUPPORTED/KEY_INVALID/MAC_INVALID/DECRYPT_FAILED/INPUT_TOO_LARGE`，**失败不留半成品**（校验+运算都在写盘前）。不做 KDF/PSS/CBC/密钥管理；GCM 通过只说明 tag 校验通过，**不构成信任判断**；仅私钥时用「重签后常量时间比较」。→ `keyRef:"../x"` 会 `KEY_INVALID`；`inputHex` 与 `path` 同给会 `INVALID_ARGUMENT`。

- **`get_baseline`**（dev-1431）：一次拿运行时就绪——`version`/`root`/`currentProject`/`outputRule`/`workspaces{script,apk{used,quota,temporary,ttlHint}}`/`toolchain{sampled,ready,missing,present}`/`jobs{retention,advice}`/`recovery`。**≤512 字节**、内容不变 `version` 不变（可去重）、`refresh:true` 强刷；**同源**：`toolchain` 与 `get_build_tools`、`workspaces.apk` 与 `apk_workspace_inventory` **逐字相等**。同一条基线自动出现在 **`get_status` + 首个 `tools/call`**（完整版）与**所有失败信封**（裁剪版 `version+recovery(+missing)+trimmed`）；`tools/list` 与普通成功调用不带。→ 读失败信封的基线**以 `structuredContent` 为准**（`content[0].text` 镜像暂不含 baseline，正在修）。
- **`apk_job_result` 文本型任务默认 `format:"text"`**（dev-1431）：`content` 直接是正文（要旧形状显式传 `format:"json"`）；新增 `formatDefaulted`/`formatSource`/`pagingSource`。**`totalChars` 现在是正文长度**（旧=原始 JSON 长度），要原始大小看 **`rawTotalChars`**；`nextOffset` 用法不变。
- **警告收窄**（dev-1431）：只有真 warning（正文为空 / `EMPTY_RESULT` / `RESULT_TRUNCATED` / 含 JADX ERROR 或 Method dump skipped / 非 `DECOMPILE_INCOMPLETE` 的 `warningCode`）才 `SUCCEEDED_WITH_WARNINGS`；**JADX INFO 与全局计数只进 `notes`（`notesCount`，`noteCount` 是旧别名）**、不改变 `SUCCEEDED`；判据原文在 `warningCriteria`、来源在 `warningSource`。

**族前缀别名（dev-1429）**：`dex_*` → `apk_dex_*`、`so_*` → `apk_native_*` 共 **16 个**，**只派发不广播**（不进 `tools/list`，advertised 仍 **225**）；新旧名等价、旧名长期保留。查参数：`tool_schema{tool:"dex_xref"}` → `resolved=apk_dex_xref` + `familyAliases[]`。

## 附录 A · 坑表（症状 → 根因 → 正确做法）

| # | 症状 | 根因 | 正确做法 |
|---|---|---|---|
| 1 | 换了 .so 毫无变化 | 脚本进程复用，dlopen 的库粘住 | 先 `stop_all_scripts`；用 pid 探针确认换了进程 |
| 2 | `isAvailable()` 首次 false 后永远 false | 首次调用（宿主未设好）失败被缓存 | 先 `setHostContext` → 再首次 `isAvailable` |
| 3 | 覆盖 `files/autox_native/<abi>/x.so` 无效 | java-memfd 从 assets 读，不落盘 | 换 AssetManager 指向的 zip（现场用 `java.util.zip` 造） |
| 4 | 同脚本两次结果不同 | 进程复用 + native 全局状态残留 | 每次 `nativeReset()` + 冷进程；一次只验一个变量 |
| 5 | 读 `/proc/self/mem` 失败 | 应用域 SELinux 拒绝 | 改用行为差分（喂错误输入看返回值变化） |
| 6 | terminal 输出丢尾巴 | 只保留最近一段缓冲 | 按 `offset=nextOffset` 轮询到结束 |
| 7 | `workspace_write` 报「只能新建支持的文本文…」 | workspace 限文本类型 | 文本走 workspace，二进制/`.mjs` 走 base64 |
| 8 | `apk_*` 返回 INVALID_ARGUMENT（缺 maxInstructions/maxBlocks/limit） | 需显式分页/预算参数 | 先看 schema；用 `apk_continue` 续页 |
| 9 | dex locator 被拒 | 本地 DEX 定位符需要 JVM 描述符 | `dex_class:Lcom/example/Foo;` |
| 10 | 中文文件名变乱码 | JS 里逐字符拼 UTF-8 | `new java.lang.String(bytes,'UTF-8')` 或设备端 `cp` 改名 |
| 11 | 文档里的长度/字节序对不上 | 旧文档未复核（如 64 vs 72 字节） | 先实测 |
| 12 | try/catch 抓不到 Java `Error`（旧版本 dev-1373 及之前，反馈 #16）；dev-1374 起已修 | 旧 Rhino 不捕获 Error；dev-1374 起在 Java 调用边界启用 feature13，普通异常和 Error 均可 catch（`OutOfMemoryError`/`ThreadDeath` 也会被放开） | 当前版本 catch 可用于记录；仍先 `files.exists`/`java.io.File` 检查、用 `java.lang.System.load(绝对路径)`，不要只用 catch 判断可加载性 |
| 13 | `System.loadLibrary("foo")` 找不到自己拷进来的 SO（.4 实战反馈） | `loadLibrary` 只查系统/Java library path，不接受绝对路径；SO 需要绝对路径加载 | 用 `java.lang.System.load("/绝对路径/libfoo.so")`；文件放当前进程可读且 SELinux 允许执行的位置（`/data/local/tmp` 能否加载随 ROM，先验证；共享存储 `/sdcard` 通常不可执行），ABI 必须匹配 |
| 14 | `pm install -r /storage/...apk` 报 `No packages staged`/读取失败（.4 实战反馈） | FUSE `/storage` 不能直接喂 PackageManager；app UID 也写不了 `/data/local/tmp` | 先用 `shell_capabilities` 确认 cp/pm 后端；用 shizuku/root shell 先 `cp` 到 `/data/local/tmp`，再 `pm install -r`；PC 用 `adb push`；详见 Guide「安装测试包」 |
| 15 | 反馈 #14：`Math.abs.invoke(null, 5)` 在 Rhino 里不是 Method（旧版还报 `argument 1 has type int, got java.lang.Double`） | Rhino 没有全局 `System`；`Math.abs` 是 JS 函数，不是 `Method`；dev-1373 及之前也没按形参收窄 | 用 `java.lang.Class.forName("java.lang.Math").getDeclaredMethods()` 取 `abs(int)`，再 `.invoke(null, 5)`；dev-1374+ 直接传 Number 返回 5；旧版才用 `new java.lang.Integer(5)`；不要写 `Math.abs.invoke` |

> 注意：Java 的 `System.loadLibrary` 只接受库名、不接受路径；自定义 SO 必须用 `System.load(绝对路径)`。上面 12–15 是 `.4` 云镜实战要求补的已知坑。

**怎么验证（.4 实战反馈条目 12–15；只做一次性探针，生产脚本不要靠 catch 兜底）：**

- 12：dev-1374+ 执行 `try { java.lang.System.load("/data/local/tmp/__absent__.so"); } catch (e) { console.log("CAUGHT class=" + e.getClass().getName()); }` → 预期 `CAUGHT class=java.lang.UnsatisfiedLinkError`；旧版 ≤dev-1373 不打印且任务 FAILED；去掉 try/catch 仍 FAILED。正式流程先 `new java.io.File(path).isFile()` / `files.exists` 再加载。
- 13：把测试 SO 放到应用私有目录或已验证可加载的位置，`java.lang.System.load(绝对路径)` 后调用一个导出符号；再对比 `System.loadLibrary` 的报错，确认不是同名系统库。若 `/data/local/tmp` 被 SELinux 拒绝，改应用私有目录/资产解压路径，不要放 `/sdcard`。
- 14：先复现 `pm install -r /storage/...` 失败；再按 Guide「安装测试包」用 `backend="shizuku"` 的 `cp` + `pm install -r /data/local/tmp/...` 成功，并用 `pm path`/`dumpsys package | grep versionName` 核对。
- 15：在 Rhino 里用 `java.lang.Class.forName("java.lang.Math").getDeclaredMethods()` 找到参数为 `int` 的 `abs`，再 `abs.invoke(null, 5)`；dev-1374+ 预期输出 `5`（旧版 dev-1373 及之前才用 `new java.lang.Integer(5)` 包装）；`Math.abs.invoke` 会拿到 JS 函数，不是 Method。

**#14 可跑骨架（Rhino，dev-1374+）：**
```js
var methods = java.lang.Class.forName("java.lang.Math").getDeclaredMethods();
var abs = null;
for (var i = 0; i < methods.length; i++) {
  var m = methods[i];
  if (m.getName() === "abs" && m.getParameterTypes().length === 1 &&
      String(m.getParameterTypes()[0].getName()) === "int") { abs = m; break; }
}
if (!abs) throw new Error("Math.abs(int) not found");
console.log(abs.invoke(null, 5)); // dev-1374+ 应为 5
```

## #16 Java 异常 vs Java Error：try/catch 边界（.4 实战反馈）

> 来源：`.4` 云镜会话；dev-1374 已在 Rhino 修复 Error 捕获（不是全局放开 Java access，见下）。

- **dev-1374 起**：普通 Java 异常（`NullPointerException`/`IllegalArgumentException`/`IOException` 等）可被 JS `try/catch` 捕获；`UnsatisfiedLinkError` 等 `Error` 也可被 catch（Rhino 会在 catch 里给出 `CAUGHT class=java.lang.UnsatisfiedLinkError`）。
- **旧版本（dev-1373 及之前）**：`Error` 会穿透 try/catch，脚本直接 FAILED；若在旧版复现，别把“没进 catch”误判为参数错误。
- **边界（dev-1374 取舍）**：Rhino 的 feature 是 Java 调用边界整体开关，`OutOfMemoryError`/`ThreadDeath` 也会变成可 catch；要单独排除需改 Rhino 内部，本轮未做。Node/QuickJS 是代码审计结论（本来可 catch），未单独真机验。
- **规避**：不要只用 catch 判断 SO 能否加载；先 `java.io.File`/`files.exists` 检查路径、大小、ABI/依赖，再用 `java.lang.System.load(绝对路径)`（`System.loadLibrary` 只接受库名、不接受路径）；catch 只用于记录错误。Rhino 没有全局 `System`，必须写 `java.lang.System`。

**怎么验证（dev-1374 起）**：
```js
// 1) 普通 Java 异常可 catch
try { throw new java.lang.IllegalArgumentException("normal"); } catch (e) { console.log("EXCEPTION_CAUGHT: " + e); }

// 2) dev-1374 起 Error 可 catch；旧版这里不执行
try { java.lang.System.load("/data/local/tmp/__absent__.so"); } catch (e) { console.log("CAUGHT class=" + e.getClass().getName()); }
// 期望 dev-1374+ 看到 CAUGHT class=java.lang.UnsatisfiedLinkError

// 3) 去掉 try/catch 仍应 FAILED（证明错误真实存在，只是可捕获）
// java.lang.System.load("/data/local/tmp/__absent__.so");

// 4) 正确做法：先检查文件，再绝对路径加载
var f = new java.io.File("/data/local/tmp/libfoo.so");
if (!f.isFile()) throw new Error("SO 不存在: " + f.getAbsolutePath());
java.lang.System.load(f.getAbsolutePath());
```

验证结果：dev-1374+ 第 1 段打印 `EXCEPTION_CAUGHT`、第 2 段打印 `CAUGHT class=java.lang.UnsatisfiedLinkError`；第 3 段不 catch 时任务 FAILED；第 4 段只对真实存在的 SO 继续执行。旧版（≤dev-1373）第 2 段不打印，任务 FAILED。

## 附录 B · 黄金流程：在外部进程里「借」目标 App 的解密函数

适用：数据加密在 assets，解密靠目标自己的 native + Java 类，本机无 root、不想改包。以下为一次实战的骨架（类名/字段按目标 App 替换）：

```js
// ① 冷进程：先 stop_all_scripts，再 run_script
// ② 造 asset 源：把 .so 打进只含 assets/autox/native/arm64-v8a/<lib>.so 的 zip
var zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(fakeApk));
zos.putNextEntry(new java.util.zip.ZipEntry('assets/autox/native/arm64-v8a/libautox_encrypt.so'));
zos.write(so); zos.closeEntry(); zos.close();
var am = new Packages.android.content.res.AssetManager(); am.addAssetPath(fakeApk);
// ③ 假宿主：包名/证书/路径指向「原版」
var fakeCtx = new x.FakeCtx(context, 'com.script.shuabu', am, fakePM, apkPath, realPI.applicationInfo);
GlobalAppContext.sApplicationContext = fakeCtx;
// ④ 顺序不能反
ne.setHostContext(fakeCtx);           // 先
ne.isAvailable();                     // 后 -> true
ne.reset(); ne.initWithBlob(blob64);  // 按目标实现传入密钥材料
// ⑤ 解数据：用逐 blob 自包含入口
var plain = ScriptEncryption.INSTANCE.decryptScript(blob);
```

## Frida 实战：RegisterNatives 与 JNI 函数表（.4 云镜实战）

> 来源：`.4` 云镜快照-成果/对照表.md §八「下一轮要修的两个小问题」——是他们自己 hook 时踩的坑。我们的 Gadget 固定 Frida 17.25.2，版本差异以实测为准。

### 1. Frida 17 取 RegisterNatives 符号（旧取法失效）

**旧取法/失败表现**：`Module.getExportByName("libart.so", "RegisterNatives")` 或 `Module.findExportByName(...)` 在 Frida 17 上可能抛 `unable to find export` / 返回 null（表现为「找不到 RegisterNatives」）。这不是 libart 没加载，而是符号名/API 变化；不要因此认定目标不可 hook。

**现在可用写法**：
```js
var lib = Process.findModuleByName("libart.so");
if (!lib) lib = Process.enumerateModules().find(function (m) { return m.name.indexOf("libart") >= 0; });
if (!lib) throw new Error("libart.so 未加载");
var addr = null;
try { addr = Module.findGlobalExportByName("RegisterNatives"); } catch (e) {}
if (!addr) {
  var syms = Process.enumerateSymbols(lib.name).filter(function (s) { return s.name.indexOf("RegisterNatives") >= 0; });
  console.log("RegisterNatives candidates=" + syms.length + " frida=" + Frida.version + " lib=" + lib.name);
  if (!syms.length) throw new Error("找不到 RegisterNatives，列出符号名后再匹配");
  addr = syms[0].address;
}
console.log(JSON.stringify({frida: Frida.version, module: lib.name, registerNatives: addr.toString()}));
```

**怎么验证**：
- 用 `frida_eval` 跑上面骨架（`script` ≤64KiB、`durationMs` ≤10000）；期望 `registerNatives` 非 null、`frida=17.x`。
- 若 `syms.length=0`，先打印前 20 个含 `Register` 的符号名，确认是名字/版本问题；把候选 `address` 与 `Process.enumerateSymbols` 输出交叉核对，不要硬编码旧十六进制地址。
- `Interceptor.attach(addr, {onEnter: function (args) { ... }})` 前先 `console.log(addr)`；attach 抛错说明地址/符号不对。

### 2. JNI 函数表扫描：三条校验（只扫 r-- 会扫到字符串）

**失败表现**：只扫 `r--` 段，把字符串/数据当函数表；`readPointer` 解出来是 ASCII/乱码，hook 后崩溃或参数错乱。

**正确校验**：扫该 so 的**所有可读段**（`r--`/`rw-`/`r-x`/`rwx` 合并去重），对 8 字节对齐候选 `P` 校验三条：
1. `readPointer(P)` == 目标名字字符串地址；
2. `readPointer(P+8)` 指向以 `(` 开头的签名串；
3. `readPointer(P+16)` 落在该模块的**可执行段**内。

**可粘贴骨架（把 so/方法名换成实际目标）**：
```js
var mod = Process.findModuleByName("libart.so");
var targetName = "nativeMethod";
if (!mod) throw new Error("目标 so 未加载");
function readableRanges(m) {
  var out = [], seen = {};
  ["r--", "rw-", "r-x", "rwx"].forEach(function (prot) {
    try {
      Process.enumerateRanges(prot).forEach(function (r) {
        if (r.base.compare(m.base) >= 0 && r.base.compare(m.base.add(m.size)) < 0) {
          var key = r.base.toString() + ":" + r.size.toString();
          if (!seen[key]) { seen[key] = 1; out.push(r); }
        }
      });
    } catch (e) {}
  });
  return out;
}
var ranges = readableRanges(mod);
var nameAddr = null;
for (var i = 0; i < ranges.length && !nameAddr; i++) {
  var ms = Memory.scanSync(ranges[i].base, ranges[i].size, targetName + String.fromCharCode(0));
  if (ms.length) nameAddr = ms[0].address;
}
if (!nameAddr) throw new Error("找不到名字字符串 " + targetName + "（确认它在可读段）");
function execRange(addr) {
  try {
    var r = Process.findRangeByAddress(addr);
    return !!r && r.protection.indexOf("x") >= 0 && r.base.compare(mod.base) >= 0 && r.base.compare(mod.base.add(mod.size)) < 0;
  } catch (e) { return false; }
}
var hits = [];
ranges.forEach(function (r) {
  for (var p = r.base; p.compare(r.base.add(r.size - 16)) < 0; p = p.add(8)) {
    try {
      if (!p.readPointer().equals(nameAddr)) continue;
      var sig = p.add(8).readPointer().readCString();
      if (!sig || sig.charAt(0) !== "(") continue;
      var fn = p.add(16).readPointer();
      if (!execRange(fn)) continue;
      hits.push({at: p.toString(), sig: sig, fn: fn.toString()});
    } catch (e) {}
  }
});
console.log(JSON.stringify({nameAddr: nameAddr.toString(), hits: hits}));
```

> 全段逐 8 字节遍历只适合目标 so 很小或已知候选范围；大模块先打印 `ranges`/`nameAddr`，再在 `nameAddr` 附近窗口查找，或用 `Memory.scanSync` 搜指向 `nameAddr` 的 8 字节，避免长时间卡住脚本。

**怎么验证**：跑骨架后看 `nameAddr` 非 null、`hits.length > 0`；每条 `sig.charAt(0) === "("`，`fn` 在同模块可执行段；若 0 命中，把 `ranges` 和候选数打印出来，确认不是只扫了 `r--`。

### 3. 与 MCP 工具配合

- `frida_status`：先确认组件/服务可用。listen 模式传 `endpoint/token`；**script 模式没有 endpoint/token**，`frida_status` 只做组件/服务检查，不能当作脚本已执行。
- `frida_eval`：对 listen 模式 Gadget 传 `pid` + `script`（≤64KiB）+ `durationMs`（≤10000），从消息/输出取结果；script 模式没有网络通道，不提供 endpoint/token，不能硬套 `frida_eval`——让目标应用把结果写文件，再用 `file_read_text`/文件工具读。
- `native_*`：`native_functions`/`native_function_detail`/`native_xrefs`/`native_decompile` 做静态对照（地址、符号、字符串、调用关系），确认 Frida 找到的地址不是把字符串当函数表；动态和静态结果都记录文件路径/SHA。
- **`Java.perform` 需要自行打包 `frida-java-bridge`**：我们的 Gadget 只带 `libfrida-gadget.so`，不带 Java bridge；用 Java API 的脚本必须自带/加载 bridge，否则 `Java.perform` 会报缺失；`frida_eval` 不会自动注入。

**怎么验证（MCP 层）**：
1. `frida_status` 返回组件可用（script 模式只看组件，不找 endpoint/token）；
2. `frida_eval` 执行上面的符号/函数表骨架，打印 JSON；
3. 用 `native_function_detail`/`native_xrefs` 对同一地址/符号做静态核对；
4. script 模式用 `apk_inject_gadget{interactionType:"script"}` 的输出（无 endpoint/token）+ 目标应用日志/结果文件验证，`frida_eval` 不适用。


## 附录 C · 命令速查

设备侧统一走 MCP（PC 端任意 HTTP 客户端都可）：

```bash
# get_status / stop_all_scripts / run_script / terminal_exec 同上文工具名
# APK native 反汇编（本地 toolkit，无需 MT）
{"name":"apk_native_disassemble","arguments":{"workspaceId":"...","editSessionId":"","locator":"zip_entry:lib/arm64-v8a/x.so","addressKind":"virtual","address":"0x1234","instructionMode":"auto","maxBytes":400,"maxInstructions":100,"stopAtSymbolBoundary":false}}
```
## 手册补丁（dev-1380 B13/B15/B17/B18 + §11/Rhino 版本边界）

> 来源：`.4` 的 `MCP-AIjsPro-总结-dev1378.md` B 清单/§11；与 Guide「dev-1380 手册补丁」互补，这里给执行前速查。

### B13 `terminal_exec` 自检

- 执行前先 `shell_capabilities {}`；`terminal_exec` **无 PTY**，能力有缺口。
- `grep` 可能不支持 `\b`/`\s`：用 `[[:space:]]`、显式边界或 `awk`；`cat` 可能没有 `-n/-A`：用 `sed -n`/`awk`/`od`。
- 没有 `zip`/`jar`/`7z`，通常只有 `tar`；ZIP 用 MCP `zip_*` 工具。`python` 软链可能坏：先用 `command -v python3`/`ls -l`，不要假设 `python` 可用。
- 验证：`shell_capabilities {}` 对照后，逐条跑 `command -v zip jar 7z python3; grep --help | head; cat --help | head`，报告写实际能力。

### B15 两套 native 工具

- `native_*`（radare2）：输入**手机文件 path**，不接受 `workspaceId`/`locator`；适合已导出的 so、r2ghidra 伪代码。
- `apk_native_*`（Capstone）：输入 `workspaceId`+`editSessionId`+`locator=zip_entry:...`，直接读 APK 快照；不落盘，适合 APK 内 so 的 inspect/xref/反汇编/补丁。
- 要从快照用 radare2：先 `apk_copy_entry direction=workspace_to_local` 导出，再把 `localPath` 传给 `native_*`。
- 验证：同一 so 用 `apk_native_inspect` 与导出后 `native_analyze` 各查一次架构/地址；把错误输入类型互传应明确报参数/locator 错误。

### B17 `native_functions` 分页

`native_functions(path,offset,limit)` 的 `offset` 是**条目索引，不是地址**；跨页用 `nextOffset`，不要拿 `address` 当 offset。返回 `heuristic:true`，是启发式函数列表，不保证覆盖全部/边界准确；单函数按地址查用 `native_function_detail`。

验证：`offset:0,limit:10` 后用 `nextOffset` 取下一页，确认 `heuristic:true`，报告标注“非完整函数表”。

### B18 非 ASCII 反编译文件名

批量反编译产物文件名可能含非 ASCII 类名（如 `okhttp3.internal.io.ვ.java`）；文件/压缩工具链可能踩坑。机器处理做 **name→ASCII 映射**（安全名 + 原名 manifest），或跳过/转义，不要把文件名当类名。`apk_read_text`（locator）与 `apk_job_export`（指定 outPath）不经过反编译文件名，不受影响。

验证：对一个非 ASCII 类名目标看产物文件名，再用 `apk_read_text(dex_class:...)` 读取并 `apk_job_export` 到自定义 outPath。

### §11 `run_script` 超时/Node 执行槽

- MCP `run_script.timeoutSeconds` 上限 **600**；`wait:true` 超时返回 `timedOut=true`，脚本可能仍 RUNNING，用 `get_execution`/`wait_execution`/`stop_script`。
- 按 `path` 运行的脚本原生超时 **1 小时**（`nativeTimeoutMillis=3600000`），MCP 600 秒不会真杀；要停必须显式 `stop_script`。
- Node 执行槽 **3 个**；第 4 个等 ≤8 秒后 `PoolBusyException`（不是 engine 不存在），先停一个 Node 脚本或复用任务。

验证：`wait:true,timeoutSeconds:1` 跑长脚本应 `timedOut=true` 且随后仍 RUNNING，再 stop；3 个 Node 长任务 + 第 4 个应 `PoolBusyException`（验证后立即停）。

### Rhino 版本边界：`String.valueOf(42)`

Node 一直返回 `42`；Rhino **dev-1380+** 返回 `42`，`.4` 的 **dev-1378** 仍是 `42.0`（`valueOf(1.5)` 都是 `1.5`）。先看 `get_status.app.build` 或实测，不要按旧手册断言整数输出。

验证：分别用 `run_script engine=rhino` 和 `engine=nodejs` 打印 `Packages.java.lang.String.valueOf(42)`，按构建版本解释差异。


## 附录 D · 留档与写作用域

- 探索脚本：`/storage/emulated/0/脚本/<用途>_harnessN.js`，只续号不覆盖。
- 结果：`<项目>/recovered/`（正式交付放 `recovered/工程明文/`）；报告：`<项目>/analysis/*.md`。
- **项目目录约定（必须，dev-1381）**：APK/ZIP/反编译/截图/导出证据统一放 `<root>/<项目名>/`；没有就 `project_folder {action:"create",name:"…"}`，再 `set` 为默认。不要放脚本根或 `tmp/out/test/__xxx`；自动归置的 `organized` 是纠错信号，不是长期方案。
- **正反例**：❌ `脚本/out.apk`、`脚本/__verify/x.apk`；✅ `脚本/云镜分析/build/out.apk`、`脚本/云镜分析/recovered/job-result.json`。
- **验证**：`project_folder {action:"state"}` 看 root/projects；裸输出应带 `organized`；按项目路径直传则不应带；`tidy` 默认 `dryRun:true`，确认后 `dryRun:false`，再用 `file_list` 核对。
- **任务结果只保留 6 小时或 64 条（dev-1378）**：`apk_job_*` 输出超出保留策略会删除缓存，只留 `JOB_EVICTED` 墓碑（`apk_job_list.evicted[]` 的 `code=JOB_EVICTED`、`reason=retentionLimit|retentionWindow`；`retention` 里 `windowMillis=21600000`、`maxRetainedFinished=64`、`maxTombstones=256`、`maxResultBytes=24 MiB`）。结果“消失”先看墓碑，不是升级清空；`SERVICE_RESTARTED` 才是进程重启丢失。**任务到终态就立刻 `apk_job_result`/`apk_job_export` 取回并写入项目目录的 `recovered/`，不要等**。`apk_job_result` 默认窗口 1,048,576 字符（dev-1378，旧 16,384），大结果一次取回；`hasMore/nextOffset` 续读，`apk_job_result` 显式 `maxChars` schema 上限已 1 MiB（dev-1380）；但 `apk_decompile_*` 的 `maxChars` 默认/上限仍是 200000、会在 200000 截断（B3 修复中），大结果用 `apk_job_export`/`offset` 续读。怎么验证：`apk_job_list {}` 看 `retention/evicted`；对墓碑 jobId 调 `apk_job_result` 应报 `JOB_EVICTED`；跑一个 >16K 结果不传 `maxChars`，确认 `content` 长度 >16384、`defaultMaxChars=1048576`。 留档用 `apk_job_export{jobId,outPath}` 原样原子导出（默认导出全部、父目录自动建、不延长 6h/64 条缓存）。
- 交付前两端 md5sum 对一遍并写进 `MANIFEST.md`；原始 APK / project.bin 只读不改。

## 资源不匹配、后台任务和回退

- 见“资源包与宿主资源表不匹配”时，先记录完整 expected/actual 摘要；按出问题手机的实际宿主重新编译。换 Android 版本不能替代资源表同步，也不要伪改 sidecar。可选 apk-resources 组件 + android_resources_compile 已提供本机编译入口。
- 长编译/反编译先取 jobId，读取进度再分块取结果；不让 MCP 调用同步等待很久。CANCELLING 不是完成。
- APK 编辑先建立 checkpoint，diff 只预览；rollback 使用当前 revision，避免覆盖别的调用刚写入的修改。
- 分包归档的 baseCandidate 只是文件名提示，必须针对实际 Manifest/签名/ABI 确认安装组合；选择成员分析不等于整组合并成功。
