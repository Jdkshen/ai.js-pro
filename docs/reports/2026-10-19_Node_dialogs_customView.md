# Node dialogs.customView：XML 布局描述协议（task-161）

日期：2026-10-19。执行：t1-native-services。验证机：K40（cccc62c7）。

## 1. 结论

- `dialogs.build({...}).customView(xml).on(...).show()` 现在可用了（K40 已验证）：Node 只传 **XML 布局字符串**，主进程 `NodeUiDialogActivity` 用引擎已有的 `NodeUiHost.inflateInlineLayout` 真实 inflate Auto.js 布局并放进对话框内容区；
- 基础控件事件回传：XML 里带 `id` 的控件点击 → `dialog.on('custom_view_click', (id, payload, dialog))`；EditText 文字变化 → `dialog.on('custom_view_text', ...)`；两类都是非终止事件，不会自动关闭对话框；
- 另提供 `dialog.customView(xml)`（buildDialog 句柄上）以及链式 builder 的 title/content/positive/negative/items/input/cancelable/on/show。

## 2. 协议

### 2.1 Node → 主进程

    {kind:'ui', op:'dialog', async:true, token:'...', customView:'<vertical>...</vertical>', title?, cancelable?, positive?, negative?}

- customView/customViewXml 二选一；为空则退回原有内置类型（alert/confirm/select/input/progress…）；
- 有 customView 时 dialogType=custom；不显式给 positive 时不加系统「确定」，让 XML 自己带按钮；negative 仍可显式给。

### 2.2 主进程 → Node（ACTION_DIALOG_EVENT）

    {token, event:'custom_view_click', payload:{id:'ok', text:'自定义确定'}}
    {token, event:'custom_view_text',  payload:{id:'input', text:'...'}}

prelude 映射：`dialog.on('custom_view_click', (id, payload, dialog) => …)`、`dialog.on('custom_view_text', …)`、`dialog.on('custom_view_event', …)`。

## 3. 实现

- Java（apps/app/src/main/java/com/jdkshen/aijspro/engine/NodeUiDialogActivity.java）：
  - resolveDialogType 识别 customView；
  - show() 里 `ContextThemeWrapper(Theme_Material_Light_NoActionBar)` + `NodeUiHost.inflateInlineLayout(themed, xml)` + ScrollView 包裹 + `builder.setView`；
  - `NodeUiHost.buildInlineViews(content, xml)` 拿到 id→viewId，逐个挂 OnClickListener / TextWatcher，事件经 `sendEvent()` 广播；
  - 布局失败抛中文 `dialogs.customView 布局失败：…`（经 dialog 错误通道回 Node）。
- prelude（modules/engine/src/main/assets/node24/prelude.js）：
  - `_receiveHostEvent` 新增 custom_view_click/custom_view_text 非终止分支；
  - `DialogHandle.prototype.customView(xml)`；
  - `dialogs.build(properties)` 链式 builder（customView/title/message/content/positive/negative/neutral/items/input/cancelable/on/show）。

## 4. 修改文件

- apps/app/src/main/java/com/jdkshen/aijspro/engine/NodeUiDialogActivity.java；
- modules/engine/src/main/assets/node24/prelude.js。

编译验证：`:engine:compileDebugJavaWithJavac`、`:app:compileMiuixCompatDebugJavaWithJavac` BUILD SUCCESSFUL（未跑完整 gradle）。

## 5. K40 验证（dev-1109）PASS

单工程：/sdcard/脚本/node161-customview/（main.js 用 dialogs.build().customView(xml).on(...).show()，XML 含 text/input/两个 button），结果写 /sdcard/node161/customview-result.json。

- 宿主日志：`NodeUiDialog: customView bound ids={"input":20161210,"ok":20161211,"cancel":20161212}`、`dialog shown ... type=custom async=true title=NODE161 对话框`；
- UI dump 明细：`[NODE161 对话框] / [NODE161 customView] / [下面的控件来自 XML 布局] / [点我输入文字] / [自定义确定] at 412,1481 / [自定义取消] at 672,1481` —— 说明 XML 里的 text/input/button 都被真实 inflate 渲染（不是文本占位）；
- Node 事件：
  - `NODE161 SHOWN event` / `SHOW resolved`（show() Promise 正常 resolve）；
  - adb 点输入框并 `input text hello161` → 连续 `custom_view_text` 事件（TEXT id=input text="h" … "hello161"）；
  - adb 点「自定义确定」→ `NODE161 CLICK id=ok payload={"id":"ok","text":"自定义确定"}`，对话框保持打开（非终止事件），脚本写盘后退出；
- 最终 result JSON：{done:true, extra:{id:"ok", payload:{id:"ok", text:"自定义确定"}}, steps:[SHOWN event, SHOW resolved, TEXT×8, CLICK id=ok …]}；无崩溃/异常。

证据：.artifacts/node161-verify/（ui-customview.xml、customview-logcat.txt、customview-result.json、main.js）。

## 6. 已知限制

- XML 布局走 NodeUiHost.inflateInlineLayout：grid/list/canvas 在无 ScriptRuntime 的 Node 宿主路径会被替换为占位文本（与 Activity.layoutXml 限制一致）；工程 res 资源（@drawable/@color）不可用，基础控件与 material 控件可用；
- 目前支持 click 与 EditText 文本变化；长按/选中/滚动等更复杂事件未接线；Node 侧暂不支持回写控件属性（setText/setEnabled 等），需要时可在同一 ACTION_DIALOG_EVENT 通道上加 request/response；
- customView 与内置类型互斥（同时给时以 customView 为准）。
