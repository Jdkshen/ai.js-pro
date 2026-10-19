
`docs/guides/SIFT特征匹配示例.md` 里的 AAR 重打包命令会从官方 SDK 重新装入**未 strip** 的原生库；
下次重生成后需要重跑本文第 2 节的 strip + 校验，并更新 `docs/architecture/项目说明.md` 里的 AAR SHA-256。

## 7. 实际结果（dev-1083 → dev-1084，2026-10-18 21:39）

Lead 构建的 dev-1084 已包含本轮改动（strip 后的 AAR + `new_api_database.ser` 排除），实测：

| 指标 | dev-1083 | dev-1084 | 变化 |
|---|---|---|---|
| APK 大小 | 150,822,129 B | 138,249,143 B | **-12,572,986 B（-11.99 MB）** |
| lib 组（压缩后） | — | — | **-9,647,704 B** |
| `resources/new_api_database.ser` | 6,506,894 B（压缩 2,945,431） | 不存在 | **-2.95 MB** |

各 so（压缩后 / 未压缩）：

| so | 1083 压缩 | 1084 压缩 | 压缩差 | 1083 未压缩 | 1084 未压缩 |
|---|---|---|---|---|---|
| libopencv_dnn.so | 9,338,645 | 4,105,219 | **-5,233,426** | 36,796,432 | 12,482,296 |
| libopencv_imgcodecs.so | 3,730,946 | 1,138,529 | **-2,592,417** | 10,822,024 | 2,438,520 |
| libtbb.so | 1,127,743 | 230,370 | **-897,373** | 3,505,888 | 771,216 |
| libopencv_core.so | 2,638,727 | 2,303,502 | -335,225 | 7,455,456 | 5,863,160 |
| libopencv_imgproc.so | 5,084,811 | 4,824,963 | -259,848 | 9,619,208 | 8,141,152 |
| libc++_shared.so | 556,270 | 450,940 | -105,330 | 1,794,776 | 1,292,904 |
| libopencv_geometry.so | 745,524 | 647,604 | -97,920 | 1,765,416 | 1,295,088 |
| libopencv_flann.so | 423,673 | 348,791 | -74,882 | 1,196,112 | 832,968 |
| libopencv_features.so | 361,400 | 310,117 | -51,283 | 952,408 | 709,592 |

（其余 lib 无变化；libmlkit_google_ocr_pipeline / libflutter / libapp / libppocr 等本来就没有 .symtab。）

### K40 冒烟（dev-1084）

1. **YOLO DNN**（`YOLO单帧直连测试.js`）：OpenCV 5.0.0 加载成功；
   `QUICKJS_NATIVE_YOLO_OK {backend:"dnn", model:"内置 yolo26_160", version:"5.0.0"}`；
   `[YOLO] 测试完成 总耗时=0.2s errors=0`；10 轮 inference p50=8.934ms；
   脚本 `运行结束，用时7.382秒`。证据：`.artifacts/opencv-strip-smoke-1084/yolo-dnn-full-key-lines.txt`。
2. **OCR**（`截图并识别文字(PP-OCR).js`）：`PP-OCR models source=assets`；
   `PP-OCR 识别到 20 块文字，耗时 726ms`；`对比：PP-OCR 726ms/20块/均分0.97  ML Kit 469ms/19块/均分0.65`；
   `运行结束，用时8.292秒`。证据：`ocr-key-lines.txt`。
3. **打包**（原生 MiuixBuildActivity，项目 `/sdcard/脚本/00-codex-ui`）：
   `Using manual template /storage/emulated/0/template.apk` →
   `KeyStoreApkSigner: unsigned files=2091 ... out=.../Codex UI Test_v1.0.0.apk` →
   `v2 sign start len=77,940,606` → 产物 77,942,043 B，`aapt dump badging` 包名/版本正常，
   zip 内含 classes.dex / AndroidManifest / assets/project/project.json。
   证据：`build-logcat-full.txt`、`build-progress.png`、`packaged-Codex-UI-Test.apk`。

结论：strip 与 ser 排除均**未造成 YOLO DNN / OCR / 打包回归**。

### 维护工具（本轮新增，已入库）

- `tools/strip-opencv-aar.ps1`：AAR 重生成后跑一次（NDK llvm-strip + 重打包 + 对齐/符号校验）；
- `tools/verify-native-strip.ps1`：守卫脚本，检查 AAR/APK 内 .so 无 .symtab、APK 不含
  `resources/new_api_database.ser`；
- 构建产物对齐守卫仍用 `tools/verify-16kb-alignment.ps1`（strip 后 arm64 LOAD p_align 保持 0x4000）。

### 冒烟时设备侧遗留（可随时删）

- `/sdcard/template.apk`（79 MB，从仓库 plugins 模板 push，用于打包冒烟）；
- `/sdcard/脚本/00-codex-ui/build/Codex UI Test_v1.0.0.apk`（打包产物）。
