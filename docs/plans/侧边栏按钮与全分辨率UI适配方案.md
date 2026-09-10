# AI.js Pro 侧边栏按钮与全分辨率 UI 适配方案

> **已作废（2026-09-10）：** 本文针对的 ImGui 主工作台已整体删除（见 [项目现状与接手说明](../PROJECT_STATUS.md)），方案不再执行，仅作历史记录保留。当时的仓库路径为 `C:\Users\18101\Documents\autojs4.4.1源码`，该目录已于 2026-09-10 改名为 `ai-js-pro`。

> 本文档是可直接交给编程 Agent 执行的任务说明。目标是解决部分手机左上角侧边栏按钮无响应，并将 ImGui 工作台改为适配小屏、普通手机、平板、折叠屏、横屏、挖孔屏和不同系统显示缩放的响应式界面。

## 1. 项目位置

```text
C:\Users\18101\Documents\ai-js-pro
```

主要涉及文件：

```text
apps/app/src/main/java/com/jdkshen/aijspro/ui/imgui/ImGuiWorkspaceActivity.java
apps/app/src/main/java/com/jdkshen/aijspro/ui/imgui/ImGuiWorkspaceDrawer.java
apps/app/src/main/java/com/jdkshen/aijspro/ui/imgui/ImGuiSurfaceView.java
apps/app/src/main/java/com/jdkshen/aijspro/ui/imgui/ImGuiNativeBridge.java
apps/app/src/main/cpp/autojs_imgui.cpp
apps/app/src/main/cpp/imgui_accessibility_bridge.cpp
apps/app/src/main/cpp/imgui_accessibility_bridge.h
apps/app/src/main/AndroidManifest.xml
```

## 2. 问题现象

### 2.1 侧边栏按钮在部分手机上无响应

左上角三横线看起来是普通 Android 按钮，实际上是 ImGui `InvisibleButton`。

当前点击链路：

```text
手指触摸
  → ImGuiWorkspaceDrawer / DrawerLayout 分发
  → ImGuiSurfaceView.onTouchEvent()
  → JNI ImGuiNativeBridge.touch()
  → Native 手势分类
  → 连续两帧模拟 Mouse Down / Mouse Up
  → ImGui InvisibleButton 命中
  → queueAction(71)
  → Java pollAction()
  → mWorkspaceDrawer.open()
```

任意一层收到 `ACTION_CANCEL`、父布局拦截、坐标错位或渲染帧未继续，都会造成点击无响应。

### 2.2 左边缘存在多套手势识别

`ImGuiWorkspaceDrawer` 本身继承 `DrawerLayout`，同时又覆写 `onInterceptTouchEvent()` 识别左边缘滑动。ImGui Native 层也有抽屉手势状态。

在不同 Android 版本、OEM 手势导航和触摸阈值下，父布局可能提前接管事件，子 SurfaceView 只收到 `DOWN` 和 `CANCEL`，收不到 `UP`。

### 2.3 View、Surface 与 Native 坐标系可能不一致

当前渲染使用：

```java
ImGuiNativeBridge.renderFrame(getWidth(), getHeight(), density);
```

触摸使用：

```java
ImGuiNativeBridge.touch(action, event.getX(), event.getY());
```

没有单独保存 `surfaceChanged()` 返回的真实 Surface 宽高，也没有将 View 触摸坐标映射到 Surface 坐标。

在以下环境中可能出现图标显示位置与命中区域不一致：

- 系统“显示大小”调整。
- 厂商分辨率切换。
- 应用兼容模式。
- 折叠屏展开/收起。
- 多窗口或自由窗口。
- SurfaceView 缓冲区尺寸与 View 布局尺寸不同。

### 2.4 当前 UI 大量使用固定 dp 和固定列数

已确认的例子：

- 顶部区域固定约 `104dp`。
- 顶部始终尝试放置标题和四个操作按钮。
- 文件行、示例行和任务行使用固定高度。
- 路径操作区使用固定右侧按钮宽度。
- 插件页始终固定两列。
- 插件卡片高度固定约 `224dp`。
- FAB 直接使用整个画面宽高定位，未单独处理安全区。
- 字体只根据 `density` 生成，没有区分 `density` 与 `fontScale/scaledDensity`。

## 3. 总体解决方案

### 3.1 将左上角入口改为真实 Android View

这是侧边栏按钮稳定性的核心修复。

要求：

1. 在 `ImGuiWorkspaceActivity` 或 `ImGuiWorkspaceDrawer` 的内容容器上方放置一个原生 Android 菜单按钮。
2. 按钮必须位于 SurfaceView 上层，直接调用：

```java
mWorkspaceDrawer.open();
```

