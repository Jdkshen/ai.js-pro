# M3Kit 原生 DEX 接入与宿主 ClassLoader 修复（dev-1232）

- 日期：2026-10-24
- 输入：`.4` 设备 `/storage/emulated/0/脚本/M3Kit/docs/Node24-UI-Issues.md`（§14 原生 DEX 原型）
- 版本：**dev-1232**，APK SHA-256 `8c8e16cdc22c467887204fb6675a3fd094516413150398254cb0a2a52e06471e`（151,787,766 字节）
- 设备：MI8 `ce4d2bdb`（ADB/MCP）；Redmi `.4` 为 M3Kit 工程所在设备（当前 dev-1231）

## 1. 根因：生成类不在 inflater 的 ClassLoader

- M3Kit `native.js` 通过 `host.runtime.loadDex` + `$java.defineClass(..., {packageName:"m3ui"})` 生成 `m3ui.NativeView`；
- `ui.inflate` 走 `NodeUiLocalSession.inflateXml`，原实现只用 Activity Context 的 ClassLoader，未包含生成类，导致 `ClassNotFoundException: m3ui.NativeView`；
- 修复：`NodeUiLocalSession.show()` 从请求的 `javaClassOwner` 解析 `Node24GeneratedClasses.loader(...)` 并缓存；`inflateXml/inflateXmlBatch` 优先使用该 loader；关闭时清空。未定义生成类时保持原路径。

## 2. M3Kit 修改（A/B/C/D）

- **A NativeView.java**：textfield EditText 改为填满宽度、root/box 56dp；slider SeekBar 最小高度/内边距对齐；chip 支持 checked/selected、选中态绘制与 `isChecked/setChecked/toggleChecked`；补 getTextValue/setTextValue/getCheckedValue/getValue/setValue facade 方法。
- **B native.js/index.js**：install 后补发主题；默认只 nativeize 静态控件（button/iconButton/progress/avatar/icon），chip/nav/slider/textfield 等继续走 JS Widget；NATIVE_ALL 可全开；facade/mark/wrap 雏形；`index.inflate` 对 nativeize 后 inflate 失败自动回退原始 XML，旧宿主不崩。
- **C main.node.js**：启动前 `await native.install()` + setTheme 再 start；loadingView 改 XML；去掉双重 setTimeout；首页 ready 后 800ms 预取 components/about；page ready 日志。
- **D docs**：README 与 Node24-UI-Issues 已更新（宿主 dev-1227/1231 修复、native 接入策略、第四轮限制），并推送回 `.4` M3Kit。

## 3. 验证

- MI8 dev-1232 Activity 内探针：原生 install=true，`m3.inflate("<m3-button>")` 子 View 类为 **m3ui.NativeView**（修复前 ClassNotFound）；
- M3Kit（MI8 dev-1232）首页 page ready ~791–884ms，组件页/关于页由预取缓存命中；无 native fallback 警告；
- `.4` 当前仍为 dev-1231（无 ClassLoader 修复）：nativeize 会回退 JS，页面保持可用；需升级 dev-1232 后才走原生静态控件；
- 构建：`:engine:testDebugUnitTest :app:testMiuixCompatDebugUnitTest` BUILD SUCCESSFUL；发布经 dev-update 直接发 1232（APK 已安装 MI8）。

## 4. 产物与文件

- M3Kit 本地镜像（工作副本，未入 Git）：`output/m3kit-work/`；已通过 MCP workspace 应用到 `.4` 的 `/storage/emulated/0/脚本/M3Kit`；
- NativeView.dex 已在 `.4` 上用 `JavaToDexAll.dex` 重新编译（20,644 字节）并替换 `native/build/NativeView.dex`；
- 宿主修复：`modules/engine/src/main/java/com/stardust/autojs/engine/node24/NodeUiLocalSession.java`；
- 发布日志：`output/publish-1232-devupdate.log`。

## 5. 限制与下一步

- 交互控件（chip/switch/nav/segmented/textfield/slider）仍走 JS Widget；native 事件回调尚未接 facade，默认不 nativeize；
- `.4` 需从 dev-1231 升到 dev-1232 才能启用原生静态控件；升级前自动回退；
- MI8 重装后无障碍被关闭，capture_screen/get_ui_layout 不可用，未取得最终截图；native 类验证来自脚本日志；
- 尚未在 `.4` 重跑 M3Kit 500ms 基准；需 dev-1232 + 无障碍开启后补测；
- 二次生成类继承（`class B extends A`）兼容缺口仍是独立问题。
