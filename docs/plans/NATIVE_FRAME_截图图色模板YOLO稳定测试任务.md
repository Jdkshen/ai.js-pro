# Native Frame 截图、图色、模板与 YOLO 稳定测试任务

> 创建日期：2026-09-02  
> 当前阶段：待实施  
> 主验收设备：小米 K40（ADB 序列号 `cccc62c7`）  
> 应用包名：`org.autojs.autojs`

## 1. 本阶段唯一目标

先把 QuickJS 新引擎的以下链路做到**可重复、可度量、可长时间稳定测试**：

1. 屏幕截图；
2. 找色与多点找色；
3. 找图与模板匹配；
4. OpenCV DNN YOLO；
5. 全分辨率与 `640/720p` 视觉加速模式的坐标映射；
6. Native Frame 创建、传递、复用和释放的完整生命周期。

本阶段不是追求某一张截图的最低数字，而是先保证：

- 结果正确；
- 接口不随机报错；
- 长时间循环不崩溃、不持续涨内存；
- 耗时统计可信，能区分首帧、稳定帧和模型推理；
- 同一案例重复运行能够得到接近的结果；
- 现有 Rhino 脚本不因本次改造退化。

预计开发与真机回归时间：**10～15 个工作日**。如果只修明显错误、不做生命周期和长稳测试，不能算完成本任务。

---

## 2. 范围锁定

### 2.1 本阶段必须处理

- `MediaProjection / ImageReader` 截图进入 Native Frame 的数据路径；
- `rowStride`、`pixelStride`、旋转方向和异常尺寸处理；
- `full`、`fast 720p`、`fast 640p` 三种截图模式；
- `fresh: true/false` 和 `timeout` 的明确行为；
- Native Frame 句柄、缓存池、释放和引擎退出清理；
- `images.pixel()`；
- `images.findColor()`；
- `images.findMultiColors()`；
- `images.findImage()`；
- `images.matchTemplate()`；
- OpenCV DNN YOLO 的加载、预处理、推理、后处理和关闭；
- 所有现有图色、模板和 YOLO 案例的统一耗时输出；
- 小米 K40 真机安装与回归报告。

### 2.2 本阶段明确不做

- 不新增 OCR；
- 不新增 NCNN、ONNX Runtime、Vulkan 或新的 FP16 后端；
- 不接入 V8；
- 不大改 ImGui 页面、文件管理或无障碍控件树；
- 不补齐与本任务无关的全部 Auto.js API；
- 不重写 Rhino 引擎；
- 不删除原有案例、模型或兼容接口；
- 不以更换算法、降低精度来冒充性能优化；
- 未确认模型输出格式前，不得随意增加或删除 NMS；
- 不允许只凭单次日志宣称“零拷贝”或“性能提升几倍”。

发现范围外问题时，只记录到报告的“后续事项”，不要顺手扩展本轮改造。

---

## 3. 当前代码基线

开始修改前必须先阅读并记录这些文件，不得绕开现有实现另起一套重复代码：

| 模块 | 当前主要文件 |
|---|---|
| Native Frame 存储 | `modules/autojs/src/main/cpp/native_frame_store.h`、`native_frame_store.cpp` |
| QuickJS JNI 注册 | `modules/autojs/src/main/cpp/quickjs_jni.cpp` |
| QuickJS Java 桥 | `modules/autojs/src/main/java/com/stardust/autojs/engine/QuickJsHostBridge.java` |
| Native 方法声明 | `modules/autojs/src/main/java/com/stardust/autojs/engine/QuickJsNativeBridge.java` |
| 截图来源 | `modules/autojs/src/main/java/com/stardust/autojs/core/image/capture/ScreenCapturer.java` |
| Rhino 图像 API | `modules/autojs/src/main/java/com/stardust/autojs/runtime/api/Images.java` |
| YOLO 对外 API | `modules/autojs/src/main/java/com/stardust/autojs/runtime/api/Yolo.java` |
| OpenCV YOLO | `modules/autojs/src/main/java/com/stardust/autojs/runtime/api/OpenCvYoloDetector.java` |
| YOLO JS 模块 | `modules/autojs/src/main/assets/modules/__yolo__.js` |
| QuickJS 图色案例 | `apps/app/src/main/assets/sample/QuickJS 新引擎/图色处理/` |
| QuickJS YOLO 案例 | `apps/app/src/main/assets/sample/QuickJS 新引擎/YOLO目标检测/opencv/` |

当前已有能力包括：

