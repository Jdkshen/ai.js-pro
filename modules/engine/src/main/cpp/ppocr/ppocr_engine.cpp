// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include "ppocr_engine.h"

#include "cpu.h"
#include "net.h"

#include <opencv2/core/core.hpp>
#include <opencv2/geometry/2d.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include <algorithm>
#include <cmath>
#include <fstream>
#include <string>

#include "ppocrv5_dict.h"

static bool path_contains(const char* path, const char* needle)
{
    if (!path || !needle) return false;
    return std::string(path).find(needle) != std::string::npos;
}

// 取模型文件所在目录，用来查找 v6 字典文件。
static std::string parent_dir(const char* path)
{
    std::string s = path ? path : "";
    size_t pos = s.find_last_of("/\\");
    if (pos == std::string::npos) return ".";
    return s.substr(0, pos);
}

// 读取 v6 字典，每行一个 token。
static bool load_lines(const std::string& path, std::vector<std::string>& lines)
{
    std::ifstream in(path);
    if (!in.is_open()) return false;
    lines.clear();
    std::string line;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        lines.push_back(line);
    }
    return !lines.empty();
}

// 计算检测框轮廓在二值图上的平均分数，用于过滤低质量文字框。
static double contour_score(const cv::Mat& binary, const std::vector<cv::Point>& contour)
{
    cv::Rect rect = cv::boundingRect(contour);
    if (rect.x < 0)
        rect.x = 0;
    if (rect.y < 0)
        rect.y = 0;
    if (rect.x + rect.width > binary.cols)
        rect.width = binary.cols - rect.x;
    if (rect.y + rect.height > binary.rows)
        rect.height = binary.rows - rect.y;
    if (rect.width <= 0 || rect.height <= 0)
        return 0.0;

    cv::Mat binROI = binary(rect);

    cv::Mat mask = cv::Mat::zeros(rect.height, rect.width, CV_8U);
    std::vector<cv::Point> roiContour;
    for (size_t i = 0; i < contour.size(); i++)
    {
        cv::Point pt = cv::Point(contour[i].x - rect.x, contour[i].y - rect.y);
        roiContour.push_back(pt);
    }

    std::vector<std::vector<cv::Point> > roiContours = {roiContour};
    cv::fillPoly(mask, roiContours, cv::Scalar(255));

    double score = cv::mean(binROI, mask).val[0];
    return score / 255.f;
}

// 把旋转文字框裁剪成识别模型需要的 48 高小图。
// 这里做了宽高/NaN/空图保护，避免异常检测框导致 warpAffine 崩溃。
static cv::Mat get_rotate_crop_image(const cv::Mat& rgb, const Object& object, int max_width)
{
    const int orientation = object.orientation;
    const float rw = object.rrect.size.width;
    const float rh = object.rrect.size.height;

    const int target_height = 48;
    if (rgb.empty() || !std::isfinite(rw) || !std::isfinite(rh) || rw < 1.f || rh < 1.f) {
        return cv::Mat();
    }
    float target_width_f = rh * target_height / rw;
    if (!std::isfinite(target_width_f) || target_width_f < 1.f) {
        return cv::Mat();
    }
    int target_width = std::max(1, std::min(static_cast<int>(std::lround(target_width_f)),
                                     max_width > 0 ? max_width : 1024));

    // warpperspective shall be used to rotate the image
    // but actually they are all rectangles, so warpaffine is almost enough  :P

    cv::Mat dst;

    cv::Point2f corners[4];
    object.rrect.points(corners);

    if (orientation == 0)
    {
        // horizontal text
        // corner points order
        //  0--------1
        //  |        |rw  -> as angle=90
        //  3--------2
        //      rh

        std::vector<cv::Point2f> src_pts(3);
        src_pts[0] = corners[0];
        src_pts[1] = corners[1];
        src_pts[2] = corners[3];

        std::vector<cv::Point2f> dst_pts(3);
        dst_pts[0] = cv::Point2f(0, 0);
        dst_pts[1] = cv::Point2f(target_width, 0);
        dst_pts[2] = cv::Point2f(0, target_height);

        cv::Mat tm = cv::getAffineTransform(src_pts, dst_pts);

        cv::warpAffine(rgb, dst, tm, cv::Size(target_width, target_height), cv::INTER_LINEAR, cv::BORDER_REPLICATE);
    }
    else
    {
        // vertial text
        // corner points order
        //  1----2
        //  |    |
        //  |    |
        //  |    |rh  -> as angle=0
        //  |    |
        //  |    |
        //  0----3
        //    rw

        std::vector<cv::Point2f> src_pts(3);
        src_pts[0] = corners[2];
        src_pts[1] = corners[3];
        src_pts[2] = corners[1];

        std::vector<cv::Point2f> dst_pts(3);
        dst_pts[0] = cv::Point2f(0, 0);
        dst_pts[1] = cv::Point2f(target_width, 0);
        dst_pts[2] = cv::Point2f(0, target_height);

        cv::Mat tm = cv::getAffineTransform(src_pts, dst_pts);

        cv::warpAffine(rgb, dst, tm, cv::Size(target_width, target_height), cv::INTER_LINEAR, cv::BORDER_REPLICATE);
    }

    return dst;
}

