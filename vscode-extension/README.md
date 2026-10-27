# AI.js Pro（VS Code 扩展）

## 0.3.6：默认快捷键

扩展版本升至 0.3.6，新增以下默认快捷键；它们仅在 JS 编辑器聚焦时生效，不改动 F5 调试和 Ctrl+S 保存：

| 操作 | Windows / Linux | macOS |
| --- | --- | --- |
| 运行当前文件 | `F6` | `F6` |
| 重新运行 | `Shift+F6` | `Shift+F6` |
| 停止当前脚本 | `Ctrl+F6` | `Cmd+F6` |
| 停止所有脚本 | `Ctrl+Shift+F6` | `Cmd+Shift+F6` |
| 运行项目 | `Alt+F6` | `Alt+F6` |

在「键盘快捷方式」中搜索 `aijspro` 即可自定义。若按键已被旧版 AutoJS6 或其它扩展占用，请在用户快捷键（`keybindings.json`）中为对应的 `aijspro.*` 命令明确绑定；冲突时扩展的默认绑定可能不会生效。注意：编辑器标题菜单的「运行单文件」是选择文件后运行，而 `F6` 直接运行当前编辑器中的文件。

## 0.3.5：自动识别子工程目录

打开「父目录 + 单个工程子目录」的工作区时（例如 `Downloads/xiangqi-draw` 里面才是含 `project.json` 的 `xiangqi-draw`），运行/保存工程会自动定位到子工程，推送根目录就是子工程本身。此前会把父目录整体打包，手机端 `project.json` 不在解压根目录，报「工程无法运行：Attempt to invoke ... on a null object reference」。

## 0.3.4：aijspro.exclude 支持 glob

`aijspro.exclude` 现在支持 `*.apk`、`assets/**`、`**/node_modules/**`、`out/*.log`、`cache/` 等 glob；无 `/` 的模式仍按任意层级的目录/文件名匹配（兼容旧配置）。工程 zip 超过 200MB 会被拦截；大资源建议排除后单独 adb push 到手机工程目录。

## 0.3.3：设备选择

功能菜单中的“选择设备”始终弹出列表，显示已连接手机、USB 手机和已记住的地址。选择后，运行命令使用当前选中的手机；该手机断开后不会自动改用另一台。

USB 手机需要启用 USB 调试并在手机上允许电脑授权，手机侧开启“电脑连接 → 服务器模式”。扩展自动建立独立转发，两台手机可以同时连接。找不到 adb 时，在设置 `aijspro.adbPath` 填写 adb 可执行文件路径；也可使用列表中的“输入手机地址连接”。

安装新版后，若当前窗口仍运行旧版，请执行“开发人员：重新加载窗口”，再打开“AI.js Pro: 选择设备”。


> 当前配套宿主：AI.js Pro（`com.jdkshen.aijspro`）。VS Code 扩展使用 AI.js Pro 自己的电脑连接通道：手机固定监听 `9317`，电脑接收手机回连默认监听 `8327`（可配置）。

连接手机上的 **AI.js Pro**，在电脑上开发、运行与调试脚本：

