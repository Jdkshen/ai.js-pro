# 象棋 RenderState 与 ImGui 走法绘制 — dev-785

## 本轮完成

- 象棋工程更新为 0.6.55。搜索结果的实际深度、评分、耗时、PV、引擎来源沿 JOB_RESULT → move-ticket 传递。
- 新增 `src/ui/render-state.js`，只生成数字、文本、图形快照，不持有原生截图句柄，也不参与识别、搜索和点击。
- 窗口截图模式使用黄色最佳走法箭头、蓝色起点圈、黄色终点圈。先核对会话、局面、模型、几何版本、票据有效期和最新棋盘；旧建议同时清掉箭头、PV、深度和评分。
- 当前字形识别路径只有 cells 而无 layout 时，从 cells 计算布局；修复过的 cells 优先于旧 layout，避免展示与真实观测不一致的着法。
- 搜索结果收尾后刷新绘制，不只在识别回调里刷新；相同快照跳过 JNI 提交。
- 暂停、重新定位、几何失效和截图失败提交空快照清屏，保持透明原生窗口挂载；新帧恢复后可以再次提交同一份有效内容。
- HUD 支持换行与限定边界，避开操作面板、日志、含棋子边缘的棋盘。无空带时保留图形、提示收起面板；超出区域的文字裁剪。
- 截图 API 调用耗时和抓帧总耗时分别记录。后者包含稳定等待，不能与其他显示指标直接相加。识别耗时按实际字形链标注，没有写成 YOLO。
- QuickJS 新增 `drawing.drawArrow`、`drawCircle`；Canvas / ImGui 都支持对应快照形状。修复 Canvas 线段误画矩形、空标签仍画百分比、空状态文字清不掉、show/hide 重播旧命令等问题。
- 更新应用文档、API 补全、窗口截图示例、象棋项目结构 MD。

## 验证

- `test_drawing_primitives.js`：完整 QuickJS bootstrap 语法、箭头/圆圈参数传输、HUD 清除、缓存重置通过。
- `test_chess_render_state.js`：实际状态机生成票据后的字段传递、屏幕起终点、过期/旧会话/旧局面/旧模型/旧几何/暂停拒绝、字形 cells 布局、HUD 空带、失败清屏、重复提交抑制通过。
- `test_chess_window_capture.js`：绑定来源、旧坐标拒绝、无整屏回退、失败资源释放、窗口模式不藏窗通过。
- 完整象棋回归：17 组场景通过；349 项自检、19 项格子契约及竞争策略、开局库检查通过。
- 面板全量启动、按钮/线程/搜索模式、折叠/展开、取消/旧回调、锁、设置同步检查通过。
- 架构检查：production=134，driverActions=93，panelActions=142。
- 原生库 armeabi-v7a / arm64-v8a / x86 编译成功；APK 与两项示例文本校验任务通过。
- 最终 APK 对当前象棋源码逐文件校验通过。构建包含工作区同期已有改动，保留了其他工作对字形识别和悬浮球默认行为的修改。

## 本地发布

宿主版本：dev-785 / versionCode 785。

象棋版本：0.6.55 / versionCode 55；302 文件，sourceSha256：

`582edeeba48d9a15c49594c665e495d86b91c90ade4ecd6f04a4780eb2986430`

本地 APK：`.artifacts/updates/app-miuix-compat-arm64-v8a-dev-785-no-usb-debug.apk`。

APK SHA-256：`b0459c5c2fa84e8c0c97d9668e43f3541ce9a031044282043c4625f1c91727cd`。

通过 `tools/publish.ps1 -SkipBuild -AllowMultiDevice -DeviceId cccc62c7 -PcHost 192.168.10.7` 发布；清单、产物版本与哈希通过，K40 拉取更新清单 HTTP 200；PC 清单与 APK HEAD 均为 HTTP 200。

发布日志：`.artifacts/publish-20261004-172702.log`。

## 使用与范围

应用检查更新到 dev-785，重新导入象棋 0.6.55，截图来源选“窗口截图”，定位校准后启用识别绘制。HUD 空间不足时收起操作面板；Android 14 以下使用原通道。

本轮完成显示快照和绘制层。ImGui 操作按钮、完整交互面板迁移及 NCNN YOLO 没有纳入本轮。没有进行手机脚本运行或安装测试，原生绘制、字体、触摸穿透及 ROM 隔离仍需要实际验收；不声称新的帧率或推理速度。