PPOCREngine::PPOCREngine()
{
    // 构造默认值，真正运行时会由 JS/nativeSetRuntimeOptions 设置 target_size。
    target_size = 960;
    ppocrv6_mode = false;
}

PPOCREngine::~PPOCREngine()
{
    ppocrv5_det.clear();
    ppocrv5_rec.clear();
    clear_runtime_memory();
    dynamic_dict.clear();
}

int PPOCREngine::load(const char* det_parampath, const char* det_modelpath, const char* rec_parampath, const char* rec_modelpath, bool use_fp16, bool use_gpu)
{
    // 加载新模型前先清空旧模型和字典。
    ppocrv5_det.clear();
    ppocrv5_rec.clear();
    dynamic_dict.clear();
    // 通过文件名判断 v5/v6。v6 需要额外加载动态字典。
    ppocrv6_mode = path_contains(det_parampath, "OCRv6") || path_contains(rec_parampath, "OCRv6");
    if (ppocrv6_mode) {
        load_lines(parent_dir(rec_parampath) + "/ppocr_keys_v6_tiny.txt", dynamic_dict);
    }

    // FP16 在部分设备上不稳定，所以由 JS 默认 false。
    ppocrv5_det.opt.use_fp16_packed = use_fp16;
    ppocrv5_det.opt.use_fp16_storage = use_fp16;
    ppocrv5_det.opt.use_fp16_arithmetic = false;

#if NCNN_VULKAN
    ppocrv5_det.opt.use_vulkan_compute = use_gpu;
#endif

    int ret = ppocrv5_det.load_param(det_parampath);
    if (ret != 0) return -101;
    ret = ppocrv5_det.load_model(det_modelpath);
    if (ret != 0) return -102;

    // rec 模型内部默认 1 线程，桥接层也串行识别，避免共享模型并发崩溃。
    ppocrv5_rec.opt.num_threads = 1;

    ppocrv5_rec.opt.use_fp16_packed = use_fp16;
    ppocrv5_rec.opt.use_fp16_storage = use_fp16;
    ppocrv5_rec.opt.use_fp16_arithmetic = false;

#if NCNN_VULKAN
    ppocrv5_rec.opt.use_vulkan_compute = use_gpu;
#endif

    ret = ppocrv5_rec.load_param(rec_parampath);
    if (ret != 0) return -103;
    ret = ppocrv5_rec.load_model(rec_modelpath);
    if (ret != 0) return -104;

    return 0;
}

