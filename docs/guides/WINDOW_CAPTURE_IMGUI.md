# 指定窗口截图与 ImGui 绘制

## 已接入的接口

`images.isWindowCaptureAvailable()`：Android 14+、无障碍服务连接且具有截图能力时返回 true。

`images.listCaptureWindows()`：列出其他应用的无障碍窗口，包含 windowId、packageName、active、focused、foreground、bounds、displayId。当前支持默认显示器。

active、focused 保留系统原始值。foreground 是宿主选窗口时使用的状态：活动窗口，或者宿主自己的无障碍/系统覆盖窗活动时，底下仍有输入焦点的应用窗口。这样点击操作窗后仍能选择原应用；不会把普通后台窗口视为前台。点击前应结合窗口 ID、包名、边界和旋转复查 foreground。

```js
const frame = images.captureWindow({
    // windowId: 123,             // 来自 listCaptureWindows，不是进程 ID
    // packageName: 'game.package', // 可与 ID 同时使用，校验目标
    timeout: 2000,
    screenCoordinates: false     // 默认返回窗口局部图片
});
try {
    const result = detector.detect(frame.image, { confidence: 0.25 });
    // 局部图坐标 → 屏幕坐标：
    // screenX = bounds.left + x * bounds.width / image.width
    // screenY = bounds.top  + y * bounds.height / image.height
} finally {
    frame.image.recycle();
}
```

Rhino 与 QuickJS 都提供这三个接口。QuickJS 的 `size` 可缩小原生帧存储，保留逻辑坐标；Rhino 使用完整窗口分辨率。

返回 image、windowId、packageName、bounds、frameId、timestamp（elapsedRealtime 毫秒）、rotation、displayWidth、displayHeight、coordinateSpace、isolated。frameId 是宿主分配的请求结果序号，不代表游戏内部帧号。

省略 ID 和包名时选择其他应用的 foreground 窗口；提供包名后不会改抓其他应用。窗口在截图期间移动或旋转时丢弃本帧。超时、服务断开、受保护窗口等情况抛错，不回退到整屏。截图后的窗口变化仍需要点击前检查。

`screenCoordinates: true` 将窗口内容映射到真实屏幕尺寸的黑色画布，窗口外不含其他应用或悬浮窗。已有棋盘网格、区域取样、画框、点击代码可继续使用屏幕坐标。这条兼容路径有 Bitmap 和像素复制开销，目前没有真机耗时结论。

## 原生绘制

QuickJS：

```js
if (!drawing.show({ renderer: 'imgui' })) throw new Error('绘制窗口创建失败');
drawing.update([{ x1:100, y1:300, x2:200, y2:400, label:'红车' }], '识别状态');
// drawing.hide() 或脚本退出释放窗口及 GL/ImGui 资源。
```

QuickJS 的 Canvas / ImGui 均支持箭头、圆圈、线段与文字：

```js
drawing.clear();
drawing.drawArrow(100, 600, 400, 300, '#FFD54F', 5, 24);
drawing.drawCircle(100, 600, 16, '#40C4FF', 3);
drawing.drawText(100, 650, '起点', '#40C4FF', 30);
drawing.update([], '深度 18\n评分 30cp');
// 空字符串明确清除状态文字；不传第二个参数则沿用上一份文字。
drawing.update([], '');
```

`drawArrow(x1,y1,x2,y2,color,strokeWidth,headSize)` 最后两个参数默认 4/18px（Canvas）或 3/18px（ImGui）；短箭头自动缩小箭头头部，重合点不画箭头。`drawCircle(x,y,radius,color,strokeWidth)` 使用屏幕像素坐标。也可通过 `drawing.update` 的 `kind:'arrow'/'circle'/'line'/'text'` 快照格式提交；箭头使用 headSize，圆圈使用 radius，文字使用 size 和 label。show/hide 会清掉上一窗口的命令缓存。

