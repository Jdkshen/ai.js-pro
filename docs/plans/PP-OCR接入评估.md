# PP-OCR 接入评估（方案 · 工作量 · 风险）

> ⚠️ **2026-09-18 起作废**：本地 PP-OCR 资产全是 **NCNN** 格式（`.param/.bin`），OpenCV DNN 吃不了，本文的 ONNX 路线不再采用。
> 现行方案见 [PP-OCRv6 (NCNN) 接入方案](PP-OCRv6_NCNN接入方案.md)。本文保留作历史参考。

> 状态：**已作废**（未开工）。本文只回答「能不能做、怎么做、多久、风险在哪、建议先做什么」。
> 相关：`docs/architecture/第一代API逐函数对照.md` §5.15（`$ocr` 契约）、`tools/pro-api/ocr-contract.js`（26/26 验收脚本）。

## 0. 一句话结论

- **技术可行**，而且**不用改引擎就能先拿到硬数据**：脚本侧可以直连包内的 `org.opencv.dnn.Dnn` 跑 ONNX。
- 最大风险**不是精度，是 CPU 推理速度**（OpenCV DNN 后端，不是 NCNN/MNN/Vulkan）。
- 建议：**先花 0.5 天做脚本侧原型出 A/B 数据 → 达标（准确率 ≥ ML Kit 且耗时 ≤ 1.5×）再进引擎（再 3–4 天）**。

## 1. 现状基线（ML Kit，真机实测）

K40（`cccc62c7`）同一张 1080×2400 图，`$ocr.detect()` 全屏 vs 裁剪：

| 喂图方式 | 耗时 | 平均置信度 | 识别块数 |
| --- | --- | --- | --- |
| 整屏 1080×2400 | 298 ms | 0.483 | 26 |
| **裁剪 800×160** | **88 ms** | **0.555** | — |
| 裁剪 ×2（放大 2 倍） | 64 ms | 0.525 | — |
| 整屏 ×0.5（缩小） | 192 ms | 0.502 | 23 |

结论：**裁剪既快又准；放大对本份图无益**。（历史数据：全屏 `OCR.detect` 169 ms，见 §5.15。）

ML Kit 的短板（也就是 PP-OCR 想解决的问题）：

- **不做旋转检测**：`rotation` / `rotationConfidence` 恒为 0，竖排/倾斜不给提示。
- 行粒度、`region` 硬钳 32×32（`MIN_REGION_SIZE`）。
- 小字 / 密集 / 艺术字有限（置信度只有 0.48 左右）。
- **与 Pro 不一致**：Pro 用的是 PaddleOCR 插件，我们当前是 ML Kit；`models` / `labelsFile` / `cpuPowerMode` / 线程数这些参数现在是「接受但忽略」。

## 2. 可选路线

| 路线 | 内容 | 成本 | 收益 |
| --- | --- | --- | --- |
| **A. 只调优 ML Kit 调用** | 裁剪 + 放大 + 二值化/对比度增强 + 按块重试 | ~0.5 天，**0 引擎改动** | 小（裁剪的收益已经吃掉了，见 §1） |
| **B. rec-only PP-OCR** ★ | 检测框交给我们已有能力（YOLO / 已有裁剪框），只把**认字**换成 PP-OCR rec | 原型 0.5 天；引擎化 3–4 天 | 中高：认字质量可控、可换模型、与 Pro 语义对齐 |
| **C. 完整 det+cls+rec** | 自带 DB 检测 + 方向分类 + 识别，完全脱离 ML Kit | 4–6 天 | 高但**速度风险最大**（三段模型串联） |
| **D. 通用 ONNX 推理 + 后端可切换** | 在 B 之上把 `$ocr.create({backend:'ppocr'\|'mlkit'})` 做成正式能力 | B 之上 +0.5–1 天 | 长期最好：模型可替换，`models/labelsFile/cpuPowerMode` 参数**才真正有意义** |

## 3. 已核实的技术事实（本次实测，不是推测）

1. **推理后端开箱可用**：`apps/app/src/main/java/.../OpenCvYoloDetector.java` 已经在用
   `org.opencv.dnn.Dnn.readNetFromONNX(path, Dnn.ENGINE_AUTO)` + `setPreferableBackend(DNN_BACKEND_OPENCV)`
   + `Dnn.blobFromImage` + `net.forward()` ⇒ native OpenCV 的 dnn 模块**已打进 APK**。
2. **脚本侧可直连**：QuickJS bootstrap 暴露了 `images.opencv`（`Mat/Core/Imgproc/CvType/Scalar/Size/Point/Rect` + `BitmapFactory`）、帧↔Mat 桥和 `requireJavaClass(全限定名)`
   ⇒ **原型可以 0 引擎改动、纯脚本完成**（有光/截图 → `Bitmap` → `Mat` → `Dnn`）。
3. **模型源可达（今天逐条测过）**：
   - `hf-mirror.com` → **200**，仓库 `SWHL/RapidOCR` 里 **PP-OCRv1–v4 的 det/rec/cls ONNX 都是现成的**，无需转换；
     已下载验证：`PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx` = **10,857,958 B**。
   - `paddleocr.bj.bcebos.com` → 200、`paddle-model-ecology.bj.bcebos.com` → 200（官方模型，但是 **Paddle 格式**，要 `paddle2onnx` 转换；清华 PyPI → 200，可装）。
   - `www.modelscope.cn` → 302（可跟）。
   - ⚠️ **GitHub HTTPS 不通**（`github.com` → 000）⇒ 拿不到 GitHub Release 里的模型资产，别按那个走。