3. 触摸区域不小于 `48dp × 48dp`，建议使用 `56dp × 56dp`。
4. 视觉图标居中，可视图形约 `24dp`。
5. 位置需加上顶部和左侧 `WindowInsets/DisplayCutout` 安全距离。
6. 设置波纹/按下反馈和内容描述“打开侧栏菜单”。
7. 按钮必须可被 Android 无障碍服务识别和点击。
8. ImGui 仍可绘制顶栏背景，但不再负责此按钮的点击分发。
9. 避免原生按钮和 ImGui `InvisibleButton` 双重触发。Native 层可保留图标占位，但应移除或禁用 `queueAction(71)` 的点击路径。

### 3.2 左边缘滑动只保留一个手势所有者

不允许 `DrawerLayout`、Java 自定义拦截和 Native ImGui 抽屉同时争抢左边缘事件。

建议：

1. 主抽屉统一使用 Android `DrawerLayout`。
2. 保留 Android 原生的点击开启、遮罩点击关闭和返回键关闭。
3. 左边缘滑动优先使用 `DrawerLayout` 原生实现。
4. 如 OEM 手势导航下必须保留自定义边缘检测，则必须禁用 `DrawerLayout` 同类拦截，而不是两套并行。
5. 系统手势排除区只设置 Android 允许的左边缘必要范围，不要排除整个 SurfaceView 宽度。
6. 必须回归“左右分页”手势，抽屉边缘滑动不得抢占内容区域的左右分页。

### 3.3 建立统一 ViewportMetrics

在 Java 层建立一套与渲染、触摸和安全区共享的视口数据。

建议字段：

```text
viewWidthPx
viewHeightPx
surfaceWidthPx
surfaceHeightPx
density
scaledDensity
fontScale
safeInsetLeftPx
safeInsetTopPx
safeInsetRightPx
safeInsetBottomPx
orientation
```

数据来源：

- View 尺寸：`onSizeChanged()`。
- Surface 尺寸：`surfaceChanged()`。
- 密度：`DisplayMetrics.density/scaledDensity`。
- 字体缩放：`Configuration.fontScale`。
- 安全区：`WindowInsets`。
- 挖孔/刘海：`DisplayCutout.safeInset*`。

必须在以下时机刷新：

- Surface 创建和尺寸变化。
- View 尺寸变化。
- 横竖屏切换。
- 折叠屏展开/收起。
- 进入/退出多窗口。
- 系统显示大小、字体大小或密度改变。
- 导航方式和键盘/IME 显示状态改变。

### 3.4 将触摸坐标映射到真实 Surface

触摸不得直接将 View 坐标当成 Native 渲染坐标。

建议映射：

```java
float surfaceX = event.getX(pointerIndex) * surfaceWidthPx / viewWidthPx;
float surfaceY = event.getY(pointerIndex) * surfaceHeightPx / viewHeightPx;
```

需要处理：

- View 或 Surface 尺寸为 0 时不分发触摸。
- 坐标限制在 Surface 边界内。
- 多指触摸使用稳定的 active pointer ID，不只依赖 `actionIndex`。
- `ACTION_POINTER_UP`后切换 active pointer 时重置拖动基准，避免坐标跳变。
- `ACTION_CANCEL` 必须完整清理 Native 点击和手势状态。

Native 渲染的 `io.DisplaySize` 和 `glViewport` 也必须使用同一套 Surface 宽高。

### 3.5 区分组件缩放与文字缩放

不要用一个 `gUiScale` 同时承担所有职责。

建议拆分：

```text
gDensityScale     = density
gFontScale        = density * fontScale
gLayoutWidthDp    = availableWidthPx / density
gLayoutHeightDp   = availableHeightPx / density
```

要求：

- 间距、圆角、图标和触摸区使用 `gDensityScale`。
- 字体点数使用 `gFontScale`。
- 不要因为字体放大而把右侧按钮挤出屏幕。
- 字体缩放过大时允许行高自适应，文字使用省略或合理换行。
- 不建议粗暴忽略用户的系统字体缩放。

## 4. 响应式布局规则

### 4.1 断点

基于可用宽度 dp，不基于手机型号或物理分辨率。

| 布局类型 | 可用宽度 | 典型设备 |
| --- | ---: | --- |
| Compact | `< 360dp` | 小屏、大字体、分屏窗口 |
| Phone | `360dp–599dp` | 普通手机 |
| Tablet | `600dp–839dp` | 小平板、折叠屏展开 |
| Expanded | `≥ 840dp` | 大平板、宽屏多窗口 |

额外横屏规则：

- 可用高度 `< 480dp` 时启用紧凑高度模式。
- 横屏不代表一定是 Tablet，仍需同时判断宽度和高度。