- Direct `ByteBuffer` 截图数据传给 JNI；
- Native Frame 的创建、复制、裁剪、缩放、灰度、旋转、阈值、模糊和释放；
- 找色、多点找色、找图、模板匹配；
- `frame.recycle()` 生命周期接口；
- `captureScreen({ mode, size, fresh, timeout })`；
- 快速帧保留原屏幕逻辑坐标；
- OpenCV DNN 加载 ONNX 模型并执行 YOLO；
- YOLO `preprocessMs`、`inferenceMs`、`totalMs` 基础统计。

因此本任务重点是**审计、收口、修正、统一测试和长稳验证**，不是把已有 API 再复制一遍。

---

## 4. 目标数据链路

```text
MediaProjection / ImageReader
        ↓
Image.Plane DirectByteBuffer
        ↓
NativeFrameStore
  ├─ 校验 width / height / rowStride / pixelStride
  ├─ 最多一次必要的格式转换或拷贝
  ├─ cv::Mat 所有权明确
  └─ 有上限的复用池
        ↓
共享 Native Frame
  ├─ findColor / findMultiColors
  ├─ findImage / matchTemplate
  └─ OpenCV DNN YOLO
        ↓
原屏幕逻辑坐标映射
        ↓
QuickJS 只接收小型结果对象
```

### 4.1 本项目“少拷贝”的准确含义

本阶段的可验收目标是：

- 不经过 Android `Bitmap`；
- 不生成整帧 Java `byte[]`；
- 不把像素数组复制到 JS；
- 同一帧进行图色、模板和 YOLO 时，不重复创建整帧副本；
- JNI 边界只传 Direct Buffer、句柄和少量参数；
- 如果原始 Plane 的布局不能被 OpenCV 安全长期持有，允许在入口做**一次**必要拷贝或颜色转换。

不要为了宣传“绝对零拷贝”，让 `cv::Mat` 引用已经关闭的 `Image.Plane` 内存。真零拷贝只有在格式、步长和生命周期都满足条件时才允许启用。

---

## 5. Native Frame 生命周期硬性规则

1. 每个句柄只能指向一份有效、所有权明确的帧数据。
2. `frame.recycle()` 必须幂等：重复调用不能崩溃，后续访问应返回清楚的“句柄已释放”错误。
3. 脚本正常结束、异常结束、强制停止和引擎销毁时，都必须回收该引擎仍持有的帧。
4. YOLO 检测期间必须保证帧仍然存活；`detect()` 返回后不得偷偷保存悬空指针。
5. 图像变换默认返回新句柄；禁止在没有明确文档时静默改写输入帧。
6. 复用池必须有数量和字节上限，不能无限保留不同尺寸的 `cv::Mat`。
7. 池中对象重新使用前必须重置尺寸、类型和有效区域，防止读到上一帧残留数据。
8. 捕获返回后如果不继续持有 Android `Image`，Native Frame 必须已经拥有可安全访问的数据。
9. 对非法宽高、异常 stride、非 Direct Buffer、无效句柄统一返回可定位错误，不能 native 崩溃。
10. 应记录调试统计：活动句柄数、池内帧数、池占用字节、创建次数、复用次数和拒绝次数。统计可只在调试构建开启，不强制成为长期公开 API。

---

## 6. 坐标与图像格式约定

### 6.1 两套尺寸必须分清

- `frame.width / frame.height`：脚本看到的原屏幕逻辑尺寸；
- `frame.pixelWidth / frame.pixelHeight`：Native 实际处理尺寸；
- `full` 模式通常两者一致；
- `fast` 模式 Native 尺寸缩小，但所有对外坐标仍映射到原屏幕逻辑坐标。

所有以下 API 的输入区域和输出点位统一使用原屏幕逻辑坐标：

- `pixel`；
- `findColor`；
- `findMultiColors`；
- `findImage`；
- `matchTemplate`；
- YOLO `region` 和检测框。

内部缩放只能影响计算尺寸，不能让脚本再手工换算坐标。

### 6.2 边界规则

- 区域必须裁剪到有效画面范围；
- 宽或高小于等于 0 时给出参数错误；
- 模板大于搜索区域时返回空结果，不崩溃；
- 映射后的点位和矩形必须限制在原屏幕范围内；
- 横竖屏切换后，下一帧必须携带新的尺寸和映射信息；
- 允许映射产生最多 1～2 个逻辑像素的舍入误差。

---

## 7. 实施阶段

### 阶段 0：建立可信基线（1 天）

