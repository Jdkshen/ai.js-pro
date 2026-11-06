# MCP 实战要点（操作手册补充）

> 来源：2026-11-02 在 192.168.10.4（Android 16 / arm64 / 无 root / MIUI）的实战记录。文件/APK 工具说明于2026-11-03更新，参数与预算以当前设备返回为准。
> 本文件随 APK 打包，`learn_autojs_skill` 会把它追加在完整说明后面；参数仍以 `tools/list` 的 inputSchema 为准。

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
- 需要 shell（uid=2000，可 `pm`/`am`/`dumpsys`）时用设备上的 rish（`terminal_exec backend="shizuku"` 或 `shell_rish`）；terminal 默认是应用 UID，跑不了 `pm`。
- 文件/APK 工具全部本地实现：先 `toolkit_status`；APK 按 `apk_list_available_apks` → `apk_workspace_open` → `apk_session_create` → 读取/搜索 → 修改 → `apk_session_check` → `apk_build`。旧 `mt_*` 名称只是 dispatch 兼容别名；公开写法和 URI 用 `aijs://current-apk` / `aijs://workspace/<id>`，不需要另装 MT。
- 浮窗：Node 用 `require("floating_window")`；坐标/根布局看 Guide「Node floating_window 坐标系与根布局（.4 实战反馈）」——根 `layout_*` 会被 WindowManager 无视，根 `match_parent`，窗口大小用 `setSize`/`initialSize`。
- 外部 MT 管理器 MCP 只作历史对照，不是本机工具链依赖。APK 工具用于静态查看和编辑，不执行目标 APK 的代码；脚本和终端由各自工具管理。

## 文件与 APK 工具：读取 → 修改 → 检查 → 构建

- 快照/会话：`apk_workspace_open` 建不可变快照（已有 APK 上下文可用 `aijs://current-apk` 取回，没有上下文时先按实际路径打开；已保存工作区用 `aijs://workspace/<id>` 且 `temporary=false`），`apk_session_create` 建写时复制会话。会话不会改源 APK。
- 先读后改：`apk_read_text`/`apk_read_bytes`/`apk_resource_read` 返回 `targetVersion`；DEX 类/方法用 `apk_read_text` 的 `dex_*` 定位符读取文本；`apk_edit_text`/`apk_resource_edit` 原样传回。`apk_edit_text` 新建目标用 `targetVersion="missing"` + 单个 `write_target`。看到“内容已改变”重新读取，不要重试旧参数。
- 输出路径坑（dev-1374）：`apk_build.outputName` 只接受**纯文件名或绝对路径**，相对子目录（如 `autojs_crack/_mcp_test/x.apk`）会被拒；`apk_inject_gadget.outPath` **不建父目录**，父目录必须已存在。先 `file_create_directory` 建目录，再传绝对路径；详见 Guide「dev-1373 已修」的 #18/#12。
- gadget 返回瘦身/partial（dev-1378）：`apk_inject_gadget` 的 `detail=summary` 只回关键字段（同一云镜样本 42 KB→480 字符），**不含 `partialReasons`**；要看 `verification.status`/`partialReasons` 必须用 `detail=full`（默认），或对输出 APK 跑 `apk_verify`。`partialReasons[].errorCodes` 出现 `ELF_PT_LOAD_ALIGNMENT_16K` 是组件预编译 gadget `.so` 的 `PT_LOAD` 未 16 KiB 对齐——当前实测加载不受影响，但 16 KiB 页设备/上架校验不合格。怎么验证：full 注入看 `partialReasons`，再 `apk_verify` 复核。
- 分页：列表/搜索/native/资源 xref 最多 200/页，返回 `nextCursor`，用 `apk_continue` 续页；游标 5 分钟过期且绑定会话 revision，编辑后必须重查。不能把两页拼成一份视图。
- 检查/构建：先 `apk_session_check(runBuildChecks=true)`，再 `apk_build`；构建会再检查，失败不导出。默认未签名；`sign=true` 用本机 AI.js Pro 身份重签并移除原签名。`apk_read_signature` 只读 V1/V2/V3/V3.1 证书元数据，`verified=false`，不是验签。
- 搜索/正则：搜索和文件替换使用 Java `java.util.regex`（支持环视/反向引用），每个 matcher 默认 750ms、最多 8M 字符访问，并受 3s/8s 总预算和中断检查约束；超预算报 `REGEX_BUDGET_EXCEEDED`。先加 prefix/缩小目标，别用灾难性回溯。
- ZIP：读取支持 ZipCrypto/AES，写入只生成 AES-256 或明文；`zip_test` 才做完整 CRC/HMAC 校验，`zip_read_*` 只是预览。大包先看 50k 条目/512MiB 扫描预算、250:1 压缩比和 2GiB 创建预算；**加密**条目才必须传非空 `password`（未加密条目可省略或传空字符串，dev-1368 起不再强制）。
- 资源：`apk_resource_read` 每项独立错误，默认 `variant=default`；`maxValueXmlChars` 超预算时 `valueXml=null`，不要写回半截 XML；`resolveDepth=1` 只解析一级直接引用。AXML/资源 XML 的写入/编辑拒绝 DOCTYPE/ENTITY。
- 原生：指令分析在原生库可用时用 Capstone 覆盖 AArch64/ARM/Thumb/x86/x86-64，不可用时只回退有界 AArch64 子集。指令补丁支持四架构，但内置汇编器只有 AArch64 常见子集；ARM/Thumb/x86/x86-64 或内置未覆盖形式要可选 clang（`aijs-toolchain install cpp`）。字符串增长只利用已有 NUL/空格 padding，不改长度/指针/重定位。
- 容量：APK 快照单包 512MiB、总量 1GiB；单条分析 64MiB、文本 10MiB；单工作区 8 会话、2000 变更条目/512MiB。旧 `mt_*` 名称不注册为规范工具。

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