- **连接设备**：手机侧打开「AI.js Pro 电脑连接」后，首次输入手机 IP（默认 `9317`，IPv6 写 `[地址]:9317`）；握手成功后手机会记住这台电脑，以后自动回连电脑 `8327`（默认端口，可用 `aijspro.listenPort` 修改，手机端回连端口也需一致），无需再输 IP。
- **删除设备**：在「连接到新设备」列表或功能菜单选择「删除已记住的设备」，可多选删除旧地址；立即断开对应连接并停止自动重试，也会忽略该手机的主动回连。以后手动输入该地址连接即可重新启用，其它设备不受影响。
- **运行**：编辑器标题栏 ▶ 运行当前文件；「推送并运行整个工程」把工作区打成 zip 推到手机执行（增量推送，会同步已删除的文件）。
- **日志**：脚本 `console.log` 与运行状态实时显示在「AI.js Pro」输出面板。
- **停止**：停止当前脚本 / 停止手机上全部脚本。
- **保存自动推送**：打开后每次保存把文件写回手机脚本目录（`<工程名>/<相对路径>`）。
- **设备文件**：资源管理器里的「AI.js Pro 设备文件」可以浏览手机脚本目录，直接打开、下载到工作区、或在手机上运行。
- **断点调试（Rhino）**：打开 `.js` 按 F5（或命令「AI.js Pro: 调试当前文件(Debug File)」）。支持断点、单步/跳入/跳出、调用栈、作用域变量树、调试控制台表达式求值；脚本 `console.log` 会进「调试控制台」。手机侧需 dev-731+
- **图色助手**：命令「AI.js Pro: 图色助手(Screenshot & Pick Color)」——拉一张手机全屏截图到 VS Code，滚轮缩放、拖拽平移，放大镜取色（HEX/ARGB/int/rgb）；三种取色模式（取色 / 框选 / 多点）都能一键插入 Auto.js 代码（`images.pixel` / `images.findColor` / `images.findMultiColors`），框选区域可存成工作区 `templates/*.png` 并插入 `images.findImage` 找图代码；截图可另存到工作区。手机侧需 dev-735+，无障碍需开启（与布局分析同一通道）
  - **测试**：工具栏「测试取色/找色/多点/找图」会在手机上**重新截屏并真跑一次**，命中坐标回传到面板（青色虚线圆标记）；用的是新截图，画面变化大时以最新一次为准
  - 取色模式**单击即固定**该点（黄色标记），移动鼠标不会改掉它（只看「悬停」栏）；「清除固定点」取消
  - 插入的代码**写法对齐 AI.js Pro 图色示例**：`requestScreenCapture('portrait')`（优先无障碍不弹授权）+ `captureScreen({ mode: 'full' })` + `images.findColor`（带 `region`/`threshold`）+ `screen.recycle()`，颜色写 `#RRGGBB`
  - **颜色口径**：面板与「测试」都按 **QuickJS 原始像素值**（截图已去掉 iCCP 广色域配置）；Rhino 脚本读同一像素会因色彩管理而不同
  - 首次会提示选择设备（也可用「增加调试设备配置」写入 `.vscode/launch.json`，`type: aijspro`）
  - 限制：只支持第一代 API（Rhino）；手机侧同一时间只允许一个调试会话（与 App 内置编辑器的调试互斥）；不支持暂停运行中的脚本/条件断点/日志断点

## AI.js Pro 电脑连接通道

本扩展只连接 AI.js Pro 自己的设备通道，保留手机端现有的 `DevPluginFrames` 帧格式：

```
[int32 BE payload length][int32 BE type][payload]      type: 1=JSON 2=bytes 3=gzip JSON 4=gzip bytes
```

- 手机端固定监听 `9317`，电脑主动连接这个端口；
- 手机回连电脑默认 `8327`，可用 VS Code 设置 `aijspro.listenPort` 自定义（手机端回连端口需同步修改）。

设备先发 `hello`，扩展回 `hello`（`client_version=4`）；之后：

| 方向 | 消息 |
|---|---|
| 扩展 → 设备 | `command`（run / stop / stopAll / save / **list_files** / **read_file** / **write_file** / **inspect_layout** / **screenshot** / **debug_***） |
| 扩展 → 设备 | `bytes_command` + bytes 帧（run_project / save_project，zip 载荷带 md5） |
| 设备 → 扩展 | `log`、`response_<id>`、**`debug_event`**（started / stopped / output / terminated）、bytes 帧（read_file 内容，用 response 里的 md5 配对） |

设备返回的 `response_<id>.data.error` 会统一转成扩展侧异常；连接已断开时命令立即失败，不会空等响应超时。

## 开发

```bash
npm install
npm run typecheck    # tsc --noEmit
npm test             # 连接协议 + 删除设备/重连/设置保存回归
npm run build        # esbuild → dist/extension.js
npm run package      # 生成 aijspro-vscode-x.y.z.vsix
```

安装：`code --install-extension aijspro-vscode-0.3.6.vsix`（或 VS Code 里「从 VSIX 安装」）。更新后执行「开发人员: 重新加载窗口」。
