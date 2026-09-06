#include "native_frame_store.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <fstream>

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

bool colorMatches(const cv::Vec4b &rgba, uint32_t argb, int threshold) {
    const int red = static_cast<int>((argb >> 16U) & 0xffU);
    const int green = static_cast<int>((argb >> 8U) & 0xffU);
    const int blue = static_cast<int>(argb & 0xffU);
    return std::abs(static_cast<int>(rgba[0]) - red) <= threshold &&
           std::abs(static_cast<int>(rgba[1]) - green) <= threshold &&
           std::abs(static_cast<int>(rgba[2]) - blue) <= threshold;
}

std::string normalizedFormat(std::string format) {
    if (!format.empty() && format.front() == '.') {
        format.erase(format.begin());
    }
    std::transform(format.begin(), format.end(), format.begin(),
                   [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
    if (format == "jpeg") {
        format = "jpg";
    }
    return format;
}

bool encodeFrame(const cv::Mat &rgba, const std::string &requestedFormat, int quality,
                 std::vector<uint8_t> *bytes, std::string *error) {
    const std::string format = normalizedFormat(requestedFormat.empty() ? "png" : requestedFormat);
    if (format != "png" && format != "jpg" && format != "webp") {
        if (error != nullptr) {
            *error = "Unsupported image format: " + format;
        }
        return false;
    }
    cv::Mat writable;
    if (format == "jpg") {
        cv::cvtColor(rgba, writable, cv::COLOR_RGBA2BGR);
    } else {
        cv::cvtColor(rgba, writable, cv::COLOR_RGBA2BGRA);
    }
    quality = std::max(0, std::min(100, quality));
    std::vector<int> params;
    if (format == "jpg") {
        params = {cv::IMWRITE_JPEG_QUALITY, quality};
    } else if (format == "webp") {
        params = {cv::IMWRITE_WEBP_QUALITY, quality};
    } else {
        const int compression = 9 - static_cast<int>(std::lround(quality * 9.0 / 100.0));
        params = {cv::IMWRITE_PNG_COMPRESSION, compression};
    }
    try {
        if (!cv::imencode("." + format, writable, *bytes, params)) {
            if (error != nullptr) {
                *error = "OpenCV failed to encode image";
            }
            return false;
        }
    } catch (const cv::Exception &exception) {
        if (error != nullptr) {
            *error = exception.what();
        }
        return false;
    }
    return true;
}

}  // namespace

int64_t NativeFrameStore::createFromRgba(const uint8_t *data, int width, int height,
                                         int rowStride, int pixelStride, int targetShortEdge) {
    if (data == nullptr || width <= 0 || height <= 0 || pixelStride != 4 ||
        rowStride < width * pixelStride) {
        return 0;
    }
    cv::Mat view(height, width, CV_8UC4, const_cast<uint8_t *>(data),
                 static_cast<size_t>(rowStride));
    int outputWidth = width;
    int outputHeight = height;
    const int shortEdge = std::min(width, height);
    if (targetShortEdge > 0 && targetShortEdge < shortEdge) {
        const double scale = static_cast<double>(targetShortEdge) / shortEdge;
        outputWidth = std::max(1, static_cast<int>(std::lround(width * scale)));
        outputHeight = std::max(1, static_cast<int>(std::lround(height * scale)));
    }

    cv::Mat owned = takeReusable(outputWidth, outputHeight, CV_8UC4);
    owned.create(outputHeight, outputWidth, CV_8UC4);
    if (outputWidth != width || outputHeight != height) {
        // INTER_AREA is high quality but disproportionately slow for non-integer ratios on
        // several Android CPUs (1280 -> 720 was slower than copying the full frame). A capture
        // frame is immediately consumed by color/template/DNN operations, where bilinear quality
        // is sufficient and its stable latency matters more.
        cv::resize(view, owned, cv::Size(outputWidth, outputHeight), 0.0, 0.0, cv::INTER_LINEAR);
    } else if (rowStride == width * pixelStride && owned.isContinuous()) {
        std::memcpy(owned.data, data, static_cast<size_t>(width) * height * pixelStride);
    } else {
        const size_t rowBytes = static_cast<size_t>(width) * pixelStride;
        for (int row = 0; row < height; ++row) {
            std::memcpy(owned.ptr(row), data + static_cast<size_t>(row) * rowStride, rowBytes);
        }
    }
    return insert(std::move(owned), width, height);
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
    return insert(std::move(rgba), decoded.cols, decoded.rows);
}

int64_t NativeFrameStore::fromEncoded(const std::vector<uint8_t> &encoded,
                                      std::string *error) {
    cv::Mat encodedMat(static_cast<int>(encoded.size()), 1, CV_8UC1,
                       const_cast<uint8_t *>(encoded.data()));
    cv::Mat decoded = cv::imdecode(encodedMat, cv::IMREAD_UNCHANGED);
    if (decoded.empty()) {
        if (error != nullptr) {
            *error = "Unable to decode image from encoded data";
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
    return insert(std::move(rgba), decoded.cols, decoded.rows);
}

int64_t NativeFrameStore::copy(int64_t handle, std::string *error) {
    const auto frame = get(handle);
    if (frame == nullptr) {
        if (error != nullptr) *error = "NativeFrame has been recycled";
        return 0;
    }
    NativeFrameInfo info;
    getInfo(handle, &info);
    return insert(frame->clone(), info.logicalWidth, info.logicalHeight);
}

int64_t NativeFrameStore::clip(int64_t handle, int x, int y, int width, int height,
                               std::string *error) {
    const auto frame = get(handle);
    if (frame == nullptr) {
        if (error != nullptr) *error = "NativeFrame has been recycled";
        return 0;
    }
    if (x < 0 || y < 0 || width <= 0 || height <= 0 ||
        x + width > frame->cols || y + height > frame->rows) {
        if (error != nullptr) *error = "Clip rectangle is outside the source frame";
        return 0;
    }
    return insert((*frame)(cv::Rect(x, y, width, height)).clone());
}

int64_t NativeFrameStore::resize(int64_t handle, int width, int height, int interpolation,
                                 std::string *error) {
    const auto frame = get(handle);
    if (frame == nullptr) {
        if (error != nullptr) *error = "NativeFrame has been recycled";
        return 0;
    }
    if (width <= 0 || height <= 0 || width > 16384 || height > 16384) {
        if (error != nullptr) *error = "Resize dimensions must be between 1 and 16384";
        return 0;
    }
    static const int modes[] = {
            cv::INTER_NEAREST, cv::INTER_LINEAR, cv::INTER_CUBIC,
            cv::INTER_AREA, cv::INTER_LANCZOS4};
    const int mode = interpolation >= 0 && interpolation < 5
                     ? modes[interpolation] : cv::INTER_LINEAR;
    cv::Mat resized;
    cv::resize(*frame, resized, cv::Size(width, height), 0.0, 0.0, mode);
    return insert(std::move(resized));
}

int64_t NativeFrameStore::concat(int64_t firstHandle, int64_t secondHandle, int direction,
                                 std::string *error) {
    const auto first = get(firstHandle);
    if (first == nullptr) {
        if (error != nullptr) *error = "First NativeFrame has been recycled";
        return 0;
    }
    const auto second = get(secondHandle);
    if (second == nullptr) {
        if (error != nullptr) *error = "Second NativeFrame has been recycled";
        return 0;
    }
    cv::Mat combined;
    if (direction == 0) {
        if (first->rows != second->rows) {
            if (error != nullptr) *error = "Frames must have equal height for horizontal concat";
            return 0;
        }
        cv::hconcat(*first, *second, combined);
    } else {
        if (first->cols != second->cols) {
            if (error != nullptr) *error = "Frames must have equal width for vertical concat";
            return 0;
        }
        cv::vconcat(*first, *second, combined);
    }
    if (combined.empty()) {
        if (error != nullptr) *error = "Unable to concat frames";
        return 0;
    }
    return insert(std::move(combined), combined.cols, combined.rows);
}

int64_t NativeFrameStore::grayscale(int64_t handle, std::string *error) {
    const auto frame = get(handle);
    if (frame == nullptr) {
        if (error != nullptr) *error = "NativeFrame has been recycled";
        return 0;
    }
    cv::Mat gray;
    cv::Mat rgba;
    cv::cvtColor(*frame, gray, cv::COLOR_RGBA2GRAY);
    cv::cvtColor(gray, rgba, cv::COLOR_GRAY2RGBA);
    NativeFrameInfo info;
    getInfo(handle, &info);
    return insert(std::move(rgba), info.logicalWidth, info.logicalHeight);
}

int64_t NativeFrameStore::cvtColor(int64_t handle, const std::string &requestedCode,
                                   std::string *error) {
    std::string code = requestedCode;
    std::transform(code.begin(), code.end(), code.begin(),
                   [](unsigned char ch) { return static_cast<char>(std::toupper(ch)); });
    if (code.rfind("COLOR_", 0) == 0) {
        code.erase(0, 6);
    }
    if (code == "RGBA2GRAY" || code == "RGB2GRAY" || code == "BGR2GRAY" ||
        code == "BGRA2GRAY" || code == "GRAY") {
        return grayscale(handle, error);
    }
    const auto frame = get(handle);
    if (frame == nullptr) {
        if (error != nullptr) *error = "NativeFrame has been recycled";
        return 0;
    }
    if (code == "RGBA2BGR" || code == "RGBA2BGRA" || code == "RGB2BGR" ||
        code == "BGR2RGB" || code == "BGRA2RGBA") {
        cv::Mat swapped;
        cv::cvtColor(*frame, swapped, cv::COLOR_RGBA2BGRA);
        NativeFrameInfo info;
        getInfo(handle, &info);
        return insert(std::move(swapped), info.logicalWidth, info.logicalHeight);
    }
    if (code == "RGBA2RGB" || code == "RGB2RGBA" || code == "BGRA2BGR" ||
        code == "BGR2BGRA" || code == "RGBA") {
        NativeFrameInfo info;
        getInfo(handle, &info);
        return insert(frame->clone(), info.logicalWidth, info.logicalHeight);
    }
    if (error != nullptr) {
        *error = "Unsupported cvtColor code: " + requestedCode;
    }
    return 0;
}

int64_t NativeFrameStore::rotate(int64_t handle, int angle, std::string *error) {
    const auto frame = get(handle);
    if (frame == nullptr) {
        if (error != nullptr) *error = "NativeFrame has been recycled";
        return 0;
    }
    int code;
    switch (angle) {
        case 90:  code = cv::ROTATE_90_CLOCKWISE; break;
        case 180: code = cv::ROTATE_180; break;
        case 270: code = cv::ROTATE_90_COUNTERCLOCKWISE; break;
        default:
            if (error != nullptr) *error = "rotate angle must be 90, 180, or 270";
            return 0;
    }
    cv::Mat rotated;
    cv::rotate(*frame, rotated, code);
    NativeFrameInfo info;
    getInfo(handle, &info);
    // 90/270 swap width/height
    int lw = (angle == 90 || angle == 270) ? info.logicalHeight : info.logicalWidth;
    int lh = (angle == 90 || angle == 270) ? info.logicalWidth : info.logicalHeight;
    return insert(std::move(rotated), lw, lh);
}

int64_t NativeFrameStore::threshold(int64_t handle, double thresh, double maxValue,
                                    int type, std::string *error) {
    const auto frame = get(handle);
    if (frame == nullptr) {
        if (error != nullptr) *error = "NativeFrame has been recycled";
        return 0;
    }
    // Convert RGBA to grayscale for thresholding
    cv::Mat gray;
    cv::cvtColor(*frame, gray, cv::COLOR_RGBA2GRAY);
    cv::Mat binary;
    cv::threshold(gray, binary, thresh, maxValue, type);
    // Convert back to RGBA for consistent NativeFrame format
    cv::Mat rgba;
    cv::cvtColor(binary, rgba, cv::COLOR_GRAY2RGBA);
    NativeFrameInfo info;
    getInfo(handle, &info);
    return insert(std::move(rgba), info.logicalWidth, info.logicalHeight);
}

int64_t NativeFrameStore::blur(int64_t handle, int ksize, std::string *error) {
    const auto frame = get(handle);
    if (frame == nullptr) {
        if (error != nullptr) *error = "NativeFrame has been recycled";
        return 0;
    }
    if (ksize < 1 || ksize > 100) {
        if (error != nullptr) *error = "blur kernel size must be between 1 and 100";
        return 0;
    }
    // Ensure odd kernel size
    if (ksize % 2 == 0) ksize++;
    cv::Mat blurred;
    cv::GaussianBlur(*frame, blurred, cv::Size(ksize, ksize), 0);
    NativeFrameInfo info;
    getInfo(handle, &info);
    return insert(std::move(blurred), info.logicalWidth, info.logicalHeight);
}

bool NativeFrameStore::compress(int64_t handle, const std::string &format, int quality,
                                std::vector<uint8_t> *bytes, std::string *error) const {
    const auto frame = get(handle);
    if (frame == nullptr) {
        if (error != nullptr) *error = "NativeFrame has been recycled";
        return false;
    }
    return encodeFrame(*frame, format, quality, bytes, error);
}

bool NativeFrameStore::save(int64_t handle, const std::string &path, const std::string &format,
                            int quality, std::string *error) const {
    std::vector<uint8_t> bytes;
    if (!compress(handle, format, quality, &bytes, error)) {
        return false;
    }
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) {
        if (error != nullptr) *error = "Unable to open image output: " + path;
        return false;
    }
    output.write(reinterpret_cast<const char *>(bytes.data()),
                 static_cast<std::streamsize>(bytes.size()));
    if (!output.good()) {
        if (error != nullptr) *error = "Unable to write image output: " + path;
        return false;
    }
    return true;
}

int64_t NativeFrameStore::insert(cv::Mat frame, int logicalWidth, int logicalHeight) {
    if (frame.empty()) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    const int64_t handle = nextHandle_++;
    const int pixelWidth = frame.cols;
    const int pixelHeight = frame.rows;
    frames_.emplace(handle, std::make_shared<cv::Mat>(std::move(frame)));
    frameInfo_.emplace(handle, NativeFrameInfo{
            pixelWidth, pixelHeight,
            logicalWidth > 0 ? logicalWidth : pixelWidth,
            logicalHeight > 0 ? logicalHeight : pixelHeight});
    ++createCount_;
    return handle;
}

cv::Mat NativeFrameStore::takeReusable(int width, int height, int type) {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto it = reusableFrames_.begin(); it != reusableFrames_.end(); ++it) {
        if (it->cols == width && it->rows == height && it->type() == type) {
            cv::Mat result = std::move(*it);
            poolBytes_ -= static_cast<int64_t>(result.step[0]) * result.rows;
            reusableFrames_.erase(it);
            ++reuseCount_;
            return result;
        }
    }
    ++rejectCount_;
    return {};
}

bool NativeFrameStore::release(int64_t handle) {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto it = frames_.find(handle);
    if (it == frames_.end()) return false;
    if (it->second.use_count() == 1 && reusableFrames_.size() < MAX_POOL_COUNT) {
        int64_t matBytes = static_cast<int64_t>(it->second->step[0]) * it->second->rows;
        if (poolBytes_ + matBytes <= MAX_POOL_BYTES) {
            poolBytes_ += matBytes;
            reusableFrames_.push_back(std::move(*it->second));
        } else {
            ++rejectCount_;
        }
    } else {
        ++rejectCount_;
    }
    frames_.erase(it);
    frameInfo_.erase(handle);
    return true;
}

bool NativeFrameStore::getInfo(int64_t handle, NativeFrameInfo *info) const {
    if (info == nullptr) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    const auto it = frameInfo_.find(handle);
    if (it == frameInfo_.end()) return false;
    *info = it->second;
    return true;
}

std::shared_ptr<const cv::Mat> NativeFrameStore::get(int64_t handle) const {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto it = frames_.find(handle);
    return it == frames_.end() ? nullptr : it->second;
}

void NativeFrameStore::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    frames_.clear();
    frameInfo_.clear();
    reusableFrames_.clear();
    poolBytes_ = 0;
}

