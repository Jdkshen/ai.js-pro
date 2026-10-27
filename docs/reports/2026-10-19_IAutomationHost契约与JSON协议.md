# Phase1 P1：IAutomationHost 契约与 JSON 协议

## 1. 角色

- 主进程（com.jdkshen.aijspro）持有 AccessibilityService / Automator / ScreenCapturer / UiSelector 能力，实现 `AutomationHostService`（Binder 服务）。
- :script 进程脚本运行时通过 `AutomationHostClient` 调 AIDL，把 auto/click/text/id/desc/selector/gesture/back/home/screenshot 等 API 代理到主进程执行。

## 2. AIDL

`apps/app/src/main/aidl/com/stardust/autojs/host/IAutomationHost.aidl`：isReady / isAccessibilityServiceEnabled / getLatestNodeDump / findNodes / getNodeInfo / clickNode / longClickNode / setTextNode / click / longClick / swipe / gesture / back / home / recents / notifications / screenshot。

## 3. selectorJson

单个 JSON 对象，字段与 UiSelector 对齐，均为可选，组合为 AND：

```json
{"id":"btn","text":"确定","textContains":"确","textStartsWith":"确","desc":"关闭","descContains":"关","className":"android.widget.Button","packageName":"com.tencent.mm","clickable":true,"enabled":true,"scrollable":false,"depth":3,"index":0,"textMatches":"^确.*$","bounds":{"left":0,"top":0,"right":100,"bottom":100}}
```

- `findNodes` 返回 JSON 数组，元素：`{"path":[0,2,1],"text":"","desc":"","id":"","className":"","packageName":"","bounds":[l,t,r,b],"clickable":bool,"enabled":bool,"scrollable":bool,"editable":bool,"childCount":int,"indexInParent":int,"depth":int}`；
- `childCount` 为 Android 节点的实际直接子节点数；`indexInParent` 为路径最后一项，根节点为 `-1`。这些字段也由 `getNodeInfo` 和完整 dump 返回。
- `path` 是从根窗口到该节点的子节点下标路径（用于二次定位）；
- `getNodeInfo(selector,index)` 返回单个元素 JSON 或空串；
- `clickNode/longClickNode/setTextNode` 先用 selector 匹配（index 取第 N 个，允许 -1 表示第 0 个）再执行动作，返回是否成功。

## 4. strokesJson

`gesture` 参数为手势描述：`[[{"x":100,"y":200,"t":0},{"x":100,"y":500,"t":300}],[...]]`。外层多条 stroke，内层点序列，`t` 为该 stroke 内相对起点的毫秒偏移；主进程用 GestureDescription 构造。

Node StrokeDescription 的延迟通过每条 stroke 第一个点的可选 `startTime`（相对整个手势开始的毫秒数，默认 0）传递；该 stroke 内的 `t` 仍从 0 开始。省略 `startTime` 的现有调用保持原来的时序。

## 5. 其他约定

- `click(x,y)/longClick/swipe` 为坐标操作；
- `screenshot(targetPath)` 保存 PNG 到 targetPath（:script 传私有目录路径），成功返回实际路径，失败返回空串；
- 所有方法同步、允许脚本线程调用（主进程内部切无障碍主线程执行并等待，超时 5s 返回 false）；
- `isReady()` = AccessibilityService 已连接；`isAccessibilityServiceEnabled()` 只查设置状态（不拉起设置页）；
- 绑定：`:script` 用显式 ComponentName（`com.jdkshen.aijspro` / `com.jdkshen.aijspro.automation.AutomationHostService`）bind，exported=false，同一 uid。

## 6. 落地任务

- task-18：示例脚本/引擎自动化 API 使用面盘点（spec）；
- task-19：主进程 AutomationHostService 实现；
- task-20：:script 引擎侧远程自动化后端 + AutomationHostClient + ScriptHostService 注入 + auto 门控开关（默认关）；
- task-21：定时任务/通知/音量键停止统一经 PocScriptHost 调度到 :script。


## 2026-10-28 扩展：通用节点动作 `nodeAction`

为 Node24 全量对齐 Pro UiObject，`IAutomationHost` 增加：

```java
boolean nodeAction(String selectorJson, int index, String action, String argumentsJson);
```

- `action`：`copy/cut/paste/select/collapse/expand/dismiss/show/scrollForward/scrollBackward/scrollUp/scrollDown/scrollLeft/scrollRight/contextClick/focus/clearFocus/accessibilityFocus/clearAccessibilityFocus/setSelection/scrollTo/setProgress`
- `argumentsJson`：JSON 数组；`setSelection` 用 `[start,end]`，`scrollTo` 用 `[row,column]`，`setProgress` 用 `[value]`，其余 `[]`
- 返回 `boolean`；主进程按 selector 重新定位第 `index` 个节点后执行 `AccessibilityNodeInfo.performAction`，失败返回 false，不用坐标兜底
- Node24 的 `select()` 节点方法已接入；MCP 新增 `ui_node_action` 工具使用独立主进程实现