### 4.2 顶部工具栏

任何宽度都必须保留：

- 原生侧栏菜单按钮。
- 应用标题或紧凑标题。
- 至少一个与当前页相关的主要操作。

Compact：

- 标题可缩短为 `AI.js`。
- 只显示一个主要操作和一个“更多”按钮。
- 编辑器、日志、文档和搜索其余入口放入更多菜单。

Phone：

- 宽度充足时显示完整标题和四个操作。
- 如字体缩放导致宽度不足，自动退化到 Compact，不得重叠。

Tablet/Expanded：

- 显示完整标题和所有操作。
- 可选在宽屏上显示文字标签，但不是本轮必须项。

顶栏所有操作按钮的触摸区不得小于 `48dp`。

### 4.3 五个主分页

- 五个分页继续平分可用宽度。
- 每个分页命中宽度不得小于 `48dp`。
- 小屏只显示图标，不强制显示文字。
- 选中指示线必须与当前分页宽度一致。
- 分页点击、左右拖动和松手吸附必须使用同一 ViewportMetrics。

### 4.4 路径和操作行

- 左侧面包屑使用剩余宽度。
- 文字超长时从左侧省略，优先保留最后一级当前目录。
- 返回上级、排序和筛选按钮始终保留。
- Compact 宽度不足时，排序和筛选可合并到更多菜单。
- 按钮不得与面包屑重叠。

### 4.5 文件、示例和任务列表

- 行宽始终使用当前可用宽度。
- 文件名优先显示，次级信息可在 Compact 模式下缩短。
- 更多菜单和直接运行按钮必须保留可点击区域。
- 文字使用省略号，不允许绘制到右侧操作按钮下方。
- 行高需根据字体缩放和横屏紧凑模式计算，不应只用一个固定值。

### 4.6 资源和插件网格

列数必须基于最小卡片宽度动态计算，不得固定为两列。

建议：

```text
Compact: 1 列
Phone: 2 列
Tablet: 3 列
Expanded: 4 列或根据最小卡片宽度计算
```

更好的通用算法：

```text
columns = max(1, floor((availableWidth + gap) / (minCardWidth + gap)))
cardWidth = (availableWidth - gap * (columns - 1)) / columns
```

卡片高度根据封面比例、标题和描述计算，不得始终固定 `224dp`。

### 4.7 FAB 和底部区域

- FAB 位置使用可用区域右侧/底部边界，加上安全区。
- 不得被手势导航条、三键导航栏、IME 或挖孔屏遮挡。
- 横屏和分屏中底部空间不足时，FAB 可上移或改为顶栏操作。
- 资源页底部“全部/我的”切换栏需避开底部安全区。

### 4.8 抽屉宽度和内容

建议抽屉宽度：

```text
drawerWidth = min(max(preferredWidth, minWidth), availableWidth - retainedEdge)
```

建议范围：

- Compact：最多占可用宽度的 88%。
- Phone：约 78%–84%。
- Tablet/Expanded：使用固定最大宽度，建议不超过 `420dp–480dp`。
- 右侧必须保留可点击的遮罩区。

抽屉顶部、滚动列表和底部退出区要共同考虑顶部/底部安全区。

## 5. 与 ImGui 按需渲染的兼容要求

项目后续还会进行“ImGui 按需渲染”优化。本轮必须避免引入新的依赖连续渲染帧的点击逻辑。

要求：

- 原生侧栏按钮不依赖 ImGui 渲染帧。
- 如其他 ImGui 按钮仍使用“两帧模拟按下/松开”，则一次点击必须显式请求足够的后续帧。
- 窗口尺寸、安全区、数据或字体变化时必须请求重新渲染。
- 分页拖动、惯性滚动和吸附动画期间保持连续帧。

## 6. 实施顺序

1. 记录当前真机基线，包括按钮命中、滑动、横竖屏和截图。
2. 增加原生 Android 侧栏按钮，暂时保留 ImGui 图标作为视觉占位。
3. 禁用 ImGui 对该图标的点击处理，确认不会重复打开。
4. 统一左边缘手势所有者，回归抽屉、左右分页和系统返回手势。
5. 建立 ViewportMetrics 并传入 Native。
6. 改造渲染和触摸坐标映射。
7. 实现 Compact / Phone / Tablet / Expanded 布局断点。
8. 改造顶栏和路径操作行。
9. 改造列表、资源页、插件网格、任务页和 FAB。
10. 处理字体缩放、安全区、挖孔屏、横屏和多窗口。
11. 构建 Debug APK 并完成模拟分辨率测试。
12. 真机回归 Android 13 和 Android 16。
13. 将实测结果、APK 路径和 SHA-256 记录到任务报告。