NativeFrameStore::Stats NativeFrameStore::stats() const {
    std::lock_guard<std::mutex> lock(mutex_);
    Stats s;
    s.activeFrames = static_cast<int>(frames_.size());
    s.activeHandles = static_cast<int64_t>(frameInfo_.size());
    s.poolCount = static_cast<int>(reusableFrames_.size());
    s.poolBytes = poolBytes_;
    s.createCount = createCount_;
    s.reuseCount = reuseCount_;
    s.rejectCount = rejectCount_;
    return s;
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
    for (int row = y; row < y + height; ++row) {
        const cv::Vec4b *pixels = frame->ptr<cv::Vec4b>(row);
        for (int col = x; col < x + width; ++col) {
            const cv::Vec4b &rgba = pixels[col];
            if (colorMatches(rgba, argb, threshold)) {
                point->x = col;
                point->y = row;
                point->similarity = 1.0;
                return true;
            }
        }
    }
    return false;
}

bool NativeFrameStore::findMultiColors(
        int64_t handle, uint32_t firstColor,
        const std::vector<NativeFrameColorOffset> &offsets, int threshold,
        int x, int y, int width, int height, NativeFramePoint *point) const {
    const auto frame = get(handle);
    if (frame == nullptr || !normalizeRegion(*frame, &x, &y, &width, &height)) {
        return false;
    }
    threshold = std::max(0, std::min(255, threshold));
    for (int row = y; row < y + height; ++row) {
        const cv::Vec4b *pixels = frame->ptr<cv::Vec4b>(row);
        for (int col = x; col < x + width; ++col) {
            if (!colorMatches(pixels[col], firstColor, threshold)) continue;
            bool matched = true;
            for (const auto &offset : offsets) {
                const int targetX = col + offset.x;
                const int targetY = row + offset.y;
                if (targetX < 0 || targetY < 0 ||
                    targetX >= frame->cols || targetY >= frame->rows ||
                    !colorMatches(frame->at<cv::Vec4b>(targetY, targetX),
                                  offset.argb, threshold)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
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

bool NativeFrameStore::matchTemplate(
        int64_t sourceHandle, int64_t templateHandle, double threshold, int maxMatches,
        int x, int y, int width, int height, std::vector<NativeFramePoint> *matches,
        std::string *error) const {
    const auto source = get(sourceHandle);
    const auto templ = get(templateHandle);
    if (source == nullptr || templ == nullptr) {
        if (error != nullptr) *error = "NativeFrame has been recycled";
        return false;
    }
    if (!normalizeRegion(*source, &x, &y, &width, &height)) {
        if (error != nullptr) *error = "Search region is outside the source frame";
        return false;
    }
    if (templ->cols > width || templ->rows > height) {
        if (error != nullptr) *error = "Template is larger than the search region";
        return false;
    }
    threshold = std::max(0.0, std::min(1.0, threshold));
    maxMatches = std::max(1, std::min(1000, maxMatches));
    cv::Mat sourceRgb;
    cv::Mat templateRgb;
    cv::cvtColor((*source)(cv::Rect(x, y, width, height)), sourceRgb, cv::COLOR_RGBA2RGB);
    cv::cvtColor(*templ, templateRgb, cv::COLOR_RGBA2RGB);
    cv::Mat scores;
    cv::matchTemplate(sourceRgb, templateRgb, scores, cv::TM_CCOEFF_NORMED);
    for (int index = 0; index < maxMatches; ++index) {
        double maxScore = 0.0;
        cv::Point maxLocation;
        cv::minMaxLoc(scores, nullptr, &maxScore, nullptr, &maxLocation);
        if (!std::isfinite(maxScore) || maxScore < threshold) break;
        matches->push_back({x + maxLocation.x, y + maxLocation.y, maxScore});
        const int left = std::max(0, maxLocation.x - templ->cols / 2);
        const int top = std::max(0, maxLocation.y - templ->rows / 2);
        const int right = std::min(scores.cols, maxLocation.x + (templ->cols + 1) / 2);
        const int bottom = std::min(scores.rows, maxLocation.y + (templ->rows + 1) / 2);
        scores(cv::Rect(left, top, std::max(1, right - left),
                        std::max(1, bottom - top))).setTo(-1.0f);
    }
    return true;
}
