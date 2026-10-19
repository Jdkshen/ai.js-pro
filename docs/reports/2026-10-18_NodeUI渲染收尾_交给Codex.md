# Node UI 渲染收尾 — 交给 Codex 的交接文档

更新时间：2026-10-18 08:40（Asia/Shanghai）
仓库：`C:\Users\18101\Documents\ai-js-pro`
Flutter 源码工程：`C:\Users\18101\Documents\AutoJsPro-本地工程-20261008\工程\output\autojs-pro-flutter-rebuild`
当前版本：**dev-1031**（APK 已构建并安装到新手机 DQKFJB59596L45BI，尚未发布到更新源）

---

## 1. 目标（只做这一件事）

让在 App 里运行 `Node.js使用Android原生界面` 示例时，界面与 **Auto.js 高级版（com.boyai.auma）** 一致：

1. **卡片 ❤️/● 图标可见**（grid_item.xml 内两个 MaterialButton，`app:icon=ic_favorite_black_48dp / ic_share_black_48dp`）；
2. **顶部固定显示「M3 UI Demo」标题栏**（AppBar/Toolbar）；
3. 不要引入回归：单层 Activity（无 overlay 叠层）、返回/脚本结束回文件列表、脚本与浮窗不被杀。

现状：日志全部正常但**像素不绘制**，截图见 `output/dev1030-node-ui.png` / `dev1031-node-ui.png`（后者被探针脚本挡住）。

---

## 2. 现状证据（dev-1031 真机日志）

```
I/C-INFLATE: dynamic fallback (no project-res.apk) layout=activity_main
I/NodeUiActivity: C-INFLATE: dynamic fallback layout=activity_main
I/C-INFLATE: C-TOPBAR: inserted h=334 statusBar=152
I/C-ICON: tint=0xff49454f size=78px w=130 h=130 measured=130x130           icon=BitmapDrawable btnIconSize=78 bg=RippleDrawable view=MaterialButton
I/NodeUiActivity: appbar top=0 h=208 translateY=0.0 scrollFlags=0 pagerTop=152 pagerBehavior=null
I/NodeUiActivity: C-TITLE title=M3 UI Demo
```

即：属性/测量全对（图标 130×130 在卡片内、AppBar h=208 top=0、标题已设），**但屏幕上看不到**。

### 根因判断（已被对照实验验证）

- 该示例工程没有 `build/project-res.apk`，项目资源是**合成 id**（非真实 aapt2 id），**系统 LayoutInflater 无法解析** → 只能走自研 `DynamicLayoutInflater`；
- 在 Dynamic inflater 路径下：
  - `MaterialButton.setIcon(...)` 的图标绘制链路不生效（真机探针实验：`setBackground(ColorDrawable(TRANSPARENT))` 会破坏图标；`setIcon` 在动态 inflate 的 View 上不画）；
  - `AppBarLayout` / `MaterialToolbar` 不绘制；
- 因此 dev-1031 做了两个兜底（已在代码里）：
  1. `NodeUiRenderFix` 对 MaterialButton 用 `setCompoundDrawablesRelativeWithIntrinsicBounds(...)`（+post 再设一次）；
  2. `NodeUiActivity` 用 `wrapWithTitleBar` 自插一个 appcompat Toolbar（#F3EDF7、56dp+状态栏、深色标题「M3 UI Demo」）包住 content。
- **dev-1031 的截图无效**：设备上还残留 imgui-re 的探针脚本（D0~D8 测试界面）盖在最上层，需先清理再截图。

---

## 3. 复现步骤（PowerShell + adb）