- [ ] 不修改核心算法，先运行现有全部图色、模板和 YOLO 案例；
- [ ] 记录 APK 版本、Git 提交、手机型号、Android 版本、分辨率和温度；
- [ ] 分开记录首次授权、第一帧、预热帧和稳定帧；
- [ ] 图色类每项至少 100 次，YOLO 至少 30 次；
- [ ] 保存 p50、p95、最大值、错误次数和内存变化；
- [ ] 保存原始日志，后续结果必须与此基线比较。

### 阶段 1：截图入口与复用池（2～3 天）

- [ ] 审计 `captureScreenRaw → DirectByteBuffer → createNativeFrame` 的真实拷贝次数；
- [ ] 消除 Bitmap、Java 数组或重复整帧 JNI 拷贝；
- [ ] 正确处理 `rowStride != width * 4` 和非标准 `pixelStride`；
- [ ] 对 full、720p、640p 使用有上限的 Native Mat 复用池；
- [ ] 明确 `fresh: false` 返回最近有效帧；
- [ ] 明确 `fresh: true` 等待新帧，超时后是回退缓存帧还是抛错，并在日志中标记；
- [ ] 横竖屏切换、应用切后台再回来后不会使用旧尺寸缓存；
- [ ] 强制停止脚本后活动句柄归零。

### 阶段 2：图色与模板正确性、性能（3～4 天）

- [ ] `pixel` 覆盖四角、中心、边界外和快速帧映射；
- [ ] `findColor` 覆盖全屏、区域、不同阈值、命中和不命中；
- [ ] `findMultiColors` 覆盖正负偏移、区域边界、不同阈值和不命中；
- [ ] `findImage` 覆盖全图、区域、阈值、模板过大和快速帧；
- [ ] `matchTemplate` 覆盖多结果、排序、最大结果数和坐标映射；
- [ ] 避免循环中每次分配同尺寸临时 Mat；
- [ ] 可复用灰度图和结果缓冲，但不得跨帧读到旧数据；
- [ ] full 与 fast 模式对同一目标的结果误差满足验收标准；
- [ ] 与 Rhino 原有案例对照，确认公共行为未退化。

### 阶段 3：OpenCV DNN YOLO（3～4 天）

- [ ] 本阶段只使用当前 `opencv` 后端；
- [ ] 模型在 detector 生命周期内只加载一次；
- [ ] 模型预热与正式统计分离；
- [ ] Native Frame 直接进入 YOLO 预处理，不转 Bitmap 或 JS 像素数组；
- [ ] 修正 letterbox、区域裁剪和原屏坐标反算；
- [ ] 先检查 ONNX 图是否内置 NMS，再决定是否执行 Java/OpenCV NMS；
- [ ] 如果需要 NMS，必须按类别处理，避免不同类别互相抑制；
- [ ] 空检测、缺失模型、错误 input size 和 detector 已关闭时给出明确错误；
- [ ] `detector.close()` 幂等，引擎退出时兜底关闭；
- [ ] 连续检测 500～1000 帧无崩溃、无句柄泄漏、内存不单调增长。

### 阶段 4：案例统一、真机验收（2～3 天）

- [ ] 更新现有案例，不重复制造名称不同但功能相同的脚本；
- [ ] 增加图色模板长稳回归脚本；
- [ ] 增加 YOLO 长稳回归脚本；
- [ ] 所有耗时统一使用 `performance.now()`；
- [ ] 构建 APK 并安装到小米 K40；
- [ ] 逐项运行验收矩阵；
- [ ] 输出稳定测试报告、日志、APK 大小和 SHA256。

---

## 8. 统一调用样例

下面只表示本阶段统一用法，不要求为此新增另一套 API。

### 8.1 截图、找色和模板

```javascript
// @engine quickjs

if (!requestScreenCapture('portrait')) {
    throw new Error('用户取消了屏幕捕获授权');
}

let frame = null;
let template = null;
try {
    frame = captureScreen({
        mode: 'fast',
        size: 720,
        fresh: true,
        timeout: 50
    });

    const point = images.findColor(frame, '#2196F3', {
        region: [0, 0, frame.width, frame.height],
        threshold: 16
    });

    const multi = images.findMultiColors(frame, '#2196F3', [
        [12, 0, '#FFFFFF'],
        [0, 12, '#000000']
    ], { threshold: 16 });

    template = images.clip(frame, 100, 200, 80, 80);
    const matched = images.matchTemplate(frame, template, {
        threshold: 0.85,
        max: 5
    });

    console.log({ point: point, multi: multi, matched: matched });
} finally {
    if (template) template.recycle();
    if (frame) frame.recycle();
}
```

### 8.2 OpenCV YOLO

