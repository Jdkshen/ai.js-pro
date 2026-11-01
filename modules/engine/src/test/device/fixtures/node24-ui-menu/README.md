# 旧 Node UI 菜单设备回归

将本目录复制到临时工程目录，把 `../../node24-ui-menu-protocol.js` 复制为 `main.js`，再运行仓库的 `tools/build-project-res.ps1 -ProjectDir <临时工程> -AppApk <当前候选APK>`。推送整个工程后，通过 MCP 的 `run_script.path` 执行 `main.js`，选择 nodejs 引擎。

需要编译资源，强制真实 JSON/IPC 兼容路径。测试读取菜单、底部/侧边栏真实选中、once 和 Toolbar 的明确不支持项，输出 `MENU_PROTOCOL_SUMMARY`。等待事件按宿主轮询节拍处理，不能以 200ms 固定等待代替就绪。

project.json 的 versionName/versionCode 不可省略，否则当前 ProjectConfig 判无效并退到仅编译资源上下文，无法扫描原始 XML；不要把无效测试工程造成的“找不到布局”记录为菜单回归。
