# AI.js Pro ImGui 左侧抽屉菜单改造任务

> **已作废（2026-09-10）：** 任务针对的 ImGui 主工作台（`ImGuiWorkspaceDrawer`、`autojs_imgui.cpp` 等）已整体删除（见 [项目现状与接手说明](../PROJECT_STATUS.md)），不再执行，仅作历史记录保留。

> 用途：把本文件直接交给开发代理执行。目标是将工作台左上角菜单从 Android 列表弹窗改为与 Auto.js Pro 使用习惯一致的左侧滑入抽屉，同时保留现有菜单功能和工作台状态。

## 1. 问题现状

当前左上角三横线按钮并没有打开侧滑抽屉，调用链如下：

1. `apps/app/src/main/cpp/autojs_imgui.cpp` 的 `drawTopBar()` 点击 `##navigation` 后执行 `queueAction(22)`。
2. `apps/app/src/main/java/com/jdkshen/aijspro/ui/imgui/ImGuiWorkspaceActivity.java` 收到 `ACTION_OPEN_WORKSPACE_MENU`。
3. `showWorkspaceMenu()` 使用 `new AlertDialog.Builder(this).setItems(...)` 显示整页列表弹窗。

原 Auto.js Pro 主界面使用 `DrawerLayout + ActionBarDrawerToggle`，因此用户看到的是从左侧滑出的导航抽屉。ImGui 工作台没有沿用该 `DrawerLayout`，而是用 `AlertDialog` 临时代替，所以交互不一致。

本任务禁止继续使用 `AlertDialog`、新 Activity 或新 Fragment 充当根菜单。根菜单必须在当前 ImGui Surface 内绘制和交互。

## 2. 最终效果

必须实现：

1. 点击左上角三横线后，菜单从屏幕左侧平滑滑入。
2. 抽屉打开时，原工作台仍保留在背景，右侧覆盖半透明遮罩。
3. 点击遮罩关闭抽屉。
4. 按 Android 返回键时优先关闭抽屉，不返回目录、不显示退出对话框。
5. 在抽屉区域向左滑动可以跟手关闭；松手后按距离和速度决定打开或关闭。
6. 打开和关闭抽屉不启动新 Activity，不刷新文件列表，不切换工作台分页，不改变当前目录及滚动位置。
7. 点击具体菜单功能时仍可按原行为进入系统设置、终端或设置页面；要求“不启动新 Activity”只针对打开抽屉本身。

可选项：本轮不强制实现从屏幕左边缘右滑打开。若实现，必须避免与 Android 系统返回手势和工作台左右分页冲突。

## 3. 菜单内容

保留当前 `showWorkspaceMenu()` 的全部入口和动态文字：

| 顺序 | 菜单项 | 现有处理逻辑 |
|------|--------|--------------|
| 1 | 无障碍服务 | 打开无障碍设置 |
| 2 | 开启悬浮窗 / 关闭悬浮窗 | `toggleFloatingWindow()` |
| 3 | 更多服务 | `showMoreServicesDialog()` |
| 4 | 开发者调试 / 断开开发者调试 | `toggleDeveloperConnection()` |
| 5 | 终端 | `openTerminal(mCurrentScriptDirectory)` |
| 6 | 主题 | `SettingsActivity.selectThemeColor(this)` |
| 7 | 官方博客 | 保留现有 URL |
| 8 | 官方频道 / 论坛 | 保留现有 URL |
| 9 | 设置 | 打开 `SettingsActivity_` |
| 10 | 检查更新 | `UpdateCheckDialog` |
| 11 | 退出 | `confirmExitApplication()` |

要求：

- 抽屉中显示 `AI.js Pro` 标题。
- 无障碍、悬浮窗和开发者调试应显示当前状态，不能写死。
- 菜单项点击后先关闭抽屉，再向 Java 层发送对应 action，避免从外部页面返回时抽屉仍然挡住界面。
- `更多服务`、开发者地址输入、检查更新和退出确认等二级弹窗可以继续使用 Android 对话框；根抽屉不能使用。

## 4. 推荐实现结构

### 4.1 Native 状态

在 `apps/app/src/main/cpp/autojs_imgui.cpp` 中增加明确状态，命名可调整，但语义必须完整：

```cpp
bool  gDrawerOpen = false;
bool  gDrawerDragging = false;
float gDrawerProgress = 0.0f;       // 0=完全关闭，1=完全打开
float gDrawerTarget = 0.0f;
float gDrawerDragStartX = 0.0f;
float gDrawerStartProgress = 0.0f;
float gDrawerVelocityX = 0.0f;
```

要求：