```javascript
// @engine quickjs

const backend = 'opencv';
const modelRoot = 'asset://sample/YOLO目标检测/OpenCV 5.0 DNN版本/models/';
const detector = yolo.load({
    backend: backend,
    model: modelRoot + 'yolo26_320.onnx',
    labels: modelRoot + 'labels.txt',
    inputSize: 320,
    threads: 4
});

let frame = null;
try {
    if (!requestScreenCapture('portrait')) {
        throw new Error('用户取消了屏幕捕获授权');
    }
    frame = captureScreen({ mode: 'fast', size: 720, fresh: true, timeout: 50 });
    const result = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
    console.log({
        count: result.length,
        preprocessMs: result.preprocessMs,
        inferenceMs: result.inferenceMs,
        totalMs: result.totalMs
    });
} finally {
    if (frame) frame.recycle();
    detector.close();
}
```

---

## 9. 耗时统计统一规范

所有案例必须遵守以下规则：

1. 使用 `performance.now()`，不能使用只能得到整数毫秒的计时方式；
2. 内部保存原始浮点数，展示至少 3 位小数；
3. 小于 `0.001 ms` 时显示 `<0.001 ms`，不能显示误导性的 `0 ms`；
4. 首次授权耗时、第一帧耗时、预热耗时不能混入稳定平均值；
5. 至少预热 5 次；YOLO 建议预热 3～5 次；
6. 不只打印平均值，必须打印 p50、p95、最大值和错误次数；
7. 截图、算法和端到端耗时分开打印；
8. YOLO 至少拆分为截图、预处理、推理、后处理和端到端；
9. 每条日志包含模式、Native 尺寸、逻辑尺寸、`fresh`、是否超时回退；
10. 测试期间记录设备温度或至少记录是否进入明显降频状态。

建议统一输出：

```text
[BENCH] name=findMultiColors mode=fast size=720 rounds=100
[BENCH] logical=1080x2400 native=324x720 fresh=false fallback=0 errors=0
[BENCH] p50=1.284ms p95=1.776ms max=2.145ms
```

YOLO 建议输出：

```text
[YOLO] capture=7.214ms preprocess=2.851ms inference=41.372ms postprocess=1.106ms total=52.543ms
```

---

## 10. 必须更新或新增的案例

### 10.1 复用现有图色案例

- `01-图色 API 自动回归.js`：作为正确性总入口；
- `02-截图找色与多点找色.js`：补充 p50/p95/max 和多点找色独立耗时；
- `04-模板匹配.js`：沿用原模板，不临时生成无法复现的随机模板；
- `05-全分辨率与视觉加速对比.js`：统一 full、720p、640p 统计；
- `06-快速帧模板坐标回归.js`：验证 fast 坐标反算；
- `07-截图首帧与稳定耗时测试.js`：分开首帧和稳定帧。

新增一个即可：

- `08-图色模板长时间稳定性测试.js`：循环 1000 次，混合截图、找色、多点找色、模板匹配和及时 recycle。

### 10.2 复用现有 YOLO 案例

- `YOLO运行环境测试.js`：只检查依赖、模型加载和一次空/正常输入；
- `YOLO单帧直连测试.js`：完整输出分阶段耗时；
- `YOLO区域检测.js`：验证区域与坐标映射；
- `YOLO实时直连测试.js`：验证实时刷新与主动停止；
- `YOLO持续识别.js`：增加 p50/p95、错误次数、回退帧数和周期内存记录。

如果现有持续识别案例已能承担 1000 帧测试，就直接增强它，不再创建同类脚本。

---

## 11. 真机验收矩阵

| 项目 | 必测场景 | 通过标准 |
|---|---|---|
| 截图授权 | 同意、取消、再次授权 | 不崩溃，错误信息明确 |
| 帧新鲜度 | `fresh:false`、`fresh:true`、超时 | 行为与日志一致，不无限等待 |
| 分辨率 | full、720p、640p | 尺寸正确，坐标可映射 |
| 屏幕方向 | 竖屏、横屏、旋转后首帧 | 不复用旧尺寸脏帧 |
| 生命周期 | 正常 recycle、重复 recycle、遗漏 recycle、强停脚本 | 无 native 崩溃，引擎退出后归零 |
| 找色 | 命中、不命中、区域边界、阈值 | 结果可重复，坐标正确 |
| 多点找色 | 正负偏移、越界点、阈值 | 无越界访问，结果正确 |
| 找图/模板 | 全图、区域、模板过大、多结果 | 返回规则一致，不崩溃 |
| YOLO 模型 | 正常、缺失、格式错误 | 正常可推理；错误可读 |
| YOLO 结果 | 无目标、多目标、区域目标 | 框和类别稳定，无跨类误抑制 |
| 前后台 | 切后台再返回、锁屏再解锁 | 能恢复或明确要求重新授权 |
| 长稳 | 图色模板 1000 轮、YOLO 500～1000 帧 | 无 ANR、崩溃、句柄泄漏 |

