# Node24 modules 注册补丁（task-146，待 native-process/Lead 合并）

本目录新增 3 个独立 JS 模块文件（**不修改 prelude.js**）：

| 文件 | 注册模块 | 作用 |
| --- | --- | --- |
| `color.js` | `color` | Pro 语义完整版：Color / HSVColor / HSLColor / toColorInt |
| `floating_window.js` | `floating_window` | Pro 面 createWindow/rawWindow/canDrawOverlays/manageDrawOverlays/FLAG_*/setPosition/setSize/on |
| `accessibility_extra.js` | `accessibility`（合并）、`ui_selector`、`ui_object`、`selector` | 选择器/node 查询/act/waitFor（基于 flutterPoc 的 AutomationHostClient 反射） |

三个文件都是独立 IIFE，加载后自行调用 `__ajsRegisterModule(...)`；对 `color`/`floating_window`/`accessibility` 采用**合并旧对象**的方式覆盖，prelude 里已有的状态/动作 API 仍然保留。

## 推荐合并方式 A（Java 加载，最小侵入，推荐）

不要往 `prelude.js` 里粘代码（native-process 正在改 work_manager）。在 `modules/engine/src/main/java/org/autojs/source/engine/android/Node24Prelude.java` 的 `wrap(Context, String, String)` 里，**在 ui.js 之后、用户源码之前**追加：

```java
private static final String[] MODULE_ASSETS = {
        "node24/modules/color.js",
        "node24/modules/floating_window.js",
        "node24/modules/accessibility_extra.js"
};

// Node24Prelude.wrap(...) 现有：
// builder.append(prelude).append("\n;\n");
// String ui = optionalAsset(context, UI_ASSET); if (ui != null ...) builder.append(ui).append("\n;\n");
// 在此之后、builder.append(source) 之前插入：
for (String moduleAsset : MODULE_ASSETS) {
    String moduleCode = optionalAsset(context, moduleAsset);
    if (moduleCode != null && !moduleCode.isEmpty()) {
        builder.append(moduleCode).append("\n;\n");
    }
}
```

这样 `require('color')` / `require('floating_window')` / `require('accessibility')` / `require('ui_selector')` / `require('ui_object')` 在用户脚本执行前就已注册；prelude 文件零改动，不会和 work_manager 冲突。

## 备选方式 B（纯 JS 合并，若 native-process 不想动 Java）

把三个文件内容按顺序追加到 `prelude.js` 末尾（即 prelude 主 IIFE 的 `})();` 之后、ui.js 拼接之前）：

```js
// prelude.js 文件最末尾（不是 IIFE 内部）
/* —— 以下内容来自 node24/modules/color.js —— */
/* —— 以下内容来自 node24/modules/floating_window.js —— */
/* —— 以下内容来自 node24/modules/accessibility_extra.js —— */
```

注意：文件顺序必须是 color → floating_window → accessibility_extra（accessibility_extra 依赖 prelude 的 `$autojs` 和 legacy accessibility 对象；三者互相独立，但保持顺序更直观）。

## 加载/缓存语义

- `__ajsRegisterModule(name, factory)` 会删除该模块的 cache；旧 factory 被覆盖。
- 由于三个文件在被加载时**立即**构造模块对象并同步更新 `globalThis.__ajsBuiltinModules.<name>` 与 `globalThis.$autojs.<name>`，`$autojs.color` 等不会停留在旧对象。
- `require` 包装优先查注册表（loadRegistered），因此覆盖后 `require('color')` 等返回新模块。

## 验证

- 模块契约/语义用 `tools/test-node24-pro-modules.cjs`（系统 Node + mock `__autojsHost`）。
- 真机 K40（dev-1083 flutterPoc）单工程 JSON 结果：见同目录 `VERIFY-K40.md`。