在快照中加入 `{kind:'hud',x1:12,y1:200,x2:1068,y2:500}` 可限定状态文字的区域（Canvas / ImGui）；空边界禁用 HUD，其他图形照常绘制。象棋面板按实际操作窗、日志窗、棋盘与棋子边缘寻找空带；没有可用空间时保留箭头，收起操作面板后显示诊断 HUD。超出空带的文字会裁剪，不覆盖其他区域。

使用 Dear ImGui v1.91.9b + OpenGL ES 3。默认 `drawing.show()` 仍使用 Canvas。
新绘制层为全屏、不可触摸的透明窗口；有无障碍服务时使用 TYPE_ACCESSIBILITY_OVERLAY，其余情况使用悬浮窗权限并限制窗口 alpha。常用操作通过另一个有边界的 ImGui 操作窗接收触摸；完整参数面板沿用现有控件。

只保留最新绘制快照，更新时渲染，不常驻 120fps。使用 APK 自带静态 CJK 字体；构建失败时清理图集后重建拉丁字库，检查字体已加载后才进入第一帧。没有覆盖的字符可能显示方框。

## 有边界的 ImGui 操作窗（QuickJS）

```js
const state = {title:'控制台', buttons:[{id:'start',label:'开始'}, {id:'stop',label:'停止'}]};
const opened = drawing.panel.show(Object.assign({x:24,y:250,width:900,columns:2},state));
if (!opened.ok) throw new Error('操作窗创建失败');
// 定时器中读取；返回按钮 ID 和墙钟毫秒 at，没有 GL 线程回调脚本。
const batch = drawing.panel.poll();
batch.events.forEach(function (event) { log(event.id,event.at); });
drawing.panel.update(state);
// 只隐藏：drawing.panel.update({visible:false,touchable:false});
// 关闭：drawing.panel.hide();
```

每引擎一个窗，按钮数 1～12，创建后数量固定；ID 唯一且为字母/数字/下划线/连字符。按钮可设 `enabled:false`。show/poll 返回 ok、x、y、width、height、visible、events。poll 消费事件；标题区拖动完成产生 `drag`。x/y/width 为屏幕像素，宽度/列数/位置会夹到屏幕内，高度由按钮行数和 48dp 触摸高度决定。内容更新传 title/buttons，位置和状态更新传 x/y/visible/touchable。

Android UI 线程处理拖动，GL 线程生成按钮事件；脚本以 100ms 定时器轮询 ID 并执行现有控制器命令。输入队列有限；每渲染帧处理一个 DOWN/MOVE/UP，避免快速点击被合并。隐藏、禁用触摸和关闭会清掉输入。改按钮 ID/禁用状态会取消旧按压。绘制层与操作窗分别关闭，正常清屏不会关闭按钮。

象棋 0.6.61 默认直接打开 ImGui 小操作窗，模式/开始或暂停/停止/定位/识别/更多各一个按钮。参数面板和日志先隐藏创建，点“更多”再显示；完整面板的“ImGui”按钮可回到小窗。`autorun.collapse=false` 可显式使用完整面板，省略该字段时使用小窗。切模式先停止；定位/识别仅暂停后可用；停止同批优先，过期开始事件丢弃。脚本阻塞期间按钮事件仍需等待脚本恢复，尚未实现跨线程强制中断。

象棋 0.6.57 将小操作窗的显示与截图来源解开：自动、录屏、Shell、窗口模式均可使用；没有完整 panel API 的旧宿主使用兼容小窗。无障碍已连接时宿主创建无障碍覆盖窗，否则要求普通悬浮窗权限。切截图来源仍先停循环、作废帧几何和绘制，但保留操作窗和收起状态。普通截图时面板与棋盘重叠仍暂避；免无障碍显示不代表已取得截图/自动点击权限。窗口截图需要无障碍；录屏需要授权；Shell 截图需要相应权限，免无障碍自动落子需要可用的 Shizuku 点击通道。0.6.59 起全部通道共用 ImGui 走法绘制，普通截图前清空绘制内容。

