// task-131：OCR 插件脚本入口（契约路径：assets/scripts/index.js）。
// 宿主加载插件时会把本目录拷到 cache/plugin-scripts/<包名>/，插件侧脚本在这里 require。
// W1 的 $ocr 分发落地前，这里只暴露「未就绪」事实；不要伪造 $ocr（会让脚本以为 OCR 可用）。
module.exports = function (runtime, global) {
    // 说明：真正的 $ocr 实现随插件 lib/模型一起分发，由宿主侧的插件加载钩子挂到 global.$ocr。
    // 若走到这里说明宿主还没接分发 —— 保持 undefined，让脚本拿到宿主的点名错误。
    return {};
};
