#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <iomanip>
#include <limits>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <opencv2/core/core.hpp>
#include <opencv2/geometry/2d.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include "ppocr_engine.h"
#include "ppocrv5_dict.h"

#define LOG_TAG "PPOCRBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 全局只保留一个 OCR 引擎实例。所有 native 调用用同一把锁保护，避免多脚本并发访问 ncnn。
std::mutex g_lock;
PPOCREngine* g_ocr = nullptr;
int g_cpuPower = 0;

// 识别工作线程数上限。串行 → 4 路实测快 2.15×（K40：shot-now 928ms → 432ms）；
// ⚠️ 再往上无益：同一台机器上 6 路反而更慢（512 下 379ms vs 4 路的 349ms）——
// 长框的尾延迟 + 小核参与拖累了整体，所以上限就定在 4。
const int kMaxSafeRecThreads = 4;
const int kMaxSafeItems = 20;
const int kMinTargetSize = 320;
const int kMaxTargetSize = 1280;

// targetSize 太小容易漏字/异常，太大又慢，这里做 SO 层兜底。
int safeTargetSize(int targetSize) {
    if (targetSize <= 0) return 480;
    return std::max(kMinTargetSize, std::min(targetSize, kMaxTargetSize));
}

// 释放当前模型，并清理运行时缓存。切换模型或脚本结束时调用。
void releaseEngineLocked() {
    if (!g_ocr) return;
    g_ocr->clear_runtime_memory();
    delete g_ocr;
    g_ocr = nullptr;
    g_cpuPower = 0;
}

// 读取 Android sysfs 里的 CPU 最大频率，用来区分大小核。
int readCpuMaxFreq(int cpu) {
    char path[128];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE* fp = std::fopen(path, "r");
    if (!fp) return -1;
    int freq = -1;
    if (std::fscanf(fp, "%d", &freq) != 1) freq = -1;
    std::fclose(fp);
    return freq;
}

// 把 cpu_set_t 转成日志可读的 "0,1,2" 字符串。
std::string cpuSetToString(const cpu_set_t& set) {
    std::ostringstream out;
    bool first = true;
    for (int i = 0; i < CPU_SETSIZE; ++i) {
        if (!CPU_ISSET(i, &set)) continue;
        if (!first) out << ",";
        out << i;
        first = false;
    }
    return out.str();
}

// 根据 cpuPower 生成要绑定的 CPU 集合：1=最低频率小核，2=最高频率大核。
bool buildCpuSetForPower(int power, cpu_set_t& set) {
    CPU_ZERO(&set);
    if (power <= 0) return false;

    long configured = sysconf(_SC_NPROCESSORS_CONF);
    int cpuCount = configured > 0 ? static_cast<int>(configured) : 0;
    cpuCount = std::min(cpuCount, CPU_SETSIZE);
    if (cpuCount <= 0) return false;

    std::vector<int> freqs(cpuCount, -1);
    int minFreq = std::numeric_limits<int>::max();
    int maxFreq = -1;
    for (int i = 0; i < cpuCount; ++i) {
        freqs[i] = readCpuMaxFreq(i);
        if (freqs[i] <= 0) continue;
        minFreq = std::min(minFreq, freqs[i]);
        maxFreq = std::max(maxFreq, freqs[i]);
    }
    if (maxFreq <= 0 || minFreq == std::numeric_limits<int>::max()) {
        LOGE("PPOCR affinity skipped: cpu max freq unavailable");
        return false;
    }

    // ⚠️ 不能用「等于最大频率」选核：K40 上只有 cpu7 是 prime，会把多 worker 全压到单核（实测比串行还慢）。
    // power=2 取「大核簇」（频点 >= 最高频的 70%），power=1 取剩下的核。
    const double bigThreshold = static_cast<double>(maxFreq) * 0.70;
    for (int i = 0; i < cpuCount; ++i) {
        if (freqs[i] <= 0) continue;
        const bool big = static_cast<double>(freqs[i]) >= bigThreshold;
        if (power == 2 ? big : !big) CPU_SET(i, &set);
    }
    // 兜底：可选核太少（<2）就不绑，避免退化成单核。
    if (CPU_COUNT(&set) < 2) {
        LOGE("PPOCR affinity skipped: set too small power=%d cpus=%s", power, cpuSetToString(set).c_str());
        return false;
    }
    return true;
}

