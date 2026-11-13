# AGSL 液态透镜（`AgsLiquidLens`）脚本用法

> 实测版本：dev-1549（K40 / Android 16）。姊妹篇：[AGSL高光层用法.md](AGSL高光层用法.md)（`AgsHighlightView`，做的是**边缘高光**，不是折射）。

## 1. 它做什么

按住容器（比如底栏）的某处 → 浮起一个**圆形透镜**，里面是「**背后真实内容**」的放大 + 色散折射；松手消失。效果与酷安 16.x 底栏按住拖动的液态玻璃同构。

## 2. 为什么必须先截整屏（这是本类的核心取舍）

脚本运行在**独立 Activity** 里，而：

| 方案 | 结果 |
|---|---|
| `RenderEffect` + `RuntimeShader` 的 `content` | ✗ **只能采样本 Activity 的渲染树**，拿不到宿主 App |
| `PixelCopy` | ✗ 只能抓自己 window 的 surface |
| **无障碍截图** `AccessibilityScreenCapturer` | ✅ Android 11+、**无需 MediaProjection 授权、不弹窗**、能拿整屏 |

⇒ 所以流程是：**手指按下 → 截一张整屏 → 当 `uniform shader` 的输入 → 在透镜区域内做放大/色散采样**。
系统对无障碍截图有 ~340ms 限频 ⇒ **只在按下时抓一次**（拖动时背景静止，够用）。

## 3. API

```java
new AgsLiquidLens(Context ctx, float radiusPx)   // radiusPx = 透镜半径（像素，不是 dp）
setMagnification(float m)                        // 放大倍率，实测 1.6~1.8 接近真品
setAberration(float a)                           // 色散强度，实测 0.03（弱）~0.10（很夸张）
attachTo(View host)                              // 把触摸挂到宿主上（hostView 也存下来，用于点击转发）
previewAt(float x, float y)                      // 不依赖触摸，直接在 (x,y) 画一个透镜（自动化验证用）
clearPreview()                                   // 关预览，交回触摸
setScreenOffset(float x, float y)                // 显式指定本 View 在屏幕的位置（收口窗口下 getLocationOnScreen 不可靠）
setCoordDebug(boolean on)                        // 调试：把「采样坐标」当颜色输出（红=X/宽, 绿=Y/高）
```

## 4. 脚本示例（nodejs）

```js
"ui nodejs";
var Lens = Packages.com.stardust.autojs.runtime.api.AgsLiquidLens;

// 1) 造透镜：半径 55dp（真品实测直径约 300px ⇒ 半径 150px，K40 密度 2.75 时 dp(55)≈151）
var lens = new Lens(ctx, dp(55));
lens.setMagnification(1.75);
lens.setAberration(0.045);

// 2) 覆盖整个栏（高度可以给 2 倍，让透镜能溢出栏边界 —— 真品的镜子就是溢出的）
var lp = new Packages.android.widget.FrameLayout.LayoutParams(barW, barH * 2);
bar.setClipChildren(false);                 // 关键：否则透镜被裁成扁圆
bar.addView(lens, lp);

// 3) 收口窗口下 getLocationOnScreen 不可靠 ⇒ 脚本显式告诉它窗口在屏幕哪里
lens.setScreenOffset(0, decorY);

// 4) attachTo 只是把 hostView 记下来（用于点击转发），触摸实际由 onTouchEvent 自己收
lens.attachTo(bar);
```

## 5. 三个必须知道的坑（都踩过）

### 5.1 `onTouchEvent` **必须返回 `true`**
```java
@Override public boolean onTouchEvent(MotionEvent ev) { handleTouch(ev); return true; }
```
Android 只在**事件被消费后**才把后续 MOVE/UP 继续派给这个 View。返回 `false` 时**只拿到 DOWN** ✗ ⇒ 表现是「按住后左右移动，透镜卡在按下位置不动，松手也不消失」✗（真机实测）。

代价：下层 tab 收不到点击 ⇒ 见 5.2。

### 5.2 消费触摸后，必须自己把点击转发给下层
本类在 `UP` 时判断「移动距离 < touchSlop」⇒ 当作点击 ⇒ 递归在 `hostView` 子树里找坐标处的可点击 View 并 `performClick()`：

```
AgsLiquidLens: click forwarded to JsLinearLayout     ← 日志可验证
脚本日志:      切到「数码」                            ← tab 真的切换了
```