4. **字典**：`ppocr_keys_v1.txt`（约 6600 字）可从 PaddleOCR 仓库经 jsDelivr 取（jsDelivr API 已验证 200）。
5. **契约不能破**：`tools/pro-api/ocr-contract.js` 真机 26/26，新后端必须返回**同结构** `{text, confidence, bounds, rotation, rotationConfidence}`。

## 4. 引擎化后的设计（照 `$ocr` 的三步走）

1. **Java**：新增 `PpOcrSession`（`Ocr` 同级）——持有 `Net` + 字典数组 + 预处理参数；
   `recognize(Bitmap)` → 预处理 → `net.setInput(blob)` → `net.forward()` → CTC 解码 → `{text, confidence, bounds}`。
2. **JNI**：`nativePpOcrCreate/Recognize/Release/LiveCount`（照 `nativeOcr*` 的写法 + `installNativeFunction`）。
3. **bootstrap**：`$ocr.ppocr.create({rec, dict, threads, cpuPowerMode})`（或统一成 `$ocr.create({backend:'ppocr', ...})`），
   会话对象字段与现有 `$ocr` 完全一致，脚本无需改。

关键实现细节：

- **预处理**：BGR→RGB；高固定 **48**，宽按比例且 `clamp(≤320)`（或 pad 到 320）；`(x/255-0.5)/0.5`；NCHW float32。
- **后处理**：CTC 贪心解码（去 blank、去连续重复）+ 字典索引映射；置信度 = 有效位概率均值（或最小值，需标定）。
- **不做**：beam search / 语言模型重排（收益小、成本高）。
- **缓存**：`Net` 只建一次（`readNetFromONNX` 是重活）；字典一次性解析成 `IntArray`/`String[]`。
- 资源：`ScriptRuntime.addCleanable()` 兜底释放（与 `Ocr` 相同做法）。

## 5. 工作量估算（人天）

| # | 阶段 | 内容 | 估时 |
| --- | --- | --- | --- |
| 0 | **脚本侧原型 + A/B** | 下模型 → push `/sdcard` → CTC 脚本 → 与 ML Kit 同图块对比 | **0.5** |
| 1 | 模型选型 | v4-mobile vs server vs（若有）v5：体积 / 精度 / 速度权衡 + 许可 | 0.5 |
| 2 | 引擎 API | `PpOcrSession` + JNI + bootstrap（**必须先跑 `build-quickjs.ps1`**） | 1–1.5 |
| 3 | 契约对齐 | 返回结构、`models/labelsFile/cpuPowerMode/线程数` 参数做实、错误码 | 0.5 |
| 4 | 真机标定 | 速度/精度矩阵、内存、多次调用稳定性、与 Rhino 侧同步 | 1 |
| 5 | 收尾 | 示例（QuickJS + Rhino）、文档、发布与回归 | 0.5 |
| | **合计** | | **4–5 天**（若阶段 0 数据到位，3.5–4.5 天可发布） |

其中阶段 0 是**决策闸门**：不达标就直接停，只损失半天。

## 6. 风险与缓解

| 风险 | 概率/影响 | 缓解 |
| --- | --- | --- |
| **速度不如 ML Kit**（OpenCV DNN CPU 逐框推理） | 中 / **高** | 用 mobile 模型、限制输入宽、批处理多框、`Net` 复用；不行再评估 NCNN/MNN（另 +2–3 天） |
| 算子不受支持（rec = CRNN+CTC 低风险；det = DB 中风险） | 低 / 中 | 原型阶段 1 小时内即可验证 `readNetFromONNX` + `forward` 是否成功 |
| APK 体积 +10–16 MB | 中 / 中 | 不内置，首次使用按需下载到 `/sdcard`；或只带 v4 rec mobile |
| 对倾斜/竖排/艺术字无实质改善 | 中 / 中 | 先测再定；ML Kit 保留为默认后端 |
| 多一个后端 = 维护面变大 | 低 / 低 | 后端可切换 + 契约脚本回归 |
| 上游许可 | 低 | RapidOCR 的 ONNX 是 PP-OCR 转换产物，PaddleOCR 为 Apache-2.0；发布前复核 |

## 7. 建议（按性价比排序）

1. **立即做阶段 0**（0.5 天，0 引擎改动）：拿 `ch_PP-OCRv4_rec_infer.onnx` + 字典，脚本侧跑通并出「同一批图块下 PP-OCR vs ML Kit」的速度/精度表。
2. 达标（建议口径：**准确率 ≥ ML Kit 且耗时 ≤ 1.5×**，或准确率明显更高且 ≤ 2×）→ 进阶段 1–5。
3. 不达标 → 只做路线 A（裁剪/放大/二值化，近乎零成本），并把 PP-OCR 记成「暂缓」。
4. 无论走哪条，**别动现有 `$ocr` 的返回结构**（26/26 契约要保持）。

## 8. 待澄清

- **「v6」指什么？** 目前能查到的公开 PP-OCR 版本是 **v1–v5**（v5 在官方 `paddle-model-ecology`，ONNX 侧见 `SWHL/RapidOCR` 只到 v4）。
  若你说的 v6 是某个模型名 / 插件 / 内部版本号，给我全名或模型文件，我按同一路线评估。
