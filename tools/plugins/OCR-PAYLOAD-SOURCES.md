# OCR 插件载荷来源（task-131）

由 tools/plugins/sync-ocr-plugin-payload.mjs 生成；复核命令：node tools/plugins/sync-ocr-plugin-payload.mjs --check

| 目标 | 来源 | 字节 | sha256(前16) | ABI |
| --- | --- | --- | --- | --- |
| jniLibs/arm64-v8a/libppocr.so | modules/engine/src/main/jniLibs/arm64-v8a/libppocr.so | 3959048 | f49908886443a140 | 0xb7 (AArch64) |
| jniLibs/arm64-v8a/libc++_shared.so | modules/engine/libs/opencv-5.0.0-16kb-page-fix.aar!/jni/arm64-v8a/libc++_shared.so | 1292904 | 4397241b4bd20a8e | 0xb7 (AArch64) |
| assets/ocr/models/PP_OCRv6_tiny_det.ncnn.param | apps/app/src/main/assets/models/ppocrv6/PP_OCRv6_tiny_det.ncnn.param | 19306 | 1e4d9fcbdddaf0ef | - |
| assets/ocr/models/PP_OCRv6_tiny_det.ncnn.bin | apps/app/src/main/assets/models/ppocrv6/PP_OCRv6_tiny_det.ncnn.bin | 4929604 | 7ec068ccdcb60a99 | - |
| assets/ocr/models/PP_OCRv6_tiny_rec.ncnn.param | apps/app/src/main/assets/models/ppocrv6/PP_OCRv6_tiny_rec.ncnn.param | 13853 | 1fecb3c24adb55ec | - |
| assets/ocr/models/PP_OCRv6_tiny_rec.ncnn.bin | apps/app/src/main/assets/models/ppocrv6/PP_OCRv6_tiny_rec.ncnn.bin | 10562152 | a674dd8d3bebeac8 | - |
| assets/ocr/models/ppocr_keys_v6_tiny.txt | apps/app/src/main/assets/models/ppocrv6/ppocr_keys_v6_tiny.txt | 74949 | e07ed91571983f5e | - |

## 警告

- libppocr.so 的 NEEDED 里有插件未带的库（应由宿主提供）：libopencv_java5.so, libopencv_imgproc.so, libopencv_core.so, libopencv_imgcodecs.so, libopencv_features.so, libopencv_flann.so, libopencv_geometry.so
