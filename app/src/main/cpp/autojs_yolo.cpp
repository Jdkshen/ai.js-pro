#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <net.h>
#include <cpu.h>
#include <platform.h>
#include <onnxruntime_cxx_api.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr const char* kLogTag = "AutoJsYolo";

struct Detection {
    float x1;
    float y1;
    float x2;
    float y2;
    float score;
    int label;
};

struct Detector {
    ncnn::Net net;
    std::mutex mutex;
    int inputSize = 320;
    int threads = 4;
};

struct OrtDetector {
    Ort::SessionOptions options;
    Ort::Session session{nullptr};
    Ort::MemoryInfo memoryInfo = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    std::mutex mutex;
    std::string inputName;
    std::string outputName;
    std::vector<float> inputBuffer;
    int inputSize = 320;
    int threads = 4;
};

Ort::Env& ortEnvironment() {
    static Ort::Env environment(ORT_LOGGING_LEVEL_WARNING, "AutoJsYoloOrt");
    return environment;
}

void throwException(JNIEnv* env, const char* className, const std::string& message) {
    jclass exceptionClass = env->FindClass(className);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
    }
}

std::string readString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

float overlap(const Detection& left, const Detection& right) {
    const float x1 = std::max(left.x1, right.x1);
    const float y1 = std::max(left.y1, right.y1);
    const float x2 = std::min(left.x2, right.x2);
    const float y2 = std::min(left.y2, right.y2);
    return std::max(0.0f, x2 - x1) * std::max(0.0f, y2 - y1);
}

void decode(const ncnn::Mat& output, float confidence, float nmsThreshold,
            float scale, int padLeft, int padTop, int sourceWidth, int sourceHeight,
            std::vector<Detection>& detections) {
    // YOLO26 end-to-end export: [x1, y1, x2, y2, confidence, class].
    if (output.dims == 2 && output.w == 6 && output.h > 0) {
        detections.reserve(std::min(output.h, 100));
        for (int rowIndex = 0; rowIndex < output.h; ++rowIndex) {
            const float* row = output.row(rowIndex);
            if (row[4] < confidence) continue;
            float x1 = (row[0] - padLeft) / scale;
            float y1 = (row[1] - padTop) / scale;
            float x2 = (row[2] - padLeft) / scale;
            float y2 = (row[3] - padTop) / scale;
            x1 = std::clamp(x1, 0.0f, static_cast<float>(sourceWidth));
            y1 = std::clamp(y1, 0.0f, static_cast<float>(sourceHeight));
            x2 = std::clamp(x2, 0.0f, static_cast<float>(sourceWidth));
            y2 = std::clamp(y2, 0.0f, static_cast<float>(sourceHeight));
            if (x2 > x1 && y2 > y1) {
                detections.push_back({x1, y1, x2, y2, row[4], static_cast<int>(row[5])});
            }
        }
        return;
    }

    // Compatibility with raw [4 + classes, anchors] exports.
    if (output.dims != 2 || output.h < 5 || output.w <= 0) return;
    const int anchors = output.w;
    const int classes = output.h - 4;
    std::vector<Detection> candidates;
    candidates.reserve(128);
    const float* cx = output.row(0);
    const float* cy = output.row(1);
    const float* width = output.row(2);
    const float* height = output.row(3);
    for (int anchor = 0; anchor < anchors; ++anchor) {
        int label = -1;
        float score = 0.0f;
        for (int classIndex = 0; classIndex < classes; ++classIndex) {
            const float value = output.row(4 + classIndex)[anchor];
            if (value > score) {
                score = value;
                label = classIndex;
            }
        }
        if (score < confidence) continue;
        float x1 = (cx[anchor] - width[anchor] * 0.5f - padLeft) / scale;
        float y1 = (cy[anchor] - height[anchor] * 0.5f - padTop) / scale;
        float x2 = (cx[anchor] + width[anchor] * 0.5f - padLeft) / scale;
        float y2 = (cy[anchor] + height[anchor] * 0.5f - padTop) / scale;
        x1 = std::clamp(x1, 0.0f, static_cast<float>(sourceWidth));
        y1 = std::clamp(y1, 0.0f, static_cast<float>(sourceHeight));
        x2 = std::clamp(x2, 0.0f, static_cast<float>(sourceWidth));
        y2 = std::clamp(y2, 0.0f, static_cast<float>(sourceHeight));
        if (x2 > x1 && y2 > y1) {
            candidates.push_back({x1, y1, x2, y2, score, label});
        }
    }

    std::sort(candidates.begin(), candidates.end(), [](const Detection& left, const Detection& right) {
        return left.score > right.score;
    });
    for (const Detection& candidate : candidates) {
        bool keep = true;
        const float candidateArea = (candidate.x2 - candidate.x1) * (candidate.y2 - candidate.y1);
        for (const Detection& selected : detections) {
            if (candidate.label != selected.label) continue;
            const float intersection = overlap(candidate, selected);
            const float selectedArea = (selected.x2 - selected.x1) * (selected.y2 - selected.y1);
            const float unionArea = std::max(1.0f, candidateArea + selectedArea - intersection);
            if (intersection / unionArea > nmsThreshold) {
                keep = false;
                break;
            }
        }
        if (keep) detections.push_back(candidate);
    }
}

