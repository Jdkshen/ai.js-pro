# AI.js Pro 自研 VS Code 扩展（vscode-extension/）

> 当前配套宿主 **dev-1112**。legacy 通道仍为「电脑拨 9317 / 手机回连 8327」；Flutter 构建另有 ws-rpc 动态端口通道，两条链路独立，扩展按需选择。

手机侧保留官方 `hyb1996.auto-js-pro-ext` 兼容协议（`DevPluginServer`），电脑侧新增自研扩展
`vscode-extension/`（`jdkshen.aijspro-vscode`），不再依赖官方扩展即可完成「连接 → 运行 →
日志 → 停止 → 文件同步」全流程。

## 功能

| 功能 | 说明 |
|---|---|
| 连接/断开 + 自动回连 | 手机「Pro 扩展服务器」打开后：首次电脑拨手机 `9317`；握手成功手机记住电脑，之后主动回连电脑 `8327`；扩展启动时也会自动拨已记住的设备 |
| 运行当前文件 | 编辑器标题栏 ▶ / 命令面板；把编辑器内容作为 `run` 命令发给手机执行 |
| 推送并运行整个工程 | 工作区打包成 zip（`run_project`），带 `deletedFiles` 增量删除；排除项见 `aijspro.exclude`（0.3.4 起支持 `*.apk`、`assets/**`、`**/node_modules/**` 等 glob） |
| 停止 | 停止当前脚本 / 停止手机上全部脚本 |
| 实时日志 | 手机脚本 `console.log` 与运行状态进「AI.js Pro」输出面板 |
| 保存自动推送 | `aijspro.pushOnSave` 打开后，保存即 `write_file` 到手机脚本目录（`<工程名>/<相对路径>`） |
| 设备文件 | 资源管理器「AI.js Pro 设备文件」：浏览/打开/下载/在手机上运行；数据来自新增的 `list_files` / `read_file` |
| **断点调试（Rhino）** | 「调试当前文件(Debug File)」或 F5：断点/单步/跳入跳出/调用栈/作用域变量/调试控制台 eval；手机侧需 dev-731+ |
| **图色助手** | 手机截图 → VS Code 面板：缩放/平移/放大镜取色，三种模式一键插代码（`images.pixel` / `findColor` / `findMultiColors`）；框选可存模板 + 插入 `findImage` 找图代码；**可在手机上真跑一次测试**（回传命中坐标）；手机侧需 dev-735+，无障碍需开启 |
| 多设备 | 保存多个地址（`aijspro.devices`），连接多个设备时命令面板/状态栏可切换 |


## 自动识别子工程（0.3.5+）

如果打开的是「父目录 + 单个工程子目录」（如 `Downloads/xiangqi-draw` 里面才是含 `project.json` 的 `xiangqi-draw`），运行/保存工程会自动使用子工程作为推送根目录与 `projectName`；不会再出现手机端 `project.json` 不在解压根目录而报「工程无法运行：Attempt to invoke ... on a null object reference」。父目录下存在多个工程子目录时仍要求明确打开其中一个。

## 排除大文件（aijspro.exclude，0.3.4+）

「推送并运行整个工程」会把工作区打成一个 zip 帧发送，扩展在压缩包 **200MB** 时就拦截（给手机协议单帧 256MB 留余量）。在工程 `.vscode/settings.json` 或用户设置里配置排除项：

```json
{
  "aijspro.exclude": [
    "node_modules", ".git", ".vscode", ".artifacts", "dist", "build",
    "*.apk", "*.mp4", "*.zip",
    "assets/**", "**/node_modules/**", "out/*.log", "cache/"
  ]
}
```

规则（大小写敏感，相对工程根）：

- 无 `/` 且无通配符：匹配**任意层级**的同名目录/文件（旧行为，如 `node_modules`、`.git`）；
- 无 `/` 但有 `*`/`?`：按名称 glob 匹配任意层级，如 `*.apk`、`*.tmp`、`file?.js`；
- 含 `/`：按路径 glob 匹配，`**` 跨任意层目录，如 `assets/**`、`**/node_modules/**`、`out/*.log`；
- 以 `/` 结尾只匹配目录（如 `cache/`）；
- 目录命中会剪掉整棵子树；暂不支持 `!` 取反。

超过 200MB 且有大文件必须随工程运行时：不要放进推送包，先用 USB/adb 把资源放到手机 `/sdcard/脚本/<工程名>/`，脚本按原路径读取（增量删除不会删除未推送文件）。

## 协议（与手机侧一致）

```
[int32 BE payload length][int32 BE type][payload]    1=JSON 2=bytes(3/4=gzip 变体)
```

