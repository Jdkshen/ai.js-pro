# PP-OCRv6 (NCNN) 接入方案

> 状态：**R1 移植已完成并通过真机验收（2026-09-18）** —— `$ocr.create({backend:'ppocr'})` 在 Rhino/QuickJS 两侧都可用，ML Kit 仍是默认且契约 26/26 未回归。实施记录见 §8。
> 取代 `docs/plans/PP-OCR接入评估.md`（那份的 ONNX / OpenCV DNN 路线已作废 —— 本地资产全是 **NCNN** 格式，OpenCV DNN 吃不了 `.param/.bin`）。
> 相关：`docs/architecture/第一代API逐函数对照.md` §5.15（`$ocr` 契约）、`tools/pro-api/ocr-contract.js`（26/26 验收脚本）。

## 1. 一句话结论

**不用从零开发。** 本机已有一套「编译好、上过真机」的 PP-OCRv6 Android 桥（含 det/rec/CTC 全流水线源码），模型与 ncnn 运行时也全在本地。真正要做的是**把它搬进 `modules/engine` 并接上 `$ocr`**。

唯一硬问题是**速度**，但成因已定位且解法已知（现桥把 rec 硬编码成单线程）。

## 2. 本机已就绪的资产（2026-09-18 实测）

| 资产 | 位置 | 说明 |
| --- | --- | --- |
| **现成 Android 桥（已上真机）** | `E:\ppocrv\`（同源副本 `E:\auto_jspro_opencv\`） | `lib\arm64-v8a\libPPOCRBridge.so`（**26.8 MB，自包含**：`NEEDED` 只有 `liblog/libandroid/libjnigraphics/libz/libdl/libm/libc` ⇒ ncnn 与 OpenCV 都是静态链进去的）、`lib\PPOCRBridge.dex`、`src\native\ppocr\{PPOCRBridge.cpp,ppocr_engine.cpp/h}`、`src\js\{ppocr.js,ppocr_bridge.js}`、`docs\PPOCRBridge.md`、真机日志 `ppocr_{100,500}_logcat.txt` |
| **ncnn 源码 + Android cmake 安装树** | `E:\ppocrv_deps\external\`、`E:\auto_jspro_opencv_deps\external\` | `ncnn_source\`（含 ncnn 官方 `examples/ppocrv5.cpp`）、`ncnn-20260526-android-vulkan\<abi>\lib\cmake\ncnn`（arm64-v8a / armeabi-v7a / riscv64 / x86 / x86_64）、静态库 `libncnn.a`（10.2 MB，含 Vulkan/glslang） |
| **PP-OCR 模型全家桶（ncnn）** | `E:\auto_jspro_opencv_deps\external\PaddleOCR-ncnn-CPP-v0.3.0-archive\PaddleOCR-ncnn-CPP\models\` | det+rec：**v3 mobile / v4 mobile / v5 mobile / v5 server / v6 tiny / v6 small / v6 medium**；方向分类器 `PP_LCNet_x0_25_textline_ori`、`x1_0`；字典 `ppocr_keys_{v1,v5,v6,v6_tiny}.txt` |
| **同流水线的并行版参考实现** | `C:\Users\18101\Documents\IMgui_Opencv\Algorithm\WindowsPPOCREngine.cpp/h` | 关键：rec `opt.num_threads=1` + **4–8 个 worker 线程**，各自 `create_extractor()` —— 并行化的正确姿势 |
| 构建链（全本地） | NDK `C:\Android\ndk-r26d`、Ninja `C:\msys64\mingw64\bin\ninja.exe`、OpenCV Android SDK `D:\opencv_5_0_android\...` | 脚本 `E:\ppocrv\build_ppocr_so.ps1` 即用 |

**本仓库侧契合度**（已核实）：
- `modules/engine/src/main/cpp/CMakeLists.txt` 已链 `opencv_java5`(+imgproc/core/imgcodecs)，头文件树含 `opencv2/geometry/2d.hpp` ✔
- `ppocr_engine.cpp` 只 `#include` geometry、**没有实际调用**（上游注释：绕过了 unclip 后处理）⇒ **无依赖缺口**
- 模型体积：v6 tiny det+rec = **3.1 MB**（867 KB + 2.2 MB），字典 27 KB

## 3. 真机实测

### 3.1 阶段 0：K40 上的直接对比（2026-09-18，R2 复用现成 `.so`，脚本 `.artifacts/ppocrv-test/ppocrv-r2-test.js`）