// 只在当前 OCR 调用线程范围内绑定 CPU，析构时自动恢复原 affinity。
class ScopedThreadAffinity {
public:
    explicit ScopedThreadAffinity(int power) : active_(false), power_(power) {
        if (power_ <= 0) return;
        if (sched_getaffinity(0, sizeof(oldSet_), &oldSet_) != 0) {
            LOGE("PPOCR affinity get failed");
            return;
        }
        cpu_set_t targetSet;
        if (!buildCpuSetForPower(power_, targetSet)) return;
        if (sched_setaffinity(0, sizeof(targetSet), &targetSet) != 0) {
            LOGE("PPOCR affinity set failed power=%d cpus=%s", power_, cpuSetToString(targetSet).c_str());
            return;
        }
        active_ = true;
        LOGD("PPOCR affinity applied power=%d cpus=%s", power_, cpuSetToString(targetSet).c_str());
    }

    ~ScopedThreadAffinity() {
        if (!active_) return;
        if (sched_setaffinity(0, sizeof(oldSet_), &oldSet_) != 0) {
            LOGE("PPOCR affinity restore failed power=%d", power_);
            return;
        }
        LOGD("PPOCR affinity restored");
    }

private:
    bool active_;
    int power_;
    cpu_set_t oldSet_;
};

std::string jstringToString(JNIEnv* env, jstring value) {
    if (!value) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return "";
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

// std::string 转 Java String。
jstring stringToJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

// JSON 字符串转义，防止识别文字里有引号、换行导致 JSON 格式坏掉。
std::string jsonEscape(const std::string& value) {
    std::ostringstream out;
    for (char ch : value) {
        switch (ch) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (static_cast<unsigned char>(ch) < 0x20) {
                    out << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                        << static_cast<int>(static_cast<unsigned char>(ch));
                } else {
                    out << ch;
                }
        }
    }
    return out.str();
}

// Android Bitmap 转 OpenCV BGR Mat。Auto.js Image 最终会走到这里。
bool bitmapToBgrMat(JNIEnv* env, jobject bitmap, cv::Mat& bgr) {
    if (!bitmap) return false;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return false;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format: %d", info.format);
        return false;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_lockPixels failed");
        return false;
    }

    cv::Mat rgba(info.height, info.width, CV_8UC4, pixels, info.stride);
    cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);

    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

// 从 Object 里取最终文字。v6 用 text_value，v5 用字符字典拼接。
std::string objectText(const Object& object) {
    if (!object.text_value.empty()) return object.text_value;
    std::string text;
    for (const Character& ch : object.text) {
        if (ch.id < 0 || ch.id >= character_dict_size) {
            if (!text.empty() && text.back() != ' ') text += " ";
            continue;
        }
        text += character_dict[ch.id];
    }
    return text;
}

// 计算一条结果的平均置信度。
float avgTextScore(const Object& object) {
    if (!object.text_scores.empty()) {
        float total = 0.f;
        for (float score : object.text_scores) total += score;
        return total / static_cast<float>(object.text_scores.size());
    }
    if (object.text.empty()) return object.prob;
    float total = 0.f;
    for (const Character& ch : object.text) total += ch.prob;
    return total / static_cast<float>(object.text.size());
}

// 判断文字是否不是空白。
bool hasVisibleText(const std::string& text) {
    for (unsigned char ch : text) {
        if (ch > 0x20) return true;
    }
    return false;
}

// 识别后过滤空文本和低置信度结果。
bool keepRecognizedObject(const Object& object, float minScore) {
    std::string text = objectText(object);
    if (!hasVisibleText(text)) return false;
    return avgTextScore(object) >= minScore;
}

// 旋转文字框转普通矩形边界，用于排序和 JSON 输出。
cv::Rect objectBounds(const Object& object) {
    cv::Point2f corners[4];
    object.rrect.points(corners);
    float left = corners[0].x;
    float top = corners[0].y;
    float right = corners[0].x;
    float bottom = corners[0].y;
    for (int i = 1; i < 4; ++i) {
        left = std::min(left, corners[i].x);
        top = std::min(top, corners[i].y);
        right = std::max(right, corners[i].x);
        bottom = std::max(bottom, corners[i].y);
    }
    int ileft = static_cast<int>(std::lround(left));
    int itop = static_cast<int>(std::lround(top));
    int iright = static_cast<int>(std::lround(right));
    int ibottom = static_cast<int>(std::lround(bottom));
    return cv::Rect(ileft, itop, std::max(0, iright - ileft), std::max(0, ibottom - itop));
}