## 7. 验收测试矩阵

### 7.1 屏幕尺寸和密度

至少覆盖以下逻辑视口：

| 类型 | 参考视口 |
| --- | --- |
| 小屏 | 320dp 宽 |
| Compact | 340dp–359dp 宽 |
| 普通手机 | 360dp、392dp、411dp 宽 |
| 宽屏手机 | 480dp–599dp 宽 |
| 平板 | 600dp、720dp、840dp+ 宽 |
| 横屏紧凑 | 高度小于 480dp |
| 分屏/自由窗口 | 动态改变宽高 |

每类至少测试 mdpi/xhdpi/xxhdpi 中的一个代表密度，确认布局使用 dp 逻辑，不依赖物理像素。

### 7.2 字体和显示缩放

- 系统字体：85%、100%、130%、150%。
- 系统显示大小：小、默认、大。
- 文字不得与图标重叠。
- 主要按钮不得被挤出屏幕。
- 无障碍节点范围需与实际视觉位置一致。

### 7.3 系统导航与安全区

- 三键导航。
- 手势导航。
- 左右侧返回手势。
- 中置、左置和右置挖孔。
- 刘海屏。
- 圆角屏。
- 底部手势提示条显示/隐藏。
- IME 打开/关闭。

### 7.4 侧边栏入口

- 连续点击 50 次，每次都应正确开启或保持已开启状态。
- 不同点击位置：图标中心、按钮四角和触摸区边缘。
- 轻微手指抖动的点击不得被误判为拖动。
- 左边缘滑动可打开抽屉。
- 内容区左右滑动只切换分页，不误开抽屉。
- 返回键优先关闭抽屉，不退出工作台。
- 遮罩区点击只关闭抽屉。
- TalkBack/无障碍服务可聚焦并执行“打开侧栏菜单”。

### 7.5 五个页面

每个断点下都要检查：

1. 文件管理。
2. 示例。
3. 资源。
4. 插件。
5. 任务。

必须确认：

- 顶栏无重叠。
- 分页指示正确。
- 路径和操作按钮可见可点。
- 列表可滚动。
- 更多菜单可打开。
- 网格列数符合断点。
- FAB 未遮挡列表的关键操作。
- 空状态、加载状态和展开列表都没有裁切。

## 8. 不得破坏的现有行为

- 左右五页实时跟手位移。
- 松手后分页吸附动画。
- 上下滚动和惯性减速。
- 抽屉打开时背景列表不继续滚动。
- 抽屉打开时背景页不继续分页动画。
- 返回键优先关闭抽屉。
- 文件列表、示例列表和任务列表的点击、长按和更多菜单。
- 现有 Android 无障碍虚拟节点。
- 悬浮窗开关和状态同步。
- 已实现的 OpenCV、YOLO、ONNX 和 NCNN 功能。

## 9. 代码要求

- 不得用单一机型、固定分辨率或截图像素坐标做特判。
- 布局以可用 dp 宽高为依据。
- 绘制坐标和触摸坐标必须来自同一 ViewportMetrics。
- 不得在 `ACTION_MOVE` 或渲染热路径中输出逐帧日志。
- 不得为了适配而重新引入原生主页。工作台仍使用 ImGui，仅对必须保证可靠性和无障碍的入口使用 Android View。
- 不得删除用户现有文件、配置、示例或数据。
- 保留现有中文界面和字体回退逻辑。
- 重构要分步完成，每一步都能独立构建和回退。

## 10. 构建和产物

首先构建：

```text
:app:assembleCommonDebug
```

验证成功后再构建 Release。

最终报告必须包含：

- 实际修改文件。
- 侧边栏按钮无响应的最终根因。
- 坐标映射公式和安全区处理方式。
- 响应式断点和各页面变化。
- Debug/Release 构建结果。
- APK 绝对路径、大小和 SHA-256。
- 真机与模拟视口测试结果。
- 尚未覆盖的设备或风险。

## 11. 完成标准

以下条件全部满足才算完成：

- 左上角侧边栏按钮已改为原生 Android View。
- 连续点击 50 次无丢失。
- 左边缘抽屉手势与内容左右分页不冲突。
- View、Surface、OpenGL、ImGui 和触摸使用统一坐标系。
- 系统显示缩放后不存在图标与点击区错位。
- Compact、Phone、Tablet 和 Expanded 四档布局已实现。
- 五个主页在竖屏、横屏和大字体下无重叠、无裁切、无不可点击项。
- 挖孔屏、手势导航和三键导航下安全区正确。
- Debug APK 构建成功并通过 Android 13 真机回归。
- Android 16 设备可用时完成最终回归。
- 已输出完整测试报告和 APK 信息。

