# 示例集按引擎重排：Rhino 引擎 / QuickJS 引擎 / Node 引擎（dev-928）

2026-10-17。按需求把「示例」从原来的两棵自定义树改成跟 Auto.js Pro 内置示例集一致的三引擎结构。

## 1. 目标结构

```
apps/app/src/main/assets/sample/
  Rhino 引擎/<分类>/...        ← Auto.js Pro 原包示例集（中文，非 Node.js 部分）
  Node 引擎/Node.js/...        ← Auto.js Pro 原包示例集（中文，Node.js 部分）
  QuickJS 引擎/...                  ← 本仓库原有 QuickJS 示例（原「QuickJS 新引擎」改名）
```

- Auto.js Pro 原包示例集在仓库里原来放在 `assets/pro-samples/{中文,English}`（403 个 .js，538 个文件），本轮按引擎拆进 `assets/sample` 后删除独立目录；随后按需求删掉 English 分类，只保留中文示例。
- Rhino 侧取中文非 Node.js 部分、Node 侧取中文 Node.js 部分（伴生资源随目录一起走）；English 分类已按需求移除。
- QuickJS 引擎 101 个文件，沿用上一轮已验收的自定义示例；因为 Auto.js Pro 原包示例里没有 QuickJS 专属示例（首行只有 `"nodejs";` / `"ui";` / `"auto";` / 默认 Rhino）。
- 示例页（Flutter 玻璃界面 / 原生 Miuix）都直接读 `files/sample`，所以顶层自然变成三个引擎目录。

## 2. 代码改动

| 文件 | 改动 |
| --- | --- |
| `apps/app/src/main/java/com/jdkshen/aijspro/model/sample/BundledSampleSync.java` | 增加「下架示例清理」`pruneRemoved` + `deleteEmptyDirs`：升级后按上次同步记录删掉新版 APK 已不存在的示例文件（用户改过的先备份到 `files/sample-backup/` 再删），解决旧「QuickJS 新引擎 / 旧 Rhino 树」在手机上残留的问题 |
| `apps/app/build.gradle` | QuickJS/YOLO/找图 三处校验路径改到 `sample/QuickJS 引擎/...`、`sample/Rhino 引擎/图片与图色处理/找图`；`verifyBundledSamplesNoNode` 对 `Node 引擎` 放行 nodejs 入口与 Node.js 目录名，其它目录仍禁 |
| `apps/app/src/androidTest/.../DshClientSamplePackagingTest.java`、`SampleFilesImportTest.java` | 示例资产路径改名 |
| `apps/app/src/main/java/com/jdkshen/aijspro/samples/ProSampleSweepActivity.java` | 实测入口资产根 `pro-samples` → `sample`，缓存目录改名 |
| QuickJS 示例内容 | 15 个文件里的 `asset://sample/QuickJS 新引擎/...` 改为 `asset://sample/QuickJS 引擎/...`；引用的找图资源路径改为 `asset://sample/Rhino 引擎/图片与图色处理/找图/` |
| 文档 | `QUICKJS_ENGINE.md`、`QUICKJS_MIGRATION_MATRIX.md`、SIFT/VSCode/共享截图/悬浮窗指南、相关 reports/plans 的示例路径同步改名；Node24 报告加 7.1 修订节 |

## 3. 验收（K40，dev-928）

- 覆盖安装 dev-928 后 `BundledSampleSync` 日志：`新增 631，更新 0，已是最新 0，备份用户改动 0，清理下架示例 311`；手机上 `files/sample` 顶层变成 `Node 引擎 / QuickJS 引擎 / Rhino 引擎`（旧的 311 个同步文件被清掉）。
- 示例页顶层：Node 引擎 / QuickJS 引擎 / Rhino 引擎。
- Rhino 引擎：English / 中文 两套 Auto.js Pro 分类（Android 7.0+ tap and gesture、Application、Canvas…；HTTP网络请求、JavaScript、Node.js 等）；打开 `Love gesture.js` 正常进原生 Ace 编辑器并带「重置」。
- Node 引擎：中文 → Node.js → 子目录（Node.js与Android和Java交互、Node.js中使用UI、使用Node.js模块…）+ 根级 shell/zip 示例，可正常浏览与打开。
- QuickJS 引擎：原 101 个文件与分类不变（图色处理/YOLO/悬浮窗/回归测试…），首行 `// @engine quickjs` 校验通过。
- APK 体积：269,326,455 B → 266,275,650 B（−3.05 MB）。发布 SHA-256 `1ade8561a4f3d2f08ced41f358182219861906dda35f7b8c6952d15fb5b34a15`。