### 5.3 `AccessibilityScreenCapturer.isAvailable()` 是**进程内单例**
它查的是**本进程**的 `AccessibilityService.getInstance()`，而脚本跑在 `:script` 隔离进程、无障碍服务在**主进程** ✗ ⇒ 在脚本进程里它**恒为 false** ✗。
**所以绝对不要**用它做前置拦截（`if (!isAvailable()) return;` ✗）—— 那会把 `capture()` 内部本来可用的**跨进程分支**（`RemoteAutomationBridge`）一起拦死 ✗。直接调 `capture()`，它自己会选路 ✓。

## 6. 平台要求

| 项 | 要求 |
|---|---|
| 截图通道 | **Android 11 (API 30)+** + 无障碍服务已开启（服务需具备 `CAPABILITY_CAN_TAKE_SCREENSHOT`；AI.js Pro 的 `capabilities=233` 含该位 ✓）|
| AGSL | **Android 13 (API 33)+**（`RuntimeShader`）；低版本本类静默降级为不画 |
| 权限 | **不需要** MediaProjection / 悬浮窗；只要无障碍服务在跑 |

## 7. 参数怎么调（K40 实测）

| 参数 | 值 | 效果 |
|---|---|---|
| `radiusPx` | `dp(30)`≈83 | 直径 165px —— **偏小** ✗（真品 300px）|
| | **`dp(55)`≈151** | 直径 ≈302px，**对齐真品** ✓ |
| `magnification` | 1.6 ~ 1.8 | 真品目测区间，1.75 折中 ✓ |
| `aberration` | 0.014 | 几乎看不出彩虹边 ✗ |
| | 0.03 ~ 0.045 | 有明显色散 ✓ |
| | 0.10 | 很夸张，边缘糊 ✗ |

**还必须关掉裁剪**：真品的镜子**溢出栏的上下边界**，而 View 只有栏那么高会被裁成扁圆 ✗ ⇒ `bar.setClipChildren(false)` + 透镜 View 高度给 `barH * 2`。

## 8. 排查

| 现象 | 看什么 |
|---|---|
| 透镜完全不出现 | logcat `AgsLiquidLens` 是否有 `capture size WxH`（没有 ⇒ 截图失败/没触发）|
| 有日志但画面没变化 | 是不是 **`onTouchEvent` 返回了 false**（只收到 DOWN）✗ |
| 透镜卡在按下位置不动 | 同上 ✗ |
| 松手不消失 | 同上 ✗（收不到 UP）|
| 透镜是椭圆不是圆 | View 被压扁（本类已用归一化半径 `t = length(d)/lensRadius` 处理 ✓）；也可能是 View 高度太小被裁 |
| 透镜里内容错位 | `setScreenOffset` 传的屏幕位置不对；或窗口收口后 `getLocationOnScreen` 返回窗口内坐标 ✗ |
| 色偏红/绿 | 色散通道采样越界（本类已修正：不要乘 `screenScale` —— 截图与屏幕本就 1:1，乘 `screenHeight/rootHeight` 会放大十几倍把 R/B 推出屏幕）✗ |
| tab 点不动 | 见 §5.2（本类已实现点击转发 ✓）|

日志关键字：`AgsLiquidLens`（`capture size WxH` / `lens geom: view=… loc=… radiusPx=…` / `previewAt x,y` / `click forwarded to XxxView`）。

## 9. 已知边界（照实写）

1. **质感与真品有差距** ✗：真品采样的是 **App 自己的 RenderNode（无损）**，本类只能采样**整屏截图** ⇒ 放大后必然有损，色散/边缘立体感做不到一模一样。
2. **截图限频 ~340ms** ⇒ 拖动时透镜里的内容是「按下那一刻」的画面；若背后内容在动会有滞后。
3. **无障碍服务必须开着**：关掉后降级为不画透镜（不崩）。

## 10. 参考

- 实现：[modules/engine/src/main/java/com/stardust/autojs/runtime/api/AgsLiquidLens.java](../../modules/engine/src/main/java/com/stardust/autojs/runtime/api/AgsLiquidLens.java)
- 截图通道：[modules/engine/src/main/java/com/stardust/autojs/core/image/capture/AccessibilityScreenCapturer.java](../../modules/engine/src/main/java/com/stardust/autojs/core/image/capture/AccessibilityScreenCapturer.java)
- 姊妹篇（边缘高光）：[AGSL高光层用法.md](AGSL高光层用法.md)
- 用户脚本：`/storage/emulated/0/脚本/酷安底栏/coolapk_bar.js`
