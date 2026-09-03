# MiMo 改造任务：全局主题完整覆盖

## 当前问题

工作台抽屉中的“主题”目前只执行：

```java
SettingsActivity.selectThemeColor(this);
```

这个入口沿用旧版 `ThemeColorManager`，本质上主要修改旧原生界面的主色。新工作台并没有接入同一套主题状态：

- `ImGuiWorkspaceActivity` 主动关闭了状态栏主题同步，并硬编码状态栏、导航栏和根背景。
- `ImGuiWorkspaceDrawer` 的面板、行、文字、图标、开关和强调色全部是静态常量。
- `autojs_imgui.cpp` 的主题、页面背景、卡片、文字、图标和 FAB 大量使用 `IM_COL32`/`ImVec4` 硬编码。
- `ProCodeEditorActivity` 的工具栏、抽屉、标签、日志面板和代码编辑器主题独立硬编码。
- `EmbeddedTerminalActivity` 也使用固定黑色/深灰色。

当前粗略统计：

| 文件 | 硬编码颜色引用数量 |
|---|---:|
| `autojs_imgui.cpp` | 147 |
| `ProCodeEditorActivity.java` | 32 |
| `ImGuiWorkspaceDrawer.java` | 12 |
| `EmbeddedTerminalActivity.java` | 6 |
| `ImGuiWorkspaceActivity.java` | 3 |

所以现在看到的“主题没有全部覆盖”不是少改一两个控件，而是缺少统一主题架构。

## 改造目标

建立一份 Java 层的统一主题状态，所有界面从同一份语义化调色板取色，并通过 JNI 同步给 ImGui。主题修改后当前页面立即刷新，重新进入页面和重启进程后保持一致。

必须明确区分两个概念：

1. **显示模式**：跟随系统、浅色、深色。
2. **强调色**：红、蓝、青、绿等用户选择颜色。

不能继续把“夜间模式”和“主题强调色”混成一个颜色值。

## 一、建立统一 `AppThemePalette`

建议新增：

`app/src/main/java/org/autojs/autojs/theme/AppThemePalette.java`

调色板至少包含以下语义颜色，不允许业务界面自行推导或硬编码：

```text
isDark
windowBackground
surfacePrimary
surfaceSecondary
surfaceElevated
toolbarBackground
rowBackground
rowPressed
popupBackground
scrim
divider
textPrimary
textSecondary
textDisabled
iconPrimary
accent
accentPressed
accentMuted
danger
statusBar
navigationBar
fabBackground
fabForeground
editorBackground
editorToolbar
editorTabActive
editorTabInactive
editorSelection
terminalBackground
terminalForeground
```

使用 ARGB `int` 作为 Java 侧统一格式。浅色和深色分别生成完整调色板；强调色只替换 `accent` 及其派生色，不能把所有背景直接改成强调色。

## 二、建立单一主题状态源

建议新增：

`app/src/main/java/org/autojs/autojs/theme/AppThemeRepository.java`

职责：

1. 从现有 `ThemeColorManagerCompat` 和夜间模式偏好读取当前状态。
2. 生成不可变的 `AppThemePalette`。
3. 保存显示模式与强调色。
4. 提供注册/注销监听，主题变化时只广播一次完整调色板。
5. 与旧 `ThemeColorManager` 双向兼容，旧界面仍能收到主色更新。
6. 应用启动时初始化，不能等用户打开设置后才创建主题状态。

不要让 Activity 分别监听多个 SharedPreferences key，也不要由 C++ 自己读取配置文件。

## 三、修复主题选择入口

文件：

`app/src/main/java/org/autojs/autojs/ui/imgui/ImGuiWorkspaceActivity.java`

当前 `ACTION_DRAWER_THEME` 只能打开旧强调色选择器。需要改为统一主题面板，至少包含：

- 跟随系统 / 浅色 / 深色。
- 强调色选择。
- 恢复默认主题。