**说明**：旧目录里用户自己放进去的文件（例如此前实测留下的象棋工程、SIFT 输出图、旧模型）不会被自动删除（保护用户数据）；本次测试机上是手动清掉的。

## 4. dev-929 修订：去掉英文示例 + 示例菜单同步 Auto.js Pro

- **去掉英文示例**：删除 `Rhino 引擎/English` 与 `Node 引擎/English`，Rhino/Node 两棵只剩 `中文` 分类。
- **示例菜单同步 Auto.js Pro**（`browser.dart`）：
  - 补「悬浮运行」（示例脚本也可以直接弹悬浮控制条，脚本不自动跑，按控制条 ▶ 才运行）；
  - 补「打包」（单文件示例走 `sampleAction.build` → materialize 后打开原生打包页）；
  - 目录型示例保留「打包（项目）」（有 project.json 时）；
  - 隐藏 Auto.js Pro 示例菜单里没有的「发送文件 / 复制路径 / 文件详情」，并去掉与「导入到我的脚本」重复的「复制到脚本目录」。

真机（K40，dev-929）验收：示例根目录 3 棵；`Rhino 引擎` 下只有 `中文`；文件菜单为 查看示例 / 运行示例 / 悬浮运行 / 打包 / 导入到我的脚本 / 重置示例；点「悬浮运行」弹出控制条（未自动运行脚本），点「打包」打开原生「打包应用」页且路径为所选示例。同步日志：`新增 0，已是最新 373，清理下架示例 258`（英文文件被清掉）。

APK（dev-929）：265,852,337 B，发布 sha256 `f2252c68b3375f3039319761f05738774cd681db11710842e12f3cf0aa71cf92`。

## 5. dev-932：引擎标记统一 + 去掉「中文」路径层

### 5.1 引擎首行标记（对齐 Auto.js Pro 的 Node 示例写法）

Auto.js Pro 的 Node 示例首行是 `"nodejs";`（也有 `"nodejs ui";` 组合），而本仓库引擎之前只认注释指令 
`// @engine quickjs/rhino/node24`，所以 Node 示例会被当 Rhino 跑。dev-932 在 
`modules/engine/.../script/JavaScriptSource.java` 的 `engineFromDirective` 里补了 Pro 风格字符串指令：

- `"nodejs";` / `"nodejs ui";` / `"ui nodejs";` → Node24 引擎；
- `"quickjs";` → QuickJS；
- `"ui";` / `"auto";` 仍按执行模式处理，继续向后找引擎指令。

三类示例的写法约定：

| 引擎 | 首行写法 |
| --- | --- |
| Node 引擎 | `"nodejs";`（Pro 原样，引擎已识别） |
| QuickJS 引擎 | `// @engine quickjs`（本仓库构建校验 `verifyQuickJsSamplesMarked` 强制） |
| Rhino 引擎 | 不写指令默认 Rhino；需要显式时写 `// @engine rhino` |

真机验证：写 `__engine_probe.js`（首行 `"nodejs";`，打印 `process.version`）点 ▶，
日志为 `GlobalConsole: probe-node v24.22.0`，运行栈是 `Node24JavaScriptEngine`（`:node24` 进程）。

### 5.2 去掉示例路径里的「中文」层

```
apps/app/src/main/assets/sample/
  Rhino 引擎/<分类>/...
  Node 引擎/Node.js/...
  QuickJS 引擎/...
```

- English 上一轮已删，只剩中文示例后 `中文/` 这层是冗余的，本轮整体上提一层；
- QuickJS 示例里引用找图资源的 `asset://sample/Rhino 引擎/中文/图片与图色处理/找图/` 同步改为无「中文」；
- `build.gradle` 的 `verifyBundledFindImageAssets` 路径、相关文档里的目录示例同步更新；
- 升级后 `BundledSampleSync` 清理旧路径日志：`新增 272，更新 2，已是最新 99，清理下架示例 272`。