- 动画必须按帧时间更新，不能用 `sleep()` 阻塞渲染线程。
- 建议动画时长 180～240ms，并使用 ease-out；打开和关闭不能瞬移。
- 抽屉宽度建议为屏幕宽度的 80%～84%，同时限制最大宽度，平板上不能占满屏幕。
- `gDrawerProgress > 0` 时禁止背景列表接收点击、垂直滚动和左右分页。
- 打开抽屉时取消尚未结束的列表惯性滚动及分页拖动，之后不能继续改变背景滚动位置。

### 4.2 绘制层级

在主工作台内容绘制完成后调用独立的 `drawWorkspaceDrawer()`：

```text
工作台内容
  ↓
半透明遮罩（仅覆盖抽屉右侧区域）
  ↓
左侧抽屉面板
  ↓
抽屉菜单项
```

建议使用独立、无标题、无保存状态的 ImGui Window/Child 绘制抽屉。不要复用文件列表的 Child Window，否则会污染文件列表的 `ScrollY`。

遮罩透明度随 `gDrawerProgress` 变化，完全打开时建议为黑色 40%～55%。抽屉应跟随当前主题颜色，避免出现与页面不一致的纯黑块。

### 4.3 左上角按钮

修改 `drawTopBar()`：

- `##navigation` 不再发送 `ACTION_OPEN_WORKSPACE_MENU = 22` 给 Java 打开弹窗。
- 点击时只在 Native 层设置抽屉目标状态。
- 动画过程中再次点击可反向关闭，不能重复创建菜单或累积 action。

原 `showWorkspaceMenu()` 在完成迁移后应删除，或确保没有任何调用入口；不能保留一条路径继续弹出旧列表。

### 4.4 菜单 action

ImGui 菜单项仍通过 `queueAction()` 交给 `ImGuiWorkspaceActivity` 执行现有 Java 功能。为菜单项分配独立 action 编号，避免让 C++ 根据菜单索引猜测 Java 分支。

建议使用 60～70 的空闲区间，并在 C++ 使用具名枚举，Java 使用对应具名常量。具体编号可以调整，但两端必须一一对应并写注释。

每个菜单项流程：

```text
点击菜单项
→ Native 立即把抽屉目标设为关闭
→ queueAction(具体功能)
→ Java 调用原有方法
```

不要把 Android `Context`、Intent 或业务逻辑搬进 C++。

### 4.5 返回键桥接

在 `ImGuiNativeBridge.java` 和 JNI 中增加类似接口：

```java
static native boolean closeWorkspaceDrawer();
```

语义：

- 抽屉已打开或正在动画/拖动时，设置目标为关闭并返回 `true`。
- 抽屉完全关闭时返回 `false`。

`ImGuiWorkspaceActivity.onBackPressed()` 的处理顺序必须调整为：

```text
1. 抽屉打开 → 关闭抽屉并 return
2. 当前在脚本子目录 → 返回父目录
3. 当前在示例子目录 → 返回父目录
4. 其他情况 → 显示退出确认
```

不能先执行目录返回再关闭抽屉。

### 4.6 手势优先级

当前 Native `touch()` 已将横向手势用于五个工作台分页。抽屉打开时必须提高抽屉手势优先级：

```text
抽屉打开或正在拖动
→ 横向手势只控制抽屉
→ 不进入 gTouchPaging
→ 不修改 gPageOffset / gPageTargetSection
```

向左拖动时应让面板跟手：

- 拖动距离实时映射到 `gDrawerProgress`。
- 松手时若打开比例低于约 50%，或向左速度超过阈值，则关闭。
- 否则回弹到完全打开。
- `ACTION_CANCEL` 必须安全收尾，不能让抽屉停在不可交互的半开状态。

点击遮罩只能关闭抽屉，不能穿透并点击背景文件或切换分页。

## 5. 文件列表状态保护

这是强制验收项，不允许通过关闭后重新加载列表来实现。

打开、关闭抽屉期间不得修改：

- `gSection`；
- `gPageOffset` 和当前分页目标；
- `gScriptDirectoryLabel` / `gSampleDirectoryLabel`；
- `gSelectedScript` / `gSelectedSample`；
- `gScriptEntriesRevision` / `gSampleEntriesRevision`；
- `gScriptScrollCache` / `gSampleScrollCache`；
- 当前文件列表 Child Window 的 `ScrollY`。

不得因为菜单状态变化调用 `refreshScriptEntries()`、`refreshSampleEntries()` 或重新进入目录。背景列表可以继续绘制，但抽屉存在时不能响应输入。

## 6. 需要修改的文件

主要文件：

1. `apps/app/src/main/cpp/autojs_imgui.cpp`
   - 抽屉状态、动画、绘制、点击和滑动关闭。
   - 左上角按钮改为 Native 开关。
   - 抽屉菜单项发送具名 action。
   - 抽屉开启时拦截背景手势。
   - 新增 `closeWorkspaceDrawer()` JNI 实现。

