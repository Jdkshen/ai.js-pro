#include "xiangqi_board_detector.h"

#include <algorithm>
#include <cmath>
#include <map>
#include <opencv2/imgproc.hpp>

namespace {

// Smooth a one-dimensional support trace, then choose its longest continuous band.
cv::Rect longestBand(const std::vector<double> &support, int window, double threshold) {
    if (support.empty()) return {};
    const int half = window / 2;
    std::vector<double> prefix(support.size() + 1, 0);
    for (size_t i = 0; i < support.size(); ++i) prefix[i + 1] = prefix[i] + support[i];
    int bestStart = 0, bestLength = 0, start = -1;
    for (int i = 0; i <= static_cast<int>(support.size()); ++i) {
        bool on = false;
        if (i < static_cast<int>(support.size())) {
            int lo = std::max(0, i - half), hi = std::min(static_cast<int>(support.size()), i + half + 1);
            // np.convolve(..., mode='same') uses a fixed kernel denominator at borders.
            on = (prefix[hi] - prefix[lo]) / window > threshold;
        }
        if (on && start < 0) start = i;
        if (!on && start >= 0) {
            if (i - start > bestLength) { bestStart = start; bestLength = i - start; }
            start = -1;
        }
    }
    return cv::Rect(bestStart, 0, bestLength, 1);
}

double interpolated(const cv::Mat &votes, double coordinate, bool horizontal) {
    const int length = horizontal ? votes.cols : votes.rows;
    if (coordinate < 0 || coordinate >= length - 1) return 0;
    const int lo = static_cast<int>(coordinate);
    const double a = coordinate - lo;
    const float left = horizontal ? votes.at<float>(0, lo) : votes.at<float>(lo, 0);
    const float right = horizontal ? votes.at<float>(0, lo + 1) : votes.at<float>(lo + 1, 0);
    return left * (1 - a) + right * a;
}

struct AxisFit { double origin = 0, step = 0, score = -1; int supported = 0; };

AxisFit fitAxis(const cv::Mat &votes, int boardStart, int boardLength,
                int count, double originMin, double originMax, bool horizontal) {
    AxisFit best;
    const int length = horizontal ? votes.cols : votes.rows;
    for (double origin = boardStart + boardLength * originMin;
         origin < boardStart + boardLength * originMax; origin += 1) {
        for (double step = boardLength * .09; step < boardLength * .12; step += .25) {
            if (origin + (count - 1) * step >= length) continue;
            double score = 0;
            int supported = 0;
            for (int i = 0; i < count; ++i) {
                const double vote = interpolated(votes, origin + i * step, horizontal);
                score += std::log1p(vote);
                if (vote >= 2) ++supported;
            }
            if (score > best.score) best = {origin, step, score, supported};
        }
    }
    return best;
}

double redInkFraction(const cv::Mat &rgba, const cv::Vec3f &circle) {
    const int cx = cvRound(circle[0]), cy = cvRound(circle[1]);
    const int radius = std::max(5, cvRound(circle[2] * .52));
    const double limit = std::pow(radius * .85, 2);
    int red = 0, ink = 0;
    for (int dy = -radius; dy <= radius; ++dy) {
        const int y = cy + dy;
        if (y < 0 || y >= rgba.rows) continue;
        const auto *line = rgba.ptr<cv::Vec4b>(y);
        for (int dx = -radius; dx <= radius; ++dx) {
            const int x = cx + dx;
            if (x < 0 || x >= rgba.cols || dx * dx + dy * dy >= limit) continue;
            const auto &pixel = line[x];
            const int r = pixel[0], g = pixel[1], b = pixel[2];
            const int gray = (77 * r + 150 * g + 29 * b) >> 8;
            if (gray >= 150) continue;
            ++ink;
            if (100 * r > 125 * g && 100 * r > 145 * b) ++red;
        }
    }
    return ink ? static_cast<double>(red) / ink : 0;
}

} // namespace

