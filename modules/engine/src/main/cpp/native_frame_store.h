#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <opencv2/core.hpp>

struct NativeFramePoint {
    int x = 0;
    int y = 0;
    double similarity = 0.0;
};

struct NativeFrameColorOffset {
    int x = 0;
    int y = 0;
    uint32_t argb = 0;
};

struct NativeFrameInfo {
    int pixelWidth = 0;
    int pixelHeight = 0;
    int logicalWidth = 0;
    int logicalHeight = 0;
};

class NativeFrameStore {
public:
    int64_t createFromRgba(const uint8_t *data, int width, int height,
                           int rowStride, int pixelStride, int targetShortEdge = 0);
    int64_t load(const std::string &path, std::string *error);
    int64_t fromEncoded(const std::vector<uint8_t> &encoded, std::string *error);
    int64_t copy(int64_t handle, std::string *error);
    int64_t clip(int64_t handle, int x, int y, int width, int height,
                 std::string *error);
    int64_t resize(int64_t handle, int width, int height, int interpolation,
                   std::string *error);
    int64_t concat(int64_t firstHandle, int64_t secondHandle, int direction,
                   std::string *error);
    int64_t grayscale(int64_t handle, std::string *error);
    int64_t cvtColor(int64_t handle, const std::string &code, std::string *error);
    int64_t rotate(int64_t handle, int angle, std::string *error);
    int64_t threshold(int64_t handle, double thresh, double maxValue, int type,
                      std::string *error);
    int64_t blur(int64_t handle, int ksize, std::string *error);
    bool save(int64_t handle, const std::string &path, const std::string &format,
              int quality, std::string *error) const;
    bool compress(int64_t handle, const std::string &format, int quality,
                  std::vector<uint8_t> *bytes, std::string *error) const;
    bool release(int64_t handle);
    std::shared_ptr<const cv::Mat> get(int64_t handle) const;
    bool getInfo(int64_t handle, NativeFrameInfo *info) const;
    void clear();

    bool pixel(int64_t handle, int x, int y, uint32_t *argb) const;
    bool findColor(int64_t handle, uint32_t argb, int threshold,
                   int x, int y, int width, int height,
                   NativeFramePoint *point) const;
    bool findMultiColors(int64_t handle, uint32_t firstColor,
                         const std::vector<NativeFrameColorOffset> &offsets,
                         int threshold, int x, int y, int width, int height,
                         NativeFramePoint *point) const;
    bool findImage(int64_t sourceHandle, int64_t templateHandle, double threshold,
                   int x, int y, int width, int height,
                   NativeFramePoint *point, std::string *error) const;
    bool matchTemplate(int64_t sourceHandle, int64_t templateHandle, double threshold,
                       int maxMatches, int x, int y, int width, int height,
                       std::vector<NativeFramePoint> *matches, std::string *error) const;

    // ---- 调试统计 ----
    struct Stats {
        int64_t activeHandles = 0;
        int activeFrames = 0;       // frames_ map size
        int poolCount = 0;          // reusableFrames_ size
        int64_t poolBytes = 0;      // total bytes in reusable frames
        int64_t createCount = 0;    // total frames created
        int64_t reuseCount = 0;     // frames reused from pool
        int64_t rejectCount = 0;    // pool rejects (size mismatch or pool full)
    };
    Stats stats() const;

private:
    int64_t insert(cv::Mat frame, int logicalWidth = 0, int logicalHeight = 0);
    cv::Mat takeReusable(int width, int height, int type);

    mutable std::mutex mutex_;
    std::unordered_map<int64_t, std::shared_ptr<cv::Mat>> frames_;
    std::unordered_map<int64_t, NativeFrameInfo> frameInfo_;
    std::vector<cv::Mat> reusableFrames_;
    int64_t nextHandle_ = 1;
    // 调试统计计数器
    static constexpr int MAX_POOL_COUNT = 4;
    static constexpr int64_t MAX_POOL_BYTES = 50 * 1024 * 1024;  // 50 MB
    int64_t createCount_ = 0;
    int64_t reuseCount_ = 0;
    int64_t rejectCount_ = 0;
    int64_t poolBytes_ = 0;
};
