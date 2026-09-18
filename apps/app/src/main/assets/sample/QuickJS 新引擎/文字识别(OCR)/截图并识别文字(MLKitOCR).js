// @engine quickjs
// 截图并把文字识别出来（ML Kit 中文，离线）
//
// 用途：截图 → $ocr.detect() 拿到文字块（文本 / 置信度 / 位置），并演示按文字中心点击。
// 前置：截图授权（第一次会弹系统框；不需要装任何插件，模型内置）。
// 覆盖：$ocr.create / detect / release / isReleased / liveCount；QuickJS 的帧用属性 width/height。
//
// 与 Auto.js Pro 的差异：Pro 是 `$plugins.load('org.autojs.autojspro.plugin.mlkit.ocr')`
// 再 `new MLKitOCR()` —— 需要从插件商店装插件。本引擎把 ML Kit 中文识别**内置**了，
// 直接用 $ocr 即可（不需要、也不支持那个插件）。
// 注意：Pro 文档里的 PaddleOCR 专用选项（models / cpuPowerMode / parallelThreads 等）
// 会被接受但不改变识别引擎 —— 这里的后端就是 ML Kit。

const ocr = $ocr.create({ models: "slim", cpuPowerMode: "LITE_POWER_NO_BIND", parallelThreads: 4 });
console.log("OCR 会话成员：" + Object.keys(ocr).join(",") + "｜当前未释放会话数 " + $ocr.liveCount());

if (!requestScreenCapture("portrait")) {
    ocr.release();
    throw new Error("没有拿到截图授权，脚本结束");
}

for (let round = 1; round <= 3; round++) {
    const frame = captureScreen({ mode: "fast", size: 720, fresh: true });
    console.log("第 " + round + " 轮：帧 " + frame.width + "×" + frame.height);

    const started = performance.now();
    const blocks = ocr.detect(frame);
    const cost = Math.round(performance.now() - started);
    console.log("  识别到 " + blocks.length + " 块文字，耗时 " + cost + "ms");

    for (let i = 0; i < Math.min(blocks.length, 8); i++) {
        const block = blocks[i];
        console.log("  [" + i + "] \"" + block.text + "\" 置信度 " +
            (Math.round(block.confidence * 100) / 100) +
            " 中心 (" + Math.round(block.bounds.centerX) + "," + Math.round(block.bounds.centerY) + ")");
    }

    // 按文字点击：找到「设置」就点它的中心（想真的点就把下面 click 那行放开）
    const target = blocks.find(function (block) { return block.text.indexOf("设置") >= 0; });
    if (target) {
        console.log("  找到「设置」：中心 (" + Math.round(target.bounds.centerX) + "," +
            Math.round(target.bounds.centerY) + ")");
        // click(target.bounds.centerX, target.bounds.centerY);
    }

    frame.recycle();
    sleep(1500);
}

ocr.release();
console.log("已释放，未释放会话数 " + $ocr.liveCount());