```powershell
$adb='C:\Android\platform-tools\adb.exe'
$d='DQKFJB59596L45BI'   # 新手机；小米8=ce4d2bdb；K40=cccc62c7（可能离线）
$apk='C:\Users\18101\Documents\ai-js-pro\apps\app\build\outputs\apk\miuixCompat\debug\app-miuix-compat-arm64-v8a-debug.apk'

# 0) 清理残留探针脚本（重要！）
& $adb -s $d shell am force-stop com.jdkshen.aijspro
# 如仍有浮窗：下拉通知/桌面查看，或 & $adb -s $d shell am force-stop com.jdkshen.aijspro:native

# 1) 安装当前构建
& $adb -s $d install -r $apk

# 2) 启动并运行示例
& $adb -s $d shell am start -n com.jdkshen.aijspro/com.jdkshen.aijspro.flutterpoc.GlassFlutterActivity
Start-Sleep -Seconds 4
& $adb -s $d shell "am broadcast -n com.jdkshen.aijspro/com.jdkshen.aijspro.flutterpoc.DebugCommandReceiver -a com.jdkshen.aijspro.flutterpoc.DEBUG_RUN -e path '/sdcard/脚本/Node.js使用Android原生界面/main.js'"
Start-Sleep -Seconds 16

# 3) 截图 + 日志
& $adb -s $d shell screencap -p /sdcard/node.png
& $adb -s $d pull /sdcard/node.png .\output\codex-node-ui.png
& $adb -s $d logcat -d -v brief | Select-String 'C-INFLATE|C-TOPBAR|C-ICON|appbar|C-TITLE|C-UI|FATAL'
```

> 注意：**不要**用管道过滤 gradle 输出（会杀 gradle）；构建请用下方脚本并写入日志文件。

---

## 4. 关键代码位置

| 文件 | 作用 |
|---|---|
| `apps/app/src/main/java/com/jdkshen/aijspro/engine/NodeUiActivity.java` | 主进程承载 Activity：`applyIntent`→inflate→`NodeUiRenderFix.inflateFramework`→`wrapWithTitleBar`→`fixAppBar`→`handleUpdate` |
| `apps/app/src/main/java/com/jdkshen/aijspro/engine/NodeUiRenderFix.java` | framework/dynamic inflater 选择、绘制兜底（compound drawable、AppBar 背景、自插 toolbar）、`apply()` |
| `apps/app/src/main/java/com/jdkshen/aijspro/engine/NodeUiBridgeService.java` | :node24 → 主进程 Messenger（MSG_SHOW/CLOSE/UPDATE） |
| `modules/engine/src/main/java/com/stardust/autojs/engine/node24/NodeUiHost.java` | :node24 侧 UI 主机（单通道 activity/overlay、setPager/setAdapter、图片加载、图标属性链） |
| `modules/engine/src/main/java/com/stardust/autojs/core/ui/inflater/DynamicLayoutInflater.java` | 动态 inflater（合成 id 资源解析、构造器候选链、include、LP） |
| `modules/engine/src/main/java/com/stardust/autojs/core/ui/attribute/MaterialButtonAttributes.java` | MaterialButton 属性（icon/iconTint/iconSize/IconButton style 兜底） |
| `NodeUiProtocol.java` / `NodeUiActivityClient.java` | 协议与 :node24 客户端 |
| `output/flutter-poc/build-aot.ps1` / `publish-poc-build.ps1` / `tools/publish.ps1` | AOT、构建、发布 |

项目资源链路：`ProjectResourceBundle`（合成 id、`.codecache`、`project-res.apk`）在 `modules/engine/src/main/java/com/stardust/autojs/core/ui/inflater/ProjectResourceBundle.java`。

---

## 5. 建议的后续方案（按优先级）

### P1（首选，彻底）：为 Node 项目生成 project-res.apk，走系统 LayoutInflater
- 现在 `NodeUiRenderFix` 已支持「有 project-res.apk 时用系统 inflater（`C-INFLATE: framework`）」；
- 需要：在运行前把项目 `res/` 用 aapt2 编译成 `<staging>/build/project-res.apk`（PC 侧可先用 `aapt2 compile/link` + 项目 AndroidManifest；
  设备侧若有 aapt2 亦可，但更实际是 PC 侧脚本 `tools/build-project-res.ps1` 并随项目一起 push）；
- 一旦有该 apk，MaterialButton 图标、AppBar、主题全部原生渲染，无需兜底。