## 附录 D · 留档与写作用域

- 探索脚本：`/storage/emulated/0/脚本/<用途>_harnessN.js`，只续号不覆盖。
- 结果：`<项目>/recovered/`（正式交付放 `recovered/工程明文/`）；报告：`<项目>/analysis/*.md`。
- **任务结果只保留 6 小时或 64 条（dev-1378）**：`apk_job_*` 输出超出保留策略会删除缓存，只留 `JOB_EVICTED` 墓碑（`apk_job_list.evicted[]` 的 `code=JOB_EVICTED`、`reason=retentionLimit|retentionWindow`；`retention` 里 `windowMillis=21600000`、`maxRetainedFinished=64`、`maxTombstones=256`、`maxResultBytes=24 MiB`）。结果“消失”先看墓碑，不是升级清空；`SERVICE_RESTARTED` 才是进程重启丢失。**任务到终态就立刻 `apk_job_result` 取回并写入本项目 `recovered/`，不要等**。`apk_job_result` 默认窗口 1,048,576 字符（dev-1378，旧 16,384），大结果一次取回；`hasMore/nextOffset` 续读，显式 `maxChars` schema 上限仍 200000。怎么验证：`apk_job_list {}` 看 `retention/evicted`；对墓碑 jobId 调 `apk_job_result` 应报 `JOB_EVICTED`；跑一个 >16K 结果不传 `maxChars`，确认 `content` 长度 >16384、`defaultMaxChars=1048576`。
- 交付前两端 md5sum 对一遍并写进 `MANIFEST.md`；原始 APK / project.bin 只读不改。

## 资源不匹配、后台任务和回退

- 见“资源包与宿主资源表不匹配”时，先记录完整 expected/actual 摘要；按出问题手机的实际宿主重新编译。换 Android 版本不能替代资源表同步，也不要伪改 sidecar。可选 apk-resources 组件 + android_resources_compile 已提供本机编译入口。
- 长编译/反编译先取 jobId，读取进度再分块取结果；不让 MCP 调用同步等待很久。CANCELLING 不是完成。
- APK 编辑先建立 checkpoint，diff 只预览；rollback 使用当前 revision，避免覆盖别的调用刚写入的修改。
- 分包归档的 baseCandidate 只是文件名提示，必须针对实际 Manifest/签名/ABI 确认安装组合；选择成员分析不等于整组合并成功。