2. `apps/app/src/main/java/com/jdkshen/aijspro/ui/imgui/ImGuiNativeBridge.java`
   - 声明关闭抽屉的 Native 接口。
   - 如需动态状态，可新增 `setDrawerState(...)`，不要每帧从 C++ 反调 Java。

3. `apps/app/src/main/java/com/jdkshen/aijspro/ui/imgui/ImGuiWorkspaceActivity.java`
   - 删除/停用 `showWorkspaceMenu()` 根弹窗。
   - 增加各菜单项 action 常量和处理分支，复用现有业务方法。
   - 返回键优先关闭抽屉。
   - 在现有运行状态刷新流程中同步无障碍、悬浮窗和开发者连接状态。

4. `apps/app/src/main/java/com/jdkshen/aijspro/ui/imgui/ImGuiSurfaceView.java`
   - 原则上继续转发原始 MotionEvent。
   - 只有确实需要处理系统手势冲突时才修改，不能破坏现有列表滚动和分页。

注意：这些文件可能已有未提交修改。必须基于现状增量编辑，不得覆盖、回退或重写与本任务无关的代码。

## 7. 禁止做法

1. 禁止用 `AlertDialog.setItems()` 继续显示根菜单。
2. 禁止点击三横线后启动 Activity 或 Fragment。
3. 禁止用全屏透明 Android View 盖住 ImGui 来伪装抽屉。
4. 禁止打开/关闭菜单时刷新文件列表或重置滚动位置。
5. 禁止让遮罩点击穿透到背景。
6. 禁止抽屉横滑与工作台分页同时生效。
7. 禁止只实现动画而遗漏返回键、遮罩和手势关闭。
8. 禁止删除现有菜单功能以缩小工作量。

## 8. 验收测试

### 8.1 基本交互

- [ ] 点击三横线，抽屉从左侧平滑滑入，没有 Android 根菜单弹窗。
- [ ] 再次点击三横线可以关闭。
- [ ] 点击右侧遮罩可以关闭。
- [ ] 按返回键可以关闭，且不触发目录返回或退出确认。
- [ ] 向左慢拖可以跟手关闭或回弹。
- [ ] 向左快速滑动可以关闭。
- [ ] `ACTION_CANCEL` 后抽屉恢复到完全打开或完全关闭。
- [ ] 抽屉打开时，背景文件、标签页和分页均不可点击或滑动。

### 8.2 状态保持

1. 进入至少包含两屏文件的目录。
2. 向下滚动到列表中部，记录屏幕顶部可见文件。
3. 打开抽屉并通过遮罩关闭，文件位置必须不变。
4. 再打开并通过返回键关闭，文件位置必须不变。
5. 再打开并通过左滑关闭，文件位置必须不变。
6. 当前分页、目录面包屑和选中项均不能变化。

### 8.3 菜单功能

逐项验证 11 个入口：

- [ ] 无障碍服务。
- [ ] 悬浮窗开关及动态文字刷新。
- [ ] 更多服务。
- [ ] 开发者调试连接/断开及动态文字刷新。
- [ ] 终端使用当前脚本目录。
- [ ] 主题选择。
- [ ] 官方博客。
- [ ] 官方频道 / 论坛。
- [ ] 设置。
- [ ] 检查更新。
- [ ] 退出确认。

从设置、终端或浏览器返回工作台后，抽屉必须保持关闭，原目录和滚动位置仍然存在。

### 8.4 回归

- [ ] 文件列表垂直滚动、惯性滚动正常。
- [ ] 五个工作台分页左右滑动正常。
- [ ] 文件进入、返回父目录后的滚动位置恢复逻辑正常。
- [ ] 点击列表项和行尾菜单没有触摸穿透或误触。
- [ ] Android 三键导航和全面屏手势导航各验证一次（条件允许时）。
- [ ] 连续快速开关抽屉 20 次无卡死、闪烁、半开残留和崩溃。

## 9. 构建、安装与交付

本任务必须修改 `autojs_imgui.cpp`，它属于 C++ 原生代码，因此必须执行完整 Native 构建，不能使用 `-SkipNative` 或旧 `.so`：

```powershell
.\release.ps1 -Install
```

只要求安装到当前选定的一台物理手机。同一手机同时存在 USB 和无线 ADB 时只测试一次，优先 USB。

最终交付必须包含：

1. 修改文件清单。
2. 抽屉状态和手势优先级说明。
3. 完整构建结果及 APK 路径、字节数、SHA256。
4. 安装目标设备和 `adb install` 的真实结果。
5. 抽屉打开、遮罩关闭、返回键关闭、左滑关闭四项实机截图或录屏证据。
6. 文件列表滚动位置保持的前后对比证据。
7. 11 个菜单入口的测试结果。
8. 未完成项和已知限制；未实测不能写 `PASS`。

只有上述强制验收全部通过，才能写“ImGui 左侧抽屉菜单改造完成”。