// 把矩形限制到图片范围内，避免输出负坐标或越界坐标。
cv::Rect clampBounds(const cv::Rect& bounds, int imageWidth, int imageHeight) {
    if (imageWidth <= 0 || imageHeight <= 0) return bounds;
    int left = std::max(0, std::min(bounds.x, imageWidth));
    int top = std::max(0, std::min(bounds.y, imageHeight));
    int right = std::max(left, std::min(bounds.x + bounds.width, imageWidth));
    int bottom = std::max(top, std::min(bounds.y + bounds.height, imageHeight));
    return cv::Rect(left, top, right - left, bottom - top);
}

// 检测框安全检查。异常框不送去 recognize，避免 warpAffine/native 崩溃。
bool hasValidBox(const Object& object, int imageWidth, int imageHeight) {
    const cv::RotatedRect& r = object.rrect;
    if (!std::isfinite(r.center.x) || !std::isfinite(r.center.y) ||
        !std::isfinite(r.size.width) || !std::isfinite(r.size.height) ||
        !std::isfinite(r.angle)) {
        return false;
    }
    if (r.size.width < 2.f || r.size.height < 2.f) return false;
    if (r.size.width > imageWidth * 1.5f || r.size.height > imageHeight * 1.5f) return false;
    if (r.center.x < -imageWidth || r.center.x > imageWidth * 2.f ||
        r.center.y < -imageHeight || r.center.y > imageHeight * 2.f) {
        return false;
    }
    cv::Rect bounds = clampBounds(objectBounds(object), imageWidth, imageHeight);
    return bounds.width >= 2 && bounds.height >= 2;
}

// 按阅读顺序排序：先从上到下，再从左到右。
void sortByReadingOrder(std::vector<Object>& objects) {
    std::sort(objects.begin(), objects.end(), [](const Object& a, const Object& b) {
        cv::Rect ar = objectBounds(a);
        cv::Rect br = objectBounds(b);
        if (std::abs(ar.y - br.y) > 18) return ar.y < br.y;
        return ar.x < br.x;
    });
}