class BitmapPixels {
public:
    BitmapPixels(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {}

    bool lock() {
        if (AndroidBitmap_getInfo(env_, bitmap_, &info_) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
        if (info_.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info_.width < 2 || info_.height < 2) {
            return false;
        }
        if (AndroidBitmap_lockPixels(env_, bitmap_, &pixels_) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
        locked_ = true;
        return true;
    }

    ~BitmapPixels() {
        if (locked_) AndroidBitmap_unlockPixels(env_, bitmap_);
    }

    const AndroidBitmapInfo& info() const { return info_; }
    const unsigned char* data() const { return static_cast<const unsigned char*>(pixels_); }

private:
    JNIEnv* env_;
    jobject bitmap_;
    AndroidBitmapInfo info_{};
    void* pixels_ = nullptr;
    bool locked_ = false;
};

struct PreparedInput {
    int sourceWidth = 0;
    int sourceHeight = 0;
    int padLeft = 0;
    int padTop = 0;
    float scale = 1.0f;
    float elapsedMs = 0.0f;
};

bool prepareOrtInput(JNIEnv* env, jobject bitmap, int inputSize, std::vector<float>& buffer,
                     PreparedInput& prepared) {
    BitmapPixels bitmapPixels(env, bitmap);
    if (!bitmapPixels.lock()) {
        throwException(env, "java/lang/IllegalArgumentException",
                       "YOLO requires an ARGB_8888 image");
        return false;
    }
    const AndroidBitmapInfo& info = bitmapPixels.info();
    prepared.sourceWidth = static_cast<int>(info.width);
    prepared.sourceHeight = static_cast<int>(info.height);

    const unsigned char* rgba = bitmapPixels.data();
    std::vector<unsigned char> packedPixels;
    if (info.stride != info.width * 4) {
        packedPixels.resize(static_cast<size_t>(prepared.sourceWidth) * prepared.sourceHeight * 4);
        for (int row = 0; row < prepared.sourceHeight; ++row) {
            std::memcpy(packedPixels.data() + static_cast<size_t>(row) * prepared.sourceWidth * 4,
                        rgba + static_cast<size_t>(row) * info.stride,
                        static_cast<size_t>(prepared.sourceWidth) * 4);
        }
        rgba = packedPixels.data();
    }

    const auto started = std::chrono::steady_clock::now();
    prepared.scale = std::min(inputSize / static_cast<float>(prepared.sourceWidth),
                              inputSize / static_cast<float>(prepared.sourceHeight));
    const int resizedWidth = std::max(1, static_cast<int>(
            std::round(prepared.sourceWidth * prepared.scale)));
    const int resizedHeight = std::max(1, static_cast<int>(
            std::round(prepared.sourceHeight * prepared.scale)));
    const int padWidth = inputSize - resizedWidth;
    const int padHeight = inputSize - resizedHeight;
    prepared.padLeft = padWidth / 2;
    prepared.padTop = padHeight / 2;

    ncnn::Mat resized = ncnn::Mat::from_pixels_resize(rgba, ncnn::Mat::PIXEL_RGBA2RGB,
            prepared.sourceWidth, prepared.sourceHeight, resizedWidth, resizedHeight);
    ncnn::Mat padded;
    ncnn::copy_make_border(resized, padded, prepared.padTop, padHeight - prepared.padTop,
            prepared.padLeft, padWidth - prepared.padLeft, ncnn::BORDER_CONSTANT, 114.0f);
    const float normalization[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
    padded.substract_mean_normalize(nullptr, normalization);

    const size_t planeSize = static_cast<size_t>(inputSize) * inputSize;
    buffer.resize(planeSize * 3);
    for (int channel = 0; channel < 3; ++channel) {
        const float* source = padded.channel(channel);
        std::copy(source, source + planeSize, buffer.begin() + planeSize * channel);
    }
    prepared.elapsedMs = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - started).count();
    return true;
}

void decodeOrtRows(const float* rows, size_t rowCount, float confidence,
                   const PreparedInput& prepared, std::vector<Detection>& detections) {
    detections.reserve(std::min<size_t>(rowCount, 100));
    for (size_t rowIndex = 0; rowIndex < rowCount; ++rowIndex) {
        const float* row = rows + rowIndex * 6;
        if (row[4] < confidence) continue;
        float x1 = (row[0] - prepared.padLeft) / prepared.scale;
        float y1 = (row[1] - prepared.padTop) / prepared.scale;
        float x2 = (row[2] - prepared.padLeft) / prepared.scale;
        float y2 = (row[3] - prepared.padTop) / prepared.scale;
        x1 = std::clamp(x1, 0.0f, static_cast<float>(prepared.sourceWidth));
        y1 = std::clamp(y1, 0.0f, static_cast<float>(prepared.sourceHeight));
        x2 = std::clamp(x2, 0.0f, static_cast<float>(prepared.sourceWidth));
        y2 = std::clamp(y2, 0.0f, static_cast<float>(prepared.sourceHeight));
        if (x2 > x1 && y2 > y1) {
            detections.push_back({x1, y1, x2, y2, row[4], static_cast<int>(row[5])});
        }
    }
}

jfloatArray detectNcnnRgba(JNIEnv* env, Detector* detector, const unsigned char* rgba,
                           int width, int height, int rowStride,
                           float confidence, float nmsThreshold) {
    if (rgba == nullptr || width < 2 || height < 2 || rowStride < width * 4) {
        throwException(env, "java/lang/IllegalArgumentException", "Invalid NativeFrame RGBA layout");
        return nullptr;
    }

    std::lock_guard<std::mutex> guard(detector->mutex);
    std::vector<unsigned char> packedPixels;
    if (rowStride != width * 4) {
        packedPixels.resize(static_cast<size_t>(width) * height * 4);
        for (int row = 0; row < height; ++row) {
            std::memcpy(packedPixels.data() + static_cast<size_t>(row) * width * 4,
                    rgba + static_cast<size_t>(row) * rowStride,
                    static_cast<size_t>(width) * 4);
        }
        rgba = packedPixels.data();
    }

    const auto prepareStarted = std::chrono::steady_clock::now();
    const float scale = std::min(detector->inputSize / static_cast<float>(width),
                                 detector->inputSize / static_cast<float>(height));
    const int resizedWidth = std::max(1, static_cast<int>(std::round(width * scale)));
    const int resizedHeight = std::max(1, static_cast<int>(std::round(height * scale)));
    const int padWidth = detector->inputSize - resizedWidth;
    const int padHeight = detector->inputSize - resizedHeight;
    const int padLeft = padWidth / 2;
    const int padTop = padHeight / 2;
    ncnn::Mat input = ncnn::Mat::from_pixels_resize(rgba, ncnn::Mat::PIXEL_RGBA2RGB,
            width, height, resizedWidth, resizedHeight);
    ncnn::Mat padded;
    ncnn::copy_make_border(input, padded, padTop, padHeight - padTop,
            padLeft, padWidth - padLeft, ncnn::BORDER_CONSTANT, 114.0f);
    const float normalization[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
    padded.substract_mean_normalize(nullptr, normalization);
    const float prepareMs = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - prepareStarted).count();

    const auto inferenceStarted = std::chrono::steady_clock::now();
    ncnn::Extractor extractor = detector->net.create_extractor();
    extractor.set_light_mode(true);
    if (extractor.input("in0", padded) != 0) {
        throwException(env, "java/lang/IllegalStateException", "YOLO input node 'in0' was not found");
        return nullptr;
    }
    ncnn::Mat output;
    if (extractor.extract("out0", output) != 0) {
        throwException(env, "java/lang/IllegalStateException", "YOLO output node 'out0' was not found");
        return nullptr;
    }
    const float inferenceMs = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - inferenceStarted).count();

    std::vector<Detection> detections;
    decode(output, confidence, nmsThreshold, scale, padLeft, padTop, width, height, detections);
    std::vector<float> packed;
    packed.reserve(2 + detections.size() * 6);
    packed.push_back(prepareMs);
    packed.push_back(inferenceMs);
    for (const Detection& detection : detections) {
        packed.insert(packed.end(), {detection.x1, detection.y1, detection.x2, detection.y2,
                                    detection.score, static_cast<float>(detection.label)});
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(packed.size()));
    if (result != nullptr && !packed.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(packed.size()), packed.data());
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_stardust_autojs_runtime_api_Yolo_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(NCNN_VERSION_STRING);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_stardust_autojs_runtime_api_Yolo_nativeCreate(
        JNIEnv* env, jclass, jstring paramPath, jstring binPath, jint inputSize, jint threads) {
    const std::string param = readString(env, paramPath);
    const std::string bin = readString(env, binPath);
    if (param.empty() || bin.empty()) {
        throwException(env, "java/lang/IllegalArgumentException", "YOLO model path is empty");
        return 0;
    }

    std::unique_ptr<Detector> detector(new Detector());
    detector->inputSize = std::max(32, static_cast<int>(inputSize));
    detector->threads = std::clamp(static_cast<int>(threads), 1, 8);
    detector->net.opt.num_threads = detector->threads;
    detector->net.opt.use_packing_layout = true;
    // Keep this YOLO26 graph in FP32. Its concat path mixes float tensors and
    // class indices, which is unsafe with ARM FP16 packing in this export.
    detector->net.opt.use_fp16_packed = false;
    detector->net.opt.use_fp16_storage = false;
    detector->net.opt.use_fp16_arithmetic = false;
    detector->net.opt.use_bf16_storage = false;
    detector->net.opt.use_vulkan_compute = false;

    // Do not call ncnn's process-wide CPU affinity/OpenMP setters here. AI.js Pro may already
    // have initialized another OpenMP consumer (notably OpenCV), and reinitializing affinity
    // from a second script can abort the whole Android process inside libomp. The per-Net
    // thread option above is sufficient and remains safe across repeated detector instances.
    const int paramResult = detector->net.load_param(param.c_str());
    const int modelResult = paramResult == 0 ? detector->net.load_model(bin.c_str()) : -1;
    if (paramResult != 0 || modelResult != 0) {
        const std::string message = "Cannot load YOLO model (param=" + std::to_string(paramResult)
                + ", bin=" + std::to_string(modelResult) + ")";
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        throwException(env, "java/lang/IllegalStateException", message);
        return 0;
    }

    __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "ncnn=%s backend=CPU(no-OpenMP) input=%d requestedThreads=%d", NCNN_VERSION_STRING,
            detector->inputSize, detector->threads);
    return reinterpret_cast<jlong>(detector.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_stardust_autojs_runtime_api_Yolo_nativeRelease(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Detector*>(handle);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_stardust_autojs_runtime_api_Yolo_nativeDetectBitmap(
        JNIEnv* env, jclass, jlong handle, jobject bitmap, jfloat confidence, jfloat nmsThreshold) {
    Detector* detector = reinterpret_cast<Detector*>(handle);
    if (detector == nullptr) {
        throwException(env, "java/lang/IllegalStateException", "YOLO detector is closed");
        return nullptr;
    }
    if (bitmap == nullptr) {
        throwException(env, "java/lang/IllegalArgumentException", "Image bitmap is null");
        return nullptr;
    }

    BitmapPixels bitmapPixels(env, bitmap);
    if (!bitmapPixels.lock()) {
        throwException(env, "java/lang/IllegalArgumentException", "YOLO requires an ARGB_8888 image");
        return nullptr;
    }
    const AndroidBitmapInfo& info = bitmapPixels.info();
    return detectNcnnRgba(env, detector, bitmapPixels.data(),
            static_cast<int>(info.width), static_cast<int>(info.height),
            static_cast<int>(info.stride), confidence, nmsThreshold);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_stardust_autojs_runtime_api_Yolo_nativeDetectRgba(
        JNIEnv* env, jclass, jlong handle, jobject rgbaBuffer,
        jint width, jint height, jint rowStride, jfloat confidence, jfloat nmsThreshold) {
    Detector* detector = reinterpret_cast<Detector*>(handle);
    if (detector == nullptr) {
        throwException(env, "java/lang/IllegalStateException", "YOLO detector is closed");
        return nullptr;
    }
    auto* rgba = static_cast<const unsigned char*>(env->GetDirectBufferAddress(rgbaBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(rgbaBuffer);
    const int64_t required = static_cast<int64_t>(height - 1) * rowStride
            + static_cast<int64_t>(width) * 4;
    if (rgba == nullptr || width < 2 || height < 2 || rowStride < width * 4
            || capacity < required) {
        throwException(env, "java/lang/IllegalArgumentException", "Invalid NativeFrame DirectByteBuffer");
        return nullptr;
    }
    return detectNcnnRgba(env, detector, rgba, width, height, rowStride,
            confidence, nmsThreshold);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_stardust_autojs_runtime_api_OnnxYoloDetector_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(OrtGetApiBase()->GetVersionString());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_stardust_autojs_runtime_api_OnnxYoloDetector_nativeCreate(
        JNIEnv* env, jclass, jstring modelPath, jint inputSize, jint threads) {
    const std::string model = readString(env, modelPath);
    if (model.empty()) {
        throwException(env, "java/lang/IllegalArgumentException", "ONNX model path is empty");
        return 0;
    }

    try {
        std::unique_ptr<OrtDetector> detector(new OrtDetector());
        detector->inputSize = std::max(32, static_cast<int>(inputSize));
        detector->threads = std::clamp(static_cast<int>(threads), 1, 8);
        detector->options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        detector->options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
        detector->options.SetIntraOpNumThreads(detector->threads);
        detector->options.SetInterOpNumThreads(1);
        try {
            detector->options.AppendExecutionProvider("XNNPACK", {
                    {"intra_op_num_threads", std::to_string(detector->threads)}});
        } catch (const Ort::Exception& error) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                    "XNNPACK unavailable, using ONNX Runtime CPU: %s", error.what());
        }
        detector->session = Ort::Session(ortEnvironment(), model.c_str(), detector->options);
        Ort::AllocatorWithDefaultOptions allocator;
        if (detector->session.GetInputCount() != 1 || detector->session.GetOutputCount() < 1) {
            throw std::runtime_error("YOLO ONNX model must have one input and at least one output");
        }
        Ort::AllocatedStringPtr inputName = detector->session.GetInputNameAllocated(0, allocator);
        Ort::AllocatedStringPtr outputName = detector->session.GetOutputNameAllocated(0, allocator);
        detector->inputName = inputName.get();
        detector->outputName = outputName.get();
        detector->inputBuffer.resize(static_cast<size_t>(detector->inputSize)
                * detector->inputSize * 3);
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                "onnxruntime=%s backend=XNNPACK/CPU input=%d threads=%d nodes=%s->%s",
                OrtGetApiBase()->GetVersionString(), detector->inputSize, detector->threads,
                detector->inputName.c_str(), detector->outputName.c_str());
        return reinterpret_cast<jlong>(detector.release());
    } catch (const Ort::Exception& error) {
        const std::string message = std::string("Cannot load ONNX YOLO model: ") + error.what();
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        throwException(env, "java/lang/IllegalStateException", message);
    } catch (const std::exception& error) {
        const std::string message = std::string("Cannot load ONNX YOLO model: ") + error.what();
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        throwException(env, "java/lang/IllegalStateException", message);
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_stardust_autojs_runtime_api_OnnxYoloDetector_nativeRelease(
        JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<OrtDetector*>(handle);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_stardust_autojs_runtime_api_OnnxYoloDetector_nativeDetectBitmap(
        JNIEnv* env, jclass, jlong handle, jobject bitmap, jfloat confidence, jfloat) {
    OrtDetector* detector = reinterpret_cast<OrtDetector*>(handle);
    if (detector == nullptr) {
        throwException(env, "java/lang/IllegalStateException", "ONNX detector is closed");
        return nullptr;
    }
    if (bitmap == nullptr) {
        throwException(env, "java/lang/IllegalArgumentException", "Image bitmap is null");
        return nullptr;
    }

    std::lock_guard<std::mutex> guard(detector->mutex);
    try {
        PreparedInput prepared;
        if (!prepareOrtInput(env, bitmap, detector->inputSize, detector->inputBuffer, prepared)) {
            return nullptr;
        }
        const std::array<int64_t, 4> inputShape = {
                1, 3, detector->inputSize, detector->inputSize};
        Ort::Value inputTensor = Ort::Value::CreateTensor<float>(detector->memoryInfo,
                detector->inputBuffer.data(), detector->inputBuffer.size(),
                inputShape.data(), inputShape.size());
        const char* inputNames[] = {detector->inputName.c_str()};
        const char* outputNames[] = {detector->outputName.c_str()};
        const auto inferenceStarted = std::chrono::steady_clock::now();
        std::vector<Ort::Value> outputs = detector->session.Run(Ort::RunOptions{nullptr},
                inputNames, &inputTensor, 1, outputNames, 1);
        const float inferenceMs = std::chrono::duration<float, std::milli>(
                std::chrono::steady_clock::now() - inferenceStarted).count();
        if (outputs.empty() || !outputs[0].IsTensor()) {
            throw std::runtime_error("ONNX output is not a tensor");
        }
        const Ort::TensorTypeAndShapeInfo outputInfo = outputs[0].GetTensorTypeAndShapeInfo();
        const size_t elementCount = outputInfo.GetElementCount();
        if (elementCount == 0 || elementCount % 6 != 0) {
            throw std::runtime_error("Unsupported ONNX output shape; expected [..., 6]");
        }
        const float* outputData = outputs[0].GetTensorData<float>();
        std::vector<Detection> detections;
        decodeOrtRows(outputData, elementCount / 6, confidence, prepared, detections);

        std::vector<float> packed;
        packed.reserve(2 + detections.size() * 6);
        packed.push_back(prepared.elapsedMs);
        packed.push_back(inferenceMs);
        for (const Detection& detection : detections) {
            packed.insert(packed.end(), {detection.x1, detection.y1, detection.x2, detection.y2,
                                        detection.score, static_cast<float>(detection.label)});
        }
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(packed.size()));
        if (result != nullptr && !packed.empty()) {
            env->SetFloatArrayRegion(result, 0, static_cast<jsize>(packed.size()), packed.data());
        }
        return result;
    } catch (const Ort::Exception& error) {
        const std::string message = std::string("ONNX Runtime inference failed: ") + error.what();
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        throwException(env, "java/lang/IllegalStateException", message);
    } catch (const std::exception& error) {
        const std::string message = std::string("ONNX Runtime inference failed: ") + error.what();
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
        throwException(env, "java/lang/IllegalStateException", message);
    }
    return nullptr;
}
