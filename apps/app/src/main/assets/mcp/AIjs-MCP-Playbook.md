# MCP 实战要点（操作手册补充）

> 来源：2026-11-02 在 192.168.10.4（Android 16 / arm64 / 无 root / MIUI）实战「刷步 1.0.0 借壳解密」沉淀。
> 本文件随 APK 打包，`learn_autojs_skill` 会把它追加在完整说明后面；参数仍以 `tools/list` 的 inputSchema 为准。

## 冷进程与库状态（最容易浪费时间，先读）

- `run_script` 会复用常驻脚本进程：dlopen 过的 .so、`isAvailable()` 结果都会粘住。换 .so 或做对照实验前，先 `stop_all_scripts` 再 `run_script`（冷进程）。
- 必须先 `setHostContext(宿主)` 再第一次调用 `isAvailable()`；顺序反了会把「不可用」缓存进该进程，之后恒为 false。
- AutoX 系 `NativeEncryptor` 从 `Context.getAssets()` 读 `autox/native/<abi>/*.so`，走 java-memfd 加载，**不落盘**；改 `files/autox_native/` 缓存文件无效，要换 .so 只能换 AssetManager 指向的 zip/APK。
- 进程内读 `/proc/self/maps`、`/proc/self/mem` 会被拒绝（空 / EACCES），不要拿它做内存取证。

## 大输出与文件传输

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
- 文件/APK 工具全部本地实现：先 `toolkit_status`；APK 用 `apk_workspace_open` → `apk_session_create` → 读写 → `apk_build`。旧 `mt_*` 名称只是兼容别名，不需要另装 MT。
- MT 管理器 MCP（如 `http://192.168.10.4:8787/mcp`）只作外部对照，其工具只读静态 APK/dex/native、不执行脚本；本机 toolkit 已覆盖同类能力。

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
| 8 | MT 工具 INVALID_ARGUMENT（缺 maxInstructions/maxBlocks/limit） | 需显式分页参数 | 先看 schema；参数别猜 |
| 9 | MT 的 dex locator 被拒 | 需 JVM 描述符 | `dex_class:Lcom/example/Foo;` |
| 10 | 中文文件名变乱码 | JS 里逐字符拼 UTF-8 | `new java.lang.String(bytes,'UTF-8')` 或设备端 `cp` 改名 |
| 11 | 文档里的长度/字节序对不上 | 旧文档未复核（如 64 vs 72 字节） | 先实测 |

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
- 交付前两端 md5sum 对一遍并写进 `MANIFEST.md`；原始 APK / project.bin 只读不改。