int PPOCREngine::load(AAssetManager* mgr, const char* det_parampath, const char* det_modelpath, const char* rec_parampath, const char* rec_modelpath, bool use_fp16, bool use_gpu)
{
    ppocrv5_det.clear();
    ppocrv5_rec.clear();
    dynamic_dict.clear();
    ppocrv6_mode = path_contains(det_parampath, "OCRv6") || path_contains(rec_parampath, "OCRv6");

    ppocrv5_det.opt.use_fp16_packed = use_fp16;
    ppocrv5_det.opt.use_fp16_storage = use_fp16;
    ppocrv5_det.opt.use_fp16_arithmetic = false;

#if NCNN_VULKAN
    ppocrv5_det.opt.use_vulkan_compute = use_gpu;
#endif

    int ret = ppocrv5_det.load_param(mgr, det_parampath);
    if (ret != 0) return -201;
    ret = ppocrv5_det.load_model(mgr, det_modelpath);
    if (ret != 0) return -202;

    // default to 1 thread, as we rec multiple lines in parallel
    ppocrv5_rec.opt.num_threads = 1;

    ppocrv5_rec.opt.use_fp16_packed = use_fp16;
    ppocrv5_rec.opt.use_fp16_storage = use_fp16;
    ppocrv5_rec.opt.use_fp16_arithmetic = false;

#if NCNN_VULKAN
    ppocrv5_rec.opt.use_vulkan_compute = use_gpu;
#endif

    ret = ppocrv5_rec.load_param(mgr, rec_parampath);
    if (ret != 0) return -203;
    ret = ppocrv5_rec.load_model(mgr, rec_modelpath);
    if (ret != 0) return -204;

    return 0;
}

void PPOCREngine::set_target_size(int _target_size)
{
    // 检测阶段会按这个尺寸缩放输入图。
    target_size = _target_size;
}

void PPOCREngine::set_rec_max_width(int max_width)
{
    // rec 裁剪宽度上限；<=0 视为用默认值。
    rec_max_width = max_width > 0 ? max_width : 1024;
}

void PPOCREngine::clear_runtime_memory()
{
    // 清理 ncnn 临时内存池，减少长时间循环 OCR 的内存积累。
    det_blob_allocator.clear();
    det_workspace_allocator.clear();
    rec_blob_allocator.clear();
    rec_workspace_allocator.clear();
}