- 设备先发 `hello` → 扩展回 `hello`（`client_version=4`）完成握手；
- 扩展 → 设备：`command`（run/stop/stopAll/save/**list_files**/**read_file**/**write_file**/**inspect_layout**/**screenshot**/**debug_run**/**debug_set_breakpoints**/**debug_resume**/**debug_evaluate**/**debug_variables**/**debug_terminate**）、
  `bytes_command`+bytes 帧（run_project/save_project，md5 配对）；
- 设备 → 扩展：`log`、`response_<id>`、**`debug_event`**（started/stopped/output/terminated）、bytes 帧（read_file 内容，response 里带 md5）。

`list_files` / `read_file` / `write_file` 是本仓库新增的扩展命令（手机侧
`DevPluginFileService`，路径限定在脚本目录内）；官方扩展不发送它们，行为不受影响。

## 构建与安装

```bash
cd vscode-extension
npm install
npm run build        # esbuild → dist/extension.js
npm run package      # 生成 aijspro-vscode-0.3.0.vsix
code --install-extension aijspro-vscode-0.3.0.vsix
```

## 断点调试（Rhino）

1. 打开要调试的 `.js` 文件，在行号左侧下断点；
2. 按 F5（或命令面板 →「AI.js Pro: 调试当前文件(Debug File)」）；
3. 第一次会提示选择设备/输入手机地址（也可用「增加调试设备配置」把地址写进
   `.vscode/launch.json`，之后每次 F5 直连）。

调试时：左侧「调用栈/变量」可用（变量树支持展开对象）、「调试控制台」可直接输入表达式
（在断点暂停时求值），脚本 `console.log` 也会出现在调试控制台。停止按钮 = 终止手机端脚本。

限制：只支持第一代 API（Rhino）脚本；手机侧同一时间只能有一个调试会话（与 App 内置编辑器
的调试互斥）；不支持暂停运行中的脚本、条件断点、日志断点。

## 图色助手（截图取色 / 找图）

1. 手机侧打开「Pro 扩展服务器」并开启 **AI.js Pro 无障碍服务**（与布局分析同一个开关）；
2. 命令面板 →「AI.js Pro: 图色助手(Screenshot & Pick Color)」（或功能菜单里同名项）；
3. 右侧面板会出现手机全屏截图：滚轮缩放、拖拽平移、十字线 + 放大镜跟着鼠标；
4. 三种模式（工具栏按钮）：
   - **取色**：单击取色，**点击后该点会被固定**（黄色方框 + 圆圈，底部「固定」栏显示坐标与颜色）；
     移动鼠标只会更新「悬停」一栏的颜色，固定点不再变；「清除固定点」或重新截图即释放。
     底部显示 `#AARRGGBB`、`#RRGGBB`、int、rgb；按钮可复制颜色、
     插入 `images.pixel` 取色代码或 `images.findColor` 找色代码（threshold 默认 4）；
   - **框选**：拖拽选区 → 复制区域坐标（`x1, y1, x2, y2`）、或「保存模板并插入找图代码」
     （模板存到工作区 `templates/*.png`，代码用 `images.read("templates/….png")` + `findImage`）；
   - **多点**：依次点击多个点（首点为基准）→「插入多点找色代码」生成
     `images.findMultiColors(img, 首点颜色, [[dx, dy, 颜色], …])`。底部「多点」显示当前点数，
     「撤销一点」「清空点」可回退；拖拽（位移 > 8px）才是平移，单击不会因为手抖而丢点。
5. **测试**（手机上真跑一次）：工具栏「测试取色 / 测试找色 / 测试多点 / 测试找图」会把一段临时脚本
   发到手机上——重新截屏（无障碍通道，不弹授权）→ 跑对应查找 → 回传命中坐标；
   结果显示在面板底部「测试」一栏（命中 (x, y) / 未命中 / 错误），命中点会在图上画青色虚线圆。
   ⚠️ 测试用的是**新截的屏**（与面板当前那张可能已经有差别），命中标记以测试时刻的坐标为准。

模板图片放在工作区里，用「推送并运行整个工程」同步到手机即可（智能相对路径，相对于手机上的工程目录）。
截图可用「重新截图」重拉、用「截图另存到工作区」存成 PNG。

插入的代码**写法对齐官方图色示例**（`sample/QuickJS 引擎/图色处理/`）：
`requestScreenCapture('portrait')` 授权（优先无障碍通道，不弹授权）、`captureScreen({ mode: 'full' })` 取帧、
`JSON.stringify(point)` 输出、结束 `screen.recycle()`；找色/多点自带取样点附近的 `region`（±100px，按需调整）
与示例阈值（24 / 20），颜色写作 `#RRGGBB`。直接 F5 就能跑；已有授权/想换区域的自行改那几行。

**代码插到哪里**：插到「当前/最近编辑的**脚本文件**」的光标处（状态栏会提示落入哪个文件），
不会新建临时文件；输出面板、调试控制台、Git diff 这类非脚本文档会被跳过
（焦点在它们上面点 ▶ 运行时，会自动改用最近编辑的脚本并在状态栏提醒）。
只有确实一个脚本文件都没打开时，才退回新建临时文件。

限制：需要 Android 11+ 与无障碍服务开启；截图是**原图分辨率**（坐标即屏幕坐标，不做缩放映射）。

**颜色数值口径**：面板显示的是手机缓冲区的**原始像素值**（截图的 `iCCP` 色彩配置已被去掉），与
**QuickJS 脚本**里 `captureScreen()` + `images.pixel()` 读到的值完全一致；「测试」也是跑 QuickJS。
⚠️ 如果用 **Rhino** 脚本，同一像素会因色彩管理（广色域→sRGB）得到**不同**的数值。

**找色返回值的形状**：`findColor / findMultiColors / findImage` 返回的点与 Auto.js 同形——用
`point.x` / `point.y` 取值，未命中返回 `null`；`"" + point`（或 `point.toString()`）输出
`{100.0, 575.0}` 这种 Auto.js/OpenCV 形状，而 `JSON.stringify(point)` 仍是 `{"x":100,"y":575}`。

## 手机侧要求

- 打开手机侧栏的「Pro 扩展服务器」开关（会随应用回前台自动恢复）；
- 电脑入站防火墙放行扩展监听端口（默认 8327）；USB 场景可用
  `adb forward tcp:19317 tcp:9317` 后连接 `127.0.0.1:19317`。

## 验证记录

- 手机侧单元测试：`DevPluginFileServiceTest` 6/6、`DevPluginFramesTest` 7/7。
- 真机探针（`.artifacts/devplugin-files-probe.py`）：list_files（含递归 2127 项）、
  read_file（md5 配对字节帧）、write_file+读回、`../` 越界拒绝，全部通过。
- 扩展自己的 TS 实现端到端（`vscode-extension/test/e2e-phone.ts`）：hello → list → read →
  write+读回 → run（日志回传）→ run_project（自研 zip 推送并运行）→ stopAll，全部通过。
- VSIX `aijspro-vscode-0.1.0.vsix` 已装本机 VS Code；装后 8327 LISTEN、手机自动回连成功。
- **隔离实例端到端**（`test/isolated-vsix-e2e.ps1`）：在干净的 `--user-data-dir/--extensions-dir` 里只装本扩展，
  用 `AIJSPRO_E2E_CONNECT=192.168.31.59:9317`（开发钩子）直连手机，扩展写入了
  `aijspro.devices:["192.168.31.59"]`（只有完成 hello 握手才会写）——VS Code 宿主里的激活/连接/握手链路已验证。
- 手机侧需 **dev-729 及以后**（新增 list_files/read_file/write_file）；断点调试需 **dev-731** +
  扩展 **0.2.0**；图色助手需 **dev-735** + 扩展 **0.3.0**；详细报告见
  `docs/reports/2026-09-29_VSCode自研扩展.md`。

## 与官方扩展共存

本扩展与 `hyb1996.auto-js-pro-ext` 都监听电脑 8327：同时启用时只有一个能接收手机回连
（先启动者占用；另一个仍可主动连手机 9317）。建议同一时间只用一个扩展的自动连接。

## 命令表（对齐官方 Auto.js Pro 扩展）

命令面板搜 `AI.js Pro` 可见全部：使用教程(Help)、连接到新设备(Connect)、选择设备(Select Device)、
重新扫描设备(Scan Devices)、运行(Run)、运行单文件(Run File)、运行项目(Run Project)、
重新运行(Rerun)、停止当前脚本(Stop)、停止所有脚本(Stop All)、布局分析(Inspect Layout)、
图色助手(Screenshot & Pick Color)、浏览设备文件(Browse Device Files)、保存当前文件(Save)、保存项目(Save Project)、
新建第一代API项目(Rhino)、新建第二代API项目(QuickJS)、在工作区建立第一/二代API项目、
调试当前文件(Debug File)、增加调试设备配置(Add Debug Config)、断开所有连接(Disconnect)、
刷新文件(Refresh Directory)、显示日志(Show Logs)、开关「保存时自动推送」。

差异：第二代官方是 Node.js，本应用没有 Node 引擎 → 映射为 **QuickJS**；断点调试与图色助手
都已实现（Rhino，DAP，`type: aijspro`）；布局分析与图色助手需要手机开启 AI.js Pro 的无障碍服务
（与手机悬浮窗同一数据源，布局分析手机侧需 dev-730+）。

