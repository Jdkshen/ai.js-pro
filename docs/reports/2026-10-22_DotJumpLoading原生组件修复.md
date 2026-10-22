# DotJumpLoading 原生组件兼容修复

2026-10-22，**dev-1200 已发布**，小米 8 已安装并实测；K40 保留 dev-1199，运行中的象棋业务未做本轮更新。
APK：`AI-js-Pro-Flutter-dev-1200.apk`，151,737,565 字节。
SHA-256：`3bd334b448a4bcc6d3892061f4537401820c02f24d0d3a852d9dd23e38004c4e`。

## 复现与原因

用户原始文件：`C:/Users/18101/Downloads/DotJumpLoading-43ee3eec2d9a42d39e41e419efa51943.zip`。
原文件 SHA-256：`ff87f29a814ba9404acfa42a819f6d68fbfe2ea3673012e769c45089ce848cbb`，保留不变。

这是 Rhino UI / JavaAdapter 项目。原始组件的 `ui.Widget.render()` 返回真实 `android.view.View`，
宿主 `modules/__ui__.js` 却无条件将返回值转成 XML 字符串，报：

```text
SAXParseException: Unexpected token (position:TEXT adapter1...)
file:///android_asset/modules/__ui__.js#56
```

## 宿主修复

在 `beforeCreateView` 中区分原生 View 和 XML。原生 View 保留对象身份，
显式执行既有 `afterCreateView`：设置 widget.view、view.widget、属性 delegate 并通知 onViewCreated，
然后由外层 inflater 应用 XML 属性、布局参数和 onFinishInflation。

这一步不能只直接 return View：Java inflater 对 beforeCreateView 返回值会跳过 afterCreateView，
导致自定义属性执行时还没有 setJumpHeight 等组件方法。候选首轮的这个失败也保留了证据。
原有 XML render 路径继续使用原 inflater；没有新增 UI/进程架构或用图片替代自绘动画。

## 工程副本

宿主升级后**未修改的原包也可以运行**。另提供修正版：

- 源码：`samples/DotJumpLoading/`，main.js / project.json / widgets / README。
- ZIP：`C:/Users/18101/Downloads/DotJumpLoading-fixed-dev1200.zip`。
- ZIP SHA-256：`fbd222976a24b3d0ed48d24fca0cc17f17476e2fec7c0fd3c9801013d360f600`。

修正版增加 Rhino 引擎标记，修正自动测量高度与固定高度的可用跳跃范围，避免顶端圆点被裁掉；
轮换条件由 >= 改为 >，包含最后一个圆点；回前台按用户暂停状态恢复动画。
解压时保留整个工程目录，从工程入口或 main.js 运行，宿主需 dev-1200 或更新版本。

## 实际验证

- 小米 8 / Android 35 / dev-1200：原始 main.js 从普通 RunIntent 入口运行成功，三组真实圆点跳动；
  修正版同一入口运行成功，自动尺寸不再裁切。
- `tools/probes/rhino-native-widget-contract.js` **19/19**：真实 TextView 返回、方法/属性顺序、
  对象身份、XML w/h、实际测量、onFinishInflation，以及旧 XML render 组件回归。
  DP 编写探针，主代理纠正探针中子控件 ID 拼写后在真机执行通过；离线 mock 结果未作为产品证据。
- `tools/probes/dot-jump-loading.js`：原包、修正版**各 9/9**。
  检查三组真实 View 测量、自动播放、进度推进、暂停与帧冻结、换色后的 Bitmap 像素差异、
  调高度后的重新测量、继续按钮及恢复后的进度推进。
- 修正版另 **2/2**：自动高度为完整跳跃区域；实际 ValueAnimator 轮换观察到 1、2、3、4、5 全部阶段。
- 发布器通过版本/哈希、22 个 64 位 ELF 无白名单 16KB 校验，手机拉更新清单 HTTP 200。
- `git diff --check` 与不含 E4X 的组件/探针 JS 静态语法检查通过。

证据：`output/dot-jump-loading/` 下 original、native-view-fix-*、native-widget-contract-fixed、
animation-contract、fixed-details 及截图；发布日志 `.artifacts/publish-20261022-131505.log`。

这次只修 Rhino 原生 View Widget 契约；QuickJS/Node 的 JavaAdapter 类继承兼容仍属其他缺口，
不能把这个项目强制切换成 Node 工程后认定同样支持。
