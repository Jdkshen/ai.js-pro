# 悬浮日志 SCRIPT_ERROR 与 item 绑定 warning（task-194）

日期：2026-10-20。执行：t1-native-services。验证机：K40（cccc62c7，dev-1139 待填）。

## 1. 问题

用户日志（dev-1138）：
- 悬浮日志.node.js 首次运行：01:01:04.886 打印结束行 `运行结束，用时15.121000秒`，200ms 后 `E SCRIPT_ERROR` + `ScriptException: SCRIPT_ERROR`（Node24JavaScriptEngine.doExecution:237），无 message；第二次运行正常；
- 使用Android原生界面/main.js：一条 `[ui] 探测 item 布局失败： Error: 无法绑定 click：view "" 没有 id` warning（Adapter item 探测 registerViewClick 抛错，被捕获）。

## 2. 根因

- 悬浮日志示例 ✕ 回调是 `window.close(); process.exit()`；`floating_window.close()` 内部 `overlayCall('close')` 在宿主已移除 overlay/已关闭时抛 apiError，异常发生在 process.exit() 之前 → Node 脚本以非 0/异常退出，Node24 结果被包装成 `SCRIPT_ERROR`（message 为空时只剩泛化错误）；
- `Node24Service` 之前只认 `succeeded()`（exitCode==0 且 error 为空），关闭窗口时的 pending host 错误也会让 exit(0) 被当成 SCRIPT_ERROR；
- `ui.js registerViewClick` 对无 id 的探测视图直接 throw，Adapter item 探测捕获后打 W，属于预期路径的噪音。

## 3. 实现

1. `floating_window.js`：`_hideNow` 的宿主 close 失败降级为 debug（关闭是幂等操作，不能阻断后续 process.exit）；
2. `Node24Service`：成功判定改为 `!cancelled && !timedOut && exitCode==0`（process.exit()/exit(0) 一律成功，即使 native 带回非空 error 文本）；非 0 失败时 message 为空则填 `Node 脚本异常退出，exitCode=N`；
3. `Node24JavaScriptEngine`：SCRIPT_ERROR 无 message 时带上 exitCode（`SCRIPT_ERROR（exitCode=N）`），可诊断；
4. `ui.js`：无 id 的 click 绑定降级为 no-op（返回 false），`detectItemLayout` 捕获后改用 console.debug，不再打 W；
5. 示例未改。

## 4. 修改文件

- modules/engine/src/main/assets/node24/modules/floating_window.js
- modules/engine/src/main/assets/node24/ui.js
- modules/engine/src/main/java/org/autojs/source/engine/android/Node24Service.java
- modules/engine/src/main/java/com/stardust/autojs/engine/Node24JavaScriptEngine.java

编译：`:engine:compileDebugJavaWithJavac` + `:app:compileMiuixCompatDebugJavaWithJavac -PflutterPoc=true` BUILD SUCCESSFUL（15s）；prelude ui.js/floating_window.js `node --check` 通过。

## 5. K40 验证（dev-1139 待填）

（待填：悬浮日志 ✕ 关闭 exitCode=0 无 SCRIPT_ERROR；process.exit(0) SUCCESS；process.exit(3) 报错带 exitCode=3；main.js 无「无法绑定 click」warning）

## 6. 限制

1. 关闭路径的宿主 close 错误不再抛出，但会在 debug 控制台留一行（默认不可见）；
2. 真实脚本异常（exitCode 非 0/JS 抛出）仍照常报错，并给出 exitCode/消息。
