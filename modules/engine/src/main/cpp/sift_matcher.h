#pragma once
#include <cstdint>
#include <memory>
#include <opencv2/core.hpp>

// Holds the store's shared_ptr, preventing recycle/pool reuse during SIFT.
std::shared_ptr<const cv::Mat> aijsSiftBorrowFrame(int64_t engine, int64_t frame);
