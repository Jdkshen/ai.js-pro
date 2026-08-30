#include "native_frame_store.h"

#include <algorithm>
#include <cmath>

#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

namespace {

bool normalizeRegion(const cv::Mat &frame, int *x, int *y, int *width, int *height) {
    if (*x < 0 || *y < 0 || *x >= frame.cols || *y >= frame.rows) {
        return false;
    }
    if (*width <= 0) {
        *width = frame.cols - *x;
    }
    if (*height <= 0) {
        *height = frame.rows - *y;
    }
    *width = std::min(*width, frame.cols - *x);
    *height = std::min(*height, frame.rows - *y);
    return *width > 0 && *height > 0;
}

cv::Mat toRgba(const cv::Mat &input) {
    cv::Mat rgba;
    switch (input.channels()) {
        case 1:
            cv::cvtColor(input, rgba, cv::COLOR_GRAY2RGBA);
            break;
        case 3:
            cv::cvtColor(input, rgba, cv::COLOR_BGR2RGBA);
            break;
        case 4:
            cv::cvtColor(input, rgba, cv::COLOR_BGRA2RGBA);
            break;
        default:
            break;
    }
    return rgba;
}

}  // namespace

int64_t NativeFrameStore::createFromRgba(const uint8_t *data, int width, int height,
                                         int rowStride, int pixelStride) {
    if (data == nullptr || width <= 0 || height <= 0 || pixelStride != 4 ||
        rowStride < width * pixelStride) {
        return 0;
    }
    cv::Mat view(height, width, CV_8UC4, const_cast<uint8_t *>(data),
                 static_cast<size_t>(rowStride));
    return insert(view.clone());
}

int64_t NativeFrameStore::load(const std::string &path, std::string *error) {
    cv::Mat decoded = cv::imread(path, cv::IMREAD_UNCHANGED);
    if (decoded.empty()) {
        if (error != nullptr) {
            *error = "Unable to decode image: " + path;
        }
        return 0;
    }
    cv::Mat rgba = toRgba(decoded);
    if (rgba.empty()) {
        if (error != nullptr) {
            *error = "Unsupported image channel count: " + std::to_string(decoded.channels());
        }
        return 0;
    }
    return insert(std::move(rgba));
}

int64_t NativeFrameStore::insert(cv::Mat frame) {
    if (frame.empty()) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    const int64_t handle = nextHandle_++;
    frames_.emplace(handle, std::make_shared<cv::Mat>(std::move(frame)));
    return handle;
}

bool NativeFrameStore::release(int64_t handle) {
    std::lock_guard<std::mutex> lock(mutex_);
    return frames_.erase(handle) > 0;
}

std::shared_ptr<const cv::Mat> NativeFrameStore::get(int64_t handle) const {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto it = frames_.find(handle);
    return it == frames_.end() ? nullptr : it->second;
}

void NativeFrameStore::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    frames_.clear();
}

bool NativeFrameStore::pixel(int64_t handle, int x, int y, uint32_t *argb) const {
    const auto frame = get(handle);
    if (frame == nullptr || x < 0 || y < 0 || x >= frame->cols || y >= frame->rows) {
        return false;
    }
    const cv::Vec4b rgba = frame->at<cv::Vec4b>(y, x);
    *argb = (static_cast<uint32_t>(rgba[3]) << 24U) |
            (static_cast<uint32_t>(rgba[0]) << 16U) |
            (static_cast<uint32_t>(rgba[1]) << 8U) |
            static_cast<uint32_t>(rgba[2]);
    return true;
}

bool NativeFrameStore::findColor(int64_t handle, uint32_t argb, int threshold,
                                 int x, int y, int width, int height,
                                 NativeFramePoint *point) const {
    const auto frame = get(handle);
    if (frame == nullptr || !normalizeRegion(*frame, &x, &y, &width, &height)) {
        return false;
    }
    threshold = std::max(0, std::min(255, threshold));
    const int red = static_cast<int>((argb >> 16U) & 0xffU);
    const int green = static_cast<int>((argb >> 8U) & 0xffU);
    const int blue = static_cast<int>(argb & 0xffU);
    for (int row = y; row < y + height; ++row) {
        const cv::Vec4b *pixels = frame->ptr<cv::Vec4b>(row);
        for (int col = x; col < x + width; ++col) {
            const cv::Vec4b &rgba = pixels[col];
            if (std::abs(static_cast<int>(rgba[0]) - red) <= threshold &&
                std::abs(static_cast<int>(rgba[1]) - green) <= threshold &&
                std::abs(static_cast<int>(rgba[2]) - blue) <= threshold) {
                point->x = col;
                point->y = row;
                point->similarity = 1.0;
                return true;
            }
        }
    }
    return false;
}

bool NativeFrameStore::findImage(int64_t sourceHandle, int64_t templateHandle, double threshold,
                                 int x, int y, int width, int height,
                                 NativeFramePoint *point, std::string *error) const {
    const auto source = get(sourceHandle);
    const auto templ = get(templateHandle);
    if (source == nullptr || templ == nullptr) {
        if (error != nullptr) {
            *error = "NativeFrame has been recycled";
        }
        return false;
    }
    if (!normalizeRegion(*source, &x, &y, &width, &height)) {
        if (error != nullptr) {
            *error = "Search region is outside the source frame";
        }
        return false;
    }
    if (templ->cols > width || templ->rows > height) {
        if (error != nullptr) {
            *error = "Template is larger than the search region";
        }
        return false;
    }

    cv::Mat sourceRgb;
    cv::Mat templateRgb;
    cv::cvtColor((*source)(cv::Rect(x, y, width, height)), sourceRgb, cv::COLOR_RGBA2RGB);
    cv::cvtColor(*templ, templateRgb, cv::COLOR_RGBA2RGB);
    cv::Mat scores;
    cv::matchTemplate(sourceRgb, templateRgb, scores, cv::TM_CCOEFF_NORMED);
    double maxScore = 0.0;
    cv::Point maxLocation;
    cv::minMaxLoc(scores, nullptr, &maxScore, nullptr, &maxLocation);
    point->x = x + maxLocation.x;
    point->y = y + maxLocation.y;
    point->similarity = maxScore;
    return maxScore >= std::max(0.0, std::min(1.0, threshold));
}
