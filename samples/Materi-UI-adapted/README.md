# Materi-UI 适配版（AI.js Pro Rhino）

来源：用户提供的 `6.Materi-ui.zip` 内 `AutoJsPro-GoogleMaterial-main`。本项目保留原始 MD3 XML/交互结构，只做当前 AI.js Pro（Rhino）可直接运行的适配。

## 运行

1. 将本目录复制到手机 `/storage/emulated/0/脚本/Materi-UI-adapted`。
2. 用 AI.js Pro 打开 `MainActivity.js`，引擎选 Rhino 运行；或 MCP：
   `run_script {engine:"rhino", path:"Materi-UI-adapted/MainActivity.js", wait:false}`。
3. 底部 Home / 选项 / 设置，选项页四个 Tab（Item列表、各种控件、下拉刷新、日志）已实测可切换。

## 适配改动

- 删除 `importClass(Packages.androidx.documentfile.provider.DocumentFile)`：当前宿主未暴露该包，原项目也未实际使用它。
- `ui.R.drawable.*` / `ui.R.string.openDrawerContentDesc` 在当前 Rhino 项目上下文中为 undefined：
  - BottomNavigation / Chip 图标改用 `android.R.drawable.*`；
  - DrawerToggle 的字符串资源参数改为 `0`。
- 未改动 XML 布局、主题色、List item 绑定、进度条、Snackbar、Tab/Drawer 等 MD3 结构。

## 已验证

- 设备：MI8 `ce4d2bdb`，AI.js Pro **dev-1260**，Rhino。
- 首页加载、权限开关、BottomNavigation（Home/选项/设置）、TabLayout 四页切换、MaterialButton/ToggleGroup、进度条、列表滚动与下拉刷新页均正常显示。
- 日志页为黑色原生控制台容器，属原项目设计。

## 边界

- 只适配到可运行/可切换 UI；原项目依赖的 Auto.js Pro 专属资源、悬浮日志、`ui.R` 图标和个别设置页功能未全部逐项验证。
- 原项目 `project.json` 的 packageName/version 仅保留参考，当前以脚本形式运行，不依赖打包配置。