### P2（快速验证，可能仍不完美）：继续动态 inflater + 兜底
1. 清掉探针脚本后重新截图，确认 `C-TOPBAR inserted h=334` 是否真的可见（toolbar 已包在 content 外，按代码应该可见）；
2. 若 ❤️/● 仍空白：在 `NodeUiRenderFix.apply` 里对 **GridView item**（`setAdapter` 创建的卡片）也执行 compound drawable 兜底（目前 apply 只作用于 activity_main 树；卡片是 rank update 后 inflate 的，需要 A 的 helper 供 B 调用）；
   - B（NodeUiHost.NodeUiAdapter）在 `onBind` 后调用 A 的 `NodeUiRenderFix.applyIcons(itemView)`；
   - 或 B 直接用 `bundle.idOf("id","...")/drawable id` 给按钮 `setCompoundDrawablesRelativeWithIntrinsicBounds`；
3. 若标题栏与 content 重叠/被状态栏遮：检查 `wrapWithTitleBar` 的 paddingTop（状态栏高度）与 content 的 weight=1 是否生效（log `C-TOPBAR: inserted h=334 statusBar=152`，h=56dp+152 正常）。

---

## 6. 验收标准

- 运行示例截图：卡片 2 列、真实网络图片、标题（功能1..N）、**❤️/● 图标可见**、**顶部「M3 UI Demo」可见**；
- 与 `output/dev1018-node-ui.png`（图片已加载版本）对比只多出图标和标题栏；
- 返回键 → 回 Flutter 文件列表；脚本结束 → Activity finish；无 overlay 叠层（`C-UI: mode=activity` only）；
- 脚本/浮窗在回桌面后继续运行（dev-1023 行为不回退）。

---

## 7. 构建与发布（本仓库约定，必读 AGENTS.md）

```powershell
# JDK17
. .\tools\jdk17.ps1 | Out-Null

# 1) 只改 Java/Kotlin（无 Dart 改动）→ 直接构建 APK
& 'C:\Users\18101\Documents\ai-js-pro\output\flutter-poc\publish-poc-build.ps1' -VersionCode <N> -VersionName 'dev-<N>' *> 'output\flutter-poc\build-<N>.log'

# 2) 改了 Flutter/Dart（lib/*.dart）→ 先重建 AOT payload
& 'C:\Users\18101\Documents\ai-js-pro\output\flutter-poc\build-aot.ps1'

# 3) 发布（注意：publish.ps1 会自动算 expected = max(repo, published, 在线设备) + 1，
#    所以 APK 的 versionCode 必须等于这个 expected，否则 dev-update 拒绝）
& .\tools\publish.ps1 -ReleaseNotes "<版本说明>" -SkipBuild -PcHost 192.168.10.7 -AllowMultiDevice -DeviceId <在线设备序列号>
```

- 在线设备：`DQKFJB59596L45BI`（新手机，当前 1031）、`ce4d2bdb`（小米8，1013）；K40 `cccc62c7` 常掉线。
- 发布前用 `Invoke-RestMethod http://192.168.10.7:8080/update.json` 核对 versionCode。
- 不要用 `Select-String` 过滤 gradle 输出；只认 `$LASTEXITCODE`。

---

## 8. 已知雷区

1. `ViewExtras.get()` 热路径日志曾导致整机卡顿（已删，勿加回）；
2. `DynamicLayoutInflater` 的属性应用顺序、构造器候选链、LayoutParams 预套逻辑很脆弱，改动要小步并看 `C-ICON/C-INFLATE` 日志；
3. `NodeUiActivity` 是主进程 `singleTask`，脚本结束会 `MSG_CLOSE` → finish；不要在 onDestroy 里杀脚本/清浮窗（dev-1023 已修）；
4. Flutter 返回键已与 Dart 协商（MethodChannel `com.jdkshen.aijspro/back`），不要改回硬编码 finish；
5. 设备上残留的探针脚本/浮窗会挡住截图，先 force-stop。

---

## 9. 交付

- 修复后用上面「验收标准」截图（手机真实截屏，附日志 `C-INFLATE/C-TOPBAR/C-ICON`）；
- 构建并发布 dev-1032+（或按版本号约定）；
- 把改动文件与关键日志写入 `docs/reports/2026-10-18_NodeUI渲染收尾结果.md`。
