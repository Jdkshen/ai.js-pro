# QuickJS 默认与 Rhino 兼容分层

## 脚本选择规则

选择优先级从高到低：

1. 脚本第一条非空行的 `// @engine quickjs` 或 `// @engine rhino`。
2. `project.json` 的 `scripts.<相对路径>.engine`。
3. `project.json` 顶层 `engine`。
4. 没有任何配置的旧脚本继续使用 Rhino。

新建单文件脚本和新建项目默认写入 `// @engine quickjs`。已有无标记脚本不会被批量修改。

项目配置示例：

```json
{
  "engine": "quickjs",
  "main": "main.js",
  "scripts": {
    "legacy.js": {
      "engine": "rhino",
      "useFeatures": [],
      "uiMode": false
    }
  }
}
```

文件内指令优先于项目配置，便于单文件迁移和回退。

## 构建版本

- `MiuixCompatDebug/Release`：QuickJS + Rhino 执行 provider，兼容旧脚本。
- `MiuixLiteDebug/Release`：只注册 QuickJS 执行引擎；旧 Rhino 脚本会收到明确提示，不会静默换引擎。

当前 `lite` 仍包含 `:rhino-language`，供编辑器 Token/AST、高亮和异常兼容代码使用；它不包含
`:engine-rhino` provider，因此不能执行 Rhino 脚本。等编辑器解析、调试器和 Java 桥接彻底解耦后，
再从 lite 中删除语言兼容库并获得完整体积收益。

## 模块边界

- `:autojs`：公共运行时和 QuickJS 引擎。
- `:rhino-language`：过渡期 Rhino Token/AST/类型兼容库。
- `:engine-rhino`：反射加载的 Rhino 执行 provider，仅 compat 打包。

`AutoJs` 不再静态初始化 Rhino；运行时只在 provider 存在时注册兼容引擎。

## 验证命令

```powershell
.\tools\test-miuix.ps1
.\tools\test-engine-flavors-device.ps1 -Serial cccc62c7
.\release.ps1 -Variant MiuixCompatDebug -SkipNative
.\release.ps1 -Variant MiuixLiteDebug -SkipNative
```

设备矩阵脚本会依次验证 compat QuickJS、compat 无标记 Rhino、lite QuickJS 和 lite
Rhino 可控拒绝路径，每次都检查 crash buffer，最后自动回装 compat。

正式移除 Rhino 前必须覆盖 `ui`、`floaty`、`threads`、`events`、`web`、debugger、编辑器解析和 Java 桥接回归。
当前通过项和硬阻塞见 `docs/architecture/QUICKJS_MIGRATION_MATRIX.md`。
