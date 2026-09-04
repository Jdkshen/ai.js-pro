# MiMo 返修任务：抽屉按钮点击无响应

## 任务结论

当前版本点击左上角三横线没有反应，根因已经定位。不是手机兼容性、触摸阈值或 ImGui 坐标问题，而是本轮改造同时切断了原生按钮与 ImGui 按钮两条点击链路。

请先只修复本问题并完成真机验证，不要顺带重写抽屉结构、页面滑动或其他功能。

## 根因一：原生按钮创建后又被删除

文件：

`apps/app/src/main/java/com/jdkshen/aijspro/ui/imgui/ImGuiWorkspaceDrawer.java`

构造函数先执行：

```java
buildHamburgerButton();
```

该方法把 `mHamburgerButton` 加入 `mContent`。但是 Activity 随后调用 `setWorkspaceContent(surfaceView)`，当前实现又执行：

```java
mContent.removeAllViews();
mContent.addView(view, ...);
```

`removeAllViews()` 会把刚创建的 `mHamburgerButton` 一并移除。字段仍然非空，但按钮已经不在 View 树中，因此看不到原生按钮，也不可能收到点击事件。

### 必须修改

让 `setWorkspaceContent()` 在替换 SurfaceView 后重新添加并置顶原生按钮。需要满足：

1. `mContent` 的底层子 View 是 `ImGuiSurfaceView`。
2. `mHamburgerButton` 始终是 `mContent` 的最上层子 View。
3. 多次调用 `setWorkspaceContent()` 不得重复添加按钮或触发 `View already has a parent`。
4. 添加完成后调用 `bringToFront()`，并重新请求 WindowInsets。
5. 不要只判断 `mHamburgerButton != null`；还必须检查 `mHamburgerButton.getParent()`。

推荐结构：

```java
void setWorkspaceContent(View view) {
    mContent.removeAllViews();
    mContent.addView(view, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

    if (mHamburgerButton == null) {
        buildHamburgerButton();
    } else {
        ViewParent parent = mHamburgerButton.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(mHamburgerButton);
        }
        mContent.addView(mHamburgerButton);
    }
    mHamburgerButton.bringToFront();
    ViewCompat.requestApplyInsets(mHamburgerButton);
}
```

可以采用等价实现，但必须保证层级和生命周期正确。

## 根因二：ImGui 旧按钮也被改成了“只显示、不点击”

文件：

`apps/app/src/main/cpp/autojs_imgui.cpp`

`drawTopBar()` 当前仍绘制左上角三横线并创建 `InvisibleButton("##navigation", ...)`，但没有检查返回值，也明确删除了点击动作：

```cpp
ImGui::InvisibleButton("##navigation", btnSize);
// do NOT handle clicks here
```

因此原生按钮被移除后，屏幕上看到的只是 ImGui 画出来的三横线；点击它必然没有反应。

### 必须增加兜底链路

现有 Java 已经定义并处理 `ACTION_OPEN_NATIVE_DRAWER = 71`，C++ 也已有 `kActionOpenNativeDrawer = 71`。应让 ImGui 图标在原生覆盖按钮不存在、位置异常或触摸未被其消费时仍能打开原生抽屉：

```cpp
const bool clicked = ImGui::InvisibleButton("##navigation", btnSize);
if (clicked && gAllowActivationThisFrame) {
    queueAction(kActionOpenNativeDrawer);
}
```

原生按钮正常位于最上层时会先消费触摸，不会产生双触发；ImGui 点击链路作为故障兜底保留。

## 返修后的新回归：两个三横线图标发生重叠

最新真机截图已经确认：按钮恢复点击后，左上角出现双层三横线，视觉发粗、错位，局部压住标题区域。

原因是现在存在两个视觉绘制者：

1. `ImGuiWorkspaceDrawer.buildHamburgerButton()` 的原生 `ImageView` 显示 `ic_menu_hamburger`。
2. `autojs_imgui.cpp::drawTopBar()` 又用三条 `AddLine()` 绘制了一套 ImGui 三横线。

### 必须修复为“单一视觉、双通路点击”

- 视觉图标只能由原生 `ImageView` 绘制。
- ImGui 继续保留同位置的 `InvisibleButton` 和 `queueAction(kActionOpenNativeDrawer)` 作为点击兜底，但不要再执行三条 `AddLine()`。
- ImGui 必须继续保留占位宽度，避免标题左移到原生按钮下方。
- 原生按钮的视觉尺寸使用 `24dp`，点击热区至少 `48dp`；不要通过绘制第二个图标扩大热区。
- 原生按钮加入后调用 `bringToFront()`，但不得覆盖标题实际布局区域。

推荐的 C++ 结构：

```cpp
ImVec2 btnSize(56.0f * gUiScale, 40.0f * gUiScale);
const bool navClicked = ImGui::InvisibleButton("##navigation", btnSize);
if (navClicked && gAllowActivationThisFrame) {
    queueAction(kActionOpenNativeDrawer);
}
// 此处不再 AddLine 绘制三横线；视觉只由 Android ImageView 提供。
```