int PPOCREngine::detect(const cv::Mat& rgb, std::vector<Object>& objects)
{
    // OpenCV 线程数不要太高，避免和 ncnn/Auto.js 抢资源。
    cv::setNumThreads(2);

    int img_w = rgb.cols;
    int img_h = rgb.rows;

    const int target_stride = 32;

    // 按 target_size 缩放，并 pad 到模型 stride 的倍数。
    int w = img_w;
    int h = img_h;
    float scale = 1.f;
    if (std::max(w, h) > target_size)
    {
        if (w > h)
        {
            scale = (float)target_size / w;
            w = target_size;
            h = h * scale;
        }
        else
        {
            scale = (float)target_size / h;
            h = target_size;
            w = w * scale;
        }
    }

    ncnn::Mat in = ncnn::Mat::from_pixels_resize(rgb.data, ncnn::Mat::PIXEL_BGR, img_w, img_h, w, h);

    int wpad = (w + target_stride - 1) / target_stride * target_stride - w;
    int hpad = (h + target_stride - 1) / target_stride * target_stride - h;
    ncnn::Mat in_pad;
    ncnn::copy_make_border(in, in_pad, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2, ncnn::BORDER_CONSTANT, 114.f);

    const float mean_vals[3] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
    const float norm_vals[3] = {1 / 0.229f / 255.f, 1 / 0.224f / 255.f, 1 / 0.225f / 255.f};
    in_pad.substract_mean_normalize(mean_vals, norm_vals);

    // 创建检测模型 extractor，并绑定内存池。
    ncnn::Extractor ex = ppocrv5_det.create_extractor();
    ex.set_light_mode(true);
    ex.set_blob_allocator(&det_blob_allocator);
    ex.set_workspace_allocator(&det_workspace_allocator);

    const char* input_name = ppocrv6_mode ? "input" : "in0";
    const char* output_name = ppocrv6_mode ? "output" : "out0";
    ex.input(input_name, in_pad);

    ncnn::Mat out;
    int ret = ex.extract(output_name, out);
    if (ret != 0 || out.empty()) {
        ex.clear();
        return -1;
    }

    const float denorm_vals[1] = {255.f};
    out.substract_mean_normalize(0, denorm_vals);

    if (out.w <= 0 || out.h <= 0) {
        ex.clear();
        return -2;
    }
    cv::Mat pred(out.h, out.w, CV_8UC1);
    out.to_pixels(pred.data, ncnn::Mat::PIXEL_GRAY);

    // 模型输出转二值图，后面用轮廓找文字框。
    cv::Mat bitmap;
    const float threshold = ppocrv6_mode ? 0.2f : 0.3f;
    cv::threshold(pred, bitmap, threshold * 255, 255, cv::THRESH_BINARY);

        // 从二值图轮廓生成旋转文字框。
    {
        // should use dbnet post process, but I think unclip process is difficult to write
        // so simply implement expansion. This may lose detection accuracy
        // original implementation can be referenced
        // https://github.com/MhLiao/DB/blob/master/structure/representers/seg_detector_representer.py

        const float box_thresh = ppocrv6_mode ? 0.45f : 0.6f;
        const float enlarge_ratio = ppocrv6_mode ? 1.40f : 1.95f;

        const float min_size = 3 * scale;
        const int max_candidates = 1000;

        std::vector<std::vector<cv::Point> > contours;
        std::vector<cv::Vec4i> hierarchy;

        cv::findContours(bitmap, contours, hierarchy, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);

        contours.resize(std::min(contours.size(), (size_t)max_candidates));

        for (size_t i = 0; i < contours.size(); i++)
        {
            const std::vector<cv::Point>& contour = contours[i];
            if (contour.size() <= 2)
                continue;

            double score = contour_score(pred, contour);
            if (score < box_thresh)
                continue;

            cv::RotatedRect rrect = cv::minAreaRect(contour);

            float rrect_maxwh = std::max(rrect.size.width, rrect.size.height);
            if (rrect_maxwh < min_size)
                continue;

            int orientation = 0;
            if (rrect.angle >= -30 && rrect.angle <= 30 && rrect.size.height > rrect.size.width * 2.7)
            {
                // vertical text
                orientation = 1;
            }
            if ((rrect.angle <= -60 || rrect.angle >= 60) && rrect.size.width > rrect.size.height * 2.7)
            {
                // vertical text
                orientation = 1;
            }

            if (rrect.angle < -30)
            {
                // make orientation from -90 ~ -30 to 90 ~ 150
                rrect.angle += 180;
            }
            if (orientation == 0 && rrect.angle < 30)
            {
                // make it horizontal
                rrect.angle += 90;
                std::swap(rrect.size.width, rrect.size.height);
            }
            if (orientation == 1 && rrect.angle >= 60)
            {
                // make it vertical
                rrect.angle -= 90;
                std::swap(rrect.size.width, rrect.size.height);
            }

            // enlarge
            rrect.size.height += rrect.size.width * (enlarge_ratio - 1);
            rrect.size.width *= enlarge_ratio;

            // 把模型输入坐标还原回原图坐标。
            rrect.center.x = (rrect.center.x - (wpad / 2)) / scale;
            rrect.center.y = (rrect.center.y - (hpad / 2)) / scale;
            rrect.size.width = (rrect.size.width) / scale;
            rrect.size.height = (rrect.size.height) / scale;

            Object obj;
            obj.rrect = rrect;
            obj.orientation = orientation;
            obj.prob = score;
            objects.push_back(obj);
        }
    }

    ex.clear();
    return 0;
}

