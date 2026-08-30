#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#include <opencv2/core.hpp>

struct NativeFramePoint {
    int x = 0;
    int y = 0;
    double similarity = 0.0;
};

class NativeFrameStore {
public:
    int64_t createFromRgba(const uint8_t *data, int width, int height,
                           int rowStride, int pixelStride);
    int64_t load(const std::string &path, std::string *error);
    bool release(int64_t handle);
    std::shared_ptr<const cv::Mat> get(int64_t handle) const;
    void clear();

    bool pixel(int64_t handle, int x, int y, uint32_t *argb) const;
    bool findColor(int64_t handle, uint32_t argb, int threshold,
                   int x, int y, int width, int height,
                   NativeFramePoint *point) const;
    bool findImage(int64_t sourceHandle, int64_t templateHandle, double threshold,
                   int x, int y, int width, int height,
                   NativeFramePoint *point, std::string *error) const;

private:
    int64_t insert(cv::Mat frame);

    mutable std::mutex mutex_;
    std::unordered_map<int64_t, std::shared_ptr<cv::Mat>> frames_;
    int64_t nextHandle_ = 1;
};
