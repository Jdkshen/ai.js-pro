# Node UI owner 隔离：双 job 停一个不再互删浮窗（task-188）

日期：2026-10-20。执行：t1-native-services。验证机：K40（cccc62c7，dev-1134）；MI8 被其它任务占用且未装 dev-1134，未验证。

## 1. 问题

t2 dev-1133 复验：悬浮日志连跑两次 → 2 个独立面板（overlay-1/overlay-2，任务页 2 行）；停第一行后，存活 job（:node24_2）的第二个面板也消失（dev-1132 正常）。怀疑 task-186 新增 fire-and-forget `closeOverlaysIfBound()` 的 closeAll 没带 ownerId。

## 2. 根因

- task-186 清理阶段改用 `NodeUiActivityClient.closeOverlaysIfBound()`，其固定发送 `{"action":"closeAll"}` **无 owner**；
- task-183 主进程 `NodeUiBridgeService` 的 closeAll 只在 owner 非空时按 owner 过滤，空 owner 保持旧的全清语义；
- 因此停旧 job 时 closeAll 把所有并行 job 的 overlay 一起清掉。

## 3. 实现

1. `NodeUiActivityClient` 新增 `closeOverlaysIfBound(String owner)`：owner 非空时 closeAll 请求带 owner；无参重载保留旧语义（向后兼容）；
2. `Node24HostBridge.closeUiOverlay()` 清理时传本实例 `mOverlayOwner`（task-183 每 job 一个 owner）；
3. 复核其它路径：`NodeUiHost.closeCurrent` 走 Activity close（按 session，不调 overlay closeAll）；`NodeUiHost.closeOverlay` 只删本进程 sOverlayView/入队 destroy；全仓库无其它无 owner closeAll 调用点（native-process 亦复核确认）。

## 4. 修改文件

- `modules/engine/src/main/java/com/stardust/autojs/engine/node24/NodeUiActivityClient.java`；
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24HostBridge.java`。

编译：`:engine:compileDebugJavaWithJavac` + `:app:compileMiuixCompatDebugJavaWithJavac -PflutterPoc=true` BUILD SUCCESSFUL（17s）。

## 5. K40 验证（dev-1134）PASS

### 5.1 双 UI job 停一个，另一个面板保留

```
00:43:32.712 overlay show id=overlay-1 (job1=3caff533...)
00:43:34.115 overlay show id=overlay-2 (job2=7a27c567...)
dumpsys window: bigPanels=2，:node24_1/:node24_2 均存活
00:43:37.7 cancel job1 → execute returned cancelled=true；
        cleanup done uiClose=2ms bridgeClose=9ms；node24 job removed job1
dumpsys window: bigPanels=1（只剩 overlay-2），:node24_2 仍存活，job2 未被取消
PocNode24Jobs: registered 2 → removed 1（任务页剩 1 行）
```

### 5.2 单 job 停止仍清理干净

取消最后的 job2：`bigPanels=0`，`cleanup done uiClose=1ms bridgeClose=11ms`，job2 removed。

### 5.3 BACK ≤1s 重跑不回归

```
00:44:05.738 C-BACK pressed
00:44:05.752 cleanup done uiClose=3ms bridgeClose=2ms
00:44:05.860 job start 新 id（BACK 后 122ms）
00:44:06.031 新 UI mounted
```
无「正在停止」/disconnect/ScriptException/错误 Toast。

## 6. MI8 验证说明

MI8 当前为 1.0.2 版本（未装 dev-1134），且有其它任务 headless job 运行（scriptLen=2122），无法验证；建议装 dev-1134 后空闲复测（或 t2 验收时顺带）。

## 7. 限制

1. owner 为 Node24HostBridge 实例级（每 job 一个），清理请求必须带该实例 owner；后续新增 overlay 清理路径时不能传空；
2. overlay 的 owner 在 show 时由 `Node24HostBridge.handleUiJson` 自动写入，若将来有绕过 bridge 直接发 floatingWindow 的路径，需要自行带 owner。
