# Node axios 默认导出与 wakeUp 实现（task-179）

日期：2026-10-19。执行：t1-native-services。验证机：K40（cccc62c7，dev-1127 待填）。

## 1. 问题

- #37 播放音乐.js：`require("axios").default` 为 undefined → `axios.get` TypeError 被 async 吞成 SCRIPT_ERROR（下载阶段 24.4s 无消息）；probe：axios keys=get,post,request，default undefined。
- #48 电量优化与设备唤醒.js：`power_manager.wakeUp` 存在但运行时只打印「[Auto.js] wakeUp 暂未实现（需要 WAKE_LOCK/权限）」，屏幕不亮；isScreenOn/lockScreen 已可用。

## 2. 实现

### 2.1 axios shim 默认导出 interop

- prelude `axiosModule` 增加 `axios.default = axios; axios.__esModule = true;`（CJS 与 TS/ESM default 导入互通，`require('axios').default` 与 `require('axios')` 都指向同一对象）；
- 其余内置 shim 检查：toast/device/lang/power_manager/accessibility 等示例均用解构导入，无 default 需求；fs/path/stream/util/http/https 由 libnode 原生提供，interop 正常，不需要额外包装。

### 2.2 power_manager.wakeUp（宿主实现）

- prelude：`wakeUp()` 改为 `domain('device','wakeUp')`；`isScreenOn()` 优先 `domain('device','isScreenOn')`（回退旧 isInteractive）；
- Node24HostBridge（:node24 进程，`mContext`）：
  1. 运行期反射 `PowerManager.wakeUp(long[, String reason])`（公开 compileSdk 不含该 @SystemApi 方法，只能反射）；
  2. 失败（方法不存在/隐藏 API 限制/SecurityException）→ 回退**公开 API wake lock**：`SCREEN_BRIGHT_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP|ON_AFTER_RELEASE` 短时 acquire(3000ms)（WAKE_LOCK 普通权限即可）；
  3. 缺 WAKE_LOCK 或两者都失败 → 明确中文：缺权限/系统拒绝/Android 12+ 后台唤醒受限，请前台运行或允许「保持唤醒」；
  4. 返回 `{wakeUp:true, method:'wakeUp'|'wakeLock', screenOn, viaWakeLockPermission}`。

## 3. 修改文件

- `modules/engine/src/main/assets/node24/prelude.js`（axios default / power_manager 宿主调用）；
- `modules/engine/src/main/java/org/autojs/source/engine/android/Node24HostBridge.java`（device wakeUp/isScreenOn 宿主实现）。

编译：`:engine:compileDebugJavaWithJavac` + `:app:compileMiuixCompatDebugJavaWithJavac -PflutterPoc=true` BUILD SUCCESSFUL（56s）；prelude `node --check` 通过。

## 4. K40 验证（dev-1127 待填）

（待填：#37 下载阶段 axios.default + stream pipeline 跑通；#48 锁屏→5s→wakeUp 点亮，dumpsys power wakefulness 验证）

## 5. 限制

1. `PowerManager.wakeUp` 是 @SystemApi（需 DEVICE_POWER 签名权限），普通应用通常只能走 wake lock 回退；实际点亮由 SCREEN_BRIGHT_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP 完成；
2. Android 12+ 后台/Doze 限制下唤醒可能被系统拒绝，此时给中文提示；
3. 未实现 `power_manager.newWakeLock`（Pro 有该函数），如需可后续补；
4. `isIgnoringBatteryOptimizations` 仍为 prelude 简化实现（本轮未改）。