如果暂时复用 `SettingsActivity.selectThemeColor()`，必须监听旧主题值变化并立即调用 `AppThemeRepository.refreshFromLegacyTheme()`，不能要求退出或重启后才更新。

## 四、让工作台 Activity 接入主题

`ImGuiWorkspaceActivity` 需要在 `onStart/onStop` 注册和注销主题监听，并在收到新调色板时统一执行：

1. 更新状态栏颜色与图标明暗。
2. 更新导航栏颜色与图标明暗。
3. 更新根容器背景。
4. 调用 `mWorkspaceDrawer.applyTheme(palette)`。
5. 调用 JNI 把调色板传给 ImGui。
6. 请求至少一帧重绘。

当前以下硬编码必须删除：

```java
window.setStatusBarColor(Color.rgb(9, 12, 18));
window.setNavigationBarColor(Color.rgb(9, 12, 18));
mWorkspaceDrawer.setBackgroundColor(Color.rgb(9, 12, 18));
```

`shouldApplyThemeColorToStatusBar()` 可以继续返回 `false`，但前提是工作台自己根据统一调色板完整管理系统栏，而不是固定深色。

## 五、让原生抽屉动态换肤

文件：

`app/src/main/java/org/autojs/autojs/ui/imgui/ImGuiWorkspaceDrawer.java`

删除或停止直接使用以下静态颜色常量：

```text
PANEL_COLOR
HEADER_COLOR
ROW_COLOR
CHILD_COLOR
TEXT_PRIMARY
TEXT_SECONDARY
ACCENT
DANGER
```

新增 `applyTheme(AppThemePalette palette)`，必须更新已经创建的全部 View，而不是只影响之后新建的 View：

- 抽屉面板和头部。
- 所有普通行、子行、分组标题和退出行。
- 主文字、次级文字、状态文字。
- 图标 tint。
- Switch thumb/track tint。
- Ripple 颜色。
- 遮罩颜色和 Drawer elevation 对应的背景层次。
- 原生三横线按钮的图标和 Ripple。

为了能更新现有控件，需要保存 View 引用或为控件设置语义 tag 后遍历；不能在 `applyTheme()` 中重新创建整个抽屉，否则会丢失滚动位置、展开状态和当前开关状态。

## 六、通过 JNI 给 ImGui 下发完整调色板

文件：

- `app/src/main/java/org/autojs/autojs/ui/imgui/ImGuiNativeBridge.java`
- `app/src/main/cpp/autojs_imgui.cpp`

建议新增 JNI：

```java
static native void setThemePalette(int[] argbColors, boolean dark);
```

C++ 建立与 Java 顺序完全一致的 `ThemePalette` 结构。JNI 线程只写入 pending palette；渲染线程在帧开始时加锁取快照并应用，不能直接从 UI 线程修改 ImGui style 或渲染全局变量。

主题变化后：

1. 更新 `ImGuiStyle::Colors`。
2. 更新页面使用的语义颜色。
3. 标记需要重绘。
4. 不要重建 EGL context。
5. 只改变颜色时不要清空字体图集。

## 七、清理 ImGui 硬编码颜色

`applyTheme()` 目前只是固定深色主题，且各页面又通过 `PushStyleColor`、`IM_COL32` 覆盖它。必须把颜色按语义替换为 `gTheme`：

```cpp
gTheme.windowBackground
gTheme.toolbarBackground
gTheme.surfacePrimary
gTheme.surfaceSecondary
gTheme.rowBackground
gTheme.textPrimary
gTheme.textSecondary
gTheme.divider
gTheme.accent
gTheme.danger
gTheme.scrim
```

至少覆盖：

- 顶部双层工具栏。
- 五个分页及指示条。
- 路径/操作行。
- 文件和示例列表。
- 文件更多菜单、排序、筛选弹层。
- 资源卡片和底部分页。
- 插件卡片。
- 任务列表。
- 所有 FAB、阴影和展开菜单。
- 空状态、分割线、滚动条。

插件封面图片、文件类型图标、警告色等具有内容语义的颜色可以保留，但必须列出例外清单，不能把普通背景和文字混在例外里。

