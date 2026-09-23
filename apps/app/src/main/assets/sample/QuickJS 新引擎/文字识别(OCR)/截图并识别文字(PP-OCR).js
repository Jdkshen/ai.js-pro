// @engine quickjs
// 截图并把文字识别出来（PP-OCRv6 tiny，ncnn 自研推理，模型随包）
//
// 用途：截图 → $ocr.detect() 拿到文字块（文本 / 置信度 / 位置），并与内置 ML Kit 后端
// 在**同一帧**上做速度与置信度对比。
// 前置：截图授权（第一次会弹系统框）；不需要装任何插件，模型（3.1MB）随包，不联网。
// 覆盖：$ocr.create({backend:'ppocr'}) / detect / release / isReleased / liveCount。
//
// 为什么用 ppocr 后端：同一张 1080×2400 屏幕上平均置信度 0.95+，而 ML Kit 只有 0.6 左右
// （乱码明显更少：ML Kit 会把「计算器」识别成「Q搜索应用」这类）；速度两者同档甚至更快
// （K40 实测：短标签图 168ms vs 360ms；长文本图 282ms vs 363ms）。
// ⚠️ libppocr.so 只随包 arm64-v8a；其它 ABI 上会明确报错，去掉 backend 用默认后端即可。
//
// 可调选项（都有实测出来的默认值，一般不用写）：
//   targetSize       检测长边，默认 512（调大更慢且不会更准）
//   parallelThreads  识别线程数，默认 4（实测 4 最优，6 反而更慢）
//   recMaxWidth      单行裁剪宽度上限，默认 640（降到 320 开始掉精度）
//   cpuPowerMode     0=不绑核（默认；绑大核簇实测无收益）
//   useFp16          false（实测开了慢 2 倍，别开）
//   minScore         最低置信度（默认 0.2；调高会丢小字，一般别动）
//   models           自带模型目录（以 / 开头的绝对路径才会被采用）。
//                    ⚠️ 目录里必须是那五个固定文件名：`PP_OCRv6_tiny_det.ncnn.param/.bin`、
//                      `PP_OCRv6_tiny_rec.ncnn.param/.bin`、`ppocr_keys_v6_tiny.txt`。
//                      换更大的模型（如 v6 small/medium）时，记得**把它自己的字典改名**成
//                      `ppocr_keys_v6_tiny.txt`（原生 v6 模式的字典名是写死的），否则索引对不上、全是乱码。
//                    ⚠️ 应用私有目录之外还要注意作用域存储：`/sdcard/xiangqi-p4/**` 这类
//                      shell push 进去的路径应用自己读不到，要放 `/sdcard/Android/data/<pkg>/files/` 下。
// detect 的 max 参数会在识别前截断候选框：只关心少数几行文字时传小值能再快 30% 以上。

const ocr = $ocr.create({ backend: "ppocr" });
console.log("PP-OCR 会话成员：" + Object.keys(ocr).join(",") + "｜当前未释放会话数 " + $ocr.liveCount());

if (!requestScreenCapture("portrait")) {
    ocr.release();
    throw new Error("没有拿到截图授权，脚本结束");
}

const frame = captureScreen({ mode: "fast", size: 720, fresh: true });
console.log("帧 " + frame.width + "×" + frame.height);

const started = performance.now();
const blocks = ocr.detect(frame, { max: 20 });
const cost = Math.round(performance.now() - started);
console.log("PP-OCR 识别到 " + blocks.length + " 块文字，耗时 " + cost + "ms");
for (let i = 0; i < Math.min(blocks.length, 8); i++) {
    const block = blocks[i];
    console.log("  [" + i + "] \"" + block.text + "\" 置信度 " +
        (Math.round(block.confidence * 100) / 100) +
        " 中心 (" + Math.round(block.bounds.centerX) + "," + Math.round(block.bounds.centerY) + ")");
}

ocr.release();

// 同一个帧再用内置 ML Kit 跑一遍，直观对比（两者返回结构完全一致）
const mlkit = $ocr.create();
const t2 = performance.now();
const mlBlocks = mlkit.detect(frame);
const mlCost = Math.round(performance.now() - t2);
let sum = 0;
for (let i = 0; i < blocks.length; i++) { sum += blocks[i].confidence; }
let mlSum = 0;
for (let i = 0; i < mlBlocks.length; i++) { mlSum += mlBlocks[i].confidence; }
console.log("对比：PP-OCR " + cost + "ms/" + blocks.length + "块/均分" +
    (blocks.length > 0 ? (Math.round(sum / blocks.length * 100) / 100) : "-") +
    "   ML Kit " + mlCost + "ms/" + mlBlocks.length + "块/均分" +
    (mlBlocks.length > 0 ? (Math.round(mlSum / mlBlocks.length * 100) / 100) : "-"));
mlkit.release();

// 按文字点击：找到「设置」就点它的中心（想真的点就把下面 click 那行放开）
const target = blocks.find(function (block) { return block.text.indexOf("设置") >= 0; });
if (target) {
    console.log("找到「设置」，中心 (" + Math.round(target.bounds.centerX) + "," +
        Math.round(target.bounds.centerY) + ")");
    // click(target.bounds.centerX, target.bounds.centerY);
} else {
    console.log("本帧没有「设置」");
}
console.log("会话已释放：" + ocr.isReleased() + "｜liveCount=" + $ocr.liveCount());