APK（dev-932）：266,030,661 B，发布 sha256 `7d7caba6f969ae70c5cc40b01beb24e4197a669316914cf63dc461740f57ea0e`。

## 6. dev-933：三引擎示例全部加首行引擎标记

按需求给三棵示例的每个 `.js` 加首行字符串引擎标记，打开一眼能看出用哪个引擎跑：

| 引擎 | 首行标记 | 说明 |
| --- | --- | --- |
| Rhino 引擎 | `"rhino";` | 原来默认不写；现在显式声明（engineFromProDirective 新增 rhino/rhinojs 识别） |
| Node 引擎 | `"nodejs";` | 沿用 Auto.js Pro 写法；`"nodejs ui";` 组合原样保留 |
| QuickJS 引擎 | `"quickjs";` | 由注释指令 `// @engine quickjs` 改为字符串指令；构建校验同时接受两种写法 |

处理规则：

- 首行是 `"ui";` / `"auto";` 时，执行模式保持第一行，引擎标记插到第二行；
- 已经有正确标记的文件不动（Node 里 35 个 `"nodejs";` / `"nodejs ui";` 原样保留）；
- 非 .js 伴生文件（json/xml/png 等）不加标记。

统计：三棵示例共 289 个 .js。Rhino 引擎新增 149 个标记、Node 引擎新增 21 个（35 个原本已是 `"nodejs";` / `"nodejs ui";`）、QuickJS 引擎由注释指令改 84 个；其中 54 个 UI/自动脚本的执行模式行（`"ui";` / `"auto";`）仍保持第一行，引擎标记紧跟其后（引擎扫描会跳过模式行），其余 235 个引擎标记就是首行。

验证：
- 引擎指令单测 `:engine:testDebugUnitTest --tests *JavaScriptSourceEngineTest*` 10 个用例全过（`"nodejs";` / `"nodejs ui";` / `"rhino";` / `"quickjs";`、模式行在前的组合）；
- 真机（K40，dev-932）Node 探针输出 `probe-node v24.22.0`（Node24 引擎）；dev-933 同步后抽查示例首行：Rhino UI 示例 `"ui";`→`"rhino";`、Node 示例 `"nodejs";`、QuickJS 示例 `"quickjs";`；
- APK（dev-933）：266,030,661 B，sha256 `fcf29ce5338cccf2d5ab16c98f2f75cacb59dcb70865920412d15dad21387ed5`。

## 7. dev-934：清理历史遗留示例目录（QuickJS 新引擎等）

问题：旧版本装过的手机上 `files/sample` 还留着上一代的 `QuickJS 新引擎/`、`Rhino 引擎/中文/` 等目录，
示例页会显示出来。`pruneRemoved` 依赖 `sample-sync-state.txt` 的旧清单，首次同步或记录丢失时不知道要删哪些目录，
旧目录就会一直留着。

修复：`BundledSampleSync` 增加显式的 `LEGACY_DIRS` 清理，在同步末尾执行：

- 列表：`QuickJS 新引擎`、`QuickJS新引擎`、`QuickJS 引擎/中文`、`Rhino 引擎/中文`、`Rhino 引擎/English`、
  `Node 引擎/中文`、`Node 引擎/English`、`pro-samples`；
- 文件内容与当前内置示例 md5 相同的直接删除；用户改过的先备份到 `files/sample-backup/legacy/` 再删；
- 删完调用 `deleteEmptyDirs`，旧目录整棵消失。

验证：

- K40（dev-934）：示例根目录仍是 Node / QuickJS / Rhino 引擎三棵；
- 小米 8（从 dev-900-node24 直接升 dev-934）：升级日志 `清理下架示例 541`，示例页只剩 Node / QuickJS / Rhino 引擎（浅色主题截图见 `output/flutter-poc/xiaomi8-samples-934.png`）；
- APK（dev-934）：266,030,657 B，sha256 `9b80b6390b3d301533b04369ce3716ede6f7f60ab2c1041de66a351f3b7c1d9e`。