设备：K40 `cccc62c7`（**Android 16**）；App `targetSdk=28` ⇒ 可从私有目录 dlopen（那一版 Android 10+ 限制不适用于我们）。
两张真实 1080×2400 截图，`minScore=0.2` / `maxItems=50` / `v6 tiny` / `targetSize=768`；ML Kit 为内置 `$ocr.detect()`。

| 图 | 引擎 | 总耗时 | 输出块数 | **平均置信度** |
| --- | --- | --- | --- | --- |
| `shot-szx.png` | **PP-OCRv6 tiny** | 316 ms | 20 | **0.944** |
| | ML Kit | 237 ms | 30 | 0.639 |
| `shot-now.png` | **PP-OCRv6 tiny** | 938 ms（det **109** + rec **815**） | 20 | **0.934** |
| | ML Kit | 287 ms | 25 | 0.608 |

**文字质量差异（同一张图，肉眼可辨）**：

| 真值 | PP-OCRv6 tiny | ML Kit |
| --- | --- | --- |
| 计算器 / 浏览器 / MT管理器 / 拨打电话 | **全对** | `Q搜索应用` / `KAE` / `Aljs Pro`（AI→Alj） |
| 象棋自动走棋 | 象棋自动走棋 | `象祺`自动走棋（错字） |
| RECOVERING:几何不可信：坚线… | RECOVERING:…（完整） | `ECOVERING`（丢首字母） |

判读：
- **精度：PP-OCRv6 tiny 完胜**（平均置信度 0.93–0.94 vs 0.61–0.64；乱码显著更少）。
- **速度：慢 1.3–3.3×**，且差异完全由 **rec** 决定 —— 短标签图（`shot-szx`）rec ≈10 ms/框，长文本图（`shot-now`）rec ≈**41 ms/框**；det 反而稳定便宜（~110 ms）。
- **rec 成本 ∝ 裁剪宽度**（高固定 48，宽随行长）⇒ 长行是代价来源，也是并行化收益最大的地方。

### 3.2 参照：现桥原环境日志（2026-06-30，569×365 / raw 14 框）

| 阶段 | 耗时 | 备注 |
| --- | --- | --- |
| det 检测 | **88 ms** | `targetSize=1280`，raw=14 |
| rec 识别（14 框） | **927 ms** | **单线程**，≈66 ms/框 |
| 端到端 | **≈1016 ms** | 输出 11 条可读文本 |

两台设备结论一致：**瓶颈 100% 在 rec 串行**。

### 3.3 并行化后（K40，2026-09-18，重编 `.so` + `.artifacts/ppocrv-test/ppocrv-r2-tune.js`）

改动两处：
1. `ppocr_engine.cpp::recognize()` 把**共享的成员 pool allocator 换成 `thread_local`** —— 这才是历史上「共享 rec 模型并发崩溃」的**真正根因**（`UnlockedPoolAllocator` 明确非线程安全，多 worker 共享必然踩内存）。
2. `PPOCRBridge.cpp` 把串行 loop 换成 N 个 worker（共享 rec `Net`，各自 `create_extractor()`；`rec.opt.num_threads` 本来就是 1）。

| 配置 | `shot-szx` | `shot-now` | 说明 |
| --- | --- | --- | --- |
| `cpuPower=0` `recThreads=1` | 311 ms | 928 ms | 串行基线 |
| `cpuPower=0` `recThreads=2` | 225 ms | 595 ms | 1.56× |
| **`cpuPower=0` `recThreads=4`** | **207 ms** | **432 ms** | **2.15×，最优** |
| `cpuPower=2` `recThreads=2` | 297 ms | 936 ms | ⚠️ 负优化 |
| `cpuPower=2` `recThreads=4` | 298 ms | 952 ms | ⚠️ 负优化 |

- **精度全程不变**（conf 0.944 / 0.934，逐行文字相同）⇒ 并行对结果**无影响**。
- ⚠️ **`cpuPower=2` 是坑**：`buildCpuSetForPower(2)` 取的是「最大频率」的所有核，而这台机器只有 **cpu7** 是 prime（日志 `affinity applied power=2 cpus=7`）⇒ 4 个 worker 被压到**单核**，比串行还慢。要用亲和性必须改成「绑大核簇」（按频点阈值），不能用「等于最大频」。
- 加速比 2.15× 而非 4×：受**长框尾延迟**限制（20 框里最长的那个决定下限）。
- **最终对比 ML Kit**：短标签图 **207 vs 232 ms（PP-OCR 更快）**；长文本图 **432 vs 297 ms（慢 1.45×）**，但置信度 **0.934 vs 0.608**。
- 体积：`llvm-strip --strip-unneeded` 后 **26.9 MB → 18.1 MB**（随包前先 strip）。
- ⚠️ 验证法提醒：`RunIntentActivity` 是**异步**的，pull 结果文件前要确认脚本已结束，否则会把「跑得慢」误判成「跑失败」。

