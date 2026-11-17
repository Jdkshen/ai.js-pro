# AI.js Pro 组件更新

此发布页仅分发按需下载的组件，不是主程序 APK 更新页。主程序 APK 使用正常版本发布。

新版主程序同时查询设置中的自定义源与默认 GitHub 源；两路不互相覆盖。默认 GitHub 地址长期保留。
组件只在用户下载或功能需要时安装，不把所有大型组件默认塞进 APK。

默认目录由 GitHub 官方 component-release 分支提供；主程序通过官方 Contents API 的原始 JSON
接口请求，逻辑来源保留 raw 地址，自定义源地址原样使用。附件使用项目既有加速通道，
同时保留 GitHub 直连 mirrors；附件必须匹配官方目录的 SHA-256，切换地址不降低校验。

## 组件清单

- 脚本组件：OCR（PPocr / ML Kit）、APK 打包运行模板、象棋引擎。
- 工具组件：JADX、APK 资源工具、Apktool、Radare2、r2ghidra、YARA、Unicorn、Frida 客户端、Frida Gadget。
- 编译环境：C/C++（复用独立 C++ 发布的固定载荷）。D8 / Smali 等内建能力由主程序提供，不伪装成下载包。

Frida 客户端连接目标应用中的 Gadget；此页不提供或要求 Root Frida Server。

## MCP 使用

先调用 `apk_component_status` 查看工具组件与内建能力。显式检查远端版本用
`apk_component_status {"refresh":true}`，安装使用 `apk_component_install {"component":"jadx","background":true}`。
后台任务通过返回的 jobId 使用 `apk_job_status` / `apk_job_result` / `apk_job_cancel` 跟进。

脚本组件使用 `file_component_list`。`file_component_download {"id":"ocr","retry":true}` 是主动修复入口，
需要读取 `file_component_auto_status` 的真实版本、错误与 warnings；已经加载原生库后可能需要完全退出并重开主程序。
组件页的检查更新与同版本缓存复用不强制替换。

附件下载后校验清单里的 SHA-256 和字节数。版本号、ABI、最低系统版本和来源随组件元数据发布；
来源故障不会关闭另一条更新路径，跨源切换不能混用不同载荷的 SHA 或断点。

各工具的上游来源、固定版本及许可证随其 ZIP 的 SOURCE-NOTICE / licenses / upstream-lock 文件提供。

## 删除可选组件（dev-1623 起）

组件页中已安装或有旧版本的可选组件提供“删除”，需要确认；“停用”仅取消激活并保留文件。
MCP 对应 `file_component_remove {"id":"..."}` 与 `apk_component_remove {"component":"..."}`。
成功检查 `installed=false`，再刷新清单；占用返回 COMPONENT_IN_USE，下载/安装冲突返回 COMPONENT_BUSY。
先结束脚本、编译任务和常驻分析；文件组件已加载时需完全退出应用并重开后再删。
源 ZIP、脚本、工程和其它组件保留；身份不明的断点通过 warnings 说明，锁和管理状态保留用于安全复装。
内建 D8、Smali、apksig 是主 APK 能力，不能单独删除。APK 打包组件最新1.0.10最低宿主1623；旧兼容包继续保留。
