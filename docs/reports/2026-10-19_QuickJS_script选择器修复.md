# QuickJS :script 选择器链修复（task-153，2026-10-19）

## 现象
- QuickJS 脚本在 `:script` 进程执行 `text("设置").findOnce()` 报：
  `:script 进程暂不支持 QuickJS 选择器链（批一）；请改用坐标动作（click/swipe/back 等）或使用 Rhino 引擎脚本`；
- 同机同路径的 Rhino `:script` 脚本正常（本轮复现 `SELRHINO=null`，无异常）。

## 根因
- `QuickJsHostBridge.selectorCreate()` 在 `RemoteAutomationBridge.isActive()` 时直接抛 ScriptException；
- 当时理由：QuickJS 的 cpp 包装层按 `UiGlobalSelector/UiObject/UiObjectCollection` 强类型处理句柄，而远程自动化返回的是 `RemoteUiSelector/RemoteUiObject/RemoteUiObjectCollection`，批一未适配；
- Rhino 侧已通过 `ScriptRuntime.selector() -> RemoteUiSelector` 远程化，所以只有 QuickJS `:script` 失败。

## 修复
- `QuickJsHostBridge.selectorCreate()`：远程模式下返回 `new RemoteUiSelector()` 句柄（与 Rhino 同一条主进程自动化代理链路）；
- `automatorCall()` 增加 remote 分支：
  - `RemoteUiSelector`：text/id/desc/className/packageName 等过滤、find/findOnce/findOne/untilFind/untilFindOne/exists/waitFor、click/longClick/setText/scrollForward/scrollBackward；
  - `RemoteUiObject`：text/desc/id/className/packageName、clickable/enabled/scrollable、depth/indexInParent/childCount、bounds、exists、click/longClick/setText/scrollForward/scrollBackward、parent/child/find/findOne/toString；
  - `RemoteUiObjectCollection`：size/empty/nonEmpty/get/toString；click/longClick/setText 逐节点执行并返回是否有成功；
- `encodeAutomatorResult()` 支持上述远程类型（对象/集合转句柄，选择器过滤器返回 void）；
- 未改 cpp/QuickJS 包装层：`wrapAutomatorSelector/Object/Collection` 的句柄协议对远程类型同样适用。

## 验证
- 修复前 K40（dev-1092）：`SELQJS_ERR=...:script 进程暂不支持 QuickJS 选择器链（批一）...`；`SELRHINO=null`；
- **修复后 K40（dev-1096）已验证 ✅**：`/sdcard/脚本/selqjs.js` → `SELQJS=null`；`/sdcard/脚本/selrhino.js` → `SELRHINO=null`；两者均不再抛「:script 进程暂不支持 QuickJS 选择器链」。
- 编译：`:engine:compileDebugJavaWithJavac` BUILD SUCCESSFUL。

## 涉及文件
- `modules/engine/src/main/java/com/stardust/autojs/engine/QuickJsHostBridge.java`

## 后续
- 若无障碍服务未开启，`findOnce` 走主进程代理会给出「无障碍服务未就绪」中文错误（符合契约）；
- AUTO 门控/远程选择器 predicate（filter/addFilter）本轮未覆盖，如需可另立任务。

## 完成表（dev-1096 K40）

| 用例 | 修复前（dev-1092） | 修复后（dev-1096） |
|---|---|---|
| QuickJS :script `text('设置').findOnce()` | ❌ `SELQJS_ERR=:script 进程暂不支持 QuickJS 选择器链（批一）…` | ✅ `SELQJS=null` |
| Rhino :script 同脚本 | ✅ `SELRHINO=null` | ✅ `SELRHINO=null` |

证据：`selqjs.js` / `selrhino.js`（/sdcard/脚本），logcat `SELQJS=`/`SELRHINO=`；引擎编译 `:engine:compileDebugJavaWithJavac` BUILD SUCCESSFUL。
