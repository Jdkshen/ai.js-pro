# QuickJS 默认与 Rhino 兼容分层

## 脚本选择规则

选择优先级从高到低：

1. 脚本第一条非空行的 `// @engine quickjs` 或 `// @engine rhino`。
2. `project.json` 的 `scripts.<相对路径>.engine`。
3. `project.json` 顶层 `engine`。
4. 没有任何配置的旧脚本继续使用 Rhino。

新建单文件脚本和新建项目默认写入 `// @engine quickjs`。已有无标记脚本不会被批量修改。

### 唯一的例外：当前引擎「连解析都过不去」的脚本

上面第 4 条有一个窄口径例外（`EngineFallbacks`，在创建引擎之前判定）：脚本被判给某个引擎、
但那个引擎**根本无法解析**它、而另一个引擎能解析时，会自动换到能跑的那个，并往控制台写一条
W 级说明。两个方向：

| 死角 | 现象 | 兜底 |
|---|---|---|
| Rhino 1.7.7 词法层没有模板字符串（反引号） | `illegal character` | 换 QuickJS（`text_switched_to_quickjs_for_template_string`） |
| QuickJS 没有 E4X（`ui.layout(<xml>)` 这种 Auto.js 经典写法） | `unexpected token in expression: '<'` | 换 Rhino（`text_switched_to_rhino_for_xml_layout`） |

⚠️ 上表第二行现在**基本不会触发**了：`XmlLiteralPreprocessor` 会在把源码交给 QuickJS 之前，
把 `ui.layout(<xml>)` / `ui.inflate(<xml>)` 的实参改写成**模板字符串**（跨行、行号不变），
所以 QuickJS 现在也能跑这套写法。留着这条兜底是给「改写没覆盖到的 XML 字面量」保底。

**两者混用**（既写 XML 字面量、又用反引号模板字符串）的脚本，过去两个引擎都跑不了；
现在走 QuickJS + 预处理即可 —— Mi8 实测通过。

判定只看这三件事：源码里是否含反引号 / XML 字面量、当前引擎是不是那个解析不了的引擎、
**目标引擎是否真的编译得过**。所以：

- 反引号只出现在字符串/注释里、XML 写成字符串的脚本，一律不动引擎；
- 两边都用不了的脚本（既含反引号又是 XML 字面量）**不会被来回倒腾** —— 保持原样，
  由带提示的报错兜底（`EngineSyntaxHint`）。

⚠️ 兜底走的是 `JavaScriptSource.setForcedEngine()`，它能**压过脚本首行的 `// @engine` 指令**。
这是刻意的：脚本在指定引擎上注定跑不起来，换引擎是救它；但这也意味着这一条例外比其他
优先级都高。正常路径请用 `setPreferredEngine()`（不压过指令）。

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

- `:engine`：公共运行时和 QuickJS 引擎。
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
