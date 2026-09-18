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

#ifndef PPOCR_ENGINE_H
#define PPOCR_ENGINE_H

#include <android/asset_manager.h>

#include <opencv2/core/core.hpp>

#include <net.h>
#include <allocator.h>
#include <string>
#include <vector>

struct Character
{
    // v5 字典里的字符下标。
    int id;
    // 当前字符的置信度。
    float prob;
};

struct Object
{
    // 检测出来的旋转文字框。
    cv::RotatedRect rrect;
    // 文字方向：0 横排，1 竖排。
    int orientation;
    // 检测框置信度。
    float prob;
    // v5 识别结果，按字符 id 保存。
    std::vector<Character> text;
    // v6 识别结果，直接保存字符串。
    std::string text_value;
    // v6 每个 token 的分数。
    std::vector<float> text_scores;
};

// PPOCREngine 是真正的 ncnn OCR 引擎，负责加载模型、检测文字框、识别文字。
class PPOCREngine
{
public:
    PPOCREngine();
    ~PPOCREngine();

    // 从文件路径加载 det/rec 模型。
    int load(const char* det_parampath, const char* det_modelpath, const char* rec_parampath, const char* rec_modelpath, bool use_fp16 = false, bool use_gpu = false);
    // 从 Android assets 加载模型，当前项目主要使用文件路径版本。
    int load(AAssetManager* mgr, const char* det_parampath, const char* det_modelpath, const char* rec_parampath, const char* rec_modelpath, bool use_fp16 = false, bool use_gpu = false);

    // 设置检测输入尺寸，越大越清晰但越慢。
    void set_target_size(int target_size);

    // rec 裁剪宽度上限（<=0 用内置默认 1024）：长行会被压到这个宽度。
    // 压得越小越快（rec 耗时 ∝ 宽度），但长行文字被挤扁，精度风险大 —— 需实测。
    void set_rec_max_width(int max_width);

    // 检测文字框，只找位置，不识别文字。
    int detect(const cv::Mat& rgb, std::vector<Object>& objects);

    // 对单个文字框做文字识别。
    int recognize(const cv::Mat& rgb, Object& object);

    // 检测并识别。当前桥接层没有直接用它，因为共享模型并发识别不稳定。
    int detect_and_recognize(const cv::Mat& rgb, std::vector<Object>& objects);
    // 调试绘制检测框。
    int draw(cv::Mat& rgb, const std::vector<Object>& objects);
    // 清理 ncnn 临时 allocator，减少长时间运行内存累积。
    void clear_runtime_memory();

protected:
    // 检测模型和识别模型。
    ncnn::Net ppocrv5_det;
    ncnn::Net ppocrv5_rec;
    // ncnn 内存池。每次识别结束会 clear，避免缓存不断增长。
    ncnn::UnlockedPoolAllocator det_blob_allocator;
    ncnn::UnlockedPoolAllocator det_workspace_allocator;
    ncnn::UnlockedPoolAllocator rec_blob_allocator;
    ncnn::UnlockedPoolAllocator rec_workspace_allocator;
    // 检测输入尺寸。
    int target_size;
    // rec 裁剪宽度上限（默认 1024）。
    int rec_max_width = 1024;
    // 当前是否是 v6 模型。v6 的输入/输出名和字典处理不同。
    bool ppocrv6_mode;
    // v6 动态字典，从 ppocr_keys_v6_tiny.txt 读取。
    std::vector<std::string> dynamic_dict;
};

#endif // PPOCR_ENGINE_H
