# 圆点接力跳动 Loading 修正版

运行环境：AI.js Pro dev-1200 或更新版本，Rhino UI 引擎。
解压保留整个 DotJumpLoading 文件夹，从工程入口或 main.js 运行。
main.js 与 widgets/DotJumpLoading.js 必须保持相对目录关系。

宿主修复：ui.Widget.render() 支持返回原生 Android View，按顺序绑定组件方法、自定义属性和生命周期。
原始 ZIP 不变。此副本另修正：自动测量尺寸避免裁切圆点、固定高度下限制跳跃范围、轮换包含最后一个圆点；回到前台时按用户暂停状态恢复。

这是 Rhino 的 JavaAdapter 自绘组件，不是 Node.js/npm 项目。