XiangqiBoardAnalysis analyzeXiangqiBoard(const cv::Mat &rgba) {
    XiangqiBoardAnalysis result;
    if (rgba.empty() || rgba.type() != CV_8UC4 || rgba.cols < 400 || rgba.rows < 500) {
        result.reason = "需要至少 400x500 的 RGBA 帧";
        return result;
    }
    std::vector<double> rows(rgba.rows, 0);
    for (int y = 0; y < rgba.rows; ++y) {
        const auto *line = rgba.ptr<cv::Vec4b>(y);
        int wood = 0;
        for (int x = 0; x < rgba.cols; ++x) {
            const int r = line[x][0], g = line[x][1], b = line[x][2];
            wood += r > 130 && g > 105 && 100 * r > 106 * g && 100 * g > 107 * b;
        }
        rows[y] = static_cast<double>(wood) / rgba.cols;
    }
    const cv::Rect rowBand = longestBand(rows, 31, .5);
    if (rowBand.width < rgba.rows * .25) {
        result.reason = "木色棋盘的连续横带不足";
        return result;
    }
    const int by = rowBand.x, bh = rowBand.width;
    std::vector<double> cols(rgba.cols, 0);
    for (int y = by; y < by + bh; ++y) {
        const auto *line = rgba.ptr<cv::Vec4b>(y);
        for (int x = 0; x < rgba.cols; ++x) {
            const int r = line[x][0], g = line[x][1], b = line[x][2];
            cols[x] += r > 130 && g > 105 && 100 * r > 106 * g && 100 * g > 107 * b;
        }
    }
    for (double &value : cols) value /= bh;
    const cv::Rect colBand = longestBand(cols, 15, .5);
    if (colBand.width < rgba.cols * .6) {
        result.reason = "木色棋盘的连续宽度不足";
        return result;
    }
    const int bx = colBand.x, bw = colBand.width;
    result.outer = cv::Rect(bx, by, bw, bh);

    cv::Mat gray, boardGray, blurred, edges;
    cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);
    boardGray = gray(result.outer);
    cv::GaussianBlur(boardGray, blurred, cv::Size(5, 5), 1);
    cv::Canny(blurred, edges, 50, 120);
    std::vector<cv::Vec4i> segments;
    const double scale = std::min(bw / 1080.0, bh / 1200.0);
    const int minLine = std::max(45, cvRound(90 * scale));
    cv::HoughLinesP(edges, segments, 1, CV_PI / 180, 40,
                    minLine, std::max(12, cvRound(25 * scale)));
    if (segments.empty()) { result.reason = "Canny 后没有可用格线"; return result; }
    cv::Mat xv = cv::Mat::zeros(1, rgba.cols, CV_32F);
    cv::Mat yv = cv::Mat::zeros(rgba.rows, 1, CV_32F);
    for (const auto &line : segments) {
        const int dx = std::abs(line[2] - line[0]), dy = std::abs(line[3] - line[1]);
        if (dx <= 2 && dy >= minLine) {
            const int x = cvRound((line[0] + line[2]) / 2.0) + bx;
            if (x >= 0 && x < rgba.cols) xv.at<float>(0, x) += dy;
            ++result.verticalSegments;
        }
        if (dy <= 2 && dx >= minLine) {
            const int y = cvRound((line[1] + line[3]) / 2.0) + by;
            if (y >= 0 && y < rgba.rows) yv.at<float>(y, 0) += dx;
            ++result.horizontalSegments;
        }
    }
    cv::GaussianBlur(xv, xv, cv::Size(), 3);
    cv::GaussianBlur(yv, yv, cv::Size(), 3);
    const AxisFit x = fitAxis(xv, bx, bw, 9, .045, .105, true);
    const AxisFit y = fitAxis(yv, by, bh, 10, .045, .10, false);
    result.lineScoreX = x.score;
    result.lineScoreY = y.score;
    if (x.step <= 0 || y.step <= 0 || x.supported < 5 || y.supported < 5 ||
        std::abs(x.step - y.step) / std::max(x.step, y.step) > .08) {
        result.reason = "格线证据不足或横竖格距不一致";
        return result;
    }
    result.originX = x.origin;
    result.originY = y.origin;
    result.stepX = x.step;
    result.stepY = y.step;
    result.circles = detectXiangqiCircles(rgba, x.origin, y.origin, x.step, y.step);
    result.ok = true;
    return result;
}

std::vector<XiangqiCircle> detectXiangqiCircles(const cv::Mat &rgba,
                                                double originX, double originY,
                                                double stepX, double stepY) {
    std::vector<XiangqiCircle> output;
    if (rgba.empty() || rgba.type() != CV_8UC4 || stepX < 20 || stepY < 20) return output;
    const double pitch = (stepX + stepY) / 2;
    const int left = std::max(0, cvFloor(originX - stepX * .7));
    const int top = std::max(0, cvFloor(originY - stepY * .7));
    const int right = std::min(rgba.cols, cvCeil(originX + stepX * 8.7));
    const int bottom = std::min(rgba.rows, cvCeil(originY + stepY * 9.7));
    if (right <= left || bottom <= top) return output;
    cv::Mat gray;
    cv::cvtColor(rgba(cv::Rect(left, top, right - left, bottom - top)), gray, cv::COLOR_RGBA2GRAY);
    std::vector<cv::Vec3f> found;
    cv::HoughCircles(gray, found, cv::HOUGH_GRADIENT, 1.2,
                     std::max(1, cvRound(pitch * .64)), 110,
                     std::max(25, cvRound(pitch * .39)),
                     cvRound(pitch * .30), cvRound(pitch * .49));
    std::map<int, XiangqiCircle> assigned;
    for (const auto &local : found) {
        const float cx = local[0] + left, cy = local[1] + top;
        const int col = cvRound((cx - originX) / stepX);
        const int row = cvRound((cy - originY) / stepY);
        if (col < 0 || col > 8 || row < 0 || row > 9) continue;
        const double ex = std::abs(cx - (originX + col * stepX)) / stepX;
        const double ey = std::abs(cy - (originY + row * stepY)) / stepY;
        if (ex > .27 || ey > .27) continue;
        const cv::Vec3f absolute(cx, cy, local[2]);
        XiangqiCircle circle;
        circle.x = cx; circle.y = cy; circle.radius = local[2];
        circle.col = col; circle.row = row; circle.error = ex + ey;
        circle.redFraction = redInkFraction(rgba, absolute);
        circle.color = circle.redFraction > .5 ? "red" : "black";
        const int key = row * 9 + col;
        const auto previous = assigned.find(key);
        if (previous == assigned.end() || circle.error < previous->second.error) assigned[key] = circle;
    }
    for (const auto &entry : assigned) output.push_back(entry.second);
    return output;
}