// 把 native 识别结果组装成 JSON，返回给 JS 层 JSON.parse。
std::string objectsToJson(const std::vector<Object>& objects, double elapsedMs, size_t rawCount, size_t filteredCount, int recThreads, int imageWidth, int imageHeight) {
    std::ostringstream out;
    out << "{\"ok\":true,\"elapsedMs\":" << std::fixed << std::setprecision(3) << elapsedMs
        << ",\"rawCount\":" << rawCount
        << ",\"filteredCount\":" << filteredCount
        << ",\"recThreads\":" << recThreads
        << ",\"items\":[";
    for (size_t i = 0; i < objects.size(); ++i) {
        if (i) out << ",";
        const Object& obj = objects[i];
        std::string text = objectText(obj);
        float confidence = avgTextScore(obj);
        cv::Rect bounds = clampBounds(objectBounds(obj), imageWidth, imageHeight);
        int ileft = bounds.x;
        int itop = bounds.y;
        int iright = bounds.x + bounds.width;
        int ibottom = bounds.y + bounds.height;
        std::ostringstream rect;
        rect << "Rect(" << ileft << ", " << itop << " - " << iright << ", " << ibottom << ")";
        std::string rectText = rect.str();
        std::ostringstream javaObject;
        javaObject << "PPOcrResult(confidence=" << std::setprecision(6) << confidence
                   << ", words=" << text
                   << ", bounds=" << rectText
                   << ", rotation=0, rotationConfidence=0)";
        out << "{\"javaObject\":\"" << jsonEscape(javaObject.str()) << "\",";
        out << "\"confidence\":" << std::setprecision(6) << confidence << ",";
        out << "\"text\":\"" << jsonEscape(text) << "\",";
        out << "\"bounds\":\"" << jsonEscape(rectText) << "\",";
        out << "\"x\":" << ileft << ",";
        out << "\"y\":" << itop << ",";
        out << "\"w\":" << bounds.width << ",";
        out << "\"h\":" << bounds.height << ",";
        out << "\"rotation\":0,";
        out << "\"rotationConfidence\":0}";
    }
    out << "]}";
    return out.str();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_stardust_autojs_runtime_api_PPOcrBridge_nativeGetVersion(JNIEnv* env, jclass) {
    return stringToJString(env, "PPOCR ncnn 1.0 (ppocrv5/v6)");
}

// 初始化模型。这里只做模型加载，运行时参数变化不应该频繁走这里。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_stardust_autojs_runtime_api_PPOcrBridge_nativeInit(
    JNIEnv* env,
    jclass,
    jstring detParamPath,
    jstring detModelPath,
    jstring recParamPath,
    jstring recModelPath,
    jint targetSize,
    jboolean useFp16,
    jboolean useGpu,
    jint cpuPower,
    jint recMaxWidth) {

    std::unique_lock<std::mutex> guard(g_lock, std::try_to_lock);
    if (!guard.owns_lock()) {
        LOGE("PPOCR init rejected: native busy");
        return JNI_FALSE;
    }

    std::string detParam = jstringToString(env, detParamPath);
    std::string detModel = jstringToString(env, detModelPath);
    std::string recParam = jstringToString(env, recParamPath);
    std::string recModel = jstringToString(env, recModelPath);

    g_cpuPower = std::max(0, std::min(2, static_cast<int>(cpuPower)));
    LOGD("PPOCR cpuPower=%d configured", g_cpuPower);

    // 每次重新 init 前先释放旧模型，防止切换 v5/v6 时旧模型残留。
    releaseEngineLocked();
    g_ocr = new PPOCREngine();
    int safeTarget = safeTargetSize(static_cast<int>(targetSize));
    g_ocr->set_target_size(safeTarget);
    g_ocr->set_rec_max_width(static_cast<int>(recMaxWidth));
    int ret = g_ocr->load(
        detParam.c_str(),
        detModel.c_str(),
        recParam.c_str(),
        recModel.c_str(),
        useFp16 == JNI_TRUE,
        useGpu == JNI_TRUE
    );
    if (ret != 0) {
        LOGE("PPOCR model load failed ret=%d target=%d detParam=%s detModel=%s recParam=%s recModel=%s",
             ret, targetSize, detParam.c_str(), detModel.c_str(), recParam.c_str(), recModel.c_str());
        releaseEngineLocked();
        return JNI_FALSE;
    }
    LOGD("PPOCR model loaded target=%d requested=%d", safeTarget, targetSize);
    return JNI_TRUE;
}

// 更新运行时参数，不释放/重载模型。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_stardust_autojs_runtime_api_PPOcrBridge_nativeSetRuntimeOptions(
    JNIEnv*,
    jclass,
    jint targetSize,
    jint cpuPower) {

    std::unique_lock<std::mutex> guard(g_lock, std::try_to_lock);
    if (!guard.owns_lock()) {
        LOGE("PPOCR set runtime options rejected: native busy");
        return JNI_FALSE;
    }
    g_cpuPower = std::max(0, std::min(2, static_cast<int>(cpuPower)));
    if (g_ocr) {
        g_ocr->set_target_size(safeTargetSize(static_cast<int>(targetSize)));
    }
    LOGD("PPOCR runtime options target=%d requested=%d cpuPower=%d",
         safeTargetSize(static_cast<int>(targetSize)), targetSize, g_cpuPower);
    return JNI_TRUE;
}