## 八、编辑器完整接入

文件：

`app/src/main/java/org/autojs/autojs/ui/imgui/ProCodeEditorActivity.java`

当前主题选择主要只切换代码区 `dark_plus.json/light_plus.json`，周边 UI 仍有大量固定深灰。必须同步更新：

- 状态栏和导航栏。
- 文件树抽屉。
- 顶部文件标签。
- 文件/编辑/调试/终端/其他工具栏。
- 搜索替换和跳转面板。
- 底部快捷键栏。
- 日志面板。
- 弹窗、菜单、分割线、选中状态。
- 代码编辑器背景、行号、选区、光标与语法主题。

深色模式映射 `dark_plus.json`，浅色模式映射 `light_plus.json`。切换后所有已打开标签必须同时刷新，且不能丢失光标、选区、撤销栈和未保存内容。

## 九、终端、悬浮窗和对话框

至少继续检查并接入：

- `EmbeddedTerminalActivity`。
- 悬浮按钮和展开菜单。
- 工作台触发的搜索、排序、筛选、新建、重命名、删除等对话框。
- 文件更多菜单。
- 插件安装和任务管理相关弹窗。

对话框优先使用现有 `ThemeColorMaterialDialogBuilder`，但背景、文字、危险操作按钮也必须根据浅色/深色模式检查，不能只改变确认按钮颜色。

## 十、主题更新生命周期

必须覆盖以下情况：

1. 当前工作台中修改主题，返回后立即生效。
2. 工作台切到后台后在设置页修改，回到工作台立即生效。
3. 编辑器已经打开时修改主题，编辑器立即或在 `onResume` 完整刷新。
4. 系统处于“跟随系统”时切换系统深浅色，应用收到配置变化后刷新。
5. 横竖屏切换后保持主题。
6. 杀进程重启后保持主题。
7. 首次安装没有偏好时使用明确默认值。

## 禁止的临时修补方式

- 不要只把青绿色替换成用户选择色。
- 不要只修改 `applyTheme()`，因为页面内还有大量硬编码覆盖。
- 不要通过 `Activity.recreate()` 作为唯一刷新手段。
- 不要在每帧从 Java 查询主题。
- 不要让 C++、抽屉、编辑器各自保存一份互不关联的主题偏好。
- 不要用全局字符串名称判断颜色用途。

## 构建与测试要求

### 自动检查

1. Java/Gradle 构建成功。
2. 三个 ABI 的 `libautojs_imgui.so` 重新编译。
3. 对上述五个重点文件重新搜索硬编码颜色，普通 UI 颜色应全部迁移到语义调色板。
4. JNI 调色板数组长度和字段顺序必须有静态常量或版本校验，避免 Java/C++ 错位。

### 真机矩阵

至少使用两台不同分辨率手机测试：

1. 深色 + 默认强调色。
2. 深色 + 红/蓝/绿各一种强调色。
3. 浅色 + 默认强调色。
4. 浅色 + 红/蓝/绿各一种强调色。
5. 跟随系统，并在应用运行期间切换系统深浅模式。

每组都必须截图以下页面：

- 工作台五个分页。
- 抽屉展开状态。
- 文件更多菜单和至少一个对话框。
- 编辑器文件树、标签、工具栏和日志面板。
- 终端。
- 悬浮窗展开菜单。

重点检查文字对比度、图标可见性、状态栏图标明暗、选中状态、危险按钮以及弹层遮罩。

## 完成标准

- 主题状态只有一个来源。
- 浅色、深色、跟随系统和强调色能够组合使用。
- 工作台、原生抽屉、ImGui、编辑器、终端、悬浮窗和相关弹窗全部覆盖。
- 切换主题无需重启应用即可看到完整变化。
- 重启应用后主题保持。
- 普通 UI 不再散落硬编码颜色；保留的内容语义颜色有明确例外清单。
- 不破坏抽屉点击、分页手势、编辑器未保存内容和现有功能。

