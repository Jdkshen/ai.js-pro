#pragma once
#include "imgui.h"

inline const ImWchar* imGuiCjkRanges(ImFontAtlas* atlas) {
    // ImGui keeps this pointer until Build; retain the ranges for every context.
    // The common Chinese subset omits some control labels (e.g. 钮).
    static const ImVector<ImWchar> ranges = [atlas]() {
        ImFontGlyphRangesBuilder builder;
        builder.AddRanges(atlas->GetGlyphRangesChineseSimplifiedCommon());
        builder.AddText("按钮暂停重试识别定位清零关闭拖动取消建议更多原生截图隔离象棋帅将仕士相象马车炮兵卒帥將俥傌砲");
        ImVector<ImWchar> result;
        builder.BuildRanges(&result);
        return result;
    }();
    return ranges.Data;
}

// Build before the first NewFrame: an unsupported Android TTC can leave the
// default font without ContainerAtlas even though AddFontFromFileTTF succeeds.
inline bool buildImGuiFonts(ImGuiIO& io, const char* path, float size, bool* fallback) {
    *fallback = false;
    ImFontConfig latin; latin.SizePixels = size;
    io.Fonts->AddFontDefault(&latin);
    if (path && *path) {
        ImFontConfig cjk; cjk.MergeMode = true;
        io.Fonts->AddFontFromFileTTF(path, size, &cjk, imGuiCjkRanges(io.Fonts));
    }
    if (io.Fonts->Build() && io.Fonts->Fonts.Size > 0 && io.Fonts->Fonts[0]->IsLoaded()) return true;
    *fallback = true;
    io.Fonts->Clear(); io.FontDefault = nullptr;
    io.Fonts->AddFontDefault(&latin);
    return io.Fonts->Build() && io.Fonts->Fonts.Size > 0 && io.Fonts->Fonts[0]->IsLoaded();
}