// OCR 主入口：Bitmap -> BGR Mat -> 裁剪 region -> detect -> recognize -> JSON。
extern "C" JNIEXPORT jstring JNICALL
Java_com_stardust_autojs_runtime_api_PPOcrBridge_nativeRecognizeBitmap(
    JNIEnv* env,
    jclass,
    jobject bitmap,
    jint roiLeft,
    jint roiTop,
    jint roiRight,
    jint roiBottom,
    jint maxItemsArg,
    jfloat minScoreArg,
    jint recThreadsArg,
    jboolean singleLineArg) {
    auto start = std::chrono::steady_clock::now();

    std::unique_lock<std::mutex> guard(g_lock, std::try_to_lock);
    if (!guard.owns_lock()) {
        return stringToJString(env, "{\"ok\":false,\"error\":\"PPOCR native busy\"}");
    }
    // 如果设置了 cpuPower，只在这次 OCR 调用期间绑定当前线程。
    std::unique_ptr<ScopedThreadAffinity> affinity;
    if (g_cpuPower > 0) {
        affinity.reset(new ScopedThreadAffinity(g_cpuPower));
    }

    cv::Mat bgr;
    if (!bitmapToBgrMat(env, bitmap, bgr)) {
        return stringToJString(env, "{\"ok\":false,\"error\":\"Bitmap read failed\"}");
    }
    if (bgr.empty() || bgr.cols <= 0 || bgr.rows <= 0) {
        return stringToJString(env, "{\"ok\":false,\"error\":\"Bitmap is empty\"}");
    }

    // region 参数在 JS 层已经裁剪过，这里再做一次 SO 层兜底。
    int left = std::max(0, static_cast<int>(roiLeft));
    int top = std::max(0, static_cast<int>(roiTop));
    int right = roiRight > roiLeft ? static_cast<int>(roiRight) : bgr.cols;
    int bottom = roiBottom > roiTop ? static_cast<int>(roiBottom) : bgr.rows;
    int maxItems = maxItemsArg > 0 ? static_cast<int>(maxItemsArg) : 8;
    maxItems = std::max(1, std::min(maxItems, kMaxSafeItems));
    float minScore = minScoreArg >= 0.f ? static_cast<float>(minScoreArg) : 0.20f;
    int recThreads = std::min(static_cast<int>(recThreadsArg), kMaxSafeRecThreads);
    bool singleLine = singleLineArg == JNI_TRUE;
    left = std::min(left, bgr.cols - 1);
    top = std::min(top, bgr.rows - 1);
    right = std::max(left + 1, std::min(right, bgr.cols));
    bottom = std::max(top + 1, std::min(bottom, bgr.rows));
    int width = right - left;
    int height = bottom - top;
    if (width <= 0 || height <= 0) {
        return stringToJString(env, "{\"ok\":true,\"elapsedMs\":0,\"rawCount\":0,\"filteredCount\":0,\"recThreads\":1,\"items\":[]}");
    }

    // region 裁剪后 clone，避免后续处理引用原 Bitmap 内存。
    cv::Mat input = bgr(cv::Rect(left, top, width, height)).clone();

    std::vector<Object> objects;
    size_t rawCount = 0;
    size_t filteredCount = 0;
    int actualRecThreads = 1;
    {
        if (!g_ocr) {
            return stringToJString(env, "{\"ok\":false,\"error\":\"PPOCR not initialized\"}");
        }
        LOGD("PPOCR recognize start image=%dx%d roi=%d,%d,%d,%d maxItems=%d requestedMaxItems=%d minScore=%.3f recThreadsArg=%d capped=%d",
             bgr.cols, bgr.rows, left, top, right, bottom, maxItems,
             static_cast<int>(maxItemsArg), minScore, static_cast<int>(recThreadsArg), recThreads);
        // 第一步：检测文字框。
        LOGD("PPOCR detect start");
        int detRet = g_ocr->detect(input, objects);
        if (detRet != 0) {
            LOGE("PPOCR detect failed ret=%d", detRet);
            if (g_ocr) g_ocr->clear_runtime_memory();
            input.release();
            bgr.release();
            return stringToJString(env, "{\"ok\":false,\"error\":\"PPOCR detect failed\"}");
        }
        LOGD("PPOCR detect done raw=%zu", objects.size());
        rawCount = objects.size();
        std::vector<Object> filtered;
        filtered.reserve(objects.size());
        // 先按置信度和框合法性过滤，异常框不进入文字识别。
        for (const Object& obj : objects) {
            if (obj.prob >= minScore && hasValidBox(obj, input.cols, input.rows)) filtered.push_back(obj);
        }
        std::sort(filtered.begin(), filtered.end(), [](const Object& a, const Object& b) {
            if (a.prob != b.prob) return a.prob > b.prob;
            return a.rrect.size.area() > b.rrect.size.area();
        });

        size_t candidateLimit = filtered.size();
        if (maxItems > 0) {
            candidateLimit = std::min(filtered.size(), static_cast<size_t>(maxItems));
            if (filtered.size() > candidateLimit) filtered.resize(candidateLimit);
        }
        if (singleLine && filtered.size() > 1) {
            filtered.resize(1);
        }

        int requestedRecThreads = recThreads;
        if (requestedRecThreads <= 0) requestedRecThreads = 1;
        requestedRecThreads = std::min(requestedRecThreads, kMaxSafeRecThreads);
        const int workerCount = std::min<int>(requestedRecThreads, static_cast<int>(filtered.size()));
        actualRecThreads = workerCount;
        LOGD("PPOCR recognize candidates=%zu requestedRecThreads=%d actualWorkerCount=%d", filtered.size(), requestedRecThreads, workerCount);
        // 第二步：多条并发识别。共享 rec Net，但每个 worker 各自 create_extractor（线程安全），
        // 内存池在引擎里是 thread_local（关键！之前崩溃就是因为共享了非线程安全的 pool allocator）。
        cv::setNumThreads(1);
        const auto recOne = [&](Object& obj) {
            if (g_ocr->recognize(input, obj) != 0) {
                obj.text.clear();
                obj.text_value.clear();
                obj.text_scores.clear();
            }
        };
        // LPT 调度：rec 耗时 ∝ 裁剪宽度（= 长边/短边×48），先把最费时的框排到队列前面，
        // 让长框从第一刻开始跑，减少「最后一个长框还在跑、其他 worker 已空转」的尾延迟。
        // 结果仍写回各自槽位，后面的过滤/排序（阅读顺序）完全不受影响。
        const auto recCropWidth = [](const Object& obj) {
            const float w = std::max(1.0f, obj.rrect.size.width);
            const float h = std::max(1.0f, obj.rrect.size.height);
            return 48.0f * std::max(w, h) / std::min(w, h);
        };
        std::vector<size_t> order(filtered.size());
        for (size_t i = 0; i < order.size(); ++i) {
            order[i] = i;
        }
        std::sort(order.begin(), order.end(), [&](size_t a, size_t b) {
            return recCropWidth(filtered[a]) > recCropWidth(filtered[b]);
        });
        if (workerCount <= 1) {
            for (size_t i : order) recOne(filtered[i]);
        } else {
            std::atomic<size_t> nextIndex(0);
            std::vector<std::thread> pool;
            pool.reserve(static_cast<size_t>(workerCount));
            for (int t = 0; t < workerCount; ++t) {
                pool.emplace_back([&]() {
                    for (;;) {
                        const size_t slot = nextIndex.fetch_add(1);
                        if (slot >= order.size()) break;
                        recOne(filtered[order[slot]]);
                    }
                });
            }
            for (auto& th : pool) th.join();
        }
        LOGD("PPOCR recognize done");

        // 第三步：过滤空文本和低分文本。
        std::vector<Object> readable;
        readable.reserve(filtered.size());
        for (const Object& obj : filtered) {
            if (keepRecognizedObject(obj, minScore)) readable.push_back(obj);
        }
        std::sort(readable.begin(), readable.end(), [](const Object& a, const Object& b) {
            float as = avgTextScore(a);
            float bs = avgTextScore(b);
            if (as != bs) return as > bs;
            return a.rrect.size.area() > b.rrect.size.area();
        });
        if (maxItems > 0 && readable.size() > static_cast<size_t>(maxItems)) {
            readable.resize(static_cast<size_t>(maxItems));
        }
        filteredCount = readable.size();
        objects.swap(readable);
        LOGD("PPOCR filter done readable=%zu", filteredCount);
    }

    // 如果识别的是局部 region，把结果坐标加回原图坐标系。
    if (left != 0 || top != 0) {
        for (Object& obj : objects) {
            obj.rrect.center.x += left;
            obj.rrect.center.y += top;
        }
    }
    sortByReadingOrder(objects);

    double elapsedMs = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - start).count();
    LOGD("PPOCR json start final=%zu elapsed=%.3f", objects.size(), elapsedMs);
    std::string json = objectsToJson(objects, elapsedMs, rawCount, filteredCount, actualRecThreads, bgr.cols, bgr.rows);
    // 每次识别结束清理 ncnn 临时 allocator，减少长时间运行内存累积。
    if (g_ocr) g_ocr->clear_runtime_memory();
    input.release();
    bgr.release();
    return stringToJString(env, json);
}

extern "C" JNIEXPORT void JNICALL
Java_com_stardust_autojs_runtime_api_PPOcrBridge_nativeRelease(JNIEnv*, jclass) {
    std::unique_lock<std::mutex> guard(g_lock, std::try_to_lock);
    if (!guard.owns_lock()) {
        LOGE("PPOCR release ignored: native busy");
        return;
    }
    releaseEngineLocked();
}