**瓶颈 100% 在 rec 串行**：`PPOCRBridge.cpp:537` 写着
`actualRecThreads = 1; // 第二步：逐条识别。这里故意串行，避免共享 rec 模型并发崩溃。`
而 `kMaxSafeRecThreads = 4` 就在上面 100 行 —— 说明多线程被**主动关掉了**（当时并发崩过）。

⇒ 4 路并行后（Windows 版已验证）预计 rec 降到 ≈230–250 ms，端到端 **≈350–400 ms**，与 ML Kit 同档；同时**模型可换更准的 v5 server / v6 medium**。

## 4. 路线对比

| 路线 | 内容 | 成本 | 结论 |
| --- | --- | --- | --- |
| **R1（推荐）移植进引擎** | `ppocr_engine.cpp`(+JNI) 搬进 `modules/engine/src/main/cpp/`，ncnn 用本地静态库或 CPU-only 重建；模型随包；`$ocr` 增 `backend:'ppocr'` | **2–3 天** | 无 dex/反射、共用 APK 内 OpenCV、体积可控（+6–10 MB） |
| R2 复用预编译 `.so` | `libPPOCRBridge.so` + `PPOCRBridge.dex` 走 `DexClassLoader`（照原 `ppocr_bridge.js`） | 0.5–1 天 | 最快，但 **+27 MB**，且 Android 10+ 对「从可写目录 dlopen」有限制（原宿主 targetSdk 低才可行）⇒ 只适合**验证**，不适合出包 |
| R3 只借 rec | 检测框用已有 YOLO/裁剪，只调 rec 模型 | 1.5–2 天 | 少一个能力；det 已被证明只要 88 ms，收益有限 |
| R4 不接，只调优 ML Kit | 裁剪 + 放大 + 二值化 | ~0.5 天 | 兜底（裁剪的收益已吃掉，见旧文档 §1） |

## 5. 工作量（R1，人天）

| # | 阶段 | 内容 | 估时 |
| --- | --- | --- | --- |
| 0 | **K40 实测现桥** | 把 `libPPOCRBridge.so` + 模型推到设备，跑一遍，确认**我们这台机器**的 det/rec 数字（决策闸门） | **0.5** |
| 1 | ncnn 进构建 | 选定：① 直接用本地 `libncnn.a`（+10 MB）或 ② CPU-only 从 `ncnn_source` 重建（≈+2–3 MB）。放进 `third-party/`，`find_package(ncnn)` 接进 `modules/engine/src/main/cpp/CMakeLists.txt` | 0.5–1 |
| 2 | 引擎移植 | `ppocr_engine.cpp/h` + JNI 落地；**必须先跑 `build-quickjs.ps1`** 类同的原生刷新流程 | 1 |
| 3 | **rec 并行化** | 改成 N 个独立 `Net`（或每 worker 独立 extractor+allocator），复现并修掉当年那个并发崩溃 | 0.5–1 |
| 4 | API 接入 | `$ocr.create({backend:'ppocr', version:'v6', type:'tiny', ...})`；返回结构**与现契约完全一致**（`text/confidence/bounds/rotation/rotationConfidence`），26/26 不许破 | 0.5 |
| 5 | 模型与资产 | 选型（v6 tiny 默认 / v5 mobile / v5 server 可选）、字典、随包 + 校验任务（照 `verifyBundledYoloSamples` 的样子） | 0.5 |
| 6 | 真机验收 | K40 + Mi8 精度/速度矩阵、内存、长跑、与 ML Kit A/B；示例 + 文档 + 发布 | 1 |
| | **合计** | | **4–5.5 天**（阶段 0 不达标就停，只损失 0.5 天） |

## 6. 风险

| 风险 | 概率/影响 | 缓解 |
| --- | --- | --- |
| **rec 并行崩溃复发** | 中 / 高 | 阶段 3 单独做；先 2 路再 4 路；每 worker 独立 allocator（`Extractor::set_blob_allocator/set_workspace_allocator`）；不行就退回串行（性能退回 1.0 s，等于不可用） |
| ncnn 体积 | 中 / 中 | 用 CPU-only、`-Os`、`--gc-sections` 重建；不带 Vulkan/glslang（10.2 MB → ≈2–3 MB） |
| 与 `$ocr` 契约冲突 | 低 / 高 | 返回结构不动，只加字段；`tools/pro-api/ocr-contract.js` 每次改动后跑 |
| 精度不如预期（截图小字/艺术字） | 中 / 中 | 阶段 0 就用**同一批真机截图**对比 ML Kit；模型可换 v5 server / v6 medium |
| 首次加载耗时（ncnn 读模型） | 低 / 低 | `Net` 只建一次 + 复用（现桥已如此）；必要时懒加载 |
| 许可 | 低 | ncnn 示例 BSD-3、PaddleOCR Apache-2.0 ⇒ 更新 `THIRD_PARTY_NOTICES` |

