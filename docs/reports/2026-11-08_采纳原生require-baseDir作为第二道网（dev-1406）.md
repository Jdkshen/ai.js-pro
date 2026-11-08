# 采纳「原生 require baseDir」作为第二道网（dev-1406）

> 用户在别处拿到一份对同一报错的分析（Rhino `Require.call` 的 `ModuleScope` 门槛 + `PathAwareRequire.baseDir`）。我复核其技术断言、在 K40 上做**双网验证**，并把它采纳为我们修复的第二层。回复稿：[output/MCP-FEEDBACK-REPLY-dev1406.md](../output/MCP-FEEDBACK-REPLY-dev1406.md)。

## 复核：它的断言全部成立

| 断言 | 复核方式 | 结果 |
|---|---|---|
| `Require.call` 只在 `thisObj instanceof ModuleScope` 时用 `getBase()/getUri()` 解析相对 ID，否则 `throwError` | 用仓库 `rhino-1.7.15.1.jar` 跑 `javap -p -c` | ✅ `instanceof ModuleScope` → `checkcast` → `getBase()` → `getUri()` → `ScriptRuntime.throwError` |
| 那句报错来自 Rhino 而非 jvm-npm | 扫整个 jar 找 `relative module ID` 字面量 | ✅ 只在 `org/mozilla/javascript/commonjs/module/Require.class`（jvm-npm.js#92 只是回落点） |
| 根因是作用域不是 `ModuleScope` | 读我们的 `initRequireBuilder` | ✅ `PathAwareRequire.install(scope)`，`scope` 是 `TopLevelScope extends ImporterTopLevel`（非 ModuleScope） |

## 两层网

```text
require("./脚本.js")
  ├─ 第一层 jvm-npm（init.js: require = Module.require）   · 用 Require.root 解析；root 在加载 jvm-npm 时固定为 files.cwd()
  │     ← dev-1401/1404：每次执行前刷成「脚本自身目录」（文本用 file.parentFile；加密/编译用新增 scriptPath）
  └─ 第二层 NativeRequire → Rhino Require.call           · 非 ModuleScope 不解析相对 ID → 抛 outside of a module
        ← dev-1406 采纳：PathAwareRequire 增 baseDir，作用域非 ModuleScope 且 ID 以 ./ ../ 开头时先 getCanonicalPath 再交父类
```

## K40 双网验证（打包运行时，入口放子目录 → cwd ≠ 脚本目录）

```text
dev-1404（只有第一道）:
  cwd=…/files/project ; require.root=…/files/project/sub
  CONTROL_ERR=Can't resolve relative module ID "./脚本.js" when require() is used outside of a module   ← 强制 root 回 cwd（=修复前行为），复现用户原句
  REQUIRE_OK hello-from-subdir

dev-1406（两道都上）:
  require.root=…/files/project/sub
  CONTROL_OK_NET2 hello-from-subdir   ← 同样强制 root 回 cwd，这次被第二道网救回
  REQUIRE_OK hello-from-subdir
```

同时验证的还有：`C-INRT: engine requireRootFix=true initJsSha256=0b65984aac4b09a2 scriptStorage=assets`（打包版启动引擎指纹）。

## 我们避开的坑（与那份分析里提到的两个一致）

1. 不用 `source.name`（只有文件名）→ 用真实路径：`JavaScriptFileSource.file.parentFile`，加密/编译用 `scriptPath`（`StringScriptSource` 与 `CompiledJavaScriptSource` 都新增）；
2. 「参数没变就提前 return」只在**重写结果与原文完全一致**时才走，不会吞掉 `baseDir` 重写；
3. 子引擎（他们的 `联邦任务.js` 场景）：刷新在**每次 `doExecution`**（每个引擎实例）里，`baseDir` 同样按该引擎当前脚本目录刷新。

## 交付

| 项 | 值 |
|---|---|
| 发布 | **dev-1406** |
| 引擎单测 | `PathAwareRequireTest` 4 项 + `EncryptedSourcePathTest` 2 项 |
| MCP 单测 | 全量通过（70 秒） |
| 冒烟 | 27 PASS / 0 FAIL / 1 SKIP |