占位宽度应与最终原生按钮热区和标题起点统一计算，不能继续一边使用原生 `56dp`、另一边固定使用 ImGui `40dp + 18dp gap` 后凭肉眼对齐。

### 新增验收

1. 对左上角区域截图放大检查，只能看到一组三横线。
2. 三条横线粗细一致，不得出现重影、双边缘或位置偏移。
3. “AI.js Pro” 标题不得与按钮热区或图标重叠。
4. 点击图标中心及其四周空白热区均能打开抽屉。
5. 竖屏、横屏、刘海屏、大字体下重复上述检查。

## 必查的关联问题

### 1. 原生按钮和 ImGui 图标必须重合

当前原生按钮使用 `56dp` 点击区域，ImGui 占位区域为 `40 * gUiScale`。请在以下设备上确认视觉中心和点击区域一致：

- 刘海屏、挖孔屏、普通直屏。
- 竖屏和横屏。
- 320dp、360dp、411dp、600dp、840dp 可用宽度。
- 系统字体 0.85、1.0、1.3、1.5 倍。

不能出现两个三横线图标错位叠加。

### 2. WindowInsets 必须在按钮重新加入后刷新

按钮第一次创建时设置的 Insets listener 不代表重新加入 View 树后一定立即拥有正确 Insets。重新添加后主动调用 `ViewCompat.requestApplyInsets()`，并确认状态栏、刘海和左侧挖孔不会遮住按钮。

### 3. 返回键行为

抽屉打开时按返回键只关闭抽屉，不退出工作台；抽屉关闭后才执行工作台原有返回逻辑。

### 4. 不要再次引入双手势所有者

左边缘抽屉手势继续只交给 `DrawerLayout`。不要在 `ImGuiSurfaceView` 或 Activity 再增加一套边缘抽屉检测。

## 本轮代码审查发现的其他适配缺口

这些问题不应阻塞“抽屉按钮恢复”，但完成按钮修复后需要继续处理：

1. `ImGuiSurfaceView.onTouchEvent()` 仍直接把 `event.getX()/getY()` 传给 native，没有实现任务文档要求的 View 坐标到实际 Surface/EGL 尺寸坐标映射。
2. `setViewportMetrics()` 在 UI 线程写 `gViewport`、`gUiScale`、`gUiConfigured`，渲染线程同时读取；`currentLayoutMode()` 读取 `gViewport` 时也未加锁，存在 C++ 数据竞争。请统一在渲染锁下提交快照，或只写 pending metrics，再由渲染线程应用。
3. `safeInsetLeftPx` 和 `safeInsetTopPx` 目前只保存未用于 ImGui 顶栏布局；刘海、挖孔和横屏侧边状态栏仍可能遮挡内容。
4. `SurfaceView.updateSystemGestureExclusion()` 当前提交的是横跨整个 View 宽度的排除矩形，不只是左侧抽屉边缘，会影响系统返回手势。应把抽屉排除区域限制在左侧合理宽度，并避免与 `DrawerLayout` 重复设置。
5. 响应式断点目前主要改变顶栏按钮数量、插件列数、行高和 FAB 边距；面包屑、路径操作行、列表文字截断、横屏高度不足等还没有完整验证。

## 构建要求

1. 重新编译三个 ABI 的 `libautojs_imgui.so`：`armeabi-v7a`、`arm64-v8a`、`x86`。
2. 再执行 `:app:assembleCommonDebug`，不能只运行 Gradle 后复用旧 `.so`。
3. 输出最终 APK 的路径、大小、SHA256 和构建时间。
4. 确认 APK 内三个 ABI 的 `.so` 时间和符号与当前 C++ 源码一致。

## 必须完成的真机验收

至少在原问题手机及另一台不同分辨率手机执行：

1. 冷启动后连续点击左上角按钮 50 次，每次均能打开抽屉。
2. 抽屉开关连续循环 50 次，不丢点击、不闪退、不出现双开动画。
3. 快速单击、长按后松手、轻微移动后松手均不能变成无响应。
4. 从左边缘滑动可打开抽屉；页面中部左右滑动仍只切换分页。
5. 横竖屏各测试 20 次，旋转后首次点击立即有效。
6. 大字体、刘海/挖孔和手势导航下按钮不被遮挡。
7. 抽屉打开时返回键只关闭抽屉。
8. 完成后提供按钮关闭和打开状态截图，以及关键 `adb logcat`，不能只报告“构建成功”。

## 完成标准

只有同时满足以下条件才算完成：

- 原生按钮仍在 View 树中且位于 SurfaceView 上方。
- ImGui 按钮保留 `ACTION_OPEN_NATIVE_DRAWER` 兜底。
- 不存在重复图标、重复触发或触摸穿透。
- 两台真机完成上述点击和旋转测试。
- native 三 ABI 与最终 APK 均重新构建。

