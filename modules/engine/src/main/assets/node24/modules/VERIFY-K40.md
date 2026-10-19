# task-146 K40 验证记录

环境：K40（M2012K11AC / cccc62c7），App dev-1083（flutterPoc 构建），Node24 Node v24.22.0。
验证方式：把 main.js 与本目录 3 个模块文件 push 到 /sdcard/脚本/node24-pro-modules/，用 RunIntentActivity 运行 nodejs 脚本；模块通过工程内 require('./modules/xxx.js') 自行注册（模拟 prelude 之后加载；正式合并见 REGISTER-PATCH.md）。
证据：.artifacts/task146-k40-result.json、.artifacts/task146-k40-visual.json、.artifacts/task146-visual.png。

## 结果摘要

1. color（Pro 语义完整版）通过：
   - 导出 Color / HSVColor / HSLColor / toColorInt；
   - Color.parse('#ff0000') 得到 Color(0xffff0000)、alpha=255、red=255；fromARGB(0x77,255,0,0) 得到 Color(0x77ff0000)；fromRGBO(...,0.5) 得到 Color(0x80ff0000)；
   - Color.parse('red') / Color.parse('#abc') 返回 null；isSimilarTo 默认 threshold=16 为 true，threshold=5 为 false；
   - HSV/HSL 往返：#ff0000 对应 hue=0/sat=1/val=1 并回到 #ff0000；#00ff00 对应 HSL hue=120/sat=1/lightness=0.5 并回到 #00ff00；toColorInt(0xFFFFFFFF)=-1。
2. floating_window（高级 API，宿主能力内）通过：
   - createWindow({initialPosition,initialSize})、setText、show、getPosition、setPosition、setSize、isShowing、close 全部成功；
   - 旧 show/close/closeAll 保留；canDrawOverlays()=true；截图可见窗口（文本 TASK146 N24 FW，位置与尺寸生效）；
   - on('touch'|'drag'|'click') 与 enableDrag 在宿主无触摸事件通道时明确抛中文错误，不伪造成功。
3. accessibility（不回归 + 扩展面就绪）通过（服务未开启，真节点动作未在本轮驱动）：
   - accessibility.status() 返回 {enabled:false,ready:false}；旧 click/longClick/performGesture/takeScreenshot 等仍在；
   - 新增 accessibility.select/UiSelector、waitFor、ui_selector、ui_object 均存在；
   - 实际调用 select().text('...').findOnce() 在服务未开启时返回明确错误（无障碍服务未就绪，请在系统设置中开启 AI.js Pro 的无障碍服务后重试），不伪造成功；
   - K40 上曾临时启用无障碍服务做真节点验证，但系统设置被其它测试/系统重置（enabled_accessibility_services 回到 null），故真节点查询/点击的真机正向用例留待无障碍服务稳定开启的环境复测；选择器/node 逻辑已由本地 Node mock harness 覆盖。
4. 模块注册/合并：K40 用工程级 require 验证了 real prelude + ui.js + 模块顺序加载下的注册、覆盖与旧 API 保留；正式合并按 REGISTER-PATCH.md（推荐在 Node24Prelude.wrap 中 prelude/ui 之后追加 node24/modules/*.js，不改 prelude.js）。

## 本地契约验证（系统 Node）

- prelude.js + ui.js + 3 个模块顺序执行：注册表为 accessibility,color,floating_window,selector,ui,ui_object,ui_selector；__ajsBuiltinModules 与 $autojs.* 均被更新；旧 floating_window.show/closeAll 与 accessibility.status/click 保留；require('color') 等返回新模块。
- 18 项模块契约用例（color 解析/ARGB/相似度/HSV/HSL、floating 创建/位置/尺寸/关闭/触摸不支持、accessibility 选择器/waitFor/dump/错误语义）全部通过。

## 未覆盖 / 风险

- 无障碍真实节点查询/点击/输入：需稳定开启无障碍服务后复测；AutomationHostClient 反射绑定已在 K40 建立（logcat 可见 AutomationHostClient: AutomationHostService connected），服务未 ready 时选择器按设计报错。
- floating_window 的 on('touch'|'drag'|'click')、enableDrag、拖拽贴边/回弹、update 不重建：需要主进程 overlay 扩展（当前 kind:ui op:floatingWindow 只有 show/close/closeAll），建议独立任务。
- 宿主 overlay 目前只渲染文本；setViewFromXml 仅提取 text 属性，复杂布局/自定义 View 需要主进程 inflate + 触摸事件扩展。
- 合并后 color 覆盖模块层 require('color') 与 $autojs.color；prelude 内部 image 模块仍用内部旧 Color 类，返回值可读但 instanceof require('color').Color 不成立（兼容风险低）。

## 设备清理

已删除 /sdcard/脚本/node24-pro-modules* 与本轮 probe 文件；无障碍设置保持/恢复为 enabled_accessibility_services=null、accessibility_enabled=0。