象棋 0.6.58 修正截图显隐：窗口模式即使定位要求 `hide:true` 也不隐藏覆盖窗；普通截图按操作窗、日志窗各自遮挡情况暂避，日志单独遮挡不再连带隐藏操作窗。结算页底部复核沿用同一策略。点击或外层截图已持有的隐藏状态不重复计数、不提前恢复；本次截图失败或等待被中断时恢复自己隐藏的窗口。普通全屏截图在面板确实压住识别区时仍须暂避。

象棋 0.6.60 按自动走棋需求收口：只画我方黄色箭头与对方预判蓝色箭头，去掉方框、圆圈和 HUD；建议模式不画走法。预判取引擎返回的下一手，并以我方预期后局面核对合法性。确认/等待对方时保留已发送箭头，直到真实应手或新走法；暂停、停止、硬截图失败、几何失效仍清除。

连续绘制在启动/首次抓帧前自动选择可用的窗口截图，不覆盖保存的截图偏好。截图不清绘制内容；没有窗口截图能力时停用实时箭头并提示，自动走棋和操作窗仍可工作。Android 14+ 窗口截图需要无障碍服务。普通录屏/Shell 手动审核仍清内容取得干净帧。

## 象棋项目接入

主界面点“截图来源”选择“窗口截图”，再切到游戏定位校准。该选项需要新版宿主 APK；已导入旧项目也需要重新导入新示例。

- 使用屏幕坐标画布，保留现有定位、取色、YOLO 和落子坐标。
- 截图期间不隐藏操作面板或应用悬浮球；截图失败停止交付本帧。
- 全部截图来源的走法绘制使用 ImGui；自动模式只显示方向箭头。
- 切换截图通道时停止面板循环，清除旧网格与绘制层。
- 点击前复查目标窗口边界和活动状态；操作面板遮住点击位置时，仍沿用点击租约暂避。

工程 0.6.55 的 `ui/render-state.js` 将已验证票据转换为最佳走法箭头、起终点和 HUD。深度、评分、搜索耗时与 PV 随搜索结果传到票据；最新观测与票据不一致、票据到期或暂停时不显示旧建议。搜索结果接收后刷新，并跳过相同绘制快照。截图失败和几何失效用空快照清屏，保留已挂载透明窗口。

HUD 区分截图 API 调用耗时、抓帧总耗时（包含多帧稳定等待）、帧检查、识别与搜索耗时；不是互斥分段，不能直接相加。当前象棋自动循环采用字形识别，HUD 使用“识别”标签，没有将其误标成 YOLO。

Android 13 及以下继续使用原有整屏/shell 通道。这里没有改 YOLO 为 NCNN，也没有改 Pikafish 的搜索架构。8ms 推理与 60/120fps 属于参考文档的目标值，未作为实测结果。

## 依据

- [Android 指定窗口截图](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))：API 34，官方支持目标窗口在无障碍覆盖层下的截图。
- [Android 触摸穿透规则](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE)：Android 12+ 对非受信悬浮窗有不透明度限制。
- [Dear ImGui 上游](https://github.com/ocornut/imgui/tree/v1.91.9b)：版本与 MIT 许可证已随源码纳入。

2026-10-04 新手机 2602BRT18C（Android 16）dev-785 复现 ImGui 第一帧闪退。反汇编定位到字体的 ContainerAtlas 空指针；从该手机提取的系统 TTC 在同一 ImGui 构建器中返回 Build=false/IsLoaded=false。自带字体与失败回退均通过第一帧回归。修复后的真机结论以本轮报告为准，不能将单机验收扩展为所有 ROM。

K40（Android 16）修复预览包通过示例 11、12，以及同时显示绘制层/操作窗的截图隔离验收：计数、清零、拖动、关闭都收到事件；操作窗外触摸可打开设置搜索；目标窗口截图没有按钮、标记线或 HUD。静态 HUD 首次更新补一帧测量后显示，不建立常驻绘制循环。尚未完成新手机修复包复测，也未完成象棋 0.6.56 的整局自动落子验收。详见 `docs/reports/2026-10-04_ImGui操作窗与字体闪退修复_dev786.md`。