## 小米 8 真机专项回归（2026-09-02）

### 设备信息

```text
设备：MI 8 Explorer Edition（ursa）
系统：Android 15 / API 35（LineageOS）
物理分辨率：1080 × 2248
物理密度：440 dpi，density=2.75
应用：com.jdkshen.aijspro 4.4.1 Alpha1
```

已核对手机安装包 SHA256，与本地 `app-common-arm64-v8a-debug.apk` 完全一致，排除装错 APK。

### 实测结论

1. 从屏幕左边缘向右滑动可以打开抽屉，说明 `DrawerLayout`、抽屉内容和边缘手势链路本身可用。
2. 左上角按钮点击也会触发抽屉，但小米 8 上动画过程较慢，短时间内停留在半开状态，容易误判为“没反应”。
3. 半开状态下三横线按钮会被抽屉从左向右逐步遮住，截图表现为图标被切开或只剩半个。
4. 原因是当前只在 `onDrawerOpened()` 中调用 `setHamburgerVisible(false)`。该回调要等抽屉完全打开才执行；在 `0 < slideOffset < 1` 的整个滑动/吸附动画期间，按钮仍然可见。
5. `ImGuiSurfaceView.run()` 仍然是无条件 `while (mRunning)` 连续渲染；虽然 EGL 请求 VSync，但旧设备上仍会与原生抽屉动画争夺 UI/GPU 合成资源。
6. `dumpsys gfxinfo` 实测存在 `30` 次 High input latency、`5` 次 Missed Vsync，99 分位帧耗时约 `61ms`。这能解释旧设备上按钮反馈和抽屉动画明显慢于新手机。

### 必须修复一：动画开始时立即隐藏按钮

不要等 `onDrawerOpened()` 才隐藏。应在 Drawer listener 中增加 `onDrawerSlide()` 或 `onDrawerStateChanged()`：

```java
@Override
public void onDrawerSlide(View drawerView, float slideOffset) {
    if (mHamburgerButton == null) return;
    // 抽屉只要开始出现，就不能继续显示覆盖在内容层上的菜单按钮。
    mHamburgerButton.setVisibility(slideOffset > 0.001f
            ? View.INVISIBLE : View.VISIBLE);
}

@Override
public void onDrawerClosed(View drawerView) {
    setHamburgerVisible(true);
    // 保留现有关闭逻辑。
}
```

可以选择在前 10% 进度内做快速 alpha 淡出，但必须满足：

- 不得让按钮在抽屉面板边缘出现裁切、重影或跟着面板一起移动的错觉。
- 按钮隐藏后必须禁用点击，完全关闭后再恢复可见和可点击。
- `ACTION_CANCEL`、返回键关闭和快速反向拖动后都要恢复正确状态。

### 必须修复二：原生抽屉动画期间降低或暂停 ImGui 渲染

抽屉是 Android 原生 View，打开期间背景内容无需持续满速重绘。应增加明确的渲染状态：

```text
DrawerLayout STATE_DRAGGING / STATE_SETTLING / 已打开
    -> 冻结 ImGui 背景，保留最后一帧

DrawerLayout 已完全关闭
    -> 唤醒 ImGui 并请求新帧
```

推荐让 `ImGuiSurfaceView` 支持 `setNativeOverlayActive(boolean)`：

1. 原生抽屉开始拖动或吸附时进入 overlay active。
2. overlay active 时不执行无条件满速 `renderFrame()`；等待输入、业务状态变化或关闭事件。
3. 抽屉关闭后立即唤醒并补绘一帧。
4. 不能在 UI 线程等待渲染线程，也不能通过忙循环轮询。

这部分应与“悬浮窗流畅度优化方案”中的按需渲染共用同一套帧调度，不要为抽屉再创建第二套线程控制。

### 必须增加时间日志

Debug 构建临时记录以下时间点，Release 删除逐帧日志，只保留异常慢日志：

```text
hamburger ACTION_DOWN
hamburger onClick
onDrawerSlide 首次 offset > 0
onDrawerOpened
onDrawerClosed
```

若 `onClick -> 首次 slide` 超过 100ms，或 `首次 slide -> opened` 超过 500ms，打印一次汇总警告。不要在每个 `onDrawerSlide` 帧打印日志。

### 小米 8 专项验收

1. 冷启动后按钮点击 50 次，必须每次立即出现抽屉位移反馈。
2. 点击后 100ms 内必须进入 `slideOffset > 0`。
3. 完整吸附打开尽量控制在 500ms 内。
4. 慢速录屏逐帧检查，抽屉移动期间不得看到被裁切的三横线按钮。
5. 左边缘慢拖、快速甩开、拖到一半再返回，按钮显示状态都必须正确。
6. 抽屉打开时 ImGui 背景不得继续分页、滚动或响应列表点击。
7. 关闭后第一帧页面内容和点击区域必须立即恢复。
8. 小米 8 与另一台高刷新率手机都执行，不能只在新手机验收。