当前只强制在小米 K40 上完成验收。第二台手机可作为兼容性补测，但不能卡住本阶段交付。

---

## 12. 量化验收标准

### 12.1 正确性

- full 与 fast 对同一目标的坐标误差不超过 2 个逻辑像素；
- 同一静态画面重复 100 次，图色和模板结果不得随机漂移；
- YOLO 对同一输入帧的类别一致，置信度误差不超过 `±0.01`，检测框误差不超过 2 像素；
- 无效参数、无效句柄和缺失模型不能导致 native crash；
- Rhino 原有截图和 images 常用案例仍能运行。

### 12.2 稳定性

- 图色模板混合循环 1000 次无崩溃、无 ANR；
- YOLO 连续运行至少 30 分钟或 1000 帧，以先达到者为准；
- 预热后 Native 活动句柄数随 `recycle()` 回落；
- 复用池达到稳定上限后不能继续单调增长；
- 长稳测试结束并回收后，PSS 相对预热完成时增长建议不超过 30 MB；若超过，必须给出对象/内存归属证据。

### 12.3 性能与波动

- 不硬写“截图必须 7 ms”，不同手机和刷新率不能直接套同一数字；
- 预热后截图 p95 建议不超过中位数的 `1.8 倍`；
- 排除首次授权和方向切换后，最大值建议不超过中位数的 `3 倍`；
- 同一模式的 p95 相比修改前基线不得退化超过 15%，除非是为修正确性且报告中说明；
- fast 720p 和 640p 的图色、模板或 YOLO 端到端耗时应明显低于 full；若没有提升，必须继续检查隐藏拷贝、重复 resize 或错误的处理尺寸。

---

## 13. 构建、安装和日志要求

实施完成后至少执行：

1. Native C++ 编译；
2. Android App Debug APK 构建；
3. 安装到 ADB 设备 `cccc62c7`；
4. 清理旧日志后运行全部必测案例；
5. 检查 `FATAL EXCEPTION`、`SIGSEGV`、`ANR`、OpenCV 断言和 JNI 错误；
6. 记录 APK 路径、大小、SHA256 和安装时间；
7. 验证手机里的案例确实是本次构建版本，不能只改源码不更新 assets/APK。

如果手机处于锁屏、USB 调试未授权或安装被 MIUI 拦截，只能标记“设备阻塞”，不能伪造真机通过结果。

---

## 14. 最终交付物

- [ ] 本任务涉及的源码修改；
- [ ] 更新后的 QuickJS 图色、模板和 YOLO 案例；
- [ ] [Native Frame 稳定测试报告](../reports/NATIVE_FRAME_稳定测试报告.md)；
- [ ] 原始基准日志或 JSON/CSV；
- [ ] 小米 K40 安装与运行证据；
- [ ] APK 路径、大小和 SHA256；
- [ ] 已知问题与后续工作列表；
- [ ] 本文档中的完成项勾选，并在每项后附文件、日志或测试证据。

报告中必须把以下三类内容分开：

1. **已验证完成**：有代码位置和真机日志；
2. **只完成代码、未真机验证**：不能写成已完成；
3. **建议项**：本轮没有实现，不得混在完成列表里。

---

## 15. 完成定义（Definition of Done）

只有同时满足以下条件，才能宣布“截图、图色、模板、YOLO 已能稳定测试”：

- [ ] 截图入口真实拷贝路径已审计并记录；
- [ ] Native Frame 所有权、复用池和回收路径已验证；
- [ ] full、720p、640p 坐标映射通过；
- [ ] 找色、多点找色、找图、模板匹配自动回归通过；
- [ ] OpenCV YOLO 单帧、区域和持续识别通过；
- [ ] 所有案例耗时不再出现误导性的 `0 ms`；
- [ ] 图色模板 1000 轮长稳通过；
- [ ] YOLO 30 分钟或 1000 帧长稳通过；
- [ ] Rhino 常用图片 API 无明显回归；
- [ ] APK 已安装到小米 K40，并确认手机案例为最新版本；
- [ ] 无未解释的崩溃、ANR、句柄泄漏或持续内存增长；
- [ ] 测试报告包含修改前后 p50、p95、最大值和错误次数对比。

完成本阶段后，再单独安排 OCR、更多 QuickJS API、真正跨模块统一 Native Frame，以及是否引入 NCNN/ONNX Runtime 等下一阶段工作。