## 7. 建议

1. ~~先做阶段 0（K40 实测现桥）~~ —— **已完成**（§3.1）：精度大幅胜出，速度慢 1.3–3.3×，瓶颈定位在 rec 串行。
2. 达标口径（用 §3.1 数据推演）：**rec 并行到 4 路后** `shot-now` 预计 ≈109+205 ≈ **315 ms**、`shot-szx` ≈ **160 ms** ⇒ 与 ML Kit 同档但更准 ⇒ **建议进 R1**。
3. **v5 还是 v6**：本地两代都全（v5 mobile/server、v6 tiny/small/medium）。现桥默认 **v6 tiny**（3.1 MB、最快）。建议**默认 v6 tiny、参数里可切 v5**（`version`/`type`），先各测一组再定默认值。
4. `$ocr` 现有契约（26/26）不许破；`models`/`labelsFile`/`cpuPowerMode` 这些「以前接受了但没实现」的参数正好在这次**做实**。

## 8. R1 实施记录（2026-09-18，已完成）

### 8.1 改动清单

| 层 | 文件 | 说明 |
| --- | --- | --- |
| 依赖 | `modules/engine/src/main/cpp/third_party/ncnn/arm64-v8a/{include/ncnn,lib/libncnn.a}` | vendor 自本机 `ncnn-install-autojs-noomp`（**Vulkan=OFF / OpenMP=OFF**，NDK r27c 构建）；`libncnn.a` strip 后 **5.5 MB**，走已有的 `*.a filter=lfs` |
| 原生 | `modules/engine/src/main/cpp/ppocr/{ppocr_engine.{h,cpp},ppocr_jni.cpp,ppocrv5_dict.h}` | 移植自 `E:\ppocrv`（源出 ncnn 官方 `examples/ppocrv5.cpp`，BSD-3）；已含 `thread_local` 内存池修复；JNI 符号改名到 `com.stardust.autojs.runtime.api.PPOcrBridge`；JSON 补 `w/h`；CPU 亲和性改为「大核簇」并禁止 <2 核集合 |
| 构建 | `modules/engine/src/main/cpp/CMakeLists.txt` | 新增 `ppocr` 目标（**仅 arm64-v8a**），链 vendored ncnn 静态库 + 打包内**共享** OpenCV，另加 `opencv_geometry`（OpenCV 5 把 `boundingRect`/`minAreaRect`/`getAffineTransform` 挪到这个模块）⇒ `libppocr.so` 仅 **4.1 MB** |
| 构建 | `modules/engine/src/main/cpp/build-quickjs.ps1` | arm64 额外产出并拷 `libppocr.so`；`apps/app/build.gradle` 的 `quickJsNativeSources`/`quickJsNativeLibraries` 同步（含新鲜度守卫） |
| Java | `runtime/api/{PPOcrBridge,PPOcr,PPOcrResult,OcrSession}.java` | 会话门面与 `Ocr` **同形**（`detect/release/isReleased`）；首次使用把 `assets/models/ppocrv6/*` 释放到 `filesDir`；引擎单例 + 会话引用计数 |
| 资源 | `apps/app/src/main/assets/models/ppocrv6/*` | v6 tiny det+rec + 字典，共 **3.1 MB** |
| 接线 | `assets/modules/__ocr__.js`（Rhino）、`quickjs_jni.cpp` 的 `$ocr` bootstrap + `QuickJsHostBridge`（QuickJS） | 按 `options.backend:'ppocr'` 分流；`liveCount()` 改为两后端求和 |

### 8.2 真机验收（K40 / Android 16，dev 构建直装）

| 图 | 后端 | 耗时 | 块数 | 平均置信度 |
| --- | --- | --- | --- | --- |
| `shot-szx`（1080×2400） | ML Kit | 429 ms | 30 | 0.639 |
| | **PP-OCR** | **251 ms** | 20 | **0.944** |
| `shot-now`（1080×2400） | ML Kit | 326 ms | 25 | 0.608 |
| | **PP-OCR** | 478 ms | 20 | **0.934** |

