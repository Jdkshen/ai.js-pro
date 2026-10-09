#pragma once

#include <opencv2/core.hpp>
#include <string>
#include <vector>

struct XiangqiCircle {
    float x = 0, y = 0, radius = 0, redFraction = 0, error = 0;
    int col = 0, row = 0;
    std::string color;
};

struct XiangqiBoardAnalysis {
    bool ok = false;
    std::string reason;
    cv::Rect outer;
    double originX = 0, originY = 0, stepX = 0, stepY = 0;
    double lineScoreX = 0, lineScoreY = 0;
    int verticalSegments = 0, horizontalSegments = 0;
    std::vector<XiangqiCircle> circles;
};

// Frame is NativeFrameStore's RGBA image. This runs entirely on-device.
XiangqiBoardAnalysis analyzeXiangqiBoard(const cv::Mat &rgba);

// Use an already calibrated grid for cheap per-frame occupancy/color verification.
std::vector<XiangqiCircle> detectXiangqiCircles(const cv::Mat &rgba,
                                                double originX, double originY,
                                                double stepX, double stepY);