int PPOCREngine::recognize(const cv::Mat& rgb, Object& object)
{
    // 单条文字识别。可并发：每个工作线程各自 create_extractor，并用 thread_local 内存池。
    object.text.clear();
    object.text_value.clear();
    object.text_scores.clear();

    cv::Mat roi = get_rotate_crop_image(rgb, object, rec_max_width);
    if (roi.empty() || roi.cols <= 0 || roi.rows <= 0) {
        return -1;
    }

    ncnn::Mat in = ncnn::Mat::from_pixels(roi.data, ncnn::Mat::PIXEL_BGR, roi.cols, roi.rows);

    // ~/.paddlex/official_models/PP-OCRv5_mobile_rec/inference.yml
    const float mean_vals[3] = {127.5, 127.5, 127.5};
    const float norm_vals[3] = {1.0 / 127.5, 1.0 / 127.5, 1.0 / 127.5};
    in.substract_mean_normalize(mean_vals, norm_vals);

    // 创建识别模型 extractor，并绑定内存池。
    // ⚠️ 必须用 thread_local：成员里的 rec_*_allocator 是非线程安全的 UnlockedPoolAllocator，
    // 多 worker 并发共享会踩内存 —— 这就是历史上「共享 rec 模型并发崩溃」的根因。
    static thread_local ncnn::UnlockedPoolAllocator tl_blob_allocator;
    static thread_local ncnn::UnlockedPoolAllocator tl_workspace_allocator;
    ncnn::Extractor ex = ppocrv5_rec.create_extractor();
    ex.set_light_mode(true);
    ex.set_blob_allocator(&tl_blob_allocator);
    ex.set_workspace_allocator(&tl_workspace_allocator);

    const char* input_name = ppocrv6_mode ? "input" : "in0";
    const char* output_name = ppocrv6_mode ? "output" : "out0";
    ex.input(input_name, in);

    ncnn::Mat out;
    int ret = ex.extract(output_name, out);
    if (ret != 0 || out.empty() || out.w <= 0 || out.h <= 0) {
        ex.clear();
        return -2;
    }

    // CTC 解码：连续重复 token 合并，0 视为空白。
    int last_token = 0;

    for (int i = 0; i < out.h; i++)
    {
        const float* p = out.row(i);

        int index = 0;
        float max_score = -9999.f;
        for (int j = 0; j < out.w; j++)
        {
            float score = *p++;
            if (score > max_score)
            {
                max_score = score;
                index = j;
            }
        }

        if (last_token == index) // CTC rule, if index is same as last one, they will be merged into one token
                continue;

        last_token = index;

        if (index <= 0)
            continue;

        if (ppocrv6_mode) {
            if (index < static_cast<int>(dynamic_dict.size())) {
                object.text_value += dynamic_dict[index];
                object.text_scores.push_back(max_score);
            }
            continue;
        }

        Character ch;
        ch.id = index - 1;
        ch.prob = max_score;

        object.text.push_back(ch);
    }

    ex.clear();
    return 0;
}

int PPOCREngine::detect_and_recognize(const cv::Mat& rgb, std::vector<Object>& objects)
{
    // 官方示例式的检测+并行识别。当前桥接层没有使用它，保留作参考。
    detect(rgb, objects);

    #pragma omp parallel for num_threads(ncnn::get_big_cpu_count()) schedule(dynamic)
    for (size_t i = 0; i < objects.size(); i++)
    {
        recognize(rgb, objects[i]);
    }

    return 0;
}

int PPOCREngine::draw(cv::Mat& rgb, const std::vector<Object>& objects)
{
    // 调试用：把检测框画到图片上。
    static const cv::Scalar colors[] = {
        cv::Scalar(176, 39, 156),
        cv::Scalar(183, 58, 103),
        cv::Scalar(181, 81, 63),
        cv::Scalar(243, 150, 33),
        cv::Scalar(244, 169, 3),
        cv::Scalar(212, 188, 0),
        cv::Scalar(136, 150, 0),
        cv::Scalar(80, 175, 76),
        cv::Scalar(74, 195, 139),
        cv::Scalar(57, 220, 205),
        cv::Scalar(59, 235, 255),
        cv::Scalar(7, 193, 255),
        cv::Scalar(0, 152, 255),
        cv::Scalar(34, 87, 255),
        cv::Scalar(72, 85, 121),
        cv::Scalar(158, 158, 158),
        cv::Scalar(139, 125, 96)
    };

    for (size_t i = 0; i < objects.size(); i++)
    {
        const Object& obj = objects[i];

        const cv::Scalar& color = colors[i % 17];

        // fprintf(stderr, "%s %.5f at %.2f %.2f %.2f x %.2f  @ %.2f  =  ", obj.orientation == 0 ? "H" : "V", obj.prob,
        //         obj.rrect.center.x, obj.rrect.center.y, obj.rrect.size.width, obj.rrect.size.height, obj.rrect.angle);

        cv::Point2f corners[4];
        obj.rrect.points(corners);
        cv::line(rgb, corners[0], corners[1], color);
        cv::line(rgb, corners[1], corners[2], color);
        cv::line(rgb, corners[2], corners[3], color);
        cv::line(rgb, corners[3], corners[0], color);

        // std::string text;
        // for (size_t j = 0; j < objects[i].text.size(); j++)
        // {
        //     const Character& ch = objects[i].text[j];
        //     if (ch.id >= character_dict_size)
        //         continue;
        //
        //     text += character_dict[ch.id];
        // }
        // fprintf(stderr, "%s\n", text.c_str());
    }

    return 0;
}