- **QuickJS 侧**（`// @engine quickjs`）：mlkit 268ms/0.639、**ppocr 230ms/0.944** ✔
- `$ocr.liveCount()` 会话内为 1、释放后归零 ✔
- **契约回归**：`tools/pro-api/ocr-contract.js` → **`OCR_SUMMARY|pass=26 failed=0`**（ML Kit 默认路径零回归）✔

### 8.3 已知限制

- `libppocr.so` **只随包 arm64-v8a**；其它 ABI 上 `PPOcr.isAvailable()` 为 false，`detect()` 会明确报错并提示用默认后端。
- 原生引擎是**进程内单例**：多个 ppocr 会话共用一份模型；`release()` 在最后一个会话时真正卸载。
- `rotation` / `rotationConfidence` 恒为 0（不做旋转检测，与 ML Kit 后端一致）。
- `labelsFile` 目前不生效（原生侧字典名固定为 `ppocr_keys_v6_tiny.txt`，必须与 rec param 同目录）；`models` 可指向自带模型目录。
- 体积：APK 净增 ≈ 7 MB（`libppocr.so` 4.1 MB + 模型 3.1 MB）。
- 未做：示例脚本、`$ocr` 文档表格更新、发布 dev 包。

### 8.4 速度优化（2026-09-18 实测，全部在真机完成）

从移植初版（串行 + 768）到当前默认配置，同一张 1080×2400 截图：**928 ms → 349 ms**，短标签图 **311 ms → 139 ms**。

| 手段 | 做法 | 效果 |
| --- | --- | --- |
| **rec 并行** | `thread_local` 内存池 + 4 个 worker | shot-now 928 → 432 ms |
| **默认 `targetSize` 768 → 512** | 已改进默认值 | 207→139 / 425→349 ms，**准确率还升了**（0.944 → 0.978）|
| **`max` 截断候选框** | 只需几行文字时传 `max:8` | 长文本屏 350 → **233 ms**（-33%），conf 仍 0.955 |
| 线程数 6 | 实测**更慢**（379 ms） | 上限固定为 **4**（写在 `kMaxSafeRecThreads` 注释里）|
| 大核簇亲和（`cpuPowerMode:2`）| 绑定最高频 70% 以上的核 | **无收益**（205 vs 207），默认 0 |
| ROI 裁剪（`region`）| 只识别指定区域 | **不是通用提速**：乱裁更慢更差（0.829）。需能干净剔除非目标文字且不切断行，按用例实测 |

对照 ML Kit（同一台机器、同样两张图）：

| 场景 | PP-OCR（默认 512/4 路） | ML Kit |
| --- | --- | --- |
| 短标签图 | **139 ms / 0.978** | 182–190 ms / 0.639 |
| 长文本图 | 349 ms / 0.953 | 213–240 ms / 0.608 |

⇒ 短标签图**更快且准得多**；长文本图慢约 1.5×，但置信度 0.95 vs 0.61。

**三项进一步优化的实测结果**（2026-09-18 真机完成）：

| 手段 | 实现 | 效果 |
| --- | --- | --- |
| **① det/rec 调度（LPT）** | rec 队列按裁剪宽度降序排（长框先跑），压掉尾延迟 | 长文本屏 349 → **329 ms**（-6%）|
| **② rec 裁剪宽度上限 1024 → 640（已设为默认）** | 引擎 `get_rotate_crop_image` 里硬编码的 1024 改为可配 | 329 → **270 ms**（-18%），置信度只掉 0.003（0.953→0.950）；cap320 就开始掉精度（0.922）|
| **③ fp16** | ncnn `use_fp16_packed/storage`（仓库 vendor 的 ncnn 确认 `NCNN_ARM82=ON`） | ❌ **负优化**：2.2–2.4× 变慢（329→786 ms），保持默认关闭 |

最终默认配置（targetSize 512 / LPT / recMaxWidth 640 / 4 worker）实测：

| 场景 | PP-OCR | ML Kit |
| --- | --- | --- |
| 短标签图 | **168 ms / 0.978** | 360 ms / 0.639 |
| 长文本图 | **282 ms / 0.950** | 363 ms / 0.608 |

⇒ **两张图上 PP-OCR 都比 ML Kit 更快**，置信度还高一大截；相对移植初版（928 ms）累计 **3.3×**。

脚本可用的旋钮（都在 `$ocr.create({backend:'ppocr', ...})` 里）：`targetSize`(512)、`parallelThreads`/`threads`(4)、`recMaxWidth`(640)、`cpuPowerMode`(0)、`useFp16`(false，别开)、`minScore`、`models`，以及 `detect` 的 `max`（只需几行文字时传小值能再快 30%+）。
