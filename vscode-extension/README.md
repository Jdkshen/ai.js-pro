# AI.js Pro（VS Code 扩展）

> 当前配套宿主：dev-1112（`com.jdkshen.aijspro`）。远程调试仍走 legacy 9317 / 手机回连 8327；新 Flutter 构建另有 ws-rpc 动态端口通道，两条链路独立。

连接手机上的 **AI.js Pro**，在电脑上开发、运行与调试脚本：

- **连接设备**：手机侧打开「Pro 扩展服务器」后，首次输入手机 IP（默认 9317）；握手成功后手机会记住这台电脑，以后自动回连电脑 `8327`，无需再输 IP。
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
  - 插入的代码**写法对齐官方图色示例**：`requestScreenCapture('portrait')`（优先无障碍不弹授权）+ `captureScreen({ mode: 'full' })` + `images.findColor`（带 `region`/`threshold`）+ `screen.recycle()`，颜色写 `#RRGGBB`
  - **颜色口径**：面板与「测试」都按 **QuickJS 原始像素值**（截图已去掉 iCCP 广色域配置）；Rhino 脚本读同一像素会因色彩管理而不同
  - 首次会提示选择设备（也可用「增加调试设备配置」写入 `.vscode/launch.json`，`type: aijspro`）
  - 限制：只支持第一代 API（Rhino）；手机侧同一时间只允许一个调试会话（与 App 内置编辑器的调试互斥）；不支持暂停运行中的脚本/条件断点/日志断点

## 协议

与手机侧 `pluginclient`（`DevPluginServer` / `DevPluginFrames`）走同一套协议：

```
[int32 BE payload length][int32 BE type][payload]      type: 1=JSON 2=bytes 3=gzip JSON 4=gzip bytes
```

设备先发 `hello`，扩展回 `hello`（`client_version=4`）；之后：

| 方向 | 消息 |
|---|---|
| 扩展 → 设备 | `command`（run / stop / stopAll / save / **list_files** / **read_file** / **write_file** / **inspect_layout** / **screenshot** / **debug_***） |
| 扩展 → 设备 | `bytes_command` + bytes 帧（run_project / save_project，zip 载荷带 md5） |
| 设备 → 扩展 | `log`、`response_<id>`、**`debug_event`**（started / stopped / output / terminated）、bytes 帧（read_file 内容，用 response 里的 md5 配对） |

> `list_files` / `read_file` / `write_file` 是本扩展与手机侧一起新增的扩展命令（官方 `hyb1996.auto-js-pro-ext` 没有）；不发送它们时与官方行为完全兼容。

## 开发

```bash
npm install
npm run build        # esbuild → dist/extension.js
npm run test:devices # 删除设备、重连与设置保存的回归检查
npm run package      # 生成 aijspro-vscode-x.y.z.vsix
```

安装：`code --install-extension aijspro-vscode-0.3.1.vsix`（或 VS Code 里「从 VSIX 安装」）。更新后执行「开发人员: 重新加载窗口」。
