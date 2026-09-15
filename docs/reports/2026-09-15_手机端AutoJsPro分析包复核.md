# 手机端「AutoJs Pro 9.3.11 分析包」复核

日期：2026-09-15。来源：用户从手机发来的 `C:\Users\18101\Downloads\AutoJs_Pro_Analysis.zip`
（11 篇 markdown，约 250 KB，已解到 `.artifacts/autojs-pro-analysis/`，该目录不入 git）。

生成方式（文档自述）：**MT 反编译 + MiMo AI**，对象是**第三方魔改包**
`AutoJs五云Ai_Pro 9.3.11-0.apk`（包名 `org.autojs.autojspro`），不是官方第一代 Auto.js Pro。

## 一、逐条核对（判定 + 证据）

| 断言 | 判定 | 证据 |
| --- | --- | --- |
| 内置模块清单：`__console__.js` / `__timers__.js` / `__ui__.js` / `__threads__.js` / `__floaty__.js` / `__images__.js` / `__http__.js` / `__files__.js` / `__engines__.js` / `__storages__.js` 等 | **与我们对得上**（除 `__files__.js`） | 我们 `modules/engine/src/main/assets/modules/` 共 36 个文件、30 个 `__*__` 模块，上面这些名字全在 |
| `__files__.js` 是内置模块 | 只是命名差异 | 我们的 `files.*` 是原生实现（Java 侧 API），没有同名 JS 模块；API 已按 PDF 对齐并有真机契约 |
| 引擎：Rhino + V8 + QuickJS | **我们差 V8** | 我们实际是 Rhino + QuickJS（`docs/architecture/ENGINE_FLAVORS.md`）；V8 只有设计文档，`V8接入_框架瓶颈与迁移方案.md` 明确写「V8 尚未接入、未编译、未运行性能对比」 |
| 权限 80+ / 组件 32 | **与我们的包不可比** | 我们 manifest：19 个 `uses-permission`、39 个组件（28 activity / 5 service / 4 receiver / 2 provider）。权限画像是风控敏感点（见 `AGENTS.md`、`docs/PERMISSIONS_AND_RELEASE.md`），不照抄第三方包 |
| `okhttp3.internal.io.ੜ` 是「JavaScript 模块加载器」（6 篇、约 150 KB 都围绕它层层"加深"） | **误判** | 文档自己列出的该类方法只有 `ԯ(String)`、`Ԫ(Closeable, Throwable)`、`ԭ(List)`；后两个与 okhttp3 自带的 `okhttp3.internal.Util` 一一对应 —— `javap -p -classpath okhttp-3.10.0.jar okhttp3.internal.Util` 可见 `closeQuietly(java.io.Closeable)`、`join(List, CharSequence)`。也就是说这是个被混淆的 okhttp 工具类，不是模块加载器；Auto.js 的模块加载器不会放在 `okhttp3.*` 包里 |
| JVM 内存布局、性能指标（模块加载 <1 ms、启动 <2 s、内存 <200 MB…） | 通用模板，无法验证 | 没有测量方法、样本或复现步骤 |
| `org.autojs.autojspro.v8.api.ui.V8ScriptExecuteActivity` 等组件 | 与「引擎」一条互证 | 只说明**那个魔改包**接了 V8 |

## 二、值得注意的三条差异（按「该不该动」排序）

1. **V8**：这份包与我们的最大实质差异。要动就是一条独立工程线 ——
   现有 `ScriptEngineManager.registerEngine()` 提供了 provider 入口，但宿主 API、事件循环、
   线程与资源生命周期、原生发行链都要改（瓶颈清单见 `docs/architecture/V8接入_框架瓶颈与迁移方案.md` §2）。
   照抄分析包没有帮助。
2. `__files__.js`：纯命名差异，我们的 `files.*` 已经过 PDF 对齐 + 真机契约验证，不为名字改动。
3. 权限 / 组件规模：那是魔改包自己的选择，我们保持最小权限画像。

## 三、结论

**这份包不能当规范用**：唯一"深挖"的技术主线是把 okhttp3 的内部工具类误判成了 JS 模块加载器
（6 篇文档在错误的结论上越写越深），其余是通用模板文字。可用的只有「模块名清单 / 引擎构成」
这类可以和我们自己对账的事实，而这些事实我们有更强的凭据（第一代 Auto.js Pro 的 335 页 PDF、
本仓库源码与真机契约脚本 `tools/pro-api/*-contract.js`）。

要据此改代码，需要先指定目标（例如「补 V8」「按某张表对齐某个模块」），
按本仓库流程（PDF + 源码 + 真机契约）来做，而不是照抄这份分析。

## 四、模块 / API 逐条对照表

按用户要求单独出了一张表：[`2026-09-15_手机端分析包_模块与API对照.md`](2026-09-15_手机端分析包_模块与API对照.md)
（由 `tools/pro-api/cmp-analysis-api.py report` 幂等生成）。结论摘要：

- **模块**：它提到的 31 个内置模块文件里 **28 个与我们同名**；`__files__.js` 与我们的 `__io__.js`
  是同一个东西（`scope.files = files` 就在那个文件里）；真正对不上的只有 `__sqlite__.js`、
  `__uiobject2__.js` —— 两个都只出现在低可信文档里，第一代 PDF 里也没有。
- **API**：它那份速查表 22 条，21 条在我们 CSV 里都能查到状态（Rhino/QuickJS 均 `实机有`），
  唯一对不上的 `storage.put` 是它把 `storages.create().put()` 写成了模块级方法。
- 另有 2 个模块我们有、它没提：`__io__.js`、`__yolo__.js`（本项目自加的 YOLO）。
