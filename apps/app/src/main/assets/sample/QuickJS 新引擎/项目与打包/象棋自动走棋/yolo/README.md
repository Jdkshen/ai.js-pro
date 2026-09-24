# yolo/ — YOLO 主识别资产（来自第三方「象棋自动走棋」包）

这套资产让「YOLO 识别」在本工程里可离线重建、可追溯。**脚本侧只有两个入口**：

- `../src/vision/yolo-recognizer.js` — 引擎封装（`create({baseDir})` → `read(image, conf, iou)`）
- `../src/services/board-service.js` 的 `recognizeYolo()` — 多帧投票 + 规则门 + 未知格闸门

## 资产清单

| 路径 | 大小 | 说明 |
|---|---|---|
| `model/yolov5n_xq_fp16.tflite` | 3.8 MB | YOLOv5n，640×640，fp16；15 类 = 红/黑各 7 子 + 棋盘框 |
| `lib/arm64-v8a/libtensorflowlite_jni.so` | 2.9 MB | TFLite C/JNI（与 Java 侧 `org.tensorflow.lite.*` 最小实现配套） |
| `dex/tflite_classes_fen_v2.dex` | 40 KB | `org.tensorflow.lite.*`（最小实现）+ `com.xq.{YoloDecode,ChessFen,TflRunner}` |
| `dex-src/` | 81 KB | 上面 dex 的全部源码（12 个 .java，含我们重写的 `TflRunner`） |

模型与 .so 原封不动取自第三方包；dex 是**用他们反编译重建的 Java 源码 + 我们重写的
`TflRunner` 重新 d8 出来的**（原因见下）。

## 为什么整条流水线在 Java 侧（`TflRunner`）

QuickJS 桥做不到直接跑 TFLite：`Array.newInstance(Float.TYPE, n)` 不支持（拿不到可写 float[]），
`Class.getConstructor(Class...)` 传 JS 数组也不支持（构造不出 `Interpreter`）。
所以预处理（letterbox）→ 推理 → 解码（YoloDecode）→ FEN（ChessFen）全收在 `TflRunner`，
脚本只递交一个 OpenCV `Mat`、拿回 JSON。

源码里几个已实测确认的约定（改之前先读）：

- `TflRunner.recognizeFenJson(mat, conf, iou)` 的输入字节序是 **RGBA**（本仓库 `images.toMat(frame)` 实测即 RGBA；
  BGRA 会把红黑认反）；
- `ChessFen` 的行序：**row0 = 屏幕最上面那行**（`flipped` 字段表示是否上下颠倒）；
- 初始化：`new TflRunner()` → `init(modelPath, nativeLibDir, threads)`；线程数**4** 最快（实测 ~170ms/帧）。

## 重建 dex

```powershell
# 仓库根先切 JDK17：. .\tools\jdk17.ps1
powershell -File yolo\build-dex.ps1
```

脚本会自己从 `modules/engine/libs/opencv-5.0.0-16kb-page-fix.aar` 里取 `classes.jar`（javac 需要 `org.opencv.core.Mat`），
否则传 `-OpenCvAar`。默认 d8 用 `C:\Android\build-tools\34.0.0\d8.bat`、android.jar 用 `android-28`。

> ⚠️ `build-dex.ps1` 现在产出的 dex 与库里这份**不完全逐字节一致**（当时的 d8 版本/参数不同，大小 40968 vs 40068）。
> 库里这份是**真机实测通过**的；重编后要在真机上过一遍 `tools/yolo-smoke.js` 再替换。

## 真机自查

```
adb shell am force-stop com.jdkshen.aijspro      # 必须先杀：RunIntentActivity 单实例
adb shell am start -a android.intent.action.VIEW \
  -d file:///sdcard/xiangqi-p4/proj/tools/yolo-smoke.js -t text/plain \
  -n com.jdkshen.aijspro/.external.open.RunIntentActivity
adb logcat -d -s GlobalConsole:V | Select-String YOLOSMOKE
```

三个真机踩过的点（2026-09-24）：

1. `DexClassLoader` 的 parent 必须是 `context.getClassLoader()`（app 的 PathClassLoader）——
   系统加载器解析不到 `org.opencv.core.Mat`（NoClassDefFoundError）；
2. `.so` 要复制到私有目录的**独立子目录**再用绝对路径 `System.load`（同一个路径不能被两个 ClassLoader 各加载一次）；
3. 多个 `TflRunner` 实例必须共用同一个 `DexClassLoader`（跨加载器的接收者检查会失败：
   `Expected receiver of type com.xq.TflRunner, but got com.xq.TflRunner`）。
